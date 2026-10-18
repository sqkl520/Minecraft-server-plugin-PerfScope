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
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * 历史文件持久化（Wave 8B）——按天分文件的 CSV 追加写，重启不清零。
 *
 * <p><b>为什么不是 SQLite</b>（锐评 B 论点 8）：SQLite-JDBC 是本项目首个重量级运行时依赖
 * （native 库 + classloader 复杂度，与 v1.0.0 shade 事故同类土壤）；而本场景（1 条/分钟的
 * 采样、7 天保留、单机读写）用按天 CSV 文件即可获得全部价值、零依赖风险。逐天文件还带来
 * 天然的分片与过期删除便利。</p>
 *
 * <p><b>文件布局</b>：{@code plugins/PerfScope/history/yyyy-MM-dd.csv}，每行：
 * {@code epochMs,tps1m,msptAvg,heapUsagePercent,playerCount,totalEntities,totalLoadedChunks}
 * （与 {@link HistorySample} 字段序一致，RAW 值不消毒）。</p>
 *
 * <p><b>写入节流</b>：与内存环共用 {@code record} 调用点（已按 sample-interval 节流），
 * 本类不再二次节流；追加写在采集主线程执行（单行 append，开销可忽略）。
 * 等跨天时自动滚到新文件。</p>
 *
 * <p><b>读取</b>：{@link #loadRecentDays(int, long)} 按需加载最近 N 天的全部样本（旧→新），
 * 供 /perfscope trend 使用；跳过损坏行（容错），单文件不可读只记日志不影响其余。</p>
 *
 * <p><b>清理</b>：{@link #cleanOld(int)} 删除超过保留天数的文件（只在启动/reload 时调用一次）。</p>
 *
 * <p>线程安全：写入仅采集主线程；读取（命令层）与写入的竞争以"读到部分行"最坏情况处理——
 * 读取端跳过解析失败的行（含写一半的最后一行），不抛异常。零依赖（纯 JDK NIO）。</p>
 */
public class HistoryFileStore {

    private static final String FILE_PREFIX = "history-";
    private static final String FILE_SUFFIX = ".csv";
    /** 表头注释行（# 开头，读取端跳过）。 */
    private static final String HEADER = "# epochMs,tps1m,msptAvg,heapUsagePercent,playerCount,totalEntities,totalLoadedChunks";

    private final PerfScope plugin;
    private final ConfigManager configManager;
    private final Path dir;

    /** 当前写入的文件日期（跨天检测；null = 尚未写过）。 */
    private LocalDate currentFileDate = null;

    public HistoryFileStore(PerfScope plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.dir = plugin.getDataFolder().toPath().resolve("history");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            plugin.getLogger().warning("[历史] 历史目录创建失败（持久化停用，内存环不受影响）: " + e.getMessage());
        }
    }

    /** 持久化是否可用（目录存在且可写）。 */
    public boolean available() {
        return Files.isDirectory(dir) && Files.isWritable(dir);
    }

    /**
     * 追加一条样本（采集主线程调用；history.persist=false 或目录不可用时直接返回）。
     * 单行 append + 必要时跨天滚动；任何 IO 失败只记日志（限频：同错误 5 分钟一条），
     * 绝不影响采集主流程。
     */
    public void append(HistorySample sample) {
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg == null || !cfg.history_persistEnabled || !available()) {
            return;
        }
        LocalDate today = LocalDate.now();
        try {
            if (currentFileDate == null || !currentFileDate.equals(today)) {
                rollToDate(today);
            }
            if (currentFileDate == null) {
                return; // rollToDate 失败（已记日志）
            }
            Path file = dir.resolve(FILE_PREFIX + currentFileDate + FILE_SUFFIX);
            boolean isNew = !Files.exists(file);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (isNew) {
                    w.write(HEADER);
                    w.write('\n');
                }
                w.write(sample.timestamp() + "," + sample.tps1m() + "," + sample.msptAvg() + ","
                    + sample.heapUsagePercent() + "," + sample.playerCount() + ","
                    + sample.totalEntities() + "," + sample.totalLoadedChunks());
                w.write('\n');
            }
        } catch (Exception | LinkageError e) {
            logFailureThrottled("[历史] 历史写入失败: " + e.getMessage());
        }
    }

    /** 跨天滚动（顺带做一次轻量清理：删除超过保留期的旧文件）。 */
    private void rollToDate(LocalDate date) {
        try {
            Files.createDirectories(dir);
            currentFileDate = date;
            cleanOld(configManager.get() == null ? 7 : configManager.get().history_persistRetentionDays);
        } catch (Exception | LinkageError e) {
            currentFileDate = null;
            logFailureThrottled("[历史] 历史目录不可用: " + e.getMessage());
        }
    }

    /**
     * 加载最近 maxDays 天内、时间戳 ≥ sinceMs 的全部样本（旧→新）。
     * 逐文件读取、跳过损坏行；单文件失败只记日志（不中断其余文件）。
     */
    public List<HistorySample> loadRecentDays(int maxDays, long sinceMs) {
        List<HistorySample> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        LocalDate cutoff = LocalDate.now().minusDays(Math.max(0, maxDays - 1));
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
                readFile(file, sinceMs, out);
            }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[历史] 历史文件枚举失败: " + e.getMessage());
        }
        out.sort(Comparator.comparingLong(HistorySample::timestamp));
        return out;
    }

    private void readFile(Path file, long sinceMs, List<HistorySample> out) {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                HistorySample s = parseLine(line);
                if (s != null && s.timestamp() >= sinceMs) {
                    out.add(s);
                }
            }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[历史] 历史文件读取失败（已跳过）: "
                + file.getFileName() + ": " + e.getMessage());
        }
    }

    /** 解析一行 CSV；# 注释/空行/字段数不符/数值非法 → null（容错跳过，含写一半的最后一行）。 */
    private static HistorySample parseLine(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) == '#') {
            return null;
        }
        String[] parts = line.split(",");
        if (parts.length != 7) {
            return null;
        }
        try {
            return new HistorySample(
                Long.parseLong(parts[0].trim()),
                Double.parseDouble(parts[1].trim()),
                Double.parseDouble(parts[2].trim()),
                Integer.parseInt(parts[3].trim()),
                Integer.parseInt(parts[4].trim()),
                Integer.parseInt(parts[5].trim()),
                Integer.parseInt(parts[6].trim())
            );
        } catch (NumberFormatException e) {
            return null;
        }
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

    /** 删除超过保留天数的历史文件（启动/reload 时调用；只删本插件命名的文件，不碰用户文件）。 */
    public void cleanOld(int retentionDays) {
        if (!available()) {
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(Math.max(1, retentionDays));
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
            }).forEach(p -> {
                LocalDate d = parseFileDate(p);
                if (d != null && d.isBefore(cutoff)) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        plugin.getLogger().warning("[历史] 旧历史文件删除失败: "
                            + p.getFileName() + ": " + e.getMessage());
                    }
                }
            });
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[历史] 历史清理失败: " + e.getMessage());
        }
    }

    /** 失败日志限频：同类错误 5 分钟一条（防故障期刷屏）；字段仅主线程访问。 */
    private long lastFailureLogMs = 0L;

    private void logFailureThrottled(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogMs < 300_000L) {
            return;
        }
        lastFailureLogMs = now;
        if (configManager.get() != null && configManager.get().general_debug) {
            plugin.getLogger().log(Level.WARNING, msg);
        } else {
            plugin.getLogger().warning(msg);
        }
    }

    /** 当前时区（ZoneId 仅在跨天判定时用到；暴露给测试/诊断用）。 */
    public ZoneId zone() {
        return ZoneId.systemDefault();
    }
}
