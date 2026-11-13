/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CPU 迟滞状态转移 {@code MetricsCollector.latched(...)} 的真值表。
 *
 * <p><b>为什么值得单测</b>：这条函数决定"CPU 越过阈值后，要跌到多低才算回落"。
 * 它有三个相互纠缠的出口——进入用 {@code value > threshold}、维持用 {@code value >= exit}、
 * 以及 {@code exit <= 0} 的兜底——任何一个写错都<b>不报错</b>，
 * 只表现为"告警永远挂着"或"边界上疯狂翻转"。</p>
 *
 * <p><b>代码层注记（不改变行为，仅记录读到的实现）</b>：方法体里保持态那一支写的是
 * {@code latched ? value >= exit : value > threshold}——即<b>带内不改变状态</b>，
 * 这也是类注释与调用侧的意图（"落在 [threshold-margin, threshold] 的样本保持上一状态"）。
 * 本测试按这个意图断言；若有人把保持支改成无状态判据（例如无条件 {@code value > threshold}），
 * 真值表里"进入后跌进带内仍保持"的那几行会先红。</p>
 */
class CpuHysteresisLatchTest {

    @BeforeAll
    static void initMessages() throws Exception {
        Class.forName("cn.perfscope.alert.AlertMessages").getMethod("init").invoke(null);
    }

    /** 直连私有纯函数；无副作用、不碰 Bukkit，故不需要任何实例状态。 */
    private static boolean latched(boolean latched, double value, double threshold, double margin)
        throws Exception {
        Method m = MetricsCollector.class.getDeclaredMethod(
            "latched", boolean.class, double.class, double.class, double.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, latched, value, threshold, margin);
    }

    // ==================== 未闩锁时的进入判据 ====================

    @Test
    void notLatchedEntersOnlyAboveThreshold() throws Exception {
        // 严格大于：等于阈值不进入（避免恰好 90.000 一遍遍开告警）
        assertFalse(latched(false, 90.0, 90.0, 5.0), "value == threshold must not enter");
        assertFalse(latched(false, 89.9, 90.0, 5.0));
        assertTrue(latched(false, 90.1, 90.0, 5.0), "value > threshold must enter");
        assertTrue(latched(false, 99.0, 90.0, 5.0));
    }

    // ==================== 已闩锁时的保持判据（核心） ====================

    /**
     * <b>这条是真值表里最关键的一行</b>：{@code latched=true, value=86, threshold=90, margin=5}。
     *
     * <p>退出线是 85，86 落在 {@code [85, 90]} 的迟滞带内 → 必须<b>保持闩锁</b>。
     * 把保持支写成 {@code value > threshold} 的变异体会在这里返回 false，
     * 迟滞带被整条抹掉，于是 86→91→86 这种边界呼吸会变成一串告警/恢复。</p>
     */
    @Test
    void latchedStaysLatchedInsideTheBand() throws Exception {
        assertTrue(latched(true, 86.0, 90.0, 5.0),
            "86 is inside [85, 90] for threshold=90 margin=5; the latch must hold");
    }

    @Test
    void latchedHoldsAtEveryPointInsideTheBand() throws Exception {
        for (double v = 85.0; v <= 89.999; v += 0.5) {
            assertTrue(latched(true, v, 90.0, 5.0),
                "inside the band (exit=85, threshold=90) the latch must hold, value=" + v);
        }
    }

    @Test
    void latchedHoldsAtTheExitBoundaryItself() throws Exception {
        // 退出线含端点（>= exit）：恰好 85.0 仍算"还在告警"
        assertTrue(latched(true, 85.0, 90.0, 5.0),
            "value == exit must still hold the latch (the exit comparison is inclusive)");
    }

    @Test
    void latchedReleasesBelowTheExitLine() throws Exception {
        assertFalse(latched(true, 84.99, 90.0, 5.0),
            "below exit (85) the latch must release");
        assertFalse(latched(true, 10.0, 90.0, 5.0));
    }

