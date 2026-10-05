package com.junxiex.mikuchat.module.logging;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.module.ChatModule;
import com.junxiex.mikuchat.core.service.ChatLogService;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.stream.Stream;

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
        plugin.services().register(ChatLogService.class, this);
        plugin.services().register(LoggingModule.class, this);
        int retentionDays = config.config().getInt("retention-days", 30);
        plugin.scheduler().async(() -> com.junxiex.mikuchat.core.util.LogRetention.purge(
                directory(), retentionDays, fileFormat, plugin.getLogger(), "聊天记录"));
    }

    @Override
    public void disable() {
        plugin.services().unregister(LoggingModule.class);
        plugin.services().unregister(ChatLogService.class);
    }

    @Override
    public void reload() {
        plugin.configManager().reload("chatlog.yml");
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
                .replace("{message}", message);
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
                    collectFromFile(file, needle, effectiveLimit - found.size(), found, effectiveLimit);
                }
            }
            callback.accept(found);
        });
    }

    /**
     * 流式读取单个日志文件，只保留该文件中最新的若干条命中，避免把整个文件读入内存。
     */
    private void collectFromFile(File file, String needle, int remaining, List<String> found, int hardLimit) {
        if (remaining <= 0) {
            return;
        }
        Deque<String> tail = new ArrayDeque<>(Math.min(remaining, 64));
        try (Stream<String> lines = Files.lines(file.toPath(), StandardCharsets.UTF_8)) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                    tail.addLast(line);
                    if (tail.size() > remaining) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("读取聊天记录失败 " + file.getName() + ": " + e.getMessage());
            return;
        }
        // tail 中保留的是该文件最新的命中，按时间倒序追加到结果
        Iterator<String> descending = tail.descendingIterator();
        while (descending.hasNext() && found.size() < hardLimit) {
            found.add(descending.next());
        }
    }

    private Path directory() {
        return plugin.getDataFolder().toPath().resolve(config.config().getString("directory", "data/chat-logs"));
    }
}
