/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.diagnose;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.TestObjects;
import cn.perfscope.util.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * f-s03-data-06 / f-s03-logic-02 / f-s03-err-04 的代次与结果契约回归喵。
 *
 * <p>尤其锁住「宽限窗口」：代次不符只在一个采集周期 + 余量内跳过诊断；
 * 超出窗口仍不匹配说明采集本身已停摆，此时必须照常评估，避免诊断永久沉默——
 * 这是 r2-fix-04 在真实 Paper 上自己发现并纠正的方向喵。</p>
 */
class DiagnosticEngineContractTest {

    @BeforeAll
    static void initMessages() throws Exception {
        // 走真实入口（裸 plugin 的 load(plugin)），**不要**用 Messages.load(null)。
        //
        // 为什么：load(null) 是"无插件"分支。它当前发布哪张表取决于该分支是否包含
        // touchModules()+mergeModules()——那条 P1 修复正在另一个分支上评审中，
        // 修好之前 diag.* 键不在表里、修好之后在。本类若依赖 load(null)，
        // 就会变成"哪个分支上跑决定成败"的测试，而这正是 CoverageProblemTest 踩过的坑。
        // AlertFixtures.initMessages() 走的是带 plugin 的那条路径（生产环境同款），
        // 键在不在与分支状态无关，只与模块是否注册有关。
        cn.perfscope.support.AlertFixtures.initMessages();
    }

    private static ConfigManager.ConfigData config() {
        ConfigManager.ConfigData c = new ConfigManager.ConfigData();
        c.general_updateInterval = 20;          // 1 秒周期 -> 宽限窗口 = 21 秒，但下限 30 秒
        c.tps_warningThreshold = 18.0;
        c.tps_criticalThreshold = 15.0;
        c.general_debug = false;
        return c;
    }

    /** 装配一个只依赖 plugin/cm/snapshot 的 DiagnosticEngine。 */
    private static DiagnosticEngine engine(long publishedAt, long snapTimestamp) {
        PerfScope p = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, p, "logger", Logger.getLogger("diag-contract-test"));

        MetricsSnapshot snap = new MetricsSnapshot();
        snap.timestamp = snapTimestamp;
        snap.unavailable = new java.util.HashSet<>();
        // getLatest() 的实现是 history.peekLast()。history 是 final 字段，
        // Unsafe.allocateInstance 不会初始化它；真实构造器又会触发 ServerCompat 的
        // Bukkit 静态初始化（单测进程无服务端，必然 NPE）。故用 Field.set 直接写入 final 字段。
        MetricsCollector mc = TestObjects.allocate(MetricsCollector.class);
        java.util.Deque<MetricsSnapshot> hist = new java.util.concurrent.ConcurrentLinkedDeque<>();
        hist.addLast(snap);
        TestObjects.setField(MetricsCollector.class, mc, "history", hist);
        TestObjects.setField(PerfScope.class, p, "metricsCollector", mc);

        ConfigManager cm = new ConfigManager(p);
        TestObjects.setField(ConfigManager.class, cm, "config", config());
        TestObjects.setField(ConfigManager.class, cm, "publishedAtMillis", publishedAt);
        TestObjects.setField(PerfScope.class, p, "configManager", cm);

