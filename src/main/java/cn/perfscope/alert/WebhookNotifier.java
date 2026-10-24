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

    /** 9A-16：Discord content 上限（Discord 硬上限 2000，留 100 字符余量） */
    private static final int MAX_DISCORD_CHARS = 1900;

    /** 9A-16：OneBot message 上限（各实现长度限制不一，保守取 1000） */
    private static final int MAX_ONEBOT_CHARS = 1000;

    private final PerfScope plugin;
    private final ConfigManager configManager;
    private final Gson gson = new Gson();

    /** 复用的 HttpClient（异步；构建一次） */
    private final HttpClient httpClient;

    /** 失败日志限频：上次输出失败日志的时间（epoch ms，0 = 从未）。仅主线程/异步回调原子性不敏感（单写者近似） */
    private volatile long lastFailureLogAtMs = 0L;

    /** 初始化时一次性配置快照（enabled/type/url）；reload 后由 {@link #reload()} 刷新 */
    private volatile boolean ready = false;
    private volatile String type = "discord";
    private volatile String url = "";

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
            plugin.getLogger().warning("[告警] webhook 已启用但 url 为空，推送保持关闭");
            ready = false;
            return;
        }
        // 9A-16（漏洞 1）：解析异常绝不输出 e.getMessage()——JDK 会回显完整 URL（含 token）。
        // 9A-16（漏洞 2）：解析成功后先做 SSRF 目的地校验，全部通过才允许 ready=true。
        URI uri;
        try {
            uri = URI.create(u);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[告警] webhook url 解析失败（malformed URL），推送保持关闭");
            ready = false;
            return;
        }
        String reject = rejectReason(uri);
        if (reject != null) {
            // 只记 host（常量 "?" 表示不可用），不含 path/query/fragment（可能内嵌令牌）
            plugin.getLogger().warning("[告警] webhook url 被拒绝（" + reject + "，host="
                + safeHost(uri) + "），推送保持关闭");
            ready = false;
            return;
        }
        this.url = u;
        this.type = "onebot".equals(t) ? "onebot" : "discord";
        this.ready = true;
        // 只记 host + type，不记完整 URL（安全要求）
        plugin.getLogger().info("[告警] webhook 已启用（type=" + this.type + ", host=" + safeHost(uri) + "）");
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
        String body;
        try {
            JsonObject obj = new JsonObject();
            if ("onebot".equals(type)) {
                // 9A-16（漏洞 3）：OneBot 侧同样限长，避免超长正文被端侧拒绝
                obj.addProperty("message", truncate(text, MAX_ONEBOT_CHARS));
            } else {
                // 9A-16（漏洞 3）：content 截断到 Discord 上限以内
                obj.addProperty("content", truncate(text, MAX_DISCORD_CHARS));
                // 9A-16（漏洞 3）：空 parse 列表 = 完全不解析 mention，
                // 世界名/玩家名里的 @everyone / @here / @role 不会再 ping 全频道
                JsonObject allowedMentions = new JsonObject();
                allowedMentions.add("parse", new JsonArray());
                obj.add("allowed_mentions", allowedMentions);
            }
            body = gson.toJson(obj);
        } catch (Exception e) {
            logFailure("序列化失败 (" + e.getClass().getSimpleName() + ")");
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        // 9A-16（漏洞 1）：只记异常类型。err.getMessage() 可能内嵌完整 URL/token
                        // （如 IllegalArgumentException: Illegal character in path at index N: https://...）
                        logFailure("异步请求失败 (" + err.getClass().getSimpleName() + ")");
                    } else if (resp.statusCode() >= 300) {
                        logFailure("HTTP " + resp.statusCode());
                    }
                });
        } catch (Exception e) {
            // URI.create / HttpRequest.newBuilder 等同步异常（配置已在 reload 校验，理论上罕见）
            // 9A-16（漏洞 1）：不拼 e.getMessage()，只记类型
            logFailure("请求构建失败 (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 9A-16（漏洞 2）：SSRF 目的地校验。通过返回 {@code null}，拒绝返回固定原因文本
     * （原因文本为常量，绝不含 URL / token）。
     *
     * <p>策略：{@code https} 允许任意主机名；{@code http} 仅允许 loopback
     * （127.0.0.1 / ::1 / localhost / *.localhost，保证本地 OneBot 可用）；
     * 任何形式的 IP 字面量还要额外过 {@link #classifyAddress(byte[])} 的保留网段黑名单。</p>
     */
    private static String rejectReason(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return "缺少 scheme";
        }
        boolean isHttps = "https".equalsIgnoreCase(scheme);
        boolean isHttp = "http".equalsIgnoreCase(scheme);
        if (!isHttps && !isHttp) {
            return "scheme 非 http(s)";
        }
        // 9A-16 顺手加固：URL 里内嵌 user:pass@ 凭据既无用（webhook 不用 Basic Auth），
        // 又会被 HttpClient 当 Authorization 发出去，直接拒绝
        if (uri.getRawUserInfo() != null) {
            return "URL 含 userinfo 凭据";
        }
        String host = uri.getHost();
        if (host == null) {
            // 例如 "http:///api/webhooks/123/token"（缺主机名）——不得放行
            return "URL 缺少主机名（malformed URL）";
        }
        String h = normalizeHost(host);
        if (h.isEmpty()) {
            return "URL 缺少主机名（malformed URL）";
        }
        if (isIpLiteral(h)) {
            byte[] ip = parseIpLiteral(h);
            if (ip == null) {
                return "IP 字面量无法解析";
            }
            String bad = classifyAddress(ip);
            if (bad != null) {
                return bad;
            }
            if (isHttp && !isLoopback(ip)) {
                // 明文 http 去向非回环主机：既可能 SSRF，也会明文传出 token
                return "http 仅允许回环地址（127.0.0.1 / ::1）";
            }
            return null;
        }
        // 主机名分支：不做 DNS 解析（避免 reload 阻塞主线程 & DNS rebinding 判定歧义）。
        // 9A-16（漏洞 2 补强）：JDK 会把 "169.254.1" / "2852039166" / "0x7f000001" 这类
        // 短写/整型/十六进制形式解析成真实 IP（实测 getByName 零延迟返回），若按普通主机名放行
        // 就能绕过上面的网段黑名单，故一律拒绝，只接受标准四段点分十进制。
        if (looksLikeNonStandardNumericHost(h)) {
            return "主机名为非标准数字形式（疑似绕过 IP 校验）";
        }
        if (isHttp && !isLocalhostName(h)) {
            return "http 仅允许 localhost / *.localhost（防 SSRF）";
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

    /** 保留/敏感网段判定；命中返回原因（常量文本），否则返回 {@code null}。 */
    private static String classifyAddress(byte[] ip) {
        // 0.0.0.0/8（isAnyLocalAddress 只覆盖 0.0.0.0 本身）
        if (ip.length == 4 && ip[0] == 0) {
            return "目标是 0.0.0.0/8";
        }
        // IPv6 ULA fc00::/7
        if (ip.length == 16 && (ip[0] & 0xFE) == 0xFC) {
            return "目标是 IPv6 ULA（fc00::/7）";
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByAddress(ip);
        } catch (UnknownHostException e) {
            return "IP 字面量无法解析";
        }
        if (addr.isAnyLocalAddress()) {
            return "目标是任意地址（0.0.0.0 / ::）";
        }
        if (addr.isLinkLocalAddress()) {
            return "目标是 link-local 地址（169.254.0.0/16、fe80::/10）——云元数据端点高危";
        }
        if (addr.isSiteLocalAddress()) {
            return "目标是内网地址（10/8、172.16/12、192.168/16）";
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
     * 失败日志：60 秒限频（同出口统一限频，防持续故障刷屏）；消息只含错误类别，
     * 不含完整 URL（安全要求）。
     */
    private void logFailure(String detail) {
        long now = System.currentTimeMillis();
        long last = lastFailureLogAtMs;
        if (now - last < 60_000L) {
            return;
        }
        lastFailureLogAtMs = now;
        plugin.getLogger().warning("[告警] webhook 推送失败（同类 60 秒内不重复记）: " + detail);
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
            plugin.getLogger().log(Level.FINE, "[告警] webhook: " + message
                + (t == null ? "" : " (" + t.getClass().getSimpleName() + ")"));
        }
    }
}
