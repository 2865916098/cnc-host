# CNC Host 服务端接口文档

- 文档版本：v1.0
- 生成日期：2026-10-28
- 代码基线：`cnc-host-git-baseline`（`server/src/main/kotlin/com/cnc/server/Routes.kt`、`Models.kt`、`Main.kt`、`ApkPublish.kt`）
- 接口总量：**共 51 个 HTTP 接口 + 1 个 WebSocket**

> 本文档全部内容来自服务端真实代码。字段名、默认值、错误码、拦截规则均与代码一一对应；静态资源 `/admin`（网页管理后台）不计入接口数量。

---

## 0. 基础约定

### 0.1 基础地址

```
http://<服务器IP>:<PORT>
```

| 配置项 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| 监听端口 | `PORT` | `8080` | 服务监听 `0.0.0.0:<PORT>` |
| 管理口令 | `CNC_ADMIN_TOKEN` | `cnc-admin-2026` | 环境变量为空串时回落到默认值，部署时必须修改 |
| SQLite 路径 | `CNC_DB` | `data/cnc-server.db` | 服务端数据库文件 |
| 已发布 APK 路径 | `CNC_APK` | `data/apk/cnc-host-update.apk` | 手动上传 / 一键发布后 APK 的落盘位置 |
| APK 构建产物扫描目录 | `CNC_APK_DIR` | 见 3.11 | 多个目录用 `;` 分隔 |

所有业务接口统一使用 `/api/v1` 前缀（`GET /` 与 `/admin/` 除外），最终地址形如：

```
http://192.168.1.10:8080/api/v1/config
```

CORS 已开启（`anyHost()`），允许请求头 `Authorization`、`Content-Type`、`X-Auth-Token`。

### 0.2 统一响应格式

服务端 JSON 由 kotlinx.serialization 序列化（`ignoreUnknownKeys = true`、`encodeDefaults = true`、`prettyPrint = true`），因此：

- 客户端可以传多余字段，服务端会忽略；
- 响应会完整输出所有带默认值的字段；
- 响应为格式化（带缩进）的 JSON。

通用结果体 `SimpleResult`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| ok | Boolean | 是否成功，默认 `true` |
| message | String | 提示信息，默认 `""` |
| id | String | 关联 ID，默认 `""`（仅 `GET /api/v1/health` 使用，值为 `cnc-server`） |

```json
{
  "ok": true,
  "message": "设备配置已保存",
  "id": ""
}
```

其余响应体均直接返回 DTO 本体（如 `DeviceConfig`、`List<ToolConfig>`、`DeviceInfo`），不做 `data` 包装。

### 0.3 鉴权总览

服务端有**三种凭据**，通过请求头 `Authorization: Bearer <凭据>` 传递。

| 凭据 | 来源 | 能访问什么 | 权限级别 |
| --- | --- | --- | --- |
| 环境变量管理口令 | 启动时的 `CNC_ADMIN_TOKEN`（默认 `cnc-admin-2026`） | 全部管理接口（不受模块权限限制）；`/ws/status` | 超管虚拟账号（`id=""`，`role=SUPER_ADMIN`） |
| 后台登录会话 token | `POST /api/v1/admin/login` 返回的 `token` | 全部管理接口，但受模块权限拦截（见第 6 节）；`/ws/status` | 按 users 表中的 `role` / `permissions` |
| 设备令牌 | `POST /api/v1/devices/register` 返回的 `token`（心跳响应也会回带） | **仅** GET 类非账号接口 + `POST /api/v1/tasks`、`POST /api/v1/status`；另可调用 `POST /api/v1/logs`（该接口只认设备令牌） | 最小权限，等同"只读配置 + 回传数据" |

要点：

- 会话 token 有效期 7 天（`SESSION_TTL_MS = 7 * 24 * 3600 * 1000`），登录响应里的 `expiresAt` 为毫秒时间戳；服务端每次校验同时检查账号是否仍启用，账号被禁用/删除后会话立即失效。
- 设备令牌**不能**访问 `/ws/status`（WebSocket 只认管理口令与会话 token）。
- 设备令牌**不能**访问任何包含 `users` 的路径（含 `GET /api/v1/users/me`），也不能访问 `admin-password`。
- 未携带凭据或凭据无效时，Ktor 的 `bearer` 认证直接返回 **401**（默认空响应体）；通过认证但权限不足时返回 401/403 的 `SimpleResult` JSON（见第 5 节）。

### 0.4 权限模型（模块 key）

可授权模块 key（与前端导航一致）：

`overview`、`device`、`tools`、`materials`、`parts`、`plans`、`drawings`、`devices`、`tasks`、`users`、`app`、`logs`

角色（`role`）取值仅三种：

| 角色 | 说明 |
| --- | --- |
| `SUPER_ADMIN` | 超级管理员，跳过所有模块权限校验 |
| `ADMIN` | 管理员，按 `permissions` 列表授权 |
| `OPERATOR` | 操作员，按 `permissions` 列表授权 |

路由 → 模块 key 对应关系（`requiredPermission()` 实现）：

| 路径特征（相对 `/api/v1`） | 所需模块 | 备注 |
| --- | --- | --- |
| `/users/me`、`/users/me/password` | 无 | 查看/修改本人信息属基础能力，不受 `users` 模块限制 |
| 其他含 `/users`、含 `/admin-password` | `users` | 含 `GET /users` 列表 |
| 含 `/app-publish`、`/app-latest-candidate` | `app` | `POST /app-publish-latest` 因含 `/app-publish` 同样归 `app` |
| 含 `/materials` | `materials` | |
| 含 `/parts`、`/blanks` | `parts` | |
| 含 `/plans`、`/process-steps` | `plans` | 工艺步骤随方案整体保存，无独立路由 |
| 含 `/drawings`、`/corrections` | `drawings` | |
| 含 `/tools` | `tools` | |
| 含 `/devices` | `devices` | |
| 含 `/tasks` | `tasks` | |
| 含 `/logs` | `logs` | |
| `PUT /device` | `device` | 仅保存全局设备配置时校验 |
| `/config`、`/config/*`、`/auth/check`、`/status`、`/admin/logout` | 无 | 任何已登录账号可访问 |
| 其他（如 `GET /app-version-info`） | 无 | 返回 `null`，不校验模块权限 |

拦截规则（`Routes.kt` 第 309–345 行，仅作用于 `authenticate("admin")` 内的路由）：

1. 环境变量管理口令：直接放行。
2. 设备令牌：`GET` 且路径不含 `/users`、不含 `/admin-password` → 放行；`POST` 且路径以 `/tasks` 或 `/status` 结尾 → 放行；其余一律 **403**（`设备令牌仅允许同步配置与回传数据`）。
3. 会话 token 查不到对应用户（账号被禁用/删除）→ **401**（`登录已失效`）。
4. `SUPER_ADMIN`：放行。
5. 普通用户：
   - **GET 只读请求宽松放行（账号管理除外）**：`GET` 且路径不含 `/users` 时，只要 `permissions` 非空即放行（不要求包含对应模块）；`permissions` 为空则 403。
   - 其他请求（含 `GET /users` 与所有写操作）：`permissions` 必须包含所需模块 key，否则 **403**（`无权限执行该操作`）。
   - 若路由映射到"无所需模块"（返回 `null`），直接放行。

---

## 接口总览（51 个 HTTP）

| # | 方法 | 路径 | 鉴权 |
| --- | --- | --- | --- |
| 1 | GET | `/` | 无 |
| 2 | GET | `/api/v1/health` | 无 |
| 3 | POST | `/api/v1/admin/login` | 无 |
| 4 | POST | `/api/v1/devices/register` | 无 |
| 5 | GET | `/api/v1/devices/{id}` | 无 |
| 6 | POST | `/api/v1/devices/{id}/heartbeat` | 无 |
| 7 | GET | `/api/v1/app-version` | 无 |
| 8 | GET | `/api/v1/app-download` | 无 |
| 9 | POST | `/api/v1/devices/{id}/verify-admin` | 无 |
| 10 | POST | `/api/v1/logs` | bearer device |
| 11 | GET | `/api/v1/users/me` | bearer admin |
| 12 | GET | `/api/v1/users` | bearer admin |
| 13 | POST | `/api/v1/users` | bearer admin |
| 14 | PUT | `/api/v1/users/{id}` | bearer admin |
| 15 | DELETE | `/api/v1/users/{id}` | bearer admin |
| 16 | POST | `/api/v1/users/{id}/reset-password` | bearer admin |
| 17 | PUT | `/api/v1/users/me/password` | bearer admin |
| 18 | GET | `/api/v1/auth/check` | bearer admin |
| 19 | POST | `/api/v1/admin/logout` | bearer admin |
| 20 | GET | `/api/v1/config` | bearer admin |
| 21 | GET | `/api/v1/config/version` | bearer admin |
| 22 | PUT | `/api/v1/device` | bearer admin |
| 23 | POST | `/api/v1/tools` | bearer admin |
| 24 | DELETE | `/api/v1/tools/{id}` | bearer admin |
| 25 | POST | `/api/v1/materials` | bearer admin |
| 26 | DELETE | `/api/v1/materials/{id}` | bearer admin |
| 27 | POST | `/api/v1/parts` | bearer admin |
| 28 | DELETE | `/api/v1/parts/{id}` | bearer admin |
| 29 | POST | `/api/v1/blanks` | bearer admin |
| 30 | DELETE | `/api/v1/blanks/{id}` | bearer admin |
| 31 | POST | `/api/v1/plans` | bearer admin |
| 32 | DELETE | `/api/v1/plans/{id}` | bearer admin |
| 33 | POST | `/api/v1/drawings` | bearer admin |
| 34 | DELETE | `/api/v1/drawings/{id}` | bearer admin |
| 35 | POST | `/api/v1/corrections` | bearer admin |
| 36 | GET | `/api/v1/tasks` | bearer admin |
| 37 | POST | `/api/v1/tasks` | bearer admin |
| 38 | GET | `/api/v1/logs` | bearer admin |
| 39 | DELETE | `/api/v1/logs` | bearer admin |
| 40 | GET | `/api/v1/devices` | bearer admin |
| 41 | GET | `/api/v1/devices/org-summary` | bearer admin |
| 42 | PUT | `/api/v1/devices/{id}` | bearer admin |
| 43 | DELETE | `/api/v1/devices/{id}` | bearer admin |
| 44 | PUT | `/api/v1/devices/{id}/permissions` | bearer admin |
| 45 | GET | `/api/v1/app-version-info` | bearer admin |
| 46 | POST | `/api/v1/app-publish` | bearer admin |
| 47 | GET | `/api/v1/app-latest-candidate` | bearer admin |
| 48 | POST | `/api/v1/app-publish-latest` | bearer admin |
| 49 | GET | `/api/v1/status` | bearer admin |
| 50 | POST | `/api/v1/status` | bearer admin |
| 51 | POST | `/api/v1/admin-password` | bearer admin |

