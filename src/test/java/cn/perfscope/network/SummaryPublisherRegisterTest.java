/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.network;

import cn.perfscope.PerfScope;
import cn.perfscope.support.FakeServer;
import cn.perfscope.support.TestObjects;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-E 的行为测试：{@code SummaryPublisher} 的 {@code register()} / {@code unregister()} /
 * {@code resetState()}。
 *
 * <h2>为什么这条必须做成行为测试</h2>
 *
 * <p>这三个方法在此前是<b>零测试</b>。{@code register()} 唯一的守护是一条源码断言
 * （{@code StatusExportsTest}）——而那条曾经写成 {@code assertContains(publisherSrc,
 * "return false;")}，在这个 795 行文件的 <b>4 处 {@code return false;}</b> 里随便哪一处
 * 都能把它喂饱，于是<b>恒真</b>：把 catch 改成 {@code return true}（P0-E 的原始形状）
 * 它照样绿。源码断言的软肋在这里暴露得很彻底——它锁的是"文件里存在某段文本"，
 * 不是"这个方法按这个结果返回"。</p>
 *
 * <p>本类改锁<b>返回值</b>：注入一个会抛的 {@code Messenger}，断言 {@code register()}
 * 返回 {@code false} 且<b>不向上抛</b>。这条断言与"文件里有没有 return false"无关，
 * 改坏生产代码的唯一办法就是真的改坏行为。</p>
 *
 * <h2>怎么在没有真实服务端的情况下走到 {@code Messenger.registerOutgoingPluginChannel}</h2>
 *
 * <p>{@code KNOWN-TEST-LIMITS.md} 第 1 条记录的是"造不出假 {@code Server}"。该结论对
 * <b>{@code Proxy}</b> 成立、对<b>具体实现</b>不成立——本类用的是
 * {@link cn.perfscope.support.FakeServer}（手写的 205 方法最小实现，理由见它自己的类注释）。
 * 关键事实：{@code Server} 接口上有 {@code createExplorerMap(..., StructureType, ...)}，
 * 而 {@code StructureType.<clinit>} 第一行就解引用 {@code Bukkit.server}；{@code Proxy}
 * 生成的类在 {@code <clinit>} 里解析参数类型因此必炸（JDK 17 实测），而<b>声明</b>参数类型
 * 不触发初始化，所以具体实现没有这个问题。</p>
 *
 * <p>生产代码一个字都没为测试改：{@code register()} 里跑的仍是
 * {@code plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL)}
 * 那一行与它自己的 {@code try/catch}。</p>
 *
 * <h2>能杀死的变异</h2>
 *
 * <ul>
 *   <li>catch 里 {@code return true;}（P0-E 原始形状）→ {@link #failureIsReportedNotSwallowed} 红</li>
 *   <li>{@code register()} 去掉 {@code try/catch}（让异常穿出）→ 同一用例红（异常穿出）</li>
 *   <li>catch 里只打日志不改返回值 / 返回 void → 编译期就不成立，调用点会跟着红</li>
 *   <li>成功路径不清 {@code registrationFailureReason}（/status 会永远显示旧失败）→
 *       {@link #successClearsTheStaleFailureReason} 红</li>
 *   <li>{@code unregister()} 去掉 {@code try/catch} → {@link #unregisterSwallowsMessengerFailures} 红</li>
 *   <li>{@code resetState()} 只清一半字段 → {@link #resetStateClearsBothSendAnchors} 红</li>
 * </ul>
 */
class SummaryPublisherRegisterTest {

    private static final String LOGGER_NAME = "pscope-publisher-register";

    /** 捕获到的 WARNING 文案（{@code register()} 失败时那条必须打出来）。 */
    private final List<String> warnings = new ArrayList<>();

    /** 记录"生产代码真的往 Messenger 上调了哪些方法"。 */
    private final List<String> messengerCalls = new ArrayList<>();

    private Logger logger;

    @BeforeAll
    static void initMessages() {
        cn.perfscope.support.AlertFixtures.initMessages();
    }

    /**
     * 每例一个独立 logger，handler 装在它上面。
     *
     * <p>不用 {@code Logger.getLogger()} 的全局实例：surefire 复用同一个 JVM，
     * 上一个用例的 handler 若没摘干净，下一个用例的 {@code warnings} 会数到别人的行。</p>
     */
    private Logger freshLogger() {
        warnings.clear();
        logger = Logger.getLogger(LOGGER_NAME);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        for (Handler h : logger.getHandlers()) {
            logger.removeHandler(h);
        }
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.WARNING && r.getMessage() != null) {
                    warnings.add(r.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    // ==================== 夹具 ====================

    /**
     * 造一个假 {@code Messenger}：{@code registerOutgoingPluginChannel} 的行为由
     * {@code failureMode} 决定，其余方法给默认值。
     *
     * <p>注意 {@code isOutgoingChannelRegistered} 的判据是 {@code registered}——一个<b>只在
     * 登记成功时</b>置真的标志。不能拿"调用过 registerOutgoingPluginChannel"当判据：
     * 抛异常那次也调用过，那样"失败的尝试也会显示成已登记"，把一条 sanity 断言喂成恒真。</p>
     *
     * <p>同一个 {@code failureMode} 也作用于 {@code unregisterOutgoingPluginChannel}：
     * {@link #unregisterSwallowsMessengerFailures} 要的正是"解注册这一步抛异常"的场景，
     * 若夹具只让注册抛，那条用例会在一个永远不会抛的载体上空转（本仓库踩过：M4 变异当时存活）。</p>
     *
     * @param failureMode {@code null} = 都成功；否则两者都抛该异常/错误
     */
    private Messenger messenger(Throwable failureMode) {
        boolean[] registered = {false};
        return (Messenger) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Messenger.class},
            (proxy, method, args) -> {
                messengerCalls.add(method.getName());
                if ("registerOutgoingPluginChannel".equals(method.getName())) {
                    if (failureMode != null) {
                        throw failureMode;
                    }
                    registered[0] = true;
                    return null;
                }
                if ("unregisterOutgoingPluginChannel".equals(method.getName())) {
                    if (failureMode != null) {
                        throw failureMode;
                    }
                    registered[0] = false;
                    return null;
                }
                if ("isOutgoingChannelRegistered".equals(method.getName())) {
                    return registered[0];
                }
                return defaultFor(method.getReturnType());
            });
    }

    /** 假 {@code Server}：{@link FakeServer} 只回答 {@code getMessenger()}，正好够用。 */
    private FakeServer server(Messenger messenger) {
        return new FakeServer(messenger);
    }

    /** 代理 handler 的兜底返回值：只防止"基本类型返回 null"变成 NPE。 */
    private static Object defaultFor(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        return null;
    }

    /**
     * 装一个 publisher：plugin 的 {@code logger} 与 {@code JavaPlugin.server} 两个字段被注入。
     *
     * <p>载体来源故意给"没有载体"（空服）——本类只测登记，不该顺带触发发送路径。</p>
     */
    private SummaryPublisher publisherWith(Messenger messenger) {
        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, plugin, "logger", freshLogger());
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, plugin, "server",
            server(messenger));
        // 载体来源：空服。register 不碰它，但字段是 final，allocateInstance 不会初始化。
        return new SummaryPublisher(plugin, (SummaryPublisher.CarrierSource) () -> null);
    }

    // ==================== register()：成功 = 返回 true ====================

    /**
     * <b>核心用例 1</b>：登记成功时 {@code register()} 必须返回 {@code true}。
     *
     * <p>这条是 {@link #failureIsReportedNotSwallowed} 的对照面：只有两个方向都被钉住，
     * "返回值恒为 false"那种错误实现才跑不掉。它同时证明夹具真的把
     * {@code Messenger} 接上了——{@code messengerCalls} 里必须出现生产过程调的那个方法名。</p>
     */
    @Test
    @DisplayName("登记成功：register() 返回 true，且真的调用了 Messenger")
    void successReturnsTrueAndTouchesTheMessenger() {
        SummaryPublisher publisher = publisherWith(messenger(null));

        assertTrue(publisher.register(),
            "a successful registerOutgoingPluginChannel must be reported as true; returning false "
                + "here would make the caller log 「未启用」 on a perfectly working channel");

        assertTrue(messengerCalls.contains("registerOutgoingPluginChannel"),
            "the fixture must actually reach the production call; if this list is empty the "
                + "assertion above proves nothing. Calls seen: " + messengerCalls);

        assertNull(publisher.registrationFailureReason(),
            "on success there is no failure reason — /perfscope status must not show a stale one");
        assertTrue(publisher.channelRegistered(),
            "sanity: the fake messenger now reports the channel as registered");
    }

    /**
     * 成功路径必须把<b>上一次</b>的失败原因清掉。
     *
     * <p>{@code registrationFailureReason} 是 {@code /perfscope status} 的唯一数据源。
     * 若成功时不清它，服主修好了问题（重启后登记正常）却仍然看到"登记失败：XXX"，
     * 排查方向被拖回一个已经不存在的故障。做法：同一个 publisher 先失败一次、再成功一次——
     * 新建一个对象再断言"它的原因是 null"是恒真的（新对象本来就是 null）。</p>
     */
    @Test
    @DisplayName("先失败后成功：成功那一轮必须清掉残留的失败原因")
    void successClearsTheStaleFailureReason() {
        // 同一个 FakeServer，只换它返回的 Messenger —— 模拟"reload 后通道空了出来"。
        FakeServer fakeServer = server(messenger(new IllegalStateException("channel taken by another plugin")));

        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, plugin, "logger", freshLogger());
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, plugin, "server", fakeServer);
        SummaryPublisher publisher = new SummaryPublisher(plugin, (SummaryPublisher.CarrierSource) () -> null);

        assertEquals(false, publisher.register(),
            "precondition: the first attempt must fail so that a stale reason exists");
        assertNotNull(publisher.registrationFailureReason(),
            "precondition: a failed registration must leave a reason behind for /status");

        fakeServer.setMessenger(messenger(null));
        assertTrue(publisher.register(),
            "precondition: the second attempt must succeed");
        assertNull(publisher.registrationFailureReason(),
            "a successful registration must clear the previous failure reason; otherwise /status "
                + "keeps reporting a failure that no longer exists and sends the admin hunting "
                + "a problem they already fixed");
    }

    // ==================== register()：失败 = 返回 false 且不抛（P0-E 本体） ====================

    /**
     * <b>核心用例 2（P0-E 本体）</b>：Messenger 抛异常时 {@code register()} 返回
     * {@code false}，<b>不向上抛</b>。
     *
     * <p>P0-E 的形状是"catch 吞掉异常后<b>正常返回</b>（void 或 true），调用方接着无条件打
     * 「已启用」"。这条从两个方向同时钉住结果：<b>不抛</b>（抛出去会打穿 {@code onEnable}）
     * 且<b>返回 false</b>（返回 true 就是 P0-E 本身）。踩中任一方向都会红。</p>
     *
     * <p>另外断言失败原因被留档：{@code /perfscope status} 靠它回答"为什么没登记上"，
     * 而启动那条 warning 早已滚出控制台。</p>
     */
    @Test
    @DisplayName("Messenger 抛异常：register() 返回 false 且不抛出（P0-E 本体）")
    void failureIsReportedNotSwallowed() {
        SummaryPublisher publisher = publisherWith(
            messenger(new IllegalStateException("Not registered / channel owned by another plugin")));

        boolean result = assertDoesNotThrow(publisher::register,
            "register() must contain the exception: letting it escape would abort plugin startup "
                + "for an optional export channel (the original bug was the mirror image — "
                + "swallowing it AND reporting success)");

        assertEquals(false, result,
            "the catch branch must return false. Returning true here is exactly P0-E: the caller "
                + "prints 「跨服摘要已启用」 while the channel was never registered, so the admin "
                + "trusts a feature that sends nothing");

        assertNotNull(publisher.registrationFailureReason(),
            "the reason must be kept for /perfscope status; a startup warning has scrolled away "
                + "by the time anyone asks why");
        assertTrue(publisher.registrationFailureReason().contains("IllegalStateException"),
            "the reason must name the exception type, otherwise /status says 'failed' with no cause. "
                + "Actual: " + publisher.registrationFailureReason());
        assertTrue(publisher.registrationFailureReason().contains("Not registered"),
            "and it must carry the message — that is the only part that says what went wrong. "
                + "Actual: " + publisher.registrationFailureReason());

        assertEquals(1, warnings.size(),
            "exactly one WARNING must accompany the failure (says the channel is unusable); "
                + "Actual warnings: " + warnings);
        // 这条日志已迁进消息表（network.log.register-failed，见 NetworkMessages），文案随
        // general.language 切换：默认 en 下是英文，zh 下才是「登记跨服通道失败」。
        // 只认中文会让本用例在**正确**的实现上报红；两份特征串都列，语言无关地断言"它说了什么"。
        // （与 PublisherFailureThrottleTest 的 FAIL_LOG_TOKENS 同口径。）
        assertTrue(warnings.get(0).contains("Failed to register the cross-server channel")   // en
                || warnings.get(0).contains("登记跨服通道失败"),                              // zh
            "the warning must name the registration failure. Actual: " + warnings.get(0));

        assertEquals(false, publisher.channelRegistered(),
            "sanity: the channel really is not registered after a failed attempt");
    }

    /**
     * {@code LinkageError} 同属"catch 得住"的范围。
     *
     * <p>生产上这条有真实场景：一个被半卸载的插件会抛 {@code NoClassDefFoundError}。
     * {@code register()} 的 {@code catch (Exception | LinkageError)} 是刻意写宽的，
     * 只写 {@code catch (Exception)} 会让它穿出 {@code onEnable} 并禁用整个插件——
     * 为一个可选的跨服摘要出口赔上主功能。</p>
     */
    @Test
    @DisplayName("LinkageError 也要被接住：仍返回 false 而不是打穿 onEnable")
    void linkageErrorIsCaughtToo() {
        SummaryPublisher publisher = publisherWith(
            messenger(new NoClassDefFoundError("org/bukkit/plugin/messaging/Messenger")));

        assertEquals(false, assertDoesNotThrow(publisher::register,
            "LinkageError must be caught as well: catch (Exception) alone would let a half-unloaded "
                + "plugin's NoClassDefFoundError disable the whole plugin"),
            "and it must still report failure, not success");
        assertNotNull(publisher.registrationFailureReason(),
            "the reason must name the linkage failure too");
    }

    // ==================== unregister() ====================

    /**
     * {@code unregister()} 必须吞掉 Messenger 的异常。
     *
     * <p>调用点是 {@code onDisable()} / reload。关服途中通道可能已经随服务端一起没了，
     * 此时抛异常会中断关服流程——本类此前零测试，所以"它是否真的吞"从未被验证过。</p>
     */
    @Test
    @DisplayName("unregister() 吞掉 Messenger 异常（关服途中通道可能已经没了）")
    void unregisterSwallowsMessengerFailures() {
        SummaryPublisher publisher = publisherWith(
            messenger(new IllegalStateException("server shutting down, messenger gone")));

        assertDoesNotThrow(publisher::unregister,
            "unregister() runs during onDisable/reload; a throw here would abort the shutdown "
                + "sequence for a channel that is about to disappear anyway");
        assertTrue(messengerCalls.contains("unregisterOutgoingPluginChannel"),
            "sanity: the production call was actually reached. Calls seen: " + messengerCalls);
    }

    // ==================== resetState() ====================

    /**
     * {@code resetState()} 必须把<b>两个</b>发送锚点都清掉。
     *
     * <p>它是 {@code /perfscope reload} 后让新配置立刻生效的那一步。只清语义串不清时刻，
     * 结果是"reload 改了上报间隔，但下一次发送仍按旧间隔算"——服主改的配置看起来没生效，
     * 而本机毫无异常。两个字段一起断言，正是为了不让"只清一半"的实现留在测试里。</p>
     */
    @Test
    @DisplayName("resetState() 同时清掉语义串与发送时刻（只清一半会让人以为 reload 没生效）")
    void resetStateClearsBothSendAnchors() {
        SummaryPublisher publisher = publisherWith(messenger(null));
        TestObjects.setField(SummaryPublisher.class, publisher, "lastSentSemantic", "20.0|20.0|20.0");
        TestObjects.setField(SummaryPublisher.class, publisher, "lastSentAtMs", 1_796_000_000_000L);

        publisher.resetState();

        assertNull(TestObjects.getField(SummaryPublisher.class, publisher, "lastSentSemantic"),
            "the semantic string must be cleared: otherwise the first post-reload cycle is "
                + "compared against the pre-reload state and can be skipped as UNCHANGED");
        assertEquals(0L, TestObjects.getField(SummaryPublisher.class, publisher, "lastSentAtMs"),
            "the send timestamp must be cleared too: reload changes network.push-interval, and a "
                + "stale timestamp makes the next send wait out the OLD interval");
    }

    /** 生产构造器仍然走服务端：{@code server} 为 null 时不抛（既有契约，顺带守一条回归）。 */
    @Test
    @DisplayName("server 为 null 时 register() 不抛，只报失败")
    void registerDegradesWhenThereIsNoServerAtAll() {
        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, plugin, "logger", freshLogger());
        // 刻意不设 server：JavaPlugin.getServer() 返回 null

        SummaryPublisher publisher = new SummaryPublisher(plugin, (SummaryPublisher.CarrierSource) () -> null);
        assertEquals(false, assertDoesNotThrow(publisher::register,
            "a null server (test shell / mid-shutdown) must degrade to 'failed', never throw: "
                + "register() runs inside onEnable, where a throw disables the whole plugin"),
            "with no server there is no messenger, so the honest answer is false");
    }
}
