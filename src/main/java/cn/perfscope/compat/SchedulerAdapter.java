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

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 调度适配层 - 屏蔽 Bukkit 与 Folia 的调度器差异，为定时/延迟任务提供统一入口。
 *
 * <p>本类为<b>编译期直连</b>实现：编译依赖 paper-api 1.20.4 本就包含 Folia 调度类
 * （io.papermc.paper.threadedregions.scheduler.*，自 paper-api 1.20.1 起提供，javap 实证），
 * 且 org.bukkit.Bukkit 上有静态方法 getGlobalRegionScheduler() / getAsyncScheduler()，
 * 因此 Folia 分支不再需要任何反射（旧版"编译依赖不含 Folia 类、必须全反射"的前提系误判，已删除）。
 * 反射版的实际代价（无法编译期类型检查、无法测试覆盖、失败即静默停用）随之消除。</p>
 *
 * <p><b>惰性隔离（防御 api-version "1.20" 的边界）</b>：plugin.yml 声明 api-version "1.20"，
 * 而 Paper 1.20.0 捆绑的 paper-api 1.20-R0.1（javap 实证）<b>不含</b> threadedregions 调度类、
 * Bukkit 上也无 getGlobalRegionScheduler() / getAsyncScheduler()。虽然该版本
 * {@link ServerCompat#isFolia()} 恒为 false（Folia 首发版本为 1.20.1，其 folia-api 已含全部类），
 * 激活分支与符号存在性在真实环境恒一致；但为使"类缺失/方法缺失"在任何 JVM 解析策略
 * （含急切解析实现）下都绝不波及非 Folia 环境，全部 Folia 直连调用集中在
 * {@link FoliaSchedulerBridge}——该类仅在 FOLIA 分支被触达，非 Folia 服务端上永不加载，
 * 其常量池中的 Folia 类型/新版 Bukkit 方法引用永不进入链接流程。本类（全环境必加载）
 * 常量池内不含任何 Folia 符号。</p>
 *
 * <p>分支判定：{@link ServerCompat#isFolia()} 为 true 走 Folia
 * GlobalRegionScheduler / AsyncScheduler（经桥直连）；否则走 Bukkit 调度器（直接 API）。</p>
 *
 * <p>fail-safe 语义与旧反射版逐字保留：Folia 分支任何失败（含 NoClassDefFoundError /
 * NoSuchMethodError 等 LinkageError）<b>一律记 severe 日志并返回无操作句柄（功能停用），
 * 绝不降级 Bukkit 调度器</b>——真 Folia 上 Bukkit 调度器 API 必抛 UnsupportedOperationException，
 * 降级即等于插件 enable 被打挂（compat-matrix CM-2 / R15 / R17 实证地雷）。
 * Folia 分支调用包裹 try-catch(Throwable)，异常不抛给调用方。tick→毫秒换算仅在本类内部进行，
 * 调用方一律使用 tick。</p>
 */
public final class SchedulerAdapter {

    /** 是否运行在 Folia 服务端（启动时快照）。 */
    private static final boolean FOLIA = ServerCompat.isFolia();

    private SchedulerAdapter() {}

    // ==================== 公共调度 API ====================

    /**
     * 全局重复同步任务。
     * 非 Folia：Bukkit runTaskTimer（主线程）；Folia：GlobalRegionScheduler.runAtFixedRate（全局区域线程，直连）。
     *
     * @param plugin      插件实例
     * @param task        任务体
     * @param delayTicks  首次延迟（tick）；Folia 下 &lt;=0 自动校正为 1
     * @param periodTicks 重复周期（tick）；Folia 下 &lt;=0 自动校正为 1
     * @return 统一任务句柄，可 cancel
     */
    public static TaskHandle runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (FOLIA) {
            // T5（CM-2 拆雷）：Folia 分支失败 = 功能停用（fail-safe）；绝不降级 Bukkit——真 Folia 上必抛 UOE
            try {
                long d = delayTicks <= 0 ? 1 : delayTicks;   // Folia 不接受 0
                long p = periodTicks <= 0 ? 1 : periodTicks;
                Object scheduledTask = FoliaSchedulerBridge.runGlobalAtFixedRate(plugin, task, d, p);
                return new TaskHandle(scheduledTask, plugin);
            } catch (Throwable t) {
                plugin.getLogger().severe("[PerfScope] Folia 调度不可用，相关功能停用：" + describe(t));
                return TaskHandle.noop();
            }
        }
        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return new TaskHandle(bukkitTask);
    }

    /**
     * 异步重复任务。
     * 非 Folia：runTaskTimerAsynchronously；Folia：AsyncScheduler.runAtFixedRate（tick 换算毫秒，1 tick = 50ms，直连）。
     *
     * @param plugin      插件实例
     * @param task        任务体
     * @param delayTicks  首次延迟（tick）；Folia 下 &lt;=0 校正为 1 tick
     * @param periodTicks 重复周期（tick）；Folia 下 &lt;=0 校正为 1 tick
     * @return 统一任务句柄，可 cancel
     */
    public static TaskHandle runAsyncTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (FOLIA) {
            // T5（CM-2 拆雷）：Folia 分支失败 = 功能停用（fail-safe）；绝不降级 Bukkit——真 Folia 上必抛 UOE
            try {
                long dMs = (delayTicks <= 0 ? 1 : delayTicks) * 50L;    // tick → 毫秒
                long pMs = (periodTicks <= 0 ? 1 : periodTicks) * 50L;
                Object scheduledTask = FoliaSchedulerBridge.runAsyncAtFixedRate(plugin, task, dMs, pMs);
                return new TaskHandle(scheduledTask, plugin);
            } catch (Throwable t) {
                plugin.getLogger().severe("[PerfScope] Folia 调度不可用，相关功能停用：" + describe(t));
                return TaskHandle.noop();
            }
        }
        BukkitTask bukkitTask = plugin.getServer().getScheduler()
            .runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
        return new TaskHandle(bukkitTask);
    }

    /**
     * 延迟一次性任务（非重复）。
     * 非 Folia：runTaskLater；Folia：GlobalRegionScheduler.runDelayed（直连）。
     *
     * @param plugin     插件实例
     * @param task       任务体
     * @param delayTicks 延迟（tick）；Folia 下 &lt;=0 校正为 1
     * @return 统一任务句柄，可 cancel
     */
    public static TaskHandle runLater(Plugin plugin, Runnable task, long delayTicks) {
        if (FOLIA) {
            // T5（CM-2 拆雷）：Folia 分支失败 = 功能停用（fail-safe）；绝不降级 Bukkit——真 Folia 上必抛 UOE
            try {
                long d = delayTicks <= 0 ? 1 : delayTicks;
                Object scheduledTask = FoliaSchedulerBridge.runGlobalDelayed(plugin, task, d);
                return new TaskHandle(scheduledTask, plugin);
            } catch (Throwable t) {
                plugin.getLogger().severe("[PerfScope] Folia 调度不可用，相关功能停用：" + describe(t));
                return TaskHandle.noop();
            }
        }
        BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
        return new TaskHandle(bukkitTask);
    }

    // ==================== 内部工具 ====================

    /**
     * fail-safe 日志原因描述：单行 "类名: 消息"，保证缺失类/缺失方法等 LinkageError
     * 在日志中可读。直连后不再有 InvocationTargetException 需要拆包（旧反射版的
     * 拆包逻辑随反射一并删除）。
     */
    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    // ==================== 统一任务句柄 ====================

    /**
     * 统一任务句柄：对内封装 Bukkit {@link BukkitTask} 或 Folia ScheduledTask
     * （以 Object 持有并经 {@link FoliaSchedulerBridge} 直连操作，避免本类常量池引用 Folia 类型），
     * 对外仅暴露 {@link #cancel()}。
     */
    public static final class TaskHandle {

        /** 非 Folia 路径句柄 */
        private final BukkitTask bukkitTask;
        /** Folia 路径句柄（ScheduledTask，以 Object 持有） */
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

        private TaskHandle() {
            this.bukkitTask = null;
            this.foliaTask = null;
            this.plugin = null;
        }

        /**
         * T5：无操作句柄——Folia 分支失败（fail-safe 功能停用）时返回，
         * {@link #cancel()} 为空操作；创建时的 severe 日志已说明停用原因。
         */
        static TaskHandle noop() {
            return new TaskHandle();
        }

        /**
         * 取消任务。Bukkit 路径直接 cancel；Folia 路径经 {@link FoliaSchedulerBridge}
         * 直连 ScheduledTask.cancel()。句柄为空（调度失败/无操作句柄）时为无操作；
         * 取消异常被吞并记 warning，不抛给调用方。
         */
        public void cancel() {
            if (bukkitTask != null) {
                bukkitTask.cancel();
                return;
            }
            if (foliaTask != null) {
                try {
                    FoliaSchedulerBridge.cancel(foliaTask);
                } catch (Throwable t) {
                    if (plugin != null) {
                        plugin.getLogger().warning("[PerfScope] 取消 Folia 任务失败: " + t.getMessage());
                    }
                }
            }
        }
    }
}
