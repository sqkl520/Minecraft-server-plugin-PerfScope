/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.support;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;

import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * {@link ConfigManager#validateThresholds} 一类配置校验测试的通用装配。
 *
 * <p>此前 {@code validConfig()} 在 {@code ThresholdPairZeroValidationTest} 与
 * {@code UpdateIntervalValidationTest} 里各写了一遍，逐字段一模一样。第三、第四个调用方
 * （double 阈值对、{@code HH:mm} 解析）进来时，任何一次字段增删都要改四处——而漏改的那一处
 * 不会编译失败，只会让那个测试开始断言"未初始化字段的默认值"，看上去仍然绿。</p>
 *
 * <p><b>为什么 {@code validConfig()} 必须把每个字段都显式写满</b>：{@code validateThresholds}
 * 会校验全部字段，任何一个留在 {@code 0}/{@code null} 都会额外触发一条回退。断言"某个值没被改"
 * 时如果有别的回退在跑，噪音会掩盖真正的失败。所以这里列全，且只列<b>合法</b>值。</p>
 */
public final class ConfigFixtures {

    private ConfigFixtures() {
    }

    /**
     * 一份<b>每一项都合法</b>的配置，作为"只改被测那一项"的基线。
     *
     * <p>字段增删时同步改这里即可；各测试类不必再各自维护一份。</p>
     */
    public static ConfigManager.ConfigData validConfig() {
        ConfigManager.ConfigData c = new ConfigManager.ConfigData();
        c.general_updateInterval = 20;
        c.general_language = "en";
        c.tps_warningThreshold = 18.0;
        c.tps_criticalThreshold = 15.0;
        c.mspt_warningThreshold = 45.0;
        c.mspt_criticalThreshold = 50.0;
        c.players_warningThreshold = 90;
        c.players_criticalThreshold = 98;
        c.entities_totalWarningThreshold = 20000;
        c.entities_totalCriticalThreshold = 30000;
        c.chunks_loadedWarningThreshold = 10000;
        c.chunks_loadedCriticalThreshold = 15000;
        c.memory_heapWarningThreshold = 85;
        c.memory_heapCriticalThreshold = 95;
        c.cpu_processWarningThreshold = 80.0;
        c.cpu_systemWarningThreshold = 90.0;
        c.cpu_hysteresisMargin = 5.0;
        c.alerts_cooldownSeconds = 300;
        c.alerts_debounceCycles = 3;
        c.alerts_dailySummaryTime = "08:00";
        c.alerts_webhookType = "discord";
        c.history_sampleIntervalSeconds = 60;
        c.history_persistRetentionDays = 7;
        c.hotspots_sampleEveryCycles = 30;
        c.hotspots_chunkWarningThreshold = 200;
        c.hotspots_scanShards = 10;
        c.prometheus_port = 8940;
        c.prometheus_path = "/metrics";
        return c;
    }

    /**
     * 造一个能调 {@code validateThresholds} 的 {@link ConfigManager}。
     *
     * <p>{@code validateThresholds} 的全部副作用都写在传入的 {@code ConfigData} 上，
     * 不读 Bukkit、不写盘；{@code plugin} 只为 {@code getLogger()} 存在。
     * 因此用 {@link AlertFixtures#barePlugin} 造一个只有 logger 的壳就够了。</p>
     */
    public static ConfigManager manager(Logger logger) {
        PerfScope p = AlertFixtures.barePlugin(logger);
        return new ConfigManager(p);
    }

    /** {@link #manager(Logger)} 的便捷形式，用独立 logger 名以免测试间串日志。 */
    public static ConfigManager manager(String loggerName) {
        return manager(Logger.getLogger(loggerName));
    }

    /**
     * 直接调私有 {@code validateThresholds}，让校验结果真实写回 {@code cfg}。
     *
     * <p>不走 {@code load()}：那需要 {@code dataFolder}、资源流与 Bukkit 配置 API。
     * 本方法与 {@code load()} 调用的是同一个方法，只跳过读盘那一段。</p>
     */
    public static void validate(ConfigManager cm, ConfigManager.ConfigData cfg) {
        TestObjects.invoke(cm, "validateThresholds",
            new Class<?>[]{ConfigManager.ConfigData.class}, cfg);
    }

    /**
     * 捕获 {@code logger} 上的全部 WARNING 文案，跑一次校验，返回拼接后的文本。
     *
     * <p>断言"回退不能静默"时必须读渲染后的文案——而文案来自 {@code Messages}，
     * 所以调用方需先完成 {@link AlertFixtures#initMessages()}，否则拿到的只是键名。</p>
     */
    public static String warningsOf(String loggerName, Consumer<ConfigManager.ConfigData> setup) {
        StringBuilder sb = new StringBuilder();
        Logger logger = Logger.getLogger(loggerName);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.WARNING && r.getMessage() != null) {
                    sb.append(r.getMessage()).append('\n');
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(h);
        try {
            ConfigManager.ConfigData cfg = validConfig();
            setup.accept(cfg);
            validate(manager(logger), cfg);
        } finally {
            logger.removeHandler(h);
        }
        return sb.toString();
    }
}
