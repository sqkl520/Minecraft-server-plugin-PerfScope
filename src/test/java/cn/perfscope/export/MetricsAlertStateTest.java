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
 * 逐告警指标 {@code perfscope_alert}（v1.4）。
 *
 * <p>设计上有三个刻意的决定，每个都对应一类会真正伤人的错误实现：</p>
 * <ol>
 *   <li><b>级别走值不走标签</b>。若做成 {@code {severity="warning"}}，级别升级时
 *       Prometheus 看到的是「一条序列 stale、另一条全新」，{@code ALERTS_FOR_STATE} 归零，
 *       运维的 {@code for: 5m} 每次翻转都重新计时——抖动治理在指标层被完全抵消。</li>
 *   <li><b>健康也输出 0</b>。否则恢复时序列直接消失，既收不到 resolved 通知，
 *       也无法用 {@code absent()} 区分"没告警"与"采集挂了"。</li>
 *   <li><b>不可判定的维度不发 0</b>。给没测的维度发 0 等于宣称它健康。</li>
 * </ol>
 */
class MetricsAlertStateTest {

    private static MetricsSnapshot snap() {
        MetricsSnapshot s = new MetricsSnapshot();
        s.timestamp = 1_700_000_000_000L;
        s.tps1m = 20.0;
        s.tps5m = 20.0;
        s.tps15m = 20.0;
        s.heapMax = 4_294_967_296L;
        s.maxPlayers = 20;
        return s;
    }

    private static MetricsTextRenderer.Context ctx() {
        return new MetricsTextRenderer.Context(
            "survival-1", "1.3.0", "Paper", "1.21.4", "21.0.10", "",
            System.currentTimeMillis(), System.currentTimeMillis() - 500L,
            false, 0, 1, 0, null);
    }

