/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import cn.perfscope.support.AlertFixtures;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetricsCollector} 里的三个格式化纯函数：{@code round2} / {@code round4} / {@code describeThrowable}。
 *
 * <h2>为什么这三个值得单独测</h2>
 * 它们出现在每一个对外数字上：
 *
 * <ul>
 *   <li>{@code round2} → {@code tps1m/5m/15m}、{@code msptAvg/Min/Max}（11 处调用）；</li>
 *   <li>{@code round4} → {@code processCpuLoad} / {@code systemCpuLoad}（2 处）；</li>
 *   <li>{@code describeThrowable} → 每一条模块采集失败的日志正文。</li>
 * </ul>
 *
 * <p>舍入错一位，服主看到的 TPS 就从 {@code 19.99} 变成 {@code 20.0} 或反之——
 * 而 20.0 与 19.99 在"是否满 TPS"的目视判断上是两回事。日志措辞错了则影响可诊断性：
 * {@code describeThrowable} 若把类名丢掉，一条 {@code NullPointerException} 就只剩空字符串。
 *
 * <h2>本测试同时钉住三个<b>已知的危险行为</b></h2>
 * 这三点在当前调用点都<b>不可达</b>（下面各条会说明为什么），但它们一旦被新的调用方踩到，
 * 后果都是"把未知报成一个看起来正常的数字"：
 *
 * <ol>
 *   <li>{@code round2(NaN)} 返回 {@code 0.0}——{@code Math.round(NaN)} 是 0，于是
 *       {@code 0 / 100.0 = 0.0}。NaN 的含义是"没有读数"，变成 0 就是"读数正常"。</li>
 *   <li>{@code round2(Infinity)} 返回 {@code 9.223372036854776E16}——{@code Math.round}
 *       对超出 {@code long} 的值饱和到 {@code Long.MAX_VALUE}。</li>
 *   <li>负数舍入与正数<b>不对称</b>：{@code round2(0.005) == 0.01} 但
 *       {@code round2(-0.005) == 0.0}——因为 {@code Math.round} 是"向正无穷取半"。</li>
 * </ol>
 *
 * <p>这里<b>不</b>修正它们：修 {@code round2} 会影响 11 个调用点的输出，
 * 而它们当前都安全。测试的作用是把这个边界记录下来——将来有人拿 {@code round2} 去舍入
 * 一个可能为 NaN/Infinity 的值时，至少这三个用例在仓库里写着"这里会怎样"。
 */
class CollectorFormattingTest {

    /**
     * 文案层：{@code describeThrowable(null)} 走 {@code Messages.get("run.collector.unknown-error")}。
     *
     * <p>三步都是必要的，但<b>第三步必须用带 plugin 的 {@code load}</b>，不能用
     * {@code load(null)}：{@code load(null)} 是"无插件"分支，它是否合并模块表取决于该分支
     * 的实现（那条 P1 修复仍在评审，两个分支上行为不同）。{@code run.*} 键由
     * {@code RuntimeMessages} 在 {@code init()} 时登记，只有 {@code touchModules() +
     * mergeModules()} 跑过才会进入快照——带 plugin 的 {@code load} 一定会跑这两步，
     * 所以它对"修复是否已合入"免疫。</p>
     */
    @BeforeAll
    static void initMessages() throws Exception {
        AlertFixtures.initMessages();
        Class.forName("cn.perfscope.RuntimeMessages").getMethod("init").invoke(null);
        // 为了 trigger touchModules + mergeModules 把 run.* 并进快照，再走一次带 plugin 的 load。
        cn.perfscope.util.Messages.load(
            AlertFixtures.barePlugin(Logger.getLogger("pscope-collector-formatting")));
    }

    /** {@code round2} / {@code round4} 是实例方法（非 static），所以要一个壳。 */
    private static MetricsCollector collector() {
        return TestObjects.allocate(MetricsCollector.class);
    }

    private static double round2(double v) {
        return (Double) TestObjects.invoke(collector(), "round2",
            new Class<?>[]{double.class}, v);
    }

    private static double round4(double v) {
        return (Double) TestObjects.invoke(collector(), "round4",
            new Class<?>[]{double.class}, v);
    }

    private static String describe(Throwable t) {
        return (String) TestObjects.invoke(MetricsCollector.class, "describeThrowable",
            new Class<?>[]{Throwable.class}, t);
    }

    // ------------------------------------------------------------------------------------
    // 1. round2：正常路径
    // ------------------------------------------------------------------------------------

    /**
     * 两位小数，且 {@code 20.004} 不得被舍成 {@code 20.01}。
     *
     * <p>{@code 20.004} 这个值不是随便选的：MSPT/CPU 的口径里有 {@code 20.5} 的 TPS 上限、
     * {@code 20} 的满 TPS 线。舍入把 {@code 20.004} 抬到 {@code 20.01} 会让一次"刚过 20"的读数
     * 看起来像"稳定超过 20"，而 {@code 20.0} 与 {@code 20.01} 在"是否达到满 TPS"的目视判断上
     * 是两个结论。</p>
     */
    @Test
    void round2KeepsTwoDecimalsWithoutRoundingUpTooEarly() {
        assertEquals(20.0, round2(20.004), 1e-9,
            "20.004 必须舍到 20.0，不能抬成 20.01——TPS 的目视判断就卡在 20 附近");
        assertEquals(19.99, round2(19.994), 1e-9);
        assertEquals(19.99, round2(19.9949), 1e-9);
        assertEquals(18.0, round2(18.0), 1e-9, "整数原样返回，不引入浮点噪声");
    }

