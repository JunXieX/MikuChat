package com.junxiex.mikuchat.module.filter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Aho-Corasick 自动机：一次扫描找出文本中所有模式串的出现位置，单次匹配 O(n + 命中数)。
 * 构建一次、多线程只读复用。
 */
public final class AhoCorasick {

    private static final class Node {
        final Map<Character, Node> next = new HashMap<>(2);
        Node fail;
        final List<Integer> output = new ArrayList<>(0);
    }

    private final Node root = new Node();
    private final List<String> patterns;
    private final int[] patternLengths;

    public AhoCorasick(Collection<String> patterns) {
        this.patterns = new ArrayList<>(patterns);
        this.patternLengths = new int[this.patterns.size()];
        for (int i = 0; i < this.patterns.size(); i++) {
            String pattern = this.patterns.get(i);
            patternLengths[i] = pattern.length();
            if (pattern.isEmpty()) {
                continue;
            }
            Node node = root;
            for (int c = 0; c < pattern.length(); c++) {
                node = node.next.computeIfAbsent(pattern.charAt(c), k -> new Node());
            }
            node.output.add(i);
        }
        buildFailureLinks();
    }

    private void buildFailureLinks() {
        Queue<Node> queue = new ArrayDeque<>();
        for (Node child : root.next.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node current = queue.poll();
            for (Map.Entry<Character, Node> entry : current.next.entrySet()) {
                char ch = entry.getKey();
                Node child = entry.getValue();
                Node fail = current.fail;
                while (fail != null && !fail.next.containsKey(ch)) {
                    fail = fail.fail;
                }
                child.fail = fail == null ? root : fail.next.get(ch);
                if (child.fail == child) {
                    child.fail = root;
                }
                child.output.addAll(child.fail.output);
                queue.add(child);
            }
        }
    }

    /**
     * 查找所有命中。
     *
     * @return 命中列表，区间为左闭右开
     */
    public List<Match> findMatches(String text) {
        List<Match> matches = new ArrayList<>();
        if (text == null || text.isEmpty() || patterns.isEmpty()) {
            return matches;
        }
        Node node = root;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // 单次 get 完成状态转移，避免 containsKey + getOrDefault 的重复查找（热路径）
            Node next = node.next.get(ch);
            while (next == null && node != root) {
                node = node.fail;
                next = node.next.get(ch);
            }
            node = next != null ? next : root;
            if (node.output.isEmpty()) {
                continue;
            }
            for (int patternIndex : node.output) {
                int length = patternLengths[patternIndex];
                if (length > 0) {
                    matches.add(new Match(i - length + 1, i + 1, patterns.get(patternIndex)));
                }
            }
        }
        return matches;
    }

    public int size() {
        return patterns.size();
    }

    /** 单次命中。 */
    public record Match(int start, int end, String pattern) {
    }
}
