# CNC Host 项目现状盘点与开发基线报告

- **报告日期**：2026-10-28
- **项目阶段**：开发中，未发布 1.0
- **盘点方式**：本地源代码、构建脚本、数据库结构、需求文档、真实构建/测试命令执行结果、前序真机测试记录
- **重要说明**：本项目目录**不是 Git 仓库**（无 `.git`，为 zip 下载副本），因此 Git 分支、commit、工作区状态、变更历史均无法从本地核实，相关章节已标注"资料缺失"。

---

## 一、项目基础信息

| 项 | 内容 | 证据 |
|---|---|---|
| 项目名称 | CNC Host — 两轴数控车床上位机（客户端 + 服务端） | `README.md` L1 |
| 当前目录 | `e:\AI软件开发\cnc-host-main\cnc-host` | 本地磁盘 |
| Git 仓库地址 | **资料缺失，待产品负责人确认**。README 未记录仓库 URL；本地无 `.git` | 本地核查 |
| 当前分支 / commit | **无法确认**（非 Git 工作区） | 本地核查 |
| 工作区是否干净 | **无法确认**（无 Git 基线可比对） | 本地核查 |
| 远程仓库可达性 | `git ls-remote` GitHub 直连 21 秒超时失败（本机网络限制），远程状态**待验证** | 实测命令 |
| 客户端当前版本 | versionCode 3 / versionName "1.2.0"（debug 签名） | `app/build.gradle.kts` L16-17 |
| 服务端版本 | build 脚本标注 1.0.0 | `server/build.gradle.kts` L8 |

### 技术栈与版本

| 端 | 技术 | 版本 | 证据 |
|---|---|---|---|
| 客户端 | Kotlin + Jetpack Compose + Room + Navigation Compose | Kotlin 2.0.21，AGP 8.7.2，Gradle 8.14.6，Compose BOM 2024.09.03，Room 2.6.1，minSdk 24 / targetSdk 34 / compileSdk 34 | `app/build.gradle.kts`、`settings.gradle.kts`、README L10-17 |
| 服务端 | Kotlin JVM + Ktor + SQLite(JDBC) + kotlinx.serialization | Ktor 2.3.12，sqlite-jdbc 3.46.1.3，kotlinx.serialization 1.7.3，logback 1.5.6，jvmToolchain 17 | `server/build.gradle.kts` |
| 客户端持久化 | Room（10 张表）+ SharedPreferences（注册信息/设置/待补传队列） | — | `app/.../persistence/CncDatabase.kt`、`setup/SetupPrefs.kt`、`sync/ServerPrefs.kt` |
| 服务端持久化 | SQLite（JDBC 直连，无 ORM） | 默认 `data/cnc-server.db` | `server/.../Database.kt`、`Main.kt` |
| 客户端→服务端通信 | **仅 REST（HttpURLConnection），无 OkHttp/Retrofit，客户端无 WebSocket** | — | `app/.../sync/ServerApi.kt` L8/L15 |
| WebSocket | 仅服务端→网页管理后台（`/ws/status?token=`） | — | `server/.../Routes.kt`、`static/app.js` L878 |
| 网页后台 | 原生 HTML/JS/CSS 静态页（`server/src/main/resources/static/`） | — | `index.html`、`app.js`(约 47KB)、`style.css` |

### 模块结构

根构建（`settings.gradle.kts`）含 8 个模块，`server` 为**独立 Gradle 工程**（需 `-p server` 单独构建）：

| 模块 | 作用 | 证据 |
|---|---|---|
| `app` | Android 客户端：简易 4 屏 + 高级 19 屏、Room 缓存、服务端同步、Kiosk 保活、自动更新 | `app/` |
| `core-model` | 统一数据模型（零件、图纸、刀具、工艺、刀路、报警等） | `core-model/` |
| `core-ui` | 工业 HMI 设计系统（颜色/字体/组件） | `core-ui/` |
| `device-api` | 统一设备接口 `DeviceController` | `device-api/` |
| `device-simulator` | 模拟控制卡（运动、主轴、IO、报警、故障注入） | `device-simulator/` |
| `dxf-parser` | DXF 文本解析 → 几何模型 | `dxf-parser/` |
| `cam-engine` | 轮廓提取、车削刀路生成、参数校验 | `cam-engine/` |
| `gcode-post` | Fanuc / Siemens G-code 后处理 | `gcode-post/` |
| `server` | REST + WebSocket + 网页管理后台 + SQLite | `server/` |

