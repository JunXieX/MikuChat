package com.junxiex.mikuchat.core.service;

/**
 * 频道作用域。
 */
public enum ChannelScope {
    /** 仅本服广播。 */
    SERVER,
    /** 经 Velocity 代理端转发到所有服务器。 */
    NETWORK
}
