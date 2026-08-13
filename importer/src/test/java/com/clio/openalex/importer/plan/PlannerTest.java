package com.clio.openalex.importer.plan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    @Test
    void manifest解析并从url提取date和part() {
        Manifest manifest = parse(validManifest());

        assertEquals(Entity.SOURCES, manifest.entity);
        assertEquals("2026-06-26", manifest.date);
        assertEquals(5L, manifest.count);
        assertEquals(30L, manifest.length);
        assertEquals(2, manifest.fileEntries.size());
        assertEquals(LocalDate.of(2026, 6, 25), manifest.fileEntries.get(0).date);
        assertEquals(0, manifest.fileEntries.get(0).part);
        assertEquals(1, manifest.fileEntries.get(1).part);
        assertEquals(2, manifest.parseSet().size());
    }

    @Test
    void manifest的count必须自洽() {
        assertContractFailure(validManifest().replace("\"record_count\":5", "\"record_count\":6"));
    }

    @Test
    void manifest拒绝错误url和entity漂移() {
        assertContractFailure(validManifest().replace("part_0000.gz", "chunk_0000.gz"));
        assertContractFailure(validManifest().replace(
                "data/jsonl/sources/updated_date=2026-06-25/part_0000.gz",
                "data/jsonl/authors/updated_date=2026-06-25/part_0000.gz"));
    }

    @Test
    void 空水位全量_已有水位只返回严格更新的文件() {
        Manifest manifest = parse(validManifest());

        assertEquals(2, newFilesSince(manifest, Optional.empty()).size());

        FileTask sameDateFirstPart = new FileTask();
        sameDateFirstPart.date = LocalDate.of(2026, 6, 25);
        sameDateFirstPart.part = 0;
        List<FileEntry> incremental = newFilesSince(manifest, Optional.of(sameDateFirstPart));
        assertEquals(1, incremental.size());
        assertEquals(1, incremental.get(0).part);

        FileTask afterManifest = new FileTask();
        afterManifest.date = LocalDate.of(2026, 6, 26);
        afterManifest.part = 0;
        assertTrue(newFilesSince(manifest, Optional.of(afterManifest)).isEmpty());
    }



    private static Manifest parse(String json) {
        return invoke("parse", new Class<?>[]{String.class}, json);
    }
    
    @Test
    void downloadManifest() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://openalex.s3.amazonaws.com/data/jsonl/sources/manifest.json")).header("Accept","application/json").build();
        HttpResponse<String> response;
        response = client.send(request,  HttpResponse.BodyHandlers.ofString());
        String body = response.body();
        Files.writeString(Path.of("src/test/resources/test.txt"),body);
    }

    @SuppressWarnings("unchecked")
    private static List<FileEntry> newFilesSince(
            Manifest manifest, Optional<FileTask> watermark) {
        return invoke(
                "newFilesSince",
                new Class<?>[]{Manifest.class, Optional.class},
                manifest,
                watermark);
    }

    private static void assertContractFailure(String json) {
        assertThrows(IllegalArgumentException.class, () -> parse(json));
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(String name, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = Planner.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return (T) method.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String validManifest() {
        return """
                {
                  "date":"2026-06-26",
                  "format":"jsonl",
                  "entity":"sources",
                  "record_count":5,
                  "content_length":30,
                  "files":[
                    {
                      "url":"s3://openalex/data/jsonl/sources/updated_date=2026-06-25/part_0000.gz",
                      "meta":{"record_count":2,"content_length":10}
                    },
                    {
                      "url":"s3://openalex/data/jsonl/sources/updated_date=2026-06-25/part_0001.gz",
                      "meta":{"record_count":3,"content_length":20}
                    }
                  ]
                }
                """;
    }
}
