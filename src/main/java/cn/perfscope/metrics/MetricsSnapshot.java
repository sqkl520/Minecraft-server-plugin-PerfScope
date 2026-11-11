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
package cn.perfscope.metrics;

import cn.perfscope.alert.Alert;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 性能指标快照 - 包含某一时刻的所有性能数据
 *
 * <p>契约（MC-3）：快照一旦发布（写入 history 或 jvmCache）即视为只读，
 * 任何消费方不得修改其字段/集合（命令/告警/报告层一律只读消费；需要修改时先复制）。</p>
 */
public class MetricsSnapshot {

    // === TPS ===
    public double tps1m;
    public double tps5m;
    public double tps15m;

    // === MSPT ===
    public double msptAvg;
    public double msptMin;
    public double msptMax;
    // 本周期实际走了哪条采集路径（9A-0）：展示层的"(估算)"与"未采集"必须以实际路径为准，
    // 不能再用静态能力位（PAPER_MSPT_AVAILABLE 等）推断——那只回答"这台服务端理论上支持什么"。
    public static final int MSPT_SOURCE_UNKNOWN = -1;
    public static final int MSPT_SOURCE_TICK_TIMES = 0;   // 一级：getTickTimes()，自带 min/max
    public static final int MSPT_SOURCE_AVG_TICK = 1;     // 二级：getAverageTickTime()，只有 avg
    public static final int MSPT_SOURCE_TPS_FALLBACK = 2; // 三级：TPS 反推，估算
    public int msptSource = MSPT_SOURCE_UNKNOWN;

    // === 玩家 ===
    public int playerCount;
    public int maxPlayers;

    // === 实体 ===
    public int totalEntities;
    public Map<String, Integer> entitiesByType;
    public Map<String, Integer> entitiesByWorld;

    // === 区块 ===
    public int totalLoadedChunks;
    public int totalForceLoadedChunks;
    public Map<String, Integer> chunksByWorld;
    public Map<String, Integer> forceLoadedChunksByWorld;

    // === 内存 ===
    public long heapUsed;
    public long heapMax;
    public long heapTotal;
    public long nonHeapUsed;
    public long nonHeapMax;
    public int heapUsagePercent;

    // === CPU ===
    public double processCpuLoad;
    public double systemCpuLoad;
    public int availableProcessors;

    // === GC ===
    public long gcCount;
    public long gcTime;

    // === Tile Entity ===
    public int tileEntityCount;
    public Map<String, Integer> tileEntitiesByWorld;

    // === 服务器 ===
    public long uptimeSeconds;
    public int threadCount;
    public int loadedPluginCount;
    // 9A-20 #6 / 9A-22 #9：原 pendingTasks 字段（Bukkit.getScheduler().getPendingTasks().size()）已删除——
    // 全仓零读取，而 getPendingTasks() 每次都要构造完整的 PendingTask 列表对象（默认 1 秒一周期），
    // 是纯粹的采集侧浪费。诊断如需调度器积压，应在 /perfscope status 里按需现算。
    public int worldCount;

    // === 告警信息 ===
    // Wave 7B/7C：alerts 为结构化真相源（key/level/message/display/module/timestamp）；
    // activeWarnings/activeCriticals 保留但其为"派生"——由采集层经 addAlert(snap, alert) 在构造
    // Alert 后同步 add 相同文案（命令层 /ps 活跃告警区、报告三格式、JSON alerts.* 全部零改动）。
    public List<Alert> alerts = new ArrayList<>();
    public List<String> activeWarnings = new ArrayList<>();
    public List<String> activeCriticals = new ArrayList<>();

    // === 热点区块（Wave 8C）===
    // 全服实体数 Top5 区块（含玩家；每 hotspots.sample-every-cycles 个采集周期刷新一次，非采样周期复用缓存）。
    // 空列表 = 尚未采样/模块不可用——展示层据此渲染"暂无热点数据"。
    public List<ChunkHotspot> topEntityChunks = new ArrayList<>();

