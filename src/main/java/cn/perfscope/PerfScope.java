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

    // TODO: 作者在 bstats.org 注册后替换（当前占位：不发送任何数据）
    private static final int BSTATS_PLUGIN_ID = 0;

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

        getLogger().info("========================================");
        getLogger().info("  PerfScope v" + getDescription().getVersion() + " 启动中...");
        getLogger().info("  服务端: " + ServerCompat.getServerName());
        getLogger().info("========================================");

        // 初始化配置
        this.configManager = new ConfigManager(this);
        configManager.load();

        // Wave 8D：消息层（messages.yml 覆盖 + 内置默认回退）
        Messages.saveDefault(this);
        Messages.load(this);

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
            getLogger().severe("指令 perfscope 注册失败：plugin.yml 缺少命令定义！");
        }

        // 注册监听器（P0-12：monitor 退出清理）
        getServer().getPluginManager().registerEvents(command, this);

        // 自动报告定时任务
        if (configManager.get().report_autoExport) {
            startAutoReport();
        }

        if (configManager.get().general_verboseStartup) {
            getLogger().info("  [+] 配置系统已加载");
            getLogger().info("  [+] 性能采集器已启动 (间隔: " +
                configManager.get().general_updateInterval + " ticks)");
            getLogger().info("  [+] 指令系统已注册 (/perfscope, /pscope, /perf)");
            getLogger().info("  [+] 报告系统就绪 (格式: " +
                configManager.get().report_format + ")");
            // MC-1 标注层 / 9A-1 条件输出：只有"实测可信"才宣称精确。
            // 能力位自 9A-1 起在 Folia 上恒 false（getTickTimes 方法存在但恒返回全零），
            // 因此本行在 Folia 上不打 "Paper API"（避免假精确），改为如实说明；
            // Paper 系保持原样（harness 断言 perfscope-surface.json 依赖此行原文）。
            if (ServerCompat.isMsptTrustworthy()) {
                getLogger().info("  MSPT精确采集: Paper API");
            } else {
                // reason 理论上此时已由类初始化置好；null 兜底避免打出 "null"
                String reason = ServerCompat.msptUnavailableReason();
                getLogger().info("  MSPT精度: 不可用"
                    + (reason == null || reason.isEmpty() ? "" : "（" + reason + "）"));
            }
            getLogger().info("  ActionBar: " + (ServerCompat.PAPER_ACTION_BAR_AVAILABLE ? "可用" : "不可用"));
        }

        // bStats 匿名统计（Wave 6D）：占位 ID 不发送任何数据；整段兜底，遥测失败绝不影响插件本体
        startBstats();

        getLogger().info("========================================");
        getLogger().info(Messages.get("startup-done"));
        getLogger().info("========================================");
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
        stopAutoReport();          // ① 产生者：自动报告（不再向报告执行器提交新任务）
        // Wave 7D：先停日报（消费告警与历史），再停告警（消费快照）
        stopDailySummary();        // ② 消费者
        stopAlertService();        // ② 消费者
        // 9A-18：告警状态写线程只在关服时关（reload 复用实例，不能关线程池）
        shutdownAlertStateWriter();
        // 9A-18：历史写线程必须在采集停止之后关——它要把队列残余刷盘，否则最后一批样本丢失
        shutdownHistoryFileStore();
        shutdownReportGenerator(); // ③ 输出：最后关报告执行器
        getLogger().info(Messages.get("shutdown-bye"));
        instance = null;
    }

    /**
     * 9A-10 #5：关闭报告执行器。
     *
     * <p>{@link ReportGenerator#shutdown()} 使用 {@code shutdownNow()}——关服阶段丢弃排队的报告请求、
     * 中断正在进行的写盘，绝不让主线程为一份没人看的报告多等 IO。已生成的文件不受影响。</p>
     */
    /**
     * 关闭历史文件写线程（9A-18）：必须在采集任务停止之后调用——
     * 写线程要把队列中尚未落盘的样本刷完再退出，否则关服瞬间的最后一批数据会丢。
     */
    private void shutdownHistoryFileStore() {
        if (historyFileStore != null) {
            try {
                historyFileStore.shutdown();
            } catch (Exception | LinkageError e) {
                getLogger().warning("[PerfScope] 历史写线程关闭失败: " + e.getMessage());
            }
        }
    }

    private void shutdownReportGenerator() {
        if (reportGenerator != null) {
            try {
                reportGenerator.shutdown();
            } catch (Exception | LinkageError e) {
                getLogger().warning("[PerfScope] 报告执行器关闭失败: " + e.getMessage());
            }
        }
    }

    // ==================== 任务启停（P0-14 收敛：P0-8 替换为 SchedulerAdapter 时只需改这四个方法内部） ====================

    /** 启动采集任务（当前经由 MetricsCollector 走 Bukkit 调度器）。A9a：调度异常捕获，不留半状态。 */
    private void startCollectionTasks() {
        try {
            metricsCollector.start();
        } catch (Exception | LinkageError e) {
            getLogger().severe("[PerfScope] 采集任务启动失败: " + e.getMessage());
            getLogger().log(java.util.logging.Level.SEVERE, "[PerfScope] 采集任务启动异常堆栈", e);
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
            getLogger().warning("[PerfScope] 采集健康看门狗启动失败（不影响采集本体，/perfscope status 仍可用）: "
                + e.getMessage());
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
            getLogger().warning("[PerfScope] 采集健康检查异常（已跳过本轮）: " + e.getMessage());
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
            getLogger().severe("[PerfScope] 采集已停止更新 " + (mc.staleForMs() / 1000L)
                + " 秒（最后成功采集 " + clockOf(mc.lastCollectAtMs()) + "，连续失败 "
                + mc.consecutiveCollectFailures() + " 次）——"
                + "/perfscope 显示的数据已过期，请执行 /perfscope reload，仍无效则重启服务器");
        } else {
            long lastAt = mc.lastCollectAtMs();
            getLogger().info("[PerfScope] 采集已恢复" + (lastAt > 0L
                ? "（最后成功采集 " + clockOf(lastAt) + "）"
                : "（已进入新任务的预热窗口，尚未产出首轮数据）"));
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
            getLogger().warning("[PerfScope] JVM 异步采集已停止更新（内存/CPU/GC 停留在 "
                + clockOf(jvmAt) + " 的数值）——请执行 /perfscope reload，仍无效则重启服务器");
        } else {
            getLogger().info("[PerfScope] JVM 异步采集已恢复（最后更新 " + clockOf(jvmAt) + "）");
        }
    }

    /** 9A-20：epoch ms → "HH:mm:ss"（本地时区，与命令层"采集时间"同口径）；0 → "从未"。 */
    private static String clockOf(long epochMs) {
        if (epochMs <= 0L) {
            return "从未";
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
            getLogger().warning("[PerfScope] report.auto_export_interval=" + configuredMinutes
                + " 非法（需 ≥1 分钟），已按 1 分钟处理");
        }
        long intervalMinutes = Math.max(1, configuredMinutes);
        long intervalTicks = intervalMinutes * 60L * 20L;
        try {
            // P0-8：经 SchedulerAdapter 走异步调度（Folia 下自动用 AsyncScheduler）
            autoReportTask = SchedulerAdapter.runAsyncTimer(
                this, () -> reportGenerator.generateAndSave(), intervalTicks, intervalTicks);
        } catch (Exception | LinkageError e) {
            getLogger().severe("[PerfScope] 自动报告任务启动失败: " + e.getMessage());
            autoReportTask = null;
            return;
        }
        getLogger().info("  [+] 自动报告已启用 (间隔: " + intervalMinutes + " 分钟)");
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
                getLogger().warning("[PerfScope] 告警状态写线程关闭失败: " + e.getMessage());
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
    private void startBstats() {
        if (!configManager.get().general_bstatsEnabled) {
            getLogger().info("[PerfScope] bStats 匿名统计未启用 (general.bstats-enabled=false)");
            return;
        }
        int bstatsPluginId = BSTATS_PLUGIN_ID; // 防常量折叠，见方法注释
        if (bstatsPluginId <= 0) {
            getLogger().info("[PerfScope] bStats 匿名统计未启用 (pluginId 占位为 0，作者注册 bstats.org 后替换生效)");
            return;
        }
        try {
            new Metrics(this, bstatsPluginId);
            getLogger().info("[PerfScope] bStats 匿名统计已启用 (pluginId=" + bstatsPluginId + ")");
        } catch (Exception | LinkageError e) {
            // general.debug 门控堆栈（与 safeCollect / monitor 推送同款日志口径）
            if (configManager.get().general_debug) {
                getLogger().log(java.util.logging.Level.WARNING, "[PerfScope] bStats 匿名统计初始化失败（已跳过，不影响插件本体）", e);
            } else {
                getLogger().warning("[PerfScope] bStats 匿名统计初始化失败（已跳过，不影响插件本体）: " + e.getMessage());
            }
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
            getLogger().severe("[PerfScope] 配置加载失败，将按旧配置继续运行: " + e.getMessage());
            getLogger().log(java.util.logging.Level.SEVERE, "[PerfScope] 配置加载异常堆栈", e);
        }
        Messages.load(this); // Wave 8D：消息层随 reload 刷新（缺失/损坏自动回退默认）

        // 无论 load 成败，任务必须重建：绝不允许"采集停了不重建"的半途冻结态
        startCollectionTasks();
        startAlertService();
        // Wave 7D：webhook 配置随 reload 刷新（enabled/url 变化即生效；就绪状态内部重算）
        if (webhookNotifier != null) {
            webhookNotifier.reload();
        }
        startDailySummary();
        if (configManager.get().report_autoExport) {
            startAutoReport();
        }
        try {
            reportGenerator.cleanOldReports();
        } catch (Exception | LinkageError e) {
            getLogger().warning("[PerfScope] 旧报告清理失败: " + e.getMessage());
        }
        // 9A-18：保留天数可能被改过；清理已从写入路径剥离，reload 时显式跑一次
        try {
            historyFileStore.runRetention(configManager.get().history_persistRetentionDays);
        } catch (Exception | LinkageError e) {
            getLogger().warning("[PerfScope] 历史文件清理失败: " + e.getMessage());
        }
        if (loadFailed) {
            getLogger().warning(Messages.get("reload-failed-old-config")); // 9A-24：接线死键
        } else {
            // 9A-24：控制台用独立键，默认值与改造前逐字一致（"PerfScope " 前缀保留，日志更好检索），
            // 与聊天侧的 reloaded 分开——专业部署可能只想中性化聊天回复而不动控制台日志
            getLogger().info(Messages.get("reloaded-console"));
        }
    }

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
