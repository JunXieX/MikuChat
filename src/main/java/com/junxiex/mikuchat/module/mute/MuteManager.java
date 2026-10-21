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
    /** 本地文件格式版本：2 起支持 P| 待同步前缀；旧文件升级时要把历史记录回填一次。 */
    private static final int FORMAT_VERSION = 2;
    /** 与代理端分页上限对应：单页约 50-150 条，512 页足以覆盖数万条禁言。 */
    private static final int MAX_STATE_PAGES = 512;
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
    /** 代理未确认时执行的解除操作：等全量同步时强制从快照中剔除并重发删除，避免禁言复活。 */
    private final Set<UUID> pendingDeletes = ConcurrentHashMap.newKeySet();
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
    /** 全量同步进行期间发生的本地禁言变更：提交快照时以这些本地变更覆盖旧快照。 */
    private final Set<UUID> touchedDuringSync = new HashSet<>();
    /** 本次同步用于决定「旧格式文件迁移」：代理快照为空时才回填本地历史，否则以代理为准。 */
    private boolean legacySync;
    /**
     * 旧格式尚未完成迁移：此期间本地写入不写 format-version，
     * 否则「升级后先独立运行、后接代理」时迁移标记会被提前抹掉，历史禁言会在首个空快照时丢失。
     */
    private boolean migrationPending;

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
            touchedDuringSync.clear();
            boolean legacyFormat = loadLocalFile(loaded);
            this.migrationPending = legacyFormat;
            loaded.entrySet().removeIf(entry -> entry.getValue().expired());
            this.cache = new ConcurrentHashMap<>(loaded);
            this.statePage = 0;
            this.stateAcc.clear();
            this.legacySync = false;
            NetworkService network = network();
            if (network != null && network.available()) {
                if (legacyFormat) {
                    // 旧格式文件（1.6.x，或未带版本号的中间版本）无法区分「本地独有」与「已在代理」：
                    // 不能直接回填——那会把本服离线期间别服已解除的禁言重新推成权威状态。
                    // 先拉取权威快照，由 commitSnapshot 决定：代理为空才回填，非空则以代理为准。
                    legacySync = true;
                } else {
                    // 只回填「离线期间新增」的记录，随后以代理端快照为准覆盖本地：
                    // 顺序不能颠倒，否则旧记录会在代理端重新出现。
                    uploadPendingToShared(network);
                }
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
                // 页数超限说明禁言表远超预期（或对端异常）：不能静默失效，否则跨服同步永久停摆
                plugin.getLogger().warning("共享状态页数异常（" + page + "/" + pages
                        + "，上限 " + MAX_STATE_PAGES + "），已忽略本次快照；请检查禁言记录数量。");
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
                loaded.entrySet().removeIf(entry -> entry.getValue().expired());
                // 页间窗口（statePage 非零期间）发生的 mute/unmute 已记入 touchedDuringSync；
                // 提交在锁内执行，不再需要保持非零，先复位避免提交异常时留下脏状态。
                statePage = 0;
                commitSnapshot(loaded);
            }
        } catch (Throwable t) {
            plugin.debug("解析共享状态失败: " + t.getMessage());
        }
    }

    /** 用权威快照替换本地状态；内容完全一致时跳过重建与磁盘重写。调用方需持有 cacheLock。 */
    private void commitSnapshot(Map<UUID, MuteInfo> loaded) {
        Map<UUID, MuteInfo> previous = this.cache;
        // 旧格式迁移且代理为空时保留的本地记录：它们没有出现在快照里，必须强制回填一次
        Set<UUID> legacyForce = new HashSet<>();
        if (legacySync) {
            migrationPending = false;
            // 旧格式迁移：代理端为空（首次由本服接管）才保留本地历史并回填；
            // 代理端已有数据说明它才是权威，本地独有记录可能早已被别服解除，直接丢弃并告警。
            legacySync = false;
            if (loaded.isEmpty()) {
                for (Map.Entry<UUID, MuteInfo> entry : previous.entrySet()) {
                    if (!entry.getValue().expired()) {
                        loaded.put(entry.getKey(), entry.getValue());
                        pendingUpload.add(entry.getKey());
                        legacyForce.add(entry.getKey());
                    }
                }
                if (!loaded.isEmpty()) {
                    plugin.getLogger().info("检测到旧版本地禁言 " + loaded.size()
                            + " 条且代理端为空，已回填为权威状态并写入新格式。");
                }
            } else {
                int dropped = 0;
                for (UUID uuid : previous.keySet()) {
                    if (!loaded.containsKey(uuid)) {
                        dropped++;
                    }
                }
                if (dropped > 0) {
                    plugin.getLogger().warning("检测到 " + dropped + " 条旧版本地禁言与代理端权威状态不一致，"
                            + "已按代理端覆盖（避免已解除的禁言复活）。");
                }
            }
        }
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
        // 代理未确认时执行过的解除：强制从快照里剔除；若快照仍包含，说明删除尚未传播，保留重试。
        Set<UUID> stillDelete = new HashSet<>();
        for (UUID uuid : pendingDeletes) {
            if (loaded.remove(uuid) != null || previous.containsKey(uuid)) {
                stillDelete.add(uuid);
            }
        }
        pendingDeletes.retainAll(stillDelete);
        // 同步进行期间发生的本地变更（Folia 上插件消息线程与命令线程不同）：以本地最新状态为准
        Set<UUID> touched = new HashSet<>(touchedDuringSync);
        touchedDuringSync.clear();
        Set<UUID> removedLocally = new HashSet<>();
        for (UUID uuid : touched) {
            MuteInfo local = previous.get(uuid);
            if (local != null && !local.expired()) {
                loaded.put(uuid, local);
                // 这条变更可能不在这份快照里，必须继续保持待同步并重新上传
                stillPending.add(uuid);
            } else {
                loaded.remove(uuid);
                removedLocally.add(uuid);
            }
        }
        stillPending.addAll(legacyForce);
        if (stillPending.isEmpty() && pendingUpload.isEmpty() && pendingDeletes.isEmpty()
                && loaded.equals(previous)) {
            // 与当前一致：不重建 map、不重写文件（避免每次全量同步都产生一次磁盘原子替换）
            return;
        }
        this.cache = new ConcurrentHashMap<>(loaded);
        // 注意：只要 pendingUpload 里有已确认的条目，也必须走这里清掉 P| 前缀与内存标记，
        // 因此提前返回条件里带上 pendingUpload.isEmpty()（否则 P| 会残留到下次重启并重新上传）。
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
            for (UUID uuid : removedLocally) {
                network.hashDelete(MUTE_HASH_KEY, uuid.toString());
            }
            for (UUID uuid : stillDelete) {
                network.hashDelete(MUTE_HASH_KEY, uuid.toString());
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

    /**
     * 读取本地回落文件。
     *
     * @return true 表示这是没有格式版本号的旧文件（升级场景），调用方需要把记录回填一次并写回版本号
     */
    private boolean loadLocalFile(Map<UUID, MuteInfo> loaded) {
        File file = new File(plugin.getDataFolder(), "mute.yml");
        if (!file.exists()) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("mutes");
        boolean legacy = yaml.getInt("format-version", 0) < FORMAT_VERSION;
        // 未确认的解除意图（tombstone）：重启后继续向代理端重发删除，避免解禁被旧快照回滚
        for (String value : yaml.getStringList("deletes")) {
            try {
                pendingDeletes.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID 忽略
            }
        }
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
                // 只有真正带 P| 前缀的「离线新增」记录才进待回填集合；
                // 旧格式记录统一交给 legacySync 逻辑按「代理是否为空」决定回填还是丢弃。
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
        boolean channel = network != null && network.available();
        // 只有「确实收到过代理端签名消息」才算写入已确认；通道注册成功不代表代理插件在线
        boolean confirmed = network != null && network.connected();
        synchronized (cacheLock) {
            cache.put(uuid, info);
            if (pendingDeletes.remove(uuid)) {
                saveDeletes();
            }
            if (statePage > 0) {
                touchedDuringSync.add(uuid);
            }
            if (channel) {
                // 先入队：代理恢复后会自动送出；未确认时同时保留 P| 标记，等快照确认后再降级
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
        boolean shared = network != null && network.available();
        boolean confirmed = network != null && network.connected();
        boolean removed;
        synchronized (cacheLock) {
            removed = cache.remove(uuid) != null;
            pendingUpload.remove(uuid);
            if (confirmed) {
                pendingDeletes.remove(uuid);
            } else {
                // 代理未确认删除成功：记录待删除（落盘），等下次全量同步时再次剔除并重发
                pendingDeletes.add(uuid);
            }
            saveDeletes();
            if (statePage > 0) {
                touchedDuringSync.add(uuid);
            }
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
                if (statePage > 0) {
                    touchedDuringSync.add(uuid);
                }
                if (Boolean.parseBoolean(payload.get("remove"))) {
                    cache.remove(uuid);
                    pendingUpload.remove(uuid);
                    if (pendingDeletes.remove(uuid)) {
                        saveDeletes();
                    }
                    saveLocal(uuid, null);
                } else {
                    MuteInfo info = parse(payload.get("value"));
                    if (info != null) {
                        cache.put(uuid, info);
                        pendingUpload.remove(uuid);
                        if (pendingDeletes.remove(uuid)) {
                            saveDeletes();
                        }
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
            if (!migrationPending) {
                yaml.set("format-version", FORMAT_VERSION);
            }
            yaml.set("mutes." + uuid, value);
            try {
                com.junxiex.mikuchat.core.util.AtomicFiles.saveYaml(yaml, file);
            } catch (Exception e) {
                plugin.getLogger().warning("保存 mute.yml 失败: " + e.getMessage());
            }
        });
    }

    /** 持久化「未确认的解除」列表，保证重启后仍会向代理端重发删除。 */
    private void saveDeletes() {
        List<String> copy = new ArrayList<>();
        for (UUID uuid : pendingDeletes) {
            copy.add(uuid.toString());
        }
        submitLocalWrite(() -> {
            File file = new File(plugin.getDataFolder(), "mute.yml");
            YamlConfiguration yaml = file.exists()
                    ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
            if (!migrationPending) {
                yaml.set("format-version", FORMAT_VERSION);
            }
            yaml.set("deletes", copy);
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
        List<String> deleteCopy = new ArrayList<>();
        for (UUID uuid : pendingDeletes) {
            deleteCopy.add(uuid.toString());
        }
        submitLocalWrite(() -> {
            File file = new File(plugin.getDataFolder(), "mute.yml");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("format-version", FORMAT_VERSION);
            yaml.set("deletes", deleteCopy);
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
