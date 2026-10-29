package com.cnc.host.data

import com.cnc.host.sync.CommConfigDto
import com.cnc.host.sync.DeviceConfigDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备仓库驱动选择测试。
 *
 * 铁律：useSimulator=true、无配置、未知驱动、创建失败 → 一律回退内置模拟器，
 * 保证无真实控制卡时全功能可用，绝不因后台配置错误导致无设备可用。
 */
class DeviceRepositoryDriverTest {

    @Test
    fun `默认使用模拟器`() {
        val repo = DeviceRepository()
        assertTrue(repo.isSimulated)
        assertTrue(repo.controller.isSimulated)
    }

    @Test
    fun `useSimulator 为 true 时保持模拟器`() = runBlocking {
        val repo = DeviceRepository()
        repo.applyServerConfig(
            DeviceConfigDto(useSimulator = true, comm = CommConfigDto(driverType = "MODBUS_TCP"))
        )
        assertTrue(repo.isSimulated)
    }

    @Test
    fun `无配置时保持模拟器`() = runBlocking {
        val repo = DeviceRepository()
        repo.applyServerConfig(null)
        assertTrue(repo.isSimulated)
    }

    @Test
    fun `MODBUS_TCP 配置切换到真实驱动`() = runBlocking {
        val repo = DeviceRepository()
        repo.applyServerConfig(
            DeviceConfigDto(
                useSimulator = false,
                comm = CommConfigDto(
                    driverType = "MODBUS_TCP",
                    ipAddress = "10.0.0.8",
                    port = 5020,
                    deviceId = 3,
                    params = mapOf("pos_scale" to "1000")
                )
            )
        )
        assertFalse(repo.isSimulated)
        assertFalse(repo.controller.isSimulated)
        // 未连接状态下安全
        assertFalse(repo.controller.isConnected())

        // 切回模拟器
        repo.applyServerConfig(DeviceConfigDto(useSimulator = true))
        assertTrue(repo.isSimulated)
    }

    @Test
    fun `未知驱动类型安全回退模拟器`() = runBlocking {
        val repo = DeviceRepository()
        repo.applyServerConfig(
            DeviceConfigDto(useSimulator = false, comm = CommConfigDto(driverType = "NO_SUCH_DRIVER"))
        )
        assertTrue(repo.isSimulated)
    }
}
