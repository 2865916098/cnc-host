# ChatGPT 项目交接文档（CNC Host 车间终端系统）

> 用途：让 ChatGPT 通过本仓库快速了解项目全貌并继续开发。
> 阅读顺序：本文档 → `docs/PROJECT_DOCUMENTATION.md`（完整项目文档）→ `docs/API.md`（接口文档）→ `README.md`。

## 1. 项目概述

工业车间 CNC 终端系统，两部分：

- **Android 客户端**（`app/`，Kotlin + Jetpack Compose）：车间平板 Kiosk 应用，锁定横屏、开机自启、不可退出到桌面、崩溃自动重启。
- **服务端**（`server/`，Kotlin + Ktor + SQLite）：REST API + WebSocket + 网页管理后台（`server/src/main/resources/static/` 原生 HTML/JS）。

## 2. 构建与运行

| 项 | 值 |
|---|---|
| JDK | 17 |
| 客户端构建 | `gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| 服务端运行 | `gradlew :server:run`（端口 8080，数据库 `server/data/cnc-server.db` 自动建表+种子数据） |
| 服务端测试 | `gradlew :server:test`（当前 18 项测试全绿） |
| 管理后台 | http://localhost:8080/admin/ ，账号 `admin` / `123456` |
| 环境变量 | `CNC_ADMIN_TOKEN`（默认 `cnc-admin-2026`）、`CNC_APK`（发布 APK 路径） |

## 3. 当前进度（全部已完成并真机验证）

- **首次启动引导**：科技感 Splash（旋转双环/脉冲/扫描线，2 秒自动进入）→ 步骤条贯穿 3 步：
  1. 网络配置：2 秒轮询联网状态，WiFi/移动数据选项卡，App 内连 WiFi，未联网隐藏「下一步」；
  2. 设备激活：省份→城市→单位名称→联系人→电话→服务器地址（默认预填 192.168.1.100，可局域网自动搜索）；幂等激活（同 hardwareId 重复激活保留 id/token，清数据不卡死）；
  3. 权限确认：悬浮窗/忽略电池优化/厂商开机自启。
- **内嵌输入法**（全局禁用系统 IME）：26 键全拼、9 键 T9 拼音（同数字串音节交错候选）、英文（大小写/符号）、数字页（带返回/完成）。拼音引擎 `PinyinEngine.kt` + 词库 `assets/pinyin_dict.txt`（约 7000 词条含工业词汇）。键盘面板限高 94% 屏高防溢出。
- **Kiosk 保活**：看门狗 700ms、BootReceiver 开机自启、崩溃自动重启、屏幕常亮、心跳 10s/离线判定 25s。
- **服务端**：设备激活/心跳/注销、按单位+地区统计终端（`/devices/org-summary`）、用户体系（PBKDF2、SUPER_ADMIN/ADMIN/OPERATOR 三角色、12 个模块权限、路由拦截）、配置同步（config_version 增量）、操作日志、APK 一键发布（AXML 解析扫描构建目录取最高 versionCode）。

## 4. 关键设计决策（不要回退）

- 网络判定**不要求** `NET_CAPABILITY_VALIDATED`（车间是无外网隔离局域网），见 `WifiSetup.isOnline()`。
- 设备令牌最小权限：仅可 GET 非账号接口 + POST /tasks、/status。
- 激活必须联网，未联网客户端直接拦截不发起请求。
- 注册/激活按 hardwareId 幂等，禁止返回 409 卡死清数据设备。

## 5. 待办（二期）

- **手写输入法**（已与用户确认为二期）：需集成离线中文手写模型（纯 TFLite，Kiosk 无 GMS），APK 预计增大 10–50MB。
- 可选增强：Device Owner / LockTask 设备级锁定（当前为软件级 Kiosk）。

## 6. 已知环境约束（开发机）

- 工作目录：`e:\AI软件开发\cnc-host-git-baseline`；分支 `feat/kiosk-20261028`。
- 测试设备：小米 M2102K1C（横屏 3200×1440），当前安装 versionCode=3 / versionName=1.2.0。
- PowerShell 5 陷阱：`Invoke-RestMethod`/`ConvertFrom-Json` 会把 JSON 数组包成 `{value,Count}`，中文按 Latin-1 解码；接口验证用 curl + UTF-8 文件读写。

## 7. 仓库文档索引

| 文档 | 内容 |
|---|---|
| `docs/PROJECT_DOCUMENTATION.md` | 14 章完整项目文档（架构、数据库、客户端/服务端模块、部署运维、验收清单） |
| `docs/API.md` | 51 个 REST 接口 + 1 个 WebSocket |
| `docs/产品需求规格说明书V1.0.md` | 原始需求 |
| `docs/spec/2026-10-28-kiosk-fixes/` | Kiosk 修复专项 spec 与任务清单 |
