English | [简体中文](README.md)

# PerfScope —— Server Performance Monitor

[![Build](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/actions/workflows/build.yml/badge.svg)](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/sqkl520/Minecraft-server-plugin-PerfScope)](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/latest)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

A comprehensive performance monitoring plugin for Minecraft servers, focused on server-side real-time performance tracking and diagnostics. Supports in-game commands for live data viewing, per-world independent alerts, and automatic performance report generation.

## Features

- **Full Diagnostic** —— Type `/perfscope` to view all core performance metrics in one place, one-stop diagnostics
- **TPS Monitoring** —— 1m/5m/15m real-time collection, with customizable warning/critical alert thresholds
- **MSPT Monitoring** —— avg/min/max three-metric collection; precise tick durations via Paper `CraftServer.getTickTimes()`, degrading in order to `getAverageTickTime` / TPS inference when unavailable (only the last level is an estimate), plus theoretical max TPS calculation
- **Player Statistics** —— Online/max/online list (world + ping), with capacity alerts
- **Entity Statistics** —— Total/per-world/by-type Top15; entity-total alert threshold (server-wide aggregate), overridable per world in the `worlds` section
- **Chunk Statistics** —— Loaded chunks/force-loaded chunks/per-world, with independent alerts
- **Memory Diagnostics** —— Heap used/max/usage%/allocated, non-heap memory, GC count/time/per-collector details
- **CPU Diagnostics** —— Process CPU usage/system CPU load/available processors/active threads
- **Tile Entity Statistics** —— Total/per-world distribution
- **Server Info** —— Uptime/thread count/plugin count/world count/JVM version
- **Highly Customizable Config** —— All module alert thresholds configurable (collection cadence is set by `general.update_interval`; per-module entity/chunk/tile sampling rates are built-in constants)
- **ActionBar Live Monitor** —— `/perfscope monitor` to toggle, refreshes core metrics every second to the ActionBar (above the hotbar at the bottom of the screen). Positioned as a **diagnostic probe**: turn it on for a few dozen seconds while troubleshooting lag or handling an incident, then turn it off; it is not designed as an always-on 24/7 monitoring panel
- **Performance Report Export** —— HTML/TXT/JSON formats, HTML reports include TPS trend chart, entity distribution table, memory progress bar, alert highlights
- **Auto Report Generation** —— Configurable interval, async generation without blocking main thread, auto cleanup by retention days
- **Per-World Independent Alerts** —— Override entity alert thresholds per world
- **Threshold Alert Push** (new in v1.1, converged in v1.2) —— When an alert fires, pushes to the console + online players holding the `perfscope.alert` permission; by default an alert must stay over-threshold for 3 consecutive collection cycles before the first push (debounce, configurable); the same alert has a default 300-second cooldown to prevent spam; level upgrades (warning→critical) send a single "（级别升级）" message bypassing the cooldown; a recovery notice is sent when the issue clears; configurable in the `alerts` section of `config.yml`
- **Webhook Push** (new in v1.2) —— Alerts / recoveries / daily summaries can be pushed to a Discord webhook or QQ OneBot, so the owner is reachable even when offline; sent fully asynchronously, failures are logged without blocking
- **Daily Summary** (new in v1.2) —— A 3-line digest at a fixed time each day: lowest TPS (with time) / peak entities & heap / alert statistics; if the server happens to be down at the configured time, one catch-up summary is sent after startup
- **History Summary** (new in v1.2) —— `/perfscope history` shows the last ~24 hours: coverage, lowest TPS with its timestamp, peak entities, peak heap — answering "when did it start getting worse"
- **Hotspot Localization** (new in v1.2) —— `/perfscope hotspot` lists the top-3 entity-density chunks server-wide with coordinates (and dominant type share), drilling from "many entities" down to "go look at this spot"; coordinates are click-to-copy
- **Color-Coded UI** —— Rendered via Paper Adventure API, key metrics colored by level (green/yellow/red) based on the `config.yml` alert thresholds; display and alerts **share the same config source and the same level-decision entry point**, and colors follow immediately after a config reload. Note: the display uses closed intervals (color changes when the threshold is *reached*) while some alert checks use strict greater-than, so **at exactly the threshold the color flips a moment before the alert fires** — an intentional early-warning semantic

## Target Platform & Compatibility

Since v1.1, PerfScope focuses on the **Paper family** of servers and relies heavily on Paper-native features such as the Adventure API.

> Bottom line up front: **"loads and the command answers" ≠ "the numbers are correct"**. The table below separates the two, so read it as a measure of verification strength.

