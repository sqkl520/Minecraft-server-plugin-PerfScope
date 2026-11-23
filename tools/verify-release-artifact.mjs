#!/usr/bin/env node
// verify-release-artifact.mjs -- L2 gate: "the jar we are about to publish IS this source".
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
//   [4b] RESOURCES  jar entry vs source blob, byte for byte. Only runs when no fresh build
//                   exists (otherwise CONTENT already covers it). This is the only byte-level
//                   evidence left under --skip-fresh-build.
//
// Plus [5] GIT: the baseline commit, tag position and tree state -- the bookkeeping half.
//
// Three result states, not two: PASS / FAIL / SKIP (plus INFO for facts that assert nothing).
// SKIP means the comparison could not be made, and a run containing one exits 3, never 0 --
// a gate that verified less must not exit like a pass.
//
// ======================================================================================
// BASELINE: HEAD by default, or a git ref with --ref
// ======================================================================================
// The comparison is only meaningful against a known commit. Two modes:
//
//   (default)    baseline = HEAD / the working tree. Answers "does this jar match what is
//                checked out RIGHT NOW". Right before a release at the tag commit this is the
//                strongest check. During normal development (HEAD ahead of the tag) it reports
//                FAIL for a perfectly good archive -- that is expected, not an incident. The
//                header says "baseline: HEAD" and spells out the distance to the tag so this
//                cannot be misread as "the archive is broken".
//
//   --ref <tag>  baseline = that ref. Every "source side" input (config.yml keys, message key
//                tables, plugin.yml, pom) is read from the ref's git blobs, and the fresh build
//                is a temporary extraction of the ref built outside the repo. HEAD and the
//                working tree are not consulted at all, so being ahead of the tag is not a
//                failure: it is reported as information.
//
// How --ref gets a build without touching the repo:
//   git archive --format=zip -o <tmp>/src.zip <ref>   (read-only, uses no index/worktree)
//   -> unzip into <tmp>/tree (this file's own zip reader, no external tools)
//   -> mvn clean package in <tmp>/tree
// The temp directory lives under os.tmpdir(), never inside the repo. `git worktree add` was
// rejected on purpose: it writes .git/worktrees/, and a failed/removed worktree tends to leave
// stale registrations that later break `git status`/`git worktree list` for the user.
//
// What --ref does NOT prove (say it out loud):
//   - it does not prove the ref is a good release (tests, docs, config semantics);
//   - it does not prove the jar attached to the GitHub Release is the same file as versions/;
//     download it and compare sha256 for that.
//
// ======================================================================================
// REPRODUCING THE CHECKS (tools/ root; PowerShell 5.1)
// ======================================================================================
//   # 1. archive vs the release tag -- the one that must be green after every release
//   node tools/verify-release-artifact.mjs --ref v1.3.0 --version 1.3.0
//
//   # 2. same, machine-readable
//   node tools/verify-release-artifact.mjs --ref v1.3.0 --version 1.3.0 --json
//
//   # 3. archive vs the current worktree (unchanged legacy behaviour; HEAD is the baseline)
//   node tools/verify-release-artifact.mjs --version 1.3.0
//
//   # 4. blob-only audit of a tag, no build at all -> [1][2] are SKIP, exit 3 (incomplete)
//   node tools/verify-release-artifact.mjs --ref v1.3.0 --version 1.3.0 --skip-fresh-build
//
//   # 5. prove the ref/build path is sound at all (archive built from the tag reproduces)
//   node tools/verify-release-artifact.mjs --ref v1.3.0 --version 1.3.0 && echo BYTE-IDENTICAL
//
//   # 6. negative control: expect FAIL. 1.2.0 archive vs the 1.3.0 tag must not pass
//   node tools/verify-release-artifact.mjs --ref v1.3.0 --version 1.2.0 --jar versions/PerfScope-1.2.0.jar
//
//   # 7. the same controls as an automated suite (asserts each exit code, 7 cases)
//   node tools/verify-release-artifact.controls.mjs            # --fast: blob-only, no Maven
//
//   # 8. byte-level verdict using the SAME JDK CI uses (Temurin 17). Without --jdk this machine
//   #    builds on whatever JAVA_HOME says (often 21/25), and SHA256/CONTENT degrade to SKIP
//   #    (exit 3, INCOMPLETE) because class-file bytes are JDK-dependent.
//   node tools/verify-release-artifact.mjs --jdk "C:\\Path\\to\\temurin-17" --ref v1.4.5-SNAPSHOT-fix1 --version 1.4.5-SNAPSHOT-fix1
//
// Full write-up -- what --ref can and cannot prove, and why git worktree was rejected:
//   tools/VERIFY-RELEASE-ARTIFACT.md
//
// Exit: 0 = every comparison ran and agreed,
//       1 = at least one FAIL,
//       2 = could not run (missing input, unresolvable ref),
//       3 = nothing failed but at least one comparison was SKIPPED -- the archive is NOT fully
//           verified. Never reported as PASS; a gate that verified less must not exit 0.
//
// Usage:
//   node tools/verify-release-artifact.mjs                          # auto-discovers version
//   node tools/verify-release-artifact.mjs --version 1.3.0
//   node tools/verify-release-artifact.mjs --ref v1.3.0 [--version 1.3.0]
//   node tools/verify-release-artifact.mjs --jar <path> --target <path>
//   node tools/verify-release-artifact.mjs --jdk <path-to-jdk-home>  # build with THAT JDK
//   node tools/verify-release-artifact.mjs --skip-fresh-build       # compare against existing target/
//   node tools/verify-release-artifact.mjs --keep-workdir           # keep the --ref temp build dir
//   node tools/verify-release-artifact.mjs --json
//
// ======================================================================================
// JDK IDENTITY: the release is built by CI's Temurin 17 on Linux; this machine has 21/25
// ======================================================================================
// The shipped jar carries `Build-Jdk-Spec` in its manifest and JDK-version-dependent bytes in
// the class constant pool (e.g. the `source` of a record's attribute, StackMapTable framing).
// Building here on a different JDK than CI used cannot reproduce those bytes, so SHA256 and the
// per-entry CONTENT comparison legitimately differ even for a perfectly good archive. Before
// `--jdk` existed this was reported as FAIL -- a false alarm on every run, which trains people
// to ignore the gate.
//
// Now the JDK used for the fresh build is an explicit input:
//   * `--jdk <home>`  build with that JDK's java (sets JAVA_HOME for the Maven child);
//   * otherwise       the ambient JAVA_HOME (or the mvn default if unset).
// Then we compare the fresh build's manifest `Build-Jdk-Spec` (major version only) against the
// archive's. When they differ, SHA256 and CONTENT are NOT failures -- they degrade to SKIP, and
// the run reports INCOMPLETE (exit 3). Key-level checks (CONFIG / MESSAGES / plugin.yml) do not
// depend on the JDK and keep running, so the run still proves a lot.
//
// Why compare major version and not the exact build string: `17.0.20.1` (Microsoft) vs
// `17.0.11` (Temurin) is the same class-file target and reproduces identically; only the major
// version changes the version-dependent bytes we care about. Vendor-specific manifest lines are
// compared with `Build-Jdk-Spec` only.
//
// If you DO have the CI-matching JDK, pass it and expect a real PASS:
//   node tools/verify-release-artifact.mjs --ref v1.4.5-SNAPSHOT-fix1 --version ... --jdk "C:\\Path\\to\\temurin-17"
//
// Design note: this script never writes to the repo and never bumps anything. --ref extracts to
// and builds in os.tmpdir() only. It never runs git stash/checkout/switch and never creates a
// worktree, so it is safe to run while the user has uncommitted changes or other agents are
// working in the same checkout.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

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
const REF_INPUT = argOf('--ref', '');
const KEEP_WORKDIR = flag('--keep-workdir');
const JDK_ARG = argOf('--jdk', '');
const MODE = REF_INPUT ? 'ref' : 'head';

