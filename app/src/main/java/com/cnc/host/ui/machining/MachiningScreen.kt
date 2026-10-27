package com.cnc.host.ui.machining

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 加工控制页面（手动/Jog + 主轴 + 连接 + 回原点）。
 *
 * ⚠️ 连接的是 SimulatedDeviceController（isSimulated = true）。
 */
@Composable
fun MachiningScreen(
    modifier: Modifier = Modifier,
    viewModel: MachiningViewModel = viewModel()
) {
    val snap by viewModel.snapshot.collectAsState()
    val ui by viewModel.uiState.collectAsState()
    var showEstopConfirm by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)
    ) {
        // 顶栏：连接状态 + 标题
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                Text("加工控制", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
                Spacer(Modifier.padding(4.dp))
                val connColor = when {
                    snap.estopPressed -> Color(0xFFEF5350)
                    snap.connected -> Color(0xFF4CAF50)
                    else -> CncColors.TextDisabled
                }
                Box(Modifier.size(10.dp).background(connColor, RoundedCornerShape(5.dp)))
                Text(
                    when {
                        snap.estopPressed -> "急停状态"
                        snap.connected -> "已连接 · 模拟模式"
                        else -> "离线"
                    },
                    style = CncTypography.labelMedium, color = connColor
                )
            }
            Text("⚡ 模拟数据", style = CncTypography.labelMedium, color = Color(0xFFFFA500))
        }
        Spacer(Modifier.height(12.dp))

        // 三段式布局：左侧状态 | 中央 Jog 盘 | 右侧主轴/连接
        Row(Modifier.fillMaxSize()) {
            // ===== 左侧：实时状态 =====
            Column(
                Modifier.fillMaxSize()
                    .background(CncColors.Surface, RoundedCornerShape(8.dp))
                    .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text("实时状态", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                // 大字号轴位置
                Column(
                    Modifier.fillMaxWidth().background(CncColors.Panel, RoundedCornerShape(6.dp)).padding(12.dp)
                ) {
                    Text("轴位置 (mm)", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        AxisReadout("X (直径)", snap.xActualPositionMm)
                        AxisReadout("Z (轴向)", snap.zActualPositionMm)
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth().background(CncColors.Panel, RoundedCornerShape(6.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("主轴 RPM", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                        Text(
                            snap.spindleRpm?.toString() ?: "—",
                            style = CncTypography.titleMedium, color = if (snap.spindleRunning) Color(0xFF4CAF50) else CncColors.TextPrimary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("刀具", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                        Text("T${snap.currentToolNumber ?: "—"}", style = CncTypography.titleMedium, color = CncColors.TextPrimary)
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 报警
                if (snap.alarms.isNotEmpty()) {
                    Text("⚠ 报警", style = CncTypography.titleSmall, color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
                    snap.alarms.forEach {
                        Text("• $it", style = CncTypography.bodySmall, color = Color(0xFFEF5350))
                    }
                } else {
                    Text("✓ 无报警", style = CncTypography.bodySmall, color = Color(0xFF4CAF50))
                }

                Spacer(Modifier.height(12.dp))

                Text("消息: ${ui.message}", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
            }

            Spacer(Modifier.padding(8.dp))

            // ===== 中央：Jog 点动 =====
            Column(
                Modifier.fillMaxSize()
                    .background(CncColors.Surface, RoundedCornerShape(8.dp))
                    .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Jog 点动", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                // 步长选择
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    viewModel.jogStepOptions.forEach { step ->
                        FilterChip(
                            selected = ui.selectedStepMm == step,
                            onClick = { viewModel.setStep(step) },
                            label = { Text("${step}mm") }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                // Jog 十字盘（步长随上方选择器实时变化）
                JogPad(viewModel, ui.selectedStepMm)

                Spacer(Modifier.height(16.dp))

                // 受控停止
                OutlinedButton(onClick = { viewModel.requestControlledStop() }) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.padding(4.dp)); Text("受控停止")
                }
            }

            Spacer(Modifier.padding(8.dp))

            // ===== 右侧：主轴 + 连接 + 急停 =====
            Column(
                Modifier.fillMaxSize()
                    .background(CncColors.Surface, RoundedCornerShape(8.dp))
                    .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("主轴控制", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))

                var rpmText by remember { mutableStateOf("1200") }
                val rpm = rpmText.toIntOrNull() ?: 0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = rpmText,
                        onValueChange = { input -> rpmText = input.filter { it.isDigit() } },
                        label = { Text("RPM") }, modifier = Modifier, singleLine = true
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { viewModel.setSpindle(rpm, true) }, enabled = rpm > 0) { Text("M03 正转") }
                    OutlinedButton(onClick = { viewModel.setSpindle(rpm, false) }, enabled = rpm > 0) { Text("M04 反转") }
                }
                Button(
                    onClick = { viewModel.setSpindle(0, true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                ) { Text("M05 停") }

                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.HorizontalDivider(color = CncColors.Border)
                Spacer(Modifier.height(8.dp))

                Text("连接", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.toggleConnection(snap.connected)
                    }, modifier = Modifier) { Text(if (snap.connected) "断开" else "连接")
                    }
                    OutlinedButton(onClick = { viewModel.goHome() }, modifier = Modifier) { Text("回原点")
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 急停大按钮
                Button(
                    onClick = { showEstopConfirm = true },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(40.dp)
                ) {
                    Icon(Icons.Default.Build, null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.padding(4.dp))
                    Text("急停 E-STOP", style = CncTypography.titleLarge, fontWeight = FontWeight.Bold)
                }

                if (snap.estopPressed) {
                    OutlinedButton(
                        onClick = { viewModel.resetEmergencyStop() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("急停复位（确认现场安全后）") }
                }

                if (showEstopConfirm) {
                    AlertDialog(
                        onDismissRequest = { showEstopConfirm = false },
                        title = { Text("确认急停") },
                        text = { Text("急停将立即停止所有运动并进入 REAL_FAULT 状态。是否确认？") },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.emergencyStop()
                                showEstopConfirm = false
                            }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))) {
                                Text("确认急停", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = { TextButton(onClick = { showEstopConfirm = false }) { Text("取消") } }
                    )
                }
            }
        }
    }
}

// ============== 辅助 ==============

@Composable
private fun AxisReadout(label: String, value: Double?) {
    Column {
        Text(label, style = CncTypography.labelMedium, color = CncColors.TextSecondary)
        Text(
            value?.let { String.format("%.3f", it) } ?: "—",
            style = CncTypography.headlineLarge, color = CncColors.TextPrimary,
            fontWeight = FontWeight.Bold, fontSize = 32.sp
        )
    }
}

@Composable
private fun JogPad(vm: MachiningViewModel, step: Double) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Z+
        JogButton("Z+") { vm.startJog(JogAxis.Z, JogDirection.POSITIVE, step) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // X+
            JogButton("X+") { vm.startJog(JogAxis.X, JogDirection.POSITIVE, step) }
            // 中心点
            Box(Modifier.size(72.dp).background(CncColors.Panel, RoundedCornerShape(36.dp)),
                contentAlignment = Alignment.Center) {
                Text("JOG", style = CncTypography.labelMedium, color = CncColors.TextSecondary, fontWeight = FontWeight.Bold)
            }
            // X-
            JogButton("X-") { vm.startJog(JogAxis.X, JogDirection.NEGATIVE, step) }
        }
        // Z-
        JogButton("Z-") { vm.startJog(JogAxis.Z, JogDirection.NEGATIVE, step) }
    }
}

@Composable
private fun JogButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .background(CncColors.Primary, RoundedCornerShape(36.dp))
            .clickable(onClick = onClick)
            .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(36.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}
