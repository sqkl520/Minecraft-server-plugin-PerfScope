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
package cn.perfscope.network;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.TestObjects;
import cn.perfscope.util.Messages;
import org.bukkit.plugin.messaging.PluginMessageRecipient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2：{@link SummaryPublisher} 的控制台日志必须跟随 {@code general.language}。
 *
 * <h2>这条 bug 的形状</h2>
 *
 * <p>这批日志此前是 {@code plugin.getLogger()} 调用里的中文字面量。{@code general.language=en}
 * 之后控制台照样输出中文——而这些恰恰是跨服链路出问题时唯一的线索（代理面板只会说
 * "数据过期"，本机必须自己说清原因）。其中 {@code formatDuration} 还自带一份中文单位表，
 * 于是英文日志里会整句英文夹着"1 小时"。</p>
 *
 * <h2>为什么不能只断言"英文文案出现了"</h2>
 *
 * <p>那样写会锁死一种实现：把 {@code Messages.get} 换成硬编码英文也能过。判据必须是
 * <b>同一份代码在两种语言下产出两种文案</b>——这条只有真正走消息表才能满足。</p>
 *
 * <p>用真实的 {@code publish()} 触发（载体接缝，见 {@link PublisherFailureThrottleTest}），
 * 不断言源码文本：文案来自 {@code Messages}，所以"语言 → 输出"这条链是真跑过的。</p>
 */
class NetworkLogLocalisationTest {

    private static final String LOGGER_NAME = "pscope-network-log-i18n";

    /** 收集到的全部日志（任意级别）。 */
    private final List<String> logs = new ArrayList<>();

    private Logger logger;
    private Handler handler;

    @BeforeAll
    static void initMessages() {
        // NetworkMessages 表必须先注册：否则 Messages.get("network.log.*") 只会返回键名，
        // 而本类断言的是渲染后的文案（键名同样是"英文"，会假绿）。
        cn.perfscope.support.AlertFixtures.initMessages();
        NetworkMessages.init();
    }

    @BeforeEach
    void installCapturingLogger() {
        logs.clear();
        logger = Logger.getLogger(LOGGER_NAME);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getMessage() != null) {
                    logs.add(r.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
    }

    @AfterEach
    void removeHandler() {
        logger.removeHandler(handler);
        Messages.load(null);   // 还原默认语言，避免污染同 JVM 的后续测试
    }

    // ==================== 夹具 ====================

    /** 发送必抛的载体——触发"发送失败"那条日志。 */
    private PluginMessageRecipient failingCarrier() {
        return (PluginMessageRecipient) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{PluginMessageRecipient.class},
            (proxy, method, args) -> {
                if ("sendPluginMessage".equals(method.getName())) {
                    throw new IllegalStateException("channel closed");
                }
                if ("getListeningPluginChannels".equals(method.getName())) {
                    return Collections.emptySet();
                }
                return null;
            });
    }

    private SummaryPublisher publisher(PluginMessageRecipient carrier) {
        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, plugin, "logger", logger);

