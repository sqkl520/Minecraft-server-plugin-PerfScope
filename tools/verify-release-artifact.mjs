#!/usr/bin/env node
// verify-release-artifact.mjs -- L2 gate: "the jar we are about to publish IS this commit".
//
// The incident this exists for: versions/PerfScope-1.2.1.jar trailed the source by 6 commits
// (and in the end by 9). The published jar had config.yml WITHOUT general.language and a
// messages.yml with 14 keys instead of 672 -- while README taught users to set
// general.language. A stranger installs it and concludes the docs are fake. Nothing detected it,
// because nothing ever compared the archive to the source.
//
// Four independent comparisons, deliberately overlapping (any one alone can be fooled):
//
//   [1] SHA256      archived jar bytes == freshly built jar bytes.
//                   Catches "you copied the wrong file". Cheapest and strongest, but only
//                   works when the archive was produced by the very same build.
//   [2] CONTENT     every class/resource entry inside the archived jar is byte-identical to
//                   the same entry in the fresh build. Catches "same name, older build" and
//                   survives non-reproducible archive headers (zip timestamps).
//   [3] CONFIG      every key in src/main/resources/config.yml exists in the jar's config.yml,
//                   and the jar's config.yml has no key the source does not. Catches exactly
//                   the 1.2.1 incident: the documented general.language was simply absent.
//   [4] MESSAGES    number of distinct message keys compiled into the jar's message classes
//                   >= the count in the source tables. Catches the 14-vs-672 regression
//                   without needing a decompiler (reads the JVM constant pool).
//
// Plus [5] GIT: commits between the release tag and HEAD, and dirty-tree state. This is the
// bookkeeping half -- it answers "was the tag even at the commit that was built".
//
// Usage:
//   node tools/verify-release-artifact.mjs                          # auto-discovers version
//   node tools/verify-release-artifact.mjs --version 1.3.0
//   node tools/verify-release-artifact.mjs --jar <path> --target <path>
//   node tools/verify-release-artifact.mjs --skip-fresh-build       # compare against existing target/
//   node tools/verify-release-artifact.mjs --json
//
// Exit: 0 = all comparisons agree, 1 = at least one FAIL, 2 = could not run (missing input).
//
// Design note: this script never writes to the repo and never bumps anything. It only looks.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : dflt;
};
const flag = (name) => argv.includes(name);

// The script used to live in the PARENT workspace (../PerfScope relative to itself resolved to
// this repo). It now lives INSIDE the repo at tools/, so the default must be one level up --
// otherwise --repo resolves to <repo>/PerfScope/PerfScope and every check dies on "pom.xml not
// found" (observed 2026-11-09). Pass --repo explicitly to override.
const REPO = path.resolve(argOf('--repo', path.join(HERE, '..')));
const AS_JSON = flag('--json');
const SKIP_BUILD = flag('--skip-fresh-build');
const ALLOW_DIRTY = flag('--allow-dirty');

const results = [];
const rec = (id, ok, detail) => results.push({ id, ok, detail });
const fail = (id, detail) => rec(id, false, detail);
const pass = (id, detail) => rec(id, true, detail);
const info = (msg) => { if (!AS_JSON) console.log(msg); };

// --------------------------------------------------------------------------------------
// small helpers
// --------------------------------------------------------------------------------------
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const sha256File = (f) => sha256(fs.readFileSync(f));

/** Read pom <version> and <artifactId> without an XML parser. */
function readPom() {
  const xml = fs.readFileSync(path.join(REPO, 'pom.xml'), 'utf8');
  const strip = xml.replace(/<!--[\s\S]*?-->/g, '');
  const artifactId = (strip.match(/<artifactId>\s*([^<]+?)\s*<\/artifactId>/) || [])[1];
  // the project-level <version> is the first one that is NOT inside <parent> or a dependency
  const body = strip.replace(/<parent>[\s\S]*?<\/parent>/g, '')
    .replace(/<dependencies>[\s\S]*?<\/dependencies>/g, '')
    .replace(/<build>[\s\S]*?<\/build>/g, '');
  const version = (body.match(/<version>\s*([^<]+?)\s*<\/version>/) || [])[1];
  return { artifactId, version };
}

