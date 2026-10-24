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
package cn.perfscope.history;

import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;

/**
 * 历史环形缓冲（Wave 7A；来源：docs/06-tasks/WAVE7-DESIGN.md「7A」节锁定契约）。
 *
 * <p>因果背景：v1.1 的最长记忆只有 300 个采集快照（默认约 5 分钟），事故复盘中"什么时候开始的"
 * 无法回答；本类提供约 24 小时的固定窗口历史内存缓冲（重启清零，文件持久化见
 * {@link HistoryFileStore}）。</p>
 *
 * <p><b>容量与节流（9A-18 重做）</b>：采样间隔来自配置 {@code history.sample-interval-seconds}，
 * 但<b>容量不再由间隔反推</b>——旧实现 {@code capacity = 86400 / intervalSec} 让"把间隔调大"
 * 等价于"把历史容量调小"：设 3600 秒 → 容量 24 条，reload 时还会把已有 1440 条瞬时削到 24 条；
 * 同时 24 条样本在 30 分钟诊断窗口里最多 1 条，{@code CoreRules} 的 {@code MIN_SAMPLES} 判据
 * 永久不满足（两条趋势规则静默失效）。现在：</p>
 * <ul>
 *   <li>容量固定 {@value #CAPACITY} 条（内存上界，与 interval 解耦）；</li>
 *   <li>淘汰按<b>时间窗</b>：丢弃 {@code timestamp < now - 24h} 的样本，再以容量兜底；</li>
 *   <li>reload 改 interval（含变大/变小）都不会整体清空或瞬间缩容（契约 7A-5）。</li>
 * </ul>
 * <p>注意：容量解耦只解决"历史被削掉"，<b>不解决</b>"间隔大到诊断窗口里没有样本"——
 * 间隔 &gt; {@value #DIAGNOSIS_MAX_INTERVAL_SEC} 秒时本类会打一条 WARNING（每个取值只打一次），
 * 说明趋势规则会因样本不足长期 skip。真正的上限应落在 ConfigManager 的配置钳制里。</p>
 *
 * <p><b>污染防护（契约 7A-2）</b>：{@code snap.unavailable} 含 {@code MODULE_TPS} 时整条跳过不记
 * （TPS 是锚指标，不可用期的假 0 会毒化最低 TPS）；其余字段按 RAW 存、不消毒（-1 等哨兵原样存）。</p>
 *
 * <p><b>helper 过滤规则（契约 7A-3；消费方可依赖，勿改）</b>：</p>
 * <ul>
 *   <li>全部 helper 先按 sinceMs 过滤（含端点），窗口内无有效样本返回 null（调用方处理空态）；</li>
 *   <li>{@link #peakTpsSince} / {@link #lowestTpsSince}：{@code tps1m <= 0} 的行跳过
 *       （0 可能是真冻结也可能是缺采，缓存的 TPS 无 -1 哨兵，跳过 0 保守）；</li>
 *   <li>{@link #peakEntitiesSince}：{@code totalEntities <= 0} 的行跳过
 *       （0 = 实体模块禁用/采集失败时的默认值，非真实计数；峰值语义下跳过不损失信息）；</li>
 *   <li>{@link #peakHeapSince}：{@code heapUsagePercent <= 0} 的行跳过
 *       （真实 JVM 堆占用恒 &gt; 0；0 = 内存模块禁用/失败/预热期的默认值）；</li>
 *   <li>MSPT 极值当前无 helper；后续若增加，按 {@code msptAvg <= 0} 跳过口径实现。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：{@code record}（采集主线程）与读取（命令线程，通常也是主线程）经同一把
 * 对象锁互斥；读方始终拿到不可变副本（{@link List#copyOf}），永不抛
 * ConcurrentModificationException 且读到一致副本。{@code history.enabled=false} 时 {@code record}
 * 直接返回、全部读取路径返回空列表/null。</p>
 *
 * <p>依赖：纯 JDK（契约 7A-6 零新依赖）。</p>
 */
public final class HistoryStore {

    /** 9A-18：固定容量（内存上界）。默认 60 秒间隔下恰好覆盖 24h；更快的间隔覆盖时间更短，但内存可控。 */
    private static final int CAPACITY = 1440;

