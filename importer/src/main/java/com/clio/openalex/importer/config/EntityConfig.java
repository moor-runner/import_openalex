package com.clio.openalex.importer.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 白名单防腐层配置(契约 C5), 与 config/entities/<entity>.json 一一对应。
 * consumed ∪ typed ∪ payload ∪ drop = 快照实测字段全集; 此文件是唯一权威。
 * 注意: typed_columns 与 payload_fields 的声明顺序参与 content_hash(契约 C4), 不可随意重排。
 */
public class EntityConfig {

    public String entity;
    public String table;                       // 形如 openalex.sources
    public String idPrefix;                    // S / W / A ...
    public String versionField;                // updated_date

    public Map<String, String> consumedFields = new LinkedHashMap<>();
    public LinkedHashMap<String, TypedColumn> typedColumns = new LinkedHashMap<>();
    public List<String> payloadFields = List.of();
    public List<String> dropFields = List.of();

    public static class TypedColumn {
        public String column;
        public String type;                    // string / bool / int
        public Integer maxLen;
        public Boolean truncate;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false); // 容忍 _notes 等注释键

    public static EntityConfig load(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), EntityConfig.class);
    }

    /** 已知字段全集; 快照记录中不在此集合的顶层字段 → schema_drift 采样告警(契约 C5)。 */
    public Set<String> knownFields() {
        Set<String> s = new TreeSet<>();
        s.addAll(consumedFields.keySet());
        s.addAll(typedColumns.keySet());
        s.addAll(payloadFields);
        s.addAll(dropFields);
        return s;
    }

    /** 数据面裸表名(去 schema 前缀), 供 upsert 守卫里的列引用使用。 */
    public String bareTable() {
        int i = table.lastIndexOf('.');
        return i < 0 ? table : table.substring(i + 1);
    }
}
