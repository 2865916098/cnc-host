package com.cnc.host.setup

import android.content.Context
import com.cnc.host.sync.ClientPermissionsDto
import com.cnc.host.sync.ServerApi
import com.cnc.host.sync.ServerPrefs
import com.cnc.host.sync.SyncManager
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
                runCatching {
                    val settings = ServerPrefs.load(context)
                    if (settings.baseUrl.isBlank()) return@runCatching
                    // 心跳顺带上报版本号，并检查是否有新版本可更新
                    val (_, versionName) = AppUpdate.localVersion(context)
                    val identity = SetupPrefs.load(context)
                    if (identity.deviceId.isNotBlank()) {
                        val api = ServerApi(settings)
                        val info = api.heartbeat(identity.deviceId, versionName).getOrNull()
                        if (info != null) {
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
                        } else {
                            SyncManager.markOnline(false)
                        }
                    }
                    AppUpdate.check(context)
                }
                delay(INTERVAL_MS)
            }
        }
    }
}
