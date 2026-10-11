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
package cn.perfscope.report;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 性能报告生成器 - 支持 HTML / TXT / JSON 三种格式
 * 自动管理报告清理
 */
public class ReportGenerator {

    private final PerfScope plugin;
    private final MetricsCollector collector;
    private final ConfigManager configManager;
    private final File reportDir;

    // P0-11：线程安全的日期格式化器（SimpleDateFormat 非线程安全，已删除）
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // P0-11：JSON 改用 Gson（服务端 provided）；serializeNulls 让 unavailable 语义明确
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    public ReportGenerator(PerfScope plugin) {
        this.plugin = plugin;
        this.collector = plugin.getMetricsCollector();
        this.configManager = plugin.getConfigManager();
        this.reportDir = new File(plugin.getDataFolder(), "reports");
        if (!reportDir.exists()) {
            reportDir.mkdirs();
        }
    }

    /**
     * 生成并保存报告 (默认格式)
     */
    public String generateAndSave() {
        return generateSpecific(configManager.get().report_format);
    }

    /**
     * 按指定格式生成并保存报告
     */
    public String generateSpecific(String format) {
        MetricsSnapshot snap = collector.getLatest();
        if (snap == null) return null;

        List<MetricsSnapshot> history = collector.getHistory();

        File file;

        switch (format.toLowerCase()) {
            case "html":
                file = resolveReportFile("html");
                try {
                    Files.write(file.toPath(), generateHtmlReport(snap, history).getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    plugin.getLogger().warning("生成HTML报告失败: " + e.getMessage());
                    return null;
                } catch (Throwable t) {
                    // P0-11 兜底：极端精简端缺 Gson 等运行时异常同样返回 null
                    plugin.getLogger().warning("生成HTML报告失败: " + t.getMessage());
                    return null;
                }
                break;
            case "json":
                file = resolveReportFile("json");
                try {
                    Files.write(file.toPath(), generateJsonReport(snap, history).getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    plugin.getLogger().warning("生成JSON报告失败: " + e.getMessage());
                    return null;
                } catch (Throwable t) {
                    plugin.getLogger().warning("生成JSON报告失败: " + t.getMessage());
                    return null;
                }
                break;
            case "txt": case "text":
            default:
                file = resolveReportFile("txt");
                try {
                    Files.write(file.toPath(), generateTextReport(snap, history).getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    plugin.getLogger().warning("生成TXT报告失败: " + e.getMessage());
                    return null;
                } catch (Throwable t) {
                    plugin.getLogger().warning("生成TXT报告失败: " + t.getMessage());
                    return null;
                }
                break;
        }

        // 清理旧报告
        cleanOldReports();

        return file.getAbsolutePath();
    }

    /**
     * P0-11：解析报告文件路径。基名精确到毫秒；若已存在则追加 -1/-2... 计数（防毫秒碰撞双保险）。
     */
    private File resolveReportFile(String extension) {
        String base = "perfscope-" + LocalDateTime.now().format(FILE_TS);
        File file = new File(reportDir, base + "." + extension);
        int counter = 1;
        while (file.exists()) {
            file = new File(reportDir, base + "-" + counter + "." + extension);
            counter++;
        }
        return file;
    }

    /**
     * 清理超过保留天数的旧报告
     */
    public void cleanOldReports() {
        int retentionDays = configManager.get().report_retentionDays;
        if (retentionDays <= 0) return;

        long cutoff = System.currentTimeMillis() - (long) retentionDays * 24 * 3600 * 1000;
        File[] files = reportDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    // ==================== HTML 报告 ====================

    private String generateHtmlReport(MetricsSnapshot snap, List<MetricsSnapshot> history) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>PerfScope 性能报告</title>\n");
        html.append("<style>\n");
        html.append(loadHtmlCss());
        html.append("</style>\n</head>\n<body>\n");

        // 头部
        html.append("<div class=\"header\">\n");
        html.append("  <h1>PerfScope 服务器性能报告</h1>\n");
        html.append("  <p>生成时间: ").append(LocalDateTime.now().format(DISPLAY_TS)).append("</p>\n");
        html.append("  <p>服务器运行时间: ").append(formatUptime(snap.uptimeSeconds)).append("</p>\n");
        html.append("</div>\n");

        html.append("<div class=\"container\">\n");

        // 概览卡片
        html.append("<div class=\"section\">\n");
        html.append("<h2>性能概览</h2>\n");
        html.append("<div class=\"card-grid\">\n");

        // P0-5：unavailable 模块显示"不可用"；P0-13：MSPT/CPU -1 哨兵显示"未知"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            html.append(htmlCard("TPS (1分钟)", "不可用", "unknown", ""));
        } else {
            html.append(htmlCard("TPS (1分钟)", String.format("%.2f", snap.tps1m), getTpsClass(snap.tps1m),
                "5分钟: " + String.format("%.2f", snap.tps5m) + " | 15分钟: " + String.format("%.2f", snap.tps15m)));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)) {
            html.append(htmlCard("MSPT (平均)", "不可用", "unknown", ""));
        } else if (snap.msptAvg < 0) {
            html.append(htmlCard("MSPT (平均)", "未知", "unknown", "健康服 TPS=20 时估算无意义"));
        } else {
            html.append(htmlCard("MSPT (平均)", String.format("%.2f ms", snap.msptAvg), getMsptClass(snap.msptAvg),
                "最小: " + String.format("%.2f", snap.msptMin) + "ms | 最大: " + String.format("%.2f", snap.msptMax) + "ms"));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            html.append(htmlCard("在线玩家", "不可用", "unknown", ""));
        } else {
            html.append(htmlCard("在线玩家", snap.playerCount + " / " + snap.maxPlayers, "normal", ""));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            html.append(htmlCard("实体总数", "不可用", "unknown", ""));
        } else {
            html.append(htmlCard("实体总数", String.valueOf(snap.totalEntities), "normal", ""));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)) {
            html.append(htmlCard("加载区块", "不可用", "unknown", ""));
        } else {
            html.append(htmlCard("加载区块", String.valueOf(snap.totalLoadedChunks), "normal",
                "强加载: " + snap.totalForceLoadedChunks));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            html.append(htmlCard("堆内存", "不可用", "unknown", ""));
        } else {
            html.append(htmlCard("堆内存", formatBytes(snap.heapUsed) + " / " + formatBytes(snap.heapMax),
                getMemClass(snap.heapUsagePercent), "使用率: " + snap.heapUsagePercent + "%"));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CPU)) {
            html.append(htmlCard("进程CPU", "不可用", "unknown", ""));
        } else {
            html.append(htmlCard("进程CPU", formatCpuPct(snap.processCpuLoad), "normal",
                "系统CPU: " + formatCpuPct(snap.systemCpuLoad)));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_SERVER)) {
            html.append(htmlCard("已加载世界", "不可用", "unknown", ""));
        } else {
            html.append(htmlCard("已加载世界", String.valueOf(snap.worldCount), "normal",
                "已加载插件: " + snap.loadedPluginCount));
        }

        html.append("</div></div>\n");

        // TPS趋势图 (简单ASCII风格)
        if (history.size() > 1) {
            html.append("<div class=\"section\">\n");
            html.append("<h2>TPS 趋势 (最近").append(Math.min(60, history.size())).append("次采样)</h2>\n");
            html.append("<div class=\"chart\">\n");
            html.append("<div class=\"tps-graph\">\n");
            int start = Math.max(0, history.size() - 60);
            double maxTps = 20.0;
            for (int i = start; i < history.size(); i++) {
                MetricsSnapshot h = history.get(i);
                double pct = h.tps1m / maxTps * 100;
                String color = h.tps1m >= 19.5 ? "#69F0AE" : h.tps1m >= 18 ? "#4CAF50" : h.tps1m >= 15 ? "#FFB300" : "#FF5252";
                html.append("<div class=\"bar\" style=\"height:").append(String.format("%.1f", pct))
                    .append("%;background:").append(color).append("\" title=\"")
                    .append(String.format("%.2f", h.tps1m)).append("\"></div>\n");
            }
            html.append("</div>\n");
            html.append("<div class=\"chart-labels\">\n");
            html.append("<span>20.0</span><span>15.0</span><span>10.0</span><span>5.0</span><span>0.0</span>\n");
            html.append("</div></div></div>\n");
        }

        // 实体分布
        html.append("<div class=\"section\">\n");
        html.append("<h2>实体按世界分布</h2>\n");
        html.append("<table><tr><th>世界</th><th>实体数</th><th>加载区块</th><th>Tile Entity</th></tr>\n");
        for (Map.Entry<String, Integer> entry : snap.entitiesByWorld.entrySet()) {
            String world = entry.getKey();
            int entities = entry.getValue();
            int chunks = snap.chunksByWorld.getOrDefault(world, 0);
            int tileEntities = snap.tileEntitiesByWorld.getOrDefault(world, 0);
            html.append("<tr><td>").append(escapeHtml(world)).append("</td><td>").append(entities)
                .append("</td><td>").append(chunks).append("</td><td>").append(tileEntities)
                .append("</td></tr>\n");
        }
        html.append("</table></div>\n");

        // 实体类型 Top 15
        html.append("<div class=\"section\">\n");
        html.append("<h2>实体类型分布 (Top 15)</h2>\n");
        // P0-5：实体模块 unavailable 时显示"不可用"（灰色），而非空表
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            html.append("<p style=\"color:#90a4ae\">不可用</p>\n");
        } else {
            html.append("<table><tr><th>实体类型</th><th>数量</th><th>占比</th></tr>\n");
            snap.entitiesByType.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .forEach(entry -> {
                    double pct = snap.totalEntities > 0 ?
                        (double) entry.getValue() / snap.totalEntities * 100 : 0;
                    html.append("<tr><td>").append(escapeHtml(entry.getKey())).append("</td><td>")
                        .append(entry.getValue()).append("</td><td>")
                        .append(String.format("%.1f%%", pct)).append("</td></tr>\n");
                });
            html.append("</table>\n");
        }
        html.append("</div>\n");

        // 内存详情
        html.append("<div class=\"section\">\n");
        html.append("<h2>内存详情</h2>\n");
        // P0-5：内存模块 unavailable 时整段显示"不可用"（灰色），而非 0 值进度条
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            html.append("<p style=\"color:#90a4ae\">不可用</p>\n");
        } else {
            html.append("<div class=\"mem-bar\">\n");
            html.append("<div class=\"mem-fill\" style=\"width:").append(snap.heapUsagePercent)
                .append("%;background:").append(getMemBarColor(snap.heapUsagePercent)).append("\"></div>\n");
            html.append("</div>\n");
            html.append("<p>堆内存: ").append(formatBytes(snap.heapUsed)).append(" / ")
                .append(formatBytes(snap.heapMax)).append(" (").append(snap.heapUsagePercent).append("%)</p>\n");
            html.append("<p>非堆内存: ").append(formatBytes(snap.nonHeapUsed));
            if (snap.nonHeapMax > 0) html.append(" / ").append(formatBytes(snap.nonHeapMax));
            html.append("</p>\n");
        }
        // P0-5：GC 模块独立判断（与概览一致），unavailable 时显示"不可用"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            html.append("<p style=\"color:#90a4ae\">GC: 不可用</p>\n");
        } else {
            html.append("<p>GC次数: ").append(snap.gcCount).append(" | GC耗时: ").append(snap.gcTime).append("ms</p>\n");
        }
        html.append("</div>\n");

        // 告警
        if (!snap.activeCriticals.isEmpty() || !snap.activeWarnings.isEmpty()) {
            html.append("<div class=\"section\">\n");
            html.append("<h2>活跃告警</h2>\n");
            for (String critical : snap.activeCriticals) {
                html.append("<p class=\"critical\">[!!!] ").append(escapeHtml(critical)).append("</p>\n");
            }
            for (String warning : snap.activeWarnings) {
                html.append("<p class=\"warning\">[!] ").append(escapeHtml(warning)).append("</p>\n");
            }
            html.append("</div>\n");
        }

        html.append("</div>\n");

        // 页脚
        html.append("<div class=\"footer\">\n");
        html.append("<p>Generated by PerfScope v").append(plugin.getDescription().getVersion())
            .append(" | Powered by PaperMC</p>\n");
        html.append("</div>\n");

        html.append("</body>\n</html>");
        return html.toString();
    }

    // ==================== TXT 报告 ====================

    private String generateTextReport(MetricsSnapshot snap, List<MetricsSnapshot> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("  PerfScope 服务器性能报告\n");
        sb.append("========================================\n");
        sb.append("生成时间: ").append(LocalDateTime.now().format(DISPLAY_TS)).append("\n");
        sb.append("运行时间: ").append(formatUptime(snap.uptimeSeconds)).append("\n\n");

        sb.append("--- 性能概览 ---\n");
        // P0-5：unavailable 模块显示"不可用"；P0-13：MSPT/CPU -1 哨兵显示"未知"
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            sb.append("TPS (1m/5m/15m): 不可用\n");
        } else {
            sb.append(String.format("TPS (1m/5m/15m): %.2f / %.2f / %.2f\n", snap.tps1m, snap.tps5m, snap.tps15m));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)) {
            sb.append("MSPT (avg/min/max): 不可用\n");
        } else if (snap.msptAvg < 0) {
            sb.append("MSPT (avg/min/max): 未知\n");
        } else {
            sb.append(String.format("MSPT (avg/min/max): %.2f / %.2f / %.2f ms\n", snap.msptAvg, snap.msptMin, snap.msptMax));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            sb.append("在线玩家: 不可用\n");
        } else {
            sb.append(String.format("在线玩家: %d / %d\n", snap.playerCount, snap.maxPlayers));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            sb.append("实体总数: 不可用\n");
        } else {
            sb.append(String.format("实体总数: %d\n", snap.totalEntities));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)) {
            sb.append("加载区块: 不可用\n");
        } else {
            sb.append(String.format("加载区块: %d (强加载: %d)\n", snap.totalLoadedChunks, snap.totalForceLoadedChunks));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            sb.append("堆内存: 不可用\n");
        } else {
            sb.append(String.format("堆内存: %s / %s (%d%%)\n", formatBytes(snap.heapUsed),
                formatBytes(snap.heapMax), snap.heapUsagePercent));
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CPU)) {
            sb.append("进程CPU: 不可用\n");
        } else {
            sb.append("进程CPU: ").append(formatCpuPct(snap.processCpuLoad))
                .append(" | 系统CPU: ").append(formatCpuPct(snap.systemCpuLoad)).append("\n");
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            sb.append("GC: 不可用\n");
        } else {
            sb.append(String.format("GC: %d次 / %dms\n", snap.gcCount, snap.gcTime));
        }
        sb.append("\n");

        sb.append("--- 实体按世界分布 ---\n");
        for (Map.Entry<String, Integer> entry : snap.entitiesByWorld.entrySet()) {
            sb.append(String.format("  %s: %d 实体 | %d 区块 | %d TileEntity\n",
                entry.getKey(), entry.getValue(),
                snap.chunksByWorld.getOrDefault(entry.getKey(), 0),
                snap.tileEntitiesByWorld.getOrDefault(entry.getKey(), 0)));
        }
        sb.append("\n");

        sb.append("--- 实体类型 Top 10 ---\n");
        // P0-5：实体模块 unavailable 时显示"不可用"，而非空列表
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            sb.append("  不可用\n");
        } else {
            snap.entitiesByType.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> {
                    double pct = snap.totalEntities > 0 ?
                        (double) entry.getValue() / snap.totalEntities * 100 : 0;
                    sb.append(String.format("  %s: %d (%.1f%%)\n", entry.getKey(), entry.getValue(), pct));
                });
        }
        sb.append("\n");

        if (!snap.activeCriticals.isEmpty() || !snap.activeWarnings.isEmpty()) {
            sb.append("--- 活跃告警 ---\n");
            for (String c : snap.activeCriticals) sb.append("  [!!!] ").append(c).append("\n");
            for (String w : snap.activeWarnings) sb.append("  [!] ").append(w).append("\n");
            sb.append("\n");
        }

        sb.append("========================================\n");
        sb.append("Generated by PerfScope v").append(plugin.getDescription().getVersion()).append("\n");
        return sb.toString();
    }

    // ==================== JSON 报告 ====================

    /**
     * P0-11：JSON 改用 Gson 生成。键名与旧手拼版本完全一致（老解析脚本兼容）。
     * P0-5：unavailable 模块对应段输出 {"status": "unavailable"}。
     * P0-13：MSPT/CPU 保留数字 -1 哨兵，前端自解释。
     */
    private String generateJsonReport(MetricsSnapshot snap, List<MetricsSnapshot> history) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generated_at", LocalDateTime.now().format(DISPLAY_TS));
        root.put("plugin_version", plugin.getDescription().getVersion());

        // TPS
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            root.put("tps", unavailableSection());
        } else {
            Map<String, Object> tps = new LinkedHashMap<>();
            tps.put("1m", safeDouble(snap.tps1m));
            tps.put("5m", safeDouble(snap.tps5m));
            tps.put("15m", safeDouble(snap.tps15m));
            root.put("tps", tps);
        }

        // MSPT
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)) {
            root.put("mspt", unavailableSection());
        } else {
            Map<String, Object> mspt = new LinkedHashMap<>();
            mspt.put("avg", safeDouble(snap.msptAvg));
            mspt.put("min", safeDouble(snap.msptMin));
            mspt.put("max", safeDouble(snap.msptMax));
            root.put("mspt", mspt);
        }

        // 玩家
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            root.put("players", unavailableSection());
        } else {
            Map<String, Object> players = new LinkedHashMap<>();
            players.put("online", snap.playerCount);
            players.put("max", snap.maxPlayers);
            root.put("players", players);
        }

        // 实体
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            root.put("entities", unavailableSection());
        } else {
            Map<String, Object> entities = new LinkedHashMap<>();
            entities.put("total", snap.totalEntities);
            entities.put("by_world", snap.entitiesByWorld);
            entities.put("by_type", snap.entitiesByType);
            root.put("entities", entities);
        }

        // 区块
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)) {
            root.put("chunks", unavailableSection());
        } else {
            Map<String, Object> chunks = new LinkedHashMap<>();
            chunks.put("total_loaded", snap.totalLoadedChunks);
            chunks.put("total_force_loaded", snap.totalForceLoadedChunks);
            chunks.put("by_world", snap.chunksByWorld);
            root.put("chunks", chunks);
        }

        // 内存
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
            root.put("memory", unavailableSection());
        } else {
            Map<String, Object> memory = new LinkedHashMap<>();
            memory.put("heap_used", snap.heapUsed);
            memory.put("heap_max", snap.heapMax);
            memory.put("heap_total", snap.heapTotal);
            memory.put("heap_usage_percent", snap.heapUsagePercent);
            memory.put("non_heap_used", snap.nonHeapUsed);
            memory.put("non_heap_max", snap.nonHeapMax);
            root.put("memory", memory);
        }

        // CPU
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_CPU)) {
            root.put("cpu", unavailableSection());
        } else {
            Map<String, Object> cpu = new LinkedHashMap<>();
            cpu.put("process_load", safeDouble(snap.processCpuLoad));
            cpu.put("system_load", safeDouble(snap.systemCpuLoad));
            cpu.put("available_processors", snap.availableProcessors);
            root.put("cpu", cpu);
        }

        // GC
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_GC)) {
            root.put("gc", unavailableSection());
        } else {
            Map<String, Object> gc = new LinkedHashMap<>();
            gc.put("count", snap.gcCount);
            gc.put("time_ms", snap.gcTime);
            root.put("gc", gc);
        }

        // 服务器信息
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_SERVER)) {
            root.put("server", unavailableSection());
        } else {
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("uptime_seconds", snap.uptimeSeconds);
            server.put("thread_count", snap.threadCount);
            server.put("plugin_count", snap.loadedPluginCount);
            server.put("world_count", snap.worldCount);
            root.put("server", server);
        }

        // 告警
        Map<String, Object> alerts = new LinkedHashMap<>();
        alerts.put("critical", snap.activeCriticals);
        alerts.put("warnings", snap.activeWarnings);
        root.put("alerts", alerts);

        // 历史趋势 (TPS)
        List<Double> tpsHistory = new ArrayList<>(history.size());
        for (MetricsSnapshot h : history) {
            tpsHistory.add(safeDouble(h.tps1m));
        }
        root.put("tps_history", tpsHistory);

        return GSON.toJson(root) + "\n";
    }

    // ==================== 工具方法 ====================

    /**
     * P0-11：unavailable 模块的 JSON 段。
     */
    private static Map<String, Object> unavailableSection() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("status", "unavailable");
        return section;
    }

    /**
     * P0-11：Gson 默认拒绝 NaN/Infinity，非有限值统一转为 -1 哨兵。
     */
    private static double safeDouble(double v) {
        return Double.isFinite(v) ? v : -1;
    }

    /**
     * P0-11：HTML 最小转义（& < > " '），防世界名/告警文案破版与 XSS。
     */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * P0-13：CPU -1 哨兵渲染为"未知"。
     */
    private static String formatCpuPct(double v) {
        return v < 0 ? "未知" : String.format("%.1f%%", v);
    }

    private String htmlCard(String title, String value, String cssClass, String subtitle) {
        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card ").append(cssClass).append("\">\n");
        card.append("<div class=\"card-value\">").append(escapeHtml(value)).append("</div>\n");
        card.append("<div class=\"card-title\">").append(escapeHtml(title)).append("</div>\n");
        if (!subtitle.isEmpty()) {
            card.append("<div class=\"card-subtitle\">").append(escapeHtml(subtitle)).append("</div>\n");
        }
        card.append("</div>\n");
        return card.toString();
    }

    private String getTpsClass(double tps) {
        if (tps >= 19.5) return "good";
        if (tps >= 18) return "warn";
        return "critical";
    }

    private String getMsptClass(double mspt) {
        if (mspt <= 40) return "good";
        if (mspt <= 50) return "warn";
        return "critical";
    }

    private String getMemClass(int pct) {
        if (pct < 70) return "good";
        if (pct < 90) return "warn";
        return "critical";
    }

    private String getMemBarColor(int pct) {
        if (pct < 70) return "#69F0AE";
        if (pct < 90) return "#FFB300";
        return "#FF5252";
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

    private String loadHtmlCss() {
        return """
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                background: #1a1a2e;
                color: #eceff1;
                line-height: 1.6;
            }
            .header {
                background: linear-gradient(135deg, #00BCD4, #00838F);
                padding: 30px;
                text-align: center;
            }
            .header h1 { font-size: 2em; margin-bottom: 10px; }
            .header p { opacity: 0.9; }
            .container { max-width: 1200px; margin: 0 auto; padding: 20px; }
            .section {
                background: #16213e;
                border-radius: 8px;
                padding: 20px;
                margin-bottom: 20px;
            }
            .section h2 {
                color: #00BCD4;
                margin-bottom: 15px;
                font-size: 1.3em;
                border-bottom: 2px solid #00BCD4;
                padding-bottom: 8px;
            }
            .card-grid {
                display: grid;
                grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
                gap: 15px;
            }
            .card {
                background: #0f3460;
                border-radius: 8px;
                padding: 15px;
                border-left: 4px solid #00BCD4;
            }
            .card.good { border-left-color: #69F0AE; }
            .card.warn { border-left-color: #FFB300; }
            .card.critical { border-left-color: #FF5252; }
            .card.unknown { border-left-color: #607d8b; }
            .card.unknown .card-value { color: #90a4ae; }
            .card-value { font-size: 1.8em; font-weight: bold; color: #4DD0E1; }
            .card.good .card-value { color: #69F0AE; }
            .card.warn .card-value { color: #FFB300; }
            .card.critical .card-value { color: #FF5252; }
            .card-title { font-size: 0.85em; color: #90a4ae; margin-top: 5px; }
            .card-subtitle { font-size: 0.75em; color: #607d8b; margin-top: 3px; }
            table {
                width: 100%;
                border-collapse: collapse;
            }
            th, td {
                padding: 10px 12px;
                text-align: left;
                border-bottom: 1px solid #1a1a2e;
            }
            th { background: #0f3460; color: #00BCD4; }
            tr:hover { background: #1a2744; }
            .chart { margin: 15px 0; }
            .tps-graph {
                display: flex;
                align-items: flex-end;
                gap: 2px;
                height: 120px;
                padding: 5px;
                background: #0f3460;
                border-radius: 4px;
            }
            .bar {
                flex: 1;
                min-width: 4px;
                border-radius: 2px 2px 0 0;
                transition: height 0.3s;
            }
            .chart-labels {
                display: flex;
                justify-content: space-between;
                font-size: 0.75em;
                color: #607d8b;
                margin-top: 5px;
            }
            .mem-bar {
                width: 100%;
                height: 20px;
                background: #0f3460;
                border-radius: 10px;
                overflow: hidden;
                margin: 10px 0;
            }
            .mem-fill {
                height: 100%;
                border-radius: 10px;
                transition: width 0.5s;
            }
            .critical { color: #FF5252; margin: 5px 0; }
            .warning { color: #FFB300; margin: 5px 0; }
            .footer {
                text-align: center;
                padding: 20px;
                color: #607d8b;
                font-size: 0.85em;
            }
            """;
    }
}
