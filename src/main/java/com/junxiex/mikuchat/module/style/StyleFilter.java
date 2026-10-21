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

    /**
     * 传统 {@code §} 色码（{@code §} 后跟颜色/格式代码，含 {@code §x§R§R§G§G§B§B} 写法）。
     * 它不属于 MiniMessage，却仍会被客户端渲染：不剥离就等于绕过样式权限，
     * 玩家可以不经过任何权限判定给文字上色/加粗。
     */
    private static final Pattern SECTION_CODES = Pattern.compile("§[0-9a-fk-orA-FK-ORxX]");

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
    /** 去重集合的容量上限，防止输入多样性导致无界增长。 */
    private static final int MAX_REPORTED_FAILURES = 32;
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
        // 用异常类型而非完整消息作去重键：消息里包含玩家输入的标签原文，
        // 若以它为键，有权限者可用互不相同的畸形标签让集合无限增长。
        String reason = e.getClass().getSimpleName();
        if (reportedFailures.size() < MAX_REPORTED_FAILURES && reportedFailures.add(reason)) {
            plugin.getLogger().warning("消息样式解析失败，已按纯文本发送 -> " + reason
                    + "（同类问题仅提示一次）");
        }
    }

    public String sanitize(String text, Player player) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 控制字符清洗必须早于 escapeByDefault 判定：§ 色码与换行不属于「样式能力」，
        // 而是绕过样式权限、伪造多行/日志行注入的手段，无论是否开启默认转义都必须清洗。
        String cleaned = stripControlChars(text);
        if (!escapeByDefault) {
            return cleaned;
        }
        StringBuilder out = new StringBuilder(cleaned.length() + 16);
        Matcher matcher = TAG.matcher(cleaned);
        int last = 0;
        while (matcher.find()) {
            out.append(escapePlain(cleaned.substring(last, matcher.start())));
            String inner = matcher.group(1);
            if (isAllowed(inner, player)) {
                out.append(matcher.group());
            } else {
                out.append("\\<").append(inner).append('>');
            }
            last = matcher.end();
        }
        out.append(escapePlain(cleaned.substring(last)));
        return out.toString();
    }

    /**
     * 清洗与样式权限无关的控制字符：删除 § 传统色码，把回车/换行替换为空格。
     * <p>
     * 换行若原样保留，玩家可用一条聊天伪造出「多行」内容（冒充系统公告），
     * 也会把聊天日志拆成多行、破坏日志行注入防护；{@code \r\n} 视为一个换行，
     * 避免 CRLF 被替换成两个空格。
     */
    private static String stripControlChars(String text) {
        String cleaned = SECTION_CODES.matcher(text).replaceAll("");
        if (cleaned.indexOf('\r') < 0 && cleaned.indexOf('\n') < 0) {
            return cleaned;
        }
        return cleaned.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
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

    /**
     * 校验十六进制色值。MiniMessage / Adventure 只接受 6 位 {@code #rrggbb}，
     * 3 位简写（{@code #abc}）会被判为非法并抛解析异常，因此这里也必须拒绝。
     */
    private static boolean isHexColor(String normalized) {
        if (normalized.length() != 7 || normalized.charAt(0) != '#') {
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
