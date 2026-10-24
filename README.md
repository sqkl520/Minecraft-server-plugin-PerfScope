[English](README-EN.md) | 简体中文

# PerfScope —— 服务器性能监测插件

[![Build](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/actions/workflows/build.yml/badge.svg)](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/sqkl520/Minecraft-server-plugin-PerfScope)](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/latest)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

一个面向 Minecraft 服务器的全面性能监测插件，专注服务端层面的实时性能追踪与诊断。支持游戏内指令查看实时数据、按世界独立告警、以及自动生成性能报告。

## 功能特性

- **全量诊断** —— 输入 `/perfscope` 即可查看所有核心性能指标，一站式诊断
- **TPS 监测** —— 1m/5m/15m 实时采集，支持 warning/critical 两级告警阈值自定义
- **MSPT 监测** —— avg/min/max 三指标采集，通过 Paper `CraftServer.getTickTimes()` 获取精确 tick 耗时，不可用时依次降级 `getAverageTickTime` / TPS 反推（仅最后一级为估算），附理论最大 TPS 推算
- **玩家统计** —— 在线数/最大数/在线列表（世界+Ping），支持容量告警
- **实体统计** —— 总数/按世界/按类型 Top15；实体总数告警阈值（全服合计），可在 worlds 段按世界覆盖
- **区块统计** —— 加载区块数/强加载区块数/按世界分布，支持独立告警
- **内存诊断** —— 堆内存使用/上限/使用率/已分配，非堆内存使用，GC 次数/耗时/各收集器详情
- **CPU 诊断** —— 进程 CPU 使用率/系统 CPU 负载/可用处理器数/活跃线程数
- **Tile Entity 统计** —— 总数/按世界分布
- **服务器运行信息** —— uptime/线程数/已加载插件数/已加载世界数/JVM 版本
- **高度自定义配置** —— 所有模块的告警阈值均可配置
- **ActionBar 实时监测** —— `/perfscope monitor` 切换，每秒刷新核心指标到 ActionBar（动作栏，屏幕底部物品栏上方）。定位为**诊断探针**：排查卡顿或事故处置时开启几十秒观察实时波动，看完即关；并非设计为 7×24 常驻监测面板
- **性能报告导出** —— 支持 HTML/TXT/JSON 三种格式，HTML 报告含 TPS 趋势图、实体分布表、内存进度条、告警高亮
- **自动报告生成** —— 可配置间隔，异步生成不阻塞主线程，旧报告自动按保留天数清理
- **按世界独立告警** —— 各世界可覆盖实体告警阈值
- **阈值告警主动推送**（v1.1 新增，v1.2 收敛） —— 告警触发时推送控制台 + 在线持有 `perfscope.alert` 权限的玩家；默认需连续 3 个采集周期超标才首次推送（防抖，可配）；同一告警默认 300 秒冷却防刷屏；级别升级（警告→严重）只推一条"（级别升级）"消息、不受冷却限制；故障恢复时发送恢复通知；可在 `config.yml` 的 `alerts` 段配置
- **Webhook 推送**（v1.2 新增） —— 告警 / 恢复 / 每日摘要可推送到 Discord webhook 或 QQ OneBot，服主不在线也能收到；全异步发送，失败只记日志不阻塞
- **每日摘要**（v1.2 新增） —— 每天固定时刻推送 3 行概况：最低 TPS（含时刻）/ 峰值实体与内存 / 告警统计；若配置时刻服务端恰好停机，启动后会自动补发当天一次
- **历史摘要**（v1.2 新增） —— `/perfscope history` 查看近 24 小时概况：覆盖时长、最低 TPS 与发生时刻、峰值实体、峰值内存，回答"什么时候开始变差的"
- **热点区块定位**（v1.2 新增） —— `/perfscope hotspot` 列出全服实体密度 Top3 区块与坐标（含主导类型占比），把"实体多"下钻到"去哪个坐标看看"；坐标可点击复制
- **彩色 UI 面板** —— 使用 Paper Adventure API 渲染，关键指标按 `config.yml` 告警阈值分级着色（绿/黄/红）；阈值来源与告警**同配置、同判定入口**，改配置 reload 后颜色立即跟随。注意：显示用闭区间（达到阈值即变色），部分指标告警用严格大于，因此**恰好等于阈值的那一瞬颜色会先变、告警随后到达**（有意的早预警语义）

