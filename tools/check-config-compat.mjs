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

const REPO = path.resolve(argOf('--repo', path.join(HERE, '..')));
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
//
// 基线必须是一个**更早的发布版本**，不能是当前版本的构建产物。
//
// 曾经这里取 versions/*.jar 里版本号最大的那个，注释写着 "not the current version"，
// 但代码里根本没有这个排除——于是发布流程本身会让门禁自我失效：按项目规则把当前版本
// 的 jar 归档进 versions/ 之后，"最高版本"就是当前工作树自己的产物，与新源码逐字相同，
// **任何** KEY PARITY 断言都恒真。
//
// 实测（2026-11-24）：同一棵代码树、把 alerts.cooldown-seconds 改名，
//   未归档时 → FAIL，点名 alerts.cooldown-seconds
//   归档后   → PASS 9/9，"100 keys, all present in source"
//
// 用户配置里的键被改名丢弃，门禁却报"都在"，而触发它的是项目自己的发布流程。
// 所以现在显式排除与 pom <version> 相同的 jar；一个都不剩时报 exit 2（"无法验证"），
// 而不是静默降低门槛。
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
  // previous release = the highest versions/*.jar that is NOT the current pom version.
  const versionsDir = path.join(REPO, 'versions');
  // 当前版本：从 pom 读。拿不到就退化成"不排除"——但那条路会让门禁静默失效，
  // 所以宁可报无法验证。
  let currentVersion = null;
  try {
    const pom = fs.readFileSync(path.join(REPO, 'pom.xml'), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
    const body = pom.replace(/<parent>[\s\S]*?<\/parent>/g, '')
      .replace(/<dependencies>[\s\S]*?<\/dependencies>/g, '')
      .replace(/<build>[\s\S]*?<\/build>/g, '');
    currentVersion = (body.match(/<version>\s*([^<]+?)\s*<\/version>/) || [])[1] || null;
  } catch {
    currentVersion = null;
  }
  /** 从文件名里取出完整版本串（含 -SNAPSHOT-fixN 后缀），例如 PerfScope-1.4.5-SNAPSHOT-fix2.jar */
  const versionOf = (f) => {
    const m = f.match(/^PerfScope-(.+)\.jar$/);
    return m ? m[1] : null;
  };
  let jars = [];
  if (fs.existsSync(versionsDir)) {
    jars = fs.readdirSync(versionsDir)
      .filter(f => f.endsWith('.jar'))
      // 排除当前版本自己的构建产物：拿它当基线，KEY PARITY 就成了恒真断言。
      .filter(f => currentVersion == null || versionOf(f) !== currentVersion)
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
//
// cm 为空 = 读不到 ConfigManager.java。这个形状必须判 FAIL，不能跳过整组断言：
// 曾经这里是 `if (cm) { … }`，文件不存在时**断言直接不执行**，results 少 3 项，
// 末尾仍打印 PASS。实测把整个 ConfigManager.java 删掉后，门禁报 "CONFIG CHECK: PASS (6/6)"
// ——9 项变 6 项，无一 FAIL。这正是本项目反复踩的"看起来绿，其实没跑"。
const cm = fs.existsSync(CONFIG_MANAGER) ? fs.readFileSync(CONFIG_MANAGER, 'utf8') : '';
if (!cm) {
  rec('CONFIG MANAGER source is readable', false,
    `${path.relative(REPO, CONFIG_MANAGER)} could not be read. Every loader-side assertion below `
    + `depends on it, so this is a hard failure -- skipping them would report PASS for a repo `
    + `that no longer has a config loader at all.`);
}
{
  const readWithDefault = (key) => {
    // readString/readInt/... always take a default; a bare cfg.getConfigurationSection().get()
    // would not. Flag only the dangerous shape.
    const esc = key.replace(/\./g, '\\.');
    return new RegExp(`read\\w*\\s*\\(\\s*"${esc}"\\s*,`).test(cm);
  };
  const newWithoutDefault = added.filter(k => !readWithDefault(k));
  // 这条曾经是 `rec(..., true, ...)` —— ok 是字面量 true，然后下面再把 ok 强制写回 true，
  // 于是"新增 key 却没有 reader"这条规则**永远 PASS**。实测：往 config.yml 加一个任意的
  // 新键、不写任何 reader，门禁报 PASS 9/9。
  //
  // 不能简单判 FAIL：这个扫描只找 read*("key", default) 这一种形状，而代码里有若干键
  // 是通过 helper（如 getInt(key, default) 包一层）读的，直接判红会误伤。
  //
  // 折中：按"这个键在 ConfigManager 里到底出现过没有"分层。
  //   - 连键名都搜不到  -> 真的没人读，判 FAIL（这就是"加了个死键"）
  //   - 出现过但形状不匹配 -> 降级为 INFO（可能是 helper 读取，扫描器看不出来）
  const totallyAbsent = newWithoutDefault.filter(k => !cm.includes(`"${k}"`));
  const shapeOnly = newWithoutDefault.filter(k => cm.includes(`"${k}"`));
  rec('NEW KEYS have a reader default', totallyAbsent.length === 0,
    totallyAbsent.length
      ? `${totallyAbsent.length} new key(s) appear nowhere in ConfigManager -- nothing reads them: `
        + totallyAbsent.slice(0, 8).join(', ')
        + (shapeOnly.length ? `; ${shapeOnly.length} more are read through a shape this scan does not`
          + ` recognise (informational, not a failure): ${shapeOnly.slice(0, 8).join(', ')}` : '')
      : shapeOnly.length
        ? `every new key is read; ${shapeOnly.length} via a helper shape this scan does not`
          + ` recognise (informational, not a failure): ${shapeOnly.slice(0, 8).join(', ')}`
        : 'every new key is read with an explicit default');
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
// 同样不能 `if (cm)`：源文件读不到时这两条断言也必须被评估（并因此判 FAIL），
// 否则删掉 ConfigManager.java 反而让门禁更绿。
//
// 而且这两条**都曾经是纯文本搜索**，于是恒真：
//   - `/CONFIG_VERSION_KEY|config-version/` 在注释里命中就算通过
//   - `/unknown-key|unknownKey|not a known|未知/` 里的「未知」在源码中出现 17 次，
//     绝大多数是注释
//
// 实测：把 `handleConfigVersion` 的迁移代码整段删掉、只留注释里的 `config-version`，
// 门禁报 PASS；把 `unknown-key` / `unknownKey` / `not a known` 全替换成随机串、
// 留 17 处中文「未知」，也报 PASS。
//
// 修法：**先剥注释**，再要求「不只是一个词，而是有真实调用形状」。
{
  const code = stripJavaComments(cm);

  // 版本迁移：光有常量名不够，必须看到「真的写到磁盘」。
  //
  // 这条判据也改过两版：
  //   第一版 `/CONFIG_VERSION_KEY|config-version/` —— 注释里命中就算过。
  //   第二版 `writeBackConfigVersion\s*\(` 或 `yaml.set(CONFIG_VERSION_KEY` —— 有两个洞：
  //     · 只删调用、留下定义时仍 PASS（`private boolean writeBackConfigVersion() {` 里就有那个名字）
  //     · `yaml.set(CONFIG_VERSION_KEY, 1)` 只改内存对象、从不落盘，语义上是错的，
  //       但第二个或分支会因此永远变绿 —— 相当于给这条判据开后门
  //
  // 现在认三件事同时成立：
  //   1. 有 `handleConfigVersion`（迁移逻辑所在的入口）
  //   2. 调用了 `writeBackConfigVersion(`（先抹掉方法定义，避免定义当调用）
  //   3. `writeBackConfigVersion` 方法体里真的有 `Files.write` 或 `Files.move`（真的落盘）
  //
  // 已知局限：`if (false) writeBackConfigVersion();` 这类死分支它看不出来。
  // 源码级判据的通病，写在这里而不是等下次踩——真判据要跑一遍加载逻辑，超出本门禁"零依赖只读源码"的定位。
  const codeNoDefs = code.replace(
    /\b(?:private|protected|public)\b[^;{}()]{0,80}?\bwriteBackConfigVersion\s*\(/g,
    'METHOD_DEF(');
  const hasVersionKey = /\bCONFIG_VERSION_KEY\b/.test(code);
  // 入口必须**被调用**，不只是被定义。方法定义里也有 `handleConfigVersion(`，
  // 先抹掉定义再找调用——攻击验证过：删掉 `load()` 里那句调用、留下方法定义时，
  // 只按「存在」判断会让门禁照绿。
  const codeNoEntries = code.replace(
    /\b(?:private|protected|public)\b[^;{}()]{0,80}?\bhandleConfigVersion\s*\([^)]*\)\s*\{/g,
    'ENTRY_DEF{');
  const hasMigrationEntry = /\bhandleConfigVersion\s*\(/.test(codeNoEntries);
  const callsWriteBack = /writeBackConfigVersion\s*\(/.test(codeNoDefs);
  // writeBackConfigVersion 的方法体：从它的签名到下一个同级方法（用"缩进 4 的 private/public"近似）
  const wbBody = (() => {
    const i = code.search(/\bwriteBackConfigVersion\s*\(\s*\)\s*\{/);
    if (i < 0) return '';
    return code.slice(i, i + 4000);
  })();
  const actuallyPersists = /Files\.(?:write|move|copy)\s*\(/.test(wbBody);
  const hasVersionMigration = hasVersionKey && hasMigrationEntry && callsWriteBack && actuallyPersists;
  rec('LOADER supports older config versions', hasVersionMigration,
    (hasVersionMigration
      ? 'config-version migration present (entry + call + real disk write, comments excluded)'
      : !hasVersionKey ? 'CONFIG_VERSION_KEY is not referenced in ConfigManager code at all'
        : !hasMigrationEntry ? 'no handleConfigVersion entry point (it exists but is never called)'
          : !callsWriteBack ? 'CONFIG_VERSION_KEY exists but nothing writes it back -- an old file will not be upgraded in place'
            : 'writeBackConfigVersion is called but never touches the disk (no Files.write/move) -- '
              + 'editing the in-memory config does not upgrade the file on disk')
    + ' | source-level: a dead branch like `if (false) writeBackConfigVersion();` still passes,'
    + ' and renaming the method is a false red. The real check would load a legacy YAML and'
    + ' observe the write; that needs a server, which this gate deliberately does not require.');

  // 未知键告警：同样先剥注释，再要求**这条特定的告警**，不是"某个名字里带 unknown 的字符串"。
  //
  // 这条判据改了两版，前两版都不成立，记录在这里防止再退回：
  //
  //   第一版 `/[unknown-key|unknownKey|not a known|未知]/` —— 注释里命中就算过。
  //
  //   第二版 `/(warning|severe)\([^;]{0,400}?unknown/i` —— 命中 config.check.alerts-unknown-after
  //   这种**无关**的消息键。
  //
  //   第三版「同一行有 warning( 且 Messages.get("...unknown...")」—— 只是把范围收窄了一点。
  //   真实源码里命中 3 行，其中只有 1 行是 unknown-key 告警：
  //     ConfigManager.java:841  Messages.get("config.unknown.key", key)          <- 真的
  //     ConfigManager.java:968  Messages.get("config.worlds.unknown", ...)       <- 世界名非法
  //     ConfigManager.java:1157 Messages.get("config.check.alerts-unknown-after") <- 阈值越界
  //   后两行永远不会被删除，于是**删掉真正的告警后门禁仍然 PASS，还打印 "3 call site(s)"**
  //   —— 比原来更危险：裸文本搜索一眼能看出是空的，这版伪装成了结构化判据。
  //
  // 现在认两件事同时成立：
  //   1. 存在 `detectUnknownKeys` 这个方法（检测逻辑本身在）
  //   2. 存在**精确键名** `config.unknown.key` 的告警调用（玩家真的会看到那条消息）
  //
  // 已知局限（写在这里而不是等下次踩）：这仍是源码级判据，`if (false)` 包住调用时它照样绿。
  // 真正的判据应当是「喂一份含错键的 YAML，看加载器有没有产出 unknownKeys 并打 WARNING」——
  // 那需要跑 YamlConfiguration，超出了本门禁"零依赖、只读源码"的定位。局限已知，不是遗漏。
  const hasDetector = /\bdetectUnknownKeys\s*\(/.test(code);
  const hasUnknownKeyMsg = /"config\.unknown\.key"/.test(code);
  const unknownWarn = hasDetector && hasUnknownKeyMsg;
  rec('LOADER warns on unknown keys (does not throw)', unknownWarn,
    (unknownWarn
      ? 'unknown-key detection method + the exact config.unknown.key message are both present'
      : !hasDetector
        ? 'no detectUnknownKeys method -- a mistyped key would be silently ignored'
        : 'detectUnknownKeys exists but never logs config.unknown.key -- the operator is not told')
    + ' | source-level: a dead branch around the warning still passes. The real check would feed a'
    + ' YAML with a bogus key and observe the WARNING; that needs a server, which this gate deliberately'
    + ' does not require.');
}

/**
 * 剥掉 Java 的 `//` 与 `/* *\/` 注释（保持长度不变，行号因此不漂）。
 *
 * <p>为什么不用 `tools/lib/java-strings.mjs` 里的 {@code stripComments}：那个函数
 * 同时处理字符串与字符字面量，输出长度会变。本门禁只需要「注释里的词不算数」，
 * 用一份保持长度的最小实现就够，也不会因为字符串处理差异产生意外。</p>
 */
function stripJavaComments(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    const d = src[i + 1];
    if (c === '/' && d === '/') {
      while (i < n && src[i] !== '\n') { out += ' '; i++; }
      continue;
    }
    if (c === '/' && d === '*') {
      out += '  ';
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) {
        out += src[i] === '\n' ? '\n' : ' ';
        i++;
      }
      if (i < n) { out += '  '; i += 2; }
      continue;
    }
    out += c;
    i++;
  }
  return out;
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
