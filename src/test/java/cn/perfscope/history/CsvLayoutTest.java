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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * f-s04-csv-05：CSV 表头与数据行必须共用同一份列顺序定义。
 *
 * <p>旧实现里 {@code HEADER} 由 {@code COLUMNS} 派生、{@code format()} 却手写 7 段拼接——
 * 两份独立定义一旦错位，读取端按列名映射就会把数据安到错误的列上，且**不报错**喵。</p>
 *
 * <p><b>v1.4 追加的兼容契约</b>（这些用例是加列的验收靶点，不是装饰）：</p>
 * <ul>
 *   <li>v1.3 写出的 7 列行（无论是 v2 表头还是无表头）必须读得出前 7 个字段，
 *       新增的 5 个字段必须是 <b>-1 哨兵</b>，不能是 0；</li>
 *   <li>12 列新行必须读得出全部字段；</li>
 *   <li>尾部多一个垃圾列/垃圾值不能让整行消失，更不能让<b>整份文件</b>报废。</li>
 * </ul>
 */
class CsvLayoutTest {

    private static HistorySample sample() {
        return new HistorySample(1_700_000_000_000L, 19.5, 8.25, 42, 3, 120, 4567);
    }

    /** v1.4：满字段样本（12 列都用真实值，便于断言"列没串位"）。 */
    private static HistorySample fullSample() {
        return new HistorySample(1_700_000_060_000L, 18.75, 12.5, 55, 7, 340, 1200,
            87.5, 63.25, 41.5, 17L, 2_345L);
    }

