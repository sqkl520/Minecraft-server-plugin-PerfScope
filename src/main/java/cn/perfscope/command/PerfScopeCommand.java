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
import cn.perfscope.alert.Metric;
import cn.perfscope.alert.WebhookNotifier;
import cn.perfscope.alert.WebhookResponse;
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
import cn.perfscope.util.DurationFormat;
import cn.perfscope.util.Messages;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * PerfScope 指令系统 - 全量诊断 + 专项检测
 * /perfscope (无参数) 或 /perfscope all  →  全量诊断
 * /perfscope tps|mspt|entities|...        →  专项检测
 */
public class PerfScopeCommand implements CommandExecutor, TabCompleter, Listener {

    static {
        // 9D：兜底触碰命令层文案表。PerfScope 已在 Messages.load 之前登记过
        // CommandMessages::init，这里再调一次是幂等的——将来若有人漏掉那行登记，
        // 命令层的键仍会在本类首次加载时进入默认表，而不是静默退化成键名。
        CommandMessages.init();
    }

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
    /**
     * P0-8 / 9B-5：monitor 定时任务句柄（经 SchedulerAdapter 统一调度，Folia 走 GlobalRegionScheduler）；
     * null 表示未运行。
     *
     * <p><b>为什么必须是 AtomicReference</b>：Folia 下本字段有两个写入方，且不在同一线程——
     * ① 命令线程（玩家所在 region 线程）在 {@link #toggleMonitor} 里启停；
     * ② monitor 任务自身（GlobalRegionScheduler 线程）在发现无人订阅时自取消。
     * 旧的普通字段 + read-modify-write 会让两边的"读-判-写"交错，丢失更新：
     * "提示已开启但 ActionBar 不刷新"（丢失了启动写）或"一次开却有两份刷新任务"（丢失了置空写）。
     * 现在所有启停都用 CAS 唯一裁决，任务体只认"自己仍是当前句柄"才动手。
     * 非 volatile 旧字段本身也有可见性问题（Folia 上跨 region 线程读可能长期读旧值）。</p>
     */
    private final AtomicReference<SchedulerAdapter.TaskHandle> monitorTask = new AtomicReference<>();

    /**
     * 9B-5：monitor 任务的"正在安装"门闩。
     *
     * <p>{@link SchedulerAdapter#runGlobalTimer} 返回句柄之前任务已经提交给调度器，
     * 这段时间里 {@link #monitorTask} 仍是 null——若两个线程同时走到创建分支，
     * 就会各建一份刷新任务（"一次开却有两份刷新"）。用 CAS 门闩把创建区段串行化，
     * 第二个线程直接让位（第一个线程装好的任务已能满足它的订阅需求）。</p>
     */
    private final AtomicBoolean monitorTaskStarting = new AtomicBoolean(false);

