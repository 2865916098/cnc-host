package com.cnc.host.ui.simple

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.data.DeviceRepository
import com.cnc.host.simple.SimpleRunController
import com.cnc.host.sync.SyncManager
import com.cnc.model.device.DeviceSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 运行监控。
 *
 * 状态全部来自 SimpleRunController（作业执行器）与设备状态流，
 * 页面只负责展示和转发停止/急停/复位动作，不参与运动控制决策。
 */
class SimpleMonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DeviceRepository.shared

    val runState = SimpleRunController.state

    private val _snapshot = MutableStateFlow(
        DeviceSnapshot(
            mode = repository.controller.connectionMode,
            connectionType = com.cnc.model.common.ConnectionType.NONE,
            connected = repository.controller.isConnected(),
            isSimulated = repository.controller.isSimulated
        )
    )
    val snapshot: StateFlow<DeviceSnapshot> = _snapshot.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _elapsedSec = MutableStateFlow(0L)
    val elapsedSec: StateFlow<Long> = _elapsedSec.asStateFlow()

    init {
        viewModelScope.launch {
            _snapshot.value = runCatching { repository.readStatus() }.getOrDefault(_snapshot.value)
            repository.statusFlow().collect { _snapshot.value = it }
        }
        viewModelScope.launch {
            SyncManager.refresh(getApplication())
            _pendingCount.value = SyncManager.pendingCount(getApplication())
        }
        // 仅用于界面计时展示，不参与任何完成判定。
        viewModelScope.launch {
            while (isActive) {
                val state = SimpleRunController.state.value
                _elapsedSec.value = when {
                    state.startedAt <= 0L -> 0L
                    state.finishedAt != null -> (state.finishedAt - state.startedAt) / 1000
                    else -> (System.currentTimeMillis() - state.startedAt) / 1000
                }
                delay(1_000)
            }
        }
    }

    fun requestStop() = SimpleRunController.requestStop()

    fun emergencyStop() = SimpleRunController.emergencyStop()

    fun resetEstop() = SimpleRunController.resetEstop()

    fun clearResult() = SimpleRunController.reset()
}