        ConfigManager cm = new ConfigManager(plugin);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.network_enabled = true;
        cfg.network_serverId = "i18n-test";
        cfg.network_pushIntervalSeconds = 10;
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);

        return new SummaryPublisher(plugin, () -> carrier);
    }

    private static MetricsSnapshot snapshot() {
        MetricsSnapshot snap = new MetricsSnapshot();
        snap.tps1m = 20.0;
        snap.tps5m = 20.0;
        snap.tps15m = 20.0;
        return snap;
    }

    /** 走真实入口切语言（{@code Messages.load(null)} 只会回默认语言）。 */
    private static void switchLocale(String locale) {
        ConfigManager cm = new ConfigManager(null);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.general_language = locale;
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);

        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        Logger log = Logger.getLogger(LOGGER_NAME + "-switch-" + locale);
        log.setUseParentHandlers(false);
        log.setLevel(Level.OFF);
        TestObjects.setField(PerfScope.class, plugin, "logger", log);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);

        Messages.load(plugin);
    }

    private String joined() {
        return String.join("\n", logs);
    }

    // ==================== 发送失败日志 ====================

    /**
     * 发送失败日志：英文界面下必须是英文，且不得残留中文。
     *
     * <p>"不得残留中文"这条是本类真正的判据：把 {@code Messages.get} 换回中文字面量时，
     * 英文断言会红；而把它换成硬编码英文时，中文那条用例会红。两条合起来才锁住"走消息表"。</p>
     */
    @Test
    @DisplayName("发送失败日志：en 下全英文、无中文")
    void sendFailureLogIsEnglishUnderEn() {
        try {
            switchLocale(Messages.LOCALE_EN);
            publisher(failingCarrier()).publish(snapshot(), 1_796_000_000_000L);

            String out = joined();
            assertTrue(out.contains("Cross-server summary send failed"),
                "英文界面下必须是英文模板；got: " + out);
            assertFalse(hasCjk(out),
                "英文界面下不得出现中文（原实现是硬编码中文字面量）；got: " + out);
        } finally {
            switchLocale(Messages.LOCALE_EN);
        }
    }

    /** 同一份代码在中文界面下必须输出中文——否则只是把硬编码中文换成了硬编码英文。 */
    @Test
    @DisplayName("发送失败日志：zh 下是中文")
    void sendFailureLogIsChineseUnderZh() {
        try {
            switchLocale(Messages.LOCALE_ZH);
            publisher(failingCarrier()).publish(snapshot(), 1_796_000_000_000L);

            String out = joined();
            assertTrue(out.contains("跨服摘要发送失败"),
                "中文界面下必须是中文模板；got: " + out);
            assertFalse(out.contains("Cross-server summary send failed"),
                "中文界面下不得出现英文模板；got: " + out);
        } finally {
            switchLocale(Messages.LOCALE_EN);
        }
    }

    // ==================== 回拨日志的时长片段（任务 2 + 任务 3 交汇处） ====================

    /**
     * 时钟回拨日志在英文界面下的时长片段必须是英文单位。
     *
     * <p>这是任务 2 的真正验收点：原 {@code formatDuration} 自带中文单位表，所以英文日志里
     * 会出现 {@code moved backwards by 1 小时}——一句话两种语言。断言里刻意检查
     * "1h" 而<b>不</b>是"没有中文"，因为后者在整句换回中文时也会通过。</p>
     */
    @Test
    @DisplayName("回拨日志：en 下时长片段是 1h，不是 '1 小时'")
    void clockRollbackDurationIsEnglishUnderEn() {
        try {
            switchLocale(Messages.LOCALE_EN);
            SummaryPublisher p = publisher(failingCarrier());
            // lastSentAtMs 设成一个未来时刻 → 下一次 publish 命中回拨分支
            TestObjects.setField(SummaryPublisher.class, p, "lastSentSemantic", "20.0|...");
            TestObjects.setField(SummaryPublisher.class, p, "lastSentAtMs", 1_796_000_000_000L);

            // 回拨 1 小时 1 分 1 秒：窗口 2 只取"天/时/分/秒"里前两个非零 → "1h 1m"
            p.publish(snapshot(), 1_796_000_000_000L - 3_661_000L);

            String out = joined();
            assertTrue(out.contains("clock moved backwards by 1h 1m"),
                "时长片段必须走 DurationFormat（英文单位），而不是自带的中文表；got: " + out);
            assertFalse(out.contains("小时"),
                "不得出现中文单位——那正是'同一句里两种语言'的混排；got: " + out);
        } finally {
            switchLocale(Messages.LOCALE_EN);
        }
    }

    /** 中文界面下同一段必须是中文单位，证明片段确实跟着语言走。 */
    @Test
    @DisplayName("回拨日志：zh 下时长片段是中文单位")
    void clockRollbackDurationIsChineseUnderZh() {
        try {
            switchLocale(Messages.LOCALE_ZH);
            SummaryPublisher p = publisher(failingCarrier());
            TestObjects.setField(SummaryPublisher.class, p, "lastSentSemantic", "20.0|...");
            TestObjects.setField(SummaryPublisher.class, p, "lastSentAtMs", 1_796_000_000_000L);

            p.publish(snapshot(), 1_796_000_000_000L - 3_661_000L);

            String out = joined();
            assertTrue(out.contains("检测到系统时钟回拨 1 时 1 分"),
                "中文界面下时长必须是中文单位且带空格；got: " + out);
        } finally {
            switchLocale(Messages.LOCALE_EN);
        }
    }

    // ==================== server-id 派生日志 ====================

    /**
     * server-id 未配置时那条派生告警同样要跟随语言。
     *
     * <p>挑这条的理由：它在 {@code resolveServerId} 里、被限频保护，是最容易在 i18n 迁移时
     * 被漏掉的一处（它不在 {@code publish} 主路径上，而是更早的"身份先过闸"分支）。</p>
     */
    @Test
    @DisplayName("server-id 派生告警：en 全英文、zh 中文")
    void serverIdDerivedLogFollowsLanguage() {
        try {
            switchLocale(Messages.LOCALE_EN);
            publisherWithBlankServerId().publish(snapshot(), 1_796_000_000_000L);
            String en = joined();
            assertTrue(en.contains("network.server-id is not configured"),
                "英文界面下必须是英文模板；got: " + en);
            assertFalse(hasCjk(en), "英文界面下不得出现中文；got: " + en);

            logs.clear();
            switchLocale(Messages.LOCALE_ZH);
            publisherWithBlankServerId().publish(snapshot(), 1_796_000_000_000L);
            String zh = joined();
            assertTrue(zh.contains("network.server-id 未配置"),
                "中文界面下必须是中文模板；got: " + zh);
        } finally {
            switchLocale(Messages.LOCALE_EN);
        }
    }

    /** server-id 留空 + 显式端口 → 走"派生 port-N"那条分支并触发告警。 */
    private SummaryPublisher publisherWithBlankServerId() {
        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, plugin, "logger", logger);

        ConfigManager cm = new ConfigManager(plugin);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.network_enabled = true;
        cfg.network_serverId = "";          // 留空 → 派生
        cfg.network_pushIntervalSeconds = 10;
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);

        // 派生要读 getServer().getPort()；测试 JVM 里没有服务端，异常会被内部 catch 吞掉
        // 并降级成 "perfscope"——告警仍会打出来，这正是本用例要的。
        return new SummaryPublisher(plugin, () -> (PluginMessageRecipient) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{PluginMessageRecipient.class},
            (proxy, method, args) -> {
                if ("getListeningPluginChannels".equals(method.getName())) return Collections.emptySet();
                if ("sendPluginMessage".equals(method.getName())) {
                    throw new IllegalStateException("channel closed");
                }
                return null;
            }));
    }

    /** 是否含 CJK 字符。用码点判断而不是正则字符类，避免不同 JVM 的 Unicode 属性差异。 */
    private static boolean hasCjk(String s) {
        return s.codePoints().anyMatch(cp ->
            (cp >= 0x4E00 && cp <= 0x9FFF)      // CJK 统一表意文字
                || (cp >= 0x3400 && cp <= 0x4DBF)   // 扩展 A
                || (cp >= 0xF900 && cp <= 0xFAFF)); // 兼容表意文字
    }
}
