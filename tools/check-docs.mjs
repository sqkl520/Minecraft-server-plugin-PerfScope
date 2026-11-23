#!/usr/bin/env node
/**
 * check-docs.mjs -- L1 gate: user-facing product text must not contain the author's particle.
 *
 * 背景：项目规则要求"产品文案不带语气词"，但这条规则长期只靠人记 —— 没有门禁。
 * 实测过的情况：README 与 config.yml 都曾经带着它发出去，CHANGELOG 更是累积了 331 处。
 * 清理一次不够，没有拦截就会再长回来。
 *
 * ---------------------------------------------------------------------------------------
 * 覆盖范围（"用户会看到吗"是唯一判据，不是"文件在哪个目录"）
 * ---------------------------------------------------------------------------------------
 *
 *   1) 产品文档（DOC_EXCLUDE_DIRS 之外的全部 *.md，默认纳入）—— 仓库首页、安装/排障/安全/
 *      贡献/许可、versions/README.md（告诉用户该拿哪个 jar）、.github/PULL_REQUEST_TEMPLATE.md
 *      （任何人开 PR 都会看到）。新增一份 md 不需要登记，默认就会被扫。
 *   2) jar 内资源（src/main/resources/** 的全部文本文件）—— plugin.yml 的 description /
 *      usage / permission-message、config.yml 的注释与默认值、META-INF/NOTICE 等都会随 jar
 *      发到用户手里。它们不进 git 之外的任何文档目录，此前完全没被管。
 *   3) pom.xml 的用户可见元数据（name / description / url / license 名）—— 会进 jar 内的
 *      META-INF/maven/.../pom.xml 与 Maven 仓库页面。
 *   4) Java 源码里的**字符串字面量**（src/main/java 下全部 .java）—— 消息表（Messages /
 *      ConfigMessages / CommandMessages / 各模块 *Messages）与内联拼接的玩家可见文案。
 *      注释不在扫描范围内：注释里讨论这条规则是允许的，而且是必要的（见下面 ALLOWED_SENTENCES
 *      的说明——它们自己就写着这个词）。
 *
 * 开发过程产物**不扫**（DOC_EXCLUDE_DIRS）：tools/（门禁与测试限制说明）、docs/、ralph/、
 * ralph-plugin/、logs/。它们本来就要引用、讨论历史上的产品文案（"某个版本曾带着它发出去"），
 * 删掉会伪造记录。判据是"这份文件写给谁看"，不是"它在不在仓库里"——所以清单按目录列，
 * 不按文件名列：新写一份 product 文档不用改这个脚本。
 *
 * ---------------------------------------------------------------------------------------
 * 白名单：只豁免那一句本身，不豁免整行
 * ---------------------------------------------------------------------------------------
 *
 * 唯一允许出现语气词的产品文案是 README 顶部那句作者自述「如果喜欢这个项目就 Star 一下喵」。
 *
 * 旧实现按**整行** `includes` 豁免：一行里只要出现过这句，该行所有语气词都放行。实测（审计
 * D3）在同一行末尾再补一句「……另外别忘了看看文档喵。」仍然报 PASS。现在改成**位置判定**：
 * 语气词必须落在某次「白名单整句」出现的位置区间**之内**才豁免。同一行里第二个语气词不在这
 * 个区间内，因此照样红。白名单仍然只认整句（不是文件、不是行号），因为门禁存在的理由就是
 * 防止口癖漏进产品文案。
 *
 * CHANGELOG 另有一条更宽的规则：允许出现，但只允许出现在**引述**里 —— 即后面紧跟
 * " ~ 」 ` ” 这几种字符，或整行属于代码围栏内的实测回放。句末语气词（后面跟 。或 ：）一律
 * 不允许。为什么 CHANGELOG 要放宽：它的历史里有「控制台回放」「被修掉的消息串原文」
 * 「i18n 可配置化决策」这类内容，语气词在那里是**被讨论的对象**，删掉会伪造历史。
 *
 * ---------------------------------------------------------------------------------------
 * 退出码：缺输入 ≠ 通过
 * ---------------------------------------------------------------------------------------
 *
 *   0 = PASS（扫了东西，且 0 处违规）
 *   1 = 发现违规
 *   2 = **无法运行**：`--repo` 不是目录、必需文件缺失、或扫描目标为空。
 *
 * 旧实现里 `--repo /nonexistent` 会打印 "DOCS CHECK: PASS" 并 exit 0（审计 D8）：
 * 路径不存在 → 每个文件都"缺失"→ 每个文件都"跳过"→ 零违规。删掉一个目录就能把门禁变成
 * 绿灯，这比没有门禁更糟。现在任何"我该扫的东西不在这里"的情况都 exit 2，与
 * check-scenarios.mjs 的口径一致（它找不到 scenario 目录时也是 exit 2）。
 *
 * 用法：
 *   node tools/check-docs.mjs
 *   node tools/check-docs.mjs --repo <path>
 *   node tools/check-docs.mjs --json
 *   node tools/check-docs.mjs --no-selftest  # skip the built-in 12-case self-check
 *   node tools/check-docs.mjs --selftest     # print the self-check table and exit
 *
 * 自检（selftest）在**每次正常调用时**静默执行：六处空洞的共同形状是"改坏了也全绿"，
 * 干净的工作树本来就该 PASS，因此只扫真实仓库永远发现不了门禁自身退化。自检失败 → exit 2。
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { javaFiles, readJava, stringLiterals } from './lib/java-strings.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : dflt;
};
// This script lives at <repo>/tools/, so one level up IS the repo.
const REPO = path.resolve(argOf('--repo', path.join(HERE, '..')));
const AS_JSON = argv.includes('--json');

const EXIT_PASS = 0;
const EXIT_VIOLATION = 1;
const EXIT_CANNOT_RUN = 2;

/** 语气词本体。集中在这里，改名词时只改一处。 */
const PARTICLE = '喵';

