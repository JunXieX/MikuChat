package com.junxiex.mikuchat.module.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

/**
 * LuckPerms 访问隔离层：只有安装并启用 LuckPerms 时才会加载本类，
 * 避免未安装时 Integrations 链接到 LuckPerms 类型导致 NoClassDefFoundError。
 */
final class LuckPermsHook {

    private final LuckPerms luckPerms;

    private LuckPermsHook(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    static LuckPermsHook create() {
        try {
            return new LuckPermsHook(LuckPermsProvider.get());
        } catch (Throwable t) {
            return null;
        }
    }

    String prefix(Player player) {
        return meta(player, true);
    }

    String suffix(Player player) {
        return meta(player, false);
    }

    String group(Player player) {
        User user = user(player);
        return user == null ? null : user.getPrimaryGroup();
    }

    private String meta(Player player, boolean prefix) {
        User user = user(player);
        if (user == null) {
            return null;
        }
        CachedMetaData metaData = user.getCachedData().getMetaData();
        return prefix ? metaData.getPrefix() : metaData.getSuffix();
    }

    private User user(Player player) {
        return player == null ? null : luckPerms.getUserManager().getUser(player.getUniqueId());
    }
}
