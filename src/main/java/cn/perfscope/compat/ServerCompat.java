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

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * 服务端兼容层 - 检测当前运行的服务端类型并标记可用功能
 * 支持 Paper / Spigot / Bukkit / Folia 及 Paper 分支；类型仅展示，功能走能力 flag
 */
public final class ServerCompat {

    private static final ServerType TYPE;
    private static final String SERVER_VERSION;
    private static final String SERVER_NAME;

    /**
     * 9A-1：getTickTimes() 方法是否存在 —— 纯静态探测结果，<b>只回答"方法在不在"，
     * 不回答"数据能不能用"</b>，仅供诊断输出；功能门控一律走 {@link #PAPER_MSPT_AVAILABLE}。
     */
    public static final boolean TICK_TIMES_METHOD_PRESENT;
    /** 9A-1：getAverageTickTime() 方法是否存在（语义同 {@link #TICK_TIMES_METHOD_PRESENT}）。 */
    public static final boolean AVG_TICK_METHOD_PRESENT;

    /**
     * 9A-1：Paper TickTimes API 是否<b>实测可用</b>（CraftServer.getTickTimes()，返回 long[]）。
     *
     * <p>9A-1 起由 {@code final} 改为 {@code volatile}：Folia 的 CraftServer 上该方法
     * <b>存在但恒返回全零</b>（jar 内 {@code TickTimes.add} 零调用点），静态方法存在性探测
     * 会把"方法在"误判成"数据真"，于是启动日志与 README 一起宣称"精确采集"。
     * 现在：Folia 在类初始化阶段即置 false；非 Folia 由运行期探针
     * {@link #probeRuntimeAvailability(Logger, boolean)} 在首周期后实测复核，连续两次全零同样下调为 false。
     * 探测方向只有"可用 → 不可用"一种：
     * 一旦下调就是这台服务端 tick 计时不动的事实，不存在"下次采样又好了"的路径。</p>
     *
     * <p>保留同名字段（而非改成方法）是为了让 {@code MetricsCollector} / {@code PerfScope}
     * 等既有读取点零改动——非 final 静态字段不会被常量折叠，运行期变更对它们立即可见。</p>
     */
    public static volatile boolean PAPER_MSPT_AVAILABLE;
    /** 9A-1：getAverageTickTime() 是否实测可用（同上：Folia 恒返回 0）。 */
    public static volatile boolean PAPER_AVG_TICK_AVAILABLE;

    /** 9A-1：MSPT 不可用的原因（展示层直接引用，避免各处自造文案；可用时为 null）。 */
    private static volatile String msptUnavailableReason;
    /** 9A-1：运行期探针是否已定案（一旦降级即置位，之后调用直接返回 false，不重复采样）。 */
    private static volatile boolean runtimeProbed;
    /** 9A-1：连续"实测全零"次数——达到 {@link #ZERO_SAMPLE_CONFIRMATIONS} 才降级，防一次瞬态误判。 */
    private static volatile int zeroSampleStreak;
    /** 9A-1：确认阈值（同一服务端 tick 计时不动是稳定事实，两次采样足以定案）。 */
    private static final int ZERO_SAMPLE_CONFIRMATIONS = 2;
    /** 9A-1：运行期探针延迟（tick）——默认 100 tick ≈ 5 秒，即采集首周期（20 tick）之后数个周期。 */
    private static final long RUNTIME_PROBE_DELAY_TICKS = 100L;
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
        // 9A-1：先取静态方法存在性（TICK_TIMES_METHOD_PRESENT 等），再据此给出初始功能门控。
        // Folia 上两个方法都存在但恒返回全零 → 初始门控就必须为 false，不能等首周期探针
        // （否则启动日志会在 Folia 上打一行"Paper API"，而那正是 9A-1 要消灭的假精确）。
        TICK_TIMES_METHOD_PRESENT = checkPaperMSPT();
        AVG_TICK_METHOD_PRESENT = checkPaperAvgTick();
        boolean folia = TYPE == ServerType.FOLIA;
        if (folia) {
            PAPER_MSPT_AVAILABLE = false;
            PAPER_AVG_TICK_AVAILABLE = false;
            msptUnavailableReason = "Folia 无全局 tick 时间（getTickTimes 恒空）";
        } else {
            PAPER_MSPT_AVAILABLE = TICK_TIMES_METHOD_PRESENT;
            PAPER_AVG_TICK_AVAILABLE = AVG_TICK_METHOD_PRESENT;
            msptUnavailableReason = null;
        }
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
            // MC-1 修复：getTickTimes() 位于 CraftServer（即 Bukkit.getServer() 的运行时类，返回 long[]），
            // 勿再走 getServer()→NMS DedicatedServer 旧链（该链在全部受支持服务端上恒探测失败）
            Bukkit.getServer().getClass().getMethod("getTickTimes");
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

