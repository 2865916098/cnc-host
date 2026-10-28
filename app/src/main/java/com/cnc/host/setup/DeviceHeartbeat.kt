package com.cnc.host.setup

import android.content.Context
import com.cnc.host.logging.LogRecorder
import com.cnc.host.logging.OperationLogEntity
import com.cnc.host.persistence.CncDatabase
import com.cnc.host.sync.ClientPermissionsDto
import com.cnc.host.sync.OperationLogDto
import com.cnc.host.sync.ServerApi
import com.cnc.host.sync.ServerPrefs
import com.cnc.host.sync.SyncManager
import com.cnc.host.sync.UploadLogsRequestDto
import com.cnc.host.update.AppUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 全局权限持有者，供 UI 实时响应后台权限变更。 */
object PermissionsHolder {
    private val _flow = MutableStateFlow(ClientPermissionsDto())
    val flow: StateFlow<ClientPermissionsDto> = _flow

    fun load(context: Context) {
        _flow.value = SetupPrefs.load(context).permissions
    }

    fun update(permissions: ClientPermissionsDto) {
        _flow.value = permissions
    }
}

/**
 * 设备心跳：每 10 秒向服务端上报存活并拉取最新权限。
 *
 * - 后台修改功能权限后，客户端 10 秒内自动生效
 * - 心跳响应携带设备令牌：老版本客户端本地无令牌时自动补录（自愈）
 * - 顺带探测配置版本号，变化即触发全量同步，任务页徽标实时反映在线状态
 */
object DeviceHeartbeat {

    private const val INTERVAL_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val settings = ServerPrefs.load(context)
                if (settings.baseUrl.isBlank()) {
                    delay(INTERVAL_MS)
                    continue
                }
                val (_, versionName) = AppUpdate.localVersion(context)
                val identity = SetupPrefs.load(context)
                if (identity.deviceId.isNotBlank()) {
                    val api = ServerApi(settings)
                    val heartbeatResult = runCatching { api.heartbeat(identity.deviceId, versionName) }
                    heartbeatResult.onSuccess { result ->
                        val info = result.getOrNull()
                        if (info != null) {
                            // 心跳成功
                            SetupPrefs.savePermissions(context, info.permissions)
                            PermissionsHolder.update(info.permissions)
                            // 老设备自愈：本地还没有令牌时，用心跳返回的令牌补齐鉴权
                            if (settings.token.isBlank() && info.token.isNotBlank()) {
                                ServerPrefs.save(
                                    context,
                                    settings.copy(token = info.token, deviceId = info.id, enabled = true)
                                )
                            }
                            SyncManager.refresh(context)
                            SyncManager.markOnline(true)
                            // 轻量探测配置版本，变化才全量同步（避免每 10 秒拉全量）
                            if (SyncManager.remoteConfigChanged(context)) {
                                SyncManager.syncNow(context)
                            }
                            // 心跳成功后补传本地缓存的操作日志
                            flushLogs(context, api, identity.deviceId)
                        } else {
                            SyncManager.markOnline(false)
                            LogRecorder.debug(context, "COMMUNICATION", "心跳无响应")
                        }
                    }.onFailure { e ->
                        val msg = e.message ?: "心跳失败"
                        // 404：服务端找不到该设备（可能已被后台注销）
                        if (msg.contains("404") || msg.contains("不存在") || msg.contains("已注销")) {
                            LogRecorder.warn(context, "COMMUNICATION", "心跳 404，设备可能已被注销，需重新注册：$msg")
                        } else {
                            LogRecorder.debug(context, "COMMUNICATION", "心跳异常：$msg")
                        }
                        SyncManager.markOnline(false)
                    }
                }
                runCatching { AppUpdate.check(context) }
                delay(INTERVAL_MS)
            }
        }
    }

    /**
     * 批量补传本地未上传的日志。失败时保留本地不动，下一轮继续。
     */
    private suspend fun flushLogs(context: Context, api: ServerApi, deviceId: String) {
        runCatching {
            val unsent = CncDatabase.getInstance(context).logDao().queryUnsent(limit = 200)
            if (unsent.isEmpty()) return@runCatching
            val dtos = unsent.map { e: OperationLogEntity ->
                OperationLogDto(
                    timestamp = e.timestamp,
                    level = e.level,
                    category = e.category,
                    message = e.message,
                    taskId = e.taskId,
                    details = e.details
                )
            }
            val req = UploadLogsRequestDto(deviceId = deviceId, logs = dtos)
            val result = api.uploadLogs(req)
            result.getOrNull()?.let {
                // 上传成功，标记为已上传
                CncDatabase.getInstance(context).logDao().markUploaded(unsent.map { it.id })
            }
        }
    }
}
