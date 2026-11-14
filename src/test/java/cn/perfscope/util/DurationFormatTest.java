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

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.support.TestObjects;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DurationFormat} 的行为测试。
 *
 * <p>这个类此前<b>零测试</b>，却承担报告 / 趋势 / 诊断 / 告警四处全部时长展示。
 * 它一旦把窗口算错，用户看到的是"3 分钟"而实际是"3 小时"这类<b>静默错误</b>——
 * 不会抛异常、不会有日志，只会让运维对着错数字排查。</p>
 *
 * <h2>实际实现与 javadoc 未必一致，测试以代码为准</h2>
 * <p>写测试前先把 {@code render(millis, firstUnit, window)} 读完，确认了这几条事实
 * （全部由下面的用例锁住，改变它们会红）：</p>
 * <ul>
 *   <li><b>精度窗口是"从最高非零单位起，往后数 N 个单位"</b>，不是"取最高的 N 个非零单位"。
 *       因此 {@code uptime(40d 5h 12m 30s)} 会<b>丢掉</b>秒（窗口 d/h/m 只有三级），
 *       而 {@code uptime(0d 0h 12m 30s)} 能显示秒（起点前移到"分"，窗口内还有三级）。</li>
 *   <li><b>取整方式是向下截断</b>（{@code millis / 1000}），不是四舍五入：
 *       1500ms 显示 {@code 1s}，3599999ms 显示 {@code 59m 59s}。</li>
 *   <li>负数与 {@link Long#MIN_VALUE} 一律按 0 处理（{@code Math.max(0L, millis)}）。</li>
 *   <li>全零输出最小单位的 0（{@code 0s} / {@code 0 秒}），空串会让调用点出现"持续  后恢复"。</li>
 *   <li>语言取自 {@link Messages#activeLocale()}（volatile 快照，每次调用重读），
 *       所以切换语言后同一毫秒数的输出会立刻变。</li>
 *   <li>数字显式用 {@link Locale#ROOT} 格式化——JVM 默认语言换成阿拉伯语也不该出现
 *       阿拉伯-印度数字（数字是数据，必须与日志里的同一数字逐字一致）。</li>
 * </ul>
 *
 * <h2>为什么这里的语言切换必须还原</h2>
 * <p>{@link Messages} 的状态是<b>进程级静态字段</b>，而 surefire 默认 {@code forkCount=1}
 * 且 {@code reuseForks=true}——本类改了语言，同 JVM 里后面跑的测试类都会看到。因此每个用例
 * 结束都还原成 {@code en}，{@code @AfterAll} 再兜一次。切换走的是真实入口
 * {@link Messages#load(PerfScope)}（而不是反射改私有快照），这样"语言 → 输出"这条链是真的。</p>
 */
class DurationFormatTest {

    // ==================== 夹具 ====================

    /**
     * 把界面语言切到 {@code locale}，走真实的 {@link Messages#load(PerfScope)}。
     *
     * <p>为什么需要这一整套：{@code Messages.load} 只有在拿到<b>非 null</b> 的 plugin、
     * 且该 plugin 的 {@code configManager.get()} 非 null 时才会读到 {@code general.language}
     * （见 {@code Messages#readConfiguredLanguage}）。传 {@code load(null)} 只会把快照重置成
     * 内置英文表，模块键全部丢失——那会让同 JVM 的后续测试读到键名。</p>
     *
     * <p>{@code PerfScope} 是 Bukkit 插件类，构造器不可用，故用 {@link TestObjects#allocate}
     * 只注入 {@code logger} 与 {@code configManager} 两个字段（见 {@code TestObjects} 约束二）。</p>
     *
     * <p>{@code load()} 末尾的 {@code applyCommandPermissionMessage} 会因为没有 Bukkit 服务端
     * 而抛 NPE，但它自己 {@code catch (Exception)} 并把原因写进我们注入的 logger——
     * 快照在那一行<b>之前</b>已经发布，所以不影响结果。logger 关掉父 handler 并把级别压到 OFF，
     * 免得每个用例都往 surefire 输出里刷一条 "回写 permissionMessage 失败"。</p>
     */
    private static void switchLocale(String locale) {
        ConfigManager cm = new ConfigManager(null);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.general_language = locale;
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);

        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        Logger log = Logger.getLogger("pscope-durationformat-" + locale);
        log.setUseParentHandlers(false);
        log.setLevel(Level.OFF);
        TestObjects.setField(PerfScope.class, plugin, "logger", log);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);

        Messages.load(plugin);
        // 先把结果断言掉，再让用例跑：否则"语言没切过去"会表现成一大片看不出原因的字符串不等
        assertEquals(locale, Messages.activeLocale(),
            "语言切换失败（Messages.load 没有读到 general.language=" + locale + "）");
    }

    @BeforeAll
    static void useEnglish() {
        TestObjects.assertReflectionUsable();
        switchLocale(Messages.LOCALE_EN);
    }

    @AfterAll
    static void restoreEnglish() {
        // 兜底还原：同 JVM 里的其它测试类默认按英文断言
        switchLocale(Messages.LOCALE_EN);
    }

    // ==================== duration：从"时"起算、窗口两级 ====================

    @Test
    @DisplayName("duration 各单位边界：秒/分/时，且最高单位是时不是天")
    void durationBoundaries() {
        // 不足 1 秒 → 最小单位的 0（不能是空串）
        assertEquals("0s", DurationFormat.duration(0L), "0 毫秒");
        assertEquals("0s", DurationFormat.duration(1L), "1 毫秒向下截断为 0 秒");
        assertEquals("0s", DurationFormat.duration(999L), "999 毫秒向下截断为 0 秒");

        assertEquals("1s", DurationFormat.duration(1_000L));
        assertEquals("59s", DurationFormat.duration(59_999L), "59.999 秒截断为 59 秒");

        assertEquals("1m", DurationFormat.duration(60_000L));
        // 窗口是"分+秒"两级：59m59s 全部显示
        assertEquals("59m 59s", DurationFormat.duration(3_599_000L));

        assertEquals("1h", DurationFormat.duration(3_600_000L));
        // 23h59m59s → 窗口是 h+m，秒被截掉（不是四舍五入，不是进位到 24h）
        assertEquals("23h 59m", DurationFormat.duration(86_399_000L));

        // 关键差异点：duration 的最高单位是"时"，一天显示成 24h 而不是 1d
        assertEquals("24h", DurationFormat.duration(86_400_000L), "duration 不显示天");
        assertEquals("25h 1m", DurationFormat.duration(90_061_000L), "25 小时 1 分 1 秒");
    }

    @Test
    @DisplayName("duration 窗口内的 0 分量不写，但占掉一个窗口位")
    void durationOmitsZeroComponentsInsideWindow() {
        // 1h 0m 5s：窗口 h+m → 分量为 0 被跳过，秒本来就在窗口外
        assertEquals("1h", DurationFormat.duration(3_605_000L));
        // 2m 0s：窗口 m+s → 0 秒被跳过
        assertEquals("2m", DurationFormat.duration(120_000L));
        // 1h 0m 0s
        assertEquals("1h", DurationFormat.duration(3_600_000L));
    }

    // ==================== uptime：从"天"起算、窗口三级 ====================

    @Test
    @DisplayName("uptime 各单位边界：秒/分/时/天")
    void uptimeBoundaries() {
        assertEquals("0s", DurationFormat.uptime(0L));
        assertEquals("0s", DurationFormat.uptime(999L));

        assertEquals("1s", DurationFormat.uptime(1_000L));
        assertEquals("59s", DurationFormat.uptime(59_999L));

        assertEquals("1m", DurationFormat.uptime(60_000L));
        assertEquals("59m 59s", DurationFormat.uptime(3_599_000L));

        assertEquals("1h", DurationFormat.uptime(3_600_000L));
        // 23h59m59s → 窗口 h+m+s 三级，秒仍在窗口内（这条是 duration 与 uptime 的分水岭）
        assertEquals("23h 59m 59s", DurationFormat.uptime(86_399_000L));

        assertEquals("1d", DurationFormat.uptime(86_400_000L));
        assertEquals("1d 1h 1m", DurationFormat.uptime(90_061_000L), "1 天 1 时 1 分 1 秒");
        assertEquals("40d 5h 12m", DurationFormat.uptime(3_474_720_000L));
    }

    @Test
    @DisplayName("uptime 三级窗口会截掉更低单位：40 天 5 时 12 分 1 秒 显示到分")
    void uptimeThreeLevelWindowDropsSeconds() {
        // 3_474_721_000ms = 40d 5h 12m 1s；起点是"天"，窗口天/时/分 → 秒被丢弃
        assertEquals("40d 5h 12m", DurationFormat.uptime(3_474_721_000L));
        // 对照：起点前移到"分"时，三级窗口能覆盖到秒
        assertEquals("12m 1s", DurationFormat.uptime(721_000L));
    }

    @Test
    @DisplayName("uptime 跳过最高位的 0：1 天 0 时 1 分 写成 1d 1m")
    void uptimeOmitsZeroHour() {
        // 86_460_000ms = 1d 0h 1m
        assertEquals("1d 1m", DurationFormat.uptime(86_460_000L));
        // 2d 2h 0m
        assertEquals("2d 2h", DurationFormat.uptime(180_000_000L));
    }

    // ==================== 两个方法的差异 ====================

    @Test
    @DisplayName("同一个毫秒数，duration 与 uptime 的输出必须不同（duration 不出现天）")
    void durationAndUptimeDifferForDayScaleValues() {
        long day = 86_400_000L;
        assertNotEquals(DurationFormat.duration(day), DurationFormat.uptime(day));
        assertEquals("24h", DurationFormat.duration(day));
        assertEquals("1d", DurationFormat.uptime(day));

        long longSpan = 3_474_720_000L;   // 40d 5h 12m
        assertNotEquals(DurationFormat.duration(longSpan), DurationFormat.uptime(longSpan));
        assertFalse(DurationFormat.duration(longSpan).contains("d"),
            "duration 的输出不该包含天单位: " + DurationFormat.duration(longSpan));
        assertEquals("965h 12m", DurationFormat.duration(longSpan));
        assertTrue(DurationFormat.uptime(longSpan).contains("d"));
    }

    // ==================== 负数 / 溢出 ====================

    @Test
    @DisplayName("负数与 Long.MIN_VALUE 按 0 处理，绝不出现负号")
    void negativeValuesClampToZero() {
        assertEquals("0s", DurationFormat.duration(-1L));
        assertEquals("0s", DurationFormat.duration(-1_000L));
        assertEquals("0s", DurationFormat.duration(-100_000L));
        assertEquals("0s", DurationFormat.uptime(-1L));
        assertEquals("0s", DurationFormat.uptime(-100_000L));
        // 极端：Math.max(0L, Long.MIN_VALUE) 必须挡住，否则拆分会出现巨大的负数分量
        assertEquals("0s", DurationFormat.duration(Long.MIN_VALUE));
        assertEquals("0s", DurationFormat.uptime(Long.MIN_VALUE));
    }

    @Test
    @DisplayName("Long.MAX_VALUE 不溢出成负数，且分量算得准")
    void longMaxValueDoesNotOverflow() {
        // Long.MAX_VALUE = 9223372036854775807ms → 9223372036854775 秒
        String d = DurationFormat.duration(Long.MAX_VALUE);
        String u = DurationFormat.uptime(Long.MAX_VALUE);
        assertFalse(d.contains("-"), "duration(Long.MAX_VALUE) 出现了负号: " + d);
        assertFalse(u.contains("-"), "uptime(Long.MAX_VALUE) 出现了负号: " + u);
        // 9223372036854775s = 2562047788015h 12m 55s（窗口 h+m）
        assertEquals("2562047788015h 12m", d);
        // = 106751991167d 7h 12m 55s（窗口 d+h+m）
        assertEquals("106751991167d 7h 12m", u);
    }

    @Test
    @DisplayName("接近 Long.MAX_VALUE 的秒级值同样不溢出")
    void nearMaxValueStaysPositive() {
        // 1e18 ms ≈ 3.17e7 年：仍在 long 安全区内，只是数字很长
        String out = DurationFormat.uptime(1_000_000_000_000_000_000L);
        assertFalse(out.startsWith("-"), "不应为负: " + out);
        // 1e18ms = 1e15 秒 = 11574074074d 1h 46m
        assertEquals("11574074074d 1h 46m", out);
    }

    // ==================== 取整方式 ====================

    @Test
    @DisplayName("精度是向下截断而不是四舍五入")
    void truncatesInsteadOfRounding() {
        // 1.5 秒 → 1 秒（若是四舍五入会是 2s）
        assertEquals("1s", DurationFormat.duration(1_500L));
        assertEquals("1s", DurationFormat.duration(1_999L));
        assertEquals("1s", DurationFormat.uptime(1_500L));
        // 59.999 秒 → 59 秒，不会进位成 "1m"
        assertEquals("59s", DurationFormat.duration(59_999L));
        // 3_599_999ms = 59m 59.999s → 59m 59s，不会进位成 "1h"
        assertEquals("59m 59s", DurationFormat.duration(3_599_999L));
        assertEquals("59m 59s", DurationFormat.uptime(3_599_999L));
        // 23:59:59.999 仍是 23h 59m，不会进位成 1d
        assertEquals("23h 59m", DurationFormat.duration(86_399_999L));
        assertEquals("23h 59m 59s", DurationFormat.uptime(86_399_999L));
    }

    // ==================== i18n ====================

    @Test
    @DisplayName("中文界面：单位是中文且在数字后加空格")
    void chineseLocaleUsesChineseUnitsWithSpace() {
        try {
            switchLocale(Messages.LOCALE_ZH);
            // 0 显示最小单位的 0：调用点要能拼出"持续 0 秒 后恢复"
            assertEquals("0 秒", DurationFormat.duration(0L));
            assertEquals("59 秒", DurationFormat.duration(59_999L));
            assertEquals("1 分", DurationFormat.duration(60_000L));
            assertEquals("59 分 59 秒", DurationFormat.duration(3_599_000L));
            // duration 不显示天：25 小时 1 分（时分之间的空格只有一处，单位前各一处）
            assertEquals("25 时 1 分", DurationFormat.duration(90_061_000L));
            assertEquals("24 时", DurationFormat.duration(86_400_000L));

            assertEquals("1 天", DurationFormat.uptime(86_400_000L));
            assertEquals("1 天 1 时 1 分", DurationFormat.uptime(90_061_000L));
            assertEquals("40 天 5 时 12 分", DurationFormat.uptime(3_474_720_000L));
            assertEquals("1 天 1 分", DurationFormat.uptime(86_460_000L), "0 时 被跳过");
        } finally {
            switchLocale(Messages.LOCALE_EN);
        }
    }

    @Test
    @DisplayName("英文界面不插空格、中文界面插空格")
    void localeChangesSeparatorOnly() {
        assertEquals("1h 3m", DurationFormat.duration(3_780_000L));
        try {
            switchLocale(Messages.LOCALE_ZH);
            assertEquals("1 时 3 分", DurationFormat.duration(3_780_000L));
        } finally {
            switchLocale(Messages.LOCALE_EN);
        }
    }

    @Test
    @DisplayName("数字用 Locale.ROOT：JVM 默认语言是阿拉伯语时仍是 ASCII 数字")
    void digitsStayAsciiUnderForeignDefaultLocale() {
        // String.format("%d") 在 ar-EG 下会输出阿拉伯-印度数字（٠١٢…）。时长里的数字是数据，
        // 必须与日志/报告里的同一数字逐字一致，所以实现显式用了 Locale.ROOT。
        // 这条用例是那个决定的唯一守门人：去掉 Locale.ROOT 就会红。
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertEquals("1s", DurationFormat.duration(1_000L));
            assertEquals("1h 3m", DurationFormat.duration(3_780_000L));
            assertEquals("1d 1h 1m", DurationFormat.uptime(90_061_000L));
        } finally {
            Locale.setDefault(original);
        }
    }

    // ==================== 稳定性 ====================

    @Test
    @DisplayName("全零/极短输入的输出永不为空串或纯空白")
    void neverReturnsBlank() {
        long[] inputs = {0L, 1L, 999L, -1L, Long.MIN_VALUE, Long.MAX_VALUE};
        for (long ms : inputs) {
            assertFalse(DurationFormat.duration(ms).isBlank(), "duration(" + ms + ") 为空");
            assertFalse(DurationFormat.uptime(ms).isBlank(), "uptime(" + ms + ") 为空");
        }
    }
}
