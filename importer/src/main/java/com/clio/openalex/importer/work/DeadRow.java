package com.clio.openalex.importer.work;

import java.time.Instant;

/**
 * 死信行：单行 Poisoned 数据的记录。
 *
 * <p>Poisoned = 数据本身有问题的单行（解不开的 JSON / 缺 id），
 * 按 worker 阶段设计不让一行脏数据拖垮整个文件——文件照常 done，坏行落到这里。
 *
 * <p>消费方式：不是常驻自动重投，而是"批量 + 人工把关 + 终态驱动"。
 * 每条被有效消费一次进入终态：
 * <ul>
 *   <li>{@link #STATUS_UNRESOLVED} 待分诊</li>
 *   <li>{@link #STATUS_RESOLVED} 判定为我们误判 → 改解析器 → 重放 {@link #rawLine} 成功入库</li>
 *   <li>{@link #STATUS_IGNORED} 判定为真·源数据坏 → 接受丢失，保留作审计</li>
 * </ul>
 *
 * <p>幂等：崩溃/超时后整文件会从头重放，同一物理坏行会再次命中。
 * 靠 (file_id, line_no) 唯一键 + insert ignore 把重放收敛成一条。
 */
public class DeadRow {

    public static final String STATUS_UNRESOLVED = "unresolved";
    public static final String STATUS_RESOLVED   = "resolved";
    public static final String STATUS_IGNORED    = "ignored";

    private Long    id;          // 自增主键
    private Long    fileId;      // 属于哪个 file_task；幂等键组成，来自 FileTask.getFileId()
    private Long    lineNo;      // 文件内绝对行号；幂等键组成
    private String  rawLine;     // 原始行原文，重放唯一数据来源
    private String  errorMsg;    // 死因明细：解析异常存 message，缺 id 存固定串
    private String  status;      // unresolved / resolved / ignored
    private String  entityType;  // 重放调 transform 时决定 entity_type
    private Instant createdAt;
    private Instant updatedAt;

    /** 落库时的构造：poison 检测点填充，status 默认 unresolved；id/时间戳由数据库生成。 */
    public DeadRow(Long fileId, Long lineNo, String rawLine, String errorMsg, String entityType) {
        this.fileId = fileId;
        this.lineNo = lineNo;
        this.rawLine = rawLine;
        this.errorMsg = errorMsg;
        this.entityType = entityType;
        this.status = STATUS_UNRESOLVED;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public Long getLineNo() {
        return lineNo;
    }

    public void setLineNo(Long lineNo) {
        this.lineNo = lineNo;
    }

    public String getRawLine() {
        return rawLine;
    }

    public void setRawLine(String rawLine) {
        this.rawLine = rawLine;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
