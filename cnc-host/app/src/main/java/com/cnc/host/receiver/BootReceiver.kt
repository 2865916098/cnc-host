package com.cnc.host.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cnc.host.MainActivity
import com.cnc.host.service.KioskService

/**
 * 开机自启接收器。
 *
 * 系统启动完成后拉起 MainActivity 并启动保活服务，
 * 使软件始终处于运行状态。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            val activityIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(activityIntent)
        }
        runCatching { KioskService.start(context) }
    }
}
