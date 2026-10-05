package com.junxiex.mikuchat.core.service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 聊天留档服务：把公开聊天写入按天分割的文件，供 /mchat log 查询。
 */
public interface ChatLogService {

    /**
     * @param channelId  频道 id
     * @param senderName 发送者名
     * @param message    纯文本内容
     * @param remote     是否来自其它服务器
     */
    void record(String channelId, String senderName, String message, boolean remote);

    /**
     * 关键词检索（玩家名或内容）。异步执行，callback 在异步线程回调，结果按时间倒序。
     */
    void search(String keyword, int limit, Consumer<List<String>> callback);
}
