package com.cnc.host.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 首次启动引导的步骤指示器：1.网络 → 2.激活 → 3.权限。
 *
 * 已完成的步骤显示绿色「✓」，当前步骤高亮为蓝色序号，未到的步骤为灰色。
 */
@Composable
fun SetupStepIndicator(currentStep: Int, modifier: Modifier = Modifier) {
    val steps = listOf("网络", "激活", "权限")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { index, label ->
            val done = index < currentStep
            val current = index == currentStep
            // 步骤圆点：序号或完成对勾
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(
                        when {
                            done -> CncColors.Success
                            current -> CncColors.Primary
                            else -> CncColors.SurfaceVariant
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (done) "✓" else "${index + 1}",
                    style = CncTypography.labelLarge,
                    color = if (done || current) CncColors.TextPrimary else CncColors.TextSecondary
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = CncTypography.labelLarge,
                color = when {
                    done -> CncColors.Success
                    current -> CncColors.Primary
                    else -> CncColors.TextSecondary
                }
            )
            if (index < steps.lastIndex) {
                Spacer(Modifier.width(10.dp))
                // 连接线
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(2.dp)
                        .background(if (done) CncColors.Success else CncColors.Border)
                )
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}