    private static String onlyAlertLines(String out) {
        StringBuilder sb = new StringBuilder();
        for (String line : out.split("\n")) {
            if (line.startsWith("perfscope_alert{")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    // ==================== 核心行为 ====================

    /** 健康时也输出 0——这是"恢复可见"与 absent() 无歧义的前提。 */
    @Test
    void healthyKeysAreEmittedAsZero() {
        MetricsSnapshot s = snap();
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("tps:global", 0);
        levels.put("memory:global", 0);
        s.alertLevels = levels;

        String out = MetricsTextRenderer.render(s, ctx());
        assertTrue(out.contains("perfscope_alert{key=\"tps:global\",module=\"tps\"} 0"), out);
        assertTrue(out.contains("perfscope_alert{key=\"memory:global\",module=\"memory\"} 0"), out);
    }

    @Test
    void levelsAreValuesNotLabels() {
        MetricsSnapshot s = snap();
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("tps:global", 2);
        s.alertLevels = levels;

        String out = MetricsTextRenderer.render(s, ctx());
        // 关键断言：级别出现在**值**的位置，而不是 label 里
        assertTrue(out.contains("perfscope_alert{key=\"tps:global\",module=\"tps\"} 2"), out);
        assertFalse(out.contains("severity="),
            "severity must NOT be a label - that makes each level change a new series and breaks `for:`");
    }

    /**
     * 级别从 2 降到 0 时，<b>同一行仍然存在</b>，只是值变了。
     *
     * <p>这条打死两类错误实现：只在告警时输出的（第二份快照里这行会消失），
     * 以及把健康省略掉的。</p>
     */
    @Test
    void recoveryKeepsTheSeriesAndDropsValueToZero() {
        MetricsSnapshot critical = snap();
        Map<String, Integer> lv1 = new LinkedHashMap<>();
        lv1.put("chunks:world:world", 2);
        critical.alertLevels = lv1;
        String before = onlyAlertLines(MetricsTextRenderer.render(critical, ctx()));

        MetricsSnapshot healthy = snap();
        Map<String, Integer> lv2 = new LinkedHashMap<>();
        lv2.put("chunks:world:world", 0);
        healthy.alertLevels = lv2;
        String after = onlyAlertLines(MetricsTextRenderer.render(healthy, ctx()));

        assertTrue(before.contains("perfscope_alert{key=\"chunks:world:world\",module=\"chunks\"} 2"), before);
        assertTrue(after.contains("perfscope_alert{key=\"chunks:world:world\",module=\"chunks\"} 0"), after);
        // 样本条数不变——序列没有消失
        assertEquals(before.lines().count(), after.lines().count(),
            "the series must survive recovery; only its value changes");
    }

    /** 不可判定的维度（不在 alertLevels 里）不得以 0 出现。 */
    @Test
    void unjudgeableKeysAreAbsentNotZero() {
        MetricsSnapshot s = snap();
        // 只放 tps —— chunks 模块被禁用时就是这种情况
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("tps:global", 0);
        s.alertLevels = levels;

        String out = MetricsTextRenderer.render(s, ctx());
        assertTrue(out.contains("key=\"tps:global\""));
        assertFalse(out.contains("key=\"chunks:world:"),
            "a disabled module must not emit healthy-looking zeros for worlds it never measured");
    }

    // ==================== 渲染器约束 ====================

    /**
     * HELP/TYPE 必须无条件输出，即使一条告警都没有。
     *
     * <p>否则 {@code absent(perfscope_alert)} 无法区分"没有告警"和"端点没数据"。</p>
     */
    @Test
    void helpAndTypeAreEmittedEvenWithNoAlerts() {
        MetricsSnapshot s = snap();   // alertLevels 为空
        String out = MetricsTextRenderer.render(s, ctx());

        assertTrue(out.contains("# HELP perfscope_alert "), "HELP must be unconditional");
        assertTrue(out.contains("# TYPE perfscope_alert gauge"), "TYPE must be unconditional");
        assertFalse(out.contains("perfscope_alert{"), "but no samples when nothing is judgeable");
    }

    /**
     * 世界名里的引号与换行必须被转义。
     *
     * <p>未转义的 {@code \n} 会把样本劈成两行，而 Prometheus 会因此拒绝**整份**响应体——
     * 一个名字怪异的世界就能让整个端点抓取失败。</p>
     */
    @Test
    void worldNamesWithSpecialCharactersAreEscaped() {
        MetricsSnapshot s = snap();
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("chunks:world:ev\"il\nx", 1);
        s.alertLevels = levels;

        String out = MetricsTextRenderer.render(s, ctx());
        assertTrue(out.contains("key=\"chunks:world:ev\\\"il\\nx\""),
            "quote and newline must be escaped: " + out);

        // 整份文本仍然结构完好：每个样本行不得因换行被劈开
        for (String line : out.split("\n")) {
            if (line.startsWith("perfscope_alert{")) {
                assertEquals(0, countUnescapedQuotes(line) % 2,
                    "unbalanced quotes in: " + line);
            }
        }
    }

    /** 模块名从键前缀推导，不需要额外数据。 */
    @Test
    void moduleLabelIsDerivedFromKeyPrefix() {
        assertEquals("tps", MetricsTextRenderer.moduleOfKey("tps:global"));
        assertEquals("chunks", MetricsTextRenderer.moduleOfKey("chunks:world:world_nether"));
        assertEquals("cpu", MetricsTextRenderer.moduleOfKey("cpu:process"));
        assertEquals("weird", MetricsTextRenderer.moduleOfKey("weird"));
        assertEquals("", MetricsTextRenderer.moduleOfKey(null));
    }

    /**
     * 采集器挂了（{@code snap == null}）时，告警段仍然输出。
     *
     * <p>{@code snap == null} 恰恰是"采集器自己坏了"的时刻——那正是最该看告警指标的时候。
     * 旧结构里 {@code alerts()} 在 {@code snap == null} 的 return 之后，永远执行不到。</p>
     */
    @Test
    void alertsSurviveANullSnapshot() {
        String out = MetricsTextRenderer.render(null, ctx());
        assertTrue(out.contains("# HELP perfscope_alert "),
            "alert metadata must survive a null snapshot - that is exactly when collection died");
        assertTrue(out.contains("perfscope_alerts_active"));
    }

    /** 输出顺序跟随插入序，保证两次抓取的 diff 可读。 */
    @Test
    void outputOrderFollowsInsertionOrder() {
        MetricsSnapshot s = snap();
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("tps:global", 0);
        levels.put("mspt:global", 0);
        levels.put("memory:global", 0);
        s.alertLevels = levels;

        String out = onlyAlertLines(MetricsTextRenderer.render(s, ctx()));
        String[] lines = out.strip().split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].contains("tps:global"), out);
        assertTrue(lines[1].contains("mspt:global"), out);
        assertTrue(lines[2].contains("memory:global"), out);
    }

    /** 值只能取 0/1/2，且必须是整数（不带小数点）。 */
    @Test
    void valuesAreIntegersInRange() {
        MetricsSnapshot s = snap();
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("tps:global", 0);
        levels.put("mspt:global", 1);
        levels.put("memory:global", 2);
        s.alertLevels = levels;

        String out = MetricsTextRenderer.render(s, ctx());
        for (String line : out.split("\n")) {
            if (!line.startsWith("perfscope_alert{")) {
                continue;
            }
            String value = line.substring(line.lastIndexOf(' ') + 1);
            assertTrue(value.matches("[012]"), "value must be 0/1/2 with no decimal point: " + line);
        }
    }

    // ==================== 工具 ====================

    private static long countUnescapedQuotes(String line) {
        long n = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '"') {
                continue;
            }
            int backslashes = 0;
            for (int j = i - 1; j >= 0 && line.charAt(j) == '\\'; j--) {
                backslashes++;
            }
            if (backslashes % 2 == 0) {
                n++;
            }
        }
        return n;
    }
}
