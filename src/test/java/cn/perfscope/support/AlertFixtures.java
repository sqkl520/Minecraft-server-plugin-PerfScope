/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.support;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;

import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 告警相关测试的通用装配片段。
 *
 * <p>本类只承载 {@link cn.perfscope.alert.AlertServiceStateMachineFixture} 里<b>真正通用</b>的那两块：
 * 文案层初始化，以及 {@link ConfigManager} / 裸 {@link PerfScope} 的构造。
 * 那个夹具本身仍然是它的薄封装，因此 4 个调用方（{@code AlertServiceCooldownTest}、
 * {@code DailySummaryNewMetricsTest}、{@code WebhookAdmitByRateLimitTest}、
 * {@code ThresholdPairZeroValidationTest}）的调用点零改动。</p>
 *
 * <p>夹具里那些针对具体状态机场景的构造（14 个 {@code final} 容器、时钟种子、{@code InlineExecutor}）
 * <b>不</b>搬到这里：它们只在"要跑完整 {@code onSnapshot}"时有意义，抽出来只会让两个类都变得难读。</p>
 */
public final class AlertFixtures {

    private AlertFixtures() {
    }

    /**
     * 初始化文案层：告警 / 配置 / 诊断三个模块的表，然后发布给 {@code Messages}。
     *
     * <p><b>为什么必须传一个非 null 的 plugin</b>：{@code Messages.load(null)} 走的是"无插件"早退分支，
     * 只发布 {@code EN_DEFAULTS}——各模块（{@code alert.*}/{@code diag.*}/{@code config.*}）的表
     * <b>从未被合并</b>，{@code Messages.get} 于是返回键名本身。
     * 断言"键名/令牌"的测试看不出来，但凡要读渲染后的 detail/title，拿到的就毫无意义。</p>
     *
     * <p>传入一个"只有一个 logger"的裸 plugin，{@code load()} 就会先 {@code touchModules()} +
     * {@code mergeModules()} 再发布快照，模块键因此到位。末尾的
     * {@code applyCommandPermissionMessage} 会因为没有 Bukkit 服务端而抛异常——
     * 那一步在快照发布<b>之后</b>，故整体包在 try 里忽略即可，快照不受影响。</p>
     */
    public static void initMessages() {
        try {
            Class.forName("cn.perfscope.alert.AlertMessages").getMethod("init").invoke(null);
            Class.forName("cn.perfscope.config.ConfigMessages").getMethod("init").invoke(null);
            Class.forName("cn.perfscope.diagnose.DiagnoseMessages").getMethod("init").invoke(null);
            PerfScope bare = barePlugin(Logger.getLogger("pscope-message-init"));
            try {
                Class.forName("cn.perfscope.util.Messages")
                    .getMethod("load", Class.forName("cn.perfscope.PerfScope"))
                    .invoke(null, bare);
            } catch (java.lang.reflect.InvocationTargetException ignored) {
                // applyCommandPermissionMessage 需要 Bukkit 服务端；快照在此之前已发布
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("message layer init failed", e);
        }
    }

    /**
     * 造一个只有 {@code logger} 的 {@link PerfScope}。
     *
     * <p>{@code PerfScope} 是 Bukkit 插件类，构造器不可用——见 {@link TestObjects} 约束二，
     * 这是少数几个确实需要 {@code allocateInstance} 的类型之一。</p>
     */
    public static PerfScope barePlugin(Logger logger) {
        PerfScope p = TestObjects.allocate(PerfScope.class);
        TestObjects.setField(PerfScope.class, p, "logger", logger);
        return p;
    }

    /**
     * 造一个已发布配置的 {@link ConfigManager}，并把它挂到 {@code plugin} 上。
     *
     * <p>{@code plugin} 只用于 {@code getLogger()} 一类调用；不启动 Bukkit。
     * 需要 {@code dataFolder} 的路径请自行 {@code setField}（不同测试用的临时目录不同）。</p>
     */
    public static ConfigManager configManager(PerfScope plugin, Consumer<ConfigManager.ConfigData> tweaks) {
        ConfigManager cm = new ConfigManager(plugin);
        ConfigManager.ConfigData cfg = new ConfigManager.ConfigData();
        if (tweaks != null) {
            tweaks.accept(cfg);
        }
        TestObjects.setField(ConfigManager.class, cm, "config", cfg);
        TestObjects.setField(PerfScope.class, plugin, "configManager", cm);
        return cm;
    }
}