const results = [];
const rec = (id, ok, detail) => results.push({ id, status: ok ? 'pass' : 'fail', detail });
const fail = (id, detail) => rec(id, false, detail);
const pass = (id, detail) => rec(id, true, detail);
const skip = (id, why) => results.push({ id, status: 'skip', detail: why });
const note = (id, detail) => results.push({ id, status: 'info', detail });
const info = (msg) => { if (!AS_JSON) console.log(msg); };

// archive-release.ps1 is NOT in this repo -- it lives in the parent workspace's tools/
// directory (D:\Server\MCServers\Server plugin\tools\), alongside the other plugin repos and
// deliberately outside git. It never travelled with the backend repo, so "tools/archive-release.ps1"
// as printed to the user was a path that does not resolve from a fresh clone. Resolve it
// relative to REPO and fall back to an honest description when the sibling checkout is absent.
const ARCHIVE_TOOL = (() => {
  const rel = path.resolve(REPO, '..', 'tools', 'archive-release.ps1');
  const abs = path.resolve(REPO, 'tools', 'archive-release.ps1');
  if (fs.existsSync(rel)) return { path: rel, where: 'workspace (outside the repo)' };
  if (fs.existsSync(abs)) return { path: abs, where: 'in the repo' };
  return { path: rel, where: 'MISSING -- workspace copy not found' };
})();
const archiveToolHint = () =>
  `tools/archive-release.ps1 is ${ARCHIVE_TOOL.where}: ${ARCHIVE_TOOL.path}`;

// --------------------------------------------------------------------------------------
// small helpers
// --------------------------------------------------------------------------------------
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const sha256File = (f) => sha256(fs.readFileSync(f));

