package com.cnc.device.modbus

import com.cnc.device.api.CommandStatus
import com.cnc.device.api.CommandType
import com.cnc.device.api.DeviceCommand
import com.cnc.model.common.ConnectionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通用 Modbus TCP 驱动控制器测试（假传输层，不接触真实硬件）。 */
class ModbusTcpDeviceControllerTest {

    private fun newController(
        fake: FakeModbusTransport,
        map: ModbusRegisterMap = ModbusRegisterMap()
    ): ModbusTcpDeviceController = ModbusTcpDeviceController(fake, unitId = 1, map = map)

    @Test
    fun `未连接时拒绝命令且位置为空`() = runBlocking {
        val controller = newController(FakeModbusTransport())
        assertFalse(controller.isConnected())
        assertEquals(ConnectionMode.OFFLINE, controller.connectionMode)
        assertEquals(Pair(null, null), controller.readAxisPositions())
        val result = controller.sendMotionCommand(
            DeviceCommand.linearFeed("c1", null, com.cnc.model.drawing.Point2D(10.0, 20.0), 500.0)
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `连接失败返回 failure`() = runBlocking {
        val fake = FakeModbusTransport().apply { failOpen = true }
        val controller = newController(fake)
        val result = controller.connect()
        assertTrue(result.isFailure)
        assertFalse(controller.isConnected())
    }

    @Test
    fun `连接后读取位置并按缩放换算`() = runBlocking {
        val fake = FakeModbusTransport()
        // pos_scale=100、32 位：X=12345 → 123.45mm；Z=-5000 → -50.0mm
        fake.holding[0] = 0x0000; fake.holding[1] = 12345
        fake.holding[2] = 0xFFFF; fake.holding[3] = 0xEC78 // -5000
        fake.holding[4] = 1500 // 主轴转速
        val controller = newController(fake)

        assertTrue(controller.connect().isSuccess)
        assertEquals(ConnectionMode.REAL_CONNECTED, controller.connectionMode)

        val status = controller.readStatus()
        assertFalse(status.isSimulated)
        assertEquals(123.45, status.xActualPositionMm!!, 1e-9)
        assertEquals(-50.0, status.zActualPositionMm!!, 1e-9)
        assertEquals(1500, status.spindleRpm)
        assertTrue(status.spindleRunning)
    }

    @Test
    fun `直线进给按映射写参数寄存器并置启动位`() = runBlocking {
        val fake = FakeModbusTransport()
        fake.holding[6] = ModbusRegisterMap.StatusBits.DONE // 完成位预置
        val controller = newController(fake)
        controller.connect()

        val result = controller.sendMotionCommand(
            DeviceCommand.linearFeed("c2", "t1", com.cnc.model.drawing.Point2D(10.0, -20.0), 600.0)
        )

        assertTrue(result.isSuccess)
        assertEquals(CommandStatus.COMPLETED, result.getOrThrow().status)
        // cmd_x=10.0mm → 1000；cmd_z=-20mm → -2000（32 位高字在前）
        assertEquals(0, fake.holding[10]); assertEquals(1000, fake.holding[11])
        assertEquals(0xFFFF, fake.holding[12]); assertEquals(0xF830, fake.holding[13]) // -2000
        // cmd_feed=600mm/min → 60000（0.01 单位）
        assertEquals(0, fake.holding[14]); assertEquals(60000, fake.holding[15])
        // cmd_type=LINEAR(2)；控制字=START(1)
        assertEquals(ModbusRegisterMap.CmdTypes.LINEAR, fake.holding[16])
        assertEquals(ModbusRegisterMap.ControlBits.START, fake.holding[8])
    }

    @Test
    fun `圆弧命令写圆心寄存器且缺圆心拒绝`() = runBlocking {
        val fake = FakeModbusTransport()
        fake.holding[6] = ModbusRegisterMap.StatusBits.DONE
        val controller = newController(fake)
        controller.connect()

        val ok = controller.sendMotionCommand(
            DeviceCommand.arcFeed(
                "c3", "t1",
                com.cnc.model.drawing.Point2D(5.0, 5.0),
                com.cnc.model.drawing.Point2D(2.5, 2.5),
                radius = 3.5, clockwise = false, feedMmPerMin = 300.0
            )
        )
        assertTrue(ok.isSuccess)
        // 圆心 2.5mm → 250；逆时针 → ARC_CCW(4)
        assertEquals(0, fake.holding[18]); assertEquals(250, fake.holding[19])
        assertEquals(0, fake.holding[20]); assertEquals(250, fake.holding[21])
        assertEquals(ModbusRegisterMap.CmdTypes.ARC_CCW, fake.holding[16])

        val bad = controller.sendMotionCommand(
            DeviceCommand("c4", "t1", CommandType.ARC_FEED, params = mapOf("x" to 1.0, "z" to 2.0))
        )
        assertTrue(bad.isFailure)
        assertTrue(bad.exceptionOrNull()!!.message!!.contains("圆心"))
    }

    @Test
    fun `主轴命令写转速与启停`() = runBlocking {
        val fake = FakeModbusTransport()
        val controller = newController(fake)
        controller.connect()

        controller.sendMotionCommand(DeviceCommand.setSpindle("c5", null, rpm = 1200, on = true, clockwise = false))
        assertEquals(1200, fake.holding[22])
        assertEquals(2, fake.holding[23]) // 反转

        controller.sendMotionCommand(DeviceCommand.setSpindle("c6", null, rpm = 0, on = false))
        assertEquals(0, fake.holding[23])
    }

    @Test
    fun `完成位超时返回失败且禁止重发`() = runBlocking {
        val fake = FakeModbusTransport()
        fake.holding[6] = ModbusRegisterMap.StatusBits.BUSY // 一直忙
        val map = ModbusRegisterMap(commandTimeoutMs = 500)
        val controller = newController(fake, map)
        controller.connect()

        val result = controller.sendMotionCommand(
            DeviceCommand(
                commandId = "c7", type = CommandType.RAPID_MOVE,
                params = mapOf("x" to 1.0, "z" to 1.0), timeoutMs = 500
            )
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("禁止盲目重发"))
    }

    @Test
    fun `控制卡报警使命令失败并可读报警区`() = runBlocking {
        val fake = FakeModbusTransport()
        fake.holding[6] = ModbusRegisterMap.StatusBits.ALARM
        fake.holding[24] = 0x0017 // 报警代码 23
        val controller = newController(fake)
        controller.connect()

        val result = controller.sendMotionCommand(
            DeviceCommand.rapidMove("c8", null, com.cnc.model.drawing.Point2D(1.0, 1.0))
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("报警"))

        val alarms = controller.readAlarms()
        assertEquals(1, alarms.size)
        assertEquals("MB-0024", alarms[0].code)
    }

    @Test
    fun `读写 IO 点位`() = runBlocking {
        val fake = FakeModbusTransport()
        fake.discrete[3] = true
        val controller = newController(fake)
        controller.connect()

        val inputs = controller.readInputs()
        assertEquals(true, inputs[3])
        assertEquals(false, inputs[0])

        assertTrue(controller.setOutputs(mapOf(2 to true, 5 to true)).isSuccess)
        assertTrue(fake.coils[2] && fake.coils[5])
    }

    @Test
    fun `通讯中断后状态标注 REAL_FAULT`() = runBlocking {
        val fake = FakeModbusTransport()
        val controller = newController(fake)
        controller.connect()
        assertEquals(ConnectionMode.REAL_CONNECTED, controller.connectionMode)

        fake.forceExceptionCode = 0x04 // 持续性故障（后台轮询也失败，结果确定）
        val status = controller.readStatus()
        assertEquals(ConnectionMode.REAL_FAULT, status.mode)
        assertFalse(controller.isSimulated)
    }

    @Test
    fun `暂停与停止写控制字对应位`() = runBlocking {
        val fake = FakeModbusTransport()
        val controller = newController(fake)
        controller.connect()

        controller.requestPause()
        assertEquals(ModbusRegisterMap.ControlBits.PAUSE, fake.holding[8])
        controller.requestStop()
        assertEquals(ModbusRegisterMap.ControlBits.STOP, fake.holding[8])
    }

    @Test
    fun `查询命令结果映射状态字`() = runBlocking {
        val fake = FakeModbusTransport()
        val controller = newController(fake)
        controller.connect()

        fake.holding[6] = ModbusRegisterMap.StatusBits.BUSY
        assertEquals(CommandStatus.EXECUTING, controller.queryCommandResult("c9").getOrThrow())
        fake.holding[6] = ModbusRegisterMap.StatusBits.DONE
        assertEquals(CommandStatus.COMPLETED, controller.queryCommandResult("c9").getOrThrow())
        fake.holding[6] = 0
        assertEquals(CommandStatus.RESULT_UNKNOWN, controller.queryCommandResult("c9").getOrThrow())
    }
}
