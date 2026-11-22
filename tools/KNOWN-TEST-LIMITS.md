# 测试环境的已知限制

这份文件记录**当前测试体系做不到什么，为什么，以及将来怎么解**。

不是抱怨清单——每一条都有实测依据。目的是让下一个人（或下一个 Agent）不必重新踩一遍，
也避免把"测不了"当成"不用测"。

---

## 1. 无法伪造 `Bukkit.server`，因此任何触发 `ServerCompat` 的路径都测不了

### 现象

`src/main/java/cn/perfscope/compat/ServerCompat.java` 的静态块第一行是
`Bukkit.getBukkitVersion()`，直接解引用 `Bukkit.server`。在测试 JVM 里它是 `null`，
于是抛 `ExceptionInInitializerError`，且**失败后不可重试**（第二次是 `NoClassDefFoundError`）。

### 被它挡住的

| 位置 | 用例数 | 说明 |
|---|---|---|
| `PrometheusEndpointHttpTest` | 7 个 `@Disabled` | 凡是要看到 200 响应体的（Prometheus 格式合规、快照值穿透 HTTP、HEAD 无 body、Content-Type…） |
| `SchedulerAdapter` (304 行) | **部分已测** | 见下方「嵌套类可以独立加载」。三个调度方法（`runGlobalTimer` / `runAsyncTimer` / `runLater`）与 Folia fail-safe 分支仍零测试；但 `TaskHandle` 已由 `TaskHandleContractTest`（13 个用例）覆盖 |
| `FoliaSchedulerBridge` (121 行) | **部分已测** | `wrap` / `cancel` 已由 `FoliaSchedulerBridgeContractTest`（7 个用例）覆盖；另三个方法首步就调 `Bukkit.getGlobalRegionScheduler()`，仍不可测 |
| `BstatsCharts` (197 行) | **部分已测** | `bucket()` / `normalizeLanguage()` 已由 `BstatsChartsBucketTest`（9 个用例）覆盖。此条此前判为"全部零测试"，**理由是错的**——见下方更正 |
| `ReportGeneratorTest` 的 TPS 可用分支 | 部分 | 该测试已按"两条契约里必中一条"处理，不算缺失 |

**注意一个反直觉的点**：`SchedulerAdapter` 的 import 里没有任何 `Bukkit.` 静态调用，
只依赖 `Plugin` 与 `BukkitTask` 两个接口——看起来完全可以用 `Proxy` 造假。
但它在**类加载期**通过字段初始化间接碰了 `ServerCompat`，于是整条路被封死。
"这个类不 import Bukkit"不代表"这个类能脱离 Bukkit 构造"。

### 更正：嵌套类可以独立加载（2026-11-16 实测）

上面那条结论**对嵌套类不成立**。JLS 12.4.1：初始化一个类只触发其**超类与接口**的初始化，
不触发外围类。实测：

```
Class.forName("cn.perfscope.compat.SchedulerAdapter")            -> FAIL (ExceptionInInitializerError)
Class.forName("cn.perfscope.compat.SchedulerAdapter$TaskHandle") -> OK
Class.forName("cn.perfscope.compat.FoliaSchedulerBridge")        -> OK   （隐式 <clinit>）
Class.forName("cn.perfscope.compat.ServerCompat$ServerType")     -> OK
```

所以"外围类加载不了"**不等于**"里面的东西全都测不了"。`TaskHandle` 自身没有任何静态依赖，
`FoliaSchedulerBridge` 没有静态字段初始化，两者都成了可测的孤岛。

配套手段：`BukkitTask`、`Plugin`、`ScheduledTask` 都是**接口**，`Proxy.newProxyInstance` 能造
（注意 `Server` 不能，见下条）。造出来的假实现只在方法被调用时进 handler，
所以不必实现全部方法。

**这条更正的实际价值**：它把 `SchedulerAdapter` / `FoliaSchedulerBridge` 从"全部不可测"
改成"部分可测"，而且**零生产代码改动**。先榨干这些孤岛，再决定要不要为了剩下那部分动接缝。

### 更正二：`BstatsCharts` 的结论也是错的

