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
import cn.perfscope.metrics.ThresholdOperator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * 阈值色阶统一判定（v1.2 A8 项）。
 *
 * <p>v1.1 的显示层配色阈值散落在命令层 16 处 + 报告层 5 处，且与 config.yml 的告警阈值
 * 是两套口径（典型矛盾：实体黄界 200 vs 配置 2000 差 10 倍；报告内存界 70 vs 命令界 80——
 * 均为 v1.1 两侧硬编码字面量，v1.2 起内存默认已重调为 85/95）。
 * 本类把"数值 → 等级"的判定收敛到一处，并**直接读取 config.yml 的告警阈值**——
 * 阈值来源与告警同一个，改配置即改颜色。</p>
 *
 * <h2>比较符不写在这里，全部取自 {@link ThresholdOperator}</h2>
 *
 * <p><b>这是一次修正，不是重构。</b>本类原先自己写闭区间（TPS {@code <=}、其余 {@code >=}），
 * 而采集层用算子表（部分指标严格比较）。当时的说法是有意的「早预警」：读数恰好等于阈值时
 * 颜色先变、告警随后到达。但两边的差<b>不是一格</b>——{@code tps1m == 15.0} 时本类判
 * CRITICAL，{@code MetricsCollector} 因为 {@code 15.0 < 15.0} 不成立只判 WARNING，
 * 于是同一屏上「红色 TPS」与「黄色警告」并存，<b>差一整档</b>。那已经不能叫预警，
 * 而是两份副本各自漂移（本仓库的另一处同类漂移见 {@code AlertMessages} 里的比较符模板）。
 * README 当时把这件事说小了，现在改的是判定本身，README 与注释一并改成事实。</p>
 *
 * <p>现在每个指标都调 {@link ThresholdOperator.Rule#levelOf}（CPU 走
 * {@link ThresholdOperator.SingleTier#trips}），本类只剩三件自己的事：</p>
 * <ul>
 *   <li><b>哨兵</b>——{@code <= 0} 的 MSPT、{@code < 0} 的 CPU 是"未采集"。
 *       这属于"读数可信吗"，不是"读数越界吗"，算子表不该管；</li>
 *   <li><b>阈值取哪一组</b>——ENTITIES 支持 worlds 段按世界覆盖，取覆盖值还是全局值；</li>
 *   <li><b>档位 → 颜色/CSS</b>——{@link #colorOf} / {@link #cssClass}。</li>
 * </ul>
 *
 * <h2>五族边界，逐族说清"恰好等于阈值"是什么</h2>
 *
 * <table border="1">
 *   <caption>{@code value} 恰好等于阈值时的判定（两侧现在逐点相同）</caption>
 *   <tr><th>族</th><th>方向</th><th>算子</th><th>恰好等于阈值</th></tr>
 *   <tr><td>{@code tps}</td><td>低值差</td><td>{@code <}（严格）</td><td>不越界：{@code 18.0} 仍是 GOOD，{@code 17.99} 才黄</td></tr>
 *   <tr><td>{@code mspt} / {@code entities} / {@code chunks}</td><td>高值差</td><td>{@code >}（严格）</td>
 *       <td>不越界：{@code 30000} 仍是 WARN，{@code 30001} 才红</td></tr>
 *   <tr><td>{@code players} / {@code memory}</td><td>高值差</td><td>{@code >=}（闭区间）</td>
 *       <td>越界：{@code 85%} 就是 WARN（18/20 人恰好 90% 是最常见的满员配置）</td></tr>
 *   <tr><td>{@code cpu:process} / {@code cpu:system}</td><td>高值差</td><td>{@code >}（严格）</td>
 *       <td>不越界：{@code 80.0%} 仍是 GOOD，{@code 80.01%} 才黄；<b>最高只到 WARN</b>（config 无 critical 键）</td></tr>
 * </table>
 *
 * <p>{@link Metric#CPU_PROCESS} / {@link Metric#CPU_SYSTEM} 是第五族，<b>不是</b>因为偷懒才单列：
 * 采集层对它们用带迟滞的闩锁（进入 {@code >}、退出 {@code >= threshold - margin}），
 * 而本类判的是<b>瞬时值</b>——闩锁回答"这个告警状态要不要延续"，
 * 颜色回答"此刻读数是多少"。两者问的问题不同，但<b>进入线的比较符是同一个</b>
 * （都取自 {@link ThresholdOperator#CPU_PROCESS}），于是 {@code 80.0%} 两边都不算越界、
 * {@code 80.01%} 两边都算。这是"同口径"能做到的极限，也是它的正确定义。</p>
 *
 * <ul>
 *   <li>{@link Metric#ENTITIES} 支持按世界覆盖：传入 worldName 且该世界在 worlds 段有配置时
 *       用覆盖值（对应 {@link ThresholdOperator#ENTITIES_WORLD}）；否则回退全局
 *       （{@link ThresholdOperator#ENTITIES_GLOBAL}）。两条规则的算子目前相同，
 *       但分开取是为了让"哪天世界口径要改"只改表里的那一行。</li>
 *   <li>数值哨兵：{@code value <= 0}（MSPT 的 -1 语义）或 NaN → {@link Level#UNKNOWN}。</li>
 * </ul>
 *
 * <p><b>行为变更声明（v1.2）</b>：绑定配置后，部分服务器的肉眼感受会变化——原先多个指标的
 * "红界"实际等于告警"黄界"（区块 1500、CPU 80 等）。</p>
 *
 * <p><b>行为变更声明（本版）</b>：颜色不再早于告警。读数恰好等于阈值时，
 * 颜色现在与告警一致地"还没变色"——{@code entities = 20000} 从"已经变黄"改为"仍是绿色"，
 * {@code tps = 18.0} 从"已经变黄"改为"仍是绿色"，{@code tps = 15.0} 从"已经变红"改为"变黄"
 * （与告警的 WARNING 同级）。宁可晚一格，也不要同一屏两套说法。</p>
 *
 * <p>线程安全：本类无状态（每次都从 {@link ConfigManager#get()} 取当前配置），
 * 可在任意线程使用；配置 reload 后自动生效。</p>
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
     * @return 等级；数值哨兵（{@code <= 0} 的 MSPT、{@code < 0} 的 CPU、NaN）返回 {@link Level#UNKNOWN}
     */
    public Level levelOf(Metric metric, double value) {
        return levelOf(metric, value, null);
    }

    /**
     * 判定指标等级（ENTITIES 可传世界名以应用 worlds 段覆盖阈值）。
     *
     * <p><b>比较符一律来自 {@link ThresholdOperator}</b>：这里只决定"拿哪个阈值去比"，
     * 不决定"怎么比"。于是"展示层恰好等于阈值时是否变色"这个问题，
     * 在整份代码里只有一个答案（表里那一行），而不是两处各写一遍。
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
                // TPS 没有 -1 哨兵：0 是"服务器冻结"的真实读数，必须照常判红而不是当未知
                return levelOf(ThresholdOperator.TPS, value,
                    cfg.tps_warningThreshold, cfg.tps_criticalThreshold);
            case MSPT:
                if (value <= 0) return Level.UNKNOWN; // -1 哨兵：满 TPS 反推无意义/未采集
                return levelOf(ThresholdOperator.MSPT, value,
                    cfg.mspt_warningThreshold, cfg.mspt_criticalThreshold);
            case MEMORY:
                return levelOf(ThresholdOperator.MEMORY, value,
                    cfg.memory_heapWarningThreshold, cfg.memory_heapCriticalThreshold);
            case CPU_PROCESS:
                // 只排除负哨兵。0.0 是合法读数（空载服务器的 CPU 就是 0），把它当"未采集"
                // 会让一台空闲服永远显示"未知"，而它恰恰是最健康的状态。这与
                // MetricsSnapshot 的约定一致（processCpuLoad 默认 -1 表示未采集），
                // 也与 /perfscope trend 的口径一致（那里同样用 >= 0 放行 0.0）。
                if (value < 0) return Level.UNKNOWN;
                return levelOf(ThresholdOperator.CPU_PROCESS, value, cfg.cpu_processWarningThreshold);
            case CPU_SYSTEM:
                if (value < 0) return Level.UNKNOWN;
                return levelOf(ThresholdOperator.CPU_SYSTEM, value, cfg.cpu_systemWarningThreshold);
            case PLAYERS:
                return levelOf(ThresholdOperator.PLAYERS, value,
                    cfg.players_warningThreshold, cfg.players_criticalThreshold);
            case ENTITIES: {
                int warning = cfg.entities_totalWarningThreshold;
                int critical = cfg.entities_totalCriticalThreshold;
                // 世界覆盖存在时换用 ENTITIES_WORLD——两条规则现在的算子相同，
                // 但分开取是为了让"世界口径要单独改"只动表里那一行
                ThresholdOperator.Rule rule = ThresholdOperator.ENTITIES_GLOBAL;
                if (worldOrNull != null) {
                    ConfigManager.WorldSettings ws = configManager.getWorldSettings(worldOrNull);
                    if (ws != null) {
                        warning = ws.entities_totalWarningThreshold;
                        critical = ws.entities_totalCriticalThreshold;
                        rule = ThresholdOperator.ENTITIES_WORLD;
                    }
                }
                return levelOf(rule, value, warning, critical);
            }
            case CHUNKS:
                return levelOf(ThresholdOperator.CHUNKS_WORLD, value,
                    cfg.chunks_loadedWarningThreshold, cfg.chunks_loadedCriticalThreshold);
            default:
                return Level.UNKNOWN;
        }
    }

    /**
     * 两档规则 → 展示等级。档位数值与 {@link ThresholdOperator.Rule#levelOf} 的
     * {@code 2/1/0} 一一对应，这里只做一次枚举换名。
     *
     * <p>刻意保留 {@code switch} 而不是给 {@code Level} 加 {@code fromLevel(int)}：
     * 让"0/1/2"这个跨包契约在本类里保持可见，比省三行更重要——它一旦被改掉，
     * 这里会是第一个说得清"颜色为什么变了"的地方。</p>
     */
    private static Level levelOf(ThresholdOperator.Rule rule, double value,
                                 double warning, double critical) {
        switch (rule.levelOf(value, warning, critical)) {
            case 2: return Level.CRITICAL;
            case 1: return Level.WARN;
            default: return Level.GOOD;
        }
    }

    /**
     * CPU 的单档判定 → 展示等级：越界 WARN，否则 GOOD。
     *
     * <p>CPU 在 config 里<b>只有一档</b>阈值（没有 critical 键），所以最高就是 WARN。
     * 不越界当 GOOD 而不是 WARN 的"半格"，是为了与采集层的 {@code latched} 进入线严格一致：
     * 瞬时值等于阈值时告警不进入，颜色也不该变。</p>
     */
    private static Level levelOf(ThresholdOperator.SingleTier tier, double value, double warning) {
        return tier.trips(value, warning) ? Level.WARN : Level.GOOD;
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
