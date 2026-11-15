/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.command;

import cn.perfscope.config.ConfigManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code /perfscope trend} 的数据缺口判据必须读真实的采样间隔。
 *
 * <p><b>这条守的是什么</b>：{@code showTrendGaps} 用
 * {@code thresholdOf(cfgNow(), "history_sampleIntervalSeconds", 60.0)} 取期望采样间隔，
 * 而 {@code thresholdOf} 是一个手写 switch —— 新加字段时**很容易忘了加 case**。
 * 漏掉的后果不是"用默认值"这么温和：它会永远返回 fallback，于是把
 * {@code history.sample-interval-seconds} 设成 300 的服，每一对相邻样本（间隔 300 秒）
 * 都会被判成"缺口"（阈值 2×60=120 秒）——**整条 trend 刷满假缺口**，
 * 而服主看到的是"我的历史数据到处是洞"。</p>
 *
 * <p>这个 bug 真实发生过：{@code history_sampleIntervalSeconds} 的 case 曾经缺失，
 * 被一轮多 Agent 审计发现。</p>
 */
class ThresholdOfTest {

    private static double thresholdOf(ConfigManager.ConfigData cfg, String field, double fallback)
        throws Exception {
        Method m = PerfScopeCommand.class.getDeclaredMethod(
            "thresholdOf", ConfigManager.ConfigData.class, String.class, double.class);
        m.setAccessible(true);
        PerfScopeCommand instance = cn.perfscope.support.TestObjects.allocate(PerfScopeCommand.class);
        return (Double) m.invoke(instance, cfg, field, fallback);
    }

    /**
     * 采样间隔字段必须被 switch 认出，而不是落到 default。
     *
     * <p>判据：给一个与 fallback 明显不同的值（300 vs 60）。若 case 缺失，
     * 返回值会是 60，测试红。</p>
     */
    @Test
    void sampleIntervalFieldIsReadFromConfigNotSwallowedByDefault() throws Exception {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.history_sampleIntervalSeconds = 300;

        assertEquals(300.0, thresholdOf(cfg, "history_sampleIntervalSeconds", 60.0), 1e-9,
            "the switch must handle history_sampleIntervalSeconds; returning the fallback means every "
                + "pair of samples 300s apart gets reported as a data gap (threshold 2x60=120s)");
    }

    /** 其它已经在用的字段也要正常（防止修一个坏一个）。 */
    @Test
    void otherFieldsStillResolveCorrectly() throws Exception {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.history_persistRetentionDays = 14;
        cfg.mspt_criticalThreshold = 77.0;

        assertEquals(14.0, thresholdOf(cfg, "history_persistRetentionDays", 7.0), 1e-9);
        assertEquals(77.0, thresholdOf(cfg, "mspt_criticalThreshold", 50.0), 1e-9);
    }

    /** 未知字段仍然回退（这是设计，不是 bug）。配置对象为 null 时也回退。 */
    @Test
    void unknownFieldAndNullConfigFallBack() throws Exception {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        assertEquals(42.0, thresholdOf(cfg, "no-such-field", 42.0), 1e-9);
        assertEquals(42.0, thresholdOf(null, "history_sampleIntervalSeconds", 42.0), 1e-9);
    }
}
