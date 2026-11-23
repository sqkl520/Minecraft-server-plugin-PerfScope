package cn.perfscope.network;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨服摘要协议 —— 后端 → 代理的唯一数据契约。
 *
 * <p><b>为什么单独抽一个类</b>：这份协议有两个消费方（后端 Paper 插件发送、代理端 Velocity
 * 插件接收）。两侧代码不在同一个 JVM、也不能共享实现，唯一能共享的就是**字段名与语义**。
 * 把它写成纯逻辑类（不 import {@code org.bukkit.*}）有三个好处：</p>
 *
 * <ul>
 *   <li>能在单测 JVM 里直接构造与校验，不需要真服务端；</li>
 *   <li>协议自己的编解码错误（缺字段、类型错、负值越界）变成可测的纯函数，而不是"联调时才发现"；</li>
 *   <li>{@link #toMap()} 的输出就是线上字节的唯一来源，测试断言它就是线上格式。</li>
 * </ul>
 *
 * <h2>哨兵约定（本协议最重要的一条）</h2>
 *
 * <p>字段分两类，**不能混**：</p>
 *
 * <table border="1">
 *   <caption>字段类型与缺值表示</caption>
 *   <tr><th>类型</th><th>缺值写法</th><th>为什么不写 0</th></tr>
 *   <tr>
 *     <td>可为空的数值（TPS / MSPT / 内存 / CPU）</td>
 *     <td>字段**不出现**（{@code toMap} 里省掉）</td>
 *     <td>0 是合法读数：空闲服务器的进程 CPU 真的可能是 0；TPS 0 是真的冻结了。
 *         写 0 等于给出一个错误的硬断言。</td>
 *   </tr>
 *   <tr>
 *     <td>计数型（玩家数 / 实体数 / 区块数）</td>
 *     <td>{@code -1}</td>
 *     <td>0 是完全合法的（空服就是 0 人），因此只能用一个不可能的值表示"没采到"。</td>
 *   </tr>
 * </table>
 *
 * <p>这条约定不是洁癖。本插件 v1.4.2 与 v1.4.3 两个版本的修复全在这一个错误上——
 * 把 {@code 0} 当成"未采集"，导致空闲服务器的 CPU 显示"未知"、卡死过的服务器日报写
 * "最低 18.5 TPS"。跨服聚合会把这个错误放大：代理侧把 5 个后端求和时，一个被误记为 0
 * 的 TPS 会让全网平均值凭空变好。</p>
 *
 * <h2>版本协商</h2>
 *
 * <p>{@link #PROTOCOL_VERSION} 随协议不兼容变更递增。接收方**必须拒绝**自己不认识的高版本
 * （而不是"尽力解析"）——否则新后端发来的字段会被旧代理当成 0，正是上面那类静默失真。</p>
 */
public final class NetworkSummary {

    /**
     * 协议版本。不兼容变更（删字段、改语义、改单位）时递增。
     *
     * <p>1 = 初版：TPS(1m/5m/15m) + MSPT(avg) + 玩家数 + 快照年龄 + 采集存活标志。</p>
     *
     * <p><b>新增"可选字段"不升这个号</b>：接收方本来就要容忍缺字段（旧后端就是这种形状），
     * 发送方多发一个接收方不认识的键也不会让它出错（解析按 key 取值）。升号会让接收方
     * 拒收"比自己高"的整包，把一次平滑升级变成一段全丢窗口。只有**删字段、改语义、改单位**
     * 才需要升。</p>
     */
    public static final int PROTOCOL_VERSION = 1;

    /** plugin messaging 通道名。命名空间必须带插件名，避免与其它插件撞。 */
    public static final String CHANNEL = "perfscope:summary";

    /**
     * 子通道标识，放在字节流第一个字段。
     *
     * <p>为什么需要：插件消息通道是字符串寻址的，但同一条通道上后续可能还要发别的东西
     * （比如代理 → 后端的查询请求）。用一个显式的 kind 字段，比"根据长度猜"可靠。</p>
     */
    public static final String KIND_SUMMARY = "summary";

    /** 计数型字段的"没采到"。{@code 0} 是合法值，所以只能用负数。 */
    public static final int COUNT_UNAVAILABLE = -1;

    private NetworkSummary() {
    }

    /**
     * 一次上报的不可变快照。
     *
     * <p>字段用包装类型（{@code Double} / {@code Integer}）而不是基本类型，因为
     * {@code null} 就是"本周期没有这个值"——这是上面哨兵约定在 Java 侧的落地方式。
     * 用 {@code double} 的话，调用方被迫二选一：写 0（撒谎）或写 NaN（序列化后是字符串
     * {@code "NaN"}，Gson 会输出非法 JSON）。</p>
     */
    public static final class Report {
        private final String serverId;
        private final long sentAtMs;
        /** 数据采集时间；用于让接收方算快照年龄，从而识别"数字冻住了"。 */
        private final long collectedAtMs;
        /** 采集器是否还活着。false = 后面的数字都是陈的，不要拿来聚合。 */
        private final boolean collectorAlive;

        private final Double tps1m;
        private final Double tps5m;
        private final Double tps15m;
        private final Double msptAvg;
        private final int playerCount;
        private final int pushIntervalSec;

        private Report(Builder b) {
            this.serverId = b.serverId;
            this.sentAtMs = b.sentAtMs;
            this.collectedAtMs = b.collectedAtMs;
            this.collectorAlive = b.collectorAlive;
            this.tps1m = b.tps1m;
            this.tps5m = b.tps5m;
            this.tps15m = b.tps15m;
            this.msptAvg = b.msptAvg;
            this.playerCount = b.playerCount;
            this.pushIntervalSec = b.pushIntervalSec;
        }

        public String serverId() { return serverId; }
        public long sentAtMs() { return sentAtMs; }
        public long collectedAtMs() { return collectedAtMs; }
        public boolean collectorAlive() { return collectorAlive; }
        public Double tps1m() { return tps1m; }
        public Double tps5m() { return tps5m; }
        public Double tps15m() { return tps15m; }
        public Double msptAvg() { return msptAvg; }
        public int playerCount() { return playerCount; }

        /**
         * 本后端实际的上报间隔（秒），随包带给代理侧。
         *
         * <p><b>为什么必须随包走</b>：代理侧要判"多久没收到才算过期"，而它读不到后端的
         * {@code config.yml}。此前代理硬编码 {@code 3 × 10s = 30s}，于是服主把间隔调大
         * （比如 60 秒省流量）后，每两轮上报之间面板都会闪一段"数据过期"。把间隔放进包里，
         * 接收方就能按**这台后端自己的节奏**算过期，配置与判定不再耦合。</p>
         *
         * <p>0 = 发送方没有提供（旧版本后端）。接收方此时回退到自己的默认假设。</p>
         */
        public int pushIntervalSec() { return pushIntervalSec; }

        // 这里曾经有一个 ageMs(long nowMs) = max(0, now - collectedAt)。
        //
        // 它已删除，不是"暂时不用"，而是**不该存在**，理由三条：
        //
        // 1. 生产零调用。接收端算年龄的是代理侧的 SummaryPacket.ageMs(PacketAnchor)，
        //    它必须锚定在"包到达的那一刻"（否则伪造未来时间戳就能换来永久 0 岁）。
        //    那个锚点只有接收方有；发送方拿到一个 nowMs 根本算不出对方视角的年龄。
        // 2. 语义已经分叉。它是 max(0, ...)，于是"时间戳在未来"会被 clamp 成 0——
        //    正是 P1-V 修掉的那个 bug 的形状。留着一个同名字、同话题、结论相反的第二实现，
        //    下一个人按名字找"怎么算年龄"时会先撞上它。
        // 3. 唯一的消费者是一个断言 3_500L 的测试。改掉那个测试比留着这个函数便宜，
        //    而且改完之后测试钉住的是真正要钉的东西（"两个时间字段不可混用"）。
        //
        // 需要年龄请读 collectedAtMs() / sentAtMs() 并自己决定基准——
        // 基线只有一处，见代理侧 SummaryPacket#ageMs(PacketAnchor) 的推导。

        /**
         * 序列化成线上格式（扁平 Map，Gson 直接写）。
         *
         * <p><b>"不出现"是语义，不是省略</b>：{@code null} 的字段在这里被整条省掉。接收方
         * 必须用 {@code containsKey} 而不是 {@code get == null} 判断——这两者在 Gson
         * 反序列化回来的 {@code Map<String,Object>} 上恰好等价，但在"字段缺失"与
         * "字段显式为 null"之间不总是等价，显式约定省掉可以少一种歧义。</p>
         */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("v", PROTOCOL_VERSION);
            m.put("kind", KIND_SUMMARY);
            m.put("server", serverId);
            m.put("sentAt", sentAtMs);
            m.put("collectedAt", collectedAtMs);
            m.put("alive", collectorAlive);
            m.put("players", playerCount);
            // 只在有意义时带上：0 表示"发送端没提供"，省掉这个键，旧代理读不到也不会误判。
            if (pushIntervalSec > 0) m.put("pushIntervalSec", pushIntervalSec);
            if (tps1m != null) m.put("tps1m", tps1m);
            if (tps5m != null) m.put("tps5m", tps5m);
            if (tps15m != null) m.put("tps15m", tps15m);
            if (msptAvg != null) m.put("msptAvg", msptAvg);
            return m;
        }

        public static Builder builder(String serverId, long collectedAtMs, long sentAtMs) {
            return new Builder(serverId, collectedAtMs, sentAtMs);
        }

        public static final class Builder {
            private final String serverId;
            private final long collectedAtMs;
            private final long sentAtMs;
            private boolean collectorAlive = true;
            private Double tps1m;
            private Double tps5m;
            private Double tps15m;
            private Double msptAvg;
            private int playerCount = COUNT_UNAVAILABLE;
            private int pushIntervalSec;

            private Builder(String serverId, long collectedAtMs, long sentAtMs) {
                this.serverId = serverId;
                this.collectedAtMs = collectedAtMs;
                this.sentAtMs = sentAtMs;
            }

            public Builder collectorAlive(boolean v) { this.collectorAlive = v; return this; }
            public Builder tps(double v1m, double v5m, double v15m) {
                this.tps1m = v1m; this.tps5m = v5m; this.tps15m = v15m; return this;
            }
            public Builder msptAvg(double v) { this.msptAvg = v; return this; }
            public Builder playerCount(int v) { this.playerCount = v; return this; }
            /** 上报间隔（秒）。接收方靠它算过期阈值，写 0 等于"没提供"。 */
            public Builder pushIntervalSec(int v) { this.pushIntervalSec = v; return this; }

            public Report build() {
                return new Report(this);
            }
        }
    }
}
