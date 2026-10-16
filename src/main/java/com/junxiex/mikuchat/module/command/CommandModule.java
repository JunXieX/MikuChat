package com.junxiex.mikuchat.module.command;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.module.ChatModule;

import java.util.List;

/**
 * 命令模块：登记 /mchat 与 /mute、/unmute。
 * <p>
 * Paper 插件不支持在描述符里声明命令，实际注册由 {@code CommandRegistrar}
 * 在 COMMANDS 生命周期事件中通过 Brigadier 完成。
 */
public final class CommandModule implements ChatModule {

    private final MikuChat plugin;

    public CommandModule(MikuChat plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "command";
    }

    @Override
    public void enable() {
        plugin.commands().register("mchat", "MikuChat 主命令", List.of(), new MikuChatCommand(plugin));
        plugin.commands().register("mute", "禁言玩家", List.of(), new MuteCommand(plugin, "mute"));
        plugin.commands().register("unmute", "解除禁言", List.of(), new MuteCommand(plugin, "unmute"));
    }

    @Override
    public void disable() {
        // 命令随插件卸载自动清理
    }

    /**
     * 命令没有可重载的配置。
     * <p>
     * 默认实现会 disable → enable，而 enable 只是把命令登记进 {@code CommandRegistrar} 的待注册队列；
     * 该队列仅在插件启用时挂载一次生命周期处理器，重载时并不会再次挂载，因此重新登记只会让队列
     * 无意义地增长而不会真正注册（Brigadier 命令由服务端持有，随插件卸载统一清理）。这里显式置空。
     */
    @Override
    public void reload() {
        // 无需处理
    }
}
