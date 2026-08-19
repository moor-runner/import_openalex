package com.clio.openalex.importer.work;

import com.clio.openalex.importer.dao.FileTaskDAO;
import com.clio.openalex.importer.exception.RetryableException;
import com.clio.openalex.importer.plan.FileTask;
import com.clio.openalex.importer.plan.Entity;
import com.clio.openalex.importer.plan.PlanArgsParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static com.clio.openalex.importer.dao.JdbcConnections.*;

public final class Worker {

    public static final int THREAD_COUNT = 16;
    private static final HikariDataSource DS;
    static {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(setting("openalex.db.url", "OPENALEX_DB_URL", DEFAULT_URL));
        cfg.setUsername(DEFAULT_USER);
        cfg.setPassword(DEFAULT_PASSWORD);
        cfg.setMaximumPoolSize(Worker.THREAD_COUNT + 4);  // 20:每个 worker 最多同时占 1 条
        cfg.setAutoCommit(true);                          // 池的默认值;事务里自己 setAutoCommit(false),归还时 Hikari 会还原
        DS = new HikariDataSource(cfg);
    }
    static Connection open() throws SQLException { return DS.getConnection(); }
    private static final FileTaskDAO fileTaskDAO = new FileTaskDAO(DS);
    /*
    契约拆分：
        1. 根据entity领取任务(同时读取那些僵尸数据)
        2. 状态变更
        3. 读取url
        4. 读取文件
        5. 解压文件
        6. 解析文件
        7. 校验数据
        8. 批量插入数据库(参考Spring Batch)
           1. 事务的处理
           2. 事务的拆分
        9. 状态变更
     */
    private static HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))       // 必须：否则连不上就永久挂起
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final int BATCH_SIZE=1000;
    public static int run(String[] args) {
        Entity entity = PlanArgsParser.parse(args);
        //创建线程池，循环提交固定大小的任务
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            /*
            定义Worker线程执行任务的逻辑
            外置循环，循环领取任务，执行任务
            1.根据entity领取任务(同时读取那些僵尸数据)
                领取到了就继续
                没有领取到，认为是任务都同步完成了，退出任务
            2.任务状态变更
                把任务状态置为running
            3.文件的流式读取和解压，解析
            4.文件的分批量入库--分批次事务
            */
            pool.execute(() -> {
                while (true) {
                    //领取任务：claim 内部已原子置为 running 并刷新心跳
                    Optional<FileTask> fileTask = tryAcquireFileTask(entity);
                    if (fileTask.isEmpty()) {
                        break;   //没有可领取的任务，worker 收工
                    }
                    FileTask task = fileTask.get();
                    try {
                        importFile(task);
                        fileTaskDAO.markDone(task.getFileId());          //成功：done
                    } catch (RetryableException e) {
                        fileTaskDAO.markPending(task.getFileId());       //可重试：退回 pending
                    } catch (Exception e) {
                        fileTaskDAO.markFailed(task.getFileId());        //Fatal/IO/SQL 等：failed，继续领下一个
                    }
                }
            });
        }
        //提交完毕后关闭线程池，等所有 worker 领完活自行退出
        pool.shutdown();
        try {
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        return 0;
    }

    /**
     * 尝试获取某个entity下处于pending状态或者处于超时状态的file task
     *
     * @param entity
     * @return
     */
    public static Optional<FileTask> tryAcquireFileTask(Entity entity) {
        return fileTaskDAO.claim(entity);
    }

    /**
     * 在 chunk 事务内推进 read_count 并刷新心跳。
     * 委托 FileTaskDAO，参与调用方（commitChunk）事务，不提交不关闭连接。
     */
    static void updateHeartbeatAndCount(Connection conn, FileTask fileTask, long readCount) throws SQLException {
        fileTaskDAO.advanceProgress(conn, fileTask.getFileId(), readCount);
    }

    /**
     * 批量插入一批原始 JSON 行到目标落库表。
     * TODO: OpenAlex 实体（works/authors/sources）的落库表未在 docs/数据库设计.md 及关系模型中定义，
     *       按 CLAUDE.md 约束不擅自新增表，待确认表结构后实现。
     */
    static void batchInsert(Connection conn, List<String> list) throws SQLException {
        throw new UnsupportedOperationException("batchInsert 目标落库表尚未定义，待确认表结构");
    }

    /**
     * 完成FileTask对应json gz文件的导入到数据库表任务
     * 网络异常--重试
     * 数量校验异常--不由此处负责，后续在reconcile阶段做校验
     * 解析过程异常--Fatal异常，直接报错
     * 数据本身有问题--Poisoned数据，记录至deadRow，继续解析
     *          完成FileTask对应json gz文件的导入到数据库表任务
     *          网络异常--重试
     *          量校验异常--不由此处负责，后续在reconcile阶段做校验
     *          解析过程异常--Fatal异常，直接报错
     *          数据本身有问题--Poisoned数据，记录至deadRow，继续解析
     *         1. Reader
     *            1. 读取文件--HttpClient--global
     *            2. 解压文件--GzInputStream
     *            3. 解析文件--BufferedReader
     *         while true
     *            读取一个批次,1000条
     *            3. commitChunk
     *                1. 校验数据
     *                2. 批量插入数据库(参考Spring Batch)+更新心跳+进度
     *                   1. 事务的处理
     *                   2. 事务的拆分
     *
     * @param fileTask
     * @throws IOException
     */
    public static void importFile(FileTask fileTask) throws IOException, SQLException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(toHttpUri(fileTask.getUrl()))
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new RetryableException("download failed: " + toHttpUri(fileTask.getUrl()), e);
        } catch (InterruptedException e) {
            throw new RetryableException("interrupted", e);
        }
        if(response.statusCode()!=200){
            response.body().close();
            throw new RetryableException("Http状态码错误"+response.statusCode());
        }

        // 外层：流的生命周期 = 整个文件；try-with-resources 保证正常/异常都关闭（含 GZIP 的 native inflater）
        try (InputStream body = response.body();
             GZIPInputStream gzip = new GZIPInputStream(body, 65536);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(gzip, StandardCharsets.UTF_8), 1 << 20)) {

            long read_count = 0;
            while (true) {
                // 读一个批次，最多 BATCH_SIZE 条；readLine 返回 null 即到 EOF
                List<String> list = new ArrayList<>();
                for (int i = 0; i < BATCH_SIZE; i++) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    read_count++;
                    list.add(line);
                }
                if (list.isEmpty()) {   // 整个文件读完
                    break;
                }

                // 内层：连接的生命周期 = 一个 chunk；每批从池借一条，用完还池
                try (Connection conn = open()) {
                    conn.setAutoCommit(false);
                    try {
                        batchInsert(conn, list);                         // DAO
                        updateHeartbeatAndCount(conn, fileTask, read_count);
                        conn.commit();
                    } catch (Exception e) {   // 任何异常都回滚并上报，绝不吞
                        conn.rollback();
                        throw e;
                    }
                }   // ← 本批结束，连接立即还池
            }
        }   // ← 文件读完，流按逆序关闭
    }

    /**
     * s3://bucket/key → https://bucket.s3.amazonaws.com/key；已是 http 的原样返回
     */
    static URI toHttpUri(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return URI.create(url);                        // ④ 防御：已经是 http，直连
        }
        if (!url.startsWith("s3://")) {
            throw new IllegalArgumentException("unsupported url scheme: " + url);
        }
        String rest = url.substring(5);                  // 去掉 "s3://"
        int slash = rest.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("s3 url has no key: " + url);
        String bucket = rest.substring(0, slash);
        String key = rest.substring(slash + 1);
        try {
            // 4 参构造器会正确编码 path、保留 '/'，比 URLEncoder 省心
            return new URI("https", bucket + ".s3.amazonaws.com", "/" + key, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad s3 url: " + url, e);
        }
    }

    private Worker() {
    }
}
