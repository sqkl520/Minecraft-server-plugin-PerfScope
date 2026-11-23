/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.support.AlertFixtures;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code alerts.*} 的 8 个决策层键只有一个读取入口：{@link ConfigManager.ConfigData}。
 *
 * <h2>锁的是什么缺陷</h2>
 * <p>{@code AlertService.loadOptions()} 原先把这 8 个键<b>再读一遍原始 config.yml</b>
 * （内部类 {@code RawConfig}），而 {@code ConfigManager.load()} 已经把其中 5 个键读进了
 * {@code ConfigData} 字段——那 5 个字段在没有消费者的情况下存在，两份默认值各写一份，
 * 直读路径还绕过了 {@code config-version} 迁移与未知键检测。</p>
 *
 * <p>本类不去断言"没有 RawConfig 这个类"（那是实现细节，改了名字就会误报），而是断言
 * <b>行为</b>：改 {@code ConfigData} 的字段必须真的影响 {@code Options} 与状态机阈值。
 * 只要还有人从原文件读，把 {@code ConfigData} 的字段改成别的值就不会改变结果，用例立刻红。</p>
 *
 * <h2>为什么必须让 ConfigManager 真的 load 一遍</h2>
 * <p>只 {@code setField(config)} 的夹具无法区分"配置层读对了"和"配置层根本没被调用"。
 * 这里用 {@link #managerFrom(Path, String)} 走真实 {@code load()}：写一份 YAML →
 * {@code ConfigManager.load()} → 拿到字段。于是"YAML 文本 → ConfigData → Options"整条链都被覆盖。</p>
 */
class AlertOptionsSingleSourceTest {

    @BeforeAll
    static void initMessages() {
        AlertFixtures.initMessages();
    }

    // ==================== 一、改 ConfigData 字段真的会改变 Options ====================

    /**
     * 8 个键全部写非默认值，断言 {@code Options} / 三个毫秒级字段逐一跟着变。
     *
     * <p><b>这是本类的主用例</b>：任何"又从别处读了一遍"的实现都会让这些断言失败——
     * 因为别处读的是原文件，而原文件里写的是另一组值。</p>
     */
    @Test
    void everyAlertOptionComesFromConfigData(@TempDir Path dir) throws Exception {
        // 文件里写一组"看起来像配置"的值；ConfigData 里稍后改成另一组。
        // 若实现仍从文件读，结果会等于文件那组；正确实现应等于 ConfigData 那组。
        ConfigManager cm = managerFrom(dir, ""
            + "alerts:\n"
            + "  recovery-debounce-cycles: 1\n"
            + "  digest-mode: false\n"
            + "  notify-mitigation: false\n"
            + "  unknown-after-minutes: 1\n"
            + "  flap-window-seconds: 1\n"
            + "  repush-delta: 0.5\n"
            + "  refire-min-interval-seconds: 1\n"
            + "  daily-summary:\n"
            + "    timezone: \"Europe/Paris\"\n");
        ConfigManager.ConfigData cfg = cm.get();
        // 现在把发射源改成与文件完全不同的值
        cfg.alerts_recoveryDebounceCycles = 7;
        cfg.alerts_digestMode = true;
        cfg.alerts_notifyMitigation = true;
        cfg.alerts_unknownAfterMinutes = 42;
        cfg.alerts_flapWindowSeconds = 13;
        cfg.alerts_repushDelta = 2.5d;
        cfg.alerts_refireMinIntervalSeconds = 11;
        cfg.alerts_dailySummaryTimezone = "Asia/Tokyo";

        AlertService svc = serviceWith(cm);
        invokeLoadOptions(svc);

        assertEquals(7, optionsInt(svc, "recoveryDebounceCycles"),
            "recoveryDebounceCycles must follow ConfigData, not config.yml");
        assertTrue(optionsBool(svc, "digestMode"), "digestMode must follow ConfigData");
        assertTrue(optionsBool(svc, "notifyMitigation"), "notifyMitigation must follow ConfigData");
        assertEquals(42, optionsInt(svc, "unknownAfterMinutes"),
            "unknownAfterMinutes must follow ConfigData");
        assertEquals("Asia/Tokyo", optionsString(svc, "dailyTimezone"),
            "dailyTimezone must follow ConfigData");
        assertEquals(11_000L, TestObjects.getField(AlertService.class, svc, "refireMinIntervalMs"),
            "refire window is configured in seconds but compared in ms");
        assertEquals(13_000L, TestObjects.getField(AlertService.class, svc, "flapWindowMs"),
            "flap window is configured in seconds but compared in ms");
        assertEquals(2.5d, TestObjects.getField(AlertService.class, svc, "repushDelta"),
            "repushDelta must be read in the double domain (8.5 must not become 8)");
    }

    /**
     * 反向对照：只改文件、**不**改 ConfigData 时，{@code Options} 必须走 ConfigData 的缺键默认值。
     *
     * <p>与上一条合起来构成"谁是发射源"的判定：上一条证明"改 ConfigData 会变"，
     * 这一条证明"改文件不会变"。只有两条都在，才能排除"两边恰好一致"的巧合。</p>
     */
    @Test
    void editingOnlyTheFileDoesNotAffectOptions(@TempDir Path dir) throws Exception {
        // 先按文件加载（此时 ConfigData 里就是文件值），证明文件确实被解析过
        ConfigManager cm = managerFrom(dir, ""
            + "alerts:\n"
            + "  recovery-debounce-cycles: 1\n"
            + "  digest-mode: false\n"
            + "  unknown-after-minutes: 1\n"
            + "  flap-window-seconds: 1\n"
            + "  repush-delta: 0.5\n"
            + "  refire-min-interval-seconds: 1\n");
        ConfigManager.ConfigData cfg = cm.get();
        assertEquals(1, cfg.alerts_flapWindowSeconds,
            "sanity: the file value did reach ConfigData (otherwise this test proves nothing)");
        // 再把 ConfigData 换成"文件里完全没写过的默认值"，模拟"快照已发布后有人改了盘上文件"
        cfg.alerts_recoveryDebounceCycles = 3;
        cfg.alerts_unknownAfterMinutes = 5;
        cfg.alerts_flapWindowSeconds = 180;
        cfg.alerts_repushDelta = 8.0d;
        cfg.alerts_refireMinIntervalSeconds = 60;

        AlertService svc = serviceWith(cm);
        invokeLoadOptions(svc);

        assertEquals(3, optionsInt(svc, "recoveryDebounceCycles"),
            "config.yml on disk must not leak into Options any more (files are not a second source)");
        assertEquals(180_000L, TestObjects.getField(AlertService.class, svc, "flapWindowMs"));
        assertEquals(60_000L, TestObjects.getField(AlertService.class, svc, "refireMinIntervalMs"));
        assertEquals(8.0d, TestObjects.getField(AlertService.class, svc, "repushDelta"));
    }

    // ==================== 二、缺键默认值 = 随包 config.yml 的默认值 ====================

    /**
     * 三项新收编的键：缺键时的默认值必须与 {@code src/main/resources/config.yml} 里登记的一致。
     *
     * <p>为什么不能只断言常量：常量与随包 config.yml 是两份彼此独立的副本，
     * 改一处而忘另一处时，"首次安装的默认行为"与"缺键的旧配置"会分叉——
     * 那正是本次缺陷的成因之一（两份默认值）。这条把两份钉在一起。</p>
     */
    @Test
    void missingKeysFallBackToTheShippedDefaults(@TempDir Path dir) throws Exception {
        // 空的 alerts 段：全部走缺键默认值
        ConfigManager cm = managerFrom(dir, "alerts:\n  enabled: false\n");
        ConfigManager.ConfigData cfg = cm.get();
        assertEquals(60, cfg.alerts_refireMinIntervalSeconds,
            "alerts.refire-min-interval-seconds default must be 60 (shipped config.yml agrees)");
        assertEquals(180, cfg.alerts_flapWindowSeconds,
            "alerts.flap-window-seconds default must be 180 (shipped config.yml agrees)");
        assertEquals(8.0d, cfg.alerts_repushDelta,
            "alerts.repush-delta default must be 8 (shipped config.yml agrees)");
        // 9A-14 的 5 项同口径复核
        assertEquals(3, cfg.alerts_recoveryDebounceCycles);
        assertFalse(cfg.alerts_digestMode);
        assertTrue(cfg.alerts_notifyMitigation);
        assertEquals(5, cfg.alerts_unknownAfterMinutes);
        assertEquals("", cfg.alerts_dailySummaryTimezone);
    }

    // ==================== 三、校验语义：0 = 关闭，负值 = 回退，不误伤 ====================

    /**
     * 三个"0 = 关闭该项抑制"的键：0 必须被原样保留，不能被当成非法值回退。
     *
     * <p>这条锁的是最容易被"顺手统一校验"改坏的语义：{@code refire} 的 0 是作者决策
     * （见 config.yml 该键注释），{@code flap} / {@code repush} 的 0 同样是明文的关闭开关。
     * 若把它们与 {@code debounce-cycles}（必须 ≥1）同用一个判据，用户写 0 会被静默改成默认值，
     * "关闭抑制"变成"恢复默认抑制"——恰好与意图相反。</p>
     */
    @Test
    void zeroMeansDisabledAndIsPreserved(@TempDir Path dir) throws Exception {
        ConfigManager cm = managerFrom(dir, ""
            + "alerts:\n"
            + "  refire-min-interval-seconds: 0\n"
            + "  flap-window-seconds: 0\n"
            + "  repush-delta: 0\n");
        ConfigManager.ConfigData cfg = cm.get();
        assertEquals(0, cfg.alerts_refireMinIntervalSeconds, "0 must mean 'disable re-fire detection'");
        assertEquals(0, cfg.alerts_flapWindowSeconds, "0 must mean 'disable flap suppression'");
        assertEquals(0.0d, cfg.alerts_repushDelta, "0 must mean 'disable the delta suppression'");

        // 并且 0 真的传导到状态机侧（毫秒 0，而不是回退成 60000）
        AlertService svc = serviceWith(cm);
        invokeLoadOptions(svc);
        assertEquals(0L, TestObjects.getField(AlertService.class, svc, "refireMinIntervalMs"),
            "0 seconds must reach the state machine as 0 ms, not fall back to 60 s");
        assertEquals(0L, TestObjects.getField(AlertService.class, svc, "flapWindowMs"));
        assertEquals(0.0d, TestObjects.getField(AlertService.class, svc, "repushDelta"));
    }

    /** 负值与 NaN 回退默认：拦住"配了个负数"与"配了个 NaN"两种静默失效。 */
    @Test
    void negativeAndNonFiniteValuesFallBackToDefaults(@TempDir Path dir) throws Exception {
        ConfigManager cm = managerFrom(dir, ""
            + "alerts:\n"
            + "  refire-min-interval-seconds: -5\n"
            + "  flap-window-seconds: -1\n"
            + "  repush-delta: -0.5\n"
            + "  unknown-after-minutes: 0\n"
            + "  recovery-debounce-cycles: 0\n");
        ConfigManager.ConfigData cfg = cm.get();
        assertEquals(60, cfg.alerts_refireMinIntervalSeconds, "negative re-fire window -> 60");
        assertEquals(180, cfg.alerts_flapWindowSeconds, "negative flap window -> 180");
        assertEquals(8.0d, cfg.alerts_repushDelta, "negative delta -> 8");
        assertEquals(5, cfg.alerts_unknownAfterMinutes, "unknown-after < 1 -> 5 minutes");
        assertEquals(3, cfg.alerts_recoveryDebounceCycles,
            "recovery-debounce < 1 -> falls back to the (clamped) debounce-cycles, which is 3");
    }

    /**
     * {@code recovery-debounce-cycles} 的缺键默认值是**当前的** {@code debounce-cycles}，不是常量 3。
     *
     * <p>旧实现写的是 {@code raw.getInt(key, Math.max(1, cfg.alerts_debounceCycles))}。
     * 用户把防抖调到 9 时，恢复防抖的兜底也应是 9；写死 3 会让"我明明调过防抖"的配置
     * 静默退回首次安装的节奏。</p>
     */
    @Test
    void recoveryDebounceFallsBackToTheEffectiveDebounce(@TempDir Path dir) throws Exception {
        ConfigManager cm = managerFrom(dir, ""
            + "alerts:\n"
            + "  debounce-cycles: 9\n");
        assertEquals(9, cm.get().alerts_recoveryDebounceCycles,
            "absent recovery-debounce-cycles must follow debounce-cycles (9), not a hard-coded 3");
    }

    /** 非法值回退时 {@code recovery-debounce-cycles} 的基准是**已钳制的** debounce（不会被 0 带崩）。 */
    @Test
    void recoveryDebounceFallbackUsesTheClampedDebounce(@TempDir Path dir) throws Exception {
        ConfigManager cm = managerFrom(dir, ""
            + "alerts:\n"
            + "  debounce-cycles: 0\n"
            + "  recovery-debounce-cycles: 0\n");
        ConfigManager.ConfigData cfg = cm.get();
        // 先钳制 debounce 0 -> 3，再让 recovery 回退到 3（顺序错了会得到 0，恢复防抖即失效）
        assertEquals(3, cfg.alerts_debounceCycles);
        assertEquals(3, cfg.alerts_recoveryDebounceCycles,
            "fallback must use the already-clamped debounce, never the raw 0");
    }

    // ==================== 四、没有第二份默认值 ====================

    /**
     * 同一键在 {@code ConfigManager} 里只能有**一处 readXxx 读取**。
     *
     * <p>这条是**结构性**断言，针对的正是本次缺陷的第二种后果："两份默认值各自维护"。
     * 它读源码而不是行为——行为对比只能证明"现在两份恰好相等"，
     * 而缺键默认值写错一处时两边会一起以为是 60（另一处根本没被读到），断言仍是绿的。</p>
     *
     * <p>只数 {@code readInt/readBool/readDouble/readString} 的调用点，且**先剥注释**：
     * 键名在注释（"旧实现是 raw.getInt(...)"这类历史说明）与告警文案的调用点里也会出现，
     * 那些都不是第二个读者。数它们会把正确实现判红，而这正是本仓库反复踩过的
     * "门禁一有噪音就没人看"的形状。</p>
     */
    @Test
    void eachKeyIsReadExactlyOnce() throws Exception {
        String src = stripComments(new String(Files.readAllBytes(
            Path.of("src/main/java/cn/perfscope/config/ConfigManager.java")), StandardCharsets.UTF_8));

        for (String key : new String[]{
            "alerts.recovery-debounce-cycles",
            "alerts.digest-mode",
            "alerts.notify-mitigation",
            "alerts.unknown-after-minutes",
            "alerts.daily-summary.timezone",
            "alerts.flap-window-seconds",
            "alerts.repush-delta",
            "alerts.refire-min-interval-seconds",
        }) {
            int readSites = 0;
            for (String reader : new String[]{"readInt", "readBool", "readDouble", "readString",
                "readIntQuiet", "getInt", "getBoolean", "getString", "getDouble"}) {
                readSites += countOccurrences(src, reader + "(\"" + key + "\"");
            }
            assertEquals(1, readSites,
                key + " must have exactly one readXxx() site in ConfigManager (found " + readSites
                    + "). A second read site means a second default value and a second review path");
        }
    }

    /** 生产代码里不得再有"自己打开 config.yml"的读者（RawConfig 已删除）。 */
    @Test
    void noProductionCodeLoadsConfigYmlDirectly() throws Exception {
        Path root = Path.of("src/main/java/cn/perfscope");
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            java.util.List<String> offenders = new java.util.ArrayList<>();
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    // ConfigManager 是唯一被允许打开 config.yml 的地方（saveDefaultConfig / loadConfiguration）
                    if (p.toString().contains("ConfigManager.java")) {
                        return;
                    }
                    // Messages 读的是 messages.yml，不是 config.yml
                    if (text.contains("loadConfiguration(new File(")
                        || text.contains("loadConfiguration(f)")) {
                        offenders.add(p.toString());
                    }
                } catch (Exception ignored) {
                    // 读不到就当无违规：本用例只做补充证据，真正的门禁是行为断言
                }
            });
            assertTrue(offenders.isEmpty(),
                "only ConfigManager may parse YAML from disk; these files still do it: " + offenders);
        }
    }

    // ==================== 五、时区：两个服务读同一个键 ====================

    /**
     * {@code alerts.daily-summary.timezone} 被 {@link AlertService}（日计数器）与
     * {@link DailySummaryService}（摘要投递）共用，两处的来源必须是同一个字段。
     *
     * <p>断言方式：把 {@code ConfigData} 的字段改成 UTC，看两边解析出的时区是否都是 UTC。
     * 若任一方仍在读原文件，它会拿到文件里写的那另一个时区。</p>
     */
    @Test
    void dailySummaryServiceReadsTheTimezoneFromConfigData(@TempDir Path dir) throws Exception {
        ConfigManager cm = managerFrom(dir, ""
            + "alerts:\n"
            + "  daily-summary:\n"
            + "    timezone: \"Europe/Paris\"\n");
        cm.get().alerts_dailySummaryTimezone = "UTC";

        // AlertService 侧
        AlertService svc = serviceWith(cm);
        invokeLoadOptions(svc);
        assertEquals("UTC", optionsString(svc, "dailyTimezone"));

        // DailySummaryService 侧（构造器会调 refreshZone）
        DailySummaryService daily = dailyServiceWith(cm);
        assertEquals(java.time.ZoneId.of("UTC"), TestObjects.getField(
            DailySummaryService.class, daily, "cachedZone"),
            "the daily summary must resolve its zone from ConfigData, not from config.yml");
    }

    // ==================== 夹具 ====================

    /**
     * 写一份 config.yml 到 {@code dir}，用真实的 {@link ConfigManager#load()} 解析它。
     *
     * <p>为什么不只 {@code setField(config)}：那样无法区分"配置层读对了"与"配置层根本没被调用"，
     * 而本次缺陷正是"有人没走配置层"。这条路径把 YAML 文本 → {@code ConfigData} 全程跑通。</p>
     *
     * <p>{@code plugin} 用 {@link AlertFixtures#barePlugin} 的壳——它只有 {@code logger}，
     * 所以还需补 {@code dataFolder}（load 会读它拼路径）与 {@code classLoader}
     * （{@code saveDefaultConfig} 与未知键检测要从 jar 内读默认 config.yml；
     * 后者用本类的 classloader 即可，{@code src/main/resources/config.yml} 已随编译复制到 target/classes）。</p>
     */
    private static ConfigManager managerFrom(Path dir, String yaml) throws Exception {
        Files.write(dir.resolve("config.yml"), yaml.getBytes(StandardCharsets.UTF_8));

        PerfScope p = AlertFixtures.barePlugin(quietLogger("pscope-single-source"));
        TestObjects.setField(PerfScope.class, p, "dataFolder", dir.toFile());
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "classLoader",
            AlertOptionsSingleSourceTest.class.getClassLoader());
        // saveDefaultConfig() 读的是 JavaPlugin.configFile（不是 getDataFolder() 推出来的）：
        // allocateInstance 不跑初始化器，这个字段是 null，不补就是 NPE 而不是"文件不存在"。
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "configFile",
            dir.resolve("config.yml").toFile());

        ConfigManager cm = new ConfigManager(p);
        TestObjects.setField(ConfigManager.class, cm, "plugin", p);
        TestObjects.setField(PerfScope.class, p, "configManager", cm);
        cm.load();
        return cm;
    }

    /** 装一个只注入必要字段的 {@link AlertService}（构造器会调 {@code ServerCompat.isFolia()}，故不走构造器）。 */
    private static AlertService serviceWith(ConfigManager cm) {
        PerfScope p = cm.get() == null ? null : pluginOf(cm);
        AlertService svc = TestObjects.allocate(AlertService.class);
        TestObjects.setField(AlertService.class, svc, "plugin", p);
        TestObjects.setField(AlertService.class, svc, "configManager", cm);
        TestObjects.setField(AlertService.class, svc, "options", defaultOptions());
        return svc;
    }

    /** {@link DailySummaryService} 的构造器只赋字段（不启动任务），可以直接 new。 */
    private static DailySummaryService dailyServiceWith(ConfigManager cm) throws Exception {
        PerfScope p = pluginOf(cm);
        TestObjects.setField(PerfScope.class, p, "dataFolder",
            Files.createTempDirectory("pscope-daily-zone").toFile());
        return new DailySummaryService(p, null, null);
    }

    private static PerfScope pluginOf(ConfigManager cm) {
        return (PerfScope) TestObjects.getField(ConfigManager.class, cm, "plugin");
    }

    private static Object defaultOptions() {
        try {
            Class<?> oc = Class.forName("cn.perfscope.alert.AlertService$Options");
            return TestObjects.invoke(oc, "defaults", new Class<?>[0]);
        } catch (Exception e) {
            throw new IllegalStateException("cannot build Options", e);
        }
    }

    private static void invokeLoadOptions(AlertService svc) {
        TestObjects.invoke(svc, "loadOptions", new Class<?>[0]);
    }

    private static Object options(AlertService svc) {
        return TestObjects.getField(AlertService.class, svc, "options");
    }

    private static int optionsInt(AlertService svc, String name) {
        Object o = options(svc);
        return (Integer) TestObjects.getField(o.getClass(), o, name);
    }

    private static boolean optionsBool(AlertService svc, String name) {
        Object o = options(svc);
        return (Boolean) TestObjects.getField(o.getClass(), o, name);
    }

    private static String optionsString(AlertService svc, String name) {
        Object o = options(svc);
        return (String) TestObjects.getField(o.getClass(), o, name);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /**
     * 把 {@code //}、{@code /* *\/} 与 javadoc 替换成等长空白（保留换行，行号仍准确）。
     *
     * <p>为什么要剥：本次改动的关键信息恰好写在注释里（"旧实现是 {@code raw.getInt(...)}"），
     * 那是**历史说明**而不是第二个读者。不剥注释的源码扫描会把"解释为什么没有第二份"的注释
     * 本身判成"有第二份"——门禁因此变成噪音，而噪音门禁等于没有门禁。</p>
     *
     * <p>字符串字面量<b>不</b>剥：本用例要数的就是 "key" 这样的字面量。
     * 代价是含 {@code //} 的字符串会被误当注释尾巴，对本文件不构成问题（键名里没有 {@code //}）。</p>
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (c == '/' && n == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && n == '*') {
                while (i < src.length()) {
                    if (src.charAt(i) == '*' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                        out.append("  ");
                        i += 2;
                        break;
                    }
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 不往 surefire 输出刷屏的 logger；校验告警在本类里不是被测对象。 */
    private static Logger quietLogger(String name) {
        Logger log = Logger.getLogger(name);
        log.setUseParentHandlers(false);
        log.setLevel(Level.OFF);
        return log;
    }
}
