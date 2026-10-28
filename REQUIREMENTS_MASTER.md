# REQUIREMENTS_MASTER — CNC Host 需求总表

- 生成时间：2026-10-28
- 分级：【正式】= 用户书面确认（PRD/README 原话/2026-10-28 指令）；【会话】= 开发对话提出、已实现、无书面变更单；【建议】= Trae/ChatGPT 提出，未经用户确认。
- 状态取值：✅T 有测试证据 / ✅ 已实现未充分验证 / 🎭 模拟实现 / 🔶 部分实现 / ❌ 未实现 / ❓ 证据不足。

## A 系列：PRD V1.0 原始需求【正式】

来源：`docs/产品需求规格说明书V1.0.md`（成文日期无记录，2026-10-28 01:40 进入 git）。验收标准以 PRD 第十二章为准。

| ID | 需求 | 状态 | 实现/证据 | 缺口 |
|---|---|---|---|---|
| REQ-A01 | 总目标：图纸导入→解析→CAM→仿真→审核→加工→记录全流程，关键环节人工审核 | 🔶 | 链路各环节代码存在（简易模式串起主流程） | 全流程端到端测试缺失；审核判据未系统验证 |
| REQ-A02 | 模拟先行：控制卡未定不阻塞开发；模拟数据明确标注 | ✅T | device-simulator 52 单测；`isSimulated` 18 处引用 | 真实控制卡接入（REQ-A13）未开始 |
| REQ-A03 | 16 模块架构划分 | 🔶 | 落地 9 模块；drawing/geometry/recognition/simulation/automation/task-engine/storage/logging/settings 未独立成模块（功能并入 app 与现有模块） | 模块边界与 PRD 有偏差，需确认接受现状 |
| REQ-A04 | 17 页面详细需求（PRD 第四章） | 🔶 | 简易 4 屏+高级 19 屏均存在 | 高级模式未系统性走查；通讯配置/系统设置为占位（FEATURE_MATRIX 四） |
| REQ-A05 | DXF 解析（LINE/ARC/CIRCLE/LWPOLYLINE；SPLINE 验证后支持） | ✅T | dxf-parser 23 单测 | POLYLINE/SPLINE=UnsupportedEntity |
| REQ-A06 | PDF/图片 OCR 图纸导入 | ❌ | 无 recognition 模块 | PRD 5.2/5.3 全部 |
| REQ-A07 | CAM 粗车/精车/进退刀/行程与过切检查 | ✅T | cam-engine 15 单测 | 不支持特征的报告完整性；真实机床未验证 |
| REQ-A08 | 刀路 2D 仿真（播放/单步/与加工同版本刀路） | 🔶 | SimulationScreen 存在 | 版本失效联动未确认（PRD 9.2） |
| REQ-A09 | 加工审核清单（16 项检查，人主动确认） | 🔶 | ReviewScreen 存在 | 各检查项真实判据未验证 |
| REQ-A10 | 自动化状态机（送料→夹紧→切削→退刀→松料，反馈确认，禁止固定延时） | 🎭✅T | AutomationFlowSimulator 6 单测（模拟反馈） | 真实 IO 反馈未接 |
| REQ-A11 | 暂停/恢复（状态机级，含恢复资格判断） | ❌ | 仅受控停止+急停（README 自报） | PRD 7.3/7.4 全部 |
| REQ-A12 | 统一设备接口（connect/readStatus/sendMotionCommand 等）+ 命令队列终态/禁盲重发 | ✅T | device-api + SimulatedCommandQueue 20 单测 | 无真实实现 |
| REQ-A13 | 真实控制卡接入（USB 串口/TCP，阶段八） | ❌ | 无 device-serial/device-tcp | 前置：型号/协议/IO 表（等用户） |
| REQ-A14 | 数据管理与版本一致性（任务绑定各版本，参数变更使旧审核失效） | 🔶 | core-model + Room + 服务端 SQLite + config_version | 版本失效联动未系统化 |
| REQ-A15 | 报警中心与操作日志 | 🎭🔶 | 模拟报警可用；OperationLog 模型存在 | 报警确认不同步服务端；日志导出未确认 |
| REQ-A16 | 权限分级（操作工/设备管理员） | ❌ | 单管理员账号现状 | 与 PRD 4.17 两级模型不一致，待确认 |
| REQ-A17 | 测试与验收体系（第十二章样例库） | 🔶 | 127 单测 | 图纸/CAM/自动化异常样例库未成体系；无 UI/集成测试 |
| REQ-A18 | 交付物清单（第十四章 20 项） | 🔶 | APK/源码/README/工程已具备 | 手册类、设备接入指南、联调验收记录缺失 |

## B 系列：C/S 升级需求【正式】（用户原话，README 开发上下文 L201-210）

