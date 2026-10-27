package com.cnc.model.device

/** 报警严重级别。 */
enum class AlarmSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}

/**
 * 报警记录。
 *
 * 确认报警不等于故障已经消除。恢复前必须重新验证设备状态。
 */
data class AlarmRecord(
    val code: String,
    val source: String,
    val message: String,
    val severity: AlarmSeverity,
    val occurredAt: Long,
    val relatedTaskId: String? = null,
    val suggestion: String? = null,
    val acknowledged: Boolean = false,
    val acknowledgedAt: Long? = null,
    val cleared: Boolean = false,
    val clearedAt: Long? = null,
    val allowsResume: Boolean = false
)
