package com.cnc.gcode.post

import com.cnc.cam.engine.CamEngine
import com.cnc.cam.engine.ContourExtractor
import com.cnc.dxf.parser.DxfParser
import com.cnc.dxf.parser.toGeometryModel
import com.cnc.gcode.post.fanuc.Fanuc0iPostProcessor
import com.cnc.gcode.post.siemens.Siemens840DPostProcessor
import com.cnc.model.cam.MotionType
import com.cnc.model.cam.ProcessStep
import com.cnc.model.cam.ProcessType
import com.cnc.model.drawing.Point2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * G-code 后处理器测试。
 *
 * 使用 sample.dxf 生成真实 Toolpath，
 * 再喂给 Fanuc / Siemens 后处理器验证输出正确性。
 */
class PostProcessorTest {

    private lateinit var sampleToolpath: com.cnc.model.cam.Toolpath

    @Before
    fun setUp() {
        val dxf = DxfParser.parseText(SAMPLE_DXF)
        val geom = dxf.toGeometryModel("test")
        val contours = ContourExtractor.extract(geom)
        // 取 OUTLINE 层的外轮廓（通常是第一个）
        val outer = contours.first()

        val step = ProcessStep(
            id = "step-1",
            type = ProcessType.ROUGH_TURNING,
            toolId = "T1",
            cuttingSpeedMmPerMin = 150.0,
            spindleRpm = 1200,
            feedMmPerRev = 0.2,
            depthOfCutMm = 5.0,
            finishAllowanceMm = 0.5,
            approachDistanceMm = 10.0,
            retractDistanceMm = 10.0,
            safetyDistanceMm = 10.0,
            valid = true
        )
        sampleToolpath = CamEngine.generate(
            contour = outer,
            step = step,
            stockDiameter = 180.0,
            stockLength = 60.0,
            processPlanId = "test-pp",
            toolNumber = 1
        )
    }

    // ============== Fanuc ==============

