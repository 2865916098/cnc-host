package com.cnc.host.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.cnc.host.persistence.cncDb
import com.cnc.host.persistence.TaskHistoryEntity
import kotlinx.coroutines.flow.Flow

/** 任务生命周期状态（P2-③，与服务端 TASK_STATE_* 对齐）。 */
enum class HistoryStatus { PENDING_REVIEW, READY, RUNNING, SUCCESS, FAILED, ABORTED }

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

/** Entity → UI model（status 字符串 → enum，兼容旧记录只有 status 没有 state）。 */
fun TaskHistoryEntity.toTaskItem(): TaskItem {
    val effectiveState = state.takeIf { it.isNotBlank() } ?: status
    return TaskItem(
        id = id, partName = partName, planId = planId,
        status = when (effectiveState) {
            "PENDING_REVIEW" -> HistoryStatus.PENDING_REVIEW
            "READY" -> HistoryStatus.READY
            "RUNNING" -> HistoryStatus.RUNNING
            "SUCCESS" -> HistoryStatus.SUCCESS
            "FAILED" -> HistoryStatus.FAILED
            else -> HistoryStatus.ABORTED
        },
        durationSec = durationSec, finishedAt = finishedAt, operator = operator
    )
}
