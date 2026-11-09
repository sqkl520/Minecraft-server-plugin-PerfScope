// Positive-control test for the S2 `negativeSamples` rule itself.
//
// A lint rule that never fires is worse than no rule. This proves S2-CONTROL-NEVER-MATCHES
// actually triggers: temporarily corrupt one check's samples, run the linter, expect an ERROR,
// then restore the file byte-for-byte.
const fs = require('fs');
const { execFileSync } = require('child_process');

const SCEN = 'D:/Server/MCServers/Server plugin/testharness/tests/scenarios/perfscope-surface.json';
const REPO = 'D:/Server/MCServers/Server plugin/PerfScope/PerfScope';

const original = fs.readFileSync(SCEN, 'utf8');
let restored = false;

try {
  const j = JSON.parse(original);
  const c = j.checks.find(x => x.id === 't02-mspt-no-estimate-marker');
  if (!c) throw new Error('target check not found');
  // A control that can never match this check's patterns.
  c.negativeSamples = ['THIS_STRING_NEVER_MATCHES_ANYTHING_XYZ'];
  fs.writeFileSync(SCEN, JSON.stringify(j, null, 4) + '\n');

  let out = '';
  try {
    out = execFileSync('node', ['tools/check-scenarios.mjs', '--repo', REPO,
      '--harness', 'D:/Server/MCServers/Server plugin/testharness'],
      { cwd: REPO, encoding: 'utf8' });
  } catch (e) {
    out = (e.stdout || '') + (e.stderr || '');
  }

  const caught = /S2-CONTROL-NEVER-MATCHES/.test(out);
  console.log(caught
    ? 'ok    S2-CONTROL-NEVER-MATCHES fired on a deliberately-wrong control'
    : 'FAIL  the rule did NOT fire -- a bad control would slip through silently');
  if (caught) {
    const line = out.split('\n').find(l => l.includes('S2-CONTROL-NEVER-MATCHES'));
    console.log('      ' + (line || '').trim());
  }
  process.exitCode = caught ? 0 : 1;
} finally {
  if (!restored) {
    fs.writeFileSync(SCEN, original);
    console.log('      (scenario restored)');
  }
}
