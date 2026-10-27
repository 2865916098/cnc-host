package com.cnc.host.sync

import android.content.Context

/**
 * 服务端连接与高级入口设置（SharedPreferences）。
 *
 * 只保存连接参数和本地缓存 JSON，业务配置本身落在 Room；
 * 这样离线时仍能读取上次同步的配置继续加工。
 */
data class ServerSettings(
    val baseUrl: String = "",
    val token: String = "",
    val deviceId: String = "tablet-1",
    val enabled: Boolean = false,
    /** 高级功能 PIN，默认 1234；仅用于防误触，不是安全边界。 */
    val advancedPin: String = "1234"
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
}

object ServerPrefs {

    private const val FILE = "cnc_server_prefs"
    private const val K_BASE_URL = "base_url"
    private const val K_TOKEN = "token"
    private const val K_DEVICE_ID = "device_id"
    private const val K_ENABLED = "enabled"
    private const val K_PIN = "advanced_pin"
    private const val K_DEVICE_JSON = "device_config_json"
    private const val K_COMM_JSON = "comm_config_json"
    private const val K_PENDING_TASKS = "pending_tasks"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): ServerSettings {
        val p = prefs(context)
        return ServerSettings(
            baseUrl = p.getString(K_BASE_URL, "") ?: "",
            token = p.getString(K_TOKEN, "") ?: "",
            deviceId = p.getString(K_DEVICE_ID, "tablet-1") ?: "tablet-1",
            enabled = p.getBoolean(K_ENABLED, false),
            advancedPin = p.getString(K_PIN, "1234") ?: "1234"
        )
    }

    fun save(context: Context, settings: ServerSettings) {
        prefs(context).edit()
            .putString(K_BASE_URL, settings.baseUrl.trim())
            .putString(K_TOKEN, settings.token.trim())
            .putString(K_DEVICE_ID, settings.deviceId.trim())
            .putBoolean(K_ENABLED, settings.enabled)
            .putString(K_PIN, settings.advancedPin.ifBlank { "1234" })
            .apply()
    }

    /** 服务端下发的设备配置 JSON（原样缓存，供高级页面展示）。 */
    fun saveDeviceJson(context: Context, deviceJson: String, commJson: String) {
        prefs(context).edit()
            .putString(K_DEVICE_JSON, deviceJson)
            .putString(K_COMM_JSON, commJson)
            .apply()
    }

    fun deviceJson(context: Context): String? = prefs(context).getString(K_DEVICE_JSON, null)
    fun commJson(context: Context): String? = prefs(context).getString(K_COMM_JSON, null)

    fun savePendingTasks(context: Context, json: String) {
        prefs(context).edit().putString(K_PENDING_TASKS, json).apply()
    }

    fun pendingTasks(context: Context): String? = prefs(context).getString(K_PENDING_TASKS, null)
}
