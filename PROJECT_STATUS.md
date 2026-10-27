# PROJECT_STATUS — 当前代码结构、功能完成度与技术风险

- 生成时间：2026-10-28 05:30 前后（基于基线提交 `5b02049` 实测）
- 详细功能逐项矩阵见 FEATURE_MATRIX.md；本文件为结构化摘要 + 今日新发现。

## 1. 代码结构与模块关系

```
cnc-host/（工作副本根=仓库根）
├── app/                 Android 客户端（Compose，minSdk 24/target 34，versionCode 3 / 1.2.0）
│   ├── ui/simple/       简易 4 屏：任务/预览下发/运行监控/报警
│   ├── ui/<19 个高级屏> 服务端同步、设备、通讯、图纸×2、零件、材料、刀具、工艺、CAM、仿真、审核、加工、IO、报警、历史、设置等
│   ├── setup/           首启引导：WifiSetup、ServerAddress、SetupPrefs、DeviceHeartbeat
│   ├── update/          AppUpdate + AppUpdateDialog（自动更新）
│   ├── sync/            ServerApi/ConfigSyncer/SyncManager（HttpURLConnection）
│   ├── persistence/     Room（CncDatabase/Entities/Daos/SeedData）
│   ├── service/KioskService + receiver/BootReceiver + CncApplication（崩溃重启）
├── core-model/          统一数据模型（14 单测）
├── core-ui/             工业 HMI 主题组件
├── device-api/          DeviceController 接口
├── device-simulator/    模拟控制卡（52 单测：运动/IO/报警/故障注入/命令队列/自动化流程）
├── dxf-parser/          DXF→几何（23 单测；POLYLINE/SPLINE 不支持）
├── cam-engine/          车削刀路（15 单测）
├── gcode-post/          Fanuc 0i / Siemens 840D（23 单测）
├── server/              Ktor 2.3.12 + SQLite：REST/WS/网页后台；独立子工程（无测试）
└── docs/                产品需求规格说明书V1.0.md
```

数据流：服务端 SQLite ⇄ REST/心跳 ⇄ 客户端 Room 缓存（config_version 增量）→ 简易/高级界面；设备侧数据全部来自 `DeviceRepository.shared`（SimulatedDeviceController，`isSimulated=true`）。

## 2. 构建与测试（2026-10-28 05:23 实测，工作副本）

| 项 | 结果 |
|---|---|
| `./gradlew --no-daemon build`（JDK 17） | **BUILD FAILED 3m30s**（213 任务：127 执行/86 缓存） |
| 失败点 | `:app:lintDebug` —— 4 错误 24 警告 |
| 编译/打包 | 全部成功（debug+release APK、server installDist 均产出） |
| 单元测试 | **127/127 通过，0 失败 0 错误 0 跳过**（XML 时间戳 05:23:30–33：cam-engine 15、core-model 5+9、device-simulator 6+10+20+11+5、dxf-parser 23、gcode-post 23） |
| app/server 测试 | app NO-SOURCE；server 无测试源 |
| Lint 4 错误 | WifiSetup.kt L96×2（NewApi：getTransportInfo/强转需 API29，minSdk 24）、L136（MissingPermission：scanResults 未做运行时权限检查）、AndroidManifest.xml L16（CoarseFineLocation：缺 ACCESS_COARSE_LOCATION） |

**新发现（旧报告需修正）**：FEATURE_MATRIX 称"静态检查未配置"不准确——AGP 内置 lintDebug 一直在 `build` 生命周期内执行且默认 abortOnError；此前只跑 `test`/`assembleDebug` 所以从未触发。另外 PROJECT_STATUS_REPORT L125"60 个测试"应为 52（明细表正确）。

## 3. 功能完成度分级（与 FEATURE_MATRIX 一致，按 2026-10-28 证据）

- **已实现+测试证据**：模拟控制卡全链路、DXF 4 类实体、CAM 刀路、G-code 后处理、核心模型。
- **已实现+真机验证**：Kiosk 保活、首启注册（含唯一注册 409 流程）、心跳权限下发、在线状态、后台设备管理、设置入口、自动更新弹窗与下载。
- **已实现未充分验证**：服务端 REST 全量（仅 curl/后台手工）、离线补传队列、配置增量同步、高级 19 屏、APK 安装回读、DB 迁移路径。
- **模拟数据**：运行监控、报警、IO、加工控制、设备总览（全部设备侧）。
- **占位/空壳**：通讯配置页（测试连接固定返回"模拟成功"，不落库）、系统设置页（仅内存 StateFlow，不持久化）。
- **未实现**：真实控制卡接入、暂停/继续、PDF/OCR、POLYLINE/SPLINE、多用户分级、release 签名混淆。

## 4. 技术风险（Top，完整 12 项见 PROJECT_STATUS_REPORT 第七节）

1. P0 自动更新通道可被中间人劫持（HTTP+校验和同通道）。
2. P0 SQLite 单文件无备份（`data/cnc-server.db`，含真实注册数据）。
3. P0 默认凭据硬编码（admin/123456、cnc-admin-2026、PIN 1234），部署未强制改。
4. 高：全链路明文 HTTP + `usesCleartextTraffic`。
5. 中：注册/心跳/密码校验免鉴权、无频率限制；管理员密码明文缓存于客户端 SharedPreferences；`allowBackup=true`；debug 签名直接下发。
6. GitHub Public 仓库历史含敏感物（真实 DB、源码 zip、windows zip、日志）——main tip 覆盖后历史仍可经 agent 分支/PR #1 访问，根除需重写历史或重建仓库（待决策）。

## 5. Git 当前状态（工作副本）

- HEAD：`5b0204995d963ef51ba45920adaa3a22394a9927`（main，root commit，173 文件）。
- 远程：origin/main=`3d705e5`、origin/trae/agent-2S8kzZ=`11212fc` 已 fetch；**未推送任何内容**。
- 覆盖方案：force-with-lease 推 main（回滚锚 3d705e5 本地/克隆双重保留），等用户确认。
- 原项目目录 `cnc-host-main\cnc-host`：全程未做任何修改；完整备份在 `cnc-host-backup-20261028`。