// ---- minimal ZIP reader (no deps; jars are plain zip32 with stored/deflate entries) ----
import zlib from 'node:zlib';

function readZip(file) {
  const buf = fs.readFileSync(file);
  // End of central directory: scan backwards for 0x06054b50
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error(`not a zip: ${file}`);
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const entries = new Map();
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error(`bad central dir at ${off}`);
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const uncompSize = buf.readUInt32LE(off + 24);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.slice(off + 46, off + 46 + nameLen).toString('utf8');
    // local header -> data start
    if (buf.readUInt32LE(localOff) !== 0x04034b50) throw new Error(`bad local header for ${name}`);
    const lNameLen = buf.readUInt16LE(localOff + 26);
    const lExtraLen = buf.readUInt16LE(localOff + 28);
    const dataStart = localOff + 30 + lNameLen + lExtraLen;
    const raw = buf.slice(dataStart, dataStart + compSize);
    entries.set(name, {
      method, compSize, uncompSize, raw,
      data: () => (method === 0 ? raw : zlib.inflateRawSync(raw)),
    });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

/** JVM class files: extract CONSTANT_Utf8 entries (tag 1) from the constant pool. */
function classUtf8(buf) {
  if (buf.length < 10 || buf.readUInt32BE(0) !== 0xCAFEBABE) return [];
  const poolCount = buf.readUInt16BE(8);
  const out = [];
  let p = 10;
  for (let i = 1; i < poolCount; i++) {
    const tag = buf[p];
    switch (tag) {
      case 1: { // Utf8
        const len = buf.readUInt16BE(p + 1);
        out.push(buf.slice(p + 3, p + 3 + len).toString('utf8'));
        p += 3 + len;
        break;
      }
      case 7: case 8: case 16: case 19: case 20: p += 3; break;      // Class, String, MethodType, Module, Package
      case 15: p += 4; break;                                        // MethodHandle
      case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: p += 5; break;
      case 5: case 6: p += 9; i++; break;                            // Long, Double take two slots
      default: return out;                                           // unknown tag: stop safely
    }
  }
  return out;
}

// ---- tiny indentation-based YAML key walker (config.yml only: maps + lists, no anchors) ----
function yamlKeys(text) {
  const keys = new Set();
  const stack = [];       // [{ indent, name }]
  let inBlockScalar = false;
  let blockIndent = -1;
  for (const line of text.split(/\r?\n/)) {
    if (/^\s*#/.test(line) || /^\s*$/.test(line)) continue;
    const indent = line.match(/^ */)[0].length;
    if (inBlockScalar) {
      if (indent > blockIndent) continue;
      inBlockScalar = false;
    }
    const m = line.match(/^(\s*)(-\s+)?([A-Za-z0-9_.-]+)\s*:(.*)$/);
    if (!m) {
      const m2 = line.match(/^(\s*)-\s*(.*)$/);
      if (m2) {
        // list item: keep the parent key as a leaf-ish key, and note the value form
        continue;
      }
      continue;
    }
    const name = m[3];
    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    const dotted = [...stack.map(s => s.name), name].join('.');
    keys.add(dotted);
    const rest = m[4].trim();
    if (rest === '|' || rest === '>' || rest === '|-' || rest === '>-') {
      inBlockScalar = true;
      blockIndent = indent;
    }
    // a key with an empty value may still be a map parent; only push if the next useful line
    // is deeper. Pushing unconditionally is fine: the pop loop above removes it when depth
    // does not increase.
    if (rest === '') stack.push({ indent, name });
  }
  return keys;
}

const git = (args) => {
  try {
    return execFileSync('git', args, { cwd: REPO, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  } catch (e) {
    return null;
  }
};

// ======================================================================================
// 0. Resolve inputs
// ======================================================================================
info('');
info('PerfScope release artifact verification');
info(`  repo : ${REPO}`);

if (!fs.existsSync(path.join(REPO, 'pom.xml'))) {
  console.error(`ERROR: pom.xml not found in ${REPO}`);
  process.exit(2);
}

let pom;
try {
  pom = readPom();
} catch (e) {
  console.error(`ERROR: cannot read pom.xml: ${e.message}`);
  process.exit(2);
}
const VERSION = argOf('--version', pom.version);
const JAR_NAME = `${pom.artifactId}-${VERSION}.jar`;
info(`  pom  : ${pom.artifactId} ${VERSION}${VERSION !== pom.version ? ` (pom says ${pom.version})` : ''}`);
if (VERSION !== pom.version) {
  fail('pom version matches requested version',
    `--version ${VERSION} but pom.xml declares ${pom.version}; the build would produce ${pom.artifactId}-${pom.version}.jar`);
}

const ARCHIVE = path.resolve(argOf('--jar', path.join(REPO, 'versions', JAR_NAME)));
const TARGET = path.resolve(argOf('--target', path.join(REPO, 'target', JAR_NAME)));

if (!fs.existsSync(ARCHIVE)) {
  info('');
  info(`  archive : MISSING  ${ARCHIVE}`);
  info(`  Run tools/archive-release.ps1 first, then re-run this check.`);
  console.error(`RESULT: FAIL (archive jar not found)`);
  process.exit(2);
}

// refresh the fresh build unless told not to
if (!SKIP_BUILD) {
  info('');
  info(`  building fresh jar from HEAD (this is what "consistent with source" means) ...`);
  // `mvn` is frequently NOT on PATH on this machine (CLAUDE.md pins it to
  // D:\maven\apache-maven-3.9.9\bin\mvn.cmd). Trying the bare name first made the build check
  // fail with spawnSync ENOENT and poisoned the whole run -- a misleading FAIL on a check that
  // had nothing to do with the artifact. Resolve in order: explicit env, PATH, known local path.
  const mvnCandidates = [
    process.env.PSCOPE_MVN,
    'mvn',
    'D:\\maven\\apache-maven-3.9.9\\bin\\mvn.cmd',
  ].filter(Boolean);
  let built = false;
  let buildErr = '';
  for (const mvn of mvnCandidates) {
    try {
      // On Windows a .cmd shim cannot be spawned directly (EINVAL) -- it needs a shell.
      const isWinShim = /\.(cmd|bat)$/i.test(mvn);
      execFileSync(mvn, ['-B', '-ntp', '-q', 'clean', 'package', '-DskipTests'], {
        cwd: REPO, stdio: ['ignore', 'inherit', 'inherit'], shell: isWinShim,
      });
      built = true;
      break;
    } catch (e) {
      buildErr = `${mvn}: ${e.message}`;
    }
  }
  if (!built) {
    fail('fresh build succeeds', `mvn clean package failed; no candidate worked (${buildErr})`);
  }
}

const buildOk = !results.some(r => r.id === 'fresh build succeeds' && !r.ok);
if (!fs.existsSync(TARGET) && buildOk) {
  fail('fresh jar exists', `expected ${TARGET}; run mvn clean package in ${REPO}`);
}
if (!fs.existsSync(TARGET)) {
  console.error('RESULT: FAIL (no fresh jar to compare against)');
  process.exit(2);
}

let archived, fresh;
try {
  archived = { bytes: fs.statSync(ARCHIVE).size, sha256: sha256File(ARCHIVE), zip: readZip(ARCHIVE) };
  fresh = { bytes: fs.statSync(TARGET).size, sha256: sha256File(TARGET), zip: readZip(TARGET) };
} catch (e) {
  console.error(`ERROR: cannot read jar(s): ${e.message}`);
  process.exit(2);
}

info(`  archive: ${path.basename(ARCHIVE)}  ${archived.bytes} B  sha ${archived.sha256.slice(0, 12)}`);
info(`  fresh  : ${path.basename(TARGET)}  ${fresh.bytes} B  sha ${fresh.sha256.slice(0, 12)}`);

// ======================================================================================
// 1. SHA256
// ======================================================================================
if (archived.sha256 === fresh.sha256) {
  pass('SHA256 archive == fresh build', `${archived.sha256.slice(0, 16)}... (${archived.bytes} B)`);
} else {
  fail('SHA256 archive == fresh build',
    `archive ${archived.sha256.slice(0, 12)} != fresh ${fresh.sha256.slice(0, 12)} `
    + `(${archived.bytes} B vs ${fresh.bytes} B). Either the archive is from an older build, `
    + `or the build is not byte-reproducible here -- see the CONTENT check below, which can `
    + `distinguish the two.`);
}

// ======================================================================================
// 2. CONTENT: every entry byte-identical
// ======================================================================================
const isFunc = (n) => n.endsWith('.class') || n.endsWith('.yml') || n.endsWith('.yaml')
  || n.endsWith('.json') || n.endsWith('.properties') || n.endsWith('.txt');
const onlyInArchive = [];
const onlyInFresh = [];
const differs = [];
for (const [name, e] of archived.zip) {
  if (!isFunc(name)) continue;
  const f = fresh.zip.get(name);
  if (!f) { onlyInArchive.push(name); continue; }
  const a = sha256(e.data());
  const b = sha256(f.data());
  if (a !== b) differs.push(name);
}
for (const name of fresh.zip.keys()) {
  if (isFunc(name) && !archived.zip.has(name)) onlyInFresh.push(name);
}

if (onlyInArchive.length === 0 && onlyInFresh.length === 0 && differs.length === 0) {
  pass('CONTENT archive entries == fresh entries',
    `${[...archived.zip.keys()].filter(isFunc).length} functional entries byte-identical`);
} else {
  const detail = [
    differs.length ? `${differs.length} entries differ (e.g. ${differs.slice(0, 4).join(', ')})` : '',
    onlyInFresh.length ? `${onlyInFresh.length} entries missing from archive (e.g. ${onlyInFresh.slice(0, 4).join(', ')})` : '',
    onlyInArchive.length ? `${onlyInArchive.length} entries exist only in archive (e.g. ${onlyInArchive.slice(0, 4).join(', ')})` : '',
  ].filter(Boolean).join('; ');
  fail('CONTENT archive entries == fresh entries', `${detail} -- the archive is NOT built from this source`);
}

// ======================================================================================
// 3. CONFIG: config.yml keys, both directions
// ======================================================================================
function jarYamlText(zipMap, name) {
  const e = zipMap.get(name);
  return e ? e.data().toString('utf8') : null;
}
const srcConfigKeys = yamlKeys(fs.readFileSync(path.join(REPO, 'src', 'main', 'resources', 'config.yml'), 'utf8'));
const archConfig = jarYamlText(archived.zip, 'config.yml');
const freshConfig = jarYamlText(fresh.zip, 'config.yml');

if (archConfig == null) {
  fail('CONFIG jar contains config.yml', 'config.yml is missing from the jar root');
} else {
  const archKeys = yamlKeys(archConfig);
  const missingInJar = [...srcConfigKeys].filter(k => !archKeys.has(k));
  const extraInJar = [...archKeys].filter(k => !srcConfigKeys.has(k));
  if (missingInJar.length === 0 && extraInJar.length === 0) {
    pass('CONFIG keys archive == source', `${archKeys.size} keys`);
  } else {
    fail('CONFIG keys archive == source',
      `${missingInJar.length} source keys absent from archive (e.g. ${missingInJar.slice(0, 6).join(', ')})`
      + (extraInJar.length ? `; ${extraInJar.length} archive-only keys (e.g. ${extraInJar.slice(0, 6).join(', ')})` : '')
      + ' -- this is the 1.2.1 incident shape: documented settings that do not exist in the published jar');
  }
  // fresh build sanity: the fresh jar must match the source (if not, the build is broken, not the archive)
  if (freshConfig != null) {
    const fk = yamlKeys(freshConfig);
    const freshMissing = [...srcConfigKeys].filter(k => !fk.has(k));
    rec('CONFIG fresh build matches source', freshMissing.length === 0,
      freshMissing.length ? `fresh jar is missing ${freshMissing.length} source keys (e.g. ${freshMissing.slice(0, 6).join(', ')})`
        : `${fk.size} keys`);
  }
}

// release-critical config keys that the docs promise (v1.3.0 acceptance criterion)
const PROMISED_KEYS = ['general.language'];
if (archConfig != null) {
  const archKeys = yamlKeys(archConfig);
  const absent = PROMISED_KEYS.filter(k => !archKeys.has(k));
  rec('CONFIG promised keys present in archive', absent.length === 0,
    absent.length ? `missing ${absent.join(', ')} -- ROADMAP §4.3 requires this in the published package`
      : PROMISED_KEYS.join(', '));
}

// ======================================================================================
// 4. MESSAGES: compiled-in message key count
// ======================================================================================
// Count distinct key-shaped strings ("a.b.c" with at least one dot, lowercase ascii) in the
// constant pools of the *Messages.class entries. messages.yml is generated at runtime, so the
// only in-jar evidence of the table's size is the bytecode.
const KEY_RE = /^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+$/;
function countMessageKeys(zipMap) {
  const seen = new Set();
  for (const [name, e] of zipMap) {
    if (!name.endsWith('Messages.class')) continue;
    for (const s of classUtf8(e.data())) {
      if (s.length > 512) continue;
      if (KEY_RE.test(s)) seen.add(s);
    }
  }
  return seen;
}
const archKeysInJar = countMessageKeys(archived.zip);
const freshKeysInJar = countMessageKeys(fresh.zip);

// source-side count: reuse the same heuristic on src (dot-separated literal keys in tables)
function sourceMessageKeyCount() {
  const SRC = path.join(REPO, 'src', 'main', 'java');
  const keys = new Set();
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) { walk(p); continue; }
      if (!/Messages\.java$/.test(e.name)) continue;
      const txt = fs.readFileSync(p, 'utf8');
      for (const m of txt.matchAll(/"([a-z][a-z0-9]*(?:[.-][a-z0-9]+)+)"/g)) {
        if (KEY_RE.test(m[1])) keys.add(m[1]);
      }
    }
  };
  walk(SRC);
  return keys;
}
const srcKeys = sourceMessageKeyCount();

if (archKeysInJar.size === 0) {
  fail('MESSAGES keys compiled into archive', 'no message-key-shaped strings found in *Messages.class -- the message layer may be absent');
} else {
  const ratio = freshKeysInJar.size > 0 ? archKeysInJar.size / freshKeysInJar.size : 1;
  // Archive must carry at least as many keys as the source tables define (allowing for
  // detection noise: +/- 2% or 10 keys, whichever is larger).
  const slack = Math.max(10, Math.round(srcKeys.size * 0.02));
  if (archKeysInJar.size + slack < srcKeys.size) {
    fail('MESSAGES keys compiled into archive',
      `archive has ${archKeysInJar.size} message keys, source defines ${srcKeys.size} `
      + `(slack ${slack}) -- this is the 1.2.1 incident shape: 14 keys shipped against 672 in source`);
  } else {
    pass('MESSAGES keys compiled into archive',
      `${archKeysInJar.size} keys in archive vs ${srcKeys.size} in source (fresh build: ${freshKeysInJar.size})`);
  }
  rec('MESSAGES archive key count == fresh build', archKeysInJar.size === freshKeysInJar.size || ratio > 0.98,
    `archive ${archKeysInJar.size} vs fresh ${freshKeysInJar.size}`);
}

// ======================================================================================
// 5. plugin.yml version
// ======================================================================================
const pluginYml = jarYamlText(archived.zip, 'plugin.yml');
if (pluginYml == null) {
  fail('plugin.yml packaged', 'plugin.yml missing from jar root');
} else {
  const m = pluginYml.match(/^version\s*:\s*(.+?)\s*$/m);
  if (!m) fail('plugin.yml version', 'no version: line');
  else if (m[1].includes('${')) fail('plugin.yml version', `unresolved Maven placeholder: ${m[1]}`);
  else if (m[1] !== VERSION) {
    fail('plugin.yml version', `jar declares ${m[1]} but this release is ${VERSION}`);
  } else pass('plugin.yml version', m[1]);
}

// ======================================================================================
// 6. GIT: tag position + dirty tree
// ======================================================================================
const head = git(['rev-parse', '--short', 'HEAD']);
const tag = `v${VERSION}`;
const tagExists = git(['tag', '--list', tag]);

if (head == null) {
  rec('GIT repository readable', false, 'git not available or not a repository');
} else {
  pass('GIT repository readable', `HEAD ${head}`);
  if (!tagExists) {
    rec('GIT tag exists', false, `${tag} is not created yet (expected before publishing)`);
    const behind = git(['rev-list', '--count', 'HEAD']);
    rec('GIT source not ahead of release tag', false,
      `no ${tag}; HEAD is ${behind} commits past v${pom.version}. Cannot prove the archive matches a tag.`);
  } else {
    pass('GIT tag exists', tag);
    const ahead = git(['rev-list', '--count', `${tag}..HEAD`]);
    const n = ahead == null ? null : Number(ahead);
    if (n === 0) {
      pass('GIT source not ahead of release tag', `${tag} is at HEAD (${head})`);
    } else {
      fail('GIT source not ahead of release tag',
        `HEAD is ${n} commits ahead of ${tag}. If the archive was built from HEAD it does not `
        + `correspond to the tag; if it was built from the tag, the source has moved on. `
        + `Commits: ${(git(['log', '--oneline', `${tag}..HEAD`]) || '').split('\n').slice(0, 6).join(' | ')}`);
    }
  }
  const dirty = git(['status', '--porcelain']);
  if (dirty == null) {
    rec('GIT working tree clean', false, 'could not read git status');
  } else {
    // versions/*.jar is gitignored (project rule: jars go to GitHub Releases, not to git),
    // so archiving must not be counted as "dirty". Filter it defensively too.
    const lines = dirty.split('\n').filter(Boolean).filter(l => !/(^|\s)versions\//.test(l));
    if (lines.length === 0) {
      pass('GIT working tree clean', 'no modified/untracked files (versions/ excluded: gitignored)');
    } else {
      rec('GIT working tree clean', !!ALLOW_DIRTY,
        `${lines.length} changed path(s) (e.g. ${lines.slice(0, 4).join(', ')})`
        + (ALLOW_DIRTY ? ' -- allowed by --allow-dirty' : ' -- a published jar must be reproducible from a clean tree'));
    }
  }
}

// provenance sidecar written by archive-release.ps1 (optional, strengthens [1])
const provPath = ARCHIVE.replace(/\.jar$/, '.provenance.json');
if (fs.existsSync(provPath)) {
  try {
    const p = JSON.parse(fs.readFileSync(provPath, 'utf8'));
    const shaOk = p.sha256 === archived.sha256;
    rec('PROVENANCE sidecar sha256 == archive', shaOk,
      shaOk ? `recorded at commit ${String(p.commit || '?').slice(0, 7)}` : `sidecar says ${String(p.sha256).slice(0, 12)}, archive is ${archived.sha256.slice(0, 12)}`);
    if (head && p.commit) {
      const headFull = git(['rev-parse', 'HEAD']);
      rec('PROVENANCE commit == HEAD', p.commit === headFull,
        p.commit === headFull ? `both ${headFull.slice(0, 7)}` : `archive built at ${String(p.commit).slice(0, 7)}, HEAD is ${String(headFull).slice(0, 7)}`);
    }
  } catch (e) {
    rec('PROVENANCE sidecar readable', false, `cannot parse ${path.basename(provPath)}: ${e.message}`);
  }
} else {
  info(`  (no provenance sidecar at ${path.basename(provPath)} -- run tools/archive-release.ps1 to record one)`);
}

// ======================================================================================
// report
// ======================================================================================
const failed = results.filter(r => !r.ok);
if (AS_JSON) {
  console.log(JSON.stringify({
    repo: REPO, version: VERSION, archive: ARCHIVE, target: TARGET,
    archiveSha256: archived.sha256, freshSha256: fresh.sha256,
    archiveKeys: archKeysInJar.size, sourceKeys: srcKeys.size,
    results,
  }, null, 2));
} else {
  console.log('');
  console.log('='.repeat(74));
  for (const r of results) {
    console.log(`[${r.ok ? 'PASS' : 'FAIL'}] ${r.id} -- ${r.detail}`);
  }
  console.log('='.repeat(74));
  console.log(`ARTIFACT CHECK: ${failed.length === 0 ? 'PASS' : 'FAIL'} (${results.length - failed.length}/${results.length} passed)`);
  if (failed.length) {
    console.log('');
    console.log('Fix: rebuild from a clean checkout of the tag, copy target/*.jar over versions/,');
    console.log('     then run tools/archive-release.ps1 -Version <x.y.z> to refresh the provenance file.');
  }
  process.exitCode = failed.length === 0 ? 0 : 1;
}
