#!/usr/bin/env node
/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * 变异验证器：证明某个测试类**真的能杀死**它声称锁住的改动。
 *
 * <h2>为什么必须有这个东西</h2>
 * "测试通过"只证明测试与被测代码当前一致，不证明测试能发现错误。本仓库已经踩过至少
 * 五次「写了测试、断言是假的、变异存活」的坑（最近的例子：`assertFalse(out.contains(" "
 * + metric + "{"))` 中的空格永远不匹配行首，改成 `"\n"` 才有意义）。
 *
 * 判据只有一条：**把被测代码按变异描述改坏，跑测试，必须变红。**
 * 绿了就是假测试（SURVIVED），红了才是真测试（KILLED）。
 *
 * <h2>用法</h2>
 * 变异表写在脚本内的 `MUTATIONS` 里，每项声明：文件、原文、替换、以及应当杀死它的测试类。
 *
 *   node tools/mutate.mjs              # 跑全部
 *   node tools/mutate.mjs --only M3    # 只跑编号 M3
 *   node tools/mutate.mjs --list       # 只列变异，不跑
 *
 * <h2>安全边界</h2>
 * - 改前先 `git status --porcelain` 检查工作树，**不干净就拒绝运行**。
 *   否则 `git checkout -- <file>` 会把未提交的改动一起抹掉（本仓库踩过三次）。
 * - 每个变异跑完立刻 `git checkout --` 还原，且还原后校验 `git diff --quiet` 为空。
 * - 中途异常（Ctrl-C / Maven 超时）不阻断还原：用 try/finally 包住。
 *
 * <h2>SKIP 也是失败（曾经的静默漏洞）</h2>
 *
 * <p>旧版的退出码只看 `survived > 0`，于是 `from` 字面量被重构搬走之后那条变异会打一行
 * SKIP 然后<b>悄悄消失</b>——覆盖少了一条，脚本照样 exit 0，CI 与人都不会注意到。
 * 本仓库真实发生过：一次重构把若干比较行收进算子表，两条变异从此永远 SKIP。</p>
 *
 * <p>现在 SKIP 计入失败：汇总行单独列出 SKIP 的编号与原因，退出码改为
 * `survived > 0 || skipped > 0`。同时三种 SKIP 的成因分开标注——"原文未找到"是
 * <b>变异表过期</b>（必须修），"既无 expect 也无 gate"是<b>变异表写错</b>（必须修）。
 * 判据：**一条变异只有 KILLED 才算数**。</p>
 */

import { execSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');

/**
 * 变异表。
 *
 * 每项的 `expect` 是**应当变红**的测试类；若该测试类全绿，说明它锁不住这个改动。
 * `desc` 要写清"改坏之后用户会看到什么"，而不只是"这行改了"——否则变异表本身
 * 也会变成一份没人读得懂的清单。
 */
const MUTATIONS = [
    {
        id: 'M1',
        file: 'src/main/java/cn/perfscope/config/ConfigManager.java',
        from: 'if (v <= 0.0d || v > 100.0d) {',
        to: 'if (v <= 0.0d) {',
        desc: 'sanitizePercent 去掉 100 上界 —— cpu 阈值写 150 被放行，'
            + 'processCpuLoad 永远到不了，这条告警等于被静默关掉',
        expect: 'CpuThresholdAndHysteresisValidationTest',
    },
    {
        id: 'M2',
        file: 'src/main/java/cn/perfscope/config/ConfigManager.java',
        from: 'cfg.tps_warningThreshold, cfg.tps_criticalThreshold, 18.0, 15.0, false);',
        to: 'cfg.tps_warningThreshold, cfg.tps_criticalThreshold, 18.0, 15.0, true);',
        desc: 'tps 的 highIsBad 方向写反 —— tps 是低值差（越低越差），传 true 后'
            + '正常配置 18/15 被判倒置并回退，告警阈值永久失效',
        expect: 'DoubleThresholdPairValidationTest',
    },
    {
        id: 'M3',
        file: 'src/main/java/cn/perfscope/config/ConfigManager.java',
        from: 'private static final double TPS_CEILING = 20.5;',
        to: 'private static final double TPS_CEILING = 10_000_000.0;',
        desc: 'TPS_CEILING 提到 1e7 等于拆掉这道闸 —— tps.warning_threshold: 25 合法通过，'
            + '窗口均值恒 <= 20.5 < 25，DIAG-TPS-PERSIST 永久命中',
        expect: 'DoubleThresholdPairValidationTest',
    },
    {
        id: 'M4',
        file: 'src/main/java/cn/perfscope/config/ConfigManager.java',
        from: 'if (cfg.mspt_warningThreshold <= 0.0d || cfg.mspt_criticalThreshold <= 0.0d) {',
        to: 'if (false) {',
        desc: '拆掉 mspt 的 0 闸 —— mspt_critical_threshold: 0 让「严重」档恒真（判据是 msptAvg > 阈值，'
            + '而 msptAvg 恒正）',
        expect: 'DoubleThresholdPairValidationTest',
    },
    {
        id: 'M5',
        file: 'src/main/java/cn/perfscope/config/ConfigManager.java',
        from: 'if (cfg.cpu_hysteresisMargin >= cpuMinThreshold) {',
        to: 'if (cfg.cpu_hysteresisMargin > cpuMinThreshold) {',
        desc: '迟滞带交叉校验的闭合方向改开 —— margin 恰好等于阈值时不拦，退出线 threshold-margin = 0，'
            + '而判定值恒正，闩锁一旦进入就永不解除',
        expect: 'CpuThresholdAndHysteresisValidationTest',
    },
    {
        id: 'M6',
        file: 'src/main/java/cn/perfscope/config/ConfigManager.java',
        from: 'double fallback = Math.min(CPU_HYSTERESIS_DEFAULT, cpuMinThreshold / 2.0d);',
        to: 'double fallback = CPU_HYSTERESIS_DEFAULT;',
        desc: '迟滞带回退值硬编码成默认 5.0 —— 阈值为 3 时 5 仍不小于阈值，回退后问题照旧',
        expect: 'CpuThresholdAndHysteresisValidationTest',
    },
    {
        id: 'M7',
        file: 'src/main/java/cn/perfscope/config/ConfigManager.java',
        from: 'double cpuMinThreshold = Math.min(cfg.cpu_processWarningThreshold, cfg.cpu_systemWarningThreshold);',
        to: 'double cpuMinThreshold = cfg.cpu_processWarningThreshold;',
        desc: '交叉校验只取 process 不看 system —— system 阈值更低时 margin 会越过它，'
            + 'system 那条告警永不解除',
        expect: 'CpuThresholdAndHysteresisValidationTest',
    },
    {
        id: 'M8',
        file: 'src/main/java/cn/perfscope/config/ConfigManager.java',
        from: 'if (h < 0 || h > 23 || m < 0 || m > 59) {',
        to: 'if (m < 0 || m > 59) {',
        desc: 'parseHhMmMinutes 去掉小时越界 —— "99:99" 被算成 6039 分钟，'
            + '与当天任何时刻都不相等，每日摘要永远不触发且完全静默',
        expect: 'HhMmTimeParsingTest',
    },
    {
        id: 'M9',
        file: 'src/main/java/cn/perfscope/config/ConfigManager.java',
        from: 'return v.matches("\\\\d{1,2}:\\\\d{2}") && parseHhMmMinutes(v) >= 0;',
        to: 'return parseHhMmMinutes(v) >= 0;',
        desc: 'isValidHhMm 去掉形态正则 —— "+8:00" / "008:00" / "8 : 00" 都会被接受，'
            + 'DailySummaryService 的解析结果与配置层校验口径分裂',
        expect: 'HhMmTimeParsingTest',
    },
    {
        id: 'M10',
        file: 'src/main/java/cn/perfscope/config/ConfigManager.java',
        from: 'if (!isValidHhMm(cfg.alerts_dailySummaryTime)) {',
        to: 'if (false) {',
        desc: '拆掉 daily-summary.time 的回退 —— 非法值原样留在配置里，'
            + 'DailySummaryService 拿到 -1 后行为未定义',
        expect: 'HhMmTimeParsingTest',
    },
    {
        id: 'M11',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        repairSubsetContracts(snap);',
        to: '',
        desc: '拆掉 freezeCollections 内置的契约修复 —— 子集被破坏的快照会被原样发布出去',
        expect: 'SnapshotSubsetInvariantTest',
    },
    {
        id: 'M12',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '                | requireSubset(snap.platformSkipped, snap.disabledModules,\n'
            + '                    "platformSkipped", "disabledModules")',
        to: '                | requireSubset(snap.platformSkipped, snap.unavailable,\n'
            + '                    "platformSkipped", "unavailable")',
        desc: '把 platformSkipped 的父集合从 disabledModules 放宽成 unavailable ——'
            + '恰好绕过 Folia MSPT 那个真实漏洞，是「看起来对但拦不住」的典型',
        expect: 'SnapshotSubsetInvariantTest',
    },
    {
        id: 'M13',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        for (int pass = 0; pass < 2; pass++) {',
        to: '        for (int pass = 0; pass < 1; pass++) {',
        desc: '把级联修复从两遍砍成一编 —— platformSkipped 的新成员进不了 unavailable，'
            + '只看 unavailable 的 40 处消费点仍然把它当健康',
        expect: 'SnapshotSubsetInvariantTest',
    },
    {
        id: 'M14',
        file: 'src/main/java/cn/perfscope/compat/SchedulerAdapter.java',
        from: '            return bukkitTask != null || foliaTask != null;',
        to: '            return true;',
        desc: 'isActive 恒返回 true —— noop 句柄也报告「已启动」，'
            + '采集/告警/探针全部静默停摆且控制台无迹象',
        expect: 'TaskHandleContractTest',
    },
    {
        id: 'M15',
        file: 'src/main/java/cn/perfscope/compat/SchedulerAdapter.java',
        from: '                } catch (Exception | LinkageError e) {',
        to: '                } catch (Exception e) {',
        desc: 'Bukkit 分支把 catch 从 Exception|LinkageError 缩回 Exception —— '
            + 'Folia 上类缺失的 NoClassDefFoundError 会在关服路径冒泡',
        expect: 'TaskHandleContractTest',
    },
    {
        id: 'M16',
        file: 'src/main/java/cn/perfscope/compat/SchedulerAdapter.java',
        from: '            if (foliaTask != null) {',
        to: '            if (false) {',
        desc: 'Folia 路径的 cancel 分支拆掉 —— Folia 句柄永不取消，'
            + 'reload 后旧任务残留并与新实例同时跑',
        expect: 'TaskHandleContractTest',
    },
    {
        id: 'M17',
        file: 'src/main/java/cn/perfscope/compat/FoliaSchedulerBridge.java',
        from: '        return ignored -> task.run();',
        to: '        return ignored -> { };',
        desc: 'wrap 返回空 Consumer —— Folia 上所有定时任务空转，'
            + '采集/告警/探针全停，而调度器与日志都显示正常（最隐蔽的失效形态）',
        expect: 'FoliaSchedulerBridgeContractTest',
    },
    {
        id: 'M18',
        file: 'src/main/java/cn/perfscope/compat/FoliaSchedulerBridge.java',
        from: '        ((ScheduledTask) scheduledTask).cancel();',
        to: '        if (scheduledTask instanceof ScheduledTask st) { st.cancel(); }',
        desc: 'cancel 加防御性 instanceof 检查后静默 return —— '
            + '把「句柄来源不对」从可见失败变成无声的功能停用（javadoc 明确要求抛）',
        expect: 'FoliaSchedulerBridgeContractTest',
    },
    {
        id: 'M19',
        file: 'src/main/java/cn/perfscope/alert/AlertCatalog.java',
        from: '        return new Object[] {formattedValue};',
        to: '        return new Object[] {scopeOrEmpty(alert), formattedValue};',
        desc: '复现 BLOCKER-1：普通模板也传 (scope, value) —— 数值被塞进没人读的 {1}，'
            + '所有普通告警渲染成「Heap usage high: 」（数值消失、零异常零日志）',
        expect: 'AlertCatalogTest',
    },
    {
        id: 'M20',
        file: 'src/main/java/cn/perfscope/alert/AlertCatalog.java',
        from: '        if (Double.isNaN(value)) {\n            return "?";',
        to: '        if (Double.isNaN(value)) {\n            return "0";',
        desc: 'NaN 渲染成 0 —— 采集没拿到值时服主看到「CPU 占用 0%」，把盲点报成健康',
        expect: 'AlertCatalogTest',
    },
    {
        id: 'M21',
        file: 'src/main/java/cn/perfscope/alert/AlertCatalog.java',
        from: '        return alert.critical() ? Alert.LEVEL_CRITICAL : Alert.LEVEL_WARNING;',
        to: '        return Alert.LEVEL_WARNING;',
        desc: 'levelId 恒返回 warning —— 严重与警告渲染同一套文案，'
            + '而级别正是决定服主要不要立刻处理的信息',
        expect: 'AlertCatalogTest',
    },
    {
        id: 'M22',
        file: 'src/main/java/cn/perfscope/alert/AlertCatalog.java',
        from: '        return metric != null && metric.scopeScoped() && !scopeOrEmpty(alert).isEmpty();',
        to: '        return false;',
        desc: 'needsScope 恒 false —— 世界维度告警丢失世界名，服主不知道是哪个世界出的问题',
        expect: 'AlertCatalogTest',
    },
    {
        id: 'M23',
        file: 'src/main/java/cn/perfscope/compat/MsptCapabilityState.java',
        from: '        if (streak < ZERO_SAMPLE_CONFIRMATIONS) {',
        to: '        if (false) {',
        desc: '拆掉「两次确认」——首次全零就降级，'
            + '首周期空窗口会把健康 Paper 误判成不可用，用户永久失去精确 MSPT',
        expect: 'MsptCapabilityStateTest',
    },
    {
        id: 'M24',
        file: 'src/main/java/cn/perfscope/compat/MsptCapabilityState.java',
        from: '        if (!avgBad) {',
        to: '        if (avgBad) {',
        desc: '两级降级的分支写反 —— 二级明明可用却报不可用，'
            + '用户丢掉唯一平均值来源；且 reasonKey 与 isMsptTrustworthy() 会自相矛盾',
        expect: 'MsptCapabilityStateTest',
    },
    {
        id: 'M25',
        file: 'src/main/java/cn/perfscope/compat/MsptCapabilityState.java',
        from: '        boolean bad = !tickTimesMethodPresent || !primarySampleOk;',
        to: '        boolean bad = !primarySampleOk;',
        desc: '一级方法缺失时不再直接判负 —— 纯 avg 服务端会白跑满两轮，'
            + '且一旦 primarySampleOk 被误传 true 就永远判「可用」（假精确）',
        expect: 'MsptCapabilityStateTest',
    },
    {
        id: 'M26',
        file: 'src/main/java/cn/perfscope/compat/MsptCapabilityState.java',
        from: '                msptAvailable, avgTickAvailable, null, true, 0);',
        to: '                true, true, null, true, 0);',
        desc: '一级成功时顺手把两个能力位都置 true —— 违反「只降不升」，'
            + '在 tick 计时恒零的 Paper 分支上宣称「精确」',
        expect: 'MsptCapabilityStateTest',
    },
    {
        id: 'M27',
        file: 'src/main/java/cn/perfscope/BstatsCharts.java',
        from: '            return snap == null ? Map.of() : single(bucket(snap.playerCount, PLAYER_BUCKETS, "200+"));',
        to: '            return snap == null ? Map.of() : single(bucket(snap.playerCount, ENTITY_BUCKETS, "50000+"));',
        desc: '退回真实存在过的 bug：玩家数复用实体桶 —— 0-100 玩家合并成一档、'
            + '溢出标签写「50000+」，bStats 后台读不出大服与小服的差别（零异常零日志）。'
            + '单测抓不到这个（register() 要真 Bukkit 才跑），由源码门禁守。',
        expect: null,
        gate: 'tools/check-bstats-buckets.mjs',
    },
    {
        id: 'M28',
        file: 'src/main/java/cn/perfscope/alert/Metric.java',
        from: '            case "entities" -> rest.startsWith("world:") ? ENTITIES_WORLD : ENTITIES;',
        to: '            case "entities" -> ENTITIES;',
        desc: 'entities 不再判世界维度 —— 单世界数字被当成全服数字，'
            + '服主会按一个世界的实体数去优化整个服务器',
        expect: 'MetricKeyParsingTest',
    },
    {
        id: 'M29',
        file: 'src/main/java/cn/perfscope/alert/Metric.java',
        from: '        int i = key.indexOf(\':\');',
        to: '        int i = key.lastIndexOf(\':\');',
        desc: '按最后一个冒号切分 —— 世界名含冒号的历史数据（entities:world:my:coloned:world）'
            + '会被解析成错的指标或 null',
        expect: 'MetricKeyParsingTest',
    },
    {
        id: 'M30',
        file: 'src/main/java/cn/perfscope/alert/Metric.java',
        from: '            case "chunks" -> CHUNKS_WORLD;',
        to: '            case "chunks" -> rest.startsWith("world:") ? CHUNKS_WORLD : null;',
        desc: 'chunks 加全服口径判断后对 chunks:global 返回 null —— '
            + '历史告警退化成原始 key 展示（而 chunks 从没有全服枚举）',
        expect: 'MetricKeyParsingTest',
    },
    {
        id: 'M31',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        long ref = last > 0L ? last : startedAtMs;',
        to: '        long ref = last;',
        desc: 'isJvmStale 去掉「从未采集时退回 startedAtMs」——now-0 恒大于任何阈值，'
            + '插件每次启动都报「JVM 指标已停止更新 56 年」，服主学会无视这条警告',
        expect: 'StalenessQueryTest',
    },
    {
        id: 'M32',
        // 860 行（isJvmStale）与 2284 行（isStale）的返回语句文本完全相同，
        // 所以必须带上前一行的 `long ref = ...` 才能唯一定位到 isJvmStale。
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        long ref = last > 0L ? last : startedAtMs;\n'
            + '        return System.currentTimeMillis() - ref > Math.max(WARMUP_WINDOW_MS, staleThresholdMs());',
        to: '        long ref = last > 0L ? last : startedAtMs;\n'
            + '        return System.currentTimeMillis() - ref > WARMUP_WINDOW_MS;',
        desc: 'isJvmStale 只比宽限期不取 max —— update_interval=100 的服务器在 10 秒后就报 stale，'
            + '比它自己的采集节拍（15 秒）还急，每轮误报一次',
        expect: 'StalenessQueryTest',
    },
    {
        id: 'M33',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        return Math.max(3000L, intervalMs * 3L);',
        to: '        return intervalMs * 3L;',
        desc: 'staleThresholdMs 去掉 3000ms 下界 —— update_interval=1 时判定窗口只有 150ms，'
            + '20 tick/s 的服务端偶发一次调度抖动就报「采集已停止」',
        expect: 'StalenessQueryTest',
    },
    {
        id: 'M34',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        return jvm == null ? 0L : jvm.timestamp;',
        to: '        return jvm == null ? System.currentTimeMillis() : jvm.timestamp;',
        desc: 'jvmSnapshotTimestamp 在无快照时返回「现在」而不是 0 —— '
            + '看门狗报出一个看起来像「刚刚」的时刻，掩盖了「从来没采到过」',
        expect: 'StalenessQueryTest',
    },
    {
        id: 'M35',
        file: 'src/main/java/cn/perfscope/PerfScope.java',
        from: '        startCollectionTasks();\n        startAlertService();',
        to: '        if (!loadFailed) {\n            startCollectionTasks();\n'
            + '            startAlertService();\n        }',
        desc: '把 reload 的重建包进 !loadFailed（看起来合理："配置都没加载成功，重建成干什么"）—— '
            + '配置写坏一次就停掉全部任务且不重建，采集停摆、告警静默、每日摘要不发，'
            + '而控制台只多一条读起来像"一切照旧"的 warning',
        expect: null,
        gate: 'tools/check-reload-rebuild.mjs',
    },
    {
        id: 'M36',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        return Math.round(value * 100.0) / 100.0;',
        to: '        return Math.round(value * 10.0) / 10.0;',
        desc: 'round2 只保留一位小数 —— TPS 19.99 变成 20.0，'
            + '「是否满 TPS」的目视判断直接反转',
        expect: 'CollectorFormattingTest',
    },
    {
        id: 'M37',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);',
        to: '        return (msg == null ? "" : msg);',
        desc: 'describeThrowable 丢掉类名 —— 服主搜 IllegalStateException 搜不到，'
            + '只剩一句消息无从判断严重程度；无消息时更退化成一个空串',
        expect: 'CollectorFormattingTest',
    },
    {
        id: 'M38',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        if (t == null) {\n            return Messages.get("run.collector.unknown-error");',
        to: '        if (false) {\n            return Messages.get("run.collector.unknown-error");',
        desc: 'describeThrowable 去掉 null 守卫 —— 采集失败的日志变成'
            + '「记日志时又抛了一个 NPE」，原本要报告的错误被掩盖',
        expect: 'CollectorFormattingTest',
    },
    {
        id: 'M39',
        file: 'src/main/java/cn/perfscope/metrics/ThresholdOperator.java',
        from: '        new Rule("entities:global", Direction.HIGH_IS_BAD, '
            + 'Comparison.STRICT, Comparison.STRICT);',
        to: '        new Rule("entities:global", Direction.HIGH_IS_BAD, '
            + 'Comparison.INCLUSIVE, Comparison.STRICT);',
        desc: '把 entities 的采集层判定从开区间改成闭区间 —— 两侧「统一」了，'
            + '早预警语义（README 第 39 行）随之取消，读数恰好等于 30000 的一批服务器会突然开始报 critical。'
            + '本变异用来验证「早预警语义」这条断言真的会亮（判定已收进算子表，探针跟到表里）',
        expect: 'ThresholdBoundaryAsymmetryTest',
    },
    {
        id: 'M40',
        file: 'src/main/java/cn/perfscope/util/ThresholdColors.java',
        from: '                if (value >= critical) return Level.CRITICAL;',
        to: '                if (value > critical) return Level.CRITICAL;',
        desc: '把展示层的闭区间改成开区间 —— 恰好到线的数字不再变红，'
            + '早预警语义的展示侧（颜色先变）被取消，'
            + '违反 ThresholdColorsTest「30000 必须已经变红」的 UI 契约',
        expect: 'ThresholdBoundaryAsymmetryTest',
    },
    {
        id: 'M41',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '                if (t <= 0) {\n                    continue; // 跳过未填充槽位/无效采样',
        to: '                if (t < 0) {\n                    continue; // 跳过未填充槽位/无效采样',
        desc: '跳过条件从 t<=0 放宽成 t<0 —— 未填充槽位 0 被计入 min，'
            + 'min 恒为 0，展示层用 msptMin<=0 判「未采集」，于是有效极值被渲染成「没数据」',
        expect: 'MsptReduceTest',
    },
    {
        id: 'M42',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '            return new MsptStats(-1, -1, -1, 0);',
        to: '            return new MsptStats(0, 0, 0, 0);',
        desc: '无样本时返回 0 而不是 -1 哨兵 —— MSPT 里 0 是合法读数，'
            + '于是「服务器极快」与「采不到」无法区分',
        expect: 'MsptReduceTest',
    },
    {
        id: 'M43',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '            round2(max / 1_000_000.0),',
        to: '            round2(max / 1_000.0),',
        desc: 'max 的换算从 /1e6 改成 /1e3 —— min/avg 是毫秒、max 是微秒，量级差 1000 的混排',
        expect: 'MsptReduceTest',
    },
    {
        id: 'M44',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '            round2(sum / (double) n / 1_000_000.0),',
        to: '            round2(sum / (double) (arr == null ? 1 : arr.length) / 1_000_000.0),',
        desc: 'avg 的分母用数组总长度而不是有效样本数 —— 含零槽位时 avg 被拉低，'
            + '甚至可能小于 min（「平均比最快还快」）',
        expect: 'MsptReduceTest',
    },
    {
        id: 'M45',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        if (platformSkipped) {\n            // 契约要求 platformSkipped ⊆ disabledModules：平台限制同时是"服主没配过但平台不让采"，\n'
            + '            // 展示层据此渲染更精确的原因而不是笼统的「已禁用」。\n'
            + '            snap.disabledModules.add(module);\n'
            + '            snap.platformSkipped.add(module);   // 9B-3',
        to: '        if (platformSkipped) {\n            snap.platformSkipped.add(module);   // 9B-3',
        desc: '退回四份手写块最容易犯的错：写 platformSkipped 却忘了写 disabledModules —— '
            + '展示层按 platformSkipped → disabledModules → unavailable 顺序判定，'
            + 'Folia 上会显示红色「采集失败」而不是灰色「平台限制」',
        expect: 'ModuleUnavailableMarkingTest',
    },
    {
        id: 'M46',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        snap.unavailable.add(module);\n        if (disabled) {',
        to: '        if (disabled) {\n            snap.unavailable.add(module);',
        desc: 'unavailable 只在 disabled 时写 —— 采集真的失败（disabled=false）时模块既不进 '
            + 'unavailable 也不进其他集合，/metrics 会输出它的 0 值',
        expect: 'ModuleUnavailableMarkingTest',
    },
    {
        id: 'M47',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        if (!(msptAvg > 0)) {\n'
            + '            return MetricsSnapshot.ALERT_LEVEL_NO_VERDICT;\n'
            + '        }',
        to: '        if (!(msptAvg > 0)) {\n'
            + '            return 0;\n'
            + '        }',
        desc: 'MSPT 哨兵被当成"健康"（返回 0 而不是"没判定"）—— msptAvg=-1（TPS 反推路径）时'
            + '告警层会把它读成"测过了、很健康"，三个周期后推一条与事实相反的「已恢复正常」，'
            + '而那个维度从头到尾没有被测量过',
        expect: 'MsptAlertLevelTest',
    },
    {
        id: 'M48',
        file: 'src/main/java/cn/perfscope/metrics/ThresholdOperator.java',
        from: '        new Rule("mspt:global", Direction.HIGH_IS_BAD, '
            + 'Comparison.STRICT, Comparison.STRICT);',
        to: '        new Rule("mspt:global", Direction.HIGH_IS_BAD, '
            + 'Comparison.STRICT, Comparison.INCLUSIVE);',
        desc: 'MSPT 的 warning 档阈值比较改成 >=（critical 保持严格）—— 恰好等于阈值的样本被判越界，'
            + '默认 45ms 警告线下一台"刚好卡在线上"的服务器会持续告警。'
            + '这是「只挪半格」的变异：两档共用一个 Comparison 字段时它根本无法被单独表达，也就无从杀死',
        expect: 'ThresholdOperatorBehaviorTest',
    },
    {
        id: 'M49',
        file: 'src/main/java/cn/perfscope/alert/AlertService.java',
        from: '            boolean verdictMissing = judgedLevel != null'
            + ' && judgedLevel == MetricsSnapshot.ALERT_LEVEL_NO_VERDICT;',
        to: '            boolean verdictMissing = judgedLevel == null;',
        desc: '把"没判定"重新读成"键缺席" —— 这次反过来了：世界被卸载后 chunks:world:<名> '
            + '不再出现，却被当成"没测"，该告警会永久卡在 unknown 里永远不再消失',
        expect: 'MissingLevelKeyIsNotRecoveryTest',
    },
    {
        id: 'M50',
        file: 'src/main/java/cn/perfscope/alert/AlertService.java',
        from: '            boolean verdictMissing = judgedLevel != null'
            + ' && judgedLevel == MetricsSnapshot.ALERT_LEVEL_NO_VERDICT;\n'
            + '            boolean moduleDown',
        to: '            boolean verdictMissing = false;\n'
            + '            boolean moduleDown',
        desc: '把"没判定"重新并回"干净周期" —— 采集层在哨兵路径（msptAvg<=0 的 TPS 反推、'
            + 'Windows 上 processCpuLoad<0）声明"没测"时，告警会在三个周期后宣布「已恢复正常」，'
            + '同时离开活跃列表，真故障从此不再被提醒',
        expect: 'MissingLevelKeyIsNotRecoveryTest',
    },
    {
        id: 'M51',
        file: 'src/main/java/cn/perfscope/alert/AlertService.java',
        from: '            if (judgedLevel != null && judgedLevel > 0) {',
        to: '            if (judgedLevel != null && judgedLevel >= 0) {',
        desc: '「判定过且健康」（写 0）也被当成不恢复 —— 修复做过了头：真实恢复永远不发生，'
            + '告警会永久挂在活跃列表，服主最终会不再相信告警列表',
        expect: 'MissingLevelKeyIsNotRecoveryTest',
    },
    {
        id: 'M52',
        file: 'src/main/java/cn/perfscope/alert/AlertService.java',
        from: '            Integer judgedLevel = snap.alertLevels == null ? null : snap.alertLevels.get(key);\n'
            + '            if (judgedLevel != null'
            + ' && judgedLevel == MetricsSnapshot.ALERT_LEVEL_NO_VERDICT) {\n'
            + '                continue; // 仍无结论：留在 unknown（不计恢复、不删除）\n'
            + '            }',
        to: '',
        desc: 'unknown 状态下的「静默解决」不再要求判定结果 —— 一个已转 unknown 的告警会在下一个'
            + '声明的"没判定"周期被删掉，unknown 行从界面上消失，而采集层从未给出过任何结论',
        expect: 'MissingLevelKeyIsNotRecoveryTest',
    },
    {
        id: 'M53',
        file: 'src/main/java/cn/perfscope/export/MetricsTextRenderer.java',
        from: '                if (key == null || level == null || level < 0) {',
        to: '                if (key == null || level == null) {',
        desc: '渲染层放行内部的 -1 哨兵 —— perfscope_alert 会出现取值为 -1 的序列，'
            + '违反 HELP 里写明的 0/1/2 契约，任何 `perfscope_alert < 1` 的规则都会把"没测"报成"有事"',
        expect: 'MetricsAlertStateTest',
    },
    // ---------------------------------------------------------------------------------
    // 阈值算子表（ThresholdOperator）：六个方向此前实测存活 —— 判定散在 7 个 collect* 方法里时，
    // 把任一算子挪半格，全量 715 个用例一个都不红。收成显式算子表后每个方向都能被单独命中。
    // 注意：这里的 `from` 必须带 key 字面量（Rule 构造行），否则六条几乎同形的构造行无法唯一定位。
    // ---------------------------------------------------------------------------------
    {
        id: 'M54',
        file: 'src/main/java/cn/perfscope/metrics/ThresholdOperator.java',
        from: '        new Rule("tps:global", Direction.LOW_IS_BAD, '
            + 'Comparison.STRICT, Comparison.STRICT);',
        to: '        new Rule("tps:global", Direction.LOW_IS_BAD, '
            + 'Comparison.INCLUSIVE, Comparison.STRICT);',
        desc: 'TPS 的 critical 档从严格 `<` 改成 `<=` —— TPS 恰好 15.0 时从「只发 warning」变成'
            + '「发 critical」，而 TPS 15.0 恰好是默认 critical 阈值，是最常见的边界读数',
        expect: 'ThresholdOperatorBehaviorTest',
    },
    {
        id: 'M55',
        file: 'src/main/java/cn/perfscope/metrics/ThresholdOperator.java',
        from: '        new Rule("chunks:world:*", Direction.HIGH_IS_BAD, '
            + 'Comparison.STRICT, Comparison.STRICT);',
        to: '        new Rule("chunks:world:*", Direction.HIGH_IS_BAD, '
            + 'Comparison.INCLUSIVE, Comparison.STRICT);',
        desc: '逐世界区块的 critical 档从 `>` 改成 `>=` —— 恰好加载 15000 区块的世界开始报 critical，'
            + '而 15000 正是默认阈值本身，等于把告警线悄悄下调一格',
        expect: 'ThresholdOperatorBehaviorTest',
    },
    {
        id: 'M56',
        file: 'src/main/java/cn/perfscope/metrics/ThresholdOperator.java',
        from: '        new Rule("entities:world:*", Direction.HIGH_IS_BAD, '
            + 'Comparison.STRICT, Comparison.STRICT);',
        to: '        new Rule("entities:world:*", Direction.HIGH_IS_BAD, '
            + 'Comparison.INCLUSIVE, Comparison.STRICT);',
        desc: '逐世界实体的 critical 档从 `>` 改成 `>=` —— 配置了 worlds 覆盖的世界在边界值上'
            + '开始告警；同一张表里 entities:global 仍是严格，两族口径就此分叉',
        expect: 'ThresholdOperatorBehaviorTest',
    },
    {
        id: 'M57',
        file: 'src/main/java/cn/perfscope/metrics/ThresholdOperator.java',
        from: '        new Rule("entities:global", Direction.HIGH_IS_BAD, '
            + 'Comparison.STRICT, Comparison.STRICT);',
        to: '        new Rule("entities:global", Direction.HIGH_IS_BAD, '
            + 'Comparison.STRICT, Comparison.INCLUSIVE);',
        desc: '全服实体的 warning 档从 `>` 改成 `>=`（critical 保持严格）—— 恰好 20000 的全服总数'
            + '开始报 warning。这是「只挪半格」的变异，两档共用字段时无法表达、也就无法杀死',
        expect: 'ThresholdOperatorBehaviorTest',
    },
    {
        id: 'M58',
        file: 'src/main/java/cn/perfscope/metrics/ThresholdOperator.java',
        from: '        new Rule("players:global", Direction.HIGH_IS_BAD, '
            + 'Comparison.INCLUSIVE, Comparison.INCLUSIVE);',
        to: '        new Rule("players:global", Direction.HIGH_IS_BAD, '
            + 'Comparison.STRICT, Comparison.INCLUSIVE);',
        desc: 'players 的 critical 档从闭区间 `>=` 改成 `>` —— 恰好 98% 满员从 critical 掉回 warning，'
            + '而这正是「满了」的典型读数；同时也是百分比族与计数族口径是否真的分开的证据',
        expect: 'ThresholdOperatorBehaviorTest',
    },
    // ---------------------------------------------------------------------------------
    // 告警文案的比较符（ThresholdTextMatchesOperatorTest）：符号此前硬编码在
    // AlertMessages 的模板里，是算子表的第五份副本 —— `players` 判定是闭区间 `>=`，
    // 文案却写 `>`，读数恰好 90%（18/20 人，最常见的满员配置）时告警已经触发而文案说"还没到线"。
    // 下面两条：一条改算子（文案必须跟着变），一条把硬编码符号塞回模板（必须被抓住）。
    // ---------------------------------------------------------------------------------
    {
        id: 'M59',
        file: 'src/main/java/cn/perfscope/metrics/ThresholdOperator.java',
        from: '                case INCLUSIVE -> lowIsBad ? "≤" : "≥";',
        to: '                case INCLUSIVE -> lowIsBad ? "≤" : ">";',
        desc: '闭区间的字形从 `>=`（≥）退回成 `>` —— 判定仍是闭区间，文案却开始说谎。'
            + '这不是"改坏了编译器会拦住"的那一类：算子表本身没动，'
            + '只有服主读到的那串字变成了错的事实（此前 AlertMessages 里的真实形状）',
        expect: 'ThresholdTextMatchesOperatorTest',
    },
    {
        id: 'M60',
        file: 'src/main/java/cn/perfscope/alert/AlertMessages.java',
        from: '        put(en, zh, "alert.threshold.players",\n'
            + '            "warning {2} {0} / critical {3} {1}", '
            + '"warning {2} {0} / critical {3} {1}");',
        to: '        put(en, zh, "alert.threshold.players",\n'
            + '            "warning > {0} / critical > {1}", "warning > {0} / critical > {1}");',
        desc: '把硬编码的 `>` 塞回 players 的告警模板（第五份副本回潮）—— 占位符 {2}/{3} 消失后，'
            + '模板不再跟随算子表，改 PLAYERS 的闭合性时文案不动。'
            + '这正是本次修复前的形状，且当时全量 749 个用例零失败',
        expect: 'ThresholdTextMatchesOperatorTest',
    },
    {
        id: 'M61',
        file: 'src/main/java/cn/perfscope/metrics/ThresholdOperator.java',
        from: '        new Rule("players:global", Direction.HIGH_IS_BAD, '
            + 'Comparison.INCLUSIVE, Comparison.INCLUSIVE);',
        to: '        new Rule("players:global", Direction.HIGH_IS_BAD, '
            + 'Comparison.INCLUSIVE, Comparison.STRICT);',
        desc: 'players 的 warning 档从闭区间 `>=` 改成 `>`（critical 保持闭区间）——'
            + '恰好 90%（18/20 人，最常见的满员配置）从 warning 掉回健康。'
            + '与 M58 改的是不同档：这条由文案测试而非行为测试杀死，'
            + '证明「改算子 → 告警文案自动跟随」不是口号而是有测试看着的',
        expect: 'ThresholdTextMatchesOperatorTest',
    },
    // ---------------------------------------------------------------------------------
    // 堆使用率的百分比取整（HeapPercentFloorTest）：从 long 整数运算退回浮点截断，
    // 或"顺手统一"成四舍五入。两者都是线上不报错、只在特定堆大小上少一格/多一格的改动。
    // ---------------------------------------------------------------------------------
    {
        id: 'M62',
        file: 'src/main/java/cn/perfscope/metrics/MetricsCollector.java',
        from: '        return (int) (100L * used / max);',
        to: '        return (int) ((double) used / max * 100);',
        desc: '堆使用率从 long 整数除法退回浮点截断 —— 精确的整数百分比会先算成"少一点"再被 (int) 砍掉一格：'
            + 'max=300000000, used=87000000 精确 29%，浮点比值 0.28999999999999998 乘 100 截断成 28。'
            + '逐 MiB 扫描 256MiB..64GiB 共 7833 次少 1（只在 p=29/57/58），而 2^n 堆与默认 85/95 阈值免疫，'
            + '所以本地开发机与默认配置都复现不出来',
        expect: 'HeapPercentFloorTest',
    },
    {
        id: 'M63',
        file: 'src/main/java/cn/perfscope/command/PerfScopeCommand.java',
        from: '        Integer entityTotal = worldOrTotal(world != null ? world.getName() : null,\n'
            + '            snap.entitiesByWorld, snap.totalEntities);',
        to: '        Integer entityTotal = snap.totalEntities;',
        desc: '`/perfscope entities <world>` 的总计行退回全服值 —— 标题写着「实体统计 - 某世界」，'
            + '第一行数字却是所有世界之和（同一屏再往下的逐世界行又是该世界自己的数字）。'
            + '这正是 CHANGELOG 1.3.0 声称修好、实际只改到告警段的那个形状：'
            + '文档说改完了、代码里没改，分叉了两个大版本',
        expect: 'WorldViewTotalTest',
    },
    // ---------------------------------------------------------------------------------
    // 报告层的数据新鲜度标记（ReportStaleMarkerTest）：`MetricsCollector#isStale()` 的
    // 注释里写着"报告层 health 段应增加 stale 标记"，是本项目唯一自己承认的欠账。
    // 下面的变异把报告层对 isStale() 的调用掐断，三种格式的标记一起消失。
    // ---------------------------------------------------------------------------------
    {
        id: 'M68',
        file: 'src/main/java/cn/perfscope/report/ReportGenerator.java',
        from: '        return new Staleness(collector.isStale(), collector.staleForMs(),',
        to: '        return new Staleness(false, collector.staleForMs(),',
        desc: '报告层的 isStale() 调用改成恒 false —— 三个格式的 stale 标记（HTML 横幅 / '
            + 'TXT [WARNING] 行 / JSON stale.isStale）一起消失，报告退回"生成于 当前时刻、卡片全绿"：'
            + '采集被取消 10 分钟后自动导出（report.auto_export_interval: 30）得到一份标题写着'
            + '现在、数字全冻结的 HTML，与"服务器很健康"逐字节不可区分。'
            + 'JSON 一侧尤其致命——巡检脚本据 isStale 区分"指标冻住了"与"服务端正常"，'
            + '掐断后两者又变成同一个形状',
        expect: 'ReportStaleMarkerTest',
    },
    // ---------------------------------------------------------------------------------
    // 单世界视图的总计行（WorldViewTotalTest）：`/perfscope entities <world>` 的标题早就切成了
    // 「实体统计 - 某世界」，第一行数字却取 snap.totalEntities（全服值）。上面 M63 改接线，
    // 这条改 worldOrTotal 自身的语义 —— 两层都必须是 KILLED 才算真的锁住。
    // ---------------------------------------------------------------------------------
    {
        id: 'M69',
        file: 'src/main/java/cn/perfscope/command/PerfScopeCommand.java',
        from: '        return byWorld == null ? null : byWorld.get(worldName);',
        to: '        return serverTotal;',
        desc: 'worldOrTotal 的世界分支直接返回全服总数 —— 调用点仍把世界名传了进来，'
            + '提取函数却把它丢掉，等价于「world 参数完全不影响总计行」。'
            + '与 M63 改的是不同层：M63 改接线（由源码断言抓住），这条改语义'
            + '（由多世界纯函数用例抓住）',
        expect: 'WorldViewTotalTest',
    },
];

// ---------------------------------------------------------------------------

const args = process.argv.slice(2);
const only = args.includes('--only') ? args[args.indexOf('--only') + 1] : null;
const listOnly = args.includes('--list');

function run(cmd, opts = {}) {
    return execSync(cmd, { cwd: ROOT, encoding: 'utf8', stdio: 'pipe', ...opts });
}

function gitClean() {
    return run('git status --porcelain').trim() === '';
}

function restore(file) {
    run(`git checkout -- "${file}"`);
    if (run(`git diff --quiet -- "${file}" && echo CLEAN || echo DIRTY`).trim() !== 'CLEAN') {
        throw new Error(`无法还原 ${file}，请手动 git checkout`);
    }
}

/** 跑指定测试类；返回 {ok, summary}。用 spawnSync 以免退出码非零时抛异常。 */
function runTests(classes) {
    const r = spawnSync(
        process.platform === 'win32' ? 'D:/maven/apache-maven-3.9.9/bin/mvn.cmd' : 'mvn',
        ['-q', 'test', `-Dtest=${classes}`, '-DfailIfNoTests=false'],
        { cwd: ROOT, encoding: 'utf8', shell: true },
    );
    const out = `${r.stdout || ''}\n${r.stderr || ''}`;
    const m = out.match(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/);
    return {
        ok: r.status === 0,
        summary: m ? `${m[1]} 跑 / ${m[2]} 失败 / ${m[3]} 错误` : '（未找到汇总行，可能编译失败）',
        detail: out,
    };
}

function main() {
    if (!gitClean()) {
        console.error('工作树不干净，拒绝运行。');
        console.error('变异验证会 git checkout 还原文件——未提交的改动会被一起抹掉。');
        console.error('请先提交或 stash。');
        process.exit(2);
    }

    const targets = only ? MUTATIONS.filter(m => m.id === only) : MUTATIONS;
    if (targets.length === 0) {
        console.error(`没有编号为 ${only} 的变异`);
        process.exit(2);
    }
    if (listOnly) {
        for (const m of targets) {
            console.log(`${m.id}  ${m.expect}`);
            console.log(`     ${m.desc}`);
        }
        return;
    }

    const results = [];
    for (const m of targets) {
        process.stdout.write(`${m.id}  ${m.desc.split('——')[0].trim()} ... `);
        const abs = path.join(ROOT, m.file);
        const original = fs.readFileSync(abs, 'utf8');
        if (!original.includes(m.from)) {
            console.log('SKIP（原文未找到，变异表已过期）');
            results.push({ ...m, verdict: 'SKIP', skipReason: 'from 字面量在文件中已不存在' });
            continue;
        }
        try {
            fs.writeFileSync(abs, original.replace(m.from, m.to), 'utf8');
            // expect: null + gate: '...' 的形态用于"单测够不着、由源码门禁守"的改动。
            // 判据同样只有一条：门禁脚本的退出码非零 = 门禁有效 = KILLED。
            let r;
            let label;
            if (m.expect) {
                r = runTests(m.expect);
                label = m.expect;
            } else if (m.gate) {
                const g = spawnSync(process.execPath, [path.join(ROOT, m.gate)],
                    { cwd: ROOT, encoding: 'utf8' });
                r = { ok: g.status === 0, summary: `门禁退出码 ${g.status}` };
                label = path.basename(m.gate);
            } else {
                console.log('SKIP（既无 expect 也无 gate，变异表写错）');
                results.push({ ...m, verdict: 'SKIP', skipReason: '既无 expect 也无 gate' });
                continue;
            }
            // 红了 = 测试/门禁有效 = KILLED；绿了 = 锁不住 = SURVIVED
            const verdict = r.ok ? 'SURVIVED' : 'KILLED';
            console.log(`${verdict}  (${label}: ${r.summary})`);
            if (verdict === 'SURVIVED') {
                console.log(`     ↑ ${m.expect ? '该测试类没有发现' : '该门禁没有发现'}这个改动，需要补断言`);
            }
            results.push({ ...m, verdict, summary: r.summary });
        } finally {
            fs.writeFileSync(abs, original, 'utf8');
            restore(m.file);
        }
    }

    console.log('\n────────────────────────────────');
    const killed = results.filter(r => r.verdict === 'KILLED').length;
    const survived = results.filter(r => r.verdict === 'SURVIVED').length;
    const skipped = results.filter(r => r.verdict === 'SKIP').length;
    console.log(`KILLED ${killed} / SURVIVED ${survived} / SKIP ${skipped}  (共 ${results.length})`);
    for (const r of results.filter(r => r.verdict !== 'KILLED')) {
        const why = r.verdict === 'SKIP' ? `  ← ${r.skipReason || '跳过'}` : '';
        console.log(`  ${r.verdict}  ${r.id}  ${r.desc}${why}`);
    }
    if (skipped > 0) {
        // SKIP 不是"没关系"，是"这条变异根本没有被验证"——覆盖静默变少的正是它。
        console.log(`\n⚠ ${skipped} 条变异被跳过：它们没有被验证过，覆盖比看上去少。`);
        console.log('   SKIP 计入失败（退出码非零）：「原文未找到」说明变异表已过期，请更新 from；');
        console.log('   「既无 expect 也无 gate」说明变异表写错，请补上判据。');
    }
    process.exit(survived > 0 || skipped > 0 ? 1 : 0);
}

main();