    // ==================== 9A-1：运行期可用性验证 ====================

    /**
     * 9A-1：在插件启用后（采集首周期之后）调用一次，用**实测数据**复核 MSPT 能力位。
     *
     * <p>背景：Folia 的 {@code CraftServer.getTickTimes()} 与 {@code getAverageTickTime()}
     * <b>方法存在但恒返回全零</b>（jar 内 {@code TickTimes.add} 零调用点）。旧的静态探测只看方法存在性，
     * 于是四层口径（能力位 / 启动日志 / README / 兼容矩阵）一起宣称"精确"，而线上永远是"未知"。</p>
     *
     * <p>本方法做两件事：</p>
     * <ol>
     *   <li>{@code isFolia == true} → 直接把两个能力位置 false（类初始化已置 false，此处是防御性重申，
     *       覆盖"类在非 Folia 上先初始化、之后才被误判为 Folia"的极端场景），打一条 INFO 说明原因；</li>
     *   <li>{@code isFolia == false} → 实测调用一次 getTickTimes()/getAverageTickTime()：
     *       返回全零/空数组/异常 → 同样下调为 false 并记 INFO。这是给 Paper 分支（Purpur/Leaf 等
     *       曾改动 tick 计时实现的分支）留的兜底——宁可降级成"不可用"，也不输出假精确。</li>
     * </ol>
     *
     * <p><b>只降不升</b>：探针永不把 false 改回 true（静态探测为 false 说明方法都不在，实测成功也无从调用）；
     * 且一旦定案（可用 / 不可用）就不再采样，reload 不会重复探测。</p>
     *
     * <p><b>两次确认</b>：非 Folia 分支看到全零时<b>不立即降级</b>——首次采样命中空窗口是可能的，
     * 故要求连续 {@link #ZERO_SAMPLE_CONFIRMATIONS} 次全零才定案（调用方按周期重试即可）。
     * 这样既能揭穿"方法在但恒空"的分支，又不会因为一次瞬态把健康 Paper 误判成不可用。</p>
     *
     * <p><b>调用位置要求</b>：必须"首周期之后"——刚 enable 时 tick 采样窗口尚未填充，
     * 全零是正常现象而非"不可用"。本类不自己挂任务（不引入调度依赖），
     * 由 {@code PerfScope.onEnable()} 经 {@link #armRuntimeProbe(org.bukkit.plugin.Plugin)} 调用。</p>
     *
     * @param logger 插件 logger（null 时退化为 JUL root logger）
     * @param isFolia 是否 Folia（调用方传 {@link #isFolia()}；显式传入便于单元测试）
     */
    public static void probeRuntimeAvailability(Logger logger, boolean isFolia) {
        if (runtimeProbed) {
            return;   // 已定案（降级）或已确认可用，不重复采样
        }
        Logger log = logger != null ? logger : Logger.getLogger("PerfScope");

        if (isFolia) {
            runtimeProbed = true;
            boolean wasAvailable = PAPER_MSPT_AVAILABLE || PAPER_AVG_TICK_AVAILABLE;
            PAPER_MSPT_AVAILABLE = false;
            PAPER_AVG_TICK_AVAILABLE = false;
            msptUnavailableReason = "Folia 无全局 tick 时间（getTickTimes 恒空）";
            if (wasAvailable) {
                // 只有"此前被误判为可用"时才需要解释——否则是噪音
                log.info("[PerfScope] Folia 无全局 tick 时间（getTickTimes 恒空），MSPT 标记为不可用");
            } else {
                log.info("[PerfScope] Folia 无全局 tick 时间（getTickTimes 恒空），MSPT 不可用 "
                    + "（TPS 为 global region 局部值）");
            }
            return;
        }

        // 非 Folia：实测。方法不存在（静态探测已 false）则不重复尝试
        boolean wasPrimaryAvailable = PAPER_MSPT_AVAILABLE;
        boolean bad = false;
        if (TICK_TIMES_METHOD_PRESENT) {
            Method m = lookupServerMethod("getTickTimes");
            // m == null 说明静态探测与实测不一致（类加载器被替换等），按不可用处理
            if (m == null || !hasPositiveTickSample(invokeTickArray(m))) {
                bad = true;
            }
        } else {
            bad = true;   // 一级方法都不在，只能看二级
        }
        boolean avgBad;
        if (AVG_TICK_METHOD_PRESENT) {
            Method m = lookupServerMethod("getAverageTickTime");
            avgBad = m == null || !(invokeAvgTick(m) > 0.0);
        } else {
            avgBad = true;
        }
        if (!bad) {
            // 一级实测有非零样本 → 能力确认，定案（不再采样，避免抖动）
            runtimeProbed = true;
            zeroSampleStreak = 0;
            msptUnavailableReason = null;
            return;
        }
        // 一级不可用（全零或方法缺失）。可能是"采样窗口尚未填充"的瞬态——
        // 统一要求连续 ZERO_SAMPLE_CONFIRMATIONS 次异常才定案，避免把健康 Paper 误判成不可用。
        // 注：探针只由单一调度线程执行，此处 ++ 无并发竞争。
        int streak = zeroSampleStreak + 1;
        zeroSampleStreak = streak;
        if (streak < ZERO_SAMPLE_CONFIRMATIONS) {
            return;   // 等下一次调用（armRuntimeProbe 已安排重试）
        }
        runtimeProbed = true;
        if (!avgBad) {
            // 一级实测恒空、但二级有值：只降一级，仍算"精确"（getAverageTickTime 非估算 API）。
            // 二级能力位保持 true，msptUnavailableReason 依契约置 null（可用时无原因）。
            PAPER_MSPT_AVAILABLE = false;
            msptUnavailableReason = null;
            if (wasPrimaryAvailable) {
                log.info("[PerfScope] getTickTimes() 实测恒为空，MSPT 降级为 getAverageTickTime"
                    + "（仅平均值，无 min/max）");
            }
            return;
        }
        // 两级都空：定案为不可用
        boolean wasAvailable = PAPER_MSPT_AVAILABLE || PAPER_AVG_TICK_AVAILABLE;
        PAPER_MSPT_AVAILABLE = false;
        PAPER_AVG_TICK_AVAILABLE = false;
        msptUnavailableReason = "getTickTimes/getAverageTickTime 实测恒为空";
        if (wasAvailable) {
            log.info("[PerfScope] getTickTimes() 与 getAverageTickTime() 连续 "
                + ZERO_SAMPLE_CONFIRMATIONS + " 次实测全零/空数组，MSPT 标记为不可用"
                + "（该服务端 tick 计时未填充，不做假精确输出）");
        }
    }