另有 1 个 WebSocket：`/ws/status`。

---

## 一、开放接口（无需鉴权）

### 1.1 GET /

- 鉴权：无
- 说明：服务存活提示，返回纯文本（`Content-Type: text/plain`）。
- 响应示例：

```
CNC 服务端运行中。管理后台：/admin/
```

### 1.2 GET /api/v1/health

- 鉴权：无
- 说明：健康检查。
- 响应 `SimpleResult`：

```json
{
  "ok": true,
  "message": "ok",
  "id": "cnc-server"
}
```

- 错误码：无业务错误码。

### 1.3 POST /api/v1/admin/login

- 鉴权：无
- 说明：后台账号密码登录，成功后签发会话 token。校验顺序：先查 `users` 表（仅启用账号）；若 `users` 表中不存在该用户名，才回落到数据库 meta 中的旧版管理员账号（默认用户名 `admin`）。
- 请求体 `AdminLoginRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| username | String | 是 | 登录用户名 |
| password | String | 是 | 登录密码 |

```json
{
  "username": "admin",
  "password": "123456"
}
```

- 成功响应 200 `AdminLoginResult`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| ok | Boolean | `true` |
| message | String | `登录成功` |
| token | String | 会话 token，后续放 `Authorization: Bearer <token>` |
| username | String | 登录用户名 |
| expiresAt | Long | 过期时间（毫秒时间戳），= 当前时间 + 7 天 |
| role | String | `SUPER_ADMIN` / `ADMIN` / `OPERATOR`；旧版回退登录按 `SUPER_ADMIN` |
| permissions | List<String> | 模块权限列表；旧版回退登录为空列表 |
| displayName | String | 显示名；无对应用户时等于用户名 |

```json
{
  "ok": true,
  "message": "登录成功",
  "token": "3f8c9a1e5b7d4c2e9a6f0b1c2d3e4f5a",
  "username": "admin",
  "expiresAt": 1793577600000,
  "role": "SUPER_ADMIN",
  "permissions": [],
  "displayName": "admin"
}
```

- 错误码：
  - 401：账号或密码错误。响应体为 `AdminLoginResult`（非 `SimpleResult`）：`{"ok": false, "message": "账号或密码错误"}`。账号被禁用同样返回 401。

- curl：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
```

### 1.4 POST /api/v1/devices/register（设备激活）

- 鉴权：无（激活前客户端尚无任何凭据）
- 说明：设备激活（注册）并签发设备令牌。**按 `hardwareId` 幂等**：同一硬件标识重复激活时，更新单位/联系人/电话/地区/服务端地址/版本并返回 200，**保留原 `id`、`registeredAt` 与设备令牌**；不再返回 409（客户端清除数据后本地令牌丢失，若拒绝会导致设备永久无法使用）。`hardwareId` 为空时按新建记录处理。激活成功即写入 `last_seen = 当前时间`、`online = 1`。
- 请求体 `RegisterRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| unitName | String | 否 | 单位名称，默认 `""` |
| contactName | String | 否 | 联系人，默认 `""` |
| phone | String | 否 | 电话，默认 `""` |
| province | String | 否 | 省份（如 `江苏省`），默认 `""`；客户端激活时必填 |
| city | String | 否 | 城市（如 `苏州市`），默认 `""`；客户端激活时必填 |
| serverAddress | String | 否 | 客户端记录的服务端地址，默认 `""` |
| deviceId | String | 否 | 设备 ID；为空时服务端生成 `dev-<8位>`。`hardwareId` 为空时，同一 id 再次激活会覆盖原设备记录并签发新令牌 |
| hardwareId | String | 否 | 硬件唯一标识（Android ID），**激活按此幂等**；为空则不查重 |
| appVersion | String | 否 | 客户端版本号，如 `1.1.0`，默认 `""` |

```json
{
  "unitName": "一号车间",
  "contactName": "张三",
  "phone": "13800000000",
  "province": "江苏省",
  "city": "苏州市",
  "serverAddress": "http://192.168.1.10:8080",
  "deviceId": "tablet-1",
  "hardwareId": "a1b2c3d4e5f6",
  "appVersion": "1.1.0"
}
```

- 成功响应 200 `DeviceInfo`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | String | 设备 ID |
| unitName | String | 单位名称 |
| province | String | 省份 |
| city | String | 城市 |
| contactName | String | 联系人 |
| phone | String | 电话 |
| serverAddress | String | 服务端地址（回显请求值） |
| registeredAt | Long | 注册时间（毫秒） |
| lastSeen | Long | 最后在线时间（毫秒），注册时为当前时间 |
| online | Boolean | 注册返回恒为 `true`；查询时为 25 秒内有心跳的实时计算结果 |
| permissions | ClientPermissions | 设备功能权限（新设备默认全开，见 1.4 附表） |
| token | String | 设备令牌（32 位无连字符 UUID） |

```json
{
  "id": "tablet-1",
  "unitName": "一号车间",
  "province": "江苏省",
  "city": "苏州市",
  "contactName": "张三",
  "phone": "13800000000",
  "serverAddress": "http://192.168.1.10:8080",
  "registeredAt": 1792969600000,
  "lastSeen": 1792969600000,
  "online": true,
  "permissions": {
    "taskSelect": true, "previewStart": true, "monitor": true, "alarms": true,
    "advanced": true, "serverSync": true, "drawings": true, "cam": true,
    "machining": true, "io": true, "history": true
  },
  "token": "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
}
```

`ClientPermissions`（11 个开关，全部默认 `true`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| taskSelect | Boolean | 任务选择 |
| previewStart | Boolean | 预览/启动 |
| monitor | Boolean | 实时监控 |
| alarms | Boolean | 报警 |
| advanced | Boolean | 高级功能 |
| serverSync | Boolean | 服务端同步 |
| drawings | Boolean | 图纸 |
| cam | Boolean | CAM 生成 |
| machining | Boolean | 加工 |
| io | Boolean | IO 控制 |
| history | Boolean | 历史记录 |

- 错误码：无业务错误码；同一 `hardwareId` 重复激活按幂等更新返回 200，不再返回 409。

- curl：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/devices/register \
  -H "Content-Type: application/json" \
  -d '{"unitName":"一号车间","contactName":"张三","phone":"13800000000","province":"江苏省","city":"苏州市","deviceId":"tablet-1","hardwareId":"a1b2c3d4e5f6","appVersion":"1.1.0"}'
```

### 1.5 GET /api/v1/devices/{id}

- 鉴权：无
- 路径参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 是 | 设备 ID |

- 响应 200 `DeviceInfo`（字段同 1.4，含 `province`、`city`）。`online` 按 `lastSeen` 实时计算：25 秒内有心跳为 `true`。

```json
{
  "id": "tablet-1",
  "unitName": "一号车间",
  "province": "江苏省",
  "city": "苏州市",
  "contactName": "张三",
  "phone": "13800000000",
  "serverAddress": "http://192.168.1.10:8080",
  "registeredAt": 1792969600000,
  "lastSeen": 1793056000000,
  "online": true,
  "permissions": {
    "taskSelect": true, "previewStart": true, "monitor": true, "alarms": true,
    "advanced": true, "serverSync": true, "drawings": true, "cam": true,
    "machining": true, "io": true, "history": true
  },
  "token": "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
}
```
- 错误码：404 `设备不存在`（`SimpleResult`）。
- curl：

```bash
curl http://127.0.0.1:8080/api/v1/devices/tablet-1
```

### 1.6 POST /api/v1/devices/{id}/heartbeat

- 鉴权：无
- 说明：设备心跳，刷新 `lastSeen` 与在线状态；请求体可省略（缺省按空对象处理）。若携带非空 `appVersion`，同时更新设备版本号。
- 路径参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 是 | 设备 ID |

- 请求体 `HeartbeatRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| appVersion | String | 否 | 客户端版本号，默认 `""`；非空时落库 |

- 响应 200 `DeviceInfo`（含 `token`，老客户端可据此自动补录令牌），字段与示例同 1.4。
- 错误码：404 `设备不存在或已注销`，响应体为 `SimpleResult`。

```json
{
  "ok": false,
  "message": "设备不存在或已注销",
  "id": ""
}
```
- curl：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/devices/tablet-1/heartbeat \
  -H "Content-Type: application/json" \
  -d '{"appVersion":"1.1.0"}'
```

### 1.7 GET /api/v1/app-version

- 鉴权：无
- 说明：客户端自动更新用，查询当前已发布版本元信息。从未发布过版本时返回 404。
- 响应 200 `AppVersionInfo`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| versionCode | Int | 版本号（整数） |
| versionName | String | 版本名，如 `1.2.0` |
| changelog | String | 更新说明 |
| sha256 | String | APK 文件 SHA-256（小写十六进制） |
| sizeBytes | Long | APK 字节数 |
| publishedAt | Long | 发布时间（毫秒） |

