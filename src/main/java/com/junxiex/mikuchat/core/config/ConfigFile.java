package com.junxiex.mikuchat.core.config;

import com.junxiex.mikuchat.MikuChat;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 单个配置文件封装：首次运行从 jar 内释放默认文件；已存在时不覆盖（保留用户注释），
 * 通过 setDefaults 让新增键自动回落到默认值。
 */
public final class ConfigFile {

    private final MikuChat plugin;
    private final String resourcePath;
    private final File file;
    private volatile YamlConfiguration config = new YamlConfiguration();

    public ConfigFile(MikuChat plugin, String resourcePath) {
        this.plugin = plugin;
        this.resourcePath = resourcePath;
        this.file = new File(plugin.getDataFolder(), resourcePath);
        load();
    }

    public synchronized void load() {
        ensureExists();
        YamlConfiguration loaded;
        try {
            loaded = YamlConfiguration.loadConfiguration(file);
        } catch (Throwable t) {
            // 配置语法错误时不要抛出：保留上一次的有效配置，避免整个模块被禁用
            plugin.getLogger().severe("配置文件 " + resourcePath + " 解析失败，已沿用上一次的有效配置: " + t.getMessage());
            return;
        }
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                loaded.setDefaults(defaults);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read default resource " + resourcePath + ": " + e.getMessage());
        }
        this.config = loaded;
    }

    private void ensureExists() {
        if (file.exists()) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Failed to create directory " + parent.getAbsolutePath());
        }
        try {
            plugin.saveResource(resourcePath, false);
        } catch (IllegalArgumentException e) {
            try {
                if (!file.createNewFile()) {
                    plugin.getLogger().warning("Failed to create empty config " + resourcePath);
                }
            } catch (Exception ignored) {
                plugin.getLogger().warning("Failed to create empty config " + resourcePath);
            }
        }
    }

    public YamlConfiguration config() {
        return config;
    }
}
