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

import cn.perfscope.PerfScope;
import cn.perfscope.alert.Alert;
import cn.perfscope.compat.SchedulerAdapter;
import cn.perfscope.compat.ServerCompat;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.history.HistoryStore;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.lang.management.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;

/**
 * 性能指标采集器 - 核心数据采集模块
 * 自动检测服务端类型，Paper 专有功能不可用时自动降级为 Bukkit API
 */
public class MetricsCollector {

    private final PerfScope plugin;
    private final ConfigManager configManager;
    // Wave 7A：历史环形缓冲（由 PerfScope 持有并注入；本类只写不读，stop/reload 不影响其内容）
    private final HistoryStore historyStore;

    // P0-1/P0-8：采集任务句柄（主线程节拍 syncHandle + 异步 JVM 节拍 jvmHandle），替代旧的 Bukkit taskId
    private SchedulerAdapter.TaskHandle syncHandle;
    private SchedulerAdapter.TaskHandle jvmHandle;

    // P0-1 分级降频：采集周期计数器（仅主线程 collectSync 访问，无需同步）
    private long tickCounter = 0;

    // P0-1 分级降频倍数：重模块每 N 个采集周期才真正遍历一次世界，其余周期复用上次缓存值，
    // 以降低主线程遍历实体/区块/方块实体的开销（常量，不进 config）
    private static final long ENTITY_SAMPLE_EVERY = 5;   // 实体：每 5 个采集周期遍历一次
    private static final long CHUNK_SAMPLE_EVERY = 5;    // 区块：每 5 个采集周期遍历一次
    private static final long TE_SAMPLE_EVERY = 10;      // 方块实体：每 10 个采集周期遍历一次

    // 历史数据
    private final Deque<MetricsSnapshot> history = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY = 300;

    // Paper MSPT 反射缓存（MC-1 修复：目标为 CraftServer.getTickTimes()，返回 long[]（nanos），
    // 由 collectMSPT_Paper 自算 avg/min/max；旧 NMS TickTimes 对象链与其 time/minTime/maxTime 字段已随修复整体删除）
    private Method getTickTimesMethod;

    // 启动时缓存的反射方法（P0-1/P0-6：热路径只做 invoke，避免每次采集重复查找）
    private Method avgTickMethod;
    private Method tileEntityCountMethod;

    // ==================== P0-1 重模块节流缓存（仅主线程 collectSync 访问，无需同步） ====================
    // 复用语义：采集周期命中采样倍数时真正遍历刷新缓存，其余周期直接复用缓存值填入快照；
    // unavailable 标记随缓存一起复用（上次失败/禁用则复用期间同样标记不可用）

    // 实体缓存
    private boolean entitiesCacheReady = false;        // 是否已有过一次真实采集（含失败/禁用）
    private boolean entitiesCacheUnavailable = false;  // 上次真实采集是否失败或被禁用
    private int cachedTotalEntities = 0;
    private Map<String, Integer> cachedEntitiesByType = new LinkedHashMap<>();
    private Map<String, Integer> cachedEntitiesByWorld = new LinkedHashMap<>();
    // Wave 7B/7C：结构化告警与派生字符串列表一并缓存/回放（addAlert 同步填充两者，避免漏配）
    private List<Alert> cachedEntityAlerts = new ArrayList<>();
    private List<String> cachedEntityWarnings = new ArrayList<>();
    private List<String> cachedEntityCriticals = new ArrayList<>();

    // 区块缓存
    private boolean chunksCacheReady = false;
    private boolean chunksCacheUnavailable = false;
    private int cachedTotalLoadedChunks = 0;
    private int cachedTotalForceLoadedChunks = 0;
    private Map<String, Integer> cachedChunksByWorld = new LinkedHashMap<>();
    private Map<String, Integer> cachedForceLoadedChunksByWorld = new LinkedHashMap<>();
    // Wave 7B/7C：结构化告警与派生字符串列表一并缓存/回放
    private List<Alert> cachedChunkAlerts = new ArrayList<>();
    private List<String> cachedChunkWarnings = new ArrayList<>();
    private List<String> cachedChunkCriticals = new ArrayList<>();

    // 方块实体缓存（collectTileEntities 无阈值告警，仅缓存计数）
    private boolean teCacheReady = false;
    private boolean teCacheUnavailable = false;
    private int cachedTileEntityCount = 0;
    private Map<String, Integer> cachedTileEntitiesByWorld = new LinkedHashMap<>();

    // ==================== P0-1 JVM 异步缓存（volatile 跨线程发布） ====================
    // collectJvmAsync（异步线程）整体构造后发布，collectSync（主线程）读取填入快照；
    // 启动初期为 null 表示异步尚未产出，相关模块按 unavailable 处理，避免 0 值误导或 NPE
    private volatile MetricsSnapshot jvmCache = null;

    // W5-D #1：预热窗口（毫秒）——reload/启动后 JVM 异步尚未产出首轮的宽限期。
    // 窗口内 memory/cpu/gc 的 unavailable 同时标 warmingUp（展示层据此渲染"预热中"）；
    // 超出窗口仍未产出说明异步调度异常（非自愈场景），回落真故障"不可用"，
    // 防"永远预热中"的虚假自愈信号；10 秒取值与用户可见文案（"超过 10 秒请查看控制台"）对齐
    private static final long WARMUP_WINDOW_MS = 10_000;
    // 预热窗口基准：start() 每次调用重置，使 /perfscope reload 后重新进入预热态（幂等重复调用同样重新计时）
    private volatile long startedAtMs = System.currentTimeMillis();

