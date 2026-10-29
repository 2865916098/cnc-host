package com.cnc.host.sync

import android.content.Context
import com.cnc.host.persistence.CncDatabase
import com.cnc.host.persistence.TaskHistoryEntity
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 同步状态（UI 展示用）。 */
data class SyncState(
    val configured: Boolean = false,
    val syncing: Boolean = false,
    val online: Boolean = false,
    val cachedVersion: Long = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val lastMessage: String = "",
    /** 待补传任务数（P2-④ 弱网/离线提示）。 */
    val pendingCount: Int = 0
)

data class SyncOutcome(val version: Long, val changed: Boolean)

/**
 * 服务端配置同步中心。
 *
 * - 联网：拉取配置，版本变化时覆盖本地 Room
 * - 离线：保留本地缓存继续加工，任务记录排队，联网后补传
 * - 急停/限位/运动下发完全不经过这里，服务端离线不影响安全逻辑
 */
object SyncManager {

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val gson = Gson()

    suspend fun refresh(context: Context) {
        val settings = ServerPrefs.load(context)
        val metaDao = CncDatabase.getInstance(context).syncMetaDao()
        val version = metaDao.get("config_version")?.value?.toLongOrNull() ?: 0L
        val lastAt = metaDao.get("last_sync_at")?.value?.toLongOrNull()
        _state.update {
            it.copy(
                configured = settings.configured,
                cachedVersion = version,
                lastSyncAt = lastAt,
                pendingCount = pendingCount(context)
            )
        }
    }

    suspend fun saveSettings(context: Context, settings: ServerSettings) {
        ServerPrefs.save(context, settings)
        _state.update { it.copy(configured = settings.configured, lastMessage = "设置已保存") }
    }

    /** 心跳结果联动在线状态（任务页徽标实时刷新），不触碰其它字段。 */
    fun markOnline(online: Boolean) {
        _state.update { it.copy(online = online) }
    }

    /**
     * 轻量探测远端配置版本是否与本地缓存不同。
     * 未配置鉴权或网络失败时返回 false（不触发同步）。
     */
    suspend fun remoteConfigChanged(context: Context): Boolean {
        val conf = ServerPrefs.load(context)
        if (!conf.configured) return false
        val remote = ServerApi(conf).fetchConfigVersion().getOrNull() ?: return false
        val cached = CncDatabase.getInstance(context).syncMetaDao()
            .get("config_version")?.value?.toLongOrNull() ?: 0L
        return remote.version != cached
    }

    /** 仅验证地址与口令，不写配置。 */
    suspend fun testConnection(context: Context, settings: ServerSettings): Result<String> {
        val api = ServerApi(settings)
        return api.checkToken().fold(
            onSuccess = {
                _state.update {
                    it.copy(online = true, configured = settings.configured, lastError = null, lastMessage = "连接成功")
                }
                Result.success("连接成功：${api.normalizedBaseUrl()}")
            },
            onFailure = { e ->
                val message = describeError(e)
                _state.update {
                    it.copy(online = false, configured = settings.configured, lastError = message, lastMessage = "连接失败")
                }
                Result.failure(Exception(message, e))
            }
        )
    }

    /** 立即同步；settings 为空时使用本地保存的设置。 */
    suspend fun syncNow(context: Context, settings: ServerSettings? = null): Result<SyncOutcome> {
        mutex.withLock {
            val conf = settings ?: ServerPrefs.load(context)
            if (!conf.configured) {
                val message = "未配置服务端地址或管理口令"
                _state.update { it.copy(configured = false, syncing = false, lastError = message, lastMessage = "未同步") }
                return Result.failure(IllegalStateException(message))
            }
            _state.update {
                it.copy(configured = true, syncing = true, lastError = null, lastMessage = "正在同步…")
            }

            val api = ServerApi(conf)
            val remote = api.fetchConfig()
            if (remote.isFailure) {
                val message = describeError(remote.exceptionOrNull())
                _state.update {
                    it.copy(
                        syncing = false,
                        online = false,
                        lastError = message,
                        lastMessage = "同步失败，继续使用本地缓存",
                        pendingCount = pendingCount(context)
                    )
                }
                return Result.failure(Exception(message, remote.exceptionOrNull()))
            }

            val config = remote.getOrThrow()
            val db = CncDatabase.getInstance(context)
            val cachedVersion = db.syncMetaDao().get("config_version")?.value?.toLongOrNull() ?: 0L
            val changed = config.version != cachedVersion || cachedVersion == 0L

            if (changed) {
                val applied = ConfigSyncer.apply(context, config)
                if (applied.isFailure) {
                    val message = applied.exceptionOrNull()?.message ?: "写入本地缓存失败"
                    _state.update {
                        it.copy(syncing = false, lastError = message, lastMessage = "同步失败，继续使用本地缓存")
                    }
                    return Result.failure(Exception(message, applied.exceptionOrNull()))
                }
                flushPendingTasksLocked(context, api)
            }

            val flushed = if (changed) 0 else {
                flushPendingTasksLocked(context, api)
            }
            if (!changed) {
                db.syncMetaDao().put(
                    com.cnc.host.persistence.SyncMetaEntity("last_sync_at", System.currentTimeMillis().toString())
                )
            }

            _state.update {
                it.copy(
                    syncing = false,
                    online = true,
                    configured = true,
                    cachedVersion = config.version,
                    lastSyncAt = System.currentTimeMillis(),
                    lastError = null,
                    lastMessage = when {
                        changed -> "已同步到配置版本 v${config.version}"
                        flushed > 0 -> "配置无变化，已补传 $flushed 条任务"
                        else -> "配置已是最新版本 v${config.version}"
                    },
                    pendingCount = pendingCount(context)
                )
            }
            return Result.success(SyncOutcome(config.version, changed))
        }
    }

