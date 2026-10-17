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
 * 结构化告警值对象（Wave 7B/7C，契约见 docs/06-tasks/WAVE7-DESIGN.md）。
 *
 * <p>告警的"唯一身份"是 {@link #key()}（metric:scope，如 "tps:global"、"entities:world:world_nether"），
 * 而非旧版按文案前缀字符串解析的启发式 key——字符串 key 在级别升级、世界名含冒号等场景会分裂成
 * 两个身份，产生"旧告警恢复 + 新告警"两条通知（告警疲劳来源之一）。结构化后同一 key 的
 * warning→critical 升级被 AlertService 收敛为一条升级通知。</p>
 *
 * <p>字段语义：</p>
 * <ul>
 *   <li>{@code key} —— 唯一身份：{@code metric:scope}（scope 含世界名以保唯一，如 {@code entities:world:<世界名>}）；</li>
 *   <li>{@code level} —— {@link #LEVEL_WARNING} / {@link #LEVEL_CRITICAL}；</li>
 *   <li>{@code message} —— 人类可读一句（逐字沿用 v1.1 文案；命令 /ps 活跃告警区、报告三格式、
 *       JSON alerts.* 的派生字符串列表依赖该文案，不得改字）；</li>
 *   <li>{@code display} —— message 的"类别部分"（最后一个冒号前，见 {@link #displayOf}），恢复文案锚点：
 *       恢复消息 = display + " 已恢复正常"；级别升级后为新级别文案的 display（如 "堆内存使用率严重偏高"）；</li>
 *   <li>{@code module} —— 所属采集模块（{@code MetricsSnapshot.MODULE_*} 常量），供 AlertService 做
 *       恢复抑制的模块归因（步进式替代旧的 moduleFor 字符串解析）；</li>
 *   <li>{@code timestamp} —— 生成本条告警的快照时间（epoch ms）。</li>
 * </ul>
 *
 * <p>本类为不可变 record，可安全跨线程传递（采集线程构造 → 快照发布 → 告警服务只读消费）。</p>
 */
public record Alert(
    String key,
    String level,
    String message,
    String display,
    String module,
    long timestamp
) {

    /** 级别常量：警告（采集层 warning 档阈值触发）。 */
    public static final String LEVEL_WARNING = "warning";

    /** 级别常量：严重（采集层 critical 档阈值触发）。 */
    public static final String LEVEL_CRITICAL = "critical";

    /** 工厂：display 由 message 按"最后一个冒号前"规则自动提取，避免调用方手写两处不一致。 */
    public static Alert of(String key, String level, String message, String module, long timestamp) {
        return new Alert(key, level, message, displayOf(message), module, timestamp);
    }

    /** 是否严重级别（非 "critical" 一律按警告处理，防御未知级别字符串）。 */
    public boolean critical() {
        return LEVEL_CRITICAL.equals(level);
    }

    /**
     * 提取 message 的类别部分（display）：取最后一个冒号（半角/全角）之前的部分并去空白。
     * 与旧 AlertService.keyOf 同一规则——用最后一个冒号而非第一个，是为了兼容世界名本身含冒号的
     * 极端情况（如 "世界 w:x 实体数严重超标: 3000" 取到完整类别）；无冒号时用全文。
     */
    public static String displayOf(String message) {
        String text = message == null ? "" : message.trim();
        int idx = Math.max(text.lastIndexOf(':'), text.lastIndexOf('：'));
        return idx > 0 ? text.substring(0, idx).trim() : text;
    }
}
