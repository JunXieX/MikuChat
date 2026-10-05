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
    private volatile Pattern combined;
    private volatile List<ChatTokenHandler> snapshot = List.of();
    private volatile boolean built;

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
        Pattern pattern = combinedOrRebuild();
        List<ChatTokenHandler> current = snapshot;
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
            ChatTokenHandler handler = handlerFor(current, token);
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
        this.built = false;
        this.combined = null;
    }

    /** 组合正则与处理器快照只构建一次；即使没有任何处理器也会置 built=true，避免每条消息重复加锁。 */
    private synchronized void rebuild() {
        if (built) {
            return;
        }
        if (handlers.isEmpty()) {
            snapshot = List.of();
            combined = null;
        } else {
            StringBuilder builder = new StringBuilder();
            for (ChatTokenHandler handler : handlers) {
                if (builder.length() > 0) {
                    builder.append('|');
                }
                builder.append("(?:").append(handler.pattern().pattern()).append(')');
            }
            snapshot = List.copyOf(handlers);
            combined = Pattern.compile(builder.toString());
        }
        built = true;
    }

    private Pattern combinedOrRebuild() {
        if (!built) {
            rebuild();
        }
        return combined;
    }
}
