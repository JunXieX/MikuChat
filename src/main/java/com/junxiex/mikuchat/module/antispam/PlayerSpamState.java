package com.junxiex.mikuchat.module.antispam;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 单个玩家的刷屏检测状态，退出即清理。
 */
final class PlayerSpamState {

    private long lastMessageAt;
    private double tokens;
    private long lastRefillNanos = System.nanoTime();
    private final Deque<String> history = new ArrayDeque<>();

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

    Deque<String> history() {
        return history;
    }

    void pushHistory(String message, int maxSize) {
        history.addLast(message);
        while (history.size() > maxSize) {
            history.pollFirst();
        }
    }
}
