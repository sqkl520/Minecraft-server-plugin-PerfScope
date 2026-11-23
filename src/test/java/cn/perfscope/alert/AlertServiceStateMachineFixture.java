/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.AlertFixtures;
import cn.perfscope.support.TestObjects;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
 * <p><b>这个前提与 {@link #close()} 不冲突</b>：本夹具仍不读、不写状态文件；
 * {@code close()} 只删除构造时创建的那个数据目录，不触碰 {@code stateLoaded}
 * 也不调用 {@code loadState()}／{@code persistState()}。</p>
 *
 * <p>{@code serverTag} 预置为字面量，{@code serverTag()} 因此永不触达 {@code Bukkit}。</p>
 *
 * <h2>临时目录的所有权：谁创建谁清理</h2>
 *
 * <p>构造器会 {@code Files.createTempDirectory("pscope-alert-fixture…")} 建一个数据目录
 * （{@code AlertService} 的 {@code persistState()} / {@code alerts.jsonl} 都挂在它下面）。
 * 此前没有任何人删除它——每次全量测试留下几十个目录。</p>
 *
 * <p>本夹具选择<b>自己删自己建的</b>（而不是"使用者负责"）：</p>
 * <ul>
 *   <li>目录是本类的构造器建的，创建点与删除点关在同一个类里，不存在"忘了配对"的可能；</li>
 *   <li>使用者无从知道该删什么——{@code plugin.getDataFolder()} 是反射注入进去的，
 *       调用方拿到的 {@code plugin()} 才看得到那个路径，要求每个调用方都记住去删，
 *       就等于把这条不变量复制成 3 份（当前 3 个类会实例化本夹具、共 24 处 {@code build()}）。</li>
 * </ul>
 *
 * <p>代价是调用方必须让清理真的发生。为此本类实现 {@link AutoCloseable}：把夹具放进
 * try-with-resources 或由自身的 {@code @AfterEach} 关闭。<b>刻意不注册 JVM shutdown hook</b>——
 * 那会在整个测试进程结束时才跑，无法归因到具体用例，而"哪个用例泄漏了"正是需要知道的信息。</p>
 */
public final class AlertServiceStateMachineFixture implements AutoCloseable {

    /** 夹具默认数据目录前缀（临时目录，避免污染仓库工作区）。 */
    private static final String TEMP_PREFIX = "pscope-alert-fixture";

    private final AlertService service;
    private final PerfScope plugin;
    private final ConfigManager configManager;
    private final ConfigManager.ConfigData config;

    /** 构造时创建的数据目录。{@link #close()} 是它唯一的删除点（谁创建谁清理）。 */
    private final Path dataDir;

    /**
     * 所有<b>已创建但还没关闭</b>的夹具。用于在用例结束时发现"忘记登记清理"的测试类。
     *
     * <p><b>为什么需要这一层，而不只是 {@link #close()} 抛异常</b>：{@code close()} 只能发现
     * "删除失败"，发现不了"根本没调用"。如果某个测试类忘了写 {@code @AfterEach} 调
     * {@link #closeAll}，每个夹具都清清白白地建完就跑，{@code close()} 一次也不会执行——
     * 目录照漏，测试照绿。变异验证确认过这个形态：把 {@code close()} 改成立刻 return，
     * 7 个用例全绿、7 个目录留在 {@code %TEMP%}。</p>
     *
     * <p>所以登记放在<b>构造器</b>里（创建与登记不可分离），关闭时移除。
     * 用法：测试类在自己的 {@code @AfterEach} 里先调 {@link #closeAll}，再调
     * {@link #assertNoLeaks}——后者在没有 {@code @TempDir} 的类里也能用。</p>
     */
    private static final Set<AlertServiceStateMachineFixture> OPEN =
        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 注入进 {@link AlertService} 的同一份容器引用——种子写这边，状态机读那边。 */
    private final Map<String, Long> cooldowns;
    private final Map<String, Object> lastResolvedAt;

    /** 快照时间戳发生器（防重复消费守卫要求全局唯一）。 */
    private long snapshotSeq = 0L;
    private final long timestampBase = System.currentTimeMillis();

    // ==================== 构造 ====================

    private AlertServiceStateMachineFixture(Builder b) throws Exception {
        Path created = Files.createTempDirectory(TEMP_PREFIX);
        this.dataDir = created;
        File dataDir = created.toFile();
        // 创建即登记：这样"忘了关"能被发现。登记处与创建处必须是同一行附近，
        // 否则又会出现"造了却没记"的缝隙。
        OPEN.add(this);

        PerfScope p = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, p, "logger", Logger.getLogger("perfscope-alert-fixture"));
        TestObjects.setField(PerfScope.class, p, "dataFolder", dataDir);

        ConfigManager cm = new ConfigManager(p);
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
        TestObjects.setField(ConfigManager.class, cm, "plugin", p);
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, p, "configManager", cm);

        AlertService svc = TestObjects.allocate(AlertService.class);
        TestObjects.setField(AlertService.class, svc, "plugin", p);
        TestObjects.setField(AlertService.class, svc, "configManager", cm);
        TestObjects.setField(AlertService.class, svc, "webhookNotifier", null);
        // 预置字面量：serverTag() 的缓存命中路径，永不触达 Bukkit.getServer()
        TestObjects.setField(AlertService.class, svc, "serverTag", b.serverTag);
        // 关键：不调用 loadState()，persistState() 因 stateLoaded=false 直接早退
        TestObjects.setField(AlertService.class, svc, "stateLoaded", false);
        TestObjects.setField(AlertService.class, svc, "lastConsumedSnapshotTs", -1L);
        TestObjects.setField(AlertService.class, svc, "options", options(b));
        // 启动抑制窗口关闭：startupSuppressed() 恒 false，免得 tps: 键被静音
        TestObjects.setField(AlertService.class, svc, "startupSuppressEnabled", false);
        TestObjects.setField(AlertService.class, svc, "startupSuppressUntilMs", 0L);
        TestObjects.setField(AlertService.class, svc, "startupSuppressSeconds", 0);
        TestObjects.setField(AlertService.class, svc, "refireMinIntervalMs", b.refireSeconds * 1000L);
        TestObjects.setField(AlertService.class, svc, "flapWindowMs", b.flapWindowSeconds * 1000L);
        // 0 = 关闭"数值变化幅度"这层抑制，让判定只剩冷却一条，用例才可预测
        TestObjects.setField(AlertService.class, svc, "repushDelta", 0.0d);
        TestObjects.setField(AlertService.class, svc, "pushedTodayDayEpoch", LocalDate.now().toEpochDay());

        // ---- allocateInstance 跳过的 final 字段初始化器，逐个补 ----
        ExecutorService writer = new InlineExecutor();
        TestObjects.setField(AlertService.class, svc, "stateWriter", writer);

        Map<String, Long> cd = new ConcurrentHashMap<>();
        Map<String, Object> resolved = new ConcurrentHashMap<>();
        TestObjects.setField(AlertService.class, svc, "cooldowns", cd);
        TestObjects.setField(AlertService.class, svc, "lastResolvedAt", resolved);
        TestObjects.setField(AlertService.class, svc, "activeAlerts", new ConcurrentHashMap<>());
        TestObjects.setField(AlertService.class, svc, "debounceCounts", new ConcurrentHashMap<>());
        TestObjects.setField(AlertService.class, svc, "recoveryCounts", new ConcurrentHashMap<>());
        TestObjects.setField(AlertService.class, svc, "flapCounts", new ConcurrentHashMap<>());
        TestObjects.setField(AlertService.class, svc, "flapHealCycles", new ConcurrentHashMap<>());
        TestObjects.setField(AlertService.class, svc, "cleanRunStartMs", new ConcurrentHashMap<>());
        TestObjects.setField(AlertService.class, svc, "recentValues", new ConcurrentHashMap<>());
        TestObjects.setField(AlertService.class, svc, "suspendedAlerts", new ConcurrentHashMap<>());
        TestObjects.setField(AlertService.class, svc, "moduleDownSince", new ConcurrentHashMap<>());
        Set<String> flapSuppressed = ConcurrentHashMap.newKeySet();
        TestObjects.setField(AlertService.class, svc, "flapSuppressed", flapSuppressed);
        Deque<Object> recent = new ArrayDeque<>();
        TestObjects.setField(AlertService.class, svc, "recentEvents", recent);
        Map<String, LongAdder> counters = new ConcurrentHashMap<>();
        TestObjects.setField(AlertService.class, svc, "alertEventCounters", counters);

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
     * <p>实现已搬进 {@link AlertFixtures#initMessages()}（4 个测试类共用同一段逻辑）。
     * 本方法保留为薄封装，调用点因此零改动。</p>
     */
    public static void initMessages() throws Exception {
        AlertFixtures.initMessages();
    }

    public AlertService service() {
        return service;
    }

    /**
     * 关闭一批夹具，<b>并把每一条失败都汇总抛出</b>——供测试类的 {@code @AfterEach} 调用。
     *
     * <p><b>为什么不是简单的 {@code for (f : fixtures) f.close();}</b>：那样第一个失败就会
     * 中断循环，剩下的夹具全都得不到清理，于是一次失败被放大成 N 个泄漏目录。
     * 本方法对每个夹具<b>都尝试</b>关闭，收集全部失败后再一起抛。</p>
     *
     * <p><b>为什么抛 {@link AssertionError} 而不是打日志</b>：日志在 surefire 的
     * {@code *-output.txt} 里，全量 994 例的输出中没人会去看；而抛异常会让
     * <b>对应用例直接变红</b>，并在报告里带上路径。代价是"清理失败 = 用例失败"——
     * 这个取舍是刻意的：留下垃圾本身就是需要修的问题，不是可以降级成告警的家务事。</p>
     *
     * <p>关闭后清空传入的列表，避免重复关闭。</p>
     */
    public static void closeAll(List<AlertServiceStateMachineFixture> fixtures) {
        if (fixtures == null || fixtures.isEmpty()) {
            return;
        }
        List<Throwable> failures = new ArrayList<>();
        for (AlertServiceStateMachineFixture f : fixtures) {
            try {
                f.close();
            } catch (RuntimeException | Error e) {
                failures.add(e);
            }
        }
        fixtures.clear();
        if (failures.isEmpty()) {
            return;
        }
        AssertionError aggregate = new AssertionError(
            failures.size() + " of the temp data dirs created by this test could not be deleted"
                + " (see the suppressed exceptions for each path)");
        for (Throwable t : failures) {
            aggregate.addSuppressed(t);
        }
        throw aggregate;
    }

    /**
     * 断言此刻没有"建了却没关"的夹具残留。
     *
     * <p><b>这是上一个测试类忘记清理的唯一可靠发现点</b>。{@code closeAll} 只能在被调用时
     * 起作用，而漏写 {@code @AfterEach} 的类根本不会调用它——那些夹具会一直留在 {@link #OPEN}
     * 里，被<b>下一个</b>跑到本方法的测试类看到。</p>
     *
     * <p>因此每个建夹具的测试类都应在 {@code @BeforeEach} 里调一次本方法：它看到的是"上一个
     * 测试类留下的账"。JUnit 同一个 JVM 里顺序执行测试类，这条链因此是连续的。</p>
     *
     * <p>为什么不让 {@code close()} 自己兜底报错：那个形态已经验证过——把 {@code close()}
     * 改成立刻 return，7 个用例全绿、7 个目录留在 {@code %TEMP%}，因为压根没人调用它。
     * 报错机制必须挂在"一定会被执行"的路径上。</p>
     */
    public static void assertNoLeaks(String where) {
        if (OPEN.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder()
            .append(OPEN.size())
            .append(" AlertServiceStateMachineFixture instance(s) were created but never closed")
            .append(" by a previously run test class (checked at ").append(where).append(").")
            .append(" Every test that builds one must call closeAll(...) in @AfterEach;")
            .append(" otherwise these temp data dirs leak into %TEMP%:");
        for (AlertServiceStateMachineFixture f : OPEN) {
            sb.append("\n  ").append(f.dataDir);
        }
        throw new AssertionError(sb.toString());
    }

    /**
     * 删除构造器创建的数据目录（递归）。
     *
     * <p><b>为什么不吞异常</b>：删不掉意味着真的留下了垃圾，而 Windows 上「文件被占用」
     * 是会发生的事（写线程尚未释放句柄、杀毒软件正在扫）。若在这里 {@code catch (IOException ignored)}，
     * 泄漏会重新变回静默——正是本次要消灭的形态。所以本方法把失败包成
     * {@link UncheckedIOException} 抛出，让它在用例层面变成一条 <b>ERROR</b>：
     * 哪个用例、哪个路径、哪个文件删不掉，全部出现在 surefire 报告里。</p>
     *
     * <p>本夹具的 {@code stateWriter} 是同步执行的 {@code InlineExecutor}，写盘在上一次
     * {@code onSnapshot} 返回时就已经关闭了文件句柄，因此正常情况下不会出现占用。
     * 一旦出现，说明那是需要被看见的真实问题，而不是可以重试的家务事。</p>
     *
     * <p>幂等：重复调用时目录已不存在，直接成功。</p>
     */
    @Override
    public void close() {
        // 先出登记表：即便下面的删除失败，也已经不再是"忘了关"那种形态，
        // 失败会由 close() 自己抛出去，不会重复报告。
        OPEN.remove(this);
        if (dataDir == null || !Files.exists(dataDir)) {
            return;
        }
        try {
            Files.walkFileTree(dataDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(
                "leaked temp data dir of " + AlertServiceStateMachineFixture.class.getSimpleName()
                    + " could not be deleted: " + dataDir
                    + " (a deleted-count of 0 in %TEMP% is a contract of this test suite)", e);
        }
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
     * @param judgedKeys 采集层本周期<b>判定过</b>的 key（写 0）——没被判定过的 key 若<b>缺席</b>，
     *                   语义是"该维度本周期不适用"（世界被卸载等），告警据此正常恢复；
     *                   若想表达"不可判定"，要用 {@link #noVerdictSnapshot} 写显式 -1
     */
    public MetricsSnapshot cleanSnapshot(long timestamp, String... judgedKeys) {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.timestamp = timestamp;
        for (String key : judgedKeys) {
            snap.alertLevels.put(key, 0);
        }
        return snap;
    }

    /**
     * 造一份"采集层显式声明这些 key 本周期不可判定"的快照（哨兵路径：{@code msptAvg <= 0}、
     * {@code processCpuLoad < 0}）。
     *
     * <p>与"键缺席"是两件事：缺席 = 该维度不适用（照常恢复），{@code -1} = 没测（转 unknown）。</p>
     */
    public MetricsSnapshot noVerdictSnapshot(long timestamp, String... unjudgedKeys) {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.timestamp = timestamp;
        for (String key : unjudgedKeys) {
            snap.alertLevels.put(key, MetricsSnapshot.ALERT_LEVEL_NO_VERDICT);
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
        TestObjects.setField(oc, o, "recoveryDebounceCycles", b.recoveryCycles);
        TestObjects.setField(oc, o, "digestMode", b.digestMode);
        TestObjects.setField(oc, o, "notifyMitigation", b.notifyMitigation);
        TestObjects.setField(oc, o, "unknownAfterMinutes", b.unknownAfterMinutes);
        TestObjects.setField(oc, o, "dailyTimezone", "");
        return o;
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
