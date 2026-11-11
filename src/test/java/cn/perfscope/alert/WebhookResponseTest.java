/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook 响应判定（{@link WebhookResponse}）。
 *
 * <p>这组用例锁的是<b>"绝不能把失败判成成功"</b>。被测场景的共同点是：企业微信 / 飞书 / 钉钉
 * 在业务失败时返回 <b>HTTP 200</b>，错误码写在响应体里。旧实现丢掉响应体只看状态码，
 * 于是每一次失败都被当作投递成功——一条日志都不打。</p>
 *
 * <p>反面同样重要：解析不出内容时<b>必须判失败</b>，不能"看不懂就当成功"。真实场景是
 * 反向代理的登录页返回 HTTP 200 的 HTML，那种情况下"catch 里 return true"会让投递永久显示正常。</p>
 */
class WebhookResponseTest {

    // ==================== 企业微信 / 钉钉：errcode ====================

    @Test
    void wecomSuccess() {
        WebhookResponse.Result r = WebhookResponse.parse("wecom", 200,
            "{\"errcode\":0,\"errmsg\":\"ok\"}");
        assertTrue(r.ok(), "errcode 0 is success");
    }

    /**
     * 这是本类最重要的一条：HTTP 200 + 非零 errcode。
     *
     * <p>93300 是"webhook url 不合法或机器人已被移出群"——最常见的配置错误，
     * 而且 HTTP 层完全正常。</p>
     */
    @Test
    void wecomBusinessErrorAtHttp200IsAFailure() {
        WebhookResponse.Result r = WebhookResponse.parse("wecom", 200,
            "{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}");
        assertFalse(r.ok(), "HTTP 200 with errcode!=0 must NOT count as delivered");
        assertEquals(WebhookResponse.Outcome.BIZ_ERROR, r.outcome());
        assertEquals(93000L, r.bizCode());
    }

    @Test
    void wecomRateLimitIsReportedWithItsCode() {
        WebhookResponse.Result r = WebhookResponse.parse("wecom", 200,
            "{\"errcode\":45009,\"errmsg\":\"api freq out of limit\"}");
        assertEquals(WebhookResponse.Outcome.BIZ_ERROR, r.outcome());
        assertEquals(45009L, r.bizCode());
        assertTrue(r.detail().contains("freq"), "the platform's own message should survive: " + r.detail());
    }

    @Test
    void dingtalkSignMismatch() {
        WebhookResponse.Result r = WebhookResponse.parse("dingtalk", 200,
            "{\"errcode\":310000,\"errmsg\":\"sign not match\"}");
        assertEquals(WebhookResponse.Outcome.BIZ_ERROR, r.outcome());
        assertEquals(310000L, r.bizCode());
    }

    /**
     * 钉钉官方文档自相矛盾：字段表写 {@code Number}，示例却写 {@code "errcode":"0"}，
     * 而实测返回数字。解析器必须两种都吃，否则遇到字符串形态会抛异常或误判。
     */
    @Test
    void dingtalkAcceptsErrcodeAsStringOrNumber() {
        assertTrue(WebhookResponse.parse("dingtalk", 200,
            "{\"errcode\":0,\"errmsg\":\"ok\"}").ok());
        assertTrue(WebhookResponse.parse("dingtalk", 200,
            "{\"errcode\":\"0\",\"errmsg\":\"ok\"}").ok());
        assertFalse(WebhookResponse.parse("dingtalk", 200,
            "{\"errcode\":\"310000\",\"errmsg\":\"sign not match\"}").ok());
    }

    // ==================== 飞书 ====================

    @Test
    void feishuSuccess() {
        assertTrue(WebhookResponse.parse("feishu", 200,
            "{\"code\":0,\"msg\":\"success\"}").ok());
    }

    /** 官方标注 StatusCode 为"冗余字段，不建议使用"，但仍要在 code 缺失时能回退。 */
    @Test
    void feishuFallsBackToStatusCode() {
        assertTrue(WebhookResponse.parse("feishu", 200,
            "{\"StatusCode\":0,\"StatusMessage\":\"success\"}").ok());
    }

    @Test
    void feishuSignFailure() {
        WebhookResponse.Result r = WebhookResponse.parse("feishu", 200,
            "{\"code\":19021,\"msg\":\"sign match fail or timestamp is not within one hour\"}");
        assertEquals(WebhookResponse.Outcome.BIZ_ERROR, r.outcome());
        assertEquals(19021L, r.bizCode());
    }

    /** 空对象 {} 不能被默认判成功。 */
    @Test
    void feishuEmptyObjectIsBadBody() {
        assertEquals(WebhookResponse.Outcome.BAD_BODY,
            WebhookResponse.parse("feishu", 200, "{}").outcome());
    }

    // ==================== OneBot（这是已经发布的 bug） ====================

    @Test
    void onebotSuccess() {
        assertTrue(WebhookResponse.parse("onebot", 200,
            "{\"status\":\"ok\",\"retcode\":0}").ok());
    }