/**
 * 白名单：精确到**整句文案**，不是文件、也不是行号。
 *
 * 唯一允许出现语气词的产品文案语句是作者在 README 顶部的那段自述声明，结尾
 * 「如果喜欢这个项目就 Star 一下喵」——这是项目对外的自我介绍口吻，不是技术文案。
 *
 * 判定方式（D3 修复的核心）：在待扫文本里找出每一次白名单整句出现的 [start, end) 区间，
 * 某个语气词**落在区间内**才豁免。旧实现取"语气词所在整行"，再用 `line.includes(整句)`
 * 判断，等于把整行一起放行——同一行再叠加一处泄漏不被发现。
 *
 * 英文 README 不设白名单——对应的英文声明按参考仓库写法不带语气词。
 */
const ALLOWED_SENTENCES = [
  '如果喜欢这个项目就 Star 一下喵',
];

/** CHANGELOG 里"引述"的判据：语气词后面紧跟这些字符之一。 */
const QUOTE_SUFFIXES = ['"', '~', '」', '`', '”'];

/** 允许引述、但禁止句末语气词的文件（相对仓库根）。 */
const QUOTING_FILES = new Set(['CHANGELOG.md']);

/**
 * 开发过程产物目录：**不扫**。按目录名匹配（任意层级），所以新增一份产品文档不需要登记。
 *
 * 依据是"写给谁看"：这些目录里的 md 记录的是开发/审计/发布过程，需要引用历史上的产品文案
 * （"某个版本曾带着它发出去""这个键当时怎么写的"）。把它们纳入扫描，唯一结果是逼人删改
 * 历史记录 —— 而那正是本门禁上一版放宽 CHANGELOG 的理由。
 */
const DOC_EXCLUDE_DIRS = new Set([
  '.git', '.idea', '.vscode',
  'node_modules', 'target', 'build', 'out', 'dist',
  'docs', 'ralph', 'ralph-plugin', 'logs', 'tools',
  '.claude', '.trae',
]);

