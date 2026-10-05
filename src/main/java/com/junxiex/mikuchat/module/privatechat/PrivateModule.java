package com.junxiex.mikuchat.module.privatechat;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.module.ChatModule;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

/**
 * 私聊模块：命令接管、文件留档、管理转发、跨服投递。
 */
public final class PrivateModule implements ChatModule, Listener {

    private final MikuChat plugin;
    private final PrivateManager privateManager;

    public PrivateModule(MikuChat plugin) {
        this.plugin = plugin;
        this.privateManager = new PrivateManager(plugin);
    }

    @Override
    public String id() {
        return "private";
    }

    @Override
    public void enable() {
        privateManager.load();
        privateManager.subscribe();
        plugin.services().register(PrivateManager.class, privateManager);

        plugin.commands().register("msg", "发送私聊",
                List.of("tell", "w", "whisper", "m"), new PrivateCommand(plugin, privateManager, "msg"));
        plugin.commands().register("reply", "回复上一次私聊",
                List.of("r"), new PrivateCommand(plugin, privateManager, "reply"));

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        org.bukkit.event.HandlerList.unregisterAll(this);
        privateManager.unsubscribe();
        plugin.services().unregister(PrivateManager.class);
    }

    @Override
    public void reload() {
        privateManager.load();
        privateManager.unsubscribe();
        privateManager.subscribe();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        privateManager.forget(event.getPlayer().getUniqueId());
    }
}
