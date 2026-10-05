package com.junxiex.mikuchat.module.style;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.module.ChatModule;

/**
 * 样式模块：注册样式权限净化器。
 */
public final class StyleModule implements ChatModule {

    private final MikuChat plugin;
    private final StyleFilter styleFilter;

    public StyleModule(MikuChat plugin) {
        this.plugin = plugin;
        this.styleFilter = new StyleFilter(plugin);
    }

    @Override
    public String id() {
        return "style";
    }

    @Override
    public void enable() {
        plugin.services().register(StyleFilter.class, styleFilter);
    }

    @Override
    public void disable() {
        plugin.services().unregister(StyleFilter.class);
    }

    @Override
    public void reload() {
        styleFilter.reload();
    }
}
