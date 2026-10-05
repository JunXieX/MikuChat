package com.junxiex.mikuchat.module.chat;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.service.ChannelView;
import com.junxiex.mikuchat.core.text.TokenContext;
import com.junxiex.mikuchat.core.text.TokenRegistry;
import com.junxiex.mikuchat.module.style.StyleFilter;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息正文组装：把过滤后的文本交由 token 处理器（展示、@ 等）与样式净化渲染为组件。
 * 公开聊天与私聊共用，保证行为一致。
 */
public final class MessageComposer {

    private final TokenRegistry tokens;
    private final StyleFilter style;

    public MessageComposer(MikuChat plugin) {
        this.tokens = plugin.services().require(TokenRegistry.class);
        this.style = plugin.services().require(StyleFilter.class);
    }

    public Composed compose(Player sender, ChannelView channel, String text) {
        List<String> mentions = new ArrayList<>();
        TokenContext context = new TokenContext(sender, channel, text, s -> style.render(s, sender), mentions);
        Component body = tokens.build(text, context);
        return new Composed(body, mentions);
    }

    public record Composed(Component body, List<String> mentions) {
    }
}
