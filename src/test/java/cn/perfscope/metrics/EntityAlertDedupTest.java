/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全服实体总数告警与逐世界实体告警的重叠去重（v1.4）。
 *
 * <p><b>修的是什么</b>：配置了 {@code worlds:} 覆盖的世界，其实体数同时计入全服合计。
 * 于是同一个世界超标会产出两条消息——{@code entities:world:world} 与 {@code entities:global}，
 * 而两条说的其实是同一批实体。首次安装时这正是"一次负载四条告警"的来源。</p>
 *
 * <p><b>修法只动"发不发消息"</b>：{@code snap.totalEntities} 是 /perfscope entities、
 * 报告与 Prometheus 的<b>展示数字</b>，不参与这里的判断；全服级别也照常写入
 * {@code alertLevels}（{@code perfscope_alert} 的状态不因去重而缺失）。
 * 因此本类的用例锁的是"消息级去重"，不是"指标口径变更"。</p>
 *
 * <p><b>为什么必须有这条边界用例</b>：最容易写错的去重是"只要有任何逐世界告警就压掉全服告警"。
 * 那会吞掉真正的全服信号——三个世界各差一点、加起来超标（逐世界口径看不见），
 * 以及全服升到 critical 而各世界只有 warning 的情况。这两种都必须发，见下。</p>
 */
class EntityAlertDedupTest {

    /** 没有任何逐世界越界：全服口径是唯一信号，必须发。 */
    @Test
    void globalAlertIsEmittedWhenNoWorldAlertFired() {
        assertTrue(MetricsCollector.shouldEmitGlobalEntitiesAlert(1, 0),
            "no per-world alert -> the global alert is the only signal, it must be emitted");
        assertTrue(MetricsCollector.shouldEmitGlobalEntitiesAlert(2, 0));
    }

    /**
     * 全服级别高于逐世界最高级别：必须发。
     *
     * <p>典型场景：每个世界各自只是 warning，合起来已经 critical。全服那条携带的
     * "整体已严重"是逐世界消息里没有的信息。</p>
     */
    @Test
    void globalAlertIsEmittedWhenStricterThanEveryWorld() {
        assertTrue(MetricsCollector.shouldEmitGlobalEntitiesAlert(2, 1),
            "global critical + per-world warning carries new information (the whole is critical)");
    }

    /**
     * 全服级别等于或低于逐世界最高级别：静默。
     *
     * <p>这是去重的实际收益：同一级别下，逐世界那条已经点名了具体世界，更具体、更可操作。</p>
     */
    @Test
    void globalAlertIsSuppressedWhenAWorldSaysTheSameOrWorse() {
        assertFalse(MetricsCollector.shouldEmitGlobalEntitiesAlert(1, 1),
            "same level: the per-world message already says it, and says it more specifically");
        assertFalse(MetricsCollector.shouldEmitGlobalEntitiesAlert(2, 2));
        assertFalse(MetricsCollector.shouldEmitGlobalEntitiesAlert(1, 2),
            "a per-world critical already covers a global warning");
    }

    /** 全服未越界时永远不发（调用方本就不会进这个分支，这里锁住判据本身的完备性）。 */
    @Test
    void noGlobalAlertWhenTotalIsHealthy() {
        assertFalse(MetricsCollector.shouldEmitGlobalEntitiesAlert(0, 0));
        assertFalse(MetricsCollector.shouldEmitGlobalEntitiesAlert(0, 2));
    }
}