/** 必须存在的输入。缺任何一个都说明"这不是那个仓库"，必须 exit 2 而不是 PASS。 */
const REQUIRED_FILES = [
  ['README.md', 'the product front page is the main thing this gate protects'],
  ['CHANGELOG.md', 'release history is product text (quoting rule applies there)'],
  ['src/main/resources/plugin.yml', 'ships inside the jar; its description is user-visible'],
  ['src/main/resources/config.yml', 'ships inside the jar; its comments are user-visible'],
];

// ---------------------------------------------------------------------------------------
// 语气词定位与白名单
// ---------------------------------------------------------------------------------------

/** 文本里每一次白名单整句出现的位置区间。 */
function allowedSpans(text) {
  const spans = [];
  for (const sentence of ALLOWED_SENTENCES) {
    let i = -1;
    while ((i = text.indexOf(sentence, i + 1)) >= 0) {
      spans.push({ start: i, end: i + sentence.length });
      i += sentence.length - 1;
    }
  }
  return spans;
}

/** 该位置上的语气词是否**落在那一次白名单整句之内**（不是"所在行包含整句"）。 */
function insideAllowedSentence(spans, idx) {
  return spans.some((s) => idx >= s.start && idx < s.end);
}

function lineOf(text, index) {
  let n = 1;
  for (let i = 0; i < index && i < text.length; i++) {
    if (text[i] === '\n') n++;
  }
  return n;
}

// ---------------------------------------------------------------------------------------
// 扫描核心（纯函数：给定仓库根，返回结果对象；--selftest 复用同一份逻辑）
// ---------------------------------------------------------------------------------------

const MAX_VIOLATIONS = 200;

/**
 * @returns {{fatal: string|null, violations: Array, stats: Array, targets: object}}
 *   fatal 非空 = 无法运行（对应 exit 2）。
 */
