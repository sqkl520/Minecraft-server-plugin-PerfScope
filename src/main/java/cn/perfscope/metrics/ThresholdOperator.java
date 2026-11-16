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
package cn.perfscope.metrics;

import java.util.List;

/**
 * 采集层阈值判定的<b>算子表</b>：每个指标用哪个比较符、朝哪个方向，写成一行可读的数据。
 *
 * <h2>为什么要有这张表</h2>
 *
 * <p>采集层的判定散在 7 个 {@code collect*} 方法内部，写法都是「两段 if / else if」。
 * 这些算子此前<b>几乎没有测试保护</b>——一次变异实测里，{@code tps} 的 {@code <} 改成
 * {@code <=}、{@code chunks} 的 {@code >} 改成 {@code >=}、逐世界实体的 {@code >} 改成
 * {@code >=}、以及全部 warning 档的 {@code >} 改成 {@code >=}，<b>全量 715 个用例零失败</b>。
 * 也就是说这些行改坏了没有任何测试会变红，而后果是线上判定整整挪动一格。</p>
 *
 * <p>表放在生产代码里（而不是测试里）是刻意的：{@code collect*} 直接调用这里的一行规则，
 * 于是「新增第 8 个指标」在结构上等于「必须往这里加一行并给方向与算子」，
 * 而不是在某处再手写一段没人看的比较。</p>
 *
 * <h2>这不是「统一口径」</h2>
 *
 * <p>{@code README.md} 第 39 行明写：展示层闭区间、采集层部分指标严格比较，是<b>有意的
 * 早预警语义</b>——读数恰好等于阈值时，颜色先变、告警还在路上。本表<b>原样记录</b>这个现状：
 * {@link #TPS} / {@link #MSPT} / {@link #ENTITIES_WORLD} / {@link #ENTITIES_GLOBAL} /
 * {@link #CHUNKS_WORLD} 是 {@link Comparison#STRICT}，{@link #PLAYERS} / {@link #MEMORY} 是
 * {@link Comparison#INCLUSIVE}。改这张表就是改线上行为，{@code ThresholdOperatorBehaviorTest}
 * 的四点表会先红。</p>
 *
 * <h2>为什么每个规则有「critical 算子」和「warning 算子」两个字段</h2>
 *
 * <p>两档在语义上可以不同（尽管当前全部相同），而把两档写成一个字段会让「只把 warning 档从
 * {@code >} 改成 {@code >=}」这种半格挪动无法被变异验证单独命中——那正是此前实测里存活的方向之一。
 * 拆成两个字段后，每个方向都能被单独改坏、单独被测试杀死。</p>
 *
 * <h2>为什么告警文案的比较符也从这里取</h2>
 *
 * <p>算子在本仓库里曾有<b>四份副本</b>：本表、{@code ThresholdColors}（展示层闭区间，有意不同）、
 * README 的散文说明、以及 {@code AlertMessages} 里 {@code alert.threshold.*} 那段
 * 「warning &gt; {0} / critical &gt; {1}」的模板。前两份有测试守着，后两份没有——而第四份
 * 面对的是服主：{@code players} 的判定是闭区间 {@code >=}（18/20 人 = 恰好 90% 是最常见的
 * 满员配置），模板却写着「大于」，于是一个真的在告警的读数会被文案描述成"还没到线"。
 * 这不是笔误，是<b>副本之间必然发生的漂移</b>。</p>
 *
 * <p>因此字形也由本表派生：{@link Comparison#symbolFor(Direction)} 把
 * 「方向 × 闭合性」投影成一个符号，{@link Rule#warningSymbol()} / {@link Rule#criticalSymbol()}
 * 是它的两条出口，{@code AlertService.thresholdText} 再把符号作为 {@code {2}} / {@code {3}}
 * 传给文案模板。改这张表里的算子，告警文案自动跟随——而不是等某天有人凑巧发现。</p>
 *
 * <p>唯一不在本表里的 CPU 除外：它的进入判据是 {@code value > threshold}（严格），
 * 退出判据 {@code >= threshold - margin} 由迟滞提示单独说明，文案里那句
 * {@code process > {0}%} 指的就是进入线，属实。</p>
 *
 * <h2>CPU 为什么不在表里</h2>
 *
 * <p>CPU 两路判定用的是<b>带迟滞的状态机</b>（{@link MetricsCollector} 的 {@code latched}）：
 * 进入用 {@code value > threshold}、退出用 {@code threshold - margin}，没有「critical 档」。
 * 把它塞进这张两档表会迫使这里引入 {@code warningOnly} 之类的分支，反而让表变难读。
 * 它的真值表由 {@code CpuHysteresisLatchTest}（12 个用例）单独覆盖。</p>
 *
 * <p>本类无状态、纯函数，可在任意线程调用。</p>
 */
