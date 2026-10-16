# 贡献指南 / Contributing Guide

感谢你想为 PerfScope 出一份力。中文版在前，English follows；两版含义相同，如有歧义以中文版为准。

## 中文版

### 提交 Pull Request

1. Fork 本仓库，从 `main` 新建分支（如 `fix/tps-threshold`），改动聚焦——一个 PR 只做一件事。
2. 提交前确保本地构建通过（见下）。
3. 向 `main` 提交 PR，按 PR 模板填写描述与测试说明。
4. CI（`.github/workflows/build.yml`）会在 PR 上自动构建并检查产物门禁；CI 不通过不会被合并。

### 构建要求

需要 **JDK 17** 与 **Maven**（本项目无 Maven Wrapper，请使用已安装的 `mvn`）。在仓库根目录（`pom.xml` 所在处）执行：

```bash
mvn clean package              # 完整构建，产物在 target/
mvn clean package -DskipTests  # 跳过测试的快速构建
mvn test                       # 仅运行测试
```

- 目标平台为 **Paper 1.20.4+**（Java 17），不支持纯 Spigot / CraftBukkit；改动须与此事实一致。
- 新增/修改命令、功能或权限时，请同步更新 `README.md` 与 `README-EN.md`，并在 `CHANGELOG.md` 的 `[Unreleased]` 段落下追加一行变更记录。
- 修改代码后必须保证 `mvn clean package` 编译通过。

### CLA（贡献者许可协议）

**首次提交 PR 之前，请先阅读 [CLA.md](CLA.md)。** 你保留自己贡献的版权；同时授予维护者一项永久、全球、非独占、免版税、不可撤销的许可，其中包括**再许可权**（可将你的贡献以任何许可证再许可，含闭源/商业许可证）。

- **首次贡献者**：请在 PR 描述（或在 PR 模板的 checkbox 处）写明下面任一语句，即视为签署 CLA：

  ```
  我已阅读并同意 CLA（CLA.md）
  I have read and agree to the CLA (CLA.md)
  ```

- 维护者会在合并你的首个 PR 时记录你的 **GitHub 用户名与同意日期**；此后的后续贡献自动沿用，无需重复声明。
- 若你不代表自己（例如代表雇主）贡献，请确保已获得相应授权。

### 行为期望

- 友善、就事论事；中文或英文交流均可。
- 报告 Bug 请附：服务端版本（含 Paper 构建号）、PerfScope 版本、复现步骤、相关日志或截图。
- 提功能建议前，可先看看 README 的「边界与不做什么」——有些事本插件有意不做（如根因定位请用 spark）。
- 维护者不承诺响应时限，也不保证合并任何 PR。

---

## English Version

### Submitting a Pull Request

1. Fork this repository, create a branch off `main` (e.g. `fix/tps-threshold`), and keep the change focused — one PR, one thing.
2. Make sure the build passes locally (see below).
3. Open a PR against `main` and fill in the description and testing notes per the PR template.
4. CI (`.github/workflows/build.yml`) builds the PR and runs artifact gates automatically; PRs with failing CI will not be merged.

### Build Requirements

You need **JDK 17** and **Maven** (no Maven Wrapper in this repo — use your installed `mvn`). Run from the repository root (where `pom.xml` lives):

```bash
mvn clean package              # full build, output in target/
mvn clean package -DskipTests  # quick build, skipping tests
mvn test                       # run tests only
```

- The target platform is **Paper 1.20.4+** (Java 17); plain Spigot / CraftBukkit is not supported. Changes must be consistent with this fact.
- When adding or changing commands, features, or permissions, update `README.md` and `README-EN.md` accordingly, and append a one-line entry under the `[Unreleased]` section of `CHANGELOG.md`.
- After any code change, `mvn clean package` must compile successfully.

### CLA (Contributor License Agreement)

**Before your first PR, please read [CLA.md](CLA.md).** You retain copyright of your Contribution, while granting the Maintainer a perpetual, worldwide, non-exclusive, royalty-free, irrevocable license that includes the **right to sublicense** (including under proprietary/commercial licenses).

- **First-time contributors**: state either of the following in your PR description (or via the PR template checkbox) to sign the CLA:

  ```
  我已阅读并同意 CLA（CLA.md）
  I have read and agree to the CLA (CLA.md)
  ```

- The Maintainer will record your **GitHub username and the date of agreement** when merging your first PR; later Contributions are covered automatically with no repeated statement.
- If you contribute on behalf of someone else (e.g. your employer), make sure you are authorized to do so.

### Behavior Expectations

- Be kind and stay on topic; Chinese or English are both fine.
- For bug reports, include: server version (with Paper build number), PerfScope version, reproduction steps, and relevant logs or screenshots.
- Before suggesting features, check the README's "Boundaries & What This Plugin Does Not Do" — some things are deliberately out of scope (e.g. use spark for root-cause profiling).
- The Maintainer does not promise response times and does not guarantee that any PR will be merged.
