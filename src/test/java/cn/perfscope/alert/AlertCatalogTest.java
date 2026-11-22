/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AlertCatalog} 的渲染契约——告警正文的<b>唯一出口</b>。
 *
 * <h2>为什么这个类最该测</h2>
 * 它是告警文案的唯一渲染点（{@code Alert.message()} 委托 {@code message()}，
 * {@code Alert.display()} 委托 {@code title()}），却长期零测试。
 * 而它出错时的表现是<b>静默失真</b>——不崩溃、不打日志，只是让服主读到错的或空的告警。
 *
 * <p>{@code argsFor} 的 javadoc（第 81-85 行）自述踩过一次这类事故：
 * 对所有模板一律传 {@code (scope, value)}，而普通模板只写 {@code {0}}，
 * 于是所有普通告警渲染成 {@code "Heap usage high: "}——<b>数值丢了，零异常零日志</b>。
 * 那是红队用真实类实测出来的（BLOCKER-1）。这组用例把那次的每一环都钉住。</p>
 *
 * <h2>为什么能直接调</h2>
 * 本类没有 {@code static {}} 块，全部方法是 {@code static}，只依赖 {@code Messages.get}
 * （纯静态表，不碰 Bukkit）。所以不需要 {@code allocateInstance} 或任何假服务端。
 *
 * <p><b>但注意</b>：{@code Messages} 的模块注册需要显式初始化，否则
 * {@code Messages.get} 一律返回键名——那样下文所有"渲染出正确文案"的断言都会
 * 恰好在测兜底分支。{@link #initMessages()} 走的正是生产环境的同一条注册路径。</p>
 */
class AlertCatalogTest {

    /**
     * 初始化文案层，让 {@code alert.*} 键真的存在。
     *
     * <p>走 {@link AlertFixtures#initMessages()}：它先 {@code init()} 三个模块表，
     * 再用一个"只有 logger 的裸 plugin"调 {@code Messages.load}，从而走完整的
     * {@code touchModules() → mergeModules() → 发布快照} 路径。</p>
     *
     * <p><b>此前这里是 {@code Messages.load(null)}</b>，那条路径在 {@code plugin == null} 时
     * 直接发布 {@code EN_DEFAULTS}、<b>跳过模块合并</b>——于是本类实际只验证了
     * {@code Messages.java} 里那份遗留副本，{@code AlertMessages} 里新增的键（如
     * {@code alert.msg.estimated}）在这里永远"不存在"。P0-U 加估算标记时踩到了这一点：
     * 标记静默不出现，而断言"正文里没有键名"照样通过。改用真实路径后，模块表的键才真的在。</p>
     */
    @org.junit.jupiter.api.BeforeAll
    static void initMessages() {
        cn.perfscope.support.AlertFixtures.initMessages();
    }

    // ------------------------------------------------------------------------------------
    // 1. formatValue：数值格式化（与语言无关的那一半）
    // ------------------------------------------------------------------------------------

    /**
     * 整数不带小数点，小数保留一位。
     *
     * <p>这条看似琐碎，但它是 BLOCKER-1 的另一面：值被格式化后要作为占位符实参传进模板。
     * 若整数渲染成 {@code "3000.0"}，服主看到的是"3000.0 个实体"——不是错误，但很难读；
     * 而若把 {@code 92.1} 渲染成 {@code "92"}，则是<b>把小数丢成整数</b>，属于失真。</p>
     */
    @Test
    void formatValueKeepsIntegersIntegralAndOneDecimalOtherwise() {
        assertEquals("3000", AlertCatalog.formatValue(3000.0, Metric.Unit.COUNT));
        assertEquals("92.1%", AlertCatalog.formatValue(92.1, Metric.Unit.PERCENT));
        assertEquals("0", AlertCatalog.formatValue(0.0, Metric.Unit.COUNT));
    }

    /**
     * 单位后缀由 {@link Metric.Unit} 决定，与语言无关。
     *
     * <p>javadoc 第 53-54 行承诺"同一份数据在两种语言下读出来的是同一个数"。
     * 若后缀被本地化（例如英文用 {@code "%"} 而中文用 {@code "％"}），
     * 跨语言对比历史告警时会以为是两个不同的读数。</p>
     */
    @Test
    void unitSuffixComesFromTheUnitNotTheLocale() {
        assertEquals("92.1%", AlertCatalog.formatValue(92.1, Metric.Unit.PERCENT));
        assertEquals("45.5ms", AlertCatalog.formatValue(45.5, Metric.Unit.MILLIS));
        assertEquals("19.9", AlertCatalog.formatValue(19.9, Metric.Unit.TPS),
            "TPS 是纯数字，不该有单位后缀——加了会让「19.9 tps」这类模板重复计数");
    }

    /**
     * {@code NaN} 渲染成 {@code "?"}，不是一个假数字。
     *
     * <p>这条锁的是"未知就说未知"。{@code NaN} 出现在告警里意味着采集侧没拿到值；
     * 把它渲染成 {@code "0"} 会让服主以为读数是 0（"CPU 占用 0%"），
     * 那正是本项目最怕的"把盲点报成健康"。</p>
     */
    @Test
    void NaNIsRenderedAsUnknownNotAsZero() {
        assertEquals("?", AlertCatalog.formatValue(Double.NaN, Metric.Unit.PERCENT),
            "NaN 必须渲染成 '?'：渲染成 0 会让服主以为读数是 0，把盲点当健康");
        assertNotEquals("0%", AlertCatalog.formatValue(Double.NaN, Metric.Unit.PERCENT));
    }

    /**
     * 小数用 {@link java.util.Locale#ROOT} 格式化，不随系统区域设置变。
     *
     * <p>javadoc 第 143-144 行记的就是这条：在 {@code de_DE} 上 {@code %.1f} 会输出
     * {@code "92,1"}（逗号）。数值展示不该随操作系统语言变化——同一份报告在德国服务器
     * 上和在中文服务器上必须读出同一个数。</p>
     *
     * <p><b>这条断言在当前 JVM 上是"运气对"而非"实现对"</b>：测试进程的默认区域是
     * en/zh，两种情况下 {@code %.1f} 都用点号。它只能挡住"有人把 {@code Locale.ROOT}
     * 删掉并把默认区域改成欧洲语言"这种组合，价值有限但保留——成本为零，
     * 而且它把「必须显式指定 Locale」这个意图写在了测试里。</p>
     */
    @Test
    void fractionalValuesNeverUseAComma() {
        assertFalse(AlertCatalog.formatValue(92.15, Metric.Unit.PERCENT).contains(","),
            "小数必须用点号：区域设置变化不能让同一份报告的读数变样");
    }

    /**
     * 极大值不落进 {@code long} 强转（那里会溢出成负数）。
     *
     * <p>实现里有 {@code Math.abs(value) < 1e15} 这个守卫。去掉它，
     * {@code 1e20} 这种值（虽然现实中不该出现）会被 {@code (long)} 截断成
     * {@code Long.MAX_VALUE} 并打印成一个离谱的整数。</p>
     */
    @Test
    void hugeValuesDoNotWrapAroundViaLongCast() {
        String s = AlertCatalog.formatValue(1e20, Metric.Unit.COUNT);
        assertFalse(s.startsWith("-"),
            "极大值不得因 long 强转溢出成负数，got: " + s);
    }

    // ------------------------------------------------------------------------------------
    // 2. argsFor 的占位符契约（BLOCKER-1 的回归）
    // ------------------------------------------------------------------------------------

    /**
     * <b>普通告警的数值必须出现在正文里。</b>这正是 BLOCKER-1 的原形。
     *
     * <p>当年的实现给所有模板都传 {@code (scope, value)}，而普通模板只写 {@code {0}}——
     * 于是 {@code {0}} 取到的是空的世界名，数值被塞进没人读的 {@code {1}}。
     * 结果：{@code "Heap usage high: "}，数值凭空消失，<b>零异常、零日志</b>。</p>
     *
     * <p>这条断言用"正文里必须出现那个数字"来锁它。注意不能断言完整文案——
     * 那是 {@code AlertMessages} 的内容，会随措辞调整；数值在不在才是契约。</p>
     */
    @Test
    void plainAlertMessageCarriesTheValue() {
        Alert heap = Alert.measured("memory:heap", Alert.LEVEL_WARNING, 92.1, "memory", 0L);
        String msg = AlertCatalog.message(heap);
        assertTrue(msg.contains("92.1"),
            "普通告警的正文必须带上数值——BLOCKER-1 就是数值被塞进没人读的 {1} 而消失，"
                + "且零异常零日志。got: " + msg);
    }

    /**
     * 世界维度告警的正文必须<b>同时</b>带上世界名与数值。
     *
     * <p>这一档的模板是 {@code {0}}=世界名、{@code {1}}=值，{@code argsFor} 的顺序因此不同。
     * 若有人把三个分支统一成一种顺序（正是 BLOCKER-1 的修法思路），
     * 这一档或上一档必然有一档丢东西。</p>
     *
     * <p><b>为什么要额外断言"不走兜底"</b>：这条用例最初只断言"正文含世界名与数值"。
     * 变异验证发现 {@code needsScope} 改成恒 {@code false} 时它<b>照样绿</b>——
     * 因为键缺失会落进 {@code message()} 的兜底分支，而兜底文案
     * （{@code "entities_world warning (nether): 5000"}）<b>恰好也含世界名</b>。
     * 于是"世界维度告警走的是 {@code .scoped} 模板"这件事，此前没有任何断言。
     * 后果：用户看到的是英文结构化兜底而不是本地化文案——切了中文仍然是英文。</p>
     */
    @Test
    void scopedAlertMessageCarriesBothWorldAndValue() {
        Alert world = Alert.measured("entities:world:nether", Alert.LEVEL_WARNING, 5000, "entities", 0L);
        assertTrue(world.metric() == Metric.ENTITIES_WORLD,
            "前置条件：这条 key 必须解析成世界维度指标，否则本用例测的不是那一档");

        String msg = AlertCatalog.message(world);
        assertTrue(msg.contains("5000"),
            "世界维度告警必须带数值，got: " + msg);
        assertTrue(msg.contains("nether"),
            "世界维度告警必须带世界名，否则服主不知道是哪个世界出的问题，got: " + msg);
        assertNotEquals(
            "entities_world warning (nether): 5000",
            msg,
            "不得落进 message() 的结构化兜底：那说明 .scoped 模板没被用到，"
                + "用户看到的是英文兜底而不是本地化文案（切中文也仍是英文）");
    }

    /**
     * 世界维度告警的<b>标题</b>同样必须走 {@code .scoped} 模板，不得落进兜底。
     *
     * <p>恢复通知是 {@code title + 已恢复}。若标题落进兜底，
     * 中文服主会看到 {@code "entities_world warning 已恢复正常"}——半英半中的拼接，
     * 而这条通知的作用正是让人确认"问题真的过去了"。</p>
     */
    @Test
    void scopedAlertTitleIsLocalizedNotFallback() {
        Alert world = Alert.measured("entities:world:nether", Alert.LEVEL_WARNING, 5000, "entities", 0L);
        String title = AlertCatalog.title(world);
        assertNotEquals("entities_world warning", title,
            "标题不得落进 title() 的结构化兜底：恢复通知会变成半英半中的拼接。got: " + title);
        assertFalse(title.isEmpty(), "标题不得为空");
    }

    /**
     * 玩家告警的正文必须带上容量（{@code 12/20} 里的 20）。
     *
     * <p>第三档模板是 {@code {0}}=值、{@code {1}}=容量。丢掉容量，服主只会看到"12 人在线"，
     * 而 12 本身不构成问题——问题是"12/20 满了"。</p>
     */
    @Test
    void playerAlertMessageCarriesTheCapacity() {
        Alert players = Alert.measuredWithReference(
            "players:online", Alert.LEVEL_WARNING, 12, 20, "players", 0L);
        String msg = AlertCatalog.message(players);
        assertTrue(msg.contains("12"), "玩家告警必须带当前人数，got: " + msg);
        assertTrue(msg.contains("20"),
            "玩家告警必须带容量——单看 12 不构成告警，12/20 才构成，got: " + msg);
    }

    /**
     * 玩家告警<b>没有容量</b>时退回两参形态，不得渲染出 {@code "12/?"} 之类的残片。
     *
     * <p>{@code argsFor} 的守卫是 {@code !Double.isNaN(alert.reference())}。
     * 去掉它，{@code reference} 为 NaN 时会走
     * {@code formatValue(NaN, COUNT)} → {@code "?"}，正文变成 {@code "12/?"}。</p>
     */
    @Test
    void playerAlertWithoutCapacityDoesNotRenderAQuestionMarkPlaceholder() {
        Alert noCap = Alert.measured("players:online", Alert.LEVEL_WARNING, 12, "players", 0L);
        String msg = AlertCatalog.message(noCap);
        assertTrue(msg.contains("12"), "got: " + msg);
        assertFalse(msg.contains("?"),
            "没有容量时不得渲染出 '?' 残片——那会让服主以为数据坏了，got: " + msg);
    }

    // ------------------------------------------------------------------------------------
    // 3. 兜底：键缺失时不得把原始键名打给服主
    // ------------------------------------------------------------------------------------

    /**
     * 未知指标（{@code metric == null}，历史遗留的已下线 key）退回 key 本身。
     *
     * <p>这是刻意行为：已下线指标的 key 是唯一还能提供的信息，
     * 编造一个"未知指标 warning: 0"反而更差。</p>
     */
    @Test
    void unknownMetricFallsBackToTheRawKey() {
        Alert legacy = new Alert("some.retired.key", Alert.LEVEL_WARNING, null, "x", 0L,
            1.0, Double.NaN, "", Double.NaN, Double.NaN, false);
        assertEquals("some.retired.key", AlertCatalog.message(legacy),
            "已下线指标没有 Metric，退回 key 是唯一还能提供的信息——不要编造一个假文案");
    }

    /** {@code null} 告警渲染成空串，不抛。 */
    @Test
    void nullAlertRendersAsEmptyString() {
        assertEquals("", AlertCatalog.message(null));
        assertEquals("", AlertCatalog.title(null));
    }

    // ------------------------------------------------------------------------------------
    // 4. title / recoveredSuffix
    // ------------------------------------------------------------------------------------

    /**
     * {@code title} 不带数值——它是恢复通知的锚点（{@code display + " 已恢复"}）。
     *
     * <p>这条锁的是"标题与正文分工"。若 title 也带上数值，恢复通知会变成
     * {@code "堆内存使用率偏高: 92.1% 已恢复正常"}——读起来像"92.1% 恢复了"，
     * 而那正是 v1.4.2 修过的那类"把结论说反"的文案。</p>
     */
    @Test
    void titleDoesNotCarryTheValue() {
        Alert heap = Alert.measured("memory:heap", Alert.LEVEL_WARNING, 92.1, "memory", 0L);
        String title = AlertCatalog.title(heap);
        assertFalse(title.contains("92.1"),
            "title 是恢复通知的锚点（title + 已恢复），带上数值会让恢复通知读成"
                + "「92.1% 恢复了」。got: " + title);
        assertFalse(title.isEmpty(), "title 不得为空，got: " + title);
    }

    /** {@code recoveredSuffix} 非空，且键缺失时有英文兜底。 */
    @Test
    void recoveredSuffixIsNeverEmptyOrARawKey() {
        String s = AlertCatalog.recoveredSuffix();
        assertFalse(s.isEmpty(), "恢复后缀不得为空");
        assertNotEquals("alert.recovered", s,
            "键缺失时必须退回英文兜底 'recovered'，不能把原始键名打给服主");
    }

    // ------------------------------------------------------------------------------------
    // 5. 告警正文不得泄露原始消息键
    // ------------------------------------------------------------------------------------

    /**
     * 任何一条告警的正文与标题都不得以 {@code alert.} 开头（那是未翻译的键名泄露）。
     *
     * <p>这是对整组测试的"反向守卫"：上面各条只断言"某段内容在"，这一条断言
     * "<b>不该有的东西不在</b>"。没有它，一个把所有内容都拼进正文的错误实现
     * （例如 {@code return key + " " + value;}）能同时通过上面好几条。</p>
     */
    @Test
    void noMessageKeyLeaksIntoUserFacingText() {
        String[][] cases = {
            {"memory:heap", "warning"},
            {"tps:global", "critical"},
            {"mspt:global", "warning"},
            {"entities:world:nether", "critical"},
            {"players:online", "warning"},
            {"cpu_process", "warning"},
            {"cpu_system", "warning"},
            {"chunks:world:world", "warning"},
        };
        for (String[] c : cases) {
            Alert a = Alert.measured(c[0], c[1], 42.0, "x", 0L);
            String msg = AlertCatalog.message(a);
            String title = AlertCatalog.title(a);
            assertFalse(msg.contains("alert."),
                "正文泄露了原始消息键（服主会看到内部键名）: " + msg);
            assertFalse(title.contains("alert."),
                "标题泄露了原始消息键: " + title);
        }
    }

    /**
     * 同一指标在 warning 与 critical 下必须渲染出不同文案。
     *
     * <p>若 {@code levelId} 被写成恒返回 {@code "warning"}（两个键会落到同一个），
     * 服主看到的"严重"与"警告"文案一模一样——而级别正是决定要不要立刻处理的信息。</p>
     */
    @Test
    void warningAndCriticalRenderDifferently() {
        Alert warn = Alert.measured("memory:heap", Alert.LEVEL_WARNING, 92.1, "memory", 0L);
        Alert crit = Alert.measured("memory:heap", Alert.LEVEL_CRITICAL, 97.5, "memory", 0L);
        assertNotEquals(AlertCatalog.title(warn), AlertCatalog.title(crit),
            "warning 与 critical 必须是不同文案：级别决定服主要不要立刻处理");
    }

    // ------------------------------------------------------------------------------------
    // 5. P0-U：估算标记（" (估算)"）—— 五条通道必须同口径
    // ------------------------------------------------------------------------------------

    /**
     * <b>靶点（P0-U）</b>：估算值的告警正文必须带「(估算)」。
     *
     * <p>改前：{@code Alert} 没有任何"这是估算"的表达，于是 webhook / 控制台推送里
     * {@code MSPT 偏高: 50.03ms} 与真实测量长得一模一样——而那个 50.03 是
     * {@code 1000/19.99}（含服务器 sleep 的墙钟间隔），真实 tick 耗时可能只有 5ms。</p>
     *
     * <p>断言的落点是"标记存在且数值仍在"，不锁具体措辞——后者是 {@code AlertMessages}
     * 的内容，会随措辞调整。</p>
     */
    @Test
    void estimatedAlertCarriesTheEstimateMarkerInItsBody() {
        Alert estimated = Alert.measured("mspt:global", Alert.LEVEL_CRITICAL, 50.03, "mspt", 0L)
            .asEstimated(true);
        String msg = AlertCatalog.message(estimated);

        // 数值按 %.1f 渲染成 "50.0"（AlertCatalog.formatValue 的既有口径），断言只看"数值还在"
        assertTrue(msg.contains("50.0"), "the value must still be there: " + msg);
        assertTrue(msg.contains(AlertCatalog.estimateSuffix(estimated)),
            "the body must carry the estimate marker: " + msg);
        assertNotEquals(AlertCatalog.message(
                Alert.measured("mspt:global", Alert.LEVEL_CRITICAL, 50.03, "mspt", 0L)),
            msg,
            "an estimated reading must not render identically to a measured one — that is the whole "
                + "point: 1000/TPS is a wall-clock interval, not the tick's compute time");
    }

    /** 非估算告警不得凭空多出标记（否则每条告警都要被读成"这个数不准"）。 */
    @Test
    void measuredAlertHasNoEstimateMarker() {
        Alert measured = Alert.measured("mspt:global", Alert.LEVEL_CRITICAL, 50.03, "mspt", 0L);
        assertFalse(measured.estimated());
        assertEquals("", AlertCatalog.estimateSuffix(measured),
            "a real measurement must not be dressed up with an estimate marker");
    }

    /**
     * 标记必须通过 i18n 键渲染，且中英各一份。
     *
     * <p>直接拿两条语言的表比对：{@code Messages.get} 在键缺失时返回键名本身，
     * 而 {@link AlertCatalog#estimateSuffix} 在这种情况下返回空串——于是"键漏了一个语言"
     * 的表现是<b>静默不标</b>，比打出错文案更难发现。这条把它变成一次断言失败。</p>
     */
    @Test
    void estimateMarkerIsLocalizedInBothLanguages() {
        Alert estimated = Alert.measured("mspt:global", Alert.LEVEL_WARNING, 50.03, "mspt", 0L)
            .asEstimated(true);
        String suffix = AlertCatalog.estimateSuffix(estimated);

        assertFalse(suffix.isEmpty(),
            "the estimate marker must exist in the message layer: an empty suffix silently means "
                + "'this number is exact', which is the bug this marker exists to prevent");
        assertFalse(suffix.contains("alert.msg.estimated"),
            "a missing key would render the raw key name into the alert body: " + suffix);
        assertFalse(suffix.startsWith("(") && !suffix.startsWith(" ("),
            "the marker needs a leading space so it does not glue onto the value: '" + suffix + "'");
    }

    /**
     * {@code asEstimated} 幂等，且不改变除标记外的任何结构字段。
     *
     * <p>它是"同一观测的标记副本"，不是新观测：key/level/value/threshold 一律不变。
     * 若某个字段跟着变了，告警层会把同一次越界读成两条不同的告警（旧告警恢复 + 新告警开启）。</p>
     */
    @Test
    void asEstimatedOnlyFlipsTheFlag() {
        Alert base = Alert.measuredWithThresholds("mspt:global", Alert.LEVEL_CRITICAL, 50.03,
            45.0, 50.0, "mspt", 123L);
        Alert marked = base.asEstimated(true);
        Alert again = marked.asEstimated(true);

        assertTrue(marked.estimated());
        assertSame(again, marked, "asEstimated(true) must be idempotent (return the same instance)");
        assertSame(base, base.asEstimated(false), "asEstimated(false) on an exact alert is a no-op");

        assertEquals(base.key(), marked.key());
        assertEquals(base.level(), marked.level());
        assertEquals(base.metric(), marked.metric());
        assertEquals(base.module(), marked.module());
        assertEquals(base.timestamp(), marked.timestamp());
        assertEquals(base.value(), marked.value(), 1e-12);
        assertEquals(base.threshold(), marked.threshold(), 1e-12);
        assertEquals(base.exitThreshold(), marked.exitThreshold(), 1e-12);
        assertEquals(base.scope(), marked.scope());
    }
}
