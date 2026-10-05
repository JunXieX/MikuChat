package com.junxiex.mikuchat.module.antispam;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.service.AntiSpamService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 防刷屏模块。
 */
public final class AntiSpamModule implements ChatModule, Listener {

    private final MikuChat plugin;
    private final AntiSpamManager antiSpamManager;

    public AntiSpamModule(MikuChat plugin) {
        this.plugin = plugin;
        this.antiSpamManager = new AntiSpamManager(plugin);
    }

    @Override
    public String id() {
        return "antispam";
    }

    @Override
    public void enable() {
        antiSpamManager.load();
        plugin.services().register(AntiSpamService.class, antiSpamManager);
        plugin.services().register(AntiSpamManager.class, antiSpamManager);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        org.bukkit.event.HandlerList.unregisterAll(this);
        plugin.services().unregister(AntiSpamManager.class);
        plugin.services().unregister(AntiSpamService.class);
    }

    @Override
    public void reload() {
        antiSpamManager.load();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        antiSpamManager.clear(event.getPlayer());
    }
}
