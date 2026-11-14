/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package cn.perfscope.util;

import cn.perfscope.config.ConfigManager;
import cn.perfscope.support.TestObjects;
import cn.perfscope.util.ThresholdColors.Level;
import cn.perfscope.util.ThresholdColors.Metric;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ThresholdColors} 的行为测试：游戏内所有数值的着色都由它决定。
 *
 * <p>这个类此前<b>零测试</b>。它把 v1.1 散落在命令层 16 处 + 报告层 5 处的硬编码阈值
 * 收成一处并改读 {@code config.yml}，也就是说：一次错误的比较符（{@code <} 写成 {@code <=}）
 * 会让全服运维看到与实际不符的红/绿，而且<b>不抛异常、不记日志</b>。</p>
 *
 * <h2>实现事实（下面每条都有用例钉住）</h2>
 * <ul>
 *   <li><b>阈值是闭区间</b>：恰好等于阈值即变色。低值差指标（TPS）用 {@code <=}，
 *       其余全部用 {@code >=}。这与采集层告警的严格比较（MSPT/实体/区块用 {@code >}）
 *       刻意不一致——"看到颜色变化时告警还在路上"，属有意的早预警。</li>
 *   <li><b>哨兵 {@code -1} 只有 MSPT / CPU_PROCESS / CPU_SYSTEM 识别</b>（{@code <= 0 → UNKNOWN}）。
 *       TPS / MEMORY / PLAYERS / ENTITIES / CHUNKS 的 {@code -1} 会落到正常比较里，
 *       TPS 因此判成 <b>CRITICAL</b>。见 {@link #sentinelMinusOneIsHonouredOnlyForMsptAndCpu}
 *       与类尾的"已知不一致"说明。</li>
 *   <li>{@code NaN} 对所有指标都是 UNKNOWN，且该判断<b>早于</b>配置判空——
 *       配置还没加载时 NaN 也返回 UNKNOWN 而不是抛异常。</li>
 *   <li>{@code configManager.get() == null}（load 尚未成功）时一律 UNKNOWN，不抛异常。</li>
 *   <li>ENTITIES 支持按世界覆盖：世界在 {@code worlds} 段有条目就用世界的阈值，否则回退全局；
 *       世界名为 null / 不存在 / 空串都走全局。</li>
 * </ul>
 *
 * <h2>为什么阈值全部显式写进夹具</h2>
 * <p>{@link ConfigManager.ConfigData} 的字段默认值是 {@code 0}（不是 config.yml 的默认值，
 * 那些默认值只在 {@code load()} 里经 {@code readInt/readDouble} 落入）。若直接拿一个裸
 * {@code ConfigData} 当配置，{"值 >= 0"} 对所有高值差指标恒真，每个用例都会得到 CRITICAL——
 * 测试全"绿"却什么都没验证。夹具因此逐项写死，与 {@code config.yml} 出厂值一致。</p>
 */
class ThresholdColorsTest {

    // ==================== 夹具 ====================

    /** 与出厂 config.yml 一致的阈值。每个用例都用它，断言才不会被"默认 0"污染。 */
    private static ConfigManager.ConfigData defaults() {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.tps_warningThreshold = 18.0;
        cfg.tps_criticalThreshold = 15.0;
        cfg.mspt_warningThreshold = 45.0;
        cfg.mspt_criticalThreshold = 50.0;
        cfg.memory_heapWarningThreshold = 85;
        cfg.memory_heapCriticalThreshold = 95;
        cfg.cpu_processWarningThreshold = 80.0;
        cfg.cpu_systemWarningThreshold = 90.0;
        cfg.players_warningThreshold = 90;
        cfg.players_criticalThreshold = 98;
        cfg.entities_totalWarningThreshold = 20_000;
        cfg.entities_totalCriticalThreshold = 30_000;
        cfg.chunks_loadedWarningThreshold = 10_000;
        cfg.chunks_loadedCriticalThreshold = 15_000;
        cfg.worldSettings = new LinkedHashMap<>();
        return cfg;
    }

    /**
     * 造一个"已发布配置"的 {@link ConfigManager}。
     *
     * <p>{@code new ConfigManager(null)} 可用：构造器只赋 {@code plugin} 字段（见
     * {@link TestObjects} 的类注释"约束二"把 {@code ConfigManager} 明确列为不需要
     * {@code allocateInstance} 的类型）。配置快照经反射写入 {@code config} 字段，
     * 与 {@code load()} 末尾的发布方式等价（都是替换同一字段）。</p>
     */
    private static ConfigManager manager(ConfigManager.ConfigData cfg) {
        ConfigManager cm = new ConfigManager(null);
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        return cm;
    }

    /** 出厂阈值下的实例。 */
    private static ThresholdColors colors() {
        return new ThresholdColors(manager(defaults()));
    }

    /** 在出厂阈值之外再加一个世界覆盖（Warning 500 / Critical 800）。 */
    private static ThresholdColors colorsWithWorld(String world, int warning, int critical) {
        ConfigManager.ConfigData cfg = defaults();
        ConfigManager.WorldSettings ws = new ConfigManager.WorldSettings();
        ws.worldName = world;
        ws.entities_totalWarningThreshold = warning;
        ws.entities_totalCriticalThreshold = critical;
        cfg.worldSettings.put(world, ws);
        return new ThresholdColors(manager(cfg));
    }

    // ==================== TPS：低值差，闭区间，最高到 CRITICAL ====================

    @Test
    @DisplayName("TPS 闭区间：20 GOOD / 18 恰好 WARN / 15 恰好 CRITICAL / 0 是真实冻结值")
    void tpsBoundariesAreClosedIntervals() {
        ThresholdColors c = colors();
        // 满 TPS 与略低于满值：GOOD
        assertEquals(Level.GOOD, c.levelOf(Metric.TPS, 20.0));
        assertEquals(Level.GOOD, c.levelOf(Metric.TPS, 18.01), "略高于 warning 仍是 GOOD");
        // 恰好等于 warning → WARN（闭区间；若把 <= 写成 < 这里会是 GOOD）
        assertEquals(Level.WARN, c.levelOf(Metric.TPS, 18.0), "18.0 必须已经变黄");
        assertEquals(Level.WARN, c.levelOf(Metric.TPS, 15.01), "略高于 critical 仍是 WARN");
        // 恰好等于 critical → CRITICAL
        assertEquals(Level.CRITICAL, c.levelOf(Metric.TPS, 15.0), "15.0 必须已经变红");
        assertEquals(Level.CRITICAL, c.levelOf(Metric.TPS, 10.0));
        // TPS=0 是"服务器完全冻结"的真实读数，必须红而不是 UNKNOWN（javadoc 明示）
        assertEquals(Level.CRITICAL, c.levelOf(Metric.TPS, 0.0), "TPS 0 是真实告警值，不是未采集");
    }

    @Test
    @DisplayName("TPS 支持高于 20 的读数（启动瞬间会 >20），仍为 GOOD")
    void tpsAboveTwentyIsGood() {
        assertEquals(Level.GOOD, colors().levelOf(Metric.TPS, 20.5));
        assertEquals(Level.GOOD, colors().levelOf(Metric.TPS, 100.0));
    }

    // ==================== MSPT：高值差，闭区间 ====================

    @Test
    @DisplayName("MSPT 闭区间：44.99 GOOD / 45 恰好 WARN / 50 恰好 CRITICAL")
    void msptBoundariesAreClosedIntervals() {
        ThresholdColors c = colors();
        assertEquals(Level.GOOD, c.levelOf(Metric.MSPT, 1.0));
        assertEquals(Level.GOOD, c.levelOf(Metric.MSPT, 44.99), "略低于 warning 仍是 GOOD");
        assertEquals(Level.WARN, c.levelOf(Metric.MSPT, 45.0), "45.0 必须已经变黄");
        assertEquals(Level.WARN, c.levelOf(Metric.MSPT, 49.99));
        assertEquals(Level.CRITICAL, c.levelOf(Metric.MSPT, 50.0), "50.0 必须已经变红");
        assertEquals(Level.CRITICAL, c.levelOf(Metric.MSPT, 500.0));
    }

    // ==================== 内存 / CPU / 玩家 / 区块 ====================

    @Test
    @DisplayName("MEMORY 闭区间：84 GOOD / 85 WARN / 95 CRITICAL")
    void memoryBoundaries() {
        ThresholdColors c = colors();
        assertEquals(Level.GOOD, c.levelOf(Metric.MEMORY, 84.0));
        assertEquals(Level.WARN, c.levelOf(Metric.MEMORY, 85.0), "85 必须已经变黄");
        assertEquals(Level.WARN, c.levelOf(Metric.MEMORY, 94.0));
        assertEquals(Level.CRITICAL, c.levelOf(Metric.MEMORY, 95.0), "95 必须已经变红");
        assertEquals(Level.CRITICAL, c.levelOf(Metric.MEMORY, 100.0));
    }

    @Test
    @DisplayName("CPU_PROCESS 只到 WARN（config 无 critical 键），80 恰好变黄、永不返回 CRITICAL")
    void cpuProcessCapsAtWarn() {
        ThresholdColors c = colors();
        assertEquals(Level.GOOD, c.levelOf(Metric.CPU_PROCESS, 79.99));
        assertEquals(Level.WARN, c.levelOf(Metric.CPU_PROCESS, 80.0), "80 必须已经变黄");
        // 关键：即便 100% 也只有 WARN——没有 critical 键就不该凭空造一档红色
        assertEquals(Level.WARN, c.levelOf(Metric.CPU_PROCESS, 100.0));
        assertEquals(Level.WARN, c.levelOf(Metric.CPU_PROCESS, 1_000.0));
    }

    @Test
    @DisplayName("CPU_SYSTEM 只到 WARN，90 恰好变黄")
    void cpuSystemCapsAtWarn() {
        ThresholdColors c = colors();
        assertEquals(Level.GOOD, c.levelOf(Metric.CPU_SYSTEM, 89.99));
        assertEquals(Level.WARN, c.levelOf(Metric.CPU_SYSTEM, 90.0), "90 必须已经变黄");
        assertEquals(Level.WARN, c.levelOf(Metric.CPU_SYSTEM, 100.0));
    }

    @Test
    @DisplayName("PLAYERS 闭区间：89 GOOD / 90 WARN / 98 CRITICAL；0 人在线是 GOOD")
    void playersBoundaries() {
        ThresholdColors c = colors();
        assertEquals(Level.GOOD, c.levelOf(Metric.PLAYERS, 0.0), "0 人在线是健康状态");
        assertEquals(Level.GOOD, c.levelOf(Metric.PLAYERS, 89.0));
        assertEquals(Level.WARN, c.levelOf(Metric.PLAYERS, 90.0), "90 必须已经变黄");
        assertEquals(Level.WARN, c.levelOf(Metric.PLAYERS, 97.0));
        assertEquals(Level.CRITICAL, c.levelOf(Metric.PLAYERS, 98.0), "98 必须已经变红");
        assertEquals(Level.CRITICAL, c.levelOf(Metric.PLAYERS, 100.0));
    }

    @Test
    @DisplayName("CHUNKS 闭区间：9999 GOOD / 10000 WARN / 15000 CRITICAL")
    void chunksBoundaries() {
        ThresholdColors c = colors();
        assertEquals(Level.GOOD, c.levelOf(Metric.CHUNKS, 9_999));
        assertEquals(Level.WARN, c.levelOf(Metric.CHUNKS, 10_000), "10000 必须已经变黄");
        assertEquals(Level.WARN, c.levelOf(Metric.CHUNKS, 14_999));
        assertEquals(Level.CRITICAL, c.levelOf(Metric.CHUNKS, 15_000), "15000 必须已经变红");
        assertEquals(Level.CRITICAL, c.levelOf(Metric.CHUNKS, 100_000));
    }

    @Test
    @DisplayName("ENTITIES（全局阈值）闭区间：19999 GOOD / 20000 WARN / 30000 CRITICAL")
    void entitiesGlobalBoundaries() {
        ThresholdColors c = colors();
        assertEquals(Level.GOOD, c.levelOf(Metric.ENTITIES, 19_999));
        assertEquals(Level.WARN, c.levelOf(Metric.ENTITIES, 20_000), "20000 必须已经变黄");
        assertEquals(Level.WARN, c.levelOf(Metric.ENTITIES, 29_999));
        assertEquals(Level.CRITICAL, c.levelOf(Metric.ENTITIES, 30_000), "30000 必须已经变红");
        assertEquals(Level.CRITICAL, c.levelOf(Metric.ENTITIES, 1_000_000));
    }

    // ==================== 世界覆盖 ====================

    @Test
    @DisplayName("ENTITIES 世界覆盖：配置了 worlds.<name> 就用世界阈值，不传世界名用全局")
    void entitiesWorldOverrideTakesPrecedence() {
        ThresholdColors c = colorsWithWorld("world_nether", 500, 800);

        // 全局 20000/30000 下 600 是 GOOD，但 nether 覆盖 500/800 下 600 应是 WARN
        assertEquals(Level.GOOD, c.levelOf(Metric.ENTITIES, 600));
        assertEquals(Level.WARN, c.levelOf(Metric.ENTITIES, 600, "world_nether"));

        // 世界阈值同样是闭区间
        assertEquals(Level.GOOD, c.levelOf(Metric.ENTITIES, 499, "world_nether"));
        assertEquals(Level.WARN, c.levelOf(Metric.ENTITIES, 500, "world_nether"), "500 必须已经变黄");
        assertEquals(Level.WARN, c.levelOf(Metric.ENTITIES, 799, "world_nether"));
        assertEquals(Level.CRITICAL, c.levelOf(Metric.ENTITIES, 800, "world_nether"), "800 必须已经变红");
    }

    @Test
    @DisplayName("ENTITIES 世界不存在 / 世界名为 null / 空串 → 回退全局阈值")
    void unknownWorldFallsBackToGlobal() {
        ThresholdColors c = colorsWithWorld("world_nether", 500, 800);

        // 不存在的世界名
        assertEquals(Level.GOOD, c.levelOf(Metric.ENTITIES, 600, "world_does_not_exist"),
            "未知世界名必须回退全局，绝不能用别的世界的阈值");
        // 世界名为 null（= 两参重载）
        assertEquals(Level.GOOD, c.levelOf(Metric.ENTITIES, 600, null));
        assertEquals(c.levelOf(Metric.ENTITIES, 600), c.levelOf(Metric.ENTITIES, 600, null),
            "两参重载必须等价于三参传 null");
        // 空串：worlds 段不可能有空名条目，但调用方可能传 ""
        assertEquals(Level.GOOD, c.levelOf(Metric.ENTITIES, 600, ""));
        // 大小写不匹配也不算命中（世界名是精确匹配，不做归一化）
        assertEquals(Level.GOOD, c.levelOf(Metric.ENTITIES, 600, "WORLD_NETHER"));
    }

    @Test
    @DisplayName("非 ENTITIES 指标忽略 worldOrNull 参数")
    void worldArgumentIsIgnoredForOtherMetrics() {
        ThresholdColors c = colorsWithWorld("world_nether", 500, 800);
        // 同一个世界名对 CHUNKS 无效：仍是全局 10000/15000
        assertEquals(Level.GOOD, c.levelOf(Metric.CHUNKS, 9_999, "world_nether"));
        assertEquals(Level.WARN, c.levelOf(Metric.CHUNKS, 10_000, "world_nether"));
        assertEquals(c.levelOf(Metric.TPS, 16.0), c.levelOf(Metric.TPS, 16.0, "world_nether"));
        assertEquals(c.levelOf(Metric.MSPT, 46.0), c.levelOf(Metric.MSPT, 46.0, "world_nether"));
    }

    @Test
    @DisplayName("worlds 段为空 / 为 null 时不抛异常，一律回退全局")
    void emptyOrNullWorldSettingsFallBack() {
        // worldSettings 为 null（load() 之前的默认形态之外的极端情形）
        ConfigManager.ConfigData nullWorlds = defaults();
        nullWorlds.worldSettings = null;
        ThresholdColors c1 = new ThresholdColors(manager(nullWorlds));
        assertEquals(Level.GOOD, c1.levelOf(Metric.ENTITIES, 600, "world_nether"));
        assertEquals(Level.CRITICAL, c1.levelOf(Metric.ENTITIES, 30_000, "world_nether"));

        // worldSettings 为空 map
        ThresholdColors c2 = colors();
        assertEquals(Level.GOOD, c2.levelOf(Metric.ENTITIES, 600, "world_nether"));
    }

    @Test
    @DisplayName("世界覆盖阈值为 0/0 时任何非负实体数都判 CRITICAL——本类自己不做校验")
    void zeroWorldThresholdsMakeEverythingCritical() {
        // 这条不是"期望行为"，而是把职责边界钉住：0/0 的清洗在
        // ConfigManager.validateThresholds（sanitizeIntPair 要求 w >= 1）里，不在本类。
        // 若有人删掉那处校验，世界覆盖会变成"永远红"，而这里会立刻告诉后来者原因。
        ThresholdColors c = colorsWithWorld("world_nether", 0, 0);
        assertEquals(Level.CRITICAL, c.levelOf(Metric.ENTITIES, 0, "world_nether"));
        assertEquals(Level.CRITICAL, c.levelOf(Metric.ENTITIES, 1, "world_nether"));
    }

    // ==================== 哨兵值 ====================

    @Test
    @DisplayName("哨兵 -1 只在 MSPT / CPU_PROCESS / CPU_SYSTEM 上判 UNKNOWN")
    void sentinelMinusOneIsHonouredForMsptAndCpu() {
        ThresholdColors c = colors();
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.MSPT, -1), "MSPT -1 = 未采集");
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.CPU_PROCESS, -1), "-1 = JDK 取不到 CPU");
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.CPU_SYSTEM, -1));
        // MSPT 的 0 也是未采集：msptAvg 默认 -1，且每 tick 耗时不可能真的是 0。
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.MSPT, 0.0));
        // 负得更离谱也一样
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.MSPT, -1_000.0));
    }

    /**
     * CPU 的 {@code 0.0} 是<b>合法读数</b>，不是哨兵。
     *
     * <p><b>这条曾经写错过</b>：初版测试跟随旧实现断言 {@code CPU_PROCESS, 0.0 → UNKNOWN}，
     * 而那是个真实缺陷。{@code MetricsSnapshot.processCpuLoad} 的哨兵默认值是 {@code -1}
     * （见其构造器注释「MSPT/CPU 未采集时为 -1」），{@code 0.0} 表示"真的没占用"——
     * 一台完全空载的服务器 CPU 就是 0，那是最健康的状态，不该显示成"未知"。</p>
     *
     * <p>同项目 {@code PerfScopeCommand} 算趋势峰值时用的就是 {@code cpuProcess() >= 0}
     * （只排除负哨兵），两处此前口径相反。</p>
     */
    @Test
    @DisplayName("CPU 的 0.0 是合法读数（空载），不是未采集")
    void cpuZeroIsARealReadingNotASentinel() {
        ThresholdColors c = colors();
        assertEquals(Level.GOOD, c.levelOf(Metric.CPU_PROCESS, 0.0),
            "an idle server's CPU is genuinely 0 - calling that UNKNOWN hides the healthiest state");
        assertEquals(Level.GOOD, c.levelOf(Metric.CPU_SYSTEM, 0.0));
        // 但负哨兵仍然要拦住
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.CPU_PROCESS, -0.1));
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.CPU_SYSTEM, -0.1));
        // 而 MSPT 的 0 确实不可信（每 tick 耗时不可能是 0），两者不能一刀切
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.MSPT, 0.0));
    }

    @Test
    @DisplayName("已知不一致：同一个 -1 在 TPS 上是 CRITICAL、在 MSPT 上是 UNKNOWN")
    void sentinelMinusOneIsHonouredOnlyForMsptAndCpu() {
        ThresholdColors c = colors();

        // 【缺陷记录】TPS 分支刻意没有哨兵判断（TPS=0 是真实冻结值，不能一律当未采集），
        // 于是 -1 这个"未采集"哨兵会穿过 <= 比较直接判成 CRITICAL——红色。
        // 而全项目其它层都把 tps1m < 0 当哨兵：MetricsTextRenderer#tps 的
        // `if (snap.tps1m < 0 && ...) return;`（注释原文"哨兵就是 -1 本身"），
        // 以及 PerfScopeCommand 趋势图的 `tps <= 0 → 灰 "?"`。
        // 也就是说：同一份数据，Prometheus 说"没有这条序列"，这里说"严重告警"。
        assertEquals(Level.CRITICAL, c.levelOf(Metric.TPS, -1),
            "当前实现把 -1 判成 CRITICAL（见类注释的缺陷说明）；修复后本断言应改为 UNKNOWN");
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.MSPT, -1));

        // 同一次调用里两个指标的答案相反——这正是"不一致"的最小可复现形态
        assertNotEquals(c.levelOf(Metric.TPS, -1), c.levelOf(Metric.MSPT, -1));
    }

    @Test
    @DisplayName("MEMORY / ENTITIES / CHUNKS / PLAYERS 的 -1 落到 GOOD（没有哨兵判断）")
    void otherMetricsTreatMinusOneAsGood() {
        ThresholdColors c = colors();
        // 这些指标的读数域天然非负，-1 无法由采集层产生（heapUsagePercent 计算值恒 >= 0，
        // 计数恒 >= 0）。所以这里没有哨兵判断是"当前无实害"，但它意味着：一旦将来某条
        // 采集路径开始用 -1 表示"未采集"，这里会静默地把异常染成绿色而不是灰色。
        assertEquals(Level.GOOD, c.levelOf(Metric.MEMORY, -1));
        assertEquals(Level.GOOD, c.levelOf(Metric.ENTITIES, -1));
        assertEquals(Level.GOOD, c.levelOf(Metric.CHUNKS, -1));
        assertEquals(Level.GOOD, c.levelOf(Metric.PLAYERS, -1));
    }

    @Test
    @DisplayName("NaN 对所有指标都是 UNKNOWN")
    void nanIsUnknownForEveryMetric() {
        ThresholdColors c = colors();
        for (Metric m : Metric.values()) {
            assertEquals(Level.UNKNOWN, c.levelOf(m, Double.NaN),
                m + " 的 NaN 必须是 UNKNOWN");
        }
    }

    // ==================== 配置缺失 ====================

    @Test
    @DisplayName("configManager.get() 返回 null 时不抛异常，所有指标返回 UNKNOWN")
    void nullConfigIsUnknownAndNeverThrows() {
        // 真实形态：ConfigManager 已构造、load() 尚未成功（插件构造期～onEnable 之间）
        ThresholdColors c = new ThresholdColors(new ConfigManager(null));
        for (Metric m : Metric.values()) {
            Level level = assertDoesNotThrow(() -> c.levelOf(m, 42.0),
                m + " 在配置为 null 时抛异常了");
            assertEquals(Level.UNKNOWN, level, m + " 在配置为 null 时应为 UNKNOWN");
        }
        // 带世界名的重载同样不抛
        assertDoesNotThrow(() -> c.levelOf(Metric.ENTITIES, 42.0, "world_nether"));
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.ENTITIES, 42.0, "world_nether"));
    }

    @Test
    @DisplayName("NaN 判断早于配置判空：配置为 null 时 NaN 仍得到 UNKNOWN（而非 NPE）")
    void nanCheckPrecedesConfigLookup() {
        ThresholdColors c = new ThresholdColors(new ConfigManager(null));
        // 顺序无关紧要的话这条与上一条等价；但它锁住了"NaN 在 cfg==null 之前返回"这个顺序，
        // 保证配置未就绪时也不会因为 NaN 走到 switch 里的字段解引用。
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.TPS, Double.NaN));
        assertEquals(Level.UNKNOWN, c.levelOf(Metric.MSPT, Double.NaN));
    }

    @Test
    @DisplayName("metric 为 null 抛 NPE（switch 解引用），不被静默吞成 UNKNOWN")
    void nullMetricThrows() {
        // 这是调用方的编程错误，不该被"展示层永不抛异常"的美德掩盖成灰色数值。
        // 锁住它是为了：若有人给 levelOf 加 null 守卫，必须是有意识的决定而不是顺手。
        assertThrows(NullPointerException.class, () -> colors().levelOf(null, 1.0));
    }

    // ==================== 色阶映射 ====================

    @Test
    @DisplayName("colorOf：GOOD 绿 / WARN 黄 / CRITICAL 红 / UNKNOWN 灰（断言具体颜色）")
    void colorMappingIsExact() {
        ThresholdColors c = colors();
        assertSame(NamedTextColor.GREEN, c.colorOf(Level.GOOD));
        assertSame(NamedTextColor.YELLOW, c.colorOf(Level.WARN));
        assertSame(NamedTextColor.RED, c.colorOf(Level.CRITICAL));
        assertSame(NamedTextColor.GRAY, c.colorOf(Level.UNKNOWN));

        // 四个等级必须映射到四个互不相同的颜色（漏一个 case 会落到 default 的 GRAY，
        // 于是"严重"变成灰——与"未知"无法区分）
        NamedTextColor[] all = {
            c.colorOf(Level.GOOD), c.colorOf(Level.WARN),
            c.colorOf(Level.CRITICAL), c.colorOf(Level.UNKNOWN)
        };
        assertEquals(4, java.util.Arrays.stream(all).distinct().count(),
            "四个 Level 的颜色必须两两不同");
    }

    @Test
    @DisplayName("colorOf / cssClass 对 null 抛 NPE（不是静默落 default）")
    void nullLevelThrowsInMappingHelpers() {
        // Java 的 switch(enum) 对 null 先抛 NPE，走不到 default 分支。这条把当前行为钉住：
        // 若将来有人以为"default 会兜住 null"而依赖它做防御，这里会立刻变红。
        // 与 levelOf 不同——那是运行期数据，这是编程错误，NPE 是合适的信号。
        assertThrows(NullPointerException.class, () -> colors().colorOf(null));
        assertThrows(NullPointerException.class, () -> colors().cssClass(null));
    }

    @Test
    @DisplayName("cssClass：good / warn / critical / unknown（报告层样式名）")
    void cssClassMappingIsExact() {
        ThresholdColors c = colors();
        assertEquals("good", c.cssClass(Level.GOOD));
        assertEquals("warn", c.cssClass(Level.WARN));
        assertEquals("critical", c.cssClass(Level.CRITICAL));
        assertEquals("unknown", c.cssClass(Level.UNKNOWN));
        // 报告层 CSS 里只有这四个类名，且必须两两不同：
        // ReportGenerator#htmlCard 直接把它拼进 class 属性，重复会让"严重"与"未知"同色
        assertEquals(4, java.util.Arrays.stream(new String[]{
            c.cssClass(Level.GOOD), c.cssClass(Level.WARN),
            c.cssClass(Level.CRITICAL), c.cssClass(Level.UNKNOWN)
        }).distinct().count());
    }

    // ==================== text() 组装 ====================

    @Test
    @DisplayName("text() 的文本与颜色都对，且与 Component.text(text, colorOf(level)) 等价")
    void textBuildsColoredComponent() {
        ThresholdColors c = colors();

        Component red = c.text("95%", Level.CRITICAL);
        assertEquals(Component.text("95%", NamedTextColor.RED), red);
        assertEquals("95%", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(red), "文本内容必须原样保留");
        assertEquals(NamedTextColor.RED, red.color());

        assertEquals(Component.text("42 ms", NamedTextColor.YELLOW), c.text("42 ms", Level.WARN));
        assertEquals(Component.text("20.0", NamedTextColor.GREEN), c.text("20.0", Level.GOOD));
        assertEquals(Component.text("--", NamedTextColor.GRAY), c.text("--", Level.UNKNOWN));

        // 与手工组装的等价性（命令层 40+ 处直接 Component.text(...) 与此方法并存）
        for (Level level : Level.values()) {
            assertEquals(Component.text("x", c.colorOf(level)), c.text("x", level),
                level + " 的 text() 与 colorOf() 不一致");
        }
    }

    @Test
    @DisplayName("text() 不解释文本内容：百分号、花括号、颜色码都按字面量")
    void textTreatsInputAsLiteral() {
        ThresholdColors c = colors();
        // 命令层常把 "12.5%" / "{0}" / "§c" 这类字符串喂进来；凡被当作格式串解析就会丢字
        assertEquals("12.5%", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(c.text("12.5%", Level.WARN)));
        assertEquals("a{b}c", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(c.text("a{b}c", Level.GOOD)));
        assertEquals("", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(c.text("", Level.GOOD)), "空串仍是合法的空组件");
    }

    // ==================== TPS 趋势桶 ====================

    @Test
    @DisplayName("tpsTrendBucket：>=20 为 3，GOOD 2，WARN 1，CRITICAL 0")
    void tpsTrendBucketMapping() {
        ThresholdColors c = colors();
        assertEquals(3, c.tpsTrendBucket(20.0), "恰好 20.0 就是满档 3");
        assertEquals(3, c.tpsTrendBucket(20.5));
        assertEquals(2, c.tpsTrendBucket(19.99));
        assertEquals(2, c.tpsTrendBucket(18.01));
        // 恰好等于 warning 阈值时，levelOf 已判 WARN → 桶 1
        assertEquals(1, c.tpsTrendBucket(18.0));
        assertEquals(1, c.tpsTrendBucket(15.01));
        assertEquals(0, c.tpsTrendBucket(15.0));
        assertEquals(0, c.tpsTrendBucket(0.0), "TPS 0 是真实冻结，归 0 档");
    }

    @Test
    @DisplayName("tpsTrendBucket 的边界与 levelOf 保持一致（同一阈值来源）")
    void tpsTrendBucketAgreesWithLevelOf() {
        ThresholdColors c = colors();
        // 桶 2 ⇔ GOOD、桶 1 ⇔ WARN、桶 0 ⇔ CRITICAL/UNKNOWN，采样整条值域
        double[] samples = {20.0, 19.5, 18.0, 17.9, 15.0, 14.9, 10.0, 1.0, 0.0};
        for (double tps : samples) {
            int bucket = c.tpsTrendBucket(tps);
            Level level = c.levelOf(Metric.TPS, tps);
            int expected = level == Level.GOOD ? 2 : level == Level.WARN ? 1 : 0;
            if (tps >= 20.0) {
                expected = 3;
            }
            assertEquals(expected, bucket, "tps=" + tps + " 的桶与 levelOf 不一致（level=" + level + "）");
        }
    }

    @Test
    @DisplayName("tpsTrendBucket 对 NaN 落到 0 档（UNKNOWN 的 default 分支）")
    void tpsTrendBucketOfNanIsZero() {
        // NaN 是"未采集"；0 档在命令层被画成 "!"（红）。命令层另有 tps<=0 的灰色 "?" 守卫，
        // 但报告层（ReportGenerator#renderTrend）只判 MODULE_TPS 不判 NaN，因此这条值得钉住。
        assertEquals(0, colors().tpsTrendBucket(Double.NaN));
    }

    // ==================== 极端值 ====================

    @Test
    @DisplayName("无穷大：高值差指标 CRITICAL；TPS +∞ 为 GOOD；-∞ 只在 TPS 上变红")
    void infinitiesAreHandledByPlainComparison() {
        ThresholdColors c = colors();
        assertEquals(Level.CRITICAL, c.levelOf(Metric.MSPT, Double.POSITIVE_INFINITY));
        assertEquals(Level.CRITICAL, c.levelOf(Metric.MEMORY, Double.POSITIVE_INFINITY));
        assertEquals(Level.CRITICAL, c.levelOf(Metric.CHUNKS, Double.POSITIVE_INFINITY));
        // TPS 越高越好，+∞ 是 GOOD；-∞ 会被 `-inf <= 15` 命中 → CRITICAL（与 -1 同一类问题，
        // 但 -∞ 同样不可能由采集层产生，属记录而非实害）
        assertEquals(Level.GOOD, c.levelOf(Metric.TPS, Double.POSITIVE_INFINITY));
        assertEquals(Level.CRITICAL, c.levelOf(Metric.TPS, Double.NEGATIVE_INFINITY));
        // MEMORY -∞ 没有哨兵判断 → GOOD（与 -1 同理）
        assertEquals(Level.GOOD, c.levelOf(Metric.MEMORY, Double.NEGATIVE_INFINITY));
    }

    // ==================== 纯函数性 ====================

    @Test
    @DisplayName("同一实例重复调用结果一致（无隐藏状态 / 无缓存污染）")
    void repeatedCallsAreStable() {
        ThresholdColors c = colors();
        for (int i = 0; i < 3; i++) {
            assertEquals(Level.WARN, c.levelOf(Metric.TPS, 18.0));
            assertEquals(Level.CRITICAL, c.levelOf(Metric.MSPT, 50.0));
            assertEquals(Level.UNKNOWN, c.levelOf(Metric.CPU_PROCESS, -1));
        }
        // 两个独立实例对同一配置给出同一答案（构造函数不缓存任何派生值）
        assertEquals(c.levelOf(Metric.ENTITIES, 25_000), colors().levelOf(Metric.ENTITIES, 25_000));
    }

    @Test
    @DisplayName("阈值取自构造时传入的 ConfigManager，换一个实例就换一套阈值")
    void thresholdsComeFromTheInjectedManager() {
        ConfigManager.ConfigData strict = defaults();
        strict.tps_warningThreshold = 19.5;
        strict.tps_criticalThreshold = 19.0;
        ThresholdColors c = new ThresholdColors(manager(strict));
        // 18.0 在出厂阈值下是 WARN，在"更严"的配置下必须是 CRITICAL
        assertEquals(Level.CRITICAL, c.levelOf(Metric.TPS, 18.0));
        assertEquals(Level.WARN, c.levelOf(Metric.TPS, 19.25));
        assertEquals(Level.GOOD, c.levelOf(Metric.TPS, 19.6));
    }

    @Test
    @DisplayName("配置被整体替换后（reload 语义）立即生效，无需重建实例")
    void picksUpReplacedConfigWithoutReconstruction() {
        ConfigManager cm = manager(defaults());
        ThresholdColors c = new ThresholdColors(cm);
        assertEquals(Level.WARN, c.levelOf(Metric.TPS, 18.0));

        // 模拟 reload：ConfigManager.load() 末尾是整体替换 config 字段（单次发布）。
        // 两个阈值都要抬：18.0 要变成 CRITICAL，critical 必须 >= 18.0 才可能命中
        ConfigManager.ConfigData reloaded = defaults();
        reloaded.tps_warningThreshold = 19.9;
        reloaded.tps_criticalThreshold = 19.5;
        TestObjects.setField(ConfigManager.class, cm, "config", reloaded);

        assertEquals(Level.CRITICAL, c.levelOf(Metric.TPS, 18.0), "reload 后阈值必须立即生效");
    }
}
