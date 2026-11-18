[简体中文](SECURITY.md) | English

# Security Policy

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
| 1.4.x | ✅ Yes | Security fixes land here |
| 1.3.x | ⚠️ Critical fixes only, until 30 days after the first 1.4.x release | |
| 1.2.x and older | ❌ No | Upgrade |

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

