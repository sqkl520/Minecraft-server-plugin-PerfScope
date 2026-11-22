#!/usr/bin/env node
/*
 * PerfScope - Minecraft 服务器性能监测插件
 * Copyright (C) 2026 PerfScope Team
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * check-protocol-fixtures-sync.mjs -- L2 gate: 两个仓库的协议 fixture 必须逐字节相同。
 *
 * <h2>它拦的是什么</h2>
 *
 * 跨仓库 golden fixture 的整个价值建立在"两边是同一份字节"上。两个单测各自能保证的是：
 *
 *   - 后端 `NetworkProtocolFixtureTest`：`toMap()` 的输出 == 磁盘上的 fixture
 *   - 代理 `ProtocolFixtureTest`：磁盘上的 fixture 能被 parse 成期望的字段值
 *
 * 但**没有任何单测能看见另一个仓库**（它们在不同的构建里跑，不同的 JVM，不同的工作目录）。
 * 于是有一种状态是两份单测都绿、而契约已经断掉的：
 *
 *   后端改了 `toMap()` → 后端重生成 fixture（后端绿）
 *                      → 忘了复制到代理（代理那份还是旧的，代理也绿）
 *
 * 这时候后端的字节已经变了，代理还在拿旧字节验"我能读懂"——它测的是一个**已经不存在**
 * 的协议。这个状态可以一直持续到某天有人想起同步，而中间每一次发布都带着"两侧测试全绿"的
 * 假象。这正是 golden fixture 最典型的失效方式（Fowler 称之为 "golden master drift"）。
 *
 * 所以同步性必须由**仓库外的东西**来检查——这也是本项目已有的做法：
 * `check-velocity-thresholds.mjs` 与 `check-bstats-buckets.mjs` 都是同一范式，
 * 因为"约束只能跨仓库显现时，把约束上升到源码/文件层"。
 *
 * <h2>判定口径</h2>
 *
 * 1. 两个目录里的**文件集合**必须完全相同（不多不少）。少一个 = 那一侧漏了同步。
 * 2. 每个同名文件的**规范化字节**必须完全相同。规范化只做一件事：去掉末尾的 CR/LF
 *    （编辑器自动补的那个换行不是协议内容，两侧的 `normalize()` 也是这么做的）。
 * 3. `FIXTURES.sha256` 索引在两个仓库里也必须逐字节相同，且它登记的值要与实际字节一致——
 *    否则"两边都是同一份错的索引"这种情况会被漏掉。
 *
 * 刻意**不**检查"fixture 里的字段名对不对"：那是两个单测的职责（它们会真的跑 toMap/parse）。
 * 本脚本只回答一件事：**两边是不是同一份字节**。职责分开，失败信息才可读。
 *
 * 用法：
 *   node tools/check-protocol-fixtures-sync.mjs
 *   node tools/check-protocol-fixtures-sync.mjs --backend . --velocity ../PerfScope-Velocity
 *   node tools/check-protocol-fixtures-sync.mjs --json
 *
 * 退出码：0 = PASS，1 = 不一致，2 = 用法/路径错误
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : dflt;
};

// tools/ 在仓库内，所以上一级就是仓库根；代理仓库是同级的另一个仓库。
const BACKEND = path.resolve(argOf('--backend', path.join(HERE, '..')));
const VELOCITY = path.resolve(argOf('--velocity', path.join(HERE, '..', '..', 'PerfScope-Velocity')));
const AS_JSON = argv.includes('--json');

const REL_DIR = path.join('src', 'test', 'resources', 'protocol');
const INDEX_FILE = 'FIXTURES.sha256';

class UsageError extends Error {}

function readDirFiles(dir, label) {
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
    throw new UsageError(
      `找不到 ${label} 的 fixture 目录：${dir}\n` +
      `  （期望位置：<repo>/${REL_DIR}）\n` +
      `  后端默认按 tools/ 的上一级推断，代理默认按同级的 PerfScope-Velocity 推断。\n` +
      `  布局不同时用 --backend / --velocity 显式指定。`);
  }
  return new Set(fs.readdirSync(dir).filter((f) => f.endsWith('.json') || f === INDEX_FILE));
}

/**
 * 去掉末尾 CR/LF 后的字节。
 *
 * 为什么允许这个差异：两侧的单元测试都做同样的规范化（`normalize()`），
 * 而编辑器常给 JSON 文件自动补一个换行。把它算成"不一致"只会制造噪音，
 * 且与单测的口径不一致——那种不一致本身就会让门禁与测试打架。
 */
function normalize(buf) {
  let end = buf.length;
  while (end > 0 && (buf[end - 1] === 0x0a || buf[end - 1] === 0x0d)) end--;
  return buf.subarray(0, end);
}

function sha256(buf) {
  return crypto.createHash('sha256').update(normalize(buf)).digest('hex');
}

/** 解析 `FIXTURES.sha256`：`<文件名>  <sha256>` 每行一条，`#` 开头为注释。 */
function parseIndex(buf) {
  const out = new Map();
  for (const raw of buf.toString('utf8').split('\n')) {
    const line = raw.trim();
    if (line === '' || line.startsWith('#')) continue;
    const parts = line.split(/\s+/);
    if (parts.length !== 2) {
      throw new UsageError(`FIXTURES.sha256 行格式应为「文件名  sha256」：${raw}`);
    }
    out.set(parts[0], parts[1]);
  }
  return out;
}

