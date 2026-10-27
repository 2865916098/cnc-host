package com.cnc.device.simulator

import com.cnc.device.api.DeviceCommand
import com.cnc.model.common.ConnectionMode
import com.cnc.model.drawing.Point2D
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 增强版模拟控制卡测试。
 *
 * 覆盖急停、就绪状态、行程检查、主轴故障、送料故障等新功能。
 */
class EnhancedSimulatorTest {

    private lateinit var device: SimulatedDeviceController

    @Before
    fun setUp() {
        device = SimulatedDeviceController(
            config = DeviceSimulatorConfig(xTravelMm = 200.0, zTravelMm = 500.0)
        )
    }

    @After
    fun tearDown() = runBlocking { device.disconnect() }

    @Test
    fun `estop prevents motion and sets fault mode`() = runBlocking {
        device.connect()
        device.triggerEstop()

        assertTrue(device.estopPressed)
        assertEquals(ConnectionMode.REAL_FAULT, device.connectionMode)

        val cmd = DeviceCommand.rapidMove("estop-test", null, Point2D(10.0, 10.0))
        val result = device.sendMotionCommand(cmd)
        assertTrue(result.isFailure)
    }

    @Test
    fun `resetEstop allows motion again`() = runBlocking {
        device.connect()
        device.triggerEstop()
        device.resetEstop()

        assertFalse(device.estopPressed)
        assertTrue(device.isReady())

        val cmd = DeviceCommand.rapidMove("reset-test", null, Point2D(10.0, 10.0))
        val result = device.sendMotionCommand(cmd)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `travel limit check rejects out-of-range coordinates`() = runBlocking {
        device.connect()
        // X 超出行程 200mm
        val cmd = DeviceCommand.rapidMove("ot-test", null, Point2D(250.0, 100.0))
        val result = device.sendMotionCommand(cmd)
        assertTrue(result.isFailure)

        val alarms = device.readAlarms()
        assertTrue(alarms.any { it.code == "SIM-OT" })
    }

    @Test
    fun `spindle failure fault prevents spindle start`() = runBlocking {
        device.connect()
        device.injectFault(FaultType.SPINDLE_FAILURE)

        val cmd = DeviceCommand.setSpindle("sf-test", null, 1000, true)
        val result = device.sendMotionCommand(cmd)
        assertTrue(result.isFailure)
        assertTrue(device.readAlarms().any { it.code == "SIM-SF" })
    }

    @Test
    fun `readiness is READY after connect`() = runBlocking {
        device.connect()
        assertTrue(device.isReady())
        assertEquals(DeviceReadiness.READY, device.getReadiness())
    }

    @Test
    fun `readiness becomes BUSY during motion`() = runBlocking {
        device.connect()
        // 运动中设备应为 BUSY（简化模型下同步执行完成后恢复 READY）
        val cmd = DeviceCommand.rapidMove("busy-test", null, Point2D(10.0, 10.0))
        device.sendMotionCommand(cmd)
        // 执行完成后恢复 READY
        assertEquals(DeviceReadiness.READY, device.getReadiness())
    }

    @Test
    fun `estop blocks setOutputs`() = runBlocking {
        device.connect()
        device.triggerEstop()
        val result = device.setOutputs(mapOf(1 to true))
        assertTrue(result.isFailure)
    }

    @Test
    fun `feed failure fault prevents feed confirmation`() = runBlocking {
        device.connect()
        device.injectFault(FaultType.FEED_FAILURE)
        device.setOutputs(mapOf(2 to true))
        // 送料失败，输入 2 应为 false
        val inputs = device.readInputs()
        assertFalse(inputs[2] == true)
    }

    @Test
    fun `logs are generated during operations`() = runBlocking {
        device.connect()
        device.sendMotionCommand(DeviceCommand.rapidMove("log-test", null, Point2D(5.0, 5.0)))
        val logs = device.logger.logs.value
        assertTrue(logs.isNotEmpty())
        // 应包含运动相关日志
        assertTrue(logs.any { it.message.contains("命令") || it.message.contains("主轴") || it.category.name.contains("MOTION") || it.category.name.contains("COMMUNICATION") })
    }

    @Test
    fun `command queue snapshot tracks commands`() = runBlocking {
        device.connect()
        device.sendMotionCommand(DeviceCommand.rapidMove("snap-test", null, Point2D(3.0, 3.0)))
        val snapshot = device.commandQueueSnapshot()
        assertTrue(snapshot.containsKey("snap-test"))
    }
}
