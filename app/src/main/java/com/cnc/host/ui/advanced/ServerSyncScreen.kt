package com.cnc.host.ui.advanced

import android.text.format.DateFormat
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 服务端同步设置（高级模式）。
 *
 * 只配置连接参数；刀具/材料/工艺/图纸均在服务端后台维护。
 */
@Composable
fun ServerSyncScreen(
    viewModel: ServerSyncViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("服务端同步", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "配置由服务端统一维护，平板只做缓存与执行。",
                    style = CncTypography.bodyMedium,
                    color = CncColors.TextSecondary
                )
            }
            SyncBadge(ui)
        }

        Spacer(Modifier.height(14.dp))

        // ===== 连接设置 =====
        Section("连接设置") {
            OutlinedTextField(
                value = ui.baseUrl,
                onValueChange = viewModel::setBaseUrl,
                label = { Text("服务端地址") },
                placeholder = { Text("http://192.168.1.10:8080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ui.token,
                onValueChange = viewModel::setToken,
                label = { Text("管理口令（CNC_ADMIN_TOKEN）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ui.deviceId,
                    onValueChange = viewModel::setDeviceId,
                    label = { Text("设备编号") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = ui.pin,
                    onValueChange = viewModel::setPin,
                    label = { Text("高级 PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = ui.enabled, onCheckedChange = viewModel::setEnabled)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("启动时自动同步", style = CncTypography.bodyLarge, color = CncColors.TextPrimary)
                    Text(
                        "离线时不阻塞启动，继续使用本地缓存。",
                        style = CncTypography.labelSmall,
                        color = CncColors.TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !ui.testing && !ui.syncing
                ) { Text(if (ui.testing) "测试中…" else "测试连接") }
                OutlinedButton(
                    onClick = viewModel::save,
                    enabled = !ui.saving
                ) { Text(if (ui.saving) "保存中…" else "保存设置") }
                Button(
                    onClick = viewModel::syncNow,
                    enabled = !ui.syncing,
                    colors = ButtonDefaults.buttonColors(containerColor = CncColors.Primary)
                ) { Text(if (ui.syncing) "同步中…" else "立即同步") }
            }

            if (ui.message != null) {
                Spacer(Modifier.height(10.dp))
                Text(ui.message!!, style = CncTypography.bodyMedium, color = CncColors.Success)
            }
            if (ui.error != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(CncColors.Warning.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        "${ui.error}（离线时继续使用本地缓存）",
                        style = CncTypography.bodyMedium,
                        color = CncColors.Warning
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 同步状态 =====
        Section("同步状态") {
            InfoRow(
                "连接",
                when {
                    !ui.configured -> "未配置"
                    ui.online -> "在线"
                    else -> "离线"
                },
                when {
                    !ui.configured -> CncColors.TextSecondary
                    ui.online -> CncColors.Success
                    else -> CncColors.Warning
                }
            )
            InfoRow("本地配置版本", "v${ui.cachedVersion}", CncColors.TextPrimary)
            InfoRow(
                "上次同步",
                ui.lastSyncAt?.let { DateFormat.format("yyyy-MM-dd HH:mm", it).toString() } ?: "从未同步",
                CncColors.TextPrimary
            )
            InfoRow(
                "待补传任务",
                if (ui.pendingCount == 0) "0 条" else "${ui.pendingCount} 条",
                if (ui.pendingCount == 0) CncColors.Success else CncColors.Warning
            )
        }

        Spacer(Modifier.height(12.dp))

        // ===== 本地缓存 =====
        Section("本地缓存统计") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("图纸", ui.drawingsCount, Modifier.weight(1f))
                Stat("刀具", ui.toolsCount, Modifier.weight(1f))
                Stat("材料", ui.materialsCount, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("工艺方案", ui.plansCount, Modifier.weight(1f))
                Stat("历史任务", ui.historyCount, Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "这些数据由服务端下发并覆盖；平板不提供编辑入口。",
                style = CncTypography.labelSmall,
                color = CncColors.TextSecondary
            )
        }

        Spacer(Modifier.height(12.dp))

        // ===== 服务端下发设备参数 =====
        Section("服务端下发设备参数") {
            if (ui.deviceSummary.isEmpty() && ui.commSummary.isEmpty()) {
                Text(
                    "尚未从服务端同步设备参数。配置好地址与口令后点击「立即同步」。",
                    style = CncTypography.bodyMedium,
                    color = CncColors.TextSecondary
                )
            } else {
                ui.deviceSummary.forEach { (label, value) -> InfoRow(label, value) }
                if (ui.commSummary.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("通讯参数", style = CncTypography.titleMedium, color = CncColors.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    ui.commSummary.forEach { (label, value) -> InfoRow(label, value) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "安全说明：急停、限位与运动下发完全在本地设备侧执行，不依赖服务端在线；" +
                "服务端只负责配置下发、任务记录与状态展示。",
            style = CncTypography.labelSmall,
            color = CncColors.TextSecondary
        )
    }
}

@Composable
private fun SyncBadge(ui: ServerSyncUiState) {
    val (text, color) = when {
        ui.syncing -> "同步中…" to CncColors.Warning
        !ui.configured -> "未连接" to CncColors.TextSecondary
        ui.online -> "在线 v${ui.cachedVersion}" to CncColors.Success
        else -> "离线缓存 v${ui.cachedVersion}" to CncColors.Warning
    }
    Box(
        Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = CncTypography.labelLarge, color = color)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
            .background(CncColors.Surface, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(title, style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = CncColors.TextPrimary) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
        Text(value, style = CncTypography.bodyMedium, color = valueColor)
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(CncColors.Panel, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Text(label, style = CncTypography.labelSmall, color = CncColors.TextSecondary)
        Spacer(Modifier.height(2.dp))
        Text("$value", style = CncTypography.titleLarge, color = CncColors.TextPrimary)
    }
}
