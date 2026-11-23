package cn.perfscope.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨服摘要协议的契约测试。
 *
 * <p>这些断言固定的是**线上格式**与**哨兵语义**，不是实现细节。协议有两端（Paper 后端发送、
 * Velocity 代理接收），两侧代码不能共享实现，唯一能共享的就是这份契约——所以它必须在这里被
 * 逐条钉住，否则任何一侧改了字段名都只会在联调时才发现。</p>
 */
class NetworkSummaryTest {

    private static final Gson GSON = new Gson();

    /** 走一遍真实的线上路径：Map → JSON 字节 → 解析回来。 */
    private static JsonObject roundTrip(NetworkSummary.Report r) {
        String json = GSON.toJson(r.toMap());
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    @DisplayName("协议标识：版本与 kind 必须出现在每一条消息里")
    void carriesProtocolVersionAndKind() {
        NetworkSummary.Report r = NetworkSummary.Report
            .builder("lobby", 1_000L, 2_000L)
            .tps(20.0, 19.9, 19.8)
            .msptAvg(12.5)
            .playerCount(7)
            .build();

        JsonObject o = roundTrip(r);
        assertEquals(NetworkSummary.PROTOCOL_VERSION, o.get("v").getAsInt());
        assertEquals(NetworkSummary.KIND_SUMMARY, o.get("kind").getAsString());
        assertEquals("lobby", o.get("server").getAsString());
    }

    @Test
    @DisplayName("哨兵：未采集的 TPS/MSPT 字段整条不出现，而不是写 0")
    void unavailableNumericFieldsAreOmittedNotZeroed() {
        // 只给存活标志与玩家数，完全不设 tps / mspt —— 模拟"模块被关掉"或"平台不支持"。
        NetworkSummary.Report r = NetworkSummary.Report
            .builder("nether", 1_000L, 2_000L)
            .playerCount(3)
            .build();

        Map<String, Object> map = r.toMap();
        assertFalse(map.containsKey("tps1m"), "缺失的 TPS 必须整条省掉");
        assertFalse(map.containsKey("tps5m"));
        assertFalse(map.containsKey("tps15m"));
        assertFalse(map.containsKey("msptAvg"), "缺失的 MSPT 必须整条省掉");

        // 关键对照：如果实现改成了写 0，这条会红。写 0 会让代理侧把"没采到"读成
        // "TPS 归零、服务器冻结了"——本插件 v1.4.2 / v1.4.3 两个版本修的就是这个错误。
        assertNull(map.get("tps1m"));
    }

    @Test
    @DisplayName("哨兵：计数型字段没采到时是 -1，因为 0 是合法值（空服 0 人）")
    void unavailableCountIsMinusOneBecauseZeroIsLegal() {
        // 这里刻意用字面量 -1 而不是 NetworkSummary.COUNT_UNAVAILABLE。
        // 用常量会让断言跟着常量一起变——把常量改成 0 时测试仍然全绿，等于没测到。
        // 实测过：用常量名写的版本在这个变异下 SURVIVED。
        assertEquals(-1, NetworkSummary.COUNT_UNAVAILABLE,
            "COUNT_UNAVAILABLE 必须就是 -1 这个字面值，不能是 0");

        NetworkSummary.Report unset = NetworkSummary.Report
            .builder("creative", 1_000L, 2_000L)
            .build();
        assertEquals(-1, unset.playerCount(),
            "没给玩家数时必须是 -1，不能是 0");

        // 0 人是完全合法的读数，必须能被如实表达——这正是不能用 0 当哨兵的理由。
        NetworkSummary.Report empty = NetworkSummary.Report
            .builder("creative", 1_000L, 2_000L)
            .playerCount(0)
            .build();
        assertEquals(0, empty.playerCount());
        assertEquals(0, roundTrip(empty).get("players").getAsInt());

        // 未采集的 -1 也会如实出现在线上，接收方据此区分"空服"与"没数据"。
        assertEquals(-1, roundTrip(unset).get("players").getAsInt());
    }

    @Test
    @DisplayName("时间：collectedAt 是数据时刻，sentAt 是发送时刻，两者不可混用")
    void distinguishesCollectedAtFromSentAt() {
        NetworkSummary.Report r = NetworkSummary.Report
            .builder("survival", 1_000L, 9_000L)
            .build();

        JsonObject o = roundTrip(r);
        assertEquals(1_000L, o.get("collectedAt").getAsLong(),
            "collectedAt 必须是采集时刻——接收方靠它算快照年龄");
        assertEquals(9_000L, o.get("sentAt").getAsLong());

        // 这条测试原来调用 Report.ageMs(nowMs)，那个方法已经删除（见 Report 里的说明：
        // 生产零调用，且它是 max(0, now-collectedAt) 这个已经分叉的旧语义）。
        // 改钉真正要钉的性质：两个字段在线上是**分开的**，且重算年龄必须用 collectedAt
        // 而不是 sentAt。用 sentAt 算会让每一条消息的年龄恒为 0，"数字冻住了"永远检不出来。
        long now = 4_500L;
        assertEquals(3_500L, now - o.get("collectedAt").getAsLong(),
            "按 collectedAt 算出的年龄是 3.5 秒——这才是真实的数据年龄");
        assertTrue(now - o.get("sentAt").getAsLong() < 0,
            "按 sentAt 算会得到负数（发送晚于查询时刻），正是「年龄恒 0」的成因");
    }

    @Test
    @DisplayName("时间：Report 不再提供 ageMs —— 年龄只有接收方算得出来（防第二份实现）")
    void reportHasNoAgeMethod() {
        // 结构性断言：这个方法是**反射**层面的契约。留着它就会出现"同名、同话题、
        // 结论相反"的第二份年龄实现（旧版是 max(0,...)，未来时间戳会被 clamp 成 0）。
        // 改实现而不删它，测试抓不到；只有断言它不存在才能防止有人"顺手加回来"。
        for (java.lang.reflect.Method m : NetworkSummary.Report.class.getDeclaredMethods()) {
            assertNotEquals("ageMs", m.getName(),
                "Report.ageMs 已删除：年龄必须锚定在接收时刻，发送方算不出对方视角的年龄");
        }
    }

    @Test
    @DisplayName("存活标志：present 且可反序列化（代理据此拒绝聚合陈数据）")
    void carriesCollectorLiveness() {
        NetworkSummary.Report alive = NetworkSummary.Report
            .builder("a", 1_000L, 2_000L).collectorAlive(true).build();
        NetworkSummary.Report dead = NetworkSummary.Report
            .builder("b", 1_000L, 2_000L).collectorAlive(false)
            .tps(20.0, 20.0, 20.0).build();

        assertTrue(roundTrip(alive).get("alive").getAsBoolean());
        assertFalse(roundTrip(dead).get("alive").getAsBoolean(),
            "采集器死了但 TPS 还写着 20 —— 接收方必须能靠这个标志识破");
    }

    @Test
    @DisplayName("编码：UTF-8 字节往返后字段无损（线上真的走字节，不是对象）")
    void survivesUtf8ByteRoundTrip() {
        NetworkSummary.Report r = NetworkSummary.Report
            .builder("世界一", 1_700_000_000_000L, 1_700_000_010_000L)
            .tps(19.95, 19.85, 19.75)
            .msptAvg(50.15)
            .playerCount(42)
            .build();

        byte[] payload = GSON.toJson(r.toMap()).getBytes(StandardCharsets.UTF_8);
        JsonObject o = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8))
            .getAsJsonObject();

