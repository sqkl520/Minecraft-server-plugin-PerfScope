/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import cn.perfscope.config.ConfigManager;
import cn.perfscope.support.ConfigFixtures;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetricsCollector} 的「采集是否还活着」查询——{@code isStale()} / {@code isJvmStale()}。
 *
 * <h2>为什么这两条值得单独测</h2>
 * 它们回答的是「插件现在还能拿到新数据吗」。答错的两种方向后果完全不同：
 *
 * <ul>
 *   <li><b>该报 stale 却报健康</b>：采集任务被静默取消，{@code getLatest()} 仍返回历史里最后一张
 *       快照，命令层照常渲染"正常"——服主看到的是一份冻结的性能数据，还以为服务器健康。
 *       这正是本项目最怕的"把盲点报成健康"。</li>
 *   <li><b>不该报却报 stale</b>：每次启动先红一次，服主学会无视这条警告——
 *       真出事时那条也被无视了。</li>
 * </ul>
 *
 * <p>而这两条各自有个容易漏的分支：<b>从未采集过时</b>要退回 {@code startedAtMs} 而不是
 * 直接算"已过期 1970 年至今"。忘掉它，插件每次启动都会立刻报"数据停止更新 56 年"。</p>
 *
 * <h2>为什么能测</h2>
 * 两个方法只读 volatile 字段与 {@code configManager.get()}，不碰 Bukkit。
 * {@code ConfigManager} 的构造器只赋字段（{@code new ConfigManager(null)} 可构造），
 * {@code MetricsCollector} 则需要 {@code allocateInstance} 绕过构造器——它的构造器解引用
 * {@code plugin.getConfigManager()} 并触发 {@code ServerCompat} 静态初始化。
 *
 * <p>所以本类全部走"造壳 + 注入字段"的路子，与 {@code CpuHysteresisLatchTest} 同一模式。</p>
 */
class StalenessQueryTest {

    /** 与生产代码 {[@code WARMUP_WINDOW_MS} 同值；用反射读，改了会在这里显示出来。 */
    private static long warmupMs() {
        return (Long) TestObjects.getField(MetricsCollector.class, null, "WARMUP_WINDOW_MS");
    }

    /**
     * 造一个只填了 staleness 相关字段的 collector。
     *
     * <p>为什么用 {@code allocate}：{@code MetricsCollector} 的构造器会
     * {@code plugin.getConfigManager()} 并触发 {@code ServerCompat} 的静态初始化，
     * 在测试 JVM 里必然抛 {@code ExceptionInInitializerError}（见 {@code TestObjects} 类注释二）。
     * 造壳后所有字段都是默认值，下面逐个注入。</p>
     */
    private static MetricsCollector collector(long lastJvmCollectAtMs, long startedAtMs) {
        MetricsCollector mc = TestObjects.allocate(MetricsCollector.class);
        ConfigManager cm = ConfigFixtures.manager("pscope-staleness-test");
        TestObjects.setField(MetricsCollector.class, mc, "configManager", cm);
        TestObjects.setField(MetricsCollector.class, mc, "lastJvmCollectAtMs", lastJvmCollectAtMs);
        TestObjects.setField(MetricsCollector.class, mc, "startedAtMs", startedAtMs);
        // lastCollectAtMs 保持 0（"从未采集"）——需要别的值时由各用例单独 setField。
        // jvmCache 同样保持 null：jvmSnapshotTimestamp() 必须能容忍这一点。
        return mc;
    }

    private static MetricsCollector collectorWithJvmLast(long lastJvmMs) {
        return collector(lastJvmMs, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------------------------
    // 1. staleThresholdMs 的算式
    // ------------------------------------------------------------------------------------

    /**
     * {@code staleThresholdMs = max(3000, intervalMs × 3)}。
     *
     * <p>下界 3000ms（3 秒）是为了防 {@code update_interval: 1} 时 150ms 的过敏判定——
     * 20 tick/s 的服务端在 150ms 窗口里偶发一次调度抖动就会报"采集已停止"。
     * 若有人去掉这道下界，那台服务器会开始刷假的 stale 警告。</p>
     */
    @Test
    void staleThresholdHasAThreeSecondFloor() {
        MetricsCollector mc = collector(0L, System.currentTimeMillis());
        ConfigManager cm = (ConfigManager) TestObjects.getField(
            MetricsCollector.class, mc, "configManager");
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.general_updateInterval = 1;              // 1 tick → 50ms × 3 = 150ms
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);

        assertEquals(3000L, mc.staleThresholdMs(),
            "update_interval=1 时算式给 150ms，必须被 3000ms 的下界托住——"
                + "否则 20 tick/s 的服务端偶发一次调度抖动就报「采集已停止」");
    }

    /** 正常间隔下算 3 倍间隔。 */
    @Test
    void staleThresholdIsThreeIntervals() {
        MetricsCollector mc = collector(0L, System.currentTimeMillis());
        ConfigManager cm = (ConfigManager) TestObjects.getField(
            MetricsCollector.class, mc, "configManager");
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.general_updateInterval = 100;            // 100 tick → 5000ms × 3 = 15000ms
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);

        assertEquals(15_000L, mc.staleThresholdMs(),
            "100 tick = 5000ms，三倍即 15000ms");
    }

