package com.junxiex.mikuchat.module.logging;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.service.ChatLogService;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 聊天留档模块：公开聊天按天写入文件，支持关键词检索。
 */
public final class LoggingModule implements ChatModule, ChatLogService {

    private final MikuChat plugin;
    private final ConfigFile config;
    private volatile DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private volatile DateTimeFormatter fileFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public LoggingModule(MikuChat plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().get("chatlog.yml");
    }

    @Override
    public String id() {
        return "logging";
    }

    @Override
    public void enable() {
        plugin.configManager().reload("chatlog.yml");
        applyFormats();
        plugin.services().register(ChatLogService.class, this);
        plugin.services().register(LoggingModule.class, this);
        purgeExpiredLogs();
    }

    /** 按当前 retention-days 异步清理过期留档；enable() 与 reload() 共用，兑现配置里「启动/重载时清理」的承诺。 */
    private void purgeExpiredLogs() {
        int retentionDays = config.config().getInt("retention-days", 30);
        plugin.scheduler().async(() -> com.junxiex.mikuchat.core.util.LogRetention.purge(
                directory(), retentionDays, fileFormat, plugin.getLogger(), "聊天记录"));
    }

    /** 解析时间/日期格式。非法格式回落到内置默认，避免一条错误配置让记录整体不可用。 */
    private void applyFormats() {
        try {
            timeFormat = DateTimeFormatter.ofPattern(config.config().getString("time-format", "yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) {
            timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        }
        try {
            fileFormat = DateTimeFormatter.ofPattern(config.config().getString("file-date-format", "yyyy-MM-dd"));
        } catch (Exception ignored) {
            fileFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        }
    }

    @Override
    public void disable() {
        plugin.services().unregister(LoggingModule.class);
        plugin.services().unregister(ChatLogService.class);
    }

    @Override
    public void reload() {
        plugin.configManager().reload("chatlog.yml");
        // 必须一并重解析格式：否则改完 chatlog.yml 的时间/日期格式要重启才生效
        applyFormats();
        // chatlog.yml 说明承诺「超期文件会在启动/重载时自动删除」，这里与 enable() 保持一致
        purgeExpiredLogs();
    }

    @Override
    public void record(String channelId, String senderName, String message, boolean remote) {
        if (!config.config().getBoolean("enabled", true)) {
            return;
        }
        // 只取一次时间，避免跨零点时"时间"与"文件名"落到不同的日期
        LocalDateTime now = LocalDateTime.now();
        String line = config.config().getString("format", "[{time}] [{channel}] {sender}: {message}")
                .replace("{time}", now.format(timeFormat))
                .replace("{channel}", channelId)
                .replace("{sender}", senderName)
                .replace("{message}", com.junxiex.mikuchat.core.text.TextUtil.oneLine(message));
        plugin.fileWriter().append(directory().resolve(now.format(fileFormat) + ".log"), line);
    }

    @Override
    public void search(String keyword, int limit, Consumer<List<String>> callback) {
        if (keyword == null || keyword.isBlank()) {
            callback.accept(List.of());
            return;
        }
        int configuredMax = Math.max(1, config.config().getInt("search-limit", 200));
        // 双重保险：无论调用方传入多大的 limit，都不超过配置的上限
        int effectiveLimit = limit > 0 ? Math.min(limit, configuredMax) : configuredMax;
        int days = Math.max(1, config.config().getInt("search-days", 7));
        int maxBytes = Math.max(64 * 1024, config.config().getInt("search-max-kilo-bytes", 65536) * 1024);
        String needle = keyword.toLowerCase(Locale.ROOT);
        plugin.scheduler().async(() -> {
            List<String> found = new ArrayList<>(Math.min(effectiveLimit, 64));
            File[] files = directory().toFile().listFiles((dir, name) -> name.endsWith(".log"));
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName).reversed());
                int processed = 0;
                for (File file : files) {
                    if (processed >= days || found.size() >= effectiveLimit) {
                        break;
                    }
                    processed++;
                    collectFromFile(file, needle, effectiveLimit - found.size(), maxBytes, found);
                }
            }
            callback.accept(found);
        });
    }

    /**
     * 只读取文件末尾 {@code maxBytes}，从新到旧取命中。日志按天增长，
     * 全量扫描几天前的文件对「查最近聊天」没有意义，还会随运行时间线性变慢。
     */
    private void collectFromFile(File file, String needle, int maxResults, int maxBytes, List<String> found) {
        if (maxResults <= 0) {
            return;
        }
        long start = Math.max(0, file.length() - maxBytes);
        Deque<String> tail = new ArrayDeque<>(Math.min(maxResults, 64));
        try (FileChannel channel = FileChannel.open(file.toPath());
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     Channels.newInputStream(channel.position(start)), StandardCharsets.UTF_8))) {
            // 从文件中段开始时第一行可能被截断；文件本身小于预算时保留第一行
            if (start > 0) {
                reader.readLine();
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (containsIgnoreCase(line, needle)) {
                    tail.addLast(line);
                    if (tail.size() > maxResults) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("读取聊天记录失败 " + file.getName() + ": " + e.getMessage());
            return;
        }
        Iterator<String> descending = tail.descendingIterator();
        while (descending.hasNext()) {
            found.add(descending.next());
        }
    }

    private static boolean containsIgnoreCase(String text, String lowerNeedle) {
        int length = lowerNeedle.length();
        for (int i = 0; i + length <= text.length(); i++) {
            if (text.regionMatches(true, i, lowerNeedle, 0, length)) {
                return true;
            }
        }
        return false;
    }

    private Path directory() {
        return plugin.getDataFolder().toPath().resolve(config.config().getString("directory", "data/chat-logs"));
    }
}
