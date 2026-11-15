/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.config;

import cn.perfscope.support.ConfigFixtures;
import cn.perfscope.support.TestObjects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigManager#validateThresholds} 里<b>两组成对的 double 阈值</b>（tps / mspt）的校验路径。
 *
 * <p><b>本类锁的是什么</b>：{@code sanitizePair} 的两个方向参数加三道指标域闸门——</p>
 * <ul>
 *   <li>tps 是<b>低值差</b>（{@code tps < threshold} 才告警），合法顺序是 {@code warning >= critical}，
 *       方向参数 {@code highIsBad=false}；上界不是 {@code MAX_DOUBLE_THRESHOLD} 而是指标域的
 *       {@code TPS_CEILING = 20.5}，超过 20.5 回退 18/15；</li>
 *   <li>mspt 是<b>高值差</b>（{@code msptAvg > threshold} 才告警），合法顺序是 {@code warning <= critical}，
 *       方向参数 {@code highIsBad=true}；{@code <= 0} 单独一道闸（0 会让 critical 档恒真），
 *       回退 45/50；</li>
 *   <li>tps 落在 {@code [TPS_TAUTOLOGY_FLOOR, TPS_CEILING]} 的「近恒真区」时<b>只警告、不改值</b>。</li>
 * </ul>
 *
 * <p><b>与 {@code ThresholdPairZeroValidationTest} 的分工</b>：那个类锁的是 <b>int</b> 阈值对
 * （players/entities/chunks/memory 与 worlds 覆盖）的 {@code 0} 下界与指标域上界，走的是
 * {@code sanitizeIntPair}；本类锁 <b>double</b> 阈值对，走的是 {@code sanitizePair} 加两道
 * 只对 double 存在的闸（{@code TPS_CEILING}、mspt 的 {@code <= 0}）。两者唯一的交集是
 * {@code mspt 0/0}——那个类只断言回退结果，本类把「方向参数相反」「只写一个 0」「界于合法/非法之间的小数」
 * 一并补齐，并锁住 warn-only 与「合法配置不许乱叫」这两条最容易写反的分支。</p>
 *
 * <p><b>为什么必须真的调用 {@code validateThresholds}</b>：审计已实测，把 {@code TPS_CEILING} 闸、
 * mspt 的 {@code <= 0} 闸、以及 tps 的方向参数逐条拆掉/写反，现有 520 个测试全绿——这些分支此前
 * <b>完全没有断言</b>。只反射读常量的测试（{@code UpdateIntervalClampTest} 那种）对整段删除同样无感，
 * 所以本类一律断言「值被真实写回 {@link ConfigManager.ConfigData}」。</p>
 */
class DoubleThresholdPairValidationTest {

    /** 独立 logger 名：{@link ConfigFixtures#warningsOf} 按名字取 logger，各用例互不串日志。 */
    private static final String LOG = "pscope-double-threshold-test";

    private static final double EPS = 1e-9;

    /**
     * 文案层必须先就位，否则 {@code Messages.get} 只回显键名（{@code config.check.tps-near-ceiling}），
     * 本类里「warn-only 必须真的喊出来」与「健康配置不许乱叫」两条断言都会失去意义。
     */
    @BeforeAll
    static void initMessages() throws Exception {
        cn.perfscope.alert.AlertServiceStateMachineFixture.initMessages();
    }

    /** 读 {@code private static double} 常量（{@code getStaticInt} 只支持 int，故走 getField）。 */
    private static double staticDouble(String name) {
        return (Double) TestObjects.getField(ConfigManager.class, null, name);
    }

    /** 跑一遍校验并把结果留在传入的 cfg 上（{@code validateThresholds} 的全部副作用都写在这里）。 */
    private static ConfigManager.ConfigData validated(ConfigManager.ConfigData cfg) {
        ConfigFixtures.validate(ConfigFixtures.manager(LOG + "-values"), cfg);
        return cfg;
    }

    // ================================================================================================
    // A. TPS：拒绝非法值 / 保留合法值
    // ================================================================================================

