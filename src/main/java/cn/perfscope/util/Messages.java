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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 消息层（Wave 8D）——用户可见文案的单一出口，支持管理员覆盖与未来语言包。
 *
 * <p><b>设计取舍</b>（锐评 D 的语气裁决：萌系服主可接受、专业受众观感存疑——两头都是真实受众）：
 * "喵"这类口癖不是删掉，而是<b>做成可配置</b>；专业部署可用 messages.yml 一键换成中性文案，
 * 无需改代码。</p>
 *
 * <p><b>覆盖机制</b>：内置默认值（{@link #DEFAULTS}，即现有中文文案逐字保留——既有 harness
 * 断言全部继续成立）；管理员在 {@code plugins/PerfScope/messages.yml} 写同名键即可覆盖；
 * 缺失键回退内置默认（用户文件永不需要包含全部键）；文件损坏只记日志、回退全默认。</p>
 *
 * <p><b>迁移进度</b>：本波迁移"最高可见度"的一批（权限拒绝/重载/报告/监测开关等 12 个键，
 * 含全部 9 处"喵"）；其余命令输出文案留待按需增量迁移——底层基础设施（本类）已就绪，
 * 迁移一个键的成本是"改一行调用 + 加一条默认值"。</p>
 *
 * <p>线程模型：{@code load} 在 reload（主线程）调用；{@code get} 可被任意线程调用
 * （Map 读取 + volatile 发布）。</p>
 */
public final class Messages {

    /** 内置默认（与迁移前文案逐字一致——默认行为零变更，harness 断言零影响）。 */
    private static final Map<String, String> DEFAULTS = new HashMap<>();

    static {
        // 权限与守卫（9 处"喵"中的 7 处；另 2 处在 PerfScope 启动/关闭日志）
        DEFAULTS.put("no-permission", "你没有权限使用此指令喵");
        DEFAULTS.put("no-permission-export", "没有导出报告权限喵");
        DEFAULTS.put("no-permission-reload", "没有重载配置权限喵");
        DEFAULTS.put("player-only", "此功能仅玩家可用喵");
        DEFAULTS.put("monitor-needs-paper", "实时监测功能需要 Paper 服务端支持 ActionBar 喵");
        // 操作反馈
        DEFAULTS.put("reloaded", "配置已重载喵~");
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
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            // 合并 jar 内默认资源（用户文件缺的键用资源里的；资源也缺的用内置 DEFAULTS）
            try (InputStream in = plugin.getResource("messages.yml")) {
                if (in != null) {
                    yaml.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
                }
            }
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
    }

    /**
     * 取一条文案：用户覆盖 → jar 资源默认 → 内置默认 → 键名本身（防御性，便于排查）。
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
