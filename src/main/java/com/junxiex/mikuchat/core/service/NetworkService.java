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

    /** 插件消息通道是否已注册（不代表代理端插件一定在线）。 */
    boolean available();

    /**
     * 是否已与代理端完成过至少一次通信。
     * <p>
     * 通道注册在任意 Paper 服务端都会成功，单机服也会有 {@link #available()} 为 true，
     * 因此「跨服是否真正可用」必须另以此判定，供降级策略与 /mchat info 使用。
     */
    boolean connected();

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
     * <p>
     * 不携带频道显示名：显示名是 MiniMessage 模板，若由远端提供并解析，等于给了对方
     * 向本服注入任意组件的通道。接收端改为用本服配置里同 id 频道的显示名。
     */
    record ChatOutbound(UUID senderId,
                        String senderName,
                        String serverId,
                        String channelId,
                        String rawText,
                        String lineJson,
                        List<String> mentions,
                        long timestamp) {
    }
}
