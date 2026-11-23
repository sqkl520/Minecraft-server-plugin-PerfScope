/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.network;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.TestObjects;
import org.bukkit.plugin.messaging.PluginMessageRecipient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-P：{@code publish()} 内部的发送失败日志必须限频。
 *
 * <h2>这条 bug 的形状</h2>
 *
 * <p>原实现每周期一条 WARNING，无节流。1 Hz 采集下 = <b>86400 条/天</b>。外层
 * {@code MetricsCollector} 的 300 秒限频<b>够不到这里</b>——{@code publish()} 把异常在
 * 内部吞掉并返回 {@code FAILED}，外层那个 {@code catch} 永远进不去。所以限频只能做在
 * 抛出点这一侧。</p>
 *
 * <h2>怎么在没有真服务端的情况下跑到这段代码</h2>
 *
 * <p>本类此前是 <b>4 个 error</b> 而不是 4 个红断言：它伪造 {@code Player} 的方式是
 * {@code Proxy.newProxyInstance(..., new Class<?>[]{Player.class}, ...)}，而这会解析
 * {@code Player} 七层继承链上的全部方法签名，牵出 {@code PotionEffectType} /
 * {@code Registry} 的静态块——它们第一行就解引用 {@code Bukkit.server}
 * （{@code null}）→ {@code ExceptionInInitializerError}，后续每个用例再拿到
 * {@code NoClassDefFoundError}。这是测试可测性问题，不是生产缺陷。</p>
 *
 * <p>现在走 {@link SummaryPublisher} 的载体接缝（见该类注释最后一节）：</p>
 *
 * <ul>
 *   <li>注入的是 {@link SummaryPublisher.CarrierSource}——它只回答"用哪个连接发"；</li>
 *   <li>载体类型是 {@link PluginMessageRecipient}（{@code Player} 的
 *       {@code sendPluginMessage} 正是从它继承来的），这个接口只有两个方法，
 *       建代理不会解析 {@code Player} 的继承链，因此<b>造得出来</b>；</li>
 *   <li>发送动作本身仍是生产代码那行 {@code carrier.sendPluginMessage(plugin, CHANNEL, payload)}，
 *       在同一个 {@code try} 里。所以下面每条用例都是<b>真实执行</b>了 {@code publish} 的
 *       catch 分支，而不是断言源码文本。</li>
 * </ul>
 *
 * <p>顺带一个更强的观测点：失败的载体在抛之前会把 payload 记下来，于是"异常真的发在
 * 发送那一步、且发的是组装好的 JSON"这件事也被锁住了——如果哪天 Failure 被提前到
 * payload 生成之前抛出，{@link #repeatedFailuresAreThrottled} 会红。</p>
 *
 * <h2>两条断言各自防什么</h2>
 *
 * <ul>
 *   <li><b>限频</b>：连打 50 次失败，WARNING 只能有一条。写成"每次失败都打"会让它红。</li>
 *   <li><b>计数不能被限频吃掉</b>：被吞掉的失败如果也不计数，日志里的"连续第 N 次"会
 *       永远显示 1——把"已经坏了一整天"说成"刚坏"。这条把"只数打出来的日志"那种
 *       错误实现钉死。</li>
 * </ul>
 */
class PublisherFailureThrottleTest {

    private static final String LOGGER_NAME = "pscope-publisher-throttle";

    /**
     * {@code publish} 里那条限频日志的特征串 —— <b>按语言给两份</b>。
     *
     * <p>P2 之后这条日志走消息表（{@code network.log.send-failed}），文案随
     * {@code general.language} 切换。本类只关心"打了几条"，若把特征串写死成中文，
     * 默认语言（en）下它会一条都数不到——测试会对一个**正确**的实现报
     * "Actual warnings: [...]" 却仍然失败。</p>
     *
     * <p>两份都要列：surefire 复用同一个 JVM，同批次里若有别的测试把语言切到中文，
     * 只剩英文特征串又会失效。</p>
     */
    private static final String[] FAIL_LOG_TOKENS = {
        "Cross-server summary send failed",   // en
        "跨服摘要发送失败",                     // zh
    };

    /** 该日志行是不是"发送失败"那一条（语言无关）。 */
    private static boolean isFailLog(String w) {
        for (String token : FAIL_LOG_TOKENS) {
            if (w.contains(token)) return true;
        }
        return false;
    }

    /**
     * 日志里是否写着"连续第 N 次失败"（语言无关：只认那个数字）。
     *
     * <p>"连续第 N 次"的中英文模板分别是 {@code 连续第 {0} 次} 与 {@code consecutive failure #{0}}，
     * 共同点是数字紧跟在模板给出的位置上。断言只看数字，因此不受语言切换影响；
     * 同时它仍然能杀死"计数只在输出时递增"那个错误实现——那种实现打出的是 1 而不是 6。</p>
     */
    private static boolean containsConsecutive(String line, int n) {
        if (line == null) return false;
        return line.contains("第 " + n + " 次")          // zh
            || line.contains("failure #" + n);           // en
    }

    /** 捕获到的 WARNING 文案。 */
    private final List<String> warnings = new ArrayList<>();

    /** 载体真的收到的 payload（按发送顺序）。用来证明异常发生在"发"这一步之后。 */
    private final List<byte[]> payloads = new ArrayList<>();

    private Logger logger;
    private Handler handler;

    @BeforeAll
    static void initMessages() {
        cn.perfscope.support.AlertFixtures.initMessages();
    }

    @BeforeEach
    void installCapturingLogger() {
        warnings.clear();
        payloads.clear();
        logger = Logger.getLogger(LOGGER_NAME);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        handler = new Handler() {
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
        };
        logger.addHandler(handler);
    }

    @AfterEach
    void removeHandler() {
        logger.removeHandler(handler);
    }

    // ==================== 夹具 ====================

    /**
     * 可切换的载体来源。
     *
     * <p>为什么需要"切换"而不是"再造一个 publisher"：{@code consecutiveSendFailures} 是
     * <b>实例</b>状态，"先失败后成功要清零"只有在<b>同一个实例</b>上才成立。旧版本为绕开
     * {@code Server} 代理的不可变性，给成功路径新建了一个 publisher——那条断言其实只能证明
     * "新实例的计数器是 0"，恒真。现在同一个实例上先坏后好，才真的锁住了清零。</p>
     */
    private static final class SwitchableCarrier implements SummaryPublisher.CarrierSource {
        private PluginMessageRecipient current;

        SwitchableCarrier(PluginMessageRecipient current) {
            this.current = current;
        }

        @Override
        public PluginMessageRecipient pick() {
            return current;
        }
    }

    /**
     * 一个"发送必抛"的载体——这就是"跨服链路坏了"的最小复现。
     *
     * <p>抛的是 {@link IllegalStateException}，与真实场景里 Paper 抛的
     * "Not registered" / "channel closed" 同类：{@code publish} 的 catch 接
     * {@code Exception | LinkageError}，所以这条路径与生产完全一致。</p>
     *
     * <p>在抛之前把 payload 记进 {@code sink}：这样"异常发生在 payload 已生成之后"是可观测的。</p>
     */
    private PluginMessageRecipient failingCarrier(List<byte[]> sink) {
        return (PluginMessageRecipient) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{PluginMessageRecipient.class},
            (proxy, method, args) -> {
                if ("sendPluginMessage".equals(method.getName())) {
                    // 记下真实字节：证明我们确实走到了生产代码的发送那一行。
                    sink.add(((byte[]) args[2]).clone());
                    throw new IllegalStateException("channel closed");
                }
                if ("getListeningPluginChannels".equals(method.getName())) {
                    return Collections.emptySet();
                }
                return defaultFor(method.getReturnType());
            });
    }

    /** 与 {@link #failingCarrier} 相同，但发送成功。 */
    private PluginMessageRecipient workingCarrier(List<byte[]> sink) {
        return (PluginMessageRecipient) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{PluginMessageRecipient.class},
            (proxy, method, args) -> {
                if ("sendPluginMessage".equals(method.getName())) {
                    sink.add(((byte[]) args[2]).clone());
                    return null;
                }
                if ("getListeningPluginChannels".equals(method.getName())) {
                    return Collections.emptySet();
                }
                return defaultFor(method.getReturnType());
            });
    }

    /**
     * 代理 handler 的兜底返回值。
     *
     * <p>只在方法<b>被调用</b>时才进 handler，而 {@code publish} 只碰
     * {@code sendPluginMessage}，所以这段实际上跑不到；留着是为了让"将来有人给这个接口
     * 加方法"时不会因为返回 {@code null} 给基本类型而抛 {@code NullPointerException}，
     * 把一条清楚的断言失败变成一句看不懂的 NPE。</p>
     */
    private static Object defaultFor(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        return null;
    }

    /**
     * 装配一个能跑完整 {@code publish()} 的 plugin。
     *
     * <p>不需要假 {@code Server}：显式配了 {@code network.server-id}，于是
     * {@code resolveServerId} 不会去问端口；载体由注入的来源提供，也不会走
     * {@code getOnlinePlayers()}。{@code metricsCollector} 保持 {@code null}——
     * {@code publish} 对它有两个 null 分支，本类不测采集存活。</p>
     */
    private SummaryPublisher publisherWith(SwitchableCarrier carrier) {
        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, plugin, "logger", logger);

        ConfigManager cm = new ConfigManager(plugin);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.network_enabled = true;
        // 显式 server-id：走"回退端口"那条会多一次告警，与本次要数的日志混在一起
        cfg.network_serverId = "throttle-test";
        cfg.network_pushIntervalSeconds = 10;
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);

        return new SummaryPublisher(plugin, carrier);
    }

    /** 每次 {@code sendPluginMessage} 都抛异常的 publisher。 */
    private SummaryPublisher failingPublisher() {
        return publisherWith(new SwitchableCarrier(failingCarrier(payloads)));
    }

    private static MetricsSnapshot snapshot() {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.tps1m = 20.0;
        snap.tps5m = 20.0;
        snap.tps15m = 20.0;
        return snap;
    }

    private long countFailLogs() {
        return warnings.stream().filter(PublisherFailureThrottleTest::isFailLog).count();
    }

    // ==================== 正向 ====================

    /**
     * <b>核心用例</b>：1 Hz 下连续失败，WARNING 不得刷屏。
     *
     * <p>50 次模拟 50 秒（真实 1 Hz 采集下就是 50 个周期）。修之前是 50 条，修之后必须是 1 条。</p>
     */
    @Test
    @DisplayName("连续 50 次发送失败只打 1 条 WARNING（修前是 50 条）")
    void repeatedFailuresAreThrottled() {
        SummaryPublisher publisher = failingPublisher();
        MetricsSnapshot snap = snapshot();

        long t = 1_796_000_000_000L;
        for (int i = 0; i < 50; i++) {
            assertEquals(SummaryPublisher.Result.FAILED, publisher.publish(snap, t + i * 1000L),
                "every call must report FAILED (and never throw out of publish)");
        }

        assertEquals(1, countFailLogs(),
            "50 failures inside the throttle window must produce exactly one WARNING; "
                + "unthrottled this is 86400 lines/day at 1 Hz. Actual warnings: " + warnings);

        // 载体真的收到了 50 份 payload：说明这 50 次 FAILED 不是"提前 return"造出来的，
        // 而是每一次都走到了生产代码里 carrier.sendPluginMessage(...) 那一行才抛的。
        assertEquals(50, payloads.size(),
            "each cycle must actually reach the send call before failing; a failure that is "
                + "short-circuited earlier would not exercise publish's catch branch at all");
        String first = new String(payloads.get(0), StandardCharsets.UTF_8);
        assertTrue(first.contains("\"server\":\"throttle-test\"") || first.contains("\"serverId\":\"throttle-test\""),
            "the payload must be the real assembled report (server id included), otherwise this "
                + "test would pass on an implementation that fails before building anything. "
                + "Actual payload: " + first);
    }

    /**
     * 被限频吞掉的失败<b>仍然计数</b>。
     *
     * <p>做法：打 5 次失败（只有第 1 次会输出），然后把限频锚点清零（模拟 5 分钟过去），
     * 再打第 6 次。日志必须说"连续第 6 次"——若计数只在输出时递增，这里会显示"第 2 次"，
     * 把"已经坏了 5 个周期"说成"刚坏第二次"。</p>
     */
    @Test
    @DisplayName("限频吞掉的失败也要计数：第 6 次失败要显示「连续第 6 次」")
    void suppressedFailuresStillIncrementTheCounter() {
        SummaryPublisher publisher = failingPublisher();
        MetricsSnapshot snap = snapshot();
        long t = 1_796_000_000_000L;

        for (int i = 0; i < 5; i++) {
            publisher.publish(snap, t + i * 1000L);
        }
        assertEquals(1, countFailLogs(), "the first five failures collapse into one line");
        assertTrue(containsConsecutive(warnings.get(0), 1),
            "the first failure is the first one; got: " + warnings.get(0));

        // 模拟"已经过去一个限频窗口"——直接清锚点，避免测试睡 5 分钟。
        TestObjects.setField(SummaryPublisher.class, publisher, "lastSendFailWarnAtMs", 0L);
        publisher.publish(snap, t + 6_000L);

        String secondLog = warnings.stream()
            .filter(PublisherFailureThrottleTest::isFailLog)
            .reduce((a, b) -> b)
            .orElseThrow();
        assertTrue(containsConsecutive(secondLog, 6),
            "the counter must count every failure, not just the logged ones; otherwise a link "
                + "that has been broken for a day looks like it just broke. Actual log: " + secondLog);
    }

    /**
     * 一次成功必须把失败计数清零。
     *
     * <p>不清零的话，一条偶发失败会在几天后仍然让"连续第 N 次"显示成大数字——那是在
     * 用一个陈旧的计数描述当前的连通性。</p>
     *
     * <p><b>必须在同一个 publisher 上先坏后好</b>：换成"新建一个 publisher 再断言它的计数器是 0"，
     * 那条断言恒真（新对象的字段本来就是 0），什么也没锁住。这里把载体从"抛"切成"不抛"，
     * 于是清零逻辑真的被执行了一遍。</p>
     */
    @Test
    @DisplayName("一次成功把连续失败计数清零（同一实例上先坏后好）")
    void successResetsTheFailureCounter() {
        SwitchableCarrier carrier = new SwitchableCarrier(failingCarrier(payloads));
        SummaryPublisher publisher = publisherWith(carrier);
        MetricsSnapshot snap = snapshot();
        long t = 1_796_000_000_000L;

        // 先坏三次：计数器必须是 3，否则"清零"这件事无从观测。
        for (int i = 0; i < 3; i++) {
            assertEquals(SummaryPublisher.Result.FAILED, publisher.publish(snap, t + i * 1000L));
        }
        assertEquals(3, TestObjects.getInt(SummaryPublisher.class, publisher, "consecutiveSendFailures"),
            "precondition: three failures must be counted before we can observe a reset");

        // 同一个实例，换一个能发出去的载体。
        carrier.current = workingCarrier(payloads);
        assertEquals(SummaryPublisher.Result.SENT, publisher.publish(snap, t + 10_000L),
            "with a working carrier the next cycle must report SENT");

        assertEquals(0, TestObjects.getInt(SummaryPublisher.class, publisher, "consecutiveSendFailures"),
            "a successful send must reset the consecutive-failure counter; without the reset a "
                + "single blip keeps 'consecutive failure #N' climbing for days, describing "
                + "yesterday's outage as current connectivity");
    }

    /**
     * 时钟回拨必须把<b>其余两个限频锚点</b>也清掉。这更进一步，验证的是"日志静默不会换地方藏"。
     *
     * <p>为什么这条必要：只修 {@code lastSentAtMs} 时，发送恢复了，但
     * {@code lastSendFailWarnAtMs} / {@code lastIdWarnAtMs} 仍然落在旧时间轴上（未来），
     * 于是那两条告警在整个回拨窗口里打不出来。也就是说——停发修好了，但"发不出去时没有
     * 任何日志"这个问题原封不动地转移到另外两条告警上，运维同样查不到原因。</p>
     *
     * <p>做法：先把两个锚点设到一个"未来"值（模拟回拨前记下的时刻），然后做一次回拨，
     * 断言两个锚点都被清成 0。</p>
     */
    @Test
    @DisplayName("时钟回拨顺带清掉另外两个限频锚点（否则静默只是换个地方藏）")
    void rollbackClearsEveryThrottleAnchor() {
        SummaryPublisher publisher = failingPublisher();
        MetricsSnapshot snap = snapshot();
        // 回拨前的"现在"
        long beforeRollback = 1_796_000_000_000L;
        // 让 publisher 相信它在该时刻成功发过一包
        TestObjects.setField(SummaryPublisher.class, publisher, "lastSentSemantic", "20.0|...");
        TestObjects.setField(SummaryPublisher.class, publisher, "lastSentAtMs", beforeRollback);
        // 两个限频锚点也停在"旧时间轴"（对回拨后的时钟来说就是未来）
        TestObjects.setField(SummaryPublisher.class, publisher, "lastSendFailWarnAtMs", beforeRollback);
        TestObjects.setField(SummaryPublisher.class, publisher, "lastIdWarnAtMs", beforeRollback);

        // 回拨 1 小时后再调一次
        int warningsBefore = warnings.size();
        assertEquals(SummaryPublisher.Result.FAILED,
            publisher.publish(snap, beforeRollback - 3_600_000L),
            "after the rollback reset the publisher must go on and try to send (and fail here)");

        assertEquals(0L, TestObjects.getField(SummaryPublisher.class, publisher, "lastIdWarnAtMs"),
            "the server-id warning anchor is on the old timeline; if it is not cleared, that "
                + "warning stays silent for the whole rollback window -- the original bug simply "
                + "moves to a different log line");
        assertEquals(0L, TestObjects.getField(SummaryPublisher.class, publisher, "lastSentAtMs"),
            "the send anchor itself must be reset so interval accounting restarts");

        // 发送失败的锚点会在**清掉之后**被这次失败重新写上（那是正确行为），
        // 所以不能用"它等于 0"来断言重置发生过。可观测的等价事实是：
        // 锚点若没被清，这次失败会被限频吞掉、日志一条不出；清掉了才会立刻打出来。
        assertTrue(warnings.stream().skip(warningsBefore).anyMatch(PublisherFailureThrottleTest::isFailLog),
            "the send-failure anchor was parked in the future by the rollback; if it had not been "
                + "cleared, this failure would be silently throttled. Actual new warnings: "
                + warnings.subList(warningsBefore, warnings.size()));
    }

    // ==================== 接缝本身（生产判据的纯函数部分） ====================

    /**
     * {@code CarrierSource.firstOf}：空集合与 null 都降级成"没有载体"，非空取第一个。
     *
     * <p>这条判据是 {@code pickCarrier()} 里唯一有分支的部分（其余只是转发），把它从
     * "藏在 lambda 里"提成静态方法是这次接缝改动的一部分。它必须可测：写错的表现是
     * 空服时抛 {@code NoSuchElementException}，被 catch 吞成"空服"——结论碰巧对，
     * 但日志里会多一条毫无意义的堆栈，掩盖真正的原因。</p>
     */
    @Test
    @DisplayName("载体选择：null / 空集合 → null，非空取第一个")
    void firstOfPicksFirstAndTreatsEmptyAsNone() {
        PluginMessageRecipient a = workingCarrier(payloads);
        PluginMessageRecipient b = workingCarrier(payloads);

        assertSame(a, SummaryPublisher.CarrierSource.firstOf(List.of(a, b)),
            "must take the first element (order matters: multi-server debugging relies on "
                + "'the carrier is just the first online player')");
        assertEquals(null, SummaryPublisher.CarrierSource.firstOf(List.of()),
            "empty server must degrade to null (-> NO_PLAYER), not throw NoSuchElementException");
        assertEquals(null, SummaryPublisher.CarrierSource.firstOf(null),
            "a wrapped server may return null from getOnlinePlayers(); that is 'no players', "
                + "not an error");
    }

    /**
     * 生产构造器必须仍然走"第一个在线玩家"，而不是被接缝默认成别的东西。
     *
     * <p>{@code plugin.getServer()} 为 {@code null} 时（本测试的壳就是这样），
     * {@code CarrierSource.fromServer} 里那行会抛 {@code NullPointerException}，
     * 由 {@code pickCarrier()} 接住 → {@code NO_PLAYER}。这条锁的是<b>默认值</b>：
     * 若有人把单参构造器改成注入一个"总是返回假载体"的默认实现，测试里会静默变成 SENT，
     * 而生产环境则可能把消息发到一个不相干的连接上。</p>
     */
    @Test
    @DisplayName("单参构造器的默认来源仍是服务端在线玩家（server=null → NO_PLAYER）")
    void productionConstructorStillUsesTheServer() {
        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, plugin, "logger", logger);
        ConfigManager cm = new ConfigManager(plugin);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.network_enabled = true;
        cfg.network_serverId = "throttle-test";
        cfg.network_pushIntervalSeconds = 10;
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);
        // 刻意不设 server：保持 JavaPlugin.server = null，模拟"取不到玩家"。

        SummaryPublisher publisher = new SummaryPublisher(plugin);
        assertEquals(SummaryPublisher.Result.NO_PLAYER, publisher.publish(snapshot(), 1_796_000_000_000L),
            "the public constructor must wire the real server-backed source; a default that "
                + "invents a carrier would turn empty-server publishing into a silent no-op SENT");
        assertFalse(publisher.channelRegistered(),
            "sanity: with no server there is no messenger either -- if this ever returns true, "
                + "the fixture is not the isolated shell this test assumes");
    }
}
