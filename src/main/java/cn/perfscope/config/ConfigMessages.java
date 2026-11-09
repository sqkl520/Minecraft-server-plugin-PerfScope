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
package cn.perfscope.config;

import cn.perfscope.util.Messages;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置层文案表（配置校验 / 版本迁移 / 未知键检测的控制台输出）。
 *
 * <p><b>为什么单独一个类</b>：与 {@code CoreMessages} 同样的理由——把这些文案塞进
 * {@link Messages} 的静态块会让它成为唯一的写入热点，多个改造任务无法并行。
 * 本类在 static 块里把自己的中英文表交给 {@link Messages#registerModule}，
 * 合并结果与"直接写在 Messages 里"完全等价（同一张表、同样的两级回退、同样的 messages.yml 覆盖）。</p>
 *
 * <p><b>键空间划分</b>：</p>
 * <ul>
 *   <li>{@code config.load.*} —— 加载流程本身（解析失败、汇总、语言默认值提示、LoadResult 摘要）；</li>
 *   <li>{@code config.version.*} —— {@code config-version} 检测与写回；</li>
 *   <li>{@code config.type.*} / {@code config.expected.*} —— 类型错误里的"实际类型 / 期望类型"词条；</li>
 *   <li>{@code config.missing.*} —— 缺键汇总；</li>
 *   <li>{@code config.unknown.*} —— 未知键检测；</li>
 *   <li>{@code config.worlds.*} —— {@code worlds} 段世界名校验；</li>
 *   <li>{@code config.check.*} —— 阈值/区间/枚举值的回退与钳制告警。</li>
 * </ul>
 *
 * <p><b>硬约束：配置键名绝不进模板正文。</b>{@code cpu.hysteresis_margin}、
 * {@code alerts.cooldown-seconds}、{@code worlds.<世界名>} 这类标识符是用户 grep 配置文件的锚点，
 * 翻译它们等于让报错无法定位。因此键名一律以 {@code {0}} 形式由调用点传入，
 * 两种语言的模板里只保留说明文字。同理，数值（用户写的值、回退后的默认值）也全部走占位符——
 * 否则翻译时数字会被"顺手"改掉，或与代码里的常量各说一套。</p>
 *
 * <p><b>两种语言键集合必须一致</b>：{@link #put} 同时写两张表，从结构上保证不会漏翻一边
 * （漏一边的后果是该语言下直接显示键名）。</p>
 */
public final class ConfigMessages {

    static {
        Map<String, String> en = new LinkedHashMap<>();
        Map<String, String> zh = new LinkedHashMap<>();

        // ---- 加载流程 ----
        put(en, zh, "config.load.parse-failed",
            "Failed to parse config.yml (likely a YAML syntax error). This load was aborted; "
                + "the previous configuration is still in use",
            "config.yml 解析失败（YAML 语法错误？），本次加载已放弃，继续使用上一份配置");
        put(en, zh, "config.load.parse-failed-hint",
            "Fix the indentation/quotes in config.yml, then run /perfscope reload",
            "请修正 config.yml 的缩进/引号后执行 /perfscope reload");
        put(en, zh, "config.load.summary",
            "Configuration loaded: {0} missing key(s) (using defaults), {1} type error(s) "
                + "(reverted to defaults), {2} unknown key(s), {3} unknown world(s)",
            "配置加载完成：缺键 {0} 项（用默认值）、类型错误 {1} 项（已回退默认值）、"
                + "未知键 {2} 项、未知世界 {3} 项");
        put(en, zh, "config.load.language-default",
            "The interface language is now English by default (this config had no {0}). "
                + "To keep Chinese, add \"{1}\" under general and run /perfscope reload.",
            "界面语言已默认为英文（本配置此前没有 {0} 项）。"
                + "如需保持中文，请在本文件 general 段加入 \"{1}\" 后执行 /perfscope reload。");
        put(en, zh, "config.load.success", "Configuration loaded successfully", "配置加载成功");
        put(en, zh, "config.load.result.success",
            "Configuration loaded successfully (config-version={0}, {1} missing key(s), "
                + "{2} type error(s), {3} unknown key(s), {4} unknown world(s))",
            "配置加载成功（config-version={0}，缺键 {1}，类型错误 {2}，未知键 {3}，未知世界 {4}）");
        put(en, zh, "config.load.result.failed", "Configuration load failed: {0}", "配置加载失败：{0}");

        // ---- config-version 检测 / 迁移 ----
        put(en, zh, "config.version.legacy-detected",
            "Legacy configuration detected (no {0}); new keys will be filled with their defaults "
                + "(existing keys keep their current values)",
            "检测到旧版配置（无 {0}），将按默认值补齐新增键（已有键保持原值不动）");
        put(en, zh, "config.version.written",
            "Original configuration backed up and {0}: {1} written (the backup sits next to the original file)",
            "已备份原配置并写入 {0}: {1}（备份文件与原文件同目录）");
        put(en, zh, "config.version.write-failed",
            "{0} could not be written back to disk (the current run is unaffected, but this notice "
                + "will reappear on the next start; check file permissions)",
            "{0} 写回磁盘失败（不影响本次运行，但下次启动会再次提示，请检查文件权限）");
        put(en, zh, "config.version.too-new",
            "{0}={1} is newer than the version this build supports ({2}) — is this configuration "
                + "from a newer PerfScope? Keys unknown to this version will be ignored",
            "{0}={1} 高于本插件支持的版本 {2}（配置来自更新版本的 PerfScope？）"
                + "本版本不认识的新键会被忽略");
        put(en, zh, "config.version.older",
            "Configuration version {0} is older than the current {1}; new keys will use their "
                + "defaults (existing keys keep their current values)",
            "配置版本 {0} 低于当前 {1}，新增键将按默认值生效（已有键保持原值）");
        put(en, zh, "config.version.append-failed", "Failed to write back {0}: {1}", "写回 {0} 失败: {1}");

        // ---- 类型错误 ----
        put(en, zh, "config.type.error",
            "Configuration type error: {0} is currently {1} of type {2}; {3} is required. "
                + "Using {4} for this load — fix it and run /perfscope reload to apply",
            "配置类型错误：{0} 的当前值 {1} 是{2}，应为{3}，本次已改用 {4}；"
                + "修正后执行 /perfscope reload 生效");
        // 实际类型（typeName）
        put(en, zh, "config.type.null", "empty", "空值");
        put(en, zh, "config.type.boolean", "a boolean", "布尔值");
        put(en, zh, "config.type.integer", "an integer", "整数");
        put(en, zh, "config.type.decimal", "a decimal number", "小数值");
        put(en, zh, "config.type.string", "a string", "字符串");
        put(en, zh, "config.type.section", "a configuration section", "配置节");
        put(en, zh, "config.type.list", "a list", "列表");
        // 期望类型（由各 readXxx 传入；与上面分开是因为中文措辞略有差别：小数 / 小数值）
        put(en, zh, "config.expected.boolean", "a boolean", "布尔值");
        put(en, zh, "config.expected.integer", "an integer", "整数");
        put(en, zh, "config.expected.decimal", "a decimal number", "小数");
        put(en, zh, "config.expected.string", "a string", "字符串");
        put(en, zh, "config.expected.section", "a configuration section", "配置节");
        // worlds: 123 这类写法被整段忽略时，作为"实际采用值"展示
        put(en, zh, "config.value.section-ignored", "(section ignored)", "(忽略该段)");

        // ---- 缺键汇总 ----
        put(en, zh, "config.missing.migrating",
            "Legacy configuration migration: the following {0} key(s) are absent and their defaults "
                + "are in use (copy them from the bundled default config if you want to customise them): ",
            "旧版配置迁移：以下 {0} 个键不存在，已按默认值运行"
                + "（如需自定义请从插件默认配置复制后修改）: ");
        put(en, zh, "config.missing.summary",
            "Configuration is missing {0} key(s) (normal for an older config); defaults are in use: ",
            "配置缺少 {0} 个键（旧版配置属正常），本次按默认值运行: ");
        put(en, zh, "config.missing.more", " (of {0} in total)", " 等共 {0} 项");

        // ---- 未知键检测 ----
        put(en, zh, "config.unknown.key",
            "Configuration key {0} is no longer used or may be misspelled "
                + "(this version does not read it; it can be deleted safely)",
            "配置项 {0} 已不再使用/拼写可能有误（当前版本不读取该键，可安全删除）");
        put(en, zh, "config.unknown.summary",
            "{0} unknown configuration key(s) found; if you are sure the spelling is correct, "
                + "upgrade PerfScope or report the key name",
            "共发现 {0} 个未知配置项；若你确认拼写无误，请升级 PerfScope 或反馈该键名");
        put(en, zh, "config.unknown.known-keys-failed",
            "Could not read the bundled default config.yml; unknown-key detection skipped: {0}",
            "无法读取内置默认 config.yml，已跳过未知配置项检测: {0}");

        // ---- worlds 段世界名校验 ----
        put(en, zh, "config.worlds.skipped",
            "Worlds are not loaded yet; skipping world-name validation for the {0} section this time",
            "世界尚未加载，本次跳过 {0} 段世界名校验");
        put(en, zh, "config.worlds.unknown",
            "The {0} section lists unknown worlds: {1}; currently loaded worlds: {2}. "
                + "These entries have no effect (usually left over after a rename, or a typo) — "
                + "check the world names; if a world simply is not loaded yet, run /perfscope reload "
                + "once it is",
            "配置 {0} 段包含未知世界: {1}；当前已加载的世界: {2}。"
                + "这些条目不会生效（多为改名后残留或拼写错误），请核对世界名；"
                + "若该世界只是尚未加载，加载后 /perfscope reload 即可");

        // ---- 阈值 / 区间 / 枚举值校验 ----
        put(en, zh, "config.check.hysteresis",
            "Configuration check: {0}={1} is outside the valid range [0, 50]; reverted to the default {2}",
            "配置校验：{0}={1} 超出合法区间 [0, 50]，已回退默认值 {2}");
        put(en, zh, "config.check.cooldown-negative",
            "Configuration check: {0} is negative ({1}); clamped to 0",
            "配置校验：{0} 为负值({1})，已钳制为 0");
        put(en, zh, "config.check.debounce-cycles",
            "Configuration check: {0}={1} is invalid (must be at least 1); using {2} instead",
            "配置校验：{0}={1} 非法（需 ≥1），已按 {2} 处理");
        put(en, zh, "config.check.daily-summary-time",
            "Configuration check: {0}=\"{1}\" is invalid (expected HH:mm); reverted to the default {2}",
            "配置校验：{0}=\"{1}\" 非法（需 HH:mm），已回退默认 {2}");
        put(en, zh, "config.check.webhook-type",
            "Configuration check: {0}=\"{1}\" is unknown (supported: discord | onebot); "
                + "treating it as {2}",
            "配置校验：{0}=\"{1}\" 未知（支持 discord | onebot），已按 {2} 处理");
        put(en, zh, "config.check.history-interval",
            "Configuration check: {0}={1} is invalid (must be at least 1); using {2} seconds instead",
            "配置校验：{0}={1} 非法（需 ≥1），已按 {2} 秒处理");
        // 采集间隔没有上限会变成"主线程压测器"：update_interval: 1 = 每 tick 全量采集
        // = 20 次/秒，大服上直接把 TPS 拖垮。MetricsCollector 只有下限 Math.max(1, ...)，
        // 那个下限恰好放行了最危险的值。
        put(en, zh, "config.check.update-interval",
            "Configuration check: {0}={1} is out of range (must be {2}-{3} ticks); using {4} ticks instead",
            "配置校验：{0}={1} 超出范围（需 {2}-{3} tick），已按 {4} tick 处理");
        put(en, zh, "config.check.history-retention",
            "Configuration check: {0}={1} is invalid (must be at least 1); using {2} days instead",
            "配置校验：{0}={1} 非法（需 ≥1），已按 {2} 天处理");
        put(en, zh, "config.check.hotspots-cycles",
            "Configuration check: {0}={1} is invalid (must be at least 1); using {2} cycles instead",
            "配置校验：{0}={1} 非法（需 ≥1），已按 {2} 周期处理");
        put(en, zh, "config.check.hotspots-chunk-threshold",
            "Configuration check: {0}={1} is invalid (must be at least 1); using {2} instead",
            "配置校验：{0}={1} 非法（需 ≥1），已按 {2} 处理");
        put(en, zh, "config.check.language",
            "Configuration check: {0}={1} is not a supported language (supported: {2}); reverted to {3}",
            "配置校验：{0}={1} 不是受支持的语言（支持: {2}），已回退 {3}");
        put(en, zh, "config.check.percent-non-finite",
            "Configuration check: {0} is not a finite number (NaN/Infinity); "
                + "reverted to the default: {1} -> {2}",
            "配置校验：{0} 为非有限数值(NaN/Infinity)，已回退默认值：{1} -> {2}");
        put(en, zh, "config.check.percent-out-of-range",
            "Configuration check: {0}={1} is out of range (must satisfy 0 < value <= 100, a percentage); "
                + "reverted to the default {2}",
            "配置校验：{0}={1} 越界（要求 0 < 值 <= 100，百分比），已回退默认值 {2}");
        put(en, zh, "config.check.threshold-out-of-range",
            "Configuration check: {0} is out of range (must satisfy 0 <= value <= {1}); "
                + "reverted to the defaults: w={2} c={3}",
            "配置校验：{0} 数值越界（要求 0 ≤ 值 ≤ {1}），已回退默认值：w={2} c={3}");
        // f-s03-logic-01 残留闭合：阈值落在近恒真区 (20.0, 20.5] 时只警告、不改值。
        // 与 config.check.history-interval 的 warn-only 口径一致，避免 breaking change。
        put(en, zh, "config.check.tps-near-ceiling",
            "Configuration check: TPS warning/critical threshold is at or above {0} "
                + "(w={1} c={2}); steady-state TPS never exceeds 20.0, so the sustained-low-TPS "
                + "rule will fire on an otherwise healthy server. Value kept as configured; "
                + "lower it if this is not intended.",
            "配置校验：TPS 告警阈值 ≥ {0}（w={1} c={2}）；稳态 TPS 上限为 20.0，"
                + "「TPS 持续低位」规则因此在健康服务器上也会命中。已按原值保留，"
                + "若非预期请调低该阈值");
        put(en, zh, "config.check.threshold-inverted",
            "Configuration check: {0} has its warning/critical thresholds inverted; "
                + "reverted to the defaults: w={1} c={2}",
            "配置校验：{0} 的 warning(critical) 设置倒置，已回退默认值：w={1} c={2}");
        put(en, zh, "config.check.threshold-non-finite",
            "Configuration check: {0} contains a non-finite value (NaN/Infinity); "
                + "reverted to the defaults: w={1} c={2}",
            "配置校验：{0} 存在非有限数值(NaN/Infinity)，已回退默认值：w={1} c={2}");

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

    private ConfigMessages() {}
}
