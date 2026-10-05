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
            String source = envelope.get("src").getAsString();
            JsonObject data = envelope.has("data") && envelope.get("data").isJsonObject()
                    ? envelope.getAsJsonObject("data") : new JsonObject();
            RegisteredServer origin = connection.getServer();
            switch (type) {
                case TYPE_WHO_REQ -> replyPresence(origin, source, data);
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
     */
    private void replyPresence(RegisteredServer origin, String source, JsonObject data) {
        Map<String, List<String>> servers = new LinkedHashMap<>();
        for (Player player : proxy.getAllPlayers()) {
            String serverName = player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse(null);
            if (serverName == null || serverName.equals(source)) {
                continue;
            }
            servers.computeIfAbsent(serverName, key -> new ArrayList<>()).add(player.getUsername());
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("token", text(data, "token"));
        payload.add("servers", gson.toJsonTree(servers));
        sendTo(origin, TYPE_WHO_RES, payload);
    }

    /** 全量共享状态：逐键下发，后端据此覆盖本地缓存（空表表示该键已无数据）。 */
    private void replyState(RegisteredServer origin) {
        for (Map.Entry<String, Map<String, String>> entry : store.snapshot().entrySet()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("key", entry.getKey());
            payload.add("map", gson.toJsonTree(entry.getValue()));
            sendTo(origin, TYPE_STATE_SYNC, payload);
        }
    }

    /** 转发给除来源外的所有后端；来源方已在本地展示过该消息，不能回发造成重复。 */
    private void relay(RegisteredServer origin, byte[] data) {
        String originName = origin.getServerInfo().getName();
        for (RegisteredServer server : proxy.getAllServers()) {
            if (server.getServerInfo().getName().equals(originName)) {
                continue;
            }
            if (!server.sendPluginMessage(channel, data) && debug) {
                logger.info("服务器 {} 无可用连接，跨服消息未送达。", server.getServerInfo().getName());
            }
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
