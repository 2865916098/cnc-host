package com.cnc.host.ui.drawingparse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.model.drawing.BoundingBox
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 图纸尺寸校正页面。
 *
 * 从图纸库选择 DXF → 真实解析为几何 → 按参数变换并显示变换后的实际包围盒。
 * 保存后的参数会被 CAM 加载图纸时自动套用。
 */
@Composable
fun DrawingParseScreen(modifier: Modifier = Modifier, vm: DrawingParseViewModel = viewModel()) {
    val c by vm.correction.collectAsState()
    val state by vm.uiState.collectAsState()

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Text("图纸尺寸校正", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(12.dp))

        if (state.drawings.isEmpty()) {
            Text(
                "图纸库中暂无可校正的 DXF。请先在「图纸管理」导入 DXF 文件。",
                style = CncTypography.bodySmall, color = CncColors.TextSecondary
            )
            return@Column
        }

        // 图纸选择
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.drawings) { opt ->
                FilterChip(
                    selected = state.selectedId == opt.id,
                    onClick = { vm.selectDrawing(opt.id) },
                    label = { Text(opt.filename) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (state.loading) {
            Text("正在解析图纸…", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.fillMaxSize()
                    .background(CncColors.Surface, RoundedCornerShape(8.dp))
                    .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
            ) {
                Text("几何变换", style = CncTypography.titleMedium, fontWeight = FontWeight.Bold, color = CncColors.TextPrimary)
                Spacer(Modifier.height(12.dp))
                NumberField("缩放系数", c.scaleFactor) { vm.update { cfg -> cfg.copy(scaleFactor = it) } }
                NumberField("偏移 X (mm)", c.offsetX) { vm.update { cfg -> cfg.copy(offsetX = it) } }
                NumberField("偏移 Y (mm)", c.offsetY) { vm.update { cfg -> cfg.copy(offsetY = it) } }
                NumberField("旋转 (°)", c.rotationDeg) { vm.update { cfg -> cfg.copy(rotationDeg = it) } }

                Spacer(Modifier.height(8.dp))
                MirrorRow("X 轴镜像", c.mirrorX) { vm.update { cfg -> cfg.copy(mirrorX = it) } }
                MirrorRow("Y 轴镜像", c.mirrorY) { vm.update { cfg -> cfg.copy(mirrorY = it) } }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.apply() }, enabled = state.selectedId != null && !state.loading) {
                        Text("应用校正")
                    }
                    OutlinedButton(onClick = { vm.reset() }) { Text("重置") }
                }

                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = CncTypography.bodySmall, color = Color(0xFFEF5350))
                }
                state.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (state.appliedAt != null) "✓ $it" else it,
                        style = CncTypography.bodySmall,
                        color = if (state.appliedAt != null) Color(0xFF4CAF50) else CncColors.TextSecondary
                    )
                }
            }

            Column(
                Modifier.fillMaxSize()
                    .background(CncColors.Surface, RoundedCornerShape(8.dp))
                    .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
            ) {
                Text("实际计算结果", style = CncTypography.titleMedium, fontWeight = FontWeight.Bold, color = CncColors.TextPrimary)
                Spacer(Modifier.height(12.dp))
                PreviewRow("实体数", "${state.result.entityCount}")
                PreviewRow("图层数", "${state.result.layerCount}")
                PreviewRow("原始尺寸", boundsText(state.result.sourceBounds))
                PreviewRow("校正后尺寸", boundsText(state.result.correctedBounds))
                PreviewRow(
                    "尺寸变化",
                    deltaText(state.result.sourceBounds, state.result.correctedBounds)
                )
                PreviewRow("解析警告", "${state.result.warnings.size}")

                Spacer(Modifier.height(16.dp))
                Text(
                    "「应用校正」会把参数保存到该图纸；之后在图纸库点「用于 CAM」时自动套用。",
                    style = CncTypography.bodySmall, color = CncColors.TextSecondary
                )
                if (state.result.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ ${state.result.warnings.first()}",
                        style = CncTypography.bodySmall, color = Color(0xFFFFA500)
                    )
                }
            }
        }
    }
}

private fun boundsText(b: BoundingBox?): String =
    if (b == null) "—" else "%.2f × %.2f mm".format(b.width, b.height)

private fun deltaText(from: BoundingBox?, to: BoundingBox?): String {
    if (from == null || to == null) return "—"
    val dw = to.width - from.width
    val dh = to.height - from.height
    return "%+.2f × %+.2f mm".format(dw, dh)
}

@Composable
private fun NumberField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    // 外部值变化（如切换图纸）时同步输入框；用户正在输入时不打断
    LaunchedEffect(value) {
        if (text.toDoubleOrNull() != value) text = value.toString()
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = CncTypography.bodySmall, color = CncColors.TextPrimary)
        OutlinedTextField(value = text, onValueChange = { text = it; it.toDoubleOrNull()?.let(onChange) },
            modifier = Modifier, singleLine = true)
    }
}

@Composable
private fun MirrorRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = CncTypography.bodySmall, color = CncColors.TextPrimary)
        Switch(checked = checked, onCheckedChange = { onChange(it) })
    }
}

@Composable
private fun PreviewRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        Text(v, style = CncTypography.bodySmall, color = CncColors.TextPrimary)
    }
}
