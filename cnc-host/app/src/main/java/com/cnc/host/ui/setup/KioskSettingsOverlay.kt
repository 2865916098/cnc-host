package com.cnc.host.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 设置面板覆盖层。
 *
 * 先输入管理员密码，通过后可修改服务器地址并临时退出到安卓桌面。
 */
@Composable
fun KioskSettingsOverlay(
    onDismiss: () -> Unit,
    onExitToAndroid: () -> Unit,
    viewModel: KioskSettingsViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CncColors.Background.copy(alpha = 0.92f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!ui.verified) {
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = CncColors.Surface,
                title = { Text("设置", color = CncColors.TextPrimary) },
                text = {
                    Column {
                        Text(
                            "请输入管理员密码（在后台「设备管理」中设置，默认 admin123）",
                            style = CncTypography.bodyMedium,
                            color = CncColors.TextSecondary
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = ui.password,
                            onValueChange = viewModel::setPassword,
                            label = { Text("管理员密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (ui.passwordError != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(ui.passwordError!!, style = CncTypography.labelSmall, color = CncColors.Danger)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = viewModel::verifyPassword,
                        enabled = !ui.verifying,
                        colors = ButtonDefaults.buttonColors(containerColor = CncColors.Primary)
                    ) { Text(if (ui.verifying) "校验中…" else "确认") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消", color = CncColors.TextSecondary) }
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CncColors.Surface, RoundedCornerShape(10.dp))
                    .padding(20.dp)
            ) {
                Text("设置", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
                Spacer(Modifier.height(16.dp))

                Text("服务器地址", style = CncTypography.bodyLarge, color = CncColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = ui.serverAddress,
                    onValueChange = viewModel::setServerAddress,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = viewModel::saveServerAddress) { Text("保存地址") }
                    OutlinedButton(onClick = onDismiss) { Text("关闭") }
                }

                if (ui.message != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(ui.message!!, style = CncTypography.bodyMedium, color = CncColors.Success)
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        viewModel.exitToAndroid()
                        onExitToAndroid()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CncColors.Danger),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("退出到安卓桌面", style = CncTypography.titleMedium) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "退出后 20 秒内不会自动拉起；到期后软件会自动回到前台。",
                    style = CncTypography.labelSmall,
                    color = CncColors.TextSecondary
                )
            }
        }
    }
}
