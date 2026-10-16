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
- **Threshold Alert Push** (new in v1.1) —— When an alert fires, pushes to the console + online players holding the `perfscope.alert` permission; the same alert has a default 300-second cooldown to prevent spam; a recovery notice is sent when the issue clears; configurable in the `alerts` section of `config.yml`
- **Color-Coded UI** —— Rendered via Paper Adventure API, key metrics colored by level (green/yellow/red) based on the `config.yml` alert thresholds; display and alerts share one source of truth, and colors follow immediately after a config reload

## Target Platform & Compatibility

Since v1.1, PerfScope focuses on the **Paper family** of servers and relies heavily on Paper-native features such as the Adventure API:

- **Supported**: Paper 1.20.4+ and its downstream forks (Purpur / Pufferfish / Leaves, etc.)
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
  warning_threshold: 80     # Player-count warning threshold (% of max players)
  critical_threshold: 95

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
  heap_warning_threshold: 80
  heap_critical_threshold: 90

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

## Boundaries & What This Plugin Does Not Do

An honest scope statement — the following are things this plugin does **not** do; pair it with other tools as needed:

1. **No root-cause profiling** —— It narrows down *which* metric is abnormal (a suspect range), but does not produce method-level flame graphs; use `/spark profiler` to actually locate the cause of lag.
2. **No historical persistence** —— The current version keeps only in-memory real-time snapshots and a limited sampling window; nothing is written to disk for trend analysis. Questions like "was the server laggy last night" or week-over-week trends cannot be answered.
3. **Limited MSPT spike-detection window** —— Values come from Paper `getTickTimes()`, a rolling window (roughly the last 100 ticks); a spike that already scrolled out of the window may not be reflected, so "did it just lag?" is not always answerable.
4. **No automatic remediation** —— The plugin only observes, alerts and exports reports; it never performs any intervention on its own (no restarts, no entity cleanup, no config changes).

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