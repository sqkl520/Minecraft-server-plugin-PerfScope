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
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 */
public class WebhookNotifier {

    /** 单次请求超时（毫秒） */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

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
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            // 只记 scheme+host 形态的说明，不记完整 URL（可能内嵌令牌）
            plugin.getLogger().warning("[告警] webhook url 非 http(s) 前缀，推送保持关闭");
            ready = false;
            return;
        }
        this.url = u;
        this.type = "onebot".equals(t) ? "onebot" : "discord";
        this.ready = true;
        // 只记 host + type，不记完整 URL（安全要求）
        plugin.getLogger().info("[告警] webhook 已启用（type=" + this.type + ", host=" + safeHost(u) + "）");
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
                obj.addProperty("message", text);
            } else {
                obj.addProperty("content", text);
            }
            body = gson.toJson(obj);
        } catch (Exception e) {
            logFailure("序列化失败: " + e.getClass().getSimpleName());
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
                        logFailure(err.getClass().getSimpleName() + ": " + err.getMessage());
                    } else if (resp.statusCode() >= 300) {
                        logFailure("HTTP " + resp.statusCode());
                    }
                });
        } catch (Exception e) {
            // URI.create 等同步异常（配置已校验过前缀，理论上罕见）
            logFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ==================== 内部工具 ====================

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

    /** 提取 URL 的 host 供日志（绝不出完整 URL；解析失败返回 "?"）。 */
    private static String safeHost(String u) {
        try {
            String host = URI.create(u).getHost();
            return host == null ? "?" : host;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 供 debug 排查用（general.debug 门控）：输出完整异常堆栈但同样不含 URL。 */
    void debugLog(String message, Throwable t) {
        if (configManager.get() != null && configManager.get().general_debug) {
            plugin.getLogger().log(Level.FINE, "[告警] webhook: " + message, t);
        }
    }
}
