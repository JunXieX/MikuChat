package com.junxiex.mikuchat.core.config;

import com.junxiex.mikuchat.MikuChat;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
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
            // 不能再用 YamlConfiguration.loadConfiguration(File)：它内部吞掉解析异常并返回空配置，
            // 这里改为显式 load(file)，让语法错误走失败分支
            loaded = new YamlConfiguration();
            loaded.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            // 配置语法错误时不要抛出：保留上一次的有效配置，避免整个模块被禁用
            plugin.getLogger().severe("配置文件 " + resourcePath + " 解析失败，已保留上一次的有效配置"
                    + "（首次加载时改用内置默认值）: " + e.getMessage());
            if (this.config.getKeys(false).isEmpty()) {
                // 首次加载就损坏（字段初始值是空配置）：至少应用 jar 内置默认值，
                // 避免模块以「全空配置」运行；重载场景下 this.config 已有旧值，不受影响。
                YamlConfiguration fallback = new YamlConfiguration();
                applyDefaults(fallback);
                this.config = fallback;
            }
            return;
        }
        applyDefaults(loaded);
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

    /** 把 jar 内置的同名资源设为默认值（新增配置键自动回落，不覆盖用户已有内容）。 */
    private void applyDefaults(YamlConfiguration target) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                target.setDefaults(defaults);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read default resource " + resourcePath + ": " + e.getMessage());
        }
    }

    public YamlConfiguration config() {
        return config;
    }
}
