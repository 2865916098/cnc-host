package com.cnc.host.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.host.navigation.Screen
import com.cnc.ui.components.CncPanel
import com.cnc.ui.components.StatusDotColor
import com.cnc.ui.components.StatusIndicator
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 首页与设备总览。
 *
 * 显示设备名称、通讯方式、连接状态、X/Z 轴位置、主轴状态、
 * 当前刀具、当前任务、任务进度、IO 摘要、最新报警、通讯状态等。
 */
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val snapshot by viewModel.snapshot.collectAsState()
    val connecting by viewModel.connecting.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 连接控制
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIndicator(
                color = if (snapshot.connected) StatusDotColor.GREEN else StatusDotColor.GRAY,
                label = if (snapshot.connected) "设备已连接" else "设备未连接"
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (!snapshot.connected) {
                Button(
                    onClick = { viewModel.connect() },
                    enabled = !connecting,
                    colors = ButtonDefaults.buttonColors(containerColor = CncColors.Primary)
                ) {
                    Text(if (connecting) "连接中.." else "连接模拟设备")
                }
            } else {
                Button(
                    onClick = { viewModel.disconnect() },
                    colors = ButtonDefaults.buttonColors(containerColor = CncColors.SurfaceVariant)
                ) {
                    Text("断开", color = CncColors.TextPrimary)
                }
            }
        }

        // 模拟模式明确标识
        if (snapshot.isSimulated) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CncColors.Simulator.copy(alpha = 0.15f))
                    .padding(8.dp)
            ) {
                Text(
                    text = "⚠ 当前为模拟模式。轴位置、主轴反馈、IO 状态和加工结果均为模拟数据，不得冒充真实设备数据。",
                    style = CncTypography.bodyMedium,
                    color = CncColors.Simulator
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 中央工作区：轴位置与主轴
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CncPanel(title = "轴位置") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        AxisDisplay("X 轴", snapshot.xActualPositionMm, snapshot.xTargetPositionMm)
                        AxisDisplay("Z 轴", snapshot.zActualPositionMm, snapshot.zTargetPositionMm)
                    }
                }
                CncPanel(title = "主轴 / 刀具") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        InfoCell("主轴转速", "${snapshot.spindleRpm ?: 0} rpm")
                        InfoCell("主轴状态", if (snapshot.spindleRunning) "运行" else "停止")
                        InfoCell("当前刀具", "#${snapshot.currentToolNumber ?: "-"}")
                    }
                }
                CncPanel(title = "任务入口") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EntryButton("新建加工任务", { onNavigate(Screen.Machining.route) })
                        EntryButton("导入图纸", { onNavigate(Screen.Drawings.route) })
                        EntryButton("刀路仿真", { onNavigate(Screen.Simulation.route) })
                    }
                }
            }

            // 右侧状态面板
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CncPanel(title = "IO 摘要") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        InfoCell("输入点", "${snapshot.inputs.size}")
                        InfoCell("输出点", "${snapshot.outputs.size}")
                        InfoCell("夹紧", if (snapshot.inputs[1] == true) "已夹紧" else "未夹紧")
                    }
                }
                CncPanel(title = "最新报警") {
                    if (snapshot.alarms.isEmpty()) {
                        StatusIndicator(StatusDotColor.GREEN, "无报警")
                    } else {
                        snapshot.alarms.take(5).forEach { code ->
                            Text(
                                text = code,
                                style = CncTypography.bodyMedium,
                                color = CncColors.Danger
                            )
                        }
                    }
                }
                CncPanel(title = "最近加工任务") {
                    Text(
                        text = "暂无加工记录",
                        style = CncTypography.bodyMedium,
                        color = CncColors.TextDisabled
                    )
                }
            }
        }
    }
}

@Composable
private fun AxisDisplay(name: String, actual: Double?, target: Double?) {
    Column {
        Text(text = name, style = CncTypography.titleMedium, color = CncColors.TextPrimary)
        Text(
            text = "实际: ${actual?.let { "%.3f".format(it) } ?: "--"} mm",
            style = CncTypography.bodyLarge,
            color = CncColors.TextSecondary
        )
        Text(
            text = "目标: ${target?.let { "%.3f".format(it) } ?: "--"} mm",
            style = CncTypography.bodyMedium,
            color = CncColors.TextSecondary
        )
    }
}

@Composable
private fun InfoCell(label: String, value: String) {
    Column {
        Text(text = label, style = CncTypography.labelSmall, color = CncColors.TextSecondary)
        Text(text = value, style = CncTypography.bodyLarge, color = CncColors.TextPrimary)
    }
}

@Composable
private fun EntryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = CncColors.PrimaryVariant)
    ) {
        Text(text, color = CncColors.TextPrimary)
    }
}
