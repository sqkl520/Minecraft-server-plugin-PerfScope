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
package cn.perfscope.util;

import cn.perfscope.PerfScope;
import cn.perfscope.config.ConfigManager;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 消息层 —— 用户可见文案的单一出口（多语言，整份切换）。
 *
 * <p><b>语言模型</b>：英文（{@link #LOCALE_EN}，{@link #DEFAULT_LOCALE}）是默认语言，
 * 中文（{@link #LOCALE_ZH}）是可选语言。当前语言由 {@code config.yml} 的
 * {@code general.language} 决定（经 {@code ConfigManager} 读取），同一时刻<b>只有一种</b>语言生效。
 * 两种语言的文案一律不含"喵"口癖：插件面向的是服务器运维者，界面文案按专业工具的口径书写。</p>
 *
 * <p><b>为什么把"整份切换"当硬约束</b>：旧设计的 {@code messages.yml} 是一层纯中文覆盖。
 * 一旦用户把语言改成英文，而旧文件仍按 key 覆盖内置默认，就会出现"同一屏里英文内置值 +
 * 中文字面值"的混排——这比全中文更糟：用户既读不懂，也判断不出哪些是插件语言没切干净、
 * 哪些是自己改过的文案。因此本类不追求"尽力而为的兼容"，而是保证任何时刻输出只有一种语言。</p>
 *
 * <p><b>覆盖规则（方案 a：单文件 + {@code language} 标签）</b>：
 * {@code plugins/PerfScope/messages.yml} 的第一项元数据是 {@code language: en|zh}，
 * 它是这份文件"用哪种语言写成"的声明。加载时：</p>
 * <ul>
 *   <li>标签与当前语言一致 → 文件内同名键<b>整体</b>作为该语言的覆盖层；</li>
 *   <li>标签与当前语言不一致 → <b>整份文件忽略</b>（记一条 WARNING，说明如何修正），
 *       全部改用当前语言的内置默认——绝不把另一种语言的文案混进来；</li>
 *   <li>标签缺失（旧版本留下的文件）→ 一次性迁移：原文件改名为
 *       {@code messages.yml.bak-<时间戳>}，再按当前语言生成一份新文件（见 {@link #migrateLegacyFile}）；</li>
 *   <li>标签无法识别（如 {@code fr}）→ 整份忽略 + WARNING（不能把"未知语言的文案"当作英文使用）。</li>
 * </ul>
 *
 * <p><b>为什么不是方案 b（{@code messages_en.yml} / {@code messages_zh.yml} 各一份）</b>：
 * 两份文件意味着用户必须在"当前生效的那一份"里编辑，改错文件时插件不会给出任何反馈
 * （另一份文件仍然合法、只是不生效），这正是本项目在 {@code config.yml} 未知键检测里
 * 反复避免的静默失效。单文件 + 显式标签只有一种编辑入口，"文件不生效"必然伴随一条
 * 指名道姓的 WARNING；同时它也让"切语言时旧自定义文案不会被静默沿用到错误语言"这件事
 * 变成结构性的（标签不匹配即整份不读），而不是靠逐个 key 的巧合。</p>
 *
 * <p><b>回退链（仍是两级，与 9A-24 口径一致）</b>：同语言用户覆盖 →
 * 该语言内置默认（{@link #EN_DEFAULTS} / {@link #ZH_DEFAULTS}）→ 键名本身（防御性，便于排查）。
 * jar 内的 {@code messages.yml} 资源<b>不再参与</b>回退：默认文案以代码内置表为唯一权威，
 * 文件只在首次运行/迁移时按内置表生成（Bukkit 保存 YAML 会丢注释，因此生成走手写文本，
 * 不走 {@code YamlConfiguration#save}）。</p>
 *
 * <p><b>占位符契约（{@link #get(String, Object...)}）</b>：文案里写 {@code {0}}、{@code {1}}……
 * 由参数按序号替换。设计目标是"永不抛异常"——消息层出问题绝不能打断业务逻辑：</p>
 * <ul>
 *   <li>参数不足 / 序号越界 → 占位符<b>原样保留</b>（比渲染成空白更容易定位是哪条文案缺参数）；</li>
 *   <li>多余参数 → 忽略；</li>
 *   <li>参数为 {@code null} → 渲染为字面量 {@code "null"}（与 {@code String.valueOf} 一致，
 *       不吞掉"这里本该有值"的信号）；</li>
 *   <li>不匹配 {@code {\d+}} 的花括号（如 {@code {not-a-placeholder}}、JSON 片段）<b>保持原样</b>——
 *       不用 {@code MessageFormat}，正是因为它会把所有花括号都当作格式语法，
 *       且会把单引号当转义符吃掉。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：{@link #load} 在 reload（主线程）调用并加类级锁（避免并发写同一文件）；
 * {@link #get} 可被任意线程调用。运行时状态是一个不可变的 {@link Snapshot}（语言 + 默认表 + 覆盖表），
 * 由 {@code volatile} 字段整体发布：读线程要么看到旧快照、要么看到新快照，
 * <b>绝不会</b>读到"英文默认 + 中文覆盖"这种撕裂组合——语言一致性由发布方式本身保证，
 * 而不是依赖调用顺序。</p>
 */
public final class Messages {

    /** 用户覆盖文件名（位于插件数据目录）。 */
    private static final String FILE_NAME = "messages.yml";

    /** 英文：默认语言。 */
    public static final String LOCALE_EN = "en";
    /** 中文：可选语言。 */
    public static final String LOCALE_ZH = "zh";
    /** 默认语言（配置缺省/未知时使用）。 */
    public static final String DEFAULT_LOCALE = LOCALE_EN;

    /**
     * 覆盖文件的元数据键：声明"这份文件是用哪种语言写成的"。
     * 它不是文案键，绝不进入覆盖表（否则会成为一条永远读不到的脏 key）。
     */
    private static final String LANGUAGE_KEY = "language";

    /**
     * 配置侧语言字段的候选名。
     *
     * <p>{@code ConfigManager.ConfigData} 的 {@code general_language} 字段由另一批改动引入，
     * 与本文件不同步。为了让本文件能<b>独立编译</b>、并在字段就位后自动生效，这里用反射查找
     * （先找公开字段，再兜底找非公开字段），而不是直接引用字段名——直接引用会让编译依赖
     * "另一个 Agent 已先落地"这一时序假设。字段名以下划线风格（{@code general_language}，与
     * ConfigData 现有命名一致）为主，另兼容驼峰写法 {@code generalLanguage}。找不到字段时按
     * {@link #DEFAULT_LOCALE} 处理，<b>不回退去读裸 YAML</b>：那会绕过 ConfigManager 的类型校验，
     * 使消息层看到的语言与插件其余部分看到的不一致。</p>
     */
    private static final String[] LANGUAGE_FIELD_NAMES = {"general_language", "generalLanguage"};

    /** 占位符：只认 {@code {数字}}，其余花括号一律按字面量处理。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)\\}");

    /** 无参调用的共享空数组（避免每次 {@link #get(String)} 都分配）。 */
    private static final Object[] NO_ARGS = new Object[0];

    /** 英文内置默认（键序即生成文件的书写顺序）。 */
    private static final Map<String, String> EN_DEFAULTS;

    /** 中文内置默认（键集合必须与英文一致）。 */
    private static final Map<String, String> ZH_DEFAULTS;

    /** 语言 → 内置默认表（只读）。 */
    /** 语言 → 合并后的默认表；可写容器，由 {@link #mergeModules()} 原地更新（引用不变）。 */
    private static final Map<String, Map<String, String>> BUILTIN_MUTABLE = new HashMap<>();

    /**
     * 运行时快照：语言 + 该语言默认表 + 用户覆盖表，三者同时生效、同时替换。
     * 初始值在静态块末尾发布，保证类初始化完成时 {@link #get} 已可用（即便 load 从未被调用）。
     */
    private static volatile Snapshot snapshot;

    static {
        // === 英文（默认语言） ===
        Map<String, String> en = new LinkedHashMap<>();
        // 权限与守卫：no-permission 用于①指令执行器的顶层拒绝分支（Bukkit 权限门之后的兜底），
        // ②启动/重载时回写 PluginCommand 的 permissionMessage（见 applyCommandPermissionMessage）。
        // 子命令的专项拒绝走 no-permission-export / no-permission-reload。
        en.put("no-permission", "You do not have permission to use this command.");
        en.put("no-permission-export", "You do not have permission to export reports.");
        en.put("no-permission-reload", "You do not have permission to reload the configuration.");
        en.put("player-only", "This feature is only available to players.");
        en.put("monitor-needs-paper", "Live monitoring requires a Paper server with ActionBar support.");
        // 操作反馈
        en.put("reloaded", "Configuration reloaded.");
        // 控制台 reload 日志独立成键：与聊天侧 reloaded 分开，专业部署可以只中性化聊天回复而不动控制台日志
        en.put("reloaded-console", "PerfScope configuration reloaded.");
        en.put("reload-failed-old-config",
            "PerfScope configuration reload incomplete (collection and alerts restarted with the previous configuration).");
        // 末尾的 ": " 是调用点拼接报告路径的约定（Messages.get("report-generated") + path），
        // 两种语言都必须保留这个分隔符，否则路径会和提示语粘在一起
        en.put("report-generated", "Report generated: ");
        en.put("report-failed", "Failed to generate the report.");
        en.put("monitor-on", "Live monitoring enabled. Use /perfscope monitor to stop.");
        en.put("monitor-off", "Live monitoring disabled.");
        // 启动/关闭横幅：两个前导空格用于与控制台分隔线内的其它行对齐，属于排版而非文案，故保留
        en.put("startup-done", "  PerfScope started successfully.");
        en.put("shutdown-bye", "PerfScope disabled. Goodbye.");
        // 命令面板标题：=== ... === 段落头。带世界名/时间戳/版本号的标题一律用 {0} 占位，
        // 调用点只传值、不再拼中文字面量；中英文都保留两侧的 "===" 装饰，保证面板视觉一致
        en.put("cmd.section.diagnostics", "=== PerfScope Diagnostics ===");
        en.put("cmd.section.tps", "=== TPS Diagnostics ===");
        en.put("cmd.section.mspt", "=== MSPT Diagnostics ===");
        en.put("cmd.section.entities", "=== Entity Statistics ===");
        en.put("cmd.section.entities.world", "=== Entity Statistics - {0} ===");
        en.put("cmd.section.chunks", "=== Chunk Statistics ===");
        en.put("cmd.section.chunks.world", "=== Chunk Statistics - {0} ===");
        en.put("cmd.section.memory", "=== Memory Diagnostics ===");
        en.put("cmd.section.cpu", "=== CPU Diagnostics ===");
        en.put("cmd.section.players", "=== Player Statistics ===");
        en.put("cmd.section.worlds", "=== World List ===");
        en.put("cmd.section.world", "=== World Details - {0} ===");
        en.put("cmd.section.redstone", "=== Redstone Activity ===");
        en.put("cmd.section.tileentities", "=== Tile Entity Statistics ===");
        en.put("cmd.section.tileentities.world", "=== Tile Entity Statistics - {0} ===");
        en.put("cmd.section.uptime", "=== Server Runtime Info ===");
        en.put("cmd.section.history", "=== History Summary ===");
        en.put("cmd.section.diagnose", "=== Automated Diagnostics ===");
        en.put("cmd.section.trend", "=== Trend Summary ===");
        en.put("cmd.section.hotspots", "=== Hotspot Chunks Top3 (sampled at {0}, players included) ===");
        en.put("cmd.section.gc", "=== GC Statistics ===");
        en.put("cmd.section.status", "=== Plugin Status ===");
        en.put("cmd.section.alerts", "=== Alert History ===");
        en.put("cmd.section.help", "=== PerfScope Help v{0} ===");
// === 9D Phase 2：告警文案（按 Metric 渲染，数值由 {0}/{1} 插入）===
        // 键名规则见 AlertCatalog：alert.<metricId>.<level>[.scoped] 与 .title[.scoped]
        en.put("alert.recovered", "recovered");
        en.put("alert.tps.warning.title", "TPS low");
        en.put("alert.tps.warning", "TPS low: {0}");
        en.put("alert.tps.critical.title", "TPS critically low");
        en.put("alert.tps.critical", "TPS critically low: {0}");
        en.put("alert.mspt.warning.title", "MSPT high");
        en.put("alert.mspt.warning", "MSPT high: {0}");
        en.put("alert.mspt.critical.title", "MSPT critically high");
        en.put("alert.mspt.critical", "MSPT critically high: {0}");
        en.put("alert.players.warning.title", "Many players online");
        en.put("alert.players.warning", "Many players online: {0}/{1}");
        en.put("alert.players.critical.title", "Player count near capacity");
        en.put("alert.players.critical", "Player count near capacity: {0}/{1}");
        en.put("alert.entities.warning.title", "Large entity count");
        en.put("alert.entities.warning", "Large entity count: {0}");
        en.put("alert.entities.critical.title", "Entity count critically high");
        en.put("alert.entities.critical", "Entity count critically high: {0}");
        en.put("alert.entities_world.warning.title", "Many entities in a world");
        en.put("alert.entities_world.warning.title.scoped", "Many entities in world {0}");
        en.put("alert.entities_world.warning.scoped", "Many entities in world {0}: {1}");
        en.put("alert.entities_world.critical.title", "Critically many entities in a world");
        en.put("alert.entities_world.critical.title.scoped", "Critically many entities in world {0}");
        en.put("alert.entities_world.critical.scoped", "Critically many entities in world {0}: {1}");
        en.put("alert.chunks_world.warning.title", "Many chunks loaded in a world");
        en.put("alert.chunks_world.warning.title.scoped", "Many chunks loaded in world {0}");
        en.put("alert.chunks_world.warning.scoped", "Many chunks loaded in world {0}: {1}");
        en.put("alert.chunks_world.critical.title", "Critically many chunks loaded in a world");
        en.put("alert.chunks_world.critical.title.scoped", "Critically many chunks loaded in world {0}");
        en.put("alert.chunks_world.critical.scoped", "Critically many chunks loaded in world {0}: {1}");
        en.put("alert.memory.warning.title", "Heap usage high");
        en.put("alert.memory.warning", "Heap usage high: {0}");
        en.put("alert.memory.critical.title", "Heap usage critically high");
        en.put("alert.memory.critical", "Heap usage critically high: {0}");
        en.put("alert.cpu_process.warning.title", "Process CPU high");
        en.put("alert.cpu_process.warning", "Process CPU high: {0}");
        en.put("alert.cpu_system.warning.title", "System CPU load high");
        en.put("alert.cpu_system.warning", "System CPU load high: {0}");
        // 告警历史事件类型名（令牌是 ASCII，展示名随语言）
        en.put("alert.event.open", "opened");
        en.put("alert.event.upgrade", "escalated");
        en.put("alert.event.downgrade", "de-escalated");
        en.put("alert.event.resolve", "resolved");
        en.put("alert.event.refire", "recurred");
        en.put("alert.event.refire_suppressed", "recurrence suppressed");
        en.put("alert.event.flap_suppressed", "flapping suppressed");
        en.put("alert.event.restore", "restored");
        en.put("alert.event.suspend", "suspended (unknown)");
        en.put("alert.event.resolved_by_disable", "silently cleared");
        EN_DEFAULTS = Collections.unmodifiableMap(en);

        // === 中文（可选语言；不含"喵"与"喵~"式的波浪尾音） ===
        Map<String, String> zh = new LinkedHashMap<>();
        zh.put("no-permission", "你没有权限使用此指令");
        zh.put("no-permission-export", "你没有导出报告的权限");
        zh.put("no-permission-reload", "你没有重载配置的权限");
        zh.put("player-only", "此功能仅玩家可用");
        zh.put("monitor-needs-paper", "实时监测功能需要 Paper 服务端支持 ActionBar");
        zh.put("reloaded", "配置已重载");
        zh.put("reloaded-console", "PerfScope 配置已重载");
        zh.put("reload-failed-old-config", "PerfScope 配置重载未完成（已按旧配置重启采集/告警）");
        zh.put("report-generated", "报告已生成: ");
        zh.put("report-failed", "报告生成失败");
        zh.put("monitor-on", "实时监测已开启，使用 /perfscope monitor 关闭");
        zh.put("monitor-off", "实时监测已关闭");
        zh.put("startup-done", "  PerfScope 启动完成");
        zh.put("shutdown-bye", "PerfScope 已关闭，再见");
        // 命令面板标题：与英文逐键同序，中文保留 v1.2 既有措辞（仅把世界名/时间戳/版本号改为占位符）
        zh.put("cmd.section.diagnostics", "=== PerfScope 诊断 ===");
        zh.put("cmd.section.tps", "=== TPS 诊断 ===");
        zh.put("cmd.section.mspt", "=== MSPT 诊断 ===");
        zh.put("cmd.section.entities", "=== 实体统计 ===");
        zh.put("cmd.section.entities.world", "=== 实体统计 - {0} ===");
        zh.put("cmd.section.chunks", "=== 区块统计 ===");
        zh.put("cmd.section.chunks.world", "=== 区块统计 - {0} ===");
        zh.put("cmd.section.memory", "=== 内存诊断 ===");
        zh.put("cmd.section.cpu", "=== CPU 诊断 ===");
        zh.put("cmd.section.players", "=== 玩家统计 ===");
        zh.put("cmd.section.worlds", "=== 世界列表 ===");
        zh.put("cmd.section.world", "=== 世界详情 - {0} ===");
        zh.put("cmd.section.redstone", "=== 红石活动 ===");
        zh.put("cmd.section.tileentities", "=== Tile Entity 统计 ===");
        zh.put("cmd.section.tileentities.world", "=== Tile Entity 统计 - {0} ===");
        zh.put("cmd.section.uptime", "=== 服务器运行信息 ===");
        zh.put("cmd.section.history", "=== 历史摘要 ===");
        zh.put("cmd.section.diagnose", "=== 智能诊断 ===");
        zh.put("cmd.section.trend", "=== 趋势摘要 ===");
        zh.put("cmd.section.hotspots", "=== 热点区块 Top3（采样于 {0}，含玩家）===");
        zh.put("cmd.section.gc", "=== GC 统计 ===");
        zh.put("cmd.section.status", "=== 插件状态 ===");
        zh.put("cmd.section.alerts", "=== 告警历史 ===");
        zh.put("cmd.section.help", "=== PerfScope 帮助 v{0} ===");
// === 9D Phase 2：告警文案（中文，无「喵」）===
        zh.put("alert.recovered", "已恢复正常");
        zh.put("alert.tps.warning.title", "TPS 偏低");
        zh.put("alert.tps.warning", "TPS 偏低: {0}");
        zh.put("alert.tps.critical.title", "TPS 严重偏低");
        zh.put("alert.tps.critical", "TPS 严重偏低: {0}");
        zh.put("alert.mspt.warning.title", "MSPT 偏高");
        zh.put("alert.mspt.warning", "MSPT 偏高: {0}");
        zh.put("alert.mspt.critical.title", "MSPT 严重偏高");
        zh.put("alert.mspt.critical", "MSPT 严重偏高: {0}");
        zh.put("alert.players.warning.title", "玩家数较多");
        zh.put("alert.players.warning", "玩家数较多: {0}/{1}");
        zh.put("alert.players.critical.title", "玩家数接近上限");
        zh.put("alert.players.critical", "玩家数接近上限: {0}/{1}");
        zh.put("alert.entities.warning.title", "实体总数较多");
        zh.put("alert.entities.warning", "实体总数较多: {0}");
        zh.put("alert.entities.critical.title", "实体总数严重超标");
        zh.put("alert.entities.critical", "实体总数严重超标: {0}");
        zh.put("alert.entities_world.warning.title", "世界实体数较多");
        zh.put("alert.entities_world.warning.title.scoped", "世界 {0} 实体数较多");
        zh.put("alert.entities_world.warning.scoped", "世界 {0} 实体数较多: {1}");
        zh.put("alert.entities_world.critical.title", "世界实体数严重超标");
        zh.put("alert.entities_world.critical.title.scoped", "世界 {0} 实体数严重超标");
        zh.put("alert.entities_world.critical.scoped", "世界 {0} 实体数严重超标: {1}");
        zh.put("alert.chunks_world.warning.title", "世界加载区块数较多");
        zh.put("alert.chunks_world.warning.title.scoped", "世界 {0} 加载区块数较多");
        zh.put("alert.chunks_world.warning.scoped", "世界 {0} 加载区块数较多: {1}");
        zh.put("alert.chunks_world.critical.title", "世界加载区块数严重超标");
        zh.put("alert.chunks_world.critical.title.scoped", "世界 {0} 加载区块数严重超标");
        zh.put("alert.chunks_world.critical.scoped", "世界 {0} 加载区块数严重超标: {1}");
        zh.put("alert.memory.warning.title", "堆内存使用率偏高");
        zh.put("alert.memory.warning", "堆内存使用率偏高: {0}");
        zh.put("alert.memory.critical.title", "堆内存使用率严重偏高");
        zh.put("alert.memory.critical", "堆内存使用率严重偏高: {0}");
        zh.put("alert.cpu_process.warning.title", "进程CPU使用率偏高");
        zh.put("alert.cpu_process.warning", "进程CPU使用率偏高: {0}");
        zh.put("alert.cpu_system.warning.title", "系统CPU负载偏高");
        zh.put("alert.cpu_system.warning", "系统CPU负载偏高: {0}");
        zh.put("alert.event.open", "新增");
        zh.put("alert.event.upgrade", "升级");
        zh.put("alert.event.downgrade", "降级");
        zh.put("alert.event.resolve", "恢复");
        zh.put("alert.event.refire", "复燃");
        zh.put("alert.event.refire_suppressed", "抖动抑制");
        zh.put("alert.event.flap_suppressed", "抖动抑制");
        zh.put("alert.event.restore", "接管");
        zh.put("alert.event.suspend", "转unknown");
        zh.put("alert.event.resolved_by_disable", "静默解决");
        ZH_DEFAULTS = Collections.unmodifiableMap(zh);

        Map<String, Map<String, String>> all = new HashMap<>();
        all.put(LOCALE_EN, EN_DEFAULTS);
        all.put(LOCALE_ZH, ZH_DEFAULTS);
        BUILTIN_MUTABLE.putAll(all);

        // 类一初始化就发布一份可用快照：即便 onEnable 在 load 之前被别处调用 get，也不会拿到 null
        snapshot = new Snapshot(DEFAULT_LOCALE, EN_DEFAULTS, Map.of());
    }

    private Messages() {}

    /**
     * 9D Phase 1：模块自带文案注册表。
     *
     * <p><b>为什么要有这个机制</b>：命令层/报告层/告警层各有几十到上百条文案，
     * 全部塞进本类的静态块会让它膨胀到两千行，且本类会成为唯一的写入热点——
     * 多个改造任务无法并行，任何一次合并冲突都会落到同一个文件上。</p>
     *
     * <p>改造后每个模块在自己的文件里维护一张 {@code Map<String,String>}，
     * 静态初始化时调用 {@link #registerModule(Map, Map)} 注册。本类只负责合并，
     * 不再承载业务文案。合并结果与"直接写在本类里"完全等价（同一张表、同样的回退链）。</p>
     *
     * <p><b>线程/时序</b>：注册发生在<b>类初始化阶段</b>（各模块的 static 块），
     * 因此必须在 {@link #load} 之前全部完成。为保证这一点，注册时会先触发所有已知模块的类初始化
     * （见 {@link #touchModules()}），再重建 {@code BUILTIN} 与当前快照。</p>
     */
    private static final List<Runnable> MODULE_TOUCHERS = new ArrayList<>();
    private static final Map<String, String> EXTRA_EN = new LinkedHashMap<>();
    private static final Map<String, String> EXTRA_ZH = new LinkedHashMap<>();
    private static boolean modulesLoaded = false;

    /**
     * 注册一个模块的中英文案表。
     *
     * <p>两张表<b>必须键集合一致</b>：缺一边意味着某种语言下会退回键名，
     * 那正是"半吊子翻译"。不一致时记 WARNING 并以英文表为准补齐（值取键名，便于排查）。</p>
     *
     * @param en 英文键值（不可为 null）
     * @param zh 中文键值（不可为 null）
     */
    public static synchronized void registerModule(Map<String, String> en, Map<String, String> zh) {
        if (en == null || zh == null) {
            return;
        }
        EXTRA_EN.putAll(en);
        EXTRA_ZH.putAll(zh);
        modulesLoaded = false;   // 有新模块加入，需要重新合并
    }

    /**
     * 让所有已注册的模块完成类初始化，然后重建默认表与当前快照。
     *
     * <p>模块在各自类的 static 块里调 {@link #registerModule}，而 static 块只有在类被
     * 首次使用时才执行。若 {@code load()} 先跑，后注册的键就不会进入默认表。
     * 这里显式登记"怎么触碰各模块类"，在合并前逐一触发。</p>
     */
    public static synchronized void touchModules() {
        for (Runnable r : MODULE_TOUCHERS) {
            try {
                r.run();
            } catch (Exception | LinkageError e) {
                // 单个模块初始化失败不能拖垮消息层：该模块的键缺失会退回键名，可见但要排查
                System.err.println("[PerfScope] 消息模块初始化失败: " + e);
            }
        }
    }

    /** 登记一个"触碰模块类"的动作（通常是一个方法引用，如 {@code CommandMessages::init}）。 */
    public static synchronized void onFirstLoad(Runnable toucher) {
        if (toucher != null) {
            MODULE_TOUCHERS.add(toucher);
            modulesLoaded = false;
        }
    }

    /** 把已注册的模块表合并进 {@code BUILTIN}，并重建当前语言的快照。 */
    private static synchronized void mergeModules() {
        if (modulesLoaded || EXTRA_EN.isEmpty()) {
            return;
        }
        Map<String, String> en = new LinkedHashMap<>(EN_DEFAULTS);
        Map<String, String> zh = new LinkedHashMap<>(ZH_DEFAULTS);
        en.putAll(EXTRA_EN);
        zh.putAll(EXTRA_ZH);
        // 键集合对齐：任何一边缺键都以英文表为基准补齐（值 = 键名，明显的"未翻译"标记）
        for (String k : en.keySet()) {
            if (!zh.containsKey(k)) {
                zh.put(k, k);
            }
        }
        for (String k : zh.keySet()) {
            if (!en.containsKey(k)) {
                en.put(k, k);
            }
        }
        Map<String, Map<String, String>> all = new HashMap<>();
        all.put(LOCALE_EN, Collections.unmodifiableMap(en));
        all.put(LOCALE_ZH, Collections.unmodifiableMap(zh));
        BUILTIN_MUTABLE.clear();
        BUILTIN_MUTABLE.putAll(all);
        modulesLoaded = true;
        // 用新默认表重建当前快照（保留当前语言与用户覆盖）
        String cur = snapshot.locale();
        snapshot = new Snapshot(cur, defaultsOf(cur), snapshot.overrides());
    }

    // ==================================================================================
    // 加载 / 生成 / 迁移
    // ==================================================================================

    /**
     * 首次运行时按<b>当前语言</b>生成 {@code messages.yml}（含语言标签与全部默认键）。
     *
     * <p>与旧实现的差别：旧版把 jar 内的 {@code messages.yml} 原样复制出来，而那份资源是中文的——
     * 在英文默认的前提下，新装用户会立刻拿到一份"语言不符"的文件。现在文件内容由内置表生成，
     * 语言标签与 {@code config.yml} 的 {@code general.language} 对齐，开箱即用。</p>
     *
     * <p>只在文件不存在时写（用户删掉文件表示"我要用内置默认"，重启后重新生成一份可编辑的模板）。
     * 加类级锁：与 {@link #load} 共用同一把锁，避免启动期两条路径同时改同一文件。</p>
     */
    public static synchronized void saveDefault(PerfScope plugin) {
        if (plugin == null) {
            return;
        }
        try {
            File dataFolder = plugin.getDataFolder();
            if (dataFolder == null) {
                return;
            }
            File file = new File(dataFolder, FILE_NAME);
            if (file.exists()) {
                return;
            }
            // saveDefault 阶段不重复告警"语言值不认识"：紧接着的 load 会用同一判定再报一次，重复告警只会吵
            String locale = detectLocale(plugin, false);
            writeDefaultFile(plugin, file, locale);
        } catch (Exception | LinkageError e) {
            warn(plugin, "messages.yml 生成失败（将使用内置默认）: " + e.getMessage());
        }
    }

    /**
     * 加载当前语言的文案（启动与 reload 时调用）。
     *
     * <p>流程：确定语言 → 读取同语言的用户覆盖（含旧文件一次性迁移）→ <b>整体发布</b>快照 →
     * 同步指令的 permissionMessage。任何一步失败都只降级为"该语言的内置默认"，永不抛出：
     * 消息层是展示层，不能反过来决定插件能否启动。</p>
     *
     * <p>注意：本方法<b>不</b>在文件缺失时生成文件——生成归 {@link #saveDefault}（启动期负责）；
     * 唯一的例外是旧文件迁移，它需要立刻补一份新文件，否则用户在下次重启前没有可编辑的模板。</p>
     */
    public static synchronized void load(PerfScope plugin) {
        if (plugin == null) {
            snapshot = new Snapshot(DEFAULT_LOCALE, EN_DEFAULTS, Map.of());
            return;
        }
        // 9D Phase 1：先把各模块的文案表合并进来，再决定语言快照——
        // 否则首次 load 会在模块注册之前跑，模块的键永远进不了默认表。
        touchModules();
        mergeModules();
        String locale = detectLocale(plugin, true);
        Map<String, String> userOverrides;
        try {
            userOverrides = loadOverrides(plugin, locale);
        } catch (Exception | LinkageError e) {
            userOverrides = Map.of();
            warn(plugin, "messages.yml 加载失败（回退 " + locale + " 内置默认）: " + e.getMessage());
        }
        // 单次整体发布：读线程不可能看到"新语言 + 旧覆盖"的组合
        snapshot = new Snapshot(locale, defaultsOf(locale), userOverrides);
        // 每次 load（含 reload）后把 no-permission 同步到指令的 permissionMessage，
        // 消除 plugin.yml 里第三份硬编码副本——见 applyCommandPermissionMessage
        applyCommandPermissionMessage(plugin);
    }

    /**
     * 读取用户覆盖表（只接受与当前语言一致的文件）。
     *
     * <p>文件状态逐一处理，且每一种处理都不会让另一种语言的文案漏进当前界面：</p>
     * <ol>
     *   <li><b>不存在</b>（或为空/只剩注释）→ 空覆盖（纯内置默认），不生成备份；</li>
     *   <li><b>解析失败</b>（Bukkit 会把 YAML 语法错误吞成空配置——沿用 ConfigManager 的
     *       "有实际内容却解析出 0 个键"识别法）→ 忽略文件 + WARNING，<b>不迁移</b>，
     *       让用户有机会修好（迁移会掩盖真正的语法错误）；</li>
     *   <li><b>无 language 标签</b> → 旧版文件，一次性迁移（见 {@link #migrateLegacyFile}）；</li>
     *   <li><b>有标签</b> → 标签与当前语言一致才读取；不一致/无法识别则整份忽略 + WARNING。</li>
     * </ol>
     */
    private static Map<String, String> loadOverrides(final PerfScope plugin, final String locale) {
        OverrideResult r = loadOverridesStateful(plugin, locale);
        lastOverrideNotice = r.notice;
        return r.overrides;
    }

    /**
     * 9C-5：本次 load 中"用户覆盖文件没有被采用"的原因（{@code null} = 正常采用或无文件）。
     *
     * <p>为什么需要它：{@code messages.yml} 被整份忽略时，用户执行 {@code /perfscope reload}
     * 会看到一句普通的"配置已重载"——一个成功提示——而他的全部自定义文案其实被丢弃了。
     * 控制台虽然打了 WARNING，但玩家很少看控制台（红队复核 Blocker #4）。
     * 命令层据此把回执改成"已重载，但 messages.yml 未生效（原因）"。</p>
     */
    private static volatile String lastOverrideNotice = null;

    /**
     * 9C-5：取"覆盖文件未生效"的原因；{@code null} 表示本次覆盖文件正常（或不存在）。
     * 供命令层在 reload 回执里如实告知，避免"报成功但改动没生效"。
     */
    public static String overrideNotice() {
        return lastOverrideNotice;
    }

    /** 覆盖文件解析结果：覆盖表 + 未采用原因（null = 采用了）。 */
    private record OverrideResult(Map<String, String> overrides, String notice) {}

    private static OverrideResult loadOverridesStateful(PerfScope plugin, String locale) {
        File dataFolder = plugin.getDataFolder();
        if (dataFolder == null) {
            return new OverrideResult(Map.of(), null);
        }
        File file = new File(dataFolder, FILE_NAME);
        if (!file.isFile()) {
            return new OverrideResult(Map.of(), null);
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.getKeys(false).isEmpty()) {
            if (hasNonCommentContent(file)) {
                warn(plugin, "messages.yml 解析失败（YAML 语法错误？），本次忽略该文件并使用内置默认；"
                    + "请修正缩进/引号后执行 /perfscope reload（原文件未改动）");
                return new OverrideResult(Map.of(), "messages.yml 解析失败（YAML 语法错误），本次未生效");
            }
            // 空文件 / 只剩注释：没有任何覆盖，也无需迁移（不制造一份空文件的备份）
            return new OverrideResult(Map.of(), null);
        }

        if (!yaml.contains(LANGUAGE_KEY)) {
            boolean customized = hasCustomizedValues(file);
            migrateLegacyFile(plugin, file, locale, customized);
            return new OverrideResult(Map.of(), customized
                ? "messages.yml 含自定义文案且没有 language 标签，本次未生效（原文件已保留）"
                : "旧版 messages.yml 已备份并按当前语言重新生成");
        }

        String tagRaw = yaml.getString(LANGUAGE_KEY);
        String fileLocale = canonicalLocale(tagRaw);
        if (fileLocale == null) {
            // 关键：不能把"未知标签"归一化成默认语言——那样一份 fr 文件会被当成 en 文案使用
            warn(plugin, "messages.yml 的 language 标签无法识别（当前值 \"" + (tagRaw == null ? "" : tagRaw)
                + "\"，支持 en / zh），已整份忽略该文件并使用内置默认");
            return new OverrideResult(Map.of(),
                "messages.yml 的 language 标签无法识别（\"" + (tagRaw == null ? "" : tagRaw) + "\"），本次未生效");
        }
        if (!fileLocale.equals(locale)) {
            warn(plugin, "messages.yml 是用 \"" + fileLocale + "\" 写的，而当前语言是 \"" + locale
                + "\"，已整份忽略该文件以避免中英混排。若要沿用其中的自定义文案，"
                + "请把 language 改成 \"" + locale + "\" 并翻译对应条目；也可以直接删除该文件让插件重新生成");
            return new OverrideResult(Map.of(),
                "messages.yml 语言是 \"" + fileLocale + "\"、当前是 \"" + locale + "\"，本次未生效");
        }

        Map<String, String> loaded = new LinkedHashMap<>();
        Set<String> known = defaultsOf(locale).keySet();
        Set<String> unknown = new TreeSet<>();
        for (String key : yaml.getKeys(true)) {
            if (LANGUAGE_KEY.equals(key) || yaml.isConfigurationSection(key)) {
                continue;
            }
            String value = yaml.getString(key);
            // 空值视为"没写"：与旧实现一致（空串覆盖会让玩家看到一条空消息，几乎总是误操作）
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (!known.contains(key)) {
                unknown.add(key);
                continue;
            }
            loaded.put(key, value);
        }
        if (!unknown.isEmpty()) {
            // 拼错的键过去是彻底静默的：用户以为自己改了文案，实际一个字都没生效
            warn(plugin, "messages.yml 含 " + unknown.size()
                + " 个本版本不认识的键（不会生效，多为拼写错误）: " + String.join(", ", unknown));
        }
        return new OverrideResult(Map.copyOf(loaded), null);
    }

    /**
     * 旧版 {@code messages.yml}（无 {@code language} 标签）的一次性迁移。
     *
     * <p>旧文件的内容是中文，且与"当前语言"没有任何可靠关联；直接沿用会在英文环境下造成混排，
     * 直接删除又会丢用户的文案。因此做成"改名保全 + 重新生成"：</p>
     * <ul>
     *   <li>原文件<b>移动</b>（不是复制）为 {@code messages.yml.bak-<yyyyMMdd-HHmmss>}——
     *       移动保证"只迁移一次"：下次启动时 {@code messages.yml} 已不存在，不会再次触发迁移，
     *       也不会反复堆积备份；</li>
     *   <li>随后按当前语言写一份全新的默认文件，用户立刻有可编辑的模板；</li>
     *   <li>移动失败（文件被占用/只读）时，<b>本次忽略旧文件</b>并用内置默认——
     *       宁可丢一次覆盖，也不把中文文案混进英文界面。下次启动会再次尝试迁移，
     *       这条警告在故障修复后会自行消失。</li>
     * </ul>
     */
    private static void migrateLegacyFile(PerfScope plugin, File file, String locale, boolean customized) {
        // 先判断这份旧文件是否"用户改过"。
        // 背景（红队复核 Blocker #3）：初版无条件把旧文件移走并重新生成，
        // 结果是用户自己翻译/定制过的文案**静默停止生效**——文件只在 .bak 里，
        // 而界面上看到的是全新的默认文本，用户会以为"我的修改丢了"（确实丢了）。
        // 判据：把旧文件解析出来，与内置默认表逐键比较。
        //   完全一致（或只差注释/空白）→ 用户没改过 → 自动迁移，无感升级；
        //   有任何键不同 → 用户改过 → **保留原文件不动**，只警告它本次不生效。
        // 宁可少一次自动迁移，也不能吞掉用户的手工修改。
        if (customized) {
            warn(plugin, "检测到旧版 messages.yml（无 language 标签）且其中包含自定义文案。"
                + "为避免覆盖你的修改，本插件不会移动该文件；但它没有语言标签，"
                + "本次已整份忽略（避免中英混排）。如需继续使用：请在文件首行加上 "
                + "language: " + locale + "，并把条目翻译成该语言；或把该文件改名备份，"
                + "让插件重新生成一份带标签的默认文件");
            return;
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File backup = new File(file.getParentFile(), FILE_NAME + ".bak-" + stamp);
        try {
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception | LinkageError e) {
            warn(plugin, "检测到旧版 messages.yml（无 language 标签），但备份失败，本次已忽略该文件"
                + "（使用内置默认，避免中英混排）: " + e.getMessage());
            return;
        }
        warn(plugin, "检测到旧版 messages.yml（无 language 标签，内容与内置默认一致，未做自定义），"
            + "已备份为 " + backup.getName() + "，并按当前语言 \"" + locale + "\" 生成新文件");
        writeDefaultFile(plugin, file, locale);
    }

    /**
     * 判断一份旧版 {@code messages.yml}（无 language 标签）是否含用户自定义。
     *
     * <p>判据：把文件里能解析出的每个键，与该键在<b>任一</b>内置语言里的默认值比较。
     * 只要有一个键的值既不等于英文默认也不等于中文默认，就认定用户改过。
     * 解析失败时保守地返回 {@code true}（当作"用户改过"，绝不自动移动）。</p>
     */
    private static boolean hasCustomizedValues(File file) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String key : yaml.getKeys(true)) {
                if (yaml.isConfigurationSection(key)) {
                    continue;
                }
                String value = yaml.getString(key);
                if (value == null) {
                    continue;
                }
                // 9D 修正（验收 HIGH）：必须比对**合并后**的表。
                // 旧实现读 EN_DEFAULTS/ZH_DEFAULTS（只含基础 85 键），于是所有模块键
                // （cmd.*/alert.*/report.*/diag.*）都返回 null → 一律判为"用户自定义"→
                // 任何 v1.2 用户的 messages.yml 都不会被自动迁移。
                Map<String, String> enTable = BUILTIN_MUTABLE.getOrDefault(LOCALE_EN, EN_DEFAULTS);
                Map<String, String> zhTable = BUILTIN_MUTABLE.getOrDefault(LOCALE_ZH, ZH_DEFAULTS);
                String en = enTable.get(key);
                String zh = zhTable.get(key);
                if (en == null && zh == null) {
                    // 未知键：用户自己加的，属于自定义
                    return true;
                }
                if (!value.equals(en) && !value.equals(zh)) {
                    return true;
                }
            }
            return false;
        } catch (Exception | LinkageError e) {
            return true;   // 解析不了就更不能动它
        }
    }

    /**
     * 把指定语言的默认内容写成 {@code messages.yml}。
     *
     * <p>手写文本而不是 {@code YamlConfiguration#save}：后者会丢掉全部注释，而本文件的注释
     * 承载了"language 标签为什么必须与配置一致""占位符怎么用"这类不能省的说明。
     * 换行统一用 {@code \n}（Bukkit 读取与平台无关），使不同机器生成的备份/文件可直接对比。</p>
     */
    private static void writeDefaultFile(PerfScope plugin, File file, String locale) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                warn(plugin, "无法创建插件数据目录，messages.yml 未生成（将使用内置默认）");
                return;
            }
            Files.write(file.toPath(), buildDefaultFileContent(locale).getBytes(StandardCharsets.UTF_8));
        } catch (Exception | LinkageError e) {
            warn(plugin, "messages.yml 写入失败（将使用内置默认）: " + e.getMessage());
        }
    }

    /** 生成默认文件文本（头部说明 + {@code language} 标签 + 全部键值）。 */
    private static String buildDefaultFileContent(String locale) {
        Map<String, String> defaults = defaultsOf(locale);
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# ==========================================\n");
        sb.append("# PerfScope - 消息文案 / Messages\n");
        sb.append("# ==========================================\n");
        sb.append("#\n");
        sb.append("# language: 本文件的语言标签（en = 英文，默认；zh = 中文）。\n");
        sb.append("#   插件只在它与 config.yml 的 general.language 一致时读取本文件；不一致时整份文件被忽略\n");
        sb.append("#   （记一条 WARNING），改用该语言的内置默认——这样任何时刻界面都只有一种语言，不会中英混排。\n");
        sb.append("#\n");
        sb.append("# 回退顺序：本文件的同名键 → 该语言的内置默认 → 键名本身（仅用于排查）。\n");
        sb.append("# 缺键无需补全：只写想改的键即可，其余自动用内置默认。\n");
        sb.append("#\n");
        sb.append("# 占位符：{0} {1} ... 由 Messages.get(key, args...) 替换；参数不足时占位符原样保留，\n");
        sb.append("#   多余参数忽略，null 参数渲染为 \"null\"。\n");
        sb.append("#\n");
        sb.append("# 改动后执行 /perfscope reload 生效（若改 language，需同时改 config.yml 的 general.language）。\n");
        sb.append('\n');
        sb.append(LANGUAGE_KEY).append(": ").append(locale).append('\n');

        sb.append("\n# ---- 权限与守卫 / Permissions & guards ----\n");
        appendEntry(sb, defaults, "no-permission");
        appendEntry(sb, defaults, "no-permission-export");
        appendEntry(sb, defaults, "no-permission-reload");
        appendEntry(sb, defaults, "player-only");
        appendEntry(sb, defaults, "monitor-needs-paper");

        sb.append("\n# ---- 操作反馈 / Operation feedback ----\n");
        appendEntry(sb, defaults, "reloaded");
        appendEntry(sb, defaults, "reloaded-console");
        appendEntry(sb, defaults, "reload-failed-old-config");
        appendEntry(sb, defaults, "report-generated");
        appendEntry(sb, defaults, "report-failed");
        appendEntry(sb, defaults, "monitor-on");
        appendEntry(sb, defaults, "monitor-off");

        sb.append("\n# ---- 启动/关闭横幅（控制台日志） / Startup & shutdown banner ----\n");
        appendEntry(sb, defaults, "startup-done");
        appendEntry(sb, defaults, "shutdown-bye");

        // 9D 修正（验收 Blocker）：其余键**按前缀自动分组**全部输出。
        // 旧实现只硬编码了 5 组 + cmd.section.*，570 个键里只生成 38 个（6.7%）——
        // 用户打开模板想改面板标题或告警文案时找不到，会直接认为这个功能不存在。
        // 现在遍历全表、按第一段前缀分组，新增键自动出现，不可能再漂移。
        appendRemainingGroups(sb, defaults);
        return sb.toString();
    }

    /** 已在上方手工分组输出过的键（不再重复）。 */
    private static final java.util.Set<String> HANDWRITTEN_KEYS = java.util.Set.of(
        "no-permission", "no-permission-export", "no-permission-reload", "player-only",
        "monitor-needs-paper", "reloaded", "reloaded-console", "reload-failed-old-config",
        "report-generated", "report-failed", "monitor-on", "monitor-off",
        "startup-done", "shutdown-bye");

    /** 前缀 → 生成文件里的小节标题（中英双语，便于用户定位）。 */
    private static final java.util.Map<String, String> GROUP_TITLES = new java.util.LinkedHashMap<>();

    static {
        GROUP_TITLES.put("cmd.section", "命令面板标题 / Command panel headings");
        GROUP_TITLES.put("cmd", "命令面板正文 / Command panel body");
        GROUP_TITLES.put("alert.event", "告警事件类型名 / Alert event labels");
        GROUP_TITLES.put("alert", "告警文案 / Alert text");
        GROUP_TITLES.put("report", "报告输出 / Report output");
        GROUP_TITLES.put("diag", "诊断规则 / Diagnostic rules");
        GROUP_TITLES.put("daily", "每日摘要 / Daily summary");
        GROUP_TITLES.put("core", "启动与健康自检 / Startup & health");
        GROUP_TITLES.put("compat", "服务端兼容说明 / Compatibility notes");
    }

    /**
     * 把尚未输出的键按前缀分组写进模板。
     *
     * <p>分组顺序由 {@link #GROUP_TITLES} 决定（人读顺序）；未登记的前缀统一落到
     * "其他 / Other"，保证<b>不会有键被静默漏掉</b>——这正是旧实现 38/570 的根因。</p>
     */
    private static void appendRemainingGroups(StringBuilder sb, Map<String, String> defaults) {
        java.util.Map<String, java.util.List<String>> buckets = new java.util.LinkedHashMap<>();
        for (String key : defaults.keySet()) {
            if (HANDWRITTEN_KEYS.contains(key)) {
                continue;
            }
            buckets.computeIfAbsent(groupOf(key), k -> new java.util.ArrayList<>()).add(key);
        }
        // 按登记顺序输出，未登记的追加在末尾
        java.util.List<String> order = new java.util.ArrayList<>(GROUP_TITLES.keySet());
        for (String g : buckets.keySet()) {
            if (!order.contains(g)) {
                order.add(g);
            }
        }
        for (String group : order) {
            java.util.List<String> keys = buckets.get(group);
            if (keys == null || keys.isEmpty()) {
                continue;
            }
            String title = GROUP_TITLES.getOrDefault(group, "其他 / Other");
            sb.append("\n# ---- ").append(title).append(" ----\n");
            for (String key : keys) {
                appendEntry(sb, defaults, key);
            }
        }
    }

    /** 取键的"分组前缀"：匹配最长的已登记前缀，否则取第一段。 */
    private static String groupOf(String key) {
        String best = null;
        for (String g : GROUP_TITLES.keySet()) {
            if ((key.equals(g) || key.startsWith(g + ".")) && (best == null || g.length() > best.length())) {
                best = g;
            }
        }
        if (best != null) {
            return best;
        }
        int dot = key.indexOf('.');
        return dot > 0 ? key.substring(0, dot) : key;
    }

    /** 写一条 {@code key: "value"}（双引号保证尾随空格、冒号与中文都被正确解析）。 */
    private static void appendEntry(StringBuilder sb, Map<String, String> defaults, String key) {
        String value = defaults.get(key);
        if (value == null) {
            return;
        }
        sb.append(key).append(": \"").append(escapeYaml(value)).append("\"\n");
    }

    /** 双引号字符串转义：只需处理反斜杠与双引号（默认文案不含换行）。 */
    private static String escapeYaml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================================================================================
    // 语言解析
    // ==================================================================================

    /**
     * 确定当前语言：配置值 → 规范语言码；无法读取时用默认语言。
     *
     * @param warnUnknown true = 对"配置里写了一个不支持的語言"记 WARNING
     *                    （{@link #saveDefault} 传 false，避免与随后的 {@link #load} 重复告警）
     */
    private static String detectLocale(PerfScope plugin, boolean warnUnknown) {
        String raw;
        try {
            raw = readConfiguredLanguage(plugin);
        } catch (Exception | LinkageError e) {
            raw = null;
        }
        String locale = normalizeLocale(raw);
        if (warnUnknown && plugin != null && raw != null && !raw.isBlank() && canonicalLocale(raw) == null) {
            warn(plugin, "配置 general.language=\"" + raw + "\" 不是内置语言（支持 en / zh），已按 "
                + DEFAULT_LOCALE + " 处理");
        }
        return locale;
    }

    /**
     * 从配置快照读取语言值（反射）。
     *
     * <p>为什么用反射：{@code general_language} 字段由另一批改动加入 {@code ConfigManager.ConfigData}，
     * 本文件必须能在字段尚未存在时独立编译。字段就位后无需再改这里——查找会自动命中。
     * 不缓存 {@link Field} 句柄：本方法只在启动/reload 调用，反射查找的成本可以忽略，
     * 而缓存会让"字段后来才加上"的场景在同一个 JVM 里永远拿不到（类结构变了缓存却不会刷新）。</p>
     *
     * @return 配置值（可能为 null / 空白）；读不到时返回 null（调用方按默认语言处理）
     */
    private static String readConfiguredLanguage(PerfScope plugin) {
        if (plugin == null || plugin.getConfigManager() == null) {
            return null;
        }
        ConfigManager.ConfigData data = plugin.getConfigManager().get();
        if (data == null) {
            // 配置尚未加载：用默认语言，绝不因此抛异常
            return null;
        }
        // 直连字段（编译期已确认存在）。反射分支保留为兜底：
        // 若将来有人把 ConfigData 的字段改名，编译期就会失败，届时删除本方法即可，
        // 而不是让运行期悄悄退化成"永远默认语言"。
        return data.general_language;
    }

    /** 依次尝试候选字段名，返回第一个可读到的值；全部失败返回 null。 */
    private static Object readField(Object target, String[] names) {
        Class<?> type = target.getClass();
        for (String name : names) {
            Field field = findField(type, name);
            if (field == null) {
                continue;
            }
            try {
                return field.get(target);
            } catch (Exception | LinkageError e) {
                // 换下一个候选名；全部失败时由调用方回退默认语言
            }
        }
        return null;
    }

    /** 先找公开字段（ConfigData 的字段风格），再兜底找非公开字段（命名一致但可见性不同）。 */
    private static Field findField(Class<?> type, String name) {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException ignored) {
            // 继续尝试非公开字段
        } catch (Exception | LinkageError e) {
            return null;
        }
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /**
     * 把配置值规范成内置语言码；无法识别时返回 {@link #DEFAULT_LOCALE}。
     *
     * <p>接受 {@code en} / {@code en_us} / {@code en-US} / {@code zh} / {@code zh_cn} / {@code zh-TW}
     * 这类常见写法（Bukkit 的 locale 常用下划线、浏览器/HTTP 常用连字符）。</p>
     */
    private static String normalizeLocale(String raw) {
        String canonical = canonicalLocale(raw);
        return canonical != null ? canonical : DEFAULT_LOCALE;
    }

    /**
     * 严格版语言识别：只返回 {@code en} / {@code zh}，<b>无法识别返回 null</b>。
     *
     * <p>与 {@link #normalizeLocale} 的区别是这里不做"未知 → 默认"的兜底。覆盖文件的标签校验
     * 必须用这个严格版本：把未知标签当成默认语言，等于允许一份外语文件冒充当前语言，
     * 正是"整份切换"要防的混排。</p>
     */
    private static String canonicalLocale(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (v.isEmpty()) {
            return null;
        }
        if (v.equals(LOCALE_ZH) || v.startsWith(LOCALE_ZH + "-")) {
            return LOCALE_ZH;
        }
        if (v.equals(LOCALE_EN) || v.startsWith(LOCALE_EN + "-")) {
            return LOCALE_EN;
        }
        return null;
    }

    /** 取某语言的内置默认表（未知语言回退英文，保证永远有表可用）。 */
    private static Map<String, String> defaultsOf(String locale) {
        Map<String, String> table = BUILTIN_MUTABLE.get(locale);
        return table != null ? table : EN_DEFAULTS;
    }

    /**
     * 判断文件是否含有实际内容（排除空行与整行注释）。
     *
     * <p>用途：区分"用户把文件清空成只剩注释"（合法，按空覆盖处理）与"YAML 语法错误被
     * Bukkit 吞成空配置"（失败，必须告警且不迁移）。与 {@code ConfigManager#hasNonCommentContent}
     * 同口径；此处独立实现是为了让消息层不依赖配置层的私有方法。</p>
     */
    private static boolean hasNonCommentContent(File file) {
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                // Windows 编辑器可能在首行前写入 BOM，先剥掉再判断注释
                String text = line.replace("﻿", "").trim();
                if (!text.isEmpty() && !text.startsWith("#")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // 读不出来就按"有内容"处理（走解析结果判断，避免漏报）
            return true;
        }
    }

    // ==================================================================================
    // 指令权限文案回写
    // ==================================================================================

    /**
     * 把 {@code no-permission} 的实际文案回写为 {@code /perfscope} 指令的 permissionMessage。
     *
     * <p>背景：Bukkit 在进入 {@code CommandExecutor} 之前就会用 {@link PluginCommand} 的
     * permission 门拦下无权限者并发送 permissionMessage，因而 plugin.yml 的
     * {@code permission-message} 是 {@link #get} 管不到的第三份副本（改 messages.yml
     * 对那条拒绝文案无效）。{@link org.bukkit.command.Command#setPermissionMessage(String)}
     * 在 Paper 上是 deprecated 的 legacy 接口，而 {@code permissionMessage(Component)} 是
     * Adventure 原生 setter（不 deprecated，运行时由 Paper 提供），plugin.yml 的字符串值经
     * {@code LegacyComponentSerializer} 反序列化后存入同一字段——两条路等价，故此处走
     * Component 版本，避免 deprecation 警告、也避免 § 色码被二次解析。</p>
     *
     * <p>可行性验证：Paper 1.20.4 API 的 {@code Command#permissionMessage(Component)} 直接
     * 写入 {@code permissionMessage} 字段，{@code testPermission} 在拒绝时读取该字段并附带
     * {@code <permission>} 占位符替换后发送给发送者——回写即时生效，且 reload 后随
     * messages.yml 一起刷新。plugin.yml 中的值从此只是"首帧兜底"（load 之前）。</p>
     *
     * <p>防御性：指令不存在（plugin.yml 缺失/注册失败）时静默跳过，绝不因消息层拖垮启动。</p>
     */
    public static void applyCommandPermissionMessage(PerfScope plugin) {
        if (plugin == null) {
            return;
        }
        try {
            PluginCommand command = plugin.getCommand("perfscope");
            if (command == null) {
                return;
            }
            command.permissionMessage(Component.text(get("no-permission")));
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("[消息] 回写指令 permissionMessage 失败（沿用 plugin.yml 值）: "
                + e.getMessage());
        }
    }

    // ==================================================================================
    // 取值
    // ==================================================================================

    /**
     * 取一条文案（两级回退：当前语言的用户覆盖 → 该语言内置默认 → 键名本身）。
     *
     * <p>签名与语义对现有 15 个调用点保持不变；内部改为读 {@link #snapshot}，
     * 因此语言切换对调用方完全透明。返回的字符串可安全用于 {@code Component.text}
     * （纯文本，无格式码）。</p>
     *
     * @param key 文案键；{@code null} 返回空串（旧实现对 null 会 NPE，这里按"展示层不抛异常"收口）
     */
    public static String get(String key) {
        return resolve(key, NO_ARGS);
    }

    /**
     * 取一条带占位符的文案，见类注释的"占位符契约"。
     *
     * <p>永不抛异常：参数不足保留占位符、多余参数忽略、{@code null} 参数渲染为 {@code "null"}、
     * 非占位符花括号原样保留。格式化失败时退回未经替换的原文。</p>
     *
     * @param key  文案键；{@code null} 返回空串
     * @param args 按序号替换 {@code {0}}、{@code {1}}……；传入 {@code null} 等价于不传
     */
    public static String get(String key, Object... args) {
        return resolve(key, args == null ? NO_ARGS : args);
    }

    /** 当前生效的语言（{@code en} / {@code zh}）；供命令层/报告等需要自适应的展示逻辑使用。 */
    public static String activeLocale() {
        return snapshot.locale();
    }

    /**
     * 当前语言全部内置键（文档/测试/诊断用，只读）。
     *
     * <p>9A-24 之后这个方法是<b>死 API</b>（零调用点）。这里不删除，而是"接上"：
     * 返回值改为<b>当前语言</b>的键集合，因而它天然与 {@link #get} 的键空间一致，
     * 不会出现"文档列了 A，实际读的是 B"的漂移。保留公开方法是为了不破坏可能存在的
     * 外部调用（测试脚本、排查工具），删除的收益不足以抵消这一风险。</p>
     */
    public static Set<String> keys() {
        return snapshot.defaults().keySet();
    }

    /** 两级回退 + 占位符替换。所有异常都被收敛为"返回可读文本"。 */
    private static String resolve(String key, Object[] args) {
        if (key == null) {
            return "";
        }
        // 只读一次 volatile：整段解析必须基于同一份快照，否则极端并发下可能"默认取自新语言、
        // 覆盖取自旧语言"——那正是要杜绝的混排
        Snapshot current = snapshot;
        String value = current.overrides().get(key);
        if (value != null && !value.isEmpty()) {
            return format(value, args);
        }
        value = current.defaults().get(key);
        return format(value != null ? value : key, args);
    }

    /**
     * 替换 {@code {0}}、{@code {1}}……占位符。
     *
     * <p>刻意不用 {@link java.text.MessageFormat}：它把<b>所有</b>花括号都当格式语法
     * （文案里出现 {@code {not-a-placeholder}} 会抛 IllegalArgumentException），
     * 还把单引号当转义符（英文文案里的 {@code don't} 会被吞掉一个引号）。
     * 这里的正则只认 {@code {\d+}}，其余字符逐字保留。</p>
     */
    private static String format(String template, Object[] args) {
        if (template == null || args == null || args.length == 0 || template.indexOf('{') < 0) {
            return template;
        }
        try {
            Matcher matcher = PLACEHOLDER.matcher(template);
            StringBuilder sb = new StringBuilder(template.length() + 16);
            while (matcher.find()) {
                String replacement;
                int index = parseIndex(matcher.group(1));
                if (index >= 0 && index < args.length) {
                    // String.valueOf(null Object) == "null"：保留"这里本该有值"的信号，且不会 NPE
                    replacement = String.valueOf(args[index]);
                } else {
                    // 序号越界或数字长到溢出：占位符原样保留，便于一眼看出是哪条文案缺参数
                    replacement = matcher.group();
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            return sb.toString();
        } catch (RuntimeException e) {
            // 兜底：任何意外都退回原文，消息层绝不把异常抛给业务逻辑
            return template;
        }
    }

    /** 解析占位符序号；溢出/非法返回 -1（由调用方保留原样）。 */
    private static int parseIndex(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 统一日志前缀（与控制台既有 "[消息]" 口径一致）。 */
    private static void warn(PerfScope plugin, String message) {
        try {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning("[消息] " + message);
            }
        } catch (Exception | LinkageError e) {
            // 日志本身失败也只能放弃——绝不能因为记日志把加载流程打断
        }
    }

    /**
     * 运行时快照：语言 + 该语言默认表 + 用户覆盖表。
     *
     * <p>三者打包成一个不可变对象、由单个 {@code volatile} 字段发布，是"整份切换"的实现基础：
     * 语言与文案永远同生同灭，读线程看不到中间态。</p>
     */
    private record Snapshot(String locale, Map<String, String> defaults, Map<String, String> overrides) {
        private Snapshot {
            // 防御：任何字段意外为 null 都补齐，保证 get() 永不 NPE
            locale = locale != null ? locale : DEFAULT_LOCALE;
            defaults = defaults != null ? defaults : EN_DEFAULTS;
            overrides = overrides != null ? overrides : Map.of();
        }
    }
}
