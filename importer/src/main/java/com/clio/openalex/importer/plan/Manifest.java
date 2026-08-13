package com.clio.openalex.importer.plan;

import java.util.List;
import java.util.Set;

public class Manifest {
    Entity entity;
    String date;
    Long count;
    Long length;
    List<FileEntry> fileEntries;
    public Manifest(){

    }
    public Manifest(Entity entity,String date,Long count,Long length,List<FileEntry> fileEntries){
        this.entity=entity;
        this.date=date;
        this.fileEntries=fileEntries;
    }
    public Set<String> parseSet(){

    }
}
