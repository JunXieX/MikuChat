package com.junxiex.mikuchat.core.config;

import com.junxiex.mikuchat.MikuChat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多配置文件管理器。每个功能模块单独一份配置文件，互不混杂，支持整体或单文件重载。
 */
public final class ConfigManager {

    private final MikuChat plugin;
    private final Map<String, ConfigFile> files = new ConcurrentHashMap<>();

    public ConfigManager(MikuChat plugin) {
        this.plugin = plugin;
    }

    /** 按相对路径获取（或惰性创建）配置文件，例如 "format.yml"。 */
    public ConfigFile get(String path) {
        return files.computeIfAbsent(path, p -> new ConfigFile(plugin, p));
    }

    public void preload(String... paths) {
        for (String path : paths) {
            get(path);
        }
    }

    public void reloadAll() {
        for (ConfigFile file : files.values()) {
            file.load();
        }
    }

    public boolean reload(String path) {
        ConfigFile file = files.get(path);
        if (file == null) {
            return false;
        }
        file.load();
        return true;
    }
}
