package cn.perfscope.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Bukkit.getTPS()} 返回数组的**窗口布局**兼容。
 *
 * <p>标准 Paper/Spigot 返回长度 3 的 {@code [1m, 5m, 15m]}。但 <b>Purpur 与 Leaf
 * 会在最前面插一个「5 秒平均」，变成长度 4 的 {@code [5s, 1m, 5m, 15m]}</b>。
 * 硬读 {@code [0][1][2]} 在那些平台上的后果是：</p>
 *
 * <table border="1">
 *   <caption>硬读下标在 4 元素数组上的后果</caption>
 *   <tr><th>我们以为读到</th><th>实际读到</th></tr>
 *   <tr><td>1 分钟</td><td>5 秒</td></tr>
 *   <tr><td>5 分钟</td><td>1 分钟</td></tr>
 *   <tr><td>15 分钟</td><td>5 分钟</td></tr>
 * </table>
 *
 * <p>严重度不是「数字略偏」，而是三件事同时发生：告警判据从 1 分钟变成 5 秒
 * （一次 200ms 的 GC 停顿就能把 5 秒 TPS 拉到 15 以下，在健康服上刷 critical）、
 * 趋势图与日报的「最低 TPS」全部是 5 秒口径、<b>15 分钟数据彻底消失</b>。
 * 而这一条不报错、不崩溃、不进 {@code unavailable}。</p>
 *
 * <p>这些用例测的是 {@link MetricsCollector#pickTpsWindows(double[])} —— 纯函数。
 * 之所以要抽出来：{@code collectTPS} 第一行是 {@code Bukkit.getTPS()}，测试 JVM 进不去，
 * 内联的偏移计算会变成零覆盖。</p>
 */
class TpsWindowLayoutTest {

    // ====================================================================================
    // 1. 标准布局：长度 3，原样取
    // ====================================================================================

    @Test
    @DisplayName("标准 Paper：长度 3，index 0/1/2 就是 1m/5m/15m")
    void standardThreeWindows() {
        MetricsCollector.TpsTriple t = MetricsCollector.pickTpsWindows(
            new double[]{19.9, 19.8, 19.7});
        assertEquals(19.9, t.oneMinute(), 1e-9);
        assertEquals(19.8, t.fiveMinutes(), 1e-9);
        assertEquals(19.7, t.fifteenMinutes(), 1e-9);
        assertNull(MetricsCollector.tpsShiftReason(new double[]{19.9, 19.8, 19.7}),
            "the standard layout must not produce a diagnostic line -- silence is the signal");
    }

    /**
     * 三个值**互不相同**，这样取错窗口一定看得出来。
     *
     * <p>若三个值都填 20.0，那么无论取哪三个下标测试都会通过——这种用例看起来在测
     * 偏移，实际什么都没测。这是本项目反复强调的「断言必须能区分对与错」。</p>
     */
    @Test
    @DisplayName("四个窗口：取后三个，短窗口不出现")
    void prependedShortWindowIsSkipped() {
        // [5s=1.0（明显不同）, 1m=19.9, 5m=19.8, 15m=19.7]
        MetricsCollector.TpsTriple t = MetricsCollector.pickTpsWindows(
            new double[]{1.0, 19.9, 19.8, 19.7});
        assertEquals(19.9, t.oneMinute(),
            "1m must be the SECOND element on a 4-window server, not the first");
        assertEquals(19.8, t.fiveMinutes());
        assertEquals(19.7, t.fifteenMinutes());
        assertFalse(Double.isNaN(t.oneMinute()));
    }

    /**
     * 这条是上面那条的**判别力证明**：如果实现退回硬读 {@code [0][1][2]}，
     * 1m 会拿到 1.0（那个 5 秒值），本用例立刻红。
     */
    @Test
    @DisplayName("判别力：硬读 [0] 会拿到 5 秒值，本用例必须能抓住")
    void oneMinuteMustNotBeTheShortWindow() {
        double shortWindow = 3.0;      // 一次卡顿的 5 秒平均值
        double oneMinute = 19.95;
        MetricsCollector.TpsTriple t = MetricsCollector.pickTpsWindows(
            new double[]{shortWindow, oneMinute, 19.9, 19.8});
        assertTrue(t.oneMinute() > 15.0,
            "1m read as " + t.oneMinute() + " -- that is the 5-second window, not the 1-minute one");
        assertEquals(oneMinute, t.oneMinute(), 1e-9);
    }

    // ====================================================================================
    // 2. 异常长度：哨兵而不是瞎取
    // ====================================================================================

    @Test
    @DisplayName("null / 过短：三个值都置 -1 哨兵，不抛异常")
    void tooShortYieldsSentinel() {
        for (double[] bad : new double[][]{null, {}, {19.9}, {19.9, 19.8}}) {
            MetricsCollector.TpsTriple t = MetricsCollector.pickTpsWindows(bad);
            assertEquals(-1.0, t.oneMinute(), "a short array must not be read optimistically");
            assertEquals(-1.0, t.fiveMinutes());
            assertEquals(-1.0, t.fifteenMinutes());
        }
    }

    @Test
    @DisplayName("过短时诊断行说明「只有 N 个窗口」，不是静默")
    void tooShortIsReported() {
        String reason = MetricsCollector.tpsShiftReason(new double[]{19.9});
        assertTrue(reason != null && reason.contains("1"),
            "an incomplete TPS array must be explainable in /status: " + reason);
    }

    // ====================================================================================
    // 3. 诊断行：只在非标准时出现
    // ====================================================================================

    @Test
    @DisplayName("四窗口时诊断行说明是前插，且指明了取的是后三个")
    void shiftIsExplained() {
        String reason = MetricsCollector.tpsShiftReason(new double[]{1.0, 19.9, 19.8, 19.7});
        assertTrue(reason != null && reason.contains("4"),
            "the diagnostic must name the actual window count: " + reason);
        assertTrue(reason.toLowerCase().contains("last three"),
            "it must say which three we use, otherwise the operator still has to guess: " + reason);
    }

    @Test
    @DisplayName("null 数组不产生诊断行（那是「没读到」，由采集失败路径负责）")
    void nullHasNoShiftReason() {
        assertNull(MetricsCollector.tpsShiftReason(null));
    }

    // ====================================================================================
    // 4. 超过 4 个窗口：仍然取后三个
    // ====================================================================================

    /**
     * 目前没有已知服务端返回 5 个窗口，但「按长度取后三个」这个规则不该只对 4 成立——
     * 若将来某个分支又前插一个窗口（比如 1 秒平均），硬编码 {@code length == 4} 的实现
     * 会退回标准的 {@code [0][1][2]} 并再次读错。
     */
    @Test
    @DisplayName("五个窗口：仍取后三个（不是只有 4 才处理）")
    void moreThanFourStillTakesTheLastThree() {
        MetricsCollector.TpsTriple t = MetricsCollector.pickTpsWindows(
            new double[]{0.5, 1.0, 19.9, 19.8, 19.7});
        assertEquals(19.9, t.oneMinute());
        assertEquals(19.8, t.fiveMinutes());
        assertEquals(19.7, t.fifteenMinutes());
    }
}