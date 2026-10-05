package com.junxiex.mikuchat.core.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家级冷却管理。key 用于区分不同功能（如 chat / display / mention）。
 */
public final class Cooldowns {

    private final Map<UUID, Map<String, Long>> lastUse = new ConcurrentHashMap<>();

    /**
     * 检查并消费冷却。
     *
     * @param uuid       玩家
     * @param key        功能键
     * @param cooldownMs 冷却毫秒，&lt;=0 表示无冷却
     * @return 剩余毫秒，0 表示已通过
     */
    public long check(UUID uuid, String key, long cooldownMs) {
        if (cooldownMs <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        Map<String, Long> map = lastUse.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        Long last = map.get(key);
        if (last != null) {
            long remaining = cooldownMs - (now - last);
            if (remaining > 0) {
                return remaining;
            }
        }
        map.put(key, now);
        return 0;
    }

    /** 玩家退出时清理，避免 map 无界增长。 */
    public void clear(UUID uuid) {
        lastUse.remove(uuid);
    }
}
