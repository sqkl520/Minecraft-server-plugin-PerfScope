/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package cn.perfscope.export;

import cn.perfscope.metrics.MetricsSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus 文本格式（0.0.4）渲染器：<b>纯函数、无状态、无 Bukkit 依赖</b>。
 *
 * <p><b>为什么自己写而不引 {@code io.prometheus:simpleclient}</b>：本场景只需要"把一张
 * {@link MetricsSnapshot} 序列化成文本"，用不到注册表、Collector 生命周期、exemplars、
 * HTTPServer exporter。而引 1–2 MB 依赖还要 relocate——本仓库已经有过一次 shade 事故
 * （v1.0.0 的 Adventure relocate），不值得为一个渲染器再赌一次。</p>
 *
 * <p><b>线程</b>：可在任意线程调用（HTTP 工作线程）。传入的 {@code snap} 必须已经过
 * {@code MetricsCollector.freezeCollections()} 固化——否则遍历其集合可能与采集线程竞争。</p>
 *
 * <p><b>本类刻意不处理的三件事，以及为什么</b>：</p>
 * <ul>
 *   <li><b>"未采集"哨兵 {@code -1} 不输出该行</b>。输出 {@code -1} 或 {@code -0.01} 会让
 *       Grafana 画出一条穿底线的假曲线；缺行才是"这一维度此刻没有数据"的正确表示，
 *       Grafana 会显示 gap。这是本项目"宁可说不知道，不说错数"原则在指标层的延伸。</li>
 *   <li><b>不给时间戳</b>。文本格式支持可选的 int64 毫秒时间戳，但自己填会和抓取时刻打架，
 *       导致乱序样本被丢弃。让 Prometheus 用抓取时刻。</li>
 *   <li><b>不输出 {@code instance} / {@code job} 标签</b>。Prometheus 抓取时会自动附加同名
 *       target 标签，本插件再输出会被改名成 {@code exported_instance}，Grafana 里出现两个
 *       instance 互相打架。要业务身份就用 {@code server_id}。</li>
 * </ul>
 *
 * @see <a href="https://prometheus.io/docs/instrumenting/exposition_formats/">Exposition formats</a>
 */
public final class MetricsTextRenderer {

    /** Prometheus 文本格式 0.0.4 要求的 Content-Type。 */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    /**
     * {@code perfscope_tick_duration_seconds} 的 {@code source} 标签取值：真实测量。
     *
     * <p>取值是 ASCII 字面量而不是本地化文案——标签值是给查询用的，会进 Grafana 变量与
     * 告警表达式，翻译它等于每次切语言都打断所有面板（与 {@code alert.state} 的字段名同口径）。</p>
     */
    public static final String SOURCE_MEASURED = "measured";

    /**
     * {@code source} 标签取值：由 {@code 1000/TPS} 反推的<b>估算</b>。
     *
     * <p>这个值出现时，{@code tick_duration_seconds} 度量的是"含服务器 sleep 的墙钟 tick 间隔"，
     * 不是 tick 的计算耗时。量级能差 5~10 倍。见 {@link #tickDuration} 的说明。</p>
     */
    public static final String SOURCE_TPS_FALLBACK = "tps_fallback";

    /** 固定输出顺序，便于人工 diff 与 harness 断言。 */
    private static final String[] MODULES = {
        MetricsSnapshot.MODULE_TPS,
        MetricsSnapshot.MODULE_MSPT,
        MetricsSnapshot.MODULE_PLAYERS,
        MetricsSnapshot.MODULE_ENTITIES,
        MetricsSnapshot.MODULE_CHUNKS,
        MetricsSnapshot.MODULE_MEMORY,
        MetricsSnapshot.MODULE_CPU,
        MetricsSnapshot.MODULE_GC,
        MetricsSnapshot.MODULE_TILE_ENTITIES,
        MetricsSnapshot.MODULE_SERVER,
        MetricsSnapshot.MODULE_HOTSPOTS
    };

    private MetricsTextRenderer() {
    }

