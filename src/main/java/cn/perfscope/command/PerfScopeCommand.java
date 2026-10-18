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

import cn.perfscope.PerfScope;
import cn.perfscope.compat.SchedulerAdapter;
import cn.perfscope.compat.ServerCompat;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.diagnose.Diagnosis;
import cn.perfscope.diagnose.DiagnosticEngine;
import cn.perfscope.history.HistorySample;
import cn.perfscope.history.HistoryStore;
import cn.perfscope.metrics.ChunkHotspot;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.report.ReportGenerator;
import cn.perfscope.util.ThresholdColors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * PerfScope 指令系统 - 全量诊断 + 专项检测
 * /perfscope (无参数) 或 /perfscope all  →  全量诊断
 * /perfscope tps|mspt|entities|...        →  专项检测
 */
public class PerfScopeCommand implements CommandExecutor, TabCompleter, Listener {

    private final PerfScope plugin;
    private final MetricsCollector collector;
    private final ReportGenerator reportGen;
    private final ConfigManager configManager;
    // Wave 7A：历史环形缓冲（24h 窗口），只读消费
    private final HistoryStore historyStore;
    // v1.2 A8：显示层色阶统一判定（读 config 告警阈值，替代原先 16 处硬编码）
    private final ThresholdColors thresholdColors;

    // 实时监测模式中的玩家（P0-12：并发 Set，遍历清理线程安全）
    private final Set<UUID> monitoringPlayers = ConcurrentHashMap.newKeySet();
    // P0-8：monitor 定时任务句柄（经 SchedulerAdapter 统一调度，Folia 走 GlobalRegionScheduler）；null 表示未运行
    private SchedulerAdapter.TaskHandle monitorTask;

    // 颜色常量
    private static final NamedTextColor GOLD = NamedTextColor.GOLD;
    private static final NamedTextColor YELLOW = NamedTextColor.YELLOW;
    private static final NamedTextColor WHITE = NamedTextColor.WHITE;
    private static final NamedTextColor RED = NamedTextColor.RED;
    private static final NamedTextColor GREEN = NamedTextColor.GREEN;
    private static final NamedTextColor GRAY = NamedTextColor.GRAY;
    private static final NamedTextColor AQUA = NamedTextColor.AQUA;

    public PerfScopeCommand(PerfScope plugin) {
        this.plugin = plugin;
        this.collector = plugin.getMetricsCollector();
        this.reportGen = plugin.getReportGenerator();
        this.configManager = plugin.getConfigManager();
        this.historyStore = plugin.getHistoryStore();
        this.thresholdColors = new ThresholdColors(plugin.getConfigManager());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("perfscope.use")) {
            sender.sendMessage(Component.text("你没有权限使用此指令喵", RED));
            return true;
        }

        // 无参数 → 全量诊断
        if (args.length == 0) {
            showAll(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "all": case "diag":
                showAll(sender);
                break;
            case "diagnose":
                // Wave 8A（V1.2 规格 §6.1 既定变更）：diagnose 从 all 的别名改为智能诊断引擎入口；
                // diag 保持指向全量诊断（老用户肌肉记忆 + harness 断言依赖）
                showDiagnose(sender);
                break;
            case "tps":
                showTPS(sender);
                break;
            case "mspt":
                showMSPT(sender);
                break;
            case "entities": case "entity": case "e":
                showEntities(sender, args.length > 1 ? args[1] : null);
                break;
            case "chunks": case "chunk": case "c":
                showChunks(sender, args.length > 1 ? args[1] : null);
                break;
            case "memory": case "mem": case "m":
                showMemory(sender);
                break;
            case "cpu":
                showCPU(sender);
                break;
            case "players": case "player": case "p":
                showPlayers(sender);
                break;
            case "world": case "w":
                showWorld(sender, args.length > 1 ? args[1] : null);
                break;
            case "redstone": case "rs":
                showRedstone(sender);
                break;
            case "tileentities": case "te":
                showTileEntities(sender, args.length > 1 ? args[1] : null);
                break;
            case "uptime": case "up":
                showUptime(sender);
                break;
            case "history": case "hist":
                showHistory(sender);
                break;
            case "trend":
                // Wave 8B：跨重启历史趋势（读文件持久化，含内存环）
                showTrend(sender, args);
                break;
            case "hotspot": case "hs":
                showHotspots(sender);
                break;
            case "gc":
                showGC(sender);
                break;
            case "report": case "export":
                handleReport(sender, args);
                break;
            case "monitor": case "live":
                toggleMonitor(sender);
                break;
            case "reload": case "rl":
                handleReload(sender);
                break;
            case "help": case "h": case "?":
                showHelp(sender);
                break;
            default:
                showHelp(sender);
                break;
        }
        return true;
    }

    // ==================== 全量诊断 ====================

    private void showAll(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) {
            // W5-D #3：统一走 noData（此前与方法内 noData 各持一份硬编码文案，易失步）
            noData(sender);
            return;
        }

        // === 头部 ===
        sender.sendMessage(Component.text("=== PerfScope 诊断 ===", GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("服务端: " + ServerCompat.getServerName(), GRAY));
        sender.sendMessage(Component.text("采集时间: "
            + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(snap.timestamp)), GRAY));

