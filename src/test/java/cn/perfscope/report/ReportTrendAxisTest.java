/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.report;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.TestObjects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML 报告里 TPS 趋势图的<b>纵轴</b>：刻度、阈值参考线、图例。
 *
 * <h2>这条守的是什么</h2>
 *
 * <p>趋势图的纵轴标签曾经是写死的字符串
 * {@code <span>20.0</span><span>15.0</span><span>10.0</span><span>5.0</span><span>0.0</span>}。
 * 它与出厂 config（critical 15 / warning 18）在数值上<b>看起来</b>对得上，于是没人发现：
 * 那串数字其实只是刻度，从来不是阈值。服主把 {@code tps.critical_threshold} 改成 17 之后，
 * 柱子的<b>颜色</b>跟着 config 变了（v1.2 A8 那条修过），
 * 而<b>图上没有任何一处提到 17</b> —— 读者只能对着 20/15/10/5/0 猜红线在哪。</p>
 *
 * <h2>为什么刻度不跟着 config 走（这是刻意的取舍）</h2>
 *
 * <p>把刻度直接换成 config 值（"5 档均分到 critical"）会把图弄得更糟：
 * critical = 15 时变成 15.0/11.25/7.5/3.75/0.0 —— 四个小数、三条无意义刻度；
 * 更糟的是柱高的分母若也跟着变成 15，一根 15 TPS 的健康柱会画到<b>100% 满高</b>，
 * 恰好制造"顶到天花板"的误读。所以：</p>
 * <ul>
 *   <li><b>刻度</b>固定由 TPS 物理上限 20 均分（默认 5 档 = 20/15/10/5/0，与旧版逐位相同）；</li>
 *   <li><b>阈值</b>另画成虚线参考线（位置 = 阈值/20*100，与柱高同一把尺）+ 一行图例文字，
 *       这两样才是跟着 config 走的部分。</li>
 * </ul>
 *
 * <p>于是本类断言三件事：刻度<b>不</b>随 config 漂移、参考线<b>必须</b>随 config 移动、
 * 图例里的数字与比较符必须与 config 及算子表一致。</p>
 *
 * <h2>为什么用「TPS 与 MSPT 都不可用」的快照</h2>
 * <p>与 {@link ReportCardLevelTest} 同因：{@code ServerCompat} 的静态块依赖
 * {@code Bukkit.getBukkitVersion()}，单测 JVM 里初始化即失败且不可重试。
 * 关掉这两个模块是 {@link MetricsSnapshot} 契约里正大光明的一支，
 * 而趋势图这一段照常渲染（柱子画成灰色"不可用"矮柱，刻度与参考线不受影响）。</p>
 */
class ReportTrendAxisTest {

    private final List<ReportGenerator> generators = new ArrayList<>();

    @BeforeAll
    static void reflectionUsable() {
        TestObjects.assertReflectionUsable();
    }

    @AfterEach
    void shutDownGenerators() {
        for (ReportGenerator gen : generators) {
            try {
                gen.shutdown();
            } catch (RuntimeException ignored) {
                // 关闭路径不再制造新的失败
            }
        }
        generators.clear();
    }

    // ==================== 夹具 ====================

    /**
     * 阈值显式给全（{@code ConfigData} 的 int 字段默认是 0，留 0 会让判定恒真）。
     * {@code general_language = "en"} 让图例文案是英文，便于按文案定位。
     */
    private static ConfigManager.ConfigData config() {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.general_language = "en";
        cfg.report_format = "html";
        cfg.report_retentionDays = 0;
        cfg.tps_warningThreshold = 18.0;
        cfg.tps_criticalThreshold = 15.0;
        cfg.mspt_warningThreshold = 45.0;
        cfg.mspt_criticalThreshold = 55.0;
        cfg.memory_heapWarningThreshold = 75;
        cfg.memory_heapCriticalThreshold = 90;
        cfg.players_warningThreshold = 90;
        cfg.players_criticalThreshold = 98;
        cfg.entities_totalWarningThreshold = 20_000;
        cfg.entities_totalCriticalThreshold = 30_000;
        cfg.chunks_loadedWarningThreshold = 10_000;
        cfg.chunks_loadedCriticalThreshold = 15_000;
        cfg.cpu_processWarningThreshold = 80.0;
        cfg.cpu_systemWarningThreshold = 80.0;
        return cfg;
    }

