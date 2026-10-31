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
 * 结构化告警值对象（Wave 7B/7C 契约 + 9D Phase 2 结构化重写）。
 *
 * <p><b>告警的"唯一身份"是 {@link #key()}（metric:scope）</b>，如 {@code "tps:global"}、
 * {@code "entities:world:world_nether"}。字符串 key 在级别升级、世界名含冒号等场景下若按文案前缀解析
 * 会分裂成两个身份，产生"旧告警恢复 + 新告警"两条通知（告警疲劳来源之一）。</p>
 *
 * <h2>9D Phase 2：为什么不再存渲染后的文案</h2>
 *
 * <p>本类此前是一个"文案容器"：字段 {@code message} / {@code display} 存的是<b>已渲染的中文串</b>，
 * 而 {@link AlertService} 又反过来解析这两个串来取数值与分类名。i18n 改造把这条路彻底堵死了——
 * 本机实测三种失效（全部静默、零日志）：</p>
 * <ul>
 *   <li>{@code "System CPU load high: 92.1% (threshold: 90%)"} → 值被解析成 <b>90.0</b>（阈值）</li>
 *   <li>{@code "Entities: 3,000"} → 解析成 <b>3.0</b>（千分位截断）</li>
 *   <li>译文无冒号 → 值为 {@code null}，重推抑制永久失效</li>
 * </ul>
 *
 * <p>现在本类只保存<b>与语言无关</b>的测量数据：{@link #metric()}、{@link #value()}、
 * {@link #reference()}、{@link #scope()}、{@link #threshold()}。文案由
 * {@link AlertCatalog} 在<b>展示的那一刻</b>按当前语言渲染。</p>
 *
 * <p><b>为什么 {@link #message()} / {@link #display()} 仍作为方法存在</b>：它们是派生值，
 * 保留是为了让 20 多处既有调用点（命令层、报告三格式、{@code alerts.jsonl}）无需改写即可编译。
 * 它们的返回值<b>随语言变化</b>，这正是想要的语义。</p>
 *
 * <p><b>不可变 record</b>：可安全跨线程传递（采集线程构造 → 快照发布 → 告警服务只读消费）。</p>
 *
 * @param key       唯一身份 {@code metric:scope}（ASCII，永不本地化，参与持久化）
 * @param level     {@link #LEVEL_WARNING} / {@link #LEVEL_CRITICAL}（令牌，永不本地化）
 * @param metric    指标类型；{@code null} 表示无法识别（历史数据里的已下线指标）
 * @param module    所属采集模块（{@code MetricsSnapshot.MODULE_*}），供恢复抑制做模块归因
 * @param timestamp 生成本条告警的快照时间（epoch ms）
 * @param value     实测值（指标原生单位）；{@link Double#NaN} 表示未知
 * @param reference 分母/容量（玩家数的 maxPlayers）；不适用时为 {@link Double#NaN}
 * @param scope     世界名（仅世界维度指标有意义）；否则空串
 * @param threshold 本次判定实际采用的<b>进入</b>阈值；不适用时 {@link Double#NaN}
 * @param exitThreshold 迟滞<b>退出</b>线；无迟滞时 {@link Double#NaN}
 */
public record Alert(
    String key,
    String level,
    Metric metric,
    String module,
    long timestamp,
    double value,
    double reference,
    String scope,
    double threshold,
    double exitThreshold
) {

    /** 级别常量：警告（采集层 warning 档阈值触发）。 */
    public static final String LEVEL_WARNING = "warning";

    /** 级别常量：严重（采集层 critical 档阈值触发）。 */
    public static final String LEVEL_CRITICAL = "critical";

    /**
     * 工厂：由测量数据构造一条告警（9D 新主入口）。
     *
     * <p>{@link #metric()} 与 {@link #scope()} 都由 key 推导，避免调用方手写两处不一致——
     * 这正是旧 {@code of(...)} 用 {@code displayOf(message)} 自动推导 display 的同一思路，
     * 只是现在推导的是"结构"而不是"文案"。</p>
     */
    public static Alert measured(String key, String level, double value,
                                 String module, long timestamp) {
        return new Alert(key, level, Metric.ofKey(key), module, timestamp,
            value, Double.NaN, Metric.scopeOf(key), Double.NaN, Double.NaN);
    }

    /**
     * 工厂：带容量/分母的告警（玩家数 {@code 12/20}）。
     */
    public static Alert measuredWithReference(String key, String level, double value,
                                              double reference, String module, long timestamp) {
        return new Alert(key, level, Metric.ofKey(key), module, timestamp,
            value, reference, Metric.scopeOf(key), Double.NaN, Double.NaN);
    }

    /**
     * 工厂：带阈值与迟滞退出线的告警（CPU 两个指标用）。
     */
    public static Alert measuredWithThresholds(String key, String level, double value,
                                               double threshold, double exitThreshold,
                                               String module, long timestamp) {
        return new Alert(key, level, Metric.ofKey(key), module, timestamp,
            value, Double.NaN, Metric.scopeOf(key), threshold, exitThreshold);
    }

    /**
     * 兼容工厂——<b>已删除</b>（9D 红队复核）。
     *
     * <p>曾有 {@code Alert.of(key, level, message, module, timestamp)} 接受已渲染文案并忽略它，
     * 产物是 {@code value = NaN}。而 {@code NaN} 会让 {@code numericValueOf} 返回 {@code null}，
     * 进而让重推抑制判据退化——换言之，这个"兼容"工厂唯一的运行时语义是
     * <b>静默关闭一道生产安全网</b>。项目没有对外 API 契约，调用点也已归零，
     * 保留它等于给下一个人挖坑，故直接删除。</p>
     */

    /** 是否严重级别（非 "critical" 一律按警告处理，防御未知级别字符串）。 */
    public boolean critical() {
        return LEVEL_CRITICAL.equals(level);
    }

    /**
     * 渲染后的告警正文（按当前语言）。派生值，不参与持久化。
     *
     * <p>旧实现把它当字段存；现在每次读都按当前语言现渲染，因此切换语言后
     * 连历史记录也会跟着变——这是"全有或全无"成立的前提。</p>
     */
    public String message() {
        return AlertCatalog.message(this);
    }

    /**
     * 渲染后的分类名（不含数值，按当前语言）。恢复通知的锚点：
     * {@link #message()} 当年靠切冒号得到的正是它。
     */
    public String display() {
        return AlertCatalog.title(this);
    }

    /** 数值是否已知（NaN 表示未经结构化构造，或历史数据缺值）。 */
    public boolean hasValue() {
        return !Double.isNaN(value);
    }

    /**
     * 提取一段文本的类别部分（取最后一个冒号前）。
     *
     * @deprecated 9D 起告警不再解析文案。保留仅为兼容第三方/旧调用；不要用于新代码。
     */
    @Deprecated
    public static String displayOf(String message) {
        String text = message == null ? "" : message.trim();
        int idx = Math.max(text.lastIndexOf(':'), text.lastIndexOf('：'));
        return idx > 0 ? text.substring(0, idx).trim() : text;
    }
}
