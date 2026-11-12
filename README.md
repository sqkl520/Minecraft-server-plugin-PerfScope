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
- **Webhook 推送**（v1.2 新增，v1.4 扩展） —— 告警 / 恢复 / 每日摘要可推送到 Discord、
  QQ OneBot、**企业微信 / 飞书 / 钉钉**群机器人；全异步发送，失败只记日志不阻塞。
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
| **Paper 1.20.4** | **已实机验证**（v1.2.1 起） | 在 Paper 1.20.4 build 343 上实测：正常启用、`/perfscope` 全量诊断、`/status`、`/diagnose`（三态输出）、`/hotspot`、`/trend` 全部正常；MSPT 走 `getTickTimes()` 取得真实 avg/min/max。1.20.4 以下版本无保证 |
| **Purpur / Leaf / Leaves** | **可加载验证** | 同一份 Paper 代码路径能启动、命令可响应；**分支私有优化（AI 限速、实体合并、区块缓存等）未覆盖**，可能影响实体/区块计数的绝对准确性 |
| **Folia** | **可加载，指标受限** | 能启动并响应命令（命令与配置层可用），但 **MSPT 不可用**（无全局 tick 时间），**TPS 为 global region 局部值**（不是全服吞吐）；**实体 / 区块 / 方块实体 / 热点四个模块自动跳过**（`/status` 显示"平台限制"）。**注意**：本版本在 Folia 上的自动化验证只覆盖到加载与命令可达性，长时间运行与指标正确性**未经验证** |
| **Pufferfish** | **未验证** | 未在 Pufferfish 上运行过。Paper 代码路径理论上兼容，但**没有任何实测证据**——请不要把它当作"已支持" |
| **纯 Spigot / CraftBukkit** | 不支持 | 依赖 Adventure 渲染，无法在无 Paper API 的服务端运行。插件会在启动第一步检测平台并**主动禁用自己**，控制台给出明确原因，而不是半装配后崩溃 |

补充说明：

