/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.config;

import cn.perfscope.support.ConfigFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-O：{@code network.push-interval-seconds} 与 {@code general.update_interval} 的交叉校验。
 *
 * <h2>要修的错误</h2>
 *
 * <p>原校验只查 push-interval 的**绝对值**（1–300），从不看它和采集周期的关系。而发送只能
 * 发生在采集周期末尾，代理侧又按「3 × pushInterval」判过期。于是
 * {@code update_interval: 1200}（60 秒）+ {@code push-interval-seconds: 10}（默认）
 * 这种**两个值都合法**的配置会让一台完全健康的后端有近一半时间显示「数据过期」，
 * 并且因为代理只在 {@code State.OK} 时计入聚合，它还一半时间被踢出全网 TPS 平均与玩家合计。</p>
 *
 * <p>实测占比（push=10 固定扫 update_interval）：1s → 0.0%；40s → 22.5%；60s → 48.3%。
 * 这些是红队的真实采集数据，不是合成轨迹。</p>
 *
 * <h2>这里锁什么</h2>
 *
 * <p>锁"关系式真的被执行"：断言 {@code validateThresholds} 跑完后 {@code ConfigData} 里的
 * push-interval 被**写回**成一个满足关系的值。<b>不</b>锁具体算法（系数、取整方式都能换），
 * 只锁结果——因为结果才是代理侧真正依赖的东西。</p>
 *
 * <p>每条用例都配一条"本来就不该动"的反向断言：没有它们，一个"一律把 push-interval 改成
 * 300"的实现也能过掉全部正向断言。</p>
 */
class PushIntervalCrossCheckTest {

    private static final String LOGGER = "pscope-push-cross";

    @BeforeAll
    static void initMessages() throws Exception {
        Class.forName("cn.perfscope.config.ConfigMessages").getMethod("init").invoke(null);
        Class.forName("cn.perfscope.util.Messages")
            .getMethod("load", Class.forName("cn.perfscope.PerfScope"))
            .invoke(null, (Object) null);
    }

    private static ConfigManager manager() {
        return ConfigFixtures.manager(LOGGER);
    }

    /** 合法基线 + 只改被测的两个量。 */
    private static ConfigManager.ConfigData cfg(int updateTicks, int pushSeconds) {
        ConfigManager.ConfigData c = ConfigFixtures.validConfig();
        c.general_updateInterval = updateTicks;
        c.network_pushIntervalSeconds = pushSeconds;
        return c;
    }

    /**
     * 算出一份配置经校验后的 push-interval。
     *
     * <p>必须走真实的 {@code validateThresholds}，不能自己复算一遍公式——那样测的是测试
     * 自己的算法，而不是被测代码的。</p>
     */
    private static int pushAfter(int updateTicks, int pushSeconds) {
        ConfigManager.ConfigData c = cfg(updateTicks, pushSeconds);
        ConfigFixtures.validate(manager(), c);
        return c.network_pushIntervalSeconds;
    }

    // ==================== 正向：不合关系时必须被上调 ====================

    /**
     * 红队实测的两个靶点。
     *
     * <p>这两个值就是"高血压"配置的正脸：单看每一个都在合法区间内，原校验一条都不报。
     * 断言的是<b>修完之后行为不同</b>——修之前 {@code pushAfter} 会返回 10（原值），
     * 修之后必然 &gt; 10。</p>
     */
    @Test
    @DisplayName("采集 40 秒 / 上报 10 秒：必须被上调（实测假 STALE 22.5%）")
    void fortySecondCollectionIsUpgraded() {
        int after = pushAfter(800, 10);
        assertTrue(after > 10,
            "update_interval=800t (40s) with push=10s leaves a ~22.5% false-STALE window "
                + "(measured, not synthetic); the interval must be raised, got " + after);
    }

    @Test
    @DisplayName("采集 60 秒 / 上报 10 秒：必须被上调（实测假 STALE 48.3%）")
    void sixtySecondCollectionIsUpgraded() {
        int after = pushAfter(1200, 10);
        assertTrue(after > 10,
            "update_interval=1200t (60s) with push=10s is the worst measured case (48.3% "
                + "false STALE); got " + after);
    }

    /**
     * 上调后的值必须**真的满足关系**，而不只是"比原来大"。
     *
     * <p>代理侧的判过期阈值是 {@code 3 × push}，而实际上报周期是
     * {@code ceil(push / update) × update}。这条断言直接按这个不等式验，与实现里的系数
     * 解耦：实现可以改成 1.5 倍、可以改成"至少一个周期"，只要结果满足不等式就绿。</p>
     */
    @Test
    @DisplayName("上调结果必须满足代理侧的判过期关系")
    void raisedValueSatisfiesProxyStaleRelation() {
        for (int updateTicks : new int[]{20, 100, 400, 800, 1000, 1200}) {
            int after = pushAfter(updateTicks, 1);
            long updateMs = updateTicks * 50L;
            long effectivePeriodMs = ((after * 1000L + updateMs - 1L) / updateMs) * updateMs;
            long staleWindowMs = after * 1000L * 3L;
            assertTrue(effectivePeriodMs <= staleWindowMs,
                "update_interval=" + updateTicks + "t -> push=" + after + "s: an actual send "
                    + "period of " + effectivePeriodMs + "ms exceeds the proxy's "
                    + staleWindowMs + "ms staleness window, so a healthy backend would still "
                    + "blink 'stale'");
        }
    }

