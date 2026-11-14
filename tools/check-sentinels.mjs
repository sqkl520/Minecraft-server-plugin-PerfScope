#!/usr/bin/env node
/**
 * check-sentinels.mjs -- L1 gate: sentinel-bearing fields must be tested for "missing",
 * not for "zero".
 *
 * 背景：v1.4.2 之前，同一个错误在**五处**代码里独立存在——把 `0` 当成"未采集"的哨兵，
 * 而真实的哨兵是 `-1`。后果两个方向都有：
 *
 *   把健康说成盲区：空闲服的 CPU（真的是 0）显示"未知"，`cpu:process` 指标整条不出现
 *   把毛病说成健康：日报 p95 滤掉 TPS 0，卡死窗口仍显示非零分数；trend 报"最低 18.0"
 *                   而实际卡死过——直接伪造历史
 *
 * 五处都是人肉发现的（补测试时顺带、或顺着线索扫）。这条门禁把"下一次"变成自动的。
 *
 * ── 判定口径 ────────────────────────────────────────────────────────────────
 *
 * 1. **有哨兵的字段**（见下方 SENTINEL_FIELDS）：`-1` 表示未采集/不可用。
 *    对这些字段的所有比较，**必须**用 `< 0` / `<= 0` 里的某一种，但要区分：
 *      - `x < 0` / `x >= 0`     → 只排除负哨兵。**正确**（0 是合法读数）
 *      - `x <= 0` / `x > 0`     → 把 0 也当成哨兵。**报错**
 *
 * 2. **无哨兵的字段**（见 NO_SENTINEL_FIELDS）：`0` 是合法读数（TPS、玩家数、内存百分比）。
 *    对这些字段，`x > 0` / `x <= 0` 形式的"排除 0"是**报错**，因为那会把真实的 0 藏起来。
 *
 * 3. 计数器（failed / queued / dropped / skipped / count…）不在此列：对它们 `> 0`
 *    是正确的用法（"有没有发生过"）。门禁按字段名白名单跳过。
 *
 * ── 为什么是白名单而不是黑名单 ──────────────────────────────────────────────
 *
 * 新加一个哨兵字段时，如果门禁是黑名单式的，它默认"不管"——错误照旧溜过去。
 * 白名单式要求作者显式声明字段属于哪一类，于是**新字段必须在两个名单之间选一个**，
 * 选错的代价是门禁报错而不是静默通过。
 *
 * 用法：
 *   node tools/check-sentinels.mjs
 *   node tools/check-sentinels.mjs --repo <path>
 *   node tools/check-sentinels.mjs --json
 *
 * 退出码：0 = PASS，1 = 发现违规，2 = 用法/路径错误
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

/**
 * 有 -1 哨兵的字段，且 `0` <b>也</b>不是可信读数：两种判断方式都接受。
 *
 * MSPT 属于这一类：默认值是 -1（未采集），而每 tick 耗时在物理上不可能真的是 0
 * ——一次 tick 至少要花掉可测量的时间。所以 `<= 0` 判"不可信"是对的，
 * `> 0` 判"可信"也是对的，两种写法在这里等价且都正确。
 *
 * 把它们和 CPU 分开是因为 CPU 的 0 是合法的（空闲），MSPT 的 0 不是。
 * 一刀切会逼着作者把正确的 `<= 0` 改成 `< 0`，那反而引入 bug。
 */
const SENTINEL_AND_ZERO_BOTH_BAD = {
  msptAvg: '每 tick 平均耗时；默认 -1，且每 tick 至少花掉可测量的时间，0 也不可信',
  msptMin: '同上',
  msptMax: '同上；HistorySample 里同为 NO_DOUBLE',
};

/**
 * 有 -1 哨兵的字段：必须用 `< 0` 式判断（只排除负值），不能用 `<= 0`（连 0 一起排除）。
 *
 * 每项给一句理由，门禁报错时会把它带出来——报错信息本身就是文档。
 */
const SENTINEL_FIELDS = {
  // MetricsSnapshot（-1 表示未采集）
  processCpuLoad: 'CPU 占用率；0 = 空闲，是合法读数（MetricsSnapshot 构造器默认 -1）',
  systemCpuLoad: '同上',
  processCpu: 'HistorySample 的 CPU 副本；NO_DOUBLE = -1',
  cpuProcess: '同上',
  cpuSystem: '同上',
  collectDurationMs: '-1 = 未测量',
};

