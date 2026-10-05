package com.junxiex.mikuchat.core.service;

import org.bukkit.entity.Player;

/**
 * 过滤服务：屏蔽词 + 广告/链接检测。
 */
public interface FilterService {

    Result apply(Player sender, ChannelView channel, String text);

    /**
     * 过滤结果。
     *
     * @param text          过滤后的文本
     * @param modified      是否发生了替换
     * @param notified      是否应提示发送者（由策略配置决定）
     * @param blocked       是否整条拦截
     * @param advertisement 是否命中广告规则
     */
    record Result(String text, boolean modified, boolean notified, boolean blocked, boolean advertisement) {
    }
}
