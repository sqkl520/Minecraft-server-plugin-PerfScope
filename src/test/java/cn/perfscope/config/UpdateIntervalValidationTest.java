/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.config;

import cn.perfscope.PerfScope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code general.update_interval} 的钳制<b>真的执行</b>了（{@link ConfigManager#validateThresholds}）。
 *
 * <p><b>与既有 {@code UpdateIntervalClampTest} 的分工</b>：那个类只反射读常量，
 * 证明"区间定义得对"，但从不调用钳制函数——把 {@code validateThresholds} 里那段 {@code if}
 * 整块删掉，它<b>仍然全绿</b>。本类直接调用私有方法，断言越界值被写回 20。</p>
 *
 * <p>这条为什么值得单独锁：{@code MetricsCollector} 只有 {@code Math.max(1, interval)}
 * 这一道下限，恰好放行最毒的值 {@code update_interval: 1}（每 tick 一次全量采集）。
 * 也就是说常量与 {@code if} 都在的时候才有保护，任一缺失都没有。</p>
 */
class UpdateIntervalValidationTest {

    @BeforeAll
    static void initMessages() throws Exception {
        Class.forName("cn.perfscope.config.ConfigMessages").getMethod("init").invoke(null);
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

    private static int constInt(String name) throws Exception {
        Field f = ConfigManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    /**
     * 造一个能调 {@code validateThresholds} 的 ConfigManager。
     *
     * <p>只需要 {@code plugin}（只为 {@code getLogger()}）——{@code validateThresholds} 的全部
     * 副作用都写在传入的 {@code ConfigData} 上，不读 Bukkit、不写盘。</p>
     */
    private static ConfigManager manager() throws Exception {
        PerfScope p = (PerfScope) alloc(PerfScope.class);
        setField(PerfScope.class, p, "logger", Logger.getLogger("pscope-update-interval-test"));
        ConfigManager cm = (ConfigManager) alloc(ConfigManager.class);
        setField(ConfigManager.class, cm, "plugin", p);
        return cm;
    }

    /** 一份"其余项全部合法"的配置，保证断言只反映 update_interval 这一项。 */
    private static ConfigManager.ConfigData validConfig(int interval) {
        ConfigManager.ConfigData c = new ConfigManager.ConfigData();
        c.general_updateInterval = interval;
        c.general_language = "en";
        c.tps_warningThreshold = 18.0;
        c.tps_criticalThreshold = 15.0;
        c.mspt_warningThreshold = 45.0;
        c.mspt_criticalThreshold = 50.0;
        c.players_warningThreshold = 90;
        c.players_criticalThreshold = 98;
        c.entities_totalWarningThreshold = 20000;
        c.entities_totalCriticalThreshold = 30000;
        c.chunks_loadedWarningThreshold = 10000;
        c.chunks_loadedCriticalThreshold = 15000;
        c.memory_heapWarningThreshold = 85;
        c.memory_heapCriticalThreshold = 95;
        c.cpu_processWarningThreshold = 80.0;
        c.cpu_systemWarningThreshold = 90.0;
        c.cpu_hysteresisMargin = 5.0;
        c.alerts_cooldownSeconds = 300;
        c.alerts_debounceCycles = 3;
        c.alerts_dailySummaryTime = "08:00";
        c.alerts_webhookType = "discord";
        c.history_sampleIntervalSeconds = 60;
        c.history_persistRetentionDays = 7;
        c.hotspots_sampleEveryCycles = 30;
        c.hotspots_chunkWarningThreshold = 200;
        c.hotspots_scanShards = 10;
        c.prometheus_port = 8940;
        c.prometheus_path = "/metrics";
        return c;
    }

    private static void validate(ConfigManager cm, ConfigManager.ConfigData cfg) throws Exception {
        Method m = ConfigManager.class.getDeclaredMethod("validateThresholds",
            ConfigManager.ConfigData.class);
        m.setAccessible(true);
        m.invoke(cm, cfg);
    }

    /** 低于下限（1 tick = 每 tick 全量采集）→ 必须被写成 20。 */
    @Test
    void belowMinimumIsClampedToTwenty() throws Exception {
        ConfigManager cm = manager();
        ConfigManager.ConfigData cfg = validConfig(1);
        validate(cm, cfg);
        assertEquals(20, cfg.general_updateInterval,
            "update_interval=1 means 20 full collections/second; it must be clamped to 20");
    }

    @Test
    void zeroAndNegativeAreClampedToTwenty() throws Exception {
        ConfigManager cm = manager();
        ConfigManager.ConfigData cfg = validConfig(0);
        validate(cm, cfg);
        assertEquals(20, cfg.general_updateInterval);

        ConfigManager.ConfigData neg = validConfig(-100);
        validate(cm, neg);
        assertEquals(20, neg.general_updateInterval);
    }

    /** 高于上限 → 同样回退 20。 */
    @Test
    void aboveMaximumIsClampedToTwenty() throws Exception {
        ConfigManager cm = manager();
        ConfigManager.ConfigData cfg = validConfig(1201);
        validate(cm, cfg);
        assertEquals(20, cfg.general_updateInterval,
            "update_interval above the max must fall back to the documented default");
    }

    /** 合法区间内的值必须原样保留——钳制不能变成"一律改成 20"。 */
    @Test
    void valuesInsideRangeArePreserved() throws Exception {
        ConfigManager cm = manager();
        int min = constInt("UPDATE_INTERVAL_MIN");
        int max = constInt("UPDATE_INTERVAL_MAX");
        for (int v : new int[]{min, min + 1, 20, 100, max}) {
            ConfigManager.ConfigData cfg = validConfig(v);
            validate(cm, cfg);
            assertEquals(v, cfg.general_updateInterval,
                "an in-range update_interval must not be rewritten (value=" + v + ")");
        }
    }

    /** 边界值本身合法（区间闭区间）。 */
    @Test
    void boundaryValuesAreLegal() throws Exception {
        ConfigManager cm = manager();
        ConfigManager.ConfigData lo = validConfig(constInt("UPDATE_INTERVAL_MIN"));
        validate(cm, lo);
        assertEquals(constInt("UPDATE_INTERVAL_MIN"), lo.general_updateInterval,
            "the min bound itself must be legal");

        ConfigManager.ConfigData hi = validConfig(constInt("UPDATE_INTERVAL_MAX"));
        validate(cm, hi);
        assertEquals(constInt("UPDATE_INTERVAL_MAX"), hi.general_updateInterval,
            "the max bound itself must be legal");
    }

    /**
     * 相邻键不得被误改：钳制只动越界的那一项。
     *
     * <p>没有这一条，一个"把整份配置重置成默认"的错误实现也能过上面几条断言。</p>
     */
    @Test
    void clampDoesNotDisturbOtherFields() throws Exception {
        ConfigManager cm = manager();
        ConfigManager.ConfigData cfg = validConfig(1);
        cfg.alerts_cooldownSeconds = 77;
        cfg.tps_warningThreshold = 17.5;
        validate(cm, cfg);
        assertEquals(20, cfg.general_updateInterval);
        assertEquals(77, cfg.alerts_cooldownSeconds, "cooldown must be untouched by the interval clamp");
        assertEquals(17.5, cfg.tps_warningThreshold, 1e-9);
    }
}
