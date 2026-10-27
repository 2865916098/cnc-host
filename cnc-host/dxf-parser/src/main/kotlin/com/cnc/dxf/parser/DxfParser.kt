package com.cnc.dxf.parser

/**
 * DXF Text 格式解析器。
 *
 * DXF 的基本单位是"组"（group）：每两行组成一个组，
 * 第一行是组代码（整数，含义见 DXF spec），
 * 第二行是组值（字符串/数字）。
 *
 * 典型格式：
 * ```
 *  0          ← group code
 * LINE       ← group value
 *  8          ← group code
 * Layer1     ← group value (layer name)
 * 10
 * 0.0        ← X
 * 20
 * 0.0        ← Y
 * 11
 * 100.0
 * 21
 * 50.0
 * ```
 *
 * 本解析器覆盖：
 * - HEADER 段（EXTMIN/EXTMAX/INSUNITS 获取图纸边界与单位）
 * - TABLES 段（LAYER 表，只采集不解析图层细节）
 * - ENTITIES 段（LINE / CIRCLE / ARC / LWPOLYLINE）
 *
 * 不覆盖：BLOCKS / OBJECTS / MTEXT / 各种复杂实体。
 * 未识别的实体 → UnsupportedEntity。
 */

import com.cnc.model.common.UnitSystem
import com.cnc.model.drawing.ArcEntity
import com.cnc.model.drawing.CircleEntity
import com.cnc.model.drawing.GeometryEntity
import com.cnc.model.drawing.GeometryModel
import com.cnc.model.drawing.GeometryValidation
import com.cnc.model.drawing.LineEntity
import com.cnc.model.drawing.LwPolylineEntity
import com.cnc.model.drawing.Point2D
import com.cnc.model.drawing.PolylineVertex
import com.cnc.model.drawing.UnsupportedEntity
import com.cnc.model.drawing.normalizeAngleDeg
import java.io.File

/** DXF 解析结果。 */
data class DxfParseResult(
    val headerInfo: DxfHeaderInfo,
    val entities: List<GeometryEntity>,
    val warnings: List<String>
) {
    val success: Boolean get() = warnings.none { it.contains("严重") }
}

/** DXF HEADER 段提取的关键信息。 */
data class DxfHeaderInfo(
    /** 单位系统。未设置时为 null。 */
    val unit: UnitSystem? = null,
    /** 图形边界最小点（HEADER 的 EXTMIN）。可能全零。 */
    val extMin: Point2D? = null,
    /** 图形边界最大点（HEADER 的 EXTMAX）。可能全零。 */
    val extMax: Point2D? = null,
    /** DXF 版本字符串（$ACADVER）。 */
    val version: String? = null
)

/** 解析异常。 */
class DxfParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * DXF Text 格式解析器入口。
 *
 * 使用方式：
 * ```
 * val result = DxfParser.parseText(dxfText)
 * val model = result.toGeometryModel(drawingId = "drawing-001")
 * ```
 */
object DxfParser {

    /** 从文件解析。 */
    fun parseFile(file: File): DxfParseResult {
        if (!file.exists()) {
            throw DxfParseException("文件不存在: ${file.absolutePath}")
        }
        val text = file.readText()
        return parseText(text)
    }

    /** 从字符串解析。 */
    fun parseText(text: String): DxfParseResult {
        val pairs = readPairs(text)
        val sections = splitSections(pairs)
        val warnings = mutableListOf<String>()

        val headerInfo = parseHeader(sections["HEADER"] ?: emptyList())

        val entities = parseEntities(sections["ENTITIES"] ?: emptyList(), warnings)

        // BLOCKS 段如果有，里面也可能包含 LINE/CIRCLE 等（块定义中的实体）。
        // CNC 场景下通常不需要，但我们仍做一个最简探测：
        // 如果只在 BLOCKS 里有实体而 ENTITIES 为空，发出警告。
        if (entities.isEmpty() && sections["BLOCKS"] != null) {
            warnings.add("图纸 ENTITIES 段为空，但 BLOCKS 段存在——可能需要单独处理块引用")
        }

        return DxfParseResult(
            headerInfo = headerInfo,
            entities = entities,
            warnings = warnings
        )
    }

    // ============== Step 1: 读组对 ==============

    private fun readPairs(text: String): List<Pair<Int, String>> {
        val lines = text.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val pairs = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (i + 1 < lines.size) {
            val codeStr = lines[i]
            val value = lines[i + 1]
            val code = codeStr.toIntOrNull()
            if (code != null) {
                pairs.add(code to value)
                i += 2
            } else {
                // 跳过异常行
                i++
            }
        }
        return pairs
    }

    // ============== Step 2: 按 SECTION 切分 ==============

