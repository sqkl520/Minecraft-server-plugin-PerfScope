/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetricsCollector#reduceTickTimes(long[])} —— Paper MSPT 一级路径的规约内核。
 *
 * <h2>为什么这个函数值得单独测</h2>
 * 它是 MSPT 一级路径（{@code CraftServer.getTickTimes()}）的<b>全部算法</b>，28 行。
 * 抽取之前它在 {@code collectMSPT_Paper} 里，而那方法前两行是
 * {@code Bukkit.getServer()} 与反射 {@code invoke}——于是整条路因为"前两行测不了"
 * 连算法一起失去了保护，此前零测试。
 *
 * <p>抽成纯函数后输入只剩 {@code long[]}，问题从"怎么造一个 CraftServer"变成"怎么造一个数组"。</p>
 *
 * <h2>这个函数里最容易改坏的三处</h2>
 * 三处改坏后都<b>不报错、不崩溃</b>，只是让 MSPT 数字变得不诚实：
 *
 * <ol>
 *   <li><b>{@code t <= 0} 的跳过条件</b>：改成 {@code t < 0}，未填充槽位 0 会被计入 min，
 *       于是 min 恒为 0 → 展示层（{@code msptMin <= 0} 判"未采集"）把<b>有效极值渲染成"未采集"</b>。
 *       这是本项目最怕的"把有数据报成没数据"。</li>
 *   <li><b>{@code n == 0} 的哨兵</b>：三个值必须是 {@code -1} 而不是 0。MSPT 里 0 是合法读数
 *       （一个 tick 几乎没耗时），用它表示"没采到"会让"服务器极快"与"采不到"无法区分。</li>
 *   <li><b>除以 1e6 的三处</b>：avg/min/max 各自除一次，漏改一处会得到量级差 1000 的混排
 *       （min 是毫秒、max 是纳秒）。</li>
 * </ol>
 */
class MsptReduceTest {

    private static final long MS_IN_NANOS = 1_000_000L;

    // ------------------------------------------------------------------------------------
    // 1. 正常路径
    // ------------------------------------------------------------------------------------

    /** 一组真实量级的样本：规约出正确的 avg / min / max。 */
    @Test
    void reducesToMillisCorrectly() {
        // 5ms、10ms、15ms（纳秒）
        long[] arr = {5 * MS_IN_NANOS, 10 * MS_IN_NANOS, 15 * MS_IN_NANOS};
        MetricsCollector.MsptStats s = MetricsCollector.reduceTickTimes(arr);

        assertEquals(10.0, s.avgMs(), 1e-9, "平均 = 30/3 = 10ms");
        assertEquals(5.0, s.minMs(), 1e-9);
        assertEquals(15.0, s.maxMs(), 1e-9);
        assertEquals(3, s.validSamples());
        assertTrue(s.hasSamples());
    }

    /** 单个样本：avg = min = max。 */
    @Test
    void singleSampleMakesAllThreeEqual() {
        MetricsCollector.MsptStats s =
            MetricsCollector.reduceTickTimes(new long[]{20 * MS_IN_NANOS});
        assertEquals(20.0, s.avgMs(), 1e-9);
        assertEquals(20.0, s.minMs(), 1e-9);
        assertEquals(20.0, s.maxMs(), 1e-9);
        assertEquals(1, s.validSamples());
    }

    /** 单位确实是纳秒→毫秒，量级不能差 1000。 */
    @Test
    void unitIsNanosToMillisNotMicros() {
        MetricsCollector.MsptStats s =
            MetricsCollector.reduceTickTimes(new long[]{50 * MS_IN_NANOS});
        assertEquals(50.0, s.avgMs(), 1e-9,
            "50ms 的 tick 在纳秒里是 50_000_000；若按微秒换算会得到 50000.0——"
                + "量级差 1000 会让一个健康的服务器看起来慢了三个数量级");
    }

    // ------------------------------------------------------------------------------------
    // 2. 未填充槽位（0 与负数）必须被跳过，不能拉低 min
    // ------------------------------------------------------------------------------------

    /**
     * <b>0 必须被跳过，不能计入 min。</b>
     *
     * <p>{@code getTickTimes()} 的数组是固定长度的环形缓冲，服务器刚启动时大部分槽位是 0
     * （"这段窗口还没 tick"）。如果把 0 当样本：min 恒为 0 → 展示层判"未采集"，
     * 而实际上 avg/max 都是有值的真实数据。</p>
     */
    @Test
    void zeroSlotsDoNotDragMinDown() {
        long[] arr = {0, 2 * MS_IN_NANOS, 0, 4 * MS_IN_NANOS, 0};
        MetricsCollector.MsptStats s = MetricsCollector.reduceTickTimes(arr);

        assertEquals(2.0, s.minMs(), 1e-9,
            "未填充槽位 0 必须被跳过：若计入，min 恒为 0，而展示层用 msptMin <= 0 判「未采集」，"
                + "于是有效极值被渲染成「没数据」");
        assertEquals(3.0, s.avgMs(), 1e-9, "avg 的分母只数有效样本：(2+4)/2 = 3");
        assertEquals(2, s.validSamples(),
            "validSamples 必须是 2 而不是 5——分母错了 avg 会偏，而且样本均匀时看不出来");
    }

    /** 负数槽位同样跳过（比 0 更不可能但要让条件覆盖）。 */
    @Test
    void negativeSlotsAreSkipped() {
        long[] arr = {-1, 3 * MS_IN_NANOS, Long.MIN_VALUE};
        MetricsCollector.MsptStats s = MetricsCollector.reduceTickTimes(arr);
        assertEquals(3.0, s.avgMs(), 1e-9, "负值与 Long.MIN_VALUE 都必须跳过");
        assertEquals(1, s.validSamples());
    }

