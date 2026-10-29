package com.cnc.cam.engine

import com.cnc.model.cam.ProcessStep
import com.cnc.model.cam.ProcessType
import com.cnc.model.cam.Toolpath
import com.cnc.model.cam.ToolpathSegment
import com.cnc.model.cam.ToolpathValidation
import com.cnc.model.cam.MotionType
import com.cnc.model.drawing.BoundingBox
import com.cnc.model.drawing.Point2D
import kotlin.math.abs

/**
 * CAM 刀路生成引擎。
 *
 * 面向两轴数控车床（X/Z）。
 * X 轴 = 工件直径方向（0 在中心，双向）
 * Z 轴 = 轴向（通常 Z+ 在安全位置，向卡盘为 Z-）
 *
 * 输入：MachiningContour + ProcessStep + 毛坯 + 刀具几何
 * 输出：Toolpath（Segment 列表 + 校验结果）
 */
object CamEngine {

    private const val DEFAULT_SAFE_X = 200.0  // Z 方向起始安全位置（远离工件）
    private const val DEFAULT_SAFE_Z = 200.0  // X 方向安全位置（直径外）

    /**
     * 根据工艺步骤生成 Toolpath。
     *
     * @param contour   加工轮廓（闭合的零件外形）
     * @param step      单个工艺步骤
     * @param stockDiameter  毛坯直径（X 方向起始切削量）
     * @param stockLength    毛坯轴向长度（Z 方向起始切削量）
     * @param processPlanId  工艺方案 id（用于版本绑定）
     * @param toolNumber     刀号
     * @param machineProfileVersion 设备配置版本
     */
    fun generate(
        contour: MachiningContour,
        step: ProcessStep,
        stockDiameter: Double,
        stockLength: Double,
        processPlanId: String,
        toolNumber: Int,
        machineProfileVersion: Int = 1
    ): Toolpath {
        val rawSegments: List<ToolpathSegment> = when (step.type) {
            ProcessType.ROUGH_TURNING ->
                generateRoughTurning(contour, step, stockDiameter, stockLength, toolNumber)
            ProcessType.FINISH_TURNING ->
                generateFinishTurning(contour, step, toolNumber)
            ProcessType.GROOVING ->
                generateGrooving(contour, step, toolNumber)
            ProcessType.THREADING ->
                generateThreading(contour, step, toolNumber)
            ProcessType.DRILLING ->
                generateDrilling(contour, step, toolNumber)
            ProcessType.CUT_OFF ->
                generateCutOff(contour, step, toolNumber)
        }

        // 统一填充 feed / spindle
        val segments = rawSegments.map { seg ->
            if (seg.motionType == MotionType.RAPID) seg
            else seg.copy(
                feedMmPerMin = seg.feedMmPerMin.takeIf { it > 0 } ?: step.cuttingSpeedMmPerMin,
                spindleRpm = seg.spindleRpm.takeIf { it > 0 } ?: step.spindleRpm
            )
        }

        val validation = validate(segments, contour, step)
        // 独立否决权（规范 §3.3）：非法坐标 / 超行程 / 段间跳变 / 转速超限，任一失败即不可执行
        val valid = validation.noIllegalCoordinates && validation.withinTravel &&
            validation.noAbnormalJumps && validation.spindleWithinLimit

        return Toolpath(
            id = "tp-${step.type.name.lowercase()}-${contour.id}",
            processPlanId = processPlanId,
            processPlanVersion = 1,
            machineProfileVersion = machineProfileVersion,
            segments = segments,
            validation = validation,
            valid = valid
        )
    }

    // ============== 粗车削 ==============