    private fun splitSections(
        pairs: List<Pair<Int, String>>
    ): Map<String, List<Pair<Int, String>>> {
        val result = mutableMapOf<String, List<Pair<Int, String>>>()
        val currentSection = StringBuilder()
        var currentName: String? = null
        val sectionPairs = mutableListOf<Pair<Int, String>>()

        var i = 0
        while (i < pairs.size) {
            val (code, value) = pairs[i]
            when {
                code == 0 && value.equals("SECTION", ignoreCase = true) -> {
                    // 下一对应该是 2 / SECTION_NAME
                    if (i + 1 < pairs.size && pairs[i + 1].first == 2) {
                        val name = pairs[i + 1].second.uppercase()
                        currentName = name
                        sectionPairs.clear()
                        i += 2
                        continue
                    }
                }
                code == 0 && value.equals("ENDSEC", ignoreCase = true) -> {
                    if (currentName != null) {
                        result[currentName] = sectionPairs.toList()
                    }
                    currentName = null
                    sectionPairs.clear()
                }
                code == 0 && value.equals("EOF", ignoreCase = true) -> {
                    break
                }
                else -> {
                    if (currentName != null) {
                        sectionPairs.add(code to value)
                    }
                }
            }
            i++
        }
        return result
    }

    // ============== Step 3: HEADER 解析 ==============

    private fun parseHeader(pairs: List<Pair<Int, String>>): DxfHeaderInfo {
        val map = pairs.associate { it }

        val version = map[1]
        val unit = when (map[70]?.toIntOrNull()) {
            1 -> UnitSystem.METRIC_MM
            4 -> UnitSystem.IMPERIAL_INCH
            else -> null
        }

        // EXTMIN: 9 + "$EXTMIN", 10=x, 20=y
        // EXTMAX: 9 + "$EXTMAX", 10=x, 20=y
        // HEADER 中这些不会按 Section 结构嵌套，而是平坦的：
        //   9, "$EXTMIN", 10, "-10.0", 20, "-10.0", 30, "0.0",
        //   9, "$EXTMAX", 10, "100.0", 20, "50.0", 30, "0.0",
        // 所以用顺序扫描：
        var extMin: Point2D? = null
        var extMax: Point2D? = null
        var i = 0
        while (i < pairs.size) {
            val (c, v) = pairs[i]
            if (c == 9) {
                val varName = v.uppercase()
                when {
                    varName == "\$EXTMIN" && i + 2 < pairs.size -> {
                        val x = pairs[i + 1].second.toDoubleOrNull() ?: 0.0
                        val y = pairs[i + 2].second.toDoubleOrNull() ?: 0.0
                        extMin = Point2D(x, y)
                    }
                    varName == "\$EXTMAX" && i + 2 < pairs.size -> {
                        val x = pairs[i + 1].second.toDoubleOrNull() ?: 0.0
                        val y = pairs[i + 2].second.toDoubleOrNull() ?: 0.0
                        extMax = Point2D(x, y)
                    }
                }
            }
            i++
        }

        return DxfHeaderInfo(unit = unit, extMin = extMin, extMax = extMax, version = version)
    }

    // ============== Step 4: ENTITIES 解析 ==============

    private fun parseEntities(
        pairs: List<Pair<Int, String>>,
        warnings: MutableList<String>
    ): List<GeometryEntity> {
        val entities = mutableListOf<GeometryEntity>()
        val chunks = splitByCodeZero(pairs)
        var seq = 0

        for (chunk in chunks) {
            if (chunk.isEmpty()) continue
            val (_, typeName) = chunk.first()
            val nativeType = typeName.uppercase()
            val id = "ent-${entities.size}"
            seq++

            val entity = when (nativeType) {
                "LINE" -> parseLine(chunk, id)
                "CIRCLE" -> parseCircle(chunk, id)
                "ARC" -> parseArc(chunk, id)
                "LWPOLYLINE" -> parseLwPolyline(chunk, id)
                else -> {
                    warnings.add("未支持的实体类型: $nativeType")
                    UnsupportedEntity(id = id, nativeType = nativeType)
                }
            }
            entities.add(entity)
        }
        return entities
    }

    /** 按组代码 0 切分（DXF 中每个实体以 0 / ENTITY_NAME 开头）。 */
    private fun splitByCodeZero(
        pairs: List<Pair<Int, String>>
    ): List<List<Pair<Int, String>>> {
        val result = mutableListOf<List<Pair<Int, String>>>()
        var current = mutableListOf<Pair<Int, String>>()
        for ((code, value) in pairs) {
            if (code == 0) {
                if (current.isNotEmpty()) {
                    result.add(current)
                }
                current = mutableListOf(code to value)
            } else {
                current.add(code to value)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current)
        }
        return result
    }

    // ============== 单实体解析 ==============

