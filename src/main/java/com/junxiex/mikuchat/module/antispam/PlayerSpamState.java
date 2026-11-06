package com.junxiex.mikuchat.module.antispam;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 单个玩家的刷屏检测状态，退出即清理。
 * 所有读写都发生在 {@link AntiSpamManager#check} 对 state 的监视器内，因此本类不再单独加锁。
 */
final class PlayerSpamState {

    private long lastMessageAt;
    private double tokens;
    private long lastRefillNanos = System.nanoTime();
    /** 令牌桶是否已初始化。用独立标志而不是「tokens==0 && lastMessageAt==0」推断，
     *  否则令牌恰好被扣到 0 时会被误判为首次发言并重新补满，形成免费额度。 */
    private boolean initialized;
    private final Deque<String> history = new ArrayDeque<>();

    boolean initialized() {
        return initialized;
    }

    void initialized(boolean value) {
        this.initialized = value;
    }

    long lastMessageAt() {
        return lastMessageAt;
    }

    void lastMessageAt(long value) {
        this.lastMessageAt = value;
    }

    double tokens() {
        return tokens;
    }

    void tokens(double value) {
        this.tokens = value;
    }

    long lastRefillNanos() {
        return lastRefillNanos;
    }

    void lastRefillNanos(long value) {
        this.lastRefillNanos = value;
    }

    Iterable<String> history() {
        return history;
    }

    void pushHistory(String message, int maxSize) {
        history.addLast(message);
        while (history.size() > maxSize) {
            history.pollFirst();
        }
    }
}
