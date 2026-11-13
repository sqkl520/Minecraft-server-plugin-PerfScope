/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import cn.perfscope.metrics.MetricsSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷却门的真实状态机回归（{@link AlertService#isCooling}）。
 *
 * <p><b>靶点</b>：把 {@code isCooling} 改成恒 {@code false}，新告警之外的"同级别重推"
 * 就再也压不住。选 {@code memory:global} 而不是 {@code cpu:system} 是关键——
 * 后者在 {@code REPUSH_SUPPRESSIBLE_KEYS} 白名单里，还会被"数值变化幅度"那层抑制挡下，
 * 于是冷却失效会被另一道门掩盖，变异体<b>杀不死</b>。{@code memory:global} 两道门都不适用，
 * 冷却成了唯一约束。</p>
 *
 * <p>用例同时把"本夹具确实驱动了真实状态机"钉住：三次喂入后 {@code activeAlerts} 必须为 1，
 * {@code open} 事件必须为 1——若夹具只是把快照丢掉，这些计数会一起变成 0。</p>
 */
class AlertServiceCooldownTest {

    @BeforeAll
    static void initMessages() throws Exception {
        AlertServiceStateMachineFixture.initMessages();
    }

    /** 冷却 = 300 秒、防抖 = 1、无抖动窗口、无复燃窗口：唯一变量只剩冷却。 */
    private static AlertServiceStateMachineFixture fixture() throws Exception {
        return AlertServiceStateMachineFixture.builder()
            .cooldownSeconds(300)
            .debounceCycles(1)
            .flapWindowSeconds(0)
            .refireSeconds(0)
            .build();
    }

    /**
     * | t | 输入 | 期望累计推送 |
     * | 0 | alert | 1 |
     * | 1000 | alert | 1（冷却中） |
     * | 301000 | alert | 2（冷却已过期） |
     *
     * <p>三次喂入都用同一个数值，确保"重推"不会被数值变化判据额外放行——
     * 这样断言失败只可能来自冷却门本身。</p>
     */
    @Test
    void cooldownSuppressesRepushThenAllowsItAfterExpiry() throws Exception {
        AlertServiceStateMachineFixture f = fixture();

        f.feed("memory:global", Alert.LEVEL_WARNING, 90.0);
        assertEquals(1, f.pushedToday(), "the first alert must be pushed");

        // 1 秒后仍在 300 秒冷却内：状态机刷新记录但不推送
        f.feed("memory:global", Alert.LEVEL_WARNING, 90.0);
        assertEquals(1, f.pushedToday(),
            "a repeat inside the cooldown window must not be pushed (isCooling must return true)");

        // 把冷却记录写老到 301 秒前——等价于真实时钟走过了冷却期
        f.seedCooldown("memory:global", 301_000L);
        f.feed("memory:global", Alert.LEVEL_WARNING, 90.0);
        assertEquals(2, f.pushedToday(),
            "an alert whose cooldown has expired must be pushed again (isCooling must return false)");
    }

    /**
     * 夹具自检：状态机真的被驱动了。
     *
     * <p>没有这一条，上面那条"等于 1"的断言在"夹具什么都没做"时也会通过——
     * 一个永远为 0 的计数器能让一半的抑制类断言变成假绿。</p>
     */
    @Test
    void fixtureActuallyDrivesTheStateMachine() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        f.feed("memory:global", Alert.LEVEL_WARNING, 90.0);

        assertEquals(1, f.activeCount(), "the alert must land in activeAlerts");
        assertEquals(1L, f.eventCount("open"), "one open event must be recorded");
        assertEquals(1, f.pushedToday());
        // 冷却记录必须被写下，且是"刚刚"
        long age = f.cooldownAgeMs("memory:global");
        assertTrue(age >= 0 && age < 60_000L,
            "the cooldown record must be freshly written, got age=" + age);
    }

    /** 冷却为 0 = 关闭冷却：紧接着的第二次同 key 告警必须立即重推。 */
    @Test
    void zeroCooldownDisablesTheGate() throws Exception {
        AlertServiceStateMachineFixture f = AlertServiceStateMachineFixture.builder()
            .cooldownSeconds(0)
            .debounceCycles(1)
            .flapWindowSeconds(0)
            .refireSeconds(0)
            .build();

        f.feed("memory:global", Alert.LEVEL_WARNING, 90.0);
        f.feed("memory:global", Alert.LEVEL_WARNING, 90.0);
        assertEquals(2, f.pushedToday(),
            "cooldown-seconds=0 means no cooldown at all, so the repush goes through");
    }

    /** 冷却窗口内不同 key 互不影响：A 的冷却不得压住 B 的首次告警。 */
    @Test
    void cooldownIsPerKey() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        long ts = f.nextTimestamp();
        // 同一份快照里两条：单独喂两个 key 会让先那个被判为"本周期没出现"
        f.feedSnapshot(f.snapshotOf(ts,
            Alert.measured("memory:global", Alert.LEVEL_WARNING, 90.0, "memory", ts),
            Alert.measured("tps:global", Alert.LEVEL_WARNING, 12.0, "tps", ts)));
        assertEquals(2, f.pushedToday(),
            "a cooldown on one key must not silence a different key");
        assertEquals(2, f.activeCount());
    }

    /** 冷却记录写的是"推送时刻"，不是"首次观测时刻"：冷却年龄必须接近 0。 */
    @Test
    void cooldownIsStampedAtPushTime() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        f.feed("memory:global", Alert.LEVEL_WARNING, 90.0);
        long age = f.cooldownAgeMs("memory:global");
        assertTrue(age < 5_000L, "cooldown must be stamped at push time, got age=" + age);

        // 冷却被刷新后，窗口重新从头计时
        f.seedCooldown("memory:global", 299_000L);
        f.feed("memory:global", Alert.LEVEL_WARNING, 90.0);
        assertEquals(1, f.pushedToday(), "299s < 300s: still cooling, no repush");
        assertEquals(1, f.activeCount());
        // 注意：此路径（冷却中）不刷新 cooldown 记录，故年龄继续变大——锁住这个语义
        assertTrue(f.cooldownAgeMs("memory:global") >= 299_000L,
            "a suppressed repush must not refresh the cooldown, otherwise it can never expire");
    }

    /** 空快照（无告警）不得凭空推送，也不得把活跃告警当成已恢复（恢复需显式"判定过 0"）。 */
    @Test
    void emptySnapshotPushesNothing() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.timestamp = f.nextTimestamp();
        f.service().onSnapshot(snap);
        assertEquals(0, f.pushedToday(), "a snapshot with no alerts must push nothing");
        assertEquals(0, f.activeCount());
    }

    /** 同一时间戳的快照只消费一次——防重复消费守卫是夹具"时间戳必须唯一"要求的来源。 */
    @Test
    void duplicateTimestampIsDroppedByTheConsumeGuard() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        long ts = f.nextTimestamp();
        f.feed(ts, "memory:global", Alert.LEVEL_WARNING, 90.0);
        // 同一时间戳：第一段守卫直接 return，什么都不会发生
        f.feed(ts, "memory:global", Alert.LEVEL_CRITICAL, 99.0);
        assertEquals(1L, f.eventCount("open"), "the second snapshot shares a timestamp and must be dropped");
        assertEquals(0L, f.eventCount("upgrade"), "an upgrade cannot be observed through a dropped snapshot");
    }
}
