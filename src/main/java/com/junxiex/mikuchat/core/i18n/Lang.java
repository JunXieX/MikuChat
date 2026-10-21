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

    /** 解析失败告警的去重上限，避免同一条错误文案反复刷控制台。 */
    private static final int MAX_REPORTED_FAILURES = 32;

    private final MikuChat plugin;
    private volatile YamlConfiguration messages = new YamlConfiguration();
    private volatile String code = "en_US";
    /** 已报告过的解析失败（按文案原文去重）。 */
    private final java.util.Set<String> reportedFailures = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public Lang(MikuChat plugin) {
        this.plugin = plugin;
    }

    public void load(String code) {
        String requested = code == null || code.isBlank() ? "zh_CN" : code;
        String path = "lang/" + requested + ".yml";
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            try {
                plugin.saveResource(path, false);
            } catch (IllegalArgumentException e) {
                // 配置了不存在的语言：直接回落到 zh_CN，否则所有提示都会退化成原始 key
                plugin.getLogger().warning("语言文件 " + path + " 不存在，已回落到 zh_CN。请在 config.yml 的 language 中使用已内置的语言代码。");
                requested = "zh_CN";
                path = "lang/zh_CN.yml";
                file = new File(plugin.getDataFolder(), path);
                if (!file.exists()) {
                    try {
                        plugin.saveResource(path, false);
                    } catch (IllegalArgumentException ignored) {
                        plugin.getLogger().warning("缺少内置语言资源 " + path);
                    }
                }
            }
        }
        this.code = requested;
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
        return parseSafe(value);
    }

    /** 占位符值按 MiniMessage 原样注入。仅用于值来自配置文件的可信场景（如频道显示名）。 */
    public Component getParsed(String key, String... placeholders) {
        String value = raw(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("{" + placeholders[i] + "}", placeholders[i + 1] == null ? "" : placeholders[i + 1]);
        }
        return parseSafe(value);
    }

    public Component get(String key, Map<String, String> placeholders) {
        return parseSafe(raw(key, placeholders));
    }

    /** 直接解析一段 MiniMessage 文案（用于配置中内联的格式）。 */
    public Component parse(String miniMessage) {
        return parseSafe(miniMessage);
    }

    /**
     * 安全解析 MiniMessage：lang 文案写错（标签拼错、色值非法）时不能让命令或聊天直接抛异常。
     * <p>
     * 解析失败时退化为纯文本并限频告警，保证玩家至少能看到原始文案，而不是什么都收不到。
     */
    private Component parseSafe(String value) {
        String text = value == null ? "" : value;
        try {
            return TextUtil.parse(text);
        } catch (RuntimeException e) {
            if (reportedFailures.size() < MAX_REPORTED_FAILURES && reportedFailures.add(text)) {
                plugin.getLogger().warning("语言文案不是合法的 MiniMessage，已按纯文本显示: " + e.getMessage());
            }
            return Component.text(text);
        }
    }
}
