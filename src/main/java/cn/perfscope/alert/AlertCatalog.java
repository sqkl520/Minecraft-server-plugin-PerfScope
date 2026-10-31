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

import cn.perfscope.util.Messages;

/**
 * 告警文案目录（9D Phase 2）：把结构化的 {@link Alert} 渲染成当前语言的文案。
 *
 * <p><b>为什么要有这一层</b>：i18n 之前，告警文案在<b>采集时</b>就把中文串渲染好存进
 * {@code Alert.message}，然后三个解析器再从串里反推数值与分类名（见 {@link Metric} 的类注释）。
 * 结构化的做法是反过来：{@code Alert} 只带"是什么指标、值是多少、阈值是多少"，
 * <b>文案在展示的那一刻</b>按当前语言现渲染。这样：</p>
 * <ul>
 *   <li>切语言不需要迁移任何已持久化的告警（{@code alerts.state} / {@code alerts.jsonl} 里存的是
 *       指标 id + 数值，不是中文串），历史记录也跟着新语言走；</li>
 *   <li>解析器全部删除——数值直接来自 {@code Alert.value()}，不可能被文案改动影响；</li>
 *   <li>同一条告警在 en 与 zh 下必然是同一次观测（同一个 key + level + value），
 *       切换语言不会被误判成"数值变了"而触发重推。</li>
 * </ul>
 *
 * <p><b>消息键命名</b>：{@code alert.<metricId>.<level>}，例如
 * {@code alert.cpu_system.warning} = {@code "System CPU load high: {0}"} /
 * {@code "系统CPU负载偏高: {0}"}。世界维度的指标（{@link Metric#scopeScoped()}）多一个
 * {@code .scoped} 变体，文案里用 {@code {0}} 插入世界名、{@code {1}} 插入数值。</p>
 *
 * <p><b>回退</b>：键缺失时 {@link Messages#get} 返回键名本身。这里把"键名"翻译成
 * 一个不含中文的兜底串（{@code "<metric> <level>: <value>"}），
 * 以避免某个漏翻的键把形如 {@code alert.cpu_system.warning} 的原始键名直接打给服主。</p>
 */
public final class AlertCatalog {

    private AlertCatalog() {}

    /**
     * 渲染告警正文（"系统CPU负载偏高: 92.1%" / "System CPU load high: 92.1%"）。
     *
     * <p>数值的单位后缀由 {@link Metric.Unit} 决定，<b>与语言无关</b>——
     * 这样同一份数据在两种语言下读出来的是同一个数，只有描述文字在变。</p>
     */
    public static String message(Alert alert) {
        if (alert == null) {
            return "";
        }
        Metric metric = alert.metric();
        if (metric == null) {
            // 未知指标（历史遗留的已下线 key）：退回 key 本身，绝不解析文案
            return alert.key();
        }
        String value = formatValue(alert.value(), metric.unit());
        String key = metric.alertKeyPrefix() + levelId(alert) + (needsScope(alert) ? ".scoped" : "");
        String rendered = Messages.get(key, argsFor(alert, metric, value));
        if (rendered.equals(key)) {
            // 键缺失（未翻译/拼错）：给一个结构化兜底，绝不把原始键名打给服主
            String scope = scopeOrEmpty(alert);
            return scope.isEmpty()
                ? metric.id() + " " + levelId(alert) + ": " + value
                : metric.id() + " " + levelId(alert) + " (" + scope + "): " + value;
        }
        return rendered;
    }

    /**
     * 按指标决定占位符实参的顺序与数量。
     *
     * <p><b>这里踩过一个坑，值得记下来</b>：最初实现对所有模板一律传 {@code (scope, value)}，
     * 但非世界维度的模板只写 {@code {0}}（值是第 0 个参数）。结果所有普通告警渲染成
     * {@code "Heap usage high: "}——数值丢了，而且零异常零日志。
     * 红队用真实类实测出这一点（BLOCKER-1）。教训：<b>占位符顺序必须与模板一一对应，
     * 不能靠"多传一个总没错"</b>。</p>
     *
     * <p>三种形态：</p>
     * <ul>
     *   <li>世界维度（{@code entities:world:x}）：{@code {0}} = 世界名，{@code {1}} = 值；</li>
     *   <li>带容量（{@code players}）：{@code {0}} = 值，{@code {1}} = 容量；</li>
     *   <li>其余：{@code {0}} = 值。</li>
     * </ul>
     */
    private static Object[] argsFor(Alert alert, Metric metric, String formattedValue) {
        if (needsScope(alert)) {
            return new Object[] {scopeOrEmpty(alert), formattedValue};
        }
        if (metric == Metric.PLAYERS && !Double.isNaN(alert.reference())) {
            return new Object[] {formattedValue, formatValue(alert.reference(), Metric.Unit.COUNT)};
        }
        return new Object[] {formattedValue};
    }

    /**
     * 渲染告警的"分类名"（不含数值）——恢复通知的锚点：
     * {@code display + " 已恢复正常"}。
     *
     * <p>这就是 {@code Alert.displayOf} 当年靠切冒号做的事；现在直接取标题键，
     * 文案里加括号、加冒号、加单位都不会破坏它。</p>
     */
    public static String title(Alert alert) {
        if (alert == null) {
            return "";
        }
        Metric metric = alert.metric();
        if (metric == null) {
            return alert.key();
        }
        String key = metric.alertKeyPrefix() + levelId(alert) + ".title"
            + (needsScope(alert) ? ".scoped" : "");
        String rendered = Messages.get(key, scopeOrEmpty(alert));
        if (rendered.equals(key)) {
            return metric.id() + " " + levelId(alert);
        }
        return rendered;
    }

    /**
     * 渲染"恢复"后缀（{@code 已恢复正常} / {@code recovered}）。
     *
     * <p>这一步在 i18n 之前是硬编码字符串拼接（{@code display + " 已恢复正常"}），
     * 翻译告警正文后会出现"英文正文 + 中文后缀"的半吊子输出——本方法把它也纳入消息层。</p>
     */
    public static String recoveredSuffix() {
        String key = "alert.recovered";
        String s = Messages.get(key);
        return s.equals(key) ? "recovered" : s;
    }

    /**
     * 数值格式化：整数不带小数点，小数保留原始精度（{@code 92.1} / {@code 3000}）。
     *
     * <p>用 {@link java.util.Locale#ROOT} 保证在 {@code de_DE} 这类使用逗号作小数点的系统上
     * 仍输出 {@code 92.1} 而不是 {@code 92,1}——数值展示不应随操作系统区域设置变化。</p>
     */
    public static String formatValue(double value, Metric.Unit unit) {
        if (Double.isNaN(value)) {
            return "?";
        }
        String num;
        if (value == Math.rint(value) && !Double.isInfinite(value)
            && Math.abs(value) < 1e15) {
            num = String.valueOf((long) value);
        } else {
            num = String.format(java.util.Locale.ROOT, "%.1f", value);
        }
        return num + unit.suffix();
    }

    /** 级别令牌（{@code warning} / {@code critical}）——用于消息键，永不本地化。 */
    private static String levelId(Alert alert) {
        return alert.critical() ? Alert.LEVEL_CRITICAL : Alert.LEVEL_WARNING;
    }

    /** 该告警是否需要世界名上下文。 */
    private static boolean needsScope(Alert alert) {
        Metric metric = alert.metric();
        return metric != null && metric.scopeScoped() && !scopeOrEmpty(alert).isEmpty();
    }

    private static String scopeOrEmpty(Alert alert) {
        String s = alert.scope();
        return s == null ? "" : s;
    }
}
