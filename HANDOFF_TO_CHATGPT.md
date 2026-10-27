# HANDOFF_TO_CHATGPT — CNC Host 项目完整交接总览

- 生成时间：2026-10-28（由 Trae CN Agent 实际调查后编写）
- 生成位置：`E:\AI软件开发\cnc-host-git-baseline\`（独立 Git 工作副本，非原项目目录）
- 配套文档：PROJECT_HISTORY.md（时间线）、REQUIREMENTS_MASTER.md（需求总表）、PROJECT_STATUS.md（当前状态）、DECISIONS.md（技术决策）、OPEN_QUESTIONS.md（未决问题）
- 证据原则：每条结论尽量附来源（文件、提交 SHA、时间戳）；无证据的明确标注"无证据/待确认"，不编造。

---

## 1. 项目是什么、最终想实现什么

**CNC Host**：面向 X/Z 两轴数控车床的 Android 横屏工业平板上位机，C/S 架构。

- **Android 客户端**（`app/`）：现场操作端，Kiosk 模式常驻，简易 4 屏（任务→预览下发→运行监控→报警）+ 高级模式 19 屏。
- **服务端**（`server/`）：Kotlin + Ktor + SQLite，REST API + WebSocket + 网页管理后台，集中维护工艺参数、刀具、材料、图纸、任务、设备注册与权限。
- **最终目标**（依据 PRD V1.0 第 1 章）：从图纸导入到加工执行的完整、可追溯、经人工审核的自动化加工流程（DXF 解析→几何校验→CAM 刀路→仿真→审核→自动送料/夹紧/切削/退刀/松料→过程监控→记录）。
- **关键前提**：真实控制卡型号未定、无通讯协议；当前全部设备行为由模拟控制卡提供，界面明确标注模拟数据，不伪装真实连接。

## 2. 三方角色与协作方式

| 角色 | 承担者 | 职责 |
|---|---|---|
| 项目负责人 | 用户本人 | 提需求、定方向、真机验收、决策批准 |
| 项目经理 | ChatGPT | 需求整理、技术方案、规划、代码审查、进度管理 |
| 开发执行 | Trae CN Agent | 本地调查、编码、构建、测试、结果汇报 |

协作规则（2026-10-28 由用户确立，见 DEVELOPMENT_WORKFLOW.md）：三级验收口径（Agent 声称完成 / 脚本检查通过 / 用户真机验收通过）；涉及覆盖远程、冲突处理、敏感信息、重写历史必须先报告等批准。

## 3. 从项目开始到现在的时间线（摘要，详见 PROJECT_HISTORY.md）

| 时间 | 事件 | 证据 |
|---|---|---|
| 早于 2026-10-27 | PRD V1.0 成文，确立 15 章方案与九阶段计划 | `docs/产品需求规格说明书V1.0.md`；成文日期无记录 |
| 2026-10-27 10:13–18:59 | 本地密集开发（至少 12 次构建会话） | `.kotlin/errors/` 12 个错误日志时间戳 |
| 2026-10-28 01:15 | GitHub 初始提交 `b1606d1`（作者 CNC Dev） | `git log origin/main` |
| 01:35–01:40 | README 补开发上下文、加入 PRD（`9756651`→`401fca3`→`3d705e5`） | 同上 |
| 01:48 | 本地 zip 解压为 `cnc-host-main`（本地副本起点） | 全部源文件 CreationTime=01:48 |
| 01:51–02:15 | 云端 Agent 分支 4 次提交（RunHistory/预览增强+Windows 部署脚本），PR #1 开启 | `git log origin/trae/agent-2S8kzZ` |
| 02:10–02:11 | 本地全量单测 127 通过 | JUnit XML 时间戳 |
| 02:47–03:34 | 本地新增：App 内 WiFi 配置、服务器地址自动补全/搜索、APK 自动更新 | 文件修改时间（WifiSetup.kt 02:47、ServerAddress.kt 02:48、AppUpdate*.kt 03:30-03:31、file_paths.xml 03:34） |
| 04:37–04:40 | 阶段 A 盘点：PROJECT_STATUS_REPORT / FEATURE_MATRIX / OPEN_QUESTIONS | 文件时间戳 |
| 05:06 | 阶段 C/D：GIT_BASELINE_REVIEW + DEVELOPMENT_WORKFLOW | 同上 |
| 05:19–05:26 | 阶段 D 执行：完整备份 263.46MB → 工作副本 18.7MB → 基线提交 `5b02049`（05:22）→ 构建实测 | 命令输出、git log |

## 4. 已确认的产品需求（用户有书面/明确确认的）

来源一：**PRD V1.0**（15 章，项目根本依据）。
来源二：**README「近期 C/S 升级需求」**（用户原话 7 条）：Kiosk 常驻；首启网络配置；设备注册（单位/姓名/电话/服务器地址，后台可见归属）；后台按设备开关功能权限（默认全给）；客户端填服务器地址；右上角设置入口（管理员密码，可改 IP、可退出到安卓桌面）；允许在此基础上扩展完善。
来源三：**2026-10-28 用户指令**：本地代码为唯一权威来源；GitHub 仓库可公开；远程可由本地整体替换；不做新旧功能合并。

详见 REQUIREMENTS_MASTER.md（每项含来源、状态、验收证据）。

## 5. 新增、修改和取消的需求

- **新增**（开发对话提出、已实现并部分真机验证，无书面变更单）：清除数据重新注册；设备唯一注册（hardwareId 唯一，重复注册 409）；APK 自动更新（发布→弹窗→下载→SHA-256→安装）；后台修改设备信息；后台显示客户端版本号+在线状态（90s 判离线）。
- **修改**：管理后台默认密码 admin123→123456（Database.kt L217/L985-987 含旧值自动迁移）；「退出软件」从"20 秒退出窗口"改为"彻底退出不被看门狗拉起"（残留旧 `setExitWindow` 用法待清理）；Kiosk FGS 修复（MIUI 后台起前台服务崩溃死循环→仅前台启动）。
- **取消/作废**：无明确记录。待确认项见 OPEN_QUESTIONS.md Q6。
- **用户明确取消的旧方案**：Git 基线不再做"本地与远程功能合并"（2026-10-28 用户决策：远程由本地整体替换）。

## 6. 当前模块与功能状态（摘要，详见 PROJECT_STATUS.md / FEATURE_MATRIX.md）

- **有测试证据**：模拟控制卡全链路（52 用例）、DXF 解析 4 类实体（23）、CAM 刀路（15）、G-code 后处理 Fanuc/Siemens（23）、核心模型（14）。合计 127 用例，2026-10-28 两轮实测均 0 失败。
- **真机验证过**（小米 M2102K1C，前序任务）：注册/唯一注册/心跳权限下发/在线状态/后台改设备/设置入口/自动更新弹窗与下载/Kiosk 保活。
- **只有代码未充分验证**：服务端 REST 全量（无自动化测试）、离线补传、高级模式 19 屏系统性走查、APK 更新安装回读。
- **模拟数据**：全部设备侧数据（轴/主轴/IO/报警）。
- **未实现**：真实控制卡接入（device-serial/device-tcp 不存在）、暂停/继续、PDF/图片 OCR、POLYLINE/SPLINE、多用户分级、release 签名。

## 7. 重要技术决策及原因（详见 DECISIONS.md）

C/S 分离（平板只操作、服务端管配置）；Kotlin+Compose+Room / Ktor+SQLite；JDK 17 必须（Java 8 构建必失败）；模拟器先行（控制卡未定）；统一 DeviceController 接口；config_version 增量同步（服务端覆盖客户端缓存）；单管理员账号；APK 更新走 HTTP+SHA-256（**安全性待决策**，P0）；Git 基线=本地整体替换远程（已批准方向，推送待确认）。

## 8. 构建、测试和真机验证情况

- 2026-10-28 05:23 实测 `gradlew --no-daemon build`（JDK 17）：**BUILD FAILED（3m30s）**——编译与打包成功、127 测试全过，失败点为 `:app:lintDebug`（4 个 Lint 错误：WifiSetup.kt L96×2 [NewApi API29]、L136 [MissingPermission]；AndroidManifest.xml L16 [CoarseFineLocation]）。此前 02:10 的 `gradlew test`（不含 lint）为 BUILD SUCCESSFUL。
- **注意**：FEATURE_MATRIX 写"静态检查未配置"与实际不符——AGP 默认 lintDebug 在 build 生命周期内且 abortOnError=true。旧报告只跑 `test` 任务所以从未触发 lint。
- 真机：见第 6 节。无书面真机验收单，记录散落于 PROJECT_STATUS_REPORT 6.4。

## 9. 当前风险、缺陷和遗留问题（Top）

P0：APK 更新通道可被中间人劫持（明文 HTTP+同通道校验和）；SQLite 无备份；默认凭据未强制修改。
P1：真实控制卡未接入；服务端/app 零自动化测试；简易模式刀路用固定参数+默认毛坯 180×60mm 未读 Room 工艺；暂停/继续缺失；DB 迁移无回归测试；Kiosk 仅单机型验证；**lint 4 错误导致 build 红**。
其他：远程 GitHub 历史含敏感物（cnc-server.db 真实数据、源码 zip），仅轮换/重写历史/重建可根除，待用户决策。

## 10. 已完成和未完成的工作

- 已完成：PRD 阶段一至六大部 + 阶段七框架（模拟协议）、C/S 升级 7 条、后续 5 项新增、Git 基线整理（备份/工作副本/本地提交/差异报告/覆盖方案）。
- 未完成：阶段八（真实控制卡）、阶段九（可靠性/生产验收）、P0-P2 清单、GitHub main 覆盖推送（待用户确认）、远程敏感历史处置（待决策）、PR #1 处置（待决策）。

## 11. 未来已确定的目标与待确认的建议

- 已确定：以本地为唯一权威源覆盖 GitHub main；仓库保持公开；lint 修复与否由用户拍板（修复后再推 or 先推另立任务）。
- 待确认：P0 三件套实施方案；阶段八前置资料（控制卡型号/协议/IO 表）；隧道/HTTPS/云迁移路线（用户明确本次不实施）；测试样例库由谁提供真实图纸。

## 12. 需要 ChatGPT 审查的问题

1. 需求总表（REQUIREMENTS_MASTER.md）中 REQ-A/B/C 系列与 PRD 的映射是否完整、有无漏项。
2. 5 项"会话新增需求"无书面变更单，请协助补录需求原文与验收标准。
3. Lint 4 错误的修复优先级建议（阻塞 build 但功能已真机用过）。
4. APK 更新验签方案选型（服务端私钥签名 vs TLS vs 两者）。
5. PR #1（agent 分支 RunHistory/预览增强/部署脚本）在"整体替换"决策下的处置建议：关闭还是保留参考。
6. 简易模式固定工艺参数切换 Room 方案的回归验收设计（OPEN_QUESTIONS Q7）。

## 13. 需要项目负责人确认的问题

见 OPEN_QUESTIONS.md 全文。最关键 5 项：① 批准 force push 覆盖 main（含回滚方案已备好）；② 远程历史敏感数据处置方式；③ PR #1 处置；④ 默认凭据轮换执行人与时间；⑤ 控制卡资料何时可提供。

## 14. 本次交接依据的资料来源与未访问到的资料

**已查阅**：PRD V1.0 全文；README 全文；PROJECT_STATUS_REPORT.md（31KB）；FEATURE_MATRIX.md；旧 OPEN_QUESTIONS.md（22 问）；GIT_BASELINE_REVIEW.md；DEVELOPMENT_WORKFLOW.md；远程 main 与 agent 分支完整提交史；本地文件创建/修改时间；`.kotlin` 会话日志时间戳；两次 JUnit XML（02:10、05:23）；服务端 Database.kt 密码迁移代码；Lint 报告全文；客户端权限/更新/注册相关源码（阶段 A 已系统读过）。

**无法访问**（明确声明）：① Trae 历史对话记录（10-27 及更早的开发对话原文）；② ChatGPT 侧的规划/审查记录；③ 真机测试的原始截图/logcat 归档（仅有报告转述）；④ PRD 成文前的原始沟通；⑤ PR #1 的页面评论（仅知存在且开放）；⑥ 是否有其他未告知的代码副本或部署实例。
