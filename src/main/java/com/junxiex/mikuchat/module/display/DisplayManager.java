package com.junxiex.mikuchat.module.display;

import com.junxiex.mikuchat.MikuChat;
import com.junxiex.mikuchat.core.config.ConfigFile;
import com.junxiex.mikuchat.core.text.ChatTokenHandler;
import com.junxiex.mikuchat.core.text.TextUtil;
import com.junxiex.mikuchat.core.text.TokenContext;
import com.junxiex.mikuchat.core.util.Cooldowns;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * 展示模块：把 [item] / [inv] / [ender] 之类的标记替换为可悬停查看的物品、背包、末影箱。
 * 纯 Paper API 实现（HoverEvent.showItem / showText），不使用 NMS，版本升级免维护。
 */
public final class DisplayManager implements ChatTokenHandler {

    public enum Type {ITEM, INVENTORY, ENDERCHEST}

    private final MikuChat plugin;
    private final ConfigFile config;
    private final Cooldowns cooldowns;

    private volatile Pattern pattern = Pattern.compile("\\[(?:item|i)\\]", Pattern.CASE_INSENSITIVE);
    private volatile Map<String, Type> tokens = Map.of();
    private volatile Map<Type, String> permissions = Map.of();
    private volatile Map<Type, Long> cooldownsMillis = Map.of();

    public DisplayManager(MikuChat plugin) {
        this.plugin = plugin;
        this.config = plugin.configManager().get("display.yml");
        this.cooldowns = plugin.services().require(Cooldowns.class);
    }

    public void load() {
        plugin.configManager().reload("display.yml");
        Map<String, Type> loadedTokens = new HashMap<>();
        collect(loadedTokens, "tokens.item", Type.ITEM);
        collect(loadedTokens, "tokens.inventory", Type.INVENTORY);
        collect(loadedTokens, "tokens.enderchest", Type.ENDERCHEST);
        this.tokens = loadedTokens;

        StringBuilder regex = new StringBuilder();
        for (String token : loadedTokens.keySet()) {
            if (regex.length() > 0) {
                regex.append('|');
            }
            regex.append(Pattern.quote(token));
        }
        this.pattern = regex.length() == 0
                ? Pattern.compile("(?!)")
                : Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);

        Map<Type, String> loadedPermissions = new HashMap<>();
        Map<Type, Long> loadedCooldowns = new HashMap<>();
        for (Type type : Type.values()) {
            String key = type.name().toLowerCase(Locale.ROOT);
            loadedPermissions.put(type, config.config().getString("permissions." + key, "mikuchat.display." + key));
            loadedCooldowns.put(type, config.config().getLong("cooldowns." + key + "-millis", 2000L));
        }
        this.permissions = loadedPermissions;
        this.cooldownsMillis = loadedCooldowns;
    }

    private void collect(Map<String, Type> target, String path, Type type) {
        ConfigurationSection section = config.config();
        for (String token : section.getStringList(path)) {
            if (token != null && !token.isBlank()) {
                target.put(token.toLowerCase(Locale.ROOT), type);
            }
        }
    }

    @Override
    public Pattern pattern() {
        return pattern;
    }

    @Override
    public Component render(TokenContext context, String matched) {
        Type type = tokens.get(matched.toLowerCase(Locale.ROOT));
        if (type == null) {
            return null;
        }
        Player player = context.sender();
        String permission = permissions.get(type);
        if (permission != null && !permission.isEmpty() && !player.hasPermission(permission)) {
            plugin.scheduler().atEntity(player, () -> player.sendMessage(
                    plugin.lang().get("generic.no-permission")));
            return null;
        }
        long cooldown = cooldownsMillis.getOrDefault(type, 0L);
        long remaining = cooldowns.check(player.getUniqueId(), "display:" + type.name(), cooldown);
        if (remaining > 0) {
            String seconds = String.format(Locale.ROOT, "%.1f", remaining / 1000.0);
            plugin.scheduler().atEntity(player, () -> player.sendMessage(
                    plugin.lang().get("display.cooldown", "seconds", seconds)));
            return null;
        }
        return switch (type) {
            case ITEM -> renderItem(player);
            case INVENTORY -> renderContainer(player, player.getInventory(), 36, "display.inventory-title",
                    "display.label-inventory", "display.empty-inventory");
            case ENDERCHEST -> renderContainer(player, player.getEnderChest(), 27, "display.enderchest-title",
                    "display.label-enderchest", "display.empty-enderchest");
        };
    }

    private Component renderItem(Player player) {
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack == null || stack.getType().isAir()) {
            plugin.scheduler().atEntity(player, () -> player.sendMessage(plugin.lang().get("display.no-item")));
            return null;
        }
        Component label = plugin.lang().get("display.label-item");
        return label.hoverEvent(stack.asHoverEvent(UnaryOperator.identity()));
    }

    private Component renderContainer(Player player, Inventory inventory, int size, String titleKey,
                                      String labelKey, String emptyKey) {
        Component grid = buildGrid(player, inventory, size, titleKey);
        if (grid == null) {
            plugin.scheduler().atEntity(player, () -> player.sendMessage(plugin.lang().get(emptyKey)));
            return null;
        }
        Component label = plugin.lang().get(labelKey);
        return label.hoverEvent(HoverEvent.showText(grid));
    }

    private Component buildGrid(Player player, Inventory inventory, int size, String titleKey) {
        Component title = plugin.lang().get(titleKey, "player", player.getName());
        boolean empty = true;
        Component result = title;
        int rows = size / 9;
        for (int row = 0; row < rows; row++) {
            Component line = Component.empty();
            for (int col = 0; col < 9; col++) {
                ItemStack stack = inventory.getItem(row * 9 + col);
                if (stack != null && !stack.getType().isAir()) {
                    empty = false;
                }
                line = line.append(compact(stack)).append(Component.text("  "));
            }
            result = result.append(Component.newline()).append(line);
        }
        return empty ? null : result;
    }

    private Component compact(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return plugin.lang().get("display.empty-slot");
        }
        Component name = stack.displayName();
        String plain = TextUtil.plain(name);
        if (plain.length() > 5) {
            name = Component.text(plain.substring(0, 5));
        }
        if (stack.getAmount() > 1) {
            name = name.append(Component.text("x" + stack.getAmount()));
        }
        return name;
    }
}
