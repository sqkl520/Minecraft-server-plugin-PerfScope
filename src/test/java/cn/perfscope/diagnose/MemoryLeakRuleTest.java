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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断规则<b>本体</b>：{@link CoreRules.MemoryLeakRule}（DIAG-MEM-LEAK）。
 *
 * <p><b>与 {@code DiagnosticEngineContractTest} 的分工</b>：那个类测引擎的闸门（代次校验、
 * 异常隔离、总数口径），任何一条规则的 {@code evaluate} 换成 {@code return Evaluation.clean()}
 * 它都不会红。本类直接驱动规则体，断言"这份快照必须产出非 null 的 {@link Diagnosis}"。</p>
 *
 * <h2>怎么在没有 Bukkit 的情况下喂历史窗口</h2>
 *
 * <p>{@link HistoryStore} 的公开写入路径 {@code record(snap)} 有三道闸：读配置、TPS 模块可用性、
 * 以及<b>按墙钟节流的采样间隔</b>（默认 60 秒）。想造"30 分钟窗口里 12 个样本"就得等 12 分钟，
 * 所以这里不用 {@code record}，而是用 {@code Unsafe.allocateInstance} + 反射直接写它的私有环
 * {@code ring}（{@code ArrayDeque<HistorySample>}）。读路径 {@code since(...)}/{@code samples()}
 * 只要求 {@code configManager.get() != null && cfg.history_enabled}，其余照常走真实代码。</p>
 *
 * <p>{@code HistorySample.timestamp} 是任意 epoch 毫秒，因此"30 分钟前"只是减法——
 * 不需要动系统时钟。</p>
 */
class MemoryLeakRuleTest {

    private static final int WARN_THRESHOLD = 85;

    @BeforeAll
    static void initMessages() throws Exception {
        // 复用同一套初始化：它会先合并各模块的文案表（否则 Messages.get 只会回退成键名）
        AlertServiceStateMachineFixture.initMessages();
    }

    // ==================== 装配 ====================

    private static Object unsafe() throws Exception {
        Class<?> c = Class.forName("sun.misc.Unsafe");
        Field f = c.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object alloc(Class<?> k) throws Exception {
        return unsafe().getClass().getMethod("allocateInstance", Class.class).invoke(unsafe(), k);
    }

    private static void setField(Class<?> cls, Object t, String n, Object v) throws Exception {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                f.set(t, v);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(n);
    }

    private static ConfigManager configManager(boolean historyEnabled) throws Exception {
        ConfigManager cm = (ConfigManager) alloc(ConfigManager.class);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.history_enabled = historyEnabled;
        cfg.history_sampleIntervalSeconds = 60;
        cfg.memory_heapWarningThreshold = WARN_THRESHOLD;
        cfg.memory_heapCriticalThreshold = 95;
        setField(ConfigManager.class, cm, "config", cfg);
        return cm;
    }

    /** 造一个只含指定样本的 HistoryStore（绕过节流，直接写私有环）。 */
    private static HistoryStore history(ConfigManager cm, List<HistorySample> samples) throws Exception {
        HistoryStore hs = (HistoryStore) alloc(HistoryStore.class);
        setField(HistoryStore.class, hs, "configManager", cm);
        setField(HistoryStore.class, hs, "logger", Logger.getLogger("pscope-memleak-test"));
        Deque<HistorySample> ring = new ArrayDeque<>(samples);
        setField(HistoryStore.class, hs, "ring", ring);
        return hs;
    }

    /** 一份内存模块可用、堆占用为 {@code heapPercent} 的快照。 */
    private static MetricsSnapshot snapshot(int heapPercent, long heapMax) {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.timestamp = System.currentTimeMillis();
        snap.heapUsagePercent = heapPercent;
        snap.heapMax = heapMax;
        snap.heapUsed = (long) (heapMax * heapPercent / 100.0);
        return snap;
    }

    /**
     * 造 {@code count} 个样本，覆盖"最近 30 分钟"、间隔 {@code gapMs}。
     *
     * <p>堆占用从 {@code fromPercent} 线性升到 {@code toPercent}；{@code lastTs} 决定最后一个样本
     * 落在"现在"还是更早（用于验证窗口边界）。</p>
     */
    private static List<HistorySample> risingSamples(int count, long gapMs,
                                                     int fromPercent, int toPercent, long lastTs) {
        java.util.ArrayList<HistorySample> out = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int pct = count == 1 ? fromPercent
                : fromPercent + (toPercent - fromPercent) * i / (count - 1);
            long ts = lastTs - (long) (count - 1 - i) * gapMs;
            out.add(new HistorySample(ts, 20.0, 10.0, pct, 5, 100, 500));
        }
        return out;
    }