    /**
     * 粗车削：分层切深 + 往返扫描。
     *
     * 核心算法：
     *   1. 计算轮廓的 bounding box → 确定 X/Z 切削范围
     *   2. 从毛坯直径出发，按 depthOfCut 分层递减到 finishContour
     *   3. 每一层：从 Z+ 向 Z- 扫描（快速接近 → 进刀 → 切削 → 退刀）
     */
    private fun generateRoughTurning(
        contour: MachiningContour,
        step: ProcessStep,
        stockDiameter: Double,
        stockLength: Double,
        toolNumber: Int
    ): List<ToolpathSegment> {
        val segments = mutableListOf<ToolpathSegment>()

        val xMaxStock = stockDiameter / 2.0  // 毛坯半径
        val xMinContour = contour.minX       // 轮廓最小 X（通常就是外圆半径）
        val zStart = contour.maxY + step.approachDistanceMm  // 安全起始 Z
        val zEnd = contour.minY                               // 切削终止 Z
        val xStartSafe = stockDiameter + step.safetyDistanceMm * 2 // X 安全距离（直径）
        val zSafe = zStart + step.safetyDistanceMm

        val totalStock = xMaxStock - xMinContour
        if (totalStock <= 0) {
            // 毛坯比轮廓还小，生成空刀路 + 警告
            return segments
        }

        val cutDepth = step.depthOfCutMm.coerceAtLeast(0.5)
        val layers = Math.ceil(totalStock / cutDepth).toInt()

        // 初始定位：G00 到安全位置
        var cursor = Point2D(0.0, 0.0)
        val initMove = Point2D(xStartSafe, zSafe)
        segments.add(
            ToolpathSegment(
                segmentId = "seg-0-rapid-home",
                motionType = MotionType.RAPID,
                start = cursor,
                end = initMove,
                feedMmPerMin = 0.0,
                spindleRpm = step.spindleRpm,
                processType = ProcessType.ROUGH_TURNING,
                toolNumber = toolNumber
            )
        )
        cursor = initMove

        for (layerIdx in 0 until layers) {
            // 当前层目标半径（递减）
            val targetRadius = xMaxStock - cutDepth * (layerIdx + 1)
            val finalRadius = minOf(targetRadius, xMinContour)

            // 进刀：从安全 X 下降到切削半径（快进）
            val rapidDownEnd = Point2D(finalRadius, zStart)
            segments.add(
                ToolpathSegment(
                    segmentId = "seg-${layerIdx}-rapid-down",
                    motionType = MotionType.RAPID,
                    start = cursor,
                    end = rapidDownEnd,
                    processType = ProcessType.ROUGH_TURNING,
                    toolNumber = toolNumber
                )
            )

            // 切削：从 zStart 切到 zEnd（直线插补）
            val cutEnd = Point2D(finalRadius, zEnd)
            segments.add(
                ToolpathSegment(
                    segmentId = "seg-${layerIdx}-cut",
                    motionType = MotionType.LINEAR_FEED,
                    start = rapidDownEnd,
                    end = cutEnd,
                    feedMmPerMin = step.cuttingSpeedMmPerMin,
                    spindleRpm = step.spindleRpm,
                    processType = ProcessType.ROUGH_TURNING,
                    toolNumber = toolNumber
                )
            )

            // 退刀：Z 方向安全后退
            val retract = Point2D(finalRadius, zStart)
            segments.add(
                ToolpathSegment(
                    segmentId = "seg-${layerIdx}-retract",
                    motionType = MotionType.RAPID,
                    start = cutEnd,
                    end = retract,
                    processType = ProcessType.ROUGH_TURNING,
                    toolNumber = toolNumber
                )
            )
            cursor = retract

            // 抬刀：X 回到安全位置（为下一层做准备）
            val lift = Point2D(xStartSafe, zStart)
            segments.add(
                ToolpathSegment(
                    segmentId = "seg-${layerIdx}-lift",
                    motionType = MotionType.RAPID,
                    start = retract,
                    end = lift,
                    processType = ProcessType.ROUGH_TURNING,
                    toolNumber = toolNumber
                )
            )
            cursor = lift
        }

        return segments
    }

    // ============== 精车削 ==============

