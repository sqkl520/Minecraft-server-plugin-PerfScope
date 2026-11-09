#!/usr/bin/env node
// check-scenarios.mjs -- L1 gate: prove the test assertions can actually fail.
//
// The problem this exists for (research/07-release-quality.md §1.2, R-01..R-09): the harness
// treats `expect: ["A","B"]` as any-of and `expectNot` as none-of. Two consequences:
//
//   * a variant that can NEVER match is invisible -- the other variant keeps the check green
//     (R-03: up01's Chinese pattern was a double-backslash literal, unreachable since day one);
//   * a negative assertion with NO positive control is vacuously true whenever the behaviour it
//     guards did not happen at all (R-01: inject05b passed in a run where the injection never
//     took effect -- inject05, its positive sibling, FAILED in the same run).
//
// Rules:
//   S1 UNREACHABLE-VARIANT   every literal variant of expect/pattern/expectNot must be spellable
//                            from the source's string tables (placeholders {0} may absorb any
//                            text, and a variant may be a slice of a longer template -- the
//                            actionbar is built by concatenating " | {0}: " with "MSPT").
//                            All variants dead = the check can never pass. Some dead = it is
//                            silently down to fewer variants than the author thought.
//   S2 NEGATIVE-NO-CONTROL   a negative assertion needs a positive check in the same scenario
//                            proving the guarded behaviour does happen. Otherwise "we saw no
//                            duplicate" is also true when we saw nothing at all.
//   S3 NO-WINDOW             a `notPresent` log check without sinceMarker/trigger scans whatever
//                            the log already held -- a previous run can satisfy or defeat it.
//   S4 REGEX-IN-LITERAL      `rcon` / `rcon-wait` / `vbot` compare with literal Contains
//                            (scripts/lib/checks.ps1). A regex written for them (\\[ , .* , \\|)
//                            is matched verbatim and never fires. This is R-03's actual bug.
//   S5 DUPLICATE-ID          the same check id twice: the report double-counts and the second
//                            result hides the first.
//
// What it does NOT do: judge whether a check asserts enough. A title-only assertion is weak but
// not broken; that is a review question, not a static one.
//
// Usage:
//   node tools/check-scenarios.mjs
//   node tools/check-scenarios.mjs --harness "D:\...\testharness" --repo "D:\...\PerfScope"
//   node tools/check-scenarios.mjs --scenario perfscope-alert
//   node tools/check-scenarios.mjs --json
//
// Exit: 0 = no ERROR findings, 1 = at least one ERROR, 2 = could not run.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { javaFiles, readJava, stringLiterals } from './lib/java-strings.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : dflt;
};
const AS_JSON = argv.includes('--json');
const STRICT = argv.includes('--strict');
const ONLY = argOf('--scenario', '');

const REPO = path.resolve(argOf('--repo', path.join(HERE, '..', 'PerfScope')));
const HARNESS = path.resolve(argOf('--harness', path.join(HERE, '..', '..', 'testharness')));
const SCEN_DIR = path.join(HARNESS, 'tests', 'scenarios');
const SRC = path.join(REPO, 'src', 'main', 'java');

if (!fs.existsSync(SCEN_DIR)) {
  console.error(`ERROR: scenario directory not found: ${SCEN_DIR}`);
  console.error('       pass --harness <testharness dir>; it lives beside the plugin parent folder');
  process.exit(2);
}

// --------------------------------------------------------------------------------------
// Source string index.
// --------------------------------------------------------------------------------------
const srcStrings = [];
for (const f of javaFiles(SRC)) {
  const { stripped } = readJava(f);
  for (const lit of stringLiterals(stripped)) {
    if (lit.value) srcStrings.push(lit.value);
  }
}

// Templates: a source literal split on {N} placeholders. {N} may be replaced by any text,
// including text that itself came from another literal (nested Messages.get calls).
const templates = srcStrings
  .filter(s => s.includes('{'))
  .map(s => s.split(/\{\d+\}/));