/**
 * 无哨兵的字段：`0` 是真实读数，用 `> 0` / `<= 0` 排除它是错的。
 *
 * 这是 v1.4.2 修掉的那类 bug 的正脸：TPS 0 = 服务器冻结（最该被看见的值），
 * 过滤掉它等于把卡死报成健康。
 */
const NO_SENTINEL_FIELDS = {
  tps1m: 'TPS 没有 -1 哨兵（采集层直接写 Bukkit.getTPS()）；0 = 服务器冻结，是真实读数',
  tps5m: '同上',
  tps15m: '同上',
  tps: '同上',
  playerCount: '0 人在线是合法的（空服）',
  heapUsagePercent: '0 是合法的堆占用读数',
  totalEntities: '0 是合法的实体数',
  totalLoadedChunks: '0 是合法的区块数',
};

/** 计数器字段：`> 0` 是正确用法（"有没有发生过"），门禁跳过。 */
const COUNTER_NAMES = new Set([
  'failed', 'queued', 'dropped', 'skipped', 'count', 'restored', 'resets',
  'size', 'length', 'index', 'limit', 'days', 'hours', 'minutes', 'seconds',
  'retention', 'bytes', 'chars', 'lines', 'missing', 'pushed', 'suspect',
  'capacity', 'pending', 'restarts', 'failures', 'warnings', 'errors',
]);

/**
 * 注释剥离：把 `//` 行注释、`/* *\/` 块注释、javadoc 全替换成等长空白（保留换行，
 * 于是行号仍然准确）。
 *
 * <p>为什么必需：javadoc 里会**引述**错误代码（"原实现 {@code if (s.playerCount() > 0)}"），
 * 那些文字不是可执行代码，报出来全是噪音。门禁一有噪音就没人看，然后等于没有门禁。</p>
 */
function stripComments(src) {
  let out = '';
  let i = 0;
  let state = 'code';   // code | line | block | string | char
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
    if (state === 'string') {
      if (c === '\\') { out += '  '; i += 2; continue; }
      if (c === '"') { state = 'code'; out += c; i++; continue; }
      out += (c === '\n') ? '\n' : ' '; i++; continue;
    }
    if (state === 'char') {
      if (c === '\\') { out += '  '; i += 2; continue; }
      if (c === "'") { state = 'code'; out += c; i++; continue; }
      out += (c === '\n') ? '\n' : ' '; i++; continue;
    }
  }
  return out;
}

/**
 * 行内豁免：在该行或**上一行**写 `@sentinel-ok <理由>`，门禁跳过这一处。
 *
 * <p>为什么需要它：有些 `<= 0` 是**有意为之**的正确用法。例如
 * {@code PerfScopeCommand} 里推算等效 MSPT 时，`tps1m <= 0` 会走"服务器无响应"分支
 * 而不是做 {@code 1000/tps}——那是对的（除数不能是 0），且注释写明了理由。
 * 这种地方强迫作者改成 `< 0` 反而会引入 Infinity。</p>
 *
 * <p>要求写理由（而不是光一个标记）是因为：豁免是给"已想清楚"的情况用的，
 * 不是给"先过了再说"用的。审阅时能一眼看出作者当时怎么想的。</p>
 */
const EXEMPT = /@sentinel-ok\b/;

const violations = [];
const stats = [];

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

const SRC = path.join(REPO, 'src', 'main', 'java');
if (!fs.existsSync(SRC)) {
  console.error(`source tree not found: ${SRC}`);
  process.exit(2);
}

const files = walk(SRC);
const rel = (p) => path.relative(REPO, p).replace(/\\/g, '/');

