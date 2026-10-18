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
        this.historyFileStore = new HistoryFileStore(this);
        this.historyStore.setFileStore(historyFileStore);
        historyFileStore.cleanOld(configManager.get().history_persistRetentionDays);

        // Wave 8A：诊断引擎（无状态构造；规则只读快照与历史，由命令层按需调用）
        this.diagnosticEngine = new DiagnosticEngine(this, historyStore);

        // 初始化性能采集器
        this.metricsCollector = new MetricsCollector(this, historyStore);
        startCollectionTasks();

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
            // MC-1 标注层：一级 getTickTimes 或二级 getAverageTickTime 均非"估算"，仅三级 TPS 反推才是
            boolean exactMspt = ServerCompat.PAPER_MSPT_AVAILABLE || ServerCompat.PAPER_AVG_TICK_AVAILABLE;
            getLogger().info("  MSPT精确采集: " + (exactMspt ? "Paper API" : "TPS估算"));
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
        // P0-12：先停 monitor 任务，再停自动报告与采集（顺序不可换）
        if (command != null) {
            command.shutdown();
        }
        stopAutoReport();
        // Wave 7D：先停日报（消费告警与历史），再停告警，最后停采集
        stopDailySummary();
        // P0-4：先停告警服务，再停采集（避免告警定时器读到已停采集的旧快照）
        stopAlertService();
        stopCollectionTasks();
        getLogger().info(Messages.get("shutdown-bye"));
        instance = null;
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
    }

    /** 停止采集任务。 */
    private void stopCollectionTasks() {
        if (metricsCollector != null) {
            metricsCollector.stop();
        }
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
        if (loadFailed) {
            getLogger().warning("PerfScope 配置重载未完成（已按旧配置重启采集/告警）");
        } else {
            getLogger().info("PerfScope 配置已重载喵~");
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
