package com.cnc.model.logging

/** 日志级别。 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/** 日志分类。 */
enum class LogCategory {
    COMMUNICATION,
    MOTION,
    IO,
    TASK,
    ALARM,
    SYSTEM
}

/**
 * 操作/运行日志记录。
 */
data class OperationLog(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val category: LogCategory,
    val message: String,
    val taskId: String? = null,
    val deviceSnapshotId: String? = null,
    val details: String? = null
)
