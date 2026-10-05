package com.junxiex.mikuchat.module.filter;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 屏蔽词表加载器。
 * <p>
 * 兼容两种文件：
 * <ul>
 *   <li>{@code words.txt}：一行一词，{@code #} 开头为注释；</li>
 *   <li>{@code words.yml}：既支持 {@code words:} 映射列表，也支持常见的「顶层序列 + 尾部配置键」
 *       写法（词条以 {@code - xxx} 开头，尾部可带 {@code Ignored-Punctuations} / {@code WhiteList} /
 *       {@code Replacement} 三个配置键）。</li>
 * </ul>
 */
public final class WordListLoader {

    private static final Pattern MAPPING_LINE = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*:.*$");

    private WordListLoader() {
    }

    /**
     * @param words             屏蔽词条
     * @param whitelist         白名单短语（命中落在其内则放行）
     * @param replacement       替换符（可为 null）
     * @param ignoredPunctuation 参与匹配时忽略的字符集合（可为 null）
     */
    public record Loaded(Set<String> words, List<String> whitelist, String replacement, String ignoredPunctuation) {
    }

    public static Loaded load(File ymlFile, File txtFile) {
        Set<String> words = new LinkedHashSet<>();
        List<String> whitelist = new ArrayList<>();
        String replacement = null;
        String ignoredPunctuation = null;

        if (txtFile != null && txtFile.isFile()) {
            for (String raw : readAll(txtFile)) {
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                words.add(trimmed);
            }
        }

        if (ymlFile != null && ymlFile.isFile()) {
            List<String> mappingLines = new ArrayList<>();
            for (String raw : readAll(ymlFile)) {
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("- ")) {
                    String value = unquote(trimmed.substring(2).trim());
                    if (!value.isEmpty()) {
                        words.add(value);
                    }
                } else if (MAPPING_LINE.matcher(trimmed).matches()) {
                    mappingLines.add(raw);
                }
            }
            if (!mappingLines.isEmpty()) {
                YamlConfiguration yaml = new YamlConfiguration();
                try {
                    yaml.loadFromString(String.join("\n", mappingLines));
                    for (String entry : yaml.getStringList("WhiteList")) {
                        if (entry != null && !entry.isBlank()) {
                            whitelist.add(entry.trim());
                        }
                    }
                    String raw = yaml.getString("Replacement");
                    if (raw != null && !raw.isEmpty()) {
                        replacement = raw;
                    }
                    List<String> punctuation = yaml.getStringList("Ignored-Punctuations");
                    if (!punctuation.isEmpty()) {
                        ignoredPunctuation = String.join("", punctuation);
                    }
                } catch (Exception ignored) {
                    // 尾部配置键无法解析时忽略，不影响词条加载
                }
            }
        }
        return new Loaded(words, whitelist, replacement, ignoredPunctuation);
    }

    private static List<String> readAll(File file) {
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
