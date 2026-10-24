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
package cn.perfscope.alert;

import cn.perfscope.PerfScope;
import cn.perfscope.compat.SchedulerAdapter;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.history.HistorySample;
import cn.perfscope.history.HistoryStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.management.ManagementFactory;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 每日摘要服务（Wave 7D 引入，9A-14 重做数据面与文案诚实度）。
 *
 * <p><b>9A-14 修复的四件事</b>：</p>
 * <ol>
 *   <li><b>时区显式化</b>：新增配置键 {@code alerts.daily-summary.timezone}（IANA 名如
 *       {@code Asia/Shanghai}；空 = 跟随系统）。此前所有日期/时间都用 JVM 默认时区，
 *       容器里常是 UTC，服主看到的"08:00"其实是北京时间 16:00；</li>
 *   <li><b>覆盖不足不再冒充"每日"</b>：历史环重启清零，旧文案顶着"每日摘要"标题却写
 *       "覆盖: 近 1 分钟"。现在覆盖不足时标题改为「自 HH:mm 重启以来（数据不足）」，
 *       并明确说明动作建议；</li>
 *   <li><b>补 p95 与超阈分钟数</b>：只看最低 TPS 会把"一次 30 秒的卡顿"和"整天在 15 TPS"
 *       看成同一件事。p95 反映"大多数时候有多差"，超阈分钟数反映"累计受苦时长"，
 *       两者都由手上样本现算（{@link HistoryStore#samples()}），不新增依赖；</li>
 *   <li><b>推送成败分开报</b>：{@code 推送 N 条（成功 M / 失败 K）}——WebhookNotifier 是
 *       fire-and-forget 的异步出口，最终结果不可观测，故单列"webhook 已排队 W 条"。</li>
 * </ol>
 *
 * <p><b>补发语义</b>：启动/启用/重载后首查时，若"今日配置时刻已过且今日未发" → 立即补发一次。</p>
 *
 * <p><b>每日去重</b>：键 = 目标时区下的本地日期，落盘到 {@code plugins/PerfScope/daily-summary.state}。</p>
 *
 * <p>线程模型：定时器在主线程执行（Folia 下为全局区域线程）；webhook 全异步。</p>
 */
public class DailySummaryService {

    private static final String PERMISSION_ALERT = "perfscope.alert";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** 9A-14：覆盖不足阈值——少于该分钟数一律标注"数据不足"（不再用"近 N 分钟"冒充每日）。 */
    private static final int MIN_COVERAGE_MINUTES = 60;

    private final PerfScope plugin;
    private final ConfigManager configManager;
    private final WebhookNotifier webhookNotifier;
    private final AlertService alertService;

    /** 周期检查任务句柄（null = 未运行） */
    private SchedulerAdapter.TaskHandle timerHandle;

    /**
     * 当日已发日期（null = 从未发过；reload 不清，跨天自动滚动）。
     * 9A-6 起落盘到 {@code plugins/PerfScope/daily-summary.state}。
     */
    private LocalDate lastSentDate = null;

    /** 9A-14：配置里的时区字符串（start()/reload 时刷新一次，不在每分钟节拍里读盘）。 */
    private volatile ZoneId cachedZone;

    public DailySummaryService(PerfScope plugin, WebhookNotifier webhookNotifier, AlertService alertService) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.webhookNotifier = webhookNotifier;
        this.alertService = alertService;
        this.lastSentDate = loadSentDate();   // 9A-6：跨重启恢复"今天已发过"的事实
        refreshZone();                        // 9A-14：启动即解析一次时区（坏配置在日志里点名）
    }

    // ==================== 9A-14：时区解析 ====================

    /**
     * 刷新目标时区：{@code alerts.daily-summary.timezone}（IANA 名），空/非法 → 系统时区。
     *
     * <p>该键需要 ConfigManager 增加字段 {@code alerts_dailySummaryTimezone}（String，默认 ""）；
     * 在字段落地前直接从 config.yml 读（{@link AlertService.RawConfig}）。
     * 读盘只发生在构造与 {@link #start()}（reload 会重新 start），每分钟节拍只读缓存。</p>
     */
    private void refreshZone() {
        String spec = "";
        try {
            spec = AlertService.RawConfig.load(plugin).getString("alerts.daily-summary.timezone", "");
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[告警] 时区配置读取失败（改用系统时区）: " + e.getMessage());
        }
        if (spec == null) {
            spec = "";
        }
        spec = spec.trim();
        if (spec.isEmpty()) {
            cachedZone = ZoneId.systemDefault();
            return;
        }
        try {
            cachedZone = ZoneId.of(spec);
        } catch (Exception e) {
            plugin.getLogger().warning("[告警] alerts.daily-summary.timezone=\"" + spec
                + "\" 无法识别（需 IANA 名如 Asia/Shanghai），已改用系统时区 "
                + ZoneId.systemDefault());
            cachedZone = ZoneId.systemDefault();
        }
    }

    /** 9A-14：当前目标时区（构造/启动时已解析好，此处零 IO）。 */
    private ZoneId zone() {
        ZoneId z = cachedZone;
        return z != null ? z : ZoneId.systemDefault();
    }

    // ==================== 已发状态持久化（9A-6） ====================

    /** 已发状态文件（一行 ISO 日期；缺失/损坏一律视为"从未发过"）。 */
    private java.io.File sentDateFile() {
        return new java.io.File(plugin.getDataFolder(), "daily-summary.state");
    }

    private LocalDate loadSentDate() {
        try {
            java.io.File f = sentDateFile();
            if (!f.isFile()) {
                return null;
            }
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) {
                return null;
            }
            return LocalDate.parse(s);
        } catch (Exception e) {
            // 不阻断启动：状态文件坏掉最多导致今天多收一份摘要
            plugin.getLogger().warning("[告警] 每日摘要已发状态读取失败（将视为今日未发）: " + e.getMessage());
            return null;
        }
    }

    private void persistSentDate(LocalDate date) {
        try {
            java.io.File f = sentDateFile();
            java.io.File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                plugin.getLogger().warning("[告警] 每日摘要状态目录创建失败，本次去重仅在内存生效");
                return;
            }
            java.nio.file.Files.write(f.toPath(),
                (date.toString() + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("[告警] 每日摘要已发状态写入失败（重启后可能重复推送）: " + e.getMessage());
        }
    }

    /**
     * 启动：每分钟检查一次（幂等，先停后启）。
     * enabled=false 时不启动（但保留 lastSentDate，重新启用后同日不重发）。
     */
    public void start() {
        stop();
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg == null || !cfg.alerts_dailySummaryEnabled) {
            plugin.getLogger().info("[告警] 每日摘要未启用（alerts.daily-summary.enabled=false）");
            return;
        }
        refreshZone(); // 9A-14：reload 后时区变更即时生效
        // 每分钟检查（1200 ticks = 60 秒）；首查延迟 5 秒（等采集器与历史环就绪）
        timerHandle = SchedulerAdapter.runGlobalTimer(plugin, this::checkCycle, 100L, 1200L);
        plugin.getLogger().info("[告警] 每日摘要已启用 (时刻: " + cfg.alerts_dailySummaryTime
            + ", 时区: " + zone() + distinctZoneNote() + ")");
    }

    /** 9A-14：时区与系统时区不一致时，启动日志里点名，省得服主对不上表。 */
    private String distinctZoneNote() {
        ZoneId z = zone();
        ZoneId sys = ZoneId.systemDefault();
        return z.equals(sys) ? "（系统时区）" : "（系统时区为 " + sys + "，二者不同）";
    }

    /** 停止：取消定时器（幂等）。不清 lastSentDate——reload 后同日不重发。 */
    public void stop() {
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
    }

    // ==================== 周期入口 ====================

    private void checkCycle() {
        try {
            ConfigManager.ConfigData cfg = configManager.get();
            if (cfg == null || !cfg.alerts_dailySummaryEnabled) {
                return;
            }
            int targetMinutes = ConfigManager.parseHhMmMinutes(cfg.alerts_dailySummaryTime);
            if (targetMinutes < 0) {
                return; // 配置校验已回退默认；此处防御
            }
            ZoneId z = zone();
            LocalDate today = LocalDate.now(z);       // 9A-14：按显式时区取日期
            if (today.equals(lastSentDate)) {
                return; // 今日已发
            }
            LocalTime nowTime = LocalTime.now(z);
            int nowMinutes = nowTime.getHour() * 60 + nowTime.getMinute();
            // 命中时刻 或 补发（今日时刻已过且今日未发）
            if (nowMinutes >= targetMinutes) {
                sendSummary(today, z);   // 内部会置 lastSentDate 并落盘（9A-6）
            }
        } catch (Exception | LinkageError e) {
            // 与告警服务同款：任务边界吞异常记日志，绝不让调度器因异常中断后续轮次
            plugin.getLogger().warning("[告警] 每日摘要本轮检查失败: " + e.getMessage());
        }
    }

    // ==================== 摘要组装与推送 ====================

    /**
     * 组装并推送摘要。
     *
     * <p><b>稳定锚点（harness 依赖，勿改）</b>：首行以 {@code [PerfScope] 每日摘要} 开头、
     * 控制台首行 {@code === 每日摘要 ===}、末行含 {@code 告警统计:}。</p>
     */
    private void sendSummary(LocalDate today, ZoneId zone) {
        HistoryStore store = plugin.getHistoryStore();
        ConfigManager.ConfigData cfg = configManager.get();
        long now = System.currentTimeMillis();
        long sinceMs = now - 24L * 60L * 60L * 1000L;

        List<HistorySample> window = store == null ? List.of() : store.since(sinceMs);
        HistorySample lowest = store == null ? null : store.lowestTpsSince(sinceMs);
        HistorySample peakEntities = store == null ? null : store.peakEntitiesSince(sinceMs);
        HistorySample peakHeap = store == null ? null : store.peakHeapSince(sinceMs);

        long coverageMs = 0L;
        if (window.size() >= 2) {
            coverageMs = window.get(window.size() - 1).timestamp() - window.get(0).timestamp();
        }
        int coverageMinutes = (int) Math.max(0, coverageMs / 60000L);

        // 9A-14：覆盖是否"够格"叫每日摘要——两个判据：绝对下限 60 分钟，或不足开机时长的 50%
        long bootMs = bootTimeMs();
        long sinceBootMs = bootMs > 0 ? Math.max(0, now - bootMs) : 0L;
        boolean thinCoverage = coverageMinutes < MIN_COVERAGE_MINUTES
            || (sinceBootMs > 2L * 60L * 60L * 1000L && coverageMs < sinceBootMs / 2);

        String title;
        String coverageText;
        if (thinCoverage) {
            title = "[PerfScope] 每日摘要 | 自 "
                + java.time.Instant.ofEpochMilli(bootMs > 0 ? bootMs : now).atZone(zone).toLocalTime().format(HH_MM)
                + " 重启以来（数据不足）";
            coverageText = "覆盖 " + coverageMinutes + " 分钟，不足 "
                + (sinceBootMs > 0 ? formatDuration(sinceBootMs) : "24 小时")
                + " 的 50%——趋势结论仅供参考，重启前的数据已丢失";
        } else {
            title = "[PerfScope] 每日摘要 | 覆盖: 近 " + coverageMinutes + " 分钟";
            coverageText = null;
        }

        // 9A-14：p95 / 超阈分钟数——用手上的样本现算（不依赖 HistoryStore 新 API）
        double p95 = percentile(window, 0.95);
        int overThresholdMinutes = overThresholdMinutes(window, cfg);

        String line1 = title;
        String line2 = "最低 TPS: " + (lowest == null ? "--" : formatTps(lowest.tps1m()) + "（" + formatTime(lowest.timestamp(), zone) + "）")
            + " | p95 TPS: " + (p95 < 0 ? "--" : formatTps(p95))
            + " | 低于 " + (cfg == null ? 18.0 : cfg.tps_warningThreshold) + " 的累计时长: " + overThresholdMinutes + " 分钟"
            + " | 峰值实体: " + (peakEntities == null ? "--" : String.valueOf(peakEntities.totalEntities()))
            + " | 峰值内存: " + (peakHeap == null ? "--" : peakHeap.heapUsagePercent() + "%");

        int pushedToday = alertService == null ? 0 : alertService.getPushedTodayCount();
        int active = alertService == null ? 0 : alertService.getActiveCount();
        int suspended = alertService == null ? 0 : alertService.getSuspendedCount();
        int ok = alertService == null ? 0 : alertService.getPushOkTodayCount();
        int fail = alertService == null ? 0 : alertService.getPushFailTodayCount();
        int queued = alertService == null ? 0 : alertService.getWebhookQueuedTodayCount();
        String line3 = "告警统计: 推送 " + pushedToday + " 条（成功 " + ok + " / 失败 " + fail + "）"
            + (queued > 0 ? "，webhook 已排队 " + queued + " 条（异步出口，送达结果不可观测）" : "")
            + " / 当前活跃 " + active + " 条"
            + (suspended > 0 ? " / unknown（模块不可用）" + suspended + " 条" : "");

        List<String> body = new ArrayList<>();
        body.add(line1);
        body.add(line2);
        body.add(line3);
        if (coverageText != null) {
            body.add("数据说明: " + coverageText);
        }

        // 控制台（=== 每日摘要 === 为 harness 锚点）
        plugin.getLogger().info("=== 每日摘要 ===");
        for (String line : body) {
            plugin.getLogger().info(line);
        }

        // 在线 OP（perfscope.alert）
        String joined = String.join("\n", body);
        Component component = Component.text(joined, NamedTextColor.GREEN);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(PERMISSION_ALERT)) {
                player.sendMessage(component);
            }
        }

        // webhook（全异步；能否真的送达本类无法得知，故摘要里单列"已排队"）
        if (webhookNotifier != null && webhookNotifier.isReady()) {
            webhookNotifier.send(joined);
        }

        lastSentDate = today;
        persistSentDate(today);   // 9A-6：发送成功后立即落盘，重启后同日不再重发
    }

    // ==================== 9A-14：统计辅助 ====================

    /** JVM 启动时刻（epoch ms）；取不到返回 0。 */
    private static long bootTimeMs() {
        try {
            return ManagementFactory.getRuntimeMXBean().getStartTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 窗口内 TPS 的 p95（线性插值，只统计 {@code tps1m > 0} 的有效样本）。
     * 无有效样本返回 -1，展示为 {@code --}。
     */
    private static double percentile(List<HistorySample> samples, double q) {
        List<Double> vals = new ArrayList<>();
        for (HistorySample s : samples) {
            if (s.tps1m() > 0) {
                vals.add(s.tps1m());
            }
        }
        if (vals.isEmpty()) {
            return -1;
        }
        vals.sort(Double::compareTo);
        if (vals.size() == 1) {
            return vals.get(0);
        }
        double pos = q * (vals.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return vals.get(lo);
        }
        double frac = pos - lo;
        return vals.get(lo) * (1 - frac) + vals.get(hi) * frac;
    }

    /**
     * TPS 低于 {@code tps.warning_threshold} 的累计分钟数。
     *
     * <p>按相邻样本的时间差加权（而不是"样本数 × 名义间隔"）——历史环的节流间隔会随 reload
     * 变化，按真实间隔算才不会凭空放大或缩水。最后一个样本按中位间隔补齐。</p>
     */
    private static int overThresholdMinutes(List<HistorySample> samples, ConfigManager.ConfigData cfg) {
        if (cfg == null || samples.size() < 2) {
            return 0;
        }
        double threshold = cfg.tps_warningThreshold;
        long totalMs = 0L;
        long medianGap = 0L;
        int gaps = 0;
        for (int i = 0; i + 1 < samples.size(); i++) {
            long gap = samples.get(i + 1).timestamp() - samples.get(i).timestamp();
            if (gap <= 0 || gap > 10L * 60L * 1000L) {
                continue; // 异常间隔（时钟回拨/长停机）不计入
            }
            medianGap += gap;
            gaps++;
            double tps = samples.get(i).tps1m();
            if (tps > 0 && tps < threshold) {
                totalMs += gap;
            }
        }
        if (gaps == 0) {
            return 0;
        }
        long per = medianGap / gaps;
        HistorySample last = samples.get(samples.size() - 1);
        if (last.tps1m() > 0 && last.tps1m() < threshold) {
            totalMs += per;
        }
        return (int) Math.max(0, Math.round(totalMs / 60000.0));
    }

    private static String formatTps(double tps) {
        return String.format(Locale.ROOT, "%.2f", tps);
    }

    private static String formatTime(long epochMs, ZoneId zone) {
        return java.time.Instant.ofEpochMilli(epochMs)
            .atZone(zone)
            .toLocalTime()
            .format(HH_MM);
    }

    /** 9A-14：时长文案（与 AlertService 同口径，独立实现避免跨类耦合）。 */
    private static String formatDuration(long ms) {
        long s = Math.max(0, ms / 1000L);
        if (s < 60) {
            return s + " 秒";
        }
        long m = s / 60;
        if (m < 60) {
            return m + " 分钟";
        }
        long h = m / 60;
        long rm = m % 60;
        return rm == 0 ? h + " 小时" : h + " 小时 " + rm + " 分";
    }
}