    /**
     * 渲染期上下文——渲染器不碰 Bukkit，这些值由调用方在自己的线程上取好再传进来。
     *
     * @param serverId    跨服聚合用的稳定身份（{@code prometheus.server-id}，空则用派生值）
     * @param version     插件版本
     * @param serverImpl  服务端实现名（Paper / Purpur / Folia …）
     * @param mcVersion   Minecraft 版本
     * @param javaVersion Java 运行时版本
     * @param tpsScope    TPS 的作用域；Folia 上为 {@code global_region}，见下方说明
     * @param nowMs       当前时刻（epoch ms），用于算 snapshot_age
     * @param lastCollectAtMs 最近一次成功采集的完成时刻（0 = 启动后尚未成功过）
     * @param stale       采集是否已过期
     * @param consecutiveFailures 连续采集失败次数
     * @param activeAlerts 当前活跃告警数
     * @param suspendedAlerts 处于 unknown（模块长期不可用）状态的告警数
     */
    public record Context(
        String serverId,
        String version,
        String serverImpl,
        String mcVersion,
        String javaVersion,
        String tpsScope,
        long nowMs,
        long lastCollectAtMs,
        boolean stale,
        int consecutiveFailures,
        int activeAlerts,
        int suspendedAlerts,
        Map<String, Long> alertEvents
    ) {
        public Context {
            serverId = serverId == null ? "" : serverId;
            version = version == null ? "" : version;
            serverImpl = serverImpl == null ? "" : serverImpl;
            mcVersion = mcVersion == null ? "" : mcVersion;
            javaVersion = javaVersion == null ? "" : javaVersion;
            tpsScope = tpsScope == null ? "" : tpsScope;
            alertEvents = alertEvents == null ? Map.of() : Map.copyOf(alertEvents);
        }
    }

    /**
     * 渲染完整指标文本。
     *
     * <p><b>{@code snap == null} 时仍然输出</b>：{@code build_info}、{@code snapshot_age_seconds}、
     * {@code collector_degraded} 三条不依赖快照的指标。服务器卡死或采集任务死亡时，
     * 这三条正是唯一能说明"不是服务器没了、是指标冻住了"的线索。</p>
     *
     * @param snap 最新快照（可为 null）
     * @param ctx  渲染上下文（不可为 null）
     * @return 完整文本，每行以 {@code \n} 结尾（规范要求末行也必须换行）
     */
    public static String render(MetricsSnapshot snap, Context ctx) {
        // 初始容量 8192 曾被当成"够用"，但它小于真实输出：实测（本机，2 世界 6175 字符、
        // 8 世界 7255、12 世界 7985、16 世界 8725、24 世界 10205、32 世界 11685）——
        // 也就是说 16 个世界起就要扩容一次。扩容是"复制整个字符数组"，而这段文本每分钟
        // 由 Prometheus 抓取一次、每次都是完整重建，属于每抓取一次的固定开销。
        //
        // 12288 的来源：32 个世界的实测输出是 11685，留了约 5% 余量。再往上报到几十个世界
        // 时仍会扩容，但那时文本本身已经超过 10KB，一次复制相对于拼装成本可以忽略；
        // 反过来把初值设成 65536 会让常规场景（12 世界、7985 字符）白占 128KB 堆。
        StringBuilder sb = new StringBuilder(12288);

        buildInfo(sb, ctx);
        health(sb, ctx, snap);
        // v1.4：告警段放在 snap == null 判断**之前**。
        // 它只读 ctx 与 snap.alertLevels，不依赖任何采集值——而这个门控原本会让
        // "采集器自己挂了"这个最该看告警的时刻，告警指标一起消失。
        alerts(sb, ctx, snap);
        if (snap == null) {
            return sb.toString();
        }

        tps(sb, snap);
        tickDuration(sb, snap);
        players(sb, snap);
        entities(sb, snap);
        chunks(sb, snap);
        tileEntities(sb, snap);
        memory(sb, snap);
        cpu(sb, snap);
        gc(sb, snap);
        serverInfo(sb, snap);
        modules(sb, snap);

        return sb.toString();
    }

