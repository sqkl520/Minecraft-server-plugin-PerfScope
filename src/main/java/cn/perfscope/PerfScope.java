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
     * {@code HH:mm:ss} 的 pattern（与命令层"采集时间"同口径）；每次仍现取时区。
     *
     * <p>提成静态常量省掉每次调用重建 pattern 的解析器树（本机实测 0.33 → 0.17 µs）。
     * 刻意<b>不</b>把 {@code withZone(ZoneId.systemDefault())} 一起冻进常量：那会让
     * 时区在类加载时被钉死，而 {@code TimeZone.setDefault()} 在 JVM 生命周期内是可能被改的
     * （见 {@code MetricsCollector.CLOCK_PATTERN} 的同款说明）。行为零变更优先于那 20 ns。</p>
     */
    private static final java.time.format.DateTimeFormatter CLOCK_PATTERN =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * bStats 句柄（Wave 10）：非 null 表示遥测已启用且需在 onDisable 时显式 {@code shutdown()}。
     * 占位 ID（0）或配置关闭时恒为 null。
     */
    private Metrics bstatsMetrics;

    /**
     * bStats 当前状态的枚举。{@code bstatsMetrics == null} 只能回答"没在发"，
     * 回答不了"为什么没在发"——而"服主自己关的"与"初始化炸了"对排障是两件完全不同的事
     * （前者无需处理，后者说明 jar 里的 shade/relocate 有问题）。所以状态必须单独留一份。
     */
    public enum BstatsState {
        /** 正在上报。 */
        ENABLED,
        /** general.bstats-enabled=false（服主的隐私开关）。 */
        DISABLED_BY_CONFIG,
        /** 作者未填 pluginId（占位 0），无可上报目标。 */
        DISABLED_NO_PLUGIN_ID,
        /** 初始化抛异常（shade/relocate 破损、bStats 内部错误等），已跳过。 */
        FAILED
    }

    /**
     * 最近一次 bStats 启停的结果。
     *
     * <p>默认值与 {@link #startBstats()} 的"配置关闭"分支一致；{@code onEnable} 里
     * {@code startBstats()} 在命令注册<b>之后</b>、任何命令可执行<b>之前</b>就会覆盖它，
     * 因此不存在"用户看到默认值"的窗口。字段用 volatile：写入在 {@code onEnable}/reload，
     * 读取在命令线程。</p>
     */
    private volatile BstatsState bstatsState = BstatsState.DISABLED_BY_CONFIG;
    /** bStats 初始化失败的原因（null = 没失败过）；供 /perfscope status 展示。 */
    private volatile String bstatsFailureReason;

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
    /**
     * 自动报告任务句柄槽位（P0-1）。
     *
     * <p>旧字段是非 volatile 的普通 {@code TaskHandle}，{@code startAutoReport()} 首行的
     * {@code stopAutoReport()} 只在单线程下幂等。并发 reload 时两个线程可以各装一个定时器、
     * 字段只留后者，先建的从此无人取消——报告文件会被重复生成（同分钟两份、保留清理互相踩）。</p>
     */
    private final cn.perfscope.compat.TaskHandleSlot autoReportSlot = new cn.perfscope.compat.TaskHandleSlot();
    private AlertService alertService;
    private WebhookNotifier webhookNotifier;
    /** v1.4：Prometheus 抓取端点（默认不启用；null 表示尚未装配）。 */
    private cn.perfscope.export.PrometheusEndpoint prometheusEndpoint;
    /** v1.5：跨服摘要上报（默认不启用；null 表示尚未装配）。 */
    private cn.perfscope.network.SummaryPublisher summaryPublisher;
    private DailySummaryService dailySummaryService;

    // ==================== 9A-20 #4：采集任务 supervisor ====================
    // 背景：collectSync 的任务体即便加了 try/catch(Throwable)，仍有"任务根本没跑起来"的路径——
    // Folia 调度失败返回 noop 句柄、插件被其他插件 disable、服务端调度器异常等。这类情况下
    // 采集不会报错、不会打日志，只会永久停在最后一张快照上。本 supervisor 每 10 秒看一眼
    // MetricsCollector.isStale()，只在"进入过期/恢复"的边沿各记一条日志（不刷屏），
    // 让控制台成为唯一的、不依赖人工比对时间戳的告警源。
    private final cn.perfscope.compat.TaskHandleSlot healthWatchSlot = new cn.perfscope.compat.TaskHandleSlot();

    /**
     * {@link #reload()} 的实例级串行锁（P0-1）。
     *
     * <p><b>为什么需要它</b>：reload 的语义是「停四个任务 → 重读配置 → 重建四个任务」，
     * 这三步必须是一个整体。Folia 上两个 region 线程同时 {@code /perfscope reload} 时，
     * 两段 reload 会交错成「A 停 → B 停 → A 重建 → B 重建」——即使每个句柄槽位都能保证
     * "只装一个"，最终活下来的仍是<b>最后一次重建按当时配置装的那一份</b>，而那可能是
     * 由 A 在 B 重读配置之前读到的旧配置算出来的间隔。串行化让并发 reload 退化成
     * "排队各跑一遍"，最后一次的配置一定生效。</p>
     *
     * <p>用显式锁对象而不是把方法改成 {@code synchronized}：方法签名保持
     * {@code public void reload()} 可以让 {@code tools/check-reload-rebuild.mjs} 的源码
     * 定位逻辑继续成立（那条门禁守的是"加载失败也要重建"，不能被签名重构连带改坏）。</p>
     */
    private final Object reloadLock = new Object();
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
        // ── 为什么这三行**不能**走 Messages.get()（P2 结论：这是时序限制，不是偷懒）──
        // 1. 语言来源不可知：{@code general.language} 在 config.yml 里，而读配置正是本守卫要
        //    赶在之前避免的 Bukkit 副作用（见上一段）。要为一条"平台不对，我要禁用自己"的
        //    日志去写一个 plugins/PerfScope/ 目录出来，代价明显大于收益。
        // 2. 没有"之后再补一条"的机会：下面紧接着就 disablePlugin 并 return，插件不再有
        //    横幅、不再有 load() 之后的生命周期。所以"先英文打、load 后再补中文"做不到。
        // 3. 强行调 Messages.get() 只会拿到键名本身（快照里还没有这条键），比英文更不可读。
        // 折中：只打英文。英文是本项目的 fallback / 默认语言，任何语言的服主都读得懂；
        // 而原实现打的是"英文一条 + 中文一条"的两行混排——那正是 9D 要消灭的形态。
        if (!ServerCompat.isSupportedPlatform()) {
            getLogger().severe("[PerfScope] Unsupported server platform: " + ServerCompat.getServerName()
                + ". This plugin targets the Paper family (Paper / Purpur / Pufferfish / Folia). "
                + "Version checking may be wrong and some features are unavailable. "
                + "Disabling PerfScope - delete the jar, or run it on Paper.");
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
        // P2：跨服摘要日志（通道登记 / 启停 / 回拨 / 发送失败 / server-id 派生）。
        // 必须在这里登记：startNetworkPush() 在 load() 之后才跑，但 reload 路径会重新
        // startNetworkPush 并再次打这些日志——不登记的话首次 load 之后这些键仍会缺失。
        Messages.onFirstLoad(cn.perfscope.network.NetworkMessages::init);
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

        // v1.5：跨服摘要上报（默认关）。同为可选出口，失败只记 warning。
        startNetworkPush();

        getLogger().info("========================================");
        getLogger().info(Messages.get("startup-done"));
        getLogger().info("========================================");

        // v1.4：首次安装引导——告警默认关闭时必须告诉用户怎么开，且必须在启动横幅**之后**
        // （横幅是"我起来了"，这一行是"你还要做一件事"；反过来会打断横幅的视觉块）。
        //
        // 为什么放在最后而不是紧跟 alerts 初始化：此时 banner 与全部可选出口（bStats /
        // Prometheus）的启动日志都已打完，这条是"最后一条建议"，不会夹在状态行中间。
        //
        // 只在**真的关闭**时打印：告警已启用时打这条就是假话。reload 不走这里
        // （reload 走 PerfScope#reload，不重复启动横幅），所以不会每次 reload 刷一遍。
        //
        // 不塞进 general.verbose_startup 门控：那是"详细的启动诊断信息"，而这条是
        // "你少配了一步"的操作提示——verbose_startup: false 的服主更需要它。
        ConfigManager.ConfigData startupCfg = configManager.get();
        if (startupCfg != null && !startupCfg.alerts_enabled) {
            getLogger().info(Messages.get("core.banner.alerts-off-by-default",
                ConfigManager.ALERTS_ENABLED_KEY));
        }
    }

    /** v1.4：供命令层 /status 与 /webhook test 使用（未装配时返回 null）。 */
    public WebhookNotifier getWebhookNotifier() {
        return webhookNotifier;
    }

    /** v1.4：供命令层 /status 展示端点状态（未启用时返回 null）。 */
    public cn.perfscope.export.PrometheusEndpoint getPrometheusEndpoint() {
        return prometheusEndpoint;
    }

    /**
     * P0-F：bStats 当前状态（供 /perfscope status 展示）。
     *
     * <p>永不返回 {@code null}：字段初始化器保证真实实例总有值，但测试夹具用
     * {@code allocateInstance} 造对象时字段初始化器不执行，返回 null 会让命令层
     * 走进最后那个 else——那恰好是"已禁用"，语义上仍然安全；这里再做一次显式收口，
     * 是为了让契约本身成立，而不是靠调用方的 if 链兜底。</p>
     */
    public BstatsState getBstatsState() {
        BstatsState s = bstatsState;
        return s == null ? BstatsState.DISABLED_BY_CONFIG : s;
    }

    /** P0-F：bStats 初始化失败的原因（null = 没失败过）；供 /perfscope status 展示。 */
    public String getBstatsFailureReason() {
        return bstatsFailureReason;
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
        stopNetworkPush();         // ① 输出：跨服摘要上报（先摘通道，避免关服期间再发）
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
        // lastStaleState / lastJvmStaleState 是"边沿判定"的基线，只有该任务线程读写。
        // 并发起停时两份看门狗会让同一次过期被报两遍、恢复也被报两遍——槽位保证只有一份在跑。
        lastStaleState = false;
        lastJvmStaleState = false;
        try {
            healthWatchSlot.start(() -> SchedulerAdapter.runAsyncTimer(this, this::checkCollectorHealth,
                HEALTH_WATCH_PERIOD_TICKS, HEALTH_WATCH_PERIOD_TICKS));
        } catch (Exception | LinkageError e) {
            getLogger().warning(Messages.get("core.log.watchdog-start-failed", e.getMessage()));
        }
    }

    private void stopHealthWatch() {
        healthWatchSlot.stop();
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
        return CLOCK_PATTERN
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
            // P0-1：经槽位安装（并发 reload 时只有一个真的装上，另一个复用或让位）
            SchedulerAdapter.TaskHandle handle = autoReportSlot.start(() -> SchedulerAdapter.runAsyncTimer(
                this, () -> reportGenerator.generateAndSave(), intervalTicks, intervalTicks));
            if (handle == null) {
                return; // 另一线程正在安装：它装好的定时器与本线程要装的是同一份配置
            }
        } catch (Exception | LinkageError e) {
            getLogger().severe(Messages.get("core.log.auto-report-failed", e.getMessage()));
            return;
        }
        getLogger().info(Messages.get("core.banner.auto-report", intervalMinutes));
    }

    private void stopAutoReport() {
        autoReportSlot.stop();
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

    /**
     * v1.5：登记跨服摘要出站通道。
     *
     * <p>与 {@link #startPrometheus()} 同款容错口径——可选出口失败绝不影响插件本体。
     * 失败情形真实存在：别的插件占了同名通道、代理端没装对应插件时通道仍能登记但消息无人消费、
     * 极端情况下 {@code Messenger} 不可用。</p>
     *
     * <p>注意这里**只登记通道**，不启动任何定时器：发送由采集周期末尾顺带触发
     * （见 {@code MetricsCollector} 的 {@code publishNetworkSummary}）。理由是不新增调度、
     * 不引入第二个时钟，也就不会出现"采集停了但上报还在发陈数据"。</p>
     */
    private void startNetworkPush() {
        ConfigManager.ConfigData cfg = configManager == null ? null : configManager.get();
        if (cfg == null || !cfg.network_enabled) {
            return;
        }
        if (summaryPublisher == null) {
            summaryPublisher = new cn.perfscope.network.SummaryPublisher(this);
        }
        try {
            // P0-E：register() 现在返回成败，**只有真的登记成功**才打「已启用」。
            // 原先 catch 在 register() 内部吞掉异常并正常返回，调用方无条件打「已启用」，
            // 于是控制台先 warning「登记失败」紧跟 info「已启用」——后一句更像结论，
            // 管理员会以为跨服摘要正常，实际代理侧永远收不到数据。
            if (summaryPublisher.register()) {
                getLogger().info(Messages.get("network.log.enabled",
                    cn.perfscope.network.NetworkSummary.CHANNEL,
                    cfg.network_pushIntervalSeconds));
            } else {
                getLogger().warning(Messages.get("network.log.not-enabled"));
            }
        } catch (Exception | LinkageError e) {
            getLogger().warning(Messages.get("network.log.start-failed",
                e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage())));
        }
    }

    /** v1.5：摘掉跨服通道。 */
    private void stopNetworkPush() {
        if (summaryPublisher != null) {
            summaryPublisher.unregister();
        }
    }

    /** v1.5：跨服摘要发布器；未启用时为 {@code null}。 */
    public cn.perfscope.network.SummaryPublisher getSummaryPublisher() {
        return summaryPublisher;
    }

    private void startBstats() {
        if (!configManager.get().general_bstatsEnabled) {
            // P0-F：状态必须留档，否则 /status 只能说"没在发"，说不出"为什么"。
            bstatsState = BstatsState.DISABLED_BY_CONFIG;
            bstatsFailureReason = null;
            getLogger().info(Messages.get("core.bstats.disabled-by-config"));
            return;
        }
        int bstatsPluginId = BSTATS_PLUGIN_ID; // 防常量折叠，见方法注释
        if (bstatsPluginId <= 0) {
            bstatsState = BstatsState.DISABLED_NO_PLUGIN_ID;
            bstatsFailureReason = null;
            getLogger().info(Messages.get("core.banner.bstats-disabled"));
            return;
        }
        try {
            Metrics metrics = new Metrics(this, bstatsPluginId);
            bstatsMetrics = metrics;
            BstatsCharts.register(metrics, this);
            bstatsState = BstatsState.ENABLED;
            bstatsFailureReason = null;
            getLogger().info(Messages.get("core.bstats.enabled", bstatsPluginId));
        } catch (Exception | LinkageError e) {
            // 失败时句柄必须归零：构造器抛异常时 {@code bstatsMetrics} 仍是上一次的值
            // （reload 场景下可能是已经不响应的旧实例），留着会让 /status 说"已启用"。
            bstatsMetrics = null;
            bstatsState = BstatsState.FAILED;
            bstatsFailureReason = e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
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
     * P0-F 附带：让 {@code general.bstats-enabled} 在 {@code /perfscope reload} 后立刻生效。
     *
     * <p><b>为什么必须单独做一次</b>：reload 会重建 Prometheus / network / webhook / 日报，
     * 唯独跳过了 bStats——遥测句柄只在 {@code onEnable} 里创建。于是服主把
     * {@code general.bstats-enabled} 改成 {@code false} 再 reload，数据会继续发到下一次重启为止。
     * 这是个<b>隐私开关失效</b>：开关的含义是"我说停就停"，而它的实际语义变成了
     * "我说停，下次重启才停"——用户读到的是前者，且没有任何提示告诉他真相。</p>
     *
     * <p>关闭方向必须在 reload 里立即执行（不能只提示"需重启"），因为这是唯一一个
     * 一旦被违反就构成隐私问题的配置项。打开方向同理重启即可，代价只是多创建一个提交线程。</p>
     */
    private void reloadBstats() {
        boolean wanted = configManager.get() != null && configManager.get().general_bstatsEnabled;
        boolean running = bstatsMetrics != null;
        switch (bstatsReloadAction(wanted, running)) {
            case START:
                startBstats();
                break;
            case STOP:
                stopBstats();
                bstatsState = BstatsState.DISABLED_BY_CONFIG;
                bstatsFailureReason = null;
                // 明说"这次真的停了"：服主改这个开关的动机通常就是隐私，他需要确认生效了。
                getLogger().info(Messages.get("core.bstats.reload-stopped"));
                break;
            case REFRESH_STATE:
                // 本来就是关的：只刷新状态快照（比如从"pluginId 占位"变成"enabled=false"），不刷日志。
                bstatsState = BstatsState.DISABLED_BY_CONFIG;
                bstatsFailureReason = null;
                break;
            case KEEP:
            default:
                // 配置要开、句柄也在：什么都不做。**绝不能**重启——那会白白重建一次提交线程，
                // 而且 bStats 每次启动都会重新读一次 bStats/config.yml。
                break;
        }
    }

    /** reload 时对 bStats 该做什么（抽成枚举 + 纯函数，便于单测穷举四象限）。 */
    enum BstatsReloadAction {
        /** 配置要开但没有句柄 → 启动。 */
        START,
        /** 配置关了但句柄还在 → 立刻停（隐私开关）。 */
        STOP,
        /** 配置本来就关着 → 只同步状态位，不打日志。 */
        REFRESH_STATE,
        /** 配置要开、句柄也在 → 不动。 */
        KEEP
    }

    /**
     * bStats 在 reload 时的决策。
     *
     * <p>单独抽出来是为了能被单测穷举四个象限——这段逻辑的价值全在
     * {@code wanted=false, running=true} 那一格（隐私开关失效的那个），
     * 而它在真实环境里必须靠 {@code /perfscope reload} 才能触发。</p>
     */
    static BstatsReloadAction bstatsReloadAction(boolean wanted, boolean running) {
        if (wanted) {
            return running ? BstatsReloadAction.KEEP : BstatsReloadAction.START;
        }
        return running ? BstatsReloadAction.STOP : BstatsReloadAction.REFRESH_STATE;
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
        // P0-1：并发 reload 退化串行。没有这把锁时两段 reload 会交错成
        // 「A 停 → B 停 → A 重建 → B 重建」，最终生效的间隔取决于交错顺序而不是最后一次配置。
        synchronized (reloadLock) {
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
            // v1.5：跨服上报随 reload 刷新。必须先摘旧通道再登记，且重置限频状态——
            // 否则改了 server-id / 间隔后，旧的"上一次摘要"缓存会让新配置直到摘要变化才生效。
            stopNetworkPush();
            if (summaryPublisher != null) {
                summaryPublisher.resetState();
            }
            startNetworkPush();
            // P0-F 附带：bStats 是**唯一**一个此前被 reload 漏掉的出口（Prometheus / network /
            // webhook / 日报都会重建）。漏掉的后果不是"没刷新"，而是 general.bstats-enabled 这个
            // **隐私开关**失效到下次重启——改 false 后遥测照发。所以这里必须真的启停，不能只提示。
            reloadBstats();
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
