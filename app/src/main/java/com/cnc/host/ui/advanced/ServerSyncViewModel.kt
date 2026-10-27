package com.cnc.host.ui.advanced

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.persistence.CncDatabase
import com.cnc.host.sync.DeviceConfigDto
import com.cnc.host.sync.ServerPrefs
import com.cnc.host.sync.ServerSettings
import com.cnc.host.sync.SyncManager
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 服务端同步设置页状态。 */
data class ServerSyncUiState(
    val baseUrl: String = "",
    val token: String = "",
    val deviceId: String = "tablet-1",
    val pin: String = "1234",
    val enabled: Boolean = true,
    val testing: Boolean = false,
    val syncing: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val online: Boolean = false,
    val configured: Boolean = false,
    val cachedVersion: Long = 0L,
    val lastSyncAt: Long? = null,
    val pendingCount: Int = 0,
    val drawingsCount: Int = 0,
    val toolsCount: Int = 0,
    val materialsCount: Int = 0,
    val plansCount: Int = 0,
    val historyCount: Int = 0,
    val deviceSummary: List<Pair<String, String>> = emptyList(),
    val commSummary: List<Pair<String, String>> = emptyList()
)

/**
 * 服务端同步设置。
 *
 * 平板只缓存服务端下发的配置，不做业务参数编辑；
 * 服务端离线时继续使用本地缓存，任务记录排队补传。
 */
class ServerSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val gson = Gson()
    private val db = CncDatabase.getInstance(application)

    private val _ui = MutableStateFlow(ServerSyncUiState())
    val ui: StateFlow<ServerSyncUiState> = _ui.asStateFlow()

    init {
        val settings = ServerPrefs.load(application)
        _ui.value = _ui.value.copy(
            baseUrl = settings.baseUrl,
            token = settings.token,
            deviceId = settings.deviceId,
            pin = settings.advancedPin,
            enabled = settings.enabled
        )
        viewModelScope.launch {
            refreshLocal()
            SyncManager.refresh(application)
        }
        viewModelScope.launch {
            SyncManager.state.collect { sync ->
                _ui.update {
                    it.copy(
                        online = sync.online,
                        configured = sync.configured,
                        cachedVersion = sync.cachedVersion,
                        lastSyncAt = sync.lastSyncAt,
                        syncing = sync.syncing,
                        error = sync.lastError,
                        message = if (sync.lastMessage.isBlank()) it.message else sync.lastMessage
                    )
                }
            }
        }
    }

    fun setBaseUrl(value: String) = _ui.update { it.copy(baseUrl = value) }
    fun setToken(value: String) = _ui.update { it.copy(token = value) }
    fun setDeviceId(value: String) = _ui.update { it.copy(deviceId = value) }
    fun setPin(value: String) = _ui.update { it.copy(pin = value.filter(Char::isDigit).take(8)) }
    fun setEnabled(value: Boolean) = _ui.update { it.copy(enabled = value) }

    fun save() {
        val settings = currentSettings()
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = null) }
            SyncManager.saveSettings(getApplication(), settings)
            _ui.update { it.copy(saving = false, message = "设置已保存", configured = settings.configured) }
        }
    }

    fun testConnection() {
        val settings = currentSettings()
        viewModelScope.launch {
            _ui.update { it.copy(testing = true, error = null, message = "正在测试连接…") }
            val result = SyncManager.testConnection(getApplication(), settings)
            _ui.update {
                it.copy(
                    testing = false,
                    message = result.getOrNull() ?: it.message,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun syncNow() {
        val settings = currentSettings()
        viewModelScope.launch {
            _ui.update { it.copy(error = null) }
            SyncManager.saveSettings(getApplication(), settings)
            val result = SyncManager.syncNow(getApplication(), settings)
            refreshLocal()
            _ui.update {
                it.copy(
                    message = SyncManager.state.value.lastMessage,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshLocal()
            SyncManager.refresh(getApplication())
        }
    }

    private fun currentSettings() = ServerSettings(
        baseUrl = _ui.value.baseUrl.trim(),
        token = _ui.value.token.trim(),
        deviceId = _ui.value.deviceId.trim().ifBlank { "tablet-1" },
        enabled = _ui.value.enabled,
        advancedPin = _ui.value.pin.ifBlank { "1234" }
    )

    private suspend fun refreshLocal() {
        val app = getApplication<Application>()
        val settings = ServerPrefs.load(app)
        val drawings = db.drawingDao().count()
        val tools = db.toolDao().count()
        val materials = db.materialDao().count()
        val plans = db.processPlanDao().count()
        val history = db.taskHistoryDao().count()
        val pending = SyncManager.pendingCount(app)

        val device = runCatching {
            ServerPrefs.deviceJson(app)?.let { gson.fromJson(it, DeviceConfigDto::class.java) }
        }.getOrNull()
        val deviceSummary = device?.let {
            listOf(
                "设备名称" to it.name,
                "控制卡" to it.controlCard,
                "模拟模式" to if (it.useSimulator) "是（模拟数据）" else "否（真实设备）",
                "X 行程" to "${it.maxTravelXmm} mm",
                "Z 行程" to "${it.maxTravelZmm} mm",
                "最高转速" to "${it.maxRpm} rpm"
            )
        }.orEmpty()
        val commSummary = device?.comm?.let {
            listOf(
                "协议" to it.protocol,
                "IP 地址" to it.ipAddress,
                "端口" to it.port.toString(),
                "串口" to it.serialPort,
                "波特率" to it.baudRate.toString(),
                "站号" to it.deviceId.toString()
            )
        }.orEmpty()

        _ui.update {
            it.copy(
                baseUrl = it.baseUrl.ifBlank { settings.baseUrl },
                token = it.token.ifBlank { settings.token },
                deviceId = settings.deviceId,
                pin = settings.advancedPin,
                enabled = settings.enabled,
                configured = settings.configured,
                drawingsCount = drawings,
                toolsCount = tools,
                materialsCount = materials,
                plansCount = plans,
                historyCount = history,
                pendingCount = pending,
                deviceSummary = deviceSummary,
                commSummary = commSummary
            )
        }
    }
}
