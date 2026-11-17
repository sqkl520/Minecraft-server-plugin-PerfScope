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
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.TestObjects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告概览卡片的<b>级别着色</b>测试。
 *
 * <h2>为什么需要这个类</h2>
 * <p>{@code htmlCard(title, value, cssClass, subtitle)} 把第三参数原样拼进
 * {@code class="card <cssClass>"}，而 CSS 里只定义了 {@code .card.good} /
 * {@code .card.warn} / {@code .card.critical} / {@code .card.unknown} 四条修饰规则。
 * 当调用方传的是 {@code "normal"} 时，那个 class 在样式表里<b>不存在</b>，
 * 卡片落回 {@code .card} 基础规则 → 边条与数值恒为青色。</p>
 *
 * <p>后果不是"颜色不好看"，而是<b>永久宣称健康</b>：玩家满载 100%、实体 40000 的服务器，
 * 报告卡片与"一切正常"的服务器长得一模一样。这条缺陷能活到 v1.4.4，
 * 直接原因是 {@code ReportGenerator} 在变异表里<b>一条变异都没有</b>（62 条里 0 条），
 * 而现有 {@code ReportGeneratorTest} 只断言文案与数字，从不断言 class 名。</p>
 *
 * <h2>测试怎么拿到私有方法的结果</h2>
 * <p>{@code htmlCard} / {@code loadHtmlCss} 都是私有实例方法，本类<b>不反射调用它们</b>，
 * 而是走真实主路径 {@code generateSpecific("html")} 落盘再回读。理由：反射单个私有方法
 * 只能证明"该方法返回了某个字符串"，证明不了"它被拼进了报告"——而后者才是缺陷所在。
 * 夹具（插件装配 / 采集器 / 阈值注入）照抄 {@link ReportGeneratorTest} 的既有范式。</p>
 *
 * <h2>为什么用「TPS 与 MSPT 都不可用」的快照</h2>
 * <p>与 {@link ReportGeneratorTest} 同因：{@code ReportGenerator} 在 TPS 可用时会调用
 * {@code ServerCompat.tpsScopeLabel()}，而 {@code ServerCompat} 的静态块依赖
 * {@code Bukkit.getBukkitVersion()}，单测 JVM 里初始化即失败且不可重试。
 * 关掉这两个模块是 {@link MetricsSnapshot} 契约里正大光明的一支，概览卡片的八张卡照常渲染。</p>
 */
class ReportCardLevelTest {

    /** 页脚与 JSON 的 plugin_version 都取自它。 */
    private static final String PLUGIN_VERSION = "1.4.4";

    /** 一条 HTML 卡片里 {@code class="card ..."} 的修饰类。 */
    private static final String CARD_OPEN = "<div class=\"card ";

    /** 卡片修饰类允许出现的全集——与 {@code ThresholdColors.cssClass(Level)} 的返回值一致。 */
    private static final List<String> ALLOWED_CARD_CLASSES =
        List.of("good", "warn", "critical", "unknown", "normal");

    private final List<ReportGenerator> generators = new ArrayList<>();

