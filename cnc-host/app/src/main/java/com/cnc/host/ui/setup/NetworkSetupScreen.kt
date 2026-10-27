package com.cnc.host.ui.setup

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 首次启动：网络配置。
 *
 * 受 Android 权限限制，应用不能直接配置 WiFi/数据，
 * 这里跳转到系统设置页，由用户手动连接。
 */
@Composable
fun NetworkSetupScreen(onNext: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("第 1 步：配置网络", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(12.dp))
        Text(
            "请连接可用的 WiFi 或移动数据网络，确保本机能访问服务器。",
            style = CncTypography.bodyMedium,
            color = CncColors.TextSecondary
        )
        Spacer(Modifier.height(28.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CncColors.Primary)
            ) { Text("打开 WiFi 设置", style = CncTypography.titleMedium) }

            OutlinedButton(
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("打开移动网络设置") }
        }

        Spacer(Modifier.height(36.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CncColors.Success)
        ) { Text("我已联网，下一步", style = CncTypography.titleMedium) }

        Spacer(Modifier.height(16.dp))
        Text(
            "联网后请继续完成设备注册，本机会自动上报到后台。",
            style = CncTypography.labelSmall,
            color = CncColors.TextSecondary
        )
    }
}