    /**
     * 精车削：沿轮廓等距偏移（向内走 finishAllowance），一次走刀。
     *
     * 算法：对闭合轮廓向内 finishAllowance 等距偏移 → 生成 G01/G02 序列
     */
    private fun generateFinishTurning(
        contour: MachiningContour,
        step: ProcessStep,
        toolNumber: Int
    ): List<ToolpathSegment> {
        val segments = mutableListOf<ToolpathSegment>()

        val allowance = step.finishAllowanceMm
        val zStart = contour.maxY + step.approachDistanceMm
        val xStartSafe = contour.maxX + step.safetyDistanceMm * 2
        val safeStart = Point2D(xStartSafe, zStart)

        segments.add(
            ToolpathSegment(
                segmentId = "seg-f-rapid-home",
                motionType = MotionType.RAPID,
                start = Point2D(0.0, 0.0),
                end = safeStart,
                processType = ProcessType.FINISH_TURNING,
                toolNumber = toolNumber
            )
        )

        // 简化精加工：从 zStart 一次走到 minY，半径 = 轮廓半径 - allowance
        // （等距偏移是复杂 CAD 算法，这里用简化版）
        val finishRadius = contour.minX - allowance
        val rapidIn = Point2D(finishRadius, zStart)
        segments.add(
            ToolpathSegment(
                segmentId = "seg-f-rapid-in",
                motionType = MotionType.RAPID,
                start = safeStart,
                end = rapidIn,
                processType = ProcessType.FINISH_TURNING,
                toolNumber = toolNumber
            )
        )

        val cutEnd = Point2D(finishRadius, contour.minY)
        segments.add(
            ToolpathSegment(
                segmentId = "seg-f-finish-cut",
                motionType = MotionType.LINEAR_FEED,
                start = rapidIn,
                end = cutEnd,
                feedMmPerMin = step.cuttingSpeedMmPerMin,
                spindleRpm = step.spindleRpm,
                processType = ProcessType.FINISH_TURNING,
                toolNumber = toolNumber
            )
        )

        // 退刀
        segments.add(
            ToolpathSegment(
                segmentId = "seg-f-retract",
                motionType = MotionType.RAPID,
                start = cutEnd,
                end = Point2D(finishRadius, zStart),
                processType = ProcessType.FINISH_TURNING,
                toolNumber = toolNumber
            )
        )

        return segments
    }

    // ============== 切槽 / 螺纹 / 钻孔 / 切断 ==============

    private fun generateGrooving(
        contour: MachiningContour,
        step: ProcessStep,
        toolNumber: Int
    ): List<ToolpathSegment> {
        val segments = mutableListOf<ToolpathSegment>()
        val zStart = contour.maxY + step.approachDistanceMm
        // 简化：假设槽在轮廓中心
        val groveZ = (contour.maxY + contour.minY) / 2
        val outerRadius = contour.maxX
        val grooveDepth = step.depthOfCutMm
        val xStartSafe = outerRadius + step.safetyDistanceMm * 2

        val safe = Point2D(xStartSafe, zStart)
        val rapidIn = Point2D(outerRadius, groveZ)
        val cutToBottom = Point2D(outerRadius - grooveDepth, groveZ)

        segments += ToolpathSegment("seg-g0", MotionType.RAPID, Point2D(0.0, 0.0), safe, processType = ProcessType.GROOVING, toolNumber = toolNumber)
        segments += ToolpathSegment("seg-g1", MotionType.RAPID, safe, rapidIn, processType = ProcessType.GROOVING, toolNumber = toolNumber)
        segments += ToolpathSegment("seg-g2", MotionType.LINEAR_FEED, rapidIn, cutToBottom, feedMmPerMin = step.cuttingSpeedMmPerMin, spindleRpm = step.spindleRpm, processType = ProcessType.GROOVING, toolNumber = toolNumber)
        segments += ToolpathSegment("seg-g3", MotionType.RAPID, cutToBottom, safe, processType = ProcessType.GROOVING, toolNumber = toolNumber)
        return segments
    }

