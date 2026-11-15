/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.compat;

import cn.perfscope.PerfScope;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SchedulerAdapter.TaskHandle} 的契约——<b>不用改任何生产代码就能测</b>。
 *
 * <h2>为什么这块能测而外部类不能</h2>
 * {@code SchedulerAdapter} 的 {@code <clinit>} 有
 * {@code private static final boolean FOLIA = ServerCompat.isFolia()}，
 * 而 {@code ServerCompat} 的 {@code <clinit>} 在测试 JVM 里必然抛
 * {@code ExceptionInInitializerError}（卡点：{@code Bukkit.getBukkitVersion()} 解引用
 * {@code Bukkit.server}，而它是 {@code null}）。
 *
 * <p>但实测确认：{@code Class.forName("...SchedulerAdapter$TaskHandle")} <b>成功</b>——
 * 加载内部类不会初始化外部类（JLS 12.4.1：初始化一个类只触发其超类与接口的初始化，
 * 不触发外围类）。{@code TaskHandle} 自己也��有任何静态依赖，于是它成了这个文件里
 * 唯一可以脱离 Bukkit 服务端验证的部分。</p>
 *
 * <p>{@code BukkitTask} 是接口，可以用 {@link Proxy} 造实例——实测可造，且方法调用会进
 * handler（不必实现全部方法）。这是这两条"取消路径"能被验证的前提。</p>
 *
 * <h2>这些契约为什么值得锁</h2>
 * {@code TaskHandle} 被所有调度调用点持有，它的 {@code isActive()} 决定调用方能否区分
 * "任务在跑"与"调度失败"（{@code f-s01-err-01}），{@code cancel()} 跑在关服 / reload /
 * 退订路径上——那里一旦抛异常，会中断"取消其余句柄"的循环，留下未取消的定时任务。
 */
class TaskHandleContractTest {

    /**
     * 文案层必须初始化：{@code cancel()} 失败时走 {@code Messages.get(...)}。
     *
     * <p><b>三步缺一不可</b>（少任何一步，{@code Messages.get} 都会退化成"返回键名 + 丢参数"，
     * 实测确认过：拿到的是字面量 {@code "run.compat.task-cancel-failed"} 且
     * {@code LogRecord.getParameters()} 为 null）：</p>
     * <ol>
     *   <li>{@code AlertFixtures.initMessages()} —— 覆盖 alert / config / diagnose 三个模块
     *       并完成 {@code Messages.load(null)}；</li>
     *   <li>{@code RuntimeMessages.init()} —— 触发它的 static 块，把 {@code run.*} 族键
     *       {@code registerModule} 进 {@code EXTRA_EN}/{@code EXTRA_ZH}（此时还没合并）；</li>
     *   <li><b>{@code Messages.load(null)} 再来一次</b> —— 这一步才会 {@code touchModules()} +
     *       {@code mergeModules()}，把上一步注册的表合并进 {@code BUILTIN} 并重建快照。</li>
     * </ol>
     * <p>漏掉第 3 步是这里最容易犯的错：注册不等于生效，{@code registerModule} 只把表存起来
     * 并把 {@code modulesLoaded} 置 false，真正的合并发生在下一次 {@code load()}。</p>
     */
    @BeforeAll
    static void initMessages() throws Exception {
        cn.perfscope.support.AlertFixtures.initMessages();
        Class.forName("cn.perfscope.RuntimeMessages").getMethod("init").invoke(null);
        Class.forName("cn.perfscope.util.Messages")
            .getMethod("load", Class.forName("cn.perfscope.PerfScope"))
            .invoke(null, (Object) null);
    }

    // ------------------------------------------------------------------------------------
    // 构造工具
    // ------------------------------------------------------------------------------------

