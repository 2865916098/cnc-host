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

    /** 出厂默认设置密码；服务端可在后台修改，联网校验后缓存到本机。 */
    const val DEFAULT_SETTINGS_PASSWORD = "123456"

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

    /**
     * 清除注册信息（设置里的「清除数据重新注册」）。
     *
     * 只清本机注册状态，服务端仍保留该设备记录——因此重新注册时会被拒绝，
     * 需管理员在后台「注销」后才能重新注册。
     */
    fun clearRegistration(context: Context) {
        prefs(context).edit()
            .remove(K_SETUP_DONE)
            .remove(K_DEVICE_ID)
            .remove(K_UNIT)
            .remove(K_CONTACT)
            .remove(K_PHONE)
            .remove(K_ADDR)
            .remove(K_PERMS)
            .apply()
    }

    // ===== 彻底退出标记（「退出软件」后看门狗不再自动拉起，手动打开软件时复位）=====

    private const val K_EXITED = "kiosk_exited"

    fun setExited(context: Context, exited: Boolean) {
        prefs(context).edit().putBoolean(K_EXITED, exited).apply()
    }

    fun isExited(context: Context): Boolean =
        prefs(context).getBoolean(K_EXITED, false)
}
