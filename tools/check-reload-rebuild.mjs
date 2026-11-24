#!/usr/bin/env node
/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * {@code PerfScope.reload()} 的「加载失败也要重建」结构门禁。
 *
 * <h2>锁的是什么</h2>
 * {@code reload()} 的顺序是：停四个任务 → {@code configManager.load()}（在 try 里）→
 * {@code Messages.load} → <b>无条件</b>重建四个任务 → 两个清理 → 按 {@code loadFailed}
 * 决定打哪条日志。
 *
 * <p>关键在"无条件"三个字上。{@code loadFailed} 只影响<b>最后打哪条消息</b>，
 * 绝不影响中间的重建。这条语义写在方法 javadoc 里（"无论 load 成败，任务必须重建：
 * 绝不允许『采集停了不重建』的半途冻结态"），但它<b>结构上没有任何东西守着</b>——
 * 一个看起来无害的重构就能把它改坏：
 *
 * <pre>{@code
 * // 改坏的样子（看起来很合理："配置都没加载成功，重建成干什么？"）
 * if (!loadFailed) {
 *     startCollectionTasks();
 *     startAlertService();
 *     startDailySummary();
 * }
 * }</pre>
 *
 * <p>那个改动的后果：配置写坏一次（语法错、权限位不对、磁盘满），{@code reload} 会
 * <b>停掉全部任务然后不重建</b>——采集停摆、告警不再推送、每日摘要不发，而控制台只多一条
 * "配置加载失败，继续用旧配置"的 warning，读起来像是"一切照旧"。旧配置还在内存里，
 * 却没有任何人在用它采集。这就是那条 javadoc 里说的"半途冻结态"。
 *
 * <h2>为什么不用单测</h2>
 * {@code reload()} 的每一步都要真的模块实例：{@code configManager.load()} 要 {@code dataFolder}
 * 与 YAML 资源流，{@code reportGenerator.cleanOldReports()} 要写盘，{@code startPrometheus()}
 * 会真的绑定端口。而 {@code reload()} 里 {@code configManager.get().report_autoExport}
 * 与 {@code reportGenerator.cleanOldReports()} 都没有判空守卫（其余 start/stop 有），
 * 所以要跑起来必须把六七个真实对象全装出来——成本远高于这条断言的价值。
 *
 * <p>而它要守的东西是<b>纯结构</b>的：四个 start 调用与 {@code loadFailed} 之间没有控制流依赖。
 * 这在源码上是可判定的，所以用门禁而不是单测。
 *
 * <p>与 {@code check-bstats-buckets.mjs} / {@code check-sentinels.mjs} 同一范式：
 * <b>当行为只能在特定运行环境里显现时，把约束上升到源码层。</b>
 *
 * <h2>为什么必须先剥注释（这条门禁曾经可以被一行注释骗过去）</h2>
 *
 * 旧实现是 {@code bodyText.indexOf("startDailySummary()")} —— 纯文本搜索。于是
 * {@code // startDailySummary();} 与 {@code /* startDailySummary(); *\/} 都算"调用还在"，
 * 而它们在行为上与删除完全等价：任务从此不再重建，方法体里却仍留着那个名字。
 * 实测：把真调用改成注释，门禁 PASS。一个只会让门禁闭嘴的"删除"，正是这类门禁最该红的形态。
 *
 * <p>所以先把注释换成等长空白再搜索。<b>但不能只剥注释</b>：{@code getLogger().info("startDailySummary")}
 * 这种日志里的同名文本不是调用，算"存在"同样是假绿。因此字符串/字符字面量的内容也一并抹成空白
 * （引号保留、长度保留、换行保留），于是"代码里真的有这个调用"才唯一成为命中条件。
 * 两个字面量形态都要处理：普通 {@code "..."}（含 {@code \"} 转义）与 Java 15 的
 * {@code """} 文本块——后者里的换行必须保留，否则行号会整体错位。
 *
 * <p>剥完的文本同时用于结构判断（定位方法体、花括号配平、找外层 if）：
 * 注释里的花括号与 {@code if (} 同样会污染结构分析。
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
/** 读 --repo <path>。缺省回退到本仓库根（脚本所在的上一级）。 */
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : dflt;
};
const REPO = path.resolve(argOf('--repo', path.join(HERE, '..')));
const TARGET = path.join(REPO, 'src', 'main', 'java', 'cn', 'perfscope', 'PerfScope.java');

/**
 * 必须在 {@code load()} 之后无条件执行的调用。
 *
 * 每条：方法名 + 为什么它不能被 loadFailed 挡住。
 */
const UNCONDITIONAL_CALLS = [
    {
        call: 'startCollectionTasks()',
        why: '采集停摆 = 插件只剩一张冻结的历史快照，而 getLatest() 仍返回它，'
            + '命令层照常渲染"正常"——服主看不出问题',
    },
    {
        call: 'startAlertService()',
        why: '告警不再推送；而告警恰恰是"服务器出事了"的唯一主动通知渠道',
    },
    {
        call: 'startDailySummary()',
        why: '每日摘要静默不发，且没有任何地方会补发',
    },
];

const errors = [];
const ok = [];

/**
 * 把注释与字面量内容替换成等长空白，保留换行与引号。
 *
 * <p>等长是刻意的：替换后的每个字符都还在原位，所以后续的行号、行内偏移与原文一致，
 * 报错能指到人真正要改的那一行。换行保留是必需的——把整段注释压成空格会让行号错位。
 * 字符串内容抹掉是为了不把日志里的同名文本当成调用（见文件头）。</p>
 *
 * 状态机：code / line-comment / block-comment / string / char / text-block。
 * 不是 Java 词法器，只求"别被注释和字符串骗"；解析不了的形态（比如非法源码）会退化成
 * code 状态，方向上只会漏报，不会误报。
 */
function maskCommentsAndLiterals(src) {
    const out = src.split('');
    let i = 0;
    const n = src.length;
    // 把 [from, to) 之间除换行外的字符抹成空格
    const blank = (from, to) => {
        for (let k = from; k < to && k < n; k++) {
            if (out[k] !== '\n') out[k] = ' ';
        }
    };
    while (i < n) {
        const c = src[i];
        const d = src[i + 1];
        const e = src[i + 2];

        if (c === '/' && d === '/') {
            let j = i;
            while (j < n && src[j] !== '\n') j++;
            blank(i, j);
            i = j;
            continue;
        }
        if (c === '/' && d === '*') {
            let j = i + 2;
            while (j < n && !(src[j] === '*' && src[j + 1] === '/')) j++;
            blank(i, Math.min(j + 2, n));
            i = Math.min(j + 2, n);
            continue;
        }
        if (c === '"' && d === '"' && e === '"') {
            // Java 15 文本块：到下一个 """ 为止，内容抹空白但保留换行
            let j = i + 3;
            while (j < n && !(src[j] === '"' && src[j + 1] === '"' && src[j + 2] === '"')) {
                if (src[j] === '\\') j += 2;
                else j++;
            }
            blank(i + 3, j);
            i = Math.min(j + 3, n);
            continue;
        }
        if (c === '"' || c === "'") {
            const quote = c;
            let j = i + 1;
            while (j < n) {
                if (src[j] === '\\') { j += 2; continue; }
                if (src[j] === quote) { j++; break; }
                if (src[j] === '\n') break;   // 未闭合：别把整个文件吃掉
                j++;
            }
            blank(i + 1, j - 1);
            i = j;
            continue;
        }
        i++;
    }
    return out.join('');
}

