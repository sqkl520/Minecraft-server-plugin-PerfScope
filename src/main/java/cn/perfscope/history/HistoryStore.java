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

/**
 * 历史环形缓冲（Wave 7A；来源：docs/06-tasks/WAVE7-DESIGN.md「7A」节锁定契约）。
 *
 * <p>因果背景：v1.1 的最长记忆只有 300 个采集快照（默认约 5 分钟），事故复盘中"什么时候开始的"
 * 无法回答；本类提供约 24 小时的固定窗口历史内存缓冲（重启清零，文件持久化规划于 Wave 8B）。</p>
 *
 * <p><b>容量与节流</b>：采样间隔来自配置 {@code history.sample-interval-seconds}，容量 =
 * max(1, 86400 / 间隔秒数)（覆盖约 24h）；二者在每次 {@link #record} 时按当前配置读取/重算——
 * reload 改间隔后容量随之伸缩（变小则淘汰最旧、变大则保留既有样本），绝不整体清空
 * （契约 7A-5：reload 不清空）。实例由 PerfScope 持有（非采集器持有），reload/采集重启不丢历史。</p>
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

    /** 一日秒数：容量公式基数（契约 7A：覆盖约 24h）。 */
    private static final int SECONDS_PER_DAY = 86400;

    private final ConfigManager configManager;

    /** 环形缓冲，迭代序旧→新（ArrayDeque）；全部访问经 this 锁。 */
    private final Deque<HistorySample> ring = new ArrayDeque<>();

    /** 上次实际入环时刻（节流基准）；0 = 从未采样（epoch ms 运行时恒 &gt; 0，首次 record 必然放行；
     *  不可用 Long.MIN_VALUE 作哨兵——{@code now - Long.MIN_VALUE} 溢出为负会令首次采样被误节流）。 */
    private long lastRecordMs = 0L;

    public HistoryStore(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 记录一条历史采样（由采集主线程在 collectSync 末尾调用）。
     * 门控顺序：history.enabled → TPS 锚指标可用性 → 节流间隔 → 入环 + 按当前配置容量淘汰最旧。
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
            if (lastRecordMs != 0L && now - lastRecordMs < intervalSec * 1000L) {
                return; // 节流：距上次采样不足间隔，直接返回（lastRecordMs==0 为首次采样，放行）
            }
            lastRecordMs = now;
            ring.addLast(sample);
            long capacity = Math.max(1L, SECONDS_PER_DAY / intervalSec);
            while (ring.size() > capacity) {
                ring.pollFirst();
            }
        }
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
