#!/usr/bin/env node
/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * check-velocity-thresholds.mjs -- L2 gate: 代理侧的分级阈值必须与后端算子表同口径。
 *
 * <h2>为什么需要这条门禁（它拦的是已经发生过两次的同一件事）</h2>
 *
 * 同一台后端在 `/perfscope` 与 `/pscope network` 上被染成不同颜色，是最伤信任的一类不一致：
 * 服主会先怀疑某一个插件坏了。而这两个面板的阈值**物理上不可能共享代码**——代理是 Velocity
 * 插件，后端是 Paper 插件，两个仓库、两个 JVM。所以它们只能靠"两处都写对"来保持一致，
 * 而这正是因为缺少门禁而反复失败的东西：
 *
 *   - fix3：代理从开区间（`tps < 15`）改成闭区间（`tps <= 15`），理由是"后端 ThresholdColors
 *     用闭区间"。对齐动作对，参照物错——那份后端闭区间本身是与采集层分裂的缺陷。
 *   - fix4：后端把展示层改成从 `ThresholdOperator` 派生（两处同时变严格），
 *     代理侧那份闭区间**立刻反转成新的分裂源**：恰好 15.00 后端黄、代理红。
 *
 * 两次都是"对齐了一个参照物"，两次参照物都选错了，而两次都没有任何测试或门禁会发现——
 * 两侧各自的用例都只断言"我这里是多少"，没有人同时看两边。
 *
 * <h2>判定口径</h2>
 *
 * 真值是后端 `src/main/java/cn/perfscope/metrics/ThresholdOperator.java` 里那几行
 * `new Rule(...)`（`tps:global` / `mspt:global`），它对每个指标给出 `Direction` 与
 * `Comparison`。本脚本：
 *
 *   1. 解析后端算子表，取出 `tps` / `mspt` 两条规则的 Direction 与**两档** Comparison
 *      （`new Rule(key, dir, critical, warning)` 的后两个参数）；
 *   2. 解析代理 `NetworkRenderer.java` 的 `tpsCell` / `msptCell` 里**每一档实际写的比较符**
 *      （`<` / `<=` / `>` / `>=`），而不是解析常量值——常量值只决定阈值多少，
 *      比较符决定边界那一格归谁；
 *   3. 两档必须**逐档**匹配：Direction=LOW_IS_BAD + STRICT ⇒ 该档必须用 `<`。
 *
 * <h2>为什么必须逐档（这条门禁曾经只看得见一半）</h2>
 *
 * 旧实现取方法体里**第一个** `(<=|>=|<|>) (TPS|MSPT)_(CRITICAL|WARN)` 就收工。cell 的写法是
 * `if (v < CONST_CRITICAL) … else if (v < CONST_WARN) …`，第一次出现的永远是 CRITICAL 档，
 * 于是 WARN 档的任何改动**永久放行**：实测把 `tps < TPS_WARN` 改成 `<=`、把
 * `mspt > MSPT_WARN` 改成 `>=`，门禁都是 PASS。而 WARN 恰好是"黄灯"那一档——
 * 服主最常看到的颜色正是在这里偏移。所以现在两档都取、都判；某档找不到比较也算失败
 * （那意味着这一档被人删掉了，边界从此落进 else，被判成 OK）。
 *
 * 刻意**不**比较阈值数值：后端阈值可配置，代理侧硬编码的是"随包默认值"，
 * 服主改了后端配置本来就会不一致（README 已写明要手动同步）。数值漂移是已知的、
 * 有文档的；闭合性漂移是无声的、破坏"颜色变了就等于告警已触发"这个承诺的。
 *
 * 用法：
 *   node tools/check-velocity-thresholds.mjs
 *   node tools/check-velocity-thresholds.mjs --repo . --velocity ../PerfScope-Velocity
 *   node tools/check-velocity-thresholds.mjs --json
 *
 * 退出码：0 = PASS，1 = 不一致或解析失败，2 = 用法/路径错误
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : dflt;
};
const REPO = path.resolve(argOf('--repo', path.join(HERE, '..')));
// 代理仓库与本仓库是兄弟目录（<plugin parent>/PerfScope 与 <plugin parent>/PerfScope-Velocity）。
// 只写一个默认值：找不到时下面的 REPORT 会明确说"没找到"，而不是静默 PASS。
const VELOCITY = path.resolve(argOf('--velocity', path.join(REPO, '..', 'PerfScope-Velocity')));
const AS_JSON = argv.includes('--json');

