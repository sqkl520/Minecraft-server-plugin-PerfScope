/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Webhook 请求载荷构造与 UTF-8 字节截断（{@link WebhookNotifier} 的静态纯函数部分）。
 *
 * <p>锁两件事：① 每个平台的字段名与嵌套形状（写错不会抛异常，只会被平台以 200+错误码拒绝）；
 * ② <b>截断必须按字节且在码点边界上切</b>——这是"看起来对、实际会切坏中文"的重灾区。</p>
 */
class WebhookPayloadTest {

    // ==================== 载荷形状 ====================

    @Test
    void wecomPayloadShape() {
        JsonObject o = WebhookNotifier.buildPayload("wecom", "hello");
        assertEquals("text", o.get("msgtype").getAsString());
        // 注意是 text.content 嵌套，不是顶层 content
        assertEquals("hello", o.getAsJsonObject("text").get("content").getAsString());
    }

    @Test
    void feishuPayloadShape() {
        JsonObject o = WebhookNotifier.buildPayload("feishu", "hello");
        // 飞书用 msg_type（下划线），且 content 是对象不是字符串
        assertEquals("text", o.get("msg_type").getAsString());
        assertTrue(o.get("content").isJsonObject(), "feishu content must be an object");
        assertEquals("hello", o.getAsJsonObject("content").get("text").getAsString());
    }

    @Test
    void dingtalkPayloadShape() {
        JsonObject o = WebhookNotifier.buildPayload("dingtalk", "hello");
        assertEquals("text", o.get("msgtype").getAsString());
        assertEquals("hello", o.getAsJsonObject("text").get("content").getAsString());
        // at 与 text 平级，且显式声明不 @ 任何人
        JsonObject at = o.getAsJsonObject("at");
        assertNotNull(at, "at object must be present");
        assertTrue(at.get("atMobiles").isJsonArray());
        assertEquals(0, at.getAsJsonArray("atMobiles").size());
        assertFalse(at.get("isAtAll").getAsBoolean(), "isAtAll must be false");
    }

    /** 9A-16 漏洞 3 的回归锁：没有空 parse 列表，世界名里的 @everyone 会 ping 全频道。 */
    @Test
    void discordKeepsAllowedMentionsEmpty() {
        JsonObject o = WebhookNotifier.buildPayload("discord", "hello @everyone");
        JsonObject mentions = o.getAsJsonObject("allowed_mentions");
        assertNotNull(mentions, "allowed_mentions is the only defence against @everyone");
        assertEquals(0, mentions.getAsJsonArray("parse").size(),
            "an empty parse list means mention syntax is not interpreted");
    }

    /**
     * OneBot 的对应防护：{@code auto_escape}。
     *
     * <p>与 Discord 的 allowed_mentions 是同一个漏洞换了个平台——OneBot 实现会把字符串
     * {@code message} 当 CQ 码解析，正文里的 {@code [CQ:at,qq=all]} 会 @全体成员。</p>
     */
    @Test
    void onebotSetsAutoEscape() {
        JsonObject o = WebhookNotifier.buildPayload("onebot", "[CQ:at,qq=all]");
        assertTrue(o.get("auto_escape").getAsBoolean(),
            "without auto_escape, CQ codes in the alert text are interpreted");
    }

    @Test
    void unknownTypeFallsBackToDiscordShape() {
        JsonObject o = WebhookNotifier.buildPayload("nonsense", "hello");
        assertNotNull(o.get("content"), "unknown types must not produce a malformed payload");
        assertNotNull(o.get("allowed_mentions"));
    }

    // ==================== 类型归一化 ====================

    @Test
    void normalizeTypeAcceptsTheFiveSupportedValues() {
        assertEquals("discord", WebhookNotifier.normalizeType("discord"));
        assertEquals("onebot", WebhookNotifier.normalizeType("onebot"));
        assertEquals("wecom", WebhookNotifier.normalizeType("wecom"));
        assertEquals("feishu", WebhookNotifier.normalizeType("feishu"));
        assertEquals("dingtalk", WebhookNotifier.normalizeType("dingtalk"));
    }

    @Test
    void normalizeTypeIsCaseAndWhitespaceInsensitive() {
        assertEquals("wecom", WebhookNotifier.normalizeType("  WeCom "));
        assertEquals("dingtalk", WebhookNotifier.normalizeType("DingTalk"));
    }

