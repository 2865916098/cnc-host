package com.cnc.model.cam

import com.cnc.model.common.VersionedEntity

/** 工序类型。 */
enum class ProcessType {
    ROUGH_TURNING,
    FINISH_TURNING,
    GROOVING,
    THREADING,
    DRILLING,
    CUT_OFF
}

/**
 * 工艺方案。
 *
 * 当材料、刀具或机床能力未知时，不允许凭空生成看似精确的生产参数。
 * 参数必须经过范围校验。
 */
data class ProcessPlan(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val partId: String,
    val materialId: String,
    val toolIds: List<String>,
    val steps: List<ProcessStep>,
    val estimatedTimeSec: Double = 0.0,
    val source: String? = null
) : VersionedEntity

/** 单个工艺步骤。 */
data class ProcessStep(
    val id: String,
    val type: ProcessType,
    val toolId: String,
    val cuttingSpeedMmPerMin: Double,
    val spindleRpm: Int,
    val feedMmPerRev: Double,
    val depthOfCutMm: Double,
    val finishAllowanceMm: Double,
    val approachDistanceMm: Double,
    val retractDistanceMm: Double,
    val safetyDistanceMm: Double,
    val valid: Boolean,
    val validationMessage: String? = null
)
