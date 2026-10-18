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
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.history.HistoryStore;

/**
 * 诊断规则契约（Wave 8A）。
 *
 * <p>来源：V1.2_DIAGNOSTIC_RULES.md 的 37 条规则表 + 锐评 B 论点 7 的替代方案——
 * <b>"规则即对象注册表"</b>而非"平铺巨型求值器"：每条规则一个实现，注册进
 * {@link DiagnosticEngine} 的列表；互斥/优先级由引擎统一处理（不再靠注释手工维护）；
 * 每条规则可独立单测。首批只收高置信度规则（超编 37→5），其余留 backlog。</p>
 *
 * <p>实现约定：{@code evaluate} 必须无副作用、只读快照与历史；返回 {@code null} 表示未命中。
 * 规则的 ID 一经发布不得复用（用于输出的稳定锚点）。</p>
 */
public interface DiagnosticRule {

    /** 规则 ID（输出锚点，如 "DIAG-MEM-LEAK"；一经发布不得改动/复用）。 */
    String id();

    /**
     * 评估规则。
     *
     * @param snap     最新指标快照（非 null）
     * @param history  历史缓冲（供趋势类规则使用；可为空数据但非 null）
     * @param cfg      当前配置
     * @return 命中则返回结论（含级别与建议动作）；未命中返回 null
     */
    Diagnosis evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg);
}
