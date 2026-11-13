/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流<b>判定函数</b> {@code WebhookNotifier.admitByRateLimit}（不是间隔参数）。
 *
 * <p><b>与 {@code WebhookRateLimitTest} 的分工</b>：那个类只测 {@code minIntervalMsFor}
 * 的取值，即"每个平台的间隔数字对不对"；把 {@code admitByRateLimit} 整个换成
 * {@code return true}，它仍然全绿——因为判定函数从未被调用。本类补上这一层。</p>
 *
 * <h2>能否在没有活体 HTTP 客户端时调用？——能，且不需要 {@code PerfScope}</h2>
 *
 * <p>读实现可知 {@code admitByRateLimit(nowMs)} 只触摸两个东西：
 * 静态纯函数 {@code minIntervalMsFor(type)} 和实例字段 {@code lastSendAtMs}。
 * 它<b>不读</b> {@code httpClient}、{@code ready}、{@code url}，也不调 {@code plugin}。
 * 因此可以像 {@code AlertService} 那样用 {@code sun.misc.Unsafe.allocateInstance}
 * 拿到实例，只补 {@code type} 与该 {@code AtomicLong}，然后反射调用——
 * 全程无网络、无 Bukkit。{@code httpClient} 只在 {@code sendWithResult} 里被用到，
 * 而那条路径本类完全不进入。</p>
 *
 * <p>（对比：想测 {@code sendWithResult} 本身就必须有真实 HTTP 端点，或者把
 * {@code HttpClient} 做成可注入——后者是生产代码改动，本任务不允许。故本类只覆盖判定层，
 * 并在报告里如实说明这一边界。）</p>
 */
class WebhookAdmitByRateLimitTest {

