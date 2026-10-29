package com.cnc.device.api.driver

import com.cnc.device.api.CommandStatus
import com.cnc.device.api.DeviceCommand
import com.cnc.device.api.DeviceController
import com.cnc.model.common.ConnectionMode
import com.cnc.model.device.AlarmRecord
import com.cnc.model.device.DeviceSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 驱动注册表与连接参数模型测试。 */
class DeviceDriverRegistryTest {

    /** 测试用假工厂。 */
    private class FakeFactory(
        id: String,
        private val created: DeviceController = FakeController()
    ) : DeviceDriverFactory {
        override val info = DeviceDriverInfo(id, "测试驱动 $id", "仅用于单元测试")
        override fun create(params: DriverConnectionParams): DeviceController = created
    }

    private class FakeController : DeviceController {
        override val connectionMode: ConnectionMode = ConnectionMode.OFFLINE
        override val isSimulated: Boolean = false
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override fun isConnected(): Boolean = false
        override fun statusFlow(): Flow<DeviceSnapshot> = flowOf()
        override suspend fun readStatus(): DeviceSnapshot =
            DeviceSnapshot(ConnectionMode.OFFLINE, com.cnc.model.common.ConnectionType.NONE, false)
        override suspend fun readAxisPositions(): Pair<Double?, Double?> = Pair(null, null)
        override suspend fun sendMotionCommand(command: DeviceCommand): Result<DeviceCommand> =
            Result.success(command.copy(status = CommandStatus.COMPLETED))
        override suspend fun requestPause(): Result<Unit> = Result.success(Unit)
        override suspend fun requestStop(): Result<Unit> = Result.success(Unit)
        override suspend fun readInputs(): Map<Int, Boolean> = emptyMap()
        override suspend fun setOutputs(outputs: Map<Int, Boolean>): Result<Unit> = Result.success(Unit)
        override suspend fun readAlarms(): List<AlarmRecord> = emptyList()
        override suspend fun queryCommandResult(commandId: String): Result<CommandStatus> =
            Result.success(CommandStatus.COMPLETED)
    }

    @Test
    fun `注册后可按类型查到工厂`() {
        val factory = FakeFactory("TEST_REG_A")
        DeviceDriverRegistry.register(factory)
        assertSame(factory, DeviceDriverRegistry.factoryFor("TEST_REG_A"))
        assertTrue(DeviceDriverRegistry.available().any { it.driverId == "TEST_REG_A" })
    }

    @Test
    fun `未注册的驱动类型返回 null`() {
        assertNull(DeviceDriverRegistry.factoryFor("NOT_EXISTS_DRIVER"))
    }

    @Test
    fun `同类型重复注册时后者覆盖前者`() {
        val first = FakeFactory("TEST_REG_B")
        val second = FakeFactory("TEST_REG_B")
        DeviceDriverRegistry.register(first)
        DeviceDriverRegistry.register(second)
        assertSame(second, DeviceDriverRegistry.factoryFor("TEST_REG_B"))
    }

    @Test
    fun `连接参数便捷解析`() {
        val p = DriverConnectionParams(
            driverType = DriverConnectionParams.DRIVER_MODBUS_TCP,
            params = mapOf(
                "host" to "192.168.1.100",
                "port" to "5020",
                "scale" to "12.5",
                "timeoutMs" to "3500",
                "pos32bit" to "true",
                "empty" to " ",
                "badInt" to "abc"
            )
        )
        assertEquals("192.168.1.100", p.get("host"))
        assertEquals(5020, p.getInt("port", 502))
        assertEquals(12.5, p.getDouble("scale", 1.0), 1e-9)
        assertEquals(3500L, p.getLong("timeoutMs", 2000L))
        assertTrue(p.getBoolean("pos32bit", false))
        // 空白串视为未配置
        assertNull(p.get("empty"))
        assertEquals(7, p.getInt("empty", 7))
        // 非法数字回退默认值
        assertEquals(9, p.getInt("badInt", 9))
        // 缺省键回退默认值
        assertEquals(502, p.getInt("missing", 502))
        assertEquals(false, p.getBoolean("missing", false))
    }
}
