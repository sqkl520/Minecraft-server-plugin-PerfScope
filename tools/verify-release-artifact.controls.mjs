#!/usr/bin/env node
// verify-release-artifact.controls.mjs -- proves the --ref path of verify-release-artifact.mjs
// actually discriminates, using the archives that already exist in versions/.
//
// Why this file exists: --ref was written because the gate misfired after every normal release.
// A gate that stops failing is worthless unless something proves it still CAN fail. Each case
// below is a claim about a real archive in this repo, checked against a real git ref. Run it
// after touching verify-release-artifact.mjs, and read the exit code, not the narrative.
//
// Usage (repo root):
//   node tools/verify-release-artifact.controls.mjs               # all cases, ~3-6 min
//   node tools/verify-release-artifact.controls.mjs --fast        # blob-only cases, no Maven
//   node tools/verify-release-artifact.controls.mjs --json
//
// Exit: 0 = every case produced the expected exit code, 1 = a case disagreed, 2 = harness error.

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(process.argv.includes('--repo')
  ? process.argv[process.argv.indexOf('--repo') + 1] : path.join(HERE, '..'));
const FAST = process.argv.includes('--fast');
const AS_JSON = process.argv.includes('--json');
const VERIFY = path.join(HERE, 'verify-release-artifact.mjs');

