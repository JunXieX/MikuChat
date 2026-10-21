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
 * NetworkService 每次都从服务注册表实时获取，避免"启用时跨服尚未就绪就永久不跨服"。
 * <p>
 * 本地文件里只有「代理不可用时新增、尚未确认写入共享存储」的记录带 {@code P|} 前缀，
 * 启动时也只回填这些记录：若把所有本地记录都回填，本服离线期间别的服务器解除的禁言
 * 会被重新推回代理，导致已解除的禁言在整网复活。
 */
public final class MuteManager implements MuteService {

    private static final String MUTE_HASH_KEY = "mikuchat:mutes";
    private static final String MUTE_UPDATE_TYPE = "MUTE_UPDATE";
    private static final String PENDING_PREFIX = "P|";
    private static final int MAX_STATE_PAGES = 64;
    private static final com.google.gson.Gson JSON = new com.google.gson.Gson();

    private final MikuChat plugin;
    /**
     * 当前禁言快照。整体替换（而不是就地 clear+putAll），读者要么看到完整旧快照、要么看到完整新快照，
     * 不会出现「快照已清空但尚未填入」导致禁言短暂失效的窗口。
     */
    private volatile Map<UUID, MuteInfo> cache = new ConcurrentHashMap<>();
    /** 所有缓存变更与全量快照替换都在此锁内完成，避免 purge 与并发 mute 互相删除刚写入的记录。 */
    private final Object cacheLock = new Object();
    /** 代理不可用期间新增、尚未确认写入共享存储的禁言（仅这些会被回填）。 */
    private final Set<UUID> pendingUpload = ConcurrentHashMap.newKeySet();
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
            loadLocalFile(loaded);
            loaded.entrySet().removeIf(entry -> entry.getValue().expired());
            this.cache = new ConcurrentHashMap<>(loaded);
            this.statePage = 0;
            this.stateAcc.clear();
            NetworkService network = network();
            if (network != null && network.available()) {
                // 只回填「离线期间新增」的记录，随后以代理端快照为准覆盖本地：
                // 顺序不能颠倒，否则旧记录会在代理端重新出现。
                uploadPendingToShared(network);
                network.requestState();
            }
        }
    }

    /**
     * 代理端下发的全量共享状态：以代理端为准覆盖本地缓存。
     * <p>
     * 代理端（{@code SharedStore}）既是唯一权威，也已落盘持久化，因此这里必须是「覆盖」而不是
     * 「合并」：若保留本地存在但共享快照里没有的记录，那么在别的服务器解除禁言后，
     * 本服从 {@code mute.yml} 恢复出来的旧记录会被永久保留，玩家将一直被误禁言。
     * <p>
     * 大表会被代理端拆成多页下发，这里按 page/pages 聚合，收齐后一次性提交。
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
                plugin.debug("忽略非法的共享状态页码: " + page + "/" + pages);
                return;
            }
            synchronized (cacheLock) {
                if (page == 1) {
                    stateAcc.clear();
                } else if (statePage != page - 1) {
                    // 乱序或缺页：丢弃本页，等下一次 STATE_REQ 重新拉取全量
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
                statePage = 0;
                loaded.entrySet().removeIf(entry -> entry.getValue().expired());
                commitSnapshot(loaded);
            }
        } catch (Throwable t) {
            plugin.debug("解析共享状态失败: " + t.getMessage());
        }
    }

    /** 用权威快照替换本地状态；内容完全一致时跳过重建与磁盘重写。调用方需持有 cacheLock。 */
    private void commitSnapshot(Map<UUID, MuteInfo> loaded) {
        Map<UUID, MuteInfo> previous = this.cache;
        // 离线期间新增、但快照里没有（回填尚未生效/丢包）的记录保留并重发，避免本地管理员的操作被静默抹掉
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
        if (stillPending.isEmpty() && loaded.equals(previous)) {
            // 与当前一致：不重建 map、不重写文件（避免每次全量同步都产生一次磁盘原子替换）
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
        }
    }

    /** 代理端可用时，把仅存在于本地的「待同步」禁言回填到共享存储。 */
    private void uploadPendingToShared(NetworkService network) {
        for (UUID uuid : pendingUpload) {
            MuteInfo info = cache.get(uuid);
            if (info != null && !info.expired()) {
                network.hashSet(MUTE_HASH_KEY, uuid.toString(), serialize(info));
            }
        }
    }

    private void loadLocalFile(Map<UUID, MuteInfo> loaded) {
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
        if (info == null || info.expired()) {
            // 读路径不做任何 I/O 或广播，过期清理交给周期任务 purge()
            return null;
        }
        return info;
    }

    public void mute(UUID uuid, long durationMillis, String reason) {
        long expireAt = durationMillis <= 0 ? 0 : System.currentTimeMillis() + durationMillis;
        MuteInfo info = new MuteInfo(reason == null ? "" : reason, expireAt);
        NetworkService network = network();
        boolean shared = network != null && network.available();
        synchronized (cacheLock) {
            cache.put(uuid, info);
            if (shared) {
                pendingUpload.remove(uuid);
                network.hashSet(MUTE_HASH_KEY, uuid.toString(), serialize(info));
                saveLocal(uuid, serialize(info));
            } else {
                // 代理不可用：先记待同步标记，等代理恢复后只回填这些记录
                pendingUpload.add(uuid);
                saveLocal(uuid, PENDING_PREFIX + serialize(info));
            }
            publish(uuid, serialize(info), false);
        }
    }

    public boolean unmute(UUID uuid) {
        NetworkService network = network();
        boolean shared = network != null && network.available();
        boolean removed;
        synchronized (cacheLock) {
            removed = cache.remove(uuid) != null;
            pendingUpload.remove(uuid);
            if (shared) {
                network.hashDelete(MUTE_HASH_KEY, uuid.toString());
            }
            saveLocal(uuid, null);
            if (shared) {
                // 删除广播是幂等的：即便本地没有缓存，其它服务器的缓存也可能仍有这条记录，
                // 不广播的话它们会继续拦截该玩家直到下一次全量同步。
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

    /**
     * 处理其它服务器发来的禁言变更。
     * <p>
     * 必须同步维护 {@code mute.yml}：本地回落文件在 {@link #load()} 时会被当作
     * 「本地独有禁言」回填给代理，若这里只改内存不落盘，别的服务器解除禁言后本服文件
     * 仍保留旧记录，重启或 reload 时就会把它重新推回共享存储，让已解除的禁言复活。
     */
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
                    saveLocal(uuid, null);
                } else {
                    MuteInfo info = parse(payload.get("value"));
                    if (info != null) {
                        cache.put(uuid, info);
                        pendingUpload.remove(uuid);
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

    /**
     * 周期清理：把已过期禁言从本地缓存、共享存储与回落文件中一并移除。
     * <p>
     * 判定与删除必须在同一把锁内完成：先收集再删除会让并发写入的新禁言被误删
     * （删掉刚生效的跨服禁言），因此这里逐条做「仍过期才删」的条件删除。
     */
    public void purge() {
        NetworkService network = network();
        boolean networkReady = network != null && network.available();
        List<UUID> expired = new ArrayList<>();
        synchronized (cacheLock) {
            for (Map.Entry<UUID, MuteInfo> entry : cache.entrySet()) {
                MuteInfo info = entry.getValue();
                if (!info.expired()) {
                    continue;
                }
                if (!cache.remove(entry.getKey(), info)) {
                    continue;
                }
                pendingUpload.remove(entry.getKey());
                saveLocal(entry.getKey(), null);
                if (networkReady) {
                    network.hashDelete(MUTE_HASH_KEY, entry.getKey().toString());
                }
                expired.add(entry.getKey());
            }
        }
        if (!expired.isEmpty()) {
            plugin.debug("已清理 " + expired.size() + " 条过期禁言。");
        }
    }

    /**
     * 用给定快照整体重写 {@code mute.yml}，使本地回落文件与代理端权威状态保持一致。
     * <p>
     * 写入集中在单线程执行器，避免与 {@link #saveLocal} 的单条写入交叉导致文件错乱；
     * 整份重写而不是逐条删除，能一并清掉代理端已不存在的历史记录。
     */
    private void writeWholeLocalFile(Map<UUID, MuteInfo> snapshot, Set<UUID> pending) {
        Map<UUID, MuteInfo> copy = new HashMap<>(snapshot);
        Set<UUID> pendingCopy = new HashSet<>(pending);
        submitLocalWrite(() -> {
            File file = new File(plugin.getDataFolder(), "mute.yml");
            YamlConfiguration yaml = new YamlConfiguration();
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
