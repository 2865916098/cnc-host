package com.cnc.host.logging

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地操作日志缓存表。
 *
 * 客户端在关键路径写 Room，由 DeviceHeartbeat 每 10 秒心跳顺带批量上传到服务端。
 * 上传成功后标记 uploaded=true，下次清理。
 */
@Entity(tableName = "operation_logs", indices = [Index("uploaded"), Index("timestamp")])
data class OperationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,
    val category: String,
    val message: String,
    val taskId: String? = null,
    val details: String? = null,
    val uploaded: Boolean = false
)
