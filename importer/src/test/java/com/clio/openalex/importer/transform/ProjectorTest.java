package com.clio.openalex.importer.transform;

import com.clio.openalex.importer.config.EntityConfig;
import com.clio.openalex.importer.db.UpsertSql;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 纯函数核心的契约测试: 配置来自真实白名单, 样本来自真实快照(2026-02-09 分区前 3 行)。 */
class ProjectorTest {

    static EntityConfig cfg;
    static Projector projector;

    @BeforeAll
    static void setup() throws Exception {
        Path p = Path.of("..", "config", "entities", "sources.json");
        assertTrue(Files.exists(p), "找不到 " + p.toAbsolutePath() + " — 测试须在 importer/ 目录下运行");
        cfg = EntityConfig.load(p);
        projector = new Projector(cfg);
    }

    @Test
    void 白名单封口_36字段全集() {
        assertEquals(36, cfg.knownFields().size());
        assertEquals(8, cfg.typedColumns.size());
        assertEquals(24, cfg.payloadFields.size());
        assertEquals(List.of("works_api_url", "topic_share"), cfg.dropFields);
    }

    @Test
    void 真实样本_三行全通过_无未知字段_无丢弃字段() throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ProjectorTest.class.getResourceAsStream("/sources_sample_3.jsonl"),
                StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                ProjectionResult res = projector.project(line);
                assertTrue(res.unknownFields().isEmpty(),
                        "真实样本出现未知字段: " + res.unknownFields());
                ProjectedRow row = res.row();
                assertTrue(row.id() > 0);
                assertTrue(row.sourceUpdatedAt().toString().startsWith("2026-"));
                assertFalse(row.payloadJson().contains("works_api_url"), "drop 字段泄入 payload");
                assertFalse(row.payloadJson().contains("topic_share"), "drop 字段泄入 payload");
                n++;
            }
            assertEquals(3, n);
        }
    }

    @Test
    void 版本时间戳_微秒截断到毫秒() throws Exception {
        ProjectionResult res = projector.project(minimal()
                .replace("2026-06-26T10:02:15.000Z", "2026-06-26T10:02:15.123456Z"));
        assertEquals(Instant.parse("2026-06-26T10:02:15.123Z"), res.row().sourceUpdatedAt());
    }

    @Test
    void hash确定性_同输入同值_改一值即变() throws Exception {
        long h1 = projector.project(minimal()).row().contentHash();
        long h2 = projector.project(minimal()).row().contentHash();
        long h3 = projector.project(minimal().replace("\"Test Journal\"", "\"Other Journal\""))
                .row().contentHash();
        assertEquals(h1, h2, "同一输入两次投影 hash 必须一致");
        assertNotEquals(h1, h3, "display_name 变化必须改变 hash");
        assertTrue(h1 >= 0, "hash 契约取非负 63 位");
    }

    @Test
    void 未知字段_触发采样告警且不中断() throws Exception {
        ProjectionResult res = projector.project(
                minimal().replace("{\"id\"", "{\"brand_new_field\":1,\"id\""));
        assertEquals(java.util.Set.of("brand_new_field"), res.unknownFields());
        assertTrue(res.row().id() > 0);
    }

    @Test
    void 毒行_缺id_坏时间戳_抛TRANSFORM异常() {
        assertThrows(Projector.ProjectionException.class,
                () -> projector.project("{\"updated_date\":\"2026-01-01T00:00:00Z\"}"));
        assertThrows(Projector.ProjectionException.class,
                () -> projector.project(minimal().replace("2026-06-26T10:02:15.000Z", "not-a-date")));
        assertThrows(Projector.ProjectionException.class,
                () -> projector.project("{\"id\":\"https://openalex.org/W123\",\"updated_date\":\"2026-01-01T00:00:00Z\"}"),
                "W 前缀混入 sources 必须拒收");
    }

    @Test
    void upsert语句_守卫只引用版本列_且版本列最后赋值() {
        String sql = UpsertSql.build(cfg, 2);
        assertTrue(sql.contains("AS new"), "MySQL 8.4 必须用行别名, VALUES() 已废弃");
        assertFalse(sql.contains("new.content_hash >"), "hash 禁止参与 SQL 条件");
        assertFalse(sql.contains("new.content_hash <"), "hash 禁止参与 SQL 条件");
        int lastGuardPos = sql.lastIndexOf("source_updated_at = IF(");
        for (String col : List.of("display_name", "payload", "content_hash")) {
            assertTrue(sql.indexOf(col + " = IF(") < lastGuardPos,
                    col + " 必须在 source_updated_at 之前赋值");
        }
        assertEquals(2, sql.split("CAST\\(\\? AS JSON\\)", -1).length - 1, "每行一个 JSON 占位符");
    }

    private static String minimal() {
        return """
                {"id":"https://openalex.org/S42","updated_date":"2026-06-26T10:02:15.000Z",\
                "display_name":"Test Journal","type":"journal","issn_l":"1234-5678",\
                "country_code":"CN","is_oa":true,"is_in_doaj":false,"works_count":10,\
                "cited_by_count":100,"ids":{"openalex":"https://openalex.org/S42"},\
                "counts_by_year":[],"topics":[]}""";
    }
}