## 目标平台与兼容性

PerfScope 自 v1.1 起专注 **Paper 系**服务端，深度依赖 Paper 原生 Adventure API 等特性。

> 先说结论：**"能加载 / 命令有响应" ≠ "数值正确"**。下表区分这两件事，请按下表理解验证强度。

| 平台 | 验证强度 | 说明 |
|------|----------|------|
| **Paper 1.21.x** | **已实机验证** | 全部命令面 + 阈值告警 + 历史持久化 + 诊断引擎在真实服务端跑通 |
| **Paper 1.20.4+** | **编译期兼容** | 以 `paper-api 1.20.4` 编译（`pom.xml`），**尚未在 1.20.x 实机启动验证**；1.20.4 以下无保证 |
| **Purpur / Leaf / Leaves** | **可加载验证** | 同一份 Paper 代码路径能启动、命令可响应；**分支私有优化（AI 限速、实体合并、区块缓存等）未覆盖**，可能影响实体/区块计数的绝对准确性 |
| **Folia** | **可加载，指标受限** | 能启动并响应命令，但 **MSPT 不可用**（无全局 tick 时间），**TPS 为 global region 局部值**（不是全服吞吐）；实体/区块/热点在跨 region 场景下可能显示"不可用" |
| **纯 Spigot / CraftBukkit** | 不支持 | 依赖 Adventure 渲染，无法在无 Paper API 的服务端运行 |

补充说明：

- **兼容矩阵的边界**：项目内的 196 条兼容断言验证的是**加载与命令可达性**（命令能执行、输出含预期标题/字段），
  **不是数值正确性**——断言均为标签子串匹配，不校验读到的 TPS/MSPT/实体数是否真值。
- **Paper 1.21.x 上的 MSPT 口径**：走 Paper `getTickTimes()`（一次拿到 avg/min/max）；
  若该实现异常返回全零，插件会在启用后约 5–10 秒（连续两次实测确认，避免瞬态误判）自动降级为
  "不可用"，**不会输出假精确值**。
- **Folia 的 TPS 标注**：所有 TPS 输出追加 `(global region)` 标注。global region 只跑全局逻辑与
  不归属任何 region 的区块，因此该数值**低于**玩家实际体感的全服吞吐属正常现象；空服启动初期
  global region 报表存在空窗期，短时接近 0 的读数同样不代表服务器卡死。
- **Folia 的 MSPT**：`getTickTimes()` 在该平台上方法存在但恒返回全零，插件在启用后即判定
  MSPT 不可用（无需等待实测），`/perfscope mspt` 显示"不可用（Folia 无全局 tick 时间）"。
- **Folia 的调度**：经 `GlobalRegionScheduler` / `AsyncScheduler` 适配，`plugin.yml` 已声明
  `folia-supported: true`；JVM 级指标（内存/CPU/GC/线程）完整可用。

## 命令

