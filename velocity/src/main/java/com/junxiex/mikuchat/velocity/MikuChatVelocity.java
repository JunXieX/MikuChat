package com.junxiex.mikuchat.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * MikuChat —— Velocity 端跨服支撑插件。
 *
 * <p>职责：</p>
 * <ul>
 *   <li><b>跨服转发</b>：把某台后端发来的消息转发给其余后端（不再扇出给消息来源自身）。</li>
 *   <li><b>跨服在线查询</b>：代理端掌握全量玩家所在服务器，直接查表应答，无需广播与超时收敛。</li>
 *   <li><b>共享状态</b>：保管禁言等跨服状态并落盘，后端启动或重载后主动拉取全量快照。</li>
 * </ul>
 *
 * @author JunXieX
 */
@Plugin(
        id = "mikuchat",
        name = "MikuChat",
        version = "1.2.0",
        description = "Velocity 端跨服转发与共享状态保管（配合后端 MikuChat）",
        authors = {"JunXieX"}
)
public final class MikuChatVelocity {

    /** 与后端 network.yml 的 channel 一致。 */
    public static final String CHANNEL = "mikuchat:proxy";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private SharedStore store;

    @Inject
    public MikuChatVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path injectedDataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        // @DataDirectory 注入的是 plugins/<插件id>（全小写），这里改用插件显示名，
        // 与其它 Miku* 代理端插件的目录约定保持一致。
        this.dataDirectory = injectedDataDirectory.getParent().resolve("MikuChat");
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(CHANNEL);
        proxy.getChannelRegistrar().register(channel);

        this.store = new SharedStore(dataDirectory, logger);
        this.store.load();

        proxy.getEventManager().register(this, new ProxyBridge(proxy, logger, channel, store));
        logger.info("MikuChat (Velocity) 已启用，跨服通道 {} 就绪。", CHANNEL);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (store != null) {
            store.flush();
        }
        logger.info("MikuChat (Velocity) 已停用。");
    }
}
