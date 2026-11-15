/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.config;

import cn.perfscope.support.AlertFixtures;
import cn.perfscope.support.ConfigFixtures;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code HH:mm} 时间解析与校验的契约：{@link ConfigManager#parseHhMmMinutes}（解析器）与
 * {@code ConfigManager#isValidHhMm}（校验器）<b>不是同一个函数</b>。
 *
 * <h2>本类真正锁住的东西：两个合法集不相等</h2>
 * <p>两者被刻意写成了两层，而不是一个方法：</p>
 * <ul>
 *   <li>{@code parseHhMmMinutes} 是<b>解析器</b>——只要能把字符串拆成两个可解析的整数、
 *       且落在 {@code [0,23]} / {@code [0,59]} 内，就返回当日分钟数。它不管字符串<b>长得像不像</b>
 *       时间：{@code Integer.parseInt} 自己就接受 {@code "+8"}、{@code "008"}、{@code " 8 "}，
 *       而 {@code trim} 又把两侧空格吃掉了。</li>
 *   <li>{@code isValidHhMm} 是<b>校验器</b>——在解析器之上再加一道 {@code \d{1,2}:\d{2}} 的
 *       形态约束，只放行"人写在 config.yml 里的那种写法"。{@code "8:00"} 合法（注释明确要容忍单数字小时），
 *       但 {@code "008:00"}、{@code "+8:00"}、{@code "23:0"} 都被正则挡下。</li>
 * </ul>
 * <p>因此存在两个方向的差异，两个方向都<b>必须</b>有断言，否则把任一层删掉都不会变红：</p>
 * <ul>
 *   <li>{@code parse > 0} 而 {@code valid == false}：{@code "+8:00"} / {@code "008:00"} / {@code "23:0"}。</li>
 *   <li>{@code parse == -1} 且 {@code valid == false}：越界时两层都拦（但拦的理由不同——
 *       解析器拦 {@code "24:00"} 是因为小时越界，校验器拦 {@code "080:00"} 是因为形态）。</li>
 * </ul>
 *
 * <h2>为什么不能合并成一个方法</h2>
 * <p>{@code parseHhMmMinutes} 是 {@code public static}，{@link cn.perfscope.alert.DailySummaryService}
 * 在运行期每轮都直接调用它（{@code DailySummaryService.java:246}）。它必须<b>宽松</b>：调用方手上
 * 可能是用户直接传进来的字符串，宽松解析 + {@code -1} 哨兵是唯一能让调用方自行决定
 * "跳过 / 报错"的形态。而配置校验需要<b>严格</b>：它要做的是"把写错的配置挡在启动阶段"，
 * 若用宽松口径，{@code daily-summary.time: "008:00"} 会被静默接受，用户永远不知道自己写的格式不规范。
 * 一个方法无法同时是"宽松解析器"和"严格校验器"，两面的差集正是这套设计的<b>产物</b>而非缺陷。
 * 本类把差集钉死，使得任何"顺手把两层合并"的重构都会立刻变红。</p>
 *
 * <h2>变异靶点（实测过）</h2>
 * <p>把 {@code parseHhMmMinutes} 里 {@code h > 23} 的越界判断拆掉，{@code "99:99"} 会被算成
 * {@code 99*60+99 = 6039}。{@code DailySummaryService} 的触发条件是
 * {@code nowMinutes >= targetMinutes}，而 {@code nowMinutes} 最大 1439，于是摘要<b>永远不会触发</b>，
 * 且没有任何日志——在此之前仓库里 520 个测试全绿。这就是本类必须存在的原因。</p>
 *
 * <p>本类不启动 Bukkit、不读盘：{@code validateThresholds} 的全部副作用都写在传入的
 * {@code ConfigData} 上，{@code plugin} 只为 {@code getLogger()} 存在。</p>
 */
class HhMmTimeParsingTest {

    /** 配置层回退用的默认值（与 {@code ConfigManager} 里硬编码的一致）。 */
    private static final String DEFAULT_TIME = "08:00";

    @BeforeAll
    static void initMessages() {
        AlertFixtures.initMessages();
    }

    // ==================== 工具 ====================

    private static int parse(String value) {
        return ConfigManager.parseHhMmMinutes(value);
    }

    /**
     * 调私有校验器。{@code null} 参数必须包成 {@code (Object) null}，
     * 否则 Java 会把它当成"空 varargs 数组"，方法收到的是缺参而不是 null。
     */
    private static boolean valid(String value) {
        return (Boolean) TestObjects.invoke(ConfigManager.class, "isValidHhMm",
            new Class<?>[]{String.class}, (Object) value);
    }

    // ==================== A. 解析器语义 ====================

    /**
     * 正常值必须映射到正确的"当日分钟数"——这是 {@code DailySummaryService} 比较大小的唯一依据。
     *
     * <p>算错一小时就是 60 分钟，摘要会在错误的时间点发出；{@code "23:59"} 必须等于 1439
     * （当日最后一分钟），否则午夜前一分钟的合法配置会被判成"今天还没到点"，摘要延后一天。</p>
     */
    @Test
    void parsesNormalValues() {
        assertEquals(480, parse("08:00"),
            "'08:00' must be 480 minutes; a wrong offset shifts the daily summary by whole hours");
        assertEquals(0, parse("00:00"),
            "'00:00' must be 0 — the very first minute of the day, not a falsy/invalid sentinel");
        assertEquals(1439, parse("23:59"),
            "'23:59' must be 1439 — the last minute; clamping it lower would push the summary past midnight");
        assertEquals(754, parse("12:34"),
            "'12:34' must be 754 (12*60+34); a swapped hour/minute parse would give 12+34*60=2052");
    }

    /**
     * 单数字小时必须被接受（{@code "8:00"}），这是源码注释明确承诺的容错。
     *
     * <p>用户写 config.yml 时 {@code 8:00} 是很自然的写法；拒绝它会把一个语义完全正确的配置
     * 回退成 {@code "08:00"}，并让服主在日志里看到一条他无法理解的告警。</p>
     */
    @Test
    void acceptsSingleDigitHour() {
        assertEquals(480, parse("8:00"),
            "'8:00' is explicitly documented as tolerated; rejecting it would revert a correct config");
        assertTrue(valid("8:00"),
            "the validator must also accept '8:00' — single-digit hours are legal per the code comment");
    }

    /** 两侧空白必须被 trim 掉——YAML 引号里带空格是常见写法。 */
    @Test
    void trimsSurroundingWhitespace() {
        assertEquals(480, parse(" 08:00 "),
            "leading/trailing spaces from a quoted YAML value must not invalidate an otherwise correct time");
        assertTrue(valid(" 08:00 "),
            "' 08:00 ' must pass validation; users quote values in YAML and spaces are invisible to them");
    }

    /** {@code null} → -1，不是 0 也不是异常。 */
    @Test
    void nullReturnsMinusOne() {
        assertEquals(-1, parse(null),
            "null must yield -1; returning 0 would silently mean 'midnight' and send the summary at 00:00");
    }

    /**
     * 空串 / 纯空白 → -1。
     *
     * <p>空串是"用户把值留空"的典型结果。若返回 0，空配置会变成"每天 00:00 发摘要"；
     * 若抛异常，{@code DailySummaryService.checkCycle} 每轮都会进 catch 刷一条 warning。</p>
     */
    @Test
    void blankReturnsMinusOne() {
        assertEquals(-1, parse(""),
            "an empty string must be -1; 0 would mean midnight and a blank config would send at 00:00");
        assertEquals(-1, parse("   "),
            "whitespace-only must be -1 for the same reason as the empty string");
    }

    /**
     * <b>核心靶点</b>：小时越界必须返回 -1。
     *
     * <p>拆掉 {@code h > 23} 这道闸，{@code "99:99"} 会返回 6039。与 {@code nowMinutes}（≤1439）
     * 比较后永远不成立，每日摘要静默失效——没有任何日志、没有任何异常，服主只会觉得"摘要功能坏了"。</p>
     */
    @Test
    void rejectsHourOutOfRange() {
        assertEquals(-1, parse("24:00"),
            "hour 24 is out of range; accepting it gives 1440, one minute past the end of the day");
        assertEquals(-1, parse("99:99"),
            "hour 99 gives 6039; nowMinutes can never reach it, so the daily summary silently never fires");
        assertEquals(-1, parse("25:00"),
            "hour 25 gives 1500 > 1439; the summary would silently never fire for the whole day");
    }

    /** 分钟越界必须返回 -1；{@code "08:60"} 若被接受会算成 540，即 09:00 前的一小时偏移。 */
    @Test
    void rejectsMinuteOutOfRange() {
        assertEquals(-1, parse("08:60"),
            "minute 60 must be rejected; accepting it yields 540, silently shifting the summary by an hour");
        assertEquals(-1, parse("08:99"),
            "minute 99 must be rejected; 99 minutes is not a wall-clock minute at all");
    }

    /**
     * 负数小时 → -1，而且这条<b>不是</b>靠解析失败拦下的。
     *
     * <p>{@code Integer.parseInt("-1")} 是<b>成功</b>的（返回 -1），所以 {@code "-1:00"} 一定会走到
     * {@code h < 0} 那道闸。若删掉 {@code h < 0}，结果是 {@code -60}，一个负数分钟数：
     * {@code nowMinutes >= -60} 恒真，每日摘要在当天第一轮就发——每天重启就重发一次。</p>
     */
    @Test
    void rejectsNegativeHour() {
        assertEquals(-1, parse("-1:00"),
            "parseInt(\"-1\") succeeds, so this relies on the h<0 guard; without it we get -60, "
                + "which makes nowMinutes>=-60 always true and fires the summary on every reload");
    }

    /** 冒号缺失或位置错误 → -1（注意 {@code ":00"} 的 {@code idx == 0}，{@code "08:"} 的 {@code idx == len-1}）。 */
    @Test
    void rejectsMissingOrMisplacedColon() {
        assertEquals(-1, parse("0800"),
            "no colon at all must be rejected — '0800' has no separator to split hours from minutes");
        assertEquals(-1, parse(":00"),
            "a leading colon leaves the hour substring empty; idx==0 is guarded explicitly");
        assertEquals(-1, parse("08:"),
            "a trailing colon leaves the minute substring empty; idx==len-1 is guarded explicitly");
    }

    /** 非数字 → -1（{@code NumberFormatException} 被 catch，不得泄漏给调用方）。 */
    @Test
    void rejectsNonNumeric() {
        assertEquals(-1, parse("ab:cd"),
            "'ab:cd' must be -1 and must not leak NumberFormatException into checkCycle's catch block");
    }

    /**
     * 多冒号：{@code "08:00:00"} → <b>-1</b>（实测确认）。
     *
     * <p>{@code indexOf(':')} 取的是<b>第一个</b>冒号，于是分钟侧子串是 {@code "00:00"}，
     * {@code Integer.parseInt("00:00")} 抛 {@code NumberFormatException}，被 catch 成 -1。</p>
     *
     * <p><b>这个行为合理吗？</b>结果上合理（{@code "08:00:00"} 确实该被拒），但<b>理由不可靠</b>：
     * 它依赖"分钟子串里恰好还有冒号导致解析失败"。若未来把 {@code Integer.parseInt} 换成
     * 更宽容的解析（例如先 {@code split(':')} 再取前两段），{@code "08:00:00"} 会开始返回 480，
     * 而这条断言的语义就不再被代码保证了。这条断言锁的是<b>当前</b>行为，重构时应当显式讨论。</p>
     */
    @Test
    void multipleColonsAreRejected() {
        assertEquals(-1, parse("08:00:00"),
            "'08:00:00' must be -1; the current reason is that parseInt(\"00:00\") throws, "
                + "which is incidental — if the parser ever splits on ':' this test forces a re-discussion");
    }

    // ==================== B. 解析器合法集 ≠ 校验器合法集 ====================

    /**
     * <b>本测试最有价值的一条</b>：解析器接受、校验器拒绝的形态（实测）。
     *
     * <p>每一条都验证了"两层不可合并"。若把 {@code isValidHhMm} 简化成
     * {@code parseHhMmMinutes(v) >= 0}，下面四个值会在配置层被静默接受，
     * 用户写的格式错误将永不出现在日志里。</p>
     *
     * <ul>
     *   <li>{@code "+8:00"}：{@code Integer.parseInt("+8")} 成功返回 8，但正则 {@code \d{1,2}} 不认 {@code +}。</li>
     *   <li>{@code "8 : 00"}：解析器对两侧各段 {@code trim()}，正则要求冒号紧贴数字。</li>
     *   <li>{@code "23:0"}：分钟位数不足两位，{@code parseInt("0")} 成功，正则要求 {@code \d{2}}。</li>
     *   <li>{@code "08:005"}：{@code parseInt("005")} = 5 → 485，正则只允许两位分钟。</li>
     * </ul>
     *
     * <p>这些值<b>语义上</b>都能被解释成一个时刻，所以"解析器宽松"是合理的；
     * 但它们<b>不是</b> config.yml 承诺的 {@code HH:mm} 形态，所以"校验器严格"也是合理的。
     * 两者的差集正是本类要钉住的契约。</p>
     */
    @Test
    void parserAcceptsFormsTheValidatorRejects() {
        // "+8:00" —— parseInt 接受前导加号
        assertEquals(480, parse("+8:00"),
            "parseInt(\"+8\") == 8, so the parser must yield 480 for '+8:00'");
        assertFalse(valid("+8:00"),
            "the validator must reject '+8:00': \\d{1,2} does not match the leading '+'. "
                + "If this flips to true, config.yml would silently accept a non-HH:mm spelling");

        // "8 : 00" —— 解析器对每段单独 trim
        assertEquals(480, parse("8 : 00"),
            "each side of the colon is trimmed individually, so '8 : 00' parses to 480");
        assertFalse(valid("8 : 00"),
            "'8 : 00' must fail the regex — otherwise the documented HH:mm form is not actually enforced");

        // "23:0" —— 单数字分钟
        assertEquals(1380, parse("23:0"),
            "parseInt(\"0\") == 0, so '23:0' parses to 1380 (23:00)");
        assertFalse(valid("23:0"),
            "a one-digit minute is not HH:mm; the validator's \\d{2} on the minute side is what rejects it");

        // "08:005" —— 三位分钟
        assertEquals(485, parse("08:005"),
            "parseInt(\"005\") == 5, so '08:005' parses to 485");
        assertFalse(valid("08:005"),
            "'08:005' must be rejected by the validator; three-digit minutes are a typo, not a time");
    }

    /**
     * 三位小时：解析器算出 480，校验器的正则把它挡下（实测确认）。
     *
     * <p>这条专门锁住"<b>正则不是装饰</b>"。{@code "008:00"} 在数值上完全等价于 {@code 08:00}，
     * 解析器如实返回 480；但它是用户手滑多打了一个 0 的产物，配置层应当告警而不是接受。
     * 若有人把 {@code isValidHhMm} 里的 {@code matches} 删掉只留 {@code parse >= 0}，
     * 这条会变红——而"数值正确"会掩盖这个错误，所以必须单独成例。</p>
     */
    @Test
    void threeDigitHourPassesParserButFailsValidator() {
        assertEquals(480, parse("008:00"),
            "parseInt(\"008\") == 8, so the parser legitimately yields 480 for '008:00'");
        assertFalse(valid("008:00"),
            "'008:00' must fail validation; \\d{1,2} allows only 1-2 hour digits. "
                + "Without this, a fat-fingered '008:00' would be accepted with no warning at all");
    }

    /** 校验器对 {@code null} 与空白返回 false，而不是抛 NPE。 */
    @Test
    void validatorRejectsNullAndBlank() {
        assertFalse(valid(null),
            "isValidHhMm(null) must be false, not an NPE — validateThresholds relies on this to revert");
        assertFalse(valid(""),
            "isValidHhMm(\"\") must be false; the empty string is the most common 'user left it blank' case");
        assertFalse(valid("   "),
            "isValidHhMm(\"   \") must be false; trim happens first, so this is the empty-string case");
    }

    /**
     * 契约：<b>配置层永远不会把 -1 交给 {@code DailySummaryService}</b>。
     *
     * <p>{@code DailySummaryService.checkCycle}（第 246-249 行）拿到 -1 时的处理是
     * {@code return;}——纯跳过，<b>不写任何日志</b>：</p>
     * <pre>
     *   int targetMinutes = ConfigManager.parseHhMmMinutes(cfg.alerts_dailySummaryTime);
     *   if (targetMinutes &lt; 0) {
     *       return; // 配置校验已回退默认；此处防御
     *   }
     * </pre>
     * <p>也就是说：只要有一份非法值绕过 {@code validateThresholds} 抵达运行期，
     * 表现就是"每日摘要功能凭空消失，日志里一个字都没有"。这个 -1 分支本身是防御性的，
     * 它的<b>正确性完全依赖</b>本测试上面的配置层断言。</p>
     *
     * <p>本测试把这条依赖关系变成可执行的：无论输入多离谱，{@code validateThresholds} 跑完之后
     * {@code parseHhMmMinutes} 都必须是合法值。这样"服务层永远看不到 -1"就不再是注释里的假设。</p>
     */
    @Test
    void configLayerNeverHandsMinusOneToDailySummaryService() {
        String[] hostile = {"25:00", "99:99", "24:00", "", "   ", null, "ab:cd", "0800", "08:60", ":00"};
        for (String raw : hostile) {
            ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
            cfg.alerts_dailySummaryTime = raw;
            ConfigFixtures.validate(ConfigFixtures.manager("pscope-hhmm-contract"), cfg);

            assertTrue(parse(cfg.alerts_dailySummaryTime) >= 0,
                "after validation the value handed to DailySummaryService must parse to >=0 "
                    + "(input was " + raw + "); a -1 there means the summary silently stops "
                    + "forever with no log line at all");
        }
    }

    // ==================== C. 配置校验路径 ====================

    /**
     * 非法格式必须被真实写回默认值 {@code "08:00"}。
     *
     * <p>只断言"产生了一条 warning"是不够的：一个只告警不回退的实现会让 {@code "25:00"} 继续留在
     * 运行期配置里，{@code DailySummaryService} 算出的 1500 分钟与任何时刻都不相等，
     * 每日摘要静默失效。所以这里断言的是<b>字段被改了</b>。</p>
     */
    @Test
    void invalidFormatIsRevertedToDefault() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.alerts_dailySummaryTime = "25:00";
        ConfigFixtures.validate(ConfigFixtures.manager("pscope-hhmm-revert"), cfg);

        assertEquals(DEFAULT_TIME, cfg.alerts_dailySummaryTime,
            "'25:00' must be reverted to \"08:00\"; leaving it in place silently disables the daily "
                + "summary because 1500 minutes is never <= nowMinutes (max 1439)");
    }

    /**
     * 回退<b>不能静默</b>：warning 文案里必须带配置键名。
     *
     * <p>服主排查"摘要为什么不发了"时只有控制台这一条线索。键名缺失（或文案退化成裸键名）
     * 会让这条日志变得无法定位到 config.yml 里的哪一行。这里同时断言文案确实被渲染过
     * （含空格与引号、而不是 {@code config.check.daily-summary-time} 这个键本身），
     * 以免 {@code Messages} 未初始化时测试假绿。</p>
     */
    @Test
    void invalidFormatWarnsWithKeyName() {
        String warnings = ConfigFixtures.warningsOf("pscope-hhmm-warn", cfg -> {
            cfg.alerts_dailySummaryTime = "25:00";
        });

        assertTrue(warnings.contains("alerts.daily-summary.time"),
            "the warning must name the offending key 'alerts.daily-summary.time'; without it the "
                + "operator has no way to find the line to fix. Actual log text: " + warnings);
        assertTrue(warnings.contains("25:00"),
            "the warning must echo the value the user actually wrote ('25:00'), not an already-reverted "
                + "one; otherwise the log cannot tell them what to change. Actual log text: " + warnings);
        assertFalse(warnings.contains("config.check.daily-summary-time"),
            "the message key itself must not appear — that means Messages was not initialised and the "
                + "rendered text is meaningless. Actual log text: " + warnings);
    }

    /**
     * 合法值必须<b>一字不改</b>保留（含 {@code "8:00"} 这种非补零写法）。
     *
     * <p>三个值分别覆盖三种"合法"：日末（{@code 23:59}）、日初（{@code 00:00}，也是 0 分钟这个
     * 容易被当成哨兵的边界）、单数字小时（{@code 8:00}，源码注释承诺容忍的写法）。
     * 特别地，{@code "8:00"} 不得被"规范化"成 {@code "08:00"}——校验器只做判断，不做改写；
     * 改写会让 {@code /perfscope reload} 后的配置与用户文件不一致，且掩盖"这里没被碰过"这一事实。</p>
     */
    @Test
    void legalValuesArePreservedVerbatim() {
        for (String legal : new String[]{"23:59", "00:00", "8:00"}) {
            ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
            cfg.alerts_dailySummaryTime = legal;
            ConfigFixtures.validate(ConfigFixtures.manager("pscope-hhmm-keep"), cfg);

            assertEquals(legal, cfg.alerts_dailySummaryTime,
                "a legal HH:mm value must not be rewritten (value=" + legal + "); "
                    + "re-padding '8:00' to '08:00' would make the running config differ from the file "
                    + "the operator edited");
        }
    }

    /**
     * 合法值不得触发 daily-summary 的 warning（负对照）。
     *
     * <p>没有这一条，上面"非法才回退"的断言可以被一个"每次都回退成 08:00 并告警"的实现满足——
     * 那种实现会把用户写的 {@code 23:59} 悄悄改掉。这里同时兼作 {@link ConfigFixtures#validConfig}
     * 的基线自检：它必须<b>零告警</b>，否则别的测试里捕获到的 warning 文本会混入无关噪音。</p>
     */
    @Test
    void legalValueProducesNoWarning() {
        String warnings = ConfigFixtures.warningsOf("pscope-hhmm-novalidwarn", cfg -> {
            cfg.alerts_dailySummaryTime = "23:59";
        });

        assertFalse(warnings.contains("alerts.daily-summary.time"),
            "a legal '23:59' must produce no warning; if it does, the baseline validConfig() carries "
                + "noise and every other config test's warning assertions become unreliable. "
                + "Actual log text: " + warnings);
    }

    /**
     * {@code null} 也必须回退——{@code readString} 在缺键时理论上给了默认值，
     * 但 {@code ConfigData} 也可能被别的路径（测试、未来的迁移代码）填成 null。
     *
     * <p>关键在于不能抛 NPE：{@code validateThresholds} 在 {@code load()} 中途调用，
     * 一个 NPE 会让整份配置加载失败，服主重启后拿到的是<b>上一版</b>配置——
     * 症状是"改了 config.yml 但 reload 之后完全没生效"，极难归因。</p>
     */
    @Test
    void nullIsAlsoRevertedWithoutNpe() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.alerts_dailySummaryTime = null;
        ConfigFixtures.validate(ConfigFixtures.manager("pscope-hhmm-null"), cfg);

        assertEquals(DEFAULT_TIME, cfg.alerts_dailySummaryTime,
            "a null time must be reverted to \"08:00\"; an NPE here would abort load() halfway and "
                + "leave the server running the previous config, which looks like 'reload did nothing'");
    }
}