export function scanRepo(root) {
  const violations = [];
  const stats = [];
  const targets = { docs: 0, jarResources: 0, javaFiles: 0, pom: false };

  const fatalOf = (message) => ({ fatal: message, violations, stats, targets });

  let rootStat;
  try {
    rootStat = fs.statSync(root);
  } catch {
    return fatalOf(`repository path does not exist: ${root}`);
  }
  if (!rootStat.isDirectory()) {
    return fatalOf(`repository path is not a directory: ${root}`);
  }

  const add = (file, text, idx, rule, detail) => {
    if (violations.length >= MAX_VIOLATIONS) return;
    violations.push({ file, line: lineOf(text, idx), rule, detail });
  };

  /** 严格模式：任何一处语气词都是违规（落在白名单整句之内的除外）。 */
  function scanStrict(rel, text, rule, detail) {
    const spans = allowedSpans(text);
    let count = 0;
    let allowed = 0;
    let idx = -1;
    while ((idx = text.indexOf(PARTICLE, idx + PARTICLE.length)) >= 0) {
      count++;
      if (insideAllowedSentence(spans, idx)) {
        allowed++;
        continue;
      }
      add(rel, text, idx, rule, detail);
    }
    stats.push({ file: rel, mode: 'strict', rule, count, allowed });
    return { count, allowed };
  }

  /** 引述模式：只禁句末语气词。 */
  function scanQuoting(rel, text) {
    let total = 0;
    let quoted = 0;
    let idx = -1;
    while ((idx = text.indexOf(PARTICLE, idx + PARTICLE.length)) >= 0) {
      total++;
      const next = text[idx + PARTICLE.length] ?? '';
      if (QUOTE_SUFFIXES.includes(next)) {
        quoted++;
        continue;
      }
      add(rel, text, idx, 'SENTENCE_PARTICLE',
        `particle followed by ${JSON.stringify(next)} -- looks like a sentence-final particle, not a quotation`);
    }
    stats.push({ file: rel, mode: 'quoted-only', rule: 'QUOTED_ONLY', count: total, quoted });
    return { total, quoted };
  }

  // ---- 0. 必需输入 -------------------------------------------------------------------
  const missing = REQUIRED_FILES
    .filter(([rel]) => !fs.existsSync(path.join(root, rel)))
    .map(([rel, why]) => `${rel} (${why})`);
  if (missing.length > 0) {
    return fatalOf(`required input file(s) missing under ${root}: ${missing.join('; ')}`);
  }

  // ---- 1. 产品文档（默认纳入，按目录排除开发过程产物）--------------------------------
  const docFiles = [];
  const walkErrors = [];
  const walkDocs = (dir) => {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch (err) {
      // 读不了的目录不能静默跳过 —— "扫不到" 与 "干净" 必须区分开，否则又是一个缺口。
      walkErrors.push(`${path.relative(root, dir) || '.'}: ${err.message}`);
      return;
    }
    for (const e of entries) {
      const abs = path.join(dir, e.name);
      if (e.isDirectory()) {
        if (DOC_EXCLUDE_DIRS.has(e.name)) continue;
        walkDocs(abs);
      } else if (e.isFile() && e.name.toLowerCase().endsWith('.md')) {
        docFiles.push(path.relative(root, abs).split(path.sep).join('/'));
      }
    }
  };
  walkDocs(root);
  docFiles.sort();

  if (walkErrors.length > 0) {
    return fatalOf(`cannot read part of the documentation tree: ${walkErrors.join('; ')}`);
  }
  if (docFiles.length === 0) {
    return fatalOf(`no markdown documentation found under ${root} -- nothing to scan is not a pass`);
  }
  targets.docs = docFiles.length;

  for (const rel of docFiles) {
    let text;
    try {
      text = fs.readFileSync(path.join(root, rel), 'utf8');
    } catch (err) {
      return fatalOf(`cannot read ${rel}: ${err.message}`);
    }
    if (QUOTING_FILES.has(rel)) {
      scanQuoting(rel, text);
    } else {
      scanStrict(rel, text, 'STRICT_FILE',
        'product documentation must not contain the particle at all (only the pinned self-introduction sentence may)');
    }
  }

  // ---- 2. jar 内资源（src/main/resources 下的全部文本文件）---------------------------
  const RES = path.join(root, 'src', 'main', 'resources');
  if (!fs.existsSync(RES) || !fs.statSync(RES).isDirectory()) {
    return fatalOf(`missing directory: ${path.relative(root, RES)} -- the files that ship inside the jar are not being scanned`);
  }
  const resourceFiles = [];
  const walkResources = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const abs = path.join(dir, e.name);
      if (e.isDirectory()) walkResources(abs);
      else if (e.isFile()) resourceFiles.push(path.relative(root, abs).split(path.sep).join('/'));
    }
  };
  try {
    walkResources(RES);
  } catch (err) {
    return fatalOf(`cannot read ${path.relative(root, RES)}: ${err.message}`);
  }
  resourceFiles.sort();

  if (resourceFiles.length === 0) {
    return fatalOf(`no files under ${path.relative(root, RES)} -- nothing to scan is not a pass`);
  }
  targets.jarResources = resourceFiles.length;

  for (const rel of resourceFiles) {
    let buf;
    try {
      buf = fs.readFileSync(path.join(root, rel));
    } catch (err) {
      return fatalOf(`cannot read ${rel}: ${err.message}`);
    }
    // 二进制资源（图标、字体）不在这里判——只扫文本，NUL 是最省事的判据。
    if (buf.includes(0)) {
      stats.push({ file: rel, mode: 'skipped', rule: 'JAR_RESOURCE', count: 0, allowed: 0, binary: true });
      continue;
    }
    scanStrict(rel, buf.toString('utf8'), 'JAR_RESOURCE',
      'this file ships inside the jar; comments and values are read by server owners (D4/D5)');
  }

  // ---- 3. pom.xml 的用户可见元数据 ---------------------------------------------------
  const pomPath = path.join(root, 'pom.xml');
  if (fs.existsSync(pomPath)) {
    const pom = fs.readFileSync(pomPath, 'utf8');
    targets.pom = true;
    // name / description / url / licenses 的 name：这些会进 jar 内的
    // META-INF/maven/**/pom.xml 与仓库页面，是用户读得到的文字。
    const rx = /<(name|description|url)>([\s\S]*?)<\/\1>/g;
    let m;
    let count = 0;
    while ((m = rx.exec(pom)) !== null) {
      const value = m[2];
      const base = m.index;
      let idx = -1;
      while ((idx = value.indexOf(PARTICLE, idx + PARTICLE.length)) >= 0) {
        count++;
        if (violations.length < MAX_VIOLATIONS) {
          violations.push({
            file: 'pom.xml',
            line: lineOf(pom, base + idx),
            rule: 'POM_METADATA',
            detail: `<${
              m[1]
            }> is published in the jar's embedded pom and on the project page`,
          });
        }
      }
    }
    stats.push({ file: 'pom.xml', mode: 'strict', rule: 'POM_METADATA', count, allowed: 0 });
  }

  // ---- 4. Java 字符串字面量（注释已剥离）--------------------------------------------
  const JAVA = path.join(root, 'src', 'main', 'java');
  if (!fs.existsSync(JAVA) || !fs.statSync(JAVA).isDirectory()) {
    return fatalOf(`missing directory: ${path.relative(root, JAVA)} -- the message tables are not being scanned`);
  }
  const javaSrc = javaFiles(JAVA);
  if (javaSrc.length === 0) {
    return fatalOf(`no .java files under ${path.relative(root, JAVA)} -- nothing to scan is not a pass`);
  }
  targets.javaFiles = javaSrc.length;

  for (const abs of javaSrc) {
    const rel = path.relative(root, abs).split(path.sep).join('/');
    let java;
    try {
      java = readJava(abs);
    } catch (err) {
      return fatalOf(`cannot read ${rel}: ${err.message}`);
    }
    let count = 0;
    let allowed = 0;
    for (const lit of stringLiterals(java.stripped)) {
      let idx = -1;
      while ((idx = lit.value.indexOf(PARTICLE, idx + PARTICLE.length)) >= 0) {
        count++;
        // 白名单同样只认整句，且在字面量自己的文本里找——避免跨字面量的伪豁免。
        if (insideAllowedSentence(allowedSpans(lit.value), idx)) {
          allowed++;
          continue;
        }
        if (violations.length >= MAX_VIOLATIONS) continue;
        // `lit.at` 是字面量在【已剥离注释】的源码里的起始偏移；stripComments 保留长度，
        // 因此它与原文行号一致。精确到字面量开头，而不是语气词在字面量内部的偏移
        // （后者在含转义时对不上原文）。
        violations.push({
          file: rel,
          line: lineOf(java.stripped, lit.at),
          rule: 'JAVA_MESSAGE',
          detail: `string literal (reaches players/console): ${JSON.stringify(lit.value.slice(0, 90))}`,
        });
      }
    }
    // 注释里的语气词不算（intentional: the tables' own javadoc discusses the rule）。
    const inComments = (java.raw.match(new RegExp(PARTICLE, 'g')) || []).length - count;
    stats.push({ file: rel, mode: 'java-literals', rule: 'JAVA_MESSAGE', count, allowed, inComments: Math.max(0, inComments) });
  }

  return { fatal: null, violations, stats, targets };
}

