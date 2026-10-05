package com.junxiex.mikuchat.core.module;

import com.junxiex.mikuchat.MikuChat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块注册与生命周期管理器。
 */
public final class ModuleManager {

    private final MikuChat plugin;
    private final Map<String, ChatModule> modules = new LinkedHashMap<>();
    // Folia 下不同区域线程可能同时触发重载，这里必须是并发集合
    private final Set<String> enabled = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ModuleManager(MikuChat plugin) {
        this.plugin = plugin;
    }

    public void register(ChatModule module) {
        if (modules.putIfAbsent(module.id(), module) != null) {
            plugin.getLogger().warning("Duplicate module id: " + module.id());
        }
    }

    public void enableAll() {
        for (ChatModule module : modules.values()) {
            enable(module);
        }
    }

    public void disableAll() {
        List<ChatModule> list = new ArrayList<>(modules.values());
        for (int i = list.size() - 1; i >= 0; i--) {
            disable(list.get(i));
        }
    }

    public boolean enable(String id) {
        ChatModule module = modules.get(id);
        if (module == null) {
            return false;
        }
        enable(module);
        return enabled.contains(id);
    }

    public boolean disable(String id) {
        ChatModule module = modules.get(id);
        if (module == null) {
            return false;
        }
        disable(module);
        return true;
    }

    public boolean reload(String id) {
        ChatModule module = modules.get(id);
        if (module == null) {
            return false;
        }
        try {
            module.reload();
            plugin.getLogger().info("Reloaded module: " + id);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload module " + id + ": " + e.getMessage());
            return false;
        }
    }

    public int reloadAll() {
        int count = 0;
        for (ChatModule module : modules.values()) {
            try {
                module.reload();
                count++;
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to reload module " + module.id() + ": " + e.getMessage());
            }
        }
        return count;
    }

    private void enable(ChatModule module) {
        if (enabled.contains(module.id())) {
            return;
        }
        try {
            module.enable();
            enabled.add(module.id());
            plugin.getLogger().info("Enabled module: " + module.id());
        } catch (Throwable t) {
            plugin.getLogger().severe("Failed to enable module " + module.id() + ": " + t.getMessage());
        }
    }

    private void disable(ChatModule module) {
        if (!enabled.remove(module.id())) {
            return;
        }
        try {
            module.disable();
        } catch (Throwable t) {
            plugin.getLogger().severe("Failed to disable module " + module.id() + ": " + t.getMessage());
        }
    }

    public Set<String> ids() {
        return modules.keySet();
    }
}