```json
{
  "versionCode": 12,
  "versionName": "1.2.0",
  "changelog": "修复断线重连问题",
  "sha256": "9c1f...e7",
  "sizeBytes": 8123456,
  "publishedAt": 1793056000000
}
```

- 错误码：404 `尚未发布版本`（`SimpleResult`）。
- curl：

```bash
curl http://127.0.0.1:8080/api/v1/app-version
```

### 1.8 GET /api/v1/app-download

- 鉴权：无
- 说明：下载当前已发布 APK。仅当 APK 文件存在**且**已有发布记录（`app-version` 非 404）时返回文件，否则 404。
- 响应 200：二进制文件流，响应头 `Content-Disposition: attachment; filename="cnc-host.apk"`。
- 错误码：404 `尚未发布版本`（`SimpleResult` JSON）。
- curl：

```bash
curl -L -o cnc-host.apk http://127.0.0.1:8080/api/v1/app-download
```

### 1.9 POST /api/v1/devices/{id}/verify-admin

- 鉴权：无
- 说明：客户端校验"管理员密码"。该密码来自数据库 meta `admin_password`（默认 `123456`，可由 `POST /api/v1/admin-password` 修改），**与 users 表账号密码无关**，为明文比较。
- 路径参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 是 | 设备 ID，必须先存在 |

- 请求体 `AdminVerifyRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| password | String | 是 | 待校验的管理员密码 |

- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "管理员密码正确", "id": "" }
```

失败时仍是 200，`ok=false`、`message="密码错误"`。

- 错误码：404 `设备不存在`（`SimpleResult`）。
- curl：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/devices/tablet-1/verify-admin \
  -H "Content-Type: application/json" \
  -d '{"password":"123456"}'
```

---

## 二、设备接口（bearer device）

`authenticate("device")` 只接受**设备令牌**（`devices.device_token`），不接受环境变量口令与会话 token。

### 2.1 POST /api/v1/logs

- 鉴权：`Authorization: Bearer <设备令牌>`
- 说明：设备批量上传操作日志。日志列表为空时服务端直接返回成功（不写入任何行）。
- 请求体 `UploadLogsRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| deviceId | String | 是 | 设备 ID |
| logs | List<OperationLogDto> | 是 | 日志数组，可为空数组 |

`OperationLogDto`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| timestamp | Long | 是 | 日志时间（毫秒） |
| level | String | 是 | 级别，如 `INFO` / `WARN` / `ERROR` |
| category | String | 是 | 分类 |
| message | String | 是 | 日志内容 |
| taskId | String? | 否 | 关联任务 ID，可空 |
| details | String? | 否 | 详情，可空 |

```json
{
  "deviceId": "tablet-1",
  "logs": [
    {
      "timestamp": 1792969600000,
      "level": "INFO",
      "category": "SYNC",
      "message": "配置同步完成",
      "taskId": "task-1a2b3c4d",
      "details": "version=42"
    }
  ]
}
```

- 成功响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "已接收 1 条日志", "id": "" }
```

- 错误码：401（无/错令牌）。

- curl：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/logs \
  -H "Authorization: Bearer 0f1e2d3c4b5a69788796a5b4c3d2e1f0" \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"tablet-1","logs":[{"timestamp":1792969600000,"level":"INFO","category":"SYNC","message":"配置同步完成"}]}'
```

---

## 三、管理接口（bearer admin）

`authenticate("admin")` 接受三种凭据：环境变量管理口令、会话 token、设备令牌；随后由 0.4 的拦截器做细粒度授权。以下每节"鉴权"默认均为 `Authorization: Bearer <管理口令或会话 token>`。

### 3.1 账号与用户

> 保护规则汇总（`/api/v1/users*` 系列）：
> 1. 密码长度 **≥ 6**（创建用户、重置密码、修改本人密码均校验）；
> 2. **禁止删除自己**（400）、**禁止禁用自己**（400）；
> 3. **禁止删除/降级/禁用最后一个超级管理员**（400 `系统至少保留一个超级管理员`）；判定条件：目标为超管，且新角色不是超管或 `enabled=false`，且 `countSuperAdmins() <= 1`；
> 4. **非超管不能操作超管**：非超管创建超管（403）、修改超管账号或把账号提升为超管（403）、删除超管（403）、重置超管密码（403）；
> 5. 可见性：`GET /users` 与全部写操作需要 `users` 模块权限；`GET /users/me`、`PUT /users/me/password` 不受该模块限制。

#### 3.1.1 GET /api/v1/users/me

- 说明：返回当前凭据对应的账号信息。
- 响应 200 `SessionUserDto`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | String | 用户 ID；环境变量管理口令为 `""` |
| username | String | 用户名；环境变量管理口令时为 meta 中的 `admin_username`（默认 `admin`） |
| displayName | String | 显示名；环境变量管理口令时为 `超级管理员` |
| role | String | 角色；环境变量管理口令时为 `SUPER_ADMIN` |
| permissions | List<String> | 权限列表；环境变量管理口令时为空列表 |
| superAdmin | Boolean | 是否超管 |

```json
{
  "id": "u-1001",
  "username": "zhangsan",
  "displayName": "张三",
  "role": "ADMIN",
  "permissions": ["overview", "tools", "tasks"],
  "superAdmin": false
}
```

- 错误码：
  - 401 `登录已失效`（会话失效。未携带/无效凭据时由认证层返回 401 空响应）。
  - 403 `设备令牌无权访问账号管理`（设备令牌；实际会先被拦截器以 `设备令牌仅允许同步配置与回传数据` 拦下）。

#### 3.1.2 GET /api/v1/users

- 说明：列出全部用户（含禁用账号）。
- 响应 200 `List<UserDto>`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | String | 用户 ID |
| username | String | 用户名 |
| displayName | String | 显示名 |
| role | String | `SUPER_ADMIN` / `ADMIN` / `OPERATOR` |
| permissions | List<String> | 模块权限列表 |
| enabled | Boolean | 是否启用 |
| createdAt | Long | 创建时间（毫秒） |
| lastLoginAt | Long | 最后登录时间（毫秒），默认 `0` |

```json
[
  {
    "id": "u-1001",
    "username": "zhangsan",
    "displayName": "张三",
    "role": "ADMIN",
    "permissions": ["overview", "tools", "tasks"],
    "enabled": true,
    "createdAt": 1792883200000,
    "lastLoginAt": 1792969600000
  }
]
```

- 错误码：401；403（无 `users` 模块权限）。

#### 3.1.3 POST /api/v1/users

- 说明：创建用户。`displayName` 为空时取用户名。
- 请求体 `CreateUserRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| username | String | 是 | 用户名，不能为空白；服务端会 `trim()` |
| password | String | 是 | 密码，长度 ≥ 6 |
| displayName | String | 否 | 显示名，默认 `""`（落库为用户名） |
| role | String | 否 | 默认 `OPERATOR`，取值必须是三种角色之一 |
| permissions | List<String> | 否 | 模块权限列表，默认 `[]` |

- 响应 200 `UserDto`（创建后的完整记录）：

```json
{
  "id": "3c9e1f52-7a44-4c1d-9b0e-2f8a6d5c7b31",
  "username": "zhangsan",
  "displayName": "张三",
  "role": "ADMIN",
  "permissions": ["overview", "tools", "tasks"],
  "enabled": true,
  "createdAt": 1792969600000,
  "lastLoginAt": 0
}
```
- 错误码：
  - 400 `用户名不能为空` / `密码长度至少 6 位` / `无效的角色`
  - 403 `无权限执行该操作`（非超管创建超管）
  - 409 `用户名已存在`
  - 401 `登录已失效`

- curl：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/users \
  -H "Authorization: Bearer <管理口令或会话token>" \
  -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","password":"abc123","displayName":"张三","role":"ADMIN","permissions":["overview","tools","tasks"]}'
```

#### 3.1.4 PUT /api/v1/users/{id}

- 说明：修改用户资料/角色/权限/启用状态。字段为 `null` 或省略表示不修改；`displayName` 传空白串时保留原值。
- 路径参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 是 | 目标用户 ID |

- 请求体 `UpdateUserRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| displayName | String? | 否 | 显示名；空串视为不变 |
| role | String? | 否 | 新角色，必须合法；省略则不变 |
| permissions | List<String>? | 否 | 新权限列表，省略则不变 |
| enabled | Boolean? | 否 | 启用状态，省略则不变 |

- 响应 200 `UserDto`（更新后的记录）：

```json
{
  "id": "3c9e1f52-7a44-4c1d-9b0e-2f8a6d5c7b31",
  "username": "zhangsan",
  "displayName": "张三",
  "role": "ADMIN",
  "permissions": ["overview", "tools", "tasks"],
  "enabled": false,
  "createdAt": 1792969600000,
  "lastLoginAt": 1793056000000
}
```
- 错误码：
  - 404 `用户不存在`
  - 400 `无效的角色` / `不能禁用自己` / `系统至少保留一个超级管理员`
  - 403 `无权限执行该操作`（非超管操作超管账号，或把账号提升为超管）
  - 401 `登录已失效`

#### 3.1.5 DELETE /api/v1/users/{id}

- 说明：删除用户，并同步清理该用户全部会话（含老版本按用户名关联的会话）。
- 路径参数：`id`（String，必填，目标用户 ID）。
- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "用户已删除", "id": "" }
```

- 错误码：
  - 404 `用户不存在`
  - 400 `不能删除自己` / `系统至少保留一个超级管理员`
  - 403 `无权限执行该操作`（非超管删除超管）
  - 401 `登录已失效`

#### 3.1.6 POST /api/v1/users/{id}/reset-password

