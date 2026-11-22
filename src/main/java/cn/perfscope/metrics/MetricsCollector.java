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
import cn.perfscope.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.lang.management.*;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    // ==================== 9A-10 #1：代际令牌（generation） ====================
    // 背景：TaskHandle.cancel() 只是"请求取消"，不中断、也不 join——旧任务若正在执行中，
    // 会继续跑完并回写 tickCounter / 四个 cacheReady / jvmCache 等普通字段（它们不是 volatile，
    // 也没有任何代际校验）。reload 之后旧任务把**旧配置**算出的 JVM 快照写进 jvmCache，
    // 新一代码就凭空拿到上一代的数值，且会被后续快照原样复制进 history。
    // 解法：每个任务闭包捕获启动那一刻的 generation 值；在发布任何状态（jvmCache / history /
    // 缓存字段）之前比对 generation，不一致即说明自己是已被替换的旧一代，直接丢弃本轮结果。
    // volatile：reload 在主线程自增，异步 JVM 任务在另一线程读。
    private volatile long generation = 0L;

    // P0-1 分级降频：采集周期计数器（仅主线程 collectSync 访问，无需同步）
    private long tickCounter = 0;

    // P0-1 分级降频倍数：重模块每 N 个采集周期才真正遍历一次世界，其余周期复用上次缓存值，
    // 以降低主线程遍历实体/区块/方块实体的开销（常量，不进 config）
    // 分级降频周期（v1.3.0 -03，降级版）。
    //
    // 为什么只动 TE_SAMPLE_EVERY：四个重模块的周期原本是 5/5/10/30，最小公倍数 30，
    // 意味着每 30 个采集周期四者同时触发一次（"撞车拍"）。实测空载测试服上撞车拍的
    // max 达 127ms（1.3-2.5 tick）。
    //
    // 改成 5/5/7/30 后最小公倍数是 210 个周期（默认 interval 下约 3.5 分钟），
    // 撞车频率降到原来的 1/7，而方块实体的刷新频率从每 10 周期提到每 7 周期（+43%）——
    // 这是单项最轻的模块，代价可以接受。
    //
    // 不做的事（红队 B / 红队 05 独立验证）：不引入相位偏移，不改 CHUNK 5→6。
    // 打散方案的前提是"每个模块单干都小于 1 tick"，而 50k 实体档下 hotspots 单模块
    // 就要 106ms、entities 51.7ms —— 在那种规模下打散只是把一个大卡顿拆成几个小卡顿，
    // 超预算周期数反而从 600 涨到 683（+13.8%）。真正的解法是热点扫描分区（v1.4），
    // 不是调周期。详见 docs/06-tasks/research/redteam-05-v130.md §4.2。
    private static final long ENTITY_SAMPLE_EVERY = 5;   // 实体：每 5 个采集周期遍历一次
    private static final long CHUNK_SAMPLE_EVERY = 5;    // 区块：每 5 个采集周期遍历一次
    private static final long TE_SAMPLE_EVERY = 7;       // 方块实体：每 7 个周期（与 5/30 互质，拉长撞车周期）

    // 历史数据
    private final Deque<MetricsSnapshot> history = new ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY = 300;

    /**
     * 历史环的当前元素数（与 {@link #history} 同步维护）。
     *
     * <p><b>为什么需要这个计数器</b>：{@link ConcurrentLinkedDeque#size()} 是 O(n) 遍历，
     * 不是常数时间。原实现在每个采集周期都调一次 `while (history.size() > MAX_HISTORY)`，
     * 默认 1Hz + 环长 300，等于每秒遍历 300 个节点——纯粹的浪费，而且随环长线性增长。
     * 用计数器后是 O(1)，且溢出的判断在绝大多数周期上是"不成立"的快速路径。</p>
     *
     * <p>只在主线程 {@code collectSync} 里增减，无需原子性；但仍用 int 而非 long：
     * 环长上限是 300，int 绰绰有余。</p>
     */
    private int historyCount = 0;

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
    // v1.4：级别缓存——非采样周期这些键必须照常输出，否则 5 次抓取里有 4 次序列消失，
    // 运维的 for: 计时器会被反复重置。与上面 alerts 同生命周期。
    private Map<String, Integer> cachedEntityLevels = new LinkedHashMap<>();

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
    // v1.4：同 cachedEntityLevels
    private Map<String, Integer> cachedChunkLevels = new LinkedHashMap<>();

    // 方块实体缓存（collectTileEntities 无阈值告警，仅缓存计数）
    private boolean teCacheReady = false;
    private boolean teCacheUnavailable = false;
    private int cachedTileEntityCount = 0;
    private Map<String, Integer> cachedTileEntitiesByWorld = new LinkedHashMap<>();

    // ==================== Wave 8C 热点区块缓存（仅主线程 collectSync 访问，无需同步） ====================
    // 复用语义与实体/区块缓存一致：命中采样倍数时全遍历刷新，其余周期复用缓存值（含 unavailable 标记）
    private boolean hotspotsCacheReady = false;
    private boolean hotspotsCacheUnavailable = false;
    private List<ChunkHotspot> cachedHotspots = new ArrayList<>();

    // ==================== v1.4 热点分区增量扫描（仅主线程 collectSync 访问，无需同步） ====================
    // 进行中的一轮扫描（null = 当前没有进行中的轮次，下一拍会重新快照区块集合）。
    // 一轮 = 把「各世界已加载区块」摊成一个线性序列后按 scan-shards 切片，跨多个采集周期扫完。
    private HotspotShardSweep hotspotSweep = null;

    // 本轮区块序列的快照（世界下标 / chunkX / chunkZ 三个平行数组，长度 = 本轮区块总数）。
    // 只存坐标不存 Chunk 句柄：Chunk 持有底层 LevelChunk 引用，攥着一轮（30 个周期）会阻止
    // 期间被卸载的区块释放内存；坐标快照完全不干预加载状态，扫描时逐项 isChunkLoaded 校验。
    private static final World[] NO_WORLDS = new World[0];
    private static final int[] EMPTY_COORDS = new int[0];
    private World[] sweepWorlds = NO_WORLDS;
    private int[] sweepChunkX = EMPTY_COORDS;
    private int[] sweepChunkZ = EMPTY_COORDS;
    private int[] sweepChunkWorld = EMPTY_COORDS;

    /** 已完成的完整轮次数（0 = 首轮尚未扫完，此时允许按分片发布部分结果，见 tickHotspots 注释）。 */
    private long hotspotCompletedRounds = 0L;

    /**
     * 下一次推进分片扫描的 {@code tickCounter} 值。
     *
     * <p>用显式排程而不是 {@code tickCounter % step}：步长依赖本轮<b>实际</b>分片数，
     * 而实际分片数受区块总数影响（区块数少于配置分片数时按区块数切），取模无法表达。
     * 详见 {@link #collectHotspotsThrottled}。</p>
     */
    private long hotspotNextScanTick = 0L;


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

    // ==================== 9A-20 可运维性：采集自身健康 ====================
    // 背景（锐评 9A-20 #1）：采集线程/任务一旦死亡，getLatest() 仍然返回 history 里的最后一张快照，
    // 命令层照常渲染"正常"——唯一线索是一个需要人工比对的 HH:mm:ss。以下字段把"采集是否还活着"
    // 变成可查询的事实，供 /perfscope status 与展示层红字告警使用。

    /** 最近一次成功采集的完成时刻（epoch ms；0 = 启动后尚未成功过一次）。仅主线程写，volatile 供命令线程读。 */
    private volatile long lastCollectAtMs = 0L;

    /** v1.5：跨服上报失败日志的限频时间戳与窗口（登录抖动时可能每周期都失败，不能刷屏）。 */
    private volatile long lastNetworkFailureLogAtMs = 0L;
    private static final long NETWORK_FAILURE_LOG_INTERVAL_MS = 300_000L;
    /**
     * 最近一次成功完成 JVM 异步采集的时刻（epoch ms；0 = 尚未成功过）。仅异步线程写。
     *
     * <p>9A-20：主线程节拍正常（{@link #isStale()} 为 false）并不代表 JVM 指标是新的——
     * 两条节拍是独立任务，异步那条被静默取消时，内存/CPU/GC 会冻结在最后一次的值上。
     * 展示层应单独检查 {@link #isJvmStale()}（本字段不单独暴露，避免又造一个零调用访问器）。</p>
     */
    private volatile long lastJvmCollectAtMs = 0L;

    /** 连续采集周期失败次数（collectSync 未捕获异常计一次；成功一次即清零）。 */
    private volatile int consecutiveCollectFailures = 0;

    /** 采集是否已被判定为"停止工作"（致命 Error 逃逸 / 连续失败达阈值）。成功一次即清除。 */
    private volatile boolean collectorDead = false;

    /** 最近一次采集周期级异常的单行描述（供 status 展示根因，null = 无）。 */
    private volatile String lastCollectError = null;

    /** 首次连续失败的时刻（epoch ms；0 = 当前不在失败序列中）。 */
    private volatile long firstFailureAtMs = 0L;

    /** 周期级失败日志限频间隔：5 分钟（与 WebhookNotifier 60s / HistoryFileStore 300s 同款范式）。 */
    private static final long CYCLE_FAILURE_LOG_INTERVAL_MS = 300_000L;
    /** 周期级失败日志上次输出时刻（0 = 从未）。 */
    private volatile long lastCycleFailureLogAtMs = 0L;
    /** 连续失败达到此数即打 SEVERE 并提示 reload/重启（默认 1 秒一周期 → 10 秒内可判定）。 */
    private static final int DEAD_AFTER_CONSECUTIVE_FAILURES = 10;

    // ==================== 9A-20 #2：模块级失败日志限频状态 ====================
    // 背景：safeCollect 原实现每周期一条 WARNING，默认 1 秒一周期 = 86400 条/天（单模块持续失败时），
    // 刷屏到看不见其他插件的报错。改为"首次立即记，之后每 5 分钟一条"，并带连续失败次数与首次失败时刻。

    /** 模块级日志限频间隔：5 分钟（与 HistoryFileStore 300s 同款）。 */
    private static final long MODULE_FAILURE_LOG_INTERVAL_MS = 300_000L;

    // ==================== v1.3.0 -04：采集自监测 ====================

    /**
     * 主线程单次采集的告警阈值（毫秒）。
     *
     * <p>取 1 tick = 50ms：这是"插件在这一拍上占满了整个 tick 预算"的界。
     * 超过它，服务器这一 tick 就没给游戏逻辑留时间——玩家的体感是卡一下。</p>
     */
    private static final long COLLECT_DURATION_WARN_MS = 50L;

    /** 耗时样本窗口大小（保留最近 N 个周期，用于算 p50/p95/max 分布）。 */
    private static final int COLLECT_DURATION_WINDOW = 300;

    /** 最近 N 个采集周期的耗时（毫秒）；仅主线程 collectSync 写，读方见 {@link #collectDurationStats()}。 */
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> collectDurations =
        new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** {@link #collectDurations} 的当前长度（O(1)，避免 ConcurrentLinkedDeque.size() 的 O(n) 遍历）。 */
    private int collectDurationsCount = 0;

    /** 模块健康状态（按模块名索引；主线程 collectSync 与异步 collectJvmAsync 共用 Map，但各自模块互不重叠，MODULE_SERVER 例外）。 */
    private final Map<String, ModuleHealth> moduleHealth = new ConcurrentHashMap<>();

    /** 单个模块的失败状态机（字段 volatile：跨主线程/异步线程读写的近似正确即可，最坏是多记/漏记一条日志）。 */
    private static final class ModuleHealth {
        volatile boolean failing = false;
        volatile int consecutiveFailures = 0;
        volatile long firstFailureAtMs = 0L;
        volatile long lastLogAtMs = 0L;
        volatile long lastRecoveryLogAtMs = 0L;
        volatile String lastMessage = null;
    }

    // ==================== 9A-20 #3：重模块"被配置禁用"缓存标记 ====================
    // refresh*Cache 在禁用分支置 true，apply*Cache 据此把模块同时写入 disabledModules（unavailable 语义不变）
    private boolean entitiesCacheDisabled = false;
    private boolean chunksCacheDisabled = false;
    private boolean teCacheDisabled = false;
    private boolean hotspotsCacheDisabled = false;

    // ==================== 9B-3：Folia 跨 region 聚合跳过标记 ====================
    // refresh*Cache 走"平台限制"分支时置 true，apply*Cache 据此把模块同时写入
    // disabledModules（按规格要求）+ platformSkipped（供展示层区分"服主关掉"与"平台做不到"）。
    // 与 entitiesCacheDisabled 分开存放是刻意的：两者可能同时成立（服主关了 entities + Folia 跳过 chunks），
    // 展示层据此分别渲染"已禁用（config …=false）"与"平台限制（Folia 跨 region）"。
    private boolean entitiesCachePlatformSkipped = false;
    private boolean chunksCachePlatformSkipped = false;
    private boolean teCachePlatformSkipped = false;
    private boolean hotspotsCachePlatformSkipped = false;

    /** 9B-3：平台跳过提示只打一次（同一次插件生命周期内；不随 reload 重置，避免刷日志）。 */
    private volatile boolean foliaSkipLogged = false;

    /**
     * 9B-3：需要"全服聚合"遍历的模块集合。
     *
     * <p>这些模块的数据源在 Folia 上都是<b>跨 region</b> 的：
     * {@code World.getLoadedChunks()} / {@code World.getEntities()} / {@code Chunk.getEntities()} /
     * {@code Chunk.getTileEntities()} / {@code World.getTileEntityCount()}。Folia 把这些状态的归属
     * 拆到各 region 线程，Bukkit 层没有任何"一次性拿全服快照"的线程安全 API——在 GlobalRegion
     * 线程上调用，轻则只看到 global region 自己的那部分（数字偏小、且在任意两秒之间跳变），
     * 重则直接 {@code IllegalStateException}（跨 region 访问）。所以本插件在 Folia 上不猜，
     * 直接跳过并如实标注。</p>
     *
     * <p>TPS / MSPT / 玩家数 / JVM 指标<b>不在此集合内</b>：它们的口径问题分别由 9A-2（TPS 标注
     * {@code (global region)}）与 9A-1（MSPT 直接不可用）处理，不属于本项。</p>
     */
    private static final Set<String> FOLIA_CROSS_REGION_MODULES = Set.of(
        MetricsSnapshot.MODULE_ENTITIES,
        MetricsSnapshot.MODULE_CHUNKS,
        MetricsSnapshot.MODULE_TILE_ENTITIES,
        MetricsSnapshot.MODULE_HOTSPOTS);

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
                plugin.getLogger().warning(Messages.get("run.collector.reflect-avg-tick-failed"));
            }
        }
        // Paper 世界 TileEntity 计数
        if (ServerCompat.PAPER_TILE_ENTITY_COUNT_AVAILABLE) {
            try {
                tileEntityCountMethod = World.class.getMethod("getTileEntityCount");
            } catch (Exception e) {
                plugin.getLogger().warning(Messages.get("run.collector.reflect-te-count-failed"));
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
        // 9A-10 #1：开启新一代。stop() 已经自增过一次，这里再自增，确保"旧任务捕获的代际"
        // 与新任务捕获的代际之间至少隔一代（cancel 期间仍在飞的旧任务一律作废）。
        long gen = ++generation;
        // 重置降频计数与缓存，使 reload 后首个周期立即重新采集，不复用旧快照数据
        tickCounter = 0;
        entitiesCacheReady = false;
        chunksCacheReady = false;
        teCacheReady = false;
        hotspotsCacheReady = false;
        // v1.4：进行中的分片轮次随 reload 丢弃——这轮是用旧配置（旧分片数）切出来的，
        // 跨配置代继续推进会得到"前半轮旧切法 + 后半轮新切法"的混合覆盖，不如从头开始。
        dropoutHotspotSweep();
        hotspotCompletedRounds = 0L;
        // 9B-3：平台跳过标记随缓存一起重置（配置可能在 reload 后变了，但"这是不是 Folia"不会变，
        // 所以 foliaSkipLogged 刻意不重置——见 logFoliaAggregationSkipOnce 注释）
        entitiesCachePlatformSkipped = false;
        chunksCachePlatformSkipped = false;
        teCachePlatformSkipped = false;
        hotspotsCachePlatformSkipped = false;
        jvmCache = null;
        startedAtMs = System.currentTimeMillis(); // W5-D #1：reload 后重新进入预热窗口（与 jvmCache 置空同步）
        // 9A-20 #4：reload/重启是人工介入的恢复动作，清空"死亡"判定让新任务有干净的判定基线
        //（否则上一代任务的失败计数会让 status 一启动就报"已停止"）。
        // ⚠️ lastCollectAtMs / lastJvmCollectAtMs 刻意不清零：它们回答的是"最后一张真数据是什么时候的"，
        // reload 不改变这个事实。若清零，reload 后 status 会谎称"从未拿到数据"（而 history 里明明有）。
        // 重新启动带来的短暂"已停止更新 N 秒"由 isStale() 如实报出，首个周期成功后自动消失。
        collectorDead = false;
        consecutiveCollectFailures = 0;
        firstFailureAtMs = 0L;
        lastCollectError = null;
        // 日志限频基准也清零：reload 是新的判定基线，首条失败必须立即可见（不被上一代残留的限频窗口压住）
        lastCycleFailureLogAtMs = 0L;
        lastJvmCycleFailureLogAtMs = 0L;
        // 9A-20 #2：模块健康状态随 reload 重置（配置可能刚改回 enabled=true，失败序列应重新计）
        moduleHealth.clear();
        // f-s04-conc-04：CPU 迟滞闩锁随 reload 重置——否则旧代异步任务留下的闩锁被新代继承，
        // 新配置下的首个周期不重新判定，告警会带着上一代的判定继续挂着。
        processCpuLatched = false;
        systemCpuLatched = false;

        // L-2 修复：interval ≤0 会被 Bukkit 调度器 coerce 成单次任务（负值 period=-1 跑一轮即静默死亡）
        // 或每 tick（0 → 1），Folia 下则被 SchedulerAdapter 校正为每 tick，行为分叉，
        // 故强制下限 1（对齐 AlertService.start 已有范式），非法配置同时记 warning 便于诊断
        int configuredInterval = configManager.get().general_updateInterval;
        if (configuredInterval <= 0) {
            plugin.getLogger().warning(Messages.get("run.collector.invalid-interval", configuredInterval));
        }
        long interval = Math.max(1, configuredInterval);
        // 9A-10 #1：把代际捕获进闭包——任务体每次执行前先校验，过期代直接丢弃本轮结果
        final long taskGen = gen;
        syncHandle = SchedulerAdapter.runGlobalTimer(plugin, () -> collectSync(taskGen), 0L, interval);
        jvmHandle = SchedulerAdapter.runAsyncTimer(plugin, () -> collectJvmAsync(taskGen), 0L, interval);
    }

    /**
     * 停止采集任务：取消两个任务句柄（句柄为空时无操作），并置空以便重建。
     *
     * <p><b>9A-10 #1</b>：cancel() 只能阻止"下一次"执行，拦不住已经在跑的那一次。这里同时推进
     * {@link #generation}，使所有仍在飞的旧任务在下一个状态发布点自行作废（见 {@link #isCurrentGeneration(long)}）。</p>
     */
    public void stop() {
        generation++;   // 9A-10：让仍在飞的旧任务作废
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
     * 9A-10 #1：本轮任务是否仍属于当前一代。
     *
     * <p>任务闭包在 {@link #start()} 时捕获当时的 generation；reload 走 stop()→start() 会使
     * 该值落后，此时旧任务必须<b>在发布任何状态之前</b>自行退出，否则会把旧配置的计算结果
     * 写进新一代（典型症状：reload 改了阈值/采集间隔，但第一个周期的数据仍是旧阈值判出来的）。</p>
     */
    private boolean isCurrentGeneration(long capturedGeneration) {
        return capturedGeneration == generation;
    }

    /**
     * 主线程采集节拍（P0-1）。轻量 Bukkit 指标每周期采集；实体/区块/方块实体分级降频复用缓存；
     * JVM 指标从异步缓存读取填入。每周期产出完整 MetricsSnapshot 入 history（节奏不变，趋势/报告不受影响）。
     *
     * <p><b>9A-20 #4（采集任务自身健康）</b>：本方法即 Bukkit repeating task 的任务体。旧实现整个方法体
     * 无 try——任何未捕获异常（含 Error）都会让 Bukkit 直接 <b>cancel 该 repeating task</b>，采集永久死亡
     * 且无人知晓（唯一的"证据"是 /ps 一直显示最后一张快照）。现在任务体整体包在
     * {@code try/catch(Throwable)} 内：异常计一次周期失败、发布死亡标志、限频记日志，任务本身继续按周期
     * 重试（多数异常是瞬时的，重试即自愈）；唯独 {@link OutOfMemoryError} / {@link ThreadDeath}
     * 这类 JVM 级故障在<b>先发布死亡标志之后</b>原样抛回，绝不吞掉。</p>
     */
    private void collectSync(long taskGen) {
        // 9A-10 #1：进入前先校验代际——被 reload 替换掉的旧任务不该再读取配置、更不该写状态
        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        try {
            collectSyncOnce(taskGen);
            if (!isCurrentGeneration(taskGen)) {
                return;   // 本周期执行期间发生了 reload：本轮结果作废（状态已在 collectSyncOnce 内校验）
            }
            onCycleSuccess();
        } catch (Throwable t) {
            if (!isCurrentGeneration(taskGen)) {
                // 9A-10：旧一代的异常不写健康状态（否则会把新一代刚清零的失败计数污染）
                return;
            }
            onCycleFailure(t);
            // 9A-20 #4：JVM 级故障不吞——死亡标志已在上方发布，此处原样抛回交给服务端处置
            if (t instanceof OutOfMemoryError oom) {
                throw oom;
            }
            if (t instanceof ThreadDeath td) {
                throw td;
            }
            // 其余 Throwable：吞掉并让任务继续重排（自愈机会 > 直接放弃）
        }
    }

    /**
     * 单周期采集主体（9A-20 #4 从 collectSync 抽出，便于外层统一做异常边界）。
     * 只有走到方法末尾才算"本轮成功"——lastCollectAtMs 在 {@link #onCycleSuccess()} 里更新。
     */
    private void collectSyncOnce(long taskGen) {
        // 主线程采集耗时（v1.3.0 -04）：本方法整体跑在 global region / 服务端主线程上，
        // 耗时直接等于"插件这一拍占用了服务器多久"。此前 isStale() 只看"多久没采"、
        // 不看"采一次花多久"，采集器自己拖慢主线程时它永远显示正常。
        final long startNanos = System.nanoTime();
        tickCounter++;
        MetricsSnapshot snap = new MetricsSnapshot();
        ConfigManager.ConfigData cfg = configManager.get();

        // 轻量 Bukkit 模块：每周期采集（开销小），仍用 P0-5 的 runOrMark 包裹
        runOrMark(snap, cfg.tps_enabled, MetricsSnapshot.MODULE_TPS, () -> collectTPS(snap, cfg));
        runOrMark(snap, cfg.mspt_enabled, MetricsSnapshot.MODULE_MSPT, () -> collectMSPT(snap, cfg));
        runOrMark(snap, cfg.players_enabled, MetricsSnapshot.MODULE_PLAYERS, () -> collectPlayers(snap, cfg));

        // 重模块：分级降频，非采集周期复用缓存（见各 throttle 方法）
        // 9A-10 #2：全部采集路径只使用上面这一份 cfg（同一份快照同一代配置），
        // 绝不在采集过程中再经 configManager.get() 回读——reload 会让前后半段用上不同代的阈值。
        collectEntitiesThrottled(snap, cfg, taskGen);
        collectChunksThrottled(snap, cfg, taskGen);
        collectTileEntitiesThrottled(snap, cfg, taskGen);
        collectHotspotsThrottled(snap, cfg, taskGen);

        // 服务器信息 Bukkit 部分（世界数/插件数）：主线程采集
        safeCollect(MetricsSnapshot.MODULE_SERVER, snap, () -> collectServerInfoBukkit(snap));

        // JVM 指标：从异步缓存读取填入（内存/CPU/GC/运行时信息）
        applyJvmCache(snap, cfg);

        // 9A-10 #1：发布前最后一道校验——采集期间可能刚发生 reload（主线程虽同线程，
        // 但 reload 由命令在两次调度之间触发，且 Folia 下调度线程与命令线程可能不同）。
        // 代际已变则丢弃整张快照：不写 history、不写缓存字段、不触发历史落盘。
        if (!isCurrentGeneration(taskGen)) {
            return;
        }

        // 9A-10 #3：防御性拷贝——节流缓存没刷新时 applyXxxCache 会把**同一个 Map 引用**塞进快照，
        // 若缓存下一次刷新复用同一实例，最多 300 份历史快照会同时看到"新值"（历史被篡改）。
        // 这里把缓存容器本身在刷新时复制（见 refreshXxxCache），此处再把快照侧集合固化为不可变副本，
        // 保证任何消费方误改都只影响自己那一份（MetricsSnapshot 类本身不改）。
        freezeCollections(snap);

        // v1.3.0 -04：把本周期耗时写进快照。放在 freezeCollections 之后、入 history 之前——
        // 这样历史样本也带着耗时，趋势面板与报告能回溯"什么时候开始变慢"。
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        snap.collectDurationMs = durationMs;
        collectDurations.addLast(durationMs);
        // O(1) 计数判断：ConcurrentLinkedDeque.size() 是 O(n) 遍历，1Hz 下每秒白跑 300 个节点。
        // 与 history 环同一处教训，这次一并按计数器维护。
        collectDurationsCount++;
        while (collectDurationsCount > COLLECT_DURATION_WINDOW) {
            collectDurations.pollFirst();
            collectDurationsCount--;
        }
        // 超阈次数不在这里累加——collectDurationStats() 从窗口内现算，
        // 保证分子分母同源（见该方法的注释）。
        history.addLast(snap);
        historyCount++;
        // O(1) 计数判断；ConcurrentLinkedDeque.size() 是 O(n) 遍历，原实现每秒白跑 300 个节点。
        while (historyCount > MAX_HISTORY) {
            history.pollFirst();
            historyCount--;
        }

        // Wave 7A：历史环形缓冲挂点（契约指定：history.addLast 之后）。内部自带
        // enabled 门控 / TPS 不可用跳过 / 采样间隔节流，此处无条件调用即可
        historyStore.record(snap);
    }

    /**
     * 9A-10 #3：把快照里的可变集合替换为不可变副本。
     *
     * <p>背景：{@code MetricsSnapshot} 的 27 个字段全是 public 非 final，集合也是可变实现；
     * 采集侧的节流缓存又把<b>同一个 Map 实例</b>反复塞进不同快照。任何一处消费方误改，
     * 全史 300 条一起被污染，且无法定位。这里在快照入 history 之前统一固化：</p>
     * <ul>
     *   <li>Map → 保留插入序的 {@code unmodifiableMap(new LinkedHashMap<>(...))}；</li>
     *   <li>List（alerts / activeWarnings / activeCriticals / topEntityChunks）→ {@code List.copyOf}；</li>
     *   <li>Set（unavailable / warmingUp / disabledModules）→ {@code unmodifiableSet(LinkedHashSet)}。</li>
     * </ul>
     * <p>成本：每次采集一次浅拷贝。Map 元素为 String/Integer（不可变），List 元素为
     * {@link Alert} / {@link ChunkHotspot}——二者在构造后即只读使用，浅拷贝足够。
     * <b>刻意不用 {@code Map.copyOf}</b>：它返回的 Map 迭代序未定义，会把报告里"按世界分布"的
     * 顺序打乱；这里用 {@code LinkedHashMap} 复制再包 unmodifiable，既防篡改又保留插入序。</p>
     */
    private static void freezeCollections(MetricsSnapshot snap) {
        // 契约保障放在固化入口本身，而不是调用点：freezeCollections 是快照发布的唯一出口，
        // 把校验写在这里，"任何被发布的快照都满足契约"就由这一处定义，没有第二条路径能绕开。
        // 写在 collectSync 的调用点则相反——那一行被删掉时契约就静默失效，而测试照样全绿
        // （这是实测到的：删掉调用点后 8 个契约用例全部存活）。
        //
        // 顺序必须先修后拷：修复要改的是原始集合，拷贝之后改副本对发布出去的那一份没有意义。
        repairSubsetContracts(snap);
        snap.entitiesByType = freezeMap(snap.entitiesByType);
        snap.entitiesByWorld = freezeMap(snap.entitiesByWorld);
        snap.chunksByWorld = freezeMap(snap.chunksByWorld);
        snap.forceLoadedChunksByWorld = freezeMap(snap.forceLoadedChunksByWorld);
        snap.tileEntitiesByWorld = freezeMap(snap.tileEntitiesByWorld);
        snap.alerts = List.copyOf(snap.alerts);
        snap.activeWarnings = List.copyOf(snap.activeWarnings);
        snap.activeCriticals = List.copyOf(snap.activeCriticals);
        snap.topEntityChunks = List.copyOf(snap.topEntityChunks);
        // v1.4：alertLevels 会被 HTTP 线程读取，必须与其它集合一同固化。
        // 用 unmodifiableMap(LinkedHashMap) 而不是 Map.copyOf —— 后者迭代序未定义，
        // 会让 /metrics 的输出顺序在两次抓取间变化，diff 与 harness 断言都会变得不可靠。
        snap.alertLevels = Collections.unmodifiableMap(new LinkedHashMap<>(snap.alertLevels));
        snap.unavailable = Collections.unmodifiableSet(new LinkedHashSet<>(snap.unavailable));
        snap.warmingUp = Collections.unmodifiableSet(new LinkedHashSet<>(snap.warmingUp));
        snap.disabledModules = Collections.unmodifiableSet(new LinkedHashSet<>(snap.disabledModules));
        // 9B-3：平台限制标记同样固化（保持 platformSkipped ⊆ disabledModules ⊆ unavailable 契约）
        snap.platformSkipped = Collections.unmodifiableSet(new LinkedHashSet<>(snap.platformSkipped));
    }

    /** 9A-10 #3：保留插入序的不可变 Map 副本（元素均为不可变类型，浅拷贝即足够）。 */
    private static Map<String, Integer> freezeMap(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 子集契约违反的日志限频间隔（与 {@code safeCollect} 的模块失败日志同口径）。
     */
    private static final long SUBSET_VIOLATION_LOG_INTERVAL_MS = 5 * 60 * 1000L;

    /** 上次记录子集契约违反的时刻；0 = 尚未记录过。 */
    private static volatile long subsetViolationLoggedAtMs;

    /** 最近一次违反的详情；仅在违反时写入，告警文案用它。 */
    private static volatile String subsetViolationDetail = "";

    /**
     * 违反子集契约时的限频告警。
     *
     * <p>限频口径与 {@code safeCollect} 一致：首次立即记，之后每 5 分钟一条。契约违反是采集侧的
     * 代码 bug，理论上修一次就消失；若它每周期都发生，说明写入点又漏了一个——这时需要的是
     * "看得见"，而不是每秒一条把日志刷爆。</p>
     *
     * <p>用 {@code Logger.getLogger} 而非 {@code plugin.getLogger()}：本方法要能被静态的
     * {@link #freezeCollections} 调用，而那是契约的唯一执法点。名字与插件日志器同名，
     * 输出会进同一处控制台。</p>
     */
    private static void warnSubsetViolation() {
        long now = System.currentTimeMillis();
        long last = subsetViolationLoggedAtMs;
        if (last != 0L && now - last < SUBSET_VIOLATION_LOG_INTERVAL_MS) {
            return;
        }
        subsetViolationLoggedAtMs = now;
        java.util.logging.Logger.getLogger("PerfScope").warning("[PerfScope] "
            + subsetViolationDetail + "。这是采集侧的自身一致性 bug，已自动修正以免影响展示；"
            + "请报告给开发者");
    }

    /**
     * 校验三个"为什么不可用"集合之间的子集契约（v1.4.5），<b>违反时就地修复并告警，不抛出</b>。
     *
     * <p>契约写在 {@link MetricsSnapshot} 的字段 javadoc 里：
     * {@code platformSkipped ⊆ disabledModules ⊆ unavailable}，另有
     * {@code warmingUp ⊆ unavailable}。此前这四条<b>只有注释没有执法者</b>——五处写入点各写各的
     * {@code add}，审计用 4 种方式破坏它们、526 个用例全绿。</p>
     *
     * <p><b>后果不是崩溃而是错标</b>：{@code PerfScopeCommand} 的模块状态列按
     * {@code platformSkipped → disabledModules → warmingUp → unavailable} 顺序判定，
     * 任一子集漏标就掉进下一档——Folia 上明明该显示「平台限制（跨 region 聚合不可靠，已自动跳过）」，
     * 漏标后变成红色的「采集失败」，服主会去查日志、重装插件，而真正的原因在平台本身。</p>
     *
     * <p><b>为什么修复而不是抛异常</b>：本方法由 {@code collectSync} 在定时任务里调用，
     * 而那条定时任务<b>外层没有 try/catch</b>（见 {@code MetricsCollector.start} 的
     * {@code runGlobalTimer}）。抛出会让 {@code freezeCollections} 之后的全部逻辑跳过——
     * 不写 history、不更新缓存、不发布快照——而且每周期抛一次，把一个"显示错标"升级成
     * "采集彻底停摆"。契约违反是采集侧的自身一致性 bug，正确的处置是纠错 + 让它在日志里可见，
     * 而不是让整个监测功能陪葬。</p>
     *
     * <p>修复方向是<b>补齐父集合</b>而不是剔掉子集合的成员：子集合记录的是更精确的原因
     * （"服主关掉" / "平台限制" / "预热中"），丢掉它只会让展示层退回笼统的「不可用」。
     * 补 {@code unavailable} 还顺带修正了 40 处只看 {@code unavailable} 的消费点。</p>
     *
     * @return 是否发生过修复（调用方据此决定要不要打日志）
     */
    private static boolean repairSubsetContracts(MetricsSnapshot snap) {
        // 跑两遍：契约是可传递的（platformSkipped ⊆ disabledModules ⊆ unavailable），
        // 把 platformSkipped 的成员补进 disabledModules 之后，它们才开始参与
        // disabledModules ⊆ unavailable 的判定——单遍修不完这条链。
        // 两遍足够：第一遍建立 disabledModules 的新成员，第二遍把新成员补进 unavailable。
        // 不做 while 循环：输入只有三个集合、三条规则，两遍之后必然收敛；
        // 用循环反而会掩盖"某条规则写反了导致永不收敛"这种真 bug。
        boolean repaired = false;
        for (int pass = 0; pass < 2; pass++) {
            repaired |= requireSubset(snap.disabledModules, snap.unavailable,
                    "disabledModules", "unavailable")
                | requireSubset(snap.platformSkipped, snap.disabledModules,
                    "platformSkipped", "disabledModules")
                | requireSubset(snap.warmingUp, snap.unavailable, "warmingUp", "unavailable");
        }
        return repaired;
    }

    /**
     * {@code child ⊆ parent} 不成立时把多出的成员补进 {@code parent}。
     *
     * <p>用位或（{@code |}）而非短路或（{@code ||}）调用：三条契约必须各自独立检查完，
     * 前一条修复不得掩盖后一条的违反。</p>
     *
     * @return 是否修复过
     */
    private static boolean requireSubset(Set<String> child, Set<String> parent,
                                         String childName, String parentName) {
        if (child == null || parent == null || parent.containsAll(child)) {
            return false;
        }
        Set<String> extra = new LinkedHashSet<>(child);
        extra.removeAll(parent);
        parent.addAll(extra);
        subsetViolationDetail = "MetricsSnapshot 子集契约 " + childName + " ⊆ " + parentName
            + " 被违反：多出 " + extra + "（已补进 " + parentName + "）";
        warnSubsetViolation();
        return true;
    }

    /**
     * 9A-10 #3：快照侧的防御性拷贝——<b>每次调用都是一个全新可变 Map</b>。
     *
     * <p>与 {@link #freezeMap} 的分工：{@code freezeMap} 用于"缓存/已发布快照"（不可变，
     * 防止误改）；本方法用于"把缓存填进正在构建的快照"（可变副本，且与缓存不共享结构）。
     * 两者都保留 LinkedHashMap 插入序（报告按世界分布的输出顺序依赖它）。</p>
     */
    private static Map<String, Integer> copyMap(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(source);
    }

    // ==================== 9A-20：周期健康状态机 ====================

    /** 本轮采集成功：刷新 lastCollectAtMs，清零失败计数；若上一轮处于失败/死亡态则补一条恢复 INFO。 */
    private void onCycleSuccess() {
        lastCollectAtMs = System.currentTimeMillis();
        boolean wasDead = collectorDead;
        int failedBefore = consecutiveCollectFailures;
        if (failedBefore > 0 || wasDead) {
            long firstAt = firstFailureAtMs;
            consecutiveCollectFailures = 0;
            firstFailureAtMs = 0L;
            lastCollectError = null;
            collectorDead = false;
            // 两种完整句式（带/不带"首次失败"）由消息层各自成句——片段拼接在英文里语序会散架
            plugin.getLogger().info(firstAt > 0
                ? Messages.get("run.collector.cycle-recovered",
                    failedBefore, formatClock(firstAt), formatClock(lastCollectAtMs))
                : Messages.get("run.collector.cycle-recovered-brief",
                    failedBefore, formatClock(lastCollectAtMs)));
        } else {
            collectorDead = false;
        }
        publishNetworkSummary();
    }

    /**
     * v1.5：把本轮快照摘要推给代理（若启用）。
     *
     * <p><b>为什么挂在采集周期末尾而不是自己开一个定时器</b>：两个理由。一是不引入第二个时钟——
     * 一旦上报有自己的调度，就会出现"采集已经停了但上报还在按旧快照发"的窗口，
     * 代理侧看到的是活的后端和死的数字，正是本项目反复修的那类静默失真。二是失败隔离天然成立：
     * 这里在任何异常上都只吞掉，不影响采集（采集是这个插件的主功能，跨服只是附加视图）。</p>
     *
     * <p>必须在主线程调用——{@code sendPluginMessage} 要求如此，而 {@code onCycleSuccess}
     * 由全局区域定时器触发，满足条件。</p>
     */
    private void publishNetworkSummary() {
        cn.perfscope.network.SummaryPublisher publisher = plugin.getSummaryPublisher();
        if (publisher == null) {
            return;
        }
        try {
            MetricsSnapshot snap = getLatest();
            if (snap != null) {
                publisher.publish(snap, System.currentTimeMillis());
            }
        } catch (Exception | LinkageError e) {
            // 跨服链路坏掉绝不能影响本地采集。限频：登录/断连抖动时可能每周期都失败。
            long now = System.currentTimeMillis();
            if (now - lastNetworkFailureLogAtMs > NETWORK_FAILURE_LOG_INTERVAL_MS) {
                lastNetworkFailureLogAtMs = now;
                plugin.getLogger().warning("[PerfScope] 跨服摘要上报异常（已忽略，不影响采集）："
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * 本轮采集抛出未捕获异常：累加失败计数、发布死亡标志、限频记日志。
     * 连续失败达 {@link #DEAD_AFTER_CONSECUTIVE_FAILURES} 次打 SEVERE 并提示人工介入
     * （"/perfscope reload 或重启服务器"）；日志本身 5 分钟一条，避免 1 秒一周期的刷屏。
     */
    private void onCycleFailure(Throwable t) {
        long now = System.currentTimeMillis();
        int failures = consecutiveCollectFailures + 1;
        consecutiveCollectFailures = failures;
        if (firstFailureAtMs == 0L) {
            firstFailureAtMs = now;
        }
        collectorDead = true;
        lastCollectError = describeThrowable(t);

        boolean severe = failures == DEAD_AFTER_CONSECUTIVE_FAILURES;
        boolean logNow = failures == 1
            || severe
            || now - lastCycleFailureLogAtMs >= CYCLE_FAILURE_LOG_INTERVAL_MS;
        if (logNow) {
            lastCycleFailureLogAtMs = now;
            String detail = Messages.get("run.collector.cycle-failed",
                failures, formatClock(firstFailureAtMs), lastCollectError);
            if (configManager.get() != null && configManager.get().general_debug) {
                plugin.getLogger().log(Level.SEVERE,
                    Messages.get("run.collector.cycle-failed-hint", detail), t);
            } else {
                plugin.getLogger().severe(detail);
            }
        }
        if (severe) {
            plugin.getLogger().severe(Messages.get("run.collector.dead", failures));
        }
        // 失败周期也要上报跨服摘要。
        //
        // 只在成功路径上报会导致 alive 恒为 true（成功路径刚刷新过 lastCollectAtMs），
        // 于是代理侧的 COLLECTOR_DEAD 分支永远走不到：采集真的坏了，面板上显示的是
        // "数据过期（可能空服）"，把人指向空服/网络这个错误方向。
        // 这里上报的是上一张快照 + alive=false，让代理能明确说"采集已停"。
        publishNetworkSummary();
    }

    /**
     * 异步 JVM 节拍（P0-1）：采集纯 JVM/MXBean 指标（内存/CPU/GC/运行时信息），整体发布到 volatile 缓存
     * 供主线程 collectSync 读取填入快照。仍用 runOrMark/safeCollect 包裹，保持 P0-5 异常隔离与 unavailable 语义。
     *
     * <p><b>9A-20 顺手项</b>：与 collectSync 同款任务边界——旧实现无 try，异步 repeating task 一旦抛
     * 未捕获异常同样会被静默取消，内存/CPU/GC 会永远停在"预热中→不可用"，而 status 只能看到症状看不到原因。</p>
     */
    private void collectJvmAsync(long taskGen) {
        // 9A-10 #1：进入前校验代际（异步线程读 volatile generation）
        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        try {
            collectJvmAsyncOnce(taskGen);
        } catch (Throwable t) {
            if (!isCurrentGeneration(taskGen)) {
                return;   // 9A-10：旧一代异常不写日志/不污染限频基准
            }
            if (t instanceof OutOfMemoryError oom) {
                throw oom;
            }
            if (t instanceof ThreadDeath td) {
                throw td;
            }
            long now = System.currentTimeMillis();
            if (now - lastJvmCycleFailureLogAtMs >= CYCLE_FAILURE_LOG_INTERVAL_MS) {
                lastJvmCycleFailureLogAtMs = now;
                plugin.getLogger().warning(Messages.get("run.collector.jvm-cycle-failed",
                    describeThrowable(t)));
            }
        }
    }

    private void collectJvmAsyncOnce(long taskGen) {
        MetricsSnapshot jvmSnap = new MetricsSnapshot();
        ConfigManager.ConfigData cfg = configManager.get();

        runOrMark(jvmSnap, cfg.memory_enabled, MetricsSnapshot.MODULE_MEMORY, () -> collectMemory(jvmSnap, cfg));
        runOrMark(jvmSnap, cfg.cpu_enabled, MetricsSnapshot.MODULE_CPU, () -> collectCPU(jvmSnap, cfg));
        safeCollect(MetricsSnapshot.MODULE_GC, jvmSnap, () -> collectGC(jvmSnap));
        safeCollect(MetricsSnapshot.MODULE_SERVER, HEALTH_KEY_SERVER_JVM, jvmSnap,
            () -> collectServerInfoJvm(jvmSnap));

        // 9A-10 #3：JVM 侧集合同样固化（它会经 applyJvmCache 被并入主线程快照）
        freezeCollections(jvmSnap);

        // 9A-10 #1：**发布点**校验——这是本项最核心的一处。旧实现 reload 后旧任务跑完仍执行
        // `jvmCache = jvmSnap`，把上一代配置算出的内存/CPU/GC 写进新一代码，且随后被 forever
        // 复制进 history（直到新异步任务首次成功才覆盖）。代际不符则整轮丢弃。
        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        jvmCache = jvmSnap; // volatile 整体发布，主线程读到的始终是某一轮的完整数据，无半成品
        // 9A-20 顺手项：JVM 异步节拍自己的"最后成功时刻"。没有它，异步任务被静默取消后
        // 内存/CPU/GC 会永远显示最后一次的数值（主线程仍每秒产快照，所以 isStale() 看不出来）。
        lastJvmCollectAtMs = System.currentTimeMillis();
    }

    /**
     * JVM 异步采集是否已过期（阈值与 {@link #staleThresholdMs()} 同口径）。
     *
     * <p>9A-20：主线程节拍正常（{@link #isStale()} 为 false）并不代表 JVM 指标是新的——
     * 两条节拍是独立任务，异步那条被静默取消时，内存/CPU/GC 会冻结在最后一次的值上。
     * 展示层应单独检查本方法；配套的"数据是哪一刻的"由 {@link #jvmSnapshotTimestamp()} 提供。</p>
     */
    public boolean isJvmStale() {
        long last = lastJvmCollectAtMs;
        long ref = last > 0L ? last : startedAtMs;
        return System.currentTimeMillis() - ref > Math.max(WARMUP_WINDOW_MS, staleThresholdMs());
    }

    /** 最新 JVM 快照的采集时刻（0 = 从未）；供 status/看门狗展示"内存/CPU/GC 的数据是哪一刻的"。 */
    public long jvmSnapshotTimestamp() {
        MetricsSnapshot jvm = jvmCache;
        return jvm == null ? 0L : jvm.timestamp;
    }

    /** 9A-20：JVM 异步周期失败日志限频时刻（0 = 从未）。 */
    private volatile long lastJvmCycleFailureLogAtMs = 0L;

    // ==================== 9A-10 #2：单份配置快照辅助 ====================

    /**
     * 从传入的 {@link ConfigManager.ConfigData} 读取某世界的覆盖设置。
     *
     * <p><b>为何不直接用 {@code configManager.getWorldSettings(name)}</b>：那个方法内部会读
     * {@code configManager} 的<b>当前</b>配置引用。一次采集从 {@code configManager.get()} 取到 cfg 之后，
     * 若中途发生 reload（命令线程 / Folia 另一 region 线程），后半段再回读就会拿到下一代配置——
     * 同一份快照前半段旧阈值、后半段新阈值。本方法只读传入 cfg，保证一份快照与一份配置绑定。
     * （ConfigManager 属他人文件，按分工不改；等价重载在此私有实现。）</p>
     *
     * @return 该世界的覆盖设置；未配置覆盖或 cfg/世界表为空时返回 {@code null}（调用方按"无覆盖"处理）
     */
    private static ConfigManager.WorldSettings worldSettingsFrom(ConfigManager.ConfigData cfg, String worldName) {
        if (cfg == null || cfg.worldSettings == null || worldName == null) {
            return null;
        }
        return cfg.worldSettings.get(worldName);
    }

    // ==================== P0-1 重模块分级降频（节流缓存，仅主线程） ====================

    /**
     * 9B-3：当前服务端上"全服聚合遍历"整体是否不可信（等价于 {@link ServerCompat#isFolia()}）。
     *
     * <p>本方法只为让调用点语义自解释而存在：判定<b>唯一来源</b>是 {@link ServerCompat}，
     * 本类不重复任何 Folia 反射探测（规格 9B-3 第 3 条）。</p>
     */
    private boolean foliaAggregationUnsafe() {
        return ServerCompat.isFolia();
    }

    /**
     * 9B-3：该模块在 Folia 上是否属于"跨 region 聚合"从而必须跳过。
     *
     * <p>两个条件同时成立才为 true：服务端是 Folia（{@link #foliaAggregationUnsafe()}）
     * <b>且</b>该模块的数据源需要跨区域遍历（{@link #FOLIA_CROSS_REGION_MODULES}）。
     * 非 Folia 上恒为 false——因此 Paper 路径不会经过任何新增分支（零行为变化）。</p>
     */
    private boolean foliaAggregationUnsafe(String module) {
        return foliaAggregationUnsafe() && FOLIA_CROSS_REGION_MODULES.contains(module);
    }

    /**
     * 9B-3：Folia 平台跳过提示——<b>每个插件生命周期只打一条 INFO</b>。
     *
     * <p>由 {@code foliaSkipLogged} 保证幂等；该标记<b>刻意不随 start()/reload 重置</b>：
     * 服主反复 /perfscope reload 时不需要重复读同一段解释（reload 不会改变"这是 Folia"的事实）。</p>
     */
    private void logFoliaAggregationSkipOnce() {
        if (foliaSkipLogged) {
            return;
        }
        foliaSkipLogged = true;
        plugin.getLogger().info(Messages.get("run.collector.folia-skip"));
    }

    /** 实体采集（分级降频）：每 ENTITY_SAMPLE_EVERY 个周期遍历一次世界实体并刷新缓存，其余周期复用缓存值填入快照。 */
    private void collectEntitiesThrottled(MetricsSnapshot snap, ConfigManager.ConfigData cfg, long taskGen) {
        if (!entitiesCacheReady || tickCounter % ENTITY_SAMPLE_EVERY == 0) {
            refreshEntitiesCache(cfg, taskGen);
        }
        applyEntitiesCache(snap);
    }

    /**
     * 刷新实体缓存。
     *
     * <p><b>9A-10 #1</b>：缓存本身就是"跨周期状态"，下发它们同样是一次<b>状态发布</b>——
     * 旧一代任务若在 reload 之后把旧配置算出的缓存写回 {@code entitiesCacheReady/cachedXxx}，
     * 新一代首周期会直接复用这批旧值（直到命中下一次采样倍数）。故入口与发布点各校验一次代际。</p>
     */
    private void refreshEntitiesCache(ConfigManager.ConfigData cfg, long taskGen) {
        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        boolean ready = true;
        boolean unavailable;
        int total = 0;
        Map<String, Integer> byType = Map.of();
        Map<String, Integer> byWorld = Map.of();
        List<Alert> alertList = List.of();
        List<String> warningList = List.of();
        List<String> criticalList = List.of();
        Map<String, Integer> levelList = Map.of();
        boolean disabled = false;
        boolean platformSkipped = false;   // 9B-3

        if (!cfg.entities_enabled) {   // 配置禁用：与 runOrMark 语义一致，标记不可用
            unavailable = true;
            disabled = true;   // 9A-20 #3：同时记入 disabledModules（区分"服主关掉"与"采集坏了"）
        } else if (foliaAggregationUnsafe(MetricsSnapshot.MODULE_ENTITIES)) {
            // 9B-3：Folia 上 world.getEntities() 是跨 region 聚合——不保证跨 region 一致，
            // 也可能抛 IllegalStateException。保守策略（A）：整块跳过，如实标注平台限制，
            // 绝不用"只覆盖 part 个 region 的数字"冒充全服实体数（那是假数据，比没有更糟）。
            logFoliaAggregationSkipOnce();
            unavailable = true;
            disabled = true;          // 规格 9B-3 第 2 条 A：同时标 disabledModules
            platformSkipped = true;   // 更精确的原因标记（disabledModules 的子集），供展示层区分
        } else {
            // 临时快照承接本次采集，隔离出本模块产生的告警，便于缓存后在复用周期一并还原
            MetricsSnapshot tmp = new MetricsSnapshot();
            safeCollect(MetricsSnapshot.MODULE_ENTITIES, tmp, () -> collectEntities(tmp, cfg));
            unavailable = tmp.unavailable.contains(MetricsSnapshot.MODULE_ENTITIES);
            if (!unavailable) {
                total = tmp.totalEntities;
                // 9A-10 #3：缓存侧存不可变副本（tmp 不再被任何地方持有，但保持"缓存只读"的不变量）
                byType = freezeMap(tmp.entitiesByType);
                byWorld = freezeMap(tmp.entitiesByWorld);
                alertList = List.copyOf(tmp.alerts);
                warningList = List.copyOf(tmp.activeWarnings);
                criticalList = List.copyOf(tmp.activeCriticals);
                levelList = freezeMap(new LinkedHashMap<>(tmp.alertLevels));
            }
        }

        // 9A-10 #1：发布点再校验——采集过程中可能已发生 reload，此时上述结果属于上一代配置
        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        entitiesCacheReady = ready;
        entitiesCacheUnavailable = unavailable;
        entitiesCacheDisabled = disabled;
        entitiesCachePlatformSkipped = platformSkipped;
        cachedTotalEntities = total;
        cachedEntitiesByType = byType;
        cachedEntitiesByWorld = byWorld;
        cachedEntityAlerts = alertList;
        cachedEntityWarnings = warningList;
        cachedEntityCriticals = criticalList;
        cachedEntityLevels = new LinkedHashMap<>(levelList);
    }

    /**
     * 四个"重模块"（entities / chunks / tile_entities / hotspots）缓存回放时的不可用标记。
     *
     * <p><b>为什么收成一个方法</b>：这四个模块的缓存回放逻辑此前是四份逐字相同的 10 行块，
     * 唯一的差别是模块名与三个布尔量。四份手写的代价不是"重复不好看"，而是
     * <b>漏一个标记不会有任何提示</b>——它只会让 {@code freezeCollections} 的
     * {@code repairSubsetContracts} 在事后补上，而"事后补救"意味着<b>数据已经错了一轮</b>
     * 才被纠正，且只在日志里留一行限频 WARNING。</p>
     *
     * <p>三个标记的语义与契约（见 {@link MetricsSnapshot} 的字段 javadoc）：</p>
     * <ul>
     *   <li>{@code unavailable} —— 必备。模块的数据这一轮不可信，收集这一份就是它的全部含义；</li>
     *   <li>{@code disabledModules} —— 服主显式 {@code enabled: false}。<b>必须同时进 unavailable</b>；</li>
     *   <li>{@code platformSkipped} —— 平台限制（Folia 跨 region 聚合不可靠）。
     *       <b>必须同时进 disabledModules 与 unavailable</b>，即
     *       {@code platformSkipped ⊆ disabledModules ⊆ unavailable}。</li>
     * </ul>
     *
     * <p>收成一个方法后，"写 platformSkipped 却忘了写 disabledModules"这类错在结构上
     * 不再可能——三个集合在同一个方法体里，一次写完。</p>
     *
     * @param module          模块名（{@link MetricsSnapshot#MODULE_ENTITIES} 等）
     * @param unavailable     本轮该模块是否不可用（缓存未就绪 或 采集失败/被禁用）
     * @param disabled        是否因配置 {@code enabled: false} 而不可用
     * @param platformSkipped 是否因平台限制（Folia）而不可用
     */
    private static void markDedicatedModuleUnavailable(MetricsSnapshot snap, String module,
                                                       boolean unavailable, boolean disabled,
                                                       boolean platformSkipped) {
        if (!unavailable) {
            return;
        }
        snap.unavailable.add(module);
        if (disabled) {
            snap.disabledModules.add(module);
        }
        if (platformSkipped) {
            // 契约要求 platformSkipped ⊆ disabledModules：平台限制同时是"服主没配过但平台不让采"，
            // 展示层据此渲染更精确的原因而不是笼统的「已禁用」。
            snap.disabledModules.add(module);
            snap.platformSkipped.add(module);   // 9B-3
        }
    }

    private void applyEntitiesCache(MetricsSnapshot snap) {
        if (!entitiesCacheReady || entitiesCacheUnavailable) {
            markDedicatedModuleUnavailable(snap, MetricsSnapshot.MODULE_ENTITIES,
                true, entitiesCacheDisabled, entitiesCachePlatformSkipped);
            return;
        }
        snap.totalEntities = cachedTotalEntities;
        // 9A-10 #3：防御性拷贝——非采样周期这里会连续复用同一份缓存，若直接赋值，
        // 最多 300 份历史快照将共享同一个 Map 实例，一次误改全史污染。
        snap.entitiesByType = copyMap(cachedEntitiesByType);
        snap.entitiesByWorld = copyMap(cachedEntitiesByWorld);
        snap.alerts.addAll(cachedEntityAlerts);
        snap.activeWarnings.addAll(cachedEntityWarnings);
        snap.activeCriticals.addAll(cachedEntityCriticals);
        snap.alertLevels.putAll(cachedEntityLevels);
    }

    /** 区块采集（分级降频）：每 CHUNK_SAMPLE_EVERY 个周期遍历一次已加载区块并刷新缓存，其余周期复用缓存值填入快照。 */
    private void collectChunksThrottled(MetricsSnapshot snap, ConfigManager.ConfigData cfg, long taskGen) {
        if (!chunksCacheReady || tickCounter % CHUNK_SAMPLE_EVERY == 0) {
            refreshChunksCache(cfg, taskGen);
        }
        applyChunksCache(snap);
    }

    /** 刷新区块缓存（9A-10 #1：入口 + 发布点各校验一次代际，见 refreshEntitiesCache 注释）。 */
    private void refreshChunksCache(ConfigManager.ConfigData cfg, long taskGen) {
        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        boolean unavailable;
        int totalLoaded = 0;
        int totalForceLoaded = 0;
        Map<String, Integer> byWorld = Map.of();
        Map<String, Integer> forceByWorld = Map.of();
        List<Alert> alertList = List.of();
        List<String> warningList = List.of();
        List<String> criticalList = List.of();
        Map<String, Integer> levelList = Map.of();
        boolean disabled = false;
        boolean platformSkipped = false;   // 9B-3

        if (!cfg.chunks_enabled) {
            unavailable = true;
            disabled = true;   // 9A-20 #3
        } else if (foliaAggregationUnsafe(MetricsSnapshot.MODULE_CHUNKS)) {
            // 9B-3：Folia 上 getLoadedChunks() 只反映调用线程可见的 region 集合（且 chunk.isForceLoaded()
            // 的语义在各 region 间也不保证一致）→ 整块跳过，不用局部数字冒充全服区块数。
            logFoliaAggregationSkipOnce();
            unavailable = true;
            disabled = true;
            platformSkipped = true;
        } else {
            MetricsSnapshot tmp = new MetricsSnapshot();
            safeCollect(MetricsSnapshot.MODULE_CHUNKS, tmp, () -> collectChunks(tmp, cfg));
            unavailable = tmp.unavailable.contains(MetricsSnapshot.MODULE_CHUNKS);
            if (!unavailable) {
                totalLoaded = tmp.totalLoadedChunks;
                totalForceLoaded = tmp.totalForceLoadedChunks;
                byWorld = freezeMap(tmp.chunksByWorld);                     // 9A-10 #3
                forceByWorld = freezeMap(tmp.forceLoadedChunksByWorld);     // 9A-10 #3
                alertList = List.copyOf(tmp.alerts);                        // 9A-10 #3
                warningList = List.copyOf(tmp.activeWarnings);              // 9A-10 #3
                criticalList = List.copyOf(tmp.activeCriticals);            // 9A-10 #3
                levelList = freezeMap(new LinkedHashMap<>(tmp.alertLevels)); // v1.4
            }
        }

        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        chunksCacheReady = true;
        chunksCacheUnavailable = unavailable;
        chunksCacheDisabled = disabled;
        chunksCachePlatformSkipped = platformSkipped;
        cachedTotalLoadedChunks = totalLoaded;
        cachedTotalForceLoadedChunks = totalForceLoaded;
        cachedChunksByWorld = byWorld;
        cachedForceLoadedChunksByWorld = forceByWorld;
        cachedChunkAlerts = alertList;
        cachedChunkWarnings = warningList;
        cachedChunkCriticals = criticalList;
        cachedChunkLevels = new LinkedHashMap<>(levelList);   // v1.4
    }

    private void applyChunksCache(MetricsSnapshot snap) {
        if (!chunksCacheReady || chunksCacheUnavailable) {
            markDedicatedModuleUnavailable(snap, MetricsSnapshot.MODULE_CHUNKS,
                true, chunksCacheDisabled, chunksCachePlatformSkipped);
            return;
        }
        snap.totalLoadedChunks = cachedTotalLoadedChunks;
        snap.totalForceLoadedChunks = cachedTotalForceLoadedChunks;
        snap.chunksByWorld = copyMap(cachedChunksByWorld);                               // 9A-10 #3
        snap.forceLoadedChunksByWorld = copyMap(cachedForceLoadedChunksByWorld);          // 9A-10 #3
        snap.alerts.addAll(cachedChunkAlerts);
        snap.activeWarnings.addAll(cachedChunkWarnings);
        snap.activeCriticals.addAll(cachedChunkCriticals);
        snap.alertLevels.putAll(cachedChunkLevels);           // v1.4
    }

    /** 方块实体采集（分级降频）：每 TE_SAMPLE_EVERY 个周期遍历一次并刷新缓存，其余周期复用缓存值填入快照。 */
    private void collectTileEntitiesThrottled(MetricsSnapshot snap, ConfigManager.ConfigData cfg, long taskGen) {
        if (!teCacheReady || tickCounter % TE_SAMPLE_EVERY == 0) {
            refreshTileEntitiesCache(cfg, taskGen);
        }
        applyTileEntitiesCache(snap);
    }

    /** 刷新方块实体缓存（9A-10 #1：入口 + 发布点各校验一次代际）。 */
    private void refreshTileEntitiesCache(ConfigManager.ConfigData cfg, long taskGen) {
        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        boolean unavailable;
        int count = 0;
        Map<String, Integer> byWorld = Map.of();
        boolean disabled = false;
        boolean platformSkipped = false;   // 9B-3

        if (!cfg.tileEntities_enabled) {
            unavailable = true;
            disabled = true;   // 9A-20 #3
        } else if (foliaAggregationUnsafe(MetricsSnapshot.MODULE_TILE_ENTITIES)) {
            // 9B-3：Paper 路径用 World.getTileEntityCount()（内部要与各 region 的区块状态对账）；
            // Bukkit 降级路径用 Chunk.getTileEntities()——两者在 Folia 上都是跨 region 聚合，
            // 故 Tier-1 与 Tier-2 一并跳过，不做"降级后就安全了"的假设。
            logFoliaAggregationSkipOnce();
            unavailable = true;
            disabled = true;
            platformSkipped = true;
        } else {
            MetricsSnapshot tmp = new MetricsSnapshot();
            safeCollect(MetricsSnapshot.MODULE_TILE_ENTITIES, tmp, () -> collectTileEntities(tmp));
            unavailable = tmp.unavailable.contains(MetricsSnapshot.MODULE_TILE_ENTITIES);
            if (!unavailable) {
                count = tmp.tileEntityCount;
                byWorld = freezeMap(tmp.tileEntitiesByWorld);   // 9A-10 #3
            }
        }

        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        teCacheReady = true;
        teCacheUnavailable = unavailable;
        teCacheDisabled = disabled;
        teCachePlatformSkipped = platformSkipped;
        cachedTileEntityCount = count;
        cachedTileEntitiesByWorld = byWorld;
    }

    private void applyTileEntitiesCache(MetricsSnapshot snap) {
        if (!teCacheReady || teCacheUnavailable) {
            markDedicatedModuleUnavailable(snap, MetricsSnapshot.MODULE_TILE_ENTITIES,
                true, teCacheDisabled, teCachePlatformSkipped);
            return;
        }
        snap.tileEntityCount = cachedTileEntityCount;
        snap.tileEntitiesByWorld = copyMap(cachedTileEntitiesByWorld);   // 9A-10 #3
    }

    // ==================== Wave 8C 热点区块（Top5 实体密度，含玩家） ====================

    /**
     * 热点区块节流入口（Wave 8C / v1.4 分区增量）。
     *
     * <p><b>v1.4 改了什么</b>：旧实现每 {@code sample-every-cycles} 个周期做一次
     * 「全部世界 × 全部已加载区块」的全遍历，实测 11,048 区块 / 13,466 实体时单次 <b>40.5 ms</b>、
     * 50,000 实体时 <b>106 ms</b>（超 2 tick）。现在把同一份活切成 {@code hotspots.scan-shards}
     * 片，按「一轮总周期 ≥ {@code sample-every-cycles}」的节奏推进，单拍成本降到约 1/N。</p>
     *
     * <p><b>节拍由 {@link #hotspotNextScanTick} 显式排定，不用 {@code tickCounter % step}</b>：
     * 推进间隔必须按<b>本轮实际分片数</b>算（区块少的服上实际分片数会小于配置值，见
     * {@link HotspotShardSweep}），而 {@code %} 取模只能用固定步长。用固定步长会出现
     * 「区块数 4、配置分片 10」这种组合：实际只切 4 片却每 3 周期扫一片，一轮 12 周期扫完
     * 4 个区块——单位时间的工作量反而比旧的「每 30 周期扫 4 个」高 2.5 倍。显式排程没有这个坑。</p>
     */
    private void collectHotspotsThrottled(MetricsSnapshot snap, ConfigManager.ConfigData cfg, long taskGen) {
        // 首周期立即开扫：改前是"启动后等 30 秒才有热点数据"，若改后还要先空转一轮会更糟
        if (!hotspotsCacheReady || tickCounter >= hotspotNextScanTick) {
            tickHotspots(cfg, taskGen);
        }
        applyHotspotsCache(snap);
    }

    /**
     * 推进热点扫描一拍：维护「当前轮」的状态，扫一片，必要时发布，并排定下一拍。
     *
     * <p><b>发布策略（这是分区方案唯一改变用户可见行为的地方）</b>：</p>
     * <ul>
     *   <li><b>首轮</b>（还没有任何完整结果）：每扫完一片就发布当前候选的 Top-N。原因是空态文案
     *       「暂无热点数据」如果持续满一轮（默认 30 秒），用户会以为模块坏了。而且候选集是
     *       <b>累积</b>的，部分结果的 Top-N 只会随扫描推进而"变得更好"，不会把已经找到的热点丢掉
     *       ——所以首轮的部分发布是单调收敛的，不是随机跳变。</li>
     *   <li><b>首轮之后</b>：只在整轮扫完时整体替换。中间周期复用上一轮的<b>完整</b>结果。
     *       若每拍都发布部分结果，Top-5 会在轮次边界剧烈跳变（轮次开头只扫了 1/N 区块，
     *       Top-5 会突然退化成"第一片里最好的 5 个"，然后随着扫描推进再换回来）——
     *       用户看到的是一份来回抖的榜单，比"晚 30 秒更新"糟得多。</li>
     * </ul>
     */
    private void tickHotspots(ConfigManager.ConfigData cfg, long taskGen) {
        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        if (!cfg.hotspots_enabled) {
            dropoutHotspotSweep();
            hotspotNextScanTick = tickCounter + Math.max(1, cfg.hotspots_sampleEveryCycles);
            publishHotspots(true, true, false, List.of(), taskGen);
            return;
        }
        if (foliaAggregationUnsafe(MetricsSnapshot.MODULE_HOTSPOTS)) {
            // 9B-3：热点扫描是"全部世界 × 全部已加载区块 × chunk.getEntities()"的最重活，
            // 在 Folia 上既不可信（区块实体按 region 归属）又最容易踩跨 region IllegalStateException
            // → 整块跳过。v1.4 的分片只在 Paper 路径内生效，此分支保持不变。
            logFoliaAggregationSkipOnce();
            dropoutHotspotSweep();
            hotspotNextScanTick = tickCounter + Math.max(1, cfg.hotspots_sampleEveryCycles);
            publishHotspots(true, true, true, List.of(), taskGen);
            return;
        }

        if (hotspotSweep == null) {
            beginHotspotSweep(cfg);
        }
        // 排下一拍：用本轮**实际**分片数算间隔，保证「分片数 × 间隔 ≥ sample-every-cycles」，
        // 即改造后单位时间扫过的区块数恒不超过改造前（见方法注释里的 4 区块反例）。
        int step = HotspotShardSweep.stepInterval(
            cfg.hotspots_sampleEveryCycles, hotspotSweep.shardCount());
        hotspotNextScanTick = tickCounter + step;

        MetricsSnapshot tmp = new MetricsSnapshot();
        // 用单元素数组把结果带出 lambda（局部变量在 lambda 内必须 effectively final）
        List<ChunkHotspot>[] scanned = new List[1];
        safeCollect(MetricsSnapshot.MODULE_HOTSPOTS, tmp, () -> scanned[0] = scanHotspotShard());
        if (tmp.unavailable.contains(MetricsSnapshot.MODULE_HOTSPOTS)) {
            // 本片失败：整轮作废（否则候选集里会留下一份"扫了一半且不知道还缺什么"的数据，
            // 下一轮发布出残缺的 Top-5），下个周期从头重开。本周期如实标不可用。
            dropoutHotspotSweep();
            publishHotspots(true, false, false, cachedHotspots, taskGen);
            return;
        }

        List<ChunkHotspot> complete = hotspotSweep.accept(scanned[0]);
        if (complete != null) {
            hotspotSweep = null;   // 本轮结束：下一拍自动开新的一轮（滚动扫描）
            hotspotCompletedRounds++;
            publishHotspots(false, false, false, complete, taskGen);
        } else if (hotspotCompletedRounds == 0) {
            publishHotspots(false, false, false, hotspotSweep.partialTopN(), taskGen);
        }
        // 其余情况（首轮已完成、本轮未完成）：不动缓存 → applyHotspotsCache 复用上一轮完整结果
    }

    /** 丢弃进行中的一轮（禁用 / 平台跳过 / 本片异常时调用），下一拍重新开始。 */
    private void dropoutHotspotSweep() {
        hotspotSweep = null;
        sweepWorlds = NO_WORLDS;
        sweepChunkX = EMPTY_COORDS;
        sweepChunkZ = EMPTY_COORDS;
        sweepChunkWorld = EMPTY_COORDS;
    }

    /**
     * 开始新的一轮：把「各世界的已加载区块」快照成一个线性序列。
     *
     * <p><b>为什么快照坐标而不是快照 {@code Chunk} 对象</b>：{@code Chunk} 句柄持有底层
     * {@code LevelChunk} 引用，跨 30 个周期都攥着就等于把这一轮开始时加载过的区块全部钉住内存，
     * 期间被卸载的区块也释放不掉。快照成三个 {@code int[]}（世界下标 / chunkX / chunkZ，
     * 11k 区块共约 132 KB）则完全不阻止卸载，扫描时逐项 {@code isChunkLoaded} 校验即可。</p>
     *
     * <p>一轮之内可见的区块集合是固定的：期间新加载的区块不会中途挤进来，下一轮才会纳入。
     * 这是刻意的一致性问题——边扫边加入会让"总区块数"在轮次中途变化，分片区间就不再对齐。</p>
     */
    private void beginHotspotSweep(ConfigManager.ConfigData cfg) {
        World[] worlds = Bukkit.getWorlds().toArray(new World[0]);
        Chunk[][] perWorld = new Chunk[worlds.length][];
        int total = 0;
        for (int i = 0; i < worlds.length; i++) {
            perWorld[i] = worlds[i].getLoadedChunks();
            total += perWorld[i].length;
        }
        sweepWorlds = worlds;
        sweepChunkX = new int[total];
        sweepChunkZ = new int[total];
        sweepChunkWorld = new int[total];
        int k = 0;
        for (int w = 0; w < worlds.length; w++) {
            for (Chunk chunk : perWorld[w]) {
                sweepChunkX[k] = chunk.getX();
                sweepChunkZ[k] = chunk.getZ();
                sweepChunkWorld[k] = w;
                k++;
            }
        }
        hotspotSweep = new HotspotShardSweep(total, cfg.hotspots_scanShards);
    }

    /**
     * 扫描当前分片，返回本片发现的非空区块。
     *
     * <p>统计口径与改造前<b>逐字一致</b>（V1.2 §5）：{@code entityCount = chunk.getEntities().length}
     * （含玩家），{@code dominantType} 取类型众数、平票取字典序最小者，空区块不进候选。
     * 唯一的实现变化是 {@code byType} 这个 HashMap 在分片内<b>复用</b>（每区块 clear 一次），
     * 不再为每个区块新建一个——旧实现的 3.6 MB 分配里有相当一部分来自这里。</p>
     */
    private List<ChunkHotspot> scanHotspotShard() {
        int from = hotspotSweep.rangeStart();
        int to = hotspotSweep.rangeEnd();
        List<ChunkHotspot> out = new ArrayList<>(Math.max(0, to - from) / 2 + 1);
        Map<String, Integer> byType = new HashMap<>();
        for (int i = from; i < to; i++) {
            World world = sweepWorlds[sweepChunkWorld[i]];
            int cx = sweepChunkX[i];
            int cz = sweepChunkZ[i];
            // 分片之间区块可能被卸载（一轮横跨多个采集周期）：跳过即可，不是错误。
            // 用 isChunkLoaded(x,z) 而不是 getChunkAt(x,z) 兜底——后者会把区块重新加载进来，
            // 一个"扫描"动作绝不该改世界的加载状态。
            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }
            Chunk chunk = world.getChunkAt(cx, cz);
            Entity[] entities = chunk.getEntities(); // 单次取得，长度即计数（含玩家）
            if (entities.length == 0) {
                continue; // 空区块不进候选
            }
            // 主导类型：单遍统计（实体数通常很小，HashMap 开销可忽略）
            byType.clear();
            int nullSkipped = 0;
            for (Entity entity : entities) {
                // 9B-4：chunk.getEntities() 在实体被并发移除时可能返回含 null 元素的数组
                // （Paper 的 ChunkEntitySlices 非完全快照）。直接 getName() 会 NPE，
                // 一次 NPE 会让本次热点采集整块失败（safeCollect 标 unavailable）。
                if (entity == null) {
                    nullSkipped++;
                    continue;
                }
                byType.merge(entity.getType().name(), 1, Integer::sum);
            }
            ConfigManager.ConfigData hsCfg = configManager.get();
            if (nullSkipped > 0 && hsCfg != null && hsCfg.general_debug) {
                // 计数口径说明：实体数仍是数组长度（含已被移除的槽位），此处只保证不崩
                plugin.getLogger().fine(Messages.get("run.collector.hotspot-null-slots",
                    nullSkipped, world.getName()));
            }
            String dominant = "UNKNOWN";
            int dominantCount = 0;
            for (Map.Entry<String, Integer> typeEntry : byType.entrySet()) {
                // 众数；平票取字典序最小（compareTo < 0 才替换，保证确定性）
                if (typeEntry.getValue() > dominantCount
                    || (typeEntry.getValue() == dominantCount && typeEntry.getKey().compareTo(dominant) < 0)) {
                    dominant = typeEntry.getKey();
                    dominantCount = typeEntry.getValue();
                }
            }
            out.add(new ChunkHotspot(world.getName(), cx, cz,
                entities.length, dominant, dominantCount));
        }
        return out;
    }

    /** 发布热点缓存（同一次调用只写一份状态，避免"标记已更新但值没换"的半更新）。 */
    private void publishHotspots(boolean unavailable, boolean disabled, boolean platformSkipped,
                                 List<ChunkHotspot> hotspots, long taskGen) {
        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        hotspotsCacheReady = true;
        hotspotsCacheUnavailable = unavailable;
        hotspotsCacheDisabled = disabled;
        hotspotsCachePlatformSkipped = platformSkipped;
        cachedHotspots = hotspots;
    }

    private void applyHotspotsCache(MetricsSnapshot snap) {
        if (!hotspotsCacheReady || hotspotsCacheUnavailable) {
            markDedicatedModuleUnavailable(snap, MetricsSnapshot.MODULE_HOTSPOTS,
                true, hotspotsCacheDisabled, hotspotsCachePlatformSkipped);
            return;
        }
        snap.topEntityChunks = new ArrayList<>(cachedHotspots);   // 9A-10 #3：每次快照独立副本
    }


    // ==================== P0-1 JVM 异步缓存读取 ====================

    /**
     * 将异步 JVM 缓存填入当前快照（P0-1）。
     * 缓存为空（启动初期异步尚未产出）时，内存/CPU/GC 标记 unavailable，避免 0 值误导或 NPE；
     * server 的 Bukkit 部分已在主线程采集，JVM 部分（运行时长/线程数）短暂为默认值，下一周期即补上。
     *
     * <p><b>9A-10 #2 之后只剩双参版本</b>：曾有一个单参重载
     * {@code applyJvmCache(MetricsSnapshot)}，方法体只有一行
     * {@code applyJvmCache(snap, configManager.get())}。唯一调用点（{@code collectSyncOnce}）
     * 早已改成显式传 {@code cfg}——因为 reload 期间再经 {@code configManager.get()} 回读，
     * 会让同一份快照的"JVM 段"用新一代配置、前半段用旧一代。</p>
     *
     * <p>那个重载因此是零调用点的死代码，且它<b>保留着已被淘汰的那种危险写法</b>：
     * 谁若误用它，9A-10 #2 那个 bug 会原样复发。删掉比留着安全。</p>
     *
     * @param cfg 本周期捕获的配置快照，<b>不得</b>在方法内回读 {@code configManager.get()}
     */
    private void applyJvmCache(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        MetricsSnapshot jvm = jvmCache; // volatile 读一次，保证本次填入一致
        if (jvm == null) {
            snap.unavailable.add(MetricsSnapshot.MODULE_MEMORY);
            snap.unavailable.add(MetricsSnapshot.MODULE_CPU);
            snap.unavailable.add(MetricsSnapshot.MODULE_GC);
            // 9A-20 #3：被配置禁用的模块是"服主的选择"，不是"预热"也不是"故障"——
            // 标 disabledModules 且不进 warmingUp（否则 status 会永远显示"预热中"）
            if (cfg != null && !cfg.memory_enabled) {
                snap.disabledModules.add(MetricsSnapshot.MODULE_MEMORY);
            }
            if (cfg != null && !cfg.cpu_enabled) {
                snap.disabledModules.add(MetricsSnapshot.MODULE_CPU);
            }
            // W5-D #1：仍在预热窗口内 → 同标 warmingUp，展示层渲染"预热中"（预计自愈）；
            // 超窗仍未产出（异步调度异常而非自愈场景）回落真故障"不可用"，防虚假自愈信号
            if (System.currentTimeMillis() - startedAtMs <= WARMUP_WINDOW_MS) {
                if (cfg == null || cfg.memory_enabled) {
                    snap.warmingUp.add(MetricsSnapshot.MODULE_MEMORY);
                }
                if (cfg == null || cfg.cpu_enabled) {
                    snap.warmingUp.add(MetricsSnapshot.MODULE_CPU);
                }
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
        // 9A-20 #3：JVM 缓存里的"配置禁用"标记一并透传（否则 enabled=false 的 memory/cpu 在 status 里
        // 会与"采集失败"混为一谈——jvm 缓存非空时原本只透传 unavailable）
        snap.disabledModules.addAll(jvm.disabledModules);
        // 9B-3：平台限制标记同样透传。JVM 侧模块（memory/cpu/gc/server）不属于跨 region 聚合，
        // 故这里实际恒为空——保留这一行是为了让"三个集合的透传规则"在同一处完整可见，
        // 将来若有 JVM 侧模块被平台限制，不必再回头找漏掉的一处。
        snap.platformSkipped.addAll(jvm.platformSkipped);
        snap.alerts.addAll(jvm.alerts);
        snap.activeWarnings.addAll(jvm.activeWarnings);
        snap.activeCriticals.addAll(jvm.activeCriticals);
        // v1.4：memory / cpu 的级别在 JVM 异步拍产生，不在这里并入的话，
        // 主线程快照里这两个键会缺席——表现为序列在主/异步拍之间反复消失。
        snap.alertLevels.putAll(jvm.alertLevels);
    }

    /**
     * 配置门控 + 异常隔离：模块被禁用同样标 unavailable（展示层显示"不可用"而非 0/空）。
     *
     * <p>9A-20 #3：禁用分支额外把模块写进 {@code disabledModules}（disabledModules ⊆ unavailable）。
     * 展示层据此把"服主自己关掉的"渲染成"已禁用（config xxx.enabled=false）"，
     * 而不是与"采集抛异常"共用同一句"不可用"——后者会让服主以为插件坏了。</p>
     */
    private void runOrMark(MetricsSnapshot snap, boolean enabled, String module, Runnable task) {
        if (!enabled) {
            snap.unavailable.add(module);
            snap.disabledModules.add(module);   // 9A-20 #3：区分"配置禁用"与"采集失败"
            // 9A-20 #2：模块被配置关掉不是故障，清掉可能残留的失败状态（reload 后从 enabled 切 disabled）
            ModuleHealth h = moduleHealth.get(module);
            if (h != null && h.failing) {
                h.failing = false;
                h.consecutiveFailures = 0;
                h.firstFailureAtMs = 0L;
            }
            return;
        }
        safeCollect(module, snap, task);
    }

    /**
     * 单模块异常隔离包装（P0-5）
     * catch 范围为 Exception | LinkageError：覆盖 Folia 跨线程 IllegalStateException 等运行时异常。
     *
     * <p><b>9A-20 #2（日志限频）</b>：原实现每个失败周期一条 WARNING——默认 1 秒一周期，单模块持续失败
     * 就是 <b>86400 条/天</b>，刷屏到看不见其他插件的报错。现改为与 {@code WebhookNotifier}（60s）、
     * {@code HistoryFileStore}（300s）同款限频范式：<b>首次立即记，之后每 5 分钟一条</b>，
     * 消息里带「已连续失败 N 次」「首次失败 HH:mm」；模块恢复时补一条 INFO。</p>
     *
     * <p><b>9A-20 #4（Error 逃逸口径）</b>：本方法只接 {@code Exception | LinkageError}，
     * {@code OutOfMemoryError} / {@code ThreadDeath} 等 JVM 级故障<b>故意不在此吞</b>——
     * 它们会逃到 {@link #collectSync()} 的任务边界，那里先发布死亡标志再原样抛回。</p>
     */
    private void safeCollect(String module, MetricsSnapshot snap, Runnable task) {
        safeCollect(module, module, snap, task);
    }

    /**
     * 带独立健康槽位的 safeCollect（9A-20 顺手修复）。
     *
     * <p>背景：{@code MODULE_SERVER} 同时被主线程（插件数/世界数）与 JVM 异步线程（运行时长/线程数）
     * 两条任务采集。若两者共用一个健康槽位，一侧成功就会把另一侧的"连续失败 N 次"清零，
     * 于是持续失败变成"首次失败 → 已恢复 → 首次失败 → 已恢复"的日志抖动（比刷屏更难读）。
     * 故异步那条用独立槽位 {@code server(jvm)}，模块名仍写 {@code module}（快照 unavailable 语义不变）。</p>
     */
    private void safeCollect(String module, String healthKey, MetricsSnapshot snap, Runnable task) {
        try {
            task.run();
            onModuleSuccess(healthKey);
        } catch (Exception | LinkageError t) {
            snap.unavailable.add(module);
            onModuleFailure(healthKey, t);
        }
    }

    // ==================== 9A-20 #2：模块级失败日志状态机 ====================

    /** 9A-20：JVM 异步侧"服务器信息"独立健康槽位（避免与主线程同模块互相清零，见 safeCollect 重载注释）。 */
    private static final String HEALTH_KEY_SERVER_JVM = "server(jvm)";

    /**
     * 模块采集成功：若此前处于失败态，补一条 INFO「模块 X 已恢复」。
     *
     * <p>9A-20 顺手项：恢复日志同样限频（5 分钟）。若模块失败/成功以周期为单位交替（瞬时异常或
     * 多线程共享槽位），不加限频会变成"失败→恢复→失败→恢复"的日志抖动，比刷屏更难读。</p>
     */
    private void onModuleSuccess(String module) {
        ModuleHealth h = moduleHealth.get(module);
        if (h == null || !h.failing) {
            return;
        }
        long now = System.currentTimeMillis();
        int failedTimes = h.consecutiveFailures;
        long firstAt = h.firstFailureAtMs;
        h.failing = false;
        h.consecutiveFailures = 0;
        h.firstFailureAtMs = 0L;
        h.lastMessage = null;
        if (now - h.lastRecoveryLogAtMs < MODULE_FAILURE_LOG_INTERVAL_MS) {
            return;   // 抖动抑制：5 分钟内只报一次"已恢复"
        }
        h.lastRecoveryLogAtMs = now;
        plugin.getLogger().info(firstAt > 0
            ? Messages.get("run.collector.module-recovered", module, failedTimes, formatClock(firstAt))
            : Messages.get("run.collector.module-recovered-brief", module, failedTimes));
    }

    /**
     * 模块采集失败：累加连续失败计数并限频记日志（首次立即、之后每 5 分钟）。
     * debug 模式额外打完整堆栈（与全仓日志口径一致）。
     */
    private void onModuleFailure(String module, Throwable t) {
        long now = System.currentTimeMillis();
        ModuleHealth h = moduleHealth.computeIfAbsent(module, k -> new ModuleHealth());
        if (!h.failing) {
            h.failing = true;
            h.consecutiveFailures = 0;
            h.firstFailureAtMs = now;
            h.lastLogAtMs = 0L;   // 首次失败必须立即记（不受上次限频残留影响）
        }
        h.consecutiveFailures++;
        h.lastMessage = describeThrowable(t);

        if (now - h.lastLogAtMs < MODULE_FAILURE_LOG_INTERVAL_MS) {
            return;   // 限频：5 分钟内不重复记
        }
        h.lastLogAtMs = now;
        String detail = Messages.get("run.collector.module-failed", module, h.lastMessage,
            h.consecutiveFailures, formatClock(h.firstFailureAtMs));
        if (configManager.get() != null && configManager.get().general_debug) {
            plugin.getLogger().log(Level.WARNING, detail, t);
        } else {
            plugin.getLogger().warning(detail);
        }
    }

    /**
     * 9A-20：当前处于失败态的模块快照（模块名 → 连续失败次数）；供 /perfscope status 展示。
     * {@code server(jvm)} 合并回 {@code server}（取较大失败次数），使 status 的模块表与快照模块名对齐。
     */
    public Map<String, Integer> failingModules() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, ModuleHealth> e : moduleHealth.entrySet()) {
            ModuleHealth h = e.getValue();
            if (!h.failing) {
                continue;
            }
            String key = e.getKey();
            if (key.endsWith("(jvm)")) {
                key = key.substring(0, key.length() - "(jvm)".length());
            }
            out.merge(key, h.consecutiveFailures, Math::max);
        }
        return out;
    }

    /** 异常单行描述（类名 + 消息），供日志与 status 输出（绝不输出堆栈到聊天框）。 */
    private static String describeThrowable(Throwable t) {
        if (t == null) {
            return Messages.get("run.collector.unknown-error");
        }
        String msg = t.getMessage();
        // 类名与原始消息保持原样（可检索），只有"没有消息"这一种情况走文案层
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    /** epoch ms → "HH:mm:ss"（本地时区，与命令层"采集时间"同口径）。 */
    private static String formatClock(long epochMs) {
        return DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs));
    }

    // ==================== TPS ====================

    private void collectTPS(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        double[] tps = Bukkit.getTPS();
        snap.tps1m = round2(tps[0]);
        snap.tps5m = round2(tps[1]);
        snap.tps15m = round2(tps[2]);
        applyTpsLevel(snap, cfg);
    }

    /**
     * TPS 的判定与告警生成（{@code collectTPS} 的判定半段，与 Bukkit 读数解耦）。
     *
     * <p><b>为什么拆出来</b>：{@code collectTPS} 的第一行是 {@code Bukkit.getTPS()}，
     * 测试 JVM 里没有服务端。判定（算子、档位、键名、告警报文级别）此前因此整段不可测——
     * 把 {@code <} 改成 {@code <=} 全量用例一个都不红。拆开后本方法只读 {@code snap.tps1m}
     * 与三个阈值，测试可以直接构造读数再调用它。</p>
     *
     * <p>行为与拆分前逐位等价：档位判定仍走 {@link ThresholdOperator#TPS}（两档严格 {@code <}），
     * 告警参数（键名、级别、阈值字段、模块、时间戳）一字未改。</p>
     */
    void applyTpsLevel(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        // 算子（`<` 还是 `<=`）不写在这里，见 ThresholdOperator.TPS：低值差、两档严格比较。
        // 之前这两段内联的 `<` 零测试保护——改成 `<=` 全量用例一个都不红（实测），
        // 而线上 TPS 恰好 15.0 时会从「只发 warning」变成「发 critical」。
        int level = ThresholdOperator.TPS.levelOf(
            snap.tps1m, cfg.tps_warningThreshold, cfg.tps_criticalThreshold);
        // 键名也从表里取：阈值与序列名是同一个身份的两半，分开写会让「改了表却没改键」
        // 变成一次静默的 Prometheus 序列改名（旧序列消失、新序列从 0 起）。
        String key = ThresholdOperator.TPS.key();
        if (level == 2) {
            addAlert(snap, Alert.measuredWithThresholds(key, Alert.LEVEL_CRITICAL, snap.tps1m,
                    cfg.tps_criticalThreshold, cfg.tps_criticalThreshold, MetricsSnapshot.MODULE_TPS, snap.timestamp));
        } else if (level == 1) {
            addAlert(snap, Alert.measuredWithThresholds(key, Alert.LEVEL_WARNING, snap.tps1m,
                    cfg.tps_warningThreshold, cfg.tps_warningThreshold, MetricsSnapshot.MODULE_TPS, snap.timestamp));
        }
        recordLevel(snap, key, level);
    }

    // ==================== MSPT ====================

    /**
     * MSPT 三级降级链（顺序固定，勿调换）：
     * ① Paper CraftServer.getTickTimes()（真实 min/max，MC-1 修复后可达）→
     * ② getAverageTickTime()（Paper/Bukkit 平均 tick API，**不是估算**，数据真实只是无 min/max）→
     * ③ TPS 反推（唯一"估算"级：满 TPS 时语义不可用，置 -1 哨兵）。
     * 展示层的"(估算)"标签口径应以 ①||② 为"精确"，仅 ③ 为估算。
     *
     * <h2>P0-U / P1-S3（本次修复）：三条路径分别"报什么"</h2>
     *
     * <p>此前三条路径的失败态全部<b>静默</b>：值置 {@code -1} 就完了，模块不进 {@code unavailable}，
     * 于是 <code>/ps status</code> 的模块表按 {@code platformSkipped → disabledModules → warmingUp
     * → unavailable} 一路落空，渲染成绿色「正常」，而 <code>/ps</code> 的 MSPT 行同时显示「未知」。
     * 同一屏上<b>采集说没数、状态说没事</b>。现在逐条定口径：</p>
     * <ul>
     *   <li><b>① n==0</b>（数组空/全零：Paper 上有这个 API 却一个有效 tick 都没采到）→
     *       <b>标 unavailable</b>。这是真的采集失败，服主需要知道；</li>
     *   <li><b>② 返回 0/负数</b>（invoke 成功但 API 给出物理上不可能的值）→ 写 {@code -1} 并
     *       <b>标 unavailable</b>。原实现把 0 原样写进快照，"没测到"被渲染成"tick 几乎不耗时"；</li>
     *   <li><b>③ TPS 反推</b> → <b>刻意不标 unavailable</b>，理由见
     *       {@link #collectMSPT_TPSFallback}。</li>
     * </ul>
     *
     * <p><b>标 unavailable 会不会引入「采集坏了却报已恢复」？</b>不会，方向恰好相反。
     * 告警层在 {@code AlertService} 第 3 步把 {@code module ∈ unavailable} 读作 {@code moduleDown}：
     * 它<b>不算干净周期</b>、不宣布恢复，只累计"无结论时长"，超时后转入 unknown（见
     * {@code alert.log.module-down}）。也就是说标 unavailable 是把"消息消失"从"故障恢复"
     * 改判成"不知道"，这正是本项目要的方向。</p>
     */
    private void collectMSPT(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        // 9A-1：先问"数据是否可信"，而不是"方法是否存在"。
        // Folia 上 getTickTimes/getAverageTickTime 方法都在但恒返回全零，若此时再降级到
        // TPS 反推，等于用"region 局部 TPS"（9A-2）去反推一个本就不存在的全局 MSPT——双重不可信。
        if (!ServerCompat.isMsptTrustworthy()) {
            if (ServerCompat.isFolia()) {
                snap.msptAvg = -1;
                snap.msptMin = -1;
                snap.msptMax = -1;
                snap.msptSource = MetricsSnapshot.MSPT_SOURCE_UNKNOWN;
                snap.unavailable.add(MetricsSnapshot.MODULE_MSPT);
                // 9B-3 契约 platformSkipped ⊆ disabledModules ⊆ unavailable：
                // Folia 的 getTickTimes/getAverageTickTime 方法都在但恒返回全零，属"平台使该指标
                // 不可信"，与 entities/chunks 的跨 region 聚合不可靠同类。此前只标了 unavailable，
                // 于是 /perfscope status 按 platformSkipped → disabledModules → warmingUp 顺序判定时
                // 一路落空，渲染成红色的「采集失败」——把平台限制误报成故障。
                snap.disabledModules.add(MetricsSnapshot.MODULE_MSPT);
                snap.platformSkipped.add(MetricsSnapshot.MODULE_MSPT);
                return;
            }
            collectMSPT_TPSFallback(snap);   // 纯 Bukkit/Spigot：全服 TPS 反推仍合理
        } else if (ServerCompat.PAPER_MSPT_AVAILABLE && getTickTimesMethod != null) {
            collectMSPT_Paper(snap);
        } else if (ServerCompat.PAPER_AVG_TICK_AVAILABLE) {
            collectMSPT_BukkitAvg(snap);
        } else {
            collectMSPT_TPSFallback(snap);
        }

        // P0-U：msptSource 是**本周期实际走的路径**（不是静态能力位），估算标记以它为准——
        // 一级 invoke 失败降级时静态位仍为 true，用能力位判断会把真实路径的估算值当成精确值。
        //
        // 为什么这一条必须在这里算、而不是在三条路径里各写一遍：三条路径最终都汇到下面同一处
        // 告警生成点，标记若跟着路径走，就会在"一级降级到三级"这类组合上漏掉（正是本 bug 的成因）。
        boolean estimated = snap.msptSource == MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK;

        // v1.4：msptAvg <= 0 是「未采集」哨兵（TPS 反推路径无 MSPT），此时该维度不可判定。
        // v1.6：**必须显式写 ALERT_LEVEL_NO_VERDICT**，而不是让键缺席 ——
        // 缺席在告警层还有第二种含义（该维度本周期不适用，例如世界已卸载），告警据此正常恢复。
        // 用缺席表达"没测"，等于把"没测"谎报成"不适用"：采集坏了，告警却宣布「已恢复正常」。
        // 级别与告警的对应关系收在 msptAlertLevel 里（纯函数，可单测），调用点只管照着发。
        int level = msptAlertLevel(snap.msptAvg, cfg.mspt_warningThreshold, cfg.mspt_criticalThreshold);
        String key = ThresholdOperator.MSPT.key();
        if (level == 2) {
            addAlert(snap, Alert.measuredWithThresholds(key, Alert.LEVEL_CRITICAL, snap.msptAvg,
                    cfg.mspt_criticalThreshold, cfg.mspt_criticalThreshold, MetricsSnapshot.MODULE_MSPT, snap.timestamp)
                .asEstimated(estimated));
        } else if (level == 1) {
            addAlert(snap, Alert.measuredWithThresholds(key, Alert.LEVEL_WARNING, snap.msptAvg,
                    cfg.mspt_warningThreshold, cfg.mspt_warningThreshold, MetricsSnapshot.MODULE_MSPT, snap.timestamp)
                .asEstimated(estimated));
        }
        recordLevel(snap, key, level);
    }

    /**
     * MSPT 的级别判定内核：返回 {@code 2} / {@code 1} / {@code 0}，或
     * {@link MetricsSnapshot#ALERT_LEVEL_NO_VERDICT}（本周期不可判定）。
     *
     * <p><b>为什么单独抽出来</b>：{@code collectMSPT} 的入口先是
     * {@code ServerCompat.isMsptTrustworthy()} 与若干反射调用，测试 JVM 里根本走不进去
     * （{@code ServerCompat} 的 {@code <clinit>} 要 {@code Bukkit.getBukkitVersion()}）。
     * 于是"哨兵是否被正确表达成没判定"这件事此前零保护，而它正是那个「采集坏了却报已恢复」
     * 的 bug 的一半。抽成纯函数后，输入只是三个 double。</p>
     *
     * <p><b>{@code <= 0} 的两个来源都归入没判定</b>：{@code -1} 是显式哨兵（TPS 反推路径无 MSPT），
     * {@code 0} 在物理上不可能是真实 tick 耗时。两者若落进 {@code 0} 级别，就是宣称
     * "测了、很健康"——而事实是"没测"。</p>
     *
     * @param msptAvg 平均每 tick 毫秒；{@code <= 0} 表示本周期没有可信读数
     * @param warn    警告阈值（{@code >} 此值进 1 级）
     * @param crit    严重阈值（{@code >} 此值进 2 级）
     * @return 级别，或 {@link MetricsSnapshot#ALERT_LEVEL_NO_VERDICT}
     */
    static int msptAlertLevel(double msptAvg, double warn, double crit) {
        if (!(msptAvg > 0)) {
            return MetricsSnapshot.ALERT_LEVEL_NO_VERDICT;
        }
        // 两档的算子（`>` 还是 `>=`）不写在这里，见 ThresholdOperator.MSPT。
        // 本方法只负责「先判有没有可信读数」，剩下的级别判定交给那张表——
        // 这样「MSPT 的 warning 档从 > 改成 >=」就能被阈值表的行为用例单独杀死。
        return ThresholdOperator.MSPT.levelOf(msptAvg, warn, crit);
    }

    /**
     * Paper 一级：CraftServer.getTickTimes() 返回 long[]（元素为 nanos），自算 avg/min/max（MC-1 修复）。
     * 边界（P0-13 哨兵语义）：空数组或全为非正值（尚无有效 tick 采样/未填充槽位）→ 三值置 -1"未知"，
     * 不做除零也不输出误导性 0；单元素 ≤0 按无效采样跳过，避免未填充槽位拉低 min/avg。
     * 一级 invoke 失败降级到第二级 getAverageTickTime（非估算），二级再失败才落 TPS 反推。
     *
     * <p><b>P1-S3：为什么 n==0 要标 unavailable</b>——旧实现只把三个值置 {@code -1} 就返回，
     * 模块不进 {@code unavailable}，于是 {@code /ps status} 的模块表显示绿色「正常」，
     * 而 {@code /ps} 的 MSPT 行显示「未知」。同一屏上"采集说没数、状态说没事"。
     * 现在标 unavailable，状态表如实变红（或在前 10 秒内显示「预热中」，见
     * {@link #markMsptCollectFailed}）。</p>
     */
    private void collectMSPT_Paper(MetricsSnapshot snap) {
        try {
            Object server = Bukkit.getServer();
            long[] arr = (long[]) getTickTimesMethod.invoke(server);
            MsptStats stats = reduceTickTimes(arr);
            snap.msptAvg = stats.avgMs();
            snap.msptMin = stats.minMs();
            snap.msptMax = stats.maxMs();
            if (stats.hasSamples()) {
                snap.msptSource = MetricsSnapshot.MSPT_SOURCE_TICK_TIMES;
            } else {
                // 方法在、调用成功、但一个有效 tick 都没采到 —— 这是"采集失败"，不是"读数为 0"。
                // msptSource 保持 UNKNOWN，使展示层不会把它当成任何"精确/估算"路径。
                markMsptCollectFailed(snap);
            }
        } catch (Exception e) {
            collectMSPT_BukkitAvg(snap);
        }
    }

    /**
     * Paper {@code getTickTimes()} 返回值的规约内核：把 {@code long[]}（纳秒）折成毫秒统计量。
     *
     * <p><b>为什么把它单独抽出来</b>：这段循环是 MSPT 一级路径的全部算法，而它此前零测试——
     * {@code collectMSPT_Paper} 的前两行是 {@code Bukkit.getServer()} 与反射 {@code invoke}，
     * 于是整条路因为"前面两行测不了"而连算法一起失去了保护。抽成纯函数后，输入只是
     * {@code long[]}，问题从"怎么造一个 CraftServer"变成"怎么造一个数组"。</p>
     *
     * <p><b>三个哨兵语义（都容易被改坏，且改坏后不报错）</b>：</p>
     * <ul>
     *   <li>{@code t <= 0} 的槽位<b>跳过</b>而不是当 0 计入——未填充槽位是"这段窗口还没 tick"，
     *       把它算进 min 会让 min 恒为 0，而展示层用 {@code msptMin <= 0} 判"未采集"，
     *       于是<b>有效极值被渲染成"未采集"</b>。跳过条件写成 {@code t < 0} 就正是这个错。</li>
     *   <li>{@code n == 0}（数组全零 / 空 / null）→ 三个值都置 {@code -1} 哨兵，
     *       <b>不是</b> 0。MSPT 里 0 是合法读数（一个 tick 几乎没耗时），
     *       用它表示"没采到"会让"服务器极快"与"采不到"无法区分。</li>
     *   <li>{@code max} 初值取 {@code 0} 而非 {@code Long.MIN_VALUE}：能进循环的 {@code t} 必为正，
     *       所以 0 不会漏掉任何真实样本。改成 {@code Long.MIN_VALUE} 在 {@code n == 0} 时
     *       会算出荒谬的巨大值——但那条路已被 {@code n == 0} 的哨兵拦住。</li>
     * </ul>
     *
     * <p>单位：入参是<b>纳秒</b>（Paper {@code getTickTimes()} 的语义），出参是<b>毫秒</b>。
     * 除以 1e6 这一步三处（avg/min/max）必须同改——漏改一处会得到量级差 1000 的混排。</p>
     *
     * @param arr {@code getTickTimes()} 的返回值，可为 {@code null}
     * @return 规约结果；无有效样本时三个值为 {@code -1} 且 {@code validSamples == 0}
     */
    static MsptStats reduceTickTimes(long[] arr) {
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
            return new MsptStats(-1, -1, -1, 0);
        }
        return new MsptStats(
            round2(sum / (double) n / 1_000_000.0),
            round2(min / 1_000_000.0),
            round2(max / 1_000_000.0),
            n);
    }

    /**
     * {@link #reduceTickTimes} 的结果。
     *
     * <p>暴露 {@code validSamples} 而不只是三个 double，是因为"min 对但 avg 的分母错"这类
     * 半对错误无法从三个值看出来——分母错了 avg 会偏，但若样本恰好均匀就看不出来。</p>
     *
     * @param avgMs        平均每 tick 毫秒；无样本时 {@code -1}
     * @param minMs        最小每 tick 毫秒；无样本时 {@code -1}
     * @param maxMs        最大每 tick 毫秒；无样本时 {@code -1}
     * @param validSamples 有效（{@code > 0}）样本数
     */
    record MsptStats(double avgMs, double minMs, double maxMs, int validSamples) {

        /** 本轮是否采到了有效样本（{@code false} 时三个值都是哨兵）。 */
        boolean hasSamples() {
            return validSamples > 0;
        }
    }

    /**
     * Paper: getAverageTickTime() 获取平均 MSPT（反射方法已在构造时缓存）。
     * 9A-0：该路径只有平均值，**没有** min/max——两者置 -1 哨兵（展示层渲染"未采集"），
     * 绝不把平均值复制给 min/max 伪造极值区间。
     *
     * <p><b>P1-S3：invoke 成功但返回 0/负数怎么办</b>——旧实现把这个值原样写进快照，
     * 于是"API 给出了物理上不可能的读数"被当成"tick 几乎不耗时"：{@code /ps} 会渲染
     * 绿色的 {@code 0.00ms}，报告里写 {@code MSPT (avg): 0.00 ms}。而每 tick 至少花掉
     * 可测量的时间（见 {@code check-sentinels.mjs} 的 {@code SENTINEL_AND_ZERO_BOTH_BAD}）。
     * 现改为：非正值一律写 {@code -1} 哨兵并标 {@code unavailable}——与一级 {@code n==0}
     * 同一处置，因为它们同属"采集拿到了无效数据"，而不是"没有这个方法"。</p>
     */
    private void collectMSPT_BukkitAvg(MetricsSnapshot snap) {
        if (avgTickMethod == null) {
            collectMSPT_TPSFallback(snap);
            return;
        }
        try {
            Object server = Bukkit.getServer();
            double mspt = ((Number) avgTickMethod.invoke(server)).doubleValue();
            snap.msptMin = -1;
            snap.msptMax = -1;
            snap.msptSource = MetricsSnapshot.MSPT_SOURCE_AVG_TICK;  // 路径确实是二级，坏的是数据
            snap.msptAvg = sanitizeAvgTick(mspt);
            if (snap.msptAvg <= 0) {
                markMsptCollectFailed(snap);
            }
        } catch (Exception e) {
            collectMSPT_TPSFallback(snap);
        }
    }

    /**
     * {@code getAverageTickTime()} 返回值的规约内核：可信则舍入，不可信则 {@code -1} 哨兵。
     *
     * <p><b>为什么抽成纯函数</b>：与 {@link #reduceTickTimes} 同一理由——{@code collectMSPT_BukkitAvg}
     * 前两行是 {@code Bukkit.getServer()} 与反射 invoke，测试 JVM 里走不进去，于是"API 返回 0 时
     * 会发生什么"此前零保护，而它正是 P1-S3 里那半条 bug（0 被原样当读数发出）。</p>
     *
     * <p>{@code !(mspt > 0)} 而不是 {@code mspt <= 0}：前者顺带挡住 {@code NaN}
     * （{@code NaN <= 0} 是 false，会让 NaN 漏进快照再被 {@code round2} 静默变成 0）。</p>
     *
     * @param mspt 反射得到的每 tick 毫秒数
     * @return 舍入到两位的读数；{@code <= 0} / 非有限值时返回 {@code -1}（"未采集"）
     */
    static double sanitizeAvgTick(double mspt) {
        if (!(mspt > 0) || !Double.isFinite(mspt)) {
            return -1;
        }
        return round2(mspt);
    }

    /**
     * Bukkit: 通过 TPS 反推近似 MSPT（满 TPS 时语义"估算不可用"，置 -1 而非 0）。
     *
     * <h2>P1-S3：为什么这一条<b>不</b>标 {@code unavailable}，而前两条要标</h2>
     *
     * <p>三条降级路径的失败语义<b>不同</b>，不能用同一处置：</p>
     * <ul>
     *   <li>一级 {@code n==0}、二级返回 0/负 —— "本该采到却采到了坏数据" = <b>故障</b>，
     *       必须标 unavailable，让 {@code /ps status} 变红；</li>
     *   <li>本条 —— "服务端根本没有这个 API / 每次 invoke 都抛" = <b>能力缺失</b>，
     *       而满 TPS 时反推公式按定义没有意义（{@code 1000/20 = 50ms} 是墙钟间隔，不是计算耗时），
     *       所以置 {@code -1} 是<b>本路径的正常工作方式</b>，不是失败。</li>
     * </ul>
     *
     * <p>更直接的判据：<b>标 unavailable 会给纯 Spigot 服制造永久红字</b>。Spigot 没有
     * {@code getTickTimes}/{@code getAverageTickTime}（能力位为 false），本条是唯一路径；
     * 一台满 TPS 的健康 Spigot 服会因此永远显示「MSPT 采集失败」——把"这个端本来就不提供"
     * 谎报成"插件坏了"。这与 Folia 上 {@code platformSkipped} 要标成灰色而非红色是同一条原则。</p>
     *
     * <p>而且本条不需要靠 unavailable 保护告警：满 TPS 时 {@code msptAvg = -1} 会让
     * {@link #msptAlertLevel} 返回 {@code NO_VERDICT}，MSPT 告警同样不会发（这是第三道闸门）；
     * {@code /ps} 侧也已有 {@code msptAvg <= 0} 的「未知」分支。前两条路径没有这个保护——
     * 它们的哨兵是"采集代码自己写坏的值"，下游必须靠 {@code unavailable} 才知道不可信。</p>
     */
    private void collectMSPT_TPSFallback(MetricsSnapshot snap) {
        if (snap.tps1m > 0 && snap.tps1m < 20) {  // @sentinel-ok TPS 反推 MSPT：必须排除 0，否则 1000/0 = Infinity；else 分支显式置 -1 表示未采集
            snap.msptAvg = round2(1000.0 / snap.tps1m);
        } else {
            snap.msptAvg = -1;
        }
        // 9A-0：反推值没有极值信息，min/max 置 -1（"未采集"），不得复制平均值
        snap.msptMin = -1;
        snap.msptMax = -1;
        snap.msptSource = MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK;
    }

    /**
     * P1-S3：把 MSPT 标成"本轮采集失败"（一级 {@code n==0} / 二级返回 0 或负值）。
     *
     * <p>只做两件事：写 {@code unavailable}、并在启动/重载后 {@link #WARMUP_WINDOW_MS} 宽限期内
     * 同时写 {@code warmingUp}（展示层据此渲染"预热中"而不是红色「采集失败」——
     * Paper 启动后头一两轮 {@code getTickTimes()} 确实可能还是空数组，那是预热不是故障）。</p>
     *
     * <p><b>为什么不写 {@code disabledModules}</b>：这个集合的语义是"服主显式关掉了"
     * （{@code xxx.enabled=false}），采集失败不是服主的选择。写错会让
     * {@code /ps status} 显示「已禁用（config …=false）」，把故障伪装成配置——
     * 这是 {@code MetricsSnapshot} 那条 {@code disabledModules ⊆ unavailable} 契约存在的理由。
     * 这里只写 {@code unavailable} 这一个集合，天然满足三条子集契约（另两个集合不变）。</p>
     *
     * <p><b>与告警层的联动（安全性论证）</b>：标了 unavailable 之后，{@code AlertService} 第 3 步
     * 会把它归入 {@code moduleDown}，从而<b>不算干净周期、不宣布恢复</b>，只累计无结论时长，
     * 超时转 unknown。也就是说这一改动把"消息消失"从"故障恢复"改判成"不知道"，
     * 方向与「采集坏了却报已恢复」相反——不会引入那类 bug。</p>
     */
    private void markMsptCollectFailed(MetricsSnapshot snap) {
        snap.unavailable.add(MetricsSnapshot.MODULE_MSPT);
        if (System.currentTimeMillis() - startedAtMs <= WARMUP_WINDOW_MS) {
            snap.warmingUp.add(MetricsSnapshot.MODULE_MSPT);
        }
    }

    // ==================== 玩家 ====================

    @SuppressWarnings("deprecation")
    private void collectPlayers(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        // 兼容 Spigot/Bukkit: getOnlinePlayers() 返回 Collection
        snap.playerCount = Bukkit.getOnlinePlayers().size();
        snap.maxPlayers = Bukkit.getMaxPlayers();

        // P0-13：maxPlayers<=0（代理/精简端）时不做除法，避免 Infinity 强转为 Integer.MAX_VALUE 误报
        // 9A-6：统一走 MetricsSnapshot.playerPercent()（唯一算法入口）
        int pct = snap.playerPercent();
        applyPlayersLevel(snap, cfg, pct);
    }

    /**
     * 玩家占比的判定与告警生成（{@code collectPlayers} 的判定半段）。
     *
     * <p>拆分理由与 {@link #applyTpsLevel} 同：取值来自 {@code Bukkit.getOnlinePlayers()}
     * 与 {@code Bukkit.getMaxPlayers()}，测不了。拆分后判定只依赖 {@code pct} 与两个阈值。</p>
     *
     * <p>行为等价：档位仍走 {@link ThresholdOperator#PLAYERS}（两档闭区间 {@code >=}），
     * 告警参数（含 {@code measuredWithReference} 的 playerCount/maxPlayers）不变。</p>
     */
    void applyPlayersLevel(MetricsSnapshot snap, ConfigManager.ConfigData cfg, int pct) {
        // 算子见 ThresholdOperator.PLAYERS：百分比指标两档都是闭区间（`>=`）。
        // 把 `>=` 改成 `>` 会让「恰好 98%」从 critical 掉回 warning——边界上整整让一档。
        int level = ThresholdOperator.PLAYERS.levelOf(
            pct, cfg.players_warningThreshold, cfg.players_criticalThreshold);
        String key = ThresholdOperator.PLAYERS.key();
        if (level == 2) {
            addAlert(snap, Alert.measuredWithReference(key, Alert.LEVEL_CRITICAL,
                snap.playerCount, snap.maxPlayers, MetricsSnapshot.MODULE_PLAYERS, snap.timestamp));
        } else if (level == 1) {
            addAlert(snap, Alert.measuredWithReference(key, Alert.LEVEL_WARNING,
                snap.playerCount, snap.maxPlayers, MetricsSnapshot.MODULE_PLAYERS, snap.timestamp));
        }
        recordLevel(snap, key, level);
    }

    // ==================== 实体 ====================

    private void collectEntities(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        int total = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Integer> byWorld = new LinkedHashMap<>();

        for (World world : Bukkit.getWorlds()) {
            int worldCount = 0;
            for (Entity entity : world.getEntities()) {
                // 9B-4：与 chunk.getEntities() 同源风险——实体被并发移除时集合可能含 null
                if (entity == null) {
                    continue;
                }
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

        applyEntitiesLevels(snap, cfg, byWorld);
    }

    /**
     * 实体判定与告警生成（{@code collectEntities} 的判定半段，与 Bukkit 遍历解耦）。
     *
     * <p><b>为什么拆出来</b>：{@code collectEntities} 的取值要 {@code Bukkit.getWorlds()} 与
     * 逐个 {@code world.getEntities()}，测试 JVM 里无法驱动。而这里的两个判定点
     * （逐世界覆盖、全服总数）此前<b>零测试保护</b>——把任一 {@code >} 改成 {@code >=}
     * 全量 715 个用例都不会红（实测）。拆分后只要给一张"世界 → 实体数"表就能验证算子。</p>
     *
     * <p>行为等价：两个判定点仍走 {@link ThresholdOperator#ENTITIES_WORLD} 与
     * {@link ThresholdOperator#ENTITIES_GLOBAL}（两档严格 {@code >}）；去重判据
     * {@link #shouldEmitGlobalEntitiesAlert} 与 {@code alertLevels} 的写入时机不变。</p>
     *
     * @param snap   快照（读 {@code totalEntities}，写 {@code alerts} / {@code alertLevels}）
     * @param cfg    本周期配置
     * @param byWorld 世界名 → 实体数（调用方已遍历完，顺序与快照展示一致）
     */
    void applyEntitiesLevels(MetricsSnapshot snap, ConfigManager.ConfigData cfg,
                            Map<String, Integer> byWorld) {
        // A5/RC-1 裁决——实体阈值判定口径（与 config.yml"总实体数告警阈值"注释及命令层总数口径一致）：
        // ① 在 worlds 段显式配置独立阈值（WorldSettings 覆盖）的世界：按该世界覆盖值逐世界判定；
        // ② 未配置覆盖的世界：不再逐世界套用全局阈值（旧行为下默认 2000/3000 在任何单世界上几乎不可达，
        //    导致默认实体告警全服失效），其计数由 ③ 的全服总数判定统一覆盖；
        // ③ 全服总数判定（一次）：对 snap.totalEntities 与全局阈值比较，生成"实体总数…"文案。
        // 9A-10 #2：WorldSettings 必须从**传入的 cfg** 取，不能再走 configManager.getWorldSettings()
        //（后者内部读 configManager 的当前引用——reload 时会让"逐世界判定"用新一代阈值，
        // 而"全服总数判定"用旧一代阈值，同一份快照混用两代配置）。
        //
        // v1.4：本周期逐世界判定的最高级别，供下方全服总数判定去重（见那里的长注释）。
        int maxWorldLevel = 0;
        for (Map.Entry<String, Integer> entry : byWorld.entrySet()) {
            ConfigManager.WorldSettings ws = worldSettingsFrom(cfg, entry.getKey());
            if (ws == null) {
                continue; // 无覆盖的世界不单独判定，由下方总数判定覆盖
            }
            // v1.4：只有「配置了 worlds 覆盖」的世界才进全集——没覆盖的世界永远不可能报警，
            // 给它发一条恒为 0 的序列会在 Grafana 上造出一个点不进去的假维度。
            String entKey = "entities:world:" + entry.getKey();
            // 算子见 ThresholdOperator.ENTITIES_WORLD：两档严格比较（`>`）。
            // 这条此前零保护——改成 `>=` 全量用例一个都不红（实测），而它决定每个配置了
            // worlds 覆盖的世界在边界值上是否告警。
            int worldLevel = ThresholdOperator.ENTITIES_WORLD.levelOf(entry.getValue(),
                ws.entities_totalWarningThreshold, ws.entities_totalCriticalThreshold);
            if (worldLevel == 2) {
                addAlert(snap, Alert.measured(entKey, Alert.LEVEL_CRITICAL, entry.getValue(),
                    MetricsSnapshot.MODULE_ENTITIES, snap.timestamp));
                maxWorldLevel = 2;
            } else if (worldLevel == 1) {
                addAlert(snap, Alert.measured(entKey, Alert.LEVEL_WARNING, entry.getValue(),
                    MetricsSnapshot.MODULE_ENTITIES, snap.timestamp));
                maxWorldLevel = Math.max(maxWorldLevel, 1);
            }
            recordLevel(snap, entKey, worldLevel);
        }

        // 全服总数判定（与命令层 /ps entities 的总数口径一致：所有世界实体数相加）。
        //
        // v1.4 去重——只改"发不发这条消息"，不动任何展示数字】
        // 配置了 worlds 覆盖的世界，其实体数**同时**计入全服合计（口径见 config.yml 与
        // /perfscope entities，v1.4 刻意不改）。于是同一批实体既触发一条 entities:world:<名>，
        // 又触发一条 entities:global——一个世界就产出两条消息，四个世界可产出多条。
        // 修法是：全服级别**照常计算并写入 alertLevels**（perfscope_alert 的完整状态不因去重而消失），
        // 但只有当它**高于**本周期逐世界判定的最高级别时才真正生成消息。
        //   · 全服 critical + 逐世界 warning → 仍然发（"整体已 critical"是逐世界消息没有的信息）；
        //   · 全服越界而没有任何逐世界越界（三个世界各差一点、加起来超了）→ 照常发，
        //     这恰恰是逐世界口径看不见的问题，不能因为去重把它也吞掉；
        //   · 全服与某个逐世界同级别或更低 → 静默（那条逐世界消息已经说了同一件事，且说得更具体）。
        //
        // 否决过的方案：
        //   ① 计算全服总数时排除有覆盖的世界 —— snap.totalEntities 是 /perfscope entities、
        //      报告与 Prometheus 的**展示数字**，改它会让用户看到的"全服实体数"与各世界之和对不上；
        //      而且当所有世界都配了覆盖时全服口径会恒为 0，"实体分散在多个世界"的超标彻底失声。
        //   ③ 只在文档里说明重叠 —— 一条消息都不减少，等于没修。
        int globalLevel = ThresholdOperator.ENTITIES_GLOBAL.levelOf(snap.totalEntities,
            cfg.entities_totalWarningThreshold, cfg.entities_totalCriticalThreshold);
        String globalKey = ThresholdOperator.ENTITIES_GLOBAL.key();
        if (shouldEmitGlobalEntitiesAlert(globalLevel, maxWorldLevel)) {
            addAlert(snap, Alert.measured(globalKey,
                globalLevel == 2 ? Alert.LEVEL_CRITICAL : Alert.LEVEL_WARNING,
                snap.totalEntities, MetricsSnapshot.MODULE_ENTITIES, snap.timestamp));
        }
        recordLevel(snap, globalKey, globalLevel);
    }

    /**
     * 全服实体总数告警是否应当真正生成消息（v1.4 去重规则）。
     *
     * <p>规则：只有当全服级别**严格高于**本周期逐世界判定的最高级别时才发。</p>
     * <ul>
     *   <li>{@code (2, 0)} / {@code (1, 0)} → 发：没有任何世界单独超标，全服口径是唯一信号；</li>
     *   <li>{@code (2, 1)} / {@code (2, 2)} → 不发：更具体的逐世界消息已经覆盖同一件事；</li>
     *   <li>{@code (1, 1)} → 不发；{@code (0, *)} → 不适用（未越界，调用方本就不会进这个分支）。</li>
     * </ul>
     *
     * <p>这里刻意只判「要不要发消息」，<b>不</b>参与级别记录——{@code alertLevels} 里
     * {@code entities:global} 必须始终反映全服总数的真实判定，否则 Prometheus 上会出现
     * "总数明明 critical 但序列显示 0"的假恢复。</p>
     *
     * @param globalLevel   全服总数对照全局阈值的级别（0/1/2）
     * @param maxWorldLevel 本周期逐世界判定的最高级别（0/1/2；无覆盖世界时为 0）
     */
    static boolean shouldEmitGlobalEntitiesAlert(int globalLevel, int maxWorldLevel) {
        return globalLevel > maxWorldLevel;
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

        applyChunksLevels(snap, cfg, byWorld);
    }

    /**
     * 区块判定与告警生成（{@code collectChunks} 的判定半段，与 Bukkit 遍历解耦）。
     *
     * <p>拆分理由与 {@link #applyEntitiesLevels} 同：取值要 {@code Bukkit.getWorlds()} 与
     * {@code world.getLoadedChunks()}，测试 JVM 里驱动不了。而这里的 {@code >} 改成 {@code >=}
     * 同样全量用例零失败（实测）——1 万/1.5 万两个阈值上的边界行为此前完全裸奔。</p>
     *
     * <p>行为等价：档位仍走 {@link ThresholdOperator#CHUNKS_WORLD}（两档严格 {@code >}），
     * 并且<b>每个世界都判</b>（与实体的"仅覆盖世界"口径不同，这是刻意的，见下方长注释）。</p>
     *
     * @param snap    快照（写 {@code alerts} / {@code alertLevels}）
     * @param cfg     本周期配置
     * @param byWorld 世界名 → 已加载区块数
     */
    void applyChunksLevels(MetricsSnapshot snap, ConfigManager.ConfigData cfg,
                          Map<String, Integer> byWorld) {
        // v1.4.5：注意与 collectEntities 的逐世界判定**口径不同**，这是刻意的，不是遗漏。
        //
        // collectEntities 对没有 worlds 覆盖的世界 `continue`（不判定、不写 entities:world:<名>），
        // 因为它有"全服总数"口径（entities:global）兜底——没覆盖的世界的实体仍计入全服合计。
        //
        // 这里则**每个世界都判**，用全局阈值，因为 chunks **没有全服口径**：
        // Metric 枚举里只有 CHUNKS_WORLD、没有"全服区块数"这个告警指标
        //（见 Metric.ofKey 的注释："全服区块数不是告警指标，旧版本也从未告警过"）。
        // 若这里也跳过没覆盖的世界，那些世界的区块超限将**完全失声**——既没有逐世界告警，
        // 也没有全服告警可依托。
        //
        // 副效果：用户新增一个世界（自定义世界、插件生成世界）却不配 worlds 覆盖时，
        // chunks:world:<新世界> 会出现且用全局阈值判定，而 entities:world:<新世界> 不会出现。
        // 两条序列的键集合因此不对称——Prometheus 侧查这两族指标时的预期要分开写。
        for (Map.Entry<String, Integer> entry : byWorld.entrySet()) {
            String chunkKey = "chunks:world:" + entry.getKey();
            // 算子见 ThresholdOperator.CHUNKS_WORLD：两档严格比较（`>`），每个世界都判。
            // 这条此前零保护——改成 `>=` 全量用例一个都不红（实测）。
            int chunkLevel = ThresholdOperator.CHUNKS_WORLD.levelOf(entry.getValue(),
                cfg.chunks_loadedWarningThreshold, cfg.chunks_loadedCriticalThreshold);
            if (chunkLevel == 2) {
                addAlert(snap, Alert.measured(chunkKey, Alert.LEVEL_CRITICAL, entry.getValue(),
                    MetricsSnapshot.MODULE_CHUNKS, snap.timestamp));
            } else if (chunkLevel == 1) {
                addAlert(snap, Alert.measured(chunkKey, Alert.LEVEL_WARNING, entry.getValue(),
                    MetricsSnapshot.MODULE_CHUNKS, snap.timestamp));
            }
            recordLevel(snap, chunkKey, chunkLevel);
        }
    }

    // ==================== 内存 ====================

    private void collectMemory(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        Runtime runtime = Runtime.getRuntime();
        snap.heapMax = runtime.maxMemory();
        snap.heapTotal = runtime.totalMemory();
        snap.heapUsed = snap.heapTotal - runtime.freeMemory();
        // 百分比计算抽成 heapPercentFloor（纯函数，可单测），守卫与取整方向都收在那里。
        snap.heapUsagePercent = heapPercentFloor(snap.heapUsed, snap.heapMax);

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        snap.nonHeapUsed = memoryMXBean.getNonHeapMemoryUsage().getUsed();
        snap.nonHeapMax = memoryMXBean.getNonHeapMemoryUsage().getMax();

        // 算子见 ThresholdOperator.MEMORY：百分比指标两档都是闭区间（`>=`）。
        // 把 `>=` 改成 `>` 会让「恰好 95%」从 critical 掉回 warning。
        applyMemoryLevel(snap, cfg, snap.heapUsagePercent);
    }

    /**
     * 堆使用率百分比（<b>向下取整</b>，整数运算）。
     *
     * <h2>为什么不用 {@code (int) ((double) used / max * 100)}</h2>
     *
     * <p>那个写法有真实的浮点截断误差：精确的整数百分比会先被算成"少一点"，再被 {@code (int)}
     * 砍掉一格。实例：{@code max = 300000000, used = 87000000} 精确是 29%，但
     * {@code (double) 87000000 / 300000000} 得出 {@code 0.28999999999999998}，
     * 乘 100 后是 {@code 28.999999999999996}，{@code (int)} 截断成 <b>28</b>。</p>
     *
     * <p>这不是孤例：逐 MiB 扫描 {@code -Xmx} 256MiB..64GiB 的全部整数百分比点（67.9 万样本）后，
     * {@code p = 29 / 57 / 58} 三个值上少 1，共 7833 次。{@code -Xmx1024/2048/4096/8192M}
     * 这类 2^n 堆不受影响（分母是 2 的幂时可以精确表示），所以本地开发机永远复现不出来；
     * 默认阈值 85/95 也刚好免疫，于是它能一直藏着。</p>
     *
     * <p>改成 {@code 100L * used / max} 后是纯整数除法：2000 万组 fuzz 与精确有理数 floor
     * 零误差，500 万组 fuzz 与"真实比值 {@code >= T/100}"的判据零分叉。溢出也安全——
     * {@code 100L * 1TiB} 仍在 {@code long} 内。</p>
     *
     * <h2>为什么保持 floor，不改成四舍五入（别"顺手统一"）</h2>
     *
     * <p>{@code config.yml:124} 的内存段白纸黑字写着「G1 正常水位 <b>70-85%</b> 属健康区间」，
     * 即 85 是健康区间的上界。改成 {@code Math.round} 会让 {@code [84.5%, 85%)} 这个
     * <b>文档声明为健康</b>的区间读数变成 85 并开始告警——等于让健康读数报警。</p>
     *
     * <h2>为什么与 {@link MetricsSnapshot#playerPercent()} 的取整方向相反是刻意的</h2>
     *
     * <p>{@code playerPercent()} 用 {@code Math.round}（round-up 倾向）：players 的语义是
     * "满员告警应接近满员"，88/90 人就该读作 98% 而不是 97%，偏上去符合意图。
     * memory 的语义是"85 是健康上界"，偏下去才不会把健康区间算成告警。
     * <b>两者方向不同是因为语义不同，不是历史遗留，不要统一。</b></p>
     *
     * @param used 已用堆字节数
     * @param max  最大堆字节数；{@code <= 0} 时返回 0（不做除法）
     * @return 0-100 的百分比，向下取整
     */
    static int heapPercentFloor(long used, long max) {
        if (max <= 0) {
            return 0;
        }
        return (int) (100L * used / max);
    }

    /**
     * 堆内存占比的判定与告警生成（{@code collectMemory} 的判定半段）。
     *
     * <p>拆分理由与 {@link #applyTpsLevel} 同：{@code heapUsagePercent} 来自
     * {@code Runtime.getRuntime()} 的真实值，构造不出"恰好 95%"。</p>
     *
     * <p>行为等价：档位仍走 {@link ThresholdOperator#MEMORY}（两档闭区间 {@code >=}），
     * 告警参数不变。</p>
     *
     * @param percent 堆使用率百分比（调用方传入，便于测试构造边界值）
     */
    void applyMemoryLevel(MetricsSnapshot snap, ConfigManager.ConfigData cfg, int percent) {
        int level = ThresholdOperator.MEMORY.levelOf(
            percent, cfg.memory_heapWarningThreshold, cfg.memory_heapCriticalThreshold);
        String key = ThresholdOperator.MEMORY.key();
        if (level == 2) {
            addAlert(snap, Alert.measuredWithThresholds(key, Alert.LEVEL_CRITICAL, percent,
                    cfg.memory_heapCriticalThreshold, cfg.memory_heapCriticalThreshold, MetricsSnapshot.MODULE_MEMORY, snap.timestamp));
        } else if (level == 1) {
            addAlert(snap, Alert.measuredWithThresholds(key, Alert.LEVEL_WARNING, percent,
                    cfg.memory_heapWarningThreshold, cfg.memory_heapWarningThreshold, MetricsSnapshot.MODULE_MEMORY, snap.timestamp));
        }
        recordLevel(snap, key, level);
    }

    // ==================== CPU ====================

    /**
     * CPU 告警闩锁（迟滞）：解决"阈值边界锯齿"。
     *
     * <p><b>问题</b>：此前进入与退出共用同一个阈值（{@code load > threshold} 才告警，不满足即视为干净）。
     * CPU 读数的采样方差远大于阈值间距——本机受控实验实测 σ≈12 个百分点，
     * 样本在 55%–100% 之间摆动。于是"越界 3 个周期 → 未越界 3 个周期"每隔几秒重复一次，
     * 每条都走「恢复 → 复燃」，而复燃按设计绕过冷却，形成 6 小时 1333 条消息的锯齿刷屏。</p>
     *
     * <p><b>做法</b>：告警进入用 {@code threshold}，退出用 {@code threshold - cpu.hysteresis-margin}。
     * 落在中间带内的样本既不进入也不退出，保持上一次判定——这条带吸收了采样噪声，锯齿从源头消失。</p>
     *
     * <p><b>为什么不放在 AlertService</b>：该类约定"采集层判定阈值、决策层只管投递"（见其类注释），
     * 且 7 类指标的阈值方向不同（TPS 是低值告警），在决策层复制阈值比较会同时破坏分层和低值语义。</p>
     *
     * <p>线程：仅在 {@code collectJvmAsync} 的异步线程内读写，故用普通字段即可；
     * {@code volatile} 仅为 reload 重建后新任务读到一致的初值。</p>
     */
    private volatile boolean systemCpuLatched = false;
    private volatile boolean processCpuLatched = false;

    private void collectCPU(MetricsSnapshot snap, ConfigManager.ConfigData cfg) {
        OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
        snap.availableProcessors = osMXBean.getAvailableProcessors();

        if (osMXBean instanceof com.sun.management.OperatingSystemMXBean sunOsMXBean) {
            // P0-13：JDK 无数据时返回 -1（原样 *100 会得到误导性的 -100），转为 -1 哨兵
            //
            // ⚠️ 本机实测（Zulu 21 / Windows 10 / E5-2660 v2）：
            //   getCpuLoad()        p50 = 0.006ms   ← 极便宜（命中缓存时几乎为零）
            //   getProcessCpuLoad() p50 = 90.5ms    p90 = 139.8ms   max = 259.6ms
            // 后者慢 4 个数量级且**阻塞**（进程 CPU 仅 0.4%，不是计算开销）——
            // Windows 实现每次都枚举全部进程名。若在主线程调用，单次就吃 1.8 个 tick。
            // 本方法固定在 collectJvmAsync 的**异步线程**执行（见 start() 的 runAsyncTimer），
            // 故不影响 tick；但需知道它会让 JVM 采集节拍抖动（见下方 getCpuLoad 的窗口说明）。
            //
            // ⚠️ Windows 上直接跳过（2026-11-10 实测）：单次阻塞 p50 171ms / p90 257ms / max 332ms，
            // 90 秒内 69 次调用累计阻塞 12.7 秒 ≈ 该异步 worker 的 14.1%。而且它跑在 Bukkit
            // **共享**异步池上，阻塞会挤占同池其他插件的任务。宁可让进程 CPU 报"未知"，
            // 也不接受为读一个数阻塞这么久。判定与理由集中在 ServerCompat.isProcessCpuReliable()。
            // 注意哨兵：不可用时 p 直接取 -1 且**不再乘 100**（乘了会变成 -100，
            // 后面的 `p < 0 ? -1 : ...` 仍能兜住，但中间值语义会变得难读）。
            double p = ServerCompat.isProcessCpuReliable() ? sunOsMXBean.getProcessCpuLoad() * 100 : -1;
            // 窗口语义（OpenJDK 源码确认）：Windows 实现有 MIN_UPDATE_INTERVAL = 500ms 的硬门限，
            // 快于 500ms 的调用只返回缓存；且读数是"两次真实采集之间的增量占比"，
            // 即**窗口长度 ≈ 调用间隔**。所以 JVM 异步线程若因上文 90ms 调用而迟到，
            // 下一帧覆盖的窗口就变长——这是 CPU 采样方差大的一个物理来源，
            // 也是迟滞（hysteresis）必须在采集层做的原因之一。
            // 注：Windows 上已跳过 getProcessCpuLoad，"迟到"的来源随之消失；
            // getCpuLoad 本身命中缓存时近乎零成本，不会造成同类偏移。
            double s = sunOsMXBean.getSystemCpuLoad() * 100;
            snap.processCpuLoad = p < 0 ? -1 : round4(p);
            snap.systemCpuLoad = s < 0 ? -1 : round4(s);

            // 迟滞带宽度：配置项非法（负值/超过 50）时回退默认 5 个百分点。
            // 上限 50 的理由：margin 过大等于把退出线压到阈值一半以下，
            // 一次真实的持续过载会永远退不出去（告警挂着不退，比误报更糟）。
            double margin = cfg.cpu_hysteresisMargin;
            if (!(margin >= 0) || margin > 50) {
                margin = ConfigManager.CPU_HYSTERESIS_DEFAULT;
            }

            // 只排除负哨兵。0.0 是合法读数（空载服务器的 CPU 就是 0），用 > 0 会把它挡在
            // 告警判定与 recordLevel 之外——后果不只是"不判等级"，而是 cpu:process 这个
            // Prometheus 指标在空载服上整条不出现，运维用 absent() 检查会误以为采集挂了。
            // 把健康说成盲区，与把盲区说成健康是同样严重的错误。
            if (snap.processCpuLoad >= 0) {
                processCpuLatched = latched(
                    processCpuLatched, snap.processCpuLoad,
                    cfg.cpu_processWarningThreshold, margin);
                if (processCpuLatched) {
                    addAlert(snap, Alert.measuredWithThresholds("cpu:process", Alert.LEVEL_WARNING, snap.processCpuLoad,
                            cfg.cpu_processWarningThreshold, cfg.cpu_processWarningThreshold - margin,
                            MetricsSnapshot.MODULE_CPU, snap.timestamp));
                }
                // 级别跟闩锁走，不跟瞬时值：闩锁的存在就是为了让边界抖动不反复翻转，
                // 若这里改用瞬时值判定，指标会比告警本身抖得多，Prometheus 侧反而更吵。
                recordLevel(snap, "cpu:process", processCpuLatched ? 1 : 0);
            } else {
                // v1.6：不可判定必须显式说出来。Windows 上这里是常态（进程 CPU 不采），
                // 若靠"不写键"表达，告警层会把它读成"本周期不适用"并宣布恢复。
                recordLevel(snap, "cpu:process", MetricsSnapshot.ALERT_LEVEL_NO_VERDICT);
            }
            if (snap.systemCpuLoad >= 0) {
                systemCpuLatched = latched(
                    systemCpuLatched, snap.systemCpuLoad,
                    cfg.cpu_systemWarningThreshold, margin);
                if (systemCpuLatched) {
                    addAlert(snap, Alert.measuredWithThresholds("cpu:system", Alert.LEVEL_WARNING, snap.systemCpuLoad,
                            cfg.cpu_systemWarningThreshold, cfg.cpu_systemWarningThreshold - margin,
                            MetricsSnapshot.MODULE_CPU, snap.timestamp));
                }
                recordLevel(snap, "cpu:system", systemCpuLatched ? 1 : 0);
            } else {
                recordLevel(snap, "cpu:system", MetricsSnapshot.ALERT_LEVEL_NO_VERDICT);
            }
        } else {
            // 非 sun.* MXBean：无 CPU 数据，显式置 -1（避免默认 0 误导），并解除闩锁。
            // v1.6：两个键同样要写"没有判定结果"——这一段既不生成消息也不写键时，
            // 告警层会把它当成一次干净周期。
            snap.processCpuLoad = -1;
            snap.systemCpuLoad = -1;
            processCpuLatched = false;
            systemCpuLatched = false;
            recordLevel(snap, "cpu:process", MetricsSnapshot.ALERT_LEVEL_NO_VERDICT);
            recordLevel(snap, "cpu:system", MetricsSnapshot.ALERT_LEVEL_NO_VERDICT);
        }
    }

    /**
     * 单个指标的迟滞状态转移：进入用 {@code threshold}，退出用 {@code threshold - margin}。
     *
     * <p>落在 {@code [threshold - margin, threshold]} 的样本保持上一状态——
     * 这正是"不让边界噪声反复触发状态翻转"的全部机制。</p>
     *
     * <p><b>退出线下界（f-s04-logic-01）</b>：{@code margin} 与 {@code threshold} 是独立配置项，
     * 当 {@code margin >= threshold} 时裸减法得到非正退出线，而调用侧保证喂入值 {@code > 0}
     * （{@code collectCPU} 的哨兵过滤），于是 {@code value >= 非正数} 恒真、闩锁永不解除。
     * 这里显式兜底：迟滞带非正即视为无效，退回无迟滞判据（与 {@code margin = 0}
     * "关闭迟滞 = 旧行为"的声明同口径），保证闩锁一定会在负载回落后解除。
     * 仅钳到 0 不足以修复——{@code value >= 0} 对恒正输入仍然恒真。</p>
     *
     * <p><b>进入线的比较符取自算子表</b>（{@link ThresholdOperator#CPU_PROCESS}）：
     * 该字形同时也是 {@code alert.threshold.cpu} 文案里那个 {@code >}，
     * 而展示层 {@code ThresholdColors} 判 CPU 颜色时读的同一条规则。
     * 三处（文案 / 告警闩锁 / 颜色）因此不可能各自漂移——此前它们各写一个 {@code >}。</p>
     */
    private static boolean latched(boolean latched, double value, double threshold, double margin) {
        double exit = threshold - margin;
        // 迟滞带非正（margin >= threshold）即视为无效：退回无迟滞判据，避免负/零退出线造成永不解锁
        if (exit <= 0.0d) {
            return ThresholdOperator.CPU_PROCESS.trips(value, threshold);
        }
        return latched ? value >= exit : ThresholdOperator.CPU_PROCESS.trips(value, threshold);   // 带内保持上一状态
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
        // 9A-20 #6 / 9A-22 #9：原此处每周期调用 Bukkit.getScheduler().getPendingTasks().size()——
        // getPendingTasks() 会构造完整的 PendingTask 列表（默认 1 秒一周期），而 pendingTasks 字段
        // 全仓零读取。字段与赋值一并删除；status 需要调度器积压时按需现算（不在采集热路径）。
    }

    // ==================== 公共访问方法 ====================

    public MetricsSnapshot getLatest() {
        return history.peekLast();
    }

    public List<MetricsSnapshot> getHistory() {
        return new ArrayList<>(history);
    }

    public int getHistorySize() {
        return historyCount;
    }

    // ==================== 9A-20 #1：采集存活/新鲜度 ====================

    /**
     * 最近一次成功采集的完成时刻（epoch ms）；{@code 0} 表示启动后尚未成功过一次。
     *
     * <p>命令层/报告层应以此判断"屏幕上的数据是哪一刻的"，而不是看快照自带的
     * {@code timestamp}——后者只能回答"这条数据产生于何时"，回答不了"之后还采过没有"。</p>
     */
    public long lastCollectAtMs() {
        return lastCollectAtMs;
    }

    /** 连续采集周期失败次数（成功一次即清零）；{@code >0} 说明采集正在挣扎。 */
    public int consecutiveCollectFailures() {
        return consecutiveCollectFailures;
    }

    /** 采集是否已被判定为"停止工作"（周期级未捕获异常使 Bukkit 取消任务 / 连续失败达阈值）。成功一次即清除。 */
    public boolean isCollectorDead() {
        return collectorDead;
    }

    /** 最近一次采集周期级异常的单行描述（{@code null} = 无）。供 /perfscope status 展示根因。 */
    public String lastCollectError() {
        return lastCollectError;
    }

    /** 当前连续失败序列的起点时刻（epoch ms）；{@code 0} = 当前不在失败序列中。 */
    public long firstFailureAtMs() {
        return firstFailureAtMs;
    }

    /**
     * 采集是否已"过期"（9A-20 #1，本项最重要的一条）。
     *
     * <p>判定：{@code now - lastCollectAtMs > 3 × update_interval}。默认间隔 20 tick（1000ms）
     * → 超过 3 秒没有新快照即视为过期。3 倍而非 1 倍是给调度抖动/主线程卡顿留余量
     * （服务器卡顿时采集晚到一两秒不应误报；但采集真的死了要尽快说）。</p>
     *
     * <p><b>从未成功过</b>（{@code lastCollectAtMs == 0}）时以 {@code start()} 时刻为基准，
     * 宽限期取 {@link #WARMUP_WINDOW_MS}（10 秒，与 noData 文案"超过 10 秒请查看控制台"同一口径）——
     * 既不让插件每次启动都先红一次，也不留下"首轮采集就死了但永远不报"的盲区。</p>
     *
     * <p><b>接线提示</b>：本方法只暴露事实，不改变任何既有输出。命令层应在每个子命令的首行
     * （或 showAll 头部）调用并渲染红字"数据已停止更新 X 分 Y 秒（最后采集 HH:mm:ss）"；
     * 报告层 health 段应增加 stale 标记。此项由编排者统一接入
     * （9A-20 本批已接：命令层 /perfscope status 与 PerfScope 的看门狗）。</p>
     */
    public boolean isStale() {
        long last = lastCollectAtMs;
        if (last > 0L) {
            return System.currentTimeMillis() - last > staleThresholdMs();
        }
        return System.currentTimeMillis() - startedAtMs > Math.max(WARMUP_WINDOW_MS, staleThresholdMs());
    }

    /**
     * 连续失败了多少个采集周期（成功一次即清零）。
     *
     * <p>供跨服上报判断"采集现在是不是坏了"。**不要用 {@link #isStale()} 代替**：本类的
     * 定时任务在成功路径上先刷新 {@code lastCollectAtMs} 再回调，所以那一刻 {@code isStale()}
     * 必然为 false，拿它当"采集是否健康"会恒真（这正是一次真实缺陷的成因）。</p>
     */
    public int consecutiveFailures() {
        return consecutiveCollectFailures;
    }

    /** stale 判定阈值（毫秒）：3 × 采集间隔，最小 3 秒（防 interval=1 tick 时 150ms 的过敏感判定）。 */
    public long staleThresholdMs() {
        ConfigManager.ConfigData cfg = configManager.get();
        long intervalTicks = cfg == null ? 20L : Math.max(1L, cfg.general_updateInterval);
        long intervalMs = intervalTicks * 50L;   // 1 tick = 50ms
        return Math.max(3000L, intervalMs * 3L);
    }

    /**
     * 采集耗时的分布统计（v1.3.0 -04）。
     *
     * <p>返回最近 {@link #COLLECT_DURATION_WINDOW} 个周期的 p50 / p95 / max / 样本数，
     * 以及累计超 1 tick 的周期数。样式与 {@code /perfscope trend} 的百分位口径一致。</p>
     *
     * <p><b>为什么是分布而不是单值</b>：采集是分级降频的（实体/区块每 5 周期、方块实体每 7/10、
     * 热点每 30），四个重模块的最小公倍数决定了会周期性"撞车"。只看某一周期的值，恰好在撞车拍
     * 看到 66ms 就会误判为"每秒 66ms"。p50 反映常态、p95 与 max 反映撞车拍、超阈值计数反映
     * 问题频率——三者缺一都会误导。</p>
     *
     * @return 长度 5 的数组：{p50, p95, max, samples, overBudget}；无样本时前四项为 -1/0
     */
    public long[] collectDurationStats() {
        Long[] arr = collectDurations.toArray(new Long[0]);
        if (arr.length == 0) {
            return new long[]{-1L, -1L, -1L, 0L, 0L};
        }
        long[] vals = new long[arr.length];
        int over = 0;
        for (int i = 0; i < arr.length; i++) {
            vals[i] = arr[i] == null ? 0L : arr[i];
            if (vals[i] > COLLECT_DURATION_WARN_MS) {
                over++;
            }
        }
        java.util.Arrays.sort(vals);
        // 超阈计数**从窗口内现算**，不用累计计数器。
        //
        // 此前用的是 overBudgetCycles（只增不减的累计值），但 dur[3] 是窗口样本数（上限 300）。
        // 两者分母不同：跑 1 小时后累计 50 次超阈 ÷ 300 窗口样本 = 16.7%，而实际可能是
        // 早期的几次、当前早已不再发生。用户看到的是"越跑越糟"的假象。
        // 现算保证分子分母同源，比例永远反映最近 COLLECT_DURATION_WINDOW 个周期。
        return new long[]{
            vals[(int) Math.floor((vals.length - 1) * 0.50)],
            vals[(int) Math.floor((vals.length - 1) * 0.95)],
            vals[vals.length - 1],
            vals.length,
            over,
        };
    }

    /** 单次采集的告警阈值（毫秒）。供命令层渲染"超阈 N 次 / 阈值 X ms"。 */
    public static long collectDurationWarnMs() {
        return COLLECT_DURATION_WARN_MS;
    }

    /** 距今多久没有新数据（毫秒）：基准是最后一次成功采集；从未成功过则取 start()/reload 时刻。 */
    public long staleForMs() {
        long last = lastCollectAtMs;
        long ref = last > 0L ? last : startedAtMs;
        return Math.max(0L, System.currentTimeMillis() - ref);
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

    /**
     * 记录一个告警键的当前级别（含健康时的 0），供 Prometheus 导出（v1.4）。
     *
     * <p><b>为什么与 {@link #addAlert} 分开而不是合并</b>：{@code addAlert} 只在越阈值时被调用，
     * 而这里要求「健康」也是一条可观测事实。两者语义不同——一个是"有事要报"，
     * 一个是"这个维度现在是什么状态"——合并会让调用点难以表达"判定过了但没超阈"。</p>
     *
     * <p>调用约定：<b>每个判定点必须调用恰好一次</b>，无论是越阈还是正常。
     * 漏调 = 该键从全集消失 = 运维看到序列消失，误以为是恢复。</p>
     *
     * <p><b>不可判定的维度也必须调用</b>（传 {@link MetricsSnapshot#ALERT_LEVEL_NO_VERDICT}），
     * 不能靠"不调用"来表达。原因：键缺席还有第二种含义——该维度本周期<b>不适用</b>
     * （世界被卸载后 {@code chunks:world:<名>} 不再出现），告警层据此正常恢复。
     * 把"没测"也写成缺席，就等于把"没测"谎报成"不适用"。</p>
     *
     * @param key   与 {@link Alert#key()} 同一命名（{@code metric:scope}）
     * @param level {@code 0} 正常 / {@code 1} 警告 / {@code 2} 严重 /
     *              {@link MetricsSnapshot#ALERT_LEVEL_NO_VERDICT} 本周期不可判定
     */
    private static void recordLevel(MetricsSnapshot snap, String key, int level) {
        snap.alertLevels.put(key, level);
    }

    /**
     * 两位小数舍入。
     *
     * <p>刻意是 {@code static}：{@link #reduceTickTimes} 是纯静态方法，不该为了调一个
     * 不读实例状态的辅助方法而先造一个 collector。</p>
     *
     * <p><b>已知边界（{@code CollectorFormattingTest} 有专门用例记录，不修正）</b>：
     * {@code round2(NaN) == 0.0}（{@code Math.round(NaN)} 是 0）、
     * {@code round2(Infinity)} 饱和到 {@code 9.2e16}、
     * 负数半值与正数不对称。当前所有调用点都有前置守卫，所以都不可达。</p>
     */
    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}