/** Read pom <version> and <artifactId> without an XML parser. */
function readPom(xml) {
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

function readZip(file) {
  const buf = fs.readFileSync(file);
  // End of central directory: scan backwards for 0x06054b50
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error(`not a zip: ${file}`);
  const count = buf.readUInt16LE(eocd + 10);
  if (count === 0xffff) throw new Error(`zip64 central directory not supported: ${file}`);
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
/** Same as git(), but raw bytes and throws -- for blobs and binaries (git show/archive). */
const gitRaw = (args) => execFileSync('git', args, {
  cwd: REPO, stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 512 * 1024 * 1024,
});

// ======================================================================================
// 0. Resolve the baseline (HEAD or --ref)
// ======================================================================================
info('');
info('PerfScope release artifact verification');
info(`  repo : ${REPO}`);

const headShort = git(['rev-parse', '--short', 'HEAD']);
const headFull = headShort ? git(['rev-parse', 'HEAD']) : null;

/** @type {null | {spec:string, commit:string, short:string, isTag:boolean}} */
let REF = null;
if (MODE === 'ref') {
  const commit = git(['rev-parse', '--verify', '--quiet', `${REF_INPUT}^{commit}`]);
  if (!commit) {
    console.error(`ERROR: --ref ${REF_INPUT} does not resolve to a commit in ${REPO}`);
    console.error('       (fetch tags first: git fetch --tags)');
    process.exit(2);
  }
  const asTag = git(['rev-parse', '--verify', '--quiet', `refs/tags/${REF_INPUT}^{commit}`]);
  REF = { spec: REF_INPUT, commit, short: commit.slice(0, 7), isTag: asTag === commit };
}

// Source-side access. In ref mode every source input comes from the ref's blobs, so the answer
// cannot depend on what happens to be checked out.
const srcFile = (rel) => {
  if (MODE === 'ref') return gitRaw(['show', `${REF.commit}:${rel}`]);
  return fs.readFileSync(path.join(REPO, rel));
};
const srcText = (rel) => srcFile(rel).toString('utf8');
const BASELINE_LABEL = MODE === 'ref' ? `ref ${REF.spec} (${REF.short})` : `HEAD ${headShort || '?'} (worktree)`;

if (MODE === 'ref') {
  info(`  baseline : ref ${REF.spec} -> ${REF.short}${REF.isTag ? '  (tag)' : ''}   [--ref mode]`);
  info(`             source-side inputs are this ref's git blobs; the fresh build is a temp`);
  info(`             extraction of this ref. HEAD and the working tree are not used.`);
  sweepStaleWorkdirs();
} else {
  info(`  baseline : HEAD ${headShort || '?'} (working tree)   [default mode]`);
  info(`             comparisons use the CURRENT WORKTREE, not any release tag.`);
  info(`             After a release (HEAD ahead of the tag) this mode reports FAIL for a`);
  info(`             correct archive. To verify the archive against its tag instead:`);
  info(`               node tools/verify-release-artifact.mjs --ref v<version> --version <version>`);
}

// ======================================================================================
// 1. pom / version
// ======================================================================================
let pom;
try {
  if (MODE === 'ref') {
    pom = readPom(srcText('pom.xml'));
  } else {
    if (!fs.existsSync(path.join(REPO, 'pom.xml'))) {
      console.error(`ERROR: pom.xml not found in ${REPO}`);
      process.exit(2);
    }
    pom = readPom(fs.readFileSync(path.join(REPO, 'pom.xml'), 'utf8'));
  }
  if (!pom.version || !pom.artifactId) throw new Error('pom.xml has no project version/artifactId');
} catch (e) {
  console.error(`ERROR: cannot read pom.xml (${BASELINE_LABEL}): ${e.message}`);
  process.exit(2);
}

const VERSION = argOf('--version', pom.version);
const JAR_NAME = `${pom.artifactId}-${VERSION}.jar`;
info(`  pom  : ${pom.artifactId} ${VERSION}${VERSION !== pom.version ? ` (pom says ${pom.version})` : ''}  [${BASELINE_LABEL}]`);
if (VERSION !== pom.version) {
  fail('pom version matches requested version',
    `--version ${VERSION} but the pom at ${BASELINE_LABEL} declares ${pom.version}; the build would`
    + ` produce ${pom.artifactId}-${pom.version}.jar`);
}

const ARCHIVE = path.resolve(argOf('--jar', path.join(REPO, 'versions', JAR_NAME)));

if (!fs.existsSync(ARCHIVE)) {
  info('');
  info(`  archive : MISSING  ${ARCHIVE}`);
  info(`  ${archiveToolHint()}`);
  info(`  Run it first, then re-run this check.`);
  console.error(`RESULT: FAIL (archive jar not found)`);
  process.exit(2);
}

// ======================================================================================
// 2. Fresh build
//    head mode : mvn clean package in the repo (existing behaviour)
//    ref  mode : git archive -> temp extraction -> mvn clean package there
// ======================================================================================
// --------------------------------------------------------------------------------------
// JDK identity
// --------------------------------------------------------------------------------------
// A JDK install is a directory with `release` (key=value) and `bin/java[.exe]`. `--jdk` may
// point at either the home or the java launcher; normalise both to the home.
function normalizeJdkHome(p) {
  if (!p) return null;
  let abs = path.resolve(p);
  // allow ".../bin/java" or ".../bin/java.exe"
  if (/[\\/]bin[\\/]java(\.exe)?$/i.test(abs)) abs = path.dirname(path.dirname(abs));
  return abs;
}

/** Read the JDK's `release` file -- present in every modern JDK, unlike needing to run java. */
function jdkInfo(home) {
  const rel = path.join(home, 'release');
  if (!fs.existsSync(rel)) {
    // still accept a dir that has bin/java (some trimmed runtimes drop `release`)
    const javaExe = process.platform === 'win32'
      ? path.join(home, 'bin', 'java.exe') : path.join(home, 'bin', 'java');
    return fs.existsSync(javaExe) ? { home, version: null, vendor: null } : null;
  }
  const txt = fs.readFileSync(rel, 'utf8');
  const get = (k) => {
    const m = txt.match(new RegExp(`^${k}="?([^"\\r\\n]+)"?`, 'm'));
    return m ? m[1] : null;
  };
  // JAVA_VERSION looks like "17.0.20.1" or "21.0.10"; major = leading integer.
  const version = get('JAVA_VERSION');
  const major = version ? parseInt(String(version).split('.')[0], 10) : null;
  const implVendor = get('IMPLEMENTOR') || get('IMPLEMENTOR_URL') || null;
  return { home, version, major, vendor: implVendor };
}

/** What JDK will the Maven child actually use? --jdk wins; else JAVA_HOME; else unknown. */
function effectiveJdk() {
  if (JDK_ARG) {
    const home = normalizeJdkHome(JDK_ARG);
    const infoRec = home && jdkInfo(home);
    if (!infoRec) return { home, info: null, source: '--jdk', invalid: true };
    return { home, info: infoRec, source: '--jdk' };
  }
  if (process.env.JAVA_HOME) {
    const home = normalizeJdkHome(process.env.JAVA_HOME);
    const infoRec = jdkInfo(home);
    return { home, info: infoRec, source: 'JAVA_HOME' };
  }
  return { home: null, info: null, source: 'mvn default (JAVA_HOME unset)' };
}

/** Major JDK version recorded in a jar's manifest as `Build-Jdk-Spec`, or null. */
function jarBuildJdkSpec(zipMap) {
  for (const [name, e] of zipMap) {
    if (!/^META-INF\/MANIFEST\.MF$/i.test(name)) continue;
    const txt = e.data().toString('utf8').replace(/\r\n/g, '\n').replace(/\n /g, '');
    const m = txt.match(/^Build-Jdk-Spec:\s*(\d+)/m);
    return m ? parseInt(m[1], 10) : null;
  }
  return null;
}

function mvnCandidates() {
  // `mvn` is frequently NOT on PATH on this machine (CLAUDE.md pins it to
  // D:\maven\apache-maven-3.9.9\bin\mvn.cmd). Trying the bare name first made the build check
  // fail with spawnSync ENOENT and poisoned the whole run -- a misleading FAIL on a check that
  // had nothing to do with the artifact. Resolve in order: explicit env, PATH, known local path.
  return [process.env.PSCOPE_MVN, 'mvn', 'D:\\maven\\apache-maven-3.9.9\\bin\\mvn.cmd'].filter(Boolean);
}

function runMvn(cwd) {
  let lastErr = '';
  // Force JAVA_HOME (hence the JDK) for the Maven child when --jdk was given. Maven prefers
  // JAVA_HOME over everything else, so this is enough -- no PATH surgery needed.
  const childEnv = { ...process.env };
  const jdk = effectiveJdk();
  if (JDK_ARG && jdk.home) childEnv.JAVA_HOME = jdk.home;
  for (const mvn of mvnCandidates()) {
    try {
      // On Windows a .cmd shim cannot be spawned directly (EINVAL) -- it needs a shell.
      const isWinShim = /\.(cmd|bat)$/i.test(mvn);
      execFileSync(mvn, ['-B', '-ntp', '-q', 'clean', 'package', '-DskipTests'], {
        cwd, stdio: ['ignore', 'inherit', 'inherit'], shell: isWinShim, env: childEnv,
      });
      return { ok: true, mvn };
    } catch (e) {
      lastErr = `${mvn}: ${e.message}`;
    }
  }
  return { ok: false, err: lastErr };
}

let tmpRoot = null;
function cleanupTemp() {
  if (!tmpRoot || KEEP_WORKDIR) return;
  try {
    fs.rmSync(tmpRoot, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 });
  } catch (e) {
    info(`  (could not remove temp dir ${tmpRoot}: ${e.message} -- safe to delete by hand)`);
  }
}
// A clean exit, an exception and Ctrl-C all hit `exit`/signal handlers. A SIGKILL, a crashed
// child or a hard power-off cannot, and would otherwise accumulate one temp tree per run. Sweep
// leftovers from earlier runs: only our own prefix, only under os.tmpdir(), only when older than
// 6 hours so a concurrent long build is never deleted from under another process.
function sweepStaleWorkdirs() {
  const cutoff = Date.now() - 6 * 3600 * 1000;
  let entries = [];
  try { entries = fs.readdirSync(os.tmpdir(), { withFileTypes: true }); } catch { return; }
  let removed = 0;
  for (const e of entries) {
    if (!e.isDirectory() || !e.name.startsWith('perfscope-verify-')) continue;
    const p = path.join(os.tmpdir(), e.name);
    try {
      if (fs.statSync(p).mtimeMs > cutoff) continue;
      fs.rmSync(p, { recursive: true, force: true, maxRetries: 1, retryDelay: 100 });
      removed++;
    } catch { /* in use or permissions: leave it */ }
  }
  if (removed) info(`  swept ${removed} stale workdir(s) from a previous interrupted run under ${os.tmpdir()}`);
}
// Backstop for early exits and Ctrl-C. A SIGKILL leaves <tmpdir>/perfscope-verify-* behind (the
// sweep above reclaims those next run); it is outside the repo and destructible, which is
// exactly why the worktree route -- which writes .git/worktrees/ -- was rejected.
process.on('exit', cleanupTemp);
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP', 'SIGBREAK']) {
  try { process.on(sig, () => { cleanupTemp(); process.exit(130); }); } catch { /* unsupported signal */ }
}

