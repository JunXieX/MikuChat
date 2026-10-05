package com.junxiex.mikuchat.module.format;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 单条格式规则。
 */
public record FormatRule(String id,
                         int priority,
                         Set<String> channels,
                         String permission,
                         String world,
                         String format) {

    public static FormatRule from(ConfigurationSection section, String fallbackId) {
        String id = section.getString("id", fallbackId);
        int priority = section.getInt("priority", 0);
        Set<String> channels = parseSet(section.getString("channel", "*"));
        String permission = blankToNull(section.getString("permission"));
        String world = section.getString("world", "*");
        String format = section.getString("format", "<gray>%player% <dark_gray>»<white> <message>");
        return new FormatRule(id, priority, channels, permission, world, format);
    }

    private static Set<String> parseSet(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("*")) {
            return null;
        }
        Set<String> set = new HashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                set.add(value);
            }
        }
        return set.isEmpty() ? null : set;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public boolean matchesChannel(String channelId) {
        return channels == null || channels.contains(channelId.toLowerCase(Locale.ROOT));
    }
}
