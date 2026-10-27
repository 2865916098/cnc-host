package com.cnc.model.part

import com.cnc.model.common.VersionedEntity

/**
 * 材料定义。
 *
 * 推荐值必须具有可追溯来源和适用条件。
 */
data class MaterialDefinition(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val name: String,
    val enabled: Boolean = true,
    val recommendedCuttingSpeedMmPerMin: Double? = null,
    val feedRangeMmPerRev: ClosedRange<Double>? = null,
    val depthOfCutRangeMm: ClosedRange<Double>? = null,
    val applicableToolIds: List<String> = emptyList(),
    val source: String? = null,
    val conditions: String? = null
) : VersionedEntity
