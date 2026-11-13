# tools/verify-release-artifact.mjs -- what it verifies, and how to reproduce it

Companion to the gate itself. Read this before changing the verifier or trusting its verdict.

## The problem it had

The gate compared `versions/<jar>` against **whatever is checked out right now**. So after every
normal release (HEAD moves ahead of the tag) it reported four FAILs for a perfectly correct
archive:

```
[FAIL] CONTENT archive entries == fresh entries -- 35 entries differ; 9 entries missing from archive
[FAIL] CONFIG keys archive == source -- 11 source keys absent from archive (e.g. alerts.webhook.secret, prometheus.port)
[FAIL] MESSAGES keys compiled into archive -- archive has 694 message keys, source defines 728
[FAIL] GIT source not ahead of release tag -- HEAD is 9 commits ahead of v1.3.0
```

None of them was an incident. The archive is a correct build of the tag: the `config.yml` inside
`versions/PerfScope-1.3.0.jar` is byte-identical to `git show v1.3.0:src/main/resources/config.yml`
(16375 bytes, SHA256 `6f7599e7a00ced6e...`).

A gate that cries wolf after every release gets ignored, and then it is not a gate.

## The fix: a baseline, stated out loud

| mode | baseline | source-side inputs | fresh build |
|---|---|---|---|
| default (no flag) | HEAD / the worktree | working tree files | `mvn clean package` in the repo |
| `--ref <tag-or-sha>` | that ref | **the ref's git blobs** (`git show`) | `git archive` the ref into `os.tmpdir()`, build there |

In `--ref` mode HEAD and the working tree are never consulted, so "HEAD is ahead of the tag" is
reported as `[INFO]`, not `[FAIL]`.

The default mode keeps its old verdicts exactly (verified: identical PASS/FAIL set per check id),
because just before a release at the tag commit it is the stronger check. What changed is that it
now says which baseline it used and how far HEAD is from the tag.

## Reproducing

Run from the repo root. Windows PowerShell 5.1.

```powershell
# 1. archive vs its release tag -- must be green after every release (exit 0, 11/11)
node tools/verify-release-artifact.mjs --ref v1.3.0 --version 1.3.0

# 2. same, machine-readable (status: pass|fail|incomplete; ok: true|false|null)
node tools/verify-release-artifact.mjs --ref v1.3.0 --version 1.3.0 --json

# 3. legacy worktree baseline -- unchanged verdicts, now labelled "baseline : HEAD <sha>"
node tools/verify-release-artifact.mjs --version 1.3.0

# 4. blob-only audit of a tag, no Maven at all -> SHA256/CONTENT become SKIP, exit 3
node tools/verify-release-artifact.mjs --ref v1.3.0 --version 1.3.0 --skip-fresh-build

# 5. the exact call tools/local-gate.ps1 -Artifact makes
node tools/verify-release-artifact.mjs --repo . --version 1.3.0 --skip-fresh-build

# 6. negative controls: does the gate still discriminate? (7 cases, ~5 min; --fast skips Maven)
node tools/verify-release-artifact.controls.mjs
node tools/verify-release-artifact.controls.mjs --fast
```

Exit codes: `0` every comparison ran and agreed; `1` at least one FAIL; `2` could not run;
`3` nothing failed but something was SKIPPED -- **the archive is not fully verified**. `3` is
deliberately distinct from `0`: a run that checked less must not exit like a pass.

## What `--ref` can and cannot prove

Really checked against the ref (not simulated):

- `SHA256` / `CONTENT` -- full builds of the ref in a temp dir. This repo builds byte-reproducibly
  (`project.build.outputTimestamp` is pinned), so `--ref v1.3.0` reproduces the archive's sha256
  `8ae2fc5aa188e5f4...` exactly. If a future ref does not build reproducibly, these turn FAIL with
  a detail line pointing at CONTENT to tell "stale archive" from "not reproducible".
- `CONFIG keys` -- both directions, ref `config.yml` blob vs jar.
- `MESSAGES` -- ref `*Messages.java` tables vs the constant pool compiled into the jar.
- `RESOURCES` (only when no build is run) -- jar entry vs ref blob, byte for byte. Entries whose
  source contains a Maven `${...}` placeholder are excluded, so `config.yml` is compared but
  `plugin.yml` is not (its `version:` is filtered from the pom).
- `GIT tag exists` / `GIT ref matches release tag` -- the ref resolves to the same commit as `v<version>`.

Never claimed by `--ref`:

- nothing about HEAD or the working tree -- deliberately out of scope, reported as INFO;
- that the jar attached to the GitHub Release equals `versions/<jar>` -- download and compare sha256;
- anything about tests, docs, or config semantics. This gate answers one question only.

## Known sharp edge: non-reproducible old tags

`--ref v1.2.0` exits **1** even though its archive is fine: v1.2.0 predates
`project.build.outputTimestamp`, so a rebuild carries different zip timestamps and `SHA256` differs
by construction. `CONTENT` passes (54 entries byte-identical) and the FAIL detail says so. That is
the gate reporting a real property of the 1.2.0 build, not a bug -- but read `CONTENT`, not the
headline, for 1.2.0 and older.

`CONFIG promised keys` also fails for 1.2.0: `general.language` genuinely did not exist yet. That
is the 1.2.1 incident shape, correctly reported.

## Why `git worktree`, `git stash`, `git checkout` were all rejected

`git worktree add` writes `.git/worktrees/` inside the repo. If the process dies between `add`
and `remove`, the registration and the checkout directory stay behind, and stale registrations
later break `git status` / `git worktree list` for the user and for any other agent working in the
same checkout. The script's own header promises it never writes to the repo. `git archive` to
`os.tmpdir()` keeps that promise literally and is read-only by construction -- it does not touch
the index or the worktree at all.

Cleanup: temp dirs are removed on clean exit, on exception, and on SIGINT/SIGTERM/SIGHUP/SIGBREAK.
A SIGKILL or power loss can still leave `<tmpdir>/perfscope-verify-*` behind; the next `--ref` run
sweeps those older than 6 hours. Nothing ever lands inside the repo, so the worst case is a
deletable directory under `%TEMP%`.

Verified in this change: running `--ref` leaves `git status` and `git rev-parse HEAD` byte-identical,
the repo's `target/` untouched (Maven is invoked with `cwd` = the temp tree), and no
`perfscope-verify-*` directory left when the run ends normally.
