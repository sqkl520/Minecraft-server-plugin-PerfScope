[English](README-EN.md) | 简体中文

# PerfScope —— 服务器性能监测插件

一个面向 Minecraft 服务器的全面性能监测插件，专注服务端层面的实时性能追踪与诊断。支持游戏内指令查看实时数据、按世界独立告警、以及自动生成性能报告。

## 功能特性

- **全量诊断** —— 输入 `/perfscope` 即可查看所有核心性能指标，一站式诊断
- **TPS 监测** —— 1m/5m/15m 实时采集，支持 warning/critical 两级告警阈值自定义
- **MSPT 监测** —— avg/min/max 三指标采集，通过 Paper API 反射获取 TickTimes 数据，理论最大 TPS 推算
- **玩家统计** —— 在线数/最大数/按世界分布/延迟(Ping)，支持容量告警
- **实体统计** —— 总数/按世界/按类型 Top15，每个世界可独立设置告警阈值
- **区块统计** —— 加载区块数/强加载区块数/按世界分布，支持独立告警
- **内存诊断** —— 堆内存使用/上限/使用率/已分配，非堆内存使用，GC 次数/耗时/各收集器详情
- **CPU 诊断** —— 进程 CPU 使用率/系统 CPU 负载/可用处理器数/活跃线程数
- **红石活动追踪** —— Paper 服务端红石线活跃计数
- **Tile Entity 统计** —— 总数/按世界分布
- **网络吞吐量监测** —— 每秒收发字节数，支持流量告警
- **服务器运行信息** —— uptime/线程数/已加载插件数/已加载世界数/JVM 版本
- **高度自定义配置** —— 所有模块的告警阈值、采集间隔、实体追踪类型均可配置
- **ActionBar 实时监测** —— `/perfscope monitor` 切换，每秒刷新核心指标到屏幕顶部
- **性能报告导出** —— 支持 HTML/TXT/JSON 三种格式，HTML 报告含 TPS 趋势图、实体分布表、内存进度条、告警高亮
- **自动报告生成** —— 可配置间隔，异步生成不阻塞主线程，旧报告自动按保留天数清理
- **按世界独立告警** —— 各世界可覆盖实体告警阈值
- **彩色 UI 面板** —— 使用 Paper Adventure API 渲染，TPS/MSPT/内存根据阈值自动变色（绿/黄/红）

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
| `/perfscope redstone` | 红石活动 |
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

## 安装

1. 下载最新版本的 JAR 文件
2. 放入服务器的 `plugins/` 目录
3. 重启服务器或使用 `/plugman load PerfScope`
4. 编辑 `plugins/PerfScope/config.yml` 进行自定义配置
5. 使用 `/perfscope` 查看性能数据

## 配置

主要配置项（`config.yml`）：

```yaml
general:
  update_interval: 20       # 数据采集间隔 (tick)
  language: zh_cn
  debug: false

tps:
  enabled: true
  warning_threshold: 18.0   # TPS 告警阈值
  critical_threshold: 15.0

mspt:
  enabled: true
  warning_threshold: 45.0   # MSPT 告警阈值 (ms)
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
  auto_export_interval: 30  # 自动导出间隔 (分钟)
  format: html
  retention_days: 7         # 报告保留天数

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

## 可监测的全部性能指标

| 分类 | 指标 | 说明 |
|------|------|------|
| TPS | 1m/5m/15m 平均 TPS | 服务器核心健康指标 |
| MSPT | 平均/最小/最大 MSPT | 每 tick 耗时（毫秒），理论最大 TPS 推算 |
| 玩家 | 在线数/最大数/按世界分布/延迟 | 玩家负载 |
| 实体 | 总数/按世界/按类型 Top15 | 实体压力分析 |
| 区块 | 加载区块数/强加载区块数/按世界 | 区块负载 |
| 内存 | 堆使用/上限/使用率/已分配/非堆 | JVM 内存状况 |
| CPU | 进程 CPU/系统 CPU/可用处理器 | 处理器负载 |
| GC | GC 次数/耗时/各收集器详情 | 垃圾回收统计 |
| 红石 | 活跃红石线数 | 红石活动追踪 |
| Tile Entity | 总数/按世界分布 | 方块实体负载 |
| 网络 | 每秒收发字节数 | 网络吞吐量 |
| 服务器 | uptime/线程数/插件数/世界数/JVM | 服务器基本信息 |

## 依赖

- Paper 1.20.4+
- Java 17+

## 构建

```bash
mvn clean package
```

JAR 文件将生成在 `target/` 目录。

## 许可证

MIT License