/** Write a git-archive zip out to a directory (binary-safe; no external unzip). */
function extractZip(zipPath, destDir) {
  const entries = readZip(zipPath);
  let files = 0;
  for (const [name, e] of entries) {
    if (name.split('/').includes('..') || path.isAbsolute(name)) {
      throw new Error(`refusing suspicious zip entry: ${name}`);
    }
    const out = path.join(destDir, name);
    if (name.endsWith('/')) { fs.mkdirSync(out, { recursive: true }); continue; }
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, e.data());
    files++;
  }
  return files;
}

const TARGET_OVERRIDE = argOf('--target', '');
// In ref mode the default target is inside the temp tree, which only exists after extraction;
// --skip-fresh-build means there is no such tree, so the default is "nothing" unless given.
let TARGET = TARGET_OVERRIDE ? path.resolve(TARGET_OVERRIDE)
  : MODE === 'ref' ? null : path.resolve(path.join(REPO, 'target', JAR_NAME));
let buildRan = false;
let buildOk = false;

if (SKIP_BUILD) {
  note('fresh build', '--skip-fresh-build: no build was run');
} else if (MODE === 'head') {
  info('');
  info(`  building fresh jar from HEAD (this is what "consistent with source" means) ...`);
  const r = runMvn(REPO);
  buildRan = true;
  buildOk = r.ok;
  if (!r.ok) {
    fail('fresh build succeeds', `mvn clean package failed; no candidate worked (${r.err})`);
  }
} else {
  // Build the ref, not the worktree.
  const stamp = `${Date.now()}-${process.pid}`;
  const safeName = REF.spec.replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 40);
  tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), `perfscope-verify-${safeName}-${stamp}-`));
  const zipPath = path.join(tmpRoot, 'src.zip');
  const treeDir = path.join(tmpRoot, 'tree');
  info('');
  info(`  building fresh jar from ${BASELINE_LABEL} in a temp dir (the repo is not touched) ...`);
  try {
    fs.mkdirSync(treeDir, { recursive: true });
    gitRaw(['archive', '--format=zip', '-o', zipPath, REF.commit]);
    const n = extractZip(zipPath, treeDir);
    info(`  extracted ${n} files from git archive -> ${treeDir}`);
    const r = runMvn(treeDir);
    buildRan = true;
    buildOk = r.ok;
    if (!r.ok) {
      fail('fresh build succeeds', `mvn clean package of ${BASELINE_LABEL} failed in ${treeDir};`
        + ` no candidate worked (${r.err})`);
    }
    if (!TARGET) TARGET = path.resolve(path.join(treeDir, 'target', `${pom.artifactId}-${pom.version}.jar`));
  } catch (e) {
    buildRan = true;
    buildOk = false;
    fail('fresh build succeeds', `could not build ${BASELINE_LABEL}: ${e.message}`);
  }
}

const builtThisRun = buildRan && buildOk;
// Default (worktree) mode keeps the legacy contract: if there is no fresh jar there is nothing
// to compare, so this is "could not run" (exit 2), not a soft skip. --ref + --skip-fresh-build is
// the one case where a partial audit is still meaningful; it degrades to SKIP + exit 3 instead.
if (MODE === 'head' && TARGET && !fs.existsSync(TARGET)) {
  if (!SKIP_BUILD) fail('fresh jar exists', `expected ${TARGET}; run mvn clean package in ${REPO}`);
  console.error('RESULT: FAIL (no fresh jar to compare against)');
  process.exit(2);
}
const haveFresh = TARGET != null && fs.existsSync(TARGET);
if (!haveFresh) {
  info(`  fresh  : not available (${SKIP_BUILD ? '--skip-fresh-build' : 'build did not produce a jar'}`
    + `${TARGET ? `: ${TARGET}` : ''})`);
}

// ======================================================================================
// 3. Load the two jars
// ======================================================================================
let archived;
let fresh = null;
try {
  archived = { bytes: fs.statSync(ARCHIVE).size, sha256: sha256File(ARCHIVE), zip: readZip(ARCHIVE) };
  if (haveFresh) fresh = { bytes: fs.statSync(TARGET).size, sha256: sha256File(TARGET), zip: readZip(TARGET) };
} catch (e) {
  console.error(`ERROR: cannot read jar(s): ${e.message}`);
  process.exit(2);
}

info(`  archive: ${path.basename(ARCHIVE)}  ${archived.bytes} B  sha ${archived.sha256.slice(0, 12)}`);
if (fresh) info(`  fresh  : ${path.basename(TARGET)}  ${fresh.bytes} B  sha ${fresh.sha256.slice(0, 12)}  [built from ${BASELINE_LABEL}]`);
if (KEEP_WORKDIR && tmpRoot) info(`  workdir: kept at ${tmpRoot} (--keep-workdir)`);

// ======================================================================================
// 3b. JDK identity -- decides whether byte-level checks are even comparable
// ======================================================================================
// Class-file bytes depend on the JDK that compiled them (StackMapTable framing, record
// attribute `source` strings, `Build-Jdk-Spec` in the manifest). CI builds the release with
// Temurin 17 on Linux; a machine with only 21/25 cannot reproduce those bytes no matter how
// correct the source is. So: compare the major version, and when it differs, DEMOTE the two
// byte-level checks to SKIP instead of FAIL. A FAIL here is a false alarm that teaches people
// to ignore the gate -- which is worse than an honest "not verified".
const jdkUsed = effectiveJdk();
if (JDK_ARG && jdkUsed.invalid) {
  fail('JDK --jdk usable',
    `--jdk ${JDK_ARG} does not look like a JDK (no release file and no bin/java under ${jdkUsed.home})`);
} else if (JDK_ARG && jdkUsed.info) {
  pass('JDK --jdk usable',
    `${jdkUsed.info.version || 'unknown version'}${jdkUsed.info.vendor ? ` (${jdkUsed.info.vendor})` : ''} at ${jdkUsed.home}`);
}

const archJdkSpec = jarBuildJdkSpec(archived.zip);
const freshJdkSpec = fresh ? jarBuildJdkSpec(fresh.zip) : null;

/** true / false when both sides are known; null when we cannot tell. */
const jdkMajorsMatch = (archJdkSpec != null && freshJdkSpec != null)
  ? (archJdkSpec === freshJdkSpec) : null;

if (!JDK_ARG) {
  info(`  jdk    : ${jdkUsed.info ? `${jdkUsed.info.version}${jdkUsed.info.vendor ? ` (${jdkUsed.info.vendor})` : ''}` : 'unknown'}`
    + `  [${jdkUsed.source}]`);
}
info(`  jdk    : archive built by JDK ${archJdkSpec ?? '?'}`
  + (fresh ? `, fresh build by JDK ${freshJdkSpec ?? '?'}` : '')
  + (jdkMajorsMatch === false ? '  <- MISMATCH: byte-level checks cannot be compared' : ''));

