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
package cn.perfscope.alert;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.ThresholdOperator;
import cn.perfscope.support.AlertFixtures;
import cn.perfscope.support.TestObjects;
import cn.perfscope.util.Messages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警正文里那段「阈值(...)」说明的<b>比较符</b>必须与采集层实际算子一致。
 *
 * <h2>这条测试要防的是什么</h2>
 *
 * <p>{@code alert.threshold.*} 模板里原本<b>硬编码</b>了符号，例如
 * {@code "warning > {0} / critical > {1}"}。而 {@code players} 的判定是<b>闭区间</b>
 * {@code pct >= threshold}（18/20 人 = 恰好 90% 是最常见的满员配置）。于是读数恰好 90 时：
 * 告警<b>已经触发</b>，文案却告诉服主"大于 90 才算"。这不是笔误，而是算子表之外多出来的
 * 一份副本——改算子忘改文案，没有任何测试会红。</p>
 *
 * <p>现在符号由 {@link ThresholdOperator.Rule#warningSymbol()} /
 * {@link ThresholdOperator.Rule#criticalSymbol()} 派生，本类把「文案说的」与「判定做的」
 * 钉在一起。三层断言，从弱到强：</p>
 * <ol>
 *   <li><b>字形语义</b>：符号里带不带 {@code =} 必须等价于「读数恰好等于阈值时是否越界」。
 *       这条不读任何模板，直接钉 {@link ThresholdOperator.Comparison#symbolFor} 与
 *       {@link ThresholdOperator.Rule#levelOf} 的一致性——把 {@code PLAYERS} 的
 *       {@code INCLUSIVE} 改成 {@code STRICT}，符号会从 {@code ≥} 变成 {@code >} 而边界不再命中，
 *       两边同时变，这条断言因此看得见矛盾。</li>
 *   <li><b>渲染结果</b>：{@code thresholdText} 对七个指标渲染出的文案必须含该指标的符号。
 *       这条读的是服主真正看到的那串字。</li>
 *   <li><b>中英双份</b>：两种语言下都成立（模板是两份独立的字面量，只测一份等于只保护一半）。</li>
 * </ol>
 *
 * <h2>为什么 CPU 单独一条</h2>
 *
 * <p>CPU 走迟滞闩锁、不在 {@code ThresholdOperator.ALL} 表里，它的进入判据是
 * {@code MetricsCollector.latched} 里的严格 {@code >}。本类<b>反射调用真实的 latched</b>
 * 来确认"等于阈值不进入"，而不是断言源码文本——文本断言一重构就假红。</p>
 */
class ThresholdTextMatchesOperatorTest {

    @BeforeAll
    static void initMessages() {
        AlertFixtures.initMessages();
    }

    @AfterAll
    static void restoreEnglish() {
        // Messages 是进程级静态状态，surefire 单 fork 复用 JVM：改过语言必须还原，
        // 否则同批后面按英文断言的测试类会一起变红
        switchLocale(Messages.LOCALE_EN);
    }

    // ====================================================================================
    // 1. 字形语义 == 边界行为
    // ====================================================================================

    /**
     * 每个规则的符号必须"诚实"：闭区间当且仅当恰好等于阈值就告警。
     *
     * <p>这条是整族的根。它不看模板、不看渲染，只问两个对象：符号字符串是不是闭区间字形，
     * 以及 {@code levelOf(阈值, 阈值, 远离值)} 是否真的越界。二者必须同真同假。</p>
     *
     * <p><b>{@code ≥} 是单字符 U+2265</b>，不含字面上的 {@code =}——用
     * {@link #isInclusiveSymbol} 判定，不能写 {@code contains("=")}。</p>
     */
    @Test
    void everySymbolSaysTheSameThingAsTheOperatorItDescribes() {
        for (ThresholdOperator.Rule rule : ThresholdOperator.ALL) {
            boolean lowIsBad = rule.direction() == ThresholdOperator.Direction.LOW_IS_BAD;
            // 取一个「warning 命中、critical 必然不命中」的三元组：value 恰好等于 warning
            double warning = 50.0;
            double critical = lowIsBad ? 1.0 : 1_000_000.0;
            boolean equalityTrips = rule.levelOf(warning, warning, critical) >= 1;

            String warnSym = rule.warningSymbol();
            String critSym = rule.criticalSymbol();
            assertEquals(isInclusiveSymbol(warnSym), equalityTrips,
                rule.key() + " 的 warning 符号 \"" + warnSym + "\" 自称"
                    + (isInclusiveSymbol(warnSym) ? "闭区间（恰好等于就告警）" : "严格（恰好等于不告警）")
                    + "，但 levelOf(阈值=" + warning + ") 实测=" + equalityTrips);
            assertEquals(isInclusiveSymbol(critSym), equalityTrips,
                rule.key() + " 的 critical 符号 \"" + critSym + "\" 与 warning 档闭合性不一致；"
                    + "本仓库当前所有规则两档同闭合性，分叉时请同步改写本断言");
        }
    }

    /** 该符号字形是否表示"恰好等于也越界"（{@code >=} / {@code ≥} / {@code <=} / {@code ≤}）。 */
    private static boolean isInclusiveSymbol(String symbol) {
        return symbol.contains("=") || symbol.contains("≥") || symbol.contains("≤");
    }

    /** 符号与方向一致：低值差只能是 {@code <}/{@code ≤}，高值差只能是 {@code >}/{@code ≥}。 */
    @Test
    void symbolFollowsDirection() {
        for (ThresholdOperator.Rule rule : ThresholdOperator.ALL) {
            boolean lowIsBad = rule.direction() == ThresholdOperator.Direction.LOW_IS_BAD;
            for (String symbol : new String[]{rule.warningSymbol(), rule.criticalSymbol()}) {
                boolean pointsTheRightWay = lowIsBad
                    ? symbol.startsWith("<") || symbol.startsWith("≤")
                    : symbol.startsWith(">") || symbol.startsWith("≥");
                assertTrue(pointsTheRightWay,
                    rule.key() + " 是 " + rule.direction() + "，符号却是 \"" + symbol + "\"");
            }
        }
    }

    /**
     * {@code players} 这一条单独点名，理由写在这里而不是注释里：它是唯一被怀疑过的指标。
     *
     * <p>闭区间是<b>有意的</b>：18/20 人 = 恰好 90% 是默认 warning 阈值，也是小型服最常见的
     * 满员配置。文案此前写「&gt;」，于是"正在告警"的读数被描述成"还没到线"。</p>
     */
    @Test
    void playersUsesInclusiveSymbolBecauseItsComparisonIsInclusive() {
        assertEquals(ThresholdOperator.Comparison.INCLUSIVE,
            ThresholdOperator.PLAYERS.criticalComparison(), "players 的 critical 档必须保持闭区间");
        assertEquals("≥", ThresholdOperator.PLAYERS.warningSymbol(),
            "players 的文案符号必须是 ≥：恰好 90%（18/20 人）时告警已经触发");
        assertEquals("≥", ThresholdOperator.PLAYERS.criticalSymbol());
    }

    // ====================================================================================
    // 2. 渲染结果（英文界面）
    // ====================================================================================

    /**
     * 七个指标逐个核对：服主看到的那串字里，符号必须与算子表一致。
     *
     * <p><b>期望值里的 {@code 18.0} / {@code 45.0}</b> 是现有实现的渲染结果（double 直接
     * {@code String.valueOf}），本类不顺手改它——那是数值格式问题，与算子无关，
     * 混进来只会让这条测试的失败原因变得含糊。</p>
     */
    @Test
    void renderedThresholdTextCarriesTheOperatorsSymbol() throws Exception {
        AlertServiceStateMachineFixture f = fixtureWithAllThresholds();

        assertRenders(f, "tps:global", "warning < 18.0 / critical < 15.0",
            ThresholdOperator.TPS);
        assertRenders(f, "mspt:global", "warning > 45.0ms / critical > 50.0ms",
            ThresholdOperator.MSPT);
        assertRenders(f, "players:global", "warning ≥ 90 / critical ≥ 98",
            ThresholdOperator.PLAYERS);
        assertRenders(f, "entities:global", "warning > 20000 / critical > 30000",
            ThresholdOperator.ENTITIES_GLOBAL);
        assertRenders(f, "chunks:world:world", "warning > 10000 / critical > 15000",
            ThresholdOperator.CHUNKS_WORLD);
        assertRenders(f, "memory:global", "warning ≥ 85% / critical ≥ 95%",
            ThresholdOperator.MEMORY);
    }

    /**
     * {@code players} 的反向断言：文案里<b>不能</b>出现 {@code "warning > 90"}。
     *
     * <p>正向断言 {@code contains("≥ 90")} 已经能抓住它，但这条把"具体错成什么样"写死，
     * 失败信息因此直接可读——旧的错误文案就是这一个字符串。</p>
     */
    @Test
    void playersTextNoLongerClaimsStrictGreaterThan() throws Exception {
        String text = thresholdText(fixtureWithAllThresholds(), "players:global");
        assertFalse(text.contains("> 90"),
            "players 的判定是闭区间 >=，文案不能说 '> 90'；实际渲染：" + text);
        assertTrue(text.contains("≥ 90"), "实际渲染：" + text);
    }

    /**
     * 逐世界实体走的是另一条模板（{@code alert.threshold.entities.world}），
     * 不含数字也不含符号——它是"去看 worlds 段"的指路牌。这条锁住它没被误加符号。
     */
    @Test
    void perWorldEntitiesPointsAtTheWorldsSection() throws Exception {
        String text = thresholdText(fixtureWithAllThresholds(), "entities:world:nether");
        assertEquals("see the worlds section for per-world thresholds", text);
    }

    // ====================================================================================
    // 3. CPU：符号与迟滞闩锁的进入线一致
    // ====================================================================================

    /**
     * CPU 文案写 {@code process > 80%}，而进入判据确实是严格 {@code >}。
     *
     * <p>真值由 {@code MetricsCollector.latched} 决定，这里反射调它——
     * 而不是断言源码里有 {@code "value > threshold"}（那种断言一重构就假红，
     * 且行为没变时也会红）。退出线 {@code >= exit} 由 {@code alert.msg.hysteresis}
     * 单独说明，不属于这段阈值文案的承诺。</p>
     */
    @Test
    void cpuTextSymbolMatchesTheLatchEntryComparison() throws Exception {
        AlertServiceStateMachineFixture f = fixtureWithAllThresholds();
        String text = thresholdText(f, "cpu:process");

        assertEquals(">", ThresholdOperator.CPU_ENTRY_SYMBOL);
        assertTrue(text.contains("process > 80.0% / system > 90.0%"), "实际渲染：" + text);

        // 进入线是严格的：恰好等于阈值不进入，高一点点才进入
        assertFalse(latched(false, 80.0, 80.0, 5.0),
            "文案写的是 '>'（严格），latched 在 value==threshold 时却进入了——文案在骗人");
        assertTrue(latched(false, 80.1, 80.0, 5.0),
            "文案写的是 '>'，latched 在 value>threshold 时必须进入");
    }

    // ====================================================================================
    // 4. 中文界面：模板是两份独立字面量，必须两份都对
    // ====================================================================================

    /**
     * 切到中文后，符号同样来自算子表，且中文模板里 CPU 用「进程 / 系统」而不是 process/system。
     *
     * <p>为什么单独测：{@code put(en, zh, key, enText, zhText)} 是两条彼此独立的字符串。
     * 只测英文等于只保护一半——中文那份写错了，中文用户照样被骗。</p>
     */
    @Test
    void chineseTemplatesCarryTheSameSymbols() throws Exception {
        try {
            switchLocale(Messages.LOCALE_ZH);
            AlertServiceStateMachineFixture f = fixtureWithAllThresholds();

            assertRenders(f, "players:global", "warning ≥ 90 / critical ≥ 98",
                ThresholdOperator.PLAYERS);
            assertRenders(f, "memory:global", "warning ≥ 85% / critical ≥ 95%",
                ThresholdOperator.MEMORY);
            assertRenders(f, "entities:global", "warning > 20000 / critical > 30000",
                ThresholdOperator.ENTITIES_GLOBAL);
            assertRenders(f, "tps:global", "warning < 18.0 / critical < 15.0",
                ThresholdOperator.TPS);

            String cpu = thresholdText(f, "cpu:process");
            assertTrue(cpu.contains("进程 > 80.0% / 系统 > 90.0%"), "中文 CPU 文案：" + cpu);
        } finally {
            switchLocale(Messages.LOCALE_EN);
        }
    }

    /**
     * 用户覆盖 {@code messages.yml} 时符号仍然是算子表那一份，不会被覆盖成别的。
     *
     * <p>只断言一件事：模板里<b>没有</b>残留的硬编码符号可供覆盖。做法是确认两条渲染结果
     * 里出现的符号集合恰好等于算子表给出的集合——若模板写死了 {@code ">"}，
     * 把 {@code PLAYERS} 改成闭区间后这里会同时看到 {@code >} 与 {@code ≥} 两种。</p>
     */
    @Test
    void noHardcodedOperatorSurvivesInTheTemplates() throws Exception {
        AlertServiceStateMachineFixture f = fixtureWithAllThresholds();
        String players = thresholdText(f, "players:global");
        String entities = thresholdText(f, "entities:global");

        // players 闭区间 → 只有 ≥；entities 严格 → 只有 >
        assertTrue(players.contains(ThresholdOperator.PLAYERS.warningSymbol()), players);
        assertFalse(players.contains(">"), "players 文案里不该出现 '>'：" + players);
        assertTrue(entities.contains(ThresholdOperator.ENTITIES_GLOBAL.warningSymbol()), entities);
        assertFalse(entities.contains("≥"), "entities 文案里不该出现 '≥'：" + entities);
    }

    // ====================================================================================
    // 夹具
    // ====================================================================================

    /** 七个指标都填上"与默认值一致"的阈值，让渲染结果可以逐字断言。 */
    private static AlertServiceStateMachineFixture fixtureWithAllThresholds() throws Exception {
        AlertServiceStateMachineFixture f = AlertServiceStateMachineFixture.builder().build();
        ConfigManager.ConfigData cfg = f.config();
        cfg.tps_warningThreshold = 18.0;
        cfg.tps_criticalThreshold = 15.0;
        cfg.mspt_warningThreshold = 45.0;
        cfg.mspt_criticalThreshold = 50.0;
        cfg.players_warningThreshold = 90;
        cfg.players_criticalThreshold = 98;
        cfg.entities_totalWarningThreshold = 20_000;
        cfg.entities_totalCriticalThreshold = 30_000;
        cfg.chunks_loadedWarningThreshold = 10_000;
        cfg.chunks_loadedCriticalThreshold = 15_000;
        cfg.memory_heapWarningThreshold = 85;
        cfg.memory_heapCriticalThreshold = 95;
        cfg.cpu_processWarningThreshold = 80.0;
        cfg.cpu_systemWarningThreshold = 90.0;
        return f;
    }

    /** 断言渲染结果逐字等于 {@code expected}，并额外确认符号确实来自算子表而非模板。 */
    private static void assertRenders(AlertServiceStateMachineFixture f, String key,
                                      String expected, ThresholdOperator.Rule rule) throws Exception {
        String actual = thresholdText(f, key);
        assertEquals(expected, actual, key + " 的阈值文案变了");
        assertTrue(actual.contains(rule.warningSymbol()),
            key + " 的文案没带算子表的 warning 符号 \"" + rule.warningSymbol() + "\"：" + actual);
        assertTrue(actual.contains(rule.criticalSymbol()),
            key + " 的文案没带算子表的 critical 符号 \"" + rule.criticalSymbol() + "\"：" + actual);
    }

    /** 直连私有方法 {@code AlertService.thresholdText}：它是纯读配置的叶子方法，无副作用。 */
    private static String thresholdText(AlertServiceStateMachineFixture f, String key) throws Exception {
        Method m = AlertService.class.getDeclaredMethod("thresholdText", String.class);
        m.setAccessible(true);
        String out = (String) m.invoke(f.service(), key);
        assertNotNull(out, "thresholdText 返回 null");
        return out;
    }

    /** 直连 {@code MetricsCollector.latched}：无副作用、不碰 Bukkit。 */
    private static boolean latched(boolean latched, double value, double threshold, double margin)
        throws Exception {
        Method m = MetricsCollector.class.getDeclaredMethod(
            "latched", boolean.class, double.class, double.class, double.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, latched, value, threshold, margin);
    }

    /**
     * 切换界面语言，走真实入口 {@link Messages#load(PerfScope)}。
     *
     * <p>不能直接改私有快照：模块键只会在 {@code load} 里被合并进来，绕过去之后
     * {@code Messages.get} 全部退化成"返回键名"，断言会看不出原因地失败。</p>
     */
    private static void switchLocale(String locale) {
        ConfigManager cm = new ConfigManager(null);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.general_language = locale;
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);

        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        Logger log = Logger.getLogger("pscope-threshold-text-" + locale);
        log.setUseParentHandlers(false);
        log.setLevel(Level.OFF);
        TestObjects.setField(PerfScope.class, plugin, "logger", log);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);

        Messages.load(plugin);
        assertEquals(locale, Messages.activeLocale(), "语言切换失败：" + locale);
    }
}
