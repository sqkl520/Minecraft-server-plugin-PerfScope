/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetricsCollector#markDedicatedModuleUnavailable} —— 四个重模块的不可用标记收口点。
 *
 * <h2>为什么这个方法是唯一写这三个集合的地方</h2>
 * 它此前是<b>四份逐字相同的 10 行块</b>（entities / chunks / tile_entities / hotspots），
 * 唯一的差别是模块名与三个布尔量。四份手写的真实代价不是重复本身，而是
 * <b>漏一个标记不会有任何提示</b>：
 *
 * <p>{@code freezeCollections} 里有个 {@code repairSubsetContracts} 会事后补上，
 * 但"事后补救"意味着<b>数据已经错了一轮</b>才被纠正，且只在日志里留一行限频 WARNING。
 * 收成一个方法后，"写 platformSkipped 却忘了写 disabledModules"在结构上不再可能。</p>
 *
 * <h2>契约（来自 MetricsSnapshot 的字段 javadoc）</h2>
 * {@code platformSkipped ⊆ disabledModules ⊆ unavailable}
 *
 * <p>三者的展示语义各不相同，这是它们必须分开记录的原因：</p>
 * <ul>
 *   <li>{@code unavailable} → 红色「采集失败」；</li>
 *   <li>{@code disabledModules} → 灰色「已禁用（config xxx.enabled=false）」；</li>
 *   <li>{@code platformSkipped} → 灰色「平台限制（Folia 跨 region 聚合不可靠，已自动跳过）」。</li>
 * </ul>
 * <p>{@code PerfScopeCommand} 按 {@code platformSkipped → disabledModules → warmingUp → unavailable}
 * 的顺序判定，所以子集关系一旦破坏，模块就掉进下一档、显示错误的原因。</p>
 */
class ModuleUnavailableMarkingTest {

    private static MetricsSnapshot snap() {
        return new MetricsSnapshot();
    }

    private static void mark(MetricsSnapshot snap, String module,
                             boolean unavailable, boolean disabled, boolean platformSkipped) {
        TestObjects.invoke(MetricsCollector.class, "markDedicatedModuleUnavailable",
            new Class<?>[]{MetricsSnapshot.class, String.class,
                boolean.class, boolean.class, boolean.class},
            snap, module, unavailable, disabled, platformSkipped);
    }

    // ------------------------------------------------------------------------------------
    // 1. 四组输入的完整真值表
    // ------------------------------------------------------------------------------------

    /**
     * <b>核心真值表</b>：三个布尔量的 8 种组合下，三个集合的成员必须分别是这样。
     *
     * <p>这张表就是方法的全部语义——写在这里而不是散在四个调用点的隐式假设里。</p>
     */
    @Test
    void truthTableForAllFlagCombinations() {
        assertMarking(false, false, false, false, false, false);
        // 不可用但既非禁用也非平台限制 = 采集真的失败了
        assertMarking(true, false, false, true, false, false);
        // 配置禁用
        assertMarking(true, true, false, true, true, false);
        // 平台限制：**必须同时进 disabledModules**，这是本方法最重要的那条
        assertMarking(true, false, true, true, true, true);
        // 禁用 + 平台限制同时成立（理论上不该出现，但组合必须被正确处理）
        assertMarking(true, true, true, true, true, true);
        // 不可用为 false 时，另外两个标记被忽略——不会出现"可用但被标禁用"的怪状态
        assertMarking(false, true, false, false, false, false);
        assertMarking(false, false, true, false, false, false);
        assertMarking(false, true, true, false, false, false);
    }

    private static void assertMarking(boolean unavailable, boolean disabled, boolean platformSkipped,
                                      boolean expectUnavailable, boolean expectDisabled,
                                      boolean expectPlatformSkipped) {
        MetricsSnapshot s = snap();
        String module = MetricsSnapshot.MODULE_ENTITIES;
        mark(s, module, unavailable, disabled, platformSkipped);

        String label = "输入(unavailable=" + unavailable + ", disabled=" + disabled
            + ", platformSkipped=" + platformSkipped + ")";
        String got = "unavailable=" + s.unavailable.contains(module)
            + ", disabledModules=" + s.disabledModules.contains(module)
            + ", platformSkipped=" + s.platformSkipped.contains(module);

        assertTrue(s.unavailable.contains(module) == expectUnavailable,
            label + " 的 unavailable 不对，实际 " + got);
        assertTrue(s.disabledModules.contains(module) == expectDisabled,
            label + " 的 disabledModules 不对，实际 " + got);
        assertTrue(s.platformSkipped.contains(module) == expectPlatformSkipped,
            label + " 的 platformSkipped 不对，实际 " + got);

        // 无论输入如何，三条子集契约必须成立——这是本方法存在的全部理由
        assertTrue(s.unavailable.containsAll(s.disabledModules),
            label + " 后违反 disabledModules ⊆ unavailable，实际 " + got);
        assertTrue(s.disabledModules.containsAll(s.platformSkipped),
            label + " 后违反 platformSkipped ⊆ disabledModules，实际 " + got);
        assertTrue(s.unavailable.containsAll(s.platformSkipped),
            label + " 后违反 platformSkipped ⊆ unavailable（传递），实际 " + got);
    }

    // ------------------------------------------------------------------------------------
    // 2. 平台限制必须同时进 disabledModules（最重要的一条）
    // ------------------------------------------------------------------------------------

