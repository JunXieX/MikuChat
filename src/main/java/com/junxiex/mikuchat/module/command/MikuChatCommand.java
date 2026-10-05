package com.junxiex.mikuchat.module.command;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.service.ChannelService;
import com.junxiex.mikuchat.core.service.ChannelView;
import com.junxiex.mikuchat.core.service.ChatLogService;
import com.junxiex.mikuchat.core.service.NetworkService;
import com.junxiex.mikuchat.module.channel.ChannelManager;
import com.junxiex.mikuchat.module.filter.FilterManager;
import com.junxiex.mikuchat.module.mention.MentionManager;
import com.junxiex.mikuchat.module.privatechat.PrivateManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /mchat 主命令。
 */
public final class MikuChatCommand implements BasicCommand {

    private record Sub(String name, String usage, String description, String permission) {
    }

    private final MikuChat plugin;
    private final List<Sub> subs = List.of(
            new Sub("help", "/mchat help", "查看命令帮助", null),
            new Sub("channel", "/mchat channel <id|list>", "切换或查看聊天频道", null),
            new Sub("toggle", "/mchat toggle mention", "开关 @ 提醒", null),
            new Sub("spy", "/mchat spy", "开关私聊监听", "mikuchat.private.spy"),
            new Sub("who", "/mchat who", "查看跨服在线玩家", "mikuchat.command.who"),
            new Sub("log", "/mchat log <玩家|关键词> [页码]", "查询聊天记录", "mikuchat.command.log"),
            new Sub("filter", "/mchat filter test <文本>", "测试过滤结果", "mikuchat.command.filter"),
            new Sub("info", "/mchat info", "查看运行信息", "mikuchat.command.info"),
            new Sub("reload", "/mchat reload [模块]", "重载配置或单个模块", "mikuchat.command.reload")
    );
    private final Map<String, Sub> subIndex = new java.util.HashMap<>();

    /** 日志分页上限：page * pageSize 不得超过该值，避免用超大页码放大检索开销。 */
    private static final int LOG_PAGE_SIZE = 10;
    private static final int LOG_MAX_RESULTS = 200;

    public MikuChatCommand(MikuChat plugin) {
        this.plugin = plugin;
        for (Sub sub : subs) {
            subIndex.put(sub.name(), sub);
        }
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        run(source.getSender(), args);
    }

    private void run(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        Sub definition = subIndex.get(sub);
        if (definition == null) {
            sender.sendMessage(plugin.lang().get("generic.unknown-command"));
            return;
        }
        if (definition.permission() != null && !sender.hasPermission(definition.permission())) {
            sender.sendMessage(plugin.lang().get("generic.no-permission"));
            return;
        }
        switch (sub) {
            case "help" -> sendHelp(sender);
            case "channel" -> channel(sender, args);
            case "toggle" -> toggle(sender, args);
            case "spy" -> spy(sender);
            case "who" -> who(sender);
            case "log" -> log(sender, args);
            case "filter" -> filter(sender, args);
            case "info" -> info(sender);
            case "reload" -> reload(sender, args);
            default -> sender.sendMessage(plugin.lang().get("generic.unknown-command"));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.lang().get("command.help-header"));
        for (Sub sub : subs) {
            if (sub.permission() != null && !sender.hasPermission(sub.permission())) {
                continue;
            }
            sender.sendMessage(plugin.lang().parse(plugin.lang().raw("command.help-line")
                    .replace("{command}", sub.usage())
                    .replace("{usage}", sub.usage())
                    .replace("{description}", sub.description())));
        }
    }

