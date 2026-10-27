package com.cnc.host.ui.simple

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.persistence.DrawingEntity
import com.cnc.host.persistence.cncDb
import com.cnc.host.simple.SimpleTaskSession
import com.cnc.host.sync.SyncManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 简易模式任务选择。
 *
 * 任务来源 = Room 图纸库（服务端同步 + 本地导入）。
 */
class SimpleTasksViewModel(application: Application) : AndroidViewModel(application) {

    private val drawingDao = application.cncDb().drawingDao()

    val drawings: StateFlow<List<DrawingEntity>> = drawingDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncState = SyncManager.state

    init {
        viewModelScope.launch { SyncManager.refresh(application) }
    }

    fun select(drawing: DrawingEntity) {
        SimpleTaskSession.select(drawing)
    }

    fun syncNow() {
        viewModelScope.launch { SyncManager.syncNow(getApplication()) }
    }
}
