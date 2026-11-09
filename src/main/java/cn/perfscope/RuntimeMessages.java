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
package cn.perfscope;

import cn.perfscope.util.Messages;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时层文案：采集器 / 历史持久化 / 调度兼容层的控制台日志（9D Phase 2）。
 *
 * <p>与 {@link CoreMessages} 同构，通过 {@link Messages#registerModule} 注册。这些文案此前是
 * 直接写在 {@code plugin.getLogger()} 调用里的中文字面量——英文环境下控制台会中英混排，
 * 而这些恰恰是故障排查时最需要读懂的部分（采集恢复/失败、写盘失败、Folia 跳过）。</p>
 *
 * <p><b>键名前缀</b>：</p>
 * <ul>
 *   <li>{@code run.collector.*} —— {@code MetricsCollector} 的采集周期/模块健康日志；</li>
 *   <li>{@code run.history.*} —— {@code HistoryFileStore} / {@code HistoryStore} 的写盘、读取、保留日志；</li>
 *   <li>{@code run.compat.*} —— {@code SchedulerAdapter} 的 Folia 调度降级日志。</li>
 * </ul>
 *
 * <p><b>不翻译的内容</b>（刻意保留原文，翻译会让日志失去可检索性）：</p>
 * <ul>
 *   <li>模块 id（{@code tps} / {@code mspt} / {@code entities} / {@code chunks} /
 *       {@code tile_entities} / {@code hotspots}）——它们同时是快照 {@code unavailable} 集合的键；</li>
 *   <li>{@code server(jvm)} 健康槽位名——{@code failingModules()} 用 {@code endsWith("(jvm)")} 归并；</li>
 *   <li>CSV 列名、{@code # v2 } 表头、线程名 {@code perfscope-history-writer}、文件路径与规则 id
 *       （{@code DIAG-MEM-LEAK} 等）；</li>
 *   <li>配置键（{@code general.update_interval}、{@code history.sample-interval-seconds}）与指令名。</li>
 * </ul>
 *
 * <p><b>占位符</b>：数值/时间/路径一律经 {@code {0}}、{@code {1}}……传入，本类不做格式化——
 * 中英文词序不同，只有把值当参数传，翻译才能自由调整语序。</p>
 */
public final class RuntimeMessages {

    static {
        Map<String, String> en = new LinkedHashMap<>();
        Map<String, String> zh = new LinkedHashMap<>();

        // ==================== 采集器 / MetricsCollector ====================
        put(en, zh, "run.collector.reflect-avg-tick-failed",
            "[PerfScope] Failed to resolve getAverageTickTime via reflection; MSPT will be derived from TPS",
            "[PerfScope] getAverageTickTime 反射解析失败，将使用 TPS 反推 MSPT");
        put(en, zh, "run.collector.reflect-te-count-failed",
            "[PerfScope] Failed to resolve getTileEntityCount via reflection; falling back to the Bukkit path",
            "[PerfScope] getTileEntityCount 反射解析失败，将使用 Bukkit 降级路径");
        put(en, zh, "run.collector.invalid-interval",
            "[PerfScope] general.update_interval={0} is invalid (must be >= 1); using 1 tick instead",
            "[PerfScope] general.update_interval={0} 非法（需 ≥1），已按 1 tick 处理");

        // 周期级健康：恢复分"有首次失败时刻"与"无"两种完整句式，
        // 不做字符串拼接——片段拼接在英文里语序会散架
        put(en, zh, "run.collector.cycle-recovered",
            "[PerfScope] Collection recovered (after {0} consecutive failures, first failure {1}, recovered at {2})",
            "[PerfScope] 采集已恢复（此前连续失败 {0} 次，首次失败 {1}，恢复于 {2}）");
        put(en, zh, "run.collector.cycle-recovered-brief",
            "[PerfScope] Collection recovered (after {0} consecutive failures, recovered at {1})",
            "[PerfScope] 采集已恢复（此前连续失败 {0} 次，恢复于 {1}）");
        put(en, zh, "run.collector.cycle-failed",
            "[PerfScope] Collection cycle error (after {0} consecutive failures, first failure {1}): {2}",
            "[PerfScope] 采集周期异常（已连续失败 {0} 次，首次失败 {1}）: {2}");
        put(en, zh, "run.collector.cycle-failed-hint",
            "{0} — data may have stopped updating; see /perfscope status",
            "{0} —— 数据可能已停止更新，/perfscope status 可查");
        put(en, zh, "run.collector.dead",
            "[PerfScope] Collection has failed {0} times in a row; the data shown by /perfscope may be stale. "
                + "Run /perfscope reload, and restart the server if that does not help (use /perfscope status "
                + "to review the most recent error in the meantime)",
            "[PerfScope] 采集已连续失败 {0} 次，/perfscope 显示的数据可能已过期。请执行 /perfscope reload，"
                + "若仍无效请重启服务器（期间可用 /perfscope status 查看最近错误）");
        put(en, zh, "run.collector.jvm-cycle-failed",
            "[PerfScope] JVM async collection cycle error (rate-limited; JVM metrics may be stalled): {0}",
            "[PerfScope] JVM 异步采集周期异常（已限频记录，JVM 指标可能停滞）: {0}");

        // Folia 平台限制：模块 id 与文档路径不翻译
        put(en, zh, "run.collector.folia-skip",
            "[PerfScope] Cross-region aggregation is unreliable on Folia (World.getEntities()/getLoadedChunks() "
                + "and Chunk.getEntities()/getTileEntities() are region-owned, so they may return only some regions "
                + "or throw IllegalStateException); entities / chunks / tile_entities / hotspots have been skipped "
                + "automatically and marked as platform-limited. Use a region-aware approach such as AlmanacAPI "
                + "for this data (see docs/07-guides/COMPAT-MATRIX-VERIFICATION.md)",
            "[PerfScope] Folia 下跨 region 聚合不可靠（World.getEntities()/getLoadedChunks()、"
                + "Chunk.getEntities()/getTileEntities() 均按 region 归属，"
                + "可能只返回部分 region 或抛 IllegalStateException），"
                + "entities / chunks / tile_entities / hotspots 已自动跳过并标记为平台限制；"
                + "需要这些数据请用 AlmanacAPI 等 region 感知方案（说明见 docs/07-guides/COMPAT-MATRIX-VERIFICATION.md）");

        put(en, zh, "run.collector.hotspot-null-slots",
            "[PerfScope] Hotspot scan skipped {0} null entity slot(s) (world={1})",
            "[PerfScope] 热点扫描跳过 {0} 个 null 实体槽位（world={1}）");

        // 模块级健康：同样两种完整句式，避免片段拼接
        put(en, zh, "run.collector.module-recovered",
            "[PerfScope] Collection module recovered: {0} (after {1} consecutive failures, first failure {2})",
            "[PerfScope] 采集模块已恢复: {0}（此前连续失败 {1} 次，首次失败 {2}）");
        put(en, zh, "run.collector.module-recovered-brief",
            "[PerfScope] Collection module recovered: {0} (after {1} consecutive failures)",
            "[PerfScope] 采集模块已恢复: {0}（此前连续失败 {1} 次）");
        put(en, zh, "run.collector.module-failed",
            "[PerfScope] Collection module failed: {0}: {1} (after {2} consecutive failures, first failure {3}; "
                + "identical messages are not repeated within 5 minutes)",
            "[PerfScope] 采集模块失败: {0}: {1}（已连续失败 {2} 次，首次失败 {3}；同类日志 5 分钟内不重复记）");
        put(en, zh, "run.collector.unknown-error", "unknown", "未知");

        // ==================== 历史持久化 / HistoryFileStore + HistoryStore ====================
        put(en, zh, "run.history.dir-create-failed", "Failed to create the history directory", "历史目录创建失败");
        put(en, zh, "run.history.writer-crashed", "History writer thread failed", "历史写线程异常");
        put(en, zh, "run.history.write-failed", "History write failed", "历史写入失败");
        put(en, zh, "run.history.flush-failed", "History flush failed", "历史 flush 失败");
        put(en, zh, "run.history.unknown-reason", "unknown cause", "未知原因");
        // 失败行外壳：{0} = 失败描述（含原因），{1} = 目录绝对路径
        put(en, zh, "run.history.log.failure",
            "[History] {0} (directory: {1})",
            "[历史] {0}（目录: {1}）");
        put(en, zh, "run.history.writer-shutdown-timeout",
            "[History] Writer thread did not exit within {0} ms; {1} sample(s) may be lost (directory: {2})",
            "[历史] 写线程未在 {0} ms 内退出，剩余 {1} 条样本可能丢失（目录: {2}）");
        put(en, zh, "run.history.failure-severe",
            "{0} — {1} consecutive failures; file persistence has stalled, so /perfscope trend can only fall "
                + "back to the in-memory ring",
            "{0} —— 连续失败 {1} 次，文件持久化已停摆；/perfscope trend 只能回退内存环");
        put(en, zh, "run.history.failure-repeated",
            "{0} — {1} consecutive failures",
            "{0} —— 连续失败 {1} 次");
        put(en, zh, "run.history.queue-full",
            "[History] Write queue is full; {0} sample(s) dropped in total (disk cannot keep up with sampling; "
                + "directory: {1})",
            "[历史] 写队列已满，累计丢弃 {0} 条历史样本（写盘速度跟不上采样；目录: {1}）");
        put(en, zh, "run.history.enumerate-failed",
            "[History] Failed to list history files: {0}",
            "[历史] 历史文件枚举失败: {0}");
        put(en, zh, "run.history.read-failed",
            "[History] Failed to read history file (skipped): {0}: {1}",
            "[历史] 历史文件读取失败（已跳过）: {0}: {1}");
        put(en, zh, "run.history.delete-failed",
            "[History] Failed to delete old history file: {0}: {1}",
            "[历史] 旧历史文件删除失败: {0}: {1}");
        put(en, zh, "run.history.retention-failed",
            "[History] History cleanup failed: {0}",
            "[历史] 历史清理失败: {0}");
        put(en, zh, "run.history.interval-too-large",
            "[History] history.sample-interval-seconds={0} is too large: a 30-minute diagnostic window holds "
                + "at most {1} sample(s), so trend rules such as DIAG-MEM-LEAK / DIAG-TPS-PERSIST will keep being "
                + "skipped for lack of samples (MIN_SAMPLES=10). Keep it at 60 seconds or less.",
            "[历史] history.sample-interval-seconds={0} 过大：30 分钟诊断窗口内最多只有 {1} 条样本，"
                + "DIAG-MEM-LEAK / DIAG-TPS-PERSIST 等趋势规则会因样本不足（MIN_SAMPLES=10）长期跳过。"
                + "建议保持 ≤60 秒。");

        // ==================== 调度兼容层 / SchedulerAdapter ====================
        put(en, zh, "run.compat.folia-scheduler-unavailable",
            "[PerfScope] Folia scheduler unavailable; the affected features are disabled: {0}",
            "[PerfScope] Folia 调度不可用，相关功能停用：{0}");
        put(en, zh, "run.compat.folia-cancel-failed",
            "[PerfScope] Failed to cancel the Folia task: {0}",
            "[PerfScope] 取消 Folia 任务失败: {0}");
        // f-s02-contract-04：取消路径的统一日志键（Bukkit / Folia 两条分支共用，
        // 带异常类型以便区分"任务已结束"与"调度器内部错误"）。
        put(en, zh, "run.compat.task-cancel-failed",
            "[PerfScope] Failed to cancel a scheduled task (swallowed, the caller continues): {0}",
            "[PerfScope] 取消定时任务失败（已吞掉异常，调用方继续执行）: {0}");

        Messages.registerModule(en, zh);
    }

    /** 触发本类静态初始化（由 {@code Messages.onFirstLoad} 登记）。 */
    public static void init() {
        // 空方法体：调用它即触发 static 块
    }

    private static void put(Map<String, String> en, Map<String, String> zh,
                            String key, String enValue, String zhValue) {
        en.put(key, enValue);
        zh.put(key, zhValue);
    }

    private RuntimeMessages() {}
}
