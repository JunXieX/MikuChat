package com.junxiex.mikuchat.module.chat;

import com.google.gson.Gson;
import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.service.AntiSpamService;
import com.junxiex.mikuchat.core.service.ChannelScope;
import com.junxiex.mikuchat.core.service.ChannelService;
import com.junxiex.mikuchat.core.service.ChannelView;
import com.junxiex.mikuchat.core.service.ChatLogService;
import com.junxiex.mikuchat.core.service.FilterService;
import com.junxiex.mikuchat.core.service.FormatService;
import com.junxiex.mikuchat.core.service.MentionNotifier;
import com.junxiex.mikuchat.core.service.MuteService;
import com.junxiex.mikuchat.core.service.NetworkService;
import com.junxiex.mikuchat.core.text.TextUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * 聊天管线模块：接管 AsyncChatEvent，编排「禁言 → 防刷屏 → 过滤 → 组装 → 投递」完整流程。
 * 监听器在异步线程触发，实际处理调度到发送者所在线程，保证 Folia 下的线程安全。
 */
public final class ChatPipelineModule implements ChatModule, Listener {

    private static final String TYPE_CHAT = "CHAT";
    /** 跨服聊天组件 JSON 的最大长度，超出即视为可疑并丢弃（与私聊保持一致）。 */
    private static final int MAX_REMOTE_LINE_LENGTH = 16384;

    private final MikuChat plugin;
    private final ConfigFile config;
    private final Gson gson = new Gson();
    private MessageComposer composer;
    private final BiConsumer<String, String> remoteHandler = (source, json) -> onRemoteChat(json);
    /** 频道触发符号 -> 频道 id，按符号长度倒序，保证 "##" 优先于 "#" 匹配。 */
    private volatile java.util.Map<String, String> triggers = java.util.Map.of();

    public ChatPipelineModule(MikuChat plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().get("chat.yml");
    }

    @Override
    public String id() {
        return "chat";
    }

    @Override
    public void enable() {
        plugin.configManager().reload("chat.yml");
        loadTriggers();
        this.composer = new MessageComposer(plugin);
        plugin.services().register(MessageComposer.class, composer);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.services().find(NetworkService.class).ifPresent(network -> network.subscribe(TYPE_CHAT, remoteHandler));
    }

    @Override
    public void disable() {
        org.bukkit.event.HandlerList.unregisterAll(this);
        plugin.services().find(NetworkService.class).ifPresent(network -> network.unsubscribe(TYPE_CHAT, remoteHandler));
        plugin.services().unregister(MessageComposer.class);
    }

    @Override
    public void reload() {
        plugin.configManager().reload("chat.yml");
        loadTriggers();
        plugin.services().find(NetworkService.class).ifPresent(network -> {
            network.unsubscribe(TYPE_CHAT, remoteHandler);
            network.subscribe(TYPE_CHAT, remoteHandler);
        });
    }

