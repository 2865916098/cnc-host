package com.cnc.host.ui.setup

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.setup.DeviceIdentity
import com.cnc.host.setup.SetupPrefs
import com.cnc.host.sync.RegisterRequestDto
import com.cnc.host.sync.ServerAddress
import com.cnc.host.sync.ServerApi
import com.cnc.host.sync.ServerPrefs
import com.cnc.host.sync.ServerSettings
import com.cnc.host.update.AppUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设备硬件唯一标识（Android ID，重装/清数据后保持不变）。 */
fun hardwareId(context: Context): String =
    runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull().orEmpty()

data class RegisterUiState(
    val unitName: String = "",
    val contactName: String = "",
    val phone: String = "",
    /** 服务器地址：只填 IP 即可，http:// 与端口自动补全。 */
    val serverAddress: String = "",
    val loading: Boolean = false,
    /** 正在局域网内自动搜索服务器。 */
    val discovering: Boolean = false,
    val error: String? = null,
    val done: Boolean = false
)

/**
 * 首次注册：把本台设备信息上报到后台。
 *
 * 服务器地址规则：
 * - 默认沿用上次保存的地址（只有一台服务器时无需再填）
 * - 空白时自动在局域网搜索 CNC 服务端
 * - 只填 IP 即可，自动补全 http:// 前缀与 8080 端口
 *
 * 成功后写入设备身份，MainActivity 据此进入主界面。
 */
class RegisterViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(RegisterUiState())
    val ui: StateFlow<RegisterUiState> = _ui.asStateFlow()

    init {
        // 默认使用上次保存的服务器地址；首次安装时自动搜索局域网内的服务端
        val saved = SetupPrefs.load(application).serverAddress.ifBlank { ServerPrefs.load(application).baseUrl }
        _ui.update { it.copy(serverAddress = saved) }
        if (saved.isBlank()) discoverServer()
    }

    fun setUnitName(v: String) = _ui.update { it.copy(unitName = v) }
    fun setContactName(v: String) = _ui.update { it.copy(contactName = v) }
    fun setPhone(v: String) = _ui.update { it.copy(phone = v) }
    fun setServerAddress(v: String) = _ui.update { it.copy(serverAddress = v) }

    /** 在局域网内自动搜索 CNC 服务端，找到后填入地址栏。 */
    fun discoverServer() {
        if (_ui.value.discovering) return
        _ui.update { it.copy(discovering = true, error = null) }
        viewModelScope.launch {
            val found = ServerAddress.discoverOnLan(getApplication())
            _ui.update {
                if (found != null) {
                    it.copy(discovering = false, serverAddress = found)
                } else {
                    it.copy(discovering = false, error = "未在局域网内找到服务器，请手动输入服务器 IP")
                }
            }
        }
    }

    fun register() {
        val state = _ui.value
        if (state.unitName.isBlank() || state.contactName.isBlank() || state.phone.isBlank()) {
            _ui.update { it.copy(error = "请填写单位名称、联系人和电话") }
            return
        }
        // 地址规范化：只填 IP 时自动补全 http:// 与端口
        val baseUrl = ServerAddress.normalize(state.serverAddress)
        if (baseUrl.isBlank()) {
            // 未填地址：尝试自动搜索一次
            if (!state.discovering) discoverServer()
            _ui.update { it.copy(error = "正在自动搜索服务器，请稍候；也可手动输入服务器 IP") }
            return
        }
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val settings = ServerSettings(baseUrl = baseUrl)
            val req = RegisterRequestDto(
                unitName = state.unitName.trim(),
                contactName = state.contactName.trim(),
                phone = state.phone.trim(),
                serverAddress = baseUrl,
                hardwareId = hardwareId(app),
                appVersion = AppUpdate.localVersion(app).second
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
                    // 同时写入设备令牌/设备编号并启用自动同步：
                    // 此后配置同步、任务上报、状态上报都凭设备令牌直连服务端
                    ServerPrefs.save(
                        app,
                        ServerPrefs.load(app).copy(
                            baseUrl = info.serverAddress,
                            token = info.token,
                            deviceId = info.id,
                            enabled = true
                        )
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
