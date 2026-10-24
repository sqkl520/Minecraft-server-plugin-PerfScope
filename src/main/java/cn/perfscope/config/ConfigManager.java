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
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 配置管理器 - 管理所有 PerfScope 配置项
 * 支持高度自定义，各世界可独立覆盖全局设置
 *
 * <p><b>9A-11 配置面强化</b>：</p>
 * <ul>
 *   <li>所有读取统一走 {@code readBool/readInt/readDouble/readString}——
 *       缺键记一次 INFO，类型写错记 WARNING 并带上"原值 / 实际采用值"，不再静默回退；</li>
 *   <li>新增 {@code config-version}：缺键视为旧版配置，备份原文件后把版本号写回磁盘；</li>
 *   <li>未知配置键逐条 WARNING（已知键集合取自 jar 内默认 config.yml，不会随代码漂移）；</li>
 *   <li>{@code worlds} 段的世界名与 {@link Bukkit#getWorlds()} 校验（世界尚未加载时跳过）；</li>
 *   <li>CPU 阈值增加 {@code 0 < v <= 100} 范围校验；</li>
 *   <li>{@link #load()} 返回 {@link LoadResult}，供调用方区分"加载成功 / 加载失败"。</li>
 * </ul>
 */
public class ConfigManager {

    /** 9A-11：当前配置文件结构版本。新增/重命名/删除配置键时递增，并在 load() 里补迁移逻辑。 */
    public static final int CURRENT_CONFIG_VERSION = 1;

    /** 9A-11：配置版本键名（位于 config.yml 顶层）。 */
    public static final String CONFIG_VERSION_KEY = "config-version";

    private final PerfScope plugin;
    /** 配置快照——load() 末尾整体替换发布（单次发布、原子可见）；volatile 保证异步任务（如自动报告）读取的跨线程可见性（A9a Wave4 修正） */
    private volatile ConfigData config;

    /** 9A-11：最近一次 load() 的结果（供命令层 / 重载逻辑查询；加载失败时保持失败态）。 */
    private volatile LoadResult lastLoadResult;

    private File configFile;

    /** 9A-11：本轮 load() 使用的 yaml 句柄（仅 load() 内部使用，勿在其它线程读取）。 */
    private FileConfiguration yaml;

    /** 9A-11：本轮缺键（key -> 采用的默认值），load() 末尾汇总成一条 INFO，避免 50 行刷屏。 */
    private final Map<String, Object> missingKeysThisLoad = new LinkedHashMap<>();

    /** 9A-11：本轮已告警过的键——同一键（含 worlds 动态键）只警告一次。 */
    private final Set<String> typeWarnedThisLoad = new HashSet<>();

    /** 9A-11：类型告警文本（供 LoadResult 汇总，命令层可展示）。 */
    private final List<String> typeWarningsThisLoad = new ArrayList<>();

    public ConfigManager(PerfScope plugin) {
        this.plugin = plugin;
    }

    /**
     * 加载配置。
     *
     * <p><b>9A-11 接口变更</b>：返回值从 {@code void} 改为 {@link LoadResult}。
     * 现有调用方 {@code ConfigManager.load();} 形式仍然合法（Java 允许忽略返回值），
     * 但推荐改为 {@code LoadResult r = configManager.load(); if (!r.success) { ... }}，
     * 以便 reload 时告知用户"本次未生效、仍按旧配置运行"。</p>
     *
     * <p><b>失败语义</b>：YAML 语法错误 / 文件内容为空时，记录 SEVERE 并抛出
     * {@link IllegalStateException}（不发布任何新值，{@link #get()} 保持旧快照），
     * 由调用方按"加载失败、按旧配置继续"处理；其余情况一律正常返回，状态放在
     * {@link LoadResult} 里（版本迁移、缺键、未知键、未知世界名）。</p>
     */
    public LoadResult load() {
        // 保存默认配置
        plugin.saveDefaultConfig();
        configFile = new File(plugin.getDataFolder(), "config.yml");

        // 9A-11：每轮重置收集器（reload 复用同一实例）
        missingKeysThisLoad.clear();
        typeWarnedThisLoad.clear();
        typeWarningsThisLoad.clear();
        migrating = false;

        FileConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);

        // 9A-11：Bukkit 的 loadConfiguration 会把语法错误吞成"空配置"，继续走就是全默认值静默覆盖。
        // 这里显式识别：文件里有实际内容（非仅注释/空行）却解析出 0 个顶层键 → 视为加载失败，保持旧快照。
        if (configFile.exists() && hasNonCommentContent(configFile) && yaml.getKeys(false).isEmpty()) {
            String msg = "config.yml 解析失败（YAML 语法错误？），本次加载已放弃，继续使用上一份配置";
            plugin.getLogger().severe("[PerfScope] " + msg);
            plugin.getLogger().severe("[PerfScope] 请修正 config.yml 的缩进/引号后执行 /perfscope reload");
            LoadResult failed = LoadResult.failed(msg);
            lastLoadResult = failed;
            throw new IllegalStateException(msg);
        }
        this.yaml = yaml;

        // 9A-11：config-version 检测 / 旧版补写（必须在读值之前，保证缺失键提示基于写回后的语义）
        boolean versionKeyWritten = handleConfigVersion(yaml);
        migrating = versionKeyWritten;
        // 9A-11：写回成功时内存里的 yaml 仍是旧内容（不重读文件），版本号按当前版本计
        int configVersion = versionKeyWritten ? CURRENT_CONFIG_VERSION : readConfigVersion(yaml);

        // A9a（Wave4 修正）：本地 fresh 承载解析结果，全部填充且阈值校验通过后于方法末尾一次性发布到
        // this.config（单次发布、原子可见）；外部（含异步任务）不再可能读到"开头建空壳、逐键填充"的半解析态
        ConfigData fresh = new ConfigData();
        fresh.configVersion = configVersion;

        // === 常规设置 ===
        fresh.general_updateInterval = readInt("general.update_interval", 20);
        fresh.general_verboseStartup = readBool("general.verbose_startup", true);
        fresh.general_debug = readBool("general.debug", false);
        // Wave 6D：bStats 匿名统计开关（缺键默认 true；pluginId 占位期间实际不发送任何数据）
        fresh.general_bstatsEnabled = readBool("general.bstats-enabled", true);

        // === TPS ===
        fresh.tps_enabled = readBool("tps.enabled", true);
        fresh.tps_warningThreshold = readDouble("tps.warning_threshold", 18.0);
        fresh.tps_criticalThreshold = readDouble("tps.critical_threshold", 15.0);

        // === MSPT ===
        fresh.mspt_enabled = readBool("mspt.enabled", true);
        fresh.mspt_warningThreshold = readDouble("mspt.warning_threshold", 45.0);
        fresh.mspt_criticalThreshold = readDouble("mspt.critical_threshold", 50.0);

        // === 玩家 ===
        fresh.players_enabled = readBool("players.enabled", true);
        fresh.players_warningThreshold = readInt("players.warning_threshold", 90);
        fresh.players_criticalThreshold = readInt("players.critical_threshold", 98);

        // === 实体 ===
        fresh.entities_enabled = readBool("entities.enabled", true);
        fresh.entities_totalWarningThreshold = readInt("entities.total_warning_threshold", 2000);
        fresh.entities_totalCriticalThreshold = readInt("entities.total_critical_threshold", 3000);

        // === 区块 ===
        fresh.chunks_enabled = readBool("chunks.enabled", true);
        fresh.chunks_loadedWarningThreshold = readInt("chunks.loaded_warning_threshold", 1500);
        fresh.chunks_loadedCriticalThreshold = readInt("chunks.loaded_critical_threshold", 2500);

        // === 内存 ===
        fresh.memory_enabled = readBool("memory.enabled", true);
        fresh.memory_heapWarningThreshold = readInt("memory.heap_warning_threshold", 85);
        fresh.memory_heapCriticalThreshold = readInt("memory.heap_critical_threshold", 95);

        // === CPU ===
        fresh.cpu_enabled = readBool("cpu.enabled", true);
        fresh.cpu_processWarningThreshold = readDouble("cpu.process_warning_threshold", 80.0);
        fresh.cpu_systemWarningThreshold = readDouble("cpu.system_warning_threshold", 90.0);

        // P0-7：红石采集已下线，不再读取 redstone.*（旧配置残留键由 9A-11 未知键检测点名）

        // === Tile Entity ===
        fresh.tileEntities_enabled = readBool("tile_entities.enabled", true);

        // P0-7：网络采集已下线，不再读取 network.*（旧配置残留键由 9A-11 未知键检测点名）

        // === 报告 ===
        fresh.report_autoExport = readBool("report.auto_export", false);
        fresh.report_autoExportInterval = readInt("report.auto_export_interval", 30);
        fresh.report_format = readString("report.format", "html");
        fresh.report_retentionDays = readInt("report.retention_days", 7);

        // === 告警（P0-4 基础版：控制台 + OP 游戏内 + 冷却；Wave 7C：+ 防抖收敛） ===
        fresh.alerts_enabled = readBool("alerts.enabled", true);
        fresh.alerts_console = readBool("alerts.console", true);
        fresh.alerts_in_game = readBool("alerts.in-game", true);
        fresh.alerts_cooldownSeconds = readInt("alerts.cooldown-seconds", 300);
        fresh.alerts_notifyRecovery = readBool("alerts.notify-recovery", true);
        fresh.alerts_debounceCycles = readInt("alerts.debounce-cycles", 3);
        // 9A-14：告警决策层新增的 5 个键（此前 AlertService 用 RawConfig 直读兜底，
        // 绕过了本类的 config-version / 未知键检测；现收编为正式配置项）
        fresh.alerts_recoveryDebounceCycles = readInt("alerts.recovery-debounce-cycles", 3);
        fresh.alerts_digestMode = readBool("alerts.digest-mode", false);
        fresh.alerts_notifyMitigation = readBool("alerts.notify-mitigation", true);
        fresh.alerts_unknownAfterMinutes = readInt("alerts.unknown-after-minutes", 5);
        fresh.alerts_dailySummaryTimezone = readString("alerts.daily-summary.timezone", "");
        // === 每日摘要与 webhook（Wave 7D） ===
        fresh.alerts_dailySummaryEnabled = readBool("alerts.daily-summary.enabled", false);
        fresh.alerts_dailySummaryTime = readString("alerts.daily-summary.time", "08:00");
        fresh.alerts_webhookEnabled = readBool("alerts.webhook.enabled", false);
        fresh.alerts_webhookType = readString("alerts.webhook.type", "discord");
        fresh.alerts_webhookUrl = readString("alerts.webhook.url", "");

        // === 历史采样（Wave 7A：24h 环形缓冲；重启清零，文件持久化规划于 Wave 8B） ===
        fresh.history_enabled = readBool("history.enabled", true);
        fresh.history_sampleIntervalSeconds = readInt("history.sample-interval-seconds", 60);
        // Wave 8B：文件持久化（按天 CSV；重启不清零）
        fresh.history_persistEnabled = readBool("history.persist", true);
        fresh.history_persistRetentionDays = readInt("history.retention-days", 7);

        // === 热点区块（Wave 8C：全服实体密度 Top5 的坐标定位；全遍历按周期节流） ===
        fresh.hotspots_enabled = readBool("hotspots.enabled", true);
        fresh.hotspots_sampleEveryCycles = readInt("hotspots.sample-every-cycles", 30);
        // 9A-8：单区块实体阈值（区别于全服 entities 阈值——旧实现拿全服阈值/2 卡单区块，
        // 默认要求单区块 1000+ 实体，导致 DIAG-ENT-HOTSPOT 永不触发）
        fresh.hotspots_chunkWarningThreshold = readInt("hotspots.chunk-warning-threshold", 200);

        // === 各世界设置 ===
        fresh.worldSettings = new HashMap<>();
        ConfigurationSection worldsSection = readWorldsSection();
        if (worldsSection != null) {
            for (String worldName : worldsSection.getKeys(false)) {
                WorldSettings ws = new WorldSettings();
                ws.worldName = worldName;
                // 9A-11：worlds 段是"可选覆盖"，缺键属正常情况，走 quiet 读取不记缺失
                ws.entities_totalWarningThreshold = readIntQuiet(
                    "worlds." + worldName + ".entities.total_warning_threshold",
                    fresh.entities_totalWarningThreshold);
                ws.entities_totalCriticalThreshold = readIntQuiet(
                    "worlds." + worldName + ".entities.total_critical_threshold",
                    fresh.entities_totalCriticalThreshold);
                fresh.worldSettings.put(worldName, ws);
            }
        }

        // A9c（T4）：全量阈值合法性校验（倒置/非有限值 → 回退默认值并记 warning，绝不静默、不抛异常）
        // 校验作用于 fresh（发布前），日志照旧；任一环节抛异常都不会触达下方发布语句，this.config 保持旧值
        validateThresholds(fresh);

        // 9A-11：worlds 段世界名校验（世界尚未加载时跳过，避免误报）
        List<String> unknownWorlds = validateWorldNames(fresh);

        // 9A-11：缺键汇总（INFO，一条日志，不刷屏）
        flushMissingKeys();

        // A9a（Wave4 修正）：全部填充与校验完成后单次发布——中途抛异常则 this.config 保持旧值（reload 按旧配置继续）
        this.config = fresh;

        // 9A-11：未知键检测（已知键集合取自 jar 内默认 config.yml，避免代码侧手工维护的清单漂移）
        List<String> unknownKeys = detectUnknownKeys();

        LoadResult result = LoadResult.success(
            configVersion,
            versionKeyWritten,
            new ArrayList<>(missingKeysThisLoad.keySet()),
            unknownKeys,
            unknownWorlds,
            new ArrayList<>(typeWarningsThisLoad));
        lastLoadResult = result;

        // 9A-11：有缺键/类型错误时给一条汇总，方便用户知道"配置没完全按我写的生效"
        if (!result.missingKeys.isEmpty() || !result.typeWarnings.isEmpty()) {
            plugin.getLogger().info("[PerfScope] 配置加载完成：缺键 " + result.missingKeys.size()
                + " 项（用默认值）、类型错误 " + result.typeWarnings.size()
                + " 项（已回退默认值）、未知键 " + result.unknownKeys.size()
                + " 项、未知世界 " + result.invalidWorldNames.size() + " 项");
        }
        return result;
    }

    /** 9A-11：最近一次 load() 的结果；从未加载过时为 {@code null}。 */
    public LoadResult getLastLoadResult() {
        return lastLoadResult;
    }

    /**
     * 9A-11：判断配置文件是否含有实际内容（排除空行与整行注释）。
     * 用于区分"用户把配置清空成只剩注释"（合法）与"YAML 语法错误被 Bukkit 吞掉"（失败）。
     */
    private static boolean hasNonCommentContent(File file) {
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                // 9A-11：Windows 编辑器可能在首行前写入 BOM，先剥掉再判断注释
                String t = line.replace("﻿", "").trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // 读不出来就按"有内容"处理（走解析结果判断，避免漏报）
            return true;
        }
    }

    // ==================================================================================
    // 9A-11：配置版本 / 迁移
    // ==================================================================================

    /**
     * 9A-11：config-version 检测与旧版补写。
     *
     * <p>缺键 → 视为旧版配置：INFO 提示、把当前文件备份为 {@code config.yml.bak-<时间戳>}，
     * 再把 {@code config-version} 追加到文件末尾（<b>追加而非整文件重写</b>——
     * Bukkit 在 1.20.4 的 YamlConfiguration 保存时会丢掉全部注释，重写等于毁掉用户注释）。
     * 旧配置里已存在的键一律保留原值，新增键按默认值生效。</p>
     *
     * @return true = 已把版本号写回磁盘
     */
    private boolean handleConfigVersion(FileConfiguration yaml) {
        if (yaml.contains(CONFIG_VERSION_KEY)) {
            return false;
        }
        plugin.getLogger().info("[PerfScope] 检测到旧版配置（无 config-version），"
            + "将按默认值补齐新增键（已有键保持原值不动）");
        boolean written = writeBackConfigVersion();
        if (written) {
            plugin.getLogger().info("[PerfScope] 已备份原配置并写入 " + CONFIG_VERSION_KEY
                + ": " + CURRENT_CONFIG_VERSION + "（备份文件与原文件同目录）");
        } else {
            plugin.getLogger().warning("[PerfScope] " + CONFIG_VERSION_KEY
                + " 写回磁盘失败（不影响本次运行，但下次启动会再次提示，请检查文件权限）");
        }
        return written;
    }

    /**
     * 9A-11：读取 config-version（缺键按 0 = 旧版处理；非法类型按 0 并 warning）。
     * 版本高于插件支持时给出降级提示（配置里出现了本插件不认识的键）。
     */
    private int readConfigVersion(FileConfiguration yaml) {
        if (!yaml.contains(CONFIG_VERSION_KEY)) {
            return 0;
        }
        Object raw = yaml.get(CONFIG_VERSION_KEY);
        Integer v = coerceInt(raw);
        if (v == null) {
            warnType(CONFIG_VERSION_KEY, raw, 0, "整数");
            return 0;
        }
        if (v > CURRENT_CONFIG_VERSION) {
            plugin.getLogger().warning("[PerfScope] " + CONFIG_VERSION_KEY + "=" + v
                + " 高于本插件支持的版本 " + CURRENT_CONFIG_VERSION
                + "（配置来自更新版本的 PerfScope？）本版本不认识的新键会被忽略");
        } else if (v < CURRENT_CONFIG_VERSION) {
            plugin.getLogger().info("[PerfScope] 配置版本 " + v + " 低于当前 " + CURRENT_CONFIG_VERSION
                + "，新增键将按默认值生效（已有键保持原值）");
        }
        return v;
    }

    /**
     * 9A-11：把 config-version 追加到 config.yml 末尾（先备份）。
     *
     * <p>不用 {@code YamlConfiguration#save} 的原因：Bukkit 1.20.4 的保存会丢掉全部注释，
     * 而本插件的 config.yml 注释承载了大量说明信息（单位、语义、下线说明）。</p>
     *
     * @return true = 写入成功
     */
    private boolean writeBackConfigVersion() {
        try {
            Path path = configFile.toPath();
            if (!Files.exists(path)) {
                // 极端情况：saveDefaultConfig 未落盘（只读目录等），直接新建一个只含版本号的配置文件
                Files.createDirectories(path.getParent());
                Files.write(path, ("# PerfScope 配置" + System.lineSeparator()
                    + CONFIG_VERSION_KEY + ": " + CURRENT_CONFIG_VERSION + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8));
                return true;
            }
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path backup = new File(configFile.getParentFile(), "config.yml.bak-" + stamp).toPath();
            Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES);

            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
                text += System.lineSeparator();
            }
            text += System.lineSeparator()
                + "# 9A-11：配置结构版本（插件自动写入，请勿手改；用于识别旧版配置并补齐新增键）"
                + System.lineSeparator()
                + CONFIG_VERSION_KEY + ": " + CURRENT_CONFIG_VERSION + System.lineSeparator();
            Files.write(path, text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[PerfScope] 写回 " + CONFIG_VERSION_KEY + " 失败: " + e.getMessage());
            return false;
        }
    }

    // ==================================================================================
    // 9A-11：类型安全的读取辅助方法（替代 yaml.getBoolean/getInt/getDouble/getString）
    // ==================================================================================

    /** 9A-11：布尔读取——缺键走默认值 + 汇总 INFO；类型错误走 WARNING；0/1、yes/no 归一化。 */
    private boolean readBool(String key, boolean def) {
        if (!yaml.contains(key)) {
            noteMissing(key, def);
            return def;
        }
        Object raw = yaml.get(key);
        Boolean v = coerceBool(raw);
        if (v == null) {
            warnType(key, raw, def, "布尔值");
            return def;
        }
        return v;
    }

    /** 9A-11：整数读取——缺键走默认值 + 汇总 INFO；非数字类型/非数字字符串 WARNING 后回退。 */
    private int readInt(String key, int def) {
        if (!yaml.contains(key)) {
            noteMissing(key, def);
            return def;
        }
        Object raw = yaml.get(key);
        Integer v = coerceInt(raw);
        if (v == null) {
            warnType(key, raw, def, "整数");
            return def;
        }
        return v;
    }

    /**
     * 9A-11：可选覆盖段的整数读取——缺键直接返回默认值，<b>不记录缺失</b>。
     * 用于 {@code worlds.<世界名>.*} 这类"存在才覆盖、不存在很正常"的键，避免刷屏。
     */
    private int readIntQuiet(String key, int def) {
        if (!yaml.contains(key)) {
            return def;
        }
        Object raw = yaml.get(key);
        Integer v = coerceInt(raw);
        if (v == null) {
            warnType(key, raw, def, "整数");
            return def;
        }
        return v;
    }

    /**
     * 9A-11：小数读取——缺键走默认值 + 汇总 INFO；类型错误 WARNING 后回退。
     *
     * <p>注意：NaN/Infinity 属于合法 double，会在 {@link #validateThresholds} 里被专门识别并回退，
     * 此处不拦截，避免与非有限值的既有告警口径分叉。</p>
     */
    private double readDouble(String key, double def) {
        if (!yaml.contains(key)) {
            noteMissing(key, def);
            return def;
        }
        Object raw = yaml.get(key);
        Double v = coerceDouble(raw);
        if (v == null) {
            warnType(key, raw, def, "小数");
            return def;
        }
        return v;
    }

    /** 9A-11：字符串读取——缺键走默认值 + 汇总 INFO；非字符串类型 WARNING 后回退。 */
    private String readString(String key, String def) {
        if (!yaml.contains(key)) {
            noteMissing(key, def);
            return def;
        }
        Object raw = yaml.get(key);
        if (raw instanceof String) {
            return (String) raw;
        }
        warnType(key, raw, def, "字符串");
        return def;
    }

    /** 9A-11：布尔归一化——Boolean 原样；数字 0/1；字符串 true/false/yes/no/on/off/1/0（忽略大小写）。 */
    private static Boolean coerceBool(Object raw) {
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw instanceof Number) {
            double d = ((Number) raw).doubleValue();
            if (d == 0.0d) {
                return Boolean.FALSE;
            }
            if (d == 1.0d) {
                return Boolean.TRUE;
            }
            return null;
        }
        if (raw instanceof String) {
            switch (((String) raw).trim().toLowerCase(Locale.ROOT)) {
                case "true":
                case "yes":
                case "on":
                case "1":
                    return Boolean.TRUE;
                case "false":
                case "no":
                case "off":
                case "0":
                    return Boolean.FALSE;
                default:
                    return null;
            }
        }
        return null;
    }

    /**
     * 9A-11：整数归一化——整数族直接取值，整数值的浮点（如 45.0）取整；其它一律返回 null。
     *
     * <p><b>字符串不在这里转换</b>：{@code warning_threshold: "45"} 这类写法在 Bukkit
     * 原生 getInt 下本来就取不到值（静默回退默认），保留为"类型错误 + 回退 + WARNING"
     * 才能让用户知道自己写的阈值从未生效，这也是本批修复的核心诉求。</p>
     */
    private static Integer coerceInt(Object raw) {
        if (!(raw instanceof Number)) {
            return null;
        }
        double d = ((Number) raw).doubleValue();
        if (!Double.isFinite(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            return null;
        }
        if (d != Math.rint(d)) {
            return null;
        }
        return (int) d;
    }

    /** 9A-11：小数归一化——仅接受 Number；字符串返回 null（由调用方回退默认值并 WARNING）。 */
    private static Double coerceDouble(Object raw) {
        return (raw instanceof Number) ? ((Number) raw).doubleValue() : null;
    }

    /** 9A-11：缺键登记（load() 末尾汇总成一条 INFO）。 */
    private void noteMissing(String key, Object def) {
        missingKeysThisLoad.put(key, def);
    }

    /** 9A-11：本轮 load 是否为"旧版配置迁移"（缺 config-version）。用于把缺失汇总降级为 INFO 说明。 */
    private boolean migrating = false;

    /** 9A-11：缺键汇总日志——只记一次，超过 15 项折叠。 */
    private void flushMissingKeys() {
        if (missingKeysThisLoad.isEmpty()) {
            return;
        }
        String head = migrating
            ? "[PerfScope] 旧版配置迁移：以下 " + missingKeysThisLoad.size()
              + " 个键不存在，已按默认值运行（如需自定义请从插件默认配置复制后修改）: "
            : "[PerfScope] 配置缺少 " + missingKeysThisLoad.size()
              + " 个键（旧版配置属正常），本次按默认值运行: ";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Object> e : missingKeysThisLoad.entrySet()) {
            if (shown >= 15) {
                sb.append(" 等共 ").append(missingKeysThisLoad.size()).append(" 项");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(describe(e.getValue()));
            shown++;
        }
        plugin.getLogger().info(head + sb);
    }

    /** 9A-11：类型错误日志——带原值与实际采用值，同一键只提示一次。 */
    private void warnType(String key, Object raw, Object used, String expected) {
        if (!typeWarnedThisLoad.add(key)) {
            return;
        }
        String msg = "[PerfScope] 配置类型错误：" + key + " 的当前值 " + describe(raw)
            + " 是" + typeName(raw) + "，应为" + expected
            + "，本次已改用 " + describe(used) + "；修正后执行 /perfscope reload 生效";
        plugin.getLogger().warning(msg);
        typeWarningsThisLoad.add(key);
    }

    private static String describe(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return "\"" + v + "\"";
        }
        return String.valueOf(v);
    }

    private static String typeName(Object v) {
        if (v == null) {
            return "空值";
        }
        if (v instanceof Boolean) {
            return "布尔值";
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return "整数";
        }
        if (v instanceof Number) {
            return "小数值";
        }
        if (v instanceof String) {
            return "字符串";
        }
        if (v instanceof ConfigurationSection) {
            return "配置节";
        }
        if (v instanceof List) {
            return "列表";
        }
        return v.getClass().getSimpleName();
    }

    /**
     * 9A-11：读取 worlds 段并做结构校验。
     * {@code worlds: 123} 这类写法以前会让 getConfigurationSection 返回 null 并静默忽略整段，现在会 warning。
     */
    private ConfigurationSection readWorldsSection() {
        if (!yaml.contains("worlds")) {
            return null;
        }
        Object raw = yaml.get("worlds");
        if (raw instanceof ConfigurationSection) {
            return (ConfigurationSection) raw;
        }
        if (raw == null) {
            return null;
        }
        warnType("worlds", raw, "(忽略该段)", "配置节");
        return null;
    }

    // ==================================================================================
    // 9A-11：未知键检测 & 世界名校验
    // ==================================================================================

    /**
     * 9A-11：未知键检测——已知键集合取自 jar 内默认 {@code config.yml}（{@code plugin.getResource}），
     * 这样新增/删除键时不会与代码里的手写清单漂移。
     *
     * <p>豁免规则：</p>
     * <ul>
     *   <li>节（section）本身只要其下有已知键就不算未知（由叶子键说话）；</li>
     *   <li>{@code worlds.<任意世界名>} 及 {@code .entities.total_*_threshold} 是动态键，按模式放行；</li>
     *   <li>{@code config-version} 恒为已知。</li>
     * </ul>
     *
     * @return 未知键列表（有序）；获取不到内置默认配置时返回空列表并跳过检测
     */
    private List<String> detectUnknownKeys() {
        Set<String> known = loadKnownKeys();
        if (known == null) {
            return Collections.emptyList();
        }
        Set<String> unknown = new TreeSet<>();
        for (String key : yaml.getKeys(true)) {
            if (isKnownKey(key, known)) {
                continue;
            }
            Object value = yaml.get(key);
            if (value instanceof ConfigurationSection
                && !((ConfigurationSection) value).getKeys(false).isEmpty()) {
                // 非空节：只点名其下叶子键，避免 "redstone" + "redstone.enabled" 双份噪音
                continue;
            }
            unknown.add(key);
        }
        for (String key : unknown) {
            plugin.getLogger().warning("[PerfScope] 配置项 " + key
                + " 已不再使用/拼写可能有误（当前版本不读取该键，可安全删除）");
        }
        if (!unknown.isEmpty()) {
            plugin.getLogger().warning("[PerfScope] 共发现 " + unknown.size()
                + " 个未知配置项；若你确认拼写无误，请升级 PerfScope 或反馈该键名");
        }
        return new ArrayList<>(unknown);
    }

    /** 9A-11：读取 jar 内默认 config.yml 的键集合；失败返回 null（跳过检测，绝不误报）。 */
    private Set<String> loadKnownKeys() {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                return null;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));
            Set<String> known = new HashSet<>(defaults.getKeys(true));
            known.add(CONFIG_VERSION_KEY);
            return known;
        } catch (Exception e) {
            plugin.getLogger().warning("[PerfScope] 无法读取内置默认 config.yml，已跳过未知配置项检测: "
                + e.getMessage());
            return null;
        }
    }

    /** 9A-11：判断某个键是否已知（含父节与 worlds 动态键豁免）。 */
    private static boolean isKnownKey(String key, Set<String> known) {
        if (known.contains(key)) {
            return true;
        }
        String prefix = key + ".";
        for (String k : known) {
            if (k.startsWith(prefix)) {
                return true;
            }
        }
        if (key.equals("worlds")) {
            return true;
        }
        if (key.startsWith("worlds.")) {
            return isKnownWorldKey(key, known);
        }
        return false;
    }

    /** 9A-11：{@code worlds.<世界名>} 下允许出现的相对键（世界名任意，其余结构必须与读取代码一致）。 */
    private static final Set<String> WORLD_KNOWN_RELATIVE_KEYS = new HashSet<>(Arrays.asList(
        "entities",
        "entities.total_warning_threshold",
        "entities.total_critical_threshold"));

    /** 9A-11：{@code worlds.<世界名>.<相对键>} 的动态豁免——世界名任意，相对键必须在白名单内。 */
    private static boolean isKnownWorldKey(String key, Set<String> known) {
        String rest = key.substring("worlds.".length());
        int dot = rest.indexOf('.');
        if (dot < 0) {
            return true; // worlds.<任意世界名>
        }
        String sub = rest.substring(dot + 1);
        if (WORLD_KNOWN_RELATIVE_KEYS.contains(sub)) {
            return true;
        }
        // 兜底：默认配置里 {worlds.<样本世界>.<相对键>} 出现过的相对键也放行
        for (String k : known) {
            if (!k.startsWith("worlds.")) {
                continue;
            }
            String rel = k.substring("worlds.".length());
            int d2 = rel.indexOf('.');
            if (d2 < 0) {
                continue;
            }
            if (rel.substring(d2 + 1).equals(sub)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 9A-11：worlds 段世界名校验。
     *
     * <p>时机说明：插件 onEnable 时 {@link Bukkit#getWorlds()} 通常已就绪；但若在极早期加载
     * （或测试环境）世界列表为空，<b>直接跳过校验</b>，避免把全部世界误报成未知。</p>
     *
     * @return 未知世界名列表（有序）
     */
    private List<String> validateWorldNames(ConfigData cfg) {
        if (cfg.worldSettings == null || cfg.worldSettings.isEmpty()) {
            return Collections.emptyList();
        }
        List<World> loaded;
        try {
            loaded = Bukkit.getWorlds();
        } catch (Throwable t) {
            // 世界系统尚未就绪（极早期加载 / 测试桩），跳过校验
            return Collections.emptyList();
        }
        if (loaded == null || loaded.isEmpty()) {
            plugin.getLogger().info("[PerfScope] 世界尚未加载，本次跳过 worlds 段世界名校验");
            return Collections.emptyList();
        }
        Set<String> names = new TreeSet<>();
        for (World w : loaded) {
            if (w != null && w.getName() != null) {
                names.add(w.getName());
            }
        }
        if (names.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> bad = new ArrayList<>();
        for (String name : cfg.worldSettings.keySet()) {
            if (!names.contains(name)) {
                bad.add(name);
            }
        }
        if (bad.isEmpty()) {
            return bad;
        }
        plugin.getLogger().warning("[PerfScope] 配置 worlds 段包含未知世界: " + String.join(", ", bad)
            + "；当前已加载的世界: " + String.join(", ", names)
            + "。这些条目不会生效（多为改名后残留或拼写错误），请核对世界名；"
            + "若该世界只是尚未加载，加载后 /perfscope reload 即可");
        return bad;
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
     *   <li>alerts.cooldown-seconds——负值钳制为 0；</li>
     *   <li>9A-11：cpu.process/system_warning_threshold 要求 {@code 0 < v <= 100}（百分比语义），越界回退默认。</li>
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
            cfg.players_warningThreshold, cfg.players_criticalThreshold, 90, 98);
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
            cfg.memory_heapWarningThreshold, cfg.memory_heapCriticalThreshold, 85, 95);
        cfg.memory_heapWarningThreshold = memory[0];
        cfg.memory_heapCriticalThreshold = memory[1];

        // CPU：单值百分比阈值（无 critical 配对）——9A-11 增加 (0, 100] 范围校验
        cfg.cpu_processWarningThreshold =
            sanitizePercent("cpu.process_warning_threshold", cfg.cpu_processWarningThreshold, 80.0);
        cfg.cpu_systemWarningThreshold =
            sanitizePercent("cpu.system_warning_threshold", cfg.cpu_systemWarningThreshold, 90.0);

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

        // === alerts.debounce-cycles：非法值（≤0）钳到默认 3 并 warning（Wave 7C，与全仓 clamp 口径一致） ===
        if (cfg.alerts_debounceCycles <= 0) {
            plugin.getLogger().warning("[PerfScope] 配置校验：alerts.debounce-cycles="
                + cfg.alerts_debounceCycles + " 非法（需 ≥1），已按 3 处理");
            cfg.alerts_debounceCycles = 3;
        }

        // === alerts.daily-summary.time：非法格式回退 "08:00" 并 warning（Wave 7D；容忍 "8:00" 单数字小时） ===
        if (!isValidHhMm(cfg.alerts_dailySummaryTime)) {
            plugin.getLogger().warning("[PerfScope] 配置校验：alerts.daily-summary.time=\""
                + cfg.alerts_dailySummaryTime + "\" 非法（需 HH:mm），已回退默认 08:00");
            cfg.alerts_dailySummaryTime = "08:00";
        }

        // === alerts.webhook.type：未知值按 discord 处理并 warning（Wave 7D，不静默） ===
        if (!"discord".equals(cfg.alerts_webhookType) && !"onebot".equals(cfg.alerts_webhookType)) {
            plugin.getLogger().warning("[PerfScope] 配置校验：alerts.webhook.type=\""
                + cfg.alerts_webhookType + "\" 未知（支持 discord | onebot），已按 discord 处理");
            cfg.alerts_webhookType = "discord";
        }

        // === history.sample-interval-seconds：非法值（≤0）钳到 60 并 warning（Wave 7A，与全仓 clamp 口径一致） ===
        if (cfg.history_sampleIntervalSeconds <= 0) {
            plugin.getLogger().warning("[PerfScope] 配置校验：history.sample-interval-seconds="
                + cfg.history_sampleIntervalSeconds + " 非法（需 ≥1），已按 60 秒处理");
            cfg.history_sampleIntervalSeconds = 60;
        }

        // === history.retention-days：非法值（≤0）钳到 7 并 warning（Wave 8B，与全仓 clamp 口径一致） ===
        if (cfg.history_persistRetentionDays <= 0) {
            plugin.getLogger().warning("[PerfScope] 配置校验：history.retention-days="
                + cfg.history_persistRetentionDays + " 非法（需 ≥1），已按 7 天处理");
            cfg.history_persistRetentionDays = 7;
        }

        // === hotspots.sample-every-cycles：非法值（≤0）钳到 30 并 warning（Wave 8C，与全仓 clamp 口径一致） ===
        if (cfg.hotspots_sampleEveryCycles <= 0) {
            plugin.getLogger().warning("[PerfScope] 配置校验：hotspots.sample-every-cycles="
                + cfg.hotspots_sampleEveryCycles + " 非法（需 ≥1），已按 30 周期处理");
            cfg.hotspots_sampleEveryCycles = 30;
        }

        // === hotspots.chunk-warning-threshold：非法值（≤0）钳到 200 并 warning（9A-8） ===
        if (cfg.hotspots_chunkWarningThreshold <= 0) {
            plugin.getLogger().warning("[PerfScope] 配置校验：hotspots.chunk-warning-threshold="
                + cfg.hotspots_chunkWarningThreshold + " 非法（需 ≥1），已按 200 处理");
            cfg.hotspots_chunkWarningThreshold = 200;
        }
    }

    /**
     * 9A-11：百分比阈值校验——{@code 0 < v <= 100}，非有限值/越界均回退默认值并 warning。
     *
     * <p>语义提醒：{@code cpu.process_warning_threshold} 的分母是<b>整机 CPU</b>，
     * 16 核机器上单线程打满只有 6.25%，要冲到 80% 需 13 个核被 JVM 吃满。</p>
     */
    private double sanitizePercent(String key, double v, double def) {
        if (!Double.isFinite(v)) {
            plugin.getLogger().warning("[PerfScope] 配置校验：" + key
                + " 为非有限数值(NaN/Infinity)，已回退默认值：" + v + " -> " + def);
            return def;
        }
        if (v <= 0.0d || v > 100.0d) {
            plugin.getLogger().warning("[PerfScope] 配置校验：" + key + "=" + v
                + " 越界（要求 0 < 值 <= 100，百分比），已回退默认值 " + def);
            return def;
        }
        return v;
    }

    /**
     * T4：double 阈值对校验——非有限值、越界或倒置时记 warning 并回退默认值。
     *
     * <p><b>9A-6 修正</b>：增加下界检查。{@code tps.warning_threshold: -1} 或
     * {@code mspt.warning_threshold: -5} 在旧实现下合法通过，会让告警条件恒真或恒假。</p>
     *
     * @param highIsBad true=数值越高越差（正常 warning &lt;= critical）；false=数值越低越差（正常 warning &gt;= critical）
     * @return {warning, critical}（合法原值或回退后的默认值）
     */
    private double[] sanitizePair(String key, double w, double c, double defW, double defC, boolean highIsBad) {
        if (!Double.isFinite(w) || !Double.isFinite(c)) {
            warnThresholdNonFinite(key, w, c);
            return new double[]{defW, defC};
        }
        boolean outOfRange = w < 0 || c < 0 || w > MAX_DOUBLE_THRESHOLD || c > MAX_DOUBLE_THRESHOLD;
        boolean inverted = highIsBad ? w > c : w < c;
        if (!outOfRange && !inverted) {
            return new double[]{w, c};
        }
        warnThresholdFallback(key, outOfRange, w, c);
        return new double[]{defW, defC};
    }

    /** double 阈值上限哨兵（9A-6）：与 int 版同口径。 */
    private static final double MAX_DOUBLE_THRESHOLD = 10_000_000.0;

    /**
     * T4：int 阈值对校验（高值差）——warning &gt; critical 时记 warning 并回退默认值。
     *
     * <p><b>9A-6 修正</b>：原实现只检查 {@code w <= c}，不设下界。于是
     * {@code entities.total_warning_threshold: -5} 合法通过，随后任何非零实体数都触发 severe，
     * 且恢复条件永不可达（永久告警）；反过来超大值（如 999999）会让告警永久静默。
     * 现要求两者都在 {@code [0, 10_000_000]} 内。</p>
     *
     * @return {warning, critical}（合法原值或回退后的默认值）
     */
    private int[] sanitizeIntPair(String key, int w, int c, int defW, int defC) {
        boolean negative = w < 0 || c < 0;
        boolean tooLarge = w > MAX_INT_THRESHOLD || c > MAX_INT_THRESHOLD;
        if (!negative && !tooLarge && w <= c) {
            return new int[]{w, c};
        }
        warnThresholdFallback(key, negative || tooLarge, w, c);
        return new int[]{defW, defC};
    }

    /** 阈值上限哨兵（9A-6）：超过此值视为非法，避免出现"永远不触发"的配置。 */
    private static final int MAX_INT_THRESHOLD = 10_000_000;

    /**
     * 阈值回退告警（9A-6：按真实原因分类，不再统一说"倒置"）。
     *
     * @param outOfRange true = 越界（负数/超上限）；与 nonFinite 同时为 false 时才是真正的"倒置"
     */
    private void warnThresholdFallback(String key, boolean outOfRange, Object w, Object c) {
        if (outOfRange) {
            plugin.getLogger().warning("[PerfScope] 配置校验：" + key
                + " 数值越界（要求 0 ≤ 值 ≤ " + MAX_INT_THRESHOLD + "），已回退默认值：w=" + w + " c=" + c);
        } else {
            plugin.getLogger().warning("[PerfScope] 配置校验：" + key
                + " 的 warning(critical) 设置倒置，已回退默认值：w=" + w + " c=" + c);
        }
    }

    /** 非有限数值（NaN/Infinity）专用的回退告警，与越界/倒置分开措辞。 */
    private void warnThresholdNonFinite(String key, Object w, Object c) {
        plugin.getLogger().warning("[PerfScope] 配置校验：" + key
            + " 存在非有限数值(NaN/Infinity)，已回退默认值：w=" + w + " c=" + c);
    }

    public ConfigData get() {
        return config;
    }

    public WorldSettings getWorldSettings(String worldName) {
        ConfigData cfg = config;
        if (cfg == null || cfg.worldSettings == null) {
            // 9A-11：load() 之前被调用时不再 NPE
            return null;
        }
        return cfg.worldSettings.getOrDefault(worldName, null);
    }

    // ==================================================================================
    // 9A-11：加载结果
    // ==================================================================================

    /**
     * 9A-11：load() 的结果快照。
     *
     * <p>调用方（PerfScope#reload）应据此决定提示语与是否重建任务；
     * 硬失败（YAML 解析失败）仍以异常形式抛出，异常时本结果不会成功。</p>
     */
    public static final class LoadResult {
        /** 是否成功（false 仅在 failed(...) 工厂里出现）。 */
        public final boolean success;
        /** 失败原因 / 成功时的简述。 */
        public final String message;
        /** 本次读取到的 config-version（缺键 = 0）。 */
        public final int configVersion;
        /** 是否因缺 config-version 而把版本号写回了磁盘。 */
        public final boolean versionKeyWritten;
        /** 缺失的配置键（按默认值运行）。 */
        public final List<String> missingKeys;
        /** 未知配置键（已逐条 WARNING）。 */
        public final List<String> unknownKeys;
        /** worlds 段中不存在的世界名（已 WARNING）。 */
        public final List<String> invalidWorldNames;
        /** 类型写错的配置键（已 WARNING 并回退默认值）。 */
        public final List<String> typeWarnings;

        private LoadResult(boolean success, String message, int configVersion, boolean versionKeyWritten,
                           List<String> missingKeys, List<String> unknownKeys,
                           List<String> invalidWorldNames, List<String> typeWarnings) {
            this.success = success;
            this.message = message;
            this.configVersion = configVersion;
            this.versionKeyWritten = versionKeyWritten;
            this.missingKeys = Collections.unmodifiableList(missingKeys);
            this.unknownKeys = Collections.unmodifiableList(unknownKeys);
            this.invalidWorldNames = Collections.unmodifiableList(invalidWorldNames);
            this.typeWarnings = Collections.unmodifiableList(typeWarnings);
        }

        public static LoadResult success(int configVersion, boolean versionKeyWritten,
                                         List<String> missingKeys, List<String> unknownKeys,
                                         List<String> invalidWorldNames, List<String> typeWarnings) {
            return new LoadResult(true, "配置加载成功", configVersion, versionKeyWritten,
                missingKeys, unknownKeys, invalidWorldNames, typeWarnings);
        }

        public static LoadResult failed(String message) {
            return new LoadResult(false, message, 0, false,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        }

        /** 9A-11：一行摘要（供命令层 / 日志使用）。 */
        public String summary() {
            if (!success) {
                return "配置加载失败：" + message;
            }
            return "配置加载成功（config-version=" + configVersion
                + "，缺键 " + missingKeys.size()
                + "，类型错误 " + typeWarnings.size()
                + "，未知键 " + unknownKeys.size()
                + "，未知世界 " + invalidWorldNames.size() + "）";
        }
    }

    /**
     * 配置数据容器
     */
    public static class ConfigData {
        /** 9A-11：本次加载读到的 config-version（旧版配置为 0）。 */
        public int configVersion;

        // 常规
        public int general_updateInterval;
        public boolean general_verboseStartup;
        public boolean general_debug;
        /** Wave 6D：bStats 匿名统计开关（general.bstats-enabled，缺键默认 true） */
        public boolean general_bstatsEnabled;

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

        // 告警（P0-4 基础版；Wave 7C 收敛）
        public boolean alerts_enabled;
        public boolean alerts_console;
        public boolean alerts_in_game;
        public int alerts_cooldownSeconds;
        public boolean alerts_notifyRecovery;
        /** Wave 7C：防抖周期数（alerts.debounce-cycles，缺键默认 3；非法值 ≤0 钳到 3）。同一 key 连续 N 个检查周期出现才首次推送。 */
        public int alerts_debounceCycles;

        /**
         * 9A-14：恢复防抖周期数（alerts.recovery-debounce-cycles，缺键默认 3）。
         * 恢复也需要连续 N 个干净周期才宣布——原实现一次干净采样就发"已恢复"，
         * 而内存类指标在 GC 后天然会短暂低于阈值，会造成假恢复。
         */
        public int alerts_recoveryDebounceCycles;

        /**
         * 9A-14：摘要模式（alerts.digest-mode，缺键默认 false）。
         * true = 同一检查周期内的多条新增告警合并为一条消息，抑制告警风暴。
         */
        public boolean alerts_digestMode;

        /**
         * 9A-14：降级时是否通知（alerts.notify-mitigation，缺键默认 true）。
         * critical → warning 时推一条"已缓解"（问题仍在，未恢复）——原实现降级后永久静默。
         */
        public boolean alerts_notifyMitigation;

        /**
         * 9A-14：模块不可用多久后把其告警转入 unknown 状态（alerts.unknown-after-minutes，缺键默认 5）。
         * 转入后不再重推、恢复时静默解决（不发"已恢复正常"）。
         */
        public int alerts_unknownAfterMinutes;

        /**
         * 9A-14：每日摘要时区（alerts.daily-summary.timezone，缺键默认 "" = 系统时区，IANA 名称如 Asia/Shanghai）。
         * 显式配置避免 VPS 默认 UTC 导致摘要落在服主本地凌晨。
         */
        public String alerts_dailySummaryTimezone;

        // 每日摘要与 webhook（Wave 7D）
        /** 每日摘要开关（alerts.daily-summary.enabled，缺键默认 false）。 */
        public boolean alerts_dailySummaryEnabled;
        /** 每日摘要时刻（alerts.daily-summary.time，本地时区 HH:mm，缺键默认 "08:00"；非法格式回退默认并 warning）。 */
        public String alerts_dailySummaryTime;
        /** webhook 总开关（alerts.webhook.enabled，缺键默认 false）。 */
        public boolean alerts_webhookEnabled;
        /** webhook 类型（alerts.webhook.type，discord | onebot，缺键默认 discord；未知值按 discord 处理并 warning）。 */
        public String alerts_webhookType;
        /** webhook 地址（alerts.webhook.url，缺键默认空串；空/非 http(s) 前缀 → 不发送、一次 warning，绝不记录完整 URL）。 */
        public String alerts_webhookUrl;

        // 历史采样（Wave 7A：24h 环形缓冲）
        /** 历史采样总开关（history.enabled，缺键默认 true；false 时 record 直接返回、读取路径返回空）。 */
        public boolean history_enabled;
        /** 历史采样节流间隔秒数（history.sample-interval-seconds，缺键默认 60；非法值 ≤0 钳到 60）。 */
        public int history_sampleIntervalSeconds;
        /** Wave 8B：历史文件持久化开关（history.persist，缺键默认 true；false 时仅内存环、不落盘）。 */
        public boolean history_persistEnabled;
        /** Wave 8B：历史文件保留天数（history.retention-days，缺键默认 7；非法值 ≤0 钳到 7）。 */
        public int history_persistRetentionDays;

        // 热点区块（Wave 8C）
        /** 热点区块总开关（hotspots.enabled，缺键默认 true；false 时模块标记不可用、命令显示"暂无热点数据"）。 */
        public boolean hotspots_enabled;
        /** 热点采样节流（hotspots.sample-every-cycles，缺键默认 30 ≈ 30 秒；非法值 ≤0 钳到 30）。 */
        public int hotspots_sampleEveryCycles;
        /**
         * 单区块实体告警阈值（hotspots.chunk-warning-threshold，缺键默认 200）。
         *
         * <p>9A-8：这是<b>单个区块</b>的阈值，与 {@code entities.total-warning-threshold}
         * （全服实体总数）是两种量纲，不可互相推导。旧实现用全服阈值/2 卡单区块，
         * 使 DIAG-ENT-HOTSPOT 在默认配置下永不触发。</p>
         */
        public int hotspots_chunkWarningThreshold;

        // 各世界设置
        public Map<String, WorldSettings> worldSettings;
    }

    /**
     * Wave 7D：校验 HH:mm 形式（容忍单数字小时如 "8:00"；24 小时制）。
     * 供 alerts.daily-summary.time 的格式校验使用——非法值由调用方回退默认。
     */
    private static boolean isValidHhMm(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.matches("\\d{1,2}:\\d{2}") && parseHhMmMinutes(v) >= 0;
    }

    /**
     * Wave 7D：把 HH:mm（容忍单数字小时）解析为"当日分钟数"（0-1439）；非法返回 -1。
     * DailySummaryService 与配置校验共用此解析，保证两处口径一致。
     */
    public static int parseHhMmMinutes(String value) {
        if (value == null) {
            return -1;
        }
        String v = value.trim();
        int idx = v.indexOf(':');
        if (idx <= 0 || idx == v.length() - 1) {
            return -1;
        }
        try {
            int h = Integer.parseInt(v.substring(0, idx).trim());
            int m = Integer.parseInt(v.substring(idx + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
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
