/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, version 3 of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package cn.perfscope.history;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 历史文件持久化（Wave 8B）——按天分文件的 CSV 追加写，重启不清零。
 *
 * <p><b>为什么不是 SQLite</b>（锐评 B 论点 8）：SQLite-JDBC 是本项目首个重量级运行时依赖
 * （native 库 + classloader 复杂度，与 v1.0.0 shade 事故同类土壤）；而本场景（1 条/分钟的
 * 采样、7 天保留、单机读写）用按天 CSV 文件即可获得全部价值、零依赖风险。逐天文件还带来
 * 天然的分片与过期删除便利。</p>
 *
 * <p><b>文件布局</b>：{@code plugins/PerfScope/history/history-yyyy-MM-dd.csv}，首行为 schema 头，
 * 其后每行：{@code epochMs,tps1m,msptAvg,heapUsagePercent,playerCount,totalEntities,totalLoadedChunks}
 * （RAW 值不消毒）。</p>
 *
 * <p><b>9A-18 重做（工程纪律）</b>：</p>
 * <ul>
 *   <li><b>写盘不再发生在主线程</b>：{@link #append} 只做非阻塞 {@code offer}，
 *       单写者守护线程 {@value #WRITER_THREAD_NAME} 按"每 {@value #BATCH_SIZE} 条或每
 *       {@value #FLUSH_INTERVAL_MS} ms"批量 flush，保持长效 {@link BufferedWriter}，
 *       仅在跨天时 reopen。旧实现每 60 秒在采集主线程做 exists/open/write/close 四次系统调用。</li>
 *   <li><b>失败可见</b>：{@link #isHealthy()} / {@link #consecutiveFailures()} /
 *       {@link #lastError()} / {@link #lastSuccessAtMs()} / {@link #droppedSamples()}；
 *       连续失败 {@value #FAILURE_SEVERE_THRESHOLD} 次升到 SEVERE（含原因与目录路径）。</li>
 *   <li><b>读取有缓存</b>：按文件 mtime + size 命中缓存；{@code sinceMs} 落在文件中段时二分定位。</li>
 *   <li><b>schema 版本 + 表头映射</b>：新文件写 {@code # v2 ...} 头，读取按列名映射，
 *       未来加列不会让旧读取端错位；旧文件（无版本头）按位置兼容读取。</li>
 *   <li><b>半行保护</b>：打开既有文件时检查末字节，不是换行则先补 {@code \n}，
 *       避免上次异常退出留下的半行与新样本拼成一行。</li>
 * </ul>
 *
 * <p><b>清理</b>：{@link #runRetention(int)} 删除超过保留天数的文件（启动/reload 时调用一次；
 * 不再耦合在跨天滚动里）。保留口径 = 最近 N 个<b>日历日</b>（含今天，cutoff = today-(N-1)）。</p>
 *
 * <p>线程安全：{@code append} 可从任意线程调用（仅入队）；写状态只属写线程；读取与写线程的竞争
 * 以"读到部分行"最坏情况处理——解析失败的行计入 {@link #skippedLines()} 并跳过，不抛异常。
 * 零依赖（纯 JDK NIO）。</p>
 */
public class HistoryFileStore {

    private static final String FILE_PREFIX = "history-";
    private static final String FILE_SUFFIX = ".csv";

    /** 9A-18：列名（顺序即 {@link HistorySample} 的组件顺序，也是旧格式的位置口径）。 */
    private static final String[] COLUMNS = {
        "epochMs", "tps1m", "msptAvg", "heapUsagePercent",
        "playerCount", "totalEntities", "totalLoadedChunks"
    };

    /** 9A-18：旧格式（无版本头）的列位置 = 0..6。 */
    private static final int[] LEGACY_COLUMNS = {0, 1, 2, 3, 4, 5, 6};

    /** 9A-18：schema 头（版本号 + 列名；读取端按列名映射，未来加列只需追加名字）。 */
    private static final String HEADER =
        "# v2 " + String.join(",", COLUMNS);

    /** 9A-18：写队列容量（24h @60s = 1440 条，留 40% 余量；写线程卡住时最多积压这么多）。 */
    private static final int QUEUE_CAPACITY = 2048;

    /** 9A-18：批量 flush 阈值（条数）。 */
    private static final int BATCH_SIZE = 64;

    /** 9A-18：批量 flush 阈值（毫秒；队列空等也用这个节拍做周期性 flush / 丢弃告警）。 */
    private static final long FLUSH_INTERVAL_MS = 5_000L;

    /** 9A-18：连续失败达到该值升 SEVERE（之后每 60 次再报一条，避免刷屏）。 */
    private static final int FAILURE_SEVERE_THRESHOLD = 3;

    /** 9A-18：失败/丢弃类日志的限频间隔（5 分钟）。 */
    private static final long FAILURE_LOG_INTERVAL_MS = 300_000L;

    /** 9A-18：shutdown 等待写线程退出的上限。 */
    private static final long SHUTDOWN_JOIN_MS = 5_000L;

    /** 9A-18：JVM 退出兜底钩子（PerfScope.onDisable 尚未接线前也不会丢最后一批样本）。
     *  构造末尾才挂到 Runtime（字段初始化完成后），避免 this-escape。 */
    private final Thread shutdownHook;

    /** 9A-18：写线程名（/perfscope status 与线程转储里可辨认）。 */
    public static final String WRITER_THREAD_NAME = "perfscope-history-writer";

    private final ConfigManager configManager;
    private final Path dir;

    /** 9A-18：日志出口（与 plugin.getLogger() 等价；抽出便于无 Bukkit 环境的单测）。 */
    private final Logger logger;

    /** 9A-18：全类统一的时区（跨天判定/文件日期/retention 同一口径）。 */
    private final ZoneId zone = ZoneId.systemDefault();

    /** 9A-18：待写样本队列（单写者；offer 非阻塞）。 */
    private final BlockingQueue<HistorySample> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    /** 9A-18：因队列满丢弃的样本数（累计）。 */
    private final AtomicLong droppedSamples = new AtomicLong();

    /** 9A-18：读取时解析失败（损坏行/半行/列数不符）的累计行数。 */
    private final AtomicLong skippedLines = new AtomicLong();

    /** 9A-18：按日缓存（key = 文件日期；命中条件 = 文件名 + mtime + size 全等）。 */
    private final ConcurrentHashMap<LocalDate, CachedFile> cache = new ConcurrentHashMap<>();

    /** 写线程存活标志（append 的门控之一）。 */
    private volatile boolean running = true;

    /** 写线程句柄（shutdown 用）。 */
    private volatile Thread writerThread;

    /** 9A-18：健康状态（volatile，跨线程读）。 */
    private volatile long lastSuccessAtMs = 0L;
    private volatile int consecutiveFailures = 0;
    private volatile String lastError = null;
    private volatile long lastFailureLogMs = 0L;
    private volatile long lastDropReported = 0L;
    private volatile long lastDropLogMs = 0L;

    /** 当前写入的文件日期（跨天检测；null = 尚未写过）；仅写线程访问。 */
    private LocalDate currentFileDate = null;

    /** 长效写句柄；仅写线程访问。 */
    private BufferedWriter writer = null;

    public HistoryFileStore(PerfScope plugin) {
        this(plugin.getConfigManager(), plugin.getDataFolder().toPath().resolve("history"), plugin.getLogger());
    }

    /**
     * 9A-18：与 Bukkit 解耦的构造（目录/日志显式传入；单测与诊断工具用）。
     *
     * @param configManager 配置来源（{@code get()} 可为 null，按不可持久化处理）
     * @param historyDir    历史目录（不存在则创建）
     * @param logger        日志出口（null 回退 "PerfScope" 同名 logger）
     */
    public HistoryFileStore(ConfigManager configManager, Path historyDir, Logger logger) {
        this.configManager = configManager;
        this.dir = historyDir;
        this.logger = logger != null ? logger : Logger.getLogger("PerfScope");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            recordFailure("历史目录创建失败", e);
        }
        Thread t = new Thread(this::writerLoop, WRITER_THREAD_NAME);
        t.setDaemon(true);
        this.writerThread = t;
        // 9A-18：兜底——即便 onDisable 忘记调用 shutdown()，JVM 退出前也会落盘最后一批
        this.shutdownHook = new Thread(this::shutdown, "perfscope-history-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM 已处于关闭流程（插件热重载场景），无需再挂
        }
        // 全部字段初始化完成后再启动写线程（避免最长可达 5 秒的轮询期里读到半构造对象）
        t.start();
    }

    // ==================== 写入路径（9A-18：主线程零 IO） ====================

    /**
     * 持久化是否可用（目录存在且可写）。注意：这是<b>即时探测</b>（有系统调用），
     * 供状态命令/读取路径使用；{@link #append} 不走它，以免主线程做 IO。
     */
    public boolean available() {
        return Files.isDirectory(dir) && Files.isWritable(dir);
    }

    /**
     * 追加一条样本（采集主线程调用；只入队，不阻塞、不做 IO）。
     *
     * <p>{@code history.persist=false} 或写线程未启动时直接返回。队列满（写盘长期跟不上）
     * 则丢弃并计入 {@link #droppedSamples()}——丢弃优于阻塞主线程。</p>
     */
    public void append(HistorySample sample) {
        if (sample == null || !running || configManager == null) {
            return;
        }
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg == null || !cfg.history_persistEnabled) {
            return;
        }
        if (!queue.offer(sample)) {
            droppedSamples.incrementAndGet();
        }
    }

    /**
     * 写线程主循环：批量收集 → 写盘 → 周期性 flush / 丢弃告警。
     *
     * <p>9A-18 注意：{@code shutdown()} 用 {@code interrupt()} 叫醒阻塞中的 poll，<b>不能用 break
     * 直接退出</b>——那会把手里已收集未写盘的批次丢掉（队列里取出的样本已经出队，断点丢失）。
     * 因此中断只用于结束等待：先清中断位（不清的话 NIO channel 会抛 ClosedByInterruptException），
     * 把手上这批写完再退出。</p>
     */
    private void writerLoop() {
        try {
            while (true) {
                HistorySample first;
                try {
                    first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (!running) {
                        break; // 关停：队列由 drainAndClose 收尾
                    }
                    continue;
                }
                if (first == null) {
                    if (!running) {
                        break;
                    }
                    flushWriter();
                    reportDropsThrottled();
                    continue;
                }
                List<HistorySample> batch = new ArrayList<>(BATCH_SIZE);
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                // 9A-18：只有"已有积压"时才继续合并（最多等 FLUSH_INTERVAL_MS）；队列已空就立刻写，
                // 否则常态（60 秒一条）下每条样本都要白等 5 秒才落盘，关服窗口内更易丢数据。
                if (!queue.isEmpty()) {
                    long deadline = System.currentTimeMillis() + FLUSH_INTERVAL_MS;
                    boolean interrupted = false;
                    while (batch.size() < BATCH_SIZE) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0L) {
                            break;
                        }
                        try {
                            HistorySample next = queue.poll(remaining, TimeUnit.MILLISECONDS);
                            if (next == null) {
                                break;
                            }
                            batch.add(next);
                        } catch (InterruptedException e) {
                            interrupted = true; // 清中断位后继续把手上这批写完
                            break;
                        }
                    }
                    writeBatch(batch);
                    reportDropsThrottled();
                    if (interrupted || !running) {
                        break;
                    }
                    continue;
                }
                writeBatch(batch);
                reportDropsThrottled();
                if (!running) {
                    break;
                }
            }
        } catch (Exception | LinkageError e) {
            recordFailure("历史写线程异常", e);
        }
        drainAndClose();
    }

    /** 写一批样本（写线程）；跨天时换文件，失败则关句柄等下一批重开。 */
    private void writeBatch(List<HistorySample> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            for (HistorySample sample : batch) {
                LocalDate date = Instant.ofEpochMilli(sample.timestamp()).atZone(zone).toLocalDate();
                if (currentFileDate == null || !currentFileDate.equals(date)) {
                    openWriter(date);
                }
                if (writer == null) {
                    return; // openWriter 失败（已计数），本批放弃
                }
                writer.write(format(sample));
                writer.write('\n');
            }
            writer.flush();
            onWriteSuccess();
        } catch (Exception | LinkageError e) {
            closeWriterQuietly();
            recordFailure("历史写入失败", e);
        }
    }

    /** 仅 flush（无新样本时的周期节拍；顺便把缓冲区落盘）。 */
    private void flushWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            onWriteSuccess();
        } catch (Exception | LinkageError e) {
            closeWriterQuietly();
            recordFailure("历史 flush 失败", e);
        }
    }

    /**
     * 打开/滚动到指定日期的文件（写线程）。
     *
     * <p>9A-18 半行保护：既有文件末字节不是换行时先补 {@code \n}——上次异常退出/写盘中断
     * 留下的半行，否则新样本会拼到它尾巴上，整行报废（连新增的那条一起丢）。</p>
     */
    private void openWriter(LocalDate date) throws IOException {
        closeWriterQuietly();
        Files.createDirectories(dir);
        Path file = fileFor(date);
        long size = 0L;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0L; // 不存在 → 新文件
        }
        BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            if (size == 0L) {
                w.write(HEADER);
                w.write('\n');
            } else if (!endsWithNewline(file)) {
                w.write('\n');
            }
        } catch (IOException e) {
            try {
                w.close();
            } catch (IOException ignored) {
                // 已失败，忽略
            }
            throw e;
        }
        this.writer = w;
        this.currentFileDate = date;
    }

    /** 末字节是否为换行（{@code \n} 或 {@code \r}）；空文件视为 true。 */
    private static boolean endsWithNewline(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size <= 0L) {
                return true;
            }
            channel.position(size - 1L);
            ByteBuffer buf = ByteBuffer.allocate(1);
            int read = channel.read(buf);
            if (read <= 0) {
                return true;
            }
            byte b = buf.get(0);
            return b == '\n' || b == '\r';
        }
    }

    private static String format(HistorySample sample) {
        return sample.timestamp() + "," + sample.tps1m() + "," + sample.msptAvg() + ","
            + sample.heapUsagePercent() + "," + sample.playerCount() + ","
            + sample.totalEntities() + "," + sample.totalLoadedChunks();
    }

    /**
     * 9A-18：停止写线程并落盘（{@code PerfScope.onDisable} 必须调用）。
     * 幂等：重复调用无副作用。等待上限 {@value #SHUTDOWN_JOIN_MS} ms，超时只记日志
     * （守护线程随 JVM 退出，不会挂住关服）。
     */
    public synchronized void shutdown() {
        if (!running && writerThread == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException | NullPointerException ignored) {
            // JVM 正在关闭 / 钩子从未挂上：忽略
        }
        running = false;
        Thread t = writerThread;
        if (t == null) {
            closeWriterQuietly();
            return;
        }
        t.interrupt();
        try {
            t.join(SHUTDOWN_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            logger.warning("[历史] 写线程未在 " + SHUTDOWN_JOIN_MS
                + " ms 内退出，剩余 " + queue.size() + " 条样本可能丢失（目录: " + dir.toAbsolutePath() + "）");
            closeWriterQuietly();
        }
        writerThread = null;
    }

    /** 收尾：把队列里剩余样本写完再关文件（写线程退出路径）。 */
    private void drainAndClose() {
        List<HistorySample> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            writeBatch(rest);
        }
        closeWriterQuietly();
    }

    private void closeWriterQuietly() {
        BufferedWriter w = this.writer;
        this.writer = null;
        this.currentFileDate = null;
        if (w != null) {
            try {
                w.flush();
                w.close();
            } catch (IOException ignored) {
                // 关闭期失败无处可报；打开失败会在下一批体现
            }
        }
    }

    // ==================== 健康状态（9A-18） ====================

    /**
     * 文件链路是否健康：写线程存活 + 目录可写 + 连续失败次数低于 {@value #FAILURE_SEVERE_THRESHOLD}。
     * 不包含 {@code history.persist} 开关判断（调用方自行区分"用户主动关闭"与"坏了"）。
     */
    public boolean isHealthy() {
        Thread t = writerThread;
        return running && t != null && t.isAlive()
            && consecutiveFailures < FAILURE_SEVERE_THRESHOLD
            && available();
    }

    /** 最近一次成功写盘时刻（epoch ms）；0 = 本次进程从未成功。 */
    public long lastSuccessAtMs() {
        return lastSuccessAtMs;
    }

    /** 连续失败次数（任一次成功写盘即清零）。 */
    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** 最近一次失败原因（成功写盘后清空）；null = 本次进程未失败过。 */
    public String lastError() {
        return lastError;
    }

    /** 因写队列满而丢弃的样本数（累计）。 */
    public long droppedSamples() {
        return droppedSamples.get();
    }

    /** 读取时跳过的损坏行数（累计；缓存命中不重复计数）。 */
    public long skippedLines() {
        return skippedLines.get();
    }

    /** 历史目录（绝对路径；状态命令/错误提示用）。 */
    public Path directory() {
        return dir;
    }

    /** 当前排队待写的样本数（诊断用）。 */
    public int pendingWrites() {
        return queue.size();
    }

    /** 写线程是否仍在运行。 */
    public boolean writerRunning() {
        Thread t = writerThread;
        return running && t != null && t.isAlive();
    }

    private void onWriteSuccess() {
        lastSuccessAtMs = System.currentTimeMillis();
        consecutiveFailures = 0;
        lastError = null;
    }

    /**
     * 记录一次失败并按其严重度打日志（9A-18）：
     * 第 1 次 WARNING；连续第 {@value #FAILURE_SEVERE_THRESHOLD} 次升 SEVERE（含原因与目录路径）；
     * 之后每 60 次再报一条 SEVERE。同类首发 5 分钟限频，避免瞬时刷屏。
     */
    private void recordFailure(String what, Throwable e) {
        int failures = ++consecutiveFailures;
        String reason = e == null ? "未知原因" : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        lastError = what + ": " + reason;
        String msg = "[历史] " + lastError + "（目录: " + dir.toAbsolutePath() + "）";
        if (failures == FAILURE_SEVERE_THRESHOLD) {
            logger.severe(msg + " —— 连续失败 " + failures
                + " 次，文件持久化已停摆；/perfscope trend 只能回退内存环");
            lastFailureLogMs = System.currentTimeMillis();
            return;
        }
        if (failures > FAILURE_SEVERE_THRESHOLD) {
            if (failures % 60 == 0 && System.currentTimeMillis() - lastFailureLogMs >= FAILURE_LOG_INTERVAL_MS) {
                lastFailureLogMs = System.currentTimeMillis();
                logger.severe(msg + " —— 连续失败 " + failures + " 次");
            }
            return;
        }
        logThrottled(msg);
    }

    /** 队列丢弃告警（写线程调用；5 分钟一条，首条立即）。 */
    private void reportDropsThrottled() {
        long dropped = droppedSamples.get();
        if (dropped <= lastDropReported) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDropLogMs < FAILURE_LOG_INTERVAL_MS) {
            return;
        }
        lastDropLogMs = now;
        lastDropReported = dropped;
        logger.warning("[历史] 写队列已满，累计丢弃 " + dropped
            + " 条历史样本（写盘速度跟不上采样；目录: " + dir.toAbsolutePath() + "）");
    }

    /** 失败日志限频：同错误 5 分钟一条（防故障期刷屏）。 */
    private void logThrottled(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogMs < FAILURE_LOG_INTERVAL_MS) {
            return;
        }
        lastFailureLogMs = now;
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg != null && cfg.general_debug) {
            logger.log(Level.WARNING, msg);
        } else {
            logger.warning(msg);
        }
    }

    // ==================== 读取路径（9A-18：缓存 + 二分） ====================

    /**
     * 加载最近 maxDays 天内、时间戳 ≥ sinceMs 的全部样本（旧→新）。
     *
     * <p>9A-18：每个文件先查缓存（mtime + size 命中即复用解析结果）；{@code sinceMs} 落在文件中段时
     * 用二分查找跳过前段，不再全量解析 7 天 10080 行。单文件失败只记日志（不中断其余文件）。</p>
     */
    public List<HistorySample> loadRecentDays(int maxDays, long sinceMs) {
        List<HistorySample> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        // 9A-6：原为 minusDays(maxDays - 1)，days=1 时 cutoff=今天 → 昨天的 CSV 整文件被跳过，
        // 于是凌晨问"昨晚卡不卡"只能看到今天的数据，而输出仍写"近 1 天"。
        // 多读一天由 sinceMs 精确裁剪（一次多读一个文件的代价），保证跨午夜可查。
        LocalDate cutoff = LocalDate.now(zone).minusDays(Math.max(0, maxDays));
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> candidates = files
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
                })
                .sorted(Comparator.comparing(Path::getFileName))
                .toList();
            for (Path file : candidates) {
                LocalDate fileDate = parseFileDate(file);
                if (fileDate == null || fileDate.isBefore(cutoff)) {
                    continue;
                }
                List<HistorySample> samples = loadFileCached(file, fileDate);
                if (samples.isEmpty()) {
                    continue;
                }
                int from = lowerBound(samples, sinceMs);
                for (int i = from; i < samples.size(); i++) {
                    out.add(samples.get(i));
                }
            }
        } catch (Exception | LinkageError e) {
            logger.warning("[历史] 历史文件枚举失败: " + e.getMessage());
        }
        out.sort(Comparator.comparingLong(HistorySample::timestamp));
        return out;
    }

    /**
     * 取某文件的样本（带缓存）。缓存键 = 文件日期，命中条件 = 文件名 + mtime + size 全等。
     * 取 stat 在读取<b>之前</b>：若写线程在读取期间又追加，下一次调用会因 size 变化而重读，
     * 不会长期返回旧缓存。读取失败返回空列表（不污染缓存）。
     */
    private List<HistorySample> loadFileCached(Path file, LocalDate date) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            long mtime = attrs.lastModifiedTime().toMillis();
            long size = attrs.size();
            String name = file.getFileName().toString();
            CachedFile cached = cache.get(date);
            if (cached != null && cached.mtimeMs == mtime && cached.size == size
                && cached.fileName.equals(name)) {
                return cached.samples;
            }
            List<HistorySample> parsed = readFile(file);
            cache.put(date, new CachedFile(name, mtime, size, parsed));
            return parsed;
        } catch (Exception | LinkageError e) {
            logger.warning("[历史] 历史文件读取失败（已跳过）: "
                + file.getFileName() + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 全量解析一个文件（返回不可变、按时间升序的列表）；坏行计入 {@link #skippedLines()}。
     *
     * <p>9A-18 半行口径：文件末尾若无换行，那最后一行可能是<b>正在写</b>的半行（读竞态）或
     * 上次异常退出的残行——不计入 {@code skippedLines}（否则每次读到写中文件都虚增坏行数），
     * 写线程打开文件时会补 {@code \n}，残行由此隔离，不会再与新数据拼接。</p>
     */
    private List<HistorySample> readFile(Path file) {
        List<HistorySample> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            int[] columns = LEGACY_COLUMNS;
            boolean headerSeen = false; // 首个非注释行是否已出现（表头只可能是第 1 行）
            String pending = null;      // 上一条已读行（读到下一条才能确认它不是文件尾）
            String line;
            while ((line = r.readLine()) != null) {
                if (pending != null) {
                    consumeLine(pending, columns, out);
                    headerSeen = true;
                }
                if (!headerSeen && line.startsWith("#")) {
                    int[] mapped = parseHeader(line);
                    columns = mapped != null ? mapped : LEGACY_COLUMNS;
                }
                pending = line;
            }
            if (pending != null) {
                // 9A-18：最后一行且文件无结尾换行时，可能是写了一半的行（读竞态/上次异常退出）；
                // 解析失败时不计入 skippedLines，避免每次读到写中文件都虚增坏行数。
                if (endsWithNewline(file) || parseLine(pending, columns) != null) {
                    consumeLine(pending, columns, out);
                }
            }
        } catch (Exception | LinkageError e) {
            logger.warning("[历史] 历史文件读取失败（已跳过）: "
                + file.getFileName() + ": " + e.getMessage());
        }
        // 二分查找要求时间升序；正常追加写天然有序，NTP 回拨等异常情况下排序兜底
        boolean sorted = true;
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i).timestamp() < out.get(i - 1).timestamp()) {
                sorted = false;
                break;
            }
        }
        if (!sorted) {
            out.sort(Comparator.comparingLong(HistorySample::timestamp));
        }
        return List.copyOf(out);
    }

    /** 解析并收录一行；注释/空行跳过，损坏行计入 skippedLines。 */
    private void consumeLine(String line, int[] columns, List<HistorySample> out) {
        if (line.isEmpty() || line.charAt(0) == '#') {
            return; // 表头/注释/空行不算损坏
        }
        HistorySample s = parseLine(line, columns);
        if (s == null) {
            skippedLines.incrementAndGet();
        } else {
            out.add(s);
        }
    }

    /**
     * 9A-18：解析表头，返回 7 列的列位置映射；不是可识别的表头返回 null。
     * 支持 {@code # v2 epochMs,...}（版本前缀被忽略，按列名定位）与旧的 {@code # epochMs,...}。
     */
    private static int[] parseHeader(String raw) {
        String content = raw.startsWith("#") ? raw.substring(1).trim() : raw.trim();
        if (content.isEmpty()) {
            return null;
        }
        // 9A-18：版本号与第一个列名以空格分隔（"# v2 epochMs,..."），先按空格剥掉版本前缀，
        // 否则 "v2 epochMs" 会被当成一个列名（曾导致整表映射失败、回退到位置口径）。
        int space = content.indexOf(' ');
        if (space > 0) {
            String prefix = content.substring(0, space);
            if (prefix.length() >= 2
                && (prefix.charAt(0) == 'v' || prefix.charAt(0) == 'V')
                && Character.isDigit(prefix.charAt(1))) {
                content = content.substring(space + 1).trim();
            }
        }
        if (content.isEmpty()) {
            return null;
        }
        String[] tokens = content.split(",");
        if (tokens.length < COLUMNS.length) {
            return null;
        }
        int[] idx = new int[COLUMNS.length];
        for (int i = 0; i < COLUMNS.length; i++) {
            int pos = -1;
            for (int j = 0; j < tokens.length; j++) {
                if (tokens[j].trim().equalsIgnoreCase(COLUMNS[i])) {
                    pos = j;
                    break;
                }
            }
            if (pos < 0) {
                return null; // 缺列 → 按旧位置口径读（兼容早期文件）
            }
            idx[i] = pos;
        }
        return idx;
    }

    /** 解析一行 CSV；空行/注释/列数不足/数值非法 → null（容错跳过，含写一半的最后一行）。 */
    private static HistorySample parseLine(String line, int[] columns) {
        if (line == null || line.isEmpty() || line.charAt(0) == '#') {
            return null;
        }
        int[] cols = columns == null ? LEGACY_COLUMNS : columns;
        String[] parts = line.split(",", -1);
        int need = 0;
        for (int c : cols) {
            need = Math.max(need, c + 1);
        }
        if (parts.length < need) {
            return null;
        }
        try {
            return new HistorySample(
                Long.parseLong(parts[cols[0]].trim()),
                Double.parseDouble(parts[cols[1]].trim()),
                Double.parseDouble(parts[cols[2]].trim()),
                Integer.parseInt(parts[cols[3]].trim()),
                Integer.parseInt(parts[cols[4]].trim()),
                Integer.parseInt(parts[cols[5]].trim()),
                Integer.parseInt(parts[cols[6]].trim())
            );
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    /** 9A-18：第一个 timestamp ≥ sinceMs 的下标（列表须按时间升序）。 */
    private static int lowerBound(List<HistorySample> samples, long sinceMs) {
        int lo = 0;
        int hi = samples.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).timestamp() < sinceMs) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static LocalDate parseFileDate(Path file) {
        String name = file.getFileName().toString();
        try {
            return LocalDate.parse(name.substring(FILE_PREFIX.length(),
                name.length() - FILE_SUFFIX.length()));
        } catch (Exception e) {
            return null;
        }
    }

    private Path fileFor(LocalDate date) {
        return dir.resolve(FILE_PREFIX + date + FILE_SUFFIX);
    }

    /** 9A-18：读取缓存条目（文件名 + mtime + size + 解析结果）。 */
    private record CachedFile(String fileName, long mtimeMs, long size, List<HistorySample> samples) {
    }

    // ==================== 清理（9A-18：与跨天滚动解耦） ====================

    /**
     * 9A-18：独立的历史保留清理（启动/reload 时由 {@code PerfScope} 调用）。
     *
     * <p>口径统一为"保留最近 {@code days} 个<b>日历日</b>（含今天）"：cutoff = today-(days-1)，
     * 早于 cutoff 的文件删除。旧实现 cutoff = today-days，实际多留 1 天且与
     * {@link #loadRecentDays} 的读取口径不一致。</p>
     *
     * @param days 保留天数（&lt;1 按 1 处理）
     */
    public void runRetention(int days) {
        if (!available()) {
            return;
        }
        int keep = Math.max(1, days);
        LocalDate cutoff = LocalDate.now(zone).minusDays(keep - 1L);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
            }).forEach(p -> {
                LocalDate d = parseFileDate(p);
                if (d != null && d.isBefore(cutoff)) {
                    try {
                        Files.deleteIfExists(p);
                        cache.remove(d);
                    } catch (IOException e) {
                        logger.warning("[历史] 旧历史文件删除失败: "
                            + p.getFileName() + ": " + e.getMessage());
                    }
                }
            });
        } catch (Exception | LinkageError e) {
            logger.warning("[历史] 历史清理失败: " + e.getMessage());
        }
    }

    /**
     * 旧入口（保留兼容，未标 {@code @Deprecated}——调用点 PerfScope 暂不在本次改动范围）：
     * 等价于 {@link #runRetention(int)}。9A-18 起新代码请用 {@code runRetention}。
     */
    public void cleanOld(int retentionDays) {
        runRetention(retentionDays);
    }

    /** 9A-18：清空读取缓存（外部修改历史文件/测试用；正常追加无需调用——mtime+size 自动失效）。 */
    public void invalidateCache() {
        cache.clear();
    }

    /** 当前时区（9A-18：跨天判定、retention、文件日期共用同一 ZoneId；测试/诊断用）。 */
    public ZoneId zone() {
        return zone;
    }
}
