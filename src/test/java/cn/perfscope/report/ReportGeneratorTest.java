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
package cn.perfscope.report;

import cn.perfscope.PerfScope;
import cn.perfscope.compat.ServerCompat;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.TestObjects;

import com.google.gson.Gson;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ReportGenerator} 的<b>主路径</b>测试：{@code generateAndSave()} / {@code generateSpecific(String)}
 * 真正把文件写到磁盘上。
 *
 * <p>写这个类之前，{@code report/} 包只有 {@code FormatBytesTest}（字节格式化）和
 * {@code ReportFileNameTest}（清理用的文件名正则），<b>两条主路径零覆盖</b>。实测变异：
 * 把 {@code generateSpecific()} 改成永远 {@code return null}，全量 421 个用例一个都不红。</p>
 *
 * <h2>一、怎么在没有服务端的 JVM 里造出一个能生成报告的实例</h2>
 * <p>{@link ReportGenerator} 的构造器接收 {@link PerfScope}（{@code final} 类、无公开构造器），
 * 于是用 {@link TestObjects#allocate} + {@link TestObjects#setField} 注入
 * {@code logger / description / dataFolder / configManager / metricsCollector} 五个字段，
 * 然后走<b>真实构造器</b>——本类的价值就在于构造器与主路径都是真的，只有 Bukkit 边界是假的。</p>
 *
 * <h2>二、为什么 HTML/TXT 用例必须用「TPS 不可用」的快照</h2>
 * <p>{@code ReportGenerator} 在 TPS 可用时会调用 {@link ServerCompat#tpsScopeLabel()}，
 * 而 {@code ServerCompat} 的静态块第一行是 {@code Bukkit.getBukkitVersion()}——单测 JVM 里
 * {@code Bukkit.server} 为 null，初始化抛 {@code ExceptionInInitializerError}，且失败后
 * <b>不可重试</b>（第二次是 {@code NoClassDefFoundError}）。{@code generateSpecificOnExecutor}
 * 的 {@code catch (Throwable)} 会把它兜成 {@code null}。</p>
 * <p>所以本类对 HTML/TXT 的主路径使用<b>真实存在</b>的服务器形态：服主把 TPS/MSPT 模块关掉了
 * （两个模块都在 {@code unavailable}，MSPT 同时在 {@code disabledModules}，于是成因判定
 * 走文案分支而不碰 {@code ServerCompat}）。这不是为了绕开而编造的形态——它是
 * {@code MetricsSnapshot} 契约里正大光明的一支，且报告正文（卡片、表格、告警、页脚）
 * 全都照常渲染。JSON 路径完全不碰 {@code ServerCompat}，因此 JSON 用例用「全模块可用」的富快照。</p>
 * <p>「TPS 可用」那条路径单独由 {@link #tpsAvailablePathDependsOnServerCompatInitialization()}
 * 覆盖：它按当前 JVM 的真实能力断言<b>成功或明确失败</b>，两种结果都是契约的一部分。</p>
 *
 * <h2>三、为什么不信固定 sleep</h2>
 * <p>中断 / 内联 / 并发这三条用例全部用 {@link CountDownLatch} 与 {@code join(timeout)} 建立
 * 可观测同步点：执行器被我们自己提交的阻塞任务占住 → 这本身就是「写盘任务正在排队」的
 * 确定性证据，不需要猜「大概已经提交了吧」。</p>
 */
class ReportGeneratorTest {

    /** 注入到 {@code PluginDescriptionFile} 的版本号；报告页脚与 JSON 的 plugin_version 都取自它。 */
    private static final String PLUGIN_VERSION = "1.4.1";

    /** 插件自己产出的报告文件名（与 {@code ReportGenerator.REPORT_FILE_NAME} 同形，{@code -N} 为碰撞后缀）。 */
    private static final Pattern REPORT_FILE = Pattern.compile(
        "^perfscope-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}(-\\d+)?\\.(html|txt|json)$");

    /**
     * 本 JVM 里 {@link ServerCompat} 能否完成静态初始化。
     *
     * <p>在 {@link #probeServerCompat()} 里<b>主动</b>探一次（而不是等某次报告生成顺手触发）：
     * 初始化是一次性的，探失败会让后续引用固定抛 {@code NoClassDefFoundError}——这正是
     * 真实生产环境之外的 JVM 里该有的形态，提前探明比在断言里撞运气好。</p>
     */
    private static boolean serverCompatUsable;

    /** 每条用例创建的生成器，{@code @AfterEach} 统一 {@code shutdown()}，不留守护线程。 */
    private final List<ReportGenerator> generators = new ArrayList<>();

    /**
     * 最近一个 {@link PerfScope} 的日志。用于断言「某条路径<b>没有</b>被走到」——
     * 例如没有快照时根本不该尝试渲染（{@code generateSpecificOnExecutor} 的
     * {@code catch (Throwable)} 会把任何渲染异常记成 {@code report.log.generate-failed}）。
     * 只靠返回值看不出来：渲染抛异常与「提前 return null」的返回值完全一样。
     */
    private final List<LogRecord> logs = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void probeServerCompat() {
        TestObjects.assertReflectionUsable();
        try {
            ServerCompat.getServerName();
            serverCompatUsable = true;
        } catch (Throwable t) {
            // 无 Bukkit 服务端时的标准形态：ExceptionInInitializerError（首次）/
            // NoClassDefFoundError（之后）。两者都不该让本类失败——见类注释二。
            serverCompatUsable = false;
        }
    }

    /**
     * 本类只测「生成 + 写盘」，不测清理。保留天数置 0 让 {@code cleanOldReports()} 首行早退，
     * 避免清理逻辑的失败以「文件不见了」的形式污染生成断言；清理路径另有一条
     * {@link #existingFreshReportSurvivesRetentionCleanup()} 专门覆盖。
     */
    private ConfigManager.ConfigData config() {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.general_language = "en";
        cfg.report_format = "html";
        cfg.report_retentionDays = 0;
        // HTML 卡片按 config 阈值选色，阈值留 0 会让所有数值都落进 CRITICAL；
        // 显式给一组常规值，让断言里的 class 名有明确含义。
        cfg.tps_warningThreshold = 18.0;
        cfg.tps_criticalThreshold = 15.0;
        cfg.mspt_warningThreshold = 45.0;
        cfg.mspt_criticalThreshold = 55.0;
        cfg.memory_heapWarningThreshold = 75;
        cfg.memory_heapCriticalThreshold = 90;
        return cfg;
    }

    // ==================== 夹具 ====================

    /**
     * 装配一个只依赖注入字段的 {@link PerfScope}（与 {@code PrometheusEndpointHttpTest#plugin} 同范式）。
     *
     * <p>{@code dataFolder} 必须给：构造器会往 {@code <dataFolder>/reports} 写，
     * {@code ReportMessages.ensureRegistered} → {@code Messages.load} 也会读它。</p>
     */
    private PerfScope plugin(Path dataFolder, ConfigManager.ConfigData cfg) throws Exception {
        PerfScope p = TestObjects.allocate(PerfScope.class);
        Logger log = Logger.getLogger("perfscope-report-test-" + System.identityHashCode(p));
        // 日志既不能刷进 surefire 输出，又必须能被本类断言到：
        // 关掉父 handler（根 logger 的控制台），再挂一个只收不发的生活在内存里的 handler。
        // 刻意不把 level 压成 OFF —— 那会在 publish 之前就过滤掉记录，logs 永远是空的（实测踩过）。
        log.setUseParentHandlers(false);
        log.setLevel(Level.ALL);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "logger", log);
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "description",
            new org.bukkit.plugin.PluginDescriptionFile("PerfScope", PLUGIN_VERSION, PerfScope.class.getName()));
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "dataFolder", dataFolder.toFile());

        ConfigManager cm = new ConfigManager(p);
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, p, "configManager", cm);
        return p;
    }

    /** 日志里是否出现过某条文案（按 {@code Messages} 渲染后的子串匹配）。 */
    private boolean logged(String needle) {
        synchronized (logs) {
            return logs.stream().anyMatch(r -> r.getMessage() != null && r.getMessage().contains(needle));
        }
    }

    /** 造一个采集器：只填 {@code history}（{@code getLatest()} = peekLast，{@code getHistory()} = 快照列表）。 */
    private static MetricsCollector collector(PerfScope p, MetricsSnapshot... snapshots) {
        MetricsCollector mc = TestObjects.allocate(MetricsCollector.class);
        Deque<MetricsSnapshot> history = new ConcurrentLinkedDeque<>();
        for (MetricsSnapshot s : snapshots) {
            if (s != null) {
                history.addLast(s);
            }
        }
        TestObjects.setField(MetricsCollector.class, mc, "history", history);
        TestObjects.setField(MetricsCollector.class, mc, "configManager", p.getConfigManager());
        TestObjects.setField(MetricsCollector.class, mc, "plugin", p);
        TestObjects.setField(PerfScope.class, p, "metricsCollector", mc);
        return mc;
    }

    /** 完整装配：目录 → 插件 → 采集器 → 真实构造 {@link ReportGenerator}。 */
    private ReportGenerator generator(Path dataFolder, ConfigManager.ConfigData cfg, MetricsSnapshot... snaps)
        throws Exception {
        PerfScope p = plugin(dataFolder, cfg);
        collector(p, snaps);
        ReportGenerator gen = new ReportGenerator(p);
        generators.add(gen);
        return gen;
    }

    private ReportGenerator generator(Path dataFolder, MetricsSnapshot... snaps) throws Exception {
        return generator(dataFolder, config(), snaps);
    }

    @AfterEach
    void shutDownGenerators() {
        for (ReportGenerator gen : generators) {
            try {
                gen.shutdown();
            } catch (RuntimeException ignored) {
                // 关闭路径不再制造新的失败
            }
        }
        generators.clear();
    }

    // ==================== 快照 ====================

    /**
     * 「管理员关掉了 TPS 与 MSPT」的服务器形态——HTML/TXT 主路径用它（见类注释二）。
     *
     * <p>刻意带上一个含 {@code < & >} 的世界名与一条 critical 告警：报告正文的转义
     * 与告警区因此能在同一条用例里被验证。</p>
     */
    private static MetricsSnapshot tpsDisabledSnapshot() {
        MetricsSnapshot s = new MetricsSnapshot();
        s.timestamp = 1_794_350_258_230L;
        s.unavailable.add(MetricsSnapshot.MODULE_TPS);
        s.unavailable.add(MetricsSnapshot.MODULE_MSPT);
        s.disabledModules.add(MetricsSnapshot.MODULE_TPS);
        s.disabledModules.add(MetricsSnapshot.MODULE_MSPT);

        s.tps1m = 0.0;
        s.tps5m = 0.0;
        s.tps15m = 0.0;
        s.msptAvg = -1;
        s.playerCount = 12;
        s.maxPlayers = 100;
        s.totalEntities = 1234;
        s.totalLoadedChunks = 567;
        s.totalForceLoadedChunks = 42;
        s.tileEntityCount = 89;
        s.heapUsed = 1_073_741_824L;
        s.heapTotal = 2_147_483_648L;
        s.heapMax = 4_294_967_296L;
        s.nonHeapUsed = 200_000_000L;
        s.heapUsagePercent = 25;
        s.processCpuLoad = 42.5;
        s.systemCpuLoad = 88.0;
        s.gcCount = 77;
        s.gcTime = 1500;
        s.uptimeSeconds = 3600;
        s.threadCount = 65;
        s.loadedPluginCount = 8;
        s.worldCount = 2;

        Map<String, Integer> entities = new LinkedHashMap<>();
        entities.put("world", 1000);
        entities.put("world_nether", 234);
        s.entitiesByWorld = entities;

        Map<String, Integer> chunks = new LinkedHashMap<>();
        chunks.put("world", 500);
        chunks.put("world_nether", 67);
        s.chunksByWorld = chunks;

        Map<String, Integer> tileEntities = new LinkedHashMap<>();
        tileEntities.put("world", 80);
        tileEntities.put("world_nether", 9);
        s.tileEntitiesByWorld = tileEntities;

        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("zombie", 700);
        types.put("cow", 534);
        s.entitiesByType = types;

        s.activeCriticals.add("TPS below critical threshold");
        return s;
    }

    /** 「全模块可用」的富快照——JSON 路径不碰 {@code ServerCompat}，可以完整走通。 */
    private static MetricsSnapshot richSnapshot(double tps1m) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.timestamp = 1_794_350_258_230L;
        s.tps1m = tps1m;
        s.tps5m = 20.0;
        s.tps15m = 19.95;
        s.msptAvg = 50.05;
        s.msptMin = 12.3;
        s.msptMax = 88.8;
        s.msptSource = MetricsSnapshot.MSPT_SOURCE_TICK_TIMES;
        s.playerCount = 12;
        s.maxPlayers = 100;
        s.totalEntities = 1234;
        s.totalLoadedChunks = 567;
        s.totalForceLoadedChunks = 42;
        s.tileEntityCount = 89;
        s.heapUsed = 1_073_741_824L;
        s.heapTotal = 2_147_483_648L;
        s.heapMax = 4_294_967_296L;
        s.nonHeapUsed = 200_000_000L;
        s.heapUsagePercent = 25;
        s.processCpuLoad = 42.5;
        s.systemCpuLoad = 88.0;
        s.gcCount = 77;
        s.gcTime = 1500;
        s.uptimeSeconds = 3600;
        s.threadCount = 65;
        s.loadedPluginCount = 8;
        s.worldCount = 2;

        Map<String, Integer> entities = new LinkedHashMap<>();
        entities.put("world", 1000);
        entities.put("world_nether", 234);
        s.entitiesByWorld = entities;

        Map<String, Integer> chunks = new LinkedHashMap<>();
        chunks.put("world", 500);
        chunks.put("world_nether", 67);
        s.chunksByWorld = chunks;

        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("zombie", 700);
        types.put("cow", 534);
        s.entitiesByType = types;

        s.activeWarnings.add("heap usage warning");
        return s;
    }

    // ==================== 断言辅助 ====================

    private static Path reportsDir(Path dataFolder) {
        return dataFolder.resolve("reports");
    }

    /** 目录里本插件产出的报告文件（按名字排序，结果稳定）。 */
    private static List<Path> reportFiles(Path dataFolder) throws IOException {
        Path dir = reportsDir(dataFolder);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                .filter(p -> REPORT_FILE.matcher(p.getFileName().toString()).matches())
                .sorted()
                .collect(Collectors.toList());
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 断言返回值是「指向 reports/ 下、扩展名正确、真的存在且非空」的绝对路径，并回读内容。 */
    private static String assertReport(Path dataFolder, String returnedPath, String extension) throws IOException {
        assertNotNull(returnedPath, "generateSpecific/generateAndSave must return the report path on success");
        Path path = Path.of(returnedPath);
        assertTrue(path.isAbsolute(), "the returned path must be absolute, got: " + returnedPath);
        assertEquals(reportsDir(dataFolder).toAbsolutePath().normalize(), path.getParent().toAbsolutePath().normalize(),
            "reports must land in <dataFolder>/reports, not somewhere else");
        String name = path.getFileName().toString();
        assertTrue(REPORT_FILE.matcher(name).matches(),
            "report name must match the plugin's own pattern, got: " + name);
        assertTrue(name.endsWith("." + extension),
            "requested format must select the file extension, got: " + name);
        assertTrue(Files.isRegularFile(path), "the returned path must exist: " + returnedPath);
        assertTrue(Files.size(path) > 0, "a successful report must not be empty: " + returnedPath);
        return read(path);
    }

    private static long fileCount(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.count();
        }
    }

    // ==================== A. 正常生成路径 ====================

    @Test
    void htmlReportIsWrittenIntoTempDataFolder(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());

        String path = gen.generateSpecific("html");
        String html = assertReport(temp, path, "html");

        assertTrue(html.startsWith("<!DOCTYPE html>"),
            "an HTML report must start with a doctype, got: " + html.substring(0, Math.min(40, html.length())));
        assertTrue(html.contains("<html lang=\"en\">"),
            "lang must follow the active language (en by config); a zh-CN document would be read aloud as Chinese");
        assertTrue(html.contains("PerfScope Performance Report"), "the report title must be present");
        assertTrue(html.contains("</html>"), "the document must be closed");

        // 夹具里的真实数字必须穿过渲染层，而不是被硬编码/丢弃
        assertTrue(html.contains("12 / 100"), "player count must be rendered: 12 / 100");
        assertTrue(html.contains("world_nether"), "per-world table must be rendered");
        assertTrue(html.contains("Plugins loaded: 8"), "worlds card subtitle must be rendered");
        assertTrue(html.contains("[!!!] TPS below critical threshold"), "critical alerts section must be rendered");
    }

    @Test
    void htmlReportEscapesWorldNamesAndAlertText(@TempDir Path temp) throws Exception {
        MetricsSnapshot snap = tpsDisabledSnapshot();
        snap.entitiesByWorld.clear();
        snap.entitiesByWorld.put("world<&>\"'", 7);
        snap.tileEntitiesByWorld.clear();
        snap.chunksByWorld.clear();
        snap.activeCriticals.clear();
        snap.activeCriticals.add("<script>alert(1)</script>");
        ReportGenerator gen = generator(temp, snap);

        String html = assertReport(temp, gen.generateSpecific("html"), "html");

        assertTrue(html.contains("world&lt;&amp;&gt;&quot;&#39;"),
            "world names must be escaped, otherwise a world name can break the table");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"),
            "alert text must be escaped, otherwise the report is an XSS carrier");
        assertFalse(html.contains("<script>alert(1)</script>"),
            "the raw alert text must never reach the output unescaped");
    }

    @Test
    void txtReportIsWrittenIntoTempDataFolder(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());

        String txt = assertReport(temp, gen.generateSpecific("txt"), "txt");

        assertTrue(txt.contains("PerfScope Server Performance Report"), "the TXT title must be present");
        assertTrue(txt.contains("TPS (1m/5m/15m): unavailable"),
            "a disabled TPS module must be rendered as unavailable, not as 0.00 / 0.00 / 0.00");
        assertTrue(txt.contains("Players online: 12 / 100"), "player line must carry the real numbers");
        assertTrue(txt.contains("world: 1000 entities | 500 chunks | 80 tile entities"),
            "per-world line must be rendered with all three columns");
        assertTrue(txt.contains("[!!!] TPS below critical threshold"), "critical alerts must be rendered");
        assertTrue(txt.contains("Generated by PerfScope v" + PLUGIN_VERSION),
            "the footer must carry the plugin version from PluginDescriptionFile");
        assertFalse(txt.contains("<!DOCTYPE html>"), "TXT must not accidentally contain the HTML document");
    }

    @Test
    void jsonReportIsValidJsonWithTheDocumentedSchema(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, richSnapshot(19.98));

        String path = gen.generateSpecific("json");
        String json = assertReport(temp, path, "json");

        @SuppressWarnings("unchecked")
        Map<String, Object> root = new Gson().fromJson(json, Map.class);
        assertNotNull(root, "the JSON report must parse");

        assertEquals(PLUGIN_VERSION, root.get("plugin_version"),
            "plugin_version is part of the external contract read by downstream scripts");
        assertNotNull(root.get("generated_at"));

        @SuppressWarnings("unchecked")
        Map<String, Object> tps = (Map<String, Object>) root.get("tps");
        assertEquals(19.98, ((Number) tps.get("1m")).doubleValue(), 1e-9);
        assertEquals(20.0, ((Number) tps.get("5m")).doubleValue(), 1e-9);

        @SuppressWarnings("unchecked")
        Map<String, Object> mspt = (Map<String, Object>) root.get("mspt");
        assertEquals(50.05, ((Number) mspt.get("avg")).doubleValue(), 1e-9);
        assertEquals("tick_times", mspt.get("source"), "the MSPT collection path must be exported");

        @SuppressWarnings("unchecked")
        Map<String, Object> players = (Map<String, Object>) root.get("players");
        assertEquals(12, ((Number) players.get("online")).intValue());
        assertEquals(100, ((Number) players.get("max")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> entities = (Map<String, Object>) root.get("entities");
        assertEquals(1234, ((Number) entities.get("total")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> alerts = (Map<String, Object>) root.get("alerts");
        assertEquals("ok", alerts.get("health"),
            "a snapshot with no unavailable module must be reported as healthy");
    }

    @Test
    void jsonReportCarriesTheWholeHistoryAsTpsTrend(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, richSnapshot(19.98), richSnapshot(18.5), richSnapshot(17.0));

        @SuppressWarnings("unchecked")
        Map<String, Object> root = new Gson().fromJson(assertReport(temp, gen.generateSpecific("json"), "json"), Map.class);
        @SuppressWarnings("unchecked")
        List<Object> history = (List<Object>) root.get("tps_history");

        assertEquals(3, history.size(), "every collected sample must appear in tps_history");
        assertEquals(17.0, ((Number) history.get(2)).doubleValue(), 1e-9, "the newest sample must be last");
    }

    @Test
    void generateAndSaveUsesTheConfiguredFormat(@TempDir Path temp) throws Exception {
        ConfigManager.ConfigData jsonCfg = config();
        jsonCfg.report_format = "json";
        ReportGenerator gen = generator(temp, jsonCfg, richSnapshot(20.0));

        assertReport(temp, gen.generateAndSave(), "json");
    }

    @Test
    void generateAndSaveFallsBackToTxtWhenFormatIsMissing(@TempDir Path temp) throws Exception {
        ConfigManager.ConfigData cfg = config();
        cfg.report_format = null;   // 配置尚未加载完时的形态
        ReportGenerator gen = generator(temp, cfg, tpsDisabledSnapshot());

        assertReport(temp, gen.generateAndSave(), "txt");
    }

    @Test
    void everySuccessfulReportIsAFreshFile(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());

        String first = gen.generateSpecific("txt");
        String second = gen.generateSpecific("txt");

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, reportFiles(temp).size(),
            "each generation must produce its own file (CREATE_NEW + rename retry), never overwrite the previous one");
        assertEquals(2, reportFiles(temp).stream().distinct().count());
    }

    // ==================== B. 格式归一化 ====================

    @Test
    void formatIsCaseInsensitive(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());

        assertTrue(assertReport(temp, gen.generateSpecific("HTML"), "html").startsWith("<!DOCTYPE html>"));
        assertTrue(assertReport(temp, gen.generateSpecific("TXT"), "txt")
            .contains("PerfScope Server Performance Report"));
        assertDoesNotThrow(() -> assertReport(temp, gen.generateSpecific("JSON"), "json"),
            "uppercase JSON must be accepted like lowercase");
    }

    @Test
    void unknownFormatFallsBackToTxt(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());

        String txt = assertReport(temp, gen.generateSpecific("nope"), "txt");

        assertTrue(txt.contains("PerfScope Server Performance Report"),
            "an unknown format must fall back to TXT (the documented default), not to HTML");
        assertFalse(txt.startsWith("<!DOCTYPE html>"));
    }

    @Test
    void blankFormatFallsBackToTxt(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());

        assertReport(temp, gen.generateSpecific(""), "txt");
        assertReport(temp, gen.generateSpecific("text"), "txt");
    }

    @Test
    void nullFormatFallsBackToTxtWithoutThrowing(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());

        assertReport(temp, gen.generateSpecific(null), "txt");
    }

    // ==================== C. shutdown() ====================

    /**
     * {@code submitAndAwait} 的第一条契约（代码注释明写）：已 {@code shutdown()} → 记 warning 并返回 null。
     * 关服阶段不该再产生新报告，也不该因为执行器已关闭而抛 {@link RejectedExecutionException} 给调用方。
     */
    @Test
    void afterShutdownNoReportIsProducedAndNoExceptionEscapes(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());
        gen.shutdown();

        assertNull(assertDoesNotThrow(() -> gen.generateSpecific("html"),
            "a closed generator must swallow the rejection, not throw RejectedExecutionException at the caller"));
        assertNull(assertDoesNotThrow(gen::generateAndSave));
        assertEquals(0, fileCount(reportsDir(temp)),
            "no file may appear after shutdown, and no leftover/half file either");
    }

    @Test
    void shutdownIsIdempotent(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());
        gen.shutdown();
        assertDoesNotThrow(gen::shutdown);
        assertNull(gen.generateSpecific("txt"));
    }

    // ==================== D. 中断处理 ====================

    /**
     * {@code submitAndAwait} 的第三条契约：等待被中断 → <b>恢复中断标志</b>并返回 null。
     *
     * <p>调用线程<b>刻意不叫</b> {@code perfscope-report*}：内联分支按线程名前缀判定，
     * 名字撞上就会走内联路径，这条用例便变成了在测别的东西（实测踩过：最初命名为
     * {@code perfscope-report-test-caller}，线程内联跑完后立刻 TERMINATED，断言在 10 秒后失败）。</p>
     *
     * <p>同步点分两段，都不用 sleep：</p>
     * <ol>
     *   <li>先往执行器投一个阻塞任务占住它（{@link CountDownLatch} 证明它真的开始跑了）；</li>
     *   <li>再轮询调用线程的 {@link Thread#getState()}，等到它进入 {@code TIMED_WAITING}——
     *       那正是 {@code future.get(30, SECONDS)} 上的等待态。此刻中断必然命中「等待中」这一段，
     *       而不会落到「还没进 get」或「已经返回」的时序里。</li>
     * </ol>
     */
    @Test
    void interruptWhileWaitingReturnsNullAndKeepsTheInterruptFlag(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());
        ExecutorService executor = (ExecutorService) TestObjects.getField(
            ReportGenerator.class, gen, "reportExecutor");

        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        executor.submit(() -> {
            blockingStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        assertTrue(blockingStarted.await(10, TimeUnit.SECONDS), "the blocker task never started");

        AtomicReference<String> result = new AtomicReference<>("unset");
        AtomicReference<Boolean> flagAfterReturn = new AtomicReference<>(null);
        Thread caller = new Thread(() -> {
            result.set(gen.generateSpecific("html"));
            flagAfterReturn.set(Thread.currentThread().isInterrupted());
        }, "report-interrupt-caller");
        caller.setDaemon(true);

        try {
            caller.start();
            // 可观测同步点：等到调用线程确实停在 future.get(timeout) 上
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (caller.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.TIMED_WAITING, caller.getState(),
                "the caller must block in future.get(timeout) while the executor is busy; state was "
                    + caller.getState());

            caller.interrupt();
            caller.join(10_000);

            assertFalse(caller.isAlive(), "an interrupted wait must return promptly, not sit out the 30s timeout");
            assertNull(result.get(),
                "an interrupted wait must report failure (null), never a path to a file that may not exist");
            assertEquals(Boolean.TRUE, flagAfterReturn.get(),
                "the interrupt flag must be restored before returning: swallowing it hides the shutdown signal "
                    + "from the caller's loop and the thread keeps running as if nothing happened");
            assertEquals(0, fileCount(reportsDir(temp)),
                "the queued task must not have run: only the blocker was submitted");
        } finally {
            releaseBlocker.countDown();
            caller.interrupt();
            caller.join(5_000);
            // 队列里那个 html 任务此刻才可能开始跑；先 shutdownNow 再等执行器静默，
            // 否则它会和 @TempDir 的清理抢同一个目录（实测：目录删除失败）。
            gen.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                "the report executor must terminate so it cannot keep writing into the temp folder");
        }
    }

    // ==================== E. 执行器线程内联 ====================

    /**
     * {@code submitAndAwait} 的第二条契约：已在 {@code perfscope-report} 线程内调用 → 内联执行，避免自等死锁。
     *
     * <p>同步点：执行器被阻塞任务占满。若内联分支被删掉，从同族线程名发起的调用会排到那个阻塞任务
     * 后面，{@code future.get(30s)} 一直等到超时——{@code join(5s)} 就会把它判死。
     * 反向也验证了：内联路径<b>不经过</b>队列，因此不受队首任务影响。</p>
     */
    @Test
    void reportRequestedFromTheExecutorThreadRunsInlineWithoutDeadlock(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());
        ExecutorService executor = (ExecutorService) TestObjects.getField(
            ReportGenerator.class, gen, "reportExecutor");

        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        executor.submit(() -> {
            blockingStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        assertTrue(blockingStarted.await(10, TimeUnit.SECONDS), "the blocker task never started");

        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread inline = new Thread(() -> {
            try {
                result.set(gen.generateSpecific("txt"));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "perfscope-report-inline-probe");
        inline.setDaemon(true);

        try {
            inline.start();
            inline.join(5_000);
            if (failure.get() != null) {
                fail("inline generation threw: " + failure.get());
            }
            assertFalse(inline.isAlive(),
                "a call from the executor thread must run inline; queuing behind the running task would deadlock "
                    + "until the 30s await timeout");
            assertNotNull(result.get(),
                "the inline path must return the report path (it bypasses the executor, so the blocker is irrelevant)");
            assertTrue(Files.isRegularFile(Path.of(result.get())),
                "the inline path must still write the file it returns");
        } finally {
            releaseBlocker.countDown();
            inline.join(5_000);
        }
    }

    // ==================== F. 文件系统 ====================

    @Test
    void constructorCreatesTheReportsDirectory(@TempDir Path temp) throws Exception {
        Path dataFolder = temp.resolve("data").resolve("PerfScope");
        assertFalse(Files.exists(reportsDir(dataFolder)), "precondition: the data folder does not exist yet");

        generator(dataFolder, tpsDisabledSnapshot());

        assertTrue(Files.isDirectory(reportsDir(dataFolder)),
            "the constructor must create <dataFolder>/reports (including missing parents)");
    }

    /**
     * B5（RT-02）自愈：运行中 reports/ 被外部删掉后，下一次生成必须重建目录而不是永久失败。
     * 去掉 {@code ensureReportDir()} 里的 {@code mkdirs} 时这条会红。
     */
    @Test
    void reportsDirectoryDeletedAtRuntimeIsRecreated(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());
        Files.delete(reportsDir(temp));
        assertFalse(Files.exists(reportsDir(temp)), "precondition: the directory is gone");

        String path = gen.generateSpecific("txt");

        assertNotNull(path, "generation must self-heal the missing directory instead of failing forever");
        assertTrue(Files.isRegularFile(Path.of(path)));
        assertTrue(Files.isDirectory(reportsDir(temp)), "the directory must be back after a successful write");
    }

    /**
     * 目录位置被一个普通文件占住 → 无法建目录也不再尝试写。契约是返回 null、不抛、不留文件。
     */
    @Test
    void unwritableReportsPathReturnsNullWithoutThrowing(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp);
        Path squattingFile = reportsDir(temp);
        Files.writeString(squattingFile, "not a directory", StandardCharsets.UTF_8);

        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());

        assertNull(assertDoesNotThrow(() -> gen.generateSpecific("html"),
            "an unusable report directory is an operating condition; generateSpecific must not throw"));
        assertEquals("not a directory", read(squattingFile),
            "the foreign file that blocks the directory must be left untouched");
    }

    // ==================== 其它边界 ====================

    /**
     * 采集器还没有任何快照时，报告<b>根本不该尝试渲染</b>：{@code generateSpecificOnExecutor} 首行就该返回 null。
     *
     * <p>只看返回值不够——渲染中途抛异常（例如 mock 出一个全默认快照后撞上
     * {@code ServerCompat} 的初始化失败）也被 {@code catch (Throwable)} 兜成 null，两者不可区分。
     * 所以这里额外断言日志里<b>没有</b> {@code report.log.generate-failed}：
     * 那条 warning 只在渲染真的开跑并失败时出现。</p>
     */
    @Test
    void noSnapshotYetReturnsNullAndWritesNothing(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp);   // 采集器里一条快照都没有

        assertNull(gen.generateSpecific("html"), "without a snapshot there is nothing to report");
        assertNull(gen.generateAndSave());
        assertEquals(0, fileCount(reportsDir(temp)), "no file may be written when there is no snapshot");
        assertFalse(logged("Failed to generate"),
            "the render path must not even be entered without a snapshot: a \"generate-failed\" warning here "
                + "means the code tried to render a placeholder snapshot instead of bailing out");
    }

    /**
     * 清理路径与生成路径的相互作用：保留期 7 天时，刚写出的报告绝不能被自己的清理逻辑删掉。
     */
    @Test
    void existingFreshReportSurvivesRetentionCleanup(@TempDir Path temp) throws Exception {
        ConfigManager.ConfigData cfg = config();
        cfg.report_retentionDays = 7;
        ReportGenerator gen = generator(temp, cfg, tpsDisabledSnapshot());

        String path = gen.generateSpecific("txt");

        assertNotNull(path);
        assertTrue(Files.isRegularFile(Path.of(path)),
            "cleanOldReports() must never delete the report that was just written");
    }

    /**
     * 「生成 + 写盘」是串行的，且文件名碰撞会换名重试：两个线程同时生成 → 两份各自完整、互不覆盖的文件。
     */
    @Test
    void concurrentGenerationsProduceTwoDistinctIntactReports(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, tpsDisabledSnapshot());

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread a = new Thread(() -> {
            try {
                start.await();
                first.set(gen.generateSpecific("txt"));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "report-concurrent-a");
        Thread b = new Thread(() -> {
            try {
                start.await();
                second.set(gen.generateSpecific("txt"));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "report-concurrent-b");
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        start.countDown();
        a.join(20_000);
        b.join(20_000);

        if (failure.get() != null) {
            fail("concurrent generation threw: " + failure.get());
        }
        assertFalse(a.isAlive() || b.isAlive(), "both concurrent calls must complete");
        assertNotNull(first.get());
        assertNotNull(second.get());
        assertFalse(first.get().equals(second.get()),
            "two concurrent generations must not resolve to the same file: the later writer would truncate the earlier one");
        assertEquals(2, reportFiles(temp).size());
        for (Path p : reportFiles(temp)) {
            assertTrue(Files.size(p) > 0, "each of the two reports must be complete: " + p);
            assertTrue(read(p).contains("PerfScope Server Performance Report"),
                "neither report may be a truncated half: " + p);
        }
    }

    /**
     * 「TPS 可用」的报告路径依赖 {@link ServerCompat} 的静态初始化，而它在没有真实 Bukkit 的
     * JVM 里必然失败（{@code Bukkit.server} 为 null）。本用例按当前 JVM 的真实能力断言，
     * 两种结果都必须是契约内的行为：
     *
     * <ul>
     *   <li>能初始化 → 报告正常生成，TPS 卡片渲染真实数字；</li>
     *   <li>不能初始化 → 返回 null、不抛、不写文件（{@code generateSpecificOnExecutor} 的
     *       {@code catch (Throwable)} 兜住了 {@code LinkageError}）。</li>
     * </ul>
     *
     * <p>这是本仓库的一个真实局限，不是测试的妥协：<b>只要快照里 TPS 可用，报告生成就绑死在
     * {@code ServerCompat} 上</b>，无服务端的 JVM 里没有 MockBukkit 就产不出 HTML/TXT。</p>
     */
    @Test
    void tpsAvailablePathDependsOnServerCompatInitialization(@TempDir Path temp) throws Exception {
        ReportGenerator gen = generator(temp, richSnapshot(19.98));

        String path = assertDoesNotThrow(() -> gen.generateSpecific("html"),
            "even a LinkageError in the render path must be contained: the caller only sees null");

        if (serverCompatUsable) {
            String html = assertReport(temp, path, "html");
            assertTrue(html.contains("19.98"), "the real TPS value must reach the report");
        } else {
            assertNull(path, "with ServerCompat unavailable the TPS path cannot render; it must fail closed");
            assertEquals(0, fileCount(reportsDir(temp)),
                "a failed render must not leave a partial file behind");
        }
    }
}
