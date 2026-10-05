package com.junxiex.mikuchat.core.service;

/**
 * 频道视图：供聊天管线读取的频道只读信息。
 */
public interface ChannelView {

    String id();

    /** 用于消息提示的显示名（MiniMessage 模板）。 */
    String displayName();

    ChannelScope scope();

    /** 使用该频道所需权限，null 表示无限制。 */
    String permission();

    /** 引用的格式规则 id，null 表示按默认匹配。 */
    String formatId();

    /** 引用的防刷屏策略 id，null 表示默认策略。 */
    String antispamProfile();

    /** 引用的过滤策略 id，null 表示默认策略。 */
    String filterProfile();

    /** 是否写入聊天留档。 */
    boolean log();

    /** 是否存在（配置被删除后为 false）。 */
    boolean valid();
}
