/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Metric#ofKey(String)} 与 {@link Metric#scopeOf(String)} 的解析契约。
 *
 * <h2>为什么这两个函数是最关键的静默失效点</h2>
 * 所有结构化告警的入口都经过它们：{@code Alert.measured} /
 * {@code measuredWithReference} / {@code measuredWithThresholds} 三个工厂都调
 * {@code ofKey(key)} + {@code scopeOf(key)}（{@code Alert.java:83-108}）。
 *
 * <p>而 {@code Metric} 的类注释（第 25-31 行）自述三个解析器历史上有过三种"静默失效"。
 * 解析错一次的后果是：告警标题与正文指向<b>错误的指标</b>，而<b>零异常、零日志</b>——
 * 服主会照着一条"区块数偏高"的文案去查区块，实际出问题的是实体数。</p>
 *
 * <p>此前 {@code ofKey} 零直接断言（{@code grep -c ofKey src/test/java} = 0），
 * 只有 {@code AlertCatalogTest} 里一条间接断言。这组用例把它补齐。</p>
 *
 * <h2>为什么能直接调</h2>
 * 两个都是 {@code public static}，纯字符串处理，不碰 Bukkit、不碰 {@code Messages}、
 * 不碰任何静态可变状态。{@code Metric} 枚举的 {@code <clinit>} 只构造九个常量。
 */
class MetricKeyParsingTest {

    // ------------------------------------------------------------------------------------
    // 1. ofKey：九个指标各自的可识别 key
    // ------------------------------------------------------------------------------------

    /** 每个指标至少一种 key 形态必须解析成它自己——这是穷举表，漏一个就少一条保护。 */
    @Test
    void everyMetricIsReachableFromItsOwnKeyShape() {
        assertEquals(Metric.TPS, Metric.ofKey("tps:global"));
        assertEquals(Metric.MSPT, Metric.ofKey("mspt:global"));
        assertEquals(Metric.PLAYERS, Metric.ofKey("players:online"));
        assertEquals(Metric.MEMORY, Metric.ofKey("memory:heap"));
        assertEquals(Metric.ENTITIES, Metric.ofKey("entities:total"),
            "entities 不带 world: 前缀时是全服口径");
        assertEquals(Metric.ENTITIES_WORLD, Metric.ofKey("entities:world:nether"),
            "entities:world:X 必须解析成世界维度——否则报告会把单世界数字当全服数字");
        assertEquals(Metric.CHUNKS_WORLD, Metric.ofKey("chunks:world:overworld"));
        assertEquals(Metric.CPU_PROCESS, Metric.ofKey("cpu:process"));
        assertEquals(Metric.CPU_SYSTEM, Metric.ofKey("cpu:system"));
    }

    /**
     * {@code chunks} 只要出现就归世界维度，<b>没有全服口径</b>。
     *
     * <p>这是刻意的：全服区块数从不告警（所以没有 {@code CHUNKS} 这个枚举常量），
     * 只有"某个世界的区块数"才有告警意义。若有人把 {@code chunks:global} 解析成
     * {@code null}（"没有全服口径所以不认识"），那条历史告警会退化成原始 key 展示。</p>
     */
    @Test
    void chunksAlwaysResolvesToTheWorldScopedMetric() {
        assertEquals(Metric.CHUNKS_WORLD, Metric.ofKey("chunks:global"),
            "chunks 没有全服枚举，任何 chunks key 都归世界维度——返回 null 会让历史告警"
                + "退化成原始 key 展示");
        assertEquals(Metric.CHUNKS_WORLD, Metric.ofKey("chunks:world:world"));
    }

    // ------------------------------------------------------------------------------------
    // 2. ofKey：按第一个冒号切分（world 名里可能还有冒号）
    // ------------------------------------------------------------------------------------

    /**
     * 世界名里含冒号时仍能正确识别指标。
     *
     * <p>javadoc 第 126-127 行明写"scope 里允许再含冒号——因此这里按<b>第一个</b>冒号切分"。
     * 若有人改成 {@code lastIndexOf(':')} 或 {@code split(":")} 取最后一段，
     * {@code entities:world:my:coloned:world} 这份历史数据会被解析成错的指标或 null。</p>
     */
    @Test
    void worldNameMayContainColons() {
        assertEquals(Metric.ENTITIES_WORLD, Metric.ofKey("entities:world:my:coloned:world"),
            "按第一个冒号切分：前缀是 entities，rest 是 world:my:coloned:world，"
                + "以 world: 开头故归世界维度");
    }

