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

import cn.perfscope.util.Messages;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨服摘要（plugin messaging）文案表：{@link SummaryPublisher} 与启动期的通道装配。
 *
 * <p><b>为什么单独一个类</b>：与 {@code CoreMessages} / {@code RuntimeMessages} 同构。
 * 这批文案此前全是 {@code plugin.getLogger()} 调用里的中文字面量，{@code general.language=en}
 * 之后控制台照样输出中文——而这些恰恰是跨服链路出问题时唯一的线索（代理面板只会说
 * "数据过期"，本机必须自己说清原因）。</p>
 *
 * <p><b>键名前缀 {@code network.*}</b>：与 {@code config.network.*} 配置键区分开——
 * 后者是"用户写错了配置"，前者是"运行期发生了什么"，两者的处置动作完全不同
 * （改配置 vs 看网络/看代理日志）。</p>
 *
 * <p><b>为什么单位与时长也走这里</b>：{@code network.log.clock-rollback} 里的时长片段由
 * {@link cn.perfscope.util.DurationFormat#elapsed} 提供（中英双表），本表只放句子骨架。
 * 片段拼接会让英文语序散架，所以时长也作为 {@code {0}} 传入。</p>
 */
public final class NetworkMessages {

    static {
        Map<String, String> en = new LinkedHashMap<>();
        Map<String, String> zh = new LinkedHashMap<>();

        // ==================== 通道登记 / 启停（启动期与 reload） ====================
        put(en, zh, "network.log.register-failed",
            "[PerfScope] Failed to register the cross-server channel; network features are unavailable: {0}",
            "[PerfScope] 登记跨服通道失败，network 功能不可用: {0}");
        // 启用回执：{0}=通道名，{1}=上报间隔秒数。
        // 末尾那句"需有玩家在线"是这条日志的重点——plugin messaging 的载体是玩家连接，
        // 空服期间代理侧收不到任何数据，而本机看起来一切正常。
        put(en, zh, "network.log.enabled",
            "[PerfScope] Cross-server summary enabled: channel {0}, interval {1}s. "
                + "(plugin messaging can only relay while a player is online, so the proxy sees nothing on an empty server)",
            "[PerfScope] 跨服摘要已启用: 通道 {0}，间隔 {1} 秒。"
                + "（plugin messaging 需有玩家在线才能转发，空服期间代理侧收不到）");
        // 这条必须在通道登记失败之后打，且要说清"配置仍是 true"——否则服主会以为改开关就好
        put(en, zh, "network.log.not-enabled",
            "[PerfScope] Cross-server summary NOT enabled: channel registration failed "
                + "(see the warning above; /perfscope status shows it too). network.enabled is still true, "
                + "but no data will ever be sent",
            "[PerfScope] 跨服摘要未启用：通道登记失败"
                + "（原因见上一条 warning，/perfscope status 也会显示）。network.enabled 仍是 true，"
                + "但跨服摘要不会发出任何数据");
        put(en, zh, "network.log.start-failed",
            "[PerfScope] Failed to start the cross-server summary: {0}",
            "[PerfScope] 跨服摘要启动失败: {0}");

        // ==================== 发送期 ====================
        // {0}=回拨时长（DurationFormat.elapsed），其余整句由本模板承载。
        // "常见原因"那段不是凑字数：它是服主判断"要不要紧"的唯一依据——
        // 快照恢复/容器迁移是运维日常，而 NTP 大步校正常常意味着有人在动时间服务。
        put(en, zh, "network.log.clock-rollback",
            "[PerfScope] System clock moved backwards by {0} (the last report time is later than now). "
                + "The send throttle has been reset and sending continues. Common causes: a VM restored from a "
                + "snapshot, container migration, a large NTP step, or the host resuming from suspend. Without this "
                + "reset the cross-server summary would stay silent until the wall clock catches up, while the proxy "
                + "only reports \"stale data\".",
            "[PerfScope] 检测到系统时钟回拨 {0}（上次上报时刻晚于当前时间）。"
                + "已重置上报节流基准并继续发送。常见原因：虚拟机从快照恢复、容器迁移、"
                + "NTP 大步校正、宿主机挂起后恢复。若不重置，跨服摘要会一直停发到墙钟"
                + "追回原时间点为止，而代理侧只会显示「数据过期」。");
        // {0}=连续失败次数，{1}=限频秒数，{2}=异常类型: 消息
        put(en, zh, "network.log.send-failed",
            "[PerfScope] Cross-server summary send failed (consecutive failure #{0}; at most one such log every {1}s): {2}",
            "[PerfScope] 跨服摘要发送失败（连续第 {0} 次；同类日志每 {1} 秒最多一条）: {2}");
        // {0}=自动派生的 server-id
        put(en, zh, "network.log.server-id-derived",
            "[PerfScope] network.server-id is not configured; derived \"{0}\" automatically. "
                + "In a multi-backend setup every server must have an explicit, distinct value, "
                + "otherwise the proxy merges their data into one series.",
            "[PerfScope] network.server-id 未配置，已自动派生为 \"{0}\"。"
                + "多后端环境下每台必须显式配一个不同的值，否则代理侧会把它们的数据混成一条。");

        Messages.registerModule(en, zh);
    }

    /** 触发本类静态初始化（由 {@code Messages.onFirstLoad} 登记）。 */
    public static void init() {
        // 空方法体：调用它即触发 static 块
    }

    private static void put(Map<String, String> en, Map<String, String> zh,
                            String key, String enValue, String zhValue) {
        en.put(key, enValue);
        zh.put(key, zhValue);
    }

    private NetworkMessages() {}
}
