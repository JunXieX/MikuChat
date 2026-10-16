package com.junxiex.mikuchat.core.text;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息标记注册表：把注册的处理器组合成单一正则，单次扫描完成 [item]、@ 等标记的识别与渲染。
 */
public final class TokenRegistry {

    private final List<ChatTokenHandler> handlers = new CopyOnWriteArrayList<>();
    /**
     * 组合正则与处理器快照。二者必须作为同一个不可变对象整体发布：
     * 若拆成两个字段分别写入，并发读可能看到「已重建完成」却拿到尚未写入的正则，
     * 导致该条消息的所有标记（[item]、@ 等）都不渲染。
     */
    private volatile Snapshot snapshot;

    public void register(ChatTokenHandler handler) {
        handlers.add(handler);
        invalidate();
    }

    public void unregister(ChatTokenHandler handler) {
        handlers.remove(handler);
        invalidate();
    }

    /**
     * 把文本切分为「普通文本段 + 标记组件」。普通文本段交由 textRenderer 做样式净化与解析；
     * 标记组件由对应处理器渲染，二者互不二次解析。
     */
    public Component build(String text, TokenContext context) {
        Snapshot current = snapshotOrRebuild();
        Pattern pattern = current.combined();
        if (pattern == null || text == null || text.isEmpty()) {
            return context.textRenderer().apply(text == null ? "" : text);
        }
        Matcher matcher = pattern.matcher(text);
        Component result = Component.empty();
        int last = 0;
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            if (matcher.start() > last) {
                result = result.append(context.textRenderer().apply(text.substring(last, matcher.start())));
            }
            String token = matcher.group();
            ChatTokenHandler handler = handlerFor(current.handlers(), token);
            Component rendered = handler == null ? null : handler.render(context, token);
            result = result.append(rendered != null ? rendered : context.textRenderer().apply(token));
            last = matcher.end();
        }
        if (!matched) {
            return context.textRenderer().apply(text);
        }
        if (last < text.length()) {
            result = result.append(context.textRenderer().apply(text.substring(last)));
        }
        return result;
    }

    private ChatTokenHandler handlerFor(List<ChatTokenHandler> current, String token) {
        for (ChatTokenHandler handler : current) {
            if (handler.pattern().matcher(token).matches()) {
                return handler;
            }
        }
        return null;
    }

    private void invalidate() {
        // 单次 volatile 写入即完成失效，读线程要么看到旧快照要么看到 null（随后重建）
        snapshot = null;
    }

    /** 组合快照只构建一次；没有任何处理器时也缓存空快照，避免每条消息重复进入同步块。 */
    private Snapshot snapshotOrRebuild() {
        Snapshot current = snapshot;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            Snapshot rebuilt = snapshot;
            if (rebuilt != null) {
                return rebuilt;
            }
            Snapshot built = buildSnapshot();
            snapshot = built;
            return built;
        }
    }

    private Snapshot buildSnapshot() {
        if (handlers.isEmpty()) {
            return new Snapshot(null, List.of());
        }
        StringBuilder builder = new StringBuilder();
        for (ChatTokenHandler handler : handlers) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append("(?:").append(handler.pattern().pattern()).append(')');
        }
        return new Snapshot(Pattern.compile(builder.toString()), List.copyOf(handlers));
    }

    /** 一次构建产物：组合正则与与之匹配的处理器快照。 */
    private record Snapshot(Pattern combined, List<ChatTokenHandler> handlers) {
    }
}
