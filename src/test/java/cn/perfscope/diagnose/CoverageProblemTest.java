/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.diagnose;

import cn.perfscope.history.HistorySample;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * f-s03-logic-04：coverageProblem 的中位间隔。
 *
 * <p>偶数个 gaps 必须取中间两值的均值；旧实现取上中位数 gaps[L/2]，
 * 会把「一半很短、一半很长」的窗口误判为覆盖不足（只产生 skip，不产生误报，
 * 故审计标 LOW）。本类同时锁住两个既有拦截，防止修中位数时把它们削弱。</p>
 */
class CoverageProblemTest {

    private static String coverage(List<Long> gaps, long windowMs, int minSamples) throws Exception {
        List<HistorySample> w = new ArrayList<>();
        long t = 1_700_000_000_000L;
        w.add(new HistorySample(t, 20.0, 8.0, 50, 1, 10, 100));
        for (long g : gaps) {
            t += g;
            w.add(new HistorySample(t, 20.0, 8.0, 50, 1, 10, 100));
        }
        Method m = CoreRules.class.getDeclaredMethod("coverageProblem",
            List.class, long.class, int.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, w, windowMs, minSamples, "history");
    }

    private static List<Long> gaps(long... values) {
        List<Long> out = new ArrayList<>();
        for (long v : values) out.add(v);
        return out;
    }

    @Test
    void skewedWindowPassesWithTrueMedian() throws Exception {
        // 1,1,1,100,100,100 秒 -> 真中位数 (1+100)/2=50.5s，旧上中位数=100s
        assertNull(coverage(gaps(1_000L, 1_000L, 1_000L, 100_000L, 100_000L, 100_000L),
            30 * 60_000L, 5));
    }

    @Test
    void samplesBelowMinimumStillSkip() throws Exception {
        assertEquals("diag.skip.coverage-samples",
            coverage(gaps(60_000L, 60_000L), 30 * 60_000L, 5));
    }

    @Test
    void oneSecondClusterStillSkip() throws Exception {
        assertEquals("diag.skip.coverage-span",
            coverage(gaps(1_000L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L),
                30 * 60_000L, 5));
    }

    @Test
    void evenlySpacedWindowStillPasses() throws Exception {
        assertNull(coverage(gaps(60_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L,
            60_000L, 60_000L, 60_000L), 30 * 60_000L, 10));
    }
}
