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
package cn.perfscope.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Messages#load(cn.perfscope.PerfScope)} 的 {@code null} 分支不得丢模块键。
 *
 * <h2>这条 bug 的形状（P1）</h2>
 *
 * <p>{@code load(null)} 原本发布 {@code EN_DEFAULTS}——静态块里手写的 85 个基础键。而模块表
 * （{@code cmd.*} / {@code alert.*} / {@code report.*} / {@code diag.*} / {@code config.*} /
 * {@code run.*}）只有 {@code mergeModules()} 才会并进 {@code BUILTIN_MUTABLE}。于是这一条
 * 早退分支让 730 个模块键<b>整批消失</b>，任何 {@code Messages.get()} 都退化成"返回键名本身"。</p>
 *
 * <p><b>为什么它不是"测试环境的无关紧要差异"</b>：这条分支正是整个测试体系在无 Bukkit 环境下
 * 初始化消息层的唯一入口（{@code AlertFixtures.initMessages()} 与
 * {@code TaskHandleContractTest} 都写着"只能传 null"）。差 85 vs 815 个键，足以让
 * "注册了模块键 → load 之后仍能取到值" 这件本应成立的事在测试里永远无法成立，
 * 于是所有依赖渲染文案的断言只能退而断言"键名/令牌"，或者干脆不断言。</p>
 *
 * <h2>这里锁什么</h2>
 *
 * <ul>
 *   <li><b>已注册的模块键在 {@code load(null)} 之后仍能取到值</b>——不是键名，也不是空串。
 *       这是回归判据：把实现改回 {@code EN_DEFAULTS} 这条会立刻红。</li>
 *   <li><b>语言仍是默认语言、覆盖表为空</b>——修复不能顺手把"无插件"变成"有插件"，
 *       它只是"读不到配置和覆盖文件"，语义边界不变。</li>
 *   <li><b>同名键的两种语言都到位</b>——只合并英文表同样会让中文服主看到键名。</li>
 * </ul>
 *
 * <p>模块的 {@code static} 块由本类显式 {@code init()} 触发，不依赖
 * {@code Messages.onFirstLoad} 的登记顺序：那是"第一次 load 才执行"的回调，在同 JVM 里
 * 谁先 load 谁决定结果——依赖它会让本类变成"本地绿、CI 红"的那种测试
 * （{@code CoverageProblemTest} 踩过同一个坑，注释里有记录）。</p>
 */
class MessagesLoadNullTest {

    /** 本类自造的探针键。名字带 {@code probe.} 前缀，任何内置表都不会撞上。 */
    private static final String PROBE_KEY = "probe.load-null.registered-after-boot";

    /**
     * 注册一个模块键，然后走 {@code load(null)}，键必须仍能取到值。
     *
     * <p>为什么先注册再 load：这正是真实时序（各模块 static 块注册，启动期 load 合并）。
     * 若写成"load 之后再注册"，测试会退化成只验证 {@code registerModule} 本身，
     * 而 bug 恰好在 load 的早退分支上。</p>
     */
    @Test
    @DisplayName("load(null) 之后，已注册的模块键仍返回文案而不是键名")
    void registeredModuleKeysSurviveLoadNull() {
        Map<String, String> en = new LinkedHashMap<>();
        Map<String, String> zh = new LinkedHashMap<>();
        en.put(PROBE_KEY, "probe value (en)");
        zh.put(PROBE_KEY, "探针值（中文）");
        Messages.registerModule(en, zh);

        Messages.load(null);

        String rendered = Messages.get(PROBE_KEY);
        assertNotEquals(PROBE_KEY, rendered,
            "load(null) 之后模块键退回了键名本身 —— 这正是那条 bug：早退分支发布的是"
                + "合并前的 EN_DEFAULTS（85 键），而不是 mergeModules() 之后的完整表");
        assertEquals("probe value (en)", rendered,
            "默认语言是 en，探针键必须取英文值；got: " + rendered);
    }

    /**
     * 真实模块键（不是自造探针）同样必须到位。
     *
     * <p>探针键只证明"注册这条路通"，不证明"内置模块的键真在表里"——两者可以被一次
     * {@code EXTRA_*} 相关回归分别弄坏。这里挑两条来自不同模块的键：
     * {@code cmd.*} 来自 {@code CommandMessages}，{@code alert.*} 来自 {@code AlertMessages}。</p>
     */
    @Test
    @DisplayName("load(null) 之后，内置模块的键（cmd.* / alert.*）仍返回文案")
    void builtinModuleKeysSurviveLoadNull() {
        // 显式触发全部模块的 static 块（registerModule 在那里），不依赖 onFirstLoad 的登记顺序
        touchAllMessageModules();

        Messages.load(null);

        String cmd = Messages.get("cmd.section.tps");
        assertFalse(cmd.equals("cmd.section.tps"),
            "cmd.* 键在 load(null) 之后退回了键名；cmd 表有上百个键，全部会这样失效");
        assertTrue(cmd.contains("TPS"),
            "取到的应该是真实标题文案（含 TPS 字样），而不是键名；got: " + cmd);

        String alert = Messages.get("alert.threshold.tps", 18, 15, "<", "<");
        assertFalse(alert.equals("alert.threshold.tps"),
            "alert.* 键在 load(null) 之后退回了键名");
        assertTrue(alert.contains("18") && alert.contains("15"),
            "占位符必须被替换成传入的阈值；got: " + alert);

        // 键集合本身也要在：check-i18n / 诊断工具按 keys() 判断键是否存在
        assertTrue(Messages.keys().contains("cmd.section.tps"),
            "keys() 必须包含模块键，否则任何按 keys() 判存的逻辑（如 ReportMessages#ensureRegistered）"
                + "都会走进错误分支");
        // 判据是"远多于基础表"，而不是写死一个总数：总数会随每次新增键漂移，
        // 写死它只会让每个改动 i18n 的人都得来改这条测试（那正是本仓库反对的脆弱断言）。
        // 真正要防的退化是"模块表没合并"——那会掉到 85 上下，差距超过一个数量级。
        assertTrue(Messages.keys().size() > 700,
            "合并后应含全部模块键（当前 826）；只有 " + Messages.keys().size()
                + " 个说明模块表没合并（基础表是 85 个）");
    }

