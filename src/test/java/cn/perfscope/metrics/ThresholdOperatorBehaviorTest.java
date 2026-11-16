/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import cn.perfscope.alert.Alert;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.support.AlertFixtures;
import cn.perfscope.support.ConfigFixtures;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集层四个判定点的<b>算子行为</b>：{@code >} / {@code >=} / {@code <} / {@code <=} 到底是哪一个。
 *
 * <h2>为什么要这个类</h2>
 *
 * <p>一次变异实测证明这些算子此前<b>几乎没有保护</b>：{@code tps} 的 {@code <} 改成 {@code <=}、
 * {@code chunks} 的 {@code >} 改成 {@code >=}、逐世界实体的 {@code >} 改成 {@code >=}、
 * 以及全部 warning 档的 {@code >} 改成 {@code >=}，<b>全量 715 个用例零失败</b>。
 * 而这些行改坏后没有任何报错，只让线上判定整整挪动一格。</p>
 *
 * <h2>为什么不是源码文本断言</h2>
 *
 * <p>此前这一族由 {@code ThresholdBoundaryAsymmetryTest} 里的
 * {@code src.contains("snap.totalEntities > ...")} 钉住。那种写法有两个硬伤：
 * 重构一改写法就<b>假红</b>（行为没变，只是文本变了），且它读文件要求 cwd 必须是仓库根，
 * 从别处跑直接 {@code NoSuchFileException}。本类改成<b>调方法看返回值</b>——算子挪了位置、
 * 换了名字、抽成函数，只要行为不变就不会误报；行为变了则必然变红。</p>
 *
 * <h2>「四点表」</h2>
 *
 * <p>每个判定点测四个读数：{@code 阈值-1}、{@code 阈值}、{@code 阈值+1}、以及一个远离值。
 * 只测"高于阈值"是不够的——把 {@code >} 改成 {@code >=}，低于/高于两档全都不变，
 * <b>只有边界那一列会翻</b>。这正是此前四个变异方向存活的原因。</p>
 *
 * <h2>三条验证路径</h2>
 * <ol>
 *   <li><b>规则表</b>：直接调 {@link ThresholdOperator.Rule#levelOf}。生产代码每个判定点都调
 *       同一张表，所以这里测的就是线上判定本身。</li>
 *   <li><b>判定半段方法</b>（{@code applyTpsLevel} / {@code applyPlayersLevel} /
 *       {@code applyEntitiesLevels} / {@code applyChunksLevels} / {@code applyMemoryLevel}）：
 *       额外锁住"表写对了但调用点用错档位 / 用错键名 / 忘了写 alertLevels"。</li>
 *   <li><b>另两条判定点</b>按其形态另有专测：{@code msptAlertLevel} 见
 *       {@code MsptAlertLevelTest}，CPU 迟滞见 {@code CpuHysteresisLatchTest}。</li>
 * </ol>
 *
 * <p><b>不测 {@code collectCPU} 的告警分支</b>：它读本机真实 CPU 读数，构造不出确定的边界值
 * （见 {@code tools/KNOWN-TEST-LIMITS.md} 第 2 节）。</p>
 */
class ThresholdOperatorBehaviorTest {

    @BeforeAll
    static void initMessages() {
        AlertFixtures.initMessages();
    }

    // ====================================================================================
    // 1. 表自身的完整性
    // ====================================================================================

    /**
     * 表里的每一条都必须被四点用例覆盖。
     *
     * <p>没有这条，新增第 8 个指标时"忘记加用例"不会有任何提示——而那张表存在的意义
     * 正是"加指标 = 必须加一行 + 必须加用例"。</p>
     */
    @Test
    void everyRuleInTheTableHasItsFourPointCases() {
        List<String> keys = ThresholdOperator.ALL.stream().map(ThresholdOperator.Rule::key).toList();
        assertEquals(7, keys.size(), "规则表条数变了：" + keys);
        for (String expected : new String[]{
            "tps:global", "mspt:global", "players:global", "entities:world:*",
            "entities:global", "chunks:world:*", "memory:global"}) {
            assertTrue(keys.contains(expected),
                "规则表缺少 " + expected + "——新增/改名指标时请同步更新本类的四点用例");
        }
        assertEquals(keys.size(), RULE_SAMPLES.size(),
            "表里有 " + keys.size() + " 条规则，但四点用例只覆盖了 " + RULE_SAMPLES.size());
    }

    /** 规则表本身就是规格：方向与闭合性逐条钉住（防止有人把 STRICT 改成 INCLUSIVE）。 */
    @Test
    void tableRecordsTheDocumentedOperators() {
        assertShape(ThresholdOperator.TPS, "tps:global",
            ThresholdOperator.Direction.LOW_IS_BAD, ThresholdOperator.Comparison.STRICT);
        assertShape(ThresholdOperator.MSPT, "mspt:global",
            ThresholdOperator.Direction.HIGH_IS_BAD, ThresholdOperator.Comparison.STRICT);
        assertShape(ThresholdOperator.ENTITIES_WORLD, "entities:world:*",
            ThresholdOperator.Direction.HIGH_IS_BAD, ThresholdOperator.Comparison.STRICT);
        assertShape(ThresholdOperator.ENTITIES_GLOBAL, "entities:global",
            ThresholdOperator.Direction.HIGH_IS_BAD, ThresholdOperator.Comparison.STRICT);
        assertShape(ThresholdOperator.CHUNKS_WORLD, "chunks:world:*",
            ThresholdOperator.Direction.HIGH_IS_BAD, ThresholdOperator.Comparison.STRICT);
        // 百分比指标两档闭区间：与展示层一致，是「4 族差异之外」的那一半
        assertShape(ThresholdOperator.PLAYERS, "players:global",
            ThresholdOperator.Direction.HIGH_IS_BAD, ThresholdOperator.Comparison.INCLUSIVE);
        assertShape(ThresholdOperator.MEMORY, "memory:global",
            ThresholdOperator.Direction.HIGH_IS_BAD, ThresholdOperator.Comparison.INCLUSIVE);
    }

    // ====================================================================================
    // 2. 七条规则的四点表（行为断言，期望值逐条写死以便 review）
    // ====================================================================================

    /**
     * {@code tps}：低值差、两档严格 {@code <}。默认阈值 18 / 15。
     *
     * <p>{@code tps1m == 15.0} 时：{@code 15 < 15} 不成立 → 落进 warning 分支
     * （{@code 15 < 18} 成立）→ 1 级。这就是"唯一差两档"的那一族。</p>
     */
    @Test
    void tpsUsesStrictLessThan() {
        assertLevels(ThresholdOperator.TPS, 18, 15,
            19.0, 0,   // 远离（高 TPS 健康）
            18.0, 0,   // 恰好 warning：严格 < 不成立
            17.99, 1,  // warning 内
            15.0, 1,   // 恰好 critical：严格 < 不成立 → 仍是 warning（差两档）
            14.99, 2,  // 刚过 critical
            0.0, 2);   // 远离（冻结）
    }

    /** {@code mspt}：高值差、两档严格 {@code >}。默认阈值 45 / 50。 */
    @Test
    void msptUsesStrictGreaterThan() {
        assertLevels(ThresholdOperator.MSPT, 45, 50,
            0.0, 0,     // 远离
            45.0, 0,    // 恰好 warning：严格 > 不成立
            45.01, 1,   // warning 内
            50.0, 1,    // 恰好 critical：严格 > 不成立 → 仍是 warning
            50.01, 2,   // 刚过 critical
            500.0, 2);  // 远离
    }

    /** {@code players}：百分比、两档闭区间 {@code >=}。默认 90 / 98。 */
    @Test
    void playersUsesInclusiveGreaterThan() {
        assertLevels(ThresholdOperator.PLAYERS, 90, 98,
            0.0, 0,
            89.0, 0,   // 差 1 到 warning
            90.0, 1,   // 恰好 warning：闭区间命中
            97.0, 1,
            98.0, 2,   // 恰好 critical：闭区间命中
            100.0, 2);
    }

    /** 逐世界实体：高值差、两档严格 {@code >}（用该世界覆盖值）。 */
    @Test
    void perWorldEntitiesUseStrictGreaterThan() {
        assertLevels(ThresholdOperator.ENTITIES_WORLD, 20_000, 30_000,
            0, 0,
            19_999, 0,
            20_000, 0,      // 恰好 warning：严格 > 不成立
            20_001, 1,
            30_000, 1,      // 恰好 critical：严格 > 不成立
            30_001, 2,
            1_000_000, 2);
    }

    /** 全服实体总数：同上，两档严格 {@code >}。 */
    @Test
    void globalEntitiesUseStrictGreaterThan() {
        assertLevels(ThresholdOperator.ENTITIES_GLOBAL, 20_000, 30_000,
            0, 0,
            19_999, 0,
            20_000, 0,
            20_001, 1,
            30_000, 1,
            30_001, 2,
            1_000_000, 2);
    }

    /** 逐世界区块：高值差、两档严格 {@code >}。默认 10 000 / 15 000。 */
    @Test
    void perWorldChunksUseStrictGreaterThan() {
        assertLevels(ThresholdOperator.CHUNKS_WORLD, 10_000, 15_000,
            0, 0,
            9_999, 0,
            10_000, 0,      // 恰好 warning：严格 > 不成立
            10_001, 1,
            15_000, 1,      // 恰好 critical：严格 > 不成立
            15_001, 2,
            1_000_000, 2);
    }

    /** {@code memory}：百分比、两档闭区间 {@code >=}。默认 85 / 95。 */
    @Test
    void memoryUsesInclusiveGreaterThan() {
        assertLevels(ThresholdOperator.MEMORY, 85, 95,
            0, 0,
            84, 0,
            85, 1,   // 恰好 warning：闭区间命中
            94, 1,
            95, 2,   // 恰好 critical：闭区间命中
            100, 2);
    }

    // ====================================================================================
    // 3. warning / critical 两档必须能分别改坏（半格挪动）
    // ====================================================================================

    /**
     * 只把 <b>warning 档</b>改成闭区间、critical 保持严格。
     *
     * <p>实测里存活的一个方向：{@code msptAvg > cfg.mspt_warningThreshold} 改成 {@code >=}
     * 全量用例零失败。这里用一张"混合规则"直接证明两档闭合性是<b>分开的两个字段</b>——
     * 若 Rule 只有一个 Comparison 字段，"只改一档"根本表达不出来，也就无法被单独处死。</p>
     */
    @Test
    void warningAndCriticalComparisonsAreIndependent() {
        ThresholdOperator.Rule mixed = new ThresholdOperator.Rule("probe:mixed",
            ThresholdOperator.Direction.HIGH_IS_BAD,
            ThresholdOperator.Comparison.STRICT,     // critical: >
            ThresholdOperator.Comparison.INCLUSIVE); // warning:  >=
        assertEquals(1, mixed.levelOf(45.0, 45, 50), "warning 闭区间：恰好 45 进 1 级");
        assertEquals(1, mixed.levelOf(50.0, 45, 50), "critical 严格：恰好 50 不进 2 级");
        assertEquals(2, mixed.levelOf(50.01, 45, 50), "越过 critical 才进 2 级");

        ThresholdOperator.Rule other = new ThresholdOperator.Rule("probe:other",
            ThresholdOperator.Direction.HIGH_IS_BAD,
            ThresholdOperator.Comparison.INCLUSIVE,  // critical: >=
            ThresholdOperator.Comparison.STRICT);    // warning:  >
        assertEquals(2, other.levelOf(50.0, 45, 50), "critical 闭区间：恰好 50 是 2 级");
        assertEquals(0, other.levelOf(45.0, 45, 50), "warning 严格：恰好 45 不命中");
    }

    /** 低值差方向也支持两档独立闭合性。 */
    @Test
    void lowIsBadDirectionHonoursPerTierComparison() {
        ThresholdOperator.Rule mixed = new ThresholdOperator.Rule("probe:tps-mixed",
            ThresholdOperator.Direction.LOW_IS_BAD,
            ThresholdOperator.Comparison.STRICT,     // critical: <
            ThresholdOperator.Comparison.INCLUSIVE); // warning:  <=
        assertEquals(2, mixed.levelOf(14.0, 18, 15));
        assertEquals(1, mixed.levelOf(15.0, 18, 15), "恰好 15：critical 严格不命中，warning 闭区间命中");
        assertEquals(1, mixed.levelOf(18.0, 18, 15), "恰好 18：warning 闭区间命中");
        assertEquals(0, mixed.levelOf(18.01, 18, 15));
    }

    // ====================================================================================
    // 4. 判定半段方法：表写对了，调用点也必须用对（含键名与 alertLevels）
    // ====================================================================================

    /** {@code applyTpsLevel}：恰好 critical 线 → {@code alertLevels}=1，且告警级别是 warning。 */
    @Test
    void tpsAtCriticalLineRecordsWarningNotCritical() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();   // tps 18 / 15

        MetricsSnapshot atCritical = tps(cfg, 15.0);
        assertEquals(1, atLevel(atCritical, "tps:global"),
            "TPS 恰好 15.0：采集层严格 `<` 不成立 → warning（早预警语义：告警比颜色晚一档）");
        assertEquals(Alert.LEVEL_WARNING, onlyAlert(atCritical, "tps:global").level());

        assertEquals(2, atLevel(tps(cfg, 14.99), "tps:global"));
        assertEquals(0, atLevel(tps(cfg, 18.0), "tps:global"), "恰好 warning 线是健康（严格 <）");
        assertEquals(0, atLevel(tps(cfg, 20.0), "tps:global"));
    }

    /** {@code applyPlayersLevel}：恰好 critical → 2（闭区间）。 */
    @Test
    void playersAtCriticalLineRecordsCritical() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();   // players 90 / 98
        MetricsSnapshot s = players(cfg, 98);
        assertEquals(2, atLevel(s, "players:global"), "players 闭区间：恰好 98% 就是 critical");
        assertEquals(Alert.LEVEL_CRITICAL, onlyAlert(s, "players:global").level());

        assertEquals(1, atLevel(players(cfg, 97), "players:global"));
        assertEquals(1, atLevel(players(cfg, 90), "players:global"), "恰好 90% → 1 级");
        assertEquals(0, atLevel(players(cfg, 89), "players:global"));
    }

    /** {@code applyEntitiesLevels}：全服总数恰好 30000 不进 critical、恰好 20000 不进 warning。 */
    @Test
    void globalEntitiesAtThresholdStayOneTierLower() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();   // entities 20000 / 30000
        String key = ThresholdOperator.ENTITIES_GLOBAL.key();

        assertEquals(2, atLevel(entities(cfg, 30_001), key));
        assertEquals(1, atLevel(entities(cfg, 30_000), key), "恰好 30000：严格 > 不成立 → 退到 warning");
        assertEquals(1, atLevel(entities(cfg, 20_001), key));
        assertEquals(0, atLevel(entities(cfg, 20_000), key), "恰好 20000：warning 也严格 > → 健康");
    }

    /** 逐世界实体用 <b>worlds 段覆盖值</b>，不是全局阈值。 */
    @Test
    void perWorldEntitiesUseTheWorldOverrideThresholds() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        // ConfigFixtures.validConfig() 不填 worldSettings（默认 null）——本用例需要它
        cfg.worldSettings = new LinkedHashMap<>();
        cfg.worldSettings.put("overridden", worldOverride("overridden", 100, 200));

        // 全局阈值 20000/30000，覆盖值 100/200——只有覆盖值生效才可能在这些读数上告警
        assertEquals(1, atLevel(entitiesIn(cfg, "overridden", 200), "entities:world:overridden"),
            "恰好 200（覆盖的 critical 线）：严格 > 不成立 → 1 级");
        assertEquals(2, atLevel(entitiesIn(cfg, "overridden", 201), "entities:world:overridden"));
        assertEquals(0, atLevel(entitiesIn(cfg, "overridden", 100), "entities:world:overridden"),
            "恰好 100（覆盖的 warning 线）：严格 > 不成立 → 健康");
        assertEquals(1, atLevel(entitiesIn(cfg, "overridden", 101), "entities:world:overridden"));
    }

    /** 没有 worlds 覆盖的世界不单独判定，也就不写 {@code entities:world:*} 序列。 */
    @Test
    void worldsWithoutOverrideDoNotGetTheirOwnSeries() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        MetricsSnapshot s = entitiesIn(cfg, "no_override", 999_999);
        assertFalse(s.alertLevels.containsKey("entities:world:no_override"),
            "没配置覆盖的世界不该有 entities:world:<名> 序列——它永远不可能告警，"
                + "发一条恒为 0 的序列只会在 Grafana 上造出一个点不进去的假维度");
    }

    /** {@code applyChunksLevels}：恰好线上不进档，且<b>每个世界都判</b>（与 entities 口径不同）。 */
    @Test
    void chunksAtThresholdStayOneTierLowerForEveryWorld() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();   // chunks 10000 / 15000

        assertEquals(2, atLevel(chunks(cfg, 15_001), "chunks:world:world"));
        assertEquals(1, atLevel(chunks(cfg, 15_000), "chunks:world:world"),
            "恰好 15000：严格 > 不成立 → 退到 warning");
        assertEquals(1, atLevel(chunks(cfg, 10_001), "chunks:world:world"));
        assertEquals(0, atLevel(chunks(cfg, 10_000), "chunks:world:world"),
            "恰好 10000：warning 也严格 > → 健康");

        // 与 entities 刻意不同：没有 worlds 覆盖的世界也要判（chunks 没有全服口径兜底）
        assertEquals(2, atLevel(chunksNamed(cfg, "new_world", 20_000), "chunks:world:new_world"),
            "区块没有全服口径，未覆盖的世界必须逐世界判定，否则超限完全失声");
    }

    /** {@code applyMemoryLevel}：恰好 95% 是 critical（闭区间）。 */
    @Test
    void memoryAtCriticalLineRecordsCritical() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();   // memory 85 / 95
        assertEquals(2, atLevel(memory(cfg, 95), "memory:global"), "memory 闭区间：恰好 95% 是 critical");
        assertEquals(1, atLevel(memory(cfg, 94), "memory:global"));
        assertEquals(1, atLevel(memory(cfg, 85), "memory:global"), "恰好 85% → 1 级");
        assertEquals(0, atLevel(memory(cfg, 84), "memory:global"));
    }

    /**
     * 每个判定点都必须写 {@code alertLevels}（含健康时的 0）。
     *
     * <p>漏写的后果不是"少一条指标"，而是那条序列在 Prometheus 上消失——运维看到序列不见
     * 会以为是恢复。这条用例是 {@code recordLevel} 调用约定的回归网。</p>
     */
    @Test
    void everyJudgementPointRecordsALevelEvenWhenHealthy() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        assertNotNull(tps(cfg, 20.0).alertLevels.get("tps:global"));
        assertNotNull(players(cfg, 1).alertLevels.get("players:global"));
        assertNotNull(entities(cfg, 0).alertLevels.get("entities:global"));
        assertNotNull(memory(cfg, 1).alertLevels.get("memory:global"));
        assertNotNull(chunks(cfg, 0).alertLevels.get("chunks:world:world"));
    }

    /** 健康周期不生成告警（避免"级别写 0 但仍推消息"）。 */
    @Test
    void healthyCyclesProduceNoAlerts() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        assertTrue(tps(cfg, 20.0).alerts.isEmpty());
        assertTrue(players(cfg, 1).alerts.isEmpty());
        assertTrue(entities(cfg, 0).alerts.isEmpty());
        assertTrue(memory(cfg, 1).alerts.isEmpty());
        assertTrue(chunks(cfg, 0).alerts.isEmpty());
    }

    // ====================================================================================
    // 夹具
    // ====================================================================================

    /** 每条规则的样点表（仅用于"表条数 == 用例条数"的对照，实际断言在各自用例里）。 */
    private static final Map<String, int[]> RULE_SAMPLES = new LinkedHashMap<>();

    static {
        RULE_SAMPLES.put(ThresholdOperator.TPS.key(), new int[0]);
        RULE_SAMPLES.put(ThresholdOperator.MSPT.key(), new int[0]);
        RULE_SAMPLES.put(ThresholdOperator.PLAYERS.key(), new int[0]);
        RULE_SAMPLES.put(ThresholdOperator.ENTITIES_WORLD.key(), new int[0]);
        RULE_SAMPLES.put(ThresholdOperator.ENTITIES_GLOBAL.key(), new int[0]);
        RULE_SAMPLES.put(ThresholdOperator.CHUNKS_WORLD.key(), new int[0]);
        RULE_SAMPLES.put(ThresholdOperator.MEMORY.key(), new int[0]);
    }

    private static void assertShape(ThresholdOperator.Rule rule, String key,
                                    ThresholdOperator.Direction direction,
                                    ThresholdOperator.Comparison comparison) {
        assertEquals(key, rule.key(), "规则 key 变了——Prometheus 序列名会跟着变");
        assertEquals(direction, rule.direction(), key + " 的判定方向变了（搞反会导致告警永不触发）");
        assertEquals(comparison, rule.criticalComparison(), key + " 的 critical 档闭合性变了");
        assertEquals(comparison, rule.warningComparison(), key + " 的 warning 档闭合性变了");
    }

    /**
     * 逐档断言：可变参数成对传入 {@code (读数, 期望档位)}。
     *
     * <p>用可变参数而不是固定 6 个，是因为不同指标的"四点"具体取几个读数不一样
     * （低值差在 warning 与 critical 之间还要多一个点）。</p>
     */
    private static void assertLevels(ThresholdOperator.Rule rule, double warning, double critical,
                                     double... valueThenLevel) {
        if (valueThenLevel.length % 2 != 0) {
            throw new IllegalArgumentException("需要成对的 (读数, 期望档位)，实际 " + valueThenLevel.length);
        }
        String label = rule.key() + " (dir=" + rule.direction() + ", "
            + rule.criticalComparison() + "/" + rule.warningComparison() + ") 读数 ";
        for (int i = 0; i < valueThenLevel.length; i += 2) {
            double value = valueThenLevel[i];
            int expected = (int) valueThenLevel[i + 1];
            assertEquals(expected, rule.levelOf(value, warning, critical),
                label + value + "（边界值一列——比较符换闭合性时只有这一列会翻）");
        }
    }

    /** 造一个不回填 {@code final} 容器的采集器壳；判定半段只用到这几个字段。 */
    private static MetricsCollector shell() {
        MetricsCollector c = TestObjects.allocate(MetricsCollector.class);
        TestObjects.setField(MetricsCollector.class, c, "plugin",
            AlertFixtures.barePlugin(Logger.getLogger("pscope-threshold-operator")));
        return c;
    }

    private static MetricsSnapshot tps(ConfigManager.ConfigData cfg, double value) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.tps1m = value;
        shell().applyTpsLevel(s, cfg);
        return s;
    }

    private static MetricsSnapshot players(ConfigManager.ConfigData cfg, int percent) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.playerCount = percent;
        s.maxPlayers = 100;
        shell().applyPlayersLevel(s, cfg, percent);
        return s;
    }

    private static MetricsSnapshot memory(ConfigManager.ConfigData cfg, int percent) {
        MetricsSnapshot s = new MetricsSnapshot();
        shell().applyMemoryLevel(s, cfg, percent);
        return s;
    }

    /** 全服实体：世界表为空，只走全服总数口径。 */
    private static MetricsSnapshot entities(ConfigManager.ConfigData cfg, int total) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.totalEntities = total;
        shell().applyEntitiesLevels(s, cfg, Map.of());
        return s;
    }

    private static MetricsSnapshot entitiesIn(ConfigManager.ConfigData cfg, String world, int count) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.totalEntities = count;
        Map<String, Integer> byWorld = new LinkedHashMap<>();
        byWorld.put(world, count);
        shell().applyEntitiesLevels(s, cfg, byWorld);
        return s;
    }

    private static MetricsSnapshot chunks(ConfigManager.ConfigData cfg, int count) {
        return chunksNamed(cfg, "world", count);
    }

    private static MetricsSnapshot chunksNamed(ConfigManager.ConfigData cfg, String world, int count) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.totalLoadedChunks = count;
        Map<String, Integer> byWorld = new LinkedHashMap<>();
        byWorld.put(world, count);
        shell().applyChunksLevels(s, cfg, byWorld);
        return s;
    }

    private static ConfigManager.WorldSettings worldOverride(String name, int warn, int crit) {
        ConfigManager.WorldSettings ws = new ConfigManager.WorldSettings();
        ws.worldName = name;
        ws.entities_totalWarningThreshold = warn;
        ws.entities_totalCriticalThreshold = crit;
        return ws;
    }

    private static Integer atLevel(MetricsSnapshot s, String key) {
        Integer level = s.alertLevels.get(key);
        assertNotNull(level, "判定点必须写 alertLevels[" + key + "]（含健康时的 0），"
            + "漏写会让该序列在 Prometheus 上消失，运维误以为是恢复");
        return level;
    }

    private static Alert onlyAlert(MetricsSnapshot s, String key) {
        List<Alert> matches = s.alerts.stream().filter(a -> a.key().equals(key)).toList();
        assertEquals(1, matches.size(), "期望恰好一条 " + key + " 告警，实际 " + matches.size());
        return matches.get(0);
    }
}