/**
 * Can `text` be spelled from the source string tables?
 *
 * Static reachability of a console line is NOT decidable in general: lines are assembled as
 * literal + runtime value + literal ("Total entities: " + "Unavailable", ", dominant " + "PIG",
 * title + " " + suffix). So this is deliberately permissive:
 *   1) substring of one literal                       (a whole message table entry);
 *   2) L + R, L a suffix of a literal and R a prefix of another (label + value);
 *   3) v shares a >=60% prefix or suffix run with some literal (long anchored fragment);
 *   4) template windows over {N} placeholders.
 * A false "reachable" only weakens the gate; a false "unreachable" would train people to
 * ignore the report -- so all ambiguity resolves toward reachable.
 */
/**
 * Placeholder-tolerant reachability.
 *
 * A scenario asserts what the user SEES: `为负值(-5)，已钳制为 0`. The source has the
 * template `{0} 为负值({1})，已钳制为 0`. Neither `includes`, nor the prefix/suffix heuristics,
 * nor the label+value split handles a placeholder in the MIDDLE of the string, so three real
 * assertions were reported as unreachable when they are perfectly reachable:
 *   - config  / cfg-cooldown-negative-clamps   "为负值(-5)，已钳制为 0"
 *   - hotspot / hotspot05-dominant-pig         "主导 PIG"        (prefix ", dominant " + runtime type)
 *   - trend   / trend07-day-parameter          "近 7 天"          (template "近 {0} 天（…）")
 *
 * Fix: when a source string contains `{n}` placeholders, turn it into a regex where each
 * placeholder matches any non-empty run, then test the assertion against it. This is the same
 * "template + runtime arguments" model the plugin actually uses (Messages#format).
 */
