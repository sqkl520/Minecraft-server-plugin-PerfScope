// Add `negativeSamples` to the 17 negative assertions the linter flags with S2.
//
// Why: a negative assertion proves nothing on its own -- it passes both when the guarded
// failure never happens AND when the pattern can never match anything. `negativeSamples`
// records literal text the failure WOULD emit; the linter then checks that at least one
// sample actually matches this check's own matcher.
//
// Samples come from what the product/JVM really prints on failure (verified against real
// console output and against the message templates in src/main/resources + *Messages.java):
//   - JVM:      "java.lang.NullPointerException: ..." / "java.util.ConcurrentModificationException"
//   - plugin:   "at PerfScope-1.2.1.jar/cn.perfscope.X.y(X.java:NNN)"  (Paper frame format)
//   - messages: "monitor push failed" / "console push failed" / "控制台推送失败"
//   - recovery: "[Alert][Recovery][tag] <display> recovered | key=memory:global | lasted ..."
const fs = require('fs');
const path = require('path');

const DIR = 'D:/Server/MCServers/Server plugin/testharness/tests/scenarios';

const JVM_FRAMES = [
  'java.util.ConcurrentModificationException: null',
  'java.lang.NullPointerException: Cannot invoke "String.isEmpty()"',
  'at PerfScope-1.2.1.jar/cn.perfscope.MetricsCollector.collectTPS(MetricsCollector.java:1316)',
];

const PUSH_FAILURES = [
  '[PerfScope] monitor push failed (skipped this round): boom',
  '[PerfScope] console push failed: boom',
  '[PerfScope] 控制台推送失败: boom',
];

const RECOVERY_MEM = [
  '[PerfScope] [Alert][Recovery][TAG] Heap usage high recovered | key=memory:global | lasted 5m, no breach for 3 consecutive check cycles',
];

const SAMPLES = {
  'soak03a-clean-window-seg1': [...JVM_FRAMES, ...PUSH_FAILURES],
  'soak03b-clean-window-seg2': [...JVM_FRAMES, ...PUSH_FAILURES],
  'soak03c-clean-window-seg3': [...JVM_FRAMES, ...PUSH_FAILURES],
  'soak03d-clean-window-seg4': [...JVM_FRAMES, ...PUSH_FAILURES],
  'soak03e-clean-window-seg5': [...JVM_FRAMES, ...PUSH_FAILURES],
  'soak03f-clean-window-seg6': [...JVM_FRAMES, ...PUSH_FAILURES],
  'soak03g-clean-window-seg7': [...JVM_FRAMES, ...PUSH_FAILURES],
  'soak03h-clean-window-seg8': [...JVM_FRAMES, ...PUSH_FAILURES],
  'alert05-mid-alert-reload-no-fake-recovery': RECOVERY_MEM,
  'up02-no-old-recovery-on-upgrade': RECOVERY_MEM,
  'inject06-no-stacktrace-in-console': [
    'at PerfScope-1.2.1.jar/cn.perfscope.MetricsCollector.collectTPS(MetricsCollector.java:1316)',
    'at cn.perfscope.MetricsCollector.collectTPS(MetricsCollector.java:1316)',
  ],
  'mon02-kick-mid-monitor-console-clean': [...JVM_FRAMES, ...PUSH_FAILURES],
  'mon02b-post-kick-still-clean': [...JVM_FRAMES, ...PUSH_FAILURES],
  'cmd02-noarg-no-exception': [
    'java.lang.NullPointerException',
    'java.lang.ArrayIndexOutOfBoundsException',
    'at PerfScope-1.2.1.jar/cn.perfscope.command.PerfScopeCommand',
  ],
  't02-mspt-no-estimate-marker': [' (estimated)', ' (估算)'],
  't02-noarg-no-estimate-marker': [' (estimated)', ' (估算)'],
  // The guard is "a player WITHOUT perfscope.alert must not receive alert pushes".
  // Sample = an alert push line; if it shows up for that player, the guard failed.
  'perms05b-no-perm-player-no-push': ['[PerfScope] [Alert]', 'System CPU load high'],
};

const report = [];
for (const f of fs.readdirSync(DIR).filter(x => x.startsWith('perfscope-') && x.endsWith('.json'))) {
  const p = path.join(DIR, f);
  const j = JSON.parse(fs.readFileSync(p, 'utf8'));
  let n = 0;
  for (const c of j.checks) {
    if (!SAMPLES[c.id]) continue;
    if (c.notPresent !== true && !c.expectNot) continue;
    c.negativeSamples = SAMPLES[c.id];
    n++;
  }
  if (n) {
    fs.writeFileSync(p, JSON.stringify(j, null, 4) + '\n');
    report.push(`${f}: ${n} check(s)`);
  }
}
console.log(report.join('\n') || 'no changes');
