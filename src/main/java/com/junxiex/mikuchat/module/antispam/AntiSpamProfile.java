package com.junxiex.mikuchat.module.antispam;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 防刷屏策略（可在 antispam.yml 定义多套，由频道按 id 引用）。
 */
public record AntiSpamProfile(boolean cooldownEnabled,
                              long cooldownMillis,
                              boolean throttleEnabled,
                              int throttleMax,
                              long throttlePeriodMillis,
                              boolean similarityEnabled,
                              double similarityThreshold,
                              int similarityWindow,
                              int similarityMinLength,
                              boolean repetitionEnabled,
                              int repetitionMinRepeat,
                              int repetitionMaxPatternLength,
                              Set<String> repetitionWhitelist,
                              boolean silent,
                              boolean notifyStaff,
                              String bypassPermission) {

    public static AntiSpamProfile from(ConfigurationSection section) {
        boolean cooldownEnabled = section.getBoolean("cooldown.enabled", true);
        long cooldownMillis = section.getLong("cooldown.millis", 2000L);

        boolean throttleEnabled = section.getBoolean("throttle.enabled", true);
        int throttleMax = Math.max(1, section.getInt("throttle.max-messages", 5));
        long throttlePeriod = Math.max(500L, section.getLong("throttle.period-millis", 5000L));

        boolean similarityEnabled = section.getBoolean("similarity.enabled", true);
        double threshold = section.getDouble("similarity.threshold", 0.85);
        int window = Math.max(1, section.getInt("similarity.window", 5));
        int minLength = Math.max(1, section.getInt("similarity.min-length", 6));

        boolean repetitionEnabled = section.getBoolean("repetition.enabled", true);
        int minRepeat = Math.max(2, section.getInt("repetition.min-repeat", 4));
        int maxPattern = Math.max(1, section.getInt("repetition.max-pattern-length", 8));
        Set<String> whitelist = new HashSet<>();
        for (String value : section.getStringList("repetition.whitelist")) {
            if (value != null && !value.isBlank()) {
                whitelist.add(value.toLowerCase(Locale.ROOT));
            }
        }

        boolean silent = "IGNORE".equalsIgnoreCase(section.getString("actions.mode", "WARN"));
        boolean notifyStaff = section.getBoolean("actions.notify-staff", false);
        String bypass = section.getString("bypass-permission", "mikuchat.bypass.antispam");

        return new AntiSpamProfile(cooldownEnabled, cooldownMillis, throttleEnabled, throttleMax, throttlePeriod,
                similarityEnabled, threshold, window, minLength, repetitionEnabled, minRepeat, maxPattern,
                whitelist, silent, notifyStaff, bypass);
    }

    public int historySize() {
        return Math.max(similarityWindow, 1);
    }
}
