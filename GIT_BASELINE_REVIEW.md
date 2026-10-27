# GIT_BASELINE_REVIEW — Git 基线核查与本地/远程差异报告

- 日期：2026-10-28
- 执行方式：只读核查 + 独立克隆比对。**未修改/删除/移动任何原始文件；未在原始目录执行 git init；未执行任何 commit/push/checkout/merge。**
- 原始本地项目：`E:\AI软件开发\cnc-host-main\cnc-host`（ZIP 副本，无 `.git`，保持原样未动）
- 独立克隆：`E:\AI软件开发\cnc-host-remote`（本次新建，此前确认不存在）

---

## 1. 远程仓库核验结果

| 项 | 结果 | 证据 |
|---|---|---|
| 仓库 | `https://github.com/2865916098/cnc-host`，**Public（公开）** | GitHub 页面 + `git ls-remote` |
| 网络 | Git 直连 github.com:443 超时（21s）；经系统代理 `127.0.0.1:17890`（仅命令级 `-c http.proxy`，未改全局配置）访问成功 | 实测命令输出 |
| 默认分支 | `main`，tip = `3d705e5`（docs: 加入完整产品需求规格说明书V1.0并在README引用，CNC Dev，2026-10-27/28） | `ls-remote --symref` |
| 分支列表 | `main`（4 commits）、`trae/agent-2S8kzZ`（tip `11212fc`，8 commits 中的后 4 个） | `ls-remote`、`git log --all` |
| 开放 PR | **PR #1 处于开放状态**：head = `trae/agent-2S8kzZ`（`11212fc`），内容为「RunHistory 运行历史 + 预览增强 + Windows 部署脚本」 | `refs/pull/1/head` + PR 页面 |
| 分支关系 | `main` 与 `trae/agent-2S8kzZ` 的 merge-base = **初始提交 `b1606d1`**；agent 分支基于旧 main 分出，main 后续有 3 个 docs 提交，**两边互有对方没有的提交** | `git merge-base` |
| 远程顶层结构 | 仓库根仅含 `cnc-host/` 子目录（项目在子目录内）+ 仓库根的 `cnc-host-windows.zip` | `git ls-tree` |
| 远程是否含三份盘点文档 | **否**（PROJECT_STATUS_REPORT / FEATURE_MATRIX / OPEN_QUESTIONS 均不存在） | `ls-tree` 核查 |
| 远程是否含自动更新/设备唯一注册 | **否**（无 `AppUpdate.kt`，无 `app-publish` 路由，无 `hardware_id`） | `git grep` 核查（零命中） |
| 远程是否含 Kiosk FGS 修复 | **否**（KioskService.kt 为旧版，无 `startIfForeground`） | 哈希比对 DIFFERENT |

### 远程完整提交历史（8 个）

```
11212fc feat: 继续完成现有项目                    ← trae/agent-2S8kzZ tip（PR #1 head）
106c73e chore: 添加 Windows 一键部署脚本与快速开始指南
383c7c9 feat: 继续完成现有项目
4793748 feat: 继续完成现有项目                    ← agent 分支从 main 旧点分出
3d705e5 docs: 加入完整产品需求规格说明书V1.0并在README引用  ← main tip
401fca3 docs: 补充用户原始需求到开发上下文
9756651 docs: README 增加开发上下文，便于新任务接手
b1606d1 初始提交：两轴数控车床上位机 C/S 架构…      ← 共同祖先
```

agent 分支由云端沙箱 Agent 提交（`gradle.properties` 内残留 Linux 沙箱路径 `/root/.local/share/mise/...` 与沙箱代理 `127.0.0.1:18080` 可证）。

---

## 2. 独立克隆执行结果

- 目标目录 `E:\AI软件开发\cnc-host-remote` 执行前确认**不存在**，克隆成功（exit 0），当前 checkout 为 `main`，工作区干净。
- 全程未 checkout 其他分支；比对通过 `ls-tree` blob 哈希 + `hash-object` 完成，对两个目录均为只读。

## 3. 差异统计（本地 vs 远程 agent 分支 tip `11212fc`）

比对口径：排除 `build/`、`.gradle/`、`.kotlin/`、`.idea/`、项目根 `data/`、`server/data/`（数据库）、`local.properties`。文本文件同时按原始字节与 LF 归一两种哈希比对（实际全部原始字节即匹配，无行尾问题）。比对修正过一次排除规则错误（`com.cnc.host.data` 包被误排除，已修正重跑）。

