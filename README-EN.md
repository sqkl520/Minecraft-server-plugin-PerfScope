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
| **Paper 1.20.4+** | **Compile-time compatible** | Built against `paper-api 1.20.4` (`pom.xml`), **not yet boot-verified on a real 1.20.x server**; nothing below 1.20.4 is guaranteed |
| **Purpur / Leaf / Leaves** | **Load-verified** | The same Paper code path starts and responds to commands; **fork-private optimizations (AI throttling, entity merging, chunk caching, etc.) are not covered** and may affect the absolute accuracy of entity/chunk counts |
| **Folia** | **Loads, but metrics are limited** | Starts and responds to commands, but **MSPT is unavailable** (no global tick timing) and **TPS is a global-region-local value** (not server-wide throughput); entity/chunk/hotspot modules may report "unavailable" across regions |
| **Plain Spigot / CraftBukkit** | Not supported | Requires Adventure rendering and cannot run without the Paper API |

Additional notes:

- **What the compatibility matrix covers**: the project's 196 compatibility assertions verify **loadability and command reachability** (the command runs and its output contains the expected heading/field). They **do not verify numeric correctness** — every assertion is a label substring match and does not check whether the TPS/MSPT/entity figures are true.
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
| `/perfscope trend [days]` | Trend summary (cross-restart persistent history, default 1 day) |
| `/perfscope gc` | GC statistics (per-collector details) |
| `/perfscope report [format]` | Export performance report (html/txt/json) |
| `/perfscope monitor` | Toggle ActionBar live monitor |
| `/perfscope status` | Plugin self-health (collector alive / module states / history / alerts) |
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

> Data disclosure: This plugin bundles bStats anonymous usage statistics (enabled by default; no player or server-owner personal information is collected). In the current version the author has not yet registered a bStats plugin ID, so **no data is sent**; it activates automatically once the ID is registered. It can be disabled in `plugins/bStats/config.yml` or via `general.bstats-enabled: false` in this plugin's config.yml.

## Configuration

Key configuration options (`config.yml`):

```yaml
general:
  update_interval: 20       # Data collection interval (ticks)
  debug: false

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
  sample-every-cycles: 30       # Hotspot full-sweep throttle (every N collection cycles, ~30s)

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
| CPU | process CPU/system CPU/processors | Processor load |
| GC | count/time/per-collector | Garbage collection stats |
| Tile Entity | total/per-world | Block entity load |
| Server | uptime/threads/plugins/worlds/JVM | Server basic info |

> Note: **Redstone activity** and **network throughput** monitoring were discontinued in v1.1 (redstone was a stub implementation; network was hardcoded fake data); these metrics and features are no longer provided. The `/perfscope redstone` subcommand is kept as an entry point but only returns a discontinued notice.

## Performance & Overhead (Self-Tested, Not a Guarantee)

A performance plugin that cannot explain its own overhead is self-contradictory. The design principle here is **keep heavy work off the main thread**:

- **Async JVM metrics** — memory/CPU/GC/runtime info is read from MXBeans on an async thread and published as a whole snapshot for the main thread to reuse; no long blocking on the main thread. The only disk-related main-thread action is a non-blocking *enqueue* of the history sample once every 60 seconds (an `offer` that drops-and-counts when the queue is full); the actual writing is batched on the `perfscope-history-writer` daemon thread;
- **Main thread does lightweight Bukkit reads only** — TPS/MSPT/players every cycle; entities/chunks every 5 cycles and tile entities every 10 are **built-in constants**, hotspot chunks every 30 (`hotspots.sample-every-cycles`, configurable), reusing caches in between;
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

- Every 5 cycles (5 s by default) one `world.getLoadedChunks()` per world, which allocates a chunk array. The *only* main-thread hit in the table above lands exactly there (`CraftWorld.getLoadedChunks ← collectChunks`). The allocation cost at 2000–5000 chunks is **not measured**;
- Every 30 cycles (30 s by default) a **full sweep of every loaded chunk** (hotspot location): `getEntities()` plus a per-chunk `HashMap` for the dominant type. This is the heaviest main-thread section in the plugin and is **not measured**;
- Once per collection cycle, a `history.size()` cap check (`ConcurrentLinkedDeque.size()` is an O(n) traversal; the ring holds 300 entries, so ~300 node visits per second);
- Every 60 s, one non-blocking history enqueue plus the in-memory ring-buffer write on the main thread.
- **Alert-state persistence**: this used to be synchronous main-thread I/O (`AlertService.persistState()` writing a temp file with `Files.write` and then an atomic `Files.move`). In the 2026-10-25 busy-load sample, **5 of 22** main-thread hits landed on that path (`checkCycle ← persistState ← Files.write`), second only to the chunk/entity sweeps. **That path now hands the write to a single-writer daemon thread** (the state snapshot is still taken on the main thread; only open/write/move moved to the `perfscope-alert-state-writer` daemon thread). The 5-of-22 figure comes from the build *before* that change; the new build has not been re-measured.

**Not yet measured**: high-load P99 (5000 entities / 2000 chunks), GC count and pause impact, heap retention, startup cost, A/B comparison.

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

## License

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

This project is licensed under the [GNU Affero General Public License v3.0](https://www.gnu.org/licenses/agpl-3.0.html) (AGPL-3.0) starting from v1.1. See the LICENSE file in the repository root for the full text.

In short:
- Anyone may freely use this plugin, including commercially — no fee and no additional permission required;
- Any distribution (modified or not, commercial or not) must retain the copyright notice and license text, and be licensed as a whole under AGPL-3.0;
- Running this plugin as a network service (e.g. on your server for players) requires offering the Corresponding Source to users (AGPL-3.0 §13).

Version note: v1.0.0 and earlier released versions remain perpetually under GPL-3.0 and are unaffected by this change; AGPL-3.0 applies starting from v1.1.