package com.junxiex.mikuchat.module.privatechat;

import com.junxiex.mikuchat.MikuChat;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * /msg、/reply 命令。两者共用同一实现，靠 {@code commandName} 区分行为。
 */
public final class PrivateCommand implements BasicCommand {

    private final MikuChat plugin;
    private final PrivateManager manager;
    private final String commandName;

    public PrivateCommand(MikuChat plugin, PrivateManager manager, String commandName) {
        this.plugin = plugin;
        this.manager = manager;
        this.commandName = commandName;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        run(source.getSender(), args);
    }

    private void run(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.lang().get("generic.player-only"));
            return;
        }
        if (commandName.equals("reply")) {
            if (args.length == 0) {
                player.sendMessage(plugin.lang().get("private.usage-reply"));
                return;
            }
            String target = manager.replyTarget(player);
            if (target == null) {
                player.sendMessage(plugin.lang().get("private.no-reply"));
                return;
            }
            manager.send(player, target, String.join(" ", args));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.lang().get("private.usage-msg"));
            return;
        }
        String target = args[0];
        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        manager.send(player, target, message);
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        return complete(source.getSender(), args);
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        if (commandName.equals("reply")) {
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
