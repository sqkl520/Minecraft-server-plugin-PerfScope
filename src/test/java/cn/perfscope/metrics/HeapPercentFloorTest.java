/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetricsCollector#heapPercentFloor(long, long)} —— 堆使用率的百分比计算内核。
 *
 * <h2>为什么单独抽出来测</h2>
 *
 * <p>{@code collectMemory} 需要 {@code Runtime.maxMemory()} 与 {@code MemoryMXBean} 的真值，
 * 而 {@code MetricsCollector} 的构造链会触发 {@code ServerCompat.<clinit>}——测试 JVM 里必炸。
 * 于是"百分比到底怎么取整"这件事此前只能靠线上读数猜。抽成纯函数后输入只剩两个 {@code long}。</p>
 *
 * <h2>要防的两种改坏</h2>
 *
 * <ol>
 *   <li><b>退回浮点</b>——{@code (int) ((double) used / max * 100)} 在特定堆大小上会少 1 格：
 *       {@code max = 300000000, used = 87000000} 精确是 29%，浮点比值是 {@code 0.28999999999999998}，
 *       乘 100 截断成 28。见 {@link #exactPercentIsNotLostToFloatTruncation()}。</li>
 *   <li><b>"顺手统一"成四舍五入</b>——{@code config.yml:124} 把 85 定为健康区间上界，
 *       round 会让 {@code [84.5%, 85%)} 这个健康的区间开始告警。
 *       见 {@link #floorNeverRoundsUpAcrossTheHealthyBoundary()}。</li>
 * </ol>
 */
class HeapPercentFloorTest {

    private static final long MiB = 1024L * 1024L;
    private static final long GiB = 1024L * MiB;
    private static final long TiB = 1024L * GiB;

    /** 默认阈值，与 {@code config.yml} 的 memory 段一致。 */
    private static final int HEAP_WARN = 85;
    private static final int HEAP_CRIT = 95;

    // ==================== 浮点截断的具体案例 ====================

    /**
     * <b>本类最关键的一条。</b>
     *
     * <p>{@code max = 300000000, used = 87000000} 精确是 29%。旧写法
     * {@code (int) ((double) used / max * 100)} 先算出 {@code 0.28999999999999998}，
     * 乘 100 得 {@code 28.999999999999996}，截断成 <b>28</b>——少报一格。</p>
     */
    @Test
    void exactPercentIsNotLostToFloatTruncation() {
        long max = 300_000_000L;
        long used = 87_000_000L;

        assertEquals(29, MetricsCollector.heapPercentFloor(used, max),
            "87M/300M is exactly 29%; integer arithmetic must not lose the digit. The old "
                + "float expression returned 28 here (0.28999999999999998 * 100 -> 28.99... -> 28)");
    }

    /**
     * 把旧写法本身也断言一次：这条用例的意义不是"测 Java 的浮点"，而是把
     * "浮点版在这里确实会错"钉成事实——后人若认为这不是个真问题，这条会告诉他数字从哪来。
     */
    @Test
    void theOldFloatExpressionReallyDidReturn28() {
        long max = 300_000_000L;
        long used = 87_000_000L;

        int oldResult = (int) ((double) used / max * 100);
        assertEquals(28, oldResult,
            "if this stops being 28, the premise of the fix changed; re-verify the whole rationale");
        assertEquals(29, MetricsCollector.heapPercentFloor(used, max),
            "the new implementation must be the one that is right");
    }

    /**
     * 这个 bug 不是孤例：逐 MiB 扫描 {@code -Xmx} 256MiB..64GiB 的全部整数百分比点
     * （67.9 万样本）后，{@code p = 29 / 57 / 58} 三个值上少 1，共 7833 次。
     *
     * <p>下面按同一扫描口径抽查这三族，确认整数版全部落在精确值上。
     * 2^n 堆（{@code -Xmx1024/2048/4096/8192M}，本地开发机的典型配置）不受影响，
     * 所以这个 bug 在开发机上永远复现不出来——这也正是它能一直藏着的原因。</p>
     */
    @Test
    void theWhole29_57_58FamilyIsExact() {
        int checked = 0;
        for (long mib = 256; mib <= 65536; mib++) {
            long max = mib * MiB;
            for (int p : new int[] {29, 57, 58}) {
                if ((max * p) % 100 != 0) {
                    continue; // 该堆大小上没有"精确整数百分比"的整字节点
                }
                long used = max * p / 100;
                assertEquals(p, MetricsCollector.heapPercentFloor(used, max),
                    "heap " + mib + "MiB at exactly " + p + "% must read " + p);
                checked++;
            }
        }
        assertTrue(checked > 1500,
            "sanity: the scan must actually cover the family, got " + checked + " points");
    }

    // ==================== 与精确有理数 floor 的一致性 ====================

    /**
     * 用一个独立实现（{@link BigInteger} 的精确有理数取整）做交叉验证，扫大范围输入。
     *
     * <p>这条不是同义反复：{@code 100L * used / max} 依赖 {@code long} 不溢出，
     * 而 BigInteger 版对任何量级都对。两者在大范围内零分叉，才说明 {@code long} 够用。</p>
     */
    @Test
    void agreesWithExactRationalFloor() {
        long[] heaps = {
            MiB, 256 * MiB, 512 * MiB, 1024 * MiB, 2048 * MiB, 4096 * MiB, 8192 * MiB,
            300_000_000L, 3 * GiB, 8 * GiB, 16 * GiB, 32 * GiB, 64 * GiB, TiB,
        };
        BigInteger hundred = BigInteger.valueOf(100);

        for (long max : heaps) {
            // 逐 0.1% 采一个点，覆盖整个 0-100% 区间
            for (int thousandths = 0; thousandths <= 1000; thousandths += 1) {
                long used = max / 1000 * thousandths;
                if (used < 0 || used > max) {
                    continue;
                }
                int expected = BigInteger.valueOf(used).multiply(hundred)
                    .divide(BigInteger.valueOf(max)).intValue();
                assertEquals(expected, MetricsCollector.heapPercentFloor(used, max),
                    "max=" + max + " used=" + used + " diverged from exact rational floor");
            }
        }
    }

    // ==================== 默认阈值 85 / 95 的边界 ====================

    /**
     * 默认阈值上恰好 85% / 95% 必须读作 85 / 95——判定走 {@code >=}，读成 84 会漏掉一条
     * 本该在线的告警，读成 86 则让一条不该有的告警出现。
     *
     * <p>注意构造：{@code -Xmx} 的字节数通常是 2 的幂，{@code max * 85 / 100} 除不尽，
     * 所以"恰好 85%"往往不是一个整字节点。这里用<b>向上取整</b>构造出第一个
     * {@code >= 85%} 的字节数，它按 floor 就该读 85。</p>
     *
     * <p>同时记录一个事实：这两个阈值恰好对旧浮点 bug <b>免疫</b>（扫描确认 85/95 上零分叉），
     * 所以这个 bug 从未在默认配置下翻出过错误告警——它只在 29/57/58 上少报。</p>
     */
    @Test
    void defaultThresholdsAreExact() {
        long[] heaps = {
            256 * MiB, 512 * MiB, 1024 * MiB, 2048 * MiB, 4096 * MiB, 8192 * MiB,
            12 * GiB, 16 * GiB, 32 * GiB, 64 * GiB,
        };
        for (long max : heaps) {
            assertEquals(HEAP_WARN, MetricsCollector.heapPercentFloor(atLeastPercent(max, HEAP_WARN), max),
                "heap " + max + ": the first byte count at >=85% must read 85 "
                    + "so the closed-interval `>=` fires");
            assertEquals(HEAP_CRIT, MetricsCollector.heapPercentFloor(atLeastPercent(max, HEAP_CRIT), max),
                "heap " + max + ": the first byte count at >=95% must read 95 "
                    + "so the closed-interval `>=` fires");
        }
    }

    /** 第一个满足 {@code used/max >= percent/100} 的整字节数（对 2^n 堆也成立）。 */
    private static long atLeastPercent(long max, int percent) {
        long num = max * percent;
        long floor = num / 100;
        return num % 100 == 0 ? floor : floor + 1;
    }

    /** 刚好越过 85 / 95 的一点点，必须被读成 86 / 96（不能因取整掉回阈值内）。 */
    @Test
    void justOverThresholdReadsAboveIt() {
        long max = 8 * GiB;
        // 85% 再多 1 个 MiB
        assertEquals(85, MetricsCollector.heapPercentFloor(max * 85 / 100 + MiB, max),
            "a hair over 85% still floors to 85 at 8GiB (1MiB is far below 1%), "
                + "but it must never read below the threshold");
        long over85 = atLeastPercent(max, 86);
        assertEquals(86, MetricsCollector.heapPercentFloor(over85, max),
            "the first byte count at >=86% must clear the warning line, not floor back to 85");
        long over95 = atLeastPercent(max, 96);
        assertEquals(96, MetricsCollector.heapPercentFloor(over95, max),
            "the first byte count at >=96% must clear the critical line");
    }

    // ==================== floor 语义（防 round 回来） ====================

    /**
     * <b>防止有人"顺手统一"成 {@code Math.round} 的关键断言。</b>
     *
     * <p>{@code config.yml:124} 写着「G1 正常水位 70-85% 属健康区间」，85 是健康上界。
     * {@code [84.5%, 85%)} 全都落在健康区间内，四舍五入会把它们读成 85 并开始告警——
     * 等于让文档声明的健康读数报警。</p>
     */
    @Test
    void floorNeverRoundsUpAcrossTheHealthyBoundary() {
        long max = 1024 * MiB;

        // 84.9999% —— 稳稳落在 [84.5%, 85%) 里
        long used = max * 849_999 / 1_000_000;
        assertEquals(84, MetricsCollector.heapPercentFloor(used, max),
            "84.9999% is inside the documented healthy band (70-85%); it must read 84. "
                + "Math.round would read 85 and start alerting on a healthy server");

        // 区间下沿 84.5% 本身
        long at845 = max * 845 / 1000;
        assertEquals(84, MetricsCollector.heapPercentFloor(at845, max),
            "84.5% must floor to 84, not round to 85");

        // 区间中点 84.7%
        long at847 = max * 847 / 1000;
        assertEquals(84, MetricsCollector.heapPercentFloor(at847, max));

        // 对照：84.4% 无论怎么取整都是 84
        long at844 = max * 844 / 1000;
        assertEquals(84, MetricsCollector.heapPercentFloor(at844, max));
    }

    /**
     * 整个 {@code [84.5%, 85%)} 区间扫描，全部必须是 84。
     *
     * <p>这条比单点断言更难被"改坏一点点"绕过：把 floor 换成 round 会让整段翻成 85。</p>
     */
    @Test
    void everyPointBelow85PercentReads84() {
        long max = 4 * GiB;
        long lower = max * 845 / 1000;      // 84.5%
        long upper = max * 85 / 100 - 1;    // 85% 之下最后一字节

        assertTrue(upper > lower, "sanity: the band must be non-empty");

        // 取 500 个采样点覆盖整个区间（逐字节扫太大）
        long step = Math.max(1, (upper - lower) / 500);
        for (long used = lower; used <= upper; used += step) {
            assertEquals(84, MetricsCollector.heapPercentFloor(used, max),
                "used=" + used + "/" + max + " is in [84.5%, 85%) and must floor to 84");
        }
    }

    /** 其余区间的 floor 语义：89.99% → 89，99.99% → 99（永不进到阈值里）。 */
    @Test
    void floorSemanticsAcrossTheScale() {
        long max = 16 * GiB;
        assertEquals(89, MetricsCollector.heapPercentFloor(max * 8999 / 10000, max));
        assertEquals(99, MetricsCollector.heapPercentFloor(max * 9999 / 10000, max));
        assertEquals(50, MetricsCollector.heapPercentFloor(max * 5000 / 10000, max));
        assertEquals(0, MetricsCollector.heapPercentFloor(1, max),
            "one byte of a 16GiB heap is 0%, not 1%");
    }

    // ==================== 边界与守卫 ====================

    /**
     * {@code heapMax <= 0} 必须返回 0 且<b>不做除法</b>（P0-13 防御）。
     *
     * <p>{@code max = 0} 时除法会抛 {@code ArithmeticException}——采集线程上抛异常
     * 会让整个周期的快照丢掉，不只是这个字段。</p>
     */
    @Test
    void nonPositiveMaxReturnsZeroWithoutDividing() {
        assertEquals(0, MetricsCollector.heapPercentFloor(0, 0));
        assertEquals(0, MetricsCollector.heapPercentFloor(123_456_789L, 0),
            "max=0 must short-circuit, not divide (would throw ArithmeticException)");
        assertEquals(0, MetricsCollector.heapPercentFloor(123_456_789L, -1L));
        assertEquals(0, MetricsCollector.heapPercentFloor(123_456_789L, Long.MIN_VALUE));
        // 极端负数：若实现写成 `used / max` 而不先判守卫，这里会是一个巨大的负数
        assertEquals(0, MetricsCollector.heapPercentFloor(1L, Long.MIN_VALUE));
    }

    /** 空堆 → 0。 */
    @Test
    void emptyHeapIsZero() {
        assertEquals(0, MetricsCollector.heapPercentFloor(0, 1024 * MiB));
        assertEquals(0, MetricsCollector.heapPercentFloor(0, 1));
        assertEquals(0, MetricsCollector.heapPercentFloor(0, Long.MAX_VALUE));
    }

    /** 满堆 → 100（不是 99）。 */
    @Test
    void fullHeapIsHundred() {
        long[] heaps = {1, 2 * MiB, 1024 * MiB, 300_000_000L, 64 * GiB, TiB};
        for (long max : heaps) {
            assertEquals(100, MetricsCollector.heapPercentFloor(max, max),
                "a completely full heap of " + max + " bytes must read 100%, not 99");
        }
    }

    // ==================== 大堆不溢出 ====================

    /**
     * {@code 100L * used} 在 1TiB 量级仍远小于 {@code Long.MAX_VALUE}（9.2e18），
     * {@code 100 * 1TiB ≈ 1.1e17}，安全。
     *
     * <p>若实现退回 {@code int}（{@code 100 * used}），1TiB 的一半就会溢出成负数——
     * 这条用例正是为了把 {@code int} 的中途溢出挡在外面。</p>
     */
    @Test
    void hugeHeapsDoNotOverflow() {
        assertEquals(50, MetricsCollector.heapPercentFloor(TiB / 2, TiB),
            "half of 1TiB must be 50%; 100 * (1TiB/2) overflows int and would go negative");
        assertEquals(100, MetricsCollector.heapPercentFloor(TiB, TiB));
        assertEquals(0, MetricsCollector.heapPercentFloor(0, TiB));

        // 4TiB：100 * used 已到 2.2e17，仍是 long 范围内
        long big = 4 * TiB;
        assertEquals(50, MetricsCollector.heapPercentFloor(big / 2, big));
        assertEquals(25, MetricsCollector.heapPercentFloor(big / 4, big));

        // 极端但仍有意义的上界：8TiB
        long huge = 8 * TiB;
        assertEquals(75, MetricsCollector.heapPercentFloor(huge * 3 / 4, huge));
    }

    /** 结果恒在 0-100 内，且对任何合法输入都不越界。 */
    @Test
    void resultIsAlwaysWithinZeroToHundred() {
        long max = 6 * GiB;
        for (long used = 0; used <= max; used += max / 97) {
            int pct = MetricsCollector.heapPercentFloor(used, max);
            assertTrue(pct >= 0 && pct <= 100,
                "used=" + used + " max=" + max + " produced out-of-range percent " + pct);
        }
        // used > max（理论上不该发生，但 Runtime 的 used 由外部决定）也不该爆
        int over = MetricsCollector.heapPercentFloor(max * 2, max);
        assertEquals(200, over,
            "used > max is not expected, but the helper must stay a pure ratio "
                + "and let the caller decide — never throw or wrap");
    }
}
