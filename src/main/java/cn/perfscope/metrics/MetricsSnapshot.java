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
    public int pendingTasks;
    public int worldCount;

    // === 告警信息 ===
    // Wave 7B/7C：alerts 为结构化真相源（key/level/message/display/module/timestamp）；
    // activeWarnings/activeCriticals 保留但其为"派生"——由采集层经 addAlert(snap, alert) 在构造
    // Alert 后同步 add 相同文案（命令层 /ps 活跃告警区、报告三格式、JSON alerts.* 全部零改动）。
    public List<Alert> alerts = new ArrayList<>();
    public List<String> activeWarnings = new ArrayList<>();
    public List<String> activeCriticals = new ArrayList<>();

    // === 模块可用性（P0-5）：采集失败或被配置禁用的模块名集合 ===
    // 展示层约定：命中本集合的模块显示"不可用"（灰色），而非 0/空值
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

    public Set<String> unavailable = ConcurrentHashMap.newKeySet();

    // === 预热标记（W5-D #1） ===
    // unavailable 中属于"首轮采集尚未就绪"（JVM 异步缓存未产出，默认 1 秒内自愈）的子集。
    // 背景：reload/启动初期的预热与"模块真死了"此前输出同款"不可用"，事故语境下是虚假信标。
    // 展示层据此渲染"预热中"，其余 unavailable 成员仍渲染"不可用"；契约 warmingUp ⊆ unavailable，
    // 不改变 unavailable 本身语义（既有消费点全部不受影响）。
    public Set<String> warmingUp = ConcurrentHashMap.newKeySet();

    // === 时间戳 ===
    public long timestamp;

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
