# SPEC — 2026-10-28 Kiosk 修复与功能完善

来源：用户 2026-10-28 指令（N1~N10）。工作目录 `E:\AI软件开发\cnc-host-git-baseline`（main=45fe505）。
本 spec 决策完备：每项含根因、精确改动点、验证方式。

## 已确认根因（源码证据）

| 编号 | 现象 | 根因（已读源码确认） |
|---|---|---|
| N6 | 任务页恒显「未连接服务端」、同步失败 | `RegisterViewModel.register()` 只把 baseUrl 写入 ServerPrefs；token 恒为空、deviceId 恒为 "tablet-1" → `ServerSettings.configured` 恒 false → `SyncManager.syncNow` 直接拒绝（"未配置服务端地址或管理口令"）。且 `/api/v1/config` 需要管理 Bearer，客户端本就没有令牌。 |
| N7 | 后台在线/离线 90s 才变 | `DeviceHeartbeat` 间隔 30s；`Database.isOnline` 阈值 90_000ms；后台设备页已 3s 轮询（无需改）。 |
| N1 | 系统状态栏遮挡页面 | `enableEdgeToEdge()` 后内容延申到状态栏下，未做沉浸隐藏也未加 padding。 |
| N2 | 仍可手动退出 | 导航栏可见可进多任务；应用出现在最近任务列表可被划掉。 |
| N3 | 清除数据后不回注册页 | `KioskSettingsViewModel.resetRegistration()` 用 AlarmManager+exitProcess 重启——Android 12+/MIUI 禁止后台启动 Activity，闹钟 PendingIntent.getActivity 被系统丢弃。 |
| N4 | 开机不自启 | BootReceiver 在后台 startActivity，Android 12+ 后台启动限制 + MIUI 需「自启/后台弹出界面」权限；未声明 SYSTEM_ALERT_WINDOW / 未引导用户授权。 |
| N5 | 系统输入法难用 | 30 处 OutlinedTextField 全部直接调起系统 IME。 |
| N8 | 发布需手动选包上传 | 服务端只支持 multipart 上传，无「扫描构建产物一键发布」。 |
| Lint | build 失败 | Manifest 缺 ACCESS_COARSE_LOCATION；WifiSetup L96 `transportInfo` 需 API29+；L136 `scanResults` 缺 @SuppressLint。 |

## 改动清单（精确到文件/函数）

### 服务端

**S1. `server/src/main/kotlin/com/cnc/server/Database.kt`**
- `init()` 迁移区新增：`ALTER TABLE devices ADD COLUMN device_token TEXT NOT NULL DEFAULT ''`（runCatching 忽略已存在）；`UPDATE devices SET device_token = lower(hex(randomblob(16))) WHERE device_token = ''`（老设备回填，配合心跳自愈）。
- `registerDevice()`：生成 `lower(hex(randomblob(16)))` 令牌插入 device_token，返回值携带 token。
- `getDevice()`：SELECT 增加 device_token，DeviceInfo 携带 token（心跳/注册响应自带令牌 → 老客户端自愈）。
- `isOnline()`：阈值 90_000 → 25_000，注释改为「心跳 10 秒，25 秒无心跳判离线」。
- 新增 `isValidDeviceToken(token: String): Boolean`（device_token 非空且匹配）。

**S2. `server/src/main/kotlin/com/cnc/server/Models.kt`**（DeviceInfo 定义处，执行时先 grep 定位）
- `DeviceInfo` 增加 `token: String = ""`。

**S3. `server/src/main/kotlin/com/cnc/server/Routes.kt`**
- bearer("admin") 鉴权：`credential.token == adminToken || Db.isValidSession(token) || Db.isValidDeviceToken(token)`。设备令牌只用于客户端拉配置/传任务/报状态，与管理口令同级（局域网可信场景，已符合 PRD 现状）。
- 新增（admin 鉴权内）：
  - `GET /api/v1/app-latest-candidate` → 扫描构建目录，返回 `{found, path, fileName, versionCode, versionName, sizeBytes, modifiedAt}`，无 APK 返回 `{found:false}`。
  - `POST /api/v1/app-publish-latest` → JSON body `{changelog}`；取最新候选 APK 拷贝到 `apkFile()`，按解析出的 versionCode/versionName 发布；无候选 404。

