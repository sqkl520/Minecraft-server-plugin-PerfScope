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

import java.util.Locale;

/**
 * 时长 / 运行时间的唯一格式化实现（Wave 9D 收口）。
 *
 * <p><b>为什么要有这个类</b>：v1.2 之前同一件事有五份各自为政的实现——{@code AlertService}、
 * {@code DailySummaryService}、{@code PerfScopeCommand}（两份）、{@code ReportGenerator}。
 * 它们不仅单位写法不同（"分钟" vs "分"、"小时" vs "时"），连"什么时候进位"都不一致：
 * 同样是 3 分 12 秒，告警说"3 分 12 秒"，{@code status} 说"3 分 12 秒"，
 * 而 3 分整的时候告警说"3 分钟"、{@code status} 说"3 分 0 秒"。用户在同一屏里看到
 * "1 小时 0 分"和"1 小时"两种写法，会怀疑数据不一致。更关键的是五份实现<b>全部硬编码中文</b>：
 * {@code general.language=en} 之后，界面是英文而时长仍是"3 分 12 秒"——这正是 9D 要消灭的混排。</p>
 *
 * <p><b>两个方法的分工</b>（不是"短/长"之分，而是"精度窗口"之分）：</p>
 * <ul>
 *   <li>{@link #duration(long)}：事件持续时长（告警持续、采集停滞）。最多两级，
 *       所以最多到"时"——跑了一天的告警显示"25 小时"而不是"1 天 1 时"，
 *       因为读者关心的是"多久没恢复"，不是"跨了几天"（与旧 {@code AlertService} 口径一致）。</li>
 *   <li>{@link #uptime(long)}：服务器运行时间。最多三级，从"天"起算。
 *       运行时间动辄数天，若不从"天"起算，"127 小时"没人愿意心算。</li>
 * </ul>
 *
 * <p><b>省略规则</b>：只从"最高非零单位"开始取窗口（{@code 0 天 0 时 5 分} 写成 {@code 5 分}），
 * 窗口内为 0 的单位也不写（{@code 1 天 0 时 12 分} 写成 {@code 1 天 12 分}）。全部为 0 时
 * 输出最小单位的 0（{@code 0 秒} / {@code 0s}）——空串会让调用点出现"持续  后恢复"这种断句。
 * 负数与溢出值一律按 0 处理：调用点传入的是 {@code now - firstSeen}、{@code staleForMs()} 这类
 * 时间差，时钟回拨或 {@code -1} 哨兵都可能出现，展示层绝不能因此抛异常或打出"-3 秒"。</p>
 *
 * <p><b>为什么用 {@link Locale#ROOT} 格式化数字</b>：{@code String.format} 的 {@code %d}
 * 在部分语言环境下会输出本地数字字形（如阿拉伯语环境的阿拉伯-印度数字）。时长里的数字是
 * <b>数据</b>不是文案——它必须与日志、报告、监控系统里的同一数字长得一样，否则运维没法搜、
 * 没法比对。文案部分（单位词）才跟随 {@link Messages#activeLocale()}。</p>
 *
 * <p><b>线程模型</b>：无状态纯函数，不缓存语言（每次读 {@code Messages.activeLocale()} 的
 * volatile 快照），因此 reload 切换语言后立即生效，且可被采集线程、告警线程、报告线程同时调用。</p>
 */
public final class DurationFormat {

    /** 单位由大到小：天 / 时 / 分 / 秒（索引即 {@link #splitSeconds} 的分量下标）。 */
    private static final String[] ZH_UNITS = {"天", "时", "分", "秒"};

    /** 英文单位；与 {@link #ZH_UNITS} 一一对应。 */
    private static final String[] EN_UNITS = {"d", "h", "m", "s"};

    /** 中文单位与数字之间的空格（"3 分"）；英文不隔（"3m"），差异由 {@link #render} 统一处理。 */
    private static final char SEPARATOR = ' ';

    /** 各单位的秒数，与 {@link #ZH_UNITS} 同序；只用于拆分，不做乘除运算，故不参与溢出讨论。 */
    private static final long[] SECONDS_PER_UNIT = {86_400L, 3_600L, 60L, 1L};

    private DurationFormat() {}

    /**
     * 事件持续时长：最多两级，最多到"小时"。
     *
     * <p>用于"持续 X"这类修饰语（告警持续、恢复耗时、采集停滞），示例：
     * {@code 12 秒} / {@code 3 分 12 秒} / {@code 1 小时 3 分}；
     * 英文 {@code 12s} / {@code 3m 12s} / {@code 1h 3m}。</p>
     *
     * @param millis 毫秒；负数按 0 处理
     */
    public static String duration(long millis) {
        return render(millis, 1, 2);   // 从"时"起算、窗口两级：时+分 / 分+秒 / 秒
    }

    /**
     * 服务器运行时间：最多三级，从"天"起算。
     *
     * <p>示例：{@code 2 天 5 时 12 分} / {@code 5 时 12 分 30 秒} / {@code 12 分 30 秒}；
     * 英文 {@code 2d 5h 12m} / {@code 5h 12m 30s} / {@code 12m 30s}。</p>
     *
     * @param millis 毫秒；负数按 0 处理
     */
    public static String uptime(long millis) {
        return render(millis, 0, 3);   // 从天起算、窗口三级
    }

