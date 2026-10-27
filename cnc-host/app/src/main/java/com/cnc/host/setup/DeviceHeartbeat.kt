package com.cnc.host.setup

import android.content.Context
import com.cnc.host.sync.ClientPermissionsDto
import com.cnc.host.sync.ServerApi
import com.cnc.host.sync.ServerPrefs
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
 * 设备心跳：定期向服务端上报存活并拉取最新权限。
 *
 * 后台修改功能权限后，客户端在下一次心跳自动生效。
 */
object DeviceHeartbeat {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching {
                    val identity = SetupPrefs.load(context)
                    if (identity.deviceId.isBlank()) return@runCatching
                    val settings = ServerPrefs.load(context)
                    if (settings.baseUrl.isBlank()) return@runCatching
                    val info = ServerApi(settings).heartbeat(identity.deviceId).getOrNull()
                    if (info != null) {
                        SetupPrefs.savePermissions(context, info.permissions)
                    }
                }
                delay(30_000)
            }
        }
    }
}
