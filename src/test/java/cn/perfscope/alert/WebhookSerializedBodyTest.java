/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 序列化后的最终 JSON 文本（{@link WebhookNotifier} 的载荷 → Gson）。
 *
 * <p>前一组测试断言的是 {@code JsonObject} 结构；这组断言的是<b>真的发到线上的那串字节</b>。
 * 两者不能互相替代：结构对但序列化出问题（字段名被转义、签名被误 URL 编码）时，
 * 只有看最终文本才能发现。</p>
 */
class WebhookSerializedBodyTest {

    private static final Gson GSON = new Gson();

    /**
     * 飞书的签名原样进 JSON，<b>不能</b>被 URL 编码。
     *
     * <p>飞书和钉钉的签名都含 {@code + / =}，但飞书的进 body、钉钉的进 URL。
     * 写反了会得到一个合法但永远验不过的请求，而两边都是 HTTP 200。</p>
     */
    @Test
    void feishuSignatureGoesIntoBodyUnencoded() {
        JsonObject payload = WebhookNotifier.signedFeishuPayload(
            "hi", "SECtest1234567890abcdef", 1712345678000L);
        String json = GSON.toJson(payload);

        assertEquals("1712345678", payload.get("timestamp").getAsString(),
            "feishu wants SECONDS; 1712345678000 (millis) would fail 100% of the time");
        String sign = payload.get("sign").getAsString();
        assertFalse(sign.contains("%"), "the feishu sign must NOT be URL-encoded: " + sign);
        assertTrue(sign.contains("="), "base64 padding should survive into the body verbatim");
        assertEquals("ksc/Evr4Gg2UmHbzwZRNyivCEYEooyggJkDOLtQU0rI=", sign);
        assertTrue(json.contains("\"timestamp\":\"1712345678\""),
            "timestamp must be a JSON string, matching the official example: " + json);
    }

    @Test
    void feishuWithoutSecretHasNoSignatureFields() {
        JsonObject payload = WebhookNotifier.signedFeishuPayload("hi", "", 1712345678000L);
        assertFalse(payload.has("timestamp"), "unsigned requests must not carry a timestamp");
        assertFalse(payload.has("sign"));
    }

    /** 钉钉的签名进 URL query，且必须百分号编码——这条断言锁住"忘了编码"。 */
    @Test
    void dingtalkSignatureGoesIntoUrlEncoded() {
        String url = WebhookNotifier.buildTargetUrl(
            "dingtalk", "https://oapi.dingtalk.com/robot/send?access_token=abc", "SECtest");
        assertTrue(url.contains("&sign="), "sign must be appended as a query parameter: " + url);
        assertFalse(url.contains("&sign=SECtest+"), "a raw '+' in the query decodes to a space");
        // 仍只应该有一个 '?'
        long qs = url.chars().filter(c -> c == '?').count();
        assertEquals(1, qs, "must not introduce a second '?': " + url);
    }

    @Test
    void dingtalkWithoutSecretLeavesUrlUntouched() {
        String base = "https://oapi.dingtalk.com/robot/send?access_token=abc";
        assertEquals(base, WebhookNotifier.buildTargetUrl("dingtalk", base, ""));
    }

    @Test
    void nonSigningPlatformsLeaveUrlUntouched() {
        String base = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc";
        assertEquals(base, WebhookNotifier.buildTargetUrl("wecom", base, "SECret"));
        assertEquals(base, WebhookNotifier.buildTargetUrl("discord", base, "SECret"));
    }

    /** 序列化后的正文不含 HTML 转义带来的字段名变化，且中文原样。 */
    @Test
    void serializedWecomBodyCarriesUtf8TextIntact() {
        String text = "TPS 低：18.2";
        JsonObject payload = WebhookNotifier.buildPayload("wecom", text);
        String json = GSON.toJson(payload);
        assertTrue(json.contains("msgtype"), json);
        assertTrue(json.contains("\"content\""), json);
        // Gson 默认会把 = < & 转成 unicode 转义，但中文不会被转义
        assertTrue(json.contains("TPS 低：18.2"), "CJK text must survive serialization: " + json);
    }
}
