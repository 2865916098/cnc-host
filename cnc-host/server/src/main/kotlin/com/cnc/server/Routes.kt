package com.cnc.server

import io.ktor.http.HttpStatusCode
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
import io.ktor.server.response.respond
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
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

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
                if (credential.token == adminToken) {
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
            if (token != adminToken) {
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
            post("/devices/register") {
                val req = call.receive<RegisterRequest>()
                val info = Db.registerDevice(req)
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
                val info = Db.heartbeat(id)
                if (info != null) call.respond(info)
                else call.respond(HttpStatusCode.NotFound, SimpleResult(ok = false, message = "设备不存在"))
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
                put("/devices/{id}/permissions") {
                    val id = call.parameters["id"].orEmpty()
                    val body = call.receive<DevicePermissionsUpdate>()
                    val ok = Db.updateDevicePermissions(id, body.permissions)
                    call.respond(SimpleResult(ok = ok, message = if (ok) "权限已更新" else "设备不存在"))
                }
                post("/admin-password") {
                    val body = call.receive<AdminPasswordUpdate>()
                    if (body.newPassword.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, SimpleResult(ok = false, message = "密码不能为空"))
                        return@post
                    }
                    Db.setAdminPassword(body.newPassword)
                    call.respond(SimpleResult(ok = true, message = "管理员密码已更新"))
                }
            }
        }
    }
}
