/**
 * scenario-model.mjs -- the ONE definition of "what counts as a negative assertion and when
 * does its matcher fire", shared by the two gates that judge scenario assertions:
 *
 *   tools/check-scenarios.mjs    (S2: every negative assertion needs a firing control)
 *   tools/negative-controls.mjs  (executes those controls, standalone)
 *
 * Why a shared module: the two gates used to keep SEPARATE copies of the same criteria --
 * both the matcher construction (variants/regex/literal) and, worse, the samples themselves
 * (negative-controls.mjs carried a hand-maintained table that had drifted one string away
 * from the scenario's `negativeSamples`). Two copies of one criterion can hand back opposite
 * verdicts for the same check, which is what happened: `check-scenarios.mjs` went red on
 * up02 while `negative-controls.mjs` passed it, for the same scenario file.
 *
 * Direction of sharing: the harness scenario JSON is the SOURCE, both tools are READERS.
 * The reverse (harness importing from tools/) cannot work -- the harness has to run on a
 * machine that does not have this repo checked out, so the scenario files can never depend
 * on it. Keeping the samples in the scenario also puts them next to the pattern they must
 * fire, where a reviewer actually sees both.
 */

import fs from 'node:fs';
import path from 'node:path';

/** `--name value` from an argv array, else `dflt`. */
export function argOf(argv, name, dflt) {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : dflt;
}

/**
 * Location of the harness. It is not in this repo and not always at a fixed relative depth;
 * the real layout is <Server plugin>/testharness beside <Server plugin>/PerfScope/PerfScope
 * (three levels up). Search upward rather than hard-code a depth, so a different checkout
 * layout is found instead of silently yielding an empty scenario set.
 */
