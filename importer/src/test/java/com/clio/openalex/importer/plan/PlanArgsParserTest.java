package com.clio.openalex.importer.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanArgsParserTest {

    @Test
    void parse() {
        PlanArgsParser planArgsParser = new PlanArgsParser();
        Entity parse = planArgsParser.parse(new String[]{"--entity", "SOURCES"});
        assertEquals(parse,Entity.SOURCES);
    }
}