package com.junxiex.mikuchat.module.integration;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.text.MetaBridge;
import com.junxiex.mikuchat.core.text.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * LuckPerms 与 PlaceholderAPI 的软依赖接入。插件缺失时自动降级为默认值，不影响主流程。
 */
public final class Integrations implements MetaBridge {

    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    private final MikuChat plugin;
    private final ConfigFile config;

    // 由命令线程在 /mchat reload 时写入、由聊天线程读取，必须 volatile 保证可见性
    private volatile LuckPerms luckPerms;
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
                try {
                    this.luckPerms = LuckPermsProvider.get();
                } catch (Throwable t) {
                    plugin.getLogger().warning("LuckPerms 已安装但 API 不可用: " + t.getMessage());
                }
            }
        }

        boolean papiConfigured = config.config().getBoolean("placeholderapi.enabled", true);
        Plugin papiPlugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        this.placeholderApi = papiConfigured && papiPlugin != null && papiPlugin.isEnabled();

        plugin.getLogger().info("Integrations -> LuckPerms: " + hasLuckPerms() + ", PlaceholderAPI: " + placeholderApi);
    }

    public void unload() {
        this.luckPerms = null;
        this.placeholderApi = false;
    }

    @Override
    public boolean hasLuckPerms() {
        return luckPerms != null;
    }

    @Override
    public boolean hasPlaceholderApi() {
        return placeholderApi;
    }

    @Override
    public Component prefix(Player player) {
        String raw = meta(player, CachedMetaData::getPrefix);
        if (raw == null || raw.isEmpty()) {
            raw = config.config().getString("luckperms.prefix-default", "");
        }
        return parseMeta(raw);
    }

    @Override
    public Component suffix(Player player) {
        String raw = meta(player, CachedMetaData::getSuffix);
        if (raw == null || raw.isEmpty()) {
            raw = config.config().getString("luckperms.suffix-default", "");
        }
        return parseMeta(raw);
    }

    @Override
    public String group(Player player) {
        if (player != null && luckPerms != null) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                String group = user.getPrimaryGroup();
                if (group != null && !group.isEmpty()) {
                    return group;
                }
            }
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

    private String meta(Player player, java.util.function.Function<CachedMetaData, String> getter) {
        if (player == null || luckPerms == null) {
            return null;
        }
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }
        CachedMetaData metaData = user.getCachedData().getMetaData();
        return getter.apply(metaData);
    }

    private Component parseMeta(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (miniMessageMeta) {
            return TextUtil.parse(raw);
        }
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.indexOf('§') >= 0) {
            return LEGACY_SECTION.deserialize(raw);
        }
        return LEGACY_AMP.deserialize(raw);
    }
}
