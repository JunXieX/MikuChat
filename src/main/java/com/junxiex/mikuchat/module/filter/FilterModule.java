package com.junxiex.mikuchat.module.filter;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.service.FilterService;

/**
 * 过滤模块：屏蔽词 + 广告/链接检测。
 */
public final class FilterModule implements ChatModule {

    private final MikuChat plugin;
    private final FilterManager filterManager;

    public FilterModule(MikuChat plugin) {
        this.plugin = plugin;
        this.filterManager = new FilterManager(plugin);
    }

    @Override
    public String id() {
        return "filter";
    }

    @Override
    public void enable() {
        saveDefaults();
        filterManager.load();
        plugin.services().register(FilterService.class, filterManager);
        plugin.services().register(FilterManager.class, filterManager);
    }

    private void saveDefaults() {
        save("filter/words.yml");
        save("filter/words.txt");
    }

    private void save(String resource) {
        java.io.File file = new java.io.File(plugin.getDataFolder(), resource);
        if (file.exists()) {
            return;
        }
        try {
            plugin.saveResource(resource, false);
        } catch (IllegalArgumentException ignored) {
            // 资源缺失时忽略
        }
    }

    @Override
    public void disable() {
        plugin.services().unregister(FilterManager.class);
        plugin.services().unregister(FilterService.class);
    }

    @Override
    public void reload() {
        filterManager.load();
    }
}
