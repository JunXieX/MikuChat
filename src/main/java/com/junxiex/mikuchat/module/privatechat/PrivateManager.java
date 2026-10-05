package com.junxiex.mikuchat.module.privatechat;

import com.google.gson.Gson;
import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.service.FilterService;
import com.junxiex.mikuchat.core.service.MentionNotifier;
import com.junxiex.mikuchat.core.service.MuteService;
import com.junxiex.mikuchat.core.service.NetworkService;
import com.junxiex.mikuchat.core.text.TextUtil;
import com.junxiex.mikuchat.module.chat.MessageComposer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 私聊管理器：接管 /msg /reply，支持文件留档、管理员转发（spy）与跨服投递（代理端转发 + ACK）。
 */
public final class PrivateManager {

    private static final String TYPE_PRIVATE = "PRIVATE";
    private static final String TYPE_ACK = "PRIVATE_ACK";
    /** 跨服私聊组件 JSON 的最大长度，超出即视为可疑并丢弃。 */
    private static final int MAX_REMOTE_LINE_LENGTH = 16384;

    private final MikuChat plugin;
    private final ConfigFile config;
    private final Gson gson = new Gson();
    private final com.junxiex.mikuchat.core.user.UserDataStore userData;

    private final Map<UUID, String> replyTargets = new ConcurrentHashMap<>();
    private final Map<String, PendingDelivery> pending = new ConcurrentHashMap<>();

    private final BiConsumer<String, String> remoteHandler = (source, json) -> onRemotePrivate(json);
    private final BiConsumer<String, String> ackHandler = (source, json) -> onAck(json);

    private volatile DateTimeFormatter logTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private volatile DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public PrivateManager(MikuChat plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().get("private.yml");
        this.userData = plugin.services().require(com.junxiex.mikuchat.core.user.UserDataStore.class);
    }

    public void load() {
        plugin.configManager().reload("private.yml");
        String pattern = config.config().getString("log.time-format", "yyyy-MM-dd HH:mm:ss");
        try {
            this.logTimeFormat = DateTimeFormatter.ofPattern(pattern);
        } catch (Exception e) {
            this.logTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        }
        String datePattern = config.config().getString("log.file-date-format", "yyyy-MM-dd");
        try {
            this.dateFormat = DateTimeFormatter.ofPattern(datePattern);
        } catch (Exception e) {
            this.dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        }
        int retentionDays = config.config().getInt("log.retention-days", 30);
        DateTimeFormatter purgeFormat = this.dateFormat;
        plugin.scheduler().async(() -> com.junxiex.mikuchat.core.util.LogRetention.purge(
                plugin.getDataFolder().toPath().resolve("data/private-logs"),
                retentionDays, purgeFormat, plugin.getLogger(), "私聊记录"));
    }

    public void subscribe() {
        plugin.services().find(NetworkService.class).ifPresent(network -> {
            network.subscribe(TYPE_PRIVATE, remoteHandler);
            network.subscribe(TYPE_ACK, ackHandler);
        });
    }

    public void unsubscribe() {
        plugin.services().find(NetworkService.class).ifPresent(network -> {
            network.unsubscribe(TYPE_PRIVATE, remoteHandler);
            network.unsubscribe(TYPE_ACK, ackHandler);
        });
    }

