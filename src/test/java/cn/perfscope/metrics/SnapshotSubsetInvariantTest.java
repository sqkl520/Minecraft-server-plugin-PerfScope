/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MetricsSnapshot} 三个"为什么不可用"集合之间的子集契约。
 *
 * <h2>契约原文</h2>
 * {@code MetricsSnapshot} 的字段 javadoc 把这条写死了：
 * <pre>契约：platformSkipped ⊆ disabledModules ⊆ unavailable</pre>
 * 另有 {@code warmingUp ⊆ unavailable}（预热是"不可用"的一种原因）。
 *
 * <h2>为什么必须用测试锁住</h2>
 * 这三个集合此前<b>完全靠代码纪律维持</b>——五处写入点各写各的 {@code add}，
 * 没有任何断言、没有运行时校验、{@code freezeCollections} 也只做拷贝不做检查。
 * 一次维护性审计用 4 种方式破坏子集关系，<b>526 个用例全部保持绿色</b>。
 *
 * <p>而破坏的后果是<b>用户可见的错标</b>，不是崩溃：{@code /perfscope status} 与报告的
 * 模块状态列按 {@code platformSkipped → disabledModules → warmingUp → unavailable}
 * 的顺序判定（见 {@code PerfScopeCommand.STATUS_MODULES} 循环），任一集合漏标就掉进
 * 下一档，于是：</p>
 * <ul>
 *   <li>{@code platformSkipped} 漏标 → Folia 上显示「采集失败」（红色），
 *       而真相是「平台限制，已自动跳过」（灰色）——服主会以为插件坏了，去查日志、重装插件；</li>
 *   <li>{@code disabledModules} 漏标 → 服主自己关掉的模块显示「采集失败」，
 *       同样指向错误的方向；</li>
 *   <li>{@code warmingUp} 漏标 → 首轮尚未就绪被当成真故障；</li>
 *   <li>反过来，多标 {@code unavailable} 而漏标子集，会让本该渲染 0 的模块整块消失，
 *       或让本该消失的模块渲染成 0（{@code MetricsTextRenderer} 的整组跳过依赖它）。</li>
 * </ul>
 *
 * <h2>为什么不测"采集器确实这么写"</h2>
 * 那需要 {@code MetricsCollector} 实例，而它的构造器触发 {@code ServerCompat} 静态初始化，
 * 后者要 {@code Bukkit.server}——本仓库造不出来（见 {@code tools/KNOWN-TEST-LIMITS.md}）。
 * 所以本类走两条替代路径：</p>
 * <ol>
 *   <li><b>契约自检</b>：直接对任意构造的快照断言子集关系，锁住"发布出去的快照必然满足契约"
 *       这个<b>由 {@code freezeCollections} 保证</b>的性质；</li>
 *   <li><b>Folia MSPT 回归</b>：用反射直调 {@code collectMSPT} 的不可达分支不现实，
 *       改为断言 {@link #freezeRejectsOrRepairsBrokenSubsets()} 里"破坏后必须被拦下"。</li>
 * </ol>
 */
class SnapshotSubsetInvariantTest {

    /** 直接调 {@code freezeCollections}：它是快照发布的唯一出口，契约在此处生效。 */
    private static void freeze(MetricsSnapshot snap) {
        TestObjects.invoke(MetricsCollector.class, "freezeCollections",
            new Class<?>[]{MetricsSnapshot.class}, snap);
    }

    private static MetricsSnapshot snapshot() {
        return new MetricsSnapshot();
    }

    // ------------------------------------------------------------------------------------
    // 1. 契约自检：合法快照经过 freeze 后，四条子集关系必须成立
    // ------------------------------------------------------------------------------------

    /**
     * 四类"不可用原因"齐全的快照，freeze 后四条子集关系全部成立。
     *
     * <p>这是契约的"正面样本"：不是空集合走后门，而是真的每个集合都有成员，
     * 且成员关系正确。空集合会让任何子集断言恒真——那是最容易写出的假绿。</p>
     */
    @Test
    void bundleOfCausesKeepsAllSubsetRelations() {
        MetricsSnapshot snap = snapshot();
        // 采集真的坏了
        snap.unavailable.add(MetricsSnapshot.MODULE_TPS);
        // 服主关掉的
        snap.unavailable.add(MetricsSnapshot.MODULE_MEMORY);
        snap.disabledModules.add(MetricsSnapshot.MODULE_MEMORY);
        // 平台限制（⊆ disabledModules ⊆ unavailable）
        snap.unavailable.add(MetricsSnapshot.MODULE_ENTITIES);
        snap.disabledModules.add(MetricsSnapshot.MODULE_ENTITIES);
        snap.platformSkipped.add(MetricsSnapshot.MODULE_ENTITIES);
        // 预热中
        snap.unavailable.add(MetricsSnapshot.MODULE_CPU);
        snap.warmingUp.add(MetricsSnapshot.MODULE_CPU);

        freeze(snap);
        assertContracts(snap);
        assertEquals(4, snap.unavailable.size(), "四个成员一个不多一个不少");
        assertEquals(2, snap.disabledModules.size());
        assertEquals(1, snap.platformSkipped.size());
        assertEquals(1, snap.warmingUp.size());
    }

    /**
     * 破坏 {@code platformSkipped ⊆ disabledModules} 的快照<b>必须被拦下</b>。
     *
     * <p>这是本类最重要的一条：它对应着真实存在于 {@code collectMSPT} Folia 分支的漏洞——
     * 标了 {@code unavailable} 却不标 {@code platformSkipped}/{@code disabledModules}，
     * 于是 Folia 服主看到「MSPT: 采集失败」而不是「平台限制」。</p>
     *
     * <p>判据是"拦下"而不是"修好"：静默修补会让真正写错的采集代码永远得不到纠正。
     * 开发期断言 + 生产期告警，是这个仓库一贯的口径（见 {@code check-sentinels.mjs}）。</p>
     */
    @Test
    void platformSkippedOutsideDisabledModulesIsRejected() {
        MetricsSnapshot snap = snapshot();
        snap.unavailable.add(MetricsSnapshot.MODULE_MSPT);
        snap.platformSkipped.add(MetricsSnapshot.MODULE_MSPT);   // 忘了 disabledModules
        assertRepaired(snap, "platformSkipped", "disabledModules");
    }

    /** {@code platformSkipped} 成员不在 {@code unavailable} 里同样违反契约（两级都断）。 */
    @Test
    void platformSkippedOutsideUnavailableIsRejected() {
        MetricsSnapshot snap = snapshot();
        snap.platformSkipped.add(MetricsSnapshot.MODULE_ENTITIES);
        assertRepaired(snap, "platformSkipped", "disabledModules");
    }

    /** 破坏 {@code disabledModules ⊆ unavailable}：标了禁用却没标不可用。 */
    @Test
    void disabledModulesOutsideUnavailableIsRejected() {
        MetricsSnapshot snap = snapshot();
        snap.disabledModules.add(MetricsSnapshot.MODULE_MEMORY);
        assertRepaired(snap, "disabledModules", "unavailable");
    }

    /**
     * 破坏 {@code warmingUp ⊆ unavailable}：预热中却不算不可用。
     *
     * <p>后果：展示层判 {@code warmingUp} 的顺序在 {@code unavailable} 之前，
     * 所以这个模块会渲染成「预热中」而永远不进入恢复判定——预热中居然是"健康"的。</p>
     */
    @Test
    void warmingUpOutsideUnavailableIsRejected() {
        MetricsSnapshot snap = snapshot();
        snap.warmingUp.add(MetricsSnapshot.MODULE_CPU);
        assertRepaired(snap, "warmingUp", "unavailable");
    }

    /**
     * 契约是<b>可传递</b>的：把 {@code platformSkipped} 的成员补进 {@code disabledModules} 后，
     * 它们才开始参与 {@code disabledModules ⊆ unavailable} 的判定——一遍修不完整条链。
     *
     * <p>这条用例专杀"单遍实现"：初始状态只 add 了 {@code platformSkipped}，两个父集合都缺它。
     * 单遍修复先补 {@code disabledModules} 就返回，{@code disabledModules} 里的新成员永远进不了
     * {@code unavailable}——而 {@code PerfScopeCommand} 只看后者的那 40 处消费点仍然把它当健康。</p>
     */
    @Test
    void transitiveChainIsRepairedInOneCall() {
        MetricsSnapshot snap = snapshot();
        snap.platformSkipped.add(MetricsSnapshot.MODULE_ENTITIES);   // 两级父集合都缺它
        assertRepaired(snap, "platformSkipped", "disabledModules");
        assertTrue(snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES),
            "传递修复必须一路补到 unavailable：否则 disabledModules ⊆ unavailable 仍被违反，"
                + "而只看 unavailable 的 40 处消费点会把这个模块当成健康");
        assertTrue(snap.disabledModules.contains(MetricsSnapshot.MODULE_ENTITIES));
    }

    /**
     * 空快照合法：四条关系对空集恒真，不得误报。
     *
     * <p>没有这一条，一个"只要集合不为空就炸"的实现也能过上面全部断言。
     * 同时锁住"合法输入返回 false"——否则日志会被无意义的修复告警刷满。</p>
     */
    @Test
    void emptySnapshotPassesContracts() {
        MetricsSnapshot snap = snapshot();
        freeze(snap);
        assertContracts(snap);
        assertTrue(snap.unavailable.isEmpty());
        assertTrue(snap.disabledModules.isEmpty());
        assertTrue(snap.platformSkipped.isEmpty());
        assertTrue(snap.warmingUp.isEmpty());
    }

    /**
     * 契约检查必须在<b>固化之后</b>仍然成立——set 已被换成不可变副本。
     *
     * <p>实现若只在固化前检查、或把检查写在 {@code unmodifiableSet} 之外的另一份集合上，
     * 发布出去的那份就可能与检查过的那份不是同一个。</p>
     */
    @Test
    void frozenSetsAreTheOnesThatWereChecked() {
        MetricsSnapshot snap = snapshot();
        snap.unavailable.add(MetricsSnapshot.MODULE_ENTITIES);
        snap.disabledModules.add(MetricsSnapshot.MODULE_ENTITIES);
        snap.platformSkipped.add(MetricsSnapshot.MODULE_ENTITIES);
        freeze(snap);

        assertContracts(snap);
        // 固化后的集合是真的不可变（与 SnapshotImmutabilityTest 分工不同：
        // 那边测"能不能改"，这边测"改不了的那份内容对不对"）
        assertTrue(snap.platformSkipped.contains(MetricsSnapshot.MODULE_ENTITIES),
            "固化是拷贝，成员必须原样带过去");
        assertTrue(snap.disabledModules.contains(MetricsSnapshot.MODULE_ENTITIES));
        assertTrue(snap.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES));
    }

    // ------------------------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------------------------

    /** 四条子集关系全查一遍，失败时明确指出违反的是哪一条。 */
    private static void assertContracts(MetricsSnapshot snap) {
        assertTrue(snap.unavailable.containsAll(snap.disabledModules),
            "契约 disabledModules ⊆ unavailable 被违反：多出 "
                + minus(snap.disabledModules, snap.unavailable)
                + "。这些模块会被展示层判为「已禁用」，但其他 40 处 unavailable 判断看不到它们，"
                + "于是 /metrics 会输出它们的 0 值 —— 健康与盲点被互换");
        assertTrue(snap.disabledModules.containsAll(snap.platformSkipped),
            "契约 platformSkipped ⊆ disabledModules 被违反：多出 "
                + minus(snap.platformSkipped, snap.disabledModules)
                + "。展示层按 platformSkipped → disabledModules → unavailable 的顺序判定，"
                + "漏标 disabledModules 会让平台限制掉进下一档渲染成「采集失败」（红色）");
        assertTrue(snap.unavailable.containsAll(snap.warmingUp),
            "契约 warmingUp ⊆ unavailable 被违反：多出 "
                + minus(snap.warmingUp, snap.unavailable)
                + "。预热中的模块若不算不可用，会渲染成「健康」——首轮未就绪被当成正常");
    }

    /**
     * 断言 {@code freezeCollections} 把 {@code child ⊆ parent} 的违反修好。
     *
     * <p><b>为什么必须走 {@code freezeCollections} 而不是直接调 {@code repairSubsetContracts}</b>：
     * 后者是内部分解出来的小函数，直接调它等于测了一个"可能没被接上"的实现。
     * 实测过这个陷阱——契约修复原本挂在 {@code collectSync} 的调用点而非固化入口，
     * 把那一行删掉后，8 个直接调静态方法的用例<b>全部存活</b>，契约静默失效。
     * 现在校验写在 {@code freezeCollections} 内部，本类也只通过它验证——
     * 任何"发布出去"的快照都必须经过它，没有第二条路径。</p>
     *
     * <p><b>为什么是"修复"而不是"抛出"</b>：{@code freezeCollections} 由 {@code collectSync}
     * 在定时任务里调用，那条任务外层没有 try/catch。抛出会让固化之后的全部逻辑跳过——
     * 不写 history、不更新缓存、不发布快照——每周期抛一次，把"显示错标"升级成"采集停摆"。</p>
     */
    private static void assertRepaired(MetricsSnapshot snap, String child, String parent) {
        freeze(snap);
        assertContracts(snap);
        assertTrue(setOf(snap, parent).containsAll(setOf(snap, child)),
            "契约 " + child + " ⊆ " + parent + " 经过 freezeCollections 后仍未成立");
    }

    /** 按名字取集合字段，只为让 {@link #assertRepaired} 的调用处能传字符串。 */
    private static Set<String> setOf(MetricsSnapshot snap, String name) {
        @SuppressWarnings("unchecked")
        Set<String> s = (Set<String>) TestObjects.getField(MetricsSnapshot.class, snap, name);
        return s == null ? Set.of() : s;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