规格书中的 `device-serial`（USB 串口）、`device-tcp`、`recognition`（OCR）、`automation`（独立模块）、`task-engine`、`logging` 模块**未单独建立**，相关能力部分并入 app/模拟器，部分未实现（见 FEATURE_MATRIX）。

### 构建 / 运行 / 测试方法（已实测）

```powershell
# 环境前提（实测踩坑）：系统默认 Java 8 会导致构建失败，必须先指定 JDK 17
$env:JAVA_HOME="C:\Users\28659\.local\share\java\jdk-17"
# local.properties 需含 sdk.dir=<AndroidSDK 路径>

# 客户端 APK
.\gradlew.bat :app:assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk

# 全部单元测试（2026-10-28 实测：BUILD SUCCESSFUL，127 个测试 0 失败）
.\gradlew.bat test

# 服务端（独立工程）
.\gradlew.bat -p server installDist
# 启动（需 JDK 17；$env:CNC_ADMIN_TOKEN 可覆盖默认管理口令）
server\build\install\cnc-server\bin\cnc-server.bat
```

### 已知环境要求

- JDK 17（**实测**：用系统 Java 8 执行 `gradlew test` 报 `Dependency requires at least JVM runtime version 11` 并 BUILD FAILED）
- Android SDK Platform 34 + `local.properties` 配置 `sdk.dir`
- Gradle 依赖仓库走阿里云镜像（`settings.gradle.kts`），离线/无外网环境首次构建会失败
- 真机测试设备：小米平板（M2102K1C，MIUI），adb 设备号 `5cebe9a0`（前序任务实测）

---

## 二、最初需求和产品目标

### 2.1 有明确依据的原始需求

**《两轴数控车床 Android 上位机自动化软件 完整产品需求规格书 V1.0》**（`docs/产品需求规格说明书V1.0.md`，15 章），核心：

- 定位：面向真实生产的 X/Z 两轴数控车床智能上位机（图纸导入→解析→CAM→仿真→审核→加工→记录全流程），不是监控 APP
- 明确前提：**控制卡型号未定、无通讯协议**，必须先做模拟开发；真实设备接入后须协议验证+安全验收
- 成功标准含：可导入解析 DXF、可生成车削刀路、模拟环境下完整加工流程、模拟模式明确标注不得伪装真实连接
- 16 个模块划分、17 个页面详细需求、自动化状态机（送料→夹紧→切削→退刀→松料，反馈确认禁止固定延时）、九阶段开发计划、15 条强制开发指令

**README「近期 C/S 升级需求」章节**（README L201-210，有文字依据的后续新增）：Kiosk 模式、首次启动网络配置、设备注册（单位/姓名/电话/服务器地址）、后台按设备配置功能权限、设置入口（管理员密码）、可退出到安卓桌面。

### 2.2 后续新增的需求（有会话记录，无独立文档）

最近一轮开发新增（本次盘点前已完成并部分真机验证）：

1. 客户端设置中「清除数据重新注册」（清本机注册信息并重启回注册流程）
2. 设备唯一注册（hardwareId 一台设备只能注册一次，重复注册提示联系管理员，须后台注销后才能再注册）
3. APK 自动更新（后台发布新版本 → 客户端弹窗 → 下载 → SHA-256 校验 → 调起系统安装器）
4. 后台可修改已注册设备信息
5. 后台设备表显示客户端版本号与实时在线状态（90 秒无心跳判离线）

依据：服务端 `Routes.kt`（`/app-publish`、`/app-version`、`/app-download`、设备编辑/注销接口）、`Database.kt`（`devices.hardware_id` 唯一索引、`app_versions` 表）、客户端 `update/AppUpdate.kt`、`ui/setup/` 相关代码。**注意：以上需求来源是开发对话，无书面变更单，需求原文细节待产品负责人确认。**

### 2.3 后续修改或替换的需求

- 管理后台默认密码由 `admin123` 迁移为 `123456`（`Database.kt` L217 注释及 L986-987 常量）
- 「退出软件」语义从"20 秒退出窗口"演变为"彻底退出、不再被看门狗拉起"（README L178 vs 旧 `setExitWindow` 残留用法，存在新旧两套语义混用痕迹）
- Kiosk 保活策略本轮修复：后台场景（开机广播、崩溃重启）不再直接启动前台服务，改为仅前台启动（修复 MIUI 上 FGS 超时崩溃死循环）

