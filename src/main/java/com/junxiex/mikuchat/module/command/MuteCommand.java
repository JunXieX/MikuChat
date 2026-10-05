package com.junxiex.mikuchat.module.command;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.service.MuteService;
import com.junxiex.mikuchat.module.mute.MuteManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /mute 与 /unmute 命令。两者共用同一实现，靠 {@code commandName} 区分行为。
 */
public final class MuteCommand implements BasicCommand {

    private final MikuChat plugin;
    private final String commandName;

    public MuteCommand(MikuChat plugin, String commandName) {
        this.plugin = plugin;
        this.commandName = commandName;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        run(source.getSender(), args);
    }

    private void run(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mikuchat.mute")) {
            sender.sendMessage(plugin.lang().get("generic.no-permission"));
            return;
        }
        MuteManager mute = plugin.services().find(MuteManager.class).orElse(null);
        if (mute == null) {
            sender.sendMessage(plugin.lang().get("generic.unknown-command"));
            return;
        }
        String name = commandName;
        if (name.equals("unmute")) {
            if (args.length < 1) {
                sender.sendMessage(plugin.lang().get("mute.usage-unmute"));
                return;
            }
            resolve(sender, args[0], (uuid, resolvedName) -> {
                if (!mute.unmute(uuid)) {
                    send(sender, plugin.lang().get("mute.not-muted", "player", resolvedName));
                    return;
                }
                send(sender, plugin.lang().get("mute.unmuted-target", "player", resolvedName));
            });
            return;
        }

        if (args.length < 1) {
            sender.sendMessage(plugin.lang().get("mute.usage-mute"));
            return;
        }
        long duration = 0;
        String reason = "";
        if (args.length >= 2) {
            duration = parseDuration(args[1]);
            if (duration < 0) {
                sender.sendMessage(plugin.lang().get("mute.usage-mute"));
                return;
            }
            if (args.length >= 3) {
                reason = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            }
        }
        long finalDuration = duration;
        String finalReason = reason;
        String targetName = args[0];
        resolve(sender, targetName, (uuid, resolvedName) -> {
            mute.mute(uuid, finalDuration, finalReason);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                plugin.scheduler().atEntity(online, () -> online.sendMessage(plugin.lang().get("mute.muted-you",
                        "reason", finalReason.isEmpty() ? "-" : finalReason,
                        "duration", mute.formatRemaining(finalDuration))));
            }
            send(sender, plugin.lang().get("mute.muted-target",
                    "player", resolvedName,
                    "duration", mute.formatRemaining(finalDuration),
                    "reason", finalReason.isEmpty() ? "-" : finalReason));
        });
    }

    /** 回调可能在全局/异步线程触发，玩家消息统一回到其所属线程再发送。 */
    private void send(CommandSender sender, net.kyori.adventure.text.Component message) {
        if (sender instanceof Player player) {
            plugin.scheduler().atEntity(player, () -> player.sendMessage(message));
        } else {
            sender.sendMessage(message);
        }
    }

    private interface Resolved {
        void accept(UUID uuid, String name);
    }

    private void resolve(CommandSender sender, String name, Resolved callback) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            callback.accept(online.getUniqueId(), online.getName());
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) {
            callback.accept(cached.getUniqueId(), cached.getName() == null ? name : cached.getName());
            return;
        }
        plugin.scheduler().async(() -> {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (offline == null || (!offline.hasPlayedBefore() && offline.getName() == null)) {
                plugin.scheduler().global(() -> send(sender,
                        plugin.lang().get("generic.player-not-found", "player", name)));
                return;
            }
            String resolved = offline.getName() == null ? name : offline.getName();
            plugin.scheduler().global(() -> callback.accept(offline.getUniqueId(), resolved));
        });
    }

    /** @return 毫秒；-1 表示非法；0 表示永久 */
    private long parseDuration(String input) {
        if (input == null || input.isBlank()) {
            return 0;
        }
        String value = input.toLowerCase(Locale.ROOT).trim();
        if (value.equals("permanent") || value.equals("perm") || value.equals("永久")) {
            return 0;
        }
        long multiplier = 60_000L;
        if (value.endsWith("s")) {
            multiplier = 1000L;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = 60_000L;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("h")) {
            multiplier = 3_600_000L;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("d")) {
            multiplier = 86_400_000L;
            value = value.substring(0, value.length() - 1);
        }
        try {
            long amount = Long.parseLong(value.trim());
            if (amount <= 0) {
                return -1;
            }
            return Math.multiplyExact(amount, multiplier);
        } catch (NumberFormatException | ArithmeticException e) {
            // 非法数字或长度溢出都视为参数错误，避免溢出成负数被当成"永久禁言"
            return -1;
        }
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        return complete(source.getSender(), args);
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(online.getName());
            }
        }
        return result;
    }
}
