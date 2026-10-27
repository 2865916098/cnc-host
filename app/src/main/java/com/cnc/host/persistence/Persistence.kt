package com.cnc.host.persistence

import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * 首次启动自动注入种子数据。
 *
 * 判断标准：part 表 count = 0 → 注入全部种子。
 * 后续启动时若 count > 0 → 跳过。
 * 显式写死的 id → 多次调用幂等（INSERT REPLACE）。
 */
fun ensureSeeded(db: CncDatabase) {
    runBlocking {
        if (db.partDao().count() == 0) {
            db.partDao().insertAll(SeedData.parts)
            db.blankDao().insert(SeedData.blanks)
            db.toolDao().insertAll(SeedData.tools)
            db.materialDao().insertAll(SeedData.materials)
            SeedData.processPlans.forEach { db.processPlanDao().insert(it) }
            db.processStepDao().insert(SeedData.processSteps)
            db.drawingDao().insertAll(SeedData.drawings)
            SeedData.taskHistory.forEach { db.taskHistoryDao().insert(it) }
        }
    }
}

/** 获取单例 DB。 */
fun Context.cncDb(): CncDatabase = CncDatabase.getInstance(this)
