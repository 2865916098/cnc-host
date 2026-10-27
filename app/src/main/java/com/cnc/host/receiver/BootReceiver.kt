package com.cnc.host.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cnc.host.MainActivity

/**
 * 开机自启接收器。
 *
 * 系统启动完成后拉起 MainActivity 并启动保活服务，
 * 使软件始终处于运行状态。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 拉起主界面；保活服务由 MainActivity.onResume 在前台启动，
        // 避免开机广播这种后台场景直接起前台服务被系统判定超时崩溃
        runCatching {
            val activityIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(activityIntent)
        }
    }
}
