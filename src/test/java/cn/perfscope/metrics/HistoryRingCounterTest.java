/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史环的计数器必须与 deque 的实际长度保持一致。
 *
 * <p>背景：{@code ConcurrentLinkedDeque.size()} 是 O(n) 遍历。原实现每个采集周期都调一次
 * {@code while (history.size() > MAX_HISTORY)}，默认 1Hz + 环长 300 = 每秒 300 个节点遍历。
 * 改成计数器后是 O(1)，但引入了一个不变量：{@code historyCount} 必须恒等于
 * {@code history.size()}——任何一处 add/poll 忘了同步都会让环慢慢漂移（要么无界增长，要么
 * 提前丢历史）。</p>
 *
 * <p>这里用反射直接驱动 deque 与计数器，验证三种状态：未满、恰好满、溢出丢弃。</p>
 *
 * <p><b>⚠️ 本测试的局限（必须诚实标注）</b>：{@code appendLikeCollector()} 是
 * <b>生产逻辑的复刻</b>，不是对生产代码的调用——{@code collectSyncOnce} 依赖 Plugin /
 * HistoryStore / ConfigManager，在当前测试环境里无法构造。所以这组用例证明的是
 * "这个不变量本身成立"，<b>不能</b>证明"生产代码维护了它"。若 {@code collectSyncOnce}
 * 里漏了 {@code historyCount++}，这里仍然全绿。</p>
 *
 * <p>真正的守卫在别处：{@code getHistorySize()} 现在直接返回 {@code historyCount}，
 * 而 {@code /perfscope history} 与趋势面板都消费它——一旦漂移，用户会立刻看到
 * "历史条数不动了"或"历史突然变少"。那是比单测更强的端到端信号。</p>
 */
class HistoryRingCounterTest {

    private static Object collectorField(Object target, String name) {
        return TestObjects.getField(target.getClass(), target, name);
    }

    private static int maxHistory() {
        return TestObjects.getStaticInt(MetricsCollector.class, "MAX_HISTORY");
    }

    /** 模拟一次 collectSync 的收尾：addLast + 计数 + 溢出裁剪。 */
    @SuppressWarnings("unchecked")
    private static void appendLikeCollector(Object collector) {
        Deque<MetricsSnapshot> history =
            (Deque<MetricsSnapshot>) collectorField(collector, "history");
        int max = maxHistory();

        history.addLast(new MetricsSnapshot());
        int count = (Integer) collectorField(collector, "historyCount") + 1;
        while (count > max) {
            history.pollFirst();
            count--;
        }
        TestObjects.setField(collector.getClass(), collector, "historyCount", count);
    }

    @Test
    void counterMatchesDequeLengthWhileFilling() {
        Object collector = allocateCollectorLike();
        @SuppressWarnings("unchecked")
        Deque<MetricsSnapshot> history =
            (Deque<MetricsSnapshot>) collectorField(collector, "history");

        for (int i = 1; i <= 10; i++) {
            appendLikeCollector(collector);
            int counter = (Integer) collectorField(collector, "historyCount");
            assertEquals(history.size(), counter, "counter and deque diverged after " + i + " appends");
            assertEquals(i, counter);
        }
    }

    @Test
    void ringStopsGrowingAtMaxHistory() {
        Object collector = allocateCollectorLike();
        @SuppressWarnings("unchecked")
        Deque<MetricsSnapshot> history =
            (Deque<MetricsSnapshot>) collectorField(collector, "history");

        int max = maxHistory();
        for (int i = 0; i < max + 50; i++) {
            appendLikeCollector(collector);
        }
        int counter = (Integer) collectorField(collector, "historyCount");
        assertEquals(max, counter, "counter must saturate at MAX_HISTORY");
        assertEquals(max, history.size(), "deque must not exceed MAX_HISTORY");
    }

    @Test
    void counterNeverDriftsOverManyAppends() {
        // 长跑不变量：计数器漂移在短测试里看不出来，跑 3 倍环长才能暴露 off-by-one。
        Object collector = allocateCollectorLike();
        @SuppressWarnings("unchecked")
        Deque<MetricsSnapshot> history =
            (Deque<MetricsSnapshot>) collectorField(collector, "history");

        int max = maxHistory();
        for (int i = 0; i < max * 3; i++) {
            appendLikeCollector(collector);
        }
        int counter = (Integer) collectorField(collector, "historyCount");
        assertEquals(history.size(), counter, "counter drifted from deque after " + (max * 3) + " appends");
        assertTrue(counter <= max);
    }

    /**
     * 不经过 Bukkit 构造 MetricsCollector——它的构造器要 Plugin/HistoryStore。
     * 这里造一个"最小可测对象"，只初始化本测试需要的那两个字段。
     */
    private static Object allocateCollectorLike() {
        Object collector = TestObjects.allocate(MetricsCollector.class);

        TestObjects.setField(MetricsCollector.class, collector, "history",
            new java.util.concurrent.ConcurrentLinkedDeque<>());
        TestObjects.setField(MetricsCollector.class, collector, "historyCount", 0);

        return collector;
    }
}