    /**
     * {@code cpu} 的 {@code rest} 是<b>精确匹配</b>，不是前缀匹配。
     *
     * <p>实现是 {@code switch (rest) { case "process" -> ...; case "system" -> ...; default -> null; }}。
     * 所以 {@code cpu:process:extra} 返回 null——这一点值得知道，因为它与
     * {@code entities}/{@code chunks} 那两个"只看 rest 开头"的分支<b>口径不同</b>。</p>
     *
     * <p>为什么这个不一致可以接受：{@code cpu} 的 key 形态由采集层固定产出（只有
     * {@code cpu:process} / {@code cpu:system} 两种），不存在"后面还挂东西"的真实数据；
     * 而 {@code entities}/{@code chunks} 要承载世界名，必须容忍后续冒号。
     * 这条把当前语义钉下来，避免有人误以为两处一致。</p>
     */
    @Test
    void cpuRestIsMatchedExactly() {
        assertEquals(Metric.CPU_PROCESS, Metric.ofKey("cpu:process"));
        assertEquals(Metric.CPU_SYSTEM, Metric.ofKey("cpu:system"));
        assertNull(Metric.ofKey("cpu:process:extra"),
            "cpu 的 rest 是精确匹配（与 entities/chunks 的「看开头」口径不同）——"
                + "这条钉住当前语义；若有人统一成前缀匹配，这条会红，那是有意识的变更");
    }

    // ------------------------------------------------------------------------------------
    // 3. ofKey：不认识的返回 null（调用方必须能容忍）
    // ------------------------------------------------------------------------------------

    /**
     * 已下线指标与畸形 key 返回 {@code null}，不抛。
     *
     * <p>javadoc 第 130-131 行明写"无法识别时返回 null（调用方须能容忍——历史数据里可能有
     * 已下线指标如 {@code redstone:global}）"。{@code AlertCatalog} 与
     * {@code ReportGenerator} 都有 {@code metric == null} 的兜底分支。</p>
     */
    @Test
    void unknownKeysReturnNullWithoutThrowing() {
        assertNull(Metric.ofKey("redstone:global"), "已下线指标（历史数据里可能有）");
        assertNull(Metric.ofKey("network:latency"), "已下线指标");
        assertNull(Metric.ofKey("cpu:threads"),
            "cpu 前缀但 rest 不是 process/system —— 不能猜一个最近的（那会指向错的指标）");
        assertNull(Metric.ofKey(null));
        assertNull(Metric.ofKey(""));
    }

    /**
     * <b>无冒号的裸词是合法的 key 形态</b>，不是拼写错误。
     *
     * <p>实现里 {@code int i = key.indexOf(':')} 取不到时，{@code prefix = key}、
     * {@code rest = ""}。于是 {@code "tps"} 解析成 {@link Metric#TPS}、
     * {@code "memory"} 解析成 {@link Metric#MEMORY}。</p>
     *
     * <p>这是刻意的宽松：{@code AlertService.java:2121} 等处直接
     * {@code new Alert(key, ...)}，而 key 的来源不保证带冒号。若把裸词判成 null，
     * 一条格式稍简的历史告警会退化成原始 key 展示（{@code AlertCatalog} 的
     * {@code metric == null} 分支），而不是正常渲染。</p>
     *
     * <p>代价是拼错的裸词（如 {@code "tpss"}）仍返回 null——因为前缀精确匹配；
     * 而拼错的带冒号 key（{@code "tpss:global"}）也返回 null。
     * 所以这条宽松不会掩盖拼写错误。</p>
     */
    @Test
    void bareWordsWithoutColonAreLegalKeys() {
        assertEquals(Metric.TPS, Metric.ofKey("tps"),
            "无冒号的裸词是合法形态（rest 为空串）——判成 null 会让格式稍简的历史告警"
                + "退化成原始 key 展示");
        assertEquals(Metric.MEMORY, Metric.ofKey("memory"));
        assertEquals(Metric.PLAYERS, Metric.ofKey("players"));
    }

    /** 前缀匹配是精确的，不是 {@code startsWith}。 */
    @Test
    void prefixMatchingIsExactNotPrefix() {
        assertNull(Metric.ofKey("tpsx:global"),
            "tpsx 不能被识别成 tps：前缀必须精确匹配，否则任何拼错都静默指向邻近指标");
        assertNull(Metric.ofKey("memoryy:heap"));
    }

    // ------------------------------------------------------------------------------------
    // 4. scopeOf：取世界名
    // ------------------------------------------------------------------------------------