function matchesTemplate(text, template) {
  if (!template || !/\{\d+\}/.test(template)) return false;
  // Escape regex metachars, then let each {n} absorb any run of characters.
  const rx = template
    .split(/(\{\d+\})/)
    .map(part => (/\{\d+\}/.test(part) ? '.+' : part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
    .join('');
  try {
    return new RegExp('^' + rx + '$', 's').test(text);
  } catch {
    return false;
  }
}

function reachable(text) {
  if (!text) return true;
  // Pure runtime data (a coordinate like "[0,0]", a number, a player/bot name from the
  // scenario itself) is produced by the harness state or by command arguments, not by a
  // message table. Judge it by shape instead of by string.
  if (/^[\[\(]?-?\d+([,:\/ ]-?\d+)*[\]\)]?$/.test(text.trim())) return true;

  for (const s of srcStrings) if (s.includes(text)) return true;

  // Placeholder templates: "{0} 为负值({1})，已钳制为 0" can spell "为负值(-5)，已钳制为 0".
  for (const s of srcStrings) if (matchesTemplate(text, s)) return true;

  const n = text.length;
  const need = Math.ceil(n * 0.6);
  for (const s of srcStrings) {
    if (s.length >= need) {
      if (s.endsWith(text.slice(0, need))) return true;
      if (s.startsWith(text.slice(n - need))) return true;
    }
  }
  // label + value: both halves must themselves be literal-derived
  for (let k = 3; k <= n - 3; k++) {
    const left = text.slice(0, k);
    const right = text.slice(k);
    if (srcStrings.some(s => s.endsWith(left)) && srcStrings.some(s => s.startsWith(right))) return true;
  }
  for (const segs of templates) if (sliceOfTemplate(text, segs)) return true;
  return false;
}

/**
 * Is `v` a slice of a template instantiation (placeholders = wildcards)?
 *
 * Two shapes are accepted, both requiring substantial literal alignment:
 *   (a) every whitespace token of v appears in the template's segments -- covers outputs built
 *       by concatenating a label literal with a value literal (" | {0}: " + "MSPT");
 *   (b) v starts/ends inside the literal segments with at least max(4, |v|/3) real characters
 *       matched -- covers assertions truncated to a prefix of a full line ("(interval: 60 ticks"
 *       against "(interval: {0} ticks)").
 *
 * The threshold is what keeps this sound: an earlier version accepted a single-character
 * anchor and thereby "explained" almost anything ("monitor 推送失败" matched "warning > {0}ms"
 * on one space character), turning the gate into a false clean.
 */
function sliceOfTemplate(v, segs) {
  if (segs.length < 2) return false;                 // no placeholder: the literal test covers it
  if (!segs.join('').trim()) return false;           // nothing but placeholders

  const tokens = v.split(/\s+/).filter(Boolean);
  if (tokens.length > 0 && tokens.every(t => segs.some(s => s.includes(t)))) return true;

  const need = Math.max(4, Math.ceil(v.length / 3));
  for (let i = 0; i < segs.length; i++) {
    for (let j = i + 1; j < segs.length; j++) {
      if (windowMatches(v, segs, i, j, need)) return true;
    }
  }
  return false;
}

/** Try to align v as suffix(seg_i) + wildcards + prefix(seg_j), with `need` literal chars. */
function windowMatches(v, segs, i, j, need) {
  const headSeg = segs[i];
  const tailSeg = segs[j];
  for (let hl = headSeg.length; hl >= 0; hl--) {
    const head = headSeg.slice(headSeg.length - hl);
    if (!v.startsWith(head)) continue;
    let pos = head.length;
    let consumed = head.length;
    let ok = true;
    for (let k = i + 1; k < j; k++) {
      const seg = segs[k];
      if (!seg) continue;
      const idx = v.indexOf(seg, pos);
      if (idx < 0) { ok = false; break; }
      pos = idx + seg.length;
      consumed += seg.length;
    }
    if (!ok) continue;
    for (let tl = tailSeg.length; tl >= 0; tl--) {
      if (v.startsWith(tailSeg.slice(0, tl), pos) && consumed + tl >= need) return true;
    }
  }
  return false;
}

/**
 * Text that legitimately appears in the log without being in the plugin's source strings:
 * JVM/Java exception names, stack-trace frames, server-side words. A negative assertion
 * ("this must NOT appear") normally targets exactly these -- so they are excluded from the
 * reachability test rather than flagged.
 */
const EXTERNAL_MARKERS = [
  'Exception', 'Error', 'Throwable', 'at cn', 'at java', 'at net', 'at org',
  'java.lang.', 'java.util.', 'Could not load', 'plugins', 'Done (', 'Enabling ',
  'Disabling ', 'Starting minecraft server', 'Stopping server',
];
const isExternal = (t) => EXTERNAL_MARKERS.some(m => t.includes(m));

/**
 * Does the scenario file itself supply this text? A messages.yml override written by
 * preBootFileOps is expected to appear in the console and is expected NOT to appear in the
 * plugin source -- treating it as "unreachable" would be a false alarm.
 */
function fixtureSupplies(text, blob) {
  if (!text || text.length < 4) return false;
  return blob.includes(text);
}

// --------------------------------------------------------------------------------------
// Scenario model.
// --------------------------------------------------------------------------------------
const RAW_TYPES = new Set(['rcon', 'rcon-wait', 'vbot']);   // literal Contains in the harness
const variantsOf = (v) => {
  if (v == null) return [];
  if (Array.isArray(v)) return v.flatMap(x => (typeof x === 'string' ? [x] : []));
  if (typeof v === 'string') return [v];
  return [];
};
const isRegex = (c) => c.regex === true;
/** Regex syntax smuggled into a literal-Contains check: \\[ , \\d , .* , \\| , ^...$ , (? */
const REGEX_SYNTAX = /(\\[\[\]().*+?{}|^$dDwWsS])|(\.\*)|(\.\+)|(\(\?)|(\$$)|(^\^)/;

/**
 * The literal fragments of a regex: split on metacharacters/classes, keep runs of >= 4 chars
 * (shorter runs like "at " match everything and would drown the report).
 * "at cn\\.perfscope" -> ["at cn", "perfscope"]; "monitor 推送失败" -> ["monitor 推送失败"].
 */
function literalRuns(re) {
  const parts = re
    .replace(/\\[dDwWsSbBnrtfv0]/g, '\u0000')       // character classes -> wildcards
    .replace(/\\./g, '\u0000')                      // escaped literal punctuation -> wildcard-ish
    .replace(/\[[^\]]*\]/g, '\u0000')               // char classes
    .replace(/\(\?[^)]*\)/g, '\u0000')              // groups/flags
    .replace(/[().*+?{}|^$]/g, '\u0000')            // operators
    .split('\u0000')
    .map(s => s.trim())
    .filter(s => s.length >= 4);
  return [...new Set(parts)];
}

const files = fs.readdirSync(SCEN_DIR)
  .filter(f => /^perfscope-.*\.json$/.test(f))
  .filter(f => !ONLY || f === `${ONLY}.json` || f === ONLY)
  .sort();

if (files.length === 0) {
  console.error(`ERROR: no scenarios matched in ${SCEN_DIR}${ONLY ? ` (filter: ${ONLY})` : ''}`);
  process.exit(2);
}

const findings = [];
const add = (sev, scenario, checkId, rule, msg) => findings.push({ sev, scenario, checkId, rule, msg });

let totalChecks = 0;
let totalVariants = 0;
let deadChecks = 0;
const perScenario = [];

for (const file of files) {
  const scen = JSON.parse(fs.readFileSync(path.join(SCEN_DIR, file), 'utf8'));
  const checks = Array.isArray(scen.checks) ? scen.checks : [];
  const name = scen.name || file.replace(/\.json$/, '');
  totalChecks += checks.length;
  perScenario.push({ scenario: name, file, checks: checks.length });

  const positives = checks.filter(c => !c.expectNot && c.notPresent !== true && (c.expect || c.pattern));

  // Text the scenario itself writes (messages.yml overrides in preBootFileOps, a config value,
  // a bot name). Those are legitimately absent from the plugin source: the fixture supplies
  // them.
  //
  // RED TEAM FIX (A1, 2026-11-02): this used to be `JSON.stringify(scen)` -- the WHOLE scenario,
  // checks included. That made S1 vacuous: an assertion's own expectation string is necessarily
  // present in the file that declares it, so `fixtureBlob.includes(v)` was always true and S1
  // could never fire. Proof: a scenario whose every expectation was
  // `TOTALLY_FAKE_STRING_XYZQ_NOT_IN_SOURCE` reported "PASS (0 error, 0 warn)".
  //
  // The fixture blob must contain ONLY what the fixture WRITES into the environment -- never
  // the assertions themselves. We take preBootFileOps / postFileOps / the checks' fileOps and
  // preCommands, i.e. the inputs that can put new text into the server's output.
  const fixtureBlob = JSON.stringify({
    preBootFileOps: scen.preBootFileOps || [],
    postFileOps: scen.postFileOps || [],
    postCommands: scen.postCommands || [],
    checkFileOps: checks.flatMap(c => c.fileOps || []),
    checkPreCommands: checks.flatMap(c => c.preCommands || []),
  });


  for (const c of checks) {
    const id = c.id || '(no id)';
    const type = c.type || 'log';
    const expectVars = variantsOf(c.expect);
    const expectNotVars = variantsOf(c.expectNot);
    const patternVars = typeof c.pattern === 'string' ? [c.pattern] : variantsOf(c.pattern);
    totalVariants += expectVars.length + expectNotVars.length + patternVars.length;

    // ---- S1: every literal variant must be spellable from source ----------------
    // Only POSITIVE variants are judged this way. A negative variant ("this must not appear")
    // usually names something the JVM or another plugin prints (NullPointerException,
    // NoSuchMethodError), which is by design absent from this plugin's strings -- flagging
    // those would be noise. The negative side is covered by S2 (needs a positive control).
    //
    // `vbot` expectations are matched against the agent's JSON reply (tool name, bot name,
    // "matched":true), not against anything the plugin prints, so source reachability does
    // not apply -- those checks are skipped here and covered by the agent contract instead.
    const sourceJudgeable = type !== 'vbot';
    const judgeSet = (vars, label) => {
      if (vars.length === 0 || !sourceJudgeable) return;
      const dead = vars.filter(v => !reachable(v) && !isExternal(v) && !fixtureSupplies(v, fixtureBlob));
      if (dead.length === 0) return;
      if (dead.length === vars.length) deadChecks++;
      add(dead.length === vars.length ? 'ERROR' : 'WARN', name, id, 'S1-UNREACHABLE-VARIANT',
        `${label}: ${dead.length}/${vars.length} variant(s) cannot be spelled from any source string -- `
        + (dead.length === vars.length
          ? 'the whole check can never pass: '
          : 'the check survives on the remaining variants only: ')
        + dead.map(v => JSON.stringify(v.slice(0, 70))).join(', '));
    };
    // expectNot variants are negatives: skip them (S2 covers that side).
    // A notPresent check's `pattern` is also a negative.
    const positivePattern = c.notPresent === true ? [] : patternVars;
    judgeSet(expectVars, 'expect');
    if (isRegex(c)) {
      // A regex still carries literal text between its syntax. Those fragments must exist in
      // source or that alternative can never match (R-02: soak03a's "monitor 推送失败" is
      // English in the code -- the Chinese alternative was unreachable for the whole door).
      for (const v of [...expectVars, ...positivePattern]) {
        const runs = literalRuns(v).filter(r => !isExternal(r));
        const dead = runs.filter(r => !reachable(r));
        if (dead.length) {
          add('ERROR', name, id, 'S1-UNREACHABLE-VARIANT',
            `regex contains literal text that appears in no source string, so that branch can never match: `
            + `${dead.map(r => JSON.stringify(r)).join(', ')} (in ${JSON.stringify(v.slice(0, 90))})`);
        }
      }
      // R-03 shape: "\\\\[" decoded to a regex meaning literal-backslash + char class.
      for (const v of [...expectVars, ...expectNotVars, ...patternVars]) {
        if (/\\\\([\[\]().*+?{}|^$-])/.test(v)) {
          add('ERROR', name, id, 'S6-OVER-ESCAPED-REGEX',
            `regex has a doubled backslash before a metacharacter (${JSON.stringify(v.slice(0, 90))}) -- `
            + `it means "a literal backslash followed by a class", which console output never contains. `
            + `This is R-03: the alternative has never matched and never will`);
        }
      }
    } else {
      judgeSet(positivePattern, 'pattern');
    }

    // ---- S7: a negative variant that can never fire = a blind spot -----------------
    // R-02 exactly: soak03a guards "no 'monitor 推送失败' in the console", but the plugin
    // prints that failure in English only. The Chinese alternative could never match, so that
    // failure mode was unguarded for the whole soak -- while the check reported PASS.
    // Same exclusions as S1: JVM/third-party markers and vbot (agent JSON) are legitimate.
    if (sourceJudgeable) {
      const negFrags = [];
      for (const v of expectNotVars) negFrags.push({ text: v, label: 'expectNot' });
      if (c.notPresent === true) {
        for (const v of patternVars) {
          if (isRegex(c)) for (const r of literalRuns(v)) negFrags.push({ text: r, label: 'notPresent' });
          else negFrags.push({ text: v, label: 'notPresent' });
        }
      }
      const blind = negFrags.filter(f => f.text.length >= 4 && !isExternal(f.text) && !reachable(f.text));
      if (blind.length) {
        add('WARN', name, id, 'S7-DEAD-NEGATIVE',
          `${blind.length} negative variant(s) cannot occur in this plugin's output, so that guard `
          + `can never fire -- the failure mode is unguarded while the check reads PASS: `
          + blind.map(b => `${b.label} ${JSON.stringify(b.text.slice(0, 70))}`).join(', '));
      }
    }

    // ---- S2: a negative assertion needs a positive control in the same scenario --
    const isNegative = (c.expectNot && expectNotVars.length > 0) || c.notPresent === true;
    if (isNegative) {
      const declared = c.controlledBy;
      const negVars = new Set([...expectNotVars, ...patternVars]);
      const control = declared
        ? positives.find(p => p.id === declared)
        : positives.find(p => [...variantsOf(p.expect), ...variantsOf(p.pattern)].some(v => negVars.has(v)));
      if (!control) {
        add('WARN', name, id, 'S2-NEGATIVE-NO-CONTROL',
          declared
            ? `controlledBy "${declared}" does not name a positive check in this scenario`
            : 'no positive check in this scenario asserts the same text -- if the guarded behaviour '
              + 'never happens, this check is vacuously true (R-01 shape)');
      }
    }

    // ---- S3: log notPresent needs a window --------------------------------------
    if (type === 'log' && c.notPresent === true) {
      const hasWindow = c.sinceMarker === true || (typeof c.trigger === 'string' && c.trigger.length > 0);
      if (!hasWindow) {
        add('WARN', name, id, 'S3-NO-WINDOW',
          'notPresent log check has no sinceMarker/trigger -- it scans the whole existing log, '
          + 'so a previous run can satisfy or defeat it');
      }
    }

    // ---- S4: regex written for a literal matcher --------------------------------
    if (RAW_TYPES.has(type)) {
      const smuggled = [...expectVars, ...expectNotVars].filter(v => REGEX_SYNTAX.test(v));
      if (smuggled.length) {
        add('ERROR', name, id, 'S4-REGEX-IN-LITERAL',
          `type "${type}" matches with literal Contains (harness checks.ps1), but the variant carries `
          + `regex syntax -- it is compared verbatim and never fires: `
          + smuggled.map(v => JSON.stringify(v.slice(0, 70))).join(', '));
      }
    }
  }

  // ---- S5: duplicate check ids ------------------------------------------------
  const ids = checks.map(c => c.id).filter(Boolean);
  const dupes = [...new Set(ids.filter((v, i) => ids.indexOf(v) !== i))];
  for (const d of dupes) {
    add('ERROR', name, d, 'S5-DUPLICATE-ID',
      'the same check id appears more than once; the report double-counts and one result hides the other');
  }
}

// --------------------------------------------------------------------------------------
// Report.
// --------------------------------------------------------------------------------------
const errors = findings.filter(f => f.sev === 'ERROR');
const warns = findings.filter(f => f.sev === 'WARN');
const byRule = {};
for (const f of findings) byRule[f.rule] = (byRule[f.rule] || 0) + 1;

if (AS_JSON) {
  console.log(JSON.stringify({
    harness: HARNESS, repo: REPO, scenarios: perScenario,
    totalChecks, totalVariants, deadChecks, byRule, findings,
  }, null, 2));
} else {
  console.log('');
  console.log('PerfScope scenario assertion reachability check');
  console.log(`  harness   : ${HARNESS}`);
  console.log(`  source    : ${REPO}`);
  console.log(`  scenarios : ${files.length}   checks: ${totalChecks}   variants: ${totalVariants}   unspellable-only checks: ${deadChecks}`);
  console.log('');
  if (findings.length === 0) {
    console.log('  no findings');
  } else {
    console.log(findings.map(f => `  [${f.sev}][${f.rule}] ${f.scenario} / ${f.checkId}\n      ${f.msg}`).join('\n'));
  }
  console.log('');
  if (Object.keys(byRule).length) {
    console.log('  by rule: ' + Object.entries(byRule).map(([k, v]) => `${k}=${v}`).join('  '));
  }
  const failed = errors.length > 0 || (STRICT && warns.length > 0);
  console.log(`SCENARIO CHECK: ${failed ? 'FAIL' : 'PASS'} (${errors.length} error, ${warns.length} warn)`);
  process.exitCode = failed ? 1 : 0;
}
