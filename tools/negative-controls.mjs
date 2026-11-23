// Positive-control runner for the negative assertions in the perfscope scenarios.
//
// A negative assertion (`notPresent` / `expectNot`) proves nothing on its own: it passes both
// when the guarded failure never happens AND when the pattern can never match anything. The
// only way to tell those apart is a control -- feed the matcher text the check is SUPPOSED to
// catch and confirm the check goes red; feed it a healthy window and confirm it stays green.
//
// WHERE THE CONTROLS COME FROM
//   The scenario JSON itself (`negativeSamples` / `cleanSamples`). This file used to carry a
//   second, hand-maintained copy of the samples, and the copy had drifted one string away from
//   the scenario's own -- so the two gates could return OPPOSITE verdicts for the same check
//   (check-scenarios.mjs red on up02 while this file passed it, same scenario file, same run).
//   Two copies of one criterion is the defect; the fix is one source. Direction: scenario JSON
//   is the source and both tools are readers -- the harness has to run where this repo is not
//   checked out, so it can never depend on a file here, and the samples belong next to the
//   pattern they must fire. The judging rules live in tools/lib/scenario-model.mjs, imported
//   here AND by check-scenarios.mjs, so there is exactly one definition of "does this control
//   prove the assertion can fail".
//
// This is a unit-level control: it validates the matcher, not the running server. It answers
// "would this assertion fire if the failure occurred?", which is exactly what S2 asks for. The
// same criterion runs inside check-scenarios.mjs; running it standalone here names every check
// it inspected, which is what a person debugging one assertion wants.
//
// Usage:
//   node tools/negative-controls.mjs
//   node tools/negative-controls.mjs --harness "D:\...\testharness"
//   node tools/negative-controls.mjs --repo "D:\...\PerfScope"
//   node tools/negative-controls.mjs --strict      # skipped checks (no declared control) fail too
//   node tools/negative-controls.mjs --json
//
// Exit: 0 = every judged control holds (and, with --strict, nothing was skipped),
//       1 = at least one control does not hold or (with --strict) a check was skipped,
//       2 = could not run (harness missing) -- never 1, which must mean "ran and found a fault".

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  argOf, findHarness, isNegativeCheck, judgeControl, scenarioDir, scenarioFiles, variantsOf,
} from './lib/scenario-model.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const AS_JSON = argv.includes('--json');
const STRICT = argv.includes('--strict');

const REPO = path.resolve(argOf(argv, '--repo', path.join(HERE, '..')));
const HARNESS = path.resolve(argOf(argv, '--harness', findHarness(REPO)));
const SCEN_DIR = scenarioDir(HARNESS);

if (!SCEN_DIR) {
  console.error(`ERROR: scenario directory not found: ${path.join(HARNESS, 'tests', 'scenarios')}`);
  console.error('       searched up to 4 levels above the repo root for a "testharness" folder.');
  console.error('       pass --harness <testharness dir> to point at it explicitly.');
  process.exit(2);
}

const files = scenarioFiles(SCEN_DIR);
if (files.length === 0) {
  console.error(`ERROR: no perfscope-*.json scenarios in ${SCEN_DIR}`);
  process.exit(2);
}

// Every negative assertion in every scenario is in scope. The old version listed seven
// scenario names by hand, so a negative assertion added to an eighth file was silently
// uncontrolled -- the same class of drift as the duplicated samples.
const rows = [];
for (const file of files) {
  let scen;
  try {
    scen = JSON.parse(fs.readFileSync(path.join(SCEN_DIR, file), 'utf8'));
  } catch (e) {
    console.error(`ERROR: cannot parse ${file}: ${e.message}`);
    process.exit(2);
  }
  for (const c of Array.isArray(scen.checks) ? scen.checks : []) {
    if (!c.id || !isNegativeCheck(c)) continue;
    const verdict = judgeControl(c);
    rows.push({
      id: c.id,
      scenario: file,
      failSamples: variantsOf(c.negativeSamples).length,
      cleanSamples: variantsOf(c.cleanSamples).length,
      declared: verdict.declared,
      findings: verdict.findings,
    });
  }
}

let failed = 0, checked = 0, skipped = 0;
const lines = [];
for (const r of rows) {
  if (!r.declared) {
    skipped++;
    lines.push(`skip  ${r.id}  (no negativeSamples/cleanSamples declared in ${r.scenario})`);
    continue;
  }
  checked++;
  if (r.findings.length === 0) {
    lines.push(`ok    ${r.id}  (${r.failSamples} failure sample(s), ${r.cleanSamples} clean)`);
    continue;
  }
  failed++;
  lines.push(`FAIL  ${r.id}  -- ${r.findings.length} control fault(s)`);
  for (const f of r.findings) lines.push(`        [${f.rule}] ${f.msg}`);
}

const strictFail = STRICT && skipped > 0;
if (AS_JSON) {
  console.log(JSON.stringify({ harness: HARNESS, repo: REPO, checked, failed, skipped, rows }, null, 2));
} else {
  for (const l of lines) console.log(l);
  if (strictFail) {
    console.log(`\n${skipped} check(s) skipped and --strict is set: an undeclared control is an `
      + 'unproven assertion, not a clean one.');
  }
  console.log(`\nNEGATIVE CONTROLS: ${checked} checked, ${failed} failed, ${skipped} skipped`);
}
process.exit(failed === 0 && !strictFail ? 0 : 1);
