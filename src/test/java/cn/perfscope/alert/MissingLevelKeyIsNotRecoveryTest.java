/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「本周期没有判定结果」不等于「已恢复」。
 *
 * <h2>被锁住的 bug</h2>
 *
 * <p>两条各自正确的约定撞在一起：</p>
 * <ol>
 *   <li>采集层在<b>哨兵路径</b>不能写 0 —— {@code msptAvg <= 0}（TPS 反推路径没有 MSPT）、
 *       {@code processCpuLoad < 0}（Windows 上进程 CPU 不采）。理由正当：给没测的维度写 0
 *       等于宣称它健康，会同时污染 {@code /metrics} 的 {@code perfscope_alert} 与 Grafana 的
 *       {@code absent()} 判定。</li>
 *   <li>告警层把「没有结论」当作一个干净周期。理由也正当：大多数键每周期必有结论。</li>
 * </ol>
 *
 * <p>相撞的结果是<b>采集坏了，告警却宣布「已恢复正常」</b>——用户收到一条与事实相反的推送，
 * 且此后该告警从活跃列表消失，真正的故障再也不会被提醒。</p>
 *
 * <h2>修法：三态而非两态</h2>
 *
 * <p>「没判」用显式的 {@link MetricsSnapshot#ALERT_LEVEL_NO_VERDICT}（-1）表达，
 * <b>不</b>用「键缺席」——因为缺席还有第二种含义：该维度本周期<b>不适用</b>
 * （世界被卸载后 {@code chunks:world:<名>} 就不再出现），那种情况告警<b>应当</b>正常恢复。
 * 把缺席一律读成"没判"会让这类告警永久卡在 unknown 里。</p>
 *
 * <h2>用例分工</h2>
 *
 * <ul>
 *   <li>① 显式"没判" + 模块不在 {@code unavailable} → <b>不得</b>恢复（本次修复的靶点）；</li>
 *   <li>② 显式"没判"持续超过 unknown-after-minutes → 转 unknown，且不被静默删除；</li>
 *   <li>③ 键缺席（本周期不适用，如世界已卸载）→ <b>必须</b>照常恢复（防止修成永久卡住）；</li>
 *   <li>④ 键写 0（判定过且健康）→ <b>必须</b>恢复（防止修得过头）；</li>
 *   <li>⑤ 键写 2 但消息缺席（消息被去重压制）→ 不得恢复（v1.4 已修的保险）；</li>
 *   <li>⑥ 模块在 {@code unavailable} → 走 unknown（既有对照）。</li>
 * </ul>
 */
class MissingLevelKeyIsNotRecoveryTest {

    /** 与产品默认值 {@code alerts.recovery-debounce-cycles: 3} 对齐——三周期才宣布恢复。 */
    private static final int RECOVERY_CYCLES = 3;

    /** 被测告警键：走哨兵路径的是 {@code mspt:global}（{@code msptAvg = -1} 时不写键）。 */
    private static final String KEY = "mspt:global";
    private static final String MODULE = MetricsSnapshot.MODULE_MSPT;

    @BeforeAll
    static void initMessages() throws Exception {
        AlertServiceStateMachineFixture.initMessages();
    }

    private static AlertServiceStateMachineFixture fixture() throws Exception {
        return AlertServiceStateMachineFixture.builder()
            .cooldownSeconds(0)        // 冷却与被测行为无关，关掉免得干扰推送计数
            .debounceCycles(1)
            .flapWindowSeconds(0)
            .refireSeconds(0)
            .recoveryCycles(RECOVERY_CYCLES)
            .build();
    }

    /** 造一份"本周期什么都没判定"的快照：{@code alertLevels} 空、{@code unavailable} 可为空。 */
    private static MetricsSnapshot undecided(long timestamp, String... unavailableModules) {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.timestamp = timestamp;
        for (String module : unavailableModules) {
            snap.unavailable.add(module);
        }
        return snap;
    }

    /**
     * 造一份"采集层显式声明该维度本周期不可判定"的快照。
     *
     * <p>这是哨兵路径的诚实表达：{@code msptAvg = -1} / {@code processCpuLoad = -1}，
     * 采集层据此写 {@link MetricsSnapshot#ALERT_LEVEL_NO_VERDICT}。</p>
     */
    private static MetricsSnapshot noVerdict(AlertServiceStateMachineFixture f, String key) {
        return f.noVerdictSnapshot(f.nextTimestamp(), key);
    }

    /** 该 key 是否已进入 unknown 分支（{@code moduleDownSince} 有记录即说明"没当干净周期"）。 */
    @SuppressWarnings("unchecked")
    private static boolean enteredUnknownBranch(AlertServiceStateMachineFixture f, String key) {
        Map<String, Long> downSince =
            (Map<String, Long>) TestObjects.getField(AlertService.class, f.service(), "moduleDownSince");
        return downSince.containsKey(key);
    }

    // ====================================================================================
    // ① 靶点：哨兵路径不写键，模块却是"健康"的
    // ====================================================================================

    /**
     * <b>本次修复的核心用例。</b>
     *
     * <p>场景就是线上那一条：MSPT 走 TPS 反推且反推不出来（{@code msptAvg = -1}），
     * 或者 Windows 上 {@code processCpuLoad = -1}。采集层显式写下「本周期没有判定结果」，
     * 而模块本身<b>不</b>在 {@code unavailable} 里（采集没抛异常，只是这个维度不可判定）。</p>
     *
     * <p>修复前的行为：三个周期后记一次 {@code resolve}、推一条「已恢复正常」、把告警移出活跃列表。
     * 用户看到的是"问题没了"，而真相是"我们不再看这个维度了"。</p>
     */
    @Test
    void noVerdictMustNotAnnounceRecovery() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        f.feed(KEY, Alert.LEVEL_CRITICAL, 80.0);
        assertEquals(1, f.activeCount(), "前置条件：告警必须已进入活跃列表");

        for (int cycle = 0; cycle < RECOVERY_CYCLES; cycle++) {
            // 采集层显式声明"没判"，模块不在 unavailable（采集没失败，只是维度不可判定）
            f.feedSnapshot(noVerdict(f, KEY));
        }

        assertEquals(0L, f.eventCount("resolve"),
            "a key reported as ALERT_LEVEL_NO_VERDICT because the collector deliberately did not judge it "
                + "(MSPT/CPU sentinel paths) must NOT be reported as recovered: users would receive a "
                + "\"recovered\" notification while nothing was actually measured, and the alert would "
                + "leave the active list so the real problem stops being tracked");
        assertEquals(1, f.activeCount(),
            "the alert must stay active (or become suspended) while the dimension is unjudgeable; "
                + "dropping it silently hides a problem that was never disproved");
        assertTrue(enteredUnknownBranch(f, KEY),
            "a missing verdict must take the same \"unknown\" path as an unavailable module "
                + "(moduleDownSince recorded), not the clean-cycle counter");
    }

    /** 显式"没判"持续超过 {@code alerts.unknown-after-minutes} → 转入 unknown（suspended），仍然不报恢复。 */
    @Test
    void persistentlyNoVerdictEventuallyGoesSuspendedNotResolved() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        f.feed(KEY, Alert.LEVEL_CRITICAL, 80.0);

        // 伪造"这个 key 从 10 分钟前就没判定结果了"，默认 unknown-after-minutes=5
        @SuppressWarnings("unchecked")
        Map<String, Long> downSince =
            (Map<String, Long>) TestObjects.getField(AlertService.class, f.service(), "moduleDownSince");
        downSince.put(KEY, System.currentTimeMillis() - 10 * 60_000L);

        f.feedSnapshot(noVerdict(f, KEY));

        assertEquals(0L, f.eventCount("resolve"),
            "an unjudgeable dimension must never produce a \"recovered\" event, even after a long "
                + "silence - the alert must degrade to unknown (suspended) instead");
        assertEquals(1L, f.eventCount("suspend"),
            "after unknown-after-minutes the alert must move to the unknown (suspended) state, "
                + "which is the honest rendering of \"we cannot tell any more\"");
        assertEquals(0, f.activeCount(),
            "a suspended alert is no longer counted as active (it is counted as unknown instead)");
        assertEquals(1, f.suspendedCount(), "the alert must remain visible as unknown, not vanish");
    }

    /**
     * 已经处于 unknown 的告警，在「仍然没有判定结果」时不得被静默删除。
     *
     * <p>这条与 {@link #persistentlyNoVerdictEventuallyGoesSuspendedNotResolved()} 是后半段：
     * 那一条锁住"进 unknown"，这一条锁住"留在 unknown"。少了后者，一个已转 unknown 的告警会在
     * 下一个无结论周期被当成"静默解决"删掉——用户界面上的 unknown 行消失，
     * 而采集层从头到尾没有给出过任何结论。</p>
     */
    @Test
    void suspendedAlertSurvivesCyclesWithoutAVerdict() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        f.feed(KEY, Alert.LEVEL_CRITICAL, 80.0);

        @SuppressWarnings("unchecked")
        Map<String, Long> downSince =
            (Map<String, Long>) TestObjects.getField(AlertService.class, f.service(), "moduleDownSince");
        downSince.put(KEY, System.currentTimeMillis() - 10 * 60_000L);
        f.feedSnapshot(noVerdict(f, KEY));   // 转入 unknown
        assertEquals(1, f.suspendedCount(), "前置条件：告警必须已转入 unknown");

        for (int cycle = 0; cycle < RECOVERY_CYCLES; cycle++) {
            f.feedSnapshot(noVerdict(f, KEY));   // 仍然没有判定结果
        }

        assertEquals(1, f.suspendedCount(),
            "an unknown alert must stay visible as unknown while the collector keeps producing no "
                + "verdict; silently deleting it is the same lie as announcing a recovery, only quieter");
        assertEquals(0L, f.eventCount("resolved_by_disable"),
            "silent resolution requires a verdict (level 0) or a key that no longer applies. Without "
                + "either, \"the problem is gone\" is not something we know, so it must not be recorded");
    }

    // ====================================================================================
    // ③ 反向保护：键缺席 = 本周期不适用（如世界已卸载）→ 必须照常恢复
    // ====================================================================================

    /**
     * 不能把"没判"的修法扩大成"键缺席也算没判"。
     *
     * <p>{@code chunks:world:<名>} / {@code entities:world:<名>} 的键来自当前已加载的世界。
     * 世界被卸载、或 {@code worlds:} 覆盖被移除之后，该键就不再出现——采集层没写它，
     * 不是"没测"，而是"这个维度本周期不适用"。此时若也走 unknown，告警会<b>永久</b>卡在
     * unknown 里，永远不再消失。原有的"键缺席即恢复"语义必须保住。</p>
     */
    @Test
    void absentKeyMeansNotApplicableAndStillRecovers() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        f.feed(KEY, Alert.LEVEL_CRITICAL, 80.0);

        for (int cycle = 0; cycle < RECOVERY_CYCLES; cycle++) {
            // 键缺席、模块不在 unavailable：世界没了 / 覆盖被移除 → 该维度不适用
            f.feedSnapshot(undecided(f.nextTimestamp()));
        }

        assertEquals(1L, f.eventCount("resolve"),
            "a key that is simply absent (the world was unloaded, so the per-world dimension no longer "
                + "applies) must still recover; treating absence as \"no verdict\" would pin such alerts "
                + "in unknown forever");
        assertEquals(0, f.activeCount(), "the alert must leave the active list once the dimension stops applying");
        assertTrue(!enteredUnknownBranch(f, KEY),
            "absence means \"not applicable\", not \"still measuring\" - it must not create a module-down "
                + "timestamp");
    }

    // ====================================================================================
    // ② 对照：键缺席 + 模块在 unavailable
    // ====================================================================================

    /**
     * 已有的正确路径，修复前后都必须绿。
     *
     * <p>{@code unavailable} 语义明确：这个模块本轮采不到。此时既不算恢复也不推恢复通知，
     * 而是记下不可用起点、超时后转 unknown。</p>
     */
    @Test
    void absentKeyWithUnavailableModuleIsUnknownNotRecovery() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        f.feed(KEY, Alert.LEVEL_CRITICAL, 80.0);

        for (int cycle = 0; cycle < RECOVERY_CYCLES; cycle++) {
            f.feedSnapshot(undecided(f.nextTimestamp(), MODULE));
        }

        assertEquals(0L, f.eventCount("resolve"),
            "a module in unavailable must never be announced as recovered - \"the message disappeared\" "
                + "is not \"the problem is gone\"");
        assertEquals(1, f.activeCount(),
            "the alert must stay active while its module is unavailable (it is not disproved)");
        assertTrue(enteredUnknownBranch(f, KEY),
            "the unavailable branch must record the module-down timestamp so the alert can later "
                + "degrade to unknown after unknown-after-minutes");
    }

    // ====================================================================================
    // ③ 反向保护：判定过且健康（写 0）必须照常恢复
    // ====================================================================================

    /**
     * 不能把修复做得过头。
     *
     * <p>{@code 0} 是<b>判定结果</b>（"测了，健康"），与"没测"是两件事。若为了堵住误报恢复
     * 而连 0 也不算干净周期，真实恢复将永远不发生——告警永久挂在活跃列表，
     * 这比误报一次恢复更糟（服主会失去对告警列表的信任）。</p>
     */
    @Test
    void judgedHealthyKeyStillRecovers() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        f.feed(KEY, Alert.LEVEL_CRITICAL, 80.0);

        for (int cycle = 0; cycle < RECOVERY_CYCLES; cycle++) {
            f.feedClean(f.nextTimestamp(), KEY);   // alertLevels.put(KEY, 0)：判定过，健康
        }

        assertEquals(1L, f.eventCount("resolve"),
            "a key explicitly judged healthy (level 0) must still recover after recovery-debounce-cycles: "
                + "refusing to recover valid keys would leave alerts stuck open forever");
        assertEquals(0, f.activeCount(), "the recovered alert must leave the active list");
        assertTrue(!enteredUnknownBranch(f, KEY),
            "a judged-healthy key must NOT take the unknown path - it has a verdict, and that verdict "
                + "is \"clean\"");
    }

    // ====================================================================================
    // ④ 对照：消息被去重压制（键写 2，但没有 alert 对象）
    // ====================================================================================

    /**
     * v1.4 已经修过一次的保险，修复不得回退它。
     *
     * <p>实体去重让"全服合计"那条消息不再发出，但级别照常写进 {@code alertLevels}。
     * 这种情况必须继续被识别为"问题还在"。</p>
     */
    @Test
    void missingMessageWithPositiveLevelIsNotRecovery() throws Exception {
        AlertServiceStateMachineFixture f = fixture();
        f.feed(KEY, Alert.LEVEL_CRITICAL, 80.0);

        for (int cycle = 0; cycle < RECOVERY_CYCLES; cycle++) {
            MetricsSnapshot snap = undecided(f.nextTimestamp());
            snap.alertLevels.put(KEY, 2);   // 判定过、仍然越界，只是消息被更具体的同级告警覆盖了
            f.feedSnapshot(snap);
        }

        assertEquals(0L, f.eventCount("resolve"),
            "a key whose message was suppressed by de-duplication but whose level is still positive "
                + "must stay active - announcing recovery there is a statement contrary to the facts");
        assertEquals(1, f.activeCount(), "the alert must still be tracked as active");
        assertTrue(!enteredUnknownBranch(f, KEY),
            "a positive level is a verdict (\"still broken\"), not an unknown state - it must not be "
                + "shadowed by the unknown branch");
    }
}
