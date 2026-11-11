/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.alert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 飞书 / 钉钉加签（{@link WebhookSigner}）。
 *
 * <p>这些用例锁的是<b>黄金向量</b>，不是"跑得通"：签名错了不会抛异常，只会产出一个长度合法的
 * 44 字符 Base64，被平台以 HTTP 200 + 错误码拒绝。任何"看着像是对的"断言（长度、非空、是 Base64）
 * 在换错 HMAC 参数位置时<b>全都仍然通过</b>。</p>
 *
 * <p>四个向量中，{@code dingtalk/official} 与 {@code feishu/official} 来自两家官方文档的示例代码，
 * 另外两个由三套独立实现（Node crypto / Java Mac / openssl CLI）交叉验证一致。</p>
 */
class WebhookSignerTest {

    // ==================== 黄金向量 ====================

    /**
     * 钉钉官方文档的示例：secret 是 {@code this is secret}（本身含空格），毫秒时间戳。
     *
     * <p>这条同时验证"secret 不被 trim"——官方示例的密钥里就有空格，trim 掉会得到不同的签名。
     */
    @Test
    void dingtalkMatchesOfficialExample() {
        assertEquals("hmPWwU+7lVdm3ZZz0r9tSfx0L4Q26jWOZr9+Gs6EZQM=",
            WebhookSigner.dingtalkSignRaw(1577262236757L, "this is secret"));
    }

    /** 飞书官方文档的示例：secret {@code demo}，<b>秒</b>时间戳。 */
    @Test
    void feishuMatchesOfficialExample() {
        assertEquals("l1N0gAcBjdwBvGm1xMjOF0XSyaLRpR7tuO5dHfhAYc8=",
            WebhookSigner.feishuSign(1599360473L, "demo"));
    }

    @Test
    void feishuMatchesCrossVerifiedVector() {
        assertEquals("ksc/Evr4Gg2UmHbzwZRNyivCEYEooyggJkDOLtQU0rI=",
            WebhookSigner.feishuSign(1712345678L, "SECtest1234567890abcdef"));
    }

    @Test
    void dingtalkMatchesCrossVerifiedVector() {
        assertEquals("VIxsF9H69HwTYUViT1AE8GnZUqdfFxJ9f1TTdjx9EoY=",
            WebhookSigner.dingtalkSignRaw(1712345678000L, "SECtest1234567890abcdef"));
    }

    // ==================== 方向性：两家必须不同 ====================

    /**
     * 这两家的算法只差 HMAC 的参数位置，所以"复制粘贴另一个平台的实现"是本功能最可能的 bug。
     *
     * <p>本用例用<b>完全相同</b>的 secret 与待签名串，断言两个构造产生不同结果——
     * 只要有人把 key/message 写反，这条立刻失败。</p>
     */
    @Test
    void theTwoConstructionsMustDiffer() {
        String secret = "demo";
        long ts = 1599360473L;
        String stringToSign = ts + "\n" + secret;

        String feishu = WebhookSigner.feishuSign(ts, secret);
        // 常规构造 = HMAC(key=secret, msg=stringToSign)，正是钉钉的做法
        String conventional = conventionalHmac(secret, stringToSign);

        assertNotEquals(feishu, conventional,
            "feishu uses the INVERTED construction (key=stringToSign, msg=EMPTY); "
                + "if these are equal, someone has swapped the HMAC arguments");
        // 而钉钉用的就是常规构造
        assertEquals(conventional, WebhookSigner.dingtalkSignRaw(ts, secret));
    }

    /** 反证：即使时间戳换成"秒 vs 毫秒"之外的同一数值，两个构造也永不相等。 */
    @Test
    void swappingArgumentOrderNeverCoincides() {
        String secret = "SECtest1234567890abcdef";
        long ts = 1712345678000L;
        assertNotEquals(
            WebhookSigner.feishuSign(ts, secret),
            WebhookSigner.dingtalkSignRaw(ts, secret),
            "even with identical inputs the two platforms must produce different signatures");
    }

    // ==================== URL 编码 ====================

    /**
     * 钉钉的签名要拼进 URL query，Base64 的 {@code +} 不转义会被服务端解码成空格。
     *
     * <p>官方示例的签名里就有两个 {@code +}，正好用来验证。</p>
     */
    @Test
    void dingtalkUrlEncodingEscapesBase64Specials() {
        String encoded = WebhookSigner.dingtalkSignUrl(1577262236757L, "this is secret");
        assertFalse(encoded.contains("+"), "'+' must become %2B or the server decodes it as a space");
        assertFalse(encoded.contains("/"), "'/' must become %2F");
        assertTrue(encoded.endsWith("%3D"), "'=' padding must become %3D");
        assertEquals("hmPWwU%2B7lVdm3ZZz0r9tSfx0L4Q26jWOZr9%2BGs6EZQM%3D", encoded);
    }