- **兼容矩阵的边界**：项目内的 189 条场景断言验证的是**加载与命令可达性**（命令能执行、输出含预期标题/字段），
  **不是数值正确性**——断言均为标签子串匹配，不校验读到的 TPS/MSPT/实体数是否真值。
  各服务端的**最后一次实测时间**见 `docs/` 下的测试报告；不要假设"曾经跑过"等于"当前版本仍然正确"。
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
| `/perfscope trend [天数]` | 趋势摘要（跨重启历史持久化，默认 1 天）。持久化不可用时自动回退到内存环并把天数收窄为 1 天；历史文件里有坏行会明确报出行数 |
| `/perfscope gc` | GC 统计（各收集器详情） |
| `/perfscope report [格式]` | 导出性能报告（html/txt/json） |
| `/perfscope monitor` | 切换 ActionBar 实时监测（采集停摆时改为提示"已 N 无数据"而不是继续显示旧数值） |
| `/perfscope status` | 插件自身状态（采集是否存活/模块三态/历史/告警/**采集耗时分布**）。最近的采集异常只显示脱敏摘要（异常类型 + 首句，绝对路径替换为 `<path>`），完整原文在控制台日志 |
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

> 数据披露：本插件内置 bStats 匿名使用统计（**默认启用**），项目 ID 为 **35061**（<https://bstats.org/plugin/bukkit/PerfScope/35061>），**数据已实际上报**。上报内容分两层，粒度不同：
>
> **A 层 — bStats 库自动上报（本插件无法逐项控制）**：服务端类型与版本、插件版本、操作系统名与版本号、CPU 核心数、**当前在线玩家数（精确值，未分档）**、正版/离线模式、服务器 UUID（随机生成，非服务器地址）。
>
> **B 层 — 本插件自定义图表（已分档）**：服务端软件类型、启用模块组合、界面语言、实体数/加载区块数/峰值玩家数/堆上限的**量级档位**、世界数量、已安装插件数量、webhook 通道类型。
>
> **两层都不会上传**：玩家名、世界名、坐标、聊天内容、插件列表、配置文件内容、告警文本、服务器地址。可在 `plugins/bStats/config.yml` 或本插件 config.yml 的 `general.bstats-enabled: false` 关闭。

## 配置

主要配置项（`config.yml`）：

```yaml
general:
  update_interval: 20       # 数据采集间隔 (tick)
  debug: false
  language: en              # 界面语言: en（默认）/ zh；改后 /perfscope reload 生效

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
  hysteresis_margin: 5.0            # 迟滞带 (百分点)：跌到阈值-本值才解除告警，防止边界抖动刷屏

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
  flap-window-seconds: 180  # 抖动窗口：该窗口内的「恢复→再越界」视为同一次事故，不重复通知
  repush-delta: 8           # 同级别重推的数值变化阈值：变化小于它时不重推（只作用于 cpu:system）
  refire-min-interval-seconds: 60  # 复燃最短间隔：恢复后只有在该窗口内再次越界才算「复燃」（0 = 关闭复燃识别，再次越界按普通新告警处理，仍受 cooldown-seconds 约束）
  daily-summary:
    enabled: false          # 每日摘要开关
    time: "08:00"           # 每日摘要时刻 (本地时区 HH:mm)
  webhook:
    enabled: false          # webhook 推送开关 (告警/恢复/日摘要)
    type: discord           # discord | onebot | wecom | feishu | dingtalk
    url: ""                 # 完整 webhook 地址（含令牌），原样粘贴
    secret: ""              # 仅 feishu/dingtalk 用；其余类型留空。纯数字密钥要加引号

history:
  enabled: true
  sample-interval-seconds: 60   # 历史采样间隔 (秒)，环形容量约覆盖 24 小时
  persist: true                 # 文件持久化（按天 CSV；/perfscope trend 跨重启可查）
  retention-days: 7             # 历史文件保留天数

hotspots:
  enabled: true
  sample-every-cycles: 30       # 热点区块刷新节流 (每 N 个采集周期完成一轮完整扫描 ≈ 30 秒)
  scan-shards: 10               # 分区增量扫描分片数（1 = 关闭；越大单拍越省、刷新越慢）

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

### 界面语言（i18n）

插件内置 **英文（默认）** 与 **简体中文** 两套完整文案，通过 `general.language` 切换：

```yaml
general:
  language: en        # en = English（默认）, zh = 简体中文
```

- **切换方式**：改 `general.language` → `/perfscope reload`。也接受 `en_US`/`en-us`、`zh_CN`/`zh-TW` 等写法（按主语言归一）；其他值会在控制台告警并回退 `en`。
- **整体切换，不会中英混排**：语言在每次 reload 时作为一份快照整体替换，任何一条输出都只可能来自同一份语言。
- **自定义文案**：`plugins/PerfScope/messages.yml` 可覆盖任何一条内置文案。文件首行必须写 `language: en` 或 `language: zh`——**标签与 `general.language` 不一致时整份文件会被忽略**（避免中文覆盖文案混进英文界面），并在控制台与 reload 回执里明确告知。
- **占位符**：文案里可写 `{0}`、`{1}`，由插件填入；参数不足时占位符原样保留，不会报错。
- **升级说明**：旧版 `messages.yml`（没有 `language` 标签）若**未做自定义**会被自动备份并按当前语言重新生成；若**含自定义**则原文件保留不动、本次不生效（回执会提示），请手动补上 `language:` 标签并翻译。

> 例外（不随语言变化）：插件的 `plugin.yml` 简介、切换前已导出的报告文件、已写入的历史告警记录（按写入时的语言保存，不随切换重渲染）、切换瞬间已排队发出的 webhook 消息。

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
| CPU | 进程 CPU/系统 CPU/可用处理器 | 处理器负载（**Windows 上进程 CPU 显示「未知」**，见下） |
| GC | GC 次数/耗时/各收集器详情 | 垃圾回收统计 |
| Tile Entity | 总数/按世界分布 | 方块实体负载 |
| 服务器 | uptime/线程数/插件数/世界数/JVM | 服务器基本信息 |

> 注：**红石活动**与**网络吞吐量**监测已于 v1.1 下线（红石为空实现、网络为硬编码假数据），相关指标与功能不再提供；`/perfscope redstone` 子命令保留入口但仅返回下线提示。

> 注（Windows 上的进程 CPU）：`getProcessCpuLoad()` 的 Windows 实现每次调用都要枚举全部进程名，实测单次阻塞 **p50 171 ms / p90 257 ms / max 332 ms**，而它跑在 Bukkit 的**共享**异步池上，会挤占其他插件的异步任务。因此**在 Windows 上本插件不采集进程 CPU**，该指标显示「未知」，`cpu.process_warning_threshold` 不会触发；系统 CPU 与其余全部指标照常工作。Linux / macOS 读 `/proc` 或等价接口，是微秒级，进程 CPU 正常采集。

### 接入 Prometheus / Grafana

`prometheus.enabled: true` 后，插件在 `127.0.0.1:8940/metrics` 暴露 Prometheus 文本格式
（0.0.4）的指标，零第三方依赖。在 `prometheus.yml` 里加一段即可：

```yaml
scrape_configs:
  - job_name: minecraft
    static_configs:
      - targets: ["127.0.0.1:8940"]
```

除了 TPS/MSPT/内存/实体/区块这些基础指标，端点还导出本插件独有的**可信度信号**——
这也是它相对通用 MC exporter 的差别所在：

| 指标 | 用途 |
|---|---|
| `perfscope_snapshot_age_seconds` | 区分"服务器死了"和"指标冻住了"。这条在别的 exporter 上通常没有 |
| `perfscope_collector_degraded` | 采集是否已过期（0/1） |
| `perfscope_module_available{module}` | 11 个采集模块各自是否可用——曲线断了先看这里 |
| `perfscope_alerts_active` / `perfscope_alerts_suspended` | 告警状态机的当前状态 |
| `perfscope_tps{period}` / `perfscope_tick_duration_seconds{stat}` | 注意单位：tick 时长是**秒**，Grafana 里乘 1000 显示为 MSPT |

#### 容器 / 面板服：先把 `bind` 改掉

**同类 exporter 的报错几乎全出自这一条，所以放在最前面。** 容器有自己的网络命名空间时，里面的
`bind: "127.0.0.1"` 绑的是**容器自己的回环**，不是宿主机，也不是面板节点。跑在容器外的
Prometheus 无论怎么抓都连不上，哪怕它和 MC 服务端在同一台物理机上。

判定只看一个反差：从外面抓是 `connection refused` / 超时，进容器里
`curl 127.0.0.1:8940/metrics` 却是 200。这个组合说明 bind 没改——既不是网络、不是防火墙，
也不是插件没起来。

> 上游同类插件 `sladkoff/minecraft-prometheus-exporter` 的三个 issue（#36、#123、#275）
> 全是这一个原因，其中一位报告者最后的评论是
> "I'm such an idiot, it's the first line in the documentation"。
> 这一小节存在的唯一目的，就是让它在 PerfScope 上不再发生。

**规则：服务端跑在容器里、且容器有自己的网络命名空间（bridge / 自定义网络）时，先把
`prometheus.bind` 改成 `"0.0.0.0"`，再按场景决定抓哪个地址。**

**两个例外**：`--network host`（容器与宿主机共享网络命名空间，容器内的 `127.0.0.1` 就是宿主机
的回环，`docker run -p` 在此模式下会被完全忽略）和 `kubectl port-forward`（kubelet 在 pod 自己
的 netns 里拨 `localhost`）。这两种情况 `bind: "127.0.0.1"` 就能抓，不必放大 bind、白白丢掉
「只听回环」的保护。

| 部署方式 | `config.yml` | Prometheus 抓取目标 |
|---|---|---|
| 宿主机直装（非容器） | `bind: "127.0.0.1"` | `127.0.0.1:8940` |
| 同机 Docker / docker-compose | `bind: "0.0.0.0"` + 发布端口 | `<宿主机IP>:8940` |
| Prometheus 与 MC 在同一 compose 网络 | `bind: "0.0.0.0"` | 服务名，如 `mc:8940` |
| Pterodactyl / Pelican | `bind: "0.0.0.0"` + 增加端口分配 | `<节点IP>:<分配到的端口>` |
| MCSManager | `bind: "0.0.0.0"` + 端口列表加端口 | `<宿主IP>:<端口>` |
| AMP | `127.0.0.1`（同机）或 `0.0.0.0` | `127.0.0.1:8940` 或 `<宿主IP>:<端口>` |
| 明确禁止额外端口的虚拟主机 | 无法被抓取 | 见本节末 |

**宿主机直装 / 同机抓取** —— 保持默认 `bind: "127.0.0.1"`，抓 `127.0.0.1:8940`。这是安全
的默认值，不需要动。

**Docker / docker-compose** —— 两个选择：

- (a) 容器内 `bind: "0.0.0.0"`，再把端口发布出来（`docker run -p 8940:8940 ...`，或 compose 里
  `ports: ["8940:8940"]`），Prometheus 抓 `<宿主机IP>:8940`；
- (b) Prometheus 与 MC 在同一个 compose 网络里时，容器内 `bind: "0.0.0.0"`，直接抓 **服务名**：
  `mc:8940`（连端口都不用发布）。

**Pterodactyl（Pelican 属同一面板谱系，同理）** —— 面板把宿主端口以「分配（allocation）」
的形式发给服务器。管理员做前两步，你自己做后两步：

1. 端口池在 `Admin → Nodes → Allocation` 里按端口段创建；
2. 给该服务器加分配：`Admin → Servers → [服务器] → Build Configuration → Assign Additional Ports`；
3. `config.yml` 里把 `bind` 改成 `"0.0.0.0"`——只加分配不改 bind，外部照样进不来；
4. Prometheus 抓 `http://<节点IP>:<分配到的端口>/metrics`。

> **为什么看不到「Create Allocation」按钮**：客户端那个按钮只在服务器的 `allocation_limit`
> 大于 0 时才出现，而面板上的初始值可能是 `NULL`。也就是说，在管理员抬高上限之前，普通用户
> **根本看不到这个入口**——不是你操作不对，得找管理员。

> **抬高上限后按钮还可能失败**：即使 `allocation_limit` 已经大于 0，「Create Allocation」仍会
> 报错，除非面板**全局**开启了自动分配——`Admin → Settings → Advanced → "Automatic Allocation
> Creation"`（对应配置 `pterodactyl.client_features.allocations.enabled`，**默认是关的**）。
> 关着时服务端抛 `AutoAllocationNotEnabledException`；开着但节点端口池没划端口段，则抛
> `NoAutoAllocationSpaceAvailableException`。

> **Wings 的一个反直觉行为**：Wings 绑宿主端口时用的是**分配项里记录的那个 IP**——可能是某个
> 具体地址，也可能是 `0.0.0.0`。所以当分配项记的是具体非回环地址时，端点可能从外部完全可达，
> 却从**宿主机自己的 `localhost`** 抓不到。这是预期行为，不是 bug——抓分配 IP，不要抓
> `127.0.0.1`。

**MCSManager** —— 容器实例的端口来自用户可编辑的端口列表（在实例设置里加），非 Docker 实例
直接绑宿主端口、不涉及发布。两种情况同一条规则：`bind: "0.0.0.0"`，抓宿主 IP 与对应端口。

**AMP** —— 实例通常以原生 tmux 进程运行，没有容器网络要跨。Prometheus 在同一台机器上时
`bind: "127.0.0.1"` 就能用；跨机才需要 `"0.0.0.0"` 加宿主端口。

**我真的没有额外端口可用** —— 这种主机确实存在，但很少（抽样 93 份服务商文档，写死「不支持」
的约 2%）。如果所在主机明确禁止开放额外端口，那么这个端点**无法被外部抓取**，且本插件
**没有推送模式（push mode），也没有加入的计划**。这种场景请改用本插件自带的 webhook 告警
（`alerts.webhook.*`，支持 Discord / 企业微信 / 飞书 / 钉钉 / OneBot）：告警由服务器主动发出，
不需要任何入站端口，没有 Prometheus 也能收到通知。

**开放前先设 token。** 绑 `0.0.0.0` 意味着任何能访问该端口的人都能读到在线玩家数、世界名与
插件数量。设一个 `prometheus.auth-token`，抓取方带 `Authorization: Bearer <token>` 头：

```yaml
# config.yml
prometheus:
  bind: "0.0.0.0"
  auth-token: "换成一串随机字符串"
```

```yaml
# prometheus.yml
scrape_configs:
  - job_name: minecraft
    static_configs:
      - targets: ["10.0.0.12:8940"]
    authorization:
      type: Bearer
      credentials: "换成同一串随机字符串"
```

插件在检测到非回环绑定时会打一条 WARNING（没设 token 时也照打，并在括号里标 `NO AUTH`）——
看到这条，就说明端点已经对容器外开放了。

**设了 token 却忘了在 `prometheus.yml` 里带请求头**，症状不是一句清晰的鉴权错误：插件返回
`401` + `WWW-Authenticate: Bearer`，而 Prometheus 把**任何非 200 都当抓取失败**、记成 `up == 0`。
于是 target 直接显示 DOWN、没有任何可读原因，看起来像网络故障——去 Prometheus 自己的
Targets 页面看该 target 的 scrape error 才能定位。

几条设计约定，看数据前值得知道：

- **未采集的指标整行不输出**，不会出现 `-1`。缺行让 Grafana 显示 gap，比画一条贴底的假线诚实。
  例如 Windows 上 `perfscope_cpu_process_ratio` 就是缺的。
- **不输出 `instance` / `job` 标签**——Prometheus 抓取时会自动附加同名的 target 标签，
  再输出会被改名成 `exported_instance`，导致 Grafana 里两个实例互相打架。要跨服区分请用 `server-id`。
- **Folia 上** `perfscope_build_info` 带 `tps_scope="global_region"`，因为那里的 TPS 只是
  global region 的局部值，不是全服。
- 端点默认只听回环。改成 `0.0.0.0` 时启动会打 WARNING：玩家数、世界名、插件数量都在里面，
  公网可达前请先设 `auth-token`。具体抓取方式见上方「容器 / 面板服」小节。

## 性能与开销（自测，非保证）

性能插件若说不清自身开销就是自相矛盾。本插件的设计取向是**重活远离主线程**：

- **JVM 指标异步采集**——内存/CPU/GC/运行时信息在异步线程读取 MXBean，整体快照发布给主线程复用；主线程不做长时阻塞。唯一与磁盘相关的主线程动作是每 60 秒一次历史 CSV **入队**（非阻塞 `offer`，队列满则丢弃并计数），真正的写盘在 `perfscope-history-writer` 守护线程上批量完成；
- **主线程只做轻量 Bukkit 读取**——TPS/MSPT/玩家每周期采；实体/区块每 5 周期、方块实体每 7 周期
  为内置常量；热点区块按**分区增量**扫描：一轮覆盖全部已加载区块，但切成
  `hotspots.scan-shards` 片（默认 10）摊到多个采集周期，单拍成本降到约 1/10（默认 30 周期完成一轮，
  其余周期复用上一轮完整结果）；
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

- 每 5 个周期（默认 5 秒）一次 `world.getLoadedChunks()`，为每个世界分配一次区块数组；上表里主线程**唯一**的命中样本正好落在它上面（`CraftWorld.getLoadedChunks ← collectChunks`）。**2026-11-10 已在高负载档测量**：4,835 区块下该方法占插件主线程采样的 34.8%（插件整体占主线程 0.481%），折合上限约 **0.042 ms/tick**——低于测量误差两个数量级，故**决定不做优化**。原因见下节；
- 每 30 周期（默认 30 秒）一次**全服区块全遍历**（热点定位）：对每个非空区块取 `getEntities()` 并建一张 `HashMap` 统计主导类型。这是本插件在主线程上最重的一段。**已测量**，见下节；
- 每采集周期一次 `history.size()` 判上限（`ConcurrentLinkedDeque.size()` 是 O(n) 遍历，默认环长 300，即每秒约 300 次节点遍历）；
- 每 60 秒一次历史采样入队（非阻塞）＋主线程侧的环形缓冲写入。
- **告警状态落盘**：历史上是主线程同步 IO（`AlertService.persistState()` 用 `Files.write` + 原子 `Files.move` 写临时文件再替换）。2026-10-25 的忙载采样里，22 个主线程命中中有 **5 个**落在这条路径上（`checkCycle ← persistState ← Files.write`），是仅次于区块/实体遍历的单项。**该路径已改为投递到单写者守护线程**（状态快照仍在主线程做，只有 open/write/move 移到 `perfscope-alert-state-writer` 守护线程）；上面那个 5/22 是**修复前**构建的数据，新构建尚未复测。

**尚未测量的**：high-load P99（5000 实体 / 2000 区块）、GC 次数与停顿影响、堆驻留、启动开销、A/B 对照。

### 采集耗时自监测（v1.3.0 起，可在游戏内自查）

`/perfscope status` 现在直接给出**主线程采集耗时分布**，不必再依赖外部 profiler：

```
Collection cost: p50 0ms / p95 54ms / max 127ms (66 samples, main thread)
5 cycles exceeded 50ms (1 tick) - the collector is eating into tick budget
```

**怎么读这三个数**（重要）：

- **`p50`** 是常态。空载测试服上是 **0ms**——绝大多数采集周期几乎没有开销（缓存复用的周期什么都不做）。
- **`p95` / `max`** 是"撞车拍"。采集是分级降频的：实体与区块每 5 周期、方块实体每 7 周期、
  热点每 30 周期。这些周期的最小公倍数决定了几件事会同时发生。实测空载（约 300 区块）
  的 `max` 是 **127ms（约 2.5 tick）**，落在四者同拍的那一周期。
- **"超阈值周期数"** 是问题频率。66 个样本里 5 个超 50ms，即约 **7.6% 的周期**会超过 1 tick。

**只看一个数会误判**：如果恰好在撞车拍看 `max`，会以为"每秒都卡 127ms"；
只看 `p50` 又会以为"完全没开销"。三者合起来才是可解释的口径。

**已知的规模边界**（诚实标注，非保证）：

| 规模 | 采集耗时 p50 / p95 / max | 证据等级 | 说明 |
|---|---|---|---|
| 147 区块 / 5 实体 | 0 / **1** / **4ms** | **A** | 空载基线，`/perfscope status` 可复现 |
| 2,723 区块 / 1,913 实体 | 0 / **8** / **13ms** | **A** | forceload 强加载 + data pack 批量召唤 |
| 4,835 区块 / 8,222 实体 | 1 / **17** / **22ms** | **A** | 同上，两次独立测量一致（22ms / 22ms） |
| 约 11,000 区块（分区改造前） | 约 66.6ms | **B** | 历史基准，**已不适用**于当前版本 |
| 约 50,000 实体 | — | **C** | 未实测；分区后预计远低于旧值 |

**这张表最重要的一件事**：采集开销随规模增长得**远比早期估计平缓**。早期在 11k 区块档测到
66.6ms，据此推演"5 万实体要 100ms+"——但 v1.4 的热点分区改造之后，8,222 实体档的 `max`
只有 **22ms**。分区把最重的热点扫描摊到了多个采集周期，所以"规模上升 → 单拍成本线性上升"
这个旧模型已经不成立了。

**证据等级的含义**：

- **A** — 有可复现的脚本（`testharness/scripts/perf-load-bench.ps1`，参数化三档 + 模块 A/B），
  任何人能跑出同一量级的结果。
- **B** — 用专用方法测过一次，但**测的是旧版本**，不适用于当前代码。
- **C** — 从未在目标规模实跑。**不要拿它做决策。**

**这套数字的边界（必须知道）**：负载是 forceload 强加载 + 批量召唤盔甲架造出来的**空地形**，
没有真实玩家、没有红石、没有区块生成压力。真实大服的主线程压力**远超**这个测试环境——
所以"22ms"应理解为"**PerfScope 自身采集在 8000 实体下的成本**"，不是"大服总共只卡 22ms"。

**没有做的事**（有意为之）：没有引入相位偏移、没有改区块周期。打散方案的前提是
"每个模块单干都小于 1 tick"——分区把单拍成本降下来之后，这个前提才真正成立。

**评估过但决定不做的一次优化**（2026-11-10）：曾计划把 `collectChunks` 里的
`world.getLoadedChunks()` 换成更便宜的 API。JFR 行级归因显示它确实是插件内主线程第一重
（占插件主线程采样 34.8%，95.4% 的采样落在这一行）。放弃的三个理由：

1. **收益低于测量误差**——插件整体只占主线程 0.481%，替换它的上限是 **0.042 ms/tick**；
   而 65/38,905 这个比例的 Wilson 95% 区间就有 ±25%，无法验证。
2. **两个替代 API 都不可用**——`getChunkCount()` 在 Paper 1.20.4 上数的是 `>= BLOCK_TICKING`
   的区块，而 `getLoadedChunks()` 数的是 `>= FULL`，**语义不同会让区块数系统性偏小**；
   `getForceLoadedChunks()` 内部对每个强制区块调用带 `load=true` 的 `getChunkAt`，
   **会触发区块加载**——一个诊断插件不该改动世界的加载状态。
3. **A/B 拔掉全部采集器，tick 反而从 57.5ms 变慢到 65.9ms**——现有仪器分辨不出"全关"，
   那么"关掉其中 35%"在原理上就无法验证。

完整证据链见 `docs/06-tasks/research/v140-01-cpu-async-blocking.md`。

复现：`tools/perfscope-jfr-overhead.ps1`（按样本块计数、同时请求 `jdk.ExecutionSample` 与 `jdk.NativeMethodSample`、单列 Server thread 并给出 Wilson 区间、打印被测 jar 的版本与 SHA-256；**排除 `cn.perfscope.shaded.*`** —— 那是 shade 进来的 bStats 的网络栈，算成插件自身开销会虚高，全关采集器时残留 6.46%）。
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

仓库内置单元测试（JUnit 5，`src/test/java`），`mvn clean package` 会先跑测试再打包：

```bash
mvn test                    # 只跑测试
mvn test -Dtest=CsvLayoutTest   # 跑单个测试类
mvn clean package -DskipTests   # 跳过测试打包
```

测试依赖为 `test` 作用域，不会进入最终 JAR。

## 许可证

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

本项目自 v1.1 起采用 [GNU Affero General Public License v3.0](https://www.gnu.org/licenses/agpl-3.0.html)（AGPL-3.0）许可证，完整条款见仓库根目录的 LICENSE 文件。

这意味着：
- 任何人都可以自由使用本插件，包括商业用途——无需付费，也无需额外授权；
- 任何分发（无论是否修改、无论是否商用）都必须保留版权声明与许可证文本，并整体以 AGPL-3.0 开源；
- 通过网络提供服务（如部署在服务器上供玩家使用）时，须向用户提供对应源码（AGPL-3.0 §13）。

版本说明：v1.0.0 及之前已发布的版本永久保持 GPL-3.0，不受此次变更影响；自 v1.1 起为 AGPL-3.0。