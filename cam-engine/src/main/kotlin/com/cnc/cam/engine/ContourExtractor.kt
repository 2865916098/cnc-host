package com.cnc.cam.engine

import com.cnc.model.drawing.ArcEntity
import com.cnc.model.drawing.CircleEntity
import com.cnc.model.drawing.GeometryEntity
import com.cnc.model.drawing.GeometryModel
import com.cnc.model.drawing.LineEntity
import com.cnc.model.drawing.LwPolylineEntity
import com.cnc.model.drawing.Point2D

/**
 * 加工轮廓——CAM 引擎的内部表示。
 *
 * 将 GeometryModel 的离散实体拼成连续的有向轮廓：
 * [Point2D, Point2D, ..., Point2D]
 */
data class MachiningContour(
    val id: String,
    val points: List<Point2D>,
    val closed: Boolean,
    val clockwise: Boolean,
    val sourceLayers: Set<String>,
    val sourceEntityIds: List<String>
) {
    val vertexCount: Int get() = points.size
    val minX: Double get() = points.minOf { it.x }
    val maxX: Double get() = points.maxOf { it.x }
    val minY: Double get() = points.minOf { it.y }
    val maxY: Double get() = points.maxOf { it.y }
    val centroid: Point2D
        get() {
            var sx = 0.0; var sy = 0.0
            points.forEach { sx += it.x; sy += it.y }
            return Point2D(sx / points.size, sy / points.size)
        }
}

/**
 * 轮廓提取器。
 *
 * 算法：
 *   1. 每个实体离散化为一条有序链（LINE=2 点，ARC=多点，CIRCLE=25 点，LWPOLYLINE=N 点）
 *   2. 按图层分组
 *   3. 同图层内端点图贪心拼接：链 A 的尾点 ≈ 链 B 的首点 → 把 B 接在 A 后面
 */
object ContourExtractor {

    /** 距离阈值（毫米）：两个端点在此距离内视为可拼接。 */
    const val POINT_MERGE_TOLERANCE = 1e-3

    /** 从 GeometryModel 提取所有轮廓。 */
    fun extract(model: GeometryModel): List<MachiningContour> {
        // Step 1: 每个实体 → 一条有序 IndexedPoint 链
        val chains = model.entities.flatMap { discretize(it) }
        if (chains.isEmpty()) return emptyList()

        // Step 2: 按图层分组
        val byLayer = chains.groupBy { chain -> chain.firstOrNull()?.entityLayer ?: "0" }

        val contours = mutableListOf<MachiningContour>()
        byLayer.forEach { (layer, layerChains) ->
            val merged = mergeChains(layerChains)
            merged.forEachIndexed { gi, chain ->
                if (chain.size >= 2) {
                    contours.add(buildContour(chain, "cnt-${contours.size}-$layer-$gi"))
                }
            }
        }
        return contours
    }

    /** 将单个实体离散成有序点链（每个实体一条链）。 */
    private fun discretize(entity: GeometryEntity): List<List<IndexedPoint>> {
        return when (entity) {
            is LineEntity -> listOf(listOf(
                IndexedPoint(entity.start, entity.id, entity.layer),
                IndexedPoint(entity.end, entity.id, entity.layer)
            ))
            is CircleEntity -> {
                val n = 24
                val chain = (0..n).map { i ->
                    val angle = Math.toRadians(360.0 * i / n)
                    IndexedPoint(
                        Point2D(
                            entity.center.x + entity.radius * Math.cos(angle),
                            entity.center.y + entity.radius * Math.sin(angle)
                        ),
                        entity.id, entity.layer
                    )
                }
                listOf(chain)
            }
            is ArcEntity -> {
                val sweep = entity.sweepAngleDeg
                val steps = maxOf(8, (sweep / 10).toInt())
                val start = entity.startAngleDeg
                val dir = if (entity.sweepSign >= 0) 1 else -1
                val chain = (0..steps).map { i ->
                    val angleDeg = start + dir * sweep * i / steps
                    val rad = Math.toRadians(angleDeg)
                    IndexedPoint(
                        Point2D(
                            entity.center.x + entity.radius * Math.cos(rad),
                            entity.center.y + entity.radius * Math.sin(rad)
                        ),
                        entity.id, entity.layer
                    )
                }
                listOf(chain)
            }
            is LwPolylineEntity -> {
                val chain = entity.vertices.map { v ->
                    IndexedPoint(v.point, entity.id, entity.layer)
                }
                val full = if (entity.closed && chain.size >= 2) {
                    chain + IndexedPoint(chain.first().point, entity.id, entity.layer)
                } else chain
                listOf(full)
            }
            else -> emptyList()
        }
    }

