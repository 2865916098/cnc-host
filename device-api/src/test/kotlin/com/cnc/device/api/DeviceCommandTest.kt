package com.cnc.device.api

import com.cnc.model.cam.MotionType
import com.cnc.model.cam.ProcessType
import com.cnc.model.cam.ToolpathSegment
import com.cnc.model.drawing.Point2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * R1 回归测试：圆弧段必须下发 ARC_FEED 命令且参数完整（禁止降级为弦）。
 */
class DeviceCommandTest {

    private fun seg(
        motionType: MotionType,
        end: Point2D = Point2D(30.0, -20.0),
        center: Point2D? = null,
        radius: Double? = null,
        clockwise: Boolean? = null,
        feed: Double = 0.0
    ) = ToolpathSegment(
        segmentId = "seg-1",
        motionType = motionType,
        start = Point2D(10.0, -20.0),
        end = end,
        center = center,
        radius = radius,
        clockwise = clockwise,
        feedMmPerMin = feed,
        processType = ProcessType.FINISH_TURNING,
        toolNumber = 1
    )

    @Test
    fun `半圆弧段下发 ARC_FEED 且参数完整`() {
        // 半圆：起点 (10,-20) → 终点 (30,-20)，圆心 (20,-20)，半径 10，顺时针
        val arc = seg(
            MotionType.ARC_FEED,
            center = Point2D(20.0, -20.0),
            radius = 10.0,
            clockwise = true,
            feed = 250.0
        )
        val cmd = DeviceCommand.fromSegment("cmd-1", "task-1", arc)

        assertEquals(CommandType.ARC_FEED, cmd.type)
        assertEquals(30.0, cmd.params["x"] as Double, 1e-9)
        assertEquals(-20.0, cmd.params["z"] as Double, 1e-9)
        assertEquals(20.0, cmd.params["cx"] as Double, 1e-9)
        assertEquals(-20.0, cmd.params["cz"] as Double, 1e-9)
        assertEquals(10.0, cmd.params["radius"] as Double, 1e-9)
        assertEquals(true, cmd.params["clockwise"])
        assertEquals(250.0, cmd.params["feed"] as Double, 1e-9)
    }

    @Test
    fun `圆弧段进给缺省时回退默认进给`() {
        val arc = seg(MotionType.ARC_FEED, center = Point2D(20.0, -20.0), radius = 10.0, clockwise = false)
        val cmd = DeviceCommand.fromSegment("cmd-2", null, arc)
        assertEquals(CommandType.ARC_FEED, cmd.type)
        assertEquals(100.0, cmd.params["feed"] as Double, 1e-9)
        assertEquals(false, cmd.params["clockwise"])
    }

    @Test
    fun `圆弧段缺圆心数据时拒绝下发`() {
        val bad = seg(MotionType.ARC_FEED, center = null)
        assertThrows(IllegalArgumentException::class.java) {
            DeviceCommand.fromSegment("cmd-3", null, bad)
        }
    }

    @Test
    fun `直线与快速段映射保持不变`() {
        val linear = DeviceCommand.fromSegment("cmd-4", null, seg(MotionType.LINEAR_FEED, feed = 120.0))
        assertEquals(CommandType.LINEAR_FEED, linear.type)
        assertEquals(120.0, linear.params["feed"] as Double, 1e-9)

        val rapid = DeviceCommand.fromSegment("cmd-5", null, seg(MotionType.RAPID))
        assertEquals(CommandType.RAPID_MOVE, rapid.type)
        assertEquals(30.0, rapid.params["x"] as Double, 1e-9)
        assertEquals(-20.0, rapid.params["z"] as Double, 1e-9)
    }
}