    /** 造一个 {@code BukkitTask} 假实现；{@code onCancel} 决定 cancel() 时做什么。 */
    private static BukkitTask fakeTask(Runnable onCancel) {
        return (BukkitTask) Proxy.newProxyInstance(
            TaskHandleContractTest.class.getClassLoader(),
            new Class<?>[]{BukkitTask.class},
            (proxy, method, args) -> {
                if ("cancel".equals(method.getName())) {
                    onCancel.run();
                    return null;
                }
                // toString/hashCode/equals 之外的返回值都给的 null/0/false；
                // 本测试不调用它们，但代理必须能响应，否则误调时会得到 NPE 而不是清晰失败
                return switch (method.getReturnType().getName()) {
                    case "boolean" -> false;
                    case "int" -> 0;
                    case "long" -> 0L;
                    default -> null;
                };
            });
    }

    /** 造一个只响应 {@code getLogger()} 的 Plugin 假实现（其余返回 null）。 */
    private static Plugin fakePlugin(Logger logger) {
        return (Plugin) Proxy.newProxyInstance(
            TaskHandleContractTest.class.getClassLoader(),
            new Class<?>[]{Plugin.class},
            (proxy, method, args) -> "getLogger".equals(method.getName()) ? logger : null);
    }

    /**
     * 反射调用 {@code TaskHandle} 的私有构造器。
     *
     * <p>三个构造器都是 private（句柄只能由 {@code SchedulerAdapter} 自己造），
     * 所以测试必须用反射。参数按类型挑选，避免依赖参数个数（两个重载都是 2 参 / 1 参）。</p>
     */
    private static SchedulerAdapter.TaskHandle handle(Class<?>[] sig, Object... args) {
        try {
            Constructor<SchedulerAdapter.TaskHandle> c =
                SchedulerAdapter.TaskHandle.class.getDeclaredConstructor(sig);
            c.setAccessible(true);
            return c.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TaskHandle 构造失败，参数签名可能已改", e);
        }
    }

    private static SchedulerAdapter.TaskHandle bukkitHandle(BukkitTask task) {
        return handle(new Class<?>[]{BukkitTask.class}, task);
    }

    private static SchedulerAdapter.TaskHandle noopHandle() {
        try {
            java.lang.reflect.Method m = SchedulerAdapter.TaskHandle.class.getDeclaredMethod("noop");
            m.setAccessible(true);
            return (SchedulerAdapter.TaskHandle) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("noop() 反射失败", e);
        }
    }

    // ------------------------------------------------------------------------------------
    // 1. noop：调度失败时的"功能停用"句柄
    // ------------------------------------------------------------------------------------

    /**
     * {@code noop().isActive() == false}。
     *
     * <p>这条是 {@code f-s01-err-01} 的根：调用方靠 {@code isActive()} 区分"任务真的在跑"
     * 与"调度器已经放弃"。若 noop 反而报告 true，日志会说"已启动"而实际什么都没跑，
     * 采集 / 告警 / 探针全部静默停摆，且控制台没有任何迹象。</p>
     */
    @Test
    void noopIsNotActive() {
        assertFalse(noopHandle().isActive(),
            "noop 是 Folia 分支 fail-safe 停用时返回的句柄，必须报告「未启用」——"
                + "否则调用方会以为任务在跑，而实际上什么都没调度");
    }

    /**
     * {@code noop().cancel()} 必须无操作且不抛。
     *
     * <p>它出现在 {@code SchedulerAdapter} 三个方法的 fail-safe 分支里（{@code runGlobalTimer} /
     * {@code runAsyncTimer} / {@code runLater}）。关服路径会遍历所有句柄逐个 cancel——
     * 这里抛一次就中断循环，剩下的任务留在调度器里跟着 JVM 一起死，或者更糟：
     * reload 时残留下来，与新实例的任务同时跑。</p>
     */
    @Test
    void noopCancelIsNoOp() {
        assertDoesNotThrow(() -> noopHandle().cancel(),
            "noop 句柄的 cancel 必须是无操作：它跑在关服 / reload 的循环里，抛一次就中断剩余句柄的取消");
    }