| 类别 | 数量 | 说明 |
|---|---|---|
| 两边完全一致 | **129** | 含全部 8 个算法/设备模块源码、测试、`DeviceRepository.kt`、`CamSession.kt` 等 |
| 仅本地存在 | **9** | 见 4.1 |
| 两边都有但内容不同 | **34** | 见 4.2 |
| 仅远程存在 | **18** | 见 4.3（含应清除的产物/敏感文件） |

## 4. 关键功能差异

### 4.1 仅本地存在（9 个）——本地领先远程的功能

| 文件 | 功能 |
|---|---|
| `app/.../update/AppUpdate.kt`、`AppUpdateDialog.kt`、`app/src/main/res/xml/file_paths.xml` | **APK 自动更新**（版本检查/弹窗/下载/SHA-256/调起安装器） |
| `app/.../setup/WifiSetup.kt` | App 内 WiFi 扫描连接（NetworkSetupScreen 配套，本地 +246 行重写） |
| `app/.../sync/ServerAddress.kt` | 服务器地址规范化/局域网自动搜索 |
| `docs/产品需求规格说明书V1.0.md` | PRD（main 上有，**agent 分支误删**，本地版本与 main 有少量内容差异） |
| `PROJECT_STATUS_REPORT.md`、`FEATURE_MATRIX.md`、`OPEN_QUESTIONS.md` | 本次盘点文档（2026-10-28 生成） |

### 4.2 两边都改过 main 的重叠文件（5 个）——真正需要人工合并的冲突面

| 文件 | 本地侧改动 | agent 分支侧改动 |
|---|---|---|
| `server/.../Database.kt` | +178 行：`app_versions` 表、`hardware_id` 唯一索引及迁移、设备编辑/注销 | RunHistory 服务端持久化（PR #1） |
| `server/.../Models.kt` | +48 行：AppVersion 等 DTO | RunHistory 数据结构 |
| `app/.../sync/ServerDtos.kt` | +20 行：版本/更新 DTO | RunHistory DTO |
| `README.md` | +115 行：自动更新/唯一注册/FGS 修复等文档 | agent 版为旧内容 + 部署指南引用 |
| `docs/产品需求规格说明书V1.0.md` | 内容有差异（细节未逐行核对） | agent 分支**删除**了该文件 |

双方改动主题不同（本地=注册/更新/Kiosk；远程=RunHistory/部署脚本）， textual 上大概率可合并，但必须逐文件核对。

### 4.3 其余 29 个 DIFFERENT 文件 —— 仅本地改动，远程 agent 未触碰

FGS 崩溃修复（`KioskService.kt` +38/-6、`MainActivity.kt` +42/-3、`CncApplication.kt`、`BootReceiver.kt`）、设备唯一注册与心跳版本上报（`SetupPrefs.kt` +28、`RegisterScreen/ViewModel`、`DeviceHeartbeat.kt`、`ServerApi.kt` +53）、设置面板增强（`KioskSettingsOverlay/ViewModel`）、服务端新接口（`Routes.kt` +170：app-publish/app-download/设备编辑/注销）、后台网页（`app.js` +151、`index.html`、`style.css`）、`AndroidManifest.xml` +24（更新安装权限/FileProvider）、`app/build.gradle.kts`（versionCode 3 / 1.2.0）等。**这部分可直接以本地为准。**

注意：`SimpleRunController.kt`、`SimplePreviewViewModel.kt`、`SimpleMonitorScreen/ViewModel.kt`、`CncDatabase.kt`、`Entities.kt`、`SeedData.kt`、`ConfigSyncer.kt`、`SimpleAlarmsScreen.kt` 这 9 个文件本地**未改过**（与 main 一致），差异全部来自 agent 分支的 RunHistory/预览增强——整合时应**采用 agent 分支版本**，不能错用本地旧版覆盖。

### 4.4 仅远程存在（18 个）

| 类别 | 文件 | 处置建议 |
|---|---|---|
| 有用资产 | `setup-windows.ps1`、`部署服务端.bat`、`WINDOWS-快速开始.md` | 整合时保留 |
| 应清除产物 | 仓库根 `cnc-host-windows.zip`（815KB） | 从仓库移除并加 .gitignore |
| **敏感** | `cnc-host/data/cnc-server.db`（98KB，main+agent 均在历史中）、`cnc-host/server/data/cnc-server.db`（98KB，agent） | 见第 5 节 |
| 垃圾 | `cnc-host/.kotlin/errors/` 10 个日志（185–618B） | 清除并忽略 |

## 5. 敏感信息风险（不含具体值）

