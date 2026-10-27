# CNC Host — 两轴数控车床上位机（客户端 + 服务端）

面向 X/Z 两轴数控车床的加工上位机。采用 C/S 架构：

- **Android 平板客户端**：傻瓜式日常操作，只做「选任务 → 预览下发 → 盯进度 → 看报警」
- **服务端**：后台配置与接口管理，提供 REST API、WebSocket 状态推送和网页管理后台
- 工艺参数、刀具、材料、图纸、任务全部由服务端维护；平板只缓存，不提供编辑入口
- 服务端离线时平板继续使用本地缓存加工，任务记录排队补传

## 技术栈

| 端 | 技术 |
|----|------|
| 客户端 | Kotlin + Jetpack Compose + Room + Navigation Compose |
| 服务端 | Kotlin JVM + Ktor 2.3.12 + SQLite (JDBC) + kotlinx.serialization |
| 构建 | Gradle 8.14.6 / AGP 8.7.2 / Kotlin 2.0.21 / JDK 17 |
| 客户端兼容 | minSdk 24 / targetSdk 34（横屏工业平板） |

## 模块划分

| 模块 | 说明 |
|------|------|
| `app` | Android 客户端：简易 4 屏、高级模式、Room 缓存、服务端同步 |
| `core-model` | 统一数据模型（零件、图纸、刀具、工艺、刀路、报警等） |
| `core-ui` | 工业 HMI 设计系统 |
| `device-api` | 统一设备接口 `DeviceController` |
| `device-simulator` | 模拟控制卡（运动、主轴、IO、报警、故障注入） |
| `dxf-parser` | DXF 文本解析 → 几何模型 |
| `cam-engine` | 轮廓提取、车削刀路生成、参数校验 |
| `gcode-post` | Fanuc / Siemens 后处理 |
| `server` | 独立 Gradle 工程：REST + WebSocket + 网页管理后台 + SQLite |

## 客户端界面

### 简易模式（默认，底部 4 页签）

1. **任务**：展示服务端下发的图纸任务，点「开始」进入预览
2. **预览下发**：自动生成刀路与 G-code，确认装夹后一键下发
3. **运行监控**：进度、轴位置、主轴、执行日志、受控停止、急停、急停复位
4. **报警状态**：设备报警、服务端同步状态、加工状态；确认仅记录在本机

### 高级模式（防误触 PIN，默认 `1234`）

顶部「高级模式」进入，左侧导航包含：服务端同步、设备配置、通讯配置、图纸管理、图纸解析、零件毛坯、材料库、刀具库、工艺方案、CAM、仿真、审核、加工控制、IO、报警中心、任务历史、系统设置。

> PIN 仅用于防误触，不是安全边界。

### 首次启动与设备注册（Kiosk 模式）

客户端安装后以 Kiosk 模式运行：开机自启、始终前台、闪退自动重启。

1. **网络配置**：首次启动引导跳转系统 WiFi / 移动网络设置，联网后进入下一步
2. **设备注册**：填写单位名称、联系人姓名、电话、服务器地址，提交后自动在后台注册，获得设备 ID 与默认全功能权限
3. **主界面**：注册完成后进入简易模式主界面

注册信息写入本地 `SetupPrefs`，后续启动直接进入主界面。

### 右上角设置（管理员密码）

主界面右上角「设置」按钮，输入管理员密码后可：

- 修改服务器地址
- 临时退出到安卓桌面（20 秒后看门狗自动重新拉起）

管理员密码由后台统一设置（默认 `admin123`）。客户端联网时以后台校验为准，离线时回退到本机缓存的密码。

### 后台设备管理与功能权限

管理后台「设备」页可查看所有注册设备（单位、联系人、在线状态、注册时间），并可：

- 按设备逐项开关功能权限：任务选择、预览下发、运行监控、报警、高级模式、服务端同步、图纸、CAM、加工控制、IO、历史
- 修改全局管理员密码

权限通过设备心跳（每 30 秒）实时下发到客户端，对应功能入口自动显隐。

