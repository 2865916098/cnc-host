package com.cnc.server

import io.ktor.client.request.delete
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用户账号与权限体系测试。
 *
 * 每个用例使用独立临时 SQLite 库，避免状态串扰。
 */
class UserAuthTest {

    private fun freshDb(): String =
        Files.createTempFile("cnc-user-auth-", ".db").toFile().absolutePath

    @Test
    fun `password hash roundtrip and random salt`() {
        val hash = Db.hashPassword("secret123")
        assertTrue(hash.startsWith("pbkdf2\$120000\$"))
        assertTrue(Db.verifyPassword("secret123", hash))
        assertFalse(Db.verifyPassword("secret124", hash))
        // 同一明文两次哈希应因随机盐而不同
        assertNotEquals(hash, Db.hashPassword("secret123"))
    }

    @Test
    fun `seed creates single super admin`() {
        Db.init(freshDb())
        val admin = Db.findUserByUsername("admin")
        assertNotNull(admin)
        assertEquals(ROLE_SUPER_ADMIN, admin.role)
        assertTrue(admin.enabled)
        assertEquals(1, Db.countSuperAdmins())
        // 现有默认账号必须仍能登录
        assertTrue(Db.verifyAdminAccount("admin", "123456"))
        assertFalse(Db.verifyAdminAccount("admin", "wrong-password"))
        val session = Db.verifyUserAccount("admin", "123456")
        assertNotNull(session)
        assertEquals(ROLE_SUPER_ADMIN, session.role)
    }

    @Test
    fun `create user and reject duplicate username`() {
        Db.init(freshDb())
        val created = Db.createUser(
            username = "operator1",
            password = "123456",
            displayName = "操作员1",
            role = ROLE_OPERATOR,
            permissions = listOf("tasks", "logs")
        )
        assertEquals("operator1", created.username)
        assertEquals("操作员1", created.displayName)
        assertEquals(listOf("tasks", "logs"), created.permissions)
        assertNotNull(Db.findUserByUsername("operator1"))
        assertNotNull(Db.findUserById(created.id))
        assertEquals(2, Db.listUsers().size)

        assertFailsWith<IllegalArgumentException> {
            Db.createUser("operator1", "abcdef", "重复账号", ROLE_OPERATOR, emptyList())
        }
    }

    @Test
    fun `session resolves user and password change invalidates it`() {
        Db.init(freshDb())
        val created = Db.createUser("op2", "123456", "操作员2", ROLE_OPERATOR, listOf("tools"))
        val token = Db.createSession(created.username, created.id)
        val session = Db.getSessionUser(token)
        assertNotNull(session)
        assertEquals(created.id, session.id)
        assertEquals(ROLE_OPERATOR, session.role)
        assertEquals(listOf("tools"), session.permissions)

        assertTrue(Db.changeOwnPassword(created.id, "123456", "654321"))
        assertFalse(Db.changeOwnPassword(created.id, "wrong-old", "abcdef"))
        assertNotNull(Db.verifyUserAccount("op2", "654321"))
        assertNull(Db.verifyUserAccount("op2", "123456"))

        Db.deleteUserSessions(created.id, created.username)
        assertNull(Db.getSessionUser(token))
    }

    @Test
    fun `login returns role permissions and resolvable session`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        Db.createUser("manager", "123456", "管理员", ROLE_ADMIN, listOf("tools", "logs"))

        val response = client.post("/api/v1/admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"manager","password":"123456"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val result = Json { ignoreUnknownKeys = true }
            .decodeFromString<AdminLoginResult>(response.bodyAsText())
        assertTrue(result.ok)
        assertEquals(ROLE_ADMIN, result.role)
        assertEquals("管理员", result.displayName)
        assertEquals(listOf("tools", "logs"), result.permissions)
        val session = Db.getSessionUser(result.token)
        assertNotNull(session)
        assertEquals("manager", session.username)
    }

    @Test
    fun `route refuses deleting last super admin`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("test-admin-token") }
        val admin = Db.findUserByUsername("admin")
        assertNotNull(admin)

        val response = client.delete("/api/v1/users/${admin.id}") {
            header(HttpHeaders.Authorization, "Bearer test-admin-token")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNotNull(Db.findUserByUsername("admin"))
        assertEquals(1, Db.countSuperAdmins())
    }

    @Test
    fun `non super user denied unpermitted module but can read own profile`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        val user = Db.createUser("op3", "123456", "操作员3", ROLE_OPERATOR, listOf("tasks"))
        val token = Db.createSession(user.username, user.id)

        val denied = client.delete("/api/v1/tools/any") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)

        val me = client.get("/api/v1/users/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, me.status)

        // 禁用账号后，未过期的会话也必须被拒绝
        Db.updateUser(user.id, user.displayName, user.role, user.permissions, enabled = false)
        val disabled = client.get("/api/v1/users/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Unauthorized, disabled.status)
    }
}
