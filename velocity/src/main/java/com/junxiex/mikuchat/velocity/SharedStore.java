package com.junxiex.mikuchat.velocity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 共享状态仓库：代理端作为跨服状态（禁言等）的唯一权威，内存持有并异步落盘。
 *
 * <p>写入串行化到单线程执行器，落盘前先在调用线程做一次快照，
 * 避免序列化过程中被并发修改。键在被清空后仍保留空表，
 * 以便后端请求全量状态时能收到「该键为空」从而清理本地缓存。</p>
 */
public final class SharedStore {

    private static final Type DATA_TYPE = new TypeToken<Map<String, Map<String, String>>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Map<String, String>> data = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MikuChat-State-Writer");
        thread.setDaemon(true);
        return thread;
    });

    public SharedStore(Path dataDirectory, Logger logger) {
        this.file = dataDirectory.resolve("shared-state.json");
        this.logger = logger;
    }

    /** 读取既有状态；文件不存在或损坏时以空状态启动。 */
    public void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Map<String, String>> loaded = gson.fromJson(reader, DATA_TYPE);
            if (loaded == null) {
                return;
            }
            loaded.forEach((key, values) -> {
                if (values != null) {
                    data.put(key, new ConcurrentHashMap<>(values));
                }
            });
        } catch (Throwable t) {
            logger.warn("读取共享状态失败，将以空状态启动：{}", t.getMessage());
        }
    }

    public void put(String key, String field, String value) {
        data.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(field, value);
        persist();
    }

    public void remove(String key, String field) {
        Map<String, String> values = data.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        if (values.remove(field) != null) {
            persist();
        }
    }

    /** 返回全部键的快照（含空表），供后端请求全量同步时逐键下发。 */
    public Map<String, Map<String, String>> snapshot() {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        data.forEach((key, values) -> copy.put(key, new LinkedHashMap<>(values)));
        return copy;
    }

    /** 阻塞等待当前排队中的写入完成，用于代理关闭前收尾。 */
    public void flush() {
        try {
            writer.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("等待共享状态落盘失败：{}", e.getMessage());
        }
    }

    private void persist() {
        Map<String, Map<String, String>> copy = snapshot();
        writer.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    gson.toJson(copy, DATA_TYPE, out);
                }
            } catch (Throwable t) {
                logger.warn("保存共享状态失败：{}", t.getMessage());
            }
        });
    }
}
