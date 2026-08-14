package com.clio.openalex.importer.plan;

import com.clio.openalex.importer.dao.FileTaskDAO;
import com.clio.openalex.importer.dao.SyncJobDAO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Planner {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern FILE_URL = Pattern.compile(
            "^s3://openalex/data/jsonl/([a-z0-9-]+)/updated_date="
                    + "(\\d{4}-\\d{2}-\\d{2})/part_(\\d+)\\.gz$");
    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    /**
     * 通过远端的Manifest文件和本地的file task水位线判断是否需要创建新任务，如果需要，创建新任务
     * @param args
     * @return
     */
    public static int run(String[] args) {
        Entity entity = PlanArgsParser.parse(args);
        Manifest manifest=downloadManifest(entity);
        FileTaskDAO fileTaskDAO = new FileTaskDAO();
        Optional<FileTask> fileTask = fileTaskDAO.selectLatestFileTask(entity.name());
        List<FileEntry> newFilesSince=newFilesSince(manifest,fileTask);
        insertJobAndTask(manifest,newFilesSince);
        return 0;
    }


    /**
     * 获取输入entity对应的Manifest文件对象
     * @param entity
     * @return
     */
    private static Manifest downloadManifest(Entity entity) {
        log.info("开始下载Manifest");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        URI uri = URI.create(entity.getLocation());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept","application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request,  HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("下载manifest失败: " + uri,e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("下载manifest时线程被中断: " + uri,e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "下载manifest失败: HTTP " + response.statusCode() + ", uri=" + uri);
        }
        log.info("下载完成，开始解析");
        Manifest manifest = parse(response.body());
        if (manifest.entity != entity) {
            throw new IllegalArgumentException(
                    "manifest entity与请求不一致: expected=" + entity.name()
                            + ", actual=" + manifest.entity.name());
        }
        log.info("Manifest文件解析完成");
        return manifest;
    }

    /**
     * 把请求获取得到的响应体解析成Manifest对象
     * 解析失败或者合理性校验(count自洽，每个url都能够解析出part date entity)失败直接抛异常
     * @param body
     * @return
     */
    private static Manifest parse(String body){
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "manifest JSON语法解析失败(文件损坏或拿到的不是JSON)",e);
        }
        if (jsonNode == null || !jsonNode.isObject()) {
            throw new IllegalArgumentException("manifest顶层必须是JSON对象");
        }
        String date = jsonNode.required("date").asText();
        LocalDate.parse(date);
        Entity entity = Entity.parse(jsonNode.required("entity").asText());
        long manifestCount=jsonNode.required("record_count").asLong();
        long manifestLength=jsonNode.required("content_length").asLong();
        JsonNode files = jsonNode.required("files");
        if (!files.isArray()) {
            throw new IllegalArgumentException("manifest.files必须是数组");
        }

        List<FileEntry> fileEntries = new ArrayList<>();
        long fileCountSum = 0;

        for (JsonNode fileNode : files) {
            String url = fileNode.required("url").asText();
            JsonNode meta = fileNode.required("meta");
            long fileCount=meta.required("record_count").asLong();
            long fileLength=meta.required("content_length").asLong();

            Matcher matcher = FILE_URL.matcher(url);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("文件url格式不完整: " + url);
            }
            if (Entity.parse(matcher.group(1)) != entity) {
                throw new IllegalArgumentException(
                        "文件url中的entity与manifest不一致: " + url);
            }
            LocalDate partitionDate = LocalDate.parse(matcher.group(2));
            int part = Integer.parseInt(matcher.group(3));

            fileCountSum += fileCount;
            fileEntries.add(new FileEntry(url,fileCount,fileLength,part,partitionDate));
        }

        if (fileCountSum != manifestCount) {
            throw new IllegalArgumentException(
                    "manifest record_count不自洽: manifest=" + manifestCount
                            + ", files=" + fileCountSum);
        }
        return new Manifest(entity,date,manifestCount,manifestLength,List.copyOf(fileEntries));
    }

    /**
     * 通过远端状态和本地水位获取需要同步的新文件列表
     * 如果为Optional为空就代表需要全量同步
     * 返回远端date大于水位线或者date等于水位线但是part大于水位线的文件
     * 没有就返回一个空对象
     * @param manifest
     * @param fileTask
     * @return
     */
    private static List<FileEntry> newFilesSince(Manifest manifest,Optional<FileTask> fileTask){
        log.info("开始计算需要同步的文件列表");
        if (fileTask.isEmpty()) {
            return List.copyOf(manifest.fileEntries);
        }
        FileTask watermark = fileTask.orElseThrow();
        List<FileEntry> result = new ArrayList<>();
        for (FileEntry entry : manifest.fileEntries) {
            if (entry.date.isAfter(watermark.getDate())
                    || (entry.date.isEqual(watermark.getDate())
                    && entry.part > watermark.getPart())) {
                result.add(entry);
            }
        }
        log.info(result.toString());
        return List.copyOf(result);
    }

    /**
     * 把FileEntry转换为File task对象，创建Sync job对象
     * 把这次任务的Job和Task记录到数据库
     * 校验输入的List是否为空，为空直接提示并退出
     * 插入过程需要保证原子性
     * @param manifest
     * @param fileEntries
     */
    private static void insertJobAndTask(Manifest manifest,List<FileEntry> fileEntries){
        if (fileEntries == null || fileEntries.isEmpty()) {
            System.out.println("没有需要同步的新文件");
            return;
        }
        log.info("开始插入Job和Task");
        SyncJob syncJob = new SyncJob();
        syncJob.setEntity(manifest.entity);
        syncJob.setSnapshot(LocalDate.parse(manifest.date));

        List<FileTask> tasks = new ArrayList<>(fileEntries.size());
        for (FileEntry entry : fileEntries) {
            FileTask task = new FileTask();
            task.setStatus("PENDING");
            task.setUrl(entry.url);
            task.setEntity(manifest.entity);
            task.setDate(entry.date);
            task.setPart(entry.part);
            task.setRecordCount(entry.count);
            task.setReadCount(0L);
            tasks.add(task);
        }
        long jobId = new SyncJobDAO().insertJobAndTasks(syncJob,List.copyOf(tasks));
        System.out.println("创建同步计划成功: job_id=" + jobId + ", files=" + tasks.size());
    }
    private Planner() {}
}