function git(args) {
  try {
    return execFileSync('git', args, { cwd: REPO, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  } catch { return null; }
}
const sha = (f) => execFileSync(process.execPath, ['-e',
  `process.stdout.write(require('crypto').createHash('sha256').update(require('fs').readFileSync(${JSON.stringify(f)})).digest('hex'))`],
  { encoding: 'utf8' });

/**
 * Each case: a description of the claim, the argv for the verifier, and the exit code that
 * claim implies. Exit codes are the contract: 0 = ran and agreed, 1 = FAIL, 3 = SKIPPED a
 * comparison (never a pass), 2 = could not run.
 */
const CASES = [
  {
    id: 'tag-clean-1.3.0',
    claim: 'versions/PerfScope-1.3.0.jar is exactly a build of v1.3.0 while HEAD is 9 commits ahead',
    argv: ['--ref', 'v1.3.0', '--version', '1.3.0'],
    expect: 0,
    needsBuild: true,
  },
  {
    id: 'tag-clean-1.2.0',
    claim: 'the 1.2.0 archive matches v1.2.0 by content and keys; its sha256 differs only because '
      + 'v1.2.0 predates project.build.outputTimestamp (zip timestamps), so this case is exit 1, not 0',
    argv: ['--ref', 'v1.2.0', '--version', '1.2.0'],
    expect: 1,
    needsBuild: true,
  },
  {
    id: 'wrong-archive-vs-ref',
    claim: 'feeding the 1.2.0 archive to a v1.3.0 baseline must FAIL -- this is the original incident',
    argv: ['--ref', 'v1.3.0', '--version', '1.2.0', '--jar', 'versions/PerfScope-1.2.0.jar'],
    expect: 1,
    needsBuild: true,
  },
  {
    id: 'wrong-archive-vs-ref-blobs',
    claim: 'same mismatch, no build: config.yml bytes differ, so it still FAILS',
    argv: ['--ref', 'v1.3.0', '--version', '1.2.0', '--jar', 'versions/PerfScope-1.2.0.jar', '--skip-fresh-build'],
    expect: 1,
    needsBuild: false,
  },
  {
    id: 'ref-without-build-is-incomplete',
    claim: '--ref + --skip-fresh-build cannot prove byte identity, so it must exit 3 (INCOMPLETE), '
      + 'never 0 and never 1',
    argv: ['--ref', 'v1.3.0', '--version', '1.3.0', '--skip-fresh-build'],
    expect: 3,
    needsBuild: false,
  },
  {
    id: 'unresolvable-ref',
    claim: 'a ref that does not exist is a harness error (exit 2), not a silent pass',
    argv: ['--ref', 'refs/heads/this-branch-does-not-exist', '--version', '1.3.0'],
    expect: 2,
    needsBuild: false,
  },
  {
    id: 'head-mode-still-fails-when-ahead',
    claim: 'default mode (no --ref) keeps its old verdict: FAIL because HEAD is ahead of the tag. '
      + 'It needs a HEAD-built jar, which this harness only has if one is passed via --target',
    argv: null, // handled below: depends on a HEAD build existing
    expect: 1,
    needsBuild: false,
  },
];

function runCase(c) {
  const args = [VERIFY, '--repo', REPO, ...c.argv];
  const before = git(['rev-parse', 'HEAD']);
  let code = 0;
  let out = '';
  try {
    out = execFileSync(process.execPath, args, {
      cwd: REPO, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 32 * 1024 * 1024,
    });
  } catch (e) {
    code = typeof e.status === 'number' ? e.status : 99;
    out = `${e.stdout || ''}${e.stderr || ''}`;
  }
  const after = git(['rev-parse', 'HEAD']);
  return { code, out, headStable: before === after };
}

const skipHead = [];
const cases = FAST ? CASES.filter(c => !c.needsBuild && c.argv) : CASES;
const rows = [];

for (const c of cases) {
  if (!c.argv) { skipHead.push(c); continue; }
  const r = runCase(c);
  rows.push({ id: c.id, claim: c.claim, expected: c.expect, actual: r.code, ok: r.code === c.expect && r.headStable });
  if (!AS_JSON) {
    const tag = r.code === c.expect ? 'OK  ' : 'BAD ';
    console.log(`[${tag}] ${c.id}: expected exit ${c.expect}, got ${r.code}${r.headStable ? '' : ' (HEAD MOVED!)'}`);
    console.log(`       ${c.claim}`);
    if (r.code !== c.expect) {
      const tail = r.out.trim().split('\n').slice(-12).join('\n         ');
      console.log(`         actual output tail:\n         ${tail}`);
    }
  }
}

// HEAD-mode case: only meaningful with a jar built from HEAD. Look for a --target the caller
// supplied, and otherwise report it as not covered rather than pretending.
const headTargetArg = process.argv.indexOf('--head-target');
const headTarget = headTargetArg >= 0 ? process.argv[headTargetArg + 1] : '';
const headCase = skipHead.find(c => c.id === 'head-mode-still-fails-when-ahead');
if (headCase) {
  if (headTarget && fs.existsSync(headTarget)) {
    const r = runCase({ ...headCase, argv: ['--version', '1.3.0', '--skip-fresh-build', '--target', headTarget] });
    // The claim has two halves: HEAD mode must still report FAIL, and it must say so out loud.
    const saysHead = /baseline : HEAD /.test(r.out);
    rows.push({ id: headCase.id, claim: headCase.claim, expected: headCase.expect, actual: r.code, ok: r.code === headCase.expect && saysHead });
    if (!AS_JSON) {
      console.log(`[${r.code === headCase.expect && saysHead ? 'OK  ' : 'BAD '}] ${headCase.id}: expected exit ${headCase.expect}, got ${r.code}; baseline labelled HEAD=${saysHead}`);
    }
  } else if (!AS_JSON) {
    console.log(`[SKIP] ${headCase.id}: no HEAD-built jar supplied (pass --head-target <jar>` +
      ` after building extraneous to this harness)`);
    rows.push({ id: headCase.id, claim: headCase.claim, expected: headCase.expect, actual: null, ok: null });
  }
}

// Repo safety, asserted rather than assumed.
const dirty = git(['status', '--porcelain']) || '';
const touched = dirty.split('\n').filter(Boolean).filter(l => /^\s*[MADRC?]{1,2}\s+(tools\/verify-release-artifact(\.controls)?\.mjs|src\/)/.test(l));

const bad = rows.filter(r => r.ok === false);
const status = bad.length ? 'fail' : rows.some(r => r.ok === null) ? 'incomplete' : 'pass';

if (AS_JSON) {
  console.log(JSON.stringify({ repo: REPO, status, cases: rows, trackedChanges: touched }, null, 2));
} else {
  console.log('');
  console.log('='.repeat(74));
  console.log(`CONTROLS: ${status.toUpperCase()} (${rows.filter(r => r.ok).length}/${rows.length} cases behaved as claimed)`);
  if (status === 'incomplete') {
    console.log('  at least one case was not run; that is not a pass');
  }
  console.log(`  (${touched.length} tracked change(s) in tools/ or src/ -- the harness itself writes nothing)`);
}
process.exitCode = status === 'fail' ? 1 : status === 'incomplete' ? 3 : 0;
