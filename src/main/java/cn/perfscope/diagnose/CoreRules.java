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
package cn.perfscope.diagnose;

import cn.perfscope.config.ConfigManager;
import cn.perfscope.history.HistorySample;
import cn.perfscope.history.HistoryStore;
import cn.perfscope.metrics.MetricsSnapshot;

import java.util.List;

/**
 * 首批 5 条高置信诊断规则（Wave 8A；来源 V1.2 规则表 D-*，按锐评 B 论点 7 缩编）。
 *
 * <p>取舍原则（为什么是这 5 条）：高置信 = 触发条件蕴含足够证据、建议动作有明确依据、
 * 误报代价低。37 条全集的其余规则（TPS 三档联动/GC 频率/进程 CPU/磁盘/网络/磁盘/热点区块告警等）
 * 留 backlog——它们要么依赖尚未采集的数据（磁盘/网络字节），要么与既有告警/report 高度重叠。</p>
 *
 * <p>全部规则的"证据不足"路径严格：数据缺失或窗口覆盖不足时返回 null（不结论），
 * 而非用猜测填满输出——错误归因的信任成本不可修复（合成裁决红线）。</p>
 */
public final class CoreRules {

    private CoreRules() {}

    // ==================== DIAG-MEM-LEAK：疑似内存泄漏（启发式） ====================

    /** 泄漏启发式：窗口内堆占用持续抬升 + GC 有效活动 + 水位不回落。 */
    public static final class MemoryLeakRule implements DiagnosticRule {
        private static final long WINDOW_MINUTES = 30;
        private static final int MIN_HEAP_GROWTH_PERCENT = 8;   // 窗口内堆占用抬升 ≥8 个百分点
        private static final int MIN_SAMPLES = 10;              // 覆盖不足不结论

        @Override
        public String id() {
            return "DIAG-MEM-LEAK";
        }