    /** 处理 /msg 与 /reply 的发送逻辑。 */
    public void send(Player sender, String targetName, String message) {
        if (targetName.equalsIgnoreCase(sender.getName())) {
            sender.sendMessage(plugin.lang().get("private.self"));
            return;
        }
        MuteService mute = plugin.services().find(MuteService.class).orElse(null);
        if (mute != null && mute.isMuted(sender.getUniqueId())) {
            sender.sendMessage(plugin.lang().get("mute.blocked"));
            return;
        }

        // 私聊同样受防刷屏约束，避免用 /msg 绕过公开聊天的冷却与频率限制
        com.junxiex.mikuchat.core.service.AntiSpamService antiSpam =
                plugin.services().find(com.junxiex.mikuchat.core.service.AntiSpamService.class).orElse(null);
        if (antiSpam != null) {
            var spam = antiSpam.check(sender, null, message);
            if (spam.blocked()) {
                if (!spam.silent()) {
                    sender.sendMessage(antiSpam.feedback(plugin.lang(), spam));
                }
                return;
            }
        }

        FilterService filter = plugin.services().find(FilterService.class).orElse(null);
        String content = message;
        if (filter != null) {
            var result = filter.apply(sender, null, message);
            if (result.blocked()) {
                sender.sendMessage(plugin.lang().get(result.advertisement()
                        ? "filter.ad-blocked" : "filter.blocked"));
                return;
            }
            content = result.text();
        }

        int maxLength = config.config().getInt("message-max-length", 256);
        content = TextUtil.truncate(content, maxLength);
        final String finalContent = content;

        MessageComposer composer = plugin.services().require(MessageComposer.class);
        MessageComposer.Composed composed = composer.compose(sender, null, content);
        Component body = composed.body();

        Component senderLine = renderLine(config.config().getString("formats.sender",
                "<gray>你 <dark_gray>→ <white>{target}</white><gray>：<message>"),
                sender.getName(), targetName, body);
        Component receiverLine = renderLine(config.config().getString("formats.receiver",
                "<gray>{sender} <dark_gray>→ <white>你</white><gray>：<message>"),
                sender.getName(), targetName, body);
        Component spyLine = renderLine(config.config().getString("formats.spy",
                "<dark_gray>[私聊] <gray>{sender} <dark_gray>→ <white>{target}</white><gray>：<message>"),
                sender.getName(), targetName, body);

        Player localTarget = findLocal(targetName);
        if (localTarget != null) {
            replyTargets.put(sender.getUniqueId(), localTarget.getName());
            plugin.scheduler().atEntity(sender, () -> sender.sendMessage(senderLine));
            plugin.scheduler().atEntity(localTarget, () -> localTarget.sendMessage(receiverLine));
            dispatchSpy(spyLine, sender.getUniqueId());
            log(sender.getName(), localTarget.getName(), content, false);
            notifyMentions(sender.getUniqueId(), composed.mentions());
            return;
        }

        NetworkService network = plugin.services().find(NetworkService.class).orElse(null);
        if (network == null || !network.available()) {
            sender.sendMessage(plugin.lang().get("private.target-offline", "target", targetName));
            return;
        }

        // 目标不在本服：先探测其所在服务器，再定向投递。
        // 否则同名玩家在多台服务器同时在线时会被重复投递（在线/离线模式下 UUID 相同，无法靠 UUID 区分）。
        network.queryPresence(presence -> {
            List<String> owners = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : presence.playersByServer().entrySet()) {
                for (String name : entry.getValue()) {
                    if (name.equalsIgnoreCase(targetName)) {
                        owners.add(entry.getKey());
                        break;
                    }
                }
            }
            if (owners.isEmpty()) {
                notifySender(sender, plugin.lang().get("private.target-offline", "target", targetName));
                return;
            }
            if (owners.size() > 1) {
                notifySender(sender, plugin.lang().get("private.ambiguous", "target", targetName));
                return;
            }
            String id = UUID.randomUUID().toString();
            long timeout = config.config().getLong("delivery-timeout-millis", 1500L);
            pending.put(id, new PendingDelivery(sender, targetName, senderLine, finalContent));
            network.publish(TYPE_PRIVATE, new PrivatePacket(id, sender.getUniqueId(), sender.getName(),
                    targetName, owners.get(0), TextUtil.toJson(receiverLine), finalContent,
                    System.currentTimeMillis()));
            plugin.scheduler().asyncLater(() -> {
                PendingDelivery delivery = pending.remove(id);
                if (delivery != null && delivery.sender().isOnline()) {
                    plugin.scheduler().atEntity(delivery.sender(), () ->
                            delivery.sender().sendMessage(plugin.lang().get("private.target-offline",
                                    "target", targetName)));
                }
            }, Math.max(1, timeout / 50));
        });
    }

    private void notifySender(Player sender, Component message) {
        plugin.scheduler().atEntity(sender, () -> sender.sendMessage(message));
    }

    private void onRemotePrivate(String json) {
        // 由插件消息回调触发，调度到全局线程后再触碰玩家列表
        plugin.scheduler().global(() -> handleRemotePrivate(json));
    }

    private void handleRemotePrivate(String json) {
        PrivatePacket packet;
        try {
            packet = gson.fromJson(json, PrivatePacket.class);
        } catch (Throwable t) {
            return;
        }
        if (packet == null || packet.targetName() == null) {
            return;
        }
        // 定向投递：若发送端已指明目标所在服务器，其它服务器不得重复投递
        if (packet.targetServer() != null) {
            NetworkService network = plugin.services().find(NetworkService.class).orElse(null);
            if (network == null || !packet.targetServer().equals(network.serverId())) {
                return;
            }
        }
        Player target = findLocal(packet.targetName());
        if (target == null) {
            return;
        }
        Component receiverLine;
        try {
            receiverLine = TextUtil.fromJson(packet.receiverLine());
        } catch (Throwable t) {
            plugin.debug("解析跨服私聊失败: " + t.getMessage());
            return;
        }
        // 跨服内容来自信任边界之外：限制体积并拒绝可交互组件（click/hover），
        // 防止被攻陷的对端借私聊向本服玩家注入点击执行指令等恶意组件
        if (packet.receiverLine().length() > MAX_REMOTE_LINE_LENGTH || containsInteractive(receiverLine)) {
            plugin.debug("丢弃可疑的跨服私聊组件: " + packet.senderName());
            return;
        }
        plugin.scheduler().atEntity(target, () -> target.sendMessage(receiverLine));
        dispatchSpy(receiverLine, packet.senderId());
        notifyMentions(packet.senderId(), null);
        log(packet.senderName(), target.getName(), packet.rawText(), true);
        plugin.services().find(NetworkService.class)
                .ifPresent(network -> network.publish(TYPE_ACK, Map.of("id", packet.id())));
    }

    private void onAck(String json) {
        plugin.scheduler().global(() -> handleAck(json));
    }

    private void handleAck(String json) {
        String id;
        try {
            id = gson.fromJson(json, AckPacket.class).id();
        } catch (Throwable t) {
            return;
        }
        PendingDelivery delivery = pending.remove(id);
        if (delivery == null) {
            return;
        }
        replyTargets.put(delivery.sender().getUniqueId(), delivery.targetName());
        if (delivery.sender().isOnline()) {
            plugin.scheduler().atEntity(delivery.sender(), () -> delivery.sender().sendMessage(delivery.senderLine()));
        }
    }

    private void notifyMentions(UUID senderId, java.util.List<String> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return;
        }
        MentionNotifier notifier = plugin.services().find(MentionNotifier.class).orElse(null);
        if (notifier != null) {
            notifier.notifyMentioned(senderId, mentions, "");
        }
    }

    private void dispatchSpy(Component line, UUID senderId) {
        String permission = config.config().getString("permissions.spy", "mikuchat.private.spy");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(senderId)) {
                continue;
            }
            if (!online.hasPermission(permission)
                    || !userData.getBoolean(online.getUniqueId(), "private-spy", false)) {
                continue;
            }
            plugin.scheduler().atEntity(online, () -> online.sendMessage(line));
        }
    }

    private void log(String senderName, String targetName, String message, boolean crossServer) {
        if (!config.config().getBoolean("log.enabled", true)) {
            return;
        }
        if (crossServer && !config.config().getBoolean("log.cross-server", true)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String line = config.config().getString("log.format", "[{time}] {sender} -> {target}: {message}")
                .replace("{time}", now.format(logTimeFormat))
                .replace("{sender}", senderName)
                .replace("{target}", targetName)
                .replace("{message}", message);
        Path path = plugin.getDataFolder().toPath()
                .resolve("data/private-logs/" + now.format(dateFormat) + ".log");
        plugin.fileWriter().append(path, line);
    }

    private Component renderLine(String template, String senderName, String targetName, Component body) {
        String processed = template
                .replace("{sender}", senderName)
                .replace("{target}", targetName)
                .replace("{message}", "<mcp_body>");
        return TextUtil.mini().deserialize(processed, Placeholder.component("mcp_body", body));
    }

    private Player findLocal(String name) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return online;
            }
        }
        return null;
    }

    /** 递归检查组件树是否含 click / hover 交互事件。 */
    private boolean containsInteractive(Component component) {
        if (component.style().clickEvent() != null || component.style().hoverEvent() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (containsInteractive(child)) {
                return true;
            }
        }
        return false;
    }

    public String replyTarget(Player player) {
        return replyTargets.get(player.getUniqueId());
    }

    public boolean toggleSpy(Player player) {
        boolean enabled = !isSpy(player);
        userData.set(player.getUniqueId(), "private-spy", enabled);
        return enabled;
    }

    public boolean isSpy(Player player) {
        return userData.getBoolean(player.getUniqueId(), "private-spy", false);
    }

    public void forget(UUID uuid) {
        // 回复目标仅保留会话期，玩家数据由 UserDataStore 统一在退出时释放
        replyTargets.remove(uuid);
    }

    /** 跨服私聊包。targetServer 为空表示未定向（兼容旧版本节点，收到即投递）。 */
    public record PrivatePacket(String id, UUID senderId, String senderName, String targetName,
                                String targetServer, String receiverLine, String rawText, long timestamp) {
    }

    private record AckPacket(String id) {
    }

    private record PendingDelivery(Player sender, String targetName, Component senderLine, String rawText) {
    }
}
