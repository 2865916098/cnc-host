package com.cnc.host.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 持久化实体。
 *
 * 镜像 core-model 数据模型，但为 SQLite 做了扁平化。
 * Version 字段由 core-model 的 VersionedEntity 同步。
 */

// ===== 零件与毛坯 =====

@Entity(tableName = "parts", indices = [Index(value = ["name"], unique = true)])
data class PartEntity(
    @PrimaryKey val id: String,
    val name: String,
    val materialId: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val version: Long = 1L
)

@Entity(tableName = "blanks", indices = [Index(value = ["partId"])])
data class BlankEntity(
    @PrimaryKey val id: String,
    val partId: String,
    val diameterMm: Double,
    val lengthMm: Double,
    val materialId: String,
    val clampType: String = "CHUCK",  // CHUCK / COLLET / CENTER
    val createdAt: Long = System.currentTimeMillis()
)

// ===== 刀具 =====

@Entity(tableName = "tools", indices = [Index(value = ["toolNumber"], unique = true)])
data class ToolEntity(
    @PrimaryKey val id: String,
    val toolNumber: Int,
    val toolType: String,     // TURNING / GROOVING / THREADING / DRILLING / CUT_OFF
    val name: String,
    val material: String,     // HSS / CARBIDE / CERAMIC
    val nominalDiameterMm: Double = 0.0,
    val lengthMm: Double = 0.0,
    val maxRpm: Int = 3000,
    val maxFeedMmPerMin: Double = 500.0,
    val wearStatus: String = "NEW",   // NEW / LIGHT / MODERATE / CRITICAL
    val usageCount: Int = 0,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ===== 材料 =====

@Entity(tableName = "materials", indices = [Index(value = ["name"], unique = true)])
data class MaterialEntity(
    @PrimaryKey val id: String,
    val name: String,
    val code: String = "",
    val density: Double = 0.0,
    val hardness: String = "",
    val recommendedCuttingSpeedMs: Double = 150.0,
    val recommendedFeedPerRev: Double = 0.2,
    val recommendedDepthMm: Double = 2.0,
    val enabled: Boolean = true,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// ===== 工艺方案 + 步骤 =====

@Entity(tableName = "process_plans")
data class ProcessPlanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val partId: String,
    val stockDiameterMm: Double = 100.0,
    val stockLengthMm: Double = 50.0,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "process_steps", indices = [Index(value = ["planId"]), Index(value = ["orderIndex"])])
data class ProcessStepEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val orderIndex: Int,
    val type: String,           // ROUGH_TURNING / FINISH_TURNING / GROOVING / THREADING / DRILLING / CUT_OFF
    val toolId: String,
    val cuttingSpeedMmPerMin: Double = 150.0,
    val spindleRpm: Int = 1200,
    val feedMmPerRev: Double = 0.2,
    val depthOfCutMm: Double = 5.0,
    val finishAllowanceMm: Double = 1.0,
    val approachDistanceMm: Double = 10.0,
    val retractDistanceMm: Double = 10.0,
    val safetyDistanceMm: Double = 10.0,
    val enabled: Boolean = true,
    val notes: String = ""
)

// ===== 图纸索引 =====

@Entity(tableName = "drawings")
data class DrawingEntity(
    @PrimaryKey val id: String,
    val filename: String,
    val filePath: String = "",     // 若从文件系统导入
    val layers: Int = 0,
    val entities: Int = 0,
    val source: String = "EMBEDDED", // EMBEDDED / FILE_IMPORT / DRAWN
    /** 导入时保存的 DXF 原文，供后续重新加载到 CAM；内置示例为空串。 */
    @ColumnInfo(defaultValue = "")
    val dxfContent: String = "",
    /** 关联的工艺方案 ID；空串表示未关联，简易模式回退默认参数。 */
    @ColumnInfo(defaultValue = "")
    val planId: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// ===== 图纸尺寸校正 =====

/** 图纸尺寸校正参数，每份图纸最多一条；CAM 读取图纸时自动套用。 */
@Entity(tableName = "drawing_corrections")
data class DrawingCorrectionEntity(
    @PrimaryKey val drawingId: String,
    val scale: Double = 1.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val rotationDeg: Double = 0.0,
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false,
    val unit: String = "MM",
    val updatedAt: Long = System.currentTimeMillis()
)

// ===== 任务历史 =====
@Entity(tableName = "task_history")
data class TaskHistoryEntity(
    @PrimaryKey val id: String,
    val partName: String,
    val planId: String,
    val status: String,     // SUCCESS / FAILED / ABORTED
    val durationSec: Double,
    val operator: String,
    val startedAt: Long,
    val finishedAt: Long
)

// ===== 同步元数据 =====

/**
 * 服务端配置同步状态（键值对）。
 *
 * 仅保存少量元信息，业务数据仍落在各自实体表；
 * 离线时这些记录用于判断“是否有可用缓存”。
 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)
