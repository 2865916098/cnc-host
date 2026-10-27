package com.cnc.model.device

import com.cnc.model.common.DiameterMode
import com.cnc.model.common.UnitSystem
import com.cnc.model.common.VersionedEntity

/**
 * 机床/设备配置。
 *
 * 安全相关参数修改后，必须重新校验受影响的加工任务。
 */
data class MachineProfile(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val name: String,
    val xTravelMm: Double,
    val zTravelMm: Double,
    val unit: UnitSystem = UnitSystem.METRIC_MM,
    val xDirection: Int = 1,
    val zDirection: Int = 1,
    val diameterMode: DiameterMode = DiameterMode.DIAMETER,
    val workZeroXMm: Double = 0.0,
    val workZeroZMm: Double = 0.0,
    val maxFeedMmPerMin: Double = 5000.0,
    val maxAccelMmPerSec2: Double = 500.0,
    val softLimitXMinMm: Double = 0.0,
    val softLimitXMaxMm: Double = 0.0,
    val softLimitZMinMm: Double = 0.0,
    val softLimitZMaxMm: Double = 0.0,
    val spindleMaxRpm: Int = 3000,
    val locked: Boolean = false
) : VersionedEntity
