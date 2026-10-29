package com.cnc.host.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Kiosk 保活所需的系统授权辅助。
 *
 * 三项授权都【无法静默完成】，需要在引导页由管理员手动点一次：
 * - 悬浮窗（显示在其他应用上层）：MIUI 等 ROM 允许「后台弹出界面」的前提，
 *   开机自启与崩溃重启依赖它
 * - 忽略电池优化：防止系统休眠时杀掉保活服务与心跳
 * - 开机自启：国产 ROM 的专属开关，只能跳到对应设置页由用户打开
 */
object KioskPermissions {

    // ===== 悬浮窗 / 后台弹出界面 =====

    fun hasOverlay(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun overlayIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    // ===== 忽略电池优化 =====

    fun isBatteryExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batteryExemptIntent(context: Context): Intent =
        runCatching {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }.getOrElse { appDetailsIntent(context) }

    // ===== 开机自启（厂商定制页，逐个尝试，兜底应用详情页）=====

    fun autostartIntent(context: Context): Intent {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val candidates = mutableListOf<Intent>()
        when {
            manufacturer.contains("xiaomi", true) || manufacturer.contains("redmi", true) -> {
                candidates += componentIntent(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            manufacturer.contains("oppo", true) -> {
                candidates += componentIntent(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            }
            manufacturer.contains("vivo", true) -> {
                candidates += componentIntent(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            }
            manufacturer.contains("huawei", true) || manufacturer.contains("honor", true) -> {
                candidates += componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }
        }
        // 通用兜底：MIUI 安全中心主页 / 应用详情页
        candidates += componentIntent("com.miui.securitycenter", "com.miui.securitycenter.MainActivity")
        return candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
            ?: appDetailsIntent(context)
    }

    private fun componentIntent(pkg: String, cls: String): Intent =
        Intent().setClassName(pkg, cls)

    private fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
}
