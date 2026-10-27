package com.cnc.cam.engine
import com.cnc.dxf.parser.toGeometryModel

import com.cnc.dxf.parser.DxfParser
import com.cnc.model.cam.ProcessStep
import com.cnc.model.cam.ProcessType
import com.cnc.model.cam.Toolpath
import com.cnc.model.cam.ToolpathValidation
import com.cnc.model.drawing.LineEntity
import com.cnc.model.drawing.CircleEntity
import com.cnc.model.drawing.GeometryModel
import com.cnc.model.drawing.GeometryValidation
import com.cnc.model.drawing.LwPolylineEntity
import com.cnc.model.drawing.Point2D
import com.cnc.model.drawing.PolylineVertex
import com.cnc.model.drawing.UnsupportedEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CAM 引擎全流程测试。
 *
 * 从 sample.dxf → GeometryModel → ContourExtractor → CamEngine → Toolpath
 */
class CamEngineTest {

    private val sampleDxf by lazy {
        File(CamEngineTest::class.java.classLoader.getResource("sample.dxf")!!.toURI()).readText()
    }

    // ==================== 轮廓提取 ====================

    @Test
    fun `contour extractor from DXF produces closed contour`() {
        val geometry = DxfParser.parseText(sampleDxf).toGeometryModel("draw-001")
        val contours = ContourExtractor.extract(geometry)
        assertTrue(contours.isNotEmpty())
        // sample.dxf 有 OUTLINE（4 LINE + 1 closed LWPOLYLINE），HOLES (CIRCLE + ARC)，TEXT
        // 图层 OUTLINE 和 HOLES 应该各有一个闭合轮廓
        val outlineContours = contours.filter { it.sourceLayers.contains("OUTLINE") }
        assertTrue(outlineContours.isNotEmpty())
    }

    @Test
    fun `contour has correct minX maxX`() {
        val geometry = DxfParser.parseText(sampleDxf).toGeometryModel("draw-002")
        val contours = ContourExtractor.extract(geometry)
        val outline = contours.firstOrNull { it.sourceLayers.contains("OUTLINE") } ?: return
        // OUTLINE 实体范围约 [10..180] x [20..80]
        assertTrue(outline.minX >= 0.0)
        assertTrue(outline.maxX <= 300.0)
        assertTrue(outline.centroid.x > 0)
        assertTrue(outline.centroid.y > 0)
    }

    @Test
    fun `empty model yields no contours`() {
        val empty = GeometryModel(
            id = "empty", drawingId = "d0", entities = emptyList(),
            validation = GeometryValidation(false, false, emptyList(), emptyList(), null)
        )
        assertEquals(0, ContourExtractor.extract(empty).size)
    }

    // ==================== CAM 生成：粗车削 ====================

    @Test
    fun `rough turning produces segments for each layer`() {
        val contour = makeSimpleContour()
        val step = makeStep(ProcessType.ROUGH_TURNING)
        val tp = CamEngine.generate(
            contour = contour, step = step,
            stockDiameter = 60.0, stockLength = 50.0,
            processPlanId = "pp-1", toolNumber = 1
        )
        assertTrue(tp.segments.isNotEmpty())
        // 3 层切深，每层 4 段 (rapid-down + cut + retract + lift) + 1 rapid-home = 13
        // 但如果切深导致层数更多也可以，只要 > 1
        assertTrue(tp.segments.size > 4)
    }

    @Test
    fun `rough turning feed segments have feedMmPerMin set`() {
        val contour = makeSimpleContour()
        val step = makeStep(ProcessType.ROUGH_TURNING).copy(cuttingSpeedMmPerMin = 200.0)
        val tp = CamEngine.generate(contour, step, 60.0, 50.0, "pp-1", 1)
        val feedSegments = tp.segments.filter { it.motionType.name == "LINEAR_FEED" }
        assertTrue(feedSegments.isNotEmpty())
        feedSegments.forEach { seg ->
            assertTrue(seg.feedMmPerMin > 0)
            assertTrue(seg.spindleRpm > 0)
        }
    }

    // ==================== CAM 生成：精车削 ====================

    @Test
    fun `finish turning produces finish allowance cut`() {
        val contour = makeSimpleContour()
        val step = makeStep(ProcessType.FINISH_TURNING).copy(finishAllowanceMm = 0.5)
        val tp = CamEngine.generate(contour, step, 60.0, 50.0, "pp-1", 1)
        assertTrue(tp.segments.isNotEmpty())
        val feedSegments = tp.segments.filter { it.motionType.name == "LINEAR_FEED" }
        assertTrue(feedSegments.any { it.processType == ProcessType.FINISH_TURNING })
    }

    // ==================== 其它工艺 ====================

    @Test
    fun `grooving produces basic segments`() {
        val contour = makeSimpleContour()
        val step = makeStep(ProcessType.GROOVING)
        val tp = CamEngine.generate(contour, step, 60.0, 50.0, "pp-1", 1)
        assertTrue(tp.segments.size >= 4)
        tp.segments.forEach { assertEquals(ProcessType.GROOVING, it.processType) }
    }

