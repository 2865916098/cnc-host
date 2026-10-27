# TASKS — 2026-10-28 Kiosk 修复（执行顺序）

依赖关系：服务端令牌（T1-T3）→ 客户端同步链路（T6-T8）；其余独立。每步完成即时勾掉。

## 服务端
- [x] T1 Database.kt：device_token 列迁移+回填；registerDevice 生成并返回 token；getDevice 带 token；isOnline 90s→25s；新增 isValidDeviceToken
- [x] T2 Models.kt：DeviceInfo += token（先 grep 定位定义文件）
- [x] T3 Routes.kt：bearer 接受设备令牌；新增 /app-latest-candidate、/app-publish-latest
- [x] T4 新建 ApkPublish.kt（AxmlVersionReader + ApkScanner，解析器可注入）
- [x] T5 新建 server 测试 ApkScannerTest（必要时 build.gradle.kts 补 kotlin("test")）
- [x] T6 static/app.js：离线提示 90s→25s；版本发布面板加「最新构建卡+一键发布」并自动填版本号

## 客户端
- [x] T7 AndroidManifest.xml：ACCESS_COARSE_LOCATION、SYSTEM_ALERT_WINDOW、REQUEST_IGNORE_BATTERY_OPTIMIZATIONS、QUICKBOOT_POWERON、excludeFromRecents
- [x] T8 MainActivity.kt：沉浸式隐藏系统栏+onWindowFocusChanged 重隐；showWhenLocked/turnScreenOn；SOFT_INPUT_ALWAYS_HIDDEN；引导 3 步（插入权限步）
- [x] T9 新建 setup/KioskPermissions.kt（悬浮窗/电池/自启 intent 工厂）
- [x] T10 新建 ui/setup/PermissionsSetupScreen.kt（第 2 步权限引导，非阻断）
- [x] T11 BootReceiver.kt：QUICKBOOT_POWERON
- [x] T12 KioskSettingsViewModel.resetRegistration 改为前台 in-place 重启（清 SetupPrefs+ServerPrefs.clearAuth，NEW_TASK|CLEAR_TASK）
- [x] T13 ServerPrefs.kt：clearAuth()；ServerDtos.kt：DeviceInfoDto += token
- [x] T14 RegisterViewModel：注册成功写 token/deviceId/enabled=true
- [x] T15 DeviceHeartbeat：10s；token 自愈回填；config/version 探测→变化即 syncNow
- [x] T16 新建 ui/components/KioskKeyboard.kt（KioskTextField+数字/字母键盘面板）
- [x] T17 替换 11 文件 30 处 OutlinedTextField→KioskTextField（含参数映射与 import 清理；ServerSyncScreen 口令 label 改文案）
- [x] T18 WifiSetup.kt：transportInfo API29 门控 + @SuppressLint("MissingPermission")

## 收尾
- [x] T19 gradlew test 全绿（JDK17）
- [ ] T20 gradlew build 全绿（lint 0 错误）
- [ ] T21 grep 验证 OutlinedTextField=0；需求总表核查更新
- [ ] T22 功能分支推送+开 PR；汇报（改动/测试/待真机验收清单）
