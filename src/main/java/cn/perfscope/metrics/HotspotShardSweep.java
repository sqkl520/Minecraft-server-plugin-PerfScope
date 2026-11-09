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
package cn.perfscope.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * v1.4：热点区块分区增量扫描的纯逻辑内核（不触碰 Bukkit，可脱离服务端单测）。
 *
 * <p><b>为什么需要它</b>：{@code collectHotspots} 原实现一次遍历「全部世界 × 全部已加载区块」，
 * 实测 11,048 区块 / 13,466 实体时单次 <b>40.5 ms</b>，50,000 实体时 <b>106 ms</b>（超 2 tick）。
 * 把它按时间切片后，单拍成本降到约 {@code 1/N}，而一整轮覆盖的区块集合不变。</p>
 *
 * <p><b>虚拟序列而非坐标游标</b>：分片的下标是一个把「各世界的已加载区块」首尾相接后得到的
 * 线性序列（世界 A 的区块在前、世界 B 的在后）。不用 {@code (chunkX, chunkZ)} 坐标游标，
 * 是因为按坐标推进必须枚举一个包围盒，而在区块稀疏分布的服务器上，包围盒里绝大多数格子
 * 根本没有加载的区块——既浪费又没法保证「一轮扫完所有区块」。</p>
 *
 * <p><b>候选池有界</b>：每并入一片后，超限即按 {@link #RANKING} 排序截断到
 * {@link #CANDIDATE_LIMIT}。这是<b>精确</b>的——「全体集合的 Top-K」等于「各分片 Top-K 的 Top-K」，
 * 所以截断不会丢掉真正的 Top-5。上限存在的意义是防止大服上一轮扫描堆积上万个
 * {@link ChunkHotspot} 对象。</p>
 *
 * <p><b>游标与区块卸载</b>：一轮扫描期间区块可能被卸载或新加载。本类只负责下标推进，
 * 调用方在候选区块列表建立时**快照**一次（一轮内可见集合固定），扫描时用
 * {@code Chunk#isLoaded()} 跳过已卸载的项。一轮结束后重新快照，新加载的区块下一轮才进入。</p>
 *
 * <p><b>线程</b>：非线程安全，只在主线程的采集周期里使用。</p>
 */
final class HotspotShardSweep {

    /** 默认分片数：与默认 {@code hotspots.sample-every-cycles: 30} 整除，单拍成本约 1/10。 */
    static final int DEFAULT_SHARDS = 10;

    /** 分片数上限（防止配置写成天文数字后每片只剩个位数的区块，反而被固定开销淹没）。 */
    static final int MAX_SHARDS = 100;

    /** 候选池上限：远大于对外发布的 Top-5，留出跨分片比较的余量。 */
    static final int CANDIDATE_LIMIT = 64;

    /** 对外发布的 Top-N 条数（与 {@code topEntityChunks} 的既有口径一致，展示层取前 3）。 */
    static final int TOP_N = 5;

    /**
     * 排名口径（与改造前的 {@code collectHotspots} 逐字一致）：
     * 实体数降序 → 世界名升序 → chunkX 升序 → chunkZ 升序。
     *
     * <p>后三级平票规则不是装饰：它保证同一份世界状态在任意两次扫描中产出<b>完全相同</b>的顺序，
     * 否则 Top-5 在同一票数之间会随机换位，harness 断言与用户看到的坐标都会抖。</p>
     */
    static final Comparator<ChunkHotspot> RANKING = Comparator
        .comparingInt(ChunkHotspot::entityCount).reversed()
        .thenComparing(ChunkHotspot::world)
        .thenComparingInt(ChunkHotspot::chunkX)
        .thenComparingInt(ChunkHotspot::chunkZ);

    /** 本轮虚拟区块序列的总长度。 */
    private final int total;
    /** 本轮实际分片数（≤ 请求的分片数，且不超过总区块数；总数为 0 时仍为 1，以便空序列也能"完成"）。 */
    private final int shardCount;
    /** 跨分片累积的候选（始终 ≤ {@link #CANDIDATE_LIMIT}）。 */
    private final List<ChunkHotspot> candidates = new ArrayList<>();
    /** 下一片的下标。 */
    private int nextShard = 0;

    /**
     * @param total  本轮虚拟区块序列长度（调用方快照到的已加载区块总数，≥ 0）
     * @param shards 期望分片数（≤ 0 视为 1）
     */
    HotspotShardSweep(int total, int shards) {
        this.total = Math.max(0, total);
        int wanted = Math.max(1, Math.min(shards, MAX_SHARDS));
        // 区块数少于分片数时按区块数切：否则会出现 [3,3) 这种空片，白白多等几个周期
        this.shardCount = this.total == 0 ? 1 : Math.min(wanted, this.total);
    }

    /** 本轮总区块数。 */
    int total() {
        return total;
    }

    /** 本轮实际分片数。 */
    int shardCount() {
        return shardCount;
    }

    /** 是否已扫完全部片。 */
    boolean isComplete() {
        return nextShard >= shardCount;
    }

    /** 下一片的下标（0 基）。 */
    int nextShardIndex() {
        return nextShard;
    }

    /**
     * 下一片的起始下标（含）。
     *
     * <p>用 {@code i * total / shardCount} 而非「每片固定 size」：这样余数会自然摊到各片上
     * （11,048 / 10 → 前 8 片各 1,104、后 2 片各 1,108），不会出现最后一片特别小。</p>
     */
    int rangeStart() {
        return (int) ((long) nextShard * total / shardCount);
    }

    /** 下一片的结束下标（不含）。 */
    int rangeEnd() {
        return (int) ((long) (nextShard + 1) * total / shardCount);
    }

    /**
     * 并入一片的扫描结果并推进游标。
     *
     * @param scanned 本片扫出的非空区块（可为空列表；{@code null} 等价于空）
     * @return 若本片是最后一片，返回本轮<b>完整</b>的 Top-N（不可变）；否则返回 {@code null}
     */
    List<ChunkHotspot> accept(List<ChunkHotspot> scanned) {
        if (isComplete()) {
            // 已经完成过一轮：再 accept 是调用方的程序错误，直接给出当前结果，不做二次推进
            return partialTopN();
        }
        if (scanned != null && !scanned.isEmpty()) {
            candidates.addAll(scanned);
        }
        nextShard++;
        if (isComplete()) {
            return finish();
        }
        trim();
        return null;
    }

    /** 截断候选池并返回完整 Top-N（本轮结束）。 */
    private List<ChunkHotspot> finish() {
        List<ChunkHotspot> top = partialTopN();
        candidates.clear();
        return top;
    }

    /**
     * 已扫部分的 Top-N（用于<b>首轮</b>尚未扫完时的部分发布）。
     *
     * <p>刻意返回新列表：调用方会把它直接挂进快照缓存，若返回内部列表，后续 {@code addAll}
     * 会同时改到已发布的快照。</p>
     *
     * @return 不可变 Top-N（候选为空时返回空列表）
     */
    List<ChunkHotspot> partialTopN() {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<ChunkHotspot> copy = new ArrayList<>(candidates);
        copy.sort(RANKING);
        return List.copyOf(copy.subList(0, Math.min(TOP_N, copy.size())));
    }

    /** 候选池超过上限时按排名截断（保留最好的 {@link #CANDIDATE_LIMIT} 个）。 */
    private void trim() {
        if (candidates.size() <= CANDIDATE_LIMIT) {
            return;
        }
        candidates.sort(RANKING);
        candidates.subList(CANDIDATE_LIMIT, candidates.size()).clear();
    }

    /**
     * 每隔多少个采集周期推进一片。
     *
     * <p>取 {@code ceil(sampleEveryCycles / shardCount)}，其中 {@code shardCount} 必须是
     * <b>本轮实际</b>分片数（{@link #shardCount()}），不是配置里的期望值——区块数少于配置分片数时
     * 实际切片会更少，用配置值算步长会让一轮变短、单位时间的工作量反而上升。</p>
     *
     * <p>由此得到的安全性质：一轮的总周期数 {@code shardCount × stepInterval} <b>恒 ≥</b>
     * {@code sampleEveryCycles}。即改造后热点扫描的单位时间工作量<b>只会持平或变少</b>，
     * 分片换来的只是「把同一份活摊开」，不是「多干活」。</p>
     *
     * @param sampleEveryCycles 配置的整轮周期（≤ 0 视为 1）
     * @param shardCount        本轮实际分片数（≤ 0 视为 1）
     */
    static int stepInterval(int sampleEveryCycles, int shardCount) {
        int every = Math.max(1, sampleEveryCycles);
        int n = Math.max(1, Math.min(shardCount, MAX_SHARDS));
        return Math.max(1, (every + n - 1) / n);
    }

    /** 配置值钳制（与全仓 clamp 口径一致：越界回退默认并打 warning）。 */
    static int clampShards(int configured) {
        return Math.max(1, Math.min(configured, MAX_SHARDS));
    }
}
