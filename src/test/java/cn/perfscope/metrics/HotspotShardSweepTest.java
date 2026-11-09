/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1.4 热点分区增量扫描的纯逻辑内核（{@link HotspotShardSweep}）。
 *
 * <p>这些用例锁的是分片方案的<b>正确性</b>而不是"跑得通"：分片后的 Top-N 必须与
 * 改造前「一次性全遍历 + 一次排序」的结果<b>逐项相同</b>，否则用户看到的 Top3 就会变。
 * 另外锁住三条容易写错的性质：区间必须无缝覆盖（不能漏区块、不能重复）、
 * 候选池截断不能丢掉真正的 Top-5、以及「单位时间工作量不增加」的步长不变式。</p>
 *
 * <p>不依赖 Bukkit：{@link HotspotShardSweep} 刻意写成不碰服务端 API 的纯逻辑类，
 * 正是为了能在这里直接跑。</p>
 */
class HotspotShardSweepTest {

    private static ChunkHotspot hs(String world, int cx, int cz, int count) {
        return new ChunkHotspot(world, cx, cz, count, "PIG", count);
    }

    /** 把 0..n-1 的区块按「每片喂给 sweep」的方式扫完，返回完成时的 Top-N。 */
    private static List<ChunkHotspot> runFull(HotspotShardSweep sweep, List<ChunkHotspot> all) {
        List<ChunkHotspot> result = null;
        while (!sweep.isComplete()) {
            int from = sweep.rangeStart();
            int to = sweep.rangeEnd();
            result = sweep.accept(new ArrayList<>(all.subList(from, to)));
            assertTrue(result == null || sweep.isComplete(),
                "only the final shard may produce a complete result");
        }
        assertNotNull(result, "a full run must publish a result");
        return result;
    }

    @Test
    void rangesCoverEveryIndexExactlyOnce() {
        // 11,048 是实测的大服区块数：分片边界若用「固定 size」会被余数坑，这里就锁住它。
        for (int total : new int[]{0, 1, 5, 7, 10, 11, 100, 11_048, 50_000}) {
            for (int shards : new int[]{1, 2, 3, 7, 10, 100}) {
                if (total == 0) {
                    continue;
                }
                HotspotShardSweep sweep = new HotspotShardSweep(total, shards);
                boolean[] seen = new boolean[total];
                while (!sweep.isComplete()) {
                    int from = sweep.rangeStart();
                    int to = sweep.rangeEnd();
                    assertTrue(from < to, "empty shard for total=" + total + " shards=" + shards);
                    for (int i = from; i < to; i++) {
                        assertTrue(!seen[i], "index " + i + " covered twice (total=" + total
                            + ", shards=" + shards + ")");
                        seen[i] = true;
                    }
                    sweep.accept(List.of());
                }
                for (int i = 0; i < total; i++) {
                    assertTrue(seen[i], "index " + i + " never covered (total=" + total
                        + ", shards=" + shards + ")");
                }
            }
        }
    }

    @Test
    void firstAndLastIndexAreAlwaysCovered() {
        HotspotShardSweep sweep = new HotspotShardSweep(1000, 10);
        assertEquals(0, sweep.rangeStart(), "first shard must start at 0");
        int lastEnd = -1;
        while (!sweep.isComplete()) {
            lastEnd = sweep.rangeEnd();
            sweep.accept(List.of());
        }
        assertEquals(1000, lastEnd, "last shard must end exactly at total");
    }

    @Test
    void shardCountIsCappedByTotal() {
        // 4 个区块配 10 片 → 实际 4 片。否则会出现 6 个 [4,4) 的空片，白等 6 个周期。
        HotspotShardSweep sweep = new HotspotShardSweep(4, 10);
        assertEquals(4, sweep.shardCount());
        HotspotShardSweep single = new HotspotShardSweep(100, 1);
        assertEquals(1, single.shardCount());
        // 总数为 0 时仍是 1 片（空序列也要能"完成"，否则 sweep 永远不结束）
        HotspotShardSweep empty = new HotspotShardSweep(0, 10);
        assertEquals(1, empty.shardCount());
        assertEquals(List.of(), runFull(new HotspotShardSweep(0, 10), List.of()));
    }

    @Test
    void shardingProducesTheSameTopNAsFullSort() {
        // 构造一个"跨分片才能定胜负"的分布：最好的几个分别落在第一片、中间片和最后一片。
        int total = 200;
        List<ChunkHotspot> all = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            int count = (i % 17) + 1;   // 大量平票，压测排序口径
            all.add(hs("world", i, -i, count));
        }
        all.set(3, hs("world", 3, -3, 999));      // 第一片冠军
        all.set(97, hs("world", 97, -97, 500));   // 中间片冠军
        all.set(198, hs("world", 198, -198, 700)); // 最后一片冠军

        // 参照值：改造前的实现就是「全收集 + 一次排序 + 取前 5」
        List<ChunkHotspot> expected = new ArrayList<>(all);
        expected.sort(HotspotShardSweep.RANKING);
        expected = expected.subList(0, HotspotShardSweep.TOP_N);