- 说明：管理员重置指定用户密码，成功后删除该用户全部会话（强制重新登录）。
- 路径参数：`id`（String，必填，目标用户 ID）。
- 请求体 `ResetPasswordRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| newPassword | String | 是 | 新密码，长度 ≥ 6 |

- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "密码已重置", "id": "" }
```

- 错误码：404 `用户不存在`；400 `密码长度至少 6 位`；403（非超管重置超管密码）；401。

### 3.2 当前账号与登录

#### 3.2.1 PUT /api/v1/users/me/password

- 说明：修改本人密码，需校验原密码；成功后删除该用户全部会话（需重新登录）。该路由必须在 `/users/{id}` 之前匹配，已由代码保证。
- 请求体 `ChangePasswordRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| oldPassword | String | 是 | 原密码 |
| newPassword | String | 是 | 新密码，长度 ≥ 6 |

- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "密码已修改，请重新登录", "id": "" }
```

- 错误码：
  - 400 `当前登录方式无需修改密码`（使用环境变量管理口令时；设备令牌会先被拦截器以 403 拦下）
  - 400 `密码长度至少 6 位`
  - 400 `原密码错误`
  - 401 `登录已失效`

- curl：

```bash
curl -X PUT http://127.0.0.1:8080/api/v1/users/me/password \
  -H "Authorization: Bearer <会话token>" \
  -H "Content-Type: application/json" \
  -d '{"oldPassword":"abc123","newPassword":"def456"}'
```

#### 3.2.2 GET /api/v1/auth/check

- 说明：校验当前凭据是否有效。不需要任何模块权限。
- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "token valid", "id": "" }
```

- 错误码：401（凭据无效/未携带）。

#### 3.2.3 POST /api/v1/admin/logout

- 说明：使当前会话 token 失效；环境变量管理口令不受影响。无请求体。
- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "已登出", "id": "" }
```

- 错误码：401（凭据无效）。

### 3.3 全局设备配置

#### 3.3.1 GET /api/v1/config

- 说明：返回完整配置快照，客户端据此同步本地 Room。不需要模块权限。
- 响应 200 `ConfigSnapshot`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| version | Long | 配置版本号，任何增删改后自增 |
| device | DeviceConfig | 全局设备配置 |
| tools | List<ToolConfig> | 刀具列表 |
| materials | List<MaterialConfig> | 材料列表 |
| parts | List<PartConfig> | 零件列表 |
| blanks | List<BlankConfig> | 毛坯列表 |
| plans | List<ProcessPlanConfig> | 工艺方案列表（含 steps） |
| drawings | List<DrawingConfig> | 图纸列表（含 DXF 原文） |
| drawingCorrections | List<DrawingCorrectionConfig> | 图纸校正列表 |

`DeviceConfig`：

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| name | String | `数控车床 #1` | 设备名称 |
| controlCard | String | `FANUC_0I` | 控制卡型号 |
| useSimulator | Boolean | `true` | 是否使用模拟器 |
| maxTravelXmm | Double | `300.0` | X 轴最大行程（mm） |
| maxTravelZmm | Double | `500.0` | Z 轴最大行程（mm） |
| maxRpm | Int | `3000` | 最大转速 |
| maxFeedMmPerMin | Double | `5000.0` | 最大进给（mm/min） |
| comm | CommConfig | `CommConfig()` | 通信参数 |

`CommConfig`：

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| protocol | String | `MODBUS_TCP` | 通信协议 |
| ipAddress | String | `192.168.1.100` | IP 地址 |
| port | Int | `502` | 端口 |
| serialPort | String | `/dev/ttyUSB0` | 串口设备 |
| baudRate | Int | `115200` | 波特率 |
| deviceId | Int | `1` | Modbus 从站地址 |
| timeoutMs | Long | `2000` | 超时（毫秒） |

```json
{
  "version": 42,
  "device": {
    "name": "数控车床 #1",
    "controlCard": "FANUC_0I",
    "useSimulator": true,
    "maxTravelXmm": 300.0,
    "maxTravelZmm": 500.0,
    "maxRpm": 3000,
    "maxFeedMmPerMin": 5000.0,
    "comm": {
      "protocol": "MODBUS_TCP",
      "ipAddress": "192.168.1.100",
      "port": 502,
      "serialPort": "/dev/ttyUSB0",
      "baudRate": 115200,
      "deviceId": 1,
      "timeoutMs": 2000
    }
  },
  "tools": [],
  "materials": [],
  "parts": [],
  "blanks": [],
  "plans": [],
  "drawings": [],
  "drawingCorrections": []
}
```

- 错误码：401；设备令牌仅需 GET 即可读本接口（放行）。

#### 3.3.2 GET /api/v1/config/version

- 说明：轻量版本探测（心跳时使用，避免每次拉全量配置）。不需要模块权限。
- 响应 200 `VersionResult`：

```json
{ "version": 42 }
```

- 错误码：401。

#### 3.3.3 PUT /api/v1/device

- 说明：保存全局设备配置（整体覆盖）。每次保存都会使配置版本 +1。
- 所需模块：`device`（普通用户写操作需授权）。
- 请求体 `DeviceConfig`：字段同 3.3.1 的 `DeviceConfig` 表（`comm` 内为 `CommConfig` 表）。
- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "设备配置已保存", "id": "" }
```

- 错误码：401；403（无 `device` 权限）。

- curl：

```bash
curl -X PUT http://127.0.0.1:8080/api/v1/device \
  -H "Authorization: Bearer <管理口令或会话token>" \
  -H "Content-Type: application/json" \
  -d '{"name":"数控车床 #1","controlCard":"FANUC_0I","useSimulator":false,"maxTravelXmm":300.0,"maxTravelZmm":500.0,"maxRpm":3000,"maxFeedMmPerMin":5000.0,"comm":{"protocol":"MODBUS_TCP","ipAddress":"192.168.1.100","port":502,"serialPort":"/dev/ttyUSB0","baudRate":115200,"deviceId":1,"timeoutMs":2000}}'
```

### 3.4 刀具

#### 3.4.1 POST /api/v1/tools

- 说明：新增或整体更新刀具（按 `id` 覆盖）。`id` 为空时服务端生成 `tool-<8位>`。保存后配置版本 +1。
- 所需模块：`tools`
- 请求体 `ToolConfig`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 否 | 为空则新增并生成 ID；非空则按 ID 覆盖 |
| toolNumber | Int | 否 | 刀号，默认 `1` |
| toolType | String | 否 | 刀具类型，默认 `TURNING` |
| name | String | 否 | 刀具名称，默认 `""` |
| material | String | 否 | 刀片材质，默认 `CARBIDE` |
| nominalDiameterMm | Double | 否 | 标称直径（mm），默认 `0.0` |
| lengthMm | Double | 否 | 刀长（mm），默认 `0.0` |
| maxRpm | Int | 否 | 允许最大转速，默认 `3000` |
| maxFeedMmPerMin | Double | 否 | 允许最大进给，默认 `500.0` |
| wearStatus | String | 否 | 磨损状态，默认 `NEW` |
| usageCount | Int | 否 | 使用次数，默认 `0` |
| notes | String | 否 | 备注，默认 `""` |

- 响应 200：保存后的 `ToolConfig`（`id` 已回填）。

```json
{
  "id": "tool-1a2b3c4d",
  "toolNumber": 1,
  "toolType": "TURNING",
  "name": "外圆粗车刀",
  "material": "CARBIDE",
  "nominalDiameterMm": 0.0,
  "lengthMm": 120.0,
  "maxRpm": 3000,
  "maxFeedMmPerMin": 500.0,
  "wearStatus": "NEW",
  "usageCount": 0,
  "notes": ""
}
```

- 错误码：401；403（无 `tools` 权限）；400（JSON 结构不合法时由框架返回）。

#### 3.4.2 DELETE /api/v1/tools/{id}

- 说明：删除刀具。**不校验 ID 是否存在**，删除后配置版本 +1，恒返回成功。
- 路径参数：`id`（String，必填）。
- 所需模块：`tools`
- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "刀具已删除", "id": "" }
```

- 错误码：401；403。

### 3.5 材料

#### 3.5.1 POST /api/v1/materials

- 说明：新增或整体更新材料。`id` 为空时生成 `mat-<8位>`。配置版本 +1。
- 所需模块：`materials`
- 请求体 `MaterialConfig`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 否 | 为空则新增；非空则覆盖 |
| name | String | 否 | 材料名称，默认 `""` |
| code | String | 否 | 材料牌号，默认 `""` |
| density | Double | 否 | 密度，默认 `0.0` |
| hardness | String | 否 | 硬度描述，默认 `""` |
| recommendedCuttingSpeedMs | Double | 否 | 推荐切削速度，默认 `150.0` |
| recommendedFeedPerRev | Double | 否 | 推荐每转进给，默认 `0.2` |
| recommendedDepthMm | Double | 否 | 推荐切深（mm），默认 `2.0` |
| enabled | Boolean | 否 | 是否启用，默认 `true` |
| notes | String | 否 | 备注，默认 `""` |

- 响应 200：保存后的 `MaterialConfig`。

```json
{
  "id": "mat-5f6e7d8c",
  "name": "45# 钢",
  "code": "45",
  "density": 7.85,
  "hardness": "HB170-210",
  "recommendedCuttingSpeedMs": 150.0,
  "recommendedFeedPerRev": 0.2,
  "recommendedDepthMm": 2.0,
  "enabled": true,
  "notes": ""
}
```

- 错误码：401；403（无 `materials` 权限）。

#### 3.5.2 DELETE /api/v1/materials/{id}

- 说明：删除材料，不校验存在性，恒返回成功，配置版本 +1。
- 路径参数：`id`（String，必填）。
- 所需模块：`materials`
- 响应 200 `SimpleResult`：`{"ok": true, "message": "材料已删除", "id": ""}`
- 错误码：401；403。