### 2.4 无法确认的需求（资料缺失，待产品负责人确认）

- 规格书 V1.0 之前的原始沟通记录、选型过程
- 各次需求变更的提出时间、原因、验收人（无 Git 历史、无变更单）
- 真实控制卡的品牌/型号/协议（规格书明确"尚未确定"，至今未接入）

---

## 三、当前功能实现情况（摘要）

逐项明细见 [FEATURE_MATRIX.md](FEATURE_MATRIX.md)。总体结论：

**已实现且有测试证据**（单元测试 127 个全部通过，2026-10-28 实测）：
- 模拟控制卡（运动/主轴/IO/报警/故障注入/命令队列/自动化流程模拟）——60 个测试
- DXF 解析（LINE/CIRCLE/ARC/LWPOLYLINE）——23 个测试
- CAM 粗车/精车刀路生成——15 个测试
- G-code 后处理（Fanuc/Siemens）——23 个测试
- 核心数据模型与几何变换——14 个测试

**已实现并经过真机验证**（前序任务实测，有截图/logcat/接口验证记录）：
- 首次启动引导（网络配置→设备注册）、设备唯一注册（重复注册 409 拒绝→后台注销→再注册成功）
- 心跳与功能权限下发（30s 心跳，权限显隐）、设备在线状态（90s 判定）
- 后台设备信息修改、版本号显示
- APK 自动更新链路（发布 v1.2.0 → 手机弹窗 → 下载 → 安装）
- 设置入口（管理员密码校验、修改服务器地址、退出软件、清除数据重新注册）
- Kiosk 保活（开机自启、看门狗拉起、崩溃自动重启；本轮修复 MIUI 后台起 FGS 崩溃死循环后 2 分钟 0 崩溃）

**已实现但尚未充分验证**：
- 服务端 REST 全量接口（无服务端自动化测试，仅经 curl/网页后台手工验证）
- 离线缓存与增量同步（config_version 机制）、任务记录离线补传队列（上限 100 条，溢出丢最旧）
- 高级模式 19 个页面（文件齐全、编译通过，但多数页面未做系统性真机走查）

**仍使用模拟数据**：
- **所有设备侧数据**：轴位置、主轴、IO、报警均来自 `SimulatedDeviceController`（`app/.../data/DeviceRepository.kt` L16-21 默认实例，`isSimulated=true`）。界面有"模拟模式"标识（`HomeScreen.kt` 等 18 处引用 `isSimulated`）

**尚未实现**（README「未实现/已知限制」+ 代码核实）：
- 真实控制卡接入（无 device-serial / device-tcp 模块）
- 暂停/继续（仅受控停止与急停）
- 矢量 PDF / 图片 OCR 图纸导入
- 简易模式刀路使用固定工艺参数与默认毛坯（180×60mm），未读取已同步到 Room 的工艺方案/毛坯表
- 多用户分级权限（单管理员账号）

---

## 四、项目架构与数据流

### 4.1 Android 客户端架构

- 单 Activity（`MainActivity`）+ Navigation Compose，MVVM（每页 Screen + ViewModel）
- 简易模式（底部 4 页签，按权限过滤）与高级模式（PIN 进入，左侧 19 项导航）双 UI 层
- 数据层：Room（10 表：Part/Blank/Tool/Material/ProcessPlan/ProcessStep/Drawing/DrawingCorrection/TaskHistory/SyncMeta）+ SharedPreferences（`SetupPrefs` 注册信息、`ServerPrefs` 同步设置与待补传队列）
- 设备访问唯一入口：`DeviceRepository.shared`（全应用共享同一模拟设备实例，避免各页持有互不相关的模拟设备）
- 模块依赖：app → core-model / core-ui / device-api / device-simulator / dxf-parser / cam-engine / gcode-post

### 4.2 服务端架构

- Ktor Netty，单进程 + SQLite（JDBC 直连，无 ORM）
- 认证：`bearer("admin")`，同时接受环境变量引导令牌与数据库会话 token（登录颁发，7 天有效）
- 开放接口（免鉴权）：`/api/v1/health`、`/admin/`、设备注册/查询/心跳/密码校验、App 版本查询与 APK 下载
- 鉴权接口：配置读写、工具/材料/零件/毛坯/工艺/图纸/任务、设备列表与权限、管理员密码、APK 发布
- WebSocket `/ws/status?token=` 仅向网页后台推送设备状态汇总
- 每次配置写操作递增 `config_version`，客户端据此做增量同步

