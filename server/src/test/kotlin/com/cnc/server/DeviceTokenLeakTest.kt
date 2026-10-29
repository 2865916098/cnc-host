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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R2 回归测试：GET /api/v1/devices/{id} 设备令牌泄露修复。
 *
 * 验收口径：
 * - 无 token 访问 → 401
 * - A 设备令牌查 B 设备 → 403
 * - 设备令牌查自身 → 200 且响应不含 token
 * - 管理口令查任意设备 → 200 且响应不含 token
 * - 注册 / 心跳响应仍携带 token（老客户端自动补录的兼容设计不变）
 */
class DeviceTokenLeakTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun freshDb(): String =
        Files.createTempFile("cnc-device-token-", ".db").toFile().absolutePath

    private fun activate(hardwareId: String, deviceId: String) = RegisterRequest(
        unitName = "示范单位", contactName = "张三", phone = "13800000000",
        province = "广东省", city = "深圳市", serverAddress = "http://192.168.1.10:8080",
        deviceId = deviceId, hardwareId = hardwareId, appVersion = "1.2.0"
    )

    private suspend fun register(
        client: io.ktor.client.HttpClient,
        hardwareId: String,
        deviceId: String
    ): DeviceInfo {
        val resp = client.post("/api/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegisterRequest.serializer(), activate(hardwareId, deviceId)))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return json.decodeFromString<DeviceInfo>(resp.bodyAsText())
    }

    @Test
    fun `device detail without token is rejected`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        register(client, "hw-a", "dev-a")

        val resp = client.get("/api/v1/devices/dev-a")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `device token cannot read other device`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        val a = register(client, "hw-a", "dev-a")
        register(client, "hw-b", "dev-b")

        val resp = client.get("/api/v1/devices/dev-b") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `device token reads itself and response hides token`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        val a = register(client, "hw-a", "dev-a")

        val resp = client.get("/api/v1/devices/dev-a") {
            header(HttpHeaders.Authorization, "Bearer ${a.token}")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val info = json.decodeFromString<DeviceInfo>(resp.bodyAsText())
        assertEquals("dev-a", info.id)
        assertTrue(info.token.isEmpty(), "详情接口不得回传设备令牌")
    }

    @Test
    fun `admin token reads any device and response hides token`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        val a = register(client, "hw-a", "dev-a")
        assertTrue(a.token.isNotBlank())

        val resp = client.get("/api/v1/devices/dev-a") {
            header(HttpHeaders.Authorization, "Bearer root-token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val info = json.decodeFromString<DeviceInfo>(resp.bodyAsText())
        assertTrue(info.token.isEmpty(), "管理端详情接口同样不得回传设备令牌")
    }

    @Test
    fun `register and heartbeat responses still carry token`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        val a = register(client, "hw-a", "dev-a")
        assertTrue(a.token.isNotBlank(), "注册响应必须携带令牌（客户端首次获取）")

        val hb = client.post("/api/v1/devices/dev-a/heartbeat") {
            contentType(ContentType.Application.Json)
            setBody("""{"appVersion":"1.2.0"}""")
        }
        assertEquals(HttpStatusCode.OK, hb.status)
        val hbInfo = json.decodeFromString<DeviceInfo>(hb.bodyAsText())
        assertEquals(a.token, hbInfo.token)
    }
}