**S4. 新建 `server/src/main/kotlin/com/cnc/server/ApkPublish.kt`**
- `AxmlVersionReader`：解析 APK 内二进制 AndroidManifest.xml（ZIP 条目）。处理 chunk：RES_XML_TYPE(0x0003)、STRING_POOL(0x0001)、RES_XML_START_ELEMENT_TYPE(0x0102)；在 `manifest` 元素内按资源 ID 取 versionCode(0x0101021b, TYPE_INT_DEC=0x10) / versionName(0x0101021c, TYPE_STRING=0x03 走字符串池)。仅依赖 JDK ZipFile，无第三方库。
- `ApkScanner`：候选目录 = 环境变量 `CNC_APK_DIR`（`;` 分隔）缺省为 `../app/build/outputs/apk/release`、`../app/build/outputs/apk/debug`、`apk-inbox`（相对 server 工作目录）。递归找 *.apk，逐一解析版本，选 versionCode 最大（并列取 mtime 最新）。解析失败跳过该文件。解析器可注入便于单测。

**S5. `server/src/main/resources/static/app.js`**
- 设备页提示「90 秒无心跳视为离线」→「25 秒无心跳视为离线」。
- 版本发布面板顶部新增「最新构建」卡：进入设备页时拉 `/app-latest-candidate`，显示 文件名/版本/大小/时间 + 主按钮「一键发布此版本」（调 publish-latest，changelog 复用输入框）；同时把 versionCode/versionName 自动填入下方手动上传表单作为兜底。

**S6. 服务端测试（新增 `server/src/test/kotlin/com/cnc/server/ApkScannerTest.kt`）**
- 注入假解析器，验证：多 APK 选最高 versionCode；同 versionCode 选最新 mtime；全失败返回 null；环境变量目录解析。若 `server/build.gradle.kts` 无 test 依赖则补 `testImplementation(kotlin("test"))`。

### 客户端（app）

**C1. `app/src/main/AndroidManifest.xml`**
- 新增权限：`ACCESS_COARSE_LOCATION`(maxSdkVersion=32，修 lint)、`SYSTEM_ALERT_WINDOW`（悬浮窗→MIUI 后台弹出界面前提）、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
- BootReceiver intent-filter 增加 `android.intent.action.QUICKBOOT_POWERON`。
- MainActivity 增加 `android:excludeFromRecents="true"`（N2：最近任务列表不可见、无法划掉）。

**C2. `MainActivity.kt`**
- onCreate：`WindowCompat.setDecorFitsSystemWindows(window,false)` 保持；用 `WindowInsetsControllerCompat` 隐藏 systemBars + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（N1 沉浸式，状态栏不再遮挡）；`setShowWhenLocked(true)`/`setTurnScreenOn(true)`（N4 辅助）；`window.setSoftInputMode(SOFT_INPUT_STATE_ALWAYS_HIDDEN)`（N5 全局禁系统 IME 弹出）。
- 重写 `onWindowFocusChanged(hasFocus)`：有焦点时重新隐藏系统栏（防下拉后残留）。
- 引导流程由 2 步改 3 步：0=网络配置，1=权限与自启（新），2=设备注册。

**C3. 新建 `app/src/main/java/com/cnc/host/setup/KioskPermissions.kt`**
- `hasOverlayPermission(ctx)` / `overlayIntent()`（ACTION_MANAGE_OVERLAY_PERMISSION）
- `isBatteryExempt(ctx)` / `batteryExemptIntent()`（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，runCatching 兜底 ACTION_APPLICATION_DETAILS_SETTINGS）
- `autostartIntent()`：依 Build.MANUFACTURER 依次尝试 MIUI(`com.miui.securitycenter`)/OPPO/vivo/Huawei 自启管理组件，全部失败回退应用详情页。

**C4. 新建 `app/src/main/java/com/cnc/host/ui/setup/PermissionsSetupScreen.kt`**
- 第 2 步「权限与自启」：三行状态卡（悬浮窗/后台弹出、忽略电池优化、开机自启）各带「去授权」按钮；ON_RESUME 刷新状态；底部「下一步」始终可点（授权非阻断，未授权仅影响保活强度，文案说明）。

**C5. `app/src/main/java/com/cnc/host/receiver/BootReceiver.kt`**
- 接受 BOOT_COMPLETED 与 QUICKBOOT_POWERON；保持前台拉起 MainActivity（runCatching）。

**C6. `app/src/main/java/com/cnc/host/ui/setup/KioskSettingsViewModel.kt`**
- `resetRegistration()` 重写：`SetupPrefs.clearRegistration()` + `ServerPrefs.clearAuth()` + `setExited(false)` → 直接 `startActivity(MainActivity, NEW_TASK|CLEAR_TASK)`（应用在前台，允许启动，回到配网页）。删除 AlarmManager/exitProcess 方案。

