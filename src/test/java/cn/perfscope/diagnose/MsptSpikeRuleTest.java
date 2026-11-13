/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.diagnose;

import cn.perfscope.alert.AlertServiceStateMachineFixture;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.util.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断规则<b>本体</b>：{@link CoreRules.MsptSpikeRule}（DIAG-MSPT-SPIKE）。
 *
 * <p><b>为什么能这么测</b>：这条规则只读 {@code snap.msptAvg} / {@code snap.msptMax}，
 * 既不碰 {@code HistoryStore} 也不碰时钟——是四条纯函数规则里最纯的一条。因此本类
 * <b>不需要</b> {@code Unsafe}、不需要反射写私有环、也不需要 {@code ConfigManager} 实例
 * （{@code ConfigData} 是无参可 new 的普通对象，且本规则根本不读它）。</p>
 *
 * <p>与 {@code DiagnosticEngineContractTest} 的分工同 {@code MemoryLeakRuleTest}：那边测引擎闸门，
 * 任何规则体换成 {@code return Evaluation.clean()} 都不会红；这边直接驱动规则体，
 * 让"把判据写反/阈值改错"必然编译期之外的运行期变红。</p>
 *
 * <h2>阈值语义（实现与字段注释的差别，边界用例据此写）</h2>
 *
 * <p>字段注释写的是 {@code max > avg×3} 与 {@code max > 100ms}，但实现里的早退条件是
 * {@code msptMax < MIN_SPIKE_MS || msptMax < msptAvg * RATIO}——即命中条件是
 * <b>{@code max >= 100} 且 {@code max >= avg×3}</b>（等号算命中）。两个边界用例
 * （{@code max == 100.0}、{@code max == avg×3.0}）锁的就是这个"等号归命中"的现行口径，
 * 免得日后有人照着注释把 {@code <} 改成 {@code <=} 而悄悄改变分界。</p>
 */
class MsptSpikeRuleTest {

    @BeforeAll
    static void initMessages() throws Exception {
        AlertServiceStateMachineFixture.initMessages();
    }

    // ==================== 装配 ====================

    /**
     * 造一份快照。
     *
     * <p>{@link MetricsSnapshot} 的构造器把 {@code msptAvg/msptMin/msptMax} 预置为 -1 哨兵
     * （P0-13），所以<b>两个字段都必须显式写</b>——只写 max 会让规则先在 avg 那关 skip 掉，
     * 用例会以"跳过了"结束而不是走到要测的判据上。</p>
     */
    private static MetricsSnapshot snapshot(double avg, double max) {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.msptAvg = avg;
        snap.msptMax = max;
        return snap;
    }

    private static Evaluation evaluate(MetricsSnapshot snap) {
        // history 传 null 是刻意的：该规则不读历史，传 null 才能证明这一点
        return new CoreRules.MsptSpikeRule().evaluate(snap, null, new ConfigManager.ConfigData());
    }

    /** 预期 skip 文案 = 消息层对同一键渲染出的文本（不能拿键名字面量去比，见 MemoryLeakRuleTest）。 */
    private static String reason(String key, Object... args) {
        return Messages.get(key, args);
    }

    // ==================== 三态之"无法评估" ====================

    /** MSPT 模块不可用 → skip，且原因点名模块不可用（不能把"没数据"说成"没尖刺"）。 */
    @Test
    void unavailableMsptModuleSkips() {
        MetricsSnapshot snap = snapshot(20, 150);
        snap.unavailable.add(MetricsSnapshot.MODULE_MSPT);

        Evaluation ev = evaluate(snap);

        assertNull(ev.hit());
        assertFalse(ev.isClean(), "a rule whose precondition failed did NOT evaluate — it must skip");
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.mspt-unavailable"), ev.skip().reason());
        assertEquals("DIAG-MSPT-SPIKE", ev.skip().ruleId());
    }

    /** avg 也是 -1 哨兵（整条采集链都没取到）→ 必须报"平均值未知"，而不是"没有极值"。 */
    @Test
    void unknownAverageSkips() {
        Evaluation ev = evaluate(snapshot(-1, -1));

        assertNull(ev.hit());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.mspt-avg-unknown"), ev.skip().reason());
    }

