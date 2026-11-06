package com.junxiex.mikuchat.module.mute;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.service.MuteService;
import com.junxiex.mikuchat.core.service.NetworkService;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * 禁言管理器：以代理端共享存储为准实现跨服同步；代理不可用时回落本地 mute.yml。
 * 变更通过 MUTE_UPDATE 实时通知其它服务器，全量状态由代理端在收到 STATE_REQ 后下发。
 * <p>
 * 本地文件只保存「代理不可用期间新增、尚未确认」的记录（P| 前缀）以及代理状态的镜像；
 * 启动时只回填 P| 记录，其余本地记录在代理可用时以代理快照为准。
 */
public final class MuteManager implements MuteService {

    private static final String MUTE_HASH_KEY = "mikuchat:mutes";
    private static final String MUTE_UPDATE_TYPE = "MUTE_UPDATE";
    private static final String PENDING_PREFIX = "P|";
    /** 本地文件格式版本：2 起支持 P| 前缀。 */
    private static final int FORMAT_VERSION = 2;
    /** 与代理端分页上限对应。 */
    private static final int MAX_STATE_PAGES = 512;
    private static final com.google.gson.Gson JSON = new com.google.gson.Gson();

    private final MikuChat plugin;
    /** 当前禁言快照；整体替换，避免读者看到清空到一半的状态。 */
    private volatile Map<UUID, MuteInfo> cache = new ConcurrentHashMap<>();
    /** 所有缓存变更与全量快照替换都在此锁内完成。 */
    private final Object cacheLock = new Object();
    /** 代理不可用期间新增、尚未确认写入共享存储的禁言；只有这些会在启动时回填。 */
    private final Set<UUID> pendingUpload = ConcurrentHashMap.newKeySet();
    /** 本次进程内未确认的解除：快照若仍包含该记录，则重新删除。 */
    private final Set<UUID> pendingDeletes = ConcurrentHashMap.newKeySet();
    private final BiConsumer<String, String> updateHandler = (source, json) -> handleUpdate(json);
    private final BiConsumer<String, String> syncHandler = (source, json) -> handleStateSync(json);
    /** 本地文件写入集中到单线程，保证顺序且不阻塞主线程。 */
    private final ExecutorService localFileExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MikuChat-MuteFile");
        thread.setDaemon(true);
        return thread;
    });

    /** STATE_SYNC 分页聚合状态（受 cacheLock 保护）。 */
    private int statePage;
    private final Map<UUID, MuteInfo> stateAcc = new HashMap<>();

    public MuteManager(MikuChat plugin) {
        this.plugin = plugin;
    }

    private NetworkService network() {
        return plugin.services().find(NetworkService.class).orElse(null);
    }

    public void load() {
        synchronized (cacheLock) {
            Map<UUID, MuteInfo> loaded = new HashMap<>();
            pendingUpload.clear();
            pendingDeletes.clear();
            boolean legacy = loadLocalFile(loaded);
            loaded.entrySet().removeIf(entry -> entry.getValue().expired());
            this.cache = new ConcurrentHashMap<>(loaded);
            this.statePage = 0;
            this.stateAcc.clear();
            if (legacy) {
                plugin.getLogger().warning("mute.yml 为旧格式；本服历史禁言仅在代理不可用时生效，"
                        + "代理可用后以代理端权威状态为准。");
            }
            NetworkService network = network();
            if (network != null && network.available()) {
                uploadPendingToShared(network);
                network.requestState();
            }
        }
    }

    /**
     * 代理端下发的全量共享状态，按 page/pages 聚合后一次性覆盖本地。
     */
    private void handleStateSync(String json) {
        try {
            com.google.gson.JsonObject object =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!object.has("key") || !MUTE_HASH_KEY.equals(object.get("key").getAsString())) {
                return;
            }
            int page = object.has("page") ? object.get("page").getAsInt() : 1;
            int pages = object.has("pages") ? object.get("pages").getAsInt() : 1;
            if (pages < 1 || pages > MAX_STATE_PAGES || page < 1 || page > pages) {
                plugin.getLogger().warning("共享状态页数异常（" + page + "/" + pages
                        + "，上限 " + MAX_STATE_PAGES + "），已忽略本次快照。");
                return;
            }
            synchronized (cacheLock) {
                if (page == 1) {
                    stateAcc.clear();
                } else if (statePage != page - 1) {
                    plugin.debug("共享状态分页乱序，已忽略第 " + page + "/" + pages + " 页");
                    return;
                }
                if (object.has("map")) {
                    for (Map.Entry<String, com.google.gson.JsonElement> entry
                            : object.getAsJsonObject("map").entrySet()) {
                        MuteInfo info = parse(entry.getValue().getAsString());
                        if (info == null) {
                            continue;
                        }
                        try {
                            stateAcc.put(UUID.fromString(entry.getKey()), info);
                        } catch (IllegalArgumentException ignored) {
                            // 非法 UUID 忽略
                        }
                    }
                }
                statePage = page;
                if (page < pages) {
                    return;
                }
                Map<UUID, MuteInfo> loaded = new HashMap<>(stateAcc);
                stateAcc.clear();
                loaded.entrySet().removeIf(entry -> entry.getValue().expired());
                statePage = 0;
                commitSnapshot(loaded);
            }
        } catch (Throwable t) {
            plugin.debug("解析共享状态失败: " + t.getMessage());
        }
    }

    /** 用权威快照替换本地状态；内容一致时跳过重建与磁盘重写。调用方需持有 cacheLock。 */
    private void commitSnapshot(Map<UUID, MuteInfo> loaded) {
        Map<UUID, MuteInfo> previous = this.cache;
        Set<UUID> stillPending = new HashSet<>();
        for (UUID uuid : pendingUpload) {
            if (loaded.containsKey(uuid)) {
                continue;
            }
            MuteInfo info = previous.get(uuid);
            if (info != null && !info.expired()) {
                loaded.put(uuid, info);
                stillPending.add(uuid);
            }
        }
        Set<UUID> stillDelete = new HashSet<>();
        for (UUID uuid : pendingDeletes) {
            if (loaded.remove(uuid) != null || previous.containsKey(uuid)) {
                stillDelete.add(uuid);
            }
        }
        pendingDeletes.retainAll(stillDelete);
        if (stillPending.isEmpty() && pendingUpload.isEmpty() && pendingDeletes.isEmpty()
                && loaded.equals(previous)) {
            return;
        }
        this.cache = new ConcurrentHashMap<>(loaded);
        pendingUpload.retainAll(stillPending);
        writeWholeLocalFile(cache, pendingUpload);
        NetworkService network = network();
        if (network != null && network.available()) {
            for (UUID uuid : stillPending) {
                MuteInfo info = cache.get(uuid);
                if (info != null) {
                    network.hashSet(MUTE_HASH_KEY, uuid.toString(), serialize(info));
                }
            }
            for (UUID uuid : stillDelete) {
                network.hashDelete(MUTE_HASH_KEY, uuid.toString());
            }
        }
    }

    private void uploadPendingToShared(NetworkService network) {
        for (UUID uuid : pendingUpload) {
            MuteInfo info = cache.get(uuid);
            if (info != null && !info.expired()) {
                network.hashSet(MUTE_HASH_KEY, uuid.toString(), serialize(info));
            }
        }
    }

    /** @return true 表示文件没有格式版本号（1.6/1.7 旧文件） */
    private boolean loadLocalFile(Map<UUID, MuteInfo> loaded) {
        File file = new File(plugin.getDataFolder(), "mute.yml");
        if (!file.exists()) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean legacy = yaml.getInt("format-version", 0) < FORMAT_VERSION;
        var section = yaml.getConfigurationSection("mutes");
        if (section == null) {
            return legacy;
        }
        for (String key : section.getKeys(false)) {
            String raw = section.getString(key);
            MuteInfo info = parse(raw);
            if (info == null) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(key);
                loaded.put(uuid, info);
                if (isPending(raw)) {
                    pendingUpload.add(uuid);
                }
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID 忽略
            }
        }
        return legacy;
    }

    private static boolean isPending(String value) {
        return value != null && value.startsWith(PENDING_PREFIX);
    }

    private MuteInfo parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String raw = isPending(value) ? value.substring(PENDING_PREFIX.length()) : value;
        String[] parts = raw.split("\\|", 2);
        try {
            long expireAt = Long.parseLong(parts[0]);
            String reason = parts.length > 1 ? parts[1] : "";
            return new MuteInfo(reason, expireAt);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String serialize(MuteInfo info) {
        return info.expireAt() + "|" + (info.reason() == null ? "" : info.reason());
    }

    @Override
    public MuteInfo muteOf(UUID uuid) {
        MuteInfo info = cache.get(uuid);
        return info == null || info.expired() ? null : info;
    }

    public void mute(UUID uuid, long durationMillis, String reason) {
        long expireAt = durationMillis <= 0 ? 0 : System.currentTimeMillis() + durationMillis;
        MuteInfo info = new MuteInfo(reason == null ? "" : reason, expireAt);
        NetworkService network = network();
        boolean channel = network != null && network.available();
        // 无人在线时没有可用的插件消息连接，即使曾经连通过也不能算「已确认」
        boolean confirmed = network != null && network.connected()
                && !org.bukkit.Bukkit.getOnlinePlayers().isEmpty();
        synchronized (cacheLock) {
            cache.put(uuid, info);
            pendingDeletes.remove(uuid);
            if (channel) {
                network.hashSet(MUTE_HASH_KEY, uuid.toString(), serialize(info));
            }
            if (confirmed) {
                pendingUpload.remove(uuid);
                saveLocal(uuid, serialize(info));
            } else {
                pendingUpload.add(uuid);
                saveLocal(uuid, PENDING_PREFIX + serialize(info));
            }
            publish(uuid, serialize(info), false);
        }
    }

    public boolean unmute(UUID uuid) {
        NetworkService network = network();
        boolean channel = network != null && network.available();
        boolean confirmed = network != null && network.connected()
                && !org.bukkit.Bukkit.getOnlinePlayers().isEmpty();
        boolean removed;
        synchronized (cacheLock) {
            removed = cache.remove(uuid) != null;
            pendingUpload.remove(uuid);
            if (confirmed) {
                pendingDeletes.remove(uuid);
            } else {
                pendingDeletes.add(uuid);
            }
            saveLocal(uuid, null);
            if (channel) {
                network.hashDelete(MUTE_HASH_KEY, uuid.toString());
                publish(uuid, null, true);
            }
        }
        return removed;
    }

    private void publish(UUID uuid, String value, boolean remove) {
        NetworkService network = network();
        if (network == null || !network.available()) {
            return;
        }
        Map<String, String> payload = new HashMap<>();
        payload.put("uuid", uuid.toString());
        payload.put("remove", Boolean.toString(remove));
        if (value != null) {
            payload.put("value", value);
        }
        network.publish(MUTE_UPDATE_TYPE, payload);
    }

    private void handleUpdate(String json) {
        Map<String, String> payload = parseJson(json);
        if (payload == null) {
            return;
        }
        String uuidString = payload.get("uuid");
        if (uuidString == null) {
            return;
        }
        try {
            UUID uuid = UUID.fromString(uuidString);
            synchronized (cacheLock) {
                if (Boolean.parseBoolean(payload.get("remove"))) {
                    cache.remove(uuid);
                    pendingUpload.remove(uuid);
                    pendingDeletes.remove(uuid);
                    saveLocal(uuid, null);
                } else {
                    MuteInfo info = parse(payload.get("value"));
                    if (info != null) {
                        cache.put(uuid, info);
                        pendingUpload.remove(uuid);
                        pendingDeletes.remove(uuid);
                        saveLocal(uuid, serialize(info));
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            // 忽略
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseJson(String json) {
        try {
            return JSON.fromJson(json, Map.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private void saveLocal(UUID uuid, String value) {
        submitLocalWrite(() -> {
            File file = new File(plugin.getDataFolder(), "mute.yml");
            YamlConfiguration yaml = file.exists()
                    ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
            yaml.set("format-version", FORMAT_VERSION);
            yaml.set("mutes." + uuid, value);
            try {
                com.junxiex.mikuchat.core.util.AtomicFiles.saveYaml(yaml, file);
            } catch (Exception e) {
                plugin.getLogger().warning("保存 mute.yml 失败: " + e.getMessage());
            }
        });
    }

    private void submitLocalWrite(Runnable task) {
        try {
            localFileExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 插件正在停用，丢弃本次落盘请求
        }
    }

    /** 周期清理过期禁言；批量删除后只重写一次文件。 */
    public void purge() {
        NetworkService network = network();
        boolean networkReady = network != null && network.available();
        List<UUID> expired = new ArrayList<>();
        synchronized (cacheLock) {
            for (Map.Entry<UUID, MuteInfo> entry : cache.entrySet()) {
                if (!entry.getValue().expired()) {
                    continue;
                }
                if (cache.remove(entry.getKey(), entry.getValue())) {
                    pendingUpload.remove(entry.getKey());
                    expired.add(entry.getKey());
                }
            }
            if (expired.isEmpty()) {
                return;
            }
            writeWholeLocalFile(cache, pendingUpload);
        }
        if (networkReady) {
            for (UUID uuid : expired) {
                network.hashDelete(MUTE_HASH_KEY, uuid.toString());
            }
        }
        plugin.debug("已清理 " + expired.size() + " 条过期禁言。");
    }

    /** 整份重写本地镜像；pending 中的记录写 P| 前缀。 */
    private void writeWholeLocalFile(Map<UUID, MuteInfo> snapshot, Set<UUID> pending) {
        Map<UUID, MuteInfo> copy = new HashMap<>(snapshot);
        Set<UUID> pendingCopy = new HashSet<>(pending);
        submitLocalWrite(() -> {
            File file = new File(plugin.getDataFolder(), "mute.yml");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("format-version", FORMAT_VERSION);
            for (Map.Entry<UUID, MuteInfo> entry : copy.entrySet()) {
                String value = serialize(entry.getValue());
                yaml.set("mutes." + entry.getKey(),
                        pendingCopy.contains(entry.getKey()) ? PENDING_PREFIX + value : value);
            }
            try {
                com.junxiex.mikuchat.core.util.AtomicFiles.saveYaml(yaml, file);
            } catch (Exception e) {
                plugin.getLogger().warning("同步 mute.yml 失败: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        localFileExecutor.shutdown();
        try {
            if (!localFileExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                localFileExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            localFileExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void subscribe() {
        NetworkService network = network();
        if (network != null) {
            network.subscribe(MUTE_UPDATE_TYPE, updateHandler);
            network.subscribe(com.junxiex.mikuchat.module.network.VelocityNetworkModule.TYPE_STATE_SYNC, syncHandler);
        }
    }

    public void unsubscribe() {
        NetworkService network = network();
        if (network != null) {
            network.unsubscribe(MUTE_UPDATE_TYPE, updateHandler);
            network.unsubscribe(com.junxiex.mikuchat.module.network.VelocityNetworkModule.TYPE_STATE_SYNC, syncHandler);
        }
    }

    @Override
    public String formatRemaining(long millis) {
        if (millis <= 0) {
            return plugin.lang().raw("mute.permanent");
        }
        long totalSeconds = millis / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append("d ");
        }
        if (hours > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0) {
            builder.append(minutes).append("m ");
        }
        builder.append(seconds).append('s');
        return builder.toString().trim();
    }
}