export function findHarness(repoRoot) {
  let dir = repoRoot;
  for (let i = 0; i < 4; i++) {
    const candidate = path.join(dir, 'testharness');
    if (fs.existsSync(path.join(candidate, 'tests', 'scenarios'))) return candidate;
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return path.join(repoRoot, '..', '..', 'testharness');   // best guess, used only for the error
}

/**
 * Scenario directory, or `null` when it is not there. Callers turn null into exit 2 --
 * "could not run" must never share an exit code with "ran and found a failure".
 */
export function scenarioDir(harnessDir) {
  const dir = path.join(harnessDir, 'tests', 'scenarios');
  return fs.existsSync(dir) ? dir : null;
}

/** The scenario files both gates judge. One glob, so the two cannot cover different sets. */
export function scenarioFiles(scenDir, only = '') {
  return fs.readdirSync(scenDir)
    .filter(f => /^perfscope-.*\.json$/.test(f))
    .filter(f => !only || f === `${only}.json` || f === only)
    .sort();
}

/** The matcher the harness would build for a text field. Same rules as Select-MatchVariant. */
export function variantsOf(v) {
  if (v == null) return [];
  if (Array.isArray(v)) return v.flatMap(x => (typeof x === 'string' ? [x] : []));
  if (typeof v === 'string') return [v];
  return [];
}

/**
 * A check is negative when it asserts absence: `expectNot`, or `notPresent` over `pattern`.
 * (This mirrors `Invoke-Check` in the harness: type `log` + `notPresent` uses `pattern`,
 * everything else uses `expectNot`.)
 */
export function isNegativeCheck(c) {
  return (Array.isArray(c.expectNot) || typeof c.expectNot === 'string' || c.notPresent === true);
}

/** The patterns a negative check is judged against, plus the match mode. */
export function negativePatterns(c) {
  if (c.notPresent === true) return { patterns: variantsOf(c.pattern), asRegex: c.regex === true };
  return { patterns: variantsOf(c.expectNot), asRegex: c.regex === true };
}

/**
 * Occurrences of the check's patterns in `text`, summed exactly like the harness's
 * Count-MatchVariant (global regex; non-overlapping literal scan). A count contract
 * (maxMatches/minMatches) is judged against this number, so a control that matches TWICE
 * must not be reported as a clean single hit.
 */
export function countMatches(c, text) {
  if (!text) return 0;
  const { patterns, asRegex } = negativePatterns(c);
  let n = 0;
  for (const p of patterns) {
    if (typeof p !== 'string' || p === '') continue;
    if (asRegex) {
      try {
        n += (text.match(new RegExp(p, 'g')) || []).length;
      } catch {
        // An unparseable regex matches nothing -- same as the harness, which would throw
        // at run time; the linter reports that separately (S6/S1).
      }
    } else {
      let i = 0;
      while ((i = text.indexOf(p, i)) >= 0) {
        n++;
        i += p.length;
      }
    }
  }
  return n;
}

/** Would this check's matcher fire on `text` at all? (the S2 control question) */
export function matchesAny(c, text) {
  return countMatches(c, text) > 0;
}

/** Does the check declare a count contract? If so a "hit" must land inside it. */
export function hasCountContract(c) {
  return c.maxMatches !== undefined || c.minMatches !== undefined;
}

/**
 * Would the check PASS on this window text? Only meaningful for count contracts; a plain
 * negative passes on absence (count 0). Used by the miss-side control: a "must not match"
 * sample is only a fair control if the check really is silent on it.
 */
export function checkPassesOn(c, text) {
  const n = countMatches(c, text);
  if (hasCountContract(c)) {
    const max = Number(c.maxMatches ?? 0);
    const min = Number(c.minMatches ?? 0);
    return n <= max && n >= min;
  }
  return n === 0;
}

/**
 * Judge a negative check's declared controls. ONE implementation, called by both gates.
 *
 * A control has to answer two separate questions, and a one-sided sample can only answer
 * one of them:
 *
 *   1. CAN this check ever go RED?  -> some `negativeSamples` entry (text the guarded
 *      failure emits) must make it fail. This is the original S2 rule, restated in terms of
 *      the check's verdict instead of the pattern's match count, so that it also works for
 *      a check written as a count contract. The two are identical for a plain `notPresent`
 *      (fails exactly when the pattern matches), and diverge only where it matters: a
 *      count-contract check also fails when its required half is MISSING.
 *
 *   2. Is the forbidden content really what flips the verdict? -> for a count contract the
 *      answer cannot come from one sample. `escalation alone` and `escalation plus a
 *      forbidden recovery line` BOTH make up02 red if you only ask question 1 -- the second
 *      for the right reason, the first because the required escalation half is absent. A
 *      sample that goes red for the wrong reason is not a control. So a count-contract
 *      check must also supply a `cleanSamples` entry that PASSES, and one failure sample
 *      must be that clean text plus the forbidden content (prefix relation). Only that pair
 *      shows the negative half is the discriminator.
 *
 * This is why up02's old sample -- a bare recovery line, copied from alert05 where the very
 * same string is the CORRECT sample -- was rejected here: it never exercised up02's
 * escalation half at all, so it would have "passed" the control by proving the wrong thing.
 *
 * Returns `declared: false` when the check carries no samples at all; the caller decides
 * what to do with that (check-scenarios.mjs turns it into S2-NEGATIVE-NO-CONTROL).
 */
export function judgeControl(c) {
  const failSamples = variantsOf(c.negativeSamples);
  const cleanSamples = variantsOf(c.cleanSamples);
  if (failSamples.length === 0 && cleanSamples.length === 0) return { declared: false, findings: [] };

  const findings = [];
  const add = (sev, rule, msg) => findings.push({ sev, rule, msg });

  const trippingClean = cleanSamples.filter(s => !checkPassesOn(c, s));
  if (cleanSamples.length > 0 && trippingClean.length > 0) {
    add('ERROR', 'S2-CLEAN-SAMPLE-TRIPS',
      `cleanSamples must be text this check PASSES on, but ${trippingClean.length}/${cleanSamples.length} `
      + `make it fail: ${trippingClean.map(s => JSON.stringify(s.slice(0, 80))).join(', ')}`);
  }

  const firing = failSamples.filter(s => !checkPassesOn(c, s));
  if (failSamples.length > 0 && firing.length === 0) {
    add('ERROR', 'S2-CONTROL-NEVER-MATCHES',
      `${failSamples.length} failure sample(s) supplied but NONE makes this check fail -- the control is `
      + `wrong, or the pattern is dead. samples: ${JSON.stringify(failSamples.slice(0, 3))}`);
  }

  if (hasCountContract(c)) {
    // The pair rule (see the header): a count contract is the one shape where "it went red"
    // alone is ambiguous, so it must also show the healthy case and show the difference.
    const passing = cleanSamples.filter(s => checkPassesOn(c, s));
    if (passing.length === 0) {
      add('ERROR', 'S2-NO-CLEAN-SAMPLE',
        'this check has a count contract (minMatches/maxMatches), so a failure sample alone cannot '
        + 'show that the forbidden content is what flips the verdict -- add cleanSamples: '
        + '[<text of a healthy window this check passes on>]');
    } else {
      const discriminated = firing.some(f => passing.some(p => p !== '' && f.startsWith(p)));
      if (!discriminated) {
        add('ERROR', 'S2-NO-DISCRIMINATOR',
          'no failure sample is a clean sample plus the forbidden content, so nothing proves the '
          + 'negative half decides the verdict (a sample may be failing only because the required '
          + 'half is missing -- text no real failure would produce)');
      }
    }
  }

  return { declared: true, findings };
}
