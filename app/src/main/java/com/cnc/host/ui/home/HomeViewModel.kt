package com.cnc.host.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.data.DeviceRepository
import com.cnc.model.device.DeviceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel。
 *
 * 负责设备连接状态和实时状态展示。
 */
class HomeViewModel(
    private val repository: DeviceRepository = DeviceRepository.shared
) : ViewModel() {

    private val _snapshot = MutableStateFlow(repository.controller.let {
        DeviceSnapshot(
            mode = it.connectionMode,
            connectionType = com.cnc.model.common.ConnectionType.NONE,
            connected = false,
            isSimulated = it.isSimulated
        )
    })
    val snapshot: StateFlow<DeviceSnapshot> = _snapshot.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    init {
        viewModelScope.launch {
            repository.statusFlow().collect { _snapshot.value = it }
        }
    }

    fun connect() {
        if (_connecting.value) return
        _connecting.value = true
        viewModelScope.launch {
            repository.connect()
            _connecting.value = false
        }
    }

    fun disconnect() {
        viewModelScope.launch { repository.disconnect() }
    }
}