    private static String header() throws Exception {
        java.lang.reflect.Field f = HistoryFileStore.class.getDeclaredField("HEADER");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private static String format(HistorySample s) throws Exception {
        Method fmt = HistoryFileStore.class.getDeclaredMethod("format", HistorySample.class);
        fmt.setAccessible(true);
        return (String) fmt.invoke(null, s);
    }

    private static HistoryFileStore storeIn(Path dir) {
        return new HistoryFileStore(null, dir, java.util.logging.Logger.getLogger("csv-test"));
    }

    @SuppressWarnings("unchecked")
    private static List<HistorySample> readFile(HistoryFileStore store, Path file) throws Exception {
        Method read = HistoryFileStore.class.getDeclaredMethod("readFile", Path.class);
        read.setAccessible(true);
        return (List<HistorySample>) read.invoke(store, file);
    }

    // ==================== 既有断言（列序唯一真相源） ====================

    @Test
    void headerAndRowHaveSameColumnCount() throws Exception {
        String row = format(sample());

        // "# v3 a,b,c" -> 去掉 "# v3 " 前缀后按逗号切
        String cols = header().replaceFirst("^#\\s*v\\d+\\s*", "");
        assertEquals(cols.split(",", -1).length, row.split(",", -1).length,
            "header/row column count drift: header=" + cols + " row=" + row);
        assertEquals(12, row.split(",", -1).length, "v1.4 schema is 12 columns");
    }

    @Test
    void rowValuesFollowDeclarationOrder() throws Exception {
        // 7 参构造 = "该版本之前没有这几列" → 尾部必须全是 -1 哨兵，绝不能写 0
        assertEquals("1700000000000,19.5,8.25,42,3,120,4567,-1.0,-1.0,-1.0,-1,-1", format(sample()));
    }

    @Test
    void fullRowWritesEveryColumnInOrder() throws Exception {
        assertEquals("1700000060000,18.75,12.5,55,7,340,1200,87.5,63.25,41.5,17,2345",
            format(fullSample()));
    }

    @Test
    void writtenFileRoundTripsThroughReader() throws Exception {
        Path dir = Files.createTempDirectory("pscope-csv");
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2023-11-14.csv");
        Files.write(file, ((header() + "\n" + format(sample()) + "\n").getBytes(StandardCharsets.UTF_8)));

        List<HistorySample> out = readFile(store, file);

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
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2023-11-15.csv");
        Files.write(file, ((header() + "\nnot,a,valid,row\n").getBytes(StandardCharsets.UTF_8)));

        readFile(store, file);

        assertTrue(store.skippedLines() >= 1, "malformed row must be counted, not silently dropped");
    }

    // ==================== v1.4：旧文件兼容（核心靶点） ====================

    /** v1.3 的 v2 表头（7 个列名）+ 7 列数据行。 */
    private static final String V13_HEADER =
        "# v2 epochMs,tps1m,msptAvg,heapUsagePercent,playerCount,totalEntities,totalLoadedChunks";

    @Test
    void legacySevenColumnFileStillParses() throws Exception {
        Path dir = Files.createTempDirectory("pscope-csv-v13");
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2026-01-01.csv");
        Files.write(file, ((V13_HEADER + "\n"
            + "1700000000000,19.5,8.25,42,3,120,4567\n"
            + "1700000060000,18.0,11.0,50,4,130,4600\n").getBytes(StandardCharsets.UTF_8)));

        List<HistorySample> out = readFile(store, file);

        assertEquals(2, out.size(), "v1.3 rows must still parse after the schema grew");
        HistorySample s = out.get(0);
        assertEquals(1_700_000_000_000L, s.timestamp());
        assertEquals(19.5, s.tps1m());
        assertEquals(8.25, s.msptAvg());
        assertEquals(42, s.heapUsagePercent());
        assertEquals(3, s.playerCount());
        assertEquals(120, s.totalEntities());
        assertEquals(4567, s.totalLoadedChunks());
        // 关键：缺失列是 -1 哨兵，不是 0 —— 否则趋势会凭空显示"CPU 0%"
        assertEquals(HistorySample.NO_DOUBLE, s.msptMax());
        assertEquals(HistorySample.NO_DOUBLE, s.cpuProcess());
        assertEquals(HistorySample.NO_DOUBLE, s.cpuSystem());
        assertEquals(HistorySample.NO_LONG, s.gcCount());
        assertEquals(HistorySample.NO_LONG, s.gcTime());
        assertEquals(0L, store.skippedLines(), "a legacy row is not a corrupt row");
    }

    @Test
    void headerlessLegacyFileAlsoParses() throws Exception {
        // 9A-18 之前的文件连表头都没有（首行直接是数据）；位置口径必须覆盖全部 12 列，
        // 否则 12 列的"新数据写进无头文件"会有一部分读不回来。
        Path dir = Files.createTempDirectory("pscope-csv-noheader");
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2026-01-02.csv");
        Files.write(file, ("1700000000000,19.5,8.25,42,3,120,4567\n").getBytes(StandardCharsets.UTF_8));

        List<HistorySample> out = readFile(store, file);

        assertEquals(1, out.size(), "header-less legacy file must still parse");
        assertEquals(4567, out.get(0).totalLoadedChunks());
        assertEquals(HistorySample.NO_DOUBLE, out.get(0).cpuProcess());
    }

    @Test
    void newTwelveColumnFileParsesEveryField() throws Exception {
        Path dir = Files.createTempDirectory("pscope-csv-v14");
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2026-01-03.csv");
        Files.write(file, ((header() + "\n" + format(fullSample()) + "\n").getBytes(StandardCharsets.UTF_8)));

        List<HistorySample> out = readFile(store, file);

        assertEquals(1, out.size());
        HistorySample s = out.get(0);
        assertEquals(1_700_000_060_000L, s.timestamp());
        assertEquals(18.75, s.tps1m());
        assertEquals(12.5, s.msptAvg());
        assertEquals(55, s.heapUsagePercent());
        assertEquals(7, s.playerCount());
        assertEquals(340, s.totalEntities());
        assertEquals(1200, s.totalLoadedChunks());
        assertEquals(87.5, s.msptMax());
        assertEquals(63.25, s.cpuProcess());
        assertEquals(41.5, s.cpuSystem());
        assertEquals(17L, s.gcCount());
        assertEquals(2_345L, s.gcTime());
    }

    @Test
    void mixedLegacyAndNewRowsInOneFileBothParse() throws Exception {
        // 升级当天的真实形态：既有文件保留 v2 表头，新样本按 v3 的 12 列追加在其后。
        // 两个读取端都不能因此错位：旧行 7 列、新行 12 列，同一个文件里共存。
        Path dir = Files.createTempDirectory("pscope-csv-mixed");
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2026-01-04.csv");
        Files.write(file, ((V13_HEADER + "\n"
            + "1700000000000,19.5,8.25,42,3,120,4567\n"
            + format(fullSample()) + "\n").getBytes(StandardCharsets.UTF_8)));

        List<HistorySample> out = readFile(store, file);

        assertEquals(2, out.size(), "upgrade-day file (old header, old+new rows) must parse fully");
        assertEquals(HistorySample.NO_DOUBLE, out.get(0).cpuProcess(), "old row has no CPU");
        assertEquals(63.25, out.get(1).cpuProcess(), "new row's CPU must not be lost");
        assertEquals(87.5, out.get(1).msptMax(), "new row's spike must not be lost");
        assertEquals(0L, store.skippedLines());
    }

    // ==================== v1.4：脏数据的容错边界 ====================

    @Test
    void garbageTrailingColumnDoesNotKillTheLine() throws Exception {
        // 尾部列脏（人工编辑/版本混写）只应让那 1 个单元格变 -1，
        // 同一行里完好的 TPS/内存/实体不该陪葬——那才是真正的数据损失。
        // 文件里随后那行干净样本也必须照常读出：坏行不得污染整份文件。
        Path dir = Files.createTempDirectory("pscope-csv-garbage");
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2026-01-05.csv");
        Files.write(file, ((header() + "\n"
            + "1700000000000,19.5,8.25,42,3,120,4567,not-a-number,63.25,41.5,17,2345\n"
            + format(fullSample()) + "\n").getBytes(StandardCharsets.UTF_8)));

        List<HistorySample> out = readFile(store, file);

        assertEquals(2, out.size(), "one bad optional cell must not drop the row or the file");
        HistorySample s = out.get(0);
        assertEquals(19.5, s.tps1m());
        assertEquals(HistorySample.NO_DOUBLE, s.msptMax(), "bad cell degrades to sentinel");
        assertEquals(63.25, s.cpuProcess(), "the following columns must still map correctly");
        assertEquals(2_345L, s.gcTime());
        assertEquals(1_700_000_060_000L, out.get(1).timestamp(), "the next row survives");
        assertEquals(87.5, out.get(1).msptMax());
        assertEquals(0L, store.skippedLines());
    }

    @Test
    void extraUnknownColumnAtTailIsIgnored() throws Exception {
        // 未来版本再追加第 13 列时，本版本按前 12 列读、忽略尾巴。
        Path dir = Files.createTempDirectory("pscope-csv-extra");
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2026-01-06.csv");
        Files.write(file, ((header() + "\n"
            + "1700000000000,19.5,8.25,42,3,120,4567,87.5,63.25,41.5,17,2345,FUTURE-COLUMN\n")
            .getBytes(StandardCharsets.UTF_8)));

        List<HistorySample> out = readFile(store, file);

        assertEquals(1, out.size(), "an unknown trailing column must not break parsing");
        assertEquals(63.25, out.get(0).cpuProcess());
        assertEquals(0L, store.skippedLines());
    }

    @Test
    void corruptAnchorCellStillSkipsOnlyThatRow() throws Exception {
        // 锚列（前 7 列）损坏 = 该行没有骨架，跳过并计数；但文件里其余行必须照常读出。
        Path dir = Files.createTempDirectory("pscope-csv-anchor");
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2026-01-07.csv");
        Files.write(file, ((header() + "\n"
            + "broken,19.5,8.25,42,3,120,4567,87.5,63.25,41.5,17,2345\n"
            + format(fullSample()) + "\n").getBytes(StandardCharsets.UTF_8)));

        List<HistorySample> out = readFile(store, file);

        assertEquals(1, out.size(), "the good row after a corrupt one must survive");
        assertEquals(1_700_000_060_000L, out.get(0).timestamp());
        assertEquals(1L, store.skippedLines(), "the corrupt row is counted, not silently dropped");
    }

    @Test
    void shortRowMissingNewColumnsIsToleratedButShortAnchorRowIsNot() throws Exception {
        Path dir = Files.createTempDirectory("pscope-csv-short");
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2026-01-08.csv");
        Files.write(file, ((header() + "\n"
            // 5 列：锚列不齐 → 跳过
            + "1700000000000,19.5,8.25,42,3\n"
            // 9 列：锚列齐、尾部部分存在 → 应读出，缺失项为哨兵
            + "1700000060000,18.0,11.0,50,4,130,4600,70.0\n").getBytes(StandardCharsets.UTF_8)));

        List<HistorySample> out = readFile(store, file);

        assertEquals(1, out.size(), "only the anchor-complete row survives");
        HistorySample s = out.get(0);
        assertEquals(70.0, s.msptMax());
        assertEquals(HistorySample.NO_DOUBLE, s.cpuProcess());
        assertEquals(HistorySample.NO_LONG, s.gcTime());
    }

    @Test
    void sentinelRoundTripsInsteadOfBecomingZero() throws Exception {
        // -1 必须是"能写出去、也能读回来"的一等值。若 format 或 parseLine 任一侧把 -1 变成 0，
        // 趋势就会显示"CPU 一直是 0%"——一个从未发生过的、看起来健康的假象。
        Path dir = Files.createTempDirectory("pscope-csv-sentinel");
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2026-01-09.csv");
        Files.write(file, ((header() + "\n" + format(sample()) + "\n").getBytes(StandardCharsets.UTF_8)));

        List<HistorySample> out = readFile(store, file);

        assertEquals(1, out.size());
        HistorySample s = out.get(0);
        assertNotNull(s);
        assertEquals(HistorySample.NO_DOUBLE, s.msptMax());
        assertEquals(HistorySample.NO_DOUBLE, s.cpuProcess());
        assertEquals(HistorySample.NO_DOUBLE, s.cpuSystem());
        assertEquals(HistorySample.NO_LONG, s.gcCount());
        assertEquals(HistorySample.NO_LONG, s.gcTime());
    }

    @Test
    void zeroCpuIsPreservedAsARealReading() throws Exception {
        // 与上一条互补：0.0 是合法读数（空闲的服），必须与 -1 区分开。
        Path dir = Files.createTempDirectory("pscope-csv-zerocpu");
        HistoryFileStore store = storeIn(dir);
        Path file = dir.resolve("history-2026-01-10.csv");
        HistorySample idle = new HistorySample(1_700_000_000_000L, 20.0, 5.0, 30, 0, 0, 0,
            HistorySample.NO_DOUBLE, 0.0, 0.0, 0L, 0L);
        Files.write(file, ((header() + "\n" + format(idle) + "\n").getBytes(StandardCharsets.UTF_8)));

        List<HistorySample> out = readFile(store, file);

        assertEquals(1, out.size());
        assertEquals(0.0, out.get(0).cpuProcess());
        assertEquals(0.0, out.get(0).cpuSystem());
        assertEquals(0L, out.get(0).gcCount());
        assertEquals(HistorySample.NO_DOUBLE, out.get(0).msptMax());
    }
}
