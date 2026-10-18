package com.junxiex.mikuchat.velocity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 跨服消息桥接：解析后端发来的信封，按类型分流处理。
 *
 * <p>控制类消息（在线查询、共享状态读写）由代理端直接消化；
 * 其余消息（跨服聊天、私聊、禁言变更通知等）原样转发给除来源外的所有后端。</p>
 */
public final class ProxyBridge {

    static final String TYPE_WHO_REQ = "WHO_REQ";
    static final String TYPE_WHO_RES = "WHO_RES";
    static final String TYPE_STATE_REQ = "STATE_REQ";
    static final String TYPE_STATE_SYNC = "STATE_SYNC";
    static final String TYPE_KV_SET = "KV_SET";
    static final String TYPE_KV_DEL = "KV_DEL";

    /** 代理端发出的信封来源标识，不会与任何后端 server-id 相同。 */
    private static final String PROXY_SOURCE = "__proxy__";

    /** 禁言共享键，需与后端 {@code MuteManager} 的 MUTE_HASH_KEY 保持一致。 */
    private static final String MUTE_KEY = "mikuchat:mutes";

    private final ProxyServer proxy;
    private final Logger logger;
    private final MinecraftChannelIdentifier channel;
    private final SharedStore store;
    private final boolean debug;
    private final Gson gson = new Gson();

    public ProxyBridge(ProxyServer proxy, Logger logger, MinecraftChannelIdentifier channel, SharedStore store,
                       boolean debug) {
        this.proxy = proxy;
        this.logger = logger;
        this.channel = channel;
        this.store = store;
        this.debug = debug;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!channel.equals(event.getIdentifier())) {
            return;
        }
        // 标记已处理，避免 Velocity 继续把该消息转发给客户端
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) {
            return;
        }
        try {
            JsonObject envelope = JsonParser.parseString(new String(event.getData(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String type = envelope.get("type").getAsString();
            // src 仅用于后端侧的来源校验；代理端这里一律以真实连接所属服务器为准
            JsonObject data = envelope.has("data") && envelope.get("data").isJsonObject()
                    ? envelope.getAsJsonObject("data") : new JsonObject();
            RegisteredServer origin = connection.getServer();
            switch (type) {
                case TYPE_WHO_REQ -> replyPresence(origin, data);
                case TYPE_STATE_REQ -> replyState(origin);
                case TYPE_KV_SET -> store.put(text(data, "key"), text(data, "field"), text(data, "value"));
                case TYPE_KV_DEL -> store.remove(text(data, "key"), text(data, "field"));
                default -> relay(origin, event.getData());
            }
        } catch (Throwable t) {
            logger.warn("处理跨服消息失败：{}", t.getMessage());
        }
    }

    /**
     * 在线查询：代理端掌握全量玩家所在服务器，直接查表应答，零额外往返。
     * 结果不含发起方自身，与后端 {@code Presence} 的语义一致。
     * <p>
     * 排除本服时用的是消息实际来源连接的服务器名，而不是信封里的 {@code src}：
     * {@code src} 由后端配置的 {@code server-id} 填入，它未必与 {@code velocity.toml}
     * 注册的服务器名完全一致。若用 {@code src} 过滤，配置不同名时本服玩家会被当成
     * 「其它服务器」列出来，造成自己看得到自己的重复条目。
     */
    private void replyPresence(RegisteredServer origin, JsonObject data) {
        String originName = origin.getServerInfo().getName();
        Map<String, List<String>> servers = new LinkedHashMap<>();
        for (Player player : proxy.getAllPlayers()) {
            String serverName = player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse(null);
            if (serverName == null || serverName.equals(originName)) {
                continue;
            }
            servers.computeIfAbsent(serverName, key -> new ArrayList<>()).add(player.getUsername());
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("token", text(data, "token"));
        payload.add("servers", gson.toJsonTree(servers));
        sendTo(origin, TYPE_WHO_RES, payload);
    }

    /**
     * 全量共享状态：逐键下发，后端据此覆盖本地缓存（空表表示该键已无数据）。
     * <p>
     * 必须始终至少下发禁言键：后端收到 STATE_SYNC 才会覆盖本地缓存并把结果回写本地文件。
     * 若仓库整体为空时一条都不发，后端会继续持有本地旧禁言，还会把它回填给代理，
     * 形成「旧状态自我强化」。这里对已知键即使为空也补发一次空表。
     */
    private void replyState(RegisteredServer origin) {
        Map<String, Map<String, String>> snapshot = store.snapshot();
        if (snapshot.isEmpty()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("key", MUTE_KEY);
            payload.add("map", new JsonObject());
            sendTo(origin, TYPE_STATE_SYNC, payload);
            return;
        }
        for (Map.Entry<String, Map<String, String>> entry : snapshot.entrySet()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("key", entry.getKey());
            payload.add("map", gson.toJsonTree(entry.getValue()));
            sendTo(origin, TYPE_STATE_SYNC, payload);
        }
    }

    /**
     * 转发给除来源外的所有后端；来源方已在本地展示过该消息，不能回发造成重复。
     * <p>
     * 转发前把信封里的 {@code src} 改写为连接实际所属的服务器名：后端侧的
     * {@code trusted-servers} 白名单只能依赖这个字段，若原样透传发送方自报的值，
     * 任何后端都能伪造成白名单内的名字，白名单形同虚设。代理端是唯一可信的转发出入口，
     * 由它盖戳才能让该字段具备可信度。
     */
    private void relay(RegisteredServer origin, byte[] data) {
        String originName = origin.getServerInfo().getName();
        byte[] stamped = stampSource(data, originName);
        for (RegisteredServer server : proxy.getAllServers()) {
            if (server.getServerInfo().getName().equals(originName)) {
                continue;
            }
            if (!server.sendPluginMessage(channel, stamped) && debug) {
                logger.info("服务器 {} 无可用连接，跨服消息未送达。", server.getServerInfo().getName());
            }
        }
    }

    /** 用代理端观测到的真实来源覆盖信封的 {@code src}；解析失败时退回原数据。 */
    private byte[] stampSource(byte[] data, String actualSource) {
        try {
            JsonObject envelope = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            envelope.addProperty("src", actualSource);
            return gson.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return data;
        }
    }

    private void sendTo(RegisteredServer server, String type, JsonElement payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", type);
        envelope.addProperty("src", PROXY_SOURCE);
        envelope.addProperty("id", UUID.randomUUID().toString());
        envelope.addProperty("ts", System.currentTimeMillis());
        envelope.add("data", payload);
        if (!server.sendPluginMessage(channel, gson.toJson(envelope).getBytes(StandardCharsets.UTF_8)) && debug) {
            logger.info("服务器 {} 无可用连接，{} 未送达。", server.getServerInfo().getName(), type);
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }
}
