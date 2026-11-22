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

import java.lang.reflect.Method;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SummaryPublisher} 里<b>此前一行都没跑过</b>的那几条路径：{@code buildReport} 的哨兵闸门、
 * {@code resolveServerId} 的身份决策、以及 {@code publish} 的早退分支。
 *
 * <h2>为什么这个类值得单独存在</h2>
 *
 * <p>审计实测（809 个测试时）只有 {@code shouldSend} 与 {@code fieldsAvailable} 两个静态函数被
 * 碰过。{@code buildReport} / {@code resolveServerId} / {@code publish} 零覆盖，于是下面三个变异
 * <b>全部 SURVIVED</b>：</p>
 *
 * <ol>
 *   <li>去掉 {@code buildReport} 里 MSPT 的 {@code msptAvg > 0} 闸门 → 全绿。
 *       这是<b>可达 bug</b>：TPS 反推路径在满 20 TPS 时会把 {@code msptAvg} 置为 {@code -1}，
 *       同时<b>不</b>把 {@code MODULE_MSPT} 加进 {@code unavailable}，于是 {@code -1} 会被
 *       当成真实读数发给代理，面板上渲染成绿色的 "-1.0 毫秒"。</li>
 *   <li>{@code resolveServerId} 加回"首个世界名"回退 → 全绿。多后端全部派生成 {@code world}，
 *       代理侧互相覆盖，面板只剩 1 行且数字全错，且不报错不告警。</li>
 *   <li>{@code onCycleFailure} 去掉 {@code publishNetworkSummary()} → 全绿（见
 *       {@code CollectorFailurePublishesTest}）。</li>
 * </ol>
 *
 * <h2>为什么走反射而不是抽函数</h2>
 *
 * <p>{@code buildReport} / {@code resolveServerId} 是私有实例方法，但它们的<b>全部输入都是纯数据</b>
 * （{@link MetricsSnapshot} + {@link ConfigManager.ConfigData} + 三个标量），不碰 Bukkit 也不需要
 * 真实服务端。所以"造壳 + 反射直调"就能完整覆盖，不必为了可测性改动生产代码——本仓库的
 * {@code TestObjects} 类注释二也写明"能用普通构造器就别动生产代码"。</p>
 *
 * <p>反射的代价是<b>签名漂移会静默失效</b>，所以 {@link #invokeBuildReport} 按<b>参数类型</b>
 * 而不是位置装配参数，并在找不到预期形状时抛一条可读的错误——而不是让测试"看起来跑过了"。</p>
 */
class SummaryPublisherReportTest {

    private static final Logger LOGGER = Logger.getLogger("pscope-publisher-report-test");

    // ==================== 夹具 ====================

    /**
     * 造一个能跑 {@code buildReport} / {@code resolveServerId} 的 publisher。
     *
     * <p>不启动 Bukkit：{@link PerfScope} 用 {@code allocateInstance} 造壳（构造器会触发
     * {@code ServerCompat} 静态初始化），{@link ConfigManager} 的构造器只赋字段，可以直接 new。
     * {@code server} 字段<b>保持 null</b>——这正是"取不到端口"的真实降级路径，
     * {@code resolveServerId} 会走它的 {@code catch} 分支。</p>
     */
    private static SummaryPublisher publisher(ConfigManager.ConfigData cfg) {
        PerfScope plugin = AlertFixtures.barePlugin(LOGGER);
        ConfigManager cm = new ConfigManager(plugin);
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);
        return new SummaryPublisher(plugin);
    }

    private static ConfigManager.ConfigData config() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.network_enabled = true;
        cfg.network_serverId = "lobby";
        cfg.prometheus_serverId = "";
        cfg.network_pushIntervalSeconds = 10;
        return cfg;
    }

    /** 一份"所有模块都正常"的快照。各用例在此基础上单点修改。 */
    private static MetricsSnapshot healthySnapshot() {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.tps1m = 19.95;
        snap.tps5m = 19.90;
        snap.tps15m = 19.85;
        snap.msptAvg = 12.5;
        // 必须显式设成"实测"来源：默认值 UNKNOWN(-1) 会被 buildReport 当成"没有 MSPT"。
        snap.msptSource = MetricsSnapshot.MSPT_SOURCE_TICK_TIMES;
        snap.playerCount = 42;
        return snap;
    }

    /**
     * 反射调用 {@code buildReport}。
     *
     * <p>按<b>参数类型</b>装配，因此对"多一个 serverId 入参"这类重构免疫。三种 {@code long}
     * 的出现顺序按生产代码的语义假定为 {@code (collectedAt, nowMs)}——这是全仓一致的约定；
     * 若哪天顺序反了，{@link #collectedAtFallsBackToNowWhenNeverCollected()} 会立刻红。</p>
     */
    private static NetworkSummary.Report invokeBuildReport(SummaryPublisher publisher,
                                                           MetricsSnapshot snap,
                                                           ConfigManager.ConfigData cfg,
                                                           String serverId,
                                                           long collectedAt, long nowMs,
                                                           boolean alive) {
        Method m = null;
        for (Method candidate : SummaryPublisher.class.getDeclaredMethods()) {
            if (candidate.getName().equals("buildReport")) {
                m = candidate;
                break;
            }
        }
        if (m == null) {
            throw new IllegalStateException(
                "SummaryPublisher#buildReport 不存在了。本测试靠它覆盖哨兵闸门；"
                    + "若它被改名/内联，请同步改这里（不要删掉这组断言）。");
        }
        m.setAccessible(true);

        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        int longs = 0;
        boolean sawSnapshot = false;
        boolean sawConfig = false;
        boolean sawServerId = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == MetricsSnapshot.class) {
                args[i] = snap;
                sawSnapshot = true;
            } else if (t == ConfigManager.ConfigData.class) {
                args[i] = cfg;
                sawConfig = true;
            } else if (t == String.class) {
                args[i] = serverId;
                sawServerId = true;
            } else if (t == boolean.class) {
                args[i] = alive;
            } else if (t == long.class) {
                args[i] = (longs++ == 0) ? collectedAt : nowMs;
            } else {
                throw new IllegalStateException(
                    "SummaryPublisher#buildReport 出现了本测试没预料到的参数类型 " + t.getName()
                        + "：请扩展本方法的装配逻辑，不要绕过它。");
            }
        }
        if (!sawSnapshot || !sawConfig) {
            throw new IllegalStateException(
                "SummaryPublisher#buildReport 的签名不再接收 MetricsSnapshot / ConfigData："
                    + "本测试的前提（纯数据输入）已不成立。");
        }
        if (!sawServerId) {
            throw new IllegalStateException(
                "SummaryPublisher#buildReport 不再接收 serverId —— 说明身份解析又回到了方法内部，"
                    + "那会让 resolveServerId 重新变成不可测。请保持注入式签名。");
        }
        try {
            return (NetworkSummary.Report) m.invoke(publisher, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("调用 buildReport 失败", e.getCause() == null ? e : e.getCause());
        }
    }

    private static NetworkSummary.Report build(SummaryPublisher p, MetricsSnapshot snap) {
        return invokeBuildReport(p, snap, config(), "lobby", 1_000L, 2_000L, true);
    }

    // ==================== MSPT 的三道闸门 ====================

    /**
     * <b>变异杀手 1</b>：{@code msptAvg = -1} 且模块"可用"时，绝不能把 -1 发出去。
     *
     * <p>这条路径是<b>真实可达</b>的，不是假想：{@code collectMSPT_TPSFallback} 在满 20 TPS 时
     * 把 {@code msptAvg} 置为 {@code -1}，同时</p>
     *
     * <ul>
     *   <li>{@code MODULE_MSPT} <b>不</b>在 {@code unavailable} 里（采集"成功"了）；</li>
     *   <li>{@code msptSource} 是 {@code TPS_FALLBACK(2)} 而不是 {@code UNKNOWN(-1)}。</li>
     * </ul>
     *
     * <p>所以只看"模块可用吗"与"来源是 UNKNOWN 吗"这两个闸门，<b>两个都放行</b>，
     * {@code -1} 就上路了。代理侧把它渲染成绿色的 "-1.0 毫秒"（"tick 耗时负一毫秒"）。
     * 唯一挡住它的是第三个闸门：{@code fieldsAvailable(..., snap.msptAvg, 0)} 里的
     * {@code msptAvg > 0}。</p>
     *
     * <p><b>实测</b>：删掉那个 {@code > 0}（改成只判模块可用）后，本用例变红。</p>
     */
    @Test
    @DisplayName("MSPT 哨兵：msptAvg=-1 且模块可用、来源是 TPS_FALLBACK 时绝不发布")
    void msptSentinelIsNeverPublished() {
        MetricsSnapshot snap = healthySnapshot();
        snap.msptAvg = -1.0;                                   // TPS 反推路径满帧时的哨兵
        snap.msptSource = MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK;
        snap.unavailable.clear();                              // 模块"可用"

        NetworkSummary.Report r = build(publisher(config()), snap);
        assertNull(r.msptAvg(),
            "msptAvg=-1 是未采集哨兵，不能当作真实读数发出去——"
                + "代理上会渲染成绿色的「-1.0 毫秒」。挡住它的只有 msptAvg > 0 这一道闸门。");
        assertFalse(r.toMap().containsKey("msptAvg"),
            "哨兵值必须让整个键缺席，而不是写成 -1.0 或 0.0");
    }

    /** 来源是 UNKNOWN 时同样不发（第二道闸门）。 */
    @Test
    @DisplayName("MSPT 闸门二：msptSource=UNKNOWN 时即使值 >0 也不发布")
    void msptWithUnknownSourceIsNotPublished() {
        MetricsSnapshot snap = healthySnapshot();
        snap.msptSource = MetricsSnapshot.MSPT_SOURCE_UNKNOWN;  // 本周期没走任何一条 MSPT 路径
        snap.msptAvg = 12.5;

        assertNull(build(publisher(config()), snap).msptAvg(),
            "来源未知说明这个数不是本周期测出来的，不能当现状发出去");
    }

    /** 模块进 unavailable 时不发（第一道闸门）。 */
    @Test
    @DisplayName("MSPT 闸门一：模块进了 unavailable 时不发布")
    void msptWithUnavailableModuleIsNotPublished() {
        MetricsSnapshot snap = healthySnapshot();
        snap.unavailable.add(MetricsSnapshot.MODULE_MSPT);
        assertNull(build(publisher(config()), snap).msptAvg());
    }

    /** 正向对照：三个闸门全过时必须发，且值原样。 */
    @Test
    @DisplayName("MSPT 正向：三道闸门全过时按原值发布")
    void healthyMsptIsPublished() {
        NetworkSummary.Report r = build(publisher(config()), healthySnapshot());
        assertEquals(12.5, r.msptAvg(), 1e-9);
        assertEquals(12.5, ((Number) r.toMap().get("msptAvg")).doubleValue(), 1e-9);
    }

    /**
     * MSPT = 0.0 也不发：{@code 0} 对 tick 耗时不合法，它是"没采到"的另一种写法。
     *
     * <p>注意这与 TPS=0.0 <b>语义相反</b>：TPS 0 是真实的"服务器冻结"，必须发出去；
     * MSPT 0 是不可能的读数，只能当缺失。两者用同一个 {@code fieldsAvailable} 但门限不同
     * （{@code > -1} 与 {@code > 0}），这个不对称正是本用例钉住的东西。</p>
     */
    @Test
    @DisplayName("MSPT=0.0 不发布（tick 耗时不可能是 0），但 TPS=0.0 必须发布")
    void zeroMsptIsMissingWhileZeroTpsIsReal() {
        MetricsSnapshot zeroMspt = healthySnapshot();
        zeroMspt.msptAvg = 0.0;
        assertNull(build(publisher(config()), zeroMspt).msptAvg(),
            "0.0 毫秒的 tick 耗时不可能存在，只能当缺失");

        MetricsSnapshot zeroTps = healthySnapshot();
        zeroTps.tps1m = 0.0;
        zeroTps.tps5m = 0.0;
        zeroTps.tps15m = 0.0;
        NetworkSummary.Report r = build(publisher(config()), zeroTps);
        assertEquals(0.0, r.tps1m(), 1e-9,
            "TPS 0 是真实的「服务器冻结」，必须发出去；漏掉它就等于把冻结说成「没数据」");
        assertNotNull(r.toMap().get("tps1m"));
    }

    // ==================== TPS / 玩家数闸门 ====================

    /** TPS 模块不可用或值是 -1 时，三个窗口一起缺席（不能只省一个）。 */
    @Test
    @DisplayName("TPS 闸门：模块不可用或值为 -1 时三个窗口一起缺席")
    void tpsIsOmittedAsAGroup() {
        MetricsSnapshot unavailable = healthySnapshot();
        unavailable.unavailable.add(MetricsSnapshot.MODULE_TPS);
        NetworkSummary.Report a = build(publisher(config()), unavailable);
        assertNull(a.tps1m());
        assertNull(a.tps5m());
        assertNull(a.tps15m());

        MetricsSnapshot sentinel = healthySnapshot();
        sentinel.tps1m = -1.0;                    // 未采集哨兵，但模块没标 unavailable
        NetworkSummary.Report b = build(publisher(config()), sentinel);
        assertNull(b.tps1m(), "tps1m=-1 必须被 -1 门限挡掉");
    }

    /**
     * 玩家数是计数型：没采到用 -1 而不是 0；0 人必须如实发。
     *
     * <p>这条同时钉住"不要把 players 也套上 {@code > 0} 门限"——空服 0 人是合法读数，
     * 套上门限就会把空服说成"没数据"。</p>
     */
    @Test
    @DisplayName("玩家数：未采集→-1 哨兵，0 人→如实 0")
    void playerCountUsesSentinelNotZero() {
        MetricsSnapshot missing = healthySnapshot();
        missing.unavailable.add(MetricsSnapshot.MODULE_PLAYERS);
        assertEquals(NetworkSummary.COUNT_UNAVAILABLE, build(publisher(config()), missing).playerCount(),
            "没采到的计数必须是 -1（0 是合法的空服读数，不能拿来当哨兵）");

        MetricsSnapshot empty = healthySnapshot();
        empty.playerCount = 0;
        assertEquals(0, build(publisher(config()), empty).playerCount(),
            "0 人是合法读数，必须能被如实表达");
    }

    // ==================== 时间与间隔 ====================

    /**
     * 从未采集成功过（{@code collectedAt == 0}）时用 {@code nowMs} 兜底。
     *
     * <p>不兜底的话代理侧算出的年龄是"自 1970 年以来"，那条后端一启动就显示"数据过期"。
     * 这个分支只有 {@code collectedAt == 0} 才走，很容易在重构里丢掉。</p>
     */
    @Test
    @DisplayName("collectedAt=0 时用 nowMs 兜底（否则年龄从 1970 年算起）")
    void collectedAtFallsBackToNowWhenNeverCollected() {
        NetworkSummary.Report r = invokeBuildReport(
            publisher(config()), healthySnapshot(), config(), "lobby",
            0L,        // 从未采集
            7_777L,    // nowMs
            true);
        assertEquals(7_777L, r.collectedAtMs(),
            "没有真实采集时刻时必须用 nowMs，否则代理算出「数据过期 56 年」");
        assertEquals(7_777L, r.sentAtMs(), "sentAt 就是本次 nowMs");

        NetworkSummary.Report withReal = invokeBuildReport(
            publisher(config()), healthySnapshot(), config(), "lobby", 5_000L, 7_777L, true);
        assertEquals(5_000L, withReal.collectedAtMs(), "有真实时刻时不得被 nowMs 覆盖");
    }

    /** 上报间隔随包外发；配置为 0 时整条缺席（0 秒间隔会被算成"永远过期"）。 */
    @Test
    @DisplayName("pushIntervalSec 随包外发；0 时整条缺席")
    void pushIntervalIsCarriedWhenPositive() {
        ConfigManager.ConfigData cfg = config();
        cfg.network_pushIntervalSeconds = 60;
        NetworkSummary.Report r = invokeBuildReport(
            publisher(cfg), healthySnapshot(), cfg, "lobby", 1_000L, 2_000L, true);
        assertEquals(60, r.pushIntervalSec());
        assertEquals(60, ((Number) r.toMap().get("pushIntervalSec")).intValue());

        ConfigManager.ConfigData zero = config();
        zero.network_pushIntervalSeconds = 0;
        NetworkSummary.Report z = invokeBuildReport(
            publisher(zero), healthySnapshot(), zero, "lobby", 1_000L, 2_000L, true);
        assertFalse(z.toMap().containsKey("pushIntervalSec"),
            "0 = 没提供，必须整条省掉——写 0 会被接收方算成「永远过期」");
    }

    /** 存活标志原样透传：{@code alive=false} 是代理识破陈数据的唯一依据。 */
    @Test
    @DisplayName("alive 标志原样透传（false 时必须真的是 false）")
    void collectorAliveIsForwarded() {
        NetworkSummary.Report dead = invokeBuildReport(
            publisher(config()), healthySnapshot(), config(), "lobby", 1_000L, 2_000L, false);
        assertFalse(dead.collectorAlive());
        assertEquals(false, dead.toMap().get("alive"),
            "采集已死必须如实外发，否则代理的「采集已停」分支永远走不到");
    }

    // ==================== resolveServerId ====================

    private static String resolve(ConfigManager.ConfigData cfg) {
        SummaryPublisher p = publisher(cfg);
        return (String) TestObjects.invoke(p, "resolveServerId",
            new Class<?>[]{ConfigManager.ConfigData.class}, cfg);
    }

    /**
     * 优先级：{@code network.server-id} → {@code prometheus.server-id}，且都要 trim。
     *
     * <p>顺序反了不会报错，只会让两台后端用同一个 promo id —— 于是代理侧互相覆盖。
     * </p>
     */
    @Test
    @DisplayName("身份优先级：network.server-id 先于 prometheus.server-id，且 trim")
    void serverIdPriorityAndTrimming() {
        ConfigManager.ConfigData cfg = config();
        cfg.network_serverId = "  lobby  ";
        cfg.prometheus_serverId = "prom-id";
        assertEquals("lobby", resolve(cfg), "network.server-id 优先，且必须去掉首尾空白");

        cfg.network_serverId = "   ";           // 纯空白 = 没配
        cfg.prometheus_serverId = "  prom-id  ";
        assertEquals("prom-id", resolve(cfg), "network 是空白时回退 prometheus");

        cfg.network_serverId = null;
        assertEquals("prom-id", resolve(cfg), "null 也要能回退，不能 NPE");
    }

    /**
     * <b>变异杀手 2</b>：两个 id 都为空时，绝不返回"首个世界名"。
     *
     * <h2>这条为什么是这么写的</h2>
     *
     * <p>审计里那个变异是"加回首个世界名回退"，而它在这台测试 JVM 里<b>结构性不可达</b>：
     * {@code plugin.getServer()} 返回 {@code null}（{@code PerfScope} 是 allocate 出来的壳，
     * {@code JavaPlugin.server} 字段从未被赋值），所以任何走服务端的路径都只会抛 NPE。
     * 因此本用例不能"造一个 {@code world} 世界"来正面证伪——那需要一个真实的
     * {@code org.bukkit.Server}，而它在测试 JVM 里造不出来（见 {@code tools/KNOWN-TEST-LIMITS.md}：
     * {@code Proxy} 与 {@code allocateInstance} 两条路都被 {@code StructureType} 的静态初始化堵死）。</p>
     *
     * <p>所以这里改成断言<b>契约的下界</b>：返回值必须精确等于文档承诺的降级值
     * {@code "perfscope"}，且不得等于任何常见的世界名。这个断言的实际杀伤力来自两点：</p>
     *
     * <ol>
     *   <li>世界名回退最常见的写法是把 {@code getServer().getWorlds()} 放在 {@code try} 外——
     *       那样会直接 NPE，本用例报错（<b>已实测</b>，见汇报）；</li>
     *   <li>即使写成"包在 try 里、失败再回退端口"，也必须显式保留 {@code "perfscope"} 这个
     *       最终降级值；一旦有人把最终降级值改成世界名或空串，这里立刻红。</li>
     * </ol>
     *
     * <p>换句话说：这条锁的是"<b>取不到端口时的最终身份</b>"，不是"世界名回退不可能发生"。
     * 后者的正面证明需要假服务端，当前测试环境做不到——这一条缺口记在汇报里，不假装覆盖了。
     * </p>
     */
    @Test
    @DisplayName("两个 id 都空时返回固定降级值，绝不用世界名兜底")
    void blankIdsNeverFallBackToWorldName() {
        ConfigManager.ConfigData cfg = config();
        cfg.network_serverId = "";
        cfg.prometheus_serverId = "";
        String id = resolve(cfg);

        assertNotNull(id, "必须给出一个可用的身份，不能返回 null 让整条链路静默停摆");
        assertEquals("perfscope", id,
            "取不到端口时的降级值必须是固定的 \"perfscope\"。"
                + "若它变成世界名（world/World/lobby…），5 个后端会全部派生成同一个 id，"
                + "代理侧后到覆盖先到 → 面板只剩 1 行、全网均值按 1 台算，且不报错不告警。");
        for (String worldName : new String[]{"world", "World", "world_nether", "lobby", "survival"}) {
            assertNotEquals(worldName, id,
                "身份绝不能是世界名 \"" + worldName + "\" —— 绝大多数服的首个世界都叫 world，"
                    + "用它当 id 会让多后端撞成一条");
        }
        assertFalse(id.isBlank(), "空身份会让代理侧把包判成「没有身份」并丢弃");
    }

    /**
     * 显式配了但不合格的 id：返回 {@code null}（= 本次一条都不发），而不是截断或改用派生值。
     *
     * <p>这条与 {@code publish} 的 {@code REJECTED_ID} 配套。判据本体在
     * {@link SummaryPublisher#validateServerId(String)}（纯函数），这里验的是
     * {@code resolveServerId} 真的把它接进去了。</p>
     */
    @Test
    @DisplayName("显式 id 不合格时返回 null（拒发），不截断也不悄悄换成派生值")
    void invalidExplicitIdIsRejectedNotRewritten() {
        ConfigManager.ConfigData tooLong = config();
        tooLong.network_serverId = "a".repeat(SummaryPublisher.MAX_SERVER_ID_LENGTH + 1);
        assertNull(resolve(tooLong),
            "超长 id 会被代理整包丢弃；发送端必须拒发而不是截断"
                + "（截断会得到一个用户没写过的名字）");

        ConfigManager.ConfigData reserved = config();
        reserved.network_serverId = SummaryPublisher.RESERVED_SERVER_ID;
        assertNull(resolve(reserved),
            "保留字 unknown 在代理侧被当作「没有身份」丢弃；发送端必须拦住");
    }

    /** 边界：恰好 64 字符与大小写不同的保留字都必须放行（别把闸门做过火）。 */
    @Test
    @DisplayName("边界：恰好 64 字符放行；大写 Unknown 是合法显式 id")
    void boundaryIdsAreAccepted() {
        ConfigManager.ConfigData exact = config();
        exact.network_serverId = "a".repeat(SummaryPublisher.MAX_SERVER_ID_LENGTH);
        assertEquals(exact.network_serverId, resolve(exact),
            "恰好 " + SummaryPublisher.MAX_SERVER_ID_LENGTH + " 字符是合法上限，不能按 >= 判");

        ConfigManager.ConfigData cased = config();
        cased.network_serverId = "Unknown";
        assertEquals("Unknown", resolve(cased),
            "保留字判据必须区分大小写（代理侧是 \"unknown\".equals(...)），"
                + "这里拦掉大写会让「后端拒发、代理收得下」");
    }

    // ==================== validateServerId 纯函数 ====================

    /**
     * 判定表：四种结果各自的条件，且<b>顺序</b>正确（空白 → 保留字 → 长度）。
     *
     * <p>顺序反了的后果具体：一个 70 字符的 {@code unknown} 会被报成"超长"，
     * 服主改成 60 字符的 {@code unknown} 还是发不出去，白跑一轮。</p>
     */
    @Test
    @DisplayName("validateServerId：空白/保留字/超长/合格四态，顺序不能反")
    void validateServerIdTable() {
        assertEquals("BLANK", String.valueOf(SummaryPublisher.validateServerId(null)));
        assertEquals("BLANK", String.valueOf(SummaryPublisher.validateServerId("")));
        assertEquals("BLANK", String.valueOf(SummaryPublisher.validateServerId("   ")));
        assertEquals("RESERVED", String.valueOf(SummaryPublisher.validateServerId("unknown")));
        assertEquals("RESERVED", String.valueOf(SummaryPublisher.validateServerId("  unknown  ")));
        assertEquals("TOO_LONG", String.valueOf(SummaryPublisher.validateServerId(
            "a".repeat(SummaryPublisher.MAX_SERVER_ID_LENGTH + 1))));
        assertEquals("OK", String.valueOf(SummaryPublisher.validateServerId(
            "a".repeat(SummaryPublisher.MAX_SERVER_ID_LENGTH))));
        assertEquals("OK", String.valueOf(SummaryPublisher.validateServerId("lobby")));
        assertEquals("OK", String.valueOf(SummaryPublisher.validateServerId("Unknown")));

        // 顺序：超长的未知字也得先被判成保留字吗？不——先判空白、再判保留字、最后判长度，
        // 所以 "unknown"+60 个 a 会被判 TOO_LONG（它确实超长），而纯 "unknown" 判 RESERVED。
        assertEquals("TOO_LONG", String.valueOf(SummaryPublisher.validateServerId(
            "unknown" + "a".repeat(60))));
    }

    /**
     * 长度按<b>字符数</b>而不是字节数：中文 id 一字算 1。
     *
     * <p>协议里它是 JSON 字符串，代理侧读回来用的是 {@code String.length()}，两边必须同口径。
     * 若这里改用 {@code getBytes().length}，一个 22 字的中文 id 就会被判成超长（66 字节）——
     * 而世界名做 id 时中文很常见。</p>
     */
    @Test
    @DisplayName("长度口径：按字符数算，中文一字算 1")
    void lengthIsCountedInCharsNotBytes() {
        String cjk = "世界".repeat(32);            // 64 个字符、192 字节
        assertEquals(64, cjk.length());
        assertEquals("OK", String.valueOf(SummaryPublisher.validateServerId(cjk)),
            "中文 id 按字符数应恰好 64，合法；按字节数会被误判超长");

        assertEquals("TOO_LONG", String.valueOf(SummaryPublisher.validateServerId("世界".repeat(33))));
    }
}
