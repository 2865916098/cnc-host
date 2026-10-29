package com.cnc.host

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.os.Process
import com.cnc.host.persistence.cncDb
import com.cnc.host.persistence.ensureSeeded
import com.cnc.host.service.KioskService
import com.cnc.host.setup.DeviceHeartbeat
import com.cnc.host.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * CNC_HOST Application 类。
 *
 * 职责：
 * - 初始化 Room Database 与种子数据
 * - 启动保活服务与设备心跳
 * - 已配置服务端时后台拉起一次配置同步
 * - 崩溃后自动重启（看门狗）
 */
class CncApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val db = cncDb()
        ensureSeeded(db)

        // 保活 + 心跳（激活后才会真正上报）。
        // 保活服务只在前台启动，避免后台进程起前台服务被系统判定超时崩溃
        KioskService.startIfForeground(this)
        DeviceHeartbeat.start(this)

        // 启动同步失败不阻塞界面；离线继续使用上次缓存
        appScope.launch {
            runCatching { SyncManager.syncIfConfigured(this@CncApplication) }
        }

        installCrashRestartHandler()
    }

    /**
     * 未捕获异常时，通过 AlarmManager 在 1.5 秒后重启主界面，
     * 实现「闪退自动重新进入」。
     */
    private fun installCrashRestartHandler() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            try {
                val am = getSystemService(ALARM_SERVICE) as AlarmManager
                am.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1500L,
                    pi
                )
            } catch (_: Throwable) {
            }
            Process.killProcess(Process.myPid())
        }
    }
}
