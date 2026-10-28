package com.cnc.host.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.logging.LogRecorder
import com.cnc.host.service.KioskService
import com.cnc.host.setup.SetupPrefs
import com.cnc.host.sync.ServerAddress
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

    /** 校验管理员密码：联网以后台为准；离线回退本机缓存；无缓存时用出厂默认 123456。 */
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
            var ok: Boolean? = null
            if (identity.deviceId.isNotBlank() && settings.baseUrl.isNotBlank()) {
                val remote = ServerApi(settings).verifyAdminPassword(identity.deviceId, pwd)
                // 请求成功（无论对错）以后台结果为准；网络失败才回退本地
                remote.getOrNull()?.let { ok = it.ok }
            }
            if (ok == null) {
                // 离线：先比本机缓存，再无缓存比出厂默认密码
                ok = when {
                    identity.adminPassword.isNotBlank() -> pwd == identity.adminPassword
                    else -> pwd == SetupPrefs.DEFAULT_SETTINGS_PASSWORD
                }
            }
            if (ok == true) {
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
        // 只填 IP 时自动补全 http:// 与端口 8080
        val addr = ServerAddress.normalize(_ui.value.serverAddress)
        if (addr.isBlank()) {
            _ui.update { it.copy(message = "请输入服务器地址（只填 IP 即可）") }
            return
        }
        ServerPrefs.save(app, ServerPrefs.load(app).copy(baseUrl = addr))
        val identity = SetupPrefs.load(app)
        SetupPrefs.saveIdentity(app, identity.copy(serverAddress = addr))
        _ui.update { it.copy(serverAddress = addr, message = "服务器地址已保存：$addr") }
    }

    /**
     * 彻底退出软件：打退出标记（看门狗不再拉起）、停止保活服务。
     * 之后由 MainActivity finishAndRemoveTask() 收尾。
     */
    fun exitApp() {
        val app = getApplication<Application>()
        SetupPrefs.setExited(app, true)
        runCatching { KioskService.stop(app) }
    }

    /**
     * 清除数据重新注册：清掉本机注册信息与设备令牌后，直接前台重启主界面，
     * 回到「配网 → 权限 → 注册」流程。
     *
     * 注意：不能用 AlarmManager+杀进程重启——Android 12+/MIUI 会拦截后台
     * 启动 Activity 导致重启丢失。当前应用在前台，直接 startActivity 可靠。
     * 服务端仍保留该设备记录，重新注册会被拒绝，需管理员先在后台「注销」。
     */
    fun resetRegistration() {
        val app = getApplication<Application>()
        LogRecorder.info(app, "SYSTEM", "用户触发清除数据，准备重新注册")
        SetupPrefs.clearRegistration(app)
        ServerPrefs.clearAuth(app)
        SetupPrefs.setExited(app, false)
        val intent = android.content.Intent(app, com.cnc.host.MainActivity::class.java).addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        app.startActivity(intent)
    }
}