    @BeforeAll
    static void initMessages() throws Exception {
        // 与夹具共用同一套初始化（含模块文案表合并）；本类不读渲染文案，
        // 但保持与其它新增用例一致，避免"换个类跑就拿到键名"的差异。
        AlertServiceStateMachineFixture.initMessages();
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

    private static Object getField(Class<?> cls, Object t, String n) throws Exception {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                return f.get(t);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(n);
    }

    /** 只装配 {@code admitByRateLimit} 需要的那两个字段。 */
    private static WebhookNotifier notifier(String type) throws Exception {
        WebhookNotifier n = (WebhookNotifier) alloc(WebhookNotifier.class);
        setField(WebhookNotifier.class, n, "type", type);
        // 与真实字段初始化器同值：MIN_VALUE = "从未发送过"
        setField(WebhookNotifier.class, n, "lastSendAtMs", new AtomicLong(Long.MIN_VALUE));
        return n;
    }

    private static boolean admit(WebhookNotifier n, long nowMs) throws Exception {
        Method m = WebhookNotifier.class.getDeclaredMethod("admitByRateLimit", long.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(n, nowMs);
    }

    private static long lastSendAt(WebhookNotifier n) throws Exception {
        return ((AtomicLong) getField(WebhookNotifier.class, n, "lastSendAtMs")).get();
    }

    /**
     * <b>核心用例</b>：间隔内必须拒、间隔外必须放。
     *
     * <p>把 {@code admitByRateLimit} 改成恒 {@code true} 后，第二次调用会返回 true → 本用例红；
     * 改成恒 {@code false} 后，第一次调用返回 false → 同样红。</p>
     */
    @Test
    void rejectsInsideTheIntervalAndAdmitsAfterIt() throws Exception {
        WebhookNotifier n = notifier("dingtalk");   // 4000 ms
        long t0 = 1_700_000_000_000L;

        assertTrue(admit(n, t0), "the very first send must be admitted (lastSendAtMs == MIN_VALUE)");
        assertFalse(admit(n, t0 + 1), "1 ms later is inside the 4000 ms interval, must be dropped");
        assertFalse(admit(n, t0 + 3_999), "3999 ms < 4000 ms, still inside the interval");
        assertTrue(admit(n, t0 + 4_000), "exactly at the interval, the next send is admitted");
    }

    /**
     * 放行必须<b>推进</b> {@code lastSendAtMs}。
     *
     * <p>这行断言专门打"判定看起来对但其实没记住"的实现：一个只做比较、忘记 CAS 的版本
     * 会让每一次调用都放行（因为基准永远停在 MIN_VALUE），而不推进字段的版本在这里暴露。</p>
     */
    @Test
    void admissionStampsTheSendTime() throws Exception {
        WebhookNotifier n = notifier("dingtalk");
        long t0 = 1_700_000_000_000L;
        assertEquals(Long.MIN_VALUE, lastSendAt(n), "precondition: never sent");

        assertTrue(admit(n, t0));
        assertEquals(t0, lastSendAt(n), "a successful admission must record the send time");

        assertFalse(admit(n, t0 + 100));
        assertEquals(t0, lastSendAt(n), "a rejected send must NOT move the baseline");
    }

    /** 被拒的调用不能把基准往前推，否则限流窗口会被无限续期（饿死后续消息）。 */
    @Test
    void rejectionDoesNotExtendTheWindow() throws Exception {
        WebhookNotifier n = notifier("wecom");      // 4000 ms
        long t0 = 1_700_000_000_000L;
        assertTrue(admit(n, t0));
        for (long d = 500; d < 4_000; d += 500) {
            assertFalse(admit(n, t0 + d), "spam inside the window must stay rejected, d=" + d);
        }
        // 基准仍是 t0，所以 t0+4000 一定放行（若被续期到这里会是 false）
        assertTrue(admit(n, t0 + 4_000),
            "rejections must not push the baseline forward, otherwise the window never closes");
    }

    /** {@code minInterval <= 0} 的平台（onebot / 未知 / null）永远放行。 */
    @Test
    void unlimitedPlatformsAlwaysAdmit() throws Exception {
        for (String type : new String[]{"onebot", "unknown", null}) {
            WebhookNotifier n = notifier(type);
            long t0 = 1_700_000_000_000L;
            assertTrue(admit(n, t0), "type=" + type + " has no platform cap");
            assertTrue(admit(n, t0), "type=" + type + " must admit back-to-back sends");
            assertTrue(admit(n, t0 + 1));
        }
    }

    /**
     * 各平台的间隔在判定层真的生效：同一时刻连发，第二次必须被拒。
     *
     * <p>这锁住"判定读的是平台间隔而不是某个固定值"——若有人把 {@code minIntervalMsFor(type)}
     * 写成常量，feishu（700ms）与 discord（2000ms）的行为差异会消失。</p>
     */
    @Test
    void intervalIsPerPlatform() throws Exception {
        long t0 = 1_700_000_000_000L;

        WebhookNotifier feishu = notifier("feishu");      // 700 ms
        assertTrue(admit(feishu, t0));
        assertFalse(admit(feishu, t0 + 699));
        assertTrue(admit(feishu, t0 + 700));

        WebhookNotifier discord = notifier("discord");    // 2000 ms
        assertTrue(admit(discord, t0));
        // 700ms 后放行 feishu 的那个点，对 discord 必须仍是拒绝
        assertFalse(admit(discord, t0 + 700),
            "700 ms is enough for feishu but not for discord; the gate must read the platform interval");
        assertTrue(admit(discord, t0 + 2_000));
    }

    /** 已放行后紧接着再来一次：基准已推进，必须拒（MIN_VALUE 特例只作用于"从未发送"）。 */
    @Test
    void minValueSentinelIsOnlyForTheFirstSend() throws Exception {
        WebhookNotifier n = notifier("dingtalk");
        long t0 = 1_700_000_000_000L;
        assertTrue(admit(n, t0));
        assertFalse(admit(n, t0), "at the same millisecond, the second send must be dropped");
    }

    /** 时间回拨（NTP）：{@code now < last} 时差值巨大为负，必然是"拒绝"。 */
    @Test
    void clockGoingBackwardsRejectsRatherThanFloods() throws Exception {
        WebhookNotifier n = notifier("dingtalk");
        long t0 = 1_700_000_000_000L;
        assertTrue(admit(n, t0));
        // 回拨 10 秒：now - last < interval 成立 → 拒绝；关键是它不能变成"负差 → 放行"
        assertFalse(admit(n, t0 - 10_000),
            "a backwards clock must not be read as 'interval elapsed'");
    }
}
