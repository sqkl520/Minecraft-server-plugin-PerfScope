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
import cn.perfscope.util.Messages;

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
     * f-s03-data-06：代次校验的生效窗口——一个采集周期再留 20 秒余量，下限 30 秒、上限 5 分钟。
     *
     * <p>窗口外的"快照早于配置发布"意味着采集中断，而不是 reload 的正常过渡；
     * 此时仍照常评估（并由既有的过期/健康提示暴露采集问题），避免诊断永久沉默。</p>
     */
    private static long reloadGraceMs(ConfigManager.ConfigData cfg) {
        long cycleMs = Math.max(1, cfg.general_updateInterval) * 50L;   // 20 tick = 1 秒
        long window = cycleMs + 20_000L;
        return Math.min(300_000L, Math.max(30_000L, window));
    }

    /**
     * 一次诊断的完整结果（9A-3）。
     *
     * @param hits    命中列表（critical 在前）
     * @param skipped 未评估的规则及原因
     * @param total   评估基数：规则循环进入过 = 注册规则数；整轮未执行（无快照 / 无配置）= 0
     */
    public record Result(List<Diagnosis> hits, List<Diagnosis.Skip> skipped, int total) {

        /** 本次实际评估过的规则数（= 总数 - 跳过数）。 */
        public int evaluatedCount() {
            return total - skipped.size();
        }

        /** 是否所有规则都评估过（否则输出不能说"未发现已知问题"）。{@code total == 0} 时恒为 false。 */
        public boolean fullyEvaluated() {
            return total > 0 && skipped.isEmpty();
        }
    }

    /**
     * 对最新快照运行全部规则。
     *
     * <p>单条规则抛异常只跳过该条并记日志（异常隔离——与采集层 safeCollect 同口径），
     * 绝不让一条坏规则拖垮整页诊断；并且该条会计入"未评估"，不会伪装成"查过了没问题"。
     * 隔离覆盖 {@code Exception}、{@code LinkageError} 与 {@code StackOverflowError}（f-s03-err-04）；
     * 其余 {@code Error}（尤其 {@code OutOfMemoryError}）不捕获——吞掉它们后继续分配只会把真实故障藏起来。</p>
     */
    public Result run() {
        List<Diagnosis> hits = new ArrayList<>();
        List<Diagnosis.Skip> skipped = new ArrayList<>();
        MetricsSnapshot snap = plugin.getMetricsCollector() == null
            ? null : plugin.getMetricsCollector().getLatest();
        if (snap == null) {
            return new Result(hits, skipped, 0); // f-s03-logic-02：整轮未执行，不是"5/5 已评估"
        }
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg == null) {
            return new Result(hits, skipped, 0);
        }
        // f-s03-data-06：代次校验。snap 的 unavailable/disabledModules 是采集那一刻按当时配置算的，
        // cfg 的阈值是现在这一代。reload 后到下一个采集周期之间，两者属于不同代次——
        // 拼在一起求值会输出"旧配置的可用性 + 新配置的阈值"这种自相矛盾的结论。
        // 不假装干净、也不报 0/0：逐条登记为未评估，原因写明代次不符。
        // 只在一个采集周期 + 余量的窗口内生效：超出窗口仍不匹配说明采集本身已停摆，
        // 那时"代次"不是首要问题，照常评估并交由既有的过期/健康提示暴露，避免永久沉默。
        long publishedAt = configManager.publishedAtMillis();
        if (publishedAt > 0L && snap.timestamp < publishedAt
            && System.currentTimeMillis() - publishedAt <= reloadGraceMs(cfg)) {
            for (DiagnosticRule rule : RULES) {
                skipped.add(new Diagnosis.Skip(rule.id(), Messages.get("diag.skip.stale-config")));
            }
            return new Result(hits, skipped, RULES.size());
        }
        for (DiagnosticRule rule : RULES) {
            try {
                Evaluation ev = rule.evaluate(snap, historyStore, cfg);
                if (ev == null) {
                    // 契约违约：旧式规则返回 null。不当作"干净"，如实记为未评估。
                    skipped.add(new Diagnosis.Skip(rule.id(), Messages.get("diag.skip.no-conclusion")));
                } else if (ev.hit() != null) {
                    hits.add(ev.hit());
                } else if (ev.skip() != null) {
                    skipped.add(ev.skip());
                }
                // ev.isClean() → 评估过且干净，两者都不计入
            } catch (Exception | LinkageError e) {
                skipped.add(new Diagnosis.Skip(rule.id(),
                    Messages.get("diag.skip.rule-exception", e.getClass().getSimpleName())));
                if (cfg.general_debug) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                        Messages.get("diag.log.rule-failed", rule.id()), e);
                } else {
                    plugin.getLogger().warning(Messages.get("diag.log.rule-failed-detail",
                        rule.id(), e.getMessage()));
                }
            } catch (StackOverflowError e) {
                // f-s03-err-04：VirtualMachineError 不经 Exception / LinkageError 捕获，会直接逃出
                // run()，让整页诊断失败——恰在本插件最该自证的场景（内存压力）成立。只收 StackOverflowError：
                // 它可恢复（栈随帧弹出自动回到正常），而 OutOfMemoryError 捕获后继续分配毫无意义。
                skipped.add(new Diagnosis.Skip(rule.id(),
                    Messages.get("diag.skip.rule-exception", e.getClass().getSimpleName())));
                plugin.getLogger().warning(Messages.get("diag.log.rule-failed-detail",
                    rule.id(), e.getMessage() == null ? "stack overflow" : e.getMessage()));
            }
        }
        // critical 优先（排序稳定：同为 critical 保持注册顺序）
        hits.sort((a, b) -> Boolean.compare(b.critical(), a.critical()));
        return new Result(hits, skipped, RULES.size());
    }
}
