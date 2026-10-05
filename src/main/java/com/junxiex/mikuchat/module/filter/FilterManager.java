package com.junxiex.mikuchat.module.filter;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.service.ChannelView;
import com.junxiex.mikuchat.core.service.FilterService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 过滤管理器：屏蔽词（Aho-Corasick）+ 广告/链接检测。
 * <p>
 * 匹配流程：归一化（全角/大小写/忽略标点空白/折叠重复）→ 自动机单次扫描 →
 * 白名单区间放行 → 纯 ASCII 词条的单词边界校验 → 原位替换（保留原文下标映射）。
 * <p>
 * 全部运行时配置收敛到一个不可变 {@link State}，重载时整体替换，聊天线程读取到的
 * 永远是同一份自洽快照；早期逐字段赋值的写法可能让并发读看到「新词库 + 旧归一化参数」
 * 这类拼接状态。
 */
public final class FilterManager implements FilterService {

    private final MikuChat plugin;
    private final ConfigFile config;
    private volatile State state = State.empty();

    public FilterManager(MikuChat plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().get("filter/config.yml");
    }

    public void load() {
        plugin.configManager().reload("filter/config.yml");
        String bypassPermission = config.config().getString("bypass-permission", "mikuchat.bypass.filter");
        String defaultProfile = config.config().getString("default-profile", "default");
        boolean asciiWordBoundary = config.config().getBoolean("matching.ascii-word-boundary", true);
        int minLength = Math.max(1, config.config().getInt("matching.min-length", 2));

        File wordsYml = new File(plugin.getDataFolder(), "filter/words.yml");
        File wordsTxt = new File(plugin.getDataFolder(), "filter/words.txt");
        WordListLoader.Loaded loaded = WordListLoader.load(wordsYml, wordsTxt);

        // 词表自带的忽略字符优先于 filter/config.yml
        String ignoreChars = loaded.ignoredPunctuation() != null
                ? loaded.ignoredPunctuation()
                : config.config().getString("normalize.ignore-chars", "");

        TextNormalizer normalizer = new TextNormalizer(
                config.config().getBoolean("normalize.full-width", true),
                config.config().getBoolean("normalize.lowercase", true),
                config.config().getBoolean("normalize.ignore-whitespace", true),
                config.config().getBoolean("normalize.collapse-repeats", true),
                ignoreChars);

        Map<String, FilterProfile> loadedProfiles = new LinkedHashMap<>();
        ConfigurationSection profilesSection = config.config().getConfigurationSection("profiles");
        if (profilesSection != null) {
            for (String key : profilesSection.getKeys(false)) {
                ConfigurationSection section = profilesSection.getConfigurationSection(key);
                if (section != null) {
                    loadedProfiles.put(key.toLowerCase(Locale.ROOT),
                            FilterProfile.from(section, FilterProfile.buildDetector(section), loaded.replacement()));
                }
            }
        }
        if (loadedProfiles.isEmpty()) {
            loadedProfiles.put(defaultProfile.toLowerCase(Locale.ROOT), FilterProfile.fallback(loaded.replacement()));
            plugin.getLogger().warning("filter/config.yml 未定义任何 profiles，已启用兜底策略（仅替换屏蔽词，不拦截广告）。");
        }

        List<String> whitelist = new ArrayList<>();
        for (String phrase : loaded.whitelist()) {
            String normalized = normalizer.normalize(phrase).text();
            if (!normalized.isEmpty()) {
                whitelist.add(normalized);
            }
        }

        Set<String> normalizedWords = new LinkedHashSet<>();
        int skipped = 0;
        for (String word : loaded.words()) {
            String normalized = normalizer.normalize(word).text();
            if (normalized.isEmpty() || normalized.length() < minLength) {
                skipped++;
                continue;
            }
            normalizedWords.add(normalized);
        }
        AhoCorasick automaton = new AhoCorasick(normalizedWords);

        // 一次性发布整份快照
        this.state = new State(normalizer, automaton, whitelist, loadedProfiles,
                defaultProfile, bypassPermission, asciiWordBoundary, automaton.size());
        plugin.getLogger().info("屏蔽词载入完成：生效 " + automaton.size() + " 条，跳过 " + skipped
                + " 条（归一化后为空或短于 min-length=" + minLength + "）。");
    }

