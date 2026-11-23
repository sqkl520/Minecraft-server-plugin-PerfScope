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
const flag = (name) => argv.includes(name);

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
// 字段表达式。两条交替分支的顺序与边界都踩过坑：
//
// 1. **方法调用分支必须排前面**。交替是"从左到右取第一个能匹配的"，而 `s.tps1m()` 里
//    「带前缀的字段名」分支会先吃掉 `s.tps1m`，然后正则要求后面跟运算符，实际却是 `()`
//    —— 整个匹配失败，`s.tps1m() <= 0` 完全不被看见。
// 2. **方法调用分支不能受外层的 `(?<![\w.])` 约束**。`s.tps1m()` 中 `tps1m` 前面是 `.`，
//    后顾断言会把它挡掉。所以调用分支自己带上可选的前缀（`s.` 也算它的一部分）。
const FIELD_EXPR = (o) => String.raw`((?:\b(?:snap\.|s\.|sample\.|last\.|first\.|prev\.)?` +
  String.raw`(?:${fieldAlt(o)})\s*\(\s*\))|` +
  String.raw`\b(?:snap\.|s\.|sample\.|last\.|first\.|prev\.)?(?:${fieldAlt(o)})\b)`;
// 字面量 0。**不带自己的边界断言**：边界由 makeComparisonRe 在外层统一加，
// 这里再带一个 `(?![\d.])` 会与外层的 `(?![\w.])` 叠加，把合法匹配也挡掉
// （实测：0 与 < 之间有空格时 `(?![\d.])` 仍成立，但紧跟 `)` 或 `&` 时会失效）。
const ZERO = String.raw`0(?:\.0+[dDfF]?)?`;

// --------------------------------------------------------------------------------------
// 「正确写法集合」与「镜像形式」
//
// 三类字段的正确写法**不同**（见下方 CORRECT_OPS 的说明），所以不能共用一套运算符判断。
// 但三类都吃同一个坑：**只认"字段在左"的形式**。
//
//   x < 0        正确           0 > x        等价，但此前完全不检查
//   0 <= x       等价 x >= 0     此前也完全不检查
//
// 等值比较 `0 == x` 同样漏。所以每类都同时匹配两个方向，
// 再把「0 在左」翻转成「字段在左」的等价运算符，统一按正确集合判定。
// --------------------------------------------------------------------------------------

/** 把 `0 <op> x` 翻转成 `x <op'> 0` 的等价运算符。 */
const MIRROR = { '<': '>', '>': '<', '<=': '>=', '>=': '<=', '==': '==', '!=': '!=' };

/**
 * 三类字段各自的**正确写法集合**。
 *
 * <p>用「正确集合」而不是「错误枚举」是这套判定的关键。错误枚举永远漏：实测 12 种写法里，
 * 旧实现只拦住 2 种（`<= 0` / `> 0`），其余 10 种全放行——包括语义**完全反转**的 `>= 0`
 * （把 -1 当可信、把 0 当不可信）、`== 0` / `!= 0`，以及全部「0 写在左边」的镜像形式。</p>
 *
 * <p>正确集合还顺带挡住「将来新出现的运算符」：不在集合里就是错。</p>
 */
const CORRECT_OPS = {
  // 哨兵 -1，0 是**合法读数**：只需排除负值。
  //   x < 0   /  x >= 0   正确（互补）
  //   x <= 0  /  x > 0    错（连合法的 0 一起排除）—— 这就是 v1.4.2 那类 bug
  //   x == 0  /  x != 0   错（只判一个点，-1 与 0 混着当）
  SENTINEL: new Set(['<', '>=']),
  // 哨兵 -1 且 0 **也不可信**（MSPT）：两个方向都必须判"不可信"。
  //   x <= 0  /  x > 0    正确（互补）
  //   x < 0   /  x >= 0   错（漏掉 0 这一半）
  BOTH_BAD: new Set(['<=', '>']),
  // 无哨兵，0 是**真实读数**（TPS 0 = 冻结）。
  //   x >= 0  /  x < 0    正确
  //   x > 0   /  x <= 0   错（把冻结报成健康）—— v1.4.2 的正脸
  NO_SENTINEL: new Set(['>=', '<']),
};

