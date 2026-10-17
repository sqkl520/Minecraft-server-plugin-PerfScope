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
import cn.perfscope.history.HistorySample;
import cn.perfscope.history.HistoryStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 每日摘要服务（Wave 7D）——每天固定时刻推送 3 行概况，实现"推送而非拉取"的每日触达。
 *
 * <p><b>数据来源</b>：{@link HistoryStore}（24h 环形缓冲）提供最低 TPS / 峰值实体 / 峰值内存；
 * {@link AlertService} 提供"当前活跃告警数"与"今日推送数"。零新依赖。</p>
 *
 * <p><b>补发语义（契约修订 2026-10-17）</b>：启动/启用/重载后首查时，若"今日配置时刻已过且今日未发"
 * → 立即补发一次。理由：服务端在配置时刻停机则当天永远收不到摘要；补发让"每天都到"的承诺成立，
 * 也让本功能可在任意时刻配置一个已过去的时刻来测试。</p>
 *
 * <p><b>每日去重</b>：键 = 本地日期（{@link LocalDate}），进程内存字段，reload 不清、跨天自动滚动。
 * 检查节拍：每分钟一次（{@link SchedulerAdapter#runGlobalTimer}，1200 ticks）。</p>
 *
 * <p>线程模型：定时器在主线程执行（Folia 下为全局区域线程），玩家消息发送与日志天然安全；
 * webhook 经 {@link WebhookNotifier#send} 全异步。</p>
 */
public class DailySummaryService {

    private static final String PERMISSION_ALERT = "perfscope.alert";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final PerfScope plugin;
    private final ConfigManager configManager;
    private final WebhookNotifier webhookNotifier;
    private final AlertService alertService;

    /** 周期检查任务句柄（null = 未运行） */
    private SchedulerAdapter.TaskHandle timerHandle;

    /** 当日已发日期（null = 从未发过；reload 不清，跨天自动滚动） */
    private LocalDate lastSentDate = null;

    public DailySummaryService(PerfScope plugin, WebhookNotifier webhookNotifier, AlertService alertService) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.webhookNotifier = webhookNotifier;
        this.alertService = alertService;
    }

    /**
     * 启动：每分钟检查一次（幂等，先停后启）。
     * enabled=false 时不启动（但保留 lastSentDate，重新启用后同日不重发）。
     */
    public void start() {
        stop();
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg == null || !cfg.alerts_dailySummaryEnabled) {
            plugin.getLogger().info("[告警] 每日摘要未启用（alerts.daily-summary.enabled=false）");
            return;
        }
        // 每分钟检查（1200 ticks = 60 秒）；首查延迟 5 秒（等采集器与历史环就绪）
        timerHandle = SchedulerAdapter.runGlobalTimer(plugin, this::checkCycle, 100L, 1200L);
        plugin.getLogger().info("[告警] 每日摘要已启用 (时刻: " + cfg.alerts_dailySummaryTime + ", 本地时区)");
    }

    /** 停止：取消定时器（幂等）。不清 lastSentDate——reload 后同日不重发。 */
    public void stop() {
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
    }

    // ==================== 周期入口 ====================

    private void checkCycle() {
        try {
            ConfigManager.ConfigData cfg = configManager.get();
            if (cfg == null || !cfg.alerts_dailySummaryEnabled) {
                return;
            }
            int targetMinutes = ConfigManager.parseHhMmMinutes(cfg.alerts_dailySummaryTime);
            if (targetMinutes < 0) {
                return; // 配置校验已回退默认；此处防御
            }
            LocalDate today = LocalDate.now();
            if (today.equals(lastSentDate)) {
                return; // 今日已发
            }
            int nowMinutes = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
            // 命中时刻 或 补发（今日时刻已过且今日未发）
            if (nowMinutes >= targetMinutes) {
                sendSummary(today);
            }
        } catch (Exception | LinkageError e) {
            // 与告警服务同款：任务边界吞异常记日志，绝不让调度器因异常中断后续轮次
            plugin.getLogger().warning("[告警] 每日摘要本轮检查失败: " + e.getMessage());
        }
    }

    // ==================== 摘要组装与推送 ====================

    /**
     * 组装并推送 3 行摘要（稳定锚点：首行 "[PerfScope] 每日摘要"、末行 "告警统计:"——
     * 行内措辞可打磨，锚点词不可改，harness 场景依赖）。
     */
    private void sendSummary(LocalDate today) {
        HistoryStore store = plugin.getHistoryStore();
        long sinceMs = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;

        HistorySample lowest = store == null ? null : store.lowestTpsSince(sinceMs);
        HistorySample peakEntities = store == null ? null : store.peakEntitiesSince(sinceMs);
        HistorySample peakHeap = store == null ? null : store.peakHeapSince(sinceMs);
        int coverageMinutes = 0;
        if (store != null && !store.samples().isEmpty()) {
            var samples = store.samples();
            long span = samples.get(samples.size() - 1).timestamp() - samples.get(0).timestamp();
            coverageMinutes = (int) Math.max(1, span / 60000L);
        }

        String line1 = "[PerfScope] 每日摘要 | 覆盖: 近 " + coverageMinutes + " 分钟";
        String line2 = "最低 TPS: " + (lowest == null ? "--" : formatTps(lowest.tps1m()) + "（" + formatTime(lowest.timestamp()) + "）")
            + " | 峰值实体: " + (peakEntities == null ? "--" : String.valueOf(peakEntities.totalEntities()))
            + " | 峰值内存: " + (peakHeap == null ? "--" : peakHeap.heapUsagePercent() + "%");
        int pushedToday = alertService == null ? 0 : alertService.getPushedTodayCount();
        int active = alertService == null ? 0 : alertService.getActiveCount();
        String line3 = "告警统计: 推送 " + pushedToday + " 条 / 当前活跃 " + active + " 条";

        // 控制台
        plugin.getLogger().info(line1);
        plugin.getLogger().info(line2);
        plugin.getLogger().info(line3);

        // 在线 OP（perfscope.alert）
        Component component = Component.text(line1 + "\n" + line2 + "\n" + line3, NamedTextColor.GREEN);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(PERMISSION_ALERT)) {
                player.sendMessage(component);
            }
        }

        // webhook（全异步）
        if (webhookNotifier != null && webhookNotifier.isReady()) {
            webhookNotifier.send(line1 + "\n" + line2 + "\n" + line3);
        }

        lastSentDate = today;
    }

    private static String formatTps(double tps) {
        return String.format(java.util.Locale.ROOT, "%.2f", tps);
    }

    private static String formatTime(long epochMs) {
        return java.time.Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(HH_MM);
    }
}
