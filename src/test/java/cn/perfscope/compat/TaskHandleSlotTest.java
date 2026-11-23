/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.compat;

import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskHandleSlot} 的启停裁决——P0-1 的靶点测试。
 *
 * <h2>这条测试在证明什么</h2>
 *
 * <p>修前的形状是四个服务各自的普通字段 + {@code start()} 首行 {@code stop()}：</p>
 *
 * <pre>
 * private SchedulerAdapter.TaskHandle timerHandle;     // 非 volatile
 * public void start() { stop(); ... timerHandle = runGlobalTimer(...); }
 * public void stop()  { if (timerHandle != null) { timerHandle.cancel(); timerHandle = null; } }
 * </pre>
 *
 * <p>两个线程同时 reload 时，{@code start()} 的"读-判-写"会交错成两个定时器并存、字段只记得
 * 后一个，先建的从此无人取消（告警推两次、冷却表双重推进）。本类把这条竞态压成三条可断言的性质：</p>
 *
 * <ol>
 *   <li><b>安装唯一</b>：N 个线程并发 {@link TaskHandleSlot#start}，恰好一个句柄活下来；</li>
 *   <li><b>不泄漏</b>：输家建出来的任务必须被<b>自己取消</b>（{@code created == canceled + 1}）；</li>
 *   <li><b>取消唯一</b>：N 个线程并发 {@link TaskHandleSlot#stop}，只有一个真的抢到句柄。</li>
 * </ol>
 *
 * <h2>为什么能脱离 Bukkit 跑</h2>
 *
 * <p>{@link SchedulerAdapter} 的 {@code <clinit>} 读 {@link ServerCompat#isFolia()}，在测试 JVM 里
 * 必抛 {@code ExceptionInInitializerError}（见 {@code tools/KNOWN-TEST-LIMITS.md} 第 1 条）。
 * 但 JLS 12.4.1 只初始化<b>超类与超接口</b>，不初始化被引用的类——{@code SchedulerAdapter$TaskHandle}
 * 自身没有任何静态字段初始化，因此能独立加载；{@code BukkitTask} 是接口，可用 {@link Proxy} 造假。
 * 这条路径已由 {@code TaskHandleContractTest} 实测验证，本类沿用同一套夹具。</p>
 *
 * <p><b>为什么不在这一层测"并发 reload 只产生一个定时器"</b>：那需要真的
 * {@code SchedulerAdapter.runGlobalTimer}，而它一加载就触发上面那个静态初始化失败。
 * 端到端不可行的部分由 {@link cn.perfscope.concurrency.DeferredTaskOwnershipTest} 的源码级断言
 * 补上（四处服务确实改用了本类），本类负责把本类的并发语义钉死。</p>
 */
class TaskHandleSlotTest {

    // ==================== 夹具 ====================

    /**
     * 造一个 {@link SchedulerAdapter.TaskHandle}：装一个 {@link BukkitTask} 代理，
     * {@code cancel()} 被调用时把计数加一。
     */
    private static SchedulerAdapter.TaskHandle liveHandle(AtomicInteger cancelCount) {
        BukkitTask task = (BukkitTask) Proxy.newProxyInstance(
            TaskHandleSlotTest.class.getClassLoader(),
            new Class<?>[] {BukkitTask.class},
            (proxy, method, args) -> {
                if ("cancel".equals(method.getName())) {
                    cancelCount.incrementAndGet();
                    return null;
                }
                return switch (method.getReturnType().getName()) {
                    case "boolean" -> false;
                    case "int" -> 0;
                    case "long" -> 0L;
                    default -> null;
                };
            });
        try {
            Constructor<SchedulerAdapter.TaskHandle> c =
                SchedulerAdapter.TaskHandle.class.getDeclaredConstructor(BukkitTask.class);
            c.setAccessible(true);
            return c.newInstance(task);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build a TaskHandle", e);
        }
    }

    /** noop 句柄（调度失败时 {@code SchedulerAdapter} 的返回值）：{@code isActive() == false}。 */
    private static SchedulerAdapter.TaskHandle noopHandle() {
        try {
            java.lang.reflect.Method m = SchedulerAdapter.TaskHandle.class.getDeclaredMethod("noop");
            m.setAccessible(true);
            return (SchedulerAdapter.TaskHandle) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build a noop handle", e);
        }
    }

    // ==================== 1. 单线程幂等（任务书要求的最低验收） ====================

    /**
     * {@code start()} 调两次只能有一个句柄，且工厂只被调用一次。
     *
     * <p>这条是任务书里"环境做不到并发就先测单线程幂等"的那一条，但它并非次要：
     * 旧实现的"先 stop 再赋值"在单线程下确实幂等，所以真正的退路在下面几条并发用例里。</p>
     */
    @Test
    void startTwiceInstallsExactlyOneHandle() {
        TaskHandleSlot slot = new TaskHandleSlot();
        AtomicInteger cancelCount = new AtomicInteger();
        AtomicInteger factoryCalls = new AtomicInteger();

        SchedulerAdapter.TaskHandle first = slot.start(() -> {
            factoryCalls.incrementAndGet();
            return liveHandle(cancelCount);
        });
        SchedulerAdapter.TaskHandle second = slot.start(() -> {
            factoryCalls.incrementAndGet();
            return liveHandle(cancelCount);
        });

        assertNotNull(first);
        assertSame(first, second, "第二次 start 必须复用已装好的句柄，而不是再建一个");
        assertEquals(1, factoryCalls.get(), "工厂只允许被调用一次：多调一次就是一个多出来的定时器");
        assertEquals(0, cancelCount.get(), "复用路径不得取消任何东西");
        assertSame(first, slot.current());
        assertTrue(slot.isRunning());
    }

    // ==================== 2. 并发安装：只有一个赢家，输家自己收尸 ====================

    /**
     * <b>核心用例</b>：16 个线程同时 {@code start}，最终只有一个句柄活着。
     *
     * <p>mutation 靶点：把 {@link TaskHandleSlot#start} 里的
     * {@code installed.compareAndSet(null, created)} 换成直接 {@code installed.set(created)}，
     * 这条会红——多个线程各装一次，活下来的句柄数 &gt; 1（被覆盖的那些既不活也不死，
     * 没有任何引用能取消它们）。</p>
     *
     * <p>断言 {@code created == canceled + 1} 是"不泄漏"的精确形式：每建一个任务，
     * 要么它是最终那个赢家，要么它被自己取消——没有第三种归宿。</p>
     */
    @Test
    void concurrentStartLeavesExactlyOneLiveHandle() throws Exception {
        int threads = 16;
        TaskHandleSlot slot = new TaskHandleSlot();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicReference<SchedulerAdapter.TaskHandle> firstResult = new AtomicReference<>();
        List<SchedulerAdapter.TaskHandle> results = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        SchedulerAdapter.TaskHandle h = slot.start(() -> {
                            created.incrementAndGet();
                            return liveHandle(cancelled);
                        });
                        if (h != null) {
                            results.add(h);
                            firstResult.compareAndSet(null, h);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "线程池没能全部就绪");
            go.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS), "并发 start 没有在 20 秒内结束");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(slot.isRunning(), "并发安装之后槽位里必须有一个句柄");
        assertEquals(1, created.get() - cancelled.get(),
            "必须恰好剩一个活着的任务：created=" + created.get() + " cancelled=" + cancelled.get()
                + "（差值 > 1 = 有定时器泄漏，它永远不会被 stop() 取消）");
        // 所有线程拿到的"生效句柄"必须是同一个对象——否则调用方以为自己在管不同的任务
        for (SchedulerAdapter.TaskHandle h : results) {
            assertSame(firstResult.get(), h, "每个调用方拿到的都应是同一个生效句柄");
        }
    }

    // ==================== 3. 取消唯一 ====================

    /**
     * 8 个线程同时 {@code stop}：恰好一个返回 true，句柄只被取消一次。
     *
     * <p>旧实现的 {@code if (handle != null) { cancel(); handle = null; }} 会让每个线程都读到
     * 非空句柄并各取消一次。重复 cancel 本身无害，但它掩盖了"谁停的"这个归因，也让
     * "同一句柄被两个 reload 各停一次"这种真实交错无法从日志上看出来。</p>
     */
    @Test
    void concurrentStopHasExactlyOneWinner() throws Exception {
        int threads = 8;
        TaskHandleSlot slot = new TaskHandleSlot();
        AtomicInteger cancelCount = new AtomicInteger();
        slot.start(() -> liveHandle(cancelCount));

        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        if (slot.stop()) {
                            winners.incrementAndGet();
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS), "并发 stop 没有在 20 秒内结束");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get(), "只有一个线程能抢到句柄并取消它");
        assertEquals(1, cancelCount.get(), "同一句柄只能被取消一次");
        assertFalse(slot.isRunning());
    }

    // ==================== 4. noop 绝不安装 ====================

    /**
     * 调度失败（noop 句柄）时不得安装——否则"句柄非空 ⇔ 任务在跑"这条不变量就破了，
     * 而告警/日报的 {@code isActive()} 检查会永远看到一个"已启动"的假象。
     *
     * <p>且失败之后必须还能重试：下一次 {@code start} 仍会调用工厂（不是拿 noop 复用）。</p>
     */
    @Test
    void noopHandleIsNeverInstalledAndCanBeRetried() {
        TaskHandleSlot slot = new TaskHandleSlot();
        AtomicInteger factoryCalls = new AtomicInteger();

        SchedulerAdapter.TaskHandle failed = slot.start(() -> {
            factoryCalls.incrementAndGet();
            return noopHandle();
        });

        assertNotNull(failed, "调度失败要如实返回 noop 句柄，调用方据此打 severe");
        assertFalse(failed.isActive());
        assertFalse(slot.isRunning(), "noop 不是任务：装进槽位会让后续所有 start 被它挡住");
        assertEquals(1, factoryCalls.get());

        AtomicInteger cancelCount = new AtomicInteger();
        SchedulerAdapter.TaskHandle ok = slot.start(() -> {
            factoryCalls.incrementAndGet();
            return liveHandle(cancelCount);
        });
        assertEquals(2, factoryCalls.get(), "失败之后必须能重新安装");
        assertTrue(ok.isActive());
        assertTrue(slot.isRunning());
    }

    // ==================== 5. 边界：空槽位、工厂抛异常 ====================

    @Test
    void stopOnEmptySlotIsANoOp() {
        TaskHandleSlot slot = new TaskHandleSlot();
        assertDoesNotThrow(slot::stop);
        assertFalse(slot.stop(), "没抢到句柄 = false（调用方可据此避免重复打日志）");
        assertFalse(slot.isRunning());
        assertEquals(null, slot.current());
    }

    /**
     * 工厂抛异常时门闩必须释放——否则一次调度异常会让这个服务<b>永久</b>无法再启动
     * （后续每次 start 都在门闩上让位，返回 null，调用方以为"别人正在装"）。
     */
    @Test
    void factoryFailureReleasesTheInstallLatch() {
        TaskHandleSlot slot = new TaskHandleSlot();
        assertThrows(IllegalStateException.class, () -> slot.start(() -> {
            throw new IllegalStateException("模拟调度器抛异常");
        }), "工厂异常必须原样抛出，由调用方记 severe");

        AtomicInteger cancelCount = new AtomicInteger();
        SchedulerAdapter.TaskHandle ok = assertDoesNotThrow(
            () -> slot.start(() -> liveHandle(cancelCount)),
            "工厂失败后门闩必须已释放，否则这个服务再也起不来");
        assertNotNull(ok);
        assertTrue(slot.isRunning());
    }

    /** 停掉之后可以重新启动（reload 的常规路径）。 */
    @Test
    void stopThenStartInstallsAFreshHandle() {
        TaskHandleSlot slot = new TaskHandleSlot();
        AtomicInteger cancelled = new AtomicInteger();
        SchedulerAdapter.TaskHandle first = slot.start(() -> liveHandle(cancelled));
        assertTrue(slot.stop());
        assertEquals(1, cancelled.get());

        SchedulerAdapter.TaskHandle second = slot.start(() -> liveHandle(cancelled));
        assertNotNull(second);
        assertFalse(first == second, "重启必须建一个新句柄，而不是复用已被取消的那个");
        assertTrue(slot.isRunning());
        assertEquals(1, cancelled.get(), "新句柄不能被立刻取消");
    }

    /**
     * 门闩互斥本身：一个线程正在工厂里（尚未返回）时，另一个线程的 start 必须<b>让位</b>，
     * 不能也进工厂——否则两个线程各建一个任务，正是我们要消灭的形状。
     */
    @Test
    void secondStarterYieldsWhileTheFirstIsStillCreating() throws Exception {
        TaskHandleSlot slot = new TaskHandleSlot();
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicBoolean inFactory = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<SchedulerAdapter.TaskHandle> slowResult = new AtomicReference<>();
        AtomicReference<SchedulerAdapter.TaskHandle> fastResult = new AtomicReference<>();

        Thread slow = new Thread(() -> slowResult.set(slot.start(() -> {
            factoryCalls.incrementAndGet();
            inFactory.set(true);
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return liveHandle(cancelled);
        })));
        slow.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS), "第一个线程没能进入工厂");

        // 第一个线程还卡在工厂里：第二次 start 必须直接让位（返回 null），而不是也进工厂
        SchedulerAdapter.TaskHandle yielded = slot.start(() -> {
            factoryCalls.incrementAndGet();
            return liveHandle(cancelled);
        });
        assertTrue(yielded == null, "安装区段必须串行化：第二个人让位，不是也建一个");
        assertEquals(1, factoryCalls.get(), "让位路径不得调用工厂");

        release.countDown();
        slow.join(10_000);
        assertNotNull(slowResult.get());
        assertEquals(1, factoryCalls.get());
        assertTrue(slot.isRunning());
        assertTrue(inFactory.get());
    }
}