package com.junxiex.mikuchat.velocity;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Velocity 端配置（YAML，与后端配置风格统一）。
 *
 * <p>首次加载时若配置文件不存在，会创建数据目录并从插件内置资源写出带注释的
 * 默认配置——保证插件启用后目录与配置文件一定存在。解析失败时沿用当前值，
 * 不会因配置文件损坏导致插件无法加载。</p>
 */
public final class VelocityConfig {

    private static final String FILE_NAME = "config.yml";
    private static final String RESOURCE = "/config.yml";
    private static final String DEFAULT_CHANNEL = "mikuchat:proxy";
    private static final boolean DEFAULT_DEBUG = false;

    private final Path dataDirectory;
    private final Logger logger;

    // 加载在初始化线程完成，读取发生在各连接的事件线程，volatile 保证可见性。
    private volatile String channel = DEFAULT_CHANNEL;
    private volatile boolean debug = DEFAULT_DEBUG;

    public VelocityConfig(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    /** 加载（或重载）配置；文件缺失时先写出默认配置。 */
    public synchronized void load() {
        Path file = dataDirectory.resolve(FILE_NAME);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dataDirectory);
                writeDefault(file);
            }
            Map<String, Object> root = read(file);
            channel = text(root.get("channel"), DEFAULT_CHANNEL);
            debug = flag(root.get("debug"), DEFAULT_DEBUG);
        } catch (Throwable t) {
            logger.warn("读取 Velocity 端配置失败，沿用当前配置：{}", t.getMessage());
        }
    }

    /** 插件消息通道名，需与后端 network.yml 的 channel 一致。 */
    public String channel() {
        return channel;
    }

    public boolean debug() {
        return debug;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(Path file) throws IOException {
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            Object parsed = new Yaml().load(reader);
            return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        }
    }

    private static String text(Object value, String def) {
        if (value == null) {
            return def;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? def : text;
    }

    private static boolean flag(Object value, boolean def) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? def : Boolean.parseBoolean(value.toString().trim());
    }

    /** 写出默认配置：优先使用内置资源，资源缺失时退化为最小内容以保证文件一定生成。 */
    private void writeDefault(Path file) throws IOException {
        try (InputStream in = VelocityConfig.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                Files.copy(in, file);
            } else {
                Files.writeString(file,
                        "channel: \"" + DEFAULT_CHANNEL + "\"\ndebug: " + DEFAULT_DEBUG + "\n",
                        StandardCharsets.UTF_8);
            }
        }
        logger.info("已生成默认配置文件：{}", file);
    }
}