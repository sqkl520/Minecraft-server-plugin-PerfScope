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
package cn.perfscope.config;

import cn.perfscope.support.ConfigFixtures;
import cn.perfscope.util.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置校验告警必须跟随 {@code general.language}（P2 任务 3 的验收）。
 *
 * <h2>这批日志此前是什么样</h2>
 *
 * <p>{@code network.push-interval-seconds} 的两条校验告警是 {@code plugin.getLogger()} 调用里的
 * 中文字面量。{@code general.language=en} 的服主改错配置时，控制台给出的仍是中文——
 * 而这两条恰恰是"你的配置被插件改了"的唯一通知，读不懂等于没通知。</p>
 *
 * <h2>判据为什么必须双向</h2>
 *
 * <p>只断言"英文文案出现"会把实现锁死成硬编码英文；只断言"中文消失"同理。
 * 只有<b>同一份代码在两种语言下产出两种文案</b>才证明它真的走了消息表。
 * 这与 {@code NetworkLogLocalisationTest} 的口径一致。</p>
 */
class ConfigCheckLogLocalisationTest {

    private static final String LOGGER = "pscope-config-check-i18n";

    @BeforeAll
    static void initMessages() {
        ConfigMessages.init();
        Messages.load(null);
    }

    /** 中文：越界回退那条告警必须是中文，且点名原值与回退值。 */
    @Test
    @DisplayName("push-interval 越界告警：zh 下是中文并点名三个数")
    void pushIntervalOutOfRangeIsChineseUnderZh() {
        try {
            Messages.load(pluginWithLanguage(Messages.LOCALE_ZH));
            String warnings = ConfigFixtures.warningsOf(LOGGER + "-zh-out", c -> {
                c.network_pushIntervalSeconds = 500;   // 上限 300 → 回退默认 10
            });

            assertTrue(warnings.contains("network.push-interval-seconds=500"),
                "必须点名键与用户写的值，否则服主不知道该改哪一行；got: " + warnings);
            assertTrue(warnings.contains("已回退"),
                "中文界面下必须是中文模板；got: " + warnings);
            assertTrue(hasCjk(warnings),
                "中文模板必须真的含中文；got: " + warnings);
        } finally {
            Messages.load(null);
        }
    }

    /** 英文：同一份代码在 en 下必须是英文——这是"走消息表"的唯一证据。 */
    @Test
    @DisplayName("push-interval 越界告警：en 下是英文、无中文")
    void pushIntervalOutOfRangeIsEnglishUnderEn() {
        try {
            Messages.load(pluginWithLanguage(Messages.LOCALE_EN));
            String warnings = ConfigFixtures.warningsOf(LOGGER + "-en-out", c -> {
                c.network_pushIntervalSeconds = 500;
            });

            assertTrue(warnings.contains("network.push-interval-seconds=500"), warnings);
            assertTrue(warnings.contains("outside the valid range"),
                "英文界面下必须是英文模板；got: " + warnings);
            assertFalse(hasCjk(warnings),
                "英文界面下不得残留中文（原实现是硬编码中文字面量）；got: " + warnings);
        } finally {
            Messages.load(null);
        }
    }

    /**
     * 交叉校验那条（上调到采集周期）同样是双语的，且保留三个数。
     *
     * <p>挑这条的额外理由：它的模板最长、参数最多（原值 / tick / 秒 / 上调后），
     * i18n 迁移时最容易漏掉某个占位符——而漏掉的占位符会原样出现在服主看到的告警里。</p>
     */
    @Test
    @DisplayName("push-interval 低于采集周期的告警：两种语言都保留三个数")
    void crossCheckWarningsKeepAllNumbersInBothLanguages() {
        try {
            Messages.load(pluginWithLanguage(Messages.LOCALE_EN));
            String en = ConfigFixtures.warningsOf(LOGGER + "-en-cross", c -> {
                c.general_updateInterval = 1200;   // 60 秒
                c.network_pushIntervalSeconds = 10;
            });
            assertTrue(en.contains("network.push-interval-seconds=10"), en);
            assertTrue(en.contains("general.update_interval=1200"), en);
            assertTrue(en.contains("60"), "必须给出采集周期的秒数换算；got: " + en);
            assertFalse(hasCjk(en), "en 下不得残留中文；got: " + en);

            Messages.load(pluginWithLanguage(Messages.LOCALE_ZH));
            String zh = ConfigFixtures.warningsOf(LOGGER + "-zh-cross", c -> {
                c.general_updateInterval = 1200;
                c.network_pushIntervalSeconds = 10;
            });
            assertTrue(zh.contains("network.push-interval-seconds=10"), zh);
            assertTrue(zh.contains("general.update_interval=1200"), zh);
            assertTrue(zh.contains("已自动上调为"), "中文界面下必须是中文模板；got: " + zh);
            assertFalse(zh.contains("raised automatically to"),
                "中文界面下不得出现英文模板；got: " + zh);
        } finally {
            Messages.load(null);
        }
    }

    /** 造一个只带 logger + configManager 的裸 plugin，用于让 {@code Messages.load} 读到语言。 */
    private static cn.perfscope.PerfScope pluginWithLanguage(String locale) {
        ConfigManager cm = new ConfigManager(null);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.general_language = locale;
        cn.perfscope.support.TestObjects.setField(ConfigManager.class, cm, "config", cfg);

        cn.perfscope.PerfScope plugin =
            cn.perfscope.support.TestObjects.allocate(cn.perfscope.PerfScope.class);
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(LOGGER + "-loader");
        log.setUseParentHandlers(false);
        log.setLevel(java.util.logging.Level.OFF);
        cn.perfscope.support.TestObjects.setField(
            cn.perfscope.PerfScope.class, plugin, "logger", log);
        cn.perfscope.support.TestObjects.setField(
            cn.perfscope.PerfScope.class, plugin, "configManager", cm);
        return plugin;
    }

    /** 是否含 CJK 字符。 */
    private static boolean hasCjk(String s) {
        return s.codePoints().anyMatch(cp ->
            (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF));
    }
}
