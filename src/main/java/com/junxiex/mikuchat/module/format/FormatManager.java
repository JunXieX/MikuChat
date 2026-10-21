package com.junxiex.mikuchat.module.format;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.service.ChannelView;
import com.junxiex.mikuchat.core.service.FormatService;
import com.junxiex.mikuchat.core.text.TemplateRenderer;
import com.junxiex.mikuchat.core.text.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 格式管理器：按 priority 从高到低匹配规则。
 */
public final class FormatManager implements FormatService {

    private static final String FALLBACK = "<gray>%player% <dark_gray>»<white> <message>";
    /** 预校验模板时用于替代 <message> / <channel> 的占位符，避免合法模板被误判为未知标签。 */
    private static final TagResolver FORMAT_PLACEHOLDERS = createFormatPlaceholders();
    /** 失败告警的去重上限，避免配置写坏时把控制台刷爆。 */
    private static final int MAX_REPORTED_FAILURES = 32;

    private final MikuChat plugin;
    private final ConfigFile config;
    private final TemplateRenderer renderer;
    private volatile List<FormatRule> rules = List.of();
    /** 频道显示名（原始 MiniMessage 字符串）到已解析组件的缓存，避免每条消息重复解析。 */
    private final Map<String, Component> channelCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** 已报告过的渲染/解析失败（按规则 id、频道显示名、模板原文去重）。 */
    private final Set<String> reportedFailures = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public FormatManager(MikuChat plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().get("format.yml");
        this.renderer = plugin.services().require(TemplateRenderer.class);
    }

    public void load() {
        plugin.configManager().reload("format.yml");
        List<FormatRule> loaded = new ArrayList<>();
        ConfigurationSection section = config.config().getConfigurationSection("rules");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection ruleSection = section.getConfigurationSection(key);
                if (ruleSection != null) {
                    FormatRule rule = FormatRule.from(ruleSection, key);
                    // 预校验：format 不是合法 MiniMessage 时直接跳过该规则。
                    // 否则运行期每条聊天消息都会在渲染时抛解析异常，整条消息都发不出去。
                    if (validateTemplate(rule.id(), rule.format())) {
                        loaded.add(rule);
                    }
                }
            }
        }
        loaded.sort(Comparator.comparingInt(FormatRule::priority).reversed());
        this.rules = loaded;
        // 频道显示名可能随 channels/*.yml 改动而变化，重载时清空缓存
        channelCache.clear();
    }

    @Override
    public Component render(Player sender, ChannelView channel, Component body) {
        String template = FALLBACK;
        try {
            FormatRule rule = match(sender, channel);
            template = rule == null ? FALLBACK : rule.format();
            Map<String, Component> extras = new HashMap<>();
            extras.put("message", body);
            extras.put("channel", channelComponent(channel.displayName()));
            return renderer.render(template, sender, extras);
        } catch (RuntimeException e) {
            // 兜底：模板/变量注入在运行期出错时只影响「样式」，不能让整条消息丢失。
            // 按模板原文去重告警，失败时退化为只显示消息正文。
            warnOnce("template:" + template, "聊天格式渲染失败，已回退为只显示消息正文: " + e.getMessage());
            return body;
        }
    }

    /**
     * 频道显示名到组件的缓存。
     * <p>
     * 显示名是固定模板，但每条消息都要用到；此前每条消息都做一次完整的 MiniMessage 解析，
     * 在多人高频聊天时是纯粹的重复开销。这里按原始字符串缓存，配置重载后清空。
     */
    private Component channelComponent(String displayName) {
        String key = displayName == null ? "" : displayName;
        Component cached = channelCache.get(key);
        if (cached != null) {
            return cached;
        }
        Component parsed;
        try {
            parsed = TextUtil.parse(key);
        } catch (RuntimeException e) {
            // 显示名写错（例如 3 位十六进制色值）不能让它冒充「每条消息都抛异常」：
            // 告警一次后退化为纯文本并缓存，避免反复解析、反复抛。
            warnOnce("channel:" + key, "频道显示名不是合法的 MiniMessage，已按纯文本显示: "
                    + key + "（" + e.getMessage() + "）");
            parsed = Component.text(key);
        }
        channelCache.put(key, parsed);
        return parsed;
    }

    /**
     * 预解析校验 format 模板；非法时告警一次并返回 false，由调用方跳过该规则。
     * <p>
     * 校验时必须提供 message / channel 两个占位符：它们是运行时由 {@link TemplateRenderer}
     * 注入的标签，直接 {@code TextUtil.parse} 会被当成未知标签而误杀所有合法规则。
     */
    private boolean validateTemplate(String ruleId, String template) {
        try {
            TextUtil.mini().deserialize(template == null ? "" : template, FORMAT_PLACEHOLDERS);
            return true;
        } catch (RuntimeException e) {
            warnOnce("rule:" + ruleId, "format.yml 规则 " + ruleId
                    + " 的 format 不是合法的 MiniMessage，已跳过该规则: " + e.getMessage());
            return false;
        }
    }

    /** 与 {@link TemplateRenderer} 相同的构建方式，保证校验与运行期使用的是同一套占位符标签。 */
    private static TagResolver createFormatPlaceholders() {
        TagResolver.Builder builder = TagResolver.builder();
        builder.resolver(Placeholder.component("message", Component.empty()));
        builder.resolver(Placeholder.component("channel", Component.empty()));
        return builder.build();
    }

    /** 失败告警去重：同一原因只提示一次，总量有上限，避免配置写坏时刷爆控制台。 */
    private void warnOnce(String key, String message) {
        if (reportedFailures.size() < MAX_REPORTED_FAILURES && reportedFailures.add(key)) {
            plugin.getLogger().warning(message);
        }
    }

    /**
     * 清空频道显示名缓存。
     * <p>
     * 显示名来自 channels/*.yml，由 channel 模块加载；`/mchat reload channel` 只重载
     * 频道配置、不会触发本模块的 load()，因此需要由频道模块在重载后显式通知，
     * 否则聊天行里的频道名会一直停留在旧样式。
     */
    public void invalidateChannelCache() {
        channelCache.clear();
    }

    /**
     * 按 priority 从高到低匹配。命中条件为「规则 id 等于频道的 format 配置」或「规则的 channel 命中频道 id」，
     * 二者任一成立即可；这样既让 channels/*.yml 的 format 键生效，也不会抢掉 admin 这类高优先级通配规则。
     */
    private FormatRule match(Player sender, ChannelView channel) {
        String wantedId = channel.formatId();
        for (FormatRule rule : rules) {
            boolean byId = wantedId != null && wantedId.equalsIgnoreCase(rule.id());
            if (!byId && !rule.matchesChannel(channel.id())) {
                continue;
            }
            if (rule.permission() != null && !sender.hasPermission(rule.permission())) {
                continue;
            }
            if (rule.world() != null && !rule.world().equals("*")
                    && !rule.world().equalsIgnoreCase(sender.getWorld().getName())) {
                continue;
            }
            return rule;
        }
        return null;
    }
}
