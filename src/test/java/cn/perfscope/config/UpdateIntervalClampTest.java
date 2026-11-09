/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code general.update_interval} 的合法区间钳制。
 *
 * <p>没有上限时 {@code update_interval: 1} 会让采集器每 tick 跑一次全量采集（20 次/秒的主线程
 * 全遍历），在实体多的服上直接把 TPS 拖垮。{@code MetricsCollector} 只有下限
 * {@code Math.max(1, configuredInterval)}——那个"下限"恰好放行了最危险的值。</p>
 *
 * <p>这些用例锁的是常量的语义边界，不依赖 Bukkit 运行时。改常量时这里会先红。</p>
 */
class UpdateIntervalClampTest {

    private static int constInt(String name) throws Exception {
        Field f = ConfigManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    @Test
    void minIsAboveOneTick() throws Exception {
        int min = constInt("UPDATE_INTERVAL_MIN");
        // 1 tick = 每 tick 全量采集 = 20 次/秒。杜绝。
        assertTrue(min > 1, "UPDATE_INTERVAL_MIN must be > 1 tick, was " + min);
    }

    @Test
    void minAllowsAtMostFourSamplesPerSecond() throws Exception {
        int min = constInt("UPDATE_INTERVAL_MIN");
        // 20 ticks/s ÷ min ticks ≥ 每秒采样次数。4 次/秒已是激进上界。
        double perSecond = 20.0 / min;
        assertTrue(perSecond <= 4.0,
            "UPDATE_INTERVAL_MIN=" + min + " allows " + perSecond + " samples/s, expected <= 4");
    }

    @Test
    void maxIsBounded() throws Exception {
        int max = constInt("UPDATE_INTERVAL_MAX");
        // 上限防止用户把间隔写成几天，让"已停止更新"判定失去意义。
        assertTrue(max <= 7200, "UPDATE_INTERVAL_MAX should be <= 7200 ticks (6 min), was " + max);
    }

    @Test
    void minLessThanMax() throws Exception {
        assertTrue(constInt("UPDATE_INTERVAL_MIN") < constInt("UPDATE_INTERVAL_MAX"));
    }

    @Test
    void defaultIsWithinRange() throws Exception {
        int dflt = constInt("UPDATE_INTERVAL_DEFAULT");
        int min = constInt("UPDATE_INTERVAL_MIN");
        int max = constInt("UPDATE_INTERVAL_MAX");
        assertTrue(dflt >= min && dflt <= max,
            "default " + dflt + " must be within [" + min + ", " + max + "]");
    }

    @Test
    void defaultMatchesDocumentedValue() throws Exception {
        // config.yml 的缺省是 20；两者必须一致，否则"回退默认值"会静默改变用户观测到的频率。
        assertEquals(20, constInt("UPDATE_INTERVAL_DEFAULT"));
    }
}
