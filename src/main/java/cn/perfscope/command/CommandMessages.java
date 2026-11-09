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
package cn.perfscope.command;

import cn.perfscope.util.Messages;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令面板文案表（9D Phase 1）。
 *
 * <p>本类只承载 {@code /perfscope} 各面板的<b>正文</b>文案：标签、数值行、空态、守卫提示、帮助行。
 * 段落标题（{@code === ... ===}）已在 {@link Messages} 的内置表里（{@code cmd.section.*}），
 * 不在这里重复登记。</p>
 *
 * <p><b>键名规则</b>（与命令层其它改造口径一致）：</p>
 * <ul>
 *   <li>{@code cmd.<面板>.<元素>}：面板私有文案，如 {@code cmd.cpu.process-label}、
 *       {@code cmd.memory.heap-line}。面板名取子命令名（{@code all}/{@code tps}/{@code status}/…）。</li>
 *   <li>{@code cmd.state.*}：跨面板共用的状态词（不可用 / 预热中 / 未知 / 未采集 / 已禁用 / 正常 …）。
 *       "不可用"在命令层出现四十余次，这里只有<b>一条</b>键——改一处即全界面一致。</li>
 *   <li>{@code cmd.unit.*}：跨面板共用的计量单位名（TPS / MSPT / 玩家 / 实体 / 内存 / 区块），
 *       供 ActionBar 这类需要拼接的复合行取用。</li>
 *   <li>{@code cmd.label.*}：跨面板共用的行标签（按世界分布 / 最低 TPS / 峰值内存 / 时间括号 …）。</li>
 *   <li>{@code cmd.help.<子命令>.display}：帮助行的用法文本；{@code .desc}：描述文本。</li>
 *   <li>{@code cmd.error.*} / {@code cmd.advice.*}：守卫提示与建议。</li>
 * </ul>
 *
 * <p><b>为什么文案里带 {@code {0}} 占位符而不是在代码里拼接</b>：同一句话在两种语言里词序不同
 * （"已停止更新 30 秒（最后采集 12:00:00）" vs "Stopped updating 30s ago (last collection 12:00:00)"），
 * 只有把数值当参数传，译文才能自由调整语序与括号形态。</p>
 *
 * <p><b>注册时机</b>：静态块调用 {@link Messages#registerModule}；{@code PerfScope} 在
 * {@code Messages.load} 之前用 {@code Messages.onFirstLoad(CommandMessages::init)} 登记本类，
 * 保证首次加载时命令层的键就已在默认表里。{@link PerfScopeCommand} 的静态块也会<b>再</b>触碰一次
 * （{@code registerModule} 是幂等的），这样即便将来有人漏掉登记行，键也不会静默丢失。</p>
 */
public final class CommandMessages {

    static {
        Map<String, String> en = new LinkedHashMap<>();
        Map<String, String> zh = new LinkedHashMap<>();

        // ==================== 跨面板：状态词 ====================
        put(en, zh, "cmd.state.unavailable", "Unavailable", "不可用");
        put(en, zh, "cmd.state.warming-up", "Warming up", "预热中");
        put(en, zh, "cmd.state.warming-up-long",
            "Warming up (first collection not ready; updates automatically)",
            "预热中（首轮采集未就绪，稍候自动更新）");
        put(en, zh, "cmd.state.unknown", "Unknown", "未知");
        put(en, zh, "cmd.state.not-collected", "Not sampled", "未采集");
        put(en, zh, "cmd.state.disabled", "Disabled", "已禁用");
        put(en, zh, "cmd.state.enabled", "Enabled", "已启用");
        put(en, zh, "cmd.state.available", "Available", "可用");
        put(en, zh, "cmd.state.ok", "OK", "正常");
        put(en, zh, "cmd.state.no-valid-samples", "No valid samples", "无有效样本");
        put(en, zh, "cmd.state.no-alerts", "No alerts", "无告警");
        put(en, zh, "cmd.state.no-criticals", "No critical alerts", "无严重告警");

        // ==================== 跨面板：单位 / 行标签 ====================
        put(en, zh, "cmd.unit.tps", "TPS", "TPS");
        put(en, zh, "cmd.unit.mspt", "MSPT", "MSPT");
        put(en, zh, "cmd.unit.players", "Players", "玩家");
        put(en, zh, "cmd.unit.entities", "Entities", "实体");
        put(en, zh, "cmd.unit.memory", "Memory", "内存");
        put(en, zh, "cmd.unit.chunks", "Chunks", "区块");

        put(en, zh, "cmd.label.by-world", "By world:", "按世界分布:");
        put(en, zh, "cmd.label.avg-tps", "Average TPS: ", "平均 TPS: ");
        put(en, zh, "cmd.label.min-tps", "Lowest TPS: ", "最低 TPS: ");
        put(en, zh, "cmd.label.peak-entities", "Peak entities: ", "峰值实体: ");
        put(en, zh, "cmd.label.peak-heap", "Peak memory: ", "峰值内存: ");
        // 时间后缀：中文用全角括号，英文用半角圆括号——括号形态属于语言，必须随语言切换
        put(en, zh, "cmd.label.timestamp-paren", " ({0})", "（{0}）");

        // ==================== /perfscope（全量诊断） ====================
        put(en, zh, "cmd.all.server-label", "Server: ", "服务端: ");
        // Folia 口径说明拆成两条完整文案（而非半句拼接）：中英文的括号与分号位置不同，拼接会译不自然
        put(en, zh, "cmd.all.folia-scope",
            "  [TPS=global region local value]", "  [TPS=global region 局部值]");
        put(en, zh, "cmd.all.folia-scope-mspt",
            "  [TPS=global region local value; MSPT unavailable]",
            "  [TPS=global region 局部值；MSPT 不可用]");
        put(en, zh, "cmd.all.collect-time-label", "Sampled at: ", "采集时间: ");
        put(en, zh, "cmd.all.section.server",
            "--- Server Diagnostics ---", "--- 服务器级诊断 ---");
        put(en, zh, "cmd.all.section.alerts", "--- Active Alerts ---", "--- 活跃告警 ---");
        put(en, zh, "cmd.all.world-label", "  World: ", "  世界: ");
        put(en, zh, "cmd.all.entities-inline", " | Entities: ", " | 实体: ");
        put(en, zh, "cmd.all.chunks-inline", " | Chunks: ", " | 区块: ");
        put(en, zh, "cmd.all.force-inline", " | Force-loaded: ", " | 强加载: ");
        put(en, zh, "cmd.all.tile-entities-inline", " | Tile entities: ", " | 方块实体: ");
        put(en, zh, "cmd.all.threads-inline", " | Threads: ", " | 线程: ");
        put(en, zh, "cmd.all.plugins-inline", " | Plugins: ", " | 插件: ");
        put(en, zh, "cmd.all.worlds-inline", " | Worlds: ", " | 世界: ");

        // ==================== TPS ====================
        put(en, zh, "cmd.tps.label", "TPS: ", "TPS: ");
        put(en, zh, "cmd.tps.triple-label", "TPS (1m/5m/15m): ", "TPS (1m/5m/15m): ");
        put(en, zh, "cmd.tps.scope-note",
            "Scope: TPS is the global region's local value, not server-wide throughput",
            "口径: TPS 为 global region 局部值，非全服吞吐");
        put(en, zh, "cmd.tps.trend-title", "TPS trend (last {0} samples):", "TPS 趋势 (最近{0}次采样):");
        put(en, zh, "cmd.tps.equivalent-mspt", "Current equivalent MSPT: {0}ms", "当前等效 MSPT: {0}ms");
        put(en, zh, "cmd.tps.equivalent-mspt-unresponsive",
            "Current equivalent MSPT: server unresponsive (TPS=0, the main thread is likely blocked)",
            "当前等效 MSPT: 服务器无响应（TPS=0，主线程可能已完全阻塞）");

        // ==================== MSPT ====================
        put(en, zh, "cmd.mspt.label", "MSPT: ", "MSPT: ");
        put(en, zh, "cmd.mspt.triple-label", "MSPT (avg/min/max): ", "MSPT (avg/min/max): ");
        put(en, zh, "cmd.mspt.estimated-tag", " (estimated)", " (估算)");
        put(en, zh, "cmd.mspt.avg-label", "Average MSPT: ", "平均 MSPT: ");
        put(en, zh, "cmd.mspt.min-label", "Minimum MSPT: ", "最小 MSPT: ");
        put(en, zh, "cmd.mspt.max-label", "Maximum MSPT: ", "最大 MSPT: ");
        put(en, zh, "cmd.mspt.max-tps-label", "Theoretical max TPS: ", "理论最大 TPS: ");

        // ==================== 实体 ====================
        put(en, zh, "cmd.entities.total-label", "Total entities: ", "总实体数: ");
        put(en, zh, "cmd.entities.by-type-world",
            "By type (Top 15, world: {0}):", "按类型分布 (Top 15, 世界: {0}):");
        put(en, zh, "cmd.entities.by-type-global",
            "By type (server-wide, Top 15):", "按类型分布 (全服, Top 15):");
        put(en, zh, "cmd.entities.scan-failed",
            "  Read failed: unable to scan entities in this world",
            "  读取失败: 无法扫描该世界实体");
        put(en, zh, "cmd.entities.critical-warning",
            "Critical: total entity count exceeds the critical threshold!",
            "严重警告: 实体总数超过严重阈值!");
        put(en, zh, "cmd.entities.warning",
            "Warning: total entity count exceeds the warning threshold",
            "警告: 实体总数超过警告阈值");

        // ==================== 区块 ====================
        put(en, zh, "cmd.chunks.total-label", "Total loaded chunks: ", "总加载区块: ");
        put(en, zh, "cmd.chunks.force-total-label", "Total force-loaded chunks: ", "总强加载区块: ");
        put(en, zh, "cmd.chunks.loaded-label", "Loaded chunks: ", "加载区块: ");
        put(en, zh, "cmd.chunks.count", "{0} chunks", "{0} 个");
        put(en, zh, "cmd.chunks.force-inline", " (force-loaded: {0})", " (强加载: {0})");

        // ==================== 内存 ====================
        put(en, zh, "cmd.memory.label", "Memory: ", "内存: ");
        put(en, zh, "cmd.memory.heap-label", "Heap: ", "堆内存: ");
        put(en, zh, "cmd.memory.heap-line", "{0}MB / {1}MB", "{0}MB / {1}MB");
        put(en, zh, "cmd.memory.committed-label", "Allocated heap: ", "已分配堆内存: ");
        put(en, zh, "cmd.memory.nonheap-label", "Non-heap: ", "非堆内存: ");
        put(en, zh, "cmd.memory.nonheap-max-label", "Non-heap max: ", "非堆内存上限: ");
        put(en, zh, "cmd.memory.critical-warning",
            "Critical: heap usage exceeds {0}%!", "严重警告: 堆内存使用率超过 {0}%！");
        put(en, zh, "cmd.memory.critical-advice",
            "Advice: increase the maximum server memory (-Xmx) or check for memory leaks.",
            "建议: 增加服务器最大内存 (-Xmx) 或检查是否有内存泄漏。");

        // ==================== CPU ====================
        put(en, zh, "cmd.cpu.label", "CPU: ", "CPU: ");
        put(en, zh, "cmd.cpu.process-label", "Process CPU usage: ", "进程 CPU 使用率: ");
        put(en, zh, "cmd.cpu.system-label", "System CPU load: ", "系统 CPU 负载: ");
        put(en, zh, "cmd.cpu.process-unknown", "Process unknown", "进程 未知");
        put(en, zh, "cmd.cpu.system-unknown", "System unknown", "系统 未知");
        put(en, zh, "cmd.cpu.process-value", "Process {0}%", "进程 {0}%");
        put(en, zh, "cmd.cpu.system-value", "System {0}%", "系统 {0}%");
        put(en, zh, "cmd.cpu.processors-label", "Available processors: ", "可用处理器: ");
        put(en, zh, "cmd.cpu.threads-label", "Active threads: ", "活跃线程数: ");
        put(en, zh, "cmd.cpu.jvm-threads-inline", " (JVM: {0})", " (JVM: {0})");

        // ==================== 玩家 ====================
        put(en, zh, "cmd.players.online-label", "Players online: ", "在线玩家: ");
        put(en, zh, "cmd.players.list-title", "Online players:", "在线玩家列表:");

        // ==================== 世界 ====================
        put(en, zh, "cmd.world.click-detail", "Click to view details for {0}", "点击查看 {0} 的详细信息");
        put(en, zh, "cmd.world.dimension-label", "Dimension: ", "维度: ");
        put(en, zh, "cmd.world.entities-label", "Entities: ", "实体数: ");
        put(en, zh, "cmd.world.force-loaded-label", "Force-loaded chunks: ", "强加载区块: ");
        put(en, zh, "cmd.world.tile-entities-label", "Tile entities: ", "Tile Entity: ");
        put(en, zh, "cmd.world.players-label", "Players in world: ", "世界内玩家: ");
        put(en, zh, "cmd.world.threshold-label", "Entity alert thresholds: ", "实体告警阈值: ");
        put(en, zh, "cmd.world.thresholds-value", "Warn={0} Crit={1}", "Warn={0} Crit={1}");

        // ==================== 红石 / 方块实体 ====================
        put(en, zh, "cmd.redstone.offline",
            "Redstone tracking was retired in v1.1 (it had been an empty implementation)",
            "红石追踪在 v1.1 已下线（此前为空实现）");
        put(en, zh, "cmd.tileentities.total-label", "Total tile entities: ", "总 Tile Entity: ");

        // ==================== 运行时间 ====================
        put(en, zh, "cmd.uptime.label", "Uptime: ", "运行时间: ");
        put(en, zh, "cmd.uptime.plugins-label", "Plugins loaded: ", "加载插件: ");
        put(en, zh, "cmd.uptime.worlds-label", "Worlds loaded: ", "加载世界: ");
        put(en, zh, "cmd.uptime.threads-label", "Active threads: ", "活跃线程: ");
        put(en, zh, "cmd.uptime.jvm-label", "JVM version: ", "JVM 版本: ");

        // ==================== 历史摘要 ====================
        put(en, zh, "cmd.history.empty",
            "No history yet (the plugin just started, or the first sampling interval has not elapsed; try again shortly)",
            "暂无历史数据（插件刚启动或首个采样间隔未到，稍后再试）");
        put(en, zh, "cmd.history.coverage-label", "Coverage: ", "覆盖: ");
        put(en, zh, "cmd.history.coverage",
            "last {0} min ({1} samples)", "近 {0} 分钟（{1} 个样本）");

        // ==================== 智能诊断 ====================
        put(en, zh, "cmd.diagnose.clean",
            "No known issues found ({0}/{1} high-confidence rules evaluated)",
            "未发现已知问题（已评估 {0}/{1} 条高置信规则）");
        put(en, zh, "cmd.diagnose.partial",
            "No issues found, but the result is incomplete ({0}/{1} rules evaluated; "
                + "{2} not evaluated — see the reason for each below)",
            "未发现问题，但结论不完整（已评估 {0}/{1} 条，其余 {2} 条未评估，逐条原因见下）");
        put(en, zh, "cmd.diagnose.advice-label", "  Advice: ", "  建议: ");
        put(en, zh, "cmd.diagnose.skipped-title",
            "Rules not evaluated ({0}):", "未评估的规则（{0} 条）:");
        put(en, zh, "cmd.diagnose.footer",
            "Note: this engine covers {0} high-confidence rules; use /spark profiler for method-level profiling",
            "提示: 本引擎覆盖 {0} 条高置信规则，方法级深挖请用 /spark profiler");

        // ==================== 趋势 ====================
        put(en, zh, "cmd.trend.empty",
            "No trend data yet (persisted history needs time to accumulate; "
                + "this process session has no samples either)",
            "暂无趋势数据（历史持久化启用后需积累采样；本进程会话亦无样本）");
        put(en, zh, "cmd.trend.source-file", "file persistence", "文件持久化");
        put(en, zh, "cmd.trend.source-memory", "in-memory ring (this process)", "内存环（本进程会话）");
        put(en, zh, "cmd.trend.range-label", "Range: ", "范围: ");
        put(en, zh, "cmd.trend.range",
            "last {0} day(s) ({1} samples, source: {2})",
            "近 {0} 天（{1} 个样本，来源: {2}）");
        put(en, zh, "cmd.trend.valid-samples", " ({0} valid samples)", "（{0} 个有效样本）");
        // f-s04-csv-06：skippedLines() 此前零调用点，坏行被静默丢弃、降级原因不可见。
        put(en, zh, "cmd.trend.skipped-lines",
            "Warning: {0} line(s) in the history files could not be parsed and were skipped "
                + "(half-written or malformed rows); the figures above exclude them",
            "注意：历史文件中有 {0} 行无法解析已被跳过（半写或格式损坏），以上统计不含这些行");
        put(en, zh, "cmd.trend.file-unavailable",
            "File persistence returned no samples; falling back to the in-memory ring. Directory: {0}",
            "文件持久化未取到样本，已回退到内存环。目录: {0}");
        // f-s02-logic-04：天数上界跟数据来源走——内存环只有约 24 小时，不能标成 N 天。
        put(en, zh, "cmd.trend.days-clamped-memory",
            "Note: history persistence is unavailable, so the in-memory ring only covers about 24 hours; "
                + "the requested {0} day(s) was clamped to {1}",
            "注意：历史持久化不可用，内存环只覆盖约 24 小时，请求的 {0} 天已收窄为 {1} 天");
        put(en, zh, "cmd.trend.coverage",
            "Coverage: {0} – {1} (local time)", "覆盖: {0} ～ {1}（本地时区）");

        // ==================== 热点区块 ====================
        put(en, zh, "cmd.hotspots.empty",
            "No hotspot data yet (first sampling round not done, or the module is unavailable; try again shortly)",
            "暂无热点数据（首轮采样未到或模块不可用，稍后再试）");
        put(en, zh, "cmd.hotspots.copy-hint",
            "Click to copy the chunk center coordinates ({0})",
            "点击复制区块中心坐标 ({0})");
        put(en, zh, "cmd.hotspots.block-coords", " (block {0},~,{1})", "（方块 {0},~,{1}）");
        put(en, zh, "cmd.hotspots.entities-label", "Entities: ", "实体 ");
        put(en, zh, "cmd.hotspots.count", "{0}", "{0} 个");
        put(en, zh, "cmd.hotspots.dominant", ", dominant ", "，主导 ");

        // ==================== GC ====================
        put(en, zh, "cmd.gc.label", "GC: ", "GC: ");
        put(en, zh, "cmd.gc.count-label", "GC count: ", "GC 次数: ");
        put(en, zh, "cmd.gc.time-label", "GC time: ", "GC 耗时: ");
        put(en, zh, "cmd.gc.total-count-label", "Total GC count: ", "累计 GC 次数: ");
        put(en, zh, "cmd.gc.total-time-label", "Total GC time: ", "累计 GC 耗时: ");
        put(en, zh, "cmd.gc.count-inline", "{0} times", "{0} 次");
        put(en, zh, "cmd.gc.seconds", "{0}s", "{0} 秒");
        put(en, zh, "cmd.gc.collectors-title", "GC collector details:", "GC 收集器详情:");
        put(en, zh, "cmd.gc.collector-line", "{0} collections / {1}ms", "{0} 次 / {1}ms");

        // ==================== 报告 / 重载 ====================
        put(en, zh, "cmd.report.copy-hint", "Click to copy the path", "点击复制路径");
        put(en, zh, "cmd.reload.notice", "Note: {0}", "注意: {0}");

        // ==================== 插件状态 ====================
        put(en, zh, "cmd.status.version-label", "Version: ", "版本: ");
        put(en, zh, "cmd.status.version-line", "{0} | Server: {1}", "{0} | 服务端: {1}");
        put(en, zh, "cmd.status.collect-label", "Collection: ", "采集: ");
        put(en, zh, "cmd.status.collect-pending",
            "First collection round not finished yet (ready within about a second of start/reload; "
                + "check the console if it takes longer than 10 seconds)",
            "尚未完成首轮采集（启动/重载后约 1 秒内就绪；超过 10 秒请看控制台）");
        put(en, zh, "cmd.status.collect-never",
            "{0} after startup and still no successful collection (no data ever obtained)",
            "启动后 {0} 仍无任何成功采集（从未拿到数据）");
        put(en, zh, "cmd.status.collect-never-action",
            "  Action: check the console for collection errors first; run /perfscope reload, "
                + "and restart the server if that does not help",
            "  处理: 先看控制台是否有采集异常；可执行 /perfscope reload，仍无效请重启服务器");
        put(en, zh, "cmd.status.collect-stale",
            "Stopped updating {0} ago (last collection {1})",
            "已停止更新 {0}（最后采集 {1}）");
        put(en, zh, "cmd.status.collect-stale-warning",
            "  Warning: other subcommands still show old data and cannot be used to judge the server's "
                + "current state; the report's \"generated at\" is not the \"sampled at\" time either",
            "  警告: 其它子命令显示的仍是旧数据，不能据此判断服务器当前状态；"
                + "报告里的\"生成时间\"也不等于\"采样时间\"");
        put(en, zh, "cmd.status.collect-ok",
            "OK (last collection {0}, {1}s ago)", "正常（最后采集 {0}，{1} 秒前）");
        put(en, zh, "cmd.status.interval-label", "Collection interval: ", "采集周期: ");
        put(en, zh, "cmd.status.interval",
            "{0} ticks (~{1}ms) | stale threshold: {2}ms",
            "{0} tick（约 {1}ms） | 过期阈值: {2}ms");
        // v1.3.0 -04：主线程采集耗时分布。采集是分级降频的，单值会误导——
        // 恰好在"撞车拍"看到 66ms 会让人以为"每秒都是 66ms"。
        put(en, zh, "cmd.status.collect-cost-label", "Collection cost: ", "采集耗时: ");
        put(en, zh, "cmd.status.collect-cost-pending",
            "warming up (no samples yet)", "预热中（暂无样本）");
        put(en, zh, "cmd.status.collect-cost",
            "p50 {0}ms / p95 {1}ms / max {2}ms ({3} samples, main thread)",
            "p50 {0}ms / p95 {1}ms / max {2}ms（{3} 个样本，主线程）");
        put(en, zh, "cmd.status.collect-cost-warn",
            "{0} cycles exceeded {1}ms (1 tick) - the collector is eating into tick budget",
            "有 {0} 个周期超过 {1}ms（1 tick）—— 采集器正在占用 tick 预算");
        put(en, zh, "cmd.status.jvm-label", "JVM sampling: ", "JVM 采集: ");
        put(en, zh, "cmd.status.jvm-never",
            "Never succeeded (no memory/CPU/GC data)", "从未成功（内存/CPU/GC 无数据）");
        put(en, zh, "cmd.status.jvm-stale",
            "Stopped updating (memory/CPU/GC frozen at values from {0})",
            "已停止更新（内存/CPU/GC 停留在 {0} 的值）");
        put(en, zh, "cmd.status.jvm-ok", "OK (last update {0})", "正常（最后更新 {0}）");
        put(en, zh, "cmd.status.first-round-pending", "first round not yet", "首轮未到");
        put(en, zh, "cmd.status.cycle-dead",
            "Stopped (the periodic task threw an uncaught exception; no samples produced)",
            "已停止（周期任务抛过未捕获异常，采集无产出）");
        put(en, zh, "cmd.status.cycle-retrying",
            "Error this round; the task is still retrying", "本轮异常，任务仍在重试");
        put(en, zh, "cmd.status.cycle-label", "Cycle health: ", "周期健康: ");
        put(en, zh, "cmd.status.cycle-failures",
            "{0} consecutive failures | ", "连续失败 {0} 次 | ");
        put(en, zh, "cmd.status.first-failure", "  First failure: {0}", "  首次失败: {0}");
        put(en, zh, "cmd.status.last-error", "  Last error: {0}", "  最近错误: {0}");
        put(en, zh, "cmd.status.cycle-action",
            "  Action: /perfscope reload rebuilds the collection task; restart the server if that does not help",
            "  处理: /perfscope reload 重建采集任务；若仍无效请重启服务器");
        put(en, zh, "cmd.status.section.modules", "--- Module Status ---", "--- 模块状态 ---");
        put(en, zh, "cmd.status.no-snapshot",
            "  No snapshot yet (first collection round not finished); module state unknown",
            "  尚无快照（采集首轮未完成），模块状态未知");
        put(en, zh, "cmd.status.snapshot-warning",
            "  Note: the following is the state of the last snapshot ({0}) and does not reflect the present",
            "  注意: 以下是最后一张快照（{0}）的状态，不代表现在");
        put(en, zh, "cmd.status.module.platform-skip",
            "Platform limitation (Folia cannot aggregate across regions reliably; skipped automatically)",
            "平台限制（Folia 跨 region 聚合不可靠，已自动跳过）");
        put(en, zh, "cmd.status.module.disabled-config",
            "Disabled (config {0}=false)", "已禁用（config {0}=false）");
        put(en, zh, "cmd.status.module.warming",
            "Warming up (first collection not ready)", "预热中（首轮采集未就绪）");
        put(en, zh, "cmd.status.module.failed", "Collection failed", "采集失败");
        put(en, zh, "cmd.status.module.failed-times", " ({0} in a row)", "（连续 {0} 次）");
        // 模块展示名（顺序即输出顺序，见 PerfScopeCommand.STATUS_MODULES）
        put(en, zh, "cmd.status.module.tps", "TPS", "TPS");
        put(en, zh, "cmd.status.module.mspt", "MSPT", "MSPT");
        put(en, zh, "cmd.status.module.players", "Players", "在线玩家");
        put(en, zh, "cmd.status.module.entities", "Entities", "实体");
        put(en, zh, "cmd.status.module.chunks", "Chunks", "区块");
        put(en, zh, "cmd.status.module.tile-entities", "Tile entities", "方块实体");
        put(en, zh, "cmd.status.module.hotspots", "Hotspot chunks", "热点区块");
        put(en, zh, "cmd.status.module.memory", "Memory", "内存");
        put(en, zh, "cmd.status.module.cpu", "CPU", "CPU");
        put(en, zh, "cmd.status.module.gc", "GC", "GC");
        put(en, zh, "cmd.status.module.server", "Server info", "服务器信息");
        put(en, zh, "cmd.status.section.history", "--- History ---", "--- 历史数据 ---");
        put(en, zh, "cmd.status.ringbuffer-label", "In-memory ring: ", "内存环形缓冲: ");
        put(en, zh, "cmd.status.ringbuffer",
            "{0} snapshots (cap 300, about {1}s)", "{0} 条快照（上限 300，约 {1} 秒）");
        put(en, zh, "cmd.status.history-ring-label", "History sample ring: ", "历史采样环: ");
        put(en, zh, "cmd.status.history-ring",
            "{0} samples (history.sample-interval-seconds={1})",
            "{0} 个样本（history.sample-interval-seconds={1}）");
        put(en, zh, "cmd.status.persistence-label", "File persistence: ", "文件持久化: ");
        put(en, zh, "cmd.status.persistence-disabled",
            "Disabled (config history.persist=false)", "已禁用（config history.persist=false）");
        put(en, zh, "cmd.status.persistence-unavailable",
            "Unavailable (directory missing or read-only; /trend can only see this process session)",
            "不可用（目录不存在/只读，/trend 只能看本进程会话）");
        put(en, zh, "cmd.status.history-dir-label", "History directory: ", "历史目录: ");
        put(en, zh, "cmd.status.section.alerts", "--- Current Alerts ---", "--- 当前告警 ---");
        put(en, zh, "cmd.status.no-snapshot-alerts",
            "  No snapshot yet; cannot count alerts", "  尚无快照，无法统计");
        put(en, zh, "cmd.status.warning-count", "{0} warnings", "{0} 条警告");
        put(en, zh, "cmd.status.critical-count", "{0} critical alerts", "{0} 条严重告警");
        put(en, zh, "cmd.status.active-alerts-label", "Active alerts: ", "活跃告警: ");
        put(en, zh, "cmd.status.alert-push-label", "Alert push: ", "告警推送: ");
        put(en, zh, "cmd.status.alert-push-disabled",
            "Disabled (config alerts.enabled=false)", "已禁用（config alerts.enabled=false）");
        put(en, zh, "cmd.status.scheduler-label", "Scheduler backlog: ", "调度器积压: ");
        put(en, zh, "cmd.status.scheduler-pending", "{0} pending tasks", "{0} 个待执行任务");
        put(en, zh, "cmd.status.scheduler-unavailable",
            "Unavailable (Folia has no global Bukkit scheduler)",
            "不可用（Folia 无全局 Bukkit 调度器）");

        // ==================== 告警历史 ====================
        put(en, zh, "cmd.alerts.service-disabled",
            "Alert service is not enabled (alerts.enabled=false)", "告警服务未启用（alerts.enabled=false）");
        put(en, zh, "cmd.alerts.empty",
            "No alert records (nothing within the last {0} entries)", "暂无告警记录（近 {0} 条内为空）");
        put(en, zh, "cmd.alerts.footer",
            "{0} entries (oldest first); {1} currently active, {2} suspended because their module is unavailable",
            "共 {0} 条（旧→新）；当前活跃 {1} 条，因模块不可用而挂起 {2} 条");

        // ==================== 错误 / 建议 ====================
        put(en, zh, "cmd.error.no-data",
            "First collection has not finished (the plugin just started or reloaded); it should be ready in "
                + "about a second. If there is still no data after 10 seconds, check the console",
            "首次采集尚未完成（插件刚启动/重载），约 1 秒后就绪；若超过 10 秒仍无数据请查看控制台");
        put(en, zh, "cmd.error.world-missing", "World not found: {0}", "世界不存在: {0}");
        put(en, zh, "cmd.error.days-not-integer",
            "Days must be an integer (1-{0}); using 1 day", "天数需为整数（1-{0}），已按 1 天处理");
        put(en, zh, "cmd.error.count-not-integer",
            "The count must be an integer; using 20", "条数需为整数，已按 20 处理");
        put(en, zh, "cmd.advice.mspt-critical",
            "Warning: MSPT exceeds {0}ms! TPS is already affected.",
            "警告: MSPT 超过 {0}ms！TPS 已受影响。");
        put(en, zh, "cmd.advice.mspt-suggestion",
            "Advice: check for large entity piles, excessive chunk loading, or other plugins consuming too much CPU.",
            "建议: 检查是否有大量实体堆积、区块加载过多、或其他插件消耗过高的 CPU。");
        put(en, zh, "cmd.advice.spark-hint",
            "Use the spark profiler to pinpoint it: /spark profiler start → /spark profiler stop",
            "可使用 spark profiler 精确定位: /spark profiler start → /spark profiler stop");

        // ==================== 实时监测启动失败（v1.3.0 -14） ====================
        // 两种失败要分开报：调度不可用（可重试） vs 插件正在停用（重试无意义）。
        // 旧实现把两者都报成"启动失败，详见控制台"，而关服分支根本没往控制台写任何东西，
        // 用户按提示去查什么也查不到。分键之后，提示与真实原因一一对应。
        put(en, zh, "cmd.monitor.failed-shutdown",
            "The plugin is shutting down; live monitoring is unavailable",
            "插件正在停用，实时监测已不可用");
        put(en, zh, "cmd.monitor.failed-no-scheduler",
            "Live monitoring failed to start (scheduler unavailable; see the console)",
            "实时监测启动失败（调度不可用，详见控制台）");

        // ==================== ActionBar ====================
        // 复合行按"前缀 + 单位"拆分：单位名（TPS/MSPT/玩家/…）来自 cmd.unit.*，
        // 这样英文的 "TPS: " 与中文的 "TPS: " 共用一条前缀，冒号/分隔符形态随语言走
        put(en, zh, "cmd.actionbar.lead", "{0}: ", "{0}: ");
        put(en, zh, "cmd.actionbar.segment", " | {0}: ", " | {0}: ");
        // f-s02-actionbar-02：采集停摆时不再渲染旧快照的"健康"数值，改为明确提示已过期多久。
        put(en, zh, "cmd.actionbar.stale",
            "[PerfScope] No data for {0} - collection has stopped; run /perfscope status",
            "[PerfScope] 已 {0} 无数据——采集已停止，请执行 /perfscope status");

        // ==================== 帮助 ====================
        put(en, zh, "cmd.help.root.display", "/perfscope", "/perfscope");
        put(en, zh, "cmd.help.root.desc", "Full diagnostics (default)", "全量诊断（默认）");
        put(en, zh, "cmd.help.all.display", "/perfscope all", "/perfscope all");
        put(en, zh, "cmd.help.all.desc", "Full diagnostics (same as no arguments)", "全量诊断（同无参数）");
        put(en, zh, "cmd.help.tps.display", "/perfscope tps", "/perfscope tps");
        put(en, zh, "cmd.help.tps.desc", "TPS diagnostics", "TPS 专项检测");
        put(en, zh, "cmd.help.mspt.display", "/perfscope mspt", "/perfscope mspt");
        put(en, zh, "cmd.help.mspt.desc", "MSPT diagnostics", "MSPT 专项检测");
        put(en, zh, "cmd.help.entities.display", "/perfscope entities [world]", "/perfscope entities [世界]");
        put(en, zh, "cmd.help.entities.desc", "Entity statistics", "实体统计");
        put(en, zh, "cmd.help.chunks.display", "/perfscope chunks [world]", "/perfscope chunks [世界]");
        put(en, zh, "cmd.help.chunks.desc", "Chunk statistics", "区块统计");
        put(en, zh, "cmd.help.memory.display", "/perfscope memory", "/perfscope memory");
        put(en, zh, "cmd.help.memory.desc", "Memory diagnostics", "内存诊断");
        put(en, zh, "cmd.help.cpu.display", "/perfscope cpu", "/perfscope cpu");
        put(en, zh, "cmd.help.cpu.desc", "CPU diagnostics", "CPU 诊断");
        put(en, zh, "cmd.help.players.display", "/perfscope players", "/perfscope players");
        put(en, zh, "cmd.help.players.desc", "Player statistics", "玩家统计");
        put(en, zh, "cmd.help.world.display", "/perfscope world [world]", "/perfscope world [世界]");
        put(en, zh, "cmd.help.world.desc", "World details", "世界详情");
        put(en, zh, "cmd.help.redstone.display", "/perfscope redstone", "/perfscope redstone");
        put(en, zh, "cmd.help.redstone.desc", "Redstone activity (retired)", "红石活动（已下线）");
        put(en, zh, "cmd.help.tileentities.display", "/perfscope tileentities [world]", "/perfscope tileentities [世界]");
        put(en, zh, "cmd.help.tileentities.desc", "Tile entity statistics", "Tile Entity 统计");
        put(en, zh, "cmd.help.uptime.display", "/perfscope uptime", "/perfscope uptime");
        put(en, zh, "cmd.help.uptime.desc", "Server runtime", "运行时间");
        put(en, zh, "cmd.help.history.display", "/perfscope history", "/perfscope history");
        put(en, zh, "cmd.help.history.desc", "History summary (last 24 hours)", "查看历史摘要（近 24 小时）");
        put(en, zh, "cmd.help.hotspot.display", "/perfscope hotspot", "/perfscope hotspot");
        put(en, zh, "cmd.help.hotspot.desc",
            "Hotspot chunks (top 3 by entity density, with coordinates)",
            "热点区块定位（实体密度 Top3 + 坐标）");
        put(en, zh, "cmd.help.diagnose.display", "/perfscope diagnose", "/perfscope diagnose");
        put(en, zh, "cmd.help.diagnose.desc",
            "Automated diagnostics (rule engine: findings + parameter-level advice)",
            "智能诊断（规则引擎：结论 + 参数级建议）");
        put(en, zh, "cmd.help.trend.display", "/perfscope trend [days]", "/perfscope trend [天数]");
        put(en, zh, "cmd.help.trend.desc",
            "Trend summary (cross-restart history, 1 day by default)",
            "趋势摘要（跨重启历史，默认 1 天）");
        put(en, zh, "cmd.help.gc.display", "/perfscope gc", "/perfscope gc");
        put(en, zh, "cmd.help.gc.desc", "GC statistics", "GC 统计");
        put(en, zh, "cmd.help.report.display", "/perfscope report [format]", "/perfscope report [格式]");
        put(en, zh, "cmd.help.report.desc", "Export a performance report", "导出性能报告");
        put(en, zh, "cmd.help.monitor.display", "/perfscope monitor", "/perfscope monitor");
        put(en, zh, "cmd.help.monitor.desc", "Toggle the live ActionBar monitor", "切换 ActionBar 实时监测");
        put(en, zh, "cmd.help.status.display", "/perfscope status", "/perfscope status");
        put(en, zh, "cmd.help.status.desc",
            "Plugin status (collection health / module state / history / alerts)",
            "插件自身状态（采集存活 / 模块状态 / 历史 / 告警）");
        put(en, zh, "cmd.help.alerts.display", "/perfscope alerts [N]", "/perfscope alerts [N]");
        put(en, zh, "cmd.help.alerts.desc",
            "Alert history (when it started / how long / recovered?); 20 by default",
            "告警历史（何时开始 / 持续多久 / 是否恢复）；默认 20 条");
        put(en, zh, "cmd.help.reload.display", "/perfscope reload", "/perfscope reload");
        put(en, zh, "cmd.help.reload.desc", "Reload the configuration", "重载配置文件");
        put(en, zh, "cmd.help.click-hint",
            "Tip: every command above is clickable", "提示: 所有命令均可点击快速跳转");
        // 帮助行的悬停提示与描述分隔符：中文用" - "，英文同样用" - "（保持视觉一致）
        put(en, zh, "cmd.help.click-hover", "Click to run {0}", "点击执行 {0}");
        put(en, zh, "cmd.help.desc-separator", " - {0}", " - {0}");

        Messages.registerModule(en, zh);
    }

    /**
     * 触发本类静态初始化（由 {@link Messages#onFirstLoad} 登记，
     * 并由 {@link PerfScopeCommand} 的静态块兜底再触碰一次）。
     */
    public static void init() {
        // 空方法体：调用它即触发 static 块
    }

    private static void put(Map<String, String> en, Map<String, String> zh,
                            String key, String enValue, String zhValue) {
        en.put(key, enValue);
        zh.put(key, zhValue);
    }

    private CommandMessages() {}
}