// 匹配「<字段表达式> <比较符> 0」与「0 <比较符> <字段表达式>」
// 只认与字面量 0 的比较；与变量比的不在此列（那多半是阈值，不是哨兵判断）
const CMP = String.raw`(<=|>=|<|>|==|!=)`;
const fieldAlt = (o) => Object.keys(o).map((k) => k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  .sort((a, b) => b.length - a.length).join('|');

const sentinelRe = new RegExp(
  String.raw`(\b(?:snap\.|s\.|sample\.|last\.|first\.|prev\.)?(?:${fieldAlt(SENTINEL_FIELDS)})\b|` +
  String.raw`\b(?:${fieldAlt(SENTINEL_FIELDS)})\s*\(\s*\))\s*${CMP}\s*0(?:\.0+[dDfF]?)?(?![\d.])`, 'g');
const noSentinelRe = new RegExp(
  String.raw`(\b(?:snap\.|s\.|sample\.|last\.|first\.|prev\.)?(?:${fieldAlt(NO_SENTINEL_FIELDS)})\b|` +
  String.raw`\b(?:${fieldAlt(NO_SENTINEL_FIELDS)})\s*\(\s*\))\s*${CMP}\s*0(?:\.0+[dDfF]?)?(?![\d.])`, 'g');

function lineOf(text, idx) {
  let n = 1;
  for (let i = 0; i < idx && i < text.length; i++) if (text[i] === '\n') n++;
  return n;
}

const BASELINE = new Set();   // 已知并接受的例外，格式 "相对路径:字段:运算符"

for (const f of files) {
  const raw = fs.readFileSync(f, 'utf8');
  const text = stripComments(raw);   // 行号保持准确：注释被替换成等长空白
  const r = rel(f);
  let found = 0;

  // 豁免标记要从**原始文本**里找（stripComments 会把注释抹掉）
  const rawLines = raw.split('\n');
  const exemptAt = (line) => {
    const cur = rawLines[line - 1] || '';
    const prev = rawLines[line - 2] || '';
    return EXEMPT.test(cur) || EXEMPT.test(prev);
  };

  // ---- 有哨兵的字段：<= 0 与 > 0 是错的 ----
  for (const m of text.matchAll(sentinelRe)) {
    const [whole, lhsOrCall, op] = m;
    found++;
    // 从匹配里还原字段名
    const name = Object.keys(SENTINEL_FIELDS).find((k) =>
      lhsOrCall.includes(k));
    if (!name) continue;
    if (COUNTER_NAMES.has(name)) continue;
    const wrong = op === '<=' || op === '>';
    if (!wrong) continue;
    const key = `${r}:${name}:${op}`;
    if (BASELINE.has(key)) continue;
    const ln = lineOf(text, m.index);
    if (exemptAt(ln)) continue;
    violations.push({
      file: r,
      line: ln,
      field: name,
      op,
      code: whole.trim(),
      rule: 'SENTINEL_AS_ZERO',
      detail: `${SENTINEL_FIELDS[name]}。用 '${op} 0' 会把 0 也排除掉；应当用 '${op === '<=' ? '<' : '>='} 0'`,
    });
  }

  // ---- 无哨兵的字段：> 0 与 <= 0 是错的 ----
  for (const m of text.matchAll(noSentinelRe)) {
    const [whole, lhsOrCall, op] = m;
    found++;
    const name = Object.keys(NO_SENTINEL_FIELDS).find((k) => lhsOrCall.includes(k));
    if (!name) continue;
    if (COUNTER_NAMES.has(name)) continue;
    const wrong = op === '>' || op === '<=';
    if (!wrong) continue;
    const key = `${r}:${name}:${op}`;
    if (BASELINE.has(key)) continue;
    const ln = lineOf(text, m.index);
    if (exemptAt(ln)) continue;
    violations.push({
      file: r,
      line: ln,
      field: name,
      op,
      code: whole.trim(),
      rule: 'ZERO_IS_REAL_READING',
      detail: `${NO_SENTINEL_FIELDS[name]}。用 '${op} 0' 会把它藏起来；应当用 '${op === '>' ? '>=' : '<'} 0'`,
    });
  }

  if (found > 0) stats.push({ file: r, comparisons: found });
}

// ---- 报告 ----
if (AS_JSON) {
  console.log(JSON.stringify({ ok: violations.length === 0, stats, violations }, null, 2));
} else {
  console.log('PerfScope sentinel-convention check');
  console.log(`  repo : ${REPO}`);
  console.log(`  files: ${files.length} java, ${stats.length} with sentinel comparisons`);
  console.log('');
  if (violations.length === 0) {
    console.log('SENTINEL CHECK: PASS');
  } else {
    const byRule = new Map();
    for (const v of violations) {
      if (!byRule.has(v.rule)) byRule.set(v.rule, []);
      byRule.get(v.rule).push(v);
    }
    for (const [rule, vs] of byRule) {
      console.log(`${rule} (${vs.length}):`);
      for (const v of vs) {
        console.log(`  ${v.file}:${v.line}  [${v.field}] ${v.code}`);
        console.log(`      ${v.detail}`);
      }
      console.log('');
    }
    console.log(`SENTINEL CHECK: FAIL (${violations.length} violation(s))`);
  }
}

process.exit(violations.length === 0 ? 0 : 1);
