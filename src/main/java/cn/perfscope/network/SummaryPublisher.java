package cn.perfscope.network;

import cn.perfscope.PerfScope;
import cn.perfscope.compat.SchedulerAdapter;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageRecipient;

import java.nio.charset.StandardCharsets;

/**
 * 后端侧：把摘要通过 plugin messaging 发给代理。
 *
 * <h2>这条链路为什么必须靠玩家</h2>
 *
 * <p>Paper 的 plugin messaging 走的是**已建立连接的玩家所持有的 TCP 连接**——插件消息被
 * 塞进那个玩家的数据包流里，由代理转发给其它插件。它不是一个独立通道。推论有两条，都很硬：</p>
 *
 * <ol>
 *   <li><b>空服时发不出去。</b>没有玩家就没有连接，{@link #publish} 会返回
 *       {@link Result#NO_PLAYER}，代理侧在这个窗口内看到的是"这个后端没有数据"。</li>
 *   <li><b>必须有一个玩家当载体。</b>消息由哪个玩家的连接送出去不影响内容，所以随便挑一个
 *       在线玩家即可（这里挑第一个）。</li>
 * </ol>
 *
 * <p>这两条是这个方案的固有代价，不是实现缺陷。空服恰恰常是最想看的时刻之一，所以代理侧
 * 的展示层必须把"没数据"与"数据是 0"分开——见 {@link NetworkSummary} 的哨兵约定。</p>
 *
 * <h2>为什么只在有变化时发</h2>
 *
 * <p>采集周期默认 1 秒（{@code general.update-interval}）。每个周期都发会把 5 个后端变成
 * 每秒 5 条跨服消息，而摘要的价值在"代理能看出谁不正常"，不在高频。因此按
 * {@code network.push-interval-seconds} 限频（默认 10 秒），并且**两次发送之间如果摘要
 * 一字未变就跳过**——空闲服务器的 TPS/MSPT/玩家数长时间不变，跳过能省掉绝大部分流量。</p>
 *
 * <p>注意"一字未变就跳过"有一个正确的例外：{@code collectedAt} 每次都变，所以不能用
 * 整条 map 比较。这里比较的是**语义字段**（tps* / mspt / players / alive）。</p>
 */
public final class SummaryPublisher {

    private final PerfScope plugin;
    private final Gson gson = new Gson();

    /** 上一次成功发出去的语义摘要，用于"没变化就不发"。 */
    private String lastSentSemantic;
    private long lastSentAtMs;

    /**
     * 发送结果。给调用方（采集器）用于统计与日志，**不给使用者看**——使用者只关心
     * "代理那边有没有数"，那由代理侧回答。
     */
    public enum Result {
        /** 发出去了。 */
        SENT,
        /** 摘要有变化但限频还没到，这次跳过。 */
        THROTTLED,
        /** 摘要与上次完全相同，跳过。 */
        UNCHANGED,
        /** 没有玩家在线，通道不可用。这是 plugin messaging 的固有限制。 */
        NO_PLAYER,
        /** 功能没开。 */
        DISABLED,
        /** 发送过程中抛异常（已被捕获，不影响采集）。 */
        FAILED
    }

    /**
     * 连续失败多少个采集周期后，认为采集器已死。
     *
     * <p>取 3：与后端告警的默认防抖（{@code alerts.debounce-cycles}）同口径——单次失败
     * 可能只是偶发抖动，连续 3 次才算"坏了"。跨服面板不需要比本地告警更敏感，
     * 但也不能像 {@code DEAD_AFTER_CONSECUTIVE_FAILURES}（10）那样迟钝：默认 1 秒周期下
     * 那是 10 秒，而代理侧的 STALE 阈值只有 30 秒，两者挤在一起就无法区分
     * "采集中断"与"只是没变化"。</p>
     */
    private static final int DEAD_AFTER_FAILURES = 3;

    /** server-id 未配置时的告警间隔（一个采集周期刷一条太吵）。 */
    private static final long ID_WARN_INTERVAL_MS = 300_000L;
    private long lastIdWarnAtMs;

