package com.clio.openalex.importer.plan;

/**
 * 契约 C6: 读 per-entity manifest.json → 建 sync_job + 逐文件 file_task。
 * 幂等: 任务身份 (job_id, s3_key); 文件指纹 (content_length, record_count),
 * 指纹一致不动, 指纹变化的 DONE 任务重置 PENDING; --incremental 只收水位之后的分区。
 */
public final class Planner {

    public static int run(String[] args) {
        throw new UnsupportedOperationException(
                "Planner 待实现(契约 C6): manifest → sync_job + file_task, 指纹幂等");
    }

    private Planner() {}
}
