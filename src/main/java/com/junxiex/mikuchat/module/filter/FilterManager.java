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
 * 白名单区间放行 → 纯 ASCII 词条的单词边界校验 → 广告与屏蔽词命中区间在归一化
 * 下标空间合并 → 一次性映射回原文替换（保留原文下标映射）。
 * <p>
 * 屏蔽词只归一化一次；广告规则另用一份「不套用忽略字符集」的归一化视图，因为
 * URL / IP / 域名规则依赖 {@code . : /} 等结构字符，屏蔽词的忽略字符集会把这些字符
 * 删掉。广告同时看「广告归一化 / 原文」两种视图，命中区间统一换算到屏蔽词归一化
 * 下标后与屏蔽词区间合并，最后一次性映射回原文替换。
 * <p>
 * 此外还提供 {@link #blockedByVisibleText} 供渲染后二次校验：玩家用 MiniMessage 标签
 * 把敏感词拆开时，原文匹配不到、但玩家实际看到的文本仍然命中。
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
        String wantedProfile = config.config().getString("default-profile", "default").toLowerCase(Locale.ROOT);
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
        // 广告检测专用归一化：只做全角→半角、小写，刻意不套用忽略字符集、不折叠重复、不删空白：
        //   - 广告规则靠 . : / @ 等结构字符定位 URL、IP、域名，屏蔽词归一化会把这些字符删掉，
        //     连普通链接都检测不到；
        //   - 折叠重复会把 http 折成 htp、把 // 折成 /，URL 规则同样失效；
        //   - 删空白会让 \S+ 越过空格把后一个词吞进链接（"http://x.com 你好" 被整段替换），
        //     所以空白必须保留。
        TextNormalizer adNormalizer = new TextNormalizer(
                config.config().getBoolean("normalize.full-width", true),
                config.config().getBoolean("normalize.lowercase", true),
                false,
                false,
                "");

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
            loadedProfiles.put(wantedProfile, FilterProfile.fallback(loaded.replacement()));
            plugin.getLogger().warning("filter/config.yml 未定义任何 profiles，已启用兜底策略（仅替换屏蔽词，不拦截广告）。");
        }
        String defaultProfile = loadedProfiles.containsKey(wantedProfile) ? wantedProfile
                : loadedProfiles.keySet().stream().findFirst().orElse(wantedProfile);

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
        this.state = new State(normalizer, adNormalizer, automaton, whitelist, loadedProfiles,
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
        // 屏蔽词归一化只做一次，其结果同时是最终替换区间的下标基准：广告命中区间会先
        // 换算到这个空间，与屏蔽词区间合并后由 replaceNormalizedRanges 一次性映射回原文，
        // 避免先替换一边、另一边再按旧下标替换造成的错位。
        TextNormalizer.Normalized normalized = current.normalizer().normalize(text);

        List<int[]> ranges = new ArrayList<>();
        boolean advertisement = false;
        if (profile.advertisementEnabled()) {
            List<int[]> adRanges = detectAdvertisement(current, profile, text, normalized);
            if (!adRanges.isEmpty()) {
                advertisement = true;
                if (profile.advertisementBlock()) {
                    return new Result(text, false, profile.notifySender(), true, true);
                }
                ranges.addAll(adRanges);
            }
        }

        Scan scan = scanNormalized(current, text, normalized);
        if (!scan.ranges().isEmpty()) {
            if (profile.blockOnMatch()) {
                return new Result(text, false, profile.notifySender(), true, advertisement);
            }
            ranges.addAll(scan.ranges());
        }

        if (ranges.isEmpty()) {
            return new Result(text, false, profile.notifySender(), false, advertisement);
        }
        String result = replaceNormalizedRanges(text, normalized, merge(ranges), profile.replacement());
        return new Result(result, true, profile.notifySender(), false, advertisement);
    }

    @Override
    public boolean blockedByVisibleText(Player sender, ChannelView channel, String visibleText) {
        if (visibleText == null || visibleText.isEmpty()) {
            return false;
        }
        State current = state;
        // 拥有绕过权限的玩家本就不受过滤限制，二次校验同样放行
        if (sender != null && sender.hasPermission(current.bypassPermission())) {
            return false;
        }
        FilterProfile profile = profileFor(current, channel);
        if (profile == null) {
            return false;
        }
        TextNormalizer.Normalized normalized = current.normalizer().normalize(visibleText);
        if (profile.advertisementEnabled()
                && !detectAdvertisement(current, profile, visibleText, normalized).isEmpty()) {
            plugin.debug("过滤二次校验拦截：可见文本命中广告规则，发送者 "
                    + (sender == null ? "?" : sender.getName()));
            return true;
        }
        if (!scanNormalized(current, visibleText, normalized).ranges().isEmpty()) {
            plugin.debug("过滤二次校验拦截：可见文本命中屏蔽词，发送者 "
                    + (sender == null ? "?" : sender.getName()));
            return true;
        }
        return false;
    }

    /** 仅供 /mchat filter test 使用：返回命中的屏蔽词。 */
    public List<String> scanWords(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        State current = state;
        return new ArrayList<>(new LinkedHashSet<>(
                scanNormalized(current, text, current.normalizer().normalize(text)).patterns()));
    }

    public boolean scanAdvertisement(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        State current = state;
        FilterProfile profile = profileFor(current, null);
        return profile != null && profile.advertisementEnabled()
                && !detectAdvertisement(current, profile, text, current.normalizer().normalize(text)).isEmpty();
    }

    public int wordCount() {
        return state.wordCount();
    }

    /**
     * 广告命中区间，统一换算到「屏蔽词归一化下标」空间。
     * <p>
     * 同时看广告归一化（全角→半角、小写，保留 URL 结构字符与空白）与原文：
     * 前者识别全角 / 大小写变形，后者兜住归一化改变边界断言上下文的情况。
     * 屏蔽词归一化会删掉 {@code . : /}，不能用于广告规则。
     */
    private List<int[]> detectAdvertisement(State current, FilterProfile profile, String text,
                                           TextNormalizer.Normalized normalized) {
        AdDetector detector = profile.adDetector();
        TextNormalizer.Normalized adNormalized = current.adNormalizer().normalize(text);
        List<int[]> ranges = new ArrayList<>();
        for (int[] range : detector.findMatches(adNormalized.text())) {
            int[] mapped = toNormalizedRange(normalized,
                    adNormalized.originalStart(range[0]), adNormalized.originalEnd(range[1]));
            if (mapped != null) {
                ranges.add(mapped);
            }
        }
        for (int[] range : detector.findMatches(text)) {
            int[] mapped = toNormalizedRange(normalized, range[0], range[1]);
            if (mapped != null) {
                ranges.add(mapped);
            }
        }
        return ranges;
    }

    /** 在调用方已归一化好的文本上扫描，供 apply / blockedByVisibleText 复用同一份归一化结果。 */
    private Scan scanNormalized(State current, String original, TextNormalizer.Normalized normalized) {
        AhoCorasick automaton = current.automaton();
        if (automaton.size() == 0) {
            return new Scan(List.of(), List.of());
        }
        List<AhoCorasick.Match> matches = automaton.findMatches(normalized.text());
        if (matches.isEmpty()) {
            return new Scan(List.of(), List.of());
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
                    && hasAsciiAlnumNeighbour(original, normalized, match.start(), match.end())) {
                continue;
            }
            ranges.add(new int[]{match.start(), match.end()});
            patterns.add(match.pattern());
        }
        return new Scan(merge(ranges), patterns);
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
        return profile != null ? profile : current.profiles().get(current.defaultProfile());
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
            builder.append(text, last, range[0]);
            builder.append(replacement(replacement, range[1] - range[0]));
            last = range[1];
        }
        if (last < text.length()) {
            builder.append(text, last, text.length());
        }
        return builder.toString();
    }

    /**
     * 把「原文下标区间」换算成「归一化下标区间」。
     * <p>
     * 归一化只删除被忽略的字符、或把连续重复字符折叠成一个，因此 {@code origStart}
     * 单调不减，可以线性定位：起点取第一个原文下标不小于 {@code originalStart} 的
     * 归一化字符，终点取第一个原文下标不小于 {@code originalEnd} 的归一化字符。
     * 若该区间内没有任何被保留的字符（例如命中片段全是标点/空白），返回 {@code null}。
     */
    private int[] toNormalizedRange(TextNormalizer.Normalized normalized, int originalStart, int originalEnd) {
        int[] starts = normalized.origStart();
        int from = -1;
        int to = starts.length;
        for (int i = 0; i < starts.length; i++) {
            if (from < 0 && starts[i] >= originalStart) {
                from = i;
            }
            if (starts[i] >= originalEnd) {
                to = i;
                break;
            }
        }
        if (from < 0 || to <= from) {
            return null;
        }
        return new int[]{from, to};
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
    private record Scan(List<int[]> ranges, List<String> patterns) {
    }

    /** 一份自洽的运行时配置快照。 */
    private record State(TextNormalizer normalizer, TextNormalizer adNormalizer, AhoCorasick automaton,
                         List<String> whitelistPhrases, Map<String, FilterProfile> profiles,
                         String defaultProfile, String bypassPermission, boolean asciiWordBoundary, int wordCount) {

        static State empty() {
            return new State(
                    new TextNormalizer(true, true, true, true, ""),
                    new TextNormalizer(true, true, false, false, ""),
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