    @Test
    void normalizeTypeDefaultsToDiscordForUnknown() {
        assertEquals("discord", WebhookNotifier.normalizeType("weixin"));
        assertEquals("discord", WebhookNotifier.normalizeType(""));
        assertEquals("discord", WebhookNotifier.normalizeType(null));
    }

    // ==================== 字节截断（最容易写坏的地方） ====================

    /**
     * 700 个汉字 = 2100 字节，超过企业微信的 2048 字节上限。
     *
     * <p>这条用例专门打两种"看起来对"的错误实现：
     * <ul>
     *   <li>{@code s.substring(0, 2048)} —— 参数是 UTF-16 code unit，700 字原样通过，仍是 2100 字节</li>
     *   <li>{@code new String(bytes, 0, 2048, UTF_8)} —— 在多字节序列中间切断，
     *       产出 U+FFFD 乱码，且实际是 2049 字节</li>
     * </ul>
     */
    @Test
    void truncateUtf8RespectsByteBudgetOnCjk() {
        String in = "告".repeat(700);
        assertEquals(2100, in.getBytes(StandardCharsets.UTF_8).length);

        String out = WebhookNotifier.truncateUtf8(in, 2000);
        int outBytes = out.getBytes(StandardCharsets.UTF_8).length;

        assertTrue(outBytes <= 2000, "must fit the byte budget, got " + outBytes);
        assertFalse(out.contains("�"), "a byte cut split a UTF-8 sequence into a replacement char");
        assertTrue(out.endsWith("…"), "truncated output should end with an ellipsis");
        // 必须真的截了：原串 2100 字节，截断后应明显小于它
        assertTrue(outBytes < 2009, "output barely shrank, the cut probably did nothing: " + outBytes);
    }

    @Test
    void truncateUtf8LeavesShortStringsAlone() {
        String in = "short";
        assertEquals(in, WebhookNotifier.truncateUtf8(in, 2000));
    }

    @Test
    void truncateUtf8HandlesSurrogatePairs() {
        // emoji 是 4 字节，一个码点占两个 char
        String in = "🎉".repeat(600);   // 2400 字节
        String out = WebhookNotifier.truncateUtf8(in, 2000);
        assertTrue(out.getBytes(StandardCharsets.UTF_8).length <= 2000);
        assertFalse(out.contains("�"));
        // 不能留下半个代理对
        int last = out.length() - 2;   // 跳过末尾的省略号
        if (last >= 0) {
            assertFalse(Character.isHighSurrogate(out.charAt(last)),
                "output must not end on a dangling high surrogate");
        }
    }

    /** 按平台选对单位：Discord 用字符，企业微信/飞书/钉钉用字节。 */
    @Test
    void discordStillCountsCharactersNotBytes() {
        // 1900 个汉字：按字符远未超 1900，按字节是 5700。Discord 的上限是字符，所以不该截断
        String in = "汉".repeat(1900);
        JsonObject o = WebhookNotifier.buildPayload("discord", in);
        String content = o.get("content").getAsString();
        assertEquals(1900, content.length(), "Discord's limit is characters; CJK must not be shrunk 3x");
    }

    @Test
    void wecomTruncatesToBytesNotCharacters() {
        String in = "汉".repeat(2000);   // 6000 字节
        JsonObject o = WebhookNotifier.buildPayload("wecom", in);
        String content = o.getAsJsonObject("text").get("content").getAsString();
        assertTrue(content.getBytes(StandardCharsets.UTF_8).length <= 2000,
            "wecom limit is 2048 BYTES, not characters");
    }

    // ==================== 响应体读取上限（防 OOM） ====================

    /**
     * 读取响应体必须限长。
     *
     * <p>旧实现用 {@code BodyHandlers.discarding()} 流式丢弃，顺带保证了不会因为远端返回巨大
     * 响应而 OOM。改成读取响应体后这个保护就没了，而 webhook URL 是用户自填的。</p>
     */
    @Test
    void responseBodyIsCapped() {
        byte[] huge = new byte[1024 * 1024];   // 1 MB
        java.util.Arrays.fill(huge, (byte) 'a');
        String read = WebhookNotifier.readBodyCapped(huge);
        assertEquals(4096, read.length(), "response body must be capped at 4 KB");
    }

    @Test
    void responseBodyCapKeepsSmallBodiesIntact() {
        byte[] small = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
        assertEquals("{\"errcode\":0}", WebhookNotifier.readBodyCapped(small));
        assertEquals("", WebhookNotifier.readBodyCapped(null));
        assertEquals("", WebhookNotifier.readBodyCapped(new byte[0]));
    }
}