上面第 18 行原本写「`BstatsCharts`(197 行) 全部——它只调 `ServerCompat.getServerName()`（一行），
但那是静态字段，读取即触发 `<clinit>`」。**这个理由是错的**，两个层面：

1. `ServerCompat.getServerName()` **只**在 `register()` 的 lambda 内部（第 79 行）被调用，
   `bucket()` / `normalizeLanguage()` 完全不碰它。
2. 本类的 `<clinit>` 经 `javap -c` 反汇编只有一个 `static {}`，里面是
   `bipush/newarray/iastore` 序列——**纯数据初始化，零 `invoke`**。

所以 `bucket(int,int[],String)` 与 `normalizeLanguage(String)` 是零生产改动可测的
（`BstatsChartsBucketTest`，9 个用例）。做不到的只有 `register()` 本身。

**教训可以复用**：「这个类读了 X（会触发静态初始化）」不等于「这个类整个都测不了」——
先看那个读点在哪个方法里。

### 反面：有些错误连单测也测不到，只能靠源码门禁

`peak_players_bucket` 曾复用 `ENTITY_BUCKETS`，导致 0–100 玩家合并成一档、
溢出标签写 `"50000+"`（bStats 后台读不出大服与小服）。

- 单测抓不到：桶的选择在 `register()` 的 lambda 里，要真 `Metrics` + `MetricsCollector` 才执行。
- 反射读常量也抓不到：常量本身是对的，错的是**哪个图表引用哪个常量**。
- 我试过"测试里自己声明两个数组再断言它们对不同值给不同标签"——那是**自证**，
  变异验证当场揭穿：把调用点改回 `ENTITY_BUCKETS`，测试照样全绿。

正解是 `tools/check-bstats-buckets.mjs`：一条**白名单式**源码门禁（新增 `*_bucket` 图表
不登记就报错）。这与 `check-sentinels.mjs` 是同一范式——**当行为只能在特定运行环境里显现时，
把约束上升到源码层**。



### 试过的三条路（全部失败，实测）

1. **`Proxy.newProxyInstance(Server.class, …)`**
   创建 Proxy 要解析接口的**所有方法签名**，其中某个方法的返回类型是
   `org.bukkit.generator.structure.StructureType`。加载它触发静态块，而它第一行就是
   `Bukkit.server.getRegistry(...)` —— 此时 `Bukkit.server` 还是 `null`。
   实测报错：`ExceptionInInitializerError → NPE: Cannot invoke "Server.getRegistry(...)"`

2. **`Unsafe.allocateInstance(Server.class)`**
   `Server` 是接口，抛 `InstantiationException`。

3. **反射直接写 `Bukkit.server` 字段**
   字段类型是 `Server`，仍然需要一个实例，回到 (1) 或 (2)。

   补充实测：`Class.forName("org.bukkit.Server")` **本身是安全的**（不触发
   `StructureType`）；触发点精确落在 `Proxy.newProxyInstance` 内部。

### 唯一可能的路：MockBukkit

`org.mockbukkit.mockbukkit:mockbukkit-v1.20` 应该是匹配 paper-api 1.20.4 的版本。
但本机试过的三个版本（`mockbukkit-v1.21` 的 4.33.0 / 4.45.0 / 4.116.3）**全部是
Java 21 编译的**（class major 65），而项目锁定 `release 17`（major 61），
编译直接报「class 文件版本 65.0，应为 61.0」。

要引入就得给 testCompile 单独放开到 21 —— 而 `pom.xml` 里那个 `release 17`
的注释写明它是「防止在 JDK21 上误链接 java.version(17) 以上的 API」的保护。
放开等于削弱它，等于在测试里培养主代码跑不了的习惯。

**未验证**：`mockbukkit-v1.20` 是否存在、它是否也是 Java 21 编译。
本地 Maven 仓库没有这个 artifact，当前网络受限无法拉取。
如果要推进，第一件事是确认这两点。

### 如果将来要解，按这个顺序

1. 确认 `mockbukkit-v1.20` 存在且能用 Java 17 编译（`javap -v` 看 major version）
2. 若它也是 21：评估「升级项目到 Java 21」——那会缩小服务端兼容范围
   （Paper 1.20.4 支持 Java 17），是产品决策不是技术决策
