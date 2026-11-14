# versions/ —— 本地构建存档

本目录为**本地**编译产物备份，JAR 不纳入 git（`.gitignore` 排除，仅本 `README.md` 例外入库）。
正式发布请走 GitHub Releases。

## 当前版本

| 版本 | 文件 | 说明 |
|---|---|---|
| **1.4.2** | `PerfScope-1.4.2.jar` | **推荐使用**（最新已发布版本）。**补丁版**，四个修复，全是"把健康说成毛病"或"把毛病说成健康"：

① **空载服务器的 CPU 被当"未采集"**。真实哨兵是 `-1`，`0` 是合法读数（空闲 CPU 就是 0）。
同一错误在三处：游戏内着色显示"未知"、`cpu:process` Prometheus 指标在空载服上**整条不出现**。

② **TPS 0 被当"没数据"过滤**。`tps1m` 同样没有 -1 哨兵，看到 0 就是服务器真的冻结了。
三处受害：日报 p95 偏高、日报超阈时长漏算卡死时段、`/perfscope trend` 报"最低 18.0"而实际卡死过
——最后一条是**直接伪造历史**，而 trend 正是服主回顾"昨晚卡没卡"的命令。

③ **`/perfscope report` 失败时不说原因**。四种失败（无快照/目录不可用/渲染异常/文件名耗尽）
处置完全不同，此前只显示一句"报告生成失败"。

无配置变更、无行为变更（除上述修复本身）。从 1.4.x 升级直接替换 jar。<br>
sha256 `48c69edc…`<br>
GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.4.2> |
| 1.4.1 | `PerfScope-1.4.1.jar` | **补丁版**，只修一件事：Prometheus 端点的请求处理原先只捕获 `Exception`，渲染路径抛 `LinkageError`（类链接失败、jlink 裁过的 JDK 缺 `jdk.httpserver` 模块等）时整个请求线程崩掉，抓取方拿到的是**连接重置**（客户端报 `HTTP/1.1 header parser received no bytes`）——这个症状与"端点没起来""端口没开""防火墙挡了"完全一样，运维会顺着网络方向排查。现在返回干净的 500，日志里有一条限频 warning 带异常类名。字节码对比：1.4.0 的 `handle` 里 `LinkageError` 捕获条目 0 个，1.4.1 是 5 个。<br>**从 1.4.0 升级无需任何改动**，直接替换 jar；无配置变更、无行为变更。从 1.3.x 升级请先读仓库 README 的「升级注意」（`alerts.enabled` 默认值翻转等）。<br>其余改动全是测试覆盖：`PrometheusEndpoint` 补 55 个用例、`HistoryFileStore` 写入路径补 21 个用例。<br>sha256 `dec97a6a…`<br>GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.4.1> |
| 1.4.0 | `PerfScope-1.4.0.jar` | 带 `LinkageError` 缺陷，建议换用 1.4.1。「可观测性」：Prometheus 指标端点（默认 `127.0.0.1:8940/metrics`，含逐告警 `perfscope_alert{key,module}` 指标——级别走值不走标签，序列恒定；健康也输出 0，`absent()` 才能区分"没告警"与"采集挂了"）；企业微信 / 飞书 / 钉钉群机器人（飞书/钉钉支持加签）；`/perfscope webhook status|test` 自检（三个国内平台业务失败也返回 HTTP 200，错误码在响应体里——HTTP 200 不等于成功）；日报新增 MSPT 尖峰 / 进程 CPU / 系统 CPU / GC 次数与时长；历史 CSV 7 列扩到 12 列（旧文件照常可读，缺列显示"未采集"而非 0）；`/perfscope trend` 报告数据缺口（把"这段时间没卡"与"这段时间根本没数"分开）。**行为变更**：`alerts.enabled` 默认值 `true` → `false`（告警改为选择加入），随包默认阈值同步重标定（`entities` 2000/3000 → 20000/30000、`chunks` 1500/2500 → 10000/15000）；你文件里已有的值不受影响。诊断规则修复 4 处口径：TPS 规则在 `tps15m == 0`（彻底冻结）与窗口全 0 时不再失声；内存泄漏规则在缺采窗口改为 SKIP 而非 CLEAN；热点区块严重线跟随配置的命中线。消息键：源码注册 778 个（`check-i18n.mjs` 实测，`--strict` 亦通过），编译进 jar 的 746 个（`verify-release-artifact.mjs` 按 class 常量池实测——两者口径不同，前者数 `put()` 调用，后者只认 `*Messages.java` 里的字面量键）。<br>sha256 `b34a77b5…`<br>GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.4.0> |
| 1.3.0 | `PerfScope-1.3.0.jar` | 「可诊断性」：热点区块扫描改为分区增量（11,047 区块空闲服 A/B 实测：采集 `max` 78→41 ms、超 1 tick 周期 2/300→0/300；p95 33→30 ms，在噪声内）；采集耗时自监测（`/perfscope status` 给出 p50/p95/max）；控制台日志与消息层完整中英双语（**725** 键，`check-i18n.mjs --strict` 实测）；LICENSE/NOTICE 进 jar；非 Paper 系平台启动即拒绝；`update_interval` 钳制 5–1200；bStats 匿名统计正式生效（35061）。**Windows 上进程 CPU 因实测单次阻塞 171ms 而改为显示「未知」**。<br>GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.3.0> |
| 1.2.1 | `PerfScope-1.2.1.jar` | 「说真话」：不新增功能，修正 v1.2.0 中说错/说假/静默失败之处（MSPT 极值捏造、Folia 假精确、诊断三态、告警生命周期、webhook 泄露、配置静默回退、历史异步写、性能自证纠错）。新增 `/status`、`/alerts`。<br>GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.2.1> |
| 1.2.0 | `PerfScope-1.2.0.jar` | 智能诊断 + 热点定位 + 跨重启历史 + 消息层（Wave 8）。<br>GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.2.0> |
| 1.1.0 | `PerfScope-1.1.0.jar` | v1.1 修复 + v1.2 核心重构（Wave 1–7）。<br>GitHub Releases：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/tag/v1.1.0> |
| 1.0.0 | `PerfScope-1.0.0.jar` | ⚠️ **不可用**构建（见下），仅存档。 |

