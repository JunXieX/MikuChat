package com.junxiex.mikuchat.core.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板渲染器：把配置中的格式模板渲染为 Component。
 * <p>
 * 规则：
 * <ul>
 *   <li>{@code <...>} 为 MiniMessage 原生标签，用于样式与交互。</li>
 *   <li>{@code %name%} 为动态值注入：优先匹配内置变量（player/displayname/prefix/suffix/group/world/server），
 *       其余交给 PlaceholderAPI。注入结果为 Component，不参与二次解析，杜绝格式注入。</li>
 * </ul>
 */
public final class TemplateRenderer {

    private static final Pattern TOKEN = Pattern.compile("%([^%\\s]+)%");

    private final MetaBridge bridge;
    private final Supplier<String> serverId;

    public TemplateRenderer(MetaBridge bridge, Supplier<String> serverId) {
        this.bridge = bridge;
        this.serverId = serverId;
    }

    public Component render(String template, Player player, Map<String, Component> extras) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        Map<String, Component> injected = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder builder = new StringBuilder();
        int index = 0;
        while (matcher.find()) {
            String token = matcher.group(1);
            Component value = resolveToken(token, player);
            String name = "mcp_" + index++;
            injected.put(name, value);
            matcher.appendReplacement(builder, Matcher.quoteReplacement("<" + name + ">"));
        }
        matcher.appendTail(builder);

        TagResolver.Builder resolvers = TagResolver.builder();
        injected.forEach((name, component) -> resolvers.resolver(Placeholder.component(name, component)));
        if (extras != null) {
            extras.forEach((name, component) -> resolvers.resolver(Placeholder.component(name, component)));
        }
        return TextUtil.mini().deserialize(builder.toString(), resolvers.build());
    }

    private Component resolveToken(String token, Player player) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "player" -> player != null ? Component.text(player.getName()) : Component.empty();
            case "displayname", "display_name" ->
                    player != null ? player.displayName() : Component.empty();
            case "prefix" -> bridge != null && player != null ? bridge.prefix(player) : Component.empty();
            case "suffix" -> bridge != null && player != null ? bridge.suffix(player) : Component.empty();
            case "group" -> Component.text(bridge != null && player != null ? bridge.group(player) : "");
            case "world" -> Component.text(player != null ? player.getWorld().getName() : "");
            case "server" -> Component.text(serverId == null ? "" : serverId.get());
            default -> resolveExternal(token, player);
        };
    }

    private Component resolveExternal(String token, Player player) {
        if (bridge == null || player == null || !bridge.hasPlaceholderApi()) {
            return Component.text("%" + token + "%");
        }
        String raw = bridge.placeholder(player, token);
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return TextUtil.legacy(raw);
    }
}
