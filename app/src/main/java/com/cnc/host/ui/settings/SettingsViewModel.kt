package com.cnc.host.ui.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val language: String = "zh-CN",
    val darkMode: Boolean = true,
    val showSimBadge: Boolean = true,
    val defaultPost: String = "Fanuc 0i",
    val feedPreviewIntervalMs: Long = 200,
    val enableFaultInjection: Boolean = true,
    val logRetentionDays: Int = 30
)
/** 系统设置 ViewModel。 */
class SettingsViewModel : ViewModel() {
    private val _s = MutableStateFlow(AppSettings())
    val s: StateFlow<AppSettings> = _s.asStateFlow()
    fun update(fn: (AppSettings) -> AppSettings) { _s.value = fn(_s.value) }
}
