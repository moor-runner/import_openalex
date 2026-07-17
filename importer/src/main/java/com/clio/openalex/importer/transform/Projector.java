package com.clio.openalex.importer.transform;

import com.clio.openalex.importer.config.EntityConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 导入 = 单行纯函数, 无 join(设计心智模型): 上游 JSON 行 → ProjectedRow。
 * 覆盖契约 C1(ID)/C2(版本)/C4(content_hash)/C5(白名单投影)。
 * JSON 语法错误由调用方在读行阶段捕获(stage=PARSE); 本类抛出的都是 stage=TRANSFORM。
 */
public final class Projector {

    private static final String ID_HOST = "https://openalex.org/";

    // USE_BIG_DECIMAL_FOR_FLOATS: 浮点保留原文精度, canonical 串对同一输入字节稳定(契约 C4)
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    private final EntityConfig cfg;
    private final Set<String> knownFields;

    public Projector(EntityConfig cfg) {
        this.cfg = cfg;
        this.knownFields = cfg.knownFields();
    }

    public ProjectionResult project(String line) throws ProjectionException {
        JsonNode root;
        try {
            root = MAPPER.readTree(line);
        } catch (Exception e) {
            throw new ProjectionException("json_decode", e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new ProjectionException("not_object", "顶层不是 JSON 对象");
        }

        long id = parseId(root.path("id"));
        Instant ts = parseVersion(root.path(cfg.versionField));

        // 类型化业务列: 保持 config 声明顺序(参与 hash)
        LinkedHashMap<String, Object> typed = new LinkedHashMap<>();
        List<Object> typedHashInputs = new ArrayList<>();
        for (var e : cfg.typedColumns.entrySet()) {
            String field = e.getKey();
            EntityConfig.TypedColumn tc = e.getValue();
            Object v = convert(field, tc, root.path(field));
            typed.put(tc.column, v);
            typedHashInputs.add(v);
        }

        // JSON 载荷列: 白名单其余字段原样透传, 声明顺序写入(MySQL 会重排键序, hash 不依赖回读)
        ObjectNode payload = MAPPER.createObjectNode();
        for (String f : cfg.payloadFields) {
            if (root.has(f)) {
                payload.set(f, root.get(f));
            }
        }

        // 防腐层: 未知顶层字段检测(契约 C5), 记录仍正常处理
        Set<String> unknown = new TreeSet<>();
        root.fieldNames().forEachRemaining(n -> {
            if (!knownFields.contains(n)) unknown.add(n);
        });

        long hash = contentHash(typedHashInputs, payload);
        ProjectedRow row = new ProjectedRow(id, ts, hash, typed, payload.toString());
        return new ProjectionResult(row, unknown);
    }

    /** 契约 C1: 'https://openalex.org/S15574646' → 15574646 */
    private long parseId(JsonNode idNode) throws ProjectionException {
        String s = idNode.isTextual() ? idNode.asText() : null;
        String prefix = ID_HOST + cfg.idPrefix;
        if (s == null || !s.startsWith(prefix)) {
            throw new ProjectionException("bad_id", "id=" + s);
        }
        try {
            return Long.parseLong(s.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new ProjectionException("bad_id", "id=" + s);
        }
    }

    /** 契约 C2: ISO8601(UTC) 微秒截断到毫秒 */
    private Instant parseVersion(JsonNode tsNode) throws ProjectionException {
        String s = tsNode.isTextual() ? tsNode.asText() : null;
        if (s == null || s.isBlank()) {
            throw new ProjectionException("bad_" + cfg.versionField, "缺失版本字段");
        }
        try {
            return Instant.parse(s).truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            throw new ProjectionException("bad_" + cfg.versionField, "值=" + s);
        }
    }

    private Object convert(String field, EntityConfig.TypedColumn tc, JsonNode v)
            throws ProjectionException {
        if (v.isMissingNode() || v.isNull()) return null;
        switch (tc.type) {
            case "string" -> {
                if (!v.isTextual()) throw mismatch(field, v);
                String s = v.asText();
                if (tc.maxLen != null && s.length() > tc.maxLen) {
                    if (Boolean.TRUE.equals(tc.truncate)) return s.substring(0, tc.maxLen);
                    throw new ProjectionException("overflow", field + " 长度 " + s.length() + " > " + tc.maxLen);
                }
                return s;
            }
            case "bool" -> {
                if (!v.isBoolean()) throw mismatch(field, v);
                return v.asBoolean();
            }
            case "int" -> {
                if (!v.isIntegralNumber()) throw mismatch(field, v);
                return v.asLong();
            }
            default -> throw new ProjectionException("bad_config", "未知列类型 " + tc.type);
        }
    }

    private ProjectionException mismatch(String field, JsonNode v) {
        return new ProjectionException("type_mismatch", field + "=" + v.getNodeType());
    }

    /**
     * 契约 C4: 规范化投影串 → sha256 前 8 字节 → 非负 long(63 位有效)。
     * 串结构: 对 typed(声明顺序)与 payload(声明顺序)的每个字段:
     *   字段名 + \x1f + 值 + \x1e
     * 值: 缺失字段 = 哨兵 \x00; JSON null = "null"; 其余 = 紧凑 JSON(对象键排序)。
     * 只在导入器内计算, 永不从 MySQL 回读 payload 重算(JSON 类型重排键序)。
     */
    private long contentHash(List<Object> typedValues, ObjectNode payload) {
        StringBuilder sb = new StringBuilder(256);
        int i = 0;
        for (String field : cfg.typedColumns.keySet()) {
            Object v = typedValues.get(i++);
            sb.append(field).append('\u001f');
            if (v == null) {
                sb.append('\u0000');
            } else if (v instanceof String s) {
                appendJsonString(sb, s);
            } else {
                sb.append(v); // Boolean / Long
            }
            sb.append('\u001e');
        }
        for (String field : cfg.payloadFields) {
            sb.append(field).append('\u001f');
            JsonNode v = payload.get(field);
            if (v == null) {
                sb.append('\u0000'); // 上游整个字段缺失(实测 100% presence, 防御性处理)
            } else {
                canonicalize(v, sb);
            }
            sb.append('\u001e');
        }
        byte[] digest = sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
        long h = 0;
        for (int b = 0; b < 8; b++) {
            h = (h << 8) | (digest[b] & 0xffL);
        }
        return h & Long.MAX_VALUE;
    }

    /** 递归规范化: 对象键按字典序, 数组保序, 标量用 Jackson 紧凑输出(BigDecimal 保留原文) */
    static void canonicalize(JsonNode n, StringBuilder sb) {
        if (n.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            n.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            sb.append('{');
            boolean first = true;
            for (var e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                appendJsonString(sb, e.getKey());
                sb.append(':');
                canonicalize(e.getValue(), sb);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) sb.append(',');
                canonicalize(n.get(i), sb);
            }
            sb.append(']');
        } else {
            sb.append(n.toString()); // 标量/null: JsonNode.toString() 即合法紧凑 JSON
        }
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** stage=TRANSFORM 的死信载荷(契约 C5); errorType 对应 dead_letter.error_type */
    public static final class ProjectionException extends Exception {
        public final String errorType;

        public ProjectionException(String errorType, String msg) {
            super(errorType + ": " + msg);
            this.errorType = errorType;
        }
    }
}
