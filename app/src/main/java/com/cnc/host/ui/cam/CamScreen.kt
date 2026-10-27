package com.cnc.host.ui.cam

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.gcode.post.MachineFamily
import com.cnc.model.cam.MotionType
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * CAM 刀路生成页面。
 *
 * 左侧：流水线统计（DXF → Geometry → Contour → Toolpath → G-code）
 * 中央：Toolpath 分段表
 * 右侧：G-code 预览（可切换机床类型）
 */
@Composable
fun CamScreen(
    modifier: Modifier = Modifier,
    drawingId: String? = null,
    viewModel: CamViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 从图纸库进入时，按 id 加载对应 DXF；仅执行一次
    LaunchedEffect(drawingId) {
        drawingId?.let { viewModel.loadDrawing(it) }
    }

    val openDxfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).readText()
                } ?: ""
                if (text.isBlank()) {
                    withContext(Dispatchers.Main) { viewModel.loadDxf("") }
                    return@launch
                }
                withContext(Dispatchers.Main) { viewModel.loadDxf(text) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { viewModel.loadDxf("") }
            }
        }
    }

    val saveNcLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-nc")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val gcode = state.activeGcode?.toText() ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream).use { it.write(gcode) }
                }
            } catch (_: Exception) { }
        }
    }

    Column(
        modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("CAM 刀路生成", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("⚡ 模拟数据", style = CncTypography.labelMedium, color = Color(0xFFFFA500))
                OutlinedButton(onClick = { openDxfLauncher.launch(arrayOf("*/*")) }) { Text("导入 DXF") }
                OutlinedButton(
                    onClick = { state.activeGcode?.let { saveNcLauncher.launch("O${it.programNumber}.nc") } },
                    enabled = state.activeGcode != null
                ) { Text("导出 NC") }
                OutlinedButton(onClick = { viewModel.runPipeline() }) { Text("恢复示例") }
            }
        }
        state.correctionSummary?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "已套用图纸校正：$it",
                style = CncTypography.bodySmall,
                color = Color(0xFF4CAF50)
            )
        }
        Spacer(Modifier.height(12.dp))
        Divider(color = CncColors.Border)
        Spacer(Modifier.height(12.dp))

        when {
            state.loading -> Loading()
            state.error != null -> ErrorMsg(state.error!!)
            else -> CamContent(state, viewModel)
        }
    }
}

