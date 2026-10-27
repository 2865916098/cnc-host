package com.cnc.host.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cnc.model.device.DeviceSnapshot
import com.cnc.ui.components.StatusDotColor
import com.cnc.ui.components.StatusIndicator
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 顶部状态栏。
 *
 * 始终显示：软件名称和版本、设备名称、连接状态、当前模式、
 * 当前任务状态、当前报警摘要。
 */
@Composable
fun TopStatusBar(
    snapshot: DeviceSnapshot,
    modifier: Modifier = Modifier,
    onAdvanced: (() -> Unit)? = null,
    advancedLabel: String = "高级模式",
    onSettings: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(CncColors.Surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "CNC Host v0.1.0",
            style = CncTypography.titleMedium,
            color = CncColors.TextPrimary
        )
        VerticalDivider()
        Text(
            text = "设备: 模拟车床",
            style = CncTypography.bodyLarge,
            color = CncColors.TextSecondary
        )
        VerticalDivider()
        StatusIndicator(
            color = if (snapshot.connected) StatusDotColor.GREEN else StatusDotColor.GRAY,
            label = if (snapshot.connected) "已连接" else "未连接"
        )
        VerticalDivider()
        StatusIndicator(
            color = if (snapshot.isSimulated) StatusDotColor.ORANGE else StatusDotColor.BLUE,
            label = if (snapshot.isSimulated) "模拟模式" else "真实设备"
        )
        VerticalDivider()
        Text(
            text = "X: ${snapshot.xActualPositionMm?.let { "%.2f".format(it) } ?: "--"} mm",
            style = CncTypography.bodyLarge,
            color = CncColors.TextPrimary
        )
        Text(
            text = "Z: ${snapshot.zActualPositionMm?.let { "%.2f".format(it) } ?: "--"} mm",
            style = CncTypography.bodyLarge,
            color = CncColors.TextPrimary
        )
        VerticalDivider()
        StatusIndicator(
            color = if (snapshot.alarms.isEmpty()) StatusDotColor.GREEN else StatusDotColor.RED,
            label = if (snapshot.alarms.isEmpty()) "无报警" else "${snapshot.alarms.size} 报警"
        )
        Spacer(modifier = Modifier.weight(1f))
        if (onAdvanced != null) {
            Text(
                text = advancedLabel,
                style = CncTypography.labelLarge,
                color = CncColors.Info,
                modifier = Modifier
                    .clickable(onClick = onAdvanced)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        if (onSettings != null) {
            VerticalDivider()
            Row(
                modifier = Modifier
                    .clickable(onClick = onSettings)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = CncColors.TextSecondary
                )
                Text(
                    text = "设置",
                    style = CncTypography.labelLarge,
                    color = CncColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun VerticalDivider() {
    Divider(
        modifier = Modifier
            .height(24.dp)
            .width(1.dp),
        color = CncColors.Border
    )
}
