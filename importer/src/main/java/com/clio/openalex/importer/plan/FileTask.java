package com.clio.openalex.importer.plan;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class FileTask {
    String url;
    String status;
    Entity entity;
    Long jobId;
    Long fileId;
    String errorMsg;
    LocalDate date;
    int part;
    Long record_count;
    Long read_count;
    LocalDateTime last_heartbeat;

    public LocalDateTime getLast_heartbeat() {
        return last_heartbeat;
    }

    public void setLast_heartbeat(LocalDateTime last_heartbeat) {
        this.last_heartbeat = last_heartbeat;
    }

    public Long getRecord_count() {
        return record_count;
    }

    public void setRecord_count(Long record_count) {
        this.record_count = record_count;
    }

    public Long getRead_count() {
        return read_count;
    }

    public void setRead_count(Long read_count) {
        this.read_count = read_count;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Entity getEntity() {
        return entity;
    }

    public void setEntity(Entity entity) {
        this.entity = entity;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public int getPart() {
        return part;
    }

    public void setPart(int part) {
        this.part = part;
    }

    public Long getRecordCount() {
        return record_count;
    }

    public void setRecordCount(Long recordCount) {
        this.record_count = recordCount;
    }

    public Long getReadCount() {
        return read_count;
    }

    public void setReadCount(Long readCount) {
        this.read_count = readCount;
    }
}
