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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 跨服消息桥接：解析后端发来的信封，按类型分流处理。
 *
 * <p>控制类消息（在线查询、共享状态读写）由代理端直接消化；
 * 其余消息（跨服聊天、私聊、禁言变更通知等）转发给其余后端。</p>
 *
 * <p>安全边界：后端无法区分「客户端直发的插件消息」与「代理转发的插件消息」，
 * 因此代理端写给后端的每一条消息都用共享密钥做 HMAC-SHA256 签名（末尾 {@code sig} 字段），
 * 后端校验失败即丢弃。签名覆盖「除 sig 外的完整信封文本」，两端必须按同一规则截取。</p>
 */
public final class ProxyBridge {

    static final String TYPE_WHO_REQ = "WHO_REQ";
    static final String TYPE_WHO_RES = "WHO_RES";
    static final String TYPE_STATE_REQ = "STATE_REQ";
    static final String TYPE_STATE_SYNC = "STATE_SYNC";
    static final String TYPE_KV_SET = "KV_SET";
    static final String TYPE_KV_DEL = "KV_DEL";
    /** 跨服私聊类型：带 targetServer 时按目标服务器定向转发，避免向所有后端扇出。 */
    static final String TYPE_PRIVATE = "PRIVATE";

    /** 代理端发出的信封来源标识，不会与任何后端 server-id 相同。 */
    private static final String PROXY_SOURCE = "__proxy__";

    /** 禁言共享键，需与后端 {@code MuteManager} 的 MUTE_HASH_KEY 保持一致。 */
    private static final String MUTE_KEY = "mikuchat:mutes";

    /**
     * 单条插件消息的字节预算。协议硬上限为 32767 字节，这里留足余量给信封与 JSON 转义，
     * 超过则拆成多页下发（后端按 page/pages 聚合）。
     */
    private static final int MAX_MESSAGE_BYTES = 8192;

    /** 协议版本：>=2 的后端支持分页与签名。 */
    private static final int PROTO_VERSION = 2;

    /** 单条 relay 转发的硬上限（贴近 serverbound custom payload 的 32767 字节上限）。 */
    private static final int MAX_RELAY_BYTES = 30000;

    private final ProxyServer proxy;
    private final Logger logger;
    private final MinecraftChannelIdentifier channel;
    private final SharedStore store;
    private final boolean debug;
    private final String sharedSecret;
    private final Gson gson = new Gson();
    /** 过大消息丢弃计数：限频告警，避免异常/被攻陷的后端刷爆代理日志。 */
    private final java.util.concurrent.atomic.AtomicLong oversizedDrops =
            new java.util.concurrent.atomic.AtomicLong();

