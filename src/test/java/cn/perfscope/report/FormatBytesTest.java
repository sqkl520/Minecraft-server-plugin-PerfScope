/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.report;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * f-s05-logic-04：{@code formatBytes} 的换档判据必须与输出精度同口径。
 *
 * <p>旧实现按原始字节判档、按 {@code %.1f} 输出，于是「比 1 MB 少 1 字节」的值
 * 会显示成 {@code "1024.0 KB"}，读者以为该进位却没进位喵。</p>
 */
class FormatBytesTest {

    private static String fmt(long bytes) throws Exception {
        Method m = ReportGenerator.class.getDeclaredMethod("formatBytes", long.class);
        m.setAccessible(true);
        // formatBytes 是实例方法，且不触碰任何实例字段 -> 用反射在 null 上调用不安全，
        // 故构造一个最小实例（ReportGenerator 构造器需要 plugin，这里改用 Unsafe 绕过）。
        Object unsafe = unsafeInstance();
        Object gen = allocate(ReportGenerator.class);
        return (String) m.invoke(gen, bytes);
    }

    private static Object unsafeInstance() throws Exception {
        Class<?> c = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = c.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object allocate(Class<?> k) throws Exception {
        return unsafeInstance().getClass()
            .getMethod("allocateInstance", Class.class).invoke(unsafeInstance(), k);
    }

    @Test
    void doesNotPrintOneThousandTwentyFourPointZeroKb() throws Exception {
        // 1048575 B = 1 MB - 1 B：旧实现输出 "1024.0 KB"
        assertEquals("1.0 MB", fmt(1_048_575L));
        // 1073741823 B = 1 GB - 1 B：旧实现输出 "1024.0 MB"
        assertEquals("1.00 GB", fmt(1_073_741_823L));
    }

    @Test
    void normalValuesStillFormatAsBefore() throws Exception {
        assertEquals("512 B", fmt(512L));
        assertEquals("1.0 KB", fmt(1024L));
        assertEquals("1.5 KB", fmt(1536L));
        assertEquals("1.0 MB", fmt(1024L * 1024L));
        assertEquals("1.50 GB", fmt(1536L * 1024L * 1024L));   // 1.5 GiB
        assertEquals("1.00 GB", fmt(1024L * 1024L * 1024L));   // 1 GiB
    }

    @Test
    void boundaryJustBelowOneMbStaysInKb() throws Exception {
        // 1048000 B / 1024 = 1023.4 KB，舍入后仍 < 1024 -> 留在 KB 档
        assertEquals("1023.4 KB", fmt(1_048_000L));
    }

    @Test
    void negativeIsTolerated() throws Exception {
        assertEquals("-1 B", fmt(-1L));
    }
}