| Platform | Verification level | Notes |
|----------|--------------------|-------|
| **Paper 1.21.x** | **Verified on a real server** | Full command surface + threshold alerts + history persistence + diagnostic engine exercised on a live server |
| **Paper 1.20.4** | **Verified on a real server** (since v1.2.1) | Measured on Paper 1.20.4 build 343: enables cleanly; `/perfscope` full diagnostics, `/status`, `/diagnose` (three-state output), `/hotspot`, `/trend` all work; MSPT uses `getTickTimes()` with real avg/min/max. Versions below 1.20.4 are not guaranteed |
| **Purpur / Leaf / Leaves** | **Load-verified** | The same Paper code path starts and responds to commands; **fork-private optimizations (AI throttling, entity merging, chunk caching, etc.) are not covered** and may affect the absolute accuracy of entity/chunk counts |
| **Folia** | **Loads, but metrics are limited** | Starts and responds to commands (commands/config work), but **MSPT is unavailable** (no global tick timing) and **TPS is a global-region-local value**; **the entity / chunk / tile-entity / hotspot modules are skipped automatically** (`/status` reports a "platform limit"). **Note**: automated verification on Folia only covers loadability and command reachability for this version — long-running behaviour and numeric correctness are **unverified** |
| **Pufferfish** | **Unverified** | Never run on Pufferfish. The Paper code path should be compatible, but there is **no measured evidence** — do not treat it as "supported" |
| **Plain Spigot / CraftBukkit** | Not supported | Requires Adventure rendering and cannot run without the Paper API. The plugin detects the platform on the first line of `onEnable` and **disables itself** with a clear console message, instead of half-initialising and crashing later |

Additional notes:

- **What the compatibility matrix covers**: the project's 189 scenario assertions verify **loadability and command reachability** (the command runs and its output contains the expected heading/field). They **do not verify numeric correctness** — every assertion is a label substring match and does not check whether the TPS/MSPT/entity figures are true. Check the **last measured date** for each platform in the test reports under `docs/`; do not assume "ran once" means "still correct on the current version".
- **MSPT semantics on Paper 1.21.x**: sourced from Paper's `getTickTimes()` (avg/min/max in one call). If that implementation returns all zeros, the plugin re-checks it (two consecutive confirmations, to avoid a transient false positive) roughly 5–10 seconds after enable, automatically downgrades it to "unavailable", and **never prints a fake-precise value**.
- **TPS labelling on Folia**: every TPS output carries a `(global region)` tag. The global region only runs global logic and chunks that belong to no region, so the figure being **lower** than the server-wide throughput players actually feel is expected; early in an empty server's life the global-region report also has a gap window, so a briefly near-zero reading does not mean the server is frozen.
- **MSPT on Folia**: `getTickTimes()` exists on that platform but always returns all zeros. The plugin therefore marks MSPT unavailable right at enable (no sampling delay needed); `/perfscope mspt` reports "unavailable (Folia has no global tick timing)".
- **Scheduling on Folia**: adapted via `GlobalRegionScheduler` / `AsyncScheduler`, with `folia-supported: true` declared in `plugin.yml`; JVM-level metrics (memory/CPU/GC/threads) are fully available.

## Commands

| Command | Description |
|------|------|
| `/perfscope` | Full diagnostic (default) |
| `/perfscope all` | Full diagnostic (same as no args) |
| `/perfscope tps` | TPS detailed check + trend chart |
| `/perfscope mspt` | MSPT detailed check + theoretical max TPS |
| `/perfscope entities [world]` | Entity statistics (per-world/by-type Top15) |
| `/perfscope chunks [world]` | Chunk statistics (loaded/force-loaded) |
| `/perfscope memory` | Memory diagnostics (heap/non-heap/GC/usage bar) |
| `/perfscope cpu` | CPU diagnostics (process/system/threads) |
| `/perfscope players` | Player statistics (online/list/ping) |
| `/perfscope world [world]` | World details (clickable navigation) |
| `/perfscope redstone` | Redstone activity (discontinued in v1.1) |
| `/perfscope tileentities` | Tile Entity statistics |
| `/perfscope uptime` | Uptime + JVM info |
| `/perfscope history` | History summary (last ~24h: lowest TPS / peak entities / peak heap) |
| `/perfscope hotspot` | Hotspot localization (entity-density top-3 chunks with coordinates) |
| `/perfscope diagnose` | Smart diagnosis (rule engine: conclusions + parameter-level advice; `diag` stays the full-diagnostics alias) |
| `/perfscope trend [days]` | Trend summary (cross-restart persistent history, default 1 day). Falls back to the in-memory ring and clamps to 1 day when persistence is unavailable; unparsable history rows are reported with their count |
| `/perfscope gc` | GC statistics (per-collector details) |
| `/perfscope report [format]` | Export performance report (html/txt/json) |
| `/perfscope monitor` | Toggle ActionBar live monitor (when collection has stalled it shows "no data for N" instead of stale figures) |
| `/perfscope status` | Plugin self-health (collector alive / module states / history / alerts). The last collection error is shown as a redacted summary (exception type + first line, absolute paths replaced by `<path>`); the full text stays in the console log |
| `/perfscope reload` | Reload config file |
| `/perfscope help` | Show command help |

