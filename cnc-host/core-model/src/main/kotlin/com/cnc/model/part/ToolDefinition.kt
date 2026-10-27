package com.cnc.model.part

import com.cnc.model.common.VersionedEntity

/** 刀具类型。 */
enum class ToolType {
    TURNING_RIGHT,
    TURNING_LEFT,
    GROOVING,
    THREADING,
    BORING,
    DRILLING,
    OTHER
}

/** 刀具方向（刀尖方位）。 */
enum class ToolOrientation {
    T1, T2, T3, T4, T5, T6, T7, T8
}

/** 磨损状态。 */
enum class WearState {
    NEW,
    NORMAL,
    WORN,
    REPLACE
}

/**
 * 刀具定义。
 *
 * 刀具几何、刀具方向和补偿定义必须与 CAM 算法约定一致。
 */
data class ToolDefinition(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val toolNumber: Int,
    val name: String,
    val type: ToolType,
    val noseRadiusMm: Double,
    val orientation: ToolOrientation,
    val insertGeometry: String? = null,
    val holderSize: String? = null,
    val applicableMaterialIds: List<String> = emptyList(),
    val applicableProcesses: List<String> = emptyList(),
    val recommendedCuttingSpeedMmPerMin: Double? = null,
    val recommendedFeedMmPerRev: Double? = null,
    val recommendedDepthOfCutMm: Double? = null,
    val wearState: WearState = WearState.NEW,
    val source: String? = null,
    val usageCount: Int = 0
) : VersionedEntity
