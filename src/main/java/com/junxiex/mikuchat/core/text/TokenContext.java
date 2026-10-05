package com.junxiex.mikuchat.core.text;

import com.junxiex.mikuchat.core.service.ChannelView;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Function;

/**
 * 消息标记处理上下文。
 *
 * @param sender       发送者
 * @param channel      当前频道
 * @param rawText      过滤后的完整原文
 * @param textRenderer 普通文本渲染器（负责样式权限净化 + MiniMessage 解析）
 * @param mentions     被 @ 的玩家名收集器，由 @ 处理器写入；"*" 表示 @all
 */
public record TokenContext(Player sender,
                           ChannelView channel,
                           String rawText,
                           Function<String, Component> textRenderer,
                           List<String> mentions) {
}
