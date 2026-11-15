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
            console.log('SKIP（原文未找到，代码可能已改动）');
            results.push({ ...m, verdict: 'SKIP' });
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
                console.log('SKIP（既无 expect 也无 gate）');
                results.push({ ...m, verdict: 'SKIP' });
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
        console.log(`  ${r.verdict}  ${r.id}  ${r.desc}`);
    }
    process.exit(survived > 0 ? 1 : 0);
}

main();