    private fun extractLayer(pairs: List<Pair<Int, String>>): String {
        for ((code, value) in pairs) {
            if (code == 8) return value
        }
        return "0"
    }

    private fun parseLine(
        pairs: List<Pair<Int, String>>,
        id: String
    ): GeometryEntity {
        val map = pairs.associate { it }
        val sx = map[10]?.toDoubleOrNull() ?: 0.0
        val sy = map[20]?.toDoubleOrNull() ?: 0.0
        val ex = map[11]?.toDoubleOrNull() ?: 0.0
        val ey = map[21]?.toDoubleOrNull() ?: 0.0
        return LineEntity(
            id = id,
            start = Point2D(sx, sy),
            end = Point2D(ex, ey),
            layer = extractLayer(pairs)
        )
    }

    private fun parseCircle(
        pairs: List<Pair<Int, String>>,
        id: String
    ): GeometryEntity {
        val map = pairs.associate { it }
        val cx = map[10]?.toDoubleOrNull() ?: 0.0
        val cy = map[20]?.toDoubleOrNull() ?: 0.0
        val r = map[40]?.toDoubleOrNull() ?: 0.0
        return CircleEntity(
            id = id,
            center = Point2D(cx, cy),
            radius = r,
            layer = extractLayer(pairs)
        )
    }

    private fun parseArc(
        pairs: List<Pair<Int, String>>,
        id: String
    ): GeometryEntity {
        val map = pairs.associate { it }
        val cx = map[10]?.toDoubleOrNull() ?: 0.0
        val cy = map[20]?.toDoubleOrNull() ?: 0.0
        val r = map[40]?.toDoubleOrNull() ?: 0.0
        val startAngle = map[50]?.toDoubleOrNull() ?: 0.0
        val endAngle = map[51]?.toDoubleOrNull() ?: 0.0
        // DXF 默认逆时针：sweepSign 正
        return ArcEntity(
            id = id,
            center = Point2D(cx, cy),
            radius = r,
            startAngleDeg = normalizeAngleDeg(startAngle),
            endAngleDeg = normalizeAngleDeg(endAngle),
            sweepSign = 1.0,
            layer = extractLayer(pairs)
        )
    }

    /**
     * 解析 LWPOLYLINE。
     *
     * LWPOLYLINE 顶点是重复出现的 10/20 (bulge 为 42)：
     *   90   → vertex count
     *   70   → flags (bit 0 = closed)
     *   10, 20, 42 (重复 N 次)
     */
    private fun parseLwPolyline(
        pairs: List<Pair<Int, String>>,
        id: String
    ): GeometryEntity {
        var vertexCount = 0
        var closed = false
        var i = 0
        val vertices = mutableListOf<PolylineVertex>()

        while (i < pairs.size) {
            val (code, value) = pairs[i]
            when (code) {
                90 -> vertexCount = value.toIntOrNull() ?: 0
                70 -> closed = (value.toIntOrNull() ?: 0) and 1 != 0
                10 -> {
                    // 顶点起点：10=x, 20=y, (可选 42=bulge)
                    val x = value.toDoubleOrNull() ?: 0.0
                    val y = if (i + 1 < pairs.size && pairs[i + 1].first == 20) {
                        pairs[i + 1].second.toDoubleOrNull() ?: 0.0
                    } else 0.0
                    var bulge = 0.0
                    // 在 20 之后可能紧跟 42=bulge（DXF 中 bulge 紧跟顶点）
                    if (i + 2 < pairs.size && pairs[i + 2].first == 42) {
                        bulge = pairs[i + 2].second.toDoubleOrNull() ?: 0.0
                    }
                    vertices.add(PolylineVertex(Point2D(x, y), bulge))
                }
            }
            i++
        }

        return LwPolylineEntity(
            id = id,
            vertices = vertices,
            closed = closed,
            layer = extractLayer(pairs)
        )
    }
}

// ============== 辅助转换 ==============

/** 将 DxfParseResult 转为完整 GeometryModel。 */
fun DxfParseResult.toGeometryModel(
    drawingId: String,
    modelId: String = "geo-${System.currentTimeMillis()}"
): GeometryModel {
    val closed = entities.any { it is LwPolylineEntity && it.closed }
    val unsupported = entities.filterIsInstance<UnsupportedEntity>().map { it.nativeType }
    val hasWarnings = warnings.isNotEmpty()

    val validation = GeometryValidation(
        closed = closed,
        selfIntersecting = false, // 后续 CAM 阶段补充几何校验
        invalidRelations = emptyList(),
        unsupportedEntities = unsupported,
        message = if (hasWarnings) warnings.joinToString("; ") else null
    )

    return GeometryModel(
        id = modelId,
        drawingId = drawingId,
        entities = entities,
        validation = validation
    )
}
