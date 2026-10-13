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
    /** 配置快照——load() 末尾整体替换发布（单次发布、原子可见）；volatile 保证异步任务（如自动报告）读取的跨线程可见性（A9a Wave4 修正） */
    private volatile ConfigData config;
    private File configFile;

    public ConfigManager(PerfScope plugin) {
        this.plugin = plugin;
    }

    public void load() {
        // 保存默认配置
        plugin.saveDefaultConfig();
        configFile = new File(plugin.getDataFolder(), "config.yml");

        FileConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        // A9a（Wave4 修正）：本地 fresh 承载解析结果，全部填充且阈值校验通过后于方法末尾一次性发布到
        // this.config（单次发布、原子可见）；外部（含异步任务）不再可能读到"开头建空壳、逐键填充"的半解析态
        ConfigData fresh = new ConfigData();

        // === 常规设置 ===
        fresh.general_updateInterval = yaml.getInt("general.update_interval", 20);
        fresh.general_verboseStartup = yaml.getBoolean("general.verbose_startup", true);
        fresh.general_debug = yaml.getBoolean("general.debug", false);

        // === TPS ===
        fresh.tps_enabled = yaml.getBoolean("tps.enabled", true);
        fresh.tps_warningThreshold = yaml.getDouble("tps.warning_threshold", 18.0);
        fresh.tps_criticalThreshold = yaml.getDouble("tps.critical_threshold", 15.0);

        // === MSPT ===
        fresh.mspt_enabled = yaml.getBoolean("mspt.enabled", true);
        fresh.mspt_warningThreshold = yaml.getDouble("mspt.warning_threshold", 45.0);
        fresh.mspt_criticalThreshold = yaml.getDouble("mspt.critical_threshold", 50.0);

        // === 玩家 ===
        fresh.players_enabled = yaml.getBoolean("players.enabled", true);
        fresh.players_warningThreshold = yaml.getInt("players.warning_threshold", 80);
        fresh.players_criticalThreshold = yaml.getInt("players.critical_threshold", 95);

        // === 实体 ===
        fresh.entities_enabled = yaml.getBoolean("entities.enabled", true);
        fresh.entities_totalWarningThreshold = yaml.getInt("entities.total_warning_threshold", 2000);
        fresh.entities_totalCriticalThreshold = yaml.getInt("entities.total_critical_threshold", 3000);

        // === 区块 ===
        fresh.chunks_enabled = yaml.getBoolean("chunks.enabled", true);
        fresh.chunks_loadedWarningThreshold = yaml.getInt("chunks.loaded_warning_threshold", 1500);
        fresh.chunks_loadedCriticalThreshold = yaml.getInt("chunks.loaded_critical_threshold", 2500);

        // === 内存 ===
        fresh.memory_enabled = yaml.getBoolean("memory.enabled", true);
        fresh.memory_heapWarningThreshold = yaml.getInt("memory.heap_warning_threshold", 80);
        fresh.memory_heapCriticalThreshold = yaml.getInt("memory.heap_critical_threshold", 90);

        // === CPU ===
        fresh.cpu_enabled = yaml.getBoolean("cpu.enabled", true);
        fresh.cpu_processWarningThreshold = yaml.getDouble("cpu.process_warning_threshold", 80.0);
        fresh.cpu_systemWarningThreshold = yaml.getDouble("cpu.system_warning_threshold", 90.0);

        // P0-7：红石采集已下线，不再读取 redstone.*（旧配置残留键天然忽略）

        // === Tile Entity ===
        fresh.tileEntities_enabled = yaml.getBoolean("tile_entities.enabled", true);

        // P0-7：网络采集已下线，不再读取 network.*（旧配置残留键天然忽略）

        // === 报告 ===
        fresh.report_autoExport = yaml.getBoolean("report.auto_export", false);
        fresh.report_autoExportInterval = yaml.getInt("report.auto_export_interval", 30);
        fresh.report_format = yaml.getString("report.format", "html");
        fresh.report_retentionDays = yaml.getInt("report.retention_days", 7);

        // === 告警（P0-4 基础版：控制台 + OP 游戏内 + 冷却） ===
        fresh.alerts_enabled = yaml.getBoolean("alerts.enabled", true);
        fresh.alerts_console = yaml.getBoolean("alerts.console", true);
        fresh.alerts_in_game = yaml.getBoolean("alerts.in-game", true);
        fresh.alerts_cooldownSeconds = yaml.getInt("alerts.cooldown-seconds", 300);
        fresh.alerts_notifyRecovery = yaml.getBoolean("alerts.notify-recovery", true);

        // === 各世界设置 ===
        fresh.worldSettings = new HashMap<>();
        ConfigurationSection worldsSection = yaml.getConfigurationSection("worlds");
        if (worldsSection != null) {
            for (String worldName : worldsSection.getKeys(false)) {
                WorldSettings ws = new WorldSettings();
                ws.worldName = worldName;
                ws.entities_totalWarningThreshold = worldsSection.getInt(
                    worldName + ".entities.total_warning_threshold",
                    fresh.entities_totalWarningThreshold);
                ws.entities_totalCriticalThreshold = worldsSection.getInt(
                    worldName + ".entities.total_critical_threshold",
                    fresh.entities_totalCriticalThreshold);
                fresh.worldSettings.put(worldName, ws);
            }
        }

        // A9c（T4）：全量阈值合法性校验（倒置/非有限值 → 回退默认值并记 warning，绝不静默、不抛异常）
        // 校验作用于 fresh（发布前），日志照旧；任一环节抛异常都不会触达下方发布语句，this.config 保持旧值
        validateThresholds(fresh);

        // A9a（Wave4 修正）：全部填充与校验完成后单次发布——中途抛异常则 this.config 保持旧值（reload 按旧配置继续）
        this.config = fresh;
    }

    /**
     * A9c（T4）：阈值合法性校验——load() 末尾集中调用，结果一律"回退默认值 + warning 日志"。
     *
     * <p>校验规则：</p>
     * <ul>
     *   <li><b>高值差</b>阈值对（mspt/players/entities/chunks/memory：数值越高越差）——
     *       正常顺序为 warning &lt;= critical，若 warning &gt; critical 则档位坍缩（warning 档不可达），视为倒置；</li>
     *   <li><b>低值差</b>阈值对（tps：数值越低越差）——正常顺序为 warning &gt;= critical，反之视为倒置；</li>
     *   <li>非有限数值（NaN/Infinity，redteam RT-01）——比较恒为 false 会导致恒静默或恒告警，同样回退；</li>
     *   <li>worlds 段覆盖值对——倒置时回退到（已校验的）全局值；</li>
     *   <li>alerts.cooldown-seconds——负值钳制为 0。</li>
     * </ul>
     */
    private void validateThresholds(ConfigData cfg) {
        // === 全局阈值对 ===
        // TPS（低值差，warning 应 >= critical）
        double[] tps = sanitizePair("tps.warning_threshold/critical_threshold",
            cfg.tps_warningThreshold, cfg.tps_criticalThreshold, 18.0, 15.0, false);
        cfg.tps_warningThreshold = tps[0];
        cfg.tps_criticalThreshold = tps[1];

        // MSPT（高值差，warning 应 <= critical）
        double[] mspt = sanitizePair("mspt.warning_threshold/critical_threshold",
            cfg.mspt_warningThreshold, cfg.mspt_criticalThreshold, 45.0, 50.0, true);
        cfg.mspt_warningThreshold = mspt[0];
        cfg.mspt_criticalThreshold = mspt[1];

        // 玩家（高值差，warning 应 <= critical）
        int[] players = sanitizeIntPair("players.warning_threshold/critical_threshold",
            cfg.players_warningThreshold, cfg.players_criticalThreshold, 80, 95);
        cfg.players_warningThreshold = players[0];
        cfg.players_criticalThreshold = players[1];

        // 实体（高值差，采集层为逐世界语义，warning 应 <= critical）
        int[] entities = sanitizeIntPair("entities.total_warning_threshold/critical_threshold",
            cfg.entities_totalWarningThreshold, cfg.entities_totalCriticalThreshold, 2000, 3000);
        cfg.entities_totalWarningThreshold = entities[0];
        cfg.entities_totalCriticalThreshold = entities[1];

        // 区块（高值差，warning 应 <= critical）
        int[] chunks = sanitizeIntPair("chunks.loaded_warning_threshold/critical_threshold",
            cfg.chunks_loadedWarningThreshold, cfg.chunks_loadedCriticalThreshold, 1500, 2500);
        cfg.chunks_loadedWarningThreshold = chunks[0];
        cfg.chunks_loadedCriticalThreshold = chunks[1];

        // 内存（高值差，warning 应 <= critical）
        int[] memory = sanitizeIntPair("memory.heap_warning_threshold/critical_threshold",
            cfg.memory_heapWarningThreshold, cfg.memory_heapCriticalThreshold, 80, 90);
        cfg.memory_heapWarningThreshold = memory[0];
        cfg.memory_heapCriticalThreshold = memory[1];

        // CPU：仅单值 warning 阈值（无 critical 配对），只校验非有限数值
        if (!Double.isFinite(cfg.cpu_processWarningThreshold)) {
            plugin.getLogger().warning("[PerfScope] 配置校验：cpu.process_warning_threshold 为非有限数值(NaN/Infinity)，"
                + "已回退默认值：" + cfg.cpu_processWarningThreshold + " -> 80.0");
            cfg.cpu_processWarningThreshold = 80.0;
        }
        if (!Double.isFinite(cfg.cpu_systemWarningThreshold)) {
            plugin.getLogger().warning("[PerfScope] 配置校验：cpu.system_warning_threshold 为非有限数值(NaN/Infinity)，"
                + "已回退默认值：" + cfg.cpu_systemWarningThreshold + " -> 90.0");
            cfg.cpu_systemWarningThreshold = 90.0;
        }

        // === worlds 段覆盖值对（倒置时回退到已校验的全局值） ===
        if (cfg.worldSettings != null) {
            for (Map.Entry<String, WorldSettings> entry : cfg.worldSettings.entrySet()) {
                WorldSettings ws = entry.getValue();
                int[] pair = sanitizeIntPair(
                    "worlds." + entry.getKey() + ".entities.total_warning_threshold/critical_threshold",
                    ws.entities_totalWarningThreshold, ws.entities_totalCriticalThreshold,
                    cfg.entities_totalWarningThreshold, cfg.entities_totalCriticalThreshold);
                ws.entities_totalWarningThreshold = pair[0];
                ws.entities_totalCriticalThreshold = pair[1];
            }
        }

        // === alerts.cooldown-seconds：负值钳制为 0 ===
        if (cfg.alerts_cooldownSeconds < 0) {
            plugin.getLogger().warning("[PerfScope] 配置校验：alerts.cooldown-seconds 为负值("
                + cfg.alerts_cooldownSeconds + ")，已钳制为 0");
            cfg.alerts_cooldownSeconds = 0;
        }
    }

    /**
     * T4：double 阈值对校验——非有限值或倒置时记 warning 并回退默认值。
     *
     * @param highIsBad true=数值越高越差（正常 warning &lt;= critical）；false=数值越低越差（正常 warning &gt;= critical）
     * @return {warning, critical}（合法原值或回退后的默认值）
     */
    private double[] sanitizePair(String key, double w, double c, double defW, double defC, boolean highIsBad) {
        boolean nonFinite = !Double.isFinite(w) || !Double.isFinite(c);
        boolean inverted = highIsBad ? w > c : w < c;   // NaN 参与比较恒 false，已由 nonFinite 分支先拦
        if (!nonFinite && !inverted) {
            return new double[]{w, c};
        }
        warnThresholdFallback(key, nonFinite, w, c);
        return new double[]{defW, defC};
    }

    /**
     * T4：int 阈值对校验（高值差）——warning &gt; critical 时记 warning 并回退默认值。
     *
     * @return {warning, critical}（合法原值或回退后的默认值）
     */
    private int[] sanitizeIntPair(String key, int w, int c, int defW, int defC) {
        if (w <= c) {
            return new int[]{w, c};
        }
        warnThresholdFallback(key, false, w, c);
        return new int[]{defW, defC};
    }

    /**
     * T4：阈值非法统一 warning 日志（不静默）——倒置用既定模板；非有限值附独立说明。
     */
    private void warnThresholdFallback(String key, boolean nonFinite, Object w, Object c) {
        if (nonFinite) {
            plugin.getLogger().warning("[PerfScope] 配置校验：" + key
                + " 存在非有限数值(NaN/Infinity)，已回退默认值：w=" + w + " c=" + c);
        } else {
            plugin.getLogger().warning("[PerfScope] 配置校验：" + key
                + " 的 warning(critical) 设置倒置，已回退默认值：w=" + w + " c=" + c);
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