    /**
     * 探针延迟（tick）：调用方（PerfScope.onEnable）据此安排首次实测时机。
     * 100 tick ≈ 5 秒，晚于采集首周期（默认 20 tick）数个周期，采样窗口必然已填充。
     */
    public static long runtimeProbeDelayTicks() {
        return RUNTIME_PROBE_DELAY_TICKS;
    }

    /**
     * 9A-1：把探针挂到统一调度器上，按 {@link #RUNTIME_PROBE_DELAY_TICKS} 延迟后周期重试，
     * 直到 {@link #probeRuntimeAvailability} 定案为止。
     *
     * <p>由 {@code PerfScope.onEnable()} 在 {@code startCollectionTasks()} 之后调用一次即可；
     * 本方法自己完成"延迟 + 线程归属 + 重试"三件事，调用方无需关心 Folia 的 GlobalRegionScheduler
     * （Bukkit 调度器在 Folia 上必抛 UnsupportedOperationException，见 {@link SchedulerAdapter}）。</p>
     *
     * <p><b>为什么要重试</b>：非 Folia 分支要求连续两次全零才定案（见
     * {@link #probeRuntimeAvailability}），因此单次延迟执行无法完成判定。这里用低频重试
     * （{@link #PROBE_RETRY_ATTEMPTS} 次，首次在延迟点）覆盖这个窗口；
     * 一旦定案后续调用直接返回（幂等），重试自然成为空操作。</p>
     *
     * <p>任何调度失败（返回 noop 句柄）都不影响插件本体：能力位在类初始化时已按 Folia 置好，
     * 探针只是给非 Folia 分支（Purpur/Leaf 等改动过 tick 计时的分支）多一道实测兜底。</p>
     */
    public static void armRuntimeProbe(org.bukkit.plugin.Plugin plugin) {
        if (plugin == null) {
            return;
        }
        scheduleProbe(plugin, TYPE == ServerType.FOLIA, PROBE_RETRY_ATTEMPTS);
    }

