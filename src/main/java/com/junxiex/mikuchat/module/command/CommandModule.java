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
}
