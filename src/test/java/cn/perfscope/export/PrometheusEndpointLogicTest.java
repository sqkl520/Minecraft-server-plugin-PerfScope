/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.export;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.support.TestObjects;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PrometheusEndpoint} 中<b>不需要真实 socket</b> 的那部分逻辑。
 *
 * <p>端到端（真起 HttpServer、走 TCP、验证 200/HEAD/405 与抓取文本）由
 * {@code PrometheusEndpointHttpTest} 负责。本类只锁四块纯逻辑：</p>
 *
 * <ol>
 *   <li>{@code constantTimeEquals} —— Bearer token 的比较。这是本类里唯一与安全沾边的代码，
 *       也是最容易"看起来对、其实退化成了 {@code String.equals}"的一处。</li>
 *   <li>{@code isLoopback} —— 决定启动时是否打"已对非回环地址开放且无鉴权"的那条 warning。
 *       判错的后果是<b>静默对外暴露</b>，所以它必须对边界值（{@code 0.0.0.0}、带空格、
 *       {@code 127.0.0.2}）有明确态度。</li>
 *   <li>{@code authorized} —— 未配置 token 即放行、配置了就定长比较，两条分支都会真正决定
 *       谁能读到指标。用手写 {@link HttpExchange} stub 驱动，不起端口。</li>
 *   <li>{@code daemonFactory} —— 端点线程必须 daemon，否则 {@code stop()} 之后 JVM 不退出。</li>
 * </ol>
 *
 * <h2>为什么 {@code authorized} 值得单独测</h2>
 * <p>它的三条早退（{@code cfg == null} / {@code token 空白} / {@code header == null}）里，
 * 任意一条写错方向都是"要么全员放行、要么全员拒绝"。前者的表现是测试全绿、生产裸奔；
 * 后者的表现是 Grafana 一片红。两种都不该靠读代码保证。</p>
 *
 * <h2>stub 的边界</h2>
 * <p>{@link HttpExchange} 是抽象类，17 个抽象方法里 {@code authorized} 只用到
 * {@code getRequestHeaders()}，{@code handle} 的早退分支额外用到
 * {@code getRequestURI()} / {@code getRequestMethod()} / {@code getResponseHeaders()} /
 * {@code sendResponseHeaders} / {@code getResponseBody} / {@code close}。其余一律抛
 * {@link UnsupportedOperationException}，这样"测试意外走到未预期的路径"会立刻变红，
 * 而不是静默拿到一个 {@code null} 继续跑。</p>
 *
 * <h2>本类<b>测不到</b>的东西（已知缺口，不是遗漏）</h2>
 * <p>"定长"这个性质本身没法用单测验证。实测：把 {@code constantTimeEquals} 改成</p>
 * <pre>{@code
 * for (int i = 0; i < x.length; i++) { if (x[i] != y[i]) return false; }
 * return true;
 * }</pre>
 * <p>—— 语义完全等价、但一旦首个字节不同就返回，从头破坏了防计时侧信道的初衷 ——
 * <b>本类 31 个用例全绿</b>（已用变异实测确认）。要真正锁住"不短路"，只能靠
 * 对字节码的指令计数／{@code jmh} 级计时统计，两者都不适合放进常规 {@code mvn test}：
 * 计时断言在 CI 上本身就是 flaky 源。因此这条属性目前只由代码评审与注释保证。</p>
 *
 * <p>同理，{@code authorized} 的返回快慢、{@code start()} 的真实绑定失败路径、
 * {@code handle} 的 200/HEAD 分支（要读 {@link cn.perfscope.metrics.MetricsCollector}
 * 与插件描述）都不在本类范围内。</p>
 */
class PrometheusEndpointLogicTest {

    // ==================== 夹具 ====================