        for (int shards : new int[]{1, 2, 3, 10, 37, 100}) {
            List<ChunkHotspot> actual = runFull(new HotspotShardSweep(total, shards), all);
            assertEquals(expected, actual, "sharded Top-N must equal full-sort Top-N at shards=" + shards);
        }
    }

    @Test
    void candidateLimitNeverDropsATopFiveEntry() {
        // 候选池上限 64，但每片都塞入远超上限的区块：必须仍然留下全局最好的 5 个。
        HotspotShardSweep sweep = new HotspotShardSweep(2000, 10);
        List<ChunkHotspot> all = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            all.add(hs("world", i, 0, 10));
        }
        all.set(1500, hs("world", 1500, 0, 5000));  // 故意放在最后一片
        List<ChunkHotspot> top = runFull(sweep, all);
        assertEquals(1500, top.get(0).chunkX(), "the global maximum must survive trimming");
    }

    @Test
    void partialResultIsNonNullAndMonotonicWhileFirstRoundRuns() {
        // 首轮每片都发布部分 Top-N：内容应随扫描推进单调"变好"（最大实体数不减）。
        // 注意：整轮结束时 finish() 会清空候选池（本轮已无用处，且避免白占内存），
        // 所以"完成后的 partialTopN()"是空列表——这里只在未完成时比较。
        HotspotShardSweep sweep = new HotspotShardSweep(100, 10);
        int bestSoFar = -1;
        List<ChunkHotspot> complete = null;
        while (!sweep.isComplete()) {
            int from = sweep.rangeStart();
            int to = sweep.rangeEnd();
            List<ChunkHotspot> scanned = new ArrayList<>();
            for (int i = from; i < to; i++) {
                scanned.add(hs("world", i, 0, i + 1));   // 下标越大，实体越多
            }
            List<ChunkHotspot> done = sweep.accept(scanned);
            if (done != null) {
                complete = done;
                break;
            }
            List<ChunkHotspot> partial = sweep.partialTopN();
            assertTrue(partial.size() <= HotspotShardSweep.TOP_N);
            int best = partial.isEmpty() ? -1 : partial.get(0).entityCount();
            assertTrue(best >= bestSoFar,
                "partial Top-1 must not get worse as the round advances (" + bestSoFar + " -> " + best + ")");
            bestSoFar = best;
        }
        assertNotNull(complete, "the round must complete");
        assertEquals(100, complete.get(0).entityCount(), "last scanned block is the global max");
    }

    @Test
    void partialTopNIsAnIndependentSnapshot() {
        // 部分结果会直接被挂进快照缓存；若返回内部列表，下一次 accept 的 addAll 会篡改已发布的快照。
        HotspotShardSweep sweep = new HotspotShardSweep(100, 10);
        sweep.accept(List.of(hs("world", 0, 0, 5)));
        List<ChunkHotspot> snapshot = sweep.partialTopN();
        assertEquals(1, snapshot.size());
        sweep.accept(List.of(hs("world", 1, 0, 9), hs("world", 2, 0, 9)));
        assertEquals(1, snapshot.size(), "previously published list must not grow");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(hs("w", 9, 9, 9)));
    }

    @Test
    void rankingTieBreaksAreDeterministic() {
        // 平票时必须按 世界名 → chunkX → chunkZ 排序，否则 Top-5 会在同票区块间随机换位。
        List<ChunkHotspot> items = new ArrayList<>(List.of(
            hs("world_nether", 0, 0, 50),
            hs("world", 5, 5, 50),
            hs("world", 5, 1, 50),
            hs("world", 1, 1, 50)));
        items.sort(HotspotShardSweep.RANKING);
        assertEquals("world", items.get(0).world());
        assertEquals(1, items.get(0).chunkX());
        assertEquals(5, items.get(1).chunkX());
        assertEquals(1, items.get(1).chunkZ(), "same chunkX: smaller chunkZ first");
        assertEquals("world_nether", items.get(3).world(), "world name breaks the tie last-but-one");
    }

    @Test
    void stepIntervalKeepsWorkPerCycleAtOrBelowTheOldFullSweep() {
        // 核心安全不变式：分片数 × 步长 ≥ sample-every-cycles，
        // 即改造后单位时间扫过的区块数不会比"每 sample-every 周期全扫一次"更多。
        int[] shards = {1, 2, 3, 5, 7, 10, 13, 30, 64, 100};
        for (int every : new int[]{1, 3, 7, 29, 30, 31, 100}) {
            for (int n : shards) {
                int step = HotspotShardSweep.stepInterval(every, n);
                assertTrue(step >= 1);
                assertTrue((long) n * step >= every,
                    "shards=" + n + " step=" + step + " cycles/round=" + (n * step)
                        + " must be >= every=" + every);
            }
        }
        // 具体到默认档：30 周期 / 10 片 → 每 3 周期一片，一轮 30 周期（与旧版本刷新频率相同）
        assertEquals(3, HotspotShardSweep.stepInterval(30, 10));
    }

    @Test
    void stepIntervalHandlesSmallServersWithoutSpeedingUp() {
        // 反例保护：4 个区块配 10 片时实际只切 4 片。若步长仍按配置的 10 算（30/10=3），
        // 一轮会在 12 个周期内扫完 4 个区块 —— 单位时间工作量比旧版的 30 周期高 2.5 倍。
        HotspotShardSweep sweep = new HotspotShardSweep(4, 10);
        int step = HotspotShardSweep.stepInterval(30, sweep.shardCount());
        assertEquals(8, step, "30 cycles / 4 actual shards = 8");
        assertTrue((long) sweep.shardCount() * step >= 30);
    }

    @Test
    void emptyServerStillCompletesInOneStep() {
        HotspotShardSweep sweep = new HotspotShardSweep(0, 10);
        assertEquals(0, sweep.total());
        assertEquals(0, sweep.rangeStart());
        assertEquals(0, sweep.rangeEnd());
        assertEquals(List.of(), sweep.accept(List.of()));
        assertTrue(sweep.isComplete());
    }
}