    /** noop 的 cancel 可以重复调用——关服路径可能因多处持有而重复取消。 */
    @Test
    void noopCancelIsIdempotent() {
        SchedulerAdapter.TaskHandle h = noopHandle();
        assertDoesNotThrow(() -> {
            h.cancel();
            h.cancel();
            h.cancel();
        }, "重复 cancel 不得抛——同一句柄可能被多个路径各取消一次");
    }

    // ------------------------------------------------------------------------------------
    // 2. 带任务的句柄：isActive 必须反映真实状态
    // ------------------------------------------------------------------------------------

    /** 装了 BukkitTask 的句柄必须报告活跃——否则调用方以为调度失败，会重复调度。 */
    @Test
    void handleWithBukkitTaskIsActive() {
        assertTrue(bukkitHandle(fakeTask(() -> { })).isActive(),
            "带 BukkitTask 的句柄必须报告活跃：否则调用方（采集 / 告警 / 探针）会以为调度失败并重复挂任务");
    }

    /**
     * {@code {null}} 构造出来的句柄等同于 noop（不活跃）——这是 f-s01-err-01 的实现细节。
     *
     * <p>为什么值得测：句柄的活跃判定是 {@code bukkitTask != null || foliaTask != null}，
     * 有人把它改成"只要有实例就算活跃"时，这一条会红。那种改动会让
     * {@code plugin.getServer().getScheduler().runTaskTimer(...)} 返回 null 的极端情况
     * （调度器拒绝）被误报成"已启动"。</p>
     */
    @Test
    void handleWithNullTaskIsNotActive() {
        assertFalse(bukkitHandle(null).isActive(),
            "null 任务句柄必须报告不活跃：它代表「调度器拒绝了这次调度」，"
                + "报告活跃会让调用方误以为任务在跑");
    }

    // ------------------------------------------------------------------------------------
    // 3. cancel 的异常吞噬契约（f-s02-contract-04）
    // ------------------------------------------------------------------------------------

    /**
     * {@code BukkitTask.cancel()} 抛出时，{@code TaskHandle.cancel()} <b>不得</b>把异常冒泡。
     *
     * <p>javadoc 第 273-276 行记录了这条的来历：原先只有 Folia 分支有 try，Bukkit 分支的
     * {@code cancel()} 一旦抛出就直接冒泡、零日志。而调用方都在关服 / 退订路径上，
     * 异常冒泡会中断「取消其余句柄」的循环。这条测试正是锁住那次修复不被退化。</p>
     */
    @Test
    void cancelSwallowsBukkitTaskException() {
        SchedulerAdapter.TaskHandle h = bukkitHandle(fakeTask(() -> {
            throw new IllegalStateException("模拟 BukkitTask.cancel 失败");
        }));
        assertDoesNotThrow(h::cancel,
            "BukkitTask.cancel() 抛异常时 TaskHandle.cancel() 必须吞掉："
                + "它跑在关服循环里，冒泡会中断其余句柄的取消，留下未取消的定时任务");
    }

