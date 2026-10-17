[English](README-EN.md) | 简体中文

# PerfScope —— 服务器性能监测插件

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
- **高度自定义配置** —— 所有模块的告警阈值、采集间隔均可配置
- **ActionBar 实时监测** —— `/perfscope monitor` 切换，每秒刷新核心指标到 ActionBar（动作栏，屏幕底部物品栏上方）。定位为**诊断探针**：排查卡顿或事故处置时开启几十秒观察实时波动，看完即关；并非设计为 7×24 常驻监测面板
- **性能报告导出** —— 支持 HTML/TXT/JSON 三种格式，HTML 报告含 TPS 趋势图、实体分布表、内存进度条、告警高亮
- **自动报告生成** —— 可配置间隔，异步生成不阻塞主线程，旧报告自动按保留天数清理
- **按世界独立告警** —— 各世界可覆盖实体告警阈值
- **阈值告警主动推送**（v1.1 新增，v1.2 收敛） —— 告警触发时推送控制台 + 在线持有 `perfscope.alert` 权限的玩家；默认需连续 3 个采集周期超标才首次推送（防抖，可配）；同一告警默认 300 秒冷却防刷屏；级别升级（警告→严重）只推一条"（级别升级）"消息、不受冷却限制；故障恢复时发送恢复通知；可在 `config.yml` 的 `alerts` 段配置
- **彩色 UI 面板** —— 使用 Paper Adventure API 渲染，关键指标按 `config.yml` 告警阈值分级着色（绿/黄/红）；显示与告警同一口径，改配置 reload 后颜色立即跟随

## 目标平台与兼容性

PerfScope 自 v1.1 起专注 **Paper 系**服务端，深度依赖 Paper 原生 Adventure API 等特性：

- **支持**：Paper 1.20.4+ 及其下游分支（Purpur / Pufferfish / Leaves 等）
- **Folia**：自 v1.1 起支持——通过调度适配层经 `GlobalRegionScheduler` / `AsyncScheduler` 适配，`plugin.yml` 已声明 `folia-supported: true`（全局与 JVM 级指标完整，实体/区块指标为尽力而为）
- **不支持**：纯 Spigot / CraftBukkit

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
| `/perfscope gc` | GC 统计（各收集器详情） |
| `/perfscope report [格式]` | 导出性能报告（html/txt/json） |
| `/perfscope monitor` | 切换 ActionBar 实时监测 |
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

## 边界与不做什么

诚实划界——以下事情本插件**不**做，请按需搭配其他工具：

1. **不做根因定位** —— 本插件给出"哪块指标异常"的嫌疑范围，不做方法级火焰图剖析；真正定位卡顿根因请用 `/spark profiler`。
2. **不做历史持久化** —— 当前版本只有内存内实时快照与有限采样窗口，不落盘趋势数据；因此回答不了"昨晚卡不卡""上周以来的趋势"这类回溯性问题。
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