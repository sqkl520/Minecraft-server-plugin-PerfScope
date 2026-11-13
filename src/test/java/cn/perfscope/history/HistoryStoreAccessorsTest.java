/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.history;

import cn.perfscope.config.ConfigManager;
import cn.perfscope.support.HistoryFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HistoryStore} 的三个访问器：{@link HistoryStore#capacity()}、
 * {@link HistoryStore#windowMs()}、{@link HistoryStore#gaps(long, long)}。
 *
 * <h2>为什么这个类值得存在</h2>
 *
 * <p>前两个是一行常量返回，第三个有真实的边界逻辑——而三者此前<b>零测试</b>。
 * 实测方式：把 {@code gaps()} 的阈值算法改烂（{@code threshold} 恒为 1），
 * 329 个用例<b>一个都没红</b>。也就是说那段代码坏了不会有任何信号。</p>
 *
 * <p>{@code gaps()} 的注释写它用于「{@code /perfscope trend} 输出数据缺口 N 处」，
 * 但 {@code trend} 命令的当前实现并没有调用它——这是写了没接线的能力。补测试的价值
 * 不在于"证明它现在对"，而在于<b>把它锁住</b>：将来谁要接这个线，改坏了会立刻知道；
 * 谁要判断"该删还是该接"，也有行为依据可看。</p>
 */
class HistoryStoreAccessorsTest {

    private static final long MINUTE = 60_000L;

    // ==================== capacity / windowMs ====================

    /**
     * 容量固定 1440 条，与采样间隔解耦。
     *
     * <p>这个数字本身是契约：{@code CAPACITY} 曾经由 {@code 86400 / intervalSec} 反推，
     * 于是"把间隔调大"会连带把容量改小。9A-18 改成固定值后，容量与间隔是两件事。
     * 断言具体数字（而不是 {@code > 0}）正是为了在有人改回反推算法时立刻失败。</p>
     */
    @Test
    void capacityIsAFixed1440IndependentOfInterval() {
        ConfigManager cm = HistoryFixtures.config(c -> c.history_sampleIntervalSeconds = 60);
        HistoryStore store = HistoryFixtures.seeded(cm, List.of());

        assertEquals(1440, store.capacity(),
            "capacity is a memory bound, not a function of the sampling interval");

        // 换一个天差地别的间隔，容量必须纹丝不动
        ConfigManager cm2 = HistoryFixtures.config(c -> c.history_sampleIntervalSeconds = 3600);
        HistoryStore store2 = HistoryFixtures.seeded(cm2, List.of());
        assertEquals(1440, store2.capacity(),
            "capacity must not track the interval, whatever the interval is");
    }

    /** 淘汰窗口固定 24 小时。 */
    @Test
    void windowIs24Hours() {
        ConfigManager cm = HistoryFixtures.config();
        HistoryStore store = HistoryFixtures.seeded(cm, List.of());

        assertEquals(24L * 60L * 60L * 1000L, store.windowMs());
    }

    // ==================== gaps：正常路径 ====================

    /**
     * 相邻样本间隔超过 2× 期望间隔才算缺口。
     *
     * <p>边界是「严格大于」：恰好 2× 不算缺口。这条边界此前无人验证。</p>
     */
    @Test
    void gapIsReportedOnlyWhenTheJumpExceedsTwiceTheExpectedInterval() {
        long now = System.currentTimeMillis();
        long step = MINUTE;
        // 时间线：0, 1, 2 分钟（正常），然后跳到 10 分钟（缺口），再到 11 分钟
        List<HistorySample> samples = List.of(
            sample(now - 11 * step),
            sample(now - 10 * step),
            sample(now - 9 * step),
            sample(now - 1 * step),
            sample(now));

        HistoryStore store = HistoryFixtures.seeded(HistoryFixtures.config(), samples);
        List<HistoryStore.Gap> gaps = store.gaps(0L, step);

        assertEquals(1, gaps.size(), "exactly one jump exceeds 2x: " + gaps);
        HistoryStore.Gap g = gaps.get(0);
        assertEquals(now - 9 * step, g.startMs(), "start is the sample BEFORE the hole");
        assertEquals(now - 1 * step, g.endMs(), "end is the sample AFTER the hole");
        assertEquals(8 * step, g.durationMs());
    }

    /**
     * 恰好 {@code 2 × expected} 的间隔<b>不算</b>缺口——判据是严格大于。
     *
     * <p>这条钉住 {@code >} 与 {@code >=} 的差别。采集抖动会让间隔偶尔略超期望值，
     * 用 {@code >=} 会把每一次轻微抖动都报成缺口。</p>
     */
    @Test
    void exactlyTwiceTheExpectedIntervalIsNotAGap() {
        long now = System.currentTimeMillis();
        long step = MINUTE;
        List<HistorySample> samples = List.of(
            sample(now - 2 * step),
            sample(now));   // 间隔恰好 2 × step

        HistoryStore store = HistoryFixtures.seeded(HistoryFixtures.config(), samples);

        assertTrue(store.gaps(0L, step).isEmpty(),
            "exactly 2x is the boundary and must not count as a gap");
        assertEquals(1, store.gaps(0L, step - 1).size(),
            "one millisecond less expected interval makes the same jump a gap");
    }

    /** 间隔均匀的样本不产生任何缺口。 */
    @Test
    void regularSamplesProduceNoGaps() {
        long now = System.currentTimeMillis();
        long step = MINUTE;
        List<HistorySample> samples = new ArrayList<>();
        for (int i = 10; i >= 0; i--) {
            samples.add(sample(now - i * step));
        }

        HistoryStore store = HistoryFixtures.seeded(HistoryFixtures.config(), samples);

        assertTrue(store.gaps(0L, step).isEmpty(), "a perfectly regular series has no holes");
    }

    /** 少于两个样本时不可能有"相邻对"，因此没有缺口。 */
    @Test
    void fewerThanTwoSamplesYieldNoGaps() {
        long now = System.currentTimeMillis();
        ConfigManager cm = HistoryFixtures.config();

        assertTrue(HistoryFixtures.seeded(cm, List.of()).gaps(0L, MINUTE).isEmpty(),
            "empty store has nothing to compare");
        assertTrue(HistoryFixtures.seeded(cm, List.of(sample(now))).gaps(0L, MINUTE).isEmpty(),
            "a single sample has no neighbour");
    }

    // ==================== gaps：参数边界 ====================

    /**
     * 期望间隔非正时返回空列表（而不是抛异常或除零）。
     *
     * <p>这是给"配置被写成 0 或负数"留的兜底。注释承诺"参数非法时返回空列表"，
     * 此处把 {@code 0}、负数都钉住。</p>
     */
    @Test
    void nonPositiveExpectedIntervalYieldsNoGaps() {
        long now = System.currentTimeMillis();
        HistoryStore store = HistoryFixtures.seeded(HistoryFixtures.config(),
            List.of(sample(now - 10 * MINUTE), sample(now)));

        assertTrue(store.gaps(0L, 0L).isEmpty(), "expected interval 0 is not usable");
        assertTrue(store.gaps(0L, -1L).isEmpty(), "negative expected interval is not usable");
        assertTrue(store.gaps(0L, Long.MIN_VALUE).isEmpty(), "even the most negative value");
    }

    /**
     * 期望间隔极大时不得因乘 2 溢出成负数。
     *
     * <p>{@code expectedIntervalMs × 2} 在 {@code > Long.MAX_VALUE / 2} 时溢出，
     * 结果变负，使「{@code delta > threshold}」恒真——每个相邻对都被报成缺口。
     * 实现在这种情况下把阈值钳到 {@code Long.MAX_VALUE}（永不报缺口）。</p>
     */
    @Test
    void hugeExpectedIntervalDoesNotOverflowIntoFalseGaps() {
        long now = System.currentTimeMillis();
        HistoryStore store = HistoryFixtures.seeded(HistoryFixtures.config(),
            List.of(sample(now - 10 * MINUTE), sample(now)));

        List<HistoryStore.Gap> gaps = store.gaps(0L, Long.MAX_VALUE);

        assertTrue(gaps.isEmpty(),
            "an overflowing threshold must clamp to Long.MAX_VALUE, not wrap negative: " + gaps);
    }

    /** {@code sinceMs} 之前的样本不参与缺口统计，但也不残留成"半个缺口"。 */
    @Test
    void samplesBeforeSinceAreIgnoredEntirely() {
        long now = System.currentTimeMillis();
        long step = MINUTE;
        List<HistorySample> samples = List.of(
            sample(now - 100 * step),   // 很久以前，应被 since 排除
            sample(now - 2 * step),
            sample(now));

        HistoryStore store = HistoryFixtures.seeded(HistoryFixtures.config(), samples);
        List<HistoryStore.Gap> gaps = store.gaps(now - 3 * step, step);

        assertTrue(gaps.isEmpty(),
            "the old sample is filtered out, so the remaining two are 2x apart = no gap: " + gaps);
    }

    // ==================== Gap 记录本身 ====================

    /**
     * {@code durationMs()} 不会返回负数。
     *
     * <p>{@code Gap} 是公开 record，可以被任意构造。乱序时间戳（NTP 回拨）能造出
     * {@code endMs < startMs} 的情形；{@code durationMs()} 用 {@code Math.max(0,…)} 兜底。
     * 这个兜底如果被删，缺口时长会变成负数并污染下游的求和。</p>
     */
    @Test
    void gapDurationIsNeverNegative() {
        HistoryStore.Gap normal = new HistoryStore.Gap(1_000L, 5_000L);
        assertEquals(4_000L, normal.durationMs());

        HistoryStore.Gap inverted = new HistoryStore.Gap(5_000L, 1_000L);
        assertEquals(0L, inverted.durationMs(),
            "a backwards gap reports zero, not a negative duration");
    }

    /** 缺口列表是不可变的——调用方拿到后不能改到内部状态。 */
    @Test
    void gapListIsImmutable() {
        long now = System.currentTimeMillis();
        HistoryStore store = HistoryFixtures.seeded(HistoryFixtures.config(),
            List.of(sample(now - 10 * MINUTE), sample(now)));

        List<HistoryStore.Gap> gaps = store.gaps(0L, MINUTE);
        assertEquals(1, gaps.size());
        assertNotNull(gaps.get(0));

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
            () -> gaps.add(new HistoryStore.Gap(0L, 1L)),
            "the returned list must be an immutable copy");
    }

    // ==================== 辅助 ====================

    private static HistorySample sample(long ts) {
        return new HistorySample(ts, 20.0, 10.0, 50, 1, 100, 500);
    }
}
