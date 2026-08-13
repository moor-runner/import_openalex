package com.clio.openalex.importer.plan;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class EntityTest {
    @Test
    void getLocation() {
        assertEquals("s3://openalex/data/jsonl/sources/manifest.json",Entity.SOURCES.getLocation());
    }

    @Test
    void parse() {
        assertEquals(Entity.SOURCES,Entity.parse("SOURCES"));
    }

}
