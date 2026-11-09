#!/usr/bin/env node
// check-i18n.mjs -- L1 gate: message-layer static validation (no build, no server, ~1s).
//
// Why this exists: the message layer is 590 keys spread over 8 tables. Nothing in the build
// or CI ever checked them. Three failure modes are invisible until a user sees them:
//   1) en table has a key, zh does not (or vice versa) -> that language silently renders the
//      raw key, e.g. "cmd.status.collect-stale" shown to a Chinese user;
//   2) the same key has {0}{1} in one language and only {0} in the other -> one language loses
//      a number. This is exactly BLOCKER-1 (AlertCatalog.argsFor placeholder order), which had
//      no regression test after it was fixed;
//   3) a Messages.get("typo.key") call site whose key does not exist -> always renders the key.
//
// What it does NOT do: judge translation quality, or resolve keys built at runtime
// (Messages.get(k) with a variable). Those are counted and reported separately so the
// "unverifiable" surface is visible instead of silently assumed clean.
//
// Exit: 0 = clean, 1 = at least one ERROR. WARN does not fail (see --strict to make it fail).
//
// Usage:
//   node tools/check-i18n.mjs                     # scan PerfScope/src/main/java
//   node tools/check-i18n.mjs --repo <path>       # explicit repo root
//   node tools/check-i18n.mjs --strict            # WARN also fails (for release gate)
//   node tools/check-i18n.mjs --json              # machine-readable output

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { callSites, evalConcatExpr, evalIdentifier, javaFiles, readJava } from './lib/java-strings.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : dflt;
};
const REPO = path.resolve(argOf('--repo', path.join(HERE, '..', 'PerfScope')));
const STRICT = argv.includes('--strict');
const AS_JSON = argv.includes('--json');
const SRC = path.join(REPO, 'src', 'main', 'java');

if (!fs.existsSync(SRC)) {
  console.error(`ERROR: source root not found: ${SRC}`);
  process.exit(2);
}

const errors = [];
const warns = [];
const err = (code, msg) => errors.push({ code, msg });
const warn = (code, msg) => warns.push({ code, msg });

// --------------------------------------------------------------------------------------
// 1. Extract the built-in message tables.
// --------------------------------------------------------------------------------------
// Two shapes exist in this codebase:
//   a) Messages.java itself:            en.put("key", "text");
//   b) every *Messages.java module:     put(en, zh, "key", "en text", "zh text");
// Module tables are registered into Messages at class-init time (Messages.registerModule),
// so a key may legitimately appear in several modules ONLY if the values agree.

/** key -> { en: [{value, file, line}], zh: [...] } */
const table = new Map();
const seenKeys = new Map(); // key -> first file that defined it (for duplicate detection)

const putIn = (lang, key, value, file, line) => {
  if (!table.has(key)) table.set(key, { en: [], zh: [] });
  table.get(key)[lang].push({ value, file, line });
};

const lineOf = (text, offset) => text.slice(0, offset).split('\n').length;

const files = javaFiles(SRC);
const callerSites = [];   // { key, file, line, kind }
let unresolvableCallSites = 0;