Aliases: `/pscope`, `/perf`

## Full Diagnostic Output Example

When you run `/perfscope`, the output looks like:

```
=== PerfScope 诊断 ===
采集时间: 14:35:22
TPS (1m/5m/15m): 20.0 / 19.8 / 19.5
MSPT (avg/min/max): 15.23ms / 8.10ms / 28.50ms
在线玩家: 3 / 20
实体总数: 847
加载区块: 632 (强加载: 4)
--- 服务器级诊断 ---
  世界: world | 实体: 520 | 区块: 380 | 强加载: 4 | 方块实体: 72
  世界: world_nether | 实体: 215 | 区块: 180 | 强加载: 0 | 方块实体: 45
  世界: world_the_end | 实体: 112 | 区块: 72 | 强加载: 0 | 方块实体: 18
内存: 2048MB / 8192MB (25%)
CPU: 进程 12.3% / 系统 35.8%
运行时间: 2天 6时 35分 22秒 | 线程: 48 | 插件: 15 | 世界: 3
GC: 42 次 / 5.23 秒
```

## Permissions

| Permission Node | Default | Description |
|------|------|------|
| `perfscope.use` | OP | Basic usage permission (view performance data) |
| `perfscope.admin` | OP | Admin permissions (includes all sub-permissions) |
| `perfscope.reload` | OP | Reload config permission |
| `perfscope.export` | OP | Export report permission |
| `perfscope.alert` | OP | Receive in-game alert pushes |

## Installation

> **Download**: [GitHub Releases](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/latest) (latest stable **v1.2.1**, `PerfScope-1.2.1.jar`)

1. Download the latest JAR file
2. Place it in your server's `plugins/` directory
3. Restart the server or use `/plugman load PerfScope`
4. Edit `plugins/PerfScope/config.yml` for custom configuration
5. Use `/perfscope` to view performance data