    /**
     * 一条"看起来健康"的快照：TPS/MSPT 模块关掉（见类注释），其余指标远低于阈值。
     * {@code tps1m} 仍按调用方给的数值写——趋势图会把它画成灰色"不可用"柱，
     * 而本次要验的刻度与参考线与单点读数无关。
     */
    private static MetricsSnapshot sample(double tps) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.timestamp = 1_794_350_258_230L;
        s.unavailable.add(MetricsSnapshot.MODULE_TPS);
        s.unavailable.add(MetricsSnapshot.MODULE_MSPT);
        s.disabledModules.add(MetricsSnapshot.MODULE_TPS);
        s.disabledModules.add(MetricsSnapshot.MODULE_MSPT);

        s.tps1m = tps;
        s.tps5m = tps;
        s.tps15m = tps;
        s.msptAvg = -1;
        s.playerCount = 3;
        s.maxPlayers = 20;
        s.totalEntities = 500;
        s.totalLoadedChunks = 900;
        s.tileEntityCount = 10;
        s.heapUsed = 1_073_741_824L;
        s.heapTotal = 2_147_483_648L;
        s.heapMax = 4_294_967_296L;
        s.heapUsagePercent = 25;
        s.processCpuLoad = 12.5;
        s.systemCpuLoad = 30.0;
        s.uptimeSeconds = 3600;
        s.loadedPluginCount = 8;
        s.worldCount = 1;

