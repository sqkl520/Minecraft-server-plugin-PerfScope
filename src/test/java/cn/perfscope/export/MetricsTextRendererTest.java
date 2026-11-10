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
        for (String line : out.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String name = line.split("[{ ]")[0];
            assertTrue(name.matches("[a-zA-Z_:][a-zA-Z0-9_:]*"),
                "invalid metric name: " + name);
        }
    }

    // ==================== 哨兵与特殊值 ====================

    @Test
    void minusOneSentinelIsOmittedNotRendered() {
        MetricsSnapshot s = snap();
        s.processCpuLoad = -1;      // Windows 上恒为 -1
        s.msptMin = -1;             // TPS 反推路径没有极值
        String out = MetricsTextRenderer.render(s, ctx());

        assertFalse(out.contains("perfscope_cpu_process_ratio"),
            "an uncollected CPU metric must be absent, never -1 or -0.01");
        // 没有 min 时不应输出 stat="min"，但 avg/max 必须还在
        assertTrue(out.contains("stat=\"avg\""));
        assertTrue(out.contains("perfscope_tick_duration_seconds{stat=\"max\"}"));
        assertFalse(out.contains("stat=\"min\""));
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

        assertTrue(out.contains("perfscope_build_info"), "build_info must survive a null snapshot");
        assertTrue(out.contains("perfscope_snapshot_age_seconds"),
            "age must be emitted even without a snapshot - it is the only 'collection is frozen' signal");
        assertTrue(out.contains("perfscope_collector_degraded"));
        // 但没有快照就不该有业务指标
        assertFalse(out.contains("perfscope_tps"));
        assertFalse(out.contains("perfscope_entities"));
    }

    @Test
    void perWorldSamplesShareOneMetricName() {
        String out = MetricsTextRenderer.render(snap(), ctx());

        // 规范做法是同一指标名 + world 标签，而不是 world_entities 这种另造名字
        assertTrue(out.contains("perfscope_entities{world=\"world\"} 1000"));
        assertTrue(out.contains("perfscope_entities{world=\"world_nether\"} 234"));
        assertFalse(out.contains("perfscope_world_entities"));
        // 总数样本与按世界样本共存
        assertTrue(out.contains("perfscope_entities 1234"));

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
            assertTrue(out.contains("perfscope_module_available{module=\"" + module + "\"}"),
                "missing module_available for " + module);
        }
        assertTrue(out.contains("perfscope_module_available{module=\"chunks\"} 0"),
            "an unavailable module must read 0, not be omitted");
        assertTrue(out.contains("perfscope_module_available{module=\"tps\"} 1"));
    }

    @Test
    void ratiosAreZeroToOneNotPercent() {
        String out = MetricsTextRenderer.render(snap(), ctx());

        // 12/100 = 0.12，不是 12.0
        assertTrue(out.contains("perfscope_players_usage_ratio 0.12"),
            "ratios must be 0-1 per Prometheus convention");
        assertTrue(out.contains("perfscope_cpu_system_ratio 0.88"));
        assertFalse(out.contains("perfscope_cpu_system_ratio 88"));
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
        assertTrue(out2.contains("perfscope_alert_events_total{type=\"open\"} 5"));
        assertTrue(out2.contains("perfscope_alert_events_total{type=\"recover\"} 3"));
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
        assertTrue(out.contains("perfscope_heap_used_bytes 1073741824"));
    }
}
