/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package cn.perfscope.compat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 调度适配层 - 屏蔽 Bukkit 与 Folia 的调度器差异，为定时/延迟任务提供统一入口。
 *
 * <p>编译依赖 paper-api 1.20.4 不含 Folia 调度类
 * （io.papermc.paper.threadedregions.scheduler.*），因此 Folia 分支的所有调用
 * 一律通过反射完成，严禁直接 import Folia 类，否则编译失败。</p>
 *
 * <p>分支判定：{@link ServerCompat#isFolia()} 为 true 走 Folia
 * GlobalRegionScheduler / AsyncScheduler（反射）；否则走 Bukkit 调度器（直接 API）。</p>
 *
 * <p>所有反射方法在类加载时解析缓存为 static final，解析失败置 null；Folia 分支调用时
 * 若发现反射未就绪，记 warning 并降级为 Bukkit 兜底。全部反射调用包裹 try-catch，
 * 异常不抛给调用方。tick→毫秒换算仅在本类内部进行，调用方一律使用 tick。</p>
 */
public final class SchedulerAdapter {

    /** 是否运行在 Folia 服务端（启动时快照）。 */
    private static final boolean FOLIA;

    // ==================== Folia 反射缓存（非 Folia 环境恒为 null，不会被使用） ====================

    /** Bukkit.getGlobalRegionScheduler() */
    private static final Method GLOBAL_SCHEDULER_GETTER;
    /** Bukkit.getAsyncScheduler() */
    private static final Method ASYNC_SCHEDULER_GETTER;
    /** GlobalRegionScheduler.runAtFixedRate(Plugin, Consumer, long delayTicks, long periodTicks) */
    private static final Method GLOBAL_RUN_AT_FIXED_RATE;
    /** GlobalRegionScheduler.runDelayed(Plugin, Consumer, long delayTicks) */
    private static final Method GLOBAL_RUN_DELAYED;
    /** AsyncScheduler.runAtFixedRate(Plugin, Consumer, long delay, long period, TimeUnit) */
    private static final Method ASYNC_RUN_AT_FIXED_RATE;
    /** ScheduledTask.cancel() */
    private static final Method SCHEDULED_TASK_CANCEL;

    static {
        FOLIA = ServerCompat.isFolia();

        Method globalGetter = null;
        Method asyncGetter = null;
        Method globalFixed = null;
        Method globalDelayed = null;
        Method asyncFixed = null;
        Method taskCancel = null;

        // 仅在 Folia 环境解析 Folia 调度反射；非 Folia 环境保持 null
        if (FOLIA) {
            try {
                Class<?> globalSchedulerClass =
                    Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
                Class<?> asyncSchedulerClass =
                    Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
                Class<?> scheduledTaskClass =
                    Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");

                globalGetter = Bukkit.class.getMethod("getGlobalRegionScheduler");
                asyncGetter = Bukkit.class.getMethod("getAsyncScheduler");

                globalFixed = globalSchedulerClass.getMethod(
                    "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
                globalDelayed = globalSchedulerClass.getMethod(
                    "runDelayed", Plugin.class, Consumer.class, long.class);
                asyncFixed = asyncSchedulerClass.getMethod(
                    "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);

                taskCancel = scheduledTaskClass.getMethod("cancel");
            } catch (Throwable t) {
                // 任一解析失败则全部视为未就绪（保持 null），调用时降级 Bukkit 兜底
            }
        }

        GLOBAL_SCHEDULER_GETTER = globalGetter;
        ASYNC_SCHEDULER_GETTER = asyncGetter;
        GLOBAL_RUN_AT_FIXED_RATE = globalFixed;
        GLOBAL_RUN_DELAYED = globalDelayed;
        ASYNC_RUN_AT_FIXED_RATE = asyncFixed;
        SCHEDULED_TASK_CANCEL = taskCancel;
    }

    private SchedulerAdapter() {}

    // ==================== 公共调度 API ====================

    /**
     * 全局重复同步任务。
     * 非 Folia：Bukkit runTaskTimer（主线程）；Folia：GlobalRegionScheduler.runAtFixedRate（全局区域线程）。
     *
     * @param plugin      插件实例
     * @param task        任务体
     * @param delayTicks  首次延迟（tick）；Folia 下 &lt;=0 自动校正为 1
     * @param periodTicks 重复周期（tick）；Folia 下 &lt;=0 自动校正为 1
     * @return 统一任务句柄，可 cancel
     */
    public static TaskHandle runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (useFolia(plugin, GLOBAL_SCHEDULER_GETTER, GLOBAL_RUN_AT_FIXED_RATE)) {
            try {
                long d = delayTicks <= 0 ? 1 : delayTicks;   // Folia 不接受 0
                long p = periodTicks <= 0 ? 1 : periodTicks;
                Object scheduler = GLOBAL_SCHEDULER_GETTER.invoke(null);
                Object scheduledTask = GLOBAL_RUN_AT_FIXED_RATE.invoke(scheduler, plugin, wrap(task), d, p);
                return new TaskHandle(scheduledTask, plugin);
            } catch (Throwable t) {
                plugin.getLogger().warning("[PerfScope] Folia 全局定时任务调度失败，降级为 Bukkit: " + t.getMessage());
            }
        }
        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return new TaskHandle(bukkitTask);
    }

    /**
     * 异步重复任务。
     * 非 Folia：runTaskTimerAsynchronously；Folia：AsyncScheduler.runAtFixedRate（tick 换算毫秒，1 tick = 50ms）。
     *
     * @param plugin      插件实例
     * @param task        任务体
     * @param delayTicks  首次延迟（tick）；Folia 下 &lt;=0 校正为 1 tick
     * @param periodTicks 重复周期（tick）；Folia 下 &lt;=0 校正为 1 tick
     * @return 统一任务句柄，可 cancel
     */
    public static TaskHandle runAsyncTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (useFolia(plugin, ASYNC_SCHEDULER_GETTER, ASYNC_RUN_AT_FIXED_RATE)) {
            try {
                long dMs = (delayTicks <= 0 ? 1 : delayTicks) * 50L;    // tick → 毫秒
                long pMs = (periodTicks <= 0 ? 1 : periodTicks) * 50L;
                Object scheduler = ASYNC_SCHEDULER_GETTER.invoke(null);
                Object scheduledTask = ASYNC_RUN_AT_FIXED_RATE.invoke(
                    scheduler, plugin, wrap(task), dMs, pMs, TimeUnit.MILLISECONDS);
                return new TaskHandle(scheduledTask, plugin);
            } catch (Throwable t) {
                plugin.getLogger().warning("[PerfScope] Folia 异步定时任务调度失败，降级为 Bukkit: " + t.getMessage());
            }
        }
        BukkitTask bukkitTask = plugin.getServer().getScheduler()
            .runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
        return new TaskHandle(bukkitTask);
    }

    /**
     * 延迟一次性任务（非重复）。
     * 非 Folia：runTaskLater；Folia：GlobalRegionScheduler.runDelayed。
     *
     * @param plugin     插件实例
     * @param task       任务体
     * @param delayTicks 延迟（tick）；Folia 下 &lt;=0 校正为 1
     * @return 统一任务句柄，可 cancel
     */
    public static TaskHandle runLater(Plugin plugin, Runnable task, long delayTicks) {
        if (useFolia(plugin, GLOBAL_SCHEDULER_GETTER, GLOBAL_RUN_DELAYED)) {
            try {
                long d = delayTicks <= 0 ? 1 : delayTicks;
                Object scheduler = GLOBAL_SCHEDULER_GETTER.invoke(null);
                Object scheduledTask = GLOBAL_RUN_DELAYED.invoke(scheduler, plugin, wrap(task), d);
                return new TaskHandle(scheduledTask, plugin);
            } catch (Throwable t) {
                plugin.getLogger().warning("[PerfScope] Folia 延迟任务调度失败，降级为 Bukkit: " + t.getMessage());
            }
        }
        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
        return new TaskHandle(bukkitTask);
    }

    // ==================== 内部工具 ====================

    /**
     * 判定本次调用是否应走 Folia 分支：既是 Folia 环境，且所需反射均已就绪。
     * Folia 环境但反射缺失时记 warning，返回 false 以降级 Bukkit 兜底。
     */
    private static boolean useFolia(Plugin plugin, Method getter, Method action) {
        if (!FOLIA) {
            return false;
        }
        if (getter != null && action != null) {
            return true;
        }
        plugin.getLogger().warning("[PerfScope] Folia 调度反射未就绪，降级为 Bukkit 调度器");
        return false;
    }

    /**
     * 将无参 Runnable 适配为 Folia 调度所需的 Consumer&lt;ScheduledTask&gt;。
     * 泛型运行时被擦除，反射传入原始 Consumer 即可。
     */
    private static Consumer<Object> wrap(Runnable task) {
        return ignored -> task.run();
    }

    // ==================== 统一任务句柄 ====================

    /**
     * 统一任务句柄：对内封装 Bukkit {@link BukkitTask} 或 Folia ScheduledTask（Object + 反射），
     * 对外仅暴露 {@link #cancel()}。
     */
    public static final class TaskHandle {

        /** 非 Folia 路径句柄 */
        private final BukkitTask bukkitTask;
        /** Folia 路径句柄（ScheduledTask，反射操作） */
        private final Object foliaTask;
        /** 用于日志（Folia 路径非空） */
        private final Plugin plugin;

        private TaskHandle(BukkitTask bukkitTask) {
            this.bukkitTask = bukkitTask;
            this.foliaTask = null;
            this.plugin = null;
        }

        private TaskHandle(Object foliaTask, Plugin plugin) {
            this.bukkitTask = null;
            this.foliaTask = foliaTask;
            this.plugin = plugin;
        }

        /**
         * 取消任务。Bukkit 路径直接 cancel；Folia 路径反射调用 ScheduledTask.cancel()。
         * 句柄为空（调度失败）时为无操作；反射异常被吞并记 warning，不抛给调用方。
         */
        public void cancel() {
            if (bukkitTask != null) {
                bukkitTask.cancel();
                return;
            }
            if (foliaTask != null && SCHEDULED_TASK_CANCEL != null) {
                try {
                    SCHEDULED_TASK_CANCEL.invoke(foliaTask);
                } catch (Throwable t) {
                    if (plugin != null) {
                        plugin.getLogger().warning("[PerfScope] 取消 Folia 任务失败: " + t.getMessage());
                    }
                }
            }
        }
    }
}