// ---------------------------------------------------------------------------------------
// 报告
// ---------------------------------------------------------------------------------------

function report(result) {
  const { fatal, violations, stats, targets } = result;

  if (fatal) {
    if (AS_JSON) {
      console.log(JSON.stringify({ ok: false, cannotRun: true, repo: REPO, error: fatal }, null, 2));
    } else {
      console.error(`ERROR: ${fatal}`);
      console.error('       the gate cannot decide anything without its inputs; refusing to report PASS.');
    }
    return EXIT_CANNOT_RUN;
  }

  if (AS_JSON) {
    console.log(JSON.stringify({ ok: violations.length === 0, repo: REPO, targets, stats, violations }, null, 2));
  } else {
    console.log('PerfScope product-docs particle check');
    console.log(`  repo  : ${REPO}`);
    console.log(`  inputs: ${targets.docs} doc(s), ${targets.jarResources} jar resource(s), `
      + `${targets.javaFiles} java file(s)${targets.pom ? ', pom.xml' : ''}`);
    console.log('');
    // Per-file lines are useful when they say something (violations elsewhere, a pinned-sentence
    // hit, binary skip). A 46-line wall of "0 java-literals" buries the one file that matters.
    let silent = 0;
    for (const s of stats) {
      if (s.mode === 'skipped') {
        console.log(`  ${'binary'.padEnd(13)} ${'  -'.padStart(4)}  ${s.file} (not text, skipped)`);
        continue;
      }
      let extra = '';
      if (s.mode === 'quoted-only') extra = ` (${s.quoted} quoted, allowed)`;
      else if (s.mode === 'java-literals') {
        extra = ` (${s.allowed ? `${s.allowed} in the pinned sentence, allowed; ` : ''}${s.inComments} in comments, not counted)`;
      } else if (s.allowed) extra = ` (${s.allowed} in the pinned sentence, allowed)`;
      const interesting = s.count > 0 || s.mode === 'quoted-only' || s.inComments > 0 || s.allowed > 0;
      if (!interesting) { silent++; continue; }
      console.log(`  ${s.mode.padEnd(13)} ${String(s.count).padStart(4)}  ${s.file}${extra}`);
    }
    if (silent > 0) console.log(`  ... ${silent} file(s) clean, 0 occurrences (not listed)`);
    console.log('');
    if (violations.length === 0) {
      console.log('DOCS CHECK: PASS');
    } else {
      console.log('Violations:');
      for (const v of violations) {
        console.log(`  [${v.rule}] ${v.file}:${v.line} -- ${v.detail}`);
      }
      console.log('');
      console.log(`DOCS CHECK: FAIL (${violations.length} violation(s))`);
    }
  }
  return violations.length === 0 ? EXIT_PASS : EXIT_VIOLATION;
}