    public ProxyBridge(ProxyServer proxy, Logger logger, MinecraftChannelIdentifier channel, SharedStore store,
                       boolean debug, String sharedSecret) {
        this.proxy = proxy;
        this.logger = logger;
        this.channel = channel;
        this.store = store;
        this.debug = debug;
        this.sharedSecret = sharedSecret;
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
            // 旧版后端不带 proto 字段：按旧格式单条下发，避免滚动升级期间把旧后端的缓存覆盖成最后一页
            boolean paged = envelope.has("proto") && envelope.get("proto").getAsInt() >= PROTO_VERSION;
            switch (type) {
                case TYPE_WHO_REQ -> replyPresence(origin, data, paged);
                case TYPE_STATE_REQ -> replyState(origin, paged);
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
    private void replyPresence(RegisteredServer origin, JsonObject data, boolean paged) {
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
        if (!paged) {
            JsonObject payload = new JsonObject();
            payload.addProperty("token", text(data, "token"));
            payload.add("servers", gson.toJsonTree(servers));
            sendTo(origin, TYPE_WHO_RES, payload);
            return;
        }
        List<Map<String, List<String>>> pages = paginatePresence(servers);
        int total = pages.size();
        for (int i = 0; i < total; i++) {
            JsonObject payload = new JsonObject();
            payload.addProperty("token", text(data, "token"));
            payload.addProperty("page", i + 1);
            payload.addProperty("pages", total);
            payload.add("servers", gson.toJsonTree(pages.get(i)));
            sendTo(origin, TYPE_WHO_RES, payload);
        }
    }

    /**
     * 全量共享状态：逐键下发，后端据此覆盖本地缓存（空表表示该键已无数据）。
     * <p>
     * 必须始终至少下发禁言键：后端收到 STATE_SYNC 才会覆盖本地缓存并把结果回写本地文件。
     * 若仓库整体为空时一条都不发，后端会继续持有本地旧禁言，还会把它回填给代理，
     * 形成「旧状态自我强化」。这里对已知键即使为空也补发一次空表。
     * <p>
     * 禁言表可能很大（数千条），整表塞进一条插件消息会超过 32767 字节硬上限并导致同步失败，
     * 因此按字节预算拆页，后端聚合后再覆盖。
     */
    private void replyState(RegisteredServer origin, boolean paged) {
        Map<String, Map<String, String>> snapshot = store.snapshot();
        if (snapshot.isEmpty()) {
            snapshot = Map.of(MUTE_KEY, Map.of());
        }
        for (Map.Entry<String, Map<String, String>> entry : snapshot.entrySet()) {
            if (!paged) {
                JsonObject payload = new JsonObject();
                payload.addProperty("key", entry.getKey());
                payload.add("map", gson.toJsonTree(entry.getValue()));
                sendTo(origin, TYPE_STATE_SYNC, payload);
                continue;
            }
            List<Map<String, String>> pages = paginate(entry.getValue());
            int total = pages.size();
            for (int i = 0; i < total; i++) {
                JsonObject payload = new JsonObject();
                payload.addProperty("key", entry.getKey());
                payload.addProperty("page", i + 1);
                payload.addProperty("pages", total);
                payload.add("map", gson.toJsonTree(pages.get(i)));
                sendTo(origin, TYPE_STATE_SYNC, payload);
            }
        }
    }

    /**
     * 转发给其余后端；来源方已在本地展示过该消息，不能回发造成重复。
     * <p>
     * 转发前把信封里的 {@code src} 改写为连接实际所属的服务器名，并重新计算签名：
     * 后端只信任「代理签发」的消息，{@code src} 因此具备可信度。跨服私聊带 targetServer 时
     * 只发给目标服务器；目标名不存在（例如 server-id 与 velocity.toml 不一致）则退回广播，
     * 由接收端按 targetServer 自行过滤，行为与旧版一致。
     */
    private void relay(RegisteredServer origin, byte[] data) {
        String originName = origin.getServerInfo().getName();
        JsonObject envelope;
        try {
            envelope = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Throwable t) {
            // 无法解析的信封不扩散到所有后端，避免畸形数据放大
            logger.warn("丢弃无法解析的跨服转发消息：{}", t.getMessage());
            return;
        }
        envelope.addProperty("src", originName);
        String type = envelope.has("type") ? envelope.get("type").getAsString() : "";
        JsonObject payload = envelope.has("data") && envelope.get("data").isJsonObject()
                ? envelope.getAsJsonObject("data") : new JsonObject();
        String targetServer = TYPE_PRIVATE.equals(type) ? text(payload, "targetServer") : "";
        byte[] sealed = seal(envelope);
        if (sealed.length > MAX_RELAY_BYTES) {
            // 代理→后端是 serverbound custom payload，硬上限 32767：超限会让接收端解码失败并断开连接
            reportOversized(sealed.length, type);
            return;
        }
        if (!targetServer.isEmpty()) {
            Optional<RegisteredServer> target = proxy.getServer(targetServer);
            if (target.isPresent()) {
                RegisteredServer server = target.get();
                if (!server.getServerInfo().getName().equals(originName)
                        && !server.sendPluginMessage(channel, sealed) && debug) {
                    logger.info("服务器 {} 无可用连接，跨服私聊未送达。", targetServer);
                }
                return;
            }
        }
        for (RegisteredServer server : proxy.getAllServers()) {
            if (server.getServerInfo().getName().equals(originName)) {
                continue;
            }
            if (!server.sendPluginMessage(channel, sealed) && debug) {
                logger.info("服务器 {} 无可用连接，跨服消息未送达。", server.getServerInfo().getName());
            }
        }
    }

    /** 按序列化后的字节数把 key-value 表拆页；空表也至少返回一页，保证「无数据」也会下发。 */
    private <V> List<Map<String, V>> paginate(Map<String, V> entries) {
        List<Map<String, V>> pages = new ArrayList<>();
        Map<String, V> current = new LinkedHashMap<>();
        int currentBytes = 0;
        for (Map.Entry<String, V> entry : entries.entrySet()) {
            // 不能序列化 Map.Entry 本身：Gson 会反射 java.util.LinkedHashMap$Entry 并失败。
            // 只对 key / value 分别计算字节数即可。
            int size = gson.toJson(entry.getKey()).getBytes(StandardCharsets.UTF_8).length
                    + gson.toJson(entry.getValue()).getBytes(StandardCharsets.UTF_8).length + 1;
            if (!current.isEmpty() && currentBytes + size > MAX_MESSAGE_BYTES) {
                pages.add(current);
                current = new LinkedHashMap<>();
                currentBytes = 0;
            }
            if (size > MAX_MESSAGE_BYTES) {
                // 单条就超页预算：无法再拆，交由统一出口的长度校验丢弃并告警
                reportOversized(size, "STATE_ENTRY");
            }
            current.put(entry.getKey(), entry.getValue());
            currentBytes += size;
        }
        if (!current.isEmpty() || pages.isEmpty()) {
            pages.add(current);
        }
        return pages;
    }

    /** 按玩家名序列化后的字节数拆页；同一服务器的玩家可能分布在相邻多页，后端会合并。 */
    private List<Map<String, List<String>>> paginatePresence(Map<String, List<String>> servers) {
        List<Map<String, List<String>>> pages = new ArrayList<>();
        Map<String, List<String>> current = new LinkedHashMap<>();
        int currentBytes = 0;
        for (Map.Entry<String, List<String>> entry : servers.entrySet()) {
            for (String name : entry.getValue()) {
                int size = gson.toJson(name).getBytes(StandardCharsets.UTF_8).length + 2;
                if (!current.isEmpty() && currentBytes + size > MAX_MESSAGE_BYTES) {
                    pages.add(current);
                    current = new LinkedHashMap<>();
                    currentBytes = 0;
                }
                current.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(name);
                currentBytes += size;
            }
        }
        if (!current.isEmpty() || pages.isEmpty()) {
            pages.add(current);
        }
        return pages;
    }

    /** 计算签名并把 {@code sig} 追加为信封最后一个字段（后端按此约定截取原文校验）。 */
    private byte[] seal(JsonObject envelope) {
        envelope.remove("sig");
        String base = gson.toJson(envelope);
        String signature = hmacHex(base);
        String sealed = base.substring(0, base.length() - 1) + ",\"sig\":\"" + signature + "\"}";
        return sealed.getBytes(StandardCharsets.UTF_8);
    }

    private String hmacHex(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算跨服消息签名失败", e);
        }
    }

    private void sendTo(RegisteredServer server, String type, JsonElement payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", type);
        envelope.addProperty("src", PROXY_SOURCE);
        envelope.addProperty("id", UUID.randomUUID().toString());
        envelope.addProperty("ts", System.currentTimeMillis());
        envelope.addProperty("proto", PROTO_VERSION);
        envelope.add("data", payload);
        // 统一出口做长度硬校验：旧后端（legacy 单条下发）与单个超大 entry 也在此被拦下，
        // 避免发出必然超过 32767 字节、导致接收端解码失败断连的数据包。
        byte[] sealed = seal(envelope);
        if (sealed.length > MAX_RELAY_BYTES) {
            reportOversized(sealed.length, type);
            return;
        }
        if (!server.sendPluginMessage(channel, sealed) && debug) {
            logger.info("服务器 {} 无可用连接，{} 未送达。", server.getServerInfo().getName(), type);
        }
    }

    /** 过大消息的限频告警（首次 + 每 100 条），避免持续异常时刷爆日志。 */
    private void reportOversized(int bytes, String type) {
        long total = oversizedDrops.incrementAndGet();
        if (total == 1L || total % 100L == 0L) {
            logger.warn("跨服消息过大（{} 字节，类型 {}），已丢弃以避免接收端断连（累计 {} 条）。",
                    bytes, type, total);
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }
}
