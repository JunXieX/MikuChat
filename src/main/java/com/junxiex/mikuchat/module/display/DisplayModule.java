package com.junxiex.mikuchat.module.display;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.text.TokenRegistry;

/**
 * 展示模块：注册 [item] / [inv] / [ender] 标记处理器。
 */
public final class DisplayModule implements ChatModule {

    private final MikuChat plugin;
    private final DisplayManager displayManager;

    public DisplayModule(MikuChat plugin) {
        this.plugin = plugin;
        this.displayManager = new DisplayManager(plugin);
    }

    @Override
    public String id() {
        return "display";
    }

    @Override
    public void enable() {
        displayManager.load();
        plugin.services().require(TokenRegistry.class).register(displayManager);
    }

    @Override
    public void disable() {
        plugin.services().require(TokenRegistry.class).unregister(displayManager);
    }

    @Override
    public void reload() {
        TokenRegistry registry = plugin.services().require(TokenRegistry.class);
        registry.unregister(displayManager);
        displayManager.load();
        registry.register(displayManager);
    }
}
