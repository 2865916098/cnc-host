# PROJECT_HISTORY — CNC Host 项目历史时间线与开发阶段记录

- 生成时间：2026-10-28
- 证据等级说明：【git】=远程提交记录；【文件时间】=本地文件系统时间戳；【日志】=.kotlin/JUnit 等技术日志；【文档】=项目内文档记载；【转述】=前序报告转述、无原始记录。

## 0. 史前阶段（日期无记录）

- PRD V1.0《两轴数控车床 Android 上位机自动化软件》成文：15 章，含 16 模块划分、17 页面需求、自动化状态机、九阶段计划、15 条强制开发指令。【文档：docs/产品需求规格说明书V1.0.md】
- 关键背景：控制卡型号未定、无协议；Windows 开发机；已有普通 Android 平板；必须先模拟开发。
- 选型过程、原始沟通记录：无资料，不可考。

## 1. 第一轮本地开发（2026-10-27 全天）

- 【日志】`.kotlin/errors/` 12 个错误日志，时间 10:13、13:52、14:32、14:33、15:27、16:13、16:28、16:32、16:33、16:48、16:59、18:53 —— 证明当天至少 12 次构建/同步会话，持续约 8.7 小时。
- 产出（按当前代码与 PRD 对应）：Gradle 多模块工程（app/core-model/core-ui/device-api/device-simulator/dxf-parser/cam-engine/gcode-post/server）、简易 4 屏+高级 19 屏、Room 持久化、模拟控制卡+故障注入+命令队列+自动化流程模拟、127 个单测的初版、Ktor 服务端+网页后台、Kiosk 保活、设备注册/心跳/权限。
- 【转述】当天前后完成 PRD 阶段一至六大部与阶段七框架；C/S 升级 7 条需求已在该轮落地（README 开发上下文有用户原话）。

## 2. GitHub 仓库建立（2026-10-28 01:15–01:40）

【git】origin/main 完整历史：

| 时间 | SHA | 作者 | 说明 |
|---|---|---|---|
| 01:15 | `b1606d1` | CNC Dev | 初始提交：两轴数控车床上位机 C/S 架构（客户端+服务端+Kiosk+设备注册+权限管理） |
| 01:35 | `9756651` | CNC Dev | docs: README 增加开发上下文，便于新任务接手 |
| 01:36 | `401fca3` | CNC Dev | docs: 补充用户原始需求到开发上下文 |
| 01:40 | `3d705e5` | CNC Dev | docs: 加入完整产品需求规格说明书V1.0并在README引用（main 当前 tip） |

注意：初始提交即将项目放在 `cnc-host/` 子目录下，且历史中含 `data/cnc-server.db`（真实注册数据）、`cnc-host-windows.zip`、`server/static/cnc-host-src.zip`、12 个 .kotlin 日志（仓库为 Public，敏感历史处置待决策）。

## 3. 双线并行开发（2026-10-28 01:48–03:34）

### 3a. 本地线

- 01:48【文件时间】zip 解压为 `E:\AI软件开发\cnc-host-main\cnc-host`（本地无 .git 的副本）。
- 01:52 创建 `local.properties`（Android SDK 路径）。
- 02:10–02:11【日志】全量单测 127 通过（JUnit XML），同时产生 3 个 .kotlin 错误日志（编译告警类）。
- 02:47 `WifiSetup.kt`：App 内 WiFi 扫描/连接（WifiNetworkSpecifier，不跳系统设置）。
- 02:48 `ServerAddress.kt`：服务器地址自动补全（http://+8080）与局域网自动搜索。
- 03:30–03:34 `AppUpdate.kt`/`AppUpdateDialog.kt`/`file_paths.xml`：APK 自动更新三件套。

### 3b. 云端 Agent 线（Trae 云任务，沙箱执行）

【git】origin/trae/agent-2S8kzZ（从 b1606d1 分出，作者显示 2865916098）：

| 时间 | SHA | 说明 |
|---|---|---|
| 01:51 | `4793748` | feat: 继续完成现有项目 |
| 02:07 | `383c7c9` | feat: 继续完成现有项目 |
| 02:14 | `106c73e` | chore: 添加 Windows 一键部署脚本与快速开始指南 |
| 02:15 | `11212fc` | feat: 继续完成现有项目（agent tip，PR #1 头） |

