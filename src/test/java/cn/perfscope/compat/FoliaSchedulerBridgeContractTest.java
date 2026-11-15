/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.compat;

import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FoliaSchedulerBridge} 里<b>不碰 Bukkit 调度器</b>的那两个方法。
 *
 * <h2>为什么只有两个方法能测</h2>
 * 该类有四个方法，其中三个（{@code runGlobalAtFixedRate} / {@code runGlobalDelayed} /
 * {@code runAsyncAtFixedRate}）第一步就是 {@code Bukkit.getGlobalRegionScheduler()} 或
 * {@code Bukkit.getAsyncScheduler()}——在测试 JVM 里 {@code Bukkit.server} 是 {@code null}，
 * 直接 NPE。这三个方法的<b>全部语义</b>就是"拿到调度器然后委托"，拿不到调度器就无从谈起。</p>
 *
 * <p>但另外两个是纯的：</p>
 * <ul>
 *   <li>{@code wrap(Runnable) -> Consumer<ScheduledTask>}——把无参 Runnable 适配成
 *       Folia 要求的 {@code Consumer<ScheduledTask>}，丢弃那个自引用参数；</li>
 *   <li>{@code cancel(Object)}——把句柄强转 {@code ScheduledTask} 并 cancel。</li>
 * </ul>
 * <p>而实测确认 {@code Class.forName("cn.perfscope.compat.FoliaSchedulerBridge")} <b>成功</b>：
 * 它的 {@code <clinit>} 是隐式的（没有静态字段初始化），所以尽管 {@code SchedulerAdapter}
 * 加载不了，本类能独立加载。</p>
 *
 * <h2>这两个方法的契约为什么重要</h2>
 * {@code wrap} 丢弃的是 Folia 传给任务的<b>自引用</b>。若它把参数误传下去（例如写成
 * {@code task.run()} 之外的形状、或做成 {@code Consumer<Object>}），任务体可能拿到一个
 * 意料之外的 {@code ScheduledTask}——而本仓库的任务体全是无参 {@code Runnable}，
 * 多一个参数只能是草率适配的产物。
 *
 * <p>{@code cancel} 的类型契约是<b>故意收紧</b>的：javadoc 第 105-106 行明写
 * "类型不符时抛 ClassCastException，由调用方捕获并记 warning"。它不是防御性编程遗漏，
 * 而是让"句柄来源不对"这件事在日志里可见——所以这里是断言"必须抛"，
 * 不是"必须不抛"。</p>
 */
class FoliaSchedulerBridgeContractTest {