    public SummaryPublisher(PerfScope plugin) {
        this.plugin = plugin;
    }

    /**
     * 在插件启用时登记出站通道。
     *
     * <p>不登记的话 {@code sendPluginMessage} 会抛 {@code IllegalArgumentException}
     * （"Not registered"），而且是在采集线程里抛——会被外层 catch 吞掉，表现为
     * "功能开了但代理永远收不到"，是最难查的一类。所以登记失败必须大声。</p>
     */
    public void register() {
        try {
            plugin.getServer().getMessenger()
                .registerOutgoingPluginChannel(plugin, NetworkSummary.CHANNEL);
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[PerfScope] 登记跨服通道失败，network 功能不可用："
                + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public void unregister() {
        try {
            plugin.getServer().getMessenger()
                .unregisterOutgoingPluginChannel(plugin, NetworkSummary.CHANNEL);
        } catch (Exception | LinkageError ignored) {
            // 关服/重载期间通道可能已经没了。这里无事可做，也不该刷屏。
        }
    }

    /**
     * 尝试把当前快照发出去。
     *
     * <p>调用点：采集周期末尾（{@code MetricsCollector} 的定时任务里），**必须在主线程**——
     * plugin messaging 要求如此。采集本身已经在全局区域定时器里跑，所以这里不再自己调度。</p>
     *
     * @param snap 当前快照；{@code null} 或尚未采集成功时直接跳过
     * @param nowMs 当前时间
     */
    public Result publish(MetricsSnapshot snap, long nowMs) {
        if (snap == null) return Result.NO_PLAYER;

        ConfigManager.ConfigData cfg = plugin.getConfigManager() == null
            ? null : plugin.getConfigManager().get();
        if (cfg == null || !cfg.network_enabled) return Result.DISABLED;

        // 采集器还活着吗？
        //
        // ⚠️ 这里**不能**用 `!collector.isStale()`：本方法由采集周期调用，而成功周期
        // 的第一件事就是刷新 lastCollectAtMs，所以 isStale() 在那一刻恒为 false，
        // 这个字段会恒为 true —— COLLECTOR_DEAD 变成永远走不到的死代码。
        //
        // 用连续失败次数才是对的：它回答的正是"采集现在是不是坏了"。
        // 失败周期也会调用本方法（见 MetricsCollector#onCycleFailure），
        // 所以采集真的坏掉时，代理能在 3 个周期内看到 alive=false，
        // 而不是傻等 30 秒后把它判成"数据过期（可能空服）"。
        MetricsCollector collector = plugin.getMetricsCollector();
        boolean alive = collector == null || collector.consecutiveFailures() < DEAD_AFTER_FAILURES;
        long collectedAt = collector == null ? 0L : collector.lastCollectAtMs();

        NetworkSummary.Report report = buildReport(snap, cfg, collectedAt, nowMs, alive);

        // ---- 语义比较：collectedAt 每次都变，所以只比"有意义的字段" ----
        String semantic = report.tps1m() + "|" + report.tps5m() + "|" + report.tps15m()
            + "|" + report.msptAvg() + "|" + report.playerCount() + "|" + report.collectorAlive();

        long intervalMs = Math.max(1, cfg.network_pushIntervalSeconds) * 1000L;

        // 保底重发：即使摘要一字未变，也要按间隔重发一次。
        //
        // 没有这一步会出一个很隐蔽的错：接收方靠包里 collectedAt 算"数据年龄"，
        // 而"没变化就不发"意味着 collectedAt 一起冻住 → 30 秒后一个**完全健康、
        // 一直在采集**的后端被判成"数据过期（可能空服）"。
        // 空闲服最容易触发：TPS 恒 20.00、玩家数不变、MSPT 模块关掉时语义串永远不变。
        //
        // 所以 UNCHANGED 只在**距上次发送还没到一个间隔**时才成立；超过一个间隔，
        // 无论有没有变化都必须重发，让 collectedAt 前进。判据本体在 shouldSend（可单测）。
        if (!shouldSend(semantic, lastSentSemantic, nowMs, lastSentAtMs, intervalMs)) {
            return Result.UNCHANGED;
        }

        Player carrier = pickCarrier();
        if (carrier == null) return Result.NO_PLAYER;

        try {
            byte[] payload = gson.toJson(report.toMap()).getBytes(StandardCharsets.UTF_8);
            carrier.sendPluginMessage(plugin, NetworkSummary.CHANNEL, payload);
            lastSentSemantic = semantic;
            lastSentAtMs = nowMs;
            return Result.SENT;
        } catch (Exception | LinkageError e) {
            // 跨服链路坏掉绝不能影响本地采集：采集是这个插件的主功能，网络只是附加视图。
            plugin.getLogger().warning("[PerfScope] 跨服摘要发送失败："
                + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Result.FAILED;
        }
    }

    /**
     * 该不该在这一次发送。
     *
     * <p><b>抽成静态纯函数是为了可测</b>：这类"什么时候发"的判据一旦写错，症状是
     * 远端显示"数据过期"而本机一切正常——最难查的一类。它不依赖任何 Bukkit 类型，
     * 所以能在单测 JVM 里逐条验证。</p>
     *
     * <p>规则只有一条但很关键：<b>超过一个上报间隔就必须发，哪怕摘要一字未变</b>。
     * 接收方靠包里的 {@code collectedAt} 算数据年龄；"没变化就不发"会让 collectedAt
     * 一起冻住，于是完全健康的后端在 30 秒后被判成"数据过期"。</p>
     *
     * @param semantic        本次摘要的语义串
     * @param lastSemantic    上次发送的语义串（从未发过传 {@code null}）
     * @param nowMs           现在
     * @param lastSentAtMs    上次发送时刻（从未发过传 0）
     * @param intervalMs      上报间隔
     */
    static boolean shouldSend(String semantic, String lastSemantic,
                              long nowMs, long lastSentAtMs, long intervalMs) {
        // 从没发过：立刻发。
        if (lastSemantic == null) {
            return true;
        }
        // 摘要变了：只要过了间隔就发（间隔内等下一轮，避免抖刷）。
        boolean changed = !semantic.equals(lastSemantic);
        boolean intervalPassed = nowMs - lastSentAtMs >= Math.max(1L, intervalMs);
        if (!changed && !intervalPassed) {
            return false;
        }
        // 变了但还没到间隔 → 不发（限频）；没变但到了间隔 → 发（保底心跳）。
        return changed ? intervalPassed : true;
    }

    /**
     * 组装摘要。
     *
     * <p>每个可空字段都要先问"这个模块可不可信"，不可信就**不填**（保持 {@code null}），
     * 而不是填 0。理由见 {@link NetworkSummary} 的哨兵约定。</p>
     */
    private NetworkSummary.Report buildReport(MetricsSnapshot snap, ConfigManager.ConfigData cfg,
                                              long collectedAt, long nowMs, boolean alive) {
        String serverId = resolveServerId(cfg);
        NetworkSummary.Report.Builder b =
            NetworkSummary.Report.builder(serverId, collectedAt == 0L ? nowMs : collectedAt, nowMs)
                .collectorAlive(alive);

        // TPS：模块被关掉 / Folia 上取不到时不填。
        if (fieldsAvailable(MetricsSnapshot.MODULE_TPS, snap.unavailable.contains(
                MetricsSnapshot.MODULE_TPS), snap.tps1m, -1)) {
            b.tps(snap.tps1m, snap.tps5m, snap.tps15m);
        }

        // MSPT：三个闸门都要过。
        //
        // 第三个别漏：TPS 反推那条路径在"满 20 TPS"时会置 msptAvg = -1，
        // 但**不**把 MODULE_MSPT 加进 unavailable，且 msptSource 是 TPS_FALLBACK(2)
        // 而不是 UNKNOWN(-1) —— 只看前两个闸门会让 -1 被当成真实读数发出去，
        // 代理上渲染成绿色的 "-1.0 毫秒"（"tick 耗时负一毫秒"）。
        boolean msptUnavailable = snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)
            || snap.msptSource == MetricsSnapshot.MSPT_SOURCE_UNKNOWN;
        if (fieldsAvailable(MetricsSnapshot.MODULE_MSPT, msptUnavailable, snap.msptAvg, 0)) {
            b.msptAvg(snap.msptAvg);
        }

        // 玩家数：计数型，没采到用 -1 而不是 0（0 人空服是合法的）。
        if (!snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            b.playerCount(snap.playerCount);
        }

        return b.build();
    }

    /**
     * 一个数值字段该不该发。
     *
     * <p>两个条件都要满足：模块可用，且值本身是有效的（大于 {@code minExclusive}）。
     * 第二个条件是防哨兵的：{@code msptAvg} 在不可用时是 -1，{@code tps1m} 不可用时是 -1。
     * 只查 unavailable 列表是不够的——有的路径置了哨兵值却没把模块加进 unavailable。</p>
     */
    static boolean fieldsAvailable(String module, boolean moduleUnavailable,
                                   double value, double minExclusive) {
        if (moduleUnavailable) {
            return false;
        }
        return value > minExclusive;
    }

    /**
     * 跨服身份。优先 {@code network.server-id}，其次 {@code prometheus.server-id}，
     * 都为空时**不猜**——返回端口派生的值并告警。
     *
     * <p><b>为什么不再回退到"首个世界名"</b>：绝大多数服的首个世界都叫 {@code world}，
     * 于是 5 个后端全部派生成同一个 id，代理侧 {@code latest.put} 后到覆盖先到，
     * 面板上只剩 1 行、全网平均值与在线合计全按 1 台算。这个错误不报错、不告警，
     * 只是数字全错——是最坏的一类。</p>
     *
     * <p><b>为什么也不用 serverTag（MOTD）</b>：多服常是同一套模板，或会被随手改。</p>
     *
     * <p>端口在同机多实例时是唯一的，跨机时通常也不同（25565/25566/...），
     * 比 {@code world} 可靠得多；而"同机同端口"根本不存在。所以回退到端口，
     * 并明确告诉服主应该显式配置。</p>
     */
    private String resolveServerId(ConfigManager.ConfigData cfg) {
        if (cfg.network_serverId != null && !cfg.network_serverId.isBlank()) {
            return cfg.network_serverId.trim();
        }
        if (cfg.prometheus_serverId != null && !cfg.prometheus_serverId.isBlank()) {
            return cfg.prometheus_serverId.trim();
        }
        String derived;
        try {
            derived = "port-" + plugin.getServer().getPort();
        } catch (Exception | LinkageError ignored) {
            derived = "perfscope";
        }
        // 限频告警：不能每个采集周期刷一条。
        long now = System.currentTimeMillis();
        if (now - lastIdWarnAtMs > ID_WARN_INTERVAL_MS) {
            lastIdWarnAtMs = now;
            plugin.getLogger().warning("[PerfScope] network.server-id 未配置，已自动派生为 \""
                + derived + "\"。多后端环境下每台必须显式配一个不同的值，"
                + "否则代理侧会把它们的数据混成一条。");
        }
        return derived;
    }

    /**
     * 挑一个玩家当载体。任意一个都行——消息内容与载体无关。
     *
     * <p>用 {@code getOnlinePlayers()} 的第一个而不是 {@code getOnlinePlayers().iterator().next()}，
     * 是因为返回值集合在个别实现上可能为空但不 null。</p>
     */
    private Player pickCarrier() {
        try {
            var players = plugin.getServer().getOnlinePlayers();
            if (players == null || players.isEmpty()) return null;
            Player p = players.iterator().next();
            return p == null ? null : p;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** 重载配置时重置限频状态，让新配置立刻生效。 */
    public void resetState() {
        lastSentSemantic = null;
        lastSentAtMs = 0L;
    }

    /** 已登记通道但尚未发送时也必须能被查询到状态（给 /perfscope status 用）。 */
    public boolean channelRegistered() {
        try {
            return plugin.getServer().getMessenger()
                .isOutgoingChannelRegistered(plugin, NetworkSummary.CHANNEL);
        } catch (Exception | LinkageError e) {
            return false;
        }
    }
}