    /**
     * 该模块当前是否可采信（不在 {@code unavailable} 里）。
     *
     * <p><b>为什么每个指标组都必须先问这一句</b>：{@code MetricsSnapshot} 只给 MSPT/CPU
     * 设了 {@code -1} 哨兵默认值，TPS / 玩家 / 实体 / 区块 / 方块实体 / 堆 / GC / uptime
     * 全是 <b>0</b>。而 0 在 Prometheus 里是一条<b>正常样本</b>，不是"缺数据"。</p>
     *
     * <p>于是模块被禁用或采集失败时，旧实现会发出 {@code perfscope_tps 0}——在 Grafana 上
     * 读作"服务器彻底冻结"，而真实原因是服主自己关了 {@code tps.enabled}。任何
     * {@code perfscope_tps < 15} 的告警规则会在这种服上永久 firing。</p>
     *
     * <p>README 逐字承诺过「未采集的指标整行不输出……缺行让 Grafana 显示 gap，比画一条
     * 贴底的假线诚实」。那个承诺此前只对 CPU/MSPT 成立。</p>
     */
    private static boolean available(MetricsSnapshot snap, String module) {
        return !snap.unavailable.contains(module);
    }


    // ==================== 各指标组 ====================

    private static void buildInfo(StringBuilder sb, Context ctx) {
        help(sb, "perfscope_build_info", "Always 1. Carries build and identity metadata as labels.", "gauge");
        sb.append("perfscope_build_info{")
            .append("version=\"").append(escape(ctx.version())).append("\",")
            .append("server_impl=\"").append(escape(ctx.serverImpl())).append("\",")
            .append("mc_version=\"").append(escape(ctx.mcVersion())).append("\",")
            .append("java_version=\"").append(escape(ctx.javaVersion())).append("\",")
            .append("server_id=\"").append(escape(ctx.serverId())).append("\",")
            // Folia 上 TPS 只是 global region 的局部值，不是全服。把作用域做成标签而不是
            // 藏进文档：让 Grafana 上的人一眼看出这条 TPS 的可信范围，符合本项目"不假精确"原则。
            .append("tps_scope=\"").append(escape(ctx.tpsScope())).append("\"")
            .append("} 1\n");
    }

    /**
     * 健康信号——这一组是整个端点的核心价值。
     *
     * <p>只导 TPS/MSPT/内存的话，本插件相对 UnifiedMetrics 没有任何增量（它有现成的
     * Prometheus + Grafana 大盘）。真正的护城河是"采集器自己是否可信"这层元信息：
     * 一个报着漂亮 TPS 但采集早已停止的看板，比没有看板更危险。</p>
     */
    private static void health(StringBuilder sb, Context ctx, MetricsSnapshot snap) {
        help(sb, "perfscope_snapshot_timestamp_seconds",
            "When the latest metrics snapshot was produced (epoch seconds).", "gauge");
        if (snap != null && snap.timestamp > 0) {
            sample(sb, "perfscope_snapshot_timestamp_seconds", null, num(snap.timestamp / 1000.0));
        }

        // snapshot_age 是"指标冻住了多久"。即使没有快照也要输出——采集从未成功过时
        // 这个值等于进程运行时长，正好说明"从来没采到过"。
        help(sb, "perfscope_snapshot_age_seconds",
            "Age of the latest snapshot. Rising means collection stopped while the server may still be alive.",
            "gauge");
        if (ctx.lastCollectAtMs() > 0) {
            double ageSec = Math.max(0L, ctx.nowMs() - ctx.lastCollectAtMs()) / 1000.0;
            sample(sb, "perfscope_snapshot_age_seconds", null, num(ageSec));
        }

        help(sb, "perfscope_collector_degraded",
            "1 when collection is stale (older than the staleness threshold), else 0.", "gauge");
        sample(sb, "perfscope_collector_degraded", null, ctx.stale() ? "1" : "0");

        help(sb, "perfscope_collector_consecutive_failures",
            "Consecutive failed collection cycles.", "gauge");
        sample(sb, "perfscope_collector_consecutive_failures", null, Integer.toString(ctx.consecutiveFailures()));
    }

    private static void tps(StringBuilder sb, MetricsSnapshot snap) {
        // TPS 字段没有 -1 哨兵（采集层直接写 Bukkit.getTPS()，值域 0..20），
        // 所以下面这条 `all < 0` 的判断永远是 false——不能靠它判"未采集"。
        // 必须问 unavailable，否则 tps.enabled=false 时发出 perfscope_tps 0，
        // 在 Grafana 上读作"服务器彻底冻结"。
        if (!available(snap, MetricsSnapshot.MODULE_TPS)) {
            return;
        }
        help(sb, "perfscope_tps", "Server ticks per second, by averaging window.", "gauge");
        emitIfPresent(sb, "perfscope_tps", "period", "1m", snap.tps1m);
        emitIfPresent(sb, "perfscope_tps", "period", "5m", snap.tps5m);
        emitIfPresent(sb, "perfscope_tps", "period", "15m", snap.tps15m);
    }

