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
import cn.perfscope.support.HistoryFixtures;
import cn.perfscope.support.TestObjects;
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
 * 诊断规则<b>本体</b>：{@link CoreRules.PlayerCapacityRule}（DIAG-PLAYER-CAP）。
 *
 * <p>准纯函数：判据本身只读 {@link MetricsSnapshot}，但"是不是活动峰值"要看 15 分钟历史窗口，
 * 所以要喂历史。喂法与 {@code MemoryLeakRuleTest} 相同——{@code HistoryFixtures.seeded}
 * 直写私有环 {@code ring}，绕开 {@code record(snap)} 的采样节流。</p>
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

    private static ConfigManager configManager(boolean historyEnabled) {
        ConfigManager cm = new ConfigManager(null);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.history_enabled = historyEnabled;
        cfg.history_sampleIntervalSeconds = 60;
        cfg.players_warningThreshold = WARN_THRESHOLD;
        cfg.players_criticalThreshold = CRITICAL_THRESHOLD;
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        return cm;
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
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(95, 95, 95, 95, 95, 95));
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
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(0, 0, 0, 0, 0, 0));

        Evaluation ev = evaluate(snapshot(5, 0), hs, cm);

        assertNull(ev.hit());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.max-players-not-ready", 0), ev.skip().reason());
    }

    /** {@code maxPlayers == -1} 同样是哨兵 → skip（守卫是 {@code <= 0}，不是 {@code == 0}）。 */
    @Test
    void negativeMaxPlayersSkips() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(0, 0, 0, 0, 0, 0));

        Evaluation ev = evaluate(snapshot(5, -1), hs, cm);

        assertNull(ev.hit());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.max-players-not-ready", -1), ev.skip().reason());
    }

    /** 窗口样本不足 5 条 → 覆盖不足 skip（不是 clean：这一条要说清"还缺什么"）。 */
    @Test
    void tooFewSamplesSkips() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(95, 95, 95, 95));

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
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(95, 95, 95, 95, 95, 95));

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
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(89, 89, 89, 89, 89, 89));

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
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(90, 92, 93, 94, 95, 95));

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
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(70, 80, 90, 93, 94, 95));

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
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(90, 92, 93, 94, 95, 95));

        Evaluation ev = evaluate(snapshot(95, MAX_PLAYERS), hs, cm);

        Diagnosis d = ev.hit();
        assertNotNull(d, "15 minutes never dipping below 90% is a real capacity problem");
        assertEquals("DIAG-PLAYER-CAP", d.ruleId());
        assertFalse(d.title().isEmpty(), "the diagnosis must carry a title");
        assertFalse(d.suggestion().isEmpty(), "the diagnosis must carry an actionable suggestion");
        assertFalse(d.critical(), "95% is a warning; critical starts at 98%");
    }

    /** detail 必须由实现真的把 {current, max, percent, minutes, lowPlayers} 填进模板。 */
    @Test
    void detailCarriesTheWindowLowAndTheSpan() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(90, 92, 93, 94, 95, 95));

        Diagnosis d = evaluate(snapshot(95, MAX_PLAYERS), hs, cm).hit();
        assertNotNull(d);

        // 6 条 × 100 秒 = 500 秒 → 整数分钟 8；窗口最低样本是 90 名玩家
        String expected = Messages.get("diag.DIAG-PLAYER-CAP.detail", 95, 100, 95, 8L, 90);
        assertEquals(expected, d.detail(),
            "detail must render {current, max, percent, minutes, windowLowPlayers} in that order");
    }

    // ==================== F-8：detail 不得编造历史百分比 ====================

    /**
     * <b>F-8（回归）</b>：历史样本只存<b>当时的玩家数</b>，不存当时的容量，所以任何
     * "窗口最低也有 X%" 都是一个按<b>当前</b>上限重算出来的、历史上从未发生过的百分比。
     *
     * <p>形状：当前 48/50（96%）；窗口里最低是 45 名玩家。按当前上限折算是 90%，
     * 而样本被采集的那一刻上限是多少、当时占比多少，历史里根本没有记录。</p>
     *
     * <p>区分力：旧 detail 写"最低也有 90%"，全文<b>不含</b> 45；新 detail 必须报出
     * 原始计数 45，且不得出现那个折算出来的 90%。</p>
     */
    @Test
    void historyLowIsReportedAsARawCountNotAFabricatedPercentage() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(45, 46, 47, 48, 48, 48));

        Diagnosis d = evaluate(snapshot(48, 50), hs, cm).hit();
        assertNotNull(d, "48/50 = 96% sustained is a real capacity wall");

        assertTrue(d.detail().contains("45"),
            "F-8: the window low must be reported as the raw player count that history really "
                + "stores (45), not only as a percentage recomputed from the current cap, got: "
                + d.detail());
        // 45/50 = 90%：旧实现会在 detail 里打出这个数，而它按当时的容量从未发生过
        assertFalse(d.detail().contains("90%"),
            "F-8: no '90%' may appear — that percentage was recomputed with today's cap and never "
                + "occurred at the time the samples were taken, got: " + d.detail());

        String expected = Messages.get("diag.DIAG-PLAYER-CAP.detail", 48, 50, 96, 8L, 45);
        assertEquals(expected, d.detail(),
            "detail must render {current, max, percent, minutes, windowLowPlayers}");
    }

    /** F-7 用的配置装配：阈值被构造成 0（模拟配置层校验失效或有人直构 ConfigData）。 */
    private static ConfigManager configManagerZeroPlayersThreshold() throws Exception {
        ConfigManager cm = configManager(true);
        cm.get().players_warningThreshold = 0;
        cm.get().players_criticalThreshold = 0;
        return cm;
    }

    /**
     * 反向（扩容 100→200）：历史 95 名玩家按当前上限折算是 47.5%，窗口低谷成立 → clean。
     *
     * <p>这条锁住"判定没被 F-8 的措辞修复带偏"：clean 的依据是历史玩家的<b>原始计数</b>
     * 相对当前容量的占比，而不是某个历史百分比。</p>
     */
    @Test
    void grownCapacityMakesTheSameHistoryLookHealthy() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(95, 95, 95, 95, 95, 95));

        // 当前 95/200 = 47.5% < 90 → 提前 clean，不进入历史判定
        Evaluation ev = evaluate(snapshot(95, 200), hs, cm);
        assertNull(ev.hit());
        assertTrue(ev.isClean());
    }

    // ==================== F-7：不可用的阈值不得产生荒谬结论 ====================

    /**
     * <b>F-7（回归）</b>：{@code players.warning_threshold = 0}（配置层漏放）时，
     * 规则不得把"1 人在线 / 上限 200"报成"接近上限"。
     *
     * <p>旧实现：{@code currentPercent(1) < 0} 恒假 → 不早退；{@code minPercent < 0-10} 恒假 →
     * 不 return clean；{@code 1 >= critical(0)} 恒真 → 一条 critical。
     * 一个 0 人服也会被判"容量严重不足"。</p>
     *
     * <p>防御口径：阈值 0 不是一个可用的判据，规则必须<b>如实说无法评估</b>（skip），
     * 而不是猜一个默认值——猜默认值会掩盖配置错误，正是本项目一条硬原则的反面。</p>
     */
    @Test
    void zeroWarningThresholdSkipsInsteadOfReportingACritical() throws Exception {
        ConfigManager cm = configManagerZeroPlayersThreshold();
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(1, 1, 1, 1, 1, 1));

        Evaluation ev = evaluate(snapshot(1, 200), hs, cm);

        assertNull(ev.hit(), "F-7: 1 player out of 200 must never be 'near capacity'");
        assertFalse(ev.isClean(), "the threshold is unusable, so nothing was really evaluated");
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.players-threshold-invalid", 0), ev.skip().reason());
    }

    /** 负数阈值同样不可用（配置层本该拦，规则侧不依赖它拦）。 */
    @Test
    void negativeWarningThresholdSkips() throws Exception {
        ConfigManager cm = configManager(true);
        cm.get().players_warningThreshold = -1;
        cm.get().players_criticalThreshold = -1;
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(1, 1, 1, 1, 1, 1));

        Evaluation ev = evaluate(snapshot(1, 200), hs, cm);

        assertNull(ev.hit());
        assertNotNull(ev.skip());
        assertEquals(reason("diag.skip.players-threshold-invalid", -1), ev.skip().reason());
    }

    /**
     * 临界阈值不可用（&lt;= 0）时不得把每条命中都升成 critical。
     *
     * <p>{@code currentPercent >= 0} 恒真——旧实现会把任何一个命中标成严重。
     * 现在命中照旧输出（warning 阈值本身是有效的），但严重度退回 false。</p>
     */
    @Test
    void unusableCriticalThresholdDoesNotEscalateEveryHit() throws Exception {
        ConfigManager cm = configManager(true);
        cm.get().players_criticalThreshold = 0;
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(90, 92, 93, 94, 95, 95));

        Diagnosis d = evaluate(snapshot(95, MAX_PLAYERS), hs, cm).hit();
        assertNotNull(d, "the warning threshold is fine, so the hit itself is still valid");
        assertFalse(d.critical(),
            "a critical threshold of 0 is not a judgement anyone made; it must not turn every hit "
                + "into a critical");
    }

    /**
     * 规则侧防御不得与配置层的正解冲突：合法阈值下行为逐字不变。
     *
     * <p>与另一个 Agent 在 {@code ConfigManager} 里加的"阈值必须 &gt; 0"是<b>两层分工</b>：
     * 配置层负责把错误配置挡在门外并给出 warning；规则层负责在错误配置真的漏进来时
     * 不产生荒谬结论（跳过 + 说明），绝不悄悄替换成一个"看起来正常"的值。</p>
     */
    @Test
    void aSaneThresholdIsUnaffectedByTheDefensiveGuard() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(90, 92, 93, 94, 95, 95));

        Diagnosis d = evaluate(snapshot(95, MAX_PLAYERS), hs, cm).hit();
        assertNotNull(d);
        assertFalse(d.critical(), "95% is still below the 98% critical line");
    }

    // ==================== critical 分级 ====================

    /** 当前占比 ≥ critical(98) → critical=true。 */
    @Test
    void atCriticalThresholdIsCritical() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(98, 98, 98, 98, 98, 99));

        Diagnosis d = evaluate(snapshot(99, MAX_PLAYERS), hs, cm).hit();

        assertNotNull(d);
        assertTrue(d.critical(), "99% of capacity is critical (inclusive line 98)");
    }

    /** 边界：97% → 不是 critical（只是警告），证明 critical 判据独立于命中判据。 */
    @Test
    void justBelowCriticalThresholdIsAWarning() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(96, 96, 96, 97, 97, 97));

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
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(0, 0, 95, 95, 95, 95));

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
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(0, 95, 96, 97, 96, 95));

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
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(80, 88, 90, 92, 94, 95));

        Evaluation ev = evaluate(snapshot(95, MAX_PLAYERS), hs, cm);

        assertNotNull(ev.hit(),
            "80% is exactly warning-10 (the margin is exclusive): the window never really dipped, "
                + "so this is sustained occupancy");
    }

    /** 反方向：低一点点（79%）就算明显低谷 → clean。 */
    @Test
    void windowLowOnePointBelowTheMarginIsClean() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(79, 88, 90, 92, 94, 95));

        Evaluation ev = evaluate(snapshot(95, MAX_PLAYERS), hs, cm);

        assertNull(ev.hit(), "79% is a real dip below warning-10");
        assertTrue(ev.isClean());
    }

    // ==================== 纯函数契约 ====================

    /** 规则只读输入：同一份输入跑两遍结论一致，且历史环没被改写。 */
    @Test
    void evaluationIsPureAndRepeatable() throws Exception {
        ConfigManager cm = configManager(true);
        HistoryStore hs = HistoryFixtures.seeded(cm, samples(90, 92, 93, 94, 95, 95));
        MetricsSnapshot snap = snapshot(95, MAX_PLAYERS);

        Diagnosis first = evaluate(snap, hs, cm).hit();
        Diagnosis second = evaluate(snap, hs, cm).hit();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.detail(), second.detail(), "a pure rule must produce the same evidence twice");
        assertEquals(6, hs.size(), "the rule must not mutate the history ring");
    }
}