    private void channel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.lang().get("generic.player-only"));
            return;
        }
        ChannelService channels = plugin.services().require(ChannelService.class);
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            channels.sendChannelList(sender, player);
            return;
        }
        ChannelView target = channels.byId(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.lang().get("channel.not-found", "channel", args[1]));
            return;
        }
        if (!channels.mayUse(player, target)) {
            sender.sendMessage(plugin.lang().get("channel.no-permission", "channel", target.id()));
            return;
        }
        ChannelView current = channels.selected(player);
        if (current != null && current.id().equalsIgnoreCase(target.id())) {
            sender.sendMessage(plugin.lang().get("channel.already", "channel", target.id()));
            return;
        }
        channels.select(player, target);
        sender.sendMessage(plugin.lang().getParsed("channel.switched", "channel", target.displayName()));
    }

    private void toggle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.lang().get("generic.player-only"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.lang().get("generic.unknown-command"));
            return;
        }
        if (args[1].equalsIgnoreCase("mention")) {
            MentionManager mention = plugin.services().find(MentionManager.class).orElse(null);
            if (mention == null) {
                player.sendMessage(plugin.lang().get("generic.unknown-command"));
                return;
            }
            boolean enabled = mention.toggle(player);
            player.sendMessage(plugin.lang().get(enabled ? "mention.toggled-on" : "mention.toggled-off"));
            return;
        }
        player.sendMessage(plugin.lang().get("generic.unknown-command"));
    }

    private void spy(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.lang().get("generic.player-only"));
            return;
        }
        if (!player.hasPermission("mikuchat.private.spy")) {
            player.sendMessage(plugin.lang().get("generic.no-permission"));
            return;
        }
        PrivateManager priv = plugin.services().find(PrivateManager.class).orElse(null);
        if (priv == null) {
            player.sendMessage(plugin.lang().get("generic.unknown-command"));
            return;
        }
        boolean enabled = priv.toggleSpy(player);
        player.sendMessage(plugin.lang().get(enabled ? "private.spy-on" : "private.spy-off"));
    }

    private void who(CommandSender sender) {
        NetworkService network = plugin.services().find(NetworkService.class).orElse(null);
        if (network == null || !network.available()) {
            sender.sendMessage(plugin.lang().get("who.disabled"));
            return;
        }
        if (sender instanceof Player player) {
            // 每次查询都会向整个网络广播，限制频率避免刷屏放大
            long remaining = plugin.services().require(com.junxiex.mikuchat.core.util.Cooldowns.class)
                    .check(player.getUniqueId(), "command:who", 3000L);
            if (remaining > 0) {
                sender.sendMessage(plugin.lang().get("generic.cooldown-command",
                        "seconds", String.format(Locale.ROOT, "%.1f", remaining / 1000.0)));
                return;
            }
        }
        sender.sendMessage(plugin.lang().get("who.requesting"));
        network.queryPresence(presence -> plugin.scheduler().global(() -> {
            if (presence.playersByServer().isEmpty()) {
                notifySender(sender, plugin.lang().get("who.none"));
                return;
            }
            notifySender(sender, plugin.lang().get("who.header", "count", String.valueOf(presence.total())));
            presence.playersByServer().forEach((server, players) -> notifySender(sender,
                    plugin.lang().get("who.server-line",
                            "server", server,
                            "count", String.valueOf(players.size()),
                            "players", String.join(", ", players))));
        }));
    }

    private void log(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.lang().get("log.usage"));
            return;
        }
        ChatLogService service = plugin.services().find(ChatLogService.class).orElse(null);
        if (service == null) {
            sender.sendMessage(plugin.lang().get("log.none"));
            return;
        }
        String keyword = args[1];
        int page = 1;
        if (args.length >= 3) {
            try {
                page = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.lang().get("generic.invalid-number", "value", args[2]));
                return;
            }
        }
        int maxPage = LOG_MAX_RESULTS / LOG_PAGE_SIZE;
        if (page > maxPage) {
            page = maxPage;
        }
        int limit = Math.min(page * LOG_PAGE_SIZE, LOG_MAX_RESULTS);
        final int finalPage = page;
        service.search(keyword, limit, results -> plugin.scheduler().global(() -> {
            if (results.isEmpty()) {
                notifySender(sender, plugin.lang().get("log.none"));
                return;
            }
            int from = Math.min((finalPage - 1) * LOG_PAGE_SIZE, results.size());
            int to = Math.min(from + LOG_PAGE_SIZE, results.size());
            notifySender(sender, plugin.lang().get("log.header",
                    "query", keyword, "page", String.valueOf(finalPage)));
            for (int i = from; i < to; i++) {
                notifySender(sender, Component.text(results.get(i)));
            }
        }));
    }

    private void filter(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("test")) {
            sender.sendMessage(plugin.lang().get("generic.unknown-command"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("/mchat filter test <文本>"));
            return;
        }
        FilterManager filterManager = plugin.services().find(FilterManager.class).orElse(null);
        if (filterManager == null) {
            sender.sendMessage(plugin.lang().get("generic.unknown-command"));
            return;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        List<String> words = filterManager.scanWords(text);
        boolean ad = filterManager.scanAdvertisement(text);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("原文", text);
        result.put("命中屏蔽词", words.isEmpty() ? "无" : String.join(", ", words));
        result.put("命中广告", ad ? "是" : "否");
        result.forEach((key, value) -> sender.sendMessage(
                plugin.lang().get("command.info-line", "key", key, "value", value)));
    }

    private void info(CommandSender sender) {
        sender.sendMessage(plugin.lang().get("command.info-header"));
        Map<String, String> info = new LinkedHashMap<>();
        info.put("server-id", plugin.serverId());
        info.put("默认频道", plugin.defaultChannelId());
        info.put("语言", plugin.lang().code());
        ChannelManager channels = plugin.services().find(ChannelManager.class).orElse(null);
        info.put("频道数", channels == null ? "0" : String.valueOf(channels.channels().size()));
        FilterManager filterManager = plugin.services().find(FilterManager.class).orElse(null);
        info.put("屏蔽词", filterManager == null ? "0" : String.valueOf(filterManager.wordCount()));
        NetworkService network = plugin.services().find(NetworkService.class).orElse(null);
        info.put("跨服", network != null && network.available() ? "已连接" : "未启用");
        info.put("在线", String.valueOf(Bukkit.getOnlinePlayers().size()));
        info.forEach((key, value) -> sender.sendMessage(
                plugin.lang().get("command.info-line", "key", key, "value", value)));
    }

    private void reload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mikuchat.command.reload")) {
            sender.sendMessage(plugin.lang().get("generic.no-permission"));
            return;
        }
        if (args.length >= 2) {
            String module = args[1];
            // 重载涉及磁盘 I/O，放到全局线程执行，避免阻塞触发的区域线程
            plugin.scheduler().global(() -> {
                boolean ok = plugin.moduleManager().reload(module);
                notifySender(sender, ok
                        ? plugin.lang().get("generic.reloaded-module", "module", module)
                        : plugin.lang().get("generic.module-not-found", "module", module));
            });
            return;
        }
        plugin.scheduler().global(() -> {
            plugin.reloadAll();
            notifySender(sender, plugin.lang().get("generic.reloaded"));
        });
    }

    private void notifySender(CommandSender sender, Component message) {
        if (sender instanceof Player player) {
            plugin.scheduler().atEntity(player, () -> player.sendMessage(message));
        } else {
            sender.sendMessage(message);
        }
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        return complete(source.getSender(), args);
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            for (Sub sub : subs) {
                if (sub.permission() != null && !sender.hasPermission(sub.permission())) {
                    continue;
                }
                if (sub.name().startsWith(prefix)) {
                    result.add(sub.name());
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("channel")) {
            List<String> result = new ArrayList<>();
            result.add("list");
            plugin.services().find(ChannelService.class).ifPresent(service -> {
                for (ChannelView channel : service.channels()) {
                    result.add(channel.id());
                }
            });
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            List<String> result = new ArrayList<>();
            for (String id : plugin.moduleManager().ids()) {
                if (id.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(id);
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            return List.of("mention");
        }
        return List.of();
    }
}