const OPERATOR = path.join(REPO, 'src', 'main', 'java', 'cn', 'perfscope', 'metrics', 'ThresholdOperator.java');
const RENDERER = path.join(VELOCITY, 'src', 'main', 'java', 'cn', 'perfscope', 'velocity', 'NetworkRenderer.java');

const problems = [];
const rows = [];

/**
 * 注释剥离：`//` 与 `/* *\/` 里的字面量不是代码（javadoc 会引述旧算子，不剥会假红）。
 *
 * <p>与 `check-sentinels.mjs` 的同名函数有一处**刻意的不同**：字符串内容原样保留。
 * 那边要抹字符串是因为它找的是"字段与 0 的比较"，字符串里的 `"x <= 0"` 是噪音；
 * 这里要读的恰恰是字符串——算子表的 `"tps:global"` key 就在引号里。抹掉之后规则一条都匹配不上，
 * 而失败信息会变成"锚点过期"这种指向错误方向的话。</p>
 */
function stripComments(src) {
  let out = '';
  let i = 0;
  let state = 'code';
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (state === 'code') {
      if (c === '/' && n === '/') { state = 'line'; out += '  '; i += 2; continue; }
      if (c === '/' && n === '*') { state = 'block'; out += '  '; i += 2; continue; }
      if (c === '"') { state = 'string'; out += c; i++; continue; }
      out += c; i++; continue;
    }
    if (state === 'line') {
      if (c === '\n') { state = 'code'; out += '\n'; i++; continue; }
      out += ' '; i++; continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') { state = 'code'; out += '  '; i += 2; continue; }
      out += (c === '\n') ? '\n' : ' '; i++; continue;
    }
    if (state === 'string') {
      if (c === '\\') { out += src.slice(i, i + 2); i += 2; continue; }
      if (c === '"') { state = 'code'; out += c; i++; continue; }
      out += (c === '\n') ? '\n' : c; i++; continue;
    }
  }
  return out;
}

/** Direction × Comparison → 该方向下"越界"应有的符号。与 Comparison#symbolFor 同一张表。 */
function expectedSymbol(direction, comparison) {
  const lowIsBad = direction === 'LOW_IS_BAD';
  if (comparison === 'STRICT') return lowIsBad ? '<' : '>';
  return lowIsBad ? '≤' : '≥';
}

/**
 * 从算子表里取一条规则的 {direction, critical, warning}。找不到就记问题。
 *
 * 两档都返回，不再只返回 critical —— 见文件头「为什么必须逐档」。两档闭合性不同
 * （ThresholdOperatorBehaviorTest 专门测这种能力）也不再报"人工决定"：代理 cell 本身就有
 * CRITICAL / WARN 两级，逐档比对已经能完整表达这种分叉。
 */
function readRule(src, key) {
  // new Rule("tps:global", Direction.LOW_IS_BAD, Comparison.STRICT, Comparison.STRICT);
  const re = new RegExp(
    `new\\s+Rule\\(\\s*"${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"\\s*,\\s*`
    + `Direction\\.(\\w+)\\s*,\\s*Comparison\\.(\\w+)\\s*,\\s*Comparison\\.(\\w+)\\s*\\)`);
  const m = src.match(re);
  if (!m) {
    problems.push(`后端算子表里找不到 ${key} 规则（正则未命中）——本门禁的锚点过期了，请同步更新它`);
    return null;
  }
  const [, direction, critical, warning] = m;
  return { direction, critical, warning };
}

/**
 * 取 {@code static Cell xxxCell(...) { ... }} 的**完整**方法体。
 *
 * 旧实现是 {@code src.slice(at, at + 1200)} —— 一个"恰好够长"的字符窗口。它之所以
 * 看起来能用，是因为第一次比较总落在窗口内；WARN 档那次比较是否在窗口内，
 * 取决于中间隔了多少注释。改成按花括号配平取到方法真正结束，窗口长度不再影响结论。
 */
function methodBody(src, methodName) {
  const at = src.indexOf(`static Cell ${methodName}(`);
  if (at < 0) {
    problems.push(`代理侧 NetworkRenderer 里找不到 ${methodName}（锚点过期）`);
    return null;
  }
  const open = src.indexOf('{', src.indexOf(')', at));
  if (open < 0) {
    problems.push(`代理侧 ${methodName} 的方法体大括号找不到（锚点过期）`);
    return null;
  }
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(open, i + 1);
    }
  }
  problems.push(`代理侧 ${methodName} 的方法体花括号不配平——无法静态分析`);
  return null;
}

