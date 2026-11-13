/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.history;

import cn.perfscope.config.ConfigManager;
import cn.perfscope.support.HistoryFixtures;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link HistoryFileStore} 的<b>写入侧</b>行为。
 *
 * <h2>为什么需要这个类</h2>
 *
 * <p>原有的 {@code CsvLayoutTest} 覆盖的是<b>读解析</b>（CSV 行怎么拆、7/12 列兼容、坏行跳过），
 * 写入侧几乎没有断言。实测方式：把 {@code append()} 里的丢弃计数改烂
 * （{@code if (false)} 替掉 {@code if (!queue.offer(sample))}），全量 344 个用例<b>一个都没红</b>。
 * 队列满丢弃、写线程健康度、shutdown 不丢批次、落盘时机——这些坏了都不会有任何信号。</p>
 *
 * <h2>怎么等（不靠 {@code Thread.sleep(1000)}）</h2>
 *
 * <p>写线程是异步的，但有三个<b>可观测的同步点</b>，本类一律轮询它们而不是睡固定时长：</p>
 * <ol>
 *   <li><b>文件内容</b>——{@link #awaitFileContains}：样本真的落盘了才通过。</li>
 *   <li><b>{@code lastSuccessAtMs()} / {@code consecutiveFailures()} / {@code lastError()}</b>——
 *       成功写盘与失败都有 volatile 计数可轮询。</li>
 *   <li><b>{@code writerRunning()}</b>——写线程退出（shutdown 或被 Error 击穿）后可观测。</li>
 * </ol>
 *
 * <p>{@code pendingWrites() == 0} <b>不是</b>落盘信号：样本先出队再写盘，出队即归零，
 * 所以本类从不拿它当"写完了"的证据（只在守卫用例里拿它当线索，真正的强判据是
 * "观察窗内没有任何 {@code history-*.csv} 出现"）。</p>
 *
 * <h2>队列满怎么做到确定性</h2>
 *
 * <p>正常情况下 {@code QUEUE_CAPACITY}=2048 且写线程毫秒级消费，靠"灌满"是不可能的。
 * 本类用 {@code shutdown()} 结束写线程、再把 {@code running} 复位为 true、把
 * {@code writerThread} 指向一个从未启动的 {@link Thread}——此时 {@code writerRunning()}
 * 为 false（无消费者），而 {@code append()} 的全部门控、{@code offer} 与丢弃计数
 * <b>都是真实执行的生产代码</b>，没有替身。</p>
 *
 * <h2>观测不到的东西（重要发现，见类尾注释）</h2>
 *
 * <p>写线程内部状态（{@code writerRestarts}、{@code currentFileDate}、{@code writer} 句柄、
 * 手里当前这一批）没有任何对外出口。需要精确构造"写线程被 Error 击穿"的场景，只能
 * 注入一个 {@code poll()} 直接抛 {@link Error} 的队列，见
 * {@link #writerCrashBoundedRestartGivesUpAndReportsUnhealthy}。
 * 生产代码没有为了本类加过任何 getter。</p>
 */
class HistoryFileStoreWriteTest {

    /** 轮询间隔：远小于 {@code FLUSH_INTERVAL_MS}=5000，也远小于写线程的单次写盘耗时。 */
    private static final long POLL_MS = 5L;

    /** 常规等待上限：写一条样本在本机是毫秒级，2 秒已很宽裕。 */
    private static final long TIMEOUT_MS = 2_000L;

    /** "本不该落盘"的观察窗：足够让活着的写线程从队列取走样本并落盘若干轮。 */
    private static final long QUIET_WINDOW_MS = 300L;

    @TempDir
    Path temp;

    /** 每个用例创建的 store 都在这里登记，用例结束统一 shutdown（顺带摘掉 JVM 钩子）。 */
    private final List<HistoryFileStore> opened = new ArrayList<>();

    @AfterEach
    void shutdownEverything() {
        for (HistoryFileStore store : opened) {
            try {
                store.shutdown();
            } catch (RuntimeException ignored) {
                // 用例本身已失败时，不要让清理异常盖住原始失败
            }
        }
        opened.clear();
    }

    // ==================================================================================
    // 1. append() 的五个守卫：每一种都必须"直接返回、不入队"
    // ==================================================================================
    //
    // pendingWrites() 归零不能单独当证据（活着的写线程会立刻消费），
    // 所以每个用例的强断言是"观察窗内没有任何 history-*.csv 出现"——
    // 只要样本真入了队，活着的写线程必然在毫秒级把它写出来。

    @Test
    void nullSampleIsIgnored() {
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);

        assertDoesNotThrow(() -> store.append(null),
            "append(null) must be a no-op, not an NPE out of ArrayBlockingQueue.offer");
        assertEquals(0, store.pendingWrites(), "a null sample must not be queued");
        assertNoHistoryFileWritten(dir);
    }

    @Test
    void nullConfigManagerIsIgnored() {
        // 构造器允许 configManager == null（见其 javadoc），此时 append 必须直接返回；
        // 去掉这个守卫就是 configManager.get() 的 NPE，而 append 由采集主线程调用。
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(null, dir);

        assertDoesNotThrow(() -> store.append(sampleAt(System.currentTimeMillis())));
        assertEquals(0, store.pendingWrites());
        assertNoHistoryFileWritten(dir);
    }

    @Test
    void nullConfigDataIsIgnored() {
        // 插件构造期到 load() 成功之间 get() 返回 null（ConfigManager 的可空契约）。
        ConfigManager cm = new ConfigManager(null); // 不写 config 字段 → get() == null
        assertNull(cm.get(), "fixture precondition: an unloaded ConfigManager returns null");

        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(cm, dir);

        assertDoesNotThrow(() -> store.append(sampleAt(System.currentTimeMillis())),
            "an unloaded config must not NPE the sampling thread");
        assertEquals(0, store.pendingWrites());
        assertNoHistoryFileWritten(dir);
    }

    @Test
    void persistDisabledIsIgnored() {
        ConfigManager cm = HistoryFixtures.config(c -> c.history_persistEnabled = false);
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(cm, dir);

        store.append(sampleAt(System.currentTimeMillis()));

        assertEquals(0, store.pendingWrites(), "persist=false must short-circuit before offer()");
        assertNoHistoryFileWritten(dir);
    }

    @Test
    void appendAfterShutdownIsIgnored() {
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);
        store.shutdown(); // 写线程已死、writerThread==null：队列不再有消费者，入队会永久滞留

        store.append(sampleAt(System.currentTimeMillis()));

        // 没有消费者时这个断言是确定性的：入了队就会一直看得见。
        assertEquals(0, store.pendingWrites(),
            "append() must honour the running flag; otherwise samples pile up in a dead store");
        assertNoHistoryFileWritten(dir);
    }

    // ==================================================================================
    // 2. 落盘：最基本、也最可能完全没测的一条
    // ==================================================================================

    @Test
    void appendWritesTheRowToDiskAndItReadsBackIdentical() {
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);
        HistorySample sample = sampleAt(System.currentTimeMillis());

        store.append(sample);

        String row = expectedRow(sample);
        Path file = awaitFileContains(dir, row);
        assertTrue(file.getFileName().toString().startsWith("history-"),
            "the sample must land in a history-*.csv file, got " + file.getFileName());
        assertTrue(file.getFileName().toString().endsWith(".csv"));

        List<String> lines = readAllLines(file);
        assertEquals(1, lines.stream().filter(l -> l.startsWith("#")).count(),
            "a fresh file must carry exactly one schema header: " + lines);

        // 读回路径必须原样还原全部 12 个字段（写入端与读取端共用同一列序契约）
        assertEquals(List.of(sample), store.loadRecentDays(1, 0L),
            "the persisted row must round-trip through the reader unchanged");
    }

    @Test
    void appendFlushesImmediatelyWithoutWaitingForTheBatchInterval() {
        // 代码注释承诺"队列已空就立刻写，否则常态（60 秒一条）下每条样本都要白等 5 秒"。
        // 这条用例把"立刻"钉住：单条样本必须远早于 FLUSH_INTERVAL_MS(5s) 就在文件里可见。
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);

        long start = System.nanoTime();
        store.append(sampleAt(System.currentTimeMillis()));
        awaitFileContains(dir, null); // 只等"任意数据行出现"
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 1_000L,
            "a single sample must be written immediately, not after the 5s flush tick (took "
                + elapsedMs + "ms)");
    }

    // ==================================================================================
    // 3. 队列满 → 丢弃并计数（不阻塞主线程、队列不无限增长）
    // ==================================================================================

    @Test
    void queueFullDropsSamplesInsteadOfBlockingOrGrowing() {
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);

        int capacity = goConsumerless(store);

        int overflow = 7;
        long start = System.nanoTime();
        for (int i = 0; i < capacity + overflow; i++) {
            store.append(sampleAt(1_700_000_000_000L + i));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(capacity, store.pendingWrites(),
            "the queue must stay bounded at QUEUE_CAPACITY, not grow with the input");
        assertEquals(overflow, store.droppedSamples(),
            "every sample that could not be queued must be counted, not silently lost");
        assertTrue(elapsedMs < 2_000L,
            "append() must stay non-blocking with a full queue (took " + elapsedMs + "ms)");
    }

    // ==================================================================================
    // 4/5. shutdown()：不丢已收集的批次、可重复调用
    // ==================================================================================

    @Test
    void shutdownPersistsEveryQueuedSample() throws IOException {
        // 对应代码注释的明确警告："用 interrupt 叫醒 poll，不能 break 直接退出——
        // 那会把手里已收集未写盘的批次丢掉"。关服窗口内最容易发生的正是这种丢失。
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);
        ZoneId zone = store.zone();
        LocalDate today = LocalDate.now(zone);

        int count = 200;
        Set<Long> expected = new HashSet<>();
        long base = today.atStartOfDay(zone).toInstant().toEpochMilli() + 1_000L;
        for (int i = 0; i < count; i++) {
            long ts = base + i * 1_000L;
            expected.add(ts);
            store.append(sampleAt(ts));
        }

        // 立刻关停：写线程此刻极可能正握着一批样本（被 interrupt 叫醒或在写盘途中）
        store.shutdown();

        Set<Long> written = readTimestamps(dir);
        assertEquals(expected, written,
            "shutdown() must persist the in-hand batch and the queue; "
                + (count - written.size()) + " sample(s) missing");
        assertFalse(store.writerRunning(), "the writer must be gone after shutdown()");
    }

    @Test
    void shutdownReleasesTheFileHandleEvenWhenTheWriterIsIdle() throws IOException {
        // 这条针对代码注释里那句警告的**另一半**：中断只用来结束等待，写线程必须走到
        // drainAndClose()（它既 drain 队列、也 close 句柄）。若改成直接 return/退出，
        // 队列可能恰好是空的（什么也丢不了）——此时唯一还能观测到的破坏是
        // **长效 BufferedWriter 没被关掉**：句柄泄漏，Windows 上文件被锁住，
        // 下一次实例化会打不开同一个文件。
        //
        // 构造是确定性的：先等那一条样本真的落盘并 flush（写线程此后必然阻塞在
        // queue.poll(5000) 上、手里没有批次），再 shutdown。此时中断一定落在
        // writeLoop 的外层 catch 里，正是 break 与 return 分岔的地方。
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);
        store.append(sampleAt(System.currentTimeMillis()));
        awaitFileContains(dir, null);
        await("the batch to be flushed", TIMEOUT_MS, () -> store.lastSuccessAtMs() > 0L);

        store.shutdown();

        assertNull(TestObjects.getField(HistoryFileStore.class, store, "writer"),
            "shutdown() must close the long-lived writer; a non-null handle means it leaked");
        assertNull(TestObjects.getField(HistoryFileStore.class, store, "currentFileDate"),
            "the current file date is cleared together with the handle");
        assertFalse(store.writerRunning());
        assertEquals(1, dataRows(dir).size(), "the sample must still be on disk exactly once");
    }

    @Test
    void shutdownIsIdempotent() throws IOException {
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);
        store.append(sampleAt(System.currentTimeMillis()));
        awaitFileContains(dir, null);
        long rowsBefore = dataRows(dir).size();

        assertDoesNotThrow(store::shutdown);
        assertDoesNotThrow(store::shutdown, "a second shutdown() must be a no-op");
        assertDoesNotThrow(store::shutdown);

        assertEquals(rowsBefore, dataRows(dir).size(),
            "repeated shutdown() must not duplicate or re-write rows");
        assertNull(TestObjects.getField(HistoryFileStore.class, store, "writerThread"),
            "writerThread is cleared so a later shutdown() short-circuits");
        assertFalse(store.writerRunning());
    }

    // ==================================================================================
    // 6. 健康度访问器
    // ==================================================================================

    @Test
    void freshStoreReportsRunningAndHealthyWithoutAnySuccessYet() {
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);

        assertTrue(store.writerRunning(), "the writer thread starts in the constructor");
        assertTrue(store.isHealthy(), "a fresh store on a writable dir is healthy");
        assertEquals(0, store.consecutiveFailures());
        assertNull(store.lastError(), "no failure yet → no error text");
        assertEquals(0L, store.lastSuccessAtMs(),
            "0 = nothing written yet in this process (an idle writer must not touch it)");
        assertEquals(0L, store.droppedSamples());
        assertEquals(0L, store.skippedLines());
        assertTrue(store.available());
        assertEquals(dir, store.directory());
    }

    @Test
    void aSuccessfulWriteAdvancesTheTimestampAndClearsFailures() {
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);

        store.append(sampleAt(System.currentTimeMillis()));
        await("lastSuccessAtMs to advance", TIMEOUT_MS, () -> store.lastSuccessAtMs() > 0L);

        assertTrue(store.isHealthy());
        assertEquals(0, store.consecutiveFailures());
        assertNull(store.lastError());
    }

    // ==================================================================================
    // 7/8. 磁盘不可用：不崩、有 lastError、失败计数递增、修好后自愈
    // ==================================================================================

    @Test
    void availableIsFalseWhenTheDirectoryIsGoneAndTheWriterRecreatesIt() throws IOException {
        // 构造器会 createDirectories；把目录删掉才轮到"目录不存在"这一态。
        // 之后的第一条样本会经 openWriter 的 createDirectories 重建目录——
        // 这条行为值得钉住：可用性探测返回 false，不等于持久化就此永久废掉。
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);
        assertTrue(store.available());

        Files.delete(dir); // @TempDir 下的空目录
        assertFalse(store.available(), "a missing directory must report unavailable");
        assertFalse(store.isHealthy(), "unavailable ⇒ not healthy");
        // 注意：isHealthy() 不因"目录暂时不在"就停写线程，只是如实报 false

        store.append(sampleAt(System.currentTimeMillis()));

        awaitFileContains(dir, null);
        assertTrue(store.available(), "openWriter recreates the directory on the next batch");
        assertTrue(store.isHealthy(), "once the directory is back, health recovers");
        assertEquals(0, store.consecutiveFailures(),
            "the missing directory is not a write failure — it is recreated transparently");
    }

    @Test
    void availableIsFalseWhenThePathIsNotADirectory() throws IOException {
        // 把"历史目录"指向一个普通文件：跨平台地制造"目录不可用"，不必改 ACL。
        Path notADir = temp.resolve("history");
        Files.writeString(notADir, "i am a file, not a directory");

        HistoryFileStore store = open(persistConfig(), notADir);

        assertFalse(store.available(), "a regular file is not a writable history directory");
        assertFalse(store.isHealthy(), "an unusable directory must not be reported healthy");
        assertNotNull(store.lastError(),
            "the constructor's createDirectories failure must be recorded, not swallowed");
        assertTrue(store.consecutiveFailures() >= 1,
            "the failed directory creation must count as a failure");
    }

    @Test
    void writeFailureIsRecordedAndRecoversAfterTheDirectoryBecomesUsable() throws IOException {
        Path blocked = temp.resolve("history");
        Files.writeString(blocked, "not a directory");
        HistoryFileStore store = open(persistConfig(), blocked);
        int afterCtor = store.consecutiveFailures();
        assertTrue(afterCtor >= 1, "fixture precondition: the constructor already failed once");

        store.append(sampleAt(System.currentTimeMillis()));
        await("the first write failure to be recorded", TIMEOUT_MS,
            () -> store.consecutiveFailures() > afterCtor);
        assertNotNull(store.lastError(), "a failed write must leave a readable reason");
        assertFalse(store.lastError().isEmpty());
        assertFalse(store.isHealthy(), "a failing store must not report healthy");
        assertTrue(store.writerRunning(),
            "a write failure is not fatal: the writer stays alive to retry the next batch");

        // 修好目录（同一路径：文件换成目录）→ 下一批必须成功，并把失败状态清零
        Files.delete(blocked);
        store.append(sampleAt(System.currentTimeMillis() + 1_000L));

        await("a successful write after recovery", TIMEOUT_MS, () -> store.lastSuccessAtMs() > 0L);
        assertEquals(0, store.consecutiveFailures(), "any successful write resets the failure streak");
        assertNull(store.lastError(), "a successful write clears the last error");
        assertTrue(store.isHealthy(), "dir fixed + no failures → healthy again");
        assertEquals(1, dataRows(store.directory()).size(),
            "only the recovered batch reaches disk; the pre-repair sample is lost by design");
    }

    // ==================================================================================
    // 9. droppedSamples vs skippedLines：两个计数器的语义必须分得开
    // ==================================================================================

    @Test
    void droppedAndSkippedCountersMeasureDifferentFailureModes() throws IOException {
        // 写入丢弃（dropped）与读取坏行（skipped）是两条完全不同的路径。
        // 若实现里把两个计数器接反/共用，排障时会得到完全错误的结论。
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);

        int capacity = goConsumerless(store);
        for (int i = 0; i < capacity + 3; i++) {
            store.append(sampleAt(1_700_000_000_000L + i));
        }
        assertEquals(3L, store.droppedSamples());
        assertEquals(0L, store.skippedLines(), "a write drop is not a read skip");

        // 反过来：读取坏行只动 skippedLines，不动 droppedSamples
        long droppedBefore = store.droppedSamples();
        Path file = dir.resolve("history-" + LocalDate.now(store.zone()) + ".csv");
        Files.write(file, (header() + "\nnot,a,row\n").getBytes(StandardCharsets.UTF_8));
        store.invalidateCache();
        List<HistorySample> loaded = store.loadRecentDays(1, 0L);

        assertTrue(loaded.isEmpty(), "the corrupt row must not surface as a sample");
        assertEquals(1L, store.skippedLines(), "the corrupt row is counted as skipped");
        assertEquals(droppedBefore, store.droppedSamples(), "reading must not touch the drop counter");
    }

    // ==================================================================================
    // 10/11. 保留清理与缓存失效
    // ==================================================================================

    @Test
    void runRetentionKeepsTheCalendarWindowAndLeavesOtherFilesAlone() throws IOException {
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);
        ZoneId zone = store.zone();
        LocalDate today = LocalDate.now(zone);

        // 保留口径 = 最近 3 个日历日（含今天）→ cutoff = today-2
        Path todayFile = createHistoryFile(dir, today);
        Path yesterday = createHistoryFile(dir, today.minusDays(1));
        Path twoDays = createHistoryFile(dir, today.minusDays(2));
        Path threeDays = createHistoryFile(dir, today.minusDays(3));
        Path longAgo = createHistoryFile(dir, today.minusDays(30));
        Path unrelated = dir.resolve("notes.txt");
        Files.writeString(unrelated, "keep me");
        Path unparsable = dir.resolve("history-not-a-date.csv");
        Files.writeString(unparsable, "keep me too");

        store.runRetention(3);

        assertTrue(Files.exists(todayFile), "today is inside the window");
        assertTrue(Files.exists(yesterday));
        assertTrue(Files.exists(twoDays), "the cutoff day (today-2) is kept");
        assertFalse(Files.exists(threeDays), "today-3 is outside a 3-day window");
        assertFalse(Files.exists(longAgo));
        assertTrue(Files.exists(unrelated), "non-history files must never be touched");
        assertTrue(Files.exists(unparsable),
            "a history-prefixed file with an unparsable date must be left alone, not deleted");
    }

    @Test
    void retentionClampsADayCountBelowOneToTodayOnly() throws IOException {
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);
        LocalDate today = LocalDate.now(store.zone());
        Path todayFile = createHistoryFile(dir, today);
        Path yesterday = createHistoryFile(dir, today.minusDays(1));

        store.runRetention(0); // <1 按 1 处理

        assertTrue(Files.exists(todayFile));
        assertFalse(Files.exists(yesterday), "days<1 clamps to 1 → only today survives");
    }

    @Test
    void invalidateCacheForcesARereadWhenStatLooksUnchanged() throws IOException {
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);
        LocalDate today = LocalDate.now(store.zone());
        Path file = dir.resolve("history-" + today + ".csv");
        FileTime frozen = FileTime.fromMillis(1_700_000_000_000L);

        String rowA = row(today, 20.0);
        String rowB = row(today, 19.0);
        assertEquals(rowA.length(), rowB.length(), "fixture precondition: same byte length");

        Files.writeString(file, header() + "\n" + rowA + "\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, frozen);
        long size = Files.size(file);

        assertEquals(20.0, store.loadRecentDays(1, 0L).get(0).tps1m(), 1e-9);

        // 内容改了，但 mtime 与 size 都还原成"看起来没变"——缓存命中条件正是这两者
        Files.writeString(file, header() + "\n" + rowB + "\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, frozen);
        assertEquals(size, Files.size(file), "fixture precondition: same size");

        assertEquals(20.0, store.loadRecentDays(1, 0L).get(0).tps1m(), 1e-9,
            "an unchanged mtime+size must hit the cache (documented contract)");

        store.invalidateCache();

        assertEquals(19.0, store.loadRecentDays(1, 0L).get(0).tps1m(), 1e-9,
            "after invalidateCache() the file must be re-read from disk");
    }

    // ==================================================================================
    // 12. 写线程被 Error 击穿后的有界重建
    // ==================================================================================

    /**
     * {@code writerLoop()} 对 {@code Error} 的守卫是"有界重建"：最多
     * {@code MAX_WRITER_RESTARTS} 次，超过即退出线程，让 {@code writerRunning()/isHealthy()}
     * 如实转 false（否则 status 会结构性谎报"可用"）。
     *
     * <p>构造方式：把 {@code queue} 换成 {@code poll()} 直接抛 {@link Error} 的队列。
     * 写线程每次循环都重新读该字段，换入后必然撞上；{@code Error} 不被
     * {@code catch (Exception | LinkageError)} 捕获，正好走到重建分支。</p>
     */
    @Test
    void writerCrashBoundedRestartGivesUpAndReportsUnhealthy() {
        Path dir = temp.resolve("hist");
        HistoryFileStore store = open(persistConfig(), dir);
        Object writerThread = TestObjects.getField(HistoryFileStore.class, store, "writerThread");
        assertTrue(writerThread instanceof Thread,
            "fixture precondition: the writer handle is observable via the writerThread field");

        TestObjects.setField(HistoryFileStore.class, store, "queue", new ExplodingQueue());
        ((Thread) writerThread).interrupt(); // 叫醒可能正阻塞在旧队列 poll 上的写线程

        int maxRestarts = TestObjects.getStaticInt(HistoryFileStore.class, "MAX_WRITER_RESTARTS");
        await("the writer to give up after " + maxRestarts + " restarts", 5_000L,
            () -> !store.writerRunning());

        assertFalse(store.isHealthy(), "a dead writer must never be reported healthy");
        assertTrue(store.consecutiveFailures() >= 3,
            "each crash pushes the failure count to at least the severe threshold");
        assertNotNull(store.lastError());
        assertTrue(store.lastError().contains("injected writer crash"),
            "lastError must carry the crash cause, got: " + store.lastError());

        // 线程已死但 running 仍为 true：append 会"成功入队"却无人消费。
        // 这是已知的有损窗口，写出来免得后人把它当新 bug 追。
        assertDoesNotThrow(() -> store.append(sampleAt(System.currentTimeMillis())));
    }

    // ==================================================================================
    // 辅助
    // ==================================================================================

    /** 注册并返回一个 store；用例结束后由 {@link #shutdownEverything} 关停。 */
    private HistoryFileStore open(ConfigManager cm, Path dir) {
        HistoryFileStore store = new HistoryFileStore(cm, dir, quietLogger());
        opened.add(store);
        return store;
    }

    private static ConfigManager persistConfig() {
        return HistoryFixtures.config(c -> c.history_persistEnabled = true);
    }

    /** 静音 logger：本类大量触发失败日志，让它只留在内存里，不污染 surefire 输出。 */
    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("pscope-history-write-test-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }

    /**
     * 把 store 变成"有队列、无消费者"的确定性状态，返回队列容量。
     *
     * <p>{@code shutdown()} 让写线程退出并把 {@code writerThread} 置空；随后把
     * {@code running} 复位为 true（否则 {@code append} 走的是另一个守卫）、把
     * {@code writerThread} 指向一个从未启动的 {@link Thread}（其 {@code isAlive()==false}）。
     * 这样 {@code append()} 的入队与丢弃分支都会被完整执行到。</p>
     */
    private static int goConsumerless(HistoryFileStore store) {
        int capacity = TestObjects.getStaticInt(HistoryFileStore.class, "QUEUE_CAPACITY");
        store.shutdown();
        TestObjects.setField(HistoryFileStore.class, store, "running", Boolean.TRUE);
        TestObjects.setField(HistoryFileStore.class, store, "writerThread", new Thread());
        assertFalse(store.writerRunning(), "fixture precondition: no consumer thread is alive");
        return capacity;
    }

    /** 12 列全量样本（哨兵/0 的区分由 CsvLayoutTest 负责，这里只关心落盘）。 */
    private static HistorySample sampleAt(long ts) {
        return new HistorySample(ts, 19.75, 8.5, 42, 3, 120, 4567, 31.25, 12.5, 40.0, 7L, 123L);
    }

    /**
     * 与 {@code HistoryFileStore.format()} 同构的期望行——列序被独立写一遍，
     * 于是"表头/数据行错位"这类改动会在这里而不是在读取端被发现。
     */
    private static String expectedRow(HistorySample s) {
        return s.timestamp() + "," + s.tps1m() + "," + s.msptAvg() + "," + s.heapUsagePercent()
            + "," + s.playerCount() + "," + s.totalEntities() + "," + s.totalLoadedChunks()
            + "," + s.msptMax() + "," + s.cpuProcess() + "," + s.cpuSystem()
            + "," + s.gcCount() + "," + s.gcTime();
    }

    private static String header() {
        return "# v3 epochMs,tps1m,msptAvg,heapUsagePercent,playerCount,totalEntities,"
            + "totalLoadedChunks,msptMax,cpuProcess,cpuSystem,gcCount,gcTime";
    }

    private static String row(LocalDate date, double tps) {
        long ts = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1_000L;
        return ts + "," + tps + ",8.5,42,3,120,4567,31.25,12.5,40.0,7,123";
    }

    private static Path createHistoryFile(Path dir, LocalDate date) throws IOException {
        Path file = dir.resolve("history-" + date + ".csv");
        Files.writeString(file, header() + "\n1700000000000,19.5,8.25,42,3,120,4567\n",
            StandardCharsets.UTF_8);
        return file;
    }

    private static List<String> dataRows(Path dir) throws IOException {
        List<String> rows = new ArrayList<>();
        for (Path file : historyFiles(dir)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    rows.add(line);
                }
            }
        }
        return rows;
    }

    private static Set<Long> readTimestamps(Path dir) throws IOException {
        Set<Long> out = new HashSet<>();
        for (String row : dataRows(dir)) {
            out.add(Long.parseLong(row.substring(0, row.indexOf(',')).trim()));
        }
        return out;
    }

    private static List<Path> historyFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("history-") && name.endsWith(".csv");
            }).toList();
        }
    }

    /** 读一个文件；IO 失败直接让用例红，而不是把异常当"文件是空的"。 */
    private static List<String> readAllLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("could not read " + file.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * 等样本落盘并返回包含该行的文件。
     *
     * @param needle 期望整行相等的文本；{@code null} = 只等"任意非注释数据行出现"
     */
    private Path awaitFileContains(Path dir, String needle) {
        await("a data row to reach disk" + (needle == null ? "" : " (" + needle + ")"),
            TIMEOUT_MS, () -> anyRowMatches(dir, needle));
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                for (Path file : historyFiles(dir)) {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        boolean dataRow = !line.isEmpty() && !line.startsWith("#");
                        if (needle == null ? dataRow : line.equals(needle)) {
                            return file;
                        }
                    }
                }
            } catch (IOException ignored) {
                // 写线程可能正在 rename/扩展：重试
            }
            if (!pause()) {
                break;
            }
        }
        return fail("the awaited row vanished between the poll and the re-read: " + needle);
    }

    private static boolean anyRowMatches(Path dir, String needle) {
        try {
            for (String row : dataRows(dir)) {
                if (needle == null || row.equals(needle)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // 文件正在被写，下一轮再看
        }
        return false;
    }

    /** 观察窗内必须始终看不到任何历史文件。 */
    private void assertNoHistoryFileWritten(Path dir) {
        long deadline = System.currentTimeMillis() + QUIET_WINDOW_MS;
        do {
            try {
                List<Path> files = historyFiles(dir);
                assertTrue(files.isEmpty(),
                    "a sample that must be ignored was written to disk: " + files);
            } catch (IOException e) {
                fail("could not inspect the history dir: " + e.getMessage());
            }
            if (!pause()) {
                break;
            }
        } while (System.currentTimeMillis() < deadline);
    }

    /** 轮询直到条件成立或超时；失败时给出"等的是什么"。 */
    private static void await(String what, long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            if (!pause()) {
                break;
            }
        }
        if (!condition.getAsBoolean()) {
            fail("timed out after " + timeoutMs + "ms waiting for " + what);
        }
    }

    /** 睡一个轮询间隔；被中断时恢复中断位并返回 false（由调用方转成断言失败）。 */
    private static boolean pause() {
        try {
            Thread.sleep(POLL_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 见 {@link #writerCrashBoundedRestartGivesUpAndReportsUnhealthy}：每次 poll 都抛 Error。 */
    private static final class ExplodingQueue extends LinkedBlockingQueue<HistorySample> {
        @Override
        public HistorySample poll(long timeout, TimeUnit unit) {
            throw new AssertionError("injected writer crash");
        }

        @Override
        public HistorySample poll() {
            throw new AssertionError("injected writer crash");
        }
    }
}