    /**
     * tps 倒置（15/18）必须回退 18/15。
     *
     * <p>靶点：{@code sanitizePair("tps...", ..., false)} 的方向参数写成 {@code true}。写反后 tps 会按
     * 「高值差」判断，{@code 15 < 18} 被判合法原样放行——于是 warning 档（判据 {@code tps < 15}）
     * 永远比 critical 档（{@code tps < 18}）更晚触发，DIAG-TPS-PERSIST 会长期停在 warning
     * 永不升级，而服主以为自己配置了一个两层告警。</p>
     */
    @Test
    void tpsInvertedPairFallsBackToDefaults() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.tps_warningThreshold = 15.0;
        cfg.tps_criticalThreshold = 18.0;
        validated(cfg);
        assertEquals(18.0, cfg.tps_warningThreshold, EPS,
            "tps is low-is-bad: warning=15 < critical=18 collapses the warning tier. If the highIsBad "
                + "flag is passed as true here, the inverted pair passes through and DIAG-TPS-PERSIST "
                + "sits at warning forever without ever escalating");
        assertEquals(15.0, cfg.tps_criticalThreshold, EPS,
            "the whole pair is reverted, not just the offending half -- half-reverting would leave "
                + "warning/critical in a state the user never wrote");
    }

    /**
     * tps 相等（18/18）是合法配置。
     *
     * <p>靶点：倒置判据写成 {@code w <= c}（把「相等」也算倒置）。方向的语义是
     * {@code warning < critical} 才叫倒置，相等只是把两档压成同一瞬间触发，语义自洽。
     * 过度收紧会让「warning 和 critical 用同一个值」这种常见降噪配置被静默改成 18/15。</p>
     */
    @Test
    void tpsEqualPairIsLegal() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.tps_warningThreshold = 18.0;
        cfg.tps_criticalThreshold = 18.0;
        validated(cfg);
        assertEquals(18.0, cfg.tps_warningThreshold, EPS,
            "equal is not inverted: only w < c is inversion for a low-is-bad metric; widening the check "
                + "to w <= c would silently rewrite a deliberate 'both tiers fire together' config");
        assertEquals(18.0, cfg.tps_criticalThreshold, EPS);
    }

    /**
     * tps 超过 {@code TPS_CEILING}（25/20）必须回退 18/15。
     *
     * <p>靶点：{@code if (cfg.tps_warningThreshold > TPS_CEILING ...)} 整块删除，或把
     * {@code TPS_CEILING} 改成 {@code MAX_DOUBLE_THRESHOLD}（1e7）。这道闸的价值在于 tps 的
     * <b>指标域</b>上界只有 20.5：阈值写 25 时窗口均值恒 {@code <= 20.5 < 25}，覆盖率达标后
     * DIAG-TPS-PERSIST 在健康服上永久命中。注意 25/20 这一对是<b>方向正确</b>的（25 >= 20），
     * {@code sanitizePair} 本身会原样放行——所以这里唯一拦得住它的就是 20.5 那道闸。
     * 用 25 这个 TPS 量级的字面值而不是「常量 + 0.1」，是为了让「把上界抬到 25 以上」的改动也变红。</p>
     */
    @Test
    void tpsAboveCeilingFallsBackToDefaults() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.tps_warningThreshold = 25.0;
        cfg.tps_criticalThreshold = 20.0;
        validated(cfg);
        assertEquals(18.0, cfg.tps_warningThreshold, EPS,
            "25/20 is direction-correct, so only the TPS_CEILING gate can reject it. Without that gate "
                + "the mean TPS (never above 20.5) is always below 25 and DIAG-TPS-PERSIST fires "
                + "permanently on a healthy server");
        assertEquals(15.0, cfg.tps_criticalThreshold, EPS,
            "an out-of-domain pair falls back as a whole: leaving critical=20 next to warning=18 would "
                + "produce a pair the user never wrote");
    }

    /**
     * 边界值 {@code TPS_CEILING} 本身合法（闭区间上端）。
     *
     * <p>靶点：上界闸写成 {@code >=}，或把 {@code TPS_CEILING} 从 20.5 收回到 20.0。这条与上一条配对——
     * 单有「25 被拒」时，把闸收紧仍然全绿，而「按 20.1/20.5 卡住一切下跌」是刻意保留的合法配置
     * （见 {@code TPS_CEILING} 的 javadoc），收紧后会被静默改成 18/15，告警一下变得很迟钝。
     * 常量本身也一并断言：20.5 不是随手取的数，它与 {@code CoreRules} 过滤异常样本的口径同值。</p>
     */
    @Test
    void tpsCeilingBoundaryIsLegal() {
        double ceiling = staticDouble("TPS_CEILING");
        assertEquals(20.5, ceiling, EPS,
            "TPS_CEILING must stay at 20.5: it is the closed upper bound of the metric domain and shares "
                + "its value with CoreRules' outlier filter. Lowering it to 20.0 rejects the legal "
                + "'hold everything below 20.1' configuration and silently reverts it to 18.0");
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.tps_warningThreshold = 20.5;
        cfg.tps_criticalThreshold = 20.0;
        validated(cfg);
        assertEquals(20.5, cfg.tps_warningThreshold, EPS,
            "20.5 is inside the closed interval: writing the gate as '>=' would reject the highest legal "
                + "TPS threshold and silently revert it to 18.0, making the rule far less sensitive than "
                + "the user asked for");
        assertEquals(20.0, cfg.tps_criticalThreshold, EPS,
            "the critical half of the boundary pair must survive too -- it is below the warning half, "
                + "which is the legal order for a low-is-bad metric");
    }

    /**
     * tps 负数（-1/-2）回退 18/15。
     *
     * <p>靶点：{@code sanitizePair} 里的 {@code w < 0 || c < 0} 下界。注意 tps 的下界是 0 而不是 1
     * （{@code 0/0} 是「关掉 TPS 告警」的合法惯用法，见 {@code ThresholdPairZeroValidationTest}），
     * 所以这里用负数而不是 0——这道闸恰好是 0 与负数的分界线。</p>
     */
    @Test
    void tpsNegativePairFallsBackToDefaults() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.tps_warningThreshold = -1.0;
        cfg.tps_criticalThreshold = -2.0;
        validated(cfg);
        assertEquals(18.0, cfg.tps_warningThreshold, EPS,
            "a negative TPS threshold is not the 'mute' idiom (that one is 0/0); dropping the lower "
                + "bound lets -1/-2 through and the rule can never fire on a real reading");
        assertEquals(15.0, cfg.tps_criticalThreshold, EPS);
    }

    /**
     * tps 非有限值（NaN / -Infinity）回退 18/15。
     *
     * <p>靶点：{@code Double.isFinite} 前置判断整块删除。NaN 参与任何比较都是 false，
     * 于是倒置判断和上界判断<b>双双放行</b>——配置里留下一个 NaN，运行时所有
     * {@code tps < NaN} 永远为 false，告警恒静默且没有任何日志说明原因。</p>
     */
    @Test
    void tpsNonFinitePairFallsBackToDefaults() {
        double[] bad = {Double.NaN, Double.NEGATIVE_INFINITY};
        String[] names = {"NaN", "-Infinity"};
        for (int i = 0; i < bad.length; i++) {
            ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
            cfg.tps_warningThreshold = bad[i];
            cfg.tps_criticalThreshold = 15.0;
            validated(cfg);
            assertEquals(18.0, cfg.tps_warningThreshold, EPS,
                "non-finite " + names[i] + ": every comparison against it is false, so both the "
                    + "inversion check and the ceiling check wave it through; the rule then silently "
                    + "never fires");
            assertEquals(15.0, cfg.tps_criticalThreshold, EPS,
                "non-finite " + names[i] + " must revert the whole pair, not just the NaN half");
        }
    }

    // ================================================================================================
    // A. MSPT：方向与 tps 相反，另有一道 <= 0 的闸
    // ================================================================================================

    /**
     * mspt 倒置（50/45）回退 45/50。
     *
     * <p>靶点：{@code sanitizePair("mspt...", ..., true)} 的方向参数写成 {@code false}。写反后
     * {@code 50 > 45} 被判合法——mspt 是高值差指标，判据 {@code msptAvg > threshold}，
     * warning=50 比 critical=45 更难过线，于是警告比严重更晚出现，DIAG-MSPT-SPIKE 的 warning
     * 档实际上不可达。这条与 tps 倒置那条是一对镜像，两条一起锁住方向参数不可能被单点写反。</p>
     */
    @Test
    void msptInvertedPairFallsBackToDefaults() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.mspt_warningThreshold = 50.0;
        cfg.mspt_criticalThreshold = 45.0;
        validated(cfg);
        assertEquals(45.0, cfg.mspt_warningThreshold, EPS,
            "mspt is high-is-bad: warning=50 > critical=45 makes the warning tier unreachable. If the "
                + "highIsBad flag were false here (the tps direction), this pair would pass untouched");
        assertEquals(50.0, cfg.mspt_criticalThreshold, EPS);
    }

    /**
     * mspt 相等（50/50）合法。
     *
     * <p>靶点：高值差方向把判据写成 {@code w >= c}。与 tps 的交界同理：相等只是两档同时触发。</p>
     */
    @Test
    void msptEqualPairIsLegal() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.mspt_warningThreshold = 50.0;
        cfg.mspt_criticalThreshold = 50.0;
        validated(cfg);
        assertEquals(50.0, cfg.mspt_warningThreshold, EPS,
            "equal is not inverted for a high-is-bad metric either; a '>=' inversion check would "
                + "silently rewrite this deliberate config to 45/50");
        assertEquals(50.0, cfg.mspt_criticalThreshold, EPS);
    }

    /**
     * mspt 取 0：{@code 0/0}、{@code 0/50}、{@code 45/0} 三种写法都必须回退 45/50。
     *
     * <p>靶点：{@code if (cfg.mspt_warningThreshold <= 0.0d || cfg.mspt_criticalThreshold <= 0.0d)} 整块删除。
     * 采集侧把 mspt 过滤为恒正，所以 critical=0 时「严重」档<b>恒真</b>且恢复条件永不可达；
     * 而 {@code sanitizePair} 本身的 {@code w < 0} 下界<b>恰好放行 0</b>（{@code 0 < 0} 为 false），
     * 所以这组用例是那道 {@code <= 0} 闸的唯一保护。</p>
     *
     * <p>单独写 {@code 45/0} 是因为它走的是另一条分支：{@code 45 > 0} 先被判「倒置」并回退，
     * 根本到不了 {@code <= 0} 这条闸。三条一起跑，保证两条路径的结果一致（都回退 45/50），
     * 不会出现「只写一个 0 就漏过」的死角。</p>
     */
    @Test
    void msptZeroPairsFallBackToDefaults() {
        double[][] pairs = {{0.0, 0.0}, {0.0, 50.0}, {45.0, 0.0}};
        String[] labels = {"0/0", "0/50", "45/0"};
        for (int i = 0; i < pairs.length; i++) {
            ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
            cfg.mspt_warningThreshold = pairs[i][0];
            cfg.mspt_criticalThreshold = pairs[i][1];
            validated(cfg);
            assertEquals(45.0, cfg.mspt_warningThreshold, EPS,
                "mspt " + labels[i] + ": a zero threshold makes the serious tier permanently true "
                    + "(all mspt readings are positive) and recovery unreachable. sanitizePair's "
                    + "w < 0 lower bound does not catch 0, so the dedicated <= 0 gate is the only guard");
            assertEquals(50.0, cfg.mspt_criticalThreshold, EPS,
                "mspt " + labels[i] + " must revert both halves, including the one that was not zero");
        }
    }

    /**
     * 亚毫秒 mspt（0.5/1.0）合法。
     *
     * <p>靶点：把 {@code <= 0} 闸写成 {@code < 1.0}（加一个「至少 1ms」的下界）。亚毫秒阈值虽然激进，
     * 但判据是严格大于，语义完全自洽；加下界会误伤刻意按 tick 预算卡点的配置。</p>
     */
    @Test
    void subMillisecondMsptIsLegal() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.mspt_warningThreshold = 0.5;
        cfg.mspt_criticalThreshold = 1.0;
        validated(cfg);
        assertEquals(0.5, cfg.mspt_warningThreshold, EPS,
            "sub-millisecond thresholds are aggressive but self-consistent (the rule uses '>'), so the "
                + "gate must be '<= 0' rather than a '>= 1ms' lower bound");
        assertEquals(1.0, cfg.mspt_criticalThreshold, EPS);
    }

    /**
     * mspt 超过 {@code MAX_DOUBLE_THRESHOLD}（10,000,000）回退 45/50。
     *
     * <p>靶点：{@code sanitizePair} 里 {@code w > MAX_DOUBLE_THRESHOLD} 的越界分支。阈值高于指标读数
     * 上限时告警永远不触发，是「0 恒触发」的镜像错误。</p>
     */
    @Test
    void msptAboveMaxDoubleThresholdFallsBackToDefaults() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.mspt_warningThreshold = 10_000_001.0;
        cfg.mspt_criticalThreshold = 10_000_002.0;
        validated(cfg);
        assertEquals(45.0, cfg.mspt_warningThreshold, EPS,
            "10,000,001 exceeds MAX_DOUBLE_THRESHOLD: a threshold above the metric's reading range can "
                + "never be reached, so the alert would stay silent under a config that 'passed validation'");
        assertEquals(50.0, cfg.mspt_criticalThreshold, EPS);
    }

    /** mspt 非有限值（NaN）回退 45/50；与 tps 的 NaN 用例是同一条 {@code isFinite} 前置判断的两侧。 */
    @Test
    void msptNonFinitePairFallsBackToDefaults() {
        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.mspt_warningThreshold = Double.NaN;
        cfg.mspt_criticalThreshold = 50.0;
        validated(cfg);
        assertEquals(45.0, cfg.mspt_warningThreshold, EPS,
            "NaN compares false against everything, so neither the inversion check nor the <= 0 gate "
                + "can see it; only the isFinite pre-check rejects it");
        assertEquals(50.0, cfg.mspt_criticalThreshold, EPS);
    }

    // ================================================================================================
    // B. warn-only 与「必须静默」
    // ================================================================================================

    /**
     * {@code TPS_TAUTOLOGY_FLOOR} 区只警告、<b>不改值</b>（20.1/20.0）。
     *
     * <p>靶点：把 warn-only 分支写成回退。20.1 在 {@code (20.0, 20.5]} 的近恒真区——稳态 TPS 上界是 20.0，
     * 阈值 20.1 让 DIAG-TPS-PERSIST 在健康服上近乎恒真。但<b>不能改值</b>：一旦回退成 18.0，
     * 服主刻意「按 20.1 卡住一切下跌」的配置就变成了一个迟钝得多的告警，属 breaking change，
     * 所以设计上选择「喊出来、让服主自己决定」。这条同时断言两件事：值一字不改 + 文案里有这条告警。
     * 只断言值的话，「整块 warn-only 删掉」的变异会存活；只断言文案的话，「警告了但顺手回退」会存活。</p>
     */
    @Test
    void tpsTautologyZoneWarnsButKeepsTheConfiguredValue() {
        String warnings = ConfigFixtures.warningsOf(LOG + "-tautology", cfg -> {
            cfg.tps_warningThreshold = 20.1;
            cfg.tps_criticalThreshold = 20.0;
        });
        assertTrue(warnings.toLowerCase(Locale.ROOT).contains("tps"),
            "the near-tautology zone must be visible in the console -- a server owner running 20.1 has "
                + "no other way to learn that DIAG-TPS-PERSIST will fire on a healthy server; got: " + warnings);
        assertTrue(warnings.contains("20.1"),
            "the warning must carry the user's own value, otherwise they cannot tell which threshold is "
                + "being talked about; got: " + warnings);

        ConfigManager.ConfigData cfg = ConfigFixtures.validConfig();
        cfg.tps_warningThreshold = 20.1;
        cfg.tps_criticalThreshold = 20.0;
        validated(cfg);
        assertEquals(20.1, cfg.tps_warningThreshold, EPS,
            "warn-only means the value is kept: reverting 20.1 to 18.0 would silently make a deliberate "
                + "tight threshold far less sensitive -- that is the breaking change this branch exists "
                + "to avoid");
        assertEquals(20.0, cfg.tps_criticalThreshold, EPS,
            "the critical half is inside the legal domain (< 20.5) and must not be touched either");
    }

    /**
     * 健康配置不得触发任何阈值类 warning。
     *
     * <p>靶点：闸门的判据写宽一档（例如 {@code >} 写成 {@code >=}、{@code <= 0} 写成 {@code <= 1}，
     * 或对默认值 18/15、45/50 也误报）。合法配置上的噪音比漏报更伤：服主每次 reload 都会看到
     * 一对「配置校验」警告，从此学会忽略这一类日志，真正被回退的那一次也就再也看不见了。</p>
     *
     * <p><b>为什么这里要先做一次对照</b>：本用例断言的是「字符串里没有某字样」——如果捕获器
     * 本身失效（handler 挂错名字、logger 层级过滤掉 WARNING），空串同样满足「没有 tps/mspt」，
     * 用例就会假绿。所以先用一份必然产生 tps 告警的配置走<b>同一条</b> {@code warningsOf} 路径，
     * 证明捕获链是通的，再断言健康配置的静默。</p>
     */
    @Test
    void healthyConfigProducesNoThresholdWarnings() {
        String control = ConfigFixtures.warningsOf(LOG + "-control", cfg -> {
            cfg.tps_warningThreshold = 20.1;
            cfg.tps_criticalThreshold = 20.0;
        });
        assertTrue(control.toLowerCase(Locale.ROOT).contains("tps"),
            "control run must produce a tps warning through the very same capture path, otherwise the "
                + "silence assertion below is vacuous (an empty capture string also contains no 'tps'); "
                + "got: " + control);

        String warnings = ConfigFixtures.warningsOf(LOG + "-healthy", cfg -> {
            // 原样，一个字段都不改：validConfig() 的两个 double 对就是发布出去的默认值。
        });
        assertFalse(warnings.toLowerCase(Locale.ROOT).contains("tps"),
            "the shipped default tps pair (18/15) must validate silently; a false positive here trains "
                + "owners to ignore the whole configuration-check family; got: " + warnings);
        assertFalse(warnings.toLowerCase(Locale.ROOT).contains("mspt"),
            "the shipped default mspt pair (45/50) must validate silently; the repository default "
                + "pairs are the most common configuration in the wild; got: " + warnings);
    }

    // ================================================================================================
    // C. 两组阈值互不污染
    // ================================================================================================

    /**
     * tps 与 mspt 的校验互不影响。
     *
     * <p>靶点：把两段校验的变量串了（例如 mspt 的 {@code sanitizePair} 复用了 tps 的返回值，
     * 或某次回退写错了目标字段）。两对阈值在同一次 {@code validateThresholds} 里前后脚执行，
     * 任何一次「复制上一段改改用」都可能把这边算出来的默认值写进那边——而这种错误只会表现为
     * 「另一个指标的阈值莫名其妙变成默认值」，没有断言就完全看不出来。</p>
     */
    @Test
    void tpsAndMsptValidationDoNotLeakIntoEachOther() {
        ConfigManager.ConfigData tpsBroken = ConfigFixtures.validConfig();
        tpsBroken.tps_warningThreshold = 25.0;
        tpsBroken.tps_criticalThreshold = 20.0;
        validated(tpsBroken);
        assertEquals(45.0, tpsBroken.mspt_warningThreshold, EPS,
            "an out-of-domain tps pair must not disturb mspt: the two pairs are validated by separate "
                + "sanitizePair calls and a shared local would silently stamp the tps defaults onto mspt");
        assertEquals(50.0, tpsBroken.mspt_criticalThreshold, EPS);

        ConfigManager.ConfigData msptBroken = ConfigFixtures.validConfig();
        msptBroken.mspt_warningThreshold = 0.0;
        msptBroken.mspt_criticalThreshold = 0.0;
        validated(msptBroken);
        assertEquals(18.0, msptBroken.tps_warningThreshold, EPS,
            "a zero mspt pair must not disturb tps: tps 18/15 is perfectly legal and must survive "
                + "untouched while mspt is reverted");
        assertEquals(15.0, msptBroken.tps_criticalThreshold, EPS);
    }
}