    private fun generateThreading(
        contour: MachiningContour,
        step: ProcessStep,
        toolNumber: Int
    ): List<ToolpathSegment> {
        val segments = mutableListOf<ToolpathSegment>()
        val zStart = contour.maxY + step.approachDistanceMm
        val outerRadius = contour.maxX
        val rootRadius = outerRadius - step.depthOfCutMm * 2
        val xStartSafe = outerRadius + step.safetyDistanceMm * 2

        // 多次进刀近似螺纹：每刀进 depthOfCut 一部分
        val passes = 5
        for (p in 1..passes) {
            val targetRadius = outerRadius - (outerRadius - rootRadius) * p / passes
            val passStart = Point2D(xStartSafe, zStart)
            val passIn = Point2D(targetRadius, zStart)
            val passEnd = Point2D(targetRadius, contour.minY)
            val passOut = Point2D(xStartSafe, contour.minY)

            segments += ToolpathSegment("seg-t${p}r", MotionType.RAPID, passStart, passIn, processType = ProcessType.THREADING, toolNumber = toolNumber)
            segments += ToolpathSegment("seg-t${p}f", MotionType.LINEAR_FEED, passIn, passEnd, feedMmPerMin = step.cuttingSpeedMmPerMin, spindleRpm = step.spindleRpm, processType = ProcessType.THREADING, toolNumber = toolNumber)
            segments += ToolpathSegment("seg-t${p}o", MotionType.RAPID, passEnd, passOut, processType = ProcessType.THREADING, toolNumber = toolNumber)
        }
        return segments
    }

    private fun generateDrilling(
        contour: MachiningContour,
        step: ProcessStep,
        toolNumber: Int
    ): List<ToolpathSegment> {
        val segments = mutableListOf<ToolpathSegment>()
        // 简化：在零件中心（X=0）钻孔
        val zStart = contour.maxY + step.approachDistanceMm
        val zEnd = contour.minY - step.depthOfCutMm // 钻穿
        val zRetract = zStart

        segments += ToolpathSegment("seg-dr", MotionType.RAPID, Point2D(0.0, 0.0), Point2D(0.0, zStart), processType = ProcessType.DRILLING, toolNumber = toolNumber)
        segments += ToolpathSegment("seg-dc", MotionType.LINEAR_FEED, Point2D(0.0, zStart), Point2D(0.0, zEnd), feedMmPerMin = step.cuttingSpeedMmPerMin, spindleRpm = step.spindleRpm, processType = ProcessType.DRILLING, toolNumber = toolNumber)
        segments += ToolpathSegment("seg-drt", MotionType.RAPID, Point2D(0.0, zEnd), Point2D(0.0, zRetract), processType = ProcessType.DRILLING, toolNumber = toolNumber)
        return segments
    }

    private fun generateCutOff(
        contour: MachiningContour,
        step: ProcessStep,
        toolNumber: Int
    ): List<ToolpathSegment> {
        val segments = mutableListOf<ToolpathSegment>()
        val zCut = contour.minY - 5.0 // 零件端面之外
        val outerRadius = contour.maxX
        val xStartSafe = outerRadius + step.safetyDistanceMm * 2

        segments += ToolpathSegment("seg-co0", MotionType.RAPID, Point2D(0.0, 0.0), Point2D(xStartSafe, zCut), processType = ProcessType.CUT_OFF, toolNumber = toolNumber)
        segments += ToolpathSegment("seg-co1", MotionType.LINEAR_FEED, Point2D(xStartSafe, zCut), Point2D(0.0, zCut), feedMmPerMin = step.cuttingSpeedMmPerMin, spindleRpm = step.spindleRpm, processType = ProcessType.CUT_OFF, toolNumber = toolNumber)
        segments += ToolpathSegment("seg-co2", MotionType.RAPID, Point2D(0.0, zCut), Point2D(xStartSafe, zCut + step.retractDistanceMm), processType = ProcessType.CUT_OFF, toolNumber = toolNumber)
        return segments
    }

