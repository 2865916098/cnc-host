package com.cnc.model.drawing

import com.cnc.model.common.UnitSystem
import com.cnc.model.common.VersionedEntity

/** 图纸文件格式。 */
enum class DrawingFormat {
    DXF,
    PDF_VECTOR,
    PDF_RASTER,
    JPG,
    PNG,
    UNKNOWN
}

/** 解析状态。 */
enum class ParseStatus {
    PENDING,
    PARSING,
    SUCCESS,
    PARTIAL,
    FAILED
}

/** 尺寸来源。关键尺寸必须标注来源。 */
enum class DimensionSource {
    VECTOR_ENTITY,
    OCR,
    GEOMETRIC_INFERENCE,
    MANUAL
}

/**
 * 图纸文档。
 *
 * 删除已关联加工任务的图纸时，必须进行引用检查。
 */
data class DrawingDocument(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val fileName: String,
    val format: DrawingFormat,
    val fileSizeBytes: Long,
    val parseStatus: ParseStatus = ParseStatus.PENDING,
    val confidence: Double = 0.0,
    val unit: UnitSystem? = null,
    val partNumber: String? = null,
    val parseMessage: String? = null
) : VersionedEntity
