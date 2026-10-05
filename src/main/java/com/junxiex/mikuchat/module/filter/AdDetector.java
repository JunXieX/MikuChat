package com.junxiex.mikuchat.module.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广告/链接检测：按配置正则识别 IP、域名、URL、联系方式；命中白名单的内容放行。
 */
public final class AdDetector {

    private final List<Pattern> patterns;
    private final List<String> whitelist;

    public AdDetector(List<String> patternStrings, List<String> whitelist) {
        List<Pattern> compiled = new ArrayList<>();
        for (String raw : patternStrings) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(raw));
            } catch (Exception ignored) {
                // 非法正则忽略
            }
        }
        this.patterns = compiled;
        List<String> normalizedWhitelist = new ArrayList<>();
        for (String entry : whitelist) {
            if (entry != null && !entry.isBlank()) {
                normalizedWhitelist.add(entry.toLowerCase(Locale.ROOT).trim());
            }
        }
        this.whitelist = normalizedWhitelist;
    }

    /** 是否存在非白名单的广告命中。 */
    public boolean isAdvertisement(String text) {
        return !findMatches(text).isEmpty();
    }

    /** 返回所有非白名单命中区间 [start, end)。 */
    public List<int[]> findMatches(String text) {
        List<int[]> matches = new ArrayList<>();
        if (text == null || text.isEmpty() || patterns.isEmpty()) {
            return matches;
        }
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                // 只对命中片段做小写化：整体 toLowerCase 可能改变字符串长度，导致下标与原文错位
                String hit = text.substring(matcher.start(), matcher.end()).toLowerCase(Locale.ROOT);
                if (isWhitelisted(hit)) {
                    continue;
                }
                matches.add(new int[]{matcher.start(), matcher.end()});
            }
        }
        return matches;
    }

    private boolean isWhitelisted(String hit) {
        for (String entry : whitelist) {
            if (hit.contains(entry)) {
                return true;
            }
        }
        return false;
    }
}
