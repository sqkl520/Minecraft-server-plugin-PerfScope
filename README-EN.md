English | [简体中文](README.md)

# PerfScope —— Server Performance Monitor

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
- **Highly Customizable Config** —— All module alert thresholds and collection intervals configurable
- **ActionBar Live Monitor** —— `/perfscope monitor` to toggle, refreshes core metrics every second to the ActionBar (above the hotbar at the bottom of the screen). Positioned as a **diagnostic probe**: turn it on for a few dozen seconds while troubleshooting lag or handling an incident, then turn it off; it is not designed as an always-on 24/7 monitoring panel
- **Performance Report Export** —— HTML/TXT/JSON formats, HTML reports include TPS trend chart, entity distribution table, memory progress bar, alert highlights
- **Auto Report Generation** —— Configurable interval, async generation without blocking main thread, auto cleanup by retention days
- **Per-World Independent Alerts** —— Override entity alert thresholds per world
- **Threshold Alert Push** (new in v1.1, converged in v1.2) —— When an alert fires, pushes to the console + online players holding the `perfscope.alert` permission; by default an alert must stay over-threshold for 3 consecutive collection cycles before the first push (debounce, configurable); the same alert has a default 300-second cooldown to prevent spam; level upgrades (warning→critical) send a single "（级别升级）" message bypassing the cooldown; a recovery notice is sent when the issue clears; configurable in the `alerts` section of `config.yml`
- **Webhook Push** (new in v1.2) —— Alerts / recoveries / daily summaries can be pushed to a Discord webhook or QQ OneBot, so the owner is reachable even when offline; sent fully asynchronously, failures are logged without blocking
- **Daily Summary** (new in v1.2) —— A 3-line digest at a fixed time each day: lowest TPS (with time) / peak entities & heap / alert statistics; if the server happens to be down at the configured time, one catch-up summary is sent after startup
- **History Summary** (new in v1.2) —— `/perfscope history` shows the last ~24 hours: coverage, lowest TPS with its timestamp, peak entities, peak heap — answering "when did it start getting worse"
- **Hotspot Localization** (new in v1.2) —— `/perfscope hotspot` lists the top-3 entity-density chunks server-wide with coordinates (and dominant type share), drilling from "many entities" down to "go look at this spot"; coordinates are click-to-copy
- **Color-Coded UI** —— Rendered via Paper Adventure API, key metrics colored by level (green/yellow/red) based on the `config.yml` alert thresholds; display and alerts share one source of truth, and colors follow immediately after a config reload

## Target Platform & Compatibility

Since v1.1, PerfScope focuses on the **Paper family** of servers and relies heavily on Paper-native features such as the Adventure API:

- **Supported**: Paper 1.20.4+ and its downstream forks (Purpur / Pufferfish / Leaves, etc.)
  — **Folia / Purpur / Leaf / Leaves verified on real servers** (full command surface + history tested on 1.21.4, 49 assertions each)
- **Folia**: supported since v1.1 — adapted through a scheduler layer via `GlobalRegionScheduler` / `AsyncScheduler`, with `folia-supported: true` declared in `plugin.yml` (global and JVM-level metrics are complete; entity/chunk metrics are best-effort)
- **Not supported**: plain Spigot / CraftBukkit

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
| `/perfscope gc` | GC statistics (per-collector details) |
| `/perfscope report [format]` | Export performance report (html/txt/json) |
| `/perfscope monitor` | Toggle ActionBar live monitor |
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

> **Download**: [GitHub Releases](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/latest) (latest stable **v1.1.0**, `PerfScope-1.1.0.jar`)

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

## Performance & Overhead (Self-Reported)

A performance plugin that cannot explain its own overhead is self-contradictory. The design principle here is **keep heavy work off the main thread**:

- **Async JVM metrics** — memory/CPU/GC/runtime info is read from MXBeans on an async thread and published as a whole snapshot for the main thread to reuse; zero main-thread blocking;
- **Main thread does lightweight Bukkit reads only** — TPS/MSPT/players every cycle; entities/chunks every 5 cycles, tile entities every 10, hotspot chunks every 30 (all configurable), reusing caches in between;
- **Guarded player-layer pushes** — the per-second ActionBar push only runs while a player has monitor on, and skipped (not crashed) the moment they disconnect.

**Measured (JDK Flight Recorder, `settings=profile`, 90-second window, Paper 1.21.4, monitor active + normal collection cadence)**:

| Metric | Measured |
|---|---|
| Samples containing a `cn.perfscope` frame | **~2.5%** |
| Of those, on async threads (JVM metrics) | 36/110 frames |
| Main-thread chunk sweeps hit | 1 sample in 90s |

Reproduce (JFR-sample a running server and count frames): `tools/perfscope-jfr-overhead.ps1`

> Caveat: the numbers above are a **magnitude reference** from one shared dev machine (light load, 90-second window), not a performance guarantee. A full high-load baseline (P99 at 5000 entities / 2000 chunks) is still being built. If you observe abnormal overhead, report it with a spark profiler link.

## Boundaries & What This Plugin Does Not Do

An honest scope statement — the following are things this plugin does **not** do; pair it with other tools as needed:

1. **No root-cause profiling** —— It narrows down *which* metric is abnormal (a suspect range), but does not produce method-level flame graphs; use `/spark profiler` to actually locate the cause of lag.
2. **No persistent history** —— History lives only in an in-memory ring buffer (~24 hours, queryable via `/perfscope history`) and **is cleared on restart**; nothing is written to disk, and there are no weekly/monthly trends. For long-term retention, export reports periodically or hook up an external monitoring stack.
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