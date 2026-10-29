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
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SaaS 多租户隔离测试（ADR-0002）。
 *
 * 私有化部署恒为 default 租户；租户上下文来自请求头 X-Tenant-Id。
 * 每个用例使用独立临时 SQLite 库，避免状态串扰。
 */
class TenantIsolationTest {

    private fun freshDb(): String =
        Files.createTempFile("cnc-tenant-", ".db").toFile().absolutePath

    @Test
    fun `config data isolated between tenants`() {
        Db.init(freshDb())
        val toolA = Db.upsertTool(ToolConfig(name = "租户A刀具", toolNumber = 91), tenantId = "tenant-a")
        val toolB = Db.upsertTool(ToolConfig(name = "租户B刀具", toolNumber = 92), tenantId = "tenant-b")

        val snapA = Db.snapshot("tenant-a")
        val snapB = Db.snapshot("tenant-b")
        val snapDefault = Db.snapshot()

        assertTrue(snapA.tools.any { it.id == toolA.id })
        assertTrue(snapA.tools.none { it.id == toolB.id })
        assertTrue(snapB.tools.any { it.id == toolB.id })
        assertTrue(snapB.tools.none { it.id == toolA.id })
        // 默认租户只有种子数据，看不到其它租户的数据
        assertTrue(snapDefault.tools.none { it.id == toolA.id || it.id == toolB.id })

        // 跨租户删除无效：用 tenant-b 上下文删 tenant-a 的刀具
        Db.deleteTool(toolA.id, "tenant-b")
        assertTrue(Db.snapshot("tenant-a").tools.any { it.id == toolA.id })
        // 本租户删除生效
        Db.deleteTool(toolA.id, "tenant-a")
        assertTrue(Db.snapshot("tenant-a").tools.none { it.id == toolA.id })
    }

    @Test
    fun `same username allowed in different tenants but conflicts within one`() {
        Db.init(freshDb())
        val userA = Db.createUser("op", "123456", "租户A操作员", ROLE_OPERATOR, emptyList(), tenantId = "tenant-a")
        val userB = Db.createUser("op", "123456", "租户B操作员", ROLE_OPERATOR, emptyList(), tenantId = "tenant-b")
        assertEquals("租户A操作员", Db.findUserByUsername("op", "tenant-a")?.displayName)
        assertEquals("租户B操作员", Db.findUserByUsername("op", "tenant-b")?.displayName)

        assertFailsWith<IllegalArgumentException> {
            Db.createUser("op", "abcdef", "重复账号", ROLE_OPERATOR, emptyList(), tenantId = "tenant-a")
        }

        // 账号校验带租户：同名不同租户各自独立
        val sessionA = Db.verifyUserAccount("op", "123456", "tenant-a")
        assertNotNull(sessionA)
        assertEquals(userA.id, sessionA.id)
        assertEquals("tenant-a", sessionA.tenantId)
        assertNotNull(Db.verifyUserAccount("op", "123456", "tenant-b"))
        // 默认租户不存在该用户
        assertNull(Db.verifyUserAccount("op", "123456"))

        // 会话绑定租户：tenant-a 的会话解析出的用户带 tenant-a
        val token = Db.createSession(userA.username, userA.id, "tenant-a")
        val sessionUser = Db.getSessionUser(token)
        assertNotNull(sessionUser)
        assertEquals("tenant-a", sessionUser.tenantId)
        assertEquals(userA.id, sessionUser.id)
    }

    @Test
    fun `route rejects session user with mismatched tenant header`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        Db.createUser("manager-a", "123456", "租户A管理员", ROLE_ADMIN, emptyList(), tenantId = "tenant-a")

