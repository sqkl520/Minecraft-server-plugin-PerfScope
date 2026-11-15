/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@code BstatsCharts} 的两个 package-private 纯函数。
 *
 * <h2>为什么能测（而 register() 不能）</h2>
 * 本类的 {@code static {}} 块只做两个 {@code newarray int} 去填
 * {@code ENTITY_BUCKETS}/{@code CHUNK_BUCKETS}/{@code PLAYER_BUCKETS}——纯数据初始化，
 * 零 {@code invoke}（{@code javap -c} 实证）。全类唯一的 {@code ServerCompat} 引用在
 * {@code register()} 的 lambda 里，所以"读 {@code ServerCompat} 会触发 {@code <clinit>}"
 * 这条限制<b>只对 {@code register()} 成立</b>，对下面两个函数不成立。
 *
 * <p>这与 {@code SchedulerAdapter$TaskHandle} 是同一类机会：不用改一行生产代码就能测。</p>
 *
 * <h2>它们错了会怎样</h2>
 * 这两个函数都只影响 bStats 遥测，用户界面上看不见。但那正是危险所在——<b>零异常、零日志</b>，
 * 只让产品决策基于错的数据。本类注释（第 38-45 行）写着这组图表存在的目的就是回答
 * "大服到底有多大"，答错了没人会发现。
 */
class BstatsChartsBucketTest {

    // ------------------------------------------------------------------------------------
    // 1. bucket：边界语义
    // ------------------------------------------------------------------------------------

    /**
     * 取"第一个 ≥ value 的边界"作为标签——上界含。
     *
     * <p>用上界标签（{@code "100"} 表示 0–100）而不是区间写法，是为了让 bStats 后台的柱状图
     * 能按数值自然排序。若改成"下界标签"或"区间字符串"，后台柱子会按字典序排，
     * {@code "1000-2500"} 会排在 {@code "100-500"} 前面。</p>
     */
    @Test
    void bucketReturnsTheFirstUpperBoundThatFits() {
        int[] bounds = {0, 100, 500};
        assertEquals("0", BstatsCharts.bucket(0, bounds, "500+"),
            "0 必须落进边界 0（闭区间上界）");
        assertEquals("100", BstatsCharts.bucket(1, bounds, "500+"));
        assertEquals("100", BstatsCharts.bucket(100, bounds, "500+"),
            "边界 100 本身属于 100 这一档（上界含）");
        assertEquals("500", BstatsCharts.bucket(101, bounds, "500+"));
    }

    /** 超过全部边界时返回溢出标签，不是最后一个边界。 */
    @Test
    void bucketOverflowsToTheGivenLabel() {
        assertEquals("500+", BstatsCharts.bucket(501, new int[]{0, 100, 500}, "500+"));
        assertEquals("500+", BstatsCharts.bucket(1_000_000, new int[]{0, 100, 500}, "500+"),
            "极大值也必须落进溢出档，不能被截断成某个边界");
    }

    /** 负数落进第一档（0），不抛也不返回怪异标签。 */
    @Test
    void bucketHandlesNegativeValues() {
        assertEquals("0", BstatsCharts.bucket(-5, new int[]{0, 100}, "100+"),
            "负值（理论上不该出现）落进第一档即可，但不能抛或返回溢出标签");
    }

    // ------------------------------------------------------------------------------------
    // 2. 玩家桶必须与实体桶区分（这一条对应一个真实的 bug）
    // ------------------------------------------------------------------------------------

    /**
     * <b>玩家数与实体数必须用不同的分桶。</b>
     *
     * <p>这是一个真实存在过的 bug：{@code peak_players_bucket} 复用了
     * {@code ENTITY_BUCKETS}，于是 0–100 玩家全被合并成同一条柱子、溢出标签写
     * {@code "50000+"}。后果是 bStats 后台那张图读不出大服与小服的差别——
     * 而它存在的唯一目的就是回答"大服到底有多大"。</p>
     *
     * <p>这条断言的写法是"同一批玩家数在两个桶下给出不同标签"：只要有人把玩家数
     * 改回实体桶，它立刻红。</p>
     */
    @Test
    void playerCountMustNotUseTheEntityBuckets() {
        int[] entityBuckets = {0, 100, 500, 1000, 2500, 5000, 10000, 25000, 50000};
        int[] playerBuckets = {0, 5, 10, 20, 50, 100, 200};

        assertNotEquals(
            BstatsCharts.bucket(30, entityBuckets, "50000+"),
            BstatsCharts.bucket(30, playerBuckets, "200+"),
            "30 个玩家在实体桶里落进「100」、在玩家桶里落进「50」——"
                + "若两者相同，说明玩家数又用了实体桶，0-100 玩家会被合并成一档，"
                + "bStats 后台读不出大服与小服的差别");

        assertEquals("50", BstatsCharts.bucket(30, playerBuckets, "200+"));
        assertEquals("100", BstatsCharts.bucket(30, entityBuckets, "50000+"),
            "对照：实体桶对 30 的标签是 100——这正是玩家数不该用的那个桶");
    }

