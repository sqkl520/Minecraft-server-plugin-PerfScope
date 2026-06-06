package cn.perfscope.compat;

import org.bukkit.Bukkit;

/**
 * 服务端兼容层 - 检测当前运行的服务端类型并标记可用功能
 * 支持 Paper / Spigot / Bukkit 及其衍生服务端
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
    /** Paper ActionBar API 是否可用 */
    public static final boolean PAPER_ACTION_BAR_AVAILABLE;
    /** Paper 红石追踪 API 是否可用 */
    public static final boolean PAPER_REDSTONE_AVAILABLE;
    /** 网络统计 API 是否可用 */
    public static final boolean PAPER_NETWORK_AVAILABLE;
    /** Adventure API 是否可用 */
    public static final boolean ADVENTURE_AVAILABLE;

    static {
        String serverClassName = Bukkit.getServer().getClass().getName();
        String bukkitVersion = Bukkit.getBukkitVersion();
        SERVER_VERSION = bukkitVersion;

        // 检测服务端类型
        if (serverClassName.contains("paper") || serverClassName.contains("Paper")) {
            TYPE = ServerType.PAPER;
        } else if (serverClassName.contains("spigot") || serverClassName.contains("Spigot")) {
            TYPE = ServerType.SPIGOT;
        } else {
            TYPE = ServerType.BUKKIT;
        }

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
        PAPER_ACTION_BAR_AVAILABLE = checkPaperActionBar();
        PAPER_REDSTONE_AVAILABLE = checkPaperRedstone();
        PAPER_NETWORK_AVAILABLE = checkPaperNetwork();
        ADVENTURE_AVAILABLE = checkAdventure();
    }

    private ServerCompat() {}

    // ==================== 检测方法 ====================

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

    private static boolean checkPaperActionBar() {
        try {
            org.bukkit.entity.Player.class.getMethod("sendActionBar", net.kyori.adventure.text.Component.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean checkPaperRedstone() {
        try {
            Object server = Bukkit.getServer();
            java.lang.reflect.Method getServer = server.getClass().getMethod("getServer");
            Object nms = getServer.invoke(server);
            for (java.lang.reflect.Method m : nms.getClass().getDeclaredMethods()) {
                if (m.getName().contains("Redstone") && m.getParameterCount() == 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkPaperNetwork() {
        try {
            Object server = Bukkit.getServer();
            java.lang.reflect.Method getServer = server.getClass().getMethod("getServer");
            Object nms = getServer.invoke(server);
            for (java.lang.reflect.Method m : nms.getClass().getDeclaredMethods()) {
                if ((m.getName().contains("Network") || m.getName().contains("Traffic"))
                    && m.getParameterCount() == 0
                    && m.getReturnType() != void.class) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkAdventure() {
        try {
            Class.forName("net.kyori.adventure.text.Component");
            return true;
        } catch (ClassNotFoundException e) {
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

    /**
     * 服务端类型枚举
     */
    public enum ServerType {
        PAPER("Paper"),
        SPIGOT("Spigot"),
        BUKKIT("Bukkit");

        private final String displayName;

        ServerType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}