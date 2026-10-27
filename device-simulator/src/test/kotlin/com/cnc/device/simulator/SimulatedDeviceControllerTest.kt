package com.cnc.device.simulator

import com.cnc.device.api.DeviceCommand
import com.cnc.device.api.DeviceController
import com.cnc.model.common.ConnectionMode
import com.cnc.model.drawing.Point2D
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 模拟控制卡测试。
 *
 * 验证连接、运动、IO、报警和故障注入行为。
 * 模拟模式必须明确标注 isSimulated = true。
 */
class SimulatedDeviceControllerTest {

    private lateinit var device: SimulatedDeviceController

    @Before
    fun setUp() {
        device = SimulatedDeviceController()
    }

    @After
    fun tearDown() {
        runBlocking { device.disconnect() }
    }

    @Test
    fun `simulator reports isSimulated true`() {
        assertTrue(device.isSimulated)
    }

    @Test
    fun `connect succeeds and reports connected`() = runBlocking {
        val result = device.connect()
        assertTrue(result.isSuccess)
        assertTrue(device.isConnected())
        assertEquals(ConnectionMode.SIMULATOR, device.connectionMode)
    }

    @Test
    fun `readAxisPositions returns null when disconnected`() = runBlocking {
        val (x, z) = device.readAxisPositions()
        assertNull(x)
        assertNull(z)
    }

    @Test
    fun `rapid move updates axis positions`() = runBlocking {
        device.connect()
        val cmd = DeviceCommand.rapidMove("cmd1", null, Point2D(10.0, 20.0))
        val result = device.sendMotionCommand(cmd)
        assertTrue(result.isSuccess)

        val (x, z) = device.readAxisPositions()
        assertNotNull(x)
        assertNotNull(z)
        assertEquals(10.0, x!!, 0.001)
        assertEquals(20.0, z!!, 0.001)
    }

    @Test
    fun `set spindle updates rpm and running state`() = runBlocking {
        device.connect()
        val cmd = DeviceCommand.setSpindle("cmd2", null, 1500, true)
        device.sendMotionCommand(cmd)

        val status = device.readStatus()
        assertEquals(1500, status.spindleRpm)
        assertTrue(status.spindleRunning)
    }

    @Test
    fun `setOutputs drives clamp feedback input`() = runBlocking {
        device.connect()
        device.setOutputs(mapOf(1 to true))

        val inputs = device.readInputs()
        // 夹紧反馈输入点位 1 应为 true
        assertTrue(inputs[1] == true)
    }

    @Test
    fun `clamp failure fault prevents clamp feedback`() = runBlocking {
        device.connect()
        device.injectFault(FaultType.CLAMP_FAILURE)
        device.setOutputs(mapOf(1 to true))

        val inputs = device.readInputs()
        // 夹紧失败：反馈应为 false
        assertFalse(inputs[1] == true)
    }

    @Test
    fun `communication drop fault disconnects device`() = runBlocking {
        device.connect()
        assertTrue(device.isConnected())
        device.injectFault(FaultType.COMMUNICATION_DROP)
        assertFalse(device.isConnected())
        assertEquals(ConnectionMode.OFFLINE, device.connectionMode)
    }

    @Test
    fun `command timeout fault returns result unknown`() = runBlocking {
        device.connect()
        device.injectFault(FaultType.COMMAND_TIMEOUT)
        val cmd = DeviceCommand.rapidMove("cmd3", null, Point2D(5.0, 5.0))
        val result = device.sendMotionCommand(cmd)
        assertTrue(result.isFailure)
    }

    @Test
    fun `device alarm fault produces alarm record`() = runBlocking {
        device.connect()
        device.injectFault(FaultType.DEVICE_ALARM)
        val alarms = device.readAlarms()
        assertTrue(alarms.isNotEmpty())
    }

    @Test
    fun `requestStop stops spindle`() = runBlocking {
        device.connect()
        device.sendMotionCommand(DeviceCommand.setSpindle("cmd4", null, 2000, true))
        assertTrue(device.readStatus().spindleRunning)

        device.requestStop()
        assertFalse(device.readStatus().spindleRunning)
        assertEquals(0, device.readStatus().spindleRpm)
    }
}