    private static Evaluation evaluate(MetricsSnapshot snap, HistoryStore hs,
                                       ConfigManager cm) throws Exception {
        return new CoreRules.MemoryLeakRule().evaluate(snap, hs, cm.get());
    }

    /**
     * 预期 skip 文案 = 消息层对同一键 + 同一实参渲染出的文本。
     *
     * <p><b>不能用键名字面量断言</b>：{@code Evaluation.skip(id, reason)} 存的是<b>渲染后</b>的文案，
     * 拿 {@code "diag.skip.heap-below-threshold"} 去比，会在文案层正常工作时失败（本机实测）。
     * 也不硬编码英文句子——那样切语言就红。让消息层自己渲染同一模板，既精确又与语言无关。</p>
     */
    private static String reason(String key, Object... args) {
        return cn.perfscope.util.Messages.get(key, args);
    }

    /** {@code coverageProblem} 的 {@code what} 实参取的就是这个标签。 */
    private static String historyLabel() {
        return cn.perfscope.util.Messages.get("diag.label.history");
    }

    // ==================== 正向：必须命中 ====================

    /**
     * <b>核心用例</b>：堆占用从 80% 线性爬到 95%，窗口后半段谷值仍高于起点 → 必须给出非 null 结论。
     *
     * <p>样本：16 条 × 100 秒 = 25 分钟，<b>刻意留出 5 分钟余量</b>而不贴着 30 分钟边界——
     * 样本时间戳用墙钟算，贴着边界跑会因几毫秒的 {@code now} 漂移随机掉出窗口（本机实测过）。
     * 满足 {@code MIN_SAMPLES=10} 与覆盖判据；
     * 抬升 15 个百分点 ≥ {@code MIN_HEAP_GROWTH_PERCENT=8}；后半段最低 88% &gt; 起点 80%。</p>
     */
    @Test
    void steadyRiseMustProduceADiagnosis() throws Exception {
        ConfigManager cm = configManager(true);
        long now = System.currentTimeMillis();
        HistoryStore hs = history(cm, risingSamples(16, 100_000L, 80, 95, now));
        MetricsSnapshot snap = snapshot(95, 4L * 1024 * 1024 * 1024);

        Evaluation ev = evaluate(snap, hs, cm);

        Diagnosis d = ev.hit();
        assertNotNull(d, "a sustained 15-point heap rise must be reported, got: "
            + (ev.skip() == null ? "clean" : "skip/" + ev.skip().reason()));
        assertEquals("DIAG-MEM-LEAK", d.ruleId());
        assertFalse(d.title().isEmpty(), "the diagnosis must carry a title");
        assertFalse(d.detail().isEmpty(), "the diagnosis must carry the evidence detail");
        assertFalse(d.suggestion().isEmpty(), "the diagnosis must carry an actionable suggestion");
        assertTrue(d.critical(), "DIAG-MEM-LEAK is emitted as critical (it takes action to confirm)");
    }

    /** detail 里必须出现真实数值，而不是一句套话——否则服主无法核对。 */
    @Test
    void diagnosisDetailCarriesTheObservedNumbers() throws Exception {
        ConfigManager cm = configManager(true);
        long now = System.currentTimeMillis();
        HistoryStore hs = history(cm, risingSamples(16, 100_000L, 80, 95, now));

        Diagnosis d = evaluate(snapshot(95, 4L * 1024 * 1024 * 1024), hs, cm).hit();
        assertNotNull(d);
        // 起点 80、终点 95 都必须出现在 detail 里（文案模板的占位符一旦错位，这里会红）
        assertTrue(d.detail().contains("80"),
            "detail must mention the window start value, got: " + d.detail());
        assertTrue(d.detail().contains("95"),
            "detail must mention the window end value, got: " + d.detail());
    }

    // ==================== 反向：不能命中 ====================

    /** 抬升不足 8 个百分点 → clean（不是 skip：它确实评估过）。 */
    @Test
    void smallRiseIsCleanNotSkipped() throws Exception {
        ConfigManager cm = configManager(true);
        long now = System.currentTimeMillis();
        HistoryStore hs = history(cm, risingSamples(16, 100_000L, 88, 93, now));
        MetricsSnapshot snap = snapshot(93, 4L * 1024 * 1024 * 1024);

        Evaluation ev = evaluate(snap, hs, cm);
        assertNull(ev.hit(), "a 5-point rise is below the 8-point bar");
        assertTrue(ev.isClean(), "it was evaluated and found fine — that is clean, not skip");
    }