| 命令 | 说明 |
|------|------|
| `/perfscope` | 全量诊断（默认） |
| `/perfscope all` | 全量诊断（同无参数） |
| `/perfscope tps` | TPS 专项检测 + 趋势图 |
| `/perfscope mspt` | MSPT 专项检测 + 理论最大 TPS |
| `/perfscope entities [世界]` | 实体统计（按世界/按类型 Top15） |
| `/perfscope chunks [世界]` | 区块统计（加载/强加载） |
| `/perfscope memory` | 内存诊断（堆/非堆/GC/使用率进度条） |
| `/perfscope cpu` | CPU 诊断（进程/系统/线程） |
| `/perfscope players` | 玩家统计（在线数/列表/延迟） |
| `/perfscope world [世界]` | 世界详情（可点击跳转） |
| `/perfscope redstone` | 红石活动（v1.1 已下线） |
| `/perfscope tileentities` | Tile Entity 统计 |
| `/perfscope uptime` | 运行时间 + JVM 信息 |
| `/perfscope history` | 历史摘要（近 24 小时：最低 TPS / 峰值实体 / 峰值内存） |
| `/perfscope hotspot` | 热点区块定位（实体密度 Top3 + 坐标，含主导类型） |
| `/perfscope diagnose` | 智能诊断（规则引擎：结论 + 参数级建议；`diag` 仍是全量诊断别名） |
| `/perfscope trend [天数]` | 趋势摘要（跨重启历史持久化，默认 1 天） |
| `/perfscope gc` | GC 统计（各收集器详情） |
| `/perfscope report [格式]` | 导出性能报告（html/txt/json） |
| `/perfscope monitor` | 切换 ActionBar 实时监测 |
| `/perfscope status` | 插件自身状态（采集是否存活/模块三态/历史/告警） |
| `/perfscope reload` | 重载配置文件 |
| `/perfscope help` | 查看命令帮助 |

别名: `/pscope`, `/perf`

## 全量诊断输出示例

输入 `/perfscope` 后，输出格式如下：

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

## 权限

| 权限节点 | 默认 | 说明 |
|------|------|------|
| `perfscope.use` | OP | 基础使用权限（查看性能数据） |
| `perfscope.admin` | OP | 管理员权限（包含所有子权限） |
| `perfscope.reload` | OP | 重载配置权限 |
| `perfscope.export` | OP | 导出性能报告权限 |
| `perfscope.alert` | OP | 接收游戏内告警推送权限 |

## 安装

> **下载**：[GitHub Releases](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/releases/latest)（最新正式版 **v1.2.1**，`PerfScope-1.2.1.jar`）

1. 下载最新版本的 JAR 文件
2. 放入服务器的 `plugins/` 目录
3. 重启服务器或使用 `/plugman load PerfScope`
4. 编辑 `plugins/PerfScope/config.yml` 进行自定义配置
5. 使用 `/perfscope` 查看性能数据

> 数据披露：本插件内置 bStats 匿名使用统计（默认启用，不收集玩家/服主个人信息）。当前版本作者尚未注册 bStats 项目 ID，**不会发送任何数据**；ID 注册后自动生效。可在 `plugins/bStats/config.yml` 或本插件 config.yml 的 `general.bstats-enabled: false` 关闭。

## 配置

主要配置项（`config.yml`）：

```yaml
general:
  update_interval: 20       # 数据采集间隔 (tick)
  debug: false

tps:
  enabled: true
  warning_threshold: 18.0   # TPS 告警阈值
  critical_threshold: 15.0

mspt:
  enabled: true
  warning_threshold: 45.0   # MSPT 告警阈值 (ms)
  critical_threshold: 50.0

players:
  enabled: true
  warning_threshold: 90     # 玩家数告警阈值 (占最大玩家数百分比)
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
  process_warning_threshold: 80.0   # 进程 CPU 使用率告警阈值 (%)
  system_warning_threshold: 90.0    # 系统 CPU 负载告警阈值 (%)

report:
  auto_export: false
  auto_export_interval: 30  # 自动导出间隔 (分钟)
  format: html
  retention_days: 7         # 报告保留天数

alerts:
  enabled: true             # 总开关 (false = 完全不做告警检查与推送)
  console: true             # 控制台推送
  in-game: true             # 游戏内推送 (在线且持有 perfscope.alert 权限的玩家)
  cooldown-seconds: 300     # 同一告警的冷却秒数 (0 = 无冷却，仍受采集周期节流)
  notify-recovery: true     # 恢复通知 (告警消失时推送恢复消息)
  debounce-cycles: 3        # 防抖：连续 N 个采集周期超标才首次推送 (1 = 首次出现即推)
  daily-summary:
    enabled: false          # 每日摘要开关
    time: "08:00"           # 每日摘要时刻 (本地时区 HH:mm)
  webhook:
    enabled: false          # webhook 推送开关 (告警/恢复/日摘要)
    type: discord           # discord | onebot (QQ)
    url: ""                 # discord: https://discord.com/api/webhooks/... ；onebot: http://127.0.0.1:3000/send_private_msg?...

history:
  enabled: true
  sample-interval-seconds: 60   # 历史采样间隔 (秒)，环形容量约覆盖 24 小时
  persist: true                 # 文件持久化（按天 CSV；/perfscope trend 跨重启可查）
  retention-days: 7             # 历史文件保留天数

hotspots:
  enabled: true
  sample-every-cycles: 30       # 热点区块全遍历节流 (每 N 个采集周期 ≈ 30 秒)

# 各世界独立设置
worlds:
  world:
    entities:
      total_warning_threshold: 2500
  world_nether:
    entities:
      total_warning_threshold: 2000
```

