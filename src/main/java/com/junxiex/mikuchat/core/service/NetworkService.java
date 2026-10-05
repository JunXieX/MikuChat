package com.junxiex.mikuchat.core.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 跨服网络服务：后端插件通过插件消息通道与 Velocity 代理端插件通信，
 * 由代理完成消息转发、跨服在线查询与共享状态（禁言）保管。
 * <p>
 * 各功能模块通过 {@link #publish}/{@link #subscribe} 使用统一消息总线，消息体自带格式，互不影响。
 */
public interface NetworkService {

    boolean available();

    String serverId();

    /** 发布一条消息；payload 由 Gson 序列化。返回是否成功。 */
    boolean publish(String type, Object payload);

    /** 订阅消息类型，handler 参数为（来源服务器 id, JSON 消息体）。 */
    void subscribe(String type, BiConsumer<String, String> handler);

    void unsubscribe(String type, BiConsumer<String, String> handler);

    /** 查询跨服在线玩家，异步回调。代理端直接查表应答，无需广播等待。 */
    void queryPresence(Consumer<Presence> callback);

    /** 请求代理端下发一次全量共享状态（{@code STATE_SYNC}），用于启动或重载后同步禁言等状态。 */
    void requestState();

    // ---- 共享键值存储：由代理端持有并落盘，用于禁言等需要跨服持久化的状态 ----

    void hashSet(String key, String field, String value);

    void hashDelete(String key, String field);

    /** 跨服在线快照：server-id -> 玩家名列表（不含本服）。 */
    record Presence(Map<String, List<String>> playersByServer) {

        public int total() {
            return playersByServer.values().stream().mapToInt(List::size).sum();
        }
    }

    /**
     * 跨服聊天广播包。lineJson 为渲染完成的整行组件（Adventure Gson），
     * 接收端直接展示，保证各服风格一致且不依赖远端的权限/变量数据。
     */
    record ChatOutbound(UUID senderId,
                        String senderName,
                        String serverId,
                        String channelId,
                        String channelDisplay,
                        String rawText,
                        String lineJson,
                        List<String> mentions,
                        long timestamp) {
    }
}
