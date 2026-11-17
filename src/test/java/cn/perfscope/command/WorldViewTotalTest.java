/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code /perfscope entities <world>} 与 {@code /perfscope chunks <world>} 的<b>总计行</b>
 * （{@link PerfScopeCommand#worldOrTotal}）。
 *
 * <h2>这条守的是什么</h2>
 *
 * <p>世界视图的标题早就切成了「实体统计 - 某世界」，紧接着的第一行却是
 * {@code snap.totalEntities}——<b>标题说一个世界、数字是全部世界之和</b>。同一屏再往下两行，
 * 逐世界行打的又是该世界自己的数字，于是用户在同一屏看到两个互相矛盾的「实体数」。</p>
 *
 * <p>这不是"没注意到"：{@code CHANGELOG} 里 1.3.0 那条明写
 * 「单世界视图改判<b>该世界自己的</b>实体数与覆盖阈值」，但当时只改到<b>告警段</b>，
 * 显示行漏了。文档说改完了，代码里还是全服值——<b>文档与代码分叉了两个大版本</b>。
 * 评审文档 {@code docs/01-reviews/critique-round2-arch-product.md} 给的判据是：
 * 「在只有 {@code world} 一个世界的服务器上，{@code /perfscope entities world} 与
 * {@code /perfscope entities} 输出的总实体数完全相同」——证明该字段不随 {@code world} 变化。</p>
 *
 * <h2>为什么断言是纯函数 + 一处"接线"检查</h2>
 *
 * <p>{@code showEntities} / {@code showChunks} 需要真 Bukkit（{@code World}、{@code CommandSender}、
 * {@code Component}），本仓库没有 Mockito，够不到。所以数值决策抽成
 * {@link PerfScopeCommand#worldOrTotal}——与 {@code computeTrendStats} / {@code thresholdOf}
 * 同一范式（前几轮已经这么抽过三个）。但纯函数测试<b>锁不住调用点</b>：
 * 把调用改回 {@code snap.totalEntities}，纯函数照样绿。于是另有一条"接线"检查，
 * 断言两处总计行的取值确实经过 {@code worldOrTotal}。</p>
 *
 * <h2>哪条用例是真正的靶点</h2>
 *
 * <p><b>「单世界时两值相同」不能当靶点</b>：修前修后它都成立（单世界时全服总数天然等于该世界数），
 * 绿得毫无信息量。真正的靶点是<b>多世界</b>：{@code world=100, nether=900, total=1000}
 * 时世界视图必须是 100 而不是 1000。把这条去掉，本次修复就等于没有测试。</p>
 */
class WorldViewTotalTest {

    /** 两个世界，且它们之和不等于任何一个单值——多世界用例的标准形状。 */
    private static Map<String, Integer> twoWorlds() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("world", 100);
        m.put("nether", 900);
        return m;
    }

    // ==================== 靶点：多世界时两值必须不同 ====================

    /**
     * <b>核心用例</b>：有 world 参数时取该世界的值，而不是全服总值。
     *
     * <p>修前这条是红的（世界视图返回 1000）。它也是评审文档那条可证伪点的直接反例：
     * 多世界服上 {@code entities world} 与 {@code entities} 必须给出不同的数字。</p>
     */
    @Test
    void worldViewTakesThatWorldsValueNotTheServerTotal() {
        Map<String, Integer> byWorld = twoWorlds();

        assertEquals(100, PerfScopeCommand.worldOrTotal("world", byWorld, 1000),
            "entities <world> must print that world's own entity count (100), "
                + "not the server-wide sum (1000)");
        assertEquals(900, PerfScopeCommand.worldOrTotal("nether", byWorld, 1000),
            "and it must track the argument, not a fixed world key");

        // 判据的二值形式：多世界时两者必须不同，否则「不随 world 变化」的 bug 仍然在
        assertNotEquals(PerfScopeCommand.worldOrTotal(null, byWorld, 1000),
            PerfScopeCommand.worldOrTotal("world", byWorld, 1000),
            "on a multi-world server the world view and the server view must NOT agree — "
                + "if they do, the total line ignores the world argument again");
    }

    /** 无 world 参数时保持原行为：全服总数（这是硬性要求，不允许被顺手改掉）。 */
    @Test
    void serverViewStillTakesTheServerTotal() {
        assertEquals(1000, PerfScopeCommand.worldOrTotal(null, twoWorlds(), 1000),
            "the no-argument view must keep printing the server-wide total (behaviour unchanged)");
        assertEquals(0, PerfScopeCommand.worldOrTotal(null, new LinkedHashMap<>(), 0));
    }

    // ==================== 缺失口径：与告警段一致 ====================

    /**
     * 世界不在 map 里（刚卸载、或快照早于世界创建）时返回 {@code null}，调用方按 0 渲染。
     *
     * <p>为什么是 null 而不是别的：{@code showEntities} 末尾的告警段读的是
     * {@code snap.entitiesByWorld.get(world.getName())}，为 null 即视为 0 实体、不报警。
     * 总计行跟它走，同一屏才不会出现两种"缺失"表示。</p>
     *
     * <p>这里尤其要断言<b>不是全服值</b>：返回 {@code serverTotal} 是这条最容易掉进的坑，
     * 也正是本次要修的 bug 本身。</p>
     */
    @Test
    void worldMissingFromSnapshotDoesNotFallBackToServerTotal() {
        Integer missing = PerfScopeCommand.worldOrTotal("the_end", twoWorlds(), 1000);

        assertNull(missing, "a world absent from the snapshot must stay absent (caller renders 0), "
            + "same rule as the alert section which reads get(...) and skips the alert on null");
        assertNotEquals(Integer.valueOf(1000), missing,
            "falling back to the server total is the exact bug this test exists to prevent");
    }

    /** 快照 map 本身为 null（采集未就绪）时不 NPE：世界视图仍为 null，全服视图仍为总数。 */
    @Test
    void nullMapIsTreatedAsNoDataForThatWorld() {
        assertNull(PerfScopeCommand.worldOrTotal("world", null, 1000));
        assertEquals(1000, PerfScopeCommand.worldOrTotal(null, null, 1000));
    }

    /**
     * 单世界服上两值相同——<b>但这次是因为逻辑对，不是因为巧合</b>。
     *
     * <p>这条用例修前修后都绿，所以它<b>不是</b>修复的靶点（多世界那条才是）。留在这里是为了
     * 把评审文档描述的那个场景钉成可执行的说明：单世界时「相同」有两种可能，
     * 一种是"两个分支碰巧算出同一个数"（修前），一种是"世界分支确实读了该世界"（修后）。
     * 断言只能看到数值，所以真正的区分交给上面那条多世界用例。</p>
     */
    @Test
    void singleWorldServerAgreesButOnlyBecauseTheBranchIsCorrect() {
        Map<String, Integer> onlyWorld = new LinkedHashMap<>();
        onlyWorld.put("world", 100);

        assertEquals(PerfScopeCommand.worldOrTotal(null, onlyWorld, 100),
            PerfScopeCommand.worldOrTotal("world", onlyWorld, 100),
            "on a single-world server the two views print the same number — that equality alone "
                + "proves nothing (the pre-fix code produced it too); multiWorldIsTheRealTarget covers it");
    }

    // ==================== 接线：两处总计行必须真的走 worldOrTotal ====================

    /**
     * {@code showEntities} / {@code showChunks} 的总计行确实把世界名传给了
     * {@link PerfScopeCommand#worldOrTotal}。
     *
     * <p><b>为什么要有这条</b>：纯函数写得再对，调用点改回 {@code snap.totalEntities}
     * 也是零成本的一行——那正是本次修掉的形状，而它在全量测试下曾经完全绿。没有这条，
     * 下一次"顺手简化"就能把它带回来。</p>
     *
     * <p>断言用<b>空白归一化后</b>的子串匹配，所以换行、缩进改动不会误报；只有语义改变
     * （不再把 {@code world.getName()} 与逐世界 map 传进去）才会红。</p>
     */
    @Test
    void totalLinesAreWiredToTheWorldAwareHelper() throws IOException {
        String src = normalize(readCommandSource());

        assertContains(src, "Integer entityTotal = worldOrTotal(world != null ? world.getName() : null, "
            + "snap.entitiesByWorld, snap.totalEntities);",
            "showEntities' total line must derive its value from worldOrTotal(world, entitiesByWorld, "
                + "totalEntities) — printing snap.totalEntities here is the bug fixed in this change");

        assertContains(src, "Integer chunkTotal = worldOrTotal(world != null ? world.getName() : null, "
            + "snap.chunksByWorld, snap.totalLoadedChunks);",
            "showChunks' loaded-chunk total must be world-aware for the same reason");

        assertContains(src, "Integer forceTotal = worldOrTotal(world != null ? world.getName() : null, "
            + "snap.forceLoadedChunksByWorld, snap.totalForceLoadedChunks);",
            "the force-loaded total sits right below it and had the same defect — both must be world-aware");
    }

    // ==================== helpers ====================

    private static void assertContains(String haystack, String needle, String message) {
        assertTrue(haystack.contains(needle), message + "\n  expected to find: " + needle);
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ");
    }

    /**
     * 读 {@code PerfScopeCommand} 的源码。
     *
     * <p>Surefire 的 cwd 是项目根，但为免 IDE / 聚合构建下 cwd 不同，这里逐级向上找
     * {@code src/main/java/...}。找不到直接失败而不是跳过——"跳过"会让这条接线检查
     * 在某台机器上悄悄不存在，而它正是拦住回退的那一条。</p>
     */
    private static String readCommandSource() throws IOException {
        Path relative = Path.of("src", "main", "java", "cn", "perfscope", "command", "PerfScopeCommand.java");
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        fail("cannot locate PerfScopeCommand.java from " + Path.of("").toAbsolutePath()
            + "; the wiring assertions below need the source tree (run tests from the project root)");
        return null; // unreachable
    }
}
