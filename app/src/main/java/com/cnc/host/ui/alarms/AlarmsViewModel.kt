package com.cnc.host.ui.alarms

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 报警中心 ViewModel（种子模拟数据）。
 */
class AlarmsViewModel : ViewModel() {

    data class AlarmItem(
        val id: String,
        val code: String,
        val message: String,
        val severity: Severity,
        val active: Boolean,
        val timestamp: Long
    )

    enum class Severity { INFO, WARNING, ERROR, CRITICAL }

    private val _alarms = MutableStateFlow(seed())
    val alarms: StateFlow<List<AlarmItem>> = _alarms.asStateFlow()

    fun acknowledge(id: String) {
        _alarms.update { list -> list.map { if (it.id == id) it.copy(active = false) else it } }
    }

    fun clearAll() {
        _alarms.update { list -> list.map { it.copy(active = false) } }
    }

    companion object {
        private fun seed() = listOf(
            AlarmItem("a1", "E-001", "急停触发", Severity.CRITICAL, active = false, timestamp = System.currentTimeMillis() - 3600000),
            AlarmItem("a2", "W-017", "刀具 T3 磨损超限", Severity.WARNING, active = true, timestamp = System.currentTimeMillis() - 600000),
            AlarmItem("a3", "I-004", "气压 0.48 MPa (正常范围下限)", Severity.INFO, active = true, timestamp = System.currentTimeMillis() - 120000),
            AlarmItem("a4", "E-023", "X 轴过行程警告", Severity.ERROR, active = false, timestamp = System.currentTimeMillis() - 7200000)
        )
    }
}
