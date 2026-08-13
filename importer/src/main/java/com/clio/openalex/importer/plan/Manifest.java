package com.clio.openalex.importer.plan;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class Manifest {
    Entity entity;
    String date;
    Long count;
    Long length;
    List<FileEntry> fileEntries;
    public Manifest() {

    }

    public Manifest(Entity entity, String date, Long count, Long length, List<FileEntry> fileEntries) {
        this.entity = entity;
        this.date = date;
        this.count = count;
        this.length = length;
        this.fileEntries = List.copyOf(fileEntries);
    }

    public Set<String> parseSet() {
        if (fileEntries == null) {
            return Set.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        for (FileEntry fileEntry : fileEntries) {
            Objects.requireNonNull(fileEntry, "fileEntries中存在null");
            urls.add(Objects.requireNonNull(fileEntry.url, "fileEntry.url为空"));
        }
        return Collections.unmodifiableSet(urls);
    }
}
