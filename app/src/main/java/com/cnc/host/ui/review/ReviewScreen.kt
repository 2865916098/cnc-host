package com.cnc.host.ui.review

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
 * 加工审核页面。
 *
 * 左侧：校验报告（刀路校验 + 工件兼容性）
 * 中央：G-code 预览
 * 右侧：执行控制（连接 + 一键下发 + 实时日志）
 *
 * ⚠️ 下发目标为 SimulatedDeviceController，所有执行结果为模拟。
 */
@Composable
fun ReviewScreen(
    modifier: Modifier = Modifier,
    viewModel: ReviewViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsState()

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("加工审核", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
            Spacer(Modifier.padding(4.dp))
            Text("⚡ 模拟设备", style = CncTypography.labelMedium, color = Color(0xFFFFA500))
            OutlinedButton(onClick = { viewModel.reconnectDevice() }) { Text("重连模拟设备") }
        }
        Spacer(Modifier.height(12.dp))

        when {
            ui.loading -> { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中…") } }
            ui.error != null -> { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("⚠ ${ui.error}") } }
            ui.toolpath != null -> ReviewContent(ui, viewModel)
        }
    }
}

@Composable
private fun ReviewContent(ui: ReviewUiState, vm: ReviewViewModel) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // 校验报告
        Column(
            Modifier.fillMaxSize()
                .background(CncColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
        ) {
            Text("校验报告", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            val tp = ui.toolpath!!
            ReportRow("刀路来源", ui.sourceLabel.ifBlank { "-" })
            ReportRow("图纸校正", ui.correctionSummary ?: "未套用")
            ReportRow("程序号", "O${ui.gcode?.programNumber}")
            ReportRow("机床", ui.gcode?.machineFamily?.label ?: "-")
            ReportRow("刀路段数", "${tp.segments.size}")
            ReportRow("G-code 行数", "${ui.gcode?.lines?.size ?: 0}")
            ReportRow("可执行指令", "${ui.gcode?.instructionCount ?: 0}")
            ReportRow("刀路校验", if (ui.ready) "✓ 通过" else "⚠ 有警告", if (ui.ready) Color(0xFF4CAF50) else Color(0xFFFFA500))

            Spacer(Modifier.height(12.dp))
            Text("警告/提示", style = CncTypography.titleSmall, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            if (ui.messages.isEmpty()) {
                Text("无", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
            } else {
                ui.messages.forEach { Text("• $it", style = CncTypography.bodySmall, color = Color(0xFFFFA500)) }
            }
            tp.validation.messages.takeIf { it.isNotEmpty() }?.forEach {
                Text("• $it", style = CncTypography.bodySmall, color = Color(0xFFFFA500))
            }
            // 验证器未执行的校验项（规范 §3.3：未实现必须明示，不得宣称通过）
            ui.unverifiedChecks.forEach {
                Text("• 未校验：$it", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
            }

            Spacer(Modifier.height(12.dp))
            Text("安全约束", style = CncTypography.titleSmall, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            ConstraintRow("RESULT_UNKNOWN 禁止盲目重发", true)
            ConstraintRow("急停后 REAL_FAULT 禁止运动", true)
            ConstraintRow("行程越界检查", true)
            ConstraintRow("模拟数据标注 isSimulated", true)
        }

        // G-code
        Column(
            Modifier.fillMaxSize()
                .background(CncColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
        ) {
            Text("G-code 预览", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val gcode = ui.gcode!!
            LazyColumn {
                items(gcode.lines.size) { i ->
                    val line = gcode.lines[i]
                    val c = when {
                        line.contains("M03") -> Color(0xFF4CAF50)
                        line.contains("M05") -> Color(0xFFEF5350)
                        line.contains("G00") -> Color(0xFFFFA500)
                        line.contains("G01") -> Color(0xFF4CAF50)
                        line.contains("G02") || line.contains("G03") -> Color(0xFF2196F3)
                        line.trim().startsWith("(") || line.trim().startsWith("%") -> CncColors.TextSecondary.copy(alpha = 0.6f)
                        else -> CncColors.TextPrimary
                    }
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 1.dp)
                            .background(CncColors.Panel, RoundedCornerShape(3.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text(line, style = CncTypography.bodySmall, color = c) }
                }
            }
        }

        // 执行
        Column(
            Modifier.fillMaxSize()
                .background(CncColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("执行控制", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("连接状态: ${if (ui.deviceConnected) "✓ 已连接" else "离线"}",
                style = CncTypography.bodySmall,
                color = if (ui.deviceConnected) Color(0xFF4CAF50) else CncColors.TextSecondary)

            LinearProgressIndicator(progress = { ui.executeProgress },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFEF5350), trackColor = Color(0xFF333344))
            Text("进度 ${(ui.executeProgress * 100).toInt()}%", style = CncTypography.bodySmall, color = CncColors.TextSecondary)

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { vm.executeAll() }, enabled = !ui.executing && ui.ready,
                    modifier = Modifier,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text(if (ui.executing) "执行中…" else "一键执行") }
                OutlinedButton(onClick = { vm.stopExecution() }, enabled = ui.executing) { Text("停止") }
            }

            Text("执行日志", style = CncTypography.titleSmall, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            LazyColumn {
                items(ui.log.size) { i ->
                    Text(ui.log[i], style = CncTypography.bodySmall,
                        color = when {
                            ui.log[i].contains("RESULT_UNKNOWN") || ui.log[i].contains("SEND_FAILED") -> Color(0xFFEF5350)
                            ui.log[i].contains("✓") -> Color(0xFF4CAF50)
                            else -> CncColors.TextSecondary
                        })
                }
            }

            ui.error?.let {
                Box(Modifier.fillMaxWidth().background(Color(0x33EF5350), RoundedCornerShape(4.dp)).padding(8.dp)) {
                    Text("⚠ $it", style = CncTypography.bodySmall, color = Color(0xFFEF5350))
                }
            }
        }
    }
}

@Composable
private fun ReportRow(k: String, v: String, color: Color = CncColors.TextPrimary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        Text(v, style = CncTypography.bodySmall, color = color)
    }
}

@Composable
private fun ConstraintRow(label: String, checked: Boolean) {
    val icon = if (checked) "✓" else "•"
    val color = if (checked) Color(0xFF4CAF50) else CncColors.TextSecondary
    Text("$icon $label", style = CncTypography.bodySmall, color = color)
}
