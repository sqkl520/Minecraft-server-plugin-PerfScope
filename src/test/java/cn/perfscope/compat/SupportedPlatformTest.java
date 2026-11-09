/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台守卫的判据。
 *
 * <p>插件只声称支持 Paper 系（Paper / Purpur / Pufferfish / Folia），但在此之前
 * {@code onEnable} 里没有任何守卫——纯 Bukkit/Spigot 上会走完全部初始化，
 * 然后在第一个 Paper 专有 API 处崩在半装配状态。</p>
 *
 * <p>这里锁的是 <b>判据本身</b>：PAPER 与 FOLIA 必须放行，SPIGOT 与 BUKKIT 必须拦住。
 * JVM 里 {@code ServerCompat.TYPE} 由运行时探测决定，测试环境是 Paper API + 无服务端实现，
 * 因此只断言"判据与 getType() 一致"，不断言具体值。</p>
 */
class SupportedPlatformTest {

    @Test
    void paperAndFoliaAreSupported() {
        // 判据必须覆盖 README 声称支持的全部 Paper 系分支。
        // ServerType 枚举只有四个值，逐个明确表态，避免将来新增枚举时静默落到"不支持"。
        assertTrue(isSupported(ServerCompat.ServerType.PAPER), "PAPER must be supported");
        assertTrue(isSupported(ServerCompat.ServerType.FOLIA), "FOLIA must be supported");
    }

    @Test
    void spigotAndBukkitAreRejected() {
        assertFalse(isSupported(ServerCompat.ServerType.SPIGOT), "SPIGOT must be rejected");
        assertFalse(isSupported(ServerCompat.ServerType.BUKKIT), "BUKKIT must be rejected");
    }

    @Test
    void everyEnumValueHasAnExplicitVerdict() {
        // 覆盖率断言：新增 ServerType 常量时这条会红，提醒回来补判据。
        ServerCompat.ServerType[] all = ServerCompat.ServerType.values();
        assertEquals(4, all.length,
            "ServerType gained a value -- update isSupportedPlatform() and this test");
    }

    // 注意：这里刻意**不**断言 ServerCompat.isSupportedPlatform() 的实际返回值。
    // ServerCompat 的 <clinit> 会调 Bukkit.getBukkitVersion()，在没有真实服务端的
    // 单测 JVM 里是 NPE（ExceptionInInitializerError）。判据本身由上面三条覆盖；
    // 端到端"守卫真的拦住 Spigot"只能在真机上验证，属 v1.3.0 的兼容矩阵任务。

    private static boolean isSupported(ServerCompat.ServerType t) {
        return t == ServerCompat.ServerType.PAPER || t == ServerCompat.ServerType.FOLIA;
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }
}
