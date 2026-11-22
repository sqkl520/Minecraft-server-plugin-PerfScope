/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code general.bstats-enabled} 在 {@code /perfscope reload} 后是否真的生效。
 *
 * <h2>这条守的是什么</h2>
 *
 * <p>修前 {@code reload()} 重建了 Prometheus / network / webhook / 日报，<b>唯独跳过 bStats</b>——
 * 遥测句柄只在 {@code onEnable} 里创建。后果是：服主把 {@code general.bstats-enabled} 改成
 * {@code false} 再 {@code /perfscope reload}，数据会继续发到下一次重启。</p>
 *
 * <p>这不是"配置没刷新"那种小毛病，而是<b>隐私开关失效</b>：开关的字面意思（也是文档里写的
 * 意思，见 README「数据披露」）是"关掉就不发"，而它的实际语义变成了"下次重启才关"，
 * 且没有任何提示告诉用户真相。开关被违反的方向对用户不利，所以必须真的停，而不是只打一句
 * "需重启生效"。</p>
 *
 * <h2>为什么用纯函数而不是端到端</h2>
 *
 * <p>真实路径要一个能构造 {@code org.bstats.bukkit.Metrics} 的 Bukkit 环境（它读
 * {@code plugins/bStats/config.yml}、起一个 {@code ScheduledThreadPoolExecutor}、
 * 往 bStats 官网发 HTTP），本仓库没有 MockBukkit（见 {@code tools/KNOWN-TEST-LIMITS.md}）。
 * 所以把四象限决策抽成 {@link PerfScope#bstatsReloadAction}，穷举它；"reload 真的调了这个决策"
 * 由 {@code StatusExportsTest#reloadRebuildsBstatsAsWell} 的源码断言补上。</p>
 *
 * <h2>哪条用例是真正的靶点</h2>
 *
 * <p>{@link #disablingBstatsThenReloadingMustStopTelemetry()}。其余三格修前修后都"对"
 * （因为修前根本没有这段逻辑，它们只是把新函数的语义钉死）。</p>
 */
class PerfScopeBstatsReloadTest {

    /**
     * <b>靶点</b>：配置说关、句柄还在 → 必须 STOP。
     *
     * <p>把 {@code bstatsReloadAction} 里 {@code return running ? STOP : REFRESH_STATE} 改成
     * 无条件 {@code REFRESH_STATE}，这条立刻变红——而真实的
     * {@code general.bstats-enabled: false + /perfscope reload} 就会退化成"数据继续发"。</p>
     */
    @Test
    void disablingBstatsThenReloadingMustStopTelemetry() {
        assertEquals(PerfScope.BstatsReloadAction.STOP,
            PerfScope.bstatsReloadAction(false, true),
            "reload 后配置说关、句柄还在 = 隐私开关没生效，必须真的停掉遥测");
    }

    @Test
    void reloadingWithBstatsStillEnabledDoesNotRestartItNeedlessly() {
        assertEquals(PerfScope.BstatsReloadAction.KEEP,
            PerfScope.bstatsReloadAction(true, true),
            "配置开着且已在跑时不要重建——每次重建都会重读一次 bStats/config.yml 并换一个提交线程");
    }

    @Test
    void enablingBstatsByReloadStartsIt() {
        assertEquals(PerfScope.BstatsReloadAction.START,
            PerfScope.bstatsReloadAction(true, false),
            "配置开着但句柄不在（首次启用 / 上次初始化失败）时才启动");
    }

    @Test
    void reloadingWithBstatsOffFromTheStartOnlyRefreshesTheState() {
        assertEquals(PerfScope.BstatsReloadAction.REFRESH_STATE,
            PerfScope.bstatsReloadAction(false, false),
            "本来就关着：只需同步 /status 的状态位，不该打一条'已停止'的假回执");
    }

    /**
     * 决策函数的四格互不相同：任何两格合并都会让至少一条上面的断言变红。
     *
     * <p>这条不是冗余——它是"四个用例真的覆盖了四个分支"的自检。如果将来有人把枚举
     * 合并成两态（比如把 REFRESH_STATE 与 KEEP 合并），上面四条会同时通过而语义已经丢失。</p>
     */
    @Test
    void allFourQuadrantsAreDistinct() {
        PerfScope.BstatsReloadAction[] all = {
            PerfScope.bstatsReloadAction(true, false),
            PerfScope.bstatsReloadAction(true, true),
            PerfScope.bstatsReloadAction(false, true),
            PerfScope.bstatsReloadAction(false, false),
        };
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                org.junit.jupiter.api.Assertions.assertNotEquals(all[i], all[j],
                    "quadrant " + i + " and " + j + " collapsed into the same action ("
                        + all[i] + ") — the four reload behaviours are no longer distinguishable");
            }
        }
    }
}
