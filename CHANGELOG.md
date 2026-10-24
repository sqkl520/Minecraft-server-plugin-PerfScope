# Changelog

本项目：**PerfScope** —— Minecraft 服务器性能监测插件（Paper 1.20.4+ / Purpur / Pufferfish / Folia / Leaves，Java 17）。
后台定时采集性能快照 → 游戏内 17 个子命令一屏诊断 → HTML/TXT/JSON 报告导出 → ActionBar 实时监测 → 阈值告警。

> **仓库**：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope> ｜ **版本**：`1.2.0`（2026-10-19 发布）

格式参照 [Keep a Changelog](https://keepachangelog.com/)。v1.2「找得到」开发周期已收尾，各段以 **Wave（开发波次）** 划分并标注对应提交哈希。

> **★ 硬规则（项目约定）**：任何代码 / 配置 / 文档的更改，都必须记入本 CHANGELOG（在最上方按
> Added / Changed / Fixed / Removed / Optimized / Refactored / Notes 分类追加）；**编译前先更新**。
> 版本号只在用户明确说"更新版本号"时 bump（Patch = Bug 修复，Minor = 新功能）。
> **本文件是开发流水账** —— 记"改了什么、为什么改、验证到哪一步、还欠什么"，不是给用户看的发布说明。

> **开发计划文档索引**（均在上层工作区）：`docs/06-tasks/ROADMAP.md`（愿景 + 49 条条目 + 35 条验收标准）、
> `V1.1_DEV_TASKS.md`（15 个 P0 逐项拆解）、`V1.2_DIAGNOSTIC_RULES.md`（37 条诊断规则）、
> `V1.3_ALERT_SPEC.md`（告警中心规格，30 个配置键）。
> **审查报告**：`docs/01-reviews/`（15 份并行审查 + 总合成 `v11-review-SYNTHESIS.md`）。
> **测试报告**：`docs/05-test-reports/`。

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

**实机验证**：`release-check -WithServer` **12/12**；场景 boot 6/6、surface 37/37、config 11/11、
alert 9/9、alertv2 6/6、diagnose 9/9、trend 10/10、history 7/7、hotspot 9/9、i18n 7/7、report 10/10 全绿。

---

### Changed

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
