package com.cnc.host.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cnc.host.setup.KioskPermissions
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 首次启动第 3 步：权限配置。
 *
 * 这三项系统授权决定 Kiosk 保活强度（开机自动启动、始终保持运行不可退出到桌面、
 * 崩溃立即重启），只能由管理员在系统页面手动打开。全部可跳过——不授权不影响使用，
 * 仅保活能力变弱。授权状态在从系统页面返回时自动刷新。
 */
@Composable
fun PermissionsSetupScreen(onNext: () -> Unit) {
    val context = LocalContext.current

    var overlay by remember { mutableStateOf(KioskPermissions.hasOverlay(context)) }
    var battery by remember { mutableStateOf(KioskPermissions.isBatteryExempt(context)) }
    // 未全部开启时点击「完成配置」后的提醒
    var remindComplete by remember { mutableStateOf(false) }

    // 从系统授权页返回时刷新状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlay = KioskPermissions.hasOverlay(context)
                battery = KioskPermissions.isBatteryExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 开机自启状态系统无法检测，可检测项全部开启即视为「已全部开启」
    val allGranted = overlay && battery

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(28.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SetupStepIndicator(currentStep = 2)
        Spacer(Modifier.height(12.dp))
        Text("权限配置", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "为确保设备正常运行，请完成以下配置",
            style = CncTypography.bodyMedium,
            color = CncColors.TextSecondary
        )
        Spacer(Modifier.height(20.dp))

        PermissionRow(
            icon = "🪟",
            title = "悬浮窗 / 后台弹出界面",
            desc = "允许后开机与崩溃时才能自动把软件拉回前台（小米/红米设备必须）",
            granted = overlay
        ) {
            runCatching { context.startActivity(KioskPermissions.overlayIntent(context)) }
        }
        Spacer(Modifier.height(10.dp))
        PermissionRow(
            icon = "🔋",
            title = "忽略电池优化",
            desc = "防止系统休眠时结束保活服务与心跳，在线状态更准确",
            granted = battery
        ) {
            runCatching { context.startActivity(KioskPermissions.batteryExemptIntent(context)) }
        }
        Spacer(Modifier.height(10.dp))
        PermissionRow(
            icon = "🚀",
            title = "开机自启",
            desc = "在系统自启管理中找到 CNC_HOST 并允许（该开关无法自动检测，请手动确认）",
            granted = null
        ) {
            runCatching { context.startActivity(KioskPermissions.autostartIntent(context)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "开机自启需要手动在系统设置中开启",
            style = CncTypography.labelSmall,
            color = CncColors.TextSecondary
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (allGranted) {
                    onNext()
                } else {
                    // 未全部开启也可继续，但先提示建议完成所有配置
                    remindComplete = true
                }
            },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (allGranted) CncColors.Success else CncColors.SurfaceVariant
            )
        ) { Text("完成配置", style = CncTypography.titleMedium) }
        if (remindComplete && !allGranted) {
            Spacer(Modifier.height(10.dp))
            Text(
                "建议完成所有配置后再进入主界面，否则保活能力会变弱。",
                style = CncTypography.bodyMedium,
                color = CncColors.Warning
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("仍要进入主界面") }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: String,
    title: String,
    desc: String,
    /** null = 状态无法检测（如自启），不显示已开启标记 */
    granted: Boolean?,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
            .background(CncColors.Surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 状态图标圆
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (granted == true) CncColors.Success.copy(alpha = 0.15f) else CncColors.SurfaceVariant,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, style = CncTypography.titleMedium)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = CncTypography.titleMedium, color = CncColors.TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(desc, style = CncTypography.labelSmall, color = CncColors.TextSecondary)
        }
        if (granted == true) {
            Text("已开启", style = CncTypography.labelLarge, color = CncColors.Success)
        } else {
            OutlinedButton(onClick = onGrant) { Text("去设置") }
        }
    }
}
