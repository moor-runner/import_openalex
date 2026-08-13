package com.clio.openalex.importer.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanArgsParserTest {

    @Test
    void parse() {
        Entity parse = PlanArgsParser.parse(new String[]{"--entity", "SOURCES"});
        assertEquals(parse,Entity.SOURCES);
    }

    @Test
    void 缺少entity值() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanArgsParser.parse(new String[]{"--entity"}));
    }

    @Test
    void 拒绝重复entity和未知参数() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanArgsParser.parse(
                        new String[]{"--entity", "sources", "--entity", "authors"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanArgsParser.parse(new String[]{"--unknown"}));
    }
}
