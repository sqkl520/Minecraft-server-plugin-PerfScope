/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.network;

import cn.perfscope.PerfScope;
import cn.perfscope.util.Messages;
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
     * {@link SummaryPublisher#formatDuration} 只取最大的两个非零单位。
     *
     * <p>为什么值得测：告警里写"检测到系统时钟回拨"而不写回拨了多久，等于没说——服主没法
     * 判断这是 NTP 校正了 3 秒还是从一周前的快照恢复。而裸毫秒数（{@code 3600000}）同样读不出来。</p>
     *
     * <p><b>这条用例在 P2 改造后测的是英文</b>：原实现自带一份中文单位表，所以它断言的是
     * "1 小时"。改造后单位来自 {@code DurationFormat}（按 {@code Messages.activeLocale()} 切换），
     * 而本仓库的默认语言是 {@code en}——于是同一个毫秒数现在渲染成 {@code 1h}。
     * 中文侧由 {@link #durationRenderingIsLocalised()} 覆盖。</p>
     */
    @Test
    @DisplayName("时长渲染（英文）：只给最大的两个非零单位")
    void durationRenderingKeepsTwoMagnitudes() {
        assertEquals("1h", SummaryPublisher.formatDuration(3_600_000L),
            "exactly 1 hour must not render as '1h 0m'");
        assertEquals("1d 2h", SummaryPublisher.formatDuration(86_400_000L + 7_200_000L + 60_000L),
            "a day plus hours must drop minutes/seconds -- they carry no action value");
        assertEquals("23m", SummaryPublisher.formatDuration(23L * 60_000L),
            "minutes are the largest unit here, so seconds must not tag along");
        assertEquals("3s", SummaryPublisher.formatDuration(3_000L),
            "only sub-minute values show seconds");
        assertEquals("0s", SummaryPublisher.formatDuration(0L));
    }

    /** 负值与超过 1 天但不足 2 小时的边界都不得渲染成负数或空串。 */
    @Test
    @DisplayName("时长渲染（英文）：负值钳成 0s，1 天整不渲染成 '1d 0h'")
    void durationRenderingEdgesAreSafe() {
        assertEquals("0s", SummaryPublisher.formatDuration(-5_000L),
            "a negative duration is nonsense; clamp instead of printing '-5s'");
        assertEquals("1d", SummaryPublisher.formatDuration(86_400_000L));
    }

    /**
     * 同一毫秒数在中文界面下必须输出中文单位。
     *
     * <p><b>这条是 P2 修复的核心判据</b>：原实现硬编码 {@code {" 天"," 小时"," 分"," 秒"}}，
     * 无论语言是什么都输出中文。而它产出的片段会被拼进 {@code network.log.clock-rollback}
     * 这条<b>已经本地化</b>的句子——英文服主的日志里会整句英文夹着"1 小时"，
     * 正是 9D 要消灭的混排。</p>
     *
     * <p>单位表在 {@code DurationFormat} 里已有中英两份（{@code ZH_UNITS}/{@code EN_UNITS}），
     * 本类只负责给出毫秒数与窗口。这条用例通过真实入口 {@code Messages.load(plugin)}
     * 切语言，所以"语言 → 单位"这条链是真跑过的，不是反射改字段。</p>
     */
    @Test
    @DisplayName("时长渲染（中文）：单位与数字间有空格，1 小时整不写 '1 小时 0 分'")
    void durationRenderingIsLocalised() {
        try {
            switchLocale(Messages.LOCALE_ZH);
            assertEquals("1 时", SummaryPublisher.formatDuration(3_600_000L),
                "duration 的最高单位是「时」，不是「天」——与 DurationFormat 的既有口径一致");
            assertEquals("1 天 2 时", SummaryPublisher.formatDuration(86_400_000L + 7_200_000L + 60_000L));
            assertEquals("23 分", SummaryPublisher.formatDuration(23L * 60_000L));
            assertEquals("3 秒", SummaryPublisher.formatDuration(3_000L));
            assertEquals("0 秒", SummaryPublisher.formatDuration(0L));
            assertEquals("1 天", SummaryPublisher.formatDuration(86_400_000L));
        } finally {
            switchLocale(Messages.LOCALE_EN);
        }
    }

    /**
     * 把界面语言切到 {@code locale}，走真实的 {@link Messages#load(PerfScope)}。
     *
     * <p>与 {@code DurationFormatTest} / {@code ThresholdTextMatchesOperatorTest} 里的同名方法同口径：
     * {@code Messages.load} 只有在拿到<b>非 null</b> 的 plugin、且该 plugin 的
     * {@code configManager.get()} 非 null 时才会读到 {@code general.language}；
     * 传 {@code load(null)} 只会把快照重置成默认语言。</p>
     */
    private static void switchLocale(String locale) {
        cn.perfscope.config.ConfigManager cm =
            new cn.perfscope.config.ConfigManager(null);
        cn.perfscope.config.ConfigManager.ConfigData cfg =
            new cn.perfscope.config.ConfigManager.ConfigData();
        cfg.general_language = locale;
        cn.perfscope.support.TestObjects.setField(
            cn.perfscope.config.ConfigManager.class, cm, "config", cfg);

        PerfScope plugin = cn.perfscope.support.TestObjects.allocate(PerfScope.class);
        java.util.logging.Logger log =
            java.util.logging.Logger.getLogger("pscope-clock-rollback-" + locale);
        log.setUseParentHandlers(false);
        log.setLevel(java.util.logging.Level.OFF);
        cn.perfscope.support.TestObjects.setField(
            PerfScope.class, plugin, "logger", log);
        cn.perfscope.support.TestObjects.setField(
            PerfScope.class, plugin, "configManager", cm);

        Messages.load(plugin);
        assertEquals(locale, Messages.activeLocale(),
            "语言切换失败（Messages.load 没有读到 general.language=" + locale + "）");
    }
}