    // ------------------------------------------------------------------------------------
    // 3. 无样本 → -1 哨兵（不是 0）
    // ------------------------------------------------------------------------------------

    /**
     * 空数组 / null / 全零 → 三个值都是 {@code -1}，{@code validSamples == 0}。
     *
     * <p><b>为什么必须是 -1 而不是 0</b>：MSPT 里 0 是合法读数——一个 tick 只花几十微秒时
     * 舍入到两位小数就是 0.0。用 0 表示"没采到"会让这两种完全不同的情况无法区分，
     * 而区分它们正是整个哨兵约定的存在理由。</p>
     */
    @Test
    void emptyArrayYieldsMinusOneSentinels() {
        MetricsCollector.MsptStats s = MetricsCollector.reduceTickTimes(new long[0]);
        assertEquals(-1.0, s.avgMs(), 1e-9);
        assertEquals(-1.0, s.minMs(), 1e-9);
        assertEquals(-1.0, s.maxMs(), 1e-9);
        assertEquals(0, s.validSamples());
        assertFalse(s.hasSamples());
    }

    /** {@code null} 数组不抛，同样返回哨兵。 */
    @Test
    void nullArrayYieldsSentinelsWithoutThrowing() {
        MetricsCollector.MsptStats s = MetricsCollector.reduceTickTimes(null);
        assertEquals(-1.0, s.avgMs(), 1e-9, "反射 invoke 可能返回 null（方法在但拿不到值）");
        assertEquals(0, s.validSamples());
        assertFalse(s.hasSamples());
    }

    /** 全零数组等价于空——服务器刚启动时就是这种形态。 */
    @Test
    void allZeroArrayYieldsSentinels() {
        MetricsCollector.MsptStats s = MetricsCollector.reduceTickTimes(new long[100]);
        assertEquals(-1.0, s.avgMs(), 1e-9,
            "100 个全零槽位是「刚启动、窗口还没填」的真实形态，必须报 -1（未采集）而不是 0");
        assertEquals(-1.0, s.minMs(), 1e-9, "min 不能是 Long.MAX_VALUE/1e6 那种荒谬值");
        assertEquals(-1.0, s.maxMs(), 1e-9);
        assertEquals(0, s.validSamples());
    }

    // ------------------------------------------------------------------------------------
    // 4. 长数组与边界
    // ------------------------------------------------------------------------------------

    /**
     * 100 个样本的长数组——真实 {@code getTickTimes()} 就是这种长度。
     *
     * <p>这条同时验证"没有在循环里用错累加器"：若 {@code sum} 写成 {@code int}，
     * 100 个 50ms 的样本（纳秒合计 5e9）会溢出 32 位，avg 变成一个负数或离谱的小值。</p>
     */
    @Test
    void longArrayDoesNotOverflowTheAccumulator() {
        long[] arr = new long[100];
        java.util.Arrays.fill(arr, 50 * MS_IN_NANOS);
        MetricsCollector.MsptStats s = MetricsCollector.reduceTickTimes(arr);

        assertEquals(50.0, s.avgMs(), 1e-9,
            "100 个 50ms 样本：sum 是 5e9 纳秒，若累加器是 int 会溢出，avg 变成荒谬值");
        assertEquals(100, s.validSamples());
    }

    /** 极大值样本不溢出（{@code long} 累加在真实量级下足够）。 */
    @Test
    void hugeSingleSampleIsHandled() {
        MetricsCollector.MsptStats s =
            MetricsCollector.reduceTickTimes(new long[]{60_000L * MS_IN_NANOS});  // 60 秒
        assertEquals(60_000.0, s.avgMs(), 1e-9, "一个 60 秒的 tick（服务器完全卡死）也要如实报出");
    }

    /** 亚毫秒 tick 舍入到 0.0——这是"合法读数恰好是 0"的场景，与哨兵不同。 */
    @Test
    void subMillisecondTickRoundsToZeroButStillCounts() {
        MetricsCollector.MsptStats s =
            MetricsCollector.reduceTickTimes(new long[]{4_000L});   // 4 微秒 = 0.004ms
        assertEquals(0.0, s.avgMs(), 1e-9, "0.004ms 舍到两位小数是 0.0");
        assertEquals(1, s.validSamples(),
            "但它是一个有效样本（validSamples == 1），与「没采到」的 validSamples == 0 必须分得开");
        assertTrue(s.hasSamples(),
            "hasSamples 为 true 而 avg 为 0.0——这正是为什么哨兵要用 -1 而不是 0");
    }

    // ------------------------------------------------------------------------------------
    // 5. avg 与 min/max 的口径一致性
    // ------------------------------------------------------------------------------------

    /**
     * avg 必须落在 min 与 max 之间——这是规约本身的自洽性。
     *
     * <p>这条能抓住"avg 的分母用了总长度 n 而不是有效样本数"这类错：若分母是本轮数组长度
     * （含零槽位），avg 会明显小于真实均值，甚至小于 min——看起来"平均比最快还快"。</p>
     */
    @Test
    void averageIsAlwaysBetweenMinAndMax() {
        long[] arr = {0, 1 * MS_IN_NANOS, 0, 0, 9 * MS_IN_NANOS, 0, 5 * MS_IN_NANOS};
        MetricsCollector.MsptStats s = MetricsCollector.reduceTickTimes(arr);

        assertTrue(s.avgMs() >= s.minMs(),
            "avg (" + s.avgMs() + ") 不得小于 min (" + s.minMs() + ")："
                + "若 avg 的分母用了含零槽位的数组长度，它会被拉低到 min 以下");
        assertTrue(s.avgMs() <= s.maxMs(),
            "avg (" + s.avgMs() + ") 不得大于 max (" + s.maxMs() + ")");
    }
}
