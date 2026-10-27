package com.cnc.host.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.setup.SetupPrefs
import com.cnc.host.sync.ServerApi
import com.cnc.host.sync.ServerPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KioskSettingsUiState(
    val serverAddress: String = "",
    val saving: Boolean = false,
    val message: String? = null,
    val password: String = "",
    val verifying: Boolean = false,
    val passwordError: String? = null,
    val verified: Boolean = false
)

/**
 * 设置面板（右上角设置按钮进入，需管理员密码）。
 *
 * 管理员密码由后台统一设置；联网时以后台校验为准，离线时回退到本机缓存。
 */
class KioskSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(KioskSettingsUiState())
    val ui: StateFlow<KioskSettingsUiState> = _ui.asStateFlow()

    init {
        val settings = ServerPrefs.load(application)
        _ui.update { it.copy(serverAddress = settings.baseUrl) }
    }

    fun setServerAddress(v: String) = _ui.update { it.copy(serverAddress = v) }
    fun setPassword(v: String) = _ui.update { it.copy(password = v, passwordError = null) }

    /** 校验管理员密码：优先请求后台，离线回退本机缓存。 */
    fun verifyPassword() {
        val pwd = _ui.value.password
        if (pwd.isBlank()) {
            _ui.update { it.copy(passwordError = "请输入管理员密码") }
            return
        }
        _ui.update { it.copy(verifying = true, passwordError = null) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val identity = SetupPrefs.load(app)
            val settings = ServerPrefs.load(app)
            var ok = false
            if (identity.deviceId.isNotBlank() && settings.baseUrl.isNotBlank()) {
                val remote = ServerApi(settings).verifyAdminPassword(identity.deviceId, pwd)
                ok = remote.getOrNull()?.ok == true
            }
            // 离线回退：与本机缓存的管理员密码比对
            if (!ok && identity.adminPassword.isNotBlank()) {
                ok = pwd == identity.adminPassword
            }
            if (ok) {
                if (identity.adminPassword != pwd) {
                    SetupPrefs.saveAdminPassword(app, pwd)
                }
                _ui.update { it.copy(verifying = false, verified = true, password = "") }
            } else {
                _ui.update {
                    it.copy(verifying = false, passwordError = "密码错误（离线时使用本机缓存的密码）")
                }
            }
        }
    }

    fun saveServerAddress() {
        val app = getApplication<Application>()
        val addr = _ui.value.serverAddress.trim()
        ServerPrefs.save(app, ServerPrefs.load(app).copy(baseUrl = addr))
        val identity = SetupPrefs.load(app)
        SetupPrefs.saveIdentity(app, identity.copy(serverAddress = addr))
        _ui.update { it.copy(message = "服务器地址已保存") }
    }

    /** 临时退出到安卓桌面：看门狗在窗口期内不自动拉起。 */
    fun exitToAndroid() {
        SetupPrefs.setExitWindow(getApplication(), 20)
    }
}
