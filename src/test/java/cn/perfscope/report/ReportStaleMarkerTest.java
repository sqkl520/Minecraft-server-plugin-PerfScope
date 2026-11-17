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
import cn.perfscope.support.ConfigFixtures;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告层的 <b>数据新鲜度标记</b>：三种格式都必须能回答"这些数字是哪一刻的"。
 *
 * <h2>为什么这个测试必须存在</h2>
 * {@code MetricsCollector#isStale()} 的注释里写着"报告层 health 段应增加 stale 标记"——
 * 这一条长期是欠账（实测 {@code grep -rc stale src/main/java/cn/perfscope/report/} = 0）。
 * 命令层（{@code /perfscope status}、ActionBar）与 Prometheus 端点都有，唯独报告没有。
 *
 * <p>危害不是"少一行提示"，而是<b>把冻住的数据当成当期值</b>：
 * {@code report.auto_export_interval: 30} 的自动导出在采集任务被取消/卡死后仍然照常落盘，
 * 得到一份标题写着"生成于 当前时刻"、卡片全绿、数字全冻结的 HTML——与"服务器很健康"
 * 在纸面上逐字节不可区分。而报告正是事后复盘时唯一还在的证据。</p>
 *
 * <h2>两个方向都要测</h2>
 * 只测"stale 时出现"会漏掉另一半：若实现写成恒 true（或把分支写反），
 * 每份正常报告都会带一条红字，服主学会无视它，真出事时那条也被无视。
 * 所以 {@link #freshCollectionLeavesNoStaleMarkerAnywhere()} 与
 * {@link #freshCollectionReportsStaleFalseInJson()} 同样是核心用例，不是配角。
 *
 * <h2>为什么能测（造壳 + 注入）</h2>
 * {@link ReportGenerator} 的构造器接收 {@link PerfScope}，用
 * {@link TestObjects#allocate} 造壳再注入五个字段即可走<b>真实构造器</b>。
 * {@link MetricsCollector} 的构造器会触发 {@code ServerCompat} 静态初始化（要 Bukkit），
 * 所以同样造壳，只注入新鲜度判定真正读到的四个字段：
 * {@code lastCollectAtMs} / {@code startedAtMs} / {@code configManager} / {@code history}。
 * 判定逻辑本身（阈值、宽限期、从未采集时的基准）已被 {@code StalenessQueryTest} 覆盖，
 * 本类不重复，只验证"报告有没有把那个判定结果如实印出来"。
 *
 * <p>TPS/MSPT 模块在夹具里是<b>禁用</b>的：HTML/TXT 一旦渲染可用的 TPS 就会调
 * {@link ServerCompat#tpsScopeLabel()}，而无 Bukkit 的 JVM 里它的静态初始化必然失败
 * （见 {@code ReportGeneratorTest} 类注释二）。这不是为测试编造的形态——服主关掉模块
 * 是 {@code MetricsSnapshot} 契约里正大光明的一支，报告正文照常渲染。</p>
 */
class ReportStaleMarkerTest {

    private static final String PLUGIN_VERSION = "1.4.4";

    /** 报告头「生成时间 / 数据采集于」用的格式（与生产 {@code DISPLAY_TS} 同形）。 */
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 横幅里「最后采集 HH:mm:ss」用的格式（与 {@code MetricsCollector#formatClock} 同口径）。 */
    private static final DateTimeFormatter CLOCK_TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 夹具快照的采样时刻：一个固定的过去时刻，与"现在"相差极远，一眼能分辨。 */
    private static final long SAMPLE_AT_MS = 1_794_350_258_230L;

    /** 「采集已停摆」的时长：10 分钟。远超 3 秒下界与任何常规采集间隔的 3 倍。 */
    private static final long STALE_FOR_MS = 10 * 60 * 1000L;

    private final List<ReportGenerator> generators = new ArrayList<>();

    /** 本 JVM 里 {@link ServerCompat} 能否完成静态初始化（TPS 可用路径的唯一变量）。 */
    private static boolean serverCompatUsable;

    @BeforeAll
    static void probeServerCompat() {
        TestObjects.assertReflectionUsable();
        try {
            ServerCompat.getServerName();
            serverCompatUsable = true;
        } catch (Throwable t) {
            serverCompatUsable = false;
        }
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

    // ==================== 夹具 ====================

    private static ConfigManager.ConfigData config() {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.general_language = "en";
        cfg.report_format = "html";
        cfg.report_retentionDays = 0;   // 让 cleanOldReports() 首行早退，别干扰生成断言
        // HTML 卡片按 config 阈值选色；阈值留 0 会让所有数值落进 CRITICAL，噪音掩盖真断言。
        cfg.tps_warningThreshold = 18.0;
        cfg.tps_criticalThreshold = 15.0;
        cfg.mspt_warningThreshold = 45.0;
        cfg.mspt_criticalThreshold = 55.0;
        cfg.memory_heapWarningThreshold = 75;
        cfg.memory_heapCriticalThreshold = 90;
        cfg.players_warningThreshold = 90;
        cfg.players_criticalThreshold = 98;
        cfg.entities_totalWarningThreshold = 20000;
        cfg.entities_totalCriticalThreshold = 30000;
        cfg.chunks_loadedWarningThreshold = 10000;
        cfg.chunks_loadedCriticalThreshold = 15000;
        cfg.cpu_processWarningThreshold = 80.0;
        cfg.cpu_systemWarningThreshold = 90.0;
        cfg.cpu_hysteresisMargin = 5.0;
        return cfg;
    }

    /** 装配一个只依赖注入字段的 {@link PerfScope}（与 {@code ReportGeneratorTest#plugin} 同范式）。 */
    private PerfScope plugin(Path dataFolder, ConfigManager.ConfigData cfg) throws Exception {
        PerfScope p = TestObjects.allocate(PerfScope.class);
        Logger log = Logger.getLogger("perfscope-stale-marker-test-" + System.identityHashCode(p));
        log.setUseParentHandlers(false);   // 不刷进 surefire 输出
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "logger", log);
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "description",
            new org.bukkit.plugin.PluginDescriptionFile("PerfScope", PLUGIN_VERSION, PerfScope.class.getName()));
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "dataFolder", dataFolder.toFile());
        ConfigManager cm = new ConfigManager(p);
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, p, "configManager", cm);
        return p;
    }

    /**
     * 造一个新鲜度可控的采集器：只填 {@code isStale()} / {@code staleForMs()} /
     * {@code lastCollectAtMs()} 真正读到的字段，以及 {@code getLatest()} / {@code getHistory()} 的容器。
     *
     * @param lastCollectAtMs 最后一次成功采集的时刻；{@code 0} = 启动后从未成功过
     * @param startedAtMs     启动（或最近一次 reload）时刻——从未采集过时的判定基准
     */
    private static MetricsCollector collector(PerfScope p, long lastCollectAtMs, long startedAtMs,
                                              MetricsSnapshot... snapshots) {
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
        TestObjects.setField(MetricsCollector.class, mc, "lastCollectAtMs", lastCollectAtMs);
        TestObjects.setField(MetricsCollector.class, mc, "startedAtMs", startedAtMs);
        TestObjects.setField(PerfScope.class, p, "metricsCollector", mc);
        return mc;
    }

    /**
     * 完整装配：目录 → 插件 → 采集器 → 真实构造 {@link ReportGenerator}。
     *
     * <p>刻意走真实构造器：本类要验的就是"报告层有没有把 collector 的判定如实印出来"，
     * 把构造器也造壳会连 {@code ReportMessages.ensureRegistered} 与 {@code ThresholdColors}
     * 的真实接线一起绕过去。</p>
     */
    private ReportGenerator generator(Path dataFolder, long lastCollectAtMs, long startedAtMs,
                                      MetricsSnapshot... snaps) throws Exception {
        PerfScope p = plugin(dataFolder, config());
        collector(p, lastCollectAtMs, startedAtMs, snaps);
        ReportGenerator gen = new ReportGenerator(p);
        generators.add(gen);
        return gen;
    }

    // ==================== 快照夹具 ====================

    /**
     * 「管理员关掉了 TPS 与 MSPT」的服务器形态——HTML/TXT 主路径用它（理由见类注释）。
     *
     * @param sampleAt 快照的采样时刻；{@code 0} 用来验"从未采集"
     */
    private static MetricsSnapshot tpsDisabledSnapshot(long sampleAt) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.timestamp = sampleAt;
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

    /** 「全模块可用」的富快照——JSON 路径完全不碰 {@link ServerCompat}，可以完整走通。 */
    private static MetricsSnapshot richSnapshot(long sampleAt) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.timestamp = sampleAt;
        s.tps1m = 19.98;
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

    private static String read(Path dataFolder, String returnedPath) throws IOException {
        assertNotNull(returnedPath, "expected a successful report, got a null path");
        Path path = Path.of(returnedPath);
        assertTrue(Files.isRegularFile(path), "the returned path must exist: " + returnedPath);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String display(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(DISPLAY_TS);
    }

    private static String clock(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(CLOCK_TS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text) {
        Map<String, Object> root = new Gson().fromJson(text, Map.class);
        assertNotNull(root, "the JSON report must parse");
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Object section = root.get(key);
        assertTrue(section instanceof Map, "'" + key + "' must be a JSON object, got: " + section);
        return (Map<String, Object>) section;
    }

    // ==================== 1. stale 时：三种格式都必须说出来 ====================

    /**
     * 采集停摆 → HTML 头部出现红底横幅，且横幅里带着<b>最后采集的时刻</b>。
     *
     * <p>只断言"含 stale 一词"不够：读者需要的是"最后一刻是哪一刻"，那才是判断
     * "这份数据有多旧"的依据。同时断言"不代表当前状态"这层意思在场，
     * 否则横幅会被读成"有点旧"而不是"这些数字不能用来下结论"。</p>
     */
    @Test
    void staleCollectionPutsAWarningBannerInHtml(@TempDir Path temp) throws Exception {
        long now = System.currentTimeMillis();
        long lastCollect = now - STALE_FOR_MS;
        ReportGenerator gen = generator(temp, lastCollect, now, tpsDisabledSnapshot(SAMPLE_AT_MS));

        String html = read(temp, gen.generateSpecific("html"));

        assertTrue(html.contains("class=\"stale-banner\""),
            "a stale collection must produce the warning banner element; "
                + "without it the report reads as 'generated just now, all green'");
        assertTrue(html.contains("Data is stale"),
            "the banner must say the data is stale, in words a reader understands");
        assertTrue(html.contains(clock(lastCollect)),
            "the banner must name the last successful collection time (" + clock(lastCollect)
                + "), not just say 'stale' - that clock is what tells the reader how old the figures are");
        assertTrue(html.contains("not the current server state"),
            "the banner must say the figures cannot be used to judge the current state; "
                + "otherwise it reads as 'slightly old' instead of 'do not conclude anything'");
    }

    /** 采集停摆 → TXT 里出现 {@code [WARNING]} 行（纯文本没有颜色，只能靠前缀拔出来）。 */
    @Test
    void staleCollectionPutsAWarningLineInTxt(@TempDir Path temp) throws Exception {
        long now = System.currentTimeMillis();
        long lastCollect = now - STALE_FOR_MS;
        ReportGenerator gen = generator(temp, lastCollect, now, tpsDisabledSnapshot(SAMPLE_AT_MS));

        String txt = read(temp, gen.generateSpecific("txt"));

        assertTrue(txt.contains("[WARNING] Data is stale"),
            "TXT has no colour, so the stale line must be marked with a [WARNING] prefix; "
                + "an unprefixed sentence blends into the body and gets skipped");
        assertTrue(txt.contains(clock(lastCollect)),
            "the TXT warning must carry the last collection clock (" + clock(lastCollect) + ")");
    }

    /**
     * 采集停摆 → JSON 的 {@code stale} 段给出 {@code isStale: true} 与那两次时刻。
     *
     * <p>这是三个格式里唯一给机器读的一份：巡检脚本据 {@code isStale} 就能把
     * "数据冻住了"与"服务器健康"分开，不必去猜 {@code generated_at} 的含义。</p>
     */
    @Test
    void staleCollectionReportsStaleTrueInJson(@TempDir Path temp) throws Exception {
        long now = System.currentTimeMillis();
        long lastCollect = now - STALE_FOR_MS;
        ReportGenerator gen = generator(temp, lastCollect, now, richSnapshot(SAMPLE_AT_MS));

        Map<String, Object> stale = section(json(read(temp, gen.generateSpecific("json"))), "stale");

        assertEquals(Boolean.TRUE, stale.get("isStale"),
            "a stopped collector must be reported as isStale=true; a downstream script that reads "
                + "only generated_at cannot tell frozen numbers from healthy ones");
        assertTrue(((Number) stale.get("staleForMs")).longValue() >= STALE_FOR_MS,
            "staleForMs must reflect how long the collection has been silent, got: " + stale.get("staleForMs"));
        assertEquals(display(lastCollect), stale.get("lastCollectAt"),
            "lastCollectAt must carry the last successful collection moment");
        assertEquals(display(SAMPLE_AT_MS), stale.get("sampleAt"),
            "sampleAt must be the snapshot's own timestamp - the moment the figures were measured");
    }

    // ==================== 2. 不 stale 时：一个标记都不许出现 ====================

    /**
     * 采集正常 → HTML / TXT / JSON 三处都<b>不含</b>任何 stale 标记。
     *
     * <p>这个方向与"stale 时出现"同等重要。若实现写成恒 true 或分支写反，每份正常报告
     * 都会带一条红字；服主很快学会无视它，真出事时那条也被无视——这正是本仓库反复
     * 记录过的"告警疲劳"失效模式。</p>
     */
    @Test
    void freshCollectionLeavesNoStaleMarkerAnywhere(@TempDir Path temp) throws Exception {
        long now = System.currentTimeMillis();
        ReportGenerator htmlGen = generator(temp, now, now, tpsDisabledSnapshot(SAMPLE_AT_MS));
        String html = read(temp, htmlGen.generateSpecific("html"));

        assertFalse(html.contains("class=\"stale-banner\""),
            "a fresh collection must not render the stale banner - a permanent red line gets ignored, "
                + "and then the real one gets ignored too");
        assertFalse(html.contains("Data is stale"),
            "a fresh collection must not claim the data is stale");

        ReportGenerator txtGen = generator(temp, now, now, tpsDisabledSnapshot(SAMPLE_AT_MS));
        String txt = read(temp, txtGen.generateSpecific("txt"));

        assertFalse(txt.contains("Data is stale"),
            "a fresh collection must not put a [WARNING] line in the TXT report");
    }

    /** 采集正常 → JSON 的 {@code isStale} 必须是 {@code false}（不是缺席、不是 null）。 */
    @Test
    void freshCollectionReportsStaleFalseInJson(@TempDir Path temp) throws Exception {
        long now = System.currentTimeMillis();
        ReportGenerator gen = generator(temp, now, now, richSnapshot(SAMPLE_AT_MS));

        Map<String, Object> stale = section(json(read(temp, gen.generateSpecific("json"))), "stale");

        assertEquals(Boolean.FALSE, stale.get("isStale"),
            "isStale must be an explicit false, not an absent key and not null: "
                + "a script doing `if (obj.stale.isStale)` must not see 'undefined' on a healthy server");
        assertEquals(display(now), stale.get("lastCollectAt"));
        assertEquals(display(SAMPLE_AT_MS), stale.get("sampleAt"),
            "sampleAt stays the snapshot's own timestamp even when fresh");
    }

    // ==================== 3. 报告头同时给出两个时刻 ====================

    /**
     * 报告头必须<b>同时</b>印出"生成于"与"采集于"，且两者可以不同。
     *
     * <p>这条挡的是本任务要修的那个原始 bug：旧实现只印 {@code LocalDateTime.now()}，
     * 于是"生成于 当前时刻"被读成"数据是当前的"。夹具里采样时刻是一个固定的过去时刻，
     * 若实现仍在用 {@code now()} 冒充采样时刻，断言 {@code contains(display(SAMPLE_AT_MS))} 会红。</p>
     */
    @Test
    void headerShowsBothGeneratedAtAndSampledAt(@TempDir Path temp) throws Exception {
        long now = System.currentTimeMillis();
        ReportGenerator gen = generator(temp, now, now, tpsDisabledSnapshot(SAMPLE_AT_MS));

        String html = read(temp, gen.generateSpecific("html"));
        assertTrue(html.contains("Generated at:"), "the report must state when the file was produced");
        assertTrue(html.contains("Data sampled at:"), "the report must state when the data was measured");
        assertTrue(html.contains(display(SAMPLE_AT_MS)),
            "the sampled-at line must carry the snapshot's own timestamp (" + display(SAMPLE_AT_MS)
                + "); if it shows now() instead, 'generated at' and 'sampled at' collapse into one fact "
                + "and the reader cannot tell how old the figures are");

        ReportGenerator txtGen = generator(temp, now, now, tpsDisabledSnapshot(SAMPLE_AT_MS));
        String txt = read(temp, txtGen.generateSpecific("txt"));
        assertTrue(txt.contains("Generated at:"), "TXT must state the generation time");
        assertTrue(txt.contains("Data sampled at:"), "TXT must state the sampling time");
        assertTrue(txt.contains(display(SAMPLE_AT_MS)), "TXT sampled-at line must carry the snapshot timestamp");
    }

    // ==================== 4. 从未采集：不伪造 1970 ====================

    /**
     * 快照 {@code timestamp == 0}（从未采集）→ 渲染"从未采集"，<b>不是</b> 1970 年。
     *
     * <p>{@code 0} 被格式化出来是 {@code 1970-01-01 08:00:00}（东八区）。那个年份是一个
     * <b>看起来像真实读数</b>的值——读者会以为"这份数据是 1970 年的"而不是"根本没有数据"。
     * 本仓库的 {@code /status} 早就为同一件事做了 {@code clockOf(0)} 的"从未"分支，
     * 报告层此前没有。</p>
     */
    @Test
    void zeroSnapshotTimestampRendersNeverCollectedNotNineteenSeventy(@TempDir Path temp) throws Exception {
        long now = System.currentTimeMillis();
        // lastCollectAtMs = 0 且刚刚启动 → isStale() 为 false（宽限期内），不会出横幅
        ReportGenerator gen = generator(temp, 0L, now, tpsDisabledSnapshot(0L));

        String html = read(temp, gen.generateSpecific("html"));
        assertTrue(html.contains("never collected"),
            "a snapshot with timestamp 0 must render as 'never collected'");
        assertFalse(html.contains("1970"),
            "timestamp 0 must never be formatted as a date - 1970 looks like a real reading "
                + "while the truth is 'no data at all'");

        ReportGenerator txtGen = generator(temp, 0L, now, tpsDisabledSnapshot(0L));
        String txt = read(temp, txtGen.generateSpecific("txt"));
        assertTrue(txt.contains("never collected"), "TXT must also render 'never collected'");
        assertFalse(txt.contains("1970"), "TXT must not fabricate a 1970 timestamp");
    }

    /** 快照 timestamp 为 0 时，JSON 的 {@code sampleAt} 是显式 null，不是空串、不是 0。 */
    @Test
    void zeroSnapshotTimestampBecomesNullInJson(@TempDir Path temp) throws Exception {
        long now = System.currentTimeMillis();
        ReportGenerator gen = generator(temp, 0L, now, richSnapshot(0L));

        Map<String, Object> stale = section(json(read(temp, gen.generateSpecific("json"))), "stale");

        assertTrue(stale.containsKey("sampleAt"), "the key must exist even when there is no sample");
        // Gson 的 serializeNulls：JSON 文本里是显式 null；解析成 Map 后该键存在、值为 null
        assertEquals(null, stale.get("sampleAt"),
            "sampleAt must be null when nothing was ever collected; an empty string or 0 "
                + "would be parsed as 1970 by lenient date parsers, which is a different fact");
    }

    /**
     * 启动后<b>从未成功采集过</b>、且已超出宽限期 → stale 横幅说"从未采集"，不伪造时刻。
     *
     * <p>这是 {@code isStale()} 的另一个分支（{@code lastCollectAtMs == 0} 且超过
     * {@code max(WARMUP, threshold)}）。首轮采集就死掉是最难发现的形态：没有任何一次
     * 成功的采集可作参照，唯一线索就是"从头到尾没有数据"。若横幅此时把
     * {@code lastCollectAtMs == 0} 格式化出来，它会写"最后采集 08:00:00"——
     * 一个今天早上根本没发生过的采集。</p>
     */
    @Test
    void staleBannerSaysNeverCollectedWhenNothingWasEverCollected(@TempDir Path temp) throws Exception {
        long now = System.currentTimeMillis();
        long startedLongAgo = now - 10 * 60 * 1000L;   // 远超 10 秒宽限期
        ReportGenerator gen = generator(temp, 0L, startedLongAgo, tpsDisabledSnapshot(0L));

        String html = read(temp, gen.generateSpecific("html"));

        assertTrue(html.contains("class=\"stale-banner\""),
            "a collector that never succeeded past the warmup window must be reported as stale");
        assertTrue(html.contains("never collected"),
            "the banner must say 'never collected' instead of formatting the 0 sentinel into a clock");
        assertFalse(html.contains("last collection 08:00:00"),
            "formatting lastCollectAtMs == 0 would invent a collection that never happened");
    }

    // ==================== 5. JSON 契约：只许增量加键 ====================

    /**
     * <b>契约保护</b>：JSON 既有键名一个都不许改。
     *
     * <p>{@code ReportGenerator} 的类注释明写"字段名与字面值是与下游脚本约定的外部契约"。
     * 本次改动只允许<b>新增</b> {@code stale} 一个顶层键。这条用例把所有既有顶层键逐个点名，
     * 并断言 {@code alerts} 段下的三个既有键仍在——键名被重命名或删掉时，先红的是这里，
     * 而不是某个用户半年后才发现 Grafana 看板空了。</p>
     */
    @Test
    void existingJsonKeysSurviveTheStaleAddition(@TempDir Path temp) throws Exception {
        long now = System.currentTimeMillis();
        ReportGenerator gen = generator(temp, now, now, richSnapshot(SAMPLE_AT_MS));

        Map<String, Object> root = json(read(temp, gen.generateSpecific("json")));

        Set<String> required = Set.of(
            "generated_at", "plugin_version",
            "tps", "mspt", "players", "entities", "tile_entities", "chunks",
            "memory", "cpu", "gc", "server", "alerts", "tps_history");
        for (String key : required) {
            assertTrue(root.containsKey(key),
                "existing top-level key '" + key + "' disappeared - the JSON field names are an "
                    + "external contract for inspection scripts, downstream may only gain keys");
        }

        assertEquals(PLUGIN_VERSION, root.get("plugin_version"),
            "plugin_version must keep its meaning and type");

        Map<String, Object> alerts = section(root, "alerts");
        for (String key : List.of("critical", "warnings", "health")) {
            assertTrue(alerts.containsKey(key),
                "alerts." + key + " is a documented contract key and must not be renamed or dropped");
        }
        assertEquals("ok", alerts.get("health"),
            "health keeps its three-valued semantics (ok/degraded/partial) - the stale marker is a "
                + "separate top-level section, it must not be folded into health and change its meaning");

        // 新增的键只有一个，且是对象
        @SuppressWarnings("unchecked")
        Map<String, Object> stale = (Map<String, Object>) root.get("stale");
        assertNotNull(stale, "the stale section is the only addition");
        assertEquals(Set.of("isStale", "staleForMs", "lastCollectAt", "sampleAt"), stale.keySet(),
            "the stale section's own field set is a new contract; keep it exactly these four");
    }
}
