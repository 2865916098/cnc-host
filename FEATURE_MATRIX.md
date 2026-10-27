# FEATURE_MATRIX — CNC Host 功能清单与实现状态

- 日期：2026-10-28　配套报告：[PROJECT_STATUS_REPORT.md](PROJECT_STATUS_REPORT.md)
- 状态取值（仅按证据标记）：
  - ✅T = 已实现且有测试证据　✅ = 已实现但尚未充分验证　🔶 = 部分实现
  - 🖼 = 仅有界面或占位实现　🎭 = 仍使用模拟数据/模拟设备行为　❌ = 尚未实现　❓ = 证据不足待核实
- "验证证据"列中「单测 n」= 单元测试用例数（2026-10-28 实测全过）；「真机」= 前序任务在小米平板 M2102K1C 实测

## 一、算法与设备模块（纯 Kotlin，有单测）

| 功能 | 状态 | 代码位置 | 验证证据 | 缺口/待办 |
|---|---|---|---|---|
| 模拟控制卡（运动/主轴/IO/报警） | ✅T（🎭 本身就是模拟） | `device-simulator/.../SimulatedDeviceController.kt` | 单测 11 | — |
| 模拟命令队列（终态判断/禁盲重发） | ✅T | `device-simulator/.../SimulatedCommandQueue*` | 单测 20 | — |
| 故障注入（断线/夹紧失败/超时等） | ✅T | `device-simulator/.../EnhancedSimulator*` | 单测 10 | — |
| 自动化流程模拟（送料-夹紧-切削-松料） | ✅T | `device-simulator/.../AutomationFlowSimulator*` | 单测 6 | 真实 IO 反馈未接 |
| 模拟日志 | ✅T | `device-simulator/.../SimulationLogger*` | 单测 5 | — |
| DXF 解析（LINE/CIRCLE/ARC/LWPOLYLINE） | ✅T | `dxf-parser/.../DxfParser.kt` L248-251 | 单测 23 | POLYLINE、SPLINE 解析为 UnsupportedEntity |
| 统一数据模型与几何变换 | ✅T | `core-model/` | 单测 14 | — |
| CAM 粗车/精车刀路生成 | ✅T | `cam-engine/.../CamEngine.kt` | 单测 15 | 不支持特征需明确报告（部分有） |
| G-code 后处理（Fanuc/Siemens） | ✅T | `gcode-post/` | 单测 23 | 未经真实机床验证 |
| 统一设备接口 DeviceController | ✅T（接口层） | `device-api/` | 被模拟器实现并被测 | 无真实实现 |
| 真实控制卡接入（USB 串口/TCP） | ❌ | 无 device-serial / device-tcp 模块 | — | 等控制卡型号与协议（规格书阶段八） |
| 矢量 PDF / 图片 OCR 图纸导入 | ❌ | 无 recognition 模块 | — | 规格书 5.2/5.3 |
| 暂停/继续（状态机级） | ❌ | — | — | 现仅受控停止+急停（README L173 自报） |

## 二、客户端 — 框架与系统功能

| 功能 | 状态 | 代码位置 | 验证证据 | 缺口/待办 |
|---|---|---|---|---|
| 双模式导航（简易 4 页签 + 高级 19 项） | ✅ | `MainActivity.kt`、`navigation/Screen.kt` | 编译通过、真机使用 | 无 UI 自动化测试 |
| Kiosk 保活（开机自启/看门狗/崩溃重启/退出标记） | ✅ | `receiver/BootReceiver.kt`、`service/KioskService.kt`、`CncApplication.kt`、`MainActivity.kt` | 真机：修复 FGS 崩溃后 2 分钟 0 崩溃 | 仅验证小米 MIUI；其他 ROM 待测；软件级保活可被强杀（README L177 自报） |
| 首次启动网络配置（App 内 WiFi） | ✅ | `ui/setup/NetworkSetupScreen.kt` | 真机走通注册流程 | WifiNetworkSpecifier 需系统弹窗确认一次（README L181） |
| 设备注册（含 hardwareId 唯一约束） | ✅ | `ui/setup/RegisterScreen.kt`、`RegisterViewModel.kt`；服务端 `/devices/register`、`devices.hardware_id` 部分唯一索引 | 真机：重复注册 409→后台注销→再注册成功 | 注册接口免鉴权（见报告七-6） |
| 心跳与功能权限下发（30s） | ✅ | `setup/DeviceHeartbeat.kt`、`PermissionsHolder`；服务端 `/devices/{id}/heartbeat` | 真机：权限显隐生效 | — |
| 在线状态判定（90s 无心跳离线） | ✅ | 服务端设备列表逻辑 | 真机/后台验证 | — |
| 设置入口（管理员密码/改服务器/退出软件/清除数据重注册） | ✅ | `ui/setup/KioskSettingsOverlay*.kt`、`setup/SetupPrefs.kt` | 真机：改地址、退出、清除数据重注册均验证 | 密码离线缓存在本机（报告七-7） |
| APK 自动更新（版本检查→弹窗→下载→SHA-256 校验→安装） | 🔶 | `update/AppUpdate.kt`、`AppUpdateDialog.kt`；服务端 `/app-version`、`/app-download`、`/app-publish`、`app_versions` 表 | 真机：v1.2.0 弹窗与下载确认 | 无 APK 签名验签（P0）；安装成功/失败回读未验证；dismissedCode 防抖 |
| 配置增量同步（config_version） | ✅ | `sync/SyncManager.kt`、`ConfigSyncer.kt`；服务端版本递增 | 代码核实；手工同步验证 | 无自动化测试；冲突策略=服务端覆盖 |
| 任务记录离线补传队列（上限 100 条） | ✅ | `sync/SyncManager.kt` L167-223、`ServerPrefs.kt` | 代码审查 | 未真机验证断网补传；溢出丢最旧（README L175） |
| 模拟模式标识 | ✅ | `data/DeviceRepository.kt` L23、18 处 `isSimulated` 引用、`HomeScreen.kt` 模拟提示 | 代码审查 | — |