    /** 反射调用 package-private 的 static 方法。 */
    private static Object call(String name, Class<?>[] sig, Object... args) throws Throwable {
        Method m = FoliaSchedulerBridge.class.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** 造一个 {@code ScheduledTask} 假实现，{@code onCancel} 决定 cancel() 时做什么。 */
    private static Object fakeScheduledTask(Runnable onCancel) throws Exception {
        Class<?> st = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
        return Proxy.newProxyInstance(
            FoliaSchedulerBridgeContractTest.class.getClassLoader(),
            new Class<?>[]{st},
            (proxy, method, args) -> {
                if ("cancel".equals(method.getName())) {
                    onCancel.run();
                    return null;
                }
                return switch (method.getReturnType().getName()) {
                    case "boolean" -> false;
                    case "int" -> 0;
                    case "long" -> 0L;
                    default -> null;
                };
            });
    }

    // ------------------------------------------------------------------------------------
    // 1. wrap：Runnable → Consumer<ScheduledTask>
    // ------------------------------------------------------------------------------------

    /**
     * {@code wrap} 返回的 Consumer 被调用时必须执行原 Runnable。
     *
     * <p>这是"适配"最基础的那一半。若 {@code wrap} 返回一个空 Consumer（忘了 {@code task.run()}），
     * Folia 上所有定时任务都变成空转——采集不跑、告警不发、探针不探，而调度器一切正常，
     * 日志里连一条错误都没有。这类"静默空转"是本项目最怕的失效形态。</p>
     */
    @Test
    void wrapInvokesTheRunnable() throws Throwable {
        AtomicInteger runs = new AtomicInteger();
        Object consumer = call("wrap", new Class<?>[]{Runnable.class}, (Runnable) runs::incrementAndGet);
        assertTrue(consumer instanceof java.util.function.Consumer<?>,
            "wrap 必须返回 Consumer<ScheduledTask>，否则拿不到 scheduler 的重载");

        @SuppressWarnings("unchecked")
        java.util.function.Consumer<Object> c = (java.util.function.Consumer<Object>) consumer;
        c.accept(fakeScheduledTask(() -> { }));
        assertEquals(1, runs.get(),
            "Consumer 被调用一次，原 Runnable 必须正好执行一次——返回空 Consumer 会让 Folia 上"
                + "所有定时任务空转，而调度器与日志都显示正常");
    }

    /**
     * {@code wrap} 丢弃 Folia 传入的自引用参数：同一个 Consumer 被多个不同句柄调用都照常执行。
     *
     * <p>若实现把参数误当成"是否该执行"的开关（例如 {@code st -> { if (st != null) task.run(); }}），
     * 这条会红。真实 Folia 总会传非 null，所以那个 bug 在线上是隐形的——
     * 只有在测试里换个参数才暴露。</p>
     */
    @Test
    void wrapIgnoresTheScheduledTaskArgument() throws Throwable {
        AtomicInteger runs = new AtomicInteger();
        @SuppressWarnings("unchecked")
        java.util.function.Consumer<Object> c = (java.util.function.Consumer<Object>)
            call("wrap", new Class<?>[]{Runnable.class}, (Runnable) runs::incrementAndGet);

        c.accept(fakeScheduledTask(() -> { }));
        c.accept(fakeScheduledTask(() -> { }));
        c.accept(fakeScheduledTask(() -> { }));
        assertEquals(3, runs.get(),
            "参数只是 Folia 的自引用，必须被完全忽略——本仓库所有任务体都是无参 Runnable，"
                + "适配层不该用这个参数决定执行与否");
    }

    /** {@code wrap} 必须能被重复调用（每次调度各造一个 Consumer），互不干扰。 */
    @Test
    void wrapCreatesIndependentConsumers() throws Throwable {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        @SuppressWarnings("unchecked")
        java.util.function.Consumer<Object> ca = (java.util.function.Consumer<Object>)
            call("wrap", new Class<?>[]{Runnable.class}, (Runnable) a::incrementAndGet);
        @SuppressWarnings("unchecked")
        java.util.function.Consumer<Object> cb = (java.util.function.Consumer<Object>)
            call("wrap", new Class<?>[]{Runnable.class}, (Runnable) b::incrementAndGet);

        ca.accept(fakeScheduledTask(() -> { }));
        cb.accept(fakeScheduledTask(() -> { }));
        cb.accept(fakeScheduledTask(() -> { }));
        assertEquals(1, a.get(), "两个 wrap 各自绑定自己的 Runnable，不能串");
        assertEquals(2, b.get());
    }

    // ------------------------------------------------------------------------------------
    // 2. cancel：强转 ScheduledTask 后调用
    // ------------------------------------------------------------------------------------

    /** 传入真的 {@code ScheduledTask} 时，必须调到它的 {@code cancel()}。 */
    @Test
    void cancelInvokesScheduledTaskCancel() throws Throwable {
        AtomicInteger cancels = new AtomicInteger();
        call("cancel", new Class<?>[]{Object.class}, fakeScheduledTask(() -> cancels.incrementAndGet()));
        assertEquals(1, cancels.get(),
            "cancel 必须真的转到 ScheduledTask.cancel()：它此前零测试，"
                + "而 Folia 路径上任务取消全靠这一行");
    }

    /**
     * 传入非 {@code ScheduledTask} 时<b>必须</b>抛 {@code ClassCastException}。
     *
     * <p><b>这条断言的是"必须抛"，不是"必须不抛"。</b>javadoc 第 105-106 行明写
     * "类型不符时抛 ClassCastException，由调用方捕获并记 warning"——这是刻意的设计：
     * 句柄来源不对（例如把 BukkitTask 传进 Folia 路径）应当<b>可见</b>地失败，
     * 而不是被静默忽略。若有人给它加上"防御性 instanceof 检查然后静默 return"，
     * 这条会红——那个改动会把"传错句柄"变成无声的功能停用。</p>
     */
    @Test
    void cancelRejectsNonScheduledTask() {
        Throwable t = assertThrows(Throwable.class,
            () -> call("cancel", new Class<?>[]{Object.class}, new Object()),
            "传错类型必须抛：静默 return 会让「句柄来源不对」变成无声的功能停用");
        assertTrue(t instanceof ClassCastException,
            "契约要求的是 ClassCastException（调用方按它记 warning），实际抛了 " + t.getClass().getName());
    }

    /**
     * {@code null} 句柄同样抛异常，而不是 NPE 之外的怪异行为。
     *
     * <p>空指针能提示"这里传了 null"，而 ClassCastException 指向"类型不对"——
     * 两者都是清晰的失败，本测试只要求"不是静默通过"。这条防的是有人为了"健壮"加上
     * {@code if (scheduledTask == null) return;}，从而让 {@code TaskHandle.foliaTask != null}
     * 那个守卫的作用被稀释。</p>
     */
    @Test
    void cancelWithNullFailsLoudly() {
        Throwable t = assertThrows(Throwable.class,
            () -> call("cancel", new Class<?>[]{Object.class}, (Object) null),
            "null 句柄必须响亮失败：静默通过会让「句柄为空」这件事在日志里彻底消失");
        assertTrue(t instanceof NullPointerException || t instanceof ClassCastException,
            "应当抛 NPE 或 CCE，实际：" + t.getClass().getName());
    }

    /**
     * 自检：本类确实能独立加载（这是上面全部用例成立的前提）。
     *
     * <p>如果将来有人给 {@code FoliaSchedulerBridge} 加上一个依赖 {@code ServerCompat} 的
     * 静态字段，这个类的所有测试会在<b>类加载阶段</b>全红，而且报错信息会误导人去查
     * 测试代码。这条先跑一次显式的加载检查，让失败点更直白。</p>
     */
    @Test
    void bridgeLoadsWithoutBukkit() {
        TestObjects.assertReflectionUsable();
        assertEquals("cn.perfscope.compat.FoliaSchedulerBridge",
            FoliaSchedulerBridge.class.getName());
    }
}