    /**
     * 钉钉 webhook URL 天生带 {@code ?access_token=}，再拼一个 {@code ?} 会让第二个问号
     * 变成 token 值的一部分，服务端回 {@code errcode 300001 token is not exist}——
     * 一个和签名毫无关系的报错，极难定位。
     */
    @Test
    void dingtalkAppendSignUsesAmpersandWhenQueryAlreadyPresent() {
        String base = "https://oapi.dingtalk.com/robot/send?access_token=abc";
        String signed = WebhookSigner.dingtalkAppendSign(base, 1577262236757L, "this is secret");

        assertEquals(1, countChar(signed, '?'), "must not introduce a second '?': " + signed);
        assertTrue(signed.startsWith(base + "&timestamp=1577262236757&sign="),
            "signature must be appended with '&' when a query already exists: " + signed);
    }

    @Test
    void dingtalkAppendSignUsesQuestionMarkWhenNoQuery() {
        String signed = WebhookSigner.dingtalkAppendSign(
            "https://example.com/hook", 1577262236757L, "this is secret");
        assertTrue(signed.startsWith("https://example.com/hook?timestamp="), signed);
    }

    // ==================== 边界 ====================

    /**
     * 空白是载荷的一部分。
     *
     * <p>YAML 加载器若 trim，或用户在末尾留了换行，签名会完全不同，而平台的报错只有
     * "签名不匹配"四个字。这条用例把该行为钉死，避免将来有人"顺手"加个 trim。</p>
     */
    @Test
    void secretWhitespaceIsSignificant() {
        String trimmed = WebhookSigner.dingtalkSignRaw(1712345678000L, "SECtest1234567890abcdef");
        String padded = WebhookSigner.dingtalkSignRaw(1712345678000L, "  SECtest1234567890abcdef  ");
        String withNewline = WebhookSigner.dingtalkSignRaw(1712345678000L, "SECtest1234567890abcdef\n");
        assertNotEquals(trimmed, padded, "leading/trailing spaces must not be trimmed");
        assertNotEquals(trimmed, withNewline, "a trailing newline must not be trimmed");
    }

    /** 非 ASCII 密钥按 UTF-8 编码；{@code String.length()} 不能当字节数用。 */
    @Test
    void nonAsciiSecretUsesUtf8() {
        String secret = "密钥测试";
        assertEquals(4, secret.length());
        assertEquals(12, secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        // 与独立实现交叉验证过的值
        assertEquals("ZddCKLuVJL90yaHvxw6TzoFwJSsWMofg7OrlMWQfuEw=",
            WebhookSigner.feishuSign(1712345678L, secret));
        assertEquals("4xGWarvaw42cf865goMENdqxU4cL1ENyNwKsdiKCBlQ=",
            WebhookSigner.dingtalkSignRaw(1712345678000L, secret));
    }

    /**
     * 空密钥在两家上的行为<b>不对称</b>，这是个陷阱：
     * 钉钉直接抛（key 为空），飞书却会算出一个"看起来合法"的签名（key 是非空的拼接串）。
     * 所以判空必须在调用方做——见 {@link WebhookSigner#requiresSecret}。
     */
    @Test
    void emptySecretFailsAsymmetrically() {
        // 钉钉：SecretKeySpec 拒绝零长度 key
        boolean dingtalkThrew = false;
        try {
            WebhookSigner.dingtalkSignRaw(1700000000000L, "");
        } catch (RuntimeException e) {
            dingtalkThrew = true;
        }
        assertTrue(dingtalkThrew,
            "dingtalk must throw on an empty secret - the JCE rejects a zero-length key");

        // 飞书：不抛，产出一个会被服务端拒绝的"合法"签名
        String feishu = WebhookSigner.feishuSign(1700000000L, "");
        assertFalse(feishu.isEmpty(),
            "feishu accidentally succeeds on an empty secret, which is why the caller must guard");
    }

    @Test
    void requiresSecretCoversExactlyFeishuAndDingtalk() {
        assertTrue(WebhookSigner.requiresSecret("feishu"));
        assertTrue(WebhookSigner.requiresSecret("dingtalk"));
        assertFalse(WebhookSigner.requiresSecret("discord"));
        assertFalse(WebhookSigner.requiresSecret("onebot"));
        assertFalse(WebhookSigner.requiresSecret("wecom"));
        assertFalse(WebhookSigner.requiresSecret(null));
    }

    // ==================== 工具 ====================

    /** 常规构造（key=secret, msg=stringToSign），用于反证飞书用的一定不是它。 */
    private static String conventionalHmac(String secret, String stringToSign) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.Base64.getEncoder().encodeToString(
                mac.doFinal(stringToSign.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long countChar(String s, char c) {
        return s.chars().filter(ch -> ch == c).count();
    }
}
