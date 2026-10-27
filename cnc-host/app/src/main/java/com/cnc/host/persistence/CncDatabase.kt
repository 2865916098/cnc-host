package com.cnc.host.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * CNC_HOST Room Database（单例）。
 *
 * 版本策略：
 *   - schema 变化 → version + 1 + Migration
 *   - 首次安装 → Room 自动建表
 *   - 数据变更（加种子数据）→ 在 firstRun 判断里做，不触发表结构 Migration
 *
 * 注意：minSdk = 24 → Room 可用（Room 要求 minSdk ≥ 21）。
 */
@Database(
    entities = [
        PartEntity::class,
        BlankEntity::class,
        ToolEntity::class,
        MaterialEntity::class,
        ProcessPlanEntity::class,
        ProcessStepEntity::class,
        DrawingEntity::class,
        DrawingCorrectionEntity::class,
        TaskHistoryEntity::class,
        SyncMetaEntity::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class CncDatabase : RoomDatabase() {

    abstract fun partDao(): PartDao
    abstract fun blankDao(): BlankDao
    abstract fun toolDao(): ToolDao
    abstract fun materialDao(): MaterialDao
    abstract fun processPlanDao(): ProcessPlanDao
    abstract fun processStepDao(): ProcessStepDao
    abstract fun drawingDao(): DrawingDao
    abstract fun drawingCorrectionDao(): DrawingCorrectionDao
    abstract fun taskHistoryDao(): TaskHistoryDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        const val DB_NAME = "cnc_host.db"

        /** v1 → v2：drawings 表新增 dxfContent（保存 DXF 原文）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drawings ADD COLUMN dxfContent TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 → v3：新增 drawing_corrections 表（尺寸校正参数）。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drawing_corrections` (" +
                        "`drawingId` TEXT NOT NULL, " +
                        "`scale` REAL NOT NULL, " +
                        "`offsetX` REAL NOT NULL, " +
                        "`offsetY` REAL NOT NULL, " +
                        "`rotationDeg` REAL NOT NULL, " +
                        "`mirrorX` INTEGER NOT NULL, " +
                        "`mirrorY` INTEGER NOT NULL, " +
                        "`unit` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`drawingId`))"
                )
            }
        }

        /** v3 → v4：新增 sync_meta 表（服务端配置同步元数据）。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_meta` (" +
                        "`key` TEXT NOT NULL, " +
                        "`value` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`key`))"
                )
            }
        }

        /** v4 → v5：drawings 表新增 planId（关联工艺方案，简易模式读取切削参数）。 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drawings ADD COLUMN planId TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: CncDatabase? = null

        fun getInstance(context: Context): CncDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): CncDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                CncDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .allowMainThreadQueries()           // 开发期允许（ViewModel 里先读）；生产改用 suspend/Flow
                .build()
        }
    }
}
