package com.junxiex.mikuchat.module.channel;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.user.UserDataStore;

/**
 * 频道模块。
 */
public final class ChannelModule implements ChatModule {

    private final MikuChat plugin;
    private final ChannelManager channelManager;

    public ChannelModule(MikuChat plugin) {
        this.plugin = plugin;
        this.channelManager = new ChannelManager(plugin, plugin.services().require(UserDataStore.class));
    }

    @Override
    public String id() {
        return "channel";
    }

    @Override
    public void enable() {
        channelManager.load();
        plugin.services().register(ChannelManager.class, channelManager);
        plugin.services().register(com.junxiex.mikuchat.core.service.ChannelService.class, channelManager);
    }

    @Override
    public void disable() {
        plugin.services().unregister(com.junxiex.mikuchat.core.service.ChannelService.class);
        plugin.services().unregister(ChannelManager.class);
    }

    @Override
    public void reload() {
        channelManager.reload();
    }
}
