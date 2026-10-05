package com.junxiex.mikuchat.module.filter;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本归一化：全角转半角、大小写折叠、忽略配置标点/空白、可选折叠连续重复字符，
 * 并维护「归一化下标 → 原文区间」映射，保证命中后可原位替换。
 */
public final class TextNormalizer {

    private final boolean fullWidth;
    private final boolean lowercase;
    private final boolean ignoreWhitespace;
    private final boolean collapseRepeats;
    private final String ignoreChars;

    public TextNormalizer(boolean fullWidth, boolean lowercase, boolean ignoreWhitespace,
                          boolean collapseRepeats, String ignoreChars) {
        this.fullWidth = fullWidth;
        this.lowercase = lowercase;
        this.ignoreWhitespace = ignoreWhitespace;
        this.collapseRepeats = collapseRepeats;
        this.ignoreChars = ignoreChars == null ? "" : ignoreChars;
    }

    public Normalized normalize(String input) {
        if (input == null || input.isEmpty()) {
            return new Normalized("", new int[0], new int[0]);
        }
        StringBuilder text = new StringBuilder(input.length());
        // 归一化后字符数不会超过原串长度，用原始数组避免装箱（消息热路径）
        int[] starts = new int[input.length()];
        int[] ends = new int[input.length()];
        int count = 0;
        int previous = -1;

        for (int i = 0; i < input.length(); ) {
            int codePoint = input.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            int normalized = codePoint;
            if (fullWidth) {
                normalized = toHalfWidth(normalized);
            }
            if (lowercase) {
                normalized = Character.toLowerCase(normalized);
            }
            boolean ignored = Character.isWhitespace(normalized)
                    ? ignoreWhitespace
                    : ignoreChars.indexOf(normalized) >= 0;
            if (!ignored) {
                if (collapseRepeats && previous == normalized && count > 0) {
                    ends[count - 1] = i + charCount;
                } else {
                    text.appendCodePoint(normalized);
                    starts[count] = i;
                    ends[count] = i + charCount;
                    count++;
                    previous = normalized;
                }
            }
            i += charCount;
        }

        return new Normalized(text.toString(),
                java.util.Arrays.copyOf(starts, count),
                java.util.Arrays.copyOf(ends, count));
    }

    private static int toHalfWidth(int codePoint) {
        if (codePoint == 0x3000) {
            return ' ';
        }
        if (codePoint >= 0xFF01 && codePoint <= 0xFF5E) {
            return codePoint - 0xFEE0;
        }
        return codePoint;
    }

    /**
     * 归一化结果。
     *
     * @param text      归一化后的文本
     * @param origStart 每个归一化字符对应的原文起始下标
     * @param origEnd   每个归一化字符对应的原文结束下标（不含）
     */
    public record Normalized(String text, int[] origStart, int[] origEnd) {

        public int originalStart(int normalizedIndex) {
            if (origStart.length == 0) {
                return 0;
            }
            return origStart[Math.max(0, Math.min(normalizedIndex, origStart.length - 1))];
        }

        public int originalEnd(int normalizedIndexExclusive) {
            if (origEnd.length == 0) {
                return 0;
            }
            int index = normalizedIndexExclusive - 1;
            index = Math.max(0, Math.min(index, origEnd.length - 1));
            return origEnd[index];
        }
    }
}
