/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import cn.perfscope.config.ConfigManager;
import cn.perfscope.history.HistorySample;
import cn.perfscope.history.HistoryStore;
import cn.perfscope.support.HistoryFixtures;
import cn.perfscope.util.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日报接入 v1.4 五个新指标（{@code msptMax} / {@code cpuProcess} / {@code cpuSystem} /
 * {@code gcCount} / {@code gcTime}）的回归测试。
 *
 * <h2>这个类要守住的东西（比"能不能打印出来"重要得多）</h2>
 *
 * <p>新指标的每一个都带 {@code -1} 哨兵，而日报是<b>用户唯一会认真读的一屏</b>。
 * 哨兵处理错了会产生两类后果相反的谎话：</p>
 * <ul>
 *   <li><b>把盲区说成健康</b>：{@code -1} 参与求最大值/求平均，落败后显示 "峰值 0%"；
 *       或 GC 增量算成 0 次 → "整晚没有 GC"。用户据此认为服务器很健康，而实际是没采到。</li>
 *   <li><b>把健康说成盲区</b>：拿 {@code 0.0} 当哨兵 → 空载服务器的 CPU 永远显示"未采集"，
 *       用户会去查一个并不存在的问题。</li>
 * </ul>
 * <p>所以这里的断言几乎全是"两种错法各自被拒绝"，而不是"字段被打印了"。</p>
 *
 * <h2>为什么断言渲染后的文本，而不是渲染前的数字</h2>
 * <p>三态（未采集 / 样本不足 / 真的是 0）的输出在数据层全是"0 或 null"，只有渲染后才有区别。
 * 若只断言 {@code gcDelta() == 0}，把整段文案换成硬编码 {@code "0 次"} 也能通过——
 * 那正好是本任务要消灭的伪健康信号。</p>
 *
 * <p><b>语言无关</b>：全部期望值都由 {@link Messages#get} 自己渲染同一模板得到，
 * 不硬编码英文句子。既有的语言设置改变时这里不会假红。</p>
 */
class DailySummaryNewMetricsTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 窗口内的"现在"（固定值，不读系统时钟——样本时间戳都是相对它算的）。 */
    private static final long NOW = 1_760_000_000_000L;

    private static final long DAY = 24L * 60L * 60L * 1000L;

    @BeforeAll
    static void initMessages() throws Exception {
        // 必须走带 plugin 的 load：否则模块表（daily.* 都在 AlertMessages）不会被合并，
        // Messages.get 只会回退成键名，"两种未采集文案是否不同"这件事就测不出来了
        AlertServiceStateMachineFixture.initMessages();
    }

    // ==================== 装配（与 MemoryLeakRuleTest 同一路数） ====================

    /** 只含指定样本的 HistoryStore：绕过 record() 的节流，直接写私有环。 */
    private static HistoryStore history(List<HistorySample> samples) {
        return HistoryFixtures.seeded(HistoryFixtures.config(), samples);
    }

    /** 12 参数全量构造（7 参数版本会把 5 个新指标一律置 -1，这里要显式控制它们）。 */
    private static HistorySample sample(long ts, double msptMax, double cpuProcess,
                                        double cpuSystem, long gcCount, long gcTime) {
        return new HistorySample(ts, 20.0, 12.0, 70, 5, 100, 500,
            msptMax, cpuProcess, cpuSystem, gcCount, gcTime);
    }

    /** 窗口内 {@code count} 条样本，间隔 {@code gapMs}，末条落在 {@link #NOW}。 */
    private static List<HistorySample> samples(int count, long gapMs,
                                               java.util.function.IntFunction<HistorySample> f) {
        List<HistorySample> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(f.apply(i));
        }
        assertFalse(out.isEmpty());
        return out;
    }

    private static long since() {
        return NOW - DAY;
    }

    // 期望文案全部走消息层，避免硬编码语言
    private static String notCollected() {
        return Messages.get("daily.metrics.not-collected");
    }

    private static String insufficient() {
        return Messages.get("daily.metrics.gc-insufficient");
    }

    private static String gcLine(Object count, Object seconds) {
        return Messages.get("daily.metrics.gc", count, seconds);
    }

    /** 算不出增量时的外壳（量词模板套整句会成病句，所以有独立的 gc-state）。 */
    private static String gcState(Object why) {
        return Messages.get("daily.metrics.gc-state", why);
    }

    // ==================================================================================
    // 问题 1：-1 哨兵怎么呈现
    // ==================================================================================

    /**
     * 全窗口都是 -1 的 MSPT 尖峰：必须说"本窗口未采集"，且<b>绝不能出现 -1</b>。
     *
     * <p>这是本批最容易被写错的一条：{@code String.format("%.2f", -1.0)} 会乖乖打印
     * {@code "-1.00 ms"}，代码完全"正确"地运行，用户读到"最快的一次 tick 是 -1 毫秒"。</p>
     */
    @Test
    void allSentinelSpikeSaysNotCollectedAndNeverPrintsMinusOne() throws Exception {
        HistoryStore hs = history(samples(1, 0, i -> sample(NOW, -1, 30.0, 40.0, 10, 100)));
        HistorySample peak = hs.peakMsptSpikeSince(since());

        String line = DailySummaryService.spikeLine(peak, ZONE);

        assertTrue(line.contains(notCollected()),
            "a window with no spike data must say it was not collected, got: " + line);
        assertFalse(line.contains("-1"),
            "printing the -1 sentinel as a measurement is worse than printing nothing, got: " + line);
        assertFalse(line.contains("null"), "the sentinel must not leak as 'null', got: " + line);
    }

    /** 同一句里"未采集"与"0 ms"必须能区分：真的测到 0 不可能是健康结论，但也不能说成未采集。 */
    @Test
    void notCollectedWordingIsDistinctFromAnyRealValue() throws Exception {
        String missing = DailySummaryService.spikeLine(null, ZONE);
        String real = DailySummaryService.spikeLine(sample(NOW, 88.5, 30.0, 40.0, 10, 100), ZONE);

        assertNotNull(missing);
        assertFalse(missing.equals(real),
            "missing data and a real measurement must not render to the same text");
    }

    // ==================================================================================
    // 问题 2：混杂 -1 与真实值时统计量怎么算
    // ==================================================================================

    /**
     * 混合窗口：真实尖峰 1200ms 夹在一堆 -1 中间。最大值必须是 1200，不能是 0、也不能是 -1。
     *
     * <p>两种错法各有杀伤力：把 -1 当候选 → 最大值被哨兵压住；把 -1 当 0 → 0 输给 1200
     * 但会污染"最小值"类统计（询问"最差时刻"时静默落败，看起来像"从没尖峰过"）。</p>
     */
    @Test
    void mixedWindowTakesTheRealMaximumNotTheSentinel() throws Exception {
        List<HistorySample> s = new ArrayList<>();
        s.add(sample(NOW - 5_000_000, -1, 10.0, 20.0, 1, 10));
        s.add(sample(NOW - 4_000_000, 1200.0, 10.0, 20.0, 2, 20));   // 真实尖峰
        s.add(sample(NOW - 3_000_000, -1, 10.0, 20.0, 3, 30));
        s.add(sample(NOW - 2_000_000, -1, 10.0, 20.0, 4, 40));
        HistoryStore hs = history(s);

        HistorySample peak = hs.peakMsptSpikeSince(since());

        assertNotNull(peak, "the one real spike must be found among sentinels");
        assertEquals(1200.0, peak.msptMax(), 1e-9,
            "the spike maximum must come from the only real reading, not from a sentinel");
    }

    /**
     * 哨兵不能把最大值压低到 0：全 -1 窗口返回 null（而不是含 -1 或 0 的样本）。
     *
     * <p>返回一个 {@code msptMax = 0} 的样本会让调用方无从分辨"没数据"和"真的是 0ms"，
     * 那正是本项目禁止的形状。</p>
     */
    @Test
    void allSentinelWindowReturnsNullNotAZeroSample() throws Exception {
        HistoryStore hs = history(samples(4, 60_000,
            i -> sample(NOW - (3 - i) * 60_000L, -1, 10.0, 20.0, i, i * 10L)));

        assertNull(hs.peakMsptSpikeSince(since()),
            "no usable spike reading must be reported as null, not as a fake 0 ms peak");
    }

    // ==================================================================================
    // 问题 5：CPU 的 0.0 是合法读数，-1 才是未采集
    // ==================================================================================

    /**
     * 空载服务器（CPU 恒为 0.0）必须<b>测得到</b>——{@code 0.0} 不是哨兵。
     *
     * <p>若把 {@code <= 0} 当过滤口径（上面几条 helper 的写法），这台服务器会永远显示
     * "本窗口未采集"。用户在 CPU 面板看到盲区，会去怀疑采集坏了，而事实上 CPU 就是 0。</p>
     *
     * <p>断言按"CPU 那一段"取，不整行断言：GC 部分在这条用例里故意是空数据，
     * 那一段出现"未采集"是<b>正确</b>的，不该被本用例误判为失败。</p>
     */
    @Test
    void zeroCpuIsAValidReadingNotASentinel() throws Exception {
        HistoryStore hs = history(samples(3, 60_000,
            i -> sample(NOW - (2 - i) * 60_000L, 30.0, 0.0, 0.0, 10, 100)));

        HistorySample process = hs.peakProcessCpuSince(since());
        HistorySample system = hs.peakSystemCpuSince(since());

        assertNotNull(process, "0.0% is a real idle reading, not 'not collected'");
        assertNotNull(system, "0.0% is a real idle reading, not 'not collected'");
        assertEquals(0.0, process.cpuProcess(), 1e-9);
        assertEquals(0.0, system.cpuSystem(), 1e-9);

        String line = DailySummaryService.cpuGcLine(process, system, HistoryStore.GcDelta.NONE);
        // 只取 CPU 那一段（" | " 之前）：GC 在本用例里故意是空数据，那一段说"未采集"是对的
        String cpuPart = line.split(" \\| ", 2)[0];
        assertTrue(line.contains("0.0%"),
            "an idle server must show 0.0%, got: " + line);
        assertFalse(cpuPart.contains(notCollected()),
            "an idle server must not be reported as unmeasured, got: " + line);
    }

    /** 真正的 -1 才表示未采集；混在真实读数里也不能赢下最大值。 */
    @Test
    void cpuSentinelLosesToRealReadingButIsStillSkipped() throws Exception {
        List<HistorySample> s = new ArrayList<>();
        s.add(sample(NOW - 180_000, -1, 55.5, 12.5, 1, 10));
        s.add(sample(NOW - 120_000, -1, -1, -1, 2, 20));
        s.add(sample(NOW - 60_000, -1, 0.0, 0.0, 3, 30));
        HistoryStore hs = history(s);

        HistorySample process = hs.peakProcessCpuSince(since());
        HistorySample system = hs.peakSystemCpuSince(since());

        assertNotNull(process);
        assertEquals(55.5, process.cpuProcess(), 1e-9,
            "the peak must be the real reading; a -1 must never be treated as 0 or as a candidate");
        assertEquals(12.5, system.cpuSystem(), 1e-9);
    }

    /** 进程 CPU 与系统 CPU 各自独立判断未采集：一边有一边无时不能整行变成盲区。 */
    @Test
    void oneCpuSideMissingDoesNotBlindTheOther() throws Exception {
        HistorySample processOnly = sample(NOW, 30.0, 42.0, -1, 10, 100);
        String line = DailySummaryService.cpuGcLine(
            processOnly, null, HistoryStore.GcDelta.NONE);

        assertTrue(line.contains("42.0%"),
            "a measured process CPU must still be shown when system CPU is unavailable, got: " + line);
        assertTrue(line.contains(notCollected()),
            "the missing system CPU must be labelled as not collected, got: " + line);
    }

    // ==================================================================================
    // 问题 3：窗口内全是 -1（老 CSV / 采集被禁用）
    // ==================================================================================

    /** 全 -1 的 GC 窗口只能报"未采集"，绝不能报"0 次"——后者是伪造的健康结论。 */
    @Test
    void allSentinelGcWindowReportsNotCollectedNotZero() {
        HistoryStore.GcDelta gc = HistoryStore.GcDelta.of(
            samples(5, 60_000, i -> sample(NOW - (4 - i) * 60_000L, 10.0, 20.0, 30.0,
                HistorySample.NO_LONG, HistorySample.NO_LONG)), since());

        assertEquals(0, gc.validSamples(), "v1.3 CSV rows carry no GC columns");
        assertFalse(gc.measured(), "no GC data means the increase is not measurable");
        assertEquals(gcState(notCollected()), DailySummaryService.gcText(gc),
            "an all-sentinel window must say 'not collected', never '0 GCs'");
    }

    /** 一条样本 → 算不出增量，但也不能说"未采集"（数据确实在）。 */
    @Test
    void singleSampleGcIsInsufficientNotMissing() {
        HistoryStore.GcDelta gc = HistoryStore.GcDelta.of(
            samples(1, 0, i -> sample(NOW, 10.0, 20.0, 30.0, 17, 2_000)), since());

        assertEquals(1, gc.validSamples());
        assertFalse(gc.measured(), "one sample cannot yield an increase");
        assertEquals(gcState(insufficient()), DailySummaryService.gcText(gc),
            "'only one sample' and 'nothing was collected' are different facts");
    }

    /**
     * <b>可测且增量为 0</b> → "本窗口未发生 GC"，这是健康结论而非盲区。
     *
     * <p>与上一条互为正反面：三态里最容易合并的就是这两条，合并后"没采到"会被读成"很干净"。</p>
     */
    @Test
    void measuredZeroIncreaseIsAHealthStatementNotAMissingOne() {
        HistoryStore.GcDelta gc = HistoryStore.GcDelta.of(
            samples(3, 60_000, i -> sample(NOW - (2 - i) * 60_000L, 10.0, 20.0, 30.0, 42, 5_000)),
            since());

        assertTrue(gc.measured(), "two comparable samples make the increase measurable");
        assertEquals(0L, gc.countDelta());
        assertEquals(Messages.get("daily.metrics.no-gc"), DailySummaryService.gcText(gc));

        // 三态文本两两不同——这一条同时锁住"合并成一句"这种改法
        String missing = DailySummaryService.gcText(HistoryStore.GcDelta.NONE);
        String shortWindow = DailySummaryService.gcText(HistoryStore.GcDelta.of(
            samples(1, 0, i -> sample(NOW, 10.0, 20.0, 30.0, 42, 5_000)), since()));
        String healthy = DailySummaryService.gcText(gc);
        assertFalse(missing.equals(healthy), "missing != healthy");
        assertFalse(shortWindow.equals(healthy), "insufficient != healthy");
        assertFalse(missing.equals(shortWindow), "missing != insufficient");
    }

    // ==================================================================================
    // 问题 4：GC 是累计量 → 报增量；跨重启不能出负数
    // ==================================================================================

    /** 正常累计：增量 = 末值 - 首值，且必须以"次/秒"呈现而非绝对值。 */
    @Test
    void gcReportsTheWindowIncreaseNotTheRunningTotal() {
        List<HistorySample> s = new ArrayList<>();
        s.add(sample(NOW - 240_000, 10.0, 20.0, 30.0, 1_000, 60_000));   // 开机以来 1000 次
        s.add(sample(NOW - 180_000, 10.0, 20.0, 30.0, 1_002, 60_400));
        s.add(sample(NOW - 120_000, 10.0, 20.0, 30.0, 1_005, 61_100));
        s.add(sample(NOW - 60_000, 10.0, 20.0, 30.0, 1_007, 61_700));

        HistoryStore.GcDelta gc = HistoryStore.GcDelta.of(s, since());

        assertEquals(7L, gc.countDelta(), "the increase is 1007-1000, not the counter value 1007");
        assertEquals(1_700L, gc.timeDeltaMs(), "GC time increase is 61700-60000 ms");
        assertEquals(3, gc.increments(), "three adjacent pairs contributed");
        assertEquals(0, gc.resets(), "a monotonic counter has no resets");
        assertEquals(gcLine("7", "1.7"), DailySummaryService.gcText(gc));
    }

    /**
     * <b>跨进程重启的负增量</b>：计数器归零后重新涨，差值法会算出负数。
     *
     * <p>这是本任务最难的一条。窗口跨越一次重启（重启前 500 次 → 重启后 3 次），
     * "末值减首值"得 {@code 3 - 500 = -497}。正确处理是逐对累加、跳过回退的那一对：</p>
     * <ul>
     *   <li>重启前 {@code 505 - 500 = 5} 次；</li>
     *   <li>重启边界那一对跳过（{@code 3 < 505}，不同基，差值无意义）；</li>
     *   <li>重启后 {@code 8 - 3 = 5} 次。</li>
     * </ul>
     * <p>合计 10。注意<b>重启后的第一个值本身不算增量</b>——它是新进程的基线，
     * 不是"从零涨到 3"（我们并不知道重启发生在哪一刻，把它算进去就是凭空造数）。
     * 这一点容易被写成 {@code +3}，从而把同一个事实数两遍。</p>
     */
    @Test
    void gcCounterResetAcrossRestartNeverProducesANegativeIncrease() {
        List<HistorySample> s = new ArrayList<>();
        s.add(sample(NOW - 300_000, 10.0, 20.0, 30.0, 500, 30_000));   // 重启前
        s.add(sample(NOW - 240_000, 10.0, 20.0, 30.0, 505, 30_500));   // 重启前最后一拍
        s.add(sample(NOW - 180_000, 10.0, 20.0, 30.0, 3, 400));        // 重启后（计数器归零）
        s.add(sample(NOW - 120_000, 10.0, 20.0, 30.0, 8, 1_200));

        HistoryStore.GcDelta gc = HistoryStore.GcDelta.of(s, since());

        assertEquals(10L, gc.countDelta(),
            "5 (before restart) + 5 (after restart) = 10; naive last-minus-first would be -497");
        assertEquals(1_300L, gc.timeDeltaMs(),
            "500 (before, 30000 -> 30500) + 800 (after, 400 -> 1200) = 1300; must not be negative");
        assertTrue(gc.countDelta() >= 0, "a negative GC count is always a bug, never a reading");
        assertTrue(gc.timeDeltaMs() >= 0, "a negative GC time is always a bug, never a reading");
        assertEquals(1, gc.resets(), "the rollback must be counted as one restart");
        assertTrue(gc.measured(), "increments on both sides of the restart are still measurable");
        assertTrue(DailySummaryService.gcText(gc).contains(
            Messages.get("daily.metrics.gc-reset", 1)),
            "the summary must tell the operator a restart happened, otherwise the number looks partial");
    }

    /**
     * 计数与耗时<b>任一</b>回退即判重启：单独一个字段回退也说明换了进程。
     *
     * <p>只判 {@code gcCount} 会漏掉"次数没变但耗时归零"这种真实情形（某些收集器在
     * 重启前最后一拍就可能是 0 次 / 非 0 耗时）。</p>
     */
    @Test
    void resetIsDetectedWhenOnlyTheTimeCounterRollsBack() {
        List<HistorySample> s = new ArrayList<>();
        s.add(sample(NOW - 120_000, 10.0, 20.0, 30.0, 9, 9_000));
        s.add(sample(NOW - 60_000, 10.0, 20.0, 30.0, 9, 100));   // 次数不变，耗时回退

        HistoryStore.GcDelta gc = HistoryStore.GcDelta.of(s, since());

        assertEquals(1, gc.resets(), "a rollback in either counter means a new process");
        assertEquals(0L, gc.timeDeltaMs(), "the meaningless pair must be dropped, not summed as -8900");
        assertFalse(gc.measured(), "dropping the only pair leaves nothing measurable");
    }

    /** 连续多次重启：每次都被识别，增量仍然只累加可信的相邻对。 */
    @Test
    void repeatedRestartsAreAllCountedAndStillNeverNegative() {
        List<HistorySample> s = new ArrayList<>();
        s.add(sample(NOW - 300_000, 10.0, 20.0, 30.0, 100, 10_000));
        s.add(sample(NOW - 240_000, 10.0, 20.0, 30.0, 104, 10_400));   // +4 / +400
        s.add(sample(NOW - 180_000, 10.0, 20.0, 30.0, 1, 90));         // reset 1
        s.add(sample(NOW - 120_000, 10.0, 20.0, 30.0, 6, 690));        // +5 / +600
        s.add(sample(NOW - 60_000, 10.0, 20.0, 30.0, 2, 100));         // reset 2

        HistoryStore.GcDelta gc = HistoryStore.GcDelta.of(s, since());

        assertEquals(9L, gc.countDelta(), "4 + 5 = 9");
        assertEquals(1_000L, gc.timeDeltaMs(), "400 + 600 = 1000");
        assertEquals(2, gc.resets());
    }

    /** 只有重启、没有任何可比对 → 不可测（不能说成 0 次 GC）。 */
    @Test
    void windowWithOnlyARestartIsNotMeasurable() {
        List<HistorySample> s = new ArrayList<>();
        s.add(sample(NOW - 60_000, 10.0, 20.0, 30.0, 900, 50_000));
        s.add(sample(NOW, 10.0, 20.0, 30.0, 1, 100));

        HistoryStore.GcDelta gc = HistoryStore.GcDelta.of(s, since());

        assertEquals(1, gc.resets());
        assertFalse(gc.measured());
        assertEquals(gcState(insufficient()), DailySummaryService.gcText(gc),
            "a window that only contains a restart has no measurable increase");
    }

    /** -1 行不参与配对：跳过它不会制造一次"假重启"，也不会让增量凭空多一段。 */
    @Test
    void sentinelRowsAreSkippedWithoutFakingARestart() {
        List<HistorySample> s = new ArrayList<>();
        s.add(sample(NOW - 300_000, 10.0, 20.0, 30.0, 50, 5_000));
        s.add(sample(NOW - 240_000, 10.0, 20.0, 30.0,
            HistorySample.NO_LONG, HistorySample.NO_LONG));   // GC 模块被禁用的一拍
        s.add(sample(NOW - 180_000, 10.0, 20.0, 30.0, 53, 5_300));

        HistoryStore.GcDelta gc = HistoryStore.GcDelta.of(s, since());

        assertEquals(2, gc.validSamples(), "only rows carrying GC data count");
        assertEquals(0, gc.resets(), "a missing reading is not a counter reset");
        assertEquals(3L, gc.countDelta(), "the increase spans the gap: 53-50");
        assertEquals(300L, gc.timeDeltaMs());
    }

    /** 窗口起点之前的样本不得参与：否则增量会带上一天以前的历史。 */
    @Test
    void samplesBeforeTheWindowAreIgnored() {
        List<HistorySample> s = new ArrayList<>();
        s.add(sample(NOW - DAY - 120_000, 10.0, 20.0, 30.0, 5, 500));   // 窗口外
        s.add(sample(NOW - 300_000, 10.0, 20.0, 30.0, 100, 10_000));
        s.add(sample(NOW - 60_000, 10.0, 20.0, 30.0, 103, 10_300));

        HistoryStore.GcDelta gc = HistoryStore.GcDelta.of(s, since());

        assertEquals(2, gc.validSamples());
        assertEquals(3L, gc.countDelta(), "5 -> 100 happened before the window and must not count");
    }

    // ==================================================================================
    // 汇总行：三态与两种错法一起锁
    // ==================================================================================

    /**
     * 整行在"全盲"状态下的形态：三处都写未采集，且不含任何伪造的 0。
     *
     * <p>这是用户最可能遇到的场景（刚升级、旧 CSV 还没有新列），必须一看就懂"没数据"，
     * 而不是"一切正常"。</p>
     */
    @Test
    void fullyBlindWindowRendersThreeExplicitGaps() {
        HistoryStore.GcDelta none = HistoryStore.GcDelta.of(List.of(
            sample(NOW, -1, -1, -1, HistorySample.NO_LONG, HistorySample.NO_LONG)), since());

        String spike = DailySummaryService.spikeLine(null, ZONE);
        String cpuGc = DailySummaryService.cpuGcLine(null, null, none);

        assertTrue(spike.contains(notCollected()), spike);
        assertTrue(cpuGc.contains(notCollected()), cpuGc);
        // 三处"未采集"（进程 CPU、系统 CPU、GC）——少一处就说明有分支把 null 渲染成了数字
        assertEquals(3, countOccurrences(cpuGc, notCollected()),
            "process CPU, system CPU and GC must each be labelled, got: " + cpuGc);
        assertFalse(cpuGc.contains("0.0%"), "a blind window must not show a fabricated 0.0%, got: " + cpuGc);
        assertFalse(spike.contains("-1"), spike);
    }

    /** 全健康的窗口：数值都在，且不该出现"未采集"。 */
    @Test
    void fullyMeasuredWindowShowsRealNumbers() {
        HistorySample spike = sample(NOW, 92.25, 61.5, 33.0, 500, 20_000);
        List<HistorySample> s = new ArrayList<>();
        s.add(sample(NOW - 120_000, 40.0, 20.0, 10.0, 490, 19_000));
        s.add(sample(NOW, 92.25, 61.5, 33.0, 500, 20_000));

        String line = DailySummaryService.spikeLine(spike, ZONE)
            + " / " + DailySummaryService.cpuGcLine(spike, spike, HistoryStore.GcDelta.of(s, since()));

        assertTrue(line.contains("92.25"), "the spike value must be printed in ms, got: " + line);
        assertTrue(line.contains("61.5%"), "process CPU peak must be printed, got: " + line);
        assertTrue(line.contains("33.0%"), "system CPU peak must be printed, got: " + line);
        assertTrue(line.contains("10"), "the GC increase (500-490) must be printed, got: " + line);
        assertFalse(line.contains(notCollected()), "a fully measured window has no gaps, got: " + line);
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int n = 0;
        int i = -1;
        while ((i = haystack.indexOf(needle, i + 1)) >= 0) {
            n++;
        }
        return n;
    }

    // ==================================================================================
    // HistoryStore 侧的接线（其余 helper 的既有语义不能被顺手改坏）
    // ==================================================================================

    /** 空环 / 全窗口外：新 helper 一律返回 null，不抛异常（日报每次都会调它们）。 */
    @Test
    void newHelpersAreNullSafeOnAnEmptyRing() throws Exception {
        HistoryStore empty = history(List.of());
        assertNull(empty.peakMsptSpikeSince(since()));
        assertNull(empty.peakProcessCpuSince(since()));
        assertNull(empty.peakSystemCpuSince(since()));
        assertEquals(HistoryStore.GcDelta.NONE, empty.gcDeltaSince(since()));
        assertFalse(empty.gcDeltaSince(since()).measured());
    }

    /** history.enabled=false 时全部读取路径为空——盲区必须显式，不能靠环里碰巧还有数据。 */
    @Test
    void disabledHistoryYieldsNoDataFromNewHelpers() throws Exception {
        ConfigManager cm = HistoryFixtures.config(cfg -> cfg.history_enabled = false);
        HistoryStore hs = HistoryFixtures.seeded(cm,
            List.of(sample(NOW, 92.0, 50.0, 40.0, 9, 900)));

        assertNull(hs.peakMsptSpikeSince(since()));
        assertNull(hs.peakProcessCpuSince(since()));
        assertNull(hs.peakSystemCpuSince(since()));
        assertFalse(hs.gcDeltaSince(since()).measured());
    }

    /** 老 helper 不能因为新 helper 而改变口径（heap/entities 仍然跳过 0）。 */
    @Test
    void existingHelpersKeepTheirZeroSkippingRules() throws Exception {
        HistoryStore hs = history(samples(2, 60_000,
            i -> new HistorySample(NOW - (1 - i) * 60_000L, 20.0, 12.0, 0, 5, 0, 0)));

        assertNull(hs.peakHeapSince(since()), "heap 0 is still a missing reading");
        assertNull(hs.peakEntitiesSince(since()), "entities 0 is still a missing reading");
    }

    // ==================== 日报里的 TPS 0 不能当哨兵 ====================

    /**
     * TPS 的 {@code 0} 是真实读数，日报的 p95 必须把它算进去。
     *
     * <p><b>为什么</b>：{@code tps1m} 没有 -1 哨兵——采集层直接写 {@code Bukkit.getTPS()}，
     * 而 TPS 不可用时的快照整条不入历史环（{@code HistoryStore} 的污染防护）。所以环里的
     * {@code 0} 只可能是"服务器真的冻结了"，是最坏的真实读数。</p>
     *
     * <p><b>滤掉它会怎样</b>：p95 偏高，日报看起来比实际健康。窗口里如果有一半样本是 0，
     * 真实 p95 应该是 0；滤掉之后会显示成那些非零样本的高分位数，读者完全看不到卡死。</p>
     */
    @Test
    void percentileCountsTpsZeroAsARealReading() throws Exception {
        // 10 条样本，5 条 0（卡死）、5 条 20.0（正常）
        List<HistorySample> window = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            window.add(new HistorySample(NOW - (9 - i) * 60_000L, i < 5 ? 0.0 : 20.0,
                12.0, 50, 5, 100, 500));
        }

        double p95 = percentile(window, 0.95);
        assertEquals(20.0, p95, 0.001,
            "with half the window at 0 TPS, the p95 of the full set is 20.0; "
                + "dropping the zeros would still give 20.0 here, so this alone is not proof");

        // 关键对比：全是 0 的窗口，p95 必须是 0，而不是"无有效样本"的 -1
        List<HistorySample> allZero = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            allZero.add(new HistorySample(NOW - (4 - i) * 60_000L, 0.0, 12.0, 50, 5, 100, 500));
        }
        assertEquals(0.0, percentile(allZero, 0.95), 0.001,
            "a fully frozen window has p95 = 0; returning -1 (\"no valid samples\") hides total failure");
    }

    /**
     * 超阈时长必须把 TPS 0 的时段算进去。
     *
     * <p>一台卡死 20 分钟的服务器，那 20 分钟当然"低于告警阈值"。滤掉 0 会让这 20 分钟
     * 从统计里消失，日报报"超阈 0 分钟"——把最严重的情况报成最健康。</p>
     */
    @Test
    void overThresholdMinutesCountsZeroTpsIntervals() throws Exception {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.tps_warningThreshold = 18.0;

        // 10 条样本、每 60s，全部 tps=0（整段卡死）
        List<HistorySample> window = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            window.add(new HistorySample(NOW - (9 - i) * 60_000L, 0.0, 12.0, 50, 5, 100, 500));
        }

        int minutes = overThresholdMinutes(window, cfg);
        assertTrue(minutes >= 9,
            "10 samples 60s apart at 0 TPS is ~9 minutes below the warning line; "
                + "reporting 0 means the worst case reads as the healthiest; got: " + minutes);
    }

    // ==================== 反射直连两个私有纯函数 ====================

    private static double percentile(List<HistorySample> samples, double q) throws Exception {
        var m = DailySummaryService.class.getDeclaredMethod("percentile", List.class, double.class);
        m.setAccessible(true);
        return (Double) m.invoke(null, samples, q);
    }

    private static int overThresholdMinutes(List<HistorySample> samples, ConfigManager.ConfigData cfg)
        throws Exception {
        var m = DailySummaryService.class.getDeclaredMethod(
            "overThresholdMinutes", List.class, ConfigManager.ConfigData.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, samples, cfg);
    }
}
