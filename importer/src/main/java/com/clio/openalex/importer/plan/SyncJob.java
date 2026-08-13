package com.clio.openalex.importer.plan;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class SyncJob {
    Long jobId;
    Entity entity;
    LocalDate snapshot;
    LocalDateTime created_at;

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public Entity getEntity() {
        return entity;
    }

    public void setEntity(Entity entity) {
        this.entity = entity;
    }

    public LocalDate getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(LocalDate snapshot) {
        this.snapshot = snapshot;
    }

    public LocalDateTime getCreatedAt() {
        return created_at;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.created_at = createdAt;
    }
}
