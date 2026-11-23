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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P0-F：{@code /perfscope status} 里的「可选出口」段。
 *
 * <h2>这条守的是什么</h2>
 *
 * <p>修之前，{@code PrometheusEndpoint.failureReason()} 与 {@code SummaryPublisher.channelRegistered()}
 * 的 javadoc 都写着"给 /perfscope status 用"，而全仓<b>零调用点</b>——
 * {@code PerfScopeCommand} 里 grep {@code prometheus|network} 命中 0 次。
 * 于是三个可选出口失败后只留一条启动 warning，翻出控制台就再也查不到。
 * 连 {@code PrometheusEndpointHttpTest} 里都有一条断言注释写着
 * "failureReason() is what /status shows"——测试在为一个不存在的接线背书。</p>
 *
 * <h2>为什么断言分成三层</h2>
 *
 * <ul>
 *   <li><b>判定逻辑</b>（{@link PerfScopeCommand#exportHealth}）：纯函数，穷举三态。
 *       最容易写错的是"配置关着 + 残留上一次失败原因"这一格——先看原因会把它显示成
 *       "启动失败"，把"我不想要它"说成"它坏了"。</li>
 *   <li><b>bStats 的 reload 决策</b>（{@link PerfScope#bstatsReloadAction}）：隐私开关失效那一格
 *       （{@code wanted=false, running=true}）必须返回 {@code STOP}。</li>
 *   <li><b>接线</b>：上面两个纯函数写得再对，{@code showStatus} 不调它们、
 *       {@code reload()} 不调 {@code reloadBstats()}，功能就等于没做。源码级断言，
 *       与 {@code WorldViewTotalTest} 同一范式。</li>
 * </ul>
 */
class StatusExportsTest {

    // ==================== 判定逻辑：exportHealth ====================

    /**
     * <b>核心用例</b>：配置说启用、实际没在工作 = FAILED。
     *
     * <p>这就是 P0-E 的形状——{@code network.enabled: true} 但通道没登记上，
     * 一条数据都发不出去。修前它连显示都不显示，修后必须显示成失败而不是"未启用"。</p>
     */
    @Test
    void configuredButNotWorkingIsAFailureNotADisabledState() {
        assertEquals(PerfScopeCommand.ExportHealth.FAILED,
            PerfScopeCommand.exportHealth(true, false),
            "配置启用却不在工作，必须报失败——报成'未启用'会让服主以为是自己没开");
    }

    @Test
    void runningBeatsEverything() {
        assertEquals(PerfScopeCommand.ExportHealth.OK,
            PerfScopeCommand.exportHealth(true, true));
        assertEquals(PerfScopeCommand.ExportHealth.OK,
            PerfScopeCommand.exportHealth(false, true),
            "正在服务就是正在服务：改配置文件不等于已 reload，此刻端口还开着、通道还登记着——"
                + "把它显示成'未启用'是在危险的方向上说谎");
    }

    /**
     * 配置关着 + 上一轮 reload 留下的失败原因 —— 必须是 OFF。
     *
     * <p>{@code PrometheusEndpoint#start()} 在未启用分支直接 return，<b>不会</b>清
     * {@code failureReason}。若判定先看原因，服主主动关掉端点后 /status 会一直显示
     * "启动失败"，而他改的正是"关掉它"。</p>
     */
    @Test
    void disabledStaysDisabledEvenWithAStaleFailureReason() {
        assertEquals(PerfScopeCommand.ExportHealth.OFF,
            PerfScopeCommand.exportHealth(false, false),
            "主动关闭的出口不能因为对象里残留着上次的失败原因就显示成失败");
    }

    // ==================== bStats 的 reload 决策 ====================
    // 决策函数在 cn.perfscope 包里（包级可见），穷举四象限的用例放在
    // PerfScopeBstatsReloadTest —— 这里只留"reload 真的调了它"的接线断言。

    // ==================== 接线：/status 真的接上了这三个出口 ====================

    /**
     * {@code showStatus} 末尾调用了 {@code showExports}，且 {@code showExports} 里三个出口
     * 都读了各自的访问器。
     *
     * <p>{@code grep} 口径刻意用"调用表达式的片段"而不是整行：换行与缩进改动不该误报，
     * 只有语义改变（不再调、或改读别的字段）才会红。</p>
     */
    @Test
    void statusActuallyReadsTheThreeExportAccessors() throws IOException {
        String src = normalize(readSource("src/main/java/cn/perfscope/command/PerfScopeCommand.java"));

        assertContains(src, "showExports(sender, cfg);",
            "showStatus must call showExports — otherwise the section exists in source only");

        assertContains(src, "plugin.getPrometheusEndpoint()",
            "Prometheus state must come from the endpoint accessor");
        assertContains(src, "endpoint.failureReason()",
            "the failure reason must be surfaced: it is the only place the cause is visible after "
                + "the startup warning scrolls away");

        assertContains(src, "plugin.getSummaryPublisher()",
            "network state must come from the publisher accessor");
        assertContains(src, "publisher.channelRegistered()",
            "channelRegistered() had zero call sites before this change; it must be wired now");
        assertContains(src, "publisher.registrationFailureReason()",
            "a failed registration must say why, not just that it failed");

        assertContains(src, "plugin.getBstatsState()",
            "bStats state must come from its accessor (bStats previously had no state at all)");
    }

    /**
     * reload 真的调用了 bStats 的启停，且这一调用不受 {@code loadFailed} 影响。
     *
     * <p>{@code tools/check-reload-rebuild.mjs} 守的是"配置加载失败时也要重建任务"；
     * 这里守的是另一半——bStats 必须<b>在 reload 里出现</b>。两者互补：
     * 修前 reload 里根本没有 bStats 这一行，所以那个门禁也不会报。</p>
     */
    @Test
    void reloadRebuildsBstatsAsWell() throws IOException {
        String src = normalize(readSource("src/main/java/cn/perfscope/PerfScope.java"));

        assertContains(src, "reloadBstats();",
            "reload() must call reloadBstats() — general.bstats-enabled is a privacy switch and "
                + "must take effect on /perfscope reload, not only on restart");
    }

    /**
     * {@code SummaryPublisher.register()} 返回成败，调用方据此选日志。
     *
     * <p>P0-E 的原文形状是 "register() 内部 catch 吞掉异常后正常返回（void），
     * 调用方紧接着无条件打「已启用」"。因此这条断言直接锁住那两件事：
     * 签名是 boolean，且调用点把它放进条件里。</p>
     */
    @Test
    void networkRegistrationResultDrivesTheLogLine() throws IOException {
        String publisherSrc = normalize(
            readSource("src/main/java/cn/perfscope/network/SummaryPublisher.java"));
        String pluginSrc = normalize(readSource("src/main/java/cn/perfscope/PerfScope.java"));

        assertContains(publisherSrc, "public boolean register()",
            "register() must report success; a void method forces every caller to guess");

        // 旧断言是 assertContains(publisherSrc, "return false;") —— 恒真。
        // 这个文件里有 4 处 return false;（publish / resolveServerId / channelRegistered /
        // register 的 catch），所以它在本文件任何一种实现状态下都成立：把 register() 的 catch
        // 改成 return true，它照样绿。也就是说它**声称**守的 P0-E（catch 吞掉异常后"正常返回"
        // 被调用方读成成功）它一个字都没守住。实测：改成 return true 后 925 例全绿。
        //
        // 改成截取 register() 方法体、再截取它内部的 catch 块，只对这个块下断言：
        // 判定范围从"整个 795 行的文件"缩到"P0-E 真正发生的那 8 行"。
        // catch 块里只有一处 return，因此"必须 return false"在这里是精确判定而非恒真。
        String registerBody = methodSource(publisherSrc, "public boolean register()");
        String catchBlock = blockAfter(registerBody, "catch (Exception | LinkageError e)");

        assertContains(catchBlock, "return false;",
            "the catch branch must return false — falling through to success is exactly the P0-E bug");
        assertFalseContains(catchBlock, "return true;",
            "the catch branch must not report success: register() failing while claiming success "
                + "is the original P0-E shape (caller then prints 「已启用」 unconditionally)");
        assertFalseContains(catchBlock, "return;",
            "catch must return false explicitly, not fall out of the method with a bare return");

        assertContains(pluginSrc, "if (summaryPublisher.register())",
            "the caller must branch on the result before printing 「已启用」");
        assertFalseContains(pluginSrc, "summaryPublisher.register();",
            "a bare register() call means the result is ignored again — that is the original bug");
    }

    // ==================== helpers ====================

    private static void assertContains(String haystack, String needle, String message) {
        assertTrue(haystack.contains(needle), message + "\n  expected to find: " + needle);
    }

    private static void assertFalseContains(String haystack, String needle, String message) {
        assertTrue(!haystack.contains(needle), message + "\n  must NOT contain: " + needle);
    }

    /**
     * 从（已归一化成单行的）源码里截出某个方法体的花括号内容。
     *
     * <p><b>为什么需要它而不是直接对全文 contains</b>：全文 contains 的判定范围是整个文件，
     * 于是"某处存在 X"会被文件里的<b>其它</b>方法满足。{@code SummaryPublisher} 有 4 处
     * {@code return false;}，旧断言就是被其中三处与 {@code register()} 无关的代码养活成恒真的。
     * 把范围缩到目标方法体，断言才真的在说"这个方法里必须这样写"。</p>
     *
     * <p>实现是花括号配对（不是正则）：归一化把换行压成了空格，但括号结构原样保留，
     * 配对因此可靠。找不到签名或括号不配对时直接返回余下全文——让断言在<b>它自己</b>
     * 那一步失败，而不是在这里抛一个看不出意图的 {@code StringIndexOutOfBoundsException}。</p>
     */
    private static String methodSource(String src, String signature) {
        int at = src.indexOf(signature);
        if (at < 0) {
            fail("cannot find method signature in source: " + signature);
        }
        return blockAfter(src.substring(at), "{");
    }

    /**
     * 截出 {@code from} 之后第一个出现的 {@code opener}（如 {@code "{"} 或
     * {@code "catch (Exception | LinkageError e)"}）所开启的那个块，含块本身。
     *
     * <p>同时支持两种入口：给 {@code "{"} 时返回"从该 '{' 起配对出的整段"；
     * 给一段代码前缀时返回"那一段 + 它后面的块"。后者让调用处能写成
     * {@code blockAfter(body, "catch (Exception | LinkageError e)")}，语义一眼可见。</p>
     */
    private static String blockAfter(String src, String opener) {
        int at = src.indexOf(opener);
        if (at < 0) {
            return "";
        }
        int open = src.indexOf('{', at);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(at, i + 1);
                }
            }
        }
        return src.substring(at);
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ");
    }

    /**
     * 从项目根读源码。
     *
     * <p>Surefire 的 cwd 是项目根，但聚合构建 / IDE 下可能不同，所以逐级向上找。
     * 找不到直接失败而不是跳过——"跳过"会让这条接线检查在某台机器上悄悄不存在，
     * 而它正是拦住回退的那一条。</p>
     */
    private static String readSource(String relative) throws IOException {
        Path rel = Path.of(relative.replace('/', java.io.File.separatorChar));
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve(rel);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        fail("cannot locate " + relative + " from " + Path.of("").toAbsolutePath()
            + "; these wiring assertions need the source tree (run tests from the project root)");
        return null; // unreachable
    }
}
