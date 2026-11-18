[English](CONTRIBUTING-EN.md) | 简体中文

# 贡献指南

## 1. 先说清楚这是什么项目

这是一个**个人项目**：高一牲用 AI 辅助（vibe coding）写出来、在真机上一点点磨的
Minecraft 服务器性能监测插件（Paper 系，Java 17，AGPL-3.0）。

先说三件实话，免得你白等：

- **不承诺响应时效**。上课、考试、被 bug 卡住都可能导致几周不看 Issue。
- **不承诺"这个功能我要做"**。这里的方向由作者自己定，Issue 里说得很好的点子也可能不做。
- **但 PR 会被认真读**。带测试、说清动机的 PR，通过率比"只改一行"高得多。

如果你能接受这三点，往下看。

## 2. 报告问题

**先搜一遍 [Issues](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/issues)（含已关闭的）**——
"TPS 一直显示 20"这类问题很可能已经有人问过。

报 bug 请带上这五项，缺一项都可能让排查多绕几轮：

1. **服务端版本与构建号**（如 `Paper 1.20.4 build 343`、`Purpur 1.21.1 build 2200`）
2. **PerfScope 版本**（`/perfscope status` 第一行，或 jar 文件名；形如 `1.4.5-SNAPSHOT-fix1`）
3. **复现步骤**——哪条命令、按什么顺序、多久出现
4. **相关日志**（`logs/latest.log` 里含 `PerfScope` 的行；有异常请贴完整堆栈）
5. **配置文件**（`plugins/PerfScope/config.yml`；**贴之前删掉 webhook URL 里的 token**）

如果是指标数值不对，请一并说明「你以为是多少、实际显示多少」——这个插件的大部分 bug
都是「把健康说成毛病 / 把毛病说成健康」这一类的判定错误，对照值比日志更有用。

## 3. 提交 Pull Request

1. Fork 本仓库，从 `main` 新建分支（如 `fix/tps-threshold`），改动聚焦——一个 PR 只做一件事。
2. 提交前确保本地构建通过（见下）。
3. 向 `main` 提交 PR，按 PR 模板填写描述与测试说明。
4. CI（`.github/workflows/build.yml`）会在 PR 上自动构建并检查产物门禁；CI 不通过不会被合并。

## 4. 构建要求

需要 **JDK 17** 与 **Maven**（本项目无 Maven Wrapper，请使用已安装的 `mvn`）。在仓库根目录（`pom.xml` 所在处）执行：

```bash
mvn clean package              # 完整构建，产物在 target/
mvn clean package -DskipTests  # 跳过测试的快速构建
mvn test                       # 仅运行测试
```

- 目标平台为 **Paper 1.20.4+**（Java 17），不支持纯 Spigot / CraftBukkit；改动须与此事实一致。
- 修改代码后必须保证 `mvn clean package` 编译通过。
- 新增/修改命令、功能或权限时，请同步更新 `README.md` 与 `README-EN.md`，并在 `CHANGELOG.md` 的当前版本段落下追加一行变更记录。

### 提交前的自检（重要）

仓库里有几道门禁脚本，CI 与本地都可跑，**改完请至少跑一遍**：

```bash
node tools/check-i18n.mjs --repo . --strict     # 消息键与占位符一致性
node tools/check-docs.mjs                       # 产品文案不得出现作者口癖
node tools/check-sentinels.mjs                  # 哨兵字段不得被拿 0 当判据
node tools/check-bstats-buckets.mjs             # 新增 bStats 图表必须登记
node tools/check-reload-rebuild.mjs             # reload() 在配置加载失败时仍须重建任务
```

**加测试比改代码更重要。** 这个项目的每一条修复都要求「有一条断言能在修复前变红」，
否则修了等于没修。只要你能证明测试真的能抓住那个 bug，PR 基本就过了。

## 5. CLA（贡献者许可协议）

**首次提交 PR 之前，请先阅读 [CLA.md](CLA.md)。** 你保留自己贡献的版权；同时授予维护者一项永久、全球、非独占、免版税、不可撤销的许可，其中包括**再许可权**（可将你的贡献以任何许可证再许可，含闭源/商业许可证）。

- **首次贡献者**：请在 PR 描述（或在 PR 模板的 checkbox 处）写明下面任一语句，即视为签署 CLA：

  ```
  我已阅读并同意 CLA（CLA.md）
  I have read and agree to the CLA (CLA.md)
  ```

- 维护者会在合并你的首个 PR 时记录你的 **GitHub 用户名与同意日期**；此后的后续贡献自动沿用，无需重复声明。
- 若你不代表自己（例如代表雇主）贡献，请确保已获得相应授权。

## 6. 行为期望

- 友善、就事论事；中文或英文交流均可。
- 提功能建议前，可先看看 README 的「边界与不做什么」——有些事本插件有意不做（如根因定位请用 spark）。
- 维护者不承诺响应时限，也不保证合并任何 PR。
