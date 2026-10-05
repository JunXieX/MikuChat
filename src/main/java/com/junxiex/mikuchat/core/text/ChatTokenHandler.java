package com.junxiex.mikuchat.core.text;

import net.kyori.adventure.text.Component;

import java.util.regex.Pattern;

/**
 * 聊天消息中的功能标记处理器（如 [item]、[inv]、@玩家）。
 * 各功能模块自行注册，聊天管线统一编排。
 */
public interface ChatTokenHandler {

    /** 匹配模式。 */
    Pattern pattern();

    /**
     * 渲染匹配到的标记。
     * <p>
     * 传入的是「本处理器自己匹配到的文本」，而不是组合正则的匹配器——
     * 处理器如需捕获组，请用 {@link #pattern()} 自行重新匹配，
     * 这样就不会依赖其它处理器的注册顺序与捕获组编号。
     *
     * @return 渲染结果；返回 null 表示放弃处理，保留原文
     */
    Component render(TokenContext context, String matched);
}
