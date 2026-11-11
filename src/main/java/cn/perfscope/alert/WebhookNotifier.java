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

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.util.Messages;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Webhook 推送出口（Wave 7D）——告警 / 恢复 / 每日摘要的统一外部通道。
 *
 * <p>支持两种体格式：{@code discord}（{@code {"content": "..."}}）与 {@code onebot}（QQ，
 * {@code {"message": "..."}}）。JSON 用 Gson 序列化（provided 域、运行时有；仓库报告层同款依赖）。</p>
 *
 * <p><b>线程模型（硬要求）</b>：{@link #send(String)} 以 {@code sendAsync} 全异步发出——
 * 调用方（告警定时器在主线程）绝不阻塞；HttpClient 构建一次复用（connectTimeout 5s），
 * 单请求 requestTimeout 5s；响应体丢弃。</p>
 *
 * <p><b>安全（硬要求）</b>：日志与异常信息<b>绝不包含完整 URL</b>（只记 host 或 type）——
 * webhook URL 通常内嵌令牌（Discord webhook token / OneBot access_token），
 * 一旦进日志即等于泄露；URL 为空或非 http(s) 前缀 → {@link #isReady()} 为 false，
 * 初始化时一次 warning 说明原因，之后静默跳过。失败日志按错误类别 60 秒限频（防抖刷屏）。</p>
 *
 * <p><b>9A-16 加固</b>：
 * <ol>
 *   <li>日志脱敏——所有 catch / whenComplete 分支只记异常<b>类型</b>，绝不拼
 *       {@code e.getMessage()}（JDK 的 {@code URI.create} / {@code HttpRequest} 异常 message
 *       会把完整 URL 原样回显，实测已复现 token 泄露）；</li>
 *   <li>SSRF 防护——{@code https://} 允许任意主机；{@code http://} 仅允许 loopback
 *       （127.0.0.1 / ::1 / localhost / *.localhost，兼容本地 OneBot）；IP 字面量一律拒绝
 *       link-local（169.254/16、fe80::/10）、site-local（10/8、172.16/12、192.168/16）、
 *       0.0.0.0/8、IPv6 ULA（fc00::/7）与任意地址。校验在 {@code ready=true} <b>之前</b>完成；</li>
 *   <li>注入防护——Discord 体固定带 {@code allowed_mentions.parse = []}（世界名/玩家名里的
 *       {@code @everyone} 不再触发全频道 ping），content 截断至 1900 字符；OneBot 截断至 1000。</li>
 * </ol>
 * </p>
 */
public class WebhookNotifier {

    /** 单次请求超时（毫秒） */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** 9A-16：Discord content 上限（Discord 硬上限 2000，留 100 字符余量）。<b>单位：字符</b>。 */
    private static final int MAX_DISCORD_CHARS = 1900;

    /** 9A-16：OneBot message 上限（各实现长度限制不一，保守取 1000）。<b>单位：字符</b>。 */
    private static final int MAX_ONEBOT_CHARS = 1000;

    // ==================== 国内三平台的正文上限（v1.4） ====================
    // ⚠️ 注意单位差异：Discord / OneBot 的上面两个常量是**字符**，
    //    企业微信官方原文是"最长不超过2048个字节，必须是utf8编码"——是**字节**。
    //    中文 3 字节/字，所以 2048 字节只放得下约 682 个汉字；
    //    若沿用"按字符截到 1900"会把约 5700 字节发出去，直接被平台拒绝。

    /** 企业微信 text.content 上限 2048 字节，留 48 字节余量给序列化后的引号/转义。 */
    private static final int MAX_WECOM_BYTES = 2000;

    /**
     * 飞书没有正文字符上限，唯一硬约束是"请求体 ≤ 20 KB"。
     * 取 16000 字节，给 JSON 结构与可能的签名字段留出余量。
     */
    private static final int MAX_FEISHU_BYTES = 16000;

    /**
     * 钉钉正文上限官方文档未明确记载（网传 4000 字符 / 20000 字节，均无官方出处）。
     * 双限取小：16000 字节 ≈ 5300 汉字，同时不超过 4000 字符的保守口径。
     */
    private static final int MAX_DINGTALK_BYTES = 12000;

    /**
     * 响应体读取上限（字节）。
     *
     * <p>正常平台响应 < 1 KB。设这个上限是因为：旧实现用 {@code BodyHandlers.discarding()}
     * 流式丢弃响应体，顺带保证了不会因远端返回巨大响应而 OOM；改成读取响应体后这个保护就没了，
     * 而 webhook URL 是用户自填的（SSRF 校验允许任意 https 主机）。</p>
     */
    private static final int MAX_RESPONSE_BYTES = 4096;

    // ==================== SSRF 拒绝原因键（v1.3.0 -14） ====================
    // 提成常量而不是在分支里写字符串字面量，是为了让"判定"与"文案表"的耦合点集中在一处：
    // 改键名时只改这里，且 check-i18n 能把这些调用点当成"可解析的字面量"静态校验。
    // 全部定义在 AlertMessages 的 alert.webhook.reject.* 键族。
    private static final String KEY_REJECT_NO_SCHEME = "alert.webhook.reject.no-scheme";
    private static final String KEY_REJECT_SCHEME_NOT_HTTP = "alert.webhook.reject.scheme-not-http";
    private static final String KEY_REJECT_USERINFO = "alert.webhook.reject.userinfo";
    private static final String KEY_REJECT_NO_HOST = "alert.webhook.reject.no-host";
    private static final String KEY_REJECT_IP_UNPARSABLE = "alert.webhook.reject.ip-unparsable";
    private static final String KEY_REJECT_HTTP_LOOPBACK_ONLY = "alert.webhook.reject.http-loopback-only";
    private static final String KEY_REJECT_NON_NUMERIC_HOST = "alert.webhook.reject.non-numeric-host";
    private static final String KEY_REJECT_HTTP_LOCALHOST_ONLY = "alert.webhook.reject.http-localhost-only";
    private static final String KEY_REJECT_RESERVED_ZERO = "alert.webhook.reject.reserved-zero";
    private static final String KEY_REJECT_RESERVED_ULA = "alert.webhook.reject.reserved-ula";
    private static final String KEY_REJECT_RESERVED_ANY = "alert.webhook.reject.reserved-any";
    private static final String KEY_REJECT_RESERVED_LINK_LOCAL = "alert.webhook.reject.reserved-link-local";
    private static final String KEY_REJECT_RESERVED_SITE_LOCAL = "alert.webhook.reject.reserved-site-local";

    private final PerfScope plugin;
    private final ConfigManager configManager;
    private final Gson gson = new Gson();

    /** 复用的 HttpClient（异步；构建一次） */
    private final HttpClient httpClient;

    /**
     * 失败日志限频：上次输出失败日志的时间（epoch ms，0 = 从未）。
     * f-s01-conc-02：调用者是 HttpClient 的异步完成回调，多个失败可能并发进入；
     * 原先的 volatile long read-check-write 会同时放行，改为 AtomicLong 以保证限频精确。
     */
    private final java.util.concurrent.atomic.AtomicLong lastFailureLogAtMs =
        new java.util.concurrent.atomic.AtomicLong(0L);

    /** 初始化时一次性配置快照（enabled/type/url/secret）；reload 后由 {@link #reload()} 刷新 */
    private volatile boolean ready = false;
    private volatile String type = "discord";
    private volatile String url = "";
    /**
     * 加签密钥（v1.4）：仅飞书 / 钉钉使用，其余类型恒为空。
     *
     * <p>属凭据：日志、异常、命令回执<b>永不回显本值</b>，与 {@code prometheus.auth-token} 同级别。
     * 刻意 <b>不 trim</b>——空白是签名载荷的一部分，两端多一个空格会让验签 100% 失败。</p>
     */
    private volatile String secret = "";

    public WebhookNotifier(PerfScope plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        reload();
    }

    /**
     * 按当前配置刷新（构造与 reload 时调用）。校验失败仅一次 warning，不抛异常。
     */
    public void reload() {
        ConfigManager.ConfigData cfg = configManager.get();
        if (cfg == null || !cfg.alerts_webhookEnabled) {
            ready = false;
            return;
        }
        String u = cfg.alerts_webhookUrl == null ? "" : cfg.alerts_webhookUrl.trim();
        String t = cfg.alerts_webhookType == null ? "discord" : cfg.alerts_webhookType.trim();
        if (u.isEmpty()) {
            plugin.getLogger().warning(logText("alert.log.webhook-no-url"));
            ready = false;
            return;
        }
        // 9A-16（漏洞 1）：解析异常绝不输出 e.getMessage()——JDK 会回显完整 URL（含 token）。
        // 9A-16（漏洞 2）：解析成功后先做 SSRF 目的地校验，全部通过才允许 ready=true。
        URI uri;
        try {
            uri = URI.create(u);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(logText("alert.log.webhook-bad-url"));
            ready = false;
            return;
        }
        String rejectKey = rejectReasonKey(uri);
        if (rejectKey != null) {
            // 只记 host（常量 "?" 表示不可用），不含 path/query/fragment（可能内嵌令牌）
            plugin.getLogger().warning(logText("alert.log.webhook-url-rejected",
                Messages.get(rejectKey), safeHost(uri)));
            ready = false;
            return;
        }
        this.url = u;
        this.type = normalizeType(t);
        // secret 刻意不 trim：两端空白是签名载荷的一部分，trim 掉会让验签失败且无从排查。
        // 只把 null 归一成空串。
        this.secret = cfg.alerts_webhookSecret == null ? "" : cfg.alerts_webhookSecret;
        this.ready = true;
        // 只记 host + type，不记完整 URL（安全要求）；有无密钥只报布尔，绝不回显内容
        plugin.getLogger().info(logText("alert.log.webhook-enabled", this.type, safeHost(uri),
            WebhookSigner.requiresSecret(this.type) && hasText(this.secret) ? "signed" : "unsigned"));
    }

    /**
     * 类型归一化：只认这五个值，其余按 discord 处理。
     *
     * <p>{@link ConfigManager} 在加载期已经把未知值纠正成 {@code discord} 并打过 warning，
     * 这里是纵深防御——万一有人绕过配置层（或将来加了新的读取路径），也不会把 Discord 格式的
     * 载荷发到企业微信上去。</p>
     *
     * <p>注意：真正的"发错平台"保护不在这里，而在 {@link WebhookResponse}——
     * 平台会返回 HTTP 200 + 业务错误码，只要读了响应体就一定能发现。</p>
     */
    static String normalizeType(String t) {
        if (t == null) {
            return "discord";
        }
        String v = t.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "onebot", "wecom", "feishu", "dingtalk" -> v;
            default -> "discord";
        };
    }

    /**
     * 取一条 webhook 文案并拼上控制台统一前缀。
     *
     * <p>前缀（{@code [告警] } / {@code [Alert] }）只由 {@code alert.log.prefix} 提供，
     * 调用点不手写方括号——方括号是下游 grep 与 harness 断言的锚点，必须只有一处定义。</p>
     */
    private static String logText(String key, Object... args) {
        return Messages.get("alert.log.prefix") + Messages.get(key, args);
    }

    /** webhook 是否就绪（enabled 且 url 合法）。调用方在 false 时零开销跳过。 */
    public boolean isReady() {
        return ready;
    }

    /**
     * 异步发送一条纯文本消息。永不阻塞、永不抛异常给调用方；失败只记限频日志。
     * 消息对 discord 走 {@code content}、onebot 走 {@code message}。
     */
    public void send(String text) {
        if (!ready || text == null || text.isEmpty()) {
            return;
        }
        // v1.4：把当次的 type 抓成局部量再进 lambda。绝不在异步回调里读 this.type——
        // reload() 会改写那个 volatile 字段，若在飞书请求在途时切成 wecom，
        // 回调就会用 wecom 的解析器解读飞书的响应体（拿到 code 找不到 errcode，
        // 判成 BAD_BODY，产生一条假的失败日志）。与 f-s01-conc-02 的教训同源。
        final String typeSnapshot = type;
        String body;
        try {
            // 飞书的 timestamp/sign 要进 body，所以它的载荷与签名是一体的；
            // 其余平台签名在 URL（钉钉）或不需要签名，body 是纯载荷。
            JsonObject payload = "feishu".equals(typeSnapshot)
                ? signedFeishuPayload(text, secret, System.currentTimeMillis())
                : buildPayload(typeSnapshot, text);
            body = gson.toJson(payload);
        } catch (Exception e) {
            logFailure(Messages.get("alert.log.webhook-det-serialize", e.getClass().getSimpleName()));
            return;
        }
        URI target;
        try {
            target = URI.create(buildTargetUrl(typeSnapshot, url, secret));
        } catch (Exception e) {
            // 9A-16（漏洞 1）：不拼 e.getMessage()，只记类型
            logFailure(Messages.get("alert.log.webhook-det-build", e.getClass().getSimpleName()));
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "PerfScope/" + pluginVersion())
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((resp, err) -> {
                    // f-s01-err-11 / f-s01-conc-03：异步回调可能在插件 onDisable 之后才回来。
                    // HttpClient 在 JDK 17 无关闭路径、句柄也未保存（无法 cancel），所以这里
                    // 以 isEnabled() 兜底：停用后不再触碰插件代码（logger/配置），
                    // 避免关服窗口内产生归属不明、且会被吞掉的告警日志。
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    if (err != null) {
                        // 9A-16（漏洞 1）：只记异常类型。err.getMessage() 可能内嵌完整 URL/token
                        // （如 IllegalArgumentException: Illegal character in path at index N: https://...）
                        logFailure(Messages.get("alert.log.webhook-det-request",
                            err.getClass().getSimpleName()));
                        return;
                    }
                    // v1.4：三家国内平台在业务失败时也返回 HTTP 200，错误码在响应体里。
                    // 只看状态码 = 把每一次失败都当成功（OneBot 从 v1.2 起就一直如此）。
                    WebhookResponse.Result result =
                        WebhookResponse.parse(typeSnapshot, resp.statusCode(), readBodyCapped(resp.body()));
                    reportResult(result);
                });
        } catch (Exception e) {
            // URI.create / HttpRequest.newBuilder 等同步异常（配置已在 reload 校验，理论上罕见）
            // 9A-16（漏洞 1）：不拼 e.getMessage()，只拼类型
            logFailure(Messages.get("alert.log.webhook-det-build", e.getClass().getSimpleName()));
        }
    }

    /**
     * 按平台构造请求载荷。
     *
     * <p><b>长度单位是逐平台不同的</b>，这是最容易写错的地方：Discord 的上限 2000 是<b>字符</b>，
     * 而企业微信的 2048 是 <b>UTF-8 字节</b>——一个中文 3 字节，按字符截到 2048 会送出约 6 KB，
     * 直接被拒（{@code 40058}）。所以这里按平台选截断单位，而不是统一按字符。</p>
     */
    static JsonObject buildPayload(String type, String text) {
        JsonObject obj = new JsonObject();
        switch (type == null ? "" : type) {
            case "onebot" -> {
                // 9A-16（漏洞 3）：OneBot 侧限长，避免超长正文被端侧拒绝。
                // auto_escape：OneBot 实现会把字符串 message 当 CQ 码解析，
                // 告警正文里的世界名/MOTD 若含 [CQ:at,qq=all] 就会 @全体成员。
                // 与 Discord 的 allowed_mentions 是同一个漏洞，只是换了平台。
                obj.addProperty("message", truncate(text, MAX_ONEBOT_CHARS));
                obj.addProperty("auto_escape", true);
            }
            case "wecom" -> {
                obj.addProperty("msgtype", "text");
                JsonObject content = new JsonObject();
                content.addProperty("content", truncateUtf8(text, MAX_WECOM_BYTES));
                obj.add("text", content);
            }
            case "feishu" -> {
                obj.addProperty("msg_type", "text");
                JsonObject content = new JsonObject();
                content.addProperty("text", truncateUtf8(text, MAX_FEISHU_BYTES));
                obj.add("content", content);
            }
            case "dingtalk" -> {
                obj.addProperty("msgtype", "text");
                JsonObject content = new JsonObject();
                content.addProperty("content", truncateUtf8(text, MAX_DINGTALK_BYTES));
                obj.add("text", content);
                // 显式声明不 @ 任何人：不写 at 对象时钉钉默认不 @，
                // 但显式写出来能让"不会 ping 全群"成为可断言的事实，而不是依赖默认值。
                JsonObject at = new JsonObject();
                at.add("atMobiles", new JsonArray());
                at.addProperty("isAtAll", false);
                obj.add("at", at);
            }
            default -> {
                // Discord：content 截断到 Discord 上限以内（字符单位，Discord 的 2000 是字符）
                obj.addProperty("content", truncate(text, MAX_DISCORD_CHARS));
                // 9A-16（漏洞 3）：空 parse 列表 = 完全不解析 mention，
                // 世界名/玩家名里的 @everyone / @here / @role 不会再 ping 全频道
                JsonObject allowedMentions = new JsonObject();
                allowedMentions.add("parse", new JsonArray());
                obj.add("allowed_mentions", allowedMentions);
            }
        }
        return obj;
    }

    /**
     * 按平台构造最终投递 URL。
     *
     * <p>只有钉钉把签名放进 URL query；飞书的 timestamp/sign 进 JSON body（由
     * {@link #buildPayload} 之外的 {@link #signedFeishuPayload} 处理）。</p>
     */
    static String buildTargetUrl(String type, String url, String secret) {
        if ("dingtalk".equals(type) && hasText(secret)) {
            return WebhookSigner.dingtalkAppendSign(url, System.currentTimeMillis(), secret);
        }
        return url;
    }

    /**
     * 飞书的签名要进 body，因此它的载荷构造与签名耦合在一起。
     *
     * <p>与 {@link #buildPayload} 分开是为了让"无签名"路径保持纯粹——飞书用户没开签名校验时
     * 不该因为签名代码的存在而多出两个字段。</p>
     */
    static JsonObject signedFeishuPayload(String text, String secret, long nowMs) {
        JsonObject obj = buildPayload("feishu", text);
        if (hasText(secret)) {
            long tsSeconds = nowMs / 1000L;   // 飞书要秒。传毫秒会让 100% 的请求验签失败
            obj.addProperty("timestamp", Long.toString(tsSeconds));   // 官方示例是字符串
            obj.addProperty("sign", WebhookSigner.feishuSign(tsSeconds, secret));
        }
        return obj;
    }

    /**
     * 读取响应体，<b>带硬上限</b>。
     *
     * <p><b>为什么必须限长</b>：旧实现用 {@code BodyHandlers.discarding()} 流式丢弃响应体，
     * 顺带保证了不会因为远端回一个巨大响应而耗尽内存。改成读取响应体之后，这个保护就没了——
     * 而 webhook URL 是用户自己填的（SSRF 校验允许任意 https 主机），一个配错的 nginx
     * 或恶意端点回几 GB 内容，就会在 HttpClient 的回调线程里把 JVM 撑爆。</p>
     *
     * <p>正常平台响应都小于 1 KB；4 KB 足够容纳任何错误信息。</p>
     */
    static String readBodyCapped(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        int len = Math.min(body.length, MAX_RESPONSE_BYTES);
        return new String(body, 0, len, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String pluginVersion() {
        try {
            return plugin.getDescription() == null ? "unknown"
                : String.valueOf(plugin.getDescription().getVersion());
        } catch (Exception | LinkageError e) {
            return "unknown";
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 把投递判定结果落到日志。
     *
     * <p>按类别给出<b>可执行</b>的提示，而不是只报数字：签名错、被限流、机器人被移出群，
     * 这三类在 HTTP 层全是 200，唯一的区别就是业务码，而服主看到 {@code 310000}
     * 并不知道该去改什么。</p>
     */
    private void reportResult(WebhookResponse.Result result) {
        if (result.ok()) {
            return;
        }
        switch (result.outcome()) {
            case HTTP_ERROR -> logFailure(Messages.get(
                "alert.log.webhook-det-http", result.httpStatus()));
            case BIZ_ERROR -> logFailure(Messages.get(
                "alert.log.webhook-det-biz", result.bizCode(), result.detail()));
            case BAD_BODY -> logFailure(Messages.get(
                "alert.log.webhook-det-bad-body", result.detail()));
            default -> { /* OK 已在上方返回 */ }
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 9A-16（漏洞 2）：SSRF 目的地校验。通过返回 {@code null}，拒绝返回<b>原因键</b>
     * （{@code alert.webhook.reject.*}，由调用方用 {@link Messages#get} 渲染）。
     *
     * <p><b>为什么返回键名而不是渲染后的文案</b>：本方法及其下游 {@link #classifyAddress(byte[])}
     * 是纯函数——只吃 {@link URI} / {@code byte[]}，没有 {@code PerfScope}、不碰 logger、不读配置。
     * 让它们返回已渲染的文案，等于把 {@link Messages} 的"当前语言快照"读进一个与插件生命周期
     * 无关的判定函数里：reload 切语言后返回值会跟着变，同一个 URI 的判定结果因此不再是纯函数，
     * 单元测试也无法在不初始化消息层的前提下断言拒绝逻辑。返回键名则保持"判定"与"展示"分离：
     * 判定稳定可测，渲染时机由调用方（日志点）决定，天然跟随当前语言。
     * 代价是调用点多一次 {@code Messages.get}——只发生在 reload 这一个非热路径上。</p>
     *
     * <p>策略：{@code https} 允许任意主机名；{@code http} 仅允许 loopback
     * （127.0.0.1 / ::1 / localhost / *.localhost，保证本地 OneBot 可用）；
     * 任何形式的 IP 字面量还要额外过 {@link #classifyAddress(byte[])} 的保留网段黑名单。</p>
     *
     * @return 拒绝原因的消息键；通过校验返回 {@code null}
     */
    private static String rejectReasonKey(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return KEY_REJECT_NO_SCHEME;
        }
        boolean isHttps = "https".equalsIgnoreCase(scheme);
        boolean isHttp = "http".equalsIgnoreCase(scheme);
        if (!isHttps && !isHttp) {
            return KEY_REJECT_SCHEME_NOT_HTTP;
        }
        // 9A-16 顺手加固：URL 里内嵌 user:pass@ 凭据既无用（webhook 不用 Basic Auth），
        // 又会被 HttpClient 当 Authorization 发出去，直接拒绝
        if (uri.getRawUserInfo() != null) {
            return KEY_REJECT_USERINFO;
        }
        String host = uri.getHost();
        if (host == null) {
            // 例如 "http:///api/webhooks/123/token"（缺主机名）——不得放行
            return KEY_REJECT_NO_HOST;
        }
        String h = normalizeHost(host);
        if (h.isEmpty()) {
            return KEY_REJECT_NO_HOST;
        }
        if (isIpLiteral(h)) {
            byte[] ip = parseIpLiteral(h);
            if (ip == null) {
                return KEY_REJECT_IP_UNPARSABLE;
            }
            String bad = classifyAddress(ip);
            if (bad != null) {
                return bad;
            }
            if (isHttp && !isLoopback(ip)) {
                // 明文 http 去向非回环主机：既可能 SSRF，也会明文传出 token
                return KEY_REJECT_HTTP_LOOPBACK_ONLY;
            }
            return null;
        }
        // 主机名分支：不做 DNS 解析（避免 reload 阻塞主线程 & DNS rebinding 判定歧义）。
        // 9A-16（漏洞 2 补强）：JDK 会把 "169.254.1" / "2852039166" / "0x7f000001" 这类
        // 短写/整型/十六进制形式解析成真实 IP（实测 getByName 零延迟返回），若按普通主机名放行
        // 就能绕过上面的网段黑名单，故一律拒绝，只接受标准四段点分十进制。
        if (looksLikeNonStandardNumericHost(h)) {
            return KEY_REJECT_NON_NUMERIC_HOST;
        }
        if (isHttp && !isLocalhostName(h)) {
            return KEY_REJECT_HTTP_LOCALHOST_ONLY;
        }
        return null;
    }

    /** 去 IPv6 方括号、去 FQDN 尾点、转小写。 */
    private static String normalizeHost(String host) {
        String h = host.trim();
        if (h.length() > 2 && h.charAt(0) == '[' && h.charAt(h.length() - 1) == ']') {
            h = h.substring(1, h.length() - 1);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h.toLowerCase(Locale.ROOT);
    }

    /** 是否为 IP 字面量（含 IPv6 / IPv4-mapped）。 */
    private static boolean isIpLiteral(String h) {
        if (h.indexOf(':') >= 0) {
            return true;
        }
        // 严格点分十进制（不做 0x / 八进制 / 短写等 Java 宽松解析，避免绕过）
        return h.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /**
     * 解析 IP 字面量为字节数组；无法解析返回 {@code null}。
     * 只接收已经 {@link #isIpLiteral(String)} 判定的字面量，不会触发 DNS 查询。
     */
    private static byte[] parseIpLiteral(String h) {
        if (h.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(h).getAddress();
            } catch (UnknownHostException | SecurityException e) {
                return null;
            }
        }
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                v = v * 10 + (c - '0');
            }
            if (v > 255) {
                return null;
            }
            out[i] = (byte) v;
        }
        return out;
    }

    /**
     * 保留/敏感网段判定；命中返回原因键（{@code alert.webhook.reject.*}），否则返回 {@code null}。
     *
     * <p>返回键名而非文案的理由同 {@link #rejectReasonKey(URI)}：纯函数、可测、渲染交给调用方。</p>
     */
    private static String classifyAddress(byte[] ip) {
        // 0.0.0.0/8（isAnyLocalAddress 只覆盖 0.0.0.0 本身）
        if (ip.length == 4 && ip[0] == 0) {
            return KEY_REJECT_RESERVED_ZERO;
        }
        // IPv6 ULA fc00::/7
        if (ip.length == 16 && (ip[0] & 0xFE) == 0xFC) {
            return KEY_REJECT_RESERVED_ULA;
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByAddress(ip);
        } catch (UnknownHostException e) {
            return KEY_REJECT_IP_UNPARSABLE;
        }
        if (addr.isAnyLocalAddress()) {
            return KEY_REJECT_RESERVED_ANY;
        }
        if (addr.isLinkLocalAddress()) {
            return KEY_REJECT_RESERVED_LINK_LOCAL;
        }
        if (addr.isSiteLocalAddress()) {
            return KEY_REJECT_RESERVED_SITE_LOCAL;
        }
        return null;
    }

    /** 回环判定（127.0.0.0/8 与 ::1），不依赖 InetAddress（对 IPv4-mapped 更直观）。 */
    private static boolean isLoopback(byte[] ip) {
        if (ip.length == 4) {
            return ip[0] == 127;
        }
        if (ip.length == 16) {
            for (int i = 0; i < 15; i++) {
                if (ip[i] != 0) {
                    return false;
                }
            }
            return ip[15] == 1;
        }
        return false;
    }

    /** localhost / *.localhost（RFC 6761），已 normalize 为小写且去掉尾点。 */
    private static boolean isLocalhostName(String h) {
        return "localhost".equals(h) || h.endsWith(".localhost");
    }

    /**
     * 9A-16（漏洞 2 补强）：识别"看起来是数字但非标准四段点分十进制"的主机名。
     *
     * <p>JDK 的 {@code InetAddress.getByName} 接受 {@code 169.254.1}（三/二段短写）、
     * {@code 2852039166}（整型）等历史写法，而 {@link URI#getHost()} 会原样返回这些字符串，
     * 可绕过 IP 段黑名单。标准四段点分十进制已在上游 {@link #isIpLiteral(String)} 分支处理，
     * 因此这里凡是"纯数字+点"或"0x 十六进制"形式一律视为可疑，直接拒绝
     * （只做字符串判断，零 DNS 开销，避免 reload 阻塞主线程）。</p>
     */
    private static boolean looksLikeNonStandardNumericHost(String h) {
        if (h.isEmpty()) {
            return false;
        }
        if (h.startsWith("0x")) {
            return true;
        }
        boolean sawDigit = false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c >= '0' && c <= '9') {
                sawDigit = true;
            } else if (c != '.') {
                return false;
            }
        }
        return sawDigit;
    }

    /**
     * 9A-16（漏洞 3）：按字符数截断（保留尾部省略号），不劈开代理对。
     * {@code max} 为含省略号在内的总长度上限。
     */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        int cut = Math.max(0, max - 1);
        if (cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) {
            cut--;
        }
        return s.substring(0, cut) + "…";
    }

    /**
     * 按 <b>UTF-8 字节数</b>截断，且在<b>码点边界</b>上切。
     *
     * <p>为什么不能用 {@code new String(s.getBytes(UTF_8), 0, max, UTF_8)}：
     * 那样会在多字节序列中间切断，得到一个 {@code U+FFFD} 替换字符——中文/emoji 告警的
     * 结尾会变成乱码，而且字节数还会多出 1（实测 2048 切出 2049 字节）。</p>
     *
     * <p>为什么不能用 {@code s.substring(0, max)}：参数单位是 UTF-16 code unit，
     * 对中文毫无约束力——700 个汉字会原样通过，实际是 2100 字节。</p>
     *
     * <p>实现：逐码点累加字节数，超预算就停；省略号本身也占 3 字节，预算里先扣掉。</p>
     *
     * @param maxBytes 目标上限（含省略号）
     */
    static String truncateUtf8(String s, int maxBytes) {
        if (s == null) {
            return null;
        }
        int total = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (total <= maxBytes) {
            return s;
        }
        final int ellipsisBytes = "…".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int budget = maxBytes - ellipsisBytes;
        if (budget <= 0) {
            return "";
        }
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cpBytes = utf8Length(cp);
            if (bytes + cpBytes > budget) {
                break;
            }
            bytes += cpBytes;
            i += Character.charCount(cp);
        }
        return s.substring(0, i) + "…";
    }

    /** 单个码点的 UTF-8 字节数（1–4）。 */
    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    /**
     * 失败日志：60 秒限频（同出口统一限频，防持续故障刷屏）；消息只含错误类别，
     * 不含完整 URL（安全要求）。
     *
     * @param detail 已渲染的原因片段（来自 {@code alert.log.webhook-det-*}），本方法只负责套上
     *               限频说明与控制台前缀，不再拼任何字面量
     */
    private void logFailure(String detail) {
        long now = System.currentTimeMillis();
        // f-s01-conc-02：CAS 推进时间戳，只有推进成功的那一个线程获得输出权。
        // 比较-交换-重试的写法在这里不必要：败者直接放弃即可（它的信息与胜者同类）。
        long last = lastFailureLogAtMs.get();
        if (now - last < 60_000L) {
            return;
        }
        if (!lastFailureLogAtMs.compareAndSet(last, now)) {
            return;
        }
        plugin.getLogger().warning(logText("alert.log.webhook-push-failed", detail));
    }

    /** 提取已解析 URI 的 host 供日志（绝不出完整 URL；缺失返回 "?"）。 */
    private static String safeHost(URI uri) {
        try {
            String host = uri == null ? null : uri.getHost();
            return host == null ? "?" : normalizeHost(host);
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** 供 debug 排查用（general.debug 门控）：输出错误类别，但同样不含 URL。 */
    void debugLog(String message, Throwable t) {
        if (configManager.get() != null && configManager.get().general_debug) {
            // 9A-16（漏洞 1）：不传 Throwable——堆栈首行就是带完整 URL 的异常 message
            plugin.getLogger().log(Level.FINE, logText("alert.log.webhook-debug", message
                + (t == null ? "" : " (" + t.getClass().getSimpleName() + ")")));
        }
    }
}
