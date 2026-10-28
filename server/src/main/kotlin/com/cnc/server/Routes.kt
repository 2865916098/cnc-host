package com.cnc.server

import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.utils.io.core.readBytes
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.io.File
import java.security.MessageDigest

/** 发布的 APK 存放位置（可用环境变量 CNC_APK 覆盖）。 */
private fun apkFile(): File =
    File(System.getenv("CNC_APK") ?: "data/apk/cnc-host-update.apk")

private fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** 当前请求的 Bearer token。 */
private fun ApplicationCall.bearerToken(): String =
    request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer").trim()

/** 管理端操作者：环境变量口令 / 设备令牌视为超管虚拟账号。 */
private data class AdminActor(
    val id: String,
    val username: String,
    val role: String,
    val isSuper: Boolean
)

private fun adminActorOf(call: ApplicationCall, adminToken: String): AdminActor? {
    val token = call.bearerToken()
    if (token == adminToken || Db.isValidDeviceToken(token)) {
        return AdminActor(id = "", username = Db.adminUsername(), role = ROLE_SUPER_ADMIN, isSuper = true)
    }
    val user = Db.getSessionUser(token) ?: return null
    return AdminActor(user.id, user.username, user.role, user.role == ROLE_SUPER_ADMIN)
}

private val USER_ROLES = setOf(ROLE_SUPER_ADMIN, ROLE_ADMIN, ROLE_OPERATOR)

/**
 * 管理端「方法 + 路径」权限映射；返回 null 表示所有已登录用户可访问。
 * 模块 key 与前端导航一致：overview/device/tools/materials/parts/plans/
 * drawings/devices/tasks/users/app/logs。
 */
private fun requiredPermission(method: String, path: String): String? {
    val p = path.substringAfter("/api/v1", path)
    return when {
        // 查看本人信息 / 修改本人密码属于基础能力，不受 users 模块权限限制
        p == "/users/me" || p == "/users/me/password" -> null
        p.contains("/users") || p.contains("/admin-password") -> "users"
        p.contains("/app-publish") || p.contains("/app-latest-candidate") -> "app"
        p.contains("/materials") -> "materials"
        p.contains("/parts") || p.contains("/blanks") -> "parts"
        p.contains("/plans") || p.contains("/process-steps") -> "plans"
        p.contains("/drawings") || p.contains("/corrections") -> "drawings"
        p.contains("/tools") -> "tools"
        p.contains("/devices") -> "devices"
        p.contains("/tasks") -> "tasks"
        p.contains("/logs") -> "logs"
        method == "PUT" && p == "/device" -> "device"
        p == "/config" || p.startsWith("/config/") ||
            p == "/auth/check" || p == "/status" || p == "/admin/logout" -> null
        else -> null
    }
}

/**
 * 服务端装配。
 *
 * 提供：
 * - /api/v1/        配置与任务 REST 接口（Bearer Token 鉴权）
 * - /ws/status      状态推送 WebSocket（?token=...）
 * - /admin/         网页管理后台（静态资源）
 */
