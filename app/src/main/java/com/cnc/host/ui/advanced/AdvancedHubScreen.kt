package com.cnc.host.ui.advanced

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.cnc.host.ui.components.KioskTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cnc.host.navigation.Screen
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

private data class HubCard(val screen: Screen, val description: String)

private val hubGroups = listOf(
    "服务端与连接" to listOf(
        HubCard(Screen.ServerSync, "服务端地址、管理口令、设备编号与同步"),
        HubCard(Screen.Device, "设备参数（服务端下发缓存）"),
        HubCard(Screen.Comm, "通讯参数（服务端下发缓存）"),
        HubCard(Screen.Settings, "本机设置与高级选项")
    ),
    "资料库（服务端维护）" to listOf(
        HubCard(Screen.Drawings, "DXF 图纸列表"),
        HubCard(Screen.DrawingParse, "DXF 解析与尺寸校正"),
        HubCard(Screen.Part, "零件与毛坯"),
        HubCard(Screen.Materials, "材料库"),
        HubCard(Screen.Tools, "刀具库"),
        HubCard(Screen.Process, "工艺方案与工序")
    ),
    "加工与调试" to listOf(
        HubCard(Screen.Cam, "生成刀路与 G-code"),
        HubCard(Screen.Simulation, "刀路仿真校验"),
        HubCard(Screen.Review, "加工前审核"),
        HubCard(Screen.Machining, "Jog 点动、主轴与急停"),
        HubCard(Screen.Io, "IO 点位监控"),
        HubCard(Screen.Alarms, "报警中心"),
        HubCard(Screen.History, "任务历史"),
        HubCard(Screen.Home, "设备总览")
    )
)

/**
 * 高级功能入口。
 *
 * 日常操作不需要进入这里；所有分区最终都会回到简易模式的 4 个页面。
 */
@Composable
fun AdvancedHubScreen(onNavigate: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("高级功能", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            "配置与调试入口。日常加工请回到「任务 / 预览下发 / 运行监控 / 报警状态」。",
            style = CncTypography.bodyMedium,
            color = CncColors.TextSecondary
        )

        Spacer(Modifier.height(16.dp))

        hubGroups.forEach { (title, cards) ->
            Text(title, style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            cards.chunked(2).forEach { rowCards ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowCards.forEach { card ->
                        HubCardView(card, onNavigate, Modifier.weight(1f))
                    }
                    if (rowCards.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun HubCardView(card: HubCard, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
            .background(CncColors.Surface, RoundedCornerShape(8.dp))
            .clickable { onNavigate(card.screen.route) }
            .padding(14.dp)
    ) {
        Text(
            card.screen.label,
            style = CncTypography.titleMedium,
            color = CncColors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(card.description, style = CncTypography.labelSmall, color = CncColors.TextSecondary)
    }
}

/**
 * 高级模式 PIN 对话框（默认 1234）。
 *
 * 仅用于防误触，不构成安全边界。
 */
@Composable
fun PinDialog(
    expectedPin: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CncColors.Surface,
        title = {
            Text("进入高级模式", style = CncTypography.titleLarge, color = CncColors.TextPrimary)
        },
        text = {
            Column {
                Text(
                    "高级模式包含设备与工艺配置，日常加工无需进入。",
                    style = CncTypography.bodyMedium,
                    color = CncColors.TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                KioskTextField(
                    value = input,
                    onValueChange = {
                        input = it.filter(Char::isDigit).take(8)
                        error = null
                    },
                    label = { Text("PIN") },
                    singleLine = true,
                    keyboardType = KeyboardType.NumberPassword,
                    isPassword = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error!!, style = CncTypography.labelSmall, color = CncColors.Danger)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (input == expectedPin) onSuccess() else error = "PIN 错误"
                },
                colors = ButtonDefaults.buttonColors(containerColor = CncColors.Primary)
            ) { Text("进入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = CncColors.TextSecondary)
            }
        }
    )
}
