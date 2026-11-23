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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中文文案的<b>术语一致性</b>（P2 任务 4）。
 *
 * <h2>锚点必须与确定的事实比较，而不是与"另一处文案"比较</h2>
 *
 * <p>最初的想法是"把 {3} 处 'Tile Entity' 全部改成一致"。但那类断言只能写成
 * "A 等于 B"，而 A 与 B 同时错（比如整批改成另一种拼法）时测试照样绿——
 * 等于什么也没锁住（本仓库 {@code check-scenarios} 的 S1 规则的注释里记着同一类教训）。</p>
 *
 * <p>所以判据换成两条与外部事实比较的：
 * <ul>
 *   <li><b>中文值里不得出现 ASCII 的 "Tile Entity" / "TileEntity"</b>。这是"中文界面里夹英文术语"
 *       的直接判据，且与具体中文译名无关——译名以后再改也不会让这条失效。</li>
 *   <li><b>中文值里的方块实体术语只有一个写法</b>（当前是「方块实体」）。做法是统计全部
 *       含该术语的键，确认它们用的都是同一个词形，而不是"四种拼法各写一遍"。</li>
 * </ul>
 *
 * <p>引入本次断言的动机是真实的：{@code cmd.world.tile-entities-label} 的中文值曾是
 * {@code "Tile Entity: "}（整个是英文），而同一个插件里另有 {@code "总 Tile Entity: "}、
 * {@code "Tile Entity 统计"}、{@code "TileEntity"} 三种写法。</p>
 */
class ZhTerminologyConsistencyTest {

    /**
     * 本类要读到<b>合并后</b>的表（{@code cmd.*} / {@code report.*} 都在模块表里，
     * 静态块的 85 个基础键里没有它们）。
     *
     * <p>{@code Messages.load(null)} 现在会 touch + merge（P1 修复），但 {@code touchModules()}
     * 依赖 {@code onFirstLoad} 的登记表——测试里没有启动流程，那张表是空的。
     * 所以像 {@code MessagesLoadNullTest} 一样显式 {@code init()} 每个模块。</p>
     */
    @org.junit.jupiter.api.BeforeAll
    static void loadMessages() {
        cn.perfscope.CoreMessages.init();
        cn.perfscope.RuntimeMessages.init();
        cn.perfscope.alert.AlertMessages.init();
        cn.perfscope.diagnose.DiagnoseMessages.init();
        cn.perfscope.command.CommandMessages.init();
        cn.perfscope.report.ReportMessages.init();
        cn.perfscope.config.ConfigMessages.init();
        cn.perfscope.network.NetworkMessages.init();
        Messages.load(null);
    }

    /**
     * 中文值里不允许出现的英文术语（作为<b>单词</b>出现时）。
     *
     * <p>只列"出现在中文句子里就是混排"的那些。刻意<b>不</b>列 {@code TPS} / {@code MSPT} /
     * {@code CPU} / {@code GC} / {@code webhook} / {@code server-id} 这类项目里本来就保留原文的
     * 标识符——RuntimeMessages 的类注释明确说过"不翻译的内容"包括模块 id 与配置键。</p>
     *
     * <p>三类合法用法必须排除，否则正确文案会变红（第一版全踩了）：</p>
     * <ol>
     *   <li><b>命令名</b>：{@code /perfscope tileentities [世界]} —— 子命令名不翻译，
     *       翻了用户就敲不出来；</li>
     *   <li><b>Bukkit/Paper 方法名</b>：{@code getTileEntityCount} —— 服主要靠它搜 API；</li>
     *   <li><b>模块 id</b>：{@code tile_entities}（快照 unavailable 集合的键）。</li>
     * </ol>
     */
    private static final Pattern ASCII_TERM_IN_ZH = Pattern.compile(
        "(?<![A-Za-z0-9_/])(?:[Tt]ile ?[Ee]ntit(?:y|ies))(?![A-Za-z0-9_])");

    /** 命令名 {@code tileentities}（无空格、无大写）也是合法用法——它是子命令本身。 */
    private static final Pattern COMMAND_TOKEN = Pattern.compile("(?<=/perfscope )tileentities\\b");