    /**
     * <b>只传 {@code platformSkipped=true} 时，{@code disabledModules} 也必须被写入。</b>
     *
     * <p>这是本方法要修的那个错：四份手写实现里若有人只写
     * {@code snap.platformSkipped.add(...)} 而忘了 {@code disabledModules}，
     * 展示层按 {@code platformSkipped → disabledModules → unavailable} 判定的顺序
     * 会一路落到 {@code unavailable}，于是 Folia 上显示红色的「采集失败」——
     * 而真相是灰色的「平台限制」。</p>
     *
     * <p>同理，{@code platformSkipped} 也必须进 {@code unavailable}（传递性由两次写入保证）。</p>
     */
    @Test
    void platformSkippedImpliesDisabledAndUnavailable() {
        MetricsSnapshot s = snap();
        String module = MetricsSnapshot.MODULE_TILE_ENTITIES;
        mark(s, module, true, false, true);

        assertTrue(s.platformSkipped.contains(module));
        assertTrue(s.disabledModules.contains(module),
            "平台限制必须同时进 disabledModules：契约是 platformSkipped ⊆ disabledModules，"
                + "漏写会让 Folia 上显示红色「采集失败」而不是灰色「平台限制」");
        assertTrue(s.unavailable.contains(module),
            "平台限制也必须进 unavailable：其余 40 处判定只看 unavailable，"
                + "漏写会让 /metrics 输出这些模块的 0 值——健康与盲点被互换");
    }

    /** {@code disabled=true} 但 {@code platformSkipped=false} 时不得写 platformSkipped。 */
    @Test
    void plainDisableDoesNotImplyPlatformSkip() {
        MetricsSnapshot s = snap();
        String module = MetricsSnapshot.MODULE_HOTSPOTS;
        mark(s, module, true, true, false);

        assertTrue(s.disabledModules.contains(module));
        assertFalse(s.platformSkipped.contains(module),
            "服主自己关掉的模块不能被标成「平台限制」——两者展示语义不同，"
                + "标错会让服主以为平台不支持而不是自己关的");
    }

    // ------------------------------------------------------------------------------------
    // 3. 幂等性与多模块
    // ------------------------------------------------------------------------------------

    /** 重复调用不改变结果（reload 后缓存回放可能连续触发）。 */
    @Test
    void repeatedMarkingIsIdempotent() {
        MetricsSnapshot s = snap();
        String module = MetricsSnapshot.MODULE_CHUNKS;
        mark(s, module, true, false, true);
        mark(s, module, true, false, true);
        mark(s, module, true, false, true);

        assertTrue(s.platformSkipped.contains(module));
        assertTrue(s.disabledModules.contains(module));
        assertTrue(s.unavailable.contains(module));
    }

    /**
     * 四个模块各自独立——标一个不影响另一个。
     *
     * <p>没有这条，一个"每次调用都清空集合"的实现也能过上面的真值表。</p>
     */
    @Test
    void markingOneModuleDoesNotAffectOthers() {
        MetricsSnapshot s = snap();
        mark(s, MetricsSnapshot.MODULE_ENTITIES, true, false, true);
        mark(s, MetricsSnapshot.MODULE_CHUNKS, true, true, false);

        assertTrue(s.platformSkipped.contains(MetricsSnapshot.MODULE_ENTITIES));
        assertFalse(s.platformSkipped.contains(MetricsSnapshot.MODULE_CHUNKS),
            "chunks 是配置禁用而非平台限制，不该带 platformSkipped");
        assertTrue(s.disabledModules.contains(MetricsSnapshot.MODULE_ENTITIES));
        assertTrue(s.disabledModules.contains(MetricsSnapshot.MODULE_CHUNKS));
        assertTrue(s.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES));
        assertTrue(s.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS));
    }

    // ------------------------------------------------------------------------------------
    // 4. 与 freezeCollections 的契约衔接
    // ------------------------------------------------------------------------------------

    /**
     * 标记后经过 {@code freezeCollections}，契约仍然成立且不需要事后修复。
     *
     * <p>{@code freezeCollections} 里的 {@code repairSubsetContracts} 是<b>兜底</b>，
     * 不是常规路径。本方法写对了，修复逻辑就应当什么都不做——
     * 若这里发现需要修，说明本方法的写入顺序或条件有问题。</p>
     */
    @Test
    void markingAloneSatisfiesTheContractWithoutRepair() {
        MetricsSnapshot s = snap();
        for (String m : new String[]{MetricsSnapshot.MODULE_ENTITIES, MetricsSnapshot.MODULE_CHUNKS,
            MetricsSnapshot.MODULE_TILE_ENTITIES, MetricsSnapshot.MODULE_HOTSPOTS}) {
            mark(s, m, true, false, true);
        }

        // 修复逻辑在"已经满足契约"时必须返回 false（什么都没修）
        boolean repaired = (Boolean) TestObjects.invoke(MetricsCollector.class,
            "repairSubsetContracts", new Class<?>[]{MetricsSnapshot.class}, s);
        assertFalse(repaired,
            "四个模块都按契约写好后，repairSubsetContracts 应当无事可做。"
                + "若它报告修复过，说明 markDedicatedModuleUnavailable 的写入不完整——"
                + "而兜底修复意味着数据已经错了一轮");
    }
}