    /** 半值向上取（{@code Math.round} 的语义）。 */
    @Test
    void round2RoundsHalfUp() {
        assertEquals(0.01, round2(0.005), 1e-9,
            "0.005 是正中半值，Math.round 向上取");
        assertEquals(20.0, round2(19.995), 1e-9);
    }

    /** {@code round4} 保留四位，供 CPU 百分比用（0-100 之间的值需要更高分辨率）。 */
    @Test
    void round4KeepsFourDecimals() {
        assertEquals(45.5, round4(45.49999), 1e-9);
        assertEquals(45.4999, round4(45.49994), 1e-9);
        assertEquals(0.0, round4(0.0), 1e-9);
    }

    /** CPU 读数是百分比，整数部分原样保留。 */
    @Test
    void round4PreservesWholePercentages() {
        assertEquals(80.0, round4(80.0), 1e-9);
        assertEquals(92.25, round4(92.25), 1e-9);
    }

    // ------------------------------------------------------------------------------------
    // 2. round2 的已知危险行为（当前调用点不可达，但记录在案）
    // ------------------------------------------------------------------------------------

    /**
     * <b>{@code round2(NaN)} 返回 {@code 0.0}</b>——记录，不修正。
     *
     * <p>{@code Math.round(Double.NaN)} 返回 {@code 0L}，于是 {@code 0 / 100.0 = 0.0}。
     * NaN 的语义是"没有读数"，变成 0 就是"读数正常"——这正是本项目最怕的失真方向。</p>
     *
     * <p><b>当前为什么不可达</b>：{@code round2} 的三个调用族各自都有前置守卫——
     * TPS 来自 {@code Bukkit.getTPS()}（恒为有限数）；
     * MSPT 的三条路径分别有 {@code n == 0 → -1}、{@code avgTickMethod == null → fallback}、
     * {@code tps1m > 0 && tps1m < 20} 的守卫；
     * CPU 在调用前已经 {@code p < 0 ? -1 : round4(p)}。</p>
     *
     * <p>这条用例存在的意义是：若将来有人删掉某个守卫、或拿 {@code round2} 去舍入一个
     * 可能为 NaN 的值，这里已经写着"会发生什么"。</p>
     */
    @Test
    void round2TurnsNaNIntoZeroAndThatIsRecorded() {
        double r = round2(Double.NaN);
        assertEquals(0.0, r, 1e-9,
            "已知行为：Math.round(NaN) 是 0，所以 round2(NaN) == 0.0。"
                + "当前调用点都有前置守卫，所以不可达；但若新调用方拿它舍入可能为 NaN 的值，"
                + "NaN（无读数）会变成 0（读数正常）");
        assertFalse(Double.isNaN(r),
            "它确实不再是 NaN——这就是这条记录的意义：失真已经发生，且是静默的");
    }

    /**
     * <b>{@code round2(Infinity)} 饱和到 {@code 9.22e16}</b>——记录，不修正。
     *
     * <p>{@code Math.round} 对超出 {@code long} 的值返回 {@code Long.MAX_VALUE}
     * （{@code 9.223372036854776E18}），再除以 100 得 {@code 9.22e16}。
     * 一个"无穷大"的读数变成"9223 万亿"，看起来像个真实但荒谬的数字。</p>
     *
     * <p><b>当前为什么不可达</b>：唯一可能产生 Infinity 的算式是
     * {@code collectMSPT_TPSFallback} 的 {@code 1000.0 / tps1m}，而它的守卫
     * {@code snap.tps1m > 0 && snap.tps1m < 20} 已排除 0 与负数
     * （那条守卫上有 {@code @sentinel-ok} 标注，说明作者当时想过这件事）。</p>
     */
    @Test
    void round2SaturatesInfinityInsteadOfKeepingIt() {
        double r = round2(Double.POSITIVE_INFINITY);
        assertTrue(Double.isFinite(r),
            "已知行为：round2(Infinity) 是有限值（Math.round 饱和到 Long.MAX_VALUE 再除 100）。"
                + "当前不可达——1000/tps1m 的守卫排除了 0；若守卫被删，这里会变成一个"
                + "荒谬但看起来真实的巨大数字");
        assertNotEquals(Double.POSITIVE_INFINITY, r);
    }