    /**
     * 中文值的方块实体术语只能有一个写法。
     *
     * <p>{@code commands}/{@code reports} 两侧都查：它们是同一屏里的相邻行，
     * 一处写「方块实体」另一处写「区块实体」时用户会以为是两个不同指标。</p>
     */
    @Test
    @DisplayName("中文值里不出现独立写出的 ASCII Tile Entity / TileEntity")
    void chineseValuesDoNotCarryAsciiTileEntity() {
        List<String> offenders = new ArrayList<>();
        for (String key : Messages.keys()) {
            String zh = zhValueOf(key);
            if (zh == null) continue;
            // 先剥掉合法的命令名与模块 id，再判术语：那些是本该保留原文的标识符。
            String scrubbed = COMMAND_TOKEN.matcher(zh).replaceAll("<cmd>");
            java.util.regex.Matcher m = ASCII_TERM_IN_ZH.matcher(scrubbed);
            if (m.find()) {
                offenders.add(key + " => " + zh);
            }
        }
        assertTrue(offenders.isEmpty(),
            "中文界面里夹了 ASCII 术语（中文服主看到的是半英半中）。共 " + offenders.size()
                + " 条：\n  " + String.join("\n  ", offenders));
    }

    /**
     * 曾被写成英文的那个具体键：{@code cmd.world.tile-entities-label} 的中文值必须是中文。
     *
     * <p>单独点名它是因为它最典型——值原本是 {@code "Tile Entity: "}，连冒号后面的空格
     * 都是英文排版。泛化的判据（上一条）已经能抓住它，这里再加一条是因为
     * <b>它在 /perfscope world 的每一屏都会出现</b>，退化代价最高。</p>
     */
    @Test
    @DisplayName("cmd.world.tile-entities-label 的中文值以「方块实体」开头并以中文冒号结尾")
    void worldTileEntitiesLabelIsChinese() {
        String zh = zhValueOf("cmd.world.tile-entities-label");
        assertNotEquals(null, zh, "该键必须有中文值（check-i18n 的键集合一致性已保证，这里是双保险）");
        assertTrue(zh.startsWith("方块实体"),
            "中文值必须以「方块实体」开头；got: " + zh);
        assertTrue(zh.endsWith("：") || zh.endsWith(": "),
            "中文标签的结尾应保持与调用点拼接约定一致（全角冒号，或全角化之前的半角+空格）；got: " + zh);
    }

    /**
     * 方块实体术语在中文值里只有一种写法。
     *
     * <p><b>判据刻意只认"方块实体这一类术语"</b>，不认所有 "X 实体"：{@code 世界实体}
     * （每个世界的实体数）、{@code 实体总数}、{@code 掉落物实体} 都是不同指标，
     * 把它们算进"术语"会让本用例在正确的文案上变红（第一版就是这样失败的）。</p>
     *
     * <p>做法：抓住"实体"前面那一段<b>且整段属于方块实体术语</b>（{@code 方块} / ASCII 的
     * {@code Tile}/{@code TE}）的出现，收集词形并断言只有一个。写死成"方块实体"会让译名
     * 将来无法演进；比较"词形集合大小"则只锁一致性，不锁具体译名。</p>
     */
    @Test
    @DisplayName("方块实体术语在中文文案里只有一个词形")
    void tileEntityTermHasOneSpellingInChinese() {
        // 只匹配"作为术语词使用"的形态。三类合法用法必须排除（见 ASCII_TERM_IN_ZH 的注释）：
        // 命令名、Bukkit 方法名、模块 id。
        Pattern termPattern = Pattern.compile(
            "(方块实体|(?<![A-Za-z0-9_/])(?:[Tt]ile ?[Ee]ntit(?:y|ies))(?![A-Za-z0-9_]))");
        java.util.Set<String> forms = new java.util.TreeSet<>();
        List<String> samples = new ArrayList<>();

        for (String key : Messages.keys()) {
            String zh = zhValueOf(key);
            if (zh == null) continue;
            String scrubbed = COMMAND_TOKEN.matcher(zh).replaceAll("<cmd>");
            java.util.regex.Matcher m = termPattern.matcher(scrubbed);
            while (m.find()) {
                // 归一化空白：只比较"用了哪个词"，不比较排版
                forms.add(m.group().replaceAll("\\s+", " ").trim());
                samples.add(key + " => " + m.group());
            }
        }

        assertFalse(forms.isEmpty(),
            "没找到任何方块实体术语 —— 判据本身失效了（源码里明明有这些键）");
        assertTrue(forms.size() == 1,
            "方块实体术语出现了 " + forms.size() + " 种词形 " + forms
                + "，同一屏里会像两个不同指标。样例：\n  " + String.join("\n  ", samples));
    }

    /**
     * 取某键的中文内置值。
     *
     * <p>不能用 {@code Messages.get(key)}：那只返回<b>当前语言</b>的值，而本类要的是中文那一份
     * （与当前语言无关）。{@link Messages#builtin(String, String)} 就是为此存在：
     * 它按语言取内置默认值，绕过用户覆盖，也不依赖当前生效语言。</p>
     */
    private static String zhValueOf(String key) {
        return Messages.builtin(Messages.LOCALE_ZH, key);
    }
}
