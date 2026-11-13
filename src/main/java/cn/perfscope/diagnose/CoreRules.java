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
import cn.perfscope.history.HistorySample;
import cn.perfscope.history.HistoryStore;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.util.DurationFormat;
import cn.perfscope.util.Messages;

import java.util.List;

/**
 * 首批 5 条高置信诊断规则（Wave 8A；9A-3/9A-8 修正）。
 *
 * <p><b>9A-3 修正（三态）</b>：每条规则不再用 {@code return null} 表达"不结论"，
 * 而是明确区分：命中 / 评估了且干净 / 无法评估（含原因）。旧实现在 3/5 条规则前置不成立时
 * 静默返回 null，展示层却统一报"已检查 5 条"，是假话。</p>
 *
 * <p><b>9A-8 修正（可信度）</b>：</p>
 * <ul>
 *   <li>覆盖率取代样本数：{@code MIN_SAMPLES} 只保证"有几条"，不保证"覆盖多久"。
 *       默认 60 秒间隔下 10 个样本只覆盖 9 分钟，却用它断言"30 分钟窗口"——现要求
 *       首尾跨度至少达到"这批样本自身的时间密度"所对应的长度（见 {@link #coverageProblem}）。</li>
 *   <li>ENT-HOTSPOT 阈值量纲：原用全服阈值（2000/2500）除以 2 去卡<b>单个区块</b>，
 *       等于要求单区块 1000+ 实体才报警，实际永不触发。现改为独立的单区块阈值。</li>
 *   <li>建议文案全部改为<b>真实存在且游戏内可完成</b>的动作（原指向 Paper 1.19+ 已删除的
 *       {@code paper.yml}、要求服主开 shell 跑 jmap/MAT）。</li>
 *   <li>PLAYER-CAP 不再跳过 0 人在线样本（0 是真实低谷），文案不再写死"近 15 分钟"。</li>
 * </ul>
 */
public final class CoreRules {

    private CoreRules() {}

    /**
     * 覆盖判据见 {@link #coverageProblem}：样本条数 + 样本自身的时间密度。
     *
     * <p>（历史：曾用"首尾跨度 ≥ 窗口 × 0.6"的比例判据，但它在刚重启的服务器上
     * 会让趋势规则长时间完全不可用——30 分钟窗要等 18 分钟才能出结论。）</p>
     */

    /**
     * 历史窗口覆盖是否足够（9A-8）。
     *
     * <p>两个判据都要满足：① 样本条数 ≥ minSamples（够算统计量）；
     * ② 首尾跨度 ≥ 判据实际采用的下限 {@code needed}，其中
     * {@code needed = max(窗口内中位间隔 × (样本数-1) × 0.8, 窗口长度 × 10%)}。</p>
     *
     * <p>为什么用「样本间隔」而不是「窗口比例」：旧实现只看条数，在 5 秒采样下
     * 45 秒数据就能断言"30 分钟趋势"（8 个样本）；但若强求窗口的 60%，刚重启的服务器
     * 又会在长时间内完全无法诊断（30 分钟窗要等 18 分钟）。以"这批样本自身的时间密度"
     * 为准是更准确的口径：它保证样本确实是分散在一段时间上的，而不是挤在一瞬间。</p>
     *
     * <p>f-s03-contract-08：上述公式与 {@code absoluteFloor} 原仅记载于方法体注释，
     * javadoc 曾写成 {@code minSamples × 采样间隔 × 0.8}（既不含中位间隔，也不含 10% 下限），
     * 与实现不符。此处按实现改写，不作行为变更。</p>
     *
     * @return 覆盖不足时返回人类可读原因（供 skip），足够时返回 null
     */
    private static String coverageProblem(List<HistorySample> window, long windowMs, int minSamples,
                                          String what) {
        if (window.size() < minSamples) {
            return Messages.get("diag.skip.coverage-samples", what, window.size(), minSamples);
        }
        long span = window.get(window.size() - 1).timestamp() - window.get(0).timestamp();
        // 用窗口内样本的实际中位间隔推"这批样本本应跨越多长时间"
        long[] gaps = new long[window.size() - 1];
        for (int i = 1; i < window.size(); i++) {
            gaps[i - 1] = window.get(i).timestamp() - window.get(i - 1).timestamp();
        }
        java.util.Arrays.sort(gaps);
        // f-s03-logic-04：偶数个间隔时取中间两个的均值才是中位数；原实现只取 gaps[L/2]（上中位数），
        // 恰好把"一半很短、一半很长"的窗口判成覆盖不足（只产生 skip，不产生误报，故为 LOW）。
        long medianGap = gaps.length == 0 ? 0
            : (gaps.length % 2 == 1 ? gaps[gaps.length / 2]
                : (gaps[gaps.length / 2 - 1] + gaps[gaps.length / 2]) / 2);
        // 期望跨度 = 中位间隔 × (样本数-1)，允许 20% 抖动
        long expected = (long) (medianGap * (window.size() - 1) * 0.8);
        // 同时保有绝对下限：至少覆盖窗口的 10%（防止"1 秒内 10 条样本"这种极端）
        long absoluteFloor = (long) (windowMs * 0.10);
        long needed = Math.max(expected, absoluteFloor);
        if (span < needed) {
            // 时长文案统一走 DurationFormat：单位词随语言切换，数值不受本地化影响
            return Messages.get("diag.skip.coverage-span", what,
                DurationFormat.duration(span), DurationFormat.duration(needed));
        }
        return null;
    }

