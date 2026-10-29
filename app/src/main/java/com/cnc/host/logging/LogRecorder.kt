package com.cnc.host.logging

import android.content.Context
import com.cnc.host.persistence.CncDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 操作日志记录器。
 *
 * 所有方法都是"即发即忘"型：在 IO 线程写 Room，不阻塞调用方。
 * 同一条 message 10 秒内只记录一次（防刷）。
 */
object LogRecorder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 最近一次 (category, message) 记录的时间戳，用于去重。 */
    private val recent = HashMap<String, Long>()
    private const val DEDUP_WINDOW_MS = 10_000L

    private fun keyOf(category: String, message: String) = "$category|$message"

    /** 记录一条操作日志到本地 Room，IO 线程执行。 */
    fun record(
        context: Context,
        level: String,
        category: String,
        message: String,
        taskId: String? = null,
        details: String? = null
    ) {
        val now = System.currentTimeMillis()
        val k = keyOf(category, message)
        val last = recent[k] ?: 0L
        if (now - last < DEDUP_WINDOW_MS) return
        recent[k] = now

        val entity = OperationLogEntity(
            timestamp = now,
            level = level,
            category = category,
            message = message,
            taskId = taskId,
            details = details,
            uploaded = false
        )
        scope.launch {
            runCatching {
                CncDatabase.getInstance(context).logDao().insert(entity)
            }
        }
    }

    /** 同一条 message 10 秒内只记录一次（避免心跳/状态上报刷爆）。 */
    fun recordOnce(context: Context, level: String, category: String, message: String) =
        record(context, level, category, message)

    // —— 便捷方法 ——

    fun info(context: Context, category: String, message: String, taskId: String? = null, details: String? = null) =
        record(context, "INFO", category, message, taskId, details)

    fun warn(context: Context, category: String, message: String, taskId: String? = null, details: String? = null) =
        record(context, "WARN", category, message, taskId, details)

    fun error(context: Context, category: String, message: String, taskId: String? = null, details: String? = null) =
        record(context, "ERROR", category, message, taskId, details)

    fun debug(context: Context, category: String, message: String, taskId: String? = null, details: String? = null) =
        record(context, "DEBUG", category, message, taskId, details)
}
