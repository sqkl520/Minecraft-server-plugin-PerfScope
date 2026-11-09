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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
        Class.forName("cn.perfscope.config.ConfigMessages").getMethod("init").invoke(null);
        Class.forName("cn.perfscope.diagnose.DiagnoseMessages").getMethod("init").invoke(null);
        Class.forName("cn.perfscope.util.Messages")
            .getMethod("load", Class.forName("cn.perfscope.PerfScope"))
            .invoke(null, (Object) null);
    }

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

    private static Object getField(Class<?> cls, Object target, String name) throws Exception {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
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
    private static DiagnosticEngine engine(long publishedAt, long snapTimestamp) throws Exception {
        PerfScope p = (PerfScope) alloc(PerfScope.class);
        setField(PerfScope.class, p, "logger", Logger.getLogger("diag-contract-test"));

        MetricsSnapshot snap = new MetricsSnapshot();
        snap.timestamp = snapTimestamp;
        snap.unavailable = new java.util.HashSet<>();
        // getLatest() 的实现是 history.peekLast()。history 是 final 字段，
        // Unsafe.allocateInstance 不会初始化它；真实构造器又会触发 ServerCompat 的
        // Bukkit 静态初始化（单测进程无服务端，必然 NPE）。故用 Unsafe 直接写入 final 字段。
        MetricsCollector mc = (MetricsCollector) alloc(MetricsCollector.class);
        java.util.Deque<MetricsSnapshot> hist = new java.util.concurrent.ConcurrentLinkedDeque<>();
        hist.addLast(snap);
        setField(MetricsCollector.class, mc, "history", hist);
        setField(PerfScope.class, p, "metricsCollector", mc);

        ConfigManager cm = (ConfigManager) alloc(ConfigManager.class);
        setField(ConfigManager.class, cm, "config", config());
        setField(ConfigManager.class, cm, "publishedAtMillis", publishedAt);
        setField(PerfScope.class, p, "configManager", cm);

        DiagnosticEngine e = (DiagnosticEngine) alloc(DiagnosticEngine.class);
        setField(DiagnosticEngine.class, e, "plugin", p);
        setField(DiagnosticEngine.class, e, "configManager", cm);
        return e;
    }

    @Test
    void noSnapshotIsZeroTotalAndNotFullyEvaluated() throws Exception {
        PerfScope p = (PerfScope) alloc(PerfScope.class);
        setField(PerfScope.class, p, "logger", Logger.getLogger("diag-contract-test"));
        ConfigManager cm = (ConfigManager) alloc(ConfigManager.class);
        setField(ConfigManager.class, cm, "config", config());
        setField(PerfScope.class, p, "configManager", cm);
        DiagnosticEngine e = (DiagnosticEngine) alloc(DiagnosticEngine.class);
        setField(DiagnosticEngine.class, e, "plugin", p);
        setField(DiagnosticEngine.class, e, "configManager", cm);

        DiagnosticEngine.Result r = e.run();
        assertEquals(0, r.total(), "no snapshot => no rule ran");
        assertFalse(r.fullyEvaluated(), "0 evaluated must NOT be reported as fully evaluated");
    }

    @Test
    void staleConfigInsideGraceWindowSkipsEveryRule() throws Exception {
        long now = System.currentTimeMillis();
        DiagnosticEngine.Result r = engine(now, now - 5_000L).run();
        assertTrue(r.total() > 0, "stale-config path must still carry a real evaluation base");
        assertEquals(r.total(), r.skipped().size(), "every rule must be explicitly skipped");
        assertTrue(r.skipped().stream()
                .allMatch(s -> "diag.skip.stale-config".equals(s.reason())),
            "skip reason must name the config generation");
    }

    @Test
    void staleConfigBeyondGraceWindowDoesNotSilenceForever() throws Exception {
        long now = System.currentTimeMillis();
        // 发布 10 分钟前，快照更早：宽限窗口（30s~5min）已过，必须照常评估
        DiagnosticEngine.Result r = engine(now - 600_000L, now - 610_000L).run();
        assertFalse(r.skipped().stream()
                .anyMatch(s -> "diag.skip.stale-config".equals(s.reason())),
            "a stalled collector must not be silenced forever by the generation guard");
    }

    @Test
    void freshConfigHasNoStaleSkips() throws Exception {
        long now = System.currentTimeMillis();
        DiagnosticEngine.Result r = engine(now - 10_000L, now).run();
        assertFalse(r.skipped().stream()
                .anyMatch(s -> "diag.skip.stale-config".equals(s.reason())),
            "a snapshot newer than the config publish must evaluate normally");
    }

    @Test
    void graceWindowIsBounded() throws Exception {
        Method m = DiagnosticEngine.class.getDeclaredMethod("reloadGraceMs", ConfigManager.ConfigData.class);
        m.setAccessible(true);
        ConfigManager.ConfigData c = config();
        long normal = (Long) m.invoke(null, c);
        assertEquals(30_000L, normal, "1s cycle + 20s margin clamps to the 30s floor");

        c.general_updateInterval = 20 * 60 * 60;  // 极端值：不能让窗口无限放大
        long extreme = (Long) m.invoke(null, c);
        assertEquals(300_000L, extreme, "grace window must be capped at 5 minutes");
    }
}
