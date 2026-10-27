package com.cnc.model.task

import com.cnc.model.common.TaskStatus
import com.cnc.model.common.VersionedEntity

/** 审核项状态。 */
enum class ReviewItemStatus {
    PASS,
    FAIL,
    NOT_CHECKED
}

/** 加工前审核项。 */
data class ReviewItem(
    val key: String,
    val label: String,
    val status: ReviewItemStatus,
    val reason: String? = null
)

/**
 * 生产任务。
 *
 * 每个加工任务必须绑定图纸、几何、工艺、刀路、刀具、设备和 IO 流程的版本。
 * 历史任务应保留当时使用的配置快照，便于追溯。
 */
data class ProductionTask(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val taskNumber: String,
    val status: TaskStatus = TaskStatus.DRAFT,
    val partId: String,
    val drawingId: String,
    val drawingVersion: Int,
    val geometryId: String? = null,
    val geometryVersion: Int? = null,
    val processPlanId: String,
    val processPlanVersion: Int,
    val toolpathId: String? = null,
    val toolpathVersion: Int? = null,
    val toolIds: List<String>,
    val machineProfileId: String,
    val machineProfileVersion: Int,
    val ioMappingVersion: Int = 1,
    val reviewItems: List<ReviewItem> = emptyList(),
    val reviewPassed: Boolean = false,
    val reviewedBy: String? = null,
    val reviewedAt: Long? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val result: String? = null,
    val abortReason: String? = null,
    val resumeRecords: List<String> = emptyList()
) : VersionedEntity
