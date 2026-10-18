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

/**
 * 一条诊断结论（Wave 8A）。
 *
 * <p>{@code suggestion} 为"参数级建议"——必须是可执行的下一步（具体命令或具体配置项），
 * 而非泛泛的"检查一下"；对无法给出确定建议的规则，写明"信息不足"并给出获取信息的动作。
 * 这是与"展示数字"的核心区别，也是错误归因成本最高处：宁可少给结论，不给错结论。</p>
 *
 * @param ruleId     规则 ID（{@link DiagnosticRule#id()}）
 * @param title      一句话结论（人类可读，如 "疑似内存泄漏"）
 * @param detail     依据细节（含具体数值/时间，供服主核对）
 * @param suggestion 参数级建议（可执行动作）
 * @param critical   true = 严重级（应尽快处理）；false = 警告级
 */
public record Diagnosis(
    String ruleId,
    String title,
    String detail,
    String suggestion,
    boolean critical
) {}