    // ============== 校验 ==============

    /**
     * 独立验证器（规范 §3.3）：校验刀路合法性。
     *
     * 已实现的校验（失败即拒绝，[Toolpath.valid] = false）：
     * - 非法坐标（NaN/无穷）
     * - 行程越界（|X| ≤ [maxTravelX]，|Z| ≤ [maxTravelZ]，由设备配置提供）
     * - 段间连续性（相邻段首尾必须重合）
     * - 主轴转速上限（≤ [maxSpindleRpm]）
     *
     * 未实现的校验（不得宣称通过）：毛坯碰撞、几何过切——
     * 在 [ToolpathValidation.unverifiedChecks] 中明示，由操作员知悉。
     */
    fun validate(
        segments: List<ToolpathSegment>,
        contour: MachiningContour,
        step: ProcessStep,
        maxTravelX: Double = 500.0,
        maxTravelZ: Double = 500.0,
        maxSpindleRpm: Int = 10000
    ): ToolpathValidation {
        val messages = mutableListOf<String>()

        // 1. 空刀路警告
        if (segments.isEmpty()) {
            messages.add("刀路为空")
        }

        // 2. 非法坐标（NaN/无穷）——与行程校验分离
        var noIllegal = true
        for (seg in segments) {
            val coords = listOf(seg.start.x, seg.start.y, seg.end.x, seg.end.y)
            if (coords.any { it.isNaN() || it.isInfinite() }) {
                noIllegal = false; messages.add("段 ${seg.segmentId} 包含非法坐标（NaN/无穷）")
            }
        }

        // 3. 行程校验（真实设备行程上限；NaN/无穷段已由第 2 步标记，此处跳过比较）
        var withinTravel = true
        for (seg in segments) {
            if (listOf(seg.start.x, seg.end.x).any { it.isFinite() && abs(it) > maxTravelX }) {
                withinTravel = false; messages.add("段 ${seg.segmentId} X 轴超出行程 ±$maxTravelX mm")
            }
            if (listOf(seg.start.y, seg.end.y).any { it.isFinite() && abs(it) > maxTravelZ }) {
                withinTravel = false; messages.add("段 ${seg.segmentId} Z 轴超出行程 ±$maxTravelZ mm")
            }
        }

        // 4. 异常跳跃（RAPID 之后立即一个很大的 FEED 跳跃，说明快进没到位）
        var noAbnormalJumps = true
        for (i in 1 until segments.size) {
            val prev = segments[i - 1]
            val cur = segments[i]
            // 上一段终点 ≠ 本段起点（可能遗漏）
            if (prev.end.distanceTo(cur.start) > 0.01) {
                noAbnormalJumps = false
                messages.add("段 ${cur.segmentId} 起点与前一段终点不重合（跳变 ${prev.end.distanceTo(cur.start)}）")
            }
        }

        // 5. 主轴转速上限校验
        var spindleWithinLimit = true
        val overRpm = segments.filter { it.spindleRpm > maxSpindleRpm }
        if (overRpm.isNotEmpty()) {
            spindleWithinLimit = false
            messages.add("主轴转速超过设备上限 $maxSpindleRpm rpm（${overRpm.first().segmentId} 等 ${overRpm.size} 段）")
        }

        return ToolpathValidation(
            withinTravel = withinTravel,
            noIllegalCoordinates = noIllegal,
            noAbnormalJumps = noAbnormalJumps,
            spindleWithinLimit = spindleWithinLimit,
            noStockCollision = false, // 毛坯碰撞校验未实现：明示未通过，不得恒 true
            hasApproachRetract = segments.isNotEmpty(), // 简化
            messages = messages,
            unverifiedChecks = listOf("毛坯碰撞校验", "几何过切校验")
        )
    }

    // 注：Point2D.distanceTo 已在 data class 里实现，不需要扩展
}
