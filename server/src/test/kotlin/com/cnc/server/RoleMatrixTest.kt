package com.cnc.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 角色矩阵落地测试（ADR-0003，五角色 + 默认权限 + 只读角色写操作拦截）。
 */
class RoleMatrixTest {

    private fun freshDb(): String =
        java.nio.file.Files.createTempFile("cnc-role-", ".db").toFile().absolutePath

    @Test
    fun `default permissions per role match matrix`() {
        assertEquals(emptyList(), defaultPermissionsForRole(ROLE_SUPER_ADMIN))
        assertEquals(ALL_MODULE_PERMISSIONS, defaultPermissionsForRole(ROLE_ADMIN))
        assertEquals(
            listOf("overview", "tools", "materials", "parts", "plans", "drawings"),
            defaultPermissionsForRole(ROLE_PROCESS_ENGINEER)
        )
        assertEquals(listOf("overview", "device", "tasks"), defaultPermissionsForRole(ROLE_OPERATOR))
        assertEquals(listOf("overview", "tasks", "logs"), defaultPermissionsForRole(ROLE_QUALITY))

        // 只读角色：质检员对任务/日志仅可读
        assertEquals(setOf("tasks", "logs"), readOnlyModulesForRole(ROLE_QUALITY))
        assertTrue(readOnlyModulesForRole(ROLE_PROCESS_ENGINEER).isEmpty())
    }

    @Test
    fun `create user fills role default permissions when not specified`() {
        Db.init(freshDb())
        val quality = Db.createUser("q1", "123456", "质检员1", ROLE_QUALITY, emptyList())
        assertEquals(listOf("overview", "tasks", "logs"), quality.permissions)

        val engineer = Db.createUser("e1", "123456", "工艺员1", ROLE_PROCESS_ENGINEER, emptyList())
        assertEquals(
            listOf("overview", "tools", "materials", "parts", "plans", "drawings"),
            engineer.permissions
        )

        // 显式指定权限时不覆盖
        val custom = Db.createUser("op1", "123456", "操作员1", ROLE_OPERATOR, listOf("logs"))
        assertEquals(listOf("logs"), custom.permissions)
    }

    @Test
    fun `quality role can read tasks but cannot write`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        Db.createUser("q1", "123456", "质检员", ROLE_QUALITY, emptyList())

        val login = client.post("/api/v1/admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"q1","password":"123456"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = Json { ignoreUnknownKeys = true }
            .decodeFromString<AdminLoginResult>(login.bodyAsText()).token

        // GET /tasks 在权限内 → 200
        val read = client.get("/api/v1/tasks") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, read.status)

        // POST /tasks 对质检员只读 → 403
        val write = client.post("/api/v1/tasks") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"partName":"x","planId":"p1","status":"DONE","durationSec":1,"operator":"q1","startedAt":1,"finishedAt":2}""")
        }
        assertEquals(HttpStatusCode.Forbidden, write.status)

        // 未授权模块 GET → 403（/users 要求 users 权限，质检员无此权限）
        val users = client.get("/api/v1/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, users.status)
    }

    @Test
    fun `process engineer can write tools but cannot access users`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        Db.createUser("e1", "123456", "工艺员", ROLE_PROCESS_ENGINEER, emptyList())

        val login = client.post("/api/v1/admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"e1","password":"123456"}""")
        }
        val token = Json { ignoreUnknownKeys = true }
            .decodeFromString<AdminLoginResult>(login.bodyAsText()).token

        // 写刀具在权限内 → 200
        val writeTool = client.post("/api/v1/tools") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"toolNumber":99,"toolType":"TURNING","name":"工艺员新增","material":"CARBIDE","nominalDiameterMm":0,"lengthMm":100,"maxRpm":3000,"maxFeedMmPerMin":500,"wearStatus":"NEW","usageCount":0}""")
        }
        assertEquals(HttpStatusCode.OK, writeTool.status)

        // 用户管理无权限 → 403
        val users = client.get("/api/v1/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, users.status)
        assertFalse(writeTool.status == HttpStatusCode.Forbidden)
    }
}
