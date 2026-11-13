/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsSnapshot;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * {@link AlertService} 的真实状态机测试夹具（测试作用域，不是 {@code @Test}）。
 *
 * <p><b>要解决的两个问题</b>：</p>
 * <ol>
 *   <li>真实构造器第 354 行调用 {@code ServerCompat.isFolia()}，而 {@code ServerCompat} 的静态块
 *       要读 {@code Bukkit.getBukkitVersion()}——无服务端进程必然 NPE。因此用
 *       {@code sun.misc.Unsafe.allocateInstance} 绕过构造器，再用反射注入字段。
 *       （与 {@code DailyCounterRolloverTest} 同一路数；本类把它扩展成"能跑完整 onSnapshot"的版本。）</li>
 *   <li>{@code allocateInstance} 只分配内存、<b>不执行任何字段初始化器</b>。所以
 *       {@code cooldowns}/{@code activeAlerts}/… 这 14 个 {@code final} 容器与
 *       {@code stateWriter} 全是 {@code null}，必须逐个补上——这是"能构造出实例"与
 *       "能真正跑状态机"之间的全部差距。</li>
 * </ol>
 *
 * <h2>时钟策略：不注入时钟，而是伪造"多久以前"</h2>
 *
 * <p>{@code onSnapshot} 内部唯一的时间输入是 {@code System.currentTimeMillis()}，
 * 而冷却 / 复燃 / 抖动窗口 / 恢复防抖 / unknown-after-minutes / 保留期这六处判定，
 * 读的都是 <b>某个历史时间戳与 now 的差值</b>。于是不必拦截时钟，只要把种子写进
 * 状态表：</p>
 * <ul>
 *   <li>想让"300 秒冷却已过期"→ {@code seedCooldown(key, 301_000)}；</li>
 *   <li>想让"刚刚恢复过、仍在 180 秒抖动窗口内"→ {@code seedLastResolved(key, 1_000)}。</li>
 * </ul>
 *
 * <p><b>陷阱（必须处理）</b>：{@code onSnapshot} 第一段有防重复消费的守卫
 * （{@code snap.timestamp == lastConsumedSnapshotTs} 即静默丢弃）。同一个毫秒构造两份快照
 * 会让第二次调用凭空消失，测试会得到"少了一次推送"的假结论。故本夹具的 {@link #feed}
 * 用单调递增计数器生成时间戳，保证每份快照唯一。</p>
 *
 * <p><b>绝不调用 {@code loadState()}</b>：保持 {@code stateLoaded=false}，
 * 这样 {@code persistState()} 直接早退，测试不会碰磁盘状态文件、也不会读到旧快照。</p>
 *
 * <p>{@code serverTag} 预置为字面量，{@code serverTag()} 因此永不触达 {@code Bukkit}。</p>
 */
public final class AlertServiceStateMachineFixture {

    /** 夹具默认数据目录前缀（临时目录，避免污染仓库工作区）。 */
    private static final String TEMP_PREFIX = "pscope-alert-fixture";

    private final AlertService service;
    private final PerfScope plugin;
    private final ConfigManager configManager;
    private final ConfigManager.ConfigData config;

    /** 注入进 {@link AlertService} 的同一份容器引用——种子写这边，状态机读那边。 */
    private final Map<String, Long> cooldowns;
    private final Map<String, Object> lastResolvedAt;

    /** 快照时间戳发生器（防重复消费守卫要求全局唯一）。 */
    private long snapshotSeq = 0L;
    private final long timestampBase = System.currentTimeMillis();

    // ==================== 构造 ====================

    private AlertServiceStateMachineFixture(Builder b) throws Exception {
        File dataDir = Files.createTempDirectory(TEMP_PREFIX).toFile();

        PerfScope p = (PerfScope) alloc(PerfScope.class);
        setField(PerfScope.class, p, "logger", Logger.getLogger("perfscope-alert-fixture"));
        setField(PerfScope.class, p, "dataFolder", dataDir);

        ConfigManager cm = (ConfigManager) alloc(ConfigManager.class);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        // 缺省关闭两个聊天通道：投递路径因此纯内存，既不需要 Bukkit 也不需要玩家。
        // 计数器仍会走 emit() 的 counted 分支（pushFailToday++），pushedToday 照常累加。
        cfg.alerts_enabled = true;
        cfg.alerts_console = false;
        cfg.alerts_in_game = false;
        cfg.alerts_notifyRecovery = true;
        cfg.alerts_cooldownSeconds = b.cooldownSeconds;
        cfg.alerts_debounceCycles = b.debounceCycles;
        cfg.general_updateInterval = 20;
        cfg.memory_heapWarningThreshold = 85;
        cfg.memory_heapCriticalThreshold = 95;
        cfg.tps_warningThreshold = 18.0;
        cfg.tps_criticalThreshold = 15.0;
        setField(ConfigManager.class, cm, "plugin", p);
        setField(ConfigManager.class, cm, "config", cfg);
        setField(PerfScope.class, p, "configManager", cm);

        AlertService svc = (AlertService) alloc(AlertService.class);
        setField(AlertService.class, svc, "plugin", p);
        setField(AlertService.class, svc, "configManager", cm);
        setField(AlertService.class, svc, "webhookNotifier", null);
        // 预置字面量：serverTag() 的缓存命中路径，永不触达 Bukkit.getServer()
        setField(AlertService.class, svc, "serverTag", b.serverTag);
        // 关键：不调用 loadState()，persistState() 因 stateLoaded=false 直接早退
        setField(AlertService.class, svc, "stateLoaded", false);
        setField(AlertService.class, svc, "lastConsumedSnapshotTs", -1L);
        setField(AlertService.class, svc, "options", options(b));
        // 启动抑制窗口关闭：startupSuppressed() 恒 false，免得 tps: 键被静音
        setField(AlertService.class, svc, "startupSuppressEnabled", false);
        setField(AlertService.class, svc, "startupSuppressUntilMs", 0L);
        setField(AlertService.class, svc, "startupSuppressSeconds", 0);
        setField(AlertService.class, svc, "refireMinIntervalMs", b.refireSeconds * 1000L);
        setField(AlertService.class, svc, "flapWindowMs", b.flapWindowSeconds * 1000L);
        // 0 = 关闭"数值变化幅度"这层抑制，让判定只剩冷却一条，用例才可预测
        setField(AlertService.class, svc, "repushDelta", 0.0d);
        setField(AlertService.class, svc, "pushedTodayDayEpoch", LocalDate.now().toEpochDay());

        // ---- allocateInstance 跳过的 final 字段初始化器，逐个补 ----
        ExecutorService writer = new InlineExecutor();
        setField(AlertService.class, svc, "stateWriter", writer);

        Map<String, Long> cd = new ConcurrentHashMap<>();
        Map<String, Object> resolved = new ConcurrentHashMap<>();
        setField(AlertService.class, svc, "cooldowns", cd);
        setField(AlertService.class, svc, "lastResolvedAt", resolved);
        setField(AlertService.class, svc, "activeAlerts", new ConcurrentHashMap<>());
        setField(AlertService.class, svc, "debounceCounts", new ConcurrentHashMap<>());
        setField(AlertService.class, svc, "recoveryCounts", new ConcurrentHashMap<>());
        setField(AlertService.class, svc, "flapCounts", new ConcurrentHashMap<>());
        setField(AlertService.class, svc, "flapHealCycles", new ConcurrentHashMap<>());
        setField(AlertService.class, svc, "cleanRunStartMs", new ConcurrentHashMap<>());
        setField(AlertService.class, svc, "recentValues", new ConcurrentHashMap<>());
        setField(AlertService.class, svc, "suspendedAlerts", new ConcurrentHashMap<>());
        setField(AlertService.class, svc, "moduleDownSince", new ConcurrentHashMap<>());
        Set<String> flapSuppressed = ConcurrentHashMap.newKeySet();
        setField(AlertService.class, svc, "flapSuppressed", flapSuppressed);
        Deque<Object> recent = new ArrayDeque<>();
        setField(AlertService.class, svc, "recentEvents", recent);
        Map<String, LongAdder> counters = new ConcurrentHashMap<>();
        setField(AlertService.class, svc, "alertEventCounters", counters);

        this.service = svc;
        this.plugin = p;
        this.configManager = cm;
        this.config = cfg;
        this.cooldowns = cd;
        this.lastResolvedAt = resolved;
    }

    // ==================== 对外入口 ====================

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 文案层初始化：与既有测试同一组模块（告警/配置/诊断）。
     *
     * <p><b>为什么传一个非 null 的 plugin</b>：{@code Messages.load(null)} 走的是"无插件"早退分支，
     * 直接发布 {@code EN_DEFAULTS} —— 那个表只含基础键，各模块（{@code alert.*}/{@code diag.*}/
     * {@code config.*}）的表<b>从未被合并</b>，{@code Messages.get} 于是返回键名本身。
     * 既有测试只断言"键名/令牌"，看不出来；但本夹具的用例要读渲染后的 detail/title，
     * 拿到 {@code "diag.DIAG-MEM-LEAK.detail"} 就毫无意义了。</p>
     *
     * <p>传入一个"只有一个 logger"的裸 plugin，{@code load()} 就会先 {@code touchModules()} +
     * {@code mergeModules()} 再发布快照，模块键因此到位。末尾的
     * {@code applyCommandPermissionMessage} 会因为没有 Bukkit 服务端而抛异常——
     * 那一步在快照发布<b>之后</b>，故整体包在 try 里忽略即可，JSON 不受影响。</p>
     */
    public static void initMessages() throws Exception {
        Class.forName("cn.perfscope.alert.AlertMessages").getMethod("init").invoke(null);
        Class.forName("cn.perfscope.config.ConfigMessages").getMethod("init").invoke(null);
        Class.forName("cn.perfscope.diagnose.DiagnoseMessages").getMethod("init").invoke(null);
        PerfScope bare = (PerfScope) alloc(PerfScope.class);
        setField(PerfScope.class, bare, "logger", Logger.getLogger("pscope-message-init"));
        try {
            Class.forName("cn.perfscope.util.Messages")
                .getMethod("load", Class.forName("cn.perfscope.PerfScope"))
                .invoke(null, bare);
        } catch (java.lang.reflect.InvocationTargetException ignored) {
            // applyCommandPermissionMessage 需要 Bukkit 服务端；快照在此之前已发布
        }
    }

    public AlertService service() {
        return service;
    }

    public PerfScope plugin() {
        return plugin;
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public ConfigManager.ConfigData config() {
        return config;
    }

    /** 生成下一个唯一快照时间戳（防重复消费守卫要求严格单调）。 */
    public long nextTimestamp() {
        snapshotSeq++;
        return timestampBase + snapshotSeq;
    }

    /**
     * 造一份只含一条告警的快照（不喂给状态机）。
     *
     * @param timestamp 必须与本次测试内其它快照<b>互不相同</b>，否则会被防重复消费守卫丢弃
     */
    public MetricsSnapshot snapshot(long timestamp, String key, String level, double value) {
        return snapshotOf(timestamp, Alert.measured(key, level, value, moduleOf(key), timestamp));
    }

    /**
     * 造一份含多条告警的快照（不喂给状态机）。
     *
     * <p><b>为什么必须能一次喂多条</b>：状态机把"本周期没出现、且 {@code alertLevels} 里也没有"
     * 的活跃 key 判为已恢复。所以"用两份单条快照分别测两个 key"会把第一条当成恢复了——
     * 那是真实语义，不是夹具 bug。想表达"两个 key 同时存在"，就得放进同一份快照。</p>
     */
    public MetricsSnapshot snapshotOf(long timestamp, Alert... alerts) {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.timestamp = timestamp;
        for (Alert alert : alerts) {
            snap.alerts.add(alert);
            // alertLevels 是"判定过"的真相源：越界写级别，健康写 0
            snap.alertLevels.put(alert.key(), alert.critical() ? 2 : 1);
            if (alert.critical()) {
                snap.activeCriticals.add(alert.message());
            } else {
                snap.activeWarnings.add(alert.message());
            }
        }
        return snap;
    }

    /**
     * 造一份"本周期没有越界"的快照（供恢复路径用）。
     *
     * @param judgedKeys 采集层本周期<b>判定过</b>的 key（写 0）——没被判定过的 key 会走
     *                   "模块不可用 / 无结论"分支，语义不同，故必须显式列出
     */
    public MetricsSnapshot cleanSnapshot(long timestamp, String... judgedKeys) {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.timestamp = timestamp;
        for (String key : judgedKeys) {
            snap.alertLevels.put(key, 0);
        }
        return snap;
    }

    /** 自动取唯一时间戳，构造单条告警并交给状态机。 */
    public MetricsSnapshot feed(String key, String level, double value) {
        return feed(nextTimestamp(), key, level, value);
    }

    /** 用指定时间戳构造单条告警并交给状态机。 */
    public MetricsSnapshot feed(long timestamp, String key, String level, double value) {
        return feedSnapshot(snapshot(timestamp, key, level, value));
    }

    /** 自动取唯一时间戳，构造一组告警并交给状态机（同周期多条）。 */
    public MetricsSnapshot feedAll(Alert... alerts) {
        return feedSnapshot(snapshotOf(nextTimestamp(), alerts));
    }

    /** 直接喂一份外层构造好的快照。 */
    public MetricsSnapshot feedSnapshot(MetricsSnapshot snap) {
        service.onSnapshot(snap);
        return snap;
    }

    /** 喂一份"全干净"快照（不触发任何推送）。 */
    public MetricsSnapshot feedClean(long timestamp, String... judgedKeys) {
        return feedSnapshot(cleanSnapshot(timestamp, judgedKeys));
    }

    /**
     * 伪造"该 key 上次推送发生在 {@code agoMs} 毫秒之前"。
     *
     * <p>这是 {{@code isCooling}} 唯一的时间输入：把记录写老，等价于让真实时钟走过了这么久。</p>
     */
    public void seedCooldown(String key, long agoMs) {
        cooldowns.put(key, System.currentTimeMillis() - agoMs);
    }

    /** 读回该 key 的冷却记录年龄（毫秒）；无记录返回 {@code -1}。 */
    public long cooldownAgeMs(String key) {
        Long at = cooldowns.get(key);
        return at == null ? -1L : System.currentTimeMillis() - at;
    }

    /**
     * 伪造"该 key 刚在 {@code agoMs} 毫秒前恢复过"——落进 {@code flapWindowMs} 窗口内即触发抖动抑制 /
     * 复燃判定。
     */
    public void seedLastResolved(String key, long agoMs) throws Exception {
        long at = System.currentTimeMillis() - agoMs;
        Class<?> rc = Class.forName("cn.perfscope.alert.AlertService$ResolvedInfo");
        Constructor<?> ctor = rc.getDeclaredConstructor(long.class, long.class);
        ctor.setAccessible(true);
        lastResolvedAt.put(key, ctor.newInstance(at, at - 60_000L));
    }

    /** 清掉某个 key 的冷却（用于"从未推送过"场景）。 */
    public void clearCooldown(String key) {
        cooldowns.remove(key);
    }

    /** 生命周期事件累计计数（open / refire / resolve / suspend …）。 */
    public Map<String, Long> events() {
        return service.alertEventCounters();
    }

    /** 某类事件的发生次数；未发生返回 0。 */
    public long eventCount(String type) {
        Long v = events().get(type);
        return v == null ? 0L : v;
    }

    /** 当前活跃（已确认未恢复）告警数。 */
    public int activeCount() {
        return service.getActiveCount();
    }

    /** 处于 unknown（模块长期不可用）状态的告警数。 */
    public int suspendedCount() {
        return service.getSuspendedCount();
    }

    /** 当日推送条数（含新增/重推/复燃，不含恢复与缓解）。 */
    public int pushedToday() {
        return service.getPushedTodayCount();
    }

    // ==================== 内部 ====================

    /** 与 {@link AlertService#metricOf} 同口径的模块前缀（只为让 Alert.module() 非空）。 */
    private static String moduleOf(String key) {
        if (key == null) {
            return "";
        }
        int i = key.indexOf(':');
        return i > 0 ? key.substring(0, i) : key;
    }

    private static Object options(Builder b) throws Exception {
        Class<?> oc = Class.forName("cn.perfscope.alert.AlertService$Options");
        Method d = oc.getDeclaredMethod("defaults");
        d.setAccessible(true);
        Object o = d.invoke(null);
        setField(oc, o, "recoveryDebounceCycles", b.recoveryCycles);
        setField(oc, o, "digestMode", b.digestMode);
        setField(oc, o, "notifyMitigation", b.notifyMitigation);
        setField(oc, o, "unknownAfterMinutes", b.unknownAfterMinutes);
        setField(oc, o, "dailyTimezone", "");
        return o;
    }

    private static Object unsafe() throws Exception {
        Class<?> c = Class.forName("sun.misc.Unsafe");
        Field f = c.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object alloc(Class<?> k) throws Exception {
        return unsafe().getClass().getMethod("allocateInstance", Class.class).invoke(unsafe(), k);
    }

    private static void setField(Class<?> cls, Object t, String n, Object v) throws Exception {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                f.set(t, v);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(n);
    }

    /**
     * 同步执行器：{@code stateWriter} 原本是单线程守护池（9A-18 异步落盘）。
     *
     * <p>测试里改成原地执行有两个好处——写盘时序确定，且不需要在每条用例后 join 收尾。
     * 它替代的是"字段为 null → {@code execute} 抛 NPE → 被 catch 后回退同步写"那条隐式路径：
     * 靠异常兜底并不算设计，显式注入才叫。</p>
     */
    private static final class InlineExecutor extends AbstractExecutorService {
        private volatile boolean stopped = false;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            stopped = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            stopped = true;
            return java.util.List.of();
        }

        @Override
        public boolean isShutdown() {
            return stopped;
        }

        @Override
        public boolean isTerminated() {
            return stopped;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    // ==================== Builder ====================

    /** 逐项覆盖状态机参数；未显式设置的一律取夹具默认（与线上默认值一致）。 */
    public static final class Builder {
        private int cooldownSeconds = 300;
        private int debounceCycles = 1;
        private int flapWindowSeconds = 180;
        private int refireSeconds = 60;
        private int recoveryCycles = 1;
        private int unknownAfterMinutes = 5;
        private boolean digestMode = false;
        private boolean notifyMitigation = false;
        private String serverTag = "fixture";

        public Builder cooldownSeconds(int v) {
            this.cooldownSeconds = v;
            return this;
        }

        public Builder debounceCycles(int v) {
            this.debounceCycles = v;
            return this;
        }

        public Builder flapWindowSeconds(int v) {
            this.flapWindowSeconds = v;
            return this;
        }

        public Builder refireSeconds(int v) {
            this.refireSeconds = v;
            return this;
        }

        public Builder recoveryCycles(int v) {
            this.recoveryCycles = v;
            return this;
        }

        public Builder unknownAfterMinutes(int v) {
            this.unknownAfterMinutes = v;
            return this;
        }

        public Builder digestMode(boolean v) {
            this.digestMode = v;
            return this;
        }

        public Builder notifyMitigation(boolean v) {
            this.notifyMitigation = v;
            return this;
        }

        public Builder serverTag(String v) {
            this.serverTag = v;
            return this;
        }

        public AlertServiceStateMachineFixture build() throws Exception {
            return new AlertServiceStateMachineFixture(this);
        }
    }
}