/** 正确写法的建议，按字段类别给（报错信息本身就是文档）。 */
const ADVICE = {
  SENTINEL: `0 是合法读数，不能连它一起排除；用 '< 0' 判"不可信"或 '>= 0' 判"可信"`,
  BOTH_BAD: `'-1' 与 '0' 都不可信，两个方向都要覆盖；用 '<= 0' 判"不可信"或 '> 0' 判"可信"`,
  NO_SENTINEL: `0 是真实读数，不能排除它；用 '>= 0' 判"可信"或 '< 0' 判"不可信"`,
};

/**
 * 一张正则同时抓两个方向。捕获组：
 *   1 = 左侧（字段表达式或字面量 0）
 *   2 = 运算符
 *   3 = 右侧（字段表达式或字面量 0）
 *
 * 判定时把「0 在左」的翻转成等价形式，再查 {@link CORRECT_OPS}。
 * 要求**恰好一边**是字面量 0、另一边是字段：两边都不是 0 是阈值比较，
 * 两边都是 0 是废话，都不在此列。
 */
function makeComparisonRe(fields) {
  // 捕获组必须恰好是：1=左、2=运算符、3=右。
  //
  // 坑：FIELD_EXPR 自带一对**捕获**组。若直接嵌进外层捕获组，
  // 组号会变成 1=外层左、2=内层左、3=运算符、4=右 —— 判定的三个下标全错位，
  // 表现为 m[3] 拿到 undefined（只有左侧是字段时才是字段名，因为内层组恰好命中）。
  // 所以先把 FIELD_EXPR 里的 `(` 换成 `(?:`，让它变成非捕获，再嵌。
  //
  // **两侧都必须允许 ZERO**。此前左侧硬编码成 FIELD_EXPR，于是 `0 < x` 这类镜像形式
  // 压根匹配不到——不是"判对了"，是没被看见。实测 36 格矩阵里 12 格因此判错，
  // 全部集中在「0 写在左边」这一半（见 .REMAINING-PLAN.md 第二部分）。
  //
  // 数字边界 `(?<![\w.])` / `(?![\w.])` 是必须的：两侧都可为 ZERO 之后，
  // 字面量 0 会从数字中间开始匹配——`10 < snap.tps1m` 会把 "10" 尾部的 0
  // 当成字面量 0，产生 [0 | < | snap.tps1m] 的假违规（实测）。
  const f = FIELD_EXPR(fields).replace('(', '(?:');
  const N = String.raw`(?:(?:${f})|${ZERO})`;
  return new RegExp(String.raw`(?<![\w.])(${N})\s*${CMP}\s*(${N})(?![\w.])`, 'g');
}
const IS_ZERO = /^0(?:\.0+[dDfF]?)?$/;

const sentinelRe = makeComparisonRe(SENTINEL_FIELDS);
const noSentinelRe = makeComparisonRe(NO_SENTINEL_FIELDS);

/**
 * 「哨兵是 -1 且 0 也不可信」的字段：**任何与 0 的等值/不等值比较都是错的**。
 *
 * <p>MSPT 就属于这一类：`0` 不可能是一次真实 tick 的耗时，`-1` 是"未采集"，
 * 两者都必须判"不可信"。所以 `x == 0` / `x != 0` 这样的判据注定漏掉一半情形——
 * `x == 0` 会把 -1 当可信，`x != 0` 会把 0 当可信。</p>
 *
 * <p>这一类此前**定义了却没有接进正则**（`SENTINEL_AND_ZERO_BOTH_BAD` 是个孤儿常量），
 * 于是 `msptMax >= 0` 这种把"0 也不可信"的字段当成"0 可信"的改动，测试与门禁双双放行。
 * 那是一轮可维护性审计实测出来的。</p>
 */
/**
 * 对「哨兵是 -1 且 0 也不可信」的字段，两个方向都必须判"不可信"，
 * 所以只有下面四组是**正确**写法：
 *
 *   x <= 0   /  x > 0      （小于等于 0 不可信 / 大于 0 才可信）
 *   x < 0    /  x >= 0     ← 这两个**错**：漏掉 0 这一半
 *   x == 0   /  x != 0     ← 这两个**错**：只判了一个点，另一半完全没提
 *
 * 此前正则只接了 `==` / `!=`（`SENTINEL_AND_ZERO_BOTH_BAD` 是孤儿常量），
 * 于是 `msptMax >= 0` 这类把"0 也不可信"当"0 可信"的改动测试与门禁双双放行。
 *
 * **但扩到序关系后不能一刀切报**：`<= 0` 与 `> 0` 正是正确写法，
 * 一刀切会产生 20 处误报（实测），噪声一多这条门禁就没人看了。
 * 所以按 {@link CORRECT_OPS}.BOTH_BAD 的**正确集合**判定，而不是枚举错误写法。
 */
