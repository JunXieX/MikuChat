package com.junxiex.mikuchat.module.mention;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.service.MentionNotifier;
import com.junxiex.mikuchat.core.text.ChatTokenHandler;
import com.junxiex.mikuchat.core.text.TokenContext;
import com.junxiex.mikuchat.core.util.Cooldowns;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @ 艾特：识别聊天中的 @玩家 / @all，渲染高亮并提供悬停与点击回复；
 * 被 @ 的在线玩家收到 ActionBar 与音效提示（可单独关闭）。
 */
public final class MentionManager implements ChatTokenHandler, MentionNotifier {

    private final MikuChat plugin;
    private final ConfigFile config;
    private final Cooldowns cooldowns;
    private final com.junxiex.mikuchat.core.user.UserDataStore userData;

    private volatile Pattern pattern = Pattern.compile("@([A-Za-z0-9_]{1,16})");
    private volatile Set<String> allAliases = Set.of("all");
    private volatile String allPermission = "mikuchat.mention.all";
    private volatile long senderAllCooldown = 30_000L;
    private volatile long notifyCooldown = 1_000L;
    private volatile boolean actionBar = true;
    private volatile boolean soundEnabled = true;
    private volatile String soundName = "entity.experience_orb.pickup";
    private volatile float soundVolume = 1.0f;
    private volatile float soundPitch = 1.5f;

    public MentionManager(MikuChat plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().get("mention.yml");
        this.cooldowns = plugin.services().require(Cooldowns.class);
        this.userData = plugin.services().require(com.junxiex.mikuchat.core.user.UserDataStore.class);
    }

    public void load() {
        plugin.configManager().reload("mention.yml");
        String raw = config.config().getString("pattern", "@([A-Za-z0-9_]{1,16})");
        try {
            this.pattern = Pattern.compile(raw);
        } catch (Exception e) {
            this.pattern = Pattern.compile("@([A-Za-z0-9_]{1,16})");
        }
        Set<String> aliases = new HashSet<>();
        for (String alias : config.config().getStringList("all-aliases")) {
            if (alias != null && !alias.isBlank()) {
                aliases.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        this.allAliases = aliases;
        this.allPermission = config.config().getString("permissions.all", "mikuchat.mention.all");
        this.senderAllCooldown = config.config().getLong("cooldown.sender-all-millis", 30_000L);
        this.notifyCooldown = config.config().getLong("cooldown.notify-millis", 1_000L);
        this.actionBar = config.config().getBoolean("notification.action-bar", true);
        this.soundEnabled = config.config().getBoolean("notification.sound.enabled", true);
        this.soundName = config.config().getString("notification.sound.name", "entity.experience_orb.pickup");
        this.soundVolume = (float) config.config().getDouble("notification.sound.volume", 1.0);
        this.soundPitch = (float) config.config().getDouble("notification.sound.pitch", 1.5);
    }

    @Override
    public Pattern pattern() {
        return pattern;
    }

    @Override
    public Component render(TokenContext context, String matched) {
        // 用本处理器自己的正则重新匹配，避免依赖组合正则的捕获组编号
        Matcher own = pattern.matcher(matched);
        if (!own.matches() || own.groupCount() < 1) {
            return null;
        }
        String name = own.group(1);
        Player sender = context.sender();
        if (allAliases.contains(name.toLowerCase(Locale.ROOT))) {
            if (!sender.hasPermission(allPermission)) {
                plugin.scheduler().atEntity(sender, () -> sender.sendMessage(
                        plugin.lang().get("mention.all-no-permission")));
                return null;
            }
            long remaining = cooldowns.check(sender.getUniqueId(), "mention:all", senderAllCooldown);
            if (remaining > 0) {
                plugin.scheduler().atEntity(sender, () -> sender.sendMessage(
                        plugin.lang().get("mention.all-cooldown")));
                return null;
            }
            context.mentions().add("*");
            return styled(plugin.lang().raw("mention.display-all"));
        }
        Player target = findPlayer(name);
        if (target == null) {
            return null;
        }
        context.mentions().add(target.getName());
        String template = plugin.lang().raw("mention.display").replace("{player}", target.getName());
        return styled(template)
                .hoverEvent(HoverEvent.showText(plugin.lang().get("mention.hover", "player", target.getName())))
                .clickEvent(ClickEvent.suggestCommand("/msg " + target.getName() + " "));
    }

    private Component styled(String template) {
        return plugin.lang().parse(template);
    }

    private Player findPlayer(String name) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return online;
            }
        }
        return null;
    }

    @Override
    public void notifyMentioned(UUID senderId, List<String> mentioned, String channelDisplay) {
        // 可能由插件消息回调或实体线程调用，统一回到全局线程遍历在线玩家
        plugin.scheduler().global(() -> handleNotifyMentioned(senderId, mentioned, channelDisplay));
    }

    private void handleNotifyMentioned(UUID senderId, List<String> mentioned, String channelDisplay) {
        if (mentioned == null || mentioned.isEmpty()) {
            return;
        }
        boolean all = mentioned.contains("*");
        Component notice = plugin.lang().getParsed("mention.notify",
                "channel", channelDisplay == null ? "" : channelDisplay);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(senderId)) {
                continue;
            }
            if (!all && !mentioned.contains(online.getName())) {
                continue;
            }
            if (!isEnabled(online)) {
                continue;
            }
            if (cooldowns.check(online.getUniqueId(), "mention:notify", notifyCooldown) > 0) {
                continue;
            }
            plugin.scheduler().atEntity(online, () -> {
                if (actionBar) {
                    online.sendActionBar(notice);
                }
                if (soundEnabled) {
                    online.playSound(online.getLocation(), soundName, soundVolume, soundPitch);
                }
            });
        }
    }

    public boolean isEnabled(Player player) {
        return userData.getBoolean(player.getUniqueId(), "mention-enabled", true);
    }

    public boolean toggle(Player player) {
        boolean value = !isEnabled(player);
        userData.set(player.getUniqueId(), "mention-enabled", value);
        return value;
    }
}
