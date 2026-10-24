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
package cn.perfscope.alert;

import cn.perfscope.PerfScope;
import cn.perfscope.compat.SchedulerAdapter;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 告警服务（Wave 7B/7C 结构化 Alert 消费 + 9A-14 决策层重写）。
 *
 * <p><b>9A-14 修复的决策层缺陷（本类重写范围）</b>：</p>
 * <ul>
 *   <li><b>降级不再是黑洞</b>：critical→warning 时刷新 {@code activeAlerts} 记录（level/值/display 全部跟随），
 *       可选推一条「已缓解」；此后恢复文案用最后一次<b>真实观测</b>的 display，不再出现
 *       "严重偏高 已恢复正常"这种从未发生的严重事故；</li>
 *   <li><b>恢复防抖</b>：需连续 {@code alerts.recovery-debounce-cycles} 个检查周期都不越界才宣布恢复
 *       （默认与 {@code alerts.debounce-cycles} 同值），一次抖动不再刷"已恢复"；</li>
 *   <li><b>复燃不被冷却吞掉</b>：恢复时清除该 key 的冷却条目，并记下恢复时刻；
 *       若在冷却窗口内再次越界 → 直接推一条「问题再次出现」（绕过防抖与冷却），
 *       但冷却表照常写入，避免复燃后连环刷屏；</li>
 *   <li><b>状态跨重启</b>：{@code plugins/PerfScope/alerts.state}（key|level|firstSeen|lastSeen|value|display|module），
 *       启动 rehydrate 后这些 key 标记 {@code restored=true}：首个周期若仍越界则静默接管（不防抖、不推送），
 *       若已恢复则走正常恢复流程——崩溃重启循环不再把老问题当新事故重报；</li>
 *   <li><b>告警历史</b>：{@code plugins/PerfScope/alerts.jsonl}（追加写，2MB 自动轮转一次），
 *       事件 open/upgrade/downgrade/resolve/refire/restore/suspend/resolved_by_disable；
 *       {@link #recent(int)} 供命令层 {@code /perfscope alerts [N]} 使用；</li>
 *   <li><b>模块失效转 unknown</b>：告警所属模块连续不可用超过 {@code alerts.unknown-after-minutes}
 *       （默认 5 分钟）→ 该 key 转入 suspended（unknown）状态，不再算"活跃"；模块恢复后
 *       若问题仍在则静默接管，若已消失则静默解决（只记历史事件 resolved_by_disable，不发"已恢复正常"）；</li>
 *   <li><b>告警风暴收敛</b>：{@code alerts.digest-mode=true} 时同一检查周期内的所有<b>新增</b>告警
 *       合并成一条消息（按模块分组，最多列 5 条 + "等 N 条"）；升级/重推仍即时发；</li>
 *   <li><b>可行动文案</b>：推送前统一加「服名 + key + 值/阈值 + 持续时长 + 一行下一步」，
 *       服名取 {@code server.properties} 的 motd 首行（去色码、截断），取不到退化为端口号；</li>
 *   <li><b>投递结果可统计</b>：每日成功/失败条数（webhook 为异步排队，结果不可观测，单独计数）。</li>
 * </ul>
 *
 * <p>采集层（{@code MetricsCollector}）仍只负责"阈值判定 + 生成 Alert"，本类只做决策与投递，不改其文案。</p>
 *
 * <p>线程模型：定时器经 {@link SchedulerAdapter#runGlobalTimer} 调度，在主线程（Folia 下为全局区域线程）
 * 周期执行；状态表用 ConcurrentHashMap；状态文件只在状态变化时写（非每周期）。</p>
 */
public class AlertService {

    /** 接收游戏内告警的权限节点（plugin.yml 已定义，不可改名） */
    private static final String PERMISSION_ALERT = "perfscope.alert";

    /** 9A-14：模块不可用转 unknown 的默认分钟数（配置 alerts.unknown-after-minutes 缺省值）。 */
    private static final int DEFAULT_UNKNOWN_AFTER_MINUTES = 5;

    /** 9A-14：告警历史文件单文件上限（超过即轮转为 alerts.jsonl.1），防止长期运行无限膨胀。 */
    private static final long MAX_EVENT_FILE_BYTES = 2L * 1024L * 1024L;

    /** 9A-14：内存中保留的最近事件条数（{@link #recent(int)} 的上限）。 */
    private static final int MAX_RECENT_EVENTS = 500;

    private final PerfScope plugin;

    /**
     * 9A-18 / 9A-20：告警状态写盘的单一守护线程。
     *
     * <p>JFR 实测抓到 {@code persistState()} 在主线程（tick 线程）做 open+write+move；
     * 单线程执行器保证写盘串行且不占 tick 预算。</p>
     */
    private final java.util.concurrent.ExecutorService stateWriter =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "perfscope-alert-state-writer");
            t.setDaemon(true);
            return t;
        });
    private final ConfigManager configManager;

    /** 冷却表：告警 key → 上次推送时间（epoch ms）。定时器单线程访问，ConcurrentHashMap 为防御性选择。 */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    /** 已确认（已推送或重启后静默接管）的告警：key → 生命周期记录。 */
    private final Map<String, ActiveAlert> activeAlerts = new ConcurrentHashMap<>();

    /** 最后一个已消费快照的时间戳（9A-6：同一快照只计入一次防抖，避免"同一观测看 N 次"）。 */
    private long lastConsumedSnapshotTs = -1L;

    /** 防抖计数表（Wave 7C）：待确认告警 key → 连续出现的检查周期数。 */
    private final Map<String, Integer> debounceCounts = new ConcurrentHashMap<>();

    /** 9A-14：恢复防抖计数表：活跃告警 key → 连续"干净"（本周期缺席）的检查周期数。 */
    private final Map<String, Integer> recoveryCounts = new ConcurrentHashMap<>();

    /** 9A-14：最近一次恢复信息：key → 复燃窗口判定（恢复时清冷却、这里记时刻与原始起始时间）。 */
    private final Map<String, ResolvedInfo> lastResolvedAt = new ConcurrentHashMap<>();

    /** 9A-14：模块长期不可用而被挂起（unknown 状态）的告警：key → 生命周期记录。 */
    private final Map<String, ActiveAlert> suspendedAlerts = new ConcurrentHashMap<>();

    /** 9A-14：每个 key 的"所属模块开始不可用"时刻（epoch ms），用于 unknown-after-minutes 判定。 */
    private final Map<String, Long> moduleDownSince = new ConcurrentHashMap<>();

    /** 周期检查任务句柄（null = 未运行） */
    private SchedulerAdapter.TaskHandle timerHandle;

    /** Wave 7D：webhook 出口（由 PerfScope 装配；null 或未就绪时零开销跳过） */
    private WebhookNotifier webhookNotifier;

    /** 9A-14：运行期选项（从 config.yml 的 alerts 段读取，start() 时刷新）。 */
    private volatile Options options = Options.defaults();

    /** 9A-14：状态文件是否已 rehydrate（每进程一次；reload 不清状态机，故也不重读）。 */
    private boolean stateLoaded = false;

    /** 9A-14：服务端标识（motd 首行 / 端口），首次使用时解析并缓存。 */
    private volatile String serverTag;

    /** Wave 7D：当日推送计数（供每日摘要"推送 W 条"；跨天自动归零，reload 不清） */
    private volatile int pushedToday = 0;
    /** 9A-14：当日推送成功条数（至少一个通道投递成功）。 */
    private volatile int pushOkToday = 0;
    /** 9A-14：当日推送失败条数（全部通道异常）。 */
    private volatile int pushFailToday = 0;
    /** 9A-14：当日 webhook 已排队条数（WebhookNotifier 为异步 fire-and-forget，结果不可观测）。 */
    private volatile int webhookQueuedToday = 0;
    private volatile long pushedTodayDayEpoch = currentDayEpoch();

    /** 9A-14：最近事件的内存镜像（旧→新），供命令层免 IO 读取；首次访问时从 jsonl 播种。 */
    private final Deque<AlertEvent> recentEvents = new ArrayDeque<>();
    private boolean recentSeeded = false;

    public AlertService(PerfScope plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }

    /** Wave 7D：装配 webhook 出口（可空；null = 不发送）。 */
    public void setWebhookNotifier(WebhookNotifier webhookNotifier) {
        this.webhookNotifier = webhookNotifier;
    }

    /** Wave 7D：当前活跃（已确认未恢复）告警数——每日摘要使用；9A-14 起不含 suspended（unknown）告警。 */
    public int getActiveCount() {
        return activeAlerts.size();
    }

    /** 9A-14：当前处于 unknown（模块长期不可用）状态的告警数。 */
    public int getSuspendedCount() {
        return suspendedAlerts.size();
    }

    /**
     * Wave 7D：当日推送的告警条数（含新告警、重复提醒、级别升级、复燃；不含恢复与缓解）。
     * 简单日计数器：访问时若已跨天则先归零（近似实现即可，摘要只需量级）。
     */
    public int getPushedTodayCount() {
        rollDay();
        return pushedToday;
    }

    /** 9A-14：当日推送成功条数（至少一个启用通道投递成功）。 */
    public int getPushOkTodayCount() {
        rollDay();
        return pushOkToday;
    }

    /** 9A-14：当日推送失败条数（所有启用通道均异常）。 */
    public int getPushFailTodayCount() {
        rollDay();
        return pushFailToday;
    }

    /** 9A-14：当日 webhook 已排队条数（异步发送，最终成败无法在 AlertService 侧观测）。 */
    public int getWebhookQueuedTodayCount() {
        rollDay();
        return webhookQueuedToday;
    }

    /** 9A-14：当日历日（按日报时区；未配置时为系统时区），与每日摘要的“今日”口径一致。 */
    private long currentDayEpoch() {
        return java.time.LocalDate.now(dayZone()).toEpochDay();
    }

    /** 9A-14：日计数器所用时区（读 alerts.daily-summary.timezone，空 = 系统时区）。 */
    private java.time.ZoneId dayZone() {
        String spec = options.dailyTimezone;
        if (spec == null || spec.isEmpty()) {
            return java.time.ZoneId.systemDefault();
        }
        try {
            return java.time.ZoneId.of(spec);
        } catch (Exception e) {
            return java.time.ZoneId.systemDefault();
        }
    }

    /** 9A-14：跨天时把所有当日计数器归零（统一入口，避免各自为政）。 */
    private void rollDay() {
        long today = currentDayEpoch();
        if (today != pushedTodayDayEpoch) {
            pushedTodayDayEpoch = today;
            pushedToday = 0;
            pushOkToday = 0;
            pushFailToday = 0;
            webhookQueuedToday = 0;
        }
    }

    /** Wave 7D：推送成功后自增当日计数。 */
    private void countPushed() {
        rollDay();
        pushedToday++;
    }

    /**
     * 启动告警服务：按 general.update_interval 在主线程周期检查最新快照。
     * 幂等（先停后启，不叠加任务）；alerts.enabled=false 时不启动。
     * reload 场景：重建定时器以应用新间隔，但不清空冷却/活跃/防抖状态（避免重载导致告警重推）。
     */
    public void start() {
        stop(); // 防呆：先停旧定时器，支持重复调用不叠加（与 MetricsCollector.start 同范式）
        loadOptions();   // 9A-14：刷新 alerts.recovery-debounce-cycles / digest-mode 等扩展键
        loadState();     // 9A-14：rehydrate（每进程仅一次）
        ConfigManager.ConfigData cfg = configManager.get();
        if (!cfg.alerts_enabled) {
            plugin.getLogger().info("[告警] 告警服务未启用（alerts.enabled=false）");
            return;
        }
        long interval = Math.max(1, cfg.general_updateInterval);
        // 首查延迟一个采集周期：等采集器先产出首份快照；runGlobalTimer 在主线程执行，玩家消息发送天然安全
        timerHandle = SchedulerAdapter.runGlobalTimer(plugin, this::checkCycle, interval, interval);
        Options o = options;
        plugin.getLogger().info("[告警] 告警服务已启动 (检查间隔: " + interval + " ticks, 冷却: "
            + cfg.alerts_cooldownSeconds + " 秒, 防抖: " + Math.max(1, cfg.alerts_debounceCycles)
            + " 周期, 恢复防抖: " + o.recoveryDebounceCycles + " 周期, digest: "
            + (o.digestMode ? "开" : "关") + ")");
    }

    /**
     * 停止告警服务：取消定时器（幂等）。不清空冷却/活跃/防抖状态机——reload 时保留。
     * 9A-14：停止时落盘一次告警状态（关服/重载都不丢生命周期）。
     */
    public void stop() {
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
        persistState();
    }

    /**
     * 9A-18：关服时关闭状态写线程（在 {@link #stop()} 之后调用，保证最后一份状态已落盘）。
     * 幂等；不等队列（已提交的任务会跑完，因为 shutdown 不中断正在执行的任务）。
     */
    public void shutdown() {
        stateWriter.shutdown();
        try {
            if (!stateWriter.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                stateWriter.shutdownNow();
            }
        } catch (InterruptedException e) {
            stateWriter.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 9A-14：扩展配置（ConfigManager 尚未提供字段时自读原文件） ====================

    /** 9A-14：运行期选项快照。 */
    private static final class Options {
        int recoveryDebounceCycles = 3;
        boolean digestMode = false;
        boolean notifyMitigation = true;
        int unknownAfterMinutes = DEFAULT_UNKNOWN_AFTER_MINUTES;
        /** 9A-14：日计数器所用时区（与每日摘要同键；空 = 系统时区）。 */
        String dailyTimezone = "";

        static Options defaults() {
            return new Options();
        }
    }

    private void loadOptions() {
        Options o = new Options();
        try {
            RawConfig raw = RawConfig.load(plugin);
            ConfigManager.ConfigData cfg = configManager.get();
            int debounce = cfg == null ? 3 : Math.max(1, cfg.alerts_debounceCycles);
            int rec = raw.getInt("alerts.recovery-debounce-cycles", debounce);
            if (rec < 1) {
                plugin.getLogger().warning("[告警] alerts.recovery-debounce-cycles=" + rec
                    + " 非法（需 ≥1），已按防抖周期数 " + debounce + " 处理");
                rec = debounce;
            }
            o.recoveryDebounceCycles = rec;
            o.digestMode = raw.getBool("alerts.digest-mode", false);
            o.notifyMitigation = raw.getBool("alerts.notify-mitigation", true);
            int unknown = raw.getInt("alerts.unknown-after-minutes", DEFAULT_UNKNOWN_AFTER_MINUTES);
            if (unknown < 1) {
                plugin.getLogger().warning("[告警] alerts.unknown-after-minutes=" + unknown
                    + " 非法（需 ≥1），已按 " + DEFAULT_UNKNOWN_AFTER_MINUTES + " 分钟处理");
                unknown = DEFAULT_UNKNOWN_AFTER_MINUTES;
            }
            o.unknownAfterMinutes = unknown;
            String tz = raw.getString("alerts.daily-summary.timezone", "");
            o.dailyTimezone = tz == null ? "" : tz.trim();
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[告警] 告警扩展配置读取失败（按默认值运行）: " + e.getMessage());
        }
        this.options = o;
    }

    /**
     * 9A-14：直接读取 config.yml 的只读快照。
     *
     * <p>为什么绕过 ConfigManager：新增键（recovery-debounce-cycles / digest-mode /
     * unknown-after-minutes / daily-summary.timezone）需要 ConfigManager 增加对应字段，
     * 本类不能改那个文件；在其落地前先从原文件读，缺键一律回退默认值，行为与字段存在时一致。
     * 读取只在 start()/reload 时发生，不在周期内做 IO。</p>
     */
    static final class RawConfig {
        private final YamlConfiguration yaml;

        private RawConfig(YamlConfiguration yaml) {
            this.yaml = yaml;
        }

        static RawConfig load(PerfScope plugin) {
            try {
                File f = new File(plugin.getDataFolder(), "config.yml");
                if (!f.isFile()) {
                    return new RawConfig(null);
                }
                return new RawConfig(YamlConfiguration.loadConfiguration(f));
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning("[告警] 扩展配置读取失败（全部使用默认值）: " + e.getMessage());
                return new RawConfig(null);
            }
        }

        boolean getBool(String path, boolean def) {
            if (yaml == null) {
                return def;
            }
            Object v = yaml.get(path);
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof String s) {
                return Boolean.parseBoolean(s.trim());
            }
            return def;
        }

        int getInt(String path, int def) {
            if (yaml == null) {
                return def;
            }
            Object v = yaml.get(path);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v instanceof String s) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }

        String getString(String path, String def) {
            if (yaml == null) {
                return def;
            }
            Object v = yaml.get(path);
            return v == null ? def : String.valueOf(v);
        }
    }

    // ==================== 周期入口 ====================

    /**
     * 定时器节拍：读取最新快照并处理。
     * 顶层 try-catch 兜底：Bukkit 重复任务抛异常会被调度器自动取消，必须在任务边界吞掉并记日志。
     */
    private void checkCycle() {
        try {
            if (plugin.getMetricsCollector() == null) {
                return;
            }
            MetricsSnapshot snap = plugin.getMetricsCollector().getLatest();
            onSnapshot(snap); // snap 为 null（启动初期）时由 onSnapshot 内部直接返回
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[告警] 本轮检查失败: " + e.getMessage());
        }
    }

    /**
     * 处理一份快照（公开入口，供定时器或未来采集周期末尾直接调用）。
     * 预期由主线程串行调用；冷却判定为非原子 check-then-put（单主线程调用下无竞态）。
     */
    public void onSnapshot(MetricsSnapshot snap) {
        ConfigManager.ConfigData cfg = configManager.get();
        if (!cfg.alerts_enabled || snap == null) {
            return;
        }
        // 9A-6：同一快照只消费一次（检查周期可能比采集周期更密）。
        if (snap.timestamp == lastConsumedSnapshotTs) {
            return;
        }
        lastConsumedSnapshotTs = snap.timestamp;

        // 1. 提取本周期告警（结构化真相源）：warning 先入、critical 后入，同 key 以更高级别覆盖
        Map<String, Alert> current = new LinkedHashMap<>();
        for (Alert alert : snap.alerts) {
            if (alert == null || alert.key() == null) {
                continue; // 防御：脏数据不进状态机
            }
            Alert prev = current.get(alert.key());
            if (prev == null || (alert.critical() && !prev.critical())) {
                current.put(alert.key(), alert);
            }
        }

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0, cfg.alerts_cooldownSeconds) * 1000L;
        int debounceCycles = Math.max(1, cfg.alerts_debounceCycles);
        Options opt = options;
        int recoveryCycles = Math.max(1, opt.recoveryDebounceCycles);
        long unknownAfterMs = Math.max(1, opt.unknownAfterMinutes) * 60_000L;

        // 9A-14：digest 模式下，本周期的"新增"告警先收集、周期末尾合并成一条消息
        List<ActiveAlert> digest = opt.digestMode ? new ArrayList<>() : null;
        boolean stateDirty = false;

        // 2. 活跃告警收敛推送：新增（防抖/冷却/复燃）、升级、降级、同级别重推
        for (Map.Entry<String, Alert> entry : current.entrySet()) {
            String key = entry.getKey();
            Alert alert = entry.getValue();
            ActiveAlert rec = activeAlerts.get(key);

            if (rec == null) {
                // 9A-14：该 key 处于 suspended（模块长期不可用时转入的 unknown 状态）——
                // 模块回来了且问题仍在：静默接管为 active（不防抖、不推送，不重复报老事故）
                ActiveAlert suspendedRec = suspendedAlerts.remove(key);
                if (suspendedRec != null) {
                    suspendedRec.alert = alert;
                    suspendedRec.lastSeen = now;
                    suspendedRec.value = valueOf(alert);
                    suspendedRec.module = moduleOf(alert);
                    suspendedRec.restored = false;
                    activeAlerts.put(key, suspendedRec);
                    moduleDownSince.remove(key);
                    recordEvent("restore", suspendedRec);
                    stateDirty = true;
                    plugin.getLogger().log(Level.FINE,
                        "[告警] 模块恢复，静默接管 unknown 告警: " + key + "（不重复推送）");
                    continue;
                }
                // —— 待确认 / 复燃 ——
                int count = debounceCounts.merge(key, 1, Integer::sum);
                ResolvedInfo resolved = lastResolvedAt.get(key);
                boolean refire = resolved != null && cooldownMs > 0 && now - resolved.atMs <= cooldownMs;
                if (!refire && count < debounceCycles) {
                    continue; // 未达 N 周期：静默累计，暂不推送（防秒级抖动误报）
                }
                if (!refire && isCooling(key, now, cooldownMs)) {
                    continue; // 防抖已达但仍在冷却期：等冷却到期再推
                }
                // 复燃沿用该问题最初的 firstSeen：持续时长跨"恢复→复燃"连续累计，而不是重新计时
                long firstSeen = refire ? resolved.firstSeen : now;
                ActiveAlert fresh = new ActiveAlert(key, alert, firstSeen, now, false);
                activeAlerts.put(key, fresh);
                debounceCounts.remove(key);   // 已确认：不再计数
                recoveryCounts.remove(key);
                moduleDownSince.remove(key);
                cooldowns.put(key, now);      // 先落冷却再推送：推送异常也不会在下一周期立刻重试刷屏
                if (refire) {
                    lastResolvedAt.remove(key);
                    recordEvent("refire", fresh);
                    pushRefire(fresh, now);
                } else {
                    recordEvent("open", fresh);
                    if (digest != null) {
                        digest.add(fresh);
                    } else {
                        pushAlert(fresh, now);
                    }
                }
                stateDirty = true;
                continue;
            }

            if (rec.restored) {
                // 9A-14：重启后首次遇见——不防抖、不推送，静默接管为 active（老问题不重报）
                rec.alert = alert;
                rec.lastSeen = now;
                rec.value = valueOf(alert);
                rec.module = moduleOf(alert);
                rec.restored = false;
                cooldowns.put(key, now);
                recordEvent("restore", rec);
                stateDirty = true;
                plugin.getLogger().log(Level.FINE,
                    "[告警] 重启后静默接管未恢复告警: " + key + "（不重复推送）");
                continue;
            }

            // 本周期仍越界：恢复计数归零（连续性由恢复流程保证）
            recoveryCounts.remove(key);
            moduleDownSince.remove(key);

            if (alert.critical() && !rec.alert.critical()) {
                // —— 级别升级（warning→critical）：一事件一通知 ——
                rec.alert = alert;
                rec.lastSeen = now;
                rec.value = valueOf(alert);
                cooldowns.put(key, now); // 升级不受冷却限制（严重度上升应立即到达），推送后刷新冷却表
                recordEvent("upgrade", rec);
                pushUpgrade(rec, now);
                stateDirty = true;
            } else if (!alert.critical() && rec.alert.critical()) {
                // —— 9A-14 降级（critical→warning）：刷新记录 + 可选「已缓解」 ——
                rec.alert = alert;
                rec.lastSeen = now;
                rec.value = valueOf(alert);
                recordEvent("downgrade", rec);
                // 降级不是恢复：不写冷却表、不清恢复计数（本周期仍越界，恢复计数已在上面归零）
                if (opt.notifyMitigation && cfg.alerts_notifyRecovery) {
                    pushMitigated(rec, now);
                }
                stateDirty = true;
            } else {
                // —— 同级别：刷新记录（数值随时间更新），冷却到期仍活跃则重推 ——
                boolean changed = !sameAlert(rec.alert, alert);
                rec.alert = alert;
                rec.lastSeen = now;
                rec.value = valueOf(alert);
                if (changed) {
                    stateDirty = true;
                }
                if (!isCooling(key, now, cooldownMs)) {
                    cooldowns.put(key, now);
                    pushAlert(rec, now);
                }
            }
        }

        // 2b. 9A-14：digest 合并推送（一周期一条，按模块分组，最多 5 条 + "等 N 条"）
        if (digest != null && !digest.isEmpty()) {
            pushDigest(digest, now);
        }

        // 3. 恢复检测（9A-14：防抖 + 模块失效转 unknown + 恢复时清冷却）
        //    抑制条件按模块归因：模块在 snap.unavailable 中 → 不算"干净周期"，
        //    连续不可用超过 unknown-after-minutes 则把该 key 转入 suspended（unknown）。
        List<String> resolvedKeys = new ArrayList<>();
        List<String> suspendedKeys = new ArrayList<>();
        for (Map.Entry<String, ActiveAlert> entry : activeAlerts.entrySet()) {
            String key = entry.getKey();
            ActiveAlert rec = entry.getValue();
            if (current.containsKey(key)) {
                continue; // 本周期仍活跃：已在步骤 2 处理
            }
            String module = rec.module;
            if (module != null && snap.unavailable.contains(module)) {
                // 模块不可用：不算恢复（"消息消失"不等于"故障恢复"），先统计不可用时长
                long since = moduleDownSince.computeIfAbsent(key, k -> now);
                if (now - since >= unknownAfterMs) {
                    suspendedAlerts.put(key, rec);
                    suspendedKeys.add(key);
                    recordEvent("suspend", rec);
                    stateDirty = true;
                    plugin.getLogger().info("[告警] 模块 " + module + " 不可用超过 "
                        + opt.unknownAfterMinutes + " 分钟，告警 " + key + " 转入 unknown 状态");
                }
                continue;
            }
            if (module == null) {
                plugin.getLogger().log(Level.FINE,
                    "[告警] 恢复判定：key=" + key + " 未携带模块归因（按正常恢复处理）");
            }
            moduleDownSince.remove(key);
            int clean = recoveryCounts.merge(key, 1, Integer::sum);
            if (clean < recoveryCycles) {
                continue; // 9A-14：恢复防抖——连续 N 个干净周期才宣布恢复
            }
            recordEvent("resolve", rec);
            if (cfg.alerts_notifyRecovery) {
                pushRecovery(rec, now, clean);
            }
            resolvedKeys.add(key);
            stateDirty = true;
        }
        // 真恢复：移出 active，记复燃窗口，清冷却（复燃不被旧冷却压掉）
        for (String key : resolvedKeys) {
            ActiveAlert rec = activeAlerts.remove(key);
            recoveryCounts.remove(key);
            moduleDownSince.remove(key);
            cooldowns.remove(key);
            lastResolvedAt.put(key, new ResolvedInfo(now, rec == null ? now : rec.firstSeen));
        }
        // 9A-14：模块失效转 unknown——移出 active（不再算“活跃告警”），但**不算恢复**：
        // 不记复燃窗口、不清冷却；模块恢复后由步骤 4 决定“静默接管”还是“静默解决”
        for (String key : suspendedKeys) {
            activeAlerts.remove(key);
            recoveryCounts.remove(key);
        }

        // 4. suspended（unknown）状态：模块恢复后——问题仍在则交由步骤 2 静默接管（上方 suspendedRec 分支），
        //    问题已消失则静默解决（只记历史 resolved_by_disable，不发“已恢复正常”）
        for (Map.Entry<String, ActiveAlert> entry : suspendedAlerts.entrySet()) {
            String key = entry.getKey();
            ActiveAlert rec = entry.getValue();
            String module = rec.module;
            if (module != null && snap.unavailable.contains(module)) {
                continue; // 模块仍不可用
            }
            if (current.containsKey(key)) {
                continue; // 模块恢复且问题仍在：由步骤 2 的 suspendedRec 分支静默接管（不影响本次遍历）
            }
            suspendedAlerts.remove(key);
            moduleDownSince.remove(key);
            cooldowns.remove(key);
            recordEvent("resolved_by_disable", rec);
            stateDirty = true;
        }

        // 5. 计数清理：本周期缺席的待确认 key 视为"连续性中断"；已确认但未进入恢复流程的
        //    （模块不可用抑制期）保留恢复计数不动
        debounceCounts.keySet().removeIf(key -> !current.containsKey(key));

        // 6. 复燃窗口过期清理（避免 lastResolvedAt 无限增长）
        if (!lastResolvedAt.isEmpty()) {
            lastResolvedAt.entrySet().removeIf(e -> now - e.getValue().atMs > Math.max(cooldownMs, 60_000L));
        }

        // 7. 9A-14：状态变化才写盘（不是每周期）
        if (stateDirty) {
            persistState();
        }
    }

    /** 两条 Alert 是否"同一个观测"（数值与级别都没变）——用于减少无谓的状态落盘。 */
    private static boolean sameAlert(Alert a, Alert b) {
        return a == b || (a != null && b != null
            && a.level().equals(b.level())
            && a.message().equals(b.message()));
    }

    // ==================== 推送通道 ====================

    /**
     * 推送一条告警：控制台 + 游戏内 + webhook。
     * 9A-14：统一加「服名 + key + 值/阈值 + 持续时长 + 下一步」前缀（不改采集层文案）。
     */
    private void pushAlert(ActiveAlert rec, long now) {
        String levelCn = levelCn(rec.alert);
        String consoleText = "[告警][" + levelCn + "][" + serverTag() + "] " + rec.alert.message() + fullDetail(rec, now);
        String gameText = "[告警·" + levelCn + "] [" + serverTag() + "] " + rec.alert.message() + shortDetail(rec, now);
        emit(consoleText, gameText, rec.alert.critical() ? NamedTextColor.RED : NamedTextColor.YELLOW,
            rec.alert.critical() ? Level.SEVERE : Level.WARNING, true);
    }

    /** 推送级别升级通知：{@code [告警][严重] <message>（级别升级）}。 */
    private void pushUpgrade(ActiveAlert rec, long now) {
        String consoleText = "[告警][严重][" + serverTag() + "] " + rec.alert.message() + "（级别升级）" + fullDetail(rec, now);
        String gameText = "[告警·严重] [" + serverTag() + "] " + rec.alert.message() + "（级别升级）" + shortDetail(rec, now);
        emit(consoleText, gameText, NamedTextColor.RED, Level.SEVERE, true);
    }

    /**
     * 9A-14：推送「已缓解」——critical→warning，问题仍在但严重度下降。
     * 与「已恢复」严格区分：文案明确写"问题仍在，未恢复"。
     */
    private void pushMitigated(ActiveAlert rec, long now) {
        String consoleText = "[告警][缓解·" + levelCn(rec.alert) + "][" + serverTag() + "] " + rec.alert.message()
            + "（已由「严重」降为「警告」，问题仍在，未恢复）" + fullDetail(rec, now);
        String gameText = "[告警·缓解] [" + serverTag() + "] " + rec.alert.message()
            + "（已降为警告，问题仍在）" + shortDetail(rec, now);
        emit(consoleText, gameText, NamedTextColor.YELLOW, Level.WARNING, false);
    }

    /** 9A-14：复燃通知——恢复窗口内同一 key 再次越界，绕过防抖与冷却。 */
    private void pushRefire(ActiveAlert rec, long now) {
        String levelCn = levelCn(rec.alert);
        String suffix = "（问题再次出现）";
        String consoleText = "[告警][" + levelCn + "][" + serverTag() + "] " + rec.alert.message() + suffix + fullDetail(rec, now);
        String gameText = "[告警·" + levelCn + "] [" + serverTag() + "] " + rec.alert.message() + suffix + shortDetail(rec, now);
        emit(consoleText, gameText, rec.alert.critical() ? NamedTextColor.RED : NamedTextColor.YELLOW,
            rec.alert.critical() ? Level.SEVERE : Level.WARNING, true);
    }

    /** 推送恢复通知：控制台 info + 游戏内绿色；文案 = 最后一次真实观测的 display + " 已恢复正常"。 */
    private void pushRecovery(ActiveAlert rec, long now, int cleanCycles) {
        String message = rec.alert.display() + " 已恢复正常";
        String detail = " | key=" + rec.key
            + " | 持续 " + formatDuration(now - rec.firstSeen) + " 后连续 " + cleanCycles + " 个检查周期未越界";
        emit("[告警][恢复][" + serverTag() + "] " + message + detail,
            "[告警·恢复] " + message + " | 持续 " + formatDuration(now - rec.firstSeen),
            NamedTextColor.GREEN, Level.INFO, false);
    }

    /**
     * 9A-14：digest 合并推送——同一检查周期内的新增告警合并成一条，按模块分组，
     * 最多列 5 条，其余折叠为"…等 N 条"。
     */
    private void pushDigest(List<ActiveAlert> batch, long now) {
        boolean anyCritical = false;
        for (ActiveAlert r : batch) {
            if (r.alert.critical()) {
                anyCritical = true;
                break;
            }
        }
        // 按模块分组（保持出现顺序）
        Map<String, List<ActiveAlert>> byModule = new LinkedHashMap<>();
        for (ActiveAlert r : batch) {
            byModule.computeIfAbsent(metricOf(r.key), k -> new ArrayList<>()).add(r);
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("[告警][").append(anyCritical ? "严重" : "警告").append("] [").append(serverTag())
          .append("] 本周期新增 ").append(batch.size()).append(" 条告警");
        int shown = 0;
        int total = batch.size();
        outer:
        for (Map.Entry<String, List<ActiveAlert>> e : byModule.entrySet()) {
            for (ActiveAlert r : e.getValue()) {
                if (shown >= 5) {
                    break outer;
                }
                sb.append('\n').append("  · ").append(e.getKey()).append(": ")
                  .append(r.alert.message())
                  .append(r.value == null || r.value.isEmpty() ? "" : "（" + r.value + "）");
                shown++;
            }
        }
        if (total > shown) {
            sb.append('\n').append("  …等 ").append(total).append(" 条，执行 /perfscope alerts 查看");
        }
        String text = sb.toString();
        // 控制台多行输出（每行一个 Logger 调用，便于日志系统逐行采集）
        emitMultiLine(text, anyCritical ? NamedTextColor.RED : NamedTextColor.YELLOW,
            anyCritical ? Level.SEVERE : Level.WARNING, true, batch.size());
    }

    /**
     * 投递一条单行消息：控制台 / 游戏内 / webhook 三通道，各通道异常互不影响。
     *
     * @param counted 是否计入当日告警推送统计（恢复/缓解不计）
     */
    private void emit(String consoleText, String gameText, NamedTextColor color, Level logLevel, boolean counted) {
        ConfigManager.ConfigData cfg = configManager.get();
        boolean consoleOk = false;
        boolean gameOk = false;
        boolean webhookQueued = false;
        if (cfg.alerts_console) {
            try {
                plugin.getLogger().log(logLevel, consoleText);
                consoleOk = true;
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning("[告警] 控制台推送失败: " + e.getMessage());
            }
        }
        if (cfg.alerts_in_game) {
            try {
                broadcastToAlertPlayers(Component.text(gameText, color));
                gameOk = true;
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning("[告警] 游戏内推送失败: " + e.getMessage());
            }
        }
        WebhookNotifier wn = webhookNotifier;
        if (wn != null) {
            try {
                wn.send(consoleText);
                webhookQueued = wn.isReady();
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning("[告警] webhook 入队失败: " + e.getMessage());
            }
        }
        if (counted) {
            countPushed();
            if (consoleOk || gameOk || webhookQueued) {
                pushOkToday++;
            } else {
                pushFailToday++;
            }
            if (webhookQueued) {
                webhookQueuedToday++;
            }
        }
    }

    /** 9A-14：digest 多行消息投递（控制台逐行输出；游戏内与 webhook 一条整块）。 */
    private void emitMultiLine(String text, NamedTextColor color, Level logLevel, boolean counted, int alertCount) {
        ConfigManager.ConfigData cfg = configManager.get();
        boolean consoleOk = false;
        boolean gameOk = false;
        boolean webhookQueued = false;
        if (cfg.alerts_console) {
            try {
                for (String line : text.split("\n")) {
                    plugin.getLogger().log(logLevel, line);
                }
                consoleOk = true;
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning("[告警] 控制台推送失败: " + e.getMessage());
            }
        }
        if (cfg.alerts_in_game) {
            try {
                broadcastToAlertPlayers(Component.text(text, color));
                gameOk = true;
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning("[告警] 游戏内推送失败: " + e.getMessage());
            }
        }
        WebhookNotifier wn = webhookNotifier;
        if (wn != null) {
            try {
                wn.send(text);
                webhookQueued = wn.isReady();
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning("[告警] webhook 入队失败: " + e.getMessage());
            }
        }
        if (counted) {
            for (int i = 0; i < alertCount; i++) {
                countPushed();
                if (consoleOk || gameOk || webhookQueued) {
                    pushOkToday++;
                } else {
                    pushFailToday++;
                }
                if (webhookQueued) {
                    webhookQueuedToday++;
                }
            }
        }
    }

    /** 9A-14：完整细节（控制台/webhook）：key + 值 + 阈值 + 持续时长 + 下一步（服名已在前缀里）。 */
    private String fullDetail(ActiveAlert rec, long now) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(" | key=").append(rec.key);
        if (rec.value != null && !rec.value.isEmpty()) {
            sb.append(" 值=").append(rec.value);
        }
        String th = thresholdText(rec.key);
        if (!th.isEmpty()) {
            sb.append(" 阈值(").append(th).append(")");
        }
        sb.append(" | 持续 ").append(formatDuration(now - rec.firstSeen))
          .append(" | 下一步: ").append(nextStep(metricOf(rec.key)));
        return sb.toString();
    }

    /** 9A-14：游戏内短细节（聊天栏宽度有限，只留服名/持续/下一步）。 */
    private String shortDetail(ActiveAlert rec, long now) {
        return " | 持续 " + formatDuration(now - rec.firstSeen)
            + " | 下一步: " + nextStep(metricOf(rec.key));
    }

    /**
     * 向所有在线且持有 perfscope.alert 权限的玩家发送消息。
     * 必须运行在主线程（本服务定时器经 SchedulerAdapter.runGlobalTimer 调度，天然满足）。
     */
    private void broadcastToAlertPlayers(Component component) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(PERMISSION_ALERT)) {
                player.sendMessage(component);
            }
        }
    }

    // ==================== 9A-14：文案辅助 ====================

    private static String levelCn(Alert alert) {
        return alert.critical() ? "严重" : "警告";
    }

    /** key 的指标前缀（"tps:global" → "tps"）。 */
    private static String metricOf(String key) {
        if (key == null) {
            return "";
        }
        int i = key.indexOf(':');
        return i > 0 ? key.substring(0, i) : key;
    }

    /** 告警所属模块：优先用 Alert.module()，缺失时按 key 前缀推断（重启 rehydrate 后只有 key）。 */
    private static String moduleOf(Alert alert) {
        return alert.module() != null ? alert.module() : metricOf(alert.key());
    }

    /** 从 message 末尾冒号后提取"值"（"TPS 严重偏低: 12.3" → "12.3"）。 */
    private static String valueOf(Alert alert) {
        String m = alert.message() == null ? "" : alert.message();
        int i = Math.max(m.lastIndexOf(':'), m.lastIndexOf('：'));
        return i >= 0 && i + 1 < m.length() ? m.substring(i + 1).trim() : "";
    }

    /** 9A-14：按 key 反查当前阈值文案（采集层已判定，这里只用于"值/阈值"展示；不改判定口径）。 */
    private String thresholdText(String key) {
        ConfigManager.ConfigData c = configManager.get();
        if (c == null) {
            return "";
        }
        try {
            switch (metricOf(key)) {
                case "tps":
                    return "warning < " + c.tps_warningThreshold + " / critical < " + c.tps_criticalThreshold;
                case "mspt":
                    return "warning > " + c.mspt_warningThreshold + "ms / critical > " + c.mspt_criticalThreshold + "ms";
                case "players":
                    return "warning > " + c.players_warningThreshold + " / critical > " + c.players_criticalThreshold;
                case "entities":
                    return (key.startsWith("entities:world:") ? "世界阈值见 worlds 段" : "warning > "
                        + c.entities_totalWarningThreshold + " / critical > " + c.entities_totalCriticalThreshold);
                case "chunks":
                    return "warning > " + c.chunks_loadedWarningThreshold + " / critical > " + c.chunks_loadedCriticalThreshold;
                case "memory":
                    return "warning ≥ " + c.memory_heapWarningThreshold + "% / critical ≥ " + c.memory_heapCriticalThreshold + "%";
                case "cpu":
                    return "进程 > " + c.cpu_processWarningThreshold + "% / 系统 > " + c.cpu_systemWarningThreshold + "%";
                default:
                    return "";
            }
        } catch (Exception | LinkageError e) {
            return "";
        }
    }

    /** 9A-14：一行可执行建议（runbook）。 */
    private static String nextStep(String metric) {
        switch (metric) {
            case "tps":
                return "执行 /perfscope diagnose 找根因，优先看实体/区块与卡顿插件";
            case "mspt":
                return "执行 /perfscope 看长耗时 tick，检查红石机器与区块加载";
            case "players":
                return "处于高峰属正常；非高峰请检查登录抖动或压测流量";
            case "entities":
                return "执行 /perfscope hotspot 定位实体密集区块，清理掉落物/村民";
            case "chunks":
                return "检查 view-distance 与预生成插件（chunky 等）是否在跑";
            case "memory":
                return "检查 -Xmx 与 GC 日志；持续高位可考虑扩容或排查内存泄漏";
            case "cpu":
                return "检查宿主机邻居占用/备份任务；结合 /perfscope 看进程与系统 CPU";
            default:
                return "执行 /perfscope 查看全量诊断";
        }
    }

    /** 9A-14：服名（motd 首行去色码，截断 16 字符）；取不到退化为端口号。 */
    private String serverTag() {
        String cached = serverTag;
        if (cached != null) {
            return cached;
        }
        String tag = null;
        try {
            // server.properties 的 motd（Bukkit Server#getMotd）；多行取首行，去掉 § 颜色码
            String motd = Bukkit.getServer().getMotd();
            if (motd != null) {
                String first = motd.split("\\R", 2)[0];
                first = first.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
                if (!first.isEmpty()) {
                    tag = first.length() > 16 ? first.substring(0, 16) + "…" : first;
                }
            }
        } catch (Throwable ignored) {
            // motd 不可用：下沉到端口号
        }
        if (tag == null) {
            try {
                tag = ":" + Bukkit.getServer().getPort();
            } catch (Throwable ignored) {
                // 端口也取不到：最后用实现名
            }
        }
        if (tag == null) {
            tag = Bukkit.getName();
        }
        serverTag = tag;
        return tag;
    }

    /** 9A-14：毫秒 → "12 秒" / "3 分 12 秒" / "1 小时 3 分"（恢复/告警文案共用）。 */
    private static String formatDuration(long ms) {
        long s = Math.max(0, ms / 1000L);
        if (s < 60) {
            return s + " 秒";
        }
        long m = s / 60;
        long rs = s % 60;
        if (m < 60) {
            return rs == 0 ? m + " 分钟" : m + " 分 " + rs + " 秒";
        }
        long h = m / 60;
        long rm = m % 60;
        return rm == 0 ? h + " 小时" : h + " 小时 " + rm + " 分";
    }

    // ==================== 去重 / 冷却 ====================

    /** 判断某 key 是否仍在冷却期内；冷却 <=0 视为无冷却（天然受采集周期节流，不会刷屏） */
    private boolean isCooling(String key, long now, long cooldownMs) {
        if (cooldownMs <= 0) {
            return false;
        }
        Long lastPush = cooldowns.get(key);
        return lastPush != null && now - lastPush < cooldownMs;
    }

    // ==================== 状态机值对象 ====================

    /** 9A-14：最近一次恢复信息（复燃窗口：时间 + 该问题最初的 firstSeen，用于持续时长连续累计）。 */
    private static final class ResolvedInfo {
        final long atMs;
        final long firstSeen;

        ResolvedInfo(long atMs, long firstSeen) {
            this.atMs = atMs;
            this.firstSeen = firstSeen;
        }
    }

    /** 9A-14：一条活跃告警的完整生命周期记录（内存 + alerts.state 的映射目标）。 */
    private static final class ActiveAlert {
        final String key;
        Alert alert;
        long firstSeen;
        long lastSeen;
        /** 从 alerts.state rehydrate 且尚未被真实快照确认（确认后静默接管并清零）。 */
        boolean restored;
        String value;
        String module;

        ActiveAlert(String key, Alert alert, long firstSeen, long lastSeen, boolean restored) {
            this.key = key;
            this.alert = alert;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.restored = restored;
            this.value = valueOf(alert);
            this.module = moduleOf(alert);
        }
    }

    // ==================== 9A-14：状态持久化（alerts.state） ====================

    private File stateFile() {
        return new File(plugin.getDataFolder(), "alerts.state");
    }

    /**
     * 9A-14 / 9A-18：把当前活跃告警落盘（仅状态变化时调用；先写临时文件再原子替换，避免半截文件）。
     *
     * <p><b>为什么必须异步</b>：本方法由 {@link #onSnapshot} 经主线程定时器调用。
     * JFR 实测（2000 区块 / 6000 实体负载下）抓到主线程栈：
     * {@code Files.write ← AlertService.persistState ← onSnapshot ← checkCycle}——
     * 即每次告警状态变化都在 tick 线程上做一次 open+write+move。这与 9A-18「历史写盘已改异步」
     * 的原则冲突（性能插件自己制造主线程 IO）。现改为投递到单写者守护线程执行。</p>
     */
    private void persistState() {
        // 关键防呆：rehydrate 之前绝不写盘——否则 start() 首行的 stop() 会用空状态覆盖磁盘上的旧记录
        if (!stateLoaded) {
            return;
        }
        // 先在调用方（主线程）快照状态，避免异步线程读到正在变化的结构
        final String payload;
        try {
            File f = stateFile();
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                plugin.getLogger().warning("[告警] 告警状态目录创建失败，状态仅在内存生效");
                return;
            }
            StringBuilder sb = new StringBuilder(256);
            sb.append("# PerfScope 9A-14 告警状态（自动生成，请勿手改）\n");
            sb.append("# key|level|firstSeen|lastSeen|value|display|module|state\n");
            sb.append("# state: active | unknown（模块长期不可用被挂起；重启后同样不重报）\n");
            for (ActiveAlert r : activeAlerts.values()) {
                appendStateLine(sb, r, "active");
            }
            for (ActiveAlert r : suspendedAlerts.values()) {
                appendStateLine(sb, r, "unknown");
            }
            payload = sb.toString();
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[告警] 告警状态序列化失败: " + e.getMessage());
            return;
        }
        try {
            stateWriter.execute(() -> writeStateFile(payload));
        } catch (Exception e) {
            // 执行器已关闭（关服后期）：退回同步写，保证最后一份状态不丢
            writeStateFile(payload);
        }
    }

    /** 9A-18：真正的落盘动作，只在告警状态写线程（或关服兜底）上执行。 */
    private void writeStateFile(String payload) {
        try {
            File f = stateFile();
            File tmp = new File(f.getParentFile(), "alerts.state.tmp");
            Files.write(tmp.toPath(), payload.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[告警] 告警状态写入失败（重启后可能重复评估，不影响运行）: " + e.getMessage());
        }
    }

    /** 9A-14：写一行状态记录（active/unknown 共用）。 */
    private static void appendStateLine(StringBuilder sb, ActiveAlert r, String state) {
        sb.append(escape(r.key)).append('|')
          .append(escape(r.alert.level())).append('|')
          .append(r.firstSeen).append('|')
          .append(r.lastSeen).append('|')
          .append(escape(r.value)).append('|')
          .append(escape(r.alert.display())).append('|')
          .append(escape(r.module == null ? "" : r.module)).append('|')
          .append(state).append('\n');
    }

    /** 9A-14：启动 rehydrate（每进程一次；reload 不重读，避免覆盖 reload 期间新的状态）。 */
    private void loadState() {
        if (stateLoaded) {
            return;
        }
        stateLoaded = true;
        File f = stateFile();
        if (!f.isFile()) {
            return;
        }
        int restored = 0;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                String[] p = s.split("\\|", -1);
                if (p.length < 6) {
                    continue;
                }
                String key = unescape(p[0]);
                String level = unescape(p[1]);
                long firstSeen = parseLong(p[2], 0L);
                long lastSeen = parseLong(p[3], 0L);
                String value = unescape(p[4]);
                String display = unescape(p[5]);
                String module = p.length >= 7 ? unescape(p[6]) : "";
                String state = p.length >= 8 ? unescape(p[7]).trim() : "active";
                if (key.isEmpty()) {
                    continue;
                }
                // 用持久化的 level/display 重建 Alert（message 用 display 兜底：restored 记录不参与推送文案）
                String msg = display.isEmpty() ? key : display;
                Alert alert = new Alert(key, level, msg, display.isEmpty() ? Alert.displayOf(msg) : display,
                    module.isEmpty() ? metricOf(key) : module, lastSeen);
                ActiveAlert rec = new ActiveAlert(key, alert, firstSeen <= 0 ? lastSeen : firstSeen, lastSeen, true);
                rec.value = value;
                rec.module = module.isEmpty() ? metricOf(key) : module;
                if ("unknown".equals(state)) {
                    // 关服前就处于 unknown：原样恢复为 unknown，等模块回来再决定静默接管/静默解决
                    suspendedAlerts.put(key, rec);
                    moduleDownSince.put(key, System.currentTimeMillis());
                } else {
                    activeAlerts.put(key, rec);
                }
                cooldowns.put(key, System.currentTimeMillis()); // 重启后不立刻重推
                restored++;
            }
            if (restored > 0) {
                plugin.getLogger().info("[告警] 已从 alerts.state 恢复 " + restored
                    + " 条未恢复告警（重启后首次观测不重复推送）");
            }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[告警] 告警状态读取失败（按全新状态运行）: " + e.getMessage());
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 状态文件字段转义（'|' 是分隔符，反斜杠用于转义自身）。 */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("|", "\\p").replace('\n', ' ').replace('\r', ' ');
    }

    private static String unescape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'p' ? '|' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ==================== 9A-14：告警历史（alerts.jsonl） ====================

    /**
     * 一条告警历史事件（9A-14）。命令层 {@code /perfscope alerts [N]} 直接消费本类型。
     *
     * @param ts      事件时间（epoch ms）
     * @param type    open / upgrade / downgrade / resolve / refire / restore / suspend / resolved_by_disable
     * @param key     告警唯一身份（metric:scope）
     * @param level   warning / critical
     * @param value   观测值（message 最后一个冒号后）
     * @param display 告警类别文案（恢复文案锚点）
     */
    public record AlertEvent(long ts, String type, String key, String level, String value, String display) {
    }

    private File eventsFile() {
        return new File(plugin.getDataFolder(), "alerts.jsonl");
    }

    /**
     * 最近 {@code limit} 条告警历史（旧→新）。
     *
     * <p>命令层接口：{@code plugin.getAlertService().recent(n)}。内存镜像最多保留
     * {@value #MAX_RECENT_EVENTS} 条；首次调用时从 alerts.jsonl 播种（跨重启也能看到历史）。</p>
     */
    public List<AlertEvent> recent(int limit) {
        int n = limit <= 0 ? 20 : Math.min(limit, MAX_RECENT_EVENTS);
        seedRecentEvents();
        List<AlertEvent> all;
        synchronized (this) {
            all = new ArrayList<>(recentEvents);
        }
        int from = Math.max(0, all.size() - n);
        return List.copyOf(all.subList(from, all.size()));
    }

    /**
     * 9A-14：记录一条历史事件（内存 + jsonl 追加）。
     * 内存镜像写第一个、IO 在后——即便首次播种失败，本进程新增的事件也一定能在 {@link #recent(int)} 里看到。
     */
    private void recordEvent(String type, ActiveAlert rec) {
        AlertEvent ev = new AlertEvent(System.currentTimeMillis(), type, rec.key,
            rec.alert.level(), rec.value == null ? "" : rec.value, rec.alert.display());
        synchronized (this) {
            recentEvents.addLast(ev);
            while (recentEvents.size() > MAX_RECENT_EVENTS) {
                recentEvents.removeFirst();
            }
        }
        appendEventLine(ev);
    }

    private void appendEventLine(AlertEvent ev) {
        try {
            File f = eventsFile();
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return; // 目录建不出来：历史功能静默降级，不影响告警本体
            }
            if (f.isFile() && f.length() > MAX_EVENT_FILE_BYTES) {
                // 单文件轮转一次（保留上一代，够事故复盘；不再无限增长）
                Files.move(f.toPath(), new File(dir, "alerts.jsonl.1").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            String line = "{\"ts\":" + ev.ts()
                + ",\"type\":\"" + jsonEscape(ev.type())
                + "\",\"key\":\"" + jsonEscape(ev.key())
                + "\",\"level\":\"" + jsonEscape(ev.level())
                + "\",\"value\":\"" + jsonEscape(ev.value())
                + "\",\"display\":\"" + jsonEscape(ev.display())
                + "\"}\n";
            Files.write(f.toPath(), line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[告警] 告警历史写入失败（告警本身不受影响）: " + e.getMessage());
        }
    }

    /** 9A-14：首次访问时从 alerts.jsonl 尾部播种内存镜像（只读一次，避免命令层反复读盘）。 */
    private void seedRecentEvents() {
        synchronized (this) {
            if (recentSeeded) {
                return;
            }
            recentSeeded = true;
        }
        try {
            File f = eventsFile();
            if (!f.isFile()) {
                return;
            }
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - MAX_RECENT_EVENTS);
            List<AlertEvent> seeded = new ArrayList<>();
            for (int i = from; i < lines.size(); i++) {
                AlertEvent ev = parseEventLine(lines.get(i));
                if (ev != null) {
                    seeded.add(ev);
                }
            }
            synchronized (this) {
                // 播种插到已有（本进程新增的）事件之前，保持"旧→新"；不覆盖运行时事件
                for (int i = seeded.size() - 1; i >= 0; i--) {
                    recentEvents.addFirst(seeded.get(i));
                }
                while (recentEvents.size() > MAX_RECENT_EVENTS) {
                    recentEvents.removeFirst();
                }
            }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[告警] 告警历史读取失败（本次仅显示进程内事件）: " + e.getMessage());
        }
    }

    /** 解析自己写出的 JSON 行（字段固定，无需通用 JSON 解析器）。 */
    private static AlertEvent parseEventLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String type = jsonField(line, "type");
        String key = jsonField(line, "key");
        if (type.isEmpty() || key.isEmpty()) {
            return null;
        }
        long ts;
        try {
            ts = Long.parseLong(jsonField(line, "ts"));
        } catch (NumberFormatException e) {
            ts = 0L;
        }
        return new AlertEvent(ts, type, key, jsonField(line, "level"),
            jsonField(line, "value"), jsonField(line, "display"));
    }

    /** 极简 JSON 字段提取（仅支持本类写出的扁平对象与字符串/数字字段）。 */
    private static String jsonField(String line, String field) {
        String needle = "\"" + field + "\":\"";
        int i = line.indexOf(needle);
        if (i < 0) {
            // 数字字段
            needle = "\"" + field + "\":";
            i = line.indexOf(needle);
            if (i < 0) {
                return "";
            }
            int start = i + needle.length();
            int end = start;
            while (end < line.length() && "-0123456789".indexOf(line.charAt(end)) >= 0) {
                end++;
            }
            return line.substring(start, end);
        }
        int start = i + needle.length();
        StringBuilder sb = new StringBuilder();
        for (int p = start; p < line.length(); p++) {
            char c = line.charAt(p);
            if (c == '\\' && p + 1 < line.length()) {
                char n = line.charAt(++p);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    default: sb.append(n); break;
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
