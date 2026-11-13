#!/usr/bin/env node
/**
 * check-docs.mjs -- L1 gate: product-facing docs must not contain the author's cat-speak particle.
 *
 * 背景：项目规则要求"产品文案不带语气词"，但这条规则长期只靠人记 —— 没有门禁。
 * 实测过的情况：README 与 config.yml 都曾经带着它发出去，CHANGELOG 更是累积了 331 处。
 * 清理一次不够，没有拦截就会再长回来。
 *
 * 判定口径（刻意区分"作者口癖"与"被引述的内容"）：
 *   README / FAQ / SECURITY / CONTRIBUTING / CLA / versions 等**纯产品文档**：必须为 0。
 *   CHANGELOG：允许出现，但只允许出现在**引述**里 —— 即后面紧跟 " ~ 」 ` 这四种字符，
 *   或整行属于代码围栏内的实测回放。句末语气词（后面跟 。或 ：）一律不允许。
 *
 * 为什么 CHANGELOG 要放宽：它的历史里有「控制台回放」「被修掉的消息串原文」「i18n 可配置化决策」
 * 这类内容，"喵" 在那里是**被讨论的对象**，删掉会伪造历史。规则写死为"只禁句末语气词"，
 * 比维护一份行号白名单可靠。
 *
 * 用法：
 *   node tools/check-docs.mjs
 *   node tools/check-docs.mjs --repo <path>
 *   node tools/check-docs.mjs --json
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
// This script lives at <repo>/tools/, so one level up IS the repo.
const REPO = path.resolve(argOf('--repo', path.join(HERE, '..')));
const AS_JSON = argv.includes('--json');

/** 语气词本体。集中在这里，改名词时只改一处。 */
const PARTICLE = '喵';

/** 必须为 0 的纯产品文档。缺失的文件跳过（不是所有仓库都有 FAQ 等）。 */
const STRICT_FILES = [
  'README.md',
  'README-EN.md',
  'FAQ.md',
  'SECURITY.md',
  'CONTRIBUTING.md',
  'CLA.md',
  'versions/README.md',
];

/** 允许引述、但禁止句末语气词的文件。 */
const QUOTING_FILES = ['CHANGELOG.md'];

/**
 * 在 CHANGELOG 里，"引述"的判据是语气词后面紧跟这些字符之一。
 * 句末语气词的典型形态是「喵。」与「喵：」—— 这两种一律算违规。
 */
const QUOTE_SUFFIXES = ['"', '~', '」', '`', '”'];

const violations = [];
const stats = [];

function readIfExists(rel) {
  const p = path.join(REPO, rel);
  if (!fs.existsSync(p)) return null;
  return fs.readFileSync(p, 'utf8');
}

function lineOf(text, index) {
  let n = 1;
  for (let i = 0; i < index && i < text.length; i++) {
    if (text[i] === '\n') n++;
  }
  return n;
}

// ---- 严格文件：任何一处都是违规 ----
for (const rel of STRICT_FILES) {
  const text = readIfExists(rel);
  if (text == null) continue;
  let count = 0;
  let idx = -1;
  while ((idx = text.indexOf(PARTICLE, idx + 1)) >= 0) {
    count++;
    if (violations.length < 50) {
      violations.push({
        file: rel,
        line: lineOf(text, idx),
        rule: 'STRICT_FILE',
        detail: 'product documentation must not contain the particle at all',
      });
    }
  }
  stats.push({ file: rel, mode: 'strict', count });
}

// ---- 引述文件：只禁句末语气词 ----
for (const rel of QUOTING_FILES) {
  const text = readIfExists(rel);
  if (text == null) continue;
  let total = 0;
  let quoted = 0;
  let idx = -1;
  while ((idx = text.indexOf(PARTICLE, idx + 1)) >= 0) {
    total++;
    const next = text[idx + PARTICLE.length] ?? '';
    if (QUOTE_SUFFIXES.includes(next)) {
      quoted++;
      continue;
    }
    if (violations.length < 50) {
      violations.push({
        file: rel,
        line: lineOf(text, idx),
        rule: 'SENTENCE_PARTICLE',
        detail: `particle followed by ${JSON.stringify(next)} -- looks like a sentence-final particle, not a quotation`,
      });
    }
  }
  stats.push({ file: rel, mode: 'quoted-only', count: total, quoted });
}

// ---- 报告 ----
if (AS_JSON) {
  console.log(JSON.stringify({ ok: violations.length === 0, stats, violations }, null, 2));
} else {
  console.log('PerfScope product-docs particle check');
  console.log(`  repo : ${REPO}`);
  console.log('');
  for (const s of stats) {
    const extra = s.mode === 'quoted-only' ? ` (${s.quoted} quoted, allowed)` : '';
    console.log(`  ${s.mode.padEnd(12)} ${String(s.count).padStart(4)}  ${s.file}${extra}`);
  }
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

process.exit(violations.length === 0 ? 0 : 1);