## 各归档与 tag 的对应关系

每个归档 JAR 都与同名 git tag 严格对应：包内 `config.yml` 与 `plugin.yml` 均与该 tag 的
`src/main/resources/` 逐字节相同。校验方式是

```bash
node tools/verify-release-artifact.mjs --ref v<版本> --version <版本>
```

它会在临时目录里按该 tag 重新构建，再与归档逐条目比对（SHA256 / 每个类文件 / config 键 /
消息键计数 / plugin.yml 版本 / 该 tag 是否存在并与版本号一致）。

各版本的关键差异：

- **1.4.0 起**才有 Prometheus 端点（`perfscope.*` 指标、默认 `127.0.0.1:8940/metrics`）、
  企业微信 / 飞书 / 钉钉通道与 `alerts.webhook.secret`、`/perfscope webhook status|test`、
  12 列历史 CSV。
- **1.3.0** 的 `config.yml` 里没有 `prometheus:` 段，jar 内没有
  `cn/perfscope/export/PrometheusEndpoint.class`，`alerts.webhook.type` 只接受
  `discord` / `onebot`。需要用那些功能请装 1.4.0。
- **1.3.0 及更早**的 `alerts.enabled` 随包默认是 `true`；**1.4.0 起改为 `false`**
  （告警是选择加入）。升级说明见仓库根目录 README 的「升级注意」。

## `target/` 里的产物不要当发布版分发

`target/` 是当前工作树的构建产物，文件名里的版本号来自 `pom.xml`。它只在你**正好处于该
版本的 tag 提交**时才与归档等价；开发中任何时候的 `target/*.jar` 都比归档多出未发布改动。
判断依据永远是 `git describe --tags` 与本目录的文件名是否一致，不要靠 jar 文件名。

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

**Latest released version: 1.4.1** (`PerfScope-1.4.1.jar`, patch release).

1.4.1 fixes one thing: the Prometheus endpoint's request handler only caught `Exception`, so a
`LinkageError` in the render path (class-link failure, a jlink-trimmed JDK without
`jdk.httpserver`, …) killed the request thread and the scraper got a **dropped connection**
(`HTTP/1.1 header parser received no bytes`) — a symptom indistinguishable from "endpoint not
running", "port closed" or "firewall". It now returns a clean 500 with a rate-limited warning in
the log. Bytecode check: `handle` has 0 `LinkageError` catch entries in 1.4.0, 5 in 1.4.1.
**Upgrading from 1.4.0 needs no changes** — just swap the jar. Everything else in this release is
test coverage.

## How each archive maps to a tag

Every archived JAR corresponds exactly to its same-named git tag: the `config.yml` and
`plugin.yml` inside are byte-identical to that tag's `src/main/resources/`. To check:

```bash
node tools/verify-release-artifact.mjs --ref v<version> --version <version>
```

It rebuilds from the tag in a temp directory and compares entry by entry (SHA256 / every class
file / config keys / message-key counts / plugin.yml version / that the tag exists and matches
the version number).

What differs between versions:

- **From 1.4.0** the Prometheus endpoint exists (`perfscope.*` metrics, default
  `127.0.0.1:8940/metrics`), along with WeCom / Feishu / DingTalk channels and
  `alerts.webhook.secret`, `/perfscope webhook status|test`, and the 12-column history CSV.
- **1.3.0** has no `prometheus:` block in `config.yml`, no
  `cn/perfscope/export/PrometheusEndpoint.class` in the jar, and `alerts.webhook.type` accepts
  only `discord` / `onebot`. Install 1.4.0 if you need those.
- **1.3.0 and earlier** ship `alerts.enabled` defaulting to `true`; **from 1.4.0 it is `false`**
  (alerts are opt-in). See "Upgrading" in the repository README.

## Do not redistribute `target/` as a release

`target/` holds the current worktree's build; the version in the file name comes from `pom.xml`.
It equals the archive only when you are **exactly at that version's tag commit** — at any other
time `target/*.jar` carries unreleased changes. Judge by `git describe --tags` against this
directory's file names, never by the jar file name.

## Older builds

- 1.2.1 (`PerfScope-1.2.1.jar`) — "Tell the truth": no new features, fixes the wrong/fake/silent-failure
  behaviour in v1.2.0. Adds `/status`, `/alerts`.
- 1.2.0 (`PerfScope-1.2.0.jar`) — smart diagnosis + hotspot localization + cross-restart history + messages.
- 1.1.0 (`PerfScope-1.1.0.jar`) — v1.1 fixes + v1.2 core refactor (Waves 1–7).
- 1.0.0 (`PerfScope-1.0.0.jar`) — **known 100% broken** (shade/relocate → `NoSuchMethodError`
  on every command); archived for history only, do not use.