    /**
     * tick 时长（MSPT）：{@code perfscope_tick_duration_seconds{stat,source}}。
     *
     * <h2>P0-U：估算值打 {@code source="tps_fallback"} 标签（而不是改现有标签名）</h2>
     *
     * <p>{@code msptSource == MSPT_SOURCE_TPS_FALLBACK} 时 {@code avg} 是 {@code 1000/TPS}——
     * 含服务器 sleep 的<b>墙钟间隔</b>，不是 tick 计算耗时。量级能差 5~10 倍（真实 3-8ms，
     * 反推 50ms）。把它当 {@code tick_duration_seconds} 发出去，Grafana 上显示的是
     * "服务器每 tick 耗时 50ms"，而真实原因是"这台服少 tick 了一点点"。</p>
     *
     * <p><b>为什么加新标签而不是改标签名/整行不输出</b>：</p>
     * <ul>
     *   <li>改标签名或改名指标（如 {@code perfscope_tick_duration_estimated_seconds}）会直接
     *       打断所有既有面板与告警规则——那些查询写的就是 {@code perfscope_tick_duration_seconds}；
     *       标签<b>加</b>一维则不影响任何以子集匹配的选择器（{@code {stat="avg"}} 仍然匹配），
     *       这是 Prometheus 的语义，不是我们的约定；</li>
     *   <li>整行不输出更糟：缺行与"采集挂了"不可区分，而 {@code absent()} 类规则会因此
     *       永久 firing；而估算值本身在"到底是不是卡了"这个问题上仍有信息量，只是不能当真。</li>
     * </ul>
     *
     * <p><b>为什么所有样本都带这个标签（而不是只给估算行加）</b>：同一个指标名下出现
     * 两种标签维度，会让 {@code sum by(...)} / {@code label_replace} 这类聚合的行为依赖
     * "有没有那条边界序列"，排查时最容易出意外。统一带上之后，标签集合恒定，
     * 值只有 {@code measured} / {@code tps_fallback} 两种。</p>
     *
     * <p>HELP 里也写了这件事——Grafana 面板的图例说明直接引用 HELP，
     * 只靠标签而文档不说，等于把发现成本转嫁给下一个看板的人。</p>
     */
    private static void tickDuration(StringBuilder sb, MetricsSnapshot snap) {
        // 指标名刻意不叫 mspt_seconds（自相矛盾），也不叫 perfscope_mspt（单位不明）。
        // 规范写法是 <name>_seconds，Grafana 面板里乘 1000 显示。
        //
        // P1-S3 之后 MODULE_MSPT 会因"采集失败"进 unavailable（一级 n==0 / 二级返回 0）。
        // 那种情况下字段已被置 -1，下面的 `> 0` 守卫本可挡住；但这里仍显式问一次
        // available()：与 tps/players/… 各组的写法保持一致，且挡住"值恰好是旧快照残留"
        // 这类将来可能出现的组合——模块不可信时不发数，是多处判定共用的唯一口径。
        if (!available(snap, MetricsSnapshot.MODULE_MSPT)) {
            return;
        }
        // P2-S2：判据从 `< 0` 收紧到 `<= 0`。MSPT 的 0 不可信（每 tick 至少花掉可测量的时间），
        // 而旧写法会把 0 当成一条正常样本发出去——Grafana 上读作"tick 零耗时"。
        if (!(snap.msptAvg > 0) && !(snap.msptMin > 0) && !(snap.msptMax > 0)) {
            return;
        }
        help(sb, "perfscope_tick_duration_seconds",
            "Per-tick duration in seconds (MSPT / 1000). source=measured is a real measurement; "
                + "source=tps_fallback is an ESTIMATE derived from 1000/TPS (wall-clock tick interval, "
                + "which includes the server's sleep, so it is NOT the tick's compute time) "
                + "and is only emitted when no real MSPT API is available.",
            "gauge");
        String source = snap.msptSource == MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK
            ? SOURCE_TPS_FALLBACK : SOURCE_MEASURED;
        emitPositiveWithSource(sb, "perfscope_tick_duration_seconds", "stat", "avg", source,
            snap.msptAvg / 1000.0, snap.msptAvg);
        emitPositiveWithSource(sb, "perfscope_tick_duration_seconds", "stat", "min", source,
            snap.msptMin / 1000.0, snap.msptMin);
        emitPositiveWithSource(sb, "perfscope_tick_duration_seconds", "stat", "max", source,
            snap.msptMax / 1000.0, snap.msptMax);
    }

