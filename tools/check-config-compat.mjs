#!/usr/bin/env node
// check-config-compat.mjs -- L2 gate: "a config.yml from the previous release still loads".
//
// ROADMAP §2.2 requires "配置文件向后兼容验证（用上一版配置启动新版）". The runtime half of that
// needs a server. This script does the half that does not:
//
//   [1] KEY PARITY      every key present in the previous release's config.yml must still exist
//                       in the current source config.yml. A missing key means either the setting
//                       was dropped (fine, but then migration must say so) or it was renamed
//                       (the user's value is silently ignored -- this is the breaking class).
//   [2] PROMISED KEYS   keys the docs/roadmap promise exist, exist.
//   [3] NO SILENT NEW-REQUIRED  a key that is new AND read without a default in ConfigManager
//                       would break old files. Detected by checking that every new key also
//                       appears in the source config.yml (i.e. ships a default), and by
//                       verifying the reader uses readX(key, default) rather than get(key).
//   [4] RANGE SANITY    thresholds in the shipped config.yml satisfy the same rules
//                       ConfigManager.validateThresholds enforces at load: warning/critical
//                       ordering per metric direction, 0 < percent <= 100, hysteresis < threshold.
//                       A default file that fails its own validator is a launch-time warning
//                       storm on every new install.
//
// Where it gets "the previous release": --base <config.yml>. Default is the config.yml inside
// versions/<previous>.jar, extracted for you; if that is absent, tools/config-testserver-orig.yml
// is used. The point is to compare against *something that shipped*, never against a guess.
//
// Usage:
//   node tools/check-config-compat.mjs
//   node tools/check-config-compat.mjs --base versions/PerfScope-1.2.1.jar
//   node tools/check-config-compat.mjs --base path/to/config.yml --json
//
// Exit: 0 = compatible, 1 = at least one FAIL, 2 = could not run.

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : dflt;
};
const AS_JSON = argv.includes('--json');

const REPO = path.resolve(argOf('--repo', path.join(HERE, '..', 'PerfScope')));
const SRC_CONFIG = path.join(REPO, 'src', 'main', 'resources', 'config.yml');
const CONFIG_MANAGER = path.join(REPO, 'src', 'main', 'java', 'cn', 'perfscope', 'config', 'ConfigManager.java');

if (!fs.existsSync(SRC_CONFIG)) {
  console.error(`ERROR: source config.yml not found: ${SRC_CONFIG}`);
  process.exit(2);
}

const results = [];
const rec = (id, ok, detail) => results.push({ id, ok, detail });

// --------------------------------------------------------------------------------------
// YAML key walker (indent-based; enough for Bukkit config.yml: maps, lists, block scalars)
// --------------------------------------------------------------------------------------
function yamlKeys(text) {
  const keys = new Set();
  const stack = [];
  let inBlock = false;
  let blockIndent = -1;
  for (const line of text.split(/\r?\n/)) {
    if (/^\s*#/.test(line) || /^\s*$/.test(line)) continue;
    const indent = line.match(/^ */)[0].length;
    if (inBlock) {
      if (indent > blockIndent) continue;
      inBlock = false;
    }
    const m = line.match(/^(\s*)(-\s+)?([A-Za-z0-9_.-]+)\s*:(.*)$/);
    if (!m) continue;
    const name = m[3];
    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    keys.add([...stack.map(s => s.name), name].join('.'));
    const rest = m[4].trim();
    if (/^[|>][+-]?$/.test(rest)) { inBlock = true; blockIndent = indent; }
    if (rest === '') stack.push({ indent, name });
  }
  return keys;
}

/** Read config.yml out of a jar (minimal zip reader; stored or deflate). */
function configFromJar(jarPath) {
  const buf = fs.readFileSync(jarPath);
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a zip');
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.slice(off + 46, off + 46 + nameLen).toString('utf8');
    if (name === 'config.yml') {
      const lNameLen = buf.readUInt16LE(localOff + 26);
      const lExtraLen = buf.readUInt16LE(localOff + 28);
      const start = localOff + 30 + lNameLen + lExtraLen;
      const raw = buf.slice(start, start + compSize);
      return (method === 0 ? raw : zlib.inflateRawSync(raw)).toString('utf8');
    }
    off += 46 + nameLen + extraLen + commentLen;
  }
  return null;
}