## 服务端运行

```bash
# 1. 构建可执行包（在仓库根目录）
./gradlew -p server installDist

# 2. 启动（需要 JDK 17）
server/build/install/cnc-server/bin/cnc-server
```

可用环境变量：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `PORT` | `8080` | HTTP 端口 |
| `CNC_ADMIN_TOKEN` | `cnc-admin-2026` | 管理口令（REST Bearer Token） |
| `CNC_DB` | `data/cnc-server.db` | SQLite 数据库路径 |

地址：

- 管理后台：`http://<服务器IP>:8080/admin`
- 健康检查：`http://<服务器IP>:8080/api/v1/health`（免鉴权）
- 状态推送：`ws://<服务器IP>:8080/ws/status?token=<管理口令>`

首次启动会自动建库并写入示例刀具、材料、零件、毛坯、工艺方案、图纸（含示例 DXF）和任务记录。

### 主要接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/config` | 拉取完整配置（含版本号） |
| GET | `/api/v1/config/version` | 仅拉取配置版本 |
| GET | `/api/v1/tasks` | 任务历史 |
| POST | `/api/v1/tasks` | 上传任务记录 |
| GET/POST | `/api/v1/status` | 查询/上报设备状态 |
| PUT | `/api/v1/device` | 更新设备与通讯配置 |
| POST/DELETE | `/api/v1/tools`、`materials`、`parts`、`blanks`、`plans`、`drawings`、`corrections` | 配置增删 |

设备管理接口（注册/心跳/校验免鉴权，列表/权限/密码需管理口令）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/devices/register` | 设备注册（单位、联系人、电话、服务器地址） |
| GET | `/api/v1/devices/{id}` | 查询设备信息与权限 |
| POST | `/api/v1/devices/{id}/heartbeat` | 心跳上报并拉取最新权限 |
| POST | `/api/v1/devices/{id}/verify-admin` | 校验管理员密码 |
| GET | `/api/v1/devices` | 设备列表（需管理口令） |
| PUT | `/api/v1/devices/{id}/permissions` | 更新设备功能权限（需管理口令） |
| POST | `/api/v1/admin-password` | 修改全局管理员密码（需管理口令） |

除 `/health`、设备注册/查询/心跳/校验外均需 `Authorization: Bearer <CNC_ADMIN_TOKEN>`。

## 客户端连接服务端

**首次启动**会引导完成设备注册（填写服务器地址等），注册成功后服务器地址即写入本地。

如需配置管理口令（用于拉取完整工艺配置），在主界面右上角「设置」→ 输入管理员密码 → 修改服务器地址；或进入高级模式「服务端同步」填写管理口令并同步。

1. 顶部「高级模式」→ 输入 PIN（默认 `1234`）
2. 进入「服务端同步」
3. 填写服务端地址（如 `http://192.168.1.10:8080`）与管理口令
4. 点「测试连接」→「保存设置」→「立即同步」
5. 回到简易模式，任务列表即出现服务端下发的图纸

配置版本号机制：服务端每次修改配置都会递增 `config_version`，客户端只在版本变化时覆盖本地 Room 缓存。

## 构建与测试

