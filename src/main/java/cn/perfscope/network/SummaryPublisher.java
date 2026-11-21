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

        // 采集器还活着吗？死了的话数字都是陈的，接收方要能看出来。
        MetricsCollector collector = plugin.getMetricsCollector();
        boolean alive = collector != null && !collector.isStale();
        long collectedAt = collector == null ? 0L : collector.lastCollectAtMs();

        NetworkSummary.Report report = buildReport(snap, cfg, collectedAt, nowMs, alive);

        // ---- 语义比较：collectedAt 每次都变，所以只比"有意义的字段" ----
        String semantic = report.tps1m() + "|" + report.tps5m() + "|" + report.tps15m()
            + "|" + report.msptAvg() + "|" + report.playerCount() + "|" + report.collectorAlive();
        if (semantic.equals(lastSentSemantic)) return Result.UNCHANGED;

        long intervalMs = Math.max(1, cfg.network_pushIntervalSeconds) * 1000L;
        if (lastSentSemantic != null && nowMs - lastSentAtMs < intervalMs) {
            return Result.THROTTLED;
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
        if (!snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            b.tps(snap.tps1m, snap.tps5m, snap.tps15m);
        }

        // MSPT：同样先问可用性。msptSource 为 UNKNOWN 说明三级降级全失败。
        if (!snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)
            && snap.msptSource != MetricsSnapshot.MSPT_SOURCE_UNKNOWN) {
            b.msptAvg(snap.msptAvg);
        }

        // 玩家数：计数型，没采到用 -1 而不是 0（0 人空服是合法的）。
        if (!snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            b.playerCount(snap.playerCount);
        }

        return b.build();
    }

    /**
     * 跨服身份。优先用 {@code network.server-id}（每个后端必须不同），否则回退到
     * {@code prometheus.server-id}，再回退到自动派生。
     *
     * <p><b>为什么不能直接用 serverTag（MOTD）</b>：本插件告警里的 serverTag 取 MOTD 首行，
     * 而五六个后端的 MOTD 很可能相同（同一套配置模板）或频繁修改。用它做跨服标识会让代理侧
     * 把不同后端的数据混成一条。所以这里用"首个世界名 → 端口"这条更稳定的派生链。</p>
     */
    private String resolveServerId(ConfigManager.ConfigData cfg) {
        if (cfg.network_serverId != null && !cfg.network_serverId.isBlank()) {
            return cfg.network_serverId.trim();
        }
        if (cfg.prometheus_serverId != null && !cfg.prometheus_serverId.isBlank()) {
            return cfg.prometheus_serverId.trim();
        }
        try {
            var worlds = plugin.getServer().getWorlds();
            if (!worlds.isEmpty() && worlds.get(0).getName() != null
                && !worlds.get(0).getName().isBlank()) {
                return worlds.get(0).getName();
            }
        } catch (Exception | LinkageError ignored) {
            // 取不到世界名，继续回退
        }
        try {
            return "port-" + plugin.getServer().getPort();
        } catch (Exception | LinkageError ignored) {
            return "perfscope";
        }
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