    /**
     * 差值时长：从"天"起算，只写<b>前 N 个非零</b>单位。
     *
     * <p><b>为什么不是直接复用 {@link #uptime}</b>：{@code uptime} 的窗口按"级"数（天/时/分），
     * 于是 {@code 1 天 0 时 0 分 3 秒} 会写成 {@code 1d}——秒被窗口截掉。而这里要的是
     * "1 天 3s" 这种形态：读者关心差值的量级，越靠前的非零单位越有信息量，尾部的 0 级只是噪音。
     * 反过来写成 {@code 1 天 0 时 0 分 3 秒} 又会让"精度很高"成为假象。两种窗口口径不同，
     * 所以这里是独立入口，而不是给 {@code uptime} 加参数。</p>
     *
     * <p><b>为什么需要它（而不是让调用方自己拼）</b>：{@code SummaryPublisher} 的时钟回拨告警
     * 此前自带一份 {@code {" 天"," 小时"," 分"," 秒"}} 的中文单位表，注释写着"不做 i18n"。
     * 但那段片段是被拼进一条<b>已经本地化</b>的句子的，英文界面下必然混排
     * （"系统时钟回拨 1 小时"整个出现在英文日志里）。单位表只允许有一份，就在这里。</p>
     *
     * <p>示例（{@code maxUnits = 2}）：{@code 1 天 2 时} / {@code 23 分} / {@code 3 秒} / {@code 0 秒}；
     * 英文 {@code 1d 2h} / {@code 23m} / {@code 3s} / {@code 0s}。</p>
     *
     * @param millis   毫秒；负数按 0 处理
     * @param maxUnits 最多写几个非零单位（至少按 1 处理）
     */
    public static String elapsed(long millis, int maxUnits) {
        int limit = Math.max(1, maxUnits);
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long[] parts = splitSeconds(totalSeconds, 0);   // 从天起算：差值也可能跨天
        boolean zh = Messages.LOCALE_ZH.equals(Messages.activeLocale());

        StringBuilder sb = new StringBuilder(24);
        int taken = 0;
        for (int i = 0; i < parts.length && taken < limit; i++) {
            if (parts[i] == 0L) {
                continue;   // 跳过 0 分量，而不是让它占掉一个名额
            }
            if (taken > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(number(parts[i])).append(unit(zh, i));
            taken++;
        }
        // 全零（或不足 1 秒）：必须给一个数，不能返回空串——
        // 调用点会拼出"检测到时钟回拨  后…"这种断句，比不写还难读。
        return sb.length() == 0 ? number(0L) + unit(zh, 3) : sb.toString();
    }

    /**
     * 公共渲染：拆分量 → 定位最高非零单位 → 取精度窗口 → 逐项拼接。
     *
     * @param firstUnit 允许的最高单位下标（{@code duration} 传 1 = 时，{@code uptime} 传 0 = 天）
     * @param maxUnits  窗口内最多浏览几个单位（含被跳过 0 的位置）
     */
    private static String render(long millis, int firstUnit, int maxUnits) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long[] parts = splitSeconds(totalSeconds, firstUnit);

        // 跳过最高位的 0：0 天 0 时 5 分 → 从"分"开始，而不是写成 "0 天 0 时 5 分"
        int start = firstUnit;
        while (start < parts.length && parts[start] == 0L) {
            start++;
        }
        boolean zh = Messages.LOCALE_ZH.equals(Messages.activeLocale());
        if (start == parts.length) {
            // 全为 0（或不足 1 秒）：给最小单位一个 0，调用点才不会出现"持续  后恢复"
            return number(0L) + unit(zh, 3);
        }

        StringBuilder sb = new StringBuilder(24);
        int end = Math.min(parts.length, start + maxUnits);
        boolean first = true;
        for (int i = start; i < end; i++) {
            if (parts[i] == 0L) {
                continue;   // 窗口内的 0 不写：1 天 0 时 12 分 → "1 天 12 分"
            }
            if (!first) {
                sb.append(SEPARATOR);
            }
            sb.append(number(parts[i])).append(unit(zh, i));
            first = false;
        }
        return sb.toString();
    }

    /**
     * 秒 → 各分量（只填 {@code firstUnit} 及之后的单位，更粗的单位留在 0）。
     *
     * <p><b>必须从 {@code firstUnit} 开始除</b>，而不是永远从天开始再取模：
     * {@code duration} 的最高单位是"时"，若先算天，「1 天 1 时」就会被拆成 {@code days=1} 而
     * 时分量只剩 1，最终显示"1 时"——把 25 小时说成 1 小时。从选中单位起算，时分量自然是
     * 总小时数（25），与旧 {@code AlertService} 的 {@code "25 小时"} 一致。</p>
     */
    private static long[] splitSeconds(long totalSeconds, int firstUnit) {
        long[] parts = new long[SECONDS_PER_UNIT.length];
        long rest = totalSeconds;
        for (int i = firstUnit; i < parts.length; i++) {
            parts[i] = rest / SECONDS_PER_UNIT[i];
            rest %= SECONDS_PER_UNIT[i];
        }
        return parts;
    }

    /**
     * 数字 → 文本：显式 {@link Locale#ROOT}，不让 JVM 默认语言把数字换成别的字形
     * （时长里的数字是数据，必须与日志/报告里的同一数字逐字一致）。
     */
    private static String number(long value) {
        return String.format(Locale.ROOT, "%d", value);
    }

    /** 数字 + 单位：中文插空格（"3 分"），英文不插（"3m"）。 */
    private static String unit(boolean zh, int index) {
        return zh ? SEPARATOR + ZH_UNITS[index] : EN_UNITS[index];
    }
}
