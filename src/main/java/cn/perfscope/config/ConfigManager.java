package cn.perfscope.config;

import cn.perfscope.PerfScope;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * 配置管理器 - 管理所有 PerfScope 配置项
 * 支持高度自定义，各世界可独立覆盖全局设置
 */
public class ConfigManager {

    private final PerfScope plugin;
    private ConfigData config;
    private File configFile;

    public ConfigManager(PerfScope plugin) {
        this.plugin = plugin;
    }

    public void load() {
        // 保存默认配置
        plugin.saveDefaultConfig();
        configFile = new File(plugin.getDataFolder(), "config.yml");

        FileConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        config = new ConfigData();

        // === 常规设置 ===
        config.general_updateInterval = yaml.getInt("general.update_interval", 20);
        config.general_language = yaml.getString("general.language", "zh_cn");
        config.general_verboseStartup = yaml.getBoolean("general.verbose_startup", true);
        config.general_debug = yaml.getBoolean("general.debug", false);

        // === TPS ===
        config.tps_enabled = yaml.getBoolean("tps.enabled", true);
        config.tps_warningThreshold = yaml.getDouble("tps.warning_threshold", 18.0);
        config.tps_criticalThreshold = yaml.getDouble("tps.critical_threshold", 15.0);

        // === MSPT ===
        config.mspt_enabled = yaml.getBoolean("mspt.enabled", true);
        config.mspt_warningThreshold = yaml.getDouble("mspt.warning_threshold", 45.0);
        config.mspt_criticalThreshold = yaml.getDouble("mspt.critical_threshold", 50.0);

        // === 玩家 ===
        config.players_enabled = yaml.getBoolean("players.enabled", true);
        config.players_warningThreshold = yaml.getInt("players.warning_threshold", 80);
        config.players_criticalThreshold = yaml.getInt("players.critical_threshold", 95);

        // === 实体 ===
        config.entities_enabled = yaml.getBoolean("entities.enabled", true);
        config.entities_totalWarningThreshold = yaml.getInt("entities.total_warning_threshold", 2000);
        config.entities_totalCriticalThreshold = yaml.getInt("entities.total_critical_threshold", 3000);
        config.entities_trackedTypes = new HashSet<>(yaml.getStringList("entities.tracked_types"));

        // === 区块 ===
        config.chunks_enabled = yaml.getBoolean("chunks.enabled", true);
        config.chunks_loadedWarningThreshold = yaml.getInt("chunks.loaded_warning_threshold", 1500);
        config.chunks_loadedCriticalThreshold = yaml.getInt("chunks.loaded_critical_threshold", 2500);

        // === 内存 ===
        config.memory_enabled = yaml.getBoolean("memory.enabled", true);
        config.memory_heapWarningThreshold = yaml.getInt("memory.heap_warning_threshold", 80);
        config.memory_heapCriticalThreshold = yaml.getInt("memory.heap_critical_threshold", 90);

        // === CPU ===
        config.cpu_enabled = yaml.getBoolean("cpu.enabled", true);
        config.cpu_processWarningThreshold = yaml.getDouble("cpu.process_warning_threshold", 80.0);
        config.cpu_systemWarningThreshold = yaml.getDouble("cpu.system_warning_threshold", 90.0);

        // === 红石 ===
        config.redstone_enabled = yaml.getBoolean("redstone.enabled", true);

        // === Tile Entity ===
        config.tileEntities_enabled = yaml.getBoolean("tile_entities.enabled", true);

        // === 网络 ===
        config.network_enabled = yaml.getBoolean("network.enabled", true);
        config.network_warningThreshold = yaml.getLong("network.warning_threshold", 10485760L);

        // === 报告 ===
        config.report_autoExport = yaml.getBoolean("report.auto_export", false);
        config.report_autoExportInterval = yaml.getInt("report.auto_export_interval", 30);
        config.report_format = yaml.getString("report.format", "html");
        config.report_retentionDays = yaml.getInt("report.retention_days", 7);
        config.report_verboseReport = yaml.getBoolean("report.verbose_report", true);

        // === 各世界设置 ===
        config.worldSettings = new HashMap<>();
        ConfigurationSection worldsSection = yaml.getConfigurationSection("worlds");
        if (worldsSection != null) {
            for (String worldName : worldsSection.getKeys(false)) {
                WorldSettings ws = new WorldSettings();
                ws.worldName = worldName;
                ws.entities_totalWarningThreshold = worldsSection.getInt(
                    worldName + ".entities.total_warning_threshold",
                    config.entities_totalWarningThreshold);
                ws.entities_totalCriticalThreshold = worldsSection.getInt(
                    worldName + ".entities.total_critical_threshold",
                    config.entities_totalCriticalThreshold);
                config.worldSettings.put(worldName, ws);
            }
        }
    }

    public ConfigData get() {
        return config;
    }

    public WorldSettings getWorldSettings(String worldName) {
        return config.worldSettings.getOrDefault(worldName, null);
    }

    /**
     * 配置数据容器
     */
    public static class ConfigData {
        // 常规
        public int general_updateInterval;
        public String general_language;
        public boolean general_verboseStartup;
        public boolean general_debug;

        // TPS
        public boolean tps_enabled;
        public double tps_warningThreshold;
        public double tps_criticalThreshold;

        // MSPT
        public boolean mspt_enabled;
        public double mspt_warningThreshold;
        public double mspt_criticalThreshold;

        // 玩家
        public boolean players_enabled;
        public int players_warningThreshold;
        public int players_criticalThreshold;

        // 实体
        public boolean entities_enabled;
        public int entities_totalWarningThreshold;
        public int entities_totalCriticalThreshold;
        public Set<String> entities_trackedTypes;

        // 区块
        public boolean chunks_enabled;
        public int chunks_loadedWarningThreshold;
        public int chunks_loadedCriticalThreshold;

        // 内存
        public boolean memory_enabled;
        public int memory_heapWarningThreshold;
        public int memory_heapCriticalThreshold;

        // CPU
        public boolean cpu_enabled;
        public double cpu_processWarningThreshold;
        public double cpu_systemWarningThreshold;

        // 红石
        public boolean redstone_enabled;

        // Tile Entity
        public boolean tileEntities_enabled;

        // 网络
        public boolean network_enabled;
        public long network_warningThreshold;

        // 报告
        public boolean report_autoExport;
        public int report_autoExportInterval;
        public String report_format;
        public int report_retentionDays;
        public boolean report_verboseReport;

        // 各世界设置
        public Map<String, WorldSettings> worldSettings;
    }

    /**
     * 单个世界的覆盖设置
     */
    public static class WorldSettings {
        public String worldName;
        public int entities_totalWarningThreshold;
        public int entities_totalCriticalThreshold;
    }
}
