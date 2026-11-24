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

import cn.perfscope.PerfScope;
import cn.perfscope.network.SummaryPublisher;
import cn.perfscope.support.AlertFixtures;
import cn.perfscope.support.TestObjects;
import cn.perfscope.util.Messages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server-id} 的**加载期**校验：配错时，{@code load()} 那一屏就要在控制台告警。
 *
 * <h2>这里守的是什么</h2>
 *
 * <p>{@code server-id} 有两条校验时机：<b>发送期</b>（{@code SummaryPublisher.publish}，
 * 不合格就一条都不发）与<b>加载期</b>（{@code ConfigManager} 加载配置时就告警）。
 * 发送期那半有测试钉住；加载期那半此前<b>一行都没有</b>——
 * 把 {@code checkCrossServerId} 那两行删掉，{@code cn.perfscope.config.*Test} 全绿。</p>
 *
 * <p>丢掉的不是"能不能上报"（发送期仍然挡住了），丢的是<b>发现时机</b>：
 * 服主改错 {@code server-id} 之后要等到第一个采集周期（默认 20 tick）才在日志里看到，
 * 而那时他大概已经切到别的窗口去了。</p>
 *
 * <h2>为什么必须跑真实的 {@code load()}</h2>
 *
 * <p>第一版是「反射调私有 {@code validateThresholds}」，因为那两行当时在那个方法里。
 * 但那条路固定的是<b>代码写在哪个方法里</b>，不是<b>加载期会不会告警</b>：把那两行挪到
 * {@code load()} 里另起一个 {@code validateServerIds()}（用户可见行为完全不变，甚至更好——
 * 跨服身份校验从"阈值校验"里分出去），第一版会全红；而真有回归让加载期不再告警时，
 * 只要方法签名没变，它又会全绿。</p>
 *
 * <p>所以这里走 YAML 文本 → {@code ConfigData} 的全程：写一份真配置到 {@code @TempDir}，
 * 调真实的 {@code cm.load()}，再从 {@code Logger} 上读它到底打了什么。
 * 这条路径只认「加载期会不会告警」这个用户可见事实。</p>
 *
 * <p>它也确实抓到了第一版抓不到的东西：最初把 {@code server-id} 拼在整份 YAML 末尾，
 * 而末尾是 {@code prometheus:} 段，于是越界值落进了 {@code prometheus.server-id}，
 * 告警点名的也是 prometheus。反射调私有方法时压根不看 YAML，这个错永远不会暴露。</p>
 *
 * <p>同样的做法在 {@code AlertOptionsSingleSourceTest#managerFrom} 已有先例，
 * 那里的注释写得很准：「不能只 setField(config)，那样无法区分『配置层读对了』
 * 与『配置层根本没被调用』」。</p>
 */
class CrossServerIdLoadTimeValidationTest {

    @BeforeAll
    static void initMessages() {
        // 只用配置层的消息表：本类测的是 ConfigManager 的加载期告警，不涉及告警/诊断/network 表。
        // 不用 AlertFixtures.initMessages()——它反射寻找 cn.perfscope.alert.AlertMessages，
        // 那个类已经并进别处了，于是它抛「message layer init failed」。
        ConfigMessages.init();
        Messages.load(null);
    }

    /**
     * 把 {@code Messages} 的全局快照恢复成「默认语言 + 无用户覆盖」。
     *
     * <p><b>为什么必须有这一步</b>：{@code Messages.load(null)} 发布的是
     * {@code new Snapshot(DEFAULT_LOCALE, …, Map.of())}，即语言强制回 en、用户覆盖表清空。
     * 任何一个后续测试类若依赖 {@code load(plugin)} 载入的 {@code messages.yml} 覆盖，
     * 就会发现覆盖没了。当前不出事靠的是"默认值恰好等于本类的残留"，那是运气不是设计。</p>
     *
     * <p>实测：本类加上 `@TempDir` 真实 load() 之后，全量跑时
     * {@code MessagesLoadNullTest} / {@code ZhPunctuationConventionTest} /
     * {@code ZhTerminologyConsistencyTest} 各红一条——正是全局语言状态被上一类改掉的样子。</p>
     */
    @AfterAll
    static void restoreGlobalMessageState() {
        Messages.load(null);
    }

    // ====================================================================================
    // 夹具
    // ====================================================================================

    /** 一次真实加载的结果：拿到的 manager 与它打出来的 WARNING / SEVERE。 */
    private record Loaded(ConfigManager cm, List<String> warnings) { }

    /**
     * 一份最小配置。{@code network.server-id} 与 {@code prometheus.server-id} 都给显式值。
     *
     * <p>两个键必须各自出现在自己的段下——{@code server-id:} 是一个 YAML 键，出现在哪个段里
     * 决定它绑定到哪个配置项。把覆盖项一律拼到文件末尾（那里是 {@code prometheus:} 段）
     * 会让"测 network"的用例实际在测 prometheus。</p>
     */
    private static String yaml(String networkServerId, String prometheusServerId) {
        return String.join("\n",
            "config-version: 3",
            "general:",
            "  language: en",
            "  update-interval: 20",
            "network:",
            "  enabled: false",
            "  push-interval-seconds: 10",
            "  server-id: \"" + networkServerId + "\"",
            "prometheus:",
            "  enabled: false",
            "  server-id: \"" + prometheusServerId + "\"",
            "");
    }

    private static Logger quietLogger(String name) {
        Logger l = Logger.getLogger(name);
        l.setUseParentHandlers(false);
        l.setLevel(Level.ALL);
        return l;
    }

    /**
     * 把 {@code text} 写进 {@code dir/config.yml}，用真实的 {@code ConfigManager.load()} 解析。
     *
     * <p>三个字段必须补：{@code dataFolder}（{@code load()} 读它拼路径）、{@code classLoader}
     * （{@code saveDefaultConfig()} 与未知键检测要从 jar 内读默认 {@code config.yml}；
     * 本类 classloader 即可，{@code src/main/resources/config.yml} 已随编译进 {@code target/classes}）、
     * {@code configFile}（{@code allocateInstance} 不跑初始化器，它是 {@code null}）。</p>
     */
    private static Loaded load(Path dir, String loggerName, String text) throws Exception {
        Files.write(dir.resolve("config.yml"), text.getBytes(StandardCharsets.UTF_8));

        Logger logger = quietLogger(loggerName);
        List<String> captured = new ArrayList<>();
        Handler h = new Handler() {
            @Override public void publish(LogRecord r) {
                if ((r.getLevel() == Level.WARNING || r.getLevel() == Level.SEVERE)
                        && r.getMessage() != null) {
                    captured.add(r.getMessage());
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(h);
        try {
            PerfScope p = AlertFixtures.barePlugin(logger);
            TestObjects.setField(PerfScope.class, p, "dataFolder", dir.toFile());
            TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "classLoader",
                CrossServerIdLoadTimeValidationTest.class.getClassLoader());
            TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "configFile",
                dir.resolve("config.yml").toFile());

            ConfigManager cm = new ConfigManager(p);
            TestObjects.setField(ConfigManager.class, cm, "plugin", p);
            TestObjects.setField(PerfScope.class, p, "configManager", cm);
            cm.load();
            return new Loaded(cm, captured);
        } finally {
            logger.removeHandler(h);
        }
    }

    /** 只挑出与 {@code server-id} 有关的告警（baseline 配置会顺带产出几条无关告警）。 */
    private static List<String> serverIdWarnings(List<String> warnings) {
        return warnings.stream().filter(w -> w.contains("server-id")).toList();
    }

    // ====================================================================================
    // 1. 加载期真的会告警
    // ====================================================================================

    /**
     * 超长 {@code network.server-id}：{@code load()} 就得告警，且要说清「多长」与「上限多少」。
     *
     * <p>长度上限是协议常量 {@code MAX_SERVER_ID_LENGTH}。超长的 id 会被代理整包丢弃，
     * 那台后端在面板上<b>永久隐身</b>，而代理日志只会说"无法解析的跨服摘要"——
     * 照那个方向查会彻底查错。</p>
     */
    @Test
    @DisplayName("加载期：超长 network.server-id 会告警，且点出实际长度与上限")
    void overlongNetworkServerIdIsWarnedDuringLoad(@TempDir Path dir) throws Exception {
        String over = "a".repeat(SummaryPublisher.MAX_SERVER_ID_LENGTH + 1);
        Loaded r = load(dir, "pscope-csid-long", yaml(over, ""));

        List<String> idWarnings = serverIdWarnings(r.warnings());
        assertEquals(1, idWarnings.size(),
            "加载期应当恰好告警一次（同一键重复告警是真实 UX 退化），实际：" + idWarnings);

        String w = idWarnings.get(0);
        assertTrue(w.contains("network.server-id"), "必须点名是哪一个键配错了，实际：" + w);
        assertTrue(w.contains(String.valueOf(over.length())),
            "必须给出实际长度，让服主知道超了多少，实际：" + w);
        assertTrue(w.contains(String.valueOf(SummaryPublisher.MAX_SERVER_ID_LENGTH)),
            "必须给出上限，否则服主不知道该改到多短，实际：" + w);
    }

    /**
     * 保留字 {@code unknown}：加载期告警，且文案说的必须是「保留字」而不是「太长」。
     *
     * <p>只断言"提到键名"不够：把 TOO_LONG 与 RESERVED 两个分支的消息键对调，
     * 那种断言照样绿——而服主会看到「unknown 有 7 个字符，超过上限 64」这种荒谬提示。
     * 所以这里断言的是<b>结论本身</b>：告警里出现了 {@code unknown} 这个保留字，
     * 且<b>不</b>出现「超过上限」那套长度的说法。</p>
     */
    @Test
    @DisplayName("加载期：保留字 unknown 告警说的是保留字，不是长度")
    void reservedWordIsWarnedAsReservedNotAsTooLong(@TempDir Path dir) throws Exception {
        Loaded r = load(dir, "pscope-csid-reserved", yaml(SummaryPublisher.RESERVED_SERVER_ID, ""));

        List<String> idWarnings = serverIdWarnings(r.warnings());
        assertEquals(1, idWarnings.size(), "加载期应当恰好告警一次，实际：" + idWarnings);

        String w = idWarnings.get(0);
        assertTrue(w.contains("network.server-id"), "必须点名该键，实际：" + w);
        assertTrue(w.contains(SummaryPublisher.RESERVED_SERVER_ID),
            "文案必须指出问题出在保留字本身，实际：" + w);
        assertFalse(w.contains("characters long"),
            "保留字不是长度问题，文案不能套用「太长」那套说法，实际：" + w);
    }

    /**
     * {@code prometheus.server-id} 单独不合格时，<b>点名的是它</b>。
     *
     * <p>这是本类最要紧的一条：两个键走同一条校验路径。如果实现把键名写死成
     * {@code network.server-id}，服主会去改一个他根本没生效过的配置项——因为
     * {@code prometheus.server-id} 只在他把 {@code network.server-id} 留空时才真正被用到。</p>
     */
    @Test
    @DisplayName("加载期：prometheus.server-id 不合格时点名的是它")
    void prometheusServerIdIsNamedSeparately(@TempDir Path dir) throws Exception {
        String over = "b".repeat(SummaryPublisher.MAX_SERVER_ID_LENGTH + 1);
        Loaded r = load(dir, "pscope-csid-prom", yaml("", over));

        List<String> idWarnings = serverIdWarnings(r.warnings());
        assertTrue(idWarnings.stream().anyMatch(w -> w.contains("prometheus.server-id")),
            "必须点名 prometheus.server-id，实际：" + idWarnings);
        assertFalse(idWarnings.stream().anyMatch(w -> w.contains("\"network.server-id\"")),
            "network.server-id 本身合法（留空），不该被点名——那会把服主引向错误的配置项。"
                + "实际：" + idWarnings);
    }

    /**
     * 含控制字符的 id：加载期告警，且告警里能看到<b>精确码点</b>。
     *
     * <p>控制符在日志里不可见，直接拼进消息会让告警看起来像少了一段。
     * 实现用 {@code describeUnsafeChars} 渲染成 {@code U+0007} 这种形式。</p>
     */
    @Test
    @DisplayName("加载期：含控制符的 id 会告警并给出码点")
    void unsafeCharsAreReportedWithCodePoints(@TempDir Path dir) throws Exception {
        // YAML 双引号标量里**不能**放裸控制符：`survival` + U+0007 会让解析器
        // 走到 YamlConfiguration 的错误路径（实测 NPE 在 Bukkit.getLogger）。
        // 用 YAML 自己的转义写法 `\u0007`——解析后仍是那个控制符，源文件保持可读。
        Loaded r = load(dir, "pscope-csid-unsafe", yaml("survival\\u0007", ""));

        List<String> idWarnings = serverIdWarnings(r.warnings());
        assertFalse(idWarnings.isEmpty(), "含控制符必须在加载期告警，实际：" + r.warnings());
        assertTrue(idWarnings.stream().anyMatch(w -> w.contains("U+0007")),
            "控制符不可见，告警必须给出精确码点 U+0007，实际：" + idWarnings);
    }

    // ====================================================================================
    // 2. 反向控制：合法值不该产生噪音
    // ====================================================================================

    /**
     * 合法值 + 留空，都不该产生 server-id 告警。
     *
     * <p>留空是<b>合法</b>的（表示"回退到 prometheus.server-id，再回退到端口派生"），
     * 不是错误。把留空也告警会让每个默认配置的服在启动时看到一条假故障。</p>
     */
    @Test
    @DisplayName("加载期：合法值与留空都不告警")
    void validAndBlankValuesStaySilent(@TempDir Path dir) throws Exception {
        Loaded r = load(dir, "pscope-csid-ok", yaml("survival-1", ""));

        List<String> idWarnings = serverIdWarnings(r.warnings());
        assertTrue(idWarnings.isEmpty(),
            "合法名字与留空都不该产生 server-id 告警，实际：" + idWarnings
                + "\n（完整日志：" + r.warnings() + "）");
    }

    /**
     * {@code Unknown}（首字母大写）是合法名字——保留字判据大小写敏感。
     *
     * <p>一个只差大小写的值被拒，服主会以为插件对他的命名有意见。</p>
     */
    @Test
    @DisplayName("加载期：Unknown（大写 U）不是保留字")
    void reservedWordIsCaseSensitive(@TempDir Path dir) throws Exception {
        Loaded r = load(dir, "pscope-csid-case", yaml("Unknown", ""));

        List<String> idWarnings = serverIdWarnings(r.warnings());
        assertTrue(idWarnings.isEmpty(),
            "只有全小写 unknown 是保留字，Unknown 合法，实际：" + idWarnings);
    }
}