完整配置说明请参考 `plugins/PerfScope/config.yml`。

> 注（阈值方向）：TPS 是低值指标——数值越低越差，因此要求 `warning_threshold ≥ critical_threshold`（与 MSPT/内存等"高值差"指标相反，别配反）；若倒置，`/perfscope reload` 会校验并回退为默认值，同时在控制台输出"配置校验：…设置倒置，已回退默认值"的告警，不会静默生效。
>
> 已知行为（告警冷却与 reload）：告警冷却表在 `/perfscope reload` 后**不清空**（设计如此，防止重载导致同一告警重推刷屏）；副作用是排障时短时间内的真实告警可能被上次的冷却窗口短暂压制。
>
> 已知行为（告警防抖延迟）：告警默认需连续 3 个采集周期超标才推送（`alerts.debounce-cycles` 可配，1 = 立即推送），最长延迟约 3×采集间隔（默认约 3 秒）——以响应延迟换取"提示器而非狼来了"的信噪比；级别升级（警告→严重）不受冷却与防抖限制，立即以"（级别升级）"单条消息送达。

## 可监测的全部性能指标

| 分类 | 指标 | 说明 |
|------|------|------|
| TPS | 1m/5m/15m 平均 TPS | 服务器核心健康指标 |
| MSPT | 平均/最小/最大 MSPT | 每 tick 耗时（毫秒），理论最大 TPS 推算 |
| 玩家 | 在线数/最大数/在线列表(世界+Ping) | 玩家负载 |
| 实体 | 总数/按世界/按类型 Top15 | 实体压力分析 |
| 区块 | 加载区块数/强加载区块数/按世界 | 区块负载 |
| 内存 | 堆使用/上限/使用率/已分配/非堆 | JVM 内存状况 |
| CPU | 进程 CPU/系统 CPU/可用处理器 | 处理器负载 |
| GC | GC 次数/耗时/各收集器详情 | 垃圾回收统计 |
| Tile Entity | 总数/按世界分布 | 方块实体负载 |
| 服务器 | uptime/线程数/插件数/世界数/JVM | 服务器基本信息 |

> 注：**红石活动**与**网络吞吐量**监测已于 v1.1 下线（红石为空实现、网络为硬编码假数据），相关指标与功能不再提供；`/perfscope redstone` 子命令保留入口但仅返回下线提示。

## 性能与开销（自测，非保证）

性能插件若说不清自身开销就是自相矛盾。本插件的设计取向是**重活远离主线程**：

- **JVM 指标异步采集**——内存/CPU/GC/运行时信息在异步线程读取 MXBean，整体快照发布给主线程复用；主线程不做长时阻塞。唯一与磁盘相关的主线程动作是每 60 秒一次历史 CSV **入队**（非阻塞 `offer`，队列满则丢弃并计数），真正的写盘在 `perfscope-history-writer` 守护线程上批量完成；
- **主线程只做轻量 Bukkit 读取**——TPS/MSPT/玩家每周期采；实体/区块每 5 周期、方块实体每 10 周期
  为内置常量，热点区块每 30 周期（`hotspots.sample-every-cycles` 可配）才全遍历一次，其余周期复用缓存；
