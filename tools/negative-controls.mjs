// Positive-control tests for the negative assertions in the perfscope scenarios.
//
// A negative assertion (`notPresent` / `expectNot`) proves nothing on its own: it passes both
// when the guarded failure never happens AND when the pattern can never match anything. The
// only way to tell those apart is a positive control -- feed the pattern text it is SUPPOSED
// to catch and confirm it does.
//
// This is a unit-level control: it validates the matcher, not the running server. It answers
// "would this assertion fire if the failure occurred?", which is exactly what S2 asks for.
//
// Run: node tools/negative-controls.mjs
// Exit 0 = every negative assertion has at least one firing sample.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const SCEN_DIR = 'D:/Server/MCServers/Server plugin/testharness/tests/scenarios';

// For each negative check that the linter flagged, one or more samples that MUST match.
// Each sample is real text the product or the JVM would emit when the guarded failure occurs.
const CONTROLS = {
  // --- soak: a 30-minute clean window must contain none of these -------------------
  'soak03a-clean-window-seg1': [
    'java.util.ConcurrentModificationException: null',
    'java.lang.NullPointerException: Cannot invoke "String.isEmpty()"',
    'at PerfScope-1.2.1.jar/cn.perfscope.MetricsCollector.collectTPS(MetricsCollector.java:1316)',
    '[PerfScope] monitor push failed (skipped this round): boom',
    '[PerfScope] console push failed: boom',
    '[PerfScope] 控制台推送失败: boom',
  ],
  'soak03b-clean-window-seg2': null, // same pattern as seg1 -- filled in below
  'soak03c-clean-window-seg3': null,
  'soak03d-clean-window-seg4': null,
  'soak03e-clean-window-seg5': null,
  'soak03f-clean-window-seg6': null,
  'soak03g-clean-window-seg7': null,
  'soak03h-clean-window-seg8': null,

  // --- alert / alertv2: no fake recovery line for memory:global -------------------
  'alert05-mid-alert-reload-no-fake-recovery': [
    '[PerfScope] [Alert][Recovery][TAG] Heap usage high recovered | key=memory:global | lasted 5m, no breach for 3 consecutive check cycles',
  ],
  'up02-no-old-recovery-on-upgrade': [
    '[PerfScope] [Alert][Recovery][TAG] Heap usage high recovered | key=memory:global | lasted 5m',
  ],

  // --- inject: no plugin stack frames when debug=false ----------------------------
  'inject06-no-stacktrace-in-console': [
    'at PerfScope-1.2.1.jar/cn.perfscope.MetricsCollector.collectTPS(MetricsCollector.java:1316)',
    'at cn.perfscope.MetricsCollector.collectTPS(MetricsCollector.java:1316)',
  ],

  // --- vbot: kick mid-monitor must not blow up the monitor task -------------------
  'mon02-kick-mid-monitor-console-clean': [
    'java.util.ConcurrentModificationException: null',
    'java.lang.NullPointerException',
    '[PerfScope] monitor push failed (skipped this round): boom',
  ],
  'mon02b-post-kick-still-clean': [
    'java.util.ConcurrentModificationException: null',
    'java.lang.NullPointerException',
    '[PerfScope] monitor push failed (skipped this round): boom',
  ],

  // --- surface: /perfscope with no args must not throw ---------------------------
  'cmd02-noarg-no-exception': [
    'java.lang.NullPointerException',
    'java.lang.ArrayIndexOutOfBoundsException',
    'at PerfScope-1.2.1.jar/cn.perfscope.command.PerfScopeCommand',
  ],

  // --- surface: the "(estimated)" marker must not leak onto the exact MSPT path ---
  't02-mspt-no-estimate-marker': [' (estimated)', ' (估算)'],
  't02-noarg-no-estimate-marker': [' (estimated)', ' (估算)'],

  // --- perms: a player without the alert permission must get no push -------------
  'perms05b-no-perm-player-no-push': [
    '[PerfScope] [Alert]',
    'System CPU load high',
  ],
};

// seg2..seg8 share seg1's pattern verbatim.
const SOAK_PATTERN = CONTROLS['soak03a-clean-window-seg1'];
for (const id of Object.keys(CONTROLS)) {
  if (id.startsWith('soak') && CONTROLS[id] === null) CONTROLS[id] = SOAK_PATTERN;
}

const scenarios = new Map();
for (const f of ['perfscope-soak', 'perfscope-alert', 'perfscope-alertv2', 'perfscope-inject',
                 'perfscope-vbot', 'perfscope-surface', 'perfscope-perms']) {
  const j = JSON.parse(readFileSync(join(SCEN_DIR, f + '.json'), 'utf8'));
  for (const c of j.checks) scenarios.set(c.id, c);
}

let fail = 0, checked = 0, skipped = 0;

for (const [id, samples] of Object.entries(CONTROLS)) {
  const c = scenarios.get(id);
  if (!c) { console.log(`SKIP  ${id}  (not found in scenarios)`); skipped++; continue; }

  const isNegative = c.notPresent === true || Array.isArray(c.expectNot) || typeof c.expectNot === 'string';
  if (!isNegative) { console.log(`SKIP  ${id}  (not a negative assertion)`); skipped++; continue; }

  // Build the matcher the harness would use.
  const pats = c.notPresent === true
    ? [].concat(c.pattern ?? [])
    : [].concat(c.expectNot ?? []);
  const asRegex = c.regex === true;

  const matchers = pats.map(p => {
    if (typeof p !== 'string') return () => false;
    if (asRegex) {
      try { const re = new RegExp(p); return s => re.test(s); }
      catch { return () => false; }
    }
    return s => s.includes(p);
  });

  const hit = samples.some(s => matchers.some(m => m(s)));
  checked++;
  if (hit) {
    console.log(`ok    ${id}`);
  } else {
    fail++;
    console.log(`FAIL  ${id}  -- NONE of the ${samples.length} failure sample(s) matched.`);
    console.log(`        patterns (${asRegex ? 'regex' : 'literal'}): ${JSON.stringify(pats)}`);
    for (const s of samples) console.log(`        sample: ${JSON.stringify(s.slice(0, 100))}`);
  }
}

console.log(`\nNEGATIVE CONTROLS: ${checked} checked, ${fail} failed, ${skipped} skipped`);
process.exit(fail === 0 ? 0 : 1);
