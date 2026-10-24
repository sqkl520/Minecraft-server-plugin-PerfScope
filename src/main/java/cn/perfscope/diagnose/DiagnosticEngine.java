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
 * 互斥/优先级不进引擎（首批 5 条规则语义天然正交，无需互斥机制）。</p>
 *
 * <p><b>9A-3 修正（三态聚合）</b>：{@link #run} 现在返回 {@link Result}，
 * 里面区分「命中 / 评估后干净 / 未评估（含原因）」。旧实现只返回命中列表，
 * 于是展示层只能拿 {@code RULES.size()} 当"已检查 N 条"——而 3/5 条规则前置不成立时
 * 根本没运行。现在"评估了几条、跳过几条、为什么跳过"都是真实计数。</p>
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

    /** 已注册的规则数（= 引擎能力上限，不等于"本次实际评估数"）。 */
    public static int ruleCount() {
        return RULES.size();
    }

    /**
     * 一次诊断的完整结果（9A-3）。
     *
     * @param hits    命中列表（critical 在前）
     * @param skipped 未评估的规则及原因
     * @param total   注册的规则总数
     */
    public record Result(List<Diagnosis> hits, List<Diagnosis.Skip> skipped, int total) {

        /** 本次实际评估过的规则数（= 总数 - 跳过数）。 */
        public int evaluatedCount() {
            return total - skipped.size();
        }

        /** 是否所有规则都评估过（否则输出不能说"未发现已知问题"）。 */
        public boolean fullyEvaluated() {
            return skipped.isEmpty();
        }
    }

    /**
     * 对最新快照运行全部规则。
     *
     * <p>单条规则抛异常只跳过该条并记日志（异常隔离——与采集层 safeCollect 同口径），
     * 绝不让一条坏规则拖垮整页诊断；并且该条会计入"未评估"，不会伪装成"查过了没问题"。</p>
     */
    public Result run() {
        List<Diagnosis> hits = new ArrayList<>();
        List<Diagnosis.Skip> skipped = new ArrayList<>();
        MetricsSnapshot snap = plugin.getMetricsCollector() == null
            ? null : plugin.getMetricsCollector().getLatest();
        if (snap == null) {
            return new Result(hits, skipped, RULES.size()); // 无快照：命令层已处理"采集中"文案
        }
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg == null) {
            return new Result(hits, skipped, RULES.size());
        }
        for (DiagnosticRule rule : RULES) {
            try {
                Evaluation ev = rule.evaluate(snap, historyStore, cfg);
                if (ev == null) {
                    // 契约违约：旧式规则返回 null。不当作"干净"，如实记为未评估。
                    skipped.add(new Diagnosis.Skip(rule.id(), "规则未给出结论（实现未遵循三态契约）"));
                } else if (ev.hit() != null) {
                    hits.add(ev.hit());
                } else if (ev.skip() != null) {
                    skipped.add(ev.skip());
                }
                // ev.isClean() → 评估过且干净，两者都不计入
            } catch (Exception | LinkageError e) {
                skipped.add(new Diagnosis.Skip(rule.id(), "规则评估异常：" + e.getClass().getSimpleName()));
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
        return new Result(hits, skipped, RULES.size());
    }
}
