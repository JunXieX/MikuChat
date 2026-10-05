package com.junxiex.mikuchat.module.mention;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.service.MentionNotifier;
import com.junxiex.mikuchat.core.text.TokenRegistry;

/**
 * @ 艾特模块。
 */
public final class MentionModule implements ChatModule {

    private final MikuChat plugin;
    private final MentionManager mentionManager;

    public MentionModule(MikuChat plugin) {
        this.plugin = plugin;
        this.mentionManager = new MentionManager(plugin);
    }

    @Override
    public String id() {
        return "mention";
    }

    @Override
    public void enable() {
        mentionManager.load();
        plugin.services().require(TokenRegistry.class).register(mentionManager);
        plugin.services().register(MentionNotifier.class, mentionManager);
        plugin.services().register(MentionManager.class, mentionManager);
    }

    @Override
    public void disable() {
        plugin.services().unregister(MentionManager.class);
        plugin.services().unregister(MentionNotifier.class);
        plugin.services().require(TokenRegistry.class).unregister(mentionManager);
    }

    @Override
    public void reload() {
        TokenRegistry registry = plugin.services().require(TokenRegistry.class);
        registry.unregister(mentionManager);
        mentionManager.load();
        registry.register(mentionManager);
    }
}
