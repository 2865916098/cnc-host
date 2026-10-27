package com.cnc.host.ui.io

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.device.simulator.DeviceSimulatorConfig
import com.cnc.device.simulator.SimulatedDeviceController
import com.cnc.device.api.DeviceController
import com.cnc.model.device.DeviceSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * IO 监控 ViewModel。
 *
 * 8 输入 / 8 输出实时监控 + 手动开关输出。
 * 输入约定：1=夹紧反馈, 2=送料到位, 3=气压正常, 4=安全门, 5=急停反馈
 * 输出约定：1=夹紧电磁阀, 2=送料电磁阀
 */
class IoViewModel : ViewModel() {

    val inputs = listOf(
        1 to "夹紧反馈", 2 to "送料到位", 3 to "气压正常", 4 to "安全门",
        5 to "急停反馈", 6 to "预留", 7 to "预留", 8 to "预留"
    )
    val outputs = listOf(
        1 to "夹紧电磁阀", 2 to "送料电磁阀", 3 to "冷却液泵", 4 to "预留",
        5 to "预留", 6 to "预留", 7 to "预留", 8 to "预留"
    )

    private val device: DeviceController = SimulatedDeviceController(
        config = DeviceSimulatorConfig(xTravelMm = 300.0, zTravelMm = 500.0)
    )

    private val _inputsState = MutableStateFlow(mapOf(1 to false, 2 to false, 3 to true, 4 to true, 5 to false))
    val inputsState: StateFlow<Map<Int, Boolean>> = _inputsState.asStateFlow()

    private val _outputsState = MutableStateFlow(mapOf(1 to false, 2 to false, 3 to false))
    val outputsState: StateFlow<Map<Int, Boolean>> = _outputsState.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch { device.connect() }
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (device.isConnected()) {
                    try {
                        _inputsState.value = device.readInputs()
                    } catch (_: Exception) {}
                }
                delay(200)
            }
        }
    }

    fun setOutput(index: Int, value: Boolean) {
        viewModelScope.launch {
            try {
                val newMap = _outputsState.value.toMutableMap().apply { put(index, value) }
                device.setOutputs(newMap)
                _outputsState.value = newMap
            } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
