/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.network;

import com.google.gson.Gson;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨仓库 golden fixture —— <b>发送端</b>（Paper 后端）。
 *
 * <h2>这组测试要解决的错误</h2>
 *
 * <p>两个仓库的测试此前<b>从来没有共享过同一个字节串</b>：本端 {@code NetworkSummaryTest}
 * 断言的是"我的 {@code toMap()} 里有这些 key"，而代理端的 {@code SummaryTest} 喂的是
 * <b>手写 JSON 字面量</b>。于是两侧的测试各测各的，谁也没测那份真正被发出去的字节。</p>
 *
 * <p>后果是不对称的：后端<b>改名</b>会让代理解析失败并记日志（响）；但同一字段
 * <b>换单位</b>（{@code tps1m} 从 0–20 TPS 改成分数占比 0–1）或<b>换语义</b>
 * （{@code players} 从"在线人数"改成"在线人数+1"）在两侧<b>完全沉默</b>——
 * 数值都还在，范围也都合法，面板照常渲染，只是数字全错。</p>
 *
 * <p>所以这里把"线上字节"本身提升为被版本控制共享的产物：{@code src/test/resources/protocol/}
 * 下的每个 {@code .json} 都是从 {@link NetworkSummary.Report#toMap()} 真实生成的字节，
 * 本类断言 {@code toMap()} 的输出与它们<b>逐字节相等</b>；代理仓库持有同一份文件的<b>副本</b>，
 * 那边断言 {@code parse()} 读出来的每个字段值正确。任一侧单方面改动契约，它在那一侧就红；
 * 两侧都改而内容不一致，由 {@code tools/check-protocol-fixtures-sync.mjs} 红。</p>
 *
 * <h2>为什么不写成"断言完整字段集"</h2>
 *
 * <p>协议允许<b>新增可选字段而不升 PROTOCOL_VERSION</b>（两端本来就要容忍缺字段）。若这里
 * 写死"必须恰好有这 11 个 key"，以后加一个可选字段就会红，而那是<b>合法变更</b>——
 * 测试会开始阻碍正确的事。所以：</p>
 *
 * <ul>
 *   <li>"逐字节相等"负责锁住<b>已经存在</b>的字节（改一个字符就红）；</li>
 *   <li>"每个 fixture 缺哪个可选字段"用<b>语义断言</b>（{@code containsKey}）锁住，而不是数量断言；</li>
 *   <li>新增可选字段时，重生成 fixture 即可——{@link #regenerateWhenAsked()} 一条命令做完，
 *       并顺带把副本写到代理仓库。</li>
 * </ul>
 *
 * <h2>重生成</h2>
 *
 * <pre>{@code
 * mvn -B -ntp test -Dtest=NetworkProtocolFixtureTest -Dperfscope.protocol.regenerate=true
 * }</pre>
 *
 * <p>代理仓库的位置默认按"同级目录 {@code ../PerfScope-Velocity}"推断；换机器或换布局时用
 * {@code -Dperfscope.protocol.mirror=<path>} 显式指定。重生成之后必须<b>两个仓库一起提交</b>，
 * 否则 {@code check-protocol-fixtures-sync.mjs} 会红。</p>
 */
class NetworkProtocolFixtureTest {

    private static final Gson GSON = new Gson();

    /** fixture 目录：测试期从 classpath 读（{@code target/test-classes/protocol}），重生成时写源码目录。 */
    private static final String CLASSPATH_DIR = "/protocol/";
    private static final Path SOURCE_DIR = Paths.get("src/test/resources/protocol");
    private static final String INDEX_FILE = "FIXTURES.sha256";

    /** 重生成开关；<b>默认关闭</b>——否则每次 {@code mvn test} 都会把"契约漂移"擦掉。 */
    private static final String REGENERATE_PROP = "perfscope.protocol.regenerate";
    private static final String MIRROR_PROP = "perfscope.protocol.mirror";

    // ==================== fixture 定义（本文件是它唯一的出处） ====================

    /**
     * 七个用例的<b>构造方式</b>（不是期望值——期望值就是它们的 {@code toMap()} 字节）。
     *
     * <p>时间戳取 2023-11-15 的固定值，且 {@code sentAt = collectedAt + 500ms}：这满足协议
     * 的不变式"采集不晚于发送"，代理侧新加的 {@code collectedAt} 合理性校验（未来时间戳 /
     * 时钟偏移）在正常路径下不会把 fixture 当成坏包丢掉。</p>
     */
    private static Map<String, NetworkSummary.Report> cases() {
        final long collectedAt = 1_700_000_000_000L;
        final long sentAt = collectedAt + 500L;

        Map<String, NetworkSummary.Report> m = new LinkedHashMap<>();

        // 1. 正常包：全部字段齐备，带上报间隔。
        m.put("normal", NetworkSummary.Report.builder("survival", collectedAt, sentAt)
            .collectorAlive(true)
            .tps(19.95, 19.90, 19.85)
            .msptAvg(12.5)
            .playerCount(42)
            .pushIntervalSec(10)
            .build());

        // 2. 缺 TPS：performance 模块被关掉 / 平台不支持。只剩 MSPT 与玩家数。
        //    ⚠️ 刻意<b>不</b>调 .tps(...)：builder 一旦被赋值就会写出 key（0.0 也是合法读数），
        //    "缺失"只有"根本不调"这一种表达方式，与 buildReport 里那道字段闸门同构。
        m.put("no-tps", NetworkSummary.Report.builder("skyblock", collectedAt, sentAt)
            .msptAvg(8.0)
            .playerCount(7)
            .pushIntervalSec(10)
            .build());

        // 3. 缺 MSPT：这就是 buildReport 里"三道闸门"挡掉的那条路径（msptSource=UNKNOWN）。
        //    玩家数为 0 —— 空服是合法读数，与下面的 unavailable-all 形成对照。
        m.put("no-mspt", NetworkSummary.Report.builder("minigames", collectedAt, sentAt)
            .tps(20.0, 20.0, 20.0)
            .playerCount(0)
            .pushIntervalSec(10)
            .build());

        // 4. 旧版后端形状：没有 pushIntervalSec（字段整条缺席，不是写 0）。
        //    这条钉住"新增可选字段不升协议版本"这个决定 —— 旧包必须能被新代理照常读懂。
        m.put("legacy-no-interval", NetworkSummary.Report.builder("lobby", collectedAt, sentAt)
            .tps(19.5, 19.6, 19.7)
            .msptAvg(14.0)
            .playerCount(3)
            .build());

        // 5. 边界值：恰好落在两个阈值的分界点上，且都是"合法读数"而不是缺值。
        //    TPS 15.0 / 18.0 是后端严格比较的档位分界；MSPT 50.0 是 critical 线；
        //    0.0 TPS 是真实的"冻结"；pushIntervalSec 300 是配置校验的上界。
        m.put("boundary", NetworkSummary.Report.builder("test-boundary", collectedAt, sentAt)
            .tps(15.0, 18.0, 0.0)
            .msptAvg(50.0)
            .playerCount(0)
            .pushIntervalSec(300)
            .build());

        // 6. 全不可用：数值字段全缺，玩家数停在哨兵 -1（0 是合法值，所以只能用 -1）。
        m.put("unavailable-all", NetworkSummary.Report.builder("creative", collectedAt, sentAt)
            .build());

        // 7. 采集已死：包在到、数字还在，但 alive=false —— 接收方必须整行都不信。
        m.put("collector-dead", NetworkSummary.Report.builder("survival", collectedAt, sentAt)
            .collectorAlive(false)
            .tps(20.0, 20.0, 20.0)
            .msptAvg(5.0)
            .playerCount(12)
            .pushIntervalSec(10)
            .build());

        return m;
    }

    /** fixture 文件名（{@code summary-<case>.json}）。 */
    private static String fileName(String caseName) {
        return "summary-" + caseName + ".json";
    }

    // ==================== 断言 1：字节就是 toMap() 的输出 ====================

    @Test
    @DisplayName("每个 fixture 与 toMap() 的真实字节逐字节相等（fixture 不是手写的）")
    void fixturesAreExactlyWhatToMapProduces() throws IOException {
        for (Map.Entry<String, NetworkSummary.Report> e : cases().entrySet()) {
            String expected = utf8(readFixture(e.getKey()));
            String actual = GSON.toJson(e.getValue().toMap());
            assertEquals(expected, actual,
                "fixture " + fileName(e.getKey()) + " 与当前 toMap() 的输出不一致。\n"
                    + "如果这次改动是<b>有意</b>的协议变更：\n"
                    + "  1) mvn -B -ntp test -Dtest=" + getClass().getSimpleName()
                    + " -Dperfscope.protocol.regenerate=true\n"
                    + "  2) 把代理仓库的 protocol/ 目录副本一起更新（两边必须同字节）\n"
                    + "  3) 改字段名/语义/单位时还要递增 NetworkSummary.PROTOCOL_VERSION\n"
                    + "否则就是把契约改了而没通知接收端。\n"
                    + "期望(fixture): " + expected + "\n实际(toMap)  : " + actual);
        }
    }

    // ==================== 断言 2：索引哈希（挡住半份副本 / 编辑器改写） ====================

    @Test
    @DisplayName("FIXTURES.sha256 与 fixture 字节一一对应（两份副本靠它校验一致性）")
    void indexHashesMatchFixtureBytes() throws IOException {
        Map<String, String> index = readIndex();
        assertEquals(cases().size(), index.size(),
            "索引里登记的用例数与代码里的用例数不一致：" + index.keySet());
        for (String caseName : cases().keySet()) {
            assertTrue(index.containsKey(fileName(caseName)),
                "索引缺少 " + fileName(caseName) + " —— 索引与 fixture 不同步");
            assertEquals(sha256(normalize(readFixture(caseName))), index.get(fileName(caseName)),
                fileName(caseName) + " 的字节与其登记的 sha256 不一致（文件被改过）");
        }
    }

    // ==================== 断言 3：缺哪个可选字段是语义，不是数量 ====================

    /**
     * 每个 fixture 的"可选字段在场/缺席"必须与它想表达的场景一致。
     *
     * <p>这一组是<b>变异杀手</b>：把 {@code buildReport} 的某个闸门删掉之后，{@code toMap()}
     * 就会多写一个键，于是第 1 条（逐字节相等）也会红——但那条的失败信息说的是"不一致"，
     * 而这里说的是"这个场景不该有这个字段"，更直接。两条一起才既锁字节又锁语义。</p>
     */
    @Test
    @DisplayName("可选字段的在场/缺席与场景语义一致（缺值≠0）")
    void optionalFieldPresenceMatchesScenario() {
        // 缺 TPS：三个 TPS 键一个都不能有 —— 写 0 会被代理读成"服务器冻结"。
        NetworkSummary.Report noTps = cases().get("no-tps");
        Set<String> noTpsKeys = noTps.toMap().keySet();
        assertFalse(noTpsKeys.contains("tps1m"), "缺 TPS 的场景不能带 tps1m");
        assertFalse(noTpsKeys.contains("tps5m"));
        assertFalse(noTpsKeys.contains("tps15m"));
        assertTrue(noTpsKeys.contains("msptAvg"), "但 MSPT 照常要有");

        // 缺 MSPT：tps 有、mspt 无 —— 这条正是 buildReport 第三道闸门（msptSource=UNKNOWN）
        // 挡掉的形状。删掉闸门后 msptAvg 会出现在字节里，本断言变红。
        NetworkSummary.Report noMspt = cases().get("no-mspt");
        assertTrue(noMspt.toMap().containsKey("tps1m"));
        assertFalse(noMspt.toMap().containsKey("msptAvg"), "缺 MSPT 的场景不能带 msptAvg");
        assertEquals(0, noMspt.playerCount(), "空服 0 人是合法读数，必须如实保留");
        assertEquals(0, ((Number) noMspt.toMap().get("players")).intValue());

        // 旧版形状：pushIntervalSec 整条缺席（不是写 0）。写 0 会被接收方算成"永远过期"。
        assertFalse(cases().get("legacy-no-interval").toMap().containsKey("pushIntervalSec"));

        // 全不可用：数值字段全缺，玩家数是 -1 哨兵（0 是合法值，所以只能用负数）。
        Set<String> allKeys = cases().get("unavailable-all").toMap().keySet();
        for (String k : new String[]{"tps1m", "tps5m", "tps15m", "msptAvg", "pushIntervalSec"}) {
            assertFalse(allKeys.contains(k), "全不可用时不该出现 " + k);
        }
        assertEquals(-1, ((Number) cases().get("unavailable-all").toMap().get("players")).intValue(),
            "没采到的计数必须是 -1 字面值");

        // 边界：0.0 TPS 是合法读数，必须真的出现在字节里（不能因为"看起来像哨兵"而省略）。
        assertEquals(0.0, cases().get("boundary").tps15m(), 0.0);
        assertTrue(cases().get("boundary").toMap().containsKey("tps15m"),
            "0.0 是合法 TPS 读数（服务器冻结），省掉它等于把'冻结'说成'没数据'");

        // 采集已死：数字照常在场，但 alive 必须是 false —— 接收方靠这个标志识破陈数据。
        assertEquals(false, cases().get("collector-dead").toMap().get("alive"));
        assertTrue(cases().get("collector-dead").toMap().containsKey("tps1m"));
    }

    /**
     * 每个 fixture 都必须带上"无条件字段"，且不含任何未知键。
     *
     * <p>未知键会红：协议是"按 key 取值"，多一个键本身无害，但<b>本端多写的键接收端不认识</b>
     * 说明两侧字段集已经分叉，而分叉是"下一轮有人照着旧名字改"的温床。强制走重生成流程，
     * 让每一次新增字段都经过一次"两侧同时看见"的动作。</p>
     */
    @Test
    @DisplayName("每个 fixture 都带齐无条件字段，且不含协议外的键")
    void everyFixtureCarriesTheUnconditionalFields() {
        // 无条件字段：不论场景如何，toMap() 都会写。可选字段（tps*/msptAvg/pushIntervalSec）
        // 刻意<b>不</b>列在这里 —— 见类注释"为什么不写成断言完整字段集"。
        Set<String> unconditional = new LinkedHashSet<>(List.of(
            "v", "kind", "server", "sentAt", "collectedAt", "alive", "players"));
        Set<String> optional = new LinkedHashSet<>(List.of(
            "tps1m", "tps5m", "tps15m", "msptAvg", "pushIntervalSec"));

        for (Map.Entry<String, NetworkSummary.Report> e : cases().entrySet()) {
            Set<String> keys = e.getValue().toMap().keySet();
            for (String k : unconditional) {
                assertTrue(keys.contains(k), fileName(e.getKey()) + " 缺少无条件字段 " + k);
            }
            for (String k : keys) {
                assertTrue(unconditional.contains(k) || optional.contains(k),
                    fileName(e.getKey()) + " 出现了协议外的键 " + k
                        + "：新增的可选字段请补进本方法的 optional 列表并重生成两侧 fixture");
            }
            assertEquals("summary", e.getValue().toMap().get("kind"));
            assertEquals(NetworkSummary.PROTOCOL_VERSION, e.getValue().toMap().get("v"));
        }
    }

    // ==================== 重生成（默认不跑） ====================

    /**
     * 重生成 fixture 与索引，并把副本写到代理仓库。
     *
     * <p>被 {@code assumeTrue} 挡住，正常跑测试时显示为 skipped —— 这是有意的：
     * 若它在正常构建里也执行，那么"改了 toMap() 忘了改契约"就永远不会红，
     * 这组测试也就失去全部意义。</p>
     */
    @Test
    @DisplayName("（按需）重生成 fixture：-D" + REGENERATE_PROP + "=true")
    void regenerateWhenAsked() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(REGENERATE_PROP),
            "未开启重生成（正常跑测试时应当跳过）");
        Assumptions.assumeTrue(Files.isDirectory(SOURCE_DIR),
            "找不到源码目录 " + SOURCE_DIR.toAbsolutePath() + "（从模块根目录运行 Maven）");

        StringBuilder index = new StringBuilder();
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, NetworkSummary.Report> e : cases().entrySet()) {
            // 末尾留一个换行：让 diff 工具不抱怨 "No newline at end of file"。
            // 两侧哈希与比较都在 normalize() 里统一去掉它，因此换行有无不影响契约。
            byte[] bytes = (GSON.toJson(e.getValue().toMap()) + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(SOURCE_DIR.resolve(fileName(e.getKey())), bytes);
            index.append(fileName(e.getKey())).append("  ")
                .append(sha256(normalize(bytes))).append('\n');
            written.add(fileName(e.getKey()));
        }
        Files.write(SOURCE_DIR.resolve(INDEX_FILE), index.toString().getBytes(StandardCharsets.UTF_8));

        Path mirror = resolveMirrorDir();
        if (mirror != null) {
            for (String name : written) {
                Files.write(mirror.resolve(name),
                    Files.readAllBytes(SOURCE_DIR.resolve(name)));
            }
            Files.write(mirror.resolve(INDEX_FILE),
                Files.readAllBytes(SOURCE_DIR.resolve(INDEX_FILE)));
            System.out.println("[protocol] 已同步副本到 " + mirror.toAbsolutePath());
        } else {
            System.out.println("[protocol] 未找到代理仓库副本目录（用 -D" + MIRROR_PROP
                + "=<path> 指定），请手动复制 " + SOURCE_DIR.toAbsolutePath() + " 下的全部文件");
        }
        System.out.println("[protocol] 已重生成 " + written.size() + " 个 fixture：" + written);
    }

    /** 代理仓库的 fixture 目录：优先系统属性，其次同级目录推断；都没有返回 {@code null}。 */
    private static Path resolveMirrorDir() {
        String explicit = System.getProperty(MIRROR_PROP);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit);
        }
        Path sibling = Paths.get("..", "PerfScope-Velocity", "src", "test", "resources", "protocol");
        return Files.isDirectory(sibling) ? sibling : null;
    }

    // ==================== 读取/哈希 ====================

    /** 读 fixture：优先 classpath（保证测的是被打进测试 classpath 的那份）。 */
    private static byte[] readFixture(String caseName) throws IOException {
        try (InputStream in = NetworkProtocolFixtureTest.class
            .getResourceAsStream(CLASSPATH_DIR + fileName(caseName))) {
            assertNotNull(in, fileName(caseName) + " 不在测试 classpath 的 " + CLASSPATH_DIR
                + " 下 —— fixture 没被打包进 src/test/resources，或文件名写错");
            return in.readAllBytes();
        }
    }

    /** 读索引：{@code <文件名>  <sha256>} 每行一条，{@code #} 开头为注释。 */
    private static Map<String, String> readIndex() throws IOException {
        try (InputStream in = NetworkProtocolFixtureTest.class
            .getResourceAsStream(CLASSPATH_DIR + INDEX_FILE)) {
            assertNotNull(in, INDEX_FILE + " 不在测试 classpath 下");
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\\s+");
                assertEquals(2, parts.length, "索引行格式应为「文件名  sha256」：" + line);
                m.put(parts[0], parts[1]);
            }
            return m;
        }
    }

    private static String utf8(byte[] b) {
        return new String(normalize(b), StandardCharsets.UTF_8);
    }

    /** 去掉末尾的 CR/LF：编辑器常自动补一个换行，那不是协议内容。 */
    private static byte[] normalize(byte[] raw) {
        int end = raw.length;
        while (end > 0 && (raw[end - 1] == '\n' || raw[end - 1] == '\r')) {
            end--;
        }
        byte[] out = new byte[end];
        System.arraycopy(raw, 0, out, 0, end);
        return out;
    }

    private static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                .append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