        // TPS（P0-5：模块不可用显示"不可用"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            sender.sendMessage(yellowLabel("TPS (1m/5m/15m): ")
                .append(Component.text("不可用", GRAY)));
        } else {
            sender.sendMessage(yellowLabel("TPS (1m/5m/15m): ")
                .append(formatTpsComponent(snap.tps1m))
                .append(Component.text(" / ", WHITE))
                .append(formatTpsComponent(snap.tps5m))
                .append(Component.text(" / ", WHITE))
                .append(formatTpsComponent(snap.tps15m)));
        }

        // MSPT（P0-5：先判模块级"不可用"；P0-13：再判 -1 哨兵显示"未知"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)) {
            sender.sendMessage(yellowLabel("MSPT (avg/min/max): ")
                .append(Component.text("不可用", GRAY)));
        } else {
            // A1（命令侧）：仅当一级 TickTimes 与二级 getAverageTickTime 都不可用（走 TPS 反推）才标"(估算)"；
            // 二级是 Paper 平均 tick 真实数据、不是估算——判定与采集侧三级降级链口径一致（MC-1 修复后）
            Component msptSource = !(ServerCompat.PAPER_MSPT_AVAILABLE || ServerCompat.PAPER_AVG_TICK_AVAILABLE)
                ? Component.text(" (估算)", GRAY) : Component.empty();
            Component msptMinAll = snap.msptMin <= 0 ? Component.text("未知", GRAY)
                : Component.text(String.format("%.2f", snap.msptMin) + "ms", WHITE);
            Component msptMaxAll = snap.msptMax <= 0 ? Component.text("未知", GRAY)
                : Component.text(String.format("%.2f", snap.msptMax) + "ms", WHITE);
            sender.sendMessage(yellowLabel("MSPT (avg/min/max): ")
                .append(formatMSPTComponent(snap.msptAvg))
                .append(msptSource)
                .append(Component.text(" / ", WHITE))
                .append(msptMinAll)
                .append(Component.text(" / ", WHITE))
                .append(msptMaxAll));
        }

        // 在线玩家（P0-5：模块不可用显示"不可用"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            sender.sendMessage(goldLabel("在线玩家: ")
                .append(Component.text("不可用", GRAY)));
        } else {
            sender.sendMessage(goldLabel("在线玩家: ")
                .append(Component.text(snap.playerCount + " / " + snap.maxPlayers, WHITE)));
        }

        // 实体总数（P0-5：模块不可用显示"不可用"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            sender.sendMessage(goldLabel("实体总数: ")
                .append(Component.text("不可用", GRAY)));
        } else {
            // v1.2 A8：改为读 config 阈值（原硬编码 200/全局warn）
            TextColor entityColor = thresholdColors.colorOf(
                thresholdColors.levelOf(ThresholdColors.Metric.ENTITIES, snap.totalEntities));
            sender.sendMessage(goldLabel("实体总数: ")
                .append(Component.text(String.valueOf(snap.totalEntities), entityColor)));
        }

        // 加载区块（P0-5：模块不可用显示"不可用"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)) {
            sender.sendMessage(goldLabel("加载区块: ")
                .append(Component.text("不可用", GRAY)));
        } else {
            sender.sendMessage(goldLabel("加载区块: ")
                .append(Component.text(String.valueOf(snap.totalLoadedChunks), WHITE))
                .append(Component.text(" (强加载: " + snap.totalForceLoadedChunks + ")", GRAY)));
        }

        // === 服务器级诊断 ===
        sender.sendMessage(Component.text("--- 服务器级诊断 ---", GOLD, TextDecoration.BOLD));

        for (World world : Bukkit.getWorlds()) {
            int entityCount = snap.entitiesByWorld.getOrDefault(world.getName(), 0);
            int loadedChunks = snap.chunksByWorld.getOrDefault(world.getName(), 0);
            int forceLoaded = snap.forceLoadedChunksByWorld.getOrDefault(world.getName(), 0);
            int tileEntities = snap.tileEntitiesByWorld.getOrDefault(world.getName(), 0);

            // v1.2 A8：改为读 config 阈值（含 worlds 段按世界覆盖；原硬编码 500/200）
            TextColor eColor = thresholdColors.colorOf(
                thresholdColors.levelOf(ThresholdColors.Metric.ENTITIES, entityCount, world.getName()));

            sender.sendMessage(Component.text("  世界: ", GRAY)
                .append(Component.text(world.getName(), WHITE))
                .append(Component.text(" | 实体: ", GRAY))
                .append(Component.text(String.valueOf(entityCount), eColor))
                .append(Component.text(" | 区块: ", GRAY))
                .append(Component.text(String.valueOf(loadedChunks), WHITE))
                .append(Component.text(" | 强加载: ", GRAY))
                .append(Component.text(String.valueOf(forceLoaded), WHITE))
                .append(Component.text(" | 方块实体: ", GRAY))
                .append(Component.text(String.valueOf(tileEntities), WHITE)));
        }

        // 内存（P0-5：模块不可用显示"不可用"；W5-D #1：预热态改渲染"预热中"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            sender.sendMessage(goldLabel("内存: ")
                .append(unavailableText(snap, MetricsSnapshot.MODULE_MEMORY)));
        } else {
            long usedMem = snap.heapUsed / 1024 / 1024;
            long maxMem = snap.heapMax / 1024 / 1024;
            // v1.2 A8：改为读 config 阈值（原硬编码 90/80）
            TextColor memColor = thresholdColors.colorOf(
                thresholdColors.levelOf(ThresholdColors.Metric.MEMORY, snap.heapUsagePercent));
            sender.sendMessage(goldLabel("内存: ")
                .append(Component.text(usedMem + "MB / " + maxMem + "MB", WHITE))
                .append(Component.text(" (" + snap.heapUsagePercent + "%)", memColor)));
        }

        // CPU（P0-5：先判模块级"不可用"；P0-13：再判 -1 哨兵显示"未知"；W5-D #1：预热态渲染"预热中"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CPU)) {
            sender.sendMessage(goldLabel("CPU: ")
                .append(unavailableText(snap, MetricsSnapshot.MODULE_CPU)));
        } else {
            Component showAllProcessCpu = snap.processCpuLoad < 0 ? Component.text("进程 未知", GRAY)
                : Component.text(String.format("进程 %.1f%%", snap.processCpuLoad), WHITE);
            Component showAllSystemCpu = snap.systemCpuLoad < 0 ? Component.text("系统 未知", GRAY)
                : Component.text(String.format("系统 %.1f%%", snap.systemCpuLoad), WHITE);
            sender.sendMessage(goldLabel("CPU: ")
                .append(showAllProcessCpu)
                .append(Component.text(" / ", GRAY))
                .append(showAllSystemCpu));
        }

        // 运行时间（P0-5：server 模块不可用显示"不可用"；W5-D #1：JVM 异步就绪前 uptime/线程数为默认 0，
        // 渲染"预热中"而非误导性的"0秒 | 线程: 0"。
        // 注：不标 unavailable——server 模块的 Bukkit 部分（插件数/世界数）首周期即有真值，契约不动）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_SERVER)) {
            sender.sendMessage(goldLabel("运行时间: ")
                .append(Component.text("不可用", GRAY)));
        } else if (snap.uptimeSeconds <= 0) {
            sender.sendMessage(goldLabel("运行时间: ")
                .append(uptimeWarmupText()));
        } else {
            sender.sendMessage(goldLabel("运行时间: ")
                .append(Component.text(formatUptime(snap.uptimeSeconds), WHITE))
                .append(Component.text(" | 线程: " + snap.threadCount, GRAY))
                .append(Component.text(" | 插件: " + snap.loadedPluginCount, GRAY))
                .append(Component.text(" | 世界: " + snap.worldCount, GRAY)));
        }

        // GC（P0-5：模块不可用显示"不可用"；W5-D #1：预热态渲染"预热中"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            sender.sendMessage(goldLabel("GC: ")
                .append(unavailableText(snap, MetricsSnapshot.MODULE_GC)));
        } else {
            sender.sendMessage(goldLabel("GC: ")
                .append(Component.text(snap.gcCount + " 次", WHITE))
                .append(Component.text(" / ", GRAY))
                .append(Component.text(String.format("%.2f 秒", snap.gcTime / 1000.0), WHITE)));
        }

        // P0-7：红石/网络段已下线删除

        // === 告警 ===
        if (!snap.activeCriticals.isEmpty() || !snap.activeWarnings.isEmpty()) {
            sender.sendMessage(Component.text("--- 活跃告警 ---", RED, TextDecoration.BOLD));
            for (String critical : snap.activeCriticals) {
                sender.sendMessage(Component.text("  [!!!] " + critical, RED));
            }
            for (String warning : snap.activeWarnings) {
                sender.sendMessage(Component.text("  [!] " + warning, YELLOW));
            }
        }

        // MSPT 过高建议（v1.2 A8：触发条件改为读 config 阈值，原硬编码 50）
        if (thresholdColors.levelOf(ThresholdColors.Metric.MSPT, snap.msptAvg) == ThresholdColors.Level.CRITICAL) {
            sender.sendMessage(Component.text("警告: MSPT 超过 " + (int) configManager.get().mspt_criticalThreshold
                + "ms！TPS 已受影响。", RED));
            sender.sendMessage(Component.text("建议: 检查是否有大量实体堆积、区块加载过多、或其他插件消耗过高的 CPU。", GRAY));
            sender.sendMessage(Component.text("可使用 spark profiler 精确定位: /spark profiler start → /spark profiler stop", GRAY));
        }
    }

    // ==================== 专项检测: TPS ====================

    private void showTPS(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== TPS 诊断 ===", GOLD, TextDecoration.BOLD));
        // P0-5：模块被禁用或采集异常时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            sender.sendMessage(yellowLabel("TPS: ").append(Component.text("不可用", GRAY)));
            return;
        }
        sender.sendMessage(yellowLabel("TPS (1m/5m/15m): ")
            .append(formatTpsComponent(snap.tps1m))
            .append(Component.text(" / ", WHITE))
            .append(formatTpsComponent(snap.tps5m))
            .append(Component.text(" / ", WHITE))
            .append(formatTpsComponent(snap.tps15m)));

        // TPS 趋势图
        List<MetricsSnapshot> history = collector.getHistory();
        if (history.size() > 1) {
            int showCount = Math.min(60, history.size());
            sender.sendMessage(Component.text("TPS 趋势 (最近" + showCount + "次采样):", GRAY));
            // A5（命令侧）：趋势位语义——"|" 满 / "." GOOD / ":" WARN / "!" CRITICAL；
            // v1.2 A8：四档判定改读 config（阈值来源 ThresholdColors.tpsTrendBucket）；
            // TPS 值 <=0 或该采样快照 MODULE_TPS 不可用时，该位是"数据缺失"（模块禁用/启动期写入的 0 值），
            // 渲染为灰色 "?"，不得画成 "!"——"数据缺失"≠"TPS=0"，避免整段趋势被误读为"TPS 崩到 0"
            Component trend = Component.empty();
            int start = Math.max(0, history.size() - showCount);
            for (int i = start; i < history.size(); i++) {
                MetricsSnapshot sample = history.get(i);
                double tps = sample.tps1m;
                if (sample.unavailable.contains(MetricsSnapshot.MODULE_TPS) || tps <= 0) {
                    trend = trend.append(Component.text("?", GRAY));
                } else {
                    switch (thresholdColors.tpsTrendBucket(tps)) {
                        case 3: trend = trend.append(Component.text("|", AQUA)); break;
                        case 2: trend = trend.append(Component.text(".", AQUA)); break;
                        case 1: trend = trend.append(Component.text(":", AQUA)); break;
                        default: trend = trend.append(Component.text("!", AQUA)); break;
                    }
                }
            }
            sender.sendMessage(Component.text("  ").append(trend));
        }

        // 对应 MSPT 推算
        if (snap.tps1m < 20) {
            double expectedMspt = 1000.0 / snap.tps1m;
            sender.sendMessage(Component.text("当前等效 MSPT: " + String.format("%.1f", expectedMspt) + "ms", GRAY));
        }
    }

    // ==================== 专项检测: MSPT ====================

    private void showMSPT(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== MSPT 诊断 ===", GOLD, TextDecoration.BOLD));
        // P0-5：先判模块级"不可用"（P0-13 的 -1 哨兵"未知"仍由 formatMSPTComponent 处理）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)) {
            sender.sendMessage(yellowLabel("MSPT: ").append(Component.text("不可用", GRAY)));
            return;
        }
        // A1（命令侧）："(估算)"标签仅在一级 TickTimes 与二级 getAverageTickTime 都不可用
        //（走 TPS 反推）时标注，与 showAll 同口径；二级是 Paper 真实平均 tick 数据、非估算
        Component msptEstimate = !(ServerCompat.PAPER_MSPT_AVAILABLE || ServerCompat.PAPER_AVG_TICK_AVAILABLE)
            ? Component.text(" (估算)", GRAY) : Component.empty();
        sender.sendMessage(yellowLabel("平均 MSPT: ")
            .append(formatMSPTComponent(snap.msptAvg))
            .append(msptEstimate));
        // P0-13：-1 哨兵显示"未知"
        Component msptMinComp = snap.msptMin <= 0 ? Component.text("未知", GRAY)
            : Component.text(String.format("%.2f ms", snap.msptMin), WHITE);
        Component msptMaxComp = snap.msptMax <= 0 ? Component.text("未知", GRAY)
            : Component.text(String.format("%.2f ms", snap.msptMax), WHITE);
        sender.sendMessage(yellowLabel("最小 MSPT: ").append(msptMinComp));
        sender.sendMessage(yellowLabel("最大 MSPT: ").append(msptMaxComp));

        if (snap.msptAvg > 0) {
            double maxTPS = 1000.0 / snap.msptAvg;
            sender.sendMessage(goldLabel("理论最大 TPS: ")
                .append(Component.text(String.format("%.1f", Math.min(maxTPS, 20.0)), AQUA)));
        }

        // v1.2 A8：触发条件与文案数值改为读 config 阈值（原硬编码 50）
        if (thresholdColors.levelOf(ThresholdColors.Metric.MSPT, snap.msptAvg) == ThresholdColors.Level.CRITICAL) {
            sender.sendMessage(Component.text("警告: MSPT 超过 " + (int) configManager.get().mspt_criticalThreshold
                + "ms！TPS 已受影响。", RED));
            sender.sendMessage(Component.text("建议: 检查是否有大量实体堆积、区块加载过多、或其他插件消耗过高的 CPU。", GRAY));
            sender.sendMessage(Component.text("可使用 spark profiler 精确定位: /spark profiler start → /spark profiler stop", GRAY));
        }
    }

    // ==================== 专项检测: 实体 ====================

    private void showEntities(CommandSender sender, String worldName) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        // A5（命令侧）：世界参数解析 + 规范名——不存在时显式提示并返回（此前静默空筛选，F4）；
        // 后续统计/过滤/显示一律用 world.getName()（规范名，大小写与快照键一致，F2）
        World world = resolveWorld(sender, worldName);
        if (worldName != null && world == null) return;

        String title = world != null ? "=== 实体统计 - " + world.getName() + " ===" : "=== 实体统计 ===";
        sender.sendMessage(Component.text(title, GOLD, TextDecoration.BOLD));

        // P0-5：模块被禁用或采集异常时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            sender.sendMessage(goldLabel("总实体数: ").append(Component.text("不可用", GRAY)));
            return;
        }

        // v1.2 A8：改为读 config 阈值（原硬编码 200/全局warn）
        TextColor entityColor = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.ENTITIES, snap.totalEntities));
        sender.sendMessage(goldLabel("总实体数: ")
            .append(Component.text(String.valueOf(snap.totalEntities), entityColor, TextDecoration.BOLD)));

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("按世界分布:", GRAY));
        for (Map.Entry<String, Integer> entry : snap.entitiesByWorld.entrySet()) {
            if (world != null && !entry.getKey().equals(world.getName())) continue;
            // v1.2 A8：改为读 config 阈值（含 worlds 段覆盖；原硬编码 500/200）
            TextColor ec = thresholdColors.colorOf(
                thresholdColors.levelOf(ThresholdColors.Metric.ENTITIES, entry.getValue(), entry.getKey()));
            sender.sendMessage(Component.text("  " + entry.getKey() + ": ", WHITE)
                .append(Component.text(String.valueOf(entry.getValue()), ec)));
        }

        sender.sendMessage(Component.text(""));
        // A5/T5c：类型分布口径修正——带世界参数时按该世界过滤后重算 Top15 与百分比（标题标注世界名），
        // 不再把全服 Top15 混入世界视图；不带参数时保持全服口径并标注"（全服）"。
        // 快照 entitiesByType 为全服口径（无世界维度），世界视图由对该世界的一次即时扫描得到——
        // 扫描范围为单世界，量级与采集侧降频周期一致；异常只影响本段（显示读取失败），不中断命令
        Map<String, Integer> typeDist;
        int typeDistTotal;
        if (world != null) {
            Map<String, Integer> scanned = null;
            try {
                Map<String, Integer> live = new LinkedHashMap<>();
                for (Entity entity : world.getEntities()) {
                    live.merge(entity.getType().name(), 1, Integer::sum);
                }
                scanned = live;
            } catch (Exception ignored) {
                // 世界实体扫描异常（如 Folia 跨区域限制）→ 下方显示"读取失败"
            }
            typeDist = scanned;
            int sum = 0;
            if (scanned != null) {
                for (int count : scanned.values()) {
                    sum += count;
                }
            }
            typeDistTotal = sum;
        } else {
            typeDist = snap.entitiesByType;
            typeDistTotal = snap.totalEntities;
        }

        sender.sendMessage(Component.text(world != null
            ? "按类型分布 (Top 15, 世界: " + world.getName() + "):"
            : "按类型分布 (全服, Top 15):", GRAY));
        if (typeDist == null) {
            sender.sendMessage(Component.text("  读取失败: 无法扫描该世界实体", RED));
        } else {
            final Map<String, Integer> dist = typeDist;
            final int distTotal = typeDistTotal;
            dist.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .forEach(entry -> {
                    double pct = distTotal > 0 ? (double) entry.getValue() / distTotal * 100 : 0;
                    sender.sendMessage(Component.text("  " + entry.getKey() + ": ", WHITE)
                        .append(Component.text(entry.getValue() + " (" + String.format("%.1f", pct) + "%)", AQUA)));
                });
        }

        if (snap.totalEntities > configManager.get().entities_totalCriticalThreshold) {
            sender.sendMessage(Component.text("严重警告: 实体总数超过严重阈值!", RED));
        } else if (snap.totalEntities > configManager.get().entities_totalWarningThreshold) {
            sender.sendMessage(Component.text("警告: 实体总数超过警告阈值", YELLOW));
        }
    }

    // ==================== 专项检测: 区块 ====================

    private void showChunks(CommandSender sender, String worldName) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        // A5（命令侧）：世界参数解析 + 规范名（同 showEntities）；标题回显被过滤的世界名
        World world = resolveWorld(sender, worldName);
        if (worldName != null && world == null) return;

        sender.sendMessage(Component.text(world != null
            ? "=== 区块统计 - " + world.getName() + " ==="
            : "=== 区块统计 ===", GOLD, TextDecoration.BOLD));
        // P0-5：模块被禁用或采集异常时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)) {
            sender.sendMessage(goldLabel("总加载区块: ").append(Component.text("不可用", GRAY)));
            return;
        }
        sender.sendMessage(goldLabel("总加载区块: ")
            .append(Component.text(String.valueOf(snap.totalLoadedChunks), WHITE, TextDecoration.BOLD)));
        sender.sendMessage(goldLabel("总强加载区块: ")
            .append(Component.text(String.valueOf(snap.totalForceLoadedChunks), WHITE)));

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("按世界分布:", GRAY));
        for (Map.Entry<String, Integer> entry : snap.chunksByWorld.entrySet()) {
            if (world != null && !entry.getKey().equals(world.getName())) continue;
            int forceLoaded = snap.forceLoadedChunksByWorld.getOrDefault(entry.getKey(), 0);
            // v1.2 A8：改为读 config 阈值（原硬编码 1500/800——1500 实为告警黄界，红界错位修正）
            TextColor cc = thresholdColors.colorOf(
                thresholdColors.levelOf(ThresholdColors.Metric.CHUNKS, entry.getValue()));
            sender.sendMessage(Component.text("  " + entry.getKey() + ": ", WHITE)
                .append(Component.text(entry.getValue() + " 个", cc))
                .append(Component.text(" (强加载: " + forceLoaded + ")", GRAY)));
        }
    }

    // ==================== 专项检测: 内存 ====================

    private void showMemory(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== 内存诊断 ===", GOLD, TextDecoration.BOLD));

        // P0-5：模块被禁用或采集异常时显示"不可用"；W5-D #1：预热态渲染"预热中"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            sender.sendMessage(yellowLabel("堆内存: ").append(unavailableText(snap, MetricsSnapshot.MODULE_MEMORY)));
            return;
        }

        long usedMemMB = snap.heapUsed / 1024 / 1024;
        long maxMemMB = snap.heapMax / 1024 / 1024;
        // v1.2 A8：改为读 config 阈值（原硬编码 90/80，与默认巧合一致）
        TextColor memColor = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.MEMORY, snap.heapUsagePercent));

        sender.sendMessage(yellowLabel("堆内存: ")
            .append(Component.text(usedMemMB + "MB / " + maxMemMB + "MB", memColor, TextDecoration.BOLD))
            .append(Component.text(" (" + snap.heapUsagePercent + "%)", memColor)));

        String bar = generateBar(snap.heapUsagePercent, 30);
        sender.sendMessage(Component.text("  [" + bar + "]", memColor));

        sender.sendMessage(goldLabel("已分配堆内存: ")
            .append(Component.text(formatBytes(snap.heapTotal), WHITE)));
        sender.sendMessage(goldLabel("非堆内存: ")
            .append(Component.text(formatBytes(snap.nonHeapUsed), WHITE)));
        if (snap.nonHeapMax > 0) {
            sender.sendMessage(goldLabel("非堆内存上限: ")
                .append(Component.text(formatBytes(snap.nonHeapMax), WHITE)));
        }

        // A5（命令侧）：GC 行独立守卫——内存正常但 GC 模块异常/禁用时显示"不可用"，
        // 而非误导性的 "GC 次数: 0 / 0 ms"（此前 showAll 的 GC 行有守卫，此处为单点漏）；
        // W5-D #1：预热态（内存/GC 同为 JVM 异步模块，常同时预热）渲染"预热中"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            sender.sendMessage(goldLabel("GC: ")
                .append(unavailableText(snap, MetricsSnapshot.MODULE_GC)));
        } else {
            sender.sendMessage(goldLabel("GC 次数: ")
                .append(Component.text(String.valueOf(snap.gcCount), WHITE)));
            sender.sendMessage(goldLabel("GC 耗时: ")
                .append(Component.text(snap.gcTime + " ms", WHITE)));
        }

        if (thresholdColors.levelOf(ThresholdColors.Metric.MEMORY, snap.heapUsagePercent) == ThresholdColors.Level.CRITICAL) {
            sender.sendMessage(Component.text("严重警告: 堆内存使用率超过 "
                + configManager.get().memory_heapCriticalThreshold + "%！", RED));
            sender.sendMessage(Component.text("建议: 增加服务器最大内存 (-Xmx) 或检查是否有内存泄漏。", GRAY));
        }
    }

    // ==================== 专项检测: CPU ====================

    private void showCPU(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== CPU 诊断 ===", GOLD, TextDecoration.BOLD));

        // P0-5：先判模块级"不可用"（P0-13 的 -1 哨兵"未知"仍在下方处理）；W5-D #1：预热态渲染"预热中"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CPU)) {
            sender.sendMessage(yellowLabel("进程 CPU 使用率: ").append(unavailableText(snap, MetricsSnapshot.MODULE_CPU)));
            return;
        }

        // v1.2 A8：改为读 config 阈值（CPU 无 critical 键，最高只判到 WARN，消除"红界=告警界"错位）
        TextColor processColor = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.CPU_PROCESS, snap.processCpuLoad));
        TextColor systemColor = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.CPU_SYSTEM, snap.systemCpuLoad));

        // P0-13：-1 哨兵显示"未知"
        Component processCpuValue = snap.processCpuLoad < 0 ? Component.text("未知", GRAY)
            : Component.text(String.format("%.1f%%", snap.processCpuLoad), processColor, TextDecoration.BOLD);
        Component systemCpuValue = snap.systemCpuLoad < 0 ? Component.text("未知", GRAY)
            : Component.text(String.format("%.1f%%", snap.systemCpuLoad), systemColor);
        sender.sendMessage(yellowLabel("进程 CPU 使用率: ").append(processCpuValue));
        sender.sendMessage(yellowLabel("系统 CPU 负载: ").append(systemCpuValue));
        sender.sendMessage(goldLabel("可用处理器: ")
            .append(Component.text(String.valueOf(snap.availableProcessors), WHITE)));
        sender.sendMessage(goldLabel("活跃线程数: ")
            .append(Component.text(String.valueOf(snap.threadCount), WHITE))
            .append(Component.text(" (JVM: " + Thread.activeCount() + ")", GRAY)));
    }

    // ==================== 专项检测: 玩家 ====================

    private void showPlayers(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== 玩家统计 ===", GOLD, TextDecoration.BOLD));
        // P0-5：模块被禁用或采集异常时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            sender.sendMessage(goldLabel("在线玩家: ").append(Component.text("不可用", GRAY)));
            return;
        }
        sender.sendMessage(goldLabel("在线玩家: ")
            .append(Component.text(snap.playerCount + " / " + snap.maxPlayers, WHITE, TextDecoration.BOLD)));

        int pct = snap.maxPlayers > 0 ? (snap.playerCount * 100 / snap.maxPlayers) : 0;
        String pBar = generateBar(pct, 30);
        // v1.2 A8：改为读 config 阈值（原硬编码 95/80，与默认巧合一致）
        TextColor pctColor = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.PLAYERS, pct));
        sender.sendMessage(Component.text("  [" + pBar + "] " + pct + "%", pctColor));

        if (snap.playerCount > 0) {
            sender.sendMessage(Component.text(""));
            sender.sendMessage(Component.text("在线玩家列表:", GRAY));
            Bukkit.getOnlinePlayers().forEach(p -> {
                String worldName = p.getWorld().getName();
                String pingStr;
                try {
                    int ping = p.getPing();
                    pingStr = ping + "ms";
                } catch (Exception e) {
                    pingStr = "?";
                }
                sender.sendMessage(Component.text("  " + p.getName(), WHITE)
                    .append(Component.text("  [" + worldName + "] ", GRAY))
                    .append(Component.text(pingStr, AQUA)));
            });
        }
    }

    // ==================== 专项检测: 世界 ====================

    private void showWorld(CommandSender sender, String worldName) {
        if (worldName == null) {
            MetricsSnapshot snap = collector.getLatest();
            if (snap == null) { noData(sender); return; }
            sender.sendMessage(Component.text("=== 世界列表 ===", GOLD, TextDecoration.BOLD));
            for (World world : Bukkit.getWorlds()) {
                Component worldEntry = Component.text("  " + world.getName(), AQUA, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/perfscope world " + world.getName()))
                    .hoverEvent(HoverEvent.showText(Component.text("点击查看 " + world.getName() + " 的详细信息", GREEN)));
                sender.sendMessage(worldEntry);
            }
            return;
        }

        World world = resolveWorld(sender, worldName);
        if (world == null) {
            // worldName != null 时 resolveWorld 已发送"世界不存在: <输入>"反馈
            return;
        }

        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        // A5（命令侧）：Bukkit.getWorld 大小写不敏感，但快照键为世界规范名——统一用 world.getName()
        // 做后续全部查询与显示，修复"/ps w WORLD"因原始大小写 miss 导致的全 0 假数据（F2）
        String canonicalName = world.getName();
        sender.sendMessage(Component.text("=== 世界详情 - " + canonicalName + " ===", GOLD, TextDecoration.BOLD));
        sender.sendMessage(goldLabel("维度: ").append(Component.text(world.getEnvironment().name(), WHITE)));
        sender.sendMessage(goldLabel("实体数: ").append(Component.text(
            String.valueOf(snap.entitiesByWorld.getOrDefault(canonicalName, 0)), WHITE)));
        sender.sendMessage(goldLabel("加载区块: ").append(Component.text(
            String.valueOf(snap.chunksByWorld.getOrDefault(canonicalName, 0)), WHITE)));
        sender.sendMessage(goldLabel("强加载区块: ").append(Component.text(
            String.valueOf(snap.forceLoadedChunksByWorld.getOrDefault(canonicalName, 0)), WHITE)));
        sender.sendMessage(goldLabel("Tile Entity: ").append(Component.text(
            String.valueOf(snap.tileEntitiesByWorld.getOrDefault(canonicalName, 0)), WHITE)));

        int playersInWorld = (int) Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.getWorld().getName().equals(canonicalName))
            .count();
        sender.sendMessage(goldLabel("世界内玩家: ").append(Component.text(String.valueOf(playersInWorld), WHITE)));

        ConfigManager.WorldSettings ws = configManager.getWorldSettings(canonicalName);
        if (ws != null) {
            sender.sendMessage(goldLabel("实体告警阈值: ")
                .append(Component.text("Warn=" + ws.entities_totalWarningThreshold + " Crit=" + ws.entities_totalCriticalThreshold, GRAY)));
        }
    }

    // ==================== 专项检测: 红石 ====================

    // P0-7：红石追踪已下线，保留子命令入口（老用户脚本不断），固定输出下线提示
    private void showRedstone(CommandSender sender) {
        sender.sendMessage(Component.text("=== 红石活动 ===", GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("红石追踪在 v1.1 已下线（此前为空实现）", GRAY));
    }

    // ==================== 专项检测: Tile Entity ====================

    private void showTileEntities(CommandSender sender, String worldName) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        // A5（命令侧）：世界参数解析 + 规范名（同 showEntities/showChunks）；标题回显被过滤的世界名
        World world = resolveWorld(sender, worldName);
        if (worldName != null && world == null) return;

        sender.sendMessage(Component.text(world != null
            ? "=== Tile Entity 统计 - " + world.getName() + " ==="
            : "=== Tile Entity 统计 ===", GOLD, TextDecoration.BOLD));
        // P0-5：模块被禁用或采集异常时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TILE_ENTITIES)) {
            sender.sendMessage(goldLabel("总 Tile Entity: ").append(Component.text("不可用", GRAY)));
            return;
        }
        sender.sendMessage(goldLabel("总 Tile Entity: ")
            .append(Component.text(String.valueOf(snap.tileEntityCount), WHITE, TextDecoration.BOLD)));

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("按世界分布:", GRAY));
        for (Map.Entry<String, Integer> entry : snap.tileEntitiesByWorld.entrySet()) {
            if (world != null && !entry.getKey().equals(world.getName())) continue;
            sender.sendMessage(Component.text("  " + entry.getKey() + ": ", WHITE)
                .append(Component.text(String.valueOf(entry.getValue()), AQUA)));
        }
    }

    // ==================== 专项检测: 运行时间 ====================

    private void showUptime(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== 服务器运行信息 ===", GOLD, TextDecoration.BOLD));
        // P0-5：server 模块被禁用或采集异常时显示"不可用"；W5-D #1：JVM 异步就绪前 uptime 为默认 0，
        // 渲染"预热中"（与 showAll 同口径），而非误导性的"0秒"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_SERVER)) {
            sender.sendMessage(goldLabel("运行时间: ").append(Component.text("不可用", GRAY)));
            return;
        }
        if (snap.uptimeSeconds <= 0) {
            sender.sendMessage(goldLabel("运行时间: ").append(uptimeWarmupText()));
            return;
        }
        sender.sendMessage(goldLabel("运行时间: ")
            .append(Component.text(formatUptime(snap.uptimeSeconds), WHITE, TextDecoration.BOLD)));
        sender.sendMessage(goldLabel("加载插件: ")
            .append(Component.text(String.valueOf(snap.loadedPluginCount), WHITE)));
        sender.sendMessage(goldLabel("加载世界: ")
            .append(Component.text(String.valueOf(snap.worldCount), WHITE)));
        sender.sendMessage(goldLabel("活跃线程: ")
            .append(Component.text(String.valueOf(snap.threadCount), WHITE))
            .append(Component.text(" (JVM: " + Thread.activeCount() + ")", GRAY)));
        sender.sendMessage(goldLabel("JVM 版本: ")
            .append(Component.text(System.getProperty("java.version"), WHITE))
            .append(Component.text(" (" + System.getProperty("java.vendor") + ")", GRAY)));
    }

    // ==================== 专项检测: 历史摘要（Wave 7A） ====================

    /**
     * Wave 7A：/perfscope history —— 24h 环形缓冲摘要。
     * 输出格式锁定（docs/06-tasks/WAVE7-DESIGN.md「7A」节）：标题 "=== 历史摘要 ==="、
     * "覆盖: 近 N 分钟（M 个样本）"、"最低 TPS: x.xx（HH:mm）"、"峰值实体: N（HH:mm）"、
     * "峰值内存: P%（HH:mm）"；空态含稳定子串 "暂无历史数据"（后续 harness 断言锚点，勿改字）。
     * 极值均取全窗口 helper（since=0，含端点恒全员参与；无效值过滤规则见 HistoryStore 类注释）。
     */
    private void showHistory(CommandSender sender) {
        List<HistorySample> samples = historyStore.samples();
        sender.sendMessage(Component.text("=== 历史摘要 ===", GOLD, TextDecoration.BOLD));
        if (samples.isEmpty()) {
            // 空态原因：刚启动/重载（首个采样未到）、采样间隔未到、或 history.enabled=false
            sender.sendMessage(Component.text("暂无历史数据（插件刚启动或首个采样间隔未到，稍后再试）", YELLOW));
            return;
        }

        HistorySample oldest = samples.get(0);
        HistorySample latest = samples.get(samples.size() - 1);
        // 覆盖时长 = 跨度分钟取整，至少显示 1（契约：<1 分钟不显示 0）
        long minutes = Math.max(1L, (latest.timestamp() - oldest.timestamp()) / 60000L);
        sender.sendMessage(goldLabel("覆盖: ")
            .append(Component.text("近 " + minutes + " 分钟（" + samples.size() + " 个样本）", WHITE)));

        appendHistoryExtreme(sender, "最低 TPS: ", historyStore.lowestTpsSince(0L),
            s -> String.format("%.2f", s.tps1m()));
        appendHistoryExtreme(sender, "峰值实体: ", historyStore.peakEntitiesSince(0L),
            s -> String.valueOf(s.totalEntities()));
        appendHistoryExtreme(sender, "峰值内存: ", historyStore.peakHeapSince(0L),
            s -> s.heapUsagePercent() + "%");
    }

    /** Wave 7A：历史极值行——"{label}{value}（{HH:mm}）"；helper 返回 null（窗口内全为无效值）时渲染"无有效样本"。 */
    private void appendHistoryExtreme(CommandSender sender, String label, HistorySample sample,
                                      Function<HistorySample, String> formatter) {
        if (sample == null) {
            sender.sendMessage(goldLabel(label).append(Component.text("无有效样本", GRAY)));
            return;
        }
        sender.sendMessage(goldLabel(label)
            .append(Component.text(formatter.apply(sample), WHITE))
            .append(Component.text("（" + formatHistoryTime(sample.timestamp()) + "）", GRAY)));
    }

    // ==================== Wave 8A：智能诊断（规则引擎） ====================

    /**
     * 智能诊断（Wave 8A）：运行规则引擎并输出结论 + 参数级建议。
     *
     * <p>输出锚点（harness 依赖）：标题 {@code === 智能诊断 ===}；无命中时 {@code 未发现已知问题}；
     * 每条命中含 {@code [ID] 标题} 行与 {@code 建议: } 行。能力边界声明逐字保留
     * （诚实划界——"本引擎只覆盖 N 条高置信规则"，防止被当成 spark 的替代品）。</p>
     */
    private void showDiagnose(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) {
            noData(sender);
            return;
        }
        sender.sendMessage(Component.text("=== 智能诊断 ===", GOLD, TextDecoration.BOLD));
        List<Diagnosis> hits = plugin.getDiagnosticEngine().run();
        if (hits.isEmpty()) {
            sender.sendMessage(Component.text("未发现已知问题（已检查 " + DiagnosticEngine.ruleCount()
                + " 条高置信规则）", GREEN));
        } else {
            for (Diagnosis d : hits) {
                TextColor color = d.critical() ? RED : YELLOW;
                sender.sendMessage(Component.text(" [" + d.ruleId() + "] ", color)
                    .append(Component.text(d.title(), color, TextDecoration.BOLD)));
                sender.sendMessage(Component.text("  " + d.detail(), WHITE));
                sender.sendMessage(Component.text("  建议: ", GRAY)
                    .append(Component.text(d.suggestion(), AQUA)));
            }
        }
        sender.sendMessage(Component.text("提示: 本引擎覆盖 " + DiagnosticEngine.ruleCount()
            + " 条高置信规则，方法级深挖请用 /spark profiler", GRAY));
    }

    // ==================== Wave 8B：趋势（跨重启历史） ====================

    /**
     * 历史趋势（Wave 8B）：读文件持久化 + 内存环的合并样本，输出跨重启可查的统计。
     *
     * <p>用法：{@code /perfscope trend [天数]}（默认 1 天，钳 1..配置保留天数）。
     * 输出锚点（harness 依赖）：标题 {@code === 趋势摘要 ===}；行含 {@code 样本}、
     * {@code 最低 TPS: }、{@code 峰值内存: }；空态 {@code 暂无趋势数据}。
     * 与 /perfscope history 的区别：history 只看内存环（近 24h 且重启清零），
     * trend 合并文件持久化样本（可跨重启、可查多日）。</p>
     */
    private void showTrend(CommandSender sender, String[] args) {
        var fileStore = plugin.getHistoryFileStore();
        int maxDays = configManager.get().history_persistRetentionDays;
        int days = 1;
        if (args.length > 1) {
            try {
                days = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("天数需为整数（1-" + maxDays + "），已按 1 天处理", YELLOW));
            }
        }
        days = Math.max(1, Math.min(maxDays, days));

        long sinceMs = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        // 合并单一事实源：优先文件持久化（跨重启）；不可用/空时回退内存环（本进程会话）
        List<HistorySample> samples = fileStore == null ? List.of() : fileStore.loadRecentDays(days, sinceMs);
        boolean fromFile = !samples.isEmpty();
        if (!fromFile) {
            samples = historyStore.since(sinceMs);
        }

        sender.sendMessage(Component.text("=== 趋势摘要 ===", GOLD, TextDecoration.BOLD));
        if (samples.isEmpty()) {
            sender.sendMessage(Component.text("暂无趋势数据（历史持久化启用后需积累采样；本进程会话亦无样本）", YELLOW));
            return;
        }
        String source = fromFile ? "文件持久化" : "内存环（本进程会话）";
        sender.sendMessage(goldLabel("范围: ")
            .append(Component.text("近 " + days + " 天（" + samples.size() + " 个样本，来源: " + source + "）", WHITE)));

        HistorySample oldest = samples.get(0);
        HistorySample latest = samples.get(samples.size() - 1);
        double minTps = Double.MAX_VALUE;
        HistorySample minTpsAt = null;
        int peakHeap = -1;
        HistorySample peakHeapAt = null;
        double sumTps = 0;
        int tpsCount = 0;
        for (HistorySample s : samples) {
            if (s.tps1m() > 0 && s.tps1m() <= 20.5) {
                sumTps += s.tps1m();
                tpsCount++;
                if (s.tps1m() < minTps) {
                    minTps = s.tps1m();
                    minTpsAt = s;
                }
            }
            if (s.heapUsagePercent() > 0 && s.heapUsagePercent() > peakHeap) {
                peakHeap = s.heapUsagePercent();
                peakHeapAt = s;
            }
        }
        if (tpsCount > 0) {
            sender.sendMessage(goldLabel("平均 TPS: ")
                .append(Component.text(String.format(java.util.Locale.ROOT, "%.2f", sumTps / tpsCount), WHITE))
                .append(Component.text("（" + tpsCount + " 个有效样本）", GRAY)));
            sender.sendMessage(goldLabel("最低 TPS: ")
                .append(Component.text(String.format(java.util.Locale.ROOT, "%.2f", minTps), WHITE))
                .append(Component.text("（" + formatTrendTime(minTpsAt.timestamp()) + "）", GRAY)));
        } else {
            sender.sendMessage(goldLabel("最低 TPS: ").append(Component.text("无有效样本", GRAY)));
        }
        if (peakHeapAt != null) {
            sender.sendMessage(goldLabel("峰值内存: ")
                .append(Component.text(peakHeap + "%", WHITE))
                .append(Component.text("（" + formatTrendTime(peakHeapAt.timestamp()) + "）", GRAY)));
        }
        sender.sendMessage(Component.text("覆盖: " + formatTrendTime(oldest.timestamp())
            + " ～ " + formatTrendTime(latest.timestamp()) + "（本地时区）", GRAY));
    }

    /** Wave 8B：趋势时间戳 → "MM-dd HH:mm"（跨天查询需带日期）。 */
    private String formatTrendTime(long epochMs) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs));
    }

    /** Wave 7A：历史时间戳 → "HH:mm"（本地时区，与 showAll 采集时间行同口径）。 */
    private String formatHistoryTime(long epochMs) {
        return DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs));
    }

    // ==================== Wave 8C：热点区块定位 ====================

    /**
     * 热点区块 Top3（Wave 8C）：全服实体密度最高的区块坐标（含玩家）。
     * 输出锚点（harness 依赖，逐字稳定）：标题 {@code === 热点区块 Top3}、
     * 行内 {@code （方块 } 与 {@code ，主导 }、头部 {@code 采样于 HH:mm:ss，含玩家）}。
     * 玩家视角：坐标 {@code [x,z]} 可点击复制（copyToClipboard，"x z" 形态可直接喂给 /tp）；
     * 控制台/RCON 保持纯文本（Wave 5D 报告路径同款口径）。
     */
    private void showHotspots(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) {
            noData(sender);
            return;
        }
        String stamp = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(snap.timestamp));
        sender.sendMessage(Component.text("=== 热点区块 Top3（采样于 " + stamp + "，含玩家）===", GOLD, TextDecoration.BOLD));
        // P0-5/Wave 8C：模块被禁用或采集异常、或首采样未到 → 空态（头部同款渲染，便于断言）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_HOTSPOTS) || snap.topEntityChunks.isEmpty()) {
            sender.sendMessage(Component.text("暂无热点数据（首轮采样未到或模块不可用，稍后再试）", YELLOW));
            return;
        }
        List<ChunkHotspot> top = snap.topEntityChunks;
        int shown = Math.min(3, top.size());
        for (int i = 0; i < shown; i++) {
            ChunkHotspot hs = top.get(i);
            Component coord = Component.text("[" + hs.chunkX() + "," + hs.chunkZ() + "]", AQUA);
            if (sender instanceof Player) {
                String tpArg = hs.blockX() + " " + hs.blockZ(); // 区块中心（可直接喂 /tp）
                coord = coord.decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.copyToClipboard(tpArg))
                    .hoverEvent(HoverEvent.showText(Component.text("点击复制区块中心坐标 (" + tpArg + ")", AQUA)));
            }
            sender.sendMessage(Component.text(" " + (i + 1) + ". ", GRAY)
                .append(Component.text(hs.world() + " ", WHITE))
                .append(coord)
                .append(Component.text("（方块 " + hs.blockX() + ",~," + hs.blockZ() + "）", GRAY))
                .append(Component.text("实体 ", WHITE))
                .append(Component.text(hs.entityCount() + " 个", thresholdColors.colorOf(
                    thresholdColors.levelOf(ThresholdColors.Metric.ENTITIES, hs.entityCount(), hs.world()))))
                .append(Component.text("，主导 ", GRAY))
                .append(Component.text(hs.dominantType(), WHITE))
                .append(Component.text(" " + hs.dominantPercent() + "%", GRAY)));
        }
    }

    // ==================== 专项检测: GC ====================

    private void showGC(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== GC 统计 ===", GOLD, TextDecoration.BOLD));
        // P0-5：模块被禁用或采集异常时显示"不可用"；W5-D #1：预热态渲染"预热中"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            sender.sendMessage(goldLabel("累计 GC 次数: ").append(unavailableText(snap, MetricsSnapshot.MODULE_GC)));
            return;
        }
        sender.sendMessage(goldLabel("累计 GC 次数: ")
            .append(Component.text(String.valueOf(snap.gcCount), WHITE, TextDecoration.BOLD)));
        sender.sendMessage(goldLabel("累计 GC 耗时: ")
            .append(Component.text(String.format("%.2f 秒", snap.gcTime / 1000.0), WHITE)));

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("GC 收集器详情:", GRAY));
        for (java.lang.management.GarbageCollectorMXBean gcBean :
            java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();
            sender.sendMessage(Component.text("  " + gcBean.getName() + ": ", WHITE)
                .append(Component.text(count + " 次 / " + time + "ms", AQUA)));
        }
    }

    // ==================== 报告 ====================

    private void handleReport(CommandSender sender, String[] args) {
        // P0-10：admin || 专项（children 兜底 + 显式检查，双保险）
        if (!sender.hasPermission("perfscope.admin") && !sender.hasPermission("perfscope.export")) {
            sender.sendMessage(Component.text("没有导出报告权限喵", RED));
            return;
        }
        String path = args.length > 1 ? reportGen.generateSpecific(args[1]) : reportGen.generateAndSave();
        if (path != null) {
            // W5-D #2：报告是唯一的"证据固化"出口，路径却不可点——聊天框无法选中复制，60 字符盘符路径
            // 手抄不现实。玩家挂 copyToClipboard（悬停提示"点击复制路径"）；控制台/RCON 保持纯文本
            //（控制台无点击交互语义，路径仍是可用的诊断信息）
            if (sender instanceof Player) {
                sender.sendMessage(Component.text("报告已生成: ", GREEN)
                    .append(Component.text(path, GREEN, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.copyToClipboard(path))
                        .hoverEvent(HoverEvent.showText(Component.text("点击复制路径", GREEN)))));
            } else {
                sender.sendMessage(Component.text("报告已生成: " + path, GREEN));
            }
        } else {
            sender.sendMessage(Component.text("报告生成失败喵", RED));
        }
    }

    // ==================== 重载 ====================

    private void handleReload(CommandSender sender) {
        // P0-10：admin || 专项
        if (!sender.hasPermission("perfscope.admin") && !sender.hasPermission("perfscope.reload")) {
            sender.sendMessage(Component.text("没有重载配置权限喵", RED));
            return;
        }
        plugin.reload();
        sender.sendMessage(Component.text("配置已重载喵~", GREEN));
    }

    // ==================== 实时监测 ====================

    private void toggleMonitor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("此功能仅玩家可用喵", YELLOW));
            return;
        }

        if (!ServerCompat.PAPER_ACTION_BAR_AVAILABLE) {
            player.sendMessage(Component.text("实时监测功能需要 Paper 服务端支持 ActionBar 喵", YELLOW));
            return;
        }

        if (monitoringPlayers.contains(player.getUniqueId())) {
            monitoringPlayers.remove(player.getUniqueId());
            player.sendMessage(Component.text("实时监测已关闭喵~", GREEN));
            if (monitoringPlayers.isEmpty() && monitorTask != null) {
                monitorTask.cancel();
                monitorTask = null;
            }
        } else {
            monitoringPlayers.add(player.getUniqueId());
            player.sendMessage(Component.text("实时监测已开启喵~ 使用 /perfscope monitor 关闭", GREEN));
            if (monitorTask == null) {
                startMonitorTask();
            }
        }
    }

    private void startMonitorTask() {
        // P0-8：经 SchedulerAdapter.runGlobalTimer 调度，保持 20 tick/次推送间隔。
        // Folia 下该任务运行于 GlobalRegionScheduler（全局区域线程）；给玩家 sendActionBar 的
        // 跨 region 线程问题由 Adventure 消息发送的线程安全性覆盖；A2 修正：本任务体自带 try 边界
        //（此前注释谎称"外层 try 兜底"但实际无 try——单帧异常会被调度器记日志并继续重排，导致每周期刷一条异常噪音）
        monitorTask = SchedulerAdapter.runGlobalTimer(plugin, () -> {
            // A2（T1b）：整帧构建+推送包 try 边界——任何异常仅跳过本轮并记日志，不中断 monitor 任务
            try {
                if (monitoringPlayers.isEmpty()) {
                    if (monitorTask != null) {
                        monitorTask.cancel();
                        monitorTask = null;
                    }
                    return;
                }
                MetricsSnapshot snap = collector.getLatest();
                if (snap == null) return;

                // P0-12：显式 Iterator 遍历清理离线玩家，避免 CME；A2（T1d）保留该清理并叠加发送前守卫
                for (Iterator<UUID> it = monitoringPlayers.iterator(); it.hasNext();) {
                    UUID uuid = it.next();
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) {
                        it.remove();
                        continue;
                    }
                    sendActionBar(player, snap);
                }
            } catch (Exception | LinkageError t) {
                // general.debug 门控堆栈（与 safeCollect 同款日志口径）
                if (configManager.get().general_debug) {
                    plugin.getLogger().log(Level.WARNING, "[PerfScope] monitor 推送失败（已跳过本轮）", t);
                } else {
                    plugin.getLogger().warning("[PerfScope] monitor 推送失败（已跳过本轮）: " + t.getMessage());
                }
            }
        }, 20L, 20L);
    }

    private void sendActionBar(Player player, MetricsSnapshot snap) {
        // A2（T1c）：发送前连接守卫——Paper 的 sendActionBar(Component) 变体无 connection null 检查
        //（javap 实证，与 sendMessage 变体不对称）；若"isOnline 通过但 connection 已置 null"的
        // 断线窗口存在，直接发送会抛 NPE 并中断本轮其余玩家推送。
        // getAddress() 在 connection==null 时返回 null（CraftPlayer 字节码实证），作为公开 API 层守卫；
        // 守卫失败跳过该玩家本轮推送，下一周期自动重试（窗口若不存在该守卫亦无害）
        if (!player.isOnline() || player.getAddress() == null) {
            return;
        }

        // A2（T1a）：六字段消费三态约定，与 showAll 同口径——
        // module unavailable → 该段"不可用"（灰；内存模块预热态除外，见下）；MSPT -1 哨兵由 formatMSPTComponent 渲染"未知"（灰）。
        // 修复禁用模块上屏 "TPS: 0.0"（红）/ "内存: 0%"（假绿）/ MSPT 直出 "-1.0" 的误导渲染
        Component actionBar = Component.text("TPS: ", GRAY)
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)
                ? Component.text("不可用", GRAY) : formatTpsComponent(snap.tps1m))
            .append(Component.text(" | MSPT: ", GRAY))
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)
                ? Component.text("不可用", GRAY) : formatMSPTComponent(snap.msptAvg))
            .append(Component.text(" | 玩家: ", GRAY))
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)
                ? Component.text("不可用", GRAY)
                : Component.text(snap.playerCount + "/" + snap.maxPlayers, WHITE))
            .append(Component.text(" | 实体: ", GRAY))
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)
                ? Component.text("不可用", GRAY)
                : Component.text(String.valueOf(snap.totalEntities), WHITE))
            .append(Component.text(" | 内存: ", GRAY))
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)
                // W5-D #1：ActionBar 空间有限，预热态用短文案"预热中"（区别于真故障"不可用"）
                ? Component.text(snap.warmingUp.contains(MetricsSnapshot.MODULE_MEMORY) ? "预热中" : "不可用", GRAY)
                : Component.text(snap.heapUsagePercent + "%",
                    // v1.2 A8：改为读 config 阈值（原硬编码 90/80）
                    thresholdColors.colorOf(thresholdColors.levelOf(
                        ThresholdColors.Metric.MEMORY, snap.heapUsagePercent))))
            .append(Component.text(" | 区块: ", GRAY))
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)
                ? Component.text("不可用", GRAY)
                : Component.text(String.valueOf(snap.totalLoadedChunks), WHITE));

        player.sendActionBar(actionBar);
    }

    // ==================== 帮助 ====================

    private void showHelp(CommandSender sender) {
        sender.sendMessage(Component.text("=== PerfScope 帮助 v" + plugin.getDescription().getVersion() + " ===", GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text(""));

        sendHelpLine(sender, "/perfscope", "全量诊断（默认）");
        sendHelpLine(sender, "/perfscope all", "全量诊断（同无参数）");
        sendHelpLine(sender, "/perfscope tps", "TPS 专项检测");
        sendHelpLine(sender, "/perfscope mspt", "MSPT 专项检测");
        // A5（T5a）：带占位符的用法行——display 用文档原文，click 用可执行实例命令。
        // 此前 display 原文被直接当命令执行：点 entities [世界] 以假世界名查询、点 report [格式] 每次静默多生成一份 TXT
        sendHelpLine(sender, "/perfscope entities [世界]", "/perfscope entities", "实体统计");
        sendHelpLine(sender, "/perfscope chunks [世界]", "/perfscope chunks", "区块统计");
        sendHelpLine(sender, "/perfscope memory", "内存诊断");
        sendHelpLine(sender, "/perfscope cpu", "CPU 诊断");
        sendHelpLine(sender, "/perfscope players", "玩家统计");
        sendHelpLine(sender, "/perfscope world [世界]", "/perfscope world", "世界详情");
        sendHelpLine(sender, "/perfscope redstone", "红石活动（已下线）");
        // W5-D #4：te 自 A5 起已接受世界参数（te foo → 世界不存在: foo），help 补 [世界] 与 entities/chunks 行对齐
        sendHelpLine(sender, "/perfscope tileentities [世界]", "/perfscope tileentities", "Tile Entity 统计");
        sendHelpLine(sender, "/perfscope uptime", "运行时间");
        // Wave 7A：历史摘要（24h 环形缓冲）
        sendHelpLine(sender, "/perfscope history", "查看历史摘要（近 24 小时）");
        // Wave 8C：热点区块定位
        sendHelpLine(sender, "/perfscope hotspot", "热点区块定位（实体密度 Top3 + 坐标）");
        // Wave 8A：智能诊断引擎（diagnose 不再是 all 的别名；diag 保持全量诊断）
        sendHelpLine(sender, "/perfscope diagnose", "智能诊断（规则引擎：结论 + 参数级建议）");
        // Wave 8B：跨重启趋势
        sendHelpLine(sender, "/perfscope trend [天数]", "/perfscope trend", "趋势摘要（跨重启历史，默认 1 天）");
        sendHelpLine(sender, "/perfscope gc", "GC 统计");
        sendHelpLine(sender, "/perfscope report [格式]", "/perfscope report html", "导出性能报告");
        sendHelpLine(sender, "/perfscope monitor", "切换 ActionBar 实时监测");
        sendHelpLine(sender, "/perfscope reload", "重载配置文件");

        if (sender instanceof Player) {
            sender.sendMessage(Component.text(""));
            sender.sendMessage(Component.text("提示: 所有命令均可点击快速跳转", GRAY));
        }
    }

    // ==================== Tab补全 ====================

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                                 @NotNull String label, @NotNull String[] args) {
        // P0-10：无 use 权限者返回空列表
        if (!sender.hasPermission("perfscope.use")) {
            return Collections.emptyList();
        }
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String[] subs = {"all", "tps", "mspt", "entities", "chunks", "memory", "cpu",
                "players", "world", "redstone", "tileentities", "uptime", "history", "hotspot", "diagnose", "trend", "gc",
                "report", "monitor", "reload", "help"};
            boolean canExport = sender.hasPermission("perfscope.admin") || sender.hasPermission("perfscope.export");
            boolean canReload = sender.hasPermission("perfscope.admin") || sender.hasPermission("perfscope.reload");
            for (String s : subs) {
                // P0-10：report/reload 按权限过滤
                if (s.equals("report") && !canExport) continue;
                if (s.equals("reload") && !canReload) continue;
                if (s.startsWith(args[0].toLowerCase())) completions.add(s);
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "entities": case "entity": case "e":
                case "chunks": case "chunk": case "c":
                case "world": case "w":
                case "tileentities": case "te":
                    for (World world : Bukkit.getWorlds()) {
                        if (world.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                            completions.add(world.getName());
                        }
                    }
                    break;
                case "report":
                    for (String fmt : new String[]{"html", "txt", "json"}) {
                        if (fmt.startsWith(args[1].toLowerCase())) completions.add(fmt);
                    }
                    break;
            }
        }

        return completions;
    }

    // ==================== UI 工具方法 ====================

    private Component goldLabel(String label) {
        return Component.text(label, GOLD);
    }

    private Component yellowLabel(String label) {
        return Component.text(label, YELLOW);
    }

    /**
     * W5-D #1：模块降级文案——预热中与真故障区分渲染。
     * 预热态（warmingUp ⊆ unavailable：reload/启动后 JVM 异步首轮未就绪，约 1 秒自愈）渲染"预热中"，
     * 与"模块采集失败/被禁用"的"不可用"区分——两者此前同款文案，在事故语境下是虚假信标。
     */
    private Component unavailableText(MetricsSnapshot snap, String module) {
        return snap.warmingUp.contains(module)
            ? Component.text("预热中（首轮采集未就绪，稍候自动更新）", GRAY)
            : Component.text("不可用", GRAY);
    }

    /** W5-D #1：运行时间行的预热文案（与 unavailableText 同词，独立方法因不涉模块判定）。 */
    private Component uptimeWarmupText() {
        return Component.text("预热中（首轮采集未就绪，稍候自动更新）", GRAY);
    }

    private void sendHelpLine(CommandSender sender, String cmd, String desc) {
        // 无占位符行：display 与可执行命令相同
        sendHelpLine(sender, cmd, cmd, desc);
    }

    private void sendHelpLine(CommandSender sender, String display, String clickCmd, String desc) {
        // A5（T5a）：display=文档用法（可含 [世界]/[格式] 占位符），clickCmd=可执行实例命令（硬编码字面量，无拼接注入面）
        Component base = Component.text("  " + display, AQUA)
            .hoverEvent(HoverEvent.showText(Component.text("点击执行 " + clickCmd, GREEN)))
            .clickEvent(ClickEvent.runCommand(clickCmd));
        Component descComp = Component.text(" - " + desc, GRAY);
        sender.sendMessage(base.append(descComp));
    }

    private void noData(CommandSender sender) {
        // W5-D #3：说清原因+时限+下一步——旧文案只说"请稍候"，管理员在跑了数天的服上见到它
        // 只会困惑"数据哪去了、是不是我把插件弄坏了"；10 秒与采集侧预热窗口（WARMUP_WINDOW_MS）同口径
        sender.sendMessage(Component.text(
            "首次采集尚未完成（插件刚启动/重载），约 1 秒后就绪；若超过 10 秒仍无数据请查看控制台", YELLOW));
    }

    /**
     * A5（命令侧）：解析世界参数并返回规范名世界对象。
     * 输入 null → 返回 null（语义："不按世界过滤"，调用方自行区分）。
     * 输入非 null 但世界不存在 → 发送"世界不存在: <输入>"明确反馈并返回 null，
     * 调用方须在输入非 null 且返回 null 时提前 return（替代旧实现静默空筛选）。
     */
    private World resolveWorld(CommandSender sender, String worldName) {
        if (worldName == null) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(Component.text("世界不存在: " + worldName, RED));
        }
        return world;
    }

    private Component formatTpsComponent(double tps) {
        // v1.2 A8：改为读 config 阈值（原硬编码 18/15，与默认巧合一致）
        NamedTextColor color = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.TPS, tps));
        return Component.text(String.format("%.1f", Math.min(tps, 20.0)), color, TextDecoration.BOLD);
    }

    private Component formatMSPTComponent(double mspt) {
        // P0-13：<= 0（含 -1 哨兵与旧 0 值）显示"未知"；真实 MSPT 恒 > 0，无误判
        if (mspt <= 0) {
            return Component.text("未知", GRAY);
        }
        // v1.2 A8：改为读 config 阈值（原硬编码 40/50——黄界 40 曾与配置 45 脱节）
        NamedTextColor color = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.MSPT, mspt));
        return Component.text(String.format("%.2f ms", mspt), color, TextDecoration.BOLD);
    }

    private String generateBar(int percent, int length) {
        int filled = Math.max(0, Math.min(length, percent * length / 100));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(i < filled ? "|" : ".");
        }
        return sb.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String formatUptime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("天 ");
        if (hours > 0) sb.append(hours).append("时 ");
        if (minutes > 0) sb.append(minutes).append("分 ");
        sb.append(secs).append("秒");
        return sb.toString();
    }

    // ==================== 清理 ====================

    /** P0-12：玩家退出时清理监测态（需 PerfScope.onEnable 注册本类为 Listener）。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        monitoringPlayers.remove(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        monitoringPlayers.clear();
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
    }
}