    /** 读取频道触发符号配置，例如 {@code "#": global} 表示以 # 开头的消息发到跨服频道。 */
    private void loadTriggers() {
        java.util.List<java.util.Map.Entry<String, String>> loaded = new java.util.ArrayList<>();
        var section = config.config().getConfigurationSection("triggers");
        if (section != null) {
            for (String symbol : section.getKeys(false)) {
                String channelId = section.getString(symbol);
                if (symbol.isEmpty() || channelId == null || channelId.isBlank()) {
                    continue;
                }
                loaded.add(java.util.Map.entry(symbol, channelId.trim()));
            }
        }
        loaded.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        java.util.Map<String, String> ordered = new java.util.LinkedHashMap<>();
        for (var entry : loaded) {
            ordered.put(entry.getKey(), entry.getValue());
        }
        this.triggers = ordered;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        Set<Audience> viewers = new HashSet<>(event.viewers());
        String raw = TextUtil.plain(event.message());
        event.setCancelled(true);
        plugin.scheduler().atEntity(sender, () -> {
            try {
                process(sender, raw, viewers);
            } catch (Throwable t) {
                // 消息已被取消：任何未预期的异常都会让这条消息静默消失，至少留下可排查的日志
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "处理聊天消息时发生未预期异常（该条消息已丢弃）", t);
            }
        });
    }

    private void process(Player sender, String raw, Set<Audience> viewers) {
        ChannelService channelService = plugin.services().find(ChannelService.class).orElse(null);
        if (channelService == null) {
            sender.sendMessage(plugin.lang().get("generic.unknown-command"));
            return;
        }
        ChannelView channel = channelService.selected(sender);
        // 频道触发符号：消息以配置的符号开头时强制切换频道，并把符号从内容中移除
        String content = raw;
        if (!triggers.isEmpty()) {
            for (var entry : triggers.entrySet()) {
                if (!raw.startsWith(entry.getKey())) {
                    continue;
                }
                ChannelView forced = channelService.byId(entry.getValue());
                if (forced == null || !forced.valid()) {
                    continue;
                }
                if (!channelService.mayUse(sender, forced)) {
                    sender.sendMessage(plugin.lang().get("generic.no-permission"));
                    return;
                }
                String trimmed = raw.substring(entry.getKey().length()).stripLeading();
                if (trimmed.isEmpty()) {
                    sender.sendMessage(plugin.lang().get("channel.trigger-empty", "symbol", entry.getKey()));
                    return;
                }
                channel = forced;
                content = trimmed;
                break;
            }
        }
        raw = content;
        if (channel == null || !channel.valid()) {
            sender.sendMessage(plugin.lang().get("channel.not-found", "channel", plugin.defaultChannelId()));
            return;
        }
        // 发送者自身也必须能用该频道：投递阶段会按频道权限过滤接收者，
        // 若发送者本来就没权限（例如默认频道被设了权限），消息会被静默丢弃且没有任何提示。
        ChannelService channelCheck = plugin.services().find(ChannelService.class).orElse(null);
        if (channelCheck != null && !channelCheck.mayUse(sender, channel)) {
            sender.sendMessage(plugin.lang().get("channel.no-permission", "channel", channel.id()));
            return;
        }
        MuteService mute = plugin.services().find(MuteService.class).orElse(null);
        if (mute != null) {
            var info = mute.muteOf(sender.getUniqueId());
            if (info != null) {
                sender.sendMessage(plugin.lang().get("mute.blocked"));
                long remaining = info.permanent() ? 0 : Math.max(0, info.expireAt() - System.currentTimeMillis());
                sender.sendMessage(plugin.lang().get("mute.muted-you",
                        "reason", info.reason(),
                        "duration", mute.formatRemaining(remaining)));
                return;
            }
        }

        AntiSpamService antiSpam = plugin.services().find(AntiSpamService.class).orElse(null);
        if (antiSpam != null) {
            var result = antiSpam.check(sender, channel, raw);
            if (result.blocked()) {
                if (!result.silent()) {
                    sender.sendMessage(antiSpam.feedback(plugin.lang(), result));
                }
                return;
            }
        }

        FilterService filter = plugin.services().find(FilterService.class).orElse(null);
        if (filter != null) {
            var result = filter.apply(sender, channel, raw);
            if (result.blocked()) {
                sender.sendMessage(plugin.lang().get(result.advertisement()
                        ? "filter.ad-blocked" : "filter.blocked"));
                return;
            }
            if (result.modified()) {
                raw = result.text();
                if (result.notified()) {
                    sender.sendMessage(plugin.lang().get("filter.replaced"));
                }
            }
        }

        raw = TextUtil.truncate(raw, config.config().getInt("message-max-length", 256));

        NetworkService network = plugin.services().find(NetworkService.class).orElse(null);
        boolean crossServer = channel.scope() == ChannelScope.NETWORK;
        // 用 connected() 而不是 available()：通道注册成功不代表代理端插件存在，
        // 单机服上 available() 恒为 true，会让 network-unavailable-action: DROP 永不生效。
        boolean networkReady = network != null && network.connected();
        // 是否投递必须先于正文组装判定：组装会触发 [item]/@ 等处理器，这些处理器会消费冷却
        // 并向玩家发送「冷却中/无物品」等提示。若先组装再因频道不可用而丢弃，玩家既损失了冷却
        // 又收到与真实发送无关的提示。
        if (crossServer && !networkReady
                && "DROP".equalsIgnoreCase(config.config().getString("network-unavailable-action", "LOCAL"))) {
            sender.sendMessage(plugin.lang().get("channel.disabled", "channel", channel.id()));
            return;
        }

        MessageComposer.Composed composed = composer.compose(sender, channel, raw);
        // MiniMessage 标签可以插在词中间绕过原文过滤（"傻<red>逼" 归一化后是 "傻red逼"），
        // 因此再对渲染后的可见文本做一次只拦截不替换的校验；命中说明发送者试图用标签拆词。
        // 只有输入里真的带标签/色码时才可能出现「标签拆词」：普通消息的可见文本与过滤后的原文一致，
        // 第一次过滤已经覆盖，没必要每条消息都再跑一遍归一化与自动机扫描。
        boolean mayHideMarkup = raw.indexOf('<') >= 0 || raw.indexOf('§') >= 0;
        if (mayHideMarkup && filter != null
                && filter.blockedByVisibleText(sender, channel, TextUtil.plain(composed.body()))) {
            sender.sendMessage(plugin.lang().get("filter.blocked"));
            return;
        }
        FormatService format = plugin.services().find(FormatService.class).orElse(null);
        Component line;
        if (format == null) {
            line = composed.body();
        } else {
            try {
                line = format.render(sender, channel, composed.body());
            } catch (RuntimeException e) {
                // 配置里的格式模板写错不应让整条聊天消失，退化为只发正文
                plugin.debug("聊天格式渲染失败，已退化为正文: " + e);
                line = composed.body();
            }
        }

        deliverLocal(viewers, channel, line);

        if (crossServer && networkReady) {
            network.publish(TYPE_CHAT, new NetworkService.ChatOutbound(
                    sender.getUniqueId(), sender.getName(), network.serverId(), channel.id(),
                    raw, TextUtil.toJson(line), composed.mentions(),
                    System.currentTimeMillis()));
        }

        notifyMentions(sender.getUniqueId(), composed.mentions(), channel.displayName());
        record(channel, sender.getName(), raw, false);
    }

    /**
     * 本服投递。频道配了 permission 时只发给有权限的玩家：否则「限权频道」在本服照常
     * 广播给所有人，而同样的消息从别的服务器发来却会按权限过滤，两侧策略不一致。
     */
    private void deliverLocal(Collection<Audience> viewers, ChannelView channel, Component line) {
        ChannelService channelService = plugin.services().find(ChannelService.class).orElse(null);
        for (Audience audience : viewers) {
            if (audience instanceof Player player) {
                if (channelService != null && !channelService.mayUse(player, channel)) {
                    continue;
                }
                plugin.scheduler().atEntity(player, () -> player.sendMessage(line));
            } else {
                audience.sendMessage(line);
            }
        }
    }

    private void notifyMentions(UUID senderId, List<String> mentions, String channelDisplay) {
        MentionNotifier notifier = plugin.services().find(MentionNotifier.class).orElse(null);
        if (notifier != null) {
            notifier.notifyMentioned(senderId, mentions, channelDisplay);
        }
    }

    private void record(ChannelView channel, String senderName, String message, boolean remote) {
        if (!channel.log()) {
            return;
        }
        ChatLogService log = plugin.services().find(ChatLogService.class).orElse(null);
        if (log != null) {
            log.record(channel.id(), senderName, message, remote);
        }
    }

    private void onRemoteChat(String json) {
        // 该方法由插件消息回调触发，统一调度到全局线程再触碰玩家列表
        plugin.scheduler().global(() -> handleRemoteChat(json));
    }

    private void handleRemoteChat(String json) {
        NetworkService.ChatOutbound packet;
        try {
            packet = gson.fromJson(json, NetworkService.ChatOutbound.class);
        } catch (Throwable t) {
            plugin.debug("解析跨服聊天失败: " + t.getMessage());
            return;
        }
        if (packet == null || packet.lineJson() == null) {
            return;
        }
        // 跨服组件体积上限：与私聊保持一致，避免对端异常或恶意构造超大 JSON 撑爆内存
        if (packet.lineJson().length() > MAX_REMOTE_LINE_LENGTH) {
            plugin.debug("丢弃体积异常的跨服聊天组件: " + packet.senderName());
            return;
        }
        // 远端只应广播 NETWORK 作用域的频道。若不校验，任何一台服务器（或伪造包）
        // 都能把消息推到本服所有玩家，包括本服设为 SERVER 作用域、带权限限制的频道。
        ChannelService channelService = plugin.services().find(ChannelService.class).orElse(null);
        ChannelView channel = channelService == null ? null : channelService.byId(packet.channelId());
        if (channel == null || !channel.valid() || channel.scope() != ChannelScope.NETWORK) {
            plugin.debug("丢弃跨服聊天：本服不存在跨服频道 " + packet.channelId());
            return;
        }
        Component line;
        try {
            line = TextUtil.fromJson(packet.lineJson());
        } catch (Throwable t) {
            plugin.debug("解析跨服聊天组件失败: " + t.getMessage());
            return;
        }
        if (line == null) {
            return;
        }
        // 整行组件来自远端，其中的 click / hover 是发送服按自己的权限策略放行的。
        // 接收服若不剥离，等于让对端决定本服玩家能看到什么交互组件（可点击执行指令），
        // 与私聊路径保持同一标准。
        final Component safeLine = TextUtil.stripInteractive(line);
        for (Player player : Bukkit.getOnlinePlayers()) {
            // 按频道权限逐个筛选，复用 mayUse 以正确处理「permission 为空表示无限制」的语义
            if (!channelService.mayUse(player, channel)) {
                continue;
            }
            plugin.scheduler().atEntity(player, () -> player.sendMessage(safeLine));
        }
        // 频道显示名一律用本服配置里的值，而不是远端包携带的值：
        // 显示名是 MiniMessage 模板，若由远端提供并解析，等于给了对方注入任意组件的通道。
        notifyMentions(packet.senderId(), packet.mentions() == null ? List.of() : packet.mentions(),
                channel.displayName());
        record(channel, packet.senderName() == null ? "" : packet.senderName(),
                packet.rawText() == null ? "" : packet.rawText(), true);
    }
}
