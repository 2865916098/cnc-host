package com.cnc.host.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 统一 DAO 接口。 */

@Dao
interface PartDao {
    @Query("SELECT * FROM parts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PartEntity>>

    @Query("SELECT * FROM parts ORDER BY updatedAt DESC")
    suspend fun getAll(): List<PartEntity>

    @Query("SELECT * FROM parts WHERE id = :id")
    suspend fun get(id: String): PartEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PartEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PartEntity)

    @Update
    suspend fun update(entity: PartEntity)

    @Query("DELETE FROM parts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM parts")
    suspend fun count(): Int

    /** 服务端同步：清空后整体替换。 */
    @Query("DELETE FROM parts")
    suspend fun clearAll()
}

@Dao
interface BlankDao {
    @Query("SELECT * FROM blanks WHERE partId = :partId")
    fun observeByPart(partId: String): Flow<List<BlankEntity>>

    @Query("SELECT * FROM blanks WHERE partId = :partId")
    suspend fun getByPart(partId: String): List<BlankEntity>

    @Query("SELECT * FROM blanks")
    suspend fun getAll(): List<BlankEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entities: List<BlankEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BlankEntity)

    @Update
    suspend fun update(entity: BlankEntity)

    @Query("DELETE FROM blanks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM blanks")
    suspend fun count(): Int

    @Query("DELETE FROM blanks")
    suspend fun clearAll()
}

@Dao
interface ToolDao {
    @Query("SELECT * FROM tools ORDER BY toolNumber ASC")
    fun observeAll(): Flow<List<ToolEntity>>

    @Query("SELECT * FROM tools ORDER BY toolNumber ASC")
    suspend fun getAll(): List<ToolEntity>

    @Query("SELECT * FROM tools WHERE id = :id")
    suspend fun get(id: String): ToolEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ToolEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ToolEntity)

    @Update
    suspend fun update(entity: ToolEntity)

    @Query("DELETE FROM tools WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM tools")
    suspend fun count(): Int

    @Query("DELETE FROM tools")
    suspend fun clearAll()
}

@Dao
interface MaterialDao {
    @Query("SELECT * FROM materials ORDER BY enabled DESC, name ASC")
    fun observeAll(): Flow<List<MaterialEntity>>

    @Query("SELECT * FROM materials ORDER BY enabled DESC, name ASC")
    suspend fun getAll(): List<MaterialEntity>

    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun get(id: String): MaterialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MaterialEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MaterialEntity)

    @Update
    suspend fun update(entity: MaterialEntity)

    @Query("DELETE FROM materials WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM materials")
    suspend fun count(): Int

    @Query("DELETE FROM materials")
    suspend fun clearAll()
}

@Dao
interface ProcessPlanDao {
    @Query("SELECT * FROM process_plans ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProcessPlanEntity>>

    @Query("SELECT * FROM process_plans ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ProcessPlanEntity>

    @Query("SELECT * FROM process_plans WHERE id = :id")
    suspend fun get(id: String): ProcessPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ProcessPlanEntity)

    @Update
    suspend fun update(entity: ProcessPlanEntity)

    @Query("DELETE FROM process_plans WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM process_plans")
    suspend fun count(): Int

    @Query("DELETE FROM process_plans")
    suspend fun clearAll()
}

@Dao
interface ProcessStepDao {
    @Query("SELECT * FROM process_steps WHERE planId = :planId ORDER BY orderIndex ASC")
    fun observeByPlan(planId: String): Flow<List<ProcessStepEntity>>

    @Query("SELECT * FROM process_steps WHERE planId = :planId ORDER BY orderIndex ASC")
    suspend fun getByPlan(planId: String): List<ProcessStepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entities: List<ProcessStepEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ProcessStepEntity)

    @Update
    suspend fun update(entity: ProcessStepEntity)

    @Query("DELETE FROM process_steps WHERE planId = :planId")
    suspend fun deleteByPlan(planId: String)

    @Query("DELETE FROM process_steps")
    suspend fun clearAll()
}

@Dao
interface DrawingDao {
    @Query("SELECT * FROM drawings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DrawingEntity>>

    @Query("SELECT * FROM drawings ORDER BY createdAt DESC")
    suspend fun getAll(): List<DrawingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DrawingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DrawingEntity)

    @Query("SELECT * FROM drawings WHERE id = :id")
    suspend fun getById(id: String): DrawingEntity?

    @Query("DELETE FROM drawings WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM drawings")
    suspend fun count(): Int

    /** 只清理服务端下发的图纸，保留本地导入的图纸。 */
    @Query("DELETE FROM drawings WHERE source = 'SERVER'")
    suspend fun deleteServerDrawings()
}

@Dao
interface DrawingCorrectionDao {
    @Query("SELECT * FROM drawing_corrections WHERE drawingId = :drawingId")
    suspend fun get(drawingId: String): DrawingCorrectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DrawingCorrectionEntity)

    @Query("DELETE FROM drawing_corrections WHERE drawingId = :drawingId")
    suspend fun delete(drawingId: String)

    @Query("SELECT * FROM drawing_corrections")
    suspend fun getAll(): List<DrawingCorrectionEntity>

    @Query("DELETE FROM drawing_corrections WHERE drawingId IN (SELECT id FROM drawings WHERE source = 'SERVER')")
    suspend fun deleteForServerDrawings()
}

@Dao
interface TaskHistoryDao {
    @Query("SELECT * FROM task_history ORDER BY finishedAt DESC")
    fun observeAll(): Flow<List<TaskHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TaskHistoryEntity)

    /** 仅更新状态（P2-③ 状态机迁移，如 RUNNING → SUCCESS）。 */
    @Query("UPDATE task_history SET state = :state, status = :state WHERE id = :id")
    suspend fun updateState(id: String, state: String)

    @Query("SELECT COUNT(*) FROM task_history")
    suspend fun count(): Int

    @Query("DELETE FROM task_history WHERE finishedAt < :beforeMs")
    suspend fun pruneBefore(beforeMs: Long)
}

/** 同步元数据读写。 */
@Dao
interface SyncMetaDao {
    @Query("SELECT * FROM sync_meta WHERE key = :key")
    suspend fun get(key: String): SyncMetaEntity?

    @Query("SELECT * FROM sync_meta")
    fun observeAll(): Flow<List<SyncMetaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SyncMetaEntity)
}