/**
 * 从 cell 方法体里取出**每一档**实际用的比较符：{ critical, warning }。
 *
 * <p>只认 `${metric}_CRITICAL` / `${metric}_WARN` 这两个常量旁边的那一个符号。
 * `<=` / `>=` 必须先于 `<` / `>` 匹配，否则两字符的会被读成单字符。</p>
 *
 * <p>某档找不到就记问题并返回 null——这一档被删掉时，那个边界会落进最后的 else
 * 被涂成 OK，正是这条门禁最该抓的形态，不能静默跳过。</p>
 */
function readCellSymbols(src, methodName, metric) {
  const body = methodBody(src, methodName);
  if (body === null) return null;

  const pick = (tier) => {
    const re = new RegExp(`(<=|>=|<|>)\\s*${metric}_${tier}\\b`);
    const m = body.match(re);
    return m ? m[1] : null;
  };

  const critical = pick('CRITICAL');
  const warning = pick('WARN');
  if (critical === null || warning === null) {
    const missing = critical === null ? 'CRITICAL' : 'WARN';
    problems.push(`代理侧 ${methodName} 里找不到 ${metric}_${missing} 的比较（该档可能被删了/锚点过期）。`
      + '缺档意味着那个边界不再变色，会直接落进 else 被判成 OK');
    return null;
  }
  return { critical, warning };
}

// ---------------------------------------------------------------------------

if (!fs.existsSync(OPERATOR)) {
  console.error(`后端算子表不存在：${OPERATOR}`);
  process.exit(2);
}
if (!fs.existsSync(VELOCITY)) {
  if (AS_JSON) {
    console.log(JSON.stringify({ ok: false, reason: 'velocity-repo-not-found', path: VELOCITY }));
  } else {
    console.error(`找不到代理仓库：${VELOCITY}`);
    console.error('这不是"通过"——两侧口径无法核对。若仓库不在兄弟目录，用 --velocity <path> 指定。');
  }
  process.exit(2);
}
if (!fs.existsSync(RENDERER)) {
  console.error(`找不到代理渲染层：${RENDERER}`);
  process.exit(2);
}

const operatorSrc = stripComments(fs.readFileSync(OPERATOR, 'utf8'));
const rendererSrc = stripComments(fs.readFileSync(RENDERER, 'utf8'));

const CASES = [
  { ruleKey: 'tps:global', cell: 'tpsCell', metric: 'TPS', label: 'TPS' },
  { ruleKey: 'mspt:global', cell: 'msptCell', metric: 'MSPT', label: 'MSPT' },
];

for (const { ruleKey, cell, metric, label } of CASES) {
  const rule = readRule(operatorSrc, ruleKey);
  const symbols = readCellSymbols(rendererSrc, cell, metric);
  if (!rule || !symbols) continue;
  for (const tier of ['critical', 'warning']) {
    const expected = expectedSymbol(rule.direction, rule[tier]);
    const actual = symbols[tier];
    const okay = actual === expected;
    rows.push({
      label, ruleKey, tier, direction: rule.direction,
      comparison: rule[tier], expected, actual, okay,
    });
    if (!okay) {
      problems.push(
        `${label}（${tier.toUpperCase()} 档）：后端 ${ruleKey} 是 ${rule.direction} + ${rule[tier]}`
        + `（越界判据应为 \`${expected}\`），代理侧 ${cell} 用的是 \`${actual}\`。`
        + `同一个读数因此会在两个面板上颜色不同——例如恰好等于阈值的那个点，一边黄一边红。`
        + `改代理侧的 ${cell}，或（若确实要改后端口径）同时更新代理与 README 的「阈值口径」段`);
    }
  }
}

const okay = problems.length === 0;

if (AS_JSON) {
  console.log(JSON.stringify({ ok: okay, rows, problems }, null, 2));
} else {
  console.log('PerfScope proxy-threshold consistency check');
  console.log(`  backend  : ${OPERATOR}`);
  console.log(`  proxy    : ${RENDERER}`);
  console.log('');
  for (const r of rows) {
    console.log(`  ${r.okay ? 'OK  ' : 'FAIL'}  ${r.label.padEnd(5)} ${r.tier.padEnd(9)} `
      + `backend ${r.direction}/${r.comparison} → expects '${r.expected}', proxy uses '${r.actual}'`);
  }
  if (!okay) {
    console.log('');
    for (const p of problems) console.log(`  [ERROR] ${p}`);
  }
  console.log('');
  console.log(okay ? 'PROXY THRESHOLD CHECK: PASS' : 'PROXY THRESHOLD CHECK: FAIL');
}

process.exit(okay ? 0 : 1);