    /**
     * OneBot v11 的 {@code /send_private_msg} 恒定返回 HTTP 200，成败在 retcode 里。
     * 旧实现（只看 {@code statusCode >= 300}）从 v1.2 起就把这类失败全当成功了。
     */
    @Test
    void onebotFailureAtHttp200IsAFailure() {
        WebhookResponse.Result r = WebhookResponse.parse("onebot", 200,
            "{\"status\":\"failed\",\"retcode\":100,\"msg\":\"user not found\"}");
        assertFalse(r.ok(), "OneBot reports failure inside a 200 body");
        assertEquals(WebhookResponse.Outcome.BIZ_ERROR, r.outcome());
        assertEquals(100L, r.bizCode());
    }

    @Test
    void onebotAsyncCountsAsAccepted() {
        assertTrue(WebhookResponse.parse("onebot", 200,
            "{\"status\":\"async\",\"retcode\":1}").ok(),
            "async means queued for sending, which is acceptance not failure");
    }

    // ==================== Discord ====================

    /** Discord 成功是 204 且无 body——不能因为"body 是空的"就判 BAD_BODY。 */
    @Test
    void discord204WithNoBodyIsSuccess() {
        assertTrue(WebhookResponse.parse("discord", 204, "").ok());
        assertTrue(WebhookResponse.parse("discord", 200, "").ok());
    }

    @Test
    void discordHttpErrorIsFailure() {
        assertEquals(WebhookResponse.Outcome.HTTP_ERROR,
            WebhookResponse.parse("discord", 401, "").outcome());
        assertEquals(WebhookResponse.Outcome.HTTP_ERROR,
            WebhookResponse.parse("discord", 429, "{\"retry_after\":1.5}").outcome());
    }

    // ==================== 通用防线 ====================

    /** HTTP 错误优先于业务码——两者同时出现时按 HTTP 判。 */
    @Test
    void httpErrorTakesPrecedenceOverBusinessCode() {
        WebhookResponse.Result r = WebhookResponse.parse("wecom", 500,
            "{\"errcode\":0,\"errmsg\":\"ok\"}");
        assertEquals(WebhookResponse.Outcome.HTTP_ERROR, r.outcome());
        assertFalse(r.ok());
    }

    /**
     * 非 JSON 的 200 响应必须判失败。
     *
     * <p>真实场景：反向代理 / 门户认证拦截了请求，返回一个 HTTP 200 的 HTML 登录页。
     * 如果解析失败就"当成功"，投递会永久显示正常。</p>
     */
    @Test
    void htmlAtHttp200IsNotSuccess() {
        for (String type : new String[]{"wecom", "feishu", "dingtalk", "onebot"}) {
            WebhookResponse.Result r = WebhookResponse.parse(type, 200,
                "<html><body>Just a moment...</body></html>");
            assertEquals(WebhookResponse.Outcome.BAD_BODY, r.outcome(),
                type + " must not treat an HTML page as success");
            assertFalse(r.ok(), type + " reported success on HTML");
        }
    }

    @Test
    void nullAndEmptyBodyAreBadBodyForBusinessCodePlatforms() {
        assertEquals(WebhookResponse.Outcome.BAD_BODY,
            WebhookResponse.parse("wecom", 200, null).outcome());
        assertEquals(WebhookResponse.Outcome.BAD_BODY,
            WebhookResponse.parse("wecom", 200, "").outcome());
    }

    /** 未知类型不能默默成功——那正是"新平台加了但忘了加判据"的形态。 */
    @Test
    void unknownTypeIsNeverSuccess() {
        assertFalse(WebhookResponse.parse("slack", 200, "{\"ok\":false}").ok(),
            "an unknown platform must not be reported as delivered");
    }

    /**
     * 平台返回的错误文本里若含 URL，必须被抹掉——webhook URL 内嵌令牌，
     * 这与本包既有的"日志绝不出现完整 URL"纪律一致。
     */
    @Test
    void urlsInPlatformErrorMessagesAreScrubbed() {
        WebhookResponse.Result r = WebhookResponse.parse("wecom", 200,
            "{\"errcode\":40058,\"errmsg\":\"invalid at https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=SECRET\"}");
        assertFalse(r.detail().contains("SECRET"), "token must not leak into the log: " + r.detail());
        assertTrue(r.detail().contains("<url>"), "URL should be replaced, not dropped silently");
    }

    /** 超长错误文本被截断，避免平台回一坨东西把日志撑爆。 */
    @Test
    void longErrorMessagesAreTruncated() {
        String msg = "x".repeat(1000);
        WebhookResponse.Result r = WebhookResponse.parse("wecom", 200,
            "{\"errcode\":40058,\"errmsg\":\"" + msg + "\"}");
        assertTrue(r.detail().length() <= 201, "detail must be capped, got " + r.detail().length());
    }
}