@Composable
private fun CamContent(state: CamUiState, vm: CamViewModel) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // ===== 左侧：流水线 =====
        Column(
            Modifier.fillMaxSize()
                .background(CncColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("流水线统计", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            PipelineStep("DXF 解析", "1 文件", ok = true)
            PipelineStep("几何实体", "${state.geometry?.entities?.size ?: 0}", ok = state.geometry?.entities?.isNotEmpty() == true)
            PipelineStep("加工轮廓", "${state.contours.size} 条", ok = state.contours.isNotEmpty())
            PipelineStep("刀路段", "${state.toolpath?.segments?.size ?: 0}", ok = (state.toolpath?.segments?.size ?: 0) > 0)
            PipelineStep("G-code 行", "${state.activeGcode?.lines?.size ?: 0}", ok = state.activeGcode?.lines?.isNotEmpty() == true)

            state.toolpath?.validation?.let { v ->
                if (v.messages.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("⚠ 刀路校验警告", style = CncTypography.titleSmall, color = Color(0xFFFFA500), fontWeight = FontWeight.Bold)
                    v.messages.forEach { Text("• $it", style = CncTypography.bodySmall, color = CncColors.TextSecondary) }
                } else {
                    Spacer(Modifier.height(12.dp))
                    Text("✓ 刀路校验通过", style = CncTypography.bodySmall, color = Color(0xFF4CAF50))
                }
            }
        }

        // ===== 中央：段表 =====
        Column(
            Modifier.fillMaxSize()
                .background(CncColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("刀路段", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            val tp = state.toolpath
            if (tp != null) {
                val counts = tp.segments.groupBy { it.motionType }.mapValues { it.value.size }
                Text(
                    "RAPID=${counts[MotionType.RAPID] ?: 0}  LINEAR=${counts[MotionType.LINEAR_FEED] ?: 0}" +
                            "  ARC=${counts[MotionType.ARC_FEED] ?: 0}",
                    style = CncTypography.bodySmall, color = CncColors.TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().background(CncColors.Panel, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("#", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                    Text("运动", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                    Text("起点", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                    Text("终点", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                    Text("F", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                }
                LazyColumn {
                    items(tp.segments.size) { idx ->
                        val s = tp.segments[idx]
                        val motionColor = when (s.motionType) {
                            MotionType.RAPID -> Color(0xFFFFA500)
                            MotionType.LINEAR_FEED -> Color(0xFF4CAF50)
                            MotionType.ARC_FEED -> Color(0xFF2196F3)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${idx + 1}", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                            Text(s.motionType.name, style = CncTypography.bodySmall, color = motionColor)
                            Text("(${fmt(s.start.x)}, ${fmt(s.start.y)})", style = CncTypography.bodySmall, color = CncColors.TextPrimary)
                            Text("(${fmt(s.end.x)}, ${fmt(s.end.y)})", style = CncTypography.bodySmall, color = CncColors.TextPrimary)
                            Text(
                                if (s.motionType != MotionType.RAPID && s.feedMmPerMin > 0) fmt(s.feedMmPerMin) else "—", style = CncTypography.bodySmall, color = CncColors.TextPrimary
                            )
                        }
                        if (idx < tp.segments.size - 1) Divider(color = CncColors.Border.copy(alpha = 0.3f))
                    }
                }
            } else {
                Text("无刀路数据", style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
            }
        }

        // ===== 右侧：G-code =====
        Column(
            Modifier.fillMaxSize()
                .background(CncColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("G-code 输出", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MachineFamily.values().forEach { f ->
                    FilterChip(
                        selected = state.selectedMachine == f,
                        onClick = { vm.setMachine(f) },
                        label = { Text(f.label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            val gcode = state.activeGcode
            if (gcode != null) {
                Text(
                    "O${gcode.programNumber} · ${gcode.instructionCount} 可执行指令",
                    style = CncTypography.bodySmall, color = CncColors.TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn {
                    items(gcode.lines.size) { idx ->
                        val line = gcode.lines[idx]
                        val isComment = line.trim().startsWith("(") || line.trim().startsWith("%")
                        val color = when {
                            line.contains("M03") || line.contains("M04") -> Color(0xFF4CAF50)
                            line.contains("M05") || line.contains("M09") -> Color(0xFFEF5350)
                            line.contains("M08") -> Color(0xFF2196F3)
                            line.contains("G00") -> Color(0xFFFFA500)
                            line.contains("G01") -> Color(0xFF4CAF50)
                            line.contains("G02") || line.contains("G03") -> Color(0xFF2196F3)
                            isComment -> CncColors.TextSecondary.copy(alpha = 0.6f)
                            else -> CncColors.TextPrimary
                        }
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 1.dp)
                                .background(if (isComment) CncColors.Panel.copy(alpha = 0.4f) else CncColors.Panel, RoundedCornerShape(3.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(line, style = CncTypography.bodySmall, color = color)
                        }
                    }
                }
            } else {
                Text("无 G-code 输出", style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun PipelineStep(label: String, value: String, ok: Boolean) {
    val color = if (ok) Color(0xFF4CAF50) else Color(0xFFEF5350)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Row {
            Text(if (ok) "✓" else "✗", color = color, style = CncTypography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(4.dp))
            Text(label, style = CncTypography.bodySmall, color = CncColors.TextPrimary)
        }
        Text(value, style = CncTypography.bodySmall, color = CncColors.TextSecondary)
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("生成中…", style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
    }
}

@Composable
private fun ErrorMsg(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("⚠ $msg", style = CncTypography.bodyMedium, color = Color(0xFFEF5350))
    }
}

private fun fmt(v: Double) = String.format("%.2f", v)