### 3.6 零件与毛坯

#### 3.6.1 POST /api/v1/parts

- 说明：新增或更新零件。`id` 为空时生成 `part-<8位>`。配置版本 +1。
- 所需模块：`parts`
- 请求体 `PartConfig`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 否 | 为空则新增；非空则覆盖 |
| name | String | 否 | 零件名称，默认 `""` |
| materialId | String | 否 | 关联材料 ID，默认 `""` |
| notes | String | 否 | 备注，默认 `""` |

- 响应 200：保存后的 `PartConfig`。
- 错误码：401；403（无 `parts` 权限）。

```json
{
  "id": "part-9a8b7c6d",
  "name": "法兰盘",
  "materialId": "mat-5f6e7d8c",
  "notes": ""
}
```

#### 3.6.2 DELETE /api/v1/parts/{id}

- 说明：删除零件，不校验存在性，恒返回成功，配置版本 +1。
- 路径参数：`id`（String，必填）。
- 所需模块：`parts`
- 响应 200 `SimpleResult`：`{"ok": true, "message": "零件已删除", "id": ""}`
- 错误码：401；403。

#### 3.6.3 POST /api/v1/blanks

- 说明：新增或更新毛坯。`id` 为空时生成 `blank-<8位>`。配置版本 +1。
- 所需模块：`parts`
- 请求体 `BlankConfig`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 否 | 为空则新增；非空则覆盖 |
| partId | String | 否 | 关联零件 ID，默认 `""` |
| diameterMm | Double | 否 | 毛坯直径（mm），默认 `80.0` |
| lengthMm | Double | 否 | 毛坯长度（mm），默认 `120.0` |
| materialId | String | 否 | 关联材料 ID，默认 `""` |
| clampType | String | 否 | 装夹方式，默认 `CHUCK` |

- 响应 200：保存后的 `BlankConfig`。
- 错误码：401；403。

```json
{
  "id": "blank-0a1b2c3d",
  "partId": "part-9a8b7c6d",
  "diameterMm": 80.0,
  "lengthMm": 120.0,
  "materialId": "mat-5f6e7d8c",
  "clampType": "CHUCK"
}
```

#### 3.6.4 DELETE /api/v1/blanks/{id}

- 说明：删除毛坯，不校验存在性，恒返回成功，配置版本 +1。
- 路径参数：`id`（String，必填）。
- 所需模块：`parts`
- 响应 200 `SimpleResult`：`{"ok": true, "message": "毛坯已删除", "id": ""}`
- 错误码：401；403。

### 3.7 工艺方案

#### 3.7.1 POST /api/v1/plans

- 说明：新增或整体更新工艺方案及其全部步骤。保存逻辑：先写方案，再**删除该方案下所有旧步骤**，然后逐条插入本次 `steps`；步骤 `id` 为空时落库 ID 为 `<planId>-step-<index>`；`orderIndex <= 0` 时用数组下标代替。配置版本 +1。
- 所需模块：`plans`
- 请求体 `ProcessPlanConfig`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 否 | 为空则生成 `plan-<8位>` |
| name | String | 否 | 方案名称，默认 `""` |
| partId | String | 否 | 关联零件 ID，默认 `""` |
| stockDiameterMm | Double | 否 | 下料直径（mm），默认 `100.0` |
| stockLengthMm | Double | 否 | 下料长度（mm），默认 `50.0` |
| description | String | 否 | 描述，默认 `""` |
| steps | List<ProcessStepConfig> | 否 | 步骤列表，默认 `[]`；整体覆盖旧步骤 |

`ProcessStepConfig`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 否 | 为空则由服务端按 `<planId>-step-<index>` 落库 |
| planId | String | 否 | 所属方案 ID；服务端以路径中的方案 ID 为准 |
| orderIndex | Int | 否 | 排序号，默认 `0`；`<=0` 时用数组下标 |
| type | String | 否 | 工序类型，默认 `ROUGH_TURNING` |
| toolId | String | 否 | 使用刀具 ID，默认 `""` |
| cuttingSpeedMmPerMin | Double | 否 | 切削速度，默认 `150.0` |
| spindleRpm | Int | 否 | 主轴转速，默认 `1200` |
| feedMmPerRev | Double | 否 | 每转进给，默认 `0.2` |
| depthOfCutMm | Double | 否 | 切深（mm），默认 `5.0` |
| finishAllowanceMm | Double | 否 | 精加工余量（mm），默认 `1.0` |
| approachDistanceMm | Double | 否 | 接近距离（mm），默认 `10.0` |
| retractDistanceMm | Double | 否 | 退刀距离（mm），默认 `10.0` |
| safetyDistanceMm | Double | 否 | 安全距离（mm），默认 `10.0` |
| enabled | Boolean | 否 | 是否启用，默认 `true` |
| notes | String | 否 | 备注，默认 `""` |

- 请求体示例（响应为同一结构，仅 `id` 由服务端回填）：

```json
{
  "id": "plan-1234abcd",
  "name": "外圆粗精车",
  "partId": "part-9a8b7c6d",
  "stockDiameterMm": 100.0,
  "stockLengthMm": 50.0,
  "description": "",
  "steps": [
    {
      "id": "",
      "planId": "plan-1234abcd",
      "orderIndex": 0,
      "type": "ROUGH_TURNING",
      "toolId": "tool-1a2b3c4d",
      "cuttingSpeedMmPerMin": 150.0,
      "spindleRpm": 1200,
      "feedMmPerRev": 0.2,
      "depthOfCutMm": 5.0,
      "finishAllowanceMm": 1.0,
      "approachDistanceMm": 10.0,
      "retractDistanceMm": 10.0,
      "safetyDistanceMm": 10.0,
      "enabled": true,
      "notes": ""
    }
  ]
}
```

- 响应 200：JSON 结构与上方请求体示例完全相同（仅回填方案 `id`；步骤 `id` 保持请求中的值，不会回填落库时的派生 ID）。
- 错误码：401；403（无 `plans` 权限）。

#### 3.7.2 DELETE /api/v1/plans/{id}

- 说明：删除工艺方案，并级联删除其全部步骤。不校验存在性，恒返回成功，配置版本 +1。
- 路径参数：`id`（String，必填）。
- 所需模块：`plans`
- 响应 200 `SimpleResult`：`{"ok": true, "message": "工艺方案已删除", "id": ""}`
- 错误码：401；403。

### 3.8 图纸与校正

#### 3.8.1 POST /api/v1/drawings

- 说明：新增或更新图纸（含 DXF 原文）。`id` 为空时生成 `drawing-<8位>`；`source` 为空时落库为 `SERVER`。配置版本 +1。
- 所需模块：`drawings`
- 请求体 `DrawingConfig`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 否 | 为空则新增；非空则覆盖 |
| filename | String | 否 | 文件名，默认 `""` |
| layers | Int | 否 | 图层数，默认 `0` |
| entities | Int | 否 | 实体数，默认 `0` |
| source | String | 否 | 来源，默认 `SERVER`；空串落库为 `SERVER` |
| dxfContent | String | 否 | DXF 原文文本，默认 `""`；客户端拉取后存 Room 用于 CAM |

- 响应 200：保存后的 `DrawingConfig`。
- 错误码：401；403（无 `drawings` 权限）。

```json
{
  "id": "drawing-55667788",
  "filename": "flange.dxf",
  "layers": 3,
  "entities": 128,
  "source": "SERVER",
  "dxfContent": "0\nSECTION\n2\nHEADER\n..."
}
```

#### 3.8.2 DELETE /api/v1/drawings/{id}

- 说明：删除图纸，并级联删除该图纸的校正记录。不校验存在性，恒返回成功，配置版本 +1。
- 路径参数：`id`（String，必填）。
- 所需模块：`drawings`
- 响应 200 `SimpleResult`：`{"ok": true, "message": "图纸已删除", "id": ""}`
- 错误码：401；403。

#### 3.8.3 POST /api/v1/corrections

- 说明：新增或更新图纸校正参数（以 `drawingId` 为主键覆盖）。无独立删除接口；删除图纸时级联清理。配置版本 +1。
- 所需模块：`drawings`
- 请求体 `DrawingCorrectionConfig`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| drawingId | String | 是 | 关联图纸 ID（主键，必填，默认 `""`） |
| scale | Double | 否 | 缩放比例，默认 `1.0` |
| offsetX | Double | 否 | X 偏移，默认 `0.0` |
| offsetY | Double | 否 | Y 偏移，默认 `0.0` |
| rotationDeg | Double | 否 | 旋转角度（度），默认 `0.0` |
| mirrorX | Boolean | 否 | X 镜像，默认 `false` |
| mirrorY | Boolean | 否 | Y 镜像，默认 `false` |
| unit | String | 否 | 单位，默认 `MM` |

- 请求体示例（响应为该结构原样回显）：

```json
{
  "drawingId": "drawing-55667788",
  "scale": 1.0,
  "offsetX": 0.0,
  "offsetY": 0.0,
  "rotationDeg": 0.0,
  "mirrorX": false,
  "mirrorY": false,
  "unit": "MM"
}
```

- 响应 200：JSON 结构与上方请求体示例完全相同（原样回显）。
- 错误码：401；403。

### 3.9 任务记录

#### 3.9.1 GET /api/v1/tasks

- 说明：查询任务历史，按 `finishedAt` 倒序，固定最多 200 条（代码内固定 `limit=200`，无分页参数）。
- 所需模块：`tasks`（普通用户需权限非空，见宽松只读规则）
- 响应 200 `List<TaskRecord>`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | String | 任务 ID |
| partName | String | 零件名称 |
| planId | String | 工艺方案 ID |
| status | String | 状态，默认语义如 `SUCCESS` |
| durationSec | Double | 耗时（秒） |
| operator | String | 操作员 |
| startedAt | Long | 开始时间（毫秒） |
| finishedAt | Long | 结束时间（毫秒） |