    @Override
    public Result apply(Player sender, ChannelView channel, String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text, false, false, false, false);
        }
        State current = state;
        if (sender != null && sender.hasPermission(current.bypassPermission())) {
            return new Result(text, false, false, false, false);
        }
        FilterProfile profile = profileFor(current, channel);
        if (profile == null) {
            // 兜底：配置异常时不阻断聊天，仅跳过过滤
            return new Result(text, false, false, false, false);
        }
        String result = text;
        boolean modified = false;
        boolean advertisement = false;

        if (profile.advertisementEnabled()) {
            List<int[]> adMatches = profile.adDetector().findMatches(result);
            if (!adMatches.isEmpty()) {
                advertisement = true;
                if (profile.advertisementBlock()) {
                    return new Result(text, false, profile.notifySender(), true, true);
                }
                result = replaceOriginalRanges(result, merge(adMatches), profile.replacement());
                modified = true;
            }
        }

        Scan scan = scan(current, result);
        if (!scan.ranges().isEmpty()) {
            if (profile.blockOnMatch()) {
                return new Result(text, modified, profile.notifySender(), true, advertisement);
            }
            result = replaceNormalizedRanges(result, scan.normalized(), scan.ranges(), profile.replacement());
            modified = true;
        }
        return new Result(result, modified, profile.notifySender(), false, advertisement);
    }

    /** 仅供 /mchat filter test 使用：返回命中的屏蔽词。 */
    public List<String> scanWords(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(scan(state, text).patterns()));
    }

    public boolean scanAdvertisement(String text) {
        State current = state;
        FilterProfile profile = current.profiles().getOrDefault(current.defaultProfile(),
                current.profiles().values().stream().findFirst().orElse(null));
        return profile != null && profile.advertisementEnabled() && profile.adDetector().isAdvertisement(text);
    }

    public int wordCount() {
        return state.wordCount();
    }

    private Scan scan(State current, String text) {
        AhoCorasick automaton = current.automaton();
        if (automaton == null || automaton.size() == 0) {
            return new Scan(null, List.of(), List.of());
        }
        TextNormalizer.Normalized normalized = current.normalizer().normalize(text);
        List<AhoCorasick.Match> matches = automaton.findMatches(normalized.text());
        if (matches.isEmpty()) {
            return new Scan(normalized, List.of(), List.of());
        }
        List<String> whitelistPhrases = current.whitelistPhrases();
        List<int[]> whitelistRanges = null;
        List<int[]> ranges = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        for (AhoCorasick.Match match : matches) {
            if (!whitelistPhrases.isEmpty()) {
                if (whitelistRanges == null) {
                    whitelistRanges = whitelistRanges(whitelistPhrases, normalized.text());
                }
                if (coveredBy(whitelistRanges, match.start(), match.end())) {
                    continue;
                }
            }
            if (current.asciiWordBoundary() && isAsciiOnly(match.pattern())
                    && hasAsciiAlnumNeighbour(text, normalized, match.start(), match.end())) {
                continue;
            }
            ranges.add(new int[]{match.start(), match.end()});
            patterns.add(match.pattern());
        }
        return new Scan(normalized, merge(ranges), patterns);
    }

    private List<int[]> whitelistRanges(List<String> whitelistPhrases, String normalizedText) {
        List<int[]> ranges = new ArrayList<>();
        for (String phrase : whitelistPhrases) {
            int from = 0;
            while (true) {
                int index = normalizedText.indexOf(phrase, from);
                if (index < 0) {
                    break;
                }
                ranges.add(new int[]{index, index + phrase.length()});
                from = index + 1;
            }
        }
        return ranges;
    }

    private boolean coveredBy(List<int[]> ranges, int start, int end) {
        for (int[] range : ranges) {
            if (range[0] <= start && end <= range[1]) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAsciiAlnumNeighbour(String original, TextNormalizer.Normalized normalized, int start, int end) {
        int originalStart = normalized.originalStart(start);
        int originalEnd = normalized.originalEnd(end);
        if (originalStart > 0 && isAsciiAlnum(original.charAt(originalStart - 1))) {
            return true;
        }
        return originalEnd < original.length() && isAsciiAlnum(original.charAt(originalEnd));
    }

    private boolean isAsciiOnly(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch > 127 || !Character.isLetterOrDigit(ch)) {
                return false;
            }
        }
        return true;
    }

    private boolean isAsciiAlnum(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
    }

    private FilterProfile profileFor(State current, ChannelView channel) {
        String id = channel == null ? null : channel.filterProfile();
        if (id == null) {
            id = current.defaultProfile();
        }
        FilterProfile profile = current.profiles().get(id.toLowerCase(Locale.ROOT));
        if (profile != null) {
            return profile;
        }
        return current.profiles().getOrDefault(current.defaultProfile(),
                current.profiles().values().stream().findFirst().orElse(null));
    }

    private List<int[]> merge(List<int[]> ranges) {
        if (ranges.size() <= 1) {
            return ranges;
        }
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt((int[] r) -> r[0]).thenComparingInt(r -> r[1]));
        List<int[]> merged = new ArrayList<>();
        int[] current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            int[] next = sorted.get(i);
            if (next[0] <= current[1]) {
                current[1] = Math.max(current[1], next[1]);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private String replaceOriginalRanges(String text, List<int[]> ranges, String replacement) {
        StringBuilder builder = new StringBuilder(text.length());
        int last = 0;
        for (int[] range : ranges) {
            int start = Math.max(last, Math.min(range[0], text.length()));
            int end = Math.max(start, Math.min(range[1], text.length()));
            builder.append(text, last, start);
            builder.append(replacement(replacement, end - start));
            last = end;
        }
        if (last < text.length()) {
            builder.append(text, last, text.length());
        }
        return builder.toString();
    }

    private String replaceNormalizedRanges(String text, TextNormalizer.Normalized normalized,
                                          List<int[]> normalizedRanges, String replacement) {
        List<int[]> original = new ArrayList<>(normalizedRanges.size());
        for (int[] range : normalizedRanges) {
            int start = normalized.originalStart(range[0]);
            int end = normalized.originalEnd(range[1]);
            if (end > start) {
                original.add(new int[]{start, end});
            }
        }
        return replaceOriginalRanges(text, merge(original), replacement);
    }

    private String replacement(String replacement, int length) {
        if (replacement == null || replacement.isEmpty()) {
            return "";
        }
        if (replacement.length() == 1) {
            return String.valueOf(replacement.charAt(0)).repeat(Math.max(1, length));
        }
        return replacement;
    }

    /** 单次匹配结果。 */
    private record Scan(TextNormalizer.Normalized normalized, List<int[]> ranges, List<String> patterns) {
    }

    /** 一份自洽的运行时配置快照。 */
    private record State(TextNormalizer normalizer, AhoCorasick automaton, List<String> whitelistPhrases,
                         Map<String, FilterProfile> profiles, String defaultProfile, String bypassPermission,
                         boolean asciiWordBoundary, int wordCount) {

        static State empty() {
            return new State(
                    new TextNormalizer(true, true, true, true, ""),
                    new AhoCorasick(List.of()),
                    List.of(),
                    Map.of(),
                    "default",
                    "mikuchat.bypass.filter",
                    true,
                    0);
        }
    }
}