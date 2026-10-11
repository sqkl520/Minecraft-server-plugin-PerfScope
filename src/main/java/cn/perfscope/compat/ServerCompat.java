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
package cn.perfscope.compat;

import org.bukkit.Bukkit;

import java.util.Locale;

/**
 * 服务端兼容层 - 检测当前运行的服务端类型并标记可用功能
 * 支持 Paper / Spigot / Bukkit / Folia 及 Paper 分支；类型仅展示，功能走能力 flag
 */
public final class ServerCompat {

    private static final ServerType TYPE;
    private static final String SERVER_VERSION;
    private static final String SERVER_NAME;

    /** Paper TickTimes API 是否可用 */
    public static final boolean PAPER_MSPT_AVAILABLE;
    /** Paper getAverageTickTime() 是否可用 */
    public static final boolean PAPER_AVG_TICK_AVAILABLE;
    /** Paper TileEntity 计数 API 是否可用 */
    public static final boolean PAPER_TILE_ENTITY_COUNT_AVAILABLE;
    /** Paper ActionBar API 是否可用（Paper 系原生提供 sendActionBar(Component)，恒可用） */
    public static final boolean PAPER_ACTION_BAR_AVAILABLE;
    /** Adventure API 是否可用（Paper 系运行时原生自带 Adventure，恒可用） */
    public static final boolean ADVENTURE_AVAILABLE;

    static {
        SERVER_VERSION = Bukkit.getBukkitVersion();

        // P0-9：三级特征检测确定服务端类型（仅用于展示；功能判断走能力 flag）
        TYPE = detectServerType();

        // 提取服务端名称
        String implName = Bukkit.getServer().getName();
        if (implName != null && !implName.isEmpty()) {
            SERVER_NAME = implName + " (" + TYPE.getDisplayName() + ")";
        } else {
            SERVER_NAME = TYPE.getDisplayName();
        }

        // 检测各功能可用性
        PAPER_MSPT_AVAILABLE = checkPaperMSPT();
        PAPER_AVG_TICK_AVAILABLE = checkPaperAvgTick();
        PAPER_TILE_ENTITY_COUNT_AVAILABLE = checkPaperTileEntityCount();
        // Paper 系原生自带 Adventure，sendActionBar(Component) 恒可用；
        // 已移除旧 shade/Spigot 降级时期的反射检测（专注 Paper 系定位）
        PAPER_ACTION_BAR_AVAILABLE = true;
        ADVENTURE_AVAILABLE = true;
    }

    private ServerCompat() {}

    // ==================== 检测方法 ====================

    /**
     * P0-9：三级特征检测（仅用于展示；功能判断一律走能力 flag）。
     * 优先级：① 标志类存在性（Folia → Paper 系 → Spigot）→ ② API 方法存在性
     * → ③ Bukkit.getName() 兜底。恒返回非空，异常下沉到下一级。
     */
    private static ServerType detectServerType() {
        try {
            // ① Folia 标志类
            if (hasClass("io.papermc.paper.threadedregions.RegionizedServer")) {
                return ServerType.FOLIA;
            }
            // ② Paper 系标志类（含 Purpur / Pufferfish 等 Paper 分支）
            if (hasClass("com.destroystokyo.paper.PaperConfig")
                    || hasClass("io.papermc.paper.configuration.Configuration")
                    || hasClass("org.purpurmc.purpur.PurpurConfig")
                    || hasClass("gg.pufferfish.pufferfish.PufferfishConfig")) {
                return ServerType.PAPER;
            }
            // ③ Spigot 标志类
            if (hasClass("org.spigotmc.SpigotConfig")) {
                return ServerType.SPIGOT;
            }
            // ④ API 方法存在性：CraftBukkit 系服务端实现 getTPS
            if (hasServerMethod("getTPS")) {
                return ServerType.SPIGOT;
            }
        } catch (Throwable t) {
            // 特征检测异常则下沉到名称兜底，保证恒有 TYPE 赋值
        }
        // ⑤ 兜底：Bukkit.getName()
        try {
            String name = String.valueOf(Bukkit.getName()).toLowerCase(Locale.ROOT);
            if (name.contains("folia")) {
                return ServerType.FOLIA;
            }
            if (name.contains("paper") || name.contains("purpur") || name.contains("pufferfish")) {
                return ServerType.PAPER;
            }
            if (name.contains("spigot") || name.contains("craftbukkit")) {
                return ServerType.SPIGOT;
            }
        } catch (Throwable ignored) {
            // getName 异常则落到 BUKKIT
        }
        return ServerType.BUKKIT;
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean hasServerMethod(String methodName) {
        try {
            Bukkit.getServer().getClass().getMethod(methodName);
            return true;
        } catch (NoSuchMethodException | SecurityException e) {
            return false;
        }
    }

    private static boolean checkPaperMSPT() {
        try {
            Object server = Bukkit.getServer();
            java.lang.reflect.Method getServer = server.getClass().getMethod("getServer");
            Object nms = getServer.invoke(server);
            nms.getClass().getMethod("getTickTimes");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkPaperAvgTick() {
        try {
            Bukkit.getServer().getClass().getMethod("getAverageTickTime");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean checkPaperTileEntityCount() {
        try {
            org.bukkit.World.class.getMethod("getTileEntityCount");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // ==================== 公共方法 ====================

    public static ServerType getType() {
        return TYPE;
    }

    public static String getServerName() {
        return SERVER_NAME;
    }

    public static String getServerVersion() {
        return SERVER_VERSION;
    }

    public static boolean isPaper() {
        return TYPE == ServerType.PAPER;
    }

    public static boolean isSpigot() {
        return TYPE == ServerType.SPIGOT;
    }

    public static boolean isBukkit() {
        return TYPE == ServerType.BUKKIT;
    }

    public static boolean isFolia() {
        return TYPE == ServerType.FOLIA;
    }

    /**
     * 服务端类型枚举
     */
    public enum ServerType {
        PAPER("Paper"),
        SPIGOT("Spigot"),
        BUKKIT("Bukkit"),
        FOLIA("Folia");

        private final String displayName;

        ServerType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}