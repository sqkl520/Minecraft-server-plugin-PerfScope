# Changelog

本项目：**PerfScope** —— Minecraft 服务器性能监测插件（Paper 1.20.4+ / Purpur / Pufferfish / Folia / Leaves，Java 17）。
后台定时采集性能快照 → 游戏内 17 个子命令一屏诊断 → HTML/TXT/JSON 报告导出 → ActionBar 实时监测 → 阈值告警。

> **仓库**：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope> ｜ **版本**：`1.1.0-SNAPSHOT`（开发中；pom.xml 尚未 bump，仍为 `1.0.0`）

格式参照 [Keep a Changelog](https://keepachangelog.com/)。当前处于 **v1.1「止血与诚实」开发周期**，各段以 **Wave（开发波次）** 划分并标注对应提交哈希。

> **★ 硬规则（项目约定）**：任何代码 / 配置 / 文档的更改，都必须记入本 CHANGELOG（在最上方按
> Added / Changed / Fixed / Removed / Optimized / Refactored / Notes 分类追加）；**编译前先更新**。
> 版本号只在用户明确说"更新版本号"时 bump（Patch = Bug 修复，Minor = 新功能）。
> **本文件是开发流水账** —— 记"改了什么、为什么改、验证到哪一步、还欠什么"，不是给用户看的发布说明。

> **开发计划文档索引**（均在上层工作区 `docs/06-tasks/`）：`ROADMAP.md`（愿景 + v1.0→v2.0
> 共 49 条条目 + 35 条验收标准）、`V1.1_DEV_TASKS.md`（15 个 P0 逐项拆解）、
> `V1.2_DIAGNOSTIC_RULES.md`（37 条诊断规则）、`V1.3_ALERT_SPEC.md`（告警中心规格，30 个配置键）。
> 测试报告在 `docs/05-test-reports/`。

---

## [Unreleased] — Wave 3 - 2026-10-12

> v1.1 最后一个开发波次（prd.json 的 P0-2 / P0-4）。P0-4 的 AlertService 只做**基础版**
> （控制台 + OP 游戏内 + 冷却 + 恢复通知）；完整的告警中心（webhook / QQ OneBot / 连续 N 次防抖 /
> 每日日报）是 v1.3 范围，本次刻意不做。至此 v1.1 的 15 个 P0 全部落地。

### Fixed
- **P0-2 收尾：命令层最后一处 `SimpleDateFormat` 清零**（🟡）：`PerfScopeCommand` 全量诊断头部的
  采集时间格式化（`new SimpleDateFormat("HH:mm:ss").format(new Date(snap.timestamp))`）改为线程安全的
  `DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())` +
  `Instant.ofEpochMilli(snap.timestamp)`。先读 `MetricsSnapshot` 确认 `timestamp` 为 `long`（epoch 毫秒）。
  输出格式与旧实现逐字一致（`HH:mm:ss`，仅全量诊断头部，非报告全文格式）。至此全仓 `SimpleDateFormat`
  清零（grep 仅剩注释）。

### Added
- **P0-4：`AlertService`（新包 `cn.perfscope.alert`）**：告警推送服务，消费采集侧已生成的
  `activeWarnings / activeCriticals`（**不做阈值判定**，阈值逻辑仍在采集层，避免双份真相源）。
  - **冷却/去重 key**：取告警文本**最后一个冒号**（半角 `:` / 全角 `：` 都认）之前的前缀作为 key
    （`TPS 严重偏低: 14.20` → key `TPS 严重偏低`）。用"最后一个"冒号是为了兼容
    "世界 w:x 实体数严重超标: 3000"这类**世界名含冒号**的告警 —— 每世界独立 key、独立冷却
    （与 V1.3 规格的 `metric:scope` 方向一致）。同 key 同周期同时出现 warning + critical 时取 critical。
  - **冷却**：`ConcurrentHashMap<String, Long>`，同 key 冷却期内（默认 300 秒，可配）只推一次；
    **先落冷却再发送**（发送失败也不会下周期立刻重试刷屏）；`cooldown-seconds: 0` = 无冷却
    （退化为受采集周期天然节流）。
  - **恢复通知（基础状态机）**：记录上周期活跃 key 集合，本周期缺失 = 恢复；默认开启
    （`notify-recovery: true`），每 key 每次恢复只发一条；恢复通知不受冷却限制。
  - **通道**：① 控制台，前缀 `[告警][严重/警告/恢复]`（critical → `Logger.severe`、warning → `warning`、
    恢复 → `info`）；② OP 游戏内，向所有在线且持有 `perfscope.alert` 权限的玩家发送
    （warning 黄 / critical 红 / 恢复 绿；**Paper 原生 Adventure 直调 `player.sendMessage(Component)`**，
    与 P0-3 修复后的依赖方案一致）。
  - **线程**：经 `SchedulerAdapter.runGlobalTimer` 在主线程按采集间隔周期运行（Folia 下为全局区域线程，
    消息发送天然线程安全）；`onSnapshot` 顶层 `catch (Exception | LinkageError)` 兜底
    （防异常导致 Bukkit 重复任务被静默取消，且不吞致命 `Error`）。
  - **生命周期**：`PerfScope.onEnable` 在采集器启动后创建并启动；`onDisable` 顺序
    "monitor → 自动报告 → 告警 → 采集"；`reload` 停→启即生效（`alerts.enabled=false` 时 `start()`
    内部直接返回），**不清空冷却与活跃状态机**（按 V1.3 规格）。
  - **已知边界（诚实声明，v1.3 状态机根治）**：无"连续 N 次确认"防抖 —— 阈值抖动场景可能出现
    告警/恢复交替刷行；warning→critical 升级因文案前缀不同视为两个 key，会先发恢复再发新告警。

### Removed
- **P0-4 欠账：死配置清理**（三项先 grep 全仓确认为"读取/定义但零使用"再删）：
  - `general.language`（ConfigManager 读取 + ConfigData 字段 + config.yml 键）—— i18n 从未实现，删；
  - `entities.tracked_types`（同上，含 ZOMBIE..MINECART 共 8 项列表）—— 按类型追踪逻辑从未实现，删；
  - `report.verbose_report`（同上）—— 报告从未使用，删。
  - `general.debug` **保留**（P0-5 的异常隔离已在实际使用它决定是否打异常堆栈）。

### Changed
- **`pendingTasks` 死字段复活**：`MetricsSnapshot.pendingTasks` 此前仅声明、全仓零读零写（Wave 1 审计发现）。
  按 V1.2 诊断规则 D-SYS-02 的计划**保留字段并补上采集**：`collectServerInfoBukkit` 内新增
  `Bukkit.getScheduler().getPendingTasks().size()`，就地 `try-catch (Exception | LinkageError)` 置 `-1` 哨兵
  （Folia 下该 API 可能不适用），**避免牵连整个 server 模块被标 unavailable**。
- **config.yml 新增 `alerts:` 段**（5 键，全带中文注释）：`enabled: true` / `console: true` /
  `in-game: true` / `cooldown-seconds: 300` / `notify-recovery: true`。
  ConfigManager 对应新增 5 个 ConfigData 字段（`alerts_enabled / alerts_console / alerts_in_game /
  alerts_cooldownSeconds / alerts_notifyRecovery`）；旧 config 缺键走默认值，多余键天然忽略。

### Notes
- **验证状态**：`mvn clean package` BUILD SUCCESS（主编排亲测）；解包核实 jar 内 config.yml 已无三个死键、
  `alerts:` 段就位；code 审查 **PASS**（0 阻断，7 条非阻断观察，报告 `wave3-code.md`）。
- **备案（非阻断，已记入报告）**：① 告警推送对象从 P0-4 早期文档写的 `isOp` 改为 `perfscope.alert`
  权限（与 P0-10 该节点 `default: op` 行为等价，且与 V1.3 规格 forward-compatible）；
  ② README / README-EN 第 105 行配置示例仍含已删的 `language: zh_cn`（文档待同步，超出本波文件范围）；
  ③ 恢复通知不受冷却 + 无防抖 —— 服主若反馈恢复行刷屏，按 `wave3-code.md` 问题 4 热修。
- **未验证（运行时，留给测试服）**：告警三场景实测 —— 把 `tps.critical_threshold` 临时调到 25
  且 `cooldown-seconds: 10`，观察控制台 `[告警][严重] TPS 严重偏低: …` 与 OP 玩家红色推送、
  10 秒内不重复、恢复后收到绿色恢复通知。
- **未归档**：`versions/` 未归档 JAR（v1.1 未发布；按版本规则待用户明确确认后再归档）。

---

## [Unreleased] — Wave 2 - 2026-10-11 ～ 10-12

> 「止血」第二波：先把采集自身的性能与调度做扎实（P0-1 / P0-8），再用**反编译实证**修掉构建层的
> 致命 bug 并顺势清理历史包袱（P0-3 / P0-15）。分两批开发、两批验收。
> **本波起插件定位调整为专注 Paper 系**（Paper / Purpur / Pufferfish / Folia / Leaves），
> 不再支持纯 Spigot/CraftBukkit。

### Fixed
- **P0-3 致命 bug：Adventure shade/relocate 破坏 API 签名**（🔴，反编译实证）：
  用 `javap -c` 反编译 v1.0.0 jar 发现 `PerfScopeCommand` 里所有
  `CommandSender.sendMessage` / `Player.sendActionBar` 的**参数类型**已被 shade relocate 改写为
  `cn.perfscope.lib.adventure.text.Component` —— 而 Paper 运行时只认
  `net.kyori.adventure.text.Component`，两者签名对不上，**插件在 Paper 上一敲任意命令必抛 `NoSuchMethodError`**
  （自 v1.0.0 起即存在；此前静态分析把它误判为"仅 Spigot 的兼容问题"，反编译后升格为
  "连 Paper 自己都用不了"的致命 bug）。
  修复：删掉整个 shade/relocate 与全部 compile 范围 adventure 依赖，改用 **Paper 原生 Adventure**
  （由 `paper-api` 传递提供，provided）。修复后再次 `javap` 验证：签名恢复为
  `Lnet/kyori/adventure/text/Component;`；jar 内零 `net/kyori` / 零 relocate 残留。
- **P0-6 收尾**：报告层详情段的 unavailable 一致性 —— HTML 内存详情 / GC 行 / 实体类型明细表
  在模块不可用时显示"不可用"（灰色）而非空表/0 值（与命令层同款语义，报告层共 29 处消费点）。

### Changed
- **P0-1 采集异步化 + 分级降频**（🔴 主线程开销，本波核心）：
  - 采集拆成两个任务：`collectSync`（主线程 / Folia 全局区域线程，**每周期产完整快照入 history，
    节奏不变** —— 趋势图、报告、monitor 的行为全部不受影响）+ `collectJvmAsync`（异步任务，
    **只碰 JVM/MXBean**，结果经 `volatile MetricsSnapshot jvmCache` 整体发布）。
    jvmCache 的写入逐方法核对过：异步线程零 Bukkit 世界 API 调用（Folia 安全的关键项）。
  - 分级降频（类内常量，不进 config）：实体每 5 周期（`ENTITY_SAMPLE_EVERY=5`）、
    区块每 5 周期、方块实体每 10 周期才**真正遍历**，其余周期复用缓存值；缓存含 unavailable 标记
    延续（上次不可用的模块在复用周期继续标不可用）；`start()` 重置 `ready=false` 保证首周期必真实采集
    （不会 NPE 或暴露错误的 0）。轻量模块（TPS/MSPT/players + server 信息的 Bukkit 半）保持每周期。
  - `start()` 首行 `stop()` 防呆（reload 幂等不叠任务）。
- **P0-8 调度适配层 `SchedulerAdapter`**（新建，`compat` 包）：统一
  `runGlobalTimer / runAsyncTimer / runLater` 三个入口，返回 `TaskHandle`（`cancel()` 判空安全）。
  非 Folia 走 Bukkit 调度器；**Folia 分支全反射**（`GlobalRegionScheduler / AsyncScheduler /
  ScheduledTask`，类加载时缓存 Method，取不到则降级 Bukkit 并 warning），**零直接 import Folia 类**
  （编译依赖 paper-api 1.20.4 不含 Folia 调度类，直接 import 会编译失败）；Folia 的 delay/period ≤0
  自动校正为 1。采集与自动报告任务已切换（`PerfScope.autoReportTask` 类型 `BukkitTask` → `TaskHandle`）；
  `plugin.yml` 增加 `folia-supported: true`。
  - 批次二收尾：`/perfscope monitor` 的定时任务（原 `scheduleSyncRepeatingTask`）迁移到
    `SchedulerAdapter.runGlobalTimer`（`monitorTaskId: int` → `TaskHandle`），20 tick/次推送间隔不变；
    **消息发送代码一字未改**（P0-3 修复后它们本就是对的）。
- **P0-15 构建清理**：移除 `spigot-api`（冗余，paper-api 是其超集）、`adventure-api`、
  `adventure-text-minimessage`、`adventure-platform-bukkit`（后两者全仓零引用）与整个
  `maven-shade-plugin`（含两条 relocate 与 META-INF 签名 filter）；连带移除只服务 spigot-api 的
  `spigotmc` 仓库。build 下只剩 `maven-compiler-plugin`，退回默认 maven-jar 打包；
  `finalName` / resources filtering / licenses 块保留。**jar 体积大减**（不再打包整套 Adventure）。
- **ServerCompat（P0-3 配套）**：删除随 shade 时代遗留的 `checkPaperActionBar()` / `checkAdventure()`
  反射检测（前者原含 `net.kyori.adventure.text.Component.class` 类字面量，旧 shade 下被 relocate
  导致恒误判为不可用）；`PAPER_ACTION_BAR_AVAILABLE` **字段名保留**（启动日志与 monitor 门控仍引用）、
  判定简化为 Paper 系恒 true；`ADVENTURE_AVAILABLE` 同样恒 true。删除后 ServerCompat 不再有任何
  net.kyori 引用。P0-9 的三层服务端识别逻辑未动。
- **文档**：README / README-EN 更新为 Paper 系定位 —— 新增"目标平台与兼容性"章节；移除非 Paper 降级描述；
  可监测指标表移除红石/网络两行并注明 v1.1 下线；`/perfscope redstone` 保留为下线提示 stub；
  Folia 表述为"经调度适配层支持（全局/JVM 指标完整、实体/区块尽力而为）"；plugin.yml description 同步。

### Notes
- **本波关键证据链**：P0-3 从"静态分析推断的隐患"→"反编译实证的致命 bug"→"修复后反编译验证"
  —— 三次 `javap` 快照是本次最有价值的工程动作。教训：**shade relocate 对"要递给服务端 API 的类型"是禁区**；
  构建产物必须反编译抽检，不能只看 BUILD SUCCESS。
- **三维测试（批次一）**：code 审查 **PASS**（0 阻断；5 条非阻断备案：节流缓存 Map 浅拷贝契约脆弱、
  `ConfigManager.config` 非 volatile 的 reload 窄竞态、`MODULE_SERVER` 被同步/异步两半共用同一
  unavailable 键、首周期 server uptime 瞬时 0、Folia 反射失败时降级 Bukkit 的兜底未包 try —— 均为边界项，
  不影响验收）。报告：`wave2-batch1-code.md`。
- **提交**：批次一 `a1c76c9`、批次二 `6ac7db9`。
- **未验证（运行时，留给测试服）**：Paper 命令输出恢复正常（本波最重要验收点）、Folia 加载与指标、
  monitor 迁移、节流后"每 5 周期一次全量遍历"的**周期性主线程尖峰幅度**（峰值未降、仅频率降至
  1/5 ～ 1/10 —— 这是刻意的取舍，压峰值是后续版本的事）。

---

## [Unreleased] — Wave 1 - 2026-10-11

> v1.1 第一波（prd.json 的 P0-5/6/7/9/10/11/12/13/14）。目标：**不崩、不假、不卡** —— 零新功能。
> 流程：4 路并行开发（文件集互斥）→ 统一编译 → build/code/arch 三维测试 → 修复循环 → 提交 `d0aac58`。

### Fixed
- **P0-5 采集模块异常隔离**（🟠）：`collect()` 内 10 个模块原先串行且无隔离，任一抛异常则后续全跳过、
  当次快照残缺，且异常每秒刷一次控制台。`MetricsSnapshot` 新增 10 个 `MODULE_*` 常量 +
  `unavailable` 集合（`ConcurrentHashMap.newKeySet`）；`MetricsCollector` 用 `runOrMark / safeCollect`
  包裹（`catch Exception | LinkageError`，放行致命 `Error`；`general.debug` 决定是否打堆栈）；
  **命令层 19 处 + 报告层 29 处**消费 unavailable，模块禁用/采集失败统一显示"不可用"（而非误导性 0/空）。
  语义决定：**配置禁用的模块也标 unavailable** —— "关掉"与"采不到"同为"不可用"，对使用者是同一种事实。
- **P0-6 TileEntity 计数值传递 bug**（🟠）：`collectTileEntities_Bukkit(int total, ...)` 累加的是形参副本，
  调用方 `tileEntityCount` 在非 Paper 降级路径**恒为 0**。改为 `return total`；Paper 路径
  `getTileEntityCount` 反射提为字段、启动时一次解析（热路径只 invoke，不再每次 `getMethod`）。
- **P0-7 网络/红石假功能下线**（🟠）：`collectNetwork` 是纯桩代码（收发字节硬编码 0，阈值永不触发）、
  `collectRedstone` 是"扫描 NMS 全部无参、方法名含 Redstone 的方法"的脆弱启发式（Paper 实机大概率恒 -1）。
  删除两方法与相关字段（`redstoneTicks / bytesSent / bytesReceived / bytesSentPerSec / bytesReceivedPerSec`）
  及 ServerCompat 的 `PAPER_REDSTONE_AVAILABLE / PAPER_NETWORK_AVAILABLE` 与两个检测方法；
  config.yml 两段整体注释并注明"v1.1 已下线（此前为空实现/假数据），不再被读取、可安全删除"
  （老用户旧键天然忽略，无迁移逻辑）；`/perfscope redstone` 保留为下线提示。
- **P0-9 服务端识别误判**（🟠）：原判定 `CraftServer` 类名含 "paper" —— 而 Paper 1.20.4 的类名是
  `org.bukkit.craftbukkit.v1_20_R3.CraftServer`，**Paper 被误判为 BUKKIT**。改为三层特征检测：标志类
  （Folia `RegionizedServer` → Paper 系 `PaperConfig / Configuration / PurpurConfig / PufferfishConfig`
  → `SpigotConfig`，Folia 优先判定）→ API 方法存在性（服务端类 `getTPS`，纯 CraftBukkit 落 SPIGOT）→
  兜底 `Bukkit.getName()`；新增 `isFolia()` 与 `FOLIA` 枚举。**类型判断降级为仅展示用途，
  功能一律走能力 flag** —— 本波最佳架构动作，此后任何核心误判都不再影响数据正确性。
- **P0-10 权限树断裂**（🟡）：`perfscope.admin` 名义管理但代码中一次未被检查、children 只含 use。
  补齐 children（use/reload/export/alert）；新增 `perfscope.alert`（默认 op）；
  命令侧改 `admin || 专项` 双保险；Tab 补全按权限过滤（无 use 返回空、report/reload 按权限提示）。
- **P0-11 报告转义/编码/覆盖三连**（🟠）：JSON 全部手拼字符串零转义 → 改用 **Gson**（2.10.1，provided；
  **键名与旧手拼版逐字一致**，unavailable 段输出 `{"status":"unavailable"}`，double 统一 `safeDouble`
  防 NaN 破坏 JSON）；HTML 加最小转义（`&<>"'`）+ 新增 `.card.unknown` 灰色样式；
  写盘 `FileWriter` 默认编码 → `Files.write(UTF_8)`（修中文 TXT 在非 UTF-8 系统乱码）；
  文件名精确到秒会互相覆盖 → `yyyy-MM-dd_HH-mm-ss-SSS` + `exists()` 计数循环防碰撞。
- **P0-12 monitor CME**（🟠）：`startMonitorTask` 遍历中直接 `remove()` 离线玩家，玩家一离线必抛
  `ConcurrentModificationException`。改显式 `Iterator.remove()` + `monitoringPlayers` 换
  `ConcurrentHashMap.newKeySet()` + 新增 `PlayerQuitEvent` 监听清理（双保险）+ `onDisable` 接上
  此前**全仓零调用**的 `command.shutdown()`。
- **P0-13 边界值哨兵**（🟡）：CPU 取不到（JDK 返回 -1）曾显示 `-100.0%` → 统一 `-1` 哨兵 + 告警加
  `> 0` 前提；`maxPlayers<=0` 除零 → 防呆（原会经 Infinity 误报 critical）；满 TPS 时 MSPT 反推的 0
  会被误读为"极好" → 改 `-1`；命令/报告层统一渲染：**模块级 unavailable → "不可用"，
  数值级 -1 → "未知"**（两层语义分明）。
- **P0-14 假 reload**（🟡）：`reload()` 原先只重读 YAML，`update_interval` / auto_export 开关改动不生效。
  收敛为 `startCollectionTasks / stopCollectionTasks / startAutoReport / stopAutoReport` 四个方法
  （为 P0-8 替换调度器预留**单一改动点**），reload 走 stop→load→start 完整重建；`getCommand` 加 null 保护。

### Notes
- **开发过程（多路并行 + 三维测试的第一次完整走通）**：4 路并行开发（文件集互斥：采集器 / 兼容+配置 /
  报告 / 命令+权限）→ 统一编译 BUILD SUCCESS → build/code/arch 三维测试**一致判 FAIL**：
  命令层漏消费 `unavailable`（唯一阻断项）→ 两路修复 agent 补齐命令层 19 处 + 报告详情段 → 复测通过。
  **教训：派活切分要按"契约的消费方"清点** —— 采集层产出、报告层消费、命令层整层被漏派。
- **三维测试报告**：`wave1-build.md` / `wave1-code.md` / `wave1-arch.md`。
  arch 结论：五层边界守住；Wave 2/3 四项预留全部到位（启停收敛方法 / `isFolia()` /
  `perfscope.alert` 契约 / Gson provided）。
- **提交**：`d0aac58`（哈希为历史改写后的最终值，见下一段说明）。
- **未验证（运行时）**：本波 JAR 记得**先别敲命令**做验收 —— 因为 P0-3 的 relocate bug 此时仍在，
  命令层在 Paper 上会崩（Wave 2 批次二才修）。本波只验证"加载不崩 + 启动日志正常"。

---

## [Unreleased] — 仓库整理与许可证迁移 - 2026-10-11

> 对齐 Hilltop Village 的两层工作区结构（**上级 = 管理目录，不入 git；内层 = 源码目录，入 git**），
> 把仓库从"什么都往里塞"整理为干净的入库清单；同期完成 GPL-3.0 → AGPL-3.0 迁移。

### Changed
- **工作区两层结构**：上级 `PerfScope/` 新增 `CLAUDE.md` / `AGENTS.md` / `rules/`（6 份，
  占位符已替换为 PerfScope 实际路径）/ `.claude/`（agents 5 个 mc-* + skills {prd, ralph} +
  workflows + settings.local.json）/ `.agents/` / `.codex/` / `.trae/`（旧路径修正）/
  `docs/`（9 分类 + README 索引）/ `logs/` / `ralph/`（prompt.md + **prd.json 15 个 P0 story** +
  progress.txt + retries.json）/ `ralph-plugin/` / `ralph.sh`（脚本本就是两层感知设计，一字未改）。
  内层迁出全部过程产物，最终入库清单收敛为 8 项：
  `src/ pom.xml README.md README-EN.md CHANGELOG.md LICENSE .gitignore`（+ 本地 `target/ versions/`）。
- **许可证迁移 GPL-3.0 → AGPL-3.0**：`LICENSE` 全文替换（661 行，逐字校验：以
  "GNU AFFERO GENERAL PUBLIC LICENSE" 开头、含 How to Apply 附录）；7 个 Java 文件头新增
  `Copyright (C) 2026 PerfScope Team` + `SPDX-License-Identifier: AGPL-3.0-only` 头
  （**方法体零改动**）；`pom.xml` 补 licenses 块；README / README-EN 换徽章 + 人话说明
  （允许商用须保留署名 / 二次分发须整体 AGPL / 网络服务须向用户提供源码）。
  **v1.0.0 及之前永久保持 GPL-3.0**（开源许可不可追溯撤销），自 v1.1 起 AGPL-3.0。
  作者名取 `plugin.yml` 既有的 `author: PerfScope Team`。
- **`.gitignore` 建立**：`/target`、`/versions/`、`*.jar`、`dependency-reduced-pom.xml` 等不入库
  （JAR 发布走 GitHub Releases，`versions/` 仅本地备份）；`git rm --cached` 移出此前入库的
  target/ 构建产物与 versions/ 历史 JAR（本地保留）；删除 maven-shade 副产物
  `dependency-reduced-pom.xml`。远程 `LICENSE.txt`（GPL）→ `LICENSE`（AGPL）。
- **开发计划文档**：`ROADMAP.md`（愿景 + v1.0→v2.0 共 49 条条目 + 35 条验收标准 + 三条红线）、
  `V1.1_DEV_TASKS.md`（15 个 P0 逐项：**问题行号逐个核实** / 方法级修改规格 / 验证步骤 / 实施波次 /
  DoD / 测试矩阵）、`V1.2_DIAGNOSTIC_RULES.md`（三态健康模型 + SRS 0-100 评分 + **37 条诊断规则** +
  趋势口径 + `/ps diagnose` 包大纲）、`V1.3_ALERT_SPEC.md`（Alert 值对象 + 五态状态机 + 防抖 +
  4 通道载荷示例 + **30 个配置键** + 每日日报 + A1-A13 验收清单）—— 均归档于上级 `docs/06-tasks/`。

### Notes
- **历史改写说明**：整理期 3 个提交的历史尾注含 AI 工具署名行（会在 GitHub Contributors 列出第三方），
  按作者要求用 `git filter-branch --msg-filter` 删除并以 `--force-with-lease` 推送；
  新哈希 `f91985f / ba40e0b / d0aac58` 取代旧 `bf222fb / e019e29 / 0be2e7d`。
  **此后所有提交不再带该尾注**。
- **待办登记**：`plugin.yml` 的 `version: 1.0.0` 为硬编码，未走 Maven filtering 的 `${project.version}`
  （与 `version-release-rules.md` 的"单一版本源"要求不符，下次发布前改造）。
- **提交**：`f91985f`（规范化整理）、`ba40e0b`（versions/ 移出版本控制）。

---

## [1.0.0] - 2026-06-07

> 1.0.0 首发版本内容（2026-06 开发周期）。本节为**历史存档，记录保持原样** ——
> 其中"红石活动追踪 / 网络吞吐量监测"两项已于 v1.1 下线（P0-7，详见上方 Wave 1 段）。

### Added
- Spigot/Bukkit 兼容层 (ServerCompat)：自动检测服务端类型 (Paper/Spigot/Bukkit)，标记各功能可用性，启动时输出服务端类型和功能可用性报告
- MSPT 三级降级采集：Paper TickTimes API → Paper getAverageTickTime() → Bukkit TPS 反推估算，确保非 Paper 服务端也能获取 MSPT 数据
- TileEntity 采集兼容：Paper 使用 World.getTileEntityCount()，Spigot/Bukkit 降级为遍历 Chunk.getTileEntities()
- Adventure API 自动 shade：pom.xml 添加 adventure-api/adventure-text-minimessage/adventure-platform-bukkit 依赖并 relocate 到 cn.perfscope.lib，确保 Spigot/Bukkit 下 Adventure API 可用（**注：此 relocate 即 v1.1 P0-3 致命 bug 的根源，此处保持记录原样**）
- 服务端信息显示：全量诊断头部新增"服务端: Paper/Spigot/Bukkit"信息
- 非 Paper 下 MSPT 标记"(估算)"：全量诊断输出中，当 MSPT 来自 TPS 反推时显示估算标记
- 启动信息增强：verbose 模式显示各功能可用性 (MSPT采集/ActionBar/红石追踪/网络统计)
- README.md 和 README-EN.md 中英文项目文档：含功能特性、命令表、全量诊断输出示例、权限表、安装指南、配置说明、可监测指标表
- 初始版本发布：完整的服务器性能监测插件框架
- TPS 监测：1m/5m/15m 实时采集，支持 warning/critical 两级告警阈值自定义
- MSPT 监测：avg/min/max 三指标采集，通过 Paper API 反射获取 TickTimes 数据，理论最大 TPS 推算
- 玩家数统计：在线数/最大数/按世界分布/延迟(Ping)，支持容量告警
- 实体统计：总数/按世界/按类型 Top10，每个世界可独立设置告警阈值
- 区块统计：加载区块数/强加载区块数/按世界分布，支持独立告警
- 内存监测：堆内存使用/上限/使用率/已分配，非堆内存使用，GC 次数/耗时/各收集器详情
- CPU 监测：进程 CPU 使用率/系统 CPU 负载/可用处理器数/活跃线程数
- 红石活动追踪：Paper 服务端红石线活跃计数（v1.1 已下线，见上）
- Tile Entity 统计：总数/按世界分布
- 网络吞吐量监测：每秒收发字节数，支持流量告警（v1.1 已下线，见上）
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

### Changed
- 插件描述更新：兼容 Paper/Spigot/Bukkit 全系列，Paper 上获完整功能，Spigot/Bukkit 部分功能降级（v1.1 起调整为专注 Paper 系）
- ActionBar 实时监测：非 Paper 服务端使用时会提示"需要 Paper 服务端支持"
- 全量诊断命令重构：`/perfscope`（无参数）或 `/perfscope all` 一键输出所有核心指标，输出格式参考 Hilltop Village `/hilltop tps` 风格（金色粗体标题 + 黄色标签 + 颜色编码值 + 按世界服务器级诊断 + 告警 + 建议）
- 原 overview 面板移除，改为全量诊断 showAll() 方法：TPS/MSPT/玩家/实体/区块/内存/CPU/GC/红石/网络/运行时间 全部在一屏展示
- 专项检测子命令拆分：tps/mspt/entities/chunks/memory/cpu/players/world/redstone/tileentities/uptime/gc 各自独立，每项输出更详细的专项信息
- entities 专项检测：实体类型 Top10 升级为 Top15，增加百分比显示，增加告警提示
- cpu 专项检测：增加 JVM 线程数，进程/系统 CPU 颜色编码阈值调整
- uptime 专项检测：新增 JVM 版本和供应商信息
- memory 专项检测：增加堆内存使用率进度条，内存使用率 >= 90% 时输出优化建议
- 帮助面板更新：新增 all 命令，overview 移除，所有命令均可点击跳转