3. 若都不行：自己写一个最小 `Server` 实现。`Server` 接口方法多但绝大多数可以
   抛 `UnsupportedOperationException`；关键是**不能碰 `StructureType`**，
   而这一点在编译期无法回避（`implements Server` 就要解析所有方法签名）——
   除非用字节码生成（ASM/ByteBuddy），那又是一个新依赖

---

## 2. CPU 采集路径无法构造真实读数

`collectCpu` 依赖 `sun.management.OperatingSystemMXBean.getProcessCpuLoad()`。
在测试 JVM 里能调用，但读数是**本机真实值**（0–100 之间浮动），构造不出
"确定是 0"或"确定是 45"的场景。

因此 `MetricsCollector` 里 CPU 相关的判定（`latched()` 之外的告警分支）**没有直接测试**。

**现有替代**：`CpuHysteresisLatchTest` 直测 `latched()` 这个私有纯函数（12 个用例）；
`ThresholdColorsTest` 覆盖了"CPU 0 该判 GOOD"的着色口径。
两者合起来覆盖了判定逻辑，但没覆盖"采集出来的值怎样流进判定"这一段。

---

## 3. `Future` 超时路径（30 秒）未覆盖

`ReportGenerator.submitAndAwait` 有 30 秒超时后返回 null 的分支。
测它要等 30 秒真实时间，代价高于价值，未覆盖。

---

## 4. 写盘 IO 异常（磁盘满 / 权限位）难以可靠构造

Windows 上 `File.setWritable(false)` 对目录不生效，构造不出"目录不可写"的确定性场景。
`ReportGeneratorTest` 只覆盖了「路径被普通文件占住」这种确定形态。

---

## 5. `publish()` 的 `SENT` 之后四条路径 —— **已解，2026-11-23**

> **状态更新**：这一条此前写的"测不了"**已经不再成立**，保留原文在下方作为记录。
> 解法是给"用哪个连接发"加一个接缝，不是伪造 `Player`——

### 现在能测到哪

| 路径 | 覆盖测试 | 触发方式 |
|---|---|---|
| `SENT` | `PublisherFailureThrottleTest.successResetsTheFailureCounter` / `productionConstructorStillUsesTheServer` | 注入一个"发送成功"的载体 |
| `UNCHANGED` | `SummaryPublisherPublishTest` 的早退用例族 | `shouldSend` 返回 false |
| `FAILED`（catch 分支） | `PublisherFailureThrottleTest` 全部 6 例 | 注入一个"发送必抛"的载体 |

### 解法：`SummaryPublisher.CarrierSource`

关键发现有两条，都在 `javap` 上口头可复现：

1. **堵点是"造 Player"，不是"发"。** 所以注入 `BiConsumer<Player, byte[]>` 之类的
   "发送"接缝**解决不了问题**——载体仍然是造出来的 `Player`，测试照样死在创建那一步。
   必须把"载体从哪来"整段换掉。
2. **`Player.sendPluginMessage` 是从 `PluginMessageRecipient` 继承的**（`javap` 实测
   Player 未重新声明它），而那个接口只有两个方法。给**它**建代理不解析 Player 的继承链，
   所以造得出来。载体静态类型因此从 `Player` 放宽到 `PluginMessageRecipient`——
   任何 Player 都是 PluginMessageRecipient，这只是把类型挪到它本来的接口上。

发送动作本身仍是生产代码那行 `carrier.sendPluginMessage(plugin, CHANNEL, payload)`，
在同一个 `try` 里。所以 `FAILED` 那条是真的执行了生产的 `catch`，不是替身路径。

### 仍然做不到的

**真实 Bukkit `Player` 的语义**（"向已掉线的玩家发会怎样"）。那需要真服务端，
与"能不能跑到 `publish` 的某一行"是两件事——本条的缺口到此为止。

### 原文（2026-11-16，已被上面的解法取代）

`SummaryPublisher.publish` 的早期分支（`NO_PLAYER` / `DISABLED` / `REJECTED_ID`）已由
`SummaryPublisherPublishTest` 覆盖。**没覆盖的是 `SENT` 以及依赖它的两条计数路径**
（`THROTTLED` / `UNCHANGED`）：`lastSentSemantic` 只在 `SENT` 时被写，而 `SENT` 需要真实
`Player.sendPluginMessage`。

