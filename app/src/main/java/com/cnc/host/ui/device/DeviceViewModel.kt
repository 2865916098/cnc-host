package com.cnc.host.ui.device

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ControlCard { FANUC_0I, SIEMENS_840D, MAZAK_SMART, HASS_VF, MODBUS_GENERIC }
data class DeviceConfig(
    val card: ControlCard = ControlCard.FANUC_0I,
    val name: String = "数控车床 #1",
    val maxTravelXmm: Double = 300.0,
    val maxTravelZmm: Double = 500.0,
    val maxRpm: Int = 3000,
    val maxFeedMmPerMin: Double = 5000.0,
    val useSimulator: Boolean = true
)
/** 控制卡配置 ViewModel。 */
class DeviceViewModel : ViewModel() {
    private val _cfg = MutableStateFlow(DeviceConfig())
    val cfg: StateFlow<DeviceConfig> = _cfg.asStateFlow()
    fun update(fn: (DeviceConfig) -> DeviceConfig) { _cfg.value = fn(_cfg.value) }
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()
    fun save() { _saved.value = true }
}
