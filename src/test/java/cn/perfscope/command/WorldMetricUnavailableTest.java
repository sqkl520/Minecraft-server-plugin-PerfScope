/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.command;

import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.TestObjects;
import cn.perfscope.util.ThresholdColors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 两个 P0 的回归测试：<b>真实 0 被渲染成"没数据"</b>。
 *
 * <h2>P0-S1 —— 趋势条把冻结的 TPS 0 画成灰色问号</h2>
 *
 * <p>{@code tps1m} 没有 {@code -1} 哨兵（{@code tools/check-sentinels.mjs} 的
 * {@code NO_SENTINEL_FIELDS} 白纸黑字写着"0 = 服务器冻结，是真实读数"）。旧趋势循环却判
 * {@code unavailable.contains(TPS) || tps <= 0}，于是同一屏上：TPS 行
 * （{@code formatTpsComponent(0)} 红色 0.0）、告警层、导出报告全部判 critical，
 * <b>唯独趋势条把那一个 0 画成灰色问号</b>——服务器完全冻结时，最该被看见的读数
 * 在趋势里变成了"没数据"，恰好把事故现场抹掉。</p>
 *
 * <p><b>靶点</b>：{@code tps = 0} 且 {@code MODULE_TPS} 可用时，字符必须是 "!" 而不是 "?"。
 * 把判据改回 {@code tps <= 0} 这条立刻变红。</p>
 *
 * <h2>P0-T —— 逐世界段与 showWorld 的假 0</h2>
 *
 * <p>{@code showAll} 的逐世界循环与整个 {@code showWorld} 都不查 {@code unavailable}，
 * 无条件 {@code getOrDefault(..., 0)}。0 是完全合理的读数（空世界就是 0），用户没有任何线索
 * 怀疑它是假数据；更糟的是 ENTITIES 的 0 在 {@link ThresholdColors} 里判 GOOD，
 * 会被<b>染成绿色</b>——"实体模块采集失败"在屏幕上表现为"这个世界的实体数很健康"。</p>
 *
 * <p><b>靶点</b>：{@code enabled: false}（模块进 unavailable）时返回 {@code null}
 * （调用方渲染「不可用」），而不是 0；同时<b>另一个模块</b>的字段必须照常出真实读数，
 * 否则"整屏统一判一个模块"的偷懒写法会把它一起打成「不可用」。</p>
 *
 * <h2>为什么是纯函数 + 接线检查</h2>
 *
 * <p>{@code showTPS} / {@code showAll} / {@code showWorld} 都要真 Bukkit
 * （{@code CommandSender}、{@code World}），本仓库没有 MockBukkit（见
 * {@code tools/KNOWN-TEST-LIMITS.md}）。所以数值决策抽成
 * {@link PerfScopeCommand#tpsTrendGlyph} 与
 * {@link PerfScopeCommand#worldMetricOrUnavailable}——与 {@code worldOrTotal} /
 * {@code computeTrendStats} 同一范式。但纯函数测试<b>锁不住调用点</b>：把调用改回
 * {@code getOrDefault(..., 0)}，纯函数照样绿。于是另有一条接线检查。</p>
 */
class WorldMetricUnavailableTest {

    // ==================== 夹具 ====================

    /** 与出厂 config.yml 一致的阈值（理由见 {@code ThresholdColorsTest} 类注释）。 */
    private static ThresholdColors colors() {
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
        ConfigManager cm = new ConfigManager(null);
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        return new ThresholdColors(cm);
    }

    private static String textOf(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static Set<String> unavailableOf(String... modules) {
        return new LinkedHashSet<>(java.util.List.of(modules));
    }

    private static Map<String, Integer> worldCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("world", 1234);
        m.put("nether", 56);
        return m;
    }

    // ==================== P0-S1：趋势条 ====================

    /**
     * <b>核心靶点</b>：TPS 真实为 0 且模块可用时，趋势位必须是 "!"（最差档），不是 "?"。
     *
     * <p>修前这条是红的（旧判据 {@code || tps <= 0} 会走到 "?" 分支）。</p>
     */
    @Test
    @DisplayName("P0-S1 靶点：TPS=0 是真实冻结读数，趋势位画 \"!\" 而不是 \"?\"")
    void zeroTpsRendersAsWorstBucketNotAsMissing() {
        Component glyph = PerfScopeCommand.tpsTrendGlyph(unavailableOf(), 0.0, colors());

        assertEquals("!", textOf(glyph),
            "TPS 0 with the module available is a frozen server - the single reading most worth "
                + "seeing. Rendering it as '?' hides the incident behind 'no data'");
        assertEquals(NamedTextColor.AQUA, glyph.color(), "it must be coloured like the other trend bits");
    }

    /** 只有模块不可用才是"没数据"：灰 "?"。 */
    @Test
    @DisplayName("P0-S1 对照：MODULE_TPS 不可用时仍是灰色 \"?\"")
    void unavailableModuleStillRendersAsQuestionMark() {
        Component glyph = PerfScopeCommand.tpsTrendGlyph(
            unavailableOf(MetricsSnapshot.MODULE_TPS), 0.0, colors());

        assertEquals("?", textOf(glyph), "no data is still no data - the other half of the branch must survive");
        assertEquals(NamedTextColor.GRAY, glyph.color(), "'?' is deliberately grey so it cannot be read as a reading");
    }

    /** 四档正常值不受影响（防止拆分时把 3/2/1 档顺手改掉）。 */
    @Test
    @DisplayName("四档分档未被本次拆分改动")
    void normalBucketsAreUnchanged() {
        ThresholdColors c = colors();
        assertEquals("|", textOf(PerfScopeCommand.tpsTrendGlyph(unavailableOf(), 20.0, c)));
        assertEquals(".", textOf(PerfScopeCommand.tpsTrendGlyph(unavailableOf(), 19.0, c)));
        assertEquals(":", textOf(PerfScopeCommand.tpsTrendGlyph(unavailableOf(), 16.0, c)));
        assertEquals("!", textOf(PerfScopeCommand.tpsTrendGlyph(unavailableOf(), 10.0, c)));
    }

    /** {@code null} 集合不得 NPE（历史快照的集合理论上非 null，但纯函数不能靠这个假设）。 */
    @Test
    @DisplayName("unavailable 为 null 时按'可用'处理，不 NPE")
    void nullUnavailableMeansAvailable() {
        assertEquals("!", textOf(PerfScopeCommand.tpsTrendGlyph(null, 0.0, colors())));
        assertEquals("|", textOf(PerfScopeCommand.tpsTrendGlyph(null, 20.0, colors())));
    }

    /**
     * 判据里<b>不能有</b> {@code tps <= 0}：那两个数一起出现时结果必须不同。
     *
     * <p>这条是"拆分后到底有没有真拆"的二值判据——若有人把分支又合回
     * {@code unavailable || tps <= 0}，{@code 0.0} 会变成 "?"，与 {@code 20.0} 的差异
     * 就只剩字符而非"数据缺失 vs 读数"。</p>
     */
    @Test
    @DisplayName("P0-S1 二值判据：TPS 0 与 TPS 20 都必须被当真读数处理")
    void zeroAndPerfectTpsAreBothTreatedAsReadings() {
        ThresholdColors c = colors();

        assertNotEquals("?", textOf(PerfScopeCommand.tpsTrendGlyph(unavailableOf(), 0.0, c)),
            "a real 0 must never take the 'missing' branch");
        assertNotEquals("?", textOf(PerfScopeCommand.tpsTrendGlyph(unavailableOf(), 20.0, c)));
    }

    // ==================== P0-T：逐世界读数 ====================

    /**
     * <b>核心靶点</b>：实体模块不可用时返回 {@code null}（调用方渲染「不可用」），不是 0。
     *
     * <p>修前这条是红的：旧实现无条件 {@code getOrDefault("world", 0)}，返回 0。</p>
     */
    @Test
    @DisplayName("P0-T 靶点：entities 模块不可用时世界读数返回 null 而非 0")
    void unavailableEntitiesModuleYieldsNoNumber() {
        Integer v = PerfScopeCommand.worldMetricOrUnavailable(
            unavailableOf(MetricsSnapshot.MODULE_ENTITIES),
            MetricsSnapshot.MODULE_ENTITIES, worldCounts(), "world");

        assertNull(v,
            "the entities module is off, so any number on screen would be fabricated; "
                + "0 is the worst choice because a 0 entity count is a perfectly normal reading");
        assertNotEquals(Integer.valueOf(0), v, "0 must not be used as a stand-in for 'not collected'");
    }

    /** 模块可用时返回真实读数（修复不能把正常路径也打坏）。 */
    @Test
    @DisplayName("P0-T 对照：模块可用时返回该世界的真实读数")
    void availableModuleReturnsTheRealCount() {
        assertEquals(1234, PerfScopeCommand.worldMetricOrUnavailable(
            unavailableOf(), MetricsSnapshot.MODULE_ENTITIES, worldCounts(), "world"));
        assertEquals(56, PerfScopeCommand.worldMetricOrUnavailable(
            unavailableOf(MetricsSnapshot.MODULE_CHUNKS),
            MetricsSnapshot.MODULE_ENTITIES, worldCounts(), "nether"),
            "a different module being unavailable must not blank this one");
    }

    /**
     * <b>只判自己所属的模块</b>：关掉 chunks 时实体列必须照常出数。
     *
     * <p>这条拦住"整屏判一个模块"的偷懒写法——那会让一个模块挂掉连带藏起另外两列的
     * 真实读数，是把 P0-T 换个方向重犯。</p>
     */
    @Test
    @DisplayName("P0-T：各列按所属模块独立判，一个模块挂掉不影响其他列")
    void eachColumnJudgesItsOwnModule() {
        Set<String> onlyChunksOff = unavailableOf(MetricsSnapshot.MODULE_CHUNKS);

        assertNull(PerfScopeCommand.worldMetricOrUnavailable(
                onlyChunksOff, MetricsSnapshot.MODULE_CHUNKS, worldCounts(), "world"),
            "chunks is off -> the chunks column must say so");
        assertEquals(1234, PerfScopeCommand.worldMetricOrUnavailable(
                onlyChunksOff, MetricsSnapshot.MODULE_ENTITIES, worldCounts(), "world"),
            "entities is still on -> its column must keep its real number");
        assertEquals(77, PerfScopeCommand.worldMetricOrUnavailable(
                onlyChunksOff, MetricsSnapshot.MODULE_TILE_ENTITIES,
                Map.of("world", 77), "world"),
            "tile_entities is still on -> its column must keep its real number");
    }

    /**
     * 世界不在 map 里时返回 0（不是 null）。
     *
     * <p>与 {@link PerfScopeCommand#worldOrTotal} 的"缺失即 0"口径一致：世界刚卸载/
     * 快照早于世界创建时它确实有 0 个实体，那不是采集失败。</p>
     */
    @Test
    @DisplayName("世界不在快照 map 里 → 0（缺失不是采集失败）")
    void worldMissingFromMapIsZeroNotUnavailable() {
        assertEquals(0, PerfScopeCommand.worldMetricOrUnavailable(
            unavailableOf(), MetricsSnapshot.MODULE_ENTITIES, worldCounts(), "the_end"));
        assertEquals(0, PerfScopeCommand.worldMetricOrUnavailable(
            unavailableOf(), MetricsSnapshot.MODULE_ENTITIES, null, "world"),
            "null map means the collector has not filled it yet; 0 keeps the caller simple, "
                + "and the unavailable set is what distinguishes 'failed' from 'empty'");
    }

    /** null 集合按"可用"处理，不 NPE。 */
    @Test
    @DisplayName("unavailable 为 null 时按'可用'处理，不 NPE")
    void nullUnavailableSetIsTreatedAsAvailable() {
        assertEquals(1234, PerfScopeCommand.worldMetricOrUnavailable(
            null, MetricsSnapshot.MODULE_ENTITIES, worldCounts(), "world"));
    }

    // ==================== 接线：调用点必须真的走纯函数 ====================

    /**
     * {@code showAll} 的逐世界循环与 {@code showWorld} 的四处读数确实经过
     * {@link PerfScopeCommand#worldMetricOrUnavailable}。
     *
     * <p><b>为什么必须有这条</b>：纯函数写得再对，调用点改回
     * {@code snap.entitiesByWorld.getOrDefault(name, 0)} 也是零成本的一行——那正是本次
     * 修掉的形状。断言用空白归一化后的子串匹配，换行/缩进改动不会误报。</p>
     */
    @Test
    @DisplayName("接线：逐世界读数都经过 worldMetricOrUnavailable（不能回退到裸 getOrDefault）")
    void worldReadingsAreWiredToTheUnavailableAwareHelper() throws IOException {
        String src = normalize(readCommandSource());

        // showAll 逐世界循环：四个字段（强加载区块与 chunks 同模块）
        assertContains(src, "worldMetricOrUnavailable(snap.unavailable, "
            + "MetricsSnapshot.MODULE_ENTITIES, snap.entitiesByWorld, worldName)",
            "showAll's per-world entities must go through the unavailable-aware helper");
        assertContains(src, "worldMetricOrUnavailable(snap.unavailable, MetricsSnapshot.MODULE_CHUNKS, "
            + "snap.chunksByWorld, worldName)", "chunks column");
        assertContains(src, "worldMetricOrUnavailable(snap.unavailable, MetricsSnapshot.MODULE_CHUNKS, "
            + "snap.forceLoadedChunksByWorld, worldName)", "force-loaded column shares the chunks module");
        assertContains(src, "worldMetricOrUnavailable(snap.unavailable, "
            + "MetricsSnapshot.MODULE_TILE_ENTITIES, snap.tileEntitiesByWorld, worldName)", "tile entities column");

        // showWorld 四处（canonicalName）
        assertContains(src, "worldMetricOrUnavailable(snap.unavailable, "
            + "MetricsSnapshot.MODULE_ENTITIES, snap.entitiesByWorld, canonicalName)", "showWorld entities");
        assertContains(src, "worldMetricOrUnavailable(snap.unavailable, MetricsSnapshot.MODULE_CHUNKS, "
            + "snap.chunksByWorld, canonicalName)", "showWorld chunks");
        assertContains(src, "worldMetricOrUnavailable(snap.unavailable, MetricsSnapshot.MODULE_CHUNKS, "
            + "snap.forceLoadedChunksByWorld, canonicalName)", "showWorld force-loaded");
        assertContains(src, "worldMetricOrUnavailable(snap.unavailable, "
            + "MetricsSnapshot.MODULE_TILE_ENTITIES, snap.tileEntitiesByWorld, canonicalName)",
            "showWorld tile entities");

        // 趋势条必须走 tpsTrendGlyph，而不是把判据内联回循环里
        assertContains(src, "trend = trend.append(tpsTrendGlyph(sample.unavailable, sample.tps1m, thresholdColors));",
            "the trend loop must delegate to the pure helper; inlining the judgement again "
                + "is exactly how the `|| tps <= 0` defect was introduced");
    }

    /**
     * 逐世界读数里<b>不允许</b>再出现裸 {@code getOrDefault(name, 0)} 直接送进
     * {@code Component.text(...)} 的形状。
     *
     * <p>反过来写一条"不该有什么"，因为上面那些 {@code assertContains} 只证明新写法在，
     * 不能证明旧写法走了——两行并存也能通过。</p>
     */
    @Test
    @DisplayName("接线：不再有裸 getOrDefault 直接渲染的旧形状")
    void noBareGetOrDefaultIsRenderedDirectly() throws IOException {
        String src = normalize(readCommandSource());

        for (String field : new String[] {"entitiesByWorld", "chunksByWorld",
                "forceLoadedChunksByWorld", "tileEntitiesByWorld"}) {
            assertTrue(!src.contains("Component.text(String.valueOf(snap." + field + ".getOrDefault("),
                "found a bare getOrDefault rendered directly for " + field
                    + " - that is the P0-T shape (it prints 0 when the module is off)");
        }
    }

    // ==================== helpers ====================

    private static void assertContains(String haystack, String needle, String message) {
        assertTrue(haystack.contains(needle), message + "\n  expected to find: " + needle);
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ");
    }

    /** 读 {@code PerfScopeCommand} 源码；找不到就失败而不是跳过（跳过等于这条检查不存在）。 */
    private static String readCommandSource() throws IOException {
        Path relative = Path.of("src", "main", "java", "cn", "perfscope", "command", "PerfScopeCommand.java");
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        fail("cannot locate PerfScopeCommand.java from " + Path.of("").toAbsolutePath());
        return null; // unreachable
    }
}
