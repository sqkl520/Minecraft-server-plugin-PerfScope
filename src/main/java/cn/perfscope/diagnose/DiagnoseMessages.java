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
package cn.perfscope.diagnose;

import cn.perfscope.util.Messages;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 诊断层文案（结论 / 依据 / 建议 / 跳过原因）。
 *
 * <p>本类只承载"用户看到的诊断文本"，不承载规则判断。规则 ID（{@code DIAG-MEM-LEAK} 等）
 * 继续留在 {@link CoreRules} 里当<b>标识符</b>：它们既被 harness 断言，也被
 * {@link Diagnosis#ruleId()} 用作稳定锚点，一旦翻译成"内存泄漏"之类的词，外部脚本和
 * 历史报告就再也对不上了。键名因此是 {@code diag.<RULE-ID>.title}——中文只出现在值里。</p>
 *
 * <p><b>为什么建议里的命令必须逐字保留</b>：建议是服主的操作手册，不是描述性文字。
 * {@code /spark heapdump}、{@code /spark profiler start --only-ticks-over 200}、
 * {@code /perfscope hotspot}、{@code /perfscope report} 在两种语言里都是同一条命令，
 * 翻译它等于给出一条不存在的指令——这比不给建议更糟。</p>
 *
 * <p><b>占位符</b>：所有数值（分钟数、百分比、坐标、阈值、实体数）都由调用点计算后按
 * {@code {0}}/{@code {1}} 传入，本类不做任何数字格式化。这样中英文可以各自调整语序
 * （"近 {0} 分钟" vs "over the last {0} min"），而数值本身仍与日志、报告逐字一致。</p>
 *
 * <p><b>注册时序</b>：静态块调用 {@link Messages#registerModule}，与 {@code CoreMessages} 同构。
 * 需要由启动流程（{@code PerfScope#onEnable}）登记
 * {@code Messages.onFirstLoad(DiagnoseMessages::init)}，否则本表只会在类首次被使用时
 * 才合并，而那时 {@code Messages} 的默认快照已经发布——首轮界面会退回键名。</p>
 */
public final class DiagnoseMessages {

    static {
        Map<String, String> en = new LinkedHashMap<>();
        Map<String, String> zh = new LinkedHashMap<>();

        // ==================== 通用标签 ====================
        put(en, zh, "diag.label.history", "History", "历史");
        // F-3：TPS 的 0.0 是真实读数（无 -1 哨兵），只有"不可能是读数"的值（负数 / NaN /
        // Infinity）才算"服务端没上报"。
        // 刻意不用"未采集"这个词——它在本项目里专指"采集层没取到"，而这里是采集层取到了
        // 一个无法解释的值；两者都不该被当成 0 渲染，但原因不同，措辞也要不同。
        put(en, zh, "diag.value.not-reported",
            "not reported by the server",
            "服务端未上报");

        // ==================== DIAG-MEM-LEAK ====================
        put(en, zh, "diag.DIAG-MEM-LEAK.title",
            "Sustained heap growth (possible memory leak)",
            "堆占用持续抬升（疑似内存泄漏）");
        put(en, zh, "diag.DIAG-MEM-LEAK.detail",
            "Heap usage climbed from {1}% to {2}% over the last {0} min (+{3} percentage points), and the "
                + "lowest reading in that window was still {4}% (it never fell back to the starting level) "
                + "— currently {5}%",
            "近 {0} 分钟内堆占用从 {1}% 升至 {2}%（+{3} 个百分点），且该时段最低点仍为 {4}%"
                + "（未回落到起点）——当前 {5}%");
        put(en, zh, "diag.DIAG-MEM-LEAK.suggestion",
            "Preserve evidence before you act: 1) /perfscope report to archive the current state; "
                + "2) /spark heapdump to take a heap snapshot (Paper bundles spark, which already ships "
                + "this command; spark can upload the dump for analysis, so no local JVM tooling is needed); "
                + "3) before restarting, check whether -Xmx is too small (currently {0} MB); "
                + "4) common holders: map/cache plugins and abnormally accumulated Item entities "
                + "(run /perfscope hotspot first to look at drops)",
            "先留证再处置：① /perfscope report 存档当前状态；② 用 /spark heapdump 取堆快照"
                + "（Paper 内置 spark 已含此命令，产物可由 spark 上传分析，无需装 JVM 工具链）；"
                + "③ 重启前确认 -Xmx 是否偏小（当前 {0}MB）；"
                + "④ 常见持有者：地图/缓存插件、异常堆积的 Item 实体（可先 /perfscope hotspot 看掉落物）");

        // ==================== DIAG-ENT-HOTSPOT ====================
        put(en, zh, "diag.DIAG-ENT-HOTSPOT.title",
            "Hotspot chunk (entity pile-up in a single chunk)",
            "热点区块（单区块实体堆积）");
        put(en, zh, "diag.DIAG-ENT-HOTSPOT.detail",
            "{0} chunk [{1}, {2}] (block {3},~,{4}) holds {5} entities (per-chunk threshold {6}), "
                + "dominated by {7} ({8}%)",
            "{0} 区块 [{1},{2}]（方块 {3},~,{4}）堆积 {5} 个实体（单区块阈值 {6}），"
                + "主导 {7}（{8}%）");
        put(en, zh, "diag.DIAG-ENT-HOTSPOT.suggestion",
            "1) Look at the coordinates first: /minecraft:tp {0} ~ {1}; 2) if this is a drop pile-up "
                + "(dominant type ITEM), use /perfscope hotspot to copy the coordinates and clear it by "
                + "hand, or temporarily shorten the item despawn time; 3) if a mob farm has run out of "
                + "control, check the world's spawn-limits / mob caps; 4) archive the state with "
                + "/perfscope report before you change anything",
            "① 先去坐标看：/minecraft:tp {0} ~ {1}；② 若是掉落物堆积（主导 ITEM），"
                + "可用 /perfscope hotspot 复制坐标后人工清理，或临时限制掉落物存活时间；"
                + "③ 若是刷怪装置失控，检查世界 spawn-limits / 生物上限；"
                + "④ 处理前建议先 /perfscope report 留档");

        // ==================== DIAG-TPS-PERSIST ====================
        put(en, zh, "diag.DIAG-TPS-PERSIST.title",
            "Sustained low TPS",
            "TPS 持续低位");
        put(en, zh, "diag.DIAG-TPS-PERSIST.detail",
            "15-minute average {0}, window average over the last {1} min {2} (warning line <{3}), "
                + "window low {4} — the rule fires when either measure falls below the warning line; "
                + "compare the 15-minute average with that line to tell a brief dip from sustained load",
            "15 分钟均值 {0}、近 {1} 分钟窗口均值 {2}（告警线 <{3}），窗口最低 {4}"
                + "——任一口径低于告警线即命中；对照 15 分钟均值与该告警线，可区分短暂回落与持续负载");
        put(en, zh, "diag.DIAG-TPS-PERSIST.suggestion",
            "Check these in order: 1) /perfscope hotspot for entity pile-up coordinates; "
                + "2) /perfscope memory for memory pressure; 3) compare the drop-off point in "
                + "/perfscope trend 1 against player logins and scheduled-task times; 4) if you still "
                + "cannot localize it, run /spark profiler start --only-ticks-over 200",
            "按顺序排查：① /perfscope hotspot 查实体堆积坐标；② /perfscope memory 看内存压力；"
                + "③ 对照 /perfscope trend 1 的跌落起点与玩家上线/定时任务时间；"
                + "④ 仍定位不了用 /spark profiler start --only-ticks-over 200");

        // ==================== DIAG-PLAYER-CAP ====================
        put(en, zh, "diag.DIAG-PLAYER-CAP.title",
            "Player count sustained near capacity",
            "在线人数持续接近上限");
        put(en, zh, "diag.DIAG-PLAYER-CAP.detail",
            "Currently {0}/{1} ({2}%), and even the lowest point in the last {3} min was {4}% "
                + "— new players may not be able to join",
            "当前 {0}/{1}（{2}%），近 {3} 分钟内最低也有 {4}%——新玩家可能进不来");
        put(en, zh, "diag.DIAG-PLAYER-CAP.suggestion",
            "First separate capacity from idling: 1) /perfscope players to see the online-time "
                + "distribution; 2) if AFK players are holding slots, install an AFK-kick plugin to free "
                + "them; 3) if the server is genuinely full, raise max-players in server.properties "
                + "(roughly +0.5–1 GB of heap per +10 slots; re-check /perfscope memory afterwards); "
                + "4) for event peaks, post a queue rule in your community channel",
            "先分辨是容量问题还是挂机：① /perfscope players 看在线时长分布；"
                + "② 若是挂机占位，装 AFK 踢出插件释放槽位；"
                + "③ 若是真实满员，扩容 server.properties → max-players"
                + "（每 +10 人约 +0.5–1G 堆，扩容后建议复查 /perfscope memory）；"
                + "④ 活动峰段可在群公告排队规则");

        // ==================== DIAG-MSPT-SPIKE ====================
        put(en, zh, "diag.DIAG-MSPT-SPIKE.title",
            "MSPT spike (isolated single-tick freeze)",
            "MSPT 尖刺（偶发单 tick 冻结）");
        put(en, zh, "diag.DIAG-MSPT-SPIKE.detail",
            "Peak {0}ms is about {2}x the {1}ms average — the average hides an isolated stall, "
                + "which players feel as a brief hitch",
            "最大 {0}ms ≈ 平均 {1}ms 的 {2} 倍——均值掩盖了偶发卡顿，玩家体感为「顿一下」");
        put(en, zh, "diag.DIAG-MSPT-SPIKE.suggestion",
            "Spikes usually come from chunk saves or GC: 1) reduce chunk-save pressure — lower "
                + "chunks.max-auto-save-chunks-per-tick in config/paper-world-defaults.yml "
                + "(default 24, try 8); 2) the autosave interval lives in bukkit.yml under "
                + "ticks-per.autosave (default 6000 ticks); 3) capture it in place: "
                + "/spark profiler start --only-ticks-over 200 (run /spark profiler stop afterwards "
                + "and read the report)",
            "尖刺多来自区块保存或 GC：① 降区块保存压力——改 config/paper-world-defaults.yml 的 "
                + "chunks.max-auto-save-chunks-per-tick（默认 24，可降到 8）；"
                + "② 自动保存周期在 bukkit.yml 的 ticks-per.autosave（默认 6000 tick）；"
                + "③ 定点抓取：/spark profiler start --only-ticks-over 200"
                + "（采样结束后 /spark profiler stop 看报告）");

        // ==================== 跳过原因（三态之"无法评估"） ====================
        put(en, zh, "diag.skip.coverage-samples",
            "{0} samples insufficient ({1}/{2})",
            "{0}样本不足（{1}/{2} 条）");
        put(en, zh, "diag.skip.coverage-span",
            "{0} coverage insufficient (covered {1} of the required {2}; let the server keep running "
                + "for a while before diagnosing again)",
            "{0}覆盖不足（已覆盖 {1} / 需要 {2}；请让服务器继续运行一段时间再诊断）");

        put(en, zh, "diag.skip.memory-unavailable",
            "Memory module unavailable (collection failed or disabled in the configuration)",
            "内存模块不可用（采集失败或被配置禁用）");
        put(en, zh, "diag.skip.heap-below-threshold",
            "Current heap usage {0}% is below the warning line {1}% (no leak verdict at low water level)",
            "当前堆占用 {0}% 低于告警线 {1}%（低水位不判定泄漏）");
        put(en, zh, "diag.skip.heap-sample-missing",
            "The window contains samples without memory data (heapUsagePercent=0); cannot compare the trend",
            "窗口内含内存未采集样本（heapUsagePercent=0），无法比较趋势");
        put(en, zh, "diag.skip.heap-sample-missing-later-half",
            "The second half of the window contains samples without memory data (heapUsagePercent=0); "
                + "cannot tell whether usage fell back",
            "窗口后半段含内存未采集样本（heapUsagePercent=0），无法判定是否回落");

        put(en, zh, "diag.skip.hotspots-unavailable",
            "Hotspot chunk module unavailable (collection failed or disabled via hotspots.enabled=false)",
            "热点区块模块不可用（采集失败或被配置禁用 hotspots.enabled=false）");
        put(en, zh, "diag.skip.hotspots-not-ready",
            "No hotspot sample yet (the first full scan has not finished; usually ready within 30 seconds)",
            "尚无热点采样（首轮全遍历未完成，通常 30 秒内就绪）");

        put(en, zh, "diag.skip.tps-unavailable",
            "TPS module unavailable (collection failed or disabled in the configuration)",
            "TPS 模块不可用（采集失败或被配置禁用）");
        put(en, zh, "diag.skip.tps-samples",
            "Not enough valid TPS samples in history ({0}/{1})",
            "历史有效 TPS 样本不足（{0}/{1} 条）");

        put(en, zh, "diag.skip.players-unavailable",
            "Player module unavailable (collection failed or disabled in the configuration)",
            "玩家模块不可用（采集失败或被配置禁用）");
        put(en, zh, "diag.skip.max-players-not-ready",
            "max-players not available yet (currently {0})",
            "max-players 未就绪（当前 {0}）");

        put(en, zh, "diag.skip.mspt-unavailable",
            "MSPT module unavailable (collection failed or disabled in the configuration)",
            "MSPT 模块不可用（采集失败或被配置禁用）");
        put(en, zh, "diag.skip.mspt-avg-unknown",
            "MSPT average unknown (the current collection path returned no data)",
            "MSPT 平均值未知（当前采集路径未取到数据）");
        put(en, zh, "diag.skip.mspt-no-extremes",
            "The current MSPT collection path provides only the average, with no per-tick extremes "
                + "(spike data requires getTickTimes(), available on Paper 1.20.4+)",
            "当前 MSPT 采集路径仅提供平均值、无 tick 极值（Paper 1.20.4+ 的 "
                + "getTickTimes() 可用时才有尖刺信息）");

        // 引擎自身的两态：契约违约（旧式规则返回 null）与规则抛异常
        put(en, zh, "diag.skip.no-conclusion",
            "Rule returned no verdict (the implementation does not follow the three-state contract)",
            "规则未给出结论（实现未遵循三态契约）");
        put(en, zh, "diag.skip.rule-exception",
            "Rule evaluation threw {0}",
            "规则评估异常：{0}");
        // f-s03-data-06：reload 后快照仍是旧配置代次采集的，不能用它配新阈值求值
        put(en, zh, "diag.skip.stale-config",
            "Configuration was reloaded after this sample was collected; evaluating only once a "
                + "sample from the current configuration is available (normally within one cycle)",
            "配置已重载，但本样本仍是在旧配置下采集的；需等到当前配置下采集的样本才能评估（通常一个采集周期内）");

        // ==================== 控制台日志（与玩家侧文案分开） ====================
        put(en, zh, "diag.log.rule-failed",
            "[Diagnostics] Rule {0} evaluation failed (skipped)",
            "[诊断] 规则 {0} 评估失败（已跳过）");
        put(en, zh, "diag.log.rule-failed-detail",
            "[Diagnostics] Rule {0} evaluation failed (skipped): {1}",
            "[诊断] 规则 {0} 评估失败（已跳过）: {1}");

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

    private DiagnoseMessages() {}
}