    /**
     * 吞异常不能是"悄悄吞"——必须留下正好一条 WARNING 级日志，且带上异常信息。
     *
     * <p><b>为什么不检查渲染后的文案</b>：{@code run.*} 族键由 {@code RuntimeMessages} 注册，
     * 而合并只发生在 {@code Messages.load(plugin)} 里带 plugin 的那条路径；测试里只能传
     * {@code null}（没有 Bukkit 服务端），走的是"无插件"早退分支——
     * 实测确认即使手动 {@code RuntimeMessages.init()} + {@code touchModules()} + {@code load(null)}，
     * {@code Messages.get} 仍然返回键名本身、{@code getParameters()} 为 null。
     * 这是消息层在无插件环境下的<b>预期降级</b>，不是 bug。</p>
     *
     * <p>所以这条测试锁的是它真正能锁的部分：<b>有没有记、记几条、什么级别</b>。
     * 文案本身由 {@code check-i18n.mjs} 的键完整性检查与
     * {@code RuntimeMessages} 的 {@code put(en, zh, ...)} 成对约束负责。</p>
     */
    @Test
    void cancelFailureIsLoggedOnceAtWarning() {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger("pscope-taskhandle-cancel-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            SchedulerAdapter.TaskHandle h = bukkitHandle(fakeTask(() -> {
                throw new IllegalStateException("模拟取消失败");
            }));
            // plugin 是 final 字段：Field.set 对 final 实例字段是允许的（TestObjects 类注释一）。
            // 之所以要注入：只有 (foliaTask, plugin) 构造器会带 plugin，Bukkit 路径的构造器把它置 null——
            // 这个不对称不是笔误（Bukkit 路径拿不到 plugin 引用），但会让本测试测不到日志出口。
            cn.perfscope.support.TestObjects.setField(
                SchedulerAdapter.TaskHandle.class, h, "plugin", fakePlugin(logger));
            h.cancel();
        } finally {
            logger.removeHandler(handler);
        }

        assertEquals(1, records.size(),
            "取消失败必须正好留一条日志：零条是静默吞异常（服主不知道任务没取消），"
                + "多条会在关服时刷屏（每个任务一条）");
        assertEquals(Level.WARNING, records.get(0).getLevel(),
            "取消失败是 warning 级：它意味着某个定时任务没被取消，而调用方已经继续往下走了");
    }

    /**
     * {@code LinkageError}（而非 {@code Exception}）同样不得冒泡。
     *
     * <p>这条锁的是 catch 子句的**范围**。把 {@code catch (Exception | LinkageError)} 缩回
     * {@code catch (Exception)} 时这条会红——而那个退化在真实 Folia 上恰好能被触发：
     * 类加载失败的典型表现就是 {@code NoClassDefFoundError}，它不是 {@code Exception}。</p>
     */
    @Test
    void cancelSwallowsLinkageError() {
        SchedulerAdapter.TaskHandle h = bukkitHandle(fakeTask(() -> {
            throw new NoClassDefFoundError("模拟类加载失败");
        }));
        assertDoesNotThrow(h::cancel,
            "LinkageError 也必须被吞：Folia 上类缺失的典型表现是 NoClassDefFoundError，"
                + "而它不是 Exception——只 catch Exception 会让它在关服路径上冒泡");
    }

    /** 没有 plugin 引用的句柄取消失败时，不得因为「想记日志却没有 logger」而 NPE。 */
    @Test
    void cancelFailureWithoutPluginDoesNotThrow() {
        SchedulerAdapter.TaskHandle h = bukkitHandle(fakeTask(() -> {
            throw new IllegalStateException("模拟取消失败，且句柄没有 plugin");
        }));
        assertDoesNotThrow(h::cancel,
            "logCancelFailure 里有 plugin != null 的守卫；去掉它会让「无 plugin 的句柄取消失败」变成 NPE，"
                + "而那条路径在关服时同样会中断剩余句柄的取消");
    }

    // ------------------------------------------------------------------------------------
    // 3b. Folia 路径的 cancel（`(foliaTask, plugin)` 构造器）
    // ------------------------------------------------------------------------------------

    /**
     * 造一个 {@code ScheduledTask}（Folia 调度句柄）假实现。
     *
     * <p>它是 {@code io.papermc.paper.threadedregions.scheduler} 里的<b>接口</b>，
     * 而 paper-api 1.20.4 已包含该包（实测 {@code javap} 可见），所以能用 {@link Proxy} 造。
     * 不能像 Bukkit 路径那样塞一个裸 {@code Object}——{@code FoliaSchedulerBridge.cancel}
     * 第 112 行是 {@code ((ScheduledTask) scheduledTask).cancel()}，塞 Object 会
     * {@code ClassCastException}，而那个异常会走进取消失败的日志分支、在
     * {@code plugin.getLogger()} 为 null 时再抛 NPE，把"路径没走通"伪装成"日志代码有 bug"。</p>
     */
    private static Object fakeScheduledTask(Runnable onCancel) {
        try {
            Class<?> st = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            return Proxy.newProxyInstance(
                TaskHandleContractTest.class.getClassLoader(),
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
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "paper-api 未提供 ScheduledTask——Folia 路径的测试前提不成立", e);
        }
    }

    /**
     * Folia 路径的 {@code cancel()} 必须真的调到 {@code FoliaSchedulerBridge.cancel}。
     *
     * <p><b>这条是用变异验证补出来的。</b>第一版测试只覆盖了 Bukkit 路径，把
     * {@code if (foliaTask != null)} 改成 {@code if (false)}——Folia 句柄永不取消——
     * <b>10 个用例全部存活</b>。而那个改动的后果是：reload 之后旧实例的定时任务留在
     * Folia 的 GlobalRegionScheduler 里继续跑，与新实例同时采集、同时告警、同时写历史。</p>
     */
    @Test
    void foliaPathCancelReachesTheBridge() {
        int[] cancelCount = {0};
        SchedulerAdapter.TaskHandle h = handle(
            new Class<?>[]{Object.class, Plugin.class},
            fakeScheduledTask(() -> cancelCount[0]++), fakePlugin(null));
        h.cancel();
        assertEquals(1, cancelCount[0],
            "Folia 路径的 cancel 必须调到 bridge 的 cancel()：把 if (foliaTask != null) 改成 "
                + "if (false) 会让 Folia 句柄永不取消——reload 后旧任务残留并与新实例同时跑。"
                + "这个缺口是变异验证发现的（当时 10 个用例全部存活）");
    }

    /** Folia 路径的句柄同样必须报告活跃。 */
    @Test
    void foliaHandleIsActive() {
        SchedulerAdapter.TaskHandle h = handle(
            new Class<?>[]{Object.class, Plugin.class},
            fakeScheduledTask(() -> { }), fakePlugin(null));
        assertTrue(h.isActive(),
            "带 foliaTask 的句柄必须报告活跃：它代表 GlobalRegionScheduler 真的接下了这个任务");
    }

    /**
     * Folia 路径取消失败（bridge 里抛出）也必须被吞掉并记日志。
     *
     * <p>这条锁的是 Folia 分支的 {@code catch (Throwable)}——范围比 Bukkit 分支的
     * {@code catch (Exception | LinkageError)} 更宽，因为 bridge 走反射，
     * 反射能抛出的东西不限于 {@code Exception}。</p>
     */
    @Test
    void foliaPathCancelFailureIsSwallowedAndLogged() {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger("pscope-taskhandle-folia-cancel-test");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            SchedulerAdapter.TaskHandle h = handle(
                new Class<?>[]{Object.class, Plugin.class},
                fakeScheduledTask(() -> {
                    throw new IllegalStateException("模拟 Folia 取消失败");
                }),
                fakePlugin(logger));
            assertDoesNotThrow(h::cancel,
                "Folia 路径取消失败必须被吞：它跑在关服 / reload 循环里，冒泡会中断其余句柄的取消");
        } finally {
            logger.removeHandler(handler);
        }
        assertEquals(1, records.size(),
            "Folia 路径取消失败必须正好记一条日志：零条是静默吞异常");
    }

    // ------------------------------------------------------------------------------------
    // 4. 边界：这条测试真的在测东西吗
    // ------------------------------------------------------------------------------------

    /**
     * 自检：反射造出来的句柄确实是 {@code TaskHandle}，而不是被 JVM 优化成别的东西。
     *
     * <p>这条看起来像废话，但它挡住一类真实的假绿：如果 {@code noop()} 被改成返回
     * 一个常量单例或换成别的类型，前面几条断言可能因为"对象的字段恰好都是 null"
     * 而继续通过，而语义已经变了。</p>
     */
    @Test
    void reflectionProducesRealHandles() {
        assertEquals(SchedulerAdapter.TaskHandle.class, noopHandle().getClass());
        assertEquals(SchedulerAdapter.TaskHandle.class,
            bukkitHandle(fakeTask(() -> { })).getClass());
    }
}
