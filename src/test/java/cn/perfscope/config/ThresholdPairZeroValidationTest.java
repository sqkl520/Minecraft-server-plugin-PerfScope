/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.config;

import cn.perfscope.PerfScope;
import cn.perfscope.support.AlertFixtures;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阈值对取 {@code 0} 必须被拒绝（v1.4）。
 *
 * <p><b>这条锁的是什么</b>：{@code players.warning_threshold: 0} + {@code critical_threshold: 0}
 * 在 v1.4 之前能通过 {@code 0 <= 0} 的校验，后果是 {@code DIAG-PLAYER-CAP} 永久 critical——
 * 1 人在线 / 200 上限也报「接近上限，新玩家可能进不来」。根因有两条，缺一不可：
 * 规则入口 {@code currentPercent < warning} 恒为 false（不再短路），而唯一的逃逸条件
 * {@code minPercent < warning - 10} 退化成 {@code < -10}，对 0-100 的百分比永假。</p>
 *
 * <p><b>为什么必须真的调用 validateThresholds</b>：{@code UpdateIntervalClampTest} 只反射读常量，
 * 把整段钳制删掉它仍然全绿。本类走与 {@code UpdateIntervalValidationTest} 相同的路径
 * （{@code Unsafe.allocateInstance} 造 manager，直调私有方法），断言值被真实写回。</p>
 *
 * <p><b>与"现有合法配置"的边界</b>：本类同时锁住 0 之外的一切都不能被误伤——
 * 默认值、边界值 {@code 1/1} 与 {@code 100/100}、以及 {@code mspt} 的 {@code 0/0}
 * 之外的小数配置。发布过的 6 个 jar 的 config.yml 里的阈值（80/95、2000/3000 等）
 * 全部落在合法区间内，见 {@code tools/check-config-compat.mjs} 的 9/9。</p>
 */
class ThresholdPairZeroValidationTest {

    /**
     * 消息层必须真的带模块表，否则 {@code Messages.get} 只会回显键名。
     *
     * <p>注意与 {@code UpdateIntervalValidationTest} 的区别：那个类传的是 {@code load(null)}，
     * 走的是"只有内置英文表、不合并模块"的短路分支——它只断言数字，所以无所谓。
     * 本类要断言<b>告警文案内容</b>，必须走 {@link cn.perfscope.alert.AlertServiceStateMachineFixture}
     * 的那条路（裸 plugin + 逐模块 init），否则永远只会读到 {@code config.check.threshold-zero} 这个键名。</p>
     */
    @BeforeAll
    static void initMessages() throws Exception {
        cn.perfscope.alert.AlertServiceStateMachineFixture.initMessages();
    }

    /** 只装 {@code plugin}（validateThresholds 只用它取 logger），不启动 Bukkit。 */
    private static ConfigManager manager(Logger logger) {
        PerfScope p = AlertFixtures.barePlugin(logger);
        return new ConfigManager(p);
    }

    private static ConfigManager manager() {
        return manager(Logger.getLogger("pscope-threshold-zero-test"));
    }

    /** 其余项全部合法的配置；断言只反映被测的那一对阈值。 */
    private static ConfigManager.ConfigData validConfig() {
        ConfigManager.ConfigData c = new ConfigManager.ConfigData();
        c.general_updateInterval = 20;
        c.general_language = "en";
        c.tps_warningThreshold = 18.0;
        c.tps_criticalThreshold = 15.0;
        c.mspt_warningThreshold = 45.0;
        c.mspt_criticalThreshold = 50.0;
        c.players_warningThreshold = 90;
        c.players_criticalThreshold = 98;
        c.entities_totalWarningThreshold = 20000;
        c.entities_totalCriticalThreshold = 30000;
        c.chunks_loadedWarningThreshold = 10000;
        c.chunks_loadedCriticalThreshold = 15000;
        c.memory_heapWarningThreshold = 85;
        c.memory_heapCriticalThreshold = 95;
        c.cpu_processWarningThreshold = 80.0;
        c.cpu_systemWarningThreshold = 90.0;
        c.cpu_hysteresisMargin = 5.0;
        c.alerts_cooldownSeconds = 300;
        c.alerts_debounceCycles = 3;
        c.alerts_dailySummaryTime = "08:00";
        c.alerts_webhookType = "discord";
        c.history_sampleIntervalSeconds = 60;
        c.history_persistRetentionDays = 7;
        c.hotspots_sampleEveryCycles = 30;
        c.hotspots_chunkWarningThreshold = 200;
        c.hotspots_scanShards = 10;
        c.prometheus_port = 8940;
        c.prometheus_path = "/metrics";
        return c;
    }

    private static void validate(ConfigManager cm, ConfigManager.ConfigData cfg) {
        TestObjects.invoke(cm, "validateThresholds",
            new Class<?>[]{ConfigManager.ConfigData.class}, cfg);
    }

