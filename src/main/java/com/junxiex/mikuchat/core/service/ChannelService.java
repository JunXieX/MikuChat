package com.junxiex.mikuchat.core.service;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * 频道服务：管理频道定义与玩家当前频道选择。
 */
public interface ChannelService {

    ChannelView byId(String id);

    ChannelView defaultChannel();

    Collection<ChannelView> channels();

    /** 玩家当前所在频道，未选择或频道已删除时回落默认频道。 */
    ChannelView selected(Player player);

    void select(Player player, ChannelView channel);

    boolean mayUse(Player player, ChannelView channel);

    void sendChannelList(CommandSender sender, Player player);
}