### 4.3 数据库主要表（服务端 SQLite）

`meta`（配置版本/管理员密码哈希）、`device_config`、`tools`、`materials`、`parts`、`blanks`、`process_plans`、`process_steps`、`drawings`（含 DXF 原文）、`drawing_corrections`、`task_history`、`devices`（含 hardware_id 唯一索引、权限 JSON、app_version、last_seen/online）、`app_versions`（APK 版本、SHA-256、强制更新标记）、`admin_sessions`（会话 token）。

客户端 Room 表与服务端配置表一一对应，另加 `sync_meta`（本地 config_version）与 `task_history` 本地副本。

### 4.4 数据流

```
网页后台/管理端 ──REST──► 服务端(Ktor) ──读写──► SQLite
                              ▲   │ WebSocket /ws/status（状态推送）
                              │   ▼
Android 客户端 ──REST（注册/心跳30s/配置同步/任务补传/App更新检查）──┘
      │
      ▼（本地）
DeviceRepository.shared ──► SimulatedDeviceController（模拟运动/IO/报警）
      │
      ▼
DXF 解析(客户端或服务端) → CAM 刀路 → G-code 生成 → 模拟设备执行
```

### 4.5 DXF→CAM→G-code→执行调用关系

- DXF：`dxf-parser` 输出几何模型；图纸可由后台上传存入服务端 `drawings` 表并同步到客户端 Room
- CAM：`cam-engine` 按工艺步骤生成粗车/精车刀路（简易模式目前用固定参数，未读 Room 工艺方案）
- G-code：`gcode-post` Fanuc/Siemens 后处理
- 执行：刀路/命令进入 `SimulatedDeviceController` 命令队列（终态判断，不用固定延时）——**未连接真实控制卡**

### 4.6 数据来源分类

- **实际采集**：无（无真实设备）
- **客户端上报**：设备注册信息、心跳（含 App 版本号）、任务记录、设备状态（源自模拟器）
- **模拟生成**：全部轴位置/主轴/IO/报警/加工进度
- **计算生成**：刀路、G-code、配置版本号

### 4.7 离线缓存与异常恢复

- 配置同步：服务端 `config_version` 单调递增，客户端仅在版本变化时覆盖 Room 缓存；服务端离线时客户端继续使用本地缓存
- 任务补传：失败写入 SharedPreferences JSON 队列，上限 100 条，超出丢最旧（README L175）
- Kiosk 异常恢复：看门狗 700ms 检测主界面是否前台，崩溃后 AlarmManager 1.5s 自动重启主界面；管理员「退出软件」置彻底退出标记后不再拉起；自动更新安装期间暂停看门狗避免顶掉系统安装器

### 4.8 已知架构风险

- **单点**：服务端单进程 + SQLite 文件，无备份机制；数据库损坏即全部配置丢失
- **耦合**：服务端 `Database.kt` 超千行（建表+迁移+全部 DAO 集中一个 object）；客户端简易模式刀路参数硬编码
- **重复逻辑**：客户端与服务端各自维护一套配置数据模型（`core-model` 与 `server/Models.kt`），靠 DTO 转换保持一致，字段漂移风险
- **安全**：全链路明文 HTTP（`usesCleartextTraffic="true"`），注册/心跳免鉴权（见第八节）

---

## 五、历史开发和变更记录

**本地无 `.git`，无法提供分支、commit、变更历史。以下为间接证据整理，变更原因均"待确认"：**

| 线索 | 证据 | 说明 |
|---|---|---|
| 规格书 V1.0 | `docs/产品需求规格说明书V1.0.md` | 项目最初蓝图，九阶段计划 |
| 九阶段中一至七阶段大部完成 | 模块与测试存在 | 阶段八（真实控制卡接入）未开始 |
| C/S 升级（注册/权限/设置/后台） | README「近期 C/S 升级需求」+ 服务端 devices 相关代码 | 原始规格书无此内容，属后续新增 |
| 管理员默认密码 admin123 → 123456 | `Database.kt` L217 迁移注释 | 旧库自动迁移 |
| devices 表增加 hardware_id 唯一约束 | `Database.kt` ALTER TABLE + 部分唯一索引 | 设备唯一注册需求（本轮新增） |
| App 更新链路（app_versions 表、/app-publish 等接口、客户端 AppUpdate） | 相关代码 | 本轮新增 |
| versionCode 1→3（0.1.0→1.1.0→1.2.0） | `app/build.gradle.kts` | 为测试自动更新发布 |
| Kiosk FGS 崩溃修复 | `KioskService.kt`、`CncApplication.kt`、`MainActivity.kt`、`BootReceiver.kt` 注释 | 修复 MIUI 后台起前台服务崩溃死循环 |
| 退出语义变更（退出窗口 → 彻底退出） | `SetupPrefs` 残留 `setExitWindow` 与新语义并存 | 原因待确认 |