    /** 后半段谷值回落到起点以下 → 更像一次尖峰后回落，必须 clean。 */
    @Test
    void spikeThenDropBackIsClean() throws Exception {
        ConfigManager cm = configManager(true);
        long now = System.currentTimeMillis();
        // 前 8 条 80→97，后 8 条 97→60：终点比起点低，min-later-half(60) < start(80)。
        // 当前堆占用取 90（仍 ≥ 阈值 85），否则前置不成立会走 skip 而不是 clean——
        // 这条用例要证明的正是"越界但更像波动 → 明确评过、判定干净"。
        java.util.ArrayList<HistorySample> s = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int pct = i < 8 ? 80 + i * 17 / 7 : 97 - (i - 7) * 37 / 8;
            s.add(new HistorySample(now - (15 - i) * 100_000L, 20.0, 10.0, pct, 5, 100, 500));
        }
        HistoryStore hs = history(cm, s);
        Evaluation ev = evaluate(snapshot(90, 4L * 1024 * 1024 * 1024), hs, cm);
        assertNull(ev.hit(), "a spike that fell back below the start is normal fluctuation, not a leak");
        assertTrue(ev.isClean(), "it was evaluated and found fine — clean, not skip");
    }

    /** 当前堆占用低于告警阈值 → 前置不成立，必须 skip 且说明原因（不是 clean）。 */
    @Test
    void heapBelowThresholdSkipsWithReason() throws Exception {
        ConfigManager cm = configManager(true);
        long now = System.currentTimeMillis();
        HistoryStore hs = history(cm, risingSamples(16, 100_000L, 40, 60, now));
        Evaluation ev = evaluate(snapshot(60, 4L * 1024 * 1024 * 1024), hs, cm);

        assertNull(ev.hit());
        assertFalse(ev.isClean(), "a rule whose precondition failed did NOT evaluate — it must skip");
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.heap-below-threshold", 60, WARN_THRESHOLD),
            ev.skip().reason());
    }

    /** 内存模块不可用 → skip，且原因是模块不可用（不能把"没数据"说成"没问题"）。 */
    @Test
    void unavailableMemoryModuleSkips() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, risingSamples(16, 100_000L, 80, 95,
            System.currentTimeMillis()));
        MetricsSnapshot snap = snapshot(95, 4L * 1024 * 1024 * 1024);
        snap.unavailable.add(MetricsSnapshot.MODULE_MEMORY);

        Evaluation ev = evaluate(snap, hs, cm);
        assertNull(ev.hit());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.memory-unavailable"), ev.skip().reason());
    }

    /** 历史样本不足 10 条 → 覆盖不足 skip（区分"没查"与"查了没事"）。 */
    @Test
    void tooFewSamplesSkips() throws Exception {
        ConfigManager cm = configManager(true);
        long now = System.currentTimeMillis();
        HistoryStore hs = history(cm, risingSamples(6, 100_000L, 80, 95, now));

        Evaluation ev = evaluate(snapshot(95, 4L * 1024 * 1024 * 1024), hs, cm);
        assertNull(ev.hit());
        assertEquals(reason("diag.skip.coverage-samples", historyLabel(), 6, 10), ev.skip().reason());
    }

    /** 窗口外（35 分钟前）的样本不算数：样本都在窗口前 → 覆盖不足。 */
    @Test
    void samplesOutsideTheWindowAreNotCounted() throws Exception {
        ConfigManager cm = configManager(true);
        long now = System.currentTimeMillis();
        // 最后一个样本落在 35 分钟前 → since(now-30min) 返回空
        HistoryStore hs = history(cm, risingSamples(16, 100_000L, 80, 95, now - 35 * 60_000L));

        Evaluation ev = evaluate(snapshot(95, 4L * 1024 * 1024 * 1024), hs, cm);
        assertNull(ev.hit(), "a stale window must not be used to claim an active leak");
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.coverage-samples", historyLabel(), 0, 10), ev.skip().reason());
    }

    /** 历史被关闭 → 读取路径返回空列表 → 覆盖不足 skip。 */
    @Test
    void disabledHistorySkipsInsteadOfInventingData() throws Exception {
        ConfigManager cm = configManager(false);
        HistoryStore hs = history(cm, risingSamples(16, 100_000L, 80, 95,
            System.currentTimeMillis()));

        Evaluation ev = evaluate(snapshot(95, 4L * 1024 * 1024 * 1024), hs, cm);
        assertNull(ev.hit());
        assertEquals(reason("diag.skip.coverage-samples", historyLabel(), 0, 10), ev.skip().reason());
    }

    /** 样本里出现 0（内存模块那段被禁用/失败）→ 不得把 0 当作真实的 0% 参与统计。 */
    @Test
    void zeroHeapSamplesAreNotTreatedAsRealZeroPercent() throws Exception {
        ConfigManager cm = configManager(true);
        long now = System.currentTimeMillis();
        // 把"窗口起点"改成 0：若规则把它当作真实的 0%，抬升会是 95 个百分点、且后半段谷值必然
        // 高于起点 → 立刻命中一条假泄漏。实现有 0 值守卫，故实际结果是不命中。
        // 注意：{@code HistoryStore.since(...)} 只按时间过滤，不会剔除 0 值样本，
        // 所以这个哨兵能真的走到规则体里——守卫必须在规则里，不能在存储里。
        java.util.ArrayList<HistorySample> s = new java.util.ArrayList<>(
            risingSamples(16, 100_000L, 80, 95, now));
        s.set(0, new HistorySample(s.get(0).timestamp(), 20.0, 10.0, 0, 5, 100, 500));
        HistoryStore hs = history(cm, s);
        assertEquals(16, hs.since(now - 30 * 60_000L).size(),
            "precondition: the 0% sample really is inside the window");

        Evaluation ev = evaluate(snapshot(95, 4L * 1024 * 1024 * 1024), hs, cm);
        assertNull(ev.hit(), "a 0% sample is a missing-reading sentinel, not a real 0% heap");
        assertNotNull(ev.skip(), "the rule must say it could not conclude, not pretend it is clean");
        assertEquals(reason("diag.skip.heap-sample-missing"), ev.skip().reason());
    }

    /**
     * 规则只读输入，不改快照与历史：同一次评估跑两遍结果必须一致。
     *
     * <p>契约要求 {@code evaluate} 无副作用。这条同时锁住"样本没有被原地排序/裁剪"。</p>
     */
    @Test
    void evaluationIsPureAndRepeatable() throws Exception {
        ConfigManager cm = configManager(true);
        long now = System.currentTimeMillis();
        HistoryStore hs = history(cm, risingSamples(16, 100_000L, 80, 95, now));
        MetricsSnapshot snap = snapshot(95, 4L * 1024 * 1024 * 1024);

        Diagnosis first = evaluate(snap, hs, cm).hit();
        Diagnosis second = evaluate(snap, hs, cm).hit();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.detail(), second.detail(),
            "a pure rule must produce the same evidence twice");
        assertEquals(16, hs.size(), "the rule must not mutate the history ring");
    }

    /** 引擎层面对同一条规则也要给出命中（规则体已独立验证，这里只确认接线没断）。 */
    @Test
    void engineSurfacesTheRuleHit() throws Exception {
        ConfigManager cm = configManager(true);
        long now = System.currentTimeMillis();
        setField(ConfigManager.class, cm, "publishedAtMillis", now - 60_000L);

        PerfScope p = (PerfScope) alloc(PerfScope.class);
        setField(PerfScope.class, p, "logger", Logger.getLogger("pscope-memleak-test"));
        setField(PerfScope.class, p, "configManager", cm);

        MetricsSnapshot snap = snapshot(95, 4L * 1024 * 1024 * 1024);
        snap.timestamp = now;
        cn.perfscope.metrics.MetricsCollector mc =
            (cn.perfscope.metrics.MetricsCollector) alloc(cn.perfscope.metrics.MetricsCollector.class);
        Deque<MetricsSnapshot> latest = new java.util.concurrent.ConcurrentLinkedDeque<>();
        latest.addLast(snap);
        setField(cn.perfscope.metrics.MetricsCollector.class, mc, "history", latest);
        setField(PerfScope.class, p, "metricsCollector", mc);

        HistoryStore hs = history(cm, risingSamples(16, 100_000L, 80, 95, now));
        DiagnosticEngine engine = new DiagnosticEngine(p, hs);
        DiagnosticEngine.Result r = engine.run();

        assertTrue(r.hits().stream().anyMatch(h -> "DIAG-MEM-LEAK".equals(h.ruleId())),
            "the engine must surface the leak hit; hits=" + r.hits().size()
                + " skipped=" + r.skipped().size());
    }
}
