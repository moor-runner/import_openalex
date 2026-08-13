package com.clio.openalex.importer.plan;

import com.clio.openalex.importer.dao.FileTaskDAO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Optional;
public final class Planner {

    /**
     * @param args
     * @return
     */
    private static final String URL="jdbc:mysql://localhost:3306/openalex";
    private static final String USERS="root";
    private static final String PASSWORD="1234";

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
        //创建sync job和file task
        //插入数据库
        insertJobAndTask(manifest,newFilesSince);
        return 0;
    }


    /**
     * 获取输入entity对应的Manifest文件对象
     * @param entity
     * @return
     */
    private static Manifest downloadManifest(Entity entity) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(entity.getLocation())).header("Accept","application/json").build();
        HttpResponse<String> response;
        try {
            response = client.send(request,  HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new RuntimeException("HttpClient发送IO异常",e);
        } catch (InterruptedException e) {
            throw new RuntimeException("HttpClient被打断异常",e);
        }
        String body = response.body();
        return parse(body);
    }

    /**
     * 把请求获取得到的响应体解析成Manifest对象
     * 解析失败或者合理性校验(count自洽，每个url都能够解析出part date entity)失败直接抛异常
     * @param body
     * @return
     */
    private static Manifest parse(String body){
        //TODO 增加合理性校验和空校验
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("manifest JSON 语法解析失败(文件损坏或拿到的不是 JSON)",e);
        }
        String date = jsonNode.required("date").asText();
        Entity entity = Entity.parse(jsonNode.required("entity").asText());
        Long manifest_count=jsonNode.required("record_count").asLong();
        Long manifest_length=jsonNode.required("content_length").asLong();
        JsonNode files = jsonNode.required("files");
        List<FileEntry> fileEntries = new ArrayList<>();

        for (JsonNode fileNode : files) {
            String url = fileNode.required("url").asText();
            Long file_count=fileNode.required("record_count").asLong();
            Long file_length=fileNode.required("content_length").asLong();
            fileEntries.add(new FileEntry(url,file_count,file_length));
        }

        return new Manifest(entity, date, manifest_count,manifest_length,List.copyOf(fileEntries));
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

    }
    private Planner() {}
}
