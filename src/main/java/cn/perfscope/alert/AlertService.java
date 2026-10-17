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
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 告警服务（Wave 7B/7C：结构化 Alert 消费 + 告警收敛）— 消费快照中的结构化告警并推送到控制台与在线玩家。
 *
 * <p>职责范围（Wave 7B/7C 收敛版）：</p>
 * <ul>
 *   <li>输入：定时读取 {@link MetricsSnapshot#alerts}（结构化 {@link Alert}，采集模块已生成），
 *       本类不重复做阈值判定；key 直接取 {@link Alert#key()}（metric:scope，唯一身份），
 *       替代旧版按文案前缀字符串解析的启发式 key；</li>
 *   <li>防抖（Wave 7C）：同一 key 连续 N 个检查周期（alerts.debounce-cycles，默认 3，钳 ≥1）出现才首次推送；
 *       计数在状态机内维护、reload 不清（与 activeAlerts 同语义）；N=1 等价旧行为。
 *       代价：首次推送最长延迟约 N × 采集间隔（README 边界段已声明）；</li>
 *   <li>一事件一通知（Wave 7C）：级别升级（warning→critical，同 key）只推一条升级消息
 *       {@code [告警][严重] <message>（级别升级）}（游戏内同理加后缀），不再产生"旧 key 恢复 + 新 key 告警"两条；
 *       升级不受冷却限制（严重度上升应立即到达），推送后刷新冷却表，防抖计数视为已确认（不重新计数）。
 *       降级（critical→warning）暂视为状态保持（不推送、不恢复，保留原记录）——记入 v1.3 Notes；</li>
 *   <li>去重/冷却：同一 key 在冷却期（alerts.cooldown-seconds，默认 300 秒）内只推一次；
 *       冷却到期仍在活跃则作为重复提醒再推；冷却表 reload 不清（设计行为，测试报告 §3-2 已登记）；</li>
 *   <li>恢复：已确认（曾推送过）的 key 上周期存在、本周期消失时发一条通知
 *       （alerts.notify-recovery 可关，不受冷却限制）；文案 = 最近一条告警的 {@link Alert#display()} + " 已恢复正常"
 *       （级别升级后跟随新级别文案的 display）。抑制条件按<b>模块归因</b>——直接用 {@link Alert#module()}
 *       对快照 unavailable 集合判定：仅当该模块出现在 unavailable（reload/启动首周期 JVM 窗口、模块被配置禁用
 *       或采集失败）时才抑制该 key 的恢复判定（"消息消失"不等于"故障恢复"），抑制期间该 key 保留在
 *       activeAlerts 中，下个周期继续判定；可用模块的真恢复不受其他模块 unavailable 影响；
 *       module 为 null（未携带归因）按正常恢复处理（宁可发真恢复，不永久静默），并记 FINE 级日志备查；</li>
 *   <li>通道：① 控制台（critical=severe、warning=warning、升级=severe、恢复=info，前缀 [告警]）；
 *       ② 在线且持有 perfscope.alert 权限的玩家（Paper 原生 Adventure，warning 黄 / critical 与升级 红 / 恢复 绿）。</li>
 * </ul>
 *
 * <p>线程模型：定时器经 {@link SchedulerAdapter#runGlobalTimer} 调度，在主线程（Folia 下为全局区域线程）
 * 周期执行，玩家消息发送天然处于安全线程；三张状态表（cooldowns/activeAlerts/debounceCounts）用
 * ConcurrentHashMap，全部为内存操作，不阻塞主线程。</p>
 *
 * <p>v1.3 Notes（暂不做）：降级仅状态保持、不推送也不恢复；防抖计数按"连续出现"语义，
 * 待确认 key 的任何缺席（含模块不可用期）都会清零重计。</p>
 */
public class AlertService {

    /** 接收游戏内告警的权限节点（plugin.yml 已定义，不可改名） */
    private static final String PERMISSION_ALERT = "perfscope.alert";

    private final PerfScope plugin;
    private final ConfigManager configManager;

    /** 冷却表：告警 key → 上次推送时间（epoch ms）。定时器单线程访问，ConcurrentHashMap 为防御性选择。 */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    /** 已确认（已推送过）的告警：key → 最近一条 Alert（恢复判定与恢复文案 display 的来源；reload 时不清空） */
    private final Map<String, Alert> activeAlerts = new ConcurrentHashMap<>();

    /**
     * 防抖计数表（Wave 7C）：待确认告警 key → 连续出现的检查周期数。
     * 达到 alerts.debounce-cycles 才首次推送并移入 activeAlerts；reload 不清（与 activeAlerts 同语义）。
     */
    private final Map<String, Integer> debounceCounts = new ConcurrentHashMap<>();

    /** 周期检查任务句柄（null = 未运行） */
    private SchedulerAdapter.TaskHandle timerHandle;

    /** Wave 7D：webhook 出口（由 PerfScope 装配；null 或未就绪时零开销跳过） */
    private WebhookNotifier webhookNotifier;

    /** Wave 7D：当日推送计数（供每日摘要"推送 W 条"；跨天自动归零，reload 不清） */
    private volatile int pushedToday = 0;
    private volatile long pushedTodayDayEpoch = currentDayEpoch();

    public AlertService(PerfScope plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }

    /** Wave 7D：装配 webhook 出口（可空；null = 不发送）。 */
    public void setWebhookNotifier(WebhookNotifier webhookNotifier) {
        this.webhookNotifier = webhookNotifier;
    }

    /** Wave 7D：当前活跃（已确认未恢复）告警数——每日摘要第 3 行使用。 */
    public int getActiveCount() {
        return activeAlerts.size();
    }

    /**
     * Wave 7D：当日成功推送的告警条数（含新告警、重复提醒、级别升级；不含恢复）。
     * 简单日计数器：访问时若已跨天则先归零（近似实现即可，摘要只需量级）。
     */
    public int getPushedTodayCount() {
        long today = currentDayEpoch();
        if (today != pushedTodayDayEpoch) {
            pushedTodayDayEpoch = today;
            pushedToday = 0;
        }
        return pushedToday;
    }

    private static long currentDayEpoch() {
        return java.time.LocalDate.now().toEpochDay();
    }

    /** Wave 7D：推送成功后自增当日计数（跨天由 getPushedTodayCount 访问时归零）。 */
    private void countPushed() {
        long today = currentDayEpoch();
        if (today != pushedTodayDayEpoch) {
            pushedTodayDayEpoch = today;
            pushedToday = 0;
        }
        pushedToday++;
    }

    /**
     * 启动告警服务：按 general.update_interval 在主线程周期检查最新快照。
     * 幂等（先停后启，不叠加任务）；alerts.enabled=false 时不启动。
     * reload 场景：重建定时器以应用新间隔，但不清空冷却/活跃/防抖状态（避免重载导致告警重推，按 V1.3 规格）。
     */
    public void start() {
        stop(); // 防呆：先停旧定时器，支持重复调用不叠加（与 MetricsCollector.start 同范式）
        ConfigManager.ConfigData cfg = configManager.get();
        if (!cfg.alerts_enabled) {
            plugin.getLogger().info("[告警] 告警服务未启用（alerts.enabled=false）");
            return;
        }
        long interval = Math.max(1, cfg.general_updateInterval);
        // 首查延迟一个采集周期：等采集器先产出首份快照；runGlobalTimer 在主线程执行，玩家消息发送天然安全
        timerHandle = SchedulerAdapter.runGlobalTimer(plugin, this::checkCycle, interval, interval);
        plugin.getLogger().info("[告警] 告警服务已启动 (检查间隔: " + interval + " ticks, 冷却: "
            + cfg.alerts_cooldownSeconds + " 秒, 防抖: " + Math.max(1, cfg.alerts_debounceCycles) + " 周期)");
    }

    /** 停止告警服务：取消定时器（幂等）。不清空冷却/活跃/防抖状态机——reload 时按 V1.3 规格保留。 */
    public void stop() {
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
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
     * 流程：提取本周期结构化告警（同 key 取更高级别）→ 防抖计数/升级判定/冷却判定后推送
     * → 对比上周期发恢复通知 → 更新状态机。
     * 预期由主线程串行调用；冷却判定为非原子 check-then-put（单主线程调用下无竞态）。
     */
    public void onSnapshot(MetricsSnapshot snap) {
        ConfigManager.ConfigData cfg = configManager.get();
        if (!cfg.alerts_enabled || snap == null) {
            return;
        }

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
        // 冷却防御：负值配置被 ConfigManager.getInt 截断/透传后钳到 0（"0 = 无冷却"，仍受采集周期节流，不刷屏）
        long cooldownMs = Math.max(0, cfg.alerts_cooldownSeconds) * 1000L;
        // 防抖防御：钳 ≥1（ConfigManager 已校验回退默认 3，此处兜底）；N=1 等价旧行为（首次出现即推）
        int debounceCycles = Math.max(1, cfg.alerts_debounceCycles);

        // 2. 活跃告警收敛推送（Wave 7C）：
        //    a. 未确认 key（不在 activeAlerts）→ 防抖计数，连续 N 个检查周期出现才首次推送；
        //    b. 已确认 key → 级别升级：只推一条"（级别升级）"消息（不受冷却限制，推后刷新冷却表）；
        //       降级：状态保持（不推送、不恢复，保留原记录）；同级别：刷新记录，冷却到期仍活跃则重推。
        for (Map.Entry<String, Alert> entry : current.entrySet()) {
            String key = entry.getKey();
            Alert alert = entry.getValue();
            Alert confirmed = activeAlerts.get(key);

            if (confirmed == null) {
                // —— 待确认：防抖计数（连续性由末尾的清理步骤保证：本周期缺席即清零） ——
                int count = debounceCounts.merge(key, 1, Integer::sum);
                if (count < debounceCycles) {
                    continue; // 未达 N 周期：静默累计，暂不推送（防秒级抖动误报）
                }
                if (isCooling(key, now, cooldownMs)) {
                    // 防抖已达但仍在冷却期（上一生命周期遗留的冷却条目）：等冷却到期再推
                    // （与旧版"冷却到期仍活跃重推"语义一致，避免绕过冷却刷屏）
                    continue;
                }
                cooldowns.put(key, now); // 先落冷却再推送：推送异常时也不会在下一周期立刻重试刷屏
                try {
                    pushAlert(alert);
                } catch (Exception | LinkageError e) {
                    // 单条推送失败只记日志，不中断本轮其余告警与恢复检测（失败只记日志原则）
                    plugin.getLogger().warning("[告警] 推送失败: " + alert.message() + ": " + e.getMessage());
                }
                activeAlerts.put(key, alert); // 确认：移入活跃状态机（此后参与恢复判定与升级/重推收敛）
                debounceCounts.remove(key);   // 已确认：不再计数
                continue;
            }

            if (alert.critical() && !confirmed.critical()) {
                // —— 级别升级（warning→critical）：一事件一通知，只推一条升级消息 ——
                cooldowns.put(key, now); // 升级不受冷却限制（严重度上升应立即到达），推送后刷新冷却表
                try {
                    pushUpgrade(alert);
                } catch (Exception | LinkageError e) {
                    plugin.getLogger().warning("[告警] 升级推送失败: " + alert.message() + ": " + e.getMessage());
                }
                // 升级即更新记录：display 跟随新级别文案（如 "堆内存使用率严重偏高"），之后恢复用新 display
                activeAlerts.put(key, alert);
            } else if (!alert.critical() && confirmed.critical()) {
                // —— 降级（critical→warning）：v1.3 Notes——暂视为状态保持（不推送、不恢复，保留原记录） ——
                // 占位注释：故意留空
            } else {
                // —— 同级别：刷新记录（message 数值随时间更新），冷却到期仍活跃则重推（重复提醒） ——
                activeAlerts.put(key, alert);
                if (!isCooling(key, now, cooldownMs)) {
                    cooldowns.put(key, now);
                    try {
                        pushAlert(alert);
                    } catch (Exception | LinkageError e) {
                        plugin.getLogger().warning("[告警] 推送失败: " + alert.message() + ": " + e.getMessage());
                    }
                }
            }
        }

        // 3. 恢复检测：已确认（曾推送）的 key 上周期存在、本周期消失（每个 key 每次恢复只发一条，不受冷却限制）
        //    假恢复防护（A2/Wave4 修正语义保留，Wave 7B 改经由结构化 module 字段直接归因）：模块被配置禁用/
        //    采集失败/Reload 窗口时，该模块的告警在本周期"静默缺席"，直接做集合差分会把"缺席"误判为"恢复"。
        //    对每个消失 key 用 alert.module() 判定：
        //    - 该模块仍在 snap.unavailable 中 → 抑制本 key 恢复（保留在 activeAlerts 中不清除，下周期继续判定）；
        //    - 其他模块不可用不影响本 key（修复旧实现"任一模块不可用则全部模块恢复永久静默"的缺陷）；
        //    - module 为 null → 按正常恢复处理（宁可发真恢复，不永久静默），记 FINE 日志备查。
        //    注：恢复推送本身不写冷却表（cooldowns 仅在活跃告警推送时写入）；活跃告警的推送冷却
        //    仍会压制其复现推送，属预期行为。
        List<String> recoveredKeys = new ArrayList<>();
        for (Map.Entry<String, Alert> entry : activeAlerts.entrySet()) {
            String key = entry.getKey();
            if (current.containsKey(key)) {
                continue; // 本周期仍活跃：保留
            }
            String module = entry.getValue().module();
            if (module != null && snap.unavailable.contains(module)) {
                continue; // 该 key 所属模块本周期不可用：抑制恢复，保留 key 待下周期模块可用后再判定
            }
            if (module == null) {
                plugin.getLogger().log(Level.FINE,
                    "[告警] 恢复判定：key=" + key + " 未携带模块归因（按正常恢复处理）");
            }
            if (cfg.alerts_notifyRecovery) {
                try {
                    pushRecovery(entry.getValue());
                } catch (Exception | LinkageError e) {
                    plugin.getLogger().warning("[告警] 恢复通知发送失败: " + key + ": " + e.getMessage());
                }
            }
            recoveredKeys.add(key); // 真恢复（或通知被关闭）：登记待移除
        }
        // 真恢复（或通知被关闭）的 key 统一从状态机移除：先登记、循环外移除，遍历与修改分离（语义直白、天然安全）
        for (String key : recoveredKeys) {
            activeAlerts.remove(key);
        }

        // 4. 防抖计数清理：本周期缺席的待确认 key 视为"连续性中断"，计数清零（已确认 key 的计数在确认时即被移除，
        //    本步骤只影响待确认集合）；缺席且已确认的 key 已在上方走恢复流程（含抑制保留路径）
        debounceCounts.keySet().removeIf(key -> !current.containsKey(key));
    }

    // ==================== 推送通道 ====================

    /** 推送一条告警：控制台 + 游戏内（各通道受 alerts.* 开关控制，每次推送读取最新配置） */
    private void pushAlert(Alert alert) {
        ConfigManager.ConfigData cfg = configManager.get();
        // a. 控制台：critical 用 severe，warning 用 warning，前缀 [告警]
        if (cfg.alerts_console) {
            if (alert.critical()) {
                plugin.getLogger().severe("[告警][严重] " + alert.message());
            } else {
                plugin.getLogger().warning("[告警][警告] " + alert.message());
            }
        }
        // b. 游戏内：Paper 原生 Adventure，warning 黄 / critical 红
        if (cfg.alerts_in_game) {
            Component component = Component.text(
                "[告警·" + (alert.critical() ? "严重" : "警告") + "] " + alert.message(),
                alert.critical() ? NamedTextColor.RED : NamedTextColor.YELLOW);
            broadcastToAlertPlayers(component);
        }
        // c. Wave 7D：当日计数 + webhook（全异步；未就绪时零开销）
        countPushed();
        if (webhookNotifier != null) {
            webhookNotifier.send("[告警][" + (alert.critical() ? "严重" : "警告") + "] " + alert.message());
        }
    }

    /**
     * 推送级别升级通知（Wave 7C 锁定格式）：{@code [告警][严重] <message>（级别升级）}；
     * 游戏内同理加后缀"（级别升级）"。升级只发生于 warning→critical，故控制台固定 severe、游戏内固定红。
     */
    private void pushUpgrade(Alert alert) {
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg.alerts_console) {
            plugin.getLogger().severe("[告警][严重] " + alert.message() + "（级别升级）");
        }
        if (cfg.alerts_in_game) {
            broadcastToAlertPlayers(Component.text(
                "[告警·严重] " + alert.message() + "（级别升级）", NamedTextColor.RED));
        }
        // Wave 7D：当日计数 + webhook
        countPushed();
        if (webhookNotifier != null) {
            webhookNotifier.send("[告警][严重] " + alert.message() + "（级别升级）");
        }
    }

    /** 推送恢复通知：控制台 info + 游戏内绿色；文案 = 最近一条告警的 display + " 已恢复正常" */
    private void pushRecovery(Alert alert) {
        ConfigManager.ConfigData cfg = configManager.get();
        String message = alert.display() + " 已恢复正常";
        if (cfg.alerts_console) {
            plugin.getLogger().info("[告警][恢复] " + message);
        }
        if (cfg.alerts_in_game) {
            broadcastToAlertPlayers(Component.text("[告警·恢复] " + message, NamedTextColor.GREEN));
        }
        // Wave 7D：webhook（恢复不计入推送计数——计数语义为"告警条数"）
        if (webhookNotifier != null) {
            webhookNotifier.send("[告警][恢复] " + message);
        }
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

    // ==================== 去重 / 冷却 ====================

    /** 判断某 key 是否仍在冷却期内；冷却 <=0 视为无冷却（天然受采集周期节流，不会刷屏） */
    private boolean isCooling(String key, long now, long cooldownMs) {
        if (cooldownMs <= 0) {
            return false;
        }
        Long lastPush = cooldowns.get(key);
        return lastPush != null && now - lastPush < cooldownMs;
    }
}
