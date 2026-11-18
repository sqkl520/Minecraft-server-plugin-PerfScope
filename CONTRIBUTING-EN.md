[简体中文](CONTRIBUTING.md) | English

# Contributing guide

## 1. What this project is, stated plainly

This is a **personal project**: a performance monitoring plugin for Minecraft servers (Paper family,
Java 17, AGPL-3.0), built by a high-school student with AI assistance (vibe coding) and ground down
bit by bit on real hardware.

Three honest facts up front, so your time is not wasted:

- **No response-time promise.** School, exams and being stuck on a bug can all mean weeks without looking at Issues.
- **No promise that "I'll build this feature".** Direction is set by the author; a genuinely good idea in an
  Issue may still not get built.
- **But PRs are read carefully.** A PR with tests and a clear motivation has a much higher chance than a
  "one-line change".

If you can accept those three points, read on.

## 2. Reporting a problem

**Search the [Issues](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/issues) first
(closed ones included)** — a question like "TPS always shows 20" has likely been asked before.

Please include all five of these in a bug report; missing one usually costs an extra round trip:

1. **Server version and build number** (e.g. `Paper 1.20.4 build 343`, `Purpur 1.21.1 build 2200`)
2. **PerfScope version** (first line of `/perfscope status`, or the jar filename; formatted like
   `1.4.5-SNAPSHOT-fix1`)
3. **Steps to reproduce** — which command, in what order, after how long
4. **Relevant logs** (the `PerfScope` lines in `logs/latest.log`; paste the full stack trace if there is one)
5. **Your config** (`plugins/PerfScope/config.yml` — **strip the token out of the webhook URL before posting**)

If a *number* looks wrong, also say "I expected X, it shows Y". Most bugs in this plugin are
judgement errors of one class — a healthy reading reported as a fault, or the reverse — so the
value you expected is often more useful than the log.

## 3. Submitting a Pull Request

1. Fork this repository, create a branch off `main` (e.g. `fix/tps-threshold`), and keep the change focused — one PR, one thing.
2. Make sure the build passes locally (see below).
3. Open a PR against `main` and fill in the description and testing notes per the PR template.
4. CI (`.github/workflows/build.yml`) builds the PR and runs artifact gates automatically; PRs with failing CI will not be merged.

## 4. Build requirements

You need **JDK 17** and **Maven** (no Maven Wrapper in this repo — use your installed `mvn`). Run from the repository root (where `pom.xml` lives):

```bash
mvn clean package              # full build, output in target/
mvn clean package -DskipTests  # quick build, skipping tests
mvn test                       # run tests only
```

- The target platform is **Paper 1.20.4+** (Java 17); plain Spigot / CraftBukkit is not supported. Changes must be consistent with this fact.
- After any code change, `mvn clean package` must compile successfully.
- When adding or changing commands, features, or permissions, update `README.md` and `README-EN.md` accordingly, and append a one-line entry under the current version section of `CHANGELOG.md`.

### Self-checks before you push (important)

The repo ships several gate scripts. CI runs them and you can run them locally — **please run at
least these after a change**:

```bash
node tools/check-i18n.mjs --repo . --strict     # message keys and placeholder parity
node tools/check-docs.mjs                       # product copy must not contain the author's verbal tic
node tools/check-sentinels.mjs                  # sentinel fields must not be compared against 0
node tools/check-bstats-buckets.mjs             # new bStats charts must be registered
node tools/check-reload-rebuild.mjs             # reload() must still rebuild tasks when config load fails
```

**Tests matter more than the code change itself.** Every fix in this project is required to come with
an assertion that goes red *before* the fix — otherwise it is not fixed, just edited. If you can show
that your test actually catches the bug, your PR is very likely to land.

## 5. CLA (Contributor License Agreement)

**Before your first PR, please read [CLA.md](CLA.md).** You retain copyright of your Contribution, while granting the Maintainer a perpetual, worldwide, non-exclusive, royalty-free, irrevocable license that includes the **right to sublicense** (including under proprietary/commercial licenses).

- **First-time contributors**: state either of the following in your PR description (or via the PR template checkbox) to sign the CLA:

  ```
  我已阅读并同意 CLA（CLA.md）
  I have read and agree to the CLA (CLA.md)
  ```

- The Maintainer will record your **GitHub username and the date of agreement** when merging your first PR; later Contributions are covered automatically with no repeated statement.
- If you contribute on behalf of someone else (e.g. your employer), make sure you are authorized to do so.

## 6. Behavior expectations

- Be kind and stay on topic; Chinese or English are both fine.
- Before suggesting features, check the README's "Boundaries & What This Plugin Does Not Do" — some things are deliberately out of scope (e.g. use spark for root-cause profiling).
- The Maintainer does not promise response times and does not guarantee that any PR will be merged.
