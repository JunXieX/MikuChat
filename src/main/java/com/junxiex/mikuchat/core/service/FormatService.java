package com.junxiex.mikuchat.core.service;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * 格式服务：把频道消息正文渲染成完整的聊天行。
 */
public interface FormatService {

    Component render(Player sender, ChannelView channel, Component body);
}