if (fresh) {
  if (jdkMajorsMatch === true) {
    pass('JDK fresh build == archive build',
      `both JDK ${freshJdkSpec}; byte-level comparisons are meaningful`);
  } else if (jdkMajorsMatch === false) {
    note('JDK fresh build == archive build',
      `archive was built by JDK ${archJdkSpec}, this fresh build by JDK ${freshJdkSpec}. SHA256 and`
      + ` per-entry CONTENT will be reported as SKIPPED, not FAILED: class-file bytes differ by JDK,`
      + ` so this run cannot decide byte identity. Run with --jdk <the CI JDK (17)> to get a verdict,`
      + ` or set JAVA_HOME to it before running this script.`);
  } else {
    note('JDK fresh build == archive build',
      'could not read Build-Jdk-Spec from one side; assuming the comparison is meaningful');
  }
}

// ======================================================================================
// 4. SHA256
// ======================================================================================
//
// 这一项在 CONTENT 之前跑，但它<b>不能独立下结论</b>：整包 sha 不同有多种成因，
// 其中一种是"仅 CRLF/LF 差异"（Maven 用平台行分隔符写 pom.properties，Linux CI
// 构建的归档与 Windows 重建必然整包 sha 不同）。CONTENT 那一步才能分辨。
//
// 所以这里只记录"整包是否逐字节相同"，结论留给 CONTENT 之后的 [4b] 段。
let shaByteIdentical = null;
if (!fresh) {
  skip('SHA256 archive == fresh build',
    `no fresh build of ${BASELINE_LABEL} (${SKIP_BUILD ? '--skip-fresh-build was passed' : 'the build failed'});`
    + ` archive byte-identity is UNVERIFIED -- re-run without --skip-fresh-build`);
} else if (jdkMajorsMatch === false) {
  skip('SHA256 archive == fresh build',
    `not comparable: archive built by JDK ${archJdkSpec}, fresh build by JDK ${freshJdkSpec}.`
    + ` Class-file bytes are JDK-dependent, so a sha mismatch here proves nothing. Pass`
    + ` --jdk <the JDK CI uses (17)> to compare for real.`);
} else if (archived.sha256 === fresh.sha256) {
  shaByteIdentical = true;
  pass('SHA256 archive == fresh build', `${archived.sha256.slice(0, 16)}... (${archived.bytes} B) [${BASELINE_LABEL}]`);
} else {
  shaByteIdentical = false;
  // 先不下结论——见下面 [4b]。
}

// ======================================================================================
// 5. CONTENT: every entry byte-identical
// ======================================================================================
const isFunc = (n) => n.endsWith('.class') || n.endsWith('.yml') || n.endsWith('.yaml')
  || n.endsWith('.json') || n.endsWith('.properties') || n.endsWith('.txt');
// 这几个容器在外层声明：CONTENT 填，[4b] 段（把整包 sha 的结论落到内容上）要读。
const onlyInArchive = [];
const onlyInFresh = [];
const differs = [];
// 只有换行符不同、其余字节相同的条目。分开记，因为它们证明的东西不一样：
// 内容一致（归档确实来自这份源码），只是构建平台不同。
const lineEndingOnly = [];
if (!fresh) {
  skip('CONTENT archive entries == fresh entries',
    `no fresh build of ${BASELINE_LABEL} to compare entries against; per-entry identity is UNVERIFIED`);
} else if (jdkMajorsMatch === false) {
  // Class entries are JDK-dependent. Dropping them and comparing only the resources would
  // silently weaken the check (the whole point is the .class files), so this degrades to SKIP.
  // The .yml/.json resources DO still get compared below in the CONFIG check, so the run is
  // still informative.
  skip('CONTENT archive entries == fresh entries',
    `not comparable: archive built by JDK ${archJdkSpec}, fresh build by JDK ${freshJdkSpec}.`
    + ` ${[...archived.zip.keys()].filter(isFunc).length} functional entries cannot be compared`
    + ` across JDKs (class-file bytes differ). Pass --jdk <the JDK CI uses (17)> to compare for real.`);
} else {
  for (const [name, e] of archived.zip) {
    if (!isFunc(name)) continue;
    const f = fresh.zip.get(name);
    if (!f) { onlyInArchive.push(name); continue; }
    const a = sha256(e.data());
    const b = sha256(f.data());
    if (a === b) continue;
    if (sameIgnoringLineEndings(e.data(), f.data())) { lineEndingOnly.push(name); continue; }
    differs.push(name);
  }
  for (const name of fresh.zip.keys()) {
    if (isFunc(name) && !archived.zip.has(name)) onlyInFresh.push(name);
  }

  if (onlyInArchive.length === 0 && onlyInFresh.length === 0 && differs.length === 0
      && lineEndingOnly.length === 0) {
    pass('CONTENT archive entries == fresh entries',
      `${[...archived.zip.keys()].filter(isFunc).length} functional entries byte-identical to a build of ${BASELINE_LABEL}`);
  } else if (onlyInArchive.length === 0 && onlyInFresh.length === 0 && differs.length === 0) {
    // 归档来自 Linux CI 构建、本机是 Windows 时，Maven 生成的 pom.properties 用 LF，
    // 本地重建用 CRLF。字节不同、内容相同——判 FAIL 会让人去查一个不存在的归档错，
    // 判 PASS 又必须把"为什么不是逐字节"说清楚。所以单列一条，措辞里写明原因。
    pass('CONTENT archive entries == fresh entries (modulo line endings)',
      `${[...archived.zip.keys()].filter(isFunc).length - lineEndingOnly.length} entries byte-identical; `
      + `${lineEndingOnly.length} differ only in CRLF/LF (${lineEndingOnly.slice(0, 4).join(', ')}) `
      + `-- Maven writes pom.properties with the platform line separator, so an archive built on `
      + `Linux CI can never be byte-identical to a Windows rebuild. Content is identical.`);
  } else {
    const detail = [
      differs.length ? `${differs.length} entries differ (e.g. ${differs.slice(0, 4).join(', ')})` : '',
      onlyInFresh.length ? `${onlyInFresh.length} entries missing from archive (e.g. ${onlyInFresh.slice(0, 4).join(', ')})` : '',
      onlyInArchive.length ? `${onlyInArchive.length} entries exist only in archive (e.g. ${onlyInArchive.slice(0, 4).join(', ')})` : '',
    ].filter(Boolean).join('; ');
    fail('CONTENT archive entries == fresh entries', `${detail} -- the archive is NOT built from ${BASELINE_LABEL}`);
  }
}

/**
 * 两个字节串是否只差换行符（CRLF ↔ LF）。
 *
 * <p>只用于资源类条目。class 文件是二进制，换行归一化不会误判（二进制里恰好形成
 * 0x0D 0x0A 序列时，归一化两边都会去掉同一个 0x0D，结果仍然不等——除非整个文件除了
 * 换行外确实相同，那时判"仅换行差异"也是对的）。</p>
 */
function sameIgnoringLineEndings(a, b) {
  const norm = (buf) => {
    // 二进制（含 NUL）不参与归一化：class 文件的字节差异从来不是换行问题。
    if (buf.includes(0)) return null;
    return buf.toString('utf8').replace(/\r\n/g, '\n');
  };
  const na = norm(a);
  const nb = norm(b);
  return na !== null && nb !== null && na === nb;
}

