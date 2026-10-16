package com.junxiex.mikuchat;

import com.junxiex.mikuchat.core.ServiceRegistry;
import com.junxiex.mikuchat.core.config.ConfigManager;
import com.junxiex.mikuchat.core.i18n.Lang;
import com.junxiex.mikuchat.core.module.ModuleManager;
import com.junxiex.mikuchat.core.scheduler.SchedulerUtil;
import com.junxiex.mikuchat.core.util.AsyncFileWriter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MikuChat 插件主类，负责基础设施初始化与模块生命周期编排。
 */
public final class MikuChat extends JavaPlugin implements Listener {

    private ConfigManager configManager;
    private Lang lang;
    private SchedulerUtil scheduler;
    private ServiceRegistry services;
    private ModuleManager moduleManager;
    private AsyncFileWriter fileWriter;
    private com.junxiex.mikuchat.core.text.TokenRegistry tokenRegistry;
    private com.junxiex.mikuchat.core.util.Cooldowns cooldowns;
    private com.junxiex.mikuchat.core.user.UserDataStore userDataStore;
    private com.junxiex.mikuchat.core.command.CommandRegistrar commandRegistrar;
    private volatile boolean debugEnabled;
    private volatile String serverId = "server-1";
    private volatile String defaultChannelId = "local";

    @Override
    public void onEnable() {

        this.configManager = new ConfigManager(this);
        this.configManager.preload("config.yml");
        this.lang = new Lang(this);
        this.lang.load(configManager.get("config.yml").config().getString("language", "zh_CN"));
        this.scheduler = new SchedulerUtil(this);
        this.services = new ServiceRegistry();
        this.moduleManager = new ModuleManager(this);
        this.fileWriter = new AsyncFileWriter(this);
        this.tokenRegistry = new com.junxiex.mikuchat.core.text.TokenRegistry();
        this.cooldowns = new com.junxiex.mikuchat.core.util.Cooldowns();
        this.userDataStore = new com.junxiex.mikuchat.core.user.UserDataStore(this);
        this.commandRegistrar = new com.junxiex.mikuchat.core.command.CommandRegistrar();
        this.services.register(com.junxiex.mikuchat.core.text.TokenRegistry.class, tokenRegistry);
        this.services.register(com.junxiex.mikuchat.core.util.Cooldowns.class, cooldowns);
        this.services.register(com.junxiex.mikuchat.core.user.UserDataStore.class, userDataStore);
        refreshCoreCache();

        registerModules();
        this.moduleManager.enableAll();
        getServer().getPluginManager().registerEvents(this, this);
        // 所有模块登记完命令后再挂载生命周期处理器，注册时机由服务器决定（晚于 onEnable）
        this.commandRegistrar.attach(getLifecycleManager());

        getLogger().info("MikuChat enabled.");
    }

    /** 玩家退出时集中清理跨模块的玩家级缓存，避免各模块各自清理导致遗漏或重复。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.clear(event.getPlayer().getUniqueId());
        userDataStore.forget(event.getPlayer().getUniqueId());
    }

    /** 加入时异步预热玩家数据，避免后续在游戏线程/全局线程上首次读盘。 */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        userDataStore.preload(event.getPlayer().getUniqueId());
    }

    @Override
    public void onDisable() {
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        if (userDataStore != null) {
            userDataStore.close();
        }
        if (fileWriter != null) {
            fileWriter.shutdown();
        }
        getLogger().info("MikuChat disabled.");
    }

    private void registerModules() {
        // 单个模块构造失败不应导致后续模块全部丢失，因此逐一注册、逐一兜底。
        // 注册顺序即启用顺序，有依赖关系的模块（如 format 依赖 integration 提供的渲染器）必须靠前。
        registerModule("integration", () -> new com.junxiex.mikuchat.module.integration.IntegrationModule(this));
        registerModule("style", () -> new com.junxiex.mikuchat.module.style.StyleModule(this));
        registerModule("channel", () -> new com.junxiex.mikuchat.module.channel.ChannelModule(this));
        registerModule("format", () -> new com.junxiex.mikuchat.module.format.FormatModule(this));
        registerModule("filter", () -> new com.junxiex.mikuchat.module.filter.FilterModule(this));
        registerModule("antispam", () -> new com.junxiex.mikuchat.module.antispam.AntiSpamModule(this));
        registerModule("network", () -> new com.junxiex.mikuchat.module.network.VelocityNetworkModule(this));
        registerModule("mute", () -> new com.junxiex.mikuchat.module.mute.MuteModule(this));
        registerModule("display", () -> new com.junxiex.mikuchat.module.display.DisplayModule(this));
        registerModule("mention", () -> new com.junxiex.mikuchat.module.mention.MentionModule(this));
        registerModule("logging", () -> new com.junxiex.mikuchat.module.logging.LoggingModule(this));
        registerModule("chat", () -> new com.junxiex.mikuchat.module.chat.ChatPipelineModule(this));
        registerModule("private", () -> new com.junxiex.mikuchat.module.privatechat.PrivateModule(this));
        registerModule("command", () -> new com.junxiex.mikuchat.module.command.CommandModule(this));
    }

    /** 构造并登记单个模块；失败时只报告该模块并继续，不影响其余模块。 */
    private void registerModule(String id, java.util.function.Supplier<com.junxiex.mikuchat.core.module.ChatModule> factory) {
        try {
            moduleManager.register(factory.get());
        } catch (Throwable t) {
            getLogger().severe("模块 " + id + " 注册失败，该模块功能将不可用: " + t);
        }
    }

    /** 跨服唯一服务器标识。 */
    public String serverId() {
        return serverId;
    }

    public String defaultChannelId() {
        return defaultChannelId;
    }

    /** 核心配置项缓存，避免在每条消息/每个跨服包上反复查配置。 */
    private void refreshCoreCache() {
        var core = configManager.get("config.yml").config();
        this.serverId = core.getString("server-id", "server-1");
        this.defaultChannelId = core.getString("default-channel", "local");
        this.debugEnabled = core.getBoolean("debug", false);
    }

    public void reloadAll() {
        configManager.reloadAll();
        lang.load(configManager.get("config.yml").config().getString("language", "zh_CN"));
        refreshCoreCache();
        moduleManager.reloadAll();
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public Lang lang() {
        return lang;
    }

    public SchedulerUtil scheduler() {
        return scheduler;
    }

    public ServiceRegistry services() {
        return services;
    }

    public ModuleManager moduleManager() {
        return moduleManager;
    }

    public AsyncFileWriter fileWriter() {
        return fileWriter;
    }

    public com.junxiex.mikuchat.core.command.CommandRegistrar commands() {
        return commandRegistrar;
    }

    public boolean debugEnabled() {
        return debugEnabled;
    }

    public void debug(String message) {
        if (debugEnabled()) {
            getLogger().info("[debug] " + message);
        }
    }
}