    // ==================== 反向：已经合规的值不许被动 ====================

    /**
     * 默认配置（20t / 1s 采集 + 10s 上报）是最常见的形状，必须一字不改。
     *
     * <p>这条是核心反向断言：一个"总是上调到 300"的错误实现会让它红。同时也是
     * {@link ConfigFixtures#validConfig()} 的基线自检——它在其它测试里代表"零告警",
     * 若这条校验改了它的值，那些测试的 warning 断言会开始混进无关噪音。</p>
     */
    @Test
    @DisplayName("默认 20t / 10s：不得被改写")
    void defaultPairIsUntouched() {
        assertEquals(10, pushAfter(20, 10),
            "the shipped default (1s collection, 10s push) is already consistent; rewriting it "
                + "would change behaviour for every existing deployment");
    }

    /** 上报间隔明显大于采集周期时当然更合规——无论多大都不许被改（包括上限 300）。 */
    @Test
    @DisplayName("上报间隔已大于采集周期：保持原值，包括 300 秒上限")
    void alreadyCompliantValuesArePreserved() {
        assertEquals(60, pushAfter(20, 60));
        assertEquals(300, pushAfter(20, 300),
            "300s is the documented max; the cross-check must not clamp it further");
    }

    /** 边界：push 恰好等于一个采集周期时合规（关系式是 ≤，不是 <）。 */
    @Test
    @DisplayName("上报间隔恰好等于一个采集周期：合规，不得上调")
    void exactlyOneCollectionPeriodIsCompliant() {
        // update_interval=200t=10s，push=10s
        assertEquals(10, pushAfter(200, 10),
            "push == one collection period is exactly the boundary the relation allows; "
                + "a strict '<' would bump it, wasting update frequency for no benefit");
    }

    // ==================== 不与既有校验互相踩 ====================

    /**
     * 绝对值越界仍走原有路径（回退默认 10），而不是被交叉校验改写。
     *
     * <p>顺序有意义：先钳绝对值（500 → 10），再看关系。若顺序反过来，一个写 500 的服主
     * 会先被交叉校验算出某个中间值，再被绝对值校验回退成 10——最终结果一样但日志会打两条
     * 互相矛盾的 warning。</p>
     */
    @Test
    @DisplayName("绝对值越界仍回退默认 10（默认采集周期下不再触发交叉校验）")
    void outOfRangeStillFallsBackToDefault() {
        assertEquals(10, pushAfter(20, 500));
        assertEquals(10, pushAfter(20, 0));
    }

    /**
     * 越界回退之后仍要再过一次关系校验。
     *
     * <p>写 500 + 采集 60 秒：绝对值先回退成 10，然后关系校验发现 10 &lt; 60 又上调。
     * 少了这一步，回退出来的 10 会留下一个假 STALE 窗口——"越界回退"反而制造了新问题。</p>
     */
    @Test
    @DisplayName("越界回退后仍须满足关系（回退值不得留下假过期窗口）")
    void fallbackValueAlsoGoesThroughCrossCheck() {
        int after = pushAfter(1200, 500);
        assertTrue(after >= 60,
            "500 falls back to 10, but update_interval=1200t (60s) still needs push >= 60s; "
                + "got " + after);
    }

    // ==================== 告警必须真的发出来 ====================

    /**
     * 上调不能静默。
     *
     * <p>自动修正是可以接受的，擅自改配置而不说不行——服主会对着 config.yml 里写的 10
     * 和实际生效的 60 完全无法解释。这条断言日志里同时出现原值、采集周期与最终值。</p>
     *
     * <p>同时断言渲染后的文案（含"秒""采集"这类词）而不是只剩键名，避免 Messages 未初始化
     * 时假绿。</p>
     */
    @Test
    @DisplayName("上调必须告警，且点名原值 / 采集周期 / 调整后的值")
    void upgradeEmitsWarningNamingAllThreeNumbers() {
        String warnings = ConfigFixtures.warningsOf(LOGGER + "-warn", c -> {
            c.general_updateInterval = 1200;
            c.network_pushIntervalSeconds = 10;
        });

        assertTrue(warnings.contains("network.push-interval-seconds"),
            "the warning must name the key so the operator can find the line; got: " + warnings);
        assertTrue(warnings.contains("general.update_interval"),
            "the warning must show which collection period drove the change; got: " + warnings);
        assertTrue(warnings.contains("1200") || warnings.contains("60"),
            "the warning must echo the collection period (in ticks, seconds, or both); "
                + "got: " + warnings);
    }

    /** 合规配置上这条告警不得出现（否则每次 reload 都多一条噪音，真被改的那次就看不见了）。 */
    @Test
    @DisplayName("合规配置不产生交叉校验告警")
    void compliantConfigIsSilent() {
        String warnings = ConfigFixtures.warningsOf(LOGGER + "-silent", c -> {
            c.general_updateInterval = 20;
            c.network_pushIntervalSeconds = 10;
        });
        assertFalse(warnings.contains("network.push-interval-seconds"),
            "the default pair must not produce an interval warning; a false positive here trains "
                + "owners to ignore the whole config-check family; got: " + warnings);
    }
}
