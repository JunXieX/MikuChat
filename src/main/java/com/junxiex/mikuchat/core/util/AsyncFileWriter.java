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
    /**
     * 最近使用过的文件路径（LRU）。日志按天分文件，跨天后全是新路径，若句柄只增不减，
     * 长期运行会每天泄漏句柄，Windows 上旧日志还会因仍被占用而无法被保留策略删除。
     * 仅在写线程内访问，无需额外同步。
     */
    private static final int MAX_OPEN_FILES = 8;
    private final java.util.Deque<Path> recentOrder = new java.util.ArrayDeque<>();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MikuChat-FileWriter");
        thread.setDaemon(true);
        return thread;
    });

    public AsyncFileWriter(MikuChat plugin) {
        this.plugin = plugin;
    }

    public void append(Path path, String line) {
        if (closed.get()) {
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
                touch(path);
                evictStaleWriters(path);
            } else {
                // 已打开的路径也要刷新 LRU 位置，否则活跃文件可能被误判为空闲而关闭
                touch(path);
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

    /**
     * 记录路径的最近使用顺序。日志按天分文件，跨天后全是新路径：
     * 若句柄一直留着，长期运行会每天泄漏句柄，且 Windows 上旧日志因仍被占用而删不掉。
     * 这里维护一个很小的 LRU（只保留最近活跃的少数几个路径），写入线程是单线程，无需额外同步。
     */
    private void touch(Path path) {
        recentOrder.remove(path);
        recentOrder.addLast(path);
    }

    private void evictStaleWriters(Path keep) {
        while (recentOrder.size() > MAX_OPEN_FILES) {
            Path oldest = recentOrder.peekFirst();
            if (oldest == null) {
                return;
            }
            recentOrder.removeFirst();
            if (!oldest.equals(keep)) {
                closeQuietly(writers.remove(oldest));
            }
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
        // 幂等：重复调用（例如插件停用后又触发一次清理）不应抛出 RejectedExecutionException
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 先置关闭标记（丢弃关闭后的迟到写入），再排队关闭任务：
        // 单线程执行器保证关闭任务排在所有已提交写入之后
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
        recentOrder.clear();
    }
}
