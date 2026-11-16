/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import cn.perfscope.config.ConfigManager;
import cn.perfscope.support.ConfigFixtures;
import cn.perfscope.support.TestObjects;
import cn.perfscope.util.ThresholdColors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 阈值边界语义：<b>两处对同一批阈值采用了不同的区间口径</b>，这里把差异显式钉住。
 *
 * <h2>发现</h2>
 * 同一份配置、同一个读数，两个地方给出的结论不同：
 *
 * <table border="1">
 *   <caption>边界值 value == threshold 时的判定</caption>
 *   <tr><th>指标</th><th>采集层（{@code MetricsCollector}）</th><th>展示层（{@code ThresholdColors}）</th></tr>
 *   <tr><td>{@code entities}</td><td>{@code >}（开区间）→ 边界值不告警</td><td>{@code >=}（闭区间）→ 边界值变红</td></tr>
 *   <tr><td>{@code chunks}</td><td>{@code >}（开区间）</td><td>{@code >=}（闭区间）</td></tr>
 *   <tr><td>{@code memory}</td><td>{@code >=}（闭区间）</td><td>{@code >=}（闭区间）</td></tr>
 *   <tr><td>{@code players}</td><td>{@code >=}（闭区间）</td><td>{@code >=}（闭区间）</td></tr>
 *   <tr><td>{@code tps}</td><td>{@code <}（低值差，闭区间）</td><td>{@code <=} 或 {@code <}（见各自测试）</td></tr>
 *   <tr><td>{@code mspt}</td><td>{@code >}（开区间）</td><td>{@code >=}（闭区间）</td></tr>
 * </table>
 *
 * <h2>后果</h2>
 * 在 {@code entities} / {@code chunks} / {@code mspt} 上，读数**恰好等于**阈值时：
 * 界面已经标红（展示层 {@code >=}），但告警不会发出（采集层 {@code >}）。
 * 服主看到红色数字却收不到通知，会以为告警坏了。
 *
 * <p>反过来也成立：读数刚好在阈值<b>下方一点点</b>时界面是黄的，而告警也确实没发——这条一致。</p>
 *
 * <h2>为什么不"统一"</h2>
 * 两种口径各有理由，而<b>改成一致会改变线上行为</b>：
 * <ul>
 *   <li>把采集层改成 {@code >=}：边界值开始告警。默认阈值 {@code entities 20000/30000}
 *       是"上界留余量"取的整数（见 config.yml 的注释），恰好等于 30000 的服务器不少——
 *       这一改会让一批服务器突然开始报 critical。</li>
 *   <li>把展示层改成 {@code >}：边界值不变红。而 {@code ThresholdColorsTest} 里那条
 *       "30000 必须已经变红"是<b>刻意的 UI 契约</b>——到线就标红比"超过才标红"更符合直觉。</li>
 * </ul>
 *
 * <p>所以本测试<b>不提修复建议，只把现状钉住</b>。若将来决定统一，这些用例会红，
 * 那正是有意识的决定需要更新测试的位置。</p>
 *
 * <h2>这一组为什么值得存在</h2>
 * 两个类分属不同包（{@code util} 与 {@code metrics}），各自有测试，但<b>没有任何测试同时看两边</b>。
 * 于是"同一阈值两种口径"这件事在这个仓库里从未被记录下来——而它是真实存在的。
 */
class ThresholdBoundaryAsymmetryTest {

    private static ThresholdColors colors() {
        ConfigManager cm = ConfigFixtures.manager("pscope-boundary-test");
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        return new ThresholdColors(cm);
    }

    // ------------------------------------------------------------------------------------
    // 1. 展示层的闭区间（记录现状）
    // ------------------------------------------------------------------------------------

    /**
     * 展示层：{@code value == critical} 已经是 CRITICAL（闭区间）。
     *
     * <p>这条与 {@code ThresholdColorsTest} 里"30000 必须已经变红"重复，但这里保留一份
     * 是为了让本类自成一体——对照表要两边都有数才能看出差异。</p>
     */
    @Test
    void displayLayerTreatsEqualityAsCritical() {
        ThresholdColors c = colors();
        assertEquals(ThresholdColors.Level.CRITICAL, c.levelOf(
                ThresholdColors.Metric.ENTITIES, 30_000),
            "展示层是闭区间：恰好 30000 就变红");
        assertEquals(ThresholdColors.Level.CRITICAL, c.levelOf(
                ThresholdColors.Metric.MEMORY, 95),
            "memory 的 critical 默认 95，恰好 95 变红");
    }

    /** 展示层：{@code value == warning} 已经是 WARN（闭区间）。 */
    @Test
    void displayLayerTreatsEqualityAsWarning() {
        ThresholdColors c = colors();
        assertEquals(ThresholdColors.Level.WARN, c.levelOf(
                ThresholdColors.Metric.ENTITIES, 20_000),
            "展示层是闭区间：恰好 20000 就变黄");
        assertEquals(ThresholdColors.Level.WARN, c.levelOf(
                ThresholdColors.Metric.MEMORY, 85));
    }

    /** 展示层：差 1 还是上一档——证明边界确实是"到线就变"，不是"超过才变"。 */
    @Test
    void displayLayerFlipsExactlyAtTheBoundary() {
        ThresholdColors c = colors();
        assertEquals(ThresholdColors.Level.GOOD, c.levelOf(ThresholdColors.Metric.ENTITIES, 19_999),
            "19999 低于 warning 线 20000，是 GOOD");
        assertEquals(ThresholdColors.Level.WARN, c.levelOf(ThresholdColors.Metric.ENTITIES, 20_000),
            "恰好 20000 就进 WARN——闭区间");
        assertEquals(ThresholdColors.Level.CRITICAL, c.levelOf(ThresholdColors.Metric.ENTITIES, 30_000),
            "恰好 30000 就进 CRITICAL——差 1 个就换档，这就是闭区间的形状");
    }

