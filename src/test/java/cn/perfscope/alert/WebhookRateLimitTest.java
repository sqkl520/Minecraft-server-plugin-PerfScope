/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端限流的参数（{@link WebhookNotifier#minIntervalMsFor}）。
 *
 * <p>这些数字不是拍脑袋来的，每条都能从平台的官方限额反推。测试锁住的正是这个反推过程，
 * 因为参数改错的表现是"某天开始告警悄悄少了"或"钉钉把机器人封了 10 分钟"，
 * 而这两种都不会抛异常。</p>
 */
class WebhookRateLimitTest {

    /**
     * 企业微信与钉钉都是 20 条/分钟。
     *
     * <p>理论最小值是 3000ms（60000/20）。取 4000ms 的理由：
     * 每分钟最多 15 条，留 25% 余量。为什么余量必须留——平台按"收到的请求"计窗口，
     * 失败的重试也占额度；而钉钉超限的代价是<b>封禁 10 分钟</b>，不是丢一条。</p>
     */
    @Test
    void wecomAndDingtalkUse4000ms() {
        assertEquals(4000L, WebhookNotifier.minIntervalMsFor("wecom"));
        assertEquals(4000L, WebhookNotifier.minIntervalMsFor("dingtalk"));

        // 反推：4000ms 间隔在任意 60 秒窗口内最多 16 条（含端点），低于官方 20 条上限
        int maxInWindow = (int) (60000 / 4000) + 1;
        assertTrue(maxInWindow <= 20,
            "4000ms must keep the worst-case 60s window at or below the platform cap of 20, got " + maxInWindow);
    }

    /**
     * 飞书有两个限额：100 次/分钟 与 5 次/秒。
     *
     * <p>按每秒口径推会得到 200ms，那对应 300 条/分钟——<b>超出分钟上限 3 倍</b>。
     * 所以必须按更严的分钟口径：60000/100 = 600ms，取 700ms。
     * 这条用例就是防止有人"优化"成 200ms。</p>
     */
    @Test
    void feishuUses700msNot200ms() {
        assertEquals(700L, WebhookNotifier.minIntervalMsFor("feishu"));

        int maxInWindow = (int) (60000 / 700) + 1;
        assertTrue(maxInWindow <= 100,
            "feishu caps at 100/min; 700ms gives " + maxInWindow + " which must not exceed it");

        // 明确否掉"按每秒 5 次推"的算法
        int perSecondDerived = (int) (60000 / 200) + 1;
        assertTrue(perSecondDerived > 100,
            "the 5-per-second figure would allow 301/min, so it must NOT be the one used");
    }

    @Test
    void discordAndOnebotParameters() {
        assertEquals(2000L, WebhookNotifier.minIntervalMsFor("discord"));
        // OneBot 是本地实现，没有平台级限额
        assertEquals(0L, WebhookNotifier.minIntervalMsFor("onebot"));
        assertEquals(0L, WebhookNotifier.minIntervalMsFor(null));
        assertEquals(0L, WebhookNotifier.minIntervalMsFor("unknown"));
    }

    /**
     * <b>容量必须为 1</b>（或至少不能大到把最坏窗口推过平台上限）。
     *
     * <p>这是最容易写错的一条：一个"令牌桶"直觉上该有突发容量。钉钉的限额是
     * "任意 60 秒窗口内 ≤ 20 条"，容量 C、间隔 T 时的最坏窗口是 {@code C + floor(60000/T)}。</p>
     *
     * <p>本用例把这个不变式钉死，并顺便记录一个反直觉的事实：<b>在 4000ms 间隔下，
     * 容量 3 其实也还在 20 条以内</b>（3 + 15 = 18）。也就是说选 4000ms 而不是 3000ms，
     * 换来的正是"即使实现上留了一点突发余量也不会被封"的空间。
     * 若有人把间隔调回 3000ms，容量 2 就会变成 22 条，触发封禁。</p>
     */
    @Test
    void worstCaseWindowStaysWithinPlatformCap() {
        long interval = WebhookNotifier.minIntervalMsFor("dingtalk");
        int perWindow = (int) (60000 / interval);

        // 容量 1（本实现的实际情况）必须安全
        assertTrue(1 + perWindow <= 20,
            "capacity 1 gives " + (1 + perWindow) + " messages per 60s, must be <= 20");

        // 4000ms 下即使容量 3 也仍在上限内 —— 这是选 4000ms 而非 3000ms 的实际收益
        assertTrue(3 + perWindow <= 20,
            "with 4000ms there is headroom for a small burst: " + (3 + perWindow) + " <= 20");

        // 反证：若把间隔改回 3000ms，容量 2 就会越界。这条锁住"别把间隔调小"。
        int perWindowAt3000 = 60000 / 3000;
        assertTrue(2 + perWindowAt3000 > 20,
            "3000ms leaves no room: capacity 2 gives " + (2 + perWindowAt3000)
                + " which would trigger DingTalk's ban - keep the interval at 4000");
    }
}
