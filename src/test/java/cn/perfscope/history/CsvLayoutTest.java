/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.history;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * f-s04-csv-05：CSV 表头与数据行必须共用同一份列顺序定义。
 *
 * <p>旧实现里 {@code HEADER} 由 {@code COLUMNS} 派生、{@code format()} 却手写 7 段拼接——
 * 两份独立定义一旦错位，读取端按列名映射就会把数据安到错误的列上，且**不报错**喵。</p>
 */
class CsvLayoutTest {

    private static HistorySample sample() {
        return new HistorySample(1_700_000_000_000L, 19.5, 8.25, 42, 3, 120, 4567);
    }

    @Test
    void headerAndRowHaveSameColumnCount() throws Exception {
        Method fmt = HistoryFileStore.class.getDeclaredMethod("format", HistorySample.class);
        fmt.setAccessible(true);
        String row = (String) fmt.invoke(null, sample());

        java.lang.reflect.Field f = HistoryFileStore.class.getDeclaredField("HEADER");
        f.setAccessible(true);
        String header = (String) f.get(null);

        // "# v2 a,b,c" -> 去掉 "# v2 " 前缀后按逗号切
        String cols = header.replaceFirst("^#\\s*v\\d+\\s*", "");
        assertEquals(cols.split(",", -1).length, row.split(",", -1).length,
            "header/row column count drift: header=" + cols + " row=" + row);
    }

    @Test
    void rowValuesFollowDeclarationOrder() throws Exception {
        Method fmt = HistoryFileStore.class.getDeclaredMethod("format", HistorySample.class);
        fmt.setAccessible(true);
        String row = (String) fmt.invoke(null, sample());
        assertEquals("1700000000000,19.5,8.25,42,3,120,4567", row);
    }

    @Test
    void writtenFileRoundTripsThroughReader() throws Exception {
        Path dir = Files.createTempDirectory("pscope-csv");
        HistoryFileStore store = new HistoryFileStore(null, dir, java.util.logging.Logger.getLogger("csv-test"));
        // 直接写一份文件，再走真实读取路径
        Path file = dir.resolve("history-2023-11-14.csv");
        java.lang.reflect.Field hf = HistoryFileStore.class.getDeclaredField("HEADER");
        hf.setAccessible(true);
        Method fmt = HistoryFileStore.class.getDeclaredMethod("format", HistorySample.class);
        fmt.setAccessible(true);
        Files.write(file, ((hf.get(null) + "\n" + fmt.invoke(null, sample()) + "\n")
            .getBytes(StandardCharsets.UTF_8)));

        Method read = HistoryFileStore.class.getDeclaredMethod("readFile", Path.class);
        read.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<HistorySample> out = (List<HistorySample>) read.invoke(store, file);

        assertEquals(1, out.size(), "one well-formed row must parse");
        HistorySample s = out.get(0);
        assertEquals(1_700_000_000_000L, s.timestamp());
        assertEquals(19.5, s.tps1m());
        assertEquals(42, s.heapUsagePercent());
        assertEquals(4567, s.totalLoadedChunks());
    }

    @Test
    void malformedRowIsCountedAsSkipped() throws Exception {
        Path dir = Files.createTempDirectory("pscope-csv-bad");
        HistoryFileStore store = new HistoryFileStore(null, dir, java.util.logging.Logger.getLogger("csv-test"));
        Path file = dir.resolve("history-2023-11-15.csv");
        java.lang.reflect.Field hf = HistoryFileStore.class.getDeclaredField("HEADER");
        hf.setAccessible(true);
        Files.write(file, ((hf.get(null) + "\nnot,a,valid,row\n").getBytes(StandardCharsets.UTF_8)));

        Method read = HistoryFileStore.class.getDeclaredMethod("readFile", Path.class);
        read.setAccessible(true);
        read.invoke(store, file);

        assertTrue(store.skippedLines() >= 1, "malformed row must be counted, not silently dropped");
    }
}
