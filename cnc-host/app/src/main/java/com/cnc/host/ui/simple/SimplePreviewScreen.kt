package com.cnc.host.ui.simple

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import com.cnc.host.simple.RunPhase
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 预览与下发页。
 *
 * 大按钮 + 明确的确认项，避免误操作；急停始终可见。
 */
@Composable
fun SimplePreviewScreen(
    onOpenMonitor: () -> Unit,
    viewModel: SimplePreviewViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val run by viewModel.runState.collectAsState()

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
                Text("预览与下发", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    ui.drawingName.ifBlank { "未选择任务" },
                    style = CncTypography.titleMedium,
                    color = CncColors.Info
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.setConfirmClamp(!ui.confirmClamp) }) {
                    Text(if (ui.confirmClamp) "已确认装夹" else "确认装夹")
                }
                Button(
                    onClick = { viewModel.emergencyStop() },
                    colors = ButtonDefaults.buttonColors(containerColor = CncColors.Danger)
                ) { Text("急停") }
            }
        }

        Spacer(Modifier.height(14.dp))

        when {
            ui.loading -> Centered("正在生成刀路…")
            ui.error != null -> Centered("⚠ ${ui.error}")
            ui.toolpath == null -> Centered("请先在「任务」页选择一个加工任务")
            else -> {
                if (run.phase == RunPhase.RUNNING || run.phase == RunPhase.PAUSED) {
                    val bannerText = if (run.phase == RunPhase.PAUSED)
                        "作业已暂停：${run.taskName}  ${(run.progress * 100).toInt()}%"
                    else
                        "作业执行中：${run.taskName}  ${(run.progress * 100).toInt()}%"
                    Banner(bannerText, if (run.phase == RunPhase.PAUSED) CncColors.Info else CncColors.Warning)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onOpenMonitor) { Text("查看运行监控") }
                    Spacer(Modifier.height(10.dp))
                }

                // ===== 概要 =====
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                        .background(CncColors.Surface, RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Text("加工概要", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    InfoRow("刀路来源", ui.drawingName)
                    InfoRow("工艺方案", ui.planName ?: "未关联（默认参数）", if (ui.planName != null) CncColors.TextPrimary else CncColors.Warning)
                    InfoRow("图纸校正", ui.correctionSummary ?: "未设置")
                    InfoRow("毛坯", ui.stockNote)
                    InfoRow("主轴转速", "${ui.spindleRpm} rpm")
                    InfoRow("刀路段数", "${ui.toolpath?.segments?.size ?: 0}")
                    InfoRow("G-code 行数", "${ui.gcode?.lines?.size ?: 0}")
                    InfoRow(
                        "刀路校验",
                        if (ui.ready) "通过" else "存在警告",
                        if (ui.ready) CncColors.Success else CncColors.Warning
                    )
                }

                if (ui.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0x33FBBF24), RoundedCornerShape(6.dp))
                            .padding(12.dp)
                    ) {
                        Text("校验警告", style = CncTypography.titleMedium, color = CncColors.Warning)
                        ui.warnings.forEach {
                            Text("• $it", style = CncTypography.bodyMedium, color = CncColors.Warning)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ===== G-code =====
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                        .background(CncColors.Panel, RoundedCornerShape(8.dp))
                        .padding(14.dp)
                ) {
                    Text("G-code 预览", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(210.dp)) {
                        val lines = ui.gcode?.lines.orEmpty()
                        items(lines.size) { i ->
                            Text(
                                lines[i],
                                style = CncTypography.labelSmall,
                                color = when {
                                    lines[i].contains("M03") || lines[i].contains("M05") -> CncColors.Success
                                    lines[i].contains("G00") -> CncColors.Warning
                                    lines[i].startsWith("(") -> CncColors.TextSecondary
                                    else -> CncColors.TextPrimary
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ===== 确认与下发 =====
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(CncColors.Surface, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = ui.confirmClamp,
                        onCheckedChange = { viewModel.setConfirmClamp(it) },
                        colors = CheckboxDefaults.colors(checkedColor = CncColors.Success)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "已确认：毛坯装夹牢固、对刀完成、安全门关闭、现场无人员靠近",
                        style = CncTypography.bodyMedium,
                        color = CncColors.TextPrimary
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (viewModel.start()) onOpenMonitor()
                        },
                        enabled = ui.ready && ui.confirmClamp && !run.running && run.phase != RunPhase.PAUSED,
                        modifier = Modifier.height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CncColors.Success,
                            disabledContainerColor = CncColors.SurfaceVariant
                        )
                    ) {
                        Text("下发并开始加工", style = CncTypography.titleMedium, color = CncColors.TextPrimary)
                    }
                    OutlinedButton(
                        onClick = { viewModel.resetRun() },
                        enabled = !run.running,
                        modifier = Modifier.height(56.dp)
                    ) { Text("清除结果") }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "当前设备为模拟控制卡，轴位置与加工结果为模拟数据；真实设备接入后此提示会自动消失。",
                    style = CncTypography.labelSmall,
                    color = CncColors.Simulator
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, color: Color = CncColors.TextPrimary) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
        Text(value, style = CncTypography.bodyMedium, color = color)
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(12.dp)
    ) {
        Text(text, style = CncTypography.bodyLarge, color = color)
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        Text(text, style = CncTypography.bodyLarge, color = CncColors.TextSecondary)
    }
}