    /** 9A-18：时间窗淘汰口径（24 小时）。 */
    private static final long WINDOW_MS = 24L * 60L * 60L * 1000L;

    /**
     * 9A-18：超过该间隔就提示"趋势规则会因样本不足长期 skip"。
     * 最紧的诊断窗口是 10 分钟 / 5 条，30 分钟窗要 10 条 → 3 分钟间隔已是极限；取 180 秒。
     */
    private static final long DIAGNOSIS_MAX_INTERVAL_SEC = 180L;

    private final ConfigManager configManager;

    /** 9A-18：日志出口（PerfScope 的插件 logger；无参构造时回退同名 JDK logger）。 */
    private final Logger logger;

    /** 环形缓冲，迭代序旧→新（ArrayDeque）；全部访问经 this 锁。 */
    private final Deque<HistorySample> ring = new ArrayDeque<>();

    /** Wave 8B：文件持久化出口（可空 = 不持久化；由 PerfScope 装配）。 */
    private volatile HistoryFileStore fileStore;

    /** 上次实际入环时刻（节流基准）；0 = 从未采样（epoch ms 运行时恒 &gt; 0，首次 record 必然放行；
     *  不可用 Long.MIN_VALUE 作哨兵——{@code now - Long.MIN_VALUE} 溢出为负会令首次采样被误节流）。 */
    private long lastRecordMs = 0L;

    /** 9A-18：已经提示过"间隔过大"的取值（同一个值只提示一次，reload 改值后可再次提示）。 */
    private volatile long warnedIntervalSec = -1L;

    public HistoryStore(ConfigManager configManager) {
        this(configManager, null);
    }

    /**
     * 9A-18：带 logger 的构造（推荐）。PerfScope 尚未改调用点前，无参构造回退到
     * {@code Logger.getLogger("PerfScope")}（Bukkit 插件 logger 即插件名，输出落同一控制台）。
     */
    public HistoryStore(ConfigManager configManager, Logger logger) {
        this.configManager = configManager;
        this.logger = logger != null ? logger : Logger.getLogger("PerfScope");
    }

