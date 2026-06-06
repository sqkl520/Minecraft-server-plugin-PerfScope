package cn.perfscope;

import cn.perfscope.command.PerfScopeCommand;
import cn.perfscope.compat.ServerCompat;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.report.ReportGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public final class PerfScope extends JavaPlugin {

    private static PerfScope instance;

    private ConfigManager configManager;
    private MetricsCollector metricsCollector;
    private ReportGenerator reportGenerator;

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

        // 初始化性能采集器
        this.metricsCollector = new MetricsCollector(this);
        metricsCollector.start();

        // 初始化报告生成器
        this.reportGenerator = new ReportGenerator(this);

        // 注册指令
        PerfScopeCommand command = new PerfScopeCommand(this);
        getCommand("perfscope").setExecutor(command);
        getCommand("perfscope").setTabCompleter(command);

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
            // 功能可用性
            getLogger().info("  MSPT精确采集: " + (ServerCompat.PAPER_MSPT_AVAILABLE ? "Paper API" : "TPS估算"));
            getLogger().info("  ActionBar: " + (ServerCompat.PAPER_ACTION_BAR_AVAILABLE ? "可用" : "不可用"));
            getLogger().info("  红石追踪: " + (ServerCompat.PAPER_REDSTONE_AVAILABLE ? "可用" : "不可用"));
            getLogger().info("  网络统计: " + (ServerCompat.PAPER_NETWORK_AVAILABLE ? "可用" : "不可用"));
        }

        getLogger().info("========================================");
        getLogger().info("  PerfScope 启动完成喵~");
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        if (metricsCollector != null) {
            metricsCollector.stop();
        }
        getLogger().info("PerfScope 已关闭，再见喵~");
        instance = null;
    }

    private void startAutoReport() {
        long intervalTicks = configManager.get().report_autoExportInterval * 60L * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            reportGenerator.generateAndSave();
        }, intervalTicks, intervalTicks);
        getLogger().info("  [+] 自动报告已启用 (间隔: " +
            configManager.get().report_autoExportInterval + " 分钟)");
    }

    public void reload() {
        configManager.load();
        reportGenerator.cleanOldReports();
        getLogger().info("PerfScope 配置已重载喵~");
    }

    public static PerfScope getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public ReportGenerator getReportGenerator() {
        return reportGenerator;
    }
}