public final class ThresholdOperator {

    private ThresholdOperator() {
    }

    /** 判定方向：读数是越大越差，还是越小越差（TPS 是后者，其余全部是前者）。 */
    public enum Direction {
        /** 高值差：值越大越差（mspt / entities / chunks / memory / players）。 */
        HIGH_IS_BAD,
        /** 低值差：值越小越差（tps）。 */
        LOW_IS_BAD
    }

    /**
     * 比较的闭合性，与 {@code ThresholdColors} 的名字保持同一套语汇。
     *
     * <p>{@link #STRICT} = 严格比较（高值差用 {@code >}、低值差用 {@code <}），
     * 恰好等于阈值<b>不</b>越界；{@link #INCLUSIVE} = 闭区间（高值差 {@code >=}、低值差 {@code <=}），
     * 恰好等于阈值<b>就</b>越界。</p>
     */
    public enum Comparison {
        /** 严格比较：高值差 {@code >}、低值差 {@code <}。 */
        STRICT,
        /** 闭区间：高值差 {@code >=}、低值差 {@code <=}。 */
        INCLUSIVE;

        /**
         * 该闭合性在给定方向下对应的<b>字形</b>（给告警文案用）。
         *
         * <p>这是 {@code alert.threshold.*} 模板里那些比较符的唯一出处。字形随
         * 闭合性走，闭区间用 {@code ≥}/{@code ≤}（与既有 {@code memory} 文案逐字一致，
         * 中文界面读起来也更自然），严格用 ASCII 的 {@code >}/{@code <}。</p>
         *
         * <p>不返回 {@code ">="} 这类"两字符合成"给闭区间：告警正文会进控制台、
         * Discord/企业微信/飞书/钉钉四家 webhook 与 {@code alerts.state} 落盘，
         * 两个字符的写法在窄列里容易被读成"大于"。</p>
         *
         * @param direction 判定方向；{@code null} 按高值差处理（防御性，调用方不会传 null）
         */
        public String symbolFor(Direction direction) {
            boolean lowIsBad = direction == Direction.LOW_IS_BAD;
            return switch (this) {
                case STRICT -> lowIsBad ? "<" : ">";
                case INCLUSIVE -> lowIsBad ? "≤" : "≥";
            };
        }
    }

    /**
     * CPU 两路迟滞闩锁的<b>进入线字形</b>。
     *
     * <p>CPU 不在 {@link #ALL} 表里（迟滞状态机不是两档规则，见类注释），但它的进入判据
     * 同样是"严格大于"——所以字形也要有唯一出处，而不是在 {@code alert.threshold.cpu}
     * 模板里再写一个 {@code >}。真值由 {@code MetricsCollector.latched} 决定，
     * {@code ThresholdTextMatchesOperatorTest} 会反射调用它，验证"进入线确实是严格的"
     * 与这个字形一致。</p>
     */
    public static final String CPU_ENTRY_SYMBOL = ">";

