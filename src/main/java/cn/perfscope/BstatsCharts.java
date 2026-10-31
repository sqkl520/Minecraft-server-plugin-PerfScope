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
package cn.perfscope;

import cn.perfscope.compat.ServerCompat;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.CustomChart;
import org.bstats.charts.SimpleBarChart;
import org.bstats.charts.SingleLineChart;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * bStats 自定义图表（Wave 10）。
 *
 * <p><b>为什么要有这个类</b>：仅有匿名基础数据（服务端类型 / MC 版本 / 玩家数）不足以回答
 * 路线图决策真正要问的问题——"大服到底有多大"、"用户开了哪些模块"、"大家是不是在用英文"。
 * 这三个问题分别对应「规模」「采用率」「i18n 收益」，都是判断某个功能该不该做的前置证据。</p>
 *
 * <p><b>隐私边界（硬约束）</b>：只上报<b>分桶后的整数</b>——实体数取量级桶、内存取 MB 整数、
 * 玩家数取原始整数。不上报世界名、玩家名、插件列表、配置文件内容、告警文本、坐标。
 * 分桶是有意的：既能判断量级，又让单条记录无法反推到具体服务器。用户可用
 * {@code general.bstats-enabled=false} 整体关闭。</p>
 *
 * <p><b>异步契约</b>：bStats 在自己的线程上调用这些 {@link Callable}，绝不在主线程。因此这里
 * 只读 {@link MetricsCollector#getLatest()} 返回的不可变快照与配置对象，不触碰 Bukkit 世界状态。
 * 快照为 null（首周期未到）时返回空 Map，bStats 会跳过该次图表上报，不会记 0——记 0 会污染数据。</p>
 *
 * <p><b>防常量折叠</b>：与 {@code startBstats()} 同理，图表引用的类必须真实存在于字节码中，
 * 才能用 javap 验证 shade 已把 {@code org.bstats.charts.*} 重写为
 * {@code cn.perfscope.shaded.bstats.charts.*}（发布门禁的 relocate 实证）。</p>
 */
final class BstatsCharts {

    /** 实体数量级分桶的边界（上界含）。最后一桶为 "50000+"。 */
    private static final int[] ENTITY_BUCKETS = {0, 100, 500, 1000, 2500, 5000, 10000, 25000, 50000};

    /** 已加载区块数量级分桶的边界（上界含）。最后一桶为 "100000+"。 */
    private static final int[] CHUNK_BUCKETS = {0, 100, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000};

    private BstatsCharts() {
    }

    /**
     * 把全部自定义图表挂到 {@code metrics} 上。
     *
     * <p>每个图表单独 try-catch：某一个图表构造失败（例如 bStats 版本差异导致类缺失）时
     * 其余图表照常工作，且遥测层的问题绝不冒泡到插件启动路径。</p>
     */
    static void register(Metrics metrics, PerfScope plugin) {
        MetricsCollector collector = plugin.getMetricsCollector();
        ConfigManager configManager = plugin.getConfigManager();
        if (collector == null || configManager == null) {
            return;   // 启动顺序异常时的防御；正常路径不会走到
        }
        addSafely(metrics, plugin, () -> new SimpleBarChart("server_software", () ->
            single(ServerCompat.getServerName())));
        addSafely(metrics, plugin, () -> new AdvancedPie("module_enabled", () -> {
            ConfigManager.ConfigData cfg = configManager.get();
            if (cfg == null) {
                return Map.of();
            }
            Map<String, Integer> out = new HashMap<>();
            out.put("tps", cfg.tps_enabled ? 1 : 0);
            out.put("mspt", cfg.mspt_enabled ? 1 : 0);
            out.put("players", cfg.players_enabled ? 1 : 0);
            out.put("entities", cfg.entities_enabled ? 1 : 0);
            out.put("chunks", cfg.chunks_enabled ? 1 : 0);
            out.put("tile_entities", cfg.tileEntities_enabled ? 1 : 0);
            out.put("memory", cfg.memory_enabled ? 1 : 0);
            out.put("cpu", cfg.cpu_enabled ? 1 : 0);
            out.put("hotspots", cfg.hotspots_enabled ? 1 : 0);
            out.put("alerts", cfg.alerts_enabled ? 1 : 0);
            out.put("history", cfg.history_enabled ? 1 : 0);
            return out;
        }));
        addSafely(metrics, plugin, () -> new SimpleBarChart("language", () -> {
            ConfigManager.ConfigData cfg = configManager.get();
            return cfg == null ? Map.of() : single(normalizeLanguage(cfg.general_language));
        }));
        addSafely(metrics, plugin, () -> new AdvancedPie("webhook_channel", () -> {
            ConfigManager.ConfigData cfg = configManager.get();
            if (cfg == null) {
                return Map.of();
            }
            // 只上报"用哪一类通道"，不上报 URL（URL 是凭据）
            String type = cfg.alerts_webhookType;
            if (type == null || type.isBlank() || "none".equalsIgnoreCase(type)) {
                return single("none");
            }
            return single(type.trim().toLowerCase(Locale.ROOT));
        }));
        addSafely(metrics, plugin, () -> new SimpleBarChart("entity_count_bucket", () -> {
            MetricsSnapshot snap = collector.getLatest();
            return snap == null ? Map.of() : single(bucket(snap.totalEntities, ENTITY_BUCKETS, "50000+"));
        }));
        addSafely(metrics, plugin, () -> new SimpleBarChart("loaded_chunks_bucket", () -> {
            MetricsSnapshot snap = collector.getLatest();
            return snap == null ? Map.of() : single(bucket(snap.totalLoadedChunks, CHUNK_BUCKETS, "100000+"));
        }));
        addSafely(metrics, plugin, () -> new SimpleBarChart("peak_players_bucket", () -> {
            MetricsSnapshot snap = collector.getLatest();
            return snap == null ? Map.of() : single(bucket(snap.playerCount, ENTITY_BUCKETS, "50000+"));
        }));
        addSafely(metrics, plugin, () -> new SimpleBarChart("heap_max_mb_bucket", () -> {
            MetricsSnapshot snap = collector.getLatest();
            if (snap == null || snap.heapMax <= 0L) {
                return Map.of();
            }
            int mb = (int) (snap.heapMax / (1024L * 1024L));
            return single(bucket(mb, new int[]{1024, 2048, 4096, 8192, 16384, 32768, 65536}, "65536+"));
        }));
        addSafely(metrics, plugin, () -> new SimpleBarChart("world_count", () -> {
            MetricsSnapshot snap = collector.getLatest();
            return snap == null ? Map.of() : single(bucket(snap.worldCount, new int[]{1, 3, 5, 10}, "10+"));
        }));
        addSafely(metrics, plugin, () -> new SingleLineChart("loaded_plugins", () -> {
            MetricsSnapshot snap = collector.getLatest();
            return snap == null ? null : snap.loadedPluginCount;
        }));
    }

    /**
     * 单值分桶：返回 {@code <=bound} 的区间标签，找不到时用 {@code overflowLabel}。
     *
     * <p>用上界标签（"100" 表示 0–100）而不是区间写法，是为了让 bStats 后台的柱状图能按
     * 数值自然排序，不必依赖字符串排序。</p>
     */
    static String bucket(int value, int[] bounds, String overflowLabel) {
        for (int bound : bounds) {
            if (value <= bound) {
                return String.valueOf(bound);
            }
        }
        return overflowLabel;
    }

    /** 语言归一：只保留白名单值，避免把用户的自定义写法原样上报。 */
    static String normalizeLanguage(String raw) {
        if (raw == null) {
            return "unknown";
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "en":
            case "zh":
                return raw.trim().toLowerCase(Locale.ROOT);
            default:
                return "other";
        }
    }

    private static Map<String, Integer> single(String key) {
        return Map.of(key, 1);
    }

    /**
     * 挂载单个图表，失败只记一条 debug 日志。
     *
     * <p>用 {@link Callable} 包装构造过程而不是直接 new：图表构造函数本身可能因 bStats
     * 版本差异抛 {@link NoClassDefFoundError}/ {@link NoSuchMethodError}，那样的话连 lambda
     * 都还没开始执行就已经失败，内部 try-catch 拦不住。返回值声明为 {@link CustomChart}
     * 而非 {@code Object}，让编译器在本类内部就校验类型，不必在调用点强转。</p>
     */
    private static void addSafely(Metrics metrics, PerfScope plugin, Callable<? extends CustomChart> factory) {
        try {
            metrics.addCustomChart(factory.call());
        } catch (Exception | LinkageError e) {
            ConfigManager.ConfigData cfg = plugin.getConfigManager() == null
                ? null : plugin.getConfigManager().get();
            if (cfg != null && cfg.general_debug) {
                plugin.getLogger().fine("bStats chart skipped: " + e);
            }
        }
    }
}
