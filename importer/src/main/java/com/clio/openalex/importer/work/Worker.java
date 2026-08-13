package com.clio.openalex.importer.work;

/**
 * 契约 C3/C6: SKIP LOCKED 领任务(001 伪 SQL A) → 心跳续租(B) →
 * LineSource 流式逐行: 解析(PARSE)→投影(TRANSFORM, Projector)→攒批 →
 * 预读分类 NEW/APPLY/STALE/SUPPRESS → UpsertSql 批量写(UPSERT) →
 * 全文件完成后一次性写计数并置 DONE(恒等式: read = upserted+stale+suppressed+failed)。
 * 毒行 → dead_letter, 不中断文件。
 */
public final class Worker {

    public static int run(String[] args) {
        throw new UnsupportedOperationException(
                "Worker 待实现(契约 C3/C6): 领任务→流式导入→两层幂等→计数 DONE");
    }

    private Worker() {}
}
