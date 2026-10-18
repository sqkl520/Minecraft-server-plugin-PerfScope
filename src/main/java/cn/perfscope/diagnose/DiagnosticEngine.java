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

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.history.HistoryStore;
import cn.perfscope.metrics.MetricsSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * 诊断引擎（Wave 8A）——"规则即对象注册表"的执行器。
 *
 * <p>设计取舍（锐评 B 论点 7）：37 条规则<b>不</b>平铺进巨型求值器，而是每条规则一个实现类、
 * 注册进 {@link #RULES} 列表；引擎只做三件事：取快照 → 逐条评估（异常隔离）→ 按级别排序输出。
 * 互斥/优先级不进引擎（首批 5 条规则语义天然正交，无需互斥机制；加规则时按需引入，
 * 避免为假想复杂度预付设计成本）。</p>
 *
 * <p><b>无规则命中时输出什么</b>：明确说"未发现已知问题"，并且<b>声明能力边界</b>
 * （"本引擎只覆盖 N 条高置信规则，不替代 spark profiler 的深度剖析"）——诚实划界是
 * 与"看起来答了实际答错"的分界线。</p>
 *
 * <p>线程模型：由命令层在主线程调用；规则只读快照与历史（HistoryStore 自带锁），无共享可变状态。</p>
 */
public class DiagnosticEngine {

    /** 规则注册表（顺序 = 评估顺序；输出按 critical 优先排序，与注册顺序无关）。 */
    private static final List<DiagnosticRule> RULES = List.of(
        new CoreRules.MemoryLeakRule(),
        new CoreRules.EntityHotspotRule(),
        new CoreRules.TpsPersistentRule(),
        new CoreRules.PlayerCapacityRule(),
        new CoreRules.MsptSpikeRule()
    );

    private final PerfScope plugin;
    private final ConfigManager configManager;
    private final HistoryStore historyStore;

    public DiagnosticEngine(PerfScope plugin, HistoryStore historyStore) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.historyStore = historyStore;
    }

    /** 已注册的规则数（供输出展示能力边界）。 */
    public static int ruleCount() {
        return RULES.size();
    }

    /**
     * 对最新快照运行全部规则。
     * 单条规则抛异常只跳过该条并记日志（异常隔离——与采集层 safeCollect 同口径），
     * 绝不让一条坏规则拖垮整页诊断。
     *
     * @return 命中列表（critical 在前）；无命中返回空列表（非 null）
     */
    public List<Diagnosis> run() {
        List<Diagnosis> hits = new ArrayList<>();
        MetricsSnapshot snap = plugin.getMetricsCollector() == null
            ? null : plugin.getMetricsCollector().getLatest();
        if (snap == null) {
            return hits; // 无快照：命令层已处理"采集中"文案
        }
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg == null) {
            return hits;
        }
        for (DiagnosticRule rule : RULES) {
            try {
                Diagnosis d = rule.evaluate(snap, historyStore, cfg);
                if (d != null) {
                    hits.add(d);
                }
            } catch (Exception | LinkageError e) {
                // 单条失败只记日志（debug 门控堆栈），不中断其余规则
                if (cfg.general_debug) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "[诊断] 规则 " + rule.id() + " 评估失败（已跳过）", e);
                } else {
                    plugin.getLogger().warning("[诊断] 规则 " + rule.id()
                        + " 评估失败（已跳过）: " + e.getMessage());
                }
            }
        }
        // critical 优先（排序稳定：同为 critical 保持注册顺序）
        hits.sort((a, b) -> Boolean.compare(b.critical(), a.critical()));
        return hits;
    }
}
