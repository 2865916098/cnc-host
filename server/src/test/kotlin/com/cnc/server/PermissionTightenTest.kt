package com.cnc.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R6 回归测试：权限收紧。
 *
 * - 会话用户 GET 不再宽松放行，一律按模块权限校验
 * - 设备令牌白名单：仅 /config*、/auth/check、/devices/{自身}、POST /tasks、POST /status
 */
class PermissionTightenTest {

    private fun freshDb(): String =
        Files.createTempFile("cnc-perm-", ".db").toFile().absolutePath

    @Test
    fun `session user without module permission is denied on GET`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        val user = Db.createUser("op-tasks", "123456", "仅任务", ROLE_OPERATOR, listOf("tasks"))
        val token = Db.createSession(user.username, user.id)

        // 有 tasks 权限：GET /tasks 放行
        val tasks = client.get("/api/v1/tasks") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, tasks.status)

        // 无 devices 权限：GET /devices 拒绝（收紧前为宽松放行）
        val devices = client.get("/api/v1/devices") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, devices.status)

        // 无 tools 权限：GET /config 属公共只读（requiredPermission=null），仍放行
        val config = client.get("/api/v1/config") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, config.status)
    }

    @Test
    fun `device token whitelist blocks logs users and device list`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        val info = Db.registerDevice(
            RegisterRequest(
                unitName = "示范单位", contactName = "张三", phone = "13800000000",
                province = "广东省", city = "深圳市", serverAddress = "",
                deviceId = "dev-w", hardwareId = "hw-w", appVersion = "1.0.0"
            )
        )
        val t = info.token

        // 白名单外：日志查询、用户列表、设备列表、管理口令修改 全部 403
        for (path in listOf("/api/v1/logs", "/api/v1/users", "/api/v1/devices", "/api/v1/devices/org-summary")) {
            val resp = client.get(path) { header(HttpHeaders.Authorization, "Bearer $t") }
            assertEquals(HttpStatusCode.Forbidden, resp.status, "GET $path 应被设备令牌白名单拒绝")
        }

        // 白名单内：配置、配置版本、自检 放行
        for (path in listOf("/api/v1/config", "/api/v1/config/version", "/api/v1/auth/check", "/api/v1/devices/dev-w")) {
            val resp = client.get(path) { header(HttpHeaders.Authorization, "Bearer $t") }
            assertEquals(HttpStatusCode.OK, resp.status, "GET $path 应被设备令牌白名单放行")
        }

        // 白名单内：回传任务 / 实时状态 放行
        val task = client.post("/api/v1/tasks") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"task-w1","partName":"试切件"}""")
        }
        assertEquals(HttpStatusCode.OK, task.status)

        val status = client.post("/api/v1/status") {
            header(HttpHeaders.Authorization, "Bearer $t")
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"dev-w"}""")
        }
        assertEquals(HttpStatusCode.OK, status.status)
    }
}
