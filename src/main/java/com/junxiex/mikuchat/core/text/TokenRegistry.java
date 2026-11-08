package com.junxiex.mikuchat.core.text;

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息标记注册表：把注册的处理器组合成单一正则，单次扫描完成 [item]、@ 等标记的识别与渲染。
 */
public final class TokenRegistry {

    private final List<ChatTokenHandler> handlers = new ArrayList<>();
    /** 组合正则与处理器快照；注册/注销时整体重建，聊天线程只做一次 volatile 读。 */
    private volatile Snapshot snapshot = new Snapshot(null, List.of());

    public synchronized void register(ChatTokenHandler handler) {
        handlers.add(handler);
        snapshot = buildSnapshot();
    }

    public synchronized void unregister(ChatTokenHandler handler) {
        handlers.remove(handler);
        snapshot = buildSnapshot();
    }

    /**
     * 把文本切分为「普通文本段 + 标记组件」。普通文本段交由 textRenderer 做样式净化与解析；
     * 标记组件由对应处理器渲染，二者互不二次解析。
     */
    public Component build(String text, TokenContext context) {
        Snapshot current = snapshot;
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
            Component rendered = null;
            if (handler != null) {
                try {
                    rendered = handler.render(context, token);
                } catch (RuntimeException ignored) {
                    // 单个标记处理器异常不能让整条消息丢失：回退为把该标记按普通文本渲染。
                    // TokenRegistry 不持有 plugin 引用（也不改构造签名），因此不在此处记录日志；
                    // 处理器内部应自行记录失败原因（例如 DisplayManager 的 debug 日志）。
                }
            }
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

    private Snapshot buildSnapshot() {
        if (handlers.isEmpty()) {
            return new Snapshot(null, List.of());
        }
        StringBuilder builder = new StringBuilder();
        for (ChatTokenHandler handler : handlers) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            Pattern pattern = handler.pattern();
            // 必须连同 flags 一起合并：Pattern#pattern() 只返回正则文本，
            // 直接拼接会丢掉 CASE_INSENSITIVE 等标志。例如 [item] 的处理器声明了
            // 大小写不敏感，若丢弃标志，[ITEM] / [Inv] 这类写法就完全不会被识别。
            // 用内联标志组把 flags 限定在该处理器自己的分支内，不污染其它分支。
            String flags = inlineFlags(pattern.flags());
            if (flags.isEmpty()) {
                builder.append("(?:").append(pattern.pattern()).append(')');
            } else {
                builder.append("(?").append(flags).append(':').append(pattern.pattern()).append(')');
            }
        }
        return new Snapshot(Pattern.compile(builder.toString()), List.copyOf(handlers));
    }

    /** 当前处理器只用 CASE_INSENSITIVE；其它 flags 不参与合并。 */
    private static String inlineFlags(int flags) {
        return (flags & Pattern.CASE_INSENSITIVE) != 0 ? "i" : "";
    }

    /** 一次构建产物：组合正则与与之匹配的处理器快照。 */
    private record Snapshot(Pattern combined, List<ChatTokenHandler> handlers) {
    }
}
