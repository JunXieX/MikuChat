package com.junxiex.mikuchat.core.service;

import org.bukkit.entity.Player;

/**
 * 防刷屏服务：冷却 / 高频 / 相似度 / 句内重复四重检测。
 */
public interface AntiSpamService {

    Result check(Player sender, ChannelView channel, String text);

    void clear(Player player);

    /** 把命中结果转换为提示文案，公开聊天与私聊共用，避免两处重复实现。 */
    default net.kyori.adventure.text.Component feedback(com.junxiex.mikuchat.core.i18n.Lang lang, Result result) {
        if (result.type() == null) {
            return lang.get("antispam.ignored");
        }
        return switch (result.type()) {
            case COOLDOWN -> lang.get("antispam.cooldown",
                    "seconds", String.format(java.util.Locale.ROOT, "%.1f", result.extra() / 1000.0));
            case THROTTLE -> lang.get("antispam.throttle");
            case SIMILARITY -> lang.get("antispam.similar");
            case REPETITION -> lang.get("antispam.repeat");
        };
    }

    enum Type {
        COOLDOWN, THROTTLE, SIMILARITY, REPETITION
    }

    /**
     * @param blocked 是否拦截
     * @param silent  是否静默丢弃（不提示发送者）
     * @param type    命中的类型
     * @param extra   附加数据（如冷却剩余毫秒）
     */
    record Result(boolean blocked, boolean silent, Type type, long extra) {

        public static final Result PASS = new Result(false, false, null, 0);
    }
}
