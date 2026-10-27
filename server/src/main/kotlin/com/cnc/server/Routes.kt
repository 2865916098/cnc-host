package com.cnc.server

import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
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
        bearer("admin") {
            authenticate { credential ->
                // 兼容环境变量管理口令，以及账号密码登录签发的会话 token
                if (credential.token == adminToken || Db.isValidSession(credential.token)) {
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
                    val token = Db.createSession(req.username)
                    call.respond(
                        AdminLoginResult(
                            ok = true,
                            message = "登录成功",
                            token = token,
                            username = req.username,
                            expiresAt = System.currentTimeMillis() + Db.SESSION_TTL_MS
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

        authenticate("admin") {
            route("/api/v1") {
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
                post("/admin-password") {
                    val body = call.receive<AdminPasswordUpdate>()
                    if (body.newPassword.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "密码不能为空"))
                        return@post
                    }
                    if (body.newUsername.isNotBlank()) Db.setAdminUsername(body.newUsername.trim())
                    Db.setAdminPassword(body.newPassword)
                    call.respond(SimpleResult(ok = true, message = "管理员账号密码已更新"))
                }
            }
        }
    }
}
