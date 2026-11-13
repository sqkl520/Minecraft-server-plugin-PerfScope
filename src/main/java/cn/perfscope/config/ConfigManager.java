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
import cn.perfscope.util.Messages;
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

    /**
     * CPU 告警迟滞带的默认宽度（百分点）。
     *
     * <p>取值依据来自真机实验（docs/04-dev-reports/CPU-ALERT-SAWTOOTH-EXPERIMENT.md）：
     * 在 E5-2660 v2 上施加负载后测得的采样标准差约 12 个百分点，样本在 55%–100% 之间摆动。
     * 6 小时合成轨迹上，margin=0 产出 1333 条消息，margin=3 降到 33 条，margin=5 降到 1 条。
     * 选 5 是为了落在拐点右侧，同时不把退出线压得过低（90−5=85 仍可通过真实的负载回落）。</p>
     */
    public static final double CPU_HYSTERESIS_DEFAULT = 5.0;

    /** i18n：受支持的语言代码（归一化后的规范形式）。新增语言时同步扩展 {@link #normalizeLanguage}。 */
    public static final Set<String> SUPPORTED_LANGUAGES =
        Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("en", "zh")));

    /** i18n：缺键 / 非法值时的回退语言。选 en 而不是 zh——控制台日志与默认配置的注释虽为中文，
     *  但面向"用户可见文案"时英文是更安全的通用默认（非中文服主看到中文界面会完全无从下手）。 */
    public static final String LANGUAGE_DEFAULT = "en";

    /** 9D：界面语言配置键（general.language）。 */
    public static final String LANGUAGE_CONFIG_KEY = "general.language";

    /**
     * 告警总开关的配置键（v1.4）。
     *
     * <p>抽成常量的唯一目的是让启动横幅那句"用 {0}: true 开启"能拼出<b>真实的键名</b>：
     * 键名一旦改名，文案会自动跟着改，不会出现"日志教你改一个不存在的键"。
     * 读取处（{@link #load()}）同样引用本常量，保证两处不可能漂移。</p>
     */
    public static final String ALERTS_ENABLED_KEY = "alerts.enabled";

    private final PerfScope plugin;
    /** 配置快照——load() 末尾整体替换发布（单次发布、原子可见）；volatile 保证异步任务（如自动报告）读取的跨线程可见性（A9a Wave4 修正） */
    private volatile ConfigData config;

    /** 9A-11：最近一次 load() 的结果（供命令层 / 重载逻辑查询；加载失败时保持失败态）。 */
    private volatile LoadResult lastLoadResult;

    /**
     * f-s03-data-06：当前配置的发布时刻（{@link #config} 被替换那一刻的墙钟）。
     * 采集层产出的 {@code MetricsSnapshot.timestamp} 早于本值，说明该快照是在旧配置下采集的，
     * 其 {@code unavailable}/{@code disabledModules} 与当前阈值不属于同一代次。
     */
    private volatile long publishedAtMillis;

    private File configFile;

    /** 9A-11：本轮 load() 使用的 yaml 句柄（仅 load() 内部使用，勿在其它线程读取）。 */
    private FileConfiguration yaml;

    /** 9A-11：本轮缺键（key -> 采用的默认值），load() 末尾汇总成一条 INFO，避免 50 行刷屏。 */
    private final Map<String, Object> missingKeysThisLoad = new LinkedHashMap<>();

    /** 9A-11：本轮已告警过的键——同一键（含 worlds 动态键）只警告一次。 */
    private final Set<String> typeWarnedThisLoad = new HashSet<>();

    /** 9A-11：类型告警文本（供 LoadResult 汇总，命令层可展示）。 */
    private final List<String> typeWarningsThisLoad = new ArrayList<>();

    static {
        // 注册本层文案表。ConfigManager 在 PerfScope#onEnable 里**先于** Messages.load() 构造，
        // 因此在类初始化阶段注册可以保证：无论编排方是否记得登记 Messages.onFirstLoad，
        // 本模块的键都已经在 Messages 第一次 mergeModules() 之前进入 EXTRA 表。
        // 注意：注册本身不会立刻进入当前语言快照（那只发生在 Messages.load() 内），
        // 所以"首次启动中早于 Messages.load() 的那一轮配置告警"仍可能显示键名；
        // 第二轮（reload）起一切正常。要彻底消除该窗口，需把 Messages.load() 提到
        // configManager.load() 之前——那在 PerfScope 里，不属本文件职责。
        ConfigMessages.init();
    }

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
     * 调用方按场景处理（f-s06-err-01 / f-s03-err-05）：<b>启动期</b>（onEnable）
     * <b>应让异常穿透以 fail-fast</b>——此时 {@link #get()} 为 {@code null}，无旧配置可退；
     * <b>reload</b> 则捕获并按旧快照继续运行。其余情况一律正常返回，状态放在
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
            String msg = Messages.get("config.load.parse-failed");
            plugin.getLogger().severe("[PerfScope] " + msg);
            plugin.getLogger().severe("[PerfScope] " + Messages.get("config.load.parse-failed-hint"));
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
        // i18n：界面语言。此处只做"读原始值"，归一化/回退统一交给 validateThresholds——
        // 若在读取处直接归一，非法值的 warning 会被拆散到两处，且难以保证"日志里看到的就是用户写的值"。
        fresh.general_language = readString("general.language", LANGUAGE_DEFAULT);
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
        // 默认值必须与 jar 内 config.yml 逐字一致（缺键的旧配置靠这里兜底）：
        // 20,000 / 30,000 的依据见 config.yml entities 段的注释（60 人正常服 5,000-15,000 实体）。
        fresh.entities_enabled = readBool("entities.enabled", true);
        fresh.entities_totalWarningThreshold = readInt("entities.total_warning_threshold", 20000);
        fresh.entities_totalCriticalThreshold = readInt("entities.total_critical_threshold", 30000);

        // === 区块 ===
        // 同上：10,000 / 15,000 的依据见 config.yml chunks 段的注释
        // （60 人正常服 3,000-8,000 区块；口径是「每世界」，故按区间上界取）。
        fresh.chunks_enabled = readBool("chunks.enabled", true);
        fresh.chunks_loadedWarningThreshold = readInt("chunks.loaded_warning_threshold", 10000);
        fresh.chunks_loadedCriticalThreshold = readInt("chunks.loaded_critical_threshold", 15000);

        // === 内存 ===
        fresh.memory_enabled = readBool("memory.enabled", true);
        fresh.memory_heapWarningThreshold = readInt("memory.heap_warning_threshold", 85);
        fresh.memory_heapCriticalThreshold = readInt("memory.heap_critical_threshold", 95);

        // === CPU ===
        fresh.cpu_enabled = readBool("cpu.enabled", true);
        fresh.cpu_processWarningThreshold = readDouble("cpu.process_warning_threshold", 80.0);
        fresh.cpu_systemWarningThreshold = readDouble("cpu.system_warning_threshold", 90.0);
        fresh.cpu_hysteresisMargin = readDouble("cpu.hysteresis_margin", CPU_HYSTERESIS_DEFAULT);

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
        // v1.4：缺键默认 **false**（首次安装不推送告警）。原因：随包默认的实体/区块阈值即使按
        // 中服调高，也无法覆盖"小服"与"超大服"两端——默认开启等于让服主在还没看过自己服规模时
        // 先挨一轮误报。缺键 = 旧配置未写过本键，按"未显式启用"处理；显式写 true 的配置原样生效
        // （配置从用户文件读，不与 jar 内默认合并）。
        fresh.alerts_enabled = readBool(ALERTS_ENABLED_KEY, false);
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
        // 9C-4：抖动抑制两项。实际判定由 AlertService 经 RawConfig 读取——决策层参数集中在决策层，
        // 避免 ConfigData 再膨胀只在 AlertService 用到的字段。这里的读取**不登记已知键**
        // （未知键集合只取自 jar 内 config.yml），它只产生两个副作用：缺键汇总 + 类型校验告警。
        // f-s03-data-07：repush-delta 必须用 readDouble——AlertService 以 getDouble 消费，
        // 用 readInt 时用户写 8.5 会被判"类型错误、已改用 8"，而实际生效的仍是 8.5。
        readInt("alerts.flap-window-seconds", 180);
        readDouble("alerts.repush-delta", 8.0);
        // === 每日摘要与 webhook（Wave 7D） ===
        fresh.alerts_dailySummaryEnabled = readBool("alerts.daily-summary.enabled", false);
        fresh.alerts_dailySummaryTime = readString("alerts.daily-summary.time", "08:00");
        fresh.alerts_webhookEnabled = readBool("alerts.webhook.enabled", false);
        fresh.alerts_webhookType = readString("alerts.webhook.type", "discord");
        fresh.alerts_webhookUrl = readString("alerts.webhook.url", "");
        // v1.4：加签密钥（仅飞书/钉钉）。缺键 → 空串（老配置零改动可用）。
        // 刻意不 trim——见 ConfigData 字段注释与 WebhookNotifier.reload()。
        fresh.alerts_webhookSecret = readString("alerts.webhook.secret", "");

        // === 历史采样（Wave 7A：24h 环形缓冲；重启清零，文件持久化规划于 Wave 8B） ===
        fresh.history_enabled = readBool("history.enabled", true);
        fresh.history_sampleIntervalSeconds = readInt("history.sample-interval-seconds", 60);
        // Wave 8B：文件持久化（按天 CSV；重启不清零）
        fresh.history_persistEnabled = readBool("history.persist", true);
        fresh.history_persistRetentionDays = readInt("history.retention-days", 7);

        // === 热点区块（Wave 8C：全服实体密度 Top5 的坐标定位；全遍历按周期节流） ===
        fresh.hotspots_enabled = readBool("hotspots.enabled", true);
        fresh.hotspots_sampleEveryCycles = readInt("hotspots.sample-every-cycles", 30);
        // v1.4：分区增量扫描（把一次全遍历摊到 N 个采集周期；缺键默认 10 = 与旧行为等价）
        fresh.hotspots_scanShards = readInt("hotspots.scan-shards", HOTSPOTS_SCAN_SHARDS_DEFAULT);
        // 9A-8：单区块实体阈值（区别于全服 entities 阈值——旧实现拿全服阈值/2 卡单区块，
        // 默认要求单区块 1000+ 实体，导致 DIAG-ENT-HOTSPOT 永不触发）
        fresh.hotspots_chunkWarningThreshold = readInt("hotspots.chunk-warning-threshold", 200);

        // === Prometheus 指标端点（v1.4） ===
        // 默认全关 + 只听回环：开了就等于多一个监听端口，而玩家数/世界名/插件数量都在里面。
        fresh.prometheus_enabled = readBool("prometheus.enabled", false);
        fresh.prometheus_bind = readString("prometheus.bind", PROMETHEUS_BIND_DEFAULT);
        fresh.prometheus_port = readInt("prometheus.port", PROMETHEUS_PORT_DEFAULT);
        fresh.prometheus_path = readString("prometheus.path", PROMETHEUS_PATH_DEFAULT);
        fresh.prometheus_serverId = readString("prometheus.server-id", "");
        fresh.prometheus_authToken = readString("prometheus.auth-token", "");

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
        // f-s03-data-06：先写时刻再发布引用，读者若读到新引用则不会再看到旧的 publishedAtMillis
        this.publishedAtMillis = System.currentTimeMillis();
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
            plugin.getLogger().info("[PerfScope] " + Messages.get("config.load.summary",
                result.missingKeys.size(), result.typeWarnings.size(),
                result.unknownKeys.size(), result.invalidWorldNames.size()));
        }
        // 9D（验收）：升级用户的界面语言会被**静默**从中文翻成英文——
        // 他们的 config.yml 没有 general.language，于是走默认 en，整站变英文，
        // 而控制台唯一相关的那行只说"缺 1 个键（旧版配置属正常）"。
        // 这里补一条点名说清楚的语言变更通知，并给出改回中文的一行操作。
        // 判据：缺 general.language（= 旧配置）且当前生效值是默认 en。
        if (result.missingKeys.contains(LANGUAGE_CONFIG_KEY)
            && LANGUAGE_DEFAULT.equals(fresh.general_language)) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.load.language-default",
                LANGUAGE_CONFIG_KEY, "language: zh"));
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
        plugin.getLogger().info("[PerfScope] " + Messages.get("config.version.legacy-detected",
            CONFIG_VERSION_KEY));
        boolean written = writeBackConfigVersion();
        if (written) {
            plugin.getLogger().info("[PerfScope] " + Messages.get("config.version.written",
                CONFIG_VERSION_KEY, CURRENT_CONFIG_VERSION));
        } else {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.version.write-failed",
                CONFIG_VERSION_KEY));
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
            warnType(CONFIG_VERSION_KEY, raw, 0, "config.expected.integer");
            return 0;
        }
        if (v > CURRENT_CONFIG_VERSION) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.version.too-new",
                CONFIG_VERSION_KEY, v, CURRENT_CONFIG_VERSION));
        } else if (v < CURRENT_CONFIG_VERSION) {
            plugin.getLogger().info("[PerfScope] " + Messages.get("config.version.older",
                v, CURRENT_CONFIG_VERSION));
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
            // f-s03-data-03：Files.write(path, ...) 是 CREATE+TRUNCATE_EXISTING+WRITE，原地截断后重写；
            // 写入中途被杀（磁盘满 / JVM 崩溃 / 强杀进程）会留下半写的 config.yml，且备份无人接线回滚。
            // 改为「同目录临时文件 + ATOMIC_MOVE」：目标要么是旧全文，要么是新全文，不存在半写态。
            // 临时文件必须与目标同目录（跨文件系统 ATOMIC_MOVE 不保证原子），并在失败时清理。
            Path tmp = new File(configFile.getParentFile(), "config.yml.tmp-writeback").toPath();
            try {
                Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    // 极少数文件系统不支持原子移动：退回普通替换（仍优于原地截断，窗口更窄）
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                // 清理失败不能掩盖"已写入成功"的结论：move 成功后 tmp 本就不存在，此处只兜底异常路径
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // 残留的 tmp 不影响 config.yml 的正确性
                }
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.version.append-failed",
                CONFIG_VERSION_KEY, e.getMessage()));
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
            warnType(key, raw, def, "config.expected.boolean");
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
            warnType(key, raw, def, "config.expected.integer");
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
            warnType(key, raw, def, "config.expected.integer");
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
            warnType(key, raw, def, "config.expected.decimal");
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
        warnType(key, raw, def, "config.expected.string");
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
            ? "[PerfScope] " + Messages.get("config.missing.migrating", missingKeysThisLoad.size())
            : "[PerfScope] " + Messages.get("config.missing.summary", missingKeysThisLoad.size());
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Object> e : missingKeysThisLoad.entrySet()) {
            if (shown >= 15) {
                sb.append(Messages.get("config.missing.more", missingKeysThisLoad.size()));
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
        String msg = "[PerfScope] " + Messages.get("config.type.error",
            key, describe(raw), Messages.get(typeName(raw)), Messages.get(expected), describe(used));
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

    /** 实际类型 → 文案键（由调用方经 {@link Messages#get(String)} 解析成当前语言）。 */
    private static String typeName(Object v) {
        if (v == null) {
            return "config.type.null";
        }
        if (v instanceof Boolean) {
            return "config.type.boolean";
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return "config.type.integer";
        }
        if (v instanceof Number) {
            return "config.type.decimal";
        }
        if (v instanceof String) {
            return "config.type.string";
        }
        if (v instanceof ConfigurationSection) {
            return "config.type.section";
        }
        if (v instanceof List) {
            return "config.type.list";
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
        warnType("worlds", raw, Messages.get("config.value.section-ignored"), "config.expected.section");
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
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.unknown.key", key));
        }
        if (!unknown.isEmpty()) {
            plugin.getLogger().warning("[PerfScope] "
                + Messages.get("config.unknown.summary", unknown.size()));
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
            plugin.getLogger().warning("[PerfScope] "
                + Messages.get("config.unknown.known-keys-failed", e.getMessage()));
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
            // 世界系统尚未就绪（极早期加载 / 测试桩），跳过校验。
            // f-s03-err-02：与下方"世界列表为空"分支保持同一可见性——两者都是"本次没校验"，
            // 异常这条曾零日志静默吞掉，服主会以为世界名校验跑过且全绿。
            // 复用既有键（本文件无权新增 i18n 键），异常类型与消息作为后缀附在本地化句子之后。
            plugin.getLogger().info("[PerfScope] " + Messages.get("config.worlds.skipped", "worlds")
                + " [" + t.getClass().getSimpleName() + ": " + t.getMessage() + "]");
            return Collections.emptyList();
        }
        if (loaded == null || loaded.isEmpty()) {
            plugin.getLogger().info("[PerfScope] " + Messages.get("config.worlds.skipped", "worlds"));
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
        plugin.getLogger().warning("[PerfScope] " + Messages.get("config.worlds.unknown",
            "worlds", String.join(", ", bad), String.join(", ", names)));
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
     *   <li>v1.4：int 阈值对（players/entities/chunks/memory 与 worlds 覆盖）要求 {@code 1 <= v <= 上界}
     *       且 {@code w <= c}。下界由 0 抬到 1——{@code 0} 与负数同效（永久告警、恢复条件永不可达）；
     *       上界按指标域取（百分比对 100，计数对 10,000,000）。详见 {@link #sanitizeIntPair}。</li>
     * </ul>
     */
    private void validateThresholds(ConfigData cfg) {
        // === 采集间隔 ===
        // 必须最先校验：它决定后续所有节流周期的时间尺度，非法值会让"每 N 周期"全部失真。
        if (cfg.general_updateInterval < UPDATE_INTERVAL_MIN || cfg.general_updateInterval > UPDATE_INTERVAL_MAX) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.update-interval",
                "general.update_interval", cfg.general_updateInterval,
                UPDATE_INTERVAL_MIN, UPDATE_INTERVAL_MAX, UPDATE_INTERVAL_DEFAULT));
            cfg.general_updateInterval = UPDATE_INTERVAL_DEFAULT;
        }

        // === 全局阈值对 ===
        // TPS（低值差，warning 应 >= critical）
        double[] tps = sanitizePair("tps.warning_threshold/critical_threshold",
            cfg.tps_warningThreshold, cfg.tps_criticalThreshold, 18.0, 15.0, false);
        cfg.tps_warningThreshold = tps[0];
        cfg.tps_criticalThreshold = tps[1];
        // f-s03-logic-01：sanitizePair 的上界是数值域 1e7，而 TPS 的合法阈值上限应取指标域。
        // 上界不拦时 tps.warning_threshold: 25 合法通过，窗口均值（恒 ≤20.5）< 25 使 DIAG-TPS-PERSIST
        // 在覆盖率达标后永久命中。取 20.5 而非 20.0：启动瞬间服务端会报 >20 的读数，
        // 卡 20.0 会把「想按 20.1 卡住下跌」的正常配置误判并非静默回退。
        if (cfg.tps_warningThreshold > TPS_CEILING || cfg.tps_criticalThreshold > TPS_CEILING) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.threshold-out-of-range",
                "tps.warning_threshold/critical_threshold", TPS_CEILING,
                cfg.tps_warningThreshold, cfg.tps_criticalThreshold));
            cfg.tps_warningThreshold = 18.0;
            cfg.tps_criticalThreshold = 15.0;
        }
        // 验收 IMPACT_UNVERIFIED 的闭合（2026-11-04）：(20.0, 20.5] 是「近恒真区」——
        // 稳态 TPS 上界就是 20.0，阈值写 20.2 会让 avg < 20.2 在健康服务器上近乎恒真。
        // 不改配置值（改 20.0 会把用户合法的 20.1 配置判非法并回退成 18.0，反而更迟钝），
        // 也不改规则判据（任何 ≥20.0 的阈值都天然近恒真，钳到 20.0 无实质改善）。
        // 采用与 f-s03-data-04 相同的「warn-only」口径：把这个恒真区变成**可见**的提示，
        // 让服主自己决定是否要一个如此灵敏的阈值，而不是让规则静默地永久命中。
        if (cfg.tps_warningThreshold >= TPS_TAUTOLOGY_FLOOR || cfg.tps_criticalThreshold >= TPS_TAUTOLOGY_FLOOR) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.tps-near-ceiling",
                TPS_TAUTOLOGY_FLOOR, cfg.tps_warningThreshold, cfg.tps_criticalThreshold));
        }

        // MSPT（高值差，warning 应 <= critical）
        double[] mspt = sanitizePair("mspt.warning_threshold/critical_threshold",
            cfg.mspt_warningThreshold, cfg.mspt_criticalThreshold, 45.0, 50.0, true);
        cfg.mspt_warningThreshold = mspt[0];
        cfg.mspt_criticalThreshold = mspt[1];
        // v1.4：mspt 是高值差指标，判据为 `msptAvg > threshold`，而 msptAvg 在采集侧被过滤为恒正。
        // 于是 mspt_critical_threshold: 0 使「严重」档恒真（v1.4 之前 0/0 能通过校验）。
        // 这里只拦 ==0/负数，不设 1ms 之类的下界：亚毫秒阈值虽激进但语义自洽。
        // 反向对照 tps（低值差，判据 `tps < threshold`）：那里 0 让告警**永不触发**，
        // 是一种自洽的"关掉 TPS 告警"惯用法，故 tps 不设同一道闸——见类内 tps 分支的注释。
        if (cfg.mspt_warningThreshold <= 0.0d || cfg.mspt_criticalThreshold <= 0.0d) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.threshold-zero",
                "mspt.warning_threshold/critical_threshold",
                cfg.mspt_warningThreshold, cfg.mspt_criticalThreshold, 45.0, 50.0));
            cfg.mspt_warningThreshold = 45.0;
            cfg.mspt_criticalThreshold = 50.0;
        }

        // 玩家（高值差，warning 应 <= critical；百分比语义 → 上界 100）
        int[] players = sanitizeIntPair("players.warning_threshold/critical_threshold",
            cfg.players_warningThreshold, cfg.players_criticalThreshold, 90, 98,
            MAX_PERCENT_THRESHOLD);
        cfg.players_warningThreshold = players[0];
        cfg.players_criticalThreshold = players[1];

        // 实体（高值差，采集层为逐世界语义，warning 应 <= critical）
        // 回退值与 readInt 的缺省、以及 jar 内 config.yml 必须三者一致（见 load() 里的说明）
        int[] entities = sanitizeIntPair("entities.total_warning_threshold/critical_threshold",
            cfg.entities_totalWarningThreshold, cfg.entities_totalCriticalThreshold, 20000, 30000,
            MAX_INT_THRESHOLD);
        cfg.entities_totalWarningThreshold = entities[0];
        cfg.entities_totalCriticalThreshold = entities[1];

        // 区块（高值差，warning 应 <= critical）
        int[] chunks = sanitizeIntPair("chunks.loaded_warning_threshold/critical_threshold",
            cfg.chunks_loadedWarningThreshold, cfg.chunks_loadedCriticalThreshold, 10000, 15000,
            MAX_INT_THRESHOLD);
        cfg.chunks_loadedWarningThreshold = chunks[0];
        cfg.chunks_loadedCriticalThreshold = chunks[1];

        // 内存（高值差，warning 应 <= critical；百分比语义 → 上界 100）
        int[] memory = sanitizeIntPair("memory.heap_warning_threshold/critical_threshold",
            cfg.memory_heapWarningThreshold, cfg.memory_heapCriticalThreshold, 85, 95,
            MAX_PERCENT_THRESHOLD);
        cfg.memory_heapWarningThreshold = memory[0];
        cfg.memory_heapCriticalThreshold = memory[1];

        // CPU：单值百分比阈值（无 critical 配对）——9A-11 增加 (0, 100] 范围校验
        cfg.cpu_processWarningThreshold =
            sanitizePercent("cpu.process_warning_threshold", cfg.cpu_processWarningThreshold, 80.0);
        cfg.cpu_systemWarningThreshold =
            sanitizePercent("cpu.system_warning_threshold", cfg.cpu_systemWarningThreshold, 90.0);
        // 迟滞带：合法区间 [0, 50]。0 = 关闭迟滞（允许，退回旧行为）；
        // 上界 50 的理由——margin 超过 50 等于把退出线压到阈值一半以下，一次真实的持续过载会永远退不出去。
        if (!Double.isFinite(cfg.cpu_hysteresisMargin)
            || cfg.cpu_hysteresisMargin < 0 || cfg.cpu_hysteresisMargin > 50) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.hysteresis",
                "cpu.hysteresis_margin", cfg.cpu_hysteresisMargin, CPU_HYSTERESIS_DEFAULT));
            cfg.cpu_hysteresisMargin = CPU_HYSTERESIS_DEFAULT;
        }
        // f-s04-logic-01：上下界之外还需与阈值交叉校验——margin 不小于任一 CPU 阈值时，
        // 退出线 threshold-margin 非正，而判定值在采集侧恒正（MetricsCollector 的 > 0 过滤），
        // 闩锁一旦进入就永不解除。margin=0 是关闭迟滞的合法值（0 恒小于正阈值），不拦。
        // 回退值不能直接用默认 5——阈值为 3 时 5 仍不小于阈值，故取「默认值与两阈值一半的较小者」。
        double cpuMinThreshold = Math.min(cfg.cpu_processWarningThreshold, cfg.cpu_systemWarningThreshold);
        if (cfg.cpu_hysteresisMargin >= cpuMinThreshold) {
            double fallback = Math.min(CPU_HYSTERESIS_DEFAULT, cpuMinThreshold / 2.0d);
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.hysteresis-not-below",
                cfg.cpu_hysteresisMargin, cfg.cpu_processWarningThreshold,
                cfg.cpu_systemWarningThreshold, fallback));
            cfg.cpu_hysteresisMargin = fallback;
        }

        // === worlds 段覆盖值对（倒置时回退到已校验的全局值） ===
        if (cfg.worldSettings != null) {
            for (Map.Entry<String, WorldSettings> entry : cfg.worldSettings.entrySet()) {
                WorldSettings ws = entry.getValue();
                int[] pair = sanitizeIntPair(
                    "worlds." + entry.getKey() + ".entities.total_warning_threshold/critical_threshold",
                    ws.entities_totalWarningThreshold, ws.entities_totalCriticalThreshold,
                    cfg.entities_totalWarningThreshold, cfg.entities_totalCriticalThreshold,
                    MAX_INT_THRESHOLD);
                ws.entities_totalWarningThreshold = pair[0];
                ws.entities_totalCriticalThreshold = pair[1];
            }
        }

        // === alerts.cooldown-seconds：负值钳制为 0 ===
        if (cfg.alerts_cooldownSeconds < 0) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.cooldown-negative",
                "alerts.cooldown-seconds", cfg.alerts_cooldownSeconds));
            cfg.alerts_cooldownSeconds = 0;
        }

        // === alerts.debounce-cycles：非法值（≤0）钳到默认 3 并 warning（Wave 7C，与全仓 clamp 口径一致） ===
        if (cfg.alerts_debounceCycles <= 0) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.debounce-cycles",
                "alerts.debounce-cycles", cfg.alerts_debounceCycles, 3));
            cfg.alerts_debounceCycles = 3;
        }

        // === alerts.daily-summary.time：非法格式回退 "08:00" 并 warning（Wave 7D；容忍 "8:00" 单数字小时） ===
        if (!isValidHhMm(cfg.alerts_dailySummaryTime)) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.daily-summary-time",
                "alerts.daily-summary.time", cfg.alerts_dailySummaryTime, "08:00"));
            cfg.alerts_dailySummaryTime = "08:00";
        }

        // === alerts.webhook.type：未知值按 discord 处理并 warning（Wave 7D，不静默） ===
        // v1.4：白名单从 2 值扩到 5 值。新增的三家国内平台在业务失败时也返回 HTTP 200，
        // 所以"类型写错"不会再退化成静默黑洞——只要读了响应体就一定能发现（见 WebhookResponse）。
        String normalizedType = cfg.alerts_webhookType == null ? ""
            : cfg.alerts_webhookType.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SUPPORTED_WEBHOOK_TYPES.contains(normalizedType)) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.webhook-type",
                "alerts.webhook.type", cfg.alerts_webhookType,
                String.join(" | ", SUPPORTED_WEBHOOK_TYPES), "discord"));
            normalizedType = "discord";
        }
        cfg.alerts_webhookType = normalizedType;

        // === alerts.webhook.secret：只在飞书/钉钉上有意义，其余类型填了是配置噪音 ===
        // 两种情况都 warning，因为它们的后果不同：
        //  • 该签没签（secret 空）→ 只有平台开了签名校验时才会失败，服主会以为是别的问题；
        //  • 不该填却填了（discord/onebot/wecom）→ 完全无作用，但会让人误以为在签名。
        boolean typeUsesSecret = "feishu".equals(normalizedType) || "dingtalk".equals(normalizedType);
        if (typeUsesSecret && !(cfg.alerts_webhookSecret != null && !cfg.alerts_webhookSecret.isBlank())) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.webhook-secret-missing",
                "alerts.webhook.secret", normalizedType));
        } else if (!typeUsesSecret && (cfg.alerts_webhookSecret != null && !cfg.alerts_webhookSecret.isBlank())) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.webhook-secret-unused",
                "alerts.webhook.secret", normalizedType));
        }

        // === history.sample-interval-seconds：非法值（≤0）钳到 60 并 warning（Wave 7A，与全仓 clamp 口径一致） ===
        if (cfg.history_sampleIntervalSeconds <= 0) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.history-interval",
                "history.sample-interval-seconds", cfg.history_sampleIntervalSeconds, 60));
            cfg.history_sampleIntervalSeconds = 60;
        }
        // f-s03-data-04：本类（≥1 即合法）与 HistoryStore（>180 只 warning、改不了值）是同一键的两个
        // 执法者，合法域不一致——3600 秒能让 30 分钟窗内只剩 0-1 条样本，DIAG-MEM-LEAK / DIAG-TPS-PERSIST
        // 因 MIN_SAMPLES 永久 skip。不改值（超过 180 是用户可理解的低内存取舍，强制回退属 breaking），
        // 只在配置侧把后果说出来，让"校验通过"不再等于"静默失效"。
        if (cfg.history_sampleIntervalSeconds > HISTORY_INTERVAL_TREND_SAFE_SEC) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get(
                "config.check.history-interval-trend-unsafe",
                cfg.history_sampleIntervalSeconds, HISTORY_INTERVAL_TREND_SAFE_SEC));
        }

        // === history.retention-days：非法值（≤0）钳到 7 并 warning（Wave 8B，与全仓 clamp 口径一致） ===
        if (cfg.history_persistRetentionDays <= 0) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.history-retention",
                "history.retention-days", cfg.history_persistRetentionDays, 7));
            cfg.history_persistRetentionDays = 7;
        }

        // === hotspots.sample-every-cycles：非法值（≤0）钳到 30 并 warning（Wave 8C，与全仓 clamp 口径一致） ===
        if (cfg.hotspots_sampleEveryCycles <= 0) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.hotspots-cycles",
                "hotspots.sample-every-cycles", cfg.hotspots_sampleEveryCycles, 30));
            cfg.hotspots_sampleEveryCycles = 30;
        }

        // === hotspots.chunk-warning-threshold：非法值（≤0）钳到 200 并 warning（9A-8） ===
        if (cfg.hotspots_chunkWarningThreshold <= 0) {
            plugin.getLogger().warning("[PerfScope] "
                + Messages.get("config.check.hotspots-chunk-threshold",
                    "hotspots.chunk-warning-threshold", cfg.hotspots_chunkWarningThreshold, 200));
            cfg.hotspots_chunkWarningThreshold = 200;
        }

        // === hotspots.scan-shards：越界（<1 或 >100）钳制并 warning（v1.4 分区扫描） ===
        // 为什么 <1 也要钳而不是"当 1 处理"：0/负数是配置写错（漏填、写了负号），静默按 1 跑等于
        // 每次都做一次完整全遍历——正好是本次改动要消除的行为，必须让服主在控制台看到这条告警。
        if (cfg.hotspots_scanShards < 1 || cfg.hotspots_scanShards > HOTSPOTS_SCAN_SHARDS_MAX) {
            int raw = cfg.hotspots_scanShards;
            cfg.hotspots_scanShards = (raw < 1)
                ? HOTSPOTS_SCAN_SHARDS_DEFAULT
                : HOTSPOTS_SCAN_SHARDS_MAX;
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.hotspots-shards",
                "hotspots.scan-shards", raw, 1, HOTSPOTS_SCAN_SHARDS_MAX, cfg.hotspots_scanShards));
        }

        // === prometheus.port：越界钳制（v1.4 指标端点） ===
        // 端口非法时端点会绑定失败。这里先钳到合法范围，让失败原因只剩"端口被占用"
        // 这一类真实问题，而不是把配置写错也伪装成绑定失败。
        if (cfg.prometheus_port < PROMETHEUS_PORT_MIN || cfg.prometheus_port > PROMETHEUS_PORT_MAX) {
            int raw = cfg.prometheus_port;
            cfg.prometheus_port = PROMETHEUS_PORT_DEFAULT;
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.prometheus-port",
                "prometheus.port", raw, PROMETHEUS_PORT_MIN, PROMETHEUS_PORT_MAX, cfg.prometheus_port));
        }

        // === prometheus.path：必须以 / 开头（HttpServer.createContext 的要求） ===
        // 缺前导斜杠时 createContext 会抛 IllegalArgumentException，在 reload 时表现为
        // "端点起不来"而不是"路径写错了"。这里补上前缀，并在日志里说明改了什么。
        if (cfg.prometheus_path == null || cfg.prometheus_path.isBlank()) {
            cfg.prometheus_path = PROMETHEUS_PATH_DEFAULT;
        } else if (!cfg.prometheus_path.startsWith("/")) {
            String raw = cfg.prometheus_path;
            cfg.prometheus_path = "/" + raw.trim();
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.prometheus-path",
                "prometheus.path", raw, cfg.prometheus_path));
        }

        // === general.language：界面语言归一化（i18n） ===
        // 语言项没有"越界"概念——写错就是一个不存在的语言，因此只做"识别 / 不认识"二分支。
        // 能识别就把归一化后的规范值写回（en_US -> en、zh_TW -> zh），下游 Messages 不必再归一；
        // 认不出则回退默认并 warning，日志保留用户原始拼写，方便对照 config.yml 直接改。
        String language = normalizeLanguage(cfg.general_language);
        if (language == null) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.language",
                LANGUAGE_CONFIG_KEY, cfg.general_language,
                String.join(", ", SUPPORTED_LANGUAGES), LANGUAGE_DEFAULT));
            language = LANGUAGE_DEFAULT;
        }
        cfg.general_language = language;
    }

    /**
     * i18n：语言代码归一化——把各种写法收敛为受支持的规范代码，供配置校验与消息层共用。
     *
     * <p>为什么需要这一步：Bukkit 生态里语言标签的写法并不统一（{@code en_US}、{@code en-us}、
     * {@code zh_CN}、{@code zh_TW} 都有人写），而本插件只提供"英文 / 简体中文"两套完整文案。
     * 若不做归一，就会出现"配置写 {@code en_US} 却被当成未知语言"式的隐性歧义。</p>
     *
     * <p>规则：去首尾空白 → 转小写 → 取主语言子标签（{@code _} 或 {@code -} 之前的部分）→
     * 与 {@link #SUPPORTED_LANGUAGES} 比对。于是 {@code en} / {@code en_US} / {@code en-GB} 统一为
     * {@code en}，{@code zh} / {@code zh_CN} / {@code zh_tw} 统一为 {@code zh}。
     * 繁体中文与简体中文共用 {@code zh} 一套文案——当前没有独立繁体资源，
     * 强行区分只会得到"一半键缺失、回退英文"的混排界面。</p>
     *
     * @param raw 原始配置值，可为 {@code null}
     * @return 规范化的语言代码（{@code "en"} 或 {@code "zh"}）；无法识别时返回 {@code null}
     *         （<b>刻意不</b>在此处兜底成 {@link #LANGUAGE_DEFAULT}：调用方需要能区分
     *         "用户本来就写 en" 与 "用户写错了被回退"，否则校验告警永远无法触发）
     */
    public static String normalizeLanguage(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        // 只取主语言子标签：zh_CN / zh-TW / zh-Hans-CN 一律收敛为 zh
        String primary = s;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '-') {
                primary = s.substring(0, i);
                break;
            }
        }
        return SUPPORTED_LANGUAGES.contains(primary) ? primary : null;
    }

    /**
     * 9A-11：百分比阈值校验——{@code 0 < v <= 100}，非有限值/越界均回退默认值并 warning。
     *
     * <p>语义提醒：{@code cpu.process_warning_threshold} 的分母是<b>整机 CPU</b>，
     * 16 核机器上单线程打满只有 6.25%，要冲到 80% 需 13 个核被 JVM 吃满。</p>
     */
    private double sanitizePercent(String key, double v, double def) {
        if (!Double.isFinite(v)) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.percent-non-finite",
                key, v, def));
            return def;
        }
        if (v <= 0.0d || v > 100.0d) {
            plugin.getLogger().warning("[PerfScope] "
                + Messages.get("config.check.percent-out-of-range", key, v, def));
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
        warnThresholdFallback(key, MAX_DOUBLE_THRESHOLD, outOfRange, w, c);
        return new double[]{defW, defC};
    }

    /** double 阈值上限哨兵（9A-6）：与 int 版同口径。 */
    private static final double MAX_DOUBLE_THRESHOLD = 10_000_000.0;

    /**
     * f-s03-logic-01：TPS 阈值上界 = 20.5，与 {@code CoreRules} 过滤异常样本的 {@code 20.5} 同口径。
     *
     * <p>为什么不用理论满值 20.0：启动瞬间服务端会读到略高于 20 的 TPS，把阈值卡在 20.0 会让
     * 「想按 20.1 卡住一切下跌」的正常配置被判非法并静默回退到 18.0（告警变迟钝）。
     * 代价是 {@code (20.0, 20.5]} 属近恒真区，由 {@link #TPS_TAUTOLOGY_FLOOR} 的
     * warn-only 提示负责暴露，不改用户配置值（见 validateThresholds）喵。</p>
     */
    private static final double TPS_CEILING = 20.5;

    /**
     * f-s03-logic-01 残留：稳态 TPS 的上界。阈值 >= 此值时，
     * {@code avg1m < threshold} 在健康服务器上近乎恒真，DIAG-TPS-PERSIST 会永久命中。
     * 只警告不改值——压到 20.0 会误伤「按 20.1 卡下跌」的合法配置，属 breaking change。
     */
    private static final double TPS_TAUTOLOGY_FLOOR = 20.0;

    /**
     * 采集间隔的合法区间（tick）。
     *
     * <p>没有上限时，{@code general.update_interval: 1} 会让采集器每 tick 跑一次全量采集——
     * 20 次/秒的主线程全遍历，在大服上直接把 TPS 拖垮。{@code MetricsCollector} 只有下限
     * {@code Math.max(1, configuredInterval)}，那个下限恰好放行了最危险的值。</p>
     *
     * <p>下限取 5（4 次/秒）而不是 1：即使原始指标需要更高频率，也应由采集器内部
     * 分级降频解决，而不是让用户把整个采集周期压到 tick 级。</p>
     */
    private static final int UPDATE_INTERVAL_MIN = 5;
    private static final int UPDATE_INTERVAL_MAX = 1200;
    private static final int UPDATE_INTERVAL_DEFAULT = 20;

    /** v1.4：热点分区扫描分片数的默认值（与 {@code hotspots.sample-every-cycles: 30} 整除，约 1/10 单片成本）。 */
    private static final int HOTSPOTS_SCAN_SHARDS_DEFAULT = 10;

    /**
     * webhook 支持的通道类型（v1.4）。
     *
     * <p>新增的三家在企业微信/飞书/钉钉——它们的共同点是<b>业务失败也返回 HTTP 200</b>，
     * 错误码在响应体里。判定逻辑见 {@code WebhookResponse}。</p>
     */
    private static final java.util.List<String> SUPPORTED_WEBHOOK_TYPES =
        java.util.List.of("discord", "onebot", "wecom", "feishu", "dingtalk");

    /** Prometheus 端点的默认监听地址：只听回环，避免默认就把指标暴露到局域网。 */
    private static final String PROMETHEUS_BIND_DEFAULT = "127.0.0.1";
    /**
     * 默认端口：8940。同时避开常用服务（9090 Prometheus、9100 node_exporter）与 99xx 出口带。
     *
     * <p><b>8940 的理由不是"本项目约定"，而是它确实没人要</b>：该端口在 IANA 注册表与
     * Prometheus 官方 {@code Default-port-allocations} wiki 中<b>均未分配</b>（8941–8952 同样空闲），
     * 所以默认值撞上别人监听的先验概率最低。</p>
     *
     * <p><b>9940 必须让开</b>：它早已分配给 {@code minecraft-prometheus-exporter}
     * （见 Prometheus wiki 的分配表），{@code hank880907/papermc_prometheus_exporter}
     * 也占用它——同机两套 exporter 抢同一端口时只能活一个，而失败方是后启动的本插件。
     * 整个 99xx 段都很挤：9940 = minecraft-prometheus-exporter、9941 = huawei-lte-exporter、
     * 9942 = drbd、9943 = FileStat。</p>
     */
    private static final int PROMETHEUS_PORT_DEFAULT = 8940;
    private static final String PROMETHEUS_PATH_DEFAULT = "/metrics";
    private static final int PROMETHEUS_PORT_MIN = 1;
    private static final int PROMETHEUS_PORT_MAX = 65535;
    /** v1.4：分片数上限——再大每片只剩个位数区块，固定开销反超收益。 */
    private static final int HOTSPOTS_SCAN_SHARDS_MAX = 100;

    /** f-s03-data-04：趋势规则仍可用的采样间隔上界（秒）——与 HistoryStore 的 DIAGNOSIS_MAX_INTERVAL_SEC 同值。 */
    private static final int HISTORY_INTERVAL_TREND_SAFE_SEC = 180;

    /**
     * T4：int 阈值对校验（高值差）——非法值记 warning 并回退默认值。
     *
     * <p><b>9A-6 修正</b>：原实现只检查 {@code w <= c}，不设下界。于是
     * {@code entities.total_warning_threshold: -5} 合法通过，随后任何非零实体数都触发 severe，
     * 且恢复条件永不可达（永久告警）；反过来超大值（如 999999）会让告警永久静默。
     * 现要求两者都在 {@code [0, 10_000_000]} 内。</p>
     *
     * <p><b>v1.4 修正：下界由 0 抬到 1，并加上指标域上界</b>。9A-6 的下界 0 漏掉了比负数更隐蔽的一类
     * 配置：{@code 0/0}（以及任何 {@code w = 0}）能通过 {@code 0 <= 0} 的检查，而它们的后果与负数
     * <b>完全相同</b>——永久告警。以 {@code players.warning_threshold: 0} 为例：</p>
     * <ul>
     *   <li>{@code MetricsCollector.collectPlayers} 判据是 {@code pct >= warning}，而
     *       {@link MetricsSnapshot#playerPercent()} 的值域是 0-100，恒满足 → 本项告警每周期都命中；</li>
     *   <li>{@code CoreRules.PlayerCapacityRule} 的判据是 {@code currentPercent < warning} 才 clean，
     *       {@code 0 < 0} 为 false → 规则不再短路；其后的「窗口内有低谷就不算容量问题」逃逸条件是
     *       {@code minPercent < warning - 10}，即 {@code minPercent < -10}，对 0-100 的百分比<b>永假</b>
     *       → 该规则失去唯一出口，1 人在线 / 200 上限也报「接近上限，新玩家可能进不来」。</li>
     * </ul>
     * <p>实体/区块两个计数对同理：判据是严格大于，{@code 0} 意味着「只要有一个实体/区块就超」，
     * 而任何真实服务器都恒有实体与区块。</p>
     *
     * <p><b>{@code 0} 不是本插件的「关闭」语义</b>：全仓没有任何一处把阈值 0 解释为禁用，
     * {@code config.yml} 里标注 "0 = 关闭" 的只有 {@code cpu.hysteresis_margin}、
     * {@code alerts.cooldown-seconds}、{@code report.retention_days} 等<b>非阈值对</b>键；
     * 模块级开关是 {@code players/entities/chunks/memory.enabled}。故此处不实现「0 = 禁用」，
     * 而是拒绝 0 并提示用户改用 {@code enabled: false}——把 0 当禁用会在配置层静默压下整条诊断，
     * 且与用户写 0 时的直觉（"从 0 起报"=最灵敏）正好相反，属于"猜错意图且不吭声"。</p>
     *
     * @param maxValue 该指标的数值域上界：百分比类传 {@link #MAX_PERCENT_THRESHOLD}（读数域 0-100），
     *                 计数类传 {@link #MAX_INT_THRESHOLD}。超过域上界的阈值永远不触发，
     *                 是「0 永久触发」的镜像错误，故一并拦掉。
     * @return {warning, critical}（合法原值或回退后的默认值）
     */
    private int[] sanitizeIntPair(String key, int w, int c, int defW, int defC, int maxValue) {
        // 下界 1 而非 0：0 与负数同效（永久告警），见方法注释
        if (w < 1 || c < 1) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.threshold-zero",
                key, w, c, defW, defC));
            return new int[]{defW, defC};
        }
        // 上界越界：百分比对 100、计数对 1e7。用专用文案（要求 1 ≤ 值 ≤ 上界），
        // 而非通用的 threshold-out-of-range——那条写的下界是 0，会误导用户去写 0
        boolean tooLarge = w > maxValue || c > maxValue;
        if (!tooLarge && w <= c) {
            return new int[]{w, c};
        }
        if (tooLarge) {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.threshold-above-max",
                key, maxValue, w, c));
        } else {
            plugin.getLogger().warning("[PerfScope] " + Messages.get("config.check.threshold-inverted",
                key, w, c));
        }
        return new int[]{defW, defC};
    }

    /** 阈值上限哨兵（9A-6）：超过此值视为非法，避免出现"永远不触发"的配置。 */
    private static final int MAX_INT_THRESHOLD = 10_000_000;

    /**
     * 百分比型阈值对的数值域上界。
     *
     * <p>取 100 而非 {@link #MAX_INT_THRESHOLD}：这些指标的读数域本身就是 0-100
     * （{@code MetricsSnapshot.heapUsagePercent}、{@link MetricsSnapshot#playerPercent()}），
     * 阈值写 101 与写 {@code 0} 是同一类错误的两端——前者永不触发，后者永久触发，
     * 而「校验通过」会让服主以为配置生效了。</p>
     */
    private static final int MAX_PERCENT_THRESHOLD = 100;

    /**
     * 阈值回退告警（9A-6：按真实原因分类，不再统一说"倒置"）。
     *
     * @param bound      该类指标的合法上界，仅用于越界分支的文案：百分比对传 int 100，
     *                   计数对传 int {@link #MAX_INT_THRESHOLD}，mspt（double 对）传
     *                   {@link #MAX_DOUBLE_THRESHOLD}。以 {@code Object} 接收是为了让文案里的
     *                   数字保持调用点原本的渲染形态（int 显示 {@code 100}，而不是 {@code 100.0}）
     * @param outOfRange true = 越界；false = 倒置（负数与 0 已由 {@link #sanitizeIntPair} /
     *                   mspt 的专用分支先行分流，不会进本方法）
     */
    private void warnThresholdFallback(String key, Object bound, boolean outOfRange, Object w, Object c) {
        if (outOfRange) {
            plugin.getLogger().warning("[PerfScope] "
                + Messages.get("config.check.threshold-out-of-range", key, bound, w, c));
        } else {
            plugin.getLogger().warning("[PerfScope] "
                + Messages.get("config.check.threshold-inverted", key, w, c));
        }
    }

    /** 非有限数值（NaN/Infinity）专用的回退告警，与越界/倒置分开措辞。 */
    private void warnThresholdNonFinite(String key, Object w, Object c) {
        plugin.getLogger().warning("[PerfScope] "
            + Messages.get("config.check.threshold-non-finite", key, w, c));
    }

    /**
     * 当前生效的配置快照（不可变；{@link #load()} 成功后整体替换）。
     *
     * <p><b>可空契约（f-s02-contract-05）</b>：本方法<b>可能返回 {@code null}</b>——
     * 插件构造期到 {@code load()} 成功之间、以及 {@code load()} 抛异常且从未成功过时，
     * {@code config} 字段一直是 {@code null}。返回类型上标注 {@link Nullable}，
     * 并有单测锁住「null 时各消费方不 NPE」的路径喵。</p>
     *
     * <p>此前 javadoc 完全缺失，导致 7 个调用点判空方言分裂：6 处直接解引用、
     * 1 处显式判空。统一口径为「调用方按需判空，不得假定非空」喵。</p>
     */
    @org.jetbrains.annotations.Nullable
    public ConfigData get() {
        return config;
    }

    /**
     * f-s03-data-06：当前配置的发布时刻（epoch ms）；{@link #load()} 尚未成功时返回 0。
     *
     * <p>用途：判断一份 {@code MetricsSnapshot}（其 {@code timestamp} 为采集时刻）是否与当前
     * 配置属于同一代次。快照时刻早于本值即"旧配置下采集、新配置下求值"，此时
     * {@code unavailable}/{@code disabledModules} 与阈值不是同一代，结论不该照常输出。</p>
     */
    public long publishedAtMillis() {
        return publishedAtMillis;
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
        /** 本次生效的 config-version：写回成功时取当前版本（磁盘上的旧值已被补写），写回失败时为磁盘读到的值（缺键 = 0）。 */
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
            return new LoadResult(true, Messages.get("config.load.success"), configVersion, versionKeyWritten,
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
                return Messages.get("config.load.result.failed", message);
            }
            return Messages.get("config.load.result.success", configVersion,
                missingKeys.size(), typeWarnings.size(),
                unknownKeys.size(), invalidWorldNames.size());
        }
    }

    /**
     * 配置数据容器
     */
    public static class ConfigData {
        /** 9A-11：本次加载生效的 config-version；写回成功时等于当前版本，写回失败时才是磁盘读到的旧值（缺键 = 0）。 */
        public int configVersion;

        // 常规
        /**
         * 界面语言（i18n）：<b>已归一化</b>的规范代码，只会是 {@code "en"} 或 {@code "zh"}。
         *
         * <p>load() 里 {@code validateThresholds} 已把 {@code en_US}/{@code zh_TW} 等写法收敛，
         * 非法值也已回退默认，因此消息层可以直接拿它当值用，不必再调
         * {@link ConfigManager#normalizeLanguage(String)}。</p>
         */
        public String general_language;
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
        /**
         * 迟滞带宽度（百分点）：告警进入用阈值本身，退出用「阈值 − 本值」。
         * 0 = 关闭迟滞（退回旧行为，不推荐）。见 {@code MetricsCollector.collectCPU} 的闩锁注释。
         */
        public double cpu_hysteresisMargin;

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
        /**
         * 加签密钥（alerts.webhook.secret，v1.4，缺键默认空串）。
         *
         * <p>只有飞书（签名校验）与钉钉（加签）使用；discord / onebot / wecom 填了会被忽略并 warning。</p>
         *
         * <p><b>刻意不 trim</b>：空白是签名载荷的一部分。secret 两端多一个空格会让
         * HMAC 结果完全不同，而平台的报错（飞书 19021 / 钉钉 310000）只说是"签名不匹配"，
         * 不会提示是空白问题——这是最难排查的一类配置错误。</p>
         *
         * <p>属凭据，与 {@code prometheus.auth-token} 同级别：日志/异常/命令回执永不回显。</p>
         */
        public String alerts_webhookSecret;

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
         * 热点扫描分片数（hotspots.scan-shards，缺键默认 10；越界钳到 1–100）。
         *
         * <p><b>v1.4 分区增量扫描</b>：整轮扫描被切成 {@code N} 片，每
         * {@code ceil(sample-every-cycles / N)} 个采集周期推进一片，一轮覆盖的区块集合
         * 与旧版一次性全遍历完全相同，但单拍成本降到约 {@code 1/N}。
         * 实测 11,048 区块下从 40.5 ms 降到约 5 ms。</p>
         *
         * <p>{@code 1} = 关闭分片（退回旧的一次性全遍历，用于对照或排查）；
         * 因为一轮的总周期数恒 ≥ {@code sample-every-cycles}，改大这个值<b>只会更省</b>，
         * 代价是同一批区块的刷新更慢。</p>
         */
        public int hotspots_scanShards;
        /**
         * 单区块实体告警阈值（hotspots.chunk-warning-threshold，缺键默认 200）。
         *
         * <p>9A-8：这是<b>单个区块</b>的阈值，与 {@code entities.total-warning-threshold}
         * （全服实体总数）是两种量纲，不可互相推导。旧实现用全服阈值/2 卡单区块，
         * 使 DIAG-ENT-HOTSPOT 在默认配置下永不触发。</p>
         */
        public int hotspots_chunkWarningThreshold;

        // ==================== Prometheus 指标端点（v1.4） ====================

        /**
         * 是否启用 Prometheus 抓取端点（{@code prometheus.enabled}，默认 false）。
         *
         * <p>默认关：绝大多数服主没有 Prometheus，开了只会多一个监听端口，
         * 而端口一旦绑到非回环地址就等于把玩家数/世界名/插件数量暴露出去。</p>
         */
        public boolean prometheus_enabled;

        /**
         * 监听地址（{@code prometheus.bind}，默认 {@code 127.0.0.1}）。
         *
         * <p>默认只听回环。容器化或多机抓取时必须改 {@code 0.0.0.0}，
         * 此时启动会打一条 WARNING，并且 {@code /perfscope status} 会显示端点与鉴权状态。</p>
         *
         * <p><b>容器里的 127.0.0.1 是容器自己的回环</b>，不是宿主机也不是面板节点。
         * 在 Docker / Pterodactyl / MCSManager 中保持默认值时，容器外的 Prometheus
         * 必然连不上（connection refused / 超时），而进容器 {@code curl 127.0.0.1} 却是通的。
         * 要被抓取就改 {@code 0.0.0.0} 并抓容器/节点 IP；面板服还需先加一条端口分配
         * （Wings 按分配项里的具体 IP 绑宿主端口，所以宿主机 {@code localhost} 可能仍不通）。</p>
         */
        public String prometheus_bind;

        /**
         * 监听端口（{@code prometheus.port}，默认 8940）。
         *
         * <p>8940 在 IANA 与 Prometheus 的 {@code Default-port-allocations} wiki 中均未分配；
         * 旧的默认值 9940 已被 {@code minecraft-prometheus-exporter} 占用。</p>
         */
        public int prometheus_port;

        /** 抓取路径（{@code prometheus.path}，默认 {@code /metrics}）。 */
        public String prometheus_path;

        /**
         * 跨服聚合身份（{@code prometheus.server-id}，空 = 自动派生）。
         *
         * <p>多服聚合时两个服撞同一个 id 会让 Grafana 把两条序列画成一条。
         * 空值时按「首个世界名 → 监听端口」派生，刻意不用 hostname——
         * 容器里所有实例的 hostname 常常一样。</p>
         */
        public String prometheus_serverId;

        /**
         * Bearer 鉴权令牌（{@code prometheus.auth-token}，空 = 不鉴权）。
         *
         * <p>属凭据：日志纪律沿用 {@code WebhookNotifier}——只记端点地址，绝不回显 token。</p>
         */
        public String prometheus_authToken;

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
