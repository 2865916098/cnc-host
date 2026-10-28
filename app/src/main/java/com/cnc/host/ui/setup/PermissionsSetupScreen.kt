package com.cnc.host.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
 * 首次启动第 2 步：权限与开机自启引导。
 *
 * 这三项系统授权决定 Kiosk 保活强度（开机自启、崩溃拉起、防休眠杀进程），
 * 只能由管理员在系统页面手动打开。全部可跳过——不授权不影响激活与加工，
 * 仅保活能力变弱。授权状态在从系统页面返回时自动刷新。
 */
@Composable
fun PermissionsSetupScreen(onNext: () -> Unit) {
    val context = LocalContext.current

    var overlay by remember { mutableStateOf(KioskPermissions.hasOverlay(context)) }
    var battery by remember { mutableStateOf(KioskPermissions.isBatteryExempt(context)) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(28.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("第 2 步：权限与开机自启", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "以下授权用于：开机自动打开软件、崩溃后自动恢复、防止系统休眠杀进程。在弹出的系统页面中找到本软件并允许即可。",
            style = CncTypography.bodyMedium,
            color = CncColors.TextSecondary
        )
        Spacer(Modifier.height(20.dp))

        PermissionRow(
            title = "悬浮窗 / 后台弹出界面",
            desc = "允许后开机与崩溃时才能自动把软件拉回前台（小米/红米设备必须）",
            granted = overlay,
            buttonText = "去授权"
        ) {
            runCatching { context.startActivity(KioskPermissions.overlayIntent(context)) }
        }
        Spacer(Modifier.height(10.dp))
        PermissionRow(
            title = "忽略电池优化",
            desc = "防止系统休眠时结束保活服务与心跳，在线状态更准确",
            granted = battery,
            buttonText = "去授权"
        ) {
            runCatching { context.startActivity(KioskPermissions.batteryExemptIntent(context)) }
        }
        Spacer(Modifier.height(10.dp))
        PermissionRow(
            title = "开机自启",
            desc = "在系统自启管理中找到 CNC_HOST 并允许（该开关无法自动检测，请手动确认）",
            granted = null,
            buttonText = "去设置"
        ) {
            runCatching { context.startActivity(KioskPermissions.autostartIntent(context)) }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CncColors.Success)
        ) {
            Text(
                if (overlay && battery) "已完成授权，下一步" else "暂不授权，直接下一步",
                style = CncTypography.titleMedium
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "跳过后仍可正常使用；之后想补强保活，可在系统设置中随时补上这三项。",
            style = CncTypography.labelSmall,
            color = CncColors.TextSecondary
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    /** null = 状态无法检测（如自启），不显示已授权标记 */
    granted: Boolean?,
    buttonText: String,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = CncTypography.titleMedium, color = CncColors.TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(desc, style = CncTypography.labelSmall, color = CncColors.TextSecondary)
        }
        if (granted == true) {
            Text("已授权", style = CncTypography.labelLarge, color = CncColors.Success)
        } else {
            OutlinedButton(onClick = onGrant) { Text(buttonText) }
        }
    }
}
