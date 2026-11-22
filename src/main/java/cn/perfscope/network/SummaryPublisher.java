package cn.perfscope.network;

import cn.perfscope.PerfScope;
import cn.perfscope.compat.SchedulerAdapter;
import cn.perfscope.config.ConfigManager;
import cn.perfscope.metrics.MetricsCollector;
import cn.perfscope.metrics.MetricsSnapshot;
import cn.perfscope.util.Messages;
import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.plugin.messaging.PluginMessageRecipient;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * 后端侧：把摘要通过 plugin messaging 发给代理。
 *
 * <h2>这条链路为什么必须靠玩家</h2>
 *
 * <p>Paper 的 plugin messaging 走的是**已建立连接的玩家所持有的 TCP 连接**——插件消息被
 * 塞进那个玩家的数据包流里，由代理转发给其它插件。它不是一个独立通道。推论有两条，都很硬：</p>
 *
 * <ol>
 *   <li><b>空服时发不出去。</b>没有玩家就没有连接，{@link #publish} 会返回
 *       {@link Result#NO_PLAYER}，代理侧在这个窗口内看到的是"这个后端没有数据"。</li>
 *   <li><b>必须有一个玩家当载体。</b>消息由哪个玩家的连接送出去不影响内容，所以随便挑一个
 *       在线玩家即可（这里挑第一个）。</li>
 * </ol>
 *
 * <p>这两条是这个方案的固有代价，不是实现缺陷。空服恰恰常是最想看的时刻之一，所以代理侧
 * 的展示层必须把"没数据"与"数据是 0"分开——见 {@link NetworkSummary} 的哨兵约定。</p>
 *
 * <h2>为什么只在有变化时发</h2>
 *
 * <p>采集周期默认 1 秒（{@code general.update-interval}）。每个周期都发会把 5 个后端变成
 * 每秒 5 条跨服消息，而摘要的价值在"代理能看出谁不正常"，不在高频。因此按
 * {@code network.push-interval-seconds} 限频（默认 10 秒），并且**两次发送之间如果摘要
 * 一字未变就跳过**——空闲服务器的 TPS/MSPT/玩家数长时间不变，跳过能省掉绝大部分流量。</p>
 *
 * <p>注意"一字未变就跳过"有一个正确的例外：{@code collectedAt} 每次都变，所以不能用
 * 整条 map 比较。这里比较的是**语义字段**（tps* / mspt / players / alive）。</p>
 *
 * <h2>为什么载体来源是一个可注入的接缝</h2>
 *
 * <p>本类此前只有 {@code publish()} 的前半段（{@code pickCarrier()} 之前）能被单测跑到，
 * 而<b>整个失败分支一条都没跑过</b>——包括那段每 5 分钟才打一条的限频日志和
 * {@code consecutiveSendFailures} 计数。不是没人写测试，是写不出来：测试 JVM 里
 * {@code Proxy.newProxyInstance(Player.class, ...)} 会解析它继承链上的全部方法签名，
 * 牵出 {@code PotionEffectType} / {@code Registry} 之类需要 {@code Bukkit.server} 的静态块，
 * 直接 {@code ExceptionInInitializerError}（实测，见 {@code tools/KNOWN-TEST-LIMITS.md} 第 5 条）。</p>
 *
 * <p>于是把"用哪个连接发"抽成 {@link CarrierSource} 这一层。要点有三：</p>
 *
 * <ol>
 *   <li><b>它只挡住"造 Player"，不挡"发"。</b>发送动作仍然是真实的
 *       {@code carrier.sendPluginMessage(plugin, CHANNEL, payload)}，在同一段 {@code try} 里。
 *       所以测试注入一个"一被调用就抛"的载体时，走的是生产代码原本那条 catch，
 *       而不是被绕过或短路。</li>
 *   <li><b>载体类型放宽到 {@link PluginMessageRecipient}，不是改语义。</b>
 *       {@code Player} 唯一的发送方法本来就是从它继承的（{@code javap} 实测 Player
 *       没有重新声明 {@code sendPluginMessage}），而 {@code PluginMessageRecipient} 只有两个方法，
 *       给<b>它</b>建代理不会解析 Player 的继承链，因此造得出来。任何 Player 都是
 *       PluginMessageRecipient，所以这只是把静态类型挪到它本来所在的接口上。</li>
 *   <li><b>生产构造路径一字未改。</b>{@link #SummaryPublisher(PerfScope)} 直接把
 *       {@link CarrierSource#fromServer} 装进 final 字段；{@code PerfScope} 里的调用点
 *       仍然是一个参数。注入用的构造器是包内可见的，只给测试用。</li>
 * </ol>
 *
 * <p>换一个方向（注入 {@code BiConsumer<Player, byte[]>} 之类的"发送"接缝）解决不了这个问题：
 * 载体仍然是 {@code pickCarrier()} 造出来的 {@code Player}，测试照样死在创建那一步。</p>
 */
public final class SummaryPublisher {

    private final PerfScope plugin;
    private final Gson gson = new Gson();

    /**
     * "用哪个连接发"的来源。生产恒为 {@link CarrierSource#fromServer(PerfScope)}。
     *
     * <p>做成 final 字段而不是 setter：测试要能换载体（先失败后成功），用可变容器
     * （{@code AtomicReference}）包一层即可，不需要生产代码为测试留一个可变入口。</p>
     */
    private final CarrierSource carrierSource;

    /** 上一次成功发出去的语义摘要，用于"没变化就不发"。 */
    private String lastSentSemantic;
    private long lastSentAtMs;

    /**
     * 发送结果。给调用方（采集器）用于统计与日志，**不给使用者看**——使用者只关心
     * "代理那边有没有数"，那由代理侧回答。
     */
    public enum Result {
        /** 发出去了。 */
        SENT,
        /** 摘要有变化但限频还没到，这次跳过。 */
        THROTTLED,
        /** 摘要与上次完全相同，跳过。 */
        UNCHANGED,
        /** 没有玩家在线，通道不可用。这是 plugin messaging 的固有限制。 */
        NO_PLAYER,
        /** 功能没开。 */
        DISABLED,
        /**
         * 配置里的 {@code server-id} 过不了发送前的校验（超长 / 保留字），本次**不发**。
         *
         * <p>单独一个结果而不是并进 {@link #FAILED}：这不是"发送出错"，是"我们主动拒发"，
         * 排查方向完全不同——前者查网络，后者改配置。原因已由
         * {@link #warnBadServerId} 的告警说清楚。</p>
         */
        REJECTED_ID,
        /** 发送过程中抛异常（已被捕获，不影响采集）。 */
        FAILED
    }

    /**
     * {@code server-id} 的长度上限。<b>必须与代理侧 {@code SummaryPacket.MAX_SERVER_ID_LENGTH}
     * 逐字一致（64）。</b>
     *
     * <p><b>为什么发送端也要有这条闸门</b>：代理侧原本就拒收超长 id，但它是<b>静默丢弃</b>
     * （只记一条"无法解析的跨服摘要，请检查版本"）。于是服主把 id 配长了，症状是"这台后端
     * 在面板上永远隐身"，而日志把他引向"版本不一致"——一个完全无关的方向。发送端在发出前
     * 就拦住并说清"是长度问题"，是唯一能让这条路径可自查的做法。</p>
     *
     * <p>两个仓库不能共享代码（一个跑 Paper、一个跑 Velocity），所以这个数字是<b>协议常量</b>，
     * 改动它等于破坏两端兼容性。</p>
     */
    public static final int MAX_SERVER_ID_LENGTH = 64;

    /**
     * 保留 id：代理侧用它表示"这个包没有身份"（{@code SummaryPacket.getString} 的缺失默认值，
     * 事件层据此丢弃整包）。
     *
     * <p>服主真把 {@code server-id} 配成 {@code unknown} 时，那个后端会与"没配置"的包撞在
     * 同一个键上，且代理侧会把它当废包丢掉——同样表现为"永久隐身"。所以在发送端就拦掉。
     * 比较是<b>区分大小写</b>的（只有小写才算保留字）：{@code Unknown} 作为显式 id 仍然合法，
     * 这样既不破坏已有部署，又与代理侧那条 {@code "unknown".equals(...)} 的判据逐字对称。</p>
     */
    public static final String RESERVED_SERVER_ID = "unknown";

    /**
     * 一条 {@code server-id} 的判定结果。
     *
     * <p>分成四种而不是一个 {@code boolean}：告警必须说清"错在哪"——超长要告诉服主上限是 64，
     * 保留字要告诉他换一个名字，两者给出的修法完全不同。</p>
     *
     * <p><b>public</b> 是因为 {@code ConfigManager} 在加载期也要用同一条判据做一次告警
     * （启动即提示，而不是等到第一个采集周期）。判据只允许有一份——两份必然漂移。</p>
     */
    public enum ServerIdVerdict {
        /** 可用。 */
        OK,
        /** 空 / 纯空白（含 {@code null}）。不告警：调用方本来就会回退到下一级候选值。 */
        BLANK,
        /** 是保留字 {@link #RESERVED_SERVER_ID}。 */
        RESERVED,
        /** 去掉首尾空白后仍超过 {@link #MAX_SERVER_ID_LENGTH}。 */
        TOO_LONG
    }

    /**
     * 连续失败多少个采集周期后，认为采集器已死。
     *
     * <p>取 3：与后端告警的默认防抖（{@code alerts.debounce-cycles}）同口径——单次失败
     * 可能只是偶发抖动，连续 3 次才算"坏了"。跨服面板不需要比本地告警更敏感，
     * 但也不能像 {@code DEAD_AFTER_CONSECUTIVE_FAILURES}（10）那样迟钝：默认 1 秒周期下
     * 那是 10 秒，而代理侧按「3 × 上报间隔」判过期（默认间隔 10 秒即 30 秒），两者挤在一起
     * 就无法区分"采集中断"与"只是没变化"。</p>
     */
    private static final int DEAD_AFTER_FAILURES = 3;

    /**
     * 限频类告警的统一间隔：5 分钟一条。
     *
     * <p>与 {@code MetricsCollector} 的 {@code NETWORK_FAILURE_LOG_INTERVAL_MS} 同值同口径——
     * 三个日志点（server-id 未配置、时钟回拨、发送失败）共用这一个数量级，服主不会觉得
     * "跨服摘要这部分日志的节奏忽快忽慢"。5 分钟的理由：1 Hz 采集下这些失败每周期都会发生，
     * 不限频就是 86400 条/天；而跨服摘要只是附加视图，不需要比本地采集更快地报。</p>
     */
    private static final long WARN_INTERVAL_MS = 300_000L;

    /** server-id 未配置时的告警间隔（一个采集周期刷一条太吵）。 */
    private long lastIdWarnAtMs;

    /**
     * 上一次"时钟回拨"告警的时刻。
     *
     * <p>回拨是**一次性事件**而不是持续故障：时间轴跳到过去之后就不再继续跳，所以这里
     * 记的是"已经报过"的锚点。存的是<b>新时钟</b>下的时刻，于是下一次调用时差值为正常
     * 正数，限频判据恢复工作——不会在回拨窗口里每周期刷一条。</p>
     */
    private long lastClockRollbackWarnAtMs;

    /** 上一次"发送失败"告警的时刻（同 {@link #lastIdWarnAtMs} 的限频写法）。 */
    private long lastSendFailWarnAtMs;

    /** 连续失败次数：被限频吞掉的失败也要计数，否则"连续第 N 次"会永远显示 1。 */
    private int consecutiveSendFailures;

    /** 上一次 {@link #register()} 失败的原因（null = 成功或尚未登记）；供 /status 展示。 */
    private volatile String registrationFailureReason;

    /**
     * 挑一个能承载 plugin messaging 的连接。
     *
     * <p>抽出来的唯一目的是<b>让发送失败分支可测</b>（理由见类注释最后一节）。接口刻意只回答
     * "用谁发"，不回答"怎么发"——发送本身留在 {@link #publish} 里，才能保证测试跑的是
     * 生产那条 {@code try/catch}，而不是一个测试专用的替身路径。</p>
     *
     * <p>方法声明 {@code throws} 是有意为之：真实实现里 {@code plugin.getServer()} 可能是
     * {@code null}（测试壳、关服中途），抛出的异常由 {@link #pickCarrier()} 统一接住并降级成
     * {@code null}（→ {@code NO_PLAYER}）。把它声明在接口上，注入方就不必假装不会失败。</p>
     */
    @FunctionalInterface
    interface CarrierSource {
        /** @return 载体；{@code null} = 这次没有可用连接（空服） */
        PluginMessageRecipient pick() throws Exception;

        /**
         * 生产实现：取第一个在线玩家。
         *
         * <p>只把 {@code plugin.getServer().getOnlinePlayers()} 留在这里——那一行是测试 JVM
         * 唯一碰不到的部分（没有可用假 {@code Server}）。真正的"取第一个"判据在
         * {@link #firstOf} 里，那部分是纯数据、可单测的。</p>
         */
        static CarrierSource fromServer(PerfScope plugin) {
            return () -> firstOf(plugin.getServer().getOnlinePlayers());
        }

        /**
         * "取第一个，空集合当没有"这条判据本体。
         *
         * <p>两处防御都是真实的：{@code getOnlinePlayers()} 在个别实现上可能返回
         * {@code null}（API 约定不 null，但被包装过的服务端会），集合也可能为空（空服）。
         * 两者都必须降级成 {@code null} → {@code NO_PLAYER}，而不是让 {@code iterator().next()}
         * 抛 {@code NoSuchElementException}——那会被 {@code pickCarrier} 吞成"空服"，
         * 结论碰巧相同，但堆栈噪音会掩盖真正的原因。</p>
         *
         * <p>抽成静态方法是为了可测（见类注释最后一节）：它的输入是纯集合，不需要 Bukkit 运行时。</p>
         */
        static PluginMessageRecipient firstOf(
            Collection<? extends PluginMessageRecipient> players) {
            if (players == null || players.isEmpty()) return null;
            return players.iterator().next();
        }
    }

    /** 生产构造器：载体来源固定为"第一个在线玩家"。 */
    public SummaryPublisher(PerfScope plugin) {
        this(plugin, CarrierSource.fromServer(plugin));
    }

    /**
     * 注入载体来源。包内可见——只给测试用，生产代码请走 {@link #SummaryPublisher(PerfScope)}。
     *
     * <p>为什么不把它做成 public：一旦对外可见，"换一个载体来源"就成了这个类承诺出去的
     * 能力，而它实际上只是为了在无 Bukkit 的 JVM 里能把 {@code sendPluginMessage} 那一行跑起来。</p>
     */
    SummaryPublisher(PerfScope plugin, CarrierSource carrierSource) {
        this.plugin = plugin;
        this.carrierSource = carrierSource;
    }

    /**
     * 在插件启用时登记出站通道。
     *
     * <p>不登记的话 {@code sendPluginMessage} 会抛 {@code IllegalArgumentException}
     * （"Not registered"），而且是在采集线程里抛——会被外层 catch 吞掉，表现为
     * "功能开了但代理永远收不到"，是最难查的一类。所以登记失败必须大声。</p>
     *
     * <p><b>为什么返回 boolean 而不是自己打一条日志了事</b>：失败只记一条 warning 时，
     * 调用方会紧接着无条件打一条「已启用」——两条日志相邻，后一条更像结论，控制台的
     * 最终印象是"开好了"。登记成败是<b>调用方</b>才能正确叙述的事实（它同时知道配置开关、
     * 间隔与"要不要提玩家在线"），所以这里只把结果交出去，由调用方决定打哪一句。</p>
     *
     * @return true = 通道已登记；false = 登记抛异常（原因见 {@link #registrationFailureReason()}）
     */
    public boolean register() {
        try {
            plugin.getServer().getMessenger()
                .registerOutgoingPluginChannel(plugin, NetworkSummary.CHANNEL);
            registrationFailureReason = null;
            return true;
        } catch (Exception | LinkageError e) {
            // 留档给 /perfscope status：警告只在启动那一瞬间可见，翻过去就查不到了。
            registrationFailureReason = e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
            plugin.getLogger().warning("[PerfScope] 登记跨服通道失败，network 功能不可用："
                + registrationFailureReason);
            return false;
        }
    }

    public void unregister() {
        try {
            plugin.getServer().getMessenger()
                .unregisterOutgoingPluginChannel(plugin, NetworkSummary.CHANNEL);
        } catch (Exception | LinkageError ignored) {
            // 关服/重载期间通道可能已经没了。这里无事可做，也不该刷屏。
        }
    }

    /**
     * 尝试把当前快照发出去。
     *
     * <p>调用点：采集周期末尾（{@code MetricsCollector} 的定时任务里），**必须在主线程**——
     * plugin messaging 要求如此。采集本身已经在全局区域定时器里跑，所以这里不再自己调度。</p>
     *
     * @param snap 当前快照；{@code null} 或尚未采集成功时直接跳过
     * @param nowMs 当前时间
     */
    public Result publish(MetricsSnapshot snap, long nowMs) {
        if (snap == null) return Result.NO_PLAYER;

        ConfigManager.ConfigData cfg = plugin.getConfigManager() == null
            ? null : plugin.getConfigManager().get();
        if (cfg == null || !cfg.network_enabled) return Result.DISABLED;

        // 采集器还活着吗？
        //
        // ⚠️ 这里**不能**用 `!collector.isStale()`：本方法由采集周期调用，而成功周期
        // 的第一件事就是刷新 lastCollectAtMs，所以 isStale() 在那一刻恒为 false，
        // 这个字段会恒为 true —— COLLECTOR_DEAD 变成永远走不到的死代码。
        //
        // 用连续失败次数才是对的：它回答的正是"采集现在是不是坏了"。
        // 失败周期也会调用本方法（见 MetricsCollector#onCycleFailure），
        // 所以采集真的坏掉时，代理能在 3 个周期内看到 alive=false，
        // 而不是傻等 30 秒后把它判成"数据过期（可能空服）"。
        MetricsCollector collector = plugin.getMetricsCollector();
        boolean alive = collector == null || collector.consecutiveFailures() < DEAD_AFTER_FAILURES;
        long collectedAt = collector == null ? 0L : collector.lastCollectAtMs();

        // 身份先过闸：配错时**一条都不发**，并在日志里说清错在哪。
        //
        // 放在最前面（早于"有没有变化"与"有没有玩家"两重判断）是刻意的：那两重判断都会让
        // 本方法安静地返回，而身份问题是每次采集都成立的**配置错误**，必须每轮都被看见
        // ——否则会出现"空服期间改了 id，等有玩家了才发现发不出去"这种延迟极长的反馈。
        String serverId = resolveServerId(cfg);
        if (serverId == null) {
            return Result.REJECTED_ID;
        }

        NetworkSummary.Report report = buildReport(snap, cfg, serverId, collectedAt, nowMs, alive);

        // ---- 语义比较：collectedAt 每次都变，所以只比"有意义的字段" ----
        // pushIntervalSec 也算在内：reload 改了间隔后必须让代理侧收到新值，
        // 否则它会一直按旧间隔判过期。
        String semantic = report.tps1m() + "|" + report.tps5m() + "|" + report.tps15m()
            + "|" + report.msptAvg() + "|" + report.playerCount() + "|" + report.collectorAlive()
            + "|" + report.pushIntervalSec();

        long intervalMs = Math.max(1, cfg.network_pushIntervalSeconds) * 1000L;

        // 时钟回拨：先把节流基准拉回新时间轴，再继续照常判断。
        //
        // 为什么必须在 shouldSend 之前处理而不是在里面顺手改字段：shouldSend 是纯函数，
        // 它的存在意义就是"能在单测里逐条验证、不碰任何状态"。把状态写进去会让它变成
        // 又一层需要装配的依赖。所以这里由调用方负责落地：重置 lastSentAtMs 让后续的
        // "距上次多久"重新有意义，清空 lastSentSemantic 保证这一包一定是"有变化"的。
        //
        // 告警是硬要求。回拨本身是一次性事件，但它造成的停发是**完全静默**的：
        // 如果这里不报，服主唯一的线索是"代理面板显示数据过期"，而本机一切正常。
        // 限频用 WARN_INTERVAL_MS，锚点记在新时钟上——回拨之后不再继续回拨，
        // 所以差值恢复为正数，不会在回拨窗口里每周期刷一条。
        if (isClockRollback(nowMs, lastSentAtMs)) {
            long backwardsMs = lastSentAtMs - nowMs;
            long warnNow = System.currentTimeMillis();
            if (warnNow - lastClockRollbackWarnAtMs > WARN_INTERVAL_MS) {
                lastClockRollbackWarnAtMs = warnNow;
                plugin.getLogger().warning("[PerfScope] 检测到系统时钟回拨 "
                    + formatDuration(backwardsMs) + "（上次上报时刻晚于当前时间）。"
                    + "已重置上报节流基准并继续发送。常见原因：虚拟机从快照恢复、容器迁移、"
                    + "NTP 大步校正、宿主机挂起后恢复。若不重置，跨服摘要会一直停发到墙钟"
                    + "追回原时间点为止，而代理侧只会显示「数据过期」。");
            }
            // 同时重置语义串：回拨后 collectedAt 也跟着倒退，若不重发，接收方算出的
            // 数据年龄会是负值（被 clamp 成 0，看着"很新"），掩盖真实情况。
            lastSentSemantic = null;
            lastSentAtMs = 0L;
            // 其余两个限频锚点也一起清掉。它们和 lastSentAtMs 一样记在旧时间轴上，
            // 回拨后同样落在"未来"，会让那两条告警在回拨窗口内一直打不出来——
            // 也就是说：只修发送而不修这两个锚点，日志静默会从"停发"转移到"身份/失败告警"
            // 上，问题只是换了个地方藏。用 0 表示"没记过"，限频判据因此立刻放行。
            lastSendFailWarnAtMs = 0L;
            lastIdWarnAtMs = 0L;
        }

        // 保底重发：即使摘要一字未变，也要按间隔重发一次。
        //
        // 没有这一步会出一个很隐蔽的错：接收方靠包里 collectedAt 算"数据年龄"，
        // 而"没变化就不发"意味着 collectedAt 一起冻住 → 过几个间隔后一个**完全健康、
        // 一直在采集**的后端被判成"数据过期（可能空服）"。
        // 空闲服最容易触发：TPS 恒 20.00、玩家数不变、MSPT 模块关掉时语义串永远不变。
        //
        // 所以 UNCHANGED 只在**距上次发送还没到一个间隔**时才成立；超过一个间隔，
        // 无论有没有变化都必须重发，让 collectedAt 前进。判据本体在 shouldSend（可单测）。
        if (!shouldSend(semantic, lastSentSemantic, nowMs, lastSentAtMs, intervalMs)) {
            return Result.UNCHANGED;
        }

        PluginMessageRecipient carrier = pickCarrier();
        if (carrier == null) return Result.NO_PLAYER;

        try {
            byte[] payload = gson.toJson(report.toMap()).getBytes(StandardCharsets.UTF_8);
            carrier.sendPluginMessage(plugin, NetworkSummary.CHANNEL, payload);
            lastSentSemantic = semantic;
            lastSentAtMs = nowMs;
            consecutiveSendFailures = 0;
            return Result.SENT;
        } catch (Exception | LinkageError e) {
            // 跨服链路坏掉绝不能影响本地采集：采集是这个插件的主功能，网络只是附加视图。
            //
            // 限频（P1-P）：这个 catch 在 1 Hz 采集下每周期都会进一次，不限频就是
            // 86400 条 WARNING/天。外层 MetricsCollector 那条 300 秒限频**够不到这里**——
            // publish() 把异常内部吞掉并返回 FAILED，外层的 catch 永远进不去，
            // 所以限频只能做在抛出点这一侧。
            //
            // 计数照常累加（不受限频影响）：被吞掉的失败如果也不计数，日志里的
            // "连续第 N 次"会永远显示 1，反而把"已经坏了一整天"说成"刚坏"。
            consecutiveSendFailures++;
            long failNow = System.currentTimeMillis();
            if (failNow - lastSendFailWarnAtMs > WARN_INTERVAL_MS) {
                lastSendFailWarnAtMs = failNow;
                plugin.getLogger().warning("[PerfScope] 跨服摘要发送失败（连续第 "
                    + consecutiveSendFailures + " 次；同类日志每 "
                    + (WARN_INTERVAL_MS / 1000L) + " 秒最多一条）："
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return Result.FAILED;
        }
    }

    /**
     * 把毫秒差渲染成"1 天 2 小时 3 分"这种可读形式，用于时钟回拨告警。
     *
     * <p>裸毫秒数（{@code 3600000}）在告警里等于没说——服主无法一眼判断揪出的是 1 小时还是 1 天。
     * 只取最大的两个单位：回拨 1 天 23 分 11 秒时，"1 天 23 分钟"足够定位，再精确也没有行动价值。</p>
     *
     * <p>不做 i18n：这是 Duration 的渲染，与 {@code DurationFormat} 的既有口径同源；
     * 调用点只有一处，且它产出的片段嵌在一条已经本地化的句子中间。</p>
     */
    static String formatDuration(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        long[] values = {days, hours, minutes, seconds};
        String[] units = {" 天", " 小时", " 分", " 秒"};
        StringBuilder sb = new StringBuilder();
        // 只取**前两个非零**单位：回拨 1 天 23 分 11 秒时 "1 天 23 分" 足够定位，
        // 再精确也没有行动价值；而写成 "1 天 0 小时 0 分 3 秒" 这种尾部噪音会让人
        // 误以为精度很重要。用"前两个非零"而不是"前两级"是因为后者会输出 "1 天 0 小时"。
        int taken = 0;
        for (int i = 0; i < values.length && taken < 2; i++) {
            if (values[i] == 0L) {
                continue;
            }
            if (taken > 0) {
                sb.append(' ');
            }
            sb.append(values[i]).append(units[i]);
            taken++;
        }
        // 全零（或不足 1 秒）：必须给一个数，不能返回空串——告警里出现"检测到时钟回拨 （上次…）"
        // 这种空洞比不写还难读。
        if (sb.length() == 0) {
            return "0 秒";
        }
        return sb.toString();
    }

    /**
     * 该不该在这一次发送。
     *
     * <p><b>抽成静态纯函数是为了可测</b>：这类"什么时候发"的判据一旦写错，症状是
     * 远端显示"数据过期"而本机一切正常——最难查的一类。它不依赖任何 Bukkit 类型，
     * 所以能在单测 JVM 里逐条验证。</p>
     *
     * <p>规则只有一条但很关键：<b>超过一个上报间隔就必须发，哪怕摘要一字未变</b>。
     * 接收方靠包里的 {@code collectedAt} 算数据年龄；"没变化就不发"会让 collectedAt
     * 一起冻住，于是完全健康的后端在 30 秒后被判成"数据过期"。</p>
     *
     * @param semantic        本次摘要的语义串
     * @param lastSemantic    上次发送的语义串（从未发过传 {@code null}）
     * @param nowMs           现在
     * @param lastSentAtMs    上次发送时刻（从未发过传 0）
     * <p><b>时钟回拨必须单独处理</b>（P1-N）：{@code lastSentAtMs} 是"上一次发送时刻"，
     * 而 {@code nowMs} 来自墙钟。两者之间出现负数时（VM 从快照恢复 / 容器迁移 /
     * NTP 大步校正 / 宿主机挂起恢复）{@code nowMs - lastSentAtMs} 为负，
     * {@code intervalPassed} 恒为 false，两条分支于是<b>同时</b>返回 false——
     * {@code !changed && !intervalPassed} 走不通，{@code changed ? intervalPassed : true} 也走不通。
     * 一条包都不发，直到墙钟追回原时间点：回拨 1 天就停发 1 天，而控制台没有任何输出。
     * 这类"完全静默失效"比任何显示错误都糟，所以判据里必须给它一条显式的放行分支。</p>
     *
     * @param semantic        本次摘要的语义串
     * @param lastSemantic    上次发送的语义串（从未发过传 {@code null}）
     * @param nowMs           现在
     * @param lastSentAtMs    上次发送时刻（从未发过传 0）
     * @param intervalMs      上报间隔
     */
    static boolean shouldSend(String semantic, String lastSemantic,
                              long nowMs, long lastSentAtMs, long intervalMs) {
        // 从没发过：立刻发。
        if (lastSemantic == null) {
            return true;
        }
        // 时钟回拨：时间轴倒退，任何"距上次多久"的判据都失去意义。立刻放行，
        // 让 collectedAt 重新前进；节流状态由 publish() 一并重置并告警。
        if (isClockRollback(nowMs, lastSentAtMs)) {
            return true;
        }
        // 摘要变了：只要过了间隔就发（间隔内等下一轮，避免抖刷）。
        boolean changed = !semantic.equals(lastSemantic);
        boolean intervalPassed = nowMs - lastSentAtMs >= Math.max(1L, intervalMs);
        if (!changed && !intervalPassed) {
            return false;
        }
        // 变了但还没到间隔 → 不发（限频）；没变但到了间隔 → 发（保底心跳）。
        return changed ? intervalPassed : true;
    }

    /**
     * 时间轴是否倒退了。
     *
     * <p>{@code lastSentAtMs == 0} 是"从未发送"的哨兵，不是"1970 年发过一次"——
     * 所以它必须排除，否则首次发送前每一轮都会被判成回拨。</p>
     *
     * <p>抽成静态纯函数是为了能单测：这一类判据写错时的症状是"远端说数据过期、本机一切正常"，
     * 靠人工复现几乎不可能，只能靠在单测里把时间戳摆到回拨后的位置。</p>
     */
    static boolean isClockRollback(long nowMs, long lastSentAtMs) {
        return lastSentAtMs > 0L && nowMs < lastSentAtMs;
    }

    /**
     * 组装摘要。
     *
     * <p>每个可空字段都要先问"这个模块可不可信"，不可信就**不填**（保持 {@code null}），
     * 而不是填 0。理由见 {@link NetworkSummary} 的哨兵约定。</p>
     */
    private NetworkSummary.Report buildReport(MetricsSnapshot snap, ConfigManager.ConfigData cfg,
                                              String serverId, long collectedAt, long nowMs,
                                              boolean alive) {
        NetworkSummary.Report.Builder b =
            NetworkSummary.Report.builder(serverId, collectedAt == 0L ? nowMs : collectedAt, nowMs)
                .collectorAlive(alive)
                // 把本机实际间隔告诉代理侧：它读不到这里的 config.yml，硬编码的过期阈值
                // 会让调大间隔的服每两轮上报之间都闪一次"数据过期"。
                .pushIntervalSec(cfg.network_pushIntervalSeconds);

        // TPS：模块被关掉 / Folia 上取不到时不填。
        if (fieldsAvailable(MetricsSnapshot.MODULE_TPS, snap.unavailable.contains(
                MetricsSnapshot.MODULE_TPS), snap.tps1m, -1)) {
            b.tps(snap.tps1m, snap.tps5m, snap.tps15m);
        }

        // MSPT：三个闸门都要过。
        //
        // 第三个别漏：TPS 反推那条路径在"满 20 TPS"时会置 msptAvg = -1，
        // 但**不**把 MODULE_MSPT 加进 unavailable，且 msptSource 是 TPS_FALLBACK(2)
        // 而不是 UNKNOWN(-1) —— 只看前两个闸门会让 -1 被当成真实读数发出去，
        // 代理上渲染成绿色的 "-1.0 毫秒"（"tick 耗时负一毫秒"）。
        boolean msptUnavailable = snap.unavailable.contains(MetricsSnapshot.MODULE_MSPT)
            || snap.msptSource == MetricsSnapshot.MSPT_SOURCE_UNKNOWN;
        if (fieldsAvailable(MetricsSnapshot.MODULE_MSPT, msptUnavailable, snap.msptAvg, 0)) {
            b.msptAvg(snap.msptAvg);
        }

        // 玩家数：计数型，没采到用 -1 而不是 0（0 人空服是合法的）。
        if (!snap.unavailable.contains(MetricsSnapshot.MODULE_PLAYERS)) {
            b.playerCount(snap.playerCount);
        }

        return b.build();
    }

    /**
     * 一个数值字段该不该发。
     *
     * <p>两个条件都要满足：模块可用，且值本身是有效的（大于 {@code minExclusive}）。
     * 第二个条件是防哨兵的：{@code msptAvg} 在不可用时是 -1，{@code tps1m} 不可用时是 -1。
     * 只查 unavailable 列表是不够的——有的路径置了哨兵值却没把模块加进 unavailable。</p>
     */
    static boolean fieldsAvailable(String module, boolean moduleUnavailable,
                                   double value, double minExclusive) {
        if (moduleUnavailable) {
            return false;
        }
        return value > minExclusive;
    }

    /**
     * 跨服身份。优先 {@code network.server-id}，其次 {@code prometheus.server-id}，
     * 都为空时**不猜**——返回端口派生的值并告警。
     *
     * <p><b>为什么不再回退到"首个世界名"</b>：绝大多数服的首个世界都叫 {@code world}，
     * 于是 5 个后端全部派生成同一个 id，代理侧 {@code latest.put} 后到覆盖先到，
     * 面板上只剩 1 行、全网平均值与在线合计全按 1 台算。这个错误不报错、不告警，
     * 只是数字全错——是最坏的一类。</p>
     *
     * <p><b>为什么也不用 serverTag（MOTD）</b>：多服常是同一套模板，或会被随手改。</p>
     *
     * <p>端口在同机多实例时是唯一的，跨机时通常也不同（25565/25566/...），
     * 比 {@code world} 可靠得多；而"同机同端口"根本不存在。所以回退到端口，
     * 并明确告诉服主应该显式配置。</p>
     *
     * <h2>为什么显式配置的值还要再校验一次</h2>
     *
     * <p>代理侧对 {@code server} 字段有两道闸门（长度上限 64、保留字 {@code unknown}），
     * 越界的包会被<b>整包丢弃</b>。此前发送端只做 {@code trim()}，于是"配错了"的表现是
     * 该后端在面板上永久隐身，而代理日志说的是"无法解析的跨服摘要，请检查版本"——
     * 把服主引向一个完全无关的方向（两端版本）。发送端在发之前就拦住，并明确说
     * "是长度 / 是保留字"，是唯一能让这条路径自查的做法。</p>
     *
     * <p><b>为什么是拒发而不是截断，也不是"悄悄改用派生值"</b>：截断会得到一个用户没写过的
     * id（前 64 个字符），面板上出现一个谁也对不上的名字；而悄悄改用 {@code port-25566}
     * 更糟——那台后端会以派生名正常出现在面板上，服主看到"有数"就不会再查，
     * 同时他真正配的那个名字一直显示「无数据」，成了一行查不出原因的幽灵行。
     * 所以显式配了却不合格时<b>这一台什么都不发</b>，让它在面板上如实缺席，
     * 配合控制台那条点名"长度 / 保留字"的告警，服主能立刻定位。</p>
     *
     * <p><b>空值仍然逐级回退</b>（{@code network.server-id} → {@code prometheus.server-id} →
     * 端口派生）：那是 {@code config.yml} 里写明的既有语义（"留空 = 回退"），不是错误，
     * 不能因为加了校验就把"留空"也判成拒发。</p>
     *
     * @return 可安全发出去的 id；{@code null} = 显式配置不合格，本次**不要发送**
     */
    private String resolveServerId(ConfigManager.ConfigData cfg) {
        // 显式值：先 network，再 prometheus。两者都**只判"配了没有"**，配了就必须合格。
        // 记住来源键名只为让告警点名——服主看到 prometheus.server-id 被拒时才不会跑去
        // 改一个他根本没生效过的 network.server-id。
        String explicit;
        String explicitKey;
        if (cfg.network_serverId != null && !cfg.network_serverId.isBlank()) {
            explicit = cfg.network_serverId;
            explicitKey = "network.server-id";
        } else if (cfg.prometheus_serverId != null && !cfg.prometheus_serverId.isBlank()) {
            explicit = cfg.prometheus_serverId;
            explicitKey = "prometheus.server-id";
        } else {
            explicit = null;
            explicitKey = null;
        }
        if (explicit != null) {
            ServerIdVerdict v = validateServerId(explicit);
            if (v != ServerIdVerdict.OK) {
                warnBadServerId(v, explicitKey, explicit);
                return null;
            }
            return explicit.trim();
        }

        // 都没配：派生端口。派生值理论上不会超长，但这里是最后一道防御。
        String derived;
        try {
            derived = "port-" + plugin.getServer().getPort();
        } catch (Exception | LinkageError ignored) {
            derived = "perfscope";
        }
        ServerIdVerdict derivedVerdict = validateServerId(derived);
        if (derivedVerdict != ServerIdVerdict.OK) {
            warnBadServerId(derivedVerdict, "network.server-id", derived);
            return null;
        }
        // 限频告警：不能每个采集周期刷一条。
        long now = System.currentTimeMillis();
        if (now - lastIdWarnAtMs > WARN_INTERVAL_MS) {
            lastIdWarnAtMs = now;
            plugin.getLogger().warning("[PerfScope] network.server-id 未配置，已自动派生为 \""
                + derived + "\"。多后端环境下每台必须显式配一个不同的值，"
                + "否则代理侧会把它们的数据混成一条。");
        }
        return derived;
    }

    /**
     * 判定一条 {@code server-id} 能不能发出去。
     *
     * <p><b>纯函数、不依赖 Bukkit</b>：这是本类唯一值得逐条单测的判据，也是与代理侧
     * {@code SummaryPacket.parse} 必须逐字对称的那一半。做成静态是因为测试只需要它，
     * 不需要构造一个 {@link PerfScope}。</p>
     *
     * <p>顺序有讲究：先判空白（调用方本来就会降级，不告警），再判保留字，最后判长度。
     * 反过来先判长度的话，一个 70 字符的保留字会被报成"超长"——服主改成 60 字符的
     * {@code unknown} 还是发不出去，白跑一轮。</p>
     *
     * @param raw 配置原值（可为 {@code null}）
     */
    public static ServerIdVerdict validateServerId(String raw) {
        if (raw == null || raw.isBlank()) {
            return ServerIdVerdict.BLANK;
        }
        String trimmed = raw.trim();
        // 保留字必须区分大小写地判：代理侧那条判据是 "unknown".equals(...)，
        // 大写 Unknown 在那侧是合法的显式 id，这里若也拦掉，就变成"后端拒发、代理收得下"。
        if (RESERVED_SERVER_ID.equals(trimmed)) {
            return ServerIdVerdict.RESERVED;
        }
        // 长度按**字符数**而不是字节数：协议里它是 JSON 字符串，代理侧读回来后
        // 用的也是 String.length()，两边必须同一口径（中文 id 一字算多字节但只算 1 长度）。
        if (trimmed.length() > MAX_SERVER_ID_LENGTH) {
            return ServerIdVerdict.TOO_LONG;
        }
        return ServerIdVerdict.OK;
    }

    /**
     * 输出一次"身份不可用"的告警，按 {@link #WARN_INTERVAL_MS} 限频。
     *
     * <p><b>文案必须点名"长度"或"保留字"</b>：这条日志存在的全部意义就是让服主不必去猜。
     * 此前代理侧那句"无法解析的跨服摘要，请检查版本"之所以有害，正是因为它说了一件
     * 真实但不相关的事。所以这里宁可写长一点，也不接受"配置无效"这种可指向任何原因的措辞。</p>
     *
     * <p>文案走 i18n（{@code config.check.network-server-id-too-long} /
     * {@code -reserved}）：这些是面向服主的诊断输出，与本项目其它配置告警同口径，
     * 与 {@code ConfigManager#checkCrossServerId} 加载期那一条<b>是同一对键</b>——
     * 两处说的是同一件事，就不该有两份措辞。</p>
     *
     * @param verdict 判定结果（决定用哪条文案）
     * @param key     来源配置键名（{@code network.server-id} / {@code prometheus.server-id}）
     * @param value   被拒的原值（日志里保留原样，方便对照 config.yml）
     */
    private void warnBadServerId(ServerIdVerdict verdict, String key, String value) {
        long now = System.currentTimeMillis();
        if (now - lastIdWarnAtMs <= WARN_INTERVAL_MS) {
            return;
        }
        lastIdWarnAtMs = now;
        // 长度类告警要报出真实字符数：只写"超长"时，服主不知道要砍掉多少。
        int actualLen = value == null ? 0 : value.trim().length();
        String text = verdict == ServerIdVerdict.RESERVED
            ? Messages.get("config.check.network-server-id-reserved",
                key, value == null ? "" : value.trim(), RESERVED_SERVER_ID)
            : Messages.get("config.check.network-server-id-too-long",
                key, value == null ? "" : value.trim(), String.valueOf(actualLen),
                String.valueOf(MAX_SERVER_ID_LENGTH));
        plugin.getLogger().warning("[PerfScope] " + text);
    }

    /**
     * 挑一个能承载 plugin messaging 的连接。任意一个都行——消息内容与载体无关。
     *
     * <p>失败一律降级成 {@code null}（→ {@code NO_PLAYER}），不向上抛：取不到玩家是 plugin
     * messaging 的固有限制，不是错误路径——空服恰恰常是最想看的时刻之一。</p>
     *
     * <p>{@code catch} 里必须同时接 {@code LinkageError}：本仓库测试 JVM 里
     * {@code getOnlinePlayers()} 抛的是 {@code NoClassDefFoundError}（不是 Exception），
     * 只写 {@code catch (Exception)} 会让它穿出 {@code publish()} 打穿整个采集周期。
     * 生产环境上这条防御也成立——一个被半卸载的插件会抛 {@code NoClassDefFoundError}。</p>
     *
     * <p><b>为什么返回类型是 {@link PluginMessageRecipient} 而不是 {@code Player}</b>：
     * 发送这个动作只需要 {@code sendPluginMessage}，而 {@code Player} 是从
     * {@link PluginMessageRecipient} 继承来的（{@code javap} 实测 Player 未重新声明它）。
     * 放宽到接口后，测试才能给这个只有两个方法的接口建代理——给 {@code Player} 建代理会解析
     * 它七层继承链上的全部方法签名并触发 {@code Bukkit.server} 的静态初始化。</p>
     */
    private PluginMessageRecipient pickCarrier() {
        try {
            return carrierSource.pick();
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** 重载配置时重置限频状态，让新配置立刻生效。 */
    public void resetState() {
        lastSentSemantic = null;
        lastSentAtMs = 0L;
    }

    /** 已登记通道但尚未发送时也必须能被查询到状态（给 /perfscope status 用）。 */
    public boolean channelRegistered() {
        try {
            return plugin.getServer().getMessenger()
                .isOutgoingChannelRegistered(plugin, NetworkSummary.CHANNEL);
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /**
     * {@link #register()} 失败的原因（null = 没失败过 / 尚未登记）；给 /perfscope status 用。
     *
     * <p>为什么要留这一份而不是让 /status 自己再问一次 {@link #channelRegistered()}：
     * 那条只能回答"现在登记着没有"，回答不了"为什么没登记上"。启动时的 warning 早已
     * 滚出控制台，翻不到原因时管理员唯一能做的就是盲目重启。</p>
     */
    public String registrationFailureReason() {
        return registrationFailureReason;
    }
}
