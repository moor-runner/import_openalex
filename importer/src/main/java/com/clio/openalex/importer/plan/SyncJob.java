package com.clio.openalex.importer.plan;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class SyncJob {
    Long jobId;
    Entity entity;
    LocalDate snapshot;
    LocalDateTime created_at;
}
