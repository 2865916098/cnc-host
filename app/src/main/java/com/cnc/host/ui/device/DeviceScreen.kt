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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/** 控制卡配置页面。 */
@Composable
fun DeviceScreen(modifier: Modifier = Modifier, vm: DeviceViewModel = viewModel()) {
    val c by vm.cfg.collectAsState()
    val saved by vm.saved.collectAsState()

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Text("控制卡配置", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
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
            OutlinedTextField(name, onValueChange = { name = it; vm.update { cfg -> cfg.copy(name = it) } }, Modifier.fillMaxWidth(), singleLine = true)

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

@Composable
private fun FieldWithLabel(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = CncTypography.bodySmall, color = CncColors.TextPrimary)
        OutlinedTextField(text, onValueChange = { text = it; onChange(it) }, Modifier.fillMaxWidth(), singleLine = true)
    }
}
