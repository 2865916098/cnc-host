package com.cnc.model.part

import com.cnc.model.common.VersionedEntity

/**
 * 零件定义。
 */
data class PartDefinition(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val name: String,
    val partNumber: String,
    val drawingVersion: Int = 1,
    val materialId: String? = null,
    val stockId: String? = null,
    val finishContourGeometryId: String? = null,
    val keyTolerances: String? = null,
    val notes: String? = null
) : VersionedEntity

/** 装夹方式。 */
enum class ClampingMethod {
    CHUCK,
    COLLET,
    MANDREL,
    OTHER
}

/**
 * 毛坯定义。
 *
 * 所有涉及真实工件装夹和机床坐标的信息，都必须来源明确并经过确认。
 */
data class StockDefinition(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val diameterMm: Double,
    val lengthMm: Double,
    val clampingMethod: ClampingMethod = ClampingMethod.CHUCK,
    val clampingLengthMm: Double = 0.0,
    val workZeroXMm: Double = 0.0,
    val workZeroZMm: Double = 0.0,
    val machiningAllowanceMm: Double = 0.0,
    val forbiddenZones: String? = null
) : VersionedEntity
