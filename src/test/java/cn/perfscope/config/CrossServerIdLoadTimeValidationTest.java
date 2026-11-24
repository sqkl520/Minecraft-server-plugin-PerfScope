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

import cn.perfscope.network.SummaryPublisher;
import cn.perfscope.support.AlertFixtures;
import cn.perfscope.support.ConfigFixtures;
import cn.perfscope.util.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server-id} 的**加载期**校验必须真的发生（P1-C 的另一半）。
 *
 * <h2>这里守的是什么</h2>
 *
 * <p>{@code server-id} 有两条校验时机：<b>发送期</b>（{@code SummaryPublisher.publish}
 * 里那条，不合格就一条都不发）与<b>加载期</b>（{@code ConfigManager.validateThresholds}
 * 里的两次 {@code checkCrossServerId}，不合格就当场告警）。</p>
 *
 * <p>发送期那半有测试钉住。加载期那半此前<b>一行都没有</b>——把
 * {@code ConfigManager} 里那两行删掉，{@code cn.perfscope.config.*Test} 全绿。</p>
 *
 * <p>丢掉的不是"能不能上报"（发送期仍然挡住了），丢的是<b>发现时机</b>：
 * 服主改错 {@code server-id} 之后要等到第一个采集周期（默认 20 tick）才在日志里看到，
 * 而那时他大概已经切到别的窗口去了。加载期告警是"启动那一屏就告诉他配错了"。</p>
 *
 * <h2>为什么直接调 validateThresholds 而不是 load()</h2>
 *
 * <p>{@code load()} 需要 {@code dataFolder}、资源流与 Bukkit 的 YAML API。
 * {@code validateThresholds} 是 {@code load()} 里负责校验的那一段，两个
 * {@code checkCrossServerId} 就在它内部——测它等于测真实调用路径，只是跳过读盘。</p>
 *
 * <h2>判据为什么必须点名「哪一个键」</h2>
 *
 * <p>{@code network.server-id} 优先、{@code prometheus.server-id} 其次。只说"server-id 无效"
 * 时，服主会去改 {@code network.server-id}，而他真正写错的可能正是 {@code prometheus.server-id}。
 * 所以下面每条用例都断言告警里出现了那个键名。</p>
 */
class CrossServerIdLoadTimeValidationTest {

    private static final String LOGGER = "pscope-crossserverid-loadtime";

    @BeforeAll
    static void initMessages() throws Exception {
        AlertFixtures.initMessages();
        ConfigMessages.init();
        Messages.load(null);
    }

    // ====================================================================================
    // 1. 两类不合格输入都必须在加载期告警
    // ====================================================================================

    /**
     * 超长 {@code network.server-id}：加载期就要点名这个键并给出实际长度与上限。
     *
     * <p>长度上限是 <b>协议常量</b> {@code SummaryPublisher.MAX_SERVER_ID_LENGTH}，
     * 与代理侧 {@code SummaryPacket} 同一数值。超长的 id 会被代理整包丢弃，
     * 那台后端在面板上<b>永久隐身</b>，而代理日志只会说"无法解析的跨服摘要"——
     * 照那个方向查会彻底查错。所以加载期告警必须把"太长"这个真实原因说出来。</p>
     */
    @Test
    @DisplayName("超长 network.server-id：加载期告警点名该键")
    void overlongNetworkServerIdIsWarnedAtLoadTime() {
        String over = "a".repeat(SummaryPublisher.MAX_SERVER_ID_LENGTH + 1);
        String warnings = ConfigFixtures.warningsOf(LOGGER + "-long", (cfg) -> cfg.network_serverId = over);

        assertTrue(warnings.contains("network.server-id"),
            "加载期告警必须点名是哪一个键配错了，实际日志：" + warnings);
        assertTrue(warnings.contains(over) || warnings.contains(String.valueOf(over.length())),
            "加载期告警应给出实际长度，让服主知道超了多少，实际日志：" + warnings);
    }

    /**
     * 保留字 {@code unknown}：同样在加载期点名。
     *
     * <p>代理侧用这个值表示"这个包没有身份"，会整包丢弃。大小写敏感——
     * {@code Unknown} 是合法名字，只有全小写是保留字。</p>
     */
    @Test
    @DisplayName("保留字 network.server-id=unknown：加载期告警")
    void reservedNetworkServerIdIsWarnedAtLoadTime() {
        String warnings = ConfigFixtures.warningsOf(LOGGER + "-reserved",
            (cfg) -> cfg.network_serverId = SummaryPublisher.RESERVED_SERVER_ID);

        assertTrue(warnings.contains("network.server-id"),
            "保留字也必须在加载期被点名，实际日志：" + warnings);
    }

