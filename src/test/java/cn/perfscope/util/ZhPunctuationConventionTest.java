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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中文标点：<b>单个文案值内部不得半角/全角冒号混用</b>。
 *
 * <h2>为什么只锁"混用"，不做全局统一（P2 的决定与理由）</h2>
 *
 * <p>实测统计（815 键中文表，统计于 i18n 收口批次；合并后的总数已增至 826，但结论不变）：
 * 半角 {@code :} 与全角 {@code ：} 都大量存在，且按<b>族</b>聚集：</p>
 * <ul>
 *   <li>{@code config.check.*} —— 34 全角 / 1 半角（中文句子里的句读，全角是排版正解）；</li>
 *   <li>{@code cmd.webhook.*} —— 7 全角 / 0 半角；</li>
 *   <li>{@code report.html.*} / {@code cmd.all.*} / {@code cmd.memory.*} 等 —— 全半角混排正是
 *       "标签 + 数值"的形态（{@code 内存: 45%}），值是 ASCII，半角冒号在这里是<b>技术分隔符</b>；</li>
 *   <li>{@code cmd.status.export.*} —— 4 全角（"启动失败：{0}"，纯中文句子）。</li>
 * </ul>
 *
 * <p>统一成全角会破坏三件事，代价远大于收益：</p>
 * <ol>
 *   <li><b>调用点拼接约定</b>：{@code report-generated} 的 zh 值是 {@code "报告已生成: "}，
 *       调用点直接 {@code get("report-generated") + path}；末尾那个半角冒号 + 空格是路径的粘合符。
 *       改全角后变成 {@code 报告已生成：/path}，中英两语言的"冒号后无空格"约定就分叉了。</li>
 *   <li><b>用户已有的 {@code messages.yml} 覆盖</b>：覆盖按 key 整体替换值，所以改内置值不会
 *       坏掉用户的自定义文案；但改完之后"内置值与用户值"的标点会分叉，用户下次对照模板改文案
 *       时会看到一个与他文件里不同的冒号，反而更容易写错。</li>
 *   <li><b>testharness 断言</b>：{@code perfscope-surface.json} / {@code perfscope-alert.json} /
 *       {@code perfscope-history.json} 等 9 处按字面量断言 {@code 总实体数:} / {@code 报告已生成:} /
 *       {@code 最低 TPS:} / {@code 覆盖: 近}。批量替换会一次性打断这些跨仓库断言，
 *       而它们守护的是"模块降级不要假绿"这类真问题。</li>
 * </ol>
 *
 * <p>所以决定是：<b>不统一</b>，只消除"同一个值里两种冒号并存"——那种才是明显的笔误
 * （读者会在同一句里看到两种宽度）。这条测试把"混用"钉住，剩下的族内一致性交给代码评审。</p>
 */
class ZhPunctuationConventionTest {

    /** 中文（CJK）紧跟半角冒号。 */
    private static final Pattern CJK_HALF = Pattern.compile("[\\u4e00-\\u9fff]:");

    /** 中文（CJK）紧跟全角冒号。 */
    private static final Pattern CJK_FULL = Pattern.compile("[\\u4e00-\\u9fff]：");

    @BeforeAll
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
     * 同一条中文文案里不得同时出现"中文+半角冒号"与"中文+全角冒号"。
     *
     * <p>只统计<b>紧跟中文</b>的冒号：{@code key=value}、{@code http://...}、
     * {@code {0}: {1}} 这类"冒号前后是 ASCII"的位置属于技术分隔符，与中文排版无关。</p>
     */
    @Test
    @DisplayName("中文文案值内部不混用半角与全角冒号")
    void noKeyMixesHalfWidthAndFullWidthColonInsideOneValue() {
        List<String> offenders = new ArrayList<>();
        for (String key : Messages.keys()) {
            String zh = Messages.builtin(Messages.LOCALE_ZH, key);
            if (zh == null) continue;
            boolean half = CJK_HALF.matcher(zh).find();
            boolean full = CJK_FULL.matcher(zh).find();
            if (half && full) {
                offenders.add(key + " => " + zh);
            }
        }
        assertTrue(offenders.isEmpty(),
            "同一个值里半角与全角冒号并存，读者会在同一句里看到两种宽度。共 "
                + offenders.size() + " 条：\n  " + String.join("\n  ", offenders));
    }

    /**
     * 反过来钉住"不要顺手把半角批量改成全角"这件事的<b>一个具体理由</b>：
     * {@code report-generated} 的中文值必须以半角冒号加空格结尾。
     *
     * <p>它不是审美选择，而是调用点契约：{@code PerfScopeCommand} 直接把它与报告路径做字符串
     * 相加。改成全角之后，中英两种语言的"冒号后是否带空格"就分叉了（英文 {@code ": "}、
     * 中文 {@code "："}），后续任何按冒号切分的处理都会在两个语言下行为不同。</p>
     */
    @Test
    @DisplayName("report-generated 的 zh 值保持半角冒号+空格（调用点拼接契约）")
    void reportGeneratedKeepsHalfWidthTrailingSeparator() {
        String zh = Messages.builtin(Messages.LOCALE_ZH, "report-generated");
        assertTrue(zh != null && zh.endsWith(": "),
            "中文值必须以 \": \" 结尾，调用点直接拼路径；got: " + zh);
    }
}
