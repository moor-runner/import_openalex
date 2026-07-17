package com.clio.openalex.importer.transform;

import java.time.Instant;
import java.util.LinkedHashMap;

/**
 * 单行投影结果 = 落入 MySQL 的完整一行(契约 C1/C2/C4)。
 *
 * @param id              短号数字部分, 主键
 * @param sourceUpdatedAt 上游 updated_date, UTC, 已截断到毫秒
 * @param contentHash     白名单投影 64 位指纹(实现取 63 位有效, 契约允许"实现内自洽")
 * @param typedValues     列名 → 值(String/Boolean/Long/null), 保持 config 声明顺序
 * @param payloadJson     载荷列紧凑 JSON 文本
 */
public record ProjectedRow(
        long id,
        Instant sourceUpdatedAt,
        long contentHash,
        LinkedHashMap<String, Object> typedValues,
        String payloadJson) {
}