## 三、客户端 — 简易模式 4 屏

| 功能 | 状态 | 代码位置 | 验证证据 | 缺口/待办 |
|---|---|---|---|---|
| 任务列表（服务端下发图纸任务） | 🔶 | `ui/simple/SimpleTasksScreen*.kt` | 真机使用 | 无单测 |
| 预览下发（自动生成刀路与 G-code） | 🔶 | `ui/simple/SimplePreviewScreen*.kt`、`simple/SimpleRunController.kt` | 真机使用 | **固定工艺参数+默认毛坯 180×60mm，未读 Room 工艺方案/毛坯**（README L172 自报，P1） |
| 运行监控（进度/轴位置/主轴/受控停止/急停） | 🎭 | `ui/simple/SimpleMonitorScreen*.kt` | 真机使用（模拟数据） | 全部来自模拟器 |
| 报警状态（确认仅记录本机） | 🎭 | `ui/simple/SimpleAlarmsScreen*.kt` | 真机使用（模拟数据） | 报警来自模拟器；确认不同步服务端 |

## 四、客户端 — 高级模式 19 屏

| 功能 | 状态 | 代码位置 | 验证证据 | 缺口/待办 |
|---|---|---|---|---|
| 服务端同步（地址/口令/测试连接/立即同步） | 🔶 | `ui/advanced/ServerSyncScreen*.kt` | 真机使用 | 无单测 |
| 设备总览 | 🎭 | `ui/home/HomeScreen*.kt` | 真机（模拟数据） | 数据来自模拟器 |
| 图纸管理 | 🔶 | `ui/drawings/DrawingsScreen*.kt` | 编译/界面核实 | 删除引用检查未确认；无测试 |
| 图纸解析与尺寸校正 | 🔶 | `ui/drawingparse/DrawingParseScreen*.kt` + dxf-parser | 解析核心有 23 单测 | PDF/图片未实现；校正项持久化未系统验证 |
| 零件与毛坯 | 🔶 | `ui/part/PartScreen*.kt` | 界面核实 | 客户端只读服务端数据；无测试 |
| 材料库 | 🔶 | `ui/materials/MaterialScreen*.kt` | 界面核实 | 编辑在服务端后台；无测试 |
| 刀具库 | 🔶 | `ui/tools/ToolScreen*.kt` | 界面核实 | 同上 |
| 工艺规划 | 🔶 | `ui/process/ProcessScreen*.kt` | 界面核实 | 自动推荐规则未确认完整实现 |
| CAM 刀路生成页 | 🔶 | `ui/cam/CamScreen*.kt` | 算法有单测 | UI 层未系统验证 |
| 刀路仿真（2D 播放/单步） | 🔶 | `ui/simulation/SimulationScreen*.kt` | 界面核实 | 版本失效联动未确认（规格书 9.2） |
| 加工审核清单 | 🔶 | `ui/review/ReviewScreen*.kt` | 界面核实 | 各检查项的真实判据未系统验证 |
| 加工控制 | 🎭 | `ui/machining/MachiningScreen*.kt` | 界面核实（模拟） | 无真实设备 |
| IO 监控 | 🎭 | `ui/io/IoScreen*.kt` | 界面核实（模拟） | 手动 IO 互锁仅在模拟器内 |
| 报警中心 | 🎭 | `ui/alarms/AlarmsScreen*.kt` | 界面核实（模拟） | — |
| 通讯配置 | 🖼 | `ui/comm/CommViewModel.kt` L29：测试连接返回"模拟成功"固定文案；配置不落库 | 代码核实 | 无真实串口/TCP 实现 |
| 设备配置 | 🔶 | `ui/device/DeviceScreen*.kt` | 界面核实 | 写入路径与生效范围未系统验证 |
| 任务历史 | 🔶 | `ui/history/HistoryScreen*.kt` + Room `task_history` | 界面核实 | 导出功能未确认 |
| 系统设置 | 🖼 | `ui/settings/SettingsViewModel.kt`：仅内存 StateFlow，**修改不持久化** | 代码核实 | 需接持久化或明确删除 |