    private static ConfigManager.ConfigData validated(ConfigManager.ConfigData cfg) throws Exception {
        validate(manager(), cfg);
        return cfg;
    }

    // ------------------------------------------------------------------------------------
    // 1. 拒绝 0：单个指标
    // ------------------------------------------------------------------------------------

    /** 任务报告里的原例：players 0/0 必须回退默认 90/98。 */
    @Test
    void playersZeroZeroFallsBackToDefaults() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.players_warningThreshold = 0;
        cfg.players_criticalThreshold = 0;
        validated(cfg);
        assertEquals(90, cfg.players_warningThreshold,
            "0/0 passes `0 <= 0` but makes DIAG-PLAYER-CAP fire forever; must fall back");
        assertEquals(98, cfg.players_criticalThreshold);
    }

    /** 只有 warning 为 0（0/95）：warning 档恒真，同样必须回退。 */
    @Test
    void playersZeroWarningFallsBackToDefaults() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.players_warningThreshold = 0;
        cfg.players_criticalThreshold = 95;
        validated(cfg);
        assertEquals(90, cfg.players_warningThreshold);
        assertEquals(98, cfg.players_criticalThreshold,
            "the whole pair is reverted, not just the offending half");
    }

    /** 只有 critical 为 0（85/0）属于倒置，本来就会被拦；确认仍走回退而不是被写坏。 */
    @Test
    void playersZeroCriticalIsRejected() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.players_warningThreshold = 85;
        cfg.players_criticalThreshold = 0;
        validated(cfg);
        assertEquals(90, cfg.players_warningThreshold);
        assertEquals(98, cfg.players_criticalThreshold);
    }

    @Test
    void memoryZeroPairFallsBack() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.memory_heapWarningThreshold = 0;
        cfg.memory_heapCriticalThreshold = 0;
        validated(cfg);
        assertEquals(85, cfg.memory_heapWarningThreshold);
        assertEquals(95, cfg.memory_heapCriticalThreshold);
    }

    @Test
    void entitiesZeroPairFallsBack() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.entities_totalWarningThreshold = 0;
        cfg.entities_totalCriticalThreshold = 0;
        validated(cfg);
        assertEquals(20000, cfg.entities_totalWarningThreshold);
        assertEquals(30000, cfg.entities_totalCriticalThreshold);
    }

    @Test
    void chunksZeroPairFallsBack() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.chunks_loadedWarningThreshold = 0;
        cfg.chunks_loadedCriticalThreshold = 0;
        validated(cfg);
        assertEquals(10000, cfg.chunks_loadedWarningThreshold);
        assertEquals(15000, cfg.chunks_loadedCriticalThreshold);
    }

    /** mspt 是高值差 double 对，判据 `msptAvg > threshold`；0/0 让严重档恒真。 */
    @Test
    void msptZeroPairFallsBack() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.mspt_warningThreshold = 0.0;
        cfg.mspt_criticalThreshold = 0.0;
        validated(cfg);
        assertEquals(45.0, cfg.mspt_warningThreshold, 1e-9);
        assertEquals(50.0, cfg.mspt_criticalThreshold, 1e-9);
    }

    /** worlds 覆盖对取 0 → 回退到（已校验的）全局值，不是硬编码默认。 */
    @Test
    void worldOverrideZeroPairFallsBackToGlobalValues() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.entities_totalWarningThreshold = 12345;
        cfg.entities_totalCriticalThreshold = 23456;
        ConfigManager.WorldSettings ws = new ConfigManager.WorldSettings();
        ws.worldName = "world";
        ws.entities_totalWarningThreshold = 0;
        ws.entities_totalCriticalThreshold = 0;
        cfg.worldSettings = new java.util.HashMap<>();
        cfg.worldSettings.put("world", ws);

        validated(cfg);
        assertEquals(12345, ws.entities_totalWarningThreshold,
            "a world override of 0 must fall back to the validated global value, not a literal");
        assertEquals(23456, ws.entities_totalCriticalThreshold);
    }

    // ------------------------------------------------------------------------------------
    // 2. 回归：负数 / 超上限 / 倒置仍然被拦（9A-6 行为不许退化）
    // ------------------------------------------------------------------------------------

    @Test
    void negativeStillFallsBack() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.players_warningThreshold = -1;
        cfg.players_criticalThreshold = 95;
        validated(cfg);
        assertEquals(90, cfg.players_warningThreshold);
        assertEquals(98, cfg.players_criticalThreshold);

        ConfigManager.ConfigData e = validConfig();
        e.entities_totalWarningThreshold = -5;
        e.entities_totalCriticalThreshold = 100;
        validated(e);
        assertEquals(20000, e.entities_totalWarningThreshold);
        assertEquals(30000, e.entities_totalCriticalThreshold);
    }

    /** 超过计数域上界 → 永远不触发，与 0 是同一错误的两端。 */
    @Test
    void aboveCountCeilingFallsBack() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.entities_totalWarningThreshold = 10_000_001;
        cfg.entities_totalCriticalThreshold = 10_000_002;
        validated(cfg);
        assertEquals(20000, cfg.entities_totalWarningThreshold);
        assertEquals(30000, cfg.entities_totalCriticalThreshold);
    }

    /** 超过百分比域上界（101）→ 同样回退；这是 v1.4 新增的上界。 */
    @Test
    void abovePercentCeilingFallsBack() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.players_warningThreshold = 101;
        cfg.players_criticalThreshold = 200;
        validated(cfg);
        assertEquals(90, cfg.players_warningThreshold);
        assertEquals(98, cfg.players_criticalThreshold);

        ConfigManager.ConfigData m = validConfig();
        m.memory_heapWarningThreshold = 101;
        m.memory_heapCriticalThreshold = 150;
        validated(m);
        assertEquals(85, m.memory_heapWarningThreshold);
        assertEquals(95, m.memory_heapCriticalThreshold);
    }

    @Test
    void invertedPairStillFallsBack() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.players_warningThreshold = 98;
        cfg.players_criticalThreshold = 90;
        validated(cfg);
        assertEquals(90, cfg.players_warningThreshold);
        assertEquals(98, cfg.players_criticalThreshold);

        ConfigManager.ConfigData c = validConfig();
        c.chunks_loadedWarningThreshold = 15000;
        c.chunks_loadedCriticalThreshold = 10000;
        validated(c);
        assertEquals(10000, c.chunks_loadedWarningThreshold);
        assertEquals(15000, c.chunks_loadedCriticalThreshold);
    }

    // ------------------------------------------------------------------------------------
    // 3. 合法值必须原样保留（含边界值）
    // ------------------------------------------------------------------------------------

    /** 默认值、以及每个指标的"最小合法对"和"百分比域上界"都必须一字不改。 */
    @Test
    void legalValuesArePreserved() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        validated(cfg);
        assertEquals(90, cfg.players_warningThreshold);
        assertEquals(98, cfg.players_criticalThreshold);
        assertEquals(20000, cfg.entities_totalWarningThreshold);
        assertEquals(30000, cfg.entities_totalCriticalThreshold);
        assertEquals(10000, cfg.chunks_loadedWarningThreshold);
        assertEquals(15000, cfg.chunks_loadedCriticalThreshold);
        assertEquals(85, cfg.memory_heapWarningThreshold);
        assertEquals(95, cfg.memory_heapCriticalThreshold);
        assertEquals(45.0, cfg.mspt_warningThreshold, 1e-9);
        assertEquals(50.0, cfg.mspt_criticalThreshold, 1e-9);
    }

    /** 下界 1 本身合法：1/1 是极灵敏但语义自洽的配置，不能被"防 0"的闸误伤。 */
    @Test
    void boundaryOneIsLegal() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.players_warningThreshold = 1;
        cfg.players_criticalThreshold = 1;
        cfg.entities_totalWarningThreshold = 1;
        cfg.entities_totalCriticalThreshold = 1;
        validated(cfg);
        assertEquals(1, cfg.players_warningThreshold);
        assertEquals(1, cfg.players_criticalThreshold);
        assertEquals(1, cfg.entities_totalWarningThreshold);
        assertEquals(1, cfg.entities_totalCriticalThreshold);
    }

    /** 上界 100 本身合法（百分比域的闭区间上端）。 */
    @Test
    void boundaryHundredIsLegal() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.players_warningThreshold = 100;
        cfg.players_criticalThreshold = 100;
        cfg.memory_heapWarningThreshold = 99;
        cfg.memory_heapCriticalThreshold = 100;
        validated(cfg);
        assertEquals(100, cfg.players_warningThreshold);
        assertEquals(100, cfg.players_criticalThreshold);
        assertEquals(99, cfg.memory_heapWarningThreshold);
        assertEquals(100, cfg.memory_heapCriticalThreshold);
    }

    /** 计数域的合法大值不被百分比上界误伤：这正是"一刀切 100"会犯的错。 */
    @Test
    void largeCountValuesAreLegal() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.entities_totalWarningThreshold = 500_000;
        cfg.entities_totalCriticalThreshold = 750_000;
        cfg.chunks_loadedWarningThreshold = 1_000_000;
        cfg.chunks_loadedCriticalThreshold = 2_000_000;
        validated(cfg);
        assertEquals(500_000, cfg.entities_totalWarningThreshold);
        assertEquals(750_000, cfg.entities_totalCriticalThreshold);
        assertEquals(1_000_000, cfg.chunks_loadedWarningThreshold);
        assertEquals(2_000_000, cfg.chunks_loadedCriticalThreshold);
    }

    /** 亚毫秒 mspt 阈值：激进但自洽（判据是 >），不该被当 0 拦掉。 */
    @Test
    void subMillisecondMsptIsLegal() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.mspt_warningThreshold = 0.5;
        cfg.mspt_criticalThreshold = 1.0;
        validated(cfg);
        assertEquals(0.5, cfg.mspt_warningThreshold, 1e-9);
        assertEquals(1.0, cfg.mspt_criticalThreshold, 1e-9);
    }

    /**
     * TPS 的 {@code 0} 是合法惯用法，必须<b>不被</b>这次改动误伤。
     *
     * <p>tps 是低值差指标，判据 {@code tps1m < threshold}。阈值 0 让告警永不触发
     * （分支走到 else → 记 0 级），是一种自洽的"关掉 TPS 告警"。这与 players 的
     * {@code pct >= threshold}（恒真）正好相反，所以一刀切"拒绝所有 0"是错的方向。</p>
     */
    @Test
    void tpsZeroIsStillLegal() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.tps_warningThreshold = 0.0;
        cfg.tps_criticalThreshold = 0.0;
        validated(cfg);
        assertEquals(0.0, cfg.tps_warningThreshold, 1e-9,
            "tps is low-is-bad (`tps < threshold`), so 0 = never alert -- a self-consistent way to "
                + "mute the rule; rejecting it would be a breaking change for no benefit");
        assertEquals(0.0, cfg.tps_criticalThreshold, 1e-9);
    }

    // ------------------------------------------------------------------------------------
    // 4. 报警可见：回退不能静默
    // ------------------------------------------------------------------------------------

    /**
     * 模块已禁用（{@code players.enabled = false}）时阈值仍被校验。
     *
     * <p>这是刻意保留的：{@code enabled: false} 之后阈值确实用不上，但把"禁用模块里的 0"
     * 也放行会让校验规则依赖另一个布尔量，用户重新启用模块的那一刻就发现自己踩进了永久告警。
     * 行为上只是多一条 warning + 把值改回默认（用户随时可改回），代价远小于"开关一翻就坏"。</p>
     */
    @Test
    void validationRunsEvenWhenTheModuleIsDisabled() throws Exception {
        ConfigManager.ConfigData cfg = validConfig();
        cfg.players_enabled = false;
        cfg.players_warningThreshold = 0;
        cfg.players_criticalThreshold = 0;
        validated(cfg);
        assertEquals(90, cfg.players_warningThreshold);
        assertEquals(98, cfg.players_criticalThreshold);
    }

    /** 用户必须能在控制台看到"你写 0 了、被回退了、想关请用 enabled: false"。 */
    @Test
    void zeroFallbackIsLoggedWithTheEnableHint() throws Exception {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger("pscope-threshold-zero-log-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.WARNING && r.getMessage() != null) {
                    warnings.add(r.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(h);
        try {
            ConfigManager.ConfigData cfg = validConfig();
            cfg.players_warningThreshold = 0;
            cfg.players_criticalThreshold = 0;
            validate(manager(logger), cfg);
        } finally {
            logger.removeHandler(h);
        }
        String joined = String.join("\n", warnings);
        assertTrue(joined.contains("players.warning_threshold"),
            "the warning must name the offending key; got: " + joined);
        assertTrue(joined.contains("enabled: false"),
            "the warning must tell the user the correct way to disable the metric; got: " + joined);
    }

    /**
     * 上界越界必须用「1 ≤ 值 ≤ 上界」的文案，不能沿用「0 ≤ 值 ≤ 上界」。
     *
     * <p>101 被拒时若提示下界是 0，用户会以为 0 合法，改成 0 后撞上另一条回退——
     * 两次 reload 都在同一对键上失败，而这本可以在一次日志里说清。</p>
     */
    @Test
    void aboveCeilingWarningStatesTheRealLowerBound() throws Exception {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger("pscope-threshold-ceiling-log-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.WARNING && r.getMessage() != null) {
                    warnings.add(r.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(h);
        try {
            ConfigManager.ConfigData cfg = validConfig();
            cfg.players_warningThreshold = 101;
            cfg.players_criticalThreshold = 102;
            validate(manager(logger), cfg);
        } finally {
            logger.removeHandler(h);
        }
        String joined = String.join("\n", warnings);
        assertTrue(joined.contains("players.warning_threshold"), "got: " + joined);
        assertTrue(joined.contains("100"),
            "the warning must state the metric's ceiling; got: " + joined);
        assertTrue(joined.contains("1 <= value") || joined.contains("1 ≤"),
            "the lower bound must be stated as 1, not 0; got: " + joined);
    }
}