    /**
     * {@code prometheus.server-id} 单独不合格时，<b>点名的是它</b>，不是 network 那个。
     *
     * <p>这是本类最要紧的一条：两个键都会走同一个 {@code checkCrossServerId}，
     * 如果实现把键名写死成 {@code network.server-id}，服主会去改一个他根本没生效过的配置项。</p>
     */
    @Test
    @DisplayName("prometheus.server-id 不合格：点名的是这个键，不是 network 那个")
    void prometheusServerIdIsNamedSeparately() {
        String warnings = ConfigFixtures.warningsOf(LOGGER + "-prom",
            (cfg) -> cfg.prometheus_serverId = "a".repeat(SummaryPublisher.MAX_SERVER_ID_LENGTH + 1));

        assertTrue(warnings.contains("prometheus.server-id"),
            "必须点名 prometheus.server-id，实际日志：" + warnings);
        assertFalse(warnings.contains("network.server-id"),
            "network.server-id 本身是合法的，不该被一起点名——那会把服主引向错误的配置项。实际日志：" + warnings);
    }

    /**
     * 含控制字符的 id 也要在加载期拦住，且告警里能看到<b>码点</b>。
     *
     * <p>控制符在日志里不可见，直接把它拼进消息会让告警看起来像少了一段。
     * 所以实现用 {@code describeUnsafeChars} 渲染成 {@code U+0007} 这种形式。</p>
     */
    @Test
    @DisplayName("含控制符的 server-id：加载期告警并给出码点")
    void unsafeCharsAreReportedWithCodePoints() {
        String warnings = ConfigFixtures.warningsOf(LOGGER + "-unsafe",
            (cfg) -> cfg.network_serverId = "survival\u0007");

        assertTrue(warnings.contains("network.server-id"),
            "含控制符也必须在加载期被点名，实际日志：" + warnings);
        assertTrue(warnings.contains("U+") || warnings.contains("0007"),
            "控制符不可见，告警必须给出码点，实际日志：" + warnings);
    }

    // ====================================================================================
    // 2. 反向控制：合法值不该产生噪音
    // ====================================================================================

    /**
     * 合法值 + 留空，都不该产生任何告警。
     *
     * <p>留空是<b>合法</b>的（表示"回退到 prometheus.server-id，再回退到端口派生"），
     * 不是错误。把留空也告警会让每个默认配置的服在启动时看到一条假故障。</p>
     */
    @Test
    @DisplayName("合法值与留空都不告警（留空是合法的回退，不是错误）")
    void validAndBlankValuesStaySilent() {
        String warnings = ConfigFixtures.warningsOf(LOGGER + "-ok", (cfg) -> {
            cfg.network_serverId = "survival-1";
            cfg.prometheus_serverId = "";
        });

        // 只筛 server-id 相关的行：夹具的 baseline 配置本身会触发几条无关告警
        // （push-interval=0 之类），那些不属于本类的被测范围。
        String idWarnings = serverIdLines(warnings);
        assertTrue(idWarnings.isEmpty(),
            "合法名字与留空都不该产生 server-id 告警，实际：" + idWarnings
                + "\n（完整日志：" + warnings + "）");
    }

    /**
     * {@code Unknown}（首字母大写）是合法名字——保留字判据大小写敏感。
     *
     * <p>这条钉的是"别把大小写不敏感的实现放进来"：一个只差大小写的值被拒，
     * 服主会以为插件对他的命名有意见。</p>
     */
    @Test
    @DisplayName("Unknown（大写 U）不是保留字，不该告警")
    void reservedWordIsCaseSensitive() {
        String warnings = ConfigFixtures.warningsOf(LOGGER + "-case",
            (cfg) -> cfg.network_serverId = "Unknown");

        String idWarnings = serverIdLines(warnings);
        assertTrue(idWarnings.isEmpty(),
            "只有全小写 unknown 是保留字，Unknown 合法，实际：" + idWarnings
                + "\n（完整日志：" + warnings + "）");
    }

    /** 从完整日志里挑出与 server-id 有关的行（其余是夹具 baseline 触发的无关告警）。 */
    private static String serverIdLines(String warnings) {
        StringBuilder sb = new StringBuilder();
        for (String line : warnings.split("\n")) {
            if (line.contains("server-id")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