fun Application.cncServerModule(adminToken: String) {

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    install(ContentNegotiation) { json(json) }
    install(CallLogging) { level = Level.INFO }
    install(CORS) {
        anyHost()
        allowHeader("Authorization")
        allowHeader("Content-Type")
        allowHeader("X-Auth-Token")
    }
    install(WebSockets)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("请求处理异常", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                SimpleResult(ok = false, message = cause.message ?: "服务器内部错误")
            )
        }
    }
    install(Authentication) {
        bearer("device") {
            authenticate { credential ->
                if (Db.isValidDeviceToken(credential.token)) {
                    io.ktor.server.auth.UserIdPrincipal("device")
                } else {
                    null
                }
            }
        }
        bearer("admin") {
            authenticate { credential ->
                // 兼容：环境变量管理口令 / 后台登录会话 token / 注册设备的设备令牌
                if (credential.token == adminToken ||
                    Db.isValidSession(credential.token) ||
                    Db.isValidDeviceToken(credential.token)
                ) {
                    io.ktor.server.auth.UserIdPrincipal("admin")
                } else {
                    null
                }
            }
        }
    }

    routing {
        get("/") { call.respondText("CNC 服务端运行中。管理后台：/admin/", contentType = io.ktor.http.ContentType.Text.Plain) }

        // 静态管理后台；index.html 由 resources/static 提供
        staticResources("/admin", "static", index = "index.html")

        get("/api/v1/health") {
            call.respond(SimpleResult(ok = true, message = "ok", id = "cnc-server"))
        }

        // 管理后台实时状态流
        webSocket("/ws/status") {
            val token = call.request.queryParameters["token"].orEmpty()
            if (token != adminToken && !Db.isValidSession(token)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid token"))
                return@webSocket
            }
            StatusHub.register(this)
            Db.latestStatus()?.let {
                send(Frame.Text(json.encodeToString(DeviceStatus.serializer(), it)))
            }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        // 管理后台只订阅；收到文本时忽略，保持连接存活
                    }
                }
            } finally {
                StatusHub.unregister(this)
            }
        }

        // 设备注册与心跳（开放接口，注册前客户端尚无管理口令）
        route("/api/v1") {
            // 管理后台账号密码登录（开放接口）
            post("/admin/login") {
                val req = call.receive<AdminLoginRequest>()
                if (Db.verifyAdminAccount(req.username, req.password)) {
                    // users 表中存在该账号时带出角色/权限；老库回退登录时按超管处理
                    val account = Db.findUserByUsername(req.username)
                    val token = Db.createSession(req.username, account?.id ?: "")
                    call.respond(
                        AdminLoginResult(
                            ok = true,
                            message = "登录成功",
                            token = token,
                            username = req.username,
                            expiresAt = System.currentTimeMillis() + Db.SESSION_TTL_MS,
                            role = account?.role ?: ROLE_SUPER_ADMIN,
                            permissions = account?.permissions ?: emptyList(),
                            displayName = account?.displayName ?: req.username
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        AdminLoginResult(ok = false, message = "账号或密码错误")
                    )
                }
            }
            post("/devices/register") {
                val req = call.receive<RegisterRequest>()
                // 同一台设备（按硬件标识）只能注册一次；需后台注销后才能重新注册
                if (req.hardwareId.isNotBlank()) {
                    val existing = Db.findByHardwareId(req.hardwareId)
                    if (existing != null) {
                        call.respond(
                            HttpStatusCode.Conflict,
                            SimpleResult(ok = false, message = "设备已注册，如需重新注册请联系管理员")
                        )
                        return@post
                    }
                }
                val info = runCatching { Db.registerDevice(req) }.getOrElse {
                    // 兜底：并发下唯一索引冲突
                    call.respond(
                        HttpStatusCode.Conflict,
                        SimpleResult(ok = false, message = "设备已注册，如需重新注册请联系管理员")
                    )
                    return@post
                }
                call.respond(info)
            }
            get("/devices/{id}") {
                val id = call.parameters["id"].orEmpty()
                val info = Db.getDevice(id)
                if (info != null) call.respond(info)
                else call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "设备不存在"))
            }
            post("/devices/{id}/heartbeat") {
                val id = call.parameters["id"].orEmpty()
                val req = runCatching { call.receive<HeartbeatRequest>() }.getOrDefault(HeartbeatRequest())
                val info = Db.heartbeat(id, req.appVersion)
                if (info != null) call.respond(info)
                else call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "设备不存在或已注销"))
            }
            // 客户端自动更新：查询当前发布版本（开放接口）
            get("/app-version") {
                val info = Db.getAppVersion()
                if (info != null) call.respond(info)
                else call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "尚未发布版本"))
            }
            // 客户端自动更新：下载最新 APK（开放接口，设备无管理口令）
            get("/app-download") {
                val apk = apkFile()
                if (apk.isFile && Db.getAppVersion() != null) {
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"cnc-host.apk\"")
                    call.respondFile(apk)
                } else {
                    call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "尚未发布版本"))
                }
            }
            post("/devices/{id}/verify-admin") {
                val id = call.parameters["id"].orEmpty()
                if (Db.getDevice(id) == null) {
                    call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "设备不存在"))
                    return@post
                }
                val req = call.receive<AdminVerifyRequest>()
                val ok = Db.verifyAdminPassword(req.password)
                call.respond(SimpleResult(ok = ok, message = if (ok) "管理员密码正确" else "密码错误"))
            }
        }

        // 操作日志：设备批量上传（bearer("device") 保护，心跳自动补传）
        authenticate("device") {
            route("/api/v1") {
                post("/logs") {
                    val req = call.receive<UploadLogsRequest>()
                    Db.insertLogs(req.deviceId, req.logs)
                    call.respond(SimpleResult(ok = true, message = "已接收 ${req.logs.size} 条日志"))
                }
            }
        }

        authenticate("admin") {
            route("/api/v1") {
                // ===== 权限拦截：环境变量口令 / 设备令牌放行；超管放行；其余按模块授权 =====
                intercept(ApplicationCallPipeline.Call) {
                    val token = call.bearerToken()
                    if (token == adminToken) return@intercept
                    if (Db.isValidDeviceToken(token)) {
                        // 设备令牌权限最小化：仅允许读配置 + 回传任务记录/实时状态，禁止账号管理与写配置
                        val m = call.request.httpMethod.value
                        val p = call.request.path()
                        val allowed = (m == "GET" && !p.contains("/users") && !p.contains("/admin-password")) ||
                            (m == "POST" && (p.endsWith("/tasks") || p.endsWith("/status")))
                        if (!allowed) {
                            call.respond(HttpStatusCode.Forbidden, SimpleResult(ok = false, message = "设备令牌仅允许同步配置与回传数据"))
                            finish()
                        }
                        return@intercept
                    }
                    // 会话仍有效但账号被禁用/删除时，getSessionUser 返回 null，必须拒绝
                    val user = Db.getSessionUser(token)
                    if (user == null) {
                        call.respond(HttpStatusCode.Unauthorized, SimpleResult(ok = false, message = "登录已失效"))
                        finish()
                        return@intercept
                    }
                    if (user.role == ROLE_SUPER_ADMIN) return@intercept
                    val method = call.request.httpMethod.value
                    val path = call.request.path()
                    val required = requiredPermission(method, path) ?: return@intercept
                    val allowed = if (method == "GET" && !path.contains("/users")) {
                        // 只读请求对已登录用户宽松放行（账号管理除外）
                        user.permissions.isNotEmpty()
                    } else {
                        user.permissions.contains(required)
                    }
                    if (!allowed) {
                        call.respond(HttpStatusCode.Forbidden, SimpleResult(ok = false, message = "无权限执行该操作"))
                        finish()
                    }
                }

                // ===== 当前账号 =====
                get("/users/me") {
                    val token = call.bearerToken()
                    if (Db.isValidDeviceToken(token)) {
                        call.respond(HttpStatusCode.Forbidden, SimpleResult(ok = false, message = "设备令牌无权访问账号管理"))
                        return@get
                    }
                    if (token == adminToken) {
                        call.respond(
                            SessionUserDto(
                                id = "", username = Db.adminUsername(), displayName = "超级管理员",
                                role = ROLE_SUPER_ADMIN, permissions = emptyList(), superAdmin = true
                            )
                        )
                        return@get
                    }
                    val user = Db.getSessionUser(token)
                    if (user == null) {
                        call.respond(HttpStatusCode.Unauthorized, SimpleResult(ok = false, message = "登录已失效"))
                        return@get
                    }
                    call.respond(
                        SessionUserDto(
                            id = user.id, username = user.username, displayName = user.displayName,
                            role = user.role, permissions = user.permissions,
                            superAdmin = user.role == ROLE_SUPER_ADMIN
                        )
                    )
                }

                // ===== 用户管理 =====
                get("/users") {
                    call.respond(Db.listUsers())
                }

                post("/users") {
                    val actor = adminActorOf(call, adminToken)
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, SimpleResult(ok = false, message = "登录已失效"))
                    val body = call.receive<CreateUserRequest>()
                    if (body.username.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "用户名不能为空"))
                        return@post
                    }
                    if (body.password.length < 6) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "密码长度至少 6 位"))
                        return@post
                    }
                    if (body.role !in USER_ROLES) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "无效的角色"))
                        return@post
                    }
                    if (body.role == ROLE_SUPER_ADMIN && !actor.isSuper) {
                        call.respond(HttpStatusCode.Forbidden, SimpleResult(ok = false, message = "无权限执行该操作"))
                        return@post
                    }
                    val result = runCatching {
                        Db.createUser(body.username.trim(), body.password, body.displayName, body.role, body.permissions)
                    }
                    val error = result.exceptionOrNull()
                    if (error is IllegalArgumentException) {
                        call.respond(HttpStatusCode.Conflict, SimpleResult(ok = false, message = error.message ?: "用户名已存在"))
                        return@post
                    }
                    call.respond(result.getOrElse { throw it })
                }

                // 改本人密码：需在 /users/{id} 之前注册；成功后强制重新登录
                put("/users/me/password") {
                    val token = call.bearerToken()
                    if (token == adminToken || Db.isValidDeviceToken(token)) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "当前登录方式无需修改密码"))
                        return@put
                    }
                    val user = Db.getSessionUser(token)
                    if (user == null) {
                        call.respond(HttpStatusCode.Unauthorized, SimpleResult(ok = false, message = "登录已失效"))
                        return@put
                    }
                    val body = call.receive<ChangePasswordRequest>()
                    if (body.newPassword.length < 6) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "密码长度至少 6 位"))
                        return@put
                    }
                    if (!Db.changeOwnPassword(user.id, body.oldPassword, body.newPassword)) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "原密码错误"))
                        return@put
                    }
                    Db.deleteUserSessions(user.id, user.username)
                    call.respond(SimpleResult(ok = true, message = "密码已修改，请重新登录"))
                }

                put("/users/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    val actor = adminActorOf(call, adminToken)
                        ?: return@put call.respond(HttpStatusCode.Unauthorized, SimpleResult(ok = false, message = "登录已失效"))
                    val target = Db.findUserById(id)
                    if (target == null) {
                        call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "用户不存在"))
                        return@put
                    }
                    val body = call.receive<UpdateUserRequest>()
                    // 非超管不能修改超管账号，也不能把普通账号提升为超管
                    if (!actor.isSuper && (target.role == ROLE_SUPER_ADMIN || body.role == ROLE_SUPER_ADMIN)) {
                        call.respond(HttpStatusCode.Forbidden, SimpleResult(ok = false, message = "无权限执行该操作"))
                        return@put
                    }
                    val newRole = body.role ?: target.role
                    if (newRole !in USER_ROLES) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "无效的角色"))
                        return@put
                    }
                    if (body.enabled == false && actor.id == id) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "不能禁用自己"))
                        return@put
                    }
                    if (target.role == ROLE_SUPER_ADMIN &&
                        (newRole != ROLE_SUPER_ADMIN || body.enabled == false) &&
                        Db.countSuperAdmins() <= 1
                    ) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "系统至少保留一个超级管理员"))
                        return@put
                    }
                    val updated = Db.updateUser(
                        id = id,
                        displayName = body.displayName?.ifBlank { target.displayName } ?: target.displayName,
                        role = newRole,
                        permissions = body.permissions ?: target.permissions,
                        enabled = body.enabled ?: target.enabled
                    )
                    if (updated == null) {
                        call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "用户不存在"))
                    } else {
                        call.respond(updated)
                    }
                }

                delete("/users/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    val actor = adminActorOf(call, adminToken)
                        ?: return@delete call.respond(HttpStatusCode.Unauthorized, SimpleResult(ok = false, message = "登录已失效"))
                    val target = Db.findUserById(id)
                    if (target == null) {
                        call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "用户不存在"))
                        return@delete
                    }
                    if (actor.id == id) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "不能删除自己"))
                        return@delete
                    }
                    if (!actor.isSuper && target.role == ROLE_SUPER_ADMIN) {
                        call.respond(HttpStatusCode.Forbidden, SimpleResult(ok = false, message = "无权限执行该操作"))
                        return@delete
                    }
                    if (target.role == ROLE_SUPER_ADMIN && Db.countSuperAdmins() <= 1) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "系统至少保留一个超级管理员"))
                        return@delete
                    }
                    if (!Db.deleteUser(id)) {
                        call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "用户不存在"))
                    } else {
                        call.respond(SimpleResult(ok = true, message = "用户已删除"))
                    }
                }

                post("/users/{id}/reset-password") {
                    val id = call.parameters["id"].orEmpty()
                    val actor = adminActorOf(call, adminToken)
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, SimpleResult(ok = false, message = "登录已失效"))
                    val target = Db.findUserById(id)
                    if (target == null) {
                        call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "用户不存在"))
                        return@post
                    }
                    if (!actor.isSuper && target.role == ROLE_SUPER_ADMIN) {
                        call.respond(HttpStatusCode.Forbidden, SimpleResult(ok = false, message = "无权限执行该操作"))
                        return@post
                    }
                    val body = call.receive<ResetPasswordRequest>()
                    if (body.newPassword.length < 6) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "密码长度至少 6 位"))
                        return@post
                    }
                    if (!Db.resetUserPassword(id, body.newPassword)) {
                        call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "用户不存在"))
                        return@post
                    }
                    Db.deleteUserSessions(target.id, target.username)
                    call.respond(SimpleResult(ok = true, message = "密码已重置"))
                }

                get("/auth/check") {
                    call.respond(SimpleResult(ok = true, message = "token valid"))
                }

                // 登出：使当前会话 token 失效（环境变量口令不受影响）
                post("/admin/logout") {
                    val auth = call.request.headers["Authorization"].orEmpty()
                    val token = auth.removePrefix("Bearer").trim()
                    if (token.isNotBlank()) Db.deleteSession(token)
                    call.respond(SimpleResult(ok = true, message = "已登出"))
                }

                get("/config") {
                    call.respond(Db.snapshot())
                }

                get("/config/version") {
                    call.respond(VersionResult(Db.version()))
                }

                put("/device") {
                    val body = call.receive<DeviceConfig>()
                    Db.saveDevice(body)
                    call.respond(SimpleResult(ok = true, message = "设备配置已保存"))
                }

                // ===== 刀具 =====
                post("/tools") {
                    val saved = Db.upsertTool(call.receive<ToolConfig>())
                    call.respond(saved)
                }
                delete("/tools/{id}") {
                    Db.deleteTool(call.parameters["id"].orEmpty())
                    call.respond(SimpleResult(ok = true, message = "刀具已删除"))
                }

                // ===== 材料 =====
                post("/materials") {
                    call.respond(Db.upsertMaterial(call.receive<MaterialConfig>()))
                }
                delete("/materials/{id}") {
                    Db.deleteMaterial(call.parameters["id"].orEmpty())
                    call.respond(SimpleResult(ok = true, message = "材料已删除"))
                }

                // ===== 零件 / 毛坯 =====
                post("/parts") {
                    call.respond(Db.upsertPart(call.receive<PartConfig>()))
                }
                delete("/parts/{id}") {
                    Db.deletePart(call.parameters["id"].orEmpty())
                    call.respond(SimpleResult(ok = true, message = "零件已删除"))
                }
                post("/blanks") {
                    call.respond(Db.upsertBlank(call.receive<BlankConfig>()))
                }
                delete("/blanks/{id}") {
                    Db.deleteBlank(call.parameters["id"].orEmpty())
                    call.respond(SimpleResult(ok = true, message = "毛坯已删除"))
                }

                // ===== 工艺方案 =====
                post("/plans") {
                    call.respond(Db.upsertPlan(call.receive<ProcessPlanConfig>()))
                }
                delete("/plans/{id}") {
                    Db.deletePlan(call.parameters["id"].orEmpty())
                    call.respond(SimpleResult(ok = true, message = "工艺方案已删除"))
                }

                // ===== 图纸 =====
                post("/drawings") {
                    call.respond(Db.upsertDrawing(call.receive<DrawingConfig>()))
                }
                delete("/drawings/{id}") {
                    Db.deleteDrawing(call.parameters["id"].orEmpty())
                    call.respond(SimpleResult(ok = true, message = "图纸已删除"))
                }
                post("/corrections") {
                    call.respond(Db.upsertCorrection(call.receive<DrawingCorrectionConfig>()))
                }

                // ===== 任务记录 =====
                get("/tasks") {
                    call.respond(Db.tasks())
                }
                post("/tasks") {
                    call.respond(Db.upsertTask(call.receive<TaskRecord>()))
                }

                // ===== 操作日志（管理端查询/清理）=====
                get("/logs") {
                    val deviceId = call.request.queryParameters["deviceId"]
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                    call.respond(Db.listLogs(deviceId, limit))
                }
                delete("/logs") {
                    val deviceId = call.request.queryParameters["deviceId"]
                    val olderThanMs = call.request.queryParameters["olderThanMs"]?.toLongOrNull()
                        ?: (System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
                    val deleted = Db.deleteLogsOlderThan(deviceId, olderThanMs)
                    call.respond(SimpleResult(ok = true, message = "已清理 $deleted 条日志"))
                }

                // ===== 客户端实时状态 =====
                get("/status") {
                    call.respond(Db.latestStatus() ?: DeviceStatus())
                }
                post("/status") {
                    val status = call.receive<DeviceStatus>()
                    Db.saveStatus(status)
                    StatusHub.broadcast(json.encodeToString(DeviceStatus.serializer(), status))
                    call.respond(SimpleResult(ok = true, message = "status accepted"))
                }

                // ===== 设备管理（后台）=====
                get("/devices") {
                    call.respond(Db.listDevices())
                }
                put("/devices/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    val body = call.receive<DeviceUpdateRequest>()
                    if (body.unitName.isBlank() || body.contactName.isBlank() || body.phone.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "单位、联系人、电话不能为空"))
                        return@put
                    }
                    val ok = Db.updateDeviceInfo(id, body)
                    call.respond(SimpleResult(ok = ok, message = if (ok) "设备信息已更新" else "设备不存在"))
                }
                delete("/devices/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    val ok = Db.deleteDevice(id)
                    call.respond(SimpleResult(ok = ok, message = if (ok) "设备已注销，可重新注册" else "设备不存在"))
                }
                put("/devices/{id}/permissions") {
                    val id = call.parameters["id"].orEmpty()
                    val body = call.receive<DevicePermissionsUpdate>()
                    val ok = Db.updateDevicePermissions(id, body.permissions)
                    call.respond(SimpleResult(ok = ok, message = if (ok) "权限已更新" else "设备不存在"))
                }

                // ===== 客户端版本发布（multipart 上传 APK + 版本号）=====
                get("/app-version-info") {
                    call.respond(Db.getAppVersion() ?: AppVersionInfo())
                }
                post("/app-publish") {
                    val multipart = call.receiveMultipart()
                    var versionCode = 0
                    var versionName = ""
                    var changelog = ""
                    var savedBytes = 0L
                    var savedFile = false
                    val target = apkFile()
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> when (part.name) {
                                "versionCode" -> versionCode = part.value.trim().toIntOrNull() ?: 0
                                "versionName" -> versionName = part.value.trim()
                                "changelog" -> changelog = part.value.trim()
                            }
                            is PartData.FileItem -> if (part.name == "apk") {
                                val bytes = part.provider().readBytes()
                                tmp.writeBytes(bytes)
                                savedBytes = bytes.size.toLong()
                                savedFile = true
                            }
                            else -> {}
                        }
                        part.dispose()
                    }
                    if (!savedFile || versionCode <= 0 || versionName.isBlank()) {
                        tmp.delete()
                        call.respond(
                            HttpStatusCode.BadRequest,
                            SimpleResult(ok = false, message = "需要上传 APK 文件并填写版本号(versionCode)与版本名(versionName)")
                        )
                        return@post
                    }
                    if (!tmp.renameTo(target)) {
                        runCatching { tmp.copyTo(target, overwrite = true) }
                        tmp.delete()
                    }
                    Db.publishAppVersion(
                        AppVersionInfo(
                            versionCode = versionCode,
                            versionName = versionName,
                            changelog = changelog,
                            sha256 = sha256Of(target),
                            sizeBytes = savedBytes,
                            publishedAt = System.currentTimeMillis()
                        )
                    )
                    call.respond(SimpleResult(ok = true, message = "已发布 v$versionName（$versionCode），${savedBytes / 1024} KB"))
                }
                // ===== 一键发布最新构建：扫描构建目录，无需手动选择本地 APK =====
                get("/app-latest-candidate") {
                    val c = ApkScanner().latest()
                    if (c == null) {
                        call.respond(ApkCandidateResult(found = false))
                    } else {
                        call.respond(
                            ApkCandidateResult(
                                found = true, path = c.path, fileName = c.fileName,
                                versionCode = c.versionCode, versionName = c.versionName,
                                sizeBytes = c.sizeBytes, modifiedAt = c.modifiedAt
                            )
                        )
                    }
                }
                post("/app-publish-latest") {
                    val body = runCatching { call.receive<PublishLatestRequest>() }
                        .getOrDefault(PublishLatestRequest())
                    val c = ApkScanner().latest()
                    if (c == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            SimpleResult(ok = false, message = "未找到可用 APK 构建产物（请先用 gradlew assembleRelease/assembleDebug 构建，或设置 CNC_APK_DIR）")
                        )
                        return@post
                    }
                    val target = apkFile()
                    target.parentFile?.mkdirs()
                    File(c.path).copyTo(target, overwrite = true)
                    Db.publishAppVersion(
                        AppVersionInfo(
                            versionCode = c.versionCode,
                            versionName = c.versionName,
                            changelog = body.changelog,
                            sha256 = sha256Of(target),
                            sizeBytes = c.sizeBytes,
                            publishedAt = System.currentTimeMillis()
                        )
                    )
                    call.respond(
                        SimpleResult(
                            ok = true,
                            message = "已发布最新构建 v${c.versionName}（${c.versionCode}），${c.sizeBytes / 1024} KB，来自 ${c.fileName}"
                        )
                    )
                }
                post("/admin-password") {
                    val body = call.receive<AdminPasswordUpdate>()
                    if (body.newPassword.length < 6) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "密码长度至少 6 位"))
                        return@post
                    }
                    // 旧接口兼容：同步更新种子超管账号，避免 users 表仍保留旧口令
                    val oldUsername = Db.adminUsername()
                    val newUsername = body.newUsername.trim().ifBlank { oldUsername }
                    val sync = runCatching { Db.syncLegacyAdminAccount(oldUsername, newUsername, body.newPassword) }
                    if (sync.isFailure) {
                        call.respond(HttpStatusCode.Conflict, SimpleResult(ok = false, message = "用户名已存在"))
                        return@post
                    }
                    Db.setAdminUsername(newUsername)
                    Db.setAdminPassword(body.newPassword)
                    call.respond(SimpleResult(ok = true, message = "管理员账号密码已更新"))
                }
            }
        }
    }
}