| ID | 需求 | 状态 | 实现/证据 | 验收 |
|---|---|---|---|---|
| REQ-B01 | Kiosk：开机自启、始终前台、闪退自动重进 | ✅ | BootReceiver/KioskService/CncApplication | 真机：FGS 修复后 2 分钟 0 崩溃（仅 MIUI 验证） |
| REQ-B02 | 首启网络配置（WiFi/移动数据，确保联网） | ✅ | NetworkSetupScreen→WifiSetup（App 内连接） | 真机走通；系统弹一次确认框（WifiNetworkSpecifier 限制） |
| REQ-B03 | 设备注册（单位/姓名/电话/服务器地址，后台可见归属与在线情况） | ✅ | RegisterScreen + /devices/register + devices 表 | 真机全流程 |
| REQ-B04 | 后台按设备配置功能权限（默认全开，可自定义） | ✅ | permissions 字段 + 心跳下发 + 客户端显隐 | 真机验证显隐生效 |
| REQ-B05 | 客户端注册时填服务器地址 | ✅ | 只填 IP 自动补全；支持局域网自动搜索 | 真机 |
| REQ-B06 | 右上角设置（管理员密码；可改 IP；可退出到安卓桌面） | ✅ | KioskSettingsOverlay；退出=彻底退出 | 真机 |
| REQ-B07 | 允许在此基础上拓展完善 | — | 授权性条款 | — |

## C 系列：后续会话新增【会话】（无书面变更单，需求原文待补录）

| ID | 需求 | 状态 | 实现/证据 | 待确认 |
|---|---|---|---|---|
| REQ-C01 | 清除数据重新注册 | ✅ | 设置入口内清除+重启回注册流程 | 真机已验证；验收标准待书面化 |
| REQ-C02 | 设备唯一注册（hardwareId，重复 409，须后台注销） | ✅ | devices.hardware_id 部分唯一索引；迁移曾崩溃已修复 | 迁移无回归测试 |
| REQ-C03 | APK 自动更新（发布→弹窗→下载→SHA-256→安装） | 🔶 | /app-publish、/app-version、/app-download、AppUpdate.kt | 无验签（P0）；安装回读未验证 |
| REQ-C04 | 后台修改已注册设备信息 | ✅ | 设备编辑接口+后台界面 | 真机/curl |
| REQ-C05 | 后台显示客户端版本号+实时在线状态（90s 判离线） | ✅ | versionCode 3/1.2.0 上报；last_seen 判定 | 真机 |
| REQ-C06 | 客户端沉浸式：状态栏不遮挡页面（2026-10-28 指令 N1） | ✅ | MainActivity hideSystemBars + onWindowFocusChanged 重隐，BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE | 待真机验收 |
| REQ-C07 | Kiosk 防退出加强（2026-10-28 指令 N2） | ✅ | KioskService 700ms 看门狗 + excludeFromRecents + showWhenLocked/turnScreenOn + SYSTEM_ALERT_WINDOW 引导 | 待真机验收 |
| REQ-C08 | 清除数据后重新进入注册流程（2026-10-28 指令 N3） | ✅ | KioskSettingsViewModel.resetRegistration 改前台 in-place 重启（清 SetupPrefs+ServerPrefs.clearAuth，NEW_TASK\|CLEAR_TASK） | 待真机验收 |
| REQ-C09 | 开机自启可靠落地（2026-10-28 指令 N4） | ✅ | BootReceiver 增加 QUICKBOOT_POWERON；新增第 2 步权限引导（悬浮窗/电池白名单/厂商自启页跳转） | 待真机验收（厂商自启设置项需手动开） |
| REQ-C10 | 内嵌输入法，禁用系统输入法（2026-10-28 指令 N5） | ✅ | KioskTextField + KioskKeyboardDialog（数字/QWERTY 双面板）；全局 SOFT_INPUT_STATE_ALWAYS_HIDDEN；30 处输入框全替换 | 待真机验收 |
| REQ-C11 | 任务菜单连接状态与配置同步修复（2026-10-28 指令 N6） | ✅ | 根因=注册未存 token/deviceId；服务端签发 device_token（注册返回+心跳自愈+老库回填），bearer 三源鉴权；心跳 10s 联动徽标 + config/version 探测变化即全量同步 | 待真机验收 |
| REQ-C12 | 在线/离线状态实时更新（2026-10-28 指令 N7） | ✅ | 心跳 30s→10s；离线判定 90s→25s；后台设备页 3s 轮询保持 | 待真机验收 |
| REQ-C13 | 版本发布默认最新 APK 一键发布（2026-10-28 指令 N8） | ✅ | AxmlVersionReader（二进制 AXML 解析 versionCode/versionName）+ ApkScanner 扫描构建目录；/app-latest-candidate + /app-publish-latest；后台「一键发布」按钮，手动上传折叠兜底 | 待验收（服务端跑一遍发布） |
| REQ-C14 | 屏幕常亮（工业平板加工现场阻塞缺口） | ✅ | MainActivity 加 FLAG_KEEP_SCREEN_ON | 待真机验收 |
| REQ-C15 | 设备被后台注销后自动回注册（现场运维链路） | ✅ | 心跳收到 404 → 清本地注册+鉴权 → 前台重启回注册流程 | 待真机验收 |
| REQ-C16 | 操作日志服务端闭环（A15 缺口修复） | ✅ | 服务端 operation_logs 表 + POST/GET/DELETE 三个 API；客户端 Room 本地缓存 + 心跳 10s 自动补传；8 处关键路径埋点（加工启动/完成/急停、心跳、APK 更新、清除数据、应用启动） | 已 curl 验证 |
| REQ-C17 | 后台全套用户与权限管理（2026-10-28 追加需求） | ✅ | users 表（PBKDF2 密码哈希、角色 SUPER_ADMIN/ADMIN/OPERATOR、模块级 permissions）；7 个用户 API；登录响应带角色权限；路由级权限拦截；后台「用户管理」页（列表/新建/编辑/重置密码/禁用/删除）+ 菜单按权限显隐 + 右上角改密码 | 已端到端验证 28 项；旧库自动 seed 超管 admin/123456 |