```bash
# 客户端编译 + 全部单元测试
./gradlew :app:assembleDebug test

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

当前结果：**单元测试全部通过**，debug APK 构建成功（约 18 MB）。

## 安全原则

- 急停、限位、运动下发完全在本地设备侧执行，**不依赖服务端在线**
- 受控停止 ≠ 硬件急停；真实急停由硬件安全回路承担
- 运动命令按命令队列终态判断，不使用固定延时猜测完成
- 任一段返回 `RESULT_UNKNOWN` / `SEND_FAILED` / 执行失败立即中止，**禁止盲目重发**
- 模拟模式数据明确标注 `isSimulated = true`，不冒充真实设备
- 报警「确认」不等于故障消除，恢复加工前必须重新检查现场与设备状态
- 平板无工艺参数编辑入口，避免现场误改

## 未实现 / 已知限制

- **真实控制卡尚未接入**，当前运动、主轴、IO、报警均为模拟数据
- 简易模式刀路根据图纸关联的工艺方案读取毛坯尺寸与切削参数（主轴转速、进给、切深等）；若图纸未关联工艺方案则回退到默认参数（毛坯 180×60 mm、主轴 1200 rpm）
- 已实现「暂停/继续」：暂停时保留已发送段索引，继续时从下一段恢复；暂停期间主轴保持转动
- 服务端状态页展示的是客户端上报数据，不是控制卡实时直连
- 客户端离线补传队列上限 100 条，超出后丢弃最旧记录
- APK 为 debug 签名，未做 release 签名与混淆
- Kiosk 为软件级保活（前台服务 + 看门狗 + 开机自启），无法阻止用户「强制停止」或系统 OOM 回收；如需彻底锁定需配合设备 Owner / LockTask 模式
- 「退出到安卓桌面」为临时窗口（20 秒），到期后看门狗自动重新拉起前台
- 管理员密码离线校验依赖本机缓存，首次必须联网校验后才会缓存
- 服务端为单管理员令牌鉴权，设备权限按设备粒度配置，无多用户分级

## 开发上下文（AI 接手必读）

> 新任务打开本仓库时，请先阅读本节，即可了解项目全貌与当前进度，无需用户重复沟通。

### ⚠️ 完整产品规格书（必读）

本项目的完整原始需求规格书保存在 [`docs/产品需求规格说明书V1.0.md`](docs/产品需求规格说明书V1.0.md)，共 15 章，涵盖：
- 项目定位与成功标准
- 产品架构与 16 个模块划分
- Android 工业 UI 设计规范
- 17 个页面的详细需求
- 图纸解析、CAM 刀路、自动化状态机、通讯适配的完整设计
- 分阶段开发计划与测试验收标准
- 7 大风险与应对
- 15 条强制开发指令

**新接手的 AI 必须先读该文档**，再结合本节的「已完成功能」和「本地接手第一步」继续开发。

### 近期 C/S 升级需求（用户最新补充）

客户端安卓系统安装本软件后需实现：
1. **Kiosk 模式**：开机自动启动，始终保持在屏幕前台显示；闪退自动重新进入软件
2. **首次启动网络配置**：先引导用户配置 WiFi 或移动数据，确保能连上网络
3. **设备注册**：网络配置完成后出现注册按钮，填写单位名称、姓名、电话号码后本台机器自动注册到后台；后台可查看设备注册信息、在线情况、设备信息，便于售后知道哪台设备属于哪个单位
4. **功能权限配置**：后台可按不同单位/设备配置客户端功能权限；注册完成后后台默认给全部权限，可自定义开关
5. **服务器地址**：客户端注册时需填入服务器地址用于连接服务器
6. **设置入口**：客户端首页右上角加设置按钮，点击需输入管理员密码（后台可设置），进入后可配置服务器 IP 地址、可退出软件回到安卓界面
7. 可在此基础上拓展和完善所有功能

### 项目定位

两轴数控车床 Android 上位机，C/S 架构：
- **Android 客户端**（`app/`）：横屏工业平板，Kotlin + Jetpack Compose，Kiosk 模式运行
- **服务端**（`server/`）：Kotlin + Ktor + SQLite，REST + WebSocket + 网页管理后台

### 已完成功能（全部编译通过、测试通过、冒烟通过）

1. **Kiosk 保活**
   - `app/src/main/java/com/cnc/host/receiver/BootReceiver.kt`：开机自启，拉起 MainActivity + KioskService
   - `app/src/main/java/com/cnc/host/service/KioskService.kt`：前台服务，看门狗每 1500ms 检测 `MainActivity.isResumed`，不在前台且不在退出窗口则自动 `startActivity` 拉起
   - `app/src/main/java/com/cnc/host/CncApplication.kt`：`installCrashRestartHandler` 通过 AlarmManager 1.5s 后重启 + `Process.killProcess`
   - `MainActivity` 维护 `companion object { var isResumed = false }`，`onResume`/`onPause` 置位

2. **首次启动注册流程**
   - `NetworkSetupScreen`：跳转系统 WiFi/移动数据设置，「我已联网」进入下一步
   - `RegisterScreen` + `RegisterViewModel`：单位/姓名/电话/服务器地址 → `ServerApi.registerDevice` → 写 `SetupPrefs.saveIdentity`（setupCompleted=true）+ `savePermissions` + 回写 `ServerPrefs.baseUrl`
   - `SetupPrefs`（`setup/SetupPrefs.kt`）：`DeviceIdentity(setupCompleted, deviceId, unitName, contactName, phone, serverAddress, adminPassword, permissions)`，另有 `setExitWindow`/`exitUntil` 控制退出窗口
   - `MainActivity` 启动时读 `SetupPrefs.load`，`!setupCompleted` 则渲染 NetworkSetup → Register，否则进入主界面

3. **后台设备管理**（服务端）
   - `Database.kt`：`devices` 表（id, unit_name, contact_name, phone, server_address, registered_at, last_seen, online, permissions TEXT）；`meta.admin_password` 默认 `admin123`
   - 开放接口：`POST /api/v1/devices/register`、`GET /api/v1/devices/{id}`、`POST /api/v1/devices/{id}/heartbeat`、`POST /api/v1/devices/{id}/verify-admin`
   - 管理接口：`GET /api/v1/devices`、`PUT /api/v1/devices/{id}/permissions`、`POST /api/v1/admin-password`
   - 后台网页 `static/app.js`：设备列表、权限勾选弹窗、修改管理员密码

4. **客户端设置面板**
   - `TopStatusBar` 右上角设置图标（`Icons.Default.Settings`），`onSettings` 回调
   - `KioskSettingsOverlay`：先 AlertDialog 输入管理员密码 → `KioskSettingsViewModel.verifyPassword`（优先 `ServerApi.verifyAdminPassword`，失败回退 `SetupPrefs.adminPassword` 缓存）→ 通过后显示服务器地址输入 + 「退出到安卓桌面」按钮（`setExitWindow(20)` + `moveTaskToBack(true)`）

5. **权限门禁**
   - `PermissionsHolder.flow`（`setup/DeviceHeartbeat.kt`）：`StateFlow<ClientPermissionsDto>`，`load`/`update`
   - `DeviceHeartbeat.start`：每 30s 调 `ServerApi.heartbeat` 拉取 `DeviceInfoDto.permissions`，写 `SetupPrefs.savePermissions` 并 `PermissionsHolder.update`
   - `MainActivity`：底部 4 页签按 `taskSelect`/`previewStart`/`monitor`/`alarms` 过滤；「高级模式」按钮按 `advanced` 显隐

### 关键凭据（本地调试用）

| 项 | 值 |
|----|-----|
| 服务端管理 Token | `cnc-admin-2026`（环境变量 `CNC_ADMIN_TOKEN`） |
| 客户端管理员密码 | `admin123`（可在后台修改） |
| 高级模式 PIN | `1234` |
| 服务端端口 | `8080` |

### 本地接手第一步

1. 确认 JDK 17、Android Studio（SDK Platform 34）已装
2. 在项目根目录创建 `local.properties`，填入 `sdk.dir=你的AndroidSDK路径`
3. 运行：`./gradlew :app:assembleDebug test` 验证编译和测试
4. 启动服务端：`CNC_ADMIN_TOKEN=cnc-admin-2026 ./gradlew -p server installDist && server/build/install/cnc-server/bin/cnc-server`
5. 浏览器打开 `http://localhost:8080/admin/` 验证后台

### 硬性约束（贯穿项目）

- 必须有真实代码，未实现功能不得标完成
- 模拟数据必须标 `isSimulated`
- 急停/限位/运动下发不得依赖服务端在线
- 自动化流程禁止固定延时；`RESULT_UNKNOWN` 禁止盲目重发
- 不创建不必要文档文件
