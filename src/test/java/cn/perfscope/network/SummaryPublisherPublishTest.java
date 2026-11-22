/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.network;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.AlertFixtures;
import cn.perfscope.support.ConfigFixtures;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SummaryPublisher#publish} 的早退分支，以及"失败周期也要上报"这条联动。
 *
 * <h2>为什么这些分支值得单独测</h2>
 *
 * <p>{@code publish} 是跨服链路的入口，它此前<b>一行都没跑过</b>。它的六个返回结果
 * （{@code SENT / THROTTLED / UNCHANGED / NO_PLAYER / DISABLED / REJECTED_ID}）里，
 * 每一个都对应一种"代理那边看不到数"的具体原因，而服主排查时唯一能依靠的就是这个结果
 * ——它被 {@code /perfscope status} 展示出来。</p>
 *
 * <p>结果写错的后果具体：把 {@code NO_PLAYER} 写成 {@code SENT}，服主会去查代理配置
 * 而不是"这台后端空服"；把 {@code DISABLED} 写成 {@code SENT}，他会以为功能开着。</p>
 *
 * <h2>能测到哪、不能测到哪（如实标注）</h2>
 *
 * <p><b>能测</b>：所有在 {@code pickCarrier()} 之前的判断——参数校验、配置开关、身份闸门、
 * 以及"没有玩家时必须承认 NO_PLAYER"。这些路径全部不碰 Bukkit 的真实类型。</p>
 *
 * <p><b>测不到</b>：真实 Bukkit {@code Player} 的语义（如"向已掉线的玩家发"），那需要真服务端。
 * <b>注意</b>：{@code SENT} / {@code FAILED} 两条路径<b>现在已可测</b>——见
 * {@code PublisherFailureThrottleTest}，它经 {@code SummaryPublisher.CarrierSource} 接缝注入载体，
 * 于是 {@code sendPluginMessage} 那一行真的被执行（包括它的 {@code catch}）。
 * 所以本类只负责"早退分支"，不承担"发送分支"的覆盖。</p>
 *
 * <p><b>{@code THROTTLED} 从未被返回过</b>：{@code publish} 在 {@code shouldSend} 为 false 时
 * 返回的是 {@link SummaryPublisher.Result#UNCHANGED}，所以 {@code THROTTLED} 目前是一个
 * 无人返回的枚举值。这里记下来而不是改掉——改枚举值属于行为变更，不属于补测试的范围。</p>
 *
 * <h2>失败周期上报：这里是那个变异的唯一杀手</h2>
 *
 * <p>审计里"删掉 {@code onCycleFailure} 末尾的 {@code publishNetworkSummary()} 调用"
 * 这个变异 SURVIVED。它直接导致代理侧的 {@code COLLECTOR_DEAD} 分支成为死代码：
 * 采集真的坏了，面板上显示的是"数据过期（可能空服）"，把服主指向空服/网络这个错误方向。</p>
 *
 * <p>本类怎么观测"这个方法被调了"：{@code publish} 在 {@code pickCarrier} 之前一定会走
 * {@code resolveServerId}，而后者在"两个 id 都没配"时会写限频告警时间戳
 * {@code lastIdWarnAtMs}。那是一个<b>只由 publish 路径产生</b>的可观测副作用，
 * 所以用它当探针既真实又不需要伪造玩家。</p>
 */
class SummaryPublisherPublishTest {

    private static final Logger LOGGER = Logger.getLogger("pscope-publisher-publish-test");

    // ==================== 夹具 ====================

    private static ConfigManager.ConfigData enabledConfig() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.network_enabled = true;
        cfg.network_serverId = "lobby";
        cfg.prometheus_serverId = "";
        cfg.network_pushIntervalSeconds = 10;
        return cfg;
    }

    /** publisher + 它的 plugin（需要 plugin 才能读到配置与 logger）。 */
    private static final class Rig {
        final SummaryPublisher publisher;
        final PerfScope plugin;
        final ConfigManager cm;

        Rig(ConfigManager.ConfigData cfg) {
            plugin = AlertFixtures.barePlugin(LOGGER);
            cm = new ConfigManager(plugin);
            TestObjects.setField(ConfigManager.class, cm, "config", cfg);
            TestObjects.setField(PerfScope.class, plugin, "configManager", cm);
            publisher = new SummaryPublisher(plugin);
            // 把 publisher 挂到 plugin 上，供"失败周期上报"那条用例经 publishNetworkSummary 取到。
            TestObjects.setField(PerfScope.class, plugin, "summaryPublisher", publisher);
        }
    }

    private static MetricsSnapshot snapshot() {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.tps1m = 19.95;
        snap.tps5m = 19.90;
        snap.tps15m = 19.85;
        snap.msptAvg = 12.5;
        snap.msptSource = MetricsSnapshot.MSPT_SOURCE_TICK_TIMES;
        snap.playerCount = 42;
        return snap;
    }

    // ==================== publish 的早退分支 ====================

    /**
     * 快照为 null → {@code NO_PLAYER}。
     *
     * <p>返回值选 {@code NO_PLAYER} 而不是自己造一个 {@code NOTHING_TO_SEND}：
     * 两者对服主的含义一样（这一轮没发出去），多一个枚举值只会让 status 多一种他看不懂的状态。
     * 这条锁的是"不要为了好看把它改成别的值"。</p>
     */
    @Test
    @DisplayName("snap=null → NO_PLAYER（不抛 NPE）")
    void nullSnapshotIsNoPlayer() {
        Rig rig = new Rig(enabledConfig());
        assertEquals(SummaryPublisher.Result.NO_PLAYER, rig.publisher.publish(null, 1_000L));
    }

    /** 配置从未加载成功（{@code get() == null}）→ {@code DISABLED}，不抛 NPE。 */
    @Test
    @DisplayName("config=null → DISABLED（不抛 NPE）")
    void nullConfigIsDisabled() {
        Rig rig = new Rig(null);
        TestObjects.setField(ConfigManager.class, rig.cm, "config", null);
        assertEquals(SummaryPublisher.Result.DISABLED, rig.publisher.publish(snapshot(), 1_000L),
            "配置还没加载成功时必须安静地报 DISABLED——抛 NPE 会让采集周期整轮失败");
    }

    /** 功能开关关闭 → {@code DISABLED}。 */
    @Test
    @DisplayName("network.enabled=false → DISABLED")
    void disabledConfigIsDisabled() {
        ConfigManager.ConfigData cfg = enabledConfig();
        cfg.network_enabled = false;
        Rig rig = new Rig(cfg);
        assertEquals(SummaryPublisher.Result.DISABLED, rig.publisher.publish(snapshot(), 1_000L));
    }

    /**
     * 显式配置的 server-id 不合格 → {@code REJECTED_ID}，且这个判断<b>早于</b>
     * "有没有玩家"与"要不要限频"。
     *
     * <p>顺序很重要：身份问题是每次采集都成立的配置错误，必须每轮都被看见。
     * 若它排在 {@code pickCarrier} 之后，空服期间改了 id 会一直没有反馈，
     * 等有玩家时才突然发现发不出去——延迟极长的反馈最难查。</p>
     */
    @Test
    @DisplayName("id 不合格 → REJECTED_ID，且早于「有没有玩家」判断")
    void invalidIdIsRejectedBeforeCarrierCheck() {
        ConfigManager.ConfigData cfg = enabledConfig();
        cfg.network_serverId = "a".repeat(SummaryPublisher.MAX_SERVER_ID_LENGTH + 1);
        Rig rig = new Rig(cfg);

        assertEquals(SummaryPublisher.Result.REJECTED_ID, rig.publisher.publish(snapshot(), 1_000L),
            "超长 id 必须报 REJECTED_ID（可自查），而不是混进 FAILED 让人去查网络");
    }

    /**
     * 配置正常但没有玩家载体 → {@code NO_PLAYER}。
     *
     * <p>这是 plugin messaging 的固有边界，不是缺陷：消息必须借某个玩家的连接发出去。
     * 本用例的 plugin 是 allocate 出来的壳，{@code getServer()} 返回 {@code null}，
     * {@code pickCarrier} 的 {@code catch} 因此生效——它精确模拟了"取不到玩家"这一面。
     * 关键断言是<b>不能报成 SENT</b>：报 SENT 会让服主不查"空服"这个真实原因。</p>
     */
    @Test
    @DisplayName("没有玩家载体 → NO_PLAYER（绝不谎报 SENT）")
    void noCarrierIsNoPlayer() {
        Rig rig = new Rig(enabledConfig());
        assertEquals(SummaryPublisher.Result.NO_PLAYER, rig.publisher.publish(snapshot(), 1_000L),
            "没有玩家时必须如实报 NO_PLAYER。taxi 成 SENT 会让服主去查代理，"
                + "而真实原因只是这台后端空服。");
    }

    /** 重复调用仍报 NO_PLAYER（不会因为"第二次"就换个说法）。 */
    @Test
    @DisplayName("重复调用保持 NO_PLAYER（不因次数而改变结论）")
    void repeatedCallsStayNoPlayer() {
        Rig rig = new Rig(enabledConfig());
        assertEquals(SummaryPublisher.Result.NO_PLAYER, rig.publisher.publish(snapshot(), 1_000L));
        assertEquals(SummaryPublisher.Result.NO_PLAYER, rig.publisher.publish(snapshot(), 20_000L),
            "没有玩家这一点不会随时间改变；换成 THROTTLED/UNCHANGED 会让服主以为"
                + "「以前发过，现在被限频了」");
    }

    // ==================== 失败周期必须上报（变异杀手 3） ====================

    /**
     * <b>变异杀手 3</b>：{@code onCycleFailure} 必须调用 {@code publishNetworkSummary()}。
     *
     * <h2>这个变异为什么危险</h2>
     *
     * <p>删掉那一行之后，{@code alive} 就<b>恒为 true</b>——因为只有成功路径会刷新
     * {@code lastCollectAtMs}，而 {@code publish} 里的存活判据正是"连续失败次数"。
     * 于是采集真的坏掉时：</p>
     *
     * <ul>
     *   <li>后端停止上报，或上报的仍是最后一张<b>健康</b>的快照；</li>
     *   <li>代理侧等 3×间隔后判成 {@link NetworkView.State#STALE}（"数据过期，可能空服"）；</li>
     *   <li>{@link NetworkView.State#COLLECTOR_DEAD} 分支<b>永远走不到</b>——它是死代码。</li>
     * </ul>
     *
     * <p>结果是把服主指向"空服 / 网络"这个完全错误的方向，而真相是"后端采集器死了"。
     * 这正是本插件反复修的那类"把盲点报成健康"。</p>
     *
     * <h2>怎么观测</h2>
     *
     * <p>不伪造玩家：{@code publish} 在 {@code pickCarrier} 之前必然走
     * {@code resolveServerId}，而"两个 id 都没配"时它会写 {@code lastIdWarnAtMs}。
     * 那是一个<b>只由 publish 路径产生</b>的副作用，所以拿它当探针。
     * 用例前后把该字段清成 0，再断言它被写成了非 0。</p>
     */
    @Test
    @DisplayName("失败周期必须上报：onCycleFailure 会调用 publish（否则 COLLECTOR_DEAD 是死代码）")
    void failureCycleStillPublishesSummary() {
        // 两个 id 都留空：这样 publish 内部一定会走"派生端口 + 限频告警"那条分支，
        // 从而写下 lastIdWarnAtMs —— 我们的探针。
        ConfigManager.ConfigData cfg = enabledConfig();
        cfg.network_serverId = "";
        cfg.prometheus_serverId = "";

        Rig rig = new Rig(cfg);
        MetricsCollector collector = collectorWithPublishedSnapshot(rig.plugin, cfg, snapshot());

        // 探针归零：证明下面那个非 0 值确实是这次调用写进去的。
        TestObjects.setField(SummaryPublisher.class, rig.publisher, "lastIdWarnAtMs", 0L);
        assertEquals(0L, TestObjects.getField(SummaryPublisher.class, rig.publisher, "lastIdWarnAtMs"),
            "前提：探针已归零");

        TestObjects.invoke(collector, "onCycleFailure",
            new Class<?>[]{Throwable.class}, new IllegalStateException("simulated collect failure"));

        Object probe = TestObjects.getField(SummaryPublisher.class, rig.publisher, "lastIdWarnAtMs");
        assertNotEquals(0L, ((Long) probe).longValue(),
            "onCycleFailure 末尾必须调用 publishNetworkSummary()。"
                + "删掉那一行后 alive 恒为 true，代理侧的「采集已停」分支变成死代码——"
                + "采集真坏了，面板上显示「数据过期（可能空服）」，把人指向完全错误的方向。");
    }

    /**
     * 成功周期也必须上报（对称的另一半）。
     *
     * <p>只测失败路径的话，"把 publish 挪到只有 onCycleFailure 调"这种改动会漏掉——
     * 那会让正常态的 {@code collectedAt} 永远不前进，同样触发"数据过期"。</p>
     */
    @Test
    @DisplayName("成功周期也必须上报：onCycleSuccess 会调用 publish")
    void successCycleAlsoPublishesSummary() {
        ConfigManager.ConfigData cfg = enabledConfig();
        cfg.network_serverId = "";
        cfg.prometheus_serverId = "";

        Rig rig = new Rig(cfg);
        MetricsCollector collector = collectorWithPublishedSnapshot(rig.plugin, cfg, snapshot());
        TestObjects.setField(SummaryPublisher.class, rig.publisher, "lastIdWarnAtMs", 0L);

        TestObjects.invoke(collector, "onCycleSuccess", new Class<?>[0]);

        Object probe = TestObjects.getField(SummaryPublisher.class, rig.publisher, "lastIdWarnAtMs");
        assertNotEquals(0L, ((Long) probe).longValue(),
            "成功周期也必须上报：否则 collectedAt 永不前进，"
                + "一个完全健康的后端会在 3 个间隔后被判成「数据过期」");
    }

    /**
     * 没有 publisher（功能未装配）时，两个周期回调都<b>安静跳过</b>，不抛 NPE。
     *
     * <p>{@code PerfScope.summaryPublisher} 在 {@code network.enabled=false} 时是 {@code null}。
     * 若 {@code publishNetworkSummary} 忘了那道 null 检查，每一轮采集都会抛 NPE
     * ——而它被外层 catch 吞掉，表现为"采集周期偶发失败"，根因极难定位。</p>
     */
    @Test
    @DisplayName("publisher 未装配时两个周期回调安静跳过（不 NPE）")
    void missingPublisherIsToleratedOnBothPaths() {
        ConfigManager.ConfigData cfg = enabledConfig();
        PerfScope plugin = AlertFixtures.barePlugin(LOGGER);
        ConfigManager cm = new ConfigManager(plugin);
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);
        // 刻意<b>不</b>设 summaryPublisher：保持它为 null（功能未启用的真实状态）。

        MetricsCollector collector = collectorWithPublishedSnapshot(plugin, cfg, snapshot());

        // 两个回调都不许抛。
        TestObjects.invoke(collector, "onCycleSuccess", new Class<?>[0]);
        TestObjects.invoke(collector, "onCycleFailure",
            new Class<?>[]{Throwable.class}, new IllegalStateException("boom"));

        assertEquals(1, collector.consecutiveFailures(),
            "失败计数仍必须被记上——publisher 缺席不能影响本地的采集健康统计");
        assertTrue(collector.isCollectorDead(), "采集死亡标志也必须照常置位");
    }

    /**
     * 失败路径必须把 {@code consecutiveFailures} 推到"让 {@code publish} 判定为不存活"的量级。
     *
     * <p>这是把两半拼起来的<b>契约断言</b>：{@code onCycleFailure} 每失败一次就 +1，
     * 而 {@code SummaryPublisher} 的存活判据是"失败次数 &lt; 3"。所以连续失败 3 次之后，
     * 下一次 {@code publish} 就会带上 {@code alive=false}。</p>
     *
     * <p>它不伪造玩家（那做不到），但<b>能</b>证明"计数会跨过那道门槛"——
     * 而这正是 {@code alive=false} 存在的前提。门槛值本身由
     * {@code SummaryPublisherReportTest.collectorAliveIsForwarded} 从入参一侧锁住。</p>
     */
    @Test
    @DisplayName("连续失败跨过 DEAD_AFTER_FAILURES 门槛后，publish 才会判定为不存活")
    void repeatedFailuresCrossTheLivenessThreshold() {
        ConfigManager.ConfigData cfg = enabledConfig();
        Rig rig = new Rig(cfg);
        MetricsCollector collector = collectorWithPublishedSnapshot(rig.plugin, cfg, snapshot());

        for (int i = 1; i <= 3; i++) {
            TestObjects.invoke(collector, "onCycleFailure",
                new Class<?>[]{Throwable.class}, new IllegalStateException("cycle " + i));
            assertEquals(i, collector.consecutiveFailures(),
                "每次失败必须 +1：publish 的存活判据完全依赖这个计数");
        }
        assertTrue(collector.consecutiveFailures() >= 3,
            "3 次连续失败后必须已跨过 DEAD_AFTER_FAILURES 门槛（默认 3），"
                + "否则 alive=false 永远发不出去，代理侧的「采集已停」分支仍是死代码");

        // 成功一次即清零 —— 这条保证"恢复"能被代理及时看见（alive 回到 true）。
        TestObjects.invoke(collector, "onCycleSuccess", new Class<?>[0]);
        assertEquals(0, collector.consecutiveFailures(),
            "成功一次必须清零，否则一次抖动会让这台后端在面板上永远显示「采集已停」");
    }

    // ==================== 采集器壳 ====================

    /**
     * 造一个"已发布过一张快照、并且挂了 publisher"的 collector 壳。
     *
     * <p>为什么要往 history 里塞快照：{@code publishNetworkSummary} 用 {@code getLatest()}
     * 取当前数据，返回 {@code null} 时会直接跳过——那会让"有没有调用 publish"这件事
     * 无法从探针上看出来（虽然探针本身不会触发）。塞一条之后，publish 才会真的跑。</p>
     */
    private static MetricsCollector collectorWithPublishedSnapshot(
        PerfScope plugin, ConfigManager.ConfigData cfg, MetricsSnapshot snap) {
        MetricsCollector collector = TestObjects.allocate(MetricsCollector.class);
        ConfigManager cm = new ConfigManager(plugin);
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(MetricsCollector.class, collector, "configManager", cm);
        // plugin 是 final 字段，allocateInstance 不执行字段初始化器 → 必须显式补。
        // 漏补的表现是 onCycleSuccess/onCycleFailure 里 plugin.getLogger() 抛 NPE，
        // 而那条异常会被外层 catch 吞成"采集周期失败"，看起来像采集真的坏了。
        TestObjects.setField(MetricsCollector.class, collector, "plugin", plugin);

        Deque<MetricsSnapshot> history = new ConcurrentLinkedDeque<>();
        history.add(snap);
        TestObjects.setField(MetricsCollector.class, collector, "history", history);

        // 让 publish 判定为"还活着"（连续失败为 0）——第一条用例要验的是"调用发生了"，
        // 不是"alive 的值对不对"（后者由 SummaryPublisherReportTest 覆盖）。
        TestObjects.setField(MetricsCollector.class, collector, "consecutiveCollectFailures", 0);
        TestObjects.setField(MetricsCollector.class, collector, "lastCollectAtMs",
            System.currentTimeMillis());
        return collector;
    }
}