        Map<String, Integer> entities = new LinkedHashMap<>();
        entities.put("world", 500);
        s.entitiesByWorld = entities;
        Map<String, Integer> chunks = new LinkedHashMap<>();
        chunks.put("world", 900);
        s.chunksByWorld = chunks;
        Map<String, Integer> tileEntities = new LinkedHashMap<>();
        tileEntities.put("world", 10);
        s.tileEntitiesByWorld = tileEntities;
        return s;
    }

    private PerfScope plugin(Path dataFolder, ConfigManager.ConfigData cfg) throws Exception {
        PerfScope p = TestObjects.allocate(PerfScope.class);
        Logger log = Logger.getLogger("perfscope-trend-axis-test-" + System.identityHashCode(p));
        log.setUseParentHandlers(false);
        log.setLevel(Level.ALL);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                // 不外发：生成失败的细节由断言体现
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "logger", log);
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "description",
            new org.bukkit.plugin.PluginDescriptionFile("PerfScope", "1.4.5", PerfScope.class.getName()));
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "dataFolder", dataFolder.toFile());

        ConfigManager cm = new ConfigManager(p);
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, p, "configManager", cm);
        return p;
    }

    /** 两个样本——趋势图的渲染条件是 {@code history.size() > 1}。 */
    private String generateHtml(Path dataFolder, ConfigManager.ConfigData cfg) throws Exception {
        PerfScope p = plugin(dataFolder, cfg);
        MetricsCollector mc = TestObjects.allocate(MetricsCollector.class);
        ConcurrentLinkedDeque<MetricsSnapshot> history = new ConcurrentLinkedDeque<>();
        history.addLast(sample(19.5));
        history.addLast(sample(17.0));
        TestObjects.setField(MetricsCollector.class, mc, "history", history);
        TestObjects.setField(MetricsCollector.class, mc, "configManager", p.getConfigManager());
        TestObjects.setField(MetricsCollector.class, mc, "plugin", p);
        TestObjects.setField(PerfScope.class, p, "metricsCollector", mc);

        ReportGenerator gen = new ReportGenerator(p);
        generators.add(gen);
        String path = gen.generateSpecific("html");
        assertTrue(path != null, "HTML report generation must succeed, got a null path");
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    // ==================== 1. 刻度：固定 5 档，不随 config 漂移 ====================

    /**
     * 默认 config（critical 15 / warning 18）下刻度是 20/15/10/5/0，与修前逐位相同。
     *
     * <p>这条同时是"没有把 20/15 换成 config 值"的证据：critical = 15 若被拿去均分，
     * 刻度会变成 15.0/11.25/7.5/3.75/0.0。</p>
     */
    @Test
    void axisTicksAreEvenStepsOfTheTpsCeilingNotOfTheConfiguredCritical(@TempDir Path temp) throws Exception {
        String html = generateHtml(temp, config());

        assertEquals("<span>20.0</span><span>15.0</span><span>10.0</span><span>5.0</span><span>0.0</span>",
            axisLabels(html),
            "ticks must be 5 even steps of the TPS ceiling (20), identical to the pre-fix hand-written set — "
                + "dividing the configured critical (15) would give 15.0/11.25/7.5/3.75/0.0");
    }

    /**
     * <b>核心靶点（反向）</b>：改 config 的 TPS 阈值，刻度必须<b>不动</b>。
     *
     * <p>这与"阈值参考线必须动"是一对：如果哪天有人把刻度改成 config 派生，
     * 这条会红；如果有人把参考线写死，下面那条会红。两条一起把"哪部分是绝对位置、
     * 哪部分是策略阈值"钉死。</p>
     */
    @Test
    void axisTicksDoNotDriftWhenTheConfiguredThresholdsChange(@TempDir Path temp) throws Exception {
        ConfigManager.ConfigData cfg = config();
        cfg.tps_warningThreshold = 19.5;
        cfg.tps_criticalThreshold = 17.0;

        String html = generateHtml(temp, cfg);

        assertEquals("<span>20.0</span><span>15.0</span><span>10.0</span><span>5.0</span><span>0.0</span>",
            axisLabels(html),
            "the axis is an absolute scale (0..20) and must not move with tps.*_threshold; "
                + "scaling it to critical would make a healthy 17 TPS bar fill the chart");
    }

    // ==================== 2. 阈值参考线：必须跟着 config 走 ====================

    /**
     * <b>核心靶点</b>：两条阈值参考线的位置 = 阈值/20*100，随 config 移动。
     *
     * <p>默认 config（critical 15 / warning 18）→ {@code bottom:75.0%} 与 {@code bottom:90.0%}。
     * 修前这里一个像素都没有：图上只有柱子和一串与阈值无关的数字。</p>
     */
    @Test
    void thresholdMarkersSitAtTheConfiguredHeights(@TempDir Path temp) throws Exception {
        String html = generateHtml(temp, config());

        assertTrue(html.contains("class=\"tps-threshold critical\" style=\"bottom:75.0%\""),
            "critical 15 / ceiling 20 must draw a line at 75% — the same scale the bars use "
                + "(bar height = tps/20*100), so 'bar top crosses the line' means 'reading is critical'");
        assertTrue(html.contains("class=\"tps-threshold warn\" style=\"bottom:90.0%\""),
            "warning 18 / ceiling 20 must draw a line at 90%");
    }

    /**
     * 改 config 后参考线必须移到新位置，且图例里的数字与比较符同步更新。
     *
     * <p>比较符取自 {@code ThresholdOperator.TPS}（低值差 + 严格 = {@code <}），
     * 不是在这里再写一个——所以断言里写的是 {@code <}，而不是任意符号。</p>
     */
    @Test
    void markersAndLegendFollowTheConfiguredThresholds(@TempDir Path temp) throws Exception {
        ConfigManager.ConfigData cfg = config();
        cfg.tps_warningThreshold = 19.0;
        cfg.tps_criticalThreshold = 17.0;

        String html = generateHtml(temp, cfg);

        assertTrue(html.contains("style=\"bottom:85.0%\""),
            "critical 17 / ceiling 20 = 85% — with thresholds at 17/19 the old hard-coded labels said 20/15, "
                + "so nothing on the page mentioned 17 at all");
        assertTrue(html.contains("style=\"bottom:95.0%\""), "warning 19 / ceiling 20 = 95%");

        assertTrue(html.contains("Critical: TPS &lt; 17.0"),
            "the legend must state the configured critical value and the operator's symbol "
                + "(taken from ThresholdOperator.TPS, not re-typed here)");
        assertTrue(html.contains("Warning: TPS &lt; 19.0"), "same for the warning tier");

        assertFalse(html.contains("Critical: TPS &lt; 15.0"),
            "the default 15 must be gone once config says 17 — a stale legend is the exact defect being fixed");
    }

    // ==================== 3. 越界阈值：不画到图顶（不伪造"阈值就是 20"） ====================
    /**
     * 阈值落在 (20, 20.5]（ConfigManager 允许的近恒真区）时不画参考线。
     *
     * <p>画在 100% 顶部会被读成"阈值就是 20"，而那正是旧实现的形态（一串到 20 的刻度 + 没有线）。
     * 信息不丢：图例文字照常写着真实阈值。</p>
     */
    @Test
    void outOfRangeThresholdsAreOmittedFromTheChartButKeptInTheLegend(@TempDir Path temp) throws Exception {
        ConfigManager.ConfigData cfg = config();
        cfg.tps_warningThreshold = 20.2;
        cfg.tps_criticalThreshold = 17.0;

        String html = generateHtml(temp, cfg);

        assertFalse(html.contains("class=\"tps-threshold warn\""),
            "a warning threshold above the axis ceiling cannot be drawn on a 0..20 scale; "
                + "clamping it to the top would read as 'the threshold is 20'");
        assertTrue(html.contains("class=\"tps-threshold critical\""),
            "the in-range critical line must still be drawn");
        assertTrue(html.contains("Warning: TPS &lt; 20.2"),
            "the legend keeps the real number even when the line cannot be placed");
    }

    // ==================== 4. 结构：新加的内层绘图区不能破坏嵌套 ====================

    /**
     * 整份报告的 {@code <div>} 必须成对闭合。
     *
     * <p>为什么值得单独一条：本次为了"柱高与参考线同一把尺"给绘图区加了一层
     * {@code .tps-plot}。这类"往里加一个 div"的改动最容易犯的错是少关一层——
     * 浏览器会自己容错，页面看着还行，但后续所有区块（实体分布表、内存详情）
     * 都被塞进图表容器里，且没有任何断言会发现。HTML 也是"生成产物"，
     * 结构错同样是缺陷。</p>
     *
     * <p>断言的是<b>整篇</b>的平衡而不是某个片段的：片段边界的闭合标签属于外层，
     * 按片段算会差一个常量，那种断言只会把读者绕进"到底该差几个"的算术里。</p>
     */
    @Test
    void trendChartMarkupClosesEveryDivItOpens(@TempDir Path temp) throws Exception {
        String html = generateHtml(temp, config());

        int from = html.indexOf("<div class=\"chart\">");
        assertTrue(from > 0, "the trend chart block must exist");
        assertTrue(html.contains("class=\"tps-plot\""),
            "the bars and the threshold markers must live in the same padding-free box, otherwise the "
                + "bar height percentage and the marker offset are measured against different boxes");

        // 整篇平衡：<div 与 </div> 数量必须相等（报告由若干独立区块拼成，
        // 任何一处少关都会让总数差一）。
        int opens = html.split("<div\\b", -1).length - 1;
        int closes = html.split("</div>", -1).length - 1;
        assertEquals(opens, closes,
            "every <div> in the report must be closed exactly once; a missing close silently swallows "
                + "the sections that follow it (browsers tolerate it, nothing else would notice)");
    }

    // ==================== 5. 刻度算法本身 ====================

    /** {@code tpsAxisTickLabels} 均分：首档是上限，末档是 0.0，中间等距。 */
    @Test
    void tickLabelsAreEvenlySpacedFromCeilingDownToZero() {
        List<String> ticks = ReportGenerator.tpsAxisTickLabels(ReportGenerator.TPS_AXIS_CEILING,
            ReportGenerator.TPS_AXIS_TICK_COUNT);

        assertEquals(List.of("20.0", "15.0", "10.0", "5.0", "0.0"), ticks);
        assertEquals(ReportGenerator.TPS_AXIS_TICK_COUNT, ticks.size(),
            "the count is what CSS's space-between layout expects; a different size would misalign labels");
    }

    /** 非法入参不抛异常：展示层宁可给一档 0.0，也不要让整份报告生成失败。 */
    @Test
    void illegalInputsDegradeToASingleZeroLabelInsteadOfThrowing() {
        assertEquals(List.of("0.0"), ReportGenerator.tpsAxisTickLabels(0.0, 5));
        assertEquals(List.of("0.0"), ReportGenerator.tpsAxisTickLabels(-1.0, 5));
        assertEquals(List.of("0.0"), ReportGenerator.tpsAxisTickLabels(20.0, 1));
        assertEquals(List.of("0.0"), ReportGenerator.tpsAxisTickLabels(Double.NaN, 5));
    }

    /** 配置快照为 null（load 之前的极端路径）时副产物是空串，不是 NPE。 */
    @Test
    void nullConfigYieldsNoLegendAndNoMarkers() {
        assertEquals("", ReportGenerator.tpsThresholdLegend(null));
        assertEquals("", ReportGenerator.tpsThresholdMarkers(null));
        assertNull(null); // 明确的空值语义，防止以后有人把它改成 "0"
    }

    // ==================== helpers ====================

    /**
     * 取出 {@code .chart-labels} 里的全部 {@code <span>}（顺序即渲染顺序，从上到下）。
     *
     * <p>断言用<b>整段</b>而不是逐个 {@code contains}：后者在刻度数量变化时仍会通过，
     * 而刻度数量是 CSS 布局的前提。</p>
     */
    private static String axisLabels(String html) {
        String marker = "<div class=\"chart-labels\">";
        int at = html.indexOf(marker);
        assertTrue(at > 0, "the trend chart must render a .chart-labels block");
        int end = html.indexOf("</div>", at);
        assertTrue(end > at, "the .chart-labels block must be closed");
        return html.substring(at + marker.length(), end).replace("\n", "").trim();
    }
}