// ---------------------------------------------------------------------------------------
// --selftest：用合成仓库证明六条判据都还活着
// ---------------------------------------------------------------------------------------
//
// 为什么要有这个：本门禁的六处空洞全部是"改坏了也全绿"的形状，靠"在当前仓库上跑一次 PASS"
// 永远发现不了 —— 干净的工作树本来就该 PASS。自检把每个空洞做成一次**应当变红/变 exit 2**
// 的变异，跑在 os.tmpdir 下的合成仓库里，不碰真实工作树。

function selftest() {
  const results = runSelftestCases();
  if (AS_JSON) {
    console.log(JSON.stringify({ ok: results.every((r) => r.ok), results }, null, 2));
  } else {
    console.log('PerfScope product-docs gate -- SELFTEST');
    console.log('');
    for (const r of results) {
      const verdict = r.ok ? 'OK  ' : 'FAIL';
      console.log(`  ${verdict}  ${r.id}  (expected exit ${r.expect}, got ${r.got}${r.named ? '' : '; wrong file named'})`);
    }
    const bad = results.filter((r) => !r.ok).length;
    console.log('');
    console.log(bad === 0
      ? `GATE SELFTEST: PASS (${results.length}/${results.length} -- every hole is still closed)`
      : `GATE SELFTEST: FAIL (${bad}/${results.length} case(s) regressed)`);
  }
  return results.every((r) => r.ok) ? EXIT_PASS : EXIT_VIOLATION;
}

/** 供正常调用时静默复用：只返回结果，不打印。 */
function runSelftestQuiet() {
  const results = runSelftestCases();
  return { ok: results.every((r) => r.ok), results };
}

