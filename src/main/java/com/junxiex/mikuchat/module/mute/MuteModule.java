package com.junxiex.mikuchat.module.mute;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.service.MuteService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * 禁言模块：本地存储 + 代理端共享存储跨服同步。
 */
public final class MuteModule implements ChatModule {

    private final MikuChat plugin;
    private MuteManager muteManager;
    private ScheduledTask purgeTask;

    public MuteModule(MikuChat plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "mute";
    }

    @Override
    public void enable() {
        this.muteManager = new MuteManager(plugin);
        muteManager.load();
        muteManager.subscribe();
        plugin.services().register(MuteService.class, muteManager);
        plugin.services().register(MuteManager.class, muteManager);
        // 定时任务与本次创建的 manager 绑定，停用时一并取消，避免残留任务引用旧实例
        this.purgeTask = plugin.scheduler().asyncTimer(muteManager::purge, 1200, 6000);
    }

    @Override
    public void disable() {
        if (purgeTask != null) {
            purgeTask.cancel();
            purgeTask = null;
        }
        if (muteManager != null) {
            muteManager.unsubscribe();
            muteManager.shutdown();
            plugin.services().unregister(MuteManager.class);
            plugin.services().unregister(MuteService.class);
            muteManager = null;
        }
    }

    @Override
    public void reload() {
        if (muteManager != null) {
            muteManager.unsubscribe();
            muteManager.load();
            muteManager.subscribe();
        }
    }
}
