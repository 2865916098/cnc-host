package com.cnc.host.ui.device

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import com.cnc.host.ui.components.KioskTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.host.sync.DeviceConfigDto
import com.cnc.host.sync.ServerPrefs
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography
import com.google.gson.Gson

/** 控制卡配置页面。 */
@Composable
fun DeviceScreen(modifier: Modifier = Modifier, vm: DeviceViewModel = viewModel()) {
    val c by vm.cfg.collectAsState()
    val saved by vm.saved.collectAsState()

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Text("控制卡配置", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(12.dp))

        // P2-④ 弱网/离线提示：同步状态 + 待补传任务数
        SyncStatusCard()
        Spacer(Modifier.height(12.dp))

        ServerDriverCard()
        Spacer(Modifier.height(12.dp))

        Column(
            Modifier.fillMaxWidth().background(CncColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
        ) {
            Text("控制卡型号", style = CncTypography.titleMedium, fontWeight = FontWeight.Bold, color = CncColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ControlCard.values().forEach { cc ->
                    FilterChip(selected = c.card == cc, onClick = { vm.update { cfg -> cfg.copy(card = cc) } }, label = { Text(cc.name.replace('_', ' ')) })
                }
            }

            Spacer(Modifier.height(12.dp))
            var name by remember(c.name) { mutableStateOf(c.name) }
            Text("设备名称", style = CncTypography.bodySmall, color = CncColors.TextPrimary)
            KioskTextField(name, onValueChange = { name = it; vm.update { cfg -> cfg.copy(name = it) } }, Modifier.fillMaxWidth(), singleLine = true)

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldWithLabel("X 最大行程 (mm)", c.maxTravelXmm.toString()) { v -> vm.update { cfg -> cfg.copy(maxTravelXmm = v.toDoubleOrNull() ?: c.maxTravelXmm) } }
                FieldWithLabel("Z 最大行程 (mm)", c.maxTravelZmm.toString()) { v -> vm.update { cfg -> cfg.copy(maxTravelZmm = v.toDoubleOrNull() ?: c.maxTravelZmm) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldWithLabel("主轴最大 RPM", c.maxRpm.toString()) { v -> vm.update { cfg -> cfg.copy(maxRpm = v.toIntOrNull() ?: c.maxRpm) } }
                FieldWithLabel("最大进给 (mm/min)", c.maxFeedMmPerMin.toString()) { v -> vm.update { cfg -> cfg.copy(maxFeedMmPerMin = v.toDoubleOrNull() ?: c.maxFeedMmPerMin) } }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("使用模拟器 (isSimulated)", style = CncTypography.bodySmall, color = CncColors.TextPrimary)
                    Text("关闭后需连接真实控制卡", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                }
                Switch(checked = c.useSimulator, onCheckedChange = { v -> vm.update { cfg -> cfg.copy(useSimulator = v) } })
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save() }, modifier = Modifier) { Text("保存配置") }
                if (saved) Text("✓ 已保存（内存态，重启后丢失）", style = CncTypography.bodySmall, color = Color(0xFF4CAF50))
            }
        }
    }
}

/**
 * 服务端下发的驱动配置（只读）。
 *
 * 驱动类型与连接参数由服务端后台统一下发；本页上方表单仅为本地演示用，
 * 真实生效以最近一次服务端同步为准。
 */
@Composable
private fun ServerDriverCard() {
    val context = LocalContext.current
    val dto = remember {
        ServerPrefs.deviceJson(context)
            ?.let { runCatching { Gson().fromJson(it, DeviceConfigDto::class.java) }.getOrNull() }
    } ?: return

    Column(
        Modifier.fillMaxWidth().background(CncColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
    ) {
        Text(
            "服务端下发驱动配置（生效以服务端为准）",
            style = CncTypography.titleMedium, fontWeight = FontWeight.Bold, color = CncColors.TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "驱动类型：${dto.comm.driverType}　协议：${dto.comm.protocol}　模式：${if (dto.useSimulator) "模拟器" else "真实控制卡"}",
            style = CncTypography.bodySmall, color = CncColors.TextPrimary
        )
        Text(
            "地址：${dto.comm.ipAddress}:${dto.comm.port}　站号：${dto.comm.deviceId}　超时：${dto.comm.timeoutMs}ms",
            style = CncTypography.bodySmall, color = CncColors.TextPrimary
        )
        if (dto.comm.params.isNotEmpty()) {
            Text(
                "扩展参数：" + dto.comm.params.entries.joinToString("　") { "${it.key}=${it.value}" },
                style = CncTypography.bodySmall, color = CncColors.TextSecondary
            )
        }
    }
}

@Composable
private fun FieldWithLabel(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = CncTypography.bodySmall, color = CncColors.TextPrimary)
        KioskTextField(text, onValueChange = { text = it; onChange(it) }, Modifier.fillMaxWidth(), singleLine = true, keyboardType = KeyboardType.Decimal)
    }
}

/** P2-④ 弱网/离线提示：最后同步时间 + 待补传任务数。 */
@Composable
private fun SyncStatusCard() {
    val syncState by com.cnc.host.sync.SyncManager.state.collectAsState()
    val pendingColor = if (syncState.pendingCount > 0) CncColors.Warning else CncColors.Success
    val statusColor = when {
        !syncState.configured -> CncColors.TextSecondary
        syncState.online -> CncColors.Success
        else -> CncColors.Warning
    }
    val statusText = when {
        !syncState.configured -> "未配置"
        syncState.syncing -> "同步中…"
        syncState.online -> "在线"
        else -> "离线"
    }
    val lastSyncText = syncState.lastSyncAt?.let {
        android.text.format.DateFormat.format("MM-dd HH:mm", it).toString()
    } ?: "从未同步"

    Column(
        Modifier.fillMaxWidth().background(CncColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("同步状态", style = CncTypography.titleMedium, color = CncColors.TextPrimary)
            Text(statusText, style = CncTypography.bodySmall, color = statusColor)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("上次同步", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
            Text(lastSyncText, style = CncTypography.bodySmall, color = CncColors.TextPrimary)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("待补传任务", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
            Text(
                if (syncState.pendingCount == 0) "0 条" else "${syncState.pendingCount} 条",
                style = CncTypography.bodySmall,
                color = pendingColor
            )
        }
    }
}
