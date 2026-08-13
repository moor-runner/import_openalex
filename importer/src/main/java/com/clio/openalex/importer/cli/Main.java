package com.clio.openalex.importer.cli;

import java.util.Arrays;

import com.clio.openalex.importer.plan.Planner;
import com.clio.openalex.importer.reconcile.Reconciler;
import com.clio.openalex.importer.work.Worker;

/**
 * 单二进制三子命令(契约 §1): plan / work / reconcile (+ status)。
 * 多 worker = 多进程, 协调完全靠 MySQL SKIP LOCKED, 进程间零通信。
 */
public final class Main {

    private static final String USAGE = """
            用法: java -jar importer.jar <子命令> [选项]
              plan      --entity sources                  读 manifest, 建 sync_job + file_task 
              work      --job <id> [--workers 4]           领任务→流式导入→计数 
              reconcile --job <id>                         三层对账→推水位 
              status    --job <id>                         进度一览 (验收 A6)
            """;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0) {
            System.err.println(USAGE);
            return 64;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            return switch (args[0]) {
                case "plan"      -> Planner.run(rest);
                case "work"      -> Worker.run(rest);
                case "reconcile" -> Reconciler.run(rest);
                case "status"    -> Reconciler.status(rest);
                default -> {
                    System.err.println("未知子命令: " + args[0] + "\n" + USAGE);
                    yield 64;
                }
            };
        } catch (UnsupportedOperationException e) {
            System.err.println("尚未实现: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
    }

    private Main() {}
}