    /**
     * 手机版服务器常见的几十人规模必须能被玩家桶区分开。
     *
     * <p>这条锁的是"桶的粒度真的够用"：0/5/10/20 四档覆盖原版与小服，
     * 若有人把 PLAYER_BUCKETS 简化成 {@code {0, 100}}，20 人与 5 人会挤进同一档，
     * 那条"大服到底有多大"的问题又答不出来了。</p>
     */
    @Test
    void playerBucketsDistinguishSmallServerSizes() {
        int[] playerBuckets = {0, 5, 10, 20, 50, 100, 200};
        assertNotEquals(BstatsCharts.bucket(3, playerBuckets, "200+"),
            BstatsCharts.bucket(8, playerBuckets, "200+"),
            "3 人与 8 人必须落进不同档——否则小服之间的差别也读不出来");
        assertNotEquals(BstatsCharts.bucket(8, playerBuckets, "200+"),
            BstatsCharts.bucket(18, playerBuckets, "200+"));
        assertNotEquals(BstatsCharts.bucket(18, playerBuckets, "200+"),
            BstatsCharts.bucket(40, playerBuckets, "200+"));
    }

    /** 玩家桶的溢出档标签是 "200+"，不是实体桶的 "50000+"。 */
    @Test
    void playerOverflowLabelMatchesItsScale() {
        int[] playerBuckets = {0, 5, 10, 20, 50, 100, 200};
        assertEquals("200+", BstatsCharts.bucket(500, playerBuckets, "200+"),
            "溢出标签必须与桶的量级匹配：玩家数写「50000+」会让后台标签荒谬");
    }

    // ------------------------------------------------------------------------------------
    // 3. normalizeLanguage
    // ------------------------------------------------------------------------------------

    /**
     * 白名单归一：只放行 {@code en} / {@code zh}，其余一律 {@code "other"}。
     *
     * <p>为什么必须归一而不是原样上报：用 {@code en_US} / {@code zh_CN} 这类区域变体的人很多，
     * 若原样上报，bStats 后台会看到几十种"语言"、每种只有一两个人。</p>
     */
    @Test
    void normalizeLanguageKeepsOnlyTheWhitelist() {
        assertEquals("en", BstatsCharts.normalizeLanguage("en"));
        assertEquals("zh", BstatsCharts.normalizeLanguage("zh"));
        assertEquals("en", BstatsCharts.normalizeLanguage("EN"),
            "大小写必须先归一，否则「EN」会被当成未知语言报成 other");
        assertEquals("other", BstatsCharts.normalizeLanguage("fr"));
    }

    /**
     * {@code null} 报 {@code "unknown"} 而不是 {@code "other"}。
     *
     * <p>两者语义不同：{@code "other"} 是"用户选了一个我们没做的语言"，
     * {@code "unknown"} 是"根本没读到配置"。混为一谈会让"配置加载失败"在遥测里隐形。</p>
     */
    @Test
    void nullLanguageIsUnknownNotOther() {
        assertEquals("unknown", BstatsCharts.normalizeLanguage(null),
            "null 是「没读到」而不是「选了别的语言」——混成 other 会让配置加载失败在遥测里隐形");
    }

    /**
     * 带区域变体的写法落进 {@code "other"}——这是<b>当前行为</b>，与
     * {@code ConfigManager.normalizeLanguage} 不同，值得知道。
     *
     * <p>{@code ConfigManager.load()} 会在把配置交给下游之前先把 {@code zh_CN} 收敛成
     * {@code zh}（{@code ConfigManager.normalizeLanguage}，按主子标签切分），所以正常路径下
     * bStats 拿到的是已归一的 {@code "zh"}，两份实现不会分歧。</p>
     *
     * <p>这条断言把"当前是 other"钉下来，避免有人误以为这里也做主子标签切分。
     * 若将来决定在这层也切分，把断言改掉即可——那时是个有意识的决定，不是意外的行为变化。</p>
     */
    @Test
    void regionalVariantsFallIntoOtherHere() {
        assertEquals("other", BstatsCharts.normalizeLanguage("zh_CN"),
            "本函数不做主子标签切分：正常路径下 ConfigManager 已先归一，到这里不会见到 zh_CN。"
                + "若将来决定在这里也切分，改这条断言即可——但那是有意识的决定，不是意外");
        assertEquals("other", BstatsCharts.normalizeLanguage("en-US"));
    }
}
