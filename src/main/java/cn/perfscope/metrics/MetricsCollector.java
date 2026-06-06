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

    // 网络流量计算
    private long lastBytesSent;
    private long lastBytesReceived;
    private long lastNetworkCheck;

    // Paper MSPT 反射缓存
    private Method getTickTimesMethod;
    private Method tickTimes_getTime;
    private Method tickTimes_getMinTime;
    private Method tickTimes_getMaxTime;

    public MetricsCollector(PerfScope plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        if (ServerCompat.PAPER_MSPT_AVAILABLE) {
            resolvePaperMSPT();
        }
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

        if (cfg.tps_enabled) collectTPS(snap, cfg);
        if (cfg.mspt_enabled) collectMSPT(snap, cfg);
        if (cfg.players_enabled) collectPlayers(snap, cfg);
        if (cfg.entities_enabled) collectEntities(snap, cfg);
        if (cfg.chunks_enabled) collectChunks(snap, cfg);
        if (cfg.memory_enabled) collectMemory(snap, cfg);
        if (cfg.cpu_enabled) collectCPU(snap, cfg);
        collectGC(snap);
        if (cfg.redstone_enabled && ServerCompat.PAPER_REDSTONE_AVAILABLE) collectRedstone(snap);
        if (cfg.tileEntities_enabled) collectTileEntities(snap);
        if (cfg.network_enabled && ServerCompat.PAPER_NETWORK_AVAILABLE) collectNetwork(snap, cfg);
        collectServerInfo(snap);

        history.addLast(snap);
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
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

    /** Paper: getAverageTickTime() 获取平均 MSPT */
    private void collectMSPT_BukkitAvg(MetricsSnapshot snap) {
        try {
            Object server = Bukkit.getServer();
            Method getAvg = server.getClass().getMethod("getAverageTickTime");
            double mspt = ((Number) getAvg.invoke(server)).doubleValue();
            snap.msptAvg = round2(mspt);
            snap.msptMin = snap.msptAvg;
            snap.msptMax = snap.msptAvg;
        } catch (Exception e) {
            collectMSPT_TPSFallback(snap);
        }
    }

    /** Bukkit: 通过 TPS 反推近似 MSPT */
    private void collectMSPT_TPSFallback(MetricsSnapshot snap) {
        if (snap.tps1m > 0 && snap.tps1m < 20) {
            snap.msptAvg = round2(1000.0 / snap.tps1m);
        } else {
            snap.msptAvg = 0;
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

        int pct = (int) ((double) snap.playerCount / snap.maxPlayers * 100);
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
        snap.heapUsagePercent = (int) ((double) snap.heapUsed / snap.heapMax * 100);

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
            snap.processCpuLoad = round4(sunOsMXBean.getProcessCpuLoad() * 100);
            snap.systemCpuLoad = round4(sunOsMXBean.getSystemCpuLoad() * 100);

            if (snap.processCpuLoad > cfg.cpu_processWarningThreshold) {
                snap.activeWarnings.add("进程CPU使用率偏高: " + snap.processCpuLoad + "%");
            }
            if (snap.systemCpuLoad > cfg.cpu_systemWarningThreshold) {
                snap.activeWarnings.add("系统CPU负载偏高: " + snap.systemCpuLoad + "%");
            }
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

    // ==================== 红石 (Paper) ====================

    private void collectRedstone(MetricsSnapshot snap) {
        try {
            Method getServer = Bukkit.getServer().getClass().getMethod("getServer");
            Object nms = getServer.invoke(Bukkit.getServer());
            for (Method m : nms.getClass().getDeclaredMethods()) {
                if (m.getName().contains("Redstone") && m.getParameterCount() == 0) {
                    Object result = m.invoke(nms);
                    if (result instanceof Number) {
                        snap.redstoneTicks = ((Number) result).intValue();
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        snap.redstoneTicks = -1;
    }

    // ==================== Tile Entity ====================

    @SuppressWarnings("deprecation")
    private void collectTileEntities(MetricsSnapshot snap) {
        int total = 0;
        Map<String, Integer> byWorld = new LinkedHashMap<>();

        if (ServerCompat.PAPER_TILE_ENTITY_COUNT_AVAILABLE) {
            // Paper: 直接使用 getTileEntityCount()
            try {
                Method getTileEntityCount = World.class.getMethod("getTileEntityCount");
                for (World world : Bukkit.getWorlds()) {
                    int count = (int) getTileEntityCount.invoke(world);
                    total += count;
                    byWorld.put(world.getName(), count);
                }
            } catch (Exception e) {
                collectTileEntities_Bukkit(total, byWorld);
            }
        } else {
            collectTileEntities_Bukkit(total, byWorld);
        }

        snap.tileEntityCount = total;
        snap.tileEntitiesByWorld = byWorld;
    }

    @SuppressWarnings("deprecation")
    private void collectTileEntities_Bukkit(int total, Map<String, Integer> byWorld) {
        total = 0;
        for (World world : Bukkit.getWorlds()) {
            int count = 0;
            for (Chunk chunk : world.getLoadedChunks()) {
                count += chunk.getTileEntities().length;
            }
            total += count;
            byWorld.put(world.getName(), count);
        }
    }

    // ==================== 网络 (Paper) ====================

    private void collectNetwork(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        long now = System.currentTimeMillis();
        snap.bytesSent = 0;
        snap.bytesReceived = 0;

        if (lastNetworkCheck > 0) {
            long elapsedMs = now - lastNetworkCheck;
            if (elapsedMs > 0) {
                snap.bytesSentPerSec = (snap.bytesSent - lastBytesSent) * 1000 / elapsedMs;
                snap.bytesReceivedPerSec = (snap.bytesReceived - lastBytesReceived) * 1000 / elapsedMs;

                if (snap.bytesSentPerSec > cfg.network_warningThreshold ||
                    snap.bytesReceivedPerSec > cfg.network_warningThreshold) {
                    snap.activeWarnings.add("网络流量偏高: " +
                        formatBytes(snap.bytesSentPerSec) + "/s / " +
                        formatBytes(snap.bytesReceivedPerSec) + "/s");
                }
            }
        }

        lastBytesSent = snap.bytesSent;
        lastBytesReceived = snap.bytesReceived;
        lastNetworkCheck = now;
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

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}