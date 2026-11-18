#!/usr/bin/env node
/**
 * check-readme-version.mjs -- README 徽章里的版本号必须与 pom.xml 一致。
 *
 * 背景：README 的徽章写死了版本号（`version-1.4.5--SNAPSHOT--fix1` 这种），而版本号的唯一
 * 真源是 pom.xml 的 `<version>`。参照仓库 dsh-fcl-android-launcher 的 README 也有同一个徽章，
 * 并在正文里靠「以 Releases 页为准」这句话兜底——那是文字约定，靠人记。
 *
 * 这里要防的是一种具体的静默失效：bump 了 pom、忘了改 README，于是仓库首页长期挂着一个
 * 过期的版本号。徽章是访客看到的第一个数字，它错了比正文写错更显眼，却没有任何东西会报警。
 *
 * 判定口径：
 *   - 从 pom.xml 取 `<version>`（在 `<artifactId>PerfScope</artifactId>` 之后那一行，
 *     避免误取依赖的版本）。
 *   - 在 README.md / README-EN.md 里找 `badge/version-<X>` 形式的徽章，把 shields.io 的
 *     转义还原（`--` → `-`，`__` → `_`，`%20` → 空格）后与 pom 版本逐字比对。
 *   - 文件里没有版本徽章时跳过（不是所有仓库都放）。
 *
 * 为什么不用「README 里出现任何版本号都算违规」：README 正文合法地会提到历史版本
 * （升级注意、行为变更加在哪个版本），那些必须能写。
 *
 * 用法：
 *   node tools/check-readme-version.mjs
 *   node tools/check-readme-version.mjs --repo <path>
 *   node tools/check-readme-version.mjs --json
 *
 * 退出码：0 = PASS，1 = 不一致，2 = 用法/路径错误
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

const README_FILES = ['README.md', 'README-EN.md'];

function read(p) {
  return fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : null;
}

/** shields.io 的静态徽章转义：`-`→`--`、`_`→`__`，空格有时写 `%20`。 */
function unescapeShields(s) {
  return s.replace(/--/g, '-').replace(/__/g, '_').replace(/%20/g, ' ');
}

const results = [];
const problems = [];

const pom = read(path.join(REPO, 'pom.xml'));
if (pom == null) {
  console.error(`check-readme-version: no pom.xml under ${REPO}`);
  process.exit(2);
}

// 取本项目自己的 <version>：紧跟 <artifactId>PerfScope</artifactId> 的那一个。
const ownVersion = (() => {
  const m = pom.match(/<artifactId>\s*PerfScope\s*<\/artifactId>\s*\n\s*<version>\s*([^<]+?)\s*<\/version>/);
  if (m) return m[1];
  // 退路：第一个 <version>（本 pom 的 project 段在最前）。
  const m2 = pom.match(/<version>\s*([^<]+?)\s*<\/version>/);
  return m2 ? m2[1] : null;
})();

if (!ownVersion) {
  console.error('check-readme-version: cannot read <version> from pom.xml');
  process.exit(2);
}

for (const rel of README_FILES) {
  const text = read(path.join(REPO, rel));
  if (text == null) continue;
  // 只认 shields.io 的静态版本徽章：badge/version-<encoded>-<color>
  // 版本段本身可能带 `-`（已转义成 `--`），颜色段是最后一段且不含 `-`，
  // 所以从末尾剥掉 `-<color>`，剩下的才是版本。
  const re = /badge\/version-([^)?\s]+)/g;
  const found = [];
  let m;
  while ((m = re.exec(text)) !== null) {
    let encoded = m[1];
    const cut = encoded.lastIndexOf('-');
    if (cut > 0) encoded = encoded.slice(0, cut);
    found.push({ raw: m[1], plain: unescapeShields(encoded) });
  }

  if (found.length === 0) {
    results.push({ file: rel, mode: 'no-badge', ok: true });
    continue;
  }
  const bad = found.filter((f) => f.plain !== ownVersion);
  results.push({
    file: rel,
    mode: 'badge',
    ok: bad.length === 0,
    expected: ownVersion,
    found: found.map((f) => f.plain),
  });
  for (const b of bad) {
    problems.push({
      file: rel,
      detail: `badge says "${b.plain}" but pom.xml says "${ownVersion}"`,
    });
  }
}

const ok = problems.length === 0;

if (AS_JSON) {
  console.log(JSON.stringify({ ok, pomVersion: ownVersion, results, problems }, null, 2));
} else {
  console.log('PerfScope README version badge check');
  console.log(`  repo : ${REPO}`);
  console.log(`  pom  : ${ownVersion}`);
  console.log('');
  for (const r of results) {
    if (r.mode === 'no-badge') {
      console.log(`  skip        ${r.file} (no version badge)`);
    } else {
      const mark = r.ok ? 'ok   ' : 'FAIL ';
      console.log(`  ${mark}      ${r.file} badge=${r.found.join(', ')}`);
    }
  }
  console.log('');
  if (ok) {
    console.log('README VERSION CHECK: PASS');
  } else {
    console.log('Mismatches:');
    for (const p of problems) console.log(`  ${p.file}: ${p.detail}`);
    console.log('');
    console.log('Fix: update the badge, or bump pom.xml. The pom is the single source of truth.');
    console.log('README VERSION CHECK: FAIL');
  }
}

process.exit(ok ? 0 : 1);