    @BeforeAll
    static void reflectionUsable() {
        TestObjects.assertReflectionUsable();
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

    /**
     * 阈值显式给全：{@link ConfigManager.ConfigData} 的 int 字段默认是 0，
     * 留 0 会让"实体 >= 0 即 critical"恒真，断言里的 class 名就没有意义了。
     *
     * <p>{@code general_language = "en"} 让卡片标题是英文，便于按标题定位卡片；
     * {@code report_retentionDays = 0} 让清理首行早退，不干扰生成断言。</p>
     */
    private static ConfigManager.ConfigData config() {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.general_language = "en";
        cfg.report_format = "html";
        cfg.report_retentionDays = 0;
        cfg.tps_warningThreshold = 18.0;
        cfg.tps_criticalThreshold = 15.0;
        cfg.mspt_warningThreshold = 45.0;
        cfg.mspt_criticalThreshold = 55.0;
        cfg.memory_heapWarningThreshold = 75;
        cfg.memory_heapCriticalThreshold = 90;
        cfg.players_warningThreshold = 90;
        cfg.players_criticalThreshold = 98;
        cfg.entities_totalWarningThreshold = 20_000;
        cfg.entities_totalCriticalThreshold = 30_000;
        cfg.chunks_loadedWarningThreshold = 10_000;
        cfg.chunks_loadedCriticalThreshold = 15_000;
        cfg.cpu_processWarningThreshold = 80.0;
        cfg.cpu_systemWarningThreshold = 80.0;
        return cfg;
    }

    private PerfScope plugin(Path dataFolder, ConfigManager.ConfigData cfg) throws Exception {
        PerfScope p = TestObjects.allocate(PerfScope.class);
        Logger log = Logger.getLogger("perfscope-card-test-" + System.identityHashCode(p));
        log.setUseParentHandlers(false);
        log.setLevel(Level.ALL);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                // 不外发：报告生成失败的细节由断言体现，不刷 surefire 输出
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

    private static void collector(PerfScope p, MetricsSnapshot snapshot) {
        MetricsCollector mc = TestObjects.allocate(MetricsCollector.class);
        ConcurrentLinkedDeque<MetricsSnapshot> history = new ConcurrentLinkedDeque<>();
        history.addLast(snapshot);
        TestObjects.setField(MetricsCollector.class, mc, "history", history);
        TestObjects.setField(MetricsCollector.class, mc, "configManager", p.getConfigManager());
        TestObjects.setField(MetricsCollector.class, mc, "plugin", p);
        TestObjects.setField(PerfScope.class, p, "metricsCollector", mc);
    }

    private String generateHtml(Path dataFolder, ConfigManager.ConfigData cfg, MetricsSnapshot snap) throws Exception {
        PerfScope p = plugin(dataFolder, cfg);
        collector(p, snap);
        ReportGenerator gen = new ReportGenerator(p);
        generators.add(gen);
        String path = gen.generateSpecific("html");
        assertTrue(path != null, "HTML report generation must succeed, got a null path");
        return java.nio.file.Files.readString(Path.of(path), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ==================== 快照 ====================

    /**
     * 一份"看起来健康"的快照：TPS/MSPT 模块关掉（见类注释），其余指标全部远低于阈值。
     * 每个用例在此基础上只改它关心的那个字段，让失败信息能直接指到那张卡。
     */
    private static MetricsSnapshot healthySnapshot() {
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
        s.playerCount = 3;
        s.maxPlayers = 20;
        s.totalEntities = 500;
        s.totalLoadedChunks = 900;
        s.totalForceLoadedChunks = 0;
        s.tileEntityCount = 10;
        s.heapUsed = 1_073_741_824L;
        s.heapTotal = 2_147_483_648L;
        s.heapMax = 4_294_967_296L;
        s.heapUsagePercent = 25;
        s.processCpuLoad = 12.5;
        s.systemCpuLoad = 30.0;
        s.uptimeSeconds = 3600;
        s.loadedPluginCount = 8;
        s.worldCount = 1;

        Map<String, Integer> entities = new LinkedHashMap<>();
        entities.put("world", 500);
        s.entitiesByWorld = entities;

        Map<String, Integer> chunks = new LinkedHashMap<>();
        chunks.put("world", 900);
        s.chunksByWorld = chunks;

        Map<String, Integer> tileEntities = new LinkedHashMap<>();
        tileEntities.put("world", 10);
        s.tileEntitiesByWorld = tileEntities;

        return s;
    }

    // ==================== 断言辅助 ====================

    /**
     * 按卡片标题取出那张卡的修饰 class。
     *
     * <p>卡片结构固定为 {@code <div class="card X">\n<div class="card-value">值</div>\n
     * <div class="card-title">标题</div>}（{@code htmlCard} 的输出顺序），
     * 因此从标题位置向前找最近的 {@code <div class="card } 就是这张卡的开标签。</p>
     */
    private static String cardClass(String html, String title) {
        String marker = "<div class=\"card-title\">" + title + "</div>";
        int titleAt = html.indexOf(marker);
        assertTrue(titleAt > 0, "card titled '" + title + "' must be rendered");
        int cardAt = html.lastIndexOf(CARD_OPEN, titleAt);
        assertTrue(cardAt >= 0, "card titled '" + title + "' must have an opening tag with a level class");
        int from = cardAt + CARD_OPEN.length();
        int to = html.indexOf('"', from);
        assertTrue(to > from, "the card class must be terminated on the same tag");
        return html.substring(from, to);
    }

    /** 报告里全部卡片修饰类的出现次数（用于断言"没有一张卡落在不存在的 class 上"）。 */
    private static List<String> allCardClasses(String html) {
        List<String> out = new ArrayList<>();
        int at = html.indexOf(CARD_OPEN);
        while (at >= 0) {
            int from = at + CARD_OPEN.length();
            int to = html.indexOf('"', from);
            out.add(html.substring(from, to));
            at = html.indexOf(CARD_OPEN, to);
        }
        return out;
    }

    // ==================== 1. CSS 与 class 名必须对得上 ====================

    /**
     * 卡片实际使用的每个修饰类，样式表里都必须有对应规则。
     *
     * <p>这是本类的<b>根断言</b>：{@code .card.good/.warn/.critical/.unknown} 四条一旦
     * 少了任何一条，对应级别的卡片就退回青色基础样式——颜色失效而测试全绿。</p>
     *
     * <p>{@code normal} 另算一类：它不是级别（{@code ThresholdColors.cssClass} 永不返回它），
     * 而是"本卡刻意不判级"的显式标记（区块合计卡与世界数卡）。它同样必须有一条规则——
     * 否则又回到"HTML 用了一个样式表里不存在的 class"这个原始缺陷，
     * 只是这次发生在中性卡上。它必须与 {@code .card} 基础边条同色，即"中性"是可被判定的。</p>
     */
    @Test
    void stylesheetDeclaresEveryCardLevelTheCardsActuallyUse(@TempDir Path temp) throws Exception {
        String html = generateHtml(temp, config(), healthySnapshot());

        for (String level : List.of("good", "warn", "critical", "unknown")) {
            assertTrue(html.contains(".card." + level + " {"),
                "stylesheet must declare a .card." + level + " rule, otherwise cards of that level "
                    + "silently fall back to the neutral cyan border");
        }
        assertTrue(html.contains(".card.normal {"),
            "the two deliberately neutral cards (all-server chunk total, world count) still carry the "
                + "'normal' class, so it must have an explicit rule too — an undeclared class is exactly "
                + "the defect this test class exists for");
        assertTrue(html.contains(".card.normal { border-left-color: #00BCD4; }"),
            "'normal' must mean 'same cyan as the plain .card rule' — a neutral card that looks special "
                + "would be a different lie");

        for (String cssClass : allCardClasses(html)) {
            assertTrue(ALLOWED_CARD_CLASSES.contains(cssClass),
                "every card must carry a class the stylesheet knows, got: '" + cssClass + "'");
        }
    }

    /**
     * 概览区的每张卡都必须落在 {@code good/warn/critical/unknown}（判级卡）或
     * {@code normal}（刻意中性卡）之一，且那个 class 在样式表里真的有规则。
     *
     * <p>旧实现的问题是"五张卡写死 {@code normal}、而 CSS 里没有这条规则"——
     * 既没判级，也没有一条能让人读懂的"这是中性"的声明。现在两种走法都必须显式：
     * 判级卡给出真实级别，中性卡给出有规则的 {@code normal}。</p>
     */
    @Test
    void noOverviewCardFallsBackToTheNonExistentNormalClass(@TempDir Path temp) throws Exception {
        MetricsSnapshot snap = healthySnapshot();
        snap.playerCount = 20;
        snap.maxPlayers = 20;              // 满载：旧实现在这里仍然是青色
        snap.totalEntities = 45_000;       // 超 critical：旧实现仍然是青色
        snap.processCpuLoad = 95.0;        // 超 warning：旧实现仍然是青色

        String html = generateHtml(temp, config(), snap);

        List<String> classes = allCardClasses(html);
        assertEquals(8, classes.size(),
            "the overview section must render exactly eight cards, got: " + classes);
        for (String cssClass : classes) {
            assertTrue(ALLOWED_CARD_CLASSES.contains(cssClass),
                "unexpected card class '" + cssClass + "', expected one of " + ALLOWED_CARD_CLASSES);
            assertTrue(html.contains(".card." + cssClass + " "),
                "card class '" + cssClass + "' has no stylesheet rule — it would render as an "
                    + "unstyled ghost, which is precisely the v1.4.4 defect");
        }
        // 三张接了阈值的卡必须已经离开中性态，否则"补了 CSS"就足以让它们蒙混过关
        assertEquals("critical", cardClass(html, "Players online"));
        assertEquals("critical", cardClass(html, "Total entities"));
        assertEquals("warn", cardClass(html, "Process CPU"));
    }

    // ==================== 2. 三张接阈值的卡片真的会变色 ====================

    @Test
    void playersCardTurnsCriticalWhenCapacityIsSaturated(@TempDir Path temp) throws Exception {
        MetricsSnapshot snap = healthySnapshot();
        snap.playerCount = 20;
        snap.maxPlayers = 20;   // 100% >= critical 98

        String html = generateHtml(temp, config(), snap);

        assertEquals("critical", cardClass(html, "Players online"),
            "a fully saturated server must not be painted healthy");
    }

    @Test
    void entitiesCardTurnsCriticalAboveTheTotalThreshold(@TempDir Path temp) throws Exception {
        MetricsSnapshot snap = healthySnapshot();
        snap.totalEntities = 30_000;          // >= entities.total_critical_threshold
        snap.entitiesByWorld.put("world", 30_000);

        String html = generateHtml(temp, config(), snap);

        assertEquals("critical", cardClass(html, "Total entities"),
            "the all-server entity total must reflect entities.total_critical_threshold");
    }

    @Test
    void cpuCardTurnsWarnAboveTheProcessThreshold(@TempDir Path temp) throws Exception {
        MetricsSnapshot snap = healthySnapshot();
        snap.processCpuLoad = 85.0;   // >= cpu.process_warning_threshold (80), no critical key exists

        String html = generateHtml(temp, config(), snap);

        assertEquals("warn", cardClass(html, "Process CPU"),
            "config only defines a warning level for process CPU, so warn is the ceiling");
    }

    /**
     * CPU 未采集（-1 哨兵）时必须是 {@code unknown}（灰色），不是"健康"的青色。
     *
     * <p>Windows 上 {@code processCpuLoad} 恒为 -1。旧实现把这种"没有数据"渲染成与
     * "一切正常"同样的青色，读者无从区分。</p>
     */
    @Test
    void cpuCardRendersUnknownWhenTheProcessLoadWasNotCollected(@TempDir Path temp) throws Exception {
        MetricsSnapshot snap = healthySnapshot();
        snap.processCpuLoad = -1;

        String html = generateHtml(temp, config(), snap);

        assertEquals("unknown", cardClass(html, "Process CPU"),
            "an uncollected -1 sentinel must read as unknown (grey), not as a healthy cyan card");
    }

    /**
     * 玩家卡片的判定输入是<b>占容量百分比</b>，不是原始人数。
     *
     * <p>反例：9/10 人 = 90% 恰好踩到 warning 线，而裸数字 9 远小于 90。
     * 若误把 {@code playerCount} 传进 {@code Metric.PLAYERS}，这张卡会一直是 good。</p>
     */
    @Test
    void playersCardJudgesByPercentageNotByRawCount(@TempDir Path temp) throws Exception {
        MetricsSnapshot snap = healthySnapshot();
        snap.playerCount = 9;
        snap.maxPlayers = 10;   // 90% >= warning 90

        String html = generateHtml(temp, config(), snap);

        assertEquals("warn", cardClass(html, "Players online"),
            "9/10 must be judged as 90% capacity (warn), not as the raw number 9 (good)");
    }

    // ==================== 3. 刻意保持中性的两张卡 ====================

    /**
     * 区块<b>总览卡</b>是全服合计，而 {@code chunks.loaded_*_threshold} 的口径是「每个世界」。
     *
     * <p>反例（本条即其回归测试）：三个世界各 4000 区块的正常服，合计 12000 越过全服黄线
     * 10000。若把合计接上阈值，卡片立刻变黄，而此刻每个世界都远低于黄线、
     * 一条告警都不会发——报告在说谎。</p>
     */
    @Test
    void chunksOverviewCardStaysNeutralBecauseItsValueIsAnAllServerTotal(@TempDir Path temp) throws Exception {
        MetricsSnapshot snap = healthySnapshot();
        snap.worldCount = 3;
        snap.totalLoadedChunks = 12_000;                      // > warning 10000
        snap.chunksByWorld.clear();
        snap.chunksByWorld.put("world", 4_000);
        snap.chunksByWorld.put("world_nether", 4_000);
        snap.chunksByWorld.put("world_the_end", 4_000);
        snap.entitiesByWorld.clear();
        snap.entitiesByWorld.put("world", 100);
        snap.entitiesByWorld.put("world_nether", 100);
        snap.entitiesByWorld.put("world_the_end", 100);
        snap.tileEntitiesByWorld.clear();

        String html = generateHtml(temp, config(), snap);

        assertEquals("normal", cardClass(html, "Loaded chunks"),
            "the all-server chunk total must stay neutral: its 12000 exceeds the per-world warning line, "
                + "but no single world does — colouring it would report a problem that does not exist");
    }

    /**
     * 区块级别挂在「实体分布」表的<b>逐世界单元格</b>上——那里的值恰好就是阈值定义的那个量。
     *
     * <p>与上一条配对：同一份 12000（合计越线、逐世界不越线）必须得出"表格无颜色、卡片中性"；
     * 把某个世界换成 12000 后，那一格才该变黄。</p>
     */
    @Test
    void perWorldChunkCellsCarryTheLevelThatTheThresholdActuallyDescribes(@TempDir Path temp) throws Exception {
        MetricsSnapshot quiet = healthySnapshot();
        quiet.totalLoadedChunks = 12_000;
        quiet.chunksByWorld.clear();
        quiet.chunksByWorld.put("world", 4_000);
        quiet.chunksByWorld.put("world_nether", 4_000);
        quiet.chunksByWorld.put("world_the_end", 4_000);
        quiet.entitiesByWorld.clear();
        quiet.entitiesByWorld.put("world", 100);
        quiet.entitiesByWorld.put("world_nether", 100);
        quiet.entitiesByWorld.put("world_the_end", 100);
        quiet.tileEntitiesByWorld.clear();

        String quietHtml = generateHtml(temp, config(), quiet);
        assertFalse(quietHtml.contains("#FFB300\">4000</td>"),
            "no world exceeds the per-world warning line, so no chunk cell may be painted as warn");

        MetricsSnapshot hot = healthySnapshot();
        hot.totalLoadedChunks = 16_000;
        hot.chunksByWorld.clear();
        hot.chunksByWorld.put("world", 12_000);   // > warning 10000
        hot.chunksByWorld.put("world_nether", 4_000);
        hot.entitiesByWorld.clear();
        hot.entitiesByWorld.put("world", 100);
        hot.entitiesByWorld.put("world_nether", 100);
        hot.tileEntitiesByWorld.clear();

        String hotHtml = generateHtml(temp, config(), hot);
        assertTrue(hotHtml.contains("#FFB300\">12000</td>"),
            "the world sitting above chunks.loaded_warning_threshold must be highlighted in its own row");
        assertFalse(hotHtml.contains("#FFB300\">4000</td>"),
            "the healthy world in the same table must stay uncoloured");
    }

    /**
     * 世界数卡片没有阈值键（{@code ThresholdColors.Metric} 里也没有这个指标），
     * 刻意保持中性——不为它发明一条健康判据。
     */
    @Test
    void worldsCardStaysNeutralBecauseNoThresholdExistsForIt(@TempDir Path temp) throws Exception {
        MetricsSnapshot snap = healthySnapshot();
        snap.worldCount = 7;

        String html = generateHtml(temp, config(), snap);

        assertEquals("normal", cardClass(html, "Loaded worlds"),
            "world count has no threshold key, so its card must stay neutral rather than invent a level");
    }
}