    // ==================== DIAG-MEM-LEAK：疑似内存泄漏（启发式） ====================

    /**
     * 泄漏启发式：窗口内堆占用持续抬升，且窗口后半段谷值未回落到起点。
     *
     * <p>措辞纪律（9A-8）：原 detail 声称"GC 后水位未回落"，但代码从未做过 GC 后测量。
     * 现在只陈述<b>观测到的事实</b>（窗口内最低点仍高于起点），不声称做过 GC 后采样。</p>
     *
     * <p>v1.4 备注：{@code HistorySample} 现在<b>已带</b> {@code gcCount}/{@code gcTime}
     * （CSV schema 加列），因此"GC 后测量"如今在数据上是可行的——但本规则仍不做，
     * 它需要按 GC 事件对齐样本边界（花括号式切割），而不是简单地"取最近一次 GC 之后的样本"。
     * 在做到那一步之前，本规则的措辞仍必须停在"观测到的事实"，不得暗示做过 GC 后采样。</p>
     *
     * <p><b>缺采前置检查（F-1 / F-2）</b>：{@code heapUsagePercent == 0} 是"这条样本没采到
     * 内存"的哨兵（{@code HistoryStore} 只按时间过滤，不剔除 0 值，所以哨兵能真的走到规则体里）。
     * 旧实现只校验首尾两个点、且校验位置在 {@code growth} 判定<b>之后</b>，后果有两条：</p>
     * <ul>
     *   <li><b>F-1</b>：12 条里 10 条缺采（{@code [85, 0×10, 86]}）时 {@code growth = 1 < 8}
     *       先返回 CLEAN，"查过了没事"——而规则根本没看过后半段那 10 条。</li>
     *   <li><b>F-2</b>：缺采落在窗口<b>中间</b>（{@code [80, 0,0,0,0, 90,92,…]}）时首尾都有效，
     *       growth 足够 → HIT，detail 声称"最低点仍为 92%"——把 4 条<b>缺失</b>样本排除在
     *       "窗口最低"之外，读者会以为 12 条都有读数。</li>
     * </ul>
     * <p>现在把检查放到所有判据之前，并且要求<b>窗口内每一条样本都有读数</b>：
     * 只有这样才能说"窗口最低点"，否则那个"最低"只是一组有洞的数据上的局部最小。
     * 缺采样本数不为 0 时一律 SKIP（不是 CLEAN：没看全就不能说没事）。
     * 不需要额外判"可读样本够不够 {@code MIN_SAMPLES}"——覆盖判据已保证
     * {@code window.size() >= MIN_SAMPLES}，缺采 &gt; 0 在这里就先返回了。
     * 代价是<b>一条坏样本会让本规则沉默一整个窗口</b>（默认 30 分钟）——这是刻意选的：
     * 相比"在 10/12 条缺采的窗口上给出泄漏结论"，沉默并说明原因才是诚实的方向。
     * 覆盖判据（{@link #coverageProblem}）仍按窗口内<b>全部</b>样本计，缺采检查在其后。</p>
     */
    public static final class MemoryLeakRule implements DiagnosticRule {
        private static final long WINDOW_MINUTES = 30;
        private static final int MIN_HEAP_GROWTH_PERCENT = 8;   // 窗口内堆占用抬升 ≥8 个百分点
        private static final int MIN_SAMPLES = 10;

        @Override
        public String id() {
            return "DIAG-MEM-LEAK";
        }