**可能引入兼容性或回归风险的变更**：
- `devices` 表迁移依赖 ALTER 顺序（曾因唯一索引建在加列之前导致旧库启动崩溃 `no such column: hardware_id`，已修复——`Database.kt` 迁移块顺序）。**旧库升级路径未做自动化回归测试**
- Kiosk 启动逻辑刚改动（前台才允许起 FGS），其他品牌 ROM 未验证
- 简易模式刀路固定参数：后续接入 Room 工艺方案时行为会变化，需回归

---

## 六、测试和运行现状（2026-10-28 实测）

### 6.1 实际执行的命令与真实结果

| 命令 | 结果 | 关键输出 |
|---|---|---|
| `.\gradlew.bat test`（系统默认 Java 8） | **失败** | `Dependency requires at least JVM runtime version 11. This build uses a Java 8 JVM.` |
| `$env:JAVA_HOME=<JDK17>; .\gradlew.bat test` | **BUILD SUCCESSFUL（1m19s）** | 127 个测试，0 失败 0 错误 0 跳过 |
| `.\gradlew.bat :app:assembleDebug`（前序任务） | 成功 | 产出 debug APK（约 18MB） |
| `.\gradlew.bat -p server installDist`（前序任务） | 成功 | 产出可执行发行包 |
| 服务端启动 + curl/网页后台手工验证（前序任务） | 通过 | 注册/心跳/权限/设备编辑/发布版本接口实测 |
| `git ls-remote` GitHub | **失败（21s 超时）** | 本机网络无法直连 GitHub |

### 6.2 单元测试明细（127 个，全部通过）

| 模块 | 测试类 | 用例数 |
|---|---|---|
| cam-engine | CamEngineTest | 15 |
| core-model | DataModelTest / GeometryTransformTest | 5 / 9 |
| device-simulator | AutomationFlowSimulator / EnhancedSimulator / SimulatedCommandQueue / SimulatedDeviceController / SimulationLogger | 6 / 10 / 20 / 11 / 5 |
| dxf-parser | DxfParserTest | 23 |
| gcode-post | PostProcessorTest | 23 |

### 6.3 测试空白

- **`app` 模块无任何单元测试**（`testDebugUnitTest NO-SOURCE`）
- **`server` 模块无任何测试**（build 脚本配置了 testImplementation 但无测试源文件）
- 无 androidTest（无仪器化/UI 测试）
- 无静态检查配置（未见 lint/detekt/ktlint 任务配置）
- 规格书要求的图纸测试样例库、CAM 边界用例（超行程/毛坯不足等部分有覆盖）、自动化异常用例、长时间运行测试均未成体系

### 6.4 真机测试覆盖（前序任务实测）

已覆盖：注册（含重复注册拒绝、注销后再注册）、心跳与在线状态、权限下发显隐、后台改设备信息、设置密码校验、退出/清除数据重注册、APK 自动更新弹窗与下载、Kiosk 崩溃恢复（修复后 2 分钟 0 崩溃）。
未覆盖：高级模式 19 屏系统性走查、离线补传、长时间运行、其他品牌平板。

### 6.5 真实设备

**未连接任何真实数控设备。** 当前全部设备行为为模拟。按规格书要求，真实设备接入需先完成协议验证、只读通讯、IO 验证、空载运动、受监督试切五个阶段。

### 6.6 与正式使用环境的差距

无 release 签名与混淆、默认凭据未改、无 TLS、无数据备份、无真实设备验证、无多机型兼容性测试、服务端无进程守护（崩溃即停）。

---

## 七、安全和敏感信息检查

> 按要求只记录风险位置与类型，不复制的真实凭据（本项目当前全部为代码内默认占位值，未发现真实生产凭据）。