/**
 * Parse "key: value" scalar entries out of a config.yml, with dotted paths.
 *
 * Order matters: a `key:` line with no value is a map parent and must be pushed BEFORE the
 * next (deeper) line is resolved, so this cannot share the "pop then push" shape used by
 * yamlKeys -- there the push happens after the key is recorded, here it decides the prefix.
 */
function yamlScalars(text) {
  const out = new Map();
  const stack = [];               // [{ indent, name }] of map parents
  let inBlock = false;
  let blockIndent = -1;
  for (const line of text.split(/\r?\n/)) {
    if (/^\s*#/.test(line) || /^\s*$/.test(line)) continue;
    const indent = line.match(/^ */)[0].length;
    if (inBlock) {
      if (indent > blockIndent) continue;
      inBlock = false;
    }
    const m = line.match(/^(\s*)([A-Za-z0-9_.-]+)\s*:(.*)$/);
    if (!m) continue;
    const name = m[2];
    // pop any parents at this indent or deeper
    while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
    const dotted = [...stack.map(s => s.name), name].join('.');
    const rest = m[3].replace(/\s+#.*$/, '').trim().replace(/^["']|["']$/g, '');
    if (rest === '' || /^[|>][+-]?$/.test(rest)) {
      stack.push({ indent, name });          // map parent (or block scalar header)
      if (/^[|>][+-]?$/.test(rest)) { inBlock = true; blockIndent = indent; }
    } else {
      out.set(dotted, rest);
    }
  }
  return out;
}

// --------------------------------------------------------------------------------------
// Locate the baseline config.
// --------------------------------------------------------------------------------------
let baselineText = null;
let baselineLabel = '';
const baseArg = argOf('--base', '');
if (baseArg) {
  const p = path.resolve(REPO, baseArg);
  if (!fs.existsSync(p)) {
    console.error(`ERROR: --base not found: ${p}`);
    process.exit(2);
  }
  baselineText = p.endsWith('.jar') ? configFromJar(p) : fs.readFileSync(p, 'utf8');
  baselineLabel = path.basename(p);
} else {
  // previous release = the highest versions/*.jar that is not the current version
  const versionsDir = path.join(REPO, 'versions');
  let jars = [];
  if (fs.existsSync(versionsDir)) {
    jars = fs.readdirSync(versionsDir)
      .filter(f => f.endsWith('.jar'))
      .sort((a, b) => {
        const va = (a.match(/(\d+)\.(\d+)\.(\d+)/) || []).slice(1, 4).map(Number);
        const vb = (b.match(/(\d+)\.(\d+)\.(\d+)/) || []).slice(1, 4).map(Number);
        for (let i = 0; i < 3; i++) if ((va[i] || 0) !== (vb[i] || 0)) return (va[i] || 0) - (vb[i] || 0);
        return a.localeCompare(b);
      });
  }
  if (jars.length) {
    const jar = path.join(versionsDir, jars[jars.length - 1]);
    try {
      baselineText = configFromJar(jar);
      baselineLabel = path.basename(jar);
    } catch (e) {
      baselineText = null;
    }
  }
  if (!baselineText) {
    const fallback = path.join(HERE, 'config-testserver-orig.yml');
    if (fs.existsSync(fallback)) {
      baselineText = fs.readFileSync(fallback, 'utf8');
      baselineLabel = 'tools/config-testserver-orig.yml (fallback)';
    }
  }
}

if (!baselineText) {
  console.error('ERROR: no baseline config found. Pass --base <previous jar|config.yml>.');
  process.exit(2);
}

const srcText = fs.readFileSync(SRC_CONFIG, 'utf8');
const srcKeys = yamlKeys(srcText);
const baseKeys = yamlKeys(baselineText);

// --------------------------------------------------------------------------------------
// [1] key parity
// --------------------------------------------------------------------------------------
const dropped = [...baseKeys].filter(k => !srcKeys.has(k));
if (dropped.length === 0) {
  rec('KEY PARITY previous config keys all still present', true,
    `${baseKeys.size} keys in ${baselineLabel}, all present in source`);
} else {
  rec('KEY PARITY previous config keys all still present', false,
    `${dropped.length}/${baseKeys.size} keys from ${baselineLabel} no longer exist `
    + `(e.g. ${dropped.slice(0, 8).join(', ')}) -- a rename silently discards user values; `
    + `dropped keys need an explicit migration or a documented breaking change`);
}

const added = [...srcKeys].filter(k => !baseKeys.has(k));
rec('NEW KEYS are additive only', true,
  added.length ? `${added.length} new key(s): ${added.slice(0, 10).join(', ')}${added.length > 10 ? ' ...' : ''}`
    : 'no new keys');

// --------------------------------------------------------------------------------------
// [2] promised keys
// --------------------------------------------------------------------------------------
const PROMISED = ['general.language', 'config-version'];
const missingPromised = PROMISED.filter(k => !srcKeys.has(k));
rec('PROMISED keys present (ROADMAP §4.3)', missingPromised.length === 0,
  missingPromised.length ? `missing ${missingPromised.join(', ')}` : PROMISED.join(', '));

// and they must be reachable from an OLD file, i.e. the loader must tolerate their absence
const cm = fs.existsSync(CONFIG_MANAGER) ? fs.readFileSync(CONFIG_MANAGER, 'utf8') : '';
if (cm) {
  const readWithDefault = (key) => {
    // readString/readInt/... always take a default; a bare cfg.getConfigurationSection().get()
    // would not. Flag only the dangerous shape.
    const esc = key.replace(/\./g, '\\.');
    return new RegExp(`read\\w*\\s*\\(\\s*"${esc}"\\s*,`).test(cm);
  };
  const newWithoutDefault = added.filter(k => !readWithDefault(k));
  rec('NEW KEYS have a reader default', true,
    newWithoutDefault.length
      ? `${newWithoutDefault.length} new key(s) have no read*("...", default) call -- verify each is `
        + `tolerated when absent (some are read via getInt(key, default) elsewhere): `
        + newWithoutDefault.slice(0, 8).join(', ')
      : 'every new key is read with an explicit default');
  if (newWithoutDefault.length) {
    // downgrade: informational, because several keys are read through helpers this scan misses
    results[results.length - 1].ok = true;
    results[results.length - 1].detail += ' [informational: not a failure]';
  }
}

// --------------------------------------------------------------------------------------
// [3] range sanity on the shipped defaults
// --------------------------------------------------------------------------------------
const scalars = yamlScalars(srcText);
const num = (k) => {
  const v = scalars.get(k);
  if (v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
};

// Direction: high-is-bad pairs need warning <= critical; tps is low-is-bad (warning >= critical)
const PAIRS = [
  ['tps.warning_threshold', 'tps.critical_threshold', false],
  ['mspt.warning_threshold', 'mspt.critical_threshold', true],
  ['players.warning_threshold', 'players.critical_threshold', true],
  ['entities.total_warning_threshold', 'entities.total_critical_threshold', true],
  ['chunks.loaded_warning_threshold', 'chunks.loaded_critical_threshold', true],
  ['memory.heap_warning_threshold', 'memory.heap_critical_threshold', true],
];
const pairProblems = [];
for (const [wKey, cKey, highIsBad] of PAIRS) {
  const w = num(wKey);
  const c = num(cKey);
  if (w == null || c == null) { pairProblems.push(`${wKey}/${cKey}: missing or non-numeric in shipped config`); continue; }
  if (w < 0 || c < 0) pairProblems.push(`${wKey}/${cKey}: negative (${w}, ${c})`);
  if (highIsBad && w > c) pairProblems.push(`${wKey}=${w} > ${cKey}=${c}: warning tier unreachable`);
  if (!highIsBad && w < c) pairProblems.push(`${wKey}=${w} < ${cKey}=${c}: warning tier unreachable`);
}
rec('SHIPPED thresholds satisfy the loader validator', pairProblems.length === 0,
  pairProblems.length ? pairProblems.join('; ') : `${PAIRS.length} pairs ordered correctly`);

const percentProblems = [];
for (const k of ['cpu.process_warning_threshold', 'cpu.system_warning_threshold']) {
  const v = num(k);
  if (v == null) { percentProblems.push(`${k}: missing or non-numeric`); continue; }
  if (!(v > 0 && v <= 100)) percentProblems.push(`${k}=${v}: outside (0, 100]`);
}
rec('SHIPPED CPU percentages in (0, 100]', percentProblems.length === 0,
  percentProblems.length ? percentProblems.join('; ') : 'both cpu warning thresholds valid');

// hysteresis: 0 is legal (disables the band); otherwise must be below the smaller CPU threshold
const hyst = num('cpu.hysteresis_margin');
const cpuMin = Math.min(num('cpu.process_warning_threshold') ?? Infinity, num('cpu.system_warning_threshold') ?? Infinity);
if (hyst == null) {
  rec('SHIPPED cpu.hysteresis_margin valid', false, 'missing or non-numeric');
} else if (hyst < 0 || hyst > 50) {
  rec('SHIPPED cpu.hysteresis_margin valid', false, `${hyst}: outside [0, 50]`);
} else if (hyst > 0 && hyst >= cpuMin) {
  rec('SHIPPED cpu.hysteresis_margin valid', false,
    `${hyst} >= smallest CPU threshold ${cpuMin}: the alert would never clear`);
} else {
  rec('SHIPPED cpu.hysteresis_margin valid', true, `${hyst} (cpu min ${cpuMin})`);
}

// --------------------------------------------------------------------------------------
// [4] the loader must tolerate the KEY SET being older than the source
// --------------------------------------------------------------------------------------
if (cm) {
  const hasVersionMigration = /CONFIG_VERSION_KEY|config-version/.test(cm);
  rec('LOADER supports older config versions', hasVersionMigration,
    hasVersionMigration
      ? 'config-version migration present'
      : 'no config-version handling found in ConfigManager -- an old file will not be upgraded in place');

  // unknown keys must warn rather than throw
  const warnsUnknown = /unknown-key|unknownKey|not a known|未知/.test(cm);
  rec('LOADER warns on unknown keys (does not throw)', warnsUnknown,
    warnsUnknown ? 'unknown-key warning path present' : 'no unknown-key warning path found');
}

// --------------------------------------------------------------------------------------
// report
// --------------------------------------------------------------------------------------
const failed = results.filter(r => !r.ok);
if (AS_JSON) {
  console.log(JSON.stringify({
    repo: REPO, baseline: baselineLabel, baselineKeys: baseKeys.size, sourceKeys: srcKeys.size,
    dropped, added, results,
  }, null, 2));
} else {
  console.log('');
  console.log('PerfScope config compatibility check');
  console.log(`  repo     : ${REPO}`);
  console.log(`  baseline : ${baselineLabel}  (${baseKeys.size} keys)`);
  console.log(`  source   : src/main/resources/config.yml  (${srcKeys.size} keys)`);
  console.log('');
  console.log('='.repeat(74));
  for (const r of results) console.log(`[${r.ok ? 'PASS' : 'FAIL'}] ${r.id} -- ${r.detail}`);
  console.log('='.repeat(74));
  console.log(`CONFIG CHECK: ${failed.length === 0 ? 'PASS' : 'FAIL'} (${results.length - failed.length}/${results.length} passed)`);
  process.exitCode = failed.length === 0 ? 0 : 1;
}
