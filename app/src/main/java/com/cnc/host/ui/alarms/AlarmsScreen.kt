package com.cnc.host.ui.alarms

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
 * 报警中心页面。
 *
 * ⚠️ 数据来自历史日志（模拟），不是实时设备上报。
 */
@Composable
fun AlarmsScreen(
    modifier: Modifier = Modifier,
    viewModel: AlarmsViewModel = viewModel()
) {
    val alarms by viewModel.alarms.collectAsState()
    val active = alarms.count { it.active }

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("报警中心", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
                Spacer(Modifier.padding(8.dp))
                if (active > 0) {
                    Text("$active 活动中", style = CncTypography.labelMedium,
                        color = Color(0xFFEF5350),
                        modifier = Modifier.background(Color(0x33EF5350), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            OutlinedButton(onClick = { viewModel.clearAll() }) { Text("全部确认") }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(alarms) { a ->
                AlarmRow(a, onAck = { viewModel.acknowledge(a.id) })
            }
        }
    }
}

@Composable
private fun AlarmRow(alarm: AlarmsViewModel.AlarmItem, onAck: () -> Unit) {
    val (bgColor, accent) = when (alarm.severity) {
        AlarmsViewModel.Severity.CRITICAL -> Color(0xFF4A0F0F) to Color(0xFFEF5350)
        AlarmsViewModel.Severity.ERROR -> Color(0xFF3A1515) to Color(0xFFF87171)
        AlarmsViewModel.Severity.WARNING -> Color(0xFF3A3015) to Color(0xFFFBBF24)
        AlarmsViewModel.Severity.INFO -> Color(0xFF15303A) to Color(0xFF60A5FA)
    }
    val bgAlpha = if (alarm.active) 1.0f else 0.4f

    Row(
        Modifier.fillMaxWidth().background(bgColor.copy(alpha = bgAlpha), RoundedCornerShape(8.dp))
            .border(1.dp, accent.copy(alpha = bgAlpha), RoundedCornerShape(8.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(accent, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(alarm.severity.name, color = Color.White, style = CncTypography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.padding(8.dp))
            Column {
                Text(alarm.code, style = CncTypography.titleSmall, color = accent, fontWeight = FontWeight.Bold)
                Text(alarm.message, style = CncTypography.bodyMedium, color = CncColors.TextPrimary)
                Text(formatTime(alarm.timestamp), style = CncTypography.bodySmall, color = CncColors.TextSecondary)
            }
        }
        if (alarm.active) {
            Button(onClick = onAck, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                Text("确认")
            }
        } else {
            Text("✓ 已确认", style = CncTypography.labelMedium, color = Color(0xFF4CAF50))
        }
    }
}

private fun formatTime(ts: Long): String {
    val s = (System.currentTimeMillis() - ts) / 1000
    return when {
        s < 60 -> "${s}s 前"
        s < 3600 -> "${s / 60}m 前"
        s < 86400 -> "${s / 3600}h 前"
        else -> "${s / 86400}d 前"
    }
}
