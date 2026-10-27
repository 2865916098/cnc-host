package com.cnc.host.ui.machining

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.device.api.CommandType
import com.cnc.device.api.DeviceCommand
import com.cnc.device.api.DeviceController
import com.cnc.host.data.DeviceRepository
import com.cnc.model.common.ConnectionMode
import com.cnc.model.device.DeviceSnapshot
import com.cnc.model.drawing.Point2D
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 加工控制 ViewModel。
 *
 * 使用 SimulatedDeviceController（isSimulated = true）。
 * ⚠️ 所有返回数据均为模拟结果，不得冒充真实设备。
 */
class MachiningViewModel(
    private val repository: DeviceRepository = DeviceRepository.shared
) : ViewModel() {

    val jogStepOptions = listOf(0.1, 0.5, 1.0, 5.0)

    /** 与首页/审核页共用同一台（模拟）设备，保证状态一致。 */
    val device: DeviceController = repository.controller

    private val _snapshot = MutableStateFlow(DeviceSnapshot(
        mode = ConnectionMode.OFFLINE,
        connectionType = com.cnc.model.common.ConnectionType.NONE,
        connected = false, isSimulated = true
    ))
    val snapshot: StateFlow<DeviceSnapshot> = _snapshot.asStateFlow()

    private val _uiState = MutableStateFlow(MachiningUiState())
    val uiState: StateFlow<MachiningUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var jogJob: Job? = null

    init {
        viewModelScope.launch { connect() }
        startRefreshLoop()
    }

    suspend fun connect() {
        _uiState.update { it.copy(connecting = true, message = "连接模拟设备…") }
        device.connect()
        _uiState.update { it.copy(connecting = false, message = "已连接（模拟模式）") }
    }

    suspend fun disconnect() {
        device.disconnect()
        _uiState.update { it.copy(message = "已断开") }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                if (device.isConnected()) {
                    try {
                        _snapshot.value = device.readStatus()
                    } catch (_: Exception) {}
                }
                delay(200)
            }
        }
    }

    /** 设置 Jog 步长（UI 步长选择器绑定）。 */
    fun setStep(stepMm: Double) {
        _uiState.update { it.copy(selectedStepMm = stepMm) }
    }

    fun startJog(axis: JogAxis, direction: JogDirection, stepMm: Double) {
        jogJob?.cancel()
        if (!device.isConnected()) {
            _uiState.update { it.copy(message = "未连接设备，Jog 未执行") }
            return
        }
        jogJob = viewModelScope.launch {
            val sign = if (direction == JogDirection.POSITIVE) 1.0 else -1.0
            val curX = _snapshot.value.xActualPositionMm ?: 0.0
            val curZ = _snapshot.value.zActualPositionMm ?: 0.0
            val target = Point2D(
                x = if (axis == JogAxis.X) curX + sign * stepMm else curX,
                y = if (axis == JogAxis.Z) curZ + sign * stepMm else curZ
            )
            _uiState.update { it.copy(jogging = true, message = "Jog ${axis.label} ${direction.label} $stepMm mm") }

            val cmd = DeviceCommand.rapidMove(
                commandId = "jog-${System.currentTimeMillis()}",
                taskId = null, target = target
            )
            // sendMotionCommand 内部已等待命令队列到达终态，无需固定延时判断完成
            val result = device.sendMotionCommand(cmd)
            _uiState.update {
                it.copy(
                    jogging = false,
                    message = result.fold(
                        onSuccess = { "Jog 完成：${axis.label} ${direction.label} $stepMm mm" },
                        onFailure = { e -> "Jog 失败：${e.message ?: "未知原因"}" }
                    )
                )
            }
        }
    }

    fun emergencyStop() {
        jogJob?.cancel()
        // 模拟硬件急停：置 REAL_FAULT 并拒绝后续运动命令。
        // 真实设备上急停由硬件回路直接切断驱动，软件不承担安全功能。
        repository.simulatorOrNull()?.triggerEstop()
        _uiState.update { it.copy(jogging = false, message = "⚠ 急停触发！所有运动停止（模拟）") }
    }

    /** 急停复位（需人工确认现场安全后执行）。 */
    fun resetEmergencyStop() {
        repository.simulatorOrNull()?.resetEstop()
        _uiState.update { it.copy(message = "急停已复位，设备恢复就绪") }
    }

    fun requestControlledStop() {
        jogJob?.cancel()
        viewModelScope.launch {
            device.requestStop()
            _uiState.update { it.copy(message = "受控停止已请求") }
        }
    }

    fun setSpindle(rpm: Int, clockwise: Boolean) {
        viewModelScope.launch {
            val on = rpm > 0
            val cmd = DeviceCommand.setSpindle(
                commandId = "spindle-${System.currentTimeMillis()}",
                taskId = null, rpm = rpm, on = on, clockwise = clockwise
            )
            val result = device.sendMotionCommand(cmd)
            _uiState.update {
                it.copy(
                    message = result.fold(
                        onSuccess = {
                            if (!on) "主轴停止"
                            else "主轴 ${if (clockwise) "正转 M03" else "反转 M04"} S$rpm"
                        },
                        onFailure = { e -> "主轴命令失败：${e.message ?: "未知原因"}" }
                    )
                )
            }
        }
    }

    fun goHome() {
        viewModelScope.launch {
            _uiState.update { it.copy(message = "回原点中…") }
            val cmd = DeviceCommand(
                commandId = "home-${System.currentTimeMillis()}",
                type = CommandType.HOME
            )
            val result = device.sendMotionCommand(cmd)
            _uiState.update {
                it.copy(
                    message = result.fold(
                        onSuccess = { "回原点完成" },
                        onFailure = { e -> "回原点失败：${e.message ?: "未知原因"}" }
                    )
                )
            }
        }
    }

    fun toggleConnection(connected: Boolean) {
        viewModelScope.launch {
            if (connected) disconnect() else connect()
        }
    }

    override fun onCleared() {
        super.onCleared()
        jogJob?.cancel()
        refreshJob?.cancel()
    }
}

enum class JogAxis(val label: String) { X("X (直径)"), Z("Z (轴向)") }
enum class JogDirection(val label: String) { POSITIVE("+"), NEGATIVE("-") }

data class MachiningUiState(
    val selectedStepMm: Double = 1.0,
    val connecting: Boolean = false,
    val jogging: Boolean = false,
    val message: String = "就绪"
)
