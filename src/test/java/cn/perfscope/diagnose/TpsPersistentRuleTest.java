/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.diagnose;

import cn.perfscope.PerfScope;
import cn.perfscope.alert.AlertServiceStateMachineFixture;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.history.HistorySample;
import cn.perfscope.history.HistoryStore;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.HistoryFixtures;
import cn.perfscope.support.TestObjects;
import cn.perfscope.util.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断规则<b>本体</b>：{@link CoreRules.TpsPersistentRule}（DIAG-TPS-PERSIST）。
 *
 * <h2>为什么这个类存在（两个实测复现的高危漏报）</h2>
 *
 * <p><b>F-3：{@code tps15m == 0} 被当成"未知"</b>。规则的 15 分钟判据写成
 * {@code snap.tps15m > 0 && ... < 阈值}，而 {@link MetricsSnapshot} 的
 * {@code tps1m/tps5m/tps15m} <b>没有</b> {@code -1} 哨兵（与 {@code msptAvg}/{@code cpu} 不同）：
 * 采集层直接把 {@code Bukkit.getTPS()} 写进去。所以 {@code 0.0} 不是"未采集"，
 * 而是"服务器彻底冻结"这个最坏的真实读数。用 {@code > 0} 把它当未知丢掉，结果是
 * 15 分钟均值 0.0（完全卡死）时规则返回 {@code CLEAN}——"查过了，没事"。</p>
 *
 * <p><b>F-4：窗口内 TPS 全 0 时整条规则 SKIP</b>。窗口扫描用
 * {@code if (s.tps1m() <= 0) continue} 过滤"哨兵"，但 {@link HistoryStore#record} 在
 * {@code unavailable.contains(MODULE_TPS)} 时<b>整条样本不入环</b>（污染防护），
 * 因此环里出现的 {@code tps1m == 0} 只可能是真实的 0 TPS。该过滤把最严重的证据当垃圾丢掉：
 * 12 条全 0 样本 + {@code tps15m=3.0} 时返回
 * {@code SKIP("Not enough valid TPS samples ... (0/5)")}——服主看到"无法评估"，
 * 而真相是"严重低位"。<b>修复后这条窗口证据必须让规则命中。</b></p>
 *
 * <h2>怎么在没有 Bukkit 的情况下喂历史窗口</h2>
 *
 * <p>与 {@link MemoryLeakRuleTest} 同路数：{@code HistoryStore.record(snap)} 有三道闸
 * （配置、TPS 可用性、<b>按墙钟节流的采样间隔</b>，默认 60 秒），造不出"10 分钟 12 个样本"。
 * 故用 {@code HistoryFixtures.seeded} 直接写它的私有环 {@code ring}，
 * 读路径 {@code since(...)} 照常走真实代码。</p>
 *
 * <p><b>为什么不能"顺手"改成用 {@code record()} 喂</b>：{@code record()} 恰好在 TPS 不可用时
 * 整条丢弃——本类要测的正是"环里的 0 是真实读数"这一前提，用 {@code record()} 反而
 * 造不出被测状态。</p>
 */
class TpsPersistentRuleTest {

    /** 与 config.yml 默认一致；显式写出，避免 ConfigData 的 0 默认值让阈值判据失去意义。 */
    private static final double WARN = 18.0;
    private static final double CRIT = 15.0;

    /** 10 分钟窗口 / 5 条样本，与规则常量对齐。 */
    private static final long WINDOW_MS = 10 * 60_000L;

    @BeforeAll
    static void initMessages() throws Exception {
        AlertServiceStateMachineFixture.initMessages();
    }

    // ==================== 装配 ====================

    private static ConfigManager configManager(boolean historyEnabled) {
        ConfigManager cm = new ConfigManager(null);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.history_enabled = historyEnabled;
        cfg.history_sampleIntervalSeconds = 60;
        cfg.tps_warningThreshold = WARN;
        cfg.tps_criticalThreshold = CRIT;
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        return cm;
    }

    /** 一份 TPS 模块可用的快照。三个 TPS 值都必须显式写：它们没有 -1 哨兵，默认就是 0.0。 */
    private static MetricsSnapshot snapshot(double tps1m, double tps5m, double tps15m) {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.timestamp = System.currentTimeMillis();
        snap.tps1m = tps1m;
        snap.tps5m = tps5m;
        snap.tps15m = tps15m;
        return snap;
    }

    /**
     * 历史样本：{@code count} 条，等间隔 {@code gapMs}，最后一条落在"现在"。
     *
     * <p>12 条 × 45 秒 = 8.25 分钟跨度，落在 10 分钟窗内且满足 {@code coverageProblem}
     * （期望跨度 = 45s×11×0.8 = 396s，实际 495s）。</p>
     */
    private static List<HistorySample> samples(int count, long gapMs, double[] tps1mValues, long lastTs) {
        List<HistorySample> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long ts = lastTs - (long) (count - 1 - i) * gapMs;
            out.add(new HistorySample(ts, tps1mValues[i], 10.0, 50, 5, 100, 500));
        }
        return out;
    }

    /** {@code count} 条 TPS 恒为 {@code tps} 的样本。 */
    private static List<HistorySample> flatSamples(int count, long gapMs, double tps) {
        double[] v = new double[count];
        java.util.Arrays.fill(v, tps);
        return samples(count, gapMs, v, System.currentTimeMillis());
    }

    private static Evaluation evaluate(MetricsSnapshot snap, HistoryStore hs,
                                       ConfigManager cm) throws Exception {
        return new CoreRules.TpsPersistentRule().evaluate(snap, hs, cm.get());
    }

    private static String reason(String key, Object... args) {
        return Messages.get(key, args);
    }

    /** {@code coverageProblem} 的 {@code what} 实参取的就是这个标签。 */
    private static String historyLabel() {
        return Messages.get("diag.label.history");
    }

    /** 与实现同口径的数值渲染（不硬编码字符串，改精度时测试不会假红）。 */
    private static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ==================== F-4：窗口里的 0 是证据，不是垃圾 ====================

    /**
     * <b>F-4 核心用例</b>：12 条样本 {@code tps1m} 全为 0.0，{@code tps15m=3.0}。
     *
     * <p>修复前：{@code tps1m <= 0} 把 12 条全部滤掉 → {@code count=0} →
     * {@code SKIP("Not enough valid TPS samples in history (0/5)")}。
     * 修复后：12 条全部参与，{@code avg1m=0.0 < 18} → 命中，且 {@code tps15m=3.0 < 15} → critical。</p>
     */
    @Test
    void allZeroTpsWindowMustHitNotSkip() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 0.0));

        Evaluation ev = evaluate(snapshot(0.0, 0.0, 3.0), hs, cm);

        Diagnosis d = ev.hit();
        assertNotNull(d, "a window where every sample is 0 TPS is the *worst* evidence there is; "
            + "got " + (ev.isClean() ? "clean" : "skip/" + (ev.skip() == null ? "?" : ev.skip().reason())));
        assertEquals("DIAG-TPS-PERSIST", d.ruleId());
        assertTrue(d.critical(), "0 TPS in the window plus a 3.0 fifteen-minute average is critical");
    }

    /**
     * 窗口全 0，但 15 分钟均值看起来健康（19.5）：仍必须命中。
     *
     * <p>这条把两条判据的独立性锁住——修 F-4 时若有人"顺手"把命中条件改成
     * {@code persist15m && persistWindow}（AND），本用例立刻红。</p>
     */
    @Test
    void allZeroWindowHitsEvenWhenFifteenMinuteAverageLooksHealthy() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 0.0));

        Evaluation ev = evaluate(snapshot(0.0, 18.0, 19.5), hs, cm);

        Diagnosis d = ev.hit();
        assertNotNull(d, "the window average is 0 TPS; a healthy 15-minute average must not veto it");
        assertFalse(d.critical(), "19.5 >= 15, so the 15-minute measure alone is not critical");
    }

    /** 0 必须真的进均值：11 条 19.0 + 1 条 0.0 → 17.42 < 18，命中。 */
    @Test
    void aSingleZeroDragsTheWindowAverageBelowTheLine() throws Exception {
        ConfigManager cm = configManager(true);
        double[] v = new double[12];
        java.util.Arrays.fill(v, 19.0);
        v[5] = 0.0;
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(12, 45_000L, v, System.currentTimeMillis()));

        Evaluation ev = evaluate(snapshot(19.0, 19.2, 19.9), hs, cm);

        Diagnosis d = ev.hit();
        assertNotNull(d, "one real 0 TPS sample must pull the mean down (17.42 < 18)");
        assertTrue(d.detail().contains(f2((19.0 * 11) / 12)),
            "the window average must be computed over all 12 samples, got: " + d.detail());
    }

    /**
     * 不命中侧：窗口均值在线上（19.0）且 15 分钟均值健康 → clean，而不是 skip。
     */
    @Test
    void healthyWindowIsCleanNotSkipped() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 19.0));

        Evaluation ev = evaluate(snapshot(19.0, 19.2, 19.9), hs, cm);

        assertNull(ev.hit());
        assertTrue(ev.isClean(), "the rule really evaluated and found nothing — that is clean");
    }

    /**
     * {@code > 20.5} 的异常值过滤必须保留（F-4 只要求去掉 {@code <= 0}）。
     *
     * <p>5 条 25.0（不可能读数）+ 7 条 20.0：过滤后 count=7 ≥ 5，均值 20.0 → clean。
     * 若有人把整个过滤条件删掉，均值会变成 22.08 也不会命中——所以这条本身不足以区分；
     * 配合下一条（有效样本被滤到不足 5 条 → skip）才有区分力。</p>
     */
    @Test
    void samplesAboveTheTpsCeilingAreStillFilteredOut() throws Exception {
        ConfigManager cm = configManager(true);
        double[] v = new double[12];
        for (int i = 0; i < 5; i++) {
            v[i] = 25.0;
        }
        for (int i = 5; i < 12; i++) {
            v[i] = 20.0;
        }
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(12, 45_000L, v, System.currentTimeMillis()));

        Evaluation ev = evaluate(snapshot(20.0, 20.0, 20.0), hs, cm);

        assertNull(ev.hit());
        assertTrue(ev.isClean(), "7 valid samples averaging 20.0 is healthy");
    }

    /** 异常值把有效样本滤到不足 5 条 → 必须 skip 并说明条数（不是 clean、也不是命中）。 */
    @Test
    void tooFewValidSamplesAfterOutlierFilteringSkips() throws Exception {
        ConfigManager cm = configManager(true);
        double[] v = new double[12];
        for (int i = 0; i < 11; i++) {
            v[i] = 25.0;
        }
        v[11] = 20.0;
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(12, 45_000L, v, System.currentTimeMillis()));

        Evaluation ev = evaluate(snapshot(3.0, 3.0, 3.0), hs, cm);

        assertNull(ev.hit());
        assertFalse(ev.isClean(), "filtering left too few samples — the rule could not evaluate");
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.tps-samples", 1, 5), ev.skip().reason());
    }

    // ==================== F-3：tps15m == 0 是最坏读数，不是未知 ====================

    /**
     * <b>F-3 核心用例</b>：窗口健康（20.0），但 {@code tps15m = 0.0}（服务器 15 分钟均值归零）。
     *
     * <p>修复前：{@code persist15m = (0 > 0 && ...)} = false，{@code persistWindow} 也 false
     * → 返回 {@code CLEAN}（"查过了，没事"）。修复后：命中且 critical。</p>
     */
    @Test
    void zeroFifteenMinuteTpsIsCriticalNotClean() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 20.0));

        Evaluation ev = evaluate(snapshot(20.0, 20.0, 0.0), hs, cm);

        Diagnosis d = ev.hit();
        assertNotNull(d, "a 0.0 fifteen-minute average is a total freeze, not a missing reading; "
            + "got " + (ev.isClean() ? "clean" : "skip"));
        assertTrue(d.critical(), "0.0 < 15 is critical");
    }

    /**
     * detail 里 {@code 0.00} 必须作为<b>真实读数</b>渲染，不能被改写成"未采集"之类的标签。
     *
     * <p>这一条是防过度修复的：F-3 的第一项改完之后，"0 不可信"这个前提已经不存在了，
     * 再给 0 贴一个"未知"标签，等于把最严重的读数又藏起来一次。</p>
     */
    @Test
    void detailPresentsTheRealZeroAsAReading() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 20.0));

        Diagnosis d = evaluate(snapshot(20.0, 20.0, 0.0), hs, cm).hit();
        assertNotNull(d);
        assertTrue(d.detail().contains(f2(0.0)),
            "the fifteen-minute average must render as 0.00 (a real reading), got: " + d.detail());
        assertFalse(d.detail().contains("NaN"), "NaN must never reach the user");
    }

    /** 15 分钟判据在窗口健康时单独也能命中（修复不得把它弄丢）。 */
    @Test
    void lowFifteenMinuteAverageAloneTriggersOnAHealthyWindow() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 20.0));

        Diagnosis d = evaluate(snapshot(20.0, 20.0, 16.0), hs, cm).hit();
        assertNotNull(d, "16.0 < 18 must fire even when the window average is 20.0");
        assertFalse(d.critical(), "16.0 >= 15 is a warning, not critical");
    }

    /** critical 的边界：<b>严格小于</b> 15 才 critical（14.99 是，15.0 不是）。 */
    @Test
    void criticalLineIsStrictlyBelowTheCriticalThreshold() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 20.0));

        assertTrue(evaluate(snapshot(20.0, 20.0, 14.99), hs, cm).hit().critical(),
            "14.99 < 15 must be critical");
        assertFalse(evaluate(snapshot(20.0, 20.0, 15.0), hs, cm).hit().critical(),
            "15.0 is exactly on the line — the comparison is strict");
    }

    /**
     * 唯一的"不可信"情形（服务端给了非有限值）必须显式说明，不得把 {@code NaN} 当读数打印。
     *
     * <p>修复后 0.0 已被认定为真实读数，故 detail 只剩这一种需要贴标签的情形。
     * 窗口均值 3.0 让规则经由窗口判据命中，从而真的走到 detail 渲染。</p>
     */
    @Test
    void nonFiniteFifteenMinuteValueIsLabelledNotPrintedAsNaN() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 3.0));

        Diagnosis d = evaluate(snapshot(3.0, 3.0, Double.NaN), hs, cm).hit();
        assertNotNull(d, "the window evidence alone is enough to conclude");
        assertFalse(d.detail().contains("NaN"),
            "a non-finite value must be explained, not printed as NaN: " + d.detail());
        assertTrue(d.detail().contains(Messages.get("diag.value.not-reported")),
            "the detail must say the server did not report this measure: " + d.detail());
    }

    /**
     * 负的 15 分钟均值同样不是读数，也走"未上报"标签，且<b>不得</b>被判成 critical。
     *
     * <p>与窗口侧口径一致：循环里 {@code !(s.tps1m() >= 0)} 把负数与 NaN 一起滤掉，
     * 快照侧若只判 {@code Double.isFinite} 就会把 {@code -1} 当作"低于 critical 线"。
     * 这条把两侧的口径钉在一起——单看窗口侧的测试发现不了这种分叉。</p>
     */
    @Test
    void negativeFifteenMinuteValueIsTreatedAsNotReported() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 20.0));

        // 窗口健康，只有 15 分钟那侧异常 -> 若把 -1 当读数，会得到"命中且 critical"的荒谬结论
        Evaluation ev = evaluate(snapshot(20.0, 20.0, -1.0), hs, cm);

        assertNull(ev.hit(), "a negative TPS average is not a reading — it must not trigger a critical");
        assertTrue(ev.isClean(), "the window was evaluated and is healthy (20.0); that is clean, not skip");
    }

    /**
     * 上界也必须与窗口侧同口径：{@code > 20.5} 的读数不是可信读数。
     *
     * <p>窗口侧一直有 {@code TPS_CEILING} 上界，快照侧原先只有下界。<b>目前</b>这不产出错误
     * 结论——{@code ConfigManager} 把 {@code tps.warning_threshold} 钳在 {@code <= 20.5}，
     * 所以 {@code tps15m > 20.5} 时 {@code persist15m} 恒为 false。但那是在靠<b>别的类</b>
     * 的钳制兜底：一旦上界放宽（或有人直接构造 {@code ConfigData}），快照侧会拿一个
     * "窗口认为是垃圾"的值去判定。这条测试把两个侧的口径钉在一起。</p>
     *
     * <p>构造：窗口正常（20.0），{@code tps15m} 用一个物理上不可能的 25.0。若快照侧放行它，
     * 它既不低于告警线也不会命中；真正要钉的是"它被贴上未上报标签"而不是被当成读数打印。</p>
     */
    @Test
    void aboveCeilingFifteenMinuteValueIsAlsoTreatedAsNotReported() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 20.0));

        // 让规则经由窗口侧命中，从而真的走到 detail 渲染
        HistoryStore slow = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 3.0));
        Diagnosis d = evaluate(snapshot(3.0, 25.0, 25.0), slow, cm).hit();
        assertNotNull(d, "the window evidence alone is enough to conclude");
        assertTrue(d.detail().contains(Messages.get("diag.value.not-reported")),
            "a value above the ceiling must be labelled, not printed as a reading: " + d.detail());
        assertFalse(d.detail().contains("25.00"),
            "25.00 is not a real 15-minute average and must not reach the user: " + d.detail());
    }

    // ==================== 三态与前置闸门（回归保护） ====================

    /** TPS 模块不可用 → skip 且点名模块（这正说明 F-3 的 0 不可能是"未采集"）。 */
    @Test
    void unavailableTpsModuleSkipsWithItsOwnReason() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 0.0));
        MetricsSnapshot snap = snapshot(0.0, 0.0, 0.0);
        snap.unavailable.add(MetricsSnapshot.MODULE_TPS);

        Evaluation ev = evaluate(snap, hs, cm);

        assertNull(ev.hit());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.tps-unavailable"), ev.skip().reason());
    }

    /** 窗口内样本不足 5 条 → 覆盖不足 skip（区分"没查"与"查了没事"）。 */
    @Test
    void tooFewSamplesSkipsWithCoverageReason() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(4, 45_000L, 0.0));

        Evaluation ev = evaluate(snapshot(0.0, 0.0, 0.0), hs, cm);

        assertNull(ev.hit());
        assertEquals(reason("diag.skip.coverage-samples", historyLabel(), 4, 5), ev.skip().reason());
    }

    /** 历史被关闭 → 读取路径返回空列表 → 覆盖不足 skip，绝不凭空造数据。 */
    @Test
    void disabledHistorySkipsInsteadOfInventingData() throws Exception {
        ConfigManager cm = configManager(false);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 0.0));

        Evaluation ev = evaluate(snapshot(0.0, 0.0, 0.0), hs, cm);

        assertNull(ev.hit());
        assertEquals(reason("diag.skip.coverage-samples", historyLabel(), 0, 5), ev.skip().reason());
    }

    /** 规则只读输入：同一次评估跑两遍结果一致，且不裁剪历史环。 */
    @Test
    void evaluationIsPureAndRepeatable() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 0.0));
        MetricsSnapshot snap = snapshot(0.0, 0.0, 3.0);

        Diagnosis first = evaluate(snap, hs, cm).hit();
        Diagnosis second = evaluate(snap, hs, cm).hit();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.detail(), second.detail(), "a pure rule must produce the same evidence twice");
        assertEquals(12, hs.size(), "the rule must not mutate the history ring");
    }

    // ==================== 接线：引擎确实把这条命中交给用户 ====================

    /**
     * 引擎层面也要能看到这条命中（规则体已独立验证，这里只确认接线没断）。
     *
     * <p>用 {@code Unsafe} 装配 {@link PerfScope}（真实构造器会触碰 Bukkit 静态初始化）。
     * {@code publishedAtMillis} 保持 0，代次守卫（f-s03-data-06）不会生效。</p>
     */
    @Test
    void engineSurfacesTheZeroWindowHit() throws Exception {
        ConfigManager cm = configManager(true);
        long now = System.currentTimeMillis();

        PerfScope p = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, p, "logger", Logger.getLogger("pscope-tpspersist-test"));
        TestObjects.setField(PerfScope.class, p, "configManager", cm);

        MetricsSnapshot snap = snapshot(0.0, 0.0, 3.0);
        snap.timestamp = now;
        cn.perfscope.metrics.MetricsCollector mc =
            TestObjects.allocate(cn.perfscope.metrics.MetricsCollector.class);
        Deque<MetricsSnapshot> latest = new java.util.concurrent.ConcurrentLinkedDeque<>();
        latest.addLast(snap);
        TestObjects.setField(cn.perfscope.metrics.MetricsCollector.class, mc, "history", latest);
        TestObjects.setField(PerfScope.class, p, "metricsCollector", mc);

        HistoryStore hs = HistoryFixtures.seeded(cm, flatSamples(12, 45_000L, 0.0));
        DiagnosticEngine engine = new DiagnosticEngine(p, hs);
        DiagnosticEngine.Result r = engine.run();

        assertTrue(r.hits().stream().anyMatch(h -> "DIAG-TPS-PERSIST".equals(h.ruleId())),
            "the engine must surface the sustained-low-TPS hit; hits=" + r.hits().size()
                + " skipped=" + r.skipped().size());
    }

    /**
     * 窗口约束的护栏：窗口外的样本不能被用来断言"持续低位"。
     */
    @Test
    void samplesOutsideTheWindowAreNotCounted() throws Exception {
        ConfigManager cm = configManager(true);
        long stale = System.currentTimeMillis() - 25 * 60_000L;   // 全部落在 10 分钟窗之外
        double[] v = new double[12];
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(12, 45_000L, v, stale));

        assertEquals(0, hs.since(System.currentTimeMillis() - WINDOW_MS).size(),
            "precondition: every sample really is outside the window");

        Evaluation ev = evaluate(snapshot(0.0, 0.0, 3.0), hs, cm);
        assertNull(ev.hit());
        assertEquals(reason("diag.skip.coverage-samples", historyLabel(), 0, 5), ev.skip().reason());
    }

    // ==================== 前提锁定：环里的 0 为什么可信 ====================
    //
    // 上面 4 个 F-4 用例（以及规则里的"不过滤 <= 0"）都建立在同一个前提上：
    // **TPS 不可用期的快照根本不会进历史环**（HistoryStore.record 的污染防护）。
    // 这条前提是"环里的 0 只可能是真实 0 TPS"的全部依据。它住在另一个类里，
    // 一旦有人为了"多留点历史"把那段守卫去掉，规则就会重新开始把假 0 当真实读数——
    // 而规则自己的测试**全部仍然绿**。故在这里直接驱动 record()，把前提钉住。

    /**
     * TPS 模块不可用的快照必须整条不进环（{@code tps1m} 默认就是 0.0，无 -1 哨兵）。
     *
     * <p>用真实 {@code record()}（不是反射写环）：这里是唯一需要验证 {@code record()} 自身
     * 语义的地方，绕开它就没有意义了。首次 record 必然放行（{@code lastRecordMs == 0}），
     * 不受采样间隔节流影响。</p>
     */
    @Test
    void tpsUnavailableSnapshotNeverEntersTheRing() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, List.of());

        MetricsSnapshot unavailableSnap = snapshot(0.0, 0.0, 0.0);
        unavailableSnap.unavailable.add(MetricsSnapshot.MODULE_TPS);
        hs.record(unavailableSnap);

        assertEquals(0, hs.size(),
            "an unavailable-TPS snapshot must not be recorded — the rule relies on this to trust 0");
    }

    /**
     * 反命题：TPS 可用时，{@code tps1m == 0} 的快照<b>必须</b>进环。
     *
     * <p>两条合起来才完整表达"环里的 0 是真实读数"：假 0（模块不可用）被挡在门外，
     * 真 0（服务器卡死）留在门内。只测其中一条会把含义测反。</p>
     */
    @Test
    void realZeroTpsSnapshotIsRecorded() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, List.of());

        hs.record(snapshot(0.0, 0.0, 3.0));

        assertEquals(1, hs.size(), "a real 0 TPS reading is data — it must be recorded, not dropped");
        assertEquals(0.0, hs.samples().get(0).tps1m(), 1e-9);
    }
}