    private static void players(StringBuilder sb, MetricsSnapshot snap) {
        if (!available(snap, MetricsSnapshot.MODULE_PLAYERS)) {
            return;
        }
        help(sb, "perfscope_players_online", "Players currently online.", "gauge");
        sample(sb, "perfscope_players_online", null, Integer.toString(snap.playerCount));

        if (snap.maxPlayers > 0) {
            help(sb, "perfscope_players_max", "Configured maximum player slots.", "gauge");
            sample(sb, "perfscope_players_max", null, Integer.toString(snap.maxPlayers));

            // 比例统一用 0–1 的 _ratio，不用 0–100 的 percent——这是 Prometheus 惯例。
            help(sb, "perfscope_players_usage_ratio", "Online players divided by max players (0-1).", "gauge");
            sample(sb, "perfscope_players_usage_ratio", null,
                num((double) snap.playerCount / snap.maxPlayers));
        }
    }

    private static void entities(StringBuilder sb, MetricsSnapshot snap) {
        if (!available(snap, MetricsSnapshot.MODULE_ENTITIES)) {
            return;
        }
        help(sb, "perfscope_entities", "Loaded entities in total and per world.", "gauge");
        sample(sb, "perfscope_entities", null, Integer.toString(snap.totalEntities));
        emitPerWorld(sb, "perfscope_entities", snap.entitiesByWorld);
    }

    private static void chunks(StringBuilder sb, MetricsSnapshot snap) {
        if (!available(snap, MetricsSnapshot.MODULE_CHUNKS)) {
            return;
        }
        help(sb, "perfscope_chunks_loaded", "Loaded chunks in total and per world.", "gauge");
        sample(sb, "perfscope_chunks_loaded", null, Integer.toString(snap.totalLoadedChunks));
        emitPerWorld(sb, "perfscope_chunks_loaded", snap.chunksByWorld);

        help(sb, "perfscope_chunks_force_loaded", "Force-loaded chunks in total and per world.", "gauge");
        sample(sb, "perfscope_chunks_force_loaded", null, Integer.toString(snap.totalForceLoadedChunks));
        emitPerWorld(sb, "perfscope_chunks_force_loaded", snap.forceLoadedChunksByWorld);
    }

    private static void tileEntities(StringBuilder sb, MetricsSnapshot snap) {
        if (!available(snap, MetricsSnapshot.MODULE_TILE_ENTITIES)) {
            return;
        }
        help(sb, "perfscope_tile_entities", "Loaded tile entities in total and per world.", "gauge");
        sample(sb, "perfscope_tile_entities", null, Integer.toString(snap.tileEntityCount));
        emitPerWorld(sb, "perfscope_tile_entities", snap.tileEntitiesByWorld);
    }

    private static void memory(StringBuilder sb, MetricsSnapshot snap) {
        if (!available(snap, MetricsSnapshot.MODULE_MEMORY)) {
            return;
        }
        // 字节就是字节。用 MB 会让「_bytes」这个名字撒谎，也会让 Grafana 的单位换算错位。
        help(sb, "perfscope_heap_used_bytes", "JVM heap currently in use.", "gauge");
        sample(sb, "perfscope_heap_used_bytes", null, Long.toString(snap.heapUsed));

        help(sb, "perfscope_heap_committed_bytes", "JVM heap committed to the OS.", "gauge");
        sample(sb, "perfscope_heap_committed_bytes", null, Long.toString(snap.heapTotal));

        if (snap.heapMax > 0) {
            help(sb, "perfscope_heap_max_bytes", "JVM heap maximum (-Xmx).", "gauge");
            sample(sb, "perfscope_heap_max_bytes", null, Long.toString(snap.heapMax));

            help(sb, "perfscope_heap_usage_ratio", "Heap used divided by heap max (0-1).", "gauge");
            sample(sb, "perfscope_heap_usage_ratio", null, num((double) snap.heapUsed / snap.heapMax));
        }

        if (snap.nonHeapUsed >= 0) {
            help(sb, "perfscope_nonheap_used_bytes", "JVM non-heap memory in use.", "gauge");
            sample(sb, "perfscope_nonheap_used_bytes", null, Long.toString(snap.nonHeapUsed));
        }
    }