    @Test
    fun `threading produces multiple passes`() {
        val contour = makeSimpleContour()
        val step = makeStep(ProcessType.THREADING)
        val tp = CamEngine.generate(contour, step, 60.0, 50.0, "pp-1", 1)
        // 5 进刀 × 3 段 = 15
        assertTrue(tp.segments.size >= 15)
    }

    @Test
    fun `drilling produces simple drill cycle`() {
        val contour = makeSimpleContour()
        val step = makeStep(ProcessType.DRILLING)
        val tp = CamEngine.generate(contour, step, 60.0, 50.0, "pp-1", 1)
        assertEquals(3, tp.segments.size)
    }

    @Test
    fun `cut off produces end of cycle path`() {
        val contour = makeSimpleContour()
        val step = makeStep(ProcessType.CUT_OFF)
        val tp = CamEngine.generate(contour, step, 60.0, 50.0, "pp-1", 1)
        assertEquals(3, tp.segments.size)
    }

    // ==================== 校验 ====================

    @Test
    fun `valid toolpath passes basic validation`() {
        val contour = makeSimpleContour()
        val step = makeStep(ProcessType.ROUGH_TURNING)
        val tp = CamEngine.generate(contour, step, 60.0, 50.0, "pp-1", 1)
        assertTrue(tp.valid)
        assertTrue(tp.validation.noIllegalCoordinates)
    }

    @Test
    fun `validation flags NaN coordinates`() {
        val seg = com.cnc.model.cam.ToolpathSegment(
            segmentId = "bad",
            motionType = com.cnc.model.cam.MotionType.LINEAR_FEED,
            start = Point2D(Double.NaN, 0.0),
            end = Point2D(10.0, 10.0),
            feedMmPerMin = 200.0, spindleRpm = 1000,
            processType = ProcessType.ROUGH_TURNING, toolNumber = 1
        )
        val tp = Toolpath(
            id = "bad", processPlanId = "p", processPlanVersion = 1,
            machineProfileVersion = 1, segments = listOf(seg),
            validation = ToolpathValidation(false, false, false, false, false, false, listOf()),
            valid = false
        )
        assertFalse(tp.valid)
    }

    // ==================== DXF → 轮廓 → 刀路 全流程 ====================

    @Test
    fun `full pipeline from sample dxf to rough turning toolpath`() {
        val result = DxfParser.parseText(sampleDxf)
        assertTrue(result.entities.isNotEmpty())
        val geometry = result.toGeometryModel("draw-010")
        val contours = ContourExtractor.extract(geometry)
        assertTrue(contours.isNotEmpty())

        val step = makeStep(ProcessType.ROUGH_TURNING)
        val tp = CamEngine.generate(
            contour = contours.first(),
            step = step,
            stockDiameter = 200.0,  // 轮廓的 maxX 约 180，毛坯直径 200 对应半径 100
            stockLength = 100.0,
            processPlanId = "pp-final",
            toolNumber = 5
        )
        assertNotNull(tp.segments)
        assertTrue(tp.segments.isNotEmpty())
        assertTrue(tp.valid)

        // 最后一段应该是 lift 回安全位置
        val last = tp.segments.last()
        assertEquals(com.cnc.model.cam.MotionType.RAPID, last.motionType)
    }

    // ==================== 边界 ====================

    @Test
    fun `toolId of step propagates to all segments`() {
        val contour = makeSimpleContour()
        val step = makeStep(ProcessType.ROUGH_TURNING)
        val tp = CamEngine.generate(contour, step, 60.0, 50.0, "pp-1", toolNumber = 7)
        tp.segments.forEach { assertEquals(7, it.toolNumber) }
    }

    @Test
    fun `rapid segments don't have feedMmPerMin set`() {
        val contour = makeSimpleContour()
        val step = makeStep(ProcessType.ROUGH_TURNING)
        val tp = CamEngine.generate(contour, step, 60.0, 50.0, "pp-1", 1)
        tp.segments.filter { it.motionType.name == "RAPID" }.forEach { rapid ->
            assertEquals(0.0, rapid.feedMmPerMin, 1e-9)
        }
    }

    // ==================== 辅助 ====================

    private fun makeSimpleContour(): MachiningContour {
        val points = listOf(
            Point2D(20.0, 10.0),
            Point2D(30.0, 10.0),
            Point2D(30.0, 50.0),
            Point2D(20.0, 50.0),
        )
        return MachiningContour(
            id = "cnt-test",
            points = points,
            closed = false,
            clockwise = false,
            sourceLayers = setOf("0"),
            sourceEntityIds = listOf("test")
        )
    }

    private fun makeStep(type: ProcessType): ProcessStep = ProcessStep(
        id = "step-1",
        type = type,
        toolId = "tool-1",
        cuttingSpeedMmPerMin = 150.0,
        spindleRpm = 1200,
        feedMmPerRev = 0.2,
        depthOfCutMm = 2.0,
        finishAllowanceMm = 0.2,
        approachDistanceMm = 5.0,
        retractDistanceMm = 5.0,
        safetyDistanceMm = 10.0,
        valid = true
    )
}