    @Test
    void latchedStaysWhenValueRisesAgain() throws Exception {
        assertTrue(latched(true, 95.0, 90.0, 5.0), "already latched and above threshold: remains latched");
    }

    // ==================== margin 的语义 ====================

    /** margin = 0 = 关闭迟滞（退回"只按阈值"的旧行为），此时无带可言。 */
    @Test
    void zeroMarginDisablesHysteresis() throws Exception {
        // 退出线 = 90；已闩锁时 89.99 必须释放
        assertFalse(latched(true, 89.99, 90.0, 0.0),
            "margin=0 means no band: a latched alert must release as soon as it drops below threshold");
        assertTrue(latched(true, 90.0, 90.0, 0.0),
            "margin=0: value == threshold is on the exit line, still latched");
        assertTrue(latched(false, 90.5, 90.0, 0.0));
    }

    /** margin 越大退出线越低：同样的值在 margin=5 时保持、在 margin=2 时释放。 */
    @Test
    void widerMarginKeepsTheLatchLonger() throws Exception {
        assertTrue(latched(true, 87.0, 90.0, 5.0), "exit=85, so 87 holds");
        assertFalse(latched(true, 87.0, 90.0, 2.0), "exit=88, so 87 releases");
    }

    // ==================== exit <= 0 的兜底 ====================

    /**
     * {@code margin >= threshold} 时裸减法得到非正退出线，而调用侧保证喂入值恒 {@code > 0}
     * （{@code collectCPU} 的哨兵过滤）——{@code value >= 非正数} 恒真，闩锁永不解除。
     * 兜底必须退回无迟滞判据。
     */
    @Test
    void nonPositiveExitFallsBackToPlainThreshold() throws Exception {
        // margin == threshold → exit == 0
        assertFalse(latched(true, 50.0, 50.0, 50.0),
            "exit == 0 must fall back to 'value > threshold', not latch forever");
        assertTrue(latched(true, 50.1, 50.0, 50.0),
            "with a non-positive exit the plain threshold still enters above it");

        // threshold == 3, margin 5 → exit == -2（配置校验会拦，但纯函数必须自身安全）
        assertFalse(latched(true, 2.5, 3.0, 5.0),
            "a negative exit must not keep the latch on a positive reading");
        assertTrue(latched(true, 3.5, 3.0, 5.0));
    }

    /** 兜底分支只在 {@code exit <= 0} 时生效：positive exit 仍走带内保持。 */
    @Test
    void positiveExitKeepsTheBandBehaviour() throws Exception {
        // exit = 0.001 > 0 → 走带内保持，0.5 在 [0.001, 1] 之间
        assertTrue(latched(true, 0.5, 1.0, 0.999),
            "a positive exit must keep the band semantics, not silently degrade");
    }

    /**
     * 状态机性质：从"未闩锁"出发喂一条单调下降序列，闩锁一旦进入就不会在带内脱落。
     *
     * <p>这是迟滞存在的<b>目的</b>本身，比单点真值更能反映"边界呼吸不翻状态"。</p>
     */
    @Test
    void latchIsMonotoneAcrossARisingThenFallingSweep() throws Exception {
        double threshold = 90.0, margin = 5.0;
        boolean state = false;
        // 上升穿过阈值
        for (double v = 80.0; v <= 100.0; v += 1.0) {
            state = latched(state, v, threshold, margin);
        }
        assertTrue(state, "the sweep crossed the threshold upwards, the latch must be set");

        // 回落但仍高于退出线：一路必须保持
        for (double v = 99.0; v >= 85.0; v -= 1.0) {
            state = latched(state, v, threshold, margin);
            assertTrue(state, "still above exit=85, the latch must hold at value=" + v);
        }
        // 跌破退出线才释放
        state = latched(state, 84.0, threshold, margin);
        assertFalse(state, "below exit=85 the latch must release");
    }
}
