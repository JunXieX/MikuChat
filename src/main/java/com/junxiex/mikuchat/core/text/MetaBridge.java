package com.junxiex.mikuchat.core.text;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * 外部权限/变量系统（LuckPerms、PlaceholderAPI）的桥接接口，
 * 供文本模板渲染使用，使 core 不直接依赖具体插件。
 */
public interface MetaBridge {

    Component prefix(Player player);

    Component suffix(Player player);

    String group(Player player);

    /** 解析单个 PlaceholderAPI 表达式（不含两侧 %），返回原始文本。 */
    String placeholder(Player player, String params);

    boolean hasLuckPerms();

    boolean hasPlaceholderApi();
}
