/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时钟回拨（P1-N）：{@link SummaryPublisher#shouldSend} 在时间轴倒退时**不能**静默停发。
 *
 * <h2>这条 bug 的形状</h2>
 *
 * <p>原实现只有两条分支，而回拨让它们**同时**返回 false：</p>
 *
 * <pre>
 * intervalPassed = nowMs - lastSentAtMs >= intervalMs   // 回拨后是负数 → 恒 false
 * if (!changed &amp;&amp; !intervalPassed) return false;        // ← 没变化时命中
 * return changed ? intervalPassed : true;              // ← 有变化时命中（changed=true, intervalPassed=false）
 * </pre>
 *
 * <p>结果是一条包都不发，直到墙钟追回原时间点。回拨 1 天就停发 1 天，**全程无任何日志**。
 * 而触发场景全是运维日常：VM 从快照恢复、容器迁移、NTP 大步校正、宿主机挂起后恢复。</p>
 *
 * <h2>这里锁什么</h2>
 *
 * <p>只锁判据（纯函数），不启动 Bukkit：{@code shouldSend} 与 {@code isClockRollback} 都是
 * 静态、无副作用的。回拨导致的"状态重置 + 告警"在 {@code publish()} 里，那一路需要
 * Bukkit 服务端，见 {@code tools/KNOWN-TEST-LIMITS.md} 的口径。</p>
 *
 * <p><b>刻意不用合成轨迹</b>：这几个断言全是单点时间戳比较，合成序列在这里没有额外信息量。
 * 回拨本身是一个离散事件，用两个时间戳就完全确定。</p>
 */
class PublisherClockRollbackTest {

    private static final long INTERVAL_MS = 10_000L;
    /** 一个"已经发过一包"的锚点：2026-11-23T01:00:00Z 之类的真实量级，不是测试里的 0/1/2。 */
    private static final long SENT_AT = 1_796_000_000_000L;
    private static final String SAME = "20.0|20.0|20.0|12.5|3|true|10";

    // ==================== 判据本体 ====================

    /**
     * 回拨 1 小时：摘要一字未变也必须发。
     *
     * <p>这是最容易被漏掉的一半：{@code changed == false} 时原实现走的是
     * {@code !changed && !intervalPassed} 那条，与"有变化"那条是两个独立的失败点。
     * 只测"有变化"会漏掉空闲服——而空闲服恰恰是长时间不发、最可能跨过回拨的形态。</p>
     */
    @Test
    @DisplayName("回拨 1 小时：没变化也必须发（否则远端永久显示数据过期）")
    void rollbackOneHourStillSendsWhenUnchanged() {
        long nowAfterRollback = SENT_AT - 3_600_000L;
        assertTrue(SummaryPublisher.shouldSend(SAME, SAME, nowAfterRollback, SENT_AT, INTERVAL_MS),
            "clock moved back 1h; the publisher must send anyway, otherwise the proxy shows "
                + "stale data for a full hour with no local symptom");
    }

    /** 回拨 1 天：同上，量级只影响"停发多久"，不影响判据形状。 */
    @Test
    @DisplayName("回拨 1 天：同样必须发")
    void rollbackOneDayStillSendsWhenUnchanged() {
        long nowAfterRollback = SENT_AT - 86_400_000L;
        assertTrue(SummaryPublisher.shouldSend(SAME, SAME, nowAfterRollback, SENT_AT, INTERVAL_MS));
    }

    /** 回拨后摘要也变了：两条分支原本都会返回 false，这里一并覆盖。 */
    @Test
    @DisplayName("回拨且摘要已变：必须发")
    void rollbackWithChangedSemanticStillSends() {
        long nowAfterRollback = SENT_AT - 3_600_000L;
        assertTrue(SummaryPublisher.shouldSend("19.0|19.0|19.0|20.0|5|true|10", SAME,
            nowAfterRollback, SENT_AT, INTERVAL_MS));
    }

    /**
     * 回拨哪怕只有 1 毫秒也必须发。
     *
     * <p>边界：{@code nowMs == lastSentAtMs} 是"同一毫秒"，不算回拨（差值 0 合法，
     * 走正常路径）；{@code nowMs == lastSentAtMs - 1} 才是回拨。这两个相邻值必须判得不一样，
     * 否则把判据写成 {@code <=} 也能过上面几条宽量级的用例。</p>
     */
    @Test
    @DisplayName("回拨判据的边界：同一毫秒不算回拨，早 1 毫秒算")
    void rollbackBoundaryIsStrict() {
        assertEquals(false, SummaryPublisher.isClockRollback(SENT_AT, SENT_AT),
            "same millisecond is not a rollback");
        assertTrue(SummaryPublisher.isClockRollback(SENT_AT - 1L, SENT_AT),
            "1 ms earlier IS a rollback; writing the check as <= would let a real rollback through");
    }

    /**
     * {@code lastSentAtMs == 0} 是"从未发送"的哨兵，**不是**"1970 年发过一次"。
     *
     * <p>不排除它的话，首次发送之前的每一轮都会命中回拨分支——判据看起来仍然"能发"
     * （第一次本来就该发），但 {@code publish()} 会因此每轮打一条回拨告警并在重置状态。
     * 这条断言防的正是那种"行为对了、日志全错"的实现。</p>
     */
    @Test
    @DisplayName("哨兵 0（从未发送）不得被判成回拨")
    void neverSentIsNotARollback() {
        assertFalse(SummaryPublisher.isClockRollback(1_000L, 0L),
            "lastSentAtMs=0 means 'never sent', not 'sent at the epoch'; treating it as a "
                + "rollback would fire a bogus warning on every cycle before the first send");
        assertFalse(SummaryPublisher.isClockRollback(Long.MAX_VALUE, 0L));
    }

    // ==================== 正常路径不得被这次修复破坏 ====================

    /**
     * 时间正常前进时的四条老行为必须一字不变。
     *
     * <p>这是回拨分支的反向对照：如果判据被写成"无条件返回 true"（而不是只在回拨时返回 true），
     * 上面所有回拨用例照样全绿，而限频与心跳会一起失效——每周期都发，`push-interval-seconds`
     * 变成一句空话。所以必须同时钉住"没有回拨时，原本的节流仍然节流"。</p>
     */
    @Test
    @DisplayName("正常时间轴：节流 / 心跳 / 首次发送三条老行为不变")
    void normalTimelineBehaviourIsUnchanged() {
        assertTrue(SummaryPublisher.shouldSend(SAME, null, 1_000L, 0L, INTERVAL_MS),
            "first send must go out immediately");
        assertFalse(SummaryPublisher.shouldSend(SAME, SAME, SENT_AT + 5_000L, SENT_AT, INTERVAL_MS),
            "no change inside the interval must stay throttled");
        assertTrue(SummaryPublisher.shouldSend(SAME, SAME, SENT_AT + 20_000L, SENT_AT, INTERVAL_MS),
            "unchanged but past the interval must resend (heartbeat keeps collectedAt moving)");
        assertFalse(SummaryPublisher.shouldSend("19.0", SAME, SENT_AT + 5_000L, SENT_AT, INTERVAL_MS),
            "changed but still inside the interval must stay throttled");
    }

    // ==================== 告警文案里的时长渲染 ====================

    /**
     * {@link SummaryPublisher#formatDuration} 只取最大的两个单位。
     *
     * <p>为什么值得测：告警里写"检测到系统时钟回拨"而不写回拨了多久，等于没说——服主没法
     * 判断这是 NTP 校正了 3 秒还是从一周前的快照恢复。而裸毫秒数（{@code 3600000}）同样读不出来。</p>
     */
    @Test
    @DisplayName("时长渲染：只给最大的两个单位，秒只在没有更大单位时出现")
    void durationRenderingKeepsTwoMagnitudes() {
        assertEquals("1 小时", SummaryPublisher.formatDuration(3_600_000L),
            "exactly 1 hour must not render as '1 小时 0 分'");
        assertEquals("1 天 2 小时", SummaryPublisher.formatDuration(86_400_000L + 7_200_000L + 60_000L),
            "a day plus hours must drop minutes/seconds -- they carry no action value");
        assertEquals("23 分", SummaryPublisher.formatDuration(23L * 60_000L),
            "minutes are the largest unit here, so seconds must not tag along");
        assertEquals("3 秒", SummaryPublisher.formatDuration(3_000L),
            "only sub-minute values show seconds");
        assertEquals("0 秒", SummaryPublisher.formatDuration(0L));
    }

    /** 负值与超过 1 天但不足 2 小时的边界都不得渲染成负数或空串。 */
    @Test
    @DisplayName("时长渲染：负值钳成 0 秒，1 天整不渲染成 '1 天 0 小时'")
    void durationRenderingEdgesAreSafe() {
        assertEquals("0 秒", SummaryPublisher.formatDuration(-5_000L),
            "a negative duration is nonsense; clamp instead of printing '-5 秒'");
        assertEquals("1 天", SummaryPublisher.formatDuration(86_400_000L));
    }
}