    /** 世界维度 key 取出世界名，且保留下游的冒号。 */
    @Test
    void scopeOfExtractsTheWorldName() {
        assertEquals("nether", Metric.scopeOf("entities:world:nether"));
        assertEquals("overworld", Metric.scopeOf("chunks:world:overworld"));
        assertEquals("my:coloned:world", Metric.scopeOf("entities:world:my:coloned:world"),
            "世界名里的冒号必须保留——截断会让两个不同世界看起来是同一个");
    }

    /**
     * 非世界维度 key 返回空串，<b>不是 null</b>。
     *
     * <p>这个区别是有意的：{@code Alert.scope} 是 {@code String}，
     * {@code AlertCatalog.needsScope()} 用 {@code !scopeOrEmpty(alert).isEmpty()} 判断，
     * 而 {@code scopeOrEmpty} 处理 null 的方式是再包一层判空。返回空串让下游少一处判空。</p>
     */
    @Test
    void scopeOfReturnsEmptyStringForNonScopedKeys() {
        assertEquals("", Metric.scopeOf("tps:global"));
        assertEquals("", Metric.scopeOf("cpu:process"));
        assertEquals("", Metric.scopeOf("entities:total"),
            "全服实体数没有世界名");
        assertEquals("", Metric.scopeOf(null),
            "null key 返回空串而不是 null——下游 fields 少一处判空");
    }

    /**
     * {@code world:} 出现在非世界维度 key 里时也会被取出——这是 {@code indexOf} 的语义。
     *
     * <p>看起来像 bug，实际是刻意的宽松：{@code scopeOf} 只回答"这个字符串里有世界名吗"，
     * 不回答"这个指标该不该有世界名"。后者由 {@code metric.scopeScoped()} 负责。
     * 两层职责分开，{@code AlertCatalog.needsScope()} 里是两个条件的与。</p>
     *
     * <p>这条把当前语义钉下来：若有人给 {@code scopeOf} 加"必须是指标前缀才取"的守卫，
     * 这条会红——那会是个有意识的口径变更，不是意外。</p>
     */
    @Test
    void scopeOfIsMetricAgnostic() {
        assertEquals("nether", Metric.scopeOf("tps:world:nether"),
            "scopeOf 只找 world: 子串，不判断指标是否该有世界名——"
                + "「该不该有」是 metric.scopeScoped() 的职责，两者分开");
    }

    // ------------------------------------------------------------------------------------
    // 5. 两个函数的组合契约（Alert 工厂真正依赖的那个）
    // ------------------------------------------------------------------------------------

    /**
     * {@code ofKey} 与 {@code scopeOf} 必须自洽：{@code scopeScoped()} 为真的指标，
     * 其 key 经 {@code scopeOf} 必然取到非空世界名。
     *
     * <p>这是 {@code Alert.measured} 隐含的前提。两者分歧时：
     * {@code AlertCatalog.needsScope()} 会判 false → 世界维度告警走非 {@code .scoped} 模板
     * → 丢世界名，而 {@code scope} 字段里还留着那个名字（更让人困惑）。</p>
     */
    @Test
    void scopeScopedMetricsAlwaysCarryANonEmptyScope() {
        String[] worldKeys = {
            "entities:world:nether",
            "entities:world:overworld",
            "chunks:world:nether",
        };
        for (String key : worldKeys) {
            Metric m = Metric.ofKey(key);
            assertTrue(m != null && m.scopeScoped(),
                "前置条件：" + key + " 应解析成世界维度指标");
            assertTrue(!Metric.scopeOf(key).isEmpty(),
                key + " 的 scopeScoped() 为真，scopeOf 却取不到世界名——"
                    + "两者分歧会让 AlertCatalog 走错模板，告警丢世界名");
        }
    }

    /** 反过来：非世界维度指标不该带世界名（除非 key 畸形，它本来就不该出现）。 */
    @Test
    void nonScopedMetricsCarryNoScope() {
        String[] plainKeys = {"tps:global", "mspt:global", "players:online",
            "memory:heap", "cpu:process", "cpu:system", "entities:total"};
        for (String key : plainKeys) {
            Metric m = Metric.ofKey(key);
            assertTrue(m != null && !m.scopeScoped(),
                "前置条件：" + key + " 应解析成非世界维度指标");
            assertEquals("", Metric.scopeOf(key),
                key + " 不带世界名——若这里取到非空值，说明 scopeOf 的 world: 匹配过宽");
        }
    }
}
