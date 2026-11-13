/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.diagnose;

import cn.perfscope.alert.AlertServiceStateMachineFixture;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.history.HistorySample;
import cn.perfscope.history.HistoryStore;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.util.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断规则<b>本体</b>：{@link CoreRules.PlayerCapacityRule}（DIAG-PLAYER-CAP）。
 *
 * <p>准纯函数：判据本身只读 {@link MetricsSnapshot}，但"是不是活动峰值"要看 15 分钟历史窗口，
 * 所以要喂历史。喂法与 {@code MemoryLeakRuleTest} 相同——{@code Unsafe.allocateInstance}
 * 绕过构造器 + 反射直写私有环 {@code ring}，绕开 {@code record(snap)} 的采样节流。</p>
 *
 * <h2>本类真正要锁住的东西：0 人在线是真实低谷</h2>
 *
 * <p>9A-8 修掉的缺陷是"跳过 {@code playerCount == 0} 的样本"。它会造成的假结论很具体：
 * 凌晨 14 个 0 人样本 + 早上 9 人上线，窗口最低点被算成 9 人，
 * 于是规则看着一片 0 说"近 15 分钟最低也有 9%"——把一段空服判成持续满员。
 * {@link #zeroPlayerSamplesCountAsTheWindowLow()} 用同样的形状把它钉死。</p>
 */
class PlayerCapacityRuleTest {

    private static final int WARN_THRESHOLD = 90;
    private static final int CRITICAL_THRESHOLD = 98;
    private static final int MAX_PLAYERS = 100;

    @BeforeAll
    static void initMessages() throws Exception {
        AlertServiceStateMachineFixture.initMessages();
    }

    // ==================== 装配 ====================

    private static Object unsafe() throws Exception {
        Class<?> c = Class.forName("sun.misc.Unsafe");
        Field f = c.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object alloc(Class<?> k) throws Exception {
        return unsafe().getClass().getMethod("allocateInstance", Class.class).invoke(unsafe(), k);
    }

    private static void setField(Class<?> cls, Object t, String n, Object v) throws Exception {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                f.set(t, v);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(n);
    }

    private static ConfigManager configManager(boolean historyEnabled) throws Exception {
        ConfigManager cm = (ConfigManager) alloc(ConfigManager.class);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.history_enabled = historyEnabled;
        cfg.history_sampleIntervalSeconds = 60;
        cfg.players_warningThreshold = WARN_THRESHOLD;
        cfg.players_criticalThreshold = CRITICAL_THRESHOLD;
        setField(ConfigManager.class, cm, "config", cfg);
        return cm;
    }

    /** 造一个只含指定样本的 HistoryStore（绕过节流，直接写私有环）。 */
    private static HistoryStore history(ConfigManager cm, List<HistorySample> samples) throws Exception {
        HistoryStore hs = (HistoryStore) alloc(HistoryStore.class);
        setField(HistoryStore.class, hs, "configManager", cm);
        setField(HistoryStore.class, hs, "logger", Logger.getLogger("pscope-playercap-test"));
        Deque<HistorySample> ring = new ArrayDeque<>(samples);
        setField(HistoryStore.class, hs, "ring", ring);
        return hs;
    }

    private static MetricsSnapshot snapshot(int players, int maxPlayers) {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.playerCount = players;
        snap.maxPlayers = maxPlayers;
        return snap;
    }

    /**
     * 造 {@code counts.length} 个样本，间隔 100 秒、最后一个落在"现在"。
     *
     * <p>6 条 × 100 秒 = 500 秒跨度，既满足 {@code MIN_SAMPLES=5}，也满足覆盖判据
     * （{@code needed = max(100s×5×0.8, 15min×10%) = 400s}）。<b>不贴 15 分钟边界</b>：
     * 时间戳按墙钟算，贴着边界跑会因几毫秒漂移随机掉出窗口。</p>
     */
    private static List<HistorySample> samples(int... counts) {
        long now = System.currentTimeMillis();
        List<HistorySample> out = new ArrayList<>(counts.length);
        for (int i = 0; i < counts.length; i++) {
            long ts = now - (long) (counts.length - 1 - i) * 100_000L;
            out.add(new HistorySample(ts, 20.0, 10.0, 50, counts[i], 100, 500));
        }
        return out;
    }

    private static Evaluation evaluate(MetricsSnapshot snap, HistoryStore hs,
                                       ConfigManager cm) throws Exception {
        return new CoreRules.PlayerCapacityRule().evaluate(snap, hs, cm.get());
    }

    private static String reason(String key, Object... args) {
        return Messages.get(key, args);
    }

    // ==================== 三态之"无法评估" ====================

    /** 玩家模块不可用 → skip，原因点名模块。 */
    @Test
    void unavailablePlayerModuleSkips() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(95, 95, 95, 95, 95, 95));
        MetricsSnapshot snap = snapshot(95, MAX_PLAYERS);
        snap.unavailable.add(MetricsSnapshot.MODULE_PLAYERS);

        Evaluation ev = evaluate(snap, hs, cm);

        assertNull(ev.hit());
        assertFalse(ev.isClean(), "a rule whose precondition failed did NOT evaluate — it must skip");
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.players-unavailable"), ev.skip().reason());
        assertEquals("DIAG-PLAYER-CAP", ev.skip().ruleId());
    }

    /** {@code maxPlayers == 0}（服务端还没报容量）→ skip，并且把实际读数写进原因。 */
    @Test
    void zeroMaxPlayersSkipsWithTheReading() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(0, 0, 0, 0, 0, 0));

        Evaluation ev = evaluate(snapshot(5, 0), hs, cm);

        assertNull(ev.hit());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.max-players-not-ready", 0), ev.skip().reason());
    }

    /** {@code maxPlayers == -1} 同样是哨兵 → skip（守卫是 {@code <= 0}，不是 {@code == 0}）。 */
    @Test
    void negativeMaxPlayersSkips() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(0, 0, 0, 0, 0, 0));

        Evaluation ev = evaluate(snapshot(5, -1), hs, cm);

        assertNull(ev.hit());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.max-players-not-ready", -1), ev.skip().reason());
    }

    /** 窗口样本不足 5 条 → 覆盖不足 skip（不是 clean：这一条要说清"还缺什么"）。 */
    @Test
    void tooFewSamplesSkips() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(95, 95, 95, 95));

        Evaluation ev = evaluate(snapshot(95, MAX_PLAYERS), hs, cm);

        assertNull(ev.hit());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.coverage-samples", reason("diag.label.history"), 4, 5),
            ev.skip().reason());
    }

    /** 历史被关闭 → 读取路径返回空列表 → skip，不得凭空判定"持续高位"。 */
    @Test
    void disabledHistorySkips() throws Exception {
        ConfigManager cm = configManager(false);
        HistoryStore hs = history(cm, samples(95, 95, 95, 95, 95, 95));

        Evaluation ev = evaluate(snapshot(95, MAX_PLAYERS), hs, cm);

        assertNull(ev.hit());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.coverage-samples", reason("diag.label.history"), 0, 5),
            ev.skip().reason());
    }

    // ==================== 反向：评估了但干净 ====================

    /**
     * 当前占比低于告警线 → clean，而且<b>在查历史之前</b>就返回了。
     *
     * <p>history 传 {@code null}：若实现对调了两段顺序，这里会 NPE 而不是绿。</p>
     */
    @Test
    void belowWarningThresholdIsCleanWithoutTouchingHistory() throws Exception {
        Evaluation ev = new CoreRules.PlayerCapacityRule()
            .evaluate(snapshot(50, MAX_PLAYERS), null, configManager(true).get());

        assertNull(ev.hit(), "50% is far below the 90% warning line");
        assertTrue(ev.isClean(), "it was evaluated and found fine — clean, not skip");
        assertNull(ev.skip());
    }

    /** 边界：当前 89% (&lt; 90) → clean。 */
    @Test
    void justBelowTheWarningLineIsClean() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(89, 89, 89, 89, 89, 89));

        assertTrue(evaluate(snapshot(89, MAX_PLAYERS), hs, cm).isClean());
    }

    /**
     * 边界：当前占比恰好等于告警线（90%）→ <b>必须继续评估</b>，不是 clean。
     *
     * <p>早退条件是 {@code currentPercent < warning}（严格小于），所以 90% 恰好落在
     * "需要查历史"的一侧。配合一个持续高位的窗口，正确实现给出 HIT。</p>
     *
     * <p>这条用例的存在理由很具体：把 {@code <} 写成 {@code <=} 时，其余所有用例仍然全绿
     * （它们要么远低于线、要么远高于线），只有这一条能杀掉那个变异体。</p>
     */
    @Test
    void exactlyAtTheWarningLineIsStillEvaluated() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(90, 92, 93, 94, 95, 95));

        Evaluation ev = evaluate(snapshot(90, MAX_PLAYERS), hs, cm);

        Diagnosis d = ev.hit();
        assertNotNull(d, "90% is exactly the warning line (inclusive): it must not be short-circuited "
            + "to clean, and the window (low 90%) confirms sustained occupancy");
        assertFalse(d.critical(), "90% is far below the 98% critical line");
    }

    /**
     * 当前越线，但窗口里有明显低谷（70% &lt; 90-10=80）→ 属活动峰值，clean。
     *
     * <p>这正是本规则与"瞬时告警"的分工：瞬时满员不算容量问题，持续的才算。</p>
     */
    @Test
    void peakWithAnObviousLowInTheWindowIsClean() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(70, 80, 90, 93, 94, 95));

        Evaluation ev = evaluate(snapshot(95, MAX_PLAYERS), hs, cm);

        assertNull(ev.hit(), "a window that dipped to 70% was an activity peak, not a capacity wall");
        assertTrue(ev.isClean());
        assertNull(ev.skip());
    }

    // ==================== 正向：必须命中 ====================

    /** 当前越线 + 窗口持续高位 → 必须给出结论，并带上当前/最低两个读数。 */
    @Test
    void sustainedHighOccupancyProducesADiagnosis() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(90, 92, 93, 94, 95, 95));

        Evaluation ev = evaluate(snapshot(95, MAX_PLAYERS), hs, cm);

        Diagnosis d = ev.hit();
        assertNotNull(d, "15 minutes never dipping below 90% is a real capacity problem");
        assertEquals("DIAG-PLAYER-CAP", d.ruleId());
        assertFalse(d.title().isEmpty(), "the diagnosis must carry a title");
        assertFalse(d.suggestion().isEmpty(), "the diagnosis must carry an actionable suggestion");
        assertFalse(d.critical(), "95% is a warning; critical starts at 98%");
    }

    /** detail 必须由实现真的把 {current, max, percent, minutes, windowLow} 填进模板。 */
    @Test
    void detailCarriesTheWindowLowAndTheSpan() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(90, 92, 93, 94, 95, 95));

        Diagnosis d = evaluate(snapshot(95, MAX_PLAYERS), hs, cm).hit();
        assertNotNull(d);

        // 6 条 × 100 秒 = 500 秒 → 整数分钟 8
        String expected = Messages.get("diag.DIAG-PLAYER-CAP.detail", 95, 100, 95, 8L, 90);
        assertEquals(expected, d.detail(),
            "detail must render {current, max, percent, minutes, windowLow} in that order");
    }

    // ==================== critical 分级 ====================

    /** 当前占比 ≥ critical(98) → critical=true。 */
    @Test
    void atCriticalThresholdIsCritical() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(98, 98, 98, 98, 98, 99));

        Diagnosis d = evaluate(snapshot(99, MAX_PLAYERS), hs, cm).hit();

        assertNotNull(d);
        assertTrue(d.critical(), "99% of capacity is critical (inclusive line 98)");
    }

    /** 边界：97% → 不是 critical（只是警告），证明 critical 判据独立于命中判据。 */
    @Test
    void justBelowCriticalThresholdIsAWarning() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(96, 96, 96, 97, 97, 97));

        Diagnosis d = evaluate(snapshot(97, MAX_PLAYERS), hs, cm).hit();

        assertNotNull(d);
        assertFalse(d.critical(), "97% is one point short of the 98% critical line");
    }

    // ==================== 关键：0 人样本必须参与最低点 ====================

    /**
     * <b>核心用例</b>：窗口里 {@code playerCount = 0} 的样本必须把最低点拉到 0，
     * 从而把"持续满员"判成 clean。
     *
     * <p>形状照抄 9A-8 的真实事故：凌晨一段 0 人，白天上线后短暂接近上限。
     * 若实现跳过 0（原缺陷），窗口最低点会算成 60% 或 95%，这条用例会拿到一条假的
     * "在线人数持续接近上限"结论。</p>
     *
     * <p>这里刻意让"去掉 0 之后"的窗口仍然越低线（60 &lt; 80），所以本用例<b>单独</b>不能
     * 区分两种实现——真正杀死"跳过 0"变异体的是
     * {@link #zeroPlayerSamplesAreTheOnlyLowInTheWindow()}，它把 0 设为唯一低谷。</p>
     */
    @Test
    void zeroPlayerSamplesCountAsTheWindowLow() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(0, 0, 95, 95, 95, 95));

        Evaluation ev = evaluate(snapshot(95, MAX_PLAYERS), hs, cm);

        assertNull(ev.hit(), "a night full of 0-player samples means this is a peak, not a wall");
        assertTrue(ev.isClean());
    }

    /**
     * <b>杀死"跳过 0 人样本"变异体的用例</b>：0 是窗口里<b>唯一</b>的低谷。
     *
     * <p>去掉 0 之后最低点是 95%，而 95 ≥ 90-10=80 → HIT。所以：</p>
     * <ul>
     *   <li>正确实现 → clean；</li>
     *   <li>把 {@code if (s.playerCount() > 0)} 加回去 → 命中一条假的"持续接近上限"。</li>
     * </ul>
     * <p>两种实现给出相反的结论，本用例因此具备区分力，而不是"碰巧通过"。</p>
     */
    @Test
    void zeroPlayerSamplesAreTheOnlyLowInTheWindow() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(0, 95, 96, 97, 96, 95));

        // 前置自检：窗口确实只靠那一个 0 把最低点拉下来
        List<HistorySample> window = hs.since(System.currentTimeMillis() - 15 * 60_000L);
        assertEquals(6, window.size(), "precondition: all six samples are inside the 15-minute window");
        assertEquals(0, window.get(0).playerCount(), "precondition: the only low sample is a real 0");

        Evaluation ev = evaluate(snapshot(95, MAX_PLAYERS), hs, cm);

        assertNull(ev.hit(),
            "a 0-player sample is a real trough; skipping it would turn this window into a fake "
                + "'sustained near-capacity' verdict");
        assertTrue(ev.isClean(), "the trough was seen, so it was evaluated and found fine");
    }

    // ==================== 边界：-10 的余量 ====================

    /**
     * 边界：窗口最低点恰好等于 {@code warning - 10} → <b>不</b>算"明显低谷"，必须命中。
     *
     * <p>实现是 {@code minPercent < warning - 10}（严格小于）。这条同时杀掉
     * "把 {@code - 10} 写成 {@code + 10}"的变异体（那样 80 &lt; 100 会变成 clean）
     * 与"把 {@code <} 写成 {@code <=}"的变异体（那样 80 ≤ 80 也会变成 clean）。</p>
     */
    @Test
    void windowLowExactlyAtTheMarginStillHits() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(80, 88, 90, 92, 94, 95));

        Evaluation ev = evaluate(snapshot(95, MAX_PLAYERS), hs, cm);

        assertNotNull(ev.hit(),
            "80% is exactly warning-10 (the margin is exclusive): the window never really dipped, "
                + "so this is sustained occupancy");
    }

    /** 反方向：低一点点（79%）就算明显低谷 → clean。 */
    @Test
    void windowLowOnePointBelowTheMarginIsClean() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(79, 88, 90, 92, 94, 95));

        Evaluation ev = evaluate(snapshot(95, MAX_PLAYERS), hs, cm);

        assertNull(ev.hit(), "79% is a real dip below warning-10");
        assertTrue(ev.isClean());
    }

    // ==================== 纯函数契约 ====================

    /** 规则只读输入：同一份输入跑两遍结论一致，且历史环没被改写。 */
    @Test
    void evaluationIsPureAndRepeatable() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = history(cm, samples(90, 92, 93, 94, 95, 95));
        MetricsSnapshot snap = snapshot(95, MAX_PLAYERS);

        Diagnosis first = evaluate(snap, hs, cm).hit();
        Diagnosis second = evaluate(snap, hs, cm).hit();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.detail(), second.detail(), "a pure rule must produce the same evidence twice");
        assertEquals(6, hs.size(), "the rule must not mutate the history ring");
    }
}
