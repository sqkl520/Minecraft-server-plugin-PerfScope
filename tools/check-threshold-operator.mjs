#!/usr/bin/env node
/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * check-threshold-operator.mjs -- L1 gate: 展示层不得自己写「读数 vs 阈值」的比较符。
 *
 * <h2>它拦的是已经发生过两次的同一件事</h2>
 *
 * <p>算子（`>` 还是 `>=`）在本仓库里曾经有<b>五份副本</b>：{@code ThresholdOperator} 的算子表、
 * {@code ThresholdColors}（展示层原本自己写闭区间）、README 的散文、{@code AlertMessages}
 * 的「warning > {0}」模板，以及 {@code /ps entities} 末尾手写的
 * {@code if (alertEntities > criticalThreshold)}。前四份已经收口，第五份是本次修的。</p>
 *
 * <p>第五份之所以能活那么久，是因为它的数值与算子表<b>恰好相同</b>（表里也是 STRICT）。
 * 于是它不产生任何可见差异，任何"看行为"的测试都发现不了；只有等哪天有人改表
 * （例如把 entities 改成闭区间）才会暴露：颜色变了、采集层告警变了，
 * 命令层的提示语却不跟着动——同一屏上一半新口径、一半旧口径。</p>
 *
 * <h2>判定口径（刻意窄，理由在下面）</h2>
 *
 * <p>只在<b>展示层包</b>里找这种形状：{@code <读数> <比较符> <名字里带 threshold 的标识符>}，
 * 即</p>
 *
 * <pre>
 *   alertEntities > criticalThreshold        ← 命中（本次修掉的形状）
 *   snap.totalEntities > cfg.entities_totalCriticalThreshold   ← 命中
 *   v >= thresholdOf(cfg, ...)               ← 命中
 * </pre>
 *
 * <p>命中的地方必须改成调用 {@code ThresholdOperator.Rule#levelOf}（或 {@code SingleTier#trips}），
 * 比较符由表提供。确实需要在本层自己比较的（例如"阈值本身是否越界"这种校验），
 * 在行内或上一行写 {@code @threshold-op-ok <理由>} 豁免。</p>
 *
 * <h2>为什么范围只限这五个包</h2>
 *
 * <p>{@code command/ report/ export/ network/ util/} 是"读同一份 config、给同一个读数上色/报警"
 * 的那几层，它们的判定必须与采集层逐点相同，所以比较符只能有一个出处。
 * 刻意<b>不含</b> {@code diagnose/}、{@code alert/} 与 {@code metrics/}：</p>
 * <ul>
 *   <li>{@code metrics/} 就是算子表的所在地，它当然要比较；</li>
 *   <li>{@code diagnose/} 的规则是<b>另一个问题</b>——"{@code DIAG-TPS-PERSIST} 是否持续低位"、
 *       "{@code DIAG-MEM-LEAK} 要不要看这 30 分钟窗口"，它们的门槛是<b>诊断门槛</b>而不是
 *       告警门槛（{@code heapUsagePercent < memory_heapWarningThreshold} 在这里是"低于这条线
 *       就不必查泄漏"的保密判据）。把它们强行接到算子表上会让两套语义互相污染。</li>
 *   <li>{@code alert/} 里确实<b>还有一处</b>同类形状没有收口：
 *       {@code DailySummaryService.overThresholdMinutes} 的
 *       {@code tps < tps_warningThreshold}（日报的"低于阈值累计时长"）。
 *       本次<b>刻意不动</b>它：它要的是"单档越界"而不是"落在哪一档"，而
 *       {@code Rule#trips} 是 private，写成 {@code levelOf(...) >= 1} 虽然当前等价
 *       （config 校验保证 tps 的 warning ≥ critical），但那是<b>借另一条不变式间接成立</b>的，
 *       等于把两件事悄悄耦上。正确做法是给算子表补一个公开的单档判定入口，
 *       而那属于改动已定稿的表结构，需单独一轮评估。
 *       <b>这一处不在门禁范围内，不是"已收口"而是"已知未收口"</b>——写在这里是为了
 *       下一个人不必重新扫一遍才发现它。</li>
 * </ul>
 * <p>同理不含 {@code config/}：那里比较的是阈值与阈值（合法性校验），不是读数与阈值。</p>
 *
 * <h2>它<b>不</b>能发现什么（说清楚，免得被当成全面保护）</h2>
 *
 * <ul>
 *   <li><b>写死的数值刻度</b>。{@code <span>20.0</span><span>15.0</span>...} 这类硬编码
 *       没有任何标识符可比，任何基于文本的门禁都抓不到——那一处只能靠
 *       {@code ReportTrendAxisTest} 的"改 config 后刻度不动、参考线要动"来锁。</li>
 *   <li><b>名字里不带 threshold 的变量</b>。{@code if (entities >= warn)} 不会命中。
 *       这条限制是有意的：把规则放宽到"任何两个数字比较"会在 {@code report/} 里
 *       撞上几十处百分比/像素/宽度运算，门禁一有噪音就没人看，然后等于没有门禁。</li>
 *   <li><b>倒置写法</b>（{@code criticalThreshold < alertEntities}）。当前代码库没有这种风格；
 *       若将来出现，需要在这里补一条镜像规则。</li>
 * </ul>
 *
 * 用法：
 *   node tools/check-threshold-operator.mjs
 *   node tools/check-threshold-operator.mjs --repo <path>
 *   node tools/check-threshold-operator.mjs --json
 *   node tools/check-threshold-operator.mjs --selftest
 *
 * 退出码：0 = PASS，1 = 发现手写比较，2 = 用法/路径错误
 *
 * <h2>为什么有 --selftest</h2>
 *
 * <p>这条门禁在修完的树上<b>命中 0 处</b>——这正是它该有的状态，但也意味着
 * "PASS" 可能是"模式根本匹配不到任何东西"造成的假绿（本仓库的
 * {@code negative-controls.mjs} 就是为同一类问题而写：否定断言在条件从未成立
 * 与正则永不命中时都通过）。{@code --selftest} 喂给匹配器一组<b>修前形状</b>的样本
 * （必须报）与一组<b>合法形状</b>的样本（必须不报），把这两种情况分开。</p>
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
const AS_JSON = argv.includes('--json');

const SRC = path.join(REPO, 'src', 'main', 'java');

/** 必须与算子表同口径的展示层。理由见文件头「为什么范围只限这五个包」。 */
const DISPLAY_PACKAGES = ['command', 'report', 'export', 'network', 'util'];

/**
 * 相对 {@code src/main/java/} 的路径是否落在展示层。
 *
 * <p>抽成函数是为了让 {@code --selftest} 能单独验它——包范围与形状匹配是两件独立的事，
 * 混在一个断言里会让"命中形状但被包排除"这种正确行为看起来像误报。</p>
 */
function isInDisplayScope(relToSrcRoot) {
  const parts = relToSrcRoot.split('/');
  // cn/perfscope/<pkg>/...
  return parts.length >= 3 && parts[0] === 'cn' && parts[1] === 'perfscope'
    && DISPLAY_PACKAGES.includes(parts[2]);
}

/**
 * 行内豁免：{@code @threshold-op-ok <理由>}，写在该行或上一行。
 *
 * <p>与 {@code check-sentinels.mjs} 的 {@code @sentinel-ok} 同一约定（含"必须写理由"）。
 * 当前<b>一处豁免都没有</b>——空了才是好状态：这条门禁里的豁免只应出现在
 * "确实要比较两个阈值、而不是读数与阈值"的场合，而那类代码本就不属于这五个包。</p>
 */
const EXEMPT = /@threshold-op-ok\b/;

/** `<左> <比较符> <右>` 且右值是名字里带 threshold 的标识符。 */
const CMP_RE = /([A-Za-z_][A-Za-z0-9_.]*(?:\(\s*\))?)\s*(<=|>=|<|>)\s*([A-Za-z_][A-Za-z0-9_.]*(?:\(\s*[^)]*\))?)/g;
const THRESHOLD_NAME_RE = /[Tt]hreshold/;

/**
 * 匹配器本体（与文件扫描共用，`--selftest` 也调它）。
 *
 * @param {string} code 已剥注释/字符串的源码
 * @returns {{code: string, lhs: string, op: string, rhs: string, index: number}[]}
 */
function findViolations(code) {
  const out = [];
  for (const m of code.matchAll(CMP_RE)) {
    const [whole, lhs, op, rhs] = m;
    // 只认"右值是阈值"的形状：左值是阈值时多见"阈值 vs 常量上限"这类校验，
    // 不在本门禁的语义里（见文件头）。
    if (!THRESHOLD_NAME_RE.test(rhs)) continue;
    out.push({ code: whole.trim(), lhs: lhs.trim(), op, rhs: rhs.trim(), index: m.index });
  }
  return out;
}

/**
 * 自检样本：修前形状必须报，合法形状必须不报。
 *
 * <p>每个"必须报"的样本都是本仓库真实出现过的写法（第一条就是本次修掉的
 * {@code /ps entities} 告警段）。每个"必须不报"的样本都是这五个包里真实存在的
 * 邻近写法——它们与违规形状只差一个标识符名，正是最容易误报的地方。</p>
 */
const SELFTEST_MUST_FLAG = [
  { code: 'if (alertEntities > criticalThreshold) {', why: '本次修掉的形状（/ps entities 告警段）' },
  { code: '} else if (alertEntities > warnThreshold) {', why: '同一段的 warning 档' },
  { code: 'if (snap.totalEntities > cfg.entities_totalCriticalThreshold) {', why: '带字段前缀的写法' },
  { code: 'if (value >= thresholdOf(cfgNow(), "x", 1.0)) {', why: '阈值来自取值函数' },
];

const SELFTEST_MUST_NOT_FLAG = [
  { code: 'if (threshold > TPS_AXIS_CEILING) {', why: '左值是阈值、右值是常量：这是阈值校验，不是读数判定' },
  { code: 'if (!(threshold > 0) || threshold > TPS_AXIS_CEILING) {', why: '同上，且带取反' },
  { code: 'if (rawName.length > 16) {', why: '普通比较，右值不带 threshold' },
  { code: 'if (modT >= modW) {', why: '变量名不带 threshold：本门禁刻意不管（见文件头「不能发现什么」）' },
  { code: 'if (history.size() > 1) {', why: '普通比较' },
  { code: 'html.append("<div class=\\"bar\\" style=\\"height:2%\\">");', why: '字符串里的 > 必须被剥掉，否则 HTML 片段会淹掉门禁' },
  { code: '// if (alertEntities > criticalThreshold) {', why: '注释里的引述不是代码' },
  { code: 'if (report_retentionDays <= 0) {', why: '小写带下划线的配置字段（无 threshold 后缀）' },
];

/**
 * 包范围的样本单独验：{@code findViolations} 只做形状匹配，包过滤在外层。
 *
 * <p>分开是因为两者是独立的两件事——把它们混在一个断言里，会让"模式命中但包被排除"
 * 这种正确的复合行为看起来像误报（写这条自检时真的踩到了这个坑：
 * {@code diagnose/} 的样本被形状匹配器命中，于是自检自己红了）。
 * 这里断言的是<b>外层</b>的选择逻辑。</p>
 */
const SELFTEST_PACKAGE_SCOPE = [
  { rel: 'cn/perfscope/command/PerfScopeCommand.java', expected: true, why: '/ps 命令层，必须扫描' },
  { rel: 'cn/perfscope/util/ThresholdColors.java', expected: true, why: '展示层色阶，必须扫描' },
  { rel: 'cn/perfscope/diagnose/CoreRules.java', expected: false, why: '诊断门槛是另一个问题，刻意排除' },
  { rel: 'cn/perfscope/metrics/ThresholdOperator.java', expected: false, why: '算子表本身当然要比较' },
  { rel: 'cn/perfscope/config/ConfigManager.java', expected: false, why: '配置层比的是阈值与阈值' },
];

function runSelfTest() {
  const problems = [];

  for (const sample of SELFTEST_MUST_FLAG) {
    const hits = findViolations(stripCommentsAndStrings(sample.code));
    if (hits.length === 0) {
      problems.push(`must-flag sample was NOT flagged (${sample.why}): ${sample.code}`);
    }
  }
  for (const sample of SELFTEST_MUST_NOT_FLAG) {
    const hits = findViolations(stripCommentsAndStrings(sample.code));
    if (hits.length > 0) {
      problems.push(`must-not-flag sample WAS flagged (${sample.why}): ${sample.code} -> ${hits[0].code}`);
    }
  }

  for (const sample of SELFTEST_PACKAGE_SCOPE) {
    const inScope = isInDisplayScope(sample.rel);
    if (inScope !== sample.expected) {
      problems.push(`package scope check (${sample.why}): ${sample.rel} should be `
        + `${sample.expected ? 'in' : 'out of'} scope but isInDisplayScope returned ${inScope}`);
    }
  }

  const okay = problems.length === 0;
  if (AS_JSON) {
    console.log(JSON.stringify({ ok: okay, mustFlag: SELFTEST_MUST_FLAG.length, mustNotFlag: SELFTEST_MUST_NOT_FLAG.length, packageScope: SELFTEST_PACKAGE_SCOPE.length, problems }, null, 2));
  } else {
    console.log('PerfScope threshold-operator gate -- SELFTEST');
    console.log(`  must flag        : ${SELFTEST_MUST_FLAG.length}`);
    console.log(`  must not flag    : ${SELFTEST_MUST_NOT_FLAG.length}`);
    console.log(`  package scope    : ${SELFTEST_PACKAGE_SCOPE.length}`);
    console.log('');
    for (const p of problems) console.log(`  [FAIL] ${p}`);
    console.log(okay ? 'GATE SELFTEST: PASS (the matcher still catches the shape it exists for)'
      : 'GATE SELFTEST: FAIL (the matcher has decayed -- a green tree scan proves nothing)');
  }
  process.exit(okay ? 0 : 1);
}

/**
 * 注释与字符串一律抹成等长空白（保留换行，行号准确）。
 *
 * <p>必须抹掉字符串：HTML 片段里有大量 {@code "<div class=\"...\">"}，
 * 里面偶尔会带 `>` 这类字符；不抹会让门禁把标记文本当成比较运算。
 * 与 {@code check-velocity-thresholds.mjs} 的做法相反（那边要读字符串里的 key），
 * 因为两者要找的东西不同。</p>
 */
function stripCommentsAndStrings(src) {
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
      if (c === "'") { state = 'char'; out += c; i++; continue; }
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
    if (state === 'string' || state === 'char') {
      const quote = state === 'string' ? '"' : "'";
      if (c === '\\') { out += '  '; i += 2; continue; }
      if (c === quote) { state = 'code'; out += c; i++; continue; }
      out += (c === '\n') ? '\n' : ' '; i++; continue;
    }
  }
  return out;
}

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

