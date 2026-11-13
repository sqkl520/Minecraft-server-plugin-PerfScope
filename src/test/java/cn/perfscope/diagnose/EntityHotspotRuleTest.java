/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.diagnose;

import cn.perfscope.alert.AlertServiceStateMachineFixture;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.ChunkHotspot;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.util.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断规则<b>本体</b>：{@link CoreRules.EntityHotspotRule}（DIAG-ENT-HOTSPOT）。
 *
 * <p>纯函数：只读 {@code snap.topEntityChunks} 与 {@code cfg.hotspots_chunkWarningThreshold}，
 * 不碰历史、不碰时钟。构造有序的 {@link ChunkHotspot} 列表即可驱动。</p>
 *
 * <h2>两个阈值怎么叠加</h2>
 *
 * <p>命中线 = {@code max(ABSOLUTE_FLOOR=100, cfg.hotspots_chunkWarningThreshold)}；
 * 严重线 = {@code entityCount >= CHUNK_CRITICAL=500}，<b>与配置无关</b>。
 * 后者是本规则唯一一处"量纲混用"：配置把命中线拉到 1000 时，一个恰好 1000 的区块会同时
 * 越过命中线与固定的 500 严重线，被判 critical。见
 * {@link #knownDefectChunkAtTheRaisedThresholdIsCritical()}。</p>
 */
class EntityHotspotRuleTest {

    private static final int DEFAULT_THRESHOLD = 200;

    @BeforeAll
    static void initMessages() throws Exception {
        AlertServiceStateMachineFixture.initMessages();
    }

    // ==================== 装配 ====================

    private static ConfigManager.ConfigData config(int chunkWarningThreshold) {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.hotspots_chunkWarningThreshold = chunkWarningThreshold;
        return cfg;
    }

    private static ChunkHotspot chunk(String world, int chunkX, int chunkZ, int entityCount,
                                      String dominantType, int dominantCount) {
        return new ChunkHotspot(world, chunkX, chunkZ, entityCount, dominantType, dominantCount);
    }

    /** 单个区块、给定实体数、主导类型为 ZOMBIE。 */
    private static MetricsSnapshot snapshotWith(int entityCount) {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.topEntityChunks = new ArrayList<>(List.of(
            chunk("world", 12, -3, entityCount, "ZOMBIE", Math.max(0, entityCount - 1))));
        return snap;
    }

    private static Evaluation evaluate(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        // history 传 null 是刻意的：该规则不读历史，传 null 才能证明这一点
        return new CoreRules.EntityHotspotRule().evaluate(snap, null, cfg);
    }

    private static String reason(String key, Object... args) {
        return Messages.get(key, args);
    }

    // ==================== 三态之"无法评估" ====================

    /** 热点模块不可用 → skip，原因点名模块（不能拿"没数据"当"没热点"）。 */
    @Test
    void unavailableHotspotModuleSkips() {
        MetricsSnapshot snap = snapshotWith(900);
        snap.unavailable.add(MetricsSnapshot.MODULE_HOTSPOTS);

        Evaluation ev = evaluate(snap, config(DEFAULT_THRESHOLD));

        assertNull(ev.hit());
        assertFalse(ev.isClean(), "a rule whose precondition failed did NOT evaluate — it must skip");
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.hotspots-unavailable"), ev.skip().reason());
        assertEquals("DIAG-ENT-HOTSPOT", ev.skip().ruleId());
    }

    /**
     * 还没采到热点样本（首轮全遍历未完成）→ skip，且原因<b>不同于</b>"模块不可用"。
     *
     * <p>两者对服主的含义完全不同：一个是"等 30 秒就好"，一个是"去看看配置或日志"。</p>
     */
    @Test
    void emptyHotspotListSkipsWithItsOwnReason() {
        MetricsSnapshot snap = new MetricsSnapshot();
        assertTrue(snap.topEntityChunks.isEmpty(), "precondition: a fresh snapshot has no hotspot yet");

        Evaluation ev = evaluate(snap, config(DEFAULT_THRESHOLD));

        assertNull(ev.hit());
        assertFalse(ev.isClean());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.hotspots-not-ready"), ev.skip().reason());
        assertFalse(ev.skip().reason().equals(reason("diag.skip.hotspots-unavailable")),
            "not-yet-sampled and module-unavailable must stay distinguishable");
    }

    // ==================== 反向：评估了但干净 ====================

    /** 最高的区块都没到 200（配置阈值）→ clean（不是 skip：规则确实比过了）。 */
    @Test
    void topChunkBelowConfiguredThresholdIsClean() {
        Evaluation ev = evaluate(snapshotWith(199), config(DEFAULT_THRESHOLD));

        assertNull(ev.hit(), "199 entities is one short of the 200 per-chunk warning line");
        assertTrue(ev.isClean());
        assertNull(ev.skip());
    }

    /**
     * 配置阈值很低（20）时必须被 {@code ABSOLUTE_FLOOR=100} 托住。
     *
     * <p>否则小服上"一个区块 60 只动物"就会报警——那是正常村庄，不是热点。
     * 这条用例同时杀掉把 {@code Math.max} 写成 {@code Math.min} 的变异体
     * （min(100, 20) = 20，60 就会命中）。</p>
     */
    @Test
    void absoluteFloorOverridesATooLowConfiguredThreshold() {
        Evaluation ev = evaluate(snapshotWith(60), config(20));

        assertNull(ev.hit(), "60 entities never counts as a hotspot, whatever the config says");
        assertTrue(ev.isClean(), "it was evaluated against the floor and found fine");
    }

    /** 边界：恰好 100（绝对下限）→ 命中，证明下限是"含等号"。 */
    @Test
    void exactlyAtTheAbsoluteFloorHits() {
        Evaluation ev = evaluate(snapshotWith(100), config(20));

        assertNotNull(ev.hit(), "100 is the inclusive floor once the config asks for less");
    }

    // ==================== 正向：必须命中 ====================

    /** Top1 越线 → 必须给出坐标级结论，detail 带世界名与坐标。 */
    @Test
    void topChunkAboveThresholdProducesADiagnosisWithCoordinates() {
        Evaluation ev = evaluate(snapshotWith(640), config(DEFAULT_THRESHOLD));

        Diagnosis d = ev.hit();
        assertNotNull(d, "640 entities in one chunk must be reported");
        assertEquals("DIAG-ENT-HOTSPOT", d.ruleId());
        assertFalse(d.title().isEmpty(), "the diagnosis must carry a title");
        assertFalse(d.suggestion().isEmpty(), "the diagnosis must carry an actionable suggestion");

        // 世界名与区块坐标是两个独立占位符，必须各自出现在 detail 里
        assertTrue(d.detail().contains("world"),
            "detail must name the world so the owner knows where to look, got: " + d.detail());
        assertTrue(d.detail().contains("[12, -3]"),
            "detail must carry the chunk coordinates, got: " + d.detail());
        // 方块坐标 = chunkX*16+8 / chunkZ*16+8（V1.2 §5.2 锁定换算）
        assertTrue(d.detail().contains("200"), "blockX of chunkX=12 is 200, got: " + d.detail());
        assertTrue(d.detail().contains("-40"), "blockZ of chunkZ=-3 is -40, got: " + d.detail());
    }

    /** detail 用消息层同一模板做全等比较：占位符错位（世界名/坐标互换）会当场红。 */
    @Test
    void detailRendersExactlyTheTemplateArguments() {
        Diagnosis d = evaluate(snapshotWith(640), config(DEFAULT_THRESHOLD)).hit();
        assertNotNull(d);

        String expected = Messages.get("diag.DIAG-ENT-HOTSPOT.detail",
            "world", 12, -3, 200, -40, 640, DEFAULT_THRESHOLD, "ZOMBIE", 100);
        assertEquals(expected, d.detail());
    }

    /** 配置阈值高于 100 且被越过时，detail 报的是<b>生效</b>阈值，不是写死的 100。 */
    @Test
    void detailReportsTheEffectiveThresholdNotTheFloor() {
        Diagnosis d = evaluate(snapshotWith(400), config(300)).hit();
        assertNotNull(d);

        assertTrue(d.detail().contains("300"),
            "detail must quote the effective threshold used for the decision, got: " + d.detail());
    }

    // ==================== critical 分级 ====================

    /** {@code entityCount >= 500} → critical。 */
    @Test
    void chunkAtCriticalLineIsCritical() {
        Diagnosis d = evaluate(snapshotWith(500), config(DEFAULT_THRESHOLD)).hit();

        assertNotNull(d);
        assertTrue(d.critical(), "500 entities in one chunk is severe (inclusive line)");
    }

    /** 边界：499 → 不是 critical（只是警告）。 */
    @Test
    void chunkJustBelowCriticalLineIsAWarning() {
        Diagnosis d = evaluate(snapshotWith(499), config(DEFAULT_THRESHOLD)).hit();

        assertNotNull(d);
        assertFalse(d.critical(), "499 is severe-adjacent but still below the 500 line");
    }

    // ==================== 记录已知缺陷（不改生产代码） ====================

    /**
     * <b>已知缺陷（记录现状，不修）</b>：配置把单区块阈值抬到 1000 时，
     * 一个恰好 1000 的区块会被判 {@code critical=true}。
     *
     * <p>原因：严重线是写死的 {@code CHUNK_CRITICAL=500}，与命中线
     * {@code max(100, cfg.hotspots_chunkWarningThreshold)} 量纲不联动。服主把阈值调到 1000
     * 明确表示"我觉得 1000 才值得一看"，插件却回一句"严重"——严重度与他自己的判断相反。</p>
     *
     * <p>本用例<b>刻意断言缺陷存在</b>：它是一张"当前行为"快照，而不是"正确行为"规格。
     * 修掉该缺陷时这条会变红，届时把它改成断言 {@code !critical} 即可（那时是一个真正的修复，
     * 需要同步 CHANGELOG 的 Fixed 段）。<b>不要为了让这条保持绿而回避修复。</b></p>
     */
    @Test
    void knownDefectChunkAtTheRaisedThresholdIsCritical() {
        Diagnosis d = evaluate(snapshotWith(1000), config(1000)).hit();

        assertNotNull(d, "1000 entities meets the configured 1000 line");
        assertTrue(d.critical(),
            "KNOWN DEFECT: the critical line is a fixed 500, so a chunk that only just reached the "
                + "owner's own 1000 line is still labelled critical");
    }

    /**
     * <b>已知假设（记录现状）</b>：规则只读 {@code topEntityChunks.get(0)}，不自己找最大值。
     *
     * <p>"{@code topEntityChunks} 已按实体数降序"是<b>采集层的承诺</b>，不是本规则能保证的事。
     * 本用例把一份乱序列表喂进去：索引 0 是 150（低于阈值），索引 1 是 5000（远超阈值），
     * 结果是 clean——证明规则确实只看第一项。</p>
     *
     * <p>不做 {@code max} 是刻意的（避免在诊断线程里重复排序）；这里锁住的是
     * <b>这个耦合存在</b>这一事实：如果采集层哪天不再排序，本规则会静默漏报，
     * 而不会有任何测试变红。真正的修法是在采集层或规则入口加一次排序断言。</p>
     */
    @Test
    void onlyTheFirstEntryIsInspected() {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.topEntityChunks = new ArrayList<>(List.of(
            chunk("world", 0, 0, 150, "COW", 100),
            chunk("world_nether", 40, 40, 5000, "PIGLIN", 4900)));

        Evaluation ev = evaluate(snap, config(DEFAULT_THRESHOLD));

        assertNull(ev.hit(),
            "KNOWN ASSUMPTION: the rule trusts ordering and never looks past get(0), so an unsorted "
                + "list hides the 5000-entity chunk");
        assertTrue(ev.isClean());
    }

    // ==================== 纯函数契约 ====================

    /** 规则只读输入：同一份输入跑两遍结论一致，且快照里的列表没被改写。 */
    @Test
    void evaluationIsPureAndRepeatable() {
        MetricsSnapshot snap = snapshotWith(640);
        ConfigManager.ConfigData cfg = config(DEFAULT_THRESHOLD);

        Diagnosis first = evaluate(snap, cfg).hit();
        Diagnosis second = evaluate(snap, cfg).hit();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.detail(), second.detail(), "a pure rule must produce the same evidence twice");
        assertEquals(1, snap.topEntityChunks.size(), "the rule must not mutate the hotspot list");
        assertEquals(640, snap.topEntityChunks.get(0).entityCount());
    }
}