        @Override
        public Diagnosis evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            // 前置：内存模块可用（不可用期的 0 无意义）且在内存"偏高"区间起（低水位泄漏无感，噪声大）
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
                return null;
            }
            if (snap.heapUsagePercent < cfg.memory_heapWarningThreshold) {
                return null;
            }
            List<HistorySample> window = history.since(System.currentTimeMillis() - WINDOW_MINUTES * 60_000L);
            if (window.size() < MIN_SAMPLES) {
                return null; // 证据不足：样本覆盖不够 30 分钟窗
            }
            HistorySample first = window.get(0);
            HistorySample last = window.get(window.size() - 1);
            if (first.heapUsagePercent() <= 0 || last.heapUsagePercent() <= 0) {
                return null;
            }
            // 条件 1：窗口内抬升 ≥8 个百分点
            int growth = last.heapUsagePercent() - first.heapUsagePercent();
            if (growth < MIN_HEAP_GROWTH_PERCENT) {
                return null;
            }
            // 条件 2：窗口后半段的谷值仍高于窗口起点（GC 后水位不回落 —— 泄漏 vs 缓存增长的判据）
            int minInLaterHalf = Integer.MAX_VALUE;
            for (int i = window.size() / 2; i < window.size(); i++) {
                int h = window.get(i).heapUsagePercent();
                if (h > 0) {
                    minInLaterHalf = Math.min(minInLaterHalf, h);
                }
            }
            if (minInLaterHalf < first.heapUsagePercent()) {
                return null; // 回落过 → 更像正常 GC 波动，不出结论
            }
            long minutes = Math.max(1, (last.timestamp() - first.timestamp()) / 60_000L);
            return new Diagnosis(id(), "疑似内存泄漏",
                "近 " + minutes + " 分钟堆占用从 " + first.heapUsagePercent() + "% 升至 "
                    + last.heapUsagePercent() + "%（+" + growth + " 个百分点），且 GC 后水位未回落到起点（当前 "
                    + snap.heapUsagePercent + "%）",
                "先留证再处置：/perfscope report 存档 → 用 jmap -dump:live,format=b,file=heap.hprof <pid> 取堆快照、"
                    + "Eclipse MAT 查看 dominator_tree 找最大持有者（常见：地图/缓存插件、异常堆积的 Item 实体）；"
                    + "重启前确认 -Xmx 是否偏小（当前 " + (snap.heapMax / 1024 / 1024) + "MB）",
                true);
        }
    }

    // ==================== DIAG-ENT-HOTSPOT：热点区块（下钻到坐标） ====================

    /** 热点区块：Top1 区块实体数显著高于世界阈值时给出坐标级结论。 */
    public static final class EntityHotspotRule implements DiagnosticRule {
        private static final int ABSOLUTE_FLOOR = 200; // 绝对下限：小服几十只动物不算"热点"

        @Override
        public String id() {
            return "DIAG-ENT-HOTSPOT";
        }

        @Override
        public Diagnosis evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_HOTSPOTS) || snap.topEntityChunks.isEmpty()) {
                return null; // 无热点数据（模块禁用/首采样未到）：不结论
            }
            // 世界阈值优先（worlds 覆盖），无覆盖用全局 critical 的一半作为关注线
            var top = snap.topEntityChunks.get(0);
            if (top.entityCount() < ABSOLUTE_FLOOR) {
                return null;
            }
            int threshold = cfg.entities_totalWarningThreshold;
            var ws = cfg.worldSettings == null ? null : cfg.worldSettings.get(top.world());
            if (ws != null) {
                threshold = ws.entities_totalWarningThreshold;
            }
            if (top.entityCount() < Math.max(ABSOLUTE_FLOOR, threshold / 2)) {
                return null;
            }
            return new Diagnosis(id(), "热点区块（单区块实体堆积）",
                top.world() + " 区块 [" + top.chunkX() + "," + top.chunkZ() + "]（方块 " + top.blockX() + ",~,"
                    + top.blockZ() + "）堆积 " + top.entityCount() + " 个实体，主导 " + top.dominantType()
                    + "（" + top.dominantPercent() + "%）",
                "传送到该坐标实地查看：/minecraft:tp @s " + top.blockX() + " ~ " + top.blockZ()
                    + "；若主导 ITEM 为掉落物堆积可 /minecraft:kill @e[type=item,x=" + top.blockX()
                    + ",dx=16,dz=16]（先备份）；若为刷怪装置失控，加装自动杀戮/上限或查 spawn-limits",
                top.entityCount() >= threshold);
        }
    }

    // ==================== DIAG-TPS-PERSIST：TPS 持续低位（趋势判定） ====================

    /** TPS 持续低位：区别于"此刻"告警——窗口均值低且持续，属结构性问题。 */
    public static final class TpsPersistentRule implements DiagnosticRule {
        private static final long WINDOW_MINUTES = 10;
        private static final int MIN_SAMPLES = 5;

        @Override
        public String id() {
            return "DIAG-TPS-PERSIST";
        }

        @Override
        public Diagnosis evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
                return null;
            }
            List<HistorySample> window = history.since(System.currentTimeMillis() - WINDOW_MINUTES * 60_000L);
            if (window.size() < MIN_SAMPLES) {
                return null;
            }
            double sum = 0;
            int count = 0;
            HistorySample lowest = null;
            for (HistorySample s : window) {
                if (s.tps1m() <= 0 || s.tps1m() > 20.5) {
                    continue; // 过滤哨兵与异常值
                }
                sum += s.tps1m();
                count++;
                if (lowest == null || s.tps1m() < lowest.tps1m()) {
                    lowest = s;
                }
            }
            if (count < MIN_SAMPLES || lowest == null) {
                return null;
            }
            double avg = sum / count;
            if (avg >= cfg.tps_warningThreshold) {
                return null; // 窗口均值已达标：非持续低位
            }
            long minutes = Math.max(1, (window.get(window.size() - 1).timestamp() - window.get(0).timestamp()) / 60_000L);
            return new Diagnosis(id(), "TPS 持续低位",
                "近 " + minutes + " 分钟均值 " + String.format(java.util.Locale.ROOT, "%.2f", avg)
                    + "（阈值 <" + cfg.tps_warningThreshold + "），最低 "
                    + String.format(java.util.Locale.ROOT, "%.2f", lowest.tps1m()) + "——非偶发抖动，属持续负载",
                "按顺序排查：① /perfscope hotspot 查实体堆积坐标；② /perfscope memory 看内存压力（内存泄漏拖慢 GC）；"
                    + "③ 对照 /perfscope history 的跌落起点与玩家上线/定时任务时间；④ 仍定位不了用 /spark profiler",
                avg < cfg.tps_criticalThreshold);
        }
    }

    // ==================== DIAG-PLAYER-CAP：玩家数持续接近上限 ====================

    /** 玩家容量：持续接近上限（含历史验证，区别于瞬时峰值）。 */
    public static final class PlayerCapacityRule implements DiagnosticRule {
        private static final long WINDOW_MINUTES = 15;
        private static final int MIN_SAMPLES = 5;

        @Override
        public String id() {
            return "DIAG-PLAYER-CAP";
        }

        @Override
        public Diagnosis evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS) || snap.maxPlayers <= 0) {
                return null;
            }
            int currentPercent = (int) Math.round(100.0 * snap.playerCount / snap.maxPlayers);
            if (currentPercent < cfg.players_warningThreshold) {
                return null;
            }
            // 历史佐证：窗口内低点也不低 → 持续接近上限（而非刚开活动）
            List<HistorySample> window = history.since(System.currentTimeMillis() - WINDOW_MINUTES * 60_000L);
            if (window.size() < MIN_SAMPLES) {
                return null; // 证据不足
            }
            int minPercent = Integer.MAX_VALUE;
            for (HistorySample s : window) {
                if (s.playerCount() > 0 && snap.maxPlayers > 0) {
                    minPercent = Math.min(minPercent, (int) Math.round(100.0 * s.playerCount() / snap.maxPlayers));
                }
            }
            if (minPercent == Integer.MAX_VALUE || minPercent < cfg.players_warningThreshold - 10) {
                return null; // 窗口内有明显低谷：属活动峰值而非容量问题
            }
            return new Diagnosis(id(), "在线人数持续接近上限",
                "当前 " + snap.playerCount + "/" + snap.maxPlayers + "（" + currentPercent + "%），近 "
                    + WINDOW_MINUTES + " 分钟低位也在 " + minPercent + "% 以上——新玩家可能进不来",
                "两条路：① 扩容 server.properties → max-players（注意每 +10 人约 +0.5–1G 内存）；"
                    + "② 挂机回收（AFK 踢出插件）释放槽位；如为活动峰段，提前在群公告排队规则",
                currentPercent >= cfg.players_criticalThreshold);
        }
    }

    // ==================== DIAG-MSPT-SPIKE：MSPT 尖刺（单 tick 冻结） ====================

    /** MSPT 尖刺：max 远超 avg（瞬时冻结），玩家体感"顿一下"但均值正常。 */
    public static final class MsptSpikeRule implements DiagnosticRule {
        private static final double RATIO = 3.0;      // max > avg×3
        private static final double MIN_SPIKE_MS = 100.0; // 且 max > 100ms（低于此玩家无感）

        @Override
        public String id() {
            return "DIAG-MSPT-SPIKE";
        }

        @Override
        public Diagnosis evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            // 需精确采集（min/max 相等 = 估算模式，无尖刺信息 → 不结论）
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)) {
                return null;
            }
            if (snap.msptAvg <= 0 || snap.msptMax <= 0) {
                return null;
            }
            if (snap.msptMax < MIN_SPIKE_MS || snap.msptMax < snap.msptAvg * RATIO) {
                return null;
            }
            return new Diagnosis(id(), "MSPT 尖刺（偶发单 tick 冻结）",
                "最大 " + snap.msptMax + "ms ≈ 平均 " + snap.msptAvg + "ms 的 "
                    + String.format(java.util.Locale.ROOT, "%.1f", snap.msptMax / snap.msptAvg)
                    + " 倍——均值掩盖了偶发卡顿，玩家体感为「顿一下」",
                "尖刺多来自区块保存或 GC：① paper.yml → max-auto-save-chunks-per-tick 降至 8；"
                    + "② 强调自动保存周期（bukkit.yml → autosave）；③ 定点抓取 /spark profiler --only-ticks-over 200",
                snap.msptMax > 1000.0);
        }
    }
}
