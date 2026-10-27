package com.cnc.model.drawing

import com.cnc.model.common.VersionedEntity

/** 几何实体类型。 */
enum class GeometryEntityType {
    LINE,
    ARC,
    CIRCLE,
    LWPOLYLINE,
    POLYLINE,
    SPLINE,
    POINT,
    TEXT,
    DIMENSION,
    UNSUPPORTED
}

/**
 * 二维点。
 * 必须保持简单：外部模块（device-api、cam、simulator）广泛依赖此结构。
 */
data class Point2D(val x: Double, val y: Double) {
    operator fun plus(other: Point2D) = Point2D(x + other.x, y + other.y)
    operator fun minus(other: Point2D) = Point2D(x - other.x, y - other.y)
    fun distanceTo(other: Point2D): Double =
        Math.hypot(x - other.x, y - other.y)
    fun rotate(angleDeg: Double): Point2D {
        val rad = Math.toRadians(angleDeg)
        val c = Math.cos(rad)
        val s = Math.sin(rad)
        return Point2D(x * c - y * s, x * s + y * c)
    }
}

/**
 * 边界框（AABB）。
 */
data class BoundingBox(
    val minX: Double, val minY: Double,
    val maxX: Double, val maxY: Double
) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val center get() = Point2D((minX + maxX) / 2, (minY + maxY) / 2)

    fun contains(p: Point2D): Boolean =
        p.x in minX..maxX && p.y in minY..maxY

    fun union(other: BoundingBox): BoundingBox = BoundingBox(
        minOf(minX, other.minX), minOf(minY, other.minY),
        maxOf(maxX, other.maxX), maxOf(maxY, other.maxY)
    )
}

/** 多段线顶点。 */
data class PolylineVertex(
    val point: Point2D,
    val bulge: Double = 0.0 // 圆弧段的凸度，0=直线
)

/**
 * 几何实体基类（sealed class 层次）。
 *
 * 各子类携带完整几何属性，避免父类里充斥大量 nullable 字段。
 */
sealed class GeometryEntity(
    open val id: String,
    open val layer: String = "0",
    open val selected: Boolean = true,
    open val sourceType: GeometryEntityType
) {
    abstract fun boundingBox(): BoundingBox
}

/** 直线段。 */
data class LineEntity(
    override val id: String,
    val start: Point2D,
    val end: Point2D,
    override val layer: String = "0",
    override val selected: Boolean = true
) : GeometryEntity(id, layer, selected, GeometryEntityType.LINE) {
    override fun boundingBox(): BoundingBox = BoundingBox(
        minOf(start.x, end.x), minOf(start.y, end.y),
        maxOf(start.x, end.x), maxOf(start.y, end.y)
    )
    val length: Double get() = start.distanceTo(end)
}

/** 圆。 */
data class CircleEntity(
    override val id: String,
    val center: Point2D,
    val radius: Double,
    override val layer: String = "0",
    override val selected: Boolean = true
) : GeometryEntity(id, layer, selected, GeometryEntityType.CIRCLE) {
    override fun boundingBox(): BoundingBox = BoundingBox(
        center.x - radius, center.y - radius,
        center.x + radius, center.y + radius
    )
    val circumference: Double get() = 2 * Math.PI * radius
    val area: Double get() = Math.PI * radius * radius
}

/**
 * 圆弧。
 *
 * startAngle/endAngle 为度数（DXF 约定），
 * 顺时针/逆时针方向由 sweepSign 区分：
 *   sweepSign > 0 : 逆时针（凸度 > 0 在 LWPOLYLINE 中也表示逆时针）
 *   sweepSign < 0 : 顺时针
 */
data class ArcEntity(
    override val id: String,
    val center: Point2D,
    val radius: Double,
    val startAngleDeg: Double,
    val endAngleDeg: Double,
    val sweepSign: Double = 1.0, // +1 逆时针, -1 顺时针
    override val layer: String = "0",
    override val selected: Boolean = true
) : GeometryEntity(id, layer, selected, GeometryEntityType.ARC) {
    override fun boundingBox(): BoundingBox {
        // 简化：圆的包围盒
        return BoundingBox(
            center.x - radius, center.y - radius,
            center.x + radius, center.y + radius
        )
    }

    /** 起始点。 */
    val startPoint: Point2D
        get() {
            val rad = Math.toRadians(startAngleDeg)
            return Point2D(center.x + radius * Math.cos(rad), center.y + radius * Math.sin(rad))
        }

    /** 终止点。 */
    val endPoint: Point2D
        get() {
            val rad = Math.toRadians(endAngleDeg)
            return Point2D(center.x + radius * Math.cos(rad), center.y + radius * Math.sin(rad))
        }

    /** 扫过角度（绝对值，0-360）。 */
    val sweepAngleDeg: Double get() {
        var d = Math.abs(endAngleDeg - startAngleDeg) % 360
        if (d < 0) d += 360.0
        return d
    }
}

