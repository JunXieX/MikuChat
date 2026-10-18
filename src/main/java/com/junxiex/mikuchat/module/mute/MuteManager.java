package com.junxiex.mikuchat.module.mute;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.service.MuteService;
import com.junxiex.mikuchat.core.service.NetworkService;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * 禁言管理器：以代理端共享存储为准实现跨服同步；代理不可用时回落本地 mute.yml。
 * 变更通过 MUTE_UPDATE 实时通知其它服务器，全量状态由代理端在收到 STATE_REQ 后下发。
 * <p>
 * NetworkService 每次都从服务注册表实时获取，避免"启用时跨服尚未就绪就永久不跨服"。
 */
public final class MuteManager implements MuteService {

    private static final String MUTE_HASH_KEY = "mikuchat:mutes";
    private static final String MUTE_UPDATE_TYPE = "MUTE_UPDATE";
    private static final com.google.gson.Gson JSON = new com.google.gson.Gson();

    private final MikuChat plugin;
    private final Map<UUID, MuteInfo> cache = new ConcurrentHashMap<>();
    private final BiConsumer<String, String> updateHandler = (source, json) -> handleUpdate(json);
    private final BiConsumer<String, String> syncHandler = (source, json) -> handleStateSync(json);
    /**
     * mute.yml 是纯本地回落存储。所有写入集中到单线程执行器，既避免主线程 I/O，
     * 又保证严格按提交顺序落盘；原先的线程池 + 锁只能互斥、无法保证顺序，
     * 密集 mute/unmute 时可能把「已解除」写成最终状态。
     */
    private final ExecutorService localFileExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MikuChat-MuteFile");
        thread.setDaemon(true);
        return thread;
    });

    public MuteManager(MikuChat plugin) {
        this.plugin = plugin;
    }

    private NetworkService network() {
        return plugin.services().find(NetworkService.class).orElse(null);
    }

    public void load() {
        cache.clear();
        // 本地文件很小，同步读取可以接受
        loadLocalFile();
        purgeExpired();
        NetworkService network = network();
        if (network != null && network.available()) {
            // 先把本地独有的禁言回填到代理端，再请求全量状态：代理端按序处理，
            // 回填内容会包含在随后下发的 STATE_SYNC 中，不会出现互相覆盖。
            uploadLocalToShared();
            network.requestState();
        }
    }

    /**
     * 代理端下发的全量共享状态：以代理端为准覆盖本地缓存。
     * <p>
     * 代理端（{@code SharedStore}）既是唯一权威，也已落盘持久化，因此这里必须是「覆盖」而不是
     * 「合并」：若保留本地存在但共享快照里没有的记录，那么在别的服务器解除禁言后，
     * 本服从 {@code mute.yml} 恢复出来的旧记录会被永久保留，玩家将一直被误禁言。
     */
    private void handleStateSync(String json) {
        try {
            com.google.gson.JsonObject object =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!object.has("key") || !MUTE_HASH_KEY.equals(object.get("key").getAsString())) {
                return;
            }
            Map<UUID, MuteInfo> loaded = new HashMap<>();
            if (object.has("map")) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry
                        : object.getAsJsonObject("map").entrySet()) {
                    MuteInfo info = parse(entry.getValue().getAsString());
                    if (info == null) {
                        continue;
                    }
                    try {
                        loaded.put(UUID.fromString(entry.getKey()), info);
                    } catch (IllegalArgumentException ignored) {
                        // 非法 UUID 忽略
                    }
                }
            }
            // 代理端是唯一权威：直接覆盖，不做合并（原因见方法注释）
            cache.clear();
            cache.putAll(loaded);
            purgeExpired();
            // 关键：把权威结果回写到本地回落文件。否则本文件里的旧记录会在下次
            // load() 时被当作「本地独有禁言」重新回填给代理，使已解除的禁言复活。
            syncLocalFileToCache();
        } catch (Throwable t) {
            plugin.debug("解析共享状态失败: " + t.getMessage());
        }
    }

    /** 代理端可用时，把仅存在于本地的禁言回填到共享存储，避免以其为源导致跨服不同步。 */
    private void uploadLocalToShared() {
        NetworkService network = network();
        if (network == null || !network.available()) {
            return;
        }
        for (Map.Entry<UUID, MuteInfo> entry : cache.entrySet()) {
            network.hashSet(MUTE_HASH_KEY, entry.getKey().toString(), serialize(entry.getValue()));
        }
    }

    private void loadLocalFile() {
        File file = new File(plugin.getDataFolder(), "mute.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("mutes");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            applyStored(key, section.getString(key));
        }
    }

    private void applyStored(String uuidString, String value) {
        MuteInfo info = parse(value);
        if (info == null) {
            return;
        }
        try {
            cache.put(UUID.fromString(uuidString), info);
        } catch (IllegalArgumentException ignored) {
            // 非法 UUID 忽略
        }
    }

    private MuteInfo parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split("\\|", 2);
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
        if (info == null || info.expired()) {
            // 读路径不做任何 I/O 或广播，过期清理交给周期任务 purge()
            return null;
        }
        return info;
    }

    public void mute(UUID uuid, long durationMillis, String reason) {
        long expireAt = durationMillis <= 0 ? 0 : System.currentTimeMillis() + durationMillis;
        MuteInfo info = new MuteInfo(reason == null ? "" : reason, expireAt);
        cache.put(uuid, info);
        String value = serialize(info);
        NetworkService network = network();
        if (network != null && network.available()) {
            network.hashSet(MUTE_HASH_KEY, uuid.toString(), value);
        }
        saveLocal(uuid, value);
        publish(uuid, value, false);
    }

    public boolean unmute(UUID uuid) {
        boolean removed = cache.remove(uuid) != null;
        NetworkService network = network();
        boolean networkReady = network != null && network.available();
        if (networkReady) {
            network.hashDelete(MUTE_HASH_KEY, uuid.toString());
        }
        saveLocal(uuid, null);
        publish(uuid, null, true);
        // 代理可用时以共享存储为准：它里面可能还有一条本服尚未同步到缓存的禁言，
        // 此时本地 cache 没命中不代表「本来就没禁言」，两者任一命中都应报告已解除。
        return removed || networkReady;
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
            if (Boolean.parseBoolean(payload.get("remove"))) {
                cache.remove(uuid);
            } else {
                MuteInfo info = parse(payload.get("value"));
                if (info != null) {
                    cache.put(uuid, info);
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
            yaml.set("mutes." + uuid, value);
            try {
                com.junxiex.mikuchat.core.util.AtomicFiles.saveYaml(yaml, file);
            } catch (Exception e) {
                plugin.getLogger().warning("保存 mute.yml 失败: " + e.getMessage());
            }
        });
    }

    /**
     * 提交本地回落写入。停服瞬间执行器已 shutdown 时 execute 会抛 RejectedExecutionException，
     * 该异常会顺着 /mute 的调用栈冒到命令线程，因此统一在此吞掉。
     */
    private void submitLocalWrite(Runnable task) {
        try {
            localFileExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 插件正在停用，丢弃本次落盘请求
        }
    }

    private void purgeExpired() {
        cache.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    /**
     * 周期清理：把已过期禁言从本地缓存、共享存储与回落文件中一并移除。
     * <p>
     * 过期禁言对玩家已无约束力（{@link #muteOf} 本就把过期记录视为未禁言），因此这里只需要
     * 清掉残留数据，不需要再逐个 {@code publish} 广播：每 5 分钟一次全量广播既浪费插件消息带宽，
     * 也会让其它服务器做无意义的缓存写入。共享存储用 {@code hashDelete} 同步删除即可。
     */
    public void purge() {
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, MuteInfo> entry : cache.entrySet()) {
            if (entry.getValue().expired()) {
                expired.add(entry.getKey());
            }
        }
        if (expired.isEmpty()) {
            return;
        }
        NetworkService network = network();
        boolean networkReady = network != null && network.available();
        for (UUID uuid : expired) {
            // 先移除缓存：即便后续 I/O 失败，玩家也不会因过期记录被继续拦下
            if (cache.remove(uuid) == null) {
                continue;
            }
            if (networkReady) {
                network.hashDelete(MUTE_HASH_KEY, uuid.toString());
            }
            saveLocal(uuid, null);
        }
    }

    /**
     * 用当前缓存整体重写 {@code mute.yml}，使本地回落文件与代理端权威状态保持一致。
     * <p>
     * 写入集中在单线程执行器，避免与 {@link #saveLocal} 的单条写入交叉导致文件错乱；
     * 整份重写而不是逐条删除，能一并清掉代理端已不存在的历史记录。
     */
    private void syncLocalFileToCache() {
        Map<UUID, MuteInfo> snapshot = new HashMap<>(cache);
        submitLocalWrite(() -> {
            File file = new File(plugin.getDataFolder(), "mute.yml");
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, MuteInfo> entry : snapshot.entrySet()) {
                yaml.set("mutes." + entry.getKey(), serialize(entry.getValue()));
            }
            try {
                com.junxiex.mikuchat.core.util.AtomicFiles.saveYaml(yaml, file);
            } catch (Exception e) {
                plugin.getLogger().warning("同步 mute.yml 失败: " + e.getMessage());
            }
        });
    }

    /** 关闭本地回落文件的写入线程，供模块停用时调用。等待已排队的写入落盘，避免停服瞬间丢数据。 */
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
