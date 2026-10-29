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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 设备激活（注册）与单位终端统计测试。
 *
 * 每个用例使用独立临时 SQLite 库，避免状态串扰。
 */
class ActivationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun freshDb(): String =
        Files.createTempFile("cnc-activation-", ".db").toFile().absolutePath

    private fun activate(
        hardwareId: String,
        deviceId: String = "",
        unitName: String = "示范单位",
        province: String = "广东省",
        city: String = "深圳市",
        contactName: String = "张三",
        phone: String = "13800000000",
        serverAddress: String = "http://192.168.1.10:8080",
        appVersion: String = "1.2.0"
    ) = RegisterRequest(
        unitName = unitName,
        contactName = contactName,
        phone = phone,
        province = province,
        city = city,
        serverAddress = serverAddress,
        deviceId = deviceId,
        hardwareId = hardwareId,
        appVersion = appVersion
    )

    private fun RegisterRequest.toJson(): String =
        json.encodeToString(RegisterRequest.serializer(), this)

    @Test
    fun `first activation returns token and persists province city`() {
        Db.init(freshDb())
        val info = Db.registerDevice(activate("hw-001", deviceId = "dev-a"))
        assertTrue(info.token.isNotBlank())
        assertEquals("dev-a", info.id)
        assertEquals("广东省", info.province)
        assertEquals("深圳市", info.city)

        val stored = Db.getDevice("dev-a")
        assertNotNull(stored)
        assertEquals("广东省", stored.province)
        assertEquals("深圳市", stored.city)
        assertEquals(info.token, stored.token)
    }

    @Test
    fun `repeat activation keeps id and token and updates unit info`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }

        val first = client.post("/api/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(activate("hw-repeat", deviceId = "dev-old").toJson())
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val firstInfo = json.decodeFromString<DeviceInfo>(first.bodyAsText())
        assertTrue(firstInfo.token.isNotBlank())

        // 客户端「清除数据」后再次激活：同一 hardwareId，不同本地 deviceId / 单位信息
        val second = client.post("/api/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(
                activate(
                    "hw-repeat",
                    deviceId = "dev-new",
                    unitName = "更新后单位",
                    province = "江苏省",
                    city = "苏州市",
                    contactName = "李四",
                    phone = "13900000000"
                ).toJson()
            )
        }
        // 必须返回 200，而不是 409
        assertEquals(HttpStatusCode.OK, second.status)
        val secondInfo = json.decodeFromString<DeviceInfo>(second.bodyAsText())
        assertEquals(firstInfo.id, secondInfo.id)
        assertEquals(firstInfo.token, secondInfo.token)
        assertEquals("更新后单位", secondInfo.unitName)
        assertEquals("江苏省", secondInfo.province)
        assertEquals("苏州市", secondInfo.city)

        val stored = Db.getDevice(firstInfo.id)
        assertNotNull(stored)
        assertEquals("更新后单位", stored.unitName)
        assertEquals("李四", stored.contactName)
        assertEquals("13900000000", stored.phone)
        assertEquals("江苏省", stored.province)
        assertEquals("苏州市", stored.city)
        assertEquals(firstInfo.token, stored.token)
    }

    @Test
    fun `device list returns province and city`() {
        Db.init(freshDb())
        Db.registerDevice(activate("hw-list", deviceId = "dev-list"))
        val item = Db.listDevices().single { it.id == "dev-list" }
        assertEquals("广东省", item.province)
        assertEquals("深圳市", item.city)
    }

    @Test
    fun `org summary groups devices and counts online`() {
        Db.init(freshDb())
        Db.registerDevice(activate("hw-1", deviceId = "dev-1"))
        Db.registerDevice(activate("hw-2", deviceId = "dev-2"))
        Db.registerDevice(activate("hw-3", deviceId = "dev-3"))

        val now = System.currentTimeMillis()
        Db.withConn { c ->
            fun touch(id: String, lastSeen: Long) {
                c.prepareStatement("UPDATE devices SET last_seen = ? WHERE id = ?").use { ps ->
                    ps.setLong(1, lastSeen)
                    ps.setString(2, id)
                    ps.executeUpdate()
                }
            }
            touch("dev-1", now - 5_000)
            touch("dev-2", now - 5_000)
            touch("dev-3", now - 60_000)
        }

        val summary = Db.orgSummary()
        val item = summary.single { it.orgName == "示范单位" }
        assertEquals(3, item.deviceCount)
        assertEquals(2, item.onlineCount)
        assertEquals(now - 5_000, item.lastSeen)
        assertEquals("广东省", item.province)
        assertEquals("深圳市", item.city)
    }

    @Test
    fun `same org name in different provinces counts as two groups`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        Db.registerDevice(activate("hw-gd", deviceId = "dev-gd", province = "广东省", city = "深圳市"))
        Db.registerDevice(activate("hw-js", deviceId = "dev-js", province = "江苏省", city = "苏州市"))

        val response = client.get("/api/v1/devices/org-summary") {
            header(HttpHeaders.Authorization, "Bearer root-token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val items = json.decodeFromString<List<OrgSummaryItem>>(response.bodyAsText())
        assertEquals(2, items.size)
        assertTrue(items.all { it.orgName == "示范单位" && it.deviceCount == 1 })
        assertEquals(setOf("广东省", "江苏省"), items.map { it.province }.toSet())
    }
}