## 五、服务端与网页后台

| 功能 | 状态 | 代码位置 | 验证证据 | 缺口/待办 |
|---|---|---|---|---|
| 配置 CRUD REST API（工具/材料/零件/毛坯/工艺/图纸/订正） | ✅ | `server/.../Routes.kt` L224-418、`Database.kt` | curl/后台手工验证 | **无自动化测试** |
| 设备管理（注册/心跳/权限/编辑/注销/列表） | ✅ | `Routes.kt` 设备区、`devices` 表 | 真机+curl 验证 | 注册/心跳免鉴权 |
| 管理后台登录/登出（会话 token 7 天） | ✅ | `Routes.kt` `/admin/login`、`admin_sessions` 表 | 后台手工验证 | 无失败锁定/频率限制 |
| 全局管理员密码修改 | ✅ | `/admin-password`、`Database.kt` L960-987 | 后台手工验证 | 默认密码硬编码（P0） |
| WebSocket 状态推送（→网页后台） | ✅ | `Routes.kt` `/ws/status`、`static/app.js` L878 | 后台手工验证 | token 走 URL query（报告七-10）；客户端不使用 WS |
| APK 发布（multipart 上传）与下载 | 🔶 | `Routes.kt` `/app-publish`、`/app-download` | curl 发布 v1.2.0 + 真机下载 | 无签名校验（P0）；无服务端测试 |
| 数据库建库与迁移（含 hardware_id 迁移） | 🔶 | `Database.kt` init/迁移块 | 新旧库均启动验证 | 迁移无自动化回归测试 |
| 首次启动种子数据（示例刀具/材料/图纸等） | ✅ | `Database.kt` | 后台可见 | — |
| 网页管理后台（设备/配置/发布界面） | 🔶 | `server/.../static/`（index.html/app.js/style.css） | 浏览器手工验证 | 静态目录夹带 `cnc-host-src.zip`（建议移除）；无前端测试 |
| 服务端自动化测试 | ❌ | `server/build.gradle.kts` 配了 test 依赖但无测试源 | 目录核查 | 补 Ktor 路由测试 |

## 六、测试与工程

| 项 | 状态 | 证据 | 待办 |
|---|---|---|---|
| 单元测试（5 模块 10 类 127 用例） | ✅T | `gradlew test` 2026-10-28 BUILD SUCCESSFUL，0 失败 | — |
| app 模块单元测试 | ❌ | `testDebugUnitTest NO-SOURCE` | 补同步/更新/状态机测试 |
| androidTest / UI 测试 | ❌ | 无 `src/androidTest` 用例（仅依赖声明） | 补关键流程 UI 测试 |
| 静态检查（lint/detekt/ktlint） | ❌ | 构建脚本未配置 | 建议接入 |
| release 签名与混淆 | ❌ | `app/build.gradle.kts` release 未配签名 | 发布前必做 |
| 规格书第十二章测试样例库（图纸/CAM/自动化异常用例集） | 🔶 | 部分被单测覆盖，无成体系样例文件与报告 | 建样例库 |
| 真机测试 | 🔶 | 注册/权限/更新/Kiosk 已验证；高级 19 屏未系统走查 | 见报告六-4 |
| 真实设备验证 | ❌ | 未接入 | 规格书 12.4 顺序执行 |

## 七、待办汇总（优先级见 PROJECT_STATUS_REPORT 第八节）

- **P0**：APK 更新验签、服务端数据备份、强制修改默认凭据
- **P1**：真实控制卡接入、服务端/app 测试空白、简易模式读 Room 工艺方案、暂停/继续、DB 迁移回归测试、Kiosk 多 ROM 验证
- **P2**：PDF/OCR、POLYLINE/SPLINE、版本失效联动、多用户分级、服务端守护与日志轮转、退出语义残留清理、移除源码 zip
- **P3**：网络层换 OkHttp、Database.kt 拆分、模型统一、静态检查、手册类交付物
