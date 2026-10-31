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

/**
 * 告警指标类型（9D：告警结构化的基石）。
 *
 * <p><b>为什么需要这个枚举</b>：i18n 改造前，告警文案在采集时就渲染成中文串，
 * 之后又被三个解析器反过来"读"回去——{@code displayOf} 按最后一个冒号切分类名、
 * {@code valueOf} 取冒号后的值、{@code numericValueOf} 再扫描数字。
 * 本机实测这三种解析在文案改动后会静默失效：</p>
 * <ul>
 *   <li>{@code "System CPU load high: 92.1% (threshold: 90%)"} → 值被解析成 <b>90.0</b>（阈值而非实测值）</li>
 *   <li>{@code "Entities: 3,000"} → 解析成 <b>3.0</b>（千分位截断，3000→8000 永不触发）</li>
 *   <li>译文无冒号 → 值解析为 {@code null}，重推抑制永久失效（每 300 秒一条，永不停止）</li>
 * </ul>
 *
 * <p>根治办法是让告警在<b>任何语言下</b>都带着"这是什么指标 + 数值是多少"的结构化信息旅行，
 * 文案只在<b>展示的那一刻</b>按当前语言渲染。本枚举就是那个"这是什么指标"。</p>
 *
 * <p><b>为什么是 9 个而不是 7 个</b>：{@code cpu:process} 与 {@code cpu:system} 的标题、
 * 阈值、迟滞提示都不同；{@code entities:global} 与 {@code entities:world:<名>} 的措辞也不同。
 * 若像旧的 {@code metricOf()} 那样按 key 前缀归并（两者都返回 {@code "cpu"}），
 * 恰恰会丢掉区分它们所需的信息——那正是这次要修的 bug 类型。</p>
 *
 * <p><b>为什么单位不是 Alert 的字段</b>：单位是<b>指标</b>的固有属性，不是单次观测的属性。
 * 做成 Alert 的独立字段就允许 {@code MSPT + PERCENT} 这种荒谬组合存在，而放在枚举里则从类型上不可能。</p>
 */
public enum Metric {

    /** TPS（低值差）：1 分钟均值。 */
    TPS("tps", Unit.TPS, false),

    /** MSPT（高值差）：平均每 tick 耗时，毫秒。 */
    MSPT("mspt", Unit.MILLIS, false),

    /** 在线玩家数：值与容量成对出现（{@code 12/20}）。 */
    PLAYERS("players", Unit.COUNT, false),

    /** 全服实体总数。 */
    ENTITIES("entities", Unit.COUNT, false),

    /** 单世界实体数（{@code scope} = 世界名）。 */
    ENTITIES_WORLD("entities_world", Unit.COUNT, true),

    /** 单世界加载区块数（{@code scope} = 世界名）。 */
    CHUNKS_WORLD("chunks_world", Unit.COUNT, true),

    /** 堆内存使用率。 */
    MEMORY("memory", Unit.PERCENT, false),

    /** 进程 CPU（JVM 自身占整机的比例）。 */
    CPU_PROCESS("cpu_process", Unit.PERCENT, false),

    /** 系统 CPU（整机口径，含 JVM 之外的一切占用）。 */
    CPU_SYSTEM("cpu_system", Unit.PERCENT, false);

    /** 数值的展示单位。 */
    public enum Unit {
        /** TPS 本身，无单位后缀。 */
        TPS(""),
        /** 毫秒。 */
        MILLIS("ms"),
        /** 百分比。 */
        PERCENT("%"),
        /** 纯计数，无单位后缀。 */
        COUNT("");

        private final String suffix;

        Unit(String suffix) {
            this.suffix = suffix;
        }

        /** 单位后缀（可能为空串）。数值与后缀之间<b>不加空格</b>（"92.1%"、"45.2ms"）。 */
        public String suffix() {
            return suffix;
        }
    }

    private final String id;
    private final Unit unit;
    private final boolean scopeScoped;

    Metric(String id, Unit unit, boolean scopeScoped) {
        this.id = id;
        this.unit = unit;
        this.scopeScoped = scopeScoped;
    }

    /** 稳定的 ASCII 标识（用于消息键名与持久化，<b>绝不</b>随语言变化）。 */
    public String id() {
        return id;
    }

    /** 该指标的数值单位。 */
    public Unit unit() {
        return unit;
    }

    /** 该指标是否与某个世界绑定（决定文案里要不要插入世界名）。 */
    public boolean scopeScoped() {
        return scopeScoped;
    }

    /**
     * 由告警 key 反查指标类型。
     *
     * <p>key 的形态固定为 {@code metric:scope}（如 {@code tps:global}、
     * {@code entities:world:world_nether}），scope 里允许再含冒号——因此这里按
     * <b>第一个</b>冒号切分，并对 {@code entities}/{@code chunks} 再判世界维度。</p>
     *
     * @return 对应指标；无法识别时返回 {@code null}（调用方须能容忍——历史数据里可能有
     *         已下线指标如 {@code redstone:global}）
     */
    public static Metric ofKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        int i = key.indexOf(':');
        String prefix = i > 0 ? key.substring(0, i) : key;
        String rest = i > 0 ? key.substring(i + 1) : "";
        return switch (prefix) {
            case "tps" -> TPS;
            case "mspt" -> MSPT;
            case "players" -> PLAYERS;
            case "memory" -> MEMORY;
            case "entities" -> rest.startsWith("world:") ? ENTITIES_WORLD : ENTITIES;
            // 全服区块数不是告警指标（旧版本也从未告警过），只有按世界才告警
            case "chunks" -> CHUNKS_WORLD;
            case "cpu" -> switch (rest) {
                case "process" -> CPU_PROCESS;
                case "system" -> CPU_SYSTEM;
                default -> null;
            };
            default -> null;
        };
    }

    /**
     * 由 key 取出世界名（仅 {@link #scopeScoped()} 的指标有意义）。
     *
     * @return 世界名；非世界维度指标或 key 形态不符时返回空串
     */
    public static String scopeOf(String key) {
        if (key == null) {
            return "";
        }
        int i = key.indexOf("world:");
        return i >= 0 ? key.substring(i + "world:".length()) : "";
    }

    /** 告警历史事件令牌（{@code open}/{@code resolve}/…）的消息键名前缀。 */
    public static String eventKeyPrefix() {
        return "alert.event.";
    }

    /** 本指标文案类名的消息键前缀（如 {@code alert.tps.warning}）。 */
    public String alertKeyPrefix() {
        return "alert." + id + ".";
    }
}