        @Override
        public Evaluation evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_MEMORY)) {
                return Evaluation.skip(id(), Messages.get("diag.skip.memory-unavailable"));
            }
            if (snap.heapUsagePercent < cfg.memory_heapWarningThreshold) {
                return Evaluation.skip(id(), Messages.get("diag.skip.heap-below-threshold",
                    snap.heapUsagePercent, cfg.memory_heapWarningThreshold));
            }
            long windowMs = WINDOW_MINUTES * 60_000L;
            List<HistorySample> window = history.since(System.currentTimeMillis() - windowMs);
            String cov = coverageProblem(window, windowMs, MIN_SAMPLES, Messages.get("diag.label.history"));
            if (cov != null) {
                return Evaluation.skip(id(), cov);
            }
            // F-1 / F-2：缺采检查必须在 growth 判定之前，且覆盖窗口内每一条样本。
            // 只有零缺采时，"窗口最低点"才是一个可以对外陈述的事实；否则那个"最低"
            // 只是一组有洞的数据上的局部最小。可读样本是否够 MIN_SAMPLES 不必再单独判——
            // 覆盖判据已保证 window.size() >= MIN_SAMPLES，缺采 > 0 时这里一律先返回。
            int missing = 0;
            for (HistorySample s : window) {
                if (s.heapUsagePercent() <= 0) {
                    missing++;
                }
            }
            if (missing > 0) {
                return Evaluation.skip(id(), Messages.get("diag.skip.heap-sample-missing",
                    missing, window.size()));
            }
            // 走到这里 ⇒ 窗口内每条样本的 heapUsagePercent 都 > 0，首尾取值必然有效
            HistorySample first = window.get(0);
            HistorySample last = window.get(window.size() - 1);
            int growth = last.heapUsagePercent() - first.heapUsagePercent();
            if (growth < MIN_HEAP_GROWTH_PERCENT) {
                return Evaluation.clean();
            }
            // 后半段谷值仍高于起点 → 抬升是持续性的，不是一次尖峰后回落
            int minInLaterHalf = Integer.MAX_VALUE;
            for (int i = window.size() / 2; i < window.size(); i++) {
                minInLaterHalf = Math.min(minInLaterHalf, window.get(i).heapUsagePercent());
            }
            if (minInLaterHalf < first.heapUsagePercent()) {
                return Evaluation.clean(); // 回落过 → 更像正常波动
            }
            long minutes = Math.max(1, (last.timestamp() - first.timestamp()) / 60_000L);
            return Evaluation.of(new Diagnosis(id(), Messages.get("diag.DIAG-MEM-LEAK.title"),
                Messages.get("diag.DIAG-MEM-LEAK.detail", minutes, first.heapUsagePercent(),
                    last.heapUsagePercent(), growth, minInLaterHalf, snap.heapUsagePercent,
                    window.size()),
                Messages.get("diag.DIAG-MEM-LEAK.suggestion", snap.heapMax / 1024 / 1024),
                true));
        }
    }

    // ==================== DIAG-ENT-HOTSPOT：热点区块（下钻到坐标） ====================

    /**
     * 热点区块：Top1 区块实体数显著偏高时给出坐标级结论。
     *
     * <p>9A-8 修正：原实现用<b>全服</b>阈值（默认 2000/2500）除以 2 去卡<b>单个区块</b>，
     * 即要求单区块 1000+ 实体——真实服务器上单区块堆几百只怪就该报警，因此该规则
     * 在默认配置下<b>永不触发</b>（v1.2.0 带着一条死规则发布）。
     * 现在改为独立的单区块阈值（{@code hotspots.chunk-warning-threshold}，默认 200）。</p>
     *
     * <p><b>严重线必须跟着命中线走（F-5）</b>：旧实现把严重线写死成 {@code CHUNK_CRITICAL = 500}，
     * 而命中线是 {@code max(100, cfg.hotspots_chunkWarningThreshold)}。两者量纲不联动，于是
     * 服主把阈值调到 1000（"我觉得 1000 才值得一看"）后，一个<b>刚好达到他自己那条线</b>的区块
     * 会被判 critical，而 detail 里同时写着"单区块阈值 1000"——读起来像配置没生效。</p>
     *
     * <p>现取 {@code critical = count >= warnThreshold × 2.5}，其中 warnThreshold 是<b>生效</b>
     * 命中线（含 {@code ABSOLUTE_FLOOR} 托底）。选 2.5 的理由：</p>
     * <ul>
     *   <li><b>默认配置下零行为变化</b>：{@code 200 × 2.5 = 500}，与 v1.2 手校准过的默认严重线
     *       逐位相同。取项目里 entities/chunks 惯用的 1.5 会变成 300，把 300–499 实体的区块
     *       从警告升级为严重——那是给所有用默认配置的服新增噪音，而本缺陷只影响"自己抬高了阈值"
     *       的那部分人。</li>
     *   <li><b>不变式：严重线永远严格高于命中线</b>。倍数 &gt; 1 即保证
     *       {@code count >= warnThreshold × K} ⇒ {@code count > warnThreshold}，
     *       所以"刚达到命中线"永远只是警告。倍数与绝对下限无关，因此配置调到 20 时
     *       严重线是 {@code 100 × 2.5 = 250}（生效命中线 100 的 2.5 倍），仍满足不变式。</li>
     *   <li>为什么不用"warn + 绝对增量"（如 {@code warn + 300}）：配置为 20（被托到 100）时
     *       严重线会是 400，等于"警告 100 / 严重 400"，跨度过大使得小服几乎见不到严重级；
     *       而配置为 5000 时又是 5300，几乎等同命中线，两者都不如比例式稳定。</li>
     * </ul>
     * <p>与现有配置项的关系：{@code config.yml} 里 {@code entities}/{@code chunks} 的
     * warning/critical 是<b>两两成对</b>的可配置键（默认比例 1:1.5），而 {@code hotspots} 段
     * 只有 {@code chunk-warning-threshold} 一个键，没有 critical 键可复用。新增一个配置键
     * 属扩展而非修复（且会让 F-5 的"配置看起来没生效"换个形式重现：新键缺省时又要回退），
     * 故此处用比例式派生，不新增键。</p>
     */
    public static final class EntityHotspotRule implements DiagnosticRule {
        /** 绝对下限：低于此数不谈"热点"（小服几十只动物属正常）。 */
        private static final int ABSOLUTE_FLOOR = 100;
        /**
         * 严重线相对<b>生效命中线</b>的倍数（F-5）。必须 &gt; 1，否则严重线会落在命中线上或更低。
         * {@code 200 × 2.5 = 500} 复现 v1.2 校准过的默认严重线。
         */
        private static final double CRITICAL_MULTIPLIER = 2.5;

        @Override
        public String id() {
            return "DIAG-ENT-HOTSPOT";
        }

        @Override
        public Evaluation evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_HOTSPOTS)) {
                return Evaluation.skip(id(), Messages.get("diag.skip.hotspots-unavailable"));
            }
            if (snap.topEntityChunks.isEmpty()) {
                return Evaluation.skip(id(), Messages.get("diag.skip.hotspots-not-ready"));
            }
            var top = snap.topEntityChunks.get(0);
            int warnThreshold = Math.max(ABSOLUTE_FLOOR, cfg.hotspots_chunkWarningThreshold);
            if (top.entityCount() < warnThreshold) {
                return Evaluation.clean(); // 最高的那个区块都没到线
            }
            // F-5：严重线由生效命中线派生，保证 critical ⇒ 已越过命中线。
            int criticalThreshold = (int) Math.ceil(warnThreshold * CRITICAL_MULTIPLIER);
            return Evaluation.of(new Diagnosis(id(), Messages.get("diag.DIAG-ENT-HOTSPOT.title"),
                Messages.get("diag.DIAG-ENT-HOTSPOT.detail", top.world(), top.chunkX(), top.chunkZ(),
                    top.blockX(), top.blockZ(), top.entityCount(), warnThreshold,
                    top.dominantType(), top.dominantPercent()),
                Messages.get("diag.DIAG-ENT-HOTSPOT.suggestion", top.blockX(), top.blockZ()),
                top.entityCount() >= criticalThreshold));
        }
    }

    // ==================== DIAG-TPS-PERSIST：TPS 持续低位（趋势判定） ====================

    /**
     * TPS 持续低位：区别于"此刻"告警——窗口持续偏低，属结构性问题。
     *
     * <p>9A-8 修正：优先用快照自带的 {@code tps15m}（服务端 15 分钟滚动均值）作为
     * "持续"的主判据；{@code tps1m} 只用于"当前是否仍在恶化"。历史窗口仍要求覆盖达标。</p>
     *
     * <p><b>零值口径（F-3 / F-4）</b>：本规则的 TPS 字段<b>没有</b> {@code -1} 哨兵——
     * {@link MetricsSnapshot} 的 {@code tps1m/tps5m/tps15m} 由采集层直接写入
     * {@code Bukkit.getTPS()}，而 TPS 不可用时的快照根本不会进历史环
     * （{@link HistoryStore#record} 的污染防护：{@code unavailable} 含 {@code MODULE_TPS} 即整条不记）。
     * 因此在<b>本规则能看到的数据里</b>，{@code 0.0} 基本只可能是"服务器真的没有 tick"——
     * 最坏的真实读数，必须参与判定，不能当"未知"丢掉：</p>
     * <ul>
     *   <li>快照侧：{@code tps15m == 0} 命中告警（且必然 critical），不再被 {@code > 0} 过滤掉；</li>
     *   <li>历史侧：{@code tps1m == 0} 参与窗口均值与最低点（旧实现把它当垃圾丢掉，
     *       全 0 窗口会得出"有效样本 0/5 → 无法评估"，等于在最该报警时失声）。</li>
     * </ul>
     * <p><b>唯一已知的例外（不隐瞒）</b>：Folia 的 global region 在<b>启动初期</b>会短暂报表空/
     * 接近 0（见 {@code AlertService} 的 9B-6 启动抑制窗口，它专为此存在），且此时
     * {@code MODULE_TPS} 是"可用"的、样本会正常入环。本规则不做平台分支（告警层已按服务端类型处理），
     * 实际影响被覆盖判据挡住：命中需要 10 分钟窗内 ≥5 条样本且跨度达标，而那个空窗按设计是"短暂"的。
     * 若将来把采样间隔调得很大或提高抑制窗口，这里需要重新评估。</p>
     * <p>仍然过滤的只有"不可能是读数"的值，两侧口径一致（{@code 0} 放行）：
     * {@code > 20.5} 的不可能读数、负数、以及 {@code NaN}/{@code Infinity}（服务端异常返回，
     * 无法解释成任何读数，且会让均值变成 NaN）。唯一需要向用户标注"未上报"的情形就是后者——
     * 这也是 F-3 第二项要求的：{@code 0.00} 必须作为真实读数出现，只有真正没上报时才换标签。</p>
     */
    public static final class TpsPersistentRule implements DiagnosticRule {
        private static final long WINDOW_MINUTES = 10;
        private static final int MIN_SAMPLES = 5;
        /** 物理上不可能的读数上限（含 1 tick 抖动余量）；超过它只可能是采集异常，不是真实 TPS。 */
        private static final double TPS_CEILING = 20.5;

        @Override
        public String id() {
            return "DIAG-TPS-PERSIST";
        }

        @Override
        public Evaluation evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
                return Evaluation.skip(id(), Messages.get("diag.skip.tps-unavailable"));
            }
            long windowMs = WINDOW_MINUTES * 60_000L;
            List<HistorySample> window = history.since(System.currentTimeMillis() - windowMs);
            String cov = coverageProblem(window, windowMs, MIN_SAMPLES, Messages.get("diag.label.history"));
            if (cov != null) {
                return Evaluation.skip(id(), cov);
            }
            double sum = 0;
            int count = 0;
            HistorySample lowest = null;
            for (HistorySample s : window) {
                // F-4：只过滤"物理上不可能"的读数与非有限值。**不能**过滤 <= 0——
                // TPS 不可用期的快照根本不进环（见类注释），所以环里的 0 是真实的 0 TPS。
                if (!(s.tps1m() >= 0) || s.tps1m() > TPS_CEILING) {
                    continue;
                }
                sum += s.tps1m();
                count++;
                if (lowest == null || s.tps1m() < lowest.tps1m()) {
                    lowest = s;
                }
            }
            if (count < MIN_SAMPLES || lowest == null) {
                return Evaluation.skip(id(), Messages.get("diag.skip.tps-samples", count, MIN_SAMPLES));
            }
            double avg1m = sum / count;
            // f-s03-logic-05（产品裁决 2026-11-04：保持 OR）：两个判据地位对等，任一口径低于
            // 告警线即命中——15 分钟滚动均值（服务端自带，天然平滑）负责"久低"，窗口均值负责
            // "刚崩"。改 AND 会减少误报但漏报"刚发生的大幅下跌"，故不取值域收窄。
            // 文案侧已同步为"任一口径低于告警线即命中"，规则语义零变更。
            //
            // F-3：15 分钟判据不再要求 tps15m > 0。该字段无 -1 哨兵，0.0 = 彻底冻结；
            // 用 > 0 把它当未知，会让"最坏的真实读数"得出 CLEAN。这里只挡"物理上不可能是读数"
            // 的值（负数 / NaN / Infinity）——它们与窗口侧的过滤口径保持一致（见上面的循环），
            // 否则会出现"窗口滤掉了 -1，快照却拿 -1 判成 critical"这种自相矛盾。
            boolean tps15mReported = Double.isFinite(snap.tps15m) && snap.tps15m >= 0;
            boolean persist15m = tps15mReported && snap.tps15m < cfg.tps_warningThreshold;
            boolean persistWindow = avg1m < cfg.tps_warningThreshold;
            if (!persist15m && !persistWindow) {
                return Evaluation.clean();
            }
            long minutes = Math.max(1, (window.get(window.size() - 1).timestamp() - window.get(0).timestamp()) / 60_000L);
            // 数值格式保持原样（Locale.ROOT + %.2f）：翻译只换语序，不碰数字口径。
            // F-3：0.00 是真实读数，照常打印；只有"服务端根本没上报"时才换成显式标签，
            // 绝不让 NaN 之类的东西带着格式走进用户视线。
            return Evaluation.of(new Diagnosis(id(), Messages.get("diag.DIAG-TPS-PERSIST.title"),
                Messages.get("diag.DIAG-TPS-PERSIST.detail",
                    tps15mReported ? String.format(java.util.Locale.ROOT, "%.2f", snap.tps15m)
                        : Messages.get("diag.value.not-reported"),
                    minutes,
                    String.format(java.util.Locale.ROOT, "%.2f", avg1m),
                    cfg.tps_warningThreshold,
                    String.format(java.util.Locale.ROOT, "%.2f", lowest.tps1m())),
                Messages.get("diag.DIAG-TPS-PERSIST.suggestion"),
                tps15mReported && snap.tps15m < cfg.tps_criticalThreshold));
        }
    }

    // ==================== DIAG-PLAYER-CAP：玩家数持续接近上限 ====================

    /**
     * 玩家容量：持续接近上限（含历史验证，区别于瞬时峰值）。
     *
     * <p>9A-8 修正：原实现 {@code if (s.playerCount() > 0)} 跳过 0 人在线样本，
     * 而文案写死"近 15 分钟低位也在 X% 以上"——凌晨 0 人 + 早上 9 人上线时，
     * 它会看着 14 个 0 人样本得出"持续接近上限"的反结论。现 0 人样本参与统计，
     * 文案按实际覆盖时间表述。</p>
     *
     * <p><b>阈值不可用时的防御（F-7）</b>：{@code players.warning_threshold = 0} 能通过
     * {@code ConfigManager.sanitizeIntPair} 的校验（它只拦负数与 &gt;1e7），于是旧实现里
     * {@code currentPercent < 0} 恒假、{@code minPercent < 0-10} 恒假、{@code currentPercent >= 0}
     * 恒真——1 人在线 / 上限 200 也会输出一条 critical 的"接近上限"。配置层正在补
     * "阈值必须 &gt; 0" 的校验，那是<b>根因修复</b>；这里做的是<b>规则侧防御</b>，
     * 覆盖配置层拦不住的情形（有人直接 new 一个 {@code ConfigData}、或未来校验被改坏）。</p>
     * <ul>
     *   <li><b>分工</b>：配置层负责"错误配置进不来"并给出可操作的 warning；规则层负责
     *       "错误配置万一进来了，不要产生荒谬结论"。两层都做，任一层失效时另一层仍成立。</li>
     *   <li><b>规则层不猜默认值</b>：把 0 悄悄替换成 90 会掩盖配置错误（服主永远不知道自己的
     *       配置没生效），这正是本项目"不让盲区假装成健康"那条原则的反面。因此改为
     *       <b>SKIP + 说明阈值不可用</b>：没说好，也没说坏。</li>
     *   <li><b>两条阈值各自守自己的判据</b>：{@code warning <= 0} 时命中判据本身失效
     *       （{@code currentPercent < 0} 恒假），后续一切无从谈起，直接 skip；
     *       {@code critical <= 0} 时命中判据仍然有效，只是"严重"这一档失去意义
     *       （{@code currentPercent >= 0} 恒真），因此命中照常输出、但严重度退回 false。</li>
     * </ul>
     *
     * <p><b>窗口低谷只报原始计数（F-8）</b>：{@code HistorySample} 只存
     * {@code playerCount}，<b>不存当时的 maxPlayers</b>（CSV 12 列 schema 里没有这一列）。
     * 旧 detail 把历史玩家数除以<b>当前</b>上限再折算成百分比，声称"近 N 分钟最低也有 X%"——
     * 容量从 200 缩到 100 时，历史 95 人当时是 47.5%，却被算成 95% 写进 detail。
     * 判定本身仍然成立（当前 95/100 确实贴顶），但那个百分比是<b>从未发生过的数字</b>。
     * 现在 detail 只陈述历史里真实存储的<b>玩家数</b>，让读者自己对照当前上限判断——
     * 给 {@code HistorySample} 加列会把 CSV 从 12 列变 13 列，影响旧文件兼容与
     * {@code CsvLayoutTest} 的 15 个用例，收益不足以抵消，故不做。</p>
     */
    public static final class PlayerCapacityRule implements DiagnosticRule {
        private static final long WINDOW_MINUTES = 15;
        private static final int MIN_SAMPLES = 5;

        @Override
        public String id() {
            return "DIAG-PLAYER-CAP";
        }

        @Override
        public Evaluation evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
                return Evaluation.skip(id(), Messages.get("diag.skip.players-unavailable"));
            }
            if (snap.maxPlayers <= 0) {
                return Evaluation.skip(id(), Messages.get("diag.skip.max-players-not-ready", snap.maxPlayers));
            }
            // F-7：阈值 0/负数不是"最严格的门槛"，而是"没有门槛"。用它判会恒真。
            if (cfg.players_warningThreshold <= 0) {
                return Evaluation.skip(id(), Messages.get("diag.skip.players-threshold-invalid",
                    cfg.players_warningThreshold));
            }
            // 9A-6：统一走 MetricsSnapshot 的算法（此前这里是 Math.round、命令层 int 截断、采集层 double 截断）
            int currentPercent = snap.playerPercent();
            if (currentPercent < cfg.players_warningThreshold) {
                return Evaluation.clean();
            }
            long windowMs = WINDOW_MINUTES * 60_000L;
            List<HistorySample> window = history.since(System.currentTimeMillis() - windowMs);
            String cov = coverageProblem(window, windowMs, MIN_SAMPLES, Messages.get("diag.label.history"));
            if (cov != null) {
                return Evaluation.skip(id(), cov);
            }
            // F-8：最低点按<b>原始玩家数</b>求，不折算历史百分比（历史没有当时的容量）。
            int minPlayers = Integer.MAX_VALUE;
            for (HistorySample s : window) {
                // 0 人是真实低谷，必须参与统计（原实现跳过它导致"持续满员"的假结论）
                minPlayers = Math.min(minPlayers, s.playerCount());
            }
            // "窗口内有没有明显低谷"仍按当前上限折算成百分比比较——这个折算不冒充历史事实，
            // 它只是把历史玩家数放到今天的容量尺度上衡量（口径与 currentPercent 一致）。
            if (MetricsSnapshot.playerPercentOf(minPlayers, snap.maxPlayers) < cfg.players_warningThreshold - 10) {
                return Evaluation.clean(); // 窗口内有明显低谷：属活动峰值而非容量问题
            }
            long minutes = Math.max(1, (window.get(window.size() - 1).timestamp() - window.get(0).timestamp()) / 60_000L);
            // F-7：critical 阈值不可用时不下"严重"结论（否则 >= 0 恒真，每条命中都变 critical）
            boolean critical = cfg.players_criticalThreshold > 0
                && currentPercent >= cfg.players_criticalThreshold;
            return Evaluation.of(new Diagnosis(id(), Messages.get("diag.DIAG-PLAYER-CAP.title"),
                Messages.get("diag.DIAG-PLAYER-CAP.detail", snap.playerCount, snap.maxPlayers,
                    currentPercent, minutes, minPlayers),
                Messages.get("diag.DIAG-PLAYER-CAP.suggestion"),
                critical));
        }
    }

    // ==================== DIAG-MSPT-SPIKE：MSPT 尖刺（单 tick 冻结） ====================

    /**
     * MSPT 尖刺：max 远超 avg（瞬时冻结），玩家体感"顿一下"但均值正常。
     *
     * <p>9A-8 修正：原建议指向 {@code paper.yml}——该文件自 Paper 1.19 起已被拆分为
     * {@code config/paper-global.yml} 与 {@code config/paper-world-defaults.yml}，
     * 服主照抄会打开一个不存在的文件。现按 Paper 1.19+ 的真实布局给键路径，
     * 并把 spark 命令补全为 {@code /spark profiler start --only-ticks-over 200}。</p>
     *
     * <p><b>三个 MSPT 严重度口径（F-6 摸底结果）</b>：</p>
     * <ol>
     *   <li>{@code MetricsCollector.collectMSPT}：{@code snap.msptAvg > cfg.mspt_criticalThreshold}
     *       → 发 critical <b>告警</b>（默认 50ms）；</li>
     *   <li>{@code ThresholdColors.levelOf(MSPT, …)}：{@code value >= cfg.mspt_criticalThreshold}
     *       → 染红（命令层/报告层）。与 ① 只差等号归属（{@code >} vs {@code >=}），
     *       同一个配置源，可视为同口径；</li>
     *   <li>本规则：{@code snap.msptMax > 1000.0}（硬编码），量的是<b>峰值</b>。</li>
     * </ol>
     * <p>①② 与 ③ 量的是<b>不同的东西</b>——均值负载 vs 单拍冻结。这个区分是真实的，不该抹平：
     * 均值 12ms 但有一拍 1500ms 的服与均值 60ms 的服是两种病，前者要查区块保存/GC，
     * 后者要查整体负载。所以本规则<b>不</b>改成"avg 越线即 critical"（那会与告警区完全重复、
     * 并把"尖刺"这件事从诊断里抹掉）。</p>
     *
     * <p><b>但同一屏不能出现互相否定的结论</b>。旧实现的毛病在这里：{@code msptAvg=60}
     * （① 已判定 critical 并推到玩家眼前）、{@code msptMax=200} 时，本规则命中但给出
     * {@code critical=false}，于是在"命中列表"里排到末尾、染成黄色——告警区说"严重"、
     * 诊断区把同一条数据当"小问题"。现把最严重度取两者并集：</p>
     * <ul>
     *   <li>{@code msptMax > 1000} → critical（一秒冻结，与均值无关）；</li>
     *   <li><b>或</b> {@code msptAvg > cfg.mspt_criticalThreshold} → critical
     *       （告警层已经这么判了，诊断层不得反向否认）；</li>
     *   <li>否则 warning。</li>
     * </ul>
     * <p>这不改变告警层语义（①② 一个字没动），只是让诊断层的严重度<b>不会低于</b>告警层。</p>
     *
     * <p><b>阈值不可用时不用它升级严重度</b>：{@code ConfigData} 是可直构的 POJO，
     * 未经过 {@code load()} 的实例里 {@code mspt_criticalThreshold == 0}；
     * 拿它去比会让任何 {@code avg > 0} 都变 critical。因此只在阈值 {@code > 0} 时启用这一支
     * （与 {@code MsptSpikeRule} 对 avg/max 的 {@code <= 0} 哨兵口径一致：
     * 0 不是"最严格的门槛"，而是"没有门槛"）。</p>
     *
     * <p>文案侧据此分成两条 detail：均值未越线时说"均值掩盖了偶发卡顿"，
     * 均值越线时说"均值本身已越过严重线，峰值是持续压力上的毛刺"——
     * 两种严重度对服主意味着完全不同的排查方向，不能用同一句话。</p>
     */
    public static final class MsptSpikeRule implements DiagnosticRule {
        private static final double RATIO = 3.0;      // max > avg×3
        private static final double MIN_SPIKE_MS = 100.0; // 且 max > 100ms（低于此玩家无感）
        /** 一秒以上的单拍冻结：与该服配置的均值阈值无关，任何服上都是严重级。 */
        private static final double FREEZE_MS = 1000.0;

        @Override
        public String id() {
            return "DIAG-MSPT-SPIKE";
        }

        @Override
        public Evaluation evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)) {
                return Evaluation.skip(id(), Messages.get("diag.skip.mspt-unavailable"));
            }
            if (snap.msptAvg <= 0) {
                return Evaluation.skip(id(), Messages.get("diag.skip.mspt-avg-unknown"));
            }
            if (snap.msptMax <= 0) {
                // 9A-0 后：min/max = -1 表示该采集路径只有平均值，不提供极值信息
                return Evaluation.skip(id(), Messages.get("diag.skip.mspt-no-extremes"));
            }
            if (snap.msptMax < MIN_SPIKE_MS || snap.msptMax < snap.msptAvg * RATIO) {
                return Evaluation.clean();
            }
            String ratio = String.format(java.util.Locale.ROOT, "%.1f", snap.msptMax / snap.msptAvg);
            boolean cfgCriticalUsable = cfg.mspt_criticalThreshold > 0;
            boolean averageAlreadyCritical = cfgCriticalUsable && snap.msptAvg > cfg.mspt_criticalThreshold;
            boolean critical = snap.msptMax > FREEZE_MS || averageAlreadyCritical;

            // 两种严重度对应两种不同的病，detail 必须分开（F-6）
            String detail = averageAlreadyCritical
                ? Messages.get("diag.DIAG-MSPT-SPIKE.detail-pressure", snap.msptMax, snap.msptAvg, ratio,
                    cfg.mspt_criticalThreshold)
                : Messages.get("diag.DIAG-MSPT-SPIKE.detail", snap.msptMax, snap.msptAvg, ratio);
            return Evaluation.of(new Diagnosis(id(), Messages.get("diag.DIAG-MSPT-SPIKE.title"),
                detail,
                Messages.get("diag.DIAG-MSPT-SPIKE.suggestion"),
                critical));
        }
    }
}