    /** 9B-5：shutdown() 之后禁止再拉起任务（否则并发的 toggle-on 会在关服期留下野任务）。 */
    private volatile boolean monitorShutdown = false;

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
            sender.sendMessage(Component.text(Messages.get("no-permission"), RED));
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
            // v1.4：webhook 自检。三个国内平台的失败模式全是静默的（HTTP 200 + 业务码），
            // 服主唯一的观测渠道是"告警群一直没消息"，而"没有告警"和"告警没送到"看起来一样。
            case "webhook":
                handleWebhook(sender, args);
                break;
            // 9A-20 #5：插件自身健康（采集存活/模块三态/历史/告警）——此前对内全瞎，只能全量 reload
            case "status":
                showStatus(sender);
                break;
            // 9A-14 #4：告警历史（此前只有"当前活跃"，没有"何时开始/持续多久/恢复了吗"）
            case "alerts": case "alarm":
                showAlerts(sender, args);
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
        sender.sendMessage(Component.text(Messages.get("cmd.section.diagnostics"), GOLD, TextDecoration.BOLD));
        // 9A-2：Folia 上"服务端"行必须自带口径说明——Bukkit.getTPS() 是 global region 局部值、
        // MSPT 无全局 tick 时间，否则读者会把下面 TPS/MSPT 两行当成全服读数。
        Component serverLine = Component.text(
            Messages.get("cmd.all.server-label") + ServerCompat.getServerName(), GRAY);
        if (ServerCompat.isFolia()) {
            // 两条完整文案而非半句拼接：中英文的括号与分号位置不同，拼接会译不自然
            serverLine = serverLine.append(Component.text(ServerCompat.isMsptTrustworthy()
                ? Messages.get("cmd.all.folia-scope")
                : Messages.get("cmd.all.folia-scope-mspt"), GRAY));
        }
        sender.sendMessage(serverLine);
        sender.sendMessage(Component.text(Messages.get("cmd.all.collect-time-label")
            + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(snap.timestamp)), GRAY));

        // TPS（P0-5：模块不可用显示"不可用"；9A-2：Folia 追加 global region 口径标注）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            sender.sendMessage(yellowLabel(Messages.get("cmd.tps.triple-label"))
                .append(Component.text(Messages.get("cmd.state.unavailable"), GRAY)));
        } else {
            sender.sendMessage(yellowLabel(Messages.get("cmd.tps.triple-label"))
                .append(formatTpsComponent(snap.tps1m))
                .append(Component.text(" / ", WHITE))
                .append(formatTpsComponent(snap.tps5m))
                .append(Component.text(" / ", WHITE))
                .append(formatTpsComponent(snap.tps15m))
                .append(tpsScopeTag()));
        }

        // MSPT（P0-5：先判模块级"不可用"；P0-13：再判 -1 哨兵显示"未知"）
        // 9A-1：Folia 上 MSPT 能力位恒 false（无全局 tick 时间）——此时既不能出数、也不能用
        // region 局部 TPS 反推，直接按"不可用"渲染并说明原因。
        // f-s02-logic-01：false 有"两种 false"，必须按 isFolia() 分叉（ServerCompat:437-438 的豁免条款）——
        // 非 Folia 上采集层已降级到 TPS 反推（MetricsCollector:1356），放行进下方由 msptSource 标"(估算)"。
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)
                || (!ServerCompat.isMsptTrustworthy() && ServerCompat.isFolia())) {
            sender.sendMessage(yellowLabel(Messages.get("cmd.mspt.triple-label"))
                .append(Component.text(Messages.get("cmd.state.unavailable"), GRAY))
                .append(msptUnavailableTag()));
        } else {
            // A1（命令侧，9A-0 修正）："(估算)"按**本快照实际采集路径**标注，不再用静态能力位推断。
            // 静态位只回答"这台服务端理论上支持什么"；实际 invoke 可能失败并降级，那种情况下
            // 旧实现会把 TPS 反推值当精确值展示，且不显示"(估算)"。
            Component msptSource = snap.msptSource == MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK
                ? Component.text(Messages.get("cmd.mspt.estimated-tag"), GRAY) : Component.empty();
            Component msptMinAll = snap.msptMin <= 0 ? Component.text(Messages.get("cmd.state.not-collected"), GRAY)
                : Component.text(String.format(java.util.Locale.ROOT, "%.2f", snap.msptMin) + "ms", WHITE);
            Component msptMaxAll = snap.msptMax <= 0 ? Component.text(Messages.get("cmd.state.not-collected"), GRAY)
                : Component.text(String.format(java.util.Locale.ROOT, "%.2f", snap.msptMax) + "ms", WHITE);
            sender.sendMessage(yellowLabel(Messages.get("cmd.mspt.triple-label"))
                .append(formatMSPTComponent(snap.msptAvg))
                .append(msptSource)
                .append(Component.text(" / ", WHITE))
                .append(msptMinAll)
                .append(Component.text(" / ", WHITE))
                .append(msptMaxAll));
        }

        // 在线玩家（P0-5：模块不可用显示"不可用"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.players.online-label"))
                .append(Component.text(Messages.get("cmd.state.unavailable"), GRAY)));
        } else {
            sender.sendMessage(goldLabel(Messages.get("cmd.players.online-label"))
                .append(Component.text(snap.playerCount + " / " + snap.maxPlayers, WHITE)));
        }

        // 实体总数（P0-5：模块不可用显示"不可用"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.entities.total-label"))
                .append(Component.text(Messages.get("cmd.state.unavailable"), GRAY)));
        } else {
            // v1.2 A8：改为读 config 阈值（原硬编码 200/全局warn）
            TextColor entityColor = thresholdColors.colorOf(
                thresholdColors.levelOf(ThresholdColors.Metric.ENTITIES, snap.totalEntities));
            sender.sendMessage(goldLabel(Messages.get("cmd.entities.total-label"))
                .append(Component.text(String.valueOf(snap.totalEntities), entityColor)));
        }

        // 加载区块（P0-5：模块不可用显示"不可用"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.chunks.loaded-label"))
                .append(Component.text(Messages.get("cmd.state.unavailable"), GRAY)));
        } else {
            sender.sendMessage(goldLabel(Messages.get("cmd.chunks.loaded-label"))
                .append(Component.text(String.valueOf(snap.totalLoadedChunks), WHITE))
                .append(Component.text(Messages.get("cmd.chunks.force-inline", snap.totalForceLoadedChunks), GRAY)));
        }

        // === 服务器级诊断 ===
        sender.sendMessage(Component.text(Messages.get("cmd.all.section.server"), GOLD, TextDecoration.BOLD));

        for (World world : Bukkit.getWorlds()) {
            int entityCount = snap.entitiesByWorld.getOrDefault(world.getName(), 0);
            int loadedChunks = snap.chunksByWorld.getOrDefault(world.getName(), 0);
            int forceLoaded = snap.forceLoadedChunksByWorld.getOrDefault(world.getName(), 0);
            int tileEntities = snap.tileEntitiesByWorld.getOrDefault(world.getName(), 0);

            // v1.2 A8：改为读 config 阈值（含 worlds 段按世界覆盖；原硬编码 500/200）
            TextColor eColor = thresholdColors.colorOf(
                thresholdColors.levelOf(ThresholdColors.Metric.ENTITIES, entityCount, world.getName()));

            sender.sendMessage(Component.text(Messages.get("cmd.all.world-label"), GRAY)
                .append(Component.text(world.getName(), WHITE))
                .append(Component.text(Messages.get("cmd.all.entities-inline"), GRAY))
                .append(Component.text(String.valueOf(entityCount), eColor))
                .append(Component.text(Messages.get("cmd.all.chunks-inline"), GRAY))
                .append(Component.text(String.valueOf(loadedChunks), WHITE))
                .append(Component.text(Messages.get("cmd.all.force-inline"), GRAY))
                .append(Component.text(String.valueOf(forceLoaded), WHITE))
                .append(Component.text(Messages.get("cmd.all.tile-entities-inline"), GRAY))
                .append(Component.text(String.valueOf(tileEntities), WHITE)));
        }

        // 内存（P0-5：模块不可用显示"不可用"；W5-D #1：预热态改渲染"预热中"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.memory.label"))
                .append(unavailableText(snap, MetricsSnapshot.MODULE_MEMORY)));
        } else {
            long usedMem = snap.heapUsed / 1024 / 1024;
            long maxMem = snap.heapMax / 1024 / 1024;
            // v1.2 A8：改为读 config 阈值（原硬编码 90/80）
            TextColor memColor = thresholdColors.colorOf(
                thresholdColors.levelOf(ThresholdColors.Metric.MEMORY, snap.heapUsagePercent));
            sender.sendMessage(goldLabel(Messages.get("cmd.memory.label"))
                .append(Component.text(Messages.get("cmd.memory.heap-line", usedMem, maxMem), WHITE))
                .append(Component.text(" (" + snap.heapUsagePercent + "%)", memColor)));
        }

        // CPU（P0-5：先判模块级"不可用"；P0-13：再判 -1 哨兵显示"未知"；W5-D #1：预热态渲染"预热中"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CPU)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.cpu.label"))
                .append(unavailableText(snap, MetricsSnapshot.MODULE_CPU)));
        } else {
            Component showAllProcessCpu = snap.processCpuLoad < 0 ? Component.text(Messages.get("cmd.cpu.process-unknown"), GRAY)
                : Component.text(Messages.get("cmd.cpu.process-value", String.format(java.util.Locale.ROOT, "%.1f", snap.processCpuLoad)), WHITE);
            Component showAllSystemCpu = snap.systemCpuLoad < 0 ? Component.text(Messages.get("cmd.cpu.system-unknown"), GRAY)
                : Component.text(Messages.get("cmd.cpu.system-value", String.format(java.util.Locale.ROOT, "%.1f", snap.systemCpuLoad)), WHITE);
            sender.sendMessage(goldLabel(Messages.get("cmd.cpu.label"))
                .append(showAllProcessCpu)
                .append(Component.text(" / ", GRAY))
                .append(showAllSystemCpu));
        }

        // 运行时间（P0-5：server 模块不可用显示"不可用"；W5-D #1：JVM 异步就绪前 uptime/线程数为默认 0，
        // 渲染"预热中"而非误导性的"0秒 | 线程: 0"。
        // 注：不标 unavailable——server 模块的 Bukkit 部分（插件数/世界数）首周期即有真值，契约不动）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_SERVER)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.uptime.label"))
                .append(Component.text(Messages.get("cmd.state.unavailable"), GRAY)));
        } else if (snap.uptimeSeconds <= 0) {
            sender.sendMessage(goldLabel(Messages.get("cmd.uptime.label"))
                .append(uptimeWarmupText()));
        } else {
            sender.sendMessage(goldLabel(Messages.get("cmd.uptime.label"))
                .append(Component.text(formatUptime(snap.uptimeSeconds), WHITE))
                .append(Component.text(Messages.get("cmd.all.threads-inline") + snap.threadCount, GRAY))
                .append(Component.text(Messages.get("cmd.all.plugins-inline") + snap.loadedPluginCount, GRAY))
                .append(Component.text(Messages.get("cmd.all.worlds-inline") + snap.worldCount, GRAY)));
        }

        // GC（P0-5：模块不可用显示"不可用"；W5-D #1：预热态渲染"预热中"）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.gc.label"))
                .append(unavailableText(snap, MetricsSnapshot.MODULE_GC)));
        } else {
            sender.sendMessage(goldLabel(Messages.get("cmd.gc.label"))
                .append(Component.text(Messages.get("cmd.gc.count-inline", snap.gcCount), WHITE))
                .append(Component.text(" / ", GRAY))
                .append(Component.text(Messages.get("cmd.gc.seconds", String.format(java.util.Locale.ROOT, "%.2f", snap.gcTime / 1000.0)), WHITE)));
        }

        // P0-7：红石/网络段已下线删除

        // === 告警 ===
        if (!snap.activeCriticals.isEmpty() || !snap.activeWarnings.isEmpty()) {
            sender.sendMessage(Component.text(Messages.get("cmd.all.section.alerts"), RED, TextDecoration.BOLD));
            for (String critical : snap.activeCriticals) {
                sender.sendMessage(Component.text("  [!!!] " + critical, RED));
            }
            for (String warning : snap.activeWarnings) {
                sender.sendMessage(Component.text("  [!] " + warning, YELLOW));
            }
        }

        // MSPT 过高建议（v1.2 A8：触发条件改为读 config 阈值，原硬编码 50）
        if (thresholdColors.levelOf(ThresholdColors.Metric.MSPT, snap.msptAvg) == ThresholdColors.Level.CRITICAL) {
            sender.sendMessage(Component.text(Messages.get("cmd.advice.mspt-critical",
                (int) thresholdOf(cfgNow(), "mspt_criticalThreshold", 50.0)), RED));
            sender.sendMessage(Component.text(Messages.get("cmd.advice.mspt-suggestion"), GRAY));
            sender.sendMessage(Component.text(Messages.get("cmd.advice.spark-hint"), GRAY));
        }
    }

    // ==================== 专项检测: TPS ====================

    private void showTPS(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text(Messages.get("cmd.section.tps"), GOLD, TextDecoration.BOLD));
        // P0-5：模块被禁用或采集异常时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            sender.sendMessage(yellowLabel(Messages.get("cmd.tps.label")).append(Component.text(Messages.get("cmd.state.unavailable"), GRAY)));
            return;
        }
        // 9A-2：Folia 上 Bukkit.getTPS() 是 global region 局部值（只覆盖 global region 的 tick 吞吐，
        // 不含各 region 的实体/区块逻辑），不是全服 TPS。此处如实标注口径，
        // 并在下方额外说明"为什么这不是全服读数"，避免管理员拿它跟 /tps 对比后误判。
        if (!ServerCompat.isTpsGlobalScope()) {
            sender.sendMessage(Component.text(Messages.get("cmd.tps.scope-note"), YELLOW));
        }
        sender.sendMessage(yellowLabel(Messages.get("cmd.tps.triple-label"))
            .append(formatTpsComponent(snap.tps1m))
            .append(Component.text(" / ", WHITE))
            .append(formatTpsComponent(snap.tps5m))
            .append(Component.text(" / ", WHITE))
            .append(formatTpsComponent(snap.tps15m))
            .append(tpsScopeTag()));

        // TPS 趋势图
        List<MetricsSnapshot> history = collector.getHistory();
        if (history.size() > 1) {
            int showCount = Math.min(60, history.size());
            sender.sendMessage(Component.text(Messages.get("cmd.tps.trend-title", showCount), GRAY));
            // A5（命令侧）：趋势位语义——"|" 满 / "." GOOD / ":" WARN / "!" CRITICAL；
            // v1.2 A8：四档判定改读 config（阈值来源 ThresholdColors.tpsTrendBucket）；
            // TPS 值 <=0 或该采样快照 MODULE_TPS 不可用时，该位是"数据缺失"（模块禁用/启动期写入的 0 值），
            // 渲染为灰色 "?"，不得画成 "!"——"数据缺失"≠"TPS=0"，避免整段趋势被误读为"TPS 崩到 0"
            Component trend = Component.empty();
            int start = Math.max(0, history.size() - showCount);
            for (int i = start; i < history.size(); i++) {
                MetricsSnapshot sample = history.get(i);
                double tps = sample.tps1m;
                if (sample.unavailable.contains(MetricsSnapshot.MODULE_TPS) || tps <= 0) {  // @sentinel-ok 不参与统计，只是判断"这条样本的 TPS 不可用"；配合 unavailable 集合使用
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

        // 对应 MSPT 推算（9A-6：TPS=0 是服务器完全冻结时的合法读数，除零会打印 "Infinityms"，
        // 而那正是最需要读数的事故现场——必须单独处理而不是让它显示成无穷大）
        if (snap.tps1m > 0 && snap.tps1m < 20) {  // @sentinel-ok 推算等效 MSPT 前必须排除 0，否则 1000/0 = Infinity 会直接打到用户眼前
            double expectedMspt = 1000.0 / snap.tps1m;
            sender.sendMessage(Component.text(Messages.get("cmd.tps.equivalent-mspt", String.format(java.util.Locale.ROOT, "%.1f", expectedMspt)), GRAY));
        } else if (snap.tps1m <= 0) {  // @sentinel-ok 0 TPS 走"服务器无响应"专门分支并显示红字，不做除法；这是有意的
            sender.sendMessage(Component.text(Messages.get("cmd.tps.equivalent-mspt-unresponsive"), RED));
        }
    }

    // ==================== 专项检测: MSPT ====================

    private void showMSPT(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text(Messages.get("cmd.section.mspt"), GOLD, TextDecoration.BOLD));
        // P0-5：先判模块级"不可用"（P0-13 的 -1 哨兵"未知"仍由 formatMSPTComponent 处理）
        // 9A-1：能力位为 false（Folia 无全局 tick 时间 / 实测恒空）时不存在可信数据源——
        // 直接按"不可用"渲染并给出原因，绝不用 TPS 反推充数（Folia 的 TPS 是 region 局部值）。
        // f-s02-logic-01：同上，只有 Folia 的 false 才挡；非 Folia 的 false 走 TPS 反推降级，需放行标注。
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)
                || (!ServerCompat.isMsptTrustworthy() && ServerCompat.isFolia())) {
            sender.sendMessage(yellowLabel(Messages.get("cmd.mspt.label")).append(Component.text(Messages.get("cmd.state.unavailable"), GRAY))
                .append(msptUnavailableTag()));
            return;
        }
        // A1（命令侧，9A-0 修正）："(估算)"按本快照实际采集路径标注，与 showAll 同口径
        Component msptEstimate = snap.msptSource == MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK
            ? Component.text(Messages.get("cmd.mspt.estimated-tag"), GRAY) : Component.empty();
        sender.sendMessage(yellowLabel(Messages.get("cmd.mspt.avg-label"))
            .append(formatMSPTComponent(snap.msptAvg))
            .append(msptEstimate));
        // 9A-0：-1 = 该采集路径不提供极值（二级 avg-only / 三级反推），显示"未采集"而非伪造数字
        Component msptMinComp = snap.msptMin <= 0 ? Component.text(Messages.get("cmd.state.not-collected"), GRAY)
            : Component.text(String.format(java.util.Locale.ROOT, "%.2f ms", snap.msptMin), WHITE);
        Component msptMaxComp = snap.msptMax <= 0 ? Component.text(Messages.get("cmd.state.not-collected"), GRAY)
            : Component.text(String.format(java.util.Locale.ROOT, "%.2f ms", snap.msptMax), WHITE);
        sender.sendMessage(yellowLabel(Messages.get("cmd.mspt.min-label")).append(msptMinComp));
        sender.sendMessage(yellowLabel(Messages.get("cmd.mspt.max-label")).append(msptMaxComp));

        if (snap.msptAvg > 0) {
            double maxTPS = 1000.0 / snap.msptAvg;
            sender.sendMessage(goldLabel(Messages.get("cmd.mspt.max-tps-label"))
                .append(Component.text(String.format(java.util.Locale.ROOT, "%.1f", Math.min(maxTPS, 20.0)), AQUA)));
        }

        // v1.2 A8：触发条件与文案数值改为读 config 阈值（原硬编码 50）
        if (thresholdColors.levelOf(ThresholdColors.Metric.MSPT, snap.msptAvg) == ThresholdColors.Level.CRITICAL) {
            sender.sendMessage(Component.text(Messages.get("cmd.advice.mspt-critical",
                (int) thresholdOf(cfgNow(), "mspt_criticalThreshold", 50.0)), RED));
            sender.sendMessage(Component.text(Messages.get("cmd.advice.mspt-suggestion"), GRAY));
            sender.sendMessage(Component.text(Messages.get("cmd.advice.spark-hint"), GRAY));
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

        String title = world != null
            ? Messages.get("cmd.section.entities.world", world.getName())
            : Messages.get("cmd.section.entities");
        sender.sendMessage(Component.text(title, GOLD, TextDecoration.BOLD));

        // P0-5：模块被禁用或采集异常时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.entities.total-label")).append(Component.text(Messages.get("cmd.state.unavailable"), GRAY)));
            return;
        }

        // v1.2 A8：改为读 config 阈值（原硬编码 200/全局warn）
        TextColor entityColor = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.ENTITIES, snap.totalEntities));
        sender.sendMessage(goldLabel(Messages.get("cmd.entities.total-label"))
            .append(Component.text(String.valueOf(snap.totalEntities), entityColor, TextDecoration.BOLD)));

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text(Messages.get("cmd.label.by-world"), GRAY));
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
                    // 9B-4 同源防护：world.getEntities() 在跨线程增删实体时可能带 null 元素
                    //（Paper 的区块实体切片并发可见性）；此处是命令线程的即时扫描，一个 null 就会让整段
                    // 掉进"读取失败"。跳过脏元素而不是放弃整份统计。
                    if (entity == null) {
                        continue;
                    }
                    live.merge(entity.getType().name(), 1, Integer::sum);
                }
                scanned = live;
            } catch (Exception e) {
                // 9A-6：原来是 catch (Exception ignored) {}——Folia 跨区域限制等失败时
                // 用户在游戏内只看到"读取失败"，控制台没有任何根因，排障只能靠猜。
                plugin.getLogger().log(java.util.logging.Level.FINE,
                    "[PerfScope] Live world entity scan failed (world=" + world.getName()
                        + "); showing read failure", e);
                if (plugin.getConfigManager().get().general_debug) {
                    plugin.getLogger().warning("[PerfScope] World entity scan failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
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
            ? Messages.get("cmd.entities.by-type-world", world.getName())
            : Messages.get("cmd.entities.by-type-global"), GRAY));
        if (typeDist == null) {
            sender.sendMessage(Component.text(Messages.get("cmd.entities.scan-failed"), RED));
        } else {
            final Map<String, Integer> dist = typeDist;
            final int distTotal = typeDistTotal;
            dist.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .forEach(entry -> {
                    double pct = distTotal > 0 ? (double) entry.getValue() / distTotal * 100 : 0;
                    sender.sendMessage(Component.text("  " + entry.getKey() + ": ", WHITE)
                        .append(Component.text(entry.getValue() + " (" + String.format(java.util.Locale.ROOT, "%.1f", pct) + "%)", AQUA)));
                });
        }

        // f-s02-logic-03：世界视图必须判该世界自己的实体数与覆盖阈值，否则单世界页会打出全服级警告。
        // world == null（全服视图）保持原行为：全服总数 vs 全局阈值。
        // v1.4：这里的 2000/3000 只是"配置快照为 null"时的兜底（thresholdOf 的契约），
        // 实际几乎不可达；但仍改成与 jar 内 config.yml 一致的新默认，避免三处默认值漂移。
        Integer alertEntities;
        int warnThreshold;
        int criticalThreshold;
        if (world == null) {
            alertEntities = snap.totalEntities;
            warnThreshold = (int) thresholdOf(cfgNow(), "entities_totalWarningThreshold", 20000.0);
            criticalThreshold = (int) thresholdOf(cfgNow(), "entities_totalCriticalThreshold", 30000.0);
        } else {
            // 该世界不在 map 里 = 0 实体 → null → 不报警
            alertEntities = snap.entitiesByWorld.get(world.getName());
            // worlds 段无覆盖时 getWorldSettings 返回 null → 回退全局（同 ThresholdColors / showWorld 口径）
            ConfigManager.WorldSettings ws = configManager.getWorldSettings(world.getName());
            warnThreshold = ws != null ? ws.entities_totalWarningThreshold
                : (int) thresholdOf(cfgNow(), "entities_totalWarningThreshold", 20000.0);
            criticalThreshold = ws != null ? ws.entities_totalCriticalThreshold
                : (int) thresholdOf(cfgNow(), "entities_totalCriticalThreshold", 30000.0);
        }
        if (alertEntities != null) {
            if (alertEntities > criticalThreshold) {
                sender.sendMessage(Component.text(Messages.get("cmd.entities.critical-warning"), RED));
            } else if (alertEntities > warnThreshold) {
                sender.sendMessage(Component.text(Messages.get("cmd.entities.warning"), YELLOW));
            }
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
            ? Messages.get("cmd.section.chunks.world", world.getName())
            : Messages.get("cmd.section.chunks"), GOLD, TextDecoration.BOLD));
        // P0-5：模块被禁用或采集异常时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.chunks.total-label")).append(Component.text(Messages.get("cmd.state.unavailable"), GRAY)));
            return;
        }
        sender.sendMessage(goldLabel(Messages.get("cmd.chunks.total-label"))
            .append(Component.text(String.valueOf(snap.totalLoadedChunks), WHITE, TextDecoration.BOLD)));
        sender.sendMessage(goldLabel(Messages.get("cmd.chunks.force-total-label"))
            .append(Component.text(String.valueOf(snap.totalForceLoadedChunks), WHITE)));

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text(Messages.get("cmd.label.by-world"), GRAY));
        for (Map.Entry<String, Integer> entry : snap.chunksByWorld.entrySet()) {
            if (world != null && !entry.getKey().equals(world.getName())) continue;
            int forceLoaded = snap.forceLoadedChunksByWorld.getOrDefault(entry.getKey(), 0);
            // v1.2 A8：改为读 config 阈值（原硬编码 1500/800——1500 实为告警黄界，红界错位修正）
            TextColor cc = thresholdColors.colorOf(
                thresholdColors.levelOf(ThresholdColors.Metric.CHUNKS, entry.getValue()));
            sender.sendMessage(Component.text("  " + entry.getKey() + ": ", WHITE)
                .append(Component.text(Messages.get("cmd.chunks.count", entry.getValue()), cc))
                .append(Component.text(Messages.get("cmd.chunks.force-inline", forceLoaded), GRAY)));
        }
    }

    // ==================== 专项检测: 内存 ====================

    private void showMemory(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text(Messages.get("cmd.section.memory"), GOLD, TextDecoration.BOLD));

        // P0-5：模块被禁用或采集异常时显示"不可用"；W5-D #1：预热态渲染"预热中"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            sender.sendMessage(yellowLabel(Messages.get("cmd.memory.heap-label")).append(unavailableText(snap, MetricsSnapshot.MODULE_MEMORY)));
            return;
        }

        long usedMemMB = snap.heapUsed / 1024 / 1024;
        long maxMemMB = snap.heapMax / 1024 / 1024;
        // v1.2 A8：改为读 config 阈值（原硬编码 90/80，与默认巧合一致）
        TextColor memColor = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.MEMORY, snap.heapUsagePercent));

        sender.sendMessage(yellowLabel(Messages.get("cmd.memory.heap-label"))
            .append(Component.text(usedMemMB + "MB / " + maxMemMB + "MB", memColor, TextDecoration.BOLD))
            .append(Component.text(" (" + snap.heapUsagePercent + "%)", memColor)));

        String bar = generateBar(snap.heapUsagePercent, 30);
        sender.sendMessage(Component.text("  [" + bar + "]", memColor));

        sender.sendMessage(goldLabel(Messages.get("cmd.memory.committed-label"))
            .append(Component.text(formatBytes(snap.heapTotal), WHITE)));
        sender.sendMessage(goldLabel(Messages.get("cmd.memory.nonheap-label"))
            .append(Component.text(formatBytes(snap.nonHeapUsed), WHITE)));
        if (snap.nonHeapMax > 0) {
            sender.sendMessage(goldLabel(Messages.get("cmd.memory.nonheap-max-label"))
                .append(Component.text(formatBytes(snap.nonHeapMax), WHITE)));
        }

        // A5（命令侧）：GC 行独立守卫——内存正常但 GC 模块异常/禁用时显示"不可用"，
        // 而非误导性的 "GC 次数: 0 / 0 ms"（此前 showAll 的 GC 行有守卫，此处为单点漏）；
        // W5-D #1：预热态（内存/GC 同为 JVM 异步模块，常同时预热）渲染"预热中"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.gc.label"))
                .append(unavailableText(snap, MetricsSnapshot.MODULE_GC)));
        } else {
            sender.sendMessage(goldLabel(Messages.get("cmd.gc.count-label"))
                .append(Component.text(String.valueOf(snap.gcCount), WHITE)));
            sender.sendMessage(goldLabel(Messages.get("cmd.gc.time-label"))
                .append(Component.text(snap.gcTime + " ms", WHITE)));
        }

        if (thresholdColors.levelOf(ThresholdColors.Metric.MEMORY, snap.heapUsagePercent) == ThresholdColors.Level.CRITICAL) {
            sender.sendMessage(Component.text(Messages.get("cmd.memory.critical-warning",
                (int) thresholdOf(cfgNow(), "memory_heapCriticalThreshold", 95.0)), RED));
            sender.sendMessage(Component.text(Messages.get("cmd.memory.critical-advice"), GRAY));
        }
    }

    // ==================== 专项检测: CPU ====================

    private void showCPU(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text(Messages.get("cmd.section.cpu"), GOLD, TextDecoration.BOLD));

        // P0-5：先判模块级"不可用"（P0-13 的 -1 哨兵"未知"仍在下方处理）；W5-D #1：预热态渲染"预热中"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CPU)) {
            sender.sendMessage(yellowLabel(Messages.get("cmd.cpu.process-label")).append(unavailableText(snap, MetricsSnapshot.MODULE_CPU)));
            return;
        }

        // v1.2 A8：改为读 config 阈值（CPU 无 critical 键，最高只判到 WARN，消除"红界=告警界"错位）
        TextColor processColor = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.CPU_PROCESS, snap.processCpuLoad));
        TextColor systemColor = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.CPU_SYSTEM, snap.systemCpuLoad));

        // P0-13：-1 哨兵显示"未知"
        Component processCpuValue = snap.processCpuLoad < 0 ? Component.text(Messages.get("cmd.state.unknown"), GRAY)
            : Component.text(String.format(java.util.Locale.ROOT, "%.1f%%", snap.processCpuLoad), processColor, TextDecoration.BOLD);
        Component systemCpuValue = snap.systemCpuLoad < 0 ? Component.text(Messages.get("cmd.state.unknown"), GRAY)
            : Component.text(String.format(java.util.Locale.ROOT, "%.1f%%", snap.systemCpuLoad), systemColor);
        sender.sendMessage(yellowLabel(Messages.get("cmd.cpu.process-label")).append(processCpuValue));
        sender.sendMessage(yellowLabel(Messages.get("cmd.cpu.system-label")).append(systemCpuValue));
        sender.sendMessage(goldLabel(Messages.get("cmd.cpu.processors-label"))
            .append(Component.text(String.valueOf(snap.availableProcessors), WHITE)));
        sender.sendMessage(goldLabel(Messages.get("cmd.cpu.threads-label"))
            .append(Component.text(String.valueOf(snap.threadCount), WHITE))
            .append(Component.text(Messages.get("cmd.cpu.jvm-threads-inline", Thread.activeCount()), GRAY)));
    }

    // ==================== 专项检测: 玩家 ====================

    private void showPlayers(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text(Messages.get("cmd.section.players"), GOLD, TextDecoration.BOLD));
        // P0-5：模块被禁用或采集异常时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.players.online-label")).append(Component.text(Messages.get("cmd.state.unavailable"), GRAY)));
            return;
        }
        sender.sendMessage(goldLabel(Messages.get("cmd.players.online-label"))
            .append(Component.text(snap.playerCount + " / " + snap.maxPlayers, WHITE, TextDecoration.BOLD)));

        // 9A-6：统一走 MetricsSnapshot.playerPercent()（此前这里是 int 截断、采集层是 double 截断、
        // 诊断规则是 Math.round，89.5% 时三方分别 89/89/90）
        int pct = snap.playerPercent();
        String pBar = generateBar(pct, 30);
        // v1.2 A8：改为读 config 阈值（原硬编码 95/80，与默认巧合一致）
        TextColor pctColor = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.PLAYERS, pct));
        sender.sendMessage(Component.text("  [" + pBar + "] " + pct + "%", pctColor));

        if (snap.playerCount > 0) {  // @sentinel-ok 判断"有没有玩家可以列出来"，不是哨兵判断
            sender.sendMessage(Component.text(""));
            sender.sendMessage(Component.text(Messages.get("cmd.players.list-title"), GRAY));
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
            sender.sendMessage(Component.text(Messages.get("cmd.section.worlds"), GOLD, TextDecoration.BOLD));
            for (World world : Bukkit.getWorlds()) {
                Component worldEntry = Component.text("  " + world.getName(), AQUA, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/perfscope world " + world.getName()))
                    .hoverEvent(HoverEvent.showText(Component.text(Messages.get("cmd.world.click-detail", world.getName()), GREEN)));
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
        sender.sendMessage(Component.text(Messages.get("cmd.section.world", canonicalName), GOLD, TextDecoration.BOLD));
        sender.sendMessage(goldLabel(Messages.get("cmd.world.dimension-label")).append(Component.text(world.getEnvironment().name(), WHITE)));
        sender.sendMessage(goldLabel(Messages.get("cmd.world.entities-label")).append(Component.text(
            String.valueOf(snap.entitiesByWorld.getOrDefault(canonicalName, 0)), WHITE)));
        sender.sendMessage(goldLabel(Messages.get("cmd.chunks.loaded-label")).append(Component.text(
            String.valueOf(snap.chunksByWorld.getOrDefault(canonicalName, 0)), WHITE)));
        sender.sendMessage(goldLabel(Messages.get("cmd.world.force-loaded-label")).append(Component.text(
            String.valueOf(snap.forceLoadedChunksByWorld.getOrDefault(canonicalName, 0)), WHITE)));
        sender.sendMessage(goldLabel(Messages.get("cmd.world.tile-entities-label")).append(Component.text(
            String.valueOf(snap.tileEntitiesByWorld.getOrDefault(canonicalName, 0)), WHITE)));

        int playersInWorld = (int) Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.getWorld().getName().equals(canonicalName))
            .count();
        sender.sendMessage(goldLabel(Messages.get("cmd.world.players-label")).append(Component.text(String.valueOf(playersInWorld), WHITE)));

        ConfigManager.WorldSettings ws = configManager.getWorldSettings(canonicalName);
        if (ws != null) {
            sender.sendMessage(goldLabel(Messages.get("cmd.world.threshold-label"))
                .append(Component.text(Messages.get("cmd.world.thresholds-value",
                    ws.entities_totalWarningThreshold, ws.entities_totalCriticalThreshold), GRAY)));
        }
    }

    // ==================== 专项检测: 红石 ====================

    // P0-7：红石追踪已下线，保留子命令入口（老用户脚本不断），固定输出下线提示
    private void showRedstone(CommandSender sender) {
        sender.sendMessage(Component.text(Messages.get("cmd.section.redstone"), GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text(Messages.get("cmd.redstone.offline"), GRAY));
    }

    // ==================== 专项检测: Tile Entity ====================

    private void showTileEntities(CommandSender sender, String worldName) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        // A5（命令侧）：世界参数解析 + 规范名（同 showEntities/showChunks）；标题回显被过滤的世界名
        World world = resolveWorld(sender, worldName);
        if (worldName != null && world == null) return;

        sender.sendMessage(Component.text(world != null
            ? Messages.get("cmd.section.tileentities.world", world.getName())
            : Messages.get("cmd.section.tileentities"), GOLD, TextDecoration.BOLD));
        // P0-5：模块被禁用或采集异常时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TILE_ENTITIES)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.tileentities.total-label")).append(Component.text(Messages.get("cmd.state.unavailable"), GRAY)));
            return;
        }
        sender.sendMessage(goldLabel(Messages.get("cmd.tileentities.total-label"))
            .append(Component.text(String.valueOf(snap.tileEntityCount), WHITE, TextDecoration.BOLD)));

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text(Messages.get("cmd.label.by-world"), GRAY));
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

        sender.sendMessage(Component.text(Messages.get("cmd.section.uptime"), GOLD, TextDecoration.BOLD));
        // P0-5：server 模块被禁用或采集异常时显示"不可用"；W5-D #1：JVM 异步就绪前 uptime 为默认 0，
        // 渲染"预热中"（与 showAll 同口径），而非误导性的"0秒"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_SERVER)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.uptime.label")).append(Component.text(Messages.get("cmd.state.unavailable"), GRAY)));
            return;
        }
        if (snap.uptimeSeconds <= 0) {
            sender.sendMessage(goldLabel(Messages.get("cmd.uptime.label")).append(uptimeWarmupText()));
            return;
        }
        sender.sendMessage(goldLabel(Messages.get("cmd.uptime.label"))
            .append(Component.text(formatUptime(snap.uptimeSeconds), WHITE, TextDecoration.BOLD)));
        sender.sendMessage(goldLabel(Messages.get("cmd.uptime.plugins-label"))
            .append(Component.text(String.valueOf(snap.loadedPluginCount), WHITE)));
        sender.sendMessage(goldLabel(Messages.get("cmd.uptime.worlds-label"))
            .append(Component.text(String.valueOf(snap.worldCount), WHITE)));
        sender.sendMessage(goldLabel(Messages.get("cmd.uptime.threads-label"))
            .append(Component.text(String.valueOf(snap.threadCount), WHITE))
            .append(Component.text(Messages.get("cmd.cpu.jvm-threads-inline", Thread.activeCount()), GRAY)));
        sender.sendMessage(goldLabel(Messages.get("cmd.uptime.jvm-label"))
            .append(Component.text(System.getProperty("java.version"), WHITE))
            .append(Component.text(" (" + System.getProperty("java.vendor") + ")", GRAY)));
    }

    // ==================== 专项检测: 历史摘要（Wave 7A） ====================

    /**
     * Wave 7A：/perfscope history —— 24h 环形缓冲摘要。
     * 输出格式锁定（docs/06-tasks/WAVE7-DESIGN.md「7A」节）：标题取自 {@code cmd.section.history}
     * （en "=== History Summary ===" / zh "=== 历史摘要 ==="）、
     * "覆盖: 近 N 分钟（M 个样本）"、"最低 TPS: x.xx（HH:mm）"、"峰值实体: N（HH:mm）"、
     * "峰值内存: P%（HH:mm）"；空态含稳定子串 "暂无历史数据"（后续 harness 断言锚点，勿改字）。
     * 极值均取全窗口 helper（since=0，含端点恒全员参与；无效值过滤规则见 HistoryStore 类注释）。
     */
    private void showHistory(CommandSender sender) {
        List<HistorySample> samples = historyStore.samples();
        sender.sendMessage(Component.text(Messages.get("cmd.section.history"), GOLD, TextDecoration.BOLD));
        if (samples.isEmpty()) {
            // 空态原因：刚启动/重载（首个采样未到）、采样间隔未到、或 history.enabled=false
            sender.sendMessage(Component.text(Messages.get("cmd.history.empty"), YELLOW));
            return;
        }

        HistorySample oldest = samples.get(0);
        HistorySample latest = samples.get(samples.size() - 1);
        // 覆盖时长 = 跨度分钟取整，至少显示 1（契约：<1 分钟不显示 0）
        long minutes = Math.max(1L, (latest.timestamp() - oldest.timestamp()) / 60000L);
        sender.sendMessage(goldLabel(Messages.get("cmd.history.coverage-label"))
            .append(Component.text(Messages.get("cmd.history.coverage", minutes, samples.size()), WHITE)));

        appendHistoryExtreme(sender, Messages.get("cmd.label.min-tps"), historyStore.lowestTpsSince(0L),
            s -> String.format(java.util.Locale.ROOT, "%.2f", s.tps1m()));
        appendHistoryExtreme(sender, Messages.get("cmd.label.peak-entities"), historyStore.peakEntitiesSince(0L),
            s -> String.valueOf(s.totalEntities()));
        appendHistoryExtreme(sender, Messages.get("cmd.label.peak-heap"), historyStore.peakHeapSince(0L),
            s -> s.heapUsagePercent() + "%");
    }

    /** Wave 7A：历史极值行——"{label}{value}（{HH:mm}）"；helper 返回 null（窗口内全为无效值）时渲染Messages.get("cmd.state.no-valid-samples")。 */
    private void appendHistoryExtreme(CommandSender sender, String label, HistorySample sample,
                                      Function<HistorySample, String> formatter) {
        if (sample == null) {
            sender.sendMessage(goldLabel(label).append(Component.text(Messages.get("cmd.state.no-valid-samples"), GRAY)));
            return;
        }
        sender.sendMessage(goldLabel(label)
            .append(Component.text(formatter.apply(sample), WHITE))
            .append(Component.text(Messages.get("cmd.label.timestamp-paren", formatHistoryTime(sample.timestamp())), GRAY)));
    }

    // ==================== Wave 8A：智能诊断（规则引擎） ====================

    /**
     * 智能诊断（Wave 8A）：运行规则引擎并输出结论 + 参数级建议。
     *
     * <p>输出锚点（harness 依赖）：标题取自 {@code cmd.section.diagnose}
     * （en "=== Automated Diagnostics ===" / zh "=== 智能诊断 ==="）；无命中时 {@code 未发现已知问题}；
     * 每条命中含 {@code [ID] 标题} 行与 {@code 建议: } 行。能力边界声明逐字保留
     * （诚实划界——"本引擎只覆盖 N 条高置信规则"，防止被当成 spark 的替代品）。</p>
     *
     * <p><b>9A-3 修正</b>：旧实现用 {@code DiagnosticEngine.ruleCount()}（注册总数）冒充
     * "已检查 N 条"，而 3/5 条规则前置不成立时根本没运行。现在改为如实报告
     * "已评估 X/Y 条"并在有跳过时列出原因；只有当全部规则都评估过且无命中，
     * 才允许说"未发现已知问题"。</p>
     */
    private void showDiagnose(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) {
            noData(sender);
            return;
        }
        sender.sendMessage(Component.text(Messages.get("cmd.section.diagnose"), GOLD, TextDecoration.BOLD));
        var result = plugin.getDiagnosticEngine().run();
        List<Diagnosis> hits = result.hits();
        List<Diagnosis.Skip> skipped = result.skipped();
        if (hits.isEmpty()) {
            if (result.fullyEvaluated()) {
                sender.sendMessage(Component.text(Messages.get("cmd.diagnose.clean",
                    result.evaluatedCount(), result.total()), GREEN));
            } else {
                // 有规则没跑：不能说"未发现"，只能说"没查完"
                sender.sendMessage(Component.text(Messages.get("cmd.diagnose.partial",
                    result.evaluatedCount(), result.total(), skipped.size()), YELLOW));
            }
        } else {
            for (Diagnosis d : hits) {
                TextColor color = d.critical() ? RED : YELLOW;
                sender.sendMessage(Component.text(" [" + d.ruleId() + "] ", color)
                    .append(Component.text(d.title(), color, TextDecoration.BOLD)));
                sender.sendMessage(Component.text("  " + d.detail(), WHITE));
                sender.sendMessage(Component.text(Messages.get("cmd.diagnose.advice-label"), GRAY)
                    .append(Component.text(d.suggestion(), AQUA)));
            }
        }
        // 未评估明细（9A-3）：让服主知道"哪条没查、还缺什么"，而不是以为全查过了
        if (!skipped.isEmpty()) {
            sender.sendMessage(Component.text(Messages.get("cmd.diagnose.skipped-title", skipped.size()), GRAY));
            for (Diagnosis.Skip s : skipped) {
                sender.sendMessage(Component.text("  · " + s.ruleId() + " — " + s.reason(), GRAY));
            }
        }
        sender.sendMessage(Component.text(Messages.get("cmd.diagnose.footer", result.total()), GRAY));
    }

    // ==================== Wave 8B：趋势（跨重启历史） ====================

    /**
     * 历史趋势（Wave 8B）：读文件持久化 + 内存环的合并样本，输出跨重启可查的统计。
     *
     * <p>用法：{@code /perfscope trend [天数]}（默认 1 天，钳 1..配置保留天数）。
     * 输出锚点（harness 依赖）：标题取自 {@code cmd.section.trend}
     * （en "=== Trend Summary ===" / zh "=== 趋势摘要 ==="）；行含 {@code 样本}、
     * {@code 最低 TPS: }、{@code 峰值内存: }；空态 {@code 暂无趋势数据}。
     * 与 /perfscope history 的区别：history 只看内存环（近 24h 且重启清零），
     * trend 合并文件持久化样本（可跨重启、可查多日）。</p>
     */
    private void showTrend(CommandSender sender, String[] args) {
        var fileStore = plugin.getHistoryFileStore();
        int retentionDays = (int) thresholdOf(cfgNow(), "history_persistRetentionDays", 7.0);
        // f-s02-logic-04：天数上界必须跟"数据实际来自哪里"走。
        // 旧实现一律用保留天数（默认 7）当上界，但文件持久化不可用时会回退到内存环，
        // 而内存环的容量上限是 24 小时——于是 `trend 7` 会输出
        // "近 7 天（1440 个样本，来源: 内存环）"，把 24 小时的数据标成 7 天喵。
        // 现在先探一次文件是否可用，再决定上界：
        //   文件可用 -> retentionDays（文件里真有那么多天）
        //   文件不可用 -> 1（内存环只保证约 24 小时）
        long sinceMsForProbe = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L;
        List<HistorySample> fileProbe =
            fileStore == null ? List.of() : fileStore.loadRecentDays(retentionDays, sinceMsForProbe);
        boolean fileUsable = !fileProbe.isEmpty();
        int maxDays = fileUsable ? retentionDays : 1;
        int days = 1;
        if (args.length > 1) {
            try {
                days = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text(Messages.get("cmd.error.days-not-integer", maxDays), YELLOW));
            }
        }
        days = Math.max(1, Math.min(maxDays, days));
        if (!fileUsable && retentionDays > 1) {
            // 请求值被上界压过时明说原因，否则用户会以为参数没生效
            sender.sendMessage(Component.text(
                Messages.get("cmd.trend.days-clamped-memory", retentionDays, maxDays), YELLOW));
        }

        long sinceMs = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        // 合并单一事实源：优先文件持久化（跨重启）；不可用/空时回退内存环（本进程会话）
        // 复用上面的探针结果：days <= retentionDays 时探针已覆盖本次请求的范围，无需二次读盘
        List<HistorySample> samples = (fileUsable && days == retentionDays)
            ? fileProbe
            : (fileStore == null ? List.of() : fileStore.loadRecentDays(days, sinceMs));
        boolean fromFile = !samples.isEmpty();
        if (!fromFile) {
            samples = historyStore.since(sinceMs);
        }

        sender.sendMessage(Component.text(Messages.get("cmd.section.trend"), GOLD, TextDecoration.BOLD));
        if (samples.isEmpty()) {
            sender.sendMessage(Component.text(Messages.get("cmd.trend.empty"), YELLOW));
            return;
        }
        String source = Messages.get(fromFile ? "cmd.trend.source-file" : "cmd.trend.source-memory");
        sender.sendMessage(goldLabel(Messages.get("cmd.trend.range-label"))
            .append(Component.text(Messages.get("cmd.trend.range", days, samples.size(), source), WHITE)));

        // f-s04-csv-06：skippedLines() 此前全仓零调用点——文件里有坏行（半写/损坏/列数不符）时
        // trend 只说"来源：内存环"，不告诉用户为什么走了回退、丢了多少行。降级原因必须可见，
        // 否则"数据看着变少了"永远只能靠猜喵。
        if (fileStore != null) {
            long skipped = fileStore.skippedLines();
            if (skipped > 0) {
                sender.sendMessage(Component.text(
                    Messages.get("cmd.trend.skipped-lines", skipped), YELLOW));
            }
            if (!fromFile) {
                // 有持久化目录却拿不到文件样本：把"为什么回退"讲明白
                sender.sendMessage(Component.text(
                    Messages.get("cmd.trend.file-unavailable", fileStore.directory()), GRAY));
            }
        }

        HistorySample oldest = samples.get(0);
        HistorySample latest = samples.get(samples.size() - 1);
        double minTps = Double.MAX_VALUE;
        HistorySample minTpsAt = null;
        int peakHeap = -1;
        HistorySample peakHeapAt = null;
        double sumTps = 0;
        int tpsCount = 0;
        // v1.4：新增三指标的窗口极值/累计（全部按 -1 哨兵过滤，见下方逐处）
        HistorySample peakMsptAt = null;
        HistorySample peakCpuAt = null;
        long gcTimeDeltaMs = 0L;
        long gcCountDelta = 0L;
        boolean gcSeen = false;
        boolean gcAnyRecorded = false;
        long prevGcTime = HistorySample.NO_LONG;
        long prevGcCount = HistorySample.NO_LONG;
        for (HistorySample s : samples) {
            // tps1m 没有 -1 哨兵（TPS 不可用时的快照整条不入环），所以 0 是真实的
            // "服务器冻结"，是最该被看见的最低值。用 > 0 把它滤掉，trend 会报"最低 18.0"
            // 而实际卡死过——那是伪造历史。只排除负哨兵与物理上不可能的上界。
            if (s.tps1m() >= 0 && s.tps1m() <= 20.5) {
                sumTps += s.tps1m();
                tpsCount++;
                if (s.tps1m() < minTps) {
                    minTps = s.tps1m();
                    minTpsAt = s;
                }
            }
            if (s.heapUsagePercent() > 0 && s.heapUsagePercent() > peakHeap) {  // @sentinel-ok 求堆占用峰值：0 不可能高于任何正值，过滤它与不过滤结果相同
                peakHeap = s.heapUsagePercent();
                peakHeapAt = s;
            }
            // 尖峰：msptMax <= 0 是"未采集"（TPS 反推/avg-only 路径），不能当 0 比较——否则
            // 一个未采集的 0 会永远赢不了真实尖峰，看起来像"从没尖峰过"，其实是无数据。
            if (s.msptMax() > 0 && (peakMsptAt == null || s.msptMax() > peakMsptAt.msptMax())) {
                peakMsptAt = s;
            }
            // CPU：0.0 是合法读数，只排除负哨兵
            if (s.cpuProcess() >= 0 && (peakCpuAt == null || s.cpuProcess() > peakCpuAt.cpuProcess())) {
                peakCpuAt = s;
            }
            // GC 是累计量：逐对取正差累加。静态差（last-first）在重启后会算出一个负数或偏小的假值，
            // 而 GC 计数在重启时归零正是最常见的场景——按相邻样本增量累加才能跨重启仍然正确。
            if (s.gcTime() != HistorySample.NO_LONG && s.gcCount() != HistorySample.NO_LONG) {
                gcAnyRecorded = true;
                boolean hasPrev = prevGcTime != HistorySample.NO_LONG;
                // 计数器回退 = 进程重启（累计量归零）。这一对差值无意义，跳过；
                // prev 会被更新到新基线，于是重启后的增量照常累加。
                boolean reset = hasPrev && (s.gcTime() < prevGcTime || s.gcCount() < prevGcCount);
                if (hasPrev && !reset) {
                    gcTimeDeltaMs += s.gcTime() - prevGcTime;
                    gcCountDelta += s.gcCount() - prevGcCount;
                    gcSeen = true;
                }
                prevGcTime = s.gcTime();
                prevGcCount = s.gcCount();
            }
        }
        if (tpsCount > 0) {
            sender.sendMessage(goldLabel(Messages.get("cmd.label.avg-tps"))
                .append(Component.text(String.format(java.util.Locale.ROOT, "%.2f", sumTps / tpsCount), WHITE))
                .append(Component.text(Messages.get("cmd.trend.valid-samples", tpsCount), GRAY)));
            sender.sendMessage(goldLabel(Messages.get("cmd.label.min-tps"))
                .append(Component.text(String.format(java.util.Locale.ROOT, "%.2f", minTps), WHITE))
                .append(Component.text(Messages.get("cmd.label.timestamp-paren", formatTrendTime(minTpsAt.timestamp())), GRAY)));
        } else {
            sender.sendMessage(goldLabel(Messages.get("cmd.label.min-tps")).append(Component.text(Messages.get("cmd.state.no-valid-samples"), GRAY)));
        }
        if (peakHeapAt != null) {
            sender.sendMessage(goldLabel(Messages.get("cmd.label.peak-heap"))
                .append(Component.text(peakHeap + "%", WHITE))
                .append(Component.text(Messages.get("cmd.label.timestamp-paren", formatTrendTime(peakHeapAt.timestamp())), GRAY)));
        }
        // v1.4：尖峰 / CPU / GC 三行。三者都走"有数据就给数值，没数据就说没数据"——
        // 旧文件（v1.3 写的 7 列行）这几列读出来是 -1，必须显示"无数据"而不是 0 或空白。
        sender.sendMessage(goldLabel(Messages.get("cmd.trend.peak-mspt"))
            .append(peakMsptAt == null
                ? Component.text(Messages.get("cmd.trend.not-recorded"), GRAY)
                : Component.text(String.format(java.util.Locale.ROOT, "%.2f ms", peakMsptAt.msptMax()), WHITE)
                    .append(Component.text(Messages.get("cmd.label.timestamp-paren", formatTrendTime(peakMsptAt.timestamp())), GRAY))));
        sender.sendMessage(goldLabel(Messages.get("cmd.trend.peak-cpu"))
            .append(peakCpuAt == null
                ? Component.text(Messages.get("cmd.trend.not-recorded"), GRAY)
                : Component.text(String.format(java.util.Locale.ROOT, "%.1f%%", peakCpuAt.cpuProcess()), WHITE)
                    .append(Component.text(Messages.get("cmd.label.timestamp-paren", formatTrendTime(peakCpuAt.timestamp())), GRAY))));
        sender.sendMessage(goldLabel(Messages.get("cmd.trend.gc"))
            .append(gcSeen
                ? Component.text(Messages.get("cmd.trend.gc-value",
                    gcCountDelta, String.format(java.util.Locale.ROOT, "%.1f", gcTimeDeltaMs / 1000.0)), WHITE)
                : Component.text(Messages.get(gcAnyRecorded
                    ? "cmd.trend.gc-insufficient" : "cmd.trend.not-recorded"), GRAY)));
        sender.sendMessage(Component.text(Messages.get("cmd.trend.coverage",
            formatTrendTime(oldest.timestamp()), formatTrendTime(latest.timestamp())), GRAY));
        showTrendGaps(sender, samples, sinceMs);
    }

    /**
     * 趋势的数据缺口（9A-18 的 {@link HistoryStore#gaps}，此前从未接到任何命令上）。
     *
     * <p><b>为什么这行值得存在</b>：上面的"覆盖 A ～ B（N 个样本）"回答不了"这 N 个样本是
     * 均匀铺满整段，还是中间断了 6 小时"。两种情况的覆盖行一模一样，而它们对"昨晚到底有没有
     * 卡"这个问题是截然不同的证据——断掉那段里的卡顿不存在于任何样本里。没有这一行，
     * 一次重启造成的空窗会被静默读成"这段时间很健康"。</p>
     *
     * <p><b>为什么用文件来源时也要算</b>：{@code gaps()} 读的是内存环，而 {@code samples}
     * 可能来自文件（跨重启）。两者不是同一批数据，所以这里<b>不调用</b> {@code gaps()}，
     * 而是对本次实际展示的 {@code samples} 直接算——否则算出的缺口与上面的统计范围不一致，
     * 用户会拿两份不同样本集的结论对照。判据与 {@code gaps()} 保持一致：
     * 相邻间隔 &gt; 2× 期望间隔。</p>
     *
     * <p>期望间隔取配置值（{@code history.sample-interval-seconds}）；配置读不到或非正时
     * 不猜，直接不输出这一行——宁可少一行，也不要拿一个假间隔把正常数据报成满屏缺口。</p>
     */
    private void showTrendGaps(CommandSender sender, List<HistorySample> samples, long sinceMs) {
        long intervalSec = (long) thresholdOf(cfgNow(), "history_sampleIntervalSeconds", 60.0);
        long expectedMs = intervalSec * 1000L;
        if (expectedMs <= 0L || samples.size() < 2) {
            return; // 没有期望间隔或不足两条样本，谈不上"缺口"
        }
        // 走 HistoryStore.gapsOf 而不是在命令层再写一遍判据：两份实现会各自演化，
        // 于是测试覆盖的是 A 实现、用户看到的是 B 实现。
        List<HistoryStore.Gap> gaps = HistoryStore.gapsOf(samples, sinceMs, expectedMs);
        if (gaps.isEmpty()) {
            sender.sendMessage(Component.text(Messages.get("cmd.trend.gaps-none"), GRAY));
            return;
        }
        long totalMs = 0L;
        for (HistoryStore.Gap g : gaps) {
            totalMs += g.durationMs();
        }
        // 缺口多于 3 处时只列最近的 3 处，剩下的用总数交代——否则一次长时间停机
        // 会把 trend 刷成几十行，把真正要看的 TPS/内存挤出屏幕。
        sender.sendMessage(Component.text(Messages.get("cmd.trend.gaps-summary",
            gaps.size(), formatGapDuration(totalMs)), YELLOW));
        int shown = Math.min(3, gaps.size());
        for (int i = gaps.size() - shown; i < gaps.size(); i++) {
            HistoryStore.Gap g = gaps.get(i);
            sender.sendMessage(Component.text(Messages.get("cmd.trend.gaps-entry",
                formatTrendTime(g.startMs()), formatTrendTime(g.endMs()),
                formatGapDuration(g.durationMs())), GRAY));
        }
    }

    /** 缺口时长 → "1天2小时" / "3小时5分" / "12分" 的人类可读形态。 */
    private String formatGapDuration(long ms) {
        long totalMinutes = Math.max(0L, ms) / 60_000L;
        long days = totalMinutes / (24L * 60L);
        long hours = (totalMinutes % (24L * 60L)) / 60L;
        long minutes = totalMinutes % 60L;
        if (days > 0) {
            return Messages.get("cmd.trend.gap-duration-days", days, hours);
        }
        if (hours > 0) {
            return Messages.get("cmd.trend.gap-duration-hours", hours, minutes);
        }
        return Messages.get("cmd.trend.gap-duration-minutes", minutes);
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
     * 输出锚点（harness 依赖，逐字稳定）：标题取自 {@code cmd.section.hotspots}
     * （en 前缀 {@code === Hotspot Chunks Top3} / zh 前缀 {@code === 热点区块 Top3}）、
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
        sender.sendMessage(Component.text(Messages.get("cmd.section.hotspots", stamp), GOLD, TextDecoration.BOLD));
        // P0-5/Wave 8C：模块被禁用或采集异常、或首采样未到 → 空态（头部同款渲染，便于断言）
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_HOTSPOTS) || snap.topEntityChunks.isEmpty()) {
            sender.sendMessage(Component.text(Messages.get("cmd.hotspots.empty"), YELLOW));
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
                    .hoverEvent(HoverEvent.showText(Component.text(Messages.get("cmd.hotspots.copy-hint", tpArg), AQUA)));
            }
            sender.sendMessage(Component.text(" " + (i + 1) + ". ", GRAY)
                .append(Component.text(hs.world() + " ", WHITE))
                .append(coord)
                .append(Component.text(Messages.get("cmd.hotspots.block-coords", hs.blockX(), hs.blockZ()), GRAY))
                .append(Component.text(Messages.get("cmd.hotspots.entities-label"), WHITE))
                .append(Component.text(Messages.get("cmd.hotspots.count", hs.entityCount()), thresholdColors.colorOf(
                    thresholdColors.levelOf(ThresholdColors.Metric.ENTITIES, hs.entityCount(), hs.world()))))
                .append(Component.text(Messages.get("cmd.hotspots.dominant"), GRAY))
                .append(Component.text(hs.dominantType(), WHITE))
                .append(Component.text(" " + hs.dominantPercent() + "%", GRAY)));
        }
    }

    // ==================== 专项检测: GC ====================

    private void showGC(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text(Messages.get("cmd.section.gc"), GOLD, TextDecoration.BOLD));
        // P0-5：模块被禁用或采集异常时显示"不可用"；W5-D #1：预热态渲染"预热中"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            sender.sendMessage(goldLabel(Messages.get("cmd.gc.total-count-label")).append(unavailableText(snap, MetricsSnapshot.MODULE_GC)));
            return;
        }
        sender.sendMessage(goldLabel(Messages.get("cmd.gc.total-count-label"))
            .append(Component.text(String.valueOf(snap.gcCount), WHITE, TextDecoration.BOLD)));
        sender.sendMessage(goldLabel(Messages.get("cmd.gc.total-time-label"))
            .append(Component.text(Messages.get("cmd.gc.seconds", String.format(java.util.Locale.ROOT, "%.2f", snap.gcTime / 1000.0)), WHITE)));

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text(Messages.get("cmd.gc.collectors-title"), GRAY));
        for (java.lang.management.GarbageCollectorMXBean gcBean :
            java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();
            sender.sendMessage(Component.text("  " + gcBean.getName() + ": ", WHITE)
                .append(Component.text(Messages.get("cmd.gc.collector-line", count, time), AQUA)));
        }
    }

    // ==================== 报告 ====================

    private void handleReport(CommandSender sender, String[] args) {
        // P0-10：admin || 专项（children 兜底 + 显式检查，双保险）
        if (!sender.hasPermission("perfscope.admin") && !sender.hasPermission("perfscope.export")) {
            sender.sendMessage(Component.text(Messages.get("no-permission-export"), RED));
            return;
        }
        String path = args.length > 1 ? reportGen.generateSpecific(args[1]) : reportGen.generateAndSave();
        if (path != null) {
            // W5-D #2：报告是唯一的"证据固化"出口，路径却不可点——聊天框无法选中复制，60 字符盘符路径
            // 手抄不现实。玩家挂 copyToClipboard（悬停提示Messages.get("cmd.report.copy-hint")）；控制台/RCON 保持纯文本
            //（控制台无点击交互语义，路径仍是可用的诊断信息）
            if (sender instanceof Player) {
                sender.sendMessage(Component.text(Messages.get("report-generated"), GREEN) // 9A-24：接线死键
                    .append(Component.text(path, GREEN, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.copyToClipboard(path))
                        .hoverEvent(HoverEvent.showText(Component.text(Messages.get("cmd.report.copy-hint"), GREEN)))));
            } else {
                sender.sendMessage(Component.text(Messages.get("report-generated") + path, GREEN)); // 9A-24
            }
        } else {
            // 失败时补一句真实原因：四种失败（无快照 / 目录不可用 / 渲染异常 / 文件名耗尽）
            // 的处置方式完全不同，只说"报告生成失败"会把排查成本全推给用户。
            // 原因可能为 null（理论上只在并发时序下出现），那时退回原来那一句。
            String reason = reportGen.failureReason();
            sender.sendMessage(reason == null || reason.isBlank()
                ? Component.text(Messages.get("report-failed"), RED)
                : Component.text(Messages.get("report-failed"), RED)
                    .append(Component.text(" " + reason, GRAY)));
        }
    }

    // ==================== 重载 ====================

    private void handleReload(CommandSender sender) {
        // P0-10：admin || 专项
        if (!sender.hasPermission("perfscope.admin") && !sender.hasPermission("perfscope.reload")) {
            sender.sendMessage(Component.text(Messages.get("no-permission-reload"), RED));
            return;
        }
        plugin.reload();
        sender.sendMessage(Component.text(Messages.get("reloaded"), GREEN));
        // 9C-5：覆盖文件没被采用时，绝不能让回执只报"成功"——用户会以为自己的改动生效了，
        // 而实际上整份 messages.yml 被忽略了（红队复核 Blocker #4）。
        String notice = Messages.overrideNotice();
        if (notice != null && !notice.isEmpty()) {
            sender.sendMessage(Component.text(Messages.get("cmd.reload.notice", notice), YELLOW));
        }
    }

    // ==================== v1.4：webhook 自检 ====================

    /**
     * {@code /perfscope webhook [status|test]}。
     *
     * <p><b>为什么需要这个命令</b>：企业微信 / 飞书 / 钉钉在业务失败时返回 HTTP 200，
     * 错误码在响应体里。配置写错、签名错、机器人被移出群，服主唯一的观测渠道是
     * "群里一直没消息"——而"服务器很健康所以没有告警"和"告警根本没送到"看起来完全一样。
     * 这个命令把"能不能通"从需要等一次真实故障，变成随时可查。</p>
     */
    private void handleWebhook(CommandSender sender, String[] args) {
        if (!sender.hasPermission("perfscope.admin") && !sender.hasPermission("perfscope.reload")) {
            sender.sendMessage(Component.text(Messages.get("no-permission-reload"), RED));
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "status";
        if ("test".equals(sub)) {
            runWebhookTest(sender);
        } else if ("status".equals(sub)) {
            showWebhookStatus(sender);
        } else {
            sender.sendMessage(Component.text(Messages.get("cmd.webhook.usage"), YELLOW));
        }
    }

    /** 展示当前 webhook 配置与最近一次投递结果（不发起任何网络请求）。 */
    private void showWebhookStatus(CommandSender sender) {
        WebhookNotifier wn = plugin.getWebhookNotifier();
        if (wn == null || !wn.isReady()) {
            sender.sendMessage(Component.text(Messages.get("cmd.webhook.not-ready"), YELLOW));
            return;
        }
        sender.sendMessage(Component.text(Messages.get("cmd.webhook.status-line",
            plugin.getConfigManager().get().alerts_webhookType,
            wn.authOrSecretHint()), GRAY));

        WebhookResponse.Result last = wn.lastResult();
        if (last == null) {
            sender.sendMessage(Component.text(Messages.get("cmd.webhook.no-attempt-yet"), GRAY));
        } else {
            sender.sendMessage(webhookResultComponent(last));
        }
        long dropped = wn.droppedByRateLimit();
        if (dropped > 0) {
            // 这是服主发现"我的告警被节流了"的唯一信号，必须显式展示
            sender.sendMessage(Component.text(Messages.get("cmd.webhook.dropped", dropped), YELLOW));
        }
    }

    /**
     * 发送一条探测消息并回报结果。
     *
     * <p>网络请求会阻塞，所以在异步线程执行；结果再经 {@link SchedulerAdapter#runLater}
     * 跳回主线程渲染——Folia 上异步线程直接给玩家发消息会抛异常。</p>
     */
    private void runWebhookTest(CommandSender sender) {
        WebhookNotifier wn = plugin.getWebhookNotifier();
        if (wn == null || !wn.isReady()) {
            sender.sendMessage(Component.text(Messages.get("cmd.webhook.not-ready"), YELLOW));
            return;
        }
        // 防连点：同一发送者 10 秒内只允许一次，避免把群刷屏
        if (!tryWebhookTest(sender.getName())) {
            sender.sendMessage(Component.text(Messages.get("cmd.webhook.test-throttled"), YELLOW));
            return;
        }
        sender.sendMessage(Component.text(Messages.get("cmd.webhook.test-sending"), GRAY));

        SchedulerAdapter.runAsyncOnce(plugin, () -> {
            WebhookResponse.Result result = wn.probe(java.time.Duration.ofSeconds(10));
            // 回主线程渲染：异步线程不碰任何 Bukkit API。
            //
            // 注意：结果同时写控制台日志。原因不是"日志更好看"，而是 RCON / 控制台这类
            // CommandSender 在异步往返期间可能已经失效——RCON 会话执行完命令就断开，
            // 之后再 sendMessage 不会报错也不会送达。只发消息出去会让 /webhook test
            // 在 RCON 下表现为"没反应"，而这种静默正是本命令要消灭的东西。
            SchedulerAdapter.runLater(plugin, () -> {
                if (!plugin.isEnabled()) {
                    return;
                }
                String rendered = renderWebhookResultPlain(result);
                plugin.getLogger().info("[PerfScope] " + rendered);
                try {
                    if (result == null) {
                        sender.sendMessage(Component.text(Messages.get("cmd.webhook.not-ready"), YELLOW));
                    } else {
                        sender.sendMessage(webhookResultComponent(result));
                    }
                } catch (RuntimeException ignored) {
                    // sender 可能已失效（RCON 断开）；结果已进控制台，不影响正确性
                }
            }, 1L);
        });
    }

    /**
     * 纯文本版的结果渲染，供控制台日志使用（{@link Component} 只能给 sender，进不了 logger）。
     */
    private String renderWebhookResultPlain(WebhookResponse.Result r) {
        if (r == null) {
            return Messages.get("cmd.webhook.not-ready");
        }
        if (r.ok()) {
            return Messages.get("cmd.webhook.test-ok", r.httpStatus());
        }
        return switch (r.outcome()) {
            case HTTP_ERROR -> Messages.get("cmd.webhook.test-http", r.httpStatus())
                + " " + Messages.get("cmd.webhook.action-http");
            case BIZ_ERROR -> Messages.get("cmd.webhook.test-biz", r.bizCode(), r.detail())
                + " " + Messages.get("cmd.webhook.action-biz");
            default -> Messages.get("cmd.webhook.test-bad-body", r.detail())
                + " " + Messages.get("cmd.webhook.action-bad-body");
        };
    }

    /**
     * 把投递结果渲染成一条带<b>可执行处置建议</b>的消息。
     *
     * <p>只说"失败"没有用：签名错、被限流、机器人被移出群在 HTTP 层都是 200，
     * 服主看到 {@code 310000} 并不知道该改什么。</p>
     */
    private Component webhookResultComponent(WebhookResponse.Result r) {
        if (r.ok()) {
            return Component.text(Messages.get("cmd.webhook.test-ok", r.httpStatus()), GREEN);
        }
        String reason;
        String action;
        switch (r.outcome()) {
            case HTTP_ERROR -> {
                reason = Messages.get("cmd.webhook.test-http", r.httpStatus());
                action = Messages.get("cmd.webhook.action-http");
            }
            case BIZ_ERROR -> {
                reason = Messages.get("cmd.webhook.test-biz", r.bizCode(), r.detail());
                action = Messages.get("cmd.webhook.action-biz");
            }
            default -> {
                reason = Messages.get("cmd.webhook.test-bad-body", r.detail());
                action = Messages.get("cmd.webhook.action-bad-body");
            }
        }
        return Component.text(reason, RED).append(Component.newline())
            .append(Component.text(action, GRAY));
    }

    /** 探测命令的简单防连点（按发送者名；只保留最近一次的时间戳）。 */
    private final java.util.Map<String, Long> webhookTestLastAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long WEBHOOK_TEST_MIN_INTERVAL_MS = 10_000L;

    private boolean tryWebhookTest(String who) {
        long now = System.currentTimeMillis();
        Long last = webhookTestLastAt.get(who);
        if (last != null && now - last < WEBHOOK_TEST_MIN_INTERVAL_MS) {
            return false;
        }
        webhookTestLastAt.put(who, now);
        return true;
    }

    // ==================== 9A-20 #5：插件自身状态 ====================

    /**
     * /perfscope status —— 插件自身健康（9A-20 #5）。
     *
     * <p>背景（锐评原文："对外很聪明，对内全瞎"）：此前 21 个子命令没有一个回答"插件自己还活着吗"。
     * 采集任务被 Bukkit 静默取消后，所有子命令继续渲染最后一张快照，与正常运行无法区分；
     * 模块被配置关掉与采集抛异常也共用同一句"不可用"。本命令把这些区分开：</p>
     * <ul>
     *   <li><b>采集存活</b>：{@link MetricsCollector#isStale()} / lastCollectAtMs / 连续失败次数；</li>
     *   <li><b>模块三态</b>：正常 / 已禁用（config enabled=false）/ 采集失败 / 预热中——分别读
     *       {@code disabledModules} / {@code unavailable} / {@code warmingUp}；</li>
     *   <li><b>历史</b>：{@link MetricsCollector#getHistorySize()}、持久化可用性与目录；</li>
     *   <li><b>告警</b>：直接读快照的 activeWarnings/activeCriticals（不依赖 AlertService 状态机）。</li>
     * </ul>
     *
     * <p>输出锚点（harness 依赖）：标题取自 {@code cmd.section.status}
     * （en "=== Plugin Status ===" / zh "=== 插件状态 ==="）。全部为只读操作、无副作用。</p>
     */
    private void showStatus(CommandSender sender) {
        sender.sendMessage(Component.text(Messages.get("cmd.section.status"), GOLD, TextDecoration.BOLD));
        sender.sendMessage(goldLabel(Messages.get("cmd.status.version-label"))
            .append(Component.text(Messages.get("cmd.status.version-line",
                plugin.getDescription().getVersion(), ServerCompat.getServerName()), WHITE)));

        // ---------- 采集存活（9A-20 #1） ----------
        long lastAt = collector.lastCollectAtMs();
        int cycleFailures = collector.consecutiveCollectFailures();
        boolean stale = collector.isStale();
        if (lastAt <= 0L && !stale) {
            sender.sendMessage(goldLabel(Messages.get("cmd.status.collect-label"))
                .append(Component.text(Messages.get("cmd.status.collect-pending"), YELLOW)));
        } else if (lastAt <= 0L) {
            // 9A-20：首轮采集就一直没产出（超过 10 秒）——这同样属于"采集死了"，不能只说"稍候"
            sender.sendMessage(goldLabel(Messages.get("cmd.status.collect-label"))
                .append(Component.text(Messages.get("cmd.status.collect-never",
                    formatStatusDuration(collector.staleForMs())), RED, TextDecoration.BOLD)));
            sender.sendMessage(Component.text(Messages.get("cmd.status.collect-never-action"), RED));
        } else if (stale) {
            sender.sendMessage(goldLabel(Messages.get("cmd.status.collect-label"))
                .append(Component.text(Messages.get("cmd.status.collect-stale",
                    formatStatusDuration(collector.staleForMs()), formatStatusClock(lastAt)),
                    RED, TextDecoration.BOLD)));
            sender.sendMessage(Component.text(
                Messages.get("cmd.status.collect-stale-warning"), RED));
        } else {
            sender.sendMessage(goldLabel(Messages.get("cmd.status.collect-label"))
                .append(Component.text(Messages.get("cmd.status.collect-ok",
                    formatStatusClock(lastAt), String.format(java.util.Locale.ROOT, "%.1f",
                        collector.staleForMs() / 1000.0)), GREEN)));
        }
        ConfigManager.ConfigData cfg = configManager.get();
        long intervalTicks = cfg == null ? 20L : Math.max(1L, cfg.general_updateInterval);
        sender.sendMessage(goldLabel(Messages.get("cmd.status.interval-label"))
            .append(Component.text(Messages.get("cmd.status.interval", intervalTicks,
                intervalTicks * 50L, collector.staleThresholdMs()), WHITE)));

        // v1.3.0 -04：主线程采集耗时分布。只报单值会误导——采集是分级降频的，
        // 恰好在撞车拍看到 66ms 会让人以为"每秒 66ms"。p50 反映常态，p95/max 反映撞车拍。
        long[] dur = collector.collectDurationStats();
        long warnMs = cn.perfscope.metrics.MetricsCollector.collectDurationWarnMs();
        if (dur[3] <= 0L) {
            sender.sendMessage(goldLabel(Messages.get("cmd.status.collect-cost-label"))
                .append(Component.text(Messages.get("cmd.status.collect-cost-pending"), YELLOW)));
        } else {
            boolean overBudget = dur[4] > 0L;
            sender.sendMessage(goldLabel(Messages.get("cmd.status.collect-cost-label"))
                .append(Component.text(Messages.get("cmd.status.collect-cost",
                    dur[0], dur[1], dur[2], dur[3]), overBudget ? RED : GREEN)));
            if (overBudget) {
                // 带上占比：绝对次数看不出严重程度（2/93 = 2% vs 2/5 = 40%）。
                double pct = dur[3] > 0 ? (100.0 * dur[4] / dur[3]) : 0.0;
                sender.sendMessage(Component.text(Messages.get("cmd.status.collect-cost-warn",
                    dur[4], dur[3], String.format(java.util.Locale.ROOT, "%.1f", pct), warnMs), RED));
            }
        }
        // 9A-20 顺手项：JVM 异步节拍是独立任务——主线程节拍正常 ≠ 内存/CPU/GC 是新的。
        // 异步任务被静默取消时主线程仍每秒产快照，只有这一行能看出来。
        long jvmAt = collector.jvmSnapshotTimestamp();
        if (collector.isJvmStale()) {
            sender.sendMessage(goldLabel(Messages.get("cmd.status.jvm-label"))
                .append(Component.text(jvmAt <= 0L
                    ? Messages.get("cmd.status.jvm-never")
                    : Messages.get("cmd.status.jvm-stale", formatStatusClock(jvmAt)), RED)));
        } else {
            sender.sendMessage(goldLabel(Messages.get("cmd.status.jvm-label"))
                .append(Component.text(Messages.get("cmd.status.jvm-ok", jvmAt <= 0L
                    ? Messages.get("cmd.status.first-round-pending")
                    : formatStatusClock(jvmAt)), GREEN)));
        }
        // 9A-20：周期级 Throwable 逃逸过 → collectorDead；任务仍在按周期重试，故按"是否真的停止产出"
        // 分两种语气：已过期（真的没数据了）才说"已停止"，否则说"本轮异常，等待自愈"
        boolean reallyDead = collector.isCollectorDead() && (stale || cycleFailures >= 10);
        Component cycleState = reallyDead
            ? Component.text(Messages.get("cmd.status.cycle-dead"), RED)
            : (cycleFailures > 0 ? Component.text(Messages.get("cmd.status.cycle-retrying"), YELLOW)
                : Component.text(Messages.get("cmd.state.ok"), GREEN));
        sender.sendMessage(goldLabel(Messages.get("cmd.status.cycle-label"))
            .append(Component.text(Messages.get("cmd.status.cycle-failures", cycleFailures), WHITE))
            .append(cycleState));
        if (cycleFailures > 0 && collector.firstFailureAtMs() > 0L) {
            sender.sendMessage(Component.text(Messages.get("cmd.status.first-failure",
                formatStatusClock(collector.firstFailureAtMs())), GRAY));
        }
        String lastError = collector.lastCollectError();
        if (lastError != null && !lastError.isEmpty()) {
            // f-s02-sec-02：异常原文里常含绝对路径（/srv/mc/plugins/... 或 C:\Users\<服主名>\...）
            // 与完整堆栈片段，而 /perfscope status 的受众是 perfscope.use，未必是服主本人。
            // 聊天框只给「异常类型 + 首句」的脱敏摘要，完整原文留在控制台日志里（那里本来就有）。
            sender.sendMessage(Component.text(
                Messages.get("cmd.status.last-error", scrubForChat(lastError)), GRAY));
        }
        if (collector.isCollectorDead() || cycleFailures >= 10) {
            sender.sendMessage(Component.text(Messages.get("cmd.status.cycle-action"), YELLOW));
        }

        // ---------- 模块状态（9A-20 #3 三态） ----------
        MetricsSnapshot snap = collector.getLatest();
        sender.sendMessage(Component.text(Messages.get("cmd.status.section.modules"), GOLD, TextDecoration.BOLD));
        if (snap == null) {
            sender.sendMessage(Component.text(Messages.get("cmd.status.no-snapshot"), YELLOW));
        } else {
            if (stale) {
                sender.sendMessage(Component.text(Messages.get("cmd.status.snapshot-warning",
                    formatStatusClock(snap.timestamp)), RED));
            }
            Map<String, Integer> failing = collector.failingModules();
            for (String[] entry : STATUS_MODULES) {
                String module = entry[0];
                // 9D：entry[1] 现为消息键，展示名随语言切换
                String display = Messages.get(entry[1]);
                Component state;
                if (snap.platformSkipped.contains(module)) {
                    // 9B-3：平台限制跳过的模块，与"服主自己关掉"必须分开说——
                    // 否则 Folia 上会显示"已禁用（config entities.enabled=false）"，而那配置其实是 true
                    state = Component.text(Messages.get("cmd.status.module.platform-skip"), GRAY);
                } else if (snap.disabledModules.contains(module)) {
                    // entry[2] 为空表示该模块没有配置开关（正常路径不会进 disabledModules，防御性兜底）
                    state = entry[2].isEmpty()
                        ? Component.text(Messages.get("cmd.state.disabled"), GRAY)
                        : Component.text(Messages.get("cmd.status.module.disabled-config",
                            entry[2]), GRAY);
                } else if (snap.warmingUp.contains(module)) {
                    state = Component.text(Messages.get("cmd.status.module.warming"), YELLOW);
                } else if (snap.unavailable.contains(module)) {
                    Integer times = failing.get(module);
                    state = Component.text(times == null
                        ? Messages.get("cmd.status.module.failed")
                        : Messages.get("cmd.status.module.failed-times", times), RED);
                } else {
                    state = Component.text(Messages.get("cmd.state.ok"), GREEN);
                }
                sender.sendMessage(Component.text("  " + display + ": ", WHITE).append(state));
            }
        }

        // ---------- 历史（9A-20 #6：getHistorySize 此前零调用） ----------
        sender.sendMessage(Component.text(Messages.get("cmd.status.section.history"), GOLD, TextDecoration.BOLD));
        sender.sendMessage(goldLabel(Messages.get("cmd.status.ringbuffer-label"))
            .append(Component.text(Messages.get("cmd.status.ringbuffer", collector.getHistorySize(),
                collector.getHistorySize() * Math.max(1L, intervalTicks) / 20L), WHITE)));
        int historySamples = historyStore == null ? 0 : historyStore.size();
        sender.sendMessage(goldLabel(Messages.get("cmd.status.history-ring-label"))
            .append(Component.text(Messages.get("cmd.status.history-ring", historySamples,
                cfg == null ? "?" : cfg.history_sampleIntervalSeconds), WHITE)));
        var fileStore = plugin.getHistoryFileStore();
        boolean persistOn = cfg != null && cfg.history_persistEnabled;
        // 只探目录（available()）曾在写线程被 Error 击穿后继续报"可用"：running 仍为 true、append 继续
        // 入队、队列满后只静默累加 droppedSamples，而 dropped 的唯一上报点全在死线程内 → 结构性谎报绿色。
        // isHealthy() 额外含线程存活 + 连续失败阈值，故此处必须用它；代价与 available() 同量级
        // （同样两次 stat，无额外 IO），可在 /status 路径使用。
        boolean persistOk = fileStore != null && fileStore.isHealthy();
        Component persistState;
        if (!persistOn) {
            persistState = Component.text(Messages.get("cmd.status.persistence-disabled"), GRAY);
        } else if (persistOk) {
            persistState = Component.text(Messages.get("cmd.state.available"), GREEN);
        } else {
            persistState = Component.text(Messages.get("cmd.status.persistence-unavailable"), RED);
        }
        sender.sendMessage(goldLabel(Messages.get("cmd.status.persistence-label")).append(persistState));
        // 目录与 HistoryFileStore 内部布局一致（plugin.getDataFolder()/history）；该路径未对外暴露 getter，
        // 故此处按同一约定推导（若持久化布局变更需同步改这里）
        sender.sendMessage(goldLabel(Messages.get("cmd.status.history-dir-label")).append(Component.text(
            plugin.getDataFolder().toPath().resolve("history").toAbsolutePath().toString(), GRAY)));

        // ---------- 告警（直接读快照，不依赖 AlertService 状态机） ----------
        sender.sendMessage(Component.text(Messages.get("cmd.status.section.alerts"), GOLD, TextDecoration.BOLD));
        if (snap == null) {
            sender.sendMessage(Component.text(Messages.get("cmd.status.no-snapshot-alerts"), YELLOW));
        } else {
            int criticals = snap.activeCriticals.size();
            int warnings = snap.activeWarnings.size();
            Component warnState = warnings == 0 ? Component.text(Messages.get("cmd.state.no-alerts"), GREEN)
                : Component.text(Messages.get("cmd.status.warning-count", warnings), YELLOW);
            Component critState = criticals == 0 ? Component.text(Messages.get("cmd.state.no-criticals"), GREEN)
                : Component.text(Messages.get("cmd.status.critical-count", criticals), RED);
            sender.sendMessage(goldLabel(Messages.get("cmd.status.active-alerts-label")).append(critState)
                .append(Component.text(" | ", WHITE)).append(warnState));
            boolean alertsOn = cfg != null && cfg.alerts_enabled;
            sender.sendMessage(goldLabel(Messages.get("cmd.status.alert-push-label"))
                .append(Component.text(alertsOn
                    ? Messages.get("cmd.state.enabled")
                    : Messages.get("cmd.status.alert-push-disabled"), alertsOn ? GREEN : GRAY)));
        }

        // ---------- 调度器（9A-20 #6：按需现算，不进采集热路径） ----------
        try {
            int pending = Bukkit.getScheduler().getPendingTasks().size();
            sender.sendMessage(goldLabel(Messages.get("cmd.status.scheduler-label"))
                .append(Component.text(Messages.get("cmd.status.scheduler-pending", pending),
                    pending > 500 ? YELLOW : WHITE)));
        } catch (Exception | LinkageError t) {
            sender.sendMessage(goldLabel(Messages.get("cmd.status.scheduler-label"))
                .append(Component.text(Messages.get("cmd.status.scheduler-unavailable"), GRAY)));
        }
    }

    /**
     * 告警历史（9A-14 #4）：回答"这条告警什么时候开始的、持续多久、恢复了吗"。
     *
     * <p>用法：{@code /perfscope alerts [N]}（默认 20，上限 500）。
     * 输出锚点：{@code cmd.section.alerts}（en "=== Alert History ===" / zh "=== 告警历史 ==="）。</p>
     */
    private void showAlerts(CommandSender sender, String[] args) {
        int limit = 20;
        if (args.length > 1) {
            try {
                limit = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text(Messages.get("cmd.error.count-not-integer"), YELLOW));
            }
        }
        limit = Math.max(1, Math.min(500, limit));

        sender.sendMessage(Component.text(Messages.get("cmd.section.alerts"), GOLD, TextDecoration.BOLD));
        var alertService = plugin.getAlertService();
        if (alertService == null) {
            sender.sendMessage(Component.text(Messages.get("cmd.alerts.service-disabled"), GRAY));
            return;
        }
        List<cn.perfscope.alert.AlertService.AlertEvent> events = alertService.recent(limit);
        if (events.isEmpty()) {
            sender.sendMessage(Component.text(Messages.get("cmd.alerts.empty", limit), GRAY));
            return;
        }
        for (var ev : events) {
            TextColor color = "resolve".equals(ev.type()) || "resolved_by_disable".equals(ev.type())
                ? GREEN : "critical".equals(ev.level()) ? RED : YELLOW;
            sender.sendMessage(Component.text(" " + formatStatusClock(ev.ts()) + " ", GRAY)
                .append(Component.text("[" + alertEventTypeName(ev.type()) + "] ", color))
                .append(Component.text(ev.key() + " ", WHITE))
                .append(Component.text(ev.display(), GRAY)));
        }
        sender.sendMessage(Component.text(Messages.get("cmd.alerts.footer", events.size(),
            alertService.getActiveCount(), alertService.getSuspendedCount()), GRAY));
    }

    /**
     * 告警事件类型名（9A-14，9D 改为消息层）。
     *
     * <p>事件<b>令牌</b>（{@code open}/{@code resolve}/…）是 ASCII 且参与 {@code alerts.jsonl}
     * 持久化，永不本地化；只有<b>展示名</b>随语言变化。
     * 键缺失时 {@link Messages#get} 返回键名本身，这里据此回退到原始令牌——
     * 既保证未知类型可见，也不会在界面上冒出 {@code alert.event.xxx} 这种原始键名。</p>
     */
    private static String alertEventTypeName(String type) {
        if (type == null || type.isEmpty()) {
            return "";
        }
        String key = Metric.eventKeyPrefix() + type;
        String name = Messages.get(key);
        return key.equals(name) ? type : name;
    }

    /** 9A-20 status：模块展示表 {模块名, 展示名键, 配置键}（顺序即输出顺序）。 */
    private static final String[][] STATUS_MODULES = {
        {MetricsSnapshot.MODULE_TPS, "cmd.status.module.tps", "tps.enabled"},
        {MetricsSnapshot.MODULE_MSPT, "cmd.status.module.mspt", "mspt.enabled"},
        {MetricsSnapshot.MODULE_PLAYERS, "cmd.status.module.players", "players.enabled"},
        {MetricsSnapshot.MODULE_ENTITIES, "cmd.status.module.entities", "entities.enabled"},
        {MetricsSnapshot.MODULE_CHUNKS, "cmd.status.module.chunks", "chunks.enabled"},
        {MetricsSnapshot.MODULE_TILE_ENTITIES, "cmd.status.module.tile-entities", "tile_entities.enabled"},
        {MetricsSnapshot.MODULE_HOTSPOTS, "cmd.status.module.hotspots", "hotspots.enabled"},
        {MetricsSnapshot.MODULE_MEMORY, "cmd.status.module.memory", "memory.enabled"},
        {MetricsSnapshot.MODULE_CPU, "cmd.status.module.cpu", "cpu.enabled"},
        {MetricsSnapshot.MODULE_GC, "cmd.status.module.gc", ""},
        {MetricsSnapshot.MODULE_SERVER, "cmd.status.module.server", ""},
    };

    /** 9A-20 status：epoch ms → "HH:mm:ss"（本地时区，与"采集时间"行同口径）。 */
    private String formatStatusClock(long epochMs) {
        return DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs));
    }

    /**
     * 9A-20 status：毫秒时长 → "X 分 Y 秒" / "Y 秒"。
     *
     * <p>9D：改用 {@link DurationFormat#duration(long)}（含小时档、双语）。负数在旧实现里返回
     * "未知"，但唯一的数据源 {@code MetricsCollector#staleForMs()} 已用 {@code Math.max(0, …)}
     * 保证非负，那个分支是死代码——统一实现按 0 处理（"0 秒"），不再需要单独的"未知"兜底。</p>
     */
    private String formatStatusDuration(long ms) {
        return DurationFormat.duration(ms);
    }

    // ==================== 实时监测 ====================

    /**
     * 切换某玩家的 monitor 订阅。
     *
     * <p><b>9B-5（原子化）</b>：原实现是"先 isEmpty() 再读 monitorTask 再 cancel/置空"的
     * read-modify-write，Folia 下命令线程与 monitor 任务线程会交错执行 → lost update。
     * 现在启停决策全部交给 CAS：</p>
     * <ul>
     *   <li><b>订阅（开）</b>：先把玩家放进 {@code monitoringPlayers}，再尝试
     *       {@code null → 新任务} 的 CAS 安装。若已有任务（或另一线程正在安装），直接让位——
     *       已存在的任务下一拍必然能看见新订阅者（Set 是并发的），因此"提示已开启但 ActionBar 不刷新"
     *       不再可能；</li>
     *   <li><b>退订（关）</b>：先从 Set 移除，再 {@code getAndSet(null)} 抢走句柄并 cancel，
     *       保证同一句柄只会被取消一次；任务体自己也会在被发现无人订阅时做同样的抢占。</li>
     * </ul>
     *
     * <p>语义等价性：Paper 上只有主线程调用，CAS 在单线程下等价于原来的 if 判断，
     * 消息文案与推送节奏（20 tick）完全不变。</p>
     */
    private void toggleMonitor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(Messages.get("player-only"), YELLOW));
            return;
        }

        if (!ServerCompat.PAPER_ACTION_BAR_AVAILABLE) {
            player.sendMessage(Component.text(Messages.get("monitor-needs-paper"), YELLOW));
            return;
        }

        if (monitoringPlayers.contains(player.getUniqueId())) {
            monitoringPlayers.remove(player.getUniqueId());
            player.sendMessage(Component.text(Messages.get("monitor-off"), GREEN));
            // 9B-5：无人订阅时抢走句柄再取消（抢不到的线程说明别人已经停过了，什么都不做）
            if (monitoringPlayers.isEmpty()) {
                cancelMonitorTaskIfIdle();
            }
        } else {
            monitoringPlayers.add(player.getUniqueId());
            // 9B-5：先入 Set 再装任务——即使这里让位给别人的安装，订阅者也不会丢
            // f-s02-conc-01：noop 时不得回执"已开启"（否则看到绿灯但 ActionBar 永不刷新），
            // 且要撤销刚加入的订阅，让下一次点击仍是"订阅"而不是被误判成"退订"。
            if (startMonitorTask()) {
                player.sendMessage(Component.text(Messages.get("monitor-on"), GREEN));
            } else {
                monitoringPlayers.remove(player.getUniqueId());
                // f-s02-conc-03：noop 与"关服中"要分开报——前者是调度不可用（可重试），
                // 后者是插件正在停用（重试无意义）。旧实现把两种都报成"启动失败，详见控制台"，
                // 而关服分支根本没往控制台写任何东西，用户按提示去查什么也查不到喵。
                // v1.3.0 -14：文案改走键表（此前是内联中英三元表达式，绕过消息层）。
                player.sendMessage(Component.text(Messages.get(monitorShutdown
                    ? "cmd.monitor.failed-shutdown"
                    : "cmd.monitor.failed-no-scheduler"), RED));
            }
        }
    }

    /**
     * 9B-5：无人订阅时停掉 monitor 任务（幂等、可并发调用）。
     *
     * <p>用 {@code getAndSet(null)} 抢占句柄：抢到的那一方才有权取消，避免同一句柄被取消两次。
     * 抢到后必须<b>再查一次</b>订阅集合——存在"任务体刚判完空 → 命令线程入 Set 并装好新任务 →
     * 任务体才走到 getAndSet"的窗口，此时手里拿到的其实是<b>别人的新句柄</b>，
     * 直接取消就会回到"提示已开启但 ActionBar 不刷新"。发现有人订阅就归还句柄，让任务继续跑。</p>
     */
    private void cancelMonitorTaskIfIdle() {
        if (!monitoringPlayers.isEmpty()) {
            return;
        }
        SchedulerAdapter.TaskHandle handle = monitorTask.getAndSet(null);
        if (handle == null) {
            return;
        }
        if (monitorShutdown) {
            handle.cancel(); // 关服中：不归还，直接停
            return;
        }
        if (!monitoringPlayers.isEmpty()) {
            // 抢到句柄之后才出现新订阅者：归还句柄（成功则任务照跑；失败说明别人已装了新任务，手上这份是重复的）
            if (!monitorTask.compareAndSet(null, handle)) {
                handle.cancel();
            }
            return;
        }
        handle.cancel();
    }

    /**
     * 9B-5：确保 monitor 任务在跑（幂等、可并发调用）。
     * 老实现直接赋值 {@code monitorTask = runGlobalTimer(...)}——两个线程同时进来会各建一份任务，
     * 后写的句柄覆盖先写的，先建的任务从此无人能取消（"一次开却有两份刷新"）。
     * 现在用「null → 新句柄」的 CAS 安装：只有一个赢家，输家丢弃自己刚建的任务。
     *
     * @return {@code true} 表示调用返回后确实有一个任务在跑（本线程装的、或别人已装的）；
     *         {@code false} 表示调度失败、任务根本没装上（f-s02-conc-01 的 noop 路径）
     */
    private boolean startMonitorTask() {
        if (monitorShutdown) {
            // f-s02-conc-03：关服中"任务没装上"= false，不是 true。
            // 旧实现返回 true 只为让回执路径维持原状，代价是 toggleMonitor 保留刚加入的订阅，
            // 而 shutdown() 已 clear() 过集合 —— 这个 UUID 就永久留在 Set 里无人服务。
            // 现在如实返回 false，由 toggleMonitor 撤销订阅并给出区分度更高的提示喵。
            return false;
        }
        if (!monitorTaskStarting.compareAndSet(false, true)) {
            return true; // 另一线程正在安装：它装好的任务同样覆盖本线程刚加入的订阅者
        }
        try {
            if (monitorTask.get() != null) {
                return true; // 双检：拿门闩期间别人已装好
            }
            // P0-8：经 SchedulerAdapter.runGlobalTimer 调度，保持 20 tick/次推送间隔。
            // Folia 下该任务运行于 GlobalRegionScheduler（全局区域线程）；给玩家 sendActionBar 的
            // 跨 region 线程问题由 Adventure 消息发送的线程安全性覆盖；A2 修正：本任务体自带 try 边界
            //（此前注释谎称"外层 try 兜底"但实际无 try——单帧异常会被调度器记日志并继续重排，导致每周期刷一条异常噪音）
            SchedulerAdapter.TaskHandle created = SchedulerAdapter.runGlobalTimer(plugin, this::monitorTick, 20L, 20L);
            if (!created.isActive()) {
                // f-s02-conc-01：noop = 任务没装上。装进 monitorTask 会让双检把所有后续订阅挡回，
                // monitor 永久静默空转；不装入则保住 conc-04 依赖的不变量「句柄非空 ⇔ 任务在跑」。
                return false;
            }
            // 9B-5：安装用 CAS；安装失败（他人抢先装好）或安装期间已关服 → 取消刚建的任务避免野跑
            if (monitorShutdown || !monitorTask.compareAndSet(null, created)) {
                created.cancel();
                return true;
            }
            // 9B-5：安装前订阅集合已被清空（创建与安装之间的退订窗口）→ 立刻撤销，不等首次 tick 自愈
            if (monitoringPlayers.isEmpty()) {
                cancelMonitorTaskIfIdle();
            }
            return true;
        } finally {
            monitorTaskStarting.set(false);
        }
    }

    /**
     * 9B-5：monitor 任务体（独立成方法，便于在任务体内判断"自己是否仍是当前句柄"）。
     * 每次执行前先看订阅集合：空 → 抢占并取消自己（与 {@link #toggleMonitor} 的退订路径互斥，只有一方能抢到）。
     */
    private void monitorTick() {
        // A2（T1b）：整帧构建+推送包 try 边界——任何异常仅跳过本轮并记日志，不中断 monitor 任务
        try {
            if (monitoringPlayers.isEmpty()) {
                // 9B-5：任务体不再直接改 monitorTask 字段，改用 CAS 抢占；抢到就取消自己，抢不到说明已被别人取消
                cancelMonitorTaskIfIdle();
                return;
            }
            MetricsSnapshot snap = collector.getLatest();
            if (snap == null) return;

            // f-s02-actionbar-02：采集停摆后 getLatest() 会一直返回同一张旧快照，
            // ActionBar 就会永远显示"健康"的 TPS/MSPT，比不显示更误导——运维看不到掉线信号。
            // 与 /perfscope status 同口径：collector.isStale() 为真时推一条明确的过期提示，
            // 而不是继续渲染旧数值。
            if (collector.isStale()) {
                Component staleMsg = Component.text(
                    Messages.get("cmd.actionbar.stale", collector.staleForMs()), RED);
                for (Iterator<UUID> it = monitoringPlayers.iterator(); it.hasNext();) {
                    UUID uuid = it.next();
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) {
                        it.remove();
                        continue;
                    }
                    if (player.getAddress() != null) {   // 同 sendActionBar 的连接守卫
                        player.sendActionBar(staleMsg);
                    }
                }
                return;
            }

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
            if (cfgNow() != null && cfgNow().general_debug) {
                plugin.getLogger().log(Level.WARNING, "[PerfScope] monitor push failed (skipped this round)", t);
            } else {
                plugin.getLogger().warning("[PerfScope] monitor push failed (skipped this round): " + t.getMessage());
            }
        }
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
        // 9A-1 / 9A-2：MSPT 能力位为 false（Folia 无全局 tick 时间）时 ActionBar 也显示"不可用"，
        // 否则会在 Folia 上渲染出 -1 哨兵派生的"未知"，被误读成"采集坏了"。
        // f-s02-logic-01：与聊天层同口径——非 Folia 的 false 是 TPS 反推降级（采集层仍在出数），放行，
        // 由下方 msptSource 判定追加"(估算)"；只有 Folia 的 false 才挡。
        boolean msptUnavailable = snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)
            || (!ServerCompat.isMsptTrustworthy() && ServerCompat.isFolia());
        Component actionBar = Component.text(
                Messages.get("cmd.actionbar.lead", Messages.get("cmd.unit.tps")), GRAY)
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)
                ? Component.text(Messages.get("cmd.state.unavailable"), GRAY) : formatTpsComponent(snap.tps1m))
            // 9A-2：Folia 上追加极简口径标记（ActionBar 空间有限，用 "gr" 缩写并在 /perfscope tps 展开说明）
            // f-s02-actionbar-04：TPS 不可用时数值已渲染"不可用"，再追加 " gr" 会得到
            // "TPS: unavailable gr" 这种自相矛盾的组合——口径标记只对真实数值有意义。
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_TPS) || ServerCompat.isTpsGlobalScope()
                ? Component.empty() : Component.text(" gr", GRAY))
            .append(Component.text(
                Messages.get("cmd.actionbar.segment", Messages.get("cmd.unit.mspt")), GRAY))
            .append(msptUnavailable
                ? Component.text(Messages.get("cmd.state.unavailable"), GRAY) : formatMSPTComponent(snap.msptAvg))
            // f-s02-data-08 / f-s02-actionbar-01：MSPT 走 TPS 反推路径时，(估算) 口径必须与聊天层一致；
            // 仅在确实渲染出数值时追加——"未知"后面挂"(估算)"是自相矛盾的组合，故排除
            .append(msptUnavailable || snap.msptAvg <= 0
                || snap.msptSource != MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK
                ? Component.empty() : Component.text(Messages.get("cmd.mspt.estimated-tag"), GRAY))
            .append(Component.text(
                Messages.get("cmd.actionbar.segment", Messages.get("cmd.unit.players")), GRAY))
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)
                ? Component.text(Messages.get("cmd.state.unavailable"), GRAY)
                : Component.text(snap.playerCount + "/" + snap.maxPlayers, WHITE))
            .append(Component.text(
                Messages.get("cmd.actionbar.segment", Messages.get("cmd.unit.entities")), GRAY))
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)
                ? Component.text(Messages.get("cmd.state.unavailable"), GRAY)
                // f-s02-actionbar-05：同一份快照在 chat 侧走 thresholdColors 着色，
                // ActionBar 却恒 WHITE——实体数超阈值时两处颜色矛盾（聊天红/黄、ActionBar 白）。
                : Component.text(String.valueOf(snap.totalEntities),
                    thresholdColors.colorOf(thresholdColors.levelOf(
                        ThresholdColors.Metric.ENTITIES, snap.totalEntities))))
            .append(Component.text(
                Messages.get("cmd.actionbar.segment", Messages.get("cmd.unit.memory")), GRAY))
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)
                // W5-D #1：ActionBar 空间有限，预热态用短文案"预热中"（区别于真故障"不可用"）
                ? Component.text(snap.warmingUp.contains(MetricsSnapshot.MODULE_MEMORY)
                    ? Messages.get("cmd.state.warming-up") : Messages.get("cmd.state.unavailable"), GRAY)
                : Component.text(snap.heapUsagePercent + "%",
                    // v1.2 A8：改为读 config 阈值（原硬编码 90/80）
                    thresholdColors.colorOf(thresholdColors.levelOf(
                        ThresholdColors.Metric.MEMORY, snap.heapUsagePercent))))
            .append(Component.text(
                Messages.get("cmd.actionbar.segment", Messages.get("cmd.unit.chunks")), GRAY))
            .append(snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)
                ? Component.text(Messages.get("cmd.state.unavailable"), GRAY)
                : Component.text(String.valueOf(snap.totalLoadedChunks), WHITE));

        player.sendActionBar(actionBar);
    }

    // ==================== 帮助 ====================

    private void showHelp(CommandSender sender) {
        sender.sendMessage(Component.text(
            Messages.get("cmd.section.help", plugin.getDescription().getVersion()), GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text(""));

        // 9D：display 与 desc 随语言切换；clickCmd 一律保持硬编码 ASCII 字面量（见 sendHelpLine 注释）
        sendHelpLine(sender, Messages.get("cmd.help.root.display"), "/perfscope",
            Messages.get("cmd.help.root.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.all.display"), "/perfscope all",
            Messages.get("cmd.help.all.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.tps.display"), "/perfscope tps",
            Messages.get("cmd.help.tps.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.mspt.display"), "/perfscope mspt",
            Messages.get("cmd.help.mspt.desc"));
        // A5（T5a）：带占位符的用法行——display 用文档原文，click 用可执行实例命令。
        // 此前 display 原文被直接当命令执行：点 entities [世界] 以假世界名查询、点 report [格式] 每次静默多生成一份 TXT
        sendHelpLine(sender, Messages.get("cmd.help.entities.display"), "/perfscope entities",
            Messages.get("cmd.help.entities.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.chunks.display"), "/perfscope chunks",
            Messages.get("cmd.help.chunks.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.memory.display"), "/perfscope memory",
            Messages.get("cmd.help.memory.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.cpu.display"), "/perfscope cpu",
            Messages.get("cmd.help.cpu.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.players.display"), "/perfscope players",
            Messages.get("cmd.help.players.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.world.display"), "/perfscope world",
            Messages.get("cmd.help.world.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.redstone.display"), "/perfscope redstone",
            Messages.get("cmd.help.redstone.desc"));
        // W5-D #4：te 自 A5 起已接受世界参数（te foo → 世界不存在: foo），help 补 [世界] 与 entities/chunks 行对齐
        sendHelpLine(sender, Messages.get("cmd.help.tileentities.display"), "/perfscope tileentities",
            Messages.get("cmd.help.tileentities.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.uptime.display"), "/perfscope uptime",
            Messages.get("cmd.help.uptime.desc"));
        // Wave 7A：历史摘要（24h 环形缓冲）
        sendHelpLine(sender, Messages.get("cmd.help.history.display"), "/perfscope history",
            Messages.get("cmd.help.history.desc"));
        // Wave 8C：热点区块定位
        sendHelpLine(sender, Messages.get("cmd.help.hotspot.display"), "/perfscope hotspot",
            Messages.get("cmd.help.hotspot.desc"));
        // Wave 8A：智能诊断引擎（diagnose 不再是 all 的别名；diag 保持全量诊断）
        sendHelpLine(sender, Messages.get("cmd.help.diagnose.display"), "/perfscope diagnose",
            Messages.get("cmd.help.diagnose.desc"));
        // Wave 8B：跨重启趋势
        sendHelpLine(sender, Messages.get("cmd.help.trend.display"), "/perfscope trend",
            Messages.get("cmd.help.trend.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.gc.display"), "/perfscope gc",
            Messages.get("cmd.help.gc.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.report.display"), "/perfscope report html",
            Messages.get("cmd.help.report.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.monitor.display"), "/perfscope monitor",
            Messages.get("cmd.help.monitor.desc"));
        // 9A-20 #5：插件自身健康（采集存活/模块三态/历史/告警）
        sendHelpLine(sender, Messages.get("cmd.help.status.display"), "/perfscope status",
            Messages.get("cmd.help.status.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.alerts.display"), "/perfscope alerts",
            Messages.get("cmd.help.alerts.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.reload.display"), "/perfscope reload",
            Messages.get("cmd.help.reload.desc"));
        sendHelpLine(sender, Messages.get("cmd.help.webhook.display"), "/perfscope webhook test",
            Messages.get("cmd.help.webhook.desc"));

        if (sender instanceof Player) {
            sender.sendMessage(Component.text(""));
            sender.sendMessage(Component.text(Messages.get("cmd.help.click-hint"), GRAY));
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
                "status", "alerts", "report", "monitor", "reload", "webhook", "help"};
            boolean canExport = sender.hasPermission("perfscope.admin") || sender.hasPermission("perfscope.export");
            boolean canReload = sender.hasPermission("perfscope.admin") || sender.hasPermission("perfscope.reload");
            for (String s : subs) {
                // P0-10：report/reload 按权限过滤
                if (s.equals("report") && !canExport) continue;
                if (s.equals("reload") && !canReload) continue;
                if (s.equals("webhook") && !canReload) continue;
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
                case "webhook":
                    for (String sub : new String[]{"status", "test"}) {
                        if (sub.startsWith(args[1].toLowerCase())) completions.add(sub);
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
            ? Component.text(Messages.get("cmd.state.warming-up-long"), GRAY)
            : Component.text(Messages.get("cmd.state.unavailable"), GRAY);
    }

    /** W5-D #1：运行时间行的预热文案（与 unavailableText 同词，独立方法因不涉模块判定）。 */
    private Component uptimeWarmupText() {
        return Component.text(Messages.get("cmd.state.warming-up-long"), GRAY);
    }

    private void sendHelpLine(CommandSender sender, String cmd, String desc) {
        // 无占位符行：display 与可执行命令相同
        sendHelpLine(sender, cmd, cmd, desc);
    }

    private void sendHelpLine(CommandSender sender, String display, String clickCmd, String desc) {
        // A5（T5a）：display=文档用法（可含 [世界]/[格式] 占位符），clickCmd=可执行实例命令（硬编码字面量，无拼接注入面）
        // 注意：clickCmd 是点击后真正执行的命令，必须是硬编码 ASCII 字面量——它属于命令协议而不是文案。
        // 若跟着语言变成 "/perfscope entities [世界]"，点击会把方括号占位符当真实世界名查询（A5/T5a 修过的原始 bug）；
        // 译成描述文字则会直接报未知命令。只有 display 与 desc 才算用户界面文案。
        Component base = Component.text("  " + display, AQUA)
            .hoverEvent(HoverEvent.showText(Component.text(Messages.get("cmd.help.click-hover", clickCmd), GREEN)))
            .clickEvent(ClickEvent.runCommand(clickCmd));
        Component descComp = Component.text(Messages.get("cmd.help.desc-separator", desc), GRAY);
        sender.sendMessage(base.append(descComp));
    }

    private void noData(CommandSender sender) {
        // W5-D #3：说清原因+时限+下一步——旧文案只说"请稍候"，管理员在跑了数天的服上见到它
        // 只会困惑"数据哪去了、是不是我把插件弄坏了"；10 秒与采集侧预热窗口（WARMUP_WINDOW_MS）同口径
        sender.sendMessage(Component.text(
            Messages.get("cmd.error.no-data"), YELLOW));
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
            sender.sendMessage(Component.text(Messages.get("cmd.error.world-missing", worldName), RED));
        }
        return world;
    }

    /**
     * f-s02-err-03：配置快照的空安全取用入口。
     *
     * <p>{@link ConfigManager#get()} 的契约是**可空**（构造期到 {@code load()} 成功之间为 null），
     * 但本类此前 7 个调用点里只有 1 处判空、6 处直接解引用。当前虽不可达（配置加载失败即
     * fail-fast），但这种"靠不变量隐式兜底"的写法在重构时一碰就炸——统一收口到本方法喵。</p>
     */
    private ConfigManager.ConfigData cfgNow() {
        return configManager == null ? null : configManager.get();
    }

    /**
     * 从配置快照读一个数值字段，快照为 null 时回退给定默认值。
     *
     * <p>只用于本类的**展示与建议**路径：配置不可用时应显示"建议阈值"的合理默认，
     * 而不是让整条命令抛 NPE。真正依赖配置的判定（告警开关、采集间隔）走各自的 fail-fast 路径喵。</p>
     */
    private double thresholdOf(ConfigManager.ConfigData cfg, String field, double fallback) {
        if (cfg == null) {
            return fallback;
        }
        switch (field) {
            case "mspt_criticalThreshold":     return cfg.mspt_criticalThreshold;
            case "memory_heapCriticalThreshold": return cfg.memory_heapCriticalThreshold;
            case "entities_totalWarningThreshold":  return cfg.entities_totalWarningThreshold;
            case "entities_totalCriticalThreshold": return cfg.entities_totalCriticalThreshold;
            case "history_persistRetentionDays":    return cfg.history_persistRetentionDays;
            default: return fallback;
        }
    }

    /**
     * f-s02-sec-02：把异常原文压成可安全进聊天框的摘要。
     *
     * <p>规则：只保留异常类型与消息的**首句**，把 Windows / POSIX 绝对路径替换为
     * {@code <path>}。完整原文仍在控制台日志（{@code lastCollectError} 的同时已经
     * 由采集层 {@code SEVERE}/{@code WARNING} 落过一份），所以脱敏不损失排障能力喵。</p>
     */
    private static String scrubForChat(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String s = raw;
        // Windows 盘符路径（含空格）：C:\a b\c  ->  <path>
        s = s.replaceAll("[A-Za-z]:\\\\[^\\s:;,'\"()\\[\\]]+", "<path>");
        // POSIX 绝对路径：/srv/mc/... -> <path>
        s = s.replaceAll("(^|[\\s(])/(?:[^\\s:;,'\"()\\[\\]]+/)+[^\\s:;,'\"()\\[\\]]*", "$1<path>");
        // 只保留首句，避免把堆栈片段带进聊天框
        int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl) + " …";
        }
        return s.length() > 200 ? s.substring(0, 200) + " …" : s;
    }

    private Component formatTpsComponent(double tps) {
        // v1.2 A8：改为读 config 阈值（原硬编码 18/15，与默认巧合一致）
        NamedTextColor color = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.TPS, tps));
        return Component.text(String.format(java.util.Locale.ROOT, "%.1f", Math.min(tps, 20.0)), color, TextDecoration.BOLD);
    }

    /**
     * 9A-2：TPS 口径标注。Paper/Spigot 返回空组件（不产生任何视觉变化），
     * Folia 返回 {@code (global region)}——Bukkit.getTPS() 在 Folia 上只反映 global region，
     * 不是全服吞吐。统一在此处生成，避免 showAll / showTPS / 报告各写一份而说法漂移。
     */
    private Component tpsScopeTag() {
        String label = ServerCompat.tpsScopeLabel();
        return label.isEmpty() ? Component.empty() : Component.text(label, GRAY);
    }

    /**
     * 9A-1：MSPT 不可用原因标注（"（无全局 tick 时间）"等）；可信时返回空组件。
     * 与 {@link #tpsScopeTag()} 同理，文案唯一出处在 {@link ServerCompat#msptUnavailableReason()}。
     */
    private Component msptUnavailableTag() {
        if (ServerCompat.isMsptTrustworthy()) {
            return Component.empty();
        }
        String reason = ServerCompat.msptUnavailableReason();
        return reason == null || reason.isEmpty()
            ? Component.empty() : Component.text(Messages.get("cmd.label.timestamp-paren", reason), GRAY);
    }

    private Component formatMSPTComponent(double mspt) {
        // P0-13：<= 0（含 -1 哨兵与旧 0 值）显示"未知"；真实 MSPT 恒 > 0，无误判
        if (mspt <= 0) {
            return Component.text(Messages.get("cmd.state.unknown"), GRAY);
        }
        // v1.2 A8：改为读 config 阈值（原硬编码 40/50——黄界 40 曾与配置 45 脱节）
        NamedTextColor color = thresholdColors.colorOf(
            thresholdColors.levelOf(ThresholdColors.Metric.MSPT, mspt));
        return Component.text(String.format(java.util.Locale.ROOT, "%.2f ms", mspt), color, TextDecoration.BOLD);
    }

    private String generateBar(int percent, int length) {
        int filled = Math.max(0, Math.min(length, percent * length / 100));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(i < filled ? "|" : ".");
        }
        return sb.toString();
    }

    /**
     * 字节数 → 人类可读文案（f-s05-logic-04；与 {@code ReportGenerator.formatBytes} 同构）。
     *
     * <p>换档判据必须与输出精度同口径：旧实现按原始字节判档、却按 {@code %.1f} 输出，
     * 于是 {@code 1048575} 被格式化成 {@code "1024.0 KB"}、{@code 1073741823} 变成
     * {@code "1024.0 MB"}——读者看到"1024 KB"会以为该进位却没进位。</p>
     */
    private String formatBytes(long bytes) {
        if (bytes < 0) return bytes + " B";
        if (bytes < 1024) return bytes + " B";
        double kb = Math.round(bytes / 102.4) / 10.0;   // 与 %.1f KB 同精度
        if (kb < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        double mb = Math.round(bytes / (1024.0 * 1024.0) * 10.0) / 10.0;   // 与 %.1f MB 同精度
        if (mb < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * 运行时间（秒）→ 时长文案。
     *
     * <p>9D：委托 {@link DurationFormat#uptime(long)}，入参从"秒"换算成毫秒。
     * 旧实现无条件输出秒（"2天 5时 12分 0秒"），现在省略为 0 的分量并最多三级。</p>
     */
    private String formatUptime(long seconds) {
        return DurationFormat.uptime(seconds * 1000L);
    }

    // ==================== 清理 ====================

    /** P0-12：玩家退出时清理监测态（需 PerfScope.onEnable 注册本类为 Listener）。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        monitoringPlayers.remove(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        // 9B-5：先立门闩再停任务——否则并发的 toggle-on 可能在 clear() 与 cancel() 之间重新拉起任务，
        // 关服后留下一个永远推空 ActionBar 的野定时器
        monitorShutdown = true;
        monitoringPlayers.clear();
        // 9B-5：getAndSet(null) 抢占句柄，与任务体/退订路径的取消互斥，句柄只会被取消一次
        SchedulerAdapter.TaskHandle handle = monitorTask.getAndSet(null);
        if (handle != null) {
            handle.cancel();
        }
    }
}