```json
[
  {
    "id": "task-1a2b3c4d",
    "partName": "法兰盘",
    "planId": "plan-1234abcd",
    "status": "SUCCESS",
    "durationSec": 96.5,
    "operator": "zhangsan",
    "startedAt": 1792969500000,
    "finishedAt": 1792969596500
  }
]
```

- 错误码：401；403。

#### 3.9.2 POST /api/v1/tasks

- 说明：设备/后台回传一条任务记录（按 `id` 覆盖）。`id` 为空时生成 `task-<8位>`。**设备令牌可调用本接口**（拦截器放行 `POST .../tasks`）。
- 所需模块：`tasks`（设备令牌不受模块限制，走设备白名单）
- 请求体 `TaskRecord`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| id | String | 否 | 为空则新增；非空则覆盖 |
| partName | String | 否 | 零件名称，默认 `""` |
| planId | String | 否 | 方案 ID，默认 `""` |
| status | String | 否 | 状态，默认 `SUCCESS` |
| durationSec | Double | 否 | 耗时（秒），默认 `0.0` |
| operator | String | 否 | 操作员，默认 `""` |
| startedAt | Long | 否 | 开始时间（毫秒），默认 `0` |
| finishedAt | Long | 否 | 结束时间（毫秒），默认 `0` |

- 响应 200：保存后的 `TaskRecord`（含回填 `id`）：

```json
{
  "id": "task-1a2b3c4d",
  "partName": "法兰盘",
  "planId": "plan-1234abcd",
  "status": "SUCCESS",
  "durationSec": 96.5,
  "operator": "zhangsan",
  "startedAt": 1792969500000,
  "finishedAt": 1792969596500
}
```
- 错误码：401；403。

### 3.10 设备管理

#### 3.10.1 GET /api/v1/devices

- 说明：设备激活列表，按注册时间倒序，含在线状态与客户端版本号。
- 所需模块：`devices`
- 响应 200 `List<DeviceListItem>`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | String | 设备 ID |
| unitName | String | 单位名称 |
| province | String | 省份 |
| city | String | 城市 |
| contactName | String | 联系人 |
| phone | String | 电话 |
| serverAddress | String | 服务端地址 |
| registeredAt | Long | 注册时间（毫秒） |
| lastSeen | Long | 最后心跳时间（毫秒） |
| online | Boolean | 25 秒内有心跳为 `true` |
| appVersion | String | 客户端版本号 |
| permissions | ClientPermissions | 设备功能权限 |

```json
[
  {
    "id": "tablet-1",
    "unitName": "一号车间",
    "province": "江苏省",
    "city": "苏州市",
    "contactName": "张三",
    "phone": "13800000000",
    "serverAddress": "http://192.168.1.10:8080",
    "registeredAt": 1792969600000,
    "lastSeen": 1793056000000,
    "online": true,
    "appVersion": "1.1.0",
    "permissions": {
      "taskSelect": true, "previewStart": true, "monitor": true, "alarms": true,
      "advanced": true, "serverSync": true, "drawings": true, "cam": true,
      "machining": true, "io": true, "history": true
    }
  }
]
```

- 错误码：401；403。

#### 3.10.2 GET /api/v1/devices/org-summary

- 说明：单位终端统计。按「单位名称 + 省份 + 城市」分组，统计每组设备数与在线数，按 `deviceCount` 降序返回。服务端将该路由注册在 `/devices/{id}` 之前，避免 `org-summary` 被当作设备 ID。
- 所需模块：`devices`
- 响应 200 `List<OrgSummaryItem>`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| orgName | String | 单位名称（`devices.unit_name`） |
| province | String | 省份 |
| city | String | 城市 |
| deviceCount | Int | 该组设备总数 |
| onlineCount | Int | 该组在线设备数（25 秒内有过心跳） |
| lastSeen | Long | 该组最近一次心跳时间（毫秒）；无记录为 0 |

```json
[
  {
    "orgName": "苏州精工机械有限公司",
    "province": "江苏省",
    "city": "苏州市",
    "deviceCount": 3,
    "onlineCount": 2,
    "lastSeen": 1793197850745
  }
]
```

- 错误码：401；403。
- curl：

```bash
curl http://127.0.0.1:8080/api/v1/devices/org-summary \
  -H "Authorization: Bearer $TOKEN"
```

#### 3.10.3 PUT /api/v1/devices/{id}

- 说明：修改设备单位/联系人/电话/地区。该接口为**全量更新**：`unitName`、`contactName`、`phone` 任一为空（`isBlank()`）返回 400；`province`、`city` 可省略（按空串落库）。
- 路径参数：`id`（String，必填，设备 ID）。
- 所需模块：`devices`
- 请求体 `DeviceUpdateRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| unitName | String | 是 | 单位名称，不能为空白 |
| contactName | String | 是 | 联系人，不能为空白 |
| phone | String | 是 | 电话，不能为空白 |
| province | String | 否 | 省份，默认 `""` |
| city | String | 否 | 城市，默认 `""` |

- 响应 200 `SimpleResult`（简单结果，**不返回完整设备对象**）：

```json
{ "ok": true, "message": "设备信息已更新", "id": "" }
```

设备不存在时仍为 200，`ok=false`、`message="设备不存在"`。
- 错误码：400 `单位、联系人、电话不能为空`；401；403。

#### 3.10.4 DELETE /api/v1/devices/{id}

- 说明：注销设备（删除记录），注销后该设备可重新注册。
- 路径参数：`id`（String，必填）。
- 所需模块：`devices`
- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "设备已注销，可重新注册", "id": "" }
```

设备不存在时仍为 200，`ok=false`、`message="设备不存在"`。
- 错误码：401；403。

#### 3.10.5 PUT /api/v1/devices/{id}/permissions

- 说明：更新设备功能权限开关（整体覆盖）。请求中缺失的 `ClientPermissions` 字段按默认 `true` 处理。
- 路径参数：`id`（String，必填）。
- 所需模块：`devices`
- 请求体 `DevicePermissionsUpdate`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| permissions | ClientPermissions | 否 | 默认 `ClientPermissions()`，11 个开关全部 `true`；字段含义见 1.4 附表 |

请求体示例：

```json
{
  "permissions": {
    "taskSelect": true,
    "previewStart": false,
    "monitor": true,
    "alarms": true,
    "advanced": false,
    "serverSync": true,
    "drawings": true,
    "cam": true,
    "machining": true,
    "io": false,
    "history": true
  }
}
```

- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "权限已更新", "id": "" }
```

设备不存在时仍为 200，`ok=false`、`message="设备不存在"`。
- 错误码：401；403。

### 3.11 APK 版本发布

五个接口的关系：

| 接口 | 角色 | 作用 |
| --- | --- | --- |
| `POST /api/v1/app-publish` | 手动上传 | multipart 上传本地 APK + 版本号，落盘到 `CNC_APK` 并写入发布元信息 |
| `GET /api/v1/app-latest-candidate` | 只读 | 扫描构建目录，返回"最新候选 APK"（仅查询，不发布） |
| `POST /api/v1/app-publish-latest` | 一键发布 | 重新扫描并取最新候选，复制到 `CNC_APK` 并写入发布元信息 |
| `GET /api/v1/app-version-info` | 后台只读 | 读取当前已发布版本的元信息（未发布时返回全默认值，200） |
| `GET /api/v1/app-version` | 客户端只读（开放） | 客户端检查更新，未发布返回 404 |
| `GET /api/v1/app-download` | 客户端下载（开放） | 下载 `CNC_APK` 文件，需同时存在发布记录 |

发布动作只写"元信息 + APK 文件"，两者共同生效：`/app-download` 要求文件存在**且**有发布记录。

扫描目录（`ApkScanner.defaultDirs`）：若设置了 `CNC_APK_DIR`（Windows 下用 `;` 分隔）则只扫描这些目录；否则扫描服务端工作目录下：

1. `../app/build/outputs/apk/release`
2. `../app/build/outputs/apk/debug`
3. `apk-inbox/`

扫描时解析 APK 内二进制 `AndroidManifest.xml` 的 `versionCode` / `versionName`，解析失败或 `versionCode <= 0` 的 APK 被跳过；取 `versionCode` 最大者，并列取修改时间最新者。

#### 3.11.1 GET /api/v1/app-version-info

- 说明：后台查看当前发布版本。未发布时返回 `AppVersionInfo()` 全默认对象（200）。
- 所需模块：无（`requiredPermission` 返回 `null`）
- 响应 200 `AppVersionInfo`（字段见 1.7）；未发布时：

```json
{
  "versionCode": 0,
  "versionName": "",
  "changelog": "",
  "sha256": "",
  "sizeBytes": 0,
  "publishedAt": 0
}
```

- 错误码：401。

#### 3.11.2 POST /api/v1/app-publish

- 说明：手动上传 APK 发布。`multipart/form-data`，要求同时存在文件字段 `apk` 且 `versionCode > 0`、`versionName` 非空，否则 400 且临时文件被清理。服务端先写 `.tmp`，再重命名/覆盖到 `CNC_APK` 路径，随后计算 SHA-256 并写入发布记录。
- 所需模块：`app`
- 表单字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| versionCode | Int（表单文本） | 是 | 必须 > 0；无法解析为整数时按 0 处理 → 400 |
| versionName | String（表单文本） | 是 | 版本名，不能为空 |
| changelog | String（表单文本） | 否 | 更新说明，默认 `""` |
| apk | File | 是 | APK 文件部件（`name="apk"`） |

- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "已发布 v1.2.0（12），7933 KB", "id": "" }
```

- 错误码：400 `需要上传 APK 文件并填写版本号(versionCode)与版本名(versionName)`；401；403（无 `app` 权限）。

