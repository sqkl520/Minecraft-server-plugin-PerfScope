/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import cn.perfscope.alert.Alert;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MetricsSnapshot} 的「发布即只读」契约（{@code MetricsCollector.freezeCollections}）。
 *
 * <h2>这个契约是什么</h2>
 *
 * <p>快照一旦发布（写进 history 或 jvmCache）就被视为只读，任何消费方不得修改它的集合字段。
 * 这不是洁癖：快照对象被<b>多个线程同时持有</b>——采集主线程持续改写下一帧的数据结构，
 * HTTP 线程（{@code /metrics}）、报告执行器、告警线程都在遍历同一个已发布的快照。
 * 集合若仍是可变的，一次误改就污染<b>最多 300 份历史快照 + 所有正在生成的报告</b>，
 * 且不可复现。</p>
 *
 * <p>{@code MetricsTextRenderer} 的类注释明确依赖它："传入的 snap 必须已经过
 * {@code freezeCollections} 固化——否则遍历其集合可能与采集线程竞争。"</p>
 *
 * <h2>为什么这个测试类存在</h2>
 *
 * <p>这条契约此前<b>没有任何断言</b>。一轮可维护性审计实测：把 {@code freezeCollections}
 * 里的 {@code unmodifiableMap} 换成裸 {@code LinkedHashMap}、{@code unmodifiableSet}
 * 换成裸 {@code LinkedHashSet}、{@code List.copyOf} 换成 {@code new ArrayList}——
 * <b>三个变异全部存活</b>（500+ 用例全绿）。</p>
 *
 * <p>也就是说这句写在注释里的承诺，删掉它不会有任何信号。</p>
 */
class SnapshotImmutabilityTest {

    /** 反射调 {@code freezeCollections}：它是 private static，且不依赖任何实例状态。 */
    private static void freeze(MetricsSnapshot snap) throws Exception {
        Method m = MetricsCollector.class.getDeclaredMethod("freezeCollections", MetricsSnapshot.class);
        m.setAccessible(true);
        m.invoke(null, snap);
    }

    /** 一个所有集合都非空的快照——空集合走 {@code Map.of()} 短路，测不出固化逻辑。 */
    private static MetricsSnapshot populated() {
        MetricsSnapshot s = new MetricsSnapshot();
        s.entitiesByType = new LinkedHashMap<>(Map.of("ZOMBIE", 10));
        s.entitiesByWorld = new LinkedHashMap<>(Map.of("world", 100));
        s.chunksByWorld = new LinkedHashMap<>(Map.of("world", 50));
        s.forceLoadedChunksByWorld = new LinkedHashMap<>(Map.of("world", 5));
        s.tileEntitiesByWorld = new LinkedHashMap<>(Map.of("world", 20));
        s.alertLevels = new LinkedHashMap<>(Map.of("tps:global", 1));

        s.alerts = new ArrayList<>(List.of(Alert.measuredWithThresholds(
            "tps:global", Alert.LEVEL_WARNING, 17.5, 18.0, 18.0, MetricsSnapshot.MODULE_TPS, 0L)));
        s.activeWarnings = new ArrayList<>(List.of("w1"));
        s.activeCriticals = new ArrayList<>(List.of("c1"));
        s.topEntityChunks = new ArrayList<>(List.of(
            new ChunkHotspot("world", 1, 2, 900, "ZOMBIE", 720)));

        // 四个"为什么不可用"集合必须是契约自洽的样本：freezeCollections 现在会在发布前校验
        // platformSkipped ⊆ disabledModules ⊆ unavailable 与 warmingUp ⊆ unavailable，
        // 一张违反契约的样本根本走不到"检查是否不可变"那一步。
        // 本类测的是"能不能改"，契约本身由 SnapshotSubsetInvariantTest 负责。
        s.unavailable = new LinkedHashSet<>(Set.of("gc", "jvm", "entities"));
        s.warmingUp = new LinkedHashSet<>(Set.of("jvm"));
        s.disabledModules = new LinkedHashSet<>(Set.of("gc", "entities"));
        s.platformSkipped = new LinkedHashSet<>(Set.of("entities"));
        return s;
    }

    /**
     * 五个 Map 都必须不可变。
     *
     * <p>用 {@code unmodifiableMap(LinkedHashMap)} 而不是 {@code Map.copyOf}：后者迭代序未定义，
     * 会把报告里"按世界分布"的顺序打乱，也会让 {@code /metrics} 的输出顺序在两次抓取间变化。</p>
     */
    @Test
    void allMapsAreImmutableAfterFreeze() throws Exception {
        MetricsSnapshot s = populated();
        freeze(s);

        assertThrows(UnsupportedOperationException.class, () -> s.entitiesByType.put("X", 1));
        assertThrows(UnsupportedOperationException.class, () -> s.entitiesByWorld.put("X", 1));
        assertThrows(UnsupportedOperationException.class, () -> s.chunksByWorld.put("X", 1));
        assertThrows(UnsupportedOperationException.class, () -> s.forceLoadedChunksByWorld.put("X", 1));
        assertThrows(UnsupportedOperationException.class, () -> s.tileEntitiesByWorld.put("X", 1));
        assertThrows(UnsupportedOperationException.class, () -> s.alertLevels.put("X", 1));
    }

