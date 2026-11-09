/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.report;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * f-s05-sec-03 / N-A6：报告清理的「归属判定」。
 *
 * <p>这里的每个用例都来自五波次审计的实测记录，不是想象出来的：
 * 插件自己生成的名字（含 -N 碰撞后缀）必须被清理，管理员放在 reports/ 里的
 * 任何文件都绝不能碰。正则只校位数不校值域时，13 月 / 25 时这类非法时间戳
 * 也会被当成插件产物删掉，故补一条值域正则。</p>
 */
class ReportFileNameTest {

    private static boolean owned(String name) throws Exception {
        Method m = ReportGenerator.class.getDeclaredMethod("isReportFileName", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, name);
    }

    @Test
    void pluginGeneratedNamesAreOwned() throws Exception {
        String[] names = {
            "perfscope-2026-11-03_22-05-07-123.html",
            "perfscope-2026-11-03_22-05-07-123.txt",
            "perfscope-2026-11-03_22-05-07-123.json",
            // writeReportAtomically 撞名时追加的 -N：漏掉这一组会让碰撞文件永不清理
            "perfscope-2026-11-03_22-05-07-123-1.html",
            "perfscope-2026-11-03_22-05-07-123-10.json",
            // 边界值
            "perfscope-2026-01-01_00-00-00-000.txt",
            "perfscope-2026-12-31_23-59-59-999.html",
            "perfscope-2024-02-29_12-00-00-000.json"
        };
        for (String n : names) {
            assertTrue(owned(n), "plugin-generated report must be owned: " + n);
        }
    }

    @Test
    void administratorFilesAreNeverOwned() throws Exception {
        String[] names = {
            "perfscope-notes.txt",
            "perfscope-2026.html",
            "perfscope-report.json",
            "perfscope-2026-11-03.txt",
            "perfscope-2026-11-03 22-05-07-123.txt",
            "perfscope-x.html",
            "perfscope-.txt",
            "notes.txt",
            "perfscope-2026-11-03_22-05-07-123.HTML",
            "perfscope-2026-11-03_22-05-07-123",
            null
        };
        for (String n : names) {
            assertFalse(owned(n), "foreign file must never be deleted: " + n);
        }
    }

    @Test
    void invalidTimestampFieldsAreNotOwned() throws Exception {
        String[] names = {
            "perfscope-2026-13-03_22-05-07-123.html",   // 月 13
            "perfscope-2026-00-03_22-05-07-123.html",   // 月 00
            "perfscope-2026-11-00_22-05-07-123.html",   // 日 00
            "perfscope-2026-11-32_22-05-07-123.html",   // 日 32
            "perfscope-2026-11-03_25-05-07-123.html",   // 时 25
            "perfscope-2026-11-03_24-05-07-123.html",   // 时 24
            "perfscope-2026-11-03_22-60-07-123.html",   // 分 60
            "perfscope-2026-11-03_22-05-60-123.html"    // 秒 60
        };
        for (String n : names) {
            assertFalse(owned(n), "invalid time field must not be treated as ours: " + n);
        }
    }
}
