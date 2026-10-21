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
    /** 格式模板解析失败告警的去重条目上限：配置写错时只提示少量，避免每条消息都刷日志。 */
    private static final int MAX_FORMAT_WARNINGS = 16;

    private final MikuChat plugin;
    private final ConfigFile config;
    private final Gson gson = new Gson();
    private final com.junxiex.mikuchat.core.user.UserDataStore userData;

    private final Map<UUID, String> replyTargets = new ConcurrentHashMap<>();
    private final Map<String, PendingDelivery> pending = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warnedFormatErrors = new ConcurrentHashMap<>();

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

        // 上一步过滤的是原文，而 MiniMessage 标签不进入可见文本：
        // 把屏蔽词拆进标签（如「傻<red>逼」）就能让原文匹配落空、游戏内照常显示完整违禁词。
        // 因此在正文组装完成后，再按玩家实际看到的纯文本校验一次。
        // 只有输入里带标签/色码时才可能「标签拆词」；普通私聊的可见文本与原文一致，第一次过滤已覆盖。
        boolean mayHideMarkup = content.indexOf('<') >= 0 || content.indexOf('§') >= 0;
        if (mayHideMarkup && filter != null
                && filter.blockedByVisibleText(sender, null, TextUtil.plain(body))) {
            sender.sendMessage(plugin.lang().get("filter.blocked"));
            return;
        }

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
            // 接收方同样要能 /reply 回来：replyTargets 是两个方向的会话指针，
            // 只写发送方会导致「A 发给 B 后，B 用 /reply 提示没有回复对象」。
            replyTargets.put(localTarget.getUniqueId(), sender.getName());
            plugin.scheduler().atEntity(sender, () -> sender.sendMessage(senderLine));
            plugin.scheduler().atEntity(localTarget, () -> localTarget.sendMessage(receiverLine));
            dispatchSpy(spyLine, sender.getUniqueId(), localTarget.getUniqueId());
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
            // 接收方不在本服：本服的监听管理员只能由发送端转发这一份副本，
            // 接收端会各自转发它那一份，两侧互补。接收方不在本服，故 receiverId 传 null（不会误排除任何人）。
            dispatchSpy(spyLine, sender.getUniqueId(), null);
            String receiverJson = TextUtil.toJson(receiverLine);
            String spyJson = TextUtil.toJson(spyLine);
            // 单条插件消息的硬上限是 32767 字节：两行组件（接收行 + 监听行）合计过大时
            // 放弃监听行副本，优先保证私聊本体能够送达。
            if (receiverJson.length() + spyJson.length() > 24000) {
                spyJson = null;
            }
            pending.put(id, new PendingDelivery(sender, targetName, senderLine, finalContent));
            network.publish(TYPE_PRIVATE, new PrivatePacket(id, sender.getUniqueId(), sender.getName(),
                    targetName, owners.get(0), receiverJson, spyJson,
                    finalContent, composed.mentions(), System.currentTimeMillis()));
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
        if (packet == null || packet.targetName() == null || packet.receiverLine() == null) {
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
        // 跨服内容来自信任边界之外：先做体积校验再解析，避免超大组件先被反序列化进内存
        if (packet.receiverLine().length() > MAX_REMOTE_LINE_LENGTH) {
            plugin.debug("丢弃体积异常的跨服私聊组件: " + packet.senderName());
            return;
        }
        Component receiverLine;
        try {
            receiverLine = TextUtil.fromJson(packet.receiverLine());
        } catch (Throwable t) {
            plugin.debug("解析跨服私聊失败: " + t.getMessage());
            return;
        }
        if (receiverLine == null) {
            return;
        }
        // 跨服组件不可信：剥离 click / hover 交互事件，防止对端借私聊向本服玩家注入
        // 点击执行指令等恶意组件。这里只剥离、不丢弃：@ 提醒与 [item] 展示本身就会带上
        // 交互事件，若整条丢弃，含这些标记的跨服私聊会静默消失、双方都收不到任何提示。
        final Component safeLine = TextUtil.stripInteractive(receiverLine);
        plugin.scheduler().atEntity(target, () -> target.sendMessage(safeLine));
        // 接收方也要能 /reply 回来：跨服场景下发送方的回复指针由 ACK 写入，
        // 接收方这一侧必须在投递时补上，否则 B 回不了 A，只能提示「没有回复对象」。
        replyTargets.put(target.getUniqueId(), packet.safeSenderName());
        // spyLine 是发送端单独渲染的监听行。旧版本节点没有该字段（Gson 反序列化为 null），
        // 缺失或非法时只能退回用接收行冒充，保证监听内容不会整条丢失。
        // 校验顺序：先长度上限（避免超大 JSON 先被反序列化进内存），再 fromJson（catch），最后剥离交互事件。
        Component spyLine = safeLine;
        if (packet.spyLine() != null && !packet.spyLine().isEmpty()
                && packet.spyLine().length() <= MAX_REMOTE_LINE_LENGTH) {
            try {
                Component parsed = TextUtil.fromJson(packet.spyLine());
                if (parsed != null) {
                    spyLine = TextUtil.stripInteractive(parsed);
                }
            } catch (Throwable t) {
                // 非法 spyLine 静默回退到接收行，不能影响私聊本体投递
            }
        }
        dispatchSpy(spyLine, packet.senderId(), target.getUniqueId());
        // 跨服私聊里的 @ 提醒由接收端触发：发送端的本地提醒覆盖不到另一台服务器的玩家
        notifyMentions(packet.senderId(), packet.safeMentions());
        log(packet.safeSenderName(), target.getName(), packet.safeRawText(), true);
        plugin.services().find(NetworkService.class)
                .ifPresent(network -> network.publish(TYPE_ACK, Map.of("id", String.valueOf(packet.id()))));
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
        // 缺 id 的包（异构/旧版节点）会让 ConcurrentHashMap.remove(null) 抛 NPE
        if (id == null) {
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

    /** 转发给开启监听的在线管理员；发送者与接收者本人都不应收到自己的私聊副本。 */
    private void dispatchSpy(Component line, UUID senderId, UUID receiverId) {
        String permission = spyPermission();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(senderId) || online.getUniqueId().equals(receiverId)) {
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
        try {
            String processed = template
                    .replace("{sender}", senderName)
                    .replace("{target}", targetName)
                    .replace("{message}", "<mcp_body>");
            return TextUtil.mini().deserialize(processed, Placeholder.component("mcp_body", body));
        } catch (RuntimeException e) {
            // 配置模板写错（非法 MiniMessage 标签、未闭合标签等）不能让 /msg 整体不可用：
            // 回退为只显示正文，并只告警一次，避免每条私聊都刷日志。
            warnFormatOnce(template, e);
            return body == null ? Component.empty() : body;
        }
    }

    /** 格式模板解析失败只告警一次，并用有限容量的 Map 控制去重条目，防止异常配置放大内存。 */
    private void warnFormatOnce(String template, RuntimeException e) {
        if (warnedFormatErrors.size() >= MAX_FORMAT_WARNINGS) {
            return;
        }
        String key = String.valueOf(template);
        if (warnedFormatErrors.putIfAbsent(key, Boolean.TRUE) == null) {
            plugin.getLogger().warning("私聊消息格式模板解析失败，已回退为只显示正文: " + e.getMessage());
        }
    }

    private Player findLocal(String name) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return online;
            }
        }
        return null;
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

    /**
     * 私聊监听权限节点。每次读取配置，reload 后立即生效；配置缺失或为空时回退默认节点，
     * 避免 hasPermission(null) 抛异常。
     */
    public String spyPermission() {
        String permission = config.config().getString("permissions.spy", "mikuchat.private.spy");
        return permission == null || permission.isBlank() ? "mikuchat.private.spy" : permission;
    }

    public void forget(UUID uuid) {
        // 回复目标仅保留会话期，玩家数据由 UserDataStore 统一在退出时释放
        replyTargets.remove(uuid);
    }

    /**
     * 跨服私聊包。targetServer 为空表示未定向（兼容旧版本节点，收到即投递）。
     * <p>
     * {@code mentions} 用于接收端补发 @ 提醒：发送端本地的提醒只覆盖本服玩家，
     * 目标玩家在另一台服务器，必须由接收端触发，否则跨服私聊里的 @ 永远不会有提示。
     * <p>
     * {@code spyLine} 是发送端渲染好的监听行；旧版本节点不发该字段，接收端需按 null 处理并回退。
     */
    public record PrivatePacket(String id, UUID senderId, String senderName, String targetName,
                                String targetServer, String receiverLine, String spyLine, String rawText,
                                List<String> mentions, long timestamp) {

        /** 兼容旧版本节点发来的、不带 mentions 字段的包（Gson 会把缺失字段置为 null）。 */
        public List<String> safeMentions() {
            return mentions == null ? List.of() : mentions;
        }

        /** 缺字段时给出空串，避免拼接日志时 String.replace 收到 null 抛 NPE。 */
        public String safeSenderName() {
            return senderName == null ? "" : senderName;
        }

        public String safeRawText() {
            return rawText == null ? "" : rawText;
        }
    }

    private record AckPacket(String id) {
    }

    private record PendingDelivery(Player sender, String targetName, Component senderLine, String rawText) {
    }
}
