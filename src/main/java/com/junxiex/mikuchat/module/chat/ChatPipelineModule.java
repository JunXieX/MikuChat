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
        plugin.scheduler().atEntity(sender, () -> process(sender, raw, viewers));
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

        MessageComposer.Composed composed = composer.compose(sender, channel, raw);
        FormatService format = plugin.services().find(FormatService.class).orElse(null);
        Component line = format == null ? composed.body() : format.render(sender, channel, composed.body());

        NetworkService network = plugin.services().find(NetworkService.class).orElse(null);
        boolean crossServer = channel.scope() == ChannelScope.NETWORK;
        boolean networkReady = network != null && network.available();
        // 先决定是否投递，再做本服广播：避免 DROP 模式下消息已经发出却仍提示“频道不可用”
        if (crossServer && !networkReady
                && "DROP".equalsIgnoreCase(config.config().getString("network-unavailable-action", "LOCAL"))) {
            sender.sendMessage(plugin.lang().get("channel.disabled", "channel", channel.id()));
            return;
        }

        deliverLocal(viewers, line);

        if (crossServer && networkReady) {
            network.publish(TYPE_CHAT, new NetworkService.ChatOutbound(
                    sender.getUniqueId(), sender.getName(), network.serverId(), channel.id(),
                    channel.displayName(), raw, TextUtil.toJson(line), composed.mentions(),
                    System.currentTimeMillis()));
        }

        notifyMentions(sender.getUniqueId(), composed.mentions(), channel.displayName());
        record(channel, sender.getName(), raw, false);
    }

    private void deliverLocal(Collection<Audience> viewers, Component line) {
        for (Audience audience : viewers) {
            if (audience instanceof Player player) {
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
        Component line;
        try {
            line = TextUtil.fromJson(packet.lineJson());
        } catch (Throwable t) {
            plugin.debug("解析跨服聊天组件失败: " + t.getMessage());
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.scheduler().atEntity(player, () -> player.sendMessage(line));
        }
        notifyMentions(packet.senderId(), packet.mentions(), packet.channelDisplay());
        ChannelView channel = plugin.services().find(ChannelService.class)
                .map(service -> service.byId(packet.channelId())).orElse(null);
        if (channel != null) {
            record(channel, packet.senderName(), packet.rawText(), true);
        }
    }
}
