# Changelog

## [Unreleased] - 2026-06-07

### Added
- Spigot/Bukkit 兼容层 (ServerCompat)：自动检测服务端类型 (Paper/Spigot/Bukkit)，标记各功能可用性，启动时输出服务端类型和功能可用性报告
- MSPT 三级降级采集：Paper TickTimes API → Paper getAverageTickTime() → Bukkit TPS 反推估算，确保非 Paper 服务端也能获取 MSPT 数据
- TileEntity 采集兼容：Paper 使用 World.getTileEntityCount()，Spigot/Bukkit 降级为遍历 Chunk.getTileEntities()
- Adventure API 自动 shade：pom.xml 添加 adventure-api/adventure-text-minimessage/adventure-platform-bukkit 依赖并 relocate 到 cn.perfscope.lib，确保 Spigot/Bukkit 下 Adventure API 可用
- 服务端信息显示：全量诊断头部新增"服务端: Paper/Spigot/Bukkit"信息
- 非 Paper 下 MSPT 标记"(估算)"：全量诊断输出中，当 MSPT 来自 TPS 反推时显示估算标记
- 启动信息增强：verbose 模式显示各功能可用性 (MSPT采集/ActionBar/红石追踪/网络统计)

### Changed
- 插件描述更新：兼容 Paper/Spigot/Bukkit 全系列，Paper 上获完整功能，Spigot/Bukkit 部分功能降级
- ActionBar 实时监测：非 Paper 服务端使用时会提示"需要 Paper 服务端支持"

### Changed
- 全量诊断命令重构：`/perfscope`（无参数）或 `/perfscope all` 一键输出所有核心指标，输出格式参考 Hilltop Village `/hilltop tps` 风格（金色粗体标题 + 黄色标签 + 颜色编码值 + 按世界服务器级诊断 + 告警 + 建议）
- 原 overview 面板移除，改为全量诊断 showAll() 方法：TPS/MSPT/玩家/实体/区块/内存/CPU/GC/红石/网络/运行时间 全部在一屏展示
- 专项检测子命令拆分：tps/mspt/entities/chunks/memory/cpu/players/world/redstone/tileentities/uptime/gc 各自独立，每项输出更详细的专项信息
- entities 专项检测：实体类型 Top10 升级为 Top15，增加百分比显示，增加告警提示
- cpu 专项检测：增加 JVM 线程数，进程/系统 CPU 颜色编码阈值调整
- uptime 专项检测：新增 JVM 版本和供应商信息
- memory 专项检测：增加堆内存使用率进度条，内存使用率 >= 90% 时输出优化建议
- 帮助面板更新：新增 all 命令，overview 移除，所有命令均可点击跳转

### Added
- README.md 和 README-EN.md 中英文项目文档：含功能特性、命令表、全量诊断输出示例、权限表、安装指南、配置说明、可监测指标表
- 初始版本发布：完整的服务器性能监测插件框架
- TPS 监测：1m/5m/15m 实时采集，支持 warning/critical 两级告警阈值自定义
- MSPT 监测：avg/min/max 三指标采集，通过 Paper API 反射获取 TickTimes 数据，理论最大 TPS 推算
- 玩家数统计：在线数/最大数/按世界分布/延迟(Ping)，支持容量告警
- 实体统计：总数/按世界/按类型 Top10，每个世界可独立设置告警阈值
- 区块统计：加载区块数/强加载区块数/按世界分布，支持独立告警
- 内存监测：堆内存使用/上限/使用率/已分配，非堆内存使用，GC 次数/耗时/各收集器详情
- CPU 监测：进程 CPU 使用率/系统 CPU 负载/可用处理器数/活跃线程数
- 红石活动追踪：Paper 服务端红石线活跃计数
- Tile Entity 统计：总数/按世界分布
- 网络吞吐量监测：每秒收发字节数，支持流量告警
- 服务器运行信息：uptime/线程数/已加载插件数/已加载世界数
- 高度自定义配置文件 (config.yml)：所有模块的告警阈值、采集间隔、实体追踪类型均可配置
- 游戏内指令系统：/perfscope 含 17 个子命令（overview/tps/mspt/entities/chunks/memory/cpu/players/world/redstone/tileentities/uptime/gc/report/monitor/reload/help），别名 /pscope /perf
- ActionBar 实时监测模式：/perfscope monitor 切换，每秒刷新 TPS/MSPT/玩家/实体/内存/区块
- 性能报告导出：支持 HTML/TXT/JSON 三种格式，HTML 报告含 TPS 趋势柱状图、实体分布表格、内存进度条、告警高亮
- 自动报告定时生成：可配置间隔，异步生成不阻塞主线程
- 旧报告自动清理：按保留天数自动删除过期报告
- 按世界独立设置告警阈值：各世界可覆盖实体告警阈值
- 指令 Tab 补全：子命令/世界名/报告格式自动补全，帮助面板可点击跳转
- 告警系统：所有指标支持 warning/critical 两级告警，阈值通过 config.yml 自定义
- 权限系统：perfscope.use（基础）/perfscope.admin（管理）/perfscope.reload（重载）/perfscope.export（导出报告）
- 彩色 UI 面板：使用 Paper Adventure API 渲染，TPS/MSPT/内存根据阈值自动变色（绿/黄/红）
- Maven 构建系统：Paper 1.20.4 API，Java 17，maven-shade-plugin 打包