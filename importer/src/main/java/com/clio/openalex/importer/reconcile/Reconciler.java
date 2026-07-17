package com.clio.openalex.importer.reconcile;

/**
 * 契约 C7: 三层对账(FILE_ACCOUNTING 精确 / 实体级 Σread==manifest / TOTAL_COUNT 容差告警)
 * + 结果集指纹; 全 PASS → job SUCCEEDED 与推水位同事务。
 */
public final class Reconciler {

    public static int run(String[] args) {
        throw new UnsupportedOperationException(
                "Reconciler 待实现(契约 C7): 三层对账 → 推水位(同事务)");
    }

    /** 验收 A6: 单条 SQL 的进度一览 */
    public static int status(String[] args) {
        throw new UnsupportedOperationException(
                "status 待实现(验收 A6): file_task 按状态聚合行数/字节/死信");
    }

    private Reconciler() {}
}
