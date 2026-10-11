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

        // P0-7：红石采集已下线，不再读取 redstone.*（旧配置残留键天然忽略）

        // === Tile Entity ===
        config.tileEntities_enabled = yaml.getBoolean("tile_entities.enabled", true);

        // P0-7：网络采集已下线，不再读取 network.*（旧配置残留键天然忽略）

        // === 报告 ===
        config.report_autoExport = yaml.getBoolean("report.auto_export", false);
        config.report_autoExportInterval = yaml.getInt("report.auto_export_interval", 30);
        config.report_format = yaml.getString("report.format", "html");
        config.report_retentionDays = yaml.getInt("report.retention_days", 7);

        // === 告警（P0-4 基础版：控制台 + OP 游戏内 + 冷却） ===
        config.alerts_enabled = yaml.getBoolean("alerts.enabled", true);
        config.alerts_console = yaml.getBoolean("alerts.console", true);
        config.alerts_in_game = yaml.getBoolean("alerts.in-game", true);
        config.alerts_cooldownSeconds = yaml.getInt("alerts.cooldown-seconds", 300);
        config.alerts_notifyRecovery = yaml.getBoolean("alerts.notify-recovery", true);

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

        // P0-7：redstone_enabled 已删除（红石采集下线）

        // Tile Entity
        public boolean tileEntities_enabled;

        // P0-7：network_enabled / network_warningThreshold 已删除（网络采集下线）

        // 报告
        public boolean report_autoExport;
        public int report_autoExportInterval;
        public String report_format;
        public int report_retentionDays;

        // 告警（P0-4 基础版）
        public boolean alerts_enabled;
        public boolean alerts_console;
        public boolean alerts_in_game;
        public int alerts_cooldownSeconds;
        public boolean alerts_notifyRecovery;

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