/**
 * 轻量多段线（LWPOLYLINE）。
 *
 * 每个顶点可通过 bulge 表达圆弧段：
 *   bulge > 0 : 凸弧（朝 z 轴正方向）
 *   bulge < 0 : 凹弧
 *   bulge = 0 : 直线段
 *
 * bulge 与弧半径的关系：
 *   radius = chordLength / (2 * |bulge|)
 */
data class LwPolylineEntity(
    override val id: String,
    val vertices: List<PolylineVertex>,
    val closed: Boolean = false,
    override val layer: String = "0",
    override val selected: Boolean = true
) : GeometryEntity(id, layer, selected, GeometryEntityType.LWPOLYLINE) {

    init {
        require(vertices.size >= 2) { "LWPOLYLINE 至少需要 2 个顶点" }
    }

    override fun boundingBox(): BoundingBox {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        vertices.forEach { v ->
            minX = minOf(minX, v.point.x)
            minY = minOf(minY, v.point.y)
            maxX = maxOf(maxX, v.point.x)
            maxY = maxOf(maxY, v.point.y)
        }
        return BoundingBox(minX, minY, maxX, maxY)
    }

    /** 总长度（近似）。 */
    val totalLength: Double
        get() {
            var sum = 0.0
            for (i in 0 until vertices.size - 1) {
                sum += vertices[i].point.distanceTo(vertices[i + 1].point)
            }
            if (closed && vertices.size >= 2) {
                sum += vertices.last().point.distanceTo(vertices.first().point)
            }
            return sum
        }
}

/** 其他/不支持实体。保留 id、layer 以便追踪。 */
data class UnsupportedEntity(
    override val id: String,
    val nativeType: String,
    override val layer: String = "0",
    override val selected: Boolean = false
) : GeometryEntity(id, layer, selected, GeometryEntityType.UNSUPPORTED) {
    override fun boundingBox(): BoundingBox =
        BoundingBox(0.0, 0.0, 0.0, 0.0)
}

/** 几何校验结果。 */
data class GeometryValidation(
    val closed: Boolean,
    val selfIntersecting: Boolean,
    val invalidRelations: List<String>,
    val unsupportedEntities: List<String>,
    val message: String?
)

/**
 * 几何模型（轮廓集合）。
 *
 * 不得直接假设图纸中的所有实体都是加工轮廓。
 * CAM 阶段会单独标记哪些实体属于"轮廓"、哪些是"辅助线"、哪些是"标注"。
 */
data class GeometryModel(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val drawingId: String,
    val entities: List<GeometryEntity>,
    val validation: GeometryValidation
) : VersionedEntity {

    /** 全局边界框。 */
    val overallBounds: BoundingBox?
        get() = entities.map { it.boundingBox() }
            .reduceOrNull { acc, box -> acc.union(box) }

    /** 按 layer 分组。 */
    val byLayer: Map<String, List<GeometryEntity>>
        get() = entities.groupBy { it.layer }

    /** 仅保留可加工的实体类型。 */
    val machinable: List<GeometryEntity>
        get() = entities.filter {
            it !is UnsupportedEntity
        }
}

// ============== 几何工具函数 ==============

/** 将角度规范化到 [0, 360)。 */
fun normalizeAngleDeg(angle: Double): Double {
    var a = angle % 360
    if (a < 0) a += 360.0
    return a
}

/**
 * 计算 LWPOLYLINE 中 bulge 对应的弧半径。
 * bulge = tan(theta/4)，theta 是对应弧段的扫过角。
 */
fun bulgeToRadius(chordLength: Double, bulge: Double): Double {
    if (bulge == 0.0) return Double.POSITIVE_INFINITY
    val theta = 4 * Math.atan(bulge) // 弧度
    return Math.abs(chordLength / (2 * Math.sin(theta)))
}
