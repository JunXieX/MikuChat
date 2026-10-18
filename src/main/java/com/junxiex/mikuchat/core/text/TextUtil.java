package com.junxiex.mikuchat.core.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;

/**
 * 文本工具：统一 MiniMessage / Gson / 纯文本序列化入口。
 */
public final class TextUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    private TextUtil() {
    }

    public static MiniMessage mini() {
        return MINI;
    }

    /**
     * 解析 legacy 颜色代码文本（如 LuckPerms 前缀、PlaceholderAPI 变量返回值）。
     *
     * <p>必须按文本实际使用的符号选择序列化器：两种序列化器互不认识对方的符号，
     * 用错会把色码当作普通文字保留在组件中——游戏内因客户端自行渲染 {@code §} 看似正常，
     * 控制台则会原样打印出 {@code §b} 这类字符。</p>
     */
    public static Component legacy(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        return (text.indexOf('§') >= 0 ? LEGACY_SECTION : LEGACY_AMPERSAND).deserialize(text);
    }

    public static Component parse(String miniMessage) {
        return MINI.deserialize(miniMessage == null ? "" : miniMessage);
    }

    public static String plain(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }

    public static String toJson(Component component) {
        return GSON.serialize(component);
    }

    public static Component fromJson(String json) {
        return GSON.deserialize(json);
    }

    /** 转义 MiniMessage 特殊字符，使其作为纯文本呈现。 */
    public static String escape(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("<", "\\<");
    }

    /**
     * 递归剥离组件树中的 click / hover 交互事件，保留文本内容与颜色样式。
     * <p>
     * 用于所有来自跨服信任边界的组件：交互事件由发送端按它自己的权限策略放行，
     * 接收端若不剥离，等于让对端决定本服玩家能点到什么（如点击执行指令）。
     */
    public static Component stripInteractive(Component component) {
        if (component == null) {
            return Component.empty();
        }
        // style(...) 只替换样式，保留该组件自身的文本内容与子组件
        Component result = component.style(component.style().clickEvent(null).hoverEvent(null));
        // 翻译组件的参数是组件列表，但不属于 children：漏掉它们等于给
        // `<lang:key:<click:run_command:...>>` 留了一条把交互组件带过信任边界的通道。
        if (result instanceof net.kyori.adventure.text.TranslatableComponent translatable
                && !translatable.args().isEmpty()) {
            result = translatable.args(translatable.args().stream().map(TextUtil::stripInteractive).toList());
        }
        List<Component> children = result.children();
        if (children.isEmpty()) {
            return result;
        }
        return result.children(children.stream().map(TextUtil::stripInteractive).toList());
    }

    /** 按字符数截断，并避免把代理对（emoji）从中间截断产生非法字符。 */
    public static String truncate(String text, int maxLength) {
        if (text == null || maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        int end = maxLength;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