> Data disclosure: This plugin bundles bStats anonymous usage statistics (**enabled by default**). The plugin ID is **35061** (<https://bstats.org/plugin/bukkit/PerfScope/35061>), so **data is actually being reported**. What gets sent comes in two layers with different granularity:
>
> **Layer A — sent automatically by the bStats library (not individually controllable by this plugin)**: server software type and version, plugin version, OS name and version, CPU core count, **current online player count (exact, not bucketed)**, online/offline mode, and a server UUID (randomly generated, not a server address).
>
> **Layer B — custom charts added by this plugin (bucketed)**: server software type, enabled module combination, UI language, magnitude buckets for entity count / loaded chunks / peak players / max heap, world count, installed plugin count, and webhook channel type.
>
> **Neither layer uploads**: player names, world names, coordinates, chat content, plugin lists, config file contents, alert text, or server addresses. It can be disabled in `plugins/bStats/config.yml` or via `general.bstats-enabled: false` in this plugin's config.yml.

## Configuration

Key configuration options (`config.yml`):

```yaml
general:
  update_interval: 20       # Data collection interval (ticks)
  debug: false
  language: en              # UI language: en (default) / zh; takes effect on /perfscope reload

tps:
  enabled: true
  warning_threshold: 18.0   # TPS warning threshold
  critical_threshold: 15.0

mspt:
  enabled: true
  warning_threshold: 45.0   # MSPT warning threshold (ms)
  critical_threshold: 50.0

players:
  enabled: true
  warning_threshold: 90     # Player-count warning threshold (% of max players)
  critical_threshold: 98

entities:
  enabled: true
  total_warning_threshold: 2000
  total_critical_threshold: 3000

chunks:
  enabled: true
  loaded_warning_threshold: 1500
  loaded_critical_threshold: 2500

memory:
  enabled: true
  heap_warning_threshold: 85
  heap_critical_threshold: 95

cpu:
  enabled: true
  process_warning_threshold: 80.0   # Process CPU usage warning threshold (%)
  system_warning_threshold: 90.0    # System CPU load warning threshold (%)
  hysteresis_margin: 5.0            # Hysteresis band (percentage points): an alert clears only after load falls to threshold-minus-this value, preventing boundary flicker

report:
  auto_export: false
  auto_export_interval: 30  # Auto export interval (minutes)
  format: html
  retention_days: 7         # Report retention days

alerts:
  enabled: true             # Master switch (false = no alert checks or pushes at all)
  console: true             # Console push
  in-game: true             # In-game push (online players with perfscope.alert permission)
  cooldown-seconds: 300     # Cooldown per alert in seconds (0 = no cooldown, still throttled by the collection cycle)
  notify-recovery: true     # Recovery notice (sent when an alert clears)
  debounce-cycles: 3        # Debounce: N consecutive over-threshold cycles before the first push (1 = push immediately)
  flap-window-seconds: 180  # Flap window: a "recovery → re-breach" inside this window is treated as the same incident (no duplicate notifications)
  repush-delta: 8           # Re-push delta: an active alert is not re-pushed unless its value changes by at least this much (applies to cpu:system only)
  refire-min-interval-seconds: 60  # Re-fire interval: after a recovery, a re-breach counts as a "re-fire" only within this window (0 = disable re-fire detection; a re-breach is then treated as a normal new alert, still subject to cooldown-seconds)
  daily-summary:
    enabled: false          # Daily summary switch
    time: "08:00"           # Daily summary time (local HH:mm)
  webhook:
    enabled: false          # Webhook push switch (alerts/recoveries/daily summary)
    type: discord           # discord | onebot (QQ)
    url: ""                 # discord: https://discord.com/api/webhooks/... ; onebot: http://127.0.0.1:3000/send_private_msg?...

history:
  enabled: true
  sample-interval-seconds: 60   # History sampling interval (s); ring covers about 24 hours
  persist: true                 # File persistence (daily CSV; /perfscope trend survives restarts)
  retention-days: 7             # History file retention (days)

hotspots:
  enabled: true
  sample-every-cycles: 30       # Hotspot refresh throttle (one full round every N cycles, ~30s)
  scan-shards: 10               # Incremental scan shards (1 = off; higher = cheaper per tick, slower refresh)

# Per-world overrides
worlds:
  world:
    entities:
      total_warning_threshold: 2500
  world_nether:
    entities:
      total_warning_threshold: 2000
```

See `plugins/PerfScope/config.yml` for complete configuration details.

### Language (i18n)

The plugin ships **two complete message sets — English (default) and Simplified Chinese** — selected by `general.language`:

```yaml
general:
  language: en        # en = English (default), zh = Simplified Chinese
```

- **Switching**: edit `general.language`, then `/perfscope reload`. `en_US`/`en-us` and `zh_CN`/`zh-TW` are normalized to their primary language; anything else warns on the console and falls back to `en`.
- **All-or-nothing**: the active language is published as a single immutable snapshot on each reload, so no single output can mix languages.
- **Custom text**: `plugins/PerfScope/messages.yml` overrides any built-in message. Its first line must declare `language: en` or `language: zh` — **a file whose tag does not match `general.language` is ignored entirely** (so Chinese overrides can never leak into an English UI), and both the console and the reload reply say so explicitly.
- **Placeholders**: messages may contain `{0}`, `{1}`, … filled in by the plugin. Missing arguments leave the placeholder verbatim; nothing throws.
- **Upgrading**: a legacy `messages.yml` with no `language` tag is backed up and regenerated for the current language **if you never customized it**; if it contains customizations it is left untouched and not applied this run (the reload reply tells you), so add the `language:` tag and translate the entries.

> Exceptions (do not follow the language): the `plugin.yml` description, report files already exported, history entries already written (stored in the language they were written in; not re-rendered on switch), and webhook messages already queued at the moment of the switch.

> Note (threshold direction): TPS is a low-is-bad metric — the lower the worse — so it requires `warning_threshold ≥ critical_threshold` (the opposite of high-is-bad metrics such as MSPT or memory; do not invert it by mistake). If inverted, `/perfscope reload` validates and falls back to the default values, logging a "配置校验：…设置倒置，已回退默认值" warning to the console — it will not take effect silently.
>
> Known behavior (alert cooldown across reload): the alert cooldown table is **not** cleared by `/perfscope reload` (by design, to prevent alert re-pushes on reload); as a side effect, during troubleshooting a genuine alert may be briefly suppressed by a cooldown window left over from before the reload.
>
> Known behavior (alert debounce delay): by default an alert must stay over-threshold for 3 consecutive collection cycles before the first push (`alerts.debounce-cycles`, configurable; 1 = push immediately), adding up to ~3× the collection interval of latency — a deliberate trade for a useful signal instead of a crying-wolf one; level upgrades (warning→critical) bypass both cooldown and debounce and arrive immediately as a single "（级别升级）" message.

## All Monitorable Metrics

| Category | Metrics | Description |
|------|------|------|
| TPS | 1m/5m/15m average TPS | Core server health indicator |
| MSPT | avg/min/max MSPT | Per-tick duration (ms), theoretical max TPS |
| Players | online/max/online list (world + ping) | Player load |
| Entities | total/per-world/by-type Top15 | Entity pressure analysis |
| Chunks | loaded/force-loaded/per-world | Chunk load |
| Memory | heap used/max/usage%/allocated/non-heap | JVM memory status |
| CPU | process CPU/system CPU/processors | Processor load (**process CPU reads "unknown" on Windows**, see below) |
| GC | count/time/per-collector | Garbage collection stats |
| Tile Entity | total/per-world | Block entity load |
| Server | uptime/threads/plugins/worlds/JVM | Server basic info |

> Note: **Redstone activity** and **network throughput** monitoring were discontinued in v1.1 (redstone was a stub implementation; network was hardcoded fake data); these metrics and features are no longer provided. The `/perfscope redstone` subcommand is kept as an entry point but only returns a discontinued notice.

> Note (process CPU on Windows): the Windows implementation of `getProcessCpuLoad()` enumerates every process name on each call, measured at **p50 171 ms / p90 257 ms / max 332 ms** of blocking, and it runs on Bukkit's **shared** async pool where it competes with other plugins' async tasks. PerfScope therefore **does not collect process CPU on Windows** — that metric shows "unknown" and `cpu.process_warning_threshold` never fires; system CPU and every other metric work as normal. On Linux / macOS the value comes from `/proc` or an equivalent interface at microsecond cost, so process CPU is collected normally there.

### Wiring up Prometheus / Grafana

With `prometheus.enabled: true` the plugin exposes Prometheus text format (0.0.4) metrics on
`127.0.0.1:9940/metrics`, with no third-party dependencies. Add a scrape job:

```yaml
scrape_configs:
  - job_name: minecraft
    static_configs:
      - targets: ["127.0.0.1:9940"]
```

Beyond TPS/MSPT/memory/entities/chunks, the endpoint exports the plugin's own **trustworthiness
signals** — which is what distinguishes it from a generic MC exporter:

| Metric | Purpose |
|---|---|
| `perfscope_snapshot_age_seconds` | Tells "the server died" apart from "the metrics froze". Most exporters have no equivalent |
| `perfscope_collector_degraded` | Whether collection has gone stale (0/1) |
| `perfscope_module_available{module}` | Per-module availability for the 11 collectors — look here first when a curve breaks |
| `perfscope_alerts_active` / `perfscope_alerts_suspended` | Current state of the alert state machine |
| `perfscope_tps{period}` / `perfscope_tick_duration_seconds{stat}` | Note the unit: tick duration is in **seconds**; multiply by 1000 in Grafana to show MSPT |

A few conventions worth knowing before reading the data:

- **Uncollected metrics are omitted entirely**, never rendered as `-1`. A missing row shows as a gap
  in Grafana, which is more honest than a line pinned to the floor. On Windows,
  `perfscope_cpu_process_ratio` is simply absent.
- **No `instance` / `job` labels are emitted** — Prometheus attaches its own target labels under
  those names at scrape time, so emitting them would get yours renamed to `exported_instance` and
  leave two competing instances in Grafana. Use `server-id` to tell servers apart.
- **On Folia**, `perfscope_build_info` carries `tps_scope="global_region"`, because TPS there is
  only the global region's local value, not the whole server's.
- The endpoint listens on loopback only by default. Binding it to `0.0.0.0` logs a WARNING: player
  counts, world names and plugin counts are all in there, so set `auth-token` before exposing it.

## Performance & Overhead (Self-Tested, Not a Guarantee)

A performance plugin that cannot explain its own overhead is self-contradictory. The design principle here is **keep heavy work off the main thread**:

- **Async JVM metrics** — memory/CPU/GC/runtime info is read from MXBeans on an async thread and published as a whole snapshot for the main thread to reuse; no long blocking on the main thread. The only disk-related main-thread action is a non-blocking *enqueue* of the history sample once every 60 seconds (an `offer` that drops-and-counts when the queue is full); the actual writing is batched on the `perfscope-history-writer` daemon thread;
- **Main thread does lightweight Bukkit reads only** — TPS/MSPT/players every cycle; entities/chunks every 5 cycles and tile entities every 7 are **built-in constants**; hotspot chunks are scanned **incrementally in shards**: one round still covers every loaded chunk, but it is split into `hotspots.scan-shards` pieces (default 10) spread across several collection cycles, cutting per-cycle cost to about 1/10 (a full round completes every 30 cycles by default; other cycles reuse the last complete round);
- **Guarded player-layer pushes** — the per-second ActionBar push only runs while a player has monitor on, and skipped (not crashed) the moment they disconnect.

**Measured (JFR, 90-second window, build under test: a 2026-10-18 master snapshot, *not* the v1.2 release; empty server + 1 bot / `view-distance=5` / ~121 loaded chunks)**:

| Metric | Value |
|---|---|
| **Samples** containing a `cn.perfscope` frame | 37 / 4394 = **0.84%** |
| Of those, on async threads (JVM metric collection) | 36 / 37 |
| Main thread (Server thread) | 1 hit out of 19 samples (Wilson 95% CI ≈ 0.9%–24.6%) |

**What this number is, and what it is not** (every line matters):

- It is a **share of samples** in the thread-time composition — **not a CPU percentage**. The denominator is a count of JFR samples, not CPU time.
- It does **not** translate into a TPS or tick-latency impact. No A/B run and no tick-duration comparison were done, so 0.84% cannot be converted into "N TPS lost".
- **96.75% of the samples in that window (4251 / 4394) came from eight idle I/O threads blocking in native code** — `HttpClient-SelectorManager` ×2, `FileSystemWatchService`, `HTTP-Dispatcher`, `RCON Listener`, `Netty Server IO #0/#1/#2`, about 531 samples each. They consume almost no CPU (parked in `WEPoll.wait` / `GetQueuedCompletionStatus`), so the sample total mostly reflects how often idle threads were sampled, not who is doing work. 0.84% is computed over a denominator dominated by idle threads.
- The main thread contributed only **19 samples with 1 hit**. The Wilson 95% interval is roughly 0.9%–24.6% — **not enough evidence to conclude anything**, in either direction.
- The measurement is from the **2026-10-18 05:14 build**, which predates the hotspot full-world sweep commit (21:39 the same day) and the CSV persistence commit (02:17 the next day). So it is **not the v1.2 release build**. The `MetricsCollector.java:656` in the sampled stack is the line number of *that* source tree (the `world.getLoadedChunks()` call inside `collectChunks`); in the current tree that method sits at a different line — which by itself shows the sampled binary and the repository HEAD were not the same code. The table therefore does **not** cover v1.2's new main-thread paths.
- An earlier draft reported 110 frame *lines* ≈ 2.5%. That counted **frame lines as samples**: one call chain (`getProcessCpuLoad0 → getProcessCpuLoad() → collectCPU → lambda$collectJvmAsync$5 → safeCollect`) spans 3 `cn.perfscope` lines, inflating the result ~3×. Every number here is now counted **per sample block**.

**Known main-thread cost points** (listed honestly, not quantified):

- Every 5 cycles (5 s by default) one `world.getLoadedChunks()` per world, which allocates a chunk array. The *only* main-thread hit in the table above lands exactly there (`CraftWorld.getLoadedChunks ← collectChunks`). **Measured on 2026-11-10** at high load: at 4,835 chunks this method accounts for 34.8% of plugin main-thread samples (the plugin overall is 0.481% of main-thread samples), capping the achievable saving at about **0.042 ms/tick** — two orders of magnitude below the measurement noise, so it was deliberately **left alone** (reasons in the section below);
- Every 30 cycles (30 s by default) a **full sweep of every loaded chunk** (hotspot location): `getEntities()` plus a per-chunk `HashMap` for the dominant type. This is the heaviest main-thread section in the plugin. **Now measured**, see below;
- Once per collection cycle, a `history.size()` cap check (`ConcurrentLinkedDeque.size()` is an O(n) traversal; the ring holds 300 entries, so ~300 node visits per second);
- Every 60 s, one non-blocking history enqueue plus the in-memory ring-buffer write on the main thread.
- **Alert-state persistence**: this used to be synchronous main-thread I/O (`AlertService.persistState()` writing a temp file with `Files.write` and then an atomic `Files.move`). In the 2026-10-25 busy-load sample, **5 of 22** main-thread hits landed on that path (`checkCycle ← persistState ← Files.write`), second only to the chunk/entity sweeps. **That path now hands the write to a single-writer daemon thread** (the state snapshot is still taken on the main thread; only open/write/move moved to the `perfscope-alert-state-writer` daemon thread). The 5-of-22 figure comes from the build *before* that change; the new build has not been re-measured.

**Not yet measured**: high-load P99 (5000 entities / 2000 chunks), GC count and pause impact, heap retention, startup cost, A/B comparison.

### Collection-cost self-monitoring (v1.3.0+, checkable in-game)

`/perfscope status` now reports the **main-thread collection cost distribution** directly, so you no longer need an external profiler to see it:

```
Collection cost: p50 0ms / p95 54ms / max 127ms (66 samples, main thread)
5 cycles exceeded 50ms (1 tick) - the collector is eating into tick budget
```

**How to read the three numbers** (this matters):

- **`p50`** is the normal case. On an idle test server it is **0ms** — most collection cycles do almost nothing (the cycles that reuse caches).
- **`p95` / `max`** are the "collision ticks". Collection is throttled in tiers: entities and chunks every 5 cycles, tile entities every 7, hotspots every 30. The least common multiple of those periods decides when several of them fire together. The measured idle-server `max` is **127ms (~2.5 ticks)**, landing on the cycle where all four coincide.
- **The over-budget count** is the frequency. 5 of 66 samples exceeded 50ms, i.e. about **7.6% of cycles** go past one tick.

**Reading only one of them misleads**: catching `max` on a collision tick makes it look like "127ms every second"; reading only `p50` makes it look like "no overhead at all". The three together are the only interpretable picture.

**Known scale boundaries** (honest, not a guarantee):

| Scale | Collection p50 / p95 / max | Evidence grade | Note |
|---|---|---|---|
| 147 chunks / 5 entities | 0 / **1** / **4ms** | **A** | idle baseline; reproducible via `/perfscope status` |
| 2,723 chunks / 1,913 entities | 0 / **8** / **13ms** | **A** | `forceload` + bulk summon via data pack |
| 4,835 chunks / 8,222 entities | 1 / **17** / **22ms** | **A** | same method; two independent runs agreed (22ms / 22ms) |
| ~11,000 chunks (before partitioning) | ~66.6ms | **B** | historical baseline, **no longer applicable** to current code |
| ~50,000 entities | — | **C** | never measured; expected well below the old figure after partitioning |

**The most important thing about this table**: collection cost grows **far more slowly with scale**
than early estimates suggested. An early benchmark measured 66.6ms at 11k chunks and the
"50k entities would be 100ms+" figure was extrapolated from it — but after v1.4's hotspot
partitioning, the 8,222-entity tier tops out at **22ms**. Partitioning spreads the heaviest sweep
across several collection cycles, so the old model of "scale up → per-cycle cost rises linearly"
no longer holds.

**What the grades mean**:

- **A** — there is a reproducible script (`testharness/scripts/perf-load-bench.ps1`, parameterised
  three tiers + module A/B); anyone can reproduce the same order of magnitude.
- **B** — measured once with a dedicated method, but **on an older build**; not applicable to current code.
- **C** — never run at the target scale. **Do not make decisions on it.**

**The boundary of these numbers (you must know this)**: the load is `forceload`-ed chunks plus
bulk-summoned armour stands on **empty terrain** — no real players, no redstone, no chunk
generation pressure. A real large server's main-thread pressure is **far higher** than this test
environment. So "22ms" means "**PerfScope's own collection cost at 8000 entities**", not
"a large server only stutters 22ms in total".

**What was deliberately not done**: no phase offsets, no chunk-period change. Scattering only
helps if *each module on its own* stays under one tick — partitioning is what makes that
precondition actually hold.

**One optimisation evaluated and rejected** (2026-11-10): replacing `world.getLoadedChunks()` in
`collectChunks` with a cheaper API. Line-level JFR attribution did confirm it is the plugin's
single heaviest main-thread item (34.8% of plugin main-thread samples; 95.4% of those land on that
one line). It was dropped for three reasons:

1. **The gain is below the measurement noise** — the whole plugin is only 0.481% of main-thread
   samples, so the ceiling is **0.042 ms/tick**; and the Wilson 95% interval on 65/38,905 is ±25%,
   so the change could not be verified even if it worked.
2. **Neither replacement API is usable** — on Paper 1.20.4 `getChunkCount()` counts chunks at
   `>= BLOCK_TICKING` while `getLoadedChunks()` counts `>= FULL`, so **the chunk count would be
   systematically under-reported**; and `getForceLoadedChunks()` calls `getChunkAt` with
   `load=true` per forced chunk, which **triggers chunk loading** — a diagnostic plugin must not
   change the world's load state.
3. **An A/B with every collector disabled made the tick *slower*, 57.5ms → 65.9ms** — the
   instrument cannot resolve "all off", so removing 35% of it cannot be verified in principle.

The full evidence chain is in `docs/06-tasks/research/v140-01-cpu-async-blocking.md`.

Reproduce: `tools/perfscope-jfr-overhead.ps1` (counts per sample block, requests both `jdk.ExecutionSample` and `jdk.NativeMethodSample`, isolates Server thread with a Wilson interval, prints the tested jar's version and SHA-256; **excludes `cn.perfscope.shaded.*`** — that is the shaded bStats network stack, and counting it as plugin overhead inflates the number: with every collector disabled the residue was still 6.46%).
The full executable minimal measurement set (three load tiers, A/B baseline, artifact format) is in `docs/07-guides/PERF-BASELINE-PLAN.md`.

Reproduce: `tools/perfscope-jfr-overhead.ps1` (counts per sample block, requests both `jdk.ExecutionSample` and `jdk.NativeMethodSample`, reports Server thread separately with a Wilson interval, and prints the tested jar's version and SHA-256).
The full, executable minimal measurement set (three load tiers, A/B baseline, artifact format) is in `docs/07-guides/PERF-BASELINE-PLAN.md`.

> Caveat: the table above is a **magnitude reference** from one shared dev machine (empty server + 1 bot, `view-distance 5`, ~121 loaded chunks, 90-second window) — not a performance guarantee and not representative of your server. The test load is only **2%–5%** of the 2000–5000-chunk target (121 chunks vs 2000–5000, a 16–41× gap). If you observe abnormal overhead, report it with a `/spark profiler` link.

## Boundaries & What This Plugin Does Not Do

An honest scope statement — the following are things this plugin does **not** do; pair it with other tools as needed:

1. **No root-cause profiling** —— It narrows down *which* metric is abnormal (a suspect range), but does not produce method-level flame graphs; use `/spark profiler` to actually locate the cause of lag.
2. **No long-term time-series platform** —— History **is persisted** (enabled by default, see `history.persist`): an in-memory ring buffer holds ~24 hours (queryable via `/perfscope history`), and daily CSV files are written to `plugins/PerfScope/history/history-<date>.csv`, kept for 7 days by default (`history.retention-days`); `/perfscope trend` can look back across restarts. What it does **not** do: weekly/monthly trend analysis, multi-server aggregation, or replacing an external monitoring stack (Prometheus/Grafana) — hook one up if you need long-term retention or cross-server comparison.
3. **Limited MSPT spike-detection window** —— Values come from Paper `getTickTimes()`, a rolling window (roughly the last 100 ticks); a spike that already scrolled out of the window may not be reflected, so "did it just lag?" is not always answerable.
4. **No automatic remediation** —— The plugin only observes, alerts and exports reports; it never performs any intervention on its own (no restarts, no entity cleanup, no config changes).
5. **Alerts are debounced** —— By default an alert needs 3 consecutive over-threshold collection cycles before it is pushed (`alerts.debounce-cycles`, configurable), adding up to ~3× the collection interval (about 3 seconds by default) of latency; a deliberate trade to suppress threshold-flicker noise — set `debounce-cycles` to 1 if you need instant delivery.

## Dependencies

- Paper 1.20.4+
- Java 17+

## Build

```bash
mvn clean package
```

The JAR file will be generated in the `target/` directory.

The repository ships unit tests (JUnit 5, `src/test/java`); `mvn clean package` runs them before packaging:

```bash
mvn test                        # tests only
mvn test -Dtest=CsvLayoutTest   # a single test class
mvn clean package -DskipTests   # build without tests
```

Test dependencies are `test`-scoped and never enter the final JAR.

## License

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

This project is licensed under the [GNU Affero General Public License v3.0](https://www.gnu.org/licenses/agpl-3.0.html) (AGPL-3.0) starting from v1.1. See the LICENSE file in the repository root for the full text.

In short:
- Anyone may freely use this plugin, including commercially — no fee and no additional permission required;
- Any distribution (modified or not, commercial or not) must retain the copyright notice and license text, and be licensed as a whole under AGPL-3.0;
- Running this plugin as a network service (e.g. on your server for players) requires offering the Corresponding Source to users (AGPL-3.0 §13).

Version note: v1.0.0 and earlier released versions remain perpetually under GPL-3.0 and are unaffected by this change; AGPL-3.0 applies starting from v1.1.