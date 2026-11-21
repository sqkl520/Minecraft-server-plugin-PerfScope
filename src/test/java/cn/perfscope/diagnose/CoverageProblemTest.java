/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.diagnose;

import cn.perfscope.history.HistorySample;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * f-s03-logic-04：coverageProblem 的中位间隔。
 *
 * <p>偶数个 gaps 必须取中间两值的均值；旧实现取上中位数 gaps[L/2]，
 * 会把「一半很短、一半很长」的窗口误判为覆盖不足（只产生 skip，不产生误报，
 * 故审计标 LOW）。本类同时锁住两个既有拦截，防止修中位数时把它们削弱。</p>
 */
class CoverageProblemTest {

    /**
     * 确保消息层已加载。
     *
     * <p><b>为什么必须显式做这一步</b>：{@code coverageProblem} 内部调用
     * {@code Messages.get()}，而模块键（{@code diag.*} 来自 {@code DiagnoseMessages}）
     * 只有在 {@link Messages#load} 里被合并进来之后才可用。此前本类没做这一步，
     * 于是返回值取决于"同 JVM 里有没有别的测试先调过 load()"——本地 surefire 的类扫描顺序
     * 下恰好没人调过，返回的是键名，断言 {@code assertEquals("diag.skip.coverage-samples", …)}
     * 侥幸通过；CI 上顺序不同，先加载了消息层，返回的是英文文案，测试就红了。
     *
     * <p>这类"执行顺序决定成败"的测试是最糟的一种：它绿的时候什么都没证明，
     * 红的时候看起来又像是代码坏了。</p>
     */
    @org.junit.jupiter.api.BeforeAll
    static void loadMessages() {
        cn.perfscope.config.ConfigManager cm = new cn.perfscope.config.ConfigManager(null);
        cn.perfscope.config.ConfigManager.ConfigData cfg = new cn.perfscope.config.ConfigManager.ConfigData();
        cfg.general_language = "en";
        TestObjects.setField(cn.perfscope.config.ConfigManager.class, cm, "config", cfg);

        cn.perfscope.PerfScope plugin = TestObjects.allocate(cn.perfscope.PerfScope.class);
        java.util.logging.Logger log = java.util.logging.Logger.getLogger("pscope-coverage-problem");
        log.setUseParentHandlers(false);
        log.setLevel(java.util.logging.Level.OFF);
        TestObjects.setField(cn.perfscope.PerfScope.class, plugin, "logger", log);
        TestObjects.setField(cn.perfscope.PerfScope.class, plugin, "configManager", cm);

        // 顺序很重要，两个都不能省：
        //   1. init() 触发 DiagnoseMessages 的 static 块 → registerModule 把 diag.* 键写进模块表
        //   2. load() 把模块表合并进当前快照
        // 只做 load() 是不够的：模块注册走的是 Messages.onFirstLoad(...)，那个回调**只在第一次
        // load 时执行**。如果同 JVM 里别的测试已经先 load 过，这里的 load 不会再触发回调，
        // diag.* 键就永远不在快照里，Messages.get 一路返回键名。
        // 这正是本类此前"本地绿、CI 红"的成因——不是代码坏了，是测试依赖了执行顺序。
        cn.perfscope.diagnose.DiagnoseMessages.init();
        cn.perfscope.util.Messages.load(plugin);
    }

    private static String coverage(List<Long> gaps, long windowMs, int minSamples) throws Exception {
        List<HistorySample> w = new ArrayList<>();
        long t = 1_700_000_000_000L;
        w.add(new HistorySample(t, 20.0, 8.0, 50, 1, 10, 100));
        for (long g : gaps) {
            t += g;
            w.add(new HistorySample(t, 20.0, 8.0, 50, 1, 10, 100));
        }
        Method m = CoreRules.class.getDeclaredMethod("coverageProblem",
            List.class, long.class, int.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, w, windowMs, minSamples, "history");
    }

    private static List<Long> gaps(long... values) {
        List<Long> out = new ArrayList<>();
        for (long v : values) out.add(v);
        return out;
    }

    @Test
    void skewedWindowPassesWithTrueMedian() throws Exception {
        // 1,1,1,100,100,100 秒 -> 真中位数 (1+100)/2=50.5s，旧上中位数=100s
        assertNull(coverage(gaps(1_000L, 1_000L, 1_000L, 100_000L, 100_000L, 100_000L),
            30 * 60_000L, 5));
    }

    @Test
    void samplesBelowMinimumStillSkip() throws Exception {
        // gaps(60s, 60s) 产生 **3** 个样本：coverage() 先放一个首样本，每条 gap 再加一个。
        // 下限是 5，所以报 "3/5"。
        String out = coverage(gaps(60_000L, 60_000L), 30 * 60_000L, 5);
        assertNotNull(out, "样本数不足必须产生 skip，而不是放过");
        assertTrue(out.contains("3/5"),
            "必须报出实际样本数与下限（3/5），实际是：" + out);
        assertTrue(out.contains("samples") || out.contains("样本"),
            "必须说明是样本数不足（而非覆盖跨度），实际是：" + out);
    }

    @Test
    void oneSecondClusterStillSkip() throws Exception {
        // 7 个样本、间隔 1 秒 —— 条数够（7 ≥ 5），但首尾只跨 6 秒，覆盖不足。
        // 这条钉的是"两个判据是分开的"：不能因为它条数够就放过。
        String out = coverage(gaps(1_000L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L),
            30 * 60_000L, 5);
        assertNotNull(out, "覆盖跨度不足必须产生 skip");
        assertTrue(out.contains("coverage") || out.contains("覆盖"),
            "必须说明是覆盖跨度不足（而非样本数），实际是：" + out);
        // 不能报成"样本不足"——那是另一条判据，混淆会让服主朝错误方向排查
        assertFalse(out.contains("samples insufficient") || out.contains("样本不足"),
            "跨度不足不能被报成样本不足，实际是：" + out);
    }

    @Test
    void evenlySpacedWindowStillPasses() throws Exception {
        assertNull(coverage(gaps(60_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L,
            60_000L, 60_000L, 60_000L), 30 * 60_000L, 10));
    }
}
