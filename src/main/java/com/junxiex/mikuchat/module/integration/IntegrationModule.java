package com.junxiex.mikuchat.module.integration;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.text.TemplateRenderer;

/**
 * 集成模块：负责 LuckPerms / PlaceholderAPI 的加载与模板渲染器注册。
 */
public final class IntegrationModule implements ChatModule {

    private final MikuChat plugin;
    private final Integrations integrations;
    private TemplateRenderer templateRenderer;

    public IntegrationModule(MikuChat plugin) {
        this.plugin = plugin;
        this.integrations = new Integrations(plugin);
    }

    @Override
    public String id() {
        return "integration";
    }

    @Override
    public void enable() {
        integrations.load();
        this.templateRenderer = new TemplateRenderer(integrations, plugin::serverId);
        plugin.services().register(Integrations.class, integrations);
        plugin.services().register(TemplateRenderer.class, templateRenderer);
    }

    @Override
    public void disable() {
        plugin.services().unregister(TemplateRenderer.class);
        plugin.services().unregister(Integrations.class);
        integrations.unload();
    }

    @Override
    public void reload() {
        integrations.load();
    }
}