    /**
     * 负数舍入与正数不对称——记录。
     *
     * <p>{@code Math.round} 是"向正无穷取半"，所以 {@code 0.005} 舍成 {@code 0.01} 而
     * {@code -0.005} 舍成 {@code 0.0}。这个不对称只在"值可能为负"时有意义。</p>
     *
     * <p><b>当前为什么无害</b>：负值在本类里是<b>哨兵</b>（{@code -1} = 未采集），
     * 哨兵由调用方直接赋值、不经过 {@code round2}。也就是说"能进 round2 的值"都是非负的真实读数。</p>
     */
    @Test
    void round2IsAsymmetricForNegativeHalfValues() {
        assertEquals(0.01, round2(0.005), 1e-9);
        assertEquals(0.0, round2(-0.005), 1e-9,
            "已知行为：Math.round 向正无穷取半，所以 -0.005 舍成 0 而 +0.005 舍成 0.01。"
                + "当前无害——负值在本类里是哨兵，由调用方直接赋值、不经过 round2");
    }

    /** 哨兵 −1 经过 round2 仍然是 −1（不会被舍成 0 或 −1.0 以外的值）。 */
    @Test
    void round2PreservesTheMinusOneSentinel() {
        assertEquals(-1.0, round2(-1.0), 1e-9,
            "哨兵 -1 必须原样返回：若它被舍成 0，下游「未采集」判定会变成「读数 0」");
        assertEquals(-1.0, round4(-1.0), 1e-9);
    }

    // ------------------------------------------------------------------------------------
    // 3. describeThrowable：日志正文
    // ------------------------------------------------------------------------------------

    /**
     * 有消息时输出 {@code "类名: 消息"}。
     *
     * <p>类名不可省：服主搜 "IllegalStateException" 能查到；只留消息则无从判断严重程度。
     * 这条锁的是"类名与消息都必须出现"。</p>
     */
    @Test
    void describeThrowableIncludesClassAndMessage() {
        String s = describe(new IllegalStateException("region 跨线程访问"));
        assertEquals("IllegalStateException: region 跨线程访问", s,
            "日志正文必须是「类名: 消息」——丢掉类名就无从判断严重程度，丢掉消息就无从定位");
    }

    /**
     * 消息为 {@code null} 时只输出类名，<b>不带</b>那个 {@code ": null"}。
     *
     * <p>很多异常构造时没有消息（例如裸的 {@code new NullPointerException()}）。
     * 若实现写成无条件拼接，日志会变成 {@code "NullPointerException: null"}——
     * 看起来像"消息就是字符串 null"，而不是"没有消息"。</p>
     */
    @Test
    void describeThrowableOmitsNullMessage() {
        assertEquals("IllegalStateException", describe(new IllegalStateException()),
            "没有消息时不得输出「类名: null」——那看起来像消息内容就是字符串 null");
        assertFalse(describe(new IllegalStateException()).contains("null"));
    }

    /**
     * {@code null} 异常走文案层，不 NPE。
     *
     * <p>{@code describeThrowable(null)} 出现在"异常对象本身丢了"的兜底路径上。
     * 若这里 NPE，采集失败的日志就变成"记日志时又抛了一个异常"——
     * 原本要报告的错误被掩盖。</p>
     *
     * <p><b>这里断言渲染后的文案，而不是只断言"非空"</b>：{@code run.*} 族键由
     * {@code RuntimeMessages} 注册，合并发生在 {@code Messages.load} 的
     * {@code touchModules() + mergeModules()} 里——**包括 {@code load(null)} 那条路径**
     * （它曾被改成直接发布只有 85 个基础键的 {@code EN_DEFAULTS}，导致模块键整批丢失；
     * 修复后 null 与带 plugin 两条路径发布的是同一张合并表）。
     * 所以本类 {@code @BeforeAll} 的 {@code AlertFixtures.initMessages() + RuntimeMessages.init()
     * + load(null)} 三步走完之后，{@code Messages.get} 必须返回真实文案。
     * 若这里又退回键名，说明消息层没加载好——那种"函数能跑但日志正文是内部键名"的降级
     * 会一路静默到服主的控制台上，正是本条要拦的东西。</p>
     */
    @Test
    void describeThrowableHandlesNullThrowable() {
        String s = describe(null);
        assertFalse(s.isEmpty(),
            "null 异常必须有可读输出，不能返回空串——否则日志里那一条会变成空白");

        // 期望值由消息层自己渲染同一模板得到，不硬编码语言，也不拿键名当期望值。
        String expected = cn.perfscope.util.Messages.get("run.collector.unknown-error");
        assertNotEquals("run.collector.unknown-error", expected,
            "前置条件：该键必须解析成真实文案，否则下面的比对退化成「键名 == 键名」");
        assertEquals(expected, s,
            "describeThrowable(null) 必须走文案层拿到同一句话；got: " + s);
    }

    /** 嵌套异常只描述最外层——本方法不做 cause 展开链。 */
    @Test
    void describeThrowableDescribesOnlyTheWrapper() {
        String s = describe(new RuntimeException("外层", new IllegalStateException("内层")));
        assertTrue(s.contains("外层"), "描述的是传进来的那个异常，got: " + s);
        assertFalse(s.contains("内层"),
            "本方法不展开 cause 链（那是调用方/日志框架的事）——"
                + "若它开始展开，一条日志会变成多行长文本，qquad 与现有格式不符。got: " + s);
    }
}
