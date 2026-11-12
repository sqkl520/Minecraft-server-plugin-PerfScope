# versions/ —— 本地构建存档

本目录为**本地**编译产物备份，JAR 不纳入 git（`.gitignore` 排除，仅本 `README.md` 例外入库）。
正式发布请走 GitHub Releases。

## 当前版本

| 版本 | 文件 | 说明 |
|---|---|---|
| **1.3.0** | `PerfScope-1.3.0.jar` | **推荐使用**（最新已发布版本）。「可诊断性」：热点区块扫描改为分区增量（11,047 区块空闲服 A/B 实测：采集 `max` 78→41 ms、超 1 tick 周期 2/300→0/300；p95 33→30 ms，在噪声内）；采集耗时自监测（`/perfscope status` 给出 p50/p95/max）；控制台日志与消息层完整中英双语（**725** 键，`check-i18n.mjs --strict` 实测）；LICENSE/NOTICE 进 jar；非 Paper 系平台启动即拒绝；`update_interval` 钳制 5–1200；bStats 匿名统计正式生效（35061）。**Windows 上进程 CPU 因实测单次阻塞 171ms 而改为显示「未知」**。<br>GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.3.0> |
| 1.2.1 | `PerfScope-1.2.1.jar` | 「说真话」：不新增功能，修正 v1.2.0 中说错/说假/静默失败之处（MSPT 极值捏造、Folia 假精确、诊断三态、告警生命周期、webhook 泄露、配置静默回退、历史异步写、性能自证纠错）。新增 `/status`、`/alerts`。<br>GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.2.1> |
| 1.2.0 | `PerfScope-1.2.0.jar` | 智能诊断 + 热点定位 + 跨重启历史 + 消息层（Wave 8）。<br>GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.2.0> |
| 1.1.0 | `PerfScope-1.1.0.jar` | v1.1 修复 + v1.2 核心重构（Wave 1–7）。<br>GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.1.0> |
| 1.0.0 | `PerfScope-1.0.0.jar` | ⚠️ **不可用**构建（见下），仅存档。 |

## 1.3.0 归档早于 v1.4 的 Prometheus 工作

`PerfScope-1.3.0.jar` 与 git tag `v1.3.0` 严格对应
（sha256 `8ae2fc5a…`；包内 `config.yml` 与 tag 的 `src/main/resources/config.yml` 逐字节相同，
`plugin.yml` 版本号 1.3.0）。它**早于** v1.4 的 Prometheus 工作，因此：

- 其 `config.yml` **没有 `prometheus:` 段**（`grep -c prometheus` = 0），也没有
  `/perfscope webhook status|test` 子命令；
- jar 内**没有** `cn/perfscope/export/PrometheusEndpoint.class`；
- `alerts.webhook.type` 只接受 `discord` / `onebot`，没有 `wecom` / `feishu` / `dingtalk`，也没有 `secret` 键。

要用这些 v1.4 功能，请从 `main` 自行构建（`mvn clean package`），而不是下载本目录的 1.3.0 归档。
当前 `main` 的 HEAD 构建与本归档**同名 1.3.0 但内容不同**（sha256 `cd1c8018…`，含 Prometheus），
原因见下节。

## 为什么 `target/` 里也有一个 1.3.0，却和这里的不一样

`pom.xml` 目前仍是 `<version>1.3.0</version>`，而 `main` 上已经累积了 v1.4 的功能提交。
于是 `mvn clean package` 产出的 `target/PerfScope-1.3.0.jar` 与归档的同名 JAR 内容不同：
前者含 v1.4 新功能，后者对应 tag `v1.3.0` 的发布内容。**归档目录只增不删、不覆盖**，
所以这里保留 tag 对应版本；v1.4 正式发布时会以新版本号另存一份。在那之前，
不要把 `target/` 的产物当成「1.3.0 发布版」分发。

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

**Latest released version: 1.3.0** (`PerfScope-1.3.0.jar`, "Diagnosability" release).

## The 1.3.0 archive predates the v1.4 Prometheus work

`PerfScope-1.3.0.jar` corresponds exactly to git tag `v1.3.0` (sha256 `8ae2fc5a…`; the
`config.yml` inside it is byte-identical to the tag's `src/main/resources/config.yml`, and
`plugin.yml` reads version 1.3.0). It predates v1.4's Prometheus work, so:

- its `config.yml` has **no `prometheus:` block** (`grep -c prometheus` = 0), and there is no
  `/perfscope webhook status|test` subcommand;
- the jar contains **no** `cn/perfscope/export/PrometheusEndpoint.class`;
- `alerts.webhook.type` accepts only `discord` / `onebot` — no `wecom` / `feishu` / `dingtalk`,
  and no `secret` key.

To get those v1.4 features, build from `main` (`mvn clean package`) instead of downloading the
1.3.0 archive below. The current `main` HEAD build carries the **same 1.3.0 file name but
different contents** (sha256 `cd1c8018…`, includes Prometheus) — see the next section.

## Why there are two different "1.3.0" jars

`pom.xml` still says `<version>1.3.0</version>` while `main` has accumulated the v1.4 feature
commits. So `mvn clean package` produces a `target/PerfScope-1.3.0.jar` whose contents differ
from the archived jar of the same name: the former includes v1.4 features, the latter is the
tag `v1.3.0` release content. **This archive directory is append-only and never overwritten**,
so the tag-matching build is what is kept here; v1.4 will be archived under a new version
number when it is released. Until then, do not redistribute the `target/` build as "the 1.3.0
release".

## Older builds

- 1.2.1 (`PerfScope-1.2.1.jar`) — "Tell the truth": no new features, fixes the wrong/fake/silent-failure
  behaviour in v1.2.0. Adds `/status`, `/alerts`.
- 1.2.0 (`PerfScope-1.2.0.jar`) — smart diagnosis + hotspot localization + cross-restart history + messages.
- 1.1.0 (`PerfScope-1.1.0.jar`) — v1.1 fixes + v1.2 core refactor (Waves 1–7).
- 1.0.0 (`PerfScope-1.0.0.jar`) — **known 100% broken** (shade/relocate → `NoSuchMethodError`
  on every command); archived for history only, do not use.