| # | 风险 | 位置 | 级别 | 建议 |
|---|---|---|---|---|
| 1 | 管理后台/客户端设置默认账号密码硬编码并在登录页明文展示 | `server/.../Database.kt` L986-987；`server/.../static/index.html` L17；`static/app.js` L747；`app/.../setup/SetupPrefs.kt` L31；README L252 | 高 | 部署强制首次改密；移除登录页默认密码提示；改密后写入仅哈希 |
| 2 | 服务端引导令牌默认值硬编码 | `server/.../Main.kt` L16（`CNC_ADMIN_TOKEN` 缺省回退）；README L253 | 高 | 未设置环境变量时拒绝启动或生成随机令牌打印一次 |
| 3 | 高级模式 PIN 默认值硬编码 | `app/.../sync/ServerPrefs.kt` L17 等 | 低（README 声明仅防误触，非安全边界） | 保持文档说明即可 |
| 4 | 全链路明文 HTTP，客户端 `usesCleartextTraffic="true"` | `app/src/main/AndroidManifest.xml` L27 | 高（局域网场景中等） | 限制在受信局域网；中期上 TLS 或至少对 APK 下载做签名校验 |
| 5 | **APK 自动更新走明文 HTTP，SHA-256 校验和与 APK 同一通道下发**——中间人可同时替换 APK 和校验和 | `server/.../Routes.kt`（/app-publish、/app-download）；`app/.../update/AppUpdate.kt` | 高 | 对 APK 做服务端私钥签名、客户端公钥验签；或固定证书校验 |
| 6 | 设备注册/心跳/密码校验接口免鉴权 | `server/.../Routes.kt` 开放区 | 中 | 局域网内任何主机可注册设备、伪造心跳、在线爆破管理员密码（无频率限制） |
| 7 | 管理员密码离线缓存在客户端 SharedPreferences 明文可比对 | `app/.../setup/SetupPrefs.kt` | 中 | 缓存哈希而非口令本体 |
| 8 | 客户端 `allowBackup="true"`，注册信息/缓存密码可经 adb 备份导出 | `AndroidManifest.xml` L22 | 中 | Kiosk 设备建议 `allowBackup="false"` |
| 9 | 无 release 签名与混淆，debug APK 直接下发更新 | `app/build.gradle.kts` | 中 | 发布前配置 release 签名；检查更新时校验签名一致性 |
| 10 | 网页后台 token 经 URL query 传递（`/ws/status?token=`），会进入访问日志 | `static/app.js` L878 | 低 | 改为子协议或首帧鉴权 |
| 11 | 服务端静态目录混入源码包 `cnc-host-src.zip`（413KB） | `server/src/main/resources/static/cnc-host-src.zip` | 低 | 确认是否有意对外提供下载；含全部源码（含上述默认凭据），建议移除 |
| 12 | 未发现会导致真实设备危险动作的逻辑 | 全项目 | — | 真实设备未接入；接入前须按规格书第五章/七章安全条款验收 |

**敏感信息结论**：未发现真实用户凭据、个人信息或支付凭据。客户端注册收集的单位/联系人/电话属于业务需要，存于服务端 SQLite 与网页后台可见，应注意访问控制。

---

## 八、未完成事项与开发建议（按优先级）

### P0（可能导致设备危险动作 / 重大数据安全 / 严重数据丢失）

1. **自动更新通道可被中间人劫持植入恶意 APK**
   - 证据：明文 HTTP + 校验和同通道（七-5）
   - 影响：批量工业平板被安装恶意软件
   - 建议：APK 签名验签或 TLS；验收标准：篡改的 APK/校验和被客户端拒绝
   - 影响面：自动更新功能
2. **服务端 SQLite 无备份机制**
   - 证据：`Database.kt` 无备份逻辑；`data/cnc-server.db` 单文件
   - 影响：库文件损坏即丢失全部配置/设备/历史
   - 建议：每日定时复制快照 + 启动时完整性检查
3. **默认凭据在部署环境未强制修改**
   - 证据：七-1/2
   - 建议：首次启动强制改密流程；`CNC_ADMIN_TOKEN` 未设置时生成随机值

### P1（影响核心功能正确性/可靠性/关键流程）