    private static void cpu(StringBuilder sb, MetricsSnapshot snap) {
        // -1 是"未采集"哨兵（Windows 上进程 CPU 恒为 -1，见 ServerCompat.isProcessCpuReliable）。
        // 绝不输出 -0.01：那会在 Grafana 上画出一条贴着 -1% 的假线。
        if (snap.processCpuLoad >= 0) {
            help(sb, "perfscope_cpu_process_ratio", "Process CPU load (0-1). Absent when not collected on this platform.", "gauge");
            sample(sb, "perfscope_cpu_process_ratio", null, num(snap.processCpuLoad / 100.0));
        }
        if (snap.systemCpuLoad >= 0) {
            help(sb, "perfscope_cpu_system_ratio", "Whole-machine system CPU load (0-1).", "gauge");
            sample(sb, "perfscope_cpu_system_ratio", null, num(snap.systemCpuLoad / 100.0));
        }
    }

    private static void gc(StringBuilder sb, MetricsSnapshot snap) {
        if (!available(snap, MetricsSnapshot.MODULE_GC)) {
            return;
        }
        // 计数用 counter（进程内累计，重启归零——Prometheus 语义允许这种重置，
        // rate() 会自动处理）。
        help(sb, "perfscope_gc_collections_total", "Total GC collections since JVM start.", "counter");
        sample(sb, "perfscope_gc_collections_total", null, Long.toString(snap.gcCount));

        help(sb, "perfscope_gc_pause_seconds_total", "Total GC pause time since JVM start.", "counter");
        sample(sb, "perfscope_gc_pause_seconds_total", null, num(snap.gcTime / 1000.0));
    }

    private static void serverInfo(StringBuilder sb, MetricsSnapshot snap) {
        if (!available(snap, MetricsSnapshot.MODULE_SERVER)) {
            return;
        }
        if (snap.uptimeSeconds >= 0) {
            help(sb, "perfscope_uptime_seconds", "JVM uptime in seconds.", "gauge");
            sample(sb, "perfscope_uptime_seconds", null, Long.toString(snap.uptimeSeconds));
        }
        if (snap.threadCount >= 0) {
            help(sb, "perfscope_threads", "Live JVM thread count.", "gauge");
            sample(sb, "perfscope_threads", null, Integer.toString(snap.threadCount));
        }
        help(sb, "perfscope_plugins_loaded", "Number of loaded Bukkit plugins.", "gauge");
        sample(sb, "perfscope_plugins_loaded", null, Integer.toString(snap.loadedPluginCount));

        help(sb, "perfscope_worlds", "Number of loaded worlds.", "gauge");
        sample(sb, "perfscope_worlds", null, Integer.toString(snap.worldCount));
    }

    /**
     * 每个采集模块是否可用。
     *
     * <p>基数有界（固定 11 个模块），且这是排查"为什么某个数字不见了"的第一入口——
     * Grafana 上看到 chunks 曲线断开时，同一张面板里的 module_available 会直接告诉你是
     * 模块被禁用、平台跳过，还是采集抛异常。</p>
     */
    private static void modules(StringBuilder sb, MetricsSnapshot snap) {
        help(sb, "perfscope_module_available",
            "1 when the collection module produced data this cycle, else 0.", "gauge");
        for (String module : MODULES) {
            boolean available = !snap.unavailable.contains(module);
            sb.append("perfscope_module_available{module=\"").append(escape(module)).append("\"} ")
                .append(available ? "1" : "0").append('\n');
        }
    }