    /**
     * 每个「可判定的告警键」的当前级别（v1.4，供 Prometheus 导出）。
     *
     * <p><b>{@code 0} 也要放进来</b>，这是它与 {@link #alerts} 的本质区别：{@code alerts} 只在
     * 越过阈值时才有条目，而这里要求「健康」也是一个可观测的事实。没有这一条，
     * 运维的 {@code perfscope_alert{...} == 0} 规则在恢复时只会看到序列消失，
     * 既收不到恢复通知，也无法用 {@code absent()} 区分"没告警"与"采集挂了"。</p>
     *
     * <p><b>键的"全集"由采集层定义</b>，不由告警层定义。原因：{@code AlertService.onSnapshot}
     * 在 {@code alerts.enabled=false} 时首行早退，而阈值判定在采集层照常进行——
     * 若全集由告警层提供，服主关掉告警投递会让整套 Prometheus 序列一起消失，
     * 那是纯粹的信息丢失，而不是他想要的"别再推消息给我"。</p>
     *
     * <p><b>不在全集里的键 = 该维度此刻不可判定</b>（模块被禁用 / 平台不支持 /
     * 哨兵值 -1 / 尚未预热）。刻意<b>不</b>发 0，因为给没测的维度发 0 等于宣称它健康。
     * 渲染层据此整行不输出，与 {@code MetricsTextRenderer} 的既有原则一致。</p>
     *
     * <p>取值：{@code 0} = 正常，{@code 1} = 警告，{@code 2} = 严重。</p>
     */
    public Map<String, Integer> alertLevels = new java.util.LinkedHashMap<>();

    // === 模块可用性（P0-5）：采集失败或被配置禁用的模块名集合 ===
    // 展示层约定：命中本集合的模块显示"不可用"（灰色），而非 0/空值
    // 9A-20 #3：本集合语义保持不变（disabled 也进本集合，向后兼容 40 处既有判断）；
    // 需要区分"服主自己关掉"与"采集真的坏了"时，优先查下方 disabledModules。
    public static final String MODULE_TPS = "tps";
    public static final String MODULE_MSPT = "mspt";
    public static final String MODULE_PLAYERS = "players";
    public static final String MODULE_ENTITIES = "entities";
    public static final String MODULE_CHUNKS = "chunks";
    public static final String MODULE_MEMORY = "memory";
    public static final String MODULE_CPU = "cpu";
    public static final String MODULE_GC = "gc";
    public static final String MODULE_TILE_ENTITIES = "tile_entities";
    public static final String MODULE_SERVER = "server";
    /** Wave 8C：热点区块定位模块（采集禁用/失败时进 unavailable，展示层渲染"不可用"）。 */
    public static final String MODULE_HOTSPOTS = "hotspots";

    public Set<String> unavailable = ConcurrentHashMap.newKeySet();

    // === 预热标记（W5-D #1） ===
    // unavailable 中属于"首轮采集尚未就绪"（JVM 异步缓存未产出，默认 1 秒内自愈）的子集。
    // 背景：reload/启动初期的预热与"模块真死了"此前输出同款"不可用"，事故语境下是虚假信标。
    // 展示层据此渲染"预热中"，其余 unavailable 成员仍渲染"不可用"；契约 warmingUp ⊆ unavailable，
    // 不改变 unavailable 本身语义（既有消费点全部不受影响）。
    public Set<String> warmingUp = ConcurrentHashMap.newKeySet();

    // === 配置禁用标记（9A-20 #3） ===
    // unavailable 中属于"被配置显式关闭"（config.yml 里 xxx.enabled=false）的子集。
    // 背景：此前"服主自己关掉 memory"与"memory 采集真的抛异常"在展示层是同一句"不可用"——
    // 服主关掉模块后看到"不可用"，会以为插件坏了；反过来采集坏了也可能被当成"我关过吧"。
    // 契约：disabledModules ⊆ unavailable（不改变 unavailable 语义，既有 40 处判断零改动）；
    // 展示层可优先判 disabledModules，渲染"已禁用（config xxx.enabled=false）"。
    public Set<String> disabledModules = ConcurrentHashMap.newKeySet();

