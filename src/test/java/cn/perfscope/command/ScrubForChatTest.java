/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.command;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * f-s02-sec-02：进聊天框前的异常摘要脱敏。
 *
 * <p>{@code /perfscope status} 的受众是 {@code perfscope.use}，未必是服主本人，
 * 而采集异常原文里常含绝对路径与服务端目录结构喵。</p>
 */
class ScrubForChatTest {

    private static String scrub(String raw) throws Exception {
        Method m = PerfScopeCommand.class.getDeclaredMethod("scrubForChat", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, raw);
    }

    @Test
    void windowsPathIsRedacted() throws Exception {
        String out = scrub("java.io.IOException: C:\\Users\\admin\\servers\\mc\\world is locked");
        assertFalse(out.contains("C:\\Users"), "windows path must not reach chat: " + out);
        assertTrue(out.contains("<path>"), "path placeholder expected: " + out);
        assertTrue(out.contains("IOException"), "exception type must be preserved for triage: " + out);
    }

    @Test
    void posixPathIsRedacted() throws Exception {
        String out = scrub("java.nio.file.AccessDeniedException: /srv/minecraft/plugins/PerfScope/history");
        assertFalse(out.contains("/srv/minecraft"), "posix path must not reach chat: " + out);
        assertTrue(out.contains("AccessDeniedException"), "exception type must be preserved: " + out);
    }

    @Test
    void stackTraceIsTruncatedToFirstLine() throws Exception {
        String out = scrub("java.lang.IllegalStateException: boom\n\tat a.b.C(D.java:1)\n\tat d.E(F.java:2)");
        assertFalse(out.contains("at a.b.C"), "stack frames must not reach chat: " + out);
        assertTrue(out.contains("boom"), "message must survive: " + out);
    }

    @Test
    void nullAndPlainTextAreSafe() throws Exception {
        assertEquals("", scrub(null));
        assertEquals("plain message", scrub("plain message"));
    }

    @Test
    void longMessageIsTruncated() throws Exception {
        String out = scrub("x".repeat(500));
        assertTrue(out.length() <= 210, "chat line must stay bounded, got " + out.length());
    }
}