function main() {
  const backendDir = path.join(BACKEND, REL_DIR);
  const velocityDir = path.join(VELOCITY, REL_DIR);

  const backendFiles = readDirFiles(backendDir, '后端仓库');
  const velocityFiles = readDirFiles(velocityDir, '代理仓库');

  const problems = [];
  const checks = [];

  // ---- 1. 文件集合必须完全相同 ----
  const onlyBackend = [...backendFiles].filter((f) => !velocityFiles.has(f)).sort();
  const onlyVelocity = [...velocityFiles].filter((f) => !backendFiles.has(f)).sort();
  if (onlyBackend.length) {
    problems.push(
      `后端有、代理没有：${onlyBackend.join(', ')}\n` +
      `  → 后端重生成 fixture 之后忘了复制到代理仓库。` +
      `这一步不能省：代理那份单测测的是「这一份字节」，少了它等于这个场景在代理侧没有覆盖。`);
  }
  if (onlyVelocity.length) {
    problems.push(
      `代理有、后端没有：${onlyVelocity.join(', ')}\n` +
      `  → 代理侧的 fixture 不允许单独存在。fixture 的唯一起源是后端 ` +
      `NetworkProtocolFixtureTest 的 toMap() —— 手写的文件测不出真实输出漂移。`);
  }
  checks.push({
    name: '文件集合一致',
    ok: onlyBackend.length === 0 && onlyVelocity.length === 0,
    detail: `后端 ${backendFiles.size} 个 / 代理 ${velocityFiles.size} 个`,
  });

  // ---- 2. 同名文件逐字节相同 ----
  const common = [...backendFiles].filter((f) => velocityFiles.has(f)).sort();
  const byteMismatch = [];
  for (const name of common) {
    const a = fs.readFileSync(path.join(backendDir, name));
    const b = fs.readFileSync(path.join(velocityDir, name));
    if (!normalize(a).equals(normalize(b))) {
      byteMismatch.push({ name, backendSha: sha256(a), velocitySha: sha256(b) });
    }
  }
  if (byteMismatch.length) {
    problems.push(
      `字节不一致：\n` +
      byteMismatch.map((m) =>
        `  - ${m.name}\n` +
        `      后端 ${m.backendSha}\n` +
        `      代理 ${m.velocitySha}`).join('\n') +
      `\n  → 两个仓库的契约已经分叉。代理那份单测测的是一份**已经不存在的协议**。` +
      `\n     修法：在后端仓库跑 mvn -B -ntp test -Dtest=NetworkProtocolFixtureTest ` +
      `-Dperfscope.protocol.regenerate=true，然后把整个 protocol/ 目录复制到代理仓库。`);
  }
  checks.push({
    name: '同名文件字节一致',
    ok: byteMismatch.length === 0,
    detail: `比对 ${common.length} 个文件`,
  });

  // ---- 3. 索引与实际字节一致（两个仓库各查一次） ----
  for (const [label, dir] of [['后端', backendDir], ['代理', velocityDir]]) {
    const indexPath = path.join(dir, INDEX_FILE);
    if (!fs.existsSync(indexPath)) {
      problems.push(`${label}仓库缺少 ${INDEX_FILE}`);
      checks.push({ name: `${label}索引存在`, ok: false, detail: '文件缺失' });
      continue;
    }
    const index = parseIndex(fs.readFileSync(indexPath));
    const mismatches = [];
    for (const [name, expected] of index) {
      const filePath = path.join(dir, name);
      if (!fs.existsSync(filePath)) {
        mismatches.push(`${name}: 索引里登记了但文件不存在`);
        continue;
      }
      const actual = sha256(fs.readFileSync(filePath));
      if (actual !== expected) {
        mismatches.push(`${name}: 索引 ${expected.slice(0, 12)}… / 实际 ${actual.slice(0, 12)}…`);
      }
    }
    // 反向：目录里有、索引里没登记（新增 fixture 忘了写索引）
    for (const name of common) {
      if (name !== INDEX_FILE && !index.has(name)) {
        mismatches.push(`${name}: 文件存在但索引未登记`);
      }
    }
    if (mismatches.length) {
      problems.push(`${label}仓库的 ${INDEX_FILE} 与实际字节不符：\n` +
        mismatches.map((m) => `  - ${m}`).join('\n'));
    }
    checks.push({
      name: `${label}索引一致`,
      ok: mismatches.length === 0,
      detail: `登记 ${index.size} 条`,
    });
  }

  const pass = problems.length === 0;
  if (AS_JSON) {
    console.log(JSON.stringify({ pass, backend: BACKEND, velocity: VELOCITY, checks, problems }, null, 2));
  } else {
    console.log(`check-protocol-fixtures-sync: ${pass ? 'PASS' : 'FAIL'}`);
    console.log(`  后端 ${path.join(backendDir)}`);
    console.log(`  代理 ${path.join(velocityDir)}`);
    for (const c of checks) {
      console.log(`  [${c.ok ? 'OK  ' : 'FAIL'}] ${c.name}（${c.detail}）`);
    }
    if (!pass) {
      console.log('\n问题：');
      for (const p of problems) console.log(`  ${p}`);
    }
  }
  return pass ? 0 : 1;
}

try {
  process.exit(main());
} catch (e) {
  if (e instanceof UsageError) {
    console.error(`check-protocol-fixtures-sync: ${e.message}`);
    process.exit(2);
  }
  throw e;
}
