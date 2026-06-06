English | [简体中文](README.md)

# PerfScope —— Server Performance Monitor

A comprehensive performance monitoring plugin for Minecraft servers, focused on server-side real-time performance tracking and diagnostics. Supports in-game commands for live data viewing, per-world independent alerts, and automatic performance report generation.

## Features

- **Full Diagnostic** —— Type `/perfscope` to view all core performance metrics in one place, one-stop diagnostics
- **TPS Monitoring** —— 1m/5m/15m real-time collection, with customizable warning/critical alert thresholds
- **MSPT Monitoring** —— avg/min/max three-metric collection via Paper API reflection for TickTimes data, theoretical max TPS calculation
- **Player Statistics** —— Online/max/per-world distribution/ping, with capacity alerts
- **Entity Statistics** —— Total/per-world/by-type Top15, each world can have independent alert thresholds
- **Chunk Statistics** —— Loaded chunks/force-loaded chunks/per-world, with independent alerts
- **Memory Diagnostics** —— Heap used/max/usage%/allocated, non-heap memory, GC count/time/per-collector details
- **CPU Diagnostics** —— Process CPU usage/system CPU load/available processors/active threads
- **Redstone Activity Tracking** —— Paper server redstone wire active count
- **Tile Entity Statistics** —— Total/per-world distribution
- **Network Throughput** —— Bytes sent/received per second, with traffic alerts
- **Server Info** —— Uptime/thread count/plugin count/world count/JVM version
- **Highly Customizable Config** —— All module alert thresholds, collection intervals, entity tracking types configurable
- **ActionBar Live Monitor** —— `/perfscope monitor` to toggle, refreshes core metrics every second on screen top
- **Performance Report Export** —— HTML/TXT/JSON formats, HTML reports include TPS trend chart, entity distribution table, memory progress bar, alert highlights
- **Auto Report Generation** —— Configurable interval, async generation without blocking main thread, auto cleanup by retention days
- **Per-World Independent Alerts** —— Override entity alert thresholds per world
- **Color-Coded UI** —— Rendered via Paper Adventure API, TPS/MSPT/memory auto-color by threshold (green/yellow/red)

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
| `/perfscope redstone` | Redstone activity |
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

## Installation

1. Download the latest JAR file
2. Place it in your server's `plugins/` directory
3. Restart the server or use `/plugman load PerfScope`
4. Edit `plugins/PerfScope/config.yml` for custom configuration
5. Use `/perfscope` to view performance data

## Configuration

Key configuration options (`config.yml`):

```yaml
general:
  update_interval: 20       # Data collection interval (ticks)
  language: zh_cn
  debug: false

tps:
  enabled: true
  warning_threshold: 18.0   # TPS warning threshold
  critical_threshold: 15.0

mspt:
  enabled: true
  warning_threshold: 45.0   # MSPT warning threshold (ms)
  critical_threshold: 50.0

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

report:
  auto_export: false
  auto_export_interval: 30  # Auto export interval (minutes)
  format: html
  retention_days: 7         # Report retention days

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

## All Monitorable Metrics

| Category | Metrics | Description |
|------|------|------|
| TPS | 1m/5m/15m average TPS | Core server health indicator |
| MSPT | avg/min/max MSPT | Per-tick duration (ms), theoretical max TPS |
| Players | online/max/per-world/ping | Player load |
| Entities | total/per-world/by-type Top15 | Entity pressure analysis |
| Chunks | loaded/force-loaded/per-world | Chunk load |
| Memory | heap used/max/usage%/allocated/non-heap | JVM memory status |
| CPU | process CPU/system CPU/processors | Processor load |
| GC | count/time/per-collector | Garbage collection stats |
| Redstone | active redstone wire count | Redstone activity |
| Tile Entity | total/per-world | Block entity load |
| Network | bytes sent/received per second | Network throughput |
| Server | uptime/threads/plugins/worlds/JVM | Server basic info |

## Dependencies

- Paper 1.20.4+
- Java 17+

## Build

```bash
mvn clean package
```

The JAR file will be generated in the `target/` directory.

## License

MIT License