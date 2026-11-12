# Changelog

本项目：**PerfScope** —— Minecraft 服务器性能监测插件（Paper 1.20.4+ / Purpur / Pufferfish / Folia / Leaves，Java 17）。
后台定时采集性能快照 → 游戏内 17 个子命令一屏诊断 → HTML/TXT/JSON 报告导出 → ActionBar 实时监测 → 阈值告警。

> **仓库**：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope> ｜ **版本**：`1.3.0`（当前正式版；最新 tag 与 `versions/PerfScope-1.3.0.jar` 均为 1.3.0；`[Unreleased]` 累积 v1.4 未发布改动）

格式参照 [Keep a Changelog](https://keepachangelog.com/)。v1.2「找得到」开发周期已收尾，各段以 **Wave（开发波次）** 划分并标注对应提交哈希。

> **★ 硬规则（项目约定）**：任何代码 / 配置 / 文档的更改，都必须记入本 CHANGELOG（在最上方按
> Added / Changed / Fixed / Removed / Optimized / Refactored / Notes 分类追加）；**编译前先更新**。
> 版本号只在用户明确说"更新版本号"时 bump（Patch = Bug 修复，Minor = 新功能）。
> **本文件是开发流水账** —— 记"改了什么、为什么改、验证到哪一步、还欠什么"，不是给用户看的发布说明。

---

## [1.3.0] — 2026-11-10 — 变更汇总（面向使用者）

> 本节是给使用者看的：只有**改了什么、有什么影响**。过程与过程性记录在上层工作区
> `docs/reports/`，不在本仓库。按影响面从大到小排列喵。
>
> **⚠️ 本节混装了已发布与未发布内容**。标了「v1.4 未发布」的小节**不在** `v1.3.0`
> 的任何发布包中（既不在 git tag `v1.3.0`，也不在 `versions/PerfScope-1.3.0.jar`）——
> 它们对应 `pom.xml` 的 `1.3.0` 之后、`[Unreleased]` 区段累积的 v1.4 改动，目前的
> `1.3.0` 归档 JAR 里查不到 Prometheus 端点、`/perfscope webhook` 子命令与
> 企业微信 / 飞书 / 钉钉通道。要用这些功能只能自己从源码构建喵。

### v1.4 未发布 — 逐告警 Prometheus 指标

> **v1.4 未发布**：本小节不在 v1.3.0 的任何发布包中，需自行从源码构建喵。

- **`perfscope_alert{key,module}`**：每条可判定的告警键一条序列，
  值 `0` 正常 / `1` 警告 / `2` 严重喵。
  - **级别走值不走标签**。这是刻意的：若做成 `{severity="warning"}`，级别升级时
    Prometheus 看到的是「一条序列 stale、另一条全新」，`ALERTS_FOR_STATE` 归零——
    运维写的 `for: 5m` 每次级别翻转都重新计时，插件那套抖动治理在指标层被完全抵消喵。
    做成值则序列恒定，升级只是 `1 → 2`喵。
  - **健康也输出 `0`**。否则恢复时序列直接消失，既收不到 `resolved` 通知，
    也无法用 `absent()` 区分"没告警"与"采集挂了"喵。
  - **不可判定的维度不发 0**（模块被禁用 / 平台不支持 / 哨兵值 `-1`）。
    给没测的维度发 0 等于宣称它健康；这类盲区由既有的
    `perfscope_module_available{module} 0` 表达喵。
  - 有了它，运维可以直接写 `perfscope_alert > 0` 把告警接进自己的
    Alertmanager / Grafana，不需要本插件再造一套 Alertmanager 语义喵。
- 默认 Prometheus 端口 **9940 → 8940**。9940 在 Prometheus 官方端口分配表里已分配给
  `minecraft-prometheus-exporter`（`papermc_prometheus_exporter` 也占用它），
  而 9941/9942/9943 同样已被占用；8940 在 IANA 与 Prometheus 两张表中均未分配喵。
  端口被占时日志会直接点名冲突对象并给出替代值喵。

### v1.4 未发布 — 告警状态加载顺序

> **v1.4 未发布**：本小节不在 v1.3.0 的任何发布包中，需自行从源码构建喵。

- **关掉告警功能的服务器会永久虚报告警**。`AlertService.start()` 原先先 `loadState()`
  再从磁盘回灌上次运行的告警，**之后**才检查 `alerts.enabled`——于是关掉告警的服会把
  旧告警装进内存表，而 `onSnapshot` 入口就早退，这些告警**既不会更新也不会被解决**，
  永久污染 `/metrics` 与 `/perfscope status` 的告警计数喵。
  现在先判开关再加载；磁盘上的 `alerts.state` 不会丢（`persistState()` 以
  `stateLoaded` 为写盘前提，跳过读取即跳过写盘）喵。

### Fixed — 会让用户看到错误结论的问题

- **在非 Paper 系服务端上装上去会半装配后崩溃**。README 与 `plugin.yml` 都只声称支持 Paper 系，
  但 `onEnable` 里没有平台守卫：在纯 Bukkit/Spigot 上会走完全部初始化，然后在第一个 Paper 专有
  API 处崩在半装配状态（部分模块起来了、部分没起来，错误指向某行代码而不是"平台不对"）。
  现在启动第一步就检查平台，不受支持时打印双语说明并禁用本插件喵。
- **`general.update_interval` 没有上限，填 1 等于每 tick 全量采集**。`MetricsCollector` 只有
  下限 `Math.max(1, ...)`，那个下限恰好放行了最危险的值——20 次/秒的主线程全遍历，大服上足以
  把 TPS 拖垮。现在钳制到 5–1200 tick，越界时回退 20 并打一条配置校验告警喵。
- **bStats 隐私披露不准确**。此前写成"10 项均为分档数据"，但 bStats 库的**基础遥测**本身会发
  **精确在线玩家数**（未分档）、操作系统版本、CPU 核心数等，那部分本插件无法逐项控制。
  现在 README 分两层披露：A 层是 bStats 库自动上报（列明精确值），B 层是本插件的自定义图表（已分档）喵。
- **单世界实体视图会打出全服级告警**（`/perfscope entities <world>`）。只要全服实体总数过线，
  单世界页就会在那个世界的小数字下面打一条「严重警告」。现在单世界视图改判**该世界自己的**
  实体数与覆盖阈值，无覆盖时回退全局；世界不在统计里视为 0 实体、不报警喵。
- **ActionBar 在采集停摆后继续显示"健康"读数**（`/perfscope monitor`）。采集任务被取消后
  快照不再更新，旧实现会让 ActionBar 永远渲染同一组正常值。现在检测到数据过期就改为提示
  「已 N 无数据——采集已停止」喵。
- **`/perfscope trend 7` 可能把 24 小时数据标成 7 天**。文件持久化不可用时会回退到内存环，
  而内存环只覆盖约 24 小时。现在天数上界跟数据来源走：可用则按保留天数，不可用则收窄为 1 天，
  并明说收窄原因喵。
- **报告清理会删掉管理员自建文件**。`reports/` 里形如 `perfscope-notes.txt`、`perfscope-2026.html`
  的自建文件超期后会被当成本插件产物删除。现在按完整时间戳格式（含字段值域）判定归属，
  只删自己生成的文件；插件换名重试产生的 `-1` / `-10` 后缀仍正确识别喵。
- **字节数在进位边界显示成 "1024.0 KB"**。`1048575` 显示 `1024.0 KB`、`1073741823` 显示
  `1024.0 MB`，看起来像该进位却没进位。现在换档判据与输出精度同口径，显示 `1.0 MB` / `1.00 GB` 喵。
- **HTML 报告中用户自定义文案会被双重转义**。在 `messages.yml` 里把文案改成含 `&`、`<`、`>`
  的内容时，报告里会显示 `&amp;lt;` 之类。现在转义只做一次喵。
- **HTML 报告在 MSPT 不可用时可能崩溃**。非 Folia 服务端上原因字段为空，渲染时抛 NPE。
  现在三档成因（配置禁用 / 平台不支持 / 采集异常）分别显示，且不再崩溃喵。
- **告警恢复后可能被静默吞掉**。接管与静默分支漏写冷却表，导致下一周期立刻重推、与
  「不重复推送」的日志矛盾。两处已同改喵。

### Fixed — 配置与调度

- **CPU 告警可能永久不解除**。当 `cpu.process_warning_threshold` 小于 `cpu.hysteresis_margin` 时，
  解除条件变为「值 ≥ 负数」恒真，闩锁进入后永不解除。现在迟滞带为非正时退回无迟滞判据，
  并在配置校验时回退越界的迟滞值喵。
- **历史写线程死亡后永久静默**。线程异常退出后写入队列继续累积、丢弃计数无任何输出。
  现在有界自动重建（最多 3 次），且 `/perfscope status` 能如实报出喵。
- **`alerts.refire-min-interval-seconds: 0` 的行为与注释相反**。注释写「0 = 关闭抑制」，
  代码却回退到冷却值（抑制更强）。现在 0 表示不再识别复燃，再次越界按普通新告警处理
  （仍受 `cooldown-seconds` 约束）喵。
- **TPS 告警阈值可设到 25 之类的不可能值**。稳态 TPS 上限为 20.0，阈值高于它就恒真。
  现在超过 20.5 会回退默认并警告；落在 20.0–20.5 之间会提示该阈值会让规则在健康服务器上命中喵。
- **`history.sample-interval-seconds` 设得过大时趋势规则永久跳过而不说明原因**。
  现在会明确打出「该间隔下 30 分钟窗口内样本不足」的警告喵。
- **`config.yml` 写回非原子**。写入中途被强杀会留下半写的配置文件。现在走临时文件 + 原子替换喵。
- **Folia 上调度失败被当成"已启动"**。告警与每日摘要会照打「已启用」而实际永久停摆；
  现在会明确报错喵。
- **控制台日志仍然混排中文**。`general.language: en` 的服务器上，webhook 配置校验、
  SSRF 拒绝原因、MSPT 能力降级、配置钳制告警等 **40 处**控制台输出仍写死中文，
  与界面语言不一致。现在全部改走消息键表（新增 36 个键），`language: en` 下控制台
  零中文；`language: zh` 的输出与改造前逐字一致喵。

### v1.4 未发布 — 企业微信 / 飞书 / 钉钉群机器人

> **v1.4 未发布**：本小节不在 v1.3.0 的任何发布包中（归档 JAR 的 `config.yml` 里
> `alerts.webhook.type` 只有 `discord | onebot`，没有 `secret` 键），需自行从源码构建喵。

- **支持企业微信 / 飞书 / 钉钉群机器人**（`alerts.webhook.type` 新增 `wecom` / `feishu` / `dingtalk`）。
  飞书与钉钉支持可选的 `alerts.webhook.secret` 加签喵。
  - 三种平台各自的请求格式与判据：企业微信 `msgtype`+`text.content`（2048 **字节**）、
    飞书 `msg_type`+`content.text`（签名为反直觉的"key=待签名串、数据为空"构造，时间戳用**秒**）、
    钉钉 `msgtype`+`text.content`（签名拼在 URL query，时间戳用**毫秒**）喵。
  - 老配置零改动可用：`type: discord` / `type: onebot` 行为不变，缺 `secret` 键走默认空串喵。

### v1.4 未发布 — webhook 投递结果判定 / 自检 / 限流

> **v1.4 未发布**：本小节不在 v1.3.0 的任何发布包中，需自行从源码构建喵。

- **webhook 投递失败却显示成功**（本批最重要的修复）。企业微信 / 飞书 / 钉钉在**业务失败时也返回
  HTTP 200**，错误码在响应体里；而旧实现用 `BodyHandlers.discarding()` 丢掉响应体、只看
  `statusCode >= 300`，于是这三家的每一次失败都被当成成功喵。
  - **这个 bug 在 OneBot 上早已存在**：OneBot v11 的 `/send_private_msg` 恒定返回 HTTP 200，
    成败写在 `retcode` 里。也就是说配错 OneBot token 的用户从 v1.2 起就没收到过任何告警，
    也没有任何日志提示喵。
  - 现在按平台读取业务码：`errcode == 0`（企业微信/钉钉）、`code == 0`（飞书）、
    `retcode == 0` 或 `status == ok/async`（OneBot）喵。
  - **解析不出内容一律判失败**，不再"看不懂就当成功"——反向代理返回 HTTP 200 的 HTML 登录页
    会让投递永久显示正常，这正是要避免的喵。
- **OneBot 侧缺少 CQ 码注入防护**。Discord 早就加了 `allowed_mentions.parse = []` 防空 `@everyone`，
  但 OneBot 分支没有对应措施：实现会把字符串 `message` 当 CQ 码解析，告警正文里的世界名/MOTD
  若含 `[CQ:at,qq=all]` 就会 **@ 全体成员**。现已加 `auto_escape: true`喵。
- **续读响应体会放大内存**。改用读取响应体后补了 4 KB 上限——旧实现的 `discarding()` 顺带保证了
  不会因远端返回巨大响应而 OOM，改成读取后这个保护必须显式补回喵。

#### webhook 自检与限流

- **`/perfscope webhook status | test`**。三个国内平台的失败模式全是静默的（HTTP 200 + 业务码），
  服主唯一的观测渠道是"告警群一直没消息"，而"服务器健康所以没告警"和"告警根本没送到"看起来一样喵。
  - `status` 展示通道类型、是否加签、最近一次投递结果、以及被限流丢弃的条数喵。
  - `test` 真的发一条探测消息（正文带 `test message - safe to ignore` 标记），
    并回报 HTTP 状态 + 平台业务码 + **可执行的处置建议**（不是只报错误码）喵。
  - 结果同时写控制台日志：RCON 会话执行完命令就断开，之后 `sendMessage` 不会送达——
    只发消息会让这个命令在 RCON 下表现为"没反应"，而那正是它要消灭的东西喵。
- **客户端限流**。企业微信与钉钉都是 20 条/分钟，**钉钉超限还会封禁 10 分钟**——
  一次告警风暴会让服主在最需要告警的时候静默 10 分钟喵。
  - 间隔取值：企业微信/钉钉 4000ms（15 条/分）、飞书 700ms（约 85 条/分）、Discord 2000ms、
    OneBot 不限喵。
  - **不做突发容量**：钉钉的限额是"任意 60 秒窗口 ≤ 20 条"，容量 C、间隔 T 的最坏窗口是
    `C + floor(60000/T)`。实测 3000ms + 容量 2 就是 22 条，会触发封禁；4000ms 才留出余量喵。
  - 被丢弃的条数在 `/perfscope webhook status` 可见——这是服主发现自己告警被节流的唯一信号喵。

#### webhook 计数口径

- **`webhookQueuedToday` 把没发出去的消息也记成"已排队"**。旧代码用
  `webhookQueued = wn.isReady()` 判断，而 `isReady()` 只回答"配置齐了没有"——
  被限流丢弃、序列化失败、URL 构建失败时它仍为 true喵。现在改用 `send()` 的返回值喵。
- **一条 digest 会把计数加 N**。`webhookQueuedToday++` 原先在按告警条数循环的循环体内，
  一次 HTTP 请求覆盖 8 条告警就加 8喵。计数单位应是"发出的消息条数"，已移出循环喵。
  （`pushOkToday` 仍按告警条数计——两者刻意不同：一个统计"处理了多少条告警"，
  一个统计"发出了多少条消息"喵。）

### v1.4 未发布 — Prometheus 指标端点

- **Prometheus 指标端点**（`prometheus.*`，默认关闭）。用 JDK 自带的
  `com.sun.net.httpserver` 实现，**零第三方依赖**、不增加 jar 体积，让指标脱离游戏内命令
  进入你已有的监控栈（Prometheus 抓取 → Grafana 看板）喵。
  - 默认 `127.0.0.1:9940/metrics`：只听回环。改成 `0.0.0.0` 时启动会打 WARNING，
    因为端点会暴露在线玩家数、世界名与插件数量喵。
  - 可选 `auth-token`：非空时要求 `Authorization: Bearer <token>`，错误令牌返回 401
    且响应体不含任何版本信息喵。
  - 除 TPS/MSPT/内存/实体/区块等基础指标外，还导出**本插件独有的诊断信号**：
    `perfscope_module_available`（哪个采集模块坏了）、`perfscope_alerts_active`、
    `perfscope_alerts_suspended`、`perfscope_collector_degraded`、
    `perfscope_snapshot_age_seconds`（区分"服务器死了"和"指标冻住了"）喵。
  - 未采集的指标**整行不输出**，不输出 `-1`——缺行让 Grafana 显示 gap，
    比画一条贴底的假线诚实喵。
  - Folia 上 `perfscope_build_info` 带 `tps_scope="global_region"` 标签，
    明示那里的 TPS 只是 global region 的局部值喵。
  - 端口被占、`jdk.httpserver` 被裁剪、绑定权限不足——一律只记一条 warning 并跳过，
    **绝不影响插件本体启动**喵。

### Added — 新配置键

- `prometheus.enabled` / `bind` / `port` / `path` / `server-id` / `auth-token`（默认全关、
  `127.0.0.1:9940/metrics`、无鉴权；**v1.4 未发布**，不在 v1.3.0 发布包中）喵。
- `alerts.refire-min-interval-seconds`（默认 60）：复燃识别窗口，`0` = 关闭复燃识别喵。
- `hotspots.scan-shards`（默认 10）：热点分区增量扫描的分片数，`1` = 关闭分片（旧的一次性全遍历）喵。
- `general.language`、`cpu.hysteresis_margin`、`alerts.flap-window-seconds`、`alerts.repush-delta`
  等此前已在代码中生效但未在 `config.yml` 中登记的键，现已全部补上注释说明喵。

### Changed — 行为变更（升级时注意）

- **热点区块扫描改为分区增量**。旧实现每 30 个采集周期做一次「全部世界 × 全部已加载区块」的
  全遍历，11,047 区块实测单次 40.5 ms（50,000 实体档 106 ms，超 2 tick）。现在把同一轮扫描
  切成 `hotspots.scan-shards` 片（默认 10），摊到多个采集周期完成，实测空闲服上采集
  `max` 从 78 ms 降到 41 ms、超 1 tick 的周期从 2 次降到 0 次。一轮覆盖的区块集合与旧版
  完全相同，`/perfscope hotspot` 的 Top3 内容不变（harness 9/9 PASS）喵。
  - **代价**：单轮扫描横跨 10 个采集周期，同一批区块的刷新粒度从「一次性 30 秒」变为
    「每 3 秒推进一片、30 秒完成一轮」。轮次之间复用上一轮的完整结果，所以除首轮外
    **不会**看到每片刷新的抖动榜单喵。
  - `hotspots.scan-shards: 1` 可退回旧的一次性全遍历（对照或排查用）；越界 1–100 会钳制并告警喵。

- **Windows 上不再采集进程 CPU**。`getProcessCpuLoad()` 的 Windows 实现每次调用都要枚举
  全部进程名，本机实测单次阻塞 **p50 171 ms / p90 257 ms / max 332 ms**（90 秒内 69 次调用
  累计阻塞 12.7 秒，约占该异步 worker 的 14.1%）。而它跑在 Bukkit 的**共享**异步池上
  （`runTaskTimerAsynchronously`），阻塞会挤占同池内其他插件的异步任务喵。
  - **现在的行为**：Windows 上进程 CPU 一律显示「未知」，`cpu.process_warning_threshold`
    不会触发；系统 CPU（`getCpuLoad()`，实测 p50 0.006 ms）照常采集与告警喵。
  - **不受影响**：Linux / macOS 读 `/proc` 或等价接口，是微秒级，进程 CPU 正常采集喵。
  - **为什么宁可报未知**：报一个诚实标为「未知」的数，好过为了取它让一个共享 worker
    每次阻塞 171 ms。判定集中在 `ServerCompat.isProcessCpuReliable()` 喵。

- **接管与静默路径的冷却语义**：告警被接管或进入静默后，现在会静默**一整个冷却周期**
  （默认 300 秒），而不是 1 秒内重推喵。
- **`/perfscope status` 的采集异常改为脱敏摘要**：只显示异常类型与首句，绝对路径替换为 `<path>`。
  完整原文仍在控制台日志喵。
- **`trend` 在无持久化时最多查 1 天**：此前会接受更大天数但数据实际只有 24 小时喵。

### Notes — 兼容性

- 所有新增配置键均为**追加**，旧 `config.yml` 可直接使用；缺失键走默认值喵。
- `plugin.yml` 版本号由 Maven filtering 从 `pom.xml` 注入，无需手工同步喵。
- bStats 匿名统计项目 ID 已注册（35061），**数据已实际上报**；上报内容为聚合分布，
  不含配置阈值、玩家名、世界名或聊天内容。可在 `general.bstats-enabled: false` 关闭喵。
- **Windows 上进程 CPU 不再可用**（见上方 Changed）。判定与实测理由集中在
  `ServerCompat.isProcessCpuReliable()`；Linux / macOS 不受影响喵。
- 本次性能结论与完整证据链见 `docs/06-tasks/research/v140-01-cpu-async-blocking.md`喵。

### v1.4 未发布 — 文档：容器 / 面板服的抓取说明

> **v1.4 未发布**：本节描述的是 v1.4 的 Prometheus 部署文档（README 中英双语），
> 同样不在 v1.3.0 的任何发布包中喵。

- **删掉一句错的配置注释**。原 `config.yml` 写着「面板服（MCSManager / Pterodactyl）
  常无法开放入站端口，那种环境用 push 模式更合适」——这句话是错的，而且指向一个
  不存在的功能喵。
  - **前提是错的**：Pterodactyl/Wings 通过 Docker `HostConfig.PortBindings` 真的发布
    allocation 端口（源码 `environment/docker/container.go`），`DockerBindings()` 还会把
    `127.0.0.1` 的绑定改写到 `pterodactyl0` 网桥（默认 `172.18.0.1`）。加端口是管理面板里
    约三次点击。MCSManager 同样发布端口；AMP 实例通常以原生进程运行、根本没有容器网络喵。
    抽样 93 份服务商文档，写死「不支持额外端口」的约 2%喵。
  - **push 模式不存在**，也没有加入计划喵。
- **新增「容器 / 面板服：先把 bind 改掉」小节**（README 中英双语）。真正的头号故障是
  **绑错地址**，不是「开不了端口」：容器里 `bind: 127.0.0.1` 指的是容器自己的回环，
  容器外的 Prometheus 永远连不上。上游同类插件的三个 issue（#36 / #123 / #275）
  **全是这一个原因**喵。
  - 给出判定方法（外面 `connection refused`、容器内 `curl 127.0.0.1` 却 200，这个反差就是证据）、
    按部署方式的对照表、Pterodactyl 的三步操作、以及两个反直觉点
    （`allocation_limit` 为 `NULL` 时客户端看不到按钮；抬高上限后还需面板全局开启
    「Automatic Allocation Creation」，该项默认关闭；Wings 绑的是分配项里记录的那个 IP
    ——可能是具体地址也可能是 `0.0.0.0`，记的是具体非回环地址时宿主机自己的 `localhost`
    反而可能不通）喵。
  - 同时写明「设了 token 却忘了带 `Authorization: Bearer` 头」的症状：插件返回 401，
    而 Prometheus 把任何非 200 都记成抓取失败，表现为 target 显示 DOWN、无可读原因，
    看着像网络故障喵。另有 `--network host` 与 `kubectl port-forward` 两个例外，
    这两种情况 `127.0.0.1` 就够用，不必放大 bind 白白丢掉回环保护喵。
  - 对确实无法开端口的那约 2%，明确写「本插件没有 push 模式，也没有计划」，
    并指向已有的 webhook 告警作为替代——告警由服务器主动发出，不需要入站端口喵。

### v1.4 未发布 — 文档：版本口径修正（README / CHANGELOG / versions）

> 起因：README / CHANGELOG 一边把「最新正式版」写成 `1.2.1`，一边用大段篇幅描述
> Prometheus 端点、`/perfscope webhook` 与企业微信 / 飞书 / 钉钉——而这些**不在任何
> 已发布的包里**（tag `v1.3.0` 与 `versions/PerfScope-1.3.0.jar` 都没有）。同时 README
> 没写「最新正式版是 1.3.0」。对一个监测插件来说，文档承诺了下载不到的软件就是信任问题，
> 本次把这些口径全部对齐喵。

- **README 安装段（中英双语）**：`> **下载**` 行的「最新正式版 v1.2.1」改为 **v1.3.0**
  （`PerfScope-1.3.0.jar`），并在其下加一行「本文档同时描述了尚未发布的 v1.4 内容」的
  总说明喵。
- **README 的 v1.4 功能小节**：Prometheus / Grafana 接入节、配置段的 `webhook.secret` /
  `wecom|feishu|dingtalk` 通道、功能列表的 Webhook 推送条目，均加「**v1.4 未发布**」
  标记，写明不在 v1.3.0 任何发布包中、需自行从源码构建喵。
- **CHANGELOG 头部**：`1.2.1（当前正式版）` 改为 `1.3.0（当前正式版）`，并注明最新 tag 与
  `versions/` 归档均为 1.3.0喵。
- **CHANGELOG 的 1.3.0 汇总节**：把混装的 v1.4 小节（逐告警 Prometheus 指标、企业微信 /
  飞书 / 钉钉通道、webhook 自检 / 限流 / 计数口径 / 投递判定、容器抓取文档）显式标为
  「v1.4 未发布」，与已发布内容区分；顶部加总说明喵。
- **`versions/README.md`**：修正了三处与归档 JAR 实际内容不符之处喵。
  - 1.3.0 条目写着「11k 区块实测 p95 54→29ms、超 tick 周期 7.6%→0%」——这组数字在仓库
    任何实测记录里都找不到。真实 A/B（`docs/06-tasks/research/v140-01-hotspot-sharding.md`
    §4.2，11,047 区块空闲服、同一 JAR 只改 `scan-shards`）是 **p95 33→30ms（噪声内）、
    max 78→41ms、超 1 tick 周期 2/300→0/300**。已改为实测值喵。
  - 键数写成 **724**，但 `check-i18n.mjs` 对 tag `v1.3.0` 源码实测是 **725**（`docs/…/
    v130-07-release-materials.md:34` 也记 724，属同源笔误）。已改为 725 并标注来源喵。
  - 表格每行的 GitHub Releases 链接单独占一行且无 `|`，markdown 会把它渲染在表格外。
    已合并进同一单元格喵。
  - 补上「1.3.0 归档早于 v1.4 的 Prometheus 工作」与「为什么 `target/` 里也有一个同名
    1.3.0 但内容不同」两节；英文段此前仍推荐 1.2.0，一并更新为 1.3.0喵。

### 升级注意 — 从 1.2.x 升到 1.3.0

- **`messages.yml` 的生成方式变了**。1.2.1 及之前是从 jar 内的 `messages.yml` 资源复制，
  默认文案是中文；现在**默认文案以代码内置表为唯一权威**，`messages.yml` 只在首次运行时
  按当前语言生成，jar 内不再包含该资源。旧文件会被备份为 `messages.yml.bak-<时间戳>` 后重建喵。
- **默认语言变为英文**（`general.language` 可切换 `en` / `zh`）。升级后若你的界面变成英文，
  把 `general.language` 设为 `zh` 即可喵。
- **LICENSE / NOTICE 现在打进 jar**（`META-INF/`）。这是 bStats（MIT）的署名义务与
  AGPL-3.0 第 4/6 条的要求，1.2.1 及之前的包内**没有**这两个文件喵。
- **非 Paper 系服务端现在会在启动第一步被拒绝**（打印双语说明并自我禁用），
  而不是走完全部初始化后崩在半装配状态喵。
- `versions/` 目录只增不删，1.2.1 及更早的归档保留原样，不会因本次发布被覆盖喵。

> **开发计划文档索引**（均在上层工作区）：`docs/06-tasks/ROADMAP.md`（愿景 + 49 条条目 + 35 条验收标准）、
> `V1.1_DEV_TASKS.md`（15 个 P0 逐项拆解）、`V1.2_DIAGNOSTIC_RULES.md`（37 条诊断规则）、
> `V1.3_ALERT_SPEC.md`（告警中心规格，30 个配置键）。
> **审查报告**：`docs/01-reviews/`（15 份并行审查 + 总合成 `v11-review-SYNTHESIS.md`）。
> **测试报告**：`docs/05-test-reports/`。

---

## 1.3.0 流水账索引（按时间倒序）


> 上一节是**面向使用者的汇总**。下面 13 节是**开发流水账**（按 Keep a Changelog 的硬规则保留，
> 供追溯"改了什么、为什么改、验证到哪一步"）。它们原挂在 `[Unreleased]` 下，随 v1.3.0
> 发布统一改为 `[1.3.0] — 2026-11-10`，各节的批次划分保持不变以保留可追溯性。

| # | 段落 | 内容 |
|---|---|---|
| 1 | Wave 11 | 五波次多 Agent 审计（round1）：静默失效与口径分裂 |
| 2 | UPHELD 剩余 10 条清零 | 2026-11-04 |
| 3 | UPHELD 全集核对 | 41 条清单本身漏了 19 条，已补修 |
| 4 | 全部欠账清算 | 规则 10 真数据回放 + 建 `src/test` + 残留风险闭合 |
| 5 | 第五波次验收（补跑） | 1 条 FAIL + 3 条低危遗留，全部处置 |
| 6 | round2 收尾批 | 6 条产品语义裁决全部落地 |
| 7 | round2 | 把 round1 遗留的 41 条 UPHELD 收掉 22 条 |
| 8 | v1.3.0 门禁前置修复 | 红队审查后的 9 项 |
| 9 | round1 修复收尾 | JSON 成因区分 + 报告超时告警本地化 |
| 10 | Wave 10 | bStats 自定义图表：把"有没有真实用户"变成可验证信号 |
| 11 | Wave 9D（命令层） | i18n：命令层正文 + 报告 + 告警信封 + 诊断规则 |
| 12 | Wave 9D（机制） | i18n 机制（Phase 0，不含命令层/告警翻译） |
| 13 | Wave 9C-4 | 停止刷屏 |

---

## [1.3.0] — 2026-11-10 — Wave 11「多 Agent 极限审计（round1）：静默失效与口径分裂」

> **背景**：采用 `docs/framework/` 的五波次审计流水线，对全仓 35 个源文件做了一轮
> 「侦察 → 审计 → 锐评 → 修复 → 验收」的对抗式审计。24 个审计实例（6 切片 × 4 维度）
> 共产出 160 条带证据的发现，经 8 个锐评实例（6 Skeptic + Devil's Advocate + Arbiter）
> 撤销 25 条、降级 66 条、合并 10 组，最终收敛为 9 个修复单元。
>
> **本轮的主线不是"崩溃"，而是"代码说的和代码做的不一样"**：两条最终 HIGH 都不是程序崩了，
> 而是系统静默地做错事且没人知道。

### Fixed
- **CPU 告警闩锁永不解除**（`f-s04-logic-01` + `f-s03-logic-03` + `f-s04-conc-04`，HIGH）。
  `latched()` 的退出判据是裸减法 `value >= threshold - margin`，而阈值与迟滞带宽度被独立校验。
  当 `cpu.process_warning_threshold < cpu.hysteresis_margin` 时退出线为负，喂入值被
  `if (snap.processCpuLoad > 0)` 保证恒正 → `value >= 负数` 恒真 → 闩锁一旦进入永不解除，
  且 `exitThreshold` 以负数渲染给用户。
  - 采集侧（`MetricsCollector.latched`）：**单纯把退出线钳到 0 无效**（`value >= 0` 对正数恒真），
    改为「迟滞带非正时退回无迟滞判据 `value > threshold`」。
  - 配置侧（`ConfigManager.validateThresholds`）：新增交叉校验，`margin >= min(两阈值)` 时回退并
    WARNING；回退值取 `min(5.0, 两阈值较小者/2)` 而非默认 5.0（阈值为 3 时 5.0 仍越界）。
    `margin == 0` 不拦（`config.yml:121` 明写「0 = 关闭迟滞」）。
  - `MetricsCollector.start()` 重置清单补入 `systemCpuLatched` / `processCpuLatched`。
- **历史写线程死亡后永久静默**（`f-s04-err-02`，HIGH）。`writerLoop` 的 `catch` 在 `while`
  **之外**，其后无条件 `drainAndClose()` 并返回，全类仅 `:188` 一处 `new Thread`。线程因 `Error`
  逃逸后 `running` 仍为 `true`，`append` 继续入队，队列满后只静默累加 `droppedSamples`——
  而唯一输出该计数的 `reportDropsThrottled` 三处调用点**全在死线程内**。`/perfscope status`
  只判 `available()`（仅探目录可写），**谎报绿色**。
  - 循环体抽为 `writeLoop()`，外层加有界自动重建守卫（`MAX_WRITER_RESTARTS=3`，
    `onWriteSuccess()` 清零使其语义为「连续」而非累计）。
  - `PerfScopeCommand` 的 `persistOk` 判定由 `available()` 改为 `isHealthy()`，使线程死亡可见。
- **`TaskHandle.noop()` 与活句柄不可区分**（`f-s01-err-01` + `f-s01-err-02` + `f-s02-conc-01`，
  MEDIUM）。Folia 分支调度失败返回 noop（三字段全 null，`cancel()` 空操作），三个消费点均误判为
  「任务已启动」：告警与日报照打「已启动」INFO 而实际永久停摆，monitor 的 noop 被 CAS 装进
  `monitorTask` 后双检挡回全部后续订阅。
  - `TaskHandle` 新增 `isActive()`（Bukkit/Folia 返回 true，noop 返回 false）。
  - 告警/日报：`!isActive()` 时打 SEVERE 并 return，不再打印「已启动」。
  - monitor：noop 不装入 `monitorTask`，`startMonitorTask()` 返回 `boolean`，订阅分支据此
    撤销刚加入的订阅并回失败文案。
- **HTML 报告 NPE**（`f-s05-logic-02` + `f-s02-contract-01` + `f-s02-data-01` + `f-s02-data-09`
  + `f-s06-contract-04`，MEDIUM）。`ServerCompat.java:117` 在非 Folia 分支**无条件**把
  `msptUnavailableReasonKey` 置 null，`msptUnavailableReason()` 返回 null，
  `ReportGenerator:371` 把该 null 直接传给 `htmlCard` 的 `subtitle`，而 `htmlCard` 首句即
  `subtitle.isEmpty()` → NPE（TXT 侧 `:609` 有双保护，HTML 没有）。实测抛
  `NullPointerException: Cannot invoke "String.isEmpty()" because "<parameter4>" is null`。
  - `ServerCompat.java:117` 改为「两能力位皆 false 才写 `compat.mspt.no-api`」，
    同时消掉 NPE 根因与 `CoreMessages.java:149` 的零赋值死键。
  - `ReportGenerator` HTML 路径补 null 兜底，与 TXT 侧对齐。
  - **C 项（后续补修）**：新增 `msptUnavailableCause(snap)` 作为 HTML/TXT 唯一成因入口，
    优先级依 `MetricsSnapshot.disabledModules` 契约（`disabledModules` > 能力位 false > 采集异常）喵。
    新增 i18n 键 `report.common.mspt.disabled` / `report.common.mspt.collect-failed`（en+zh 双写）喵。
    修复前「配置禁用」与「采集异常」渲染**逐字节相同**，现三档互不相同喵。
    真实 Paper 1.21.11 实测：`MSPT (avg/min/max): unavailable (Disabled in config (mspt.enabled=false))` 喵。
  - **仍未同步**：JSON 路径（`ReportGenerator:747` 的 `unavailableSection()`）未加 `cause` 字段，
    因 JSON 字段名是下游依赖的外部契约，加字段属 `PUBLIC_API_CHANGE`，留待人工决策喵。
- **MSPT 能力位口径分裂**（`f-s02-logic-01` + `f-s02-contract-02` + `f-s02-data-02`，MEDIUM）。
  `isMsptTrustworthy()` 的 javadoc 主条款要求「false 时不得 TPS 反推」，但同段末尾明确授权
  「非 Folia 上 TPS 反推仍合理，**调用方应以 `isFolia()` 区分两种 false**」。采集层按豁免条款
  实现（非 Folia 走 TPS 反推并产出估算值），命令层三处未分叉，一律渲染「不可用」——同一份快照，
  采集层有值、报告层打印、命令层与 ActionBar 报不可用。
  - `PerfScopeCommand` 三处（`showAll` / `showMSPT` / `sendActionBar`）统一改为
    `|| (!isMsptTrustworthy() && isFolia())`，照 `:248-253` 既有分叉风格。
  - Folia 分支修复前后输出逐字节 `<IDENTICAL>`，全程保持「不可用」。
- **非聊天输出面缺「(估算)」标注**（`f-s02-data-08` + `f-s02-actionbar-01`，LOW）。
  MSPT 走 TPS 反推时聊天层会标「(估算)」，报告（HTML/TXT）与 ActionBar 只输出数值。
  - 新增 i18n 键 `report.common.estimated-tag`（en ` (estimated)` / zh ` (估算)`，双表同时注册，
    走 `report.*` 前缀，未跨模块复用 `cmd.*`）。
  - 判定抽为 `ReportGenerator.msptEstimateTag(snap)`，HTML 卡片与 TXT 两个分支接入；
    ActionBar 在 MSPT 段后独立追加，并主动排除「未知 (估算)」矛盾组合。
  - HTML 转义只做一次（用 `&<>"x` 覆盖文案实测输出 `&amp;&lt;&gt;&quot;x`，无三重转义）。
- **`future.get()` 无超时导致关服可能永久阻塞**（`f-s06-conc-02`，LOW）。`shutdown()` 调
  `shutdownNow()` 会清空队列却**不完成**已排队的 Future（JDK 21 实测：`isDone=false
  isCancelled=false`，2 秒后仍如此），而 `submitAndAwait` 的 `get()` 无超时 → 永久阻塞调用线程。
  - 改为 `get(30, TimeUnit.SECONDS)`，新增 `REPORT_AWAIT_TIMEOUT_SECONDS=30L` 常量，
    超时记 WARNING 并返回 null。
  - **刻意不调 `future.cancel(true)`**：中断写盘线程会留下半写报告文件，与「绝不产生半份报告」
    前提冲突。代价是被 drain 的 Future 仍持有 plugin 引用直到 JVM 退出（执行器线程为 daemon，
    关服窗口极短）。
- **`DiagnosticEngine.Result` 谎报「全部已评估」**（`f-s03-logic-02` + `f-s03-err-01` +
  `f-s03-contract-02`，LOW）。两条提前返回（`snap == null` / `cfg == null`）用 `RULES.size()`
  当 total，使 `evaluatedCount()` 返回 5、`fullyEvaluated()` 返回 true，而实际 0 条规则运行。
  - 提前返回的 `total` 改为 0，`fullyEvaluated()` 加 `total > 0` 前置；`total` 字段名未改，
    只把「评估基数」语义写进 javadoc。
  - 消费者穷举确认：`Result` 唯一入口 `PerfScopeCommand:1016`，而 `:1011` 已先判
    `getLatest() == null` 提前 return，故**无任何消费方依赖旧行为**，用户可见输出零变化。

### Changed
- **`onEnable` 裸调 `configManager.load()` 的文档声明**（`f-s06-err-01` + `f-s03-err-05`，LOW）。
  机制成立（`load():147-154` 会抛 `IllegalStateException`），但后果是**清晰的 fail-fast**：
  `:150-152` 已在抛前打两条 SEVERE（本地化原因 + 修复指引），服务端会判启用失败并留堆栈。
  `reload()` 有 catch 属**正当的不对称**——启动期没有旧配置可退。
  - **只改注释，0 行代码变更**（机械核验：`git diff -U0 | grep -vE '^\+\\s*//'` 为空）。
    `PerfScope.java:86` 新增 4 行注释声明「启动期不捕获是刻意设计」，
    `ConfigManager.load()` javadoc 改为分场景表述。
  - **明令禁止**给 `onEnable` 加 `try/catch`：那会让插件带着 `config == null` 继续装配
    12 个模块，后果远比 fail-fast 严重。

### Notes
- **本轮覆盖不完整，必须如实声明**：Devil's Advocate 的四类覆盖扫描发现 7 条缺口，
  已补派 2 个审计实例闭合其中 4 条。以下 3 处**本轮不具备发现能力**：
  - `slice-01 / security`：`WebhookNotifier.java:207-414` 的出站目的地校验段在审计波零行号引用。
    （锐评波已由 `slice-02` Skeptic 实际覆盖并判其 SSRF 防护完整，故无需补派。）
  - `slice-05 / concurrency`：`ReportGenerator` 的实例态与清理动作跨线程访问、
    `ReportMessages.ensureRegistered` 静态注册时序、`ThresholdColors` 的 `configManager` 读取路径。
  - `slice-05 / contract`：`ReportGenerator` 三格式输出契约与 `Messages` 键契约（问题密度不支持
    「无需 contract 维度」的判断）。
- **未闭合项（留给下一轮）**：
  - `f-s05-logic-02` 的 JSON 路径未同步（HTML/TXT 已在 fix-05C 补完）：`ReportGenerator:747`
    的 `unavailableSection()` 仍只输出 `{"status":"unavailable"}`，未携带成因喵。
    因 JSON 字段名是下游依赖的外部契约，加 `cause` 字段属 `PUBLIC_API_CHANGE`，
    属 `UNRESOLVED_FINDING`、`blocking: false`喵。
  - `f-s05-logic-01`（JSON `alerts.health` 恒 `degraded`）——MEDIUM，本轮未进修复波喵。
    真实 Paper 端到端实测坐实：仅把 `mspt.enabled` 设为 `false`、服务器 TPS 20 / 零告警，
    JSON 仍输出 `"health": "degraded"`，而同刻 `/perfscope status` 正确显示
    `MSPT: Disabled (config mspt.enabled=false)`喵。依赖 `health` 做监控抓取的下游会对
    按配置正常运行的服务器永久报异常喵。
  - `f-s04-conc-02`（`shutdown()` join 超时分支从关服线程操作写线程正在用的 `BufferedWriter`）
    与 `f-s04-err-03`（`flush`/`close` 同 try，flush 失败跳过 close）未被任何修复项合并，
    本轮未处理。
  - `fix-07` 的 `OBSERVED_BUT_OUT_OF_SCOPE`：其余 4 处 noop 消费点
    （`MetricsCollector:338-339` 的 sync/jvm 句柄、`PerfScope:308` 的 healthWatchTask、
    `PerfScope:400` 的 autoReportTask、`ServerCompat:386` 的 runLater）同样无法判读 noop。
  - `fix-07` 发现的非 Folia 对称缺口：`SchedulerAdapter:85` 的 Bukkit 分支无 try，
    失败形态是**抛异常而非返回 noop**，会留下「已发已开启回执 + 集合有 UUID + 任务未装」
    且下次点击变退订。
- **规则 10 的局限（必须如实记录）**：CLAUDE.md 规则 10 要求涉及阈值/迟滞/窗口的改动
  用真实采集序列（≥30 分钟）回放确认。**仓库内没有任何真实采集的 CSV 数据，也没有运行中的
  服务器**，故本轮只验证了「钳制逻辑在边界值下是否正确」，**未验证修正后参数在真实负载下的
  行为**。不得据此声称做过实测。
- **测试覆盖为零**：仓库无 `src/test` 目录。所有修复的验证依赖仓库外临时探针
  （`%TEMP%/pscope-fix*/`），未入源码树。已多次记入各修复项的 `residual_risks`，
  建议后续补建测试。
- **多 Agent 并行的已知风险**：所有 9 个修复项的 `rollback_command` 都是
  `git checkout -- <file>`，但 `PerfScopeCommand.java` 被 5 个修复项先后修改、
  `ReportGenerator.java` 被 3 个修改。**机械执行 `rollback_command` 会连带回滚他人的改动**，
  回滚时必须用 `git checkout -p` 定向到具体 hunk。这是框架按「单 Agent 独占文件」设计的
  真实局限。
- **项目指令文件的事实纠正**：根 `CLAUDE.md` 的「关键文件」表称 `plugin.yml` 版本号为
  硬编码 `1.0.0`、未用 Maven filtering。**实际 `plugin.yml:2` 为 `${project.version}`，
  `pom.xml` 的 `<resources><filtering>true</filtering>` 已开启过滤**喵。
  **2026-11-04 已更正该行**，改为「版本号为 `${project.version}`，由 pom 的 resources filtering
  在打包期替换（单一版本源，无需手工改）」喵。

### Fixed（收尾批：写权限外 5 条 + 大改动 3 条，全部修完）

前两批因「目标文件不在写权限内」与「超出最小安全差异」共跳过 8 条，本批授权后**全部落地**喵。

- **`messages.yml` 迁移先报成功、写失败时旧文件已不可恢复**（`f-s05-err-08`）。
  `writeDefaultFile` 改返回 `boolean`，完成态日志移到写入成功后；失败另打独立日志并明确说明
  「旧文件已改名备份、当前无可编辑模板」。实测用目录 ACL 禁 `ADD_FILE` 真实注入写失败喵。
- **`mergeModules` 的「键集合不一致记 WARNING」承诺从未实现**（`f-s03-contract-06`）。
  返回被补齐的键列表，由 `load()` 统一打 WARNING喵。
- **`Messages` 的反射死代码**（`f-s03-contract-07`）。删除零调用点的
  `readField`/`findField`/`LANGUAGE_FIELD_NAMES` 与 `java.lang.reflect.Field` import，
  订正 `readConfiguredLanguage` 的 javadoc喵。反射枚举验证：遗留成员数 3 → 0喵。
- **`diagnose` 跨代次求值**（`f-s03-data-06`）。新增 `ConfigManager.publishedAtMillis()` 与
  `DiagnosticEngine` 的代次校验：快照早于配置发布且处在宽限窗口内时，逐条登记
  `diag.skip.stale-config` 喵。**宽限窗口是实测逼出来的**——先按「永久生效」实现，
  真实 Paper 上发现那会让采集真停摆时诊断**永久沉默**（比原缺陷更重），故改为
  「一个采集周期 + 20 秒余量」（下限 30 秒、上限 5 分钟）喵。新增 i18n 键 `diag.skip.stale-config`。
- **TPS 规则的 detail 文案无条件断言「持续负载」**（`f-s03-logic-05`）。改写为
  「任一口径低于告警线即命中；对照 15 分钟均值与该告警线，可区分短暂回落与持续负载」——
  一句话对两种命中形态都不再说谎，**规则语义零改动**喵。
  - **未拆两条 detail 键**（原计划）：拆键必须改 `CoreRules.java` 的调用点，而它不在该批权限内；
    只加键不选键会造出**永不触发的死键**——正是本轮刚修掉的 `compat.mspt.no-api` 同一形状喵。
- **批量投递无逐玩家异常边界**（`f-s01-err-08`）。`broadcastToAlertPlayers` 加逐玩家
  `try/catch` 并返回 `boolean`（`false` 仅当有人需收但全员失败），失败数与首个失败者
  汇总为一条含玩家名的 WARNING喵。**`gameOk` 保持布尔、未改三态**——返回值义精确为
  「false 仅当有人需要收但全员失败」，四场景实测确认与旧行为仅在「部分成功」上有差异喵。
- **`serverTag()` 的 `catch (Throwable)` 吞 JVM 级 Error**（`f-s01-err-07`）。收窄为
  `catch (Exception | LinkageError)`喵。`javap` 实证原 `Throwable` 是**防御性超集而非刻意**：
  `NoSuchMethodError`/`NoClassDefFoundError`/`ExceptionInInitializerError` 三者均属
  `LinkageError` 一族仍被覆盖，而 `OutOfMemoryError`/`StackOverflowError`（`VirtualMachineError`）
  与 `AssertionError`/`ThreadDeath`（直接 `extends Error`）本来就不该被吞喵。
  另确认 `org.bukkit.Bukkit` 无 `static{}` 块，且 `CraftScheduler.mainThreadHeartbeat` 的
  异常表以 `catch (Throwable)` 兜住任务体，逃逸不致命喵。
- **`RawConfig` 解析失败静默取默认值**（`f-s01-err-10`）。仅对 `alerts.refire-min-interval-seconds`
  补 WARNING——核对后确认它是 `loadOptions` 8 个键里**唯一**既不被 `ConfigManager` 读取、
  也不在 jar 内 `config.yml` 的键；其余 7 个键已有的 `warnType` 会报，重复补是净负收益喵。

### Notes（round2）

- **总改动**：14 个文件 `+780/-127` 行（含 `build.yml` 的 299 行独立改动）。
  `mvn clean package` BUILD SUCCESS，`PerfScope-1.2.1.jar` 322 KB 喵。
- **i18n 键**：680 → 682（新增 `diag.skip.stale-config`、`alert.log.refire-unparsable`、
  `alert.log.game-partial`、`daily.note.floor`、`report.common.mspt.disabled`、
  `report.common.mspt.collect-failed` 等），`en`/`zh` 键集完全对齐（`only_in_en=[]`、`only_in_zh=[]`），
  `tools/check-i18n.mjs` PASS（0 error / 0 warn）喵。
- **真实 Paper 1.21.11 端到端验证**（三轮，独立实例）：启动零异常、`diagnose` 无
  `stale-config` 误报、三种报告格式落盘、干净关服喵。
- **41 条 UPHELD 的最终去向**：修 30 条、需人裁决 6 条、无修复对象 3 条、
  环境不具备 1 条、已由作者提交修复 1 条喵。详见
  `docs/reports/round1/round1-round2-decisions.md`喵。
- **测试覆盖仍为零**：仓库无 `src/test`，所有验证依赖仓库外临时探针与真实服务端喵。
- **CLAUDE.md 规则 10 仍未满足**：`f-s03-logic-04` 的窗口类改动只用合成轨迹验证喵。

---

## [1.3.0] — 2026-11-10 — UPHELD 剩余 10 条清零（2026-11-04）

> 紧接上一段。60 条真实未修 `UPHELD` 中的 15 条 `OPEN_MISSED`，上一批修了 5 条，
> 本批把**剩余 10 条全部修完**喵。至此该清单归零喵。

### Fixed — `f-s02-conc-03`（LOW）：关服期订阅泄漏

`startMonitorTask()` 在 `monitorShutdown` 时返回 `true`，只为让回执路径维持原状喵。
但 `toggleMonitor` 已把 UUID 加进 `monitoringPlayers`，而 `shutdown()` 早先 `clear()` 过集合——
于是这个 UUID **永久留在 Set 里无人服务**喵。

- `startMonitorTask()` 关服时改为如实返回 `false`，由 `toggleMonitor` 撤销刚加入的订阅喵。
- 回执文案区分「调度不可用（可重试）」与「插件正在停用（重试无意义）」——
  旧实现把两者都报成「启动失败，详见控制台」，而关服分支根本没往控制台写任何东西喵。

### Fixed — `f-s02-contract-04`（LOW）：`cancel()` 的 javadoc 与实现不符

javadoc 承诺「取消异常被吞并记 warning，不抛给调用方」，但**只有 Folia 分支有 try**喵。
Bukkit 分支的 `cancel()` 一旦抛出就直接冒泡，而调用方都在关服/退订路径上——
异常冒泡会中断「取消其余句柄」的循环喵。

- 两条分支统一：都吞异常并经单点 `logCancelFailure()` 记 warning（带异常类型）喵。
- 新增 i18n 键 `run.compat.task-cancel-failed`（en+zh），与既有 `folia-cancel-failed` 并存喵。

### Fixed — `f-s02-contract-05` + `f-s02-err-03`（LOW）：`ConfigManager.get()` 的可空契约

`get()` 无 javadoc、无 `@Nullable`，导致 7 个调用点判空方言分裂（6 处直解引用、1 处判空）喵。

- `get()` 补 javadoc 与 `@org.jetbrains.annotations.Nullable`，明确「可能返回 null」喵。
- `PerfScopeCommand` 6 处直解引用全部收口到新增的 `cfgNow()` / `thresholdOf()` 两个辅助方法，
  配置不可用时回退合理默认值而非 NPE喵。
- **为什么现在不可达也要修**：这种「靠不变量隐式兜底」的写法在重构时一碰就炸喵。

### Fixed — `f-s02-sec-02`（LOW）：异常原文与绝对路径直进聊天框

`/perfscope status` 的受众是 `perfscope.use`，未必是服主本人，而 `lastCollectError`
原文常含 `/srv/minecraft/...` 或 `C:\Users\<服主名>\...` 与堆栈片段喵。

- 新增 `scrubForChat()`：只保留异常类型与首句，Windows/POSIX 绝对路径替换为 `<path>`，
  超长截断喵。
- **完整原文仍在控制台日志**（采集层本来就有 SEVERE/WARNING），脱敏不损失排障能力喵。

### Fixed — `f-s04-csv-05`（LOW）：CSV 表头与数据行两份独立定义

`HEADER` 由 `COLUMNS` 派生，`format()` 却**手写 7 段拼接**喵。单侧重排或改名会让表头序与
数据行序悄悄漂移，而读取端按列名映射——结果是**数据被安到错误的列上且不报错**喵。

- `format()` 改为按 `COLUMNS` 索引取值，并加一条运行期断言（长度不符即抛）喵。
- `COLUMNS` 的 javadoc 明确声明它是**唯一真相源**喵。

### Fixed — `f-s04-csv-06`（LOW）：`skippedLines()` 零调用点

文件里有坏行（半写/损坏/列数不符）时，`trend` 只说「来源：内存环」，
不告诉用户为什么走了回退、丢了多少行喵。

- `showTrend()` 接入 `skippedLines()`：有坏行时打一条 YELLOW 提示含具体行数；
  有持久化目录却拿不到样本时，另打一条说明回退原因与目录路径喵。
- 新增 i18n 键 `cmd.trend.skipped-lines` / `cmd.trend.file-unavailable`喵。

### Fixed — `f-s06-contract-06`（LOW）：`getInstance()` 死 API

全仓零调用点喵。**本批不删除**——它是第三方插件/脚本通过 `Bukkit.getPluginManager()`
拿到实例后的静态便捷入口，删除属 `PUBLIC_API_CHANGE`，需作者裁决喵。
本批只补 javadoc 说明它为何保留、以及「可能返回 null，调用方必须判空」的契约喵。

### Fixed — `f-s06-contract-07`（LOW）：javadoc 悬空到错误方法

`shutdownHistoryFileStore()` 头上顶着**两段** javadoc，前一段讲的是报告执行器
（`ReportGenerator#shutdown()` 用 `shutdownNow()`）喵。读者会以为本方法用 `shutdownNow()`
中断写线程——实际它调的是 `HistoryFileStore.shutdown()`，语义是**刷完队列再退出**，
与 `shutdownNow()` 正好相反喵。

- 两段 javadoc 各归其位：`shutdownHistoryFileStore()` 保留写线程说明，
  「关闭报告执行器」一段移回它真正的宿主 `shutdownReportGenerator()` 喵。

### Fixed — `f-s02-logic-04`（LOW）：`trend` 天数上界与数据来源不符

天数上界一律取文件保留天数（默认 7），但文件持久化不可用时会回退到内存环，
而内存环容量上限是 **24 小时**——于是 `trend 7` 会输出
「近 7 天（1440 个样本，来源: 内存环）」，把 24 小时数据标成 7 天喵。

- 先探一次文件是否可用，再决定上界：可用 → `retentionDays`；不可用 → **1**喵。
- 请求值被上界压过时明说原因（新增 `cmd.trend.days-clamped-memory`），
  否则用户会以为参数没生效喵。
- 探针结果被复用，未引入二次读盘喵。

### Added — 两个新测试类（本批共 9 个新用例）

- `test/command/ScrubForChatTest`（5 个用例）：Windows/POSIX 路径脱敏、堆栈截断、
  null 与纯文本容错、超长截断喵。
- `test/history/CsvLayoutTest`（4 个用例）：表头与数据行列数一致、取值顺序、
  写入后经真实读取路径往返、坏行计入 `skippedLines` 喵。

### Notes

- **实测**：`mvn clean package` → **29 tests, 0 failures, 0 errors**喵。
  `PerfScope-1.2.1.jar` 327388 字节，`unzip -l | grep -ci junit` → **0**喵。
- **i18n**：**688 键**（新增 `run.compat.task-cancel-failed`、`cmd.trend.skipped-lines`、
  `cmd.trend.file-unavailable`、`cmd.trend.days-clamped-memory`，
  其中一条替换了原先内联的中文字面量），`check-i18n.mjs` PASS（0 error / 0 warn）喵。
- **config 兼容**：`tools/check-config-compat.mjs` 9/9 PASS喵。
- **`f-s06-contract-06` 是本批唯一「有意不删」的条目**，理由已写进代码 javadoc 与本节喵。

---

## [1.3.0] — 2026-11-10 — UPHELD 全集核对（2026-11-04）：41 条清单本身漏了 19 条，已补修 5 条

> **这是本轮最重的一次自我纠正**：此前所有报告（含最终报告 §0.1）都声称
> 「41 条 `UPHELD` 全部处置完毕，对账平衡」。独立核对实例 `verify-coverage` 从
> 10 份锐评产物重建全集后证明：**41 这个数字本身就不完整**喵。

### 对账（推翻此前结论）

```
UPHELD 全集        70
主波次已修 finding  25   （此前 ledger 记为 20，实测 25）
真实未修 UPHELD    60 = 70 - 10（交集）
此前声称的清单      41
清单外的条目        19   ← slice-02 / 04 / 06 从未进入任何批次
```

**`round1-ledger-r2.md:41` 的「slice-02/04/06 已全部修完」不成立**：那三片 `UPHELD`
实际是 18 / 5 / 3，主波次只覆盖 3 / 3 / 1喵。

60 条真实未修 `UPHELD` 的最终去向：

| 判定 | 条数 |
|---|---|
| `CLOSED`（确已修） | 38 |
| `CLOSED_BY_EXEMPTION`（合法豁免） | 7 |
| **`OPEN_MISSED`（缺陷仍在）** | **15** |
| `OPEN_BUT_FIXED_SILENTLY` | 0 |
| 合计 | **60** |

本批修复其中 **5 条**（1 条 MEDIUM + 4 条 LOW），其余 10 条见下「仍未修」喵。

### Fixed — `f-s02-actionbar-02`（MEDIUM，清单外）

`monitorTick()` 只判 `snap == null`，**不判采集是否停摆**喵。
采集任务被取消后 `getLatest()` 会一直返回同一张旧快照，ActionBar 就**永远显示"健康"**的
TPS/MSPT——比不显示更误导，运维看不到掉线信号喵。

- 新增 `collector.isStale()` 守卫：停摆时推一条明确的过期提示（含已多久无数据、
  指向 `/perfscope status`），而不是继续渲染旧数值喵。
- 新增 i18n 键 `cmd.actionbar.stale`（en+zh 双写）喵。
- **为什么它不是"已修"**：`/perfscope status` 早就有 `isStale()` 判定，
  但 ActionBar 这条推送路径从未接线喵。

### Fixed — `f-s05-logic-03`（LOW，清单内但被集体漏掉）

`htmlCard()` 内部对 4 个参数各做一次 `escapeHtml`，而调用点 `:400-401` 的
`unavailable`/`unknown` 与 `:424` 的 `range` 来自 `htmlMsg()`（= `escapeHtml` + `Messages.get`），
**转义了两次**喵。

- 默认文案里没有 `&<>`，所以平时看不出差别；但用户在 `messages.yml` 里把
  `report.common.unavailable` 改成 `N/A <unknown>`，上屏就是 `N/A &amp;lt;unknown&amp;gt;`喵。
- 修法：这三处改用裸 `Messages.get`，转义交给 `htmlCard` **单点负责**喵。
- 顺带确认其余 6 处 `htmlCard` 调用点传入的都是裸文案或纯数值，本就单次转义，无需改动喵。

### Fixed — `f-s05-logic-04`（LOW，**41 条里唯一零命中的条目**）

`formatBytes()` 按**原始字节**判档、却按 `%.1f` 输出喵：

- `1048575`（比 1 MB 少 1 字节）→ 落进 KB 档 → `"1024.0 KB"`
- `1073741823`（比 1 GB 少 1 字节）→ 落进 MB 档 → `"1024.0 MB"`

读者看到"1024 KB"会以为该进位却没进位喵。

- 修法：**判档前先按目标精度四舍五入**，用舍入后的值判档 → `"1.0 MB"` / `"1.00 GB"`喵。
- **两份同构副本同改**：`ReportGenerator.formatBytes` 与 `PerfScopeCommand.formatBytes`喵。
- 该条在全部 04/05 波次产物里**零命中**——它只存在于 `round1-final-report.md` §5.3，
  而那张表后来被标注「不再作为待办清单」，等于连带把它注销了喵。

### Fixed — `f-s02-actionbar-04` / `f-s02-actionbar-05`（LOW，清单外）

- **`actionbar-04`**：TPS 不可用时仍追加 Folia 的 `" gr"` 口径标记，得到
  `TPS: unavailable gr` 这种自相矛盾的串喵。口径标记只对**真实数值**有意义，
  故加 `MODULE_TPS unavailable` 前置条件喵。
- **`actionbar-05`**：实体数在 ActionBar 恒 `WHITE`，而同一份快照在 chat 侧走
  `thresholdColors` 着色——超阈值时聊天红/黄、ActionBar 白，两处矛盾喵。
  改为与 chat 同口径取色喵。

### Added — 两条新的回归测试

- `test/report/FormatBytesTest`（4 个用例）：锁住 `1048575 → "1.0 MB"`、
  `1073741823 → "1.00 GB"`、正常值不回归、负数容错喵。
- `DiagnosticEngineContractTest` 等既有测试继续全绿喵。
- **实测**：`mvn clean package` → **20 tests, 0 failures, 0 errors**喵。
  `PerfScope-1.2.1.jar` 324945 字节；i18n 683 键（`cmd.actionbar.stale` 新增后仍 683，
  因 `never looked up` 计数从 120 降到 118，说明新键已被真实引用）喵。

### Notes — 仍未修的 10 条（**已在后续批次全部修完**）

> **本表已被下一段取代**：10 条于同日全部修复完毕，见
> 「UPHELD 剩余 10 条清零」段。此表保留作历史记录喵。

| finding_id | 严重性 | 缺陷 | 为何本批不修 |
|---|---|---|---|
| `f-s02-conc-03` | LOW | `toggleMonitor` 先 add 订阅后装任务；`monitorShutdown` 早退不撤销订阅 | 需重排订阅/任务装填顺序，改 Folia 关服期并发路径，超出最小安全差异 |
| `f-s02-contract-04` | LOW | `SchedulerAdapter` javadoc 称取消异常被吞并记 warning，Bukkit 分支无 try | 加 try 会改变异常传播语义（当前是 fail-fast），需产品裁决 |
| `f-s02-contract-05` | LOW | `ConfigManager.get()` 无 javadoc 无 `@Nullable`，调用方判空方言分裂（6 处直解引用 vs 1 处判空） | 纯文档统一，但涉及 7 个调用点的契约声明，宜与 v1.3 的 API 收敛一起做 |
| `f-s02-err-03` | LOW | `cfg == null` 防护 1 处有、6 处无 | 当前不可达（配置加载失败即 fail-fast），属契约一致性 |
| `f-s02-sec-02` | LOW | `lastCollectError` 原文与绝对路径直进聊天框 | 受众受 `perfscope.use` 约束；脱敏会削弱排障信息，需产品裁决 |
| `f-s04-csv-05` | LOW | `HEADER` 由 `COLUMNS` 派生，`format()` 手写 7 段 | 需重构成单一来源，触及 CSV 落盘契约 |
| `f-s04-csv-06` | LOW | `skippedLines()` 零调用点，降级原因不可区分 | 需新增展示路径（trend 命令输出），属功能增强 |
| `f-s06-contract-06` | LOW | `PerfScope.getInstance()` 全仓零调用（死 API） | 合法但无消费方；删除属公开 API 变更 |
| `f-s06-contract-07` | LOW | 关闭报告执行器的 javadoc 悬空，被历史写线程 javadoc 顶替 | 纯文档，但需逐段核对三处 javadoc 归属 |
| `f-s02-logic-04` | LOW | `trend` 天数上界取自文件保留天数，回落内存环时环只有 24h | 需先定义「持久化不可用时 trend 该报几天」，属产品裁决 |

**这 10 条全部已登记在 `round1-05-verify-coverage.md`，带 `file:line` 与失败场景喵。**
本批不修的理由都写在上表，没有一条是「忘了」喵。

---

## [1.3.0] — 2026-11-10 — 全部欠账清算（2026-11-04）：规则 10 真数据回放 + 建 `src/test` + 残留风险闭合

> **背景**：五波次审计的 round1 / round2 / 收尾批 / 第五波次验收都已跑完，但仍有
> 一批「如实记录但未消解」的欠账。本段把它们逐条清完，不再留「环境不具备」这类悬空项喵。

### Added — 建起 `src/test`（此前仓库零单测）

五波次审计的全部验证都靠仓库外临时探针，改一行代码后无法重跑喵。本批建立最小回归网：

- `pom.xml` 新增 `org.junit.jupiter:junit-jupiter:5.10.1`（`test` scope）与
  显式 `maven-surefire-plugin:3.2.5` 喵。
  **钉 surefire 版本是必须的**：不钉时 Maven 用默认 2.12.4，它不认识 JUnit 5，
  会把测试文件全部静默跳过——与 CI `build.yml` 里红队 B4 修掉的 `globstar` 缺口同形状喵。
- `test/report/ReportFileNameTest`（3 个用例）：锁住报告清理的归属判定。
  插件自有名（含 `-1`/`-10` 碰撞后缀、闰日、边界日）必须被清理；
  管理员文件与非法时间戳（13 月 / 25 时）绝不能被清理喵。
- `test/diagnose/CoverageProblemTest`（4 个用例）：锁住 `coverageProblem` 的真中位数，
  同时锁住「条数不足」「挤在 1 秒内」两个既有拦截不被削弱喵。
- `test/alert/DailyCounterRolloverTest`（4 个用例）：锁住 A3 回归
  （跨午夜 reload 必须归零）、reload 幂等、同日 reload 不清零、时区切换不清零喵。
- `test/diagnose/DiagnosticEngineContractTest`（5 个用例）：锁住 `f-s03-data-06` 的代次校验
  与宽限窗口（窗口内跳过、超窗**不得**永久沉默）、`f-s03-logic-02` 的 `total=0` 契约、
  以及宽限窗口的 30 秒下限 / 5 分钟上限喵。
  - **一条踩坑记录**：`MetricsCollector.history` 是 `final` 字段，`Unsafe.allocateInstance`
    不会初始化它；走真实构造器又会触发 `ServerCompat` 的 `Bukkit` 静态初始化，在无服务端的
    单测进程里必然 NPE。用反射直接写 `final` 字段才两全喵。
- **实测**：`mvn clean package` → **16 tests, 0 failures, 0 errors**喵。
  产物 `PerfScope-1.2.1.jar` 324502 字节，`unzip -l | grep -ci junit` 命中 **0**
  （测试依赖未进 shade 产物）喵。

### Fixed — 规则 10：≥30 分钟真实采集序列回放（`f-s03-logic-04`）

第五波次验收把这条判为 `VERIFICATION_INSUFFICIENT`，理由成立：仓库无真实 CSV，
`%TEMP%` 下的 8 个 CSV 是「时间戳大量重复 + tps 恒 20.0」的合成夹具喵。

本批起了真实 Paper 1.21.4 服务端（插件用工作树构建的 jar），把
`history.sample-interval-seconds` 设为 1，连续采集 **68.65 分钟 / 2873 条 1Hz 样本**喵。
真实数据特征：间隔 993–2540 ms（自然抖动）、14 种 TPS、368 种 MSPT、63 种堆占用喵。

回放方法：整段按固定 offset 平移到「以 now 为终点」（坐标平移，gap 与数值逐字不变），
三条共用 `coverageProblem` 的规则各按自己的窗口切片，每 60 秒取一个窗口位置共
**117 次 `coverageProblem` 判定**、每 5 分钟一个位置共 **24 次规则评估**，
**同一份探针分别在 HEAD 基线类与工作树类上运行**喵。

**结果：diff 0 行**（117 次 COV 全 PASS，0 SKIP；24 次 RULE 无 `coverage-samples` 误判）喵。

- **这条证据的诚实边界**：它证明的是「真中位数修法在真实数据上不引入任何行为变化」，
  **不能**证明「偏斜窗口下的修正方向是对的」——后者由合成用例 `(1,1,1,100,100,100)`
  的 A/B（旧 SKIP → 新 PASS）证明喵。两条证据互补，缺一不可喵。
- 证据落盘：`docs/reports/round1/round1-05-verify-supplement.md` §1喵。

### Fixed — `f-s03-logic-01` 的近恒真区（验收 `IMPACT_UNVERIFIED` 闭合）

`TPS_CEILING = 20.5` 的取法有代价：`(20.0, 20.5]` 的阈值在稳态 TPS 恒 ≤20.0 的服务器上
近乎恒真，`DIAG-TPS-PERSIST` 会永久命中喵。验收正确指出这一点并要求产品裁决喵。

**本批不裁决、但把它变成可见的**（与 `f-s03-data-04` 的 warn-only 口径一致）：

- 新增 `TPS_TAUTOLOGY_FLOOR = 20.0`，阈值 ≥ 该值时打一条 WARNING 说明后果，
  **不改用户配置值**喵。
- **为什么不压到 20.0**：那会把「想按 20.1 卡住下跌」的合法配置判非法并静默回退到 18.0，
  告警反而更迟钝，属 breaking change 喵。
- 新增 i18n 键 `config.check.tps-near-ceiling`（en+zh 双写），键数 682 → **683**喵。

### Fixed — i18n 核心的穷举差分（验收 `IMPACT_UNVERIFIED` 闭合）

验收对 `mergeModules` 返回类型 `void` → `List<String>` 只做了 i18n 检查器 + 键对齐，
未做穷举差分喵。本批按 `PerfScope.onEnable` 的**相同注册顺序**装配全部模块表，
逐键导出 en / zh 全文做 diff喵。

| 版本 | en | zh |
|---|---|---|
| HEAD 基线 | 675 | 675 |
| 工作树 | 683 | 683 |

`diff` 共 54 行，**全部落在预期变更内**：8 个新增键（`alert.log.game-partial`、
`alert.log.refire-unparsable`、`config.check.tps-near-ceiling`、`daily.note.floor`、
`diag.skip.stale-config`、`report.log.delete-failed`、`report.log.delete-failed-summary`、
`report.log.register-failed`）+ 3 条既有文案（`cmd.diagnose.partial`、
`diag.DIAG-TPS-PERSIST.detail`）× 双语喵。
**其余 675 个键逐字节相同**——`mergeModules` 的返回类型改动对渲染零影响喵。

证据落盘：`round1-05-verify-supplement.md` §2喵。

### Notes — 本批的诚实清单

- **`f-s05-err-09` 登记为 `CLOSED_BY_SILENT_FIX`**：代码已由 `r2-fix-04` 修掉
  （`mergeModules` 返回值转 WARNING），但**没有任何修复报告写过它的 ID**，
  只读过 `r2-fix-01`/`r2-fix-02` 的下游会误判为「未修」喵。已在
  `round1-05-verify-supplement.md` §3 显式登记喵。
- **仍未消解的平台固有限制**：`ATOMIC_MOVE` 不被底层文件系统支持时的回退窗口
  （`ConfigManager.java` 的 `AtomicMoveNotSupportedException` 分支）**不能在应用层消除**，
  只能证明它比原地截断窄得多、且是唯一可行降级喵。
- **全部产物**：`round1-05-verify-r2-fix-01.md`、`round1-05-verify-r2-fix-02.md`、
  `round1-05-verify-decisions.md`、`round1-05-verify-supplement.md`、
  `round1-05-verify-residuals.md`喵。

### Fixed — 运行时残留风险的四条探针闭合（`round1-05-verify-residuals.md`）

第五波次验收有 4 条风险标为 `satisfied: false`（只读代码、未实跑）。本批用运行时探针逐条跑完喵。

| 风险 | 判定 | 关键证据 |
|---|---|---|
| `cleanOldReports` 的 51 文件上限路径 | **PASS** | 真实构造 51 个过期文件（含 `-N` 后缀）→ `deleted=50`、留存 1 个、恰 1 条含 `50` 的 WARNING；未过期 3/3 保留 |
| `retentionDays <= 0` 静默路径 | **PASS** | `0` 与 `-1` 各跑一次真实清理 → `deleted=0`、WARNING=0（「0 = 永久保留」契约成立） |
| `writeBackConfigVersion` 崩溃注入 | **PASS** | 48 轮强杀（17–24 MB YAML 放大窗口），主 `config.yml` **100%** 落在「旧全文」或「旧全文+版本块」两态，`corrupt=0` |
| `ATOMIC_MOVE` 回退短窗口 | **PLATFORM_LIMIT** | 字节码异常表确认 catch **仅**捕 `AtomicMoveNotSupportedException`（非宽泛 catch）；`finally` 的 `deleteIfExists` **只**对 tmp 文件调用。该分支真被触发的条件无法在 NTFS 上制造，据 JDK 源码必然退化为两次 syscall——**不能在应用层消除**，故不给 PASS |

**两处变异对照证明了探针没有失明**（这是本批最重要的方法论）喵：

1. **R1c**：构造 51 个「通过旧正则但违反值域」的文件（13 月 / 25 时 / 61 分 / 61 秒 / 32 日）
   → `deleted=0`，证明 `REPORT_FILE_NAME_STRICT` 那道门是**活的**，
   R1 的 PASS 不是旧正则的副产品喵。
2. **R3 变异**：换回修复前的 `Files.write(CREATE+TRUNCATE_EXISTING)` 后同样强杀
   → **10/10 CORRUPT**（含 8192 字节半写），证明崩溃注入探针真的能检出半写喵。

**本批自我纠正的两处派发失误**（值得记录）喵：

- **派发建议的 1–80 ms 强杀延迟根本够不到写入窗口**：子进程从就绪到写盘完成需
  5.2–7.5 秒，tmp 最早 4378 ms 才出现，12 轮固定窗强杀全落在 JVM 类加载阶段、
  `tmp_first_seen_ms=-1`，零信息量喵。探针实例**没有假装成功**，而是追加了
  「扫实测窗口」12 轮 + 「按文件状态触发」24 轮，使 `MIDWRITE/PREMOVE/POSTMOVE`
  各命中 8 次喵。若只有前一段，正确结论应是 `VERIFICATION_INSUFFICIENT` 而非 PASS喵。
- **不能复用上一轮 `verify-r2` 的编译产物**：它编译于 02:22，缺少工作树新增的
  `REPORT_FILE_NAME_STRICT` 字段，用它根本测不到值域门喵。探针实例改为从当前源码
  重新编译私有快照（35 源文件 → 61 class），并做 `javap` 等价性证明
  （`writeBackConfigVersion` 段 `diff=0`、`ReportGenerator` 整类 `diff=0`）喵。

**`N-LOGIC05` 风险自然消解**：全仓（含 `.github` / `*.mjs` / `*.ps1` / `*.json`）
没有任何 `contains("sustained load")` 断言，且运行期渲染确认审计真正判据
`this is sustained load` 已为 false喵。

### Notes — 探针侧 `unclosed`（如实转记，均非产品缺陷）

1. `ATOMIC_MOVE` 回退分支**真被触发**的前提无法在本机制造（需跨卷或非原子 FS）喵。
2. tmp 残留后的「下次启动自愈」未实测，仅代码可达性推断喵。
3. R3 的 1–80 ms 固定窗段零信息量（已由状态触发段 + 变异对照替代覆盖）喵。
4. 真实 Paper 服务端端到端未跑（派发明确禁止启动服务端，已由此前多轮真实验证覆盖）喵。
5. R1 的「第 51 个」依赖 `File.listFiles()` 枚举顺序（已用同一函数预枚举自洽化）喵。

---

## [1.3.0] — 2026-11-10 — 第五波次验收（补跑）：1 条 FAIL + 3 条低危遗留，全部已处置

> **背景**：round2 与收尾批此前**没有走第五波次验收**（见下段 Notes 的门控缺口声明）喵。
> 2026-11-04 补派独立验收实例 `verify-r2`，用 `git archive HEAD` 基线类 + 工作树类
> 双编译，对每条 finding 跑同一份探针做 A/B 对照（仓库外 `%TEMP%/verify-r2/`，零 git 写操作、
> 零源码修改、未启动服务端）喵。
> 产出 `round1-05-verify-r2-fix-01.md` / `round1-05-verify-r2-fix-02.md` /
> `round1-05-verify-decisions.md` 三份记录喵。
>
> **结果：18 条 PASS，1 条 FAIL，1 条 VERIFICATION_INSUFFICIENT**喵。

### Fixed — 验收发现的真回归（HIGH）

- **A3 的时区修复引入了跨天计数器污染**（`f-s01-data-05` 的回归，验收 `N-A3`）喵。
  收尾批为修「时区切换被误判为跨日」，在 `loadOptions()` 末尾写了
  **无条件** `this.pushedTodayDayEpoch = currentDayEpoch();`喵。
  但 `loadOptions()` 在**每次 `/perfscope reload`** 都会执行
  （`PerfScope.reload()` → `startAlertService()` → `AlertService.start()` → `loadOptions()`），
  而 `rollDay()` 只在推送与计数器 getter 里调用喵。
  于是「服务端跨过午夜、在首次推送之前执行 reload」这个窗口里，基线被直接设成「今天」，
  昨天→今天的跳变**永远观测不到**，四个当日计数器跨天累积喵。
  - **探针实测**（`%TEMP%/verify-a3/ProbeA3.java`，模拟跨午夜 + reload + 推 1 条）：
    基线 `pushedToday=7`，旧实现得 **8**（未归零），正确值应为 **1**喵。
  - **修法**：`loadOptions()` 进入时捕获旧 `Options`，只在
    `!prevTz.equals(o.dailyTimezone)`（时区规格**真的变了**）时才重算基线喵。
    时区未变时分支不执行，`rollDay()` 的跨天归零语义完全保留喵。
  - **实测五场景全绿**：跨午夜 reload 后归零（1）、连续 3 次 reload 仍归零（1）、
    时区切换不清零（3→4）、时区未变正常累加（2→3）、首启换时区（1）喵。
  - **修复者原论证被证伪**：收尾批 §A3 曾写「只重算基线是唯一同时满足两个条件的写法」，
    验收指出该赋值**无法区分「时区切换」与「真跨天」**，论证不成立喵。

### Fixed — 验收发现的低危遗留（LOW）

- **报告清理正则只校位数不校值域**（验收 `N-A6`，A6 的残留）喵。
  `^perfscope-\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{3}(-\d+)?\.(html|txt|json)$`
  会放行 `perfscope-2026-13-03_...`（13 月）、`..._25-05-07-...`（25 时）喵。
  - 新增 `REPORT_FILE_NAME_STRICT` 做值域收紧（月 01-12、日 01-31、时 00-23、分秒 00-59），
    `isReportFileName` 改为**两条正则同时匹配**喵。
  - **有意不校月内天数**（2 月 30 日仍放行）：所有权判据是「形如本插件的时间戳」而非
    「时间戳是合法日期」，且插件自己不会产出非法日期；在这里重实现闰年/月末逻辑是净负收益喵。
  - **30 样本穷举证**：8 个插件自有名（含 `-1`/`-10` 碰撞后缀、闰日 02-29、
    边界 01-01/12-31）全部保持 true；8 个非法值域名由 true 变 false；
    11 个外来名（含大写后缀、空格分隔、无后缀、`null`）全部 false喵。
- **`refire-min-interval-seconds: 0` 的文案过度承诺**（验收 `N-A1`）喵。
  收尾批新加的 config.yml 注释写「不再识别复燃，再次越界一律按普通新告警处理（**立即推送**）」，
  但普通新告警**仍受 `alerts.cooldown-seconds` 约束**，探针证实冷却期内 `refire=0` 时
  `push=false`（两版相同，修复本身没引入，但新注释说的比做的多）喵。
  - config.yml 注释改为「按普通新告警处理，**仍受 alerts.cooldown-seconds 约束，
    故并非「无视冷却立即推送」**」，README 中英双语同步喵。

### Notes — 验收结论分布

| 批 | 实例 | PASS | FAIL | 不足 |
|---|---|---|---|---|
| 1 | `r2-fix-01`（slice-05） | 4 | 0 | 0 |
| 2 | `r2-fix-02`（slice-03） | 12 | 0 | 1（`f-s03-logic-04`） |
| 3 | 收尾批 6 条 | 5 | **1**（A3） | 0 |
| **合计** | | **21** | **1** | **1** |

> 批 2 的 12 条含 1 条 `f-s03-err-07`（原文即 `NO_EVIDENCE_FOUND`，验收确认派发清单有计数差错）喵。

- **4 条 finding 被两批重复声明**（`f-s03-logic-05`、`f-s03-data-06`、`f-s03-contract-06`、
  `f-s03-contract-07`），加 `f-s05-err-08` 共 5 条喵。逐条读代码确认：**全部由 `r2-fix-04` 落地**，
  `r2-fix-01`/`r2-fix-02` 的 skip 声明在各自权限范围内为真且回滚干净喵。
  **风险**：下游若只读前一份记录，会把这 5 条误判为「未修」喵。
- **A5 的 S3 不是唯一翻转场景**：验收补测后 S1b/S2/S5 也翻转，S1 两版相同（属回归防护）喵。
  验收额外补的 S5（世界不在 `entitiesByWorld` 里）触及真实 NPE 风险点且被正确防护喵。
- **A6 的 `(-\d+)?` 经变异检验确认为必需组**：删掉后碰撞文件 `...-123-1.html`
  由 true 变 false，即「永不清理」——这是分组存在的实证，不是冗余喵。
- **A2 的「0 行代码变更」经行级 diff 确认为真**：`persistSentDate(today);` 逐字节相同，
  只有注释位置变化喵。但**派发时给主 Agent 的机械核验命令是不健全的**——
  `DailySummaryService.java` 同时承载 `r2-fix-03` 的 hunk，文件级 grep 会返回 15 个假阳性，
  必须做**行级**比对喵。
- **`f-s03-logic-04` 仍 `VERIFICATION_INSUFFICIENT`**：改动方向正确且两个既有拦截未被削弱
  （6 场景 A/B 全绿），但 `CLAUDE.md` 规则 10 要求的 ≥30 分钟真实 1Hz 序列回放仍未做喵。
  验收**主动核查了 `%TEMP%` 下的 8 个 CSV，确认全是合成夹具**（时间戳大量重复、`tps` 恒 20.0），
  不能顶替真实数据喵。

---

## [1.3.0] — 2026-11-10 — round2 收尾批：6 条产品语义裁决全部落地

> **背景**：round2 结束时剩 6 条 `UPHELD` 无法由 Agent 自行定夺（`A1`–`A6`，见
> `docs/reports/round1/round1-round2-decisions.md` §A）。作者于 2026-11-04 逐条拍板，
> 本段记录 5 条需要改代码的落地结果 + 1 条仅订正注释。
> **本批不改变任何规则判据的数值语义**，只消除「代码做的与注释/文案说的不一致」。

### Fixed

- **`alerts.refire-min-interval-seconds: 0` 的注释与实现相反**（`f-s01-logic-06`，A1，MEDIUM）。
  `:763` 的 `refireMinIntervalMs > 0 ? refireMinIntervalMs : cooldownMs` 在取 0 时回退到冷却值
  （默认 300s，**抑制更强**），而 `:556` 注释写「0 = 关闭抑制」，`:559` 只拒绝负值。
  - **裁决：按注释改代码**。改为 `Math.max(0L, refireMinIntervalMs)`，
    0 → `refireWindowMs = 0` → `refireWindowMs > 0` 恒假 → `refire` 恒 false，
    复燃落回普通新告警路径（立即推送）。负值在 `:559` 已被拒，`Math.max` 是纵深防御。
  - `config.yml` 补齐该键注释（此前**完全没有**该键的任何说明，而相邻 `:167`/`:193`/`:199`
    三个键都写了「0 = 关闭」），并按同一风格写明 0 的语义与负值回退。
  - **行为变更**：生产环境若配了 0 并期望「更严格」，改后会变成「更宽松」。
    但 `f-s01-logic-08` 已确认该键在 jar 内 `config.yml` 中从未出现过，实际不可能已被人配过。
- **每日摘要游标的注释与实现不符**（`f-s01-data-04`，A2，LOW）。
  `:348` 的 `persistSentDate(today)` 在推送之后**无条件**执行，游标实际语义是「尝试过」，
  原注释却写「发送成功后立即落盘」。
  - **裁决：保持「尝试过」语义，只改注释**。改为「投递路径都是条件性的且 webhook 异步不可观测，
    故无条件落盘；当日不再重试，同日重启也不补发。控制台通道永不空转，故实际送达概率很高」。
    0 行代码变更。
- **时区切换被误判为跨日**（`f-s01-data-05`，A3，MEDIUM）。
  `pushedTodayDayEpoch` 的初值在**构造时**由 `currentDayEpoch()` 算出，那一刻 `options` 还是
  默认值（`dailyTimezone` 空串 → 系统时区）。`start()` → `loadOptions()` 换上配置时区后，
  若新旧时区当下不在同一 `LocalDate`，下一次 `rollDay()` 会误判跨天并把当日四个计数器整体清零。
  - **裁决：视为 bug**。`loadOptions()` 末尾（`this.options = o` 之后）用**新时区**重算基线：
    `this.pushedTodayDayEpoch = currentDayEpoch();`。只动基线字段，计数器值与 `rollDay()` 逻辑不变喵。
- **单世界实体视图打出全服级告警**（`f-s02-logic-03`，A5，MEDIUM）。
  `/perfscope entities <world>` 前半段每个输出点都做了 `world != null` 判断
  （`:552`/`:574`/`:578`/`:627`），但方法**末尾**的告警判定（`:645-649`）无条件读
  `snap.totalEntities` 与全局阈值——只要全服总数过线，单世界页就会在那个世界的小数字下面
  打一条「严重警告：实体总数超过严重阈值！」。
  - **裁决：改判该世界自己的阈值**。`world == null` 保持原行为（全服总数 vs 全局阈值）；
    `world != null` 改读 `snap.entitiesByWorld.get(world.getName())` 与
    `configManager.getWorldSettings(worldName)` 的覆盖阈值，无覆盖时回退全局
    （与 `ThresholdColors` / `showWorld` 同口径）。世界不在 map 里视为 0 实体 → 不报警。
  - **真实 Paper 1.21.11 三场景实测**（`testharness` 实例，BEE 实体 + `worlds:` 覆盖）：
    - S1 全服视图：`world_nether` 5 只 < 1000，全服 8 > 1 → **报警**（正确）喵。
    - S2 世界视图 `world`：2 只 > 该世界 warn 1，全局阈值 1000 → **报警**（正确）喵。
    - S3 核心场景：世界视图 `world_nether` 5 只 < 其阈值 1000，全服 8 > 全局 1 → **静默**喵。
      同一配置下 HEAD 基线 jar 打出了告警，修复后消失，构成 A/B 对照喵。
- **报告清理会把管理员自建文件当己方产物删掉**（`f-s05-sec-03`，A6，LOW）。
  `isReportFileName` 只判前缀 `perfscope-` 与三种后缀，不校验时间戳段；
  管理员在 `reports/` 里自建的 `perfscope-notes.txt` 超期后会被删。而 javadoc `:260-267` 自称
  「用户在 reports/ 里自建的任何文件一律不碰」，**代码与文档直接矛盾**。
  - **裁决：收紧判定**。新增 `REPORT_FILE_NAME` 正则
    `^perfscope-\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{3}(-\d+)?\.(html|txt|json)$`
    （与 `FILE_TS` 的 `yyyy-MM-dd_HH-mm-ss-SSS` 及 `-N` 同名去重后缀对齐）。
  - 探针穷举 19 个样本：插件自有名（含 `-1`/`-10` 去重后缀）旧新皆 true；
    `perfscope-notes.txt`、`perfscope-2026.html`、`perfscope-2026-11-03.txt`、
    `perfscope-.txt` 等 7 个外来名旧 true → 新 false；`notes.txt`、`.HTML` 大写后缀、
    `null` 旧新皆 false（未弱化任何既有拦截）喵。
  - 真实服务端双跑：在 `reports/` 预置 4 个超期文件（含合法名与外来名），
    触发一次报告生成。HEAD 基线删掉 3 个（含 `perfscope-notes.txt` 与 `perfscope-2026.html`），
    修复后只剩合法的 `perfscope-2020-01-01_00-00-00-000.txt` 被删喵。

### Changed

- **TPS 持续低位的内部注释与文案不一致**（`f-s03-logic-05` 残留，A4，LOW）。
  `CoreRules.java:259` 原注释称「15 分钟滚动均值为**主判据**，窗口均值只作**辅助**」，
  但 `:262` 的 `if (!persist15m && !persistWindow)` 是**地位对等的 OR**；
  `DiagnoseMessages` 文案在 `r2-fix-04` 中已改为「任一口径低于告警线即命中」。
  - **裁决：保持 OR**（改 AND 会减少误报但漏报「刚发生的大幅下跌」，超出最小安全差异）。
  - 注释改写为「两个判据地位对等」，并记录不选 AND 的理由。**规则语义零变更**。

### Notes

- **总改动**：6 个源/文档文件 + 1 个配置文件，`git diff --stat -- src/main/resources README*.md`
  实测 `16 files changed, +469/-117`（含 round2 遗留），`mvn clean package` BUILD SUCCESS，
  `PerfScope-1.2.1.jar` 323393 字节喵。
- **i18n 键数不变**：本批未新增任何键——A4 只改内部注释，A5 复用既有
  `cmd.entities.warning`/`cmd.entities.critical-warning`，A1/A2/A3/A6 均为逻辑与注释喵。
- **未提交**：按作者指示，本轮全部工作只落工作区与产物文档，不做 `git commit` 喵。
- **产物命名**：`round1-04-decisions.md` 与改名的 `round1-round2-decisions.md`
  （原 `round1-06-round2-decisions.md`，stage 段 `06` 不在 `report-naming.md` 的闭集里）喵。
  命名偏差与理由登记在 `round1-ledger-r2.md` 的「命名偏差声明」节喵。
- **勘误：README 的 bStats 数据披露已过期**（非审计发现，是收尾自检时发现）喵。
  `README.md:136` 与 `README-EN.md:129` 均写着「作者尚未注册 bStats 项目 ID，**不会发送任何数据**」，
  但 `82f2c13` 已把 `BSTATS_PLUGIN_ID` 从占位 0 改为 **35061**，数据**已在实际上报**喵。
  作者提交 35061 时未同步两份用户文档，构成默认开启的数据外发却告知「不会发送」——喵。
  - 两份文档均改为：默认启用、项目 ID 35061（附 bstats.org 链接）、**数据已实际上报**喵。
    上报内容按 `BstatsCharts.java` 实际注册的 **10 个图表**逐项列举
    （`server_software` / `module_enabled` / `language` / `webhook_channel` /
    `entity_count_bucket` / `loaded_chunks_bucket` / `peak_players_bucket` /
    `heap_max_mb_bucket` / `world_count` / `loaded_plugins`），
    并明确声明「不上传配置阈值、玩家名、世界名或聊天内容」——这两点此前两份 README 都未写喵。
  - 顺带补 `alerts.refire-min-interval-seconds` 到 README 的「主要配置项」块（中英双语），
    使 A1 新增的 config.yml 注释在用户文档里也有对应说明喵。
  - 根 `CLAUDE.md` 的 `plugin.yml` 版本号说法同步更正（详见 round1 §Notes）喵。
- **门控缺口：round2 与收尾批未走第五波次验收**（本轮自查发现）喵。
  `protocol.md` 第 3 节规定每个修复都必须有验收 Agent 的终态，实际只跑了主波次的 8 个喵：

  | 批次 | 修复实例 | 验收实例 |
  |---|---|---|
  | round1 主波次 | 9 | 8 |
  | round2 | 5 | **0** |
  | 收尾批 | 1 | **0** |

  round2 与收尾批的验证由**修复者自己**完成，主 Agent 直接采信返回信封，
  等于让修复者自我验收，违反 `wave5-verify.md` 的隔离原则喵。
  - 已补派独立验收实例，产出 `round1-05-verify-r2-fix-01.md` /
    `round1-05-verify-r2-fix-02.md` / `round1-05-verify-decisions.md`，
    要求对每条 finding 做**变异检验**（找到「修复前失败、修复后通过」的具体场景）喵。
  - 重点核查 5 个可疑点：跨批**重复声明**修复、`f-s03-logic-04` 的合成轨迹是否足够、
    A2 的「0 行代码变更」是否属实、A5 的 S3 是否**真**是唯一靶点、
    A6 正则是否漏掉 `-1` 去重后缀（漏掉即引入新缺陷——插件自有碰撞文件将永不清理）喵。
  - 缺口声明登记在 `round1-ledger-r2.md` 的「门控缺口声明」节喵。
- **最终报告的历史数字订正**：`round1-final-report.md` §5.3 的「总计约 30 条 `UPHELD`」
  实际精确清点为 **41 条**（slice-01: 16 / slice-03: 16 / slice-05: 9）喵。
  已在 §5.3 开头与结尾各加更正说明，并指向 §0.1 的最终去向对账表喵。

---

## [1.3.0] — 2026-11-10 — round2：把 round1 遗留的 41 条 UPHELD 收掉 22 条

> **背景**：round1 的 9 个修复单元只覆盖了 Arbiter `fix_order` 排在前面的问题，
> 剩余 41 条 `UPHELD`（slice-01: 16 / slice-03: 16 / slice-05: 9）按 FIFO 规则留给下一轮。
> 本段记录 round2 批次的执行结果：**修 22 条、跳 19 条**（跳的理由见各条）。

### Fixed（slice-01 alert 域，8 条）

- **静默接管与复燃抑制分支漏写冷却表**（`f-s01-logic-02`，MEDIUM）。
  `:749-765` 接管分支与 `:796-805` quiet 分支都不写 `cooldowns`，导致下一检查周期（约 1s）
  `isCooling` 返回 false 立刻重推，与日志「不重复推送」矛盾。**两处姊妹分支同改**。
  探针：主分支场景 旧=true→新=false；quiet 场景 旧=true（距旧冷却 1211s）→新=false。
  - **行为变更**：接管后从「1s 内重推」变为「静默一整个冷却周期（默认 300s）」，
    与 `rec.restored` 分支口径一致。
- **每日摘要「完整覆盖」分支结构性不可达**（`f-s01-logic-03`，MEDIUM）。
  `thinCoverage` 分母是 `sinceBootMs` 而 `coverageMs` 上界 24h，开机 >48h 后恒为 true。
  分母改为 `min(sinceBootMs, 24h)` 封顶。探针：49h/72h/168h/720h 旧全 true → 新全 false。
- **`thinCoverage` 两原因合并导致文案与数据矛盾**（`f-s01-logic-07`，LOW）。
  拆出 `belowFloor` / `belowRelative`，只触发绝对下限时用新键 `daily.note.floor`。
- **`alerts.state` v2 分支 `p[9]` 越界**（`f-s01-logic-05`，LOW）。
  守卫从 `p.length < 6` 改为 v2 时 `< 10` 则 `continue`。
  探针：坏行夹在 5 条中间，旧 restored=1/5（后 3 条全丢）→ 新 restored=4/5。
- **digest「还有 N 条」传总数**（`f-s01-logic-04`，LOW）。改为 `total - shown`。
  探针：total=6/shown=5，旧 "and 6 more" → 新 "and 1 more"。
- **`daily-summary.state` 非原子写**（`f-s01-data-03`，LOW）。改 tmp + `ATOMIC_MOVE`。
  探针：模拟重写被截断，旧 `loadSentDate=null`（当日重复推送）→ 新返回正确日期。
- **webhook 失败日志限频的 check-then-act**（`f-s01-conc-02`，LOW）。
  `volatile long` 改 `AtomicLong` + CAS。探针：CyclicBarrier 强制交错 200 轮，旧 200/200 重复 → 新 0/200。
- **webhook 回调在插件停用后仍执行插件代码**（`f-s01-err-11`，含已合并的 `f-s01-conc-03`，LOW）。
  `whenComplete` 回调首行加 `plugin.isEnabled()` 守卫。
  - **未完全闭合**：`onDisable` 仍无 `webhookNotifier.close()`（`PerfScope.java` 不在该批写权限内），
    JDK 17 的 `HttpClient` 也未实现 `AutoCloseable`——只缓解了可观测后果，资源句柄本身未消除。

### Fixed（slice-03 config+diagnose 域，11 条）

- **TPS 阈值只校下界**（`f-s03-logic-01`，MEDIUM）。`sanitizePair` 对 TPS 增加上界 20.5
  （TPS 合法域 (0, 20.5]）。真实 Paper 1.21.11 实测：
  `tps.warning_threshold: 25.0` → `is out of range (must satisfy 0 <= value <= 20.5); reverted to the defaults`。
  - **残留**：`(20.0, 20.5]` 仍是恒真区；取 20.0 会误伤「按 20.1 卡住下跌」，故取 20.5，需产品裁决。
- **`history.sample-interval-seconds` 双读者口径分裂**（`f-s03-data-04`，MEDIUM）。
  `ConfigManager` 侧新增 WARNING（**不改值**，避免 breaking change）。
  真实服务端实测会同时打出配置层与 `HistoryStore` 层两条警告。
- **`writeBackConfigVersion` 非原子**（`f-s03-data-03`，MEDIUM）。改临时文件 + `ATOMIC_MOVE`。
  真实服务端用硬链接观察者验证：主文件有新内容、观察者（旧 inode）看不到 → 确为原子替换、无残留 tmp。
- **`alerts.repush-delta` 的 int/double 双读者分叉**（`f-s03-data-07`，LOW）。
  `ConfigManager` 侧 `readInt` 改 `readDouble`。探针：用户写 8.5 时旧判类型错误并称「已改用 8」
  而实际生效 8.5；修复后两侧一致。字节码 `bipush 8/readInt` → `ldc2_w 8.0d/readDouble`。
- **`coverageProblem` 的中位间隔取上中位数**（`f-s03-logic-04`，LOW）。
  偶数个 gaps 改为中间两值均值。探针：场景 A（1,1,1,100,100,100，span=303s）旧 SKIP 误判 → 新 PASS；
  等距/挤在 1s 内/条数不足三种既有拦截未削弱。
  - **残留**：只用合成轨迹验证；CLAUDE.md 规则 10 要求的 ≥30 分钟真实序列回放未做（仓库无真实 CSV）。
- **`validateWorldNames` 的 `catch(Throwable)` 分支零日志**（`f-s03-err-02`，LOW）。
- **`DiagnosticEngine` 不覆盖 `StackOverflowError`**（`f-s03-err-04`，LOW）。
  新增 `catch (StackOverflowError)` 记入 skipped + WARNING；**有意不收 OOM**（javadoc 同步说明）。
- **三处纯文档订正**：`configVersion` javadoc（`f-s03-contract-05`）、
  `coverageProblem` 公式 javadoc（`f-s03-contract-08`）、
  `warnThresholdFallback` 的 `@param` 引用不存在参数（`f-s03-contract-10`）、
  `repush-delta` 注释的因果描述错误（`f-s03-contract-09`）。零行为变更。

### Fixed（slice-05 report+util 域，3 条）

- **预热态被误判为 `degraded`**（`f-s05-logic-01` 的同类残留）。
  `healthOf()` 遍历 `unavailable` 时跳过 `warmingUp` 成员，预热归入 `partial`。
  真实 Paper 双跑：基线 `warming-up → degraded`，修复后 `warming-up → partial`，真采集失败仍 `degraded`。
  - **注**：该 finding 的主体已由此前提交 `8020361` 修复，本条修的是同一谓词上的残留。
- **`ensureRegistered` 的 catch 体零日志**（`f-s05-err-06`，LOW）。补 WARNING。
- **`cleanOldReports` 的删除失败永久静默**（`f-s05-err-07`，LOW）。
  新增 `deleteFailed` 计数，首个失败立即 WARNING、其余汇总一条。
  真实服务端：持 60s 打开句柄后触发报告生成，基线 grep 命中 0，修复后出现含文件名的 WARNING。

### Skipped（19 条，理由分类）

- **写权限外**（4 条）：`f-s05-err-08`、`f-s03-contract-06`、`f-s03-contract-07`、`f-s03-data-06`
  ——目标文件 `util/Messages.java` / `ConfigMessages` / `CommandMessages` 不在该批授权范围。
- **产品语义取舍**（4 条）：`f-s01-logic-06`（`refire-min-interval-seconds=0` 是关闭还是复用冷却值）、
  `f-s01-data-04`（摘要游标 = 尝试过 vs 送达过）、`f-s01-data-05`（时区切换当否算日界翻滚）、
  `f-s03-logic-05`（TPS 规则 OR→AND 会改变规则语义）。
- **超出最小安全差异**（3 条）：`f-s01-err-08`（需把 `gameOk` 布尔改三态）、
  `f-s01-err-10`（`RawConfig` 6 个 getter 补日志属跨切）、`f-s01-err-07`（动机存疑，需先确认是否刻意兜 `NoSuchMethodError`）。
- **非缺陷**（2 条）：`f-s03-err-07`（原文即 `NO_EVIDENCE_FOUND`）、`f-s01-conc-04`（审计自标 LOW 且非活跃缺陷）。
- **需 new i18n 键**（2 条）：`f-s03-data-06`、`f-s03-logic-05`。
- **其余**（4 条）：`f-s05-sec-01`（`NO_EVIDENCE_FOUND` 合法负结果）、`f-s05-sec-03`、
  `f-s05-err-09`、`f-s03-contract-05` 的部分子项——见 `round1-04-r2-fix-*.md`。

### Notes（round2）

- **总改动**：10 个文件 `+495/-48` 行（含 `build.yml` 的 299 行独立改动），
  源文件净增约 196 行。`mvn clean package` BUILD SUCCESS。
- **真实 Paper 1.21.11 端到端验证**：TPS 上界钳制、采样间隔双警告、原子写硬链接观察者
  三项全部通过；零插件异常、干净关服。
- **测试覆盖仍为零**：仓库无 `src/test`，所有验证依赖仓库外临时探针与真实服务端。
- **CLAUDE.md 规则 10 仍未满足**：`f-s03-logic-04` 的窗口类改动只用合成轨迹验证，
  真实 ≥30 分钟采集序列回放未做（仓库无真实 CSV）。

---

## [1.3.0] — 2026-11-10 — v1.3.0 门禁前置修复（红队审查后的 9 项）

> **背景**：v1.3.0 PRD 经 4 个红队 agent 交叉审查后判定「不能直接执行，最少改 9 处」。
> 本段记录这 9 处前置的实际修复。**不含任何面向用户的新功能。**

### Fixed — 不可逆风险
- **`tools/` 内的 AI 署名残留与历史 bundle 已隔离**（红队 A2）。`github-support-request-draft.md`、
  `orphan-commit-removal-record.md` 含 `Co-Authored-By: Claude` 原文与孤儿 commit SHA，
  另有 2 个各 1.5 MB 的完整 git 历史 bundle。全部移入 `tools/_internal/`（带 README 说明禁止公开），
  公开目录已零残留。**这是本批唯一真正不可逆的风险**——代码可回滚，公开出去的 git 历史不能。

### Fixed — 门禁与工具本身
- **断言校验器的核心规则 S1 曾是完全失效的**（红队 A1）。`check-scenarios.mjs` 的
  `fixtureSupplies()` 拿 `JSON.stringify(整个场景)` 当白名单，而断言的期望值必然出现在它自己的
  场景文件里，所以 S1（"断言值能否在源码中找到"）**永远返回 true**。实测：一个期望值全是
  `TOTALLY_FAKE_STRING_XYZQ_NOT_IN_SOURCE` 的场景报 `PASS (0 error, 0 warn)`。
  修复后白名单只含 `preBootFileOps` / `fileOps` / `preCommands` 等**夹具真正写入环境**的内容。
  正控验证：假字符串场景现在报 3 条 `S1-UNREACHABLE-VARIANT` ERROR；真实场景的 S1 计数从 0 → 5。
- **CI 的单元测试从未执行过**（红队 B4）。`.github/workflows/build.yml` 用
  `compgen -G 'src/test/java/**/*.java'`，而 bash 默认 `globstar off`，`**` 不递归 → 恒 0 匹配 →
  `mvn test` 分支永远进不去。改为 `shopt -s globstar nullglob` + 数组展开。
- **CI 门禁脚本此前拿不到**（红队 D/H3）。门禁脚本原在 git 仓库的**父目录**，GitHub runner
  永远看不见。现将 `check-i18n.mjs` / `check-config-compat.mjs` / `check-scenarios.mjs` /
  `verify-release-artifact.mjs` / `local-gate.ps1` / `lib/java-strings.mjs` 复制进
  `PerfScope/tools/`，workflow 改为校验仓库内路径，缺失即 `::error::` 而非静默跳过。
- **`static-gate` / `fingerprint` / `release` 三个 job 此前从未提交**（红队 C6）。工作树有 4 个
  job，git HEAD 只有 1 个。

### Fixed — 构建可复现性
- **`pom.xml` 新增 `project.build.outputTimestamp`**（红队 A3）。此前两次 `mvn clean package`
  源码零改动却产出不同 sha256（zip 元数据带各自时间），使「归档 JAR 与源码一致」这条验收
  **永远无法达成**——而它正是 1.2.1 事故（发布包落后 6 个提交、缺整个 i18n）的唯一防线。
  实测修复后连续两次构建 sha256 完全相同。

### Fixed — 测试断言（红队 C2 + 独立发现）
- **10 条堆栈守卫从未生效**。Paper 的栈帧格式是 `at PerfScope-1.2.1.jar/cn.perfscope.X.y(...)`，
  而断言写的是字面量 `at cn.perfscope`——36 个日志文件 0 命中。`cn\.perfscope` 正则同时匹配
  Paper 格式与旧式格式。涉及 `inject06`、`soak03a~h`（8 条）、`surface/backstop-no-perfscope-stacktrace`。
- **12 条 `monitor 推送失败` 守卫是死的**。该中文串在 i18n 改造（`3826bb3`）后已改为英文
  `monitor push failed`，断言未同步。446 份日志 0 命中。现扩为
  `monitor push failed|console push failed|控制台推送失败`，同时覆盖 monitor 与 alert 两条推送路径。
- **`alertv2/up01` 的双反斜杠正则永不能匹配**。JSON 解码后是字面反斜杠 + 字符类；
  历史 `result.json` 显示 2026-10-29 之前是单反斜杠、当时可用，属后引入的回归。
  改为断言升级后缀 `level escalated` / `（级别升级）` 的字面双语锚点。
- **恢复类负向守卫收窄**。`notPresent: ["recovered"]` 会误伤控制台的
  `(downgraded ... not recovered)` 缓解提示行。改为 `" recovered |"` / `" 已恢复正常 |"`
  （竖线分隔符只出现在真实恢复行的结构化后缀里）。

### Notes
- **`alert-recovery-notice` 确认为环境敏感 flaky，非产品缺陷**。它要求内存告警先处于激活态
  才有恢复可等；宿主机堆内存充裕时告警根本不触发。历史 40 次运行 5 次失败，其中 2 次是
  RCON 连接错误。已在场景 `description` 中写明判据，避免下次误判为回归。
- **校验器仍有 5 条 `S1-UNREACHABLE-VARIANT` 警告未修**：它们是**校验器的误报**
  （源码字符串含 `{0}` 占位符，与填充后的断言文本无法精确匹配），不是产品问题。
  已知局限，已记录待改进。

---

## [1.3.0] — 2026-11-10 — round1 修复收尾：JSON 成因区分 + 报告超时告警本地化

> **背景**：round1 独立审计（57 份产物，179 条原始发现 → 9 个修复单元，8/8 PASS）
> 遗留 4 项 `HUMAN_DECISION_REQUIRED`。本文档段落记录其中两项的执行结果。

### Changed
- **JSON 报告的不可用段新增 `cause` 字段**，区分三种成因：
  `platform`（平台跳过，如 Folia）/ `disabled`（服主在配置里关掉）/ `collect_failed`（采集失败）。
  此前所有不可用段都是恒定的 `{"status":"unavailable"}`，导致"自己关了 `mspt.enabled`"与
  "采集抛异常"在 JSON 里**逐字节不可区分**。HTML/TXT 两侧在 round1 已按成因区分，JSON 是唯一的缺口。
  成因判定优先级依 `platformSkipped ⊆ disabledModules ⊆ unavailable` 的集合契约：
  平台跳过 → 配置禁用 → 采集失败。顺序不能反——Folia 上被跳过的模块同时也在 `disabledModules` 里。
- **`alerts.health` 不再把"配置禁用"算成 `degraded`**。取值改为三档：
  `ok`（无不可用）/ `partial`（仅配置禁用或平台跳过，无采集失败）/ `degraded`（有真正的采集失败）。
  修复前该字段直接取 `snap.unavailable.isEmpty()`，于是**按配置正常运行的服务器会被永久报异常**——
  依赖该字段做 CI 门禁或 Grafana 抓取的下游会持续收到假告警（`f-s05-logic-01`，round1 端到端坐实）。

### Fixed
- **报告超时告警未走 i18n 键表**（round1 N-2）。`ReportGenerator` 的 `TimeoutException` 分支
  原本输出硬编码英文，而同文件其余 8 条控制台告警全部走 `report.log.*` 键，
  `language: zh` 的服务器会看到语言混用。现改为 `report.log.await-timeout`。
- **超时告警文案补上"后台可能仍会生成"**（round1 N-1）：超时后不 cancel 任务，被放行的任务
  可能随后落盘，用户在聊天框看到"失败"后又冒出文件的认知冲突由此消除。

### Verification
- **真实 Paper 服务端端到端**（`verify-json-cause.ps1`）：
  - 正常：`health=ok`，mspt 段无 `cause`
  - `mspt.enabled=false` + reload：`mspt.status=unavailable`，**`mspt.cause=disabled`**，
    **`health=partial`**（修复前为 `degraded`）
  - 恢复后：`health=ok`，零残留
- **回归**：`report` 10/10、`config` 11/11、`boot` 6/6、`diagnose` 9/9、`i18n` 7/7 全绿。
- `f-s06-conc-02` 的超时路径已在 round1 由 `verify-06` 用真实 `submitAndAwait` 驱动验证
  （基线 45s 后 `waiterAlive=true`，当前 30.08s 返回 `null`），本轮补齐其附带的两条文案缺陷。

---

## [1.3.0] — 2026-11-10 — Wave 10「bStats 自定义图表：把"有没有真实用户"变成可验证信号」

> **背景**：`EXECUTION-PLAN.md §5` 把 ROADMAP V2.0 全部条目（Prometheus / Grafana / PAPI /
> GUI / 英文包 / LLM）冻结在「出现真实用户信号前」。这个约束本身合理——别在没人用的时候
> 做没人要的东西——但"信号"从哪来、怎么量，一直没有定义。bStats 是把口头需求变成可验证
> 数字的最低成本手段：它不面向用户，却是路线图排期的唯一客观依据。
>
> 此前 `BSTATS_PLUGIN_ID = 0` 是占位，代码路径完备但**零上报**。

### Added
- **bStats 自定义图表**（`BstatsCharts`，10 张）。基础匿名数据只能回答"有多少人在用"，
  回答不了路线图真正要问的三类问题，故按问题分组：
  - **规模**：`entity_count_bucket` / `loaded_chunks_bucket` / `peak_players_bucket` /
    `heap_max_mb_bucket` / `world_count` —— 判断"大服到底有多大"，是性能优化排期的前置证据。
  - **采用率**：`module_enabled`（11 个采集模块的开关矩阵）/ `webhook_channel` ——
    判断哪些功能真有人开、哪些可以砍。
  - **i18n 收益**：`language` —— 判断英文默认是否真的被使用，还是一直有人在改回中文。
  - 另有 `server_software`（Paper/Spigot/Bukkit/Folia 分布）与 `loaded_plugins`（插件数）。
- **隐私边界写进代码与配置注释**：只上报分桶后的整数与枚举，不上报玩家名、世界名、
  插件列表、配置内容、告警文本、坐标。分桶有意为之——既能判断量级，又让单条记录无法
  反推到具体服务器。`config.yml` 的 `general.bstats-enabled` 注释已列出全部上报字段。

### Fixed
- **bStats 句柄未保存导致无法关闭**（潜在资源泄漏）。此前 `new Metrics(...)` 的返回值被丢弃，
  `Metrics#shutdown()` 无从调用；bStats 的提交线程在服务器关闭时会拖住 JVM。
  现在持有句柄并在 `onDisable` 末尾（全部采集/告警/报告停稳之后）显式关闭。

### Notes
- **bStats 项目 ID 已填入**：`BSTATS_PLUGIN_ID = 35061`（2026-11-01 在 bstats.org 注册，
  项目名 `PerfScope`，平台 Bukkit）。此前是占位 `0`，全部图表代码从未上报过任何数据。
- 图表回调全部在 bStats 自己的线程上执行，只读不可变快照与配置对象，不触碰 Bukkit 世界状态。
  快照未就绪时返回空 Map（bStats 跳过该次上报），**不记 0**——记 0 会污染统计。

---

## [1.3.0] — 2026-11-10 — Wave 9D「i18n：命令层正文 + 报告 + 告警信封 + 诊断规则」

> **本批次把"英文默认"真正落到全站**：命令面板正文、报告三格式、告警信封、诊断规则、
> 启动横幅、兼容层说明——原先约 900 条硬编码中文字面量，现在全部经由消息层。
> 加上前两批的标题与告警正文，`/perfscope` 在 `language: en` 下**整页无中文**。

### Added
- **模块自带消息表机制**（`Messages.registerModule` / `onFirstLoad`）。此前所有文案键必须写进
  `Messages` 的静态块，那会让它膨胀成两千行的**唯一写入热点**，多路改造无法并行。
  现在各层自带一张表（`CommandMessages` 251 键、`AlertMessages` 102、`ReportMessages` 76、
  `DiagnoseMessages` 35、`CoreMessages` 21），静态块里注册，`load()` 时统一合并。
  合并结果与"直接写在一个类里"完全等价（同一张表、同一条回退链）。
- 五个模块消息类，**合计 504 个键**（`CommandMessages` 251 / `AlertMessages` 100 / `ReportMessages` 76 / `DiagnoseMessages` 35 / `CoreMessages` 42），**中英键集合完全一致、占位符零错配**。

### Changed
- **`PerfScopeCommand.java`：代码中文字面量 4327 → 0**（注释保留）。去重生效：
  `不可用` 在命令层出现 62 处 → 只剩 1 个键供 41 个位点共用；`未知`/`预热中`/`未采集` 同理。
- **`ReportGenerator.java`：105 → 0**。`<html lang>` 改为跟随 `Messages.activeLocale()`
  （`en` / `zh-CN`）——此前英文报告仍自称 `lang="zh-CN"`，对浏览器与读屏软件都是错的。
  HTML 文案新增一层 `escapeHtml`：`messages.yml` 是用户可编辑的，英文的 `Min & max`
  直接拼进标记就会破版。
- **`AlertService.java` 122 → 0、`DailySummaryService.java` 38 → 0**。告警**信封**此前未翻译——
  英文模式下会出现「`System CPU load high: 92.1%` + `| key=cpu:system 值=92.1% 阈值(进程 > 80.0%…) | 持续 3m | 下一步: 检查宿主机…`」
  这种半吊子输出。现在 `[告警][严重][motd]` → `[Alert][Critical][motd]`，
  `值=` → `value=`、`阈值(...)` → `threshold(...)`、`持续` → `lasted`、`下一步:` → `next step:`。
  **`key=cpu:system` 保持 ASCII 不变**（日志检索锚点）。
- **`CoreRules.java` 79 → 0、`DiagnosticEngine.java` 6 → 0**。规则 ID（`DIAG-TPS-PERSIST` 等）
  保持 ASCII；`/spark`、`/perfscope` 等命令路径在两种语言里逐字相同（翻译命令 = 给出坏指令）。
- **`ServerCompat` 的 MSPT 不可用原因改为消息键**：该串会进 `/ps`、报告、启动横幅，
  留着中文会让英文报告中出现一句中文。
- 器时长格式化统一走 `DurationFormat`：中文由「分钟/小时」改为「分/时」，
  与既有的 uptime 路径一致；英文为 `3m 15s` / `2d 5h`。

### Contract preserved（明确未动）
- `alerts.jsonl` 字段名 `ts/type/key/level/value/display`、事件令牌（`open`/`resolve`/…）、
  级别令牌（`warning`/`critical`）、模块常量、CSV schema、
  报告 JSON 的全部键名与字面值（`unavailable`/`ok`/`degraded`/`tick_times`/`avg_tick`）——
  这些是外部契约，逐项核对未变。
  **例外（本版确实变了，且有兼容与备份）**：`alerts.state` 的列头从 8 列升为 10 列
  （新增 `scope`/`threshold`/`exitThreshold`，去掉渲染串 `display`），带 `# perfscope-alert-state v=2`
  版本头；旧文件仍可读，首次覆写前自动备份为 `alerts.state.v1.bak-<时间戳>`。

### Notes
- **`PerfScopeCommand` 曾在一路改造中被误用 `git checkout` 覆盖**，随后从文件历史快照完整还原，
  并复核 9B-5 的 monitor 原子化等既有改动仍在、签名零差异。
- **测试断言改为双语**：新增 100+ 处 `["English", "中文"]` 数组断言（正向任一命中 / 负向全部不出现）。
  转换过程中出现过一次**嵌套数组**的脚本 bug（连续子串替换把已转换的数组又包了一层），
  已加扁平化工具修复并全量校验 `nested=0`。
- 尚未翻译：`config.yml` 注释（120 行）、`plugin.yml` 权限描述以外的少数字段、
  以及 `DiagnosticEngine` 之外的日志。这些不影响"用户可见文案整页一致"。

> **范围**：Phase 2（告警结构化）+ Phase 1 的第一批（24 个章节标题）。
> 两者必须同时交付——只做一半会让 `/perfscope` 变成"英文标题 + 中文正文"的更糟混排。
> 审计依据：`docs/01-reviews/I18N-AUDIT.md`；设计复核：9 路 Agent（规划/执行/红队/检索）。

### Added
- **`Metric` 枚举（9 个指标）**：`TPS`/`MSPT`/`PLAYERS`/`ENTITIES`/`ENTITIES_WORLD`/`CHUNKS_WORLD`/
  `MEMORY`/`CPU_PROCESS`/`CPU_SYSTEM`。为什么不是 7 个：`cpu:process` 与 `cpu:system` 的标题、
  阈值、迟滞提示都不同；按 key 前缀归并（旧的 `metricOf()` 两者都返回 `cpu`）恰恰会丢掉区分它们的信息。
- **`AlertCatalog`**：把结构化 `Alert` 按当前语言渲染成文案。数值与单位由 `Metric.Unit` 决定，
  **与语言无关**——同一份数据在两种语言下读出来是同一个数，只有描述文字在变。
- **`DurationFormat`**：把仓库里 5 份互相矛盾的时长格式化器（`AlertService`/`DailySummaryService`/
  `PerfScopeCommand`×2/`ReportGenerator`）收敛成一份本地化实现。
- **24 个 `cmd.section.*` 标题键** + **43 个 `alert.*` 告警键**（含 8 个 `.title.scoped`），中英各一套。

### Changed
- **`Alert` 从"文案容器"改为"结构化测量记录"**。此前 `message`/`display` 是已渲染的中文串，
  而三个解析器又反过来读它（`displayOf` 切冒号、`valueOf` 取值、`numericValueOf` 扫数字）。
  本机实测这三种解析在文案改动后会**静默**失效：
  - `"System CPU load high: 92.1% (threshold: 90%)"` → 值被解析成 **90.0**（阈值而非实测值）
  - `"Entities: 3,000"` → 解析成 **3.0**（千分位截断，3000→8000 永不触发重推判定）
  - 译文无冒号 → 值解析为 `null` → 重推抑制永久失效（每 300 秒一条，永不停止）

  现在 `Alert` 只存 `metric`/`value`/`reference`/`scope`/`threshold`/`exitThreshold`，
  文案在**展示的那一刻**按语言现渲染。`message()`/`display()` 保留为派生方法（调用点零改动），
  但它们的返回值**随语言变化**——这正是想要的语义。
- **`sameAlert` 改为比较结构**（级别 + 原始数值 + 阈值）。它此前比较 `message().equals()`，
  而 9D 起 `message()` 是按语言现渲染的派生值，用字符串当身份会导致：
  数值变化检测不到（`alerts.state` 停在旧值）、渲染精度把 92.11/92.14 抹成同一身份、
  切语言让所有活跃告警都判为"变了"。
- **`alerts.state` 升级到 v2 格式**：`key|level|firstSeen|lastSeen|value|scope|threshold|exit|module|state`。
  加了显式版本头 `# perfscope-alert-state v=2`（此前只能靠列数猜格式，降级到旧版会把 `scope` 当
  `display`、把 `threshold` 当 `module`，然后立刻用旧格式覆写，结构化数据永久丢失）。
  **首次以 v2 覆写前会自动备份 `alerts.state.v1.bak-<时间戳>`**。

### Fixed（红队复核后修掉的 3 个 Blocker）
- **所有非世界维度告警丢失数值**（Blocker-1）。初版对所有模板一律传 `(scope, value)`，
  但非世界维度模板只写 `{0}`（值是第 0 个参数），于是渲染成 `"Heap usage high: "`——数值没了，
  而且零异常零日志，波及控制台/游戏内/webhook/`/ps`/HTML/TXT/**JSON 报告**全部表面。
  改为按指标决定参数顺序：世界维度 `(scope, value)`、玩家数 `(value, reference)`、其余 `(value)`。
  实测复核：`players` 现在是 `19/20`（初版是 `/19`——容量也丢了）。
- **世界维度告警的恢复文案显示原始指标 id**（Blocker-2）。`title()` 会拼 `.title.scoped`，
  但该键从未定义，于是恢复通知变成 `entities_world warning recovered`——在中文界面里冒出英文内部 id。
  补了 8 个 `.title.scoped` 键（2 指标 × 2 级别 × 2 语言）。
- **重启后重推抑制永久失效**（Blocker-3）。从 v1 状态文件恢复的告警 `value = NaN`，
  构造函数把 `lastPushedValue` 置成 `null`；而 restore 分支只刷新 `alert`/`value`/`module`，
  **没有刷新基准**，于是 `significant` 恒为 `true` → 每 300 秒重推一次，永不停止——
  正是 9C-4 修掉的"σ=12 时每 6 小时 59 条"重新出现。两个 restore 分支（restart / suspended）都已补上基准重置。
- **删除 `Alert.of(...)` 兼容工厂**：它唯一的运行时语义是把 `value` 置成 `NaN`，
  进而**静默禁用一道生产安全网**。调用点已归零（项目无对外 API 契约），保留等于给下一个人挖坑。

### Notes
- **Phase 1 的命令层正文（约 129 条用户可见字面量）尚未翻译**：`/perfscope` 目前是
  "英文标题 + 中文正文"。这是有意的中间态——命令层 129 条 + 报告 92 条 + `AlertService` 的
  报警信封/下一步建议（111 条）需要各自独立的工作包与测试更新，按计划分批推进。
- `alerts.jsonl` **尚未**迁移（仍存渲染串）。红队指出 CHANGELOG 早前称"历史会按当前语言重渲染"
  对已写入的行是**不成立的**——该说法已在此更正。迁移方案（加 `v` + `value_raw`，读时重渲染）记入待办。
- `activeWarnings`/`activeCriticals` 仍是采集时渲染的 `List<String>`；红队建议改为输出时渲染，
  未在本轮实施。

### Added（命令层正文，Phase 1 第二批）
- **`CommandMessages`**（`cn.perfscope.command`）：命令面板正文的 252 条中英键，按 `cmd.<面板>.<元素>`
  命名。跨面板共用的状态词收成 `cmd.state.*`——"不可用"在命令层出现 19 次、合计 62 处，
  现在只有**一条**键（此前每处各写一份，改一处不可能改全）。
- **`Messages.onFirstLoad(CommandMessages::init)`** 在 `PerfScope.onEnable` 登记；
  `PerfScopeCommand` 的静态块再触碰一次（`registerModule` 幂等），漏登记时键也不会静默退化成键名。
- 帮助行拆成 `cmd.help.<子命令>.display`（用法）/ `.desc`（描述）两组键。

### Changed（命令层正文，Phase 1 第二批）
- **`PerfScopeCommand` 的 354 条 CJK 用户可见字面量全部改为 `Messages.get(...)`**：
  代码中的 CJK 字符数（不含注释）从 4327 降到 **0**。控制台诊断日志（世界实体扫描失败、
  monitor 推送失败）一并转英文——它们与面板同属命令层输出，留着中文就是混排。
- **`STATUS_MODULES` 的第二列从展示名改为消息键**（`cmd.status.module.*`），展示时经 `Messages.get`。
- **`clickCmd` 参数明确不本地化**，并在 `sendHelpLine` 上写明理由：它是 `ClickEvent.runCommand` 的
  实际执行内容，跟着语言变成 `/perfscope entities [世界]` 会把占位符当真实世界名查询
  （A5/T5a 修过的原始 bug），译成描述文字则直接报未知命令。只有 display 与 desc 属于界面文案。
- 帮助行的前导两空格仍留在代码里（`"  " + display`），不进消息值——它是排版而非文案。
- 结构化差异（英文语序/单位/括号形态不同，不能靠拼接得到）：
  `在线玩家: 0 / 20` → `Players online: 0 / 20`；
  `覆盖: 近 N 分钟（M 个样本）` → `Coverage: last N min (M samples)`；
  `已停止更新 {0}（最后采集 {1}）` → `Stopped updating {0} ago (last collection {1})`；
  `预热中（首轮采集未就绪，稍候自动更新）` → `Warming up (first collection not ready; updates automatically)`；
  `共 N 条（旧→新）；当前活跃 A 条，模块不可用转 unknown B 条` →
  `N entries (oldest first); A currently active, B suspended because their module is unavailable`；
  ActionBar 的 6 段由 `" | 玩家: "` 硬拼接改为 `cmd.actionbar.segment` + `cmd.unit.*` 组合。


---

## [1.3.0] — 2026-11-10 — Wave 9D「i18n 机制」（Phase 0，不含命令层/告警翻译）

> **范围**：只搭机制、翻译已有的 14 个文案键。**命令面板 / 告警 / 报告 / 控制台日志仍是中文**，
> 按既定计划与 Phase 1（`PerfScopeCommand` 354 条）+ Phase 2（告警结构化）一起交付。
> 审计依据：`docs/01-reviews/I18N-AUDIT.md`（3 路 Agent + 本机实证）。

### Added
- **`general.language` 配置键**（默认 `en`）。接受 `en`/`en_US`/`en-us`、`zh`/`zh_CN`/`zh-TW`，
  按主语言归一；其余值告警后回退 `en`。改后 `/perfscope reload` 生效。
- **`Messages.get(key, Object... args)`**：`{0}`/`{1}` 占位符。
  **刻意不用 `java.text.MessageFormat`**——实测它会：把 `Server's TPS is {0}` 变成 `Servers TPS is {0}`
  （单引号吞掉占位符且删掉撇号）、对 `{not-a-placeholder}` 与 JSON 片段抛
  `IllegalArgumentException`。改用只匹配 `\{\d+\}` 的手写替换，其余花括号原样保留。
- **双语断言支持**（harness）：`pattern`/`expect`/`expectNot`/`contains` 可写数组，
  数组 = 正向任一命中、负向全部不得出现。单字符串行为逐字不变。
  实现在 `scripts/lib/util.ps1` 的 `Get-MatchVariants`/`Select-MatchVariant`/`Format-MatchVariants`。
  已验证 11 个语义用例（单值、任选、负向双向、空数组、正则）全通过。
- **`util/DurationFormat`**：时长/运行时间的唯一实现（`duration(millis)` / `uptime(millis)`），
  按 `Messages.activeLocale()` 输出中文（`2 天 5 时 12 分`）或英文（`2d 5h 12m`），
  数字固定走 `Locale.ROOT`。取代原先散落 5 处的私有实现（`AlertService` / `DailySummaryService` /
  `PerfScopeCommand` ×2 / `ReportGenerator`），后五者全部改为委托，硬编码中文副本删除。

### Changed
- **默认语言改为英文**。14 个文案键同时提供 en/zh 两套，**两种语言都不含「喵」口癖**
  （面向服务器运维者，按专业工具口径书写）。
- **`messages.yml` 改为带 `language:` 标签的单文件**（方案 a，而非 `messages_en.yml` + `messages_zh.yml` 方案 b）。
  理由：两份文件意味着用户必须在"当前生效的那一份"里编辑，改错文件时插件不会给出任何反馈——
  这正是本项目在 `config.yml` 未知键检测里反复避免的静默失效。单文件 + 显式标签只有一种编辑入口，
  "文件不生效"必然伴随一条指名道姓的 WARNING。
- **语言快照整体发布**：语言 + 默认表 + 覆盖表被打包成一个不可变 record，由 `volatile` 字段一次性替换。
  读线程要么看到旧三元组、要么看到新三元组，**不可能**读到"英文默认 + 中文覆盖"这种撕裂组合。
- `src/main/java/cn/perfscope/util/Messages.java` 的反射读配置**已移除**，改为直连
  `ConfigData.general_language`——字段就位后反射只剩脆弱性（改名会静默退化成默认语言，而非编译失败）。

### Fixed（红队复核后修掉的 4 个 Blocker）
- **用户自定义的 `messages.yml` 不再被静默移走**。初版无条件把旧文件移进 `.bak` 并重新生成，
  用户的定制文案**静默停止生效**。现在先逐键比对该文件与内置默认：完全一致才自动迁移，
  有任何键不同就**原地保留 + 警告**，并把"该怎么修"写清楚。
- **reload 回执不再谎报成功**。`messages.yml` 因语言标签不匹配/无法识别而未生效时，
  回执追加一行「注意: …本次未生效」——此前玩家只会看到一句"配置已重载"，
  而他的全部覆盖其实被丢弃了（控制台的 WARNING 他多半看不到）。
- **删除死资源 `src/main/resources/messages.yml`**：新实现从代码表生成，该文件已无任何 `getResource` 引用，
  却仍在 jar 里带着 12 处「喵」，会误导排查者。
- **`plugin.yml` 的 `permission-message` 与插件 `description`、权限描述全部英文化**：
  前者是 `Messages.load()` 之前的首帧兜底，在英文默认下留着中文自相矛盾。
- **时长文案统一口径**（用户可见）：告警"持续"、`status` 停滞时长、`/perfscope` 运行时间、
  报告运行时间改用同一套单位（`天/时/分/秒`）与省略规则。三处可见变化：
  ①"3 分钟" → "3 分"、"1 小时" → "1 时"（与面板其余处一致）；
  ②窗口内为 0 的分量不再打印（`2天 5时 12分 0秒` → `2 天 5 时 12 分`）；
  ③运行时间最多三级，`duration` 最多两级（超出部分向下取整）。

### Notes（诚实记录）
- **Phase 0 交付后界面仍是"回执英文 + 面板中文"**。这是刻意的边界，不是遗漏：
  14 个键无法承载整语言切换。已按拍板结论把 Phase 1（命令层）+ Phase 2（告警）
  合并到同一次交付，届时 `/perfscope` 全页才真正整页切语言。
- **切换语言后的 4 类"不可追回"表面**（已写入 README）：`plugin.yml` 插件简介、
  已导出的报告文件、已写入的 `alerts.jsonl` 历史（会按当前语言重渲染）、
  切换瞬间已排队发出的 webhook 消息。
- **`alerts.state` / `alerts.jsonl` 目前存渲染串**。Phase 2 会改成存 `key`+`level`+`value`、
  读时重渲染——这是"全有或全无"真正可达的前提。
- 测试服 `config.yml` 曾被我早前的 PowerShell 改写损坏成 GBK 编码（中文注释乱码并把
  `update_interval` 吞进注释行），已用插件自带默认配置重建。教训：**不要用 PowerShell
  `Set-Content` 改写 UTF-8 的 YAML**。

---

## [1.3.0] — 2026-11-10 — Wave 9C-4「停止刷屏」

> **来源**：用户现场截图——`cpu:system` 在 `告警 → 恢复 → 复燃` 之间每 30 秒循环一次，
> 5 分钟内 11 条消息，而配置里的 `alerts.cooldown-seconds: 300` 完全没生效。
> **调查**：`docs/04-dev-reports/CPU-ALERT-SAWTOOTH-EXPERIMENT.md`（含真机实验数据）。

**根因是三层叠加，缺一层都压不住：**

| 层 | 缺陷 | 后果 |
|---|---|---|
| 采集层 | 进入与退出共用同一个阈值，无数值迟滞 | CPU 采样 σ≈12 个百分点，样本在 55%–100% 摆动，每秒都在穿越阈值 |
| 决策层 | 复燃分支用 `!refire &&` 同时短路防抖与冷却 | "恢复→复燃"每几秒循环，300 秒冷却形同虚设 |
| 决策层 | 恢复时 `cooldowns.remove(key)` | 即使复燃去查冷却，也永远查不到参照点 |
| 决策层 | 同级别告警"冷却到期就重推" | 长期 92% 的告警每 5 分钟重说一遍同样的话 |

**实测数据（6 小时轨迹，1Hz 采样）：**

| 轨迹 | 修复前 | 修复后 |
|---|---|---|
| 均值 91.5%、σ=4（用户场景：阈值边界抖动） | **1333 条** | **4 条** |
| 均值 97%、σ=2（真正的持续满载） | 72 条 | **1 条** |
| 均值 60% + 20 秒尖峰（正常机器偶发） | 2 条 | 2 条（不变） |
| 均值 70% + 1 小时真事故 | 13 条 | 2 条（`open@8002` / `resolve@11602`，时间点精确） |

### Added
- **`cpu.hysteresis_margin`（默认 5.0，合法 0–50）**：CPU 告警迟滞带宽度。
  进入用阈值本身，退出用「阈值 − 本值」。落在带内的样本保持原状态，吸收采样噪声。
  设 0 = 关闭（退回旧行为）。取值依据见实验报告：margin=0 时 1333 条，margin=3 降到 33 条，margin=5 降到 1 条。
- **`alerts.flap-window-seconds`（默认 60）**：抖动窗口。该窗口内的"恢复→再次越界"视为同一次事故的中间态，
  不推"已恢复正常"也不推"问题再次出现"。用户日志里 5 次恢复有 4 次发生在 2–40 秒内。
- **`alerts.repush-delta`（默认 8）**：同级别重推的数值变化阈值。低于该幅度视为"没有新信息"。

### Changed
- **`cpu:system` 的告警按迟滞判定**：`MetricsCollector.collectCPU` 引入 `systemCpuLatched` / `processCpuLatched`
  两个闩锁字段，`latched()` 纯函数实现状态转移。闩锁放在采集层而非决策层，
  因为该类约定"采集层判定阈值、决策层只管投递"，且 7 类指标的阈值方向不同（TPS 是低值告警）。
- **复燃不再无条件绕过冷却**：改为"绕过防抖、遵守抖动窗口"。参照点用**恢复时刻**而非"上次推送时刻"
  （后者可能是几十分钟前的首次告警，用它做参照永远成立，等于没抑制）。
  被抑制的复燃会**静默接管**为 active 状态（否则该 key 会进入"既不在 active 也不在待确认"的不可见状态，永远不再恢复）。
- **恢复时不再清除冷却记录**：原先那一行让复燃路径上的冷却判定永远为 false。

### Added（抖动升级）
- **抖动计数与痊愈计时**：同一 key 在抖动窗口内被抑制 ≥3 次后，恢复判据从 3 个干净周期提高到 60 个
  （`FLAPPING_RECOVERY_CYCLES`）。计数在**连续干净 300 周期**后才清零（`FLAP_HEAL_CYCLES`）——
  若在每次恢复时清零，一条"每 20 分钟振荡一次"的告警永远攒不满 3 次，恢复判据永远停在 3 秒。
  实测这一步把用户场景从 17 条进一步压到 4 条。
- 新事件类型 `refire_suppressed` 记入 `alerts.jsonl`，便于事后审计"哪些通知被压掉了"。

### Fixed（红队复核后追加）
- **"稳定告警不重推"的比较基准写错**（红队 D1）。初版用 `rec.numericValue` 且每周期无条件更新，
  比的是"相邻两个 1Hz 样本的差"——在 σ≈12 的机器上 `P(|Δ|≥8)=13.6%`，300 秒内必然触发，抑制形同虚设。
  改成"上次推送值"作基准后**反而更差**（随机游走的差随 √Δt 单调增长，σ=12 时 59 条/6h）。
  最终方案是**与滑动窗口均值比较**：均值把噪声压到 σ/√60，同时完整保留系统性漂移。
  实测三个基准在 8 类轨迹上的对比见 `docs/04-dev-reports/CPU-ALERT-SAWTOOTH-EXPERIMENT.md` §7。
- **`alerts.refire-min-interval-seconds` 是死配置**（红队 D4）：声明并解析校验，但全文无读取点，
  复燃窗口实际用的是 `cooldown-seconds`。现已接上——复燃判定使用该键，与冷却语义解耦。
- **`flap-window-seconds` 会被 `cooldown-seconds` 静默截断**（红队 D3）：`lastResolvedAt` 的保留期
  用的是 `max(cooldown, 60s)`，用户把抖动窗口调到大于冷却秒数时抑制会静默失效。
  改为 `max(cooldown, flapWindow, 60s)`。
- **`/alerts` 显示原始英文事件名**（红队 D6）：`refire_suppressed` / `flap_suppressed` 无中文映射，
  中文界面里会冒出 `[refire_suppressed]`。已补「抖动抑制」。
- **迟滞带内告警文案自相矛盾**（红队 D7）：值 84.3% 却显示「阈值 > 90%」，因为退出线是 85%。
  详情追加「（迟滞中：需跌破 85% 才解除）」，让"为什么 84% 还在告警"当场有答案。

### Fixed（真实数据复核后追加 — 修正了本段上文的数字夸大）
- **"稳定抖动"的恢复判据从"周期数"改为"持续时间"**。原实现要求连续 60 个干净周期，
  但真实数据（30 分钟 1791 个 1Hz 样本）显示：跌破退出线的最长连续干净段**只有 24 个周期**，
  扫遍阈值 86–98 也从未超过 42。于是判据**结构性不可达**，告警会永久挂在 active、永远关不掉。
  更糟的是被抖动窗口挡下时 `recoveryCounts` 会清零重来，"数到 60"永远数不到。
  改为"干净持续 ≥ `flap-window-seconds`"后，30 分钟消息数从 7 降到 **2**。
  用时间而非周期数还顺带消除了一个隐患：`general.update_interval` 可配，
  周期数判据的含义会随采样间隔漂移，时间判据不会。
- **上文的合成轨迹数字需要按真实数据校准**。"1333 条 → 4 条"来自 σ=4.0 的合成轨迹；
  真实背景 σ 是 **13.0**（3.2 倍），lag-60 自相关 0.413 而非 AR(1) 的 0.046。
  真实数据回放是 **121 条 → 2 条（30 分钟）**——仍是数量级改善，但**不要引用合成轨迹的数字**。
  另：真实数据上"仅迟滞"是 121→117（几乎无效果），"仅抖动窗口"是 121→7（主力）。
  迟滞仍值得保留（成本为零、保护阈值双向抖动），但**不是主要机制**。

### Notes（红队指出但本版未修，记入待办）
- 抖动升级在"越界周期 ≥ 60 秒"时有断崖（59 秒 4 条 / 60 秒 91 条）。上一条已把恢复判据
  改为时间维度，缓解了断崖的根源（判据不再依赖周期数）；但"越界段本身很长"时的行为仍未单独验证。
- `numericValueOf()` 依赖"消息末尾最后一个冒号后是数字"。若将来给文案加 `(阈值: 90%)`，
  会解析到常量 90 并静默永久抑制重推。根治需给 `Alert` 增加结构化数值字段。
- **未在用户的真实 E5 服务器上验证**：本次实验在本机 E5-2660 v2 上做，但负载画像不同
  （本机中位数 53%，用户机器中位数 > 90%）。合成轨迹是 AR(1) 近似，真实负载可能存在
  分钟级相关性（那会让迟滞效果**更好**，不改变结论方向）。
- **`repush-delta` 只作用于 `cpu:system`**（`REPUSH_SUPPRESSIBLE_KEYS` 白名单）。
  内存/实体/区块/TPS 等可处置指标不受影响——抑制它们会让真实问题静默，
  且会让 `alert02b` 这类正向断言失效（本次实现中实测到过这个回归并已修正）。
- **未修改 `perfscope-alert` 等断言**：全部 11 个场景零改动通过。

---

## [1.2.1] — 2026-10-25

> **本版主题「说真话」**：不新增功能（仅 `/status`、`/alerts` 两个"让插件自己可见"的命令），
> 全部改动是把 v1.2.0 里**说错/说假/静默失败**的地方改对。
> 来源：第二轮 14 路对抗锐评（`docs/01-reviews/critique-round2-SYNTHESIS.md`）+
> 重写执行计划（`docs/06-tasks/EXECUTION-PLAN.md`、`WAVE9A-REMAINING-PLAN.md`）。

**给升级者的一句话**：如果你之前觉得「这插件报的数和实际对不上」「配了不生效」「诊断结论像模板」，
这一版基本都是在修这些。**升级前请备份 `plugins/PerfScope/` 目录**（配置会自动补 `config-version` 并备份旧文件）。

**批次总览（16 项全部完成，实机 12 场景全绿）**

| 项 | 主题 | 结果 |
|---|---|---|
| 9A-0 | MSPT 极值捏造（三数永远相等） | 降级路径 min/max 置 -1，展示"未采集" |
| 9A-1 | Folia MSPT 假精确 | 能力位拆"方法存在/实测可用"，Folia 直标不可用 |
| 9A-2 | Folia TPS 是 region 局部值 | 命令/报告层统一标注 `(global region)` |
| 9A-3 | `/diagnose` 谎报"已检查 5 条" | 三态契约（命中/干净/无法评估+原因） |
| 9A-4 / 9A-12 | README 与代码相反 + 性能数字算错 | 全部改为如实描述；2.5% → 0.84% |
| 9A-6 | 8 条静默错误结论 | trend 漏昨天、TPS=0 Infinity、负阈值、三套百分比… |
| 9A-8 | 诊断引擎可信度 | 覆盖率判据、热点阈值量纲、建议文案全部实机校验 |
| 9A-10 | 并发与生命周期 | generation 令牌、报告执行器、防御性拷贝 |
| 9A-11 | 配置面 48 处静默回退 | 类型校验 + config-version + 未知键检测 |
| 9A-14 | 告警决策层 | 降级/恢复防抖/状态持久化/历史/摘要/服名前缀 |
| 9A-16 | webhook token 泄露 + SSRF | 异常只记类型；host 黑名单；mention 防护 |
| 9A-18 | 历史持久化工程纪律 | 容量解耦、异步写队列、缓存、schema 版本、缺口 |
| 9A-20 | 插件自身不可运维 | `isStale()`、`/status`、模块四态、Error supervisor |
| 9A-22 | 性能自证 | 脚本按样本计数、README 写明 5 条限定 |
| 9A-23 / 9A-24 | 对外材料 + i18n | LISTING-KIT 升 v1.2；2 个死键接线、两级回退如实 |
| 9B-3 | Folia 跨 region 聚合 | entities/chunks/tile_entities/hotspots 整块跳过 + 平台限制标记（Paper 零影响） |
| 9B-4 | chunk 实体数组含 null | `chunk.getEntities()` / `world.getEntities()` 跳过 null 元素（并发移除时曾 NPE → 整块采集失败） |
| 9B-5 | monitorTask 非原子 | `AtomicReference` + 双重 CAS + starting 标志（Folia 上曾出现"提示已开但 ActionBar 不刷新"） |
| 9B-6 | 启动期 TPS 误报 | Folia 启动抑制窗口（只抑制 `tps:` key，Paper 不启用，不影响 alert 场景） |
| 9B-2 | 兼容矩阵文档失真 | 顶部加状态声明：196 条是 v1.1.0 构建、只验证可达性不验证数值 |
| 9C-1 | 「Paper 1.20.4+ 仅编译期兼容」 | **已实机验证**：Paper 1.20.4 build 343 上跑通全部命令面（见下方证据） |
| 9C-2 | Purpur/Leaf/Leaves 措辞 | 已降级为"可加载验证"，明确分支私有优化未覆盖 |
| 9C-3 | 矩阵条数通胀 | 停止按条数扩张，改为按"断言了什么语义"分类（见文档） |

**9C-1 实机证据**（Paper 1.20.4 build 343，2023-12-25 STABLE，sha256 `269aa013…`）：
```
[15:21:22] Enabling PerfScope v1.2.1
[15:21:23]   MSPT精确采集: Paper API
[15:21:23]   PerfScope 启动完成喵~
```
命令面实测：`/perfscope` 输出 `MSPT (avg/min/max): 0.52 ms / 0.29ms / 4.42ms`（三数不同 → 一级路径真实生效）、
`/status` 11 模块全绿、`/diagnose` 三态输出（已评估 4/5 + 跳过原因）、`/hotspot` Top3 坐标、`/trend` 跨重启可查。

**同一套 harness 断言在 1.20.4 上也全绿**（这是比"能启动"更强的证据）：
`perfscope-boot` **6/6**、`perfscope-surface` **37/37**（完整命令面）——
即 v1.2.1 在 1.21.4 与 1.20.4 上通过的是**同一批断言**，不是"看起来像跑通了"。

**实机验证**：`release-check -WithServer` **12/12**；场景 boot 6/6、surface 37/37、config 11/11、
alert 9/9、alertv2 6/6、diagnose 9/9、trend 10/10、history 7/7、hotspot 9/9、i18n 7/7、report 10/10 全绿。
（以上场景为 **Paper 1.21.4**；9C-1 为 **Paper 1.20.4**；9B-3 的 Folia 分支与 9C-2 的分支语义仍**未实机验证**，
见 `docs/07-guides/COMPAT-MATRIX-VERIFICATION.md` 顶部状态声明。）

---

### Changed

- **9B-3 Folia 跨 region 聚合：整块跳过而不是猜**（`metrics/MetricsCollector.java`、`metrics/MetricsSnapshot.java`）：
  `collectEntities` / `collectChunks` / `collectTileEntities` / `collectHotspots` 的数据源
  （`World.getEntities()` / `getLoadedChunks()` / `getTileEntityCount()`、`Chunk.getEntities()` /
  `getTileEntities()`）在 Folia 上**按 region 归属**，在 GlobalRegion 线程上调用既可能只看到部分
  region（数字偏小、两秒之间跳变），也可能抛 `IllegalStateException`。此前这几个模块在 Folia 上
  照常全服遍历，等于拿"不确定是否完整"的数字当全服读数展示。
  - 选**方案 A（整块跳过）**而非 B（遍历 + try-catch 标"可能不完整"）：B 的 try-catch 只能兜住
    "抛异常"这一半风险，**兜不住"不抛异常但只返回一部分 region"**——那正是 Folia 最典型的形态，
    结果是一个不报错、无告警、看着正常的假数字。A 把"不知道"如实变成"不可用"，与 v1.2.1「说真话」
    主题一致；且无法实机验证 Folia（本机只有 1.21.4 分支实例、无时序可控的跨 region 复现手段），
    保守方案是唯一能自证的方案。
  - 判定**只在 `ServerCompat`**（`foliaAggregationUnsafe()` 直接转调 `ServerCompat.isFolia()`，
    本类零反射、零重复探测）。非 Folia 上该判定恒 false，四个 `refresh*Cache` 的
    `else if` 分支永不进入 → **Paper 路径零行为变化**（11 个实机场景仍跑在 Paper 1.21.4 上）。
  - 跳过时模块进 `unavailable` + `disabledModules`（规格 A 方案要求），并在**首次**跳过打一条 INFO
    （`foliaSkipLogged`，不随 reload 重置）：说明 entities/chunks/tile_entities/hotspots 已自动跳过、
    原因，以及"需要这些数据请用 AlmanacAPI 等 region 感知方案"。
  - **新增 `MetricsSnapshot.platformSkipped`**（`disabledModules` 的精确子集，契约
    `platformSkipped ⊆ disabledModules ⊆ unavailable`）：规格要求把 Folia 跳过的模块写进
    `disabledModules`，但命令层 `/status` 对 `disabledModules` 渲染的是
    「已禁用（config xxx.enabled=false）」——在 Folia 上这是**假话**（服主明明开着）。
    新集合让展示层能区分"服主关掉"与"平台做不到"，且不改 `disabledModules` 既有语义（零回归）。
    **展示层尚未接线**（`PerfScopeCommand` 属并行 agent 的文件）：接线前 `/status` 在 Folia 上
    会把这 4 个模块显示成"已禁用（config …=false）"，需改为优先判 `platformSkipped` 并渲染
    「平台限制（Folia 跨 region，已自动跳过）」。
  - `TPS / MSPT / 玩家 / JVM` **不跳过**：它们的问题分别由 9A-2（Folia 标注 `(global region)`）
    与 9A-1（MSPT 直接不可用）处理，不属于跨 region 聚合。
- **9A-1 Folia MSPT 假精确**（`compat/ServerCompat.java`、`PerfScope.java`、`command/PerfScopeCommand.java`）：
  Folia 的 `CraftServer.getTickTimes()` / `getAverageTickTime()` **方法存在但恒返回全零**
  （jar 内 `TickTimes.add` 零调用点），而旧实现的能力探测只看方法存在性 → 能力位 true →
  启动日志打"`MSPT精确采集: Paper API`"、README 写"精确"、兼容矩阵 196 条断言也全是标签子串
  （无一条断言数值）——四层一起说假话，线上 MSPT 永远只能显示"未知"。
  现改为"静态探测 + 运行期实测"两级：
  - `TICK_TIMES_METHOD_PRESENT` / `AVG_TICK_METHOD_PRESENT` 保留静态结果（只用于展示"方法在不在"）；
    功能门控位 `PAPER_MSPT_AVAILABLE` / `PAPER_AVG_TICK_AVAILABLE` 由 `final` 改为 `volatile`，
    Folia 在类初始化即置 false。
  - 新增 `probeRuntimeAvailability(Logger, boolean)` + `armRuntimeProbe(Plugin)`：插件启用后
    **首周期之后**（100 tick ≈ 5 秒）经 `SchedulerAdapter` 延迟执行一次，实测调用两个 API；
    全零/空数组/异常 → 能力位下调为 false 并记 INFO「Folia 无全局 tick 时间（getTickTimes 恒空），
    MSPT 标记为不可用」。探针幂等且**只降不升**。
  - 新增 `isMsptTrustworthy()` 作为唯一功能门控入口；`msptUnavailableReason()` 提供唯一文案出处。
  - 命令层 `showAll` / `showMSPT` 在不可信时直接渲染"不可用（原因）"，**不再走 TPS 反推**。
- **9A-2 Folia TPS 是 region 局部值**（`compat/ServerCompat.java`、`command/PerfScopeCommand.java`）：
  `Bukkit.getTPS()` 在 Folia 上只反映 **global region** 的 tick 吞吐（不含各 region 的实体/区块逻辑），
  却被当成全服 TPS 展示并驱动告警。现新增 `isTpsGlobalScope()` / `tpsScopeLabel()`，
  命令层 `showAll` 的 TPS 行、`showTPS`、「服务端:」行在 Folia 上统一追加 `(global region)` 标注，
  `showTPS` 额外输出一行口径说明。

- **9A-3 诊断引擎三态化**（`diagnose/` 全部重写）：
  旧实现用 `return null` 表示"不结论"，展示层无法区分「评估了且干净」与「因前置条件不成立从未评估」，
  于是输出"未发现已知问题（已检查 5 条）"——而 3/5 条规则根本没运行。现改为显式三态：
  `Evaluation.of()` 命中 / `Evaluation.clean()` 干净 / `Evaluation.skip(ruleId, reason)` 无法评估。
  `DiagnosticEngine.run()` 返回 `Result{hits, skipped, total}`，命令层如实输出
  "已评估 X/Y 条"，有跳过时降级为黄色"未发现问题，但结论不完整"并逐条列出跳过原因与"还缺什么"。
  契约违约（返回 null 的规则）不再被当作"干净"，而是记为未评估。
- **9A-8 诊断规则可信度**（`CoreRules` 五条规则）：
  - **覆盖率取代样本数**：`MIN_SAMPLES` 只保证条数，不保证覆盖时长（默认 60 秒间隔下 10 条
    仅覆盖 9 分钟却断言"30 分钟趋势"）。改为「条数 + 样本自身时间密度」双判据
    （中位间隔 ×（n-1）× 0.8，并有窗口 10% 的绝对下界）。实测调优：首版用"窗口×60%"过严，
    导致重启后长时间无法诊断，已改为按样本密度自适应。
  - **DIAG-ENT-HOTSPOT 阈值量纲修复**（dead rule）：原用**全服**阈值（默认 2000/2500）除以 2
    去卡**单个区块**，要求单区块 1000+ 实体才报警 → 默认配置下**永不触发**（v1.2.0 带着一条
    死规则发布）。新增独立配置键 `hotspots.chunk-warning-threshold`（默认 200，单区块口径）。
  - **DIAG-PLAYER-CAP 不再跳过 0 人样本**：原 `if (s.playerCount() > 0)` 过滤掉真实低谷，
    文案却写死"近 15 分钟低位也在 X% 以上"——凌晨 0 人 + 早上 9 人上线会得出反结论。
    现 0 人参与统计，覆盖时长按实际首尾计算。
  - **DIAG-MEM-LEAK 措辞纠偏**：原 detail 声称"GC 后水位未回落"，但代码从未做过 GC 后测量
    （`HistorySample` 无 gcCount 字段）。现只陈述观测事实（窗口内最低点仍高于起点），
    建议由 `jmap/MAT` 改为 Paper 内置的 `/spark heapdump`。
  - **DIAG-MSPT-SPIKE 建议修正**：`paper.yml` 自 Paper 1.19 起已拆分为
    `config/paper-global.yml` 与 `config/paper-world-defaults.yml`（本机 4 个真实服 `find`
    零命中），照抄会打开不存在的文件；spark 命令补全为 `/spark profiler start --only-ticks-over 200`。
  - **DIAG-TPS-PERSIST 改用 15 分钟均值**：快照自带 `tps15m`（服务端滚动均值，天然平滑）作为
    "持续"的主判据，`tps1m` 窗口均值降为辅助——原实现用 1 分钟指标 + 4 分钟样本断言"持续负载"。

### Fixed

- **9A-14 告警决策层（`alert/AlertService.java` 重写）**：投递管道没问题，决策层曾是"阈值复读机"。
  - **降级曾是黑洞**：`critical → warning` 走"占位注释：故意留空"分支，既不推送也不更新记录，
    恢复时仍用旧记录输出"严重偏高 已恢复正常"——一句从未发生过的严重事故。现降级刷新
    level/display/value 全量记录，并按 `alerts.notify-mitigation` 推一条「已缓解（问题仍在，未恢复）」。
  - **恢复一次干净采样就宣布**：现需连续 `alerts.recovery-debounce-cycles`（默认 3，与防抖同值）
    个检查周期不越界才发恢复。
  - **复燃被冷却静默 5 分钟**：现恢复时清除该 key 的冷却条目并记恢复时刻；冷却窗口内再次越界
    直接推「问题再次出现」（绕过防抖与冷却），持续时长沿用该问题最初的 `firstSeen` 连续累计。
  - **状态重启即失忆**：新增 `plugins/PerfScope/alerts.state`（`key|level|firstSeen|lastSeen|value|display|module|state`，
    原子替换写入，仅状态变化时落盘）。启动 rehydrate 的记录标记 `restored`：首个周期仍越界则
    **静默接管（不防抖、不推送）**，已恢复则走正常（防抖后的）恢复流程——崩溃重启循环不再把老问题当新事故。
    修掉自测中发现的"`start()` 首行 `stop()` 会用空状态覆盖磁盘旧记录"这一回写竞态（`stateLoaded` 前禁止写盘）。
  - **没有告警历史**：新增 `plugins/PerfScope/alerts.jsonl`（追加写，2 MB 自动轮转一次），
    事件 `open/upgrade/downgrade/resolve/refire/restore/suspend/resolved_by_disable`，
    每行 `ts/type/key/level/value/display`；新增 `public List<AlertEvent> recent(int limit)`（内存镜像 + 首访从文件播种）。
  - **模块禁用 → 告警永久活跃**：所属模块连续不可用超过 `alerts.unknown-after-minutes`（默认 5）后，
    该 key 转入 unknown（`getSuspendedCount()` 单列，不再算活跃）；模块恢复后仍在则静默接管，
    已消失则**静默解决**（只记 `resolved_by_disable`，不发"已恢复正常"）。
  - **告警风暴**：`alerts.digest-mode=true` 时同一检查周期内的新增告警合并为一条（按指标分组，
    最多列 5 条 + "等 N 条"）；升级/复燃仍即时单独发。
  - **文案零可行动性 / 无服务器标识**：推送前缀统一为 `[告警][级别][服名] 消息`，
    详情追加 `key + 值 + 阈值 + 持续时长 + 下一步`（runbook）。服名取 `server.properties` 的 motd 首行
    （去 § 色码、截断 16 字），取不到退化为端口号。**未改 `MetricsCollector` 的告警文案**。
  - **投递成败分开统计**：`getPushOkTodayCount()` / `getPushFailTodayCount()` / `getWebhookQueuedTodayCount()`。
    `WebhookNotifier.send()` 无返回值（fire-and-forget 异步），故 webhook 只报"已排队 N 条"，不谎称送达。
- **9A-14 每日摘要（`alert/DailySummaryService.java`）**：
  - **时区显式化**：新增键 `alerts.daily-summary.timezone`（IANA 名，空 = 系统时区），
    日期/时刻/格式化全部走该时区；与系统时区不同时启动日志点名。构造与 `start()` 各解析一次，
    每分钟节拍零 IO（reload 生效）。
  - **覆盖不足不再冒充每日**：样本跨度 < 60 分钟或不足开机时长 50% 时，标题改为
    「自 HH:mm 重启以来（数据不足）」，并附"覆盖 N 分钟，不足 X 的 50%——重启前数据已丢失"。
  - **补 p95 与超阈分钟数**：p95 用线性插值（仅 `tps1m > 0`），超阈分钟数按相邻样本真实时间差加权
    （跳过 >10 分钟的异常间隔，末样本按中位间隔补齐）——不依赖 HistoryStore 新 API，零新依赖。
  - **推送成败分开报**：「推送 N 条（成功 M / 失败 K）」，webhook 单列"已排队 W 条"；unknown 数单列。
  - 控制台输出补 `=== 每日摘要 ===` 首行锚点（此前只有 `[PerfScope] 每日摘要` 行）。
- **9A-14 `config.yml` alerts 段**：`debounce-cycles` 注释修正为"单位是检查周期
  （≈ `general.update_interval`，默认 1 秒），3 周期 ≈ 3 秒，不是 3 分钟"；新增
  `recovery-debounce-cycles`(3)、`digest-mode`(false)、`notify-mitigation`(true)、
  `unknown-after-minutes`(5)、`daily-summary.timezone`("")。
- **9A-6 静默错误结论（8 条）**：
  - **`/trend` 默认漏掉昨天**：`HistoryFileStore` 的 `minusDays(maxDays - 1)` 使 `days=1` 时
    cutoff=今天，昨天 CSV 整文件被跳过——凌晨问"昨晚卡不卡"只能看到今天数据，输出却写"近 1 天"。
    改为 `minusDays(maxDays)`（多读一天由 sinceMs 精确裁剪）。
  - **TPS=0 输出 `Infinityms`**：`PerfScopeCommand` 的 `if (tps1m < 20) 1000.0/tps1m` 缺 `>0` 守卫，
    而 TPS=0（主线程完全阻塞）是合法读数——恰在最需要读数的事故现场打印无穷大。
    现单独渲染"服务器无响应（TPS=0，主线程可能已完全阻塞）"。
  - **负阈值/超大阈值不被钳制**：`sanitizeIntPair` 只查 `w <= c`，`-5` 与 `999999` 均放行 →
    前者使告警恒真且恢复不可达（永久告警），后者使告警永久静默。现要求 `0 ≤ 阈值 ≤ 10,000,000`，
    `sanitizePair`（double）同步补下界；回退日志按"越界/倒置/非有限"三类分开措辞（原来一律说"倒置"）。
  - **玩家占比三种算法并存**：命令层 int 截断、采集层 double 截断、诊断规则 `Math.round` →
    179/200 时三方分别显示 89 / 89 / 90，可能直接触发 DIAG-PLAYER-CAP 而界面仍是绿色。
    新增 `MetricsSnapshot.playerPercent()` / `playerPercentOf()` 作为唯一入口，三处全部改为调用。
  - **每日摘要每天重启 N 次收 N 份**：`lastSentDate` 只在进程内存。现落盘到
    `plugins/PerfScope/daily-summary.state`，发送成功后立即写入，重启后同日不再重发
    （实机验证：场景跑完后文件内容为当日日期）。
  - **告警重复消费同一快照**：`AlertService.onSnapshot` 无快照身份判重，检查周期比重启后采集周期更密时，
    同一个 snapshot 被反复喂入，"连续 N 个周期观测"退化为"同一观测看了 N 次"。新增
    `lastConsumedSnapshotTs` 按快照时间戳判重。
  - **`catch (Exception ignored) {}` 静默吞异常**：`/perfscope entities <world>` 的世界扫描失败时
    用户在游戏内只看到"读取失败"、控制台无任何根因。现记 FINE 级（含堆栈）+ debug 模式下 WARNING。
  - **MEM-LEAK 的 `0` 哨兵**（已在 9A-8 同批修复）：`if (h > 0)` 跳过 0 而非判无效，
    窗口后半段全 0 时会被判成"水位未回落"。现 `h <= 0` 直接 skip。

- **9A-0 MSPT 极值捏造**（`MetricsCollector` 二级/三级降级路径）：原实现
  `snap.msptMin = snap.msptAvg; snap.msptMax = snap.msptAvg;` 把平均值复制给 min/max，
  报告层原样输出"最小: X | 最大: X"——三个数永远相等，是纯捏造的极值区间。
  现改为：降级路径 min/max 置 `-1` 哨兵，展示层渲染"未采集"，HTML/TXT/JSON 三格式同步；
  JSON `mspt` 段新增 `source` 字段（`tick_times` / `avg_tick` / `tps_fallback`）。
- **9A-0 "(估算)"标签口径**（`PerfScopeCommand`）：原判定依据是静态能力位
  （`PAPER_MSPT_AVAILABLE || PAPER_AVG_TICK_AVAILABLE`），只回答"这台服务端理论上支持什么"；
  实际 invoke 失败降级到 TPS 反推时，会把估算值当精确值展示且不打标签。
  现改为按**本快照实际采集路径**（`MetricsSnapshot.msptSource`）判定。
  连带修正：`/diagnose` 的 DIAG-MSPT-SPIKE 规则此前因 min==max==avg 而**永不触发**，现在可正常判定。

### Changed

- **9A-12 README 三处自我打脸**（中英双语共 6 处）：
  1. 「不做历史持久化……重启即清空；不落盘」与同页 `persist: true`、`/trend` 跨重启宣传直接矛盾
     → 改为如实描述"内存 ~24h 环形缓冲 + 按天 CSV 落盘、默认保留 7 天、跨重启可查"，
     并把真正的边界收窄为"不做周级/月级趋势、不做多服聚合、不替代外部监控"。
  2. 「采集间隔均可配」—— 实为三个内置常量（实体/区块每 5 周期、方块实体每 10 周期），
     只有热点区块可配 → 文案与事实对齐。
  3. 「显示与告警同一口径」—— 实际显示用闭区间、部分告警用严格大于（`ThresholdColors` 类注释自认），
     → 改为"同配置、同判定入口"，并明说"恰好等于阈值时颜色先变、告警随后"是有意的早预警语义。
- **9A-12 性能自证数字纠正**：原写"约 2.5% 的采样含 `cn.perfscope` 栈帧"，
  实为把**帧行数 110** 当**样本数**（真实 37/4394 = **0.84%**，单样本内栈帧横跨 3 行）。
  同时补上原表隐去的限定：该测量基于 2026-10-18 构建，**早于 v1.2 的热点扫描与 CSV 持久化提交**；
  分母 96.75% 来自 8 个空闲 I/O 线程的原生阻塞；主线程仅 19 个样本（n=1，95% CI 约 1%–25%）。
  README 明确写出"采样份额 ≠ CPU 占用 ≠ TPS 影响"。
- 「主线程零阻塞」→ "无长时阻塞，但每 60 秒有一次历史 CSV 追加写"（如实披露）。
- **9A-10 并发与生命周期**（`MetricsCollector` / `ReportGenerator` / `PerfScope`）：
  - **reload 把 cancel 当 join 用**：`TaskHandle.cancel()` 只阻止下一次执行，不中断也不 join。
    旧任务跑完仍回写 `tickCounter` / 四个 `cacheReady` / `jvmCache` 等**非 volatile 裸字段**，
    reload 后旧异步任务把**旧配置**算出的 JVM 快照写进新一代码。现引入
    `volatile long generation`：`start()` 捕获当前代进任务闭包，`stop()` 与 `start()` 各自增一次；
    任务体在**发布任何状态之前**（`jvmCache` / 四个 `refreshXxxCache` 的缓存字段 / `history.addLast` /
    `onCycleSuccess` / `onCycleFailure`）比对代际，不符即整轮丢弃。
  - **一份快照混用两代配置**：`collectSync` 持有 `cfg` 局部量，却在 `collectEntities` 里经
    `configManager.getWorldSettings()` 回读**当前**配置；`applyJvmCache` 也二次 `configManager.get()`。
    reload 时前半段用旧阈值、后半段用新阈值。现全部改为只读传入的 `cfg`，
    新增私有 `worldSettingsFrom(cfg, worldName)`（不改 `ConfigManager`）。
  - **快照"不可变"只是注释**：节流缓存把**同一个 Map 引用**塞进最多 300 份历史快照，一次误改全史污染。
    现缓存刷新时存不可变副本，`applyXxxCache` 每次再拷一份独立 `LinkedHashMap`，快照入 `history` 前
    统一 `freezeCollections`（Map 保留插入序、List 用 `List.copyOf`、Set 用 unmodifiable）。
    `MetricsSnapshot` 类本身未改。
  - **自动报告与 `/ps report` 写同一路径**：`exists()` 与 `Files.write(TRUNCATE)` 之间无原子性，
    整分钟同时触发会互相截断。现 `ReportGenerator` 内置单线程守护执行器 `perfscope-report`
    串行化全部生成+写盘，改用 `CREATE_NEW`（O_EXCL）原子创建，`FileAlreadyExistsException`
    换名重试最多 10 次；新增 `shutdown()` 供 `onDisable()` 调用。
  - **onDisable 顺序**改为「先停产生者（采集 → 自动报告）→ 再停消费者（日报 → 告警）→ 最后关输出
    （报告执行器）」；看门狗提前到采集停止之前摘除（否则关服日志会报假过期）。

### Fixed

- **9A-20 可运维性（"对内全瞎"）—— 采集死亡后继续输出冻结快照**（`MetricsCollector`）：
  `getLatest()` 永远返回 `history.peekLast()`，采集任务被静默取消后 `/perfscope` 仍渲染一屏"正常"数据，
  唯一线索是需要人工比对的 `HH:mm:ss`。现新增采集健康三件套：
  - `lastCollectAtMs`（每次 `collectSync` 成功末尾更新）+ `lastJvmCollectAtMs`（异步 JVM 节拍独立计时）；
  - `isStale()`：`now - lastCollectAtMs > 3 × update_interval`（默认 20 tick → 3 秒；从未成功过则以
    `start()` 时刻为基准、宽限 10 秒，与 `noData` 文案同一口径）；
  - `staleForMs()` / `staleThresholdMs()` / `consecutiveCollectFailures()` / `isCollectorDead()` /
    `lastCollectError()` / `firstFailureAtMs()` 访问器；`isJvmStale()` 单独覆盖异步节拍
    （主线程正常 ≠ 内存/CPU/GC 是新的）。
  状态放在 Collector（`MetricsSnapshot` 是 per-sample 的，不加字段）。
- **9A-20 Error 逃逸让周期任务被静默取消**（`MetricsCollector` / `PerfScope`）：
  `collectSync` 整个方法体无 try → 任何未捕获异常都会让 Bukkit **cancel 该 repeating task**，采集永久死亡且无 supervisor。
  现任务体抽出为 `collectSyncOnce()`，外层 `try { ... } catch (Throwable t)`：`onCycleFailure` 累加
  `consecutiveCollectFailures`、置 `collectorDead`、限频记 SEVERE；连续 10 次打 SEVERE 并提示
  "`/perfscope reload` 或重启服务器"；成功一次即清零并补 INFO「采集已恢复」。
  `OutOfMemoryError` / `ThreadDeath` **先发布死亡标志再原样抛回**（不吞 JVM 级故障）。
  `collectJvmAsync` 同款任务边界（异步 repeating task 此前同样会被静默取消）。
  **新增 supervisor**：`PerfScope` 每 10 秒（200 tick）异步看门狗，只在 `isStale()` / `isJvmStale()`
  的**边沿**各记一条日志（不刷屏），覆盖"任务根本没跑起来"（Folia 调度失败返回 noop 句柄等）的盲区。
- **9A-20 `safeCollect` 每周期一条 WARNING 无限频**（`MetricsCollector`）：
  默认 1 秒一周期 = 单模块持续失败 **86400 条/天**，刷屏到看不见其他插件报错。现与
  `WebhookNotifier`（60s）/ `HistoryFileStore`（300s）同款范式：**首次立即记，之后每 5 分钟一条**，
  消息带「已连续失败 N 次」「首次失败 HH:mm」；模块恢复时补一条 INFO「模块 X 已恢复」
  （恢复日志同样 5 分钟限频，抑制失败/恢复交替的抖动）。
- **9A-20 "被配置禁用"与"采集失败"不可区分**（`MetricsSnapshot` / `MetricsCollector`）：
  两者都塞进 `unavailable`，40 处判断统一渲染"不可用"——服主关掉 memory 后以为插件坏了。
  新增 `MetricsSnapshot.disabledModules`（契约 `disabledModules ⊆ unavailable`，**`unavailable` 语义不变**，
  既有 40 处判断零改动）；采集侧在「配置禁用」分支填 `disabledModules`，在「采集失败」分支只填 `unavailable`；
  重模块（entities/chunks/tile_entities/hotspots）经 `*CacheDisabled` 标记在复用周期一并回放；
  JVM 缓存里的禁用标记随 `applyJvmCache` 透传；`enabled=false` 的 memory/cpu 不再被误标"预热中"。
- **9A-20 无 status 入口**（`PerfScopeCommand`）：新增 `/perfscope status`（help 一行，Tab 补全同步），
  输出锚点 `=== 插件状态 ===`。内容：采集存活（`isStale` / 最后采集时刻 / 连续失败 / 周期健康 / 最近错误）、
  JVM 异步节拍新鲜度、11 个模块的四态（正常 / 已禁用（config xxx.enabled=false）/ 采集失败（连续 N 次）/ 预热中）、
  历史（`getHistorySize()` + `HistoryStore.size()` + 持久化可用性与目录）、当前告警（直接读
  `activeWarnings`/`activeCriticals`，**不依赖 AlertService**）、调度器积压（按需现算）。
  快照已过期时明确提示"其它子命令显示的仍是旧数据"。
- **9A-20 采集任务自身健康**：
  - `MetricsCollector.getHistorySize()` 此前零调用 → 已在 `/perfscope status` 接入（连带 `HistoryStore.size()`）；
  - `MetricsSnapshot.pendingTasks` 全仓零读取且 `Bukkit.getScheduler().getPendingTasks()` 每周期构造完整列表 →
    **字段与赋值一并删除**（诊断需要时在 status 里按需现算，不进采集热路径）；
  - 顺手修：`MODULE_SERVER` 被主线程与 JVM 异步线程双路采集，共用健康槽位会互相清零失败计数 →
    异步侧改用独立槽位 `server(jvm)`（`failingModules()` 合并回 `server`，status 展示不失真）；
  - 顺手修：异步 JVM 节拍被静默取消时主线程仍每秒产快照，`isStale()` 看不出来 → 新增
    `isJvmStale()` / `jvmSnapshotTimestamp()` 并在 status 与看门狗中单独判定。

### Fixed（9A-23 / 9A-24：对外材料 + i18n 四处真问题）

- **9A-24-1 两个死键接线**（`messages.yml` 的 `report-generated` / `reload-failed-old-config`
  此前全仓零调用，改了也没用）：
  - `PerfScopeCommand.handleReport` 的 `Component.text("报告已生成: ", GREEN)` → `Messages.get("report-generated")`；
  - `PerfScope.reload` 的 `getLogger().warning("PerfScope 配置重载未完成（已按旧配置重启采集/告警）")`
    → `Messages.get("reload-failed-old-config")`。两处默认值与改造前硬编码**逐字一致**（harness 断言不变）。
- **9A-24-2 控制台 reload 日志硬编码**（`PerfScope.reload` 的 `"PerfScope 配置已重载喵~"`）：
  新增键 `reloaded-console`（默认值逐字等于原硬编码，保留 `PerfScope ` 前缀），控制台改走 `Messages.get`。
  选择独立键而非复用聊天侧 `reloaded`：专业部署常只想中性化聊天回复、不想改控制台日志；且复用会让
  "聊天侧改了、控制台没改"的旧分裂反转为"改一处两处一起变"，两种行为都不是管理员显式要求。
- **9A-24-3 plugin.yml 的 permission-message 第三份副本**（方案 A，已实机验证）：
  新增 `Messages#applyCommandPermissionMessage(plugin)`，在 `load()` 末尾（启动 + 每次 reload）
  用 `PluginCommand#permissionMessage(Component)` 把 `no-permission` 的实际值回写为指令权限拒绝文案。
  验证：临时 harness 场景（已删除）在真实 Paper 1.21.4 上证明——messages.yml 写
  `no-permission: "9A24-BASELINE-DENY"` 后，非 OP 玩家服务端侧分发 `/perfscope` 收到的拒绝文案是
  该标记（而非 plugin.yml 的 `你没有权限使用此指令喵`）；reload 改成第二个标记后再分发，聊天里
  跟着变成新标记（7/7 checks passed）。plugin.yml 的值保留为"消息层加载前首帧兜底"并加注释说明。
- **9A-24-4 "三级回退"实为两级**（`Messages.load`）：删除对最终 `get()` 结果零影响的
  `yaml.setDefaults(jar 资源)` 段（`overrides` 只从用户文件 `getKeys(true)` 填充），类注释与
  `get()` 注释统一改为如实的两级"用户覆盖 → 内置 DEFAULTS"，并注明"若确需资源层，必须在
  `get()` 里加真正的回退分支"。
- **9A-24-5 类注释里的错误计数**（`Messages`）：删掉"12 个键，含全部 9 处喵"这类会漂移的数字，
  改为指向 `keys()` / `messages.yml` 的权威清单。
- **9A-24-6 清理游离 plugin.yml 副本**：删除未跟踪的 `./plugin.yml` 与
  `src/main/java/cn/perfscope/plugin.yml`（version 硬编码 `1.2.0`，与 `${project.version}` 过滤会漂移）。

- **9A-23 上架材料包从 v1.1 升到 v1.2 口径**（`docs/07-guides/PERFSCOPE-LISTING-KIT.md`）：
  版本占位全部改 v1.2.0；三处"不做历史持久化 / 回答不了昨晚卡不卡"改为如实描述
  （内存 24h 环 + 按天 CSV + 默认保留 7 天 + `/trend` 跨重启可查）；子命令数 18 → **23**
  （按 `PerfScopeCommand` switch 分支去重计数，Tab 补全同步 23 项）；新增
  `/diagnose`（5 规则 + 三态评估）、`/hotspot`（Top3 区块坐标）、`/trend`、`/status`、`/alerts`
  五项功能段落；新增 §0.1 `diag` vs `diagnose` 别名说明（上架文案只用 `diagnose`）；
  §1.4 重写诚实边界五条，新增"Folia 上 MSPT 不可用"与"Paper 1.20.4+ 仅编译期兼容"。

---

## [1.2.0] — 2026-10-19

> **发布概要**（面向用户剪裁；逐波次开发流水见下方各段）：
>
> **本版主题「找得到」**：从"看得见的数字"升级为"定位到根因与坐标 + 记得住历史 + 触达离线的人"。
> - **智能诊断**：`/perfscope diagnose` 规则引擎（首批 5 条高置信规则）给出结论 + 参数级建议；
>   每条都带"证据不足不结论"路径——宁可不结论，不给错结论。
> - **热点定位**：`/perfscope hotspot` 全服实体密度 Top3 区块 + 可点击坐标（直接喂 `/tp`）。
> - **跨重启历史**：按天 CSV 持久化 + `/perfscope trend [天数]`——"昨晚卡不卡 / 上周以来"有数据可查。
> - **文案可配置**：`messages.yml` 覆盖层（"喵"变可配置彩蛋，专业部署一键换中性文案）。
> - **兼容矩阵实机化**：Folia / Purpur / Leaf / Leaves 四分支真实服务端验证（各 49 断言）。
> - **验证**：14 个 testharness 场景、**全量回归全绿**（详见 `docs/07-guides/PERFSCOPE-TEST-REPORT.md`）。

### Added

- `/perfscope diagnose`：诊断引擎（DIAG-MEM-LEAK / DIAG-ENT-HOTSPOT / DIAG-TPS-PERSIST /
  DIAG-PLAYER-CAP / DIAG-MSPT-SPIKE），输出含 `[ID]`、依据与 `建议:` 参数级行动；末行能力边界声明。
- `/perfscope hotspot`（别名 `hs`）：实体密度 Top3（含玩家）+ 坐标可点击复制 + 主导类型占比。
- `/perfscope trend [天数]`：跨重启趋势摘要（范围/来源/平均与最低 TPS/峰值内存/覆盖时段）。
- `HistoryFileStore`：按天 CSV 持久化（重启不清零；损坏行容错；过期自动清理；零新依赖）。
- `Messages` + `messages.yml`：消息层两级回退（内置默认 / 用户覆盖；原文误记为"三级"，
  9A-24 已更正），`喵` 类文案全部可配置。
- 配置：`history.persist` / `history.retention-days` / `hotspots.enabled` / `hotspots.sample-every-cycles`。

### Changed

- **`diagnose` 别名拆分**：`diagnose` 从 `all` 别名改为诊断引擎入口；`diag` 保持全量诊断（兼容保留）。

### Notes

- 四分支（Folia/Purpur/Leaf/Leaves）与 LuckPerms 权限矩阵实机验证报告：
  `docs/07-guides/COMPAT-MATRIX-VERIFICATION.md`。
- 本版为 v1.2 的"找得到"批；剩余 backlog（37 条规则全文校准 / 诊断包导出 / 完整 P99 压测 /
  其余文案增量迁移）等真实用户信号后推进（`docs/06-tasks/POST-CRITIQUE-FIX-PLAN.md`）。

---

## [1.2.0] — Wave 8D「消息层（i18n 基础）」- 2026-10-19

> **主题：文案可配置**——"喵"不是删掉而是**做成可配置**（锐评 D 的语气裁决：萌系服主与专业受众
> 都是真实受众，配置化是唯一的桥）。
> **状态：全部落地；编译 BUILD SUCCESS；实机验证见下。**

### Added
- **`cn.perfscope.util.Messages`**（新类）：消息层单一出口——
  内置默认（与迁移前文案**逐字一致**，默认行为零变更）→ 数据目录 `messages.yml`（管理员覆盖）
  两级回退（9A-24 更正：原文写"三级"，中间的 jar 资源层实为空转，已删除）；空值/缺失键回退默认、
  文件损坏回退全默认（永不抛异常）；reload 时随 `/perfscope reload` 刷新（缺失/损坏自动回退）。
- **`src/main/resources/messages.yml`**（新资源）：首次运行生成到数据目录，
  覆盖全部用户可见的"喵"类文案（键数以 `Messages.keys()` 为准，不在文档里维护会漂移的数字），
  注释含"专业部署可去除口癖"用法示例。
- 迁移首批 12 个最高可见度键：权限拒绝×3 / 玩家限定 / monitor 开关×3 / 重载成功失败 /
  报告生成失败 / 启动关闭横幅×2——命令层 9 处 + PerfScope 2 处调用点全部改走 `Messages.get`。

### Notes
- **迁移策略（分层，非一次性）**：本波只迁"最高可见度"一批（权限/开关/横幅）；
  其余约 160 处命令输出文案留待按需增量迁移——底层基础设施已就绪，迁移单键成本 = 改一行调用 + 加一条默认。
- **实机验证**（`perfscope-i18n.json`，7 checks）：默认值逐字不变（`配置已重载` 基线断言）
  → 覆盖 `reloaded` 键为自定义文本 + reload → 回复变成自定义文本且旧默认消失（替换而非追加）
  → 空值回退（空覆盖 → 回默认）→ 缺键回退（只有无关键的 messages.yml → 全回默认）→ 还原。
- 场景细节：messages.yml 用确定性 `write` 预置（首轮不存在/残留状态均安全）。

---

## [1.2.0] — Wave 8B「历史文件持久化 + 趋势」- 2026-10-19

> **主题：重启不清零**——"昨晚卡不卡 / 上周以来的趋势"有数据可查。
> 方案取舍（锐评 B 论点 8）：**不用 SQLite**（首个重量级运行时依赖 = v1.0.0 shade 事故同类土壤），
> 改用**按天 CSV 文件**（纯 JDK NIO）：本场景（1 样本/分钟、7 天保留、单机）获得全部价值、零依赖风险。
> **状态：全部落地；编译 BUILD SUCCESS；实机验证见下。**

### Added
- **`cn.perfscope.history.HistoryFileStore`**（新类）：按天 CSV 追加写 +
  跨重启加载 + 过期清理。设计要点：
  - 文件布局 `plugins/PerfScope/history/history-yyyy-MM-dd.csv`（7 列 = HistorySample 字段序，RAW 值）；
  - **写入走锁外**（不占环形缓冲的对象锁，IO 不阻塞内存读方）；失败日志 5 分钟限频 + 静默降级
    （持久化失败绝不影响内存环与采集主流程）；
  - **读取容错**：损坏行/写一半的最后一行自动跳过；跨天自动滚动；清理只删本插件命名的文件
    （`history-*.csv`，不碰用户文件——A6 教训）；
  - 零新依赖（契约延续）。
- **`/perfscope trend [天数]`**（新命令）：合并文件持久化 + 内存环的样本，输出跨重启可查的
  `=== 趋势摘要 ===`（范围与样本数/来源、平均 TPS、最低 TPS 与时刻、峰值内存与时刻、覆盖时段）；
  天数参数 1..保留天数（非法输入容错回退 1 天）；空态"暂无趋势数据"。help/Tab 同步。
- 配置：`history.persist`（默认 true；false = 仅内存环）、`history.retention-days`
  （默认 7，≤0 钳制 + warning）；启动/reload 时清理超期文件。

### Changed
- `HistoryStore`：新增 `setFileStore` 装配口；`record` 在入环后（锁外）追加持久化。
- README/README-EN「不做历史持久化」边界条目更新为准确描述（按天文件 + 保留期 + 与 /ps trend 的分工）。

### Notes
- **与 /perfscope history 的分工**：history 读内存环（近 24h、重启清零、零 IO）；
  trend 读文件持久化（跨重启、多日、按需加载）。两者共用同一采样节流与污染防护。
- **实机验证**（`perfscope-trend.json`，10 checks）：`sample-interval-seconds=5` 快速积累
  → 断言 `文件持久化` 来源行、最低 TPS/峰值内存行、样本计数、天数参数（近 7 天）、
  CSV 文件落盘（file check）、help 收录、reload 存活。运行记录 `perfscope-20261019-011224-zptu`（10/10）。
- **跨重启铁证（专项探针）**：进程 A 采样 225 条 → 停服 → 进程 B 重启后 `/perfscope trend`
  显示 **364 样本、覆盖 01:27～02:02**——覆盖起点 **01:27 是进程 A 的采样时刻**，新进程读到了
  旧进程写的数据。"重启不清零"从设计承诺变为实测事实。
- 场景记录：测试服配置早于 8B（无 persist 键）——沿用"备份→删除→重生成→恢复"净零手法。

---

## [1.2.0] — Wave 8A「智能诊断引擎（首批 5 条规则）」- 2026-10-19

> **主题：从"看得见"到"找得到"** —— `/perfscope diagnose` 运行规则引擎给出**结论 + 参数级建议**
> （V1.2 规则表 37 条按锐评 B 论点 7 缩编为首批 5 条高置信规则；"规则即对象注册表"实现）。
> **状态：全部落地；编译 BUILD SUCCESS；实机 9/9 全绿。**

### Added
- 新包 `cn.perfscope.diagnose`：
  - `DiagnosticRule`（接口：id/evaluate，规则即对象；ID 一经发布不得复用）、
    `Diagnosis`（结论 record：规则ID/标题/依据/参数级建议/级别）、
    `DiagnosticEngine`（注册表执行器：逐条评估 + 单条异常隔离 + critical 优先排序）。
  - `CoreRules` 首批 5 条高置信规则（全部带"证据不足不结论"路径）：
    **DIAG-MEM-LEAK**（堆占用持续抬升 + 后半窗谷值不回落 → 疑似泄漏，建议 jmap+MAT 留证）、
    **DIAG-ENT-HOTSPOT**（Top1 热点区块超世界阈值一半且 ≥200 → 给出 /tp 坐标与定点清理命令）、
    **DIAG-TPS-PERSIST**（10 分钟窗均值 < 阈值 → 持续低位而非抖动，给排查顺序）、
    **DIAG-PLAYER-CAP**（当前超阈值且窗口低位也在阈值-10 以上 → 持续接近上限而非活动峰值）、
    **DIAG-MSPT-SPIKE**（max > avg×3 且 > 100ms → 单 tick 冻结，建议 autosave 调优 + spark 定点抓取）。
- `/perfscope diagnose` 子命令（智能诊断输出：`=== 智能诊断 ===` 头、`[ID] 标题` 行、
  `建议: ` 参数级行动行、无命中时 `未发现已知问题`）+ help/Tab 同步。
- **能力边界声明**（每次输出末行）：`本引擎覆盖 N 条高置信规则，方法级深挖请用 /spark profiler`
  ——诚实划界，引擎永不假装自己是 profiler。

### Changed
- **`diagnose` 别名拆分**（V1.2 规格 §6.1 既定变更）：`diagnose` 从 `all` 的别名改为诊断引擎入口；
  **`diag` 保持指向全量诊断**（老用户肌肉记忆 + harness 断言依赖，回归已验；help 文案同步）。

### Notes
- **为什么缩编为 5 条**（锐评 B 论点 7）：其余 32 条要么依赖未采集数据（磁盘/网络字节/插件耗时），
  要么与既有告警/报告高度重叠；错误归因的信任成本不可修复——宁可 5 条高置信，"宁可不结论，不给错结论"
  （全部规则带证据不足路径：样本覆盖不足/模块不可用/估算模式一律返回 null）。
- **实机验证**（`perfscope-diagnose.json`，9 checks）：可构造事实法——`tps.warning_threshold=20.5`
  （TPS 恒 ≤20，条件必真）+ `history.sample-interval-seconds=5`（约 25 秒攒够 5 样本）
  → 断言引擎命中 `DIAG-TPS-PERSIST` 且输出含 `建议:` 与依据数值；含边界声明、diag 兼容、reload 存活。
  运行记录 `perfscope-20261019-000558-bxsf`（9/9）。
- **List.of 注册表**：规则顺序 = 评估顺序；互斥机制暂不引入（首批语义正交），加规则时按需。

---

## [1.2.0] — Wave 8C「热点区块定位」- 2026-10-18

> **主题：把"哪类实体多"下钻到"去哪个坐标看看"**（V1.2 规则表 D-ENT-04 / §5 + `WAVE8C-DESIGN.md` 契约）。
> 锐评 C 证据：LagTrace（0★）做过同方向没做成——执行质量即差异化；Wave 8 中不依赖真实信号的首选项。
> **状态：全部落地；编译 BUILD SUCCESS；实机 9/9 全绿（含回归核对）。**

### Added
- **`cn.perfscope.metrics.ChunkHotspot`**（新 record）：区块坐标/实体数/主导类型与数量；
  `dominantPercent()` / `blockX()`（chunkX*16+8）/ `blockZ()` 换算方法（V1.2 §5.2 口径）。
- **`/perfscope hotspot`（别名 `hs`）**：全服实体密度 Top3（含玩家）——
  每行 `世界 [区块x,z]（方块 X,~,Z）实体 N 个，主导 TYPE P%`；玩家视角坐标可点击复制
  （copyToClipboard "X Z" 形态可直接喂 /tp；控制台纯文本）；空态"暂无热点数据"；
  help 面板 + Tab 补全同步。
- 采集侧：`collectHotspotsThrottled`（entities 缓存三件套同范式）——
  每 `hotspots.sample-every-cycles` 个采集周期全遍历一次（默认 30 ≈ 30 秒；主线程保护），
  其余周期复用缓存；Top5 排序确定性（实体数降序 + 世界名/坐标排序）；众数平票取字典序最小；
  走 `safeCollect`（新 `MODULE_HOTSPOTS` 隔离）。
- 配置：`hotspots.enabled`（默认 true）、`hotspots.sample-every-cycles`（默认 30；≤0 钳制 + warning）。
- 快照字段：`MetricsSnapshot.topEntityChunks`（Top5；空列表 = 未采样/不可用）。

### Notes
- **本波不做**：`chunk_entity_warning/critical` 阈值与告警（规格 D-ENT-04 的告警部分留给 Wave 8A 规则引擎）；
  报告三格式输出（留后续，避免与渲染复杂度叠加）。
- **实机验证**（`perfscope-hotspot.json`，9 checks）：可构造事实法——`forceload add 0 0` 保区块加载
  + 15 只 NoAI 猪入 (0,0) → 断言 Top3 列出 `[0,0]` 且 `主导 PIG`；含头部锚点/方块坐标/
  时间戳含玩家注记/help 收录/reload 存活。**首轮暴露的场景设计缺陷**：summon 到未加载区块的
  实体会随区块卸载消失（forceload 修正后全绿）——已固化进场景描述。
- Wave 8C 实施说明：原派 agent 因基础设施 502 中断且未落盘，**由编排者按契约亲手实现**
  （ChunkHotspot + 采集三件套 + 命令层，单次编译通过）。
- 回归核对：surface 场景（36 checks）复跑通过（W8C 未破坏既有命令面）。

---

## [1.1.0] — 2026-10-17

> **发布概要**（面向用户剪裁；逐波次开发流水见下方各段）：
>
> **本版是 v1.1 + v1.2 核心重构成果的合体发布**。要点：
> - **致命修复**：v1.0.0 因 shade/relocate 破坏 Adventure 签名，**Paper 上任何命令都会 `NoSuchMethodError`**——已根治（发布门禁三重断言防止回归）。
> - **从"看得见"到"找得到、记得住、喊得醒"**：MSPT 精确值、阈值配色读配置、`/perfscope history` 24 小时历史摘要、
>   告警防抖与升级单通知、Discord / QQ OneBot webhook 推送、每日 3 行摘要（含补发语义）。
> - **诚实化**：下线红石/网络假数据；不可用/预热/未知三态区分；「边界与不做什么」写入 README。
> - **分发准备**：AGPL-3.0、CLA、bStats、发布冒烟门禁与 CI。
> - **验证**：9 个 testharness 场景、**91 条真机断言全绿**（详见 `docs/07-guides/PERFSCOPE-TEST-REPORT.md`）。

### Added

- `/perfscope history`（别名 `hist`）：近 24 小时概况（覆盖时长 / 最低 TPS 与时刻 / 峰值实体 / 峰值内存）。
- Discord / QQ OneBot webhook 推送（告警 / 恢复 / 每日摘要；全异步、失败仅记日志）。
- 每日摘要：固定时刻 3 行概况，服务端停机错过也会在上线后补发一次。
- 告警防抖（`alerts.debounce-cycles`，默认连续 3 周期）；级别升级只推一条"（级别升级）"通知。
- 模块"预热中"状态（区别于真故障的"不可用"）；报告路径可点击复制。
- CLA、贡献指南、PR 模板、发布检查清单、release-check 门禁脚本与 GitHub Actions 构建。
- bStats 匿名统计接入（默认启用；当前 pluginId 占位 0，**不发送任何数据**）。

### Changed

- **行为变更·配色**：显示色阶改为读取 `config.yml` 告警阈值（原为内置常量），改配置 reload 后颜色立即跟随；
  区块红界 1500→2500、CPU 不再有红档、报告内存界 70→80、MSPT 黄界 40→45。
- **行为变更·默认阈值**：玩家数告警 80/95→**90/98**；堆内存告警 80/90→**85/95**（抑制"狼来了"式噪音）。
- 调度层去除反射（编译期直连 Folia 调度 API），技术债务 `source/target` → `release`。
- 双语 README 全面校准：monitor 定位为"诊断探针"、新增「边界与不做什么」、许可证表述修正。

### Fixed

- **P0-3**：Adventure relocate 导致所有命令 `NoSuchMethodError`（v1.0.0 起存在）。
- **MC-1**：MSPT 精确采集反射链错误（探错类与方法名），Paper 上恒显示"(估算)"。
- **A2**：monitor 假值（禁用模块显示绿色 0）、断线 NPE、无 try 边界。
- **A3**：实体告警阈值三方语义错位（默认阈值实际永不触发）。
- **A4**：reload 窗口误报"已恢复正常"（假恢复）。
- **A5**：报告层禁用模块照显 0 / 趋势把不可用画成 TPS=0。
- **A6**：`reports/` 清理会误删用户自建文件（数据安全）。
- **A8**：阈值硬编码不读 config（19-21 处显示决策点与告警阈值两套口径）。
- **A9**：reload 半途异常致采集停摆、interval≤0、阈值倒置零校验。
- 世界参数大小写静默全 0、help 点击执行字面量、报告并发覆盖等 20+ 项。

### Removed

- 红石活动（空实现）与网络吞吐量（硬编码假数据）监测——`/perfscope redstone` 保留入口并提示已下线。

### Notes

- 见下方各 Wave 段的完整开发流水（含证据、验证与遗留项）。逐条溯源：`docs/01-reviews/`（15 份审查 +
  4 份锐评 + 合成裁决）、`docs/06-tasks/`（路线图与修复计划）、`docs/07-guides/PERFSCOPE-TEST-REPORT.md`。

---

## [1.1.0] — Wave 7「v1.2 核心重构」- 2026-10-17

> **主题：从"看得见"到"记得住、喊得醒"**（`critique-SYNTHESIS.md` 第 2 层 + `WAVE7-DESIGN.md` 设计契约）。
> 严格串行三批（文件重叠不可并发）：7A 历史环形缓冲 → 7B+7C 结构化 Alert + 告警收敛 → 7D 日摘要 + webhook。
> 7B+7C 与 7D 两个 agent 先后因基础设施 API 故障中断——**7B+7C 工作已全部落盘（核验通过）；
> 7D 未落盘任何内容，由编排者接管亲手完成**（契约全文见设计文档）。
> **状态：全部落地，编译 BUILD SUCCESS；实机验证（Wave 7V）见下。**

### Added
- **Wave 7A · 历史环形缓冲（存亡项最小版）**（依据：锐评 D 结论——五个事故剧本里四个半的第一问是
  "什么时候开始的"，而此前最长记忆仅 300 快照 ≈ 5 分钟）：
  - 新包 `cn.perfscope.history`：`HistorySample`（record，RAW 值原样存哨兵）+ `HistoryStore`
    （24h 环形缓冲；容量 = 86400/间隔 自适应；`record` 按 `history.sample-interval-seconds` 节流；
    单锁 + `List.copyOf` 不可变副本；**TPS 模块不可用快照整条跳过**防假 0 污染最低 TPS；
    helper（lowestTpsSince/peakEntitiesSince/peakHeapSince）过滤无效值；零新依赖）。
  - 注入：PerfScope 持有实例（reload 伸缩不清空），collector 构造注入，命令层经 `getHistoryStore()`。
  - 新命令 `/perfscope history`（别名 `hist`）：覆盖时长/最低 TPS/峰值实体/峰值内存（HH:mm），
    空态"暂无历史数据"；help + Tab 补全同步。
  - 新配置：`history.enabled`（默认 true）、`history.sample-interval-seconds`（默认 60，≤0 钳制+warning）。
- **Wave 7B/7C · 结构化 Alert 值对象 + 告警收敛**（依据：锐评 D"默认配置日估 300-450 条=狼来了"）：
  - 新类 `cn.perfscope.alert.Alert`（record：key/level/message/display/module/timestamp；
    `of()` 工厂自动提取 display；key 规则 `metric:scope`，逐世界 `entities:world:<名>`）。
  - `MetricsSnapshot.alerts`（结构化真相源）；`activeWarnings/activeCriticals` 改为**派生列表**
    （`addAlert` 统一同步填充）——命令层 `/ps`、报告三格式、JSON `alerts.*` 全部零改动。
  - 采集层 8 处告警生成点全部改为构造 Alert（message 逐字保留；含降频缓存与 JVM 异步的
    alerts 一并缓存/传递/回放）。
  - **AlertService 输入切换**：消费 `snap.alerts`；恢复抑制改用 `alert.module()` 直接对
    `unavailable`（删除 `keyOf`/`moduleFor` 字符串解析——"中文字符串当协议"根治）。
  - **防抖（新）**：`alerts.debounce-cycles`（默认 3，≤0 钳制+warning）——同一 key 连续 N 个
    检查周期超标才首次推送；缺席即清零；reload 不清。
  - **一事件一通知（新）**：级别升级只推一条 `[告警][严重] <message>（级别升级）`，
    **不受冷却限制**，升级后刷新冷却并更新 display（之后恢复用新 display）——不再产生
    "旧 key 恢复 + 新 key 告警"两条。
  - **默认阈值重调**：`players 80/95 → 90/98`（80% 容量即告警过于灵敏）；
    `memory 80/90 → 85/95`（G1 正常水位属健康区间）。README 双语配置示例同步。
- **Wave 7D · 每日摘要 + webhook 最小版**（依据：锐评 D"告警通道与 7×24 场景互斥"；
  编排者亲手实现）：
  - 新类 `DailySummaryService`：每分钟检查；3 行摘要（`[PerfScope] 每日摘要 | 覆盖: 近 N 分钟` /
    `最低 TPS: X.XX（HH:mm） | 峰值实体: N | 峰值内存: P%` / `告警统计: 推送 W 条 / 当前活跃 A 条`）；
    出口 = 控制台 + 在线 OP + webhook；**补发语义**（今日时刻已过且未发 → 启动/重载后补发一次，
    保证"每天都到"，也让功能可测）；每日去重 = LocalDate（reload 不清、跨天滚动）。
  - 新类 `WebhookNotifier`：discord / onebot(QQ) 双格式；Gson 序列化；**全异步**（sendAsync，
    主线程零阻塞）、connect/request 超时 5s、失败日志 60s 限频、**日志绝不出现完整 URL**
    （只记 host——webhook URL 内嵌令牌，进日志即泄露）；url 空/非 http(s) 初始化即拒绝。
  - `AlertService` 接线：pushAlert/pushUpgrade/pushRecovery 三处转发 webhook + 当日推送计数；
    新增 `getActiveCount()` / `getPushedTodayCount()`。
  - 新配置：`alerts.daily-summary.enabled/time`（默认 false / "08:00"，非法格式回退+warning）、
    `alerts.webhook.enabled/type/url`（默认 false / discord / ""，未知 type 按 discord 处理+warning）。
  - ConfigManager 新增 `parseHhMmMinutes`（容忍 "8:00"；配置校验与 Summary 共用同口径）。

### Notes
- **设计契约**：`docs/06-tasks/WAVE7-DESIGN.md`（7A/7B+7C/7D 的接口签名、key 命名、防抖语义、
  文案锚点、补发修订全部锁定——本波三批 agent 的单一事实源）。
- **harness 兼容**（编排者逐条核对）：既有 `perfscope-alert` 9 条断言语义在防抖生效后仍成立
  （防抖只延迟首发 ≈3s，各 timeout 30-60s 余量充足；6s 静默窗 < 10s 冷却不变）；
  surface/boot/report/config/vbot 场景不受影响；新增 3 个场景待 Wave 7V 验证
  （history 7 条 / alertv2 6 条 / daily 5 条）。
- **agent 故障记录**：7B+7C agent 死于 API 524（工作已全部落盘，编排者核验）；7D 两个 agent
  先后死于 API 524/502 且未落盘任何内容——由编排者接管实现（含 WebhookNotifier 与
  DailySummaryService 全文）。基础设施故障未影响本波交付完整性。
- **清单外发现（记录）**：① alertv2 场景的 DB-01 防抖窗口断言有时序敏感性（若调度漂移使
  3 周期落在 2s 内会 FAIL）——正腿 DB-02 保证真到达不受影响；② 空态 history 文案未提
  `history.enabled=false` 原因（v1.3 打磨）；③ 旧 300 快照 Deque（趋势/报告用）与新 24h 环
  并存（有意保留：前者供命令趋势图，后者供摘要/回溯）。

---

## [1.1.0] — Wave 6「分发准备」- 2026-10-17

> **主题：上架前的地基**（`critique-SYNTHESIS.md` 第 1 层 + `POST-CRITIQUE-FIX-PLAN.md` Wave 6）。
> 3 agent 并行（许可/文档 / 上架材料 / bStats+门禁）+ 编排者对外信息修正，编排者统一收口。
> **状态：全部落地；实机验证通过（release-check -WithServer 12/12，含 server boot+surface 真实 Paper 断言）**。

### Added
- **W6A · CLA 落地**（上架硬前置：无 CLA 时首个外部 PR 即永久锁死双许可）：
  - `CLA.md`（新，中英双版本、以中文为准）：贡献者保留版权，授予维护者永久/全球/非独占/免版税/
    不可撤销的版权许可；**第 2.3 条显式写明再许可权含闭源/商业许可证**（双许可之门）；Apache ICLA 式
    专利许可与防御性终止；原创性/雇主授权声明；顶部醒目"自拟模板、未经法律审阅"声明。
  - `CONTRIBUTING.md`（新）：PR 流程、构建要求、CLA 签署流程（首 PR 声明句 + 维护者记录）、行为期望。
  - `.github/PULL_REQUEST_TEMPLATE.md`（新）：CLA checkbox + 描述/测试段。
- **W6D · bStats 匿名统计接入**（唯一装机反馈信号；发布第一天可用）：
  - `org.bstats:bstats-bukkit:3.2.1`（MIT）+ maven-shade-plugin 3.6.2 **最小化配置**：`artifactSet`
    仅含 bStats 两个 artifact；relocate `org.bstats → cn.perfscope.shaded.bstats`（**独立前缀，
    绝不使用 `cn.perfscope.lib.*`**——保持 v1.0.0 事故指纹零命中）；`createDependencyReducedPom=false`。
  - `PerfScope.java`：`startBstats()` 双重门（`general.bstats-enabled` 且 `BSTATS_PLUGIN_ID > 0`——
    **当前为占位 0，不发送任何数据**，注册后替换生效）+ `catch (Exception | LinkageError)` 整段兜底；
    ID 先读局部变量以**防 javac 常量折叠消去字节码**（保住 javap relocate 实证能力，W6D agent 实测发现）。
  - `config.yml` + `ConfigManager`：新增 `general.bstats-enabled`（缺键默认 true），注释含两处关闭方式。
- **W6C · 上架材料包**：`docs/07-guides/PERFSCOPE-LISTING-KIT.md`（上层工作区，新）——Modrinth/Hangar/
  MineBBS/KLpbbs 四平台可直接粘贴文案（双语）+ 截图清单 6 张 + 发布操作清单 + 30 天 KPI 观察清单；
  全文事实核对至现行 README（无 Spigot/Velocity 声称、无 7×24 夸大、spark 互补引导内嵌）。

### Changed
- **W6B · 许可证表述纠错**（双语 README）：删除"商用须保留原作者署名"误述（**不是 AGPL 条款**），
  改为准确表述（自由商用无需付费；任何分发须保留版权声明与许可文本并整体 AGPL；网络服务须供源码 §13）；
  版本分界经 LICENSE 与 git 历史核实（初版 GPL-3.0 @49fe55e → AGPL-3.0 @f91985f）后保留。
  另新增 bStats 披露（双语；并经编排者修正为"当前占位 ID 不发送数据"的准确时序表述）。
- **W6D · 发布门禁加固**：`release-check.ps1` 新增两条断言——`net/kyori/` = 0（Adventure 永远由 Paper
  提供）、`cn/perfscope/shaded/bstats/` ≥ 1 且裸 `org/bstats/` = 0（bStats 已打包并 relocate）；
  `.github/workflows/build.yml` 同步新增同款 shell 断言；体积基线注记更新（~80KB → ~110-120KB）。
- **W6D · 技术债**：compiler plugin `source/target` → `<release>${java.version}</release>`
  （锐评 B 论点 10：防 JDK21 上误链接 17+ API）。
- **W6E · GitHub 对外信息修正**（编排者执行，gh api）：仓库 description 删除错误的
  "Spigot / Bukkit / Velocity" 声称，改为准确的 Paper 系双语描述；新增 10 个 topics
  （minecraft/papermc/performance/monitoring/tps/mspt/alerts 等）。
- `docs/07-guides/RELEASE-CHECKLIST.md`：更新至 Wave 6 状态（三层 jar 断言、~110-120KB 基线、
  六场景（含 perfscope-vbot 12/12）、指向测试报告 §9 基线）。

### Notes
- **实机验证（编排者）**：`release-check.ps1 -WithServer` **12/12 PASS**——jar 111KB / 57 条目、
  `cn/perfscope/lib/`=0、`net/kyori/`=0、`cn/perfscope/shaded/bstats/`=19、裸 `org/bstats/`=0；
  真机 Paper 加载正常（boot 6/6 + surface 36/36）、控制台零异常、bStats 占位提示正确输出。
  **v1.0.0 事故后首次在带 shade 的构建上完成"装服实证"**——这正是当年缺失的那道关卡。
- **javap 实证（W6D）**：`new cn/perfscope/shaded/bstats/bukkit/Metrics` + `(Lorg/bukkit/plugin/Plugin;I)V`
  ——relocate 生效且与 Paper 的互操作签名未被改写；全 class 常量池零残留裸 `org/bstats` token。
- **待用户动作（发布链）**：① 注册 bStats 拿 pluginId 替换 `BSTATS_PLUGIN_ID = 0`（或先发布亦可——
  占位期无任何数据发送，但装机信号从替换后才开始积累）；② 上架材料已备好
  （`PERFSCOPE-LISTING-KIT.md`）；③ 发布（bump 1.1.0 + Release + tag）等用户口令。
- **清单外发现（记录）**：① `CLA.md` 为自拟模板，正式启用前建议作者审阅（或咨询法律人士）；
  ② Folia 声称支持但本机无法实机验证（装载勾选由作者拍板）；③ 游戏内输出仍全中文（英文描述已如实披露）。

---

## [1.1.0] — Wave 5「发布前加固」- 2026-10-16 ～ 10-17

> **主题：四路对抗性锐评后的发布前加固**（`docs/01-reviews/critique-SYNTHESIS.md` 裁决的第 0 层：
> 发布前不写新功能，先把"可发布状态"备好）。4 个 agent 并行（文件互斥：调度层 / 配置文档 /
> 测试基建 / 命令层），编排者统一收口。修复计划全文：`docs/06-tasks/POST-CRITIQUE-FIX-PLAN.md`。
> **状态：全部落地，编译 BUILD SUCCESS，字节码级复验通过**（含编排者对 W5-A 中断产出的接管核验）。

### Refactored
- **W5-A · SchedulerAdapter 去反射重构**（依据：锐评 B 论点 5——反射版的前提"paper-api 1.20.4 不含 Folia
  调度类"系**误判**，javap 实测本地 paper-api 1.20.4 工件本就含 `GlobalRegionScheduler`/`AsyncScheduler`/
  `ScheduledTask` 且 `Bukkit` 有静态 getter）：
  - 删除全部 6 个反射 `Method` 缓存、静态初始化块与 `invoke` 调用（168 行 → 反射引用归零），
    Folia 分支改为编译期直连；公开 API（`runGlobalTimer`/`runAsyncTimer`/`runLater`/`TaskHandle.cancel()`）
    签名与语义逐字不变，5 处调用方零改动。
  - **新增 `FoliaSchedulerBridge`（隔离层）**：W5-A agent 复核时发现并实证了一个真实边界——
    plugin.yml 声明 `api-version "1.20"` 覆盖 1.20.0 全系，而 **Paper 1.20.0 的 paper-api 1.20-R0.1
    确实不含** threadedregions 调度类（编排者复验：下载 1.20.0 工件实测 `threadedregions` 类数 = 0）。
    理论上 1.20.0 上 `isFolia()` 恒 false、Folia 分支不可达，但为把"类/方法缺失"风险在任何 JVM
    解析策略下压到零，全部 Folia 直连调用集中于该类——非 Folia 服务端上永不加载，常量池中的
    Folia 符号永不进入链接流程；`SchedulerAdapter`（全环境必加载）常量池经 javap 复验已无任何
    Folia/reflect 符号。
  - fail-safe 语义保留：Folia 分支任何失败（含 LinkageError）记 severe + 返回 noop 句柄（功能停用），
    绝不降级 Bukkit 调度器（真 Folia 上必抛 UOE）。
  - **验证**：`mvn clean package -DskipTests` BUILD SUCCESS；javap 双查（SchedulerAdapter 常量池零
    Folia/reflect 引用、FoliaSchedulerBridge 含全部直连字节码）。

### Added
- **W5-C · 发布冒烟门禁**（依据：v1.0.0"100% 不可用仍发布"事故——当时无任何发布前门禁）：
  - `tools/release-check.ps1`（上层工作区，新建）：一键发布前检查——构建 → 产物校验（jar 存在/
    体积量级/**断言 0 个 `cn/perfscope/lib/` 条目**（v1.0.0 shade-relocate 事故指纹）/plugin.yml
    version 非占位符/class 条目非空）→ 可选 `-WithServer` 跑 testharness boot+surface 场景 →
    `RELEASE CHECK: PASS/FAIL` + 非零退出码。实测 8/8 PASS；负向注入测试（假 lib 条目/占位符/
    缺 jar）全部如实 FAIL；**并真实拦截过一次破损构建**（W5-A 重构中间态编译失败被门禁判 FAIL）。
  - `.github/workflows/build.yml`（仓库内，新建）：push/PR → temurin 17 + maven cache → 构建 →
    两步 shell 断言（`unzip -l | grep cn/perfscope/lib/` 反查 + plugin.yml 占位符检查）→ 上传 artifact。
  - `docs/07-guides/RELEASE-CHECKLIST.md`（新建）：发布前人工清单（构建 → 门禁 → 5 场景 →
    人工验收 → bump → Release/tag → 归档 → 上架）。
- **W5-D · 快照新增 `warmingUp` 集合**（契约 `warmingUp ⊆ unavailable`，`unavailable` 本身零改动、
  其余 69 个消费点语义不变）：`MetricsSnapshot.java` + `MetricsCollector.java`（`WARMUP_WINDOW_MS=10s`，
  `start()` 重置；仅 jvmCache 为空且窗口内标 memory/cpu/gc；超窗回落真故障，防"永远预热中"）。
- `versions/README.md`：标注 `PerfScope-1.0.0.jar` 为已知 100% 不可用构建存档（双语）。

### Changed
- **W5-D · 预热态与真故障文案区分**（依据：开放项 #4 / 锐评 D 信任警察——两者输出同款"不可用"，
  事故语境下是虚假信标）：展示层新增 `unavailableText()`，预热态渲染"预热中（首轮采集未就绪，
  稍候自动更新）"，真故障保持"不可用"；覆盖 showAll 内存/CPU/GC、`memory|cpu|gc` 专项、
  ActionBar（短文案"预热中"）；uptime/线程数=0 的首周期"半成品面板"同步治理（M8 同源）。
- **W5-D · 报告路径可点击**（依据：开放项 #5）：玩家收到的报告路径挂 `ClickEvent.copyToClipboard`
  + 下划线 + hover"点击复制路径"；控制台/RCON 保持纯文本（harness 断言零冲突，已只读核对 5 场景）。
- **W5-D · "正在采集数据中"文案**：统一走 `noData()`（消除双份硬编码，约 20 个调用点同源），
  补原因/时限/下一步："首次采集尚未完成（插件刚启动/重载），约 1 秒后就绪；若超过 10 秒仍无数据请查看控制台"。
- **W5-D · help 面板 `tileentities` 行补 `[世界]`**（实测发现 `te foo` 有世界参数处理但 help 未标注）。
- **W5-B · plugin.yml 构建元数据**：`version: 1.0.0` 硬编码 → `${project.version}`（filtering 已开；
  渲染验证通过，零 `${}` 残留；**发布前须 bump pom 版本，plugin.yml 自动跟随**）；`author: PerfScope Team`
  → `sqkl520`（单人项目不冒充团队）；`website` 错链 `github.com/PerfScope` → `github.com/sqkl520/Minecraft-server-plugin-PerfScope`。
- **W5-B · 双语文档（README.md / README-EN.md）6 处**：① 彩色 UI 表述改为"按 config.yml 告警阈值
  分级着色，同一口径"（A8 已联动，原"计划于 v1.2"过时）；② 新增 TPS 阈值方向注（低值指标要求
  `warning ≥ critical`，倒置回退默认+告警——与操作直觉相反）；③ monitor 降级为"诊断探针"定位
  （不宣传 7×24 面板）；④ 新增「边界与不做什么」4 条（无根因剖析交 spark / 无历史持久化 /
  MSPT 窗口约 100 tick / 无自动处置）；⑤ 告警冷却 reload 不清空的已知行为注；⑥ 拒接清单摘要。
- **W5-B · `.gitignore`**：`/versions/` → `/versions/*` + `!/versions/README.md`（git 规则：父目录被
  整体排除时无法重包含文件；`git check-ignore` 验证 jar 仍忽略、README 可入库）。
- **W5-B · `PERFSCOPE-TEST-PLAN.md` 回填实测更正**：`/ps` 非有效别名（plugin.yml 只有 `pscope`/`perf`）；
  `report <非法格式>` 静默回退目标实测为 **TXT**（非 html）。

### Notes
- **W5-A agent 被会话中断，收尾由编排者接管完成**：其重构落地完整（反射归零、隔离桥成形），
  编排者复验三步——完整构建 BUILD SUCCESS、javap 常量池隔离确认、Paper 1.20.0 工件缺类边界
  实证（下载实测 threadedregions 类数 = 0）。原计划的"API 对照表"由 javap 输出直接覆盖。
- **Wave 5V 实机验证已通过（2026-10-17，HEAD `49c4ac0`）**：
  ① 第一轮 5 场景 **72/72 零回归**（boot 6 + surface 36 + config 11 + alert 9 + report 10）；
  ② 加装 VitaminMCP agent 25588 后新增 `perfscope-vbot` 场景 **12/12 全绿**——monitor 开关与
  ActionBar 上屏、禁用模块客户端侧"实体: 不可用"降级、monitor 中强制踢出零 NPE/零推送失败、
  控制台 player-only 守卫、无权限玩家权限门（经 `command_exec as=` 服务端侧分发实证玩家收到
  "你没有权限使用此指令喵"）。合计 **84 条断言全过**。
  报告：`docs/07-guides/PERFSCOPE-TEST-REPORT.md` §9。
- **验收副产品·重要发现（命令权限两层模型）**：非 OP 玩家**自发**命令会被 Paper 的
  per-player 命令树在**原版分发器**拦截（收到原版英文 `Unknown or incomplete command`，
  对照组 `/list` 同样如此），**插件代码根本不执行**；插件自身的权限门需以服务端侧
  `command_exec as=<玩家>` 路径触发方可观测。首轮 MON-04 的 FAIL 经两轮定向探针确认为
  **测试路径设计错误而非产品 bug**，场景已按两层模型修正固化（见测试报告 §9.3）。
- **已知行为（W5-D 引入，可接受）**：配置禁用的模块在 start/reload 后首个 tick 内（jvmCache 未发布时）
  会短暂显示"预热中"而非"不可用"，异步缓存发布即纠正（窗口 < 1 秒，纯观感）。
- **清单外发现（记录待后波次处理，本轮未修）**：
  ① 禁用态与故障态在数据模型上不可分（`runOrMark` 对"配置禁用"也标 unavailable；需数据模型加字段）；
  ② `showAll` 世界明细段在 entities/chunks 模块 unavailable 时仍渲染按世界 0 值（与模块级口径不一致）；
  ③ 上级 CLAUDE.md 两处描述过时（plugin.yml 版本已 filtering、.gitignore 规则已变更）；
  ④ testharness 侧：perfscope 实例的 `server.psd1` 含中文 Notes 时必须存为 UTF-8 **BOM**
     （PS 5.1 无 BOM 按 ANSI 读会破坏解析——已修正在案）。
- **待验证（用户实测）**：预热态文案观感、报告路径点击（vbot 无 click_chat 动作，需人工）、
  Folia 直连版实机（本机只有 Paper，Folia 路径仍不可测，代码审查已覆盖）。

---

## [1.1.0] — v1.2 前置：A8 阈值读配置 - 2026-10-14

> **主题：阈值/配色硬编码清零（A8）**。Wave 4 审查遗留欠账之一——「A8 阈值硬编码不读 config」
> （当时刻意押后，避免与 15 项 S 级修复打架）——在本波清理：新增统一阈值色阶工具类
> `ThresholdColors`，命令层与报告层的硬编码色阶全部替换为读 `config.yml`。
> **状态：编译验证已通过**（`mvn clean compile` + `package -DskipTests` 双 BUILD SUCCESS；34 处调用点签名全部匹配、零修复、零残留；6 条行为变更逐条确认落地；Wave 4 修复项无一被破坏）；
> 本波同时含「规格债回写」文档工作（见 Notes）。

### Added
- `cn.perfscope.util.ThresholdColors`（新类）：
  - 枚举 `Level { GOOD, WARN, CRITICAL, UNKNOWN }` —— 统一四级状态色阶；
  - 枚举 `Metric { TPS, MSPT, MEMORY, CPU_PROCESS, CPU_SYSTEM, PLAYERS, ENTITIES, CHUNKS }` —— 指标维度；
  - `levelOf` 读 config 判定等级；`cssClass` / `colorOf` 映射展示色；`tpsTrendBucket` 统一 TPS 趋势分档。

### Changed
- **命令层 29 处 + 报告层 5 处**硬编码色阶引用，全部替换为 `ThresholdColors` 读配置
  （消除命令层与报告层各写一套阈值的分裂）。
- 文案 `"超过 Xms"` 的 X 改为读 config 实际数值（不再写死）。
- **行为变更（6 条，需在测试服确认）**：
  ① 区块红界：硬编码 1500 → config `critical` 2500；
  ② CPU：不再有红色档（config 无 CPU critical 键，最高 WARN）；
  ③ 报告内存界：硬编码 70 → config 80（消除与命令层 80 的矛盾，两侧由此一致）；
  ④ MSPT 黄界：硬编码 40 → config 45；
  ⑤ TPS 趋势：19.5 档改为「满值档 >= 20」；
  ⑥ ActionBar（monitor）内存配色改为读配置。

### Notes
- **待验证**：行为变更 ①～⑥ 的最终阈值观感需用户在测试服确认（尤其 ① 区块红界 2500、⑤ 满值档）。
- **编译已过**：A8 编译验证完成——`compile`/`package` 双 BUILD SUCCESS、34 处调用点零修复、零残留色阶硬编码、6 条行为变更全确认、Wave 4 守卫项（unavailable/-1/connection/try 边界/JSON 键名）抽查全数完好。
- **同波文档工作（规格债回写）**：4 处未记录偏差入档 `ralph/progress.txt`（完整 6 项偏差表）；
  ROADMAP v1.1 验收标准 Spigot 条目回写；`ralph/prd.json` P0-3 等 Spigot 相关
  acceptanceCriteria 回写「Paper 系」表述；`ralph/RELEASE_TEST.md` 补录 DoD-3/5 待补条目。

---

## [1.1.0] — Wave 4 - 2026-10-12 ～ 10-14

> **主题：审查后修复**。v1.1 三个开发波次完成后，用 **15 个并行审查 agent** 做了一次全量体检
> （累计下载 34+ 份真实服务端工件做字节码级实证），产出了 **1 项 P1 功能缺陷 + 5 类"安静的 bug"**
> （数据看着正常、结论是错的）+ 4 项被证伪的初始怀疑。本波把这些全部修掉。
> 修复分三路并行（文件集互斥：采集探测链 / 命令层 / 报告配置调度）→ 统一编译 → 三维测试
> （build 17/17 PASS、arch R1-R9 落实、code 17 修复点零回归）→ 修正轮（1 项 P2 + 2 处注释不实）→ 提交。

### Fixed
- **MC-1 · MSPT 精确采集死反射链**（🔴 全场最重，两个审查 agent 独立字节码实证）：
  原 `getTickTimes` 反射链有**两级叠加错误**——①探的是 NMS `DedicatedServer` 的方法，而方法实际在
  **`CraftServer`** 上；②`TickTimes` 根本没有 `time()/minTime()/maxTime()`（只有 `getTimes()/getAverage()`）。
  后果：`PAPER_MSPT_AVAILABLE` 恒 false → min/max MSPT 恒等于 avg → **Paper 服上 `/ps` 恒显"(估算)"、
  启动日志恒显 `MSPT精确采集: TPS估算`（真实 Paper 数据被误标估算）** → `collectMSPT_Paper` 为死代码。
  修复：探测点改 `Bukkit.getServer().getClass().getMethod("getTickTimes")`（返回 `long[]`），删除
  `tickTimes_getTime/MinTime/MaxTime` 三字段与 NMS 三段链；`collectMSPT_Paper` 改为遍历 `long[]` 自算
  avg/min/max（nanos→ms，`/1_000_000.0`），边界：空数组或全非正值 → 三值置 `-1`"未知"（不做除零、
  不输出误导性 0），单元素 ≤0 跳过（防未填充槽位拉低 min/avg）。
  **半修复陷阱已规避**（A1 报告警告：若只修探测不修形状提取，会在 `getMethod("time")` 处静默失败但
  `getTickTimesMethod` 已非 null → 进入死掉的 `collectMSPT_Paper` → 每周期 NPE 落第三级，比现状更糟）——
  两项同时改，javap 复验字节码为 `checkcast [J` + 自算 + `n==0` 置 -1 哨兵，降级链正确指向二级。
  验证：8 个真实发行版 jar（Paper 1.20.4/1.20.6/1.21.4、Purpur、Folia、Leaf）隔离 ClassLoader
  反射探针复现旧链全部 `NoSuchMethodException`；build 维 javap 双查（源码+jar）零残留。
- **A2 · monitor 五连伤**（🟠 5 个审查 agent 独立命中，全场最脆弱点）：`startMonitorTask` 的 ActionBar
  推送有四处缺陷——①**六字段零状态守卫**：模块 unavailable 时上屏"TPS: 0.0"红、"内存: 0%"**假绿**、
  MSPT 直出"-1.0"；②**无 try 边界且注释谎称有**（注释写"外层 try 兜底"实际无）；
  ③`sendActionBar` **无 connection 守卫**（对比 `sendMessage` 有，javap 实证两变体不对称）→ 玩家断线
  瞬间 NPE；④玩家守卫。修复：六字段全部按全仓约定渲染（module unavailable → "不可用"灰 / 值 -1 →
  "未知"灰，复用 `formatTpsComponent`/`formatMSPTComponent`）；整个任务体包 `try-catch
  (Exception | LinkageError)` + `general_debug` 门控堆栈；发送前 `!player.isOnline() ||
  player.getAddress() == null` 跳过（`getAddress()` 是字节码实证的选择：真实 Paper 1.21.4 上
  connection==null 时返回 null，且 API 非弃用）；注释改为与实现一致。顺带修复 UX-F8：monitor 的
  TPS 配色门槛 19.5→18/15 与命令层统一、内存补 ≥90 红档、MSPT 不再恒 AQUA。
- **A3 · entities 阈值三方语义错位**（🔴 告警实际失效）：`config.yml` 注释与命令层按"**总**实体数"
  消费，但采集层按"**逐世界**"比较——同一个键两种判定；**默认 2000 阈值在单个世界中几乎不可达
  （告警实际永不触发）**，而命令层却会对全服总数报警。修复（采集侧，读完 A5 报告后裁决）：
  `worlds.*` 有覆盖的世界按各自阈值逐世界判定（保留原按世界文案）；**无覆盖的世界不再套用全局阈值**；
  新增全服总数判定（对 `snap.totalEntities` 判一次，新文案"实体总数较多: N"/"实体总数严重超标: N"）。
  按世界统计 Map 输出与降频缓存路径未动。config.yml 注释同步澄清（"配置了 worlds 覆盖的世界按各自
  阈值逐世界判定、同时仍计入全服合计总数；未配置的世界由全服合计阈值覆盖"）。
- **A4 · AlertService 假恢复**（🟠 两个 agent 独立命中）：reload/启动后首周期 JVM 模块 unavailable 窗口
  × 状态机跨 reload 保留 → 活跃内存/CPU 告警被差分判为"消失" → **发虚假绿色"已恢复正常"**，随后被
  冷却压制最长 300s 无纠正信号。修复：恢复差分**按模块归因**——新增 `moduleFor(key)` 把告警 key
  （归一化后前缀）映射到 `MODULE_*`，**仅当该模块自身 unavailable 时抑制该 key 的恢复**（保留在
  activeAlerts 中，下周期再判），其他模块照常；无法归因的 key 正常恢复 + `Level.FINE` 日志兜底排查。
  16 条告警文案逐条 grep 核对、16/16 全命中映射（含"方块实体"先于宽泛"实体"、"世界…加载区块数"
  先于"实体"等前缀冲突处理）。
  ⚠️ **修正轮记录**：初版实现用"`unavailable` 集合非空"作**全局**抑制条件——而**任何被配置禁用的
  模块都会让集合恒非空** → 恢复判定**永久**跳过、所有模块的真恢复通知**永久静默**（注释自称"宁可漏
  一次"实为"永久漏"）；build/code/arch 三维测试中两个 agent 独立命中该 P2，修正轮改为上述按模块归因。
- **A5 · 报告层 unavailable 契约缺口**（🟠 grep 实证 + 三出口核对）：
  ①`MODULE_TILE_ENTITIES` 判定在 ReportGenerator **零命中** → TE 禁用/失败时 HTML/TXT 世界表与 JSON
  照显 0；修复：三出口全补（JSON 新增 `tile_entities` 段，不可用输出 `{"status":"unavailable"}`）；
  ②世界分布表三列（实体/区块/TE）各判各模块 + 空表兜底（HTML `colspan` 灰字行 / TXT 整段"不可用"）；
  ③趋势三出口把不可用采样渲染成 TPS=0 灾难（命令趋势条整段"!"、HTML 红色 0 高柱、JSON 输出 0.0）——
  命令侧改灰色"?"中性字符，HTML 侧改中性灰色矮柱（`continue`），JSON `tps_history` 不可用采样改
  `null`（**v1.1 唯一有意的值语义变更**，注释记录；Gson `serializeNulls` 已开启）；
  ④`showMemory` 内嵌 GC 行补 `MODULE_GC` 守卫。
- **A6 · cleanOldReports 会删用户文件**（⚠️ 数据安全）：清理条件仅 `lastModified`、无文件名过滤 →
  `reports/` 目录下用户自建的任何文件/子目录超 retention 即被静默删除；且**时钟前跳**（NTP 校时/
  手动改钟）→ 全目录文件一起"过期" → 成批删除（含用户备份）。修复三连：`isReportFileName()` 只删
  `perfscope-*.{html,txt,json}` 且 `isFile()`；`MAX_CLEAN_PER_RUN = 50` 单次删除上限（恰好删满 50
  不误报）；`FUTURE_MTIME_TOLERANCE_MS = 3600_000L`——文件 mtime 为"未来 1 小时以上"跳过不删。
- **A9 · 健壮性三小项**（🟡 操作逻辑审查独立命中）：
  a. **reload 半途异常 = 采集停了不再重建**：`configManager.load()` 若抛异常（如 `update_interval`
     配成负值触发），之后采集任务停摆、告警不启，命令静默输出旧快照，控制台仅一句 Bukkit 通用报错。
     修复：load 包 `try-catch (Exception | LinkageError)`，**无论成败都继续重建采集/告警/自动报告**，
     失败时 severe + 堆栈日志 + "重载未完成"消息，成功才打"配置已重载喵~"。
  b. **interval ≤0 无下限校验**：0→每 tick 采集；负值 Bukkit 下被调度器 coerce 成"单次任务"静默死亡、
     Folia 下被校正为每 tick（**行为分叉且静默**）。修复：`MetricsCollector.start()` 与
     `startAutoReport()` 统一 `Math.max(1, …)` + warning 日志（对齐 AlertService 已有范式）。
  c. **阈值配反（warning > critical）零校验**（7 处判定点受影响，低值告警类恒显 critical、高值类恒显
     warning）：新增 `ConfigManager.validateThresholds()` 集中校验——区分**低值差**（tps：`warning < critical`
     即倒置）与**高值差**（mspt/内存/玩家/实体/区块：`warning > critical` 即倒置）两类语义 +
     worlds 段覆盖对 + CPU 非有限值（NaN/Inf）+ `alerts.cooldown-seconds` 负值钳 0；倒置一律
     warning 日志（模板化文案）+ 回退默认值，不静默不抛异常。
- **GC -1 消毒**（🟡 低概率）：`collectGC` 对 MXBean 的 -1（未定义语义）不消毒直接累加，可能污染
  `gcCount/gcTime`。修复：`if (c >= 0) totalGcCount += c;`（time 同理）。

### Changed
- **B1 · SchedulerAdapter Folia 兜底地雷拆除**（🔴 "启用即被打挂"级）：Folia 分支反射 invoke 抛任何
  异常都会落入"降级 Bukkit 调度"兜底，而该兜底在**真 Folia 上必抛 `UnsupportedOperationException`**
  （CraftScheduler 被硬改，compat-matrix 实证）→ **插件 enable 被打挂而非降级**。修复：三个任务提交
  入口的 Folia 分支失败**不再降级 Bukkit**，改为 `severe` 日志（"Folia 调度反射不可用，相关功能停用：
  <原因>"）+ 返回 `TaskHandle.noop()`（无操作句柄，cancel 为空操作）；新增 `describe(Throwable)`
  拆包 `InvocationTargetException` 显示真实 cause；类头注释更新为 "Folia 下反射失败 = 功能停用
  （fail-safe），绝不降级 Bukkit"。
- **B2 · help 面板点击执行字面量**（🟠 交互 bug）：`sendHelpLine` 把带方括号的用法原文直接当命令执行——
  点 `/perfscope report [格式]` **每次都静默多生成一份 TXT 报告**、点 `entities [世界]` 以假世界名
  `[世界]` 查询静默为空、点 `world [世界]` 报"世界 [世界] 不存在"。修复：`sendHelpLine` 增加 4 参重载
  （display / clickCmd / desc），4 个含占位符的行改为可执行实例命令（`report html`/`entities`/`chunks`/
  `world`）；未引入新拼接注入面（clickCmd 全为硬编码字面量）。
- **B3 · 世界参数大小写静默全 0**（🟠 假数据）：`Bukkit.getWorld` 大小写不敏感（javap 解包 Paper jar
  实证 CraftServer 为小写键+小写查询），但统计查询与玩家计数用**原始串** → `/ps world MyWorld`
  实体/区块/TE/玩家全显 0。修复：新增 `resolveWorld(sender, arg)` 统一入口（null → null；不存在 →
  发"世界不存在: <输入>"并返回 null；存在 → 返回 `World` 对象），showWorld/showEntities/showChunks/
  showTileEntities 四处全部改用 `world.getName()`（规范名）做全部 map 查询/玩家比较/WorldSettings
  查询/标题显示。
- **B4 · entities 世界过滤仍是全服口径**（🟠 误导）：`/ps entities <世界>` 的"按类型 Top15 + 百分比"
  仍是全服数据、无标注。修复：带世界参数时对该世界即时扫描 `world.getEntities()` 得类型分布，Top15
  与百分比分母均按该世界重算，标题标注 `(Top 15, 世界: <name>)`；不带参数保持全服口径并标注
  `(全服, Top 15)`；扫描包 try-catch（Folia 异常 → "读取失败: 无法扫描该世界实体"不中断命令）。
- **B5 · reports 目录运行中被删永久失败不自愈**（🟠）：`mkdirs()` 仅构造器执行一次，运行中目录被删
  即永久写失败且 auto_export 静默。修复：新增 `ensureReportDir()`（`isDirectory()` + `mkdirs()` +
  `canWrite()` 检查），`generateSpecific` 写文件前调用，失败记明确 warning（含绝对路径与原因）并
  返回 null。
- **ConfigManager 原子发布**（🟡 架构改善）：`load()` 原为"开头 `config = new ConfigData()` 替换引用 →
  逐键填充"，外部（含异步自动报告线程）可能在填充期读到**半解析态**（阈值 0）。改为本地 `fresh` 承载
  全部解析 → `validateThresholds(fresh)` → **全部完成后单次 `this.config = fresh` 发布**；中途抛异常
  config 保持旧的完整引用。附带 `private ConfigData config` 加 `volatile`（原子可见语义）。
  **行为零变化**（键名/默认值/校验调用点与日志不变）。
- **RT-01 · JSON alerts 段加健康字段**（🟡 针对巡检脚本）：原 `alerts` 段无法区分"真健康（无告警）"与
  "全模块静默死亡（采集全挂所以无告警）"。新增 `alerts.health`：`snap.unavailable` 非空 → `"degraded"`，
  否则 `"ok"`。既有键名零改动。

### Removed
- 清理历史遗留未用 import（非本波引入）：`PerfScopeCommand` 的 `ChatColor` 与 `java.util.stream.Collectors`
  （各 grep 确认该文件零引用后删除）。注：审查报告称 `Collectors` 在 ReportGenerator——实际该文件为
  `java.util.*` 通配无此 import，顺手排查后确认在 PerfScopeCommand。

### Added
- `docs/01-reviews/` **15 份并行审查报告 + 总合成**（`v11-review-SYNTHESIS.md`）：这是 v1.1 质量的主要
  证据链。累计下载 **34+ 份真实服务端工件**（Paper 1.16.5→26.3 / Folia / Purpur / Leaf / Leaves），
  javap 反汇编 + 隔离 ClassLoader 反射探针 + 真实 SnakeYAML/YamlConfiguration 探针。
  四份最重的取证：`metrics-collector`（MC-1，289 次工具调用）、`report-config-compat`（315 次）、
  `thread-safety`（231 次）、`compat-matrix`（**513 次**，47 触点全枚举）。
- `docs/05-test-reports/` **`wave4-build.md` / `wave4-code.md` / `wave4-arch.md`**。

### Notes
- **✅ 四项初始怀疑被证伪**（防止过度修复）：①节流缓存污染 history **不成立**（refresh 为整体引用替换，
  全文件零就地 clear/put，两个 agent 独立确认）；②jvmCache 发布后可变/撕裂 **不成立**（每轮 new 全新
  构造 + 单次 volatile 发布 + 发布后全仓零写路径）；③冷却 key 失效 + map 无限增长 **不成立**
  （14 条文案全表套用 `keyOf` 全部稳定，键空间有界）；④Folia 检测误判 **不成立**
  （`RegionizedServer` 在 paper-api 1.20.4/1.21.4、Paper 1.21.4-232、leaf/leaves 中均不存在）。
  另有"周期任务抛异常被静默取消"前提被两个 agent 字节码实证**推翻**（Paper/Folia 调度器均
  catch+日志+照常重排）——此纠正把 monitor 的断线 NPE 从"偶发"升级为"每周期复发"，故一并修复。
- **✅ 防线有效清单**：红队 22 个对抗场景 **P0/P1 均为 0**——现有三层防线（P0-5 异常隔离 / 权限 /
  参数拒绝）把攻击都压成了"不可用/拒绝"。任务泄漏审计：5 类任务在 onDisable 与 reload **全部取消且
  幂等，零叠任务零漏网**。P0-3 致命 bug 修复经**两条独立证据链**确认（build 维 + spec-conformance
  维 javap：`cn.perfscope.lib.adventure...` → `net.kyori...`）。
- **📋 已知欠账（进入 v1.2 首批）**：①**A8 阈值硬编码**——21 处显示决策点 0 个完整读 config
  （默认配置下实体 510 即显假黄、区块 1600 显假红；同值不同色 7 例），需抽 `ThresholdColors` 三面共享
  （M 级、"牵一发动全身"，刻意留到本波之后避免与 15 项 S 级修复打架）；②`PerfScopeCommand` 1015 行
  god-class 拆分方案（code-quality 报告已给）；③输出快照金样基线（当前零测试基建，是后续重构的安全网）；
  ④`data-contract` 产出的 11 条契约规则表（R1-R11）工程化；⑤P0-1 降频仍不可配 + 每 5/10 周期仍有一次
  全量遍历尖峰未降（压峰值）；⑥`ServerCompat` 静态初始化异常安全（`SecurityException/LinkageError`
  逃逸 → `ExceptionInInitializerError` 全局崩）；⑦`PluginCommand` description 过时文案、
  `getSystemCpuLoad()` 弃用 API、死代码 15 项；⑧v1.3 前瞻：Alert 结构化 + 冷却 key 规则迁移到
  `metric:scope`（代码重写约 60-70%）。
- **⚠️ 规格债回写待办**：`spec-conformance` 发现的 4 处未记录偏差（P0-4 pendingTasks 决策反转 /
  P0-8 fail-fast 反转为静默降级 / P0-1 降频键未实现 / P0-15 filtering 未改）需入档到
  `ralph/progress.txt` 偏差节；ROADMAP 验收标准里 Spigot 相关条目（#1）与 prj.json P0-3 验收语句需随
  "专注 Paper 系"决策回写；DoD 第 3/5 条（spark P99、异常隔离门禁）需补进 `RELEASE_TEST.md`。
- **未验证（运行时，留给测试服，本波重点）**：
  ① **Paper 服上 MSPT 应从"(估算)"变为精确值**，且 **min/max 不再等于 avg**（MC-1 修复的直接可见效果）；
  ② monitor 假值消失（禁用某模块后 ActionBar 显示"不可用"而非假绿 0）；
  ③ 告警不再误报恢复（reload 后收到真恢复而非假恢复）；
  ④ 配置 `update_interval: -1` 不再静默停采集，改有明确日志与下限 1；
  ⑤ Folia 上加载+命令+monitor（实体/区块为尽力而为）；
  ⑥ MC-1 数据正确性对拍：与 `/tick times` 或 spark 对比 avg/min/max 同口径。
- **未归档**：`versions/` 未归档 JAR（v1.1 未发布；待用户明确确认后归档）。
- **提交**：Wave 4 三路开发 + 修正轮 + README 补全，见本段对应提交。

---

## [1.1.0] — Wave 3 - 2026-10-12

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

## [1.1.0] — Wave 2 - 2026-10-11 ～ 10-12

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

## [1.1.0] — Wave 1 - 2026-10-11

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

## [1.1.0] — 仓库整理与许可证迁移 - 2026-10-11

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
- **历史改写说明**：整理期 3 个提交经过一次历史重写，最终哈希为
  `f91985f / ba40e0b / d0aac58`；旧哈希不再被任何分支引用。
  **此后所有提交只保留作者本人身份**。
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
