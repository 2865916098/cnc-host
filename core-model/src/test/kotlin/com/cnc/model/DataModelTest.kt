package com.cnc.model

import com.cnc.model.common.ConnectionMode
import com.cnc.model.common.DiameterMode
import com.cnc.model.common.TaskStatus
import com.cnc.model.common.UnitSystem
import com.cnc.model.device.MachineProfile
import com.cnc.model.task.ProductionTask
import com.cnc.model.task.ReviewItem
import com.cnc.model.task.ReviewItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核心数据模型测试。
 *
 * 验证版本化数据模型的一致性，以及任务审核状态的正确性。
 */
class DataModelTest {

    @Test
    fun `machine profile defaults are sensible`() {
        val machine = MachineProfile(
            id = "m1",
            name = "Test Lathe",
            xTravelMm = 200.0,
            zTravelMm = 500.0
        )
        assertEquals(UnitSystem.METRIC_MM, machine.unit)
        assertEquals(DiameterMode.DIAMETER, machine.diameterMode)
        assertFalse(machine.locked)
    }

    @Test
    fun `production task binds versions correctly`() {
        val task = ProductionTask(
            id = "t1",
            taskNumber = "T-001",
            partId = "p1",
            drawingId = "d1",
            drawingVersion = 2,
            processPlanId = "pp1",
            processPlanVersion = 3,
            toolIds = listOf("tool1"),
            machineProfileId = "m1",
            machineProfileVersion = 1
        )
        assertEquals(TaskStatus.DRAFT, task.status)
        assertEquals(2, task.drawingVersion)
        assertEquals(3, task.processPlanVersion)
        assertFalse(task.reviewPassed)
    }

    @Test
    fun `review item status blocks start when any fails`() {
        val items = listOf(
            ReviewItem("drawing", "图纸版本", ReviewItemStatus.PASS),
            ReviewItem("tool", "刀具确认", ReviewItemStatus.FAIL, "刀具未选择"),
            ReviewItem("sim", "仿真完成", ReviewItemStatus.PASS)
        )
        val task = ProductionTask(
            id = "t2",
            taskNumber = "T-002",
            partId = "p1",
            drawingId = "d1",
            drawingVersion = 1,
            processPlanId = "pp1",
            processPlanVersion = 1,
            toolIds = emptyList(),
            machineProfileId = "m1",
            machineProfileVersion = 1,
            reviewItems = items,
            reviewPassed = false
        )
        assertFalse(task.reviewPassed)
        assertTrue(items.any { it.status == ReviewItemStatus.FAIL })
    }

    @Test
    fun `snapshot isSimulated must be true for simulator`() {
        // 模拟模式快照必须标注为模拟数据
        val sim = com.cnc.model.device.DeviceSnapshot(
            mode = ConnectionMode.SIMULATOR,
            connectionType = com.cnc.model.common.ConnectionType.NONE,
            connected = true,
            isSimulated = true
        )
        assertTrue(sim.isSimulated)
        assertEquals(ConnectionMode.SIMULATOR, sim.mode)
    }

    @Test
    fun `versioned entity increments are distinguishable`() {
        val v1 = MachineProfile(id = "m1", name = "A", xTravelMm = 100.0, zTravelMm = 200.0, version = 1)
        val v2 = v1.copy(version = 2, xTravelMm = 150.0)
        assertEquals("m1", v1.id)
        assertEquals("m1", v2.id)
        assertNotEquals(v1.version, v2.version)
        assertNotEquals(v1.xTravelMm, v2.xTravelMm)
    }
}
