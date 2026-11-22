/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.command;

import cn.perfscope.metrics.ThresholdOperator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code /ps entities} 末尾告警段的档位判定（{@link PerfScopeCommand#entityAlertLevel}）。
 *
 * <h2>这条守的是什么</h2>
 *
 * <p>P0-A 把展示层（{@code ThresholdColors}）改成从 {@link ThresholdOperator} 派生之后，
 * 命令层这一处仍自己写着 {@code alertEntities > criticalThreshold}。它<b>没有任何测试</b>，
 * 而当时的数值与算子表里的 {@code ENTITIES_GLOBAL}/{@code ENTITIES_WORLD}（都是 STRICT）
 * <b>恰好相同</b>——于是"两份副本"在行为上完全看不出来，只有改表的那一天才会暴露：
 * 表改成闭区间后，颜色变了、采集层告警变了，这一处的提示语却不跟着动，
 * 同一屏上演"总数已经变黄、却没有那句警告"。</p>
 *
 * <h2>怎么证明它真的读了算子表，而不是又写了一遍 {@code >}</h2>
 *
 * <p>断言默认严格规则的四点还不够——一个手写的 {@code >} 也能全过。
 * 所以核心用例喂进去一条<b>合成规则</b>（{@code INCLUSIVE}）：如果实现是"读 rule"，
 * 恰好等于阈值就该越界；如果实现又写回了 {@code >}，边界那一格会翻，测试立刻红。
 * 这正是变异测试要的那个"能区分两种写法"的点。</p>
 *
 * <h2>为什么还要一条接线检查</h2>
 *
 * <p>把 {@code showEntities} 里的调用改回手写比较，纯函数测试照样全绿。
 * 那正是本次修掉的形状，所以另有一条源码断言（空白归一化后做子串匹配，
 * 换行缩进改动不会误报）。</p>
 */
class EntityAlertLevelTest {

    /** 出厂默认：entities warning 20000 / critical 30000（与 config.yml 一致）。 */
    private static final int WARN = 20_000;
    private static final int CRITICAL = 30_000;

    // ==================== 默认规则（严格 >）的四点表 ====================

    /**
     * 严格规则下的四点：{@code 警告-1} / {@code 警告} / {@code 严重} / {@code 严重+1}。
     *
     * <p>只测"高于阈值"是不够的：把 {@code >} 改成 {@code >=}，低于/高于两档全都不变，
     * 只有"恰好等于"那一列会翻——那正是历史上一整类变异存活的原因。</p>
     */
    @Test
    void strictRuleLeavesTheBoundaryPointsBelowTheLine() {
        ThresholdOperator.Rule rule = ThresholdOperator.ENTITIES_GLOBAL;

        assertEquals(0, PerfScopeCommand.entityAlertLevel(WARN - 1, rule, WARN, CRITICAL));
        assertEquals(0, PerfScopeCommand.entityAlertLevel(WARN, rule, WARN, CRITICAL),
            "恰好等于 warning 阈值时不算越界（STRICT）：与 ThresholdColors / 采集层同口径");
        assertEquals(1, PerfScopeCommand.entityAlertLevel(WARN + 1, rule, WARN, CRITICAL));
        assertEquals(1, PerfScopeCommand.entityAlertLevel(CRITICAL, rule, WARN, CRITICAL),
            "恰好等于 critical 阈值仍是警告档，不是严重档");
        assertEquals(2, PerfScopeCommand.entityAlertLevel(CRITICAL + 1, rule, WARN, CRITICAL));
    }

    /**
     * <b>核心靶点</b>：档位必须来自传入的规则，而不是实现里再写一次 {@code >}。
     *
     * <p>合成一条 {@code INCLUSIVE} 规则后，恰好等于阈值的读数必须越界。
     * 修前的内联 {@code >} 在这里会返回 0 / 1，而期望是 1 / 2。</p>
     */
    @Test
    void levelFollowsTheRulePassedInNotAHardcodedComparison() {
        ThresholdOperator.Rule inclusive = new ThresholdOperator.Rule(
            "entities:global", ThresholdOperator.Direction.HIGH_IS_BAD,
            ThresholdOperator.Comparison.INCLUSIVE, ThresholdOperator.Comparison.INCLUSIVE);

        assertEquals(1, PerfScopeCommand.entityAlertLevel(WARN, inclusive, WARN, CRITICAL),
            "an INCLUSIVE rule must trip exactly at the warning threshold — returning 0 means the "
                + "implementation wrote its own '>' instead of reading the operator table");
        assertEquals(2, PerfScopeCommand.entityAlertLevel(CRITICAL, inclusive, WARN, CRITICAL),
            "and exactly at the critical threshold it must already be critical");
    }

    /**
     * 世界覆盖走 {@code ENTITIES_WORLD}：两条规则现在算子相同，但取哪条是命令层的事。
     *
     * <p>靶点：把世界覆盖值喂进去，判定必须用<b>覆盖阈值</b>而不是全局阈值。
     * 用 800 / 600 这种与全局 20000 / 30000 完全不同量级的数字，一旦实现错用全局阈值，
     * 三档会全部落到 0，测试立刻红。</p>
     */
    @Test
    void worldOverrideThresholdsAreTheOnesCompared() {
        int worldWarn = 500;
        int worldCritical = 800;
        ThresholdOperator.Rule rule = ThresholdOperator.ENTITIES_WORLD;

        assertEquals(0, PerfScopeCommand.entityAlertLevel(499, rule, worldWarn, worldCritical));
        assertEquals(1, PerfScopeCommand.entityAlertLevel(501, rule, worldWarn, worldCritical));
        assertEquals(1, PerfScopeCommand.entityAlertLevel(800, rule, worldWarn, worldCritical));
        assertEquals(2, PerfScopeCommand.entityAlertLevel(801, rule, worldWarn, worldCritical));
    }

    /**
     * 世界不在快照里（{@code null}）→ 0（不告警），与 {@code worldOrTotal} 的缺失口径一致。
     *
     * <p>这里刻意不断言"当成 0 去比较"：0 在任何合法阈值下都不越界，两者结果相同，
     * 但 {@code null} 分支必须显式存在，否则自动拆箱会直接 NPE。</p>
     */
    @Test
    void nullEntityCountMeansNoAlert() {
        assertEquals(0, PerfScopeCommand.entityAlertLevel(null, ThresholdOperator.ENTITIES_WORLD, WARN, CRITICAL));
        assertEquals(0, PerfScopeCommand.entityAlertLevel(null, ThresholdOperator.ENTITIES_GLOBAL, WARN, CRITICAL));
    }

    // ==================== 接线：调用点必须真的走纯函数 ====================

    /**
     * {@code showEntities} 的告警段确实调了 {@link PerfScopeCommand#entityAlertLevel}，
     * 且源码里不再有自己写的阈值比较。
     *
     * <p><b>为什么必须有这条</b>：纯函数写得再对，把调用换回
     * {@code if (alertEntities > criticalThreshold)} 也是零成本的一行——那正是本次修掉的形状。
     * 第二段断言（"不许再出现"）比第一段更重要：只断言"调用了"的话，
     * 实现可以既调用纯函数、又在旁边留一份手写比较。</p>
     */
    @Test
    void entityAlertSectionIsWiredToTheOperatorTable() throws IOException {
        String src = normalize(readCommandSource());

        assertTrue(src.contains("entityAlertLevel(alertEntities, alertRule, warnThreshold, criticalThreshold)"),
            "the entities alert section must derive its level from entityAlertLevel(...), "
                + "which in turn reads ThresholdOperator — writing the comparison inline is the bug fixed here");

        assertFalse(src.contains("alertEntities > criticalThreshold"),
            "a hand-written '>' next to the operator-table call is still a fifth copy: it would not follow "
                + "the table when the table changes");
        assertFalse(src.contains("alertEntities > warnThreshold"),
            "same for the warning tier");
    }

    // ==================== helpers ====================

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ");
    }

    /**
     * 读 {@code PerfScopeCommand} 的源码。找不到直接失败而不是跳过——
     * 跳过会让这条接线检查在某台机器上悄悄不存在，而它正是拦住回退的那一条。
     */
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
        fail("cannot locate PerfScopeCommand.java from " + Path.of("").toAbsolutePath()
            + "; the wiring assertions need the source tree (run tests from the project root)");
        return null; // unreachable
    }
}
