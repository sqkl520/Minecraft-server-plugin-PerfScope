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
package cn.perfscope;

import cn.perfscope.util.Messages;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动横幅与跨模块公共日志文案（9D Phase 1）。
 *
 * <p>为什么单独一个类而不是继续写在 {@code Messages} 的静态块里：命令层/报告层/告警层/诊断层
 * 各有几十到上百条文案，全部塞进 {@code Messages} 会让它膨胀成两千行的写入热点——
 * 多个改造任务无法并行，任何冲突都落在同一个文件上。现在每层自带一张表，
 * 通过 {@link Messages#registerModule} 注册，本类只承载"不属于任何单一模块"的那部分：
 * 启停横幅、采集健康自检、兼容层的能力探测说明。</p>
 *
 * <p><b>为什么文案里带 {@code {0}} 占位符而不是拼接</b>：同一句话在两种语言里词序不同
 * （"采集已停止更新 30 秒" vs "Collection stopped updating 30s ago"），
 * 只有把数值当参数传，翻译才能自由调整语序。</p>
 */
public final class CoreMessages {

    static {
        Map<String, String> en = new LinkedHashMap<>();
        Map<String, String> zh = new LinkedHashMap<>();

        // ---- 启动横幅 ----
        put(en, zh, "core.banner.starting", "  PerfScope v{0} starting...", "  PerfScope v{0} 启动中...");
        put(en, zh, "core.banner.server", "  Server: {0}", "  服务端: {0}");
        put(en, zh, "core.banner.config-loaded", "  [+] Configuration loaded", "  [+] 配置系统已加载");
        put(en, zh, "core.banner.collector-started",
            "  [+] Collector started (interval: {0} ticks)", "  [+] 性能采集器已启动 (间隔: {0} ticks)");
        put(en, zh, "core.banner.commands-registered",
            "  [+] Commands registered (/perfscope, /pscope, /perf)", "  [+] 指令系统已注册 (/perfscope, /pscope, /perf)");
        put(en, zh, "core.banner.report-ready", "  [+] Report system ready (format: {0})", "  [+] 报告系统就绪 (格式: {0})");
        put(en, zh, "core.banner.mspt-exact", "  MSPT sampling: Paper API (exact)", "  MSPT精确采集: Paper API");
        put(en, zh, "core.banner.mspt-unavailable", "  MSPT precision: unavailable ({0})", "  MSPT精度: 不可用（{0}）");
        put(en, zh, "core.banner.mspt-exact-unavailable-only", "  MSPT precision: unavailable", "  MSPT精度: 不可用");
        put(en, zh, "core.banner.actionbar", "  ActionBar: {0}", "  ActionBar: {0}");
        put(en, zh, "core.banner.available", "available", "可用");
        put(en, zh, "core.banner.unavailable", "unavailable", "不可用");
        put(en, zh, "core.banner.bstats-disabled",
            "  [PerfScope] bStats anonymous metrics disabled (pluginId is 0; set it after registering at bstats.org)",
            "  [PerfScope] bStats 匿名统计未启用 (pluginId 占位为 0，作者注册 bstats.org 后替换生效)");

        // ---- 采集健康自检 ----
        put(en, zh, "core.health.stale",
            "[PerfScope] Collection stopped updating {0}s ago (last success {1}, {2} consecutive failures) — "
                + "/perfscope data is stale; run /perfscope reload, restart the server if that does not help",
            "[PerfScope] 采集已停止更新 {0} 秒（最后成功采集 {1}，连续失败 {2} 次）——"
                + "/perfscope 显示的数据已过期，请执行 /perfscope reload，仍无效则重启服务器");
        put(en, zh, "core.health.recovered",
            "[PerfScope] Collection recovered (last success {0})",
            "[PerfScope] 采集已恢复（最后成功采集 {0}）");
        put(en, zh, "core.health.recovered-warmup",
            "[PerfScope] Collection recovered (new task warm-up, no data yet)",
            "[PerfScope] 采集已恢复（已进入新任务的预热窗口，尚未产出首轮数据）");
        put(en, zh, "core.health.jvm-stale",
            "[PerfScope] JVM sampling stalled — memory/CPU/GC frozen at {0}; run /perfscope reload, "
                + "restart the server if that does not help",
            "[PerfScope] JVM 异步采集已停止更新（内存/CPU/GC 停留在 {0} 的数值）——"
                + "请执行 /perfscope reload，仍无效则重启服务器");
        put(en, zh, "core.health.jvm-recovered-at",
            "[PerfScope] JVM sampling recovered (last update {0})",
            "[PerfScope] JVM 异步采集已恢复（最后更新 {0}）");

        // ---- 其余控制台日志（验收 HIGH：这些此前硬编码中文，英文模式下会漏中文）----
        put(en, zh, "core.time.never", "never", "从未");
        put(en, zh, "core.log.command-register-failed",
            "Command 'perfscope' registration failed: plugin.yml is missing the command definition",
            "指令 perfscope 注册失败：plugin.yml 缺少命令定义！");
        put(en, zh, "core.log.history-writer-shutdown-failed",
            "[PerfScope] History writer thread shutdown failed: {0}",
            "[PerfScope] 历史写线程关闭失败: {0}");
        put(en, zh, "core.log.report-executor-shutdown-failed",
            "[PerfScope] Report executor shutdown failed: {0}",
            "[PerfScope] 报告执行器关闭失败: {0}");
        put(en, zh, "core.log.alert-writer-shutdown-failed",
            "[PerfScope] Alert state writer thread shutdown failed: {0}",
            "[PerfScope] 告警状态写线程关闭失败: {0}");
        put(en, zh, "core.log.collector-start-failed",
            "[PerfScope] Failed to start collection task: {0}",
            "[PerfScope] 采集任务启动失败: {0}");
        put(en, zh, "core.log.collector-start-stack",
            "[PerfScope] Collection task startup stack trace",
            "[PerfScope] 采集任务启动异常堆栈");
        put(en, zh, "core.log.watchdog-start-failed",
            "[PerfScope] Health watchdog failed to start (collection itself is unaffected; "
                + "/perfscope status still works): {0}",
            "[PerfScope] 采集健康看门狗启动失败（不影响采集本体，/perfscope status 仍可用）: {0}");
        put(en, zh, "core.log.watchdog-cycle-failed",
            "[PerfScope] Health check threw (this cycle skipped): {0}",
            "[PerfScope] 采集健康检查异常（已跳过本轮）: {0}");
        put(en, zh, "core.log.config-load-failed",
            "[PerfScope] Config load failed; continuing with the previous configuration: {0}",
            "[PerfScope] 配置加载失败，将按旧配置继续运行: {0}");
        put(en, zh, "core.log.config-load-stack",
            "[PerfScope] Config load stack trace",
            "[PerfScope] 配置加载异常堆栈");
        put(en, zh, "core.log.report-cleanup-failed",
            "[PerfScope] Old report cleanup failed: {0}",
            "[PerfScope] 旧报告清理失败: {0}");
        put(en, zh, "core.log.history-cleanup-failed",
            "[PerfScope] History file cleanup failed: {0}",
            "[PerfScope] 历史文件清理失败: {0}");
        put(en, zh, "core.log.auto-report-failed",
            "[PerfScope] Failed to start the auto-report task: {0}",
            "[PerfScope] 自动报告任务启动失败: {0}");
        put(en, zh, "core.banner.auto-report",
            "  [+] Auto report enabled (interval: {0} min)",
            "  [+] 自动报告已启用 (间隔: {0} 分钟)");
        put(en, zh, "core.bstats.disabled-by-config",
            "[PerfScope] bStats anonymous metrics disabled (general.bstats-enabled=false)",
            "[PerfScope] bStats 匿名统计未启用 (general.bstats-enabled=false)");
        put(en, zh, "core.bstats.enabled",
            "[PerfScope] bStats anonymous metrics enabled (pluginId={0})",
            "[PerfScope] bStats 匿名统计已启用 (pluginId={0})");
        put(en, zh, "core.bstats.init-failed",
            "[PerfScope] bStats initialization failed (skipped; the plugin itself is unaffected)",
            "[PerfScope] bStats 匿名统计初始化失败（已跳过，不影响插件本体）");
        put(en, zh, "core.bstats.init-failed-detail",
            "[PerfScope] bStats initialization failed (skipped; the plugin itself is unaffected): {0}",
            "[PerfScope] bStats 匿名统计初始化失败（已跳过，不影响插件本体）: {0}");

        // ---- 兼容层能力探测原因（会出现在 /ps、报告、启动日志里）----
        put(en, zh, "compat.mspt.folia-no-global-tick",
            "Folia has no global tick time (getTickTimes is always empty)",
            "Folia 无全局 tick 时间（getTickTimes 恒空）");
        put(en, zh, "compat.mspt.tick-times-empty",
            "getTickTimes/getAverageTickTime measured empty",
            "getTickTimes/getAverageTickTime 实测恒为空");
        put(en, zh, "compat.mspt.no-api", "server provides no MSPT API", "服务端未提供 MSPT 采集 API");
        put(en, zh, "compat.tps.scope-global", " (global region)", " (global region)");
        put(en, zh, "compat.mspt.tps-scope-note",
            ", MSPT unavailable (TPS is a global-region local value)",
            "，MSPT 不可用（TPS 为 global region 局部值）");

        Messages.registerModule(en, zh);
    }

    /** 触发本类静态初始化（由 {@code Messages.onFirstLoad} 登记）。 */
    public static void init() {
        // 空方法体：调用它即触发 static 块
    }

    private static void put(Map<String, String> en, Map<String, String> zh,
                            String key, String enValue, String zhValue) {
        en.put(key, enValue);
        zh.put(key, zhValue);
    }

    private CoreMessages() {}
}
