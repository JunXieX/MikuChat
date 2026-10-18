package com.junxiex.mikuchat.module.antispam;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 单个玩家的刷屏检测状态，退出即清理。
 * <p>
 * 同一玩家的检测可能来自不同线程：公开聊天在主线程/区域线程，而 /msg 私聊的发送入口
 * 可能在异步或全局线程触发。字段与队列都不是线程安全的，因此这里的读写统一加锁，
 * 避免令牌桶计数错乱与 {@link java.util.ConcurrentModificationException}。
 */
final class PlayerSpamState {

    private long lastMessageAt;
    private double tokens;
    private long lastRefillNanos = System.nanoTime();
    /** 令牌桶是否已初始化。用独立标志而不是「tokens==0 && lastMessageAt==0」推断，
     *  否则令牌恰好被扣到 0 时会被误判为首次发言并重新补满，形成免费额度。 */
    private boolean initialized;
    private final Deque<String> history = new ArrayDeque<>();

    synchronized boolean initialized() {
        return initialized;
    }

    synchronized void initialized(boolean value) {
        this.initialized = value;
    }

    synchronized long lastMessageAt() {
        return lastMessageAt;
    }

    synchronized void lastMessageAt(long value) {
        this.lastMessageAt = value;
    }

    synchronized double tokens() {
        return tokens;
    }

    synchronized void tokens(double value) {
        this.tokens = value;
    }

    synchronized long lastRefillNanos() {
        return lastRefillNanos;
    }

    synchronized void lastRefillNanos(long value) {
        this.lastRefillNanos = value;
    }

    /** 返回历史快照，调用方遍历期间不会被并发修改。 */
    synchronized List<String> history() {
        return new ArrayList<>(history);
    }

    synchronized void pushHistory(String message, int maxSize) {
        history.addLast(message);
        while (history.size() > maxSize) {
            history.pollFirst();
        }
    }
}
