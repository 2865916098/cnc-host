package com.cnc.model

import com.cnc.model.drawing.CircleEntity
import com.cnc.model.drawing.GeometryModel
import com.cnc.model.drawing.GeometryTransform
import com.cnc.model.drawing.GeometryValidation
import com.cnc.model.drawing.LineEntity
import com.cnc.model.drawing.LwPolylineEntity
import com.cnc.model.drawing.Point2D
import com.cnc.model.drawing.PolylineVertex
import com.cnc.model.drawing.Transform2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTransformTest {

    private fun assertPoint(expected: Point2D, actual: Point2D, tol: Double = 1e-9) {
        assertEquals("x 不一致", expected.x, actual.x, tol)
        assertEquals("y 不一致", expected.y, actual.y, tol)
    }

    @Test
    fun `恒等变换返回原模型`() {
        val model = GeometryModel(
            id = "g1", drawingId = "d1",
            entities = listOf(LineEntity("l1", Point2D(0.0, 0.0), Point2D(10.0, 0.0))),
            validation = GeometryValidation(true, false, emptyList(), emptyList(), null)
        )
        val result = GeometryTransform.apply(model, Transform2D.IDENTITY)
        assertSame(model, result)
    }

    @Test
    fun `缩放与平移按顺序生效`() {
        val t = Transform2D(scale = 2.0, offsetX = 5.0, offsetY = 1.0)
        assertPoint(Point2D(5.0, 1.0), t.applyTo(Point2D(0.0, 0.0)))
        assertPoint(Point2D(25.0, 1.0), t.applyTo(Point2D(10.0, 0.0)))
    }

    @Test
    fun `旋转 90 度`() {
        val t = Transform2D(rotationDeg = 90.0)
        assertPoint(Point2D(0.0, 1.0), t.applyTo(Point2D(1.0, 0.0)))
    }

    @Test
    fun `X 镜像翻转圆心且半径不变`() {
        val t = Transform2D(mirrorX = true)
        assertPoint(Point2D(-10.0, 5.0), t.applyTo(Point2D(10.0, 5.0)))
        assertEquals(3.0, t.applyToRadius(3.0), 1e-9)
    }

    @Test
    fun `Y 镜像翻转圆弧角度与扫向`() {
        val t = Transform2D(mirrorY = true)
        assertEquals(330.0, t.applyToAngle(30.0), 1e-9)
        assertEquals(-1.0, t.flipsSweep(1.0), 1e-9)
    }

    @Test
    fun `双轴镜像等价 180 度旋转且不反转扫向`() {
        val t = Transform2D(mirrorX = true, mirrorY = true)
        assertEquals(210.0, t.applyToAngle(30.0), 1e-9)
        assertEquals(1.0, t.flipsSweep(1.0), 1e-9)
        assertPoint(Point2D(-1.0, -2.0), t.applyTo(Point2D(1.0, 2.0)))
    }

    @Test
    fun `镜像翻转多段线凸度符号`() {
        val t = Transform2D(mirrorX = true)
        assertEquals(-0.5, t.flipsBulge(0.5), 1e-9)
        val pure = Transform2D(scale = 3.0)
        assertEquals(0.5, pure.flipsBulge(0.5), 1e-9)
    }

    @Test
    fun `模型变换后实体数量保持且包围盒正确`() {
        val model = GeometryModel(
            id = "g1", drawingId = "d1",
            entities = listOf(
                LineEntity("l1", Point2D(0.0, 0.0), Point2D(10.0, 0.0)),
                CircleEntity("c1", Point2D(10.0, 10.0), 2.0),
                LwPolylineEntity(
                    "p1",
                    listOf(PolylineVertex(Point2D(0.0, 0.0)), PolylineVertex(Point2D(4.0, 0.0)))
                )
            ),
            validation = GeometryValidation(true, false, emptyList(), emptyList(), null)
        )
        val t = Transform2D(scale = 2.0, offsetX = 1.0)
        val result = GeometryTransform.apply(model, t)

        assertEquals(3, result.entities.size)
        assertEquals(2, result.version)
        val bounds = result.overallBounds!!
        assertEquals(1.0, bounds.minX, 1e-9)
        assertEquals(25.0, bounds.maxX, 1e-9)
        assertTrue(bounds.width > 0)
    }

    @Test
    fun `缩放系数必须为正`() {
        var thrown = false
        try {
            Transform2D(scale = 0.0)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
