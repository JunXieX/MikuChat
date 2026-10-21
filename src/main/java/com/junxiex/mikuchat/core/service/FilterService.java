package com.junxiex.mikuchat.core.service;

import org.bukkit.entity.Player;

/**
 * 过滤服务：屏蔽词 + 广告/链接检测。
 */
public interface FilterService {

    Result apply(Player sender, ChannelView channel, String text);

    /**
     * 可见文本二次校验：只判断是否应拦截，不做任何替换、无副作用。
     * <p>
     * 主过滤（{@link #apply}）作用在玩家的原始输入上；若玩家用 MiniMessage 标签把敏感词
     * 拆开（如 {@code 广<red>告</red>}），原始输入里夹着标签，归一化后不是「广告」，
     * 自动机不会命中；但渲染后玩家看到的仍是「广告」。因此调用方应在渲染出正文后，
     * 用玩家实际看到的纯文本再调一次本方法。
     *
     * @param sender      发送者，可为 null
     * @param channel     频道，可为 null（按默认策略判定）
     * @param visibleText 渲染后玩家实际看到的纯文本
     * @return true 表示应整条拦截
     */
    boolean blockedByVisibleText(Player sender, ChannelView channel, String visibleText);

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