- **玩家层推送自带守卫**——ActionBar 每秒推送仅在玩家开启 monitor 时运行，断线瞬间跳过而非报错。

**实测（JFR，90 秒窗口，被测构建：2026-10-18 的 master 快照，非 v1.2 发布构建；空服 + 1 bot / `view-distance=5` / 约 121 个已加载区块）**：

| 口径 | 数值 |
|---|---|
| 采样**样本**中含 `cn.perfscope` 帧 | 37 / 4394 = **0.84%** |
| 其中异步线程（JVM 指标采集） | 36 / 37 |
| 主线程（Server thread） | 19 个样本中 1 个命中（Wilson 95% CI 约 0.9%–24.6%） |

**这个数字是什么、不是什么**（每一条都重要）：

- 它是"线程时间构成里的**采样份额**"，**不是 CPU 百分比**——分母是 JFR 的样本数，不是 CPU 时间。
- 它**不等于**对 TPS 或 tick 延迟的影响。没有做 A/B、没有 tick 耗时对比，就不能从 0.84% 推出"掉多少 TPS"。
- 该窗口 **96.75% 的样本（4251 / 4394）来自 8 个空闲 I/O 线程的原生阻塞采样**——`HttpClient-SelectorManager`×2、`FileSystemWatchService`、`HTTP-Dispatcher`、`RCON Listener`、`Netty Server IO #0/#1/#2`，各约 531 个。它们几乎不耗 CPU（阻塞在 `WEPoll.wait` / `GetQueuedCompletionStatus`），所以样本总数反映的主要是"空闲线程被采样了多少次"，而不是"谁在干活"；0.84% 是在这样一个被空闲线程主导的分母下算出来的。
- 主线程只有 **19 个样本、命中 1 个**。Wilson 95% 区间约 0.9%–24.6%，**样本量不足以支撑任何结论**——既不能证明它低，也不能证明它高。
- 测的是 **2026-10-18 05:14 的构建**，早于热点全服扫描提交（同日 21:39）与 CSV 写盘提交（次日 02:17），即**测的不是 v1.2 发布构建**。栈帧里的 `MetricsCollector.java:656` 是当时那份代码的行号（`collectChunks` 方法体里的 `world.getLoadedChunks()`），而当前源码里该方法已在不同的行——**这本身就说明被测二进制与仓库 HEAD 不是同一份代码**；本表因此不覆盖 v1.2 新增的主线程路径。
- 早期草稿曾按"帧行数"报告 110 帧 ≈ 2.5%，那是**把帧行当样本**：一个样本内 `getProcessCpuLoad0 → getProcessCpuLoad() → collectCPU → lambda$collectJvmAsync$5 → safeCollect` 横跨 3 行 `cn.perfscope`，按行数会把结果放大 3 倍。本文所有数字都已改为**按样本块**计数。

**已知的主线程开销点**（如实列出，未做量化）：

- 每 5 个周期（默认 5 秒）一次 `world.getLoadedChunks()`，为每个世界分配一次区块数组；上表里主线程**唯一**的命中样本正好落在它上面（`CraftWorld.getLoadedChunks ← collectChunks`）。2000–5000 区块下这个数组的分配成本**尚未测量**；
- 每 30 周期（默认 30 秒）一次**全服区块全遍历**（热点定位）：对每个非空区块取 `getEntities()` 并建一张 `HashMap` 统计主导类型。这是本插件在主线程上最重的一段，**尚未测量**；
- 每采集周期一次 `history.size()` 判上限（`ConcurrentLinkedDeque.size()` 是 O(n) 遍历，默认环长 300，即每秒约 300 次节点遍历）；
- 每 60 秒一次历史采样入队（非阻塞）＋主线程侧的环形缓冲写入。
- **告警状态落盘**：历史上是主线程同步 IO（`AlertService.persistState()` 用 `Files.write` + 原子 `Files.move` 写临时文件再替换）。2026-10-25 的忙载采样里，22 个主线程命中中有 **5 个**落在这条路径上（`checkCycle ← persistState ← Files.write`），是仅次于区块/实体遍历的单项。**该路径已改为投递到单写者守护线程**（状态快照仍在主线程做，只有 open/write/move 移到 `perfscope-alert-state-writer` 守护线程）；上面那个 5/22 是**修复前**构建的数据，新构建尚未复测。

