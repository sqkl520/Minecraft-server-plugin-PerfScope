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
import cn.perfscope.compat.ServerCompat;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.util.DurationFormat;
import cn.perfscope.util.Messages;
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

    /**
     * 9C-4：复燃最短重新通知间隔的默认值（秒）。
     * 60 秒的依据：真机实测中"恢复→复燃"的锯齿间隔在 2–43 秒之间摆动，
     * 60 秒能覆盖整段抖动周期，同时不会把"故障真的再次发生"压到用户无法感知。
     */
    private static final long DEFAULT_REFIRE_MIN_INTERVAL_MS = 60_000L;

    /**
     * 9C-4：抖动窗口默认值（秒）。
     *
     * <p>取值依据（真机 10 分钟波动负载实测）：恢复→再次越界的间隔落在 4–66 秒区间，
     * 60 秒窗口漏掉了 66 秒那次。取 180 秒可以覆盖"阈值的呼吸"这一整类现象——
     * 一条告警若在 3 分钟内反复起落，它的事实是"这个问题一直没好"，
     * 中间那些"已恢复"对用户没有任何价值。</p>
     *
     * <p>为什么不是更长：超过 5 分钟会把"真实的短暂故障"也吞掉（故障发生→5 分钟内自愈→
     * 用户从未收到过任何通知）。180 秒在两者之间。</p>
     */
    private static final long DEFAULT_FLAP_WINDOW_MS = 180_000L;

    /**
     * 9C-4：同级别重推的数值变化阈值默认值。
     * 与迟滞带同量级（5–8）：小于它的变化本就不会改变状态机判定，重推只是重复噪音。
     * 实测依据——该值在 6 小时合成轨迹上把重推从 65 条降到几乎为 0，而真满载轨迹的重推语义不变。
     */
    private static final double DEFAULT_REPUSH_DELTA = 8.0;

    /** 9A-14：告警历史文件单文件上限（超过即轮转为 alerts.jsonl.1），防止长期运行无限膨胀。 */
    private static final long MAX_EVENT_FILE_BYTES = 2L * 1024L * 1024L;

    /** 9A-14：内存中保留的最近事件条数（{@link #recent(int)} 的上限）。 */
    private static final int MAX_RECENT_EVENTS = 500;

    /**
     * 9B-6：启动抑制窗口的默认秒数（Folia 启动初期 global region 的 TPS 报表空窗）。
     *
     * <p>只用常量、不读 config.yml：本修复不新增配置键（config.yml 由其他改动占用中）。
     * 建议后续在 {@code alerts} 段加 {@code startup-suppress-seconds}（0 = 关闭），
     * 缺省仍为本值；接入点即 {@link #startupSuppressSeconds}。</p>
     */
    private static final int DEFAULT_STARTUP_SUPPRESS_SECONDS = 60;

    /**
     * 9B-6：启动抑制窗口的<b>下限</b>——窗口至少覆盖 {@code 3 × general.update_interval} 个采集周期，
     * 保证"空窗期"内至少发生过若干个快照；配置的间隔越大，窗口相应拉长。
     */
    private static final int STARTUP_SUPPRESS_MIN_CYCLES = 3;

    /** 9B-6：只抑制 TPS 指标的告警（key 形如 {@code tps:global}，证据见 perfscope-alert.json）。 */
    private static final String STARTUP_SUPPRESS_METRIC = "tps:";

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

    /**
     * 9C-4：抖动计数——key → 在抖动窗口内被抑制的次数。
     *
     * <p>达到 {@link #FLAP_ESCALATE_THRESHOLD} 次后，该 key 进入"稳定抖动"状态：
     * 恢复判据从"连续 {@code recoveryCycles} 个干净周期"改为"连续干净 {@code flapWindowMs} 毫秒"。
     * 理由：一条 20 分钟振荡一次的告警若按 3 秒判恢复，会在 6 小时里产出 32 条消息；
     * 而它的事实是"这个问题一直没好"。用户真正需要的是"真的好了"那一刻的通知，
     * 不是每一次阈值的呼吸。</p>
     *
     * <p>该状态在"确实长时间干净并宣布恢复"后由 {@link #flapHealCycles} 计时清零。</p>
     */
    private final Map<String, Integer> flapCounts = new ConcurrentHashMap<>();

    /** 9C-4：抖动窗口内被抑制多少次后判定为"稳定抖动"（恢复判据随之加严）。 */
    private static final int FLAP_ESCALATE_THRESHOLD = 3;

    /**
     * 9C-4：抖动计数"痊愈"所需的干净周期数（默认 300 ≈ 5 分钟）。
     *
     * <p>为什么要这一层：抖动计数若在每次恢复时清零，一条"每 20 分钟振荡一次"的告警
     * 永远攒不满 3 次，恢复判据永远停在 3 秒——这正是实测 A 轨迹还剩 16 条消息的原因。
     * 只有在连续干净超过这个时长后，才认定"抖动真的平息了"，把计数清零。</p>
     */
    private static final int FLAP_HEAL_CYCLES = 300;

    /** 9C-4：抖动痊愈计时表——key → 连续干净的周期数（用于决定何时清零 {@link #flapCounts}）。 */
    private final Map<String, Integer> flapHealCycles = new ConcurrentHashMap<>();

    /**
     * 9C-4：本次连续干净段的起始时刻（epoch ms）——key → 时刻。
     *
     * <p>用于"抖动中的告警"按**时间**判定恢复（而不是数干净周期）：
     * 实测真实 CPU 序列在 σ=13 的饱和背景下，跌破退出线的最长连续干净段只有 24 个周期，
     * 任何超过该值的周期数判据都结构性不可达，会让告警永久挂在 active。
     * 本周期重新越界时立即清除（干净段被打断）。</p>
     */
    private final Map<String, Long> cleanRunStartMs = new ConcurrentHashMap<>();

    /**
     * 9C-4：适用"稳定告警不重推"的 key 白名单。
     *
     * <p>只包含 {@code cpu:system}——它的分母是整机 CPU，包含 JVM 之外的一切占用。
     * 服主看到"系统CPU负载偏高: 92%"第五次、第十次，能做的动作都是同一个。
     * 其余指标（内存/实体/区块/TPS/MSPT/玩家）都排除在外：它们是服主可处置的，
     * 重推是"问题还在"的有效提醒，抑制它们等于让真问题静默。</p>
     */
    private static final java.util.Set<String> REPUSH_SUPPRESSIBLE_KEYS = java.util.Set.of("cpu:system");

    /**
     * 9C-4：抖动抑制标记——该 key 的恢复被"刚恢复过"的窗口挡下了。
     * 仅用于诊断日志与后续清理，不参与判定（判定只看 {@link #lastResolvedAt}）。
     */
    private final java.util.Set<String> flapSuppressed = ConcurrentHashMap.newKeySet();

    /**
     * 9C-4：复燃最短重新通知间隔（毫秒）。
     *
     * <p>与 {@code cooldown-seconds} 刻意的分离：两者的理想取值方向相反——
     * 冷却要长（别刷屏），复燃窗口要短（几秒内又坏了确实该立即告知）。
     * 复用同一个数会让"真复燃要立即到达"（9A-14 的初衷）与"别被抖动刷屏"（本次修复）
     * 争抢同一个旋钮，无论调成多少都有一方受害。</p>
     */
    private volatile long refireMinIntervalMs = DEFAULT_REFIRE_MIN_INTERVAL_MS;

    /**
     * 9C-4：抖动窗口（毫秒）——距上次恢复不足该时长的"再次恢复"不推送、不移出 active。
     * 0 = 关闭抖动抑制（退回旧行为）。
     */
    private volatile long flapWindowMs = DEFAULT_FLAP_WINDOW_MS;

    /**
     * 9C-4：同级别重推的数值变化阈值（百分点/同单位）。
     * 低于该幅度的变化视为"没有新信息"，不再周期性重推——这是"E5 常态化满载"场景下
     * 从 92 条/6 小时降到个位数的主因。0 = 关闭该抑制（退回旧行为：每冷却期重推一次）。
     */
    private volatile double repushDelta = DEFAULT_REPUSH_DELTA;

    /**
     * 9C-4：每个 key 的数值滑动窗口（最近 {@link #NOISE_WINDOW} 次观测），用于估计采样噪声 σ̂。
     *
     * <p>为什么需要它：{@code alerts.repush-delta} 是**绝对值**（默认 8 个百分点），
     * 但不同机器的采样噪声差异极大——本机受控实验实测 σ≈12，而安静机器可能只有 1–2。
     * 用一个固定值会让"噪声大的机器每个冷却周期都重推一次"（比不抑制还密），
     * 或让"安静机器该报的变化被吞掉"。σ̂ 让阈值自适应：有效阈值 = delta × max(1, σ̂/margin)。</p>
     */
    private final Map<String, Deque<Double>> recentValues = new ConcurrentHashMap<>();

    /** 9C-4：噪声估计窗口长度（样本数）。60 个 1Hz 样本 ≈ 1 分钟，足够稳定且能跟上负载画像变化。 */
    private static final int NOISE_WINDOW = 60;

    /** 9C-4：σ̂ 与 margin 的最小允许比值——避免噪声极小的机器把阈值压到 0（等于关闭抑制）。 */
    private static final double MIN_NOISE_RATIO = 1.0;

    /** 9C-4：σ̂/margin 比值的上限——防止一次极端尖峰把阈值抬到永远触发不了。 */
    private static final double MAX_NOISE_RATIO = 4.0;

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

    /**
     * v1.4：生命周期事件的进程内累计计数（Prometheus counter 源）。
     *
     * <p>不能用 {@link #recentEvents} 代替——那是 500 条环形镜像，满了会丢最旧的，
     * 拿它算计数会得出"发生次数恒 ≤ 500"的错误结论。基数有界（9 个写死的事件类型）。</p>
     */
    private final Map<String, java.util.concurrent.atomic.LongAdder> alertEventCounters =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 9B-6：插件启用时刻（构造时记录；reload 不重置——抑制窗口只针对"启动初期"，
     * 运行中途 reload 不该把 TPS 告警静音 60 秒）。
     */
    private final long pluginEnabledAtMs = System.currentTimeMillis();

    /**
     * 9B-6：启动抑制窗口是否启用（仅 Folia）。
     *
     * <p>为什么只在 Folia 上启用：Paper/Spigot 的 {@code Bukkit.getTPS()} 从第一个 tick 起就有
     * 有效滑动平均值，不存在"报表空窗"；只有 Folia 的 global region 在启动初期会短暂报空/接近 0。
     * 在 Paper 上启用只会无收益地把真实 TPS 问题静音 60 秒。</p>
     */
    private final boolean startupSuppressEnabled;

    /** 9B-6：启动抑制窗口结束时刻（epoch ms；未启用时为 {@link #pluginEnabledAtMs}）。 */
    private final long startupSuppressUntilMs;

    /** 9B-6：实际窗口秒数（日志/诊断使用）。 */
    private final int startupSuppressSeconds;

    /** 9B-6：窗口内是否有 TPS 推送被抑制（窗口结束时打一条汇总，避免"静音得无声无息"）。 */
    private boolean startupSuppressedAnyPush = false;
    /** 9B-6：窗口结束汇总是否已打（只打一次）。 */
    private boolean startupSuppressSummaryLogged = false;
    /** 9B-6：启动抑制说明是否已打（每进程一次；reload 会再次调用 start()，不重复刷屏）。 */
    private boolean startupSuppressNoticeLogged = false;

    public AlertService(PerfScope plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();

        // 9B-6：窗口长度 = max(60 秒, 3 个采集周期)，且只在该服务端确实会报表空窗时启用
        ConfigManager.ConfigData cfg = configManager == null ? null : configManager.get();
        int intervalTicks = cfg == null ? 20 : Math.max(1, cfg.general_updateInterval);
        long cycleSeconds = (intervalTicks + 19L) / 20L; // tick → 秒，向上取整
        int seconds = (int) Math.max(DEFAULT_STARTUP_SUPPRESS_SECONDS,
            STARTUP_SUPPRESS_MIN_CYCLES * cycleSeconds);
        this.startupSuppressSeconds = seconds;
        this.startupSuppressEnabled = ServerCompat.isFolia() && seconds > 0;
        this.startupSuppressUntilMs = pluginEnabledAtMs + (startupSuppressEnabled ? seconds * 1000L : 0L);
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
            plugin.getLogger().info(logText("alert.log.disabled"));
            return;
        }
        long interval = Math.max(1, cfg.general_updateInterval);
        // 首查延迟一个采集周期：等采集器先产出首份快照；runGlobalTimer 在主线程执行，玩家消息发送天然安全
        timerHandle = SchedulerAdapter.runGlobalTimer(plugin, this::checkCycle, interval, interval);
        if (!timerHandle.isActive()) {
            // f-s01-err-01：noop 句柄 = 任务没装上（原因已由 SchedulerAdapter 记 severe）。照旧打印
            // "已启动"就是告警永久停摆而控制台显示正常。
            // v1.3.0 -14：文案改走键表（此前是内联中英三元表达式，绕过消息层）。
            plugin.getLogger().severe(logText("alert.log.start-failed-no-scheduler"));
            return;
        }
        Options o = options;
        plugin.getLogger().info(logText("alert.log.started",
            interval, cfg.alerts_cooldownSeconds, Math.max(1, cfg.alerts_debounceCycles),
            o.recoveryDebounceCycles,
            Messages.get(o.digestMode ? "alert.msg.digest-on" : "alert.msg.digest-off")));
        // 9B-6：启动抑制窗口说明（仅 Folia；每进程只打一次，reload 不重复刷屏）
        if (startupSuppressEnabled && !startupSuppressNoticeLogged) {
            startupSuppressNoticeLogged = true;
            plugin.getLogger().info(logText("alert.log.startup-suppress", startupSuppressSeconds));
        }
    }

    /**
     * 9B-6：启动抑制窗口是否正在生效（窗口内 + 仅 Folia + 仅 TPS 指标）。
     *
     * <p>窗口内的 TPS 告警<b>照常</b>走完状态机（activeAlerts / 冷却 / 历史事件），
     * 只是不投递到控制台 / 游戏内 / webhook；窗口结束时也不补发——否则启动 60 秒后
     * 会突然涌出一批"历史告警"，比误报本身更吵。</p>
     */
    private boolean startupSuppressed(ActiveAlert rec, long now) {
        return startupSuppressEnabled
            && now < startupSuppressUntilMs
            && rec != null && rec.key != null && rec.key.startsWith(STARTUP_SUPPRESS_METRIC);
    }

    /** 9B-6：记录一次"被抑制的 TPS 推送"（窗口结束时汇总一条 INFO，静音不留白）。 */
    private void noteStartupSuppressed(ActiveAlert rec) {
        startupSuppressedAnyPush = true;
        plugin.getLogger().log(Level.FINE, logText("alert.log.startup-suppress-skip", rec.key));
    }

    /** 9B-6：窗口结束时汇总一次被抑制的条数（只打一条，不补发任何告警）。 */
    private void maybeLogStartupSuppressEnd() {
        if (!startupSuppressEnabled || startupSuppressSummaryLogged) {
            return;
        }
        if (System.currentTimeMillis() < startupSuppressUntilMs) {
            return;
        }
        startupSuppressSummaryLogged = true;
        if (startupSuppressedAnyPush) {
            plugin.getLogger().info(logText("alert.log.startup-suppress-end"));
        }
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
        // f-s01-data-05：进入时捕获旧快照，供末尾判定「时区规格是否真的变了」。
        Options previous = this.options;
        try {
            RawConfig raw = RawConfig.load(plugin);
            ConfigManager.ConfigData cfg = configManager.get();
            int debounce = cfg == null ? 3 : Math.max(1, cfg.alerts_debounceCycles);
            int rec = raw.getInt("alerts.recovery-debounce-cycles", debounce);
            if (rec < 1) {
                plugin.getLogger().warning(logText("alert.log.bad-recovery-debounce", rec, debounce));
                rec = debounce;
            }
            o.recoveryDebounceCycles = rec;
            o.digestMode = raw.getBool("alerts.digest-mode", false);
            o.notifyMitigation = raw.getBool("alerts.notify-mitigation", true);
            int unknown = raw.getInt("alerts.unknown-after-minutes", DEFAULT_UNKNOWN_AFTER_MINUTES);
            if (unknown < 1) {
                plugin.getLogger().warning(logText("alert.log.bad-unknown-after", unknown,
                    DEFAULT_UNKNOWN_AFTER_MINUTES));
                unknown = DEFAULT_UNKNOWN_AFTER_MINUTES;
            }
            o.unknownAfterMinutes = unknown;
            String tz = raw.getString("alerts.daily-summary.timezone", "");
            o.dailyTimezone = tz == null ? "" : tz.trim();
            // 9C-4：复燃最短间隔与抖动窗口（秒）。0 = 关闭对应抑制，负值/非数值回退默认。
            // f-s01-err-10：本键是 loadOptions 里唯一既不被 ConfigManager 读取、也不在 jar 内
            // config.yml 中登记的键——写错时没有第二处校验会报，故在此补一条带原值的 WARNING。
            String badRefire = raw.unparsableInt("alerts.refire-min-interval-seconds");
            if (badRefire != null) {
                plugin.getLogger().warning(logText("alert.log.refire-unparsable", badRefire,
                    (int) (DEFAULT_REFIRE_MIN_INTERVAL_MS / 1000L)));
            }
            int refireSec = raw.getInt("alerts.refire-min-interval-seconds",
                (int) (DEFAULT_REFIRE_MIN_INTERVAL_MS / 1000L));
            if (refireSec < 0) {
                long def = DEFAULT_REFIRE_MIN_INTERVAL_MS / 1000L;
                plugin.getLogger().warning(logText("alert.log.bad-refire", refireSec, def));
                refireSec = (int) def;
            }
            this.refireMinIntervalMs = refireSec * 1000L;
            int flapSec = raw.getInt("alerts.flap-window-seconds",
                (int) (DEFAULT_FLAP_WINDOW_MS / 1000L));
            if (flapSec < 0) {
                long def = DEFAULT_FLAP_WINDOW_MS / 1000L;
                plugin.getLogger().warning(logText("alert.log.bad-flap", flapSec, def));
                flapSec = (int) def;
            }
            this.flapWindowMs = flapSec * 1000L;
            // 9C-4：同级别重推的数值变化阈值。0 = 关闭抑制（退回"每冷却期重推一次"）。
            double delta = raw.getDouble("alerts.repush-delta", DEFAULT_REPUSH_DELTA);
            if (!Double.isFinite(delta) || delta < 0) {
                plugin.getLogger().warning(logText("alert.log.bad-repush", delta, DEFAULT_REPUSH_DELTA));
                delta = DEFAULT_REPUSH_DELTA;
            }
            this.repushDelta = delta;
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning(logText("alert.log.options-failed", e.getMessage()));
        }
        this.options = o;
        // f-s01-data-05：pushedTodayDayEpoch 的初值在构造时按「系统时区」算出（那一刻 options 还是
        // 默认值、dailyTimezone 为空串）。这里换上配置时区后，若新旧时区不在同一 LocalDate，
        // 下一次 rollDay() 会误判跨天并把当日四个计数器整体清零——时区切换不是跨天。
        //
        // **只在时区规格真的变了时才重算基线**（f-s01-data-05-r1 回归修正）：
        // 初版写成无条件 `pushedTodayDayEpoch = currentDayEpoch()`，那会让「时区切换」与
        // 「真跨天」不可区分——服务端跨过午夜后、在首次推送（即首次 rollDay）之前执行
        // /perfscope reload，基线会被直接设成"今天"，昨天→今天的跳变永远观测不到，
        // 四个计数器跨天累积、每日摘要报出虚高的"今日已推送"。故必须比对旧时区。
        //
        // 只动基线字段，计数器值与 rollDay() 逻辑均不变。时区未变时本分支不执行，
        // rollDay() 的跨天归零语义完全保留。
        String prevTz = previous == null ? "" : previous.dailyTimezone;
        if (!prevTz.equals(o.dailyTimezone)) {
            this.pushedTodayDayEpoch = currentDayEpoch();
        }
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
                plugin.getLogger().warning(logText("alert.log.rawconfig-failed", e.getMessage()));
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

        /**
         * f-s01-err-10：整数键无法被 {@link #getInt} 解析时返回原值文本，缺键/合法返回 null。
         *
         * <p>只给 ConfigManager 不读的键用——其余键已由 {@code ConfigManager.readInt} 的
         * {@code warnType} 报过带原值的 WARNING，再报一次等于同一错误两条日志。当前
         * {@code loadOptions} 的 8 个键里只有 {@code alerts.refire-min-interval-seconds} 属此类。</p>
         */
        String unparsableInt(String path) {
            if (yaml == null || !yaml.contains(path)) {
                return null;
            }
            Object v = yaml.get(path);
            if (v instanceof Number n) {
                // getInt 用 intValue() 截断：1.5 静默变 1，与 ConfigManager.coerceInt 的
                // "非整数即类型错误" 口径分叉，故同样上报。
                return n.intValue() == n.doubleValue() ? null : String.valueOf(v);
            }
            if (v instanceof String s) {
                try {
                    Integer.parseInt(s.trim());
                    return null;
                } catch (NumberFormatException e) {
                    return s;
                }
            }
            return String.valueOf(v);
        }

        String getString(String path, String def) {
            if (yaml == null) {
                return def;
            }
            Object v = yaml.get(path);
            return v == null ? def : String.valueOf(v);
        }

        /** 9C-4：小数读取（不存在的键返回默认值；无法解析同样返回默认值）。 */
        double getDouble(String path, double def) {
            if (yaml == null) {
                return def;
            }
            Object v = yaml.get(path);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
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
            plugin.getLogger().warning(logText("alert.log.cycle-failed", e.getMessage()));
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
                    // 9D 修（同 BLOCKER-3）：重置重推基准，否则该 key 永久处于"无基准"状态
                    suspendedRec.numericValue = numericValueOf(alert);
                    suspendedRec.lastPushedValue = suspendedRec.numericValue;
                    suspendedRec.restored = false;
                    activeAlerts.put(key, suspendedRec);
                    moduleDownSince.remove(key);
                    // f-s01-logic-02：静默接管必须同时刷新冷却表。否则本周期不推送、下一周期走到
                    // 同级别分支时 isCooling 无记录（进 unknown 至少 5 分钟，旧记录已过期）→ 立刻重推，
                    // 与日志"不重复推送"及 rec.restored 分支（下方 cooldowns.put）口径矛盾。
                    cooldowns.put(key, now);
                    recordEvent("restore", suspendedRec);
                    stateDirty = true;
                    plugin.getLogger().log(Level.FINE, logText("alert.log.resume-unknown", key));
                    continue;
                }
                // —— 待确认 / 复燃 ——
                int count = debounceCounts.merge(key, 1, Integer::sum);
                ResolvedInfo resolved = lastResolvedAt.get(key);
                // 9C-4：复燃的判定窗口用 refireMinIntervalMs（配置 alerts.refire-min-interval-seconds），
                // 不再复用 cooldownSeconds。两者语义相反：冷却要长（别刷屏），
                // 复燃窗口要短（几秒内又坏了确实该立即告知）；复用同一个数会让一方受害。
                // 该键此前被声明与解析但从未被读取（红队 D4：死配置），现接上。
                // f-s01-logic-06：0 = 关闭复燃抑制（作者决策，按配置注释的语义实现）。
                // 0 → refireWindowMs = 0 → 下方 refireWindowMs > 0 恒假 → refire 恒 false，
                // 复燃走普通新告警路径（立即推送）；负值已在 loadOptions() 回退默认，不会到这里。
                long refireWindowMs = Math.max(0L, refireMinIntervalMs);
                boolean refire = resolved != null && refireWindowMs > 0
                    && now - resolved.atMs <= refireWindowMs;
                if (!refire && count < debounceCycles) {
                    continue; // 未达 N 周期：静默累计，暂不推送（防秒级抖动误报）
                }
                if (!refire && isCooling(key, now, cooldownMs)) {
                    continue; // 防抖已达但仍在冷却期：等冷却到期再推
                }
                // 9C-4：复燃不再无条件绕过冷却。
                // 背景：refire 分支原先用 `!refire &&` 同时短路防抖与冷却，设计意图是"真复燃要立即到达"
                // （9A-14 修的是"复燃被冷却静默 5 分钟"）。但指标在阈值附近抖动时，"恢复→复燃"会每几秒
                // 循环一次，冷却完全失效——实测 6 小时 1333 条消息。折中：复燃仍绕过**防抖**（必须立即到达），
                // 但遵守**最短重新通知间隔**。间隔取独立键而非复用 cooldown-seconds，因为两者语义相反：
                // 抑制窗口要长（别刷屏），复燃窗口要短（几秒内又坏了 = 同一次事故）。
                //
                // 参照点必须是**恢复时刻**而不是"上次推送时刻"：恢复时已刻意保留冷却表，
                // 但那条记录可能是几十分钟前的首次告警，用它做参照永远成立，等于没有抑制。
                if (refire && flapWindowMs > 0 && now - resolved.atMs < flapWindowMs) {
                    // 静默接管：问题确实存在，但这条通知刚发过。必须落 active 状态，
                    // 否则该 key 会进入"既不在 active 也不在待确认"的不可见状态，永远不再恢复。
                    // 刻意**保留** lastResolvedAt：下次再"干净"时它将触发抖动抑制，
                    // 使"恢复→复燃→恢复→复燃"在第二次就收敛，而不是无限循环。
                    ActiveAlert quiet = new ActiveAlert(key, alert, resolved.firstSeen, now, false);
                    activeAlerts.put(key, quiet);
                    debounceCounts.remove(key);
                    recoveryCounts.remove(key);
                    moduleDownSince.remove(key);
                    // f-s01-logic-02（姊妹分支）：静默接管同样必须刷新冷却表，理由同上方
                    // suspendedAlerts 分支——否则"抑制"只压一个周期，下一周期即以普通重推出现。
                    cooldowns.put(key, now);
                    noteFlap(key, now);
                    recordEvent("refire_suppressed", quiet);
                    stateDirty = true;
                    continue;
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
                    // 9B-6：启动抑制窗口内（仅 Folia + 仅 tps:）不投递，但状态机与历史照常
                    if (startupSuppressed(fresh, now)) {
                        noteStartupSuppressed(fresh);
                    } else {
                        pushRefire(fresh, now);
                    }
                } else {
                    recordEvent("open", fresh);
                    if (startupSuppressed(fresh, now)) {
                        noteStartupSuppressed(fresh);   // 9B-6：不进 digest 批次，避免窗口末尾一次补发
                    } else if (digest != null) {
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
                // 9D 修（红队 BLOCKER-3）：重置重推基准。
                // rehydrate 时若来自 v1 格式（没有结构化数值），Alert.value 是 NaN，
                // 构造函数把 lastPushedValue 置成了 null。这里若只刷新 alert/value 而不刷新基准，
                // 该 key 的 lastPushedValue 会**永久为 null** → significant 恒 true
                // → 每 cooldown 周期重推一次，永不停止。那正是 9C-4 修掉的"σ=12 时每 6 小时 59 条"。
                rec.numericValue = numericValueOf(alert);
                rec.lastPushedValue = rec.numericValue;
                rec.restored = false;
                cooldowns.put(key, now);
                recordEvent("restore", rec);
                stateDirty = true;
                plugin.getLogger().log(Level.FINE, logText("alert.log.resume-restored", key));
                continue;
            }

            // 本周期仍越界：恢复计数归零（连续性由恢复流程保证）
            recoveryCounts.remove(key);
            // 9C-4：干净段被打断——清除起始时刻，下次干净要重新计时
            cleanRunStartMs.remove(key);
            moduleDownSince.remove(key);

            if (alert.critical() && !rec.alert.critical()) {
                // —— 级别升级（warning→critical）：一事件一通知 ——
                rec.alert = alert;
                rec.lastSeen = now;
                rec.value = valueOf(alert);
                cooldowns.put(key, now); // 升级不受冷却限制（严重度上升应立即到达），推送后刷新冷却表
                recordEvent("upgrade", rec);
                if (startupSuppressed(rec, now)) {
                    noteStartupSuppressed(rec);   // 9B-6
                } else {
                    pushUpgrade(rec, now);
                }
                stateDirty = true;
            } else if (!alert.critical() && rec.alert.critical()) {
                // —— 9A-14 降级（critical→warning）：刷新记录 + 可选「已缓解」 ——
                rec.alert = alert;
                rec.lastSeen = now;
                rec.value = valueOf(alert);
                recordEvent("downgrade", rec);
                // 降级不是恢复：不写冷却表、不清恢复计数（本周期仍越界，恢复计数已在上面归零）
                if (opt.notifyMitigation && cfg.alerts_notifyRecovery && !startupSuppressed(rec, now)) {
                    pushMitigated(rec, now);
                }
                stateDirty = true;
            } else {
                // —— 同级别：刷新记录（数值随时间更新），冷却到期仍活跃则重推 ——
                boolean changed = !sameAlert(rec.alert, alert);
                // 9C-4：稳定告警不再周期性重推。判据是"数值变化幅度"而不是"等够 300 秒"——
                // 一条长期 92% 的告警每 5 分钟重说一遍"系统CPU负载偏高: 92%"没有任何新信息；
                // 反之从 92% 跳到 99% 是用户想知道的。阈值取 alerts.repush-delta。
                //
                // ⚠️ 基准必须是「**上次推送时**的值」，不是「上一个 1Hz 样本」。
                // 最初实现把基准写成 rec.numericValue 并在每周期无条件更新，导致比较的是
                // 「相邻两秒的差」——在 σ≈12 的机器上，相邻样本差 ≥8 的概率是 13.6%，
                // 300 秒内几乎必然发生一次，于是这层抑制**完全失效**（仍是每 5 分钟一条）。
                // 反过来，数值在几小时里从 88% 平滑爬到 100% 时，每步只变化 0.003，
                // 永远够不到 delta —— 真正该通知的"持续恶化"被静默。
                // 改为与"上次推送值"比较后，两个方向同时正确。
                Double prevPushed = rec.lastPushedValue;
                Double curVal = numericValueOf(alert);
                // 变化是否"显著"。
                //
                // ⚠️ 这里踩过两个坑，都值得记下来：
                //   坑 1（最初实现）：基准用 rec.numericValue，且每周期无条件更新 → 比的是"相邻两秒的差"。
                //        在 σ≈12 的机器上 P(|Δ|≥8) = 13.6%，300 秒内必然触发 → 抑制完全失效。
                //   坑 2（改成"上次推送值"后）：随机游走里单点与旧基准的差**随时间单调增大**，
                //        300 秒后几乎必然超阈值 → 抑制仍然失效（模拟：σ=12 时 59 条/6h）。
                //
                // 正确的判据是"**窗口均值**相对上次推送值的变化"：均值的标准误是 σ/√n，
                // 噪声被平均掉，而真实的系统性变化（88%→100%）会完整保留在均值里。
                boolean significant;
                if (prevPushed == null || curVal == null) {
                    significant = true;   // 解析不出数值：不抑制（宁可多说一句，也不能静音）
                } else {
                    Double smoothed = movingAverage(key, curVal);
                    double ref = smoothed == null ? curVal : smoothed;
                    double absDelta = Math.abs(ref - prevPushed);
                    double relDelta = prevPushed == 0 ? (ref == 0 ? 0 : 1.0)
                        : Math.abs(ref - prevPushed) / Math.abs(prevPushed);
                    significant = absDelta >= repushDelta || relDelta >= 0.5;
                }
                // 9C-4 的适用范围限定：只有"服主无从处置"的系统级指标才抑制重推。
                // 依据——cpu:system 的分母是整机 CPU，包含 JVM 之外的一切占用；服主看到
                // "系统CPU负载偏高: 92%" 第三次、第五次、第十次，回答都是同一个"哦，我知道"。
                // 而内存/实体/区块/TPS 这些是服主能动手的指标，重推是"问题还在"的有效提醒，
                // 抑制它们会让 alert02b 这类正向断言失效，也会让真实问题被静默。
                boolean repushSuppressible = REPUSH_SUPPRESSIBLE_KEYS.contains(key);
                boolean shouldRepush = !repushSuppressible || significant;
                rec.alert = alert;
                rec.lastSeen = now;
                rec.value = valueOf(alert);
                rec.numericValue = curVal;
                if (changed) {
                    stateDirty = true;
                }
                if (shouldRepush && !isCooling(key, now, cooldownMs)) {
                    cooldowns.put(key, now);
                    // 9C-4：只有真正要推送时才推进"上次推送值"这个基准。
                    rec.lastPushedValue = curVal;
                    if (startupSuppressed(rec, now)) {
                        noteStartupSuppressed(rec);   // 9B-6
                    } else {
                        pushAlert(rec, now);
                    }
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
                    plugin.getLogger().info(logText("alert.log.module-down",
                        module, opt.unknownAfterMinutes, key));
                }
                continue;
            }
            if (module == null) {
                plugin.getLogger().log(Level.FINE, logText("alert.log.recovery-no-module", key));
            }
            moduleDownSince.remove(key);
            int clean = recoveryCounts.merge(key, 1, Integer::sum);
            // 9C-4：稳定抖动中的告警需要更长的干净期才宣布恢复。
            //
            // ⚠️ 这里最初写成「连续 60 个干净**周期**」，在真实数据上是一条死路：
            // 实测 30 分钟 1791 个 1Hz 样本，跌破退出线的最长连续干净段只有 **24 个周期**，
            // 扫遍 86–98 全部阈值，最长也才 23–42 —— 永远数不到 60。
            // 更致命的是：被抖动窗口挡下时 recoveryCounts 会被清零重来，
            // 于是"数到 60"这件事在噪声背景下**结构性地不可达**，告警会永久挂在 active。
            //
            // 改为**按时间**判定：干净持续时间达到 flapWindow 即可宣布恢复。
            // 这样判据与"阈值的呼吸周期"同尺度，而不是与采样频率绑定——
            // 采样间隔改了（用户可配 update_interval），周期数判据的含义也会跟着漂移，时间判据不会。
            boolean flapping = flapCounts.getOrDefault(key, 0) >= FLAP_ESCALATE_THRESHOLD;
            boolean cleanEnough;
            if (flapping) {
                long cleanFor = now - cleanRunStartMs.computeIfAbsent(key, k -> now);
                cleanEnough = cleanFor >= flapWindowMs;
            } else {
                cleanEnough = clean >= recoveryCycles;
            }
            if (!cleanEnough) {
                continue; // 9A-14：恢复防抖——连续 N 个干净周期（或抖动中的 M 毫秒）才宣布恢复
            }
            // 9C-4：抖动抑制——若该 key 刚刚才恢复过一次（在 flap 窗口内），这次"干净"极可能只是
            // 阈值下方的一次回落。此时宣布恢复会造成「恢复→复燃→恢复→复燃」的锯齿。
            // 做法：不推送恢复、不移出 active（问题在观测上仍活跃），把恢复计数清零重新累计；
            // 若窗口过后确实一直干净，下一个 recoveryCycles 周期会正常宣布恢复——"已恢复"只会迟到，不会丢失。
            ResolvedInfo lastResolved = lastResolvedAt.get(key);
            if (lastResolved != null && flapWindowMs > 0 && now - lastResolved.atMs < flapWindowMs) {
                recoveryCounts.remove(key);
                noteFlap(key, now);
                plugin.getLogger().log(Level.FINE, logText("alert.log.flap-suppressed",
                    key, now - lastResolved.atMs, flapWindowMs));
                continue;
            }
            flapSuppressed.remove(key);
            recordEvent("resolve", rec);
            // 9C-4：宣布恢复。但抖动计数**保留**——由步骤 5b 的痊愈计时负责清零。
            // 在这里清零会让"每 20 分钟振荡一次"的告警每次恢复后都回到 3 秒判据，永远压不住。
            // 9B-6：窗口内连 TPS 的"已恢复正常"也不推——用户从未收到过那条告警，突然收到"已恢复"只会困惑
            if (cfg.alerts_notifyRecovery && !startupSuppressed(rec, now)) {
                pushRecovery(rec, now, clean);
            }
            resolvedKeys.add(key);
            stateDirty = true;
        }
        // 真恢复：移出 active，记复燃窗口，冷却记录刻意保留
        for (String key : resolvedKeys) {
            ActiveAlert rec = activeAlerts.remove(key);
            recoveryCounts.remove(key);
            moduleDownSince.remove(key);
            flapSuppressed.remove(key);
            // 9C-4：flapCounts 刻意**不**在这里清零——由步骤 3b 的痊愈计时负责。
            // 在这里清零会让"每 20 分钟振荡一次"的告警每次恢复后都回到 3 秒判据，永远压不住。
            // 冷却记录同样**保留**：原先 cooldowns.remove(key) 让"复燃遵守最短间隔"
            // 永远拿不到参照点（isCooling 恒 false），冷却表在这条路径上被架空。
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

        // 5b. 9C-4：抖动计数的"痊愈"计时。
        //     一条告警进入稳定抖动后，恢复判据变成"连续干净 flapWindowMs 毫秒"；
        //     若它此后连续干净超过 FLAP_HEAL_CYCLES 个周期，说明抖动真的平息了，才把计数清零。
        //     关键：本周期仍在越界的 key 直接清零计时（它没有在痊愈）；本周期缺席的才累加。
        if (!flapCounts.isEmpty()) {
            for (Map.Entry<String, Integer> e : flapCounts.entrySet()) {
                String key = e.getKey();
                if (current.containsKey(key)) {
                    flapHealCycles.remove(key);           // 又越界了：痊愈计时重来
                } else {
                    int healed = flapHealCycles.merge(key, 1, Integer::sum);
                    if (healed >= FLAP_HEAL_CYCLES) {
                        flapCounts.remove(key);
                        flapHealCycles.remove(key);
                        plugin.getLogger().log(Level.FINE, logText("alert.log.flap-healed", key, healed));
                    }
                }
            }
        }

        // 6. 复燃窗口过期清理（避免 lastResolvedAt 无限增长）
        //    9C-4：保留期必须覆盖"抖动窗口"——否则用户把 flap-window-seconds 调到大于冷却秒数时，
        //    窗口内的恢复记录会被提前清掉，抖动抑制静默失效（配置标着 600 秒，实际只生效 300 秒）。
        if (!lastResolvedAt.isEmpty()) {
            long retainMs = Math.max(Math.max(cooldownMs, flapWindowMs), 60_000L);
            lastResolvedAt.entrySet().removeIf(e -> now - e.getValue().atMs > retainMs);
        }

        // 7. 9A-14：状态变化才写盘（不是每周期）
        if (stateDirty) {
            persistState();
        }

        // 8. 9B-6：启动抑制窗口结束汇总（只打一条；不补发任何告警）
        maybeLogStartupSuppressEnd();
    }

    /** 两条 Alert 是否"同一个观测"（数值与级别都没变）——用于减少无谓的状态落盘。 */
    /**
     * 两次观测是否"同一次"——用于决定是否需要落盘（{@code stateDirty}）。
     *
     * <p><b>9D 修正</b>：旧实现比较 {@code message().equals()}，而 9D 起 {@code message()}
     * 是<b>按当前语言现渲染</b>的派生值。用字符串当身份有三个问题（红队 HIGH-1）：</p>
     * <ol>
     *   <li>数值变化若不改变渲染精度，就检测不到 → {@code alerts.state} 停止更新 value/lastSeen；</li>
     *   <li>渲染精度（{@code %.1f}）把 92.11 与 92.14 抹成同一个身份；</li>
     *   <li>切语言会让所有活跃告警都判为"变了"，触发一轮无意义的状态重写。</li>
     * </ol>
     *
     * <p>改为比较<b>结构化字段</b>：级别、原始数值、阈值（阈值会随 reload 变化且会展示给用户，
     * 所以变化时应当落盘）。注意<b>不能</b>把数值放进告警 key——那会让"数值变了"变成
     * "换了一条告警"，重新引入"旧告警恢复 + 新告警开启"的双通知。</p>
     */
    private static boolean sameAlert(Alert a, Alert b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.level().equals(b.level())
            && sameDouble(a.value(), b.value())
            && sameDouble(a.threshold(), b.threshold())
            && sameDouble(a.exitThreshold(), b.exitThreshold());
    }

    /** NaN 视为相等（两者都未知 = 同一次观测），其余按 {@code Double.compare}。 */
    private static boolean sameDouble(double x, double y) {
        return (Double.isNaN(x) && Double.isNaN(y)) || Double.compare(x, y) == 0;
    }

    /** 9C-4：记录一次抖动抑制（达到阈值后该 key 的恢复判据自动加严）。 */
    private void noteFlap(String key, long now) {
        int n = flapCounts.merge(key, 1, Integer::sum);
        if (n == FLAP_ESCALATE_THRESHOLD) {
            plugin.getLogger().info(logText("alert.log.flap-escalate", key, flapWindowMs / 1000L, n));
        }
        flapSuppressed.add(key);
    }

    // ==================== 推送通道 ====================

    /**
     * 推送一条告警：控制台 + 游戏内 + webhook。
     * 9A-14：统一加「服名 + key + 值/阈值 + 持续时长 + 下一步」前缀（不改采集层文案）。
     */
    private void pushAlert(ActiveAlert rec, long now) {
        String level = levelText(rec.alert);
        String consoleText = Messages.get("alert.prefix.console", level, serverTag())
            + rec.alert.message() + fullDetail(rec, now);
        String gameText = Messages.get("alert.prefix.game", level, serverTag())
            + rec.alert.message() + shortDetail(rec, now);
        emit(consoleText, gameText, rec.alert.critical() ? NamedTextColor.RED : NamedTextColor.YELLOW,
            rec.alert.critical() ? Level.SEVERE : Level.WARNING, true);
    }

    /** 推送级别升级通知：{@code [告警][严重] <message>（级别升级）}。 */
    private void pushUpgrade(ActiveAlert rec, long now) {
        String tag = serverTag();
        String suffix = Messages.get("alert.msg.escalated");
        // 升级通知的级别位恒为"严重"（升级只发生在 warning→critical，见状态机）
        String consoleText = Messages.get("alert.prefix.console", levelText(rec.alert), tag)
            + rec.alert.message() + suffix + fullDetail(rec, now);
        String gameText = Messages.get("alert.prefix.game", levelText(rec.alert), tag)
            + rec.alert.message() + suffix + shortDetail(rec, now);
        emit(consoleText, gameText, NamedTextColor.RED, Level.SEVERE, true);
    }

    /**
     * 9A-14：推送「已缓解」——critical→warning，问题仍在但严重度下降。
     * 与「已恢复」严格区分：文案明确写"问题仍在，未恢复"。
     */
    private void pushMitigated(ActiveAlert rec, long now) {
        String tag = serverTag();
        String consoleText = Messages.get("alert.prefix.mitigated.console", levelText(rec.alert), tag)
            + rec.alert.message()
            + Messages.get("alert.msg.mitigated.console") + fullDetail(rec, now);
        String gameText = Messages.get("alert.prefix.mitigated.game", tag)
            + rec.alert.message()
            + Messages.get("alert.msg.mitigated.game") + shortDetail(rec, now);
        emit(consoleText, gameText, NamedTextColor.YELLOW, Level.WARNING, false);
    }

    /** 9A-14：复燃通知——恢复窗口内同一 key 再次越界，绕过防抖与冷却。 */
    private void pushRefire(ActiveAlert rec, long now) {
        String level = levelText(rec.alert);
        String tag = serverTag();
        String suffix = Messages.get("alert.msg.refire");
        String consoleText = Messages.get("alert.prefix.console", level, tag)
            + rec.alert.message() + suffix + fullDetail(rec, now);
        String gameText = Messages.get("alert.prefix.game", level, tag)
            + rec.alert.message() + suffix + shortDetail(rec, now);
        emit(consoleText, gameText, rec.alert.critical() ? NamedTextColor.RED : NamedTextColor.YELLOW,
            rec.alert.critical() ? Level.SEVERE : Level.WARNING, true);
    }

    /**
     * 推送恢复通知：控制台 info + 游戏内绿色。
     *
     * <p>文案 = 最后一次真实观测的分类名 + 本地化的"已恢复正常"。
     * 9D 之前后缀是硬编码字符串拼接（{@code display + " 已恢复正常"}），
     * 翻译正文后会出现"英文正文 + 中文后缀"的半吊子输出；现在后缀也走消息层。</p>
     */
    private void pushRecovery(ActiveAlert rec, long now, int cleanCycles) {
        String message = rec.alert.display() + " " + AlertCatalog.recoveredSuffix();
        String duration = DurationFormat.duration(now - rec.firstSeen);
        emit(Messages.get("alert.prefix.recovery.console", serverTag())
                + message + Messages.get("alert.msg.recovery.console", rec.key, duration, cleanCycles),
            Messages.get("alert.prefix.recovery.game")
                + message + Messages.get("alert.msg.recovery.game", duration),
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
        sb.append(Messages.get("alert.prefix.digest",
                Messages.get(anyCritical ? "alert.level.critical" : "alert.level.warning"), serverTag()))
          .append(Messages.get("alert.msg.digest-header", batch.size()));
        int shown = 0;
        int total = batch.size();
        outer:
        for (Map.Entry<String, List<ActiveAlert>> e : byModule.entrySet()) {
            for (ActiveAlert r : e.getValue()) {
                if (shown >= 5) {
                    break outer;
                }
                sb.append('\n').append(r.value == null || r.value.isEmpty()
                    ? Messages.get("alert.msg.digest-line", e.getKey(), r.alert.message())
                    : Messages.get("alert.msg.digest-line-value", e.getKey(), r.alert.message(), r.value));
                shown++;
            }
        }
        if (total > shown) {
            // f-s01-logic-04：模板 "…and {0} more" 问的是**未展示**条数，不是本周期总数。
            // 传 total 时，新增 6 条 / 已展示 5 条会打出 "and 6 more"（实际只剩 1 条）。
            sb.append('\n').append(Messages.get("alert.msg.digest-more", total - shown));
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
                plugin.getLogger().warning(logText("alert.log.console-failed", e.getMessage()));
            }
        }
        if (cfg.alerts_in_game) {
            try {
                // f-s01-err-08：逐玩家异常已在方法内隔离，这里只接"整批不可用"（如 getOnlinePlayers 抛出）。
                gameOk = broadcastToAlertPlayers(Component.text(gameText, color));
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning(logText("alert.log.game-failed", e.getMessage()));
            }
        }
        WebhookNotifier wn = webhookNotifier;
        if (wn != null) {
            try {
                wn.send(consoleText);
                webhookQueued = wn.isReady();
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning(logText("alert.log.webhook-failed", e.getMessage()));
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
                plugin.getLogger().warning(logText("alert.log.console-failed", e.getMessage()));
            }
        }
        if (cfg.alerts_in_game) {
            try {
                // f-s01-err-08：同 emit，逐玩家边界在 broadcastToAlertPlayers 内部。
                gameOk = broadcastToAlertPlayers(Component.text(text, color));
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning(logText("alert.log.game-failed", e.getMessage()));
            }
        }
        WebhookNotifier wn = webhookNotifier;
        if (wn != null) {
            try {
                wn.send(text);
                webhookQueued = wn.isReady();
            } catch (Exception | LinkageError e) {
                plugin.getLogger().warning(logText("alert.log.webhook-failed", e.getMessage()));
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
        sb.append(Messages.get("alert.msg.key", rec.key));
        if (rec.value != null && !rec.value.isEmpty()) {
            sb.append(Messages.get("alert.msg.value", rec.value));
        }
        String th = thresholdText(rec.key);
        if (!th.isEmpty()) {
            sb.append(Messages.get("alert.msg.threshold", th));
        }
        // 9C-4：迟滞带内的告警，其显示值可能低于阈值本身（例如阈值 90、迟滞 5 时，
        // 显示"84.3%"却仍在告警——因为退出线是 85）。不说明会自相矛盾（红队 D7）。
        String latchNote = hysteresisNote(rec.key);
        if (!latchNote.isEmpty()) {
            sb.append(' ').append(latchNote);
        }
        sb.append(Messages.get("alert.msg.duration", DurationFormat.duration(now - rec.firstSeen)))
          .append(Messages.get("alert.msg.next-step", nextStep(metricOf(rec.key))));
        return sb.toString();
    }

    /**
     * 9C-4：迟滞提示（仅 CPU 两个指标）。
     *
     * <p>迟滞让"退出线"低于"进入线"，因此告警消息里的数值可能低于它自己写着的阈值。
     * 不解释的话，用户看到「系统CPU负载偏高: 84.3% | 阈值(系统 > 90.0%)」会认为是 bug
     * （红队 D7 指出的自相矛盾）。这里补一句退出线，让"为什么 84% 还在告警"当场有答案。</p>
     *
     * @return 空串表示该 key 不适用（非 CPU 指标 / 迟滞关闭）
     */
    private String hysteresisNote(String key) {
        ConfigManager.ConfigData c = configManager.get();
        if (c == null) {
            return "";
        }
        double margin = c.cpu_hysteresisMargin;
        if (!(margin > 0) || margin > 50) {
            return "";
        }
        if ("cpu:system".equals(key)) {
            return Messages.get("alert.msg.hysteresis",
                fmtThreshold(c.cpu_systemWarningThreshold - margin));
        }
        if ("cpu:process".equals(key)) {
            return Messages.get("alert.msg.hysteresis",
                fmtThreshold(c.cpu_processWarningThreshold - margin));
        }
        return "";
    }

    /** 阈值展示格式化：去掉无意义的尾随 .0（90.0 → 90，87.5 → 87.5）。 */
    private static String fmtThreshold(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** 9C-4：记录一个观测值，用于滚动估计采样噪声 σ̂。 */
    private void observeValue(String key, Double value) {
        if (value == null || !Double.isFinite(value)) {
            return;
        }
        Deque<Double> dq = recentValues.computeIfAbsent(key, k -> new ArrayDeque<>(NOISE_WINDOW));
        dq.addLast(value);
        while (dq.size() > NOISE_WINDOW) {
            dq.removeFirst();
        }
    }

    /**
     * 9C-4：把单个观测压进滑动窗口并返回其均值（样本不足 10 个时返回 null，调用方退化为单点比较）。
     *
     * <p>为什么必须用均值而不是单点：随机游走下"当前值 − 上次推送值"的期望随 √Δt 增长，
     * 任何固定阈值最终都会被噪声穿过（σ=12 时 300 秒后几乎必然）；
     * 而均值把噪声压到 σ/√n，同时**完整保留系统性漂移**。
     * 这是 9C-4 里唯一能同时满足"噪声大的机器不刷屏"和"缓慢恶化仍会通知"的判据。</p>
     */
    private Double movingAverage(String key, Double value) {
        observeValue(key, value);
        Deque<Double> dq = recentValues.get(key);
        if (dq == null || dq.size() < 10) {
            return null;
        }
        double sum = 0;
        for (double v : dq) {
            sum += v;
        }
        return sum / dq.size();
    }

    /** 9A-14：游戏内短细节（聊天栏宽度有限，只留服名/持续/下一步）。 */
    private String shortDetail(ActiveAlert rec, long now) {
        return Messages.get("alert.msg.duration", DurationFormat.duration(now - rec.firstSeen))
            + Messages.get("alert.msg.next-step", nextStep(metricOf(rec.key)));
    }

    /**
     * 向所有在线且持有 perfscope.alert 权限的玩家发送消息。
     * 必须运行在主线程（本服务定时器经 SchedulerAdapter.runGlobalTimer 调度，天然满足）。
     *
     * <p><b>f-s01-err-08</b>：原实现 for 内无 try，任一 sendMessage 抛出即跳过其后全部玩家。
     * 现逐玩家隔离，失败数与首个失败者（含玩家名）汇总为一条 WARNING。</p>
     *
     * @return {@code false} 仅当"有人需要收但全员失败"；无人需要收时返回 {@code true}，
     *         以保持调用方 {@code gameOk}="通道可用"的既有计数语义不变。
     */
    private boolean broadcastToAlertPlayers(Component component) {
        int failed = 0;
        boolean anyDelivered = false;
        String firstFailure = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(PERMISSION_ALERT)) {
                continue;
            }
            try {
                player.sendMessage(component);
                anyDelivered = true;
            } catch (Exception | LinkageError e) {
                failed++;
                if (firstFailure == null) {
                    firstFailure = player.getName() + ": " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage());
                }
            }
        }
        if (failed > 0) {
            plugin.getLogger().warning(logText("alert.log.game-partial", failed, firstFailure));
        }
        return anyDelivered || failed == 0;
    }

    // ==================== 9A-14：文案辅助 ====================

    /** 级别展示词（{@code 严重}/{@code 警告} → {@code Critical}/{@code Warning}）。令牌本身不翻译。 */
    private static String levelText(Alert alert) {
        return Messages.get(alert.critical() ? "alert.level.critical" : "alert.level.warning");
    }

    /**
     * 控制台日志：文案 + 统一的 {@code [告警] } / {@code [Alert] } 前缀。
     *
     * <p>前缀由 {@code alert.log.prefix} 提供，调用点只写内容——这样"前缀长什么样"
     * 只有一处定义，不会出现某条日志漏前缀、或两种语言下前缀不一致。</p>
     */
    private static String logText(String key, Object... args) {
        return Messages.get("alert.log.prefix") + Messages.get(key, args);
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
    /**
     * 9D：取告警值的<b>展示串</b>——由结构化数值 + 指标单位直接拼，<b>不再解析文案</b>。
     *
     * <p>旧实现切最后一个冒号取子串，本机实测在三种文案形态下会静默取错
     * （见 {@link Metric} 类注释）。现在数值来自 {@code alert.value()}，
     * 与文案里写了什么都无关，切语言也不会改变它。</p>
     */
    private static String valueOf(Alert alert) {
        if (alert == null) {
            return "";
        }
        Metric m = alert.metric();
        if (m != null && alert.hasValue()) {
            return AlertCatalog.formatValue(alert.value(), m.unit());
        }
        // 无法结构化（历史数据缺值/未知指标）：返回空串，调用方按"未知"处理
        return "";
    }

    /**
     * 9C-4：把 {@link #valueOf} 的结果解析成数值。
     *
     * <p>9D 起这是<b>直读</b>而不是解析：值本来就以 double 形式存在于 {@link Alert#value()}。
     * 返回 {@code null} 仅表示"这条观测没有结构化数值"（历史数据），
     * 调用方据此退化为"不抑制重推"——安全的 fail-open 方向。</p>
     */
    private static Double numericValueOf(Alert alert) {
        if (alert == null || !alert.hasValue()) {
            return null;
        }
        double v = alert.value();
        return Double.isFinite(v) ? v : null;
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
                    return Messages.get("alert.threshold.tps",
                        c.tps_warningThreshold, c.tps_criticalThreshold);
                case "mspt":
                    return Messages.get("alert.threshold.mspt",
                        c.mspt_warningThreshold, c.mspt_criticalThreshold);
                case "players":
                    return Messages.get("alert.threshold.players",
                        c.players_warningThreshold, c.players_criticalThreshold);
                case "entities":
                    return key.startsWith("entities:world:")
                        ? Messages.get("alert.threshold.entities.world")
                        : Messages.get("alert.threshold.entities",
                            c.entities_totalWarningThreshold, c.entities_totalCriticalThreshold);
                case "chunks":
                    return Messages.get("alert.threshold.chunks",
                        c.chunks_loadedWarningThreshold, c.chunks_loadedCriticalThreshold);
                case "memory":
                    return Messages.get("alert.threshold.memory",
                        c.memory_heapWarningThreshold, c.memory_heapCriticalThreshold);
                case "cpu":
                    return Messages.get("alert.threshold.cpu",
                        c.cpu_processWarningThreshold, c.cpu_systemWarningThreshold);
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
            case "mspt":
            case "players":
            case "entities":
            case "chunks":
            case "memory":
            case "cpu":
                return Messages.get("alert.next-step." + metric);
            default:
                return Messages.get("alert.next-step.default");
        }
    }

    /**
     * 9A-14：服名（motd 首行去色码，截断 16 字符）；取不到退化为端口号。
     *
     * <p><b>f-s01-err-07（catch 口径）</b>：两处原为 {@code catch (Throwable)}，把 Error 一并当作
     * "motd 不可用"静默降级，且降级值在本方法末尾被<em>永久缓存</em>到 {@code serverTag} 字段——
     * 错误从此不可见。实测该路径可达的 {@code Error} 只有 LinkageError 一族
     * （{@code NoSuchMethodError} 等非 Paper 混合端缺方法、{@code NoClassDefFoundError}、
     * {@code ExceptionInInitializerError}，三者均 extends LinkageError），其余
     * （OOM / StackOverflowError / AssertionError / ThreadDeath）是 JVM 级故障，
     * 按本项目既定口径（{@code MetricsCollector} 9A-20 #4、
     * {@code DiagnosticEngine} f-s03-err-04）<b>故意不吞</b>，让其逃到任务边界由服务端记日志。
     * 故收窄为与全文件其余 catch 一致的 {@code Exception | LinkageError}。</p>
     */
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
        } catch (Exception | LinkageError ignored) {
            // motd 不可用：下沉到端口号
        }
        if (tag == null) {
            try {
                tag = ":" + Bukkit.getServer().getPort();
            } catch (Exception | LinkageError ignored) {
                // 端口也取不到：最后用实现名
            }
        }
        if (tag == null) {
            tag = Bukkit.getName();
        }
        serverTag = tag;
        return tag;
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
        /**
         * 9C-4：上一次推送时的<b>数值</b>（从 message 末尾解析 ，解析不出为 null）。
         * 用于"稳定告警不重推"判据——数值变化小于 alerts.repush-delta 时视为没有新信息。
         * 不做持久化：重启后首次重推是合理的（用户确实很久没收到了）。
         */
        Double numericValue;

        /**
         * 9C-4：<b>上次真正推送</b>时的数值——{@link #numericValue} 无法替代它。
         *
         * <p>区别很关键：{@code numericValue} 每周期都更新（记录"最近一次观测"），
         * 而本字段只在 {@code pushAlert} 真正发生时更新。抑制判据必须用后者：
         * 若用前者，"相邻两秒的差"在 σ≈12 的机器上有 13.6% 概率 ≥8，
         * 300 秒内几乎必然触发一次重推——抑制形同虚设；
         * 同时"几小时内从 88% 平滑爬到 100%"这种真正该通知的恶化因为每步变化太小而永不触发。</p>
         */
        Double lastPushedValue;

        ActiveAlert(String key, Alert alert, long firstSeen, long lastSeen, boolean restored) {
            this.key = key;
            this.alert = alert;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.restored = restored;
            this.value = valueOf(alert);
            this.module = moduleOf(alert);
            this.numericValue = numericValueOf(alert);
            // 9C-4：新建记录即视为"这条观测即将被推送"，故基准与当前值一致。
            // open 路径会立刻推送；被 startupSuppressed 吞掉的推送不更新基准也无妨
            // （下一周期 significant 仍为 false，与"已推过"的效果一致）。
            this.lastPushedValue = this.numericValue;
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
                plugin.getLogger().warning(logText("alert.log.state-dir-failed"));
                return;
            }
            StringBuilder sb = new StringBuilder(256);
            sb.append(Messages.get("alert.state.header")).append('\n');
            // 9D：显式版本头。以前只有列数可用来区分格式，降级到旧版会把 v2 文件
            // 按 v1 解析（display 变成世界名、module 变成数字），然后立刻用旧格式覆写，
            // 结构化数据永久丢失（红队 HIGH-3）。
            sb.append("# ").append(STATE_HEADER_V2).append('\n');
            // 列名行是**格式契约**，不随语言变：解析端按列序读，人不读它
            sb.append("# key|level|firstSeen|lastSeen|value|scope|threshold|exitThreshold|module|state\n");
            sb.append(Messages.get("alert.state.legend")).append('\n');
            for (ActiveAlert r : activeAlerts.values()) {
                appendStateLine(sb, r, "active");
            }
            for (ActiveAlert r : suspendedAlerts.values()) {
                appendStateLine(sb, r, "unknown");
            }
            payload = sb.toString();
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning(logText("alert.log.state-serialize-failed", e.getMessage()));
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
            // 9D：首次以 v2 覆写前，保留一份旧格式原件（红队 HIGH-3：
            // 旧版读到 v2 文件会按 v1 解析并把结构化字段永久覆盖掉）。
            backupLegacyStateOnce(f);
            File tmp = new File(f.getParentFile(), "alerts.state.tmp");
            Files.write(tmp.toPath(), payload.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning(logText("alert.log.state-write-failed", e.getMessage()));
        }
    }

    /** 9D：状态文件格式版本标记（出现在第 2 行注释里）。 */
    private static final String STATE_HEADER_V2 = "perfscope-alert-state v=2";

    /** 9D：v1 文件的列数上限（8 列；6-8 都算合法 v1）。 */
    private static final int STATE_V1_MAX_COLUMNS = 8;

    /** 9D：是否已经为本次进程做过"首写前备份 v1 文件"。 */
    private boolean stateBackedUp = false;

    /**
     * 9D：本次进程开始时发现状态文件是旧格式（无 v2 头），首次写入前必须先备份。
     *
     * <p>与 {@link #stateFormatVersion} 分开，是因为"要不要备份"取决于**文件当前长什么样**，
     * 而"用什么格式写"取决于**我们即将写什么**。混成一个变量正是验收 HIGH 的成因。</p>
     */
    private boolean pendingLegacyBackup = false;

    /** 9D：判断状态文件是否带 v2 版本头（只需读前几行）。 */
    private boolean hasV2Header(File f) {
        try (java.io.BufferedReader r = java.nio.file.Files.newBufferedReader(
                f.toPath(), StandardCharsets.UTF_8)) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 8) {
                if (line.contains("perfscope-alert-state v=2")) {
                    return true;
                }
            }
            return false;
        } catch (Exception | LinkageError e) {
            // 读不了就当作旧格式：宁可多备份一次，不能丢数据
            return false;
        }
    }

    /** 9D：解析出的格式版本（loadState 时确定；缺头 = v1）。 */
    private int stateFormatVersion = 1;

    /**
     * 9D：首次以 v2 覆写前，把已存在的 v1 状态文件复制为 {@code alerts.state.v1.bak-<时间戳>}。
     *
     * <p>为什么必须备份：旧版本（≤1.2.1）读 v2 文件时只按列数猜测格式，会把
     * {@code scope} 当 {@code display}、把 {@code threshold} 当 {@code module}，
     * 然后立刻用旧格式覆写整个文件——结构化数据就此永久消失。有了这份备份，
     * 用户降级后升级回来仍能还原。</p>
     *
     * <p>只在{@code stateFormatVersion < 2}（即本次是迁移）且旧文件存在时执行一次；
     * 每进程只做一次，避免反复堆积备份。</p>
     */
    private void backupLegacyStateOnce(File f) {
        if (stateBackedUp || !pendingLegacyBackup || !f.isFile()) {
            return;
        }
        stateBackedUp = true;
        pendingLegacyBackup = false;
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
            .format(new java.util.Date());
        File bak = new File(f.getParentFile(), "alerts.state.v1.bak-" + stamp);
        try {
            java.nio.file.Files.copy(f.toPath(), bak.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info(logText("alert.log.state-migrated", bak.getName()));
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning(logText("alert.log.state-backup-failed", e.getMessage()));
        }
    }

    /** 9A-14：写一行状态记录（active/unknown 共用）。 */
    private static void appendStateLine(StringBuilder sb, ActiveAlert r, String state) {
        sb.append(escape(r.key)).append('|')
          .append(escape(r.alert.level())).append('|')
          .append(r.firstSeen).append('|')
          .append(r.lastSeen).append('|')
          // 9D v2：存**数值**而非渲染串。旧格式把中文 display 存盘，
          // 切语言后历史记录会一直显示旧语言，而且是恢复文案的锚点来源。
          .append(fmtNum(r.alert.value())).append('|')
          .append(escape(r.alert.scope() == null ? "" : r.alert.scope())).append('|')
          .append(fmtNum(r.alert.threshold())).append('|')
          .append(fmtNum(r.alert.exitThreshold())).append('|')
          .append(escape(r.module == null ? "" : r.module)).append('|')
          .append(state).append('\n');
    }

    /** 9D：数值落盘格式——NaN 写成空串（避免 "-1" 与真实负值混淆）。 */
    private static String fmtNum(double v) {
        if (Double.isNaN(v) || !Double.isFinite(v)) {
            return "";
        }
        // 用 ROOT：状态文件的字节表示不应随操作系统区域设置变化
        return String.format(java.util.Locale.ROOT, "%.6f", v);
    }

    /** 9D：读回数值（空串/非法 → NaN）。 */
    private static double parseNum(String s) {
        if (s == null || s.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** 9A-14：启动 rehydrate（每进程一次；reload 不重读，避免覆盖 reload 期间新的状态）。 */
    private void loadState() {
        if (stateLoaded) {
            return;
        }
        stateLoaded = true;
        // 9D 修（验收 HIGH）：先把"需要备份"这件事定下来，再读文件。
        //
        // 原实现的漏洞：stateFormatVersion 只在读到 **v2 版本头**时才变成 2，
        // 而 v1 文件没有头 → 它永远停在 1 → 判定"这是迁移" → 应该备份。
        // 听起来对，但真正危险的是**顺序**：AlertService.start() 第一行就是 stop()，
        // 而 stop() 会调 persistState()——那时 loadState() 还没跑完（甚至还没开始），
        // stateFormatVersion 仍是初始值，backupLegacyStateOnce 里 `!f.isFile()` 或
        // 竞态条件会让备份被跳过，然后 v2 内容直接覆写 v1 原件，用户的历史告警永久丢失。
        //
        // 现在改为：进入 loadState 时若文件存在且**没有 v2 头**，立刻记下
        // "这是旧格式、必须先备份"（pendingLegacyBackup=true），与读取过程解耦。
        File f = stateFile();
        if (!f.isFile()) {
            stateFormatVersion = 2;   // 全新安装：没有旧文件，不需要备份
            return;
        }
        pendingLegacyBackup = !hasV2Header(f);
        if (!pendingLegacyBackup) {
            stateFormatVersion = 2;
        }
        int restored = 0;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (s.startsWith("#")) {
                    // 9D：读版本头。没有头的文件按 v1 处理（兼容既有部署）。
                    if (s.contains("perfscope-alert-state v=")) {
                        int eq = s.indexOf("v=");
                        try {
                            stateFormatVersion = Integer.parseInt(s.substring(eq + 2).trim());
                        } catch (NumberFormatException ignored) {
                            // 头损坏：保守当作 v1（只读不毁）
                            stateFormatVersion = 1;
                        }
                    }
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
                if (key.isEmpty()) {
                    continue;
                }
                // 9D：两种格式共存读取。
                //   v1（旧）：key|level|firstSeen|lastSeen|value(文本)|display|module|state   —— 8 列
                //   v2（新）：key|level|firstSeen|lastSeen|value(数值)|scope|threshold|exit|module|state —— 10 列
                //
                // 9D 修（验收）：判定依据改为**版本头**（stateFormatVersion），列数只作兜底。
                // 原先只数 `p.length >= 10`——一个 v1 行若某个字段含未转义的 '|'（旧版本
                // 手改、或第三方工具改写），就会被当成 v2 解析：scope 取到旧 display、
                // threshold 取到旧 module，然后立刻用 v2 格式覆写，结构化数据错位且不可逆。
                // 版本头是显式的、由本插件自己写的，不存在这种歧义。
                boolean v2 = stateFormatVersion >= 2;
                // f-s01-logic-05：v2 行需要 10 列，但入口守卫只保证 6 列（v1 的最小宽度）。
                // 一条 6–9 列的 v2 行会在下方 p[9] 抛 ArrayIndexOutOfBoundsException，
                // 而该异常被包住整个 for 的 catch 吞掉 → 其后所有行都不再恢复。
                // 格式不符的行直接跳过（保留 v1 的宽松口径：v1 分支逐字段自带守卫）。
                if (v2 && p.length < 10) {
                    continue;
                }
                double value;
                String scope;
                double threshold;
                double exitThreshold;
                String module;
                String state;
                if (v2) {
                    value = parseNum(p[4]);
                    scope = unescape(p[5]);
                    threshold = parseNum(p[6]);
                    exitThreshold = parseNum(p[7]);
                    module = unescape(p[8]);
                    state = unescape(p[9]).trim();
                } else {
                    // 旧格式：数值藏在一段中文文本里（"92.1%"），不再解析文案——
                    // 置 NaN，于是该条告警的"数值比较"降级为 fail-open（不抑制重推）。
                    value = Double.NaN;
                    scope = Metric.scopeOf(key);
                    threshold = Double.NaN;
                    exitThreshold = Double.NaN;
                    module = p.length >= 7 ? unescape(p[6]) : "";
                    state = p.length >= 8 ? unescape(p[7]).trim() : "active";
                }
                if (state.isEmpty()) {
                    state = "active";
                }
                // 9D：用结构化字段重建 Alert。不再有 display 列，文案一律按当前语言现渲染——
                // 这正是"切换语言后历史告警也跟着变语言"的实现方式。
                Alert alert = new Alert(key, level, Metric.ofKey(key),
                    module.isEmpty() ? metricOf(key) : module, lastSeen,
                    value, Double.NaN, scope, threshold, exitThreshold);
                ActiveAlert rec = new ActiveAlert(key, alert, firstSeen <= 0 ? lastSeen : firstSeen, lastSeen, true);
                rec.value = valueOf(alert);
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
                plugin.getLogger().info(logText("alert.log.state-restored", restored));
            }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning(logText("alert.log.state-read-failed", e.getMessage()));
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
    /**
     * 告警历史事件（内存镜像与 {@code alerts.jsonl} 的共同载体）。
     *
     * <p><b>9D：字段名是外部契约，不能改。</b>{@code ts/type/key/level/value/display}
     * 在 v1.2.1 就写进了用户磁盘上的 {@code alerts.jsonl}，下游可能已在解析。</p>
     *
     * <p>但 {@code display} 是**渲染后的自然语言串**——写进文件的那一刻就冻结了语言，
     * 之后切语言不会变，历史里于是中英混排。<b>修法不是改字段名，而是让读取端
     * 优先用 {@code key + level} 重新渲染</b>（见 {@link #parseEventLine}），
     * {@code display} 只作兜底：它仍然会被写出去（保持契约），只是不再被优先信任。</p>
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
        List<AlertEvent> out = new ArrayList<>(all.size() - from);
        for (int i = from; i < all.size(); i++) {
            out.add(localizeEvent(all.get(i)));
        }
        return List.copyOf(out);
    }

    /**
     * 9D：把事件里的 {@code display} 换成**当前语言**的渲染结果。
     *
     * <p>为什么要在读取时做：{@link #recentEvents} 是内存镜像，里面的对象是在
     * 各自被记录的那一刻创建的（可能还是上一种语言）。不在这里重渲染的话，
     * 用户切换语言后仍会看到旧语言的历史条目——正是"全有或全无"要消灭的混排。</p>
     */
    private static AlertEvent localizeEvent(AlertEvent ev) {
        String localized = renderDisplayForHistory(ev.key(), ev.level());
        return localized == null || localized.equals(ev.display())
            ? ev
            : new AlertEvent(ev.ts(), ev.type(), ev.key(), ev.level(), ev.value(), localized);
    }

    /**
     * 9A-14：记录一条历史事件（内存 + jsonl 追加）。
     * 内存镜像写第一个、IO 在后——即便首次播种失败，本进程新增的事件也一定能在 {@link #recent(int)} 里看到。
     */
    private void recordEvent(String type, ActiveAlert rec) {
        AlertEvent ev = new AlertEvent(System.currentTimeMillis(), type, rec.key,
            rec.alert.level(), rec.value == null ? "" : rec.value, rec.alert.display());
        // v1.4：Prometheus 导出需要累计计数。放在这里而不是另起监听——
        // recordEvent 是全部 9 种生命周期事件的唯一汇聚点，从这里计数不会漏。
        if (type != null) {
            alertEventCounters.computeIfAbsent(type, k -> new java.util.concurrent.atomic.LongAdder())
                .increment();
        }
        synchronized (this) {
            recentEvents.addLast(ev);
            while (recentEvents.size() > MAX_RECENT_EVENTS) {
                recentEvents.removeFirst();
            }
        }
        appendEventLine(ev);
    }

    /**
     * 各生命周期事件的累计发生次数（v1.4 Prometheus 导出用）。
     *
     * <p><b>为什么不复用 {@link #recentEvents}</b>：那是 500 条环形镜像，容量满了会丢最旧的，
     * 拿它算计数会得出"发生次数恒 ≤ 500"的错误结论。<b>为什么不解析 {@code alerts.jsonl}</b>：
     * 那是磁盘 IO，且依赖文件可写。这里用 {@code LongAdder} 进程内累计，重启归零——
     * Prometheus 的 counter 语义允许重置，{@code rate()} 会自动处理。</p>
     *
     * <p>基数有界：事件类型是代码里写死的 9 个字面量，不会被用户输入放大。</p>
     *
     * @return 事件类型 → 累计次数（不可变副本；无事件时返回空 map）
     */
    public java.util.Map<String, Long> alertEventCounters() {
        if (alertEventCounters.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        alertEventCounters.forEach((k, v) -> out.put(k, v.sum()));
        return java.util.Map.copyOf(out);
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
            plugin.getLogger().warning(logText("alert.log.events-write-failed", e.getMessage()));
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
                // 9D 修（验收）：跳过本进程**已经**记在内存里的事件。
                // recordEvent 是先 addLast 再 appendEventLine，所以本进程写的事件同时存在于
                // 内存与磁盘；若播种时原样读回，/alerts 会把每条事件显示两遍。
                // 判据用 (ts,type,key) 三元组：同一毫秒内同 key 同类型重复在现实中不会发生，
                // 而只比 ts 会因为秒级批量事件误伤。
                java.util.Set<String> inMemory = new java.util.HashSet<>();
                for (AlertEvent e : recentEvents) {
                    inMemory.add(e.ts() + "|" + e.type() + "|" + e.key());
                }
                // 播种插到已有（本进程新增的）事件之前，保持"旧→新"；不覆盖运行时事件
                for (int i = seeded.size() - 1; i >= 0; i--) {
                    AlertEvent e = seeded.get(i);
                    if (inMemory.contains(e.ts() + "|" + e.type() + "|" + e.key())) {
                        continue;
                    }
                    recentEvents.addFirst(e);
                }
                while (recentEvents.size() > MAX_RECENT_EVENTS) {
                    recentEvents.removeFirst();
                }
            }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning(logText("alert.log.events-read-failed", e.getMessage()));
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
        String level = jsonField(line, "level");
        String value = jsonField(line, "value");
        String stored = jsonField(line, "display");

        // 9D：display 不再被优先信任——用 key+level 按**当前语言**重新渲染，
        // 这样切换到另一种语言后，历史条目也跟着变，不再出现中英混排。
        // 只有在无法重新渲染（key 不认识 / 已下线指标）时才退回文件中冻结的旧串。
        String display = renderDisplayForHistory(key, level);
        if (display == null) {
            display = stored;
        }
        return new AlertEvent(ts, type, key, level, value, display);
    }

    /**
     * 用 {@code key}+{@code level} 重建历史事件的分类名（当前语言）。
     *
     * @return 重建失败（未知指标 / 指标已下线）时返回 {@code null}，调用方退回存储值
     */
    private static String renderDisplayForHistory(String key, String level) {
        try {
            Metric metric = Metric.ofKey(key);
            if (metric == null) {
                return null;
            }
            Alert probe = new Alert(key, level, metric, null, 0L,
                Double.NaN, Double.NaN, Metric.scopeOf(key), Double.NaN, Double.NaN);
            String title = AlertCatalog.title(probe);
            // title() 在键缺失时退回 "metric level" 这种内部 id —— 那不是给用户看的，
            // 此时宁可显示文件里冻结的原文
            return title.contains(metric.id()) && title.contains(level) ? null : title;
        } catch (Exception | LinkageError e) {
            return null;
        }
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
