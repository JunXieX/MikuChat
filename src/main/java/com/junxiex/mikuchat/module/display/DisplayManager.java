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
        // 顺序：先做廉价可行性检查（不构建组件）→ 消费冷却 → 构建 hover。
        // 这样既不会让「空手/空背包」的失败展示吃掉冷却，也不会让冷却中的刷屏消息
        // 反复构建整张 36/27 格网格（[inv]xN 会把网格构建放大 N 倍）。
        boolean hasContent = false;
        String emptyLangKey = null;
        Inventory inventory = null;
        int size = 0;
        switch (type) {
            case ITEM -> hasContent = !isAir(player.getInventory().getItemInMainHand());
            case INVENTORY -> {
                inventory = player.getInventory();
                size = 36;
                emptyLangKey = "display.empty-inventory";
                hasContent = hasAnyItem(inventory, size);
            }
            case ENDERCHEST -> {
                inventory = player.getEnderChest();
                size = 27;
                emptyLangKey = "display.empty-enderchest";
                hasContent = hasAnyItem(inventory, size);
            }
        }
        if (!hasContent) {
            String key = emptyLangKey == null ? "display.no-item" : emptyLangKey;
            notifyLimited(player, "empty:" + type.name(), () -> plugin.lang().get(key));
            return null;
        }
        long cooldown = cooldownsMillis.getOrDefault(type, 0L);
        long remaining = cooldowns.check(player.getUniqueId(), "display:" + type.name(), cooldown);
        if (remaining > 0) {
            notifyCooldown(player, type, remaining);
            return null;
        }
        Inventory target = inventory;
        return switch (type) {
            case ITEM -> renderItem(player);
            case INVENTORY -> renderContainer(player, target, size, "display.inventory-title",
                    "display.label-inventory", "display.empty-inventory");
            case ENDERCHEST -> renderContainer(player, target, size, "display.enderchest-title",
                    "display.label-enderchest", "display.empty-enderchest");
        };
    }

    /** 背包/末影箱是否至少有一件非空气物品（只读槽位、不构建组件）。 */
    private static boolean hasAnyItem(Inventory inventory, int size) {
        for (int i = 0; i < size; i++) {
            if (!isAir(inventory.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAir(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    /**
     * 失败/提示类消息的 1 秒限频：一条消息里 [inv]xN 不应放大成 N 条系统提示。
     */
    private void notifyLimited(Player player, String key, java.util.function.Supplier<Component> message) {
        if (cooldowns.check(player.getUniqueId(), "display:notice:" + key, 1000L) != 0) {
            return;
        }
        plugin.scheduler().atEntity(player, () -> player.sendMessage(message.get()));
    }

    /**
     * 冷却提示。同一条消息里 [item][item][item] 这类重复标记会逐个进入 render，
     * 若每个都发一条提示，一条消息就会被放大成十几条系统消息；这里用独立 key 做 1 秒限频。
     */
    private void notifyCooldown(Player player, Type type, long remaining) {
        if (cooldowns.check(player.getUniqueId(), "display:notice:" + type.name(), 1000L) != 0) {
            return;
        }
        String seconds = String.format(Locale.ROOT, "%.1f", remaining / 1000.0);
        plugin.scheduler().atEntity(player, () -> player.sendMessage(
                plugin.lang().get("display.cooldown", "seconds", seconds)));
    }

    private Component renderItem(Player player) {
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (isAir(stack)) {
            notifyLimited(player, "empty:ITEM", () -> plugin.lang().get("display.no-item"));
            return null;
        }
        Component label = plugin.lang().get("display.label-item");
        try {
            // Paper 的 ItemStack#asHoverEvent 要求数量在 1..99 之间，否则抛 IllegalArgumentException。
            // 其它插件生成超堆叠物品时先夹到 99，避免整条聊天消息因一个展示标记而丢失。
            ItemStack shown = stack;
            if (stack.getAmount() > 99) {
                shown = stack.clone();
                shown.setAmount(99);
            }
            return label.hoverEvent(shown.asHoverEvent(UnaryOperator.identity()));
        } catch (RuntimeException e) {
            plugin.debug("展示 [item] 失败: " + e.getMessage());
            return null;
        }
    }

    private Component renderContainer(Player player, Inventory inventory, int size, String titleKey,
                                      String labelKey, String emptyKey) {
        Component grid = buildGrid(player, inventory, size, titleKey);
        if (grid == null) {
            notifyLimited(player, "empty:" + labelKey, () -> plugin.lang().get(emptyKey));
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
            // 用支持代理对的截断，避免把 emoji（物品自定义名常见）从中间切断产生非法字符
            name = Component.text(TextUtil.truncate(plain, 5));
        }
        if (stack.getAmount() > 1) {
            name = name.append(Component.text("x" + stack.getAmount()));
        }
        return name;
    }
}
