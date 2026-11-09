# Security Policy

English | [简体中文](#安全策略简体中文)

PerfScope is a read-only monitoring plugin for Paper-family Minecraft servers. It reads JVM and
Bukkit metrics, writes files under `plugins/PerfScope/`, and — only if you enable it — sends HTTP
requests to a webhook URL you configure. It never modifies game state, never writes to a world, and
has no backend service of its own.

Because it is a plugin that network-reachable players interact with indirectly, this document is
deliberately explicit about **what it collects, what it sends, and what it stores**.

---

## Supported Versions

Security fixes are issued for the **latest minor release line only**.

| Version | Supported | Notes |
|---------|-----------|-------|
| 1.3.x | ✅ Yes | Security fixes land here |
| 1.2.x | ⚠️ Critical fixes only, until 30 days after 1.3.0 ships | |
| 1.1.x and older | ❌ No | Upgrade |

There is no backporting to versions older than the previous minor release. If you are on an
unsupported version, the fix is to upgrade — this is a single-maintainer project and maintaining
parallel branches would mean maintaining none of them well.

---

## Reporting a Vulnerability

**Do not open a public issue for a security problem.** A public issue is visible to everyone,
including anyone who would exploit it before a fix exists.

**Preferred — GitHub Security Advisories:**

> <https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/security/advisories/new>

This gives us a private thread, a place to develop the fix, and a way to publish a CVE-style
advisory that credits you.

**Alternative — email:** `sqkl520soap@gmail.com` with `[PerfScope Security]` in the subject line.

You do not need to encrypt, and there is no PGP key. Do **not** paste production credentials,
webhook URLs, or player personal data into the report; redact them.

### What to include

- PerfScope version (`/perfscope status` prints it, or read the jar filename)
- Server software and build, e.g. `Paper 1.21.4 build 232`, or `Folia`, `Purpur`, `Leaf`…
- Java version and OS
- What the issue is, and what an attacker gains from it — impact matters more than the mechanism
- Reproduction steps, or a proof of concept. A minimal repro is worth more than a long write-up
- Relevant log excerpts, **redacted** (server IPs, player names, webhook URLs and tokens)

### What to expect

| Stage | Target |
|-------|--------|
| We confirm we received it | within **7 days** |
| Initial assessment — accepted / need more info / out of scope | within **14 days** |
| Fix, or a documented mitigation you can apply yourself | within **90 days**; sooner for high severity |
| Public advisory and credit | after the fixed release is available |

These are **targets from one person**, not a contractual SLA. There is no bug bounty. This is a
free, volunteer-maintained project; if a report needs a specialist I do not have, I will say so
rather than let it sit. You will be credited in the advisory and the CHANGELOG unless you ask
otherwise.

### Scope — what counts as a security issue

**In scope:**

- Remote code execution, authentication bypass, or privilege escalation through the plugin
- Any path by which a **non-OP player** can read data they should not, or trigger alerts, exports,
  config reloads, or file writes
- **Leakage of secrets** in logs, reports, error messages, chat output, or telemetry. Webhook URLs
  are credentials: a Discord webhook URL embeds a token, and a OneBot URL may embed an access token
- SSRF or arbitrary-host request forgery through the webhook configuration
- Path traversal or arbitrary file write through report export, history persistence, or config
  write-back
- Denial of service reachable from an unprivileged command, or from normal gameplay
- Supply-chain issues in the build: the shaded/relocated bStats copy, the CI workflows, the
  release artifacts

**Out of scope** — these are expected behaviour, not vulnerabilities:

- **Anything requiring OP or console access.** An operator can already run arbitrary commands and
  read the server files. This is not a privilege boundary the plugin can defend.
- **Anything requiring another malicious plugin** or a modified server jar. Plugins share a JVM and
  a security manager does not exist in modern Minecraft; one hostile plugin can do anything.
- **The plugin disabling itself on non-Paper platforms.** This is the platform guard working as
  designed, not a bug and not a denial of service. See "Known non-issues" below.
- **Load caused by running PerfScope on an already-overloaded server.** The plugin reports load; it
  does not create a server's problems. Measured overhead is documented in the README.
- **Vulnerabilities in Minecraft, Paper, Purpur, Folia, or Bukkit.** Report those upstream.
- **Anything in a version not listed as supported above.**

### Known non-issues

Behaviour that gets reported as a bug or a security concern but is intentional:

1. **"The plugin disabled itself on my Spigot server."** Intentional. PerfScope requires Paper APIs.
   On a non-Paper platform it prints a bilingual explanation on the first line of `onEnable` and
   disables itself, instead of half-initialising and crashing later at an arbitrary line. Use Paper,
   Purpur, Folia, Leaf/Leaves, or Pufferfish.
2. **"A non-OP player gets `You do not have permission`."** Every command node defaults to `op`.
3. **"The plugin writes files to `plugins/PerfScope/`."** Reports, history CSV, alert state. Listing
   them is not an information leak on its own; if your filesystem permissions let an unrelated local
   user read your server directory, that is a host-level issue.
4. **"`/perfscope status` shows a redacted error instead of the full stack trace."** Deliberate. The
   command is available to `perfscope.use`, which may be granted to non-OP staff, and exception
   messages routinely contain absolute paths and server internals. The full text stays in the
   console log, which only console-level access can read.
5. **"The webhook rejects my `http://` URL to a LAN address."** Deliberate SSRF protection: `https://`
   is allowed for any host; plain `http://` is allowed only for loopback (`127.0.0.1`, `::1`,
   `localhost`, `*.localhost`) so a local OneBot can work. IP literals in private, link-local,
   unique-local and unspecified ranges are rejected.

---

## Data Collection — what PerfScope does and does not send

**There is no PerfScope-operated backend, no account system, and no cloud service.** Everything is
computed on your server. Nothing is uploaded unless you enable it or leave the default telemetry
on.

### Outbound network connections

There are exactly **two** possible outbound connections:

| # | Connection | Default | Destination | Who controls it |
|---|-----------|---------|-------------|-----------------|
| 1 | bStats anonymous usage statistics | **Enabled** | `bstats.org` | `general.bstats-enabled: false`, or `plugins/bStats/config.yml` |
| 2 | Webhook push (alerts / recovery / daily summary) | **Disabled** | **The URL you configure** (Discord or OneBot) | `alerts.webhook.enabled` |

There is **no update checker**, and no network code in this plugin's own source outside the webhook.
You can verify this yourself:

```bash
grep -rn "HttpClient\|HttpRequest\|openConnection\|new URL(\|new Socket" src/main/java/
```

Every hit is in `alert/WebhookNotifier.java`. The one other outbound call lives inside the bundled,
relocated bStats library (`cn.perfscope.shaded.bstats`), which uses `HttpsURLConnection` against a
fixed `REPORT_URL` — that is why it is listed above as a separate connection rather than counted as
plugin code.

### Layer 1 — what the bStats library itself sends

PerfScope bundles [bStats](https://bstats.org/) (MIT), relocated to `cn.perfscope.shaded.bstats` so
it cannot collide with another plugin's copy. The bStats library sends a **baseline payload** that
is identical for every plugin using bStats; **PerfScope cannot bucket, suppress, or add to it**. The
only control is turning bStats off entirely.

| Field | Example | Notes |
|-------|---------|-------|
| `pluginVersion` | `1.3.0` | PerfScope's version |
| `playerAmount` | `7` | **Exact online player count at send time — not bucketed** |
| `onlineMode` | `true` | Whether the server runs in online-mode |
| `bukkitVersion` | `1.21.4-R0.1-SNAPSHOT` | Server API version string |
| `javaVersion` | `17.0.10` | JVM version |
| `osName` | `Linux` | OS family |
| `osArch` | `amd64` | CPU architecture |
| `osVersion` | `6.8.0-45-generic` | OS kernel or build string |
| `coreCount` | `8` | Available processors |
| `serverUUID` | `6f1c…` | **Random UUID generated on first run**, stored locally in `plugins/bStats/config.yml`. Not your server address, not your IP |
| `serviceId` | `35061` | The bStats project ID — constant, identifies PerfScope |
| `metricsVersion` | `3.2.1` | Version of the bStats library doing the sending |

> The field list above is bStats' own payload, not something this plugin chooses — it was read out
> of `bstats-bukkit` 3.2.1, not from memory. If the exact player count or a specific kernel string is
> unacceptable to you, turn bStats off: the plugin behaves identically with telemetry disabled.

### Layer 2 — the custom charts PerfScope registers

These are added by this plugin and are **bucketed or enumerated**:

| Chart | Example | Notes |
|-------|---------|-------|
| `server_software` | `Paper` | Paper / Purpur / Folia / Spigot |
| `module_enabled` | `entities=1`, `hotspots=0` | Which collection modules you turned on |
| `language` | `en`, `zh`, `other` | Any other value is normalized to `other` |
| `webhook_channel` | `discord`, `onebot`, `none` | **Channel type only — never the URL** |
| `entity_count_bucket` | `1000` | Magnitude bucket, not the exact count |
| `loaded_chunks_bucket` | `1000` | Magnitude bucket |
| `peak_players_bucket` | `10` | Magnitude bucket |
| `heap_max_mb_bucket` | `4096` | Magnitude bucket |
| `world_count` | `3` | Bucketed to 1 / 3 / 5 / 10 / `10+` |
| `loaded_plugins` | `42` | Plain count of installed plugins |

### Neither layer sends

Player names, player UUIDs, IP addresses, world names, plugin **names**, whitelist or ban contents,
server MOTD, chat, webhook URLs or tokens, configuration file contents, alert text, report contents,
or file paths.

### Disabling telemetry

Either of these works, and neither affects any plugin feature:

```yaml
# plugins/PerfScope/config.yml
general:
  bstats-enabled: false
```

```yaml
# plugins/bStats/config.yml — disables bStats for every plugin on the server
enabled: false
```

### What PerfScope stores on your disk

All under `plugins/PerfScope/`. **None of it is uploaded.**

| Path | Contents |
|------|----------|
| `config.yml` | Your configuration |
| `config.yml.bak-<timestamp>` | Backup written during config migration / write-back |
| `messages.yml` | Message overrides for the active language |
| `reports/perfscope-*.{html,txt,json}` | Exported reports, 7-day retention by default |
| `history/history-YYYY-MM-DD.csv` | One row per sample: `epochMs, tps1m, msptAvg, heapUsagePercent, playerCount, totalEntities, totalLoadedChunks` — aggregate counters only |
| `alerts.state` | Alert lifecycle state, so alerts survive a restart. Columns: `key\|level\|firstSeen\|lastSeen\|value\|scope\|threshold\|exitThreshold\|module\|state`. **`scope` holds a world name** for world-dimension metrics (e.g. per-world entity thresholds), otherwise empty |
| `alerts.jsonl` | Alert history log (open/upgrade/downgrade/resolve/refire/restore/suspend), rotated to `alerts.jsonl.1` past 2 MB. Carries the same `scope` field, so **world names appear here too** |
| `daily-summary.state` | Last-sent marker for the daily summary |

**Player names are never written to any of these files, and never appear in reports** — reports
contain `playerCount` and `maxPlayers`, not a player list. The `/perfscope players` command does
list online player names, worlds and ping, but **only to the sender**, behind `perfscope.use`
(default: OP).

> **Reports and in-game output do contain world names and entity/chunk counts** — that is the point
> of the tool. Review an exported report before attaching it to a public issue.

### Webhook-specific hardening

- Treated as a credential: the URL contains a token. Never commit it to a repository or paste it in
  an issue; rotate it if it leaks.
- **What actually leaves the server**: the webhook receives the same message text that goes to the
  console and to in-game staff — alerts, recovery notices, and the daily summary. That text can
  include **world names** when a per-world entity alert fires, and metric values. Keep that in mind
  if your webhook target is a third-party service or a shared channel.
- **SSRF protection**: `https://` is allowed for any host; `http://` is allowed only for loopback
  (`127.0.0.1`, `::1`, `localhost`, `*.localhost`). IP literals in link-local, private, ULA and
  unspecified ranges are rejected. Validation happens before the notifier is marked ready.
- **Log redaction**: failure paths log the exception **type** only, never the message — JDK URL
  exceptions echo the full URL, which would leak the token into `latest.log`.
- **Mention injection**: Discord payloads are sent with `allowed_mentions.parse = []`, so an
  `@everyone` inside a world or player name cannot ping a channel. Message bodies are truncated
  (1900 chars for Discord, 1000 for OneBot).

---

## Hardening recommendations

- Keep every `perfscope.*` permission at `op` unless you have a specific reason. Grant
  `perfscope.use` to staff if you like — `perfscope.status` output is already redacted — but keep
  `perfscope.reload` and `perfscope.export` to people who should be able to rewrite config and pull
  server internals onto disk.
- If you enable webhooks, treat the URL as a password and rotate it if it ever appears in a log,
  screenshot, or issue.
- Review an exported report before sharing it: it lists world names and per-world entity counts.
- Run the server as a user that cannot read other users' files. PerfScope writes into
  `plugins/PerfScope/` with normal plugin permissions; it cannot protect files from a host-level
  misconfiguration.
- Keep bStats at whatever setting you are comfortable with, but do it deliberately. The default is
  on, and the two layers above are what it sends.

---

# 安全策略（简体中文）

PerfScope 是面向 Paper 系 Minecraft 服务端的**只读**监测插件。它读取 JVM 与 Bukkit 指标，把文件写入
`plugins/PerfScope/`，并且**仅在你主动开启时**向你自己填写的 webhook URL 发送 HTTP 请求。它不修改游戏
状态、不写世界、也没有自建后端服务。

本插件会间接与可联网的玩家交互，因此本文档刻意写得具体：**它采集什么、发出什么、在你磁盘上存什么**。

---

## 支持范围

安全修复只发给**最新的 minor 分支**。

| 版本 | 是否支持 | 说明 |
|------|---------|------|
| 1.3.x | ✅ 支持 | 安全修复落在这里 |
| 1.2.x | ⚠️ 仅接受严重问题的修复，直到 1.3.0 发布满 30 天 | |
| 1.1.x 及更早 | ❌ 不支持 | 请升级 |

不向更早的 minor 版本回移补丁。若你在不受支持的版本上，修复方式就是升级——这是单人维护项目，
同时维护多条分支等于哪条都维护不好。

---

## 报告漏洞

**不要为安全问题开公开 issue。** 公开 issue 所有人可见，包括会在修复前利用它的人。

**首选——GitHub Security Advisory（私密报告）：**

> <https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/security/advisories/new>

这条通道给我们一个私密线程、一个共同开发修复的地方，以及发布带署名的安全公告的方式。

**备选——邮件：** `sqkl520soap@gmail.com`，主题写 `[PerfScope Security]`。

无需加密，本项目没有 PGP 密钥。请**不要**在报告中粘贴生产凭据、webhook URL 或玩家个人信息；请先打码。

### 请提供

- PerfScope 版本（`/perfscope status` 首行会打印，或看 jar 文件名）
- 服务端软件与构建号，例如 `Paper 1.21.4 build 232`，或 `Folia` / `Purpur` / `Leaf`
- Java 版本与操作系统
- 问题是什么，以及攻击者能因此得到什么——**影响比机制更重要**
- 复现步骤，或 PoC。一份最小复现比一篇长文更有用
- 相关日志片段，**已脱敏**（服务器 IP、玩家名、webhook URL 与 token）

### 响应时间

| 阶段 | 目标 |
|------|------|
| 确认收到 | **7 天内** |
| 初步判定——接受 / 需要更多信息 / 超出范围 | **14 天内** |
| 修复，或给出你可自行实施的缓解方案 | **90 天内**；高危问题更快 |
| 发布安全公告并署名 | 修复版本可用之后 |

这些是**一个人给出的目标**，不是合同 SLA。本项目免费、由志愿者维护，没有漏洞赏金。若某份报告需要的
专业知识我不具备，我会直接说明，而不是让它一直挂着。除非你要求匿名，否则会在安全公告与 CHANGELOG 中
署名致谢。

### 范围——什么算安全漏洞

**在范围内：**

- 通过本插件实现的远程代码执行、鉴权绕过、权限提升
- 任何让**非 OP 玩家**读到不该读的数据，或触发告警、导出、重载配置、写文件的路径
- **密钥泄露**到日志、报告、错误信息、聊天输出或遥测中。webhook URL 就是凭据：Discord webhook URL
  内嵌 token，OneBot URL 可能内嵌 access token
- 通过 webhook 配置实现的 SSRF 或任意主机请求伪造
- 通过报告导出、历史持久化或配置写回实现的路径穿越或任意文件写入
- 非特权命令或正常游戏行为即可触发的拒绝服务
- 构建链问题：被 shade/relocate 的 bStats 副本、CI 工作流、发布产物

**不在范围内**——这些是预期行为，不是漏洞：

- **任何需要 OP 或控制台权限的问题。** 管理员本来就能执行任意命令、读取服务器文件，这不是本插件
  能守住的权限边界。
- **任何需要另一个恶意插件**或改动过的服务端 jar 才能成立的问题。插件共享同一个 JVM，现代
  Minecraft 也没有 SecurityManager；一个恶意插件能做任何事。
- **插件在非 Paper 平台自动禁用自己。** 这是平台守卫按设计工作，不是 bug，也不是拒绝服务。
  见下方"已知非问题"。
- **在本来就过载的服务器上运行 PerfScope 所带来的负载。** 插件只是报告负载，不制造服务器的
  问题。实测开销写在 README 里。
- **Minecraft / Paper / Purpur / Folia / Bukkit 自身的漏洞。** 请报告给上游。
- **不在上方支持列表内的版本中的任何问题。**

### 已知非问题

以下行为常被当作 bug 或安全问题上报，但都是有意为之：

1. **"插件在我的 Spigot 服上把自己禁用了。"** 有意如此。PerfScope 依赖 Paper API。在非 Paper 平台
   上，它在 `onEnable` 第一行打印中英双语说明后禁用自己，而不是半装配后在某个莫名其妙的行崩溃。
   请使用 Paper / Purpur / Folia / Leaf / Leaves / Pufferfish。
2. **"非 OP 玩家收到 `You do not have permission`。"** 所有命令节点默认 `op`。
3. **"插件往 `plugins/PerfScope/` 写文件。"** 报告、历史 CSV、告警状态。文件存在本身不构成信息泄露；
   如果宿主机上无关的本地用户能读你的服务器目录，那是主机层面的问题。
4. **"`/perfscope status` 只显示脱敏错误，不给完整堆栈。"** 有意如此。该命令对 `perfscope.use` 开放，
   可能授予非 OP 的管理人员，而异常信息里常含绝对路径与服务器内部信息。完整原文留在控制台日志里，
   只有控制台级权限能读。
5. **"webhook 拒绝我指向内网地址的 `http://` URL。"** 有意为之的 SSRF 防护：`https://` 允许任意主机；
   明文 `http://` 只允许 loopback（`127.0.0.1`、`::1`、`localhost`、`*.localhost`）以便本地 OneBot 使用。
   私有、链路本地、唯一本地与未指定地址段的 IP 字面量一律拒绝。

---

## 数据采集——发什么、不发什么

**没有 PerfScope 的后端、没有账号系统、没有云服务。** 一切都在你的服务器上算完。除非你主动开启或
保留默认遥测，否则不上传任何东西。

### 出站连接

只有**两条**可能的出站连接：

| # | 连接 | 默认 | 目的地 | 由谁控制 |
|---|------|------|--------|---------|
| 1 | bStats 匿名使用统计 | **启用** | `bstats.org` | `general.bstats-enabled: false`，或 `plugins/bStats/config.yml` |
| 2 | Webhook 推送（告警 / 恢复 / 每日摘要） | **关闭** | **你自己配置的 URL**（Discord 或 OneBot） | `alerts.webhook.enabled` |

**没有更新检查器**，本插件自有源码里除 webhook 外没有任何网络代码。你可以自行验证：

```bash
grep -rn "HttpClient\|HttpRequest\|openConnection\|new URL(\|new Socket" src/main/java/
```

所有命中都在 `alert/WebhookNotifier.java`。唯一另一处出站调用在随包发布的、已 relocate 的 bStats 库里
（`cn.perfscope.shaded.bstats`），它用 `HttpsURLConnection` 请求一个固定的 `REPORT_URL`——这也是它被
单列为一条连接、而不算作插件自有代码的原因。

### 第一层——bStats 库自己发的内容

PerfScope 内置 [bStats](https://bstats.org/)（MIT），relocate 到 `cn.perfscope.shaded.bstats`，避免与
其它插件的那份冲突。bStats 库会发一份对所有使用 bStats 的插件都相同的**基线负载**，**本插件无法对它
分桶、屏蔽或追加**；唯一的控制手段是完全关闭 bStats。

| 字段 | 示例 | 说明 |
|------|------|------|
| `pluginVersion` | `1.3.0` | 本插件版本 |
| `playerAmount` | `7` | **发送时的精确在线玩家数——未分桶** |
| `onlineMode` | `true` | 是否正版验证模式 |
| `bukkitVersion` | `1.21.4-R0.1-SNAPSHOT` | 服务端 API 版本串 |
| `javaVersion` | `17.0.10` | JVM 版本 |
| `osName` | `Linux` | 操作系统族 |
| `osArch` | `amd64` | CPU 架构 |
| `osVersion` | `6.8.0-45-generic` | 操作系统内核/构建串 |
| `coreCount` | `8` | 可用处理器数 |
| `serverUUID` | `6f1c…` | **首次运行随机生成的 UUID**，存在本地 `plugins/bStats/config.yml`。不是服务器地址，不是你的 IP |
| `serviceId` | `35061` | bStats 项目 ID——常量，标识 PerfScope |
| `metricsVersion` | `3.2.1` | 实际执行上报的 bStats 库版本 |

> 上表是 bStats 自己的负载，不是本插件选的字段——它是从 `bstats-bukkit` 3.2.1 的字节码里读出来的，
> 不是凭记忆写的。如果精确玩家数或具体内核串对你不可接受，请关闭 bStats：关闭遥测后插件行为完全一致。

### 第二层——本插件注册的自定义图表

这些由本插件添加，且**已分档或为枚举值**：

| 图表 | 示例 | 说明 |
|------|------|------|
| `server_software` | `Paper` | Paper / Purpur / Folia / Spigot |
| `module_enabled` | `entities=1`、`hotspots=0` | 你开启了哪些采集模块 |
| `language` | `en`、`zh`、`other` | 其它值统一归一为 `other` |
| `webhook_channel` | `discord`、`onebot`、`none` | **仅通道类型——绝不含 URL** |
| `entity_count_bucket` | `1000` | 量级档位，非精确值 |
| `loaded_chunks_bucket` | `1000` | 量级档位 |
| `peak_players_bucket` | `10` | 量级档位 |
| `heap_max_mb_bucket` | `4096` | 量级档位 |
| `world_count` | `3` | 分档为 1 / 3 / 5 / 10 / `10+` |
| `loaded_plugins` | `42` | 已安装插件数量 |

### 两层都不会发送

玩家名、玩家 UUID、IP 地址、世界名、插件**名**、白名单/封禁内容、服务器 MOTD、聊天内容、
webhook URL 或 token、配置文件内容、告警文本、报告内容、文件路径。

### 关闭遥测

下面两种方式任一即可，都不影响任何插件功能：

```yaml
# plugins/PerfScope/config.yml
general:
  bstats-enabled: false
```

```yaml
# plugins/bStats/config.yml —— 对全服所有插件的 bStats 生效
enabled: false
```

### 本插件在你磁盘上存什么

全部位于 `plugins/PerfScope/` 下，**都不上传**。

| 路径 | 内容 |
|------|------|
| `config.yml` | 你的配置 |
| `config.yml.bak-<时间戳>` | 配置迁移 / 写回时生成的备份 |
| `messages.yml` | 当前语言的文案覆盖文件 |
| `reports/perfscope-*.{html,txt,json}` | 导出的报告，默认保留 7 天 |
| `history/history-YYYY-MM-DD.csv` | 每次采样一行：`epochMs, tps1m, msptAvg, heapUsagePercent, playerCount, totalEntities, totalLoadedChunks`——只有聚合计数 |
| `alerts.state` | 告警生命周期状态，使告警能跨重启延续。列为 `key\|level\|firstSeen\|lastSeen\|value\|scope\|threshold\|exitThreshold\|module\|state`。**`scope` 在世界维度指标下存的是世界名**（如按世界配置的实体阈值），否则为空 |
| `alerts.jsonl` | 告警历史日志（open/upgrade/downgrade/resolve/refire/restore/suspend），超过 2 MB 轮转为 `alerts.jsonl.1`。含同一个 `scope` 字段，因此**世界名也会出现在这里** |
| `daily-summary.state` | 每日摘要的最后发送标记 |

**任何文件都不会写入玩家名，报告里也不会出现玩家名**——报告只有 `playerCount` 与 `maxPlayers`，
没有玩家列表。`/perfscope players` 会列出在线玩家名、所在世界与 ping，但**只发给执行者本人**，
且受 `perfscope.use`（默认 OP）控制。

> **报告与游戏内输出确实包含世界名与实体/区块计数**——这正是工具的用途。把导出的报告贴到公开
> issue 前，请先自己过一遍。

### webhook 专项加固

- 按凭据对待：URL 内含 token。绝不要提交进仓库或贴到 issue 里；一旦泄露请轮换。
- **实际离开服务器的内容**：webhook 收到的是与控制台、游戏内管理人员完全相同的消息正文——告警、
  恢复通知、每日摘要。按世界配置的实体告警触发时，该正文会包含**世界名**，以及指标数值。若你的
  webhook 指向第三方服务或共享频道，请把这一点考虑进去。
- **SSRF 防护**：`https://` 允许任意主机；明文 `http://` 只允许 loopback（`127.0.0.1`、`::1`、
  `localhost`、`*.localhost`）。链路本地、私有、ULA 与未指定地址段的 IP 字面量一律拒绝。校验发生在
  通知器被标记为 ready **之前**。
- **日志脱敏**：失败路径只记录异常**类型**，绝不拼异常 message——JDK 的 URL 异常会把完整 URL 原样
  回显，等于把 token 写进 `latest.log`。
- **提及注入防护**：Discord 负载固定带 `allowed_mentions.parse = []`，世界名或玩家名里的
  `@everyone` 无法触发全频道 ping。正文会截断（Discord 1900 字符，OneBot 1000）。

---

## 加固建议

- 除非有明确理由，所有 `perfscope.*` 权限保持 `op`。可以给管理人员授予 `perfscope.use`
  （`/perfscope status` 输出本身已脱敏），但 `perfscope.reload` 与 `perfscope.export` 应只给那些
  本来就能改配置、把服务器内部数据落盘的人。
- 若开启 webhook，请把 URL 当密码对待；一旦出现在日志、截图或 issue 里就轮换。
- 分享导出的报告前先自查：它会列出世界名与按世界分布的实体数。
- 用不能读取其它用户文件的账号运行服务器。PerfScope 以普通插件权限写入 `plugins/PerfScope/`，
  它无法在宿主机配置错误的情况下保护文件。
- bStats 保持在你认为合适的档位，但请**有意识地**选择。默认是开启的，发什么就是上面那两层。
