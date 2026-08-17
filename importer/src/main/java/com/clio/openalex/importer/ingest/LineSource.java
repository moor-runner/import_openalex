package com.clio.openalex.importer.ingest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;


public final class LineSource implements AutoCloseable {

    private final Process process; // file:// 时为 null
    private final BufferedReader reader;

    private LineSource(Process process, BufferedReader reader) {
        this.process = process;
        this.reader = reader;
    }

    public static LineSource open(String url) throws IOException {
        if (url.startsWith("s3://")) {
            Process p = new ProcessBuilder("aws", "s3", "cp", url, "-", "--no-sign-request")
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            return new LineSource(p, wrap(p.getInputStream()));
        }
        if (url.startsWith("file://")) {
            InputStream in = Files.newInputStream(Path.of(URI.create(url)));
            return new LineSource(null, wrap(in));
        }
        throw new IOException("不支持的 scheme: " + url);
    }

    private static BufferedReader wrap(InputStream in) throws IOException {
        return new BufferedReader(
                new InputStreamReader(new GZIPInputStream(in, 1 << 16), StandardCharsets.UTF_8),
                1 << 16);
    }

    /** 下一行, EOF 返回 null */
    public String readLine() throws IOException {
        return reader.readLine();
    }

    /** 正常收尾: 校验子进程退出码, 非 0 说明流不完整, 该文件必须整体重跑 */
    @Override
    public void close() throws IOException {
        reader.close();
        if (process != null) {
            try {
                int code = process.waitFor();
                if (code != 0) {
                    throw new IOException("aws s3 cp 退出码 " + code + ", 流不完整");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待下载子进程时被中断", e);
            }
        }
    }

    /** 异常路径放弃: 不校验退出码, 直接击杀子进程 */
    public void abort() {
        try {
            reader.close();
        } catch (IOException ignored) {
        }
        if (process != null) {
            process.destroyForcibly();
        }
    }
}