- curl：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/app-publish \
  -H "Authorization: Bearer <管理口令或会话token>" \
  -F "versionCode=12" \
  -F "versionName=1.2.0" \
  -F "changelog=修复断线重连问题" \
  -F "apk=@app-release.apk"
```

#### 3.11.3 GET /api/v1/app-latest-candidate

- 说明：返回扫描到的最新候选 APK（不发布）。未找到时 `found=false` 且其余字段为默认值。
- 所需模块：`app`
- 响应 200 `ApkCandidateResult`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| found | Boolean | 是否找到候选 |
| path | String | 候选文件绝对路径（服务端本地路径） |
| fileName | String | 文件名 |
| versionCode | Int | 解析出的版本号 |
| versionName | String | 解析出的版本名 |
| sizeBytes | Long | 文件字节数 |
| modifiedAt | Long | 文件修改时间（毫秒） |

```json
{
  "found": true,
  "path": "E:\\AI软件开发\\cnc-host-git-baseline\\app\\build\\outputs\\apk\\release\\app-release.apk",
  "fileName": "app-release.apk",
  "versionCode": 12,
  "versionName": "1.2.0",
  "sizeBytes": 8123456,
  "modifiedAt": 1793056000000
}
```

未找到：

```json
{
  "found": false,
  "path": "",
  "fileName": "",
  "versionCode": 0,
  "versionName": "",
  "sizeBytes": 0,
  "modifiedAt": 0
}
```

- 错误码：401；403（无 `app` 权限）。

#### 3.11.4 POST /api/v1/app-publish-latest

- 说明：一键发布最新构建。服务端重新扫描构建目录，取最新候选复制到 `CNC_APK` 并写入发布记录。请求体可省略。
- 所需模块：`app`
- 请求体 `PublishLatestRequest`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| changelog | String | 否 | 更新说明，默认 `""` |

- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "已发布最新构建 v1.2.0（12），7933 KB，来自 app-release.apk", "id": "" }
```

- 错误码：
  - 404 `未找到可用 APK 构建产物（请先用 gradlew assembleRelease/assembleDebug 构建，或设置 CNC_APK_DIR）`
  - 401；403。

- curl：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/app-publish-latest \
  -H "Authorization: Bearer <管理口令或会话token>" \
  -H "Content-Type: application/json" \
  -d '{"changelog":"一键发布最新构建"}'
```

### 3.12 操作日志

#### 3.12.1 GET /api/v1/logs

- 说明：查询操作日志，按 `timestamp` 倒序。
- 所需模块：`logs`
- 查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| deviceId | String | 否 | 按设备过滤；省略或空白则查全部 |
| limit | Int | 否 | 返回条数上限，默认 `500`；非整数时按 500 处理 |

- 响应 200 `List<OperationLogDto>`（字段见 2.1）：

```json
[
  {
    "timestamp": 1792969600000,
    "level": "INFO",
    "category": "SYNC",
    "message": "配置同步完成",
    "taskId": "task-1a2b3c4d",
    "details": "version=42"
  }
]
```

- 错误码：401；403。

#### 3.12.2 DELETE /api/v1/logs

- 说明：清理早于指定时间的日志。
- 所需模块：`logs`
- 查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| deviceId | String | 否 | 仅清理该设备的日志；省略或空白则清理全部 |
| olderThanMs | Long | 否 | 时间阈值（毫秒时间戳），早于该值的记录被删除；默认 `当前时间 - 7 天` |

- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "已清理 12 条日志", "id": "" }
```

- 错误码：401；403。

- curl：

```bash
curl -X DELETE "http://127.0.0.1:8080/api/v1/logs?deviceId=tablet-1&olderThanMs=1792969600000" \
  -H "Authorization: Bearer <管理口令或会话token>"
```

### 3.13 其他（实时状态与管理员口令）

#### 3.13.1 GET /api/v1/status

- 说明：读取最近一次设备上报的实时状态；从未上报时返回 `DeviceStatus()` 全默认对象（200）。不需要模块权限。
- 响应 200 `DeviceStatus`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| deviceId | String | 上报设备 ID，默认 `tablet-1` |
| connected | Boolean | 是否已连接，默认 `false` |
| mode | String | 运行模式，默认 `OFFLINE` |
| isSimulated | Boolean | 是否模拟运行，默认 `true` |
| taskName | String | 当前任务名，默认 `""` |
| progress | Double | 进度 0–100（客户端约定），默认 `0.0` |
| message | String | 状态描述，默认 `""` |
| xMm | Double? | X 轴坐标（mm），可空 |
| zMm | Double? | Z 轴坐标（mm），可空 |
| spindleRpm | Int? | 主轴实际转速，可空 |
| timestamp | Long | 上报时间（毫秒），默认当前时间 |

```json
{
  "deviceId": "tablet-1",
  "connected": true,
  "mode": "RUNNING",
  "isSimulated": false,
  "taskName": "法兰盘粗车",
  "progress": 42.5,
  "message": "加工中",
  "xMm": 12.3,
  "zMm": -45.6,
  "spindleRpm": 1200,
  "timestamp": 1792969600000
}
```

- 错误码：401。

#### 3.13.2 POST /api/v1/status

- 说明：设备上报实时状态。服务端缓存最近一次状态、刷新该设备在线时间，并**立即广播给所有 `/ws/status` 订阅者**。不需要模块权限；**设备令牌可调用本接口**（拦截器放行 `POST .../status`）。
- 请求体 `DeviceStatus`（字段见 3.13.1）。
- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "status accepted", "id": "" }
```

- 错误码：401（本路径映射为"无需模块权限"，不会返回 403）。

- curl：

```bash
curl -X POST http://127.0.0.1:8080/api/v1/status \
  -H "Authorization: Bearer <设备令牌>" \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"tablet-1","connected":true,"mode":"RUNNING","isSimulated":false,"taskName":"法兰盘粗车","progress":42.5,"message":"加工中","xMm":12.3,"zMm":-45.6,"spindleRpm":1200,"timestamp":1792969600000}'
```

#### 3.13.3 POST /api/v1/admin-password

- 说明：设置旧版"管理员账号密码"（供客户端 `verify-admin` 使用），同时同步更新 seed 超管账号（`role=SUPER_ADMIN AND username=旧用户名`）的用户名与密码哈希，并更新 meta。旧接口兼容；**密码长度至少 6 位，不足返回 400**。
- 所需模块：`users`
- 请求体 `AdminPasswordUpdate`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| newPassword | String | 是 | 新密码，不能为空白 |
| newUsername | String | 否 | 新用户名；空白则沿用 meta 中的当前用户名 |

- 响应 200 `SimpleResult`：

```json
{ "ok": true, "message": "管理员账号密码已更新", "id": "" }
```

- 错误码：400 `密码不能为空`；409 `用户名已存在`（同步超管账号失败）；401；403（无 `users` 权限）。

---

## 四、WebSocket

### 4.1 /ws/status

- 地址：`ws://<服务器IP>:<PORT>/ws/status?token=<凭据>`
- 鉴权：查询参数 `token` 必须是**环境变量管理口令**或**有效的后台会话 token**；其他情况（含设备令牌）服务端立即以关闭码 **1008（VIOLATED_POLICY）**、原因 `invalid token` 关闭连接。
- 行为：
  1. 连接建立后，若服务端已缓存状态，立即下发**一帧** `DeviceStatus` JSON 文本；
  2. 之后每次 `POST /api/v1/status` 成功，服务端向所有在线订阅者广播该 `DeviceStatus` JSON；
  3. 客户端发送的文本帧被忽略（仅用于保活），服务端不回发。
- 消息格式：`DeviceStatus` 单对象 JSON（非数组、无包装），字段见 3.13.1。

```json
{"deviceId":"tablet-1","connected":true,"mode":"RUNNING","isSimulated":false,"taskName":"法兰盘粗车","progress":42.5,"message":"加工中","xMm":12.3,"zMm":-45.6,"spindleRpm":1200,"timestamp":1792969600000}
```

- 错误（关闭原因 `invalid token`）：
  - 未带 `token`、token 错误、token 为设备令牌、会话已过期/账号已禁用。

---

## 五、错误码总表

| HTTP 状态 | 含义 | 典型场景 | 响应体 |
| --- | --- | --- | --- |
| 200 | 成功 | 绝大多数接口成功；部分"业务失败"也返回 200（如 `verify-admin` 密码错误、注销不存在的设备 `ok=false`） | DTO 或 `SimpleResult` |
| 400 | 请求参数错误 | 用户名为空、密码不足 6 位、角色非法、设备单位/联系人/电话为空、不能删除/禁用自己、最后一名超管保护、原密码错误、当前登录方式无需改密、`admin-password` 密码为空、APK 上传缺字段 | `SimpleResult` |
| 401 | 未认证 / 登录失效 | 未携带或无效 Bearer；会话过期；账号被禁用或删除（`登录已失效`）；登录账号或密码错误（返回 `AdminLoginResult`） | `SimpleResult`（未通过认证层时为空响应体）；登录失败为 `AdminLoginResult` |
| 403 | 已认证但无权限 | `无权限执行该操作`（缺模块权限、非超管操作超管）；`设备令牌仅允许同步配置与回传数据`；`设备令牌无权访问账号管理` | `SimpleResult` |
| 404 | 资源不存在 | 设备不存在/已注销、用户不存在、尚未发布版本、未找到可用 APK 构建产物 | `SimpleResult` |
| 409 | 冲突 | `用户名已存在`（设备重复激活已改为幂等更新，不再返回 409） | `SimpleResult` |
| 500 | 服务端异常 | 未捕获异常；由 `StatusPages` 统一返回 `ok=false` + 异常信息 | `SimpleResult` |

补充规则（与代码一致，实施时需注意）：

