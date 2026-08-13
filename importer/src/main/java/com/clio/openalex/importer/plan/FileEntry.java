package com.clio.openalex.importer.plan;
import java.time.LocalDate;
public class FileEntry {
    String url;
    Long count;
    Long length;
    int part;
    LocalDate date;
    public FileEntry(){

    }
    public FileEntry(String url,Long count,Long length,int part,LocalDate date){
        this.url=url;
        this.count=count;
        this.length=length;
        this.part=part;
        this.date=date;
    }
}
