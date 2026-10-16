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

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Folia 调度直连桥 - 隔离层，本类是全插件唯一直接引用
 * {@code io.papermc.paper.threadedregions.scheduler.*} 与
 * {@code Bukkit.getGlobalRegionScheduler() / getAsyncScheduler()} 的位置。
 *
 * <p><b>为什么需要隔离（api-version "1.20" 边界）</b>：plugin.yml 声明 api-version "1.20"，
 * 该声明覆盖 1.20.0 起的全部 1.20.x。而 javap 实证：Paper 1.20.0 捆绑的
 * paper-api 1.20-R0.1（20230613.000034-20）<b>不含</b> threadedregions 调度类，
 * {@code org.bukkit.Bukkit} 上也无 {@code getGlobalRegionScheduler()} /
 * {@code getAsyncScheduler()}；自 paper-api 1.20.1-R0.1（20230921.165944-178）起才提供。
 * 理论上 1.20.0 上 {@link ServerCompat#isFolia()} 恒为 false（Folia 首发 1.20.1，
 * 标志类与调度类同包同 jar 共存亡），Folia 分支不可达、符号必在；
 * 但为把"类/方法缺失"风险压缩到零——无论 JVM 采用何种解析策略、服务端是否为
 * 非常规分支/裁剪构建——所有 Folia 直连调用集中于本类：</p>
 *
 * <ul>
 *   <li><b>类加载惰性</b>：本类仅被 {@link SchedulerAdapter} 的 FOLIA 分支（含句柄 cancel）
 *       触达；非 Folia 服务端上永不加载，其常量池中的 Folia 类型/新版 Bukkit 方法引用
 *       永不进入链接流程。{@link SchedulerAdapter}（全环境必加载）的常量池只含本类的
 *       普通名称，不含任何 Folia 符号。</li>
 *   <li><b>桥签名去 Folia 化</b>：对外方法一律收发 JDK / Bukkit 既有类型与 {@code Object}
 *       （返回值以 {@code Object} 持有 ScheduledTask），调用方记录/传递句柄无需触碰 Folia 类型。</li>
 * </ul>
 *
 * <p><b>失败契约</b>：本类不吞异常——所有可能失败的路径（类加载、方法解析、
 * Folia 运行期 IllegalStateException 等）由调用方 {@link SchedulerAdapter}
 * 以 try-catch(Throwable) 兜底：记 severe + 返回无操作句柄（fail-safe 功能停用），
 * 绝不降级 Bukkit 调度器（真 Folia 上必抛 UnsupportedOperationException）。</p>
 */
final class FoliaSchedulerBridge {

    private FoliaSchedulerBridge() {}

    /**
     * 全局区域线程重复任务：GlobalRegionScheduler.runAtFixedRate。
     *
     * @param plugin      插件实例（Folia 以此绑定任务生命周期）
     * @param task        任务体（包装为 Consumer&lt;ScheduledTask&gt; 后直传）
     * @param delayTicks  首次延迟（tick，调用方已校正 &gt;0）
     * @param periodTicks 重复周期（tick，调用方已校正 &gt;0）
     * @return Folia ScheduledTask（以 Object 持有，取消经 {@link #cancel(Object)}）
     */
    static Object runGlobalAtFixedRate(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();
        return scheduler.runAtFixedRate(plugin, wrap(task), delayTicks, periodTicks);
    }

    /**
     * 全局区域线程延迟一次性任务：GlobalRegionScheduler.runDelayed。
     *
     * @param plugin     插件实例
     * @param task       任务体
     * @param delayTicks 延迟（tick，调用方已校正 &gt;0）
     * @return Folia ScheduledTask（以 Object 持有）
     */
    static Object runGlobalDelayed(Plugin plugin, Runnable task, long delayTicks) {
        GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();
        return scheduler.runDelayed(plugin, wrap(task), delayTicks);
    }

    /**
     * 异步重复任务：AsyncScheduler.runAtFixedRate（毫秒时间单位）。
     *
     * @param plugin       插件实例
     * @param task         任务体
     * @param delayMillis  首次延迟（毫秒，调用方由 tick 换算而来）
     * @param periodMillis 重复周期（毫秒，调用方由 tick 换算而来）
     * @return Folia ScheduledTask（以 Object 持有）
     */
    static Object runAsyncAtFixedRate(Plugin plugin, Runnable task, long delayMillis, long periodMillis) {
        AsyncScheduler scheduler = Bukkit.getAsyncScheduler();
        return scheduler.runAtFixedRate(plugin, wrap(task), delayMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 取消 Folia 任务：ScheduledTask.cancel()。类型不符时抛 ClassCastException，
     * 由调用方（{@link SchedulerAdapter.TaskHandle#cancel()}）捕获并记 warning。
     *
     * @param scheduledTask {@link #runGlobalAtFixedRate} / {@link #runGlobalDelayed} /
     *                      {@link #runAsyncAtFixedRate} 返回的句柄
     */
    static void cancel(Object scheduledTask) {
        ((ScheduledTask) scheduledTask).cancel();
    }

    /**
     * 将无参 Runnable 适配为 Folia 调度所需的 Consumer&lt;ScheduledTask&gt;（丢弃任务自引用参数）。
     */
    private static Consumer<ScheduledTask> wrap(Runnable task) {
        return ignored -> task.run();
    }
}