    /**
     * 一条判定规则：{@code key} 只用于人读与测试定位（与 {@code Alert.key()} 同一命名），
     * 两个 {@link Comparison} 分别是 critical 档与 warning 档的闭合性。
     *
     * @param key                 告警键名（{@code metric:scope}，世界维度用 {@code *} 占位）
     * @param direction           判定方向
     * @param criticalComparison  critical 档的闭合性
     * @param warningComparison   warning 档的闭合性
     */
    public record Rule(String key, Direction direction,
                       Comparison criticalComparison, Comparison warningComparison) {

        /**
         * 判定读数落在哪一档：{@code 2} critical / {@code 1} warning / {@code 0} 健康。
         *
         * <p><b>顺序固定：critical 必须先判。</b>反过来写会让严重样本永远只报 1 级——
         * 一条 500ms 的卡顿被渲染成黄色警告，用户不会去查。</p>
         *
         * <p><b>{@code NaN} 落在 0</b>：所有比较对 {@code NaN} 都是 {@code false}，
         * 与原内联写法逐位一致（原来也是两段 {@code if} 都不进、落进 {@code else}）。</p>
         *
         * @param value    实测读数（原生单位）
         * @param warning  warning 阈值
         * @param critical critical 阈值
         * @return 0 / 1 / 2
         */
        public int levelOf(double value, double warning, double critical) {
            if (trips(value, critical, criticalComparison)) {
                return 2;
            }
            if (trips(value, warning, warningComparison)) {
                return 1;
            }
            return 0;
        }

        /** 该档是否越界。 */
        private boolean trips(double value, double threshold, Comparison comparison) {
            boolean highIsBad = direction == Direction.HIGH_IS_BAD;
            return switch (comparison) {
                case STRICT -> highIsBad ? value > threshold : value < threshold;
                case INCLUSIVE -> highIsBad ? value >= threshold : value <= threshold;
            };
        }

        /**
         * warning 档越界时用的比较符（{@code <} / {@code ≤} / {@code >} / {@code ≥}）。
         *
         * <p>与 {@link #trips} 的 warning 分支一一对应：改那边的算子，这里返回的符号随之变化，
         * 告警文案因此不可能与判定说两套话。消费者是 {@code AlertService.thresholdText}。</p>
         */
        public String warningSymbol() {
            return warningComparison.symbolFor(direction);
        }

        /**
         * critical 档越界时用的比较符。
         *
         * @see #warningSymbol()
         */
        public String criticalSymbol() {
            return criticalComparison.symbolFor(direction);
        }
    }

    // ====================================================================================
    // 算子表本体
    //
    // ⚠️ 改任何一行 = 改线上告警行为。README 第 39 行的早预警语义依赖 STRICT 与 INCLUSIVE
    // 的差异，不是笔误，不要「统一」它。新增指标时在这里加一行，并在
    // ThresholdOperatorBehaviorTest 的点表里加一条四点用例。
    // ====================================================================================

    /** TPS：低值差，两档都严格（{@code tps1m < critical} / {@code < warning}）。 */
    public static final Rule TPS =
        new Rule("tps:global", Direction.LOW_IS_BAD, Comparison.STRICT, Comparison.STRICT);

    /** MSPT：高值差，两档都严格（{@code msptAvg > critical} / {@code > warning}）。 */
    public static final Rule MSPT =
        new Rule("mspt:global", Direction.HIGH_IS_BAD, Comparison.STRICT, Comparison.STRICT);

    /** 玩家占比：高值差，两档都闭区间（{@code pct >= critical} / {@code >= warning}）。 */
    public static final Rule PLAYERS =
        new Rule("players:global", Direction.HIGH_IS_BAD, Comparison.INCLUSIVE, Comparison.INCLUSIVE);

    /**
     * 逐世界实体：高值差，两档都严格。
     *
     * <p>只对 {@code worlds:} 段有覆盖的世界判定；没覆盖的世界由 {@link #ENTITIES_GLOBAL} 兜底。
     * 这与区块的逐世界口径<b>刻意不同</b>（区块没有全服口径，所以每个世界都判）。</p>
     */
    public static final Rule ENTITIES_WORLD =
        new Rule("entities:world:*", Direction.HIGH_IS_BAD, Comparison.STRICT, Comparison.STRICT);

    /** 全服实体总数：高值差，两档都严格。 */
    public static final Rule ENTITIES_GLOBAL =
        new Rule("entities:global", Direction.HIGH_IS_BAD, Comparison.STRICT, Comparison.STRICT);

    /** 逐世界加载区块：高值差，两档都严格（每个世界都判，用全局阈值）。 */
    public static final Rule CHUNKS_WORLD =
        new Rule("chunks:world:*", Direction.HIGH_IS_BAD, Comparison.STRICT, Comparison.STRICT);

    /** 堆内存占比：高值差，两档都闭区间（{@code heapUsagePercent >= critical} / {@code >= warning}）。 */
    public static final Rule MEMORY =
        new Rule("memory:global", Direction.HIGH_IS_BAD, Comparison.INCLUSIVE, Comparison.INCLUSIVE);

    /**
     * 全部规则，供测试逐条遍历。
     *
     * <p>它没有生产调用点，是刻意的：这是一张<b>规格表</b>，消费者是测试。
     * 有了它，「新增规则却没加四点用例」会由 {@code ThresholdOperatorBehaviorTest} 的
     * 「表里有几条就必须测几条」用例变成红色，而不是靠人记得。</p>
     */
    public static final List<Rule> ALL = List.of(
        TPS, MSPT, PLAYERS, ENTITIES_WORLD, ENTITIES_GLOBAL, CHUNKS_WORLD, MEMORY);
}
