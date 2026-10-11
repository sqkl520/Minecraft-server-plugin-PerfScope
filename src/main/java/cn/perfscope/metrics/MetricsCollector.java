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
import cn.perfscope.compat.SchedulerAdapter;
import cn.perfscope.compat.ServerCompat;
import cn.perfscope.config.ConfigManager;
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

    // Paper MSPT 反射缓存
    private Method getTickTimesMethod;
    private Method tickTimes_getTime;
    private Method tickTimes_getMinTime;
    private Method tickTimes_getMaxTime;

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
    private List<String> cachedEntityWarnings = new ArrayList<>();
    private List<String> cachedEntityCriticals = new ArrayList<>();

    // 区块缓存
    private boolean chunksCacheReady = false;
    private boolean chunksCacheUnavailable = false;
    private int cachedTotalLoadedChunks = 0;
    private int cachedTotalForceLoadedChunks = 0;
    private Map<String, Integer> cachedChunksByWorld = new LinkedHashMap<>();
    private Map<String, Integer> cachedForceLoadedChunksByWorld = new LinkedHashMap<>();
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

    public MetricsCollector(PerfScope plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        if (ServerCompat.PAPER_MSPT_AVAILABLE) {
            resolvePaperMSPT();
        }
        resolveReflections();
    }

    private void resolvePaperMSPT() {
        try {
            Object server = Bukkit.getServer();
            Method getServer = server.getClass().getMethod("getServer");
            Object nms = getServer.invoke(server);
            getTickTimesMethod = nms.getClass().getMethod("getTickTimes");
            Object tickTimes = getTickTimesMethod.invoke(nms);
            tickTimes_getTime = tickTimes.getClass().getMethod("time");
            try {
                tickTimes_getMinTime = tickTimes.getClass().getMethod("minTime");
                tickTimes_getMaxTime = tickTimes.getClass().getMethod("maxTime");
            } catch (NoSuchMethodException ignored) {
                // 某些版本可能只有 time()
            }
        } catch (Exception e) {
            // 解析失败，降级为 Bukkit
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

        long interval = configManager.get().general_updateInterval;
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
        // JVM 模块的不可用标记与告警一并并入当前快照（内存/CPU 的阈值告警在采集时已生成）
        snap.unavailable.addAll(jvm.unavailable);
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
            snap.activeCriticals.add("TPS 严重偏低: " + snap.tps1m);
        } else if (snap.tps1m < cfg.tps_warningThreshold) {
            snap.activeWarnings.add("TPS 偏低: " + snap.tps1m);
        }
    }

    // ==================== MSPT ====================

    private void collectMSPT(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        if (ServerCompat.PAPER_MSPT_AVAILABLE && getTickTimesMethod != null) {
            collectMSPT_Paper(snap);
        } else if (ServerCompat.PAPER_AVG_TICK_AVAILABLE) {
            collectMSPT_BukkitAvg(snap);
        } else {
            collectMSPT_TPSFallback(snap);
        }

        if (snap.msptAvg > 0 && snap.msptAvg > cfg.mspt_criticalThreshold) {
            snap.activeCriticals.add("MSPT 严重偏高: " + snap.msptAvg + "ms");
        } else if (snap.msptAvg > 0 && snap.msptAvg > cfg.mspt_warningThreshold) {
            snap.activeWarnings.add("MSPT 偏高: " + snap.msptAvg + "ms");
        }
    }

    /** Paper: getTickTimes() 获取完整 MSPT 数据 */
    private void collectMSPT_Paper(MetricsSnapshot snap) {
        try {
            Object server = Bukkit.getServer();
            Method getServer = server.getClass().getMethod("getServer");
            Object nms = getServer.invoke(server);
            Object tickTimes = getTickTimesMethod.invoke(nms);

            double avgNanos = ((Number) tickTimes_getTime.invoke(tickTimes)).doubleValue();
            snap.msptAvg = round2(avgNanos / 1_000_000.0);

            if (tickTimes_getMinTime != null && tickTimes_getMaxTime != null) {
                snap.msptMin = round2(((Number) tickTimes_getMinTime.invoke(tickTimes)).doubleValue() / 1_000_000.0);
                snap.msptMax = round2(((Number) tickTimes_getMaxTime.invoke(tickTimes)).doubleValue() / 1_000_000.0);
            } else {
                snap.msptMin = snap.msptAvg;
                snap.msptMax = snap.msptAvg;
            }
        } catch (Exception e) {
            collectMSPT_TPSFallback(snap);
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
            snap.activeCriticals.add("玩家数接近上限: " + snap.playerCount + "/" + snap.maxPlayers);
        } else if (pct >= cfg.players_warningThreshold) {
            snap.activeWarnings.add("玩家数较多: " + snap.playerCount + "/" + snap.maxPlayers);
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

        for (Map.Entry<String, Integer> entry : byWorld.entrySet()) {
            ConfigManager.WorldSettings ws = configManager.getWorldSettings(entry.getKey());
            int warnThreshold = ws != null ? ws.entities_totalWarningThreshold : cfg.entities_totalWarningThreshold;
            int critThreshold = ws != null ? ws.entities_totalCriticalThreshold : cfg.entities_totalCriticalThreshold;

            if (entry.getValue() > critThreshold) {
                snap.activeCriticals.add("世界 " + entry.getKey() + " 实体数严重超标: " + entry.getValue());
            } else if (entry.getValue() > warnThreshold) {
                snap.activeWarnings.add("世界 " + entry.getKey() + " 实体数较多: " + entry.getValue());
            }
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
                snap.activeCriticals.add("世界 " + entry.getKey() + " 加载区块数严重超标: " + entry.getValue());
            } else if (entry.getValue() > cfg.chunks_loadedWarningThreshold) {
                snap.activeWarnings.add("世界 " + entry.getKey() + " 加载区块数较多: " + entry.getValue());
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
            snap.activeCriticals.add("堆内存使用率严重偏高: " + snap.heapUsagePercent + "%");
        } else if (snap.heapUsagePercent >= cfg.memory_heapWarningThreshold) {
            snap.activeWarnings.add("堆内存使用率偏高: " + snap.heapUsagePercent + "%");
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
                snap.activeWarnings.add("进程CPU使用率偏高: " + snap.processCpuLoad + "%");
            }
            if (snap.systemCpuLoad > 0 && snap.systemCpuLoad > cfg.cpu_systemWarningThreshold) {
                snap.activeWarnings.add("系统CPU负载偏高: " + snap.systemCpuLoad + "%");
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
            totalGcCount += gcBean.getCollectionCount();
            totalGcTime += gcBean.getCollectionTime();
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

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}