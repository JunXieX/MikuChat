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
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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

    private final MikuChat plugin;
    private final ConfigFile config;
    private final Gson gson = new Gson();
    private final Map<String, List<BiConsumer<String, String>>> handlers = new ConcurrentHashMap<>();
    private final Map<String, Consumer<Presence>> pendingPresence = new ConcurrentHashMap<>();
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
    private volatile boolean available;
    private volatile UUID carrierId;
    private int presenceTimeout = 1500;
    private int queueCapacity = 1024;

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
        this.presenceTimeout = config.config().getInt("presence-timeout-millis", 1500);
        this.queueCapacity = config.config().getInt("outbound-queue-capacity", 1024);
        this.trustedServers.clear();
        this.trustedServers.addAll(config.config().getStringList("trusted-servers"));

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
        try {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, channel, this);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, channel);
        } catch (Throwable ignored) {
            // 忽略
        }
        org.bukkit.event.HandlerList.unregisterAll(this);
        carrierId = null;
        pendingPresence.clear();
        synchronized (outbound) {
            outbound.clear();
        }
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
        pendingPresence.put(token, callback);
        send(buildEnvelope(TYPE_WHO_REQ, Map.of("token", token)), false);
        plugin.scheduler().asyncLater(() -> {
            Consumer<Presence> pending = pendingPresence.remove(token);
            if (pending != null) {
                plugin.debug("在线查询未在 " + presenceTimeout + "ms 内得到代理应答。");
                pending.accept(new Presence(Map.of()));
            }
        }, Math.max(1, presenceTimeout / 50));
    }

    @Override
    public void requestState() {
        if (!available) {
            return;
        }
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
            for (byte[] data : queued) {
                try {
                    player.sendPluginMessage(plugin, channel, data);
                } catch (Throwable t) {
                    plugin.debug("补发跨服消息失败: " + t.getMessage());
                    break;
                }
            }
            if (!queued.isEmpty()) {
                plugin.debug("已补发 " + queued.size() + " 条暂存的跨服消息。");
            }
            send(buildEnvelope(TYPE_STATE_REQ, Map.of()), false);
        }, 1L);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String incoming, @NotNull Player player, byte @NotNull [] message) {
        if (!available || !channel.equals(incoming)) {
            return;
        }
        dispatch(new String(message, StandardCharsets.UTF_8));
    }

    private void dispatch(String json) {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            String type = object.get("type").getAsString();
            String source = object.get("src").getAsString();
            // 代理已排除发送方，这里是双保险，避免自己处理自己发出的消息
            if (source.equals(serverId())) {
                return;
            }
            // 配置了白名单时，只信任名单内的来源服务器，拒绝伪造/未知来源的插件消息
            if (!trustedServers.isEmpty() && !trustedServers.contains(source)) {
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

    private void handlePresence(String data) {
        String token;
        JsonObject object;
        try {
            object = JsonParser.parseString(data).getAsJsonObject();
            token = object.get("token").getAsString();
        } catch (Throwable t) {
            return;
        }
        Consumer<Presence> callback = pendingPresence.remove(token);
        if (callback == null || !object.has("servers")) {
            return;
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : object.getAsJsonObject("servers").entrySet()) {
            List<String> names = new ArrayList<>();
            for (com.google.gson.JsonElement element : entry.getValue().getAsJsonArray()) {
                names.add(element.getAsString());
            }
            result.put(entry.getKey(), names);
        }
        callback.accept(new Presence(result));
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
        try {
            carrier.sendPluginMessage(plugin, channel, data);
        } catch (Throwable t) {
            plugin.debug("发送跨服消息失败: " + t.getMessage());
        }
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
