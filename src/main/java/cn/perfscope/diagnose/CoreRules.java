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
     * ② 首尾时间跨度覆盖了"这 minSamples 条样本本该覆盖的时间"——即
     * {@code span ≥ minSamples × 采样间隔 × 0.8}。</p>
     *
     * <p>为什么用「样本间隔」而不是「窗口比例」：旧实现只看条数，在 5 秒采样下
     * 45 秒数据就能断言"30 分钟趋势"（8 个样本）；但若强求窗口的 60%，刚重启的服务器
     * 又会在长时间内完全无法诊断（30 分钟窗要等 18 分钟）。以"这批样本自身的时间密度"
     * 为准是更准确的口径：它保证样本确实是分散在一段时间上的，而不是挤在一瞬间。</p>
     *
     * @return 覆盖不足时返回人类可读原因（供 skip），足够时返回 null
     */
    private static String coverageProblem(List<HistorySample> window, long windowMs, int minSamples,
                                          String what) {
        if (window.size() < minSamples) {
            return what + "样本不足（" + window.size() + "/" + minSamples + " 条）";
        }
        long span = window.get(window.size() - 1).timestamp() - window.get(0).timestamp();
        // 用窗口内样本的实际中位间隔推"这批样本本应跨越多长时间"
        long[] gaps = new long[window.size() - 1];
        for (int i = 1; i < window.size(); i++) {
            gaps[i - 1] = window.get(i).timestamp() - window.get(i - 1).timestamp();
        }
        java.util.Arrays.sort(gaps);
        long medianGap = gaps.length == 0 ? 0 : gaps[gaps.length / 2];
        // 期望跨度 = 中位间隔 × (样本数-1)，允许 20% 抖动
        long expected = (long) (medianGap * (window.size() - 1) * 0.8);
        // 同时保有绝对下限：至少覆盖窗口的 10%（防止"1 秒内 10 条样本"这种极端）
        long absoluteFloor = (long) (windowMs * 0.10);
        long needed = Math.max(expected, absoluteFloor);
        if (span < needed) {
            long haveSec = span / 1000L;
            long needSec = needed / 1000L;
            String have = haveSec >= 60 ? (haveSec / 60) + " 分 " + (haveSec % 60) + " 秒" : haveSec + " 秒";
            String need = needSec >= 60 ? (needSec / 60) + " 分" : needSec + " 秒";
            return what + "覆盖不足（已覆盖 " + have + " / 需要 " + need
                + "；请让服务器继续运行一段时间再诊断）";
        }
        return null;
    }

    // ==================== DIAG-MEM-LEAK：疑似内存泄漏（启发式） ====================

    /**
     * 泄漏启发式：窗口内堆占用持续抬升，且窗口后半段谷值未回落到起点。
     *
     * <p>措辞纪律（9A-8）：原 detail 声称"GC 后水位未回落"，但代码从未做过 GC 后测量
     * （{@code HistorySample} 里没有 gcCount 字段）。现在只陈述<b>观测到的事实</b>
     * （窗口内最低点仍高于起点），不声称做过 GC 后采样。</p>
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
                return Evaluation.skip(id(), "内存模块不可用（采集失败或被配置禁用）");
            }
            if (snap.heapUsagePercent < cfg.memory_heapWarningThreshold) {
                return Evaluation.skip(id(), "当前堆占用 " + snap.heapUsagePercent + "% 低于告警线 "
                    + cfg.memory_heapWarningThreshold + "%（低水位不判定泄漏）");
            }
            long windowMs = WINDOW_MINUTES * 60_000L;
            List<HistorySample> window = history.since(System.currentTimeMillis() - windowMs);
            String cov = coverageProblem(window, windowMs, MIN_SAMPLES, "历史");
            if (cov != null) {
                return Evaluation.skip(id(), cov);
            }
            HistorySample first = window.get(0);
            HistorySample last = window.get(window.size() - 1);
            if (first.heapUsagePercent() <= 0 || last.heapUsagePercent() <= 0) {
                return Evaluation.skip(id(), "窗口内含内存未采集样本（heapUsagePercent=0），无法比较趋势");
            }
            int growth = last.heapUsagePercent() - first.heapUsagePercent();
            if (growth < MIN_HEAP_GROWTH_PERCENT) {
                return Evaluation.clean();
            }
            // 后半段谷值仍高于起点 → 抬升是持续性的，不是一次尖峰后回落
            int minInLaterHalf = Integer.MAX_VALUE;
            for (int i = window.size() / 2; i < window.size(); i++) {
                int h = window.get(i).heapUsagePercent();
                if (h <= 0) {
                    return Evaluation.skip(id(), "窗口后半段含内存未采集样本（heapUsagePercent=0），无法判定是否回落");
                }
                minInLaterHalf = Math.min(minInLaterHalf, h);
            }
            if (minInLaterHalf < first.heapUsagePercent()) {
                return Evaluation.clean(); // 回落过 → 更像正常波动
            }
            long minutes = Math.max(1, (last.timestamp() - first.timestamp()) / 60_000L);
            return Evaluation.of(new Diagnosis(id(), "堆占用持续抬升（疑似内存泄漏）",
                "近 " + minutes + " 分钟内堆占用从 " + first.heapUsagePercent() + "% 升至 "
                    + last.heapUsagePercent() + "%（+" + growth + " 个百分点），且该时段最低点仍为 "
                    + minInLaterHalf + "%（未回落到起点）——当前 " + snap.heapUsagePercent + "%",
                "先留证再处置：① /perfscope report 存档当前状态；② 用 /spark heapdump 取堆快照"
                    + "（Paper 内置 spark 已含此命令，产物可由 spark 上传分析，无需装 JVM 工具链）；"
                    + "③ 重启前确认 -Xmx 是否偏小（当前 " + (snap.heapMax / 1024 / 1024) + "MB）；"
                    + "④ 常见持有者：地图/缓存插件、异常堆积的 Item 实体（可先 /perfscope hotspot 看掉落物）",
                true));
        }
    }

    // ==================== DIAG-ENT-HOTSPOT：热点区块（下钻到坐标） ====================

    /**
     * 热点区块：Top1 区块实体数显著偏高时给出坐标级结论。
     *
     * <p>9A-8 修正：原实现用<b>全服</b>阈值（默认 2000/2500）除以 2 去卡<b>单个区块</b>，
     * 即要求单区块 1000+ 实体——真实服务器上单区块堆几百只怪就该报警，因此该规则
     * 在默认配置下<b>永不触发</b>（v1.2.0 等于带着一条死规则发布）。
     * 现在改为独立的单区块阈值（{@code hotspots.chunk-warning-threshold}，默认 200）。</p>
     */
    public static final class EntityHotspotRule implements DiagnosticRule {
        /** 绝对下限：低于此数不谈"热点"（小服几十只动物属正常）。 */
        private static final int ABSOLUTE_FLOOR = 100;
        /** 单个区块进入"严重"的绝对门槛（与全服阈值无关）。 */
        private static final int CHUNK_CRITICAL = 500;

        @Override
        public String id() {
            return "DIAG-ENT-HOTSPOT";
        }

        @Override
        public Evaluation evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_HOTSPOTS)) {
                return Evaluation.skip(id(), "热点区块模块不可用（采集失败或被配置禁用 hotspots.enabled=false）");
            }
            if (snap.topEntityChunks.isEmpty()) {
                return Evaluation.skip(id(), "尚无热点采样（首轮全遍历未完成，通常 30 秒内就绪）");
            }
            var top = snap.topEntityChunks.get(0);
            int warnThreshold = Math.max(ABSOLUTE_FLOOR, cfg.hotspots_chunkWarningThreshold);
            if (top.entityCount() < warnThreshold) {
                return Evaluation.clean(); // 最高的那个区块都没到线
            }
            return Evaluation.of(new Diagnosis(id(), "热点区块（单区块实体堆积）",
                top.world() + " 区块 [" + top.chunkX() + "," + top.chunkZ() + "]（方块 " + top.blockX() + ",~,"
                    + top.blockZ() + "）堆积 " + top.entityCount() + " 个实体（单区块阈值 " + warnThreshold
                    + "），主导 " + top.dominantType() + "（" + top.dominantPercent() + "%）",
                "① 先去坐标看：/minecraft:tp " + top.blockX() + " ~ " + top.blockZ()
                    + "；② 若是掉落物堆积（主导 ITEM），可用 /perfscope hotspot 复制坐标后人工清理，"
                    + "或临时限制掉落物存活时间；③ 若是刷怪装置失控，检查世界 spawn-limits / 生物上限；"
                    + "④ 处理前建议先 /perfscope report 留档",
                top.entityCount() >= CHUNK_CRITICAL));
        }
    }

    // ==================== DIAG-TPS-PERSIST：TPS 持续低位（趋势判定） ====================

    /**
     * TPS 持续低位：区别于"此刻"告警——窗口持续偏低，属结构性问题。
     *
     * <p>9A-8 修正：优先用快照自带的 {@code tps15m}（服务端 15 分钟滚动均值）作为
     * "持续"的主判据；{@code tps1m} 只用于"当前是否仍在恶化"。历史窗口仍要求覆盖达标。</p>
     */
    public static final class TpsPersistentRule implements DiagnosticRule {
        private static final long WINDOW_MINUTES = 10;
        private static final int MIN_SAMPLES = 5;

        @Override
        public String id() {
            return "DIAG-TPS-PERSIST";
        }

        @Override
        public Evaluation evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
                return Evaluation.skip(id(), "TPS 模块不可用（采集失败或被配置禁用）");
            }
            long windowMs = WINDOW_MINUTES * 60_000L;
            List<HistorySample> window = history.since(System.currentTimeMillis() - windowMs);
            String cov = coverageProblem(window, windowMs, MIN_SAMPLES, "历史");
            if (cov != null) {
                return Evaluation.skip(id(), cov);
            }
            double sum = 0;
            int count = 0;
            HistorySample lowest = null;
            for (HistorySample s : window) {
                if (s.tps1m() <= 0 || s.tps1m() > 20.5) {
                    continue; // 过滤哨兵与异常值
                }
                sum += s.tps1m();
                count++;
                if (lowest == null || s.tps1m() < lowest.tps1m()) {
                    lowest = s;
                }
            }
            if (count < MIN_SAMPLES || lowest == null) {
                return Evaluation.skip(id(), "历史有效 TPS 样本不足（" + count + "/" + MIN_SAMPLES + " 条）");
            }
            double avg1m = sum / count;
            // "持续"主判据：15 分钟滚动均值（服务端自带，天然平滑），窗口均值只作辅助
            boolean persist15m = snap.tps15m > 0 && snap.tps15m < cfg.tps_warningThreshold;
            boolean persistWindow = avg1m < cfg.tps_warningThreshold;
            if (!persist15m && !persistWindow) {
                return Evaluation.clean();
            }
            long minutes = Math.max(1, (window.get(window.size() - 1).timestamp() - window.get(0).timestamp()) / 60_000L);
            return Evaluation.of(new Diagnosis(id(), "TPS 持续低位",
                "15 分钟均值 " + String.format(java.util.Locale.ROOT, "%.2f", snap.tps15m)
                    + "、近 " + minutes + " 分钟窗口均值 " + String.format(java.util.Locale.ROOT, "%.2f", avg1m)
                    + "（告警线 <" + cfg.tps_warningThreshold + "），窗口最低 "
                    + String.format(java.util.Locale.ROOT, "%.2f", lowest.tps1m())
                    + "——非偶发抖动，属持续负载",
                "按顺序排查：① /perfscope hotspot 查实体堆积坐标；② /perfscope memory 看内存压力；"
                    + "③ 对照 /perfscope trend 1 的跌落起点与玩家上线/定时任务时间；"
                    + "④ 仍定位不了用 /spark profiler start --only-ticks-over 200",
                snap.tps15m > 0 && snap.tps15m < cfg.tps_criticalThreshold));
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
                return Evaluation.skip(id(), "玩家模块不可用（采集失败或被配置禁用）");
            }
            if (snap.maxPlayers <= 0) {
                return Evaluation.skip(id(), "max-players 未就绪（当前 " + snap.maxPlayers + "）");
            }
            // 9A-6：统一走 MetricsSnapshot 的算法（此前这里是 Math.round、命令层 int 截断、采集层 double 截断）
            int currentPercent = snap.playerPercent();
            if (currentPercent < cfg.players_warningThreshold) {
                return Evaluation.clean();
            }
            long windowMs = WINDOW_MINUTES * 60_000L;
            List<HistorySample> window = history.since(System.currentTimeMillis() - windowMs);
            String cov = coverageProblem(window, windowMs, MIN_SAMPLES, "历史");
            if (cov != null) {
                return Evaluation.skip(id(), cov);
            }
            int minPercent = Integer.MAX_VALUE;
            for (HistorySample s : window) {
                // 0 人是真实低谷，必须参与统计（原实现跳过它导致"持续满员"的假结论）
                minPercent = Math.min(minPercent, MetricsSnapshot.playerPercentOf(s.playerCount(), snap.maxPlayers));
            }
            if (minPercent < cfg.players_warningThreshold - 10) {
                return Evaluation.clean(); // 窗口内有明显低谷：属活动峰值而非容量问题
            }
            long minutes = Math.max(1, (window.get(window.size() - 1).timestamp() - window.get(0).timestamp()) / 60_000L);
            return Evaluation.of(new Diagnosis(id(), "在线人数持续接近上限",
                "当前 " + snap.playerCount + "/" + snap.maxPlayers + "（" + currentPercent + "%），"
                    + "近 " + minutes + " 分钟内最低也有 " + minPercent + "%——新玩家可能进不来",
                "先分辨是容量问题还是挂机：① /perfscope players 看在线时长分布；"
                    + "② 若是挂机占位，装 AFK 踢出插件释放槽位；"
                    + "③ 若是真实满员，扩容 server.properties → max-players"
                    + "（每 +10 人约 +0.5–1G 堆，扩容后建议复查 /perfscope memory）；"
                    + "④ 活动峰段可在群公告排队规则",
                currentPercent >= cfg.players_criticalThreshold));
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
     */
    public static final class MsptSpikeRule implements DiagnosticRule {
        private static final double RATIO = 3.0;      // max > avg×3
        private static final double MIN_SPIKE_MS = 100.0; // 且 max > 100ms（低于此玩家无感）

        @Override
        public String id() {
            return "DIAG-MSPT-SPIKE";
        }

        @Override
        public Evaluation evaluate(MetricsSnapshot snap, HistoryStore history, ConfigManager.ConfigData cfg) {
            if (snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)) {
                return Evaluation.skip(id(), "MSPT 模块不可用（采集失败或被配置禁用）");
            }
            if (snap.msptAvg <= 0) {
                return Evaluation.skip(id(), "MSPT 平均值未知（当前采集路径未取到数据）");
            }
            if (snap.msptMax <= 0) {
                // 9A-0 后：min/max = -1 表示该采集路径只有平均值，不提供极值信息
                return Evaluation.skip(id(), "当前 MSPT 采集路径仅提供平均值、无 tick 极值（Paper 1.20.4+ 的 "
                    + "getTickTimes() 可用时才有尖刺信息）");
            }
            if (snap.msptMax < MIN_SPIKE_MS || snap.msptMax < snap.msptAvg * RATIO) {
                return Evaluation.clean();
            }
            return Evaluation.of(new Diagnosis(id(), "MSPT 尖刺（偶发单 tick 冻结）",
                "最大 " + snap.msptMax + "ms ≈ 平均 " + snap.msptAvg + "ms 的 "
                    + String.format(java.util.Locale.ROOT, "%.1f", snap.msptMax / snap.msptAvg)
                    + " 倍——均值掩盖了偶发卡顿，玩家体感为「顿一下」",
                "尖刺多来自区块保存或 GC：① 降区块保存压力——改 config/paper-world-defaults.yml 的 "
                    + "chunks.max-auto-save-chunks-per-tick（默认 24，可降到 8）；"
                    + "② 自动保存周期在 bukkit.yml 的 ticks-per.autosave（默认 6000 tick）；"
                    + "③ 定点抓取：/spark profiler start --only-ticks-over 200（采样结束后 /spark profiler stop 看报告）",
                snap.msptMax > 1000.0));
        }
    }
}
