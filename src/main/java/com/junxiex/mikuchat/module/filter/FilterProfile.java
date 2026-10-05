package com.junxiex.mikuchat.module.filter;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * 过滤策略（可在 filter/config.yml 中定义多套，由频道按 id 引用）。
 */
public final class FilterProfile {

    private final String replacement;
    private final boolean blockOnMatch;
    private final boolean notifySender;
    private final boolean advertisementEnabled;
    private final boolean advertisementBlock;
    private final AdDetector adDetector;

    private FilterProfile(String replacement, boolean blockOnMatch, boolean notifySender,
                          boolean advertisementEnabled, boolean advertisementBlock, AdDetector adDetector) {
        this.replacement = replacement;
        this.blockOnMatch = blockOnMatch;
        this.notifySender = notifySender;
        this.advertisementEnabled = advertisementEnabled;
        this.advertisementBlock = advertisementBlock;
        this.adDetector = adDetector;
    }

    public static FilterProfile from(ConfigurationSection section, AdDetector detector, String defaultReplacement) {
        String fallback = defaultReplacement == null || defaultReplacement.isEmpty() ? "*" : defaultReplacement;
        String replacement = section.getString("replacement", fallback);
        if (replacement == null || replacement.isEmpty()) {
            replacement = fallback;
        }
        boolean block = section.getBoolean("block-on-match", false);
        boolean notify = section.getBoolean("notify-sender", true);
        boolean adEnabled = section.getBoolean("ad.enabled", true);
        boolean adBlock = section.getBoolean("ad.block", true);
        return new FilterProfile(replacement, block, notify, adEnabled, adBlock, detector);
    }

    /** 兜底策略：配置中未定义任何 profiles 时使用，保证聊天流程不会因空配置而崩溃。 */
    public static FilterProfile fallback(String replacement) {
        String value = replacement == null || replacement.isEmpty() ? "*" : replacement;
        return new FilterProfile(value, false, true, false, false, new AdDetector(List.of(), List.of()));
    }

    public static AdDetector buildDetector(ConfigurationSection section) {
        List<String> patterns = new ArrayList<>();
        List<String> whitelist = new ArrayList<>();
        if (section != null) {
            patterns.addAll(section.getStringList("ad.patterns"));
            whitelist.addAll(section.getStringList("ad.whitelist"));
        }
        return new AdDetector(patterns, whitelist);
    }

    public String replacement() {
        return replacement;
    }

    public boolean blockOnMatch() {
        return blockOnMatch;
    }

    public boolean notifySender() {
        return notifySender;
    }

    public boolean advertisementEnabled() {
        return advertisementEnabled;
    }

    public boolean advertisementBlock() {
        return advertisementBlock;
    }

    public AdDetector adDetector() {
        return adDetector;
    }
}