    /** 只填了 {@code prometheus_authToken} 的配置；{@code token == null} 时把整个 config 置空。 */
    private static PrometheusEndpoint endpoint(String token) {
        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, plugin, "logger", Logger.getLogger("pscope-prom-logic"));
        ConfigManager cm = new ConfigManager(plugin);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.prometheus_authToken = token;
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);
        // 构造器只赋 plugin 字段，不碰 Bukkit（见 TestObjects 约束二：这里不需要 allocateInstance）。
        return new PrometheusEndpoint(plugin);
    }

    /** {@link ConfigManager#get()} 返回 {@code null} 的形态（load() 从未成功时的真实状态）。 */
    private static PrometheusEndpoint endpointWithoutPublishedConfig() {
        PerfScope plugin = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, plugin, "logger", Logger.getLogger("pscope-prom-logic"));
        ConfigManager cm = new ConfigManager(plugin);
        TestObjects.setField(ConfigManager.class, cm, "config", null);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);
        return new PrometheusEndpoint(plugin);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return (Boolean) TestObjects.invoke(PrometheusEndpoint.class, "constantTimeEquals",
            new Class<?>[] {String.class, String.class}, a, b);
    }

    private static boolean isLoopback(String bind) {
        return (Boolean) TestObjects.invoke(PrometheusEndpoint.class, "isLoopback",
            new Class<?>[] {String.class}, bind);
    }

    private static ThreadFactory daemonFactory() {
        return (ThreadFactory) TestObjects.invoke(PrometheusEndpoint.class, "daemonFactory",
            new Class<?>[0]);
    }

    private static boolean authorized(PrometheusEndpoint ep, HttpExchange ex) {
        return (Boolean) TestObjects.invoke(ep, "authorized",
            new Class<?>[] {HttpExchange.class}, ex);
    }

    /** 发一个请求：{@code path} 用于 404/405 分支，{@code authHeader} 为 null 表示不带该头。 */
    private static StubExchange request(String method, String path, String authHeader) {
        StubExchange ex = new StubExchange(method, path);
        if (authHeader != null) {
            ex.getRequestHeaders().set("Authorization", authHeader);
        }
        return ex;
    }

    private static void handle(PrometheusEndpoint ep, StubExchange ex, String contextPath) {
        TestObjects.invoke(ep, "handle",
            new Class<?>[] {HttpExchange.class, String.class}, ex, contextPath);
    }

    // ==================== 1. constantTimeEquals ====================

    @Test
    void identicalStringsAreEqual() {
        assertTrue(constantTimeEquals("", ""));
        assertTrue(constantTimeEquals("abc", "abc"));
        assertTrue(constantTimeEquals("Bearer s3cr3t-token", "Bearer s3cr3t-token"));
        // 长串：走满整个 XOR 循环而不是被前几个字节劝退
        String longToken = "Bearer " + "k".repeat(4096);
        assertTrue(constantTimeEquals(longToken, longToken));
    }

    @Test
    void sameLengthDifferentContentIsNotEqual() {
        assertFalse(constantTimeEquals("abc", "abd"), "末位不同");
        assertFalse(constantTimeEquals("abc", "xbc"), "首位不同");
        assertFalse(constantTimeEquals("abc", "axc"), "中间不同");
        assertFalse(constantTimeEquals("Bearer abc", "Bearer abcd"), "长度不同，另有专门用例");
        assertFalse(constantTimeEquals("Abc", "abc"), "大小写是不同字节");
    }

    @Test
    void differentLengthsAreNotEqual() {
        // 前缀关系是最容易写成"逐个比较到较短的末尾就返回 true"的形状
        assertFalse(constantTimeEquals("abc", "abcd"));
        assertFalse(constantTimeEquals("abcd", "abc"));
        assertFalse(constantTimeEquals("", "a"));
        assertFalse(constantTimeEquals("a", ""));
        assertFalse(constantTimeEquals("Bearer abc", "Bearer abc "));
    }

    @Test
    void emptyStringsAreEqualOnlyToEachOther() {
        assertTrue(constantTimeEquals("", ""));
        assertFalse(constantTimeEquals("", " "));
        assertFalse(constantTimeEquals("", "a"));
    }

    @Test
    void multibyteUtf8SameTextIsEqual() {
        assertTrue(constantTimeEquals("中文令牌", "中文令牌"));
        assertTrue(constantTimeEquals("😀", "😀"), "emoji 😀 四字节");
        assertTrue(constantTimeEquals("Bearer 密钥-🔑", "Bearer 密钥-🔑"));
    }

    /**
     * 长度检查是<b>字节长度</b>而不是字符长度——这是与 {@code String.equals} 最关键的分歧点。
     *
     * <p>{@code "a"} 与 {@code "中"} 字符数相同（都是 1），但 UTF-8 字节数是 1 vs 3。
     * 一个用 {@code a.length() == b.length()} 做长度门的实现会在这里走进逐字节循环，
     * 随后 {@code x[i]} 越界（{@code "中"} 更长的方向）或误判。本用例要求 {@code false}。</p>
     */
    @Test
    void byteLengthNotCharLengthDrivesTheLengthGate() {
        assertFalse(constantTimeEquals("a", "中"));
        assertFalse(constantTimeEquals("中", "a"));
        assertFalse(constantTimeEquals("aa", "中"), "2 字符/2 字节 vs 1 字符/3 字节");
        assertFalse(constantTimeEquals("中中", "ab"), "6 字节 vs 2 字节");
        assertFalse(constantTimeEquals("😀", "ab"), "4 字节 vs 2 字节");
        // 字节长度相同、字符数不同：必须仍按内容判否（"é" 2 字节 1 字符 vs "aa" 2 字节 2 字符）
        assertFalse(constantTimeEquals("é", "aa"));
    }

    /**
     * 特征化（characterization）：比较对象是<b>编码后的字节</b>，不是字符。
     *
     * <p>{@code "\uD800"} 是孤立高代理，UTF-8 编码器会把它替换成 {@code '?'}（0x3F），
     * 因此它与 {@code "?"} 的字节串完全相同，于是这里返回 {@code true}——两个
     * <b>Java 层面不相等</b>的字符串被判为相等。</p>
     *
     * <p>这个用例存在的两个理由：</p>
     * <ol>
     *   <li>它把"实现是按字节比，而不是委托 {@code String.equals}"这件事钉死；
     *       删掉它，把实现换成 {@code a.equals(b)} 或 {@code a.equals(b) && 长度检查}
     *       都不会被本类任何其他用例发现（对合法字符串两者语义等价）。</li>
     *   <li>它是真实的语义边界：token 来自 YAML，孤立代理在 YAML 里几乎不会出现，
     *       故实际风险极低，但"两个不等字符串比较相等"必须被记录而不是被假设不存在。</li>
     * </ol>
     */
    @Test
    void comparisonIsOnEncodedBytesNotOnChars() {
        assertEquals("?", new String("\uD800".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
            "前置条件：孤立代理经 UTF-8 编码后确实变成 '?'");
        assertNotEquals("\uD800", "?", "前置条件：两串在 Java 层面并不相等");
        assertTrue(constantTimeEquals("\uD800", "?"), "字节相同 => 判等（记录该语义）");
        assertTrue(constantTimeEquals("?", "\uD800"));
    }

    /**
     * 累积必须用按位或，<b>不能</b>用加法。
     *
     * <p>{@code diff += x[i] ^ y[i]} 看起来"只要非零就非零"，但 {@code byte} 在 Java 里是
     * 有符号的：逐字节 XOR 的结果经符号扩展后落在 {@code -128..127}，一组非零项可以
     * 正负相消、和恰好为 0，于是两个<b>不同</b>的串被判相等——一个静默的鉴权绕过。</p>
     *
     * <p>下列两串是穷举出来的最小见证（各 4 字节，长度相同）：</p>
     * <pre>
     * "aaÀ" -> [97, 97, -61, -128]
     * "a一" -> [97, -28, -72, -128]
     * 逐字节 XOR（含符号扩展）：0, -123, 123, 0  ->  和 = 0，按位或 = -1
     * </pre>
     *
     * <p>没有这条用例，把 {@code |=} 改成 {@code +=} 时整个测试类<b>依然全绿</b>
     * （已实测：这是本类唯一能杀掉该变异体的用例）。</p>
     */
    @Test
    void accumulationMustNotBeArithmetic() {
        assertNotEquals("aaÀ", "a一", "前置条件：两串不相等");
        assertFalse(constantTimeEquals("aaÀ", "a一"),
            "按位或累积：任一字节不同即非零。若实现改用 +=，符号扩展会让非零项相消得到 0");
        assertFalse(constantTimeEquals("a一", "aaÀ"));
    }

    /**
     * 空指针不被容忍：{@code constantTimeEquals} 自身不做 null 处理。
     *
     * <p>这不是缺陷——两个调用点都在其之前判过空（{@code authorized} 对 header 与
     * {@code expected} 都非 null）。但把这一点锁住，任何"给它加 null 宽容"的改动
     * 都会显式失败，而不是悄悄让"null == null"变成放行。</p>
     */
    @Test
    void nullIsNotTolerated() {
        assertThrows(NullPointerException.class, () -> constantTimeEquals(null, "a"));
        assertThrows(NullPointerException.class, () -> constantTimeEquals("a", null));
    }

    // ==================== 2. isLoopback ====================

    @Test
    void ipv4AndIpv6LoopbackLiteralsAreRecognized() {
        assertTrue(isLoopback("127.0.0.1"));
        assertTrue(isLoopback("::1"));
    }

    @Test
    void localhostIsCaseInsensitive() {
        assertTrue(isLoopback("localhost"));
        assertTrue(isLoopback("LocalHost"));
        assertTrue(isLoopback("LOCALHOST"));
        assertTrue(isLoopback("lOcAlHoSt"));
    }

    @Test
    void nonLoopbackBindsAreRejected() {
        assertFalse(isLoopback("0.0.0.0"), "通配地址：监听全部网卡，必须被判为对外暴露");
        assertFalse(isLoopback("192.168.1.1"));
        assertFalse(isLoopback("10.0.0.5"));
        assertFalse(isLoopback("example.com"));
        assertFalse(isLoopback("::"));
        assertFalse(isLoopback("127.0.0.1.nip.io"), "解析到回环但不是字面量");
    }

    /**
     * 已知局限，<b>刻意不修</b>：整个 {@code 127.0.0.0/8} 都是回环网段，但实现只认
     * {@code 127.0.0.1}；{@code 127.0.0.2} 会被判非回环，于是启动时多打一条"已对外暴露"
     * 的 warning。
     *
     * <p>方向是安全的（误报而非漏报），代价只是一条日志。本用例把当前行为钉住，
     * 免得日后有人"顺手"改成前缀匹配却没同步调整 warning 的措辞——
     * 若哪天确实要支持整段回环，改实现的同时把这条用例改成 {@code assertTrue}。</p>
     */
    @Test
    void otherAddressesInLoopbackRangeAreNotRecognized() {
        assertFalse(isLoopback("127.0.0.2"));
        assertFalse(isLoopback("127.1.2.3"));
    }

    @Test
    void nullEmptyAndBlankAreNotLoopback() {
        assertFalse(isLoopback(null), "equals 的调用方是非 null 字面量，故 null 安全返回 false");
        assertFalse(isLoopback(""));
        assertFalse(isLoopback(" "));
    }

    /** 实现不 trim：{@code start()} 在调用前已 trim，这里锁住"自身不兜底"的事实。 */
    @Test
    void whitespaceIsNotTrimmed() {
        assertFalse(isLoopback(" 127.0.0.1"));
        assertFalse(isLoopback("127.0.0.1 "));
        assertFalse(isLoopback(" 127.0.0.1 "));
        assertFalse(isLoopback(" localhost"));
        assertFalse(isLoopback("\t::1"));
    }

    // ==================== 3. authorized ====================

    @Test
    void blankOrMissingTokenAllowsEveryone() {
        // null / 空串 / 纯空白：三种都等于"未配置鉴权"，无 header 也应放行
        for (String token : new String[] {null, "", "   ", "\t", "\n  \n"}) {
            PrometheusEndpoint ep = endpoint(token);
            assertTrue(authorized(ep, request("GET", "/metrics", null)),
                "token=[" + token + "] 无 Authorization 头应放行");
            assertTrue(authorized(ep, request("GET", "/metrics", "Bearer anything")),
                "token=[" + token + "] 任意头应放行");
            assertTrue(authorized(ep, request("GET", "/metrics", "garbage")),
                "token=[" + token + "] 非 Bearer 头也应放行");
        }
    }

    /** {@code cfg == null}（load() 从未成功）走的是 {@code token == null} 分支 => 不鉴权。 */
    @Test
    void unpublishedConfigMeansNoAuth() {
        PrometheusEndpoint ep = endpointWithoutPublishedConfig();
        assertTrue(authorized(ep, request("GET", "/metrics", null)));
    }

    @Test
    void missingHeaderIsRejectedWhenTokenConfigured() {
        PrometheusEndpoint ep = endpoint("abc");
        assertFalse(authorized(ep, request("GET", "/metrics", null)));
        // 有别的头但没有 Authorization
        StubExchange ex = request("GET", "/metrics", null);
        ex.getRequestHeaders().set("User-Agent", "Prometheus/2.51.0");
        assertFalse(authorized(ep, ex));
    }

    @Test
    void correctBearerHeaderIsAccepted() {
        PrometheusEndpoint ep = endpoint("abc");
        assertTrue(authorized(ep, request("GET", "/metrics", "Bearer abc")));
    }

    @Test
    void wrongTokenIsRejected() {
        PrometheusEndpoint ep = endpoint("abc");
        assertFalse(authorized(ep, request("GET", "/metrics", "Bearer abd")));
        assertFalse(authorized(ep, request("GET", "/metrics", "Bearer abcd")));
        assertFalse(authorized(ep, request("GET", "/metrics", "Bearer ")));
        assertFalse(authorized(ep, request("GET", "/metrics", "Bearer ABC")), "token 区分大小写");
        assertFalse(authorized(ep, request("GET", "/metrics", "Bearer  abc")), "多一个空格即不匹配");
    }

    @Test
    void missingBearerPrefixIsRejected() {
        PrometheusEndpoint ep = endpoint("abc");
        assertFalse(authorized(ep, request("GET", "/metrics", "abc")), "裸 token 不放行");
        assertFalse(authorized(ep, request("GET", "/metrics", "Basic abc")));
        assertFalse(authorized(ep, request("GET", "/metrics", "bearer abc")), "scheme 大小写敏感");
        assertFalse(authorized(ep, request("GET", "/metrics", "Bearer")), "只有 scheme 没有值");
        assertFalse(authorized(ep, request("GET", "/metrics", "Bearer ")), "scheme 后只有空格");
    }

    /** 配置值两端空白会被 {@code token.trim()} 吃掉：{@code "  abc  "} 等价于 {@code "abc"}。 */
    @Test
    void configuredTokenIsTrimmed() {
        PrometheusEndpoint ep = endpoint("  abc  ");
        assertTrue(authorized(ep, request("GET", "/metrics", "Bearer abc")));
        assertFalse(authorized(ep, request("GET", "/metrics", "Bearer   abc  ")),
            "期望串是 \"Bearer abc\"，请求头的空格只有两端会被 trim，中间的不行");
    }

    /**
     * 请求头两端空白会被 {@code header.trim()} 吃掉（代理/手写客户端常见形态）。
     *
     * <p>不用 {@code \n}／{@code \r} 做用例：{@link Headers#set} 自己会拒绝 LF
     * （"Illegal LF found in header"），JDK 的 HTTP 解析层也不允许裸换行进入头值，
     * 那种形态到不了这里。空格与制表符是这个夹具能表达的全部。</p>
     */
    @Test
    void headerIsTrimmed() {
        PrometheusEndpoint ep = endpoint("abc");
        assertTrue(authorized(ep, request("GET", "/metrics", "  Bearer abc  ")));
        assertTrue(authorized(ep, request("GET", "/metrics", "\tBearer abc")));
        assertTrue(authorized(ep, request("GET", "/metrics", "Bearer abc\t")));
    }

    /** 头名大小写：{@link Headers} 是大小写不敏感的 map，写 {@code authorization} 也必须被读到。 */
    @Test
    void headerNameIsCaseInsensitive() {
        PrometheusEndpoint ep = endpoint("abc");
        StubExchange ex = request("GET", "/metrics", null);
        ex.getRequestHeaders().set("authorization", "Bearer abc");
        assertTrue(authorized(ep, ex));
    }

    // ---------- authorized 与 handle 的接线（401 分支不触达渲染） ----------

    @Test
    void unauthorizedRequestGets401AndNoTokenEcho() {
        PrometheusEndpoint ep = endpoint("abc");
        StubExchange ex = request("GET", "/metrics", "Bearer wrong");
        handle(ep, ex, "/metrics");

        assertEquals(401, ex.statusCode());
        assertEquals("Bearer", ex.getResponseHeaders().getFirst("WWW-Authenticate"));
        assertEquals("unauthorized\n", ex.bodyText());
        assertEquals(ex.bodyText().getBytes(StandardCharsets.UTF_8).length, ex.declaredLength(),
            "Content-Length 必须与实际写出字节数一致");
        assertFalse(ex.bodyText().contains("wrong"), "401 响应体绝不回显收到的凭据");
        assertTrue(ex.isClosed());
    }

    /** 授权通过后的 200 路径要读 MetricsCollector/描述，属端到端测试的范围；这里只锁 401 之前的早退。 */
    @Test
    void wrongPathGets404BeforeAuthRuns() {
        PrometheusEndpoint ep = endpoint("abc");
        StubExchange ex = request("GET", "/", null);
        handle(ep, ex, "/metrics");
        assertEquals(404, ex.statusCode());
        assertEquals("not found\n", ex.bodyText());
    }

    @Test
    void nonGetOrHeadGets405WithAllowHeader() {
        PrometheusEndpoint ep = endpoint("abc");
        for (String method : new String[] {"POST", "PUT", "DELETE", "OPTIONS"}) {
            StubExchange ex = request(method, "/metrics", "Bearer abc");
            handle(ep, ex, "/metrics");
            assertEquals(405, ex.statusCode(), method);
            assertEquals("GET, HEAD", ex.getResponseHeaders().getFirst("Allow"), method);
            assertEquals("method not allowed\n", ex.bodyText(), method);
        }
    }

    // ==================== 4. daemonFactory ====================

    @Test
    void createdThreadsAreDaemonAndPrefixed() {
        Thread t = daemonFactory().newThread(() -> { });
        assertTrue(t.isDaemon(), "非守护线程会让 stop() 之后 JVM 无法退出");
        assertTrue(t.getName().startsWith("perfscope-metrics-"), "实际名字：" + t.getName());
    }

    @Test
    void threadNamesAreSequential() {
        ThreadFactory f = daemonFactory();
        Thread t1 = f.newThread(() -> { });
        Thread t2 = f.newThread(() -> { });
        Thread t3 = f.newThread(() -> { });
        assertEquals("perfscope-metrics-1", t1.getName());
        assertEquals("perfscope-metrics-2", t2.getName());
        assertEquals("perfscope-metrics-3", t3.getName());
        assertNotEquals(t1.getName(), t2.getName(), "jstack 里必须能区分两个 worker");
    }

    /** 工厂必须把传入的 Runnable 原样交给线程执行（不是丢弃、不是包错对象）。 */
    @Test
    void factoryWrapsTheSuppliedRunnable() throws InterruptedException {
        AtomicBoolean ran = new AtomicBoolean();
        Thread t = daemonFactory().newThread(() -> ran.set(true));
        t.start();
        t.join(5_000L);
        assertTrue(ran.get(), "线程没有执行传入的 Runnable");
        assertFalse(t.isAlive());
    }

    /** 每个工厂各自从 1 开始编号（seq 是工厂级、不是全局）。记录该行为，不视为约束。 */
    @Test
    void eachFactoryNumbersFromOne() {
        ThreadFactory a = daemonFactory();
        ThreadFactory b = daemonFactory();
        assertEquals("perfscope-metrics-1", a.newThread(() -> { }).getName());
        assertEquals("perfscope-metrics-1", b.newThread(() -> { }).getName());
    }

    // ==================== stub ====================

    /**
     * 手写的 {@link HttpExchange}：只实现被测代码真正会碰的 7 个方法，其余抛异常。
     *
     * <p>抛异常而不是返回默认值，是为了让"某条路径意外被走到"立刻以带栈的失败暴露；
     * 返回 {@code null} 则会被 {@code equals}/判空逻辑吞下去，变成一个看似正常的错误答案。</p>
     */
    private static final class StubExchange extends HttpExchange {

        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final URI uri;
        private final String method;

        private int status = -1;
        private long declaredLength = -1L;
        private boolean closed;

        StubExchange(String method, String path) {
            this.method = method;
            this.uri = URI.create(path);
        }

        int statusCode() {
            return status;
        }

        long declaredLength() {
            return declaredLength;
        }

        boolean isClosed() {
            return closed;
        }

        String bodyText() {
            return new String(responseBody.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public void sendResponseHeaders(int code, long length) {
            this.status = code;
            this.declaredLength = length;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void close() {
            closed = true;
        }

        // ---------- 被测代码不会走到这里 ----------

        @Override
        public HttpContext getHttpContext() {
            throw new UnsupportedOperationException("stub: getHttpContext");
        }

        @Override
        public InputStream getRequestBody() {
            throw new UnsupportedOperationException("stub: getRequestBody");
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            throw new UnsupportedOperationException("stub: getRemoteAddress");
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            throw new UnsupportedOperationException("stub: getLocalAddress");
        }

        @Override
        public int getResponseCode() {
            return status;
        }

        @Override
        public String getProtocol() {
            throw new UnsupportedOperationException("stub: getProtocol");
        }

        @Override
        public Object getAttribute(String name) {
            throw new UnsupportedOperationException("stub: getAttribute");
        }

        @Override
        public void setAttribute(String name, Object value) {
            throw new UnsupportedOperationException("stub: setAttribute");
        }

        @Override
        public void setStreams(InputStream in, OutputStream out) {
            throw new UnsupportedOperationException("stub: setStreams");
        }

        @Override
        public HttpPrincipal getPrincipal() {
            throw new UnsupportedOperationException("stub: getPrincipal");
        }
    }
}
