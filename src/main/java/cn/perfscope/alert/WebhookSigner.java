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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 飞书 / 钉钉的请求签名（纯函数，无 Bukkit、无 IO、无日志，可脱离服务端单测）。
 *
 * <p><b>为什么单独成类</b>：这两家的签名算法只差一个 HMAC 参数的位置，而"复制粘贴另一个平台的实现"
 * 是这个功能最可能出的 bug——它产出的仍是长度合法的 44 字符 Base64，没有任何格式异常会暴露它。
 * 把两者放在同一个文件的相邻位置、配上逐字对照的注释和黄金向量测试，是唯一能挡住这类错误的结构。</p>
 *
 * <p><b>两家的差别（这是全类最重要的 12 行）</b>：</p>
 * <table border="1">
 *   <caption>签名构造对照</caption>
 *   <tr><th></th><th>飞书 Feishu</th><th>钉钉 DingTalk</th></tr>
 *   <tr><td>待签名串</td><td colspan="2">{@code timestamp + "\n" + secret}（两家完全相同）</td></tr>
 *   <tr><td>HMAC 的 <b>key</b></td><td><b>待签名串本身</b></td><td><b>secret</b></td></tr>
 *   <tr><td>HMAC 的 <b>数据</b></td><td><b>空字节数组</b></td><td><b>待签名串</b></td></tr>
 *   <tr><td>时间戳单位</td><td><b>秒</b>（10 位）</td><td><b>毫秒</b>（13 位）</td></tr>
 *   <tr><td>结果去向</td><td>JSON body 的 {@code timestamp}/{@code sign} 字段</td>
 *       <td>URL query 的 {@code &timestamp=&sign=}（需 URL 编码）</td></tr>
 *   <tr><td>结果是否 URL 编码</td><td><b>否</b>（原样进 JSON）</td><td><b>是</b>（Base64 的 +/= 必须转义）</td></tr>
 * </table>
 *
 * <p>飞书那个"key 是待签名串、数据为空"的构造是<b>反直觉的</b>：它意味着 secret 从不出现在被签名的数据里，
 * 只出现在 key 里。按常规 HMAC 习惯写出来的实现会得到一个看起来完全正常、但每次都被服务端拒绝的签名
 * （飞书返回 HTTP 200 + {@code code:19021}，钉钉返回 HTTP 200 + {@code errcode:310000}）。</p>
 *
 * <p><b>时间戳单位是签名的一部分，不是显示格式</b>。同一个 secret 换个单位，签名完全不同——
 * 把 {@code System.currentTimeMillis()} 传给飞书会让 100% 的请求失败，而本地验算"看起来是对的"。</p>
 *
 * <p><b>空白是载荷</b>：secret 两端若有空格或换行，签名会不同。本类刻意<b>不</b> trim，
 * 由调用方保证取值原样（见 {@code ConfigManager} 对该键的处理说明）。</p>
 *
 * @see <a href="https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot">飞书自定义机器人</a>
 * @see <a href="https://open.dingtalk.com/document/dingstart/customize-robot-security-settings">钉钉自定义机器人安全设置</a>
 */
public final class WebhookSigner {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private WebhookSigner() {
    }

    /**
     * 飞书签名。<b>key = 待签名串，数据 = 空。</b>
     *
     * @param timestampSeconds 当前 Unix 时间（<b>秒</b>，10 位）
     * @param secret           机器人「安全设置 → 签名校验」给出的密钥（原样，不 trim）
     * @return Base64 字符串，原样放进 JSON body 的 {@code sign} 字段（不要再 URL 编码）
     */
    public static String feishuSign(long timestampSeconds, String secret) {
        String stringToSign = timestampSeconds + "\n" + secret;
        return hmacBase64(
            stringToSign.getBytes(StandardCharsets.UTF_8),
            new byte[0]);
    }

    /**
     * 钉钉签名（未 URL 编码的原始 Base64）。<b>key = secret，数据 = 待签名串。</b>
     *
     * @param timestampMillis 当前 Unix 时间（<b>毫秒</b>，13 位）
     * @param secret          「安全设置 → 加签」给出的密钥（通常以 SEC 开头）
     */
    public static String dingtalkSignRaw(long timestampMillis, String secret) {
        String stringToSign = timestampMillis + "\n" + secret;
        return hmacBase64(
            secret.getBytes(StandardCharsets.UTF_8),
            stringToSign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 钉钉签名（已 URL 编码），可直接拼进 URL query。
     *
     * <p>Base64 字母表里有三个字符在 query string 中必须转义：{@code +} → {@code %2B}
     * （不转义会被服务端解码成空格）、{@code /} → {@code %2F}、{@code =} → {@code %3D}。
     * 漏掉这一步的报错是 {@code errcode 310000 sign not match}，且不会提示是编码问题。</p>
     */
    public static String dingtalkSignUrl(long timestampMillis, String secret) {
        return URLEncoder.encode(dingtalkSignRaw(timestampMillis, secret), StandardCharsets.UTF_8);
    }

    /**
     * 把钉钉的 {@code timestamp} 与 {@code sign} 追加到 webhook URL 上。
     *
     * <p><b>为什么不能简单写 {@code url + "?timestamp="}</b>：钉钉的 webhook URL 天生带 query
     * （{@code ?access_token=xxx}），再拼一个 {@code ?} 会让第二个 {@code ?} 变成 access_token 值的一部分，
     * 服务端返回 {@code errcode 300001 token is not exist}——一个和签名毫无关系的报错。</p>
     *
     * @param webhookUrl      原始 webhook URL（可能已带 query）
     * @param timestampMillis 毫秒时间戳
     * @param secret          加签密钥
     * @return 追加了签名参数的新 URL
     */
    public static String dingtalkAppendSign(String webhookUrl, long timestampMillis, String secret) {
        String sep = webhookUrl.indexOf('?') >= 0 ? "&" : "?";
        return webhookUrl + sep
            + "timestamp=" + timestampMillis
            + "&sign=" + dingtalkSignUrl(timestampMillis, secret);
    }

    /**
     * 该平台是否需要签名。
     *
     * <p>企业微信的凭证就在 URL 的 {@code key=} 里，没有加签机制；Discord 与 OneBot 也没有。
     * 只有飞书和钉钉有。</p>
     */
    public static boolean requiresSecret(String type) {
        return "feishu".equals(type) || "dingtalk".equals(type);
    }

    private static String hmacBase64(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            // 注意：SecretKeySpec 对零长度 key 抛 IllegalArgumentException（"Empty key"）。
            // 调用方必须先判空——见 requiresSecret 与 WebhookNotifier.reload() 的守卫。
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return Base64.getEncoder().encodeToString(mac.doFinal(message));
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 是 JLS 强制要求每个实现都提供的算法，缺它说明 JRE 被裁剪过
            throw new IllegalStateException("HmacSHA256 unavailable in this JRE", e);
        }
    }
}
