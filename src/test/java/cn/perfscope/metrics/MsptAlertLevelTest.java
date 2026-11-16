/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MetricsCollector#msptAlertLevel(double, double, double)} —— MSPT 的级别判定内核。
 *
 * <h2>为什么单独抽出来测</h2>
 *
 * <p>{@code collectMSPT} 的第一行是 {@code ServerCompat.isMsptTrustworthy()}，而
 * {@code ServerCompat} 的 {@code <clinit>} 要 {@code Bukkit.getBukkitVersion()}——
 * 测试 JVM 里必炸。于是"哨兵有没有被正确表达成没判定"这件事此前<b>零保护</b>，
 * 而它正是那个「采集坏了、告警却报已恢复」的 bug 的一半（另一半在 {@code AlertService}）。</p>
 *
 * <p>抽成纯函数后输入只剩三个 {@code double}，问题从"怎么造一个 CraftServer"变成"怎么调一个函数"。</p>
 *
 * <h2>三条改坏后不报错的语义</h2>
 *
 * <ol>
 *   <li><b>{@code <= 0} 必须归入"没判定"</b>。写成 {@code msptAvg < 0} 会让 {@code 0}
 *       落进"健康"分支——那是宣称一个从未被测量的维度是健康的；写成"整块跳过且不写键"
 *       会让告警层把它读成"本周期不适用"并宣布「已恢复正常」。</li>
 *   <li><b>阈值比较必须是严格大于</b>。写成 {@code >=} 时，恰好等于阈值的样本被判越界——
 *       默认阈值下 {@code mspt = 45.0} 会开一条"机械盘刚好在线上"的告警。</li>
 *   <li><b>两级顺序</b>：critical 必须先判。反过来写会让严重样本永远只报 1 级。</li>
 * </ol>
 */
class MsptAlertLevelTest {

    private static final double WARN = 45.0;
    private static final double CRIT = 50.0;

    // ==================== 哨兵：本周期没有判定结果 ====================

    /**
     * <b>本类最关键的一条。</b>
     *
     * <p>{@code -1} 是 TPS 反推路径的显式哨兵，{@code 0} 在物理上不可能是一次真实 tick 的耗时。
     * 两者都必须返回"没有判定结果"，而不是 0（健康）或"什么都不写"（不适用）。
     * 返回 0 时，用户会收到一条「已恢复正常」——而那个维度从头到尾没有被测量过。</p>
     */
    @Test
    void nonPositiveMsptMeansNoVerdict() {
        assertEquals((double) MetricsSnapshot.ALERT_LEVEL_NO_VERDICT,
            (double) MetricsCollector.msptAlertLevel(-1.0, WARN, CRIT),
            "the -1 sentinel (TPS-fallback path has no MSPT) must be reported as \"no verdict\": "
                + "treating it as 0 would claim a health we never measured");
        assertEquals((double) MetricsSnapshot.ALERT_LEVEL_NO_VERDICT,
            (double) MetricsCollector.msptAlertLevel(0.0, WARN, CRIT),
            "0 ms per tick is physically impossible; it is a placeholder, not a healthy reading");
        assertEquals((double) MetricsSnapshot.ALERT_LEVEL_NO_VERDICT,
            (double) MetricsCollector.msptAlertLevel(-0.01, WARN, CRIT));
        assertEquals((double) MetricsSnapshot.ALERT_LEVEL_NO_VERDICT,
            (double) MetricsCollector.msptAlertLevel(Double.NaN, WARN, CRIT),
            "NaN must not slip into a level: comparisons against NaN are all false, so an unguarded "
                + "implementation would silently return 0 = \"healthy\"");
    }

    /** 哨兵标记必须是负值：渲染层靠 `< 0` 把它挡在 Prometheus 的 0/1/2 契约之外。 */
    @Test
    void noVerdictMarkerIsNegative() {
        assertEquals(-1, MetricsSnapshot.ALERT_LEVEL_NO_VERDICT,
            "the marker must be negative so /metrics can drop it with a single `< 0` guard; "
                + "a non-negative marker would be emitted as a real sample and trip `perfscope_alert < 1`");
    }

    // ==================== 正常区间 ====================

    /** 低于警告阈值（含恰好等于）→ 0，健康。等于阈值不算越界。 */
    @Test
    void valuesAtOrBelowWarningThresholdAreHealthy() {
        assertEquals(0, MetricsCollector.msptAlertLevel(1.0, WARN, CRIT));
        assertEquals(0, MetricsCollector.msptAlertLevel(WARN, WARN, CRIT),
            "a sample exactly at the threshold must stay healthy: `>=` here would alert on 45.0 "
                + "with the default 45ms warning threshold");
        assertEquals(0, MetricsCollector.msptAlertLevel(44.999, WARN, CRIT));
    }

    /** 超过警告、未超严重 → 1。恰好等于严重阈值仍是 1。 */
    @Test
    void aboveWarningButNotCriticalIsLevelOne() {
        assertEquals(1, MetricsCollector.msptAlertLevel(45.001, WARN, CRIT));
        assertEquals(1, MetricsCollector.msptAlertLevel(CRIT, WARN, CRIT),
            "exactly at the critical threshold is still a warning, not a critical");
        assertEquals(1, MetricsCollector.msptAlertLevel(49.99, WARN, CRIT));
    }

    /** 超过严重 → 2。 */
    @Test
    void aboveCriticalIsLevelTwo() {
        assertEquals(2, MetricsCollector.msptAlertLevel(50.001, WARN, CRIT));
        assertEquals(2, MetricsCollector.msptAlertLevel(500.0, WARN, CRIT));
    }

    /**
     * 两级顺序：严重必须先判。
     *
     * <p>若实现把 warning 放在前面并且用 {@code else if} 收尾，严重样本会永远停在第 1 级——
     * 一条 500ms 的卡顿被渲染成黄色警告，用户不会去查。</p>
     */
    @Test
    void criticalIsCheckedBeforeWarning() {
        assertEquals(2, MetricsCollector.msptAlertLevel(100.0, 10.0, 20.0),
            "a sample far above both thresholds must be critical; checking warning first would "
                + "cap every alert at level 1");
    }
}
