package com.junxiex.mikuchat.module.format;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.service.ChannelView;
import com.junxiex.mikuchat.core.service.FormatService;
import com.junxiex.mikuchat.core.text.TemplateRenderer;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 格式管理器：按 priority 从高到低匹配规则。
 */
public final class FormatManager implements FormatService {

    private static final String FALLBACK = "<gray>%player% <dark_gray>»<white> <message>";

    private final MikuChat plugin;
    private final ConfigFile config;
    private final TemplateRenderer renderer;
    private volatile List<FormatRule> rules = List.of();

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
                    loaded.add(FormatRule.from(ruleSection, key));
                }
            }
        }
        loaded.sort(Comparator.comparingInt(FormatRule::priority).reversed());
        this.rules = loaded;
    }

    @Override
    public Component render(Player sender, ChannelView channel, Component body) {
        FormatRule rule = match(sender, channel);
        String template = rule == null ? FALLBACK : rule.format();
        Map<String, Component> extras = new HashMap<>();
        extras.put("message", body);
        extras.put("channel", com.junxiex.mikuchat.core.text.TextUtil.parse(channel.displayName()));
        return renderer.render(template, sender, extras);
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
