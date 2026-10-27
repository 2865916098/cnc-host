package com.cnc.host.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.cnc.host.persistence.cncDb
import com.cnc.host.persistence.TaskHistoryEntity
import kotlinx.coroutines.flow.Flow

enum class HistoryStatus { SUCCESS, FAILED, ABORTED }

data class TaskItem(
    val id: String, val partName: String, val planId: String,
    val status: HistoryStatus, val durationSec: Double,
    val finishedAt: Long, val operator: String
)

/** 任务历史 ViewModel（Room 持久化）。 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = application.cncDb().taskHistoryDao()
    val tasks: Flow<List<TaskHistoryEntity>> = dao.observeAll()
}

/** Entity → UI model（status 字符串 → enum）。 */
fun TaskHistoryEntity.toTaskItem() = TaskItem(
    id = id, partName = partName, planId = planId,
    status = when (status) {
        "SUCCESS" -> HistoryStatus.SUCCESS
        "FAILED" -> HistoryStatus.FAILED
        else -> HistoryStatus.ABORTED
    },
    durationSec = durationSec, finishedAt = finishedAt, operator = operator
)
