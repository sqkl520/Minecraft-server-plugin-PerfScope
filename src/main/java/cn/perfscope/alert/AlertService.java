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
 * 告警服务（P0-4 基础版）— 消费快照中的告警文案并推送到控制台与在线玩家。
 *
 * <p>职责范围（v1.1 止血基础版；刻意不做：webhook / QQ / 日报 / 连续 N 次防抖，属于 v1.3「告警中心」）：</p>
 * <ul>
 *   <li>输入：定时读取 {@link MetricsSnapshot#activeWarnings} / {@link MetricsSnapshot#activeCriticals}
 *       （各采集模块已生成的告警文案），本类不重复做阈值判定；</li>
 *   <li>去重/冷却：以"告警类别前缀"为 key（见 {@link #keyOf}），同一 key 在冷却期
 *       （alerts.cooldown-seconds，默认 300 秒）内只推一次；冷却到期仍在活跃则作为重复提醒再推；</li>
 *   <li>恢复：上一周期存在、本周期消失的 key 视为恢复，发一条通知
 *       （alerts.notify-recovery 可关，不受冷却限制）；抑制条件按<b>模块归因</b>——每个 key 经
 *       {@link #moduleFor} 映射到对应 {@code MetricsSnapshot.MODULE_*} 常量，仅当该模块出现在快照
 *       unavailable 集合（reload/启动首周期 JVM 窗口、模块被配置禁用或采集失败）时才抑制该 key 的恢复判定
 *       （"消息消失"不等于"故障恢复"），抑制期间该 key 保留在 activeAlerts 中，下个周期继续判定；
 *       可用模块的真恢复不受其他模块 unavailable 影响（含被配置禁用的模块——其长期占据 unavailable
 *       不再波及其他模块），正常发出；无法归因的 key（映射未命中）按正常恢复处理
 *       （宁可发真恢复，不永久静默），并记 FINE 级日志备查（详见 {@link #onSnapshot}）；</li>
 *   <li>通道：① 控制台（critical=severe、warning=warning、恢复=info，前缀 [告警]）；
 *       ② 在线且持有 perfscope.alert 权限的玩家（Paper 原生 Adventure，warning 黄 / critical 红 / 恢复 绿）。</li>
 * </ul>
 *
 * <p>线程模型：定时器经 {@link SchedulerAdapter#runGlobalTimer} 调度，在主线程（Folia 下为全局区域线程）
 * 周期执行，玩家消息发送天然处于安全线程；冷却表用 ConcurrentHashMap，全部为内存操作，不阻塞主线程。</p>
 *
 * <p>已知边界（v1.3 状态机修复）：无连续 N 次防抖，指标在阈值附近秒级抖动时可能产生交替的告警/恢复文案；
 * 级别升级（warning→critical）文案前缀含级别词，视为不同 key，会先发旧 key 恢复再发新 key 告警。</p>
 */
public class AlertService {

    /** 接收游戏内告警的权限节点（plugin.yml 已定义，不可改名） */
    private static final String PERMISSION_ALERT = "perfscope.alert";

    private final PerfScope plugin;
    private final ConfigManager configManager;

    /** 冷却表：告警 key → 上次推送时间（epoch ms）。定时器单线程访问，ConcurrentHashMap 为防御性选择。 */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    /** 基础状态机：上一周期仍活跃的告警 key → 记录（用于恢复检测，reload 时不清空） */
    private final Map<String, ActiveAlert> activeAlerts = new ConcurrentHashMap<>();

    /** 周期检查任务句柄（null = 未运行） */
    private SchedulerAdapter.TaskHandle timerHandle;

    public AlertService(PerfScope plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }

    /**
     * 启动告警服务：按 general.update_interval 在主线程周期检查最新快照。
     * 幂等（先停后启，不叠加任务）；alerts.enabled=false 时不启动。
     * reload 场景：重建定时器以应用新间隔，但不清空冷却/活跃状态（避免重载导致告警重推，按 V1.3 规格）。
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
            + cfg.alerts_cooldownSeconds + " 秒)");
    }

    /** 停止告警服务：取消定时器（幂等）。不清空冷却/活跃状态机——reload 时按 V1.3 规格保留。 */
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
     * 流程：提取本周期告警（同 key 取更高级别）→ 冷却判定后推送 → 对比上周期发恢复通知 → 更新状态机。
     * 预期由主线程串行调用；冷却判定为非原子 check-then-put（单主线程调用下无竞态）。
     */
    public void onSnapshot(MetricsSnapshot snap) {
        ConfigManager.ConfigData cfg = configManager.get();
        if (!cfg.alerts_enabled || snap == null) {
            return;
        }

        // 1. 提取本周期告警：warning 先入、critical 后入，同 key 以更高级别覆盖
        Map<String, ActiveAlert> current = new LinkedHashMap<>();
        for (String message : snap.activeWarnings) {
            current.put(keyOf(message), new ActiveAlert(message, false));
        }
        for (String message : snap.activeCriticals) {
            current.put(keyOf(message), new ActiveAlert(message, true));
        }

        long now = System.currentTimeMillis();
        // 冷却防御：负值配置被 ConfigManager.getInt 截断/透传后钳到 0（"0 = 无冷却"，仍受采集周期节流，不刷屏）
        long cooldownMs = Math.max(0, cfg.alerts_cooldownSeconds) * 1000L;

        // 2. 活跃告警推送：新告警或冷却到期的重复提醒（同一 key 冷却期内只推一次）
        for (Map.Entry<String, ActiveAlert> entry : current.entrySet()) {
            String key = entry.getKey();
            if (isCooling(key, now, cooldownMs)) {
                continue;
            }
            cooldowns.put(key, now); // 先落冷却再推送：推送异常时也不会在下一周期立刻重试刷屏
            try {
                pushAlert(entry.getValue());
            } catch (Exception | LinkageError e) {
                // 单条推送失败只记日志，不中断本轮其余告警与恢复检测（失败只记日志原则）
                plugin.getLogger().warning("[告警] 推送失败: " + entry.getValue().message() + ": " + e.getMessage());
            }
        }

        // 3. 恢复检测：上周期存在、本周期消失的 key（每个 key 每次恢复只发一条，不受冷却限制）
        //    P2-1 假恢复修复（A2，Wave4 修正：按模块归因）：模块被配置禁用/采集失败/Reload 窗口时，
        //    该模块的告警文案在本周期"静默缺席"，直接做集合差分会把"缺席"误判为"恢复"发出假恢复通知。
        //    现对每个消失 key 经 moduleFor 归因到所属模块：
        //    - 该模块仍在 snap.unavailable 中 → 抑制本 key 恢复（保留在 activeAlerts 中不清除，下周期继续判定）；
        //    - 其他模块不可用不影响本 key —— 修复旧实现"任一模块不可用则全部模块恢复永久静默"的缺陷
        //      （被禁用模块会长期占据 unavailable，导致集合恒非空）；
        //    - 无法归因（moduleFor=null）→ 按正常恢复处理（宁可发真恢复，不永久静默），记 FINE 日志备查。
        //    注：恢复推送本身不写冷却表（cooldowns 仅在活跃告警推送时写入）；活跃告警的推送冷却
        //    仍会压制其复现推送，属预期行为。
        List<String> recoveredKeys = new ArrayList<>();
        for (Map.Entry<String, ActiveAlert> entry : activeAlerts.entrySet()) {
            if (current.containsKey(entry.getKey())) {
                continue; // 本周期仍活跃：保留（数值变化由下方 putAll 刷新为最新记录）
            }
            String mod = moduleFor(entry.getKey());
            if (mod != null && snap.unavailable.contains(mod)) {
                continue; // 该 key 所属模块本周期不可用：抑制恢复，保留 key 待下周期模块可用后再判定
            }
            if (mod == null) {
                plugin.getLogger().log(Level.FINE,
                    "[告警] 恢复判定：未归因 key=" + entry.getKey() + "（按正常恢复处理，可考虑补充 moduleFor 映射）");
            }
            if (cfg.alerts_notifyRecovery) {
                try {
                    pushRecovery(entry.getKey());
                } catch (Exception | LinkageError e) {
                    plugin.getLogger().warning("[告警] 恢复通知发送失败: " + entry.getKey() + ": " + e.getMessage());
                }
            }
            recoveredKeys.add(entry.getKey()); // 真恢复（或通知被关闭）：登记待移除
        }
        // 真恢复（或通知被关闭）的 key 统一从状态机移除：先登记、循环外移除，遍历与修改分离（语义直白、天然安全）
        for (String key : recoveredKeys) {
            activeAlerts.remove(key);
        }
        // 4. 更新状态机：合入本周期活跃告警（含冷却中未推送的，供下周期恢复判定）并刷新为最新记录；
        //    被抑制恢复的消失 key 已在循环内 continue 保留在表中
        activeAlerts.putAll(current);
    }

    // ==================== 推送通道 ====================

    /** 推送一条告警：控制台 + 游戏内（各通道受 alerts.* 开关控制，每次推送读取最新配置） */
    private void pushAlert(ActiveAlert alert) {
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
    }

    /** 推送恢复通知：控制台 info + 游戏内绿色 */
    private void pushRecovery(String key) {
        ConfigManager.ConfigData cfg = configManager.get();
        String message = key + " 已恢复正常";
        if (cfg.alerts_console) {
            plugin.getLogger().info("[告警][恢复] " + message);
        }
        if (cfg.alerts_in_game) {
            broadcastToAlertPlayers(Component.text("[告警·恢复] " + message, NamedTextColor.GREEN));
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

    /**
     * 提取告警的去重/冷却 key（告警类别前缀）。
     * 规则：取最后一个冒号（含中文全角）之前的部分并去空白。采集侧文案约定为"类别前缀: 数值"，
     * 数值部分不含冒号；用最后一个冒号而非第一个，是为了兼容世界名本身含冒号的极端情况
     * （如 "世界 w:x 实体数严重超标: 3000" 取到完整类别）。示例："TPS 严重偏低: 14.20" → "TPS 严重偏低"；
     * 无冒号时用全文。
     */
    private static String keyOf(String alertText) {
        String text = alertText == null ? "" : alertText.trim();
        int idx = Math.max(text.lastIndexOf(':'), text.lastIndexOf('：'));
        return idx > 0 ? text.substring(0, idx).trim() : text;
    }

    /**
     * T1（Wave4 修正）：按告警 key 前缀归因到所属采集模块（{@code MetricsSnapshot.MODULE_*} 常量）。
     *
     * <p>用于恢复判定的按模块抑制：仅当 key 所属模块出现在快照 unavailable 集合时才抑制其恢复，
     * 其他模块的不可用不再影响本 key（修复旧"全局抑制"缺陷——被禁用模块长期占据 unavailable 集合，
     * 导致全部模块恢复判定永久跳过）。映射依据 MetricsCollector 全部告警文案构造点逐条核对（16 条），
     * 用 startsWith 前缀匹配、先精确后宽泛排列。</p>
     *
     * <ul>
     *   <li>"世界 " 开头（逐世界实体/区块文案）——世界名任意，按文案固定部分的特征词分流：
     *       "加载区块数" → CHUNKS（区块文案独有词，实体文案不含，先判之），"实体" → ENTITIES
     *       （对应"实体数严重超标/较多"）；两特征词皆无的未知世界文案返回 null；</li>
     *   <li>"实体总数" → ENTITIES；"方块实体" → TILE_ENTITIES（先于宽泛"实体"判断，防御未来文案）；</li>
     *   <li>"堆内存"/"内存" → MEMORY；"在线玩家"/"玩家" → PLAYERS；</li>
     *   <li>"进程CPU"/"系统CPU"/"CPU" → CPU；"TPS" → TPS；"MSPT" → MSPT；</li>
     *   <li>"GC" → GC；"区块" → CHUNKS；"服务器"/"运行时间" → SERVER
     *       （后三条当前无对应文案，按映射规则防御性保留）。</li>
     * </ul>
     *
     * @param key 告警去重 key（见 {@link #keyOf}）
     * @return 对应 MODULE_* 常量；无法归因返回 null（调用方按正常恢复处理，不永久静默）
     */
    private static String moduleFor(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        // 逐世界文案（"世界 <名> 实体数严重超标/较多" / "世界 <名> 加载区块数严重超标/较多"）：
        // 世界名任意，不能按固定前缀匹配；用文案固定部分的强特征词分流——"加载区块数"是区块文案
        // 独有词（实体文案不含），先判之；再判"实体"。正常世界名不含这两个特征词，分流互斥
        if (key.startsWith("世界")) {
            if (key.contains("加载区块数")) {
                return MetricsSnapshot.MODULE_CHUNKS;
            }
            if (key.contains("实体")) {
                return MetricsSnapshot.MODULE_ENTITIES;
            }
            return null;
        }
        // 固定前缀，先长后短（防前缀冲突）
        if (key.startsWith("实体总数")) {
            return MetricsSnapshot.MODULE_ENTITIES;
        }
        if (key.startsWith("方块实体")) {
            return MetricsSnapshot.MODULE_TILE_ENTITIES;
        }
        if (key.startsWith("实体")) {
            return MetricsSnapshot.MODULE_ENTITIES; // 防御：未来"实体…"文案兜底（方块实体已先判，不受影响）
        }
        if (key.startsWith("堆内存")) {
            return MetricsSnapshot.MODULE_MEMORY;
        }
        if (key.startsWith("内存")) {
            return MetricsSnapshot.MODULE_MEMORY;
        }
        if (key.startsWith("在线玩家")) {
            return MetricsSnapshot.MODULE_PLAYERS;
        }
        if (key.startsWith("玩家")) {
            return MetricsSnapshot.MODULE_PLAYERS;
        }
        if (key.startsWith("进程CPU") || key.startsWith("系统CPU") || key.startsWith("CPU")) {
            return MetricsSnapshot.MODULE_CPU;
        }
        if (key.startsWith("TPS")) {
            return MetricsSnapshot.MODULE_TPS;
        }
        if (key.startsWith("MSPT")) {
            return MetricsSnapshot.MODULE_MSPT;
        }
        if (key.startsWith("GC")) {
            return MetricsSnapshot.MODULE_GC;
        }
        if (key.startsWith("区块")) {
            return MetricsSnapshot.MODULE_CHUNKS;
        }
        if (key.startsWith("服务器") || key.startsWith("运行时间")) {
            return MetricsSnapshot.MODULE_SERVER;
        }
        return null;
    }

    /** 判断某 key 是否仍在冷却期内；冷却 <=0 视为无冷却（天然受采集周期节流，不会刷屏） */
    private boolean isCooling(String key, long now, long cooldownMs) {
        if (cooldownMs <= 0) {
            return false;
        }
        Long lastPush = cooldowns.get(key);
        return lastPush != null && now - lastPush < cooldownMs;
    }

    /** 告警跟踪记录（基础版仅需原文与级别，供推送与恢复判定使用） */
    private record ActiveAlert(String message, boolean critical) {}
}
