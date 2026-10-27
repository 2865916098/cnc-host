package com.cnc.host.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.setup.DeviceIdentity
import com.cnc.host.setup.SetupPrefs
import com.cnc.host.sync.RegisterRequestDto
import com.cnc.host.sync.ServerApi
import com.cnc.host.sync.ServerPrefs
import com.cnc.host.sync.ServerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RegisterUiState(
    val unitName: String = "",
    val contactName: String = "",
    val phone: String = "",
    val serverAddress: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val done: Boolean = false
)

/**
 * 首次注册：把本台设备信息上报到后台。
 *
 * 成功后写入设备身份，MainActivity 据此进入主界面。
 */
class RegisterViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(RegisterUiState())
    val ui: StateFlow<RegisterUiState> = _ui.asStateFlow()

    fun setUnitName(v: String) = _ui.update { it.copy(unitName = v) }
    fun setContactName(v: String) = _ui.update { it.copy(contactName = v) }
    fun setPhone(v: String) = _ui.update { it.copy(phone = v) }
    fun setServerAddress(v: String) = _ui.update { it.copy(serverAddress = v) }

    fun register() {
        val state = _ui.value
        if (state.unitName.isBlank() || state.contactName.isBlank() ||
            state.phone.isBlank() || state.serverAddress.isBlank()
        ) {
            _ui.update { it.copy(error = "请填写全部信息") }
            return
        }
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val settings = ServerSettings(baseUrl = state.serverAddress.trim())
            val req = RegisterRequestDto(
                unitName = state.unitName.trim(),
                contactName = state.contactName.trim(),
                phone = state.phone.trim(),
                serverAddress = settings.baseUrl
            )
            val result = ServerApi(settings).registerDevice(req)
            result.fold(
                onSuccess = { info ->
                    // 注册成功：同时写入设备身份与服务端地址（供后续配置同步使用）
                    SetupPrefs.saveIdentity(
                        app,
                        DeviceIdentity(
                            setupCompleted = true,
                            deviceId = info.id,
                            unitName = info.unitName,
                            contactName = info.contactName,
                            phone = info.phone,
                            serverAddress = info.serverAddress
                        )
                    )
                    SetupPrefs.savePermissions(app, info.permissions)
                    ServerPrefs.save(
                        app,
                        ServerPrefs.load(app).copy(baseUrl = info.serverAddress)
                    )
                    _ui.update { it.copy(loading = false, done = true) }
                },
                onFailure = { e ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            error = e.message ?: "注册失败，请检查服务器地址与网络"
                        )
                    }
                }
            )
        }
    }
}