        DiagnosticEngine e = TestObjects.allocate(DiagnosticEngine.class);
        TestObjects.setField(DiagnosticEngine.class, e, "plugin", p);
        TestObjects.setField(DiagnosticEngine.class, e, "configManager", cm);
        return e;
    }

    @Test
    void noSnapshotIsZeroTotalAndNotFullyEvaluated() {
        PerfScope p = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, p, "logger", Logger.getLogger("diag-contract-test"));
        ConfigManager cm = new ConfigManager(p);
        TestObjects.setField(ConfigManager.class, cm, "config", config());
        TestObjects.setField(PerfScope.class, p, "configManager", cm);
        DiagnosticEngine e = TestObjects.allocate(DiagnosticEngine.class);
        TestObjects.setField(DiagnosticEngine.class, e, "plugin", p);
        TestObjects.setField(DiagnosticEngine.class, e, "configManager", cm);

        DiagnosticEngine.Result r = e.run();
        assertEquals(0, r.total(), "no snapshot => no rule ran");
        assertFalse(r.fullyEvaluated(), "0 evaluated must NOT be reported as fully evaluated");
    }

    @Test
    void staleConfigInsideGraceWindowSkipsEveryRule() {
        long now = System.currentTimeMillis();
        DiagnosticEngine.Result r = engine(now, now - 5_000L).run();
        assertTrue(r.total() > 0, "stale-config path must still carry a real evaluation base");
        assertEquals(r.total(), r.skipped().size(), "every rule must be explicitly skipped");

        // 期望值取自**文案表里的实际字符串**，不是键名。
        //
        // 旧写法是 `"diag.skip.stale-config".equals(s.reason())` —— 那条断言只有在
        // Messages 查不到这个键（resolve 退回键名本身）时才成立，正是"恒真的假绿"：
        // 它把"文案没接上"当成正确结果，于是 Messages 的加载一旦修好、真正解析出文案，
        // 这条就红了。实测取证（本地）：Messages.load(null) 前后同一键分别解析出
        // "diag.skip.stale-config" 与 "Configuration was reloaded after this sample was collected…"。
        //
        // 现在的判据是"reason 必须是这条键**解析出来的那句话**"——文案改了这里不会假红
        // （直接取表），文案没接上则立刻红。
        String expected = staleReason();
        assertFalse("diag.skip.stale-config".equals(expected),
            "fixture precondition: the key must resolve to real text, not fall back to itself");
        assertTrue(r.skipped().stream().allMatch(s -> expected.equals(s.reason())),
            "skip reason must name the config generation; got "
                + r.skipped().stream().map(Diagnosis.Skip::reason).distinct().toList());
    }

    @Test
    void staleConfigBeyondGraceWindowDoesNotSilenceForever() {
        long now = System.currentTimeMillis();
        // 发布 10 分钟前，快照更早：宽限窗口（30s~5min）已过，必须照常评估
        DiagnosticEngine.Result r = engine(now - 600_000L, now - 610_000L).run();
        assertFalse(r.skipped().stream()
                .anyMatch(s -> staleReason().equals(s.reason())),
            "a stalled collector must not be silenced forever by the generation guard; got "
                + r.skipped().stream().map(Diagnosis.Skip::reason).distinct().toList());
    }

    @Test
    void freshConfigHasNoStaleSkips() {
        long now = System.currentTimeMillis();
        DiagnosticEngine.Result r = engine(now - 10_000L, now).run();
        assertFalse(r.skipped().stream()
                .anyMatch(s -> staleReason().equals(s.reason())),
            "a snapshot newer than the config publish must evaluate normally; got "
                + r.skipped().stream().map(Diagnosis.Skip::reason).distinct().toList());
    }

    /**
     * 代次 skip 的期望文案（消息层对该键的<b>解析结果</b>）。
     *
     * <p><b>为什么两条负向断言也必须改</b>：它们原先写的是 {@code "diag.skip.stale-config"
     * .equals(reason)}。键缺失时 reason 退回键名，{@code assertFalse(anyMatch(...))} 才有
     * 拦截力；{@code Messages.load(null)} 修好之后 reason 是翻译文案，键名比对<b>恒为 false</b>，
     * 于是这两条 {@code assertFalse} 退化成永真——规则真的"永久沉默"了它们也会绿。
     * 负向断言比正向断言更危险，因为它绿的时候看起来像"通过了"。</p>
     */
    private static String staleReason() {
        return Messages.get("diag.skip.stale-config");
    }

    @Test
    void graceWindowIsBounded() {
        ConfigManager.ConfigData c = config();
        long normal = (Long) TestObjects.invoke(DiagnosticEngine.class, "reloadGraceMs",
            new Class<?>[]{ConfigManager.ConfigData.class}, c);
        assertEquals(30_000L, normal, "1s cycle + 20s margin clamps to the 30s floor");

        c.general_updateInterval = 20 * 60 * 60;  // 极端值：不能让窗口无限放大
        long extreme = (Long) TestObjects.invoke(DiagnosticEngine.class, "reloadGraceMs",
            new Class<?>[]{ConfigManager.ConfigData.class}, c);
        assertEquals(300_000L, extreme, "grace window must be capped at 5 minutes");
    }
}
