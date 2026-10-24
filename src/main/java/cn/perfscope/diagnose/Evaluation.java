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
 * 诊断规则的三态评估结果（9A-3）。
 *
 * <p>背景：旧契约用 {@code Diagnosis 或 null} 表达结果，导致「评估了且干净」与
 * 「因前置条件不成立根本没评估」在展示层完全无法区分——于是
 * "未发现已知问题（已检查 5 条）" 变成了假话（实际 3/5 条从未运行）。
 * 本类把第三态显式化。</p>
 *
 * <p>三种形态：</p>
 * <ul>
 *   <li>{@link #hit()} 非 null —— 命中，输出结论 + 建议</li>
 *   <li>{@link #isClean()} 为 true —— 评估了且未命中（"查过了，没事"）</li>
 *   <li>{@link #skip()} 非 null —— 无法评估，携带原因（"还缺什么"）</li>
 * </ul>
 */
public final class Evaluation {

    private final Diagnosis hit;
    private final boolean clean;
    private final Diagnosis.Skip skip;

    private Evaluation(Diagnosis hit, boolean clean, Diagnosis.Skip skip) {
        this.hit = hit;
        this.clean = clean;
        this.skip = skip;
    }

    /** 命中：返回结论。 */
    public static Evaluation of(Diagnosis diagnosis) {
        if (diagnosis == null) {
            throw new IllegalArgumentException("hit 不可为 null；未命中请用 clean()");
        }
        return new Evaluation(diagnosis, false, null);
    }

    /** 评估了且干净（明确的"查过了，没事"）。 */
    public static Evaluation clean() {
        return new Evaluation(null, true, null);
    }

    /** 无法评估：必须说明原因与"还缺什么"。 */
    public static Evaluation skip(String ruleId, String reason) {
        return new Evaluation(null, false, new Diagnosis.Skip(ruleId, reason));
    }

    public Diagnosis hit() {
        return hit;
    }

    public boolean isClean() {
        return clean;
    }

    public Diagnosis.Skip skip() {
        return skip;
    }
}
