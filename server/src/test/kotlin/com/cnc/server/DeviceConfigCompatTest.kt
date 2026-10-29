package com.cnc.server

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设备驱动配置兼容性测试。
 *
 * 驱动类型/扩展参数（driverType/params）是 P1 新增字段：
 * - 旧版本客户端/旧数据 JSON 不含新字段 → 必须能解码出默认值（服务端 ignoreUnknownKeys + 默认值兜底）
 * - 新字段编码后 → 必须能无损往返
 * 保证「换控制卡只改后台配置」的链路不因版本错位崩溃。
 */
class DeviceConfigCompatTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `旧版 JSON 缺省驱动字段可解码为默认值`() {
        val legacy = """
            {"protocol":"MODBUS_TCP","ipAddress":"192.168.1.100","port":502,
             "serialPort":"/dev/ttyUSB0","baudRate":115200,"deviceId":1,"timeoutMs":2000}
        """.trimIndent()
        val comm = json.decodeFromString(CommConfig.serializer(), legacy)
        assertEquals("SIMULATOR", comm.driverType)
        assertTrue(comm.params.isEmpty())
    }

    @Test
    fun `新版驱动字段编码后可无损往返`() {
        val comm = CommConfig(
            protocol = "MODBUS_TCP",
            ipAddress = "10.0.0.8",
            port = 5020,
            deviceId = 3,
            timeoutMs = 3000,
            driverType = "MODBUS_TCP",
            params = mapOf("pos_scale" to "1000", "status_word_addr" to "200")
        )
        val encoded = json.encodeToString(CommConfig.serializer(), comm)
        val decoded = json.decodeFromString(CommConfig.serializer(), encoded)
        assertEquals(comm, decoded)
    }

    @Test
    fun `设备配置整体兼容旧 JSON 且未知字段被忽略`() {
        // 模拟未来客户端多发了一个未知字段 futureField，服务端必须忽略而非报错
        val legacy = """
            {"name":"数控车床 #1","controlCard":"FANUC_0I","useSimulator":true,
             "maxTravelXmm":300.0,"maxTravelZmm":500.0,"maxRpm":3000,"maxFeedMmPerMin":5000.0,
             "comm":{"protocol":"MODBUS_TCP","ipAddress":"192.168.1.100","port":502,
                     "serialPort":"/dev/ttyUSB0","baudRate":115200,"deviceId":1,"timeoutMs":2000},
             "futureField":"ignored"}
        """.trimIndent()
        val device = json.decodeFromString(DeviceConfig.serializer(), legacy)
        assertEquals("SIMULATOR", device.comm.driverType)
        assertEquals("FANUC_0I", device.controlCard)
    }
}