    /**
     * {@code configManager.get()} 返回 {@code null} 时退回 20 tick 而不是 NPE。
     *
     * <p>{@code ConfigManager.get()} 的契约明写"可能返回 null"（构造期到首次 load 成功之间）。
     * 若 {@code staleThresholdMs} 直接解引用，看门狗在启动早期会抛 NPE——
     * 而那个异常被 {@code checkCollectorHealth} 吞成一条 warning，表现为"看门狗不工作"。</p>
     */
    @Test
    void staleThresholdSurvivesNullConfig() {
        MetricsCollector mc = collector(0L, System.currentTimeMillis());
        ConfigManager cm = (ConfigManager) TestObjects.getField(
            MetricsCollector.class, mc, "configManager");
        TestObjects.setField(ConfigManager.class, cm, "config", null);

        assertEquals(3000L, mc.staleThresholdMs(),
            "config 为 null 时按 20 tick 算（1000ms×3=3000，恰好压在下界上）——"
                + "不许 NPE，否则看门狗在启动早期静默失效");
    }

    // ------------------------------------------------------------------------------------
    // 2. isJvmStale：从未采集过时退回 startedAtMs
    // ------------------------------------------------------------------------------------

    /**
     * <b>从未 JVM 采集过、且刚启动</b> → 不是 stale。
     *
     * <p>这是最容易漏的分支。若实现写成 {@code now - lastJvmCollectAtMs > threshold} 而
     * {@code lastJvmCollectAtMs == 0}，那么 {@code now - 0} 是"自 1970 年以来的毫秒数"，
     * 恒大于任何阈值——插件每次启动都会立刻报"JVM 指标已停止更新 56 年"。</p>
     */
    @Test
    void freshlyStartedCollectorIsNotJvmStale() {
        MetricsCollector mc = collector(0L, System.currentTimeMillis());
        assertFalse(mc.isJvmStale(),
            "刚启动、还没采过 JVM 时不得报 stale：忘掉 startedAtMs 兜底会让"
                + "每次启动都报「JVM 指标已停止更新 56 年」");
    }

    /**
     * 从未采集过、且启动已超过宽限期 → 是 stale。
     *
     * <p>另一面：不能因为"从没采过"就永远说健康。异步任务若一开始就没起来，
     * 十秒后必须报出来——否则内存/CPU/GC 永远是 0 而界面显示正常。</p>
     */
    @Test
    void neverCollectedButPastWarmupIsJvmStale() {
        long longAgo = System.currentTimeMillis() - warmupMs() - 60_000L;
        MetricsCollector mc = collector(0L, longAgo);
        assertTrue(mc.isJvmStale(),
            "启动超过宽限期仍未采到 JVM 数据，必须报 stale——"
                + "否则异步任务一开始就没起来时，内存/CPU/GC 停在 0 而界面显示正常");
    }

    /** 刚采过 → 不 stale。 */
    @Test
    void recentlyCollectedIsNotJvmStale() {
        MetricsCollector mc = collectorWithJvmLast(System.currentTimeMillis());
        assertFalse(mc.isJvmStale());
    }

    /** 很久没采 → stale。 */
    @Test
    void longSilentCollectorIsJvmStale() {
        MetricsCollector mc = collectorWithJvmLast(
            System.currentTimeMillis() - warmupMs() - 60_000L);
        assertTrue(mc.isJvmStale(),
            "JVM 异步节拍停摆必须报出来——主线程仍每秒产快照，isStale() 看不出来，"
                + "症状是内存/CPU/GC 永远停在最后一次的数值");
    }

