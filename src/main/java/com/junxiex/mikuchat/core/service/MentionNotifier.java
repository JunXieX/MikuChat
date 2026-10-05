package com.junxiex.mikuchat.core.service;

import java.util.List;
import java.util.UUID;

/**
 * @ 提醒服务：向被 @ 的在线玩家发送提示（音效 + ActionBar）。跨服消息在接收端也会调用。
 */
public interface MentionNotifier {

    /**
     * @param senderId       发送者 UUID（用于跳过自己）
     * @param mentioned      被 @ 的玩家名；"*" 表示全体
     * @param channelDisplay 频道显示名（可空）
     */
    void notifyMentioned(UUID senderId, List<String> mentioned, String channelDisplay);
}
