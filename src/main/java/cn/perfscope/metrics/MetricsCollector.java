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
    private int taskId = -1;

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

    public void start() {
        long interval = configManager.get().general_updateInterval;
        taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
            plugin,
            this::collect,
            0L,
            interval
        );
    }

    public void stop() {
        if (taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public void collect() {
        MetricsSnapshot snap = new MetricsSnapshot();
        ConfigManager.ConfigData cfg = configManager.get();

        // P0-5：每模块独立 try-catch，失败仅标记 unavailable，不中断其余模块
        runOrMark(snap, cfg.tps_enabled, MetricsSnapshot.MODULE_TPS, () -> collectTPS(snap, cfg));
        runOrMark(snap, cfg.mspt_enabled, MetricsSnapshot.MODULE_MSPT, () -> collectMSPT(snap, cfg));
        runOrMark(snap, cfg.players_enabled, MetricsSnapshot.MODULE_PLAYERS, () -> collectPlayers(snap, cfg));
        runOrMark(snap, cfg.entities_enabled, MetricsSnapshot.MODULE_ENTITIES, () -> collectEntities(snap, cfg));
        runOrMark(snap, cfg.chunks_enabled, MetricsSnapshot.MODULE_CHUNKS, () -> collectChunks(snap, cfg));
        runOrMark(snap, cfg.memory_enabled, MetricsSnapshot.MODULE_MEMORY, () -> collectMemory(snap, cfg));
        runOrMark(snap, cfg.cpu_enabled, MetricsSnapshot.MODULE_CPU, () -> collectCPU(snap, cfg));
        safeCollect(MetricsSnapshot.MODULE_GC, snap, () -> collectGC(snap));
        runOrMark(snap, cfg.tileEntities_enabled, MetricsSnapshot.MODULE_TILE_ENTITIES, () -> collectTileEntities(snap));
        safeCollect(MetricsSnapshot.MODULE_SERVER, snap, () -> collectServerInfo(snap));

        history.addLast(snap);
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }
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

    private void collectServerInfo(MetricsSnapshot snap) {
        snap.uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        snap.threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        snap.loadedPluginCount = Bukkit.getPluginManager().getPlugins().length;
        snap.worldCount = Bukkit.getWorlds().size();
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