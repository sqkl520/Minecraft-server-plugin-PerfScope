/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import cn.perfscope.config.ConfigManager;
import cn.perfscope.support.ConfigFixtures;
import cn.perfscope.support.TestObjects;
import cn.perfscope.util.ThresholdColors;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 展示层与采集层在阈值边界上必须判同一档——<b>差异为零</b>。
 *
 * <h2>这个类曾经证明的是相反的事</h2>
 *
 * <p>它的原名与全部断言都在<b>钉住差异</b>：展示层闭区间、采集层严格比较，
 * 读数恰好等于阈值时「颜色先变、告警随后到达」，被称为「有意的早预警」。
 * 那份记录本身是诚实的，但它把缺陷写成了规格——实测下来两边差的不是一格：
 * {@code tps1m == 15.0} 时展示层判 {@code CRITICAL}（红），采集层 {@code 15.0 < 15.0} 不成立、
 * 落进 warning 分支只判 {@code WARNING}（黄），<b>整整差一档</b>。
 * {@code ThresholdColors} 自己的类注释当时也承认「是唯一差两档的」，而 README 承诺的只是「随后到达」。</p>
 *
 * <p>颜色与告警读同一份 config、显示同一个数字，却各说一套，用户没有第三种信息可以判断谁对。
 * 所以现在改成：<b>展示层从 {@link ThresholdOperator} 派生</b>，两层逐点相同。</p>
 *
 * <h2>本类的三条职责</h2>
 * <ol>
 *   <li><b>差值为零</b>：对每条规则，用一串覆盖边界的读数（阈值±1、阈值±0.01、远离值），
 *       两层的档位必须完全相同。这是根断言，改任何一层的比较符都会让它红。</li>
 *   <li><b>边界行为本身</b>：恰好等于阈值时是越界还是否，按各族的闭合性分别写死——
 *       否则「两层一致地都错了」也能通过第 1 条。</li>
 *   <li><b>allow-list 是空的，且必须显式为空</b>：本轮确实存在"可接受的差异"的候选
 *       （CPU 的迟滞闩锁），但它不是差异，见 {@link #cpuColourUsesTheSameEntryLineAsTheLatch()}。</li>
 * </ol>
 *
 * <h2>为什么数值不再写死成 30000 / 50 这类字面量</h2>
 *
 * <p>上一版在断言里直接写 {@code 30000}、{@code 50}，那是"通过 ConfigFixtures 的默认阈值"
 * 这个隐含前提。现在两层都用同一组阈值算出档位，字面量只保留在"边界点怎么取"（阈值本身 ± 一点），
 * 于是改默认值不会让测试变成在测另一个指标。</p>
 */
class ThresholdBoundaryAsymmetryTest {

    /** 阈值 18 / 15（与出厂 config.yml 一致）。 */
    private static ThresholdColors colors() {
        ConfigManager cm = ConfigFixtures.manager("pscope-boundary-test");
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        return new ThresholdColors(cm);
    }

    /** 出厂 config.yml 的那一套阈值（{@link ConfigFixtures#validConfig()} 是唯一来源）。 */
    private static ConfigManager.ConfigData configOf() {
        return ConfigFixtures.validConfig();
    }

    // ------------------------------------------------------------------------------------
    // 1. 根断言：两层逐点相同
    // ------------------------------------------------------------------------------------

    /**
     * <b>差异集合必须为空。</b>
     *
     * <p>每条规则取五个读数：{@code 阈值-ε}、{@code 阈值}、{@code 阈值+ε}（两个阈值各一组）、
     * 以及两个远离值。{@code ε} 用 0.01 / 1（计数型指标用 1，浮点型用 0.01），
     * 保证"恰好等于"那一列真的落在边界上。</p>
     *
     * <p>失败信息给出的是<b>整个不一致清单</b>而不是第一个：一层里挪动了半格会导致
     * 多条同时红，只报第一条会让人以为是单点笔误。</p>
     */
    @Test
    void displayAndCollectorNeverDisagreeAtTheBoundary() {
        ThresholdColors colors = colors();
        ConfigManager.ConfigData cfg = configOf();
        List<String> mismatches = new ArrayList<>();

        // TPS：默认 18 / 15
        compare(mismatches, "tps", ThresholdOperator.TPS,
            colors, ThresholdColors.Metric.TPS, cfg.tps_warningThreshold, cfg.tps_criticalThreshold, 0.01);
        // MSPT：默认 45 / 50
        compare(mismatches, "mspt", ThresholdOperator.MSPT,
            colors, ThresholdColors.Metric.MSPT, cfg.mspt_warningThreshold, cfg.mspt_criticalThreshold, 0.01);
        // 玩家占比：默认 90 / 98
        compare(mismatches, "players", ThresholdOperator.PLAYERS,
            colors, ThresholdColors.Metric.PLAYERS, cfg.players_warningThreshold, cfg.players_criticalThreshold, 1);
        // 全服实体：默认 20000 / 30000
        compare(mismatches, "entities", ThresholdOperator.ENTITIES_GLOBAL,
            colors, ThresholdColors.Metric.ENTITIES,
            cfg.entities_totalWarningThreshold, cfg.entities_totalCriticalThreshold, 1);
        // 逐世界区块：默认 10000 / 15000
        compare(mismatches, "chunks", ThresholdOperator.CHUNKS_WORLD,
            colors, ThresholdColors.Metric.CHUNKS,
            cfg.chunks_loadedWarningThreshold, cfg.chunks_loadedCriticalThreshold, 1);
        // 堆内存：默认 85 / 95
        compare(mismatches, "memory", ThresholdOperator.MEMORY,
            colors, ThresholdColors.Metric.MEMORY,
            cfg.memory_heapWarningThreshold, cfg.memory_heapCriticalThreshold, 1);

        assertTrue(mismatches.isEmpty(),
            "展示层与采集层在阈值边界上判了不同的档——两层必须同口径（展示层现在从 ThresholdOperator 派生，"
                + "若你改成自己写比较符，就是这条在报警）：\n  " + String.join("\n  ", mismatches));
    }

    /** 把一条规则在边界附近的档位与展示层逐个比对，差异收集进 {@code out}。 */
    private static void compare(List<String> out, String label, ThresholdOperator.Rule rule,
                                ThresholdColors colors, ThresholdColors.Metric metric,
                                double warning, double critical, double epsilon) {
        boolean lowIsBad = rule.direction() == ThresholdOperator.Direction.LOW_IS_BAD;
        double[] samples = {
            critical - 2 * epsilon, critical - epsilon, critical, critical + epsilon, critical + 2 * epsilon,
            warning - epsilon, warning, warning + epsilon,
            // 远离值两个：高值差往上一倍取（不能用 0——MSPT 的 0 是"未采集"哨兵，
            // 展示层会判 UNKNOWN 而不是 GOOD/CRITICAL，那不是算子差异而是可信度差异）
            lowIsBad ? 0 : warning * 2,
            lowIsBad ? warning * 2 : critical * 2,
        };
        for (double value : samples) {
            int collectorTier = rule.levelOf(value, warning, critical);
            int displayTier = tierOf(colors.levelOf(metric, value));
            if (collectorTier != displayTier) {
                out.add(label + " value=" + value + "（阈值 " + warning + "/" + critical + "）："
                    + "采集层=" + tierName(collectorTier) + "，展示层=" + tierName(displayTier));
            }
        }
    }

    /** 展示层等级 → 与 {@link ThresholdOperator.Rule#levelOf} 相同的 0/1/2 语言。 */
    private static int tierOf(ThresholdColors.Level level) {
        return switch (level) {
            case GOOD -> 0;
            case WARN -> 1;
            case CRITICAL -> 2;
            // UNKNOWN 只出现在哨兵输入上（本类的样本都是正常读数），落 -1 让任何意外立刻显形
            default -> -1;
        };
    }

    private static String tierName(int tier) {
        return switch (tier) {
            case 0 -> "GOOD";
            case 1 -> "WARN";
            case 2 -> "CRITICAL";
            default -> "UNKNOWN";
        };
    }

    // ------------------------------------------------------------------------------------
    // 2. 边界行为本身（防止「两层一致地都错」）
    // ------------------------------------------------------------------------------------

    /**
     * 严格族（tps / mspt / entities / chunks）：恰好等于阈值<b>不</b>越界，差一个单位就变。
     *
     * <p>这条与上面那条互补：上面只要求两层口径相同，哪怕两边一起把 {@code >} 写成 {@code >=}
     * 也能通过。这条把"边界在哪一侧"写死，两条合起来才算钉住。</p>
     */
    @Test
    void strictFamiliesDoNotTripAtExactlyTheThreshold() {
        ThresholdColors c = colors();

        // TPS（低值差）：18.0 仍是 GOOD，17.99 才黄；15.0 是 WARN，14.99 才红
        assertEquals(ThresholdColors.Level.GOOD, c.levelOf(ThresholdColors.Metric.TPS, 18.0),
            "TPS 恰好等于 warning 线：两层都是严格比较，不该变色");
        assertEquals(ThresholdColors.Level.WARN, c.levelOf(ThresholdColors.Metric.TPS, 17.99));
        assertEquals(ThresholdColors.Level.WARN, c.levelOf(ThresholdColors.Metric.TPS, 15.0),
            "TPS 恰好等于 critical 线：采集层判 WARNING，颜色必须也是 WARN 而不是 RED");
        assertEquals(ThresholdColors.Level.CRITICAL, c.levelOf(ThresholdColors.Metric.TPS, 14.99));

        // MSPT（高值差）：45.0 仍是 GOOD，50.0 是 WARN，50.01 才红
        assertEquals(ThresholdColors.Level.GOOD, c.levelOf(ThresholdColors.Metric.MSPT, 45.0));
        assertEquals(ThresholdColors.Level.WARN, c.levelOf(ThresholdColors.Metric.MSPT, 50.0));
        assertEquals(ThresholdColors.Level.CRITICAL, c.levelOf(ThresholdColors.Metric.MSPT, 50.01));

        // 实体：20000 仍是 GOOD，30000 是 WARN，30001 才红
        assertEquals(ThresholdColors.Level.GOOD, c.levelOf(ThresholdColors.Metric.ENTITIES, 20_000));
        assertEquals(ThresholdColors.Level.WARN, c.levelOf(ThresholdColors.Metric.ENTITIES, 30_000));
        assertEquals(ThresholdColors.Level.CRITICAL, c.levelOf(ThresholdColors.Metric.ENTITIES, 30_001));

        // 区块：同上
        assertEquals(ThresholdColors.Level.GOOD, c.levelOf(ThresholdColors.Metric.CHUNKS, 10_000));
        assertEquals(ThresholdColors.Level.WARN, c.levelOf(ThresholdColors.Metric.CHUNKS, 15_000));
        assertEquals(ThresholdColors.Level.CRITICAL, c.levelOf(ThresholdColors.Metric.CHUNKS, 15_001));
    }

    /**
     * 闭区间族（players / memory）：恰好等于阈值<b>就</b>越界。
     *
     * <p>这两族是"达到即算"，因为它们的默认值不是"上界留余量"取的整数，而是语义本身：
     * 85% 就是 config.yml 声明的健康区间上界；18/20 人 = 恰好 90% 是最常见的满员配置。
     * 采集层也这么判，所以两层天然一致——这条把那份一致写死。</p>
     */
    @Test
    void inclusiveFamiliesTripAtExactlyTheThreshold() {
        ThresholdColors c = colors();

        assertEquals(ThresholdColors.Level.WARN, c.levelOf(ThresholdColors.Metric.MEMORY, 85),
            "memory 恰好 85%：闭区间，两层都进 WARN");
        assertEquals(ThresholdColors.Level.CRITICAL, c.levelOf(ThresholdColors.Metric.MEMORY, 95));
        assertEquals(ThresholdColors.Level.GOOD, c.levelOf(ThresholdColors.Metric.MEMORY, 84));

        assertEquals(ThresholdColors.Level.WARN, c.levelOf(ThresholdColors.Metric.PLAYERS, 90));
        assertEquals(ThresholdColors.Level.CRITICAL, c.levelOf(ThresholdColors.Metric.PLAYERS, 98));
        assertEquals(ThresholdColors.Level.GOOD, c.levelOf(ThresholdColors.Metric.PLAYERS, 89));
    }

    /**
     * 世界覆盖换的是<b>阈值</b>，不换<b>算子</b>：{@code entities:world:*} 与
     * {@code entities:global} 当前同闭合性，覆盖值下的边界行为必须与全局一致。
     *
     * <p>这条同时钉住 {@link ThresholdColors} 里"覆盖存在时改用 ENTITIES_WORLD 规则"那一行——
     * 若哪天有人让两条规则的算子分叉，颜色与告警就会在某个世界的边界值上各自为政，
     * 而这里会先红。</p>
     */
    @Test
    void worldOverrideUsesTheSameOperatorAsGlobal() {
        ThresholdColors plain = colors();

        // 覆盖值 500 / 800（全局是 20000 / 30000）：只有覆盖真的生效才可能在这些读数上变色
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.worldSettings = new java.util.LinkedHashMap<>();
        ConfigManager.WorldSettings ws = new ConfigManager.WorldSettings();
        ws.worldName = "nether";
        ws.entities_totalWarningThreshold = 500;
        ws.entities_totalCriticalThreshold = 800;
        cfg.worldSettings.put("nether", ws);
        ConfigManager cm = ConfigFixtures.manager("pscope-boundary-world");
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        ThresholdColors overridden = new ThresholdColors(cm);

        assertEquals(ThresholdColors.Level.GOOD, overridden.levelOf(ThresholdColors.Metric.ENTITIES, 500, "nether"),
            "覆盖后的 warning 线仍是严格比较：恰好 500 不变黄");
        assertEquals(ThresholdColors.Level.WARN, overridden.levelOf(ThresholdColors.Metric.ENTITIES, 501, "nether"));
        assertEquals(ThresholdColors.Level.WARN, overridden.levelOf(ThresholdColors.Metric.ENTITIES, 800, "nether"));
        assertEquals(ThresholdColors.Level.CRITICAL, overridden.levelOf(ThresholdColors.Metric.ENTITIES, 801, "nether"));

        assertEquals(ThresholdOperator.ENTITIES_WORLD.criticalComparison(),
            ThresholdOperator.ENTITIES_GLOBAL.criticalComparison(),
            "逐世界与全服实体的闭合性一旦分叉，覆盖世界的边界值上颜色与告警就会不同；"
                + "要分叉请先给 ThresholdColors 与阈值文案各自加用例");
        // 未覆盖的世界仍走全局那条（同一个读数在 nether 上 GOOD、在全局口径下 GOOD，但阈值不同）
        assertEquals(plain.levelOf(ThresholdColors.Metric.ENTITIES, 20_000),
            overridden.levelOf(ThresholdColors.Metric.ENTITIES, 20_000, "end"));
    }

    // ------------------------------------------------------------------------------------
    // 3. 唯一看似该进 allow-list 的候选：CPU
    // ------------------------------------------------------------------------------------

    /**
     * CPU：采集层走迟滞闩锁、展示层走瞬时值，但<b>进入线是同一个比较符</b>，
     * 所以它<b>不是</b>需要 allow-list 的差异。
     *
     * <p>这条替原本可能写下的 allow-list 说明原因。两层量的是不同问题
     * （闩锁答"状态要不要延续"、颜色答"此刻读数是多少"），差异只在<b>是否存在记忆</b>，
     * 不在边界位置：{@code 80.00%} 两边都不越界，{@code 80.01%} 两边都越界。
     * 一条"看起来不一致"的注释会让人以为这里也有 shim，而实际没有。</p>
     */
    @Test
    void cpuColourUsesTheSameEntryLineAsTheLatch() {
        ThresholdColors c = colors();
        double threshold = configOf().cpu_processWarningThreshold;   // 80.0

        assertEquals(ThresholdColors.Level.GOOD, c.levelOf(ThresholdColors.Metric.CPU_PROCESS, threshold),
            "CPU 恰好等于阈值：闩锁的进入线是严格 `>`，颜色也不能变黄");
        assertEquals(ThresholdColors.Level.WARN, c.levelOf(ThresholdColors.Metric.CPU_PROCESS, threshold + 0.01));

        assertEquals(ThresholdColors.Level.GOOD, c.levelOf(ThresholdColors.Metric.CPU_SYSTEM,
                configOf().cpu_systemWarningThreshold));
        // 最高只到 WARN：config 根本没有 critical 键，凭空造一档红色才是错
        assertEquals(ThresholdColors.Level.WARN, c.levelOf(ThresholdColors.Metric.CPU_PROCESS, 100.0));

        // 与采集层同一个出处：这条规则若被改坏（比如 STRICT→INCLUSIVE），
        // latched 与颜色会同时挪，而 ThresholdTextMatchesOperatorTest 会先发现文案说了别的话
        assertEquals(ThresholdOperator.Comparison.STRICT, ThresholdOperator.CPU_PROCESS.comparison(),
            "CPU 进入线的闭合性属于算子表；改它必须同时改文案模板与这条断言");
        assertEquals(">", ThresholdOperator.CPU_PROCESS.symbol());
    }
}