    /**
     * {@code msptAvg == 0} 与 {@code msptAvg == -1} 必须同属"平均值未知"。
     *
     * <p>这一条单独写，是因为把守卫从 {@code <= 0} 改成 {@code < 0} 时它会掉进下一条守卫、
     * 报出"没有极值"——文案错了，服主会以为是采集路径的问题而不是没采到。</p>
     */
    @Test
    void zeroAverageIsAlsoUnknownNotMissingExtremes() {
        Evaluation ev = evaluate(snapshot(0, -1));

        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.mspt-avg-unknown"), ev.skip().reason());
    }

    /** avg 有效但极值是 -1 哨兵（avg-only / TPS 反推路径）→ skip，且原因<b>不同于</b>上一条。 */
    @Test
    void missingExtremesSkipsWithItsOwnReason() {
        Evaluation ev = evaluate(snapshot(12.5, -1));

        assertNull(ev.hit());
        assertFalse(ev.isClean(), "an avg-only sample cannot prove there was no spike");
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.mspt-no-extremes"), ev.skip().reason());
        assertFalse(ev.skip().reason().equals(reason("diag.skip.mspt-avg-unknown")),
            "the two skip causes must stay distinguishable to the reader");
    }

    /**
     * {@code msptMax == 0} 同样是哨兵，不是"这一拍耗时 0 毫秒"。
     *
     * <p>真实 tick 耗时恒 &gt; 0；0 只可能来自未采集的默认值。守卫若是 {@code < 0}，
     * 这条会静默变成 clean，于是"没采到"被说成"没有尖刺"。</p>
     */
    @Test
    void zeroMaxIsTreatedAsMissingNotAsARealReading() {
        Evaluation ev = evaluate(snapshot(12.5, 0));

        assertNull(ev.hit());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.mspt-no-extremes"), ev.skip().reason());
    }

    // ==================== 反向：评估了但干净 ====================

    /**
     * 只满足"低于均值 3 倍"这一个条件：{@code max=150, avg=60} → 150 &lt; 180，
     * 是持续偏高而不是尖刺 → clean（不是 skip：规则确实评估过）。
     */
    @Test
    void highAverageWithProportionalMaxIsClean() {
        Evaluation ev = evaluate(snapshot(60, 150));

        assertNull(ev.hit(), "150ms is only 2.5x the 60ms average — that is load, not a spike");
        assertTrue(ev.isClean(), "it was evaluated and found fine — clean, not skip");
        assertNull(ev.skip());
    }

    /** 只满足"是均值的 3 倍以上"这一个条件：{@code max=90, avg=20} → 90 &lt; 100 下限，玩家无感 → clean。 */
    @Test
    void spikeBelowTheAbsoluteFloorIsClean() {
        Evaluation ev = evaluate(snapshot(20, 90));

        assertNull(ev.hit(), "a 90ms peak is below the 100ms player-noticeable floor");
        assertTrue(ev.isClean());
    }

    // ==================== 正向：必须命中 ====================

    /** max 与 avg 同时过线 → 必须给出结论，并带上可核对的数值。 */
    @Test
    void bothConditionsMetProducesADiagnosis() {
        Evaluation ev = evaluate(snapshot(50, 150));

        Diagnosis d = ev.hit();
        assertNotNull(d, "a 150ms peak on a 50ms average is a single-tick freeze and must be reported");
        assertEquals("DIAG-MSPT-SPIKE", d.ruleId());
        assertFalse(d.title().isEmpty(), "the diagnosis must carry a title");
        assertFalse(d.suggestion().isEmpty(), "the diagnosis must carry an actionable suggestion");
        assertFalse(d.critical(), "150ms is a hitch, not a 1s+ freeze — warning level");
    }

    /**
     * detail 必须由实现真的把 max / avg / 倍数填进模板（而不是一句套话）。
     *
     * <p>用消息层渲染同一模板做<b>全等</b>比较：占位符错位（比如 max 与 avg 互换）会直接红，
     * 而这样断言又与语言无关。</p>
     */
    @Test
    void detailCarriesMaxAverageAndRatio() {
        Diagnosis d = evaluate(snapshot(50, 150)).hit();
        assertNotNull(d);

        String expected = Messages.get("diag.DIAG-MSPT-SPIKE.detail",
            150.0, 50.0, String.format(java.util.Locale.ROOT, "%.1f", 150.0 / 50.0));
        assertEquals(expected, d.detail(),
            "detail must render {max, avg, ratio} in that order");
        assertTrue(d.detail().contains("3.0"), "the ratio 150/50 must appear as 3.0");
    }