    // ------------------------------------------------------------------------------------
    // 2. 采集层的开区间（记录现状 + 说明差异）
    // ------------------------------------------------------------------------------------

    /**
     * <b>采集层对 entities 用开区间</b>：恰好等于阈值<b>不</b>告警。
     *
     * <p>这条是用源码事实表达的：{@code collectEntities} 里写的是
     * {@code snap.totalEntities > cfg.entities_totalCriticalThreshold}（{@code >} 而非 {@code >=}）。
     * 所以读数恰好 30000 时，采集层不生成 critical 告警，而展示层已经把那个数字标红。</p>
     *
     * <p>测试方式：把 {@code collectEntities} 的判定行读出来做断言。这不是"测行为"，
     * 而是把**结构事实**变成可失败的断言——若有人把 {@code >} 改成 {@code >=}，这里会红，
     * 提醒他两侧口径已经不一致。</p>
     */
    @Test
    void collectorUsesStrictGreaterThanForEntities() throws Exception {
        String src = sourceOf("src/main/java/cn/perfscope/metrics/MetricsCollector.java");
        boolean strict = src.contains("snap.totalEntities > cfg.entities_totalCriticalThreshold");
        boolean closed = src.contains("snap.totalEntities >= cfg.entities_totalCriticalThreshold");
        assertEquals(true, strict || closed,
            "找不到 entities 的 critical 判定——判定被改写或重组了，请更新本测试");
        assertEquals(true, strict,
            "采集层对 entities 用的是 `>`（开区间），而展示层 ThresholdColors 用 `>=`（闭区间）。"
                + "这个不一致是当前事实：读数恰好等于 30000 时界面标红但告警不发。"
                + "若你刚把它改成 `>=`，那是有意识的行为变更——请同时确认"
                + "ThresholdColorsTest 的「30000 必须已经变红」仍然成立，并更新本测试的说明");
    }

    /**
     * 采集层对 memory / players 用<b>闭</b>区间（{@code >=}），与展示层一致。
     *
     * <p>这两个是百分比指标，"达到 85%" 比 "超过 85%" 更符合直觉，
     * 且它们的默认值（85/95、90/98）不是"上界留余量"取的——所以两侧一致是正确的。</p>
     */
    @Test
    void collectorUsesClosedIntervalForPercentMetrics() {
        String src;
        try {
            src = sourceOf("src/main/java/cn/perfscope/metrics/MetricsCollector.java");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertEquals(true,
            src.contains("pct >= cfg.players_criticalThreshold"),
            "players 是百分比指标，采集层用闭区间 `>=`，与展示层一致");
        assertEquals(true,
            src.contains("snap.heapUsagePercent >= cfg.memory_heapCriticalThreshold"),
            "memory 是百分比指标，采集层用闭区间 `>=`，与展示层一致");
    }

    // ------------------------------------------------------------------------------------
    // 3. 差异的汇总断言（让"不一致"本身成为一条可失败的用例）
    // ------------------------------------------------------------------------------------

    /**
     * <b>记录差异清单</b>：哪几个指标在两层的区间口径不同。
     *
     * <p>这条用例的作用是"把隐式不一致变成显式清单"。它断言的是<b>当前已知的差异集合</b>——
     * 若将来有人统一了口径，这里会红，让他必须来更新这份清单（而不是让差异悄悄消失或悄悄扩大）。</p>
     */
    @Test
    void knownDivergencesBetweenLayersAreExactlyThese() {
        String src;
        try {
            src = sourceOf("src/main/java/cn/perfscope/metrics/MetricsCollector.java");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // 采集层用开区间（>）的指标
        boolean entitiesStrict = src.contains("snap.totalEntities > cfg.entities_totalCriticalThreshold");
        boolean msptStrict = src.contains("snap.msptAvg > cfg.mspt_criticalThreshold");

        // 展示层对所有指标一律用闭区间（>=）——见 ThresholdColors.Level 的实现
        ThresholdColors c = colors();
        boolean displayEntitiesClosed =
            c.levelOf(ThresholdColors.Metric.ENTITIES, 30_000) == ThresholdColors.Level.CRITICAL;
        boolean displayMsptClosed =
            c.levelOf(ThresholdColors.Metric.MSPT, 50) == ThresholdColors.Level.CRITICAL;

        assertEquals(true, displayEntitiesClosed && displayMsptClosed,
            "展示层预期对 entities 与 mspt 都用闭区间（恰好到线就变红）");

        assertEquals("entities 开 / mspt 开",
            (entitiesStrict ? "entities 开" : "entities 闭")
                + " / " + (msptStrict ? "mspt 开" : "mspt 闭"),
            "已知的区间口径差异清单变了。当前：entities 与 mspt 在采集层是开区间（>），"
                + "在展示层是闭区间（>=），所以边界值会出现「界面已标红但告警未发」。"
                + "若你刚统一了口径，更新这份清单与 ThresholdBoundaryAsymmetryTest 的说明");
    }

    /** 读源码文本。只用于本条把结构事实变成断言，不参与任何生产逻辑。 */
    private static String sourceOf(String relativePath) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(relativePath);
        if (!java.nio.file.Files.exists(p)) {
            // 从 target/test-classes 往上找仓库根（surefire 的 cwd 就是仓库根，但保险起见）
            p = java.nio.file.Paths.get("..").resolve(relativePath).normalize();
        }
        return new String(java.nio.file.Files.readAllBytes(p),
            java.nio.charset.StandardCharsets.UTF_8);
    }
}