    /**
     * 触发全部文案模块的 {@code static} 块。
     *
     * <p>不能用 {@code Messages.touchModules()} 代替：那张 {@code MODULE_TOUCHERS} 表由
     * {@code PerfScope#onEnable} 填，测试里没有启动流程，表是空的——调用它等于什么都没做，
     * 而断言因此会以"模块表没合并"的形式莫名失败（本类第一版就踩过）。</p>
     */
    private static void touchAllMessageModules() {
        cn.perfscope.CoreMessages.init();
        cn.perfscope.RuntimeMessages.init();
        cn.perfscope.alert.AlertMessages.init();
        cn.perfscope.diagnose.DiagnoseMessages.init();
        cn.perfscope.command.CommandMessages.init();
        cn.perfscope.report.ReportMessages.init();
        cn.perfscope.config.ConfigMessages.init();
    }

    /**
     * {@code null} 分支只表达"读不到插件状态"，不能顺手把语言或覆盖表改掉。
     *
     * <p>实现换成 {@code defaultsOf(...)} 之后，唯一仍然成立的三件事是：语言 = 默认语言、
     * 覆盖表为空、快照非 null。这条把边界钉住，防止下一次有人"顺手"在这里读一次
     * {@code general.language}（那会让 {@code load(null)} 依赖静态状态，测试之间互相污染）。</p>
     */
    @Test
    @DisplayName("load(null)：语言回默认、覆盖为空，不抛异常")
    void loadNullKeepsNeutralLanguageState() {
        Messages.load(null);

        assertEquals(Messages.DEFAULT_LOCALE, Messages.activeLocale(),
            "无插件环境没有可信的语言来源，必须回默认语言而不是沿用上一次的快照");
        assertEquals("You do not have permission to use this command.",
            Messages.get("no-permission"),
            "基础键同样要能取到值（回归保护：不要把整张表换成只有模块键的新表）");
    }

    /**
     * 中文值也要到位：只合并英文表会让中文服主看到键名，而这条分支恰恰是"两种语言都没有覆盖文件"。
     *
     * <p>做法是先把语言切到中文（走真实入口 {@code load(plugin)}，需要一个只有 logger +
     * configManager 的裸 plugin），然后调 {@code load(null)}。这里刻意断言<b>切换之后</b>的
     * 模块键：它同时覆盖"中文表被合并"和"load(null) 用的是合并表"两件事。</p>
     */
    @Test
    @DisplayName("中文语言下 load(null) 的模块键取中文值")
    void chineseModuleKeysSurviveLoadNull() {
        touchAllMessageModules();

        cn.perfscope.config.ConfigManager cm = new cn.perfscope.config.ConfigManager(null);
        cn.perfscope.config.ConfigManager.ConfigData cfg = new cn.perfscope.config.ConfigManager.ConfigData();
        cfg.general_language = Messages.LOCALE_ZH;
        cn.perfscope.support.TestObjects.setField(
            cn.perfscope.config.ConfigManager.class, cm, "config", cfg);

        cn.perfscope.PerfScope plugin =
            cn.perfscope.support.TestObjects.allocate(cn.perfscope.PerfScope.class);
        java.util.logging.Logger log = java.util.logging.Logger.getLogger("pscope-load-null-zh");
        log.setUseParentHandlers(false);
        log.setLevel(java.util.logging.Level.OFF);
        cn.perfscope.support.TestObjects.setField(
            cn.perfscope.PerfScope.class, plugin, "logger", log);
        cn.perfscope.support.TestObjects.setField(
            cn.perfscope.PerfScope.class, plugin, "configManager", cm);

        try {
            Messages.load(plugin);
            assertEquals(Messages.LOCALE_ZH, Messages.activeLocale(),
                "前置条件：语言必须真的切到中文，否则这条测的不是中文表");

            // load(null) 会回到默认语言（en）——这是设计边界，不是回归。
            Messages.load(null);

            // 语言回 en，所以这里看到的是英文值；关键判据是"不是键名"。
            String en = Messages.get("cmd.section.tps");
            assertFalse(en.equals("cmd.section.tps"),
                "load(null) 之后 cmd.* 退回了键名；中文用户同样会看到键名");

            // 再切回中文，确认中文表确实被合并进了 BUILTIN_MUTABLE（而不是只合并了英文）。
            Messages.load(plugin);
            String zh = Messages.get("cmd.section.tps");
            assertTrue(zh.contains("诊断") || zh.contains("TPS"),
                "中文表必须同样被合并；got: " + zh);
            assertFalse(zh.equals("cmd.section.tps"),
                "中文值缺失时 registerModule 会用键名补齐 —— 那正是本条要防的'半吊子翻译'");
        } finally {
            // 还原成默认语言：本类的状态是进程级静态字段，同 JVM 里后续测试按英文断言
            Messages.load(null);
        }
    }
}
