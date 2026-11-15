/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.compat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MsptCapabilityState} 的 7 条不变量。
 *
 * <h2>为什么这些断言值得写</h2>
 * 这段判定决定 {@code PAPER_MSPT_AVAILABLE} / {@code PAPER_AVG_TICK_AVAILABLE} 两个能力位。
 * 破坏任何一条不变量都<b>不会崩溃、不会抛异常</b>，只会让能力位或控制台文案变得不诚实：
 * <ul>
 *   <li>只降不升被破坏 → 探针把 {@code false} 改回 {@code true}，于是在一个
 *       {@code getTickTimes()} 恒返回全零的服务端上，MSPT 列又显示"精确采集"的假数据；</li>
 *   <li>两次确认被丢掉 → 健康 Paper 上恰好一次空窗口（刚 reload 完）就被永久降级，
 *       {@code /perfscope status} 从此显示"平台限制"，直到重启；</li>
 *   <li>两级降级被并成"双降" → 明明 {@code getAverageTickTime()} 可用，平均值也不显示了；</li>
 *   <li>两级都空不记原因键 → 报告层把空原因当文案（HTML 侧直接 NPE）；</li>
 *   <li>定案后重复采样 → reload 时多打一条降级日志、多挂 5 秒任务；</li>
 *   <li>{@code wasAvailable} 门控被去掉/改动 → 每次启动都在控制台喊"已降级"，
 *       而它本来就不可用，服主会去查一个不存在的故障；</li>
 *   <li>方法缺失判定被绕过 → 在压根没有 MSPT API 的服务端上宣称"精确"。</li>
 * </ul>
 *
 * <h2>为什么这个测试以前不存在</h2>
 * 判定逻辑原本内联在 {@code ServerCompat} 里，而它的 {@code <clinit>} 要
 * {@code Bukkit.getBukkitVersion()}（解引用 {@code Bukkit.server}，测试 JVM 里是 null），
 * 必然 {@code ExceptionInInitializerError}。抽出纯状态机之后，本类不需要任何 Bukkit 造法。
 *
 * <p>断言消息写成"破坏后会怎样"，是给未来维护者看的：改坏了哪条，看消息就知道用户会受什么影响。</p>
 */
class MsptCapabilityStateTest {

    // ====================================================================================
    // 1. 只降不升
    // ====================================================================================

