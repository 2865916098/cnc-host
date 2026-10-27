package com.cnc.device.simulator

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自动化流程模拟器测试。
 *
 * 覆盖正常流程、夹紧失败、送料失败等场景。
 * 验证流程使用反馈信号判断动作结果，不依靠固定延时。
 */
class AutomationFlowSimulatorTest {

    private lateinit var device: SimulatedDeviceController
    private lateinit var flow: AutomationFlowSimulator

    @Before
    fun setUp() {
        device = SimulatedDeviceController()
        flow = AutomationFlowSimulator(device)
    }

    @After
    fun tearDown() = runBlocking { device.disconnect() }

    @Test
    fun `normal flow completes successfully`() = runBlocking {
        device.connect()
        val result = flow.execute("T-001")
        assertEquals(FlowResult.SUCCESS, result)
        assertEquals(AutomationStep.COMPLETED, flow.currentStep)
        assertNull(flow.lastError)
    }

    @Test
    fun `clamp failure aborts flow at CONFIRM_CLAMP`() = runBlocking {
        device.connect()
        device.injectFault(FaultType.CLAMP_FAILURE)

        val result = flow.execute("T-002")
        assertEquals(FlowResult.FAILED, result)
        assertEquals(AutomationStep.CONFIRM_CLAMP, flow.currentStep)
        assertTrue(flow.lastError?.contains("夹紧") == true)
    }

    @Test
    fun `feed failure aborts flow at CONFIRM_LOAD`() = runBlocking {
        device.connect()
        device.injectFault(FaultType.FEED_FAILURE)

        val result = flow.execute("T-003")
        assertEquals(FlowResult.FAILED, result)
        assertEquals(AutomationStep.CONFIRM_LOAD, flow.currentStep)
        assertTrue(flow.lastError?.contains("送料") == true)
    }

    @Test
    fun `flow resets to STANDBY after reset`() = runBlocking {
        device.connect()
        flow.execute("T-004")
        flow.reset()
        assertEquals(AutomationStep.STANDBY, flow.currentStep)
        assertNull(flow.result)
    }

    @Test
    fun `axis overtravel aborts flow during rough turn`() = runBlocking {
        device.connect()
        // 使用超出行程的配置，让粗车定位失败
        val smallDevice = SimulatedDeviceController(
            config = DeviceSimulatorConfig(xTravelMm = 10.0, zTravelMm = 10.0)
        )
        val smallFlow = AutomationFlowSimulator(smallDevice)
        smallDevice.connect()
        val result = smallFlow.execute("T-005")
        assertEquals(FlowResult.FAILED, result)
        smallDevice.disconnect()
    }

    @Test
    fun `flow logs are recorded`() = runBlocking {
        device.connect()
        flow.execute("T-006")
        val logs = device.logger.logs.value
        // 流程步骤应被记录
        assertTrue(logs.any { it.message.contains("步骤") })
        assertTrue(logs.any { it.taskId == "T-006" })
    }
}
