package com.cnc.device.modbus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 寄存器映射配置解析测试。 */
class ModbusRegisterMapTest {

    @Test
    fun `空参数使用默认布局`() {
        val map = ModbusRegisterMap.from(emptyMap())
        assertEquals(ModbusRegisterMap(), map)
    }

    @Test
    fun `后台参数覆盖默认值`() {
        val map = ModbusRegisterMap.from(
            mapOf(
                "pos_x_addr" to "100",
                "pos_z_addr" to "102",
                "pos_scale" to "1000",
                "pos_32bit" to "false",
                "status_word_addr" to "200",
                "poll_interval_ms" to "500",
                "command_timeout_ms" to "30000"
            )
        )
        assertEquals(100, map.posXAddr)
        assertEquals(102, map.posZAddr)
        assertEquals(1000.0, map.posScale, 1e-9)
        assertEquals(false, map.pos32Bit)
        assertEquals(200, map.statusWordAddr)
        assertEquals(500L, map.pollIntervalMs)
        assertEquals(30000L, map.commandTimeoutMs)
    }

    @Test
    fun `非法或空白参数回退默认值`() {
        val map = ModbusRegisterMap.from(
            mapOf("pos_x_addr" to "abc", "pos_scale" to " ", "status_word_addr" to "")
        )
        assertEquals(0, map.posXAddr)
        assertEquals(100.0, map.posScale, 1e-9)
        assertEquals(6, map.statusWordAddr)
    }

    @Test
    fun `非法配置直接拒绝`() {
        try {
            ModbusRegisterMap.from(mapOf("pos_scale" to "0"))
            fail("应拒绝非正缩放")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pos_scale"))
        }
        try {
            ModbusRegisterMap.from(mapOf("poll_interval_ms" to "10"))
            fail("应拒绝过小轮询间隔")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("poll_interval_ms"))
        }
    }

    @Test
    fun `驱动工厂按参数创建控制器`() {
        val factory = ModbusTcpDriverFactory()
        assertEquals("MODBUS_TCP", factory.info.driverId)
        val controller = factory.create(
            com.cnc.device.api.driver.DriverConnectionParams(
                driverType = "MODBUS_TCP",
                params = mapOf("host" to "10.0.0.8", "port" to "5020", "unitId" to "3")
            )
        )
        assertFalse(controller.isSimulated)
        assertFalse(controller.isConnected())
    }
}
