package com.clio.openalex.importer.transform;

import java.util.Set;

/**
 * 投影输出: 行本体 + 未知字段集合。
 * unknownFields 非空时记录仍正常入库, 由调用方发 schema_drift 死信采样告警(契约 C5)。
 */
public record ProjectionResult(ProjectedRow row, Set<String> unknownFields) {
}
