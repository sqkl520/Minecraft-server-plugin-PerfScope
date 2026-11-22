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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 占位符解析的边界：<b>前导零不是占位符</b>（P3）。
 *
 * <h2>为什么这件事需要一条测试</h2>
 *
 * <p>{@code {01}} / {@code {00}} 此前被 {@code \{\d+\}} 接受，并按
 * {@link Integer#parseInt} 当成 {@code {1}} / {@code {0}} 替换。于是"写错了"与"写对了"
 * 产出同一份结果——用户永远看不到自己的笔误。</p>
 *
 * <p>更糟的是门禁与实现<b>不同口径</b>：{@code check-i18n.mjs} 的 {@code placeholderSet()}
 * 把 {@code {00}} 与 {@code {0}} 当成两个不同的占位符，于是它会对一段运行时完全正常的文案
 * 报 E-PLACEHOLDER，也可能漏掉真正的缺参。两边同口径之后，"门禁绿 ⇒ 运行时正常"才是真的。</p>
 *
 * <h2>为什么选择"拒绝"而不是"归一化"</h2>
 *
 * <p>完整理由写在 {@code Messages.PLACEHOLDER} 的 javadoc 里。一句话：归一化把一个必然的笔误
 * 变成一次成功的替换，而拒绝让它<b>原样显示在界面上</b>——那正是这个类对
 * {@code {not-a-placeholder}} 这类非占位花括号一直采用的策略（保留原样，一眼可见）。</p>
 */
class PlaceholderLeadingZeroTest {

    /**
     * 注册一条含前导零占位符的键，断言它原样保留。
     *
     * <p>走 {@code registerModule} 而不是反射改快照：这样测的是真实的"注册 → load → get"
     * 链路，而 {@code format()} 是这条链路的最后一站。</p>
     */
    @Test
    @DisplayName("{01} 不被当成 {1}：原样保留")
    void leadingZeroPlaceholderIsNotSubstituted() {
        Map<String, String> en = Map.of("probe.placeholder.leading-zero", "first={01} second={1}");
        Map<String, String> zh = Map.of("probe.placeholder.leading-zero", "第一={01} 第二={1}");
        Messages.registerModule(en, zh);
        Messages.load(null);

        String rendered = Messages.get("probe.placeholder.leading-zero", "A", "B");
        assertEquals("first={01} second=B", rendered,
            "只有 {1} 被替换；{01} 必须原样保留（若它被当成 {1}，结果会是 'first=B second=B'，"
                + "用户永远看不到自己写错了）");
    }

    /** {@code {00}} 同理——它是 {@code {0}} 的笔误，不是"第 0 个参数"的另一种写法。 */
    @Test
    @DisplayName("{00} 原样保留，{0} 正常替换")
    void doubleZeroPlaceholderIsNotSubstituted() {
        Map<String, String> en = Map.of("probe.placeholder.double-zero", "{00} / {0}");
        Map<String, String> zh = Map.of("probe.placeholder.double-zero", "{00} / {0}");
        Messages.registerModule(en, zh);
        Messages.load(null);

        assertEquals("{00} / 7", Messages.get("probe.placeholder.double-zero", 7));
    }

    /**
     * 正常占位符仍然工作——这条是上面两条的反向对照。
     *
     * <p>没有它，把 {@code format()} 整个改成"直接返回模板"也能让上面两条通过。</p>
     */
    @Test
    @DisplayName("反向对照：正常占位符 {0}/{1}/{2} 仍按序号替换")
    void normalPlaceholdersStillSubstitute() {
        Map<String, String> en = Map.of("probe.placeholder.normal", "{0}|{1}|{2}|{10}");
        Map<String, String> zh = Map.of("probe.placeholder.normal", "{0}|{1}|{2}|{10}");
        Messages.registerModule(en, zh);
        Messages.load(null);

        assertEquals("a|b|c|{10}", Messages.get("probe.placeholder.normal", "a", "b", "c"),
            "前三个按序号替换；{10} 越界必须原样保留（不是被截成 {1}+'0'）");
    }

    /**
     * 长得像前导零但实际是合法序号的边界：{@code {0}} 与 {@code {10}}。
     *
     * <p>正则改成 {@code (0|[1-9]\d*)} 之后最容易出现的回归是"把 0 本身也拒掉"。
     * 这条断言 0 仍然可用。</p>
     */
    @Test
    @DisplayName("边界：{0} 合法、{10} 合法（多位数序号不能被前导零规则误伤）")
    void zeroAndMultiDigitIndexesRemainValid() {
        Map<String, String> en = Map.of("probe.placeholder.edge", "{0}-{10}");
        Map<String, String> zh = Map.of("probe.placeholder.edge", "{0}-{10}");
        Messages.registerModule(en, zh);
        Messages.load(null);

        Object[] args = new Object[11];
        for (int i = 0; i < args.length; i++) {
            args[i] = i;
        }
        assertEquals("0-10", Messages.get("probe.placeholder.edge", args),
            "{0} 是第 0 个参数、{10} 是第 10 个；两者都不能被前导零规则误伤");
    }

    /**
     * {@code {00}} 与 {@code {not-a-placeholder}} 走同一条兜底：保留原样。
     *
     * <p>把两件事钉在一起是有意的：它们现在是<b>同一类</b>东西（非占位花括号）。
     * 将来若有人给 {@code {01}} 加"宽容"分支，这条会红，并提醒他
     * {@code {not-a-placeholder}} 也该一起宽容——而后者是绝不能替换的。</p>
     */
    @Test
    @DisplayName("非占位花括号（含前导零）一律原样保留，与 {not-a-placeholder} 同策略")
    void nonPlaceholderBracesAreKeptVerbatim() {
        Map<String, String> en = Map.of("probe.placeholder.verbatim", "{not-a-placeholder} {01} {x}");
        Map<String, String> zh = Map.of("probe.placeholder.verbatim", "{not-a-placeholder} {01} {x}");
        Messages.registerModule(en, zh);
        Messages.load(null);

        String out = Messages.get("probe.placeholder.verbatim", "IGNORED");
        assertEquals("{not-a-placeholder} {01} {x}", out);
        assertTrue(out.contains("{01}"), "前导零占位符必须原样留着，got: " + out);
    }
}
