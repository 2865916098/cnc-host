package com.cnc.host.sync

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 服务端返回非 2xx 时抛出，便于 UI 区分鉴权失败与网络失败。 */
class ApiException(val code: Int, message: String) : IOException(message)

/**
 * 服务端 REST 客户端（HttpURLConnection 实现，不引入额外网络库）。
 *
 * 所有方法都在 IO 线程执行；失败时返回 Result.failure，
 * 调用方据此提示“离线，继续使用本地缓存”。
 */
class ServerApi(private val settings: ServerSettings) {

    private val gson = Gson()

    fun normalizedBaseUrl(): String? {
        var u = settings.baseUrl.trim()
        if (u.isEmpty()) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u.trimEnd('/')
    }

    suspend fun checkToken(): Result<Unit> = runCatching {
        request("GET", "/api/v1/auth/check")
        Unit
    }

    suspend fun fetchConfig(): Result<ConfigDto> = runCatching {
        gson.fromJson(request("GET", "/api/v1/config"), ConfigDto::class.java)
    }

    suspend fun uploadTask(task: TaskDto): Result<Unit> = runCatching {
        request("POST", "/api/v1/tasks", gson.toJson(task))
        Unit
    }

    suspend fun postStatus(status: StatusDto): Result<Unit> = runCatching {
        request("POST", "/api/v1/status", gson.toJson(status))
        Unit
    }

    // ===== 设备注册与管理员校验（部分为开放接口，无需管理口令）=====

    suspend fun registerDevice(req: RegisterRequestDto): Result<DeviceInfoDto> = runCatching {
        gson.fromJson(request("POST", "/api/v1/devices/register", gson.toJson(req)), DeviceInfoDto::class.java)
    }

    suspend fun getDevice(deviceId: String): Result<DeviceInfoDto> = runCatching {
        gson.fromJson(request("GET", "/api/v1/devices/$deviceId"), DeviceInfoDto::class.java)
    }

    suspend fun heartbeat(deviceId: String, appVersion: String = ""): Result<DeviceInfoDto> = runCatching {
        gson.fromJson(
            request("POST", "/api/v1/devices/$deviceId/heartbeat", gson.toJson(HeartbeatRequestDto(appVersion))),
            DeviceInfoDto::class.java
        )
    }

    /** 查询服务端发布的最新客户端版本；未发布返回 null。 */
    suspend fun appVersion(): Result<AppVersionDto?> = runCatching {
        try {
            gson.fromJson(request("GET", "/api/v1/app-version"), AppVersionDto::class.java)
        } catch (e: ApiException) {
            if (e.code == 404) null else throw e
        }
    }

    /** 下载最新 APK 到目标文件，progress 回调已下载字节数。 */
    suspend fun downloadApk(target: File, progress: (downloaded: Long, total: Long) -> Unit): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) {
                val base = normalizedBaseUrl() ?: throw IOException("未配置服务端地址")
                val conn = (URL(base + "/api/v1/app-download").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 30000
                }
                try {
                    if (conn.responseCode !in 200..299) throw ApiException(conn.responseCode, "下载失败 HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    target.parentFile?.mkdirs()
                    conn.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var downloaded = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                downloaded += n
                                progress(downloaded, total)
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }

    suspend fun verifyAdminPassword(deviceId: String, password: String): Result<SimpleOkDto> = runCatching {
        gson.fromJson(
            request("POST", "/api/v1/devices/$deviceId/verify-admin", gson.toJson(AdminVerifyRequestDto(password))),
            SimpleOkDto::class.java
        )
    }

    private suspend fun request(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val base = normalizedBaseUrl()
                ?: throw IOException("未配置服务端地址")
            val url = URL(base + path)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 6000
                readTimeout = 12000
                // 注册、心跳、管理员校验等开放接口允许空口令；其它接口必须带 Bearer Token
                if (settings.token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${settings.token}")
                }
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    // 优先取服务端 JSON 里的 message 字段（如「设备已注册，如需重新注册请联系管理员」）
                    val serverMsg = runCatching {
                        gson.fromJson(text, SimpleOkDto::class.java)?.message?.takeIf { it.isNotBlank() }
                    }.getOrNull()
                    throw ApiException(code, serverMsg ?: "服务端返回 HTTP $code")
                }
                text
            } finally {
                conn.disconnect()
            }
        }
}
