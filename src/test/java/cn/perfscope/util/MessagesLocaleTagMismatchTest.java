/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.util;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code messages.yml} 的<b>语言标签失配即整份忽略</b>这一条安全属性。
 *
 * <h2>这条属性为什么重要到值得单独一个测试类</h2>
 *
 * <p>{@code Messages} 的类注释花了整段论证它：一份中文写的 {@code messages.yml} 若在
 * {@code general.language=en} 的服务器上被"尽力合并"，结果是<b>中英混排</b>——
 * 面板标题是英文、被改过的两条提示是中文、其余又是英文。那不是"多显示了一点用户文案"，
 * 而是<b>界面语言被打碎</b>：英文玩家看到的界面里混着看不懂的中文。</p>
 *
 * <p>而 {@code migrateLegacyFile} / {@code hasCustomizedValues} / {@code loadOverrides} /
 * {@code overrideNotice} 这一整块（{@code Messages.java} 里最复杂的一段）此前<b>零引用</b>——
 * 没有任何测试提到它们，{@code overrideNotice()} 更没有调用点断言。
 * 也就是说：把 {@code if (!fileLocale.equals(locale))} 那个分支改成"尽力合并"，
 * 925 个用例里<b>一个都不会红</b>。</p>
 *
 * <h2>三条断言各自锁什么</h2>
 *
 * <ul>
 *   <li><b>失配 → 忽略</b>：文件写 {@code language: zh}，配置 {@code general.language=en}，
 *       文件里改了 {@code reloaded}；{@code Messages.get("reloaded")} 必须是<b>英文内置默认</b>，
 *       既不是文件里的中文，也不是中文内置默认。</li>
 *   <li><b>用户能被告知</b>：{@code overrideNotice()} 非 null。没有它，用户执行
 *       {@code /perfscope reload} 只会看到一句"配置已重载"——一个成功提示——
 *       而他全部的自定义文案其实被丢弃了（这正是 9C-5 那条 Blocker 的形状）。</li>
 *   <li><b>一致 → 生效</b>：标签改成 {@code en} 后，同一个覆盖值必须真的出现。
 *       没有这一条，前两条可以被"永远忽略一切覆盖文件"这种实现满足——
 *       那是个更容易写错的方向，且同样是 bug。</li>
 * </ul>
 *
 * <h2>为什么必须走 {@link Messages#load} 而不是反射改快照</h2>
 *
 * <p>被测的恰恰是"加载时怎么读文件"这段判据。直接改 {@code snapshot} 会把
 * {@code loadOverrides} 整段跳过，测的就不是同一件事了——本仓库已经踩过
 * "断言了一个不存在的接线"的坑（见 {@code StatusExportsTest} 的类注释）。</p>
 *
 * <p>{@code plugin.getDataFolder()} 是 {@code JavaPlugin} 的 {@code final} 方法、直接返回
 * 私有 {@code dataFolder} 字段（{@code javap} 实测），所以用 {@link TestObjects#setField}
 * 写那个字段即可，不需要真实服务端。</p>
 *
 * <h2>全局状态</h2>
 *
 * <p>{@code Messages} 是进程级静态字段，surefire 复用同一 JVM。每个用例开始时显式切到
 * {@code en}、结束时 {@code @AfterAll} 再兜一次，避免把语言泄漏给后面跑的测试类。</p>
 */
class MessagesLocaleTagMismatchTest {

    /** 键取自 {@code EN_DEFAULTS}/{@code ZH_DEFAULTS} 里最稳定的一对，两侧文案明显不同。 */
    private static final String KEY = "reloaded";

    /** 文件里的自定义值：中英都不是，只要它出现就说明"文件被采用了"。 */
    private static final String CUSTOM = "OVERRIDE-MARKER-自定义文案";

    private static final Logger LOG = quietLogger();

    @TempDir
    Path dataFolder;

    /**
     * 英文内置默认值，在 {@link #resetToEnglish()} 里<b>实测</b>取一次。
     *
     * <p>不写字面量：文案本身是产品内容，改一句话就该让测试跟着走，而不是每次改文案都要
     * 回来同步一个常量。取法也保证了"内置默认"这个基准与生产用的是同一张表。</p>
     */
    private String englishDefault;

    private static Logger quietLogger() {
        Logger log = Logger.getLogger("pscope-messages-tag");
        log.setUseParentHandlers(false);
        log.setLevel(Level.OFF);
        return log;
    }

    @BeforeEach
    void resetToEnglish() {
        // 每个用例从确定的快照开始：上一个用例可能把语言切到了 zh
        Messages.load(pluginWith("en", null));
        assertEquals(Messages.LOCALE_EN, Messages.activeLocale(),
            "precondition: the snapshot must start in English");
        // 此刻没有任何数据目录 → 快照里只有内置默认，取到的就是英文默认值
        englishDefault = Messages.get(KEY);
        assertNotNull(englishDefault, "precondition: the key must exist in the built-in table");
    }

    @AfterAll
    static void restoreEnglish() {
        // 兜底：同 JVM 的其它测试类默认按英文断言
        Messages.load(pluginWith("en", null));
    }

    // ==================== 夹具 ====================

    /**
     * 装一个 plugin：{@code logger} 静音、{@code dataFolder} 指向临时目录、
     * {@code configManager} 带指定语言。
     *
     * <p>不用 {@link TempDir} 时传 {@code null} 表示"没有数据目录"——{@code Messages}
     * 对 {@code getDataFolder() == null} 有显式早退分支（返回空覆盖），正好用作基线。</p>
     */
    private static PerfScope pluginWith(String language, Path folder) {
        ConfigManager cm = new ConfigManager(null);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.general_language = language;
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);

        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, plugin, "logger", LOG);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);
        if (folder != null) {
            // JavaPlugin#getDataFolder() 直接返回这个字段（javap 实测），无需别的装配
            TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, plugin,
                "dataFolder", folder.toFile());
        }
        return plugin;
    }

    /** 在临时目录写一份 {@code messages.yml}。 */
    private void writeMessagesYml(String languageTag) throws IOException {
        String content = "# test fixture\n"
            + "language: " + languageTag + "\n"
            + KEY + ": \"" + CUSTOM + "\"\n";
        Files.writeString(dataFolder.resolve("messages.yml"), content, StandardCharsets.UTF_8);
    }

    // ==================== 一、标签失配 → 整份忽略 ====================

    /**
     * <b>核心用例</b>：{@code messages.yml} 写 {@code language: zh}、配置是 {@code en}，
     * 文件里的覆盖值必须<b>一个都不生效</b>。
     *
     * <p>这是"避免中英混排"的唯一实现手段。若改成"尽力合并"，本用例会拿到
     * {@link #CUSTOM}——一个中文串出现在英文界面上。</p>
     *
     * <p>同时断言它拿到的<b>正是英文内置默认</b>（而不是键名、不是空串、不是中文默认）：
     * 只有精确等值才能同时排除"覆盖生效"与"选择器坏了"两种失败。</p>
     */
    @Test
    @DisplayName("language 标签与 general.language 不一致 → 整份文件被忽略，用英文内置默认")
    void mismatchedLanguageTagDiscardsTheWholeFile() throws IOException {
        writeMessagesYml("zh");

        Messages.load(pluginWith("en", dataFolder));

        assertEquals(Messages.LOCALE_EN, Messages.activeLocale(),
            "active language must stay en — the file's tag must not switch the interface language");

        String actual = Messages.get(KEY);
        assertFalse(actual.contains(CUSTOM),
            "a zh-tagged file must NOT leak its customised values into an en interface: that is "
                + "exactly the 中英混排 this rule exists to prevent. Actual: " + actual);
        // 与英文内置默认精确相等：既排除"覆盖生效"，也排除"取到键名/空串"
        assertEquals(englishDefault, actual,
            "on a mismatch the value must be the built-in English default, not the file's value, "
                + "not the zh default, and not the raw key. Actual: " + actual);
    }

    /**
     * 失配时必须有可读的"未生效"原因给命令层。
     *
     * <p>{@code overrideNotice()} 是 9C-5 加的：{@code /perfscope reload} 原先无论覆盖文件
     * 有没有被采用都回一句"配置已重载"。用户的全部自定义文案被丢弃，却收到一个成功提示。
     * 这条断言把"失败必须可叙述"钉住。</p>
     */
    @Test
    @DisplayName("失配时 overrideNotice() 必须非 null（用户能被告知整份被忽略）")
    void mismatchLeavesAnOverrideNotice() throws IOException {
        writeMessagesYml("zh");

        Messages.load(pluginWith("en", dataFolder));

        String notice = Messages.overrideNotice();
        assertNotNull(notice,
            "when the whole messages.yml is ignored the caller must be able to say so; otherwise "
                + "/perfscope reload reports success while every customisation was dropped (9C-5)");
        assertTrue(notice.contains("zh") && notice.contains("en"),
            "the notice must name both the file's language and the current one, so the admin knows "
                + "which line to change. Actual: " + notice);
    }

    /**
     * 无法识别的标签同样整份忽略，且<b>不</b>被归一化成默认语言。
     *
     * <p>这是 {@code canonicalLocale} 那条"严格版"判据的意义：把它写成"未知 → 默认"，
     * 一份 {@code fr} 文件会被当成 {@code en} 文案使用，界面上出现法语。</p>
     */
    @Test
    @DisplayName("无法识别的 language 标签 → 整份忽略，且不被当成默认语言")
    void unknownLanguageTagIsNotCoercedToTheDefaultLocale() throws IOException {
        writeMessagesYml("fr");

        Messages.load(pluginWith("en", dataFolder));

        String actual = Messages.get(KEY);
        assertFalse(actual.contains(CUSTOM),
            "an unrecognised tag must not be coerced into the default locale: that would let a "
                + "foreign-language file masquerade as the current language. Actual: " + actual);
        assertEquals(englishDefault, actual,
            "it must fall back to the built-in default");
        assertNotNull(Messages.overrideNotice(),
            "and the admin must be told the file was ignored");
    }

    // ==================== 二、标签一致 → 覆盖生效 ====================

    /**
     * <b>反向对照</b>：标签一致时覆盖必须生效。
     *
     * <p>没有这一条，上面三条可以被"永远忽略一切覆盖文件"满足——那是同一个 bug 的镜像，
     * 且更隐蔽（用户改了文案，什么也没发生，也没有任何告警）。</p>
     */
    @Test
    @DisplayName("标签一致 → 覆盖值生效，且 overrideNotice() 为 null")
    void matchingLanguageTagAppliesTheOverride() throws IOException {
        writeMessagesYml("en");

        Messages.load(pluginWith("en", dataFolder));

        String actual = Messages.get(KEY);
        assertTrue(actual.contains(CUSTOM),
            "a correctly tagged file must be used: ignoring it would make every customisation "
                + "silently do nothing. Actual: " + actual);
        assertNull(Messages.overrideNotice(),
            "when the file is adopted there is nothing to warn about; a non-null notice here would "
                + "make /perfscope reload claim a problem that does not exist. Actual: "
                + Messages.overrideNotice());
    }

    /**
     * 中文标签 + 中文配置：同一个覆盖值必须生效（证明判据是"相符"而不是"只认 en"）。
     *
     * <p>这一条防的是把判据写成 {@code "en".equals(fileLocale)} 那种实现——它在英文环境下
     * 表现完美，在中文环境下会静默丢掉全部中文自定义。</p>
     */
    @Test
    @DisplayName("zh 标签 + zh 配置 → 覆盖生效（判据是\"相符\"，不是\"只认 en\"）")
    void matchingChineseTagAlsoApplies() throws IOException {
        writeMessagesYml("zh");

        Messages.load(pluginWith("zh", dataFolder));

        assertEquals(Messages.LOCALE_ZH, Messages.activeLocale(),
            "precondition: the interface language must really be zh");
        String actual = Messages.get(KEY);
        assertTrue(actual.contains(CUSTOM),
            "a zh-tagged file on a zh server must be used; a rule written as \"en\".equals(tag) "
                + "would silently drop every Chinese customisation. Actual: " + actual);
        assertNull(Messages.overrideNotice(),
            "no notice when the file matches");
    }

    // ==================== 三、无文件时不制造噪音 ====================

    /**
     * 没有任何 {@code messages.yml} 时：纯内置默认，且不该有"未生效"的告警。
     *
     * <p>否则每台新装的服务器第一次 reload 都会看到一条"messages.yml 未生效"——
     * 而它根本不存在。把"没有文件"说成"文件有问题"是纯粹的错误信息。</p>
     */
    @Test
    @DisplayName("没有 messages.yml → 纯内置默认，且没有 overrideNotice")
    void missingFileIsNotANoticeWorthyEvent() {
        Messages.load(pluginWith("en", dataFolder));

        assertEquals(englishDefault, Messages.get(KEY),
            "with no file the built-in default must be used");
        assertNull(Messages.overrideNotice(),
            "a missing file is not a problem: no notice must be produced for it");
    }
}
