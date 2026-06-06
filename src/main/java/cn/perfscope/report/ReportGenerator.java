package cn.perfscope.report;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 性能报告生成器 - 支持 HTML / TXT / JSON 三种格式
 * 自动管理报告清理
 */
public class ReportGenerator {

    private final PerfScope plugin;
    private final MetricsCollector collector;
    private final ConfigManager configManager;
    private final File reportDir;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

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

        // 先采集一次最新数据
        collector.collect();
        snap = collector.getLatest();
        if (snap == null) return null;

        List<MetricsSnapshot> history = collector.getHistory();

        String timestamp = dateFormat.format(new Date());
        String filename = "perfscope-" + timestamp;
        File file;

        switch (format.toLowerCase()) {
            case "html":
                file = new File(reportDir, filename + ".html");
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(generateHtmlReport(snap, history));
                } catch (IOException e) {
                    plugin.getLogger().warning("生成HTML报告失败: " + e.getMessage());
                    return null;
                }
                break;
            case "json":
                file = new File(reportDir, filename + ".json");
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(generateJsonReport(snap, history));
                } catch (IOException e) {
                    plugin.getLogger().warning("生成JSON报告失败: " + e.getMessage());
                    return null;
                }
                break;
            case "txt": case "text":
            default:
                file = new File(reportDir, filename + ".txt");
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(generateTextReport(snap, history));
                } catch (IOException e) {
                    plugin.getLogger().warning("生成TXT报告失败: " + e.getMessage());
                    return null;
                }
                break;
        }

        // 清理旧报告
        cleanOldReports();

        return file.getAbsolutePath();
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
        html.append("  <p>生成时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("</p>\n");
        html.append("  <p>服务器运行时间: ").append(formatUptime(snap.uptimeSeconds)).append("</p>\n");
        html.append("</div>\n");

        html.append("<div class=\"container\">\n");

        // 概览卡片
        html.append("<div class=\"section\">\n");
        html.append("<h2>性能概览</h2>\n");
        html.append("<div class=\"card-grid\">\n");

        html.append(htmlCard("TPS (1分钟)", String.format("%.2f", snap.tps1m), getTpsClass(snap.tps1m),
            "5分钟: " + String.format("%.2f", snap.tps5m) + " | 15分钟: " + String.format("%.2f", snap.tps15m)));
        html.append(htmlCard("MSPT (平均)", String.format("%.2f ms", snap.msptAvg), getMsptClass(snap.msptAvg),
            "最小: " + String.format("%.2f", snap.msptMin) + "ms | 最大: " + String.format("%.2f", snap.msptMax) + "ms"));
        html.append(htmlCard("在线玩家", snap.playerCount + " / " + snap.maxPlayers, "normal",
            ""));
        html.append(htmlCard("实体总数", String.valueOf(snap.totalEntities), "normal", ""));
        html.append(htmlCard("加载区块", String.valueOf(snap.totalLoadedChunks), "normal",
            "强加载: " + snap.totalForceLoadedChunks));
        html.append(htmlCard("堆内存", formatBytes(snap.heapUsed) + " / " + formatBytes(snap.heapMax),
            getMemClass(snap.heapUsagePercent), "使用率: " + snap.heapUsagePercent + "%"));
        html.append(htmlCard("进程CPU", String.format("%.1f%%", snap.processCpuLoad), "normal",
            "系统CPU: " + String.format("%.1f%%", snap.systemCpuLoad)));
        html.append(htmlCard("已加载世界", String.valueOf(snap.worldCount), "normal",
            "已加载插件: " + snap.loadedPluginCount));

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
            html.append("<tr><td>").append(world).append("</td><td>").append(entities)
                .append("</td><td>").append(chunks).append("</td><td>").append(tileEntities)
                .append("</td></tr>\n");
        }
        html.append("</table></div>\n");

        // 实体类型 Top 15
        html.append("<div class=\"section\">\n");
        html.append("<h2>实体类型分布 (Top 15)</h2>\n");
        html.append("<table><tr><th>实体类型</th><th>数量</th><th>占比</th></tr>\n");
        snap.entitiesByType.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(15)
            .forEach(entry -> {
                double pct = snap.totalEntities > 0 ?
                    (double) entry.getValue() / snap.totalEntities * 100 : 0;
                html.append("<tr><td>").append(entry.getKey()).append("</td><td>")
                    .append(entry.getValue()).append("</td><td>")
                    .append(String.format("%.1f%%", pct)).append("</td></tr>\n");
            });
        html.append("</table></div>\n");

        // 内存详情
        html.append("<div class=\"section\">\n");
        html.append("<h2>内存详情</h2>\n");
        html.append("<div class=\"mem-bar\">\n");
        html.append("<div class=\"mem-fill\" style=\"width:").append(snap.heapUsagePercent)
            .append("%;background:").append(getMemBarColor(snap.heapUsagePercent)).append("\"></div>\n");
        html.append("</div>\n");
        html.append("<p>堆内存: ").append(formatBytes(snap.heapUsed)).append(" / ")
            .append(formatBytes(snap.heapMax)).append(" (").append(snap.heapUsagePercent).append("%)</p>\n");
        html.append("<p>非堆内存: ").append(formatBytes(snap.nonHeapUsed));
        if (snap.nonHeapMax > 0) html.append(" / ").append(formatBytes(snap.nonHeapMax));
        html.append("</p>\n");
        html.append("<p>GC次数: ").append(snap.gcCount).append(" | GC耗时: ").append(snap.gcTime).append("ms</p>\n");
        html.append("</div>\n");

        // 告警
        if (!snap.activeCriticals.isEmpty() || !snap.activeWarnings.isEmpty()) {
            html.append("<div class=\"section\">\n");
            html.append("<h2>活跃告警</h2>\n");
            for (String critical : snap.activeCriticals) {
                html.append("<p class=\"critical\">[!!!] ").append(critical).append("</p>\n");
            }
            for (String warning : snap.activeWarnings) {
                html.append("<p class=\"warning\">[!] ").append(warning).append("</p>\n");
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
        sb.append("生成时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n");
        sb.append("运行时间: ").append(formatUptime(snap.uptimeSeconds)).append("\n\n");

        sb.append("--- 性能概览 ---\n");
        sb.append(String.format("TPS (1m/5m/15m): %.2f / %.2f / %.2f\n", snap.tps1m, snap.tps5m, snap.tps15m));
        sb.append(String.format("MSPT (avg/min/max): %.2f / %.2f / %.2f ms\n", snap.msptAvg, snap.msptMin, snap.msptMax));
        sb.append(String.format("在线玩家: %d / %d\n", snap.playerCount, snap.maxPlayers));
        sb.append(String.format("实体总数: %d\n", snap.totalEntities));
        sb.append(String.format("加载区块: %d (强加载: %d)\n", snap.totalLoadedChunks, snap.totalForceLoadedChunks));
        sb.append(String.format("堆内存: %s / %s (%d%%)\n", formatBytes(snap.heapUsed),
            formatBytes(snap.heapMax), snap.heapUsagePercent));
        sb.append(String.format("进程CPU: %.1f%% | 系统CPU: %.1f%%\n", snap.processCpuLoad, snap.systemCpuLoad));
        sb.append(String.format("GC: %d次 / %dms\n", snap.gcCount, snap.gcTime));
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
        snap.entitiesByType.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(entry -> {
                double pct = snap.totalEntities > 0 ?
                    (double) entry.getValue() / snap.totalEntities * 100 : 0;
                sb.append(String.format("  %s: %d (%.1f%%)\n", entry.getKey(), entry.getValue(), pct));
            });
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

    private String generateJsonReport(MetricsSnapshot snap, List<MetricsSnapshot> history) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"generated_at\": \"").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\",\n");
        json.append("  \"plugin_version\": \"").append(plugin.getDescription().getVersion()).append("\",\n");

        // TPS
        json.append("  \"tps\": {\n");
        json.append("    \"1m\": ").append(snap.tps1m).append(",\n");
        json.append("    \"5m\": ").append(snap.tps5m).append(",\n");
        json.append("    \"15m\": ").append(snap.tps15m).append("\n");
        json.append("  },\n");

        // MSPT
        json.append("  \"mspt\": {\n");
        json.append("    \"avg\": ").append(snap.msptAvg).append(",\n");
        json.append("    \"min\": ").append(snap.msptMin).append(",\n");
        json.append("    \"max\": ").append(snap.msptMax).append("\n");
        json.append("  },\n");

        // 玩家
        json.append("  \"players\": {\n");
        json.append("    \"online\": ").append(snap.playerCount).append(",\n");
        json.append("    \"max\": ").append(snap.maxPlayers).append("\n");
        json.append("  },\n");

        // 实体
        json.append("  \"entities\": {\n");
        json.append("    \"total\": ").append(snap.totalEntities).append(",\n");
        json.append("    \"by_world\": ").append(jsonMap(snap.entitiesByWorld)).append(",\n");
        json.append("    \"by_type\": ").append(jsonMap(snap.entitiesByType)).append("\n");
        json.append("  },\n");

        // 区块
        json.append("  \"chunks\": {\n");
        json.append("    \"total_loaded\": ").append(snap.totalLoadedChunks).append(",\n");
        json.append("    \"total_force_loaded\": ").append(snap.totalForceLoadedChunks).append(",\n");
        json.append("    \"by_world\": ").append(jsonMap(snap.chunksByWorld)).append("\n");
        json.append("  },\n");

        // 内存
        json.append("  \"memory\": {\n");
        json.append("    \"heap_used\": ").append(snap.heapUsed).append(",\n");
        json.append("    \"heap_max\": ").append(snap.heapMax).append(",\n");
        json.append("    \"heap_total\": ").append(snap.heapTotal).append(",\n");
        json.append("    \"heap_usage_percent\": ").append(snap.heapUsagePercent).append(",\n");
        json.append("    \"non_heap_used\": ").append(snap.nonHeapUsed).append(",\n");
        json.append("    \"non_heap_max\": ").append(snap.nonHeapMax).append("\n");
        json.append("  },\n");

        // CPU
        json.append("  \"cpu\": {\n");
        json.append("    \"process_load\": ").append(snap.processCpuLoad).append(",\n");
        json.append("    \"system_load\": ").append(snap.systemCpuLoad).append(",\n");
        json.append("    \"available_processors\": ").append(snap.availableProcessors).append("\n");
        json.append("  },\n");

        // GC
        json.append("  \"gc\": {\n");
        json.append("    \"count\": ").append(snap.gcCount).append(",\n");
        json.append("    \"time_ms\": ").append(snap.gcTime).append("\n");
        json.append("  },\n");

        // 服务器信息
        json.append("  \"server\": {\n");
        json.append("    \"uptime_seconds\": ").append(snap.uptimeSeconds).append(",\n");
        json.append("    \"thread_count\": ").append(snap.threadCount).append(",\n");
        json.append("    \"plugin_count\": ").append(snap.loadedPluginCount).append(",\n");
        json.append("    \"world_count\": ").append(snap.worldCount).append("\n");
        json.append("  },\n");

        // 告警
        json.append("  \"alerts\": {\n");
        json.append("    \"critical\": ").append(jsonArray(snap.activeCriticals)).append(",\n");
        json.append("    \"warnings\": ").append(jsonArray(snap.activeWarnings)).append("\n");
        json.append("  },\n");

        // 历史趋势 (TPS)
        json.append("  \"tps_history\": [");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) json.append(", ");
            json.append(String.format("%.2f", history.get(i).tps1m));
        }
        json.append("]\n");

        json.append("}\n");
        return json.toString();
    }

    // ==================== 工具方法 ====================

    private String jsonMap(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (!first) sb.append(", ");
            sb.append("\"").append(entry.getKey()).append("\": ").append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String jsonArray(List<String> list) {
        return "[" + list.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")) + "]";
    }

    private String htmlCard(String title, String value, String cssClass, String subtitle) {
        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card ").append(cssClass).append("\">\n");
        card.append("<div class=\"card-value\">").append(value).append("</div>\n");
        card.append("<div class=\"card-title\">").append(title).append("</div>\n");
        if (!subtitle.isEmpty()) {
            card.append("<div class=\"card-subtitle\">").append(subtitle).append("</div>\n");
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