**尚未测量的**：high-load P99（5000 实体 / 2000 区块）、GC 次数与停顿影响、堆驻留、启动开销、A/B 对照。

复现：`tools/perfscope-jfr-overhead.ps1`（按样本块计数、同时请求 `jdk.ExecutionSample` 与 `jdk.NativeMethodSample`、单列 Server thread 并给出 Wilson 区间、打印被测 jar 的版本与 SHA-256）。
完整、可执行的最小实测集（三档负载、A/B 基线、产物格式）见 `docs/07-guides/PERF-BASELINE-PLAN.md`。

> 局限声明：上表是本机（共享开发机、空服 + 1 名 bot、`view-distance 5`、约 121 个已加载区块、90 秒窗口）的**量级参考**，不是性能保证，也不代表你的服务器。测试负载只有 2000–5000 区块目标的 **2%–5%**（121 区块 vs 2000–5000，相差 16–41 倍）。若你观察到异常开销，请带 `/spark profiler` 链接反馈。

## 边界与不做什么

诚实划界——以下事情本插件**不**做，请按需搭配其他工具：

1. **不做根因定位** —— 本插件给出"哪块指标异常"的嫌疑范围，不做方法级火焰图剖析；真正定位卡顿根因请用 `/spark profiler`。
2. **不做长期时序分析平台** —— 历史**会落盘**（默认开启，见 `history.persist`）：内存环形缓冲保留约 24 小时（`/perfscope history`），同时按天写入 `plugins/PerfScope/history/history-<日期>.csv`，默认保留 7 天（`history.retention-days`），`/perfscope trend` 可跨重启回查。但本插件**不做**周级/月级趋势分析、不做多服务器聚合、不替代外部监控（Prometheus/Grafana）；需要长期留存或跨服对比请自行接入外部监控。
3. **MSPT 尖刺检测窗口有限** —— 底层取自 Paper `getTickTimes()` 的滚动窗口（约最近 100 tick）；若尖刺发生在窗口之外，"刚才卡了吗"不一定会如实反映。
4. **不做任何自动处置** —— 本插件只观测、告警与出报告，不会自动执行任何干预动作（不重启、不清理实体、不改配置）。
5. **告警有防抖延迟** —— 告警默认需连续 3 个采集周期超标才推送（`alerts.debounce-cycles` 可配），最长延迟约 3×采集间隔（默认约 3 秒）；这是为抑制阈值附近抖动噪音的有意取舍，要求秒级即时到达的场景可把 `debounce-cycles` 设为 1。

## 依赖

- Paper 1.20.4+
- Java 17+

## 构建

```bash
mvn clean package
```

JAR 文件将生成在 `target/` 目录。

## 许可证

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

本项目自 v1.1 起采用 [GNU Affero General Public License v3.0](https://www.gnu.org/licenses/agpl-3.0.html)（AGPL-3.0）许可证，完整条款见仓库根目录的 LICENSE 文件。

这意味着：
- 任何人都可以自由使用本插件，包括商业用途——无需付费，也无需额外授权；
- 任何分发（无论是否修改、无论是否商用）都必须保留版权声明与许可证文本，并整体以 AGPL-3.0 开源；
- 通过网络提供服务（如部署在服务器上供玩家使用）时，须向用户提供对应源码（AGPL-3.0 §13）。

版本说明：v1.0.0 及之前已发布的版本永久保持 GPL-3.0，不受此次变更影响；自 v1.1 起为 AGPL-3.0。