    public MetricsCollector(PerfScope plugin, HistoryStore historyStore) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.historyStore = historyStore;
        if (ServerCompat.PAPER_MSPT_AVAILABLE) {
            resolvePaperMSPT();
        }
        resolveReflections();
    }

    /**
     * 解析并缓存 CraftServer.getTickTimes()（MC-1 修复）。
     * 目标方法位于 Bukkit.getServer() 的运行时类上（Paper 系各家 CraftServer 均有，返回 long[]，元素为 nanos）；
     * 旧实现探测 NMS DedicatedServer 与 TickTimes.time()/minTime()/maxTime() 在全部受支持服务端上恒失败，已删除。
     * 解析失败置 null，collectMSPT 门控自然降级到第二级，无 NPE 路径。
     */
    private void resolvePaperMSPT() {
        try {
            getTickTimesMethod = Bukkit.getServer().getClass().getMethod("getTickTimes");
        } catch (Exception e) {
            getTickTimesMethod = null;
        }
    }

    /**
     * 启动时一次性缓存热路径反射方法，避免每次采集重复查找
     * （P0-1 规格的 resolveReflections，Wave 1 提前落地 MSPT 平均 Tick 与 TE 计数两项）
     */
    private void resolveReflections() {
        // Bukkit getAverageTickTime()（Spigot/Paper 通用 API）
        if (ServerCompat.PAPER_AVG_TICK_AVAILABLE) {
            try {
                avgTickMethod = Bukkit.getServer().getClass().getMethod("getAverageTickTime");
            } catch (Exception e) {
                plugin.getLogger().warning("[PerfScope] getAverageTickTime 反射解析失败，将使用 TPS 反推 MSPT");
            }
        }
        // Paper 世界 TileEntity 计数
        if (ServerCompat.PAPER_TILE_ENTITY_COUNT_AVAILABLE) {
            try {
                tileEntityCountMethod = World.class.getMethod("getTileEntityCount");
            } catch (Exception e) {
                plugin.getLogger().warning("[PerfScope] getTileEntityCount 反射解析失败，将使用 Bukkit 降级路径");
            }
        }
    }

    /**
     * 启动采集任务（P0-1/P0-8），拆分为两个节拍，间隔均为 general.update_interval：
     *  - syncHandle：主线程（Folia 下 GlobalRegionScheduler）节拍，采集 TPS/MSPT/玩家等轻量 Bukkit 指标，
     *    并对实体/区块/方块实体做分级降频；每周期产出完整快照入 history。
     *  - jvmHandle：异步（Folia 下 AsyncScheduler）节拍，采集纯 JVM/MXBean 指标写入 volatile 缓存。
     * Folia 下世界 API 的 region 线程归属问题由 P0-5 的 safeCollect 兜底（抛异常则标 unavailable）。
     */
    public void start() {
        stop(); // 防呆：先停旧任务，支持 reload 重复调用不叠加（P0-14 幂等）
        // 重置降频计数与缓存，使 reload 后首个周期立即重新采集，不复用旧快照数据
        tickCounter = 0;
        entitiesCacheReady = false;
        chunksCacheReady = false;
        teCacheReady = false;
        jvmCache = null;
        startedAtMs = System.currentTimeMillis(); // W5-D #1：reload 后重新进入预热窗口（与 jvmCache 置空同步）

        // L-2 修复：interval ≤0 会被 Bukkit 调度器 coerce 成单次任务（负值 period=-1 跑一轮即静默死亡）
        // 或每 tick（0 → 1），Folia 下则被 SchedulerAdapter 校正为每 tick，行为分叉，
        // 故强制下限 1（对齐 AlertService.start 已有范式），非法配置同时记 warning 便于诊断
        int configuredInterval = configManager.get().general_updateInterval;
        if (configuredInterval <= 0) {
            plugin.getLogger().warning("[PerfScope] general.update_interval=" + configuredInterval
                + " 非法（需 ≥1），已按 1 tick 处理");
        }
        long interval = Math.max(1, configuredInterval);
        syncHandle = SchedulerAdapter.runGlobalTimer(plugin, this::collectSync, 0L, interval);
        jvmHandle = SchedulerAdapter.runAsyncTimer(plugin, this::collectJvmAsync, 0L, interval);
    }

    /** 停止采集任务：取消两个任务句柄（句柄为空时无操作），并置空以便重建。 */
    public void stop() {
        if (syncHandle != null) {
            syncHandle.cancel();
            syncHandle = null;
        }
        if (jvmHandle != null) {
            jvmHandle.cancel();
            jvmHandle = null;
        }
    }

    /**
     * 主线程采集节拍（P0-1）。轻量 Bukkit 指标每周期采集；实体/区块/方块实体分级降频复用缓存；
     * JVM 指标从异步缓存读取填入。每周期产出完整 MetricsSnapshot 入 history（节奏不变，趋势/报告不受影响）。
     */
    private void collectSync() {
        tickCounter++;
        MetricsSnapshot snap = new MetricsSnapshot();
        ConfigManager.ConfigData cfg = configManager.get();

        // 轻量 Bukkit 模块：每周期采集（开销小），仍用 P0-5 的 runOrMark 包裹
        runOrMark(snap, cfg.tps_enabled, MetricsSnapshot.MODULE_TPS, () -> collectTPS(snap, cfg));
        runOrMark(snap, cfg.mspt_enabled, MetricsSnapshot.MODULE_MSPT, () -> collectMSPT(snap, cfg));
        runOrMark(snap, cfg.players_enabled, MetricsSnapshot.MODULE_PLAYERS, () -> collectPlayers(snap, cfg));

        // 重模块：分级降频，非采集周期复用缓存（见各 throttle 方法）
        collectEntitiesThrottled(snap, cfg);
        collectChunksThrottled(snap, cfg);
        collectTileEntitiesThrottled(snap, cfg);

        // 服务器信息 Bukkit 部分（世界数/插件数）：主线程采集
        safeCollect(MetricsSnapshot.MODULE_SERVER, snap, () -> collectServerInfoBukkit(snap));

        // JVM 指标：从异步缓存读取填入（内存/CPU/GC/运行时信息）
        applyJvmCache(snap);

        history.addLast(snap);
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }

        // Wave 7A：历史环形缓冲挂点（契约指定：history.addLast 之后）。内部自带
        // enabled 门控 / TPS 不可用跳过 / 采样间隔节流，此处无条件调用即可
        historyStore.record(snap);
    }

    /**
     * 异步采集节拍（P0-1）：采集纯 JVM/MXBean 指标（内存/CPU/GC/运行时信息），整体发布到 volatile 缓存
     * 供主线程 collectSync 读取填入快照。仍用 runOrMark/safeCollect 包裹，保持 P0-5 异常隔离与 unavailable 语义。
     */
    private void collectJvmAsync() {
        MetricsSnapshot jvmSnap = new MetricsSnapshot();
        ConfigManager.ConfigData cfg = configManager.get();

        runOrMark(jvmSnap, cfg.memory_enabled, MetricsSnapshot.MODULE_MEMORY, () -> collectMemory(jvmSnap, cfg));
        runOrMark(jvmSnap, cfg.cpu_enabled, MetricsSnapshot.MODULE_CPU, () -> collectCPU(jvmSnap, cfg));
        safeCollect(MetricsSnapshot.MODULE_GC, jvmSnap, () -> collectGC(jvmSnap));
        safeCollect(MetricsSnapshot.MODULE_SERVER, jvmSnap, () -> collectServerInfoJvm(jvmSnap));

        jvmCache = jvmSnap; // volatile 整体发布，主线程读到的始终是某一轮的完整数据，无半成品
    }

    // ==================== P0-1 重模块分级降频（节流缓存，仅主线程） ====================

    /** 实体采集（分级降频）：每 ENTITY_SAMPLE_EVERY 个周期遍历一次世界实体并刷新缓存，其余周期复用缓存值填入快照。 */
    private void collectEntitiesThrottled(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        if (!entitiesCacheReady || tickCounter % ENTITY_SAMPLE_EVERY == 0) {
            refreshEntitiesCache(cfg);
        }
        applyEntitiesCache(snap);
    }

    private void refreshEntitiesCache(ConfigManager.ConfigData cfg) {
        entitiesCacheReady = true;
        if (!cfg.entities_enabled) {   // 配置禁用：与 runOrMark 语义一致，标记不可用
            entitiesCacheUnavailable = true;
            return;
        }
        // 临时快照承接本次采集，隔离出本模块产生的告警，便于缓存后在复用周期一并还原
        MetricsSnapshot tmp = new MetricsSnapshot();
        safeCollect(MetricsSnapshot.MODULE_ENTITIES, tmp, () -> collectEntities(tmp, cfg));
        if (tmp.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES)) {
            entitiesCacheUnavailable = true;
            return;
        }
        entitiesCacheUnavailable = false;
        cachedTotalEntities = tmp.totalEntities;
        cachedEntitiesByType = tmp.entitiesByType;
        cachedEntitiesByWorld = tmp.entitiesByWorld;
        cachedEntityAlerts = tmp.alerts;
        cachedEntityWarnings = tmp.activeWarnings;
        cachedEntityCriticals = tmp.activeCriticals;
    }

    private void applyEntitiesCache(MetricsSnapshot snap) {
        if (!entitiesCacheReady || entitiesCacheUnavailable) {
            snap.unavailable.add(MetricsSnapshot.MODULE_ENTITIES);
            return;
        }
        snap.totalEntities = cachedTotalEntities;
        snap.entitiesByType = cachedEntitiesByType;
        snap.entitiesByWorld = cachedEntitiesByWorld;
        snap.alerts.addAll(cachedEntityAlerts);
        snap.activeWarnings.addAll(cachedEntityWarnings);
        snap.activeCriticals.addAll(cachedEntityCriticals);
    }

    /** 区块采集（分级降频）：每 CHUNK_SAMPLE_EVERY 个周期遍历一次已加载区块并刷新缓存，其余周期复用缓存值填入快照。 */
    private void collectChunksThrottled(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        if (!chunksCacheReady || tickCounter % CHUNK_SAMPLE_EVERY == 0) {
            refreshChunksCache(cfg);
        }
        applyChunksCache(snap);
    }

    private void refreshChunksCache(ConfigManager.ConfigData cfg) {
        chunksCacheReady = true;
        if (!cfg.chunks_enabled) {
            chunksCacheUnavailable = true;
            return;
        }
        MetricsSnapshot tmp = new MetricsSnapshot();
        safeCollect(MetricsSnapshot.MODULE_CHUNKS, tmp, () -> collectChunks(tmp, cfg));
        if (tmp.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS)) {
            chunksCacheUnavailable = true;
            return;
        }
        chunksCacheUnavailable = false;
        cachedTotalLoadedChunks = tmp.totalLoadedChunks;
        cachedTotalForceLoadedChunks = tmp.totalForceLoadedChunks;
        cachedChunksByWorld = tmp.chunksByWorld;
        cachedForceLoadedChunksByWorld = tmp.forceLoadedChunksByWorld;
        cachedChunkAlerts = tmp.alerts;
        cachedChunkWarnings = tmp.activeWarnings;
        cachedChunkCriticals = tmp.activeCriticals;
    }

    private void applyChunksCache(MetricsSnapshot snap) {
        if (!chunksCacheReady || chunksCacheUnavailable) {
            snap.unavailable.add(MetricsSnapshot.MODULE_CHUNKS);
            return;
        }
        snap.totalLoadedChunks = cachedTotalLoadedChunks;
        snap.totalForceLoadedChunks = cachedTotalForceLoadedChunks;
        snap.chunksByWorld = cachedChunksByWorld;
        snap.forceLoadedChunksByWorld = cachedForceLoadedChunksByWorld;
        snap.alerts.addAll(cachedChunkAlerts);
        snap.activeWarnings.addAll(cachedChunkWarnings);
        snap.activeCriticals.addAll(cachedChunkCriticals);
    }

    /** 方块实体采集（分级降频）：每 TE_SAMPLE_EVERY 个周期遍历一次并刷新缓存，其余周期复用缓存值填入快照。 */
    private void collectTileEntitiesThrottled(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        if (!teCacheReady || tickCounter % TE_SAMPLE_EVERY == 0) {
            refreshTileEntitiesCache(cfg);
        }
        applyTileEntitiesCache(snap);
    }

    private void refreshTileEntitiesCache(ConfigManager.ConfigData cfg) {
        teCacheReady = true;
        if (!cfg.tileEntities_enabled) {
            teCacheUnavailable = true;
            return;
        }
        MetricsSnapshot tmp = new MetricsSnapshot();
        safeCollect(MetricsSnapshot.MODULE_TILE_ENTITIES, tmp, () -> collectTileEntities(tmp));
        if (tmp.unavailable.contains(MetricsSnapshot.MODULE_TILE_ENTITIES)) {
            teCacheUnavailable = true;
            return;
        }
        teCacheUnavailable = false;
        cachedTileEntityCount = tmp.tileEntityCount;
        cachedTileEntitiesByWorld = tmp.tileEntitiesByWorld;
    }

    private void applyTileEntitiesCache(MetricsSnapshot snap) {
        if (!teCacheReady || teCacheUnavailable) {
            snap.unavailable.add(MetricsSnapshot.MODULE_TILE_ENTITIES);
            return;
        }
        snap.tileEntityCount = cachedTileEntityCount;
        snap.tileEntitiesByWorld = cachedTileEntitiesByWorld;
    }

    // ==================== P0-1 JVM 异步缓存读取 ====================

    /**
     * 将异步 JVM 缓存填入当前快照（P0-1）。
     * 缓存为空（启动初期异步尚未产出）时，内存/CPU/GC 标记 unavailable，避免 0 值误导或 NPE；
     * server 的 Bukkit 部分已在主线程采集，JVM 部分（运行时长/线程数）短暂为默认值，下一周期即补上。
     */
    private void applyJvmCache(MetricsSnapshot snap) {
        MetricsSnapshot jvm = jvmCache; // volatile 读一次，保证本次填入一致
        if (jvm == null) {
            snap.unavailable.add(MetricsSnapshot.MODULE_MEMORY);
            snap.unavailable.add(MetricsSnapshot.MODULE_CPU);
            snap.unavailable.add(MetricsSnapshot.MODULE_GC);
            // W5-D #1：仍在预热窗口内 → 同标 warmingUp，展示层渲染"预热中"（预计自愈）；
            // 超窗仍未产出（异步调度异常而非自愈场景）回落真故障"不可用"，防虚假自愈信号
            if (System.currentTimeMillis() - startedAtMs <= WARMUP_WINDOW_MS) {
                snap.warmingUp.add(MetricsSnapshot.MODULE_MEMORY);
                snap.warmingUp.add(MetricsSnapshot.MODULE_CPU);
                snap.warmingUp.add(MetricsSnapshot.MODULE_GC);
            }
            return;
        }
        // 内存
        snap.heapUsed = jvm.heapUsed;
        snap.heapMax = jvm.heapMax;
        snap.heapTotal = jvm.heapTotal;
        snap.nonHeapUsed = jvm.nonHeapUsed;
        snap.nonHeapMax = jvm.nonHeapMax;
        snap.heapUsagePercent = jvm.heapUsagePercent;
        // CPU
        snap.processCpuLoad = jvm.processCpuLoad;
        snap.systemCpuLoad = jvm.systemCpuLoad;
        snap.availableProcessors = jvm.availableProcessors;
        // GC
        snap.gcCount = jvm.gcCount;
        snap.gcTime = jvm.gcTime;
        // 服务器信息 - JVM 部分（运行时长 / 线程数）
        snap.uptimeSeconds = jvm.uptimeSeconds;
        snap.threadCount = jvm.threadCount;
        // JVM 模块的不可用标记与告警一并并入当前快照（内存/CPU 的阈值告警在采集时已生成；
        // Wave 7B/7C：结构化 alerts 与派生字符串列表同步传递）
        snap.unavailable.addAll(jvm.unavailable);
        snap.alerts.addAll(jvm.alerts);
        snap.activeWarnings.addAll(jvm.activeWarnings);
        snap.activeCriticals.addAll(jvm.activeCriticals);
    }

    /**
     * 配置门控 + 异常隔离：模块被禁用同样标 unavailable（展示层显示"不可用"而非 0/空）
     */
    private void runOrMark(MetricsSnapshot snap, boolean enabled, String module, Runnable task) {
        if (!enabled) {
            snap.unavailable.add(module);
            return;
        }
        safeCollect(module, snap, task);
    }

    /**
     * 单模块异常隔离包装（P0-5）
     * catch 范围为 Exception | LinkageError：覆盖 Folia 跨线程 IllegalStateException 等运行时异常，
     * 同时显式放行 ThreadDeath / OutOfMemoryError 等致命 Error，避免吞掉 JVM 级故障信号
     */
    private void safeCollect(String module, MetricsSnapshot snap, Runnable task) {
        try {
            task.run();
        } catch (Exception | LinkageError t) {
            snap.unavailable.add(module);
            if (configManager.get().general_debug) {
                plugin.getLogger().log(Level.WARNING, "[PerfScope] 采集模块失败: " + module, t);
            } else {
                plugin.getLogger().warning("[PerfScope] 采集模块失败: " + module + ": " + t.getMessage());
            }
        }
    }

    // ==================== TPS ====================

    private void collectTPS(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        double[] tps = Bukkit.getTPS();
        snap.tps1m = round2(tps[0]);
        snap.tps5m = round2(tps[1]);
        snap.tps15m = round2(tps[2]);

        if (snap.tps1m < cfg.tps_criticalThreshold) {
            addAlert(snap, Alert.of("tps:global", Alert.LEVEL_CRITICAL,
                "TPS 严重偏低: " + snap.tps1m, MetricsSnapshot.MODULE_TPS, snap.timestamp));
        } else if (snap.tps1m < cfg.tps_warningThreshold) {
            addAlert(snap, Alert.of("tps:global", Alert.LEVEL_WARNING,
                "TPS 偏低: " + snap.tps1m, MetricsSnapshot.MODULE_TPS, snap.timestamp));
        }
    }

    // ==================== MSPT ====================

    /**
     * MSPT 三级降级链（顺序固定，勿调换）：
     * ① Paper CraftServer.getTickTimes()（真实 min/max，MC-1 修复后可达）→
     * ② getAverageTickTime()（Paper/Bukkit 平均 tick API，**不是估算**，数据真实只是无 min/max）→
     * ③ TPS 反推（唯一"估算"级：满 TPS 时语义不可用，置 -1 哨兵）。
     * 展示层的"(估算)"标签口径应以 ①||② 为"精确"，仅 ③ 为估算。
     */
    private void collectMSPT(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        if (ServerCompat.PAPER_MSPT_AVAILABLE && getTickTimesMethod != null) {
            collectMSPT_Paper(snap);
        } else if (ServerCompat.PAPER_AVG_TICK_AVAILABLE) {
            collectMSPT_BukkitAvg(snap);
        } else {
            collectMSPT_TPSFallback(snap);
        }

        if (snap.msptAvg > 0 && snap.msptAvg > cfg.mspt_criticalThreshold) {
            addAlert(snap, Alert.of("mspt:global", Alert.LEVEL_CRITICAL,
                "MSPT 严重偏高: " + snap.msptAvg + "ms", MetricsSnapshot.MODULE_MSPT, snap.timestamp));
        } else if (snap.msptAvg > 0 && snap.msptAvg > cfg.mspt_warningThreshold) {
            addAlert(snap, Alert.of("mspt:global", Alert.LEVEL_WARNING,
                "MSPT 偏高: " + snap.msptAvg + "ms", MetricsSnapshot.MODULE_MSPT, snap.timestamp));
        }
    }

    /**
     * Paper 一级：CraftServer.getTickTimes() 返回 long[]（元素为 nanos），自算 avg/min/max（MC-1 修复）。
     * 边界（P0-13 哨兵语义）：空数组或全为非正值（尚无有效 tick 采样/未填充槽位）→ 三值置 -1"未知"，
     * 不做除零也不输出误导性 0；单元素 ≤0 按无效采样跳过，避免未填充槽位拉低 min/avg。
     * 一级 invoke 失败降级到第二级 getAverageTickTime（非估算），二级再失败才落 TPS 反推。
     */
    private void collectMSPT_Paper(MetricsSnapshot snap) {
        try {
            Object server = Bukkit.getServer();
            long[] arr = (long[]) getTickTimesMethod.invoke(server);
            long sum = 0;
            long min = Long.MAX_VALUE;
            long max = 0;
            int n = 0;
            if (arr != null) {
                for (long t : arr) {
                    if (t <= 0) {
                        continue; // 跳过未填充槽位/无效采样
                    }
                    sum += t;
                    n++;
                    if (t < min) {
                        min = t;
                    }
                    if (t > max) {
                        max = t;
                    }
                }
            }
            if (n == 0) {
                snap.msptAvg = -1;
                snap.msptMin = -1;
                snap.msptMax = -1;
                return;
            }
            snap.msptAvg = round2(sum / (double) n / 1_000_000.0);
            snap.msptMin = round2(min / 1_000_000.0);
            snap.msptMax = round2(max / 1_000_000.0);
        } catch (Exception e) {
            collectMSPT_BukkitAvg(snap);
        }
    }

    /** Paper: getAverageTickTime() 获取平均 MSPT（反射方法已在构造时缓存） */
    private void collectMSPT_BukkitAvg(MetricsSnapshot snap) {
        if (avgTickMethod == null) {
            collectMSPT_TPSFallback(snap);
            return;
        }
        try {
            Object server = Bukkit.getServer();
            double mspt = ((Number) avgTickMethod.invoke(server)).doubleValue();
            snap.msptAvg = round2(mspt);
            snap.msptMin = snap.msptAvg;
            snap.msptMax = snap.msptAvg;
        } catch (Exception e) {
            collectMSPT_TPSFallback(snap);
        }
    }

    /** Bukkit: 通过 TPS 反推近似 MSPT（满 TPS 时语义"估算不可用"，置 -1 而非 0） */
    private void collectMSPT_TPSFallback(MetricsSnapshot snap) {
        if (snap.tps1m > 0 && snap.tps1m < 20) {
            snap.msptAvg = round2(1000.0 / snap.tps1m);
        } else {
            snap.msptAvg = -1;
        }
        snap.msptMin = snap.msptAvg;
        snap.msptMax = snap.msptAvg;
    }

    // ==================== 玩家 ====================

    @SuppressWarnings("deprecation")
    private void collectPlayers(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        // 兼容 Spigot/Bukkit: getOnlinePlayers() 返回 Collection
        snap.playerCount = Bukkit.getOnlinePlayers().size();
        snap.maxPlayers = Bukkit.getMaxPlayers();

        // P0-13：maxPlayers<=0（代理/精简端）时不做除法，避免 Infinity 强转为 Integer.MAX_VALUE 误报
        int pct = snap.maxPlayers > 0 ? (int) ((double) snap.playerCount / snap.maxPlayers * 100) : 0;
        if (pct >= cfg.players_criticalThreshold) {
            addAlert(snap, Alert.of("players:global", Alert.LEVEL_CRITICAL,
                "玩家数接近上限: " + snap.playerCount + "/" + snap.maxPlayers,
                MetricsSnapshot.MODULE_PLAYERS, snap.timestamp));
        } else if (pct >= cfg.players_warningThreshold) {
            addAlert(snap, Alert.of("players:global", Alert.LEVEL_WARNING,
                "玩家数较多: " + snap.playerCount + "/" + snap.maxPlayers,
                MetricsSnapshot.MODULE_PLAYERS, snap.timestamp));
        }
    }

    // ==================== 实体 ====================

    private void collectEntities(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        int total = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Integer> byWorld = new LinkedHashMap<>();

        for (World world : Bukkit.getWorlds()) {
            int worldCount = 0;
            for (Entity entity : world.getEntities()) {
                total++;
                worldCount++;
                String typeName = entity.getType().name();
                byType.merge(typeName, 1, Integer::sum);
            }
            byWorld.put(world.getName(), worldCount);
        }

        snap.totalEntities = total;
        snap.entitiesByType = byType;
        snap.entitiesByWorld = byWorld;

        // A5/RC-1 裁决——实体阈值判定口径（与 config.yml"总实体数告警阈值"注释及命令层总数口径一致）：
        // ① 在 worlds 段显式配置独立阈值（WorldSettings 覆盖）的世界：按该世界覆盖值逐世界判定；
        // ② 未配置覆盖的世界：不再逐世界套用全局阈值（旧行为下默认 2000/3000 在任何单世界上几乎不可达，
        //    导致默认实体告警全服失效），其计数由 ③ 的全服总数判定统一覆盖；
        // ③ 全服总数判定（一次）：对 snap.totalEntities 与全局阈值比较，生成"实体总数…"文案。
        for (Map.Entry<String, Integer> entry : byWorld.entrySet()) {
            ConfigManager.WorldSettings ws = configManager.getWorldSettings(entry.getKey());
            if (ws == null) {
                continue; // 无覆盖的世界不单独判定，由下方总数判定覆盖
            }
            if (entry.getValue() > ws.entities_totalCriticalThreshold) {
                addAlert(snap, Alert.of("entities:world:" + entry.getKey(), Alert.LEVEL_CRITICAL,
                    "世界 " + entry.getKey() + " 实体数严重超标: " + entry.getValue(),
                    MetricsSnapshot.MODULE_ENTITIES, snap.timestamp));
            } else if (entry.getValue() > ws.entities_totalWarningThreshold) {
                addAlert(snap, Alert.of("entities:world:" + entry.getKey(), Alert.LEVEL_WARNING,
                    "世界 " + entry.getKey() + " 实体数较多: " + entry.getValue(),
                    MetricsSnapshot.MODULE_ENTITIES, snap.timestamp));
            }
        }

        // 全服总数判定（与命令层 /ps entities 的总数口径一致）
        if (snap.totalEntities > cfg.entities_totalCriticalThreshold) {
            addAlert(snap, Alert.of("entities:global", Alert.LEVEL_CRITICAL,
                "实体总数严重超标: " + snap.totalEntities, MetricsSnapshot.MODULE_ENTITIES, snap.timestamp));
        } else if (snap.totalEntities > cfg.entities_totalWarningThreshold) {
            addAlert(snap, Alert.of("entities:global", Alert.LEVEL_WARNING,
                "实体总数较多: " + snap.totalEntities, MetricsSnapshot.MODULE_ENTITIES, snap.timestamp));
        }
    }

    // ==================== 区块 ====================

    private void collectChunks(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        int totalLoaded = 0;
        int totalForceLoaded = 0;
        Map<String, Integer> byWorld = new LinkedHashMap<>();
        Map<String, Integer> forceByWorld = new LinkedHashMap<>();

        for (World world : Bukkit.getWorlds()) {
            Chunk[] loadedChunks = world.getLoadedChunks();
            int loaded = loadedChunks.length;
            totalLoaded += loaded;
            byWorld.put(world.getName(), loaded);

            int forceLoaded = 0;
            for (Chunk chunk : loadedChunks) {
                if (chunk.isForceLoaded()) {
                    forceLoaded++;
                }
            }
            totalForceLoaded += forceLoaded;
            forceByWorld.put(world.getName(), forceLoaded);
        }

        snap.totalLoadedChunks = totalLoaded;
        snap.totalForceLoadedChunks = totalForceLoaded;
        snap.chunksByWorld = byWorld;
        snap.forceLoadedChunksByWorld = forceByWorld;

        for (Map.Entry<String, Integer> entry : byWorld.entrySet()) {
            if (entry.getValue() > cfg.chunks_loadedCriticalThreshold) {
                addAlert(snap, Alert.of("chunks:world:" + entry.getKey(), Alert.LEVEL_CRITICAL,
                    "世界 " + entry.getKey() + " 加载区块数严重超标: " + entry.getValue(),
                    MetricsSnapshot.MODULE_CHUNKS, snap.timestamp));
            } else if (entry.getValue() > cfg.chunks_loadedWarningThreshold) {
                addAlert(snap, Alert.of("chunks:world:" + entry.getKey(), Alert.LEVEL_WARNING,
                    "世界 " + entry.getKey() + " 加载区块数较多: " + entry.getValue(),
                    MetricsSnapshot.MODULE_CHUNKS, snap.timestamp));
            }
        }
    }

    // ==================== 内存 ====================

    private void collectMemory(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        Runtime runtime = Runtime.getRuntime();
        snap.heapMax = runtime.maxMemory();
        snap.heapTotal = runtime.totalMemory();
        snap.heapUsed = snap.heapTotal - runtime.freeMemory();
        // P0-13：heapMax<=0 防御（正常恒>0，guard 零成本）
        snap.heapUsagePercent = snap.heapMax > 0 ? (int) ((double) snap.heapUsed / snap.heapMax * 100) : 0;

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        snap.nonHeapUsed = memoryMXBean.getNonHeapMemoryUsage().getUsed();
        snap.nonHeapMax = memoryMXBean.getNonHeapMemoryUsage().getMax();

        if (snap.heapUsagePercent >= cfg.memory_heapCriticalThreshold) {
            addAlert(snap, Alert.of("memory:global", Alert.LEVEL_CRITICAL,
                "堆内存使用率严重偏高: " + snap.heapUsagePercent + "%", MetricsSnapshot.MODULE_MEMORY, snap.timestamp));
        } else if (snap.heapUsagePercent >= cfg.memory_heapWarningThreshold) {
            addAlert(snap, Alert.of("memory:global", Alert.LEVEL_WARNING,
                "堆内存使用率偏高: " + snap.heapUsagePercent + "%", MetricsSnapshot.MODULE_MEMORY, snap.timestamp));
        }
    }

    // ==================== CPU ====================

    private void collectCPU(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
        snap.availableProcessors = osMXBean.getAvailableProcessors();

        if (osMXBean instanceof com.sun.management.OperatingSystemMXBean sunOsMXBean) {
            // P0-13：JDK 无数据时返回 -1（原样 *100 会得到误导性的 -100），转为 -1 哨兵
            double p = sunOsMXBean.getProcessCpuLoad() * 100;
            double s = sunOsMXBean.getSystemCpuLoad() * 100;
            snap.processCpuLoad = p < 0 ? -1 : round4(p);
            snap.systemCpuLoad = s < 0 ? -1 : round4(s);

            // 哨兵 -1 不参与告警判定
            if (snap.processCpuLoad > 0 && snap.processCpuLoad > cfg.cpu_processWarningThreshold) {
                addAlert(snap, Alert.of("cpu:process", Alert.LEVEL_WARNING,
                    "进程CPU使用率偏高: " + snap.processCpuLoad + "%", MetricsSnapshot.MODULE_CPU, snap.timestamp));
            }
            if (snap.systemCpuLoad > 0 && snap.systemCpuLoad > cfg.cpu_systemWarningThreshold) {
                addAlert(snap, Alert.of("cpu:system", Alert.LEVEL_WARNING,
                    "系统CPU负载偏高: " + snap.systemCpuLoad + "%", MetricsSnapshot.MODULE_CPU, snap.timestamp));
            }
        } else {
            // 非 sun.* MXBean：无 CPU 数据，显式置 -1（避免默认 0 误导）
            snap.processCpuLoad = -1;
            snap.systemCpuLoad = -1;
        }
    }

    // ==================== GC ====================

    private void collectGC(MetricsSnapshot snap) {
        long totalGcCount = 0;
        long totalGcTime = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            // MC-2：MXBean 规范允许收集器在"未定义"时返回 -1——直接累加会污染总和，
            // 故对负值消毒（跳过），与 P0-13 哨兵约定对齐
            long c = gcBean.getCollectionCount();
            long t = gcBean.getCollectionTime();
            if (c >= 0) {
                totalGcCount += c;
            }
            if (t >= 0) {
                totalGcTime += t;
            }
        }
        snap.gcCount = totalGcCount;
        snap.gcTime = totalGcTime;
    }

    // ==================== Tile Entity ====================

    @SuppressWarnings("deprecation")
    private void collectTileEntities(MetricsSnapshot snap) {
        int total = 0;
        Map<String, Integer> byWorld = new LinkedHashMap<>();

        if (ServerCompat.PAPER_TILE_ENTITY_COUNT_AVAILABLE && tileEntityCountMethod != null) {
            // Paper: 直接使用 getTileEntityCount()（反射方法构造时已缓存）
            try {
                for (World world : Bukkit.getWorlds()) {
                    int count = (int) tileEntityCountMethod.invoke(world);
                    total += count;
                    byWorld.put(world.getName(), count);
                }
            } catch (Exception e) {
                total = collectTileEntities_Bukkit(byWorld);
            }
        } else {
            total = collectTileEntities_Bukkit(byWorld);
        }

        snap.tileEntityCount = total;
        snap.tileEntitiesByWorld = byWorld;
    }

    /**
     * Bukkit 降级路径：逐已加载 chunk 统计（开销大，数值按采集瞬间计，约数语义）
     *
     * @return 累计总数
     */
    @SuppressWarnings("deprecation")
    private int collectTileEntities_Bukkit(Map<String, Integer> byWorld) {
        int total = 0;
        for (World world : Bukkit.getWorlds()) {
            int count = 0;
            for (Chunk chunk : world.getLoadedChunks()) {
                count += chunk.getTileEntities().length;
            }
            total += count;
            byWorld.put(world.getName(), count);
        }
        return total;
    }

    // ==================== 服务器信息 ====================

    /**
     * 服务器信息 - 纯 JVM 部分（运行时长 / 线程数），由异步任务采集（MXBean 线程安全）。
     */
    private void collectServerInfoJvm(MetricsSnapshot snap) {
        snap.uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        snap.threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
    }

    /**
     * 服务器信息 - Bukkit 部分（插件数 / 世界数），留在主线程采集（Bukkit API 安全优先）。
     */
    private void collectServerInfoBukkit(MetricsSnapshot snap) {
        snap.loadedPluginCount = Bukkit.getPluginManager().getPlugins().length;
        snap.worldCount = Bukkit.getWorlds().size();
        // V1.2 D-SYS-02 诊断用；Folia 下 getPendingTasks 可能不适用，若抛异常由 safeCollect 隔离
        // （此处就地 try-catch 置 -1 哨兵，避免牵连整个 server 模块被标 unavailable）
        try {
            snap.pendingTasks = Bukkit.getScheduler().getPendingTasks().size();
        } catch (Exception | LinkageError t) {
            snap.pendingTasks = -1;
        }
    }

    // ==================== 公共访问方法 ====================

    public MetricsSnapshot getLatest() {
        return history.peekLast();
    }

    public List<MetricsSnapshot> getHistory() {
        return new ArrayList<>(history);
    }

    public int getHistorySize() {
        return history.size();
    }

    // ==================== 工具方法 ====================

    /**
     * Wave 7B/7C：结构化告警统一入口——同一条 {@link Alert} 同步写入 {@code snap.alerts}
     * （结构化真相源）与对应级别的派生字符串列表（activeCriticals / activeWarnings，
     * 命令层 /ps 活跃告警区、报告三格式、JSON alerts.* 零改动）。key/module/display 由
     * {@link Alert#of} 构造时确定。⚠️ 所有告警生成点必须经此方法，禁止单独往字符串列表
     * add（会漏掉结构化副本，告警服务将看不到该条）。
     */
    private void addAlert(MetricsSnapshot snap, Alert alert) {
        snap.alerts.add(alert);
        if (alert.critical()) {
            snap.activeCriticals.add(alert.message());
        } else {
            snap.activeWarnings.add(alert.message());
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}