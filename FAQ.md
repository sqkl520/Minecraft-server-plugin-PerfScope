# FAQ

English | [简体中文](#常见问题faq)

Answers to what people actually ask before installing PerfScope. Every answer here was checked
against the code, not written from memory — where a number is a measurement, it says so; where it is
a design decision, it says that instead.

---

## Will it slow my server down?

**Yes, it costs something — and you can read the exact cost on your own server instead of trusting
a marketing number.**

PerfScope runs one lightweight collection per interval (default `general.update_interval: 20` ticks
= 1 second). Expensive work runs at a fraction of that rate:

| Cadence | What runs | Thread |
|---|---|---|
| Every 1 s | TPS / MSPT / player count | Main thread (read-only Bukkit API) |
| Every 5 s | Entity count, chunk count | Main thread — `world.getLoadedChunks()` allocates |
| Every 10 s | Tile entity count | Main thread |
| Every 30 s | Full sweep of every loaded chunk (hotspot localization) | Main thread — **the heaviest path** |
| Every 1 s | Memory / CPU / GC / threads | **Async** (MXBean reads) |
| Every 60 s | History sample enqueued → CSV written | Enqueue on main thread (non-blocking), write on a daemon thread |

The ActionBar live monitor (`/perfscope monitor`) is **off by default**; it only pushes once per
second while you have it on.

### How to see the cost on your own server

Run:

```
/perfscope status
```

The `Collection cost:` line reports the **p50 / p95 / max** main-thread collection duration in
milliseconds over the samples collected so far, next to the sample count. The shape of the line is:

```
Collection cost: p50 0ms / p95 1ms / max 12ms (8214 samples, main thread)
```

> The numbers above are an illustration of the format, not a measurement from your server. Run the
> command to get your own — they depend on your chunk count, entity count and world count.

Read it this way:

- **p50** is the normal case — what collection costs most seconds.
- **p95 / max** are the collision frames — the moments when the hotspot sweep, the chunk array
  allocation and a busy tick land together. A single 12 ms max does **not** mean every second costs
  12 ms, which is exactly why the plugin reports three numbers instead of one.
- If any cycle exceeds one tick (50 ms), the line turns red and reports **how many cycles** did, with
  the warning that the collector is eating into tick budget.

One tick is 50 ms. A 1.20 TPS target leaves about 50 ms per tick for everything.

### Turning the expensive parts off

Each of these is independent, and turning one off does not break the others:

```yaml
hotspots:
  enabled: false      # removes the heaviest path (the 30s full chunk sweep)

history:
  enabled: false      # removes the 60s enqueue; /perfscope history and /trend will have no data

tile_entities:
  enabled: false      # removes the 10s sweep

entities:
  enabled: false      # removes the 5s entity sweep
chunks:
  enabled: false      # removes the 5s chunk sweep
```

Modules you disable show as `Disabled (config ...=false)` in `/perfscope status` — they do not
silently report zeros.

### What the README's overhead figure means

The README has a JFR measurement **and a list of everything that measurement cannot tell you** —
including that the tested build was not the release build and that the main-thread sample count was
too small to support a conclusion. Read it in that spirit. It is a magnitude reference from one
shared dev machine, not a performance guarantee for your server.

---

## Does it collect my data?

**There is no PerfScope backend, no account, and no cloud service.** Everything is computed on your
server.

There are exactly **two** possible outbound connections:

1. **bStats anonymous statistics — on by default.** Two layers, with different granularity:
   - **What this plugin registers** (bucketed / enumerated): server software, which modules you
     enabled, UI language, webhook **channel type** (never the URL), magnitude buckets for entity
     count / loaded chunks / peak players / max heap, world count, plugin count.
   - **What the bStats library itself sends** — PerfScope cannot bucket, suppress or add to this:
     plugin version, **exact online player count (not bucketed)**, online-mode flag, server API
     version, Java version, OS name / arch / version, CPU core count, and a randomly generated
     server UUID stored in `plugins/bStats/config.yml`.
   - **Neither layer sends** player names, player UUIDs, IPs, world names, plugin names, config
     contents, alert text, or webhook URLs.
2. **Webhook push — off by default.** Nothing is sent unless you enable it, and then it goes only to
   the URL you configure.

**To turn telemetry off**, either of these works and neither changes plugin behaviour:

```yaml
# plugins/PerfScope/config.yml
general:
  bstats-enabled: false
```

```yaml
# plugins/bStats/config.yml — applies to every plugin on the server
enabled: false
```

The field-by-field disclosure, including the exact bStats baseline payload, is in
[SECURITY.md](SECURITY.md). If you want a second opinion, the outbound code is grep-able: every
network call in `src/main/java/` lives in `alert/WebhookNotifier.java`; bStats does its own HTTP
inside the shaded library.

---

## Does it support Folia?

**It runs, but a subset of metrics is unavailable — by design, not by configuration mistake.**

| Capability | On Folia |
|---|---|
| Commands and configuration | ✅ Complete |
| Memory / CPU / GC / threads (JVM level) | ✅ Complete |
| **MSPT** | ❌ **Unavailable.** Folia has no global tick timing; `getTickTimes()` returns all zeros |
| **TPS** | ⚠️ **Global-region-local value only**, not server-wide throughput. Output is tagged `(global region)` |
| **Entities / chunks / tile entities / hotspot chunks** | ❌ **Four modules skipped automatically.** `/perfscope status` reports "Platform limitation" |

Why the four modules are skipped rather than approximated: their data sources are cross-region
(`World.getLoadedChunks()`, `World.getEntities()`, `Chunk.getEntities()`, `Chunk.getTileEntities()`,
`World.getTileEntityCount()`). Folia assigns that state to individual region threads, and Bukkit has
no thread-safe "snapshot the whole server" API. Called from the global region thread, the result is
either only that region's slice (a number that is too small and jumps between samples) or an
`IllegalStateException`. Reporting a fraction of the server as if it were the whole would be worse
than reporting nothing.

So on Folia, PerfScope is a useful JVM probe and a per-region TPS reader, but it is **not** a
complete TPS/MSPT monitor. TPS being lower than what players feel is expected: the global region only
runs global logic and chunks belonging to no region.

Pufferfish (a Paper fork) should be compatible through the same code path, but it has **never been
run on Pufferfish** — treat it as unverified, not supported.

---

## Does it clash with spark or Plan?

**No. They are complementary, and the boundary is deliberate.**

- **spark** does method-level flame graphs and answers *which line of code* is slow. PerfScope does
  not attempt that — the README tells you to use `/spark profiler` every time the topic comes up.
  Conversely, spark will not sit in-game watching your thresholds and tell you when they break.
- **Plan** does player-behaviour analytics and long-range reporting. PerfScope's history is one
  server, daily CSV, 7-day retention by default, and it explicitly does not replace
  Prometheus/Grafana or do multi-server aggregation.
- **Running all three together is fine.** PerfScope registers exactly **one** event listener
  (`PlayerQuitEvent`, to clear monitor state), does not change game behaviour, and never writes to a
  world.
- The one real-world hazard with any metric plugin is bundled libraries: when several plugins each
  shade their own bStats, class clashes can occur. PerfScope relocates bStats to
  `cn.perfscope.shaded.bstats`, so it will not collide with another plugin's copy.

---

## There are so many config options — do I have to tune them all?

**No. It works out of the box. Do not change a single key on day one.**

If you only do one thing: run it for a few days with defaults, then look at what actually fires in
`/perfscope alerts`, and tune only that.

### Minimal config to start with

This is the whole file as far as a new install is concerned:

```yaml
general:
  update_interval: 20     # ticks between collections; 20 = 1s (clamped to 5-1200)
  language: en            # en (default) or zh; takes effect on /perfscope reload
  bstats-enabled: true    # set false to opt out of anonymous statistics
```

Everything else ships with a working default. The comments in `plugins/PerfScope/config.yml` are
more detailed than this FAQ — read that file, not a wiki page.

### Three mistakes that are easy to make

1. **TPS is a low-is-bad metric.** `tps.warning_threshold` must be **≥** `tps.critical_threshold` —
   the opposite of MSPT and memory. Invert it and `/perfscope reload` warns and falls back to the
   defaults rather than taking effect silently. A TPS threshold above 20.5 is rejected outright,
   since steady-state TPS cannot exceed 20.0 and such a rule would match a healthy server.
2. **`alerts.debounce-cycles` is measured in collection cycles, not minutes.** Default 3 ≈ 3
   seconds. To suppress one-minute jitter, set it to 60 or more — that aligns with the 1-minute TPS
   averaging window, which is where the meaning comes from.
3. **If alerts are too noisy, check the flap controls before lowering thresholds.**
   `alerts.flap-window-seconds` (default 180), `alerts.repush-delta` (default 8) and
   `alerts.cooldown-seconds` (default 300) exist specifically for threshold-edge flapping. In a
   measured six-hour run on a server sitting near the CPU threshold, the alert stream went from
   ~1333 messages to single digits after the flap controls were introduced. Raising thresholds to
   silence noise usually just hides a real problem.

---

## Why does my server show "Unavailable"?

Because "Unavailable" covers **four different situations** on purpose, and `/perfscope status` is
what tells them apart. Never read a bare "Unavailable" as "the plugin is broken".

Run `/perfscope status` and read the `--- Module Status ---` section. Each module is in exactly one
of these states:

| State shown | Meaning | What to do |
|---|---|---|
| `Disabled (config entities.enabled=false)` | **You** turned it off in `config.yml` | Set it back to `true` and `/perfscope reload` |
| `Platform limitation (...)` | **The platform cannot provide it** — on Folia, entities / chunks / tile entities / hotspots | Nothing. This is correct behaviour; see the Folia answer above |
| `Collection failed (N in a row)` | The module threw an exception N collection cycles in a row | Check the console for the stack trace; often a fork-specific API difference |
| `Warming up (first collection not ready)` | Normal for the first second after start or reload | Wait ~1 s; it clears itself |

The distinction matters: a Folia server showing "Platform limitation" is healthy, while being told
"you disabled this in config" when you did not would send you looking in the wrong place. That is
why the plugin reports them separately instead of collapsing everything into one grey word.

Two more states worth knowing:

- **The whole collection is stalled.** The `Collection:` line says `Stopped updating N ago` and warns
  that every other subcommand is showing stale data. That warning is literal — do not diagnose the
  server from those numbers. `/perfscope reload` rebuilds the collection task; restart if that fails.
- **Only the JVM half is stalled.** The `JVM sampling:` line goes red when the async MXBean thread
  died while the main-thread tick keeps producing snapshots. Memory/CPU/GC then freeze at their last
  values while TPS still updates — the one case where a "mostly working" panel is lying about
  memory.

---

## Common follow-ups

**Do I need to install anything else?** No. Paper 1.20.4+ and Java 17. If the JAR is on a non-Paper
platform, the plugin disables itself with a clear console message — that is the platform guard
working, not a crash.

**Where do exports go?** `plugins/PerfScope/reports/`. HTML reports include a TPS trend chart, entity
distribution, memory bar and alert highlights. Retention is 7 days by default; review a report before
sharing it, since it lists world names and entity counts.

**Does it work with a proxy network?** PerfScope monitors one server process. It does not aggregate
across a network — install it on each backend and compare, or use a real monitoring stack.

**Can I translate it?** `general.language` accepts `en` (default) and `zh`; both are fully covered
and switching is all-or-nothing, never half-translated. Message overrides go in
`plugins/PerfScope/messages.yml`.

---

# 常见问题（FAQ）

[English](#faq) | 简体中文

这里回答的是"装之前真的会问"的问题。下面每条都与代码核对过，不是凭记忆写的——凡是实测数字都会
说明是实测，凡是设计决定都会说明是设计决定。

---

## 会不会拖慢我的服务器？

**会，有开销——而且你可以在自己的服务器上读到确切开销，不用相信任何宣传数字。**

PerfScope 每个采集周期做一次轻量采集（默认 `general.update_interval: 20` tick = 1 秒），重活按
倍数降频：

| 频率 | 做什么 | 线程 |
|---|---|---|
| 每 1 秒 | TPS / MSPT / 在线玩家数 | 主线程（只读 Bukkit API） |
| 每 5 秒 | 实体数、区块数 | 主线程——`world.getLoadedChunks()` 会分配数组 |
| 每 10 秒 | 方块实体数 | 主线程 |
| 每 30 秒 | 全服已加载区块全遍历（热点定位） | 主线程——**最重的一段** |
| 每 1 秒 | 内存 / CPU / GC / 线程数 | **异步**（读 MXBean） |
| 每 60 秒 | 历史采样入队 → 写 CSV | 入队在主线程（非阻塞），写盘在守护线程 |

ActionBar 实时监测（`/perfscope monitor`）**默认关闭**，只有你开启后才每秒推送一次。

### 怎么在自己服务器上看到开销

执行：

```
/perfscope status
```

`采集耗时:` 行会给出目前样本下**主线程采集耗时的 p50 / p95 / max**（毫秒）与样本数，形如：

```
采集耗时: p50 0ms / p95 1ms / max 12ms（8214 个样本，主线程）
```

> 上面这行数字只是格式示意，不是你服务器上的实测值。请自己执行命令取——结果取决于你的区块数、
> 实体数与世界数。

这样读：

- **p50** 是常态——大多数秒里采集花多少时间；
- **p95 / max** 是撞车拍——热点全遍历、区块数组分配与忙碌 tick 撞在一起的那几次。一个 12ms 的
  max **不**等于每秒都花 12ms，这正是插件给三个数而不是一个数的原因；
- 只要有周期超过 1 tick（50ms），该行会变红并报出**有几个周期**超标，提示采集器正在占用 tick 预算。

1 tick = 50ms。要维持 20 TPS，每 tick 大约有 50ms 可供所有事情共用。

### 关掉最贵的部分

下面每一项都独立生效，关掉任何一个不影响其它功能：

```yaml
hotspots:
  enabled: false      # 去掉最重的一段（每 30 秒全服区块全遍历）

history:
  enabled: false      # 去掉每 60 秒入队；/perfscope history 与 /trend 将无数据

tile_entities:
  enabled: false      # 去掉每 10 秒的遍历

entities:
  enabled: false      # 去掉每 5 秒的实体遍历
chunks:
  enabled: false      # 去掉每 5 秒的区块遍历
```

被关掉的模块在 `/perfscope status` 里显示为 `已禁用（config ...=false）`，不会静默报 0。

### README 里那个开销数字怎么理解

README 里有一份 JFR 实测，**以及一整串"这个数字不能说明什么"**——包括被测构建不是发布构建、
主线程样本量不足以支撑结论。请按那个口径理解：它是一台共享开发机上的量级参考，不是你服务器的
性能保证。

---

## 会不会收集我的数据？

**没有 PerfScope 的后端、没有账号、没有云服务。** 一切都在你的服务器上算完。

只有**两条**可能的出站连接：

1. **bStats 匿名统计——默认开启。** 分两层，粒度不同：
   - **本插件注册的图表**（已分档 / 枚举）：服务端类型、启用了哪些模块、界面语言、webhook
     **通道类型**（绝不含 URL）、实体数/加载区块/峰值玩家/堆上限的**量级档位**、世界数、插件数。
   - **bStats 库自己发的内容**，本插件无法分档、屏蔽或追加：插件版本、**精确在线玩家数（未分档）**、
     正版模式标记、服务端 API 版本、Java 版本、操作系统名/架构/版本、CPU 核心数，以及一个随机生成、
     存在 `plugins/bStats/config.yml` 的服务端 UUID。
   - **两层都不发**：玩家名、玩家 UUID、IP、世界名、插件名、配置内容、告警文本、webhook URL。
2. **Webhook 推送——默认关闭。** 你不开启就不发；开启后只发往你自己配置的 URL。

**关闭遥测**：下面任一方式即可，都不改变插件行为：

```yaml
# plugins/PerfScope/config.yml
general:
  bstats-enabled: false
```

```yaml
# plugins/bStats/config.yml —— 对全服所有插件生效
enabled: false
```

逐字段披露（含 bStats 基线负载的确切字段）见 [SECURITY.md](SECURITY.md)。想做二次核验也可以：
`src/main/java/` 下所有网络调用都在 `alert/WebhookNotifier.java`，bStats 的 HTTP 在 shade 后的库里。

---

## 支持 Folia 吗？

**能跑，但有一部分指标拿不到——这是设计决定，不是配置错了。**

| 能力 | Folia 上 |
|---|---|
| 命令与配置 | ✅ 完整 |
| 内存 / CPU / GC / 线程（JVM 级） | ✅ 完整 |
| **MSPT** | ❌ **不可用**。Folia 无全局 tick 时间，`getTickTimes()` 恒返回全零 |
| **TPS** | ⚠️ **仅 global region 局部值**，不是全服吞吐；输出带 `(global region)` 标注 |
| **实体 / 区块 / 方块实体 / 热点区块** | ❌ **四个模块自动跳过**；`/perfscope status` 显示"Platform limitation" |

为什么是跳过而不是估算：这四个模块的数据源都是跨 region 的（`World.getLoadedChunks()`、
`World.getEntities()`、`Chunk.getEntities()`、`Chunk.getTileEntities()`、`World.getTileEntityCount()`）。
Folia 把这些状态的归属拆到各 region 线程，Bukkit 层没有"一次性拿全服快照"的线程安全 API。在 global
region 线程上调用，要么只拿到该 region 的那一片（数字偏小、且两次采样之间会跳），要么直接抛
`IllegalStateException`。把一部分 region 的数字当成全服报出去，比什么都不报更糟。

所以在 Folia 上，PerfScope 是一个有用的 JVM 探针与按 region 的 TPS 读数器，但**不是**完整的
TPS/MSPT 监测器。TPS 比玩家体感低是正常的：global region 只跑全局逻辑与不属于任何 region 的区块。

Pufferfish（Paper 分支）走同一条代码路径，理论上兼容，但**从未在 Pufferfish 上跑过**——请当作未验证，
而不是"支持"。

---

## 和 spark / Plan 冲突吗？

**不冲突，而且是互补关系，边界是刻意划的。**

- **spark** 做方法级火焰图，回答*哪一行代码*慢。PerfScope 不做这件事——README 每次提到这个话题都
  会说"深挖请用 `/spark profiler`"。反过来说，spark 不会在游戏里持续盯着你的阈值、越界时喊你。
- **Plan** 做玩家行为分析与长期报表。PerfScope 的历史是单服、按天 CSV、默认保留 7 天，且明确不做
  多服聚合、不替代 Prometheus/Grafana。
- **三者同时装没问题。** PerfScope 只注册**一个**事件监听器（`PlayerQuitEvent`，用于清理 monitor
  状态），不改变游戏行为，也从不写世界。
- 任何指标类插件在真实环境里唯一容易踩的坑是打包库：多个插件各自 shade 一份 bStats 可能类冲突。
  PerfScope 已把 bStats relocate 到 `cn.perfscope.shaded.bstats`，不会与别的那份打架。

---

## 配置项太多了，必须全调吗？

**不用。开箱即用，第一天一个键都不要改。**

如果你只想做一件事：用默认配置跑几天，然后看 `/perfscope alerts` 里到底什么在响，只调那一项。

### 最小可用配置

对新安装来说，整个文件就这几行是相关的：

```yaml
general:
  update_interval: 20     # 采集间隔（tick），20 = 1 秒（钳制在 5-1200）
  language: en            # en（默认）或 zh；改后 /perfscope reload 生效
  bstats-enabled: true    # 设为 false 可退出匿名统计
```

其余全部带可用默认值。`plugins/PerfScope/config.yml` 里的注释比本 FAQ 更细——读那个文件，不要读 wiki。

### 三个容易踩的坑

1. **TPS 是"越低越差"的指标。** `tps.warning_threshold` 必须 **≥** `tps.critical_threshold`，
   与 MSPT、内存相反。配反了 `/perfscope reload` 会告警并回退默认值，不会静默生效。TPS 阈值超过
   20.5 会被直接拒绝——稳态 TPS 不可能超过 20.0，这样的规则在健康服务器上恒真。
2. **`alerts.debounce-cycles` 的单位是"采集周期"，不是分钟。** 默认 3 ≈ 3 秒。想压掉 1 分钟的抖动
   请设到 60 以上——那才和 1 分钟 TPS 均值窗口对齐，这个数的意义就来自那里。
3. **告警太吵时，先看抖动抑制，别急着降阈值。** `alerts.flap-window-seconds`（默认 180）、
   `alerts.repush-delta`（默认 8）、`alerts.cooldown-seconds`（默认 300）就是为阈值边界锯齿准备的。
   在一台 CPU 长期贴着阈值的服务器上实测 6 小时，引入抖动抑制后告警从约 1333 条压到个位数。
   为了止噪而抬高阈值，通常只是把真问题藏起来。

---

## 为什么我的服显示"不可用"？

因为"不可用"**故意**覆盖了**四种不同情况**，而 `/perfscope status` 就是用来区分它们的。不要把光秃秃的
"不可用"读成"插件坏了"。

执行 `/perfscope status`，看 `--- 模块状态 ---` 段。每个模块必定处于以下之一：

| 显示状态 | 含义 | 怎么办 |
|---|---|---|
| `已禁用（config entities.enabled=false）` | **你**在 `config.yml` 里关掉了 | 改回 `true` 后 `/perfscope reload` |
| `Platform limitation (...)` | **平台拿不到**——Folia 上的实体/区块/方块实体/热点 | 不用管。这是正确行为，见上方 Folia 一节 |
| `采集失败（连续 N 次）` | 该模块连续 N 个采集周期抛异常 | 看控制台堆栈；常见于分支服务端的 API 差异 |
| `预热中（首轮采集未就绪）` | 启动或重载后的第一秒属正常 | 等约 1 秒，会自行消失 |

这个区分很重要：Folia 上显示"Platform limitation"是健康的；而如果你并没有关过某个模块，却被告知
"你在配置里关掉了它"，那会把你引向完全错误的方向。这就是插件把它们分开报、而不是统一压成一个灰词
的原因。

还有两个状态值得知道：

- **整个采集停摆**：`采集:` 行会说 `已停止更新 N 前`，并警告其它子命令显示的都是旧数据。那句警告是
  字面意思——不要用那些数字去判断服务器现状。`/perfscope reload` 会重建采集任务；仍无效请重启。
- **只有 JVM 那一半停摆**：`JVM 采集:` 行变红，表示异步 MXBean 线程死了而主线程节拍仍在产出快照。
  此时内存/CPU/GC 冻结在最后一次的值、而 TPS 仍在更新——这是唯一一种"看起来大多正常，但内存数字
  在说谎"的情况。

---

## 其它常见追问

**还需要装别的东西吗？** 不需要。Paper 1.20.4+ 与 Java 17。如果 JAR 被放到非 Paper 平台，插件会打印
清晰的中英双语说明并禁用自己——那是平台守卫在工作，不是崩溃。

**导出的报告在哪？** `plugins/PerfScope/reports/`。HTML 报告含 TPS 趋势图、实体分布、内存进度条与
告警高亮。默认保留 7 天；分享前请自查，因为它会列出世界名与实体计数。

**支持群组服（代理网络）吗？** PerfScope 监测的是单个服务端进程，不做跨服聚合——请每个后端各装一份
对比，或接入真正的监控栈。

**能翻译吗？** `general.language` 接受 `en`（默认）与 `zh`，两种语言均完整覆盖，切换是整份生效、
不会出现半译。文案覆盖写在 `plugins/PerfScope/messages.yml`。