    // ==================== 边界 ====================

    /** 边界：{@code max == 100.0} 恰好等于下限 → 命中（实现是 {@code <}，不是 {@code <=}）。 */
    @Test
    void maxExactlyAtTheFloorHits() {
        Evaluation ev = evaluate(snapshot(20, 100.0));

        assertNotNull(ev.hit(), "100ms is the inclusive floor: a peak of exactly 100ms still counts");
    }

    /** 边界：{@code max == avg × 3.0} 恰好等于倍数线 → 命中（等号归命中）。 */
    @Test
    void maxExactlyThreeTimesAverageHits() {
        Evaluation ev = evaluate(snapshot(40, 120));

        assertNotNull(ev.hit(), "exactly 3x the average meets the ratio bar (>=, not >)");
    }

    /** 边界：刚好差一点点就不命中，防止把 {@code >=} 写成 {@code >} 以外方向的松动。 */
    @Test
    void justBelowBothBarsIsClean() {
        // max=99.9 差下限 0.1；max=119.9 差倍数线 0.1（avg=40 → 3x=120）
        assertTrue(evaluate(snapshot(20, 99.9)).isClean(), "99.9ms is below the 100ms floor");
        assertTrue(evaluate(snapshot(40, 119.9)).isClean(), "119.9ms is below 3 x 40ms");
    }

    // ==================== critical 分级 ====================

    /** {@code max > 1000} → critical。 */
    @Test
    void peakAboveOneSecondIsCritical() {
        Diagnosis d = evaluate(snapshot(100, 1500)).hit();

        assertNotNull(d);
        assertTrue(d.critical(), "a 1.5s tick is a visible freeze — it must be critical");
    }

    /**
     * 边界：{@code max == 1000.0} 恰好 → <b>不</b> critical（实现是 {@code >}，严格大于）。
     *
     * <p>这条同时杀掉"把 {@code >} 写成 {@code >=}"的变异体。</p>
     */
    @Test
    void peakExactlyOneSecondIsNotCritical() {
        Diagnosis d = evaluate(snapshot(100, 1000.0)).hit();

        assertNotNull(d);
        assertFalse(d.critical(), "exactly 1000ms stays at warning level (the bar is strictly >1000)");
    }

    // ==================== F-6：诊断层与告警层不得互相否定 ====================

    /**
     * <b>F-6（回归）</b>：{@code msptAvg = 60}（采集层已按 {@code mspt.critical_threshold=50}
     * 判为 critical 告警）、{@code msptMax = 200} 时，诊断结论不得是"只是一次尖刺、不严重"。
     *
     * <p>旧实现只看 {@code msptMax > 1000}，于是同一屏上告警区写着"critical"、
     * 诊断区却把这条排到命中列表末尾（warning 级），两个结论互相否定。</p>
     *
     * <p>本规则的定位是"尖刺"，所以判据是"均值是否也已越线"——均值越线就不再是孤立尖刺，
     * 而是持续的压力。这样既不改变告警层语义（那边仍按 avg 判），也让同屏结论一致。</p>
     */
    @Test
    void spikeOnAnAlreadyCriticalAverageIsCritical() {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.mspt_warningThreshold = 45.0;
        cfg.mspt_criticalThreshold = 50.0;

        Evaluation ev = new CoreRules.MsptSpikeRule().evaluate(snapshot(60, 200), null, cfg);

        Diagnosis d = ev.hit();
        assertNotNull(d, "a 200ms peak on a 60ms average is still a spike worth reporting");
        assertTrue(d.critical(),
            "F-6: the collection layer already raised a critical MSPT alert for avg=60 > 50; the "
                + "diagnostic must not contradict it by calling the same snapshot a mere warning");
    }

