package com.junxiex.mikuchat.module.channel;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.service.ChannelService;
import com.junxiex.mikuchat.core.service.ChannelView;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 频道管理器：加载 channels/*.yml，维护玩家当前频道选择。
 */
public final class ChannelManager implements ChannelService {

    private final MikuChat plugin;
    private final com.junxiex.mikuchat.core.user.UserDataStore userData;
    // 整体替换而非原地修改，保证 reload 与聊天线程并发时读到的始终是完整的一份
    private volatile Map<String, Channel> channels = new LinkedHashMap<>();

    public ChannelManager(MikuChat plugin, com.junxiex.mikuchat.core.user.UserDataStore userData) {
        this.plugin = plugin;
        this.userData = userData;
    }

    public void load() {
        saveDefaults();
        Map<String, Channel> loaded = new LinkedHashMap<>();
        File dir = new File(plugin.getDataFolder(), "channels");
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                String id = yaml.getString("id", file.getName().replace(".yml", ""));
                loaded.put(id.toLowerCase(Locale.ROOT), new Channel(id, yaml));
            }
        }
        this.channels = loaded;
        if (channels.isEmpty()) {
            plugin.getLogger().severe("未加载到任何频道，聊天功能将不可用！请检查 channels/ 目录。");
        }
    }

    private void saveDefaults() {
        save("channels/local.yml");
        save("channels/global.yml");
    }

    private void save(String resource) {
        File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            try {
                plugin.saveResource(resource, false);
            } catch (IllegalArgumentException ignored) {
                // 资源缺失时忽略
            }
        }
    }

    @Override
    public ChannelView byId(String id) {
        return id == null ? null : channels.get(id.toLowerCase(Locale.ROOT));
    }

    @Override
    public ChannelView defaultChannel() {
        ChannelView channel = byId(plugin.defaultChannelId());
        if (channel != null) {
            return channel;
        }
        return channels.values().stream().findFirst().orElse(null);
    }

    @Override
    public Collection<ChannelView> channels() {
        return new ArrayList<>(channels.values());
    }

    @Override
    public ChannelView selected(Player player) {
        String id = userData.getString(player.getUniqueId(), "channel");
        ChannelView channel = byId(id);
        if (channel == null || !mayUse(player, channel)) {
            channel = defaultChannel();
        }
        return channel;
    }

    @Override
    public void select(Player player, ChannelView channel) {
        userData.set(player.getUniqueId(), "channel", channel.id());
    }

    @Override
    public boolean mayUse(Player player, ChannelView channel) {
        if (channel == null) {
            return false;
        }
        String permission = channel.permission();
        return permission == null || player.hasPermission(permission);
    }

    @Override
    public void sendChannelList(CommandSender sender, Player player) {
        sender.sendMessage(plugin.lang().get("channel.list-header"));
        for (ChannelView channel : channels.values()) {
            boolean accessible = mayUse(player, channel);
            Map<String, String> placeholders = new java.util.HashMap<>();
            placeholders.put("id", channel.id());
            placeholders.put("display", channel.displayName());
            placeholders.put("description", accessible ? channel.displayName() : plugin.lang().raw("channel.no-permission"));
            var line = plugin.lang().parse(plugin.lang().raw("channel.list-line", placeholders));
            sender.sendMessage(line);
        }
    }

    public void reload() {
        load();
    }
}
