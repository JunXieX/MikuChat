package com.junxiex.mikuchat.module.integration;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.text.MetaBridge;
import com.junxiex.mikuchat.core.text.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * LuckPerms 与 PlaceholderAPI 的软依赖接入。插件缺失时自动降级为默认值，不影响主流程。
 */
public final class Integrations implements MetaBridge {

    private final MikuChat plugin;
    private final ConfigFile config;

    // 由命令线程在 /mchat reload 时写入、由聊天线程读取，必须 volatile 保证可见性
    private volatile LuckPermsHook luckPerms;
    private volatile boolean placeholderApi;
    private volatile boolean miniMessageMeta;

    public Integrations(MikuChat plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().get("integrations.yml");
    }

    public void load() {
        plugin.configManager().reload("integrations.yml");
        this.miniMessageMeta = "minimessage".equalsIgnoreCase(
                config.config().getString("luckperms.format", "legacy"));

        boolean lpConfigured = config.config().getBoolean("luckperms.enabled", true);
        this.luckPerms = null;
        if (lpConfigured) {
            Plugin lpPlugin = Bukkit.getPluginManager().getPlugin("LuckPerms");
            if (lpPlugin != null && lpPlugin.isEnabled()) {
                this.luckPerms = LuckPermsHook.create();
                if (this.luckPerms == null) {
                    plugin.getLogger().warning("LuckPerms 已安装但 API 不可用。");
                }
            }
        }

        boolean papiConfigured = config.config().getBoolean("placeholderapi.enabled", true);
        Plugin papiPlugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        this.placeholderApi = papiConfigured && papiPlugin != null && papiPlugin.isEnabled();

        plugin.getLogger().info("Integrations -> LuckPerms: " + (luckPerms != null)
                + ", PlaceholderAPI: " + placeholderApi);
    }

    public void unload() {
        this.luckPerms = null;
        this.placeholderApi = false;
    }

    @Override
    public boolean hasPlaceholderApi() {
        return placeholderApi;
    }

    @Override
    public Component prefix(Player player) {
        String raw = luckPerms == null ? null : luckPerms.prefix(player);
        if (raw == null || raw.isEmpty()) {
            raw = config.config().getString("luckperms.prefix-default", "");
        }
        return parseMeta(raw);
    }

    @Override
    public Component suffix(Player player) {
        String raw = luckPerms == null ? null : luckPerms.suffix(player);
        if (raw == null || raw.isEmpty()) {
            raw = config.config().getString("luckperms.suffix-default", "");
        }
        return parseMeta(raw);
    }

    @Override
    public String group(Player player) {
        String group = luckPerms == null ? null : luckPerms.group(player);
        if (group != null && !group.isEmpty()) {
            return group;
        }
        return config.config().getString("luckperms.group-default", "default");
    }

    @Override
    public String placeholder(Player player, String params) {
        if (!placeholderApi || player == null) {
            return null;
        }
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%" + params + "%");
        } catch (Throwable t) {
            plugin.debug("PlaceholderAPI 解析失败 " + params + ": " + t.getMessage());
            return null;
        }
    }

    private Component parseMeta(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (miniMessageMeta) {
            return TextUtil.parse(raw);
        }
        return TextUtil.legacy(raw);
    }
}