    /** 应用启动时调用：已启用则自动同步，失败静默（状态在 UI 可见）。 */
    suspend fun syncIfConfigured(context: Context) {
        refresh(context)
        val settings = ServerPrefs.load(context)
        if (settings.enabled && settings.configured) {
            syncNow(context, settings)
        }
    }

    /**
     * 上传一条任务记录。
     * 离线或失败时写入待补传队列，不阻塞加工流程。
     */
    suspend fun uploadTask(context: Context, entity: TaskHistoryEntity, operator: String) {
        val task = TaskDto(
            id = entity.id,
            partName = entity.partName,
            planId = entity.planId,
            status = entity.state,
            state = entity.state,
            durationSec = entity.durationSec,
            operator = operator,
            startedAt = entity.startedAt,
            finishedAt = entity.finishedAt
        )
        mutex.withLock {
            val settings = ServerPrefs.load(context)
            if (!settings.configured) {
                enqueueLocked(context, task)
                return@withLock
            }
            val api = ServerApi(settings)
            val result = api.uploadTask(task)
            if (result.isFailure) {
                enqueueLocked(context, task)
            } else {
                flushPendingTasksLocked(context, api)
            }
            // 刷新待补传计数（P2-④ 弱网/离线提示）
            _state.update { it.copy(pendingCount = pendingCount(context)) }
        }
    }

    /** 上报实时状态；失败静默，仅用于后台监控。 */
    suspend fun postStatus(context: Context, status: StatusDto) {
        val settings = ServerPrefs.load(context)
        if (!settings.configured) return
        val withDevice = status.copy(deviceId = settings.deviceId)
        ServerApi(settings).postStatus(withDevice)
    }

    /** 待补传任务数。 */
    fun pendingCount(context: Context): Int = readPending(context).size

    private suspend fun flushPendingTasksLocked(context: Context, api: ServerApi): Int {
        val pending = readPending(context)
        if (pending.isEmpty()) return 0
        var sent = 0
        val remain = mutableListOf<TaskDto>()
        for (task in pending) {
            val r = api.uploadTask(task)
            if (r.isSuccess) sent++ else remain.add(task)
        }
        writePending(context, remain)
        return sent
    }

    private fun enqueueLocked(context: Context, task: TaskDto) {
        val pending = readPending(context)
        if (pending.none { it.id == task.id }) pending.add(task)
        writePending(context, pending)
    }

    private fun readPending(context: Context): MutableList<TaskDto> {
        val raw = ServerPrefs.pendingTasks(context) ?: return mutableListOf()
        return runCatching {
            gson.fromJson(raw, Array<TaskDto>::class.java)?.toMutableList() ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    private fun writePending(context: Context, list: List<TaskDto>) {
        val trimmed = list.takeLast(100)
        ServerPrefs.savePendingTasks(context, gson.toJson(trimmed))
    }

    private fun describeError(e: Throwable?): String {
        val ex = e ?: return "未知错误"
        return when (ex) {
            is ApiException -> when (ex.code) {
                401 -> "管理口令无效，请在服务端后台核对 CNC_ADMIN_TOKEN"
                404 -> "接口不存在，请确认服务端版本"
                else -> ex.message ?: "服务端错误"
            }
            else -> ex.message?.takeIf { it.isNotBlank() }
                ?: "网络不可达，请检查地址与网络（离线时继续使用本地缓存）"
        }
    }
}