1. **真实控制卡未接入**（阶段八未开始；前置：型号、协议文档、IO 表）
2. **服务端与 app 模块零自动化测试**：每次改动只能手工回归；建议补 Ktor 路由测试与同步逻辑测试
3. **简易模式刀路用固定工艺参数与默认毛坯**，未读 Room 已同步的工艺方案/毛坯（README L172 自报）；影响：现场加工参数与后台配置不一致
4. **暂停/继续未实现**（规格书 7.3 要求；当前仅受控停止+急停）
5. **数据库迁移无回归测试**（旧库→新结构已出过一次启动崩溃）
6. **Kiosk 崩溃修复仅验证小米一台设备**，其他 ROM 待验证

### P2（重要功能缺失/体验问题/技术债）

1. 矢量 PDF / 图片 OCR 图纸导入未实现（规格书 4.2/5.2/5.3）
2. DXF 的 POLYLINE、SPLINE 实体未解析（标记 UnsupportedEntity，规格书要求能力验证后支持）
3. 图纸删除引用检查、刀路版本失效联动（规格书 9.2）未系统化
4. 多用户分级权限未实现（当前单管理员）
5. 服务端无守护/开机自启部署方案；无日志轮转
6. `SetupPrefs` 退出语义新旧混用（`setExitWindow` 残留）
7. 网页后台静态目录夹带源码 zip（七-11）

### P3（优化建议）

1. 客户端网络层 HttpURLConnection 可考虑替换为 OkHttp（超时/拦截器/重试更可控）
2. `server/Database.kt` 拆分 DAO
3. 客户端/服务端数据模型统一由 `core-model` 生成 DTO，减少漂移
4. 加 detekt/ktlint 静态检查
5. 补安装与操作手册、设备接入指南（规格书第十四章交付物缺口）

---

## 九、项目当前基线（结论）

1. **已确认可用**（有测试或真机证据）：模拟控制卡全链路、DXF 解析（4 类实体）、CAM 刀路、G-code 后处理、客户端 23 屏 UI、注册/心跳/权限/设备管理/自动更新/Kiosk 保活/离线缓存与补传
2. **无法确认**：Git 历史与远程状态（无 .git、网络不通）；高级模式多数页面的真机行为；服务端长期运行稳定性
3. **模拟/未接入**：全部真实设备行为；真实控制卡通讯；PDF/OCR
4. **最重要技术风险**：更新通道安全（P0-1）、数据无备份（P0-2）、默认凭据（P0-3）、零服务端测试（P1-2）、真实设备接入完全未开始（P1-1）
5. **推荐下一阶段顺序**：① P0 三项安全加固 → ② 服务端/客户端同步链路补测试 → ③ 简易模式刀路改为读取已同步工艺方案 → ④ 确定控制卡型号并启动只读通讯验证 → ⑤ 暂停/继续与版本失效联动
6. **建议优先补充的测试**：服务端路由集成测试、DB 迁移回归测试、同步冲突与离线补传测试、APK 更新防篡改测试、Kiosk 多 ROM 真机测试
7. **需产品负责人回答的问题**：见 [OPEN_QUESTIONS.md](OPEN_QUESTIONS.md)

---

## 十、第一阶段实施任务：本地跟踪机制、报告格式与自动化评估

> 对应《CNC Host 自动化协作系统：第一阶段实施任务》任务二/三/四。本阶段仅给出方案，未安装、未登录、未改全局配置。

### 10.1 本地开发跟踪机制设计（Windows 10/11，PowerShell + Git + Gradle）

**前提缺口**：当前项目目录不是 Git 仓库。任何"分支/commit/差异"类跟踪都必须先解决版本控制基线（见 OPEN_QUESTIONS Q1）。在 Git 基线建立前，只能用文件快照+哈希清单做弱跟踪。

建议新增 `tools/` 目录（本阶段仅设计，**未创建**）：

**脚本 A：`tools/snapshot.ps1`（任务开始/结束各跑一次）**
- 输出 `_tracking/YYYYMMDD-HHmm-<任务号>/snapshot.json`：
  - Git：`git rev-parse --abbrev-ref HEAD`、`git rev-parse HEAD`、`git status --porcelain=v1`（有仓库时）
  - 无 Git 时：`Get-FileHash` 对 `app/src`、`server/src`、`*/build.gradle.kts` 生成 SHA-256 清单
- 明确排除：`local.properties`、`*.db`、`data/`、`build/`、任何 `*.keystore`

