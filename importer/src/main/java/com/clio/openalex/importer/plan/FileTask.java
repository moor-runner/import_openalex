package com.clio.openalex.importer.plan;

import java.time.LocalDate;

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
}
