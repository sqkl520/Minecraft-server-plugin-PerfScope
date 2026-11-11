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
package cn.perfscope;

import cn.perfscope.alert.AlertService;
import cn.perfscope.alert.DailySummaryService;
import cn.perfscope.alert.WebhookNotifier;
import cn.perfscope.command.PerfScopeCommand;
import cn.perfscope.compat.SchedulerAdapter;
import cn.perfscope.compat.ServerCompat;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.diagnose.DiagnosticEngine;
import cn.perfscope.history.HistoryFileStore;
import cn.perfscope.history.HistoryStore;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.report.ReportGenerator;
import cn.perfscope.util.Messages;
import org.bstats.bukkit.Metrics;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PerfScope extends JavaPlugin {

    /**
     * bStats 项目 ID（2026-11-01 注册）。
     *
     * <p>持在局部变量里再判定（见 {@code startBstats()}），避免 javac 对常量做常量折叠，
     * 把 {@code Metrics} 引用连同整段代码从字节码中消去——保留引用才能用 javap 验证
     * shade 已把 {@code org.bstats} 重写为 {@code cn.perfscope.shaded.bstats}。</p>
     */
    private static final int BSTATS_PLUGIN_ID = 35061;

    /**
     * bStats 句柄（Wave 10）：非 null 表示遥测已启用且需在 onDisable 时显式 {@code shutdown()}。
     * 占位 ID（0）或配置关闭时恒为 null。
     */
    private Metrics bstatsMetrics;

    private static PerfScope instance;

    private ConfigManager configManager;
    private HistoryStore historyStore;
    /** Wave 8B：历史文件持久化（按天 CSV）。命令层 /perfscope trend 经此读取。 */
    private HistoryFileStore historyFileStore;
    /** Wave 8A：诊断引擎（规则即对象注册表；由命令层 /perfscope diagnose 消费）。 */
    private DiagnosticEngine diagnosticEngine;
    private MetricsCollector metricsCollector;
    private ReportGenerator reportGenerator;
    private PerfScopeCommand command;
    private SchedulerAdapter.TaskHandle autoReportTask;
    private AlertService alertService;
    private WebhookNotifier webhookNotifier;
    /** v1.4：Prometheus 抓取端点（默认不启用；null 表示尚未装配）。 */
    private cn.perfscope.export.PrometheusEndpoint prometheusEndpoint;
    private DailySummaryService dailySummaryService;

    // ==================== 9A-20 #4：采集任务 supervisor ====================
    // 背景：collectSync 的任务体即便加了 try/catch(Throwable)，仍有"任务根本没跑起来"的路径——
    // Folia 调度失败返回 noop 句柄、插件被其他插件 disable、服务端调度器异常等。这类情况下
    // 采集不会报错、不会打日志，只会永久停在最后一张快照上。本 supervisor 每 10 秒看一眼
    // MetricsCollector.isStale()，只在"进入过期/恢复"的边沿各记一条日志（不刷屏），
    // 让控制台成为唯一的、不依赖人工比对时间戳的告警源。
    private SchedulerAdapter.TaskHandle healthWatchTask;
    /** supervisor 上次观测到的过期状态（false = 正常）；仅该任务线程读写。 */
    private boolean lastStaleState = false;
    /** supervisor 上次观测到的 JVM 异步节拍过期状态；仅该任务线程读写。 */
    private boolean lastJvmStaleState = false;
    /** supervisor 周期：10 秒（200 tick）。 */
    private static final long HEALTH_WATCH_PERIOD_TICKS = 200L;

    @Override
    public void onEnable() {
        instance = this;

        // 平台守卫（v1.3.0 P1）：本插件只支持 Paper 系。没有这道守卫时，纯 Bukkit/Spigot 上
        // 会走完全部初始化、在第一个 Paper 专有 API 处崩在半装配状态——部分模块起来了、
        // 部分没起来，而且错误信息指向具体某行而不是"平台不对"。
        //
        // 放在最前面：此刻还没有任何 Bukkit API 副作用（配置未读、任务未起、事件未注册），
        // disablePlugin 之后不会有残留。
        //
        // 注意：这里 **不能** 用 Messages.get()——消息层要等 load() 之后才就绪，此时调用
        // 只会拿到键名本身（见 Messages#resolve 的 fallback）。所以文案直接内联。
        if (!ServerCompat.isSupportedPlatform()) {
            getLogger().severe("[PerfScope] Unsupported server platform: " + ServerCompat.getServerName()
                + ". This plugin targets the Paper family (Paper / Purpur / Pufferfish / Folia). "
                + "Version checking may be wrong and some features are unavailable. "
                + "Disabling PerfScope - delete the jar, or run it on Paper.");
            getLogger().severe("[PerfScope] 不支持的服务端平台：" + ServerCompat.getServerName()
                + "。本插件面向 Paper 系（Paper / Purpur / Pufferfish / Folia）。"
                + "指标可能与事实不符，且部分功能不可用。已禁用 PerfScope —— 请删除本插件，或改用 Paper 系服务端。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 9D 修（验收 HIGH）：横幅**推迟到 Messages.load() 之后**再打印。
        // 原先写在这里，而 load() 在下面十几行之后——于是 language: zh 的服务器
        // 首两行（插件名/服务端）永远是英文默认语言，正是"混排"最显眼的一处。

        // 初始化配置
        // f-s06-err-01 / f-s03-err-05：此处**刻意不捕获**（与 reload() 的 catch 是两种场景）——
        // 启动期没有旧配置可退（configManager.get() 此时为 null），让异常穿透由服务端判为启用失败，
        // 是清晰可诊断的 fail-fast；捕获后继续装配只会让后续模块解引用 null（第一处是 runRetention 的
        // history_persistRetentionDays）。reload() 已有可用快照，捕获并按旧配置继续才是正确取舍。
        this.configManager = new ConfigManager(this);
        configManager.load();

        // Wave 8D：消息层（messages.yml 覆盖 + 内置默认回退）
        // 9D Phase 1：先登记"触碰各模块文案表"的动作，再 load——load 内部会逐一触发它们，
        // 保证本模块与各子模块（命令/报告/告警/诊断）的键都进入默认表。
        Messages.onFirstLoad(CoreMessages::init);
        Messages.onFirstLoad(cn.perfscope.alert.AlertMessages::init);
        Messages.onFirstLoad(cn.perfscope.diagnose.DiagnoseMessages::init);
        Messages.onFirstLoad(cn.perfscope.command.CommandMessages::init);
        // 报告层：本类仿 Colleague 方式自注册（ensureRegistered），但在 load 之前若有人
        // 读 report.* 会拿到键名。显式登记一次，保证与其它模块时序一致。
        Messages.onFirstLoad(cn.perfscope.report.ReportMessages::init);
        // 9D：采集/历史/兼容层的控制台日志
        Messages.onFirstLoad(RuntimeMessages::init);
        // 9D：配置校验警告（服主改错配置时最常看到的那类）
        Messages.onFirstLoad(cn.perfscope.config.ConfigMessages::init);
        // 9D 修（验收）：必须**先合并模块表**再生成 messages.yml，
        // 否则 saveDefault 只会写出基础 86 个键（模块表尚未合并进来），
        // 用户打开模板找不到 cmd.*/report.*/alert.*，会以为自定义功能不存在。
        // load() 内部会 touchModules + mergeModules；saveDefault 放在它之后即可看到全表。
        Messages.load(this);
        Messages.saveDefault(this);

        // 9D 修（验收 HIGH）：横幅放在消息层就绪之后，保证 language: zh 的服务器
        // 从第一行起就是中文——此前横幅在 load() 之前打印，首两行永远是英文默认语言。
        getLogger().info("========================================");
        getLogger().info(Messages.get("core.banner.starting", getDescription().getVersion()));
        getLogger().info(Messages.get("core.banner.server", ServerCompat.getServerName()));
        getLogger().info("========================================");

        // 初始化历史环形缓冲（Wave 7A）：由本类持有（非采集器），reload/采集重启不丢历史；
        // 采集器构造时注入引用，collectSync 末尾按节流间隔入环
        this.historyStore = new HistoryStore(configManager);

        // Wave 8B：历史文件持久化（按天 CSV；重启不清零）+ 启动期清理超期文件
        // 9A-18：写盘已改为单写者异步队列（不再阻塞主线程），retention 从写入路径剥离为独立方法
        this.historyFileStore = new HistoryFileStore(this);
        this.historyStore.setFileStore(historyFileStore);
        historyFileStore.runRetention(configManager.get().history_persistRetentionDays);

        // Wave 8A：诊断引擎（无状态构造；规则只读快照与历史，由命令层按需调用）
        this.diagnosticEngine = new DiagnosticEngine(this, historyStore);

        // 初始化性能采集器
        this.metricsCollector = new MetricsCollector(this, historyStore);
        startCollectionTasks();

        // 9A-1：MSPT 能力位运行期复核——首周期之后再实测一次 getTickTimes()/getAverageTickTime()，
        // 把"方法存在"与"数据真实"分开（Folia 上方法存在但恒返回全零）。
        // 延迟 100 tick（≈5 秒）：早于此采样窗口尚未填充，全零是正常态，会把 Paper 误判为不可用。
        // 经 SchedulerAdapter 挂载，Folia 上走 GlobalRegionScheduler（Bukkit 调度器在 Folia 必抛 UOE）。
        ServerCompat.armRuntimeProbe(this);

        // 初始化告警服务（P0-4：位置在采集器启动之后，周期读取快照推送告警）
        this.alertService = new AlertService(this);
        alertService.start();

        // Wave 7D：webhook 出口 + 每日摘要服务（webhook 在告警服务之后装配，供其推送通道使用）
        this.webhookNotifier = new WebhookNotifier(this);
        this.alertService.setWebhookNotifier(webhookNotifier);
        this.dailySummaryService = new DailySummaryService(this, webhookNotifier, alertService);
        dailySummaryService.start();

        // 初始化报告生成器
        this.reportGenerator = new ReportGenerator(this);

        // 注册指令（P0-14：getCommand null 保护）
        this.command = new PerfScopeCommand(this);
        PluginCommand pluginCommand = getCommand("perfscope");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().severe(Messages.get("core.log.command-register-failed"));
        }

        // 注册监听器（P0-12：monitor 退出清理）
        getServer().getPluginManager().registerEvents(command, this);

        // 自动报告定时任务
        if (configManager.get().report_autoExport) {
            startAutoReport();
        }

        if (configManager.get().general_verboseStartup) {
            // 9D：横幅全部走消息层。参数化而非拼接，让两种语言的词序都能自然。
            getLogger().info(Messages.get("core.banner.config-loaded"));
            getLogger().info(Messages.get("core.banner.collector-started",
                configManager.get().general_updateInterval));
            getLogger().info(Messages.get("core.banner.commands-registered"));
            getLogger().info(Messages.get("core.banner.report-ready",
                configManager.get().report_format));
            // MC-1 标注层 / 9A-1 条件输出：只有"实测可信"才宣称精确。
            // 能力位自 9A-1 起在 Folia 上恒 false（getTickTimes 方法存在但恒返回全零），
            // 因此本行在 Folia 上不打 "Paper API"（避免假精确），改为如实说明。
            // 两种语言都保留 "Paper API" 这一稳定检索锚点。
            if (ServerCompat.isMsptTrustworthy()) {
                getLogger().info(Messages.get("core.banner.mspt-exact"));
            } else {
                // reason 理论上此时已由类初始化置好；null 兜底避免打出 "null"
                String reason = ServerCompat.msptUnavailableReason();
                getLogger().info(reason == null || reason.isEmpty()
                    ? Messages.get("core.banner.mspt-exact-unavailable-only")
                    : Messages.get("core.banner.mspt-unavailable", reason));
            }
            getLogger().info(Messages.get("core.banner.actionbar",
                Messages.get(ServerCompat.PAPER_ACTION_BAR_AVAILABLE
                    ? "core.banner.available" : "core.banner.unavailable")));
        }

        // bStats 匿名统计（Wave 6D）：占位 ID 不发送任何数据；整段兜底，遥测失败绝不影响插件本体
        startBstats();

        // v1.4：Prometheus 抓取端点（默认关）。失败只记 warning，绝不让可选出口挡住启动。
        startPrometheus();

        getLogger().info("========================================");
        getLogger().info(Messages.get("startup-done"));
        getLogger().info("========================================");
    }

    /** v1.4：供命令层 /status 与 /webhook test 使用（未装配时返回 null）。 */
    public WebhookNotifier getWebhookNotifier() {
        return webhookNotifier;
    }

    /** v1.4：供命令层 /status 展示端点状态（未启用时返回 null）。 */
    public cn.perfscope.export.PrometheusEndpoint getPrometheusEndpoint() {
        return prometheusEndpoint;
    }

    @Override
    public void onDisable() {
        // 9A-10 #5：关服顺序固定为「先停产生者 → 再停消费者 → 最后关输出」。
        //  ① 产生者：采集任务（MSPT/实体/区块……）与自动报告定时器——不先停它们，
        //     后面关掉的执行器/服务会在关服瞬间收到新的提交请求（RejectedExecutionException）。
        //  ② 消费者：日报（消费告警与历史）→ 告警（消费快照）→ 看门狗。
        //  ③ 输出：报告执行器最后关，保证上面任何一处触发的报告都能落盘完成（shutdownNow 会中断在写任务）。
        // P0-12：monitor 任务最先停（它直接推 ActionBar，与采集/告警无依赖，早停早静音）。
        if (command != null) {
            command.shutdown();
        }
        // 9A-20 #4：看门狗是纯观察者（只读采集状态、不产出任何数据），必须早于采集停止——
        // 否则停止采集到停止看门狗之间那段窗口里，它会观察到"采集已过期"并在关服日志里报假警。
        stopHealthWatch();         // 观察者：先摘掉
        stopCollectionTasks();     // ① 产生者：采集
        // v1.4：先停生产者再关输出——端点关掉后抓取方立刻连接失败，而不是读到一份冻住的旧快照
        stopPrometheus();          // ① 输出：Prometheus 端点
        stopAutoReport();          // ① 产生者：自动报告（不再向报告执行器提交新任务）
        // Wave 7D：先停日报（消费告警与历史），再停告警（消费快照）
        stopDailySummary();        // ② 消费者
        stopAlertService();        // ② 消费者
        // 9A-18：告警状态写线程只在关服时关（reload 复用实例，不能关线程池）
        shutdownAlertStateWriter();
        // 9A-18：历史写线程必须在采集停止之后关——它要把队列残余刷盘，否则最后一批样本丢失
        shutdownHistoryFileStore();
        shutdownReportGenerator(); // ③ 输出：最后关报告执行器
        // Wave 10：遥测放在最后关——它的图表回调要读采集器快照，必须等上面全部停稳；
        // 同时它自带线程，不显式 shutdown 会拖住 JVM 退出。
        stopBstats();
        getLogger().info(Messages.get("shutdown-bye"));
        instance = null;
    }

    /**
     * 关闭历史文件写线程（9A-18）：必须在采集任务停止之后调用——
     * 写线程要把队列中尚未落盘的样本刷完再退出，否则关服瞬间的最后一批数据会丢。
     *
     * <p><b>f-s06-contract-07</b>：本方法此前被两段 javadoc 同时描述——一段讲报告执行器
     * （{@code ReportGenerator#shutdown()} 用 {@code shutdownNow()}），紧贴在它上面；
     * 另一段才是真正讲写线程的。前一段是「关闭报告执行器」的说明在重构后**失去了宿主**，
     * 于是悬空到了本方法头上，读者会以为本方法用 {@code shutdownNow()} 中断写线程——
     * 实际它调的是 {@link HistoryFileStore#shutdown()}，语义是**刷完队列再退出**，
     * 与 {@code shutdownNow()} 正好相反。现已把两段各归其位喵。</p>
     */
    private void shutdownHistoryFileStore() {
        if (historyFileStore != null) {
            try {
                historyFileStore.shutdown();
            } catch (Exception | LinkageError e) {
                getLogger().warning(Messages.get("core.log.history-writer-shutdown-failed", e.getMessage()));
            }
        }
    }

    /**
     * 关闭报告执行器（9A-10 #5）。
     *
     * <p>{@link ReportGenerator#shutdown()} 使用 {@code shutdownNow()}——关服阶段丢弃排队的报告请求、
     * 中断正在进行的写盘，绝不让主线程为一份没人看的报告多等 IO。已生成的文件不受影响。</p>
     */
    private void shutdownReportGenerator() {
        if (reportGenerator != null) {
            try {
                reportGenerator.shutdown();
            } catch (Exception | LinkageError e) {
                getLogger().warning(Messages.get("core.log.report-executor-shutdown-failed", e.getMessage()));
            }
        }
    }

    // ==================== 任务启停（P0-14 收敛：P0-8 替换为 SchedulerAdapter 时只需改这四个方法内部） ====================

    /** 启动采集任务（当前经由 MetricsCollector 走 Bukkit 调度器）。A9a：调度异常捕获，不留半状态。 */
    private void startCollectionTasks() {
        try {
            metricsCollector.start();
        } catch (Exception | LinkageError e) {
            getLogger().severe(Messages.get("core.log.collector-start-failed", e.getMessage()));
            getLogger().log(java.util.logging.Level.SEVERE, Messages.get("core.log.collector-start-stack"), e);
        }
        // 9A-20 #4：采集启动后立刻挂 supervisor（含 start 失败的分支——那正是最需要它的场景）
        startHealthWatch();
    }

    /** 停止采集任务。 */
    private void stopCollectionTasks() {
        if (metricsCollector != null) {
            metricsCollector.stop();
        }
    }

    // ==================== 9A-20 #4：采集任务 supervisor ====================

    /**
     * 挂载采集健康看门狗（9A-20 #4，"无 supervisor"的补齐）。
     *
     * <p>职责只有一件事：在 {@code MetricsCollector.isStale()} 的<b>边沿</b>上说话。
     * 每 {@value #HEALTH_WATCH_PERIOD_TICKS} tick（10 秒）检查一次，</p>
     * <ul>
     *   <li>正常 → 过期：SEVERE「采集已停止更新…」，并提示 /perfscope status 与 reload；</li>
     *   <li>过期 → 正常：INFO「采集已恢复」，附更新时间。</li>
     * </ul>
     * <p>边沿触发而非周期触发，避免 10 秒一条的二次刷屏；任务体自带 try 边界（任何异常只跳过本轮，
     * 不中断看门狗自身——看门狗自己死了才是真的没人管了）。调度失败时只是少一层日志，
     * 不影响 /perfscope status 与命令层的过期判定。</p>
     */
    private void startHealthWatch() {
        stopHealthWatch();
        lastStaleState = false;
        lastJvmStaleState = false;
        try {
            healthWatchTask = SchedulerAdapter.runAsyncTimer(this, this::checkCollectorHealth,
                HEALTH_WATCH_PERIOD_TICKS, HEALTH_WATCH_PERIOD_TICKS);
        } catch (Exception | LinkageError e) {
            healthWatchTask = null;
            getLogger().warning(Messages.get("core.log.watchdog-start-failed", e.getMessage()));
        }
    }

    private void stopHealthWatch() {
        if (healthWatchTask != null) {
            healthWatchTask.cancel();
            healthWatchTask = null;
        }
    }

    /** 看门狗单轮检查（异步线程；只读 MetricsCollector 的 volatile 状态）。 */
    private void checkCollectorHealth() {
        try {
            MetricsCollector mc = metricsCollector;
            if (mc == null) {
                return;
            }
            checkMainTickHealth(mc);
            checkJvmTickHealth(mc);
        } catch (Exception | LinkageError e) {
            // 看门狗自身绝不因单轮异常而退出
            getLogger().warning(Messages.get("core.log.watchdog-cycle-failed", e.getMessage()));
        }
    }

    /** 主线程采集节拍：过期/恢复的边沿各说一句。 */
    private void checkMainTickHealth(MetricsCollector mc) {
        boolean stale = mc.isStale();
        if (stale == lastStaleState) {
            return;   // 边沿触发：状态未变不说话
        }
        lastStaleState = stale;
        if (stale) {
            getLogger().severe(Messages.get("core.health.stale",
                mc.staleForMs() / 1000L,
                clockOf(mc.lastCollectAtMs()),
                mc.consecutiveCollectFailures()));
        } else {
            long lastAt = mc.lastCollectAtMs();
            getLogger().info(lastAt > 0L
                ? Messages.get("core.health.recovered", clockOf(lastAt))
                : Messages.get("core.health.recovered-warmup"));
        }
    }

    /**
     * JVM 异步节拍同样需要看门狗（9A-20 顺手项）：它是独立任务，被静默取消时主线程仍每秒产快照，
     * {@link MetricsCollector#isStale()} 看不出来——症状是内存/CPU/GC 永远停在最后一次的数值。
     */
    private void checkJvmTickHealth(MetricsCollector mc) {
        boolean jvmStale = mc.isJvmStale();
        if (jvmStale == lastJvmStaleState) {
            return;
        }
        lastJvmStaleState = jvmStale;
        long jvmAt = mc.jvmSnapshotTimestamp();
        if (jvmStale) {
            getLogger().warning(Messages.get("core.health.jvm-stale", clockOf(jvmAt)));
        } else {
            getLogger().info(Messages.get("core.health.jvm-recovered-at", clockOf(jvmAt)));
        }
    }

    /** 9A-20：epoch ms → "HH:mm:ss"（本地时区，与命令层"采集时间"同口径）；0 → "从未"。 */
    private static String clockOf(long epochMs) {
        if (epochMs <= 0L) {
            return Messages.get("core.time.never");
        }
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(epochMs));
    }

    /** 启动自动报告任务。A9a：调度异常捕获并记录（间隔非法时 report 侧不再进入半状态）。 */
    private void startAutoReport() {
        stopAutoReport(); // 防呆：避免重复启动叠任务
        // L-2/A9b：auto_export_interval ≤0 会被 Bukkit 调度器 coerce（0→每 tick 写盘 IO 风暴；
        // 负值→单次后静默死亡）/Folia 校正为每 tick，行为分叉，故强制下限 1 分钟
        int configuredMinutes = configManager.get().report_autoExportInterval;
        if (configuredMinutes <= 0) {
            getLogger().warning(Messages.get("core.log.auto-report-interval-clamped", configuredMinutes));
        }
        long intervalMinutes = Math.max(1, configuredMinutes);
        long intervalTicks = intervalMinutes * 60L * 20L;
        try {
            // P0-8：经 SchedulerAdapter 走异步调度（Folia 下自动用 AsyncScheduler）
            autoReportTask = SchedulerAdapter.runAsyncTimer(
                this, () -> reportGenerator.generateAndSave(), intervalTicks, intervalTicks);
        } catch (Exception | LinkageError e) {
            getLogger().severe(Messages.get("core.log.auto-report-failed", e.getMessage()));
            autoReportTask = null;
            return;
        }
        getLogger().info(Messages.get("core.banner.auto-report", intervalMinutes));
    }

    private void stopAutoReport() {
        if (autoReportTask != null) {
            autoReportTask.cancel();
            autoReportTask = null;
        }
    }

    /** 启动告警服务（P0-4）：定时器重建以应用新间隔；enabled=false 时 start() 内部直接返回。 */
    private void startAlertService() {
        if (alertService != null) {
            alertService.start();
        }
    }

    /** 停止告警服务（P0-4）：只取消定时器，冷却/活跃状态机保留（V1.3 规格：reload 不清状态机）。 */
    private void stopAlertService() {
        if (alertService != null) {
            alertService.stop();
        }
    }

    /** 9A-18：关服时关闭告警状态写线程（stopAlertService 已把最后一份状态提交进去）。 */
    private void shutdownAlertStateWriter() {
        if (alertService != null) {
            try {
                alertService.shutdown();
            } catch (Exception | LinkageError e) {
                getLogger().warning(Messages.get("core.log.alert-writer-shutdown-failed", e.getMessage()));
            }
        }
    }

    /** 启动每日摘要（Wave 7D）：enabled=false 时 start() 内部直接返回（幂等）。 */
    private void startDailySummary() {
        if (dailySummaryService != null) {
            dailySummaryService.start();
        }
    }

    /** 停止每日摘要（Wave 7D）：只取消定时器；当日已发标记保留（reload 后同日不重发）。 */
    private void stopDailySummary() {
        if (dailySummaryService != null) {
            dailySummaryService.stop();
        }
    }

    /**
     * 启动 bStats 匿名统计（Wave 6D）：general.bstats-enabled=true 且 {@code BSTATS_PLUGIN_ID > 0} 时创建
     * {@link Metrics}（源码 import 原包名，打包期由 maven-shade 重写为 cn.perfscope.shaded.bstats）。
     *
     * <p>整段 try-catch 兜底：遥测初始化失败绝不影响插件本体（catch Exception | LinkageError，
     * 与全仓异常隔离口径一致——relocate 落点缺失时的 NoClassDefFoundError 同属 LinkageError）；
     * 失败日志按 general.debug 门控堆栈（与 safeCollect 同款口径）。</p>
     *
     * <p>实现注意：ID 先读入局部变量再判定，避免 javac 对常量做常量折叠，把 Metrics 引用连同整段
     * 代码从字节码中消去——保留引用才能用 javap 验证 shade 已把 org.bstats 重写为
     * cn.perfscope.shaded.bstats（发布门禁的 relocate 实证）。</p>
     */
    /**
     * v1.4：启动 Prometheus 抓取端点。
     *
     * <p>与 {@link #startBstats()} 同款容错口径——可选出口失败绝不影响插件本体：
     * 端口被占、{@code jdk.httpserver} 被裁剪、绑定权限不足，都只记一条 warning，
     * 插件照常工作喵。</p>
     */
    private void startPrometheus() {
        if (prometheusEndpoint == null) {
            prometheusEndpoint = new cn.perfscope.export.PrometheusEndpoint(this);
        }
        try {
            prometheusEndpoint.start();
        } catch (Exception | LinkageError e) {
            // 端点内部已兜底并记日志；这里再兜一层，防构造期就炸（例如 LinkageError 在类加载阶段）
            getLogger().warning(Messages.get("core.prometheus.start-failed",
                e.getClass().getSimpleName()));
        }
    }

    /** v1.4：停止 Prometheus 端点并释放端口。 */
    private void stopPrometheus() {
        if (prometheusEndpoint != null) {
            try {
                prometheusEndpoint.stop();
            } catch (Exception ignored) {
                // 关闭路径不再制造新的失败
            }
        }
    }

    private void startBstats() {
        if (!configManager.get().general_bstatsEnabled) {
            getLogger().info(Messages.get("core.bstats.disabled-by-config"));
            return;
        }
        int bstatsPluginId = BSTATS_PLUGIN_ID; // 防常量折叠，见方法注释
        if (bstatsPluginId <= 0) {
            getLogger().info(Messages.get("core.banner.bstats-disabled"));
            return;
        }
        try {
            Metrics metrics = new Metrics(this, bstatsPluginId);
            bstatsMetrics = metrics;
            BstatsCharts.register(metrics, this);
            getLogger().info(Messages.get("core.bstats.enabled", bstatsPluginId));
        } catch (Exception | LinkageError e) {
            // general.debug 门控堆栈（与 safeCollect / monitor 推送同款日志口径）
            if (configManager.get().general_debug) {
                getLogger().log(java.util.logging.Level.WARNING, Messages.get("core.bstats.init-failed-detail", e.getMessage()), e);
            } else {
                getLogger().warning(Messages.get("core.bstats.init-failed-detail", e.getMessage()));
            }
        }
    }

    /**
     * 停止 bStats（Wave 10）：{@link Metrics#shutdown()} 会终止其提交线程。
     *
     * <p>为什么必须显式调用：bStats 的提交线程是 {@code Thread} 而非守护线程的子类，
     * 插件被 reload/disable 后若线程仍在，服务器关闭时会拖住 JVM。整段兜底——
     * 遥测层的关闭失败绝不能影响插件本体与服务器关闭流程。</p>
     */
    private void stopBstats() {
        Metrics metrics = bstatsMetrics;
        bstatsMetrics = null;
        if (metrics == null) {
            return;
        }
        try {
            metrics.shutdown();
        } catch (Exception | LinkageError e) {
            // 关闭期静默：此时日志可能已不可用，且遥测失败无用户可见影响
        }
    }

    /**
     * 真重载（P0-14）：stop→重读→start，重建采集与自动报告任务。
     * monitor 任务不受影响（玩家监测态保持，间隔固定 20 tick 无配置项）。
     * P0-4：告警服务同步启停（enabled 切 false 时不重建定时器），但不重置冷却/活跃状态机。
     *
     * A9a 健壮性修复（Wave4 修正注释：与 ConfigManager.load 最终实现对齐）：
     * load 若半途抛异常（旧实现会停在"采集已停不重建"的静默冻结态），此处捕获并降级继续——
     * load 内部以本地 fresh 承载解析、全部填充与 validateThresholds 完成后才单次替换
     * ConfigManager.config，故失败时 config 保持旧的完整引用（不会暴露半解析态）；
     * 采集/告警/自动报告一律照常重建（按旧配置继续运行），并给出明确日志。
     */
    public void reload() {
        stopCollectionTasks();
        stopAutoReport();
        stopAlertService();
        stopDailySummary();
        boolean loadFailed = false;
        try {
            configManager.load();
        } catch (Exception | LinkageError e) {
            loadFailed = true;
            getLogger().severe(Messages.get("core.log.config-load-failed", e.getMessage()));
            getLogger().log(java.util.logging.Level.SEVERE, Messages.get("core.log.config-load-stack"), e);
        }
        Messages.load(this); // Wave 8D：消息层随 reload 刷新（缺失/损坏自动回退默认）

        // 无论 load 成败，任务必须重建：绝不允许"采集停了不重建"的半途冻结态
        startCollectionTasks();
        startAlertService();
        // Wave 7D：webhook 配置随 reload 刷新（enabled/url 变化即生效；就绪状态内部重算）
        if (webhookNotifier != null) {
            webhookNotifier.reload();
        }
        // v1.4：Prometheus 端点配置随 reload 刷新。start() 内部先 stop()，
        // 所以改端口/路径/鉴权都能生效；未启用时 start() 直接返回 false 并不监听。
        startPrometheus();
        startDailySummary();
        if (configManager.get().report_autoExport) {
            startAutoReport();
        }
        try {
            reportGenerator.cleanOldReports();
        } catch (Exception | LinkageError e) {
            getLogger().warning(Messages.get("core.log.report-cleanup-failed", e.getMessage()));
        }
        // 9A-18：保留天数可能被改过；清理已从写入路径剥离，reload 时显式跑一次
        try {
            historyFileStore.runRetention(configManager.get().history_persistRetentionDays);
        } catch (Exception | LinkageError e) {
            getLogger().warning(Messages.get("core.log.history-cleanup-failed", e.getMessage()));
        }
        if (loadFailed) {
            getLogger().warning(Messages.get("reload-failed-old-config")); // 9A-24：接线死键
        } else {
            // 9A-24：控制台用独立键，默认值与改造前逐字一致（"PerfScope " 前缀保留，日志更好检索），
            // 与聊天侧的 reloaded 分开——专业部署可能只想中性化聊天回复而不动控制台日志
            getLogger().info(Messages.get("reloaded-console"));
        }
    }

    /**
     * 当前插件实例（{@link #onEnable} 发布，{@link #onDisable} 置 {@code null}）。
     *
     * <p><b>f-s06-contract-06</b>：本方法全仓<b>零调用点</b>——审计标为「死 API」。
     * 之所以保留而不删除：它是插件的标准外部访问入口，第三方插件/脚本通过
     * {@code Bukkit.getPluginManager().getPlugin("PerfScope")} 拿到实例后，本方法提供
     * 「静态可达」的便捷路径；删除属公开 API 变更（{@code PUBLIC_API_CHANGE}），
     * 需作者裁决，不应由审计批次顺手决定喵。</p>
     *
     * <p>契约：可能返回 {@code null}（插件未启用/已停用）。调用方必须判空喵。</p>
     */
    @org.jetbrains.annotations.Nullable
    public static PerfScope getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    /** Wave 7A：历史环形缓冲（24h 窗口）。命令层经此读取；实例生命周期 = 插件生命周期。 */
    public HistoryStore getHistoryStore() {
        return historyStore;
    }

    /** Wave 8A：诊断引擎（规则即对象注册表）。命令层 /perfscope diagnose 经此运行。 */
    public DiagnosticEngine getDiagnosticEngine() {
        return diagnosticEngine;
    }

    /** Wave 8B：历史文件持久化。命令层 /perfscope trend 经此读取（供全面历史查询）。 */
    public HistoryFileStore getHistoryFileStore() {
        return historyFileStore;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public ReportGenerator getReportGenerator() {
        return reportGenerator;
    }

    public AlertService getAlertService() {
        return alertService;
    }
}
