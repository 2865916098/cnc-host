package com.cnc.host.ui.setup

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography
import kotlinx.coroutines.delay

/**
 * 首次启动科技感 Splash / 激活入口页。
 *
 * 深蓝到黑的渐变背景，中央为 Compose 自绘的动态元素（旋转双环 + 呼吸脉冲 + 扫描线），
 * 下方是带辉光的大号「开始激活」按钮。进入后 2 秒自动进入引导流程，
 * 点击按钮可立即进入。动画全部使用 Compose 自带 rememberInfiniteTransition，无第三方依赖。
 */
@Composable
fun SplashScreen(onEnter: () -> Unit) {
    // 2 秒无操作自动进入引导流程
    LaunchedEffect(Unit) {
        delay(2000)
        onEnter()
    }

    val transition = rememberInfiniteTransition(label = "splash")
    // 外环顺时针旋转
    val outerAngle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "outerRing"
    )
    // 内环逆时针旋转
    val innerAngle by transition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "innerRing"
    )
    // 中心呼吸脉冲（0.85 ~ 1.15 往复）
    val pulse by transition.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    // 扫描线纵向位置 0~1
    val scan by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "scan"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1437), Color(0xFF070B18), Color(0xFF04060C))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))

            // 动态圆环：外环弧 + 内环弧 + 中心脉冲圆 + 扫描线
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(240.dp)) {
                    val r = size.minDimension / 2f
                    // 中心呼吸脉冲
                    drawCircle(
                        color = CncColors.Primary.copy(alpha = 0.18f),
                        radius = r * 0.5f * pulse
                    )
                    drawCircle(
                        color = CncColors.Primary.copy(alpha = 0.35f),
                        radius = r * 0.28f * pulse,
                        style = Stroke(width = 3f)
                    )
                    // 外环弧（顺时针）
                    rotate(outerAngle) {
                        drawArc(
                            color = CncColors.Primary,
                            startAngle = 0f,
                            sweepAngle = 110f,
                            useCenter = false,
                            style = Stroke(width = 6f)
                        )
                        drawArc(
                            color = CncColors.Primary.copy(alpha = 0.35f),
                            startAngle = 180f,
                            sweepAngle = 60f,
                            useCenter = false,
                            style = Stroke(width = 6f)
                        )
                    }
                    // 内环弧（逆时针，稍小）
                    rotate(innerAngle) {
                        drawArc(
                            color = CncColors.Info.copy(alpha = 0.8f),
                            startAngle = 0f,
                            sweepAngle = 200f,
                            useCenter = false,
                            topLeft = Offset(r * 0.22f, r * 0.22f),
                            size = androidx.compose.ui.geometry.Size(r * 1.56f, r * 1.56f),
                            style = Stroke(width = 3f)
                        )
                    }
                    // 扫描线：上下往返扫过圆环区域，中间亮两端淡
                    val alpha = (1f - kotlin.math.abs(scan - 0.5f) * 2f) * 0.7f
                    drawLine(
                        color = CncColors.Primary.copy(alpha = alpha),
                        start = Offset(r * 0.2f, size.height * scan),
                        end = Offset(r * 1.8f, size.height * scan),
                        strokeWidth = 2f
                    )
                }
                // 圆环中心标题
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CNC Host", style = CncTypography.displayLarge, color = CncColors.TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text("智能制造 · 设备上位机", style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
                }
            }

            Spacer(Modifier.weight(1f))

            // 辉光「开始激活」按钮：背后用径向渐变模拟发光，无模糊依赖
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .height(110.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(CncColors.Primary.copy(alpha = 0.45f), Color.Transparent)
                            )
                        )
                )
                Button(
                    onClick = onEnter,
                    modifier = Modifier
                        .width(260.dp)
                        .height(64.dp),
                    shape = RoundedCornerShape(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CncColors.PrimaryVariant)
                ) {
                    Text("开始激活", style = CncTypography.titleLarge, color = Color.White)
                }
            }
            Spacer(Modifier.height(56.dp))
        }
    }
}