**C7. `app/src/main/java/com/cnc/host/sync/ServerPrefs.kt`**
- 新增 `clearAuth(ctx)`：token→""、deviceId→"tablet-1"、enabled→false（保留 baseUrl 便于重新注册沿用地址）。

**C8. `app/src/main/java/com/cnc/host/sync/ServerDtos.kt`**
- `DeviceInfoDto` 增加 `token: String = ""`。

**C9. `app/src/main/java/com/cnc/host/ui/setup/RegisterViewModel.kt`**
- 注册成功：`ServerPrefs.save(baseUrl=info.serverAddress, token=info.token, deviceId=info.id, enabled=true)`；注释更新。

**C10. `app/src/main/java/com/cnc/host/setup/DeviceHeartbeat.kt`**
- 间隔 30_000 → 10_000（N7）。
- 心跳返回后：若本地 ServerPrefs.token 为空而响应 token 非空 → 回填 token/deviceId/enabled=true（老设备自愈，无需重新注册）。
- 每次心跳顺带 `GET /config/version`（经 SyncManager 轻量探测），版本变化才 `syncNow` —— 权限/配置后台改完 10 秒内自动生效（N6 体验闭环）。
- 心跳成功/失败联动 `SyncManager` 的 online 状态刷新（`SyncManager.refresh` + online 标记），任务页徽标实时准确。

**C11. 新建 `app/src/main/java/com/cnc/host/ui/components/KioskKeyboard.kt`**（N5 核心）
- `KioskTextField(value, onValueChange, modifier, label: (@Composable ()->Unit)?, placeholder, keyboardType: KeyboardType, isPassword: Boolean, singleLine)`：只读描边框，显示值（密码掩码 •），点击弹出键盘面板；不获取真实输入焦点 → 系统 IME 不弹出。
- 键盘面板（底部大按钮 ≥56dp，适配戴手套/工业平板）：
  - 数字页：1-9、0、`.`、`-`、`:`（IP/端口/参数场景）；`⌫`、`清空`、`完成`。
  - 字母页：QWERTY 三行 + 大小写切换 + 空格；`123` 切数字页。
  - 常用符号并入数字页：`.` `-` `_` `:` `/` `@`。
  - 顶部实时回显当前值（密码掩码）+ 字段 label。
- 中文输入不在范围内（注册/配网/参数均为数字与 ASCII；局限写入汇报）。

**C12. 替换 30 处 OutlinedTextField → KioskTextField（11 个文件）**
- RegisterScreen(4)、NetworkSetupScreen(1，WiFi 密码)、KioskSettingsOverlay(2)、AdvancedHubScreen PinDialog(1)、ServerSyncScreen(5，口令框 label 改为「设备令牌/管理口令（注册后自动填充）」)、SettingsScreen(1)、DeviceScreen(3)、PartScreen(1)、DrawingParseScreen(2)、CommScreen(5)、MachiningScreen(1)。
- 参数映射：`keyboardOptions=KeyboardOptions(keyboardType=X)` → `keyboardType=X`；`visualTransformation=PasswordVisualTransformation()` → `isPassword=true`；清理因此不再使用的 import。

**C13. `app/src/main/java/com/cnc/host/setup/WifiSetup.kt`（lint）**
- L96：`caps.transportInfo` 包 `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)`，低版本返回 null（显示「WiFi 已连接」）。
- `startScan` 加 `@SuppressLint("MissingPermission")`（运行时已在 `hasScanPermission` 校验）。

## 验证

1. `$env:JAVA_HOME='C:\Users\28659\.local\share\java\jdk-17'; .\gradlew test` → 现有 127 全过 + 新增 ApkScanner 测试。
2. `.\gradlew build` → lintDebug 0 错误（原 4 个修复）。
3. 编译期核查：全部 OutlinedTextField 引用清零（grep）。
4. 真机/联调项（标注待用户验收）：状态栏沉浸、防退出、清除数据回注册、开机自启、内嵌键盘、任务页在线徽标与 10s 同步、后台 25s 离线判定、一键发布 APK。
5. git：功能分支 `feat/kiosk-20261028` → 推送（命令级代理）→ 开 PR，不直推 main。

## 明确不做

- 不改 device-simulator/cam-engine 等业务模块。
- 不做 REQ-E 系列建议项（验签、OkHttp 等）。
- 中文输入法不做（内嵌键盘覆盖数字+英文+符号）。