        // 租户A 登录拿到会话
        val login = client.post("/api/v1/admin/login") {
            contentType(ContentType.Application.Json)
            header("X-Tenant-Id", "tenant-a")
            setBody("""{"username":"manager-a","password":"123456"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = Json { ignoreUnknownKeys = true }
            .decodeFromString<AdminLoginResult>(login.bodyAsText()).token
        assertTrue(token.isNotBlank())

        // 同租户访问正常
        val ok = client.get("/api/v1/config") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Tenant-Id", "tenant-a")
        }
        assertEquals(HttpStatusCode.OK, ok.status)

        // 跨租户访问被拒
        val denied = client.get("/api/v1/config") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Tenant-Id", "tenant-b")
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)

        // 不带租户头 = 默认租户，同样与会话租户不匹配
        val noHeader = client.get("/api/v1/config") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, noHeader.status)

        // 服务器级管理口令不受租户限制
        val root = client.get("/api/v1/config") {
            header(HttpHeaders.Authorization, "Bearer root-token")
        }
        assertEquals(HttpStatusCode.OK, root.status)
    }

    @Test
    fun `route rejects device token with mismatched tenant header`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        val device = Db.registerDevice(
            RegisterRequest(unitName = "租户B工厂", contactName = "张三", phone = "13800000000", hardwareId = "hw-b-001"),
            tenantId = "tenant-b"
        )
        assertTrue(device.token.isNotBlank())
        assertEquals("tenant-b", Db.tenantIdOfDeviceToken(device.token))

        // 同租户访问正常（设备令牌允许读配置）
        val ok = client.get("/api/v1/config") {
            header(HttpHeaders.Authorization, "Bearer ${device.token}")
            header("X-Tenant-Id", "tenant-b")
        }
        assertEquals(HttpStatusCode.OK, ok.status)

        // 跨租户访问被拒
        val denied = client.get("/api/v1/config") {
            header(HttpHeaders.Authorization, "Bearer ${device.token}")
            header("X-Tenant-Id", "tenant-a")
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)

        // 不带租户头 = 默认租户，与设备所属租户不匹配
        val noHeader = client.get("/api/v1/config") {
            header(HttpHeaders.Authorization, "Bearer ${device.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, noHeader.status)
    }

    @Test
    fun `old database migrates to tenant schema and keeps data`() {
        val path = freshDb()
        // 先用 JDBC 建老版本结构（users 带列级 UNIQUE、tools 无 tenant_id），模拟升级前现场
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE users(
                        id TEXT PRIMARY KEY,
                        username TEXT NOT NULL UNIQUE,
                        password_hash TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        role TEXT NOT NULL,
                        permissions TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        last_login_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO users(id, username, password_hash, display_name, role, permissions, enabled, created_at, last_login_at)
                    VALUES('legacy-1', 'legacyop', 'x', '老用户', 'OPERATOR', '[]', 1, 1, 0)
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE tools(
                        id TEXT PRIMARY KEY,
                        toolNumber INTEGER NOT NULL,
                        toolType TEXT NOT NULL,
                        name TEXT NOT NULL,
                        material TEXT NOT NULL,
                        nominalDiameterMm REAL NOT NULL,
                        lengthMm REAL NOT NULL,
                        maxRpm INTEGER NOT NULL,
                        maxFeedMmPerMin REAL NOT NULL,
                        wearStatus TEXT NOT NULL,
                        usageCount INTEGER NOT NULL,
                        notes TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO tools VALUES('legacy-t1', 7, 'TURNING', '老刀具', 'CARBIDE', 0.0, 100.0, 3000, 500.0, 'NEW', 0, '')
                    """.trimIndent()
                )
            }
        }

        // 走完整初始化（含迁移）
        Db.init(path)

        // 老数据保留且归入默认租户
        val legacyUser = Db.findUserByUsername("legacyop")
        assertNotNull(legacyUser)
        assertEquals("老用户", legacyUser.displayName)
        assertTrue(Db.snapshot().tools.any { it.id == "legacy-t1" })
        // 老库已有数据，不应被种子覆盖
        assertTrue(Db.snapshot().tools.none { it.id == "srv-t1" })

        // 迁移后唯一性升级为 (tenant_id, username)：同名可建于其它租户，默认租户仍冲突
        Db.createUser("legacyop", "123456", "新租户同名", ROLE_OPERATOR, emptyList(), tenantId = "tenant-c")
        assertFailsWith<IllegalArgumentException> {
            Db.createUser("legacyop", "123456", "默认租户重复", ROLE_OPERATOR, emptyList())
        }
        assertEquals("新租户同名", Db.findUserByUsername("legacyop", "tenant-c")?.displayName)
        assertEquals("老用户", Db.findUserByUsername("legacyop")?.displayName)
    }
}
