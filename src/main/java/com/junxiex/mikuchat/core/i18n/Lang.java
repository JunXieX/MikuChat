package com.junxiex.mikuchat.core.i18n;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.text.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 多语言文案。文案使用 MiniMessage 语法，占位符使用 {name} 形式。
 */
public final class Lang {

    private final MikuChat plugin;
    private volatile YamlConfiguration messages = new YamlConfiguration();
    private volatile String code = "en_US";

    public Lang(MikuChat plugin) {
        this.plugin = plugin;
    }

    public void load(String code) {
        this.code = code;
        String path = "lang/" + code + ".yml";
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            try {
                plugin.saveResource(path, false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Missing language resource " + path);
            }
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource(path)) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read language defaults " + path + ": " + e.getMessage());
        }
        this.messages = loaded;
    }

    public String code() {
        return code;
    }

    public String raw(String key) {
        String value = messages.getString(key);
        return value == null ? key : value;
    }

    /** 占位符值按原文替换（不做转义）。仅用于 channel.list-line 这类值本身即 MiniMessage 的配置场景。 */
    public String raw(String key, Map<String, String> placeholders) {
        String value = raw(key);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                value = value.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return value;
    }

    /**
     * 取文案并替换占位符。占位符值会被 {@link TextUtil#escape} 转义，
     * 防止玩家输入（玩家名、禁言原因、检索关键词等）注入 MiniMessage 标签。
     */
    public Component get(String key, String... placeholders) {
        String value = raw(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("{" + placeholders[i] + "}", TextUtil.escape(placeholders[i + 1]));
        }
        return TextUtil.parse(value);
    }

    /** 占位符值按 MiniMessage 原样注入。仅用于值来自配置文件的可信场景（如频道显示名）。 */
    public Component getParsed(String key, String... placeholders) {
        String value = raw(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("{" + placeholders[i] + "}", placeholders[i + 1] == null ? "" : placeholders[i + 1]);
        }
        return TextUtil.parse(value);
    }

    public Component get(String key, Map<String, String> placeholders) {
        return TextUtil.parse(raw(key, placeholders));
    }

    /** 直接解析一段 MiniMessage 文案（用于配置中内联的格式）。 */
    public Component parse(String miniMessage) {
        return TextUtil.parse(miniMessage);
    }
}
