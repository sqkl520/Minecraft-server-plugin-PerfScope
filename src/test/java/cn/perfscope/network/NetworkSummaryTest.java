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

        // 快照年龄必须用 collectedAt 算。用 sentAt 算会让每一条消息的年龄恒为 0，
        // "数字冻住了"就永远检不出来。
        assertEquals(3_500L, r.ageMs(4_500L));
    }

    @Test
    @DisplayName("时钟不同步：后端比代理快时年龄按 0 处理，不返回负数")
    void negativeAgeClampsToZero() {
        NetworkSummary.Report r = NetworkSummary.Report
            .builder("survival", 5_000L, 5_000L)
            .build();
        // 后端的 collectedAt(5000) 比代理的 now(1000) 还晚 —— 跨机时钟没对上。
        assertEquals(0L, r.ageMs(1_000L),
            "负年龄会让上层算出诡异的百分比；夹到 0 是唯一安全的选择");
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
}