// ======================================================================================
// 4b. 整包 sha 不一致时，把结论落到"内容是否相同"上
// ======================================================================================
//
// 整包 sha 是<strong>最强</strong>的判据，但它有一个已知的假红形态：构建平台不同。
// CI 在 Linux 上构建，Maven 用 LF 写 pom.properties；本机在 Windows 上重建，同一个
// 文件用 CRLF。于是整包 sha 必不相同，而<b>内容逐字节等价</b>。
//
// 这条假红必须被区分出来，否则归档校验在"CI 构建归档 + 本地校验"这个最常见的组合下
// 永远红，人会开始无视它——比不检查更糟。
if (shaByteIdentical === false) {
  if (differs.length === 0 && onlyInArchive.length === 0 && onlyInFresh.length === 0) {
    pass('SHA256 archive == fresh build (modulo line endings)',
      `whole-jar sha differs (${archived.sha256.slice(0, 12)} vs ${fresh.sha256.slice(0, 12)}, `
      + `${archived.bytes} B vs ${fresh.bytes} B) but every functional entry is either byte-identical `
      + `or differs only in CRLF/LF (${lineEndingOnly.length} such entries). `
      + `This is a build-OS difference (Maven writes pom.properties with the platform separator), `
      + `not an archive mismatch.`);
  } else {
    fail('SHA256 archive == fresh build',
      `archive ${archived.sha256.slice(0, 12)} != fresh ${fresh.sha256.slice(0, 12)} `
      + `(${archived.bytes} B vs ${fresh.bytes} B) [${BASELINE_LABEL}], and the difference is `
      + `NOT explained by line endings: ${differs.length} entries changed in content`
      + (differs.length ? ` (e.g. ${differs.slice(0, 4).join(', ')})` : '')
      + `, ${onlyInFresh.length} missing, ${onlyInArchive.length} extra.`);
  }
}

// ======================================================================================
// 6. CONFIG: config.yml keys, both directions
// ======================================================================================
function jarYamlText(zipMap, name) {
  const e = zipMap.get(name);
  return e ? e.data().toString('utf8') : null;
}
let srcConfigKeys = new Set();
try {
  srcConfigKeys = yamlKeys(srcText('src/main/resources/config.yml'));
} catch (e) {
  fail('CONFIG source config.yml readable', `cannot read config.yml at ${BASELINE_LABEL}: ${e.message}`);
}
const archConfig = jarYamlText(archived.zip, 'config.yml');
const freshConfig = fresh ? jarYamlText(fresh.zip, 'config.yml') : null;

if (archConfig == null) {
  fail('CONFIG jar contains config.yml', 'config.yml is missing from the jar root');
} else if (srcConfigKeys.size > 0) {
  const archKeys = yamlKeys(archConfig);
  const missingInJar = [...srcConfigKeys].filter(k => !archKeys.has(k));
  const extraInJar = [...archKeys].filter(k => !srcConfigKeys.has(k));
  if (missingInJar.length === 0 && extraInJar.length === 0) {
    pass('CONFIG keys archive == source', `${archKeys.size} keys [source: ${BASELINE_LABEL}]`);
  } else {
    fail('CONFIG keys archive == source',
      `${missingInJar.length} source keys absent from archive (e.g. ${missingInJar.slice(0, 6).join(', ')})`
      + (extraInJar.length ? `; ${extraInJar.length} archive-only keys (e.g. ${extraInJar.slice(0, 6).join(', ')})` : '')
      + ` [source: ${BASELINE_LABEL}]`
      + ' -- this is the 1.2.1 incident shape: documented settings that do not exist in the published jar');
  }
  // fresh build sanity: the fresh jar must match the source (if not, the build is broken, not the archive)
  if (freshConfig != null) {
    const fk = yamlKeys(freshConfig);
    const freshMissing = [...srcConfigKeys].filter(k => !fk.has(k));
    rec('CONFIG fresh build matches source', freshMissing.length === 0,
      freshMissing.length ? `fresh jar is missing ${freshMissing.length} source keys (e.g. ${freshMissing.slice(0, 6).join(', ')})`
        : `${fk.size} keys [${BASELINE_LABEL}]`);
  } else {
    skip('CONFIG fresh build matches source', 'no fresh build to inspect');
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
// 6b. RESOURCES: jar entry == source blob, byte for byte  (only when no fresh build exists)
// ======================================================================================
// With a fresh build present this adds nothing: CONTENT already proves config.yml is
// byte-identical to a build of the baseline, which is stronger than equal to the blob. It exists
// for the --skip-fresh-build case, where every other check is key-level and nothing else pins
// the bytes at all.
//
// Maven resource filtering rewrites ${...} placeholders (plugin.yml's version comes from the
// pom), so an entry whose source contains a placeholder is excluded by construction rather than
// compared wrongly. config.yml has none, so for it this is a true byte-identity check of the
// archive against the ref -- the exact comparison that showed the 1.3.0 archive is tag-clean.
function packagedResources(srcPrefix) {
  if (MODE === 'ref') {
    return (git(['ls-tree', '-r', '--name-only', REF.commit, '--', srcPrefix]) || '').split('\n').filter(Boolean);
  }
  const root = path.join(REPO, srcPrefix);
  const out = [];
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else out.push(path.relative(REPO, p).split(path.sep).join('/'));
    }
  };
  walk(root);
  return out;
}
if (fresh) {
  note('RESOURCES jar entries == source blobs',
    'not needed: CONTENT compared every resource entry against a build of the baseline, which'
    + ' implies byte-identity to the source blobs');
} else try {
  const resFiles = packagedResources('src/main/resources');
  const checkable = [];
  const filteredOut = [];
  for (const p of resFiles) {
    const entry = p.slice('src/main/resources/'.length);
    const blob = srcFile(p);
    if (blob.includes('${')) { filteredOut.push(entry); continue; }
    checkable.push({ entry, blob });
  }
  const wrong = [];
  for (const { entry, blob } of checkable) {
    const e = archived.zip.get(entry);
    if (!e) { wrong.push(`${entry} (missing from jar)`); continue; }
    if (Buffer.compare(e.data(), blob) !== 0) wrong.push(entry);
  }
  if (checkable.length === 0) {
    skip('RESOURCES jar entries == source blobs',
      `no resource free of Maven placeholders to compare (${filteredOut.length} carry ${'${...}'})`);
  } else if (wrong.length) {
    fail('RESOURCES jar entries == source blobs',
      `${wrong.length} of ${checkable.length} compared entries do not match the blob at ${BASELINE_LABEL}: `
      + `${wrong.slice(0, 5).join(', ')} -- byte-level proof the archive is NOT this source`);
  } else {
    pass('RESOURCES jar entries == source blobs',
      `${checkable.length} entr${checkable.length === 1 ? 'y' : 'ies'} byte-identical to ${BASELINE_LABEL}`
      + (filteredOut.length ? ` (${filteredOut.length} not comparable: Maven-filtered ${filteredOut.join(', ')})` : ''));
  }
} catch (e) {
  skip('RESOURCES jar entries == source blobs', `could not read source resources at ${BASELINE_LABEL}: ${e.message}`);
}

