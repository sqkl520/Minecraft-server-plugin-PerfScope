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

    // ==================== Wave 8C 热点区块缓存（仅主线程 collectSync 访问，无需同步） ====================
    // 复用语义与实体/区块缓存一致：命中采样倍数时全遍历刷新，其余周期复用缓存值（含 unavailable 标记）
    private boolean hotspotsCacheReady = false;
    private boolean hotspotsCacheUnavailable = false;
    private List<ChunkHotspot> cachedHotspots = new ArrayList<>();

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
        // 9A-10 #1：开启新一代。stop() 已经自增过一次，这里再自增，确保"旧任务捕获的代际"
        // 与新任务捕获的代际之间至少隔一代（cancel 期间仍在飞的旧任务一律作废）。
        long gen = ++generation;
        // 重置降频计数与缓存，使 reload 后首个周期立即重新采集，不复用旧快照数据
        tickCounter = 0;
        entitiesCacheReady = false;
        chunksCacheReady = false;
        teCacheReady = false;
        hotspotsCacheReady = false;
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

        // L-2 修复：interval ≤0 会被 Bukkit 调度器 coerce 成单次任务（负值 period=-1 跑一轮即静默死亡）
        // 或每 tick（0 → 1），Folia 下则被 SchedulerAdapter 校正为每 tick，行为分叉，
        // 故强制下限 1（对齐 AlertService.start 已有范式），非法配置同时记 warning 便于诊断
        int configuredInterval = configManager.get().general_updateInterval;
        if (configuredInterval <= 0) {
            plugin.getLogger().warning("[PerfScope] general.update_interval=" + configuredInterval
                + " 非法（需 ≥1），已按 1 tick 处理");
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

        history.addLast(snap);
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
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
        snap.entitiesByType = freezeMap(snap.entitiesByType);
        snap.entitiesByWorld = freezeMap(snap.entitiesByWorld);
        snap.chunksByWorld = freezeMap(snap.chunksByWorld);
        snap.forceLoadedChunksByWorld = freezeMap(snap.forceLoadedChunksByWorld);
        snap.tileEntitiesByWorld = freezeMap(snap.tileEntitiesByWorld);
        snap.alerts = List.copyOf(snap.alerts);
        snap.activeWarnings = List.copyOf(snap.activeWarnings);
        snap.activeCriticals = List.copyOf(snap.activeCriticals);
        snap.topEntityChunks = List.copyOf(snap.topEntityChunks);
        snap.unavailable = Collections.unmodifiableSet(new LinkedHashSet<>(snap.unavailable));
        snap.warmingUp = Collections.unmodifiableSet(new LinkedHashSet<>(snap.warmingUp));
        snap.disabledModules = Collections.unmodifiableSet(new LinkedHashSet<>(snap.disabledModules));
    }

    /** 9A-10 #3：保留插入序的不可变 Map 副本（元素均为不可变类型，浅拷贝即足够）。 */
    private static Map<String, Integer> freezeMap(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
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
            plugin.getLogger().info("[PerfScope] 采集已恢复（此前连续失败 " + failedBefore + " 次"
                + (firstAt > 0 ? "，首次失败 " + formatClock(firstAt) : "")
                + "，恢复于 " + formatClock(lastCollectAtMs) + "）");
        } else {
            collectorDead = false;
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
            String detail = "[PerfScope] 采集周期异常（已连续失败 " + failures + " 次，首次失败 "
                + formatClock(firstFailureAtMs) + "）: " + lastCollectError;
            if (configManager.get() != null && configManager.get().general_debug) {
                plugin.getLogger().log(Level.SEVERE, detail + " —— 数据可能已停止更新，/perfscope status 可查", t);
            } else {
                plugin.getLogger().severe(detail);
            }
        }
        if (severe) {
            plugin.getLogger().severe("[PerfScope] 采集已连续失败 " + failures
                + " 次，/perfscope 显示的数据可能已过期。请执行 /perfscope reload，"
                + "若仍无效请重启服务器（期间可用 /perfscope status 查看最近错误）");
        }
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
                plugin.getLogger().warning("[PerfScope] JVM 异步采集周期异常（已限频记录，JVM 指标可能停滞）: "
                    + describeThrowable(t));
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
        boolean disabled = false;

        if (!cfg.entities_enabled) {   // 配置禁用：与 runOrMark 语义一致，标记不可用
            unavailable = true;
            disabled = true;   // 9A-20 #3：同时记入 disabledModules（区分"服主关掉"与"采集坏了"）
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
            }
        }

        // 9A-10 #1：发布点再校验——采集过程中可能已发生 reload，此时上述结果属于上一代配置
        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        entitiesCacheReady = ready;
        entitiesCacheUnavailable = unavailable;
        entitiesCacheDisabled = disabled;
        cachedTotalEntities = total;
        cachedEntitiesByType = byType;
        cachedEntitiesByWorld = byWorld;
        cachedEntityAlerts = alertList;
        cachedEntityWarnings = warningList;
        cachedEntityCriticals = criticalList;
    }

    private void applyEntitiesCache(MetricsSnapshot snap) {
        if (!entitiesCacheReady || entitiesCacheUnavailable) {
            snap.unavailable.add(MetricsSnapshot.MODULE_ENTITIES);
            if (entitiesCacheDisabled) {
                snap.disabledModules.add(MetricsSnapshot.MODULE_ENTITIES);
            }
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
        boolean disabled = false;

        if (!cfg.chunks_enabled) {
            unavailable = true;
            disabled = true;   // 9A-20 #3
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
            }
        }

        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        chunksCacheReady = true;
        chunksCacheUnavailable = unavailable;
        chunksCacheDisabled = disabled;
        cachedTotalLoadedChunks = totalLoaded;
        cachedTotalForceLoadedChunks = totalForceLoaded;
        cachedChunksByWorld = byWorld;
        cachedForceLoadedChunksByWorld = forceByWorld;
        cachedChunkAlerts = alertList;
        cachedChunkWarnings = warningList;
        cachedChunkCriticals = criticalList;
    }

    private void applyChunksCache(MetricsSnapshot snap) {
        if (!chunksCacheReady || chunksCacheUnavailable) {
            snap.unavailable.add(MetricsSnapshot.MODULE_CHUNKS);
            if (chunksCacheDisabled) {
                snap.disabledModules.add(MetricsSnapshot.MODULE_CHUNKS);
            }
            return;
        }
        snap.totalLoadedChunks = cachedTotalLoadedChunks;
        snap.totalForceLoadedChunks = cachedTotalForceLoadedChunks;
        snap.chunksByWorld = copyMap(cachedChunksByWorld);                               // 9A-10 #3
        snap.forceLoadedChunksByWorld = copyMap(cachedForceLoadedChunksByWorld);          // 9A-10 #3
        snap.alerts.addAll(cachedChunkAlerts);
        snap.activeWarnings.addAll(cachedChunkWarnings);
        snap.activeCriticals.addAll(cachedChunkCriticals);
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

        if (!cfg.tileEntities_enabled) {
            unavailable = true;
            disabled = true;   // 9A-20 #3
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
        cachedTileEntityCount = count;
        cachedTileEntitiesByWorld = byWorld;
    }

    private void applyTileEntitiesCache(MetricsSnapshot snap) {
        if (!teCacheReady || teCacheUnavailable) {
            snap.unavailable.add(MetricsSnapshot.MODULE_TILE_ENTITIES);
            if (teCacheDisabled) {
                snap.disabledModules.add(MetricsSnapshot.MODULE_TILE_ENTITIES);
            }
            return;
        }
        snap.tileEntityCount = cachedTileEntityCount;
        snap.tileEntitiesByWorld = copyMap(cachedTileEntitiesByWorld);   // 9A-10 #3
    }

    // ==================== Wave 8C 热点区块（Top5 实体密度，含玩家） ====================

    /**
     * 热点区块节流入口（Wave 8C）：每 {@code hotspots.sample-every-cycles} 个采集周期全遍历一次，
     * 其余周期复用缓存（与实体/区块缓存同范式）。全遍历成本高（全部世界全部已加载区块），
     * 节流是主线程保护的第一要务。
     */
    private void collectHotspotsThrottled(MetricsSnapshot snap, ConfigManager.ConfigData cfg, long taskGen) {
        long every = Math.max(1, cfg.hotspots_sampleEveryCycles);
        if (!hotspotsCacheReady || tickCounter % every == 0) {
            refreshHotspotsCache(cfg, taskGen);
        }
        applyHotspotsCache(snap);
    }

    /** 刷新热点缓存（9A-10 #1：入口 + 发布点各校验一次代际）。 */
    private void refreshHotspotsCache(ConfigManager.ConfigData cfg, long taskGen) {
        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        boolean unavailable;
        List<ChunkHotspot> hotspots = List.of();
        boolean disabled = false;

        if (!cfg.hotspots_enabled) {
            unavailable = true;
            disabled = true;   // 9A-20 #3
        } else {
            MetricsSnapshot tmp = new MetricsSnapshot();
            safeCollect(MetricsSnapshot.MODULE_HOTSPOTS, tmp, () -> collectHotspots(tmp));
            unavailable = tmp.unavailable.contains(MetricsSnapshot.MODULE_HOTSPOTS);
            if (!unavailable) {
                hotspots = List.copyOf(tmp.topEntityChunks);   // 9A-10 #3
            }
        }

        if (!isCurrentGeneration(taskGen)) {
            return;
        }
        hotspotsCacheReady = true;
        hotspotsCacheUnavailable = unavailable;
        hotspotsCacheDisabled = disabled;
        cachedHotspots = hotspots;
    }

    private void applyHotspotsCache(MetricsSnapshot snap) {
        if (!hotspotsCacheReady || hotspotsCacheUnavailable) {
            snap.unavailable.add(MetricsSnapshot.MODULE_HOTSPOTS);
            if (hotspotsCacheDisabled) {
                snap.disabledModules.add(MetricsSnapshot.MODULE_HOTSPOTS);
            }
            return;
        }
        snap.topEntityChunks = new ArrayList<>(cachedHotspots);   // 9A-10 #3：每次快照独立副本
    }

    /**
     * 全量热点扫描（Wave 8C，运行在采样周期的主线程）：遍历全部世界全部已加载区块，
     * 统计每区块实体数（{@code chunk.getEntities()}，含玩家——V1.2 §5 口径）并取全服 Top5；
     * 同时统计各区块主导实体类型（众数，平票取字典序最小者保证输出稳定）。
     *
     * <p>性能：单次全遍历的区块数与实体数成正比；由调用方按 sample-every-cycles 节流
     * （默认 30 周期 ≈ 30 秒），异常由 safeCollect 兜底（模块级 unavailable）。</p>
     */
    private void collectHotspots(MetricsSnapshot snap) {
        List<ChunkHotspot> all = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            String worldName = world.getName();
            for (Chunk chunk : world.getLoadedChunks()) {
                Entity[] entities = chunk.getEntities(); // 单次取得，长度即计数（含玩家）
                if (entities.length == 0) {
                    continue; // 空区块不进候选
                }
                // 主导类型：单遍统计（实体数通常很小，HashMap 开销可忽略）
                Map<String, Integer> byType = new HashMap<>();
                for (Entity entity : entities) {
                    byType.merge(entity.getType().name(), 1, Integer::sum);
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
                all.add(new ChunkHotspot(worldName, chunk.getX(), chunk.getZ(),
                    entities.length, dominant, dominantCount));
            }
        }
        // 全服 Top5（降序）；平票按世界名+坐标排序保证输出稳定
        all.sort(Comparator.comparingInt(ChunkHotspot::entityCount).reversed()
            .thenComparing(ChunkHotspot::world)
            .thenComparingInt(ChunkHotspot::chunkX)
            .thenComparingInt(ChunkHotspot::chunkZ));
        if (all.size() > 5) {
            all = new ArrayList<>(all.subList(0, 5));
        }
        snap.topEntityChunks = all;
    }

    // ==================== P0-1 JVM 异步缓存读取 ====================

    /**
     * 将异步 JVM 缓存填入当前快照（P0-1）。
     * 缓存为空（启动初期异步尚未产出）时，内存/CPU/GC 标记 unavailable，避免 0 值误导或 NPE；
     * server 的 Bukkit 部分已在主线程采集，JVM 部分（运行时长/线程数）短暂为默认值，下一周期即补上。
     */
    private void applyJvmCache(MetricsSnapshot snap) {
        applyJvmCache(snap, configManager.get());
    }

    /**
     * 9A-10 #2：接受调用方传入的 cfg，避免在采集过程中回读配置。
     * reload 期间 configManager.get() 已换新引用，若此处再读一次，同一份快照的
     * "JVM 段"就会用新一代的 enabled 判定，与前半段（旧阈值）混用两代配置。
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
        snap.alerts.addAll(jvm.alerts);
        snap.activeWarnings.addAll(jvm.activeWarnings);
        snap.activeCriticals.addAll(jvm.activeCriticals);
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
        plugin.getLogger().info("[PerfScope] 采集模块已恢复: " + module
            + "（此前连续失败 " + failedTimes + " 次"
            + (firstAt > 0 ? "，首次失败 " + formatClock(firstAt) : "") + "）");
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
        String detail = "[PerfScope] 采集模块失败: " + module + ": " + h.lastMessage
            + "（已连续失败 " + h.consecutiveFailures + " 次，首次失败 "
            + formatClock(h.firstFailureAtMs) + "；同类日志 5 分钟内不重复记）";
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
            return "unknown";
        }
        String msg = t.getMessage();
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
            snap.msptSource = MetricsSnapshot.MSPT_SOURCE_TICK_TIMES;
        } catch (Exception e) {
            collectMSPT_BukkitAvg(snap);
        }
    }

    /**
     * Paper: getAverageTickTime() 获取平均 MSPT（反射方法已在构造时缓存）。
     * 9A-0：该路径只有平均值，**没有** min/max——两者置 -1 哨兵（展示层渲染"未采集"），
     * 绝不把平均值复制给 min/max 伪造极值区间。
     */
    private void collectMSPT_BukkitAvg(MetricsSnapshot snap) {
        if (avgTickMethod == null) {
            collectMSPT_TPSFallback(snap);
            return;
        }
        try {
            Object server = Bukkit.getServer();
            double mspt = ((Number) avgTickMethod.invoke(server)).doubleValue();
            snap.msptAvg = round2(mspt);
            snap.msptMin = -1;
            snap.msptMax = -1;
            snap.msptSource = MetricsSnapshot.MSPT_SOURCE_AVG_TICK;
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
        // 9A-0：反推值没有极值信息，min/max 置 -1（"未采集"），不得复制平均值
        snap.msptMin = -1;
        snap.msptMax = -1;
        snap.msptSource = MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK;
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
        // 9A-10 #2：WorldSettings 必须从**传入的 cfg** 取，不能再走 configManager.getWorldSettings()
        //（后者内部读 configManager 的当前引用——reload 时会让"逐世界判定"用新一代阈值，
        // 而"全服总数判定"用旧一代阈值，同一份快照混用两代配置）。
        for (Map.Entry<String, Integer> entry : byWorld.entrySet()) {
            ConfigManager.WorldSettings ws = worldSettingsFrom(cfg, entry.getKey());
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
        return history.size();
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

    /** stale 判定阈值（毫秒）：3 × 采集间隔，最小 3 秒（防 interval=1 tick 时 150ms 的过敏感判定）。 */
    public long staleThresholdMs() {
        ConfigManager.ConfigData cfg = configManager.get();
        long intervalTicks = cfg == null ? 20L : Math.max(1L, cfg.general_updateInterval);
        long intervalMs = intervalTicks * 50L;   // 1 tick = 50ms
        return Math.max(3000L, intervalMs * 3L);
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

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}