`Player` 在测试 JVM 里造不出来，原因与第 1 条同源（实测）：
`Proxy.newProxyInstance(Player.class, …)` 要解析它的全部方法签名，牵出
`org.bukkit.Registry` 的静态初始化，而它第一行就是 `Bukkit.server.getRegistry(...)`
→ `NullPointerException`。

**现有替代**：把"什么时候发"的判据 `shouldSend(...)` 抽成静态纯函数单独测
（`NetworkSummaryTest.mustResendAfterIntervalEvenWhenUnchanged`），
把"发出去的内容"用 `buildReport` + fixture 测（`SummaryPublisherReportTest`）。
缺口是两者之间的粘合：语义串生成、`lastSentSemantic` 的写入时机。

**补充（2026-11-23 实测）**：上方那句"牵出 `org.bukkit.Registry`"不够精确——
真实的第一触发点是 `org.bukkit.potion.PotionEffectType.<clinit>`，它调
`Bukkit.server.getRegistry(...)`。`Registry` 只是同一张静态初始化网里的另一个节点。
这不改变结论，但下次有人照着查时能少走一步：

```
org.bukkit.potion.PotionEffectType.getPotionEffectType(PotionEffectType.java:195)
org.bukkit.potion.PotionEffectType.<clinit>(PotionEffectType.java:25)
jdk.proxy2.$Proxy14.<clinit>
java.lang.reflect.Proxy.newProxyInstance(Proxy.java:1053)
```

另：`Proxy.newProxyInstance(PluginMessageRecipient.class, …)` **实测可用**，
连 `Bukkit.server` 都不碰——这就是上方的解法。

---

## 6. "首个世界名回退"无法正面证伪（`resolveServerId`）

审计里的变异"给 `resolveServerId` 加回首个世界名回退"，在测试 JVM 里**结构性不可达**：
`plugin.getServer()` 返回 `null`（`PerfScope` 是 `allocateInstance` 造出来的壳），
所以任何走服务端的路径都只能抛或降级。

实测过两种变异写法，结果相反：

| 变异写法 | 结果 |
|---|---|
| 回退写在 `try` **外**（`plugin.getServer().getWorlds()` 裸调） | **被抓**：`blankIdsNeverFallBackToWorldName` 报 NPE |
| 回退写在 `try` **内**（catch 再回退 `"perfscope"`） | **SURVIVED**：与"取不到端口"那次降级不可区分 |

所以 `SummaryPublisherReportTest.blankIdsNeverFallBackToWorldName` 断言的是**契约下界**
（降级值必须精确等于 `"perfscope"`，且不得等于任何常见世界名），不是"世界名回退不可能发生"。
正面证明需要一个可用的假 `Server`——回到第 1 条。

---

## 7. `NetworkCommand.expectedServers()` 的降级范围过大（**已知缺陷，未修**）

代理侧 `expectedServers()` 整个循环包在同一个 `try/catch(Exception)` 里：

```java
try {
    for (var registered : plugin.server().getAllServers()) {
        String name = registered.getServerInfo().getName();   // 一台抛 NPE …
        ...
    }
} catch (Exception e) {
    return Collections.emptyList();                            // … 整张列表一起没了
}
```

后果：任何**一台**第三方插件的 `RegisteredServer` 注册信息不完整（`getServerInfo()` 返回
`null`），都会让**全部**后端从面板上消失。症状是"面板上所有后端都显示无数据"，
与"代理没装插件"一模一样，而真实原因只是其中一台。

**为什么没修**：这一批的约束是"只补测试、不碰生产判定逻辑"。
`NetworkCommandTest.nullServerInfoDegradesWholeList_DocumentedCurrentBehavior` 固定的是
**现状**，好让将来修它（把 `try` 移进循环内部）时立刻看到行为变了。

---

## 这不是"可以不做"

上面每一条都在说**为什么现在做不到**，不是**为什么不重要**。
`PrometheusEndpointHttpTest` 里那 7 个 `@Disabled` 的断言内容和场景设计都是对的，
只差不了一个可用的假服务端——去掉注解就能跑。