// ======================================================================================
// 7. MESSAGES: compiled-in message key count
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
const freshKeysInJar = fresh ? countMessageKeys(fresh.zip) : null;

// source-side count: reuse the same heuristic on the source tables
function sourceMessageKeyCount() {
  const keys = new Set();
  const take = (txt) => {
    for (const m of txt.matchAll(/"([a-z][a-z0-9]*(?:[.-][a-z0-9]+)+)"/g)) {
      if (KEY_RE.test(m[1])) keys.add(m[1]);
    }
  };
  if (MODE === 'ref') {
    const list = (git(['ls-tree', '-r', '--name-only', REF.commit, '--', 'src/main/java']) || '')
      .split('\n').filter(Boolean).filter(p => /Messages\.java$/.test(p));
    for (const p of list) take(srcText(p));
  } else {
    const SRC = path.join(REPO, 'src', 'main', 'java');
    const walk = (d) => {
      for (const e of fs.readdirSync(d, { withFileTypes: true })) {
        const p = path.join(d, e.name);
        if (e.isDirectory()) { walk(p); continue; }
        if (!/Messages\.java$/.test(e.name)) continue;
        take(fs.readFileSync(p, 'utf8'));
      }
    };
    walk(SRC);
  }
  return keys;
}
const srcKeys = sourceMessageKeyCount();

if (archKeysInJar.size === 0) {
  fail('MESSAGES keys compiled into archive', 'no message-key-shaped strings found in *Messages.class -- the message layer may be absent');
} else {
  // Archive must carry at least as many keys as the source tables define (allowing for
  // detection noise: +/- 2% or 10 keys, whichever is larger).
  const slack = Math.max(10, Math.round(srcKeys.size * 0.02));
  if (archKeysInJar.size + slack < srcKeys.size) {
    fail('MESSAGES keys compiled into archive',
      `archive has ${archKeysInJar.size} message keys, source at ${BASELINE_LABEL} defines ${srcKeys.size} `
      + `(slack ${slack}) -- this is the 1.2.1 incident shape: 14 keys shipped against 672 in source`);
  } else {
    pass('MESSAGES keys compiled into archive',
      `${archKeysInJar.size} keys in archive vs ${srcKeys.size} in source at ${BASELINE_LABEL}`
      + (freshKeysInJar ? ` (fresh build: ${freshKeysInJar.size})` : ''));
  }
  if (freshKeysInJar) {
    const ratio = freshKeysInJar.size > 0 ? archKeysInJar.size / freshKeysInJar.size : 1;
    rec('MESSAGES archive key count == fresh build', archKeysInJar.size === freshKeysInJar.size || ratio > 0.98,
      `archive ${archKeysInJar.size} vs fresh ${freshKeysInJar.size} [${BASELINE_LABEL}]`);
  } else {
    skip('MESSAGES archive key count == fresh build', 'no fresh build to compare key counts against');
  }
}

// ======================================================================================
// 8. plugin.yml version
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
// 9. GIT: baseline vs tag, plus the working-tree facts
// ======================================================================================
//
// 发布 tag 的名字不一定等于 `v${VERSION}`。
//
// 本项目的 tag 命名在 2026-11-18 变过一次：`1.4.4` 的 tag 是 `v1.4.4-SNAPSHOT`，
// `1.4.5-SNAPSHOT-fix4` 的 tag 是 `v1.4.5-SNAPSHOT-fix4`。也就是说**版本字符串与 tag 后缀
// 之间没有可推导的关系**，硬写 `v${VERSION}` 会让每一版都报「tag 不存在」——那是判据错了，
// 不是归档错了，但输出看起来与真的缺 tag 一模一样。
//
// 所以按优先级解析：
//   1. --tag <name>            显式指定（最可靠）
//   2. --ref <name> 恰好是个 tag 且指向我们要验的版本 -> 就用它
//   3. v<VERSION>              旧约定，兜底
const tag = resolveReleaseTag();
function resolveReleaseTag() {
  const explicit = argOf('--tag', null);
  if (explicit) return explicit;
  if (MODE === 'ref' && REF && REF.spec) {
    const asTag = git(['rev-parse', '--verify', '--quiet', `refs/tags/${REF.spec}^{commit}`]);
    if (asTag) return REF.spec;
  }
  return `v${VERSION}`;
}
const tagCommit = git(['rev-parse', '--verify', '--quiet', `refs/tags/${tag}^{commit}`]);