    /**
     * 端点图贪心拼接。
     *
     * 维护"未使用链"集合，每次取第一条链作为当前轮廓，
     * 不断找端点匹配的链接在 tail 或 prepend 到 head。
     */
    private fun mergeChains(chains: List<List<IndexedPoint>>): List<List<IndexedPoint>> {
        if (chains.isEmpty()) return emptyList()
        val unused = chains.toMutableList()
        val result = mutableListOf<List<IndexedPoint>>()

        while (unused.isNotEmpty()) {
            var chain = unused.removeAt(0).toMutableList()
            var growing = true
            while (growing) {
                growing = false
                val head = chain.first().point
                val tail = chain.last().point

                // 1. 尝试从后面接（别的链的 head == 我的 tail）
                val idxTail = unused.indexOfFirst { it.first().point.distanceTo(tail) <= POINT_MERGE_TOLERANCE }
                if (idxTail >= 0) {
                    val other = unused.removeAt(idxTail)
                    chain.addAll(other.drop(1)) // drop(1) 避免重复端点
                    growing = true
                    continue
                }
                // 2. 尝试反向接（别的链的 tail == 我的 tail → 它反向后接在后面）
                val idxTailRev = unused.indexOfFirst { it.last().point.distanceTo(tail) <= POINT_MERGE_TOLERANCE }
                if (idxTailRev >= 0) {
                    val other = unused.removeAt(idxTailRev).reversed()
                    chain.addAll(other.drop(1))
                    growing = true
                    continue
                }
                // 3. 尝试 prepend（别的链的 tail == 我的 head）
                val idxHead = unused.indexOfFirst { it.last().point.distanceTo(head) <= POINT_MERGE_TOLERANCE }
                if (idxHead >= 0) {
                    val other = unused.removeAt(idxHead)
                    chain.addAll(0, other.dropLast(1)) // prepend 但 dropLast 避免重复
                    growing = true
                    continue
                }
                // 4. 尝试反向 prepend
                val idxHeadRev = unused.indexOfFirst { it.first().point.distanceTo(head) <= POINT_MERGE_TOLERANCE }
                if (idxHeadRev >= 0) {
                    val other = unused.removeAt(idxHeadRev).reversed()
                    chain.addAll(0, other.dropLast(1))
                    growing = true
                    continue
                }
            }
            result.add(chain)
        }
        return result
    }

    /** 从一条合并后的链构建 MachiningContour。 */
    private fun buildContour(chain: List<IndexedPoint>, id: String): MachiningContour {
        val points = chain.map { it.point }
        val closed = points.size >= 4 &&
            points.first().distanceTo(points.last()) <= POINT_MERGE_TOLERANCE
        val unique = if (closed) points.dropLast(1) else points
        val clockwise = computeOrientation(unique) > 0
        val layers = chain.map { it.entityLayer }.toSet()
        val ids = chain.map { it.entityId }.distinct()
        return MachiningContour(id, unique, closed, clockwise, layers, ids)
    }

    /** 多边形方向（正负号：正=顺时针，负=逆时针）。 */
    private fun computeOrientation(points: List<Point2D>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            sum += (points[j].x - points[i].x) * (points[j].y + points[i].y)
        }
        return sum
    }

    private data class IndexedPoint(
        val point: Point2D,
        val entityId: String = "",
        val entityLayer: String = "0"
    )
}
