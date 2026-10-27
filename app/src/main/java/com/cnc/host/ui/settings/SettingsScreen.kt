package com.cnc.host.ui.settings

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/** 系统设置页面。 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
    val s by vm.s.collectAsState()

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Text("系统设置", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(12.dp))

        Column(
            Modifier.fillMaxWidth().background(CncColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
        ) {
            SettingRow("深色主题", s.darkMode) { v -> vm.update { s -> s.copy(darkMode = v) } }
            SettingRow("显示模拟数据徽章", s.showSimBadge) { v -> vm.update { s -> s.copy(showSimBadge = v) } }
            SettingRow("启用故障注入（模拟器）", s.enableFaultInjection) { v -> vm.update { s -> s.copy(enableFaultInjection = v) } }

            Spacer(Modifier.height(12.dp))
            Text("默认后处理器", style = CncTypography.bodySmall, color = CncColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Fanuc 0i", "Siemens 840D", "通用").forEach { name ->
                    FilterChip(selected = s.defaultPost == name,
                        onClick = { vm.update { it.copy(defaultPost = name) } },
                        label = { Text(name) })
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("应用信息", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            InfoRow("版本", "v0.1.0")
            InfoRow("构建", "debug (CNC_HOST)")
            InfoRow("测试覆盖", "118 tests, 0 failures")
            InfoRow("模块数", "9")
            InfoRow("协议支持", "MODBUS TCP/RTU · Fanuc 0i · Siemens 840D")
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = CncTypography.bodyMedium, color = CncColors.TextPrimary)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        Text(v, style = CncTypography.bodySmall, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
    }
}