    private static void alerts(StringBuilder sb, Context ctx, MetricsSnapshot snap) {
        help(sb, "perfscope_alerts_active", "Alerts currently open (confirmed, not recovered).", "gauge");
        sample(sb, "perfscope_alerts_active", null, Integer.toString(ctx.activeAlerts()));

        help(sb, "perfscope_alerts_suspended",
            "Alerts suspended because their source module has been unavailable (unknown state).", "gauge");
        sample(sb, "perfscope_alerts_suspended", null, Integer.toString(ctx.suspendedAlerts()));

        // v1.4：每告警一条序列，级别走**值**而不是标签。
        //
        // 为什么级别不做标签：warning→critical 的升级若体现为标签变化，Prometheus 看到的
        // 是一条序列 stale、另一条全新开始，`ALERTS_FOR_STATE` 归零——运维写的 `for: 5m`
        // 每次级别翻转都重新计时，在有抖动治理保护的环境里依然永不触发。
        // 做成值则序列恒定不变，升级只是 1 → 2。
        //
        // 0 也要输出：健康是一种可观测事实。没有这一行，恢复时序列直接消失，
        // 既收不到 resolved 通知，也无法用 absent() 区分"没告警"与"采集挂了"。
        //
        // 键全集由采集层定义（MetricsSnapshot.alertLevels），不含的键 = 该维度本周期不适用。
        // 负值（ALERT_LEVEL_NO_VERDICT）= 该维度本周期不可判定（采集层走了哨兵路径）。
        // 两种情况的对外表现完全一致：**整行不输出**。给没测的维度发 0 等于宣称它健康；
        // 而 -1 是采集层与告警层之间的内部信号，不属于 perfscope_alert 的 0/1/2 契约。
        help(sb, "perfscope_alert",
            "Per-alert state: 0 = healthy, 1 = warning, 2 = critical. Always one series per judgeable key, "
                + "including healthy ones; a key absent here is not currently judgeable "
                + "(module disabled, unsupported platform, or no data yet).",
            "gauge");
        if (snap != null && snap.alertLevels != null) {
            for (Map.Entry<String, Integer> e : snap.alertLevels.entrySet()) {
                String key = e.getKey();
                Integer level = e.getValue();
                if (key == null || level == null || level < 0) {
                    continue;
                }
                sb.append("perfscope_alert{key=\"").append(escape(key))
                    .append("\",module=\"").append(escape(moduleOfKey(key)))
                    .append("\"} ").append(level).append('\n');
            }
        }

        // 生命周期累计计数在 v1.3.0 尚未在 AlertService 里实现（见 ROADMAP V1.4-06），
        // 因此在 map 为空时整组不输出，而不是发一堆 0 假装"从未发生过告警"。
        Map<String, Long> events = ctx.alertEvents();
        if (events.isEmpty()) {
            return;
        }
        help(sb, "perfscope_alert_events_total", "Alert lifecycle events since plugin start.", "counter");
        for (Map.Entry<String, Long> e : new LinkedHashMap<>(events).entrySet()) {
            sb.append("perfscope_alert_events_total{type=\"").append(escape(e.getKey())).append("\"} ")
                .append(e.getValue()).append('\n');
        }
    }

    /**
     * 从告警键推出模块名（{@code metric:scope} 的前缀）。
     *
     * <p>键前缀与 {@link MetricsSnapshot#MODULE_*} 的字面值相同，所以这是一个纯映射，
     * 不需要额外数据。未知前缀返回空串，绝不抛异常——渲染失败会让整次抓取 500。</p>
     */
    static String moduleOfKey(String key) {
        if (key == null) {
            return "";
        }
        int idx = key.indexOf(':');
        return idx > 0 ? key.substring(0, idx) : key;
    }

    // ==================== 工具 ====================

