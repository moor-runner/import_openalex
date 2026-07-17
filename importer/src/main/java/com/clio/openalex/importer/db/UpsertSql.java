package com.clio.openalex.importer.db;

import com.clio.openalex.importer.config.EntityConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 契约 C3 第二层防线的 SQL 生成器。
 *
 * 赋值顺序陷阱(必须遵守): ON DUPLICATE KEY UPDATE 的赋值从左到右依次生效,
 * 先赋的新值会被后面的条件看到。因此守卫条件只允许引用 source_updated_at,
 * 且该列必须最后赋值; content_hash 不进 SQL 条件(判断已上移客户端预读)。
 */
public final class UpsertSql {

    /** 列顺序: id, source_updated_at, content_hash, <typed...>, payload */
    public static List<String> columns(EntityConfig cfg) {
        List<String> cols = new ArrayList<>(List.of("id", "source_updated_at", "content_hash"));
        cfg.typedColumns.values().forEach(tc -> cols.add(tc.column));
        cols.add("payload");
        return cols;
    }

    /** 批量幂等 upsert 语句, batchRows 组占位符; payload 占位符带 CAST(? AS JSON) */
    public static String build(EntityConfig cfg, int batchRows) {
        if (batchRows < 1) throw new IllegalArgumentException("batchRows=" + batchRows);
        List<String> cols = columns(cfg);
        String t = cfg.bareTable();

        StringBuilder sb = new StringBuilder(1024);
        sb.append("INSERT INTO ").append(cfg.table)
          .append(" (").append(String.join(", ", cols)).append(")\nVALUES ");

        String row = "(" + "?, ".repeat(cols.size() - 1) + "CAST(? AS JSON))";
        for (int i = 0; i < batchRows; i++) {
            if (i > 0) sb.append(",\n       ");
            sb.append(row);
        }
        sb.append(" AS new\nON DUPLICATE KEY UPDATE\n");

        // 业务列(typed + payload) + content_hash 在前, 守卫条件读到的仍是旧 source_updated_at
        List<String> guarded = new ArrayList<>();
        cfg.typedColumns.values().forEach(tc -> guarded.add(tc.column));
        guarded.add("payload");
        guarded.add("content_hash");
        for (String col : guarded) {
            sb.append("  ").append(guard(col, t)).append(",\n");
        }
        // source_updated_at 必须最后赋值
        sb.append("  ").append(guard("source_updated_at", t));
        return sb.toString();
    }

    private static String guard(String col, String table) {
        return col + " = IF(new.source_updated_at > " + table + ".source_updated_at, new."
                + col + ", " + table + "." + col + ")";
    }

    private UpsertSql() {}
}