function main() {
    if (!fs.existsSync(TARGET)) {
        console.error(`source not found: ${TARGET}`);
        process.exit(2);
    }
    const source = fs.readFileSync(TARGET, 'utf8');
    // 先抹掉注释与字面量内容：之后所有搜索/结构判断都在这份文本上做。
    // 抹的是等长空白，所以行号与列号与原文一一对应。
    const code = maskCommentsAndLiterals(source);
    const lines = code.split('\n');
    const rel = path.relative(REPO, TARGET).replace(/\\/g, '/');

    // 1. 定位 reload() 方法体
    const startLine = lines.findIndex((l) => /public void reload\s*\(\s*\)\s*\{/.test(l));
    if (startLine < 0) {
        console.error('找不到 public void reload() 的定义——方法被改名/删除了？');
        console.error('若是有意重构，同步更新本脚本。');
        process.exit(2);
    }
    // 用花括号配平找方法体结束
    let depth = 0;
    let endLine = -1;
    for (let i = startLine; i < lines.length; i++) {
        for (const ch of lines[i]) {
            if (ch === '{') depth++;
            else if (ch === '}') depth--;
        }
        if (depth === 0 && i > startLine) {
            endLine = i;
            break;
        }
    }
    if (endLine < 0) {
        console.error('reload() 方法体花括号不配平——无法静态分析。');
        process.exit(2);
    }

    const body = lines.slice(startLine, endLine + 1);
    const bodyText = body.join('\n');

    // 2. loadFailed 的置位点与读取点必须"隔着"全部重建调用
    const loadCallLine = body.findIndex((l) => /\bconfigManager\.load\s*\(\s*\)/.test(l));
    if (loadCallLine < 0) {
        errors.push({
            detail: 'reload() 里找不到 configManager.load() 调用——'
                + '方法结构变了，本脚本的判断前提失效，请同步更新。',
        });
    }

    for (const rule of UNCONDITIONAL_CALLS) {
        const idx = bodyText.indexOf(rule.call);
        if (idx < 0) {
            errors.push({
                call: rule.call,
                detail: `reload() 里找不到 ${rule.call} —— 它被删了？`
                    + '删掉意味着 reload 之后再也不会重建这个任务。',
            });
            continue;
        }
        const callLine = bodyText.slice(0, idx).split('\n').length - 1;

        // 2a. 必须紧跟在 load() 之后（而不是在 else 分支里）
        if (loadCallLine >= 0 && callLine < loadCallLine) {
            errors.push({
                call: rule.call,
                detail: `${rule.call} 出现在 configManager.load() <b>之前</b>——`
                    + '顺序反了会让新配置不生效（任务用旧配置启动后 load 才换新配置）。',
            });
            continue;
        }

        // 2b. 往上找最近的、未闭合的 if 条件，看它是不是 loadFailed / !loadFailed
        const guard = enclosingGuard(body, callLine);
        if (guard && /\bloadFailed\b/.test(guard)) {
            errors.push({
                call: rule.call,
                line: startLine + callLine + 1,
                detail: `${rule.call} 被 "${guard.trim()}" 这个含 loadFailed 的条件包住了。`
                    + `后果：配置写坏一次，reload 会停掉全部任务然后不重建——${rule.why}。`
                    + '这正是方法 javadoc 明令禁止的"半途冻结态"。',
            });
            continue;
        }

        ok.push({ call: rule.call, line: startLine + callLine + 1 });
    }

    // 3. loadFailed 必须仍然只用于"选日志"
    const loadFailedReads = body.filter((l) => /\bloadFailed\b/.test(l));
    for (const l of loadFailedReads) {
        if (/Messages\.get\(/.test(l)) {
            continue;   // 选日志：允许
        }
        if (/boolean loadFailed = false/.test(l) || /loadFailed = true/.test(l)) {
            continue;   // 声明与置位：允许
        }
        // 出现在条件里 = 疑似用来控制重建
        if (/\bif\s*\(/.test(l)) {
            // 最后那条 "if (loadFailed) { 打警告 } else { 打 info }" 是合法的。
            // 区分方法：它后面紧跟的那条语句是 getLogger()。
            const idx = body.indexOf(l);
            const next = body.slice(idx + 1, idx + 3).join('\n');
            if (/getLogger\s*\(/.test(next)) {
                continue;
            }
            errors.push({
                line: startLine + body.indexOf(l) + 1,
                detail: `loadFailed 出现在条件 "${l.trim()}" 里，且后面紧跟的不是日志——`
                    + '它可能被用来控制重建了。loadFailed 只允许影响"打哪条日志"。',
            });
        }
    }

    console.log('PerfScope reload() "rebuild even when load fails" check');
    console.log(`  repo  : ${REPO}`);
    console.log(`  source: ${rel}`);
    console.log(`  reload: line ${startLine + 1}-${endLine + 1} (${endLine - startLine + 1} lines)`);
    console.log('');

    if (errors.length === 0) {
        for (const o of ok) {
            console.log(`  ok  ${o.call.padEnd(24)} line ${o.line} (unconditional)`);
        }
        console.log('');
        console.log('RELOAD REBUILD CHECK: PASS');
        process.exit(0);
    }

    for (const e of errors) {
        console.log(`  FAIL  ${e.call || 'reload()'}${e.line ? ':' + e.line : ''}`);
        console.log(`        ${e.detail}`);
    }
    console.log('');
    console.log(`RELOAD REBUILD CHECK: FAIL (${errors.length} problem(s))`);
    process.exit(1);
}

/**
 * 返回包住 {@code targetLine} 的、仍处于打开状态的 if 条件文本（最近的第一个）。
 *
 * 做法是从方法体开头逐行扫，维护一个"未闭合的 if 条件"栈：遇到 `if (...)` 且该行以 `{` 结尾
 * 就压栈；遇到闭合 `}` 就弹栈。到目标行时栈顶就是最近的外层条件。
 *
 * <p>这不是完整解析器——只处理本文件这种"单行 if + 大括号"的写法。若将来出现
 * 跨行条件或多语句无括号 if，本函数会退化成"找不到守卫"，从而漏报而不是误报——
 * 漏报方向更安全（门禁不阻挡正常改动）。
 */
function enclosingGuard(bodyLines, targetLine) {
    const stack = [];
    let depth = 0;
    for (let i = 0; i < targetLine; i++) {
        const raw = bodyLines[i];
        const isIf = /^\s*if\s*\(/.test(raw);
        const opens = (raw.match(/\{/g) || []).length;
        const closes = (raw.match(/\}/g) || []).length;

        if (isIf && opens > 0) {
            stack.push({ depth: depth + 1, text: raw.trim() });
        }
        depth += opens - closes;
        while (stack.length > 0 && depth < stack[stack.length - 1].depth) {
            stack.pop();
        }
    }
    return stack.length > 0 ? stack[stack.length - 1].text : null;
}

main();