    /**
     * 宽限期与 stale 阈值取<b>较大者</b>，不是只比宽限期。
     *
     * <p>{@code Math.max(WARMUP_WINDOW_MS, staleThresholdMs())}。若有人把 max 改成 min 或
     * 直接只用宽限期，那么 {@code update_interval: 100}（stale 阈值 15 秒）的服务器会在
     * 10 秒后就报 stale——比它自己的采集节拍还急，每轮都误报一次。</p>
     */
    @Test
    void jvmStaleUsesTheLargerOfWarmupAndThreshold() {
        MetricsCollector mc = collector(0L, System.currentTimeMillis() - warmupMs() - 1_000L);
        ConfigManager cm = (ConfigManager) TestObjects.getField(
            MetricsCollector.class, mc, "configManager");
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.general_updateInterval = 100;            // stale 阈值 15000ms > 宽限 10000ms
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);

        assertFalse(mc.isJvmStale(),
            "启动 11 秒、采集间隔 100 tick（阈值 15 秒）时不该报 stale——"
                + "取 max 而非 min，否则比采集节拍还急，每轮误报");
    }

    // ------------------------------------------------------------------------------------
    // 3. 主线程节拍 isStale：与 JVM 节拍是两件事
    // ------------------------------------------------------------------------------------

    /** 从未采集过、刚启动 → 不 stale（与 isJvmStale 同款兜底）。 */
    @Test
    void freshlyStartedCollectorIsNotMainStale() {
        MetricsCollector mc = collector(0L, System.currentTimeMillis());
        assertFalse(mc.isStale(),
            "刚启动时不得报 stale——忘掉 startedAtMs 兜底会让每次启动都报「数据停止更新 56 年」");
    }

    /** 从未采集过、启动已超宽限期 → stale。 */
    @Test
    void neverCollectedMainPastWarmupIsStale() {
        MetricsCollector mc = collector(0L, System.currentTimeMillis() - warmupMs() - 60_000L);
        assertTrue(mc.isStale(),
            "启动超过宽限期仍未成功采集过一次，必须报 stale");
    }

    /**
     * <b>两条节拍互相独立</b>：主线程新、JVM 旧 → 主线程健康但 JVM stale。
     *
     * <p>这是这两个方法<b>都要存在</b>的全部理由。若有人把它们合并成一个（"采集还活着吗"），
     * 异步任务被静默取消时主线程仍每秒产快照，合并后的判定会说"健康"——
     * 而内存/CPU/GC 已经冻结。这条用例把那个合并挡住。</p>
     */
    @Test
    void theTwoBeatsAreIndependent() {
        MetricsCollector mc = collector(
            System.currentTimeMillis() - warmupMs() - 60_000L,   // JVM 很久没采
            System.currentTimeMillis());
        TestObjects.setField(MetricsCollector.class, mc, "lastCollectAtMs",
            System.currentTimeMillis());                          // 主线程刚采过

        assertFalse(mc.isStale(), "主线程刚采过，应当健康");
        assertTrue(mc.isJvmStale(),
            "JVM 节拍是独立任务，它停摆时主线程仍然每秒产快照——"
                + "若把两条判定合并成一个，这里会误报健康，而内存/CPU/GC 已冻结");
    }

    // ------------------------------------------------------------------------------------
    // 4. jvmSnapshotTimestamp：jvmCache 为 null 时返回 0
    // ------------------------------------------------------------------------------------

    /**
     * 从未产出 JVM 快照时返回 {@code 0}（"从未"），不 NPE。
     *
     * <p>这个 0 会被 {@code PerfScope.clockOf(0)} 渲染成"从未"文案。
     * 若返回 {@code System.currentTimeMillis()} 之类的近似值，看门狗会报出一个
     * 看起来像"刚刚"的时刻，掩盖了"从来没采到过"这个事实。</p>
     */
    @Test
    void jvmSnapshotTimestampIsZeroWhenNoSnapshotYet() {
        MetricsCollector mc = collector(0L, System.currentTimeMillis());
        assertEquals(0L, mc.jvmSnapshotTimestamp(),
            "从未产出 JVM 快照时返回 0（渲染为「从未」）——"
                + "返回一个近似「现在」的时刻会掩盖「从来没采到过」");
    }

    /** 有 JVM 快照时返回该快照的 timestamp，而不是"最后一次写入时刻"。 */
    @Test
    void jvmSnapshotTimestampComesFromTheSnapshotItself() {
        MetricsCollector mc = collector(0L, System.currentTimeMillis());
        MetricsSnapshot jvm = new MetricsSnapshot();
        jvm.timestamp = 1_700_000_000_000L;
        TestObjects.setField(MetricsCollector.class, mc, "jvmCache", jvm);
        assertEquals(1_700_000_000_000L, mc.jvmSnapshotTimestamp(),
            "要的是快照自己的 timestamp——它是「这份数据是哪一刻的」，"
                + "而不是「缓存什么时候被写入的」");
    }
}