    /** 四个 List 都必须不可变。 */
    @Test
    void allListsAreImmutableAfterFreeze() throws Exception {
        MetricsSnapshot s = populated();
        freeze(s);

        assertThrows(UnsupportedOperationException.class, () -> s.alerts.clear());
        assertThrows(UnsupportedOperationException.class, () -> s.activeWarnings.add("x"));
        assertThrows(UnsupportedOperationException.class, () -> s.activeCriticals.add("x"));
        assertThrows(UnsupportedOperationException.class, () -> s.topEntityChunks.clear());
    }

    /**
     * 五个 Set 都必须不可变。
     *
     * <p>这三个集合（{@code unavailable} / {@code warmingUp} / {@code disabledModules}）
     * 是 {@code /metrics} 判断"该不该发这个指标"的唯一依据——可变意味着抓取方能在遍历中途
     * 看到集合变化，或者某个消费方一次 {@code add} 就永久污染后续所有读取。</p>
     */
    @Test
    void allSetsAreImmutableAfterFreeze() throws Exception {
        MetricsSnapshot s = populated();
        freeze(s);

        assertThrows(UnsupportedOperationException.class, () -> s.unavailable.add("x"));
        assertThrows(UnsupportedOperationException.class, () -> s.warmingUp.add("x"));
        assertThrows(UnsupportedOperationException.class, () -> s.disabledModules.add("x"));
        assertThrows(UnsupportedOperationException.class, () -> s.platformSkipped.add("x"));
        assertThrows(UnsupportedOperationException.class, () -> s.unavailable.remove("gc"));
    }

    /**
     * 固化是<b>拷贝</b>，不是包装原对象——原集合后续的改动不得漏进快照。
     *
     * <p>这条比"不可变"更严格：如果实现改成 {@code Collections.unmodifiableMap(source)}
     * 而不复制，快照看上去仍然不可写，但采集线程继续写那个源 Map 时，快照的内容会跟着变——
     * 那正是"已发布快照"最不该发生的事。</p>
     */
    @Test
    void freezeCopiesRatherThanWrapsTheLiveCollection() throws Exception {
        MetricsSnapshot s = new MetricsSnapshot();
        Map<String, Integer> liveMap = new LinkedHashMap<>();
        liveMap.put("world", 100);
        Set<String> liveSet = new LinkedHashSet<>(Set.of("gc"));
        List<String> liveList = new ArrayList<>(List.of("w1"));
        s.entitiesByWorld = liveMap;
        s.unavailable = liveSet;
        s.activeWarnings = liveList;

        freeze(s);

        // 采集线程继续改它的活集合
        liveMap.put("nether", 999);
        liveSet.add("memory");
        liveList.add("w2");

        assertEquals(Map.of("world", 100), s.entitiesByWorld,
            "the snapshot must hold a COPY; wrapping the live collection lets later writes leak in");
        assertEquals(Set.of("gc"), s.unavailable);
        assertEquals(List.of("w1"), s.activeWarnings);
    }

    /** 顺序必须保留（报告"按世界分布"与 /metrics 输出稳定性都依赖它）。 */
    @Test
    void insertionOrderSurvivesFreezing() throws Exception {
        MetricsSnapshot s = new MetricsSnapshot();
        Map<String, Integer> ordered = new LinkedHashMap<>();
        ordered.put("zebra", 1);
        ordered.put("alpha", 2);
        ordered.put("middle", 3);
        s.entitiesByWorld = ordered;

        freeze(s);

        assertEquals(List.of("zebra", "alpha", "middle"), new ArrayList<>(s.entitiesByWorld.keySet()),
            "Map.copyOf would lose this order (undefined iteration order); "
                + "reports and /metrics both depend on it");
    }

    /** 空集合走短路路径，但结果也必须不可变。 */
    @Test
    void emptyCollectionsAreStillImmutable() throws Exception {
        MetricsSnapshot s = new MetricsSnapshot();   // 全部默认空
        freeze(s);

        assertThrows(UnsupportedOperationException.class, () -> s.entitiesByWorld.put("X", 1));
        assertThrows(UnsupportedOperationException.class, () -> s.unavailable.add("x"));
        assertThrows(UnsupportedOperationException.class, () -> s.activeWarnings.add("x"));
        assertTrue(s.entitiesByWorld.isEmpty());
    }
}
