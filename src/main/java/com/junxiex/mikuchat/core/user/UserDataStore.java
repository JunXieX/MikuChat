package com.junxiex.mikuchat.core.user;

import com.junxiex.mikuchat.MikuChat;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 玩家数据存储：所有模块（频道选择、@ 提醒开关、私聊监听等）共用同一个
 * {@code data/users/<uuid>.yml}，由本类统一做「读-改-写」并串行落盘。
 * <p>
 * 内存中每个玩家维护一份并发值表作为唯一权威数据：写操作在调用线程即时生效
 * （保证「写后即读」可见），落盘任务只读取这张并发表并在单线程执行器上写文件。
 * 磁盘写入与内存修改互不接触同一可变对象，因此不存在并发改坏文件的问题，
 * 也不会出现某个线程遍历时另一个线程修改同一 {@link YamlConfiguration} 的竞争。
 */
public final class UserDataStore implements AutoCloseable {

    private final MikuChat plugin;
    private final File directory;
    private final ExecutorService executor;
    private final Map<UUID, ConcurrentHashMap<String, Object>> cache = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public UserDataStore(MikuChat plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "data/users");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "MikuChat-UserData");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 在存储线程上预热玩家数据，避免后续在游戏线程首次读盘。 */
    public void preload(UUID uuid) {
        if (closed) {
            return;
        }
        executor.execute(() -> values(uuid));
    }

    public boolean getBoolean(UUID uuid, String key, boolean defaultValue) {
        Object value = values(uuid).get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null ? Boolean.parseBoolean(String.valueOf(value)) : defaultValue;
    }

    public String getString(UUID uuid, String key) {
        Object value = values(uuid).get(key);
        return value != null ? String.valueOf(value) : null;
    }

    public void set(UUID uuid, String key, Object value) {
        if (closed) {
            return;
        }
        // 先在并发表中即时生效，再排队落盘；落盘只读该表，避免跨线程共享可变对象
        values(uuid).put(key, value);
        executor.execute(() -> save(uuid));
    }

    /**
     * 玩家退出后释放内存副本。任务排队执行，保证该玩家已提交的写入先落盘再释放，
     * 避免"退出瞬间的修改"被丢掉。
     */
    public void forget(UUID uuid) {
        if (closed) {
            return;
        }
        executor.execute(() -> cache.remove(uuid));
    }

    private ConcurrentHashMap<String, Object> values(UUID uuid) {
        return cache.computeIfAbsent(uuid, id -> {
            ConcurrentHashMap<String, Object> values = new ConcurrentHashMap<>();
            File file = file(id);
            if (file.isFile()) {
                try {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                    for (Map.Entry<String, Object> entry : yaml.getValues(false).entrySet()) {
                        if (entry.getValue() != null) {
                            values.put(entry.getKey(), entry.getValue());
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("读取玩家数据失败 " + file.getName() + ": " + e.getMessage());
                }
            }
            return values;
        });
    }

    private void save(UUID uuid) {
        ConcurrentHashMap<String, Object> values = cache.get(uuid);
        if (values == null) {
            return;
        }
        try {
            Files.createDirectories(directory.toPath());
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                yaml.set(entry.getKey(), entry.getValue());
            }
            yaml.save(file(uuid));
        } catch (Exception e) {
            plugin.getLogger().warning("保存玩家数据失败 " + uuid + ": " + e.getMessage());
        }
    }

    private File file(UUID uuid) {
        return new File(directory, uuid + ".yml");
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}