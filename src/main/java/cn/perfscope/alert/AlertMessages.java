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
package cn.perfscope.alert;

import cn.perfscope.util.Messages;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警层文案（9D Phase 3）：告警正文的"信封"——级别、前缀、详情尾巴、下一步、阈值说明、每日摘要。
 *
 * <p><b>与 {@link AlertCatalog} 的分工</b>：{@code AlertCatalog} 负责"告警是什么"（
 * {@code alert.<metric>.<level>}，Phase 2 已完成）；本类负责"告警外面那一圈字"：</p>
 * <ul>
 *   <li><b>前缀</b>：{@code alert.prefix.*} —— 保留方括号结构，只让括号里的词随语言变。
 *       {@code [告警][严重][motd]} 在英文下是 {@code [Alert][Critical][motd]}。
 *       方括号本身不能省：控制台日志按它 grep，harness 也按它断言；
 *       "局部翻译、结构不变"才有可迁移性（改为自然语言句式会让所有下游匹配同时失效）。</li>
 *   <li><b>详情尾巴</b>：{@code alert.msg.*} —— {@code key=} / {@code 值=} / {@code 阈值()} /
 *       {@code 持续} / {@code 下一步:}。字段名（{@code key=}）保持 ASCII，因为它是给运维搜日志用的；
 *       中文版的"值="在英文版里是" value="。</li>
 *   <li><b>阈值说明</b>：{@code alert.threshold.<metric>} —— 每个指标的比较方向不同
 *       （TPS 是"低于"、内存是"高于等于"），全塞进一个模板会译错方向。</li>
 *   <li><b>下一步</b>：{@code alert.next-step.<metric>} —— runbook 建议。
 *       {@code /spark heapdump}、{@code /perfscope hotspot} 这类<b>真实命令在两种语言里逐字不变</b>：
 *       翻译一条命令等于给出一条不存在的指令，比不给建议更糟（与 {@code DiagnoseMessages} 同口径）。</li>
 *   <li><b>每日摘要</b>：{@code daily.*} —— 标题、指标行、告警统计行。</li>
 *   <li><b>控制台日志</b>：{@code alert.log.*} —— 生命周期、配置校验、状态持久化的 WARNING。
 *       统一带 {@code alert.log.prefix}（{@code [告警] } / {@code [Alert] }）。</li>
 * </ul>
 *
 * <p><b>绝不进入本表的四类令牌</b>（与 I18N-AUDIT §3 一致，改动会破坏持久化或下游匹配）：</p>
 * <ol>
 *   <li><b>事件令牌</b> {@code open/upgrade/downgrade/resolve/refire/...} —— 写进
 *       {@code alerts.jsonl} 的 {@code type} 字段；其展示名已由 {@code alert.event.*} 提供（Phase 2），
 *       本类不重复定义。</li>
 *   <li><b>alerts.jsonl 字段名</b> {@code ts/type/key/level/value/display} —— 外部看板与
 *       {@code parseEventLine} 都按字面读。</li>
 *   <li><b>告警 key</b>（{@code cpu:system}）与<b>级别令牌</b>（{@code warning}/{@code critical}）——
 *       前者是身份，后者是 {@code Alert#critical()} 的判据。</li>
 *   <li><b>{@code alerts.state} 的列分隔与转义</b> —— 文件格式本身，翻译它等于换格式。</li>
 * </ol>
 *
 * <p><b>注册时序</b>：静态块调用 {@link Messages#registerModule}，与 {@code CoreMessages} 同构；
 * 必须在 {@code Messages.load} 之前由启动流程登记 {@code Messages.onFirstLoad(AlertMessages::init)}，
 * 否则首批告警会在默认表合并之前渲染出键名。</p>
 *
 * <p><b>占位符</b>：数值/时长/服名一律由调用点算好后按 {@code {0}/{1}} 传入，本类不做任何数值格式化。
 * 这样中英文可以各自调整语序（"持续 {0}" vs "lasted {0}"），而数值本身与日志、报告逐字一致。</p>
 */
public final class AlertMessages {

    static {
        Map<String, String> en = new LinkedHashMap<>();
        Map<String, String> zh = new LinkedHashMap<>();

        // ==================== 级别词（只出现在方括号里） ====================
        put(en, zh, "alert.level.warning", "Warning", "警告");
        put(en, zh, "alert.level.critical", "Critical", "严重");

        // ==================== 消息前缀（方括号结构保留，括号内可翻译） ====================
        // {0} = 级别词，{1} = 服名（motd 首行或端口号）
        put(en, zh, "alert.prefix.console", "[Alert][{0}][{1}] ", "[告警][{0}][{1}] ");
        put(en, zh, "alert.prefix.game", "[Alert·{0}] [{1}] ", "[告警·{0}] [{1}] ");
        // digest 前缀与单条告警**不同**：原实现写成 "] ["（级别括号与服名括号之间有一个空格）。
        // 单独成键而不是复用 alert.prefix.console + 手动补空格——中文输出才能与改造前逐字节一致。
        put(en, zh, "alert.prefix.digest", "[Alert][{0}] [{1}] ", "[告警][{0}] [{1}] ");
        // 恢复：级别位固定为"恢复"，不随 critical/warning 变
        put(en, zh, "alert.prefix.recovery.console", "[Alert][Recovery][{0}] ", "[告警][恢复][{0}] ");
        put(en, zh, "alert.prefix.recovery.game", "[Alert·Recovery] ", "[告警·恢复] ");
        // 缓解（critical→warning）：控制台保留级别位，便于与"恢复"区分
        put(en, zh, "alert.prefix.mitigated.console", "[Alert][Mitigated·{0}][{1}] ", "[告警][缓解·{0}][{1}] ");
        put(en, zh, "alert.prefix.mitigated.game", "[Alert·Mitigated] [{0}] ", "[告警·缓解] [{0}] ");
        // 控制台日志统一前缀（生命周期、配置校验、状态落盘）
        put(en, zh, "alert.log.prefix", "[Alert] ", "[告警] ");

        // ==================== 消息尾巴（正文与详情之间） ====================
        put(en, zh, "alert.msg.escalated", " (level escalated)", "（级别升级）");
        put(en, zh, "alert.msg.mitigated.console",
            " (downgraded from Critical to Warning — the issue persists, not recovered)",
            "（已由「严重」降为「警告」，问题仍在，未恢复）");
        put(en, zh, "alert.msg.mitigated.game",
            " (downgraded to Warning — the issue persists)",
            "（已降为警告，问题仍在）");
        put(en, zh, "alert.msg.refire", " (the problem has recurred)", "（问题再次出现）");

        // ==================== 详情字段（控制台 / 游戏内） ====================
        // key= 保持 ASCII：它是运维 grep 日志的锚点，不是文案
        put(en, zh, "alert.msg.key", " | key={0}", " | key={0}");
        put(en, zh, "alert.msg.value", " value={0}", " 值={0}");
        put(en, zh, "alert.msg.threshold", " threshold({0})", " 阈值({0})");
        put(en, zh, "alert.msg.duration", " | lasted {0}", " | 持续 {0}");
        put(en, zh, "alert.msg.next-step", " | next step: {0}", " | 下一步: {0}");
        // 恢复后的完整细节：{0}=key，{1}=持续时长，{2}=连续干净周期数
        put(en, zh, "alert.msg.recovery.console",
            " | key={0} | lasted {1}, no breach for {2} consecutive check cycles",
            " | key={0} | 持续 {1} 后连续 {2} 个检查周期未越界");
        put(en, zh, "alert.msg.recovery.game", " | lasted {0}", " | 持续 {0}");
        // 迟滞说明：解释"显示值低于阈值却仍在告警"（退出线低于进入线）
        // 不加前导空格——调用点负责在字段之间插入一个分隔空格
        put(en, zh, "alert.msg.hysteresis",
            "(hysteresis: clears only below {0}%)",
            "（迟滞中：需跌破 {0}% 才解除）");

        // ==================== digest（一周期合并推送） ====================
        put(en, zh, "alert.msg.digest-header", "{0} new alerts this cycle", "本周期新增 {0} 条告警");
        put(en, zh, "alert.msg.digest-line", "  · {0}: {1}", "  · {0}: {1}");
        put(en, zh, "alert.msg.digest-line-value", "  · {0}: {1} ({2})", "  · {0}: {1}（{2}）");
        put(en, zh, "alert.msg.digest-more",
            "  …and {0} more; run /perfscope alerts to list them",
            "  …等 {0} 条，执行 /perfscope alerts 查看");
        // start() 日志里的 digest: 开/关
        put(en, zh, "alert.msg.digest-on", "on", "开");
        put(en, zh, "alert.msg.digest-off", "off", "关");

        // ==================== 阈值说明（按指标，比较方向各不相同） ====================
        put(en, zh, "alert.threshold.tps",
            "warning < {0} / critical < {1}", "warning < {0} / critical < {1}");
        put(en, zh, "alert.threshold.mspt",
            "warning > {0}ms / critical > {1}ms", "warning > {0}ms / critical > {1}ms");
        put(en, zh, "alert.threshold.players",
            "warning > {0} / critical > {1}", "warning > {0} / critical > {1}");
        put(en, zh, "alert.threshold.entities",
            "warning > {0} / critical > {1}", "warning > {0} / critical > {1}");
        put(en, zh, "alert.threshold.entities.world",
            "see the worlds section for per-world thresholds", "世界阈值见 worlds 段");
        put(en, zh, "alert.threshold.chunks",
            "warning > {0} / critical > {1}", "warning > {0} / critical > {1}");
        put(en, zh, "alert.threshold.memory",
            "warning ≥ {0}% / critical ≥ {1}%", "warning ≥ {0}% / critical ≥ {1}%");
        // CPU 两个指标共用一个 metric 前缀，二者阈值同时给出
        put(en, zh, "alert.threshold.cpu",
            "process > {0}% / system > {1}%", "进程 > {0}% / 系统 > {1}%");

        // ==================== 下一步（runbook；命令逐字保留） ====================
        put(en, zh, "alert.next-step.tps",
            "run /perfscope diagnose to find the root cause; check entities, chunks and lagging plugins first",
            "执行 /perfscope diagnose 找根因，优先看实体/区块与卡顿插件");
        put(en, zh, "alert.next-step.mspt",
            "run /perfscope to inspect long ticks; check redstone machines and chunk loading",
            "执行 /perfscope 看长耗时 tick，检查红石机器与区块加载");
        put(en, zh, "alert.next-step.players",
            "normal at peak hours; off-peak, check for login churn or load-test traffic",
            "处于高峰属正常；非高峰请检查登录抖动或压测流量");
        put(en, zh, "alert.next-step.entities",
            "run /perfscope hotspot to locate dense chunks; clean up dropped items and villagers",
            "执行 /perfscope hotspot 定位实体密集区块，清理掉落物/村民");
        put(en, zh, "alert.next-step.chunks",
            "check view-distance and whether a pre-generation plugin (chunky etc.) is running",
            "检查 view-distance 与预生成插件（chunky 等）是否在跑");
        put(en, zh, "alert.next-step.memory",
            "check -Xmx and the GC log; if it stays high, add heap or hunt for a leak",
            "检查 -Xmx 与 GC 日志；持续高位可考虑扩容或排查内存泄漏");
        put(en, zh, "alert.next-step.cpu",
            "check host-neighbour load and backup jobs; use /perfscope to compare process vs system CPU",
            "检查宿主机邻居占用/备份任务；结合 /perfscope 看进程与系统 CPU");
        put(en, zh, "alert.next-step.default",
            "run /perfscope for a full diagnostic", "执行 /perfscope 查看全量诊断");

        // ==================== 控制台生命周期 / 配置校验日志 ====================
        put(en, zh, "alert.log.disabled",
            "Alert service is not enabled (alerts.enabled=false)",
            "告警服务未启用（alerts.enabled=false）");
        // v1.3.0 -14：noop 句柄（调度没装上）时的失败日志。此前是调用点内联的中英三元表达式，
        // 与"其余文案一律走键表"的口径不一致——同一份控制台输出因此有两套实现路径。
        put(en, zh, "alert.log.start-failed-no-scheduler",
            "Alert service did not start (scheduler unavailable; no alerts will fire; "
                + "/perfscope reload to retry)",
            "告警服务未能启动（调度不可用，告警不会触发；/perfscope reload 可重试）");
        put(en, zh, "alert.log.started",
            "Alert service started (interval: {0} ticks, cooldown: {1}s, debounce: {2} cycles, "
                + "recovery debounce: {3} cycles, digest: {4})",
            "告警服务已启动 (检查间隔: {0} ticks, 冷却: {1} 秒, 防抖: {2} 周期, "
                + "恢复防抖: {3} 周期, digest: {4})");
        put(en, zh, "alert.log.startup-suppress",
            "no TPS alerts will be pushed during the {0}s startup suppression window "
                + "(avoids false alarms from the empty report window)",
            "启动抑制窗口 {0} 秒内不推送 TPS 告警（避免报表空窗误报）");
        put(en, zh, "alert.log.startup-suppress-skip",
            "TPS push skipped during the startup suppression window: {0} "
                + "(still counted as active and recorded in history)",
            "启动抑制窗口内跳过 TPS 推送: {0}（仍计入活跃状态与历史）");
        put(en, zh, "alert.log.startup-suppress-end",
            "Startup suppression window ended (TPS alerts from the window are recorded as state "
                + "and history but are not replayed; TPS alerts push normally again)",
            "启动抑制窗口已结束（窗口内的 TPS 告警已计入状态与历史，"
                + "不补发；后续 TPS 告警恢复正常推送）");
        put(en, zh, "alert.log.bad-recovery-debounce",
            "alerts.recovery-debounce-cycles={0} is invalid (must be ≥1); using the debounce cycle count {1}",
            "alerts.recovery-debounce-cycles={0} 非法（需 ≥1），已按防抖周期数 {1} 处理");
        put(en, zh, "alert.log.bad-unknown-after",
            "alerts.unknown-after-minutes={0} is invalid (must be ≥1); using {1} minutes",
            "alerts.unknown-after-minutes={0} 非法（需 ≥1），已按 {1} 分钟处理");
        put(en, zh, "alert.log.bad-refire",
            "alerts.refire-min-interval-seconds={0} is invalid (must be ≥0); using the default {1}s",
            "alerts.refire-min-interval-seconds={0} 非法（需 ≥0），已按默认值 {1} 秒处理");
        // f-s01-err-10：非数值 / 非整数的写法（如 "1m"、"abc"、1.5）。与上方 bad-refire 分开成键：
        // 那条讲"值越界"，这条讲"根本解析不出来"，成因不同、修法也不同（改格式 vs 改大小）。
        put(en, zh, "alert.log.refire-unparsable",
            "alerts.refire-min-interval-seconds=\"{0}\" cannot be read as a whole number of seconds; "
                + "using the default {1}s — fix the value and run /perfscope reload",
            "alerts.refire-min-interval-seconds=\"{0}\" 无法解析为整数秒，已按默认值 {1} 秒处理；"
                + "请修正该值后执行 /perfscope reload");
        // f-s01-err-08：部分玩家投递失败。{0}=失败人数，{1}=首个失败者「玩家名: 异常类型 消息」。
        put(en, zh, "alert.log.game-partial",
            "in-game alert delivery failed for {0} player(s); first failure: {1} "
                + "(the remaining players still received it)",
            "游戏内告警投递失败 {0} 名玩家；首个失败: {1}（其余玩家仍已收到）");
        put(en, zh, "alert.log.bad-flap",
            "alerts.flap-window-seconds={0} is invalid (must be ≥0); using the default {1}s",
            "alerts.flap-window-seconds={0} 非法（需 ≥0），已按默认值 {1} 秒处理");
        put(en, zh, "alert.log.bad-repush",
            "alerts.repush-delta={0} is invalid (must be ≥0); using the default {1}",
            "alerts.repush-delta={0} 非法（需 ≥0），已按默认值 {1} 处理");
        put(en, zh, "alert.log.options-failed",
            "failed to read the extended alert options (running on defaults): {0}",
            "告警扩展配置读取失败（按默认值运行）: {0}");
        put(en, zh, "alert.log.rawconfig-failed",
            "failed to read the extended configuration (all defaults in use): {0}",
            "扩展配置读取失败（全部使用默认值）: {0}");
        put(en, zh, "alert.log.cycle-failed", "check cycle failed: {0}", "本轮检查失败: {0}");

        // ==================== 控制台状态机诊断日志 ====================
        put(en, zh, "alert.log.resume-unknown",
            "module recovered; silently resuming the unknown alert: {0} (not re-pushed)",
            "模块恢复，静默接管 unknown 告警: {0}（不重复推送）");
        put(en, zh, "alert.log.resume-restored",
            "silently resuming an unresolved alert after restart: {0} (not re-pushed)",
            "重启后静默接管未恢复告警: {0}（不重复推送）");
        put(en, zh, "alert.log.module-down",
            "module {0} has been unavailable for more than {1} minutes; alert {2} moved to unknown",
            "模块 {0} 不可用超过 {1} 分钟，告警 {2} 转入 unknown 状态");
        put(en, zh, "alert.log.recovery-no-module",
            "recovery check: key={0} carries no module attribution (treated as a normal recovery)",
            "恢复判定：key={0} 未携带模块归因（按正常恢复处理）");
        put(en, zh, "alert.log.flap-suppressed",
            "flap suppression: key={0} recovered only {1}ms ago (< {2}ms); recovery not announced this cycle",
            "抖动抑制：key={0} 距上次恢复仅 {1}ms（< {2}ms），本次不宣布恢复");
        put(en, zh, "alert.log.flap-healed",
            "{0} has been clean for {1} consecutive cycles; flap counter reset",
            "{0} 已连续 {1} 个周期干净，抖动计数清零");
        put(en, zh, "alert.log.flap-escalate",
            "{0} has flapped {2} times within {1}s; the recovery criterion is now "
                + "\"{1}s of continuous clean time\" (announced only on a lasting recovery)",
            "{0} 在 {1} 秒内已抖动 {2} 次，恢复判据改为「连续干净 {1} 秒」"
                + "（仅在真正持续恢复时才通知）");

        // ==================== 投递通道异常 ====================
        put(en, zh, "alert.log.console-failed", "console push failed: {0}", "控制台推送失败: {0}");
        put(en, zh, "alert.log.game-failed", "in-game push failed: {0}", "游戏内推送失败: {0}");
        put(en, zh, "alert.log.webhook-failed", "failed to queue the webhook message: {0}", "webhook 入队失败: {0}");

        // ==================== Webhook 出口（v1.3.0 -14：控制台零中文） ====================
        // 前缀一律由调用点拼 alert.log.prefix，本组键只承载内容——[告警] / [Alert] 不在这里重复。
        //
        // 一条硬约束：{0}/{1} 只放 host 或 type，**绝不放完整 URL**。webhook URL 通常内嵌
        // Discord token / OneBot access_token，进日志即等于泄露（9A-16 漏洞 1）。
        // 因此本组文案里所有 "url" 都只作名词出现，不回显值。
        put(en, zh, "alert.log.webhook-no-url",
            "webhook is enabled but the url is empty; push stays disabled",
            "webhook 已启用但 url 为空，推送保持关闭");
        put(en, zh, "alert.log.webhook-bad-url",
            "failed to parse the webhook url (malformed URL); push stays disabled",
            "webhook url 解析失败（malformed URL），推送保持关闭");
        // {0} = 拒绝原因（由 alert.webhook.reject.* 渲染后传入），{1} = host
        put(en, zh, "alert.log.webhook-url-rejected",
            "webhook url rejected ({0}; host={1}); push stays disabled",
            "webhook url 被拒绝（{0}，host={1}），推送保持关闭");
        put(en, zh, "alert.log.webhook-enabled",
            "webhook enabled (type={0}, host={1})",
            "webhook 已启用（type={0}, host={1}）");
        // 失败限频提示：说明"同类 60 秒内只出现一次"是限频而非故障消失，
        // 否则运维会以为推送恢复了。{0} = 失败原因（下面 alert.log.webhook-det-* 之一）
        put(en, zh, "alert.log.webhook-push-failed",
            "webhook push failed (the same category is logged at most once per 60s): {0}",
            "webhook 推送失败（同类 60 秒内不重复记）: {0}");
        // 失败原因片段：被上面 {0} 消费，故只写"哪一步坏了"，不再重复 "webhook" 主语。
        // {0} 一律是异常**类名**或 HTTP 状态码——绝不拼 e.getMessage()（JDK 会回显完整 URL）。
        put(en, zh, "alert.log.webhook-det-serialize",
            "serialization failed ({0})", "序列化失败 ({0})");
        put(en, zh, "alert.log.webhook-det-request",
            "async request failed ({0})", "异步请求失败 ({0})");
        put(en, zh, "alert.log.webhook-det-http",
            "HTTP {0}", "HTTP {0}");
        put(en, zh, "alert.log.webhook-det-build",
            "request build failed ({0})", "请求构建失败 ({0})");
        // general.debug 门控的排查行：{0} = 已渲染的原因（同样不含 URL）。
        put(en, zh, "alert.log.webhook-debug", "webhook: {0}", "webhook: {0}");

        // ---------- SSRF 目的地校验的拒绝原因 ----------
        // 键族与上面的日志键分开：它们是**原因**而不是完整句子，被 alert.log.webhook-url-rejected
        // 的 {0} 消费，也会原样进入 debug 日志。分开的收益是原因可以被单独复用/断言，
        // 且换语言时不需要改调用点的句子结构。
        put(en, zh, "alert.webhook.reject.no-scheme",
            "missing scheme", "缺少 scheme");
        put(en, zh, "alert.webhook.reject.scheme-not-http",
            "scheme is not http(s)", "scheme 非 http(s)");
        put(en, zh, "alert.webhook.reject.userinfo",
            "the URL carries userinfo credentials", "URL 含 userinfo 凭据");
        put(en, zh, "alert.webhook.reject.no-host",
            "the URL has no hostname (malformed URL)", "URL 缺少主机名（malformed URL）");
        put(en, zh, "alert.webhook.reject.ip-unparsable",
            "the IP literal cannot be parsed", "IP 字面量无法解析");
        put(en, zh, "alert.webhook.reject.http-loopback-only",
            "http is only allowed for loopback addresses (127.0.0.1 / ::1)",
            "http 仅允许回环地址（127.0.0.1 / ::1）");
        put(en, zh, "alert.webhook.reject.non-numeric-host",
            "the hostname is a non-standard numeric form (possible IP-check bypass)",
            "主机名为非标准数字形式（疑似绕过 IP 校验）");
        put(en, zh, "alert.webhook.reject.http-localhost-only",
            "http is only allowed for localhost / *.localhost (SSRF protection)",
            "http 仅允许 localhost / *.localhost（防 SSRF）");
        put(en, zh, "alert.webhook.reject.reserved-zero",
            "the target is in 0.0.0.0/8", "目标是 0.0.0.0/8");
        put(en, zh, "alert.webhook.reject.reserved-ula",
            "the target is an IPv6 ULA address (fc00::/7)", "目标是 IPv6 ULA（fc00::/7）");
        put(en, zh, "alert.webhook.reject.reserved-any",
            "the target is the unspecified address (0.0.0.0 / ::)",
            "目标是任意地址（0.0.0.0 / ::）");
        // 两个保留网段的原因里刻意保留"云元数据端点高危"——它不是解释，是给运维的处置提示
        put(en, zh, "alert.webhook.reject.reserved-link-local",
            "the target is a link-local address (169.254.0.0/16, fe80::/10) - "
                + "cloud metadata endpoints are a high risk here",
            "目标是 link-local 地址（169.254.0.0/16、fe80::/10）——云元数据端点高危");
        put(en, zh, "alert.webhook.reject.reserved-site-local",
            "the target is a private-network address (10/8, 172.16/12, 192.168/16)",
            "目标是内网地址（10/8、172.16/12、192.168/16）");

        // ==================== 状态文件（alerts.state，仅注释行；列格式不变） ====================
        put(en, zh, "alert.state.header",
            "# PerfScope alert state (auto-generated; do not edit)",
            "# PerfScope 告警状态（自动生成，请勿手改）");
        put(en, zh, "alert.state.legend",
            "# state: active | unknown (suspended while the module is unavailable; not re-reported after restart)",
            "# state: active | unknown（模块长期不可用被挂起；重启后同样不重报）");
        put(en, zh, "alert.log.state-dir-failed",
            "failed to create the alert state directory; state is kept in memory only",
            "告警状态目录创建失败，状态仅在内存生效");
        put(en, zh, "alert.log.state-serialize-failed",
            "failed to serialize the alert state: {0}", "告警状态序列化失败: {0}");
        put(en, zh, "alert.log.state-write-failed",
            "failed to write the alert state (may be re-evaluated after restart; runtime unaffected): {0}",
            "告警状态写入失败（重启后可能重复评估，不影响运行）: {0}");
        put(en, zh, "alert.log.state-migrated",
            "alert state upgraded to the new format; the old file was backed up as {0} "
                + "(use it to restore if you downgrade)",
            "告警状态已升级为新格式，旧格式原件已备份为 {0}（如需回退到旧版本可用它还原）");
        put(en, zh, "alert.log.state-backup-failed",
            "failed to back up the old-format state (continuing with the new format): {0}",
            "旧格式状态备份失败（继续按新格式写入）: {0}");
        put(en, zh, "alert.log.state-restored",
            "restored {0} unresolved alerts from alerts.state "
                + "(the first observation after a restart is not re-pushed)",
            "已从 alerts.state 恢复 {0} 条未恢复告警（重启后首次观测不重复推送）");
        put(en, zh, "alert.log.state-read-failed",
            "failed to read the alert state (starting from a clean state): {0}",
            "告警状态读取失败（按全新状态运行）: {0}");

        // ==================== 告警历史（alerts.jsonl，仅日志；字段名不改） ====================
        put(en, zh, "alert.log.events-write-failed",
            "failed to write the alert history (alerts themselves are unaffected): {0}",
            "告警历史写入失败（告警本身不受影响）: {0}");
        put(en, zh, "alert.log.events-read-failed",
            "failed to read the alert history (showing in-process events only): {0}",
            "告警历史读取失败（本次仅显示进程内事件）: {0}");

        // ==================== 每日摘要 ====================
        // 控制台首行：harness 锚点，中文版逐字保留
        put(en, zh, "daily.console-header", "=== Daily Summary ===", "=== 每日摘要 ===");
        put(en, zh, "daily.title.thin",
            "[PerfScope] Daily Summary | since the {0} restart (insufficient data)",
            "[PerfScope] 每日摘要 | 自 {0} 重启以来（数据不足）");
        put(en, zh, "daily.title.full",
            "[PerfScope] Daily Summary | coverage: last {0} min",
            "[PerfScope] 每日摘要 | 覆盖: 近 {0} 分钟");
        put(en, zh, "daily.note.thin",
            "coverage {0} min, less than 50% of {1} — trend conclusions are indicative only; "
                + "data from before the restart is lost",
            "覆盖 {0} 分钟，不足 {1} 的 50%——趋势结论仅供参考，重启前的数据已丢失");
        // f-s01-logic-07：只触发绝对下限（60 分钟）时的说明。不能复用 note.thin——那句无条件
        // 断言"不足 50%"，在刚重启不久（覆盖率其实已过半）时与自身数据矛盾。
        put(en, zh, "daily.note.floor",
            "coverage {0} min, less than the {1} min minimum — trend conclusions are indicative only; "
                + "the server has not been up long enough for a full daily picture",
            "覆盖 {0} 分钟，不足 {1} 分钟下限——趋势结论仅供参考，开机时长尚不足以构成完整的每日视图");
        put(en, zh, "daily.note.24h", "24 hours", "24 小时");
        put(en, zh, "daily.note.label", "Data notes: {0}", "数据说明: {0}");
        // 最低 TPS 附带发生时刻（{0}=TPS，{1}=HH:mm）
        put(en, zh, "daily.metrics.lowest-at", "{0} (at {1})", "{0}（{1}）");
        // {0}=最低 TPS（含时刻），{1}=p95，{2}=阈值，{3}=超阈分钟数，{4}=峰值实体，{5}=峰值内存
        put(en, zh, "daily.metrics",
            "Lowest TPS: {0} | p95 TPS: {1} | time below {2}: {3} min | peak entities: {4} | peak heap: {5}",
            "最低 TPS: {0} | p95 TPS: {1} | 低于 {2} 的累计时长: {3} 分钟 | 峰值实体: {4} | 峰值内存: {5}");
        // 告警统计行按段落拼接：base 必有，其余三段按条件追加
        put(en, zh, "daily.alerts.base",
            "Alerts: {0} pushed (ok {1} / failed {2})",
            "告警统计: 推送 {0} 条（成功 {1} / 失败 {2}）");
        put(en, zh, "daily.alerts.webhook",
            ", {0} queued for webhook (async egress; delivery is not observable)",
            "，webhook 已排队 {0} 条（异步出口，送达结果不可观测）");
        put(en, zh, "daily.alerts.active", " / {0} active now", " / 当前活跃 {0} 条");
        put(en, zh, "daily.alerts.suspended",
            " / {0} unknown (module unavailable)", " / unknown（模块不可用）{0} 条");

        // 每日摘要服务日志
        put(en, zh, "daily.log.disabled",
            "Daily summary is not enabled (alerts.daily-summary.enabled=false)",
            "每日摘要未启用（alerts.daily-summary.enabled=false）");
        put(en, zh, "daily.log.enabled",
            "Daily summary enabled (time: {0}, timezone: {1}{2})",
            "每日摘要已启用 (时刻: {0}, 时区: {1}{2})");
        // v1.3.0 -14：noop 句柄时的失败日志（与 alert.log.start-failed-no-scheduler 同构）
        put(en, zh, "daily.log.start-failed-no-scheduler",
            "Daily summary did not start (scheduler unavailable; no summaries will be sent; "
                + "/perfscope reload to retry)",
            "每日摘要未能启动（调度不可用，摘要不会发送；/perfscope reload 可重试）");
        put(en, zh, "daily.zone.system", " (system timezone)", "（系统时区）");
        put(en, zh, "daily.zone.differs",
            " (the system timezone is {0}; the two differ)",
            "（系统时区为 {0}，二者不同）");
        put(en, zh, "daily.log.zone-read-failed",
            "failed to read the timezone setting (falling back to the system timezone): {0}",
            "时区配置读取失败（改用系统时区）: {0}");
        put(en, zh, "daily.log.zone-bad",
            "alerts.daily-summary.timezone=\"{0}\" is not recognized "
                + "(an IANA name such as Asia/Shanghai is required); using the system timezone {1}",
            "alerts.daily-summary.timezone=\"{0}\" 无法识别（需 IANA 名如 Asia/Shanghai），"
                + "已改用系统时区 {1}");
        put(en, zh, "daily.log.sent-date-read-failed",
            "failed to read the daily-summary sent state (treating today as not sent): {0}",
            "每日摘要已发状态读取失败（将视为今日未发）: {0}");
        put(en, zh, "daily.log.state-dir-failed",
            "failed to create the daily-summary state directory; de-duplication applies in memory only",
            "每日摘要状态目录创建失败，本次去重仅在内存生效");
        put(en, zh, "daily.log.sent-date-write-failed",
            "failed to write the daily-summary sent state (may repeat after restart): {0}",
            "每日摘要已发状态写入失败（重启后可能重复推送）: {0}");
        put(en, zh, "daily.log.cycle-failed",
            "daily summary check failed this cycle: {0}", "每日摘要本轮检查失败: {0}");

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

    private AlertMessages() {}
}