    /** 输出一条 {@code # HELP} 与 {@code # TYPE}（规范要求同名的 HELP/TYPE 各最多一条且在样本前）。 */
    private static void help(StringBuilder sb, String name, String help, String type) {
        sb.append("# HELP ").append(name).append(' ').append(escapeHelp(help)).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    /** 输出一条无标签样本。 */
    private static void sample(StringBuilder sb, String name, String unused, String value) {
        sb.append(name).append(' ').append(value).append('\n');
    }

    /** 按世界维度输出同一条指标的多个样本（同一指标名 + {@code world} 标签，不另造指标名）。 */
    private static void emitPerWorld(StringBuilder sb, String name, Map<String, Integer> byWorld) {
        if (byWorld == null) {
            return;
        }
        for (Map.Entry<String, Integer> e : byWorld.entrySet()) {
            sb.append(name).append("{world=\"").append(escape(e.getKey())).append("\"} ")
                .append(e.getValue()).append('\n');
        }
    }

    /**
     * 输出带单个标签的样本，值为 {@code v} 且未采集（{@code raw < 0}）时跳过。
     *
     * <p>用 {@code raw} 而不是 {@code v} 判断哨兵：调用方可能已经做过单位换算
     * （如 MSPT 的 ms→s），换算后的 -0.001 与 -1 无法区分，必须用原始值判定。</p>
     */
    private static void emitIfPresent(StringBuilder sb, String name, String label, String labelValue,
                                      double v, double raw) {
        if (raw < 0) {
            return;
        }
        sb.append(name).append('{').append(label).append("=\"").append(escape(labelValue)).append("\"} ")
            .append(num(v)).append('\n');
    }

    /** 无原始值可判的情形（如 TPS，哨兵就是 -1 本身）。 */
    private static void emitIfPresent(StringBuilder sb, String name, String label, String labelValue, double v) {
        emitIfPresent(sb, name, label, labelValue, v, v);
    }

    /**
     * 输出带<b>两个</b>标签（{@code stat} + {@code source}）的样本，原始值不可信时跳过。
     *
     * <p>P0-U 专用：{@code perfscope_tick_duration_seconds} 需要把"实测"与"TPS 反推估算"
     * 分开，而 {@code source} 是<b>新增</b>的一维，不是改名——既有查询
     * {@code perfscope_tick_duration_seconds{stat="avg"}} 仍然匹配（子集选择器语义）。</p>
     *
     * <p>P2-S2：判据是 {@code !(raw > 0)} 而不是 {@code raw < 0}。MSPT 的 {@code 0} 同样不可信
     * （每 tick 至少花掉可测量时间），而旧写法会把第三方写进快照的 0 当成真实样本发出去——
     * Grafana 上读作"这一 tick 零耗时"，恰好掩盖了"根本没测到"。
     * 用 {@code !(x > 0)} 而不是 {@code x <= 0} 是为了同时挡住 {@code NaN}。</p>
     */
    private static void emitPositiveWithSource(StringBuilder sb, String name, String label, String labelValue,
                                               String source, double v, double raw) {
        if (!(raw > 0)) {
            return;
        }
        sb.append(name).append('{')
            .append(label).append("=\"").append(escape(labelValue)).append("\",")
            .append("source=\"").append(escape(source)).append("\"} ")
            .append(num(v)).append('\n');
    }

    /**
     * 数字格式化。
     *
     * <p><b>输出普通十进制，绝不用科学计数法</b>。{@code Double.toString} 对时间戳这类值会给出
     * {@code 1.79435025823E9}、对 tick 时长给出 {@code 5.3E-4}——这既难读（服主 curl 出来看到的
     * 第一手数据就是它），也把"是否被解析器接受"变成一个需要查规范才能确认的问题。
     * 本项目能用的数据范围是有界的（时间戳 ~1e9、时长 ~1e-4），普通十进制完全够用，
     * 而它在任何解析器上都是无歧义合法的，不依赖对 exposition 规范某一版的解读。</p>
     *
     * <p>本机无法取到权威解析器（无 promtool，网络受限），因此这里刻意选择<b>两种解读下都成立</b>
     * 的写法，而不是赌 E 记法被接受。</p>
     */
    static String num(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            // Prometheus 认的是 +Inf/-Inf，不是 Java 的 Infinity。写错会让整个抓取解析失败。
            return v > 0 ? "+Inf" : "-Inf";
        }
        // 整数值不带小数点，避免 5.0 这种无意义尾数
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        // BigDecimal 由 Double.toString 构造（最短可回读表示），再转普通十进制
        String plain = java.math.BigDecimal.valueOf(v).toPlainString();
        // toPlainString 对 10.0 这类会给 "10.0"；去掉无意义的尾零，但保留至少一位小数
        if (plain.indexOf('.') >= 0) {
            int end = plain.length();
            while (end > 0 && plain.charAt(end - 1) == '0' && plain.charAt(end - 2) != '.') {
                end--;
            }
            plain = plain.substring(0, end);
        }
        return plain;
    }

    /** 标签值转义：规范只要求处理 {@code \\}、{@code \"}、{@code \n} 三个字符。 */
    static String escape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** HELP 文本转义：除了 {@code \\} 与 {@code \n}，其余原样（引号在 HELP 里无需转义）。 */
    static String escapeHelp(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\n", "\\n");
    }

    /** 供测试与调用方枚举会输出的模块（顺序稳定）。 */
    public static List<String> modules() {
        return List.of(MODULES);
    }
}
