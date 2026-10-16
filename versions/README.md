# versions/ —— 本地构建存档

本目录为**本地**编译产物备份，JAR 不纳入 git（`.gitignore` 排除，仅本 `README.md` 例外入库）。
正式发布请走 GitHub Releases。

## PerfScope-1.0.0.jar

**警告：此 JAR 为已知 100% 不可用构建，仅供历史参考，请勿使用。**

该构建因 maven-shade-plugin 的 shade/relocate 处理问题，装入服务器后所有命令均会抛出
`NoSuchMethodError` 而无法执行（v1.1 开发期的 P0-3 回归点）。正确可用的构建请从
GitHub Releases 下载，或自行 `mvn clean package` 构建。

---

# versions/ —— Local Build Archive

This directory holds **local** build artifacts; JARs are not tracked by git (excluded via
`.gitignore`, with this `README.md` as the only exception). Use GitHub Releases for official
downloads.

## PerfScope-1.0.0.jar

**WARNING: This JAR is a known 100% non-functional build, kept for historical reference only —
do not use it.** Due to a maven-shade-plugin shade/relocate issue, every command throws
`NoSuchMethodError` after installation (the P0-3 regression point during v1.1 development).
Download a working build from GitHub Releases instead, or build it yourself with
`mvn clean package`.
