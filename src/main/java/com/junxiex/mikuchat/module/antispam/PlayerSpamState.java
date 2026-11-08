package com.junxiex.mikuchat.module.antispam;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 单个玩家的刷屏检测状态，退出即清理。
 * 所有读写都发生在 {@link AntiSpamManager#check} 对 state 的监视器内，因此本类不加锁。
 */
final class PlayerSpamState {

    long lastMessageAt;
    double tokens;
    long lastRefillNanos = System.nanoTime();
    /** 用独立标志而不是「tokens==0」推断首次发言，否则令牌扣到 0 时会被重新补满。 */
    boolean initialized;
    final Deque<String> history = new ArrayDeque<>();

    void pushHistory(String message, int maxSize) {
        history.addLast(message);
        while (history.size() > maxSize) {
            history.pollFirst();
        }
    }
}
