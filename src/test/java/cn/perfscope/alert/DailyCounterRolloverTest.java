/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A3 回归测试（f-s01-data-05-r1）：时区切换修复不得吞掉真实的跨天归零。
 *
 * <p>背景：收尾批为修「时区切换被误判为跨日」在 {@code loadOptions()} 末尾写了
 * <b>无条件</b> {@code pushedTodayDayEpoch = currentDayEpoch()}。但 {@code loadOptions()}
 * 在每次 {@code /perfscope reload} 都执行，而 {@code rollDay()} 只在推送与 getter 触发——
 * 于是「跨过午夜 → 尚未推送 → 管理员 reload」这个窗口里，昨天→今天的跳变永远观测不到，
 * 四个当日计数器跨天累积。第五波次验收实测旧实现得 8（正确值 1），故此条锁死。喵
 */
class DailyCounterRolloverTest {

    private static final ZoneId SYS = ZoneId.systemDefault();

    @BeforeAll
    static void initMessages() throws Exception {
        Class.forName("cn.perfscope.alert.AlertMessages").getMethod("init").invoke(null);
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

    private static int getInt(Class<?> cls, Object t, String n) throws Exception {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                return f.getInt(t);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(n);
    }

    /** 造一个 AlertService：昨天已推 7 条，时间已跨到今天，尚未发生任何 rollDay。 */
    private static AlertService serviceWithYesterdayCounters() throws Exception {
        File df = Files.createTempDirectory("pscope-rollover").toFile();
        Files.write(new File(df, "config.yml").toPath(),
            "alerts:\n  daily-summary:\n    timezone: \"\"\n".getBytes(StandardCharsets.UTF_8));

        PerfScope p = (PerfScope) alloc(PerfScope.class);
        setField(PerfScope.class, p, "logger", Logger.getLogger("rollover-test"));
        setField(PerfScope.class, p, "dataFolder", df);

        ConfigManager cm = (ConfigManager) alloc(ConfigManager.class);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.alerts_enabled = true;
        cfg.alerts_debounceCycles = 3;
        cfg.general_updateInterval = 20;
        cfg.alerts_cooldownSeconds = 300;
        setField(ConfigManager.class, cm, "plugin", p);
        setField(ConfigManager.class, cm, "config", cfg);
        setField(PerfScope.class, p, "configManager", cm);

        AlertService svc = (AlertService) alloc(AlertService.class);
        setField(AlertService.class, svc, "plugin", p);
        setField(AlertService.class, svc, "configManager", cm);
        setField(AlertService.class, svc, "options", defaultOptions());

        long today = LocalDate.now(SYS).toEpochDay();
        setField(AlertService.class, svc, "pushedTodayDayEpoch", today - 1);
        setField(AlertService.class, svc, "pushedToday", 7);
        setField(AlertService.class, svc, "pushOkToday", 5);
        setField(AlertService.class, svc, "pushFailToday", 2);
        setField(AlertService.class, svc, "webhookQueuedToday", 4);
        return svc;
    }

    private static Object defaultOptions() throws Exception {
        Class<?> oc = Class.forName("cn.perfscope.alert.AlertService$Options");
        Method d = oc.getDeclaredMethod("defaults");
        d.setAccessible(true);
        Object o = d.invoke(null);
        setField(oc, o, "digestMode", false);
        setField(oc, o, "recoveryDebounceCycles", 3);
        setField(oc, o, "dailyTimezone", "");
        return o;
    }

    private static void invokeLoadOptions(AlertService svc) throws Exception {
        Method m = AlertService.class.getDeclaredMethod("loadOptions");
        m.setAccessible(true);
        m.invoke(svc);
    }

    private static void invokeCountPushed(AlertService svc) throws Exception {
        Method m = AlertService.class.getDeclaredMethod("countPushed");
        m.setAccessible(true);
        m.invoke(svc);
    }

    @Test
    void reloadRightAfterMidnightStillRollsOver() throws Exception {
        AlertService svc = serviceWithYesterdayCounters();
        // reload（时区规格未变：仍是空串 = 跟随系统时区）
        invokeLoadOptions(svc);
        invokeCountPushed(svc);
        assertEquals(1, getInt(AlertService.class, svc, "pushedToday"),
            "cross-midnight reload must not swallow the day rollover (old bug yielded 8)");
    }

    @Test
    void repeatedReloadsStillRollOver() throws Exception {
        AlertService svc = serviceWithYesterdayCounters();
        invokeLoadOptions(svc);
        invokeLoadOptions(svc);
        invokeLoadOptions(svc);
        invokeCountPushed(svc);
        assertEquals(1, getInt(AlertService.class, svc, "pushedToday"),
            "repeated reloads must be idempotent w.r.t. the day baseline");
    }

    @Test
    void reloadWithoutDayChangeKeepsAccumulating() throws Exception {
        AlertService svc = serviceWithYesterdayCounters();
        long today = LocalDate.now(SYS).toEpochDay();
        setField(AlertService.class, svc, "pushedTodayDayEpoch", today);
        setField(AlertService.class, svc, "pushedToday", 2);
        invokeLoadOptions(svc);
        invokeCountPushed(svc);
        assertEquals(3, getInt(AlertService.class, svc, "pushedToday"),
            "same-day reload must not reset the counter");
    }

    @Test
    void timezoneChangeDoesNotClearCounters() throws Exception {
        ZoneId far = zoneOnDifferentDate();
        org.junit.jupiter.api.Assumptions.assumeTrue(far != null,
            "no timezone currently on a different calendar date");

        AlertService svc = serviceWithYesterdayCounters();
        long today = LocalDate.now(SYS).toEpochDay();
        setField(AlertService.class, svc, "pushedTodayDayEpoch", today);
        setField(AlertService.class, svc, "pushedToday", 3);
        // 把配置时区改成「此刻不在同一天」的时区 → 基线重算，但计数器不得清零
        Object opts = defaultOptions();
        setField(opts.getClass(), opts, "dailyTimezone", far.getId());
        setField(AlertService.class, svc, "options", opts);

        invokeLoadOptions(svc);
        invokeCountPushed(svc);
        // 计数器保留 3（未被清零），本次推送 +1 -> 4。若被误判为跨天，结果会是 1。
        assertEquals(4, getInt(AlertService.class, svc, "pushedToday"),
            "a timezone switch is not a day rollover; counters must be preserved (3 -> 4, not reset to 1)");
    }

    private static ZoneId zoneOnDifferentDate() {
        LocalDate base = LocalDate.now(SYS);
        for (String id : ZoneId.getAvailableZoneIds()) {
            try {
                ZoneId z = ZoneId.of(id);
                if (!LocalDate.now(z).equals(base)) {
                    return z;
                }
            } catch (RuntimeException ignored) {
                // 无法解析的时区 id：跳过
            }
        }
        return null;
    }
}
