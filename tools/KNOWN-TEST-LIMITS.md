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
| `SchedulerAdapter` (304 行) | 全部 | 零测试。**类加载时**就执行 `private static final boolean FOLIA = ServerCompat.isFolia()`（第 56 行），所以连"造个假 Plugin 试试"都做不到 |
| `FoliaSchedulerBridge` (121 行) | 全部 | 零测试 |
| `BstatsCharts` (197 行) | 全部 | 零测试。它只调 `ServerCompat.getServerName()`（一行），但那是静态字段，读取即触发 `<clinit>` |
| `ReportGeneratorTest` 的 TPS 可用分支 | 部分 | 该测试已按"两条契约里必中一条"处理，不算缺失 |

**注意一个反直觉的点**：`SchedulerAdapter` 的 import 里没有任何 `Bukkit.` 静态调用，
只依赖 `Plugin` 与 `BukkitTask` 两个接口——看起来完全可以用 `Proxy` 造假。
但它在**类加载期**通过字段初始化间接碰了 `ServerCompat`，于是整条路被封死。
"这个类不 import Bukkit"不代表"这个类能脱离 Bukkit 构造"。

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

## 这不是"可以不做"

上面每一条都在说**为什么现在做不到**，不是**为什么不重要**。
`PrometheusEndpointHttpTest` 里那 7 个 `@Disabled` 的断言内容和场景设计都是对的，
只差不了一个可用的假服务端——去掉注解就能跑。