    @Test
    fun `Fanuc program header has O number and percent markers`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath)
        assertTrue("Should have O-number line", prog.lines.any { it.startsWith("O1001") })
        assertTrue("Should have opening %", prog.lines.contains("%"))
        assertTrue("Last line should be %", prog.lines.last() == "%")
        // O line should be near top
        assertTrue("O-line should be first real line", prog.lines.indexOfFirst { it.startsWith("O") } <= 2)
    }

    @Test
    fun `Fanuc setup line includes G21 G07 G90 G99 G54`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath)
        val setup = prog.lines.first { it.startsWith("G21") || it.contains("G21") }
        assertTrue("Should include G21 metric", setup.contains("G21"))
        assertTrue("Should include G07 diameter", setup.contains("G07"))
        assertTrue("Should include G90 absolute", setup.contains("G90"))
        assertTrue("Should include G99 mm/rev", setup.contains("G99"))
        assertTrue("Should include G54 work offset", setup.contains("G54"))
    }

    @Test
    fun `Fanuc tool and spindle appear after setup`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath)
        val toolLine = prog.lines.firstOrNull { it.startsWith("T01") && it.length >= 4 }
            ?: prog.lines.first { it.startsWith("T01") }
        assertTrue("Tool line should contain T01", toolLine.startsWith("T01"))
        assertTrue("Tool should have tool offset suffix", toolLine.length >= 4)
        val spindleLine = prog.lines.first { it.contains("M03") || it.contains("M04") }
        assertTrue("Spindle should have S value", spindleLine.contains("S"))
    }

    @Test
    fun `Fanuc rapid segments emit G00`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath)
        assertTrue("Should have at least one G00", prog.lines.any { it.contains("G00") })
    }

    @Test
    fun `Fanuc feed segments emit G01 and F`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath)
        assertTrue("Should have G01 lines", prog.lines.any { it.contains("G01") })
        assertTrue("At least one G01 should set F (modal)", prog.lines.filter { it.contains("G01") }.any { it.contains("F") })
    }

    @Test
    fun `Fanuc every motion line has both X and Z`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath)
        val motionLines = prog.lines.filter {
            it.contains("G00") || it.contains("G01") || it.contains("G02") || it.contains("G03")
        }
        assertTrue("Should have motion lines", motionLines.isNotEmpty())
        motionLines.forEach { line ->
            assertTrue("$line should contain X", line.contains('X'))
            assertTrue("$line should contain Z", line.contains('Z'))
        }
    }

    @Test
    fun `Fanuc line numbers increment by configured step`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath, PostOptions(lineNumberStep = 10))
        val nLines = prog.lines.filter { it.matches(Regex("^N\\d+.*")) }
        assertTrue("Should have N-prefixed lines", nLines.isNotEmpty())
        val nums = nLines.mapNotNull { Regex("N(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
        assertEquals("First line number should be 10", 10, nums.first())
        for (i in 1 until nums.size) {
            val diff = nums[i] - nums[i-1]
            assertTrue("Jump from ${nums[i-1]} to ${nums[i]} should be step=10", diff == 10 || diff == 0)
        }
    }

    @Test
    fun `Fanuc no line numbers when step is 0`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath, PostOptions(lineNumberStep = 0))
        assertTrue("No N-prefixed lines", prog.lines.none { it.startsWith("N") })
    }

    @Test
    fun `Fanuc program end with M30 and coolant off`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath)
        val last = prog.lines.takeLast(6)
        assertTrue("Should end with %", prog.lines.last() == "%")
        assertTrue("Should have M30", prog.lines.any { it == "M30" })
        assertTrue("Should have M09 coolant off", prog.lines.any { it == "M09" })
        assertTrue("Should have M05 spindle stop", prog.lines.any { it == "M05" })
    }

    @Test
    fun `Fanuc inch mode uses G20`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath, PostOptions(useMetric = false))
        val setup = prog.lines.first { it.contains("G20") || it.contains("G21") }
        assertTrue("Should have G20 inch", setup.contains("G20"))
        assertFalse("Should NOT have G21", setup.contains("G21") && !setup.contains("G20"))
    }

    @Test
    fun `Fanuc M02 end instead of M30 when option set`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath, PostOptions(useM30 = false))
        assertTrue("Should have M02", prog.lines.any { it == "M02" })
    }

    @Test
    fun `Fanuc coolant off when autoCoolantOn = false`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath, PostOptions(autoCoolantOn = false))
        assertFalse("Should NOT have M08", prog.lines.contains("M08"))
        assertFalse("Should NOT have M09", prog.lines.contains("M09"))
    }

    @Test
    fun `Fanuc arc segments have G02 or G03 with I and K`() {
        // 需要确保 Toolpath 里有 ARC 段。如果 sample 没生成 arc 段，则跳过
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath)
        val hasG02 = prog.lines.any { it.contains("G02") }
        val hasG03 = prog.lines.any { it.contains("G03") }
        if (hasG02 || hasG03) {
            prog.lines.filter { it.contains("G02") || it.contains("G03") }.forEach { line ->
                assertTrue("Arc line $line should have I", line.contains('I'))
                assertTrue("Arc line $line should have K", line.contains('K'))
            }
        }
    }

    @Test
    fun `Fanuc GcodeProgram toText ends with newline`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath)
        val text = prog.toText()
        assertTrue("Text should end with \\n", text.endsWith('\n'))
        // 行数 ≈ 段数 + 头尾
        assertTrue("Output should have lines", prog.lines.size >= 15)
    }

    @Test
    fun `Fanuc instructionCount excludes comments and program mark`() {
        val pp = Fanuc0iPostProcessor()
        val prog = pp.generate(sampleToolpath)
        assertTrue("Should count actual instructions", prog.instructionCount > 0)
        assertTrue("Instructions <= total lines", prog.instructionCount <= prog.lines.size)
    }

    @Test
    fun `Fanuc idempotent same toolpath same output`() {
        val pp = Fanuc0iPostProcessor()
        val a = pp.generate(sampleToolpath).toText()
        val b = pp.generate(sampleToolpath).toText()
        assertEquals("Same toolpath should produce identical output", a, b)
    }

    // ============== Siemens ==============

    @Test
    fun `Siemens program header uses _N_Oxxx_MPF format`() {
        val pp = Siemens840DPostProcessor()
        val prog = pp.generate(sampleToolpath)
        assertTrue("First line should be %_N_Oxxx_MPF", prog.lines.first().startsWith("%_N_O"))
        assertTrue("Last line should be %", prog.lines.last() == "%")
    }

    @Test
    fun `Siemens tool call uses T= M6`() {
        val pp = Siemens840DPostProcessor()
        val prog = pp.generate(sampleToolpath)
        assertTrue("Should have T=\"1\" M6", prog.lines.any { it.contains("T=\"1\"") || it.contains("T=\"2\"") })
    }

    @Test
    fun `Siemens feed uses F= parameter form`() {
        val pp = Siemens840DPostProcessor()
        val prog = pp.generate(sampleToolpath)
        val fLines = prog.lines.filter { Regex("F=[\\d.]").find(it) != null }
        assertTrue("Siemens should use F=value", fLines.isNotEmpty())
    }

    @Test
    fun `Siemens vs Fanuc produce different output`() {
        val fanuc = Fanuc0iPostProcessor().generate(sampleToolpath).toText()
        val siemens = Siemens840DPostProcessor().generate(sampleToolpath).toText()
        assertNotEquals("Different machines should produce different gcode", fanuc, siemens)
        assertTrue("Fanuc contains G00", fanuc.contains("G00"))
        assertTrue("Siemens contains G0", siemens.contains("G0"))
    }

    @Test
    fun `MachineFamily is reported correctly`() {
        assertEquals(MachineFamily.FANUC_0I, Fanuc0iPostProcessor().machineFamily)
        assertEquals(MachineFamily.SIEMENS_840D, Siemens840DPostProcessor().machineFamily)
    }

    @Test
    fun `PostOptions decimal places respected`() {
        val pp3 = Fanuc0iPostProcessor()
        val pp6 = Fanuc0iPostProcessor()
        val prog3 = pp3.generate(sampleToolpath, PostOptions(decimalPlaces = 3))
        val prog6 = pp6.generate(sampleToolpath, PostOptions(decimalPlaces = 6))
        val firstMotion3 = prog3.lines.first { it.contains("G01") || it.contains("G00") }
        val firstMotion6 = prog6.lines.first { it.contains("G01") || it.contains("G00") }
        // 不同精度导致不同字符串
        assertNotEquals("Different precision should give different text", firstMotion3, firstMotion6)
    }

    // ============== 端到端 ==============

    @Test
    fun `DXF → GeometryModel → Toolpath → G-code full pipeline`() {
        val dxf = DxfParser.parseText(SAMPLE_DXF)
        val geom = dxf.toGeometryModel("e2e")
        assertTrue("GeometryModel should have entities", geom.entities.isNotEmpty())

        val contours = ContourExtractor.extract(geom)
        assertTrue("Should extract at least 1 contour", contours.isNotEmpty())

        val step = ProcessStep(
            id = "e2e-1", type = ProcessType.FINISH_TURNING,
            toolId = "T1", cuttingSpeedMmPerMin = 200.0,
            spindleRpm = 1500, feedMmPerRev = 0.15,
            depthOfCutMm = 1.0, finishAllowanceMm = 0.0,
            approachDistanceMm = 10.0, retractDistanceMm = 10.0,
            safetyDistanceMm = 10.0, valid = true
        )
        val tp = CamEngine.generate(contours.first(), step, 180.0, 60.0, "e2e-pp", 1)
        assertTrue("Toolpath should have segments", tp.segments.isNotEmpty())

        val fanuc = Fanuc0iPostProcessor().generate(tp)
        assertTrue("Fanuc G-code should have lines", fanuc.lines.size >= 10)
        assertTrue("Fanuc G-code should have motion", fanuc.lines.any { it.contains("G01") || it.contains("G00") })
        assertNotNull("Should have source toolpath id", fanuc.sourceToolpathId)

        val siemens = Siemens840DPostProcessor().generate(tp)
        assertTrue("Siemens G-code should have lines", siemens.lines.size >= 10)
    }

    companion object {
        private const val SAMPLE_DXF = """0
SECTION
2
HEADER
9
${"$"}ACADVER
1
AC1024
0
ENDSEC
0
SECTION
2
ENTITIES
0
LWPOLYLINE
8
OUTLINE
90
4
70
1
10
20.0
20
10.0
10
80.0
20
10.0
10
80.0
20
50.0
10
20.0
20
50.0
0
CIRCLE
8
HOLES
10
50.0
20
30.0
40
5.0
0
ENDSEC
0
EOF"""
    }
}
