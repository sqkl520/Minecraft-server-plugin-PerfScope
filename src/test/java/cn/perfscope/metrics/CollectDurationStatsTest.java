/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集耗时统计的分子分母必须同源。
 *
 * <p><b>修的是什么 bug</b>：超阈次数原本来自 {@code overBudgetCycles}（只增不减的累计值），
 * 而样本数来自 {@code collectDurations}（上限 300 的滑窗）。两个分母不同，于是跑得越久
 * 比例越离谱——累计 50 次 ÷ 300 窗口样本 = 16.7%，而实际可能全是早期的、当前早已不再发生。
 * 用户看到的是"越跑越糟"的假象。</p>
 *
 * <p>修法：{@code collectDurationStats()} 从窗口里现算超阈数，分子分母永远同源。</p>
 */
class CollectDurationStatsTest {

    private static final long WARN_MS = 50L;   // 与 COLLECT_DURATION_WARN_MS 一致

    /** 构造只带 collectDurations / collectDurationsCount 两个字段的对象（绕过需要 Plugin 的构造器）。 */
    private static Object allocate() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);

        Object collector = unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(unsafe, MetricsCollector.class);

        // 用 Field.set 而不是 Unsafe.putObject/objectFieldOffset：后两者自 JDK 24 起被
        // terminal deprecation（--sun-misc-unsafe-memory-access=deny 立刻抛异常），
        // 而它们的调用方正是「写 final 实例字段」——Field.set 完全等价且三个 JDK 都支持。
        Field durationsField = MetricsCollector.class.getDeclaredField("collectDurations");
        durationsField.setAccessible(true);
        durationsField.set(collector, new java.util.concurrent.ConcurrentLinkedDeque<>());

        Field countField = MetricsCollector.class.getDeclaredField("collectDurationsCount");
        countField.setAccessible(true);
        countField.setInt(collector, 0);

        return collector;
    }

    /** 按生产逻辑追加一个耗时样本。 */
    @SuppressWarnings("unchecked")
    private static void push(Object collector, long ms) throws Exception {
        Field durationsField = MetricsCollector.class.getDeclaredField("collectDurations");
        durationsField.setAccessible(true);
        Deque<Long> d = (Deque<Long>) durationsField.get(collector);

        Field countField = MetricsCollector.class.getDeclaredField("collectDurationsCount");
        countField.setAccessible(true);
        Field windowField = MetricsCollector.class.getDeclaredField("COLLECT_DURATION_WINDOW");
        windowField.setAccessible(true);
        int window = windowField.getInt(null);

        d.addLast(ms);
        int c = countField.getInt(collector) + 1;
        while (c > window) { d.pollFirst(); c--; }
        countField.setInt(collector, c);
    }

    private static long[] stats(Object collector) throws Exception {
        Method m = MetricsCollector.class.getDeclaredMethod("collectDurationStats");
        m.setAccessible(true);
        return (long[]) m.invoke(collector);
    }

    @Test
    void emptyWindowReportsNoSamplesAndNoOvers() throws Exception {
        long[] s = stats(allocate());
        assertEquals(0L, s[3], "sample count");
        assertEquals(0L, s[4], "over-budget count must be 0 when there are no samples");
    }

    @Test
    void oversAreCountedWithinTheWindowOnly() throws Exception {
        Object c = allocate();
        // 100 个正常周期 + 3 个超阈周期
        for (int i = 0; i < 100; i++) push(c, 10L);
        for (int i = 0; i < 3; i++) push(c, WARN_MS + 10);
        long[] s = stats(c);
        assertEquals(103L, s[3]);
        assertEquals(3L, s[4], "exactly the 3 over-budget samples must be counted");
    }

    @Test
    void oldOversSlideOutOfTheWindow() throws Exception {
        Object c = allocate();
        int window = 300;

        // 先塞 10 个超阈周期，再塞 window 个正常周期——那 10 个应当全部滑出窗口。
        for (int i = 0; i < 10; i++) push(c, WARN_MS + 100);
        for (int i = 0; i < window; i++) push(c, 5L);

        long[] s = stats(c);
        assertEquals(window, s[3], "window must be saturated");
        assertEquals(0L, s[4],
            "early overs must have slid out: this is the exact bug the counter version had");
    }

    @Test
    void ratioNeverExceedsOne() throws Exception {
        // 分母同源的直接推论：超阈数不可能大于样本数。
        // 累计计数器版本在这个场景下会给出 10/300 而窗口里只剩 0 个。
        Object c = allocate();
        for (int i = 0; i < 400; i++) push(c, i % 7 == 0 ? WARN_MS + 1 : 3L);
        long[] s = stats(c);
        assertTrue(s[4] <= s[3], "over-budget count (" + s[4] + ") cannot exceed samples (" + s[3] + ")");
    }
}
