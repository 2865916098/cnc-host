package com.cnc.host.ui.io

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * IO 监控页面。
 *
 * ⚠️ 数据来自 SimulatedDeviceController（isSimulated = true）。
 */
@Composable
fun IoScreen(
    modifier: Modifier = Modifier,
    viewModel: IoViewModel = viewModel()
) {
    val inputs by viewModel.inputsState.collectAsState()
    val outputs by viewModel.outputsState.collectAsState()

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("IO 监控", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
            Text("⚡ 模拟数据", style = CncTypography.labelMedium, color = Color(0xFFFFA500))
        }
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 输入
            Column(
                Modifier.fillMaxSize()
                    .background(CncColors.Surface, RoundedCornerShape(8.dp))
                    .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
            ) {
                Text("输入点 (DI)", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text("只读，来自现场传感器", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                Spacer(Modifier.height(12.dp))
                viewModel.inputs.forEach { (idx, label) ->
                    IoRow(idx, label, inputs[idx] ?: false, writable = false, onToggle = {})
                    Spacer(Modifier.height(4.dp))
                }
            }

            // 输出
            Column(
                Modifier.fillMaxSize()
                    .background(CncColors.Surface, RoundedCornerShape(8.dp))
                    .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
            ) {
                Text("输出点 (DO)", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text("可手动切换（⚠ 仅模拟模式）", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                Spacer(Modifier.height(12.dp))
                viewModel.outputs.forEach { (idx, label) ->
                    IoRow(idx, label, outputs[idx] ?: false, writable = true, onToggle = { viewModel.setOutput(idx, !(outputs[idx] ?: false)) })
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun IoRow(idx: Int, label: String, value: Boolean, writable: Boolean, onToggle: () -> Unit) {
    val color = if (value) Color(0xFF4CAF50) else CncColors.TextDisabled
    Row(
        Modifier.fillMaxWidth().background(CncColors.Panel, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("IN$idx" + (if (!writable) " / OUT$idx" else "  "), style = CncTypography.labelMedium,
                    color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.padding(4.dp))
            Text(label, style = CncTypography.bodyMedium, color = CncColors.TextPrimary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (value) "ON" else "OFF", style = CncTypography.bodySmall,
                color = color, fontWeight = FontWeight.Bold)
            if (writable) {
                Spacer(Modifier.padding(8.dp))
                Switch(checked = value, onCheckedChange = { onToggle() })
            }
        }
    }
}
