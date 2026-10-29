package com.cnc.host.logging

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** 操作日志 DAO。 */
@Dao
interface LogDao {

    @Insert
    suspend fun insertAll(logs: List<OperationLogEntity>)

    @Insert
    suspend fun insert(log: OperationLogEntity)

    /** 查询所有未上传的日志（心跳批量补传用）。 */
    @Query("SELECT * FROM operation_logs WHERE uploaded = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun queryUnsent(limit: Int = 200): List<OperationLogEntity>

    /** 标记指定 id 的日志为已上传。 */
    @Query("UPDATE operation_logs SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    /** 删除指定时间之前的日志（本地清理）。 */
    @Query("DELETE FROM operation_logs WHERE timestamp < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long): Int

    /** 总条数（调试用）。 */
    @Query("SELECT COUNT(*) FROM operation_logs")
    suspend fun count(): Int
}
