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

    private static final Pattern TAG = Pattern.compile("<([^<>]*)>");

    private static final Set<String> NAMED_COLORS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white");

    private static final Set<String> DECORATIONS = Set.of(
            "bold", "b", "italic", "i", "underlined", "u", "strikethrough", "st", "obfuscated", "obf");

    private static final String[] PERMISSION_KEYS = {"color-named", "color-hex", "decoration", "advanced", "minimessage"};

    private final ConfigFile config;
    private final MikuChat plugin;
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
        return TextUtil.mini().deserialize(sanitize(text, player));
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
            return has(player, "color-hex") && normalized.length() >= 4;
        }
        if (normalized.indexOf(':') >= 0) {
            // 带参数的标签（gradient / click / hover / font 等）一律归入高级权限
            return has(player, "advanced");
        }
        if (normalized.equals("reset") || normalized.equals("newline")) {
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