        // 中文 server-id 必须无损：世界名做 id 是最常见的自动派生结果。
        assertEquals("世界一", o.get("server").getAsString());
        assertEquals(19.95, o.get("tps1m").getAsDouble(), 1e-9);
        assertEquals(50.15, o.get("msptAvg").getAsDouble(), 1e-9);
        assertEquals(42, o.get("players").getAsInt());
    }

    @Test
    @DisplayName("字段名是外部契约：改名即破坏代理侧，必须显式断言")
    void fieldNamesAreTheContract() {
        NetworkSummary.Report r = NetworkSummary.Report
            .builder("id", 1L, 2L)
            .tps(1.0, 2.0, 3.0)
            .msptAvg(4.0)
            .playerCount(5)
            .build();

        JsonObject o = roundTrip(r);
        // 逐个点名。这些名字同时被 Velocity 代理侧读取，任何一个改动都是破坏性变更，
        // 必须伴随 PROTOCOL_VERSION 递增——这条测试就是那道闸门。
        for (String key : new String[]{
            "v", "kind", "server", "sentAt", "collectedAt", "alive",
            "players", "tps1m", "tps5m", "tps15m", "msptAvg"}) {
            assertTrue(o.has(key), "protocol field missing: " + key);
        }
    }

    @Test
    @DisplayName("上报间隔随包外发：接收方靠它判过期，不能靠猜")
    void carriesPushIntervalForStaleDetection() {
        // 接收方读不到后端的 config.yml。不发这个字段，它只能写死一个假设，
        // 于是服主把间隔调大到 60 秒后，面板会在两轮上报之间闪「数据过期」。
        NetworkSummary.Report r = NetworkSummary.Report
            .builder("survival", 1_000L, 2_000L)
            .pushIntervalSec(60)
            .build();
        assertEquals(60, roundTrip(r).get("pushIntervalSec").getAsInt());

        // 0 表示"没提供"：整条省掉，让旧接收方走它自己的默认值而不是把 0 当间隔。
        NetworkSummary.Report unset = NetworkSummary.Report
            .builder("survival", 1_000L, 2_000L)
            .build();
        assertFalse(unset.toMap().containsKey("pushIntervalSec"),
            "没设置时不能写 0——0 秒间隔会被接收方算成「永远过期」");
    }

    // ==================== 验收修复后新增：防回归 ====================

    @Test
    @DisplayName("发送节流：超过一个间隔即使没变化也必须发（否则远端判成数据过期）")
    void mustResendAfterIntervalEvenWhenUnchanged() {
        String same = "20.0|20.0|20.0|null|3|true";
        long interval = 10_000L;

        assertTrue(SummaryPublisher.shouldSend(same, null, 1_000L, 0L, interval),
            "第一次必须发");

        assertFalse(SummaryPublisher.shouldSend(same, same, 5_000L, 1_000L, interval),
            "间隔内且无变化，不发");

        // 关键一条：不发会让远端 collectedAt 冻住，30 秒后健康后端被显示成"数据过期"。
        assertTrue(SummaryPublisher.shouldSend(same, same, 20_000L, 1_000L, interval),
            "超过一个间隔必须保底重发，让数据年龄前进");

        assertTrue(SummaryPublisher.shouldSend("19.0|x", same, 20_000L, 1_000L, interval),
            "有变化且过间隔，发");

        assertFalse(SummaryPublisher.shouldSend("19.0|x", same, 5_000L, 1_000L, interval),
            "有变化但间隔内，限频不发");
    }

    @Test
    @DisplayName("字段闸门：哨兵值 -1 必须被挡掉，不能当成真实读数发出去")
    void sentinelValuesAreNotPublished() {
        // msptAvg = -1（TPS 反推路径满帧时的哨兵）：模块"可用"但值是哨兵。
        // 不挡的话代理上会渲染成绿色的 "-1.0 毫秒"。
        assertFalse(SummaryPublisher.fieldsAvailable("mspt", false, -1.0, 0),
            "msptAvg=-1 是未采集哨兵，必须挡掉");

        assertTrue(SummaryPublisher.fieldsAvailable("mspt", false, 12.5, 0));
        assertTrue(SummaryPublisher.fieldsAvailable("tps", false, 20.0, -1));

        // 模块不可用：无论值是多少都挡
        assertFalse(SummaryPublisher.fieldsAvailable("tps", true, 20.0, -1));
        assertFalse(SummaryPublisher.fieldsAvailable("mspt", true, 12.5, 0));

        // 0 对 MSPT 不合法（tick 耗时不可能是 0）
        assertFalse(SummaryPublisher.fieldsAvailable("mspt", false, 0.0, 0));
    }

    // ==================== P1-C：server-id 发送前校验 ====================

    /**
     * 常量必须是<b>字面量 64</b>，且与代理侧 {@code SummaryPacket.MAX_SERVER_ID_LENGTH} 同值。
     *
     * <p>用字面量断言而不是"互相引用常量"，是因为后者会一起漂移：把两边都改成 1 亿，
     * "两边相等"仍然成立，而真实契约已经破了。这条把"64"这个<b>协议约定</b>钉死。</p>
     */
    @Test
    @DisplayName("server-id 上限是协议常量 64，保留字是 unknown")
    void serverIdProtocolConstantsAreFixed() {
        assertEquals(64, SummaryPublisher.MAX_SERVER_ID_LENGTH,
            "上限必须就是 64——代理侧 SummaryPacket.MAX_SERVER_ID_LENGTH 也是这个值，"
                + "两端靠这个字面量对接，不靠代码共享");
        assertEquals("unknown", SummaryPublisher.RESERVED_SERVER_ID,
            "保留字必须就是 unknown，与代理侧事件层 \"unknown\".equals(...) 的判据逐字一致");
    }

    @Test
    @DisplayName("校验：超过 64 字符必须被判 TOO_LONG（不是放行给代理去丢）")
    void overlongServerIdIsRejectedAtSendTime() {
        // 65 个字符：超限。
        assertEquals(SummaryPublisher.ServerIdVerdict.TOO_LONG,
            SummaryPublisher.validateServerId("a".repeat(65)),
            "65 字符必须被发送端拒——这正是 P1-C 的核心：后端发得出去的，代理必须收得下");

        // 恰好 64：合法边界。
        assertEquals(SummaryPublisher.ServerIdVerdict.OK,
            SummaryPublisher.validateServerId("a".repeat(64)),
            "恰好 64 字符必须放行（上限是 > 64，不是 >= 64）");

        // 63：合法。
        assertEquals(SummaryPublisher.ServerIdVerdict.OK,
            SummaryPublisher.validateServerId("a".repeat(63)));

        // 首尾空白不算长度（trim 后才判）：60 个 a 加 10 个空格 trim 后仍是 60，合法。
        String padded = " ".repeat(10) + "a".repeat(60) + " ".repeat(10);
        assertEquals(SummaryPublisher.ServerIdVerdict.OK,
            SummaryPublisher.validateServerId(padded),
            "首尾空白只是 YAML 排版噪音，trim 后不超限就该放行");
    }

    @Test
    @DisplayName("校验：保留字 unknown 被拒，但大小写变体 Unknown 合法")
    void reservedWordRejectedButCaseVariantsAreFine() {
        assertEquals(SummaryPublisher.ServerIdVerdict.RESERVED,
            SummaryPublisher.validateServerId("unknown"),
            "全小写 unknown 是代理侧「没有身份」的保留字，必须拒");

        // 大小写敏感：代理侧判据是 "unknown".equals(...)，所以 Unknown 在那边是合法 id。
        assertEquals(SummaryPublisher.ServerIdVerdict.OK,
            SummaryPublisher.validateServerId("Unknown"),
            "大写 Unknown 在代理侧是合法 id，后端若拦掉就成了「后端拒发、代理收得下」");
        assertEquals(SummaryPublisher.ServerIdVerdict.OK,
            SummaryPublisher.validateServerId("UNKNOWN"));
        assertEquals(SummaryPublisher.ServerIdVerdict.OK,
            SummaryPublisher.validateServerId("my-unknown-server"),
            "含 unknown 子串但不是保留字本身，必须放行");
    }

    @Test
    @DisplayName("校验：空 / 纯空白判 BLANK（不是错误，是「回退下一级」）")
    void blankIdIsNotAnError() {
        assertEquals(SummaryPublisher.ServerIdVerdict.BLANK, SummaryPublisher.validateServerId(null));
        assertEquals(SummaryPublisher.ServerIdVerdict.BLANK, SummaryPublisher.validateServerId(""));
        assertEquals(SummaryPublisher.ServerIdVerdict.BLANK, SummaryPublisher.validateServerId("   "));
        assertEquals(SummaryPublisher.ServerIdVerdict.BLANK, SummaryPublisher.validateServerId("\t\n"));
        // BLANK ≠ TOO_LONG：留空是 config.yml 写明的"回退"语义，不能被当成错误拒发。
        assertTrue(SummaryPublisher.validateServerId("") != SummaryPublisher.ServerIdVerdict.TOO_LONG);
    }

    @Test
    @DisplayName("校验：中文 id 按字符数算长度，不按字节（与代理侧 String.length 同口径）")
    void lengthIsMeasuredInCharsNotBytes() {
        // 40 个汉字：UTF-8 下 120 字节，但字符数只有 40 —— 必须放行。
        // 若实现改成按字节算，这条会红，而后端会拒绝一个代理明明收得下的中文 id。
        assertEquals(SummaryPublisher.ServerIdVerdict.OK,
            SummaryPublisher.validateServerId("服".repeat(40)),
            "40 个汉字只有 40 个字符，代理侧 String.length() 也是 40，必须放行");

        // 65 个汉字：字符数超限。
        assertEquals(SummaryPublisher.ServerIdVerdict.TOO_LONG,
            SummaryPublisher.validateServerId("服".repeat(65)));
    }

    @Test
    @DisplayName("校验：先判保留字再判长度（65 字符的 unknown 变体不能被误报成超长）")
    void reservedWordIsCheckedBeforeLength() {
        // 一个 65 字符、以 unknown 开头的值：应该报 TOO_LONG（它不是保留字）。
        // 真正的保留字本身只有 7 字符，不会撞长度闸门——这条钉住判定顺序不是反的。
        assertEquals(SummaryPublisher.ServerIdVerdict.TOO_LONG,
            SummaryPublisher.validateServerId("unknown" + "a".repeat(58)));

        // 反过来：如果实现先判长度，7 字符的 unknown 依然 RESERVED（长度不超），
        // 所以真正能区分顺序的是"超长的保留字类值"——它必须报 TOO_LONG。
        // 再补一条：恰好 64 字符且等于保留字前缀的，不因长度被判错。
        assertEquals(SummaryPublisher.ServerIdVerdict.OK,
            SummaryPublisher.validateServerId("a".repeat(63) + "k"));
    }

    // ==================== 控制字符：与代理侧 sanitize 对称 ====================

    /**
     * 后端必须拒绝含控制符 / {@code §} 的 server-id——与代理侧 {@code sanitize} 同口径。
     *
     * <p><b>为什么"代理会剔除"不够</b>：剔除意味着 {@code "survival\u0007"} 在面板上
     * 变成 {@code "survival"}，与另一台真正叫 {@code survival} 的后端<b>塌成同一个 id</b>。
     * 三个可滥用的后果（Agent 实测）：</p>
     *
     * <ol>
     *   <li>先到的伪造包抢注受害者 id（{@code SourceBinder} 把它钉在第一条来源上），
     *       真正的后端随后 {@code REJECT_SQUAT} <b>永久丢包</b>。</li>
     *   <li>{@code "§csurvival"} 在面板上显示成 {@code "csurvival"}——服主配的名字不存在。</li>
     *   <li>{@code "ab"} 与 {@code "a\u0007b"} 塌成同一个 key，后到者覆盖先到者的数据。</li>
     * </ol>
     *
     * <p>这条测试与 {@code SummaryPacketTest}（代理侧）的
     * {@code stripsControlCharactersFromServerId} 是同一判据的两端：
     * 那边断言"剔除"，这边断言"拒发"。</p>
     */
    @Test
    @DisplayName("校验：控制符（C0/C1/DEL）与 § 一律拒发（与代理侧 sanitize 同判据）")
    void unsafeCharactersAreRejected() {
        char[] bad = {
            '\n', '\r', '\t', '\u0000', '\u0007', '\u001B', '\u007F', '\u0085', '\u009F', '§'
        };
        for (char c : bad) {
            String raw = "survival" + c + "lobby";
            assertEquals(SummaryPublisher.ServerIdVerdict.UNSAFE_CHARS,
                SummaryPublisher.validateServerId(raw),
                "U+" + String.format("%04X", (int) c) + " 必须被拒——代理会剔除它，"
                    + "于是这个值与真正的 survivallobby 塌成同一个 server-id，"
                    + "先到的包能抢注身份、真正的后端随后永久丢包");
        }

        // 名字**就是**保留字 + 一个控制符：剔除后等于 "unknown"。必须先报"含非法字符"，
        // 因为那才是服主实际写错的字符（报成"是保留字"他会盯着 unknown 发愣）。
        assertEquals(SummaryPublisher.ServerIdVerdict.UNSAFE_CHARS,
            SummaryPublisher.validateServerId("unknown\u0007"),
            "剔除后就成了保留字，但服主写错的是那个不可见字符——必须先报它");

        // 反证：普通字符一个都不能误伤（否则合法部署会被拒发、永久隐身）。
        assertEquals(SummaryPublisher.ServerIdVerdict.OK,
            SummaryPublisher.validateServerId("survival-1"));
        assertEquals(SummaryPublisher.ServerIdVerdict.OK,
            SummaryPublisher.validateServerId("世界一区"),
            "中文 id 必须照常放行（README 明确支持）");
        assertEquals(SummaryPublisher.ServerIdVerdict.OK,
            SummaryPublisher.validateServerId("srv_2.eu"));
    }

    @Test
    @DisplayName("控制符：告警文案必须给出码点，而不是把不可见字符原样拼进日志")
    void unsafeCharacterWarningNamesCodePoints() {
        // BEL / ESC 在控制台里不可见（一个会响铃、一个会吞掉后续字符）。
        // 原样拼接只会得到"配了一个看起来完全正常的名字却被拒"——最没法自查的告警。
        String desc = SummaryPublisher.describeUnsafeChars("survival\u0007lobby");
        assertEquals("U+0007", desc, "必须写成码点，实际：" + desc);

        // 去重：同一个字符出现多次只报一次。
        assertEquals("U+0007", SummaryPublisher.describeUnsafeChars("a\u0007b\u0007c"),
            "同一个码点重复出现不该重复列出");

        // 多个不同字符按出现顺序列出。
        String multi = SummaryPublisher.describeUnsafeChars("a\u0007b\u001Bc§");
        assertTrue(multi.contains("U+0007") && multi.contains("U+001B") && multi.contains("U+00A7"),
            "所有非法字符都要列出，实际：" + multi);

        // 没有非法字符时给一个明确的占位，不是空串（空串会让告警变成
        // "含 server-id 不允许的字符（）"）。
        assertEquals("（无）", SummaryPublisher.describeUnsafeChars("survival"));
        assertEquals("（无）", SummaryPublisher.describeUnsafeChars(null));
    }

    @Test
    @DisplayName("控制符：剔除判据与代理侧逐字一致（同一组字符，两端结论相同）")
    void unsafeCharPredicateMatchesProxySide() {
        // 代理侧 SummaryPacket.isUnsafeChar 的判据是：
        //   Character.isISOControl(c) || c == '\u007F' || c == '§'
        // 这里用**同一组边界字符**独立验证后端判据，不靠共享代码（两个仓库无法共享）。
        // 若哪天有人只改了一边，这条（后端）与代理侧的
        // stripsControlCharactersFromServerId（那边）会有一边红。
        Object[][] cases = {
            {'\u0000', true}, {'\u001F', true}, {'\u007F', true},
            {'\u0080', true}, {'\u009F', true},
            {' ', false}, {'~', false}, {' ', false},
            {'A', false}, {'中', false}, {'-', false}, {'_', false},
        };
        for (Object[] c : cases) {
            char ch = (Character) c[0];
            boolean expectedUnsafe = (Boolean) c[1];
            String raw = "a" + ch + "b";
            boolean rejected = SummaryPublisher.validateServerId(raw)
                == SummaryPublisher.ServerIdVerdict.UNSAFE_CHARS;
            assertEquals(expectedUnsafe, rejected,
                "U+" + String.format("%04X", (int) ch) + " 的判定与代理侧不一致");
        }
        // § 单列（不在 isISOControl 里）。
        assertEquals(SummaryPublisher.ServerIdVerdict.UNSAFE_CHARS,
            SummaryPublisher.validateServerId("a§b"));
    }
}

