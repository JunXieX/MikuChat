package com.junxiex.mikuchat.module.style;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.text.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 样式权限净化：默认把玩家文本整体转义，再按权限白名单逐类放行 MiniMessage 标签，
 * 杜绝越权样式与点击/悬停注入。
 */
public final class StyleFilter {

    /**
     * 标签识别。显式排除换行：{@code [^<>]*} 会跨越换行，使
     * {@code <red>第一行} 与后续某行的 {@code >} 被当成同一个「标签」，
     * 从而把中间的正常文本整段吞进判定、破坏转义结果。
     */
    private static final Pattern TAG = Pattern.compile("<([^<>\r\n]*)>");

    private static final Set<String> NAMED_COLORS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white");

    private static final Set<String> DECORATIONS = Set.of(
            "bold", "b", "italic", "i", "underlined", "u", "strikethrough", "st", "obfuscated", "obf");

    private static final String[] PERMISSION_KEYS = {"color-named", "color-hex", "decoration", "advanced", "minimessage"};

    private final ConfigFile config;
    private final MikuChat plugin;
    /** 已报告过的解析失败原因，避免同一种畸形标签反复刷日志。 */
    private final java.util.Set<String> reportedFailures = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // 权限串与开关在 load 时解析一次，避免每条消息反复查配置
    private volatile boolean escapeByDefault = true;
    private volatile java.util.Map<String, String> permissions = java.util.Map.of();

    public StyleFilter(MikuChat plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().get("style.yml");
        loadSettings();
    }

    private void loadSettings() {
        this.escapeByDefault = config.config().getBoolean("escape-by-default", true);
        java.util.Map<String, String> loaded = new java.util.HashMap<>();
        for (String key : PERMISSION_KEYS) {
            loaded.put(key, config.config().getString("permissions." + key, "mikuchat.chat." + key));
        }
        this.permissions = loaded;
    }

    public Component render(String text, Player player) {
        String sanitized = sanitize(text, player);
        try {
            return TextUtil.mini().deserialize(sanitized);
        } catch (RuntimeException e) {
            // 兜底：即便有权限的玩家写出了 MiniMessage 无法解析的标签（如非法参数），
            // 也不能让整条消息因异常丢失——退化为纯文本发送。
            // 日志按原因去重，避免有人用畸形标签刷爆控制台。
            logParseFailure(e);
            return Component.text(sanitized.replace("\\<", "<"));
        }
    }

    private void logParseFailure(RuntimeException e) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (reportedFailures.add(reason)) {
            plugin.getLogger().warning("消息样式解析失败，已按纯文本发送 -> " + reason);
        }
    }

    public String sanitize(String text, Player player) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!escapeByDefault) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        Matcher matcher = TAG.matcher(text);
        int last = 0;
        while (matcher.find()) {
            out.append(escapePlain(text.substring(last, matcher.start())));
            String inner = matcher.group(1);
            if (isAllowed(inner, player)) {
                out.append(matcher.group());
            } else {
                out.append("\\<").append(inner).append('>');
            }
            last = matcher.end();
        }
        out.append(escapePlain(text.substring(last)));
        return out.toString();
    }

    private boolean isAllowed(String inner, Player player) {
        if (player == null) {
            return false;
        }
        if (has(player, "minimessage")) {
            return true;
        }
        String tag = inner.startsWith("/") ? inner.substring(1) : inner;
        String normalized = tag.toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.startsWith("#")) {
            // 必须校验为合法的 3/6 位十六进制：非法色值会让 MiniMessage 解析抛异常，
            // 而异常会沿整条消息的渲染链上抛，最终表现为「玩家发不出任何消息」。
            return has(player, "color-hex") && isHexColor(normalized);
        }
        if (normalized.indexOf(':') >= 0) {
            // 带参数的标签（gradient / click / hover / font 等）一律归入高级权限
            return has(player, "advanced");
        }
        if (normalized.equals("newline") || normalized.equals("br")) {
            // 换行不是颜色能力：若跟随默认对所有玩家开放的 color-named，
            // 任何人都能把一句话渲染成多行，伪造「看起来像系统/管理员发言」的内容。
            return has(player, "advanced");
        }
        if (normalized.equals("reset")) {
            return has(player, "color-named") || has(player, "advanced");
        }
        if (NAMED_COLORS.contains(normalized)) {
            return has(player, "color-named");
        }
        if (DECORATIONS.contains(normalized)) {
            return has(player, "decoration");
        }
        return has(player, "advanced");
    }

    /** 校验 {@code #rgb} / {@code #rrggbb} 形式的十六进制色值。 */
    private static boolean isHexColor(String normalized) {
        int length = normalized.length() - 1;
        if (length != 3 && length != 6) {
            return false;
        }
        for (int i = 1; i < normalized.length(); i++) {
            if (Character.digit(normalized.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private boolean has(Player player, String key) {
        String permission = permissions.get(key);
        return permission != null && !permission.isEmpty() && player.hasPermission(permission);
    }

    private String escapePlain(String segment) {
        if (segment.isEmpty()) {
            return segment;
        }
        return segment.replace("\\", "\\\\").replace("<", "\\<");
    }

    public void reload() {
        plugin.configManager().reload("style.yml");
        loadSettings();
    }
}