- `DELETE /tools|materials|parts|blanks|plans|drawings/{id}` 恒返回 `ok=true`（不存在的 ID 也返回成功）。
- `DELETE /devices/{id}`、`PUT /devices/{id}`、`PUT /devices/{id}/permissions` 在设备不存在时返回 **200 + `ok=false`**，不是 404。
- 成功但 `ok=false` 的情况仅出现在上述设备类接口与 `verify-admin`（密码错误）。
- JSON 结构不合法/缺少必填字段（如 `ResetPasswordRequest.newPassword`）由框架直接返回 400。

---

## 六、权限矩阵

| 路由组 | 所需模块权限 | 设备令牌 | 普通用户（`ADMIN`/`OPERATOR`） | 超管（`SUPER_ADMIN` 或环境变量口令） |
| --- | --- | --- | --- | --- |
| 开放接口：`GET /`、`/health`、`/admin/login`、`/devices/register`、`GET /devices/{id}`、`/devices/{id}/heartbeat`、`/devices/{id}/verify-admin`、`/app-version`、`/app-download` | 无需（不过 admin 认证） | 可 | 可 | 可 |
| `POST /api/v1/logs` | 仅 bearer device | 可 | 不可（该路由不属于 admin 认证，会话 token 无法通过认证） | 不可（同上，须用设备令牌） |
| `GET /config`、`GET /config/version`、`GET /auth/check`、`GET /status` | 无 | 可（GET 放行） | 可 | 可 |
| `POST /status` | 无 | 可（POST 以 `/status` 结尾） | 可 | 可 |
| `POST /admin/logout` | 无 | 不可（非 GET、非 tasks/status） | 可 | 可 |
| `GET /users/me`、`PUT /users/me/password` | 无（不受 `users` 限制） | 不可（路径含 `users`） | 可 | 可 |
| `GET /users` | `users`（且 GET 含 `users` 不适用宽松规则） | 不可 | 需含 `users` | 可 |
| `POST /users`、`PUT /users/{id}`、`DELETE /users/{id}`、`POST /users/{id}/reset-password` | `users` | 不可 | 需含 `users` | 可 |
| `POST /admin-password` | `users` | 不可 | 需含 `users` | 可 |
| `PUT /device` | `device` | 不可 | 需含 `device` | 可 |
| `GET /devices`、`GET /tasks`、`GET /logs`（只读；刀具/材料/零件/毛坯/方案/图纸无独立 GET 接口，统一走 `GET /config`） | 对应模块（`devices`/`tasks`/`logs`） | 可（GET 放行） | 需 `permissions` 非空（不要求包含对应模块） | 可 |
| `GET /devices/org-summary` | `devices` | 可（GET 放行） | 需 `permissions` 非空（不要求包含对应模块） | 可 |
| `POST/DELETE` 刀具、材料、零件、毛坯、方案、图纸、校正、设备写操作 | 对应模块（`tools`/`materials`/`parts`/`plans`/`drawings`/`devices`） | 不可（403） | 需含对应模块 | 可 |
| `POST /tasks` | `tasks` | 可（POST 以 `/tasks` 结尾） | 需含 `tasks` | 可 |
| `DELETE /logs` | `logs` | 不可（DELETE） | 需含 `logs` | 可 |
| `GET /app-version-info` | 无（映射返回 `null`） | 可（GET 放行） | 可 | 可 |
| `GET /app-latest-candidate`、`POST /app-publish`、`POST /app-publish-latest` | `app` | 不可 | 需含 `app` | 可 |

说明：

- "设备令牌 可"的 GET 一律指路径不含 `users` 且不含 `admin-password`。
- 普通用户的"可"指：路由无需模块权限；或为 GET 且 `permissions` 非空；或（写操作）`permissions` 包含对应模块。
- 环境变量管理口令在拦截器中直接放行，等价超管；但 `GET /users/me` 因凭据无用户记录，返回 `id=""` 的虚拟超管信息。

---

## 七、典型调用流程

以下示例以 `BASE=http://127.0.0.1:8080`、管理口令 `cnc-admin-2026` 为例。

### 7.1 设备激活 → 心跳 → 拉配置 → 回传任务

```bash
BASE=http://127.0.0.1:8080

# 1) 激活设备（开放接口），拿到设备令牌；同一 hardwareId 重复激活为幂等更新，保留原 id 与令牌
curl -X POST $BASE/api/v1/devices/register \
  -H "Content-Type: application/json" \
  -d '{"unitName":"一号车间","contactName":"张三","phone":"13800000000","province":"江苏省","city":"苏州市","serverAddress":"http://127.0.0.1:8080","deviceId":"tablet-1","hardwareId":"a1b2c3d4e5f6","appVersion":"1.1.0"}'

# 2) 心跳（携带新版本号，同时会回带令牌）
DEV_TOKEN=<上一步返回的 token>
curl -X POST $BASE/api/v1/devices/tablet-1/heartbeat \
  -H "Content-Type: application/json" \
  -d '{"appVersion":"1.1.0"}'

# 3) 探测配置版本，再拉全量配置（设备令牌只读放行）
curl $BASE/api/v1/config/version -H "Authorization: Bearer $DEV_TOKEN"
curl $BASE/api/v1/config -H "Authorization: Bearer $DEV_TOKEN"

# 4) 回传任务记录
curl -X POST $BASE/api/v1/tasks \
  -H "Authorization: Bearer $DEV_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"partName":"法兰盘","planId":"plan-1234abcd","status":"SUCCESS","durationSec":96.5,"operator":"zhangsan","startedAt":1792969500000,"finishedAt":1792969596500}'

# 5) 上报实时状态（会推送到 /ws/status）
curl -X POST $BASE/api/v1/status \
  -H "Authorization: Bearer $DEV_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"tablet-1","connected":true,"mode":"RUNNING","isSimulated":false,"taskName":"法兰盘粗车","progress":42.5,"message":"加工中","xMm":12.3,"zMm":-45.6,"spindleRpm":1200}'

# 6) 批量上传操作日志（同样用设备令牌）
curl -X POST $BASE/api/v1/logs \
  -H "Authorization: Bearer $DEV_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"tablet-1","logs":[{"timestamp":1792969600000,"level":"INFO","category":"SYNC","message":"配置同步完成"}]}'
```

### 7.2 后台登录 → 管理配置 → 一键发布 APK

```bash
BASE=http://127.0.0.1:8080

# 1) 登录，取会话 token
TOKEN=<登录响应中的 token>
curl -X POST $BASE/api/v1/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'

# 2) 校验凭据并读取当前配置
curl $BASE/api/v1/auth/check -H "Authorization: Bearer $TOKEN"
curl $BASE/api/v1/config -H "Authorization: Bearer $TOKEN"

# 3) 修改全局设备配置（需 device 模块；超管无需额外授权）
curl -X PUT $BASE/api/v1/device \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"数控车床 #1","controlCard":"FANUC_0I","useSimulator":false,"maxTravelXmm":300.0,"maxTravelZmm":500.0,"maxRpm":3000,"maxFeedMmPerMin":5000.0,"comm":{"protocol":"MODBUS_TCP","ipAddress":"192.168.1.100","port":502,"serialPort":"/dev/ttyUSB0","baudRate":115200,"deviceId":1,"timeoutMs":2000}}'

# 4) 新增刀具（id 留空由服务端生成）
curl -X POST $BASE/api/v1/tools \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"toolNumber":1,"toolType":"TURNING","name":"外圆粗车刀","lengthMm":120.0}'

# 5) 查看当前发布版本与最新候选构建
curl $BASE/api/v1/app-version-info -H "Authorization: Bearer $TOKEN"
curl $BASE/api/v1/app-latest-candidate -H "Authorization: Bearer $TOKEN"

# 6) 一键发布最新构建
curl -X POST $BASE/api/v1/app-publish-latest \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"changelog":"一键发布最新构建"}'

# 7) 客户端视角校验：查询版本 + 下载 APK（开放接口）
curl $BASE/api/v1/app-version
curl -L -o cnc-host.apk $BASE/api/v1/app-download
```

### 7.3 创建用户并分配权限 → 新用户登录验证

```bash
BASE=http://127.0.0.1:8080

# 1) 超管创建操作员账号（密码 ≥ 6）
curl -X POST $BASE/api/v1/users \
  -H "Authorization: Bearer <超管凭据>" \
  -H "Content-Type: application/json" \
  -d '{"username":"lisi","password":"op123456","displayName":"李四","role":"OPERATOR","permissions":["overview","tasks","logs"]}'

# 2) 分配/调整权限（也可在此启用或禁用账号）
USER_ID=<上一步返回的 id>
curl -X PUT $BASE/api/v1/users/$USER_ID \
  -H "Authorization: Bearer <超管凭据>" \
  -H "Content-Type: application/json" \
  -d '{"permissions":["overview","tools","tasks","drawings"],"enabled":true}'

# 3) 新用户登录
curl -X POST $BASE/api/v1/admin/login \
  -H "Content-Type: application/json" \
  -d '{"username":"lisi","password":"op123456"}'

# 4) 用新会话验证权限：可读配置、可写任务、无权访问用户列表
NEW_TOKEN=<lisi 登录返回的 token>
curl $BASE/api/v1/users/me -H "Authorization: Bearer $NEW_TOKEN"
curl $BASE/api/v1/config -H "Authorization: Bearer $NEW_TOKEN"
curl -X POST $BASE/api/v1/tasks -H "Authorization: Bearer $NEW_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"partName":"法兰盘","status":"SUCCESS"}'
curl -i $BASE/api/v1/users -H "Authorization: Bearer $NEW_TOKEN"   # 需 users 模块，预期 403

# 5) 新用户修改本人密码（成功后旧会话全部失效，需重新登录）
curl -X PUT $BASE/api/v1/users/me/password \
  -H "Authorization: Bearer $NEW_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"oldPassword":"op123456","newPassword":"op654321"}'
```
