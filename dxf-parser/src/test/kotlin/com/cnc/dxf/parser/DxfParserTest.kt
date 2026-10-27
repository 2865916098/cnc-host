package com.cnc.dxf.parser

import com.cnc.model.common.UnitSystem
import com.cnc.model.drawing.ArcEntity
import com.cnc.model.drawing.CircleEntity
import com.cnc.model.drawing.LineEntity
import com.cnc.model.drawing.LwPolylineEntity
import com.cnc.model.drawing.Point2D
import com.cnc.model.drawing.UnsupportedEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DXF 解析器测试。
 *
 * 使用 sample.dxf 文件（包含 4 条 LINE + 1 个 CIRCLE + 1 个 ARC
 * + 1 个闭合 LWPOLYLINE + 1 个未支持的 TEXT + HEADER + TABLES）。
 */
class DxfParserTest {

    private val sampleText: String by lazy {
        File(DxfParserTest::class.java.classLoader.getResource("sample.dxf")!!.toURI()).readText()
    }

    // ==================== HEADER 解析 ====================

    @Test
    fun `parseHeader extracts version and units`() {
        val result = DxfParser.parseText(sampleText)
        assertEquals("AC1024", result.headerInfo.version)
        assertEquals(UnitSystem.IMPERIAL_INCH, result.headerInfo.unit) // $INSUNITS=4
    }

    @Test
    fun `parseHeader extracts extents`() {
        val result = DxfParser.parseText(sampleText)
        assertNotNull(result.headerInfo.extMin)
        assertEquals(Point2D(0.0, 0.0), result.headerInfo.extMin)
        assertNotNull(result.headerInfo.extMax)
        assertEquals(Point2D(200.0, 100.0), result.headerInfo.extMax)
    }

    // ==================== 实体数量和类型 ====================

    @Test
    fun `parses 4 LINE entities`() {
        val result = DxfParser.parseText(sampleText)
        val lines = result.entities.filterIsInstance<LineEntity>()
        assertEquals(4, lines.size)
    }

    @Test
    fun `parses 1 CIRCLE entity`() {
        val result = DxfParser.parseText(sampleText)
        val circles = result.entities.filterIsInstance<CircleEntity>()
        assertEquals(1, circles.size)
    }

    @Test
    fun `parses 1 ARC entity`() {
        val result = DxfParser.parseText(sampleText)
        val arcs = result.entities.filterIsInstance<ArcEntity>()
        assertEquals(1, arcs.size)
    }

    @Test
    fun `parses 1 closed LWPOLYLINE entity`() {
        val result = DxfParser.parseText(sampleText)
        val polys = result.entities.filterIsInstance<LwPolylineEntity>()
        assertEquals(1, polys.size)
        assertTrue(polys.first().closed)
        assertEquals(4, polys.first().vertices.size)
    }

    @Test
    fun `unsupported TEXT entity becomes UnsupportedEntity`() {
        val result = DxfParser.parseText(sampleText)
        val unsupported = result.entities.filterIsInstance<UnsupportedEntity>()
        assertEquals(1, unsupported.size)
        assertEquals("TEXT", unsupported.first().nativeType)
    }

    @Test
    fun `total entity count = 8`() {
        val result = DxfParser.parseText(sampleText)
        assertEquals(8, result.entities.size)
    }

    // ==================== 实体属性 ====================

    @Test
    fun `LINE has correct endpoints and layer`() {
        val result = DxfParser.parseText(sampleText)
        val first = result.entities.filterIsInstance<LineEntity>().first()
        assertEquals(Point2D(10.0, 20.0), first.start)
        assertEquals(Point2D(90.0, 20.0), first.end)
        assertEquals("OUTLINE", first.layer)
        assertEquals(80.0, first.length, 1e-6)
    }

    @Test
    fun `CIRCLE has center radius and HOLES layer`() {
        val result = DxfParser.parseText(sampleText)
        val circle = result.entities.filterIsInstance<CircleEntity>().first()
        assertEquals(Point2D(50.0, 50.0), circle.center)
        assertEquals(10.0, circle.radius, 1e-6)
        assertEquals("HOLES", circle.layer)
        // 简单面积验证
        assertEquals(Math.PI * 100.0, circle.area, 1e-6)
    }

    @Test
    fun `ARC has correct angles`() {
        val result = DxfParser.parseText(sampleText)
        val arc = result.entities.filterIsInstance<ArcEntity>().first()
        assertEquals(Point2D(150.0, 50.0), arc.center)
        assertEquals(15.0, arc.radius, 1e-6)
        assertEquals(0.0, arc.startAngleDeg, 1e-6)
        assertEquals(90.0, arc.endAngleDeg, 1e-6)
        assertEquals(1.0, arc.sweepSign, 1e-6)
    }

