package com.junxiex.mikuchat.core.util;

import com.junxiex.mikuchat.MikuChat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 异步文件追加写入器。所有磁盘写入集中到单线程，避免阻塞服务器线程，
 * 同时保证同一文件的写入顺序。
 * <p>
 * 每个文件维持一个复用句柄（写入后立即 flush），避免"每行开-写-关一次文件"；
 * 关闭时先排队执行关闭任务，确保已入队的日志全部落盘。
 */
public final class AsyncFileWriter {

    private final MikuChat plugin;
    private final Map<Path, BufferedWriter> writers = new HashMap<>();
    private volatile boolean closed;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MikuChat-FileWriter");
        thread.setDaemon(true);
        return thread;
    });

    public AsyncFileWriter(MikuChat plugin) {
        this.plugin = plugin;
    }

    public void append(Path path, String line) {
        if (closed) {
            return;
        }
        try {
            executor.execute(() -> write(path, line));
        } catch (RejectedExecutionException e) {
            // 与 shutdown() 竞争导致的关闭后提交，直接丢弃即可
        }
    }

    private void write(Path path, String line) {
        try {
            BufferedWriter writer = writers.get(path);
            if (writer == null) {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writers.put(path, writer);
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (Exception e) {
            // 句柄可能已失效（文件被外部删除/移动），移除后由下次写入重建
            closeQuietly(writers.remove(path));
            plugin.getLogger().warning("Failed to write " + path + ": " + e.getMessage());
        }
    }

    private void closeQuietly(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }

    public void shutdown() {
        // 先置关闭标记（丢弃关闭后的迟到写入），再排队关闭任务：
        // 单线程执行器保证关闭任务排在所有已提交写入之后
        closed = true;
        executor.execute(this::closeAll);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("文件写入任务未在 10 秒内完成，部分日志可能未落盘。");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void closeAll() {
        for (BufferedWriter writer : writers.values()) {
            closeQuietly(writer);
        }
        writers.clear();
    }
}
