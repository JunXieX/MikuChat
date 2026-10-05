package com.junxiex.mikuchat.core.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 命令注册器：模块在 {@code enable()} 阶段登记命令，插件启用完成后统一通过 Paper 的
 * {@link LifecycleEvents#COMMANDS} 生命周期事件以 Brigadier 方式注册。
 * <p>
 * Paper 插件不再使用描述符的 {@code commands} 字段，命令只能由 Brigadier 注册。
 */
public final class CommandRegistrar {

    private record Pending(String name, String description, List<String> aliases, BasicCommand command) {
    }

    private final List<Pending> pending = new ArrayList<>();

    /** 由模块在启用阶段调用，只登记不注册。 */
    public void register(String name, String description, List<String> aliases, BasicCommand command) {
        pending.add(new Pending(name, description, List.copyOf(aliases), command));
    }

    /** 必须在所有模块启用完成后调用一次；注册时机由服务器决定，晚于 onEnable。 */
    public void attach(LifecycleEventManager<Plugin> lifecycle) {
        List<Pending> entries = List.copyOf(pending);
        pending.clear();
        lifecycle.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            for (Pending entry : entries) {
                commands.register(entry.name(), entry.description(), entry.aliases(), entry.command());
            }
        });
    }
}
