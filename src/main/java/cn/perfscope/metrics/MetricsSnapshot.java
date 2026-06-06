package cn.perfscope.metrics;

import java.util.*;

/**
 * 性能指标快照 - 包含某一时刻的所有性能数据
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

    // === 红石 ===
    public int redstoneTicks;

    // === Tile Entity ===
    public int tileEntityCount;
    public Map<String, Integer> tileEntitiesByWorld;

    // === 网络 ===
    public long bytesSent;
    public long bytesReceived;
    public long bytesSentPerSec;
    public long bytesReceivedPerSec;

    // === 服务器 ===
    public long uptimeSeconds;
    public int threadCount;
    public int loadedPluginCount;
    public int pendingTasks;
    public int worldCount;

    // === 告警信息 ===
    public List<String> activeWarnings = new ArrayList<>();
    public List<String> activeCriticals = new ArrayList<>();

    // === 时间戳 ===
    public long timestamp;

    public MetricsSnapshot() {
        this.timestamp = System.currentTimeMillis();
        this.entitiesByType = new LinkedHashMap<>();
        this.entitiesByWorld = new LinkedHashMap<>();
        this.chunksByWorld = new LinkedHashMap<>();
        this.forceLoadedChunksByWorld = new LinkedHashMap<>();
        this.tileEntitiesByWorld = new LinkedHashMap<>();
    }
}
