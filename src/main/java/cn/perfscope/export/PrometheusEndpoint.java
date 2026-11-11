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
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.config.ConfigManager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Prometheus 抓取端点：用 JDK 自带的 {@code com.sun.net.httpserver} 提供服务，<b>零第三方依赖</b>。
 *
 * <p><b>为什么不用 {@code io.prometheus:prometheus-metrics-*}</b>：本场景只需要"把一张快照
 * 序列化成文本"，用不到注册表、Collector 生命周期、exemplars。引 1–2 MB 依赖还要 relocate，
 * 而本仓库已经有过一次 shade 事故（v1.0.0 的 Adventure relocate）。渲染逻辑在
 * {@link MetricsTextRenderer} 里是纯函数，这里只管 HTTP。</p>
 *
 * <p><b>失败绝不影响插件本体</b>：端口被占、{@code jdk.httpserver} 模块缺失、
 * 绑定权限不足，一律只记一条 warning 并把端点置为未启动，与 {@code startBstats()} 同款容错口径。
 * 一个诊断插件的可选出口不应该有能力让服务器起不来。</p>
 *
 * <p><b>线程</b>：{@code HttpServer} 自带 2 个守护线程。处理函数只读
 * {@link MetricsCollector#getLatest()}（{@code ConcurrentLinkedDeque} 的安全发布）与已固化的
 * 不可变集合，<b>不碰任何 Bukkit API</b>——因此在 Folia 上也不需要 region 调度。</p>
 */
public final class PrometheusEndpoint {

    /** 端口被占等启动失败后，隔多久再重试（reload 之外的自愈机会）。 */
    private static final int WORKER_THREADS = 2;

    /**
     * 旧默认端口：9940。仅用于在 bind 失败时认出"用的是我们改掉的那个默认值"。
     *
     * <p>9940 已分配给 {@code minecraft-prometheus-exporter}，装它的机器上本插件必然抢不到。
     * 只对这一个值给定向提示——用户自己改过的端口不该被我们猜测动机。</p>
     */
    private static final int LEGACY_DEFAULT_PORT_CONFLICT = 9940;

    /** 建议改用的端口：8940（IANA 与 Prometheus 分配表均未占用），与 ConfigManager 默认值一致。 */
    private static final int PROMETHEUS_PORT_SUGGESTED = 8940;

    private final PerfScope plugin;
    /** 端点启动失败的原因（null = 正常或未启用）；供 /status 展示。 */
    private volatile String failureReason;
    private volatile String boundAddress;
    private volatile boolean authEnabled;

    private HttpServer server;
    private ExecutorService executor;

    public PrometheusEndpoint(PerfScope plugin) {
        this.plugin = plugin;
    }

    /**
     * 按当前配置启动端点。重复调用安全（内部先 {@link #stop()}）。
     *
     * @return true = 已在监听；false = 未启用或启动失败（原因见 {@link #failureReason()}）
     */
    public synchronized boolean start() {
        stop();

        ConfigManager.ConfigData cfg = plugin.getConfigManager() == null
            ? null : plugin.getConfigManager().get();
        if (cfg == null || !cfg.prometheus_enabled) {
            return false;
        }

        String bind = cfg.prometheus_bind == null || cfg.prometheus_bind.isBlank()
            ? "127.0.0.1" : cfg.prometheus_bind.trim();
        int port = cfg.prometheus_port;
        String path = cfg.prometheus_path == null || cfg.prometheus_path.isBlank()
            ? "/metrics" : cfg.prometheus_path.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        try {
            InetSocketAddress addr = new InetSocketAddress(bind, port);
            HttpServer s = HttpServer.create(addr, 0);
            final String contextPath = path;
            s.createContext(contextPath, ex -> handle(ex, contextPath));
            executor = Executors.newFixedThreadPool(WORKER_THREADS, daemonFactory());
            s.setExecutor(executor);
            s.start();

            server = s;
            boundAddress = bind + ":" + port + path;
            authEnabled = cfg.prometheus_authToken != null && !cfg.prometheus_authToken.isBlank();
            failureReason = null;

            plugin.getLogger().info(cn.perfscope.util.Messages.get(
                "core.prometheus.started", boundAddress, authEnabled ? "auth" : "no-auth"));
            if (!isLoopback(bind)) {
                // 绑到非回环地址 = 对局域网/公网开放。玩家数、世界名、插件数量都在里面。
                // 不阻止（容器里抓取必须这样），但必须让人看见。
                plugin.getLogger().warning(cn.perfscope.util.Messages.get(
                    "core.prometheus.exposed", boundAddress, authEnabled ? "auth" : "NO AUTH"));
            }
            return true;
        } catch (Exception | LinkageError e) {
            // LinkageError 覆盖"jlink 裁过 JDK、缺 jdk.httpserver 模块"这种情形
            server = null;
            boundAddress = null;
            String reason = e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
            if (e instanceof java.net.BindException) {
                // 9940 是旧默认值，且早已分配给 minecraft-prometheus-exporter：
                // 装了那个 exporter 的机器上，本插件永远是后启动、抢不到端口的那一方。
                // 只对旧默认值提示——用户自己改过的端口不该被我们猜测动机。
                if (port == LEGACY_DEFAULT_PORT_CONFLICT) {
                    reason = reason + " | " + cn.perfscope.util.Messages.get(
                        "core.prometheus.bind-conflict-hint", port, PROMETHEUS_PORT_SUGGESTED);
                }
            }
            failureReason = reason;
            if (cfg.general_debug) {
                plugin.getLogger().log(Level.WARNING,
                    cn.perfscope.util.Messages.get("core.prometheus.start-failed", failureReason), e);
            } else {
                plugin.getLogger().warning(
                    cn.perfscope.util.Messages.get("core.prometheus.start-failed", failureReason));
            }
            return false;
        }
    }

    /** 停止端点并释放端口。可在未启动时安全调用。 */
    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception ignored) {
                // 已经停了；关闭路径不再制造新的失败
            }
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        boundAddress = null;
    }

    public boolean isRunning() {
        return server != null;
    }

    public String boundAddress() {
        return boundAddress;
    }

    public String failureReason() {
        return failureReason;
    }

    public boolean authEnabled() {
        return authEnabled;
    }

    // ==================== HTTP ====================

    private void handle(HttpExchange ex, String contextPath) throws IOException {
        try {
            // 只回答配置的路径，且只认 GET/HEAD。不提供根路径页面：
            // 一个 404 页就能让扫描器确认"这里有东西"，而 HTTP 服务的价值只在 /metrics。
            if (!contextPath.equals(ex.getRequestURI().getPath())) {
                respond(ex, 404, "not found\n");
                return;
            }
            String method = ex.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
                respond(ex, 405, "method not allowed\n");
                return;
            }

            if (!authorized(ex)) {
                // 401 响应体不回显任何版本信息——扫描器常拿错误响应判断服务类型
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                respond(ex, 401, "unauthorized\n");
                return;
            }

            String body = MetricsTextRenderer.render(
                plugin.getMetricsCollector() == null ? null : plugin.getMetricsCollector().getLatest(),
                buildContext());

            if ("HEAD".equals(method)) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", MetricsTextRenderer.CONTENT_TYPE);
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            respond(ex, 200, body);
        } catch (Exception e) {
            // 异常绝不进响应体（会把内部路径/堆栈泄露给抓取方），只记一条 warning。
            // 限频：同一类失败每秒最多一条，避免抓取方重试把日志刷爆。
            warnThrottled(e);
            try {
                respond(ex, 500, "internal error\n");
            } catch (Exception ignored) {
                // 响应阶段已断开，无法再写
            }
        } finally {
            ex.close();
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type",
            status == 200 ? MetricsTextRenderer.CONTENT_TYPE : "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private boolean authorized(HttpExchange ex) {
        ConfigManager.ConfigData cfg = plugin.getConfigManager() == null
            ? null : plugin.getConfigManager().get();
        String token = cfg == null ? null : cfg.prometheus_authToken;
        if (token == null || token.isBlank()) {
            return true;
        }
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return false;
        }
        String expected = "Bearer " + token.trim();
        // 定长比较，避免按字节短路泄露前缀（token 是凭据，沿用 WebhookNotifier 的纪律）
        return constantTimeEquals(header.trim(), expected);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    private MetricsTextRenderer.Context buildContext() {
        MetricsCollector collector = plugin.getMetricsCollector();
        ConfigManager.ConfigData cfg = plugin.getConfigManager() == null
            ? null : plugin.getConfigManager().get();

        String serverId = cfg == null || cfg.prometheus_serverId == null
            ? "" : cfg.prometheus_serverId.trim();
        if (serverId.isEmpty()) {
            serverId = deriveServerId();
        }

        Map<String, Long> events = plugin.getAlertService() == null
            ? Map.of() : plugin.getAlertService().alertEventCounters();

        return new MetricsTextRenderer.Context(
            serverId,
            plugin.getDescription() == null ? "" : String.valueOf(plugin.getDescription().getVersion()),
            ServerCompat.getServerName(),
            plugin.getServer() == null ? "" : plugin.getServer().getBukkitVersion(),
            System.getProperty("java.version", ""),
            // Folia 上 TPS 只覆盖 global region，不是全服。把它做成标签而不是藏进文档。
            ServerCompat.isTpsGlobalScope() ? "" : "global_region",
            System.currentTimeMillis(),
            collector == null ? 0L : collector.lastCollectAtMs(),
            collector != null && collector.isStale(),
            collector == null ? 0 : collector.consecutiveCollectFailures(),
            plugin.getAlertService() == null ? 0 : plugin.getAlertService().getActiveCount(),
            plugin.getAlertService() == null ? 0 : plugin.getAlertService().getSuspendedCount(),
            events
        );
    }

    /**
     * 未配置 {@code server-id} 时的派生值。
     *
     * <p>多服聚合时两个服撞同一个 id 会让 Grafana 把两条序列画成一条。因此派生值必须
     * 尽量唯一：优先用世界名（同一群组内通常不同），回退到监听端口。
     * 刻意<b>不</b>用 hostname——容器里所有实例的 hostname 常常一样。</p>
     */
    private String deriveServerId() {
        try {
            var worlds = plugin.getServer().getWorlds();
            if (!worlds.isEmpty() && worlds.get(0).getName() != null) {
                String name = worlds.get(0).getName();
                if (!name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // 取不到就继续回退
        }
        try {
            return "port-" + plugin.getServer().getPort();
        } catch (Exception ignored) {
            return "perfscope";
        }
    }

    private final AtomicLong lastWarnAtMs = new AtomicLong();

    private void warnThrottled(Exception e) {
        long now = System.currentTimeMillis();
        long last = lastWarnAtMs.get();
        if (now - last < 1000L || !lastWarnAtMs.compareAndSet(last, now)) {
            return;
        }
        ConfigManager.ConfigData cfg = plugin.getConfigManager() == null
            ? null : plugin.getConfigManager().get();
        // 只记异常类名与首句，绝不记请求头（可能含 Bearer token）
        if (cfg != null && cfg.general_debug) {
            plugin.getLogger().log(Level.WARNING,
                cn.perfscope.util.Messages.get("core.prometheus.render-failed", e.getClass().getSimpleName()), e);
        } else {
            plugin.getLogger().warning(
                cn.perfscope.util.Messages.get("core.prometheus.render-failed", e.getClass().getSimpleName()));
        }
    }

    private static boolean isLoopback(String bind) {
        return "127.0.0.1".equals(bind) || "localhost".equalsIgnoreCase(bind) || "::1".equals(bind);
    }

    private static ThreadFactory daemonFactory() {
        AtomicLong seq = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, "perfscope-metrics-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
