package com.cnc.host.setup

import android.content.Context
import com.cnc.host.sync.ClientPermissionsDto
import com.google.gson.Gson

/**
 * 设备注册与首次启动配置。
 *
 * 与 ServerPrefs 分工：
 * - ServerPrefs：服务端配置同步的连接参数（管理口令、设备编号等）
 * - SetupPrefs：设备注册信息、首次启动是否完成、缓存的管理员密码与权限
 *
 * 注册成功后 setupCompleted = true，MainActivity 才进入主界面。
 */
data class DeviceIdentity(
    val setupCompleted: Boolean = false,
    val deviceId: String = "",
    val unitName: String = "",
    val contactName: String = "",
    val phone: String = "",
    val serverAddress: String = "",
    /** 缓存的管理员密码，用于离线校验；联网时以后台为准。 */
    val adminPassword: String = "",
    val permissions: ClientPermissionsDto = ClientPermissionsDto()
)

object SetupPrefs {

    private const val FILE = "cnc_setup_prefs"
    private const val K_SETUP_DONE = "setup_completed"
    private const val K_DEVICE_ID = "device_id"
    private const val K_UNIT = "unit_name"
    private const val K_CONTACT = "contact_name"
    private const val K_PHONE = "phone"
    private const val K_ADDR = "server_address"
    private const val K_ADMIN_PWD = "admin_password"
    private const val K_PERMS = "permissions_json"

    private val gson = Gson()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): DeviceIdentity {
        val p = prefs(context)
        val perms = p.getString(K_PERMS, null)?.let {
            runCatching { gson.fromJson(it, ClientPermissionsDto::class.java) }.getOrDefault(ClientPermissionsDto())
        } ?: ClientPermissionsDto()
        return DeviceIdentity(
            setupCompleted = p.getBoolean(K_SETUP_DONE, false),
            deviceId = p.getString(K_DEVICE_ID, "") ?: "",
            unitName = p.getString(K_UNIT, "") ?: "",
            contactName = p.getString(K_CONTACT, "") ?: "",
            phone = p.getString(K_PHONE, "") ?: "",
            serverAddress = p.getString(K_ADDR, "") ?: "",
            adminPassword = p.getString(K_ADMIN_PWD, "") ?: "",
            permissions = perms
        )
    }

    fun saveIdentity(context: Context, identity: DeviceIdentity) {
        prefs(context).edit()
            .putBoolean(K_SETUP_DONE, identity.setupCompleted)
            .putString(K_DEVICE_ID, identity.deviceId)
            .putString(K_UNIT, identity.unitName)
            .putString(K_CONTACT, identity.contactName)
            .putString(K_PHONE, identity.phone)
            .putString(K_ADDR, identity.serverAddress)
            .apply()
    }

    fun savePermissions(context: Context, permissions: ClientPermissionsDto) {
        prefs(context).edit().putString(K_PERMS, gson.toJson(permissions)).apply()
    }

    fun saveAdminPassword(context: Context, password: String) {
        prefs(context).edit().putString(K_ADMIN_PWD, password).apply()
    }

    // ===== 临时退出窗口（退出到安卓界面后，看门狗在这段时间内不自动拉起）=====

    private const val K_EXIT_UNTIL = "kiosk_exit_until"

    fun setExitWindow(context: Context, seconds: Int) {
        prefs(context).edit()
            .putLong(K_EXIT_UNTIL, System.currentTimeMillis() + seconds * 1000L)
            .apply()
    }

    fun exitUntil(context: Context): Long =
        prefs(context).getLong(K_EXIT_UNTIL, 0L)
}
