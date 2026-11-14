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
import cn.perfscope.util.DurationFormat;
import cn.perfscope.util.Messages;
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

    /**
     * f-s01-logic-03：历史环可提供的覆盖上界（与 {@code HistoryStore.WINDOW_MS} 同值，24 小时）。
     * 摘要的"应有多少数据"分母必须用它封顶——历史环结构上不可能提供超过 24h 的样本，
     * 用开机时长做分母会让开机 >48h 的服务器永远被判为"覆盖不足"。
     */
    private static final long HISTORY_RETENTION_MS = 24L * 60L * 60L * 1000L;

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
            plugin.getLogger().warning(logText("daily.log.zone-read-failed", e.getMessage()));
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
            plugin.getLogger().warning(logText("daily.log.zone-bad", spec, ZoneId.systemDefault()));
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
            plugin.getLogger().warning(logText("daily.log.sent-date-read-failed", e.getMessage()));
            return null;
        }
    }

    private void persistSentDate(LocalDate date) {
        try {
            java.io.File f = sentDateFile();
            java.io.File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                plugin.getLogger().warning(logText("daily.log.state-dir-failed"));
                return;
            }
            // f-s01-data-03：与 alerts.state 同口径——临时文件 + 原子替换。
            // 原实现原地截断写：进程被强杀或磁盘写满时文件会停在半截 ISO 日期串，
            // loadSentDate 解析失败 → 视为"今天没发过" → 当日重复推送一份摘要。
            java.io.File tmp = new java.io.File(f.getParentFile(), "daily-summary.state.tmp");
            java.nio.file.Files.write(tmp.toPath(),
                (date.toString() + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(logText("daily.log.sent-date-write-failed", e.getMessage()));
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
            plugin.getLogger().info(logText("daily.log.disabled"));
            return;
        }
        refreshZone(); // 9A-14：reload 后时区变更即时生效
        // 每分钟检查（1200 ticks = 60 秒）；首查延迟 5 秒（等采集器与历史环就绪）
        timerHandle = SchedulerAdapter.runGlobalTimer(plugin, this::checkCycle, 100L, 1200L);
        if (!timerHandle.isActive()) {
            // f-s01-err-02：与 AlertService.start 同构——noop = 任务没装上，不得再打印"已启用"
            //（否则每日摘要静默永不送达）。v1.3.0 -14：文案改走键表。
            plugin.getLogger().severe(logText("daily.log.start-failed-no-scheduler"));
            return;
        }
        plugin.getLogger().info(logText("daily.log.enabled",
            cfg.alerts_dailySummaryTime, zone(), distinctZoneNote()));
    }

    /** 9A-14：时区与系统时区不一致时，启动日志里点名，省得服主对不上表。 */
    private String distinctZoneNote() {
        ZoneId z = zone();
        ZoneId sys = ZoneId.systemDefault();
        return z.equals(sys)
            ? Messages.get("daily.zone.system")
            : Messages.get("daily.zone.differs", sys);
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
            plugin.getLogger().warning(logText("daily.log.cycle-failed", e.getMessage()));
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
        // v1.4：新增 5 指标。三者各自处理"没数据"，口径见 MetricWindow 的注释。
        HistorySample peakSpike = store == null ? null : store.peakMsptSpikeSince(sinceMs);
        HistorySample peakProcessCpu = store == null ? null : store.peakProcessCpuSince(sinceMs);
        HistorySample peakSystemCpu = store == null ? null : store.peakSystemCpuSince(sinceMs);
        HistoryStore.GcDelta gc = store == null
            ? HistoryStore.GcDelta.NONE : store.gcDeltaSince(sinceMs);

        long coverageMs = 0L;
        if (window.size() >= 2) {
            coverageMs = window.get(window.size() - 1).timestamp() - window.get(0).timestamp();
        }
        int coverageMinutes = (int) Math.max(0, coverageMs / 60000L);

        // 9A-14：覆盖是否"够格"叫每日摘要——两个判据：绝对下限 60 分钟，或不足开机时长的 50%
        long bootMs = bootTimeMs();
        long sinceBootMs = bootMs > 0 ? Math.max(0, now - bootMs) : 0L;
        // f-s01-logic-03：分母必须封顶在历史环的保留窗口。裸用开机时长时，开机 > 48h 的服务器
        // 恒满足 coverageMs < sinceBootMs/2（coverageMs 结构上不可能超过 24h），
        // thinCoverage 因此永真，daily.title.full "完整覆盖"分支不可达。
        long expectedMs = sinceBootMs > 0 ? Math.min(sinceBootMs, HISTORY_RETENTION_MS) : HISTORY_RETENTION_MS;
        // f-s01-logic-07：两个原因分开存。合并成一个布尔量后文案只能断言"不足 50%"，
        // 而在只触发 60 分钟下限时（例如刚重启 30 分钟、覆盖 25 分钟）该断言与自身数据矛盾。
        boolean belowFloor = coverageMinutes < MIN_COVERAGE_MINUTES;
        boolean belowRelative = sinceBootMs > 2L * 60L * 60L * 1000L && coverageMs < expectedMs / 2;
        boolean thinCoverage = belowFloor || belowRelative;

        String title;
        String coverageText;
        if (thinCoverage) {
            title = Messages.get("daily.title.thin",
                java.time.Instant.ofEpochMilli(bootMs > 0 ? bootMs : now).atZone(zone).toLocalTime().format(HH_MM));
            String expectedText = sinceBootMs > 0
                ? DurationFormat.duration(expectedMs) : Messages.get("daily.note.24h");
            coverageText = belowRelative
                ? Messages.get("daily.note.thin", coverageMinutes, expectedText)
                : Messages.get("daily.note.floor", coverageMinutes, MIN_COVERAGE_MINUTES);
        } else {
            title = Messages.get("daily.title.full", coverageMinutes);
            coverageText = null;
        }

        // 9A-14：p95 / 超阈分钟数——用手上的样本现算（不依赖 HistoryStore 新 API）
        double p95 = percentile(window, 0.95);
        int overThresholdMinutes = overThresholdMinutes(window, cfg);

        String lowestText = lowest == null
            ? "--"
            : Messages.get("daily.metrics.lowest-at", formatTps(lowest.tps1m()),
                formatTime(lowest.timestamp(), zone));

        String line1 = title;
        String line2 = Messages.get("daily.metrics",
            lowestText,
            p95 < 0 ? "--" : formatTps(p95),
            cfg == null ? 18.0 : cfg.tps_warningThreshold,
            overThresholdMinutes,
            peakEntities == null ? "--" : String.valueOf(peakEntities.totalEntities()),
            peakHeap == null ? "--" : peakHeap.heapUsagePercent() + "%");

        int pushedToday = alertService == null ? 0 : alertService.getPushedTodayCount();
        int active = alertService == null ? 0 : alertService.getActiveCount();
        int suspended = alertService == null ? 0 : alertService.getSuspendedCount();
        int ok = alertService == null ? 0 : alertService.getPushOkTodayCount();
        int fail = alertService == null ? 0 : alertService.getPushFailTodayCount();
        int queued = alertService == null ? 0 : alertService.getWebhookQueuedTodayCount();
        StringBuilder alertStats = new StringBuilder(Messages.get("daily.alerts.base", pushedToday, ok, fail));
        if (queued > 0) {
            alertStats.append(Messages.get("daily.alerts.webhook", queued));
        }
        alertStats.append(Messages.get("daily.alerts.active", active));
        if (suspended > 0) {
            alertStats.append(Messages.get("daily.alerts.suspended", suspended));
        }
        String line3 = alertStats.toString();

        List<String> body = new ArrayList<>();
        body.add(line1);
        body.add(line2);
        // v1.4：新增 5 指标压成两行（尖峰一行、CPU+GC 一行）。必须插在告警统计行之前——
        // 「末行含 告警统计:」是 harness 依赖的稳定锚点，挪到后面会让所有既有断言失效。
        body.add(spikeLine(peakSpike, zone));
        body.add(cpuGcLine(peakProcessCpu, peakSystemCpu, gc));
        body.add(line3);
        if (coverageText != null) {
            body.add(Messages.get("daily.note.label", coverageText));
        }

        // 控制台（=== 每日摘要 === 为 harness 锚点；两种语言各自保留自己的三连等号结构）
        plugin.getLogger().info(Messages.get("daily.console-header"));
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
        // f-s01-data-04：游标语义是「尝试过」而非「送达过」——投递路径（控制台/OP/webhook）都是
        // 条件性的且 webhook 异步不可观测，故无条件落盘；当日不再重试，同日重启也不补发。
        // 控制台通道永不空转，故实际送达概率很高。
        persistSentDate(today);
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
     * 窗口内 TPS 的 p95（线性插值，只统计有效样本）。
     * 无有效样本返回 -1，展示为 {@code --}。
     *
     * <p><b>为什么不过滤 {@code 0}</b>：{@code tps1m} 没有 -1 哨兵——采集层直接写
     * {@code Bukkit.getTPS()}，而 TPS 不可用时的快照整条不入历史环（{@code HistoryStore}
     * 的污染防护）。所以环里的 {@code 0} 只可能是"服务器真的冻结了"，是最坏的真实读数。
     * 把它滤掉会让 p95 偏高，日报看起来比实际健康。</p>
     */
    private static double percentile(List<HistorySample> samples, double q) {
        List<Double> vals = new ArrayList<>();
        for (HistorySample s : samples) {
            if (s.tps1m() >= 0) {
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
            // 0 是真实读数（见 percentile 的注释），它当然低于任何告警线，必须计入超阈时长
            if (tps >= 0 && tps < threshold) {
                totalMs += gap;
            }
        }
        if (gaps == 0) {
            return 0;
        }
        long per = medianGap / gaps;
        HistorySample last = samples.get(samples.size() - 1);
        if (last.tps1m() >= 0 && last.tps1m() < threshold) {
            totalMs += per;
        }
        return (int) Math.max(0, Math.round(totalMs / 60000.0));
    }

    // ==================== v1.4：新增 5 指标的渲染 ====================
    //
    // 三个渲染方法刻意是包级可见（而非 private）：它们是无副作用的纯函数，"未采集 / 样本不足 /
    // 真的是 0" 的三态区分是本批最容易被改坏的地方，必须能被同包测试直接驱动。
    // 走反射（本仓库其它测试的既有手法）也能测，但那会让"三态是否真的分开"这件事
    // 在重构时静默失效——测试编译不过才是我们要的信号。

    /**
     * MSPT 尖峰行。
     *
     * <p><b>-1 哨兵不显示成数值</b>：{@code -1 ms} 会被读成"有史以来最快的一次 tick 是负数"，
     * 比不显示更糟。整个窗口都没有尖峰值时显式写"本窗口未采集"——它与"没有尖峰"是两件事，
     * 前者是盲区（旧 CSV / avg-only 路径 / TPS 反推），后者是健康的结论。
     * 两件事不能共用一句话，否则盲区会被读成健康。</p>
     */
    static String spikeLine(HistorySample peakSpike, ZoneId zone) {
        String value = peakSpike == null
            ? Messages.get("daily.metrics.not-collected")
            : Messages.get("daily.metrics.spike-at",
                String.format(Locale.ROOT, "%.2f", peakSpike.msptMax()),
                formatTime(peakSpike.timestamp(), zone));
        return Messages.get("daily.metrics.spike", value);
    }

    /**
     * CPU + GC 行。
     *
     * <p><b>两个 CPU 分开放"未采集"判断</b>：进程 CPU 与系统 CPU 是两个独立指标，
     * 一边有一边无（例如 Windows 上 {@code getProcessCpuLoad} 被跳过）时不能用一个共同结论
     * 覆盖两边——那会把有数据的那一边也标成盲区。</p>
     *
     * <p><b>GC 报增量而不是最大值</b>：{@code gcCount}/{@code gcTime} 是进程内累计计数器，
     * 报最大值等于报"开机以来一共 GC 了多少次"，与"本窗口"无关。增量由
     * {@link HistoryStore.GcDelta} 逐对累加，跨重启不会出现负值。</p>
     */
    static String cpuGcLine(HistorySample peakProcessCpu, HistorySample peakSystemCpu,
                            HistoryStore.GcDelta gc) {
        String process = peakProcessCpu == null
            ? Messages.get("daily.metrics.not-collected")
            : String.format(Locale.ROOT, "%.1f%%", peakProcessCpu.cpuProcess());
        String system = peakSystemCpu == null
            ? Messages.get("daily.metrics.not-collected")
            : String.format(Locale.ROOT, "%.1f%%", peakSystemCpu.cpuSystem());
        return Messages.get("daily.metrics.cpu", process, system)
            + " | " + gcText(gc);
    }

    /**
     * GC 增量的三种态，逐条区分（任何一种都不能退化成"0 次"）：
     * <ol>
     *   <li><b>窗口内没有任何带 GC 数据的样本</b> → "本窗口未采集"（旧 CSV / GC 模块被禁用）；</li>
     *   <li><b>有样本但凑不出可比的相邻对</b> → "样本不足"（只有 1 条，或相邻对全是重启边界）；</li>
     *   <li><b>可测且增量为 0</b> → "本窗口未发生 GC"（这是真的健康结论，不是盲区）。</li>
     * </ol>
     * 窗口内发生过 JVM 重启时追加一句说明：增量按相邻对累加，重启前那段仍计入——
     * 服主据此知道"这个数字没有被重启吞掉"。
     */
    static String gcText(HistoryStore.GcDelta gc) {
        if (gc == null || gc.validSamples() == 0) {
            // 用 gc-state（"GC: {0}"）而不是数值模板：数值模板的量词是给数字用的，
            // 套在整句上会渲染成 "GC: not collected in this window time(s), --s" 这种病句
            return Messages.get("daily.metrics.gc-state",
                Messages.get("daily.metrics.not-collected"));
        }
        if (!gc.measured()) {
            // 样本在，但凑不出可比的一对：既不是"未采集"，也不是"没发生 GC"
            return Messages.get("daily.metrics.gc-state",
                Messages.get("daily.metrics.gc-insufficient"));
        }
        if (gc.countDelta() == 0L && gc.timeDeltaMs() == 0L) {
            return Messages.get("daily.metrics.no-gc");
        }
        String text = Messages.get("daily.metrics.gc",
            String.valueOf(gc.countDelta()),
            String.format(Locale.ROOT, "%.1f", gc.timeDeltaMs() / 1000.0));
        if (gc.resets() > 0) {
            text += Messages.get("daily.metrics.gc-reset", gc.resets());
        }
        return text;
    }

    private static String formatTps(double tps) {
        return String.format(Locale.ROOT, "%.2f", tps);
    }

    /** 日志：内容 + 统一的 {@code [告警] } / {@code [Alert] } 前缀（与其他告警层日志同口径）。 */
    private static String logText(String key, Object... args) {
        return Messages.get("alert.log.prefix") + Messages.get(key, args);
    }

    private static String formatTime(long epochMs, ZoneId zone) {
        return java.time.Instant.ofEpochMilli(epochMs)
            .atZone(zone)
            .toLocalTime()
            .format(HH_MM);
    }
}
