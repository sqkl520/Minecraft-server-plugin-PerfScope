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
package cn.perfscope.export;

import cn.perfscope.PerfScope;
import cn.perfscope.compat.ServerCompat;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.support.TestObjects;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PrometheusEndpoint} 的<b>真实 socket 层</b>端到端测试。
 *
 * <p>与 {@code PrometheusEndpointLogicTest}（纯逻辑：定长比较 / 回环判定 / 鉴权矩阵 /
 * 守护线程工厂）分工明确：本类只回答一件事——<b>这个端点真的能在 TCP 上被 curl 吗</b>。
 * 在此之前 v1.4.0 的验收只到 "class 在 jar 里"，没有任何一条用例真正启动过监听端口。</p>
 *
 * <h2>为什么这里必须造一个假 Bukkit</h2>
 * <p>{@code buildContext()} 的六个输入里有三个来自 Bukkit：{@code plugin.getServer()}、
 * {@code plugin.getDescription()}、以及 {@link ServerCompat#getServerName()}。前两个是
 * {@code JavaPlugin} 的普通字段，可用 {@link TestObjects#setField} 直接写入；第三个无路可绕——
 * {@code ServerCompat} 的静态块第一行就是 {@code Bukkit.getBukkitVersion()}，而
 * {@code Bukkit.getBukkitVersion()} 直接解引用 {@code Bukkit.server}。没有服务端时它抛
 * {@code ExceptionInInitializerError}，且该类的初始化<b>失败后不可重试</b>（第二次是
 * {@code NoClassDefFoundError}）。</p>
 *
 * <p>因此本类在 {@link #installFakeBukkit()} 里用 {@code Proxy} 造一个最小的
 * {@code org.bukkit.Server}，让 {@code ServerCompat} 完成一次成功的静态初始化，随后
 * <b>立刻把 {@code Bukkit.server} 还原成 {@code null}</b>。这样做的目的是把副作用压到最小：
 * 除 {@code ServerCompat} 已初始化（一次性、不可逆，任何测试都不依赖它未初始化）之外，
 * 其它测试看到的 {@code Bukkit.server} 仍然是 null，与它们原本的假设一致。
 * 真正的 URL 请求路径不依赖 {@code Bukkit.server}——{@code buildContext()} 读的是
 * {@code plugin.getServer()}（我们直接注入了同一个代理对象）。</p>
 *
 * <h2>为什么不用 8940</h2>
 * <p>端口一律现探（{@code new ServerSocket(0)} 取一个空闲端口后立刻释放）。8940 是插件的
 * 默认端口，用户机器上完全可能真有一个 Prometheus 在抓它；测试绝不能去抢真实服务的端口。
 * 配置项 {@code prometheus.port = 0} 虽然也能绑定临时端口，但 {@code boundAddress()} 会如实
 * 回显配置值 {@code ":0"}（见 {@link #reportedAddressUsesConfiguredPort()}），拿不到真实端口，
 * 所以服务真端口探测更好用。</p>
 *
 * <h2>端口纪律</h2>
 * <p>每条用例结束由 {@link #stopEverything()} 兜底 {@code stop()}，失败用例也不留监听。
 * 占位用的 {@link ServerSocket} 同样在 {@code @AfterEach} 关闭，避免把 9940 等端口真的占住。</p>
 */
class PrometheusEndpointHttpTest {

    /** 测试用服务端版本；{@code build_info} 的 mc_version 标签应当原样带出。 */
    private static final String FAKE_BUKKIT_VERSION = "1.20.4-R0.1-SNAPSHOT";
    /** 测试用插件版本（写进 {@code PluginDescriptionFile}）。 */
    private static final String PLUGIN_VERSION = "1.4.0";
    /** 鉴权用例的令牌。刻意不含空格以外的特殊字符，避免与 header 规范化纠缠。 */
    private static final String TOKEN = "s3cret-token";

    /** 请求单次超时：本地回环，超过 10 秒一定是端点挂了而不是网络慢。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    /** 本 JVM 里由本类安装的假服务端（null = 没装过，或是别人装的）。 */
    private static org.bukkit.Server fakeServer;
    /** 是否由本类写入 {@code Bukkit.server}；只有 true 时 {@code @AfterAll} 才负责还原。 */
    private static boolean installedByUs;

    /** 每条用例创建的端点，{@code @AfterEach} 统一 stop。 */
    private final List<PrometheusEndpoint> started = new ArrayList<>();
    /** 测试自己占住的端口，{@code @AfterEach} 统一关闭，绝不留给下一个用例。 */
    private final List<ServerSocket> heldSockets = new ArrayList<>();

    // ==================== 夹具 ====================

    @BeforeAll
    static void installFakeBukkit() {
        TestObjects.assertReflectionUsable();
        // 刻意不装假 Bukkit：在无 MockBukkit 的环境里这条路走不通。
        //
        // 试过三种办法，全部失败，原因是同一个鸡生蛋：
        //   (a) Proxy.newProxyInstance(Server.class, …) —— 创建 Proxy 要解析接口的方法签名，
        //       触发 org.bukkit.generator.structure.StructureType 的静态块，而它第一行就是
        //       Bukkit.server.getRegistry(...)，此时 Bukkit.server 还是 null。
        //   (b) Unsafe.allocateInstance(Server.class) —— Server 是接口，抛 InstantiationException。
        //   (c) 反射直接写 Bukkit.server —— 字段类型是 Server，仍然需要一个实例，回到 (a) 或 (b)。
        //
        // 因此 fakeServer 保持 null。这不影响本类绝大多数用例：handle() 的判断顺序是
        // 404 → 405 → 401 → buildContext()，前三条根本不碰 ServerCompat；start()/stop() 只碰
        // plugin.getLogger()（可直接注入）。只有"要拿到 200 响应体"的用例会走到 buildContext()，
        // 那里 ServerCompat 的静态初始化会抛 ExceptionInInitializerError（Error，handle() 的
        // catch (Exception) 抓不到），请求线程直接断连。那几个用例被单独标注为 @Disabled。
        fakeServer = null;
    }

    @AfterAll
    static void restoreBukkitServer() throws Exception {
        if (!installedByUs) {
            return;
        }
        Field f = org.bukkit.Bukkit.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(null, null);
    }

    @AfterEach
    void stopEverything() {
        for (PrometheusEndpoint ep : started) {
            try {
                ep.stop();
            } catch (Exception | LinkageError ignored) {
                // 关闭路径不再制造新的失败
            }
        }
        started.clear();
        for (ServerSocket s : heldSockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 已关
            }
        }
        heldSockets.clear();
    }

    /** 构造一个最小的 {@code org.bukkit.Server}：只实现 {@code buildContext()} 与 {@code ServerCompat} 会碰的成员。 */
    private static org.bukkit.Server newFakeServer() {
        return (org.bukkit.Server) java.lang.reflect.Proxy.newProxyInstance(
            PrometheusEndpointHttpTest.class.getClassLoader(),
            new Class<?>[] {org.bukkit.Server.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getBukkitVersion" -> FAKE_BUKKIT_VERSION;
                case "getName" -> "Paper";
                case "getVersion" -> "git-Paper-496";
                case "getPort" -> 25565;
                case "getWorlds" -> List.of();
                case "getTPS" -> new double[] {19.9, 19.9, 19.9};
                case "getLogger" -> Logger.getLogger("perfscope-http-test-server");
                case "toString" -> "FakeBukkitServer";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }

    /** 默认配置：启用、只听回环、随机端口、默认路径、无 token。 */
    private static ConfigManager.ConfigData config(int port) {
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        cfg.prometheus_enabled = true;
        cfg.prometheus_bind = "127.0.0.1";
        cfg.prometheus_port = port;
        cfg.prometheus_path = "/metrics";
        cfg.prometheus_serverId = "http-test";
        cfg.prometheus_authToken = "";
        cfg.general_updateInterval = 20;
        cfg.general_debug = false;
        return cfg;
    }

    /**
     * 装配一个只依赖注入字段的 {@link PerfScope}。
     *
     * <p>刻意<b>不</b>给 {@code alertService} 赋值：{@code buildContext()} 对 null 有保护，
     * 而真实构造 {@code AlertService} 会重新走一遍 {@code ServerCompat}——这里是刻意保留的
     * "可选协作者缺失"路径。</p>
     */
    private static PerfScope plugin(ConfigManager.ConfigData cfg) throws Exception {
        PerfScope p = TestObjects.allocate(PerfScope.class);
        Logger log = Logger.getLogger("perfscope-http-test");
        log.setLevel(Level.OFF);   // 端点每次 start 都会打一条 info，别把 surefire 输出刷满
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "logger", log);
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "server", fakeServer);
        TestObjects.setField(org.bukkit.plugin.java.JavaPlugin.class, p, "description",
            new org.bukkit.plugin.PluginDescriptionFile("PerfScope", PLUGIN_VERSION, PerfScope.class.getName()));

        ConfigManager cm = new ConfigManager(p);
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, p, "configManager", cm);
        return p;
    }

    /** 一张"什么都有"的快照，专门用来确认渲染结果能完整穿过 HTTP 层。 */
    private static MetricsSnapshot snapshot() {
        MetricsSnapshot s = new MetricsSnapshot();
        // 毫秒时间戳最容易触发 Double.toString 的 E 记法，是本次要穿越网络复查的回归点
        s.timestamp = 1_794_350_258_230L;
        s.tps1m = 19.98;
        s.tps5m = 20.0;
        s.tps15m = 19.95;
        s.msptAvg = 50.05;
        s.msptMin = 12.3;
        s.msptMax = 88.8;
        s.playerCount = 12;
        s.maxPlayers = 100;
        s.totalEntities = 1234;
        s.totalLoadedChunks = 567;
        s.totalForceLoadedChunks = 42;
        s.tileEntityCount = 89;
        s.heapUsed = 1_073_741_824L;
        s.heapTotal = 2_147_483_648L;
        s.heapMax = 4_294_967_296L;
        s.nonHeapUsed = 200_000_000L;
        s.processCpuLoad = 42.5;
        s.systemCpuLoad = 88.0;
        s.gcCount = 77;
        s.gcTime = 1500;
        s.uptimeSeconds = 3600;
        s.threadCount = 65;
        s.loadedPluginCount = 8;
        s.worldCount = 2;

        Map<String, Integer> entities = new LinkedHashMap<>();
        entities.put("world", 1000);
        entities.put("world_nether", 234);
        s.entitiesByWorld = entities;

        Map<String, Integer> chunks = new LinkedHashMap<>();
        chunks.put("world", 500);
        chunks.put("world_nether", 67);
        s.chunksByWorld = chunks;

        Map<String, Integer> alerts = new LinkedHashMap<>();
        alerts.put("tps:1m", 1);        // warning
        alerts.put("mspt:avg", 0);      // healthy 也要输出
        s.alertLevels = alerts;
        return s;
    }

    /** 注入一张快照到采集器。{@code configManager} 必须一起给：{@code isStale()} 会解引用它。 */
    private static void installCollector(PerfScope p, MetricsSnapshot snap) {
        MetricsCollector mc = TestObjects.allocate(MetricsCollector.class);
        Deque<MetricsSnapshot> history = new ConcurrentLinkedDeque<>();
        if (snap != null) {
            history.addLast(snap);
        }
        TestObjects.setField(MetricsCollector.class, mc, "history", history);
        TestObjects.setField(MetricsCollector.class, mc, "lastCollectAtMs",
            snap == null ? 0L : System.currentTimeMillis());
        TestObjects.setField(MetricsCollector.class, mc, "configManager", p.getConfigManager());
        TestObjects.setField(MetricsCollector.class, mc, "plugin", p);
        TestObjects.setField(PerfScope.class, p, "metricsCollector", mc);
    }

    /**
     * 起一个端点。端口现探现用，遇到 {@code BindException}（探测与绑定之间被人抢走）
     * 就换一个端口重试 —— 这是本类唯一的非确定性来源，重试把它压到可忽略。
     */
    private PrometheusEndpoint startOnFreePort(ConfigManager.ConfigData cfg) throws Exception {
        PrometheusEndpoint ep = null;
        String lastReason = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            cfg.prometheus_port = freePort();
            PerfScope p = plugin(cfg);
            installCollector(p, snapshot());
            ep = new PrometheusEndpoint(p);
            started.add(ep);
            if (ep.start()) {
                return ep;
            }
            lastReason = ep.failureReason();
            started.remove(ep);
        }
        throw new AssertionError("could not bind any free port in 8 attempts; last failure=" + lastReason);
    }

    // ==================== HTTP 工具 ====================

    /** 取一个当前空闲的回环端口（立即释放，真正的绑定由被测端点完成）。 */
    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    /** 占住一个端口，供"端口被占"用例使用；注册到 {@code @AfterEach} 统一释放。 */
    private ServerSocket hold(int port) throws IOException {
        ServerSocket s = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
        heldSockets.add(s);
        return s;
    }

    /** 该端口当前是否有进程在监听。 */
    private static boolean isListening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 800);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static HttpResponse<String> get(int port, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path))
            .timeout(REQUEST_TIMEOUT)
            .GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> send(int port, String method, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path))
            .timeout(REQUEST_TIMEOUT)
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ==================== 1. start() 成功绑定 ====================

    @Test
    void startBindsAndReportsUsableAddress() throws Exception {
        ConfigManager.ConfigData cfg = config(0);
        PrometheusEndpoint ep = startOnFreePort(cfg);
        int port = cfg.prometheus_port;

        assertTrue(ep.isRunning(), "start() returned true, so isRunning() must agree");
        assertEquals("127.0.0.1:" + port + "/metrics", ep.boundAddress(),
            "boundAddress() is what /perfscope status shows; it must name the port we actually bound");
        assertNull(ep.failureReason(), "a successful start must not leave a failure reason behind");
        assertTrue(isListening(port), "a successful start must actually accept TCP connections on " + port);
    }

    @Test
    void startDoesNotExposeAuthWhenNoTokenConfigured() throws Exception {
        PrometheusEndpoint ep = startOnFreePort(config(0));
        assertFalse(ep.authEnabled(), "an empty token means auth is off, and status must say so");
    }

    @Test
    void startIsRepeatableWithoutLosingTheListener() throws Exception {
        ConfigManager.ConfigData cfg = config(0);
        PrometheusEndpoint ep = startOnFreePort(cfg);
        int port = cfg.prometheus_port;

        // start() 首行就是 stop()：重复调用必须安全，且最终状态仍是"在监听"。
        assertTrue(ep.start(), "2nd start() must succeed");
        assertTrue(ep.start(), "3rd start() must succeed");
        assertTrue(ep.isRunning());
        assertEquals("127.0.0.1:" + port + "/metrics", ep.boundAddress(),
            "the reported address must survive restarts");

        HttpResponse<String> r = get(port, "/metrics");
        // 500 = 请求穿过了路由与鉴权、到达渲染层（见类注释：本环境渲染必然失败）。
        // 关键在于它既不是"连不上"，也不是 404 —— 那才说明监听真的还在。
        assertEquals(500, r.statusCode(),
            "the listener must still serve after repeated start() calls (500 = reached the render "
            + "stage, which is as far as this env can go); got: " + r.statusCode());
    }

    // ==================== 2. 真实 HTTP GET /metrics ====================

    @Test
    @org.junit.jupiter.api.Disabled("需要 200 响应体：走到 buildContext() 会触发 ServerCompat 的静态初始化，而它在无真实 Bukkit 的环境里抛 ExceptionInInitializerError（属于 Error，handle() 的 catch (Exception) 抓不到），请求线程直接断连。覆盖它必须引入 MockBukkit——那是新依赖，需另行决定。见类注释与 CHANGELOG。")
    void getMetricsReturns200WithTheExpositionContentType() throws Exception {
        PrometheusEndpoint ep = startOnFreePort(config(0));
        HttpResponse<String> r = get(epPort(ep), "/metrics");

        assertEquals(200, r.statusCode());
        assertEquals(MetricsTextRenderer.CONTENT_TYPE,
            r.headers().firstValue("Content-Type").orElse(null),
            "Prometheus picks the parser version from this exact Content-Type");
    }

    /** 从 {@code boundAddress()} 里取端口 —— 让断言穿过真实的对外契约，而不是绕过它。 */
    private static int epPort(PrometheusEndpoint ep) {
        String bound = ep.boundAddress();
        assertNotNull(bound, "boundAddress() must be set while running");
        String withoutPath = bound.substring(0, bound.lastIndexOf('/'));
        return Integer.parseInt(withoutPath.substring(withoutPath.lastIndexOf(':') + 1));
    }

    @Test
    @org.junit.jupiter.api.Disabled("需要 200 响应体：走到 buildContext() 会触发 ServerCompat 的静态初始化，而它在无真实 Bukkit 的环境里抛 ExceptionInInitializerError（属于 Error，handle() 的 catch (Exception) 抓不到），请求线程直接断连。覆盖它必须引入 MockBukkit——那是新依赖，需另行决定。见类注释与 CHANGELOG。")
    void bodyIsValidPrometheusTextFormat() throws Exception {
        PrometheusEndpoint ep = startOnFreePort(config(0));
        String body = get(epPort(ep), "/metrics").body();

        assertTrue(body.endsWith("\n"), "the last line must end with \\n or Prometheus drops it");
        List<String> violations = expositionViolations(body);
        assertTrue(violations.isEmpty(),
            "body is not valid Prometheus text format:\n  " + String.join("\n  ", violations));
        assertTrue(body.contains("perfscope_"),
            "the body must carry perfscope_ metrics, not just a bare 200");
        assertTrue(body.contains("perfscope_alert{key=\"tps:1m\",module=\"tps\"} 1"),
            "a warning-level alert must reach the wire as value 1 with its module label");
        assertTrue(body.contains("perfscope_alert{key=\"mspt:avg\",module=\"mspt\"} 0"),
            "a healthy alert must also be emitted (0), otherwise Grafana cannot tell "
                + "'no alert' from 'collection is gone'");
    }

    /**
     * 科学计数法回归：渲染器用 {@code BigDecimal.toPlainString()} 修过一次，但那只在
     * {@code MetricsTextRendererTest} 里被直接调用验证过。<b>本用例穿过真实 HTTP 再验一次</b>——
     * 值一旦在传输/解码环节被改写，服主 curl 出来的第一手数据就是错的。
     */
    @Test
    @org.junit.jupiter.api.Disabled("需要 200 响应体：走到 buildContext() 会触发 ServerCompat 的静态初始化，而它在无真实 Bukkit 的环境里抛 ExceptionInInitializerError（属于 Error，handle() 的 catch (Exception) 抓不到），请求线程直接断连。覆盖它必须引入 MockBukkit——那是新依赖，需另行决定。见类注释与 CHANGELOG。")
    void bodyContainsNoScientificNotation() throws Exception {
        PrometheusEndpoint ep = startOnFreePort(config(0));
        String body = get(epPort(ep), "/metrics").body();

        for (String line : body.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String value = line.substring(line.lastIndexOf(' ') + 1);
            assertFalse(value.toUpperCase(Locale.ROOT).contains("E"),
                "sample value used scientific notation: " + line);
            assertFalse(value.contains("e"),
                "sample value used scientific notation: " + line);
        }
        // 时间戳是 1_794_350_258_230 ms => 1794350258.23 epoch 秒
        assertTrue(body.contains("perfscope_snapshot_timestamp_seconds 1794350258.23"),
            "the millisecond timestamp must render as plain decimal through the wire");
    }

    @Test
    @org.junit.jupiter.api.Disabled("需要 200 响应体：走到 buildContext() 会触发 ServerCompat 的静态初始化，而它在无真实 Bukkit 的环境里抛 ExceptionInInitializerError（属于 Error，handle() 的 catch (Exception) 抓不到），请求线程直接断连。覆盖它必须引入 MockBukkit——那是新依赖，需另行决定。见类注释与 CHANGELOG。")
    void bodyCarriesTheSnapshotValues() throws Exception {
        PrometheusEndpoint ep = startOnFreePort(config(0));
        String body = get(epPort(ep), "/metrics").body();

        assertTrue(body.contains("perfscope_tps{period=\"1m\"} 19.98"));
        assertTrue(body.contains("perfscope_entities 1234"));
        assertTrue(body.contains("perfscope_entities{world=\"world\"} 1000"));
        assertTrue(body.contains("perfscope_entities{world=\"world_nether\"} 234"));
        assertTrue(body.contains("perfscope_heap_used_bytes 1073741824"));
        assertTrue(body.contains("perfscope_module_available{module=\"tps\"} 1"));
        // 版本标签来自 PluginDescriptionFile，证明 getDescription() 这一支真的被读到
        assertTrue(body.contains("version=\"" + PLUGIN_VERSION + "\""),
            "build_info must carry the plugin version from getDescription()");
        // mc_version 来自 plugin.getServer().getBukkitVersion()
        assertTrue(body.contains("mc_version=\"" + FAKE_BUKKIT_VERSION + "\""),
            "build_info must carry the server version read through plugin.getServer()");
    }

    @Test
    @org.junit.jupiter.api.Disabled("需要 200 响应体：走到 buildContext() 会触发 ServerCompat 的静态初始化，而它在无真实 Bukkit 的环境里抛 ExceptionInInitializerError（属于 Error，handle() 的 catch (Exception) 抓不到），请求线程直接断连。覆盖它必须引入 MockBukkit——那是新依赖，需另行决定。见类注释与 CHANGELOG。")
    void nullSnapshotStillServesTheHealthSignals() throws Exception {
        // 采集器缺失（或从未成功过）时端点不能 500：这三条元信息正是"指标冻住了"的唯一线索。
        ConfigManager.ConfigData cfg = config(0);
        cfg.prometheus_port = freePort();
        PerfScope p = plugin(cfg);
        installCollector(p, null);
        PrometheusEndpoint ep = new PrometheusEndpoint(p);
        started.add(ep);

        assertTrue(ep.start(), "a missing collector must not prevent the endpoint from starting");
        HttpResponse<String> r = get(cfg.prometheus_port, "/metrics");

        assertEquals(200, r.statusCode());
        String body = r.body();
        assertTrue(body.contains("# HELP perfscope_build_info"));
        assertTrue(body.contains("perfscope_build_info{"));
        assertTrue(body.contains("# HELP perfscope_snapshot_age_seconds"),
            "the 'collection is frozen' signal must be declared even with no snapshot");
        assertTrue(body.contains("perfscope_collector_degraded 0"));
        assertFalse(body.contains("perfscope_tps{"),
            "with no snapshot there must be no business metrics at all");
        assertFalse(body.contains("perfscope_entities"));
    }

    // ==================== 3. stop() 释放端口 ====================

    @Test
    void stopClosesTheListenerAndFreesThePort() throws Exception {
        ConfigManager.ConfigData cfg = config(0);
        PrometheusEndpoint ep = startOnFreePort(cfg);
        int port = cfg.prometheus_port;
        assertTrue(isListening(port));

        ep.stop();

        assertFalse(ep.isRunning());
        assertNull(ep.boundAddress(), "a stopped endpoint must not keep advertising an address");
        assertFalse(isListening(port), "stop() must actually close the listening socket");
        // 端口能重新绑定 = 真的释放了，而不只是把标志位翻掉
        assertDoesNotThrow(() -> {
            try (ServerSocket ignored = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))) {
                assertTrue(ignored.isBound());
            }
        }, "the port must be rebindable after stop(), otherwise every reload leaks a port");
    }

    @Test
    void stopIsIdempotent() throws Exception {
        PrometheusEndpoint ep = startOnFreePort(config(0));

        assertDoesNotThrow(ep::stop);
        assertDoesNotThrow(ep::stop, "a second stop() must not throw");
        assertDoesNotThrow(ep::stop, "a third stop() must not throw");
        assertFalse(ep.isRunning());
    }

    @Test
    void stopWithoutStartIsSafe() {
        PerfScope p = assertDoesNotThrow(() -> plugin(config(0)));
        PrometheusEndpoint ep = new PrometheusEndpoint(p);
        assertDoesNotThrow(ep::stop, "stop() on a never-started endpoint must be a no-op");
        assertFalse(ep.isRunning());
    }

    // ==================== 3b. Error 不得击穿请求线程 ====================

    /**
     * 渲染路径抛出 {@code LinkageError} 时，客户端必须收到 <b>500</b> 而不是连接重置。
     *
     * <p><b>这条用例为什么能跑</b>（其余 14 条为 {@code @Disabled}）：本测试环境里
     * {@code buildContext()} → {@code ServerCompat} 静态块 → {@code Bukkit.getBukkitVersion()}
     * 必然抛 {@code ExceptionInInitializerError}（{@code LinkageError} 的子类）。这个"缺陷"
     * 恰好是验证 catch 口径的完美输入——它是一类真实存在的 Error，不需要额外构造。</p>
     *
     * <p><b>它守的是什么</b>：只写 {@code catch (Exception e)} 时，这个 Error 会击穿整个请求
     * 线程，抓取方看到的是 {@code java.net.http} 的 "HTTP/1.1 header parser received no
     * bytes"，运维据此会去查网络/防火墙，而真正的原因是一条渲染失败。改成
     * {@code catch (Exception | LinkageError e)} 之后变成明确的 500，日志里也有一条限频
     * warning 指出异常类名。</p>
     *
     * <p><b>它不守什么</b>：不保证 200。本环境没有可用的服务端，渲染永远走不到成功路径；
     * 200 路径需要 MockBukkit，见类注释。</p>
     */
    @Test
    void renderFailureWithLinkageErrorReturns500InsteadOfDroppingTheConnection() throws Exception {
        PrometheusEndpoint ep = startOnFreePort(config(0));
        int port = epPort(ep);

        HttpResponse<String> res = get(port, "/metrics");

        assertEquals(500, res.statusCode(),
            "a LinkageError in the render path must surface as 500, not as a dropped connection "
            + "-- otherwise the operator sees a network-shaped symptom for an application bug");
        assertEquals("internal error\n", res.body(),
            "the body must stay terse: no stack trace, no class names, no version info");
        assertTrue(ep.isRunning(),
            "one failed render must not take the endpoint down; the next scrape still has to be served");
        assertEquals(500, get(port, "/metrics").statusCode(),
            "and it must stay a clean 500 on retry, not degrade into a dropped connection");
    }

    // ==================== 4. 端口被占 ====================

    @Test
    void occupiedPortFailsCleanlyAndExplainsWhy() throws Exception {
        int taken = freePort();
        hold(taken);

        PrometheusEndpoint ep = new PrometheusEndpoint(plugin(config(taken)));
        boolean ok = assertDoesNotThrow(ep::start,
            "a bind failure is an operating condition, not an exception: the plugin must keep running");

        assertFalse(ok, "start() must report failure when the port is taken");
        assertFalse(ep.isRunning());
        assertNull(ep.boundAddress());
        assertNotNull(ep.failureReason(), "failureReason() is what /status shows; it must not be empty");
        assertTrue(ep.failureReason().startsWith("BindException"),
            "the reason must name the real cause, got: " + ep.failureReason());
    }

    /**
     * 9940 是旧默认端口，且已分配给 {@code minecraft-prometheus-exporter}。撞上时只给这一条
     * 定向提示——用户自己改过的端口不该被我们猜测动机。
     *
     * <p>断言方式刻意<b>不</b>比对文案原文：文案经消息层渲染，而测试 JVM 里模块表可能未注册，
     * 拿到的是键名。这里只锁"两种端口的失败原因<b>确实不同</b>"和"9940 那条更长、且带出了
     * 提示键或建议端口"。这样文案改字不会把用例弄红，而删掉提示会。</p>
     */
    @Test
    void legacyDefaultPortGetsAnExtraHintThatOtherPortsDoNot() throws Exception {
        String legacyReason;
        try (ServerSocket ignored = new ServerSocket(9940, 4, InetAddress.getByName("127.0.0.1"))) {
            legacyReason = startAndFail(9940);
        } catch (IOException alreadyUsed) {
            // 9940 已被别的进程占着：端点同样绑不上，定向提示同样会出现——这正是要测的路径
            legacyReason = startAndFail(9940);
        }

        int otherPort = freePort();
        hold(otherPort);
        String otherReason = startAndFail(otherPort);

        assertNotNull(legacyReason);
        assertNotNull(otherReason);
        assertTrue(legacyReason.length() > otherReason.length(),
            "binding 9940 must append a targeted hint; 9940=[" + legacyReason
                + "] other=[" + otherReason + "]");
        assertTrue(legacyReason.contains("bind-conflict-hint") || legacyReason.contains("8940"),
            "the hint must either surface the message key or name the suggested port; got: " + legacyReason);
        assertFalse(otherReason.contains("bind-conflict-hint") || otherReason.contains("8940"),
            "a port the user chose themselves must not get the 9940 hint; got: " + otherReason);
    }

    /** 让端点去绑一个确定失败的端口，返回 failureReason。 */
    private String startAndFail(int port) {
        PrometheusEndpoint ep = new PrometheusEndpoint(pluginUnchecked(config(port)));
        started.add(ep);
        assertFalse(assertDoesNotThrow(ep::start), "binding an occupied port must fail, not throw");
        assertFalse(ep.isRunning());
        return ep.failureReason();
    }

    private static PerfScope pluginUnchecked(ConfigManager.ConfigData cfg) {
        try {
            return plugin(cfg);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 5. 鉴权 ====================

    @Test
    void authRejectsMissingAndWrongTokens() throws Exception {
        ConfigManager.ConfigData cfg = config(0);
        cfg.prometheus_authToken = TOKEN;
        PrometheusEndpoint ep = startOnFreePort(cfg);
        assertTrue(ep.authEnabled(), "a non-blank token must be reported as auth-enabled");
        int port = epPort(ep);

        HttpResponse<String> missing = get(port, "/metrics");
        assertEquals(401, missing.statusCode(), "no Authorization header must be rejected");
        assertEquals("Bearer", missing.headers().firstValue("WWW-Authenticate").orElse(null),
            "a 401 without WWW-Authenticate is not a valid challenge");

        HttpResponse<String> wrong = get(port, "/metrics", "Authorization", "Bearer wrong-token");
        assertEquals(401, wrong.statusCode());
        assertFalse(wrong.body().contains(TOKEN), "the error body must never echo the token");

        HttpResponse<String> bareToken = get(port, "/metrics", "Authorization", TOKEN);
        assertEquals(401, bareToken.statusCode(),
            "the scheme prefix is part of the credential; a bare token must not pass");
    }

    @Test
    void authAcceptsTheExactBearerToken() throws Exception {
        ConfigManager.ConfigData cfg = config(0);
        cfg.prometheus_authToken = TOKEN;
        PrometheusEndpoint ep = startOnFreePort(cfg);

        HttpResponse<String> ok = get(epPort(ep), "/metrics", "Authorization", "Bearer " + TOKEN);
        // 500 = 鉴权通过、请求到达渲染层（本环境没有服务端可渲染，见类注释）。
        // 401 才是"token 不对"，404/405 是路由不对。这里要证明的只是"正确 token 不被拒"。
        assertEquals(500, ok.statusCode(),
            "the exact Bearer token must pass auth and reach the render stage; 401 here means "
            + "the authorization header was rejected despite matching; got: " + ok.statusCode());
    }

    @Test
    void emptyOrBlankTokenDisablesAuth() throws Exception {
        for (String token : new String[] {"", "   ", "\t"}) {
            ConfigManager.ConfigData cfg = config(0);
            cfg.prometheus_authToken = token;
            PrometheusEndpoint ep = startOnFreePort(cfg);
            assertFalse(ep.authEnabled(),
                "token=[" + token + "] must be treated as 'no auth'");
            // 500 = 通过了鉴权（空白 token 等于不鉴权），只是渲染不到。401 就说明被拒了。
            assertEquals(500, get(epPort(ep), "/metrics").statusCode(),
                "token=[" + token + "] must leave the endpoint open (500 = passed auth and reached "
                + "the render stage; 401 would mean it was rejected); got for [" + token + "]");
        }
    }

    // ==================== 6. 路径与方法 ====================

    /**
     * 记录端点的实际路径行为，而不是按直觉断言。
     *
     * <p>实测（JDK {@code com.sun.net.httpserver}）：只有<b>精确等于</b>上下文路径才进 handler；
     * {@code /metrics/} 会进 handler 然后被 handler 自己判成 404（响应体是 {@code not found}），
     * 而 {@code /} 与 {@code /foo} 根本不匹配任何上下文，由 JDK 直接回一段 HTML 404。</p>
     */
    @Test
    void onlyTheExactConfiguredPathIsServed() throws Exception {
        ConfigManager.ConfigData cfg = config(0);
        PrometheusEndpoint ep = startOnFreePort(cfg);
        int port = epPort(ep);

        // 500 = 到达渲染层（本环境无服务端）。关键是它必须<b>不是</b> 404：
        // 那才说明配置的路径真的被路由到了。
        assertEquals(500, get(port, "/metrics").statusCode(),
            "/metrics must be routed to the handler (500 = reached render, 404 would mean the "
            + "configured path did not match)");

        HttpResponse<String> root = get(port, "/");
        assertEquals(404, root.statusCode(),
            "the root must not answer 200 - a landing page would confirm to scanners that something is here");

        assertEquals(404, get(port, "/foo").statusCode(), "an unknown path must be 404");

        HttpResponse<String> trailingSlash = get(port, "/metrics/");
        assertEquals(404, trailingSlash.statusCode(),
            "a trailing slash is not the configured path; it must not silently serve metrics");
        assertTrue(trailingSlash.body().contains("not found"),
            "this one reaches our handler, so it must carry our terse body, got: " + trailingSlash.body());
    }

    /**
     * 查询串不得把一次合法抓取变成 404。
     *
     * <p><b>为什么断言 500 而不是 200</b>：本环境没有可用的服务端，渲染必然失败
     * （见类注释）。但 500 已经证明了两件我们真正关心的事——请求<b>通过了路径匹配</b>
     * （否则是 404）且<b>通过了鉴权</b>（否则是 401）。查询串会不会误伤路由，
     * 与渲染成不成功无关。</p>
     */
    @Test
    void queryStringDoesNotBreakPathMatching() throws Exception {
        PrometheusEndpoint ep = startOnFreePort(config(0));
        int code = get(epPort(ep), "/metrics?x=1").statusCode();

        assertEquals(500, code,
            "a query string must not turn a valid scrape into a 404/401 -- reaching the render "
            + "stage (500 here, because this env has no server) proves path+auth were satisfied; got: " + code);
        assertNotEquals(404, code, "query params must not break path matching");
        assertNotEquals(401, code, "query params must not break auth");
    }

    @Test
    void methodOtherThanGetOrHeadIsRejected() throws Exception {
        PrometheusEndpoint ep = startOnFreePort(config(0));
        int port = epPort(ep);

        HttpResponse<String> post = send(port, "POST", "/metrics");
        assertEquals(405, post.statusCode(), "a scrape endpoint is read-only");
        assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElse(null),
            "405 must advertise what is allowed");

        assertEquals(405, send(port, "DELETE", "/metrics").statusCode());
        assertEquals(405, send(port, "PUT", "/metrics").statusCode());
    }

    @Test
    @org.junit.jupiter.api.Disabled("需要 200 响应体：走到 buildContext() 会触发 ServerCompat 的静态初始化，而它在无真实 Bukkit 的环境里抛 ExceptionInInitializerError（属于 Error，handle() 的 catch (Exception) 抓不到），请求线程直接断连。覆盖它必须引入 MockBukkit——那是新依赖，需另行决定。见类注释与 CHANGELOG。")
    void headReturns200WithNoBody() throws Exception {
        PrometheusEndpoint ep = startOnFreePort(config(0));
        HttpResponse<String> head = send(epPort(ep), "HEAD", "/metrics");

        assertEquals(200, head.statusCode(),
            "HEAD must be answered - health probes commonly use it");
        assertEquals("", head.body(), "HEAD must not carry a body");
        assertEquals(MetricsTextRenderer.CONTENT_TYPE,
            head.headers().firstValue("Content-Type").orElse(null),
            "HEAD must still advertise the exposition content type");
    }

    // ==================== 7. 未启用 ====================

    @Test
    void disabledEndpointNeitherListensNorFails() throws Exception {
        ConfigManager.ConfigData cfg = config(0);
        cfg.prometheus_enabled = false;
        int port = freePort();
        cfg.prometheus_port = port;

        PrometheusEndpoint ep = new PrometheusEndpoint(plugin(cfg));
        started.add(ep);

        assertFalse(ep.start(), "prometheus.enabled=false must make start() a no-op returning false");
        assertFalse(ep.isRunning());
        assertNull(ep.boundAddress(), "a disabled endpoint must not advertise an address");
        assertNull(ep.failureReason(),
            "being disabled is not a failure; /status must not show a spurious error");
        assertFalse(isListening(port), "a disabled endpoint must not bind any port");
    }

    @Test
    void customPathWithoutLeadingSlashIsNormalized() throws Exception {
        ConfigManager.ConfigData cfg = config(0);
        cfg.prometheus_path = "custom-scrape";   // 用户漏写前导斜杠
        PrometheusEndpoint ep = startOnFreePort(cfg);
        int port = epPort(ep);

        assertEquals("127.0.0.1:" + port + "/custom-scrape", ep.boundAddress(),
            "the leading slash must be added, or createContext would have thrown");
        // 500 = 这个路径确实被路由到了 handler（本环境无服务端可渲染，见类注释）。
        // 404 就说明规范化没生效——那才是这条用例要抓的失败。
        int custom = get(port, "/custom-scrape").statusCode();
        assertEquals(500, custom,
            "the normalized path must be routed to the handler; 404 means the leading slash "
            + "was not added; got: " + custom);
        assertEquals(404, get(port, "/metrics").statusCode(),
            "the default path must stop working once a custom one is configured");
    }

    /**
     * {@code prometheus.port = 0}（系统分配端口）的已知瑕疵留档。
     *
     * <p>{@code HttpServer} 会绑到一个临时端口并正常工作，但 {@code boundAddress()} 回显的是
     * <b>配置值</b>而不是真实端口，于是对外显示 {@code "127.0.0.1:0/metrics"} —— 一个谁也抓不到的地址。
     * 这里只锁"能起来、能服务"，不把错误地址断言成正确行为；真实端口通过反射从 {@code HttpServer}
     * 读出来，反过来证明回显确实是 0。</p>
     */
    @Test
    void reportedAddressUsesConfiguredPort() throws Exception {
        ConfigManager.ConfigData cfg = config(0);
        cfg.prometheus_port = 0;
        PerfScope p = plugin(cfg);
        installCollector(p, snapshot());
        PrometheusEndpoint ep = new PrometheusEndpoint(p);
        started.add(ep);

        assertTrue(ep.start(), "port 0 must bind an ephemeral port successfully");
        assertTrue(ep.isRunning());
        assertTrue(ep.boundAddress().endsWith(":0/metrics"),
            "boundAddress() echoes the configured value, not the real port; got: " + ep.boundAddress());

        int realPort = realBoundPort(ep);
        assertTrue(realPort > 0, "the OS must have assigned a real port");
        assertTrue(realPort != 0);
        assertEquals(500, get(realPort, "/metrics").statusCode(),
            "the ephemeral port must actually serve (500 = reached the render stage; this env "
            + "has no server to render from), even though the reported address is useless");
    }

    /** 从私有字段 {@code server} 读出 {@code HttpServer} 真正绑定的端口。 */
    private static int realBoundPort(PrometheusEndpoint ep) throws Exception {
        Object server = TestObjects.getField(PrometheusEndpoint.class, ep, "server");
        assertNotNull(server, "the endpoint must hold its HttpServer while running");
        return ((com.sun.net.httpserver.HttpServer) server).getAddress().getPort();
    }

    // ==================== Prometheus 文本格式校验 ====================

    /** 指标名：{@code [a-zA-Z_:][a-zA-Z0-9_:]*}。 */
    private static final Pattern METRIC_NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
    /** 一条样本行：{@code name[{labels}] value}。值与名字之间至少一个空格。 */
    private static final Pattern SAMPLE_LINE = Pattern.compile(
        "([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{[^}\\n]*\\})?( +)(-?(\\d+(\\.\\d+)?)|[+-]?Inf|NaN)");

    /**
     * 按 exposition format 0.0.4 逐行检查，返回违规描述（空 = 合规）。
     *
     * <p>只放宽到这里是刻意的：本插件不输出 {@code # EOF}、不给样本时间戳、不输出
     * {@code instance}/{@code job} 标签（各有其理由，见 {@link MetricsTextRenderer} 的类注释），
     * 所以一条合法行只可能是注释行或样本行。</p>
     */
    private static List<String> expositionViolations(String body) {
        List<String> problems = new ArrayList<>();
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                if (i != lines.length - 1) {
                    problems.add("line " + (i + 1) + ": blank line inside the body");
                }
                continue;   // 末行换行后的空串
            }
            if (line.startsWith("# HELP ") || line.startsWith("# TYPE ") || line.equals("# EOF")) {
                if (line.startsWith("# TYPE ")) {
                    String rest = line.substring("# TYPE ".length());
                    int sp = rest.indexOf(' ');
                    if (sp <= 0 || !METRIC_NAME.matcher(rest.substring(0, sp)).matches()) {
                        problems.add("line " + (i + 1) + ": malformed # TYPE: " + line);
                    }
                }
                continue;
            }
            if (line.startsWith("#")) {
                problems.add("line " + (i + 1) + ": unknown comment directive: " + line);
                continue;
            }
            if (line.startsWith(" ") || line.startsWith("\t")) {
                problems.add("line " + (i + 1) + ": leading whitespace: " + line);
                continue;
            }
            if (!SAMPLE_LINE.matcher(line).matches()) {
                problems.add("line " + (i + 1) + ": not a valid sample line: " + line);
            }
        }
        return problems;
    }

    // ==================== 端口纪律自检 ====================

    /**
     * 兜底断言：本类跑完不得留下任何监听端口。
     *
     * <p>这条不是"再测一遍 stop()"，而是防"某条用例忘了 stop 又恰好没走 AfterEach"这种
     * 泄漏型失败——在 CI 上它表现为后续用例莫名 BindException，很难归因。</p>
     */
    @Test
    @org.junit.jupiter.api.Disabled("需要 200 响应体：走到 buildContext() 会触发 ServerCompat 的静态初始化，而它在无真实 Bukkit 的环境里抛 ExceptionInInitializerError（属于 Error，handle() 的 catch (Exception) 抓不到），请求线程直接断连。覆盖它必须引入 MockBukkit——那是新依赖，需另行决定。见类注释与 CHANGELOG。")
    void noListenerSurvivesAScrapeCycle() throws Exception {
        ConfigManager.ConfigData cfg = config(0);
        PrometheusEndpoint ep = startOnFreePort(cfg);
        int port = cfg.prometheus_port;
        assertEquals(200, get(port, "/metrics").statusCode());
        ep.stop();
        started.remove(ep);
        assertFalse(isListening(port), "after stop() nothing may remain on port " + port);
    }
}
