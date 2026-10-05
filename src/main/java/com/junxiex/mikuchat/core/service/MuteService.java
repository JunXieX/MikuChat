package com.junxiex.mikuchat.core.service;

import java.util.UUID;

/**
 * 禁言服务。
 */
public interface MuteService {

    /** 返回禁言信息，未禁言返回 null。 */
    MuteInfo muteOf(UUID uuid);

    default boolean isMuted(UUID uuid) {
        return muteOf(uuid) != null;
    }

    String formatRemaining(long millis);

    /**
     * 禁言记录。
     *
     * @param reason   原因
     * @param expireAt 到期时间戳（毫秒），{@code <= 0} 表示永久
     */
    record MuteInfo(String reason, long expireAt) {

        public boolean permanent() {
            return expireAt <= 0;
        }

        public boolean expired() {
            return !permanent() && System.currentTimeMillis() >= expireAt;
        }
    }
}
