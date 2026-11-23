/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.export;

import cn.perfscope.metrics.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prometheus 文本格式（0.0.4）渲染器（{@link MetricsTextRenderer}）。
 *
 * <p>这些用例锁的是<b>文本规范</b>而不是"能拼出字符串"：格式错了不会抛异常，
 * 只会让 Prometheus 抓取失败或在 Grafana 上画出一条假曲线，这类错误在开发机上
 * 完全看不出来。因此逐条锁住规范原文要求的性质：</p>
 * <ul>
 *   <li>末行必须以 {@code \n} 结尾（缺了会让最后一个样本被丢弃）</li>
 *   <li>同名指标的 {@code # HELP}/{@code # TYPE} 各只能有一条，且必须在样本之前</li>
 *   <li>{@code -1} 哨兵不得变成样本（否则 Grafana 画出贴底的假线）</li>
 *   <li>{@code Infinity} 必须映射成 {@code +Inf}（否则整个抓取解析失败）</li>
 *   <li>标签值只需转义 {@code \\}、{@code "}、{@code \n} 三个字符</li>
 * </ul>
 */
class MetricsTextRendererTest {

    /**
     * 断言输出里有这样一条样本，且**只比较"指标名 + 我关心的那几个标签 + 值"**，
     * 不关心标签出现的顺序、也不关心是否还带着别的标签。
     *
     * <p>为什么不能直接 {@code out.contains("perfscope_tps{period=\"1m\"}")}：
     * Prometheus 里标签顺序**没有语义**，而渲染器现在会给每条样本追加
     * {@code server_id}（多服抓同一个 Prometheus 时靠它区分，缺了会串服）。
     * 按字面前缀匹配的断言会因为多一个标签而全部失配——那锁的是标签顺序，
     * 不是文本规范。</p>
     *
     * @param out   渲染结果
     * @param name  指标名
     * @param pairs 期望的标签（{@code k=v}，值不带引号），可传空
     * @param value 期望的值；传 {@code null} 表示只校验指标名与标签，不校验值
     */
    private static void assertSample(String out, String name, String[] pairs, String value) {
        for (String line : out.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int sp = line.lastIndexOf(' ');
            if (sp <= 0) {
                continue;
            }
            String head = line.substring(0, sp);
            String got = line.substring(sp + 1);
            int brace = head.indexOf('{');
            String metric = brace < 0 ? head : head.substring(0, brace);
            if (!metric.equals(name)) {
                continue;
            }
            String labels = brace < 0 ? "" : head.substring(brace + 1, head.lastIndexOf('}'));
            boolean allPresent = true;
            for (String p : pairs) {
                int eq = p.indexOf('=');
                String k = p.substring(0, eq);
                String v = p.substring(eq + 1);
                if (!labels.contains(k + "=\"" + v + "\"")) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent && (value == null || value.equals(got))) {
                return;
            }
        }
        throw new AssertionError("no sample " + name + "{" + String.join(",", pairs) + "}"
            + (value == null ? "" : " " + value) + " in:\n" + out);
    }

    /** 只校验指标名存在（不校验值）。 */
    private static boolean hasSample(String out, String name, String[] pairs) {
        try {
            assertSample(out, name, pairs, null);
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    private static final String[] NO_LABELS = new String[0];

    private static MetricsTextRenderer.Context ctx() {
        return new MetricsTextRenderer.Context(
            "survival-1", "1.3.0", "Paper", "1.21.4", "21.0.10", "",
            System.currentTimeMillis(), System.currentTimeMillis() - 1000L,
            false, 0, 2, 1, null);
    }

    private static MetricsSnapshot snap() {
        MetricsSnapshot s = new MetricsSnapshot();
        s.timestamp = 1_700_000_000_000L;
        s.tps1m = 19.98;
        s.tps5m = 20.0;
        s.tps15m = 19.95;
        s.msptAvg = 50.05;
        s.msptMin = 12.3;
        s.msptMax = 88.8;
        s.playerCount = 12;
        s.maxPlayers = 100;
        s.totalEntities = 1234;
        s.totalLoadedChunks = 567;
        s.totalForceLoadedChunks = 42;
        s.tileEntityCount = 89;
        s.heapUsed = 1_073_741_824L;
        s.heapTotal = 2_147_483_648L;
        s.heapMax = 4_294_967_296L;
        s.nonHeapUsed = 200_000_000L;
        s.processCpuLoad = 42.5;
        s.systemCpuLoad = 88.0;
        s.gcCount = 77;
        s.gcTime = 1500;
        s.uptimeSeconds = 3600;
        s.threadCount = 65;
        s.loadedPluginCount = 8;
        s.worldCount = 2;

        Map<String, Integer> byWorld = new LinkedHashMap<>();
        byWorld.put("world", 1000);
        byWorld.put("world_nether", 234);
        s.entitiesByWorld = byWorld;

        Map<String, Integer> chunks = new LinkedHashMap<>();
        chunks.put("world", 500);
        chunks.put("world_nether", 67);
        s.chunksByWorld = chunks;

        Map<String, Integer> force = new LinkedHashMap<>();
        force.put("world", 42);
        s.forceLoadedChunksByWorld = force;

        Map<String, Integer> tiles = new LinkedHashMap<>();
        tiles.put("world", 89);
        s.tileEntitiesByWorld = tiles;

        return s;
    }

    // ==================== 规范性质 ====================

    @Test
    void everyLineEndsWithNewline() {
        String out = MetricsTextRenderer.render(snap(), ctx());
        assertTrue(out.endsWith("\n"), "the last line must end with \\n or Prometheus drops it");
        // 不允许出现空行：规范只认样本行与注释行
        for (String line : out.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;   // 末尾 split 产生的空串
            }
            assertFalse(line.isBlank(), "no blank lines are allowed in the body");
        }
    }

    @Test
    void helpAndTypeAppearAtMostOncePerMetric() {
        String out = MetricsTextRenderer.render(snap(), ctx());
        Map<String, Integer> helpCount = new LinkedHashMap<>();
        Map<String, Integer> typeCount = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            if (line.startsWith("# HELP ")) {
                String name = line.substring(7).split(" ")[0];
                helpCount.merge(name, 1, Integer::sum);
            } else if (line.startsWith("# TYPE ")) {
                String name = line.substring(7).split(" ")[0];
                typeCount.merge(name, 1, Integer::sum);
            }
        }
        helpCount.forEach((name, n) ->
            assertEquals(1, n, "# HELP must appear exactly once for " + name));
        typeCount.forEach((name, n) ->
            assertEquals(1, n, "# TYPE must appear exactly once for " + name));
        assertEquals(helpCount.keySet(), typeCount.keySet(),
            "every metric with HELP must also have TYPE");
    }

    @Test
    void helpAndTypePrecedeTheirSamples() {
        String out = MetricsTextRenderer.render(snap(), ctx());
        String[] lines = out.split("\n");
        Map<String, Boolean> declared = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.startsWith("# TYPE ")) {
                declared.put(line.substring(7).split(" ")[0], Boolean.TRUE);
            } else if (!line.startsWith("#")) {
                String name = line.substring(0, line.indexOf('{') >= 0 && line.indexOf('{') < line.indexOf(' ')
                    ? line.indexOf('{') : line.indexOf(' '));
                assertTrue(declared.containsKey(name),
                    "sample for " + name + " appeared before its # TYPE");
            }
        }
    }

    @Test
    void metricNamesAreValid() {
        String out = MetricsTextRenderer.render(snap(), ctx());
        requireSampleLines(out);
        for (String line : out.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String name = line.split("[{ ]")[0];
            assertTrue(name.matches("[a-zA-Z_:][a-zA-Z0-9_:]*"),
                "invalid metric name: " + name);
        }
    }

    /**
     * 守卫：断言输出里**至少有一行样本**（非注释、非空）。
     *
     * <p><b>为什么这个守卫是必须的</b>：下面两个用例都是"逐行校验样本行"的循环。
     * 若 {@code render()} 因回归返回空串、或只输出 HELP/TYPE 注释行，循环体一次都不执行，
     * 断言<b>从不运行</b>——两个用例双双全绿，而实际是什么都没渲染。</p>
     *
     * <p>当前之所以没出事，全靠夹具凑巧：{@code snap()} 填了 14 个字段且 {@code unavailable}
     * 为空，所以必然有样本行。但那是「夹具碰巧」，不是「断言保证」。夹具一改窄，
     * 这些用例就会静默失去意义。</p>
     */
    private static void requireSampleLines(String out) {
        long sampleLines = out.lines()
            .filter(l -> !l.isEmpty() && !l.startsWith("#"))
            .count();
        assertTrue(sampleLines > 0,
            "输出里一行样本都没有，下面的逐行校验会一次都不执行（恒真的空转）——"
                + "要么 render() 回归了，要么 snap() 夹具变窄了。实际输出：\n" + out);
    }

    // ==================== 哨兵与特殊值 ====================

    @Test
    void minusOneSentinelIsOmittedNotRendered() {
        MetricsSnapshot s = snap();
        s.processCpuLoad = -1;      // Windows 上恒为 -1
        s.msptMin = -1;             // TPS 反推路径没有极值
        String out = MetricsTextRenderer.render(s, ctx());

        assertFalse(hasSample(out, "perfscope_cpu_process_ratio", NO_LABELS),
            "an uncollected CPU metric must be absent, never -1 or -0.01");
        // 没有 min 时不应输出 stat="min"，但 avg/max 必须还在
        assertTrue(out.contains("stat=\"avg\""));
        // P0-U：tick_duration 现在多带一个 source 标签（见 tickDuration 的 javadoc）。
        // 断言按标签出现而非逐字整行：既有查询 {stat="avg"} 是子集选择器，仍然匹配。
        assertTrue(hasSample(out, "perfscope_tick_duration_seconds",
            new String[]{"stat=max", "source=measured"}),
            "the max sample must still be emitted (with the new source label): " + out);
        assertFalse(out.contains("stat=\"min\""));
    }

    /**
     * P0-U：TPS 反推路径的 tick 时长必须带 {@code source="tps_fallback"}。
     *
     * <p>这不是"多打一个标签"的装饰：{@code msptSource == TPS_FALLBACK} 时
     * {@code tick_duration_seconds} 度量的是 {@code 1000/TPS}——含服务器 sleep 的墙钟间隔，
     * 不是 tick 的计算耗时。没有这个标签，Grafana 上 50ms 与真实 5ms 长得一模一样。</p>
     */
    @Test
    void estimatedTickDurationIsLabeledAsTpsFallback() {
        MetricsSnapshot s = snap();
        s.msptAvg = 50.03;          // 1000/19.99，正是那条反推公式的输出
        s.msptMin = -1;
        s.msptMax = -1;
        s.msptSource = MetricsSnapshot.MSPT_SOURCE_TPS_FALLBACK;
        String out = MetricsTextRenderer.render(s, ctx());

        assertTrue(hasSample(out, "perfscope_tick_duration_seconds",
                new String[]{"stat=avg", "source=tps_fallback"}),
            "an estimated tick duration must carry source=tps_fallback: " + out);
        assertFalse(out.contains("source=\"measured\"") || hasSample(out, "perfscope_tick_duration_seconds", new String[]{"source=measured"}),
            "no sample of this snapshot is a real measurement, so none may be labeled measured: " + out);
        // HELP 必须说明这件事——只看标签不看文档，等于把发现成本转给下一个看板的人
        assertTrue(out.contains("ESTIMATE"),
            "the HELP text must state that source=tps_fallback is an estimate: " + out);
    }

    /**
     * P2-S2：{@code mspt == 0} 不可信，必须整行不输出（而不是发一条 {@code 0}）。
     *
     * <p>旧判据是 {@code msptAvg < 0}，0 会漏过去，Prometheus 收到
     * {@code tick_duration_seconds{stat="avg"} 0}——Grafana 上读作"这一 tick 零耗时"，
     * 而真相是"没测到"。项目口径见 {@code check-sentinels.mjs} 的
     * {@code SENTINEL_AND_ZERO_BOTH_BAD}。</p>
     */
    @Test
    void zeroTickDurationIsOmittedNotEmittedAsZero() {
        MetricsSnapshot s = snap();
        s.msptAvg = 0.0;
        s.msptMin = 0.0;
        s.msptMax = 0.0;
        String out = MetricsTextRenderer.render(s, ctx());

        assertFalse(hasSample(out, "perfscope_tick_duration_seconds", NO_LABELS),
            "0 ms per tick is physically impossible; emitting it draws a false baseline: " + out);
        assertFalse(out.contains("# HELP perfscope_tick_duration_seconds"),
            "with no sample at all the HELP/TYPE pair must be absent too: " + out);
    }

    /**
     * P2-S2 的第二半：avg 正常但 <b>某个</b>极值为 0 时，那一条必须单独被挡住。
     *
     * <p>上一例（三个都是 0）会被外层的"整组都不可信"守卫短路，于是<b>单条</b>的
     * {@code !(raw > 0)} 判据没有被任何用例覆盖——把 inner guard 改回 {@code raw < 0}
     * 时那一条仍然绿灯（变异实测：M5 存活）。这个用例的目标是 inner guard 本身：
     * avg=7.2 是可信读数，而 min=0 是"未填充槽位被塞进了快照"的典型形态，
     * 发出去会在 Grafana 上画出"总有一 tick 零耗时"的假地板线。</p>
     */
    @Test
    void individualZeroStatIsSkippedWhileTheOthersAreEmitted() {
        MetricsSnapshot s = snap();
        s.msptAvg = 7.2;
        s.msptMin = 0.0;
        s.msptMax = 88.8;
        String out = MetricsTextRenderer.render(s, ctx());

        assertTrue(hasSample(out, "perfscope_tick_duration_seconds", new String[]{"stat=avg"}),
            "the credible avg must still be emitted: " + out);
        assertTrue(hasSample(out, "perfscope_tick_duration_seconds", new String[]{"stat=max"}),
            "the credible max must still be emitted: " + out);
        assertFalse(out.contains("stat=\"min\""),
            "a 0 stat is not a reading and must be dropped individually — the group-level guard "
                + "does not cover this case: " + out);
    }

    @Test
    void infinityIsRenderedInPrometheusSpelling() {
        assertEquals("+Inf", MetricsTextRenderer.num(Double.POSITIVE_INFINITY));
        assertEquals("-Inf", MetricsTextRenderer.num(Double.NEGATIVE_INFINITY));
        assertEquals("NaN", MetricsTextRenderer.num(Double.NaN));
    }

    @Test
    void integralDoublesHaveNoTrailingPointZero() {
        assertEquals("20", MetricsTextRenderer.num(20.0));
        assertEquals("0", MetricsTextRenderer.num(0.0));
        assertEquals("19.98", MetricsTextRenderer.num(19.98));
    }

    /**
     * 科学计数法是本渲染器真实出过的一个缺陷：{@code Double.toString} 把时间戳写成
     * {@code 1.79435025823E9}、把 tick 时长写成 {@code 5.3E-4}。服主 curl 出来的第一手数据
     * 就是它，而且"E 记法是否被 exposition 解析器接受"需要查规范才能确认。
     * 普通十进制两种解读下都成立，所以这里锁死"绝不出现 E"。
     */
    @Test
    void neverEmitsScientificNotation() {
        double[] values = {
            1_794_350_258_230.0,      // 毫秒时间戳
            1_794_350_258.23,         // epoch 秒
            0.00053,                  // tick 时长（秒）
            0.000000123,              // 极小值
            1e14,                     // 大但仍在 long 范围内
            -0.0004,
        };
        for (double v : values) {
            String s = MetricsTextRenderer.num(v);
            assertFalse(s.toUpperCase(java.util.Locale.ROOT).contains("E"),
                "num(" + v + ") produced scientific notation: " + s);
        }
        assertEquals("1794350258.23", MetricsTextRenderer.num(1_794_350_258.23));
        assertEquals("0.00053", MetricsTextRenderer.num(0.00053));
    }

    @Test
    void renderedBodyContainsNoExponentsAnywhere() {
        MetricsSnapshot s = snap();
        s.timestamp = 1_794_350_258_230L;   // 毫秒时间戳，最容易触发 E 记法
        String out = MetricsTextRenderer.render(s, ctx());
        requireSampleLines(out);

        for (String line : out.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String value = line.substring(line.lastIndexOf(' ') + 1);
            assertFalse(value.toUpperCase(java.util.Locale.ROOT).contains("E"),
                "sample value used scientific notation: " + line);
        }
    }

    @Test
    void labelValuesEscapeOnlyTheThreeRequiredCharacters() {
        assertEquals("a\\\\b", MetricsTextRenderer.escape("a\\b"));
        assertEquals("a\\\"b", MetricsTextRenderer.escape("a\"b"));
        assertEquals("a\\nb", MetricsTextRenderer.escape("a\nb"));
        // 不需要转义的字符保持原样
        assertEquals("a'b", MetricsTextRenderer.escape("a'b"));
        assertEquals("世界", MetricsTextRenderer.escape("世界"));
    }

    @Test
    void worldNamesWithQuotesDoNotBreakTheFormat() {
        MetricsSnapshot s = snap();
        Map<String, Integer> evil = new LinkedHashMap<>();
        evil.put("world\"quote", 5);
        s.entitiesByWorld = evil;
        String out = MetricsTextRenderer.render(s, ctx());

        assertTrue(out.contains("world=\"world\\\"quote\""),
            "a world name containing a quote must be escaped, not injected raw");
        // 整份文本仍必须是合法结构：统计未被转义的引号，必须成对。
        // 注意不能直接数 '"' —— 转义后的 \" 里那个引号是数据，不是定界符。
        for (String line : out.split("\n")) {
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            long delimiters = 0;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) != '"') {
                    continue;
                }
                // 前面是奇数个反斜杠 = 该引号被转义，属于数据
                int backslashes = 0;
                for (int j = i - 1; j >= 0 && line.charAt(j) == '\\'; j--) {
                    backslashes++;
                }
                if (backslashes % 2 == 0) {
                    delimiters++;
                }
            }
            assertEquals(0, delimiters % 2,
                "unbalanced unescaped quotes in: " + line);
        }
    }

    // ==================== 内容正确性 ====================

    @Test
    void snapshotlessRenderStillEmitsTheHealthSignals() {
        String out = MetricsTextRenderer.render(null, ctx());

        assertTrue(hasSample(out, "perfscope_build_info", NO_LABELS), "build_info must survive a null snapshot");
        assertTrue(hasSample(out, "perfscope_snapshot_age_seconds", NO_LABELS),
            "age must be emitted even without a snapshot - it is the only 'collection is frozen' signal");
        assertTrue(hasSample(out, "perfscope_collector_degraded", NO_LABELS));
        // 但没有快照就不该有业务指标
        assertFalse(hasSample(out, "perfscope_tps", NO_LABELS));
        assertFalse(hasSample(out, "perfscope_entities", NO_LABELS));
    }

    @Test
    void perWorldSamplesShareOneMetricName() {
        String out = MetricsTextRenderer.render(snap(), ctx());

        // 规范做法是同一指标名 + world 标签，而不是 world_entities 这种另造名字
        assertSample(out, "perfscope_entities", new String[]{"world=world"}, "1000");
        assertSample(out, "perfscope_entities", new String[]{"world=world_nether"}, "234");
        assertFalse(hasSample(out, "perfscope_world_entities", NO_LABELS));
        // 总数样本与按世界样本共存
        assertSample(out, "perfscope_entities", NO_LABELS, "1234");

        long helpCount = out.lines()
            .filter(l -> l.equals("# HELP perfscope_entities Loaded entities in total and per world."))
            .count();
        assertEquals(1, helpCount);
    }

    @Test
    void moduleAvailabilityCoversEveryModule() {
        MetricsSnapshot s = snap();
        s.unavailable.add("chunks");
        String out = MetricsTextRenderer.render(s, ctx());

        assertEquals(11, MetricsTextRenderer.modules().size(),
            "module list must match the 11 collection modules");
        for (String module : MetricsTextRenderer.modules()) {
            assertTrue(hasSample(out, "perfscope_module_available", new String[]{"module=" + module}),
                "missing module_available for " + module);
        }
        assertSample(out, "perfscope_module_available", new String[]{"module=chunks"}, "0");
        // an unavailable module must read 0, not be omitted
        assertSample(out, "perfscope_module_available", new String[]{"module=tps"}, "1");
    }

    @Test
    void ratiosAreZeroToOneNotPercent() {
        String out = MetricsTextRenderer.render(snap(), ctx());

        // 12/100 = 0.12，不是 12.0
        assertSample(out, "perfscope_players_usage_ratio", NO_LABELS, "0.12");
        // ratios must be 0-1 per Prometheus convention
        assertSample(out, "perfscope_cpu_system_ratio", NO_LABELS, "0.88");
        assertFalse(hasSample(out, "perfscope_cpu_system_ratio", new String[]{"x=88"}));
    }

    @Test
    void alertEventCountersOmittedWhenNotYetImplemented() {
        // 空 map = 尚未实现计数器，整组不输出；不能发一堆 0 假装"从未告警"
        String out = MetricsTextRenderer.render(snap(), ctx());
        assertFalse(out.contains("perfscope_alert_events_total"));

        MetricsTextRenderer.Context withEvents = new MetricsTextRenderer.Context(
            "survival-1", "1.3.0", "Paper", "1.21.4", "21.0.10", "",
            System.currentTimeMillis(), System.currentTimeMillis(),
            false, 0, 2, 1, Map.of("open", 5L, "recover", 3L));
        String out2 = MetricsTextRenderer.render(snap(), withEvents);
        assertSample(out2, "perfscope_alert_events_total", new String[]{"type=open"}, "5");
        assertSample(out2, "perfscope_alert_events_total", new String[]{"type=recover"}, "3");
    }

    @Test
    void foliaTpsScopeIsCarriedAsALabel() {
        MetricsTextRenderer.Context folia = new MetricsTextRenderer.Context(
            "folia-1", "1.3.0", "Folia", "1.21.4", "21.0.10", "global_region",
            System.currentTimeMillis(), System.currentTimeMillis(), false, 0, 0, 0, null);
        String out = MetricsTextRenderer.render(snap(), folia);

        // Folia 的 TPS 只是 global region 的局部值，必须让抓取方看得见这个限制
        assertTrue(out.contains("tps_scope=\"global_region\""),
            "Folia must declare the TPS scope so Grafana does not over-trust it");
    }

    @Test
    void gcIsCounterAndMemoryIsBytes() {
        String out = MetricsTextRenderer.render(snap(), ctx());
        assertTrue(out.contains("# TYPE perfscope_gc_collections_total counter"));
        assertTrue(out.contains("# TYPE perfscope_heap_used_bytes gauge"));
        // 字节就是字节：1 GiB 应输出 1073741824，不是 1024
        assertSample(out, "perfscope_heap_used_bytes", NO_LABELS, "1073741824");
    }

    // ==================== 模块不可用时必须整行不输出 ====================

    /**
     * 模块被禁用/采集失败时，那一组指标必须<b>整行消失</b>，而不是发一个 0。
     *
     * <p><b>这条守的是一个致命缺陷</b>：{@code MetricsSnapshot} 只给 MSPT/CPU 设了 -1
     * 哨兵默认值，TPS / 玩家 / 实体 / 区块 / 方块实体 / 堆 / GC / uptime 全是 <b>0</b>。
     * 而 0 在 Prometheus 里是一条<b>正常样本</b>。</p>
     *
     * <p>所以旧实现在 {@code tps.enabled: false} 时发出
     * {@code perfscope_tps{period="1m"} 0}——Grafana 上读作"服务器彻底冻结"，
     * 而真实原因是服主自己关了 TPS 模块。任何 {@code perfscope_tps < 15} 的告警规则
     * 会在这种服上<b>永久 firing</b>。同理 Folia 上 {@code perfscope_entities 0}
     * 是"一个实体都没有"的硬断言。</p>
     *
     * <p>README 逐字承诺过「未采集的指标整行不输出……缺行让 Grafana 显示 gap，
     * 比画一条贴底的假线诚实」。这条测试把那个承诺变成可执行的。</p>
     */
    @Test
    void unavailableModulesEmitNoSampleInsteadOfZero() {
        MetricsSnapshot s = snap();
        s.unavailable.add(MetricsSnapshot.MODULE_TPS);
        s.unavailable.add(MetricsSnapshot.MODULE_MEMORY);
        s.unavailable.add(MetricsSnapshot.MODULE_GC);
        s.unavailable.add(MetricsSnapshot.MODULE_ENTITIES);
        s.unavailable.add(MetricsSnapshot.MODULE_CHUNKS);
        s.unavailable.add(MetricsSnapshot.MODULE_PLAYERS);

        String out = MetricsTextRenderer.render(s, ctx());

        for (String metric : new String[] {
            "perfscope_tps", "perfscope_heap_used_bytes", "perfscope_gc_collections_total",
            "perfscope_entities", "perfscope_chunks_loaded", "perfscope_players_online"}) {
            // 样本行形如 `metric{...} value` 或 `metric value`，且总是行首（前面是 \n 或字符串开头）。
            // 不能只查 " metric{"——换行后面不是空格。
            assertFalse(out.contains("\n" + metric + "{") || out.contains("\n" + metric + " "),
                "metric '" + metric + "' must be absent (not zero) when its module is unavailable: "
                    + "a 0 reads as a real sample in Prometheus, and 'TPS 0' reads as 'server frozen'");
            assertFalse(out.contains("# HELP " + metric + " "),
                "a group that is not emitted must not leave its HELP/TYPE header behind either: " + metric);
        }

        // module_available 这一组本身必须还在——它正是告诉抓取方"别信这些缺失"的
        assertSample(out, "perfscope_module_available", new String[]{"module=tps"}, "0");
        // the availability signal itself must survive; it is how the scraper knows the gap is real
    }

    /** 模块可用时必须正常输出（守卫不能把所有东西都挡掉）。 */
    @Test
    void availableModulesStillEmitTheirSamples() {
        MetricsSnapshot s = snap();   // 默认没有任何模块进 unavailable
        String out = MetricsTextRenderer.render(s, ctx());

        assertTrue(hasSample(out, "perfscope_tps", new String[]{"period=1m"}), "TPS is available here");
        assertTrue(out.contains("perfscope_heap_used_bytes "), "memory is available here");
        assertTrue(out.contains("perfscope_entities "), "entities are available here");
    }

    // ==================== 跨服身份标签（server_id）====================

    /**
     * **每一条样本**都必须带上 `server_id`，否则多服抓进同一个 Prometheus 时会串服。
     *
     * <p>这条守的是一个「静默产生错数据」的缺陷：`server_id` 此前只出现在
     * `perfscope_build_info` 一条序列上，其余几十条都没有。两个服被抓进同一个
     * Prometheus 时，`perfscope_tps{period="1m"}` 在每个服上都是**同一组标签**，
     * Prometheus 报 duplicate sample 或后者覆盖前者——看到的是一个随机某台服的数字，
     * 而且**不会报任何错**。</p>
     *
     * <p>断言方式刻意是「扫每一条样本」而不是「挑几条看看」：漏一条就是一条序列会串服，
     * 而那种漏检在单服测试里完全看不出来。</p>
     */
    @Test
    void everySampleCarriesTheServerIdLabel() {
        String out = MetricsTextRenderer.render(snap(), ctx());
        int samples = 0;
        for (String line : out.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            samples++;
            assertTrue(line.contains("server_id=\"survival-1\""),
                "every sample must carry server_id, otherwise multi-server scrapes collide: " + line);
        }
        assertTrue(samples > 20, "precondition: this snapshot should emit many samples, got " + samples);
    }

    /** `server_id` 只能出现一次——重复的标签名会让 Prometheus 整次抓取判格式错误。 */
    @Test
    void serverIdLabelAppearsOnlyOncePerLine() {
        String out = MetricsTextRenderer.render(snap(), ctx());
        for (String line : out.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int first = line.indexOf("server_id=\"");
            assertEquals(-1, line.indexOf("server_id=\"", first + 1),
                "a duplicated label name makes the whole scrape fail to parse: " + line);
        }
    }

    /** 没配 server-id 时不注入空标签——空值只会让 Grafana 的筛选器更难写。 */
    @Test
    void emptyServerIdInjectsNoLabel() {
        MetricsTextRenderer.Context noId = new MetricsTextRenderer.Context(
            "", "1.3.0", "Paper", "1.21.4", "21.0.10", "",
            System.currentTimeMillis(), System.currentTimeMillis() - 1000L,
            false, 0, 2, 1, null);
        String out = MetricsTextRenderer.render(snap(), noId);
        // perfscope_build_info 本来就带 server_id（即使为空），那是既有契约。
        // 这里只要求**其余样本**不被注入空标签。
        for (String line : out.split("\n")) {
            if (line.startsWith("perfscope_build_info{")) {
                continue;
            }
            assertFalse(line.contains("server_id=\"\""),
                "an empty id must not become an empty label on: " + line);
        }
        assertTrue(hasSample(out, "perfscope_tps", new String[]{"period=1m"}));
    }
}