**脚本 B：`tools/run-checks.ps1`（构建与测试记录）**
- 固定 JDK17：`$env:JAVA_HOME` 指向已知 JDK 17 路径（从 `local.properties` 同级配置读取，不硬编码个人路径）
- 顺序执行：`gradlew :app:assembleDebug`、`gradlew test`、`gradlew -p server installDist`
- 每项记录：命令、开始/结束时间、退出码、输出尾部 200 行 → `_tracking/<任务号>/checks.json` + `build.log`
- **绝不记录**：环境变量值（只记录名称）、命令行中的 token 参数

**脚本 C：`tools/report.ps1`（生成任务报告骨架）**
- 汇总 snapshot 差异 + checks 结果 → `_tracking/<任务号>/REPORT.md`（格式见 10.2）
- `_tracking/` 加入 `.gitignore`，避免追踪数据污染仓库

### 10.2 任务报告格式（模板）

```markdown
# 任务报告：<任务编号> <任务目标>

## 1. 任务信息
- 任务编号 / 目标 / 验收标准 / 未解决问题

## 2. 变更文件清单
- 新增 / 修改 / 删除（路径列表；来源：git status 或快照哈希比对）

## 3. 变更摘要
- 每个文件一句话说明；不含敏感信息的 diff 统计（+x/-y 行）

## 4. 实际执行的检查与测试
| 命令 | 退出码 | 结果 | 日志 |
## 5. 每项检查的真实结果（附关键输出摘录）
## 6. 未执行的检查及原因
## 7. 已知风险和待办
## 8. Git 提交编号（无仓库时标注"本地未提交"）
## 9. 验收状态
- [ ] Agent 声称完成
- [ ] 脚本检查通过
- [ ] 用户真机验收通过
```

三级验收状态必须独立勾选，**上级不自动蕴含下级**。

### 10.3 后续自动化评估（调查结论）

| 问题 | 结论 | 依据 |
|---|---|---|
| 当前 Trae CN 是否支持 TRAE CLI 2.0 | **待验证**。本会话未找到可查询本机 Trae 版本/账号能力的非交互命令；需用户确认客户端版本与官方文档 | 未执行安装/登录 |
| 是否存在安全任务派发的非交互接口 | **待验证**。当前会话由人工对话驱动；未发现本地可调用的任务派发 API | — |
| 本地脚本/MCP 收集任务结果 | **可行**。10.1 的脚本方案完全用 PowerShell+Git+Gradle 实现，不依赖外部服务 | 设计 |
| GitHub Actions 可行性 | **有条件可行但当前受阻**：① 项目不在 Git 仓库且无可用远程（GitHub 直连超时，网络待解决）；② 若解决网络，Android 构建可用 `ubuntu-latest` + `setup-java` JDK17 + Android SDK；服务端 job 独立；③ 凭据（如 `CNC_ADMIN_TOKEN`、签名 keystore）应入 GitHub Secrets，绝不入库 | 设计 |
| 避免本地未提交改动被覆盖 | 自动化一律在**独立 clone / git worktree** 中运行；禁止在用户工作目录执行 `git checkout/reset/clean`；任务开始前快照（10.1 脚本 A）作为回滚参照 | 设计 |
| Agent 工作区隔离与回滚 | 每个自动任务 = 独立 worktree + 独立 `_tracking/<任务号>`；合入前必须人工 review diff；数据库文件不进版本库，迁移脚本必须附回滚 SQL | 设计 |

---

## 附：本次盘点实际检查的范围

- 全部 9 个模块的目录与构建脚本；客户端 52 个 Kotlin UI 文件清单；导航路由定义
- 服务端 `Main.kt`、`Routes.kt`、`Database.kt`、`Models.kt`、网页后台静态资源
- 关键客户端文件：`DeviceRepository.kt`、`AndroidManifest.xml`、`KioskService.kt`、`CncApplication.kt`、`MainActivity.kt`、`BootReceiver.kt`、`ServerApi.kt`、`SyncManager.kt`、`SetupPrefs.kt`、`ServerPrefs.kt`
- 需求文档：`README.md`、`docs/产品需求规格说明书V1.0.md`
- 安全扫描：默认凭据、明文传输、鉴权范围、备份与签名配置
- 实测：`gradlew test`（Java 8 失败一次 / JDK 17 成功一次）、JUnit XML 结果解析、GitHub 连通性测试
- 未修改任何业务代码、未删除任何文件、未改数据库、未提交/推送、未连接任何真实设备