    @Test
    fun `LWPOLYLINE vertices are ordered and closed`() {
        val result = DxfParser.parseText(sampleText)
        val poly = result.entities.filterIsInstance<LwPolylineEntity>().first()
        assertEquals(Point2D(120.0, 30.0), poly.vertices[0].point)
        assertEquals(Point2D(180.0, 30.0), poly.vertices[1].point)
        assertEquals(Point2D(180.0, 70.0), poly.vertices[2].point)
        assertEquals(Point2D(120.0, 70.0), poly.vertices[3].point)
        // 4 个顶点，闭合矩形
        assertTrue(poly.closed)
        assertEquals("OUTLINE", poly.layer)
    }

    // ==================== 按 layer 分组 ====================

    @Test
    fun `byLayer groups correctly`() {
        val result = DxfParser.parseText(sampleText)
        val model = result.toGeometryModel("drawing-001")
        val outline = model.byLayer["OUTLINE"]
        val holes = model.byLayer["HOLES"]
        assertNotNull(outline)
        assertNotNull(holes)
        // OUTLINE：4 条 LINE + 1 个 LWPOLYLINE + (TEXT 在 ANNOTATION)
        assertEquals(5, outline!!.size)
        // HOLES：CIRCLE + ARC
        assertEquals(2, holes!!.size)
    }

    // ==================== BoundingBox ====================

    @Test
    fun `CircleEntity boundingBox is correct`() {
        val circle = CircleEntity(
            id = "c",
            center = Point2D(50.0, 50.0),
            radius = 10.0
        )
        val box = circle.boundingBox()
        assertEquals(40.0, box.minX, 1e-9)
        assertEquals(40.0, box.minY, 1e-9)
        assertEquals(60.0, box.maxX, 1e-9)
        assertEquals(60.0, box.maxY, 1e-9)
    }

    @Test
    fun `GeometryModel overallBounds computed correctly`() {
        val result = DxfParser.parseText(sampleText)
        val model = result.toGeometryModel("drawing-001")
        val bounds = model.overallBounds!!
        // 实体坐标范围约 [10, 180] x [20, 80]（加上 ARC 的 150+/-15 ≈ 135~165）
        assertTrue(bounds.minX <= 120.0) // ARC 150, LWPOLYLINE 120
        assertTrue(bounds.maxX >= 180.0)
        assertTrue(bounds.minY <= 30.0)
        assertTrue(bounds.maxY >= 80.0)
    }

    // ==================== 警告与不支持实体 ====================

    @Test
    fun `warnings mention unsupported TEXT`() {
        val result = DxfParser.parseText(sampleText)
        assertTrue(result.warnings.any { it.contains("未支持") && it.contains("TEXT") })
    }

    @Test
    fun `toGeometryModel records unsupported entity types in validation`() {
        val result = DxfParser.parseText(sampleText)
        val model = result.toGeometryModel("drawing-001")
        assertTrue(model.validation.unsupportedEntities.contains("TEXT"))
    }

    // ==================== 边界条件 ====================

    @Test
    fun `empty text produces no entities and no crash`() {
        val result = DxfParser.parseText("")
        assertTrue(result.entities.isEmpty())
    }

    @Test
    fun `no ENTITIES section yields empty entities`() {
        val text = "0\nSECTION\n2\nHEADER\n0\nENDSEC\n0\nEOF\n"
        val result = DxfParser.parseText(text)
        assertTrue(result.entities.isEmpty())
        assertNull(result.headerInfo.unit)
    }

    @Test(expected = DxfParseException::class)
    fun `non-existent file throws exception`() {
        DxfParser.parseFile(File("/nonexistent/file.dxf"))
    }

    // ==================== Angle normalization ====================

    @Test
    fun `normalizeAngleDeg maps 370 to 10`() {
        val normalized = com.cnc.model.drawing.normalizeAngleDeg(370.0)
        assertEquals(10.0, normalized, 1e-6)
    }

    @Test
    fun `normalizeAngleDeg maps -10 to 350`() {
        val normalized = com.cnc.model.drawing.normalizeAngleDeg(-10.0)
        assertEquals(350.0, normalized, 1e-6)
    }

    @Test
    fun `normalizeAngleDeg maps 0 to 0`() {
        assertEquals(0.0, com.cnc.model.drawing.normalizeAngleDeg(0.0), 1e-9)
    }
}
