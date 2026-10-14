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
package cn.perfscope.util;

import cn.perfscope.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * 阈值色阶统一判定（v1.2 A8 项）。
 *
 * <p>v1.1 的显示层配色阈值散落在命令层 16 处 + 报告层 5 处，且与 config.yml 的告警阈值
 * 是两套口径（典型矛盾：实体黄界 200 vs 配置 2000 差 10 倍；报告内存界 70 vs 命令界 80）。
 * 本类把"数值 → 等级"的判定收敛到一处，并**直接读取 config.yml 的告警阈值**——
 * 显示与告警从此同一口径，改配置即改颜色。
 *
 * <p>语义约定（闭合区间：达到或越过阈值即变色，与 A8 规格的 {@code ≥critical→CRITICAL} 表述一致）：
 * 注意采集层告警的触发条件在部分指标上用严格比较（如 MSPT &gt; 50、区块 &gt; 2500），因此
 * <b>恰好等于阈值的瞬间，显示会比告警先变一档</b>——这是有意的"早预警"语义（看到颜色变化时告警还在路上）；
 * MEMORY / PLAYERS 两侧均为闭区间、完全一致。
 * <ul>
 *   <li>低值差指标（{@link Metric#TPS}）：值越低越差。{@code value <= critical → CRITICAL}，
 *       {@code value <= warning → WARN}，否则 GOOD。</li>
 *   <li>高值差指标（其余全部）：值越高越差。{@code value >= critical → CRITICAL}，
 *       {@code value >= warning → WARN}，否则 GOOD。</li>
 *   <li>{@link Metric#CPU_PROCESS} / {@link Metric#CPU_SYSTEM}：config 只有 warning 级
 *       （无 critical 键），故最高只判到 WARN——不再有"红界=告警界"的错位。</li>
 *   <li>{@link Metric#ENTITIES} 支持按世界覆盖：传入 worldName 且该世界在 worlds 段有配置时
 *       用覆盖值；否则回退全局阈值（显示侧的提示性质，告警侧总数判定见 MetricsCollector）。</li>
 *   <li>数值哨兵：{@code value <= 0}（MSPT/CPU 的 -1 语义）或 NaN → {@link Level#UNKNOWN}。</li>
 * </ul>
 *
 * <p><b>行为变更声明（v1.2）</b>：绑定配置后，部分服务器的肉眼感受会变化——原先多个指标的
 * "红界"实际等于告警"黄界"（区块 1500、CPU 80 等），现在红色只在真正越过 critical 时出现。
 * 这是有意的口径修正（Wave 4 审查 A8 项），已记入 CHANGELOG。
 *
 * <p>线程安全：本类无状态（每次都从 {@link ConfigManager#get()} 取当前配置），
 * 可在任意线程使用；配置 reload 后自动生效。
 */
public final class ThresholdColors {

    /** 等级：与"不可用/未知"哨兵语义对齐（UNKNOWN 专供数值哨兵，模块级不可用由调用方先行判断）。 */
    public enum Level { GOOD, WARN, CRITICAL, UNKNOWN }

    /** 参与色阶判定的指标类型。 */
    public enum Metric {
        /** 低值差：TPS。 */
        TPS,
        /** 高值差：MSPT（毫秒）。 */
        MSPT,
        /** 高值差：堆内存使用率（百分比）。 */
        MEMORY,
        /** 高值差：进程 CPU（百分比，config 仅 warning 级）。 */
        CPU_PROCESS,
        /** 高值差：系统 CPU（百分比，config 仅 warning 级）。 */
        CPU_SYSTEM,
        /** 高值差：玩家占容量百分比。 */
        PLAYERS,
        /** 高值差：实体总数（支持 worlds 段按世界覆盖）。 */
        ENTITIES,
        /** 高值差：加载区块数（全局阈值）。 */
        CHUNKS
    }

    private final ConfigManager configManager;

    public ThresholdColors(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 判定指标等级（不区分世界）。
     *
     * @param metric 指标类型
     * @param value  当前数值（TPS 为 0-20+；MSPT 为毫秒；内存/CPU/玩家为百分比；实体/区块为计数）
     * @return 等级；数值哨兵（&lt;= 0 的 MSPT/CPU、NaN）返回 {@link Level#UNKNOWN}
     */
    public Level levelOf(Metric metric, double value) {
        return levelOf(metric, value, null);
    }

    /**
     * 判定指标等级（ENTITIES 可传世界名以应用 worlds 段覆盖阈值）。
     *
     * @param worldOrNull 世界名（仅 ENTITIES 使用；其余指标忽略此参数）
     */
    public Level levelOf(Metric metric, double value, String worldOrNull) {
        if (Double.isNaN(value)) {
            return Level.UNKNOWN;
        }
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg == null) {
            return Level.UNKNOWN;
        }
        switch (metric) {
            case TPS:
                // 低值差：TPS 0 是"服务器冻结"的真实告警值，不视为未知
                if (value <= cfg.tps_criticalThreshold) return Level.CRITICAL;
                if (value <= cfg.tps_warningThreshold) return Level.WARN;
                return Level.GOOD;
            case MSPT:
                if (value <= 0) return Level.UNKNOWN; // -1 哨兵：满 TPS 反推无意义/未采集
                if (value >= cfg.mspt_criticalThreshold) return Level.CRITICAL;
                if (value >= cfg.mspt_warningThreshold) return Level.WARN;
                return Level.GOOD;
            case MEMORY:
                if (value >= cfg.memory_heapCriticalThreshold) return Level.CRITICAL;
                if (value >= cfg.memory_heapWarningThreshold) return Level.WARN;
                return Level.GOOD;
            case CPU_PROCESS:
                if (value <= 0) return Level.UNKNOWN; // -1 哨兵：JDK 取不到
                if (value >= cfg.cpu_processWarningThreshold) return Level.WARN; // 无 critical 键
                return Level.GOOD;
            case CPU_SYSTEM:
                if (value <= 0) return Level.UNKNOWN;
                if (value >= cfg.cpu_systemWarningThreshold) return Level.WARN;
                return Level.GOOD;
            case PLAYERS:
                if (value >= cfg.players_criticalThreshold) return Level.CRITICAL;
                if (value >= cfg.players_warningThreshold) return Level.WARN;
                return Level.GOOD;
            case ENTITIES: {
                int warning = cfg.entities_totalWarningThreshold;
                int critical = cfg.entities_totalCriticalThreshold;
                if (worldOrNull != null) {
                    ConfigManager.WorldSettings ws = configManager.getWorldSettings(worldOrNull);
                    if (ws != null) {
                        warning = ws.entities_totalWarningThreshold;
                        critical = ws.entities_totalCriticalThreshold;
                    }
                }
                if (value >= critical) return Level.CRITICAL;
                if (value >= warning) return Level.WARN;
                return Level.GOOD;
            }
            case CHUNKS:
                if (value >= cfg.chunks_loadedCriticalThreshold) return Level.CRITICAL;
                if (value >= cfg.chunks_loadedWarningThreshold) return Level.WARN;
                return Level.GOOD;
            default:
                return Level.UNKNOWN;
        }
    }

    /** 等级 → 命令层颜色（GOOD 绿 / WARN 黄 / CRITICAL 红 / UNKNOWN 灰）。 */
    public NamedTextColor colorOf(Level level) {
        switch (level) {
            case GOOD: return NamedTextColor.GREEN;
            case WARN: return NamedTextColor.YELLOW;
            case CRITICAL: return NamedTextColor.RED;
            default: return NamedTextColor.GRAY;
        }
    }

    /** 一把梭：文本 + 等级 → 着色 Component（命令层高频用法）。 */
    public Component text(String text, Level level) {
        return Component.text(text, colorOf(level));
    }

    /** 等级 → 报告层 CSS class（good / warn / critical / unknown，与 ReportGenerator 既有样式对齐）。 */
    public String cssClass(Level level) {
        switch (level) {
            case GOOD: return "good";
            case WARN: return "warn";
            case CRITICAL: return "critical";
            default: return "unknown";
        }
    }

    /**
     * TPS 趋势桶（0-3）：供命令层趋势字符与 HTML 趋势柱共用的四档映射。
     * 3=完美（>= 20，满 TPS，物理上限而非策略阈值）；2=GOOD；1=WARN；0=CRITICAL。
     * 数值 &lt;= 0 归 0 档（真实冻结，非哨兵）。
     */
    public int tpsTrendBucket(double tps) {
        if (tps >= 20.0) return 3;
        Level level = levelOf(Metric.TPS, tps);
        switch (level) {
            case GOOD: return 2;
            case WARN: return 1;
            default: return 0;
        }
    }
}
