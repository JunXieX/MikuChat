package com.junxiex.mikuchat.module.antispam;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.service.AntiSpamService;
import com.junxiex.mikuchat.core.service.ChannelView;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 防刷屏管理器：冷却 / 令牌桶高频 / 相似度 / 句内重复四重检测。
 * 所有状态按玩家独立存放，退出即清理，复杂度与在线人数成线性关系。
 */
public final class AntiSpamManager implements AntiSpamService {

    /** 预编译：String#replaceAll 每次都会重新编译正则，属于消息热路径。 */
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    private final MikuChat plugin;
    private final ConfigFile config;
    private final Map<UUID, PlayerSpamState> states = new ConcurrentHashMap<>();

    private volatile Map<String, AntiSpamProfile> profiles = Map.of();
    private volatile String defaultProfile = "default";

    public AntiSpamManager(MikuChat plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().get("antispam.yml");
    }

    public void load() {
        plugin.configManager().reload("antispam.yml");
        this.defaultProfile = config.config().getString("default-profile", "default");
        Map<String, AntiSpamProfile> loaded = new LinkedHashMap<>();
        ConfigurationSection section = config.config().getConfigurationSection("profiles");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection profileSection = section.getConfigurationSection(key);
                if (profileSection != null) {
                    loaded.put(key.toLowerCase(Locale.ROOT), AntiSpamProfile.from(profileSection));
                }
            }
        }
        this.profiles = loaded;
    }

    @Override
    public Result check(Player sender, ChannelView channel, String text) {
        if (text == null || text.isEmpty() || sender == null) {
            return Result.PASS;
        }
        AntiSpamProfile profile = profileFor(channel);
        if (profile == null || sender.hasPermission(profile.bypassPermission())) {
            return Result.PASS;
        }
        PlayerSpamState state = states.computeIfAbsent(sender.getUniqueId(), k -> new PlayerSpamState());
        long now = System.currentTimeMillis();

        if (profile.cooldownEnabled() && profile.cooldownMillis() > 0 && state.lastMessageAt() > 0) {
            long elapsed = now - state.lastMessageAt();
            if (elapsed < profile.cooldownMillis()) {
                return violation(profile, Type.COOLDOWN, profile.cooldownMillis() - elapsed, sender);
            }
        }

        if (profile.throttleEnabled() && !consumeToken(state, profile)) {
            return violation(profile, Type.THROTTLE, 0, sender);
        }

        if (profile.repetitionEnabled() && isRepetition(text, profile)) {
            return violation(profile, Type.REPETITION, 0, sender);
        }

        if (profile.similarityEnabled() && isSimilar(text, state, profile)) {
            return violation(profile, Type.SIMILARITY, 0, sender);
        }

        state.lastMessageAt(now);
        state.pushHistory(normalize(text), profile.historySize());
        return Result.PASS;
    }

    private Result violation(AntiSpamProfile profile, Type type, long extra, Player sender) {
        if (profile.notifyStaff()) {
            notifyStaff(sender, type);
        }
        return new Result(true, profile.silent(), type, extra);
    }

    private void notifyStaff(Player sender, Type type) {
        String playerName = sender.getName();
        // 遍历在线玩家统一回到全局线程，避免在区域线程上操作全局玩家列表
        plugin.scheduler().global(() -> {
            var message = plugin.lang().get("antispam.staff-notify",
                    "player", playerName, "type", type.name());
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("mikuchat.admin")) {
                    plugin.scheduler().atEntity(online, () -> online.sendMessage(message));
                }
            }
        });
    }

    private boolean consumeToken(PlayerSpamState state, AntiSpamProfile profile) {
        double capacity = profile.throttleMax();
        long now = System.nanoTime();
        if (state.lastMessageAt() == 0 && state.tokens() == 0) {
            // 玩家首次发言：令牌桶视为已满，避免第一条消息被误判为高频
            state.tokens(capacity);
            state.lastRefillNanos(now);
        }
        double ratePerMilli = capacity / (double) profile.throttlePeriodMillis();
        long elapsedNanos = now - state.lastRefillNanos();
        state.lastRefillNanos(now);
        state.tokens(Math.min(capacity, state.tokens() + elapsedNanos / 1_000_000.0 * ratePerMilli));
        if (state.tokens() >= 1.0) {
            state.tokens(state.tokens() - 1.0);
            return true;
        }
        return false;
    }

    private boolean isSimilar(String text, PlayerSpamState state, AntiSpamProfile profile) {
        String normalized = normalize(text);
        if (normalized.length() < profile.similarityMinLength()) {
            return false;
        }
        double threshold = profile.similarityThreshold();
        double prefilter = threshold * 0.6;
        for (String previous : state.history()) {
            if (previous.length() < profile.similarityMinLength()) {
                continue;
            }
            double gram = SimilarityUtil.gramSimilarity(previous, normalized, 3);
            if (gram >= threshold) {
                return true;
            }
            if (gram >= prefilter && SimilarityUtil.editSimilarity(previous, normalized) >= threshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * 句内重复检测：整条消息由同一子串重复组成时判为刷屏。
     * <p>
     * 判定基于「最小周期」而非逐个长度试除：白名单存的是重复单元（如 {@code ?}、{@code 哈}），
     * 只有拿最小周期去比对白名单才能正确放行 {@code ????????} 这类消息。
     * 早期逐长度试除的写法在重复次数 ≥ 2×min-repeat 时会退化到更长的周期
     * （如 8 个问号会试到 {@code ??}），白名单里只有 {@code ?} 时反而误判为刷屏。
     */
    private boolean isRepetition(String text, AntiSpamProfile profile) {
        String stripped = WHITESPACE.matcher(text).replaceAll("");
        int minRepeat = profile.repetitionMinRepeat();
        if (stripped.length() < minRepeat) {
            return false;
        }
        int period = minimalPeriod(stripped);
        if (period >= stripped.length()) {
            // 无重复结构
            return false;
        }
        if (stripped.length() / period < minRepeat) {
            return false;
        }
        if (period > profile.repetitionMaxPatternLength()) {
            return false;
        }
        return !profile.repetitionWhitelist().contains(stripped.substring(0, period).toLowerCase(Locale.ROOT));
    }

    /** 返回字符串的最小周期长度；无重复时返回字符串自身长度。 */
    private int minimalPeriod(String text) {
        int length = text.length();
        // KMP 前缀函数：最长公共前后缀的长度
        int[] prefix = new int[length];
        for (int i = 1; i < length; i++) {
            int j = prefix[i - 1];
            while (j > 0 && text.charAt(i) != text.charAt(j)) {
                j = prefix[j - 1];
            }
            if (text.charAt(i) == text.charAt(j)) {
                j++;
            }
            prefix[i] = j;
        }
        int candidate = length - prefix[length - 1];
        return length % candidate == 0 ? candidate : length;
    }

    private String normalize(String text) {
        return WHITESPACE.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private AntiSpamProfile profileFor(ChannelView channel) {
        String id = channel == null ? null : channel.antispamProfile();
        if (id == null) {
            id = defaultProfile;
        }
        AntiSpamProfile profile = profiles.get(id.toLowerCase(Locale.ROOT));
        if (profile != null) {
            return profile;
        }
        return profiles.getOrDefault(defaultProfile, profiles.values().stream().findFirst().orElse(null));
    }

    @Override
    public void clear(Player player) {
        states.remove(player.getUniqueId());
    }
}
