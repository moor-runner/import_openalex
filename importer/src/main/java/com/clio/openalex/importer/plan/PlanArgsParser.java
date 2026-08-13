package com.clio.openalex.importer.plan;

public class PlanArgsParser {
    public static Entity parse(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("参数数组为空");
        }
        Entity entity = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--entity" -> {
                    if (entity != null) {
                        throw new IllegalArgumentException("传入了多个entity参数");
                    }
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("需要传入选择的entity值");
                    }
                    entity = Entity.parse(args[i]);
                }
                default -> {
                    throw new IllegalArgumentException("未知的参数类型: " + args[i]);
                }
            }
        }
        if (entity == null) {
            throw new IllegalArgumentException("缺少必需参数 --entity");
        }
        return entity;
    }
}