## D 系列：修改/替换记录【正式/会话混合】

| ID | 变更 | 旧→新 | 证据 | 残留 |
|---|---|---|---|---|
| REQ-D01 | 管理后台默认密码 | admin123 → 123456（旧库自动迁移） | Database.kt L217/L985-987 | README L233 仍写 admin123（文档过时） |
| REQ-D02 | 「退出软件」语义 | 20 秒退出窗口 → 彻底退出 | README L178 | `setExitWindow` 旧用法残留待清理 |
| REQ-D03 | Kiosk 保活策略 | 后台直接起 FGS → 仅前台启动 | KioskService.kt 等注释 | 其他 ROM 未验证 |
| REQ-D04 | Git 基线策略 | 本地/远程功能合并方案 → 本地整体替换远程（不合并） | 2026-10-28 用户指令 | 覆盖推送待用户最终确认 |

## E 系列：技术建议【建议】（未获用户确认，不得当作需求）

| ID | 建议 | 提出方 | 状态 |
|---|---|---|---|
| REQ-E01 | APK 更新改服务端私钥签名+客户端验签 | Trae（报告七-5） | 待用户决策（P0） |
| REQ-E02 | SQLite 每日快照备份+启动完整性检查 | Trae | 待决策（P0） |
| REQ-E03 | 首次启动强制改密；CNC_ADMIN_TOKEN 未设置时随机生成 | Trae | 待决策（P0） |
| REQ-E04 | 网络层 HttpURLConnection→OkHttp | Trae | P3 待议 |
| REQ-E05 | server/Database.kt 拆分 DAO；core-model 统一生成 DTO | Trae | P3 待议 |
| REQ-E06 | detekt/ktlint 静态检查 | Trae | P3 待议（注：AGP lint 实际已在跑，见 PROJECT_STATUS） |
| REQ-E07 | 远程敏感历史处置三选一（仅轮换/重写历史/删库重建） | Trae | 待用户决策 |
| REQ-E08 | `_tracking/` 任务报告目录机制 | Trae（DEVELOPMENT_WORKFLOW） | 待用户批准创建 |

## F 系列：工程基线决策（2026-10-28 用户明确确认）【正式】

| ID | 决策 |
|---|---|
| REQ-F01 | `E:\AI软件开发\cnc-host-main\cnc-host` 为唯一权威代码来源 |
| REQ-F02 | GitHub 仓库 2865916098/cnc-host 保持 Public |
| REQ-F03 | 远程现有代码不保留，确认后由本地整体替换 |
| REQ-F04 | 不做新旧分支功能合并 |
| REQ-F05 | 敏感处理原则：普通源码公开不阻塞，只处理实际账户/数据/服务安全风险（真实 DB、有效密钥、签名私钥不上传） |
| REQ-F06 | force push 前必须汇报目标分支、远程变化、回滚方式并等确认（已完成汇报，等待中） |

## 统计

- 正式需求：A 18 + B 7 + F 6 = 31 项；会话新增 13 项（C01-C13，其中 C06-C13 为 2026-10-28 指令 N1-N8 落地）；变更 4 项；建议 8 项。
- 实现状态（A+B 25 项功能需求口径）：✅T 6、✅ 6、🔶 8、🎭 1、❌ 4。
- 矛盾/不一致清单：见 OPEN_QUESTIONS.md 第三节。
