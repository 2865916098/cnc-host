package com.cnc.host.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cnc.host.MainActivity
import com.cnc.host.R
import com.cnc.host.setup.SetupPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台保活服务（看门狗）。
 *
 * 职责：
 * - 作为前台服务降低被系统杀死的概率
 * - 定时检测主界面是否在前台；被切到后台或崩溃后自动重新拉起
 * - 尊重「退出到安卓界面」的临时窗口，窗口内不自动拉起
 *
 * 注意：这是软件级保活，无法阻止用户强制停止或系统 OOM；
 * 配合 BOOT_COMPLETED 接收器实现开机自启。
 */
class KioskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdog: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        watchdog?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startWatchdog() {
        watchdog = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                if (MainActivity.isResumed) continue
                val exitUntil = SetupPrefs.exitUntil(this@KioskService)
                if (System.currentTimeMillis() < exitUntil) continue
                relaunch()
            }
        }
    }

    private fun relaunch() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        runCatching { startActivity(intent) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "CNC 保活", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持加工软件在前台运行" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CNC Host 运行中")
            .setContentText("加工软件保活，请勿关闭")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "cnc_kiosk"
        private const val NOTIF_ID = 1001
        private const val CHECK_INTERVAL_MS = 1500L

        fun start(context: Context) {
            val intent = Intent(context, KioskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