if (headShort == null) {
  rec('GIT repository readable', false, 'git not available or not a repository');
} else {
  pass('GIT repository readable',
    MODE === 'ref' ? `HEAD ${headShort}; baseline ${BASELINE_LABEL}` : `HEAD ${headShort}`);

  if (!tagCommit) {
    rec('GIT tag exists', false, MODE === 'ref'
      ? `${tag} is not created yet; ${REF.spec} was verified as a commit, but an archive in versions/`
        + ` must correspond to a release tag -- create ${tag} at ${REF.short} before publishing`
      : `${tag} is not created yet (expected before publishing)`);
    if (MODE === 'head') {
      const behind = git(['rev-list', '--count', 'HEAD']);
      rec('GIT source not ahead of release tag', false,
        `no ${tag}; HEAD is ${behind} commits past v${pom.version}. Cannot prove the archive matches a tag.`);
    }
  } else {
    pass('GIT tag exists', `${tag} -> ${tagCommit.slice(0, 7)}`);
    if (MODE === 'head') {
      const ahead = git(['rev-list', '--count', `${tag}..HEAD`]);
      const n = ahead == null ? null : Number(ahead);
      if (n === 0) {
        pass('GIT source not ahead of release tag', `${tag} is at HEAD (${headShort})`);
      } else {
        fail('GIT source not ahead of release tag',
          `HEAD is ${n} commits ahead of ${tag}. If the archive was built from HEAD it does not `
          + `correspond to the tag; if it was built from the tag, the source has moved on. `
          + `Re-run with --ref ${tag} --version ${VERSION} to verify the archive against the tag `
          + `instead of the worktree. Commits: ${(git(['log', '--oneline', `${tag}..HEAD`]) || '').split('\n').slice(0, 6).join(' | ')}`);
      }
    } else if (REF.commit === tagCommit) {
      pass('GIT ref matches release tag', REF.spec === tag ? `${tag} -> ${REF.short}`
        : `${REF.spec} (${REF.short}) is ${tag}`);
    } else {
      fail('GIT ref matches release tag',
        `--ref ${REF.spec} is ${REF.short} but ${tag} is ${tagCommit.slice(0, 7)}; the archive is being`
        + ` checked against a ref that is not the release tag ${tag}`);
    }
  }

  if (MODE === 'ref') {
    // Honest bookkeeping: the distance to HEAD is information here, never a failure.
    // A...B: left column = commits only in A (HEAD is that many behind), right = only in B.
    const lr = git(['rev-list', '--left-right', '--count', `${tagCommit || REF.commit}...HEAD`]);
    if (!lr) {
      note('GIT HEAD vs ref', `HEAD ${headShort}; could not compute distance to ${BASELINE_LABEL}`);
    } else {
      const [headBehind, headAhead] = lr.split(/\s+/).map(Number);
      const parts = [];
      if (headAhead) parts.push(`HEAD is ${headAhead} commit(s) ahead of ${BASELINE_LABEL}`);
      if (headBehind) parts.push(`HEAD is ${headBehind} commit(s) behind ${BASELINE_LABEL}`);
      note('GIT HEAD vs ref', parts.length
        ? `${parts.join('; ')} -- informational only: these commits are NOT covered by this`
          + ` verification and are not a failure`
        : `HEAD == ${BASELINE_LABEL} (${REF.short})`);
    }
    const dirty = git(['status', '--porcelain']);
    const dirtyLines = dirty == null ? [] : dirty.split('\n').filter(Boolean).filter(l => !/(^|\s)versions\//.test(l));
    note('GIT working tree state',
      `${dirtyLines.length} changed path(s)${dirtyLines.length ? ` (e.g. ${dirtyLines.slice(0, 4).join(', ')})` : ''}`
      + ` -- not used by this verification: it built ${BASELINE_LABEL}, not the worktree`);
  } else {
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
}

// provenance sidecar written by archive-release.ps1 (optional, strengthens [1])
const provPath = ARCHIVE.replace(/\.jar$/, '.provenance.json');
if (fs.existsSync(provPath)) {
  try {
    const p = JSON.parse(fs.readFileSync(provPath, 'utf8'));
    const shaOk = p.sha256 === archived.sha256;
    rec('PROVENANCE sidecar sha256 == archive', shaOk,
      shaOk ? `recorded at commit ${String(p.commit || '?').slice(0, 7)}` : `sidecar says ${String(p.sha256).slice(0, 12)}, archive is ${archived.sha256.slice(0, 12)}`);
    if (p.commit) {
      const want = MODE === 'ref' ? REF.commit : headFull;
      const same = want && p.commit === want;
      rec(MODE === 'ref' ? 'PROVENANCE commit == ref' : 'PROVENANCE commit == HEAD', !!same,
        same ? `both ${String(want).slice(0, 7)} [${BASELINE_LABEL}]`
          : `archive built at ${String(p.commit).slice(0, 7)}, ${BASELINE_LABEL} is ${String(want || '?').slice(0, 7)}`);
    }
  } catch (e) {
    rec('PROVENANCE sidecar readable', false, `cannot parse ${path.basename(provPath)}: ${e.message}`);
  }
} else {
  info(`  (no provenance sidecar at ${path.basename(provPath)} -- run archive-release.ps1 to record one:`
    + ` ${ARCHIVE_TOOL.path})`);
}

// ======================================================================================
// 10. report
// ======================================================================================
cleanupTemp();

const counts = { pass: 0, fail: 0, skip: 0, info: 0, total: 0 };
for (const r of results) {
  counts[r.status]++;
  if (r.status !== 'info') counts.total++;
}
// pass = everything ran and agreed; incomplete = nothing failed but something did not run.
const overall = counts.fail > 0 ? 'fail' : counts.skip > 0 ? 'incomplete' : 'pass';
const baselineObj = MODE === 'ref'
  ? { kind: 'ref', spec: REF.spec, commit: REF.commit, label: BASELINE_LABEL }
  : { kind: 'head', commit: headFull, label: BASELINE_LABEL, worktree: true };

if (AS_JSON) {
  console.log(JSON.stringify({
    repo: REPO, version: VERSION, archive: ARCHIVE, target: haveFresh ? TARGET : null,
    baseline: baselineObj, mode: MODE,
    status: overall, summary: counts,
    workdir: KEEP_WORKDIR && tmpRoot ? tmpRoot : null,
    archiveSha256: archived.sha256, freshSha256: fresh ? fresh.sha256 : null,
    built: builtThisRun,
    archiveKeys: archKeysInJar.size, sourceKeys: srcKeys.size,
    results,
    // `ok` kept for consumers written against the old shape. It means "verified equals true":
    // SKIP is ok:null, not true, so a skipped check can never be read as a pass.
    ok: overall === 'pass' ? true : overall === 'fail' ? false : null,
  }, null, 2));
} else {
  console.log('');
  console.log('='.repeat(74));
  for (const r of results) {
    const label = r.status === 'pass' ? 'PASS' : r.status === 'fail' ? 'FAIL'
      : r.status === 'skip' ? 'SKIP' : 'INFO';
    console.log(`[${label}] ${r.id} -- ${r.detail}`);
  }
  console.log('='.repeat(74));
  const bits = [];
  if (counts.fail) bits.push(`${counts.fail} failed`);
  if (counts.skip) bits.push(`${counts.skip} skipped`);
  if (counts.pass) bits.push(`${counts.pass} passed`);
  bits.push(`baseline ${BASELINE_LABEL}`);
  if (overall === 'pass') {
    console.log(`ARTIFACT CHECK: PASS (${counts.pass}/${counts.total} passed)  [baseline ${BASELINE_LABEL}]`);
  } else if (overall === 'fail') {
    console.log(`ARTIFACT CHECK: FAIL (${bits.join(', ')})`);
  } else {
    console.log(`ARTIFACT CHECK: INCOMPLETE (${bits.join(', ')})`);
    console.log('  -> nothing failed, but at least one comparison did NOT run. This is not a pass.');
    console.log('     The archive is not fully verified by this run; see the SKIP lines above.');
  }
  if (counts.fail) {
    console.log('');
    console.log('Fix: rebuild from a clean checkout of the tag, copy target/*.jar over versions/,');
    console.log(`     then run ${ARCHIVE_TOOL.path} -Version <x.y.z> to refresh the provenance file.`);
    console.log('     (That script lives OUTSIDE this repo -- see the path above.)');
    console.log(`     To check against the tag instead of the worktree: --ref v${VERSION} --version ${VERSION}`);
  }
  // INCOMPLETE caused specifically by a JDK mismatch is the common local case; say what to do
  // about it rather than leaving the user to guess from the SKIP lines.
  if (counts.fail === 0 && counts.skip > 0 && jdkMajorsMatch === false) {
    console.log('');
    console.log(`Note: the archive was built by JDK ${archJdkSpec} but this run built it with JDK ${freshJdkSpec}.`);
    console.log('      Class-file bytes differ by JDK, so SHA256/CONTENT could not be compared. To get a');
    console.log('      real verdict, point at the JDK CI uses for releases (Temurin 17):');
    console.log(`        node tools/verify-release-artifact.mjs --jdk "<path to a JDK 17>" ...`);
  }
}
// Set for BOTH output modes. Previously this lived inside the text branch, so --json always
// exited 0 -- a JSON consumer (or a CI step piping it) could not fail the gate at all.
process.exitCode = overall === 'fail' ? 1 : overall === 'incomplete' ? 3 : 0;
