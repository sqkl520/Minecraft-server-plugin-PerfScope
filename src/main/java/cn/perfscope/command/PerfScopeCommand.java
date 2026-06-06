package cn.perfscope.command;

import cn.perfscope.PerfScope;
import cn.perfscope.compat.ServerCompat;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.report.ReportGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PerfScope 指令系统 - 全量诊断 + 专项检测
 * /perfscope (无参数) 或 /perfscope all  →  全量诊断
 * /perfscope tps|mspt|entities|...        →  专项检测
 */
public class PerfScopeCommand implements CommandExecutor, TabCompleter {

    private final PerfScope plugin;
    private final MetricsCollector collector;
    private final ReportGenerator reportGen;
    private final ConfigManager configManager;

    // 实时监测模式中的玩家
    private final Set<UUID> monitoringPlayers = new HashSet<>();
    private int monitorTaskId = -1;

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
            case "all": case "diagnose": case "diag":
                showAll(sender);
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
            sender.sendMessage(Component.text("正在采集数据中，请稍候...", YELLOW));
            return;
        }

        // === 头部 ===
        sender.sendMessage(Component.text("=== PerfScope 诊断 ===", GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("服务端: " + ServerCompat.getServerName(), GRAY));
        sender.sendMessage(Component.text("采集时间: " + new SimpleDateFormat("HH:mm:ss").format(new Date(snap.timestamp)), GRAY));

        // TPS
        sender.sendMessage(yellowLabel("TPS (1m/5m/15m): ")
            .append(formatTpsComponent(snap.tps1m))
            .append(Component.text(" / ", WHITE))
            .append(formatTpsComponent(snap.tps5m))
            .append(Component.text(" / ", WHITE))
            .append(formatTpsComponent(snap.tps15m)));

        // MSPT
        Component msptSource = ServerCompat.PAPER_MSPT_AVAILABLE ? Component.empty()
            : Component.text(" (估算)", GRAY);
        sender.sendMessage(yellowLabel("MSPT (avg/min/max): ")
            .append(formatMSPTComponent(snap.msptAvg))
            .append(msptSource)
            .append(Component.text(" / ", WHITE))
            .append(Component.text(String.format("%.2f", snap.msptMin) + "ms", WHITE))
            .append(Component.text(" / ", WHITE))
            .append(Component.text(String.format("%.2f", snap.msptMax) + "ms", WHITE)));

        // 在线玩家
        sender.sendMessage(goldLabel("在线玩家: ")
            .append(Component.text(snap.playerCount + " / " + snap.maxPlayers, WHITE)));

        // 实体总数
        TextColor entityColor = snap.totalEntities > configManager.get().entities_totalWarningThreshold ? RED
            : (snap.totalEntities > 200 ? YELLOW : GREEN);
        sender.sendMessage(goldLabel("实体总数: ")
            .append(Component.text(String.valueOf(snap.totalEntities), entityColor)));

        // 加载区块
        sender.sendMessage(goldLabel("加载区块: ")
            .append(Component.text(String.valueOf(snap.totalLoadedChunks), WHITE))
            .append(Component.text(" (强加载: " + snap.totalForceLoadedChunks + ")", GRAY)));

        // === 服务器级诊断 ===
        sender.sendMessage(Component.text("--- 服务器级诊断 ---", GOLD, TextDecoration.BOLD));

        for (World world : Bukkit.getWorlds()) {
            int entityCount = snap.entitiesByWorld.getOrDefault(world.getName(), 0);
            int loadedChunks = snap.chunksByWorld.getOrDefault(world.getName(), 0);
            int forceLoaded = snap.forceLoadedChunksByWorld.getOrDefault(world.getName(), 0);
            int tileEntities = snap.tileEntitiesByWorld.getOrDefault(world.getName(), 0);

            TextColor eColor;
            if (entityCount > 500) {
                eColor = RED;
            } else if (entityCount > 200) {
                eColor = YELLOW;
            } else {
                eColor = GREEN;
            }

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

        // 内存
        long usedMem = snap.heapUsed / 1024 / 1024;
        long maxMem = snap.heapMax / 1024 / 1024;
        TextColor memColor = snap.heapUsagePercent >= 90 ? RED
            : (snap.heapUsagePercent >= 80 ? YELLOW : GREEN);
        sender.sendMessage(goldLabel("内存: ")
            .append(Component.text(usedMem + "MB / " + maxMem + "MB", WHITE))
            .append(Component.text(" (" + snap.heapUsagePercent + "%)", memColor)));

        // CPU
        sender.sendMessage(goldLabel("CPU: ")
            .append(Component.text(String.format("进程 %.1f%%", snap.processCpuLoad), WHITE))
            .append(Component.text(" / ", GRAY))
            .append(Component.text(String.format("系统 %.1f%%", snap.systemCpuLoad), WHITE)));

        // 运行时间
        sender.sendMessage(goldLabel("运行时间: ")
            .append(Component.text(formatUptime(snap.uptimeSeconds), WHITE))
            .append(Component.text(" | 线程: " + snap.threadCount, GRAY))
            .append(Component.text(" | 插件: " + snap.loadedPluginCount, GRAY))
            .append(Component.text(" | 世界: " + snap.worldCount, GRAY)));

        // GC
        sender.sendMessage(goldLabel("GC: ")
            .append(Component.text(snap.gcCount + " 次", WHITE))
            .append(Component.text(" / ", GRAY))
            .append(Component.text(String.format("%.2f 秒", snap.gcTime / 1000.0), WHITE)));

        // 红石
        if (snap.redstoneTicks >= 0) {
            sender.sendMessage(goldLabel("红石活动: ")
                .append(Component.text(String.valueOf(snap.redstoneTicks), WHITE)));
        }

        // 网络
        if (snap.bytesSentPerSec > 0 || snap.bytesReceivedPerSec > 0) {
            sender.sendMessage(goldLabel("网络: ")
                .append(Component.text("↑" + formatBytes(snap.bytesSentPerSec) + "/s", WHITE))
                .append(Component.text(" ↓", GRAY))
                .append(Component.text(formatBytes(snap.bytesReceivedPerSec) + "/s", WHITE)));
        }

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

        // MSPT 过高建议
        if (snap.msptAvg > 50) {
            sender.sendMessage(Component.text("警告: MSPT 超过 50ms！TPS 已受影响。", RED));
            sender.sendMessage(Component.text("建议: 检查是否有大量实体堆积、区块加载过多、或其他插件消耗过高的 CPU。", GRAY));
            sender.sendMessage(Component.text("可使用 spark profiler 精确定位: /spark profiler start → /spark profiler stop", GRAY));
        }
    }

    // ==================== 专项检测: TPS ====================

    private void showTPS(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== TPS 诊断 ===", GOLD, TextDecoration.BOLD));
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
            StringBuilder trend = new StringBuilder();
            int start = Math.max(0, history.size() - showCount);
            for (int i = start; i < history.size(); i++) {
                double tps = history.get(i).tps1m;
                if (tps >= 19.5) trend.append("|");
                else if (tps >= 18) trend.append(".");
                else if (tps >= 15) trend.append(":");
                else trend.append("!");
            }
            sender.sendMessage(Component.text("  " + trend.toString(), AQUA));
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
        sender.sendMessage(yellowLabel("平均 MSPT: ")
            .append(formatMSPTComponent(snap.msptAvg)));
        sender.sendMessage(yellowLabel("最小 MSPT: ")
            .append(Component.text(String.format("%.2f ms", snap.msptMin), WHITE)));
        sender.sendMessage(yellowLabel("最大 MSPT: ")
            .append(Component.text(String.format("%.2f ms", snap.msptMax), WHITE)));

        if (snap.msptAvg > 0) {
            double maxTPS = 1000.0 / snap.msptAvg;
            sender.sendMessage(goldLabel("理论最大 TPS: ")
                .append(Component.text(String.format("%.1f", Math.min(maxTPS, 20.0)), AQUA)));
        }

        if (snap.msptAvg > 50) {
            sender.sendMessage(Component.text("警告: MSPT 超过 50ms！TPS 已受影响。", RED));
            sender.sendMessage(Component.text("建议: 检查是否有大量实体堆积、区块加载过多、或其他插件消耗过高的 CPU。", GRAY));
            sender.sendMessage(Component.text("可使用 spark profiler 精确定位: /spark profiler start → /spark profiler stop", GRAY));
        }
    }

    // ==================== 专项检测: 实体 ====================

    private void showEntities(CommandSender sender, String worldName) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        String title = worldName != null ? "=== 实体统计 - " + worldName + " ===" : "=== 实体统计 ===";
        sender.sendMessage(Component.text(title, GOLD, TextDecoration.BOLD));

        TextColor entityColor = snap.totalEntities > configManager.get().entities_totalWarningThreshold ? RED
            : (snap.totalEntities > 200 ? YELLOW : GREEN);
        sender.sendMessage(goldLabel("总实体数: ")
            .append(Component.text(String.valueOf(snap.totalEntities), entityColor, TextDecoration.BOLD)));

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("按世界分布:", GRAY));
        for (Map.Entry<String, Integer> entry : snap.entitiesByWorld.entrySet()) {
            if (worldName != null && !entry.getKey().equalsIgnoreCase(worldName)) continue;
            TextColor ec = entry.getValue() > 500 ? RED : (entry.getValue() > 200 ? YELLOW : GREEN);
            sender.sendMessage(Component.text("  " + entry.getKey() + ": ", WHITE)
                .append(Component.text(String.valueOf(entry.getValue()), ec)));
        }

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("按类型分布 (Top 15):", GRAY));
        snap.entitiesByType.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(15)
            .forEach(entry -> {
                double pct = snap.totalEntities > 0
                    ? (double) entry.getValue() / snap.totalEntities * 100 : 0;
                sender.sendMessage(Component.text("  " + entry.getKey() + ": ", WHITE)
                    .append(Component.text(entry.getValue() + " (" + String.format("%.1f", pct) + "%)", AQUA)));
            });

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

        sender.sendMessage(Component.text("=== 区块统计 ===", GOLD, TextDecoration.BOLD));
        sender.sendMessage(goldLabel("总加载区块: ")
            .append(Component.text(String.valueOf(snap.totalLoadedChunks), WHITE, TextDecoration.BOLD)));
        sender.sendMessage(goldLabel("总强加载区块: ")
            .append(Component.text(String.valueOf(snap.totalForceLoadedChunks), WHITE)));

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("按世界分布:", GRAY));
        for (Map.Entry<String, Integer> entry : snap.chunksByWorld.entrySet()) {
            if (worldName != null && !entry.getKey().equalsIgnoreCase(worldName)) continue;
            int forceLoaded = snap.forceLoadedChunksByWorld.getOrDefault(entry.getKey(), 0);
            TextColor cc = entry.getValue() > 1500 ? RED : (entry.getValue() > 800 ? YELLOW : GREEN);
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

        long usedMemMB = snap.heapUsed / 1024 / 1024;
        long maxMemMB = snap.heapMax / 1024 / 1024;
        TextColor memColor = snap.heapUsagePercent >= 90 ? RED
            : (snap.heapUsagePercent >= 80 ? YELLOW : GREEN);

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

        sender.sendMessage(goldLabel("GC 次数: ")
            .append(Component.text(String.valueOf(snap.gcCount), WHITE)));
        sender.sendMessage(goldLabel("GC 耗时: ")
            .append(Component.text(snap.gcTime + " ms", WHITE)));

        if (snap.heapUsagePercent >= 90) {
            sender.sendMessage(Component.text("严重警告: 堆内存使用率超过 90%！", RED));
            sender.sendMessage(Component.text("建议: 增加服务器最大内存 (-Xmx) 或检查是否有内存泄漏。", GRAY));
        }
    }

    // ==================== 专项检测: CPU ====================

    private void showCPU(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== CPU 诊断 ===", GOLD, TextDecoration.BOLD));

        TextColor processColor = snap.processCpuLoad > 80 ? RED
            : (snap.processCpuLoad > 60 ? YELLOW : GREEN);
        TextColor systemColor = snap.systemCpuLoad > 90 ? RED
            : (snap.systemCpuLoad > 70 ? YELLOW : GREEN);

        sender.sendMessage(yellowLabel("进程 CPU 使用率: ")
            .append(Component.text(String.format("%.1f%%", snap.processCpuLoad), processColor, TextDecoration.BOLD)));
        sender.sendMessage(yellowLabel("系统 CPU 负载: ")
            .append(Component.text(String.format("%.1f%%", snap.systemCpuLoad), systemColor)));
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
        sender.sendMessage(goldLabel("在线玩家: ")
            .append(Component.text(snap.playerCount + " / " + snap.maxPlayers, WHITE, TextDecoration.BOLD)));

        int pct = snap.maxPlayers > 0 ? (snap.playerCount * 100 / snap.maxPlayers) : 0;
        String pBar = generateBar(pct, 30);
        TextColor pctColor = pct >= 95 ? RED : (pct >= 80 ? YELLOW : GREEN);
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

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(Component.text("世界 " + worldName + " 不存在喵", RED));
            return;
        }

        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== 世界详情 - " + world.getName() + " ===", GOLD, TextDecoration.BOLD));
        sender.sendMessage(goldLabel("维度: ").append(Component.text(world.getEnvironment().name(), WHITE)));
        sender.sendMessage(goldLabel("实体数: ").append(Component.text(
            String.valueOf(snap.entitiesByWorld.getOrDefault(worldName, 0)), WHITE)));
        sender.sendMessage(goldLabel("加载区块: ").append(Component.text(
            String.valueOf(snap.chunksByWorld.getOrDefault(worldName, 0)), WHITE)));
        sender.sendMessage(goldLabel("强加载区块: ").append(Component.text(
            String.valueOf(snap.forceLoadedChunksByWorld.getOrDefault(worldName, 0)), WHITE)));
        sender.sendMessage(goldLabel("Tile Entity: ").append(Component.text(
            String.valueOf(snap.tileEntitiesByWorld.getOrDefault(worldName, 0)), WHITE)));

        int playersInWorld = (int) Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.getWorld().getName().equals(worldName))
            .count();
        sender.sendMessage(goldLabel("世界内玩家: ").append(Component.text(String.valueOf(playersInWorld), WHITE)));

        ConfigManager.WorldSettings ws = configManager.getWorldSettings(worldName);
        if (ws != null) {
            sender.sendMessage(goldLabel("实体告警阈值: ")
                .append(Component.text("Warn=" + ws.entities_totalWarningThreshold + " Crit=" + ws.entities_totalCriticalThreshold, GRAY)));
        }
    }

    // ==================== 专项检测: 红石 ====================

    private void showRedstone(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== 红石活动 ===", GOLD, TextDecoration.BOLD));
        if (snap.redstoneTicks >= 0) {
            sender.sendMessage(goldLabel("活跃红石线: ")
                .append(Component.text(String.valueOf(snap.redstoneTicks), WHITE, TextDecoration.BOLD)));
        } else {
            sender.sendMessage(Component.text("红石活动追踪不可用 (需要 Paper 服务端)", GRAY));
        }
    }

    // ==================== 专项检测: Tile Entity ====================

    private void showTileEntities(CommandSender sender, String worldName) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== Tile Entity 统计 ===", GOLD, TextDecoration.BOLD));
        sender.sendMessage(goldLabel("总 Tile Entity: ")
            .append(Component.text(String.valueOf(snap.tileEntityCount), WHITE, TextDecoration.BOLD)));

        sender.sendMessage(Component.text(""));
        sender.sendMessage(Component.text("按世界分布:", GRAY));
        for (Map.Entry<String, Integer> entry : snap.tileEntitiesByWorld.entrySet()) {
            if (worldName != null && !entry.getKey().equalsIgnoreCase(worldName)) continue;
            sender.sendMessage(Component.text("  " + entry.getKey() + ": ", WHITE)
                .append(Component.text(String.valueOf(entry.getValue()), AQUA)));
        }
    }

    // ==================== 专项检测: 运行时间 ====================

    private void showUptime(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== 服务器运行信息 ===", GOLD, TextDecoration.BOLD));
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

    // ==================== 专项检测: GC ====================

    private void showGC(CommandSender sender) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) { noData(sender); return; }

        sender.sendMessage(Component.text("=== GC 统计 ===", GOLD, TextDecoration.BOLD));
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
        if (!sender.hasPermission("perfscope.export")) {
            sender.sendMessage(Component.text("没有导出报告权限喵", RED));
            return;
        }
        String path = args.length > 1 ? reportGen.generateSpecific(args[1]) : reportGen.generateAndSave();
        if (path != null) {
            sender.sendMessage(Component.text("报告已生成: " + path, GREEN));
        } else {
            sender.sendMessage(Component.text("报告生成失败喵", RED));
        }
    }

    // ==================== 重载 ====================

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("perfscope.reload")) {
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
            if (monitoringPlayers.isEmpty() && monitorTaskId != -1) {
                Bukkit.getScheduler().cancelTask(monitorTaskId);
                monitorTaskId = -1;
            }
        } else {
            monitoringPlayers.add(player.getUniqueId());
            player.sendMessage(Component.text("实时监测已开启喵~ 使用 /perfscope monitor 关闭", GREEN));
            if (monitorTaskId == -1) {
                startMonitorTask();
            }
        }
    }

    private void startMonitorTask() {
        monitorTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (monitoringPlayers.isEmpty()) {
                Bukkit.getScheduler().cancelTask(monitorTaskId);
                monitorTaskId = -1;
                return;
            }
            MetricsSnapshot snap = collector.getLatest();
            if (snap == null) return;

            for (UUID uuid : monitoringPlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null) {
                    monitoringPlayers.remove(uuid);
                    continue;
                }
                sendActionBar(player, snap);
            }
        }, 20L, 20L);
    }

    private void sendActionBar(Player player, MetricsSnapshot snap) {
        NamedTextColor tpsColor = snap.tps1m >= 19.5 ? GREEN : (snap.tps1m >= 15.0 ? YELLOW : RED);
        NamedTextColor memColor = snap.heapUsagePercent >= 80 ? YELLOW : GREEN;

        Component actionBar = Component.text("TPS: ", GRAY)
            .append(Component.text(String.format("%.1f", snap.tps1m), tpsColor))
            .append(Component.text(" | MSPT: ", GRAY))
            .append(Component.text(String.format("%.1f", snap.msptAvg), AQUA))
            .append(Component.text(" | 玩家: ", GRAY))
            .append(Component.text(snap.playerCount + "/" + snap.maxPlayers, WHITE))
            .append(Component.text(" | 实体: ", GRAY))
            .append(Component.text(String.valueOf(snap.totalEntities), WHITE))
            .append(Component.text(" | 内存: ", GRAY))
            .append(Component.text(snap.heapUsagePercent + "%", memColor))
            .append(Component.text(" | 区块: ", GRAY))
            .append(Component.text(String.valueOf(snap.totalLoadedChunks), WHITE));

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
        sendHelpLine(sender, "/perfscope entities [世界]", "实体统计");
        sendHelpLine(sender, "/perfscope chunks [世界]", "区块统计");
        sendHelpLine(sender, "/perfscope memory", "内存诊断");
        sendHelpLine(sender, "/perfscope cpu", "CPU 诊断");
        sendHelpLine(sender, "/perfscope players", "玩家统计");
        sendHelpLine(sender, "/perfscope world [世界]", "世界详情");
        sendHelpLine(sender, "/perfscope redstone", "红石活动");
        sendHelpLine(sender, "/perfscope tileentities", "Tile Entity 统计");
        sendHelpLine(sender, "/perfscope uptime", "运行时间");
        sendHelpLine(sender, "/perfscope gc", "GC 统计");
        sendHelpLine(sender, "/perfscope report [格式]", "导出性能报告");
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
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String[] subs = {"all", "tps", "mspt", "entities", "chunks", "memory", "cpu",
                "players", "world", "redstone", "tileentities", "uptime", "gc",
                "report", "monitor", "reload", "help"};
            for (String s : subs) {
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

    private void sendHelpLine(CommandSender sender, String cmd, String desc) {
        Component base = Component.text("  " + cmd, AQUA)
            .hoverEvent(HoverEvent.showText(Component.text("点击执行 " + cmd, GREEN)))
            .clickEvent(ClickEvent.runCommand(cmd));
        Component descComp = Component.text(" - " + desc, GRAY);
        sender.sendMessage(base.append(descComp));
    }

    private void noData(CommandSender sender) {
        sender.sendMessage(Component.text("正在采集数据中，请稍候...", YELLOW));
    }

    private Component formatTpsComponent(double tps) {
        NamedTextColor color;
        if (tps >= 18.0) color = GREEN;
        else if (tps >= 15.0) color = YELLOW;
        else color = RED;
        return Component.text(String.format("%.1f", Math.min(tps, 20.0)), color, TextDecoration.BOLD);
    }

    private Component formatMSPTComponent(double mspt) {
        NamedTextColor color;
        if (mspt <= 40) color = GREEN;
        else if (mspt <= 50) color = YELLOW;
        else color = RED;
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

    public void shutdown() {
        monitoringPlayers.clear();
        if (monitorTaskId != -1) {
            Bukkit.getScheduler().cancelTask(monitorTaskId);
            monitorTaskId = -1;
        }
    }
}