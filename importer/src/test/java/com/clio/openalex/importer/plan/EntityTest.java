package com.clio.openalex.importer.plan;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class EntityTest {
    @Test
    void getLocation() {
        assertEquals(
                "https://openalex.s3.amazonaws.com/data/jsonl/sources/manifest.json",
                Entity.SOURCES.getLocation());
    }

    @Test
    void parse() {
        assertEquals(Entity.SOURCES,Entity.parse("SOURCES"));
    }

}
