package com.cnc.host.ui.simple

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.data.DeviceRepository
import com.cnc.host.simple.SimpleRunController
import com.cnc.host.sync.SyncManager
import com.cnc.model.device.AlarmSeverity
import com.cnc.model.device.DeviceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 报警条目（设备报警 + 本机确认状态）。 */
data class SimpleAlarmItem(
    val code: String,
    val message: String,
    val severity: AlarmSeverity,
    val occurredAt: Long,
    val acknowledged: Boolean,
    val source: String
)

/**
 * 报警与状态页。
 *
 * 报警来自设备控制器（模拟模式下为模拟报警）；确认只在本机记录，
 * 不代表故障已消除，也不代表设备侧报警已清除。
 */
class SimpleAlarmsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DeviceRepository.shared

    val syncState = SyncManager.state
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

    private val _alarms = MutableStateFlow<List<SimpleAlarmItem>>(emptyList())
    val alarms: StateFlow<List<SimpleAlarmItem>> = _alarms.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    /** 本机确认过的报警代码（进程内；不写回设备）。 */
    private val localAcknowledged = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            _snapshot.value = runCatching { repository.readStatus() }.getOrDefault(_snapshot.value)
            refreshAlarms()
            repository.statusFlow().collect {
                _snapshot.value = it
                refreshAlarms()
            }
        }
        viewModelScope.launch {
            SyncManager.refresh(getApplication())
            _pendingCount.value = SyncManager.pendingCount(getApplication())
        }
    }

    private suspend fun refreshAlarms() {
        val list = runCatching { repository.controller.readAlarms() }.getOrDefault(emptyList())
        _alarms.value = list.map { alarm ->
            SimpleAlarmItem(
                code = alarm.code,
                message = alarm.message,
                severity = alarm.severity,
                occurredAt = alarm.occurredAt,
                acknowledged = alarm.acknowledged || localAcknowledged.contains(alarm.code),
                source = alarm.source
            )
        }
    }

    /** 本机确认：模拟器同时记录确认；真实设备仅平板本地记录。 */
    fun acknowledge(code: String) {
        repository.simulatorOrNull()?.acknowledgeAlarm(code)
        localAcknowledged.add(code)
        viewModelScope.launch { refreshAlarms() }
    }

    fun acknowledgeAll() {
        val codes = _alarms.value.filter { !it.acknowledged }.map { it.code }
        codes.forEach { code ->
            repository.simulatorOrNull()?.acknowledgeAlarm(code)
            localAcknowledged.add(code)
        }
        viewModelScope.launch { refreshAlarms() }
    }
}
