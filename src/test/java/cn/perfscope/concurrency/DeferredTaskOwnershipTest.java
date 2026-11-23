/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.concurrency;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P0-1 的<b>接线</b>断言：四个服务的任务句柄确实都换成了 {@link cn.perfscope.compat.TaskHandleSlot}。
 *
 * <h2>为什么需要源码级断言（而不是只测槽位本身）</h2>
 *
 * <p>{@link cn.perfscope.compat.TaskHandleSlot} 自己的并发语义由 {@code TaskHandleSlotTest} 覆盖，
 * 而那条测试对"四个服务是否真的改用了它"<b>一无所知</b>。真实的失败模式正是"槽位写得很好，
 * 但某个服务漏改"——那处仍然是「非 volatile 普通字段 + 无锁 start/stop」，P0 竞态原样存在，
 * 而所有测试全绿。这与 {@code check-reload-rebuild.mjs} 同一范式：
 * <b>当行为只能在特定运行环境（Folia 多 region 线程并发 reload）里显现时，把约束上升到源码层。</b></p>
 *
 * <h2>为什么不能做成行为测试</h2>
 *
 * <p>要端到端跑 {@code MetricsCollector.start()}，就需要真的 {@code SchedulerAdapter.runGlobalTimer}，
 * 而它的 {@code <clinit>} 读 {@code ServerCompat.isFolia()}，在测试 JVM 里必抛
 * {@code ExceptionInInitializerError}（{@code tools/KNOWN-TEST-LIMITS.md} 第 1 条）。
 * 换个角度：就算能造出假调度器，要观察"并发 reload 产生两个定时器"也得让两个线程真的
 * 在 {@code start()} 里交错，那需要往生产代码里插可控的闩锁——为了测试而改变被测量对象。</p>
 *
 * <h2>这三条各有靶点</h2>
 *
 * <ul>
 *   <li>{@link #everyServiceHandleIsATaskHandleSlot()}：把任意一处的 {@code TaskHandleSlot}
 *       改回普通 {@code TaskHandle} 字段 → 红；</li>
 *   <li>{@link #noServiceStillCallsHandleCancelDirectly()}：绕过槽位直接
 *       {@code handle.cancel()} → 红（那是"取消权不唯一"的原形状）；</li>
 *   <li>{@link #startAndStopBothGoThroughTheSlot()}：{@code start()} 里出现
 *       {@code xxx = SchedulerAdapter.runXxxTimer(...)} 的直接赋值 → 红。</li>
 * </ul>
 */
class DeferredTaskOwnershipTest {

    /** 四个服务：类路径 + 它们必须持有的槽位字段名（多个槽位按顺序列出时就逐个断言）。 */
    private record Service(String path, String... slotFields) {
    }

    private static final List<Service> SERVICES = List.of(
        // 采集：两个节拍（主线程 + 异步 JVM），各一个槽位
        new Service("src/main/java/cn/perfscope/metrics/MetricsCollector.java",
            "syncSlot", "jvmSlot"),
        // 告警：周期检查定时器
        new Service("src/main/java/cn/perfscope/alert/AlertService.java", "timerSlot"),
        // 每日摘要：每分钟检查
        new Service("src/main/java/cn/perfscope/alert/DailySummaryService.java", "timerSlot"),
        // 插件本体：自动报告 + 采集看门狗
        new Service("src/main/java/cn/perfscope/PerfScope.java",
            "autoReportSlot", "healthWatchSlot"));

    /**
     * 去掉注释后再做文本判定。
     *
     * <p><b>为什么必须剥注释</b>：{@code MetricsCollector} 的 javadoc 里有
     * 「{@code TaskHandle.cancel()} 只是请求取消，不中断、也不 join」这样的叙述——
     * 不剥注释的话，一条解释"为什么不能直接 cancel"的注释会把"不许直接 cancel"的断言喂红。
     * 这正是 {@code StatusExportsTest} 里源码断言踩过的坑（子串匹配会被注释与别的代码段骗），
     * 这里用最小限度的剥离把噪音去掉，断言只针对真实语句。</p>
     */
    private static String stripComments(String src) {
        // 块注释：先剥 /.../（非贪婪，跨行）
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        // 行注释：剥 // 到行尾（不必处理字符串字面量里的 // ——本项目源码中不含这类注释陷阱）
        return noBlock.replaceAll("//[^\\n]*", " ");
    }

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

    /** 读源码并剥掉注释——所有"不许出现某种语句"的断言都应该用它。 */
    private static String readCode(String relative) throws IOException {
        return stripComments(readSource(relative));
    }

    // ==================== 1. 句柄字段类型 ====================

    /**
     * 每个服务都必须把句柄声明成 {@link cn.perfscope.compat.TaskHandleSlot}，且<b>不得</b>
     * 再留一个裸 {@code SchedulerAdapter.TaskHandle} 字段。
     *
     * <p>后者是这条测试的真正靶点：漏改的那一处编译器不会抱怨（旧字段仍然可用），
     * 只有源码断言能抓住。允许 {@code SchedulerAdapter.TaskHandle} 作为<b>局部变量</b>
     * 出现（{@code start()} 里接 {@code slot.start(...)} 的返回值），所以只查字段声明形态。</p>
     */
    @Test
    void everyServiceHandleIsATaskHandleSlot() throws IOException {
        // 字段声明：private [final] SchedulerAdapter.TaskHandle xxx;
        Pattern bareField = Pattern.compile(
            "^\\s*(?:private|protected|public)\\s+(?!final\\s+TaskHandleSlot)[^;=]*?"
                + "SchedulerAdapter\\.TaskHandle\\s+\\w+\\s*(?:=[^;]*)?;",
            Pattern.MULTILINE);

        for (Service svc : SERVICES) {
            String src = readCode(svc.path());

            for (String slot : svc.slotFields()) {
                assertTrue(
                    Pattern.compile("TaskHandleSlot\\s+" + Pattern.quote(slot) + "\\s*=")
                        .matcher(src).find(),
                    svc.path() + " 必须持有 TaskHandleSlot " + slot
                        + "（P0-1：非 volatile 普通句柄字段在并发 reload 下会让两个定时器并存）");
            }

            Matcher m = bareField.matcher(src);
            if (m.find()) {
                fail(svc.path() + " 仍有裸 TaskHandle 字段：" + m.group().trim()
                    + " —— 这处没换成 TaskHandleSlot，P0-1 的竞态原样存在");
            }
        }
    }

    // ==================== 2. 取消权唯一 ====================

    /**
     * 四个服务里不得再出现 {@code handle.cancel()} / {@code xxxTask.cancel()} 这类直接取消。
     *
     * <p>取消必须经 {@code slot.stop()}——它用 {@code getAndSet(null)} 保证同一句柄只会被
     * 一个线程取消。源码里直接调 {@code cancel()} 意味着有人重新引入了"谁都能停"的路径。</p>
     *
     * <p>只检查服务文件本身，{@code TaskHandleSlot} / {@code SchedulerAdapter} 内部的
     * {@code cancel()} 实现当然合法。</p>
     *
     * <p><b>为什么是"任何 {@code .cancel()} 都不许出现"这种最宽的形态</b>：第一版写的是
     * {@code \w*(Handle|Task|slot)\w*\.cancel()}，变异验证当场揭穿它——有人把取消写成
     * {@code timerSlot.current().cancel()} 时正则不匹配（接收者是 {@code ...current()} 而不是
     * 一个裸标识符），门禁照样绿。而那个写法正是"取消权不唯一"的变体：
     * {@code getAndSet(null)} 已经释放了引用，这里再拿一个快照去 cancel，等于绕开了唯一裁决。
     * 收紧到"这四个服务里不出现任何 {@code .cancel()} 调用"，让绕道无路可走。</p>
     */
    @Test
    void noServiceStillCallsHandleCancelDirectly() throws IOException {
        Pattern directCancel = Pattern.compile("\\.\\s*cancel\\s*\\(\\s*\\)");
        for (Service svc : SERVICES) {
            String src = readCode(svc.path());
            Matcher m = directCancel.matcher(src);
            if (m.find()) {
                fail(svc.path() + " 里仍有直接 cancel 调用：" + m.group()
                    + " —— 取消权必须由 TaskHandleSlot.stop() 的 getAndSet(null) 唯一裁决；"
                    + "连 timerSlot.current().cancel() 这种'先取快照再取消'的写法也算绕道");
            }
        }
    }

    // ==================== 3. 安装权唯一 ====================

    /**
     * 不得再出现"把调度器返回值直接赋给字段"的形态：
     * {@code xxx = SchedulerAdapter.runGlobalTimer(...)} / {@code runAsyncTimer(...)} / {@code runLater(...)}。
     *
     * <p>那正是"安装权不唯一"的原形状——两个线程各赋一次，先建的任务从此无人可取消。
     * 正确形态是 {@code slot.start(() -> SchedulerAdapter.runXxxTimer(...))}。</p>
     */
    @Test
    void startAndStopBothGoThroughTheSlot() throws IOException {
        Pattern directAssign = Pattern.compile(
            "=\\s*SchedulerAdapter\\.run(?:GlobalTimer|AsyncTimer|Later)\\s*\\(");
        for (Service svc : SERVICES) {
            String src = readCode(svc.path());
            Matcher m = directAssign.matcher(src);
            if (m.find()) {
                fail(svc.path() + " 里仍有直接赋值安装：" + m.group()
                    + " —— 安装必须经 TaskHandleSlot.start(...) 的 CAS 闸口");
            }
        }
    }

    // ==================== 4. reload 串行化 ====================

    /**
     * {@code PerfScope.reload()} 的整个方法体必须被一个实例级锁包住。
     *
     * <p>四个槽位解决的是"同一服务不会有两个定时器"，但两段 reload 仍然可以交错成
     * 「A 停 → B 停 → A 重建 → B 重建」：最终生效的采集间隔取决于交错顺序，而不是最后一次
     * 重读配置的结果。串行化让并发 reload 退化成排队各跑一遍。</p>
     *
     * <p>判定方式：找 {@code public void reload()}，其后第一个语句必须是
     * {@code synchronized (reloadLock)}（允许中间有注释与空行）。</p>
     */
    @Test
    void reloadBodyIsGuardedByAnInstanceLock() throws IOException {
        String src = readCode("src/main/java/cn/perfscope/PerfScope.java");
        assertTrue(src.contains("reloadLock"),
            "PerfScope 必须有一个 reloadLock 实例锁字段");

        int idx = src.indexOf("public void reload()");
        assertFalse(idx < 0, "找不到 public void reload()——方法被改名了？");
        String after = src.substring(idx);

        // 允许方法签名与 synchronized 之间只有空白/注释
        Matcher m = Pattern.compile(
            "^[\\s\\S]{0,400}?synchronized\\s*\\(\\s*reloadLock\\s*\\)\\s*\\{", Pattern.MULTILINE)
            .matcher(after);
        assertTrue(m.find(),
            "reload() 的首个语句必须是被 synchronized (reloadLock) 包住的方法体——"
                + "否则并发 reload 会交错成两段 stop/start，最终生效的间隔取决于交错顺序");
    }
}