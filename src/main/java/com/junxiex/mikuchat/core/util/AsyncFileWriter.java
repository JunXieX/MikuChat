package com.junxiex.mikuchat.core.util;

import com.junxiex.mikuchat.MikuChat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 异步文件追加写入器。所有磁盘写入集中到单线程，避免阻塞服务器线程，
 * 同时保证同一文件的写入顺序。
 * <p>
 * 每个文件维持一个复用句柄，避免"每行开-写-关一次文件"；待写队列有界（容量见
 * {@link #QUEUE_CAPACITY}），磁盘卡顿时宁可丢弃并限频告警，也不让内存无界积压；
 * 仅在队列排空时 flush，既合并突发写入的刷盘开销，又保证空闲时逐行落盘；
 * 关闭时在所有已入队写入之后兜底 flush + close，确保日志不丢。
 */
public final class AsyncFileWriter {

    private final MikuChat plugin;
    // 兜底关闭路径可能由调用线程执行，因此用并发 Map；正常写入仍只由写线程访问
    private final Map<Path, BufferedWriter> writers = new ConcurrentHashMap<>();
    /**
     * 最近使用过的文件路径（LRU）。日志按天分文件，跨天后全是新路径，若句柄只增不减，
     * 长期运行会每天泄漏句柄，Windows 上旧日志还会因仍被占用而无法被保留策略删除。
     * 仅在写线程内访问，无需额外同步。
     */
    private static final int MAX_OPEN_FILES = 8;
    private static final int QUEUE_CAPACITY = 4096;
    private final java.util.Deque<Path> recentOrder = new java.util.ArrayDeque<>();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    /** 队列满时被丢弃的行数，用于限频告警（只在首次与每满 1000 条时提示）。 */
    private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread thread = new Thread(r, "MikuChat-FileWriter");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

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
            // 与 shutdown() 竞争导致的关闭后提交直接丢弃；队列满（磁盘卡顿 / 日志暴增）则计数并限频告警
            if (!closed.get()) {
                reportDropped();
            }
        }
    }

    /** 丢弃计数：首次与每满 1000 条提示一次，避免告警本身刷爆日志。 */
    private void reportDropped() {
        long total = dropped.incrementAndGet();
        if (total == 1L || total % 1000L == 0L) {
            plugin.getLogger().warning("日志写入队列已满，已丢弃 " + total + " 条日志（请检查磁盘 IO 或日志量）。");
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
            // 仅在待写队列排空时统一 flush：突发期间合并刷盘、减少系统调用，突发结束（或空闲）落盘。
            // 缓冲区是按文件独立的，必须 flush 全部打开的文件，否则低流量日志（如私聊留档）
            // 可能在持续聊天负载下长时间只停留在 JVM 缓冲里。
            if (executor.getQueue().isEmpty()) {
                flushAll();
            }
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

    /** flush 所有已打开的句柄（最多 {@link #MAX_OPEN_FILES} 个），单个失败不影响其它文件。 */
    private void flushAll() {
        for (BufferedWriter writer : writers.values()) {
            try {
                writer.flush();
            } catch (Throwable ignored) {
                // 句柄已失效时由下一次 write 的错误路径重建
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
        boolean queued = true;
        try {
            executor.execute(this::closeAll);
        } catch (RejectedExecutionException e) {
            // 有界队列可能已满：关闭任务排不进去时，退化为等写入任务结束后由当前线程兜底关闭
            queued = false;
        }
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
        if (!queued) {
            // 写入线程可能刚被强制中断、仍在收尾，再等一小会儿
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (executor.isTerminated()) {
                // 写线程已完全退出（recentOrder 等单线程结构不再被访问），可安全兜底关闭
                closeAll();
            } else {
                plugin.getLogger().warning("文件写入线程未在关闭等待内结束，跳过句柄关闭（进程退出后由操作系统回收）。");
            }
        }
    }

    private void closeAll() {
        for (BufferedWriter writer : new ArrayList<>(writers.values())) {
            closeQuietly(writer);
        }
        writers.clear();
        recentOrder.clear();
    }
}
