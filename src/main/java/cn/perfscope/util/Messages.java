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

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 消息层（Wave 8D）——用户可见文案的单一出口，支持管理员覆盖与未来语言包。
 *
 * <p><b>设计取舍</b>（锐评 D 的语气裁决：萌系服主可接受、专业受众观感存疑——两头都是真实受众）：
 * "喵"这类口癖不是删掉，而是<b>做成可配置</b>；专业部署可用 messages.yml 一键换成中性文案，
 * 无需改代码。</p>
 *
 * <p><b>覆盖机制（9A-24：如实为两级）</b>：内置默认值（{@link #DEFAULTS}，即现有中文文案逐字
 * 保留——既有 harness 断言全部继续成立）；管理员在 {@code plugins/PerfScope/messages.yml}
 * 写同名键即可覆盖；缺失键回退内置默认（用户文件永不需要包含全部键）；文件损坏只记日志、
 * 回退全默认。回退链是 <b>用户覆盖 → 内置 DEFAULTS</b> 两级——jar 内 {@code messages.yml}
 * 只在首次运行时复制成用户文件，<b>不参与</b> {@link #get} 的回退（旧注释误称"三级"；
 * 若将来真需要资源层，必须在 {@code get()} 里加显式分支，而不是靠
 * {@code YamlConfiguration#setDefaults}）。</p>
 *
 * <p><b>迁移范围</b>：只迁移"最高可见度"的一批（权限拒绝/重载/报告/监测开关/启停横幅），
 * 键的权威清单见 {@link #keys()} 与 {@code src/main/resources/messages.yml}——本注释不再
 * 维护会漂移的键数/口癖计数。其余命令输出文案留待按需增量迁移，成本是"改一行调用 +
 * 加一条默认值"。</p>
 *
 * <p>线程模型：{@code load} 在 reload（主线程）调用；{@code get} 可被任意线程调用
 * （Map 读取 + volatile 发布）。</p>
 */
public final class Messages {

    /** 内置默认（与迁移前文案逐字一致——默认行为零变更，harness 断言零影响）。 */
    private static final Map<String, String> DEFAULTS = new HashMap<>();

    static {
        // 权限与守卫：no-permission 用于①指令执行器的顶层拒绝分支（Bukkit 权限门之后的兜底），
        // ②启动/重载时回写 PluginCommand 的 permissionMessage（见 applyCommandPermissionMessage）。
        // 子命令的专项拒绝走 no-permission-export / no-permission-reload。见 9A-24。
        DEFAULTS.put("no-permission", "你没有权限使用此指令喵");
        DEFAULTS.put("no-permission-export", "没有导出报告权限喵");
        DEFAULTS.put("no-permission-reload", "没有重载配置权限喵");
        DEFAULTS.put("player-only", "此功能仅玩家可用喵");
        DEFAULTS.put("monitor-needs-paper", "实时监测功能需要 Paper 服务端支持 ActionBar 喵");
        // 操作反馈
        DEFAULTS.put("reloaded", "配置已重载喵~");
        // 9A-24：控制台 reload 日志独立成键（默认值 = 改造前硬编码逐字一致，保留 "PerfScope " 前缀）
        DEFAULTS.put("reloaded-console", "PerfScope 配置已重载喵~");
        DEFAULTS.put("reload-failed-old-config", "PerfScope 配置重载未完成（已按旧配置重启采集/告警）");
        DEFAULTS.put("report-generated", "报告已生成: ");
        DEFAULTS.put("report-failed", "报告生成失败喵");
        DEFAULTS.put("monitor-on", "实时监测已开启喵~ 使用 /perfscope monitor 关闭");
        DEFAULTS.put("monitor-off", "实时监测已关闭喵~");
        // 启动/关闭横幅（日志侧"喵"）
        DEFAULTS.put("startup-done", "  PerfScope 启动完成喵~");
        DEFAULTS.put("shutdown-bye", "PerfScope 已关闭，再见喵~");
    }

    private static volatile Map<String, String> overrides = Map.of();

    private Messages() {}

    /** 从 jar 资源读取默认内容并写入数据目录（首次运行生成 messages.yml）。 */
    public static void saveDefault(PerfScope plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            try (InputStream in = plugin.getResource("messages.yml")) {
                if (in != null) {
                    plugin.saveResource("messages.yml", false);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[消息] messages.yml 生成失败（将使用内置默认）: " + e.getMessage());
            }
        }
    }

    /**
     * 加载用户覆盖（reload 时调用）。缺失键回退默认、文件损坏回退全默认——永不抛异常。
     */
    public static void load(PerfScope plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            overrides = Map.of();
            applyCommandPermissionMessage(plugin);
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            // 9A-24：此处原有 yaml.setDefaults(jar 资源) 一段——它对最终 get() 结果零影响
            // （overrides 只从用户文件的 getKeys(true) 填充，setDefaults 不改变这个键集合），
            // 旧文档宣传的"三级回退"实际只有两级，故删除。真要资源层就在 get() 里加显式分支。
            Map<String, String> loaded = new HashMap<>();
            for (String key : yaml.getKeys(true)) {
                String value = yaml.getString(key);
                if (value != null && !yaml.isConfigurationSection(key)) {
                    loaded.put(key, value);
                }
            }
            overrides = Map.copyOf(loaded);
        } catch (Exception | LinkageError e) {
            overrides = Map.of();
            if (plugin.getConfigManager() != null && plugin.getConfigManager().get() != null
                    && plugin.getConfigManager().get().general_debug) {
                plugin.getLogger().log(Level.WARNING, "[消息] messages.yml 加载失败（回退内置默认）", e);
            } else {
                plugin.getLogger().warning("[消息] messages.yml 加载失败（回退内置默认）: " + e.getMessage());
            }
        }
        // 9A-24：每次 load（含 reload）后把 no-permission 同步到指令的 permissionMessage，
        // 消除 plugin.yml 里第三份硬编码副本——见 applyCommandPermissionMessage
        applyCommandPermissionMessage(plugin);
    }

    /**
     * 9A-24：把 {@code no-permission} 的实际文案回写为 {@code /perfscope} 指令的 permissionMessage。
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

    /**
     * 取一条文案（9A-24：两级回退）：用户覆盖 → 内置 DEFAULTS → 键名本身（防御性，便于排查）。
     * 返回的字符串可安全用于 Component.text（纯文本，无格式码）。
     */
    public static String get(String key) {
        String v = overrides.get(key);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        v = DEFAULTS.get(key);
        return v != null ? v : key;
    }

    /** 全部内置键（文档/测试用）。 */
    public static java.util.Set<String> keys() {
        return java.util.Collections.unmodifiableSet(DEFAULTS.keySet());
    }
}