| # | 位置 | 风险类型 | 建议 |
|---|---|---|---|
| 1 | 远程历史中 `cnc-host/data/cnc-server.db`、`cnc-host/server/data/cnc-server.db` | SQLite 库，按代码 schema 含设备注册信息（单位/联系人/电话）、管理员密码哈希、会话 token。**仓库为 Public** | 需用户决策：轮换凭据 + 历史清除（重写历史需批准）或删库重建 |
| 2 | 远程 `cnc-host-windows.zip`（815KB，agent 分支） | 完整项目压缩包，可能内嵌上述数据库 | 同上 |
| 3 | `server/src/main/resources/static/cnc-host-src.zip`（413KB，本地+远程 main/agent 均有） | 完整源码包（含硬编码默认凭据），经后台静态目录对外可下载 | 从静态目录移除；仓库历史清除随 #1 决策 |
| 4 | 源码硬编码默认凭据（后台默认账号密码、`CNC_ADMIN_TOKEN` 缺省值、高级模式 PIN） | 默认凭据公开可见 | 已在 OPEN_QUESTIONS Q10/Q11 跟踪；部署前强制修改 |
| 5 | agent 分支 `gradle.properties` 含沙箱 `org.gradle.java.home`（Linux 路径）与代理配置 | 非秘密，但会直接破坏本地 Windows 构建 | 整合时以本地版本为准 |
| 6 | 本地 `local.properties`（SDK 路径） | 个人配置 | 不入库（已在排除清单） |

## 6. .gitignore 核查

本地 `.gitignore` 已忽略 `build/`、`.gradle/`、`local.properties` 等，但**未覆盖**：`data/`（服务端数据库）、`.kotlin/`、`*.zip`、`server/data/`。整合方案中需补充这些规则（见第 7 节，待批准后执行）。

## 7. 基线方案评估与推荐

**情形判定：B+C 混合**——本地有远程没有的功能（自动更新、设备唯一注册、FGS 修复、WiFi 配置、盘点文档），远程 agent 分支也有本地没有的功能（RunHistory、预览增强、部署脚本）。共同祖先内容 = main tip `3d705e5`。冲突面小（5 个文件，其中 2 个是文档）。

| 方案 | 内容 | 风险 | 结论 |
|---|---|---|---|
| A. 远程为基线迁入本地功能 | 以 agent tip 为底，逐项迁入本地改动 | RunHistory 等新功能保留；本地 9 个未改文件天然采用远程版 | **推荐（改良版见下）** |
| B. 本地为准建分支整合 | 以本地为内容基线建分支推送 | 会丢失 RunHistory/预览增强/部署脚本；且无法保留远程提交历史 | 不推荐 |
| C. 模块级合并设计后再整合 | 先做合并设计文档再动手 | 冲突面仅 5 文件，过度谨慎 | 不必单独成阶段 |

**推荐方案 A 的具体形态（待批准，未执行）**：
1. 在克隆中从 `trae/agent-2S8kzZ` tip（`11212fc`）建整合分支 `integration/local-baseline`（保留远程全部历史）。
2. 恢复被误删的 `docs/产品需求规格说明书V1.0.md`（比对本地与 main 版本取正确内容）。
3. 复制本地 4.1 的新增文件 + 4.3 的 29 个本地独有改动文件。
4. 手工三方合并 4.2 的 4 个代码文件（base=main，两侧=agent/本地）；README 以本地为主补部署指南引用。
5. 补充 `.gitignore`（data/、.kotlin/、*.zip、server/data/），**不纳入**远程的 DB/zip/日志。
6. 全量构建 + `gradlew test` + `gradlew -p server installDist` 验证并记录真实结果。
7. 推送 `integration/local-baseline`（普通推送，绝不 force），PR #1 与 main 的合并由用户决定。

## 8. 后续需要用户批准/决策的操作

1. **批准第 7 节整合方案**（含文件级取舍清单）。
2. **决策远程历史中的敏感文件**（第 5 节 #1/#2/#3）：接受现状仅轮换凭据 / 批准重写历史清除（影响 PR #1）/ 删除仓库重建。此为 Public 仓库，建议尽快决定。
3. **决策 PR #1**：合并、关闭还是由整合分支取代。
4. 确认默认凭据轮换的执行人（涉及真机/后台在用的密码）。

## 9. 本次未执行的操作（如实声明）

- 未 commit、未 push、未 merge、未 checkout 任何分支、未修改原始本地目录任何文件。
- 未运行真实设备相关操作；未读取/输出任何敏感值内容。
- 单元测试本次会话已于 02:10–02:11 实跑（`gradlew test`，JDK 17，127 用例全过，JUnit XML 为证）；此后业务代码零改动（仅新增 4 个 .md 文档），结果仍有效。整合后必须重跑。
- 勘误：PROJECT_STATUS_REPORT.md L125「模拟控制卡 60 个测试」应为 **52**（6+10+20+11+5），总数 127 无误；按只读要求本次未修改该文件。
