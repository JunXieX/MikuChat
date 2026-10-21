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
        // 当前线程已拥有该实体所属区域时直接内联执行：Paper（非 Folia）主线程、Folia 同区域线程
        // 都满足此条件，可省去一次任务入队与调度开销（聊天投递对每个接收者都会走这里）。
        // 异步线程或其它区域仍需交给实体调度器。
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            // 内联执行没有调度器兜底：任务异常不能冒泡打乱调用方（如某个接收者发送失败导致
            // 后续接收者/跨服广播/留档全部中断），因此这里自行隔离并记录。
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "实体内联任务执行失败", t);
            }
            return;
        }
        entity.getScheduler().run(plugin, t -> task.run(), null);
    }

    public void atEntityLater(Entity entity, Runnable task, long delayTicks) {
        entity.getScheduler().runDelayed(plugin, t -> task.run(), null, Math.max(1L, delayTicks));
    }
}
