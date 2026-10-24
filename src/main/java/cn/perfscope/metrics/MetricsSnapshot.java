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

    // === 时间戳 ===
    public long timestamp;

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
