package com.cnc.model.cam

import com.cnc.model.common.VersionedEntity
import com.cnc.model.drawing.Point2D

/** 运动类型。根据算法实现能力确定。 */
enum class MotionType {
    RAPID,
    LINEAR_FEED,
    ARC_FEED
}

/**
 * 校验结果（独立验证器输出，规范 §3.3）。
 *
 * 字段语义必须诚实：未实现的校验项不得宣称通过，必须在 [unverifiedChecks] 中明示。
 */
data class ToolpathValidation(
    /** X/Z 坐标均未超设备行程（真实行程校验）。 */
    val withinTravel: Boolean,
    /** 无 NaN/无穷坐标。 */
    val noIllegalCoordinates: Boolean,
    /** 相邻段首尾连续，无异常跳变。 */
    val noAbnormalJumps: Boolean,
    /** 主轴转速未超设备上限（原名 noOvercut，名不符实已更正；几何过切校验见 [unverifiedChecks]）。 */
    val spindleWithinLimit: Boolean,
    /** 毛坯碰撞校验通过。该校验未实现时恒 false，并在 [unverifiedChecks] 明示。 */
    val noStockCollision: Boolean,
    val hasApproachRetract: Boolean,
    val messages: List<String>,
    /** 未执行的校验项清单（如碰撞/过切），供 UI 向操作员明示，不参与通过判定。 */
    val unverifiedChecks: List<String> = emptyList()
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