function runSelftestCases() {
  const CASES = [
    {
      id: 'D3-whitelist-line-plus-second-leak',
      expect: EXIT_VIOLATION,
      mutate(fx) {
        const p = path.join(fx, 'README.md');
        const t = fs.readFileSync(p, 'utf8');
        fs.writeFileSync(p, t.replace(
          '> 如果喜欢这个项目就 Star 一下喵 ⭐',
          '> 如果喜欢这个项目就 Star 一下喵 ⭐ 另外别忘了看看文档喵。'));
      },
      mustMention: 'README.md',
    },
    {
      id: 'D4-plugin-yml-description',
      expect: EXIT_VIOLATION,
      mutate(fx) {
        const p = path.join(fx, 'src/main/resources/plugin.yml');
        const t = fs.readFileSync(p, 'utf8');
        fs.writeFileSync(p, t.replace(/^description: .*$/m, 'description: 服务器性能监测喵'));
      },
      mustMention: 'src/main/resources/plugin.yml',
    },
    {
      id: 'D5-config-yml-comment',
      expect: EXIT_VIOLATION,
      mutate(fx) {
        fs.appendFileSync(path.join(fx, 'src/main/resources/config.yml'), '\n# 这一行是注释喵\n');
      },
      mustMention: 'src/main/resources/config.yml',
    },
    {
      id: 'D6-new-doc-is-scanned-by-default',
      expect: EXIT_VIOLATION,
      mutate(fx) {
        fs.writeFileSync(path.join(fx, 'INSTALL.md'), '# 安装\n\n把 jar 丢进 plugins 目录就行喵。\n');
      },
      mustMention: 'INSTALL.md',
    },
    {
      id: 'D7-java-message-table',
      expect: EXIT_VIOLATION,
      mutate(fx) {
        const p = path.join(fx, 'src/main/java/cn/perfscope/command/CommandMessages.java');
        const t = fs.readFileSync(p, 'utf8');
        fs.writeFileSync(p, t.replace(
          'put(en, zh, "cmd.state.not-collected", "Not sampled", "未采集");',
          'put(en, zh, "cmd.state.not-collected", "Not sampled", "未采集喵");'));
      },
      mustMention: 'CommandMessages.java',
    },
    {
      id: 'D8-missing-repo-is-cannot-run',
      expect: EXIT_CANNOT_RUN,
      noFixture: true,
      // 每次现取一个不存在的路径，避免上一次的残留目录让"路径不存在"这个前提失效。
      repo: path.join(os.tmpdir(), `perfscope-check-docs-missing-${process.pid}-${Date.now()}`),
    },
    {
      id: 'R1-java-comment-is-allowed',
      expect: EXIT_PASS,
      mutate(fx) {
        const p = path.join(fx, 'src/main/java/cn/perfscope/util/Messages.java');
        fs.appendFileSync(p, '\n// 注释里讨论这条规则是允许的喵\n');
      },
    },
    {
      id: 'R2-changelog-quotation-is-allowed',
      expect: EXIT_PASS,
      mutate(fx) {
        fs.appendFileSync(path.join(fx, 'CHANGELOG.md'), '\n- 旧文案是「你没有权限使用此指令喵」，已改。\n');
      },
    },
    {
      id: 'R3-changelog-sentence-final-is-red',
      expect: EXIT_VIOLATION,
      mutate(fx) {
        fs.appendFileSync(path.join(fx, 'CHANGELOG.md'), '\n- 这一行以语气词结尾喵。\n');
      },
      mustMention: 'CHANGELOG.md',
    },
    {
      id: 'R4-dev-artifact-dir-is-out-of-scope',
      expect: EXIT_PASS,
      mutate(fx) {
        fs.appendFileSync(path.join(fx, 'tools/KNOWN-TEST-LIMITS.md'), '\n开发过程产物可以引用历史文案喵。\n');
      },
    },
    {
      id: 'R5-deleted-readme-is-cannot-run',
      expect: EXIT_CANNOT_RUN,
      mutate(fx) { fs.rmSync(path.join(fx, 'README.md')); },
    },
    {
      id: 'R6-no-docs-at-all-is-cannot-run',
      expect: EXIT_CANNOT_RUN,
      mutate(fx) {
        for (const f of fs.readdirSync(fx)) {
          const p = path.join(fx, f);
          if (f.endsWith('.md') && fs.statSync(p).isFile()) fs.rmSync(p);
        }
        fs.rmSync(path.join(fx, 'versions'), { recursive: true, force: true });
      },
    },
  ];

  const results = [];
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'perfscope-check-docs-selftest-'));
  try {
    for (const c of CASES) {
      let code;
      let out = '';
      if (c.noFixture) {
        const r = scanRepo(c.repo);
        code = r.fatal ? EXIT_CANNOT_RUN : (r.violations.length ? EXIT_VIOLATION : EXIT_PASS);
        out = r.fatal || '';
      } else {
        const fx = path.join(tmpRoot, c.id);
        makeFixture(fx);
        c.mutate(fx);
        const r = scanRepo(fx);
        code = r.fatal ? EXIT_CANNOT_RUN : (r.violations.length ? EXIT_VIOLATION : EXIT_PASS);
        out = r.fatal || r.violations.map((v) => v.file).join('\n');
      }
      const named = c.mustMention ? out.includes(c.mustMention) : true;
      results.push({ id: c.id, expect: c.expect, got: code, ok: code === c.expect && named, named });
    }
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
  return results;
}

