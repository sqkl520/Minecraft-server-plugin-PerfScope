/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package cn.perfscope.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2：{@link ConfigManager#load()} 必须是 {@code synchronized}。
 *
 * <h2>这条守的是什么</h2>
 *
 * <p>{@code load()} 内部有三个<b>普通</b>集合：{@code missingKeysThisLoad}（LinkedHashMap）、
 * {@code typeWarnedThisLoad}（HashSet）、{@code typeWarningsThisLoad}（ArrayList）。
 * 每次进入先 {@code clear()}，随后在几十个 {@code readXxx()} 里 {@code put}/{@code add}。
 * 两个线程同时 load 会得到两种伤害，而<b>第一种会把排障方向带偏</b>：</p>
 *
 * <ul>
 *   <li>{@code ConcurrentModificationException} 从 {@code load()} 抛出 → 被
 *       {@code PerfScope.reload()} 的 catch 当成「配置解析失败」→ 打印"已按旧配置继续"。
 *       日志里一个字都没提并发，而真正的原因就是并发；</li>
 *   <li>{@code HashSet} 并发 {@code add} 可能撕裂桶链成环，之后的 {@code contains}
 *       <b>永远转</b>在链上，卡死 region 线程——没有任何日志，比异常严重得多。</li>
 * </ul>
 *
 * <h2>为什么是"断言修饰符"而不是"构造并发场景"</h2>
 *
 * <p>并发场景可以构造（两个线程各调一次 {@link ConfigManager#load()}，重复几百轮），
 * 但它的结论<b>只能反向使用</b>：跑绿了不能证明没有竞态（调度器可能一次都没让两个线程
 * 真正交错），跑红了才有信息量。拿一条"跑绿没意义"的用例当回归保护，等于给未来的人一个
 * 虚假的安全感。修饰符是<b>确定性</b>的：去掉 {@code synchronized} 一定红，
 * 留着一定绿，且这条断言与 {@code load()} 的实现细节解耦（换成内部锁也只需改这一处断言）。
 * 这与 {@code check-reload-rebuild.mjs} 同一取舍：行为只在特定环境显现时，把约束上升到结构层。</p>
 *
 * <p>配套的"集合访问必须留在 load 调用栈内"这条不变量写在字段注释里，无法机械判定，
 * 故不在此断言——但 {@code synchronized} 一旦被去掉，本类立刻红，注释会在失败信息里被读到。</p>
 */
class ConfigLoadSynchronizationTest {

    /**
     * <b>靶点</b>：把 {@code load()} 的 {@code synchronized} 去掉，这条立刻红。
     *
     * <p>失败信息里刻意带上"两个集合各是什么"——只写"must be synchronized"会让下一个人
     * 想把它"顺手"改成 {@code ConcurrentHashMap} 交差，而那样防不住 {@code clear()} 与
     * {@code put} 的整体性（A 的 clear 会抹掉 B 刚填的缺键，结果变成日志少报几条，静默错报）。</p>
     */
    @Test
    void loadIsSynchronized() throws Exception {
        Method load = ConfigManager.class.getDeclaredMethod("load");
        assertTrue(Modifier.isSynchronized(load.getModifiers()),
            "ConfigManager.load() 必须是 synchronized：它内部用普通 LinkedHashMap / HashSet / ArrayList "
                + "收集本轮缺键与类型告警，并发 load 时 ConcurrentModificationException 会被 reload 的 catch "
                + "误报成「配置解析失败」，而 HashSet 并发 add 撕裂桶链后 contains 会死循环卡住 region 线程。"
                + "注意：换成 ConcurrentHashMap 不够——它防撕裂，防不住 A 的 clear() 抹掉 B 刚填的缺键。");
    }

    /**
     * 反面对照：{@code get()} 刻意<b>不</b>加锁。
     *
     * <p>{@code get()} 走 {@code volatile} 字段，是热路径（每次告警判定、每条命令、每个报告
     * 片段都要读）。给它加 {@code synchronized} 会让所有读者去抢 reload 那把锁——reload 期间
     * 读盘 + 解析可能持续几百毫秒，整个服务端会跟着卡。这条断言防止"顺手统一加锁"。</p>
     */
    @Test
    void getStaysLockFree() throws Exception {
        Method get = ConfigManager.class.getDeclaredMethod("get");
        assertFalse(Modifier.isSynchronized(get.getModifiers()),
            "ConfigManager.get() 不得 synchronized：它读 volatile 快照、是热路径，"
                + "加锁会让所有读者在 reload 的读盘期间排队，把一次配置重载放大成全局卡顿");
    }
}