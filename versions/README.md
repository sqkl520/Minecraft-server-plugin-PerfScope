# versions/ —— 本地构建存档

本目录为**本地**编译产物备份，JAR 不纳入 git（`.gitignore` 排除，仅本 `README.md` 例外入库）。
正式发布请走 GitHub Releases。

## 当前版本

| 版本 | 文件 | 说明 |
|---|---|---|
| **1.1.0** | `PerfScope-1.1.0.jar` | **推荐使用**。首个可用正式版（Wave 1–7：15 个 P0 修复 + 审查加固 + v1.2 核心重构）。
GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.1.0> |
| 1.0.0 | `PerfScope-1.0.0.jar` | ⚠️ **不可用**构建（见下），仅存档。 |

## PerfScope-1.0.0.jar

**警告：此 JAR 为已知 100% 不可用构建，仅供历史参考，请勿使用。**

该构建因 maven-shade-plugin 的 shade/relocate 处理问题，装入服务器后所有命令均会抛出
`NoSuchMethodError` 而无法执行（v1.1 开发期的 P0-3 回归点）。正确可用的构建请从
GitHub Releases 下载 1.1.0，或自行 `mvn clean package` 构建。

> 注：自 1.1.0 起 shade 重新引入但**仅用于 bStats 且落点隔离前缀** `cn.perfscope.shaded.bstats`；
> release-check 与 CI 以三重断言防回归（`cn/perfscope/lib/`=0、`net/kyori/`=0、relocated bStats=19）。

---

# versions/ —— Local Build Archive

This directory holds **local** build artifacts; JARs are not tracked by git (excluded via
`.gitignore`, with this `README.md` as the only exception). Use GitHub Releases for official
downloads.

- **1.1.0** (`PerfScope-1.1.0.jar`) — the first usable release; use this one.
- 1.0.0 (`PerfScope-1.0.0.jar`) — **known 100% broken** (shade/relocate → `NoSuchMethodError`
  on every command); archived for history only, do not use.