    @Test
    void capabilityBitsNeverRise() {
        // 性质断言：遍历所有输入组合 × 所有"已定案前"的初始状态，能力位只能不变或下降。
        boolean[] bools = {true, false};
        List<String> violations = new ArrayList<>();
        for (boolean initMspt : bools) {
            for (boolean initAvg : bools) {
                for (boolean folia : bools) {
                    for (boolean methodPresent : bools) {
                        for (boolean avgMethodPresent : bools) {
                            for (boolean sampleOk : bools) {
                                for (boolean avgOk : bools) {
                                    // 跑两次：第一次可能只是累计 streak，第二次才定案
                                    MsptCapabilityState s = MsptCapabilityState.initial(
                                        initMspt, initAvg, reasonOf(initMspt, initAvg));
                                    for (int round = 0; round < MsptCapabilityState.ZERO_SAMPLE_CONFIRMATIONS; round++) {
                                        MsptCapabilityState next = s.transition(
                                            folia, methodPresent, avgMethodPresent, sampleOk, avgOk).state;
                                        String where = describe(initMspt, initAvg, folia, methodPresent,
                                            avgMethodPresent, sampleOk, avgOk, round);
                                        if (next.msptAvailable && !s.msptAvailable) {
                                            violations.add("PAPER_MSPT_AVAILABLE rose false->true @ " + where);
                                        }
                                        if (next.avgTickAvailable && !s.avgTickAvailable) {
                                            violations.add("PAPER_AVG_TICK_AVAILABLE rose false->true @ " + where);
                                        }
                                        s = next;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Probe must only ever lower capability bits (a false->true transition would re-claim "
                + "'exact MSPT' on a server that was already proven not to populate tick times, "
                + "and /perfscope status would show fake precision). Violations: " + violations);
    }

    @Test
    void avgBitOnlyDropsInFoliaOrWhenBothLevelsEmpty() {
        // 第二个性质：二级能力位只有两条路径可以从 true 变 false——
        // Folia 分支，或"两级都空"定案。其余路径（含 DEGRADED_TO_AVG）必须保留它。
        boolean[] bools = {true, false};
        List<String> violations = new ArrayList<>();
        for (boolean initMspt : bools) {
            for (boolean methodPresent : bools) {
                for (boolean avgMethodPresent : bools) {
                    for (boolean sampleOk : bools) {
                        for (boolean avgOk : bools) {
                            MsptCapabilityState s = MsptCapabilityState.initial(
                                initMspt, true, null);
                            for (int round = 0; round <= MsptCapabilityState.ZERO_SAMPLE_CONFIRMATIONS; round++) {
                                MsptCapabilityState.Result r = s.transition(
                                    false, methodPresent, avgMethodPresent, sampleOk, avgOk);
                                if (s.avgTickAvailable && !r.state.avgTickAvailable
                                    && r.event != MsptCapabilityState.Event.MARKED_UNAVAILABLE) {
                                    violations.add("AVG bit dropped on " + r.event + " @ "
                                        + describe(initMspt, true, false, methodPresent,
                                            avgMethodPresent, sampleOk, avgOk, round));
                                }
                                s = r.state;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "PAPER_AVG_TICK_AVAILABLE may only drop via the Folia path or the 'both levels empty' "
                + "verdict. Dropping it on the degraded-to-avg path would hide the average MSPT that "
                + "this server still reports correctly. Violations: " + violations);
    }

    // ====================================================================================
    // 2. 两次确认
    // ====================================================================================

    @Test
    void firstZeroSampleDoesNotDegrade() {
        MsptCapabilityState s = MsptCapabilityState.initial(true, true, null);
        MsptCapabilityState.Result r = s.transition(false, true, true, false, false);

        assertFalse(r.state.probed,
            "After a single empty sample the probe must stay undecided: one zero window can simply be "
                + "a collection interval that has not filled yet, and deciding here would permanently "
                + "mark a healthy Paper server unavailable on the first reload.");
        assertTrue(r.state.msptAvailable,
            "MSPT capability must not drop on the first empty sample: a single zero window is a normal "
                + "transient right after enable, and dropping here would silently disable MSPT for the "
                + "rest of the uptime.");
        assertTrue(r.state.avgTickAvailable,
            "AVG capability must not drop on the first empty sample: same transient as above.");
        assertEquals(1, r.state.zeroSampleStreak,
            "The streak must count the first empty sample so the next probe can confirm it.");
        assertEquals(MsptCapabilityState.Event.NONE, r.event,
            "The first empty sample must not log anything: it is not a verdict yet, and a log line here "
                + "would make every healthy server print a degradation notice.");
    }

    @Test
    void secondZeroSampleDecides() {
        MsptCapabilityState s = MsptCapabilityState.initial(true, true, null)
            .transition(false, true, true, false, false).state;
        MsptCapabilityState.Result r = s.transition(false, true, true, false, false);

        assertTrue(r.state.probed,
            "The second consecutive empty sample must decide: leaving it undecided means the probe "
                + "never converges and keeps sampling forever (one extra log + 5s task per retry).");
        assertFalse(r.state.msptAvailable,
            "After ZERO_SAMPLE_CONFIRMATIONS empty samples MSPT must be marked unavailable; otherwise the "
                + "report keeps rendering all-zero MSPT as exact measurements.");
        assertFalse(r.state.avgTickAvailable,
            "After ZERO_SAMPLE_CONFIRMATIONS empty samples on both levels AVG must drop too; otherwise "
                + "'msptTrustworthy' stays true and the collector keeps reading zeros.");
        assertEquals(MsptCapabilityState.Event.MARKED_UNAVAILABLE, r.event,
            "The confirmed verdict must be MARKED_UNAVAILABLE so the console explains why MSPT went away.");
    }

    // ====================================================================================
    // 3. 两级降级
    // ====================================================================================

    @Test
    void primaryEmptyButAvgAliveDegradesOnlyOneLevel() {
        MsptCapabilityState s = MsptCapabilityState.initial(true, true, null);
        // 两次全零一级 + 二级有值
        s = s.transition(false, true, true, false, true).state;
        assertTrue(s.msptAvailable, "First empty primary sample must not degrade yet (see two-sample rule).");
        MsptCapabilityState.Result r = s.transition(false, true, true, false, true);

        assertEquals(MsptCapabilityState.Event.DEGRADED_TO_AVG, r.event,
            "Primary empty + secondary alive must be DEGRADED_TO_AVG (not MARKED_UNAVAILABLE): otherwise "
                + "the console claims MSPT is gone while getAverageTickTime still returns real data.");
        assertFalse(r.state.msptAvailable,
            "MSPT (exact, from getTickTimes) must drop when the primary source is provably empty.");
        assertTrue(r.state.avgTickAvailable,
            "AVG must stay true: getAverageTickTime is a real (non-estimated) API, so the server still "
                + "reports a trustworthy mean. Dropping it here would hide the average MSPT for no reason.");
        assertNull(r.state.reasonKey,
            "reasonKey must be null on the degraded-to-avg path: the capability is still available, and a "
                + "non-null reason makes the report claim 'MSPT unavailable' while it is being collected.");
        assertTrue(r.state.probed,
            "Degraded-to-avg is a verdict: without probed=true the probe samples once more (extra log line "
                + "+ extra 5s retry) on a decision that cannot change.");
    }

    // ====================================================================================
    // 4. 两级都空
    // ====================================================================================

    @Test
    void bothLevelsEmptyMarksUnavailableWithReason() {
        MsptCapabilityState s = MsptCapabilityState.initial(true, true, null);
        s = s.transition(false, true, true, false, false).state;
        MsptCapabilityState.Result r = s.transition(false, true, true, false, false);

        assertFalse(r.state.msptAvailable, "Both levels empty must clear the MSPT capability bit.");
        assertFalse(r.state.avgTickAvailable, "Both levels empty must clear the AVG capability bit as well.");
        assertEquals("compat.mspt.tick-times-empty", r.state.reasonKey,
            "Both-empty must record the tick-times-empty reason key: leaving it null (or a stale key) makes "
                + "the report render an empty reason, which the HTML renderer dereferences into an NPE.");
        assertTrue(r.state.probed,
            "Both-empty is a final verdict; without probed=true the probe resamples and re-logs forever.");
        assertEquals(MsptCapabilityState.Event.MARKED_UNAVAILABLE, r.event,
            "Both-empty must emit MARKED_UNAVAILABLE so the operator learns the server does not populate "
                + "tick timings (instead of hunting a plugin bug).");
        assertTrue(r.announce,
            "The verdict must be announced when the bits were previously true - that is exactly the "
                + "misjudged case operators need to hear about.");
    }

    // ====================================================================================
    // 5. 已定案不重复采样（幂等）
    // ====================================================================================

    @Test
    void decidedStateIsIdempotent() {
        boolean[] bools = {true, false};
        for (boolean mspt : bools) {
            for (boolean avg : bools) {
                for (boolean folia : bools) {
                    for (boolean methodPresent : bools) {
                        for (boolean avgMethodPresent : bools) {
                            for (boolean sampleOk : bools) {
                                for (boolean avgOk : bools) {
                                    MsptCapabilityState decided = MsptCapabilityState.current(
                                        mspt, avg, "some.reason", true, 2);
                                    MsptCapabilityState.Result r = decided.transition(
                                        folia, methodPresent, avgMethodPresent, sampleOk, avgOk);
                                    assertStateEquals(decided, r.state,
                                        "A decided state must be returned unchanged (got " + r.state + "). "
                                            + "Re-running the verdict would re-log the degradation and "
                                            + "re-announce it on every reload.");
                                    assertEquals(MsptCapabilityState.Event.NONE, r.event,
                                        "A decided state must emit NONE: emitting a real event would print a "
                                            + "second, duplicate console line for the same verdict.");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void verifiedStateWithZeroStreakStaysVerified() {
        // 一级成功定案后 streak 必须是 0（0 是"已确认"的合法形态）。
        // 单测这里的原因：上面的幂等矩阵用 streak=2 起步，恰好避开了"成功定案后 streak=0"
        // 这个组合——若转移函数把 probed 与 streak 绑在一起，矩阵看不到。
        MsptCapabilityState confirmed = MsptCapabilityState.initial(true, true, null)
            .transition(false, true, true, true, true).state;
        assertEquals(0, confirmed.zeroSampleStreak,
            "Setup: a confirmed state carries a zero streak.");
        MsptCapabilityState.Result again = confirmed.transition(false, true, true, false, false);
        assertStateEquals(confirmed, again.state,
            "A confirmed state with a zero streak must still be terminal: if the early return keys off "
                + "'streak > 0' instead of 'probed', a confirmed healthy server gets re-judged on the next "
                + "retry and can be permanently degraded by a single empty window.");
        assertEquals(MsptCapabilityState.Event.NONE, again.event,
            "A terminal state must not re-emit an event (duplicate console line).");
    }

    // ====================================================================================
    // 6. wasAvailable 门控（只有原本可用时才解释）
    // ====================================================================================

    @Test
    void foliaExplainsOnlyWhenItWasPreviouslyAvailable() {
        MsptCapabilityState.Result wasAvailable = MsptCapabilityState.initial(true, false, null)
            .transition(true, true, true, true, true);
        assertEquals(MsptCapabilityState.Event.FOLIA_EXPLAINED, wasAvailable.event,
            "Folia with previously-true capability must explain the downgrade: that is the misjudged case "
                + "(the plugin claimed 'exact MSPT' on a server that never had global tick times).");
        assertTrue(wasAvailable.announce,
            "FOLIA_EXPLAINED must be announced - silence here leaves the wrong claim uncorrected.");

        MsptCapabilityState.Result wasUnavailable = MsptCapabilityState.initial(false, false,
            "compat.mspt.folia-no-global-tick").transition(true, true, true, true, true);
        assertEquals(MsptCapabilityState.Event.FOLIA_QUIET, wasUnavailable.event,
            "Folia that was already known-unavailable must stay quiet apart from the scope note: shouting "
                + "'marked unavailable' on a server that never claimed it is pure noise.");
        assertTrue(wasUnavailable.announce,
            "FOLIA_QUIET still prints one line (reason + TPS scope note); dropping it loses the only "
                + "explanation of why Folia TPS figures are global-region local.");
    }

    @Test
    void degradationIsNotAnnouncedWhenNothingWasAvailable() {
        // 原本两级都不可用 → 定案不可用，不该再喊"已降级"。
        MsptCapabilityState.Result r = MsptCapabilityState.initial(false, false, "compat.mspt.no-api")
            .transition(false, true, true, false, false).state
            .transition(false, true, true, false, false);
        assertEquals(MsptCapabilityState.Event.MARKED_UNAVAILABLE, r.event,
            "The verdict event must still be MARKED_UNAVAILABLE so the reason key gets written.");
        assertFalse(r.announce,
            "Nothing was available before the verdict, so the console must stay silent: announcing a "
                + "degradation that never happened sends operators looking for a fault that is not there.");
        assertEquals("compat.mspt.tick-times-empty", r.state.reasonKey,
            "The reason key must be written even when nothing is announced - the report still needs it.");
    }

    @Test
    void degradedToAvgIsAnnouncedOnlyWhenThePrimaryBitWasTrue() {
        // 静态探测就说 getTickTimes 不存在（一级位 false），只有二级位 true。
        // 此时"一级降级"没有降级可言——一级本来就没被宣称过，喊 degraded-to-avg 是无中生有的告警。
        // 这条专锁靶点 3：wasPrimaryAvailable（只看一级）与 wasAvailable（两级口径）语义不同，
        // 一旦被合并成两级口径，这里就会从静默变成打日志。
        MsptCapabilityState s = MsptCapabilityState.initial(false, true, null);
        s = s.transition(false, true, true, false, true).state;
        MsptCapabilityState.Result r = s.transition(false, true, true, false, true);

        assertEquals(MsptCapabilityState.Event.DEGRADED_TO_AVG, r.event,
            "Primary empty + secondary alive is still the degraded event.");
        assertFalse(r.announce,
            "The primary capability bit was false from the start (getTickTimes absent), so no claim is "
                + "being withdrawn: printing 'MSPT degraded to getAverageTickTime' here announces a "
                + "change that never happened and sends the operator to check a healthy server.");
        assertTrue(r.state.avgTickAvailable,
            "AVG must survive the degraded path regardless of the announce gate.");
    }

    @Test
    void successResetsTheZeroStreak() {
        // 先吃一次空窗口（streak=1），再成功一次：streak 必须归零。
        MsptCapabilityState afterOneEmpty = MsptCapabilityState.initial(true, true, null)
            .transition(false, true, true, false, false).state;
        assertEquals(1, afterOneEmpty.zeroSampleStreak,
            "Setup: the first empty window must leave a streak of 1.");

        MsptCapabilityState.Result r = afterOneEmpty.transition(false, true, true, true, false);

        assertEquals(MsptCapabilityState.Event.CONFIRMED_EXACT, r.event,
            "A positive primary sample confirms the capability.");
        assertEquals(0, r.state.zeroSampleStreak,
            "Success must reset the zero streak: keeping the old count means a later single empty window "
                + "(very common right after a reload) immediately reaches ZERO_SAMPLE_CONFIRMATIONS and "
                + "permanently degrades a healthy server.");
    }

    // ====================================================================================
    // 7. 方法缺失
    // ====================================================================================

    @Test
    void missingTickTimesMethodIsBadEvenWithAGoodSample() {
        MsptCapabilityState s = MsptCapabilityState.initial(true, true, null);
        s = s.transition(false, false, true, true, true).state;   // 故意传"样本 OK"
        MsptCapabilityState.Result r = s.transition(false, false, true, true, true);

        assertEquals(MsptCapabilityState.Event.DEGRADED_TO_AVG, r.event,
            "When getTickTimes() does not exist the primary level must be treated as unavailable no matter "
                + "what the caller passed: trusting a 'good sample' from a method that is not there means "
                + "the plugin claims exact MSPT on a server without the API.");
        assertFalse(r.state.msptAvailable,
            "MSPT must drop when the primary method is absent, even if primarySampleOk was true.");
        assertTrue(r.state.avgTickAvailable,
            "AVG must survive: the secondary method exists and returned a value.");
    }

    @Test
    void missingAvgTickMethodCountsAsBad() {
        MsptCapabilityState s = MsptCapabilityState.initial(true, true, null);
        s = s.transition(false, true, false, false, true).state;
        MsptCapabilityState.Result r = s.transition(false, true, false, false, true);

        assertEquals(MsptCapabilityState.Event.MARKED_UNAVAILABLE, r.event,
            "A missing getAverageTickTime() must count as an empty secondary level: reporting a positive "
                + "average from a method that does not exist would be fabricated data.");
        assertFalse(r.state.avgTickAvailable,
            "AVG must drop when the secondary method is absent, even if avgTickOk was true.");
    }

    // ====================================================================================
    // 8. 一级成功路径
    // ====================================================================================

    @Test
    void primarySuccessConfirmsWithoutTouchingCapabilityBits() {
        MsptCapabilityState.Result r = MsptCapabilityState.initial(false, false, "compat.mspt.no-api")
            .transition(false, true, true, true, false);

        assertEquals(MsptCapabilityState.Event.CONFIRMED_EXACT, r.event,
            "A healthy primary sample must be CONFIRMED_EXACT.");
        assertTrue(r.state.probed,
            "A healthy primary sample is a verdict: leaving probed false means the probe keeps sampling a "
                + "server it already confirmed, wasting a retry every 5 seconds.");
        assertEquals(0, r.state.zeroSampleStreak,
            "The zero streak must reset on success so a later transient empty window starts counting from "
                + "zero instead of deciding immediately.");
        assertNull(r.state.reasonKey,
            "reasonKey must be cleared on success: a stale 'no-api'/'ticks-empty' key makes the report "
                + "claim MSPT is unavailable while it is being collected correctly.");
        assertFalse(r.state.msptAvailable,
            "The success path must NOT write the capability bits (only-degrade invariant): flipping "
                + "false->true here would re-claim exact MSPT on a server whose static probe already "
                + "said the API is missing.");
        assertFalse(r.state.avgTickAvailable,
            "The success path must NOT enable AVG either: the original implementation only clears the "
                + "reason key here, and enabling a level that was never measured would fabricate data.");
        assertFalse(r.announce,
            "The success path logs nothing (the original had no line here); announcing would add console "
                + "noise on every healthy startup.");
    }

    // ====================================================================================
    // 9. hasPositiveTickSample
    // ====================================================================================

    @Test
    void hasPositiveTickSampleAcceptsOnlyPositiveValues() {
        assertFalse(MsptCapabilityState.hasPositiveTickSample(null),
            "null must count as 'no data', not as a crash and not as data: a reflection failure returns "
                + "null and must degrade rather than claim a sample exists.");
        assertFalse(MsptCapabilityState.hasPositiveTickSample(new long[0]),
            "An empty array is 'no data': getTickTimes() on Folia returns exactly this shape.");
        assertFalse(MsptCapabilityState.hasPositiveTickSample(new long[]{0L, 0L, 0L}),
            "All-zero is 'no data': this is the Folia signature (the method exists but nothing fills it), "
                + "and treating it as data is the exact false-precision bug the probe exists to catch.");
        assertTrue(MsptCapabilityState.hasPositiveTickSample(new long[]{0L, 0L, 5L}),
            "A single positive entry is real data anywhere in the window; requiring more would degrade "
                + "healthy servers on idle windows.");
        assertFalse(MsptCapabilityState.hasPositiveTickSample(new long[]{-1L, -5L}),
            "Negative tick timings are impossible; treating them as data would let a broken branch claim "
                + "exact MSPT from garbage.");
    }

    // ====================================================================================
    // 辅助
    // ====================================================================================

    /** 初始原因键的选择规则与 {@code ServerCompat.<clinit>} 一致（仅在测试里简化表达）。 */
    private static String reasonOf(boolean mspt, boolean avg) {
        return (mspt || avg) ? null : "compat.mspt.no-api";
    }

    private static String describe(boolean initMspt, boolean initAvg, boolean folia,
                                   boolean methodPresent, boolean avgMethodPresent,
                                   boolean sampleOk, boolean avgOk, int round) {
        return "init{mspt=" + initMspt + ",avg=" + initAvg + "} in{folia=" + folia
            + ",tickMethod=" + methodPresent + ",avgMethod=" + avgMethodPresent
            + ",primaryOk=" + sampleOk + ",avgOk=" + avgOk + "} round=" + round;
    }

    /** 让 {@code assertEquals(state, state)} 得到可读的失败信息（字段逐个比，不是比引用）。 */
    private static void assertStateEquals(MsptCapabilityState expected, MsptCapabilityState actual,
                                          String message) {
        assertEquals(expected.msptAvailable, actual.msptAvailable, message);
        assertEquals(expected.avgTickAvailable, actual.avgTickAvailable, message);
        assertEquals(expected.reasonKey, actual.reasonKey, message);
        assertEquals(expected.probed, actual.probed, message);
        assertEquals(expected.zeroSampleStreak, actual.zeroSampleStreak, message);
    }
}