    /** 反向：均值在告警线下、峰值也未过一秒 → 仍是 warning，不得因为这条修复而升级。 */
    @Test
    void spikeOnAHealthyAverageStaysAWarning() {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.mspt_warningThreshold = 45.0;
        cfg.mspt_criticalThreshold = 50.0;

        Diagnosis d = new CoreRules.MsptSpikeRule().evaluate(snapshot(20, 150), null, cfg).hit();

        assertNotNull(d);
        assertFalse(d.critical(), "a 150ms peak on a 20ms average is a hitch, not a freeze");
    }

    /**
     * 边界：均值<b>恰好</b>等于 critical 阈值（50.0）→ critical。
     *
     * <p>采集层用的是 {@code msptAvg > cfg.mspt_criticalThreshold}（严格大于），
     * 诊断层若也用 {@code >} 就完全同口径。这里锁的是"等号归哪边"必须与采集层一致：
     * 采集层 50.0 不告警，诊断层 50.0 也不得升严重。</p>
     */
    @Test
    void averageExactlyAtTheCriticalThresholdDoesNotEscalate() {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.mspt_warningThreshold = 45.0;
        cfg.mspt_criticalThreshold = 50.0;

        Diagnosis d = new CoreRules.MsptSpikeRule().evaluate(snapshot(50.0, 200), null, cfg).hit();

        assertNotNull(d);
        assertFalse(d.critical(),
            "the collection layer uses a strict '>' against mspt.critical_threshold; at exactly 50.0 "
                + "it raises only a warning, so the diagnostic must not label it critical either");
    }

    /**
     * 严重线的三套口径必须同源：硬编码的一秒冻结（1000ms）<b>仍然</b>有效。
     *
     * <p>修复不得把"一秒冻结"这条绝对判据换掉——均值 10ms 但有一拍 1000ms+ 的服务器
     * 体验是"偶尔卡死一下"，与均值越线是两种不同的病。</p>
     */
    @Test
    void oneSecondFreezeStaysCriticalEvenWhenTheAverageIsHealthy() {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.mspt_warningThreshold = 45.0;
        cfg.mspt_criticalThreshold = 50.0;

        Diagnosis d = new CoreRules.MsptSpikeRule().evaluate(snapshot(10, 1200), null, cfg).hit();

        assertNotNull(d);
        assertTrue(d.critical(), "a 1.2s tick is a visible freeze whatever the average says");
    }

    /**
     * detail 必须把"尖刺"与"均值负载"两种严重度分开表述。
     *
     * <p>同一个数字（峰值）在两种场景下读法完全不同：均值健康时它是"偶发卡顿"，
     * 均值越线时它是"持续压力下的毛刺"。detail 里必须能看到均值与阈值的对照，
     * 否则服主无法判断这条结论与告警区的关系。</p>
     */
    @Test
    void detailDistinguishesASpikeFromSustainedPressure() {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.mspt_warningThreshold = 45.0;
        cfg.mspt_criticalThreshold = 50.0;

        Diagnosis spikeOnly = new CoreRules.MsptSpikeRule().evaluate(snapshot(20, 150), null, cfg).hit();
        Diagnosis pressure = new CoreRules.MsptSpikeRule().evaluate(snapshot(60, 200), null, cfg).hit();
        assertNotNull(spikeOnly);
        assertNotNull(pressure);

        assertEquals(150.0, 150.0);
        assertFalse(spikeOnly.detail().equals(pressure.detail()),
            "the two conclusions carry different severity, so their evidence text must differ");
        assertTrue(pressure.detail().contains("60"),
            "the critical detail must show the average that crossed the critical line, got: "
                + pressure.detail());
        assertTrue(pressure.detail().contains("50"),
            "the critical detail must show the critical threshold it is compared against, got: "
                + pressure.detail());
    }

    // ==================== 纯函数契约 ====================

    /** 规则只读快照：同一份输入跑两遍，结论与证据必须逐字一致。 */
    @Test
    void evaluationIsPureAndRepeatable() {
        MetricsSnapshot snap = snapshot(50, 150);

        Diagnosis first = evaluate(snap).hit();
        Diagnosis second = evaluate(snap).hit();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.detail(), second.detail(), "a pure rule must produce the same evidence twice");
        assertEquals(50.0, snap.msptAvg, 0.0, "the rule must not rewrite the snapshot");
        assertEquals(150.0, snap.msptMax, 0.0, "the rule must not rewrite the snapshot");
    }
}
