package com.cnc.model.cam

import com.cnc.model.common.VersionedEntity
import com.cnc.model.drawing.Point2D

/** 运动类型。根据算法实现能力确定。 */
enum class MotionType {
    RAPID,
    LINEAR_FEED,
    ARC_FEED
}

/** 校验结果。 */
data class ToolpathValidation(
    val withinTravel: Boolean,
    val noIllegalCoordinates: Boolean,
    val noAbnormalJumps: Boolean,
    val noOvercut: Boolean,
    val noStockCollision: Boolean,
    val hasApproachRetract: Boolean,
    val messages: List<String>
)

/**
 * 刀路轨迹段。
 *
 * 与具体控制卡无关的统一刀路模型。
 */
data class ToolpathSegment(
    val segmentId: String,
    val motionType: MotionType,
    val start: Point2D,
    val end: Point2D,
    val center: Point2D? = null,
    val radius: Double? = null,
    val clockwise: Boolean? = null,
    val feedMmPerMin: Double = 0.0,
    val spindleRpm: Int = 0,
    val processType: ProcessType,
    val toolNumber: Int,
    val safetyAttribute: String? = null,
    val processParamVersion: Int = 1
)

/**
 * 刀路模型。
 *
 * 仿真必须使用与最终加工任务相同的刀路版本。
 * 修改工艺参数或设备配置后，必须使依赖旧参数的刀路和审核结果失效。
 */
data class Toolpath(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val processPlanId: String,
    val processPlanVersion: Int,
    val machineProfileVersion: Int,
    val segments: List<ToolpathSegment>,
    val validation: ToolpathValidation,
    val valid: Boolean
) : VersionedEntity
