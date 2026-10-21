package com.junxiex.mikuchat.module.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.service.NetworkService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 跨服网络模块：由 Velocity 代理端插件承接转发、在线查询与共享状态保管，后端只负责收发插件消息。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>插件消息必须借由玩家连接发送，因此本服无玩家在线时，对外消息会暂存到队列，
 *       待玩家接入后补发（禁言等状态变更正依赖这一点）。</li>
 *   <li>在线查询由代理端直接查表应答，不做广播，因此没有超时收敛问题。</li>
 *   <li>共享状态（禁言）由代理端持有并落盘，后端启动或重载后主动请求一次全量同步。</li>
 * </ul>
 */
public final class VelocityNetworkModule implements ChatModule, NetworkService, Listener, PluginMessageListener {

    /** 请求代理下发全量共享状态。 */
    public static final String TYPE_STATE_REQ = "STATE_REQ";
    /** 代理下发的全量共享状态：{@code {"key": 键, "map": {字段: 值}}}。 */
    public static final String TYPE_STATE_SYNC = "STATE_SYNC";
    /** 写入共享状态：{@code {"key","field","value"}}。 */
    public static final String TYPE_KV_SET = "KV_SET";
    /** 删除共享状态：{@code {"key","field"}}。 */
    public static final String TYPE_KV_DEL = "KV_DEL";
    /** 跨服在线查询请求：{@code {"token"}}。 */
    public static final String TYPE_WHO_REQ = "WHO_REQ";
    /** 跨服在线查询应答：{@code {"token","servers":{服务器: [玩家名]}}}。 */
    public static final String TYPE_WHO_RES = "WHO_RES";

    /** 去重窗口：代理端可能通过多条连接重复投递同一条消息。 */
    private static final int SEEN_CAPACITY = 512;

    /**
     * 代理端自身发出的消息所使用的来源标识，与 Velocity 端 {@code ProxyBridge} 保持一字不差。
     * <p>
     * 代理端的 {@code WHO_RES} / {@code STATE_SYNC} 都由它发出，因此白名单校验必须显式放行它，
     * 否则一旦服主按 network.yml 的建议填了 {@code trusted-servers}，跨服在线查询与禁言同步
     * 会被本插件自己丢掉，表现为「查询一直无响应、禁言不跨服」。
     */
    private static final String PROXY_SOURCE = "__proxy__";