内容：RunHistory 运行历史、预览增强、部署脚本等 18 个仅远程文件；**误删了 `docs/产品需求规格说明书V1.0.md`**；gradle.properties 残留沙箱路径（/root/.local/share/mise、代理 127.0.0.1:18080）。PR #1 至今开放未合。

### 3c. 分歧事实

本地与远程 main 同源（b1606d1 快照）后双向演进：本地加了 WiFi/更新等，云端 agent 加了运行历史等。2026-10-28 用户决策：**不合并，远程由本地整体替换**。

## 4. 现状盘点阶段（2026-10-28 04:37–04:40）

【文件时间/文档】阶段 A 产出三份文档到项目根目录：
- 04:37 PROJECT_STATUS_REPORT.md（含构建/测试实测、安全 12 项、P0-P3 清单；L125 有笔误：device-simulator 小计写 60，明细表 L267 为 6+10+20+11+5=52，以 52 为准）
- 04:39 FEATURE_MATRIX.md（功能矩阵，按证据分级）
- 04:40 OPEN_QUESTIONS.md（22 问，Q1/Q2/Q11/Q12 为阻塞项）

## 5. Git 基线核查与建立（2026-10-28 05:06–05:26）

- 05:06【文件时间】GIT_BASELINE_REVIEW.md + DEVELOPMENT_WORKFLOW.md 落盘：确认本地深度 3 内无 .git；GitHub 直连 21s 超时→发现系统代理 127.0.0.1:17890 可用（仅命令级使用）；独立克隆至 `cnc-host-remote`；blob 哈希只读比对（129 一致/9 仅本地/34 不同/18 仅远程/重叠 5）。
- 05:19–05:20【命令输出】完整备份 `E:\AI软件开发\cnc-host-backup-20261028`（263.46MB，含构建产物与 data/）。
- 05:19 创建独立工作副本 `E:\AI软件开发\cnc-host-git-baseline`（18.70MB 纯源码，排除 .gradle/build/.idea/.kotlin）。
- 05:22:29【git】本地基线提交 `5b0204995d963ef51ba45920adaa3a22394a9927`（root commit，173 文件/23720 行，作者 2865916098 <2865916098@users.noreply.github.com>，仓库级身份）。暂存审计零敏感命中（db/zip/log/apk/local.properties 全部排除，com.cnc.host.data 源码包未误伤）。
- 05:23–05:26【命令输出】工作副本实测 `gradlew --no-daemon build`（JDK 17）：BUILD FAILED，失败点 `:app:lintDebug` 4 错误；此前全部编译/打包任务成功，127 测试全过（JUnit XML 05:23:30–33）。

## 6. 真机验证记录（日期无独立书面记录，均早于 04:37）

【转述】前序任务在小米平板 M2102K1C（MIUI）实测：注册流程（含 hardwareId 重复注册 409→后台注销→再注册）、心跳与权限显隐、在线状态、后台改设备信息、版本号显示、设置入口（密码校验/改地址/退出/清除数据重注册）、APK 自动更新 v1.2.0 弹窗与下载、Kiosk FGS 修复后 2 分钟 0 崩溃。原始截图/logcat 归档位置未知。

## 7. 当前所处阶段

PRD 九阶段模型下：阶段一至七（工程基础→模拟器→图纸→物料→CAM 仿真→状态机→通讯框架）大部完成（以模拟为边界）；阶段八（真实控制卡接入）未开始、被阻塞；阶段九（可靠性/生产验收）未开始。工程层面处于"Git 基线即将覆盖远程、Lint 待修复、P0 安全项待实施"的整理期。

## 8. 下一步原计划（仍然有效，按优先级）

1. 用户批准后：修复或绕过 lint → 覆盖 GitHub main（force-with-lease，回滚锚 3d705e5 已本地保留）。
2. P0：APK 验签、SQLite 备份、强制改默认凭据。
3. P1：服务端/app 测试、简易模式读 Room 工艺、暂停/继续、DB 迁移回归、Kiosk 多 ROM。
4. 阶段八前置：控制卡型号/协议/IO 表（等用户提供）。
5. 阶段七延伸（用户明确本次不实施）：HTTPS→验签→VPN 隧道→云迁移。