    /**
     * 记录一条历史采样（由采集主线程在 collectSync 末尾调用）。
     * 门控顺序：history.enabled → TPS 锚指标可用性 → 节流间隔 → 入环 + 时间窗/容量淘汰。
     * 因不可用/节流跳过的调用不更新节流基准（恢复后下一周期即可立即补记）。
     */
    public void record(MetricsSnapshot snap) {
        if (snap == null) {
            return;
        }
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg == null || !cfg.history_enabled) {
            return; // 契约：enabled=false 时直接返回
        }
        if (snap.unavailable.contains(MetricsSnapshot.MODULE_TPS)) {
            return; // 污染防护：TPS 不可用期的快照整条不记
        }
        // 防御性下限（ConfigManager 已在 load 时钳制非法值，此处零成本兜底）
        long intervalSec = Math.max(1, cfg.history_sampleIntervalSeconds);
        long now = System.currentTimeMillis();
        warnIfIntervalTooLarge(intervalSec);
        HistorySample sample = new HistorySample(
            snap.timestamp,   // 采样时刻取快照自身时间戳（数据时点），而非入环时刻
            snap.tps1m,
            snap.msptAvg,     // -1 哨兵原样存
            snap.heapUsagePercent,
            snap.playerCount,
            snap.totalEntities,
            snap.totalLoadedChunks
        );
        synchronized (this) {
            // 9A-6 / 9A-18：NTP 回拨（now 小于 lastRecordMs）时旧值会让节流永久卡死——
            // 差值恒为负、永远 < 间隔。检测到回拨即重置基准，本条立即放行。
            if (lastRecordMs != 0L && now < lastRecordMs) {
                lastRecordMs = 0L;
            }
            if (lastRecordMs != 0L && now - lastRecordMs < intervalSec * 1000L) {
                return; // 节流：距上次采样不足间隔，直接返回（lastRecordMs==0 为首次采样，放行）
            }
            lastRecordMs = now;
            // 9A-18：乱序判定（NTP 回拨/快照时间戳回退）——见 evictLocked 注释
            HistorySample tail = ring.peekLast();
            boolean outOfOrder = tail != null && sample.timestamp() < tail.timestamp();
            ring.addLast(sample);
            // 9A-18：先按 24h 时间窗淘汰（与 interval 无关），再以固定容量兜底。
            evictLocked(now, outOfOrder);
        }
        // Wave 8B / 9A-18：文件持久化（锁外执行；9A-18 起 append 只做非阻塞入队，
        // 真正的写盘在 perfscope-history-writer 守护线程上批量完成，主线程零 IO）
        HistoryFileStore fs = fileStore;
        if (fs != null) {
            fs.append(sample);
        }
    }

    /**
     * 9A-18：按时间窗 + 容量淘汰（调用方须持锁）。
     *
     * <p>常规路径 O(1/条)：头部过期就删、超容量就删最旧。但 NTP 回拨会让新样本的时间戳小于
     * 环尾，此时"只删头"不够——过期样本可能不在头部，且 {@link #samples()} 承诺的"旧→新"
     * 迭代序也被破坏（{@link #gaps} 等消费方依赖该序）。因此乱序一旦发生就整体重排一次
     * （1440 条的一次性排序，异常场景才触发），之后恢复单调、淘汰重新精确。</p>
     */
    private void evictLocked(long now, boolean outOfOrder) {
        if (outOfOrder) {
            List<HistorySample> sorted = new ArrayList<>(ring);
            sorted.sort((x, y) -> Long.compare(x.timestamp(), y.timestamp()));
            ring.clear();
            ring.addAll(sorted);
        }
        long cutoff = now - WINDOW_MS;
        while (!ring.isEmpty()) {
            HistorySample head = ring.peekFirst();
            if (head.timestamp() >= cutoff && ring.size() <= CAPACITY) {
                break;
            }
            ring.pollFirst();
        }
    }

    /**
     * 9A-18：间隔过大时的可见提示。容量与间隔解耦后，历史不会再被削掉，但"30 分钟窗口 10 条样本"
     * 在 3600 秒间隔下仍不可能满足；旧行为是静默 skip，新行为至少让服主在控制台看到原因。
     */
    private void warnIfIntervalTooLarge(long intervalSec) {
        if (intervalSec <= DIAGNOSIS_MAX_INTERVAL_SEC || warnedIntervalSec == intervalSec) {
            return;
        }
        warnedIntervalSec = intervalSec;
        logger.warning("[历史] history.sample-interval-seconds=" + intervalSec
            + " 过大：30 分钟诊断窗口内最多只有 " + Math.max(1L, 1800L / intervalSec)
            + " 条样本，DIAG-MEM-LEAK / DIAG-TPS-PERSIST 等趋势规则会因样本不足（MIN_SAMPLES=10）长期跳过。"
            + "建议保持 ≤60 秒。");
    }

    /** Wave 8B：装配文件持久化出口（PerfScope 在构造时/装配期调用；null 安全）。 */
    public void setFileStore(HistoryFileStore fileStore) {
        this.fileStore = fileStore;
    }

    /** 9A-18：环形容量上界（固定 1440 条，与采样间隔无关）。 */
    public int capacity() {
        return CAPACITY;
    }

    /** 9A-18：时间窗淘汰口径（24h，毫秒）。 */
    public long windowMs() {
        return WINDOW_MS;
    }

    /** 全部样本（旧→新，不可变副本）；history.enabled=false 时返回空列表。 */
    public List<HistorySample> samples() {
        return snapshotAll();
    }

    /** sinceMs 起的样本（含端点，旧→新，不可变副本）；history.enabled=false 时返回空列表。 */
    public List<HistorySample> since(long epochMs) {
        List<HistorySample> result = new ArrayList<>();
        for (HistorySample s : snapshotAll()) {
            if (s.timestamp() >= epochMs) {
                result.add(s);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 9A-18：重启/采集中断造成的数据缺口——相邻样本间隔 &gt; 2× 期望间隔即记为一段缺口。
     *
     * <p>用于 /perfscope trend 输出"数据缺口 N 处，共 M 小时"，把"没有数据"与"数据正常"
     * 区分开（旧实现两者都表现为一条平线）。缺口语义为<b>开区间</b>：{@code startMs} 是缺口前
     * 最后一个样本时刻，{@code endMs} 是缺口后第一个样本时刻。</p>
     *
     * @param sinceMs            只统计该时刻（含）之后的样本
     * @param expectedIntervalMs 期望采样间隔（通常为 history.sample-interval-seconds × 1000）
     * @return 缺口列表（按时间升序，不可变）；参数非法时返回空列表
     */
    public List<Gap> gaps(long sinceMs, long expectedIntervalMs) {
        if (expectedIntervalMs <= 0L) {
            return List.of();
        }
        long threshold = expectedIntervalMs > Long.MAX_VALUE / 2L
            ? Long.MAX_VALUE : expectedIntervalMs * 2L;
        List<Gap> result = new ArrayList<>();
        HistorySample prev = null;
        for (HistorySample s : snapshotAll()) {
            if (s.timestamp() < sinceMs) {
                continue;
            }
            if (prev != null) {
                long delta = s.timestamp() - prev.timestamp();
                if (delta > threshold) {
                    result.add(new Gap(prev.timestamp(), s.timestamp()));
                }
            }
            prev = s;
        }
        return List.copyOf(result);
    }

    /** 9A-18：一段数据缺口（左开右闭语义见 {@link #gaps}）。 */
    public record Gap(long startMs, long endMs) {
        /** 缺口时长（毫秒，恒 ≥ 0）。 */
        public long durationMs() {
            return Math.max(0L, endMs - startMs);
        }
    }

    /** 窗口内 tps1m 最高的样本（"最好的时刻"）；无有效样本返回 null。过滤规则见类注释。 */
    public HistorySample peakTpsSince(long sinceMs) {
        HistorySample best = null;
        for (HistorySample s : snapshotAll()) {
            if (s.timestamp() < sinceMs || s.tps1m() <= 0) {
                continue;
            }
            if (best == null || s.tps1m() > best.tps1m()) {
                best = s;
            }
        }
        return best;
    }

    /** 窗口内 tps1m 最低的样本（"最差的时刻"，日报用）；无有效样本返回 null。过滤规则见类注释。 */
    public HistorySample lowestTpsSince(long sinceMs) {
        HistorySample worst = null;
        for (HistorySample s : snapshotAll()) {
            if (s.timestamp() < sinceMs || s.tps1m() <= 0) {
                continue;
            }
            if (worst == null || s.tps1m() < worst.tps1m()) {
                worst = s;
            }
        }
        return worst;
    }

    /** 窗口内 totalEntities 最高的样本；无有效样本返回 null。过滤规则见类注释。 */
    public HistorySample peakEntitiesSince(long sinceMs) {
        HistorySample best = null;
        for (HistorySample s : snapshotAll()) {
            if (s.timestamp() < sinceMs || s.totalEntities() <= 0) {
                continue;
            }
            if (best == null || s.totalEntities() > best.totalEntities()) {
                best = s;
            }
        }
        return best;
    }

    /** 窗口内 heapUsagePercent 最高的样本；无有效样本返回 null。过滤规则见类注释。 */
    public HistorySample peakHeapSince(long sinceMs) {
        HistorySample best = null;
        for (HistorySample s : snapshotAll()) {
            if (s.timestamp() < sinceMs || s.heapUsagePercent() <= 0) {
                continue;
            }
            if (best == null || s.heapUsagePercent() > best.heapUsagePercent()) {
                best = s;
            }
        }
        return best;
    }

    /** 当前实际存量（诊断用；不受 history.enabled 影响——门控只作用于 read 路径的返回值）。 */
    public int size() {
        synchronized (this) {
            return ring.size();
        }
    }

    /** 读取路径统一入口：enabled 门控 + 加锁复制（不可变副本，旧→新）。 */
    private List<HistorySample> snapshotAll() {
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg == null || !cfg.history_enabled) {
            return List.of();
        }
        synchronized (this) {
            return List.copyOf(ring);
        }
    }
}
