package com.cnc.host.update

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cnc.ui.theme.CncColors
import kotlinx.coroutines.launch

/**
 * 自动更新弹窗：心跳发现新版本时弹出「是否更新」；
 * 确认后显示下载进度，完成后自动调起系统安装器。
 */
@Composable
fun AppUpdateDialog() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prompt by AppUpdate.prompt.collectAsState()
    val progress by AppUpdate.progress.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    val version = prompt
    when {
        // 下载中
        progress >= 0 -> AlertDialog(
            onDismissRequest = {},
            containerColor = CncColors.Surface,
            title = { Text("正在下载新版本", color = CncColors.TextPrimary) },
            text = {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = { Text("$progress%", color = CncColors.TextSecondary) }
        )
        version != null -> AlertDialog(
            onDismissRequest = { AppUpdate.dismiss() },
            containerColor = CncColors.Surface,
            title = { Text("发现新版本 v${version.versionName}", color = CncColors.TextPrimary) },
            text = {
                Text(
                    buildString {
                        if (version.changelog.isNotBlank()) append(version.changelog).append("\n\n")
                        append("大小约 ${version.sizeBytes / 1024 / 1024} MB，更新过程约 1 分钟。")
                        error?.let { append("\n\n$it") }
                    },
                    color = CncColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            error = AppUpdate.downloadAndInstall(context, version)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CncColors.Success)
                ) { Text("立即更新") }
            },
            dismissButton = {
                TextButton(onClick = { AppUpdate.dismiss() }) {
                    Text("暂不", color = CncColors.TextSecondary)
                }
            }
        )
    }
}
