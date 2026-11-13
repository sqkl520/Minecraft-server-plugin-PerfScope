/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.support;

import cn.perfscope.config.ConfigManager;
import cn.perfscope.history.HistorySample;
import cn.perfscope.history.HistoryStore;
import cn.perfscope.metrics.MetricsSnapshot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * {@link HistoryStore} 的两种装载路径 + 一个配置夹具。
 *
 * <p>两条路径对应两类断言，用错了测试会"通过"但什么都没验证：</p>
 * <ul>
 *   <li>{@link #seeded} —— 直接写私有环，<b>样本时间戳说什么就是什么</b>。
 *       要构造"很久以前"的窗口（24h 淘汰、跨天、陈旧覆盖）必须用它。</li>
 *   <li>{@link #recorded} —— 走真实的 {@link HistoryStore#record}，只把节流基准归零。
 *       TPS 不可用守卫、GC 的 -1 哨兵、乱序重排这些<b>只在 record() 里发生</b>的行为
 *       只有这条路能执行到。</li>
 * </ul>
 *
 * <h2>为什么 {@code recorded} 不能拿来测时间窗</h2>
 *
 * <p>{@code record()} 的淘汰口径是<b>墙钟</b>：{@code now = System.currentTimeMillis()}，
 * 凡 {@code timestamp < now - 24h} 的样本立即丢弃。所以写成</p>
 *
 * <pre>{@code
 * // 错：这条测试"通过"，但环里一条都没有
 * List<MetricsSnapshot> snaps = List.of(snapAt(1_760_000_000_000L));
 * HistoryStore hs = HistoryFixtures.recorded(cm, snaps);
 * assertEquals(1, hs.samples().size());   // 实际是 0 —— 固定 epoch 早已超过 24h
 * }</pre>
 *
 * <p>固定 epoch 常量（如 {@code 1_760_000_000_000L}）在写这条测试的时刻通常是"过去某天"，
 * 但只要测试运行日往后推，超过 24h 后样本就会被整体淘汰，{@code size()==0}，
 * 而"没有样本"往往被上层规则解释成"数据不足 → skip"，于是断言仍然"成立"。
 * <b>要测很久以前的窗口，用 {@link #seeded}。</b> {@code recorded} 的时间戳请始终相对
 * {@code System.currentTimeMillis()} 生成。</p>
 */
public final class HistoryFixtures {

    private HistoryFixtures() {
    }

    // ==================== 两条装载路径 ====================

    /**
     * 造一个<b>只含给定样本</b>的 {@link HistoryStore}：绕过节流与淘汰，直接把样本塞进私有环。
     *
     * <p>这是原先散落在 4 个测试类里的 {@code history(cm, samples)} 的等价替代——
     * 同参数、同返回、同行为（{@code configManager} 指向传入的 {@code cm}，
     * {@code ring} 是 {@code ArrayDeque} 拷贝，{@code lastRecordMs} 保持 0）。
     * 因此调用方只需删掉私有方法 + 加 import。</p>
     *
     * <p>不做的事：不排序、不过滤、不淘汰、不碰 {@code fileStore}。环里放什么就是什么，
     * 迭代序即传入列表序——消费方（如 {@code gaps}）依赖"旧→新"，请自行保证。</p>
     *
     * @param cm      提供 {@code history.enabled} / 间隔等读数的配置源；可为 {@link #config} 的产物
     * @param samples 环内容，按传入顺序
     */
    public static HistoryStore seeded(ConfigManager cm, List<HistorySample> samples) {
        HistoryStore store = new HistoryStore(cm, Logger.getLogger("pscope-fixture-seeded"));
        Deque<HistorySample> ring = new ArrayDeque<>(samples);
        TestObjects.setField(HistoryStore.class, store, "ring", ring);
        return store;
    }

    /**
     * 造一个 {@link HistoryStore} 并把快照<b>逐条交给真实的 {@link HistoryStore#record}</b>。
     *
     * <p>与 {@link #seeded} 的差别是这条会真的跑 {@code record()} 的全部门控：</p>
     * <ol>
     *   <li>{@code history.enabled} 关闭 → 直接返回；</li>
     *   <li>{@code snap.unavailable} 含 {@code MODULE_TPS} → 整条不记（污染防护）；</li>
     *   <li>节流：距上次入环不足 {@code history_sampleIntervalSeconds} → 丢弃；</li>
     *   <li>入环 + 24h 墙钟淘汰 + 容量兜底 + 乱序重排；</li>
     *   <li>（{@code fileStore} 为 null，不落盘。）</li>
     * </ol>
     *
     * <p><b>每次 {@code record()} 前都把非 final 的 {@code lastRecordMs} 归零</b>，
     * 这样节流不会吃掉样本，上面的 1/2/4 三条仍然原样生效。归零而非改小间隔：
     * 间隔本身也是被测配置的一部分，替调用方改掉它就等于替调用方做了一半断言。</p>
     *
     * <p><b>时间戳必须相对当前墙钟</b>，理由见类注释。传入固定 epoch 会得到空环。</p>
     *
     * @param cm    配置源；通常来自 {@link #config}
     * @param snaps 按顺序喂给 {@code record()} 的快照
     */
    public static HistoryStore recorded(ConfigManager cm, List<MetricsSnapshot> snaps) {
        HistoryStore store = new HistoryStore(cm, Logger.getLogger("pscope-fixture-recorded"));
        for (MetricsSnapshot snap : snaps) {
            // record() 只在 lastRecordMs != 0 时判定节流；归零即"从未采样"，本条必放行。
            TestObjects.setField(HistoryStore.class, store, "lastRecordMs", 0L);
            store.record(snap);
        }
        return store;
    }

    // ==================== 配置夹具 ====================

    /**
     * 造一个已发布的 {@link ConfigManager}（构造器不需要 Bukkit，见 {@link TestObjects} 约束二）。
     *
     * <p>默认值与历史测试的既有口径一致：{@code history.enabled=true}、
     * {@code history.sample-interval-seconds=60}。需要用别的字段时在 {@code tweaks} 里改。</p>
     *
     * <p>注意它<b>不</b>设置 {@code plugin} 字段：本夹具只服务于读配置的路径
     * （历史/趋势规则）。要调 {@code validateThresholds} 这类会用
     * {@code plugin.getLogger()} 的方法，请自行
     * {@code TestObjects.setField(ConfigManager.class, cm, "plugin", barePlugin)}。</p>
     */
    public static ConfigManager config(Consumer<ConfigManager.ConfigData> tweaks) {
        ConfigManager cm = new ConfigManager(null);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.history_enabled = true;
        cfg.history_sampleIntervalSeconds = 60;
        if (tweaks != null) {
            tweaks.accept(cfg);
        }
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        return cm;
    }

    /** 默认配置的历史夹具（{@code history.enabled=true}、间隔 60 秒）。 */
    public static ConfigManager config() {
        return config(null);
    }
}