    /**
     * 探针重试次数：首次在 {@link #RUNTIME_PROBE_DELAY_TICKS} 时触发，之后每次再延同样的间隔，
     * 3 次足以覆盖"连续两次确认"的判定需求，又不会在定案后留下常驻任务。
     */
    private static final int PROBE_RETRY_ATTEMPTS = 3;

    /**
     * 延迟挂一次探针；若本次未定案且还有次数，则再挂下一次（自续，不常驻）。
     * 用 runLater 而非 runGlobalTimer 的原因：探针是一次性的，定案后不该留下每 5 秒空转的周期任务。
     */
    private static void scheduleProbe(org.bukkit.plugin.Plugin plugin, boolean folia, int remaining) {
        if (remaining <= 0) {
            return;
        }
        SchedulerAdapter.runLater(plugin, () -> {
            probeRuntimeAvailability(plugin.getLogger(), folia);
            if (!runtimeProbed) {
                scheduleProbe(plugin, folia, remaining - 1);
            }
        }, RUNTIME_PROBE_DELAY_TICKS);
    }

    private static Method lookupServerMethod(String name) {
        try {
            return Bukkit.getServer().getClass().getMethod(name);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static long[] invokeTickArray(Method m) {
        try {
            return (long[]) m.invoke(Bukkit.getServer());
        } catch (Throwable t) {
            return null;
        }
    }

    private static double invokeAvgTick(Method m) {
        try {
            Object v = m.invoke(Bukkit.getServer());
            return v instanceof Number n ? n.doubleValue() : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private static boolean hasPositiveTickSample(long[] arr) {
        if (arr == null || arr.length == 0) {
            return false;
        }
        for (long t : arr) {
            if (t > 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * 9A-1：MSPT 是否**值得信任**（唯一功能门控入口）。
     *
     * <p>{@code MetricsCollector.collectMSPT} 必须消费本方法，语义：</p>
     * <ul>
     *   <li>返回 true → 走 ① getTickTimes / ② getAverageTickTime，正常采集；</li>
     *   <li>返回 false → <b>不得</b>走 ③ TPS 反推（Folia 上 TPS 本身是 global region 局部值，
     *       反推出来的 MSPT 是"双重不可信"），应直接置 {@code msptAvg/min/max = -1}
     *       并把 {@code MODULE_MSPT} 放进 {@code unavailable}。</li>
     * </ul>
     *
     * <p>注意：非 Folia 的纯 Bukkit/Spigot 上返回 false 时，TPS 反推仍是合理的降级
     * （那些服务端 TPS 是真全服 TPS），所以调用方应以 {@link #isFolia()} 区分两种 false。</p>
     */
    public static boolean isMsptTrustworthy() {
        return PAPER_MSPT_AVAILABLE || PAPER_AVG_TICK_AVAILABLE;
    }

    /**
     * 9A-1：MSPT 不可用的原因（一句话，可直接拼进聊天框/报告）；可用时返回 null。
     * 例：{@code "Folia 无全局 tick 时间（getTickTimes 恒空）"}。
     */
    public static String msptUnavailableReason() {
        return msptUnavailableReason;
    }

    /**
     * 9A-2：本服务端的 TPS 读数是否为**全服口径**。
     *
     * <p>Folia 上 {@code Bukkit.getTPS()} 是 <b>global region 的局部 TPS</b>（global region 只跑
     * 全局逻辑与不归属任何 region 的区块），并非全服吞吐；空服启动期 global region 报表还有空窗期，
     * 会短暂报出接近 0 的假值。故 Folia 上一律返回 false，展示层据此追加 {@code (global region)} 标注，
     * 告警层据此抑制启动窗口内的误报。</p>
     */
    public static boolean isTpsGlobalScope() {
        return TYPE != ServerType.FOLIA;
    }

    /**
     * 9A-2：TPS 口径标注（Paper/Spigot 返回空串，Folia 返回 {@code " (global region)"}）。
     * 展示层直接 append，避免每处自造文案与译法漂移。
     */
    public static String tpsScopeLabel() {
        return isTpsGlobalScope() ? "" : " (global region)";
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