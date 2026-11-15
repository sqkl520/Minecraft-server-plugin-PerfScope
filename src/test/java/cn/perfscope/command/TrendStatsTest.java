/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.command;

import cn.perfscope.history.HistorySample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /perfscope trend} 的窗口统计（{@link PerfScopeCommand#computeTrendStats}）。
 *
 * <h2>为什么这个类存在</h2>
 *
 * <p>这段逻辑原本内联在 {@code showTrend} 里，60 行手抄五个判据，<b>零测试</b>。
 * 一轮可维护性审计实测：把其中任意一条改坏，505 个用例全绿。也就是说
 * v1.4.2 / v1.4.3 修的"把最坏报成最好"那类 bug，<b>在命令层可以原样复发</b>——
 * 库层的 {@code HistoryStore} helper 修过测过，命令层这份手抄的没有。</p>
 *
 * <p>抽成纯静态函数后用三条样本就能驱动断言，与 {@code HistoryStore.gapsOf} 同一思路。</p>
 */
class TrendStatsTest {

    private static final long STEP = 60_000L;
    private static final long BASE = 1_760_000_000_000L;

    /** 一条样本：只关心用于统计的那几个字段。 */
    private static HistorySample s(int i, double tps, int heap, double msptMax,
                                   double cpuProcess, long gcCount, long gcTime) {
        return new HistorySample(BASE + i * STEP, tps, 12.0, heap, 5, 100, 500,
            msptMax, cpuProcess, cpuProcess / 2, gcCount, gcTime);
    }

    // ==================== 最小 TPS：0 是最差，不是缺失 ====================

    /**
     * {@code tps1m == 0} 必须参与"最低 TPS"。
     *
     * <p>TPS 没有 -1 哨兵（采集层直接写 {@code Bukkit.getTPS()}，TPS 不可用时的快照整条不入环），
     * 所以 {@code 0} 是真实的"服务器冻结"——最该被看见的那个值。若把它滤掉，trend 会报
     * "最低 18.5"而实际卡死过，<b>直接伪造历史</b>。</p>
     */
    @Test
    void minTpsCountsZeroBecauseZeroIsTheWorstReading() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 19.9, 60, 20, 30, 10, 100),
            s(1, 0.0, 61, 20, 30, 10, 100),     // 卡死
            s(2, 18.5, 62, 20, 30, 10, 100)));

        assertEquals(3, st.tpsCount(), "all three samples are valid TPS readings");
        assertEquals(0.0, st.minTps(), 1e-9,
            "0 TPS is a frozen server - the single worst moment. Filtering it means "
                + "'lowest TPS' can never report a crash");
        assertNotNull(st.minTpsAt());
        assertEquals(BASE + STEP, st.minTpsAt().timestamp(), "the timestamp must point at the frozen sample");
    }

    /** 负哨兵必须被排除（-1 才是"未采集"）。 */
    @Test
    void minTpsSkipsNegativeSentinel() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, -1.0, 60, 20, 30, 10, 100),    // 哨兵
            s(1, 18.0, 61, 20, 30, 10, 100)));

        assertEquals(1, st.tpsCount());
        assertEquals(18.0, st.minTps(), 1e-9, "-1 is 'not collected', not a reading");
    }

    /** 物理上不可能的上界（> 20.5）也排除，但它不该把整条记录成 0。 */
    @Test
    void tpsAboveCeilingIsExcludedFromTheAverage() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 25.0, 60, 20, 30, 10, 100),    // 不可能读数
            s(1, 19.0, 61, 20, 30, 10, 100)));

        assertEquals(1, st.tpsCount());
        assertEquals(19.0, st.sumTps(), 1e-9);
    }

    // ==================== 峰值类：必须真的是最大 ====================

    /** 堆峰值取最大，不是第一个、不是最小。 */
    @Test
    void peakHeapIsTheMaximum() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 30, 20, 30, 10, 100),
            s(1, 20, 95, 20, 30, 10, 100),      // 峰值
            s(2, 20, 40, 20, 30, 10, 100)));

        assertEquals(95, st.peakHeap());
        assertEquals(BASE + STEP, st.peakHeapAt().timestamp());
    }

    /** MSPT 尖峰取最大，且 {@code msptMax <= 0}（未采集）不参与比较。 */
    @Test
    void peakMsptIsTheMaximumAndSkipsNotCollected() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 60, 100.0, 30, 10, 100),
            s(1, 20, 60, 500.0, 30, 10, 100),   // 最严重
            s(2, 20, 60, -1.0, 30, 10, 100)));  // 未采集

        assertNotNull(st.peakMsptAt());
        assertEquals(500.0, st.peakMsptAt().msptMax(), 1e-9,
            "the worst single tick must win; a -1 'not collected' must not be compared as 0");
    }

    /**
     * {@code msptMax == 0} 同样算"未采集"，不能参与峰值比较。
     *
     * <p><b>这条是补上去的</b>：上面那条用的是 {@code -1}，而 {@code -1} 对
     * {@code > 0} 与 {@code >= 0} 两种写法<b>都能排除</b>——所以把判据从 {@code > 0}
     * 改成 {@code >= 0} 时它不会红（变异验证实测 SURVIVED）。</p>
     *
     * <p>{@code 0} 才是这两者的分界：MSPT 的 {@code 0} 不可能是一次真实 tick 的耗时
     * （每 tick 至少要花可测量的时间），出现 0 只可能是 TPS 反推路径或 avg-only 路径
     * 留下的"无数据"。若允许它参与比较，一个无数据的 0 会永远赢不了真实尖峰，
     * 于是"从没尖峰过"和"没采到尖峰"变得同形。</p>
     */
    @Test
    void zeroMsptIsTreatedAsNotCollectedForThePeak() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 60, 0.0, 30, 10, 100),     // 无数据，不是"最快的一次 tick"
            s(1, 20, 60, 0.0, 30, 10, 100)));

        assertNull(st.peakMsptAt(),
            "all-zero MSPT means no spike data at all; returning a sample here would let a "
                + "'not collected' 0 masquerade as a real reading");
    }

    /** 0 与真实尖峰混合时，0 不得参与比较（峰值仍是真实那个）。 */
    @Test
    void zeroMsptLosesToARealSpikeRatherThanTyingIt() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 60, 0.0, 30, 10, 100),     // 无数据
            s(1, 20, 60, 250.0, 30, 10, 100))); // 真实尖峰

        assertNotNull(st.peakMsptAt());
        assertEquals(250.0, st.peakMsptAt().msptMax(), 1e-9);
    }

    /** CPU 峰值取最大，且 {@code 0.0}（空载）是合法读数。 */
    @Test
    void peakCpuIsTheMaximumAndZeroIsARealReading() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 60, 20, 0.0, 10, 100),     // 空载：合法
            s(1, 20, 60, 20, 80.0, 10, 100),    // 峰值
            s(2, 20, 60, 20, -1.0, 10, 100)));  // 未采集

        assertNotNull(st.peakCpuAt());
        assertEquals(80.0, st.peakCpuAt().cpuProcess(), 1e-9);
    }

    /** 全是空载（CPU 恒 0）时必须仍给出峰值 0，而不是"无数据"。 */
    @Test
    void allIdleCpuStillYieldsAPeakOfZero() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 60, 20, 0.0, 10, 100),
            s(1, 20, 60, 20, 0.0, 10, 100)));

        assertNotNull(st.peakCpuAt(),
            "an idle server has CPU 0, which is a real reading - reporting 'no data' hides the healthiest state");
        assertEquals(0.0, st.peakCpuAt().cpuProcess(), 1e-9);
    }

    // ==================== GC：逐对累加，跨重启不产生负数 ====================

    /** GC 报的是窗口增量，不是累计值。 */
    @Test
    void gcReportsTheWindowIncreaseNotTheRunningTotal() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 60, 20, 30, 100, 1000),
            s(1, 20, 60, 20, 30, 107, 1700)));

        assertTrue(st.gcSeen());
        assertEquals(7L, st.gcCountDelta());
        assertEquals(700L, st.gcTimeDeltaMs());
    }

    /**
     * 跨重启（计数器归零）不得产生负数，且重启前的增量不丢。
     *
     * <p>静态差 {@code last - first} 会算出负数（500 → 3 = -497）；逐对累加跳过回退那一对，
     * 把基线更新到新值，于是 0→500 那段仍然计入。</p>
     */
    @Test
    void gcRestartNeverProducesANegativeIncrease() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 60, 20, 30, 200, 2000),
            s(1, 20, 60, 20, 30, 500, 5000),    // 重启前：+300 / +3000
            s(2, 20, 60, 20, 30, 3, 30),        // 重启后：计数器归零
            s(3, 20, 60, 20, 30, 10, 100)));    // 重启后：+7 / +70

        assertTrue(st.gcSeen());
        assertEquals(307L, st.gcCountDelta(),
            "300 before the restart + 7 after it; a naive last-first would give 10-200 = -190");
        assertEquals(3070L, st.gcTimeDeltaMs());
    }

    /** 只有一条样本时无法算增量 → 标记为"样本不足"，而不是报 0。 */
    @Test
    void singleSampleCannotMeasureAnIncrease() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 60, 20, 30, 100, 1000)));

        assertTrue(st.gcAnyRecorded(), "the sample does carry GC data");
        assertFalse(st.gcSeen(), "but one sample cannot yield an increase - that is 'insufficient', not zero");
        assertEquals(0L, st.gcCountDelta());
    }

    /** 完全没 GC 数据时标记为"未记录"。 */
    @Test
    void noGcDataIsDistinctFromZeroIncrease() {
        var st = PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 60, 20, 30, HistorySample.NO_LONG, HistorySample.NO_LONG),
            s(1, 20, 60, 20, 30, HistorySample.NO_LONG, HistorySample.NO_LONG)));

        assertFalse(st.gcAnyRecorded(), "no sample carries GC data");
        assertFalse(st.gcSeen());
    }

    // ==================== 边界 ====================

    /** 空列表不崩，且各项为"无数据"。 */
    @Test
    void emptyInputYieldsNoDataNotGarbage() {
        var st = PerfScopeCommand.computeTrendStats(List.of());

        assertEquals(0, st.tpsCount());
        assertNull(st.minTpsAt());
        assertNull(st.peakHeapAt());
        assertNull(st.peakMsptAt());
        assertNull(st.peakCpuAt());
        assertFalse(st.gcSeen());
    }

    /** 让"峰值"落在首位、末位、中间都取对（防止"返回第一个"蒙对）。 */
    @Test
    void peakIsFoundAtAnyPosition() {
        assertEquals(99, PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 99, 20, 30, 1, 10), s(1, 20, 50, 20, 30, 1, 10),
            s(2, 20, 10, 20, 30, 1, 10))).peakHeap(), "peak first");

        assertEquals(99, PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 10, 20, 30, 1, 10), s(1, 20, 50, 20, 30, 1, 10),
            s(2, 20, 99, 20, 30, 1, 10))).peakHeap(), "peak last");

        assertEquals(99, PerfScopeCommand.computeTrendStats(List.of(
            s(0, 20, 10, 20, 30, 1, 10), s(1, 20, 99, 20, 30, 1, 10),
            s(2, 20, 50, 20, 30, 1, 10))).peakHeap(), "peak middle");
    }
}
