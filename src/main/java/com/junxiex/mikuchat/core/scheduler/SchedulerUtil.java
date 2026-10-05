package com.junxiex.mikuchat.core.scheduler;

import com.junxiex.mikuchat.MikuChat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.util.concurrent.TimeUnit;

/**
 * 调度器统一封装：在 Paper 上走主线程/异步，在 Folia 上走区域化调度，一套实现两端通用。
 * 给玩家发消息、播声音、发 ActionBar 一律使用 {@link #atEntity}，线程安全。
 */
public final class SchedulerUtil {

    private final MikuChat plugin;

    public SchedulerUtil(MikuChat plugin) {
        this.plugin = plugin;
    }

    public void async(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    public void asyncLater(Runnable task, long delayTicks) {
        Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), delayTicks * 50L, TimeUnit.MILLISECONDS);
    }

    public io.papermc.paper.threadedregions.scheduler.ScheduledTask asyncTimer(Runnable task, long initialTicks, long periodTicks) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> task.run(),
                Math.max(1L, initialTicks) * 50L, Math.max(1L, periodTicks) * 50L, TimeUnit.MILLISECONDS);
    }

    public void global(Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
    }

    public void atEntity(Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, t -> task.run(), null);
    }

    public void atEntityLater(Entity entity, Runnable task, long delayTicks) {
        entity.getScheduler().runDelayed(plugin, t -> task.run(), null, Math.max(1L, delayTicks));
    }
}