/** 合成仓库：结构最小但覆盖四类扫描目标，且自身必须干净。 */
function makeFixture(root) {
  fs.rmSync(root, { recursive: true, force: true });
  const write = (rel, text) => {
    const p = path.join(root, rel);
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, text);
  };
  write('README.md', '# PerfScope\n\n> 如果喜欢这个项目就 Star 一下喵 ⭐\n\n安装见 FAQ。\n');
  write('CHANGELOG.md', '# 变更\n\n- 旧文案「重载完成喵~」已改为中性文案。\n');
  write('versions/README.md', '# versions\n\n本目录为本地构建存档。\n');
  write('tools/KNOWN-TEST-LIMITS.md', '# 已知限制\n\n测试体系做不到什么。\n');
  write('src/main/resources/plugin.yml', 'name: PerfScope\ndescription: Server performance monitor\n');
  write('src/main/resources/config.yml', '# 主配置\ngeneral:\n  update_interval: 20\n');
  write('pom.xml', '<project><name>PerfScope</name><description>性能监测</description></project>\n');
  write('src/main/java/cn/perfscope/util/Messages.java', [
    'package cn.perfscope.util;',
    '/** 消息层；两种语言一律不含口癖。 */',
    'public final class Messages {',
    '    private static final String A = "配置已重载";',
    '}',
    '',
  ].join('\n'));
  write('src/main/java/cn/perfscope/command/CommandMessages.java', [
    'package cn.perfscope.command;',
    'final class CommandMessages {',
    '    static void init() {',
    '        put(en, zh, "cmd.state.not-collected", "Not sampled", "未采集");',
    '    }',
    '}',
    '',
  ].join('\n'));
}

// ---------------------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------------------
//
// The selftest runs on EVERY normal invocation, not only under --selftest. Reason: the six
// holes this file was hardened for were all "the mutation stays green" shapes -- a clean tree
// reports PASS either way, so nothing would ever notice the detector decaying again. It costs
// about a second (12 synthetic repos) and it is the only way this gate can prove it still
// fails when it should. `--no-selftest` exists for callers that re-run the gate in a tight loop.

if (argv.includes('--selftest')) {
  process.exit(selftest());
} else {
  let selftestCode = EXIT_PASS;
  if (!argv.includes('--no-selftest')) {
    // Quiet unless something regressed: the selftest's own report is noise on a clean run.
    const st = runSelftestQuiet();
    if (!st.ok) {
      console.error('ERROR: gate selftest regressed -- this gate can no longer prove it catches the holes it exists for:');
      for (const r of st.results.filter((x) => !x.ok)) {
        console.error(`  [SELFTEST] ${r.id} -- expected exit ${r.expect}, got ${r.got}${r.named ? '' : '; wrong file named'}`);
      }
      console.error('       run `node tools/check-docs.mjs --selftest` for the full table.');
      selftestCode = EXIT_CANNOT_RUN;
    }
  }
  const result = scanRepo(REPO);
  const code = report(result);
  // A broken gate must never read as PASS, even when the tree happens to be clean.
  process.exit(code === EXIT_CANNOT_RUN ? code : Math.max(code, selftestCode));
}