for (const file of files) {
  const { raw, stripped } = readJava(file);
  const rel = path.relative(REPO, file).replace(/\\/g, '/');

  // (a) Messages.java base tables: en.put("k", "v") / zh.put("k", "v")
  // Skip this shape inside the small `put(Map en, Map zh, ...)` helper itself, where the
  // receiver is a parameter and the key/value are parameters — that is the helper body,
  // not a table entry. Detected as: the enclosing method declares the receiver name.
  const helperBodies = new Set();
  for (const hm of stripped.matchAll(/void\s+put\s*\(\s*Map<[^>]*>\s+(\w+)\s*,\s*Map<[^>]*>\s+(\w+)/g)) {
    helperBodies.add(hm[1]);
    helperBodies.add(hm[2]);
  }

  for (const lang of ['en', 'zh']) {
    if (helperBodies.has(lang)) continue;   // `en`/`zh` here is a parameter, not a table
    for (const site of callSites(stripped, `${lang}.put`)) {
      const keyExpr = evalConcatExpr(stripped, site.args[0].from, site.args[0].to);
      const valExpr = site.args.length > 1
        ? evalConcatExpr(stripped, site.args[1].from, site.args[1].to) : null;
      if (!keyExpr) {
        // `en.put(k, k)` inside a loop that copies one map into another is a merge, not a
        // table entry -- same goes for any key passed through a variable. Not a defect and
        // not worth a WARN (a real table typo shows up as E-UNKNOWN-KEY at the call site).
        continue;
      }
      putIn(lang, keyExpr.value, valExpr ? valExpr.value : null, rel, lineOf(raw, site.index));
    }
  }

  // (b) module tables: put(en, zh, "k", "en", "zh")
  for (const site of callSites(stripped, 'put')) {
    if (site.args.length !== 5) continue;   // a different helper with the same name
    const a = site.args.map(x => evalConcatExpr(stripped, x.from, x.to));
    const enId = evalIdentifier(stripped, site.args[0].from, site.args[0].to);
    const zhId = evalIdentifier(stripped, site.args[1].from, site.args[1].to);
    if (enId !== 'en' || zhId !== 'zh') continue;   // not the (en, zh, key, enVal, zhVal) shape
    const key = a[2];
    const enVal = a[3];
    const zhVal = a[4];
    const line = lineOf(raw, site.index);
    if (!key) {
      warn('W-DYNAMIC-KEY', `${rel}:${line} module put() key is not a literal (cannot be indexed)`);
      continue;
    }
    putIn('en', key.value, enVal ? enVal.value : null, rel, line);
    putIn('zh', key.value, zhVal ? zhVal.value : null, rel, line);
  }
}

// --------------------------------------------------------------------------------------
// 2. Key-set parity + placeholder parity + duplicate/empty checks.
// --------------------------------------------------------------------------------------
const placeholderSet = (s) => {
  if (s == null) return null;
  const set = new Set();
  for (const m of s.matchAll(/\{(\d+)\}/g)) set.add(m[1]);
  return set;
};
const fmtSet = (s) => (s == null ? '(null)' : `{${[...s].sort((a, b) => a - b).join(',')}}`);

let keyCount = 0;
let placeholderChecked = 0;

for (const [key, e] of [...table.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
  keyCount++;
  // duplicate definitions with different values = one silently wins (Map.putAll order)
  for (const lang of ['en', 'zh']) {
    const defs = e[lang];
    const vals = new Set(defs.map(d => d.value));
    if (vals.size > 1) {
      err('E-DUP-KEY',
        `key "${key}" is defined ${defs.length}x in ${lang} with ${vals.size} different values `
        + `(${defs.map(d => `${d.file}:${d.line}`).join(', ')}) -- last registration wins, the others are dead`);
    }
    if (defs.length > 0 && defs.every(d => d.value == null)) {
      err('E-NULL-VALUE', `key "${key}" exists in ${lang} but its value could not be statically resolved (${defs[0].file}:${defs[0].line})`);
    }
    if (defs.some(d => d.value === '')) {
      warn('W-EMPTY-VALUE', `key "${key}" has an empty ${lang} value (${defs.find(d => d.value === '').file}:${defs.find(d => d.value === '').line})`);
    }
  }

  const hasEn = e.en.length > 0;
  const hasZh = e.zh.length > 0;
  if (!hasEn && hasZh) {
    err('E-MISSING-EN', `key "${key}" has zh but no en (${e.zh[0].file}:${e.zh[0].line}) -- English users see the raw key`);
  } else if (hasEn && !hasZh) {
    err('E-MISSING-ZH', `key "${key}" has en but no zh (${e.en[0].file}:${e.en[0].line}) -- Chinese users see the raw key`);
  }

  const enPH = placeholderSet(e.en[0]?.value ?? null);
  const zhPH = placeholderSet(e.zh[0]?.value ?? null);
  if (enPH && zhPH && e.en[0]?.value != null && e.zh[0]?.value != null) {
    placeholderChecked++;
    if (fmtSet(enPH) !== fmtSet(zhPH)) {
      err('E-PLACEHOLDER', `key "${key}" placeholder mismatch: en=${fmtSet(enPH)} zh=${fmtSet(zhPH)} `
        + `(${e.en[0].file}:${e.en[0].line}) -- one language loses or gains an argument`);
    }
    // Gaps like {0}{2} mean a call site will silently keep a literal "{2}"
    for (const [lang, set] of [['en', enPH], ['zh', zhPH]]) {
      const max = Math.max(...[...set].map(Number), -1);
      for (let i = 0; i <= max; i++) {
        if (!set.has(String(i))) {
          warn('W-PLACEHOLDER-GAP', `key "${key}" ${lang} uses {${max}} without {${i}} -- a call site passing N args leaves a literal placeholder`);
          break;
        }
      }
    }
  }
}

// --------------------------------------------------------------------------------------
// 3. Call sites: every literal key passed to Messages.get / logText must exist.
// --------------------------------------------------------------------------------------
const KNOWN_KEYS = new Set(table.keys());
const GET_FNS = ['Messages.get', 'logText', 'alertText'];   // wrappers that resolve a table key

for (const file of files) {
  const { raw, stripped } = readJava(file);
  const rel = path.relative(REPO, file).replace(/\\/g, '/');
  for (const fn of GET_FNS) {
    for (const site of callSites(stripped, fn)) {
      if (site.args.length === 0) continue;
      const k = evalConcatExpr(stripped, site.args[0].from, site.args[0].to);
      if (!k) { unresolvableCallSites++; continue; }
      callerSites.push({ key: k.value, file: rel, line: lineOf(raw, site.index), kind: fn });
      if (!KNOWN_KEYS.has(k.value)) {
        err('E-UNKNOWN-KEY', `${rel}:${lineOf(raw, site.index)} ${fn}("${k.value}") -- key not in any message table; renders as the raw key`);
      }
    }
  }
}

// --------------------------------------------------------------------------------------
// 4. Report.
// --------------------------------------------------------------------------------------
const usedKeys = new Set(callerSites.map(s => s.key));
const unusedKeys = [...KNOWN_KEYS].filter(k => !usedKeys.has(k));
// Keys can be resolved by a variable (e.g. Messages.get(k)) inside their own module, so
// "unused" is informational: it flags keys that no literal call site reaches.

if (AS_JSON) {
  console.log(JSON.stringify({
    repo: REPO, files: files.length, keys: keyCount, placeholderChecked,
    callSites: callerSites.length, unresolvableCallSites, errors, warns,
    unusedKeyCount: unusedKeys.length,
  }, null, 2));
} else {
  console.log('');
  console.log('PerfScope i18n static check');
  console.log(`  repo            : ${REPO}`);
  console.log(`  java files      : ${files.length}`);
  console.log(`  message keys    : ${keyCount}  (placeholder parity checked on ${placeholderChecked})`);
  console.log(`  literal lookups : ${callerSites.length}  (runtime-built, unverifiable: ${unresolvableCallSites})`);
  console.log(`  never looked up : ${unusedKeys.length} (informational; runtime keys look unused here)`);
  console.log('');
  const dump = (list, label) => {
    if (list.length === 0) return;
    console.log(`${label} (${list.length}):`);
    for (const e of list) console.log(`  [${e.code}] ${e.msg}`);
    console.log('');
  };
  dump(errors, 'ERROR');
  dump(warns, 'WARN');
  const failed = errors.length > 0 || (STRICT && warns.length > 0);
  const verdict = failed ? 'FAIL' : 'PASS';
  console.log(`I18N CHECK: ${verdict} (${errors.length} error, ${warns.length} warn)`);
  process.exitCode = failed ? 1 : 0;
}