function lineOf(text, idx) {
  let n = 1;
  for (let i = 0; i < idx && i < text.length; i++) if (text[i] === '\n') n++;
  return n;
}

if (argv.includes('--selftest')) {
  // 自检不需要源码树：它只驱动匹配器本身
  runSelfTest();
}

if (!fs.existsSync(SRC)) {
  console.error(`source root not found: ${SRC}`);
  process.exit(2);
}

const files = walk(SRC).filter((f) => {
  const rel = path.relative(SRC, f).replace(/\\/g, '/');
  return isInDisplayScope(rel);
});

const violations = [];
let comparisonsSeen = 0;

for (const file of files) {
  const raw = fs.readFileSync(file, 'utf8');
  const text = stripCommentsAndStrings(raw);
  const rawLines = raw.split('\n');
  const rel = path.relative(REPO, file).replace(/\\/g, '/');

  for (const hit of findViolations(text)) {
    comparisonsSeen++;
    const line = lineOf(text, hit.index);
    const cur = rawLines[line - 1] || '';
    const prev = rawLines[line - 2] || '';
    if (EXEMPT.test(cur) || EXEMPT.test(prev)) continue;
    violations.push({
      file: rel,
      line,
      code: hit.code,
      lhs: hit.lhs,
      op: hit.op,
      rhs: hit.rhs,
    });
  }
}

const okay = violations.length === 0;

if (AS_JSON) {
  console.log(JSON.stringify({ ok: okay, repo: REPO, files: files.length, comparisonsSeen, violations }, null, 2));
} else {
  console.log('PerfScope threshold-operator consistency check');
  console.log(`  repo : ${REPO}`);
  console.log(`  scope: ${DISPLAY_PACKAGES.map((p) => p + '/').join(' ')} (${files.length} files)`);
  console.log('');
  if (okay) {
    console.log('THRESHOLD-OPERATOR CHECK: PASS');
  } else {
    for (const v of violations) {
      console.log(`  FAIL  ${v.file}:${v.line}  ${v.code}`);
      console.log(`        展示层不得自己写「读数 ${v.op} 阈值」：比较符只有一个出处`
        + `（ThresholdOperator.Rule#levelOf）。改调算子表；若确实要比较两个阈值，`
        + `在行内或上一行写 @threshold-op-ok <理由>。`);
    }
    console.log('');
    console.log(`THRESHOLD-OPERATOR CHECK: FAIL (${violations.length} violation(s))`);
  }
}

process.exit(okay ? 0 : 1);
