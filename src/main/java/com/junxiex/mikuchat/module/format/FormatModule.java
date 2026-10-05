package com.junxiex.mikuchat.module.format;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.service.FormatService;

/**
 * 格式模块。
 */
public final class FormatModule implements ChatModule {

    private final MikuChat plugin;
    private FormatManager formatManager;

    public FormatModule(MikuChat plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "format";
    }

    @Override
    public void enable() {
        this.formatManager = new FormatManager(plugin);
        formatManager.load();
        plugin.services().register(FormatService.class, formatManager);
        plugin.services().register(FormatManager.class, formatManager);
    }

    @Override
    public void disable() {
        plugin.services().unregister(FormatManager.class);
        plugin.services().unregister(FormatService.class);
    }

    @Override
    public void reload() {
        formatManager.load();
    }
}
