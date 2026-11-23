/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-S3：MSPT 三条降级路径"失败时到底报什么"。
 *
 * <h2>要修的 bug</h2>
 *
 * <p>三条路径中有两条在失败时<b>只把值置成哨兵就完了</b>：模块不进 {@code unavailable}。
 * 于是 <code>/ps status</code> 的模块表按
 * {@code platformSkipped → disabledModules → warmingUp → unavailable} 顺序判定时一路落空，
 * 渲染成绿色「正常」；而 <code>/ps</code> 的 MSPT 行同时显示「未知」。
 * <b>同一屏上"采集说没数、状态说没事"</b>——这正是本项目的红线：宁可说不知道，不说错话。</p>
 *
 * <h2>为什么这三个方法可以单独测</h2>
 *
 * <p>{@link MetricsCollector#sanitizeAvgTick(double)} 与
 * {@link MetricsCollector#reduceTickTimes(long[])} 是纯函数（输入只有数字/数组），
 * {@link MetricsCollector#markMsptCollectFailed} 只写快照的两个集合。
 * 三者都不碰 {@code Bukkit}——而 {@code collectMSPT} 本身的第一行
 * {@code ServerCompat.isMsptTrustworthy()} 会触发 {@code ServerCompat} 的静态初始化，
 * 那需要真实服务端（见 {@code tools/KNOWN-TEST-LIMITS.md}）。</p>
 */
class MsptDegradationMarkingTest {

    /**
     * {@code markMsptCollectFailed} 的反射调用：它是<b>实例方法</b>（要读 {@code startedAtMs}
     * 判断预热窗口），所以必须给一个 {@code allocateInstance} 出来的实例。
     *
     * <p>{@code allocateInstance} 不执行字段初始化器，{@code startedAtMs} 因此是 {@code 0}——
     * 距"现在"早已超过 10 秒宽限期，正好是"预热已过"的默认场景。需要测预热的那两条用例
     * 自己把该字段拨到"刚刚开始"。</p>
     */
    private static void markFailed(MetricsSnapshot snap) {
        MetricsCollector mc = TestObjects.allocate(MetricsCollector.class);
        TestObjects.invoke(mc, "markMsptCollectFailed",
            new Class<?>[]{MetricsSnapshot.class}, snap);
    }

    private static MetricsSnapshot snap() {
        return new MetricsSnapshot();
    }

    // ====================================================================================
    // 1. P1-S3 第一条：一级 n==0（reduceTickTimes 的哨兵）+ 必须标 unavailable
    // ====================================================================================

    /**
     * 空数组 / 全零数组 → 三个值都是 {@code -1}，且 {@code validSamples == 0}。
     *
     * <p>这一半由 {@code MsptReduceTest} 已有覆盖，这里再钉一次是为了让本类读起来完整：
     * 后面的用例依赖"n==0 时确实返回 -1"这个前提。</p>
     */
    @Test
    void emptyTickTimesYieldTheMinusOneSentinel() {
        MetricsCollector.MsptStats fromNull = MetricsCollector.reduceTickTimes(null);
        MetricsCollector.MsptStats fromEmpty = MetricsCollector.reduceTickTimes(new long[0]);
        MetricsCollector.MsptStats allZero = MetricsCollector.reduceTickTimes(new long[100]);

        for (MetricsCollector.MsptStats s : new MetricsCollector.MsptStats[]{fromNull, fromEmpty, allZero}) {
            assertEquals(-1.0, s.avgMs(), 1e-9);
            assertEquals(-1.0, s.minMs(), 1e-9);
            assertEquals(-1.0, s.maxMs(), 1e-9);
            assertFalse(s.hasSamples(), "no valid sample means hasSamples() must be false");
        }
    }

    /**
     * <b>靶点（P1-S3 第一条）</b>：一级失败时必须写进 {@code unavailable}。
     *
     * <p>改前：三个值置 -1 就返回，{@code unavailable} 不含 {@code mspt}，
     * {@code /ps status} 显示绿色「正常」。改后：模块状态如实变红（或预热期显示「预热中」）。</p>
     */
    @Test
    void paperPathFailureMarksTheModuleUnavailable() {
        MetricsSnapshot s = snap();
        markFailed(s);

        assertTrue(s.unavailable.contains(MetricsSnapshot.MODULE_MSPT),
            "a failed MSPT collection must land in unavailable: otherwise /ps status renders a green "
                + "'normal' while /ps renders 'unknown' for the very same metric");
    }

    /**
     * <b>刻意不写 {@code disabledModules}</b>：那是"服主显式关掉了"，采集失败不是他的选择。
     *
     * <p>写错会让 {@code /ps status} 显示「已禁用（config mspt.enabled=false）」——
     * 把故障伪装成配置，服主会去翻 config.yml 而不是看日志。</p>
     */
    @Test
    void collectionFailureIsNotReportedAsDisabled() {
        MetricsSnapshot s = snap();
        markFailed(s);

        assertFalse(s.disabledModules.contains(MetricsSnapshot.MODULE_MSPT),
            "a collection failure must not be filed as 'disabled in config': that sends the operator "
                + "to config.yml instead of the log");
        assertFalse(s.platformSkipped.contains(MetricsSnapshot.MODULE_MSPT),
            "a collection failure is not a platform limitation either (that is the Folia branch)");
    }

    /**
     * 启动/重载后的宽限期内同时标 {@code warmingUp}——展示层据此渲染「预热中」。
     *
     * <p>Paper 启动后头一两轮 {@code getTickTimes()} 确实是空数组，那是预热不是故障；
     * 超过 {@code WARMUP_WINDOW_MS} 仍未产出才回落真故障。这里把
     * {@code startedAtMs} 拨到"刚刚开始"，断言两支都进了。</p>
     */
    @Test
    void failureWithinTheWarmupWindowIsAlsoFlaggedAsWarmingUp() {
        MetricsSnapshot s = snap();
        MetricsCollector mc = TestObjects.allocate(MetricsCollector.class);
        TestObjects.setField(MetricsCollector.class, mc, "startedAtMs", System.currentTimeMillis());

        TestObjects.invoke(mc, "markMsptCollectFailed",
            new Class<?>[]{MetricsSnapshot.class}, s);

        assertTrue(s.warmingUp.contains(MetricsSnapshot.MODULE_MSPT),
            "within the warmup window a failed first collection is 'still warming up', not a fault");
        assertTrue(s.unavailable.contains(MetricsSnapshot.MODULE_MSPT),
            "warmingUp is a subset of unavailable: the contract must hold for the published snapshot");
    }

    /** 宽限期过后不再标 warmingUp——否则等于一个永不结束的"预热中"，是虚假的自愈信号。 */
    @Test
    void failureAfterTheWarmupWindowIsAPlainFault() {
        MetricsSnapshot s = snap();
        MetricsCollector mc = TestObjects.allocate(MetricsCollector.class);
        // 拨到 60 秒前：远超 WARMUP_WINDOW_MS（10 秒）
        TestObjects.setField(MetricsCollector.class, mc, "startedAtMs",
            System.currentTimeMillis() - 60_000L);

        TestObjects.invoke(mc, "markMsptCollectFailed",
            new Class<?>[]{MetricsSnapshot.class}, s);

        assertFalse(s.warmingUp.contains(MetricsSnapshot.MODULE_MSPT),
            "past the warmup window it must stop claiming 'warming up' — a permanent warmup state "
                + "is a false self-healing signal");
        assertTrue(s.unavailable.contains(MetricsSnapshot.MODULE_MSPT));
    }

    // ====================================================================================
    // 2. P1-S3 第二条：二级 invoke 成功但返回 0/负/NaN
    // ====================================================================================

    /**
     * <b>靶点（P1-S3 第二条）</b>：{@code getAverageTickTime()} 返回 0 时不得当读数写下。
     *
     * <p>改前：0 原样写进 {@code msptAvg}，{@code /ps} 渲染绿色 {@code 0.00ms}，
     * 报告写 {@code MSPT (avg): 0.00 ms}。改后：写 {@code -1} 哨兵并标 unavailable。</p>
     */
    @Test
    void zeroAverageTickIsRejectedNotStoredAsAReading() {
        assertEquals(-1.0, MetricsCollector.sanitizeAvgTick(0.0), 1e-9,
            "0 ms per tick is physically impossible; storing it makes 'unmeasured' look like "
                + "'the tick costs nothing'");
        assertEquals(-1.0, MetricsCollector.sanitizeAvgTick(-1.0), 1e-9);
        assertEquals(-1.0, MetricsCollector.sanitizeAvgTick(-0.001), 1e-9);
    }

    /**
     * {@code NaN} 也必须被拒。
     *
     * <p>若判据写成 {@code mspt <= 0}，{@code NaN <= 0} 是 {@code false}，NaN 会漏过守卫；
     * 随后 {@code round2(NaN)} 静默返回 {@code 0.0}（{@code CollectorFormattingTest} 有专测），
     * 于是 NaN 最终变成一条 0 读数。{@code !(mspt > 0)} 顺带挡住它。</p>
     */
    @Test
    void NaNIsRejectedBeforeRound2CanTurnItIntoZero() {
        assertEquals(-1.0, MetricsCollector.sanitizeAvgTick(Double.NaN), 1e-9,
            "NaN must be rejected here: `NaN <= 0` is false, so a `<= 0` guard would let it through "
                + "and round2 would silently turn it into 0.0");
        assertEquals(-1.0, MetricsCollector.sanitizeAvgTick(Double.POSITIVE_INFINITY), 1e-9);
    }

    /** 正常值原样舍入到两位——不能因为加了守卫就把好数据也丢掉。 */
    @Test
    void saneAverageTickIsRoundedAndKept() {
        assertEquals(7.25, MetricsCollector.sanitizeAvgTick(7.246), 1e-9);
        assertEquals(50.05, MetricsCollector.sanitizeAvgTick(50.049999), 1e-9);
        assertEquals(0.01, MetricsCollector.sanitizeAvgTick(0.005), 1e-9,
            "a tiny but positive reading is still a real reading: 0.005 ms rounds up to 0.01");
    }

    // ====================================================================================
    // 3. P1-S3 第三条：TPS 反推**刻意不标** unavailable
    // ====================================================================================

    /**
     * <b>反方向断言</b>：TPS 反推路径满 TPS 时置 {@code -1}，但<b>不</b>进 {@code unavailable}。
     *
     * <p>这条看起来像"漏了"，其实是本类最重要的一条：纯 Spigot 服没有
     * {@code getTickTimes}/{@code getAverageTickTime}，TPS 反推是<b>唯一</b>路径；
     * 一台满 TPS 的健康 Spigot 服若因此被标 unavailable，会永远显示「MSPT 采集失败」——
     * 把"这个端本来就不提供"谎报成"插件坏了"。</p>
     *
     * <p>反推路径不需要靠 unavailable 保护告警：满 TPS 时 {@code msptAvg = -1} 会让
     * {@code msptAlertLevel} 返回 {@code NO_VERDICT}（第三道闸门），{@code /ps} 侧也已有
     * {@code msptAvg <= 0} 的「未知」分支。</p>
     */
    @Test
    void tpsFallbackWithFullTpsKeepsTheModuleAvailable() {
        MetricsCollector mc = TestObjects.allocate(MetricsCollector.class);
        MetricsSnapshot s = snap();
        s.tps1m = 20.0;   // 满 TPS：反推公式按定义不可用

        TestObjects.invoke(mc, "collectMSPT_TPSFallback",
            new Class<?>[]{MetricsSnapshot.class}, s);

        assertEquals(-1.0, s.msptAvg, 1e-9,
            "at a full 20 TPS the fallback has no meaningful value and must emit the sentinel");
        assertEquals(MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK, s.msptSource,
            "the source must say 'tps_fallback' so the display layer can mark it as an estimate");
        assertFalse(s.unavailable.contains(MetricsSnapshot.MODULE_MSPT),
            "deliberately NOT unavailable: on plain Spigot this is the only path, and a healthy "
                + "full-TPS server must not be told its MSPT collection is broken forever");
    }

    /**
     * 反推路径给出了一个值时的形态：{@code 1000/TPS}、min/max 置 -1、source 恒为 TPS_FALLBACK。
     *
     * <p>{@code 19.99 → 50.03ms} 正是 P0-U 里那条"健康服上反推出 critical"的算例
     * （默认 critical 线 50ms）。这里只锁采集层形态，告警/展示层的标记见
     * {@code AlertCatalogTest} 与 {@code MetricsTextRendererTest}。</p>
     */
    @Test
    void tpsFallbackProducesTheWallClockIntervalAndNoExtremes() {
        MetricsCollector mc = TestObjects.allocate(MetricsCollector.class);
        MetricsSnapshot s = snap();
        s.tps1m = 19.99;

        TestObjects.invoke(mc, "collectMSPT_TPSFallback",
            new Class<?>[]{MetricsSnapshot.class}, s);

        assertEquals(50.03, s.msptAvg, 1e-9,
            "1000/19.99 = 50.03 — this is the wall-clock tick interval (server sleep included), "
                + "which is exactly why the display layer must mark it as an estimate");
        assertEquals(-1.0, s.msptMin, 1e-9, "the fallback has no extreme information");
        assertEquals(-1.0, s.msptMax, 1e-9);
        assertEquals(MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK, s.msptSource);
    }

    // ====================================================================================
    // 4. 契约：三种标记都不得破坏子集关系
    // ====================================================================================

    /**
     * 标记之后 {@code freezeCollections} 的契约修复逻辑必须无事可做。
     *
     * <p>与 {@code ModuleUnavailableMarkingTest} 同构：{@code repairSubsetContracts} 是兜底，
     * 常规路径写对了它就什么都不修。若这里报告"修过"，说明 {@code markMsptCollectFailed}
     * 的写入不完整——而兜底修复意味着数据已经错了一轮。</p>
     */
    @Test
    void markingAloneSatisfiesTheSubsetContract() {
        MetricsSnapshot s = snap();
        markFailed(s);

        boolean repaired = (Boolean) TestObjects.invoke(MetricsCollector.class,
            "repairSubsetContracts", new Class<?>[]{MetricsSnapshot.class}, s);
        assertFalse(repaired,
            "markMsptCollectFailed must not require the safety-net repair: "
                + "if it does, the sets were already wrong for one cycle");

        assertTrue(s.unavailable.containsAll(new LinkedHashSet<>(s.disabledModules)));
        assertTrue(s.unavailable.containsAll(new LinkedHashSet<>(s.warmingUp)));
    }

    // ====================================================================================
    // 5. 两个判据本身（P1-S3 真正的修复点）
    //
    // 上面所有用例测的都是 markMsptCollectFailed 这个"动作"。而让 P1-S3 消失的是
    // collectMSPT_Paper / collectMSPT_BukkitAvg 里那两个 **if**——它们所在的方法第一行
    // 就要 Bukkit，测试进不来。判据抽成纯函数之前，删掉或写反那个 if，994 个用例全绿。
    // ====================================================================================

    /**
     * 一级路径：有样本才算成功。
     *
     * <p>反驳的是"空数组也算采到了"——那会把 {@code msptAvg = -1} 当成精确读数，
     * 于是 {@code /ps} 显示 {@code -1.0ms} 而 {@code /ps status} 说正常。</p>
     */
    @Test
    void paperPathNeedsAtLeastOneSample() {
        assertFalse(MetricsCollector.tickTimesCollectedSamples(null),
            "null stats must not count as a successful collection");
        assertFalse(MetricsCollector.tickTimesCollectedSamples(MetricsCollector.reduceTickTimes(new long[0])),
            "an empty tick-times array is the warm-up shape, not a reading");
        assertFalse(MetricsCollector.tickTimesCollectedSamples(MetricsCollector.reduceTickTimes(new long[100])),
            "an all-zero array (unfilled ring buffer slots) is not a reading either");
        assertTrue(MetricsCollector.tickTimesCollectedSamples(
                MetricsCollector.reduceTickTimes(new long[]{1_000_000L, 2_000_000L})),
            "one real sample is a reading");
    }

    /**
     * 二级路径：{@code > 0} 才算可用。
     *
     * <p><b>边界必须钉死</b>：{@code 0} 是 {@code sanitizeAvgTick} 在未填充/除零/NaN
     * 之后给出的形状。判据若放宽成 {@code <= -999}（一种看似"更有容错"的写法），
     * 恰好 0 就会漏过去，于是 {@code /ps} 说"未知"而 {@code /ps status} 说"正常"——
     * 正是 P1-S3 那条 bug 本身。</p>
     */
    @Test
    void avgTickIsUsableOnlyAboveZeroAndFinite() {
        assertFalse(MetricsCollector.avgTickIsUsable(0.0),
            "exactly 0 is the sanitizeAvgTick failure shape, not a reading -- this is the P1-S3 boundary");
        assertFalse(MetricsCollector.avgTickIsUsable(-1.0), "negative is the unknown sentinel");
        assertFalse(MetricsCollector.avgTickIsUsable(Double.NaN), "NaN must not pass a >0 check by accident");
        assertFalse(MetricsCollector.avgTickIsUsable(Double.POSITIVE_INFINITY),
            "infinity would render as 'Infinity ms' and poison the average");
        assertTrue(MetricsCollector.avgTickIsUsable(0.001), "the smallest positive reading is usable");
        assertTrue(MetricsCollector.avgTickIsUsable(3.5), "a normal reading is usable");
    }

    /**
     * 两个判据在"恰好 0 / 空"这个边界上必须**一致地**判失败。
     *
     * <p>分开测能覆盖各自的取值域，但真正的 bug 是"两条降级路径口径不同"——
     * 一条标 unavailable、另一条不标。这条用例把两种输入摆在一起，
     * 任何一侧被改宽都会红。</p>
     */
    @Test
    void bothPathsAgreeOnTheZeroBoundary() {
        boolean paperSaysFailed = !MetricsCollector.tickTimesCollectedSamples(
            MetricsCollector.reduceTickTimes(new long[0]));
        boolean avgSaysFailed = !MetricsCollector.avgTickIsUsable(0.0);
        assertEquals(paperSaysFailed, avgSaysFailed,
            "the two degradation paths must agree on what counts as 'collected nothing': "
                + "if one marks the module unavailable and the other does not, /ps says 'unknown' "
                + "while /ps status says 'normal'");
        assertTrue(paperSaysFailed, "precondition: both must in fact judge this input as failed");
    }
}
