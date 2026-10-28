package com.cnc.host.ui.setup

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.setup.CHINA_PROVINCES
import com.cnc.host.setup.DeviceIdentity
import com.cnc.host.setup.SetupPrefs
import com.cnc.host.setup.WifiSetup
import com.cnc.host.sync.ApiException
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
import java.io.IOException

/** 设备硬件唯一标识（Android ID，重装/清数据后保持不变）。 */
fun hardwareId(context: Context): String =
    runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull().orEmpty()

/** 电话只允许 10–13 位纯数字。 */
private val PHONE_REGEX = Regex("^\\d{10,13}$")

/** 无任何历史/检测到的服务器时预填的默认服务器地址（对应 http://192.168.1.100:8080）。 */
private const val DEFAULT_SERVER_IP = "192.168.1.100"

/** 激活表单各字段的校验错误，null 表示该字段通过。 */
data class RegisterFieldErrors(
    val province: String? = null,
    val city: String? = null,
    val unitName: String? = null,
    val contactName: String? = null,
    val phone: String? = null
) {
    val hasError: Boolean
        get() = province != null || city != null || unitName != null ||
            contactName != null || phone != null
}

data class RegisterUiState(
    val province: String = "",
    val city: String = "",
    val unitName: String = "",
    val contactName: String = "",
    val phone: String = "",
    /** 服务器地址：只填 IP 即可，http:// 与端口自动补全。 */
    val serverAddress: String = "",
    val loading: Boolean = false,
    /** 正在局域网内自动搜索服务器。 */
    val discovering: Boolean = false,
    val error: String? = null,
    /** 点击激活时未联网：界面据此展示「去配置网络」入口。 */
    val networkRequired: Boolean = false,
    val fieldErrors: RegisterFieldErrors = RegisterFieldErrors(),
    val done: Boolean = false
)

/**
 * 首次激活：把本台设备的归属地、单位与联系人信息上报到后台登记。
 *
 * 服务器地址规则：
 * - 默认沿用上次保存的地址（只有一台服务器时无需再填）
 * - 空白时自动在局域网搜索 CNC 服务端
 * - 只填 IP 即可，自动补全 http:// 前缀与 8080 端口
 *
 * 激活必须联网：未联网时直接拦截，不发起请求。
 * 成功后写入设备身份，MainActivity 据此进入主界面。
 */
class RegisterViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(RegisterUiState())
    val ui: StateFlow<RegisterUiState> = _ui.asStateFlow()

    init {
        // 默认使用上次保存的服务器地址；没有历史地址时预填默认服务器地址，
        // 同时继续在局域网内自动搜索服务端，搜到后覆盖默认值
        val saved = SetupPrefs.load(application).serverAddress.ifBlank { ServerPrefs.load(application).baseUrl }
        _ui.update { it.copy(serverAddress = saved.ifBlank { DEFAULT_SERVER_IP }) }
        if (saved.isBlank()) discoverServer()
    }

    /** 选择省份：切换省份时清空不属于该省的城市。 */
    fun setProvince(v: String) {
        _ui.update { state ->
            val cities = CHINA_PROVINCES.firstOrNull { it.name == v }?.cities.orEmpty()
            state.copy(
                province = v,
                city = if (state.city in cities) state.city else "",
                fieldErrors = state.fieldErrors.copy(province = null, city = null)
            )
        }
    }

    fun setCity(v: String) {
        _ui.update { it.copy(city = v, fieldErrors = it.fieldErrors.copy(city = null)) }
    }

    fun setUnitName(v: String) {
        _ui.update { it.copy(unitName = v, fieldErrors = it.fieldErrors.copy(unitName = null)) }
    }

    fun setContactName(v: String) {
        _ui.update { it.copy(contactName = v, fieldErrors = it.fieldErrors.copy(contactName = null)) }
    }

    fun setPhone(v: String) {
        _ui.update { it.copy(phone = v, fieldErrors = it.fieldErrors.copy(phone = null)) }
    }

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
                } else if (it.serverAddress.isBlank()) {
                    it.copy(discovering = false, error = "未在局域网内找到服务器，请手动输入服务器 IP")
                } else {
                    // 已有默认/手填地址：搜索失败不打扰，沿用现有地址
                    it.copy(discovering = false)
                }
            }
        }
    }

    /** 点击激活时网络不可用：不发起请求，提示先联网并提供「去配置网络」入口。 */
    fun onNetworkUnavailable() {
        _ui.update {
            it.copy(
                loading = false,
                error = "网络尚未连接，请先连接 WiFi 或移动数据",
                networkRequired = true
            )
        }
    }

    /** 网络恢复后清除联网提示，避免与页面顶部的绿色状态条冲突。 */
    fun onNetworkRestored() {
        _ui.update {
            if (it.networkRequired) it.copy(networkRequired = false, error = null) else it
        }
    }

    /** 校验表单并提交激活请求。 */
    fun activate() {
        val app = getApplication<Application>()
        // 联网前置校验：未联网直接拦截，不发起任何请求
        if (!WifiSetup.isOnline(app)) {
            onNetworkUnavailable()
            return
        }

        val state = _ui.value
        val errors = validate(state)
        if (errors.hasError) {
            _ui.update { it.copy(fieldErrors = errors, error = null) }
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

        _ui.update { it.copy(loading = true, error = null, networkRequired = false) }
        viewModelScope.launch {
            val req = RegisterRequestDto(
                unitName = state.unitName.trim(),
                contactName = state.contactName.trim(),
                phone = state.phone.trim(),
                province = state.province,
                city = state.city,
                serverAddress = baseUrl,
                hardwareId = hardwareId(app),
                appVersion = AppUpdate.localVersion(app).second
            )
            val result = ServerApi(ServerSettings(baseUrl = baseUrl)).registerDevice(req)
            result.fold(
                onSuccess = { info ->
                    // 激活成功：同时写入设备身份与服务端地址（供后续配置同步使用）
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
                    _ui.update { it.copy(loading = false, error = activationErrorMessage(e)) }
                }
            )
        }
    }

    /** 表单校验：省份、城市必选；单位名称非空且不超过 100 字；联系人非空；电话为 10–13 位数字。 */
    private fun validate(state: RegisterUiState): RegisterFieldErrors {
        val unitName = state.unitName.trim()
        val contactName = state.contactName.trim()
        val phone = state.phone.trim()
        return RegisterFieldErrors(
            province = if (state.province.isBlank()) "请选择省份" else null,
            city = if (state.city.isBlank()) "请选择城市" else null,
            unitName = when {
                unitName.isBlank() -> "请填写单位名称"
                unitName.length > 100 -> "单位名称不能超过 100 个字"
                else -> null
            },
            contactName = if (contactName.isBlank()) "请填写联系人" else null,
            phone = when {
                phone.isBlank() -> "请填写电话"
                !PHONE_REGEX.matches(phone) -> "电话需填写 10–13 位数字"
                else -> null
            }
        )
    }

    /** 把失败原因转换成便于操作工理解的提示：网络异常与服务端消息分开处理。 */
    private fun activationErrorMessage(e: Throwable): String = when {
        // 服务端已改为幂等激活；仍收到 409 时给出人工处理指引
        e is ApiException && e.code == 409 -> "该设备已在后台登记，请联系管理员处理"
        e is ApiException -> e.message ?: "激活失败，请检查服务器地址与网络"
        e is IOException -> "网络请求失败，请检查网络是否连通、服务器地址是否正确"
        else -> e.message ?: "激活失败，请稍后重试"
    }
}