    // === 平台限制标记（9B-3） ===
    // unavailable 中属于"当前服务端平台使该指标不可信/不可采"的子集。
    // 背景：Folia 是 region 化多线程，World.getEntities()/getLoadedChunks()、Chunk.getEntities()/
    // getTileEntities() 这些"全服聚合"API 的语义不保证跨 region 一致，且可能抛 IllegalStateException。
    // 采集层在 Folia 上整块跳过 entities/chunks/tile_entities/hotspots，并按规格同时写入
    // disabledModules（规格 9B-3 A 方案的字面要求）——但那样展示层会误渲染成
    // "已禁用（config xxx.enabled=false）"，与事实相反（服主明明开着）。
    // 本集合是 disabledModules 的**更精确子集**：命中本集合的模块应优先渲染
    // "平台限制（Folia 跨 region，已自动跳过）"，而不是"配置禁用"。
    // 契约：platformSkipped ⊆ disabledModules ⊆ unavailable；非 Folia 上恒为空（Paper 零行为变化）。
    public Set<String> platformSkipped = ConcurrentHashMap.newKeySet();

    // === 时间戳 ===
    public long timestamp;

    /**
     * 本周期主线程采集耗时（毫秒）；-1 表示未测量。
     *
     * <p><b>为什么要有这个字段</b>：{@link MetricsCollector#isStale()} 只看"多久没采集"，
     * 不看"每次采集花了多久"——于是采集器自己把主线程拖慢时，它永远显示一切正常。
     * 用户装个 spark 就能看到这个栈，插件不报只是让自己无法归因，损失的是可信度。</p>
     *
     * <p><b>为什么要报分布而不只是本周期值</b>：采集是分级降频的（实体/区块每 5 周期、
     * 方块实体每 7/10、热点每 30），单看某一周期的值会误判——恰好在撞车拍看到 66ms，
     * 会以为"每秒都是 66ms"。分布（p50/p95/max + 超阈值次数）才是可解释的口径。</p>
     */
    public long collectDurationMs = -1;

    /**
     * 玩家占容量百分比（9A-6：唯一算法入口）。
     *
     * <p>背景：此前命令层用 {@code playerCount*100/maxPlayers}（int 截断）、采集层用
     * {@code (int)((double)..*100)}（double 截断）、诊断规则用 {@code Math.round(...)}（四舍五入）——
     * 89.5% 时三方分别显示 89 / 89 / 90，可能直接触发 DIAG-PLAYER-CAP 而界面仍是绿色。
     * 现统一为四舍五入，三个消费方全部调用本方法。</p>
     *
     * @return 0-100 的百分比；{@code maxPlayers <= 0} 时返回 0（不做除法，避免 Infinity）
     */
    public int playerPercent() {
        if (maxPlayers <= 0) {
            return 0;
        }
        return (int) Math.round(100.0 * playerCount / maxPlayers);
    }

    /**
     * 给定玩家数与容量计算百分比（供历史样本等非快照场景复用同一算法，9A-6）。
     */
    public static int playerPercentOf(int players, int max) {
        if (max <= 0) {
            return 0;
        }
        return (int) Math.round(100.0 * players / max);
    }

    public MetricsSnapshot() {
        this.timestamp = System.currentTimeMillis();
        this.entitiesByType = new LinkedHashMap<>();
        this.entitiesByWorld = new LinkedHashMap<>();
        this.chunksByWorld = new LinkedHashMap<>();
        this.forceLoadedChunksByWorld = new LinkedHashMap<>();
        this.tileEntitiesByWorld = new LinkedHashMap<>();
        // 哨兵默认值（P0-13）：MSPT/CPU 未采集时为 -1（未知），而非误导性的 0
        this.msptAvg = -1;
        this.msptMin = -1;
        this.msptMax = -1;
        this.processCpuLoad = -1;
        this.systemCpuLoad = -1;
    }
}
