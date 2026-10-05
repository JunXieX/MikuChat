package com.junxiex.mikuchat.module.antispam;

import java.util.HashSet;
import java.util.Set;

/**
 * 相似度计算：3-gram Jaccard 低成本预筛，仅在候选区间内才做编辑距离。
 */
public final class SimilarityUtil {

    private SimilarityUtil() {
    }

    /** 字符 n-gram 集合的 Jaccard 相似度。 */
    public static double gramSimilarity(String a, String b, int gramSize) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        Set<Integer> gramsA = grams(a, gramSize);
        Set<Integer> gramsB = grams(b, gramSize);
        if (gramsA.isEmpty() || gramsB.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        for (Integer gram : gramsA) {
            if (gramsB.contains(gram)) {
                intersection++;
            }
        }
        int union = gramsA.size() + gramsB.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /** 用滚动哈希计算 n-gram 集合，避免为每个 gram 分配一个子串（消息热路径）。 */
    private static Set<Integer> grams(String text, int gramSize) {
        Set<Integer> set = new HashSet<>();
        int size = Math.max(1, gramSize);
        if (text.length() < size) {
            set.add(text.hashCode());
            return set;
        }
        int power = 1;
        for (int i = 1; i < size; i++) {
            power *= 31;
        }
        int hash = 0;
        for (int i = 0; i < text.length(); i++) {
            if (i >= size) {
                hash -= text.charAt(i - size) * power;
            }
            hash = hash * 31 + text.charAt(i);
            if (i >= size - 1) {
                set.add(hash);
            }
        }
        return set;
    }

    /** 归一化编辑距离相似度：1 - 距离 / 最大长度。 */
    public static double editSimilarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(a, b) / max;
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
