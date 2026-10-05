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

    /** 代理端下发的全量共享状态：以代理端为准覆盖本地缓存。 */
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
            cache.clear();
            cache.putAll(loaded);
            purgeExpired();
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
        if (network != null && network.available()) {
            network.hashDelete(MUTE_HASH_KEY, uuid.toString());
        }
        saveLocal(uuid, null);
        publish(uuid, null, true);
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
        localFileExecutor.execute(() -> {
            File file = new File(plugin.getDataFolder(), "mute.yml");
            YamlConfiguration yaml = file.exists()
                    ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
            yaml.set("mutes." + uuid, value);
            try {
                yaml.save(file);
            } catch (Exception e) {
                plugin.getLogger().warning("保存 mute.yml 失败: " + e.getMessage());
            }
        });
    }

    private void purgeExpired() {
        cache.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    /** 周期清理：把已过期禁言从本地缓存、共享存储与回落文件中一并移除。 */
    public void purge() {
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, MuteInfo> entry : cache.entrySet()) {
            if (entry.getValue().expired()) {
                expired.add(entry.getKey());
            }
        }
        for (UUID uuid : expired) {
            unmute(uuid);
        }
    }

    /** 关闭本地回落文件的写入线程，供模块停用时调用。 */
    public void shutdown() {
        localFileExecutor.shutdown();
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
