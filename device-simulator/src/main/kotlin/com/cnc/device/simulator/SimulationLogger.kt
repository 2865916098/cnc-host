package com.cnc.device.simulator

import com.cnc.model.logging.LogCategory
import com.cnc.model.logging.LogLevel
import com.cnc.model.logging.OperationLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模拟控制卡日志记录器。
 *
 * 记录通讯日志、运行日志、报警记录和诊断导出。
 * 所有日志必须可追溯到具体的模拟操作。
 */
class SimulationLogger(
    private val maxEntries: Int = 1000
) {
    private val _logs = MutableStateFlow<List<OperationLog>>(emptyList())
    val logs: StateFlow<List<OperationLog>> = _logs.asStateFlow()

    fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        taskId: String? = null,
        details: String? = null
    ) {
        val entry = OperationLog(
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            message = message,
            taskId = taskId,
            details = details
        )
        val current = _logs.value
        val updated = if (current.size >= maxEntries) {
            (current + entry).takeLast(maxEntries)
        } else {
            current + entry
        }
        _logs.value = updated
    }

    fun debug(category: LogCategory, message: String, taskId: String? = null) =
        log(LogLevel.DEBUG, category, message, taskId)

    fun info(category: LogCategory, message: String, taskId: String? = null) =
        log(LogLevel.INFO, category, message, taskId)

    fun warn(category: LogCategory, message: String, taskId: String? = null) =
        log(LogLevel.WARN, category, message, taskId)

    fun error(category: LogCategory, message: String, taskId: String? = null) =
        log(LogLevel.ERROR, category, message, taskId)

    /** 导出全部日志为文本。 */
    fun exportAsText(): String = buildString {
        _logs.value.forEach { entry ->
            appendLine("[${entry.timestamp}] [${entry.level}] [${entry.category}] ${entry.message}")
            entry.details?.let { appendLine("  详情: $it") }
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
