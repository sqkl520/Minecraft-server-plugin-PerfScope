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
 * 阈值边界的<b>早预警语义</b>：展示层闭区间、采集层严格比较，这里把这个设计钉住。
 *
 * <h2>这是既定设计，不是缺陷</h2>
 * {@code README.md} 第 39 行原文：「显示用闭区间（达到阈值即变色），部分指标告警用严格大于，
 * 因此<b>恰好等于阈值的那一瞬颜色会先变、告警随后到达</b>（有意的早预警语义）」。
 * {@code ThresholdColors} 类注释（约 33-51 行）与 {@code docs/07-guides/PERFSCOPE-TEST-PLAN.md}
 * 附录 A 第 9 项也写着同一件事。本类的作用是<b>第一次用测试把它钉住</b>——
 * 此前没有任何用例同时看两层，比较符被改坏也不会有测试变红。
 *
 * <h2>两层的实际口径</h2>
 *
 * <table border="1">
 *   <caption>边界值时的判定（{@code value} 恰好等于阈值）</caption>
 *   <tr><th>指标</th><th>采集层（{@code MetricsCollector}）</th><th>展示层（{@code ThresholdColors}）</th></tr>
 *   <tr><td>{@code entities}</td><td>{@code >}（开区间）→ 边界值不告警</td><td>{@code >=}（闭区间）→ 边界值变红</td></tr>
 *   <tr><td>{@code chunks}</td><td>{@code >}（开区间）</td><td>{@code >=}（闭区间）</td></tr>
 *   <tr><td>{@code mspt}</td><td>{@code >}（开区间）</td><td>{@code >=}（闭区间）</td></tr>
 *   <tr><td>{@code tps}</td><td>{@code <}（低值差，开区间）</td><td>{@code <=}（低值差，闭区间）</td></tr>
 *   <tr><td>{@code memory} / {@code players}</td><td>{@code >=}（闭区间）</td><td>{@code >=}（闭区间）</td></tr>
 * </table>
 *
 * <p><b>共 4 族</b>存在差异：{@code entities} / {@code chunks} / {@code mspt} / {@code tps}。
 * 注意展示层<b>不是</b>"一律 {@code >=}"——{@code tps} 是低值差指标，方向相反，用的是 {@code <=}。</p>
 *
 * <h2>边界值时的行为</h2>
 * 前三族只差一档：读数恰好等于阈值时界面已经标红（展示层闭区间），但告警不会发出
 * （采集层开区间）——看到颜色变化时告警还在路上。
 *
 * <p>{@code tps} 是<b>唯一差两档</b>的：{@code tps1m == 15.0} 时展示层判 {@code CRITICAL}（红），
 * 而采集层 {@code 15.0 < 15.0} 不成立、落进 {@code else if (tps1m < 18.0)} 分支 →
 * 发 {@code WARNING} 告警、{@code recordLevel = 1}（黄）。</p>
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
 * <p>所以本测试<b>不提修复建议，只把语义钉住</b>。若将来决定统一，这些用例会红，
 * 那正是有意识的决定需要更新测试的位置。</p>
 *
 * <h2>这一组为什么值得存在</h2>
 * 两个类分属不同包（{@code util} 与 {@code metrics}），各自有测试，但<b>没有任何测试同时看两边</b>。
 * 口径此前只写在注释与 README 里——注释不会在你改比较符时变红，测试会。
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
     * 提醒他早预警语义已经被改动（界面上"颜色先变"这一档就不存在了）。</p>
     */
    @Test
    void collectorUsesStrictGreaterThanForEntities() throws Exception {
        String src = sourceOf("src/main/java/cn/perfscope/metrics/MetricsCollector.java");
        boolean strict = src.contains("snap.totalEntities > cfg.entities_totalCriticalThreshold");
        boolean closed = src.contains("snap.totalEntities >= cfg.entities_totalCriticalThreshold");
        assertEquals(true, strict || closed,
            "找不到 entities 的 critical 判定——判定被改写或重组了，请更新本测试");
        assertEquals(true, strict,
            "采集层对 entities 用的是 `>`（开区间），展示层 ThresholdColors 用 `>=`（闭区间）——"
                + "这是 README 第 39 行明写的早预警语义：读数恰好等于 30000 时界面先标红、告警还在路上。"
                + "若你刚把它改成 `>=`，那是有意识的行为变更（早预警语义被取消）——请同时确认"
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
    // 3. 早预警语义的汇总断言（让"设计意图"本身成为一条可失败的用例）
    // ------------------------------------------------------------------------------------

    /**
     * <b>钉住差异清单</b>：本条覆盖高值差两族（{@code entities} / {@code mspt}）的口径差异。
     *
     * <p>作用是把"早预警语义"从注释里的一句话变成会变红的断言。它断言的是<b>当前设计下的差异集合</b>——
     * 若将来有人统一了口径，这里会红，让他必须来更新这份清单（而不是让语义悄悄消失或悄悄扩大）。</p>
     *
     * <p>另两族（{@code chunks} 同为高值差开区间、{@code tps} 为低值差且差两档）见类注释的对照表；
     * 本条不重复断言它们，断言内容保持不变。</p>
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
        // mspt 的判定已抽进纯函数 msptAlertLevel（见 MetricsCollector，v1.6 让哨兵口径可测）；
        // 探针跟着搬到新位置，语义仍是「开区间」——顺便把"抽函数时把 > 写成 >= "变成会红的事实。
        boolean msptStrict = src.contains("msptAvg > crit");

        // 展示层对高值差指标用闭区间（>=）；低值差指标 TPS 方向相反，用 <=。
        ThresholdColors c = colors();
        boolean displayEntitiesClosed =
            c.levelOf(ThresholdColors.Metric.ENTITIES, 30_000) == ThresholdColors.Level.CRITICAL;
        boolean displayMsptClosed =
            c.levelOf(ThresholdColors.Metric.MSPT, 50) == ThresholdColors.Level.CRITICAL;

        assertEquals(true, displayEntitiesClosed && displayMsptClosed,
            "展示层对高值差指标 entities 与 mspt 用闭区间（恰好到线就变红）");

        assertEquals("entities 开 / mspt 开",
            (entitiesStrict ? "entities 开" : "entities 闭")
                + " / " + (msptStrict ? "mspt 开" : "mspt 闭"),
            "早预警语义的差异清单变了。当前：entities 与 mspt 在采集层是开区间（>），"
                + "在展示层是闭区间（>=），所以边界值会出现「界面已标红但告警未发」——这是 README 第 39 行"
                + "明写的既定设计。若你刚统一了口径（取消了早预警），更新这份清单与"
                + " ThresholdBoundaryAsymmetryTest 的说明");
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