    private final MikuChat plugin;
    private final ConfigFile config;
    private final Gson gson = new Gson();
    private final Map<String, List<BiConsumer<String, String>>> handlers = new ConcurrentHashMap<>();
    private final Map<String, PendingPresence> pendingPresence = new ConcurrentHashMap<>();
    /** 已处理消息 id 的有界 LRU，用于丢弃重复投递。 */
    private final Map<String, Boolean> seen = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SEEN_CAPACITY;
                }
            });
    /** 本服无玩家在线时暂存的待发消息，玩家接入后按序补发。 */
    private final Deque<byte[]> outbound = new ArrayDeque<>();
    /**
     * 可信来源服务器白名单（{@code network.yml: trusted-servers}）。为空表示不校验（兼容旧配置）；
     * 一旦配置，只接受 {@code src} 命中的消息，防止后端脱离代理运行时被伪造的插件消息注入。
     */
    private final Set<String> trustedServers = ConcurrentHashMap.newKeySet();

    private volatile String channel = "mikuchat:proxy";
    /** 与代理端共享的 HMAC 密钥；为空表示未配置，跨服功能不启用（否则客户端可伪造插件消息）。 */
    private volatile String sharedSecret = "";
    private volatile boolean available;
    /** 是否收到过通过签名校验的代理端消息：通道注册成功不代表代理端真的存在。 */
    private volatile boolean proxyConnected;
    /** 是否需要在下一名玩家接入时补发一次全量状态请求（本服曾无人在线而漏收实时更新）。 */
    private volatile boolean resyncNeeded;
    private volatile UUID carrierId;
    // 与 channel/available 一样由 enable() 在重载时重新赋值，而读取可能发生在
    // 异步线程（查询超时）或实体线程（入队容量），必须 volatile 保证可见性。
    private volatile int presenceTimeout = 1500;
    private volatile int queueCapacity = 1024;

    public VelocityNetworkModule(MikuChat plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().get("network.yml");
    }

    @Override
    public String id() {
        return "network";
    }

    @Override
    public void enable() {
        plugin.configManager().reload("network.yml");
        this.channel = config.config().getString("channel", "mikuchat:proxy");
        this.presenceTimeout = Math.max(1, config.config().getInt("presence-timeout-millis", 1500));
        // 队列容量必须为正：<=0 会让下面的裁剪循环永远成立（负数时直接死循环），
        // 而 0 会造成「每条都先被清空再入队」，等于队列失效。
        this.queueCapacity = Math.max(1, config.config().getInt("outbound-queue-capacity", 1024));
        this.trustedServers.clear();
        this.trustedServers.addAll(config.config().getStringList("trusted-servers"));

        this.sharedSecret = config.config().getString("shared-secret", "").trim();
        this.proxyConnected = false;
        this.resyncNeeded = true;
        if (sharedSecret.isEmpty()) {
            // 没有共享密钥就无法证明消息来自代理端：客户端同样能向该通道发送插件消息，
            // 伪造 STATE_SYNC / MUTE_UPDATE 即可清空禁言或冒充他人。宁可不启用跨服。
            plugin.getLogger().severe("跨服功能已禁用：network.yml 的 shared-secret 为空。"
                    + "请在后端 network.yml 与 Velocity 端 plugins/MikuChat/config.yml 配置相同的 shared-secret 后重载。");
            available = false;
            return;
        }

        if (!config.config().getBoolean("enabled", true)) {
            plugin.getLogger().info("跨服功能已在配置中关闭，仅启用本服聊天。");
            available = false;
            return;
        }
        try {
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, channel);
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, channel, this);
        } catch (Throwable t) {
            plugin.getLogger().warning("注册跨服通道失败（请确认服务端运行在 Velocity 代理下）: " + t.getMessage());
            available = false;
            return;
        }
        available = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.services().register(NetworkService.class, this);
        plugin.services().register(VelocityNetworkModule.class, this);
        plugin.getLogger().info("跨服通道已就绪，服务器标识 " + serverId()
                + "（须与 Velocity 中注册的服务器名一致）");
    }

    @Override
    public void disable() {
        available = false;
        proxyConnected = false;
        resyncNeeded = true;
        try {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, channel, this);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, channel);
        } catch (Throwable ignored) {
            // 忽略
        }
        org.bukkit.event.HandlerList.unregisterAll(this);
        carrierId = null;
        // 停用/重载时若有查询仍在等待应答，代理不会再回包：立即以空快照回调，
        // 否则调用方（/mchat who、跨服私聊探测）会一直停留在「正在查询」状态。
        // 先整体取出再回调：回调本身可能重新发起查询，边遍历边清空会漏掉或误丢那一条。
        List<PendingPresence> stalled = new ArrayList<>(pendingPresence.values());
        pendingPresence.clear();
        for (PendingPresence pending : stalled) {
            pending.callback.accept(new Presence(Map.of()));
        }
        // 这里不清空 outbound：这些消息通常是「本服无人在线时暂存的禁言变更」，
        // 清掉就等于丢数据。重载由本方法 + enable() 完成，队列原样保留待补发。
        // handlers 中保存着聊天/私聊/禁言等模块的订阅，重载时不能清除，
        // 否则 /mchat reload network 之后跨服功能会静默失效
        plugin.services().unregister(VelocityNetworkModule.class);
        plugin.services().unregister(NetworkService.class);
    }

    @Override
    public void reload() {
        disable();
        enable();
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public boolean connected() {
        return available && proxyConnected;
    }

    @Override
    public String serverId() {
        return plugin.serverId();
    }

    @Override
    public boolean publish(String type, Object payload) {
        if (!available) {
            return false;
        }
        send(buildEnvelope(type, payload), true);
        return true;
    }

    @Override
    public void subscribe(String type, BiConsumer<String, String> handler) {
        List<BiConsumer<String, String>> list = handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        if (!list.contains(handler)) {
            list.add(handler);
        }
    }

    @Override
    public void unsubscribe(String type, BiConsumer<String, String> handler) {
        List<BiConsumer<String, String>> list = handlers.get(type);
        if (list != null) {
            list.remove(handler);
        }
    }

    @Override
    public void queryPresence(Consumer<Presence> callback) {
        if (!available || carrier() == null) {
            // 无玩家在线时没有可用的插件消息连接，直接返回空快照（本服也没有玩家可展示）
            callback.accept(new Presence(Map.of()));
            return;
        }
        String token = UUID.randomUUID().toString();
        PendingPresence pending = new PendingPresence(callback);
        pendingPresence.put(token, pending);
        send(buildEnvelope(TYPE_WHO_REQ, Map.of("token", token)), false);
        plugin.scheduler().asyncLater(() -> {
            PendingPresence stalled = pendingPresence.remove(token);
            if (stalled != null) {
                plugin.debug("在线查询未在 " + presenceTimeout + "ms 内得到代理应答。");
                stalled.callback.accept(new Presence(Map.of()));
            }
        }, Math.max(1, presenceTimeout / 50));
    }

    @Override
    public void requestState() {
        if (!available) {
            return;
        }
        // 已提交一次全量同步请求；若之后本服玩家全部离线导致漏收消息，onQuit 会重新置位 resyncNeeded
        resyncNeeded = false;
        send(buildEnvelope(TYPE_STATE_REQ, Map.of()), true);
    }

    @Override
    public void hashSet(String key, String field, String value) {
        if (!available) {
            return;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("field", field);
        payload.put("value", value);
        send(buildEnvelope(TYPE_KV_SET, payload), true);
    }

    @Override
    public void hashDelete(String key, String field) {
        if (!available) {
            return;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("field", field);
        send(buildEnvelope(TYPE_KV_DEL, payload), true);
    }

    /** 玩家接入后补发暂存消息，并主动拉取一次全量共享状态。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!available) {
            return;
        }
        Player player = event.getPlayer();
        // 玩家刚接入时连接尚未就绪，延后一个 tick 再发送
        plugin.scheduler().atEntityLater(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            List<byte[]> queued;
            synchronized (outbound) {
                if (outbound.isEmpty()) {
                    queued = List.of();
                } else {
                    queued = new ArrayList<>(outbound);
                    outbound.clear();
                }
            }
            int sent = 0;
            Throwable failure = null;
            for (byte[] data : queued) {
                try {
                    player.sendPluginMessage(plugin, channel, data);
                    sent++;
                } catch (Throwable t) {
                    failure = t;
                    break;
                }
            }
            if (failure != null) {
                // 中途失败：把没发出去的部分原样放回队首，等下一个玩家接入时重试，
                // 否则一次连接异常就会静默丢掉整批禁言同步消息。
                List<byte[]> unsent = queued.subList(sent, queued.size());
                synchronized (outbound) {
                    for (int i = unsent.size() - 1; i >= 0; i--) {
                        outbound.addFirst(unsent.get(i));
                    }
                    // 重新入队后仍受容量约束
                    while (outbound.size() > queueCapacity) {
                        outbound.pollLast();
                    }
                }
                plugin.debug("补发跨服消息失败，剩余 " + unsent.size() + " 条已重新排队: " + failure.getMessage());
            } else if (!queued.isEmpty()) {
                plugin.debug("已补发 " + queued.size() + " 条暂存的跨服消息。");
            }
            // 只在「可能漏收实时消息」时补一次全量同步：启动后首名玩家接入、或本服曾无人在线、
            // 或补发失败。避免每次 join 都做一次全量 STATE_SYNC 与本地文件整写。
            if (resyncNeeded || failure != null) {
                resyncNeeded = false;
                send(buildEnvelope(TYPE_STATE_REQ, Map.of()), true);
            }
        }, 1L);
    }

    /** 最后一名玩家离开后标记「下次接入需补一次全量状态」，否则会漏收离开期间其它服务器的禁言变更。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!available) {
            return;
        }
        if (Bukkit.getOnlinePlayers().size() <= 1) {
            resyncNeeded = true;
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String incoming, @NotNull Player player, byte @NotNull [] message) {
        if (!available || !channel.equals(incoming)) {
            return;
        }
        dispatch(new String(message, StandardCharsets.UTF_8));
    }

    private void dispatch(String json) {
        String verified = verifySignature(json);
        if (verified == null) {
            plugin.debug("丢弃未通过签名校验的跨服消息（可能为伪造的客户端插件消息）。");
            return;
        }
        try {
            JsonObject object = JsonParser.parseString(verified).getAsJsonObject();
            String type = object.get("type").getAsString();
            String source = object.get("src").getAsString();
            // 能通过共享密钥校验的消息只可能由代理端签发，可认为跨服已实际连通
            proxyConnected = true;
            // 代理已排除发送方，这里是双保险，避免自己处理自己发出的消息
            if (source.equals(serverId())) {
                return;
            }
            // 配置了白名单时，只信任名单内的来源服务器，拒绝伪造/未知来源的插件消息。
            // 代理端自身的应答（WHO_RES / STATE_SYNC）来源固定为 PROXY_SOURCE，必须放行，
            // 否则填了 trusted-servers 反而会让跨服查询与禁言同步全部失效。
            // 只对代理端确实自产的这两种类型放行，避免其它类型借 PROXY_SOURCE 绕过白名单。
            boolean fromProxy = PROXY_SOURCE.equals(source)
                    && (TYPE_WHO_RES.equals(type) || TYPE_STATE_SYNC.equals(type));
            if (!trustedServers.isEmpty() && !fromProxy && !trustedServers.contains(source)) {
                plugin.debug("已忽略来自非受信服务器 " + source + " 的跨服消息 " + type);
                return;
            }
            String id = object.has("id") ? object.get("id").getAsString() : "";
            if (!id.isEmpty() && isDuplicate(id)) {
                return;
            }
            String data = object.has("data") ? object.get("data").toString() : "{}";
            if (TYPE_WHO_RES.equals(type)) {
                handlePresence(data);
                return;
            }
            List<BiConsumer<String, String>> list = handlers.get(type);
            if (list == null) {
                return;
            }
            for (BiConsumer<String, String> handler : list) {
                try {
                    handler.accept(source, data);
                } catch (Throwable t) {
                    plugin.getLogger().warning("处理跨服消息 " + type + " 失败: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            plugin.debug("忽略无法解析的跨服消息: " + t.getMessage());
        }
    }

    private boolean isDuplicate(String id) {
        synchronized (seen) {
            return seen.put(id, Boolean.TRUE) != null;
        }
    }

    /**
     * 处理在线查询应答。代理端会把较大的结果拆成多页（单条插件消息上限 32767 字节），
     * 这里按 token 累计，收齐后一次回调；解析失败时也必须回调空快照，否则调用方会永远停在「查询中」。
     */
    private void handlePresence(String data) {
        String token;
        JsonObject object;
        int page;
        int pages;
        try {
            object = JsonParser.parseString(data).getAsJsonObject();
            token = object.get("token").getAsString();
            page = object.has("page") ? object.get("page").getAsInt() : 1;
            pages = object.has("pages") ? object.get("pages").getAsInt() : 1;
        } catch (Throwable t) {
            return;
        }
        if (pages < 1 || pages > 64 || page < 1 || page > pages) {
            return;
        }
        PendingPresence pending = pendingPresence.get(token);
        if (pending == null) {
            return;
        }
        Presence completed = null;
        synchronized (pending) {
            if (page == 1) {
                pending.acc.clear();
                pending.nextPage = 1;
            }
            if (page != pending.nextPage) {
                // 乱序/重复页：等待正确页；超时任务会兜底回调空快照
                return;
            }
            try {
                if (object.has("servers")) {
                    for (Map.Entry<String, com.google.gson.JsonElement> entry
                            : object.getAsJsonObject("servers").entrySet()) {
                        List<String> names = new ArrayList<>();
                        for (com.google.gson.JsonElement element : entry.getValue().getAsJsonArray()) {
                            names.add(element.getAsString());
                        }
                        pending.acc.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).addAll(names);
                    }
                }
            } catch (Throwable t) {
                pendingPresence.remove(token, pending);
                pending.callback.accept(new Presence(Map.of()));
                return;
            }
            pending.nextPage++;
            if (pending.nextPage <= pages) {
                return;
            }
            completed = new Presence(new LinkedHashMap<>(pending.acc));
        }
        pendingPresence.remove(token, pending);
        pending.callback.accept(completed);
    }

    /** 分页应答的累计状态；同一次查询可能分多页到达。 */
    private static final class PendingPresence {
        final Consumer<Presence> callback;
        final Map<String, List<String>> acc = new LinkedHashMap<>();
        int nextPage = 1;

        PendingPresence(Consumer<Presence> callback) {
            this.callback = callback;
        }
    }

    /**
     * 校验代理端签名并返回去掉 {@code sig} 字段的原始 JSON 文本；失败返回 null。
     * <p>
     * 签名覆盖「除 sig 外的完整信封文本」，因此不能靠重新序列化校验，必须做精确的字符串截取：
     * 代理端始终把 sig 作为最后一个字段写入，形如 {@code {...,"sig":"<hex>"}}。
     */
    private String verifySignature(String json) {
        String secret = sharedSecret;
        if (secret.isEmpty()) {
            return null;
        }
        int index = json.lastIndexOf(",\"sig\":\"");
        if (index < 0 || !json.endsWith("\"}")) {
            return null;
        }
        String signature = json.substring(index + 8, json.length() - 2);
        String base = json.substring(0, index) + "}";
        try {
            byte[] expected = hmacSha256(secret, base);
            byte[] provided = HexFormat.of().parseHex(signature);
            if (MessageDigest.isEqual(expected, provided)) {
                return base;
            }
        } catch (Throwable ignored) {
            // 非法十六进制/算法不可用：按校验失败处理
        }
        return null;
    }

    private static byte[] hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    private String buildEnvelope(String type, Object payload) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.addProperty("src", serverId());
        object.addProperty("id", UUID.randomUUID().toString());
        object.addProperty("ts", System.currentTimeMillis());
        object.add("data", gson.toJsonTree(payload));
        return object.toString();
    }

    /**
     * 发送一条插件消息。插件消息必须借由玩家连接发出，
     * 因此 {@code queueIfOffline} 为 true 时无人在线则暂存待补发，否则直接丢弃。
     * <p>
     * {@code sendPluginMessage} 会触碰玩家连接状态，在 Folia 下必须落在该玩家所属区域线程；
     * 本方法的调用方可能是全局线程或异步线程（如跨服状态回填），因此统一经由
     * {@link com.junxiex.mikuchat.core.scheduler.SchedulerUtil#atEntity} 投递。
     */
    private void send(String json, boolean queueIfOffline) {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        Player carrier = carrier();
        if (carrier == null) {
            if (queueIfOffline) {
                enqueue(data);
            }
            return;
        }
        plugin.scheduler().atEntity(carrier, () -> {
            try {
                if (!carrier.isOnline()) {
                    // 承载玩家在投递前离线：按队列语义处理，避免消息静默丢失
                    if (queueIfOffline) {
                        enqueue(data);
                    }
                    carrierId = null;
                    return;
                }
                carrier.sendPluginMessage(plugin, channel, data);
            } catch (Throwable t) {
                plugin.debug("发送跨服消息失败: " + t.getMessage());
                carrierId = null;
                // 需要补发的消息（禁言变更等）失败后重新入队，等下一名玩家接入时重试
                if (queueIfOffline) {
                    enqueue(data);
                }
            }
        });
    }

    private void enqueue(byte[] data) {
        synchronized (outbound) {
            while (outbound.size() >= queueCapacity) {
                outbound.pollFirst();
            }
            outbound.addLast(data);
        }
        plugin.debug("本服当前无玩家在线，跨服消息已暂存，待玩家接入后补发。");
    }

    private Player carrier() {
        // 缓存承载连接，避免每条跨服消息都 O(N) 遍历在线玩家；承载玩家离线后自动重选
        UUID cached = carrierId;
        if (cached != null) {
            Player player = Bukkit.getPlayer(cached);
            if (player != null && player.isOnline()) {
                return player;
            }
        }
        Player found = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            found = player;
            break;
        }
        carrierId = found == null ? null : found.getUniqueId();
        return found;
    }
}