const bothBadRe = makeComparisonRe(SENTINEL_AND_ZERO_BOTH_BAD);

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

  // ---- 三类字段统一判定：对照「正确写法集合」，而不是枚举错误写法 ----
  //
  // 三层问题一次解决：
  //   1. 只用错误枚举 -> 漏掉未枚举的运算符（实测 12 种写法只拦住 2 种）
  //   2. 只认「字段在左」-> `0 < x` 这类镜像形式完全不检查
  //   3. 三类的正确集合不同，不能共用一套判断
  const CLASSES = [
    { re: sentinelRe,   fields: SENTINEL_FIELDS,            kind: 'SENTINEL',    rule: 'SENTINEL_AS_ZERO' },
    { re: bothBadRe,    fields: SENTINEL_AND_ZERO_BOTH_BAD, kind: 'BOTH_BAD',    rule: 'EQUALITY_AGAINST_ZERO' },
    { re: noSentinelRe, fields: NO_SENTINEL_FIELDS,         kind: 'NO_SENTINEL', rule: 'ZERO_IS_REAL_READING' },
  ];

  for (const cls of CLASSES) {
    for (const m of text.matchAll(cls.re)) {
      const [whole, lhs, opRaw, rhs] = m;
      const lhsIsZero = IS_ZERO.test(lhs.trim());
      const rhsIsZero = IS_ZERO.test(rhs.trim());
      // 必须恰好一边是字面量 0、另一边是字段。两边都不是 0 是阈值比较，两边都是 0 是废话。
      if (lhsIsZero === rhsIsZero) continue;
      found++;
      const fieldSide = lhsIsZero ? rhs : lhs;
      // 「0 在左」要翻转成等价的「字段在左」形式再判定：`0 > x` 等价于 `x < 0`
      const op = lhsIsZero ? MIRROR[opRaw] : opRaw;
      const name = Object.keys(cls.fields).find((k) => fieldSide.includes(k));
      if (!name) continue;
      if (COUNTER_NAMES.has(name)) continue;
      if (CORRECT_OPS[cls.kind].has(op)) continue;
      const ln = lineOf(text, m.index);
      if (exemptAt(ln)) continue;
      const key = `${r}:${name}:${op}`;
      if (BASELINE.has(key)) continue;
      violations.push({
        file: r,
        line: ln,
        field: name,
        op: opRaw,
        code: whole.trim(),
        rule: cls.rule,
        detail: `${cls.fields[name]}。用 '${whole.trim()}' 判不对：${ADVICE[cls.kind]}`,
      });
    }
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

// 自检必须在退出之前跑完：它是「本次报告可信」的前提，不是一个可选的附加项。
// 放在这里而不是文件末尾，是因为 process.exit 之后什么都不执行。

// ======================================================================================
// 自检：36 格判定表
// ======================================================================================
//
// 这张表是三类字段 × 12 种写法，每一格的"该红/该绿"都有语义依据（见 CORRECT_OPS）。
//
// **为什么必须内置**：上一轮的"正确写法集合"改造把"字段在左"那一半修对了，但
// "0 写在左边"的镜像形式**压根匹配不到**——不是判对了，是没被看见。36 格里 12 格判错，
// 而所有现有测试都是绿的，因为没有任何东西覆盖这张表。
//
// 与 `check-docs.mjs` 同款：每次正常调用都**静默执行**，只在失败时影响退出码。
// 单独看表时用 `--selftest`。
function runSelftest() {
  const MIRROR = { '<': '>', '>': '<', '<=': '>=', '>=': '<=', '==': '==', '!=': '!=' };
  const OPS = ['<', '<=', '>', '>=', '==', '!='];
  // **期望值是写死的字面量，不能从 CORRECT_OPS 推导**。
  //
  // 第一版写成 `expectRed = !CORRECT_OPS[kind].has(eff)`，看着简洁，实际是自指：
  // 把 CORRECT_OPS.SENTINEL 改坏成 `['<']` 之后，`>=` 的期望值跟着变成"该红"，
  // 于是自检仍报 36/36 PASS —— 自检把自己的错误当成了标准答案。
  // 实测确认过这个形态：改坏集合后 selftest 依然 PASS。
  //
  // 表的内容来自 .REMAINING-PLAN.md 第二部分（三类语义各自推导，见 CORRECT_OPS 的注释）。
  const EXPECT = {
    SENTINEL:    { '<': 'GREEN', '<=': 'RED',  '>': 'RED',  '>=': 'GREEN', '==': 'RED', '!=': 'RED' },
    BOTH_BAD:    { '<': 'RED',   '<=': 'GREEN','>': 'GREEN','>=': 'RED',   '==': 'RED', '!=': 'RED' },
    NO_SENTINEL: { '<': 'GREEN', '<=': 'RED',  '>': 'RED',  '>=': 'GREEN', '==': 'RED', '!=': 'RED' },
  };
  const CASES = [
    { kind: 'SENTINEL',    field: 'processCpuLoad' },
    { kind: 'BOTH_BAD',    field: 'msptMax' },
    { kind: 'NO_SENTINEL', field: 'tps1m' },
  ];
  const made = {
    SENTINEL: sentinelRe, BOTH_BAD: bothBadRe, NO_SENTINEL: noSentinelRe,
  };
  const out = [];
  for (const c of CASES) {
    for (const mirror of [false, true]) {
      for (const op of OPS) {
        const text = mirror ? `0 ${op} snap.${c.field};` : `snap.${c.field} ${op} 0;`;
        const eff = mirror ? MIRROR[op] : op;
        // 期望值查**字面量表**，不查被检验的集合本身。
        const expectRed = EXPECT[c.kind][eff] === 'RED';
        const re = made[c.kind];
        re.lastIndex = 0;
        const m = re.exec(text);
        // 「有没有被这条正则看见」是独立的一件事：看不见 = 该报的也报不出来。
        const sawIt = m != null && (IS_ZERO.test((m[1] || '').trim()) || IS_ZERO.test((m[3] || '').trim()));
        const actualRed = sawIt && !CORRECT_OPS[c.kind].has(eff);
        // 看见 + 判定正确，才算这一格对。没看见的格子里，期望绿的那些算"碰巧对"，
        // 但也要标记出来——因为漏匹配意味着下次换成同义写法时会漏。
        const ok = sawIt ? expectRed === actualRed : !expectRed;
        out.push({ kind: c.kind, text: text.trim(), eff, expectRed, actualRed, sawIt, ok });
      }
    }
  }
  return out;
}

const selftestRows = runSelftest();
const selftestFailures = selftestRows.filter((r) => !r.ok);
if (flag('--selftest')) {
  console.log('check-sentinels 自检：三类字段 × 12 种写法 = 36 格');
  console.log('');
  console.log('  状态 类别          写法                     等价形式  期望   实际');
  for (const r of selftestRows) {
    console.log(`  ${r.ok ? 'OK  ' : 'FAIL'} ${r.kind.padEnd(13)} ${r.text.padEnd(24)} ${r.eff.padEnd(8)} `
      + `${r.expectRed ? 'RED  ' : 'GREEN'}  ${r.sawIt ? (r.actualRed ? 'RED' : 'GREEN') : '(未匹配)'}`);
  }
  console.log('');
  console.log(`SENTINEL SELFTEST: ${selftestFailures.length === 0 ? 'PASS' : 'FAIL'} `
    + `(${selftestRows.length - selftestFailures.length}/${selftestRows.length})`);
}

if (selftestFailures.length > 0) {
  console.log('');
  console.log(`SENTINEL SELFTEST: FAIL (${selftestFailures.length}/${selftestRows.length} cells wrong) -- `
    + `the operator sets in CORRECT_OPS and the regex in makeComparisonRe disagree. `
    + `Run with --selftest to see the full table.`);
  for (const r of selftestFailures.slice(0, 6)) {
    console.log(`  ${r.kind}  ${r.text}  expected ${r.expectRed ? 'REPORTED' : 'not reported'}, got `
      + `${r.sawIt ? (r.actualRed ? 'reported' : 'not reported') : 'NOT MATCHED AT ALL'}`);
  }
}

process.exit(violations.length === 0 && selftestFailures.length === 0 ? 0 : 1);
