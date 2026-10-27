package com.cnc.host.ui.simulation

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.model.cam.MotionType
import com.cnc.model.drawing.GeometryModel
import com.cnc.model.drawing.Point2D
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.PI

/**
 * 刀路仿真页面。
 *
 * Canvas 渲染零件轮廓 + 刀路轨迹 + 刀具 marker，
 * 右侧面板显示实时状态与播放控制。
 *
 * ⚠️ 所有仿真数据 isSimulated = true，不得冒充真实设备数据。
 */
@Composable
fun SimulationScreen(
    modifier: Modifier = Modifier,
    viewModel: SimulationViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("刀路仿真", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
            Spacer(Modifier.width(12.dp))
            Text(
                "⚡ 模拟数据",
                style = CncTypography.labelMedium,
                color = Color(0xFFFFA500),
                modifier = Modifier
                    .background(Color(0x33FFA500), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(12.dp))

        when {
            state.loading -> LoadingContent()
            state.error != null -> ErrorContent(state.error!!) { viewModel.loadDefaultSample() }
            state.toolpath != null -> SimulationContent(state, viewModel)
            else -> EmptyContent()
        }
    }
}

@Composable
private fun SimulationContent(
    state: SimulationUiState,
    viewModel: SimulationViewModel
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
            ) {
                ToolpathCanvas(state)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                InfoPanel(state)
            }
        }
        Spacer(Modifier.height(12.dp))
        PlaybackBar(state, viewModel)
    }
}

// ============== Canvas 渲染 ==============

/**
 * Canvas — 所有绘制都直接在 DrawScope 里完成，
 * 避免扩展函数 receiver 歧义。
 */
@Composable
private fun ToolpathCanvas(state: SimulationUiState) {
    val geometry = state.geometry ?: return
    val toolpath = state.toolpath ?: return

    Canvas(Modifier.fillMaxSize()) {
        val canvasW = size.width
        val canvasH = size.height

        // ---- bounds ----
        val bounds = computeCombinedBounds(geometry, toolpath)
        val pad = 40f
        val sx = (canvasW - 2 * pad) / maxOf(bounds.width, 1.0).toFloat()
        val sy = (canvasH - 2 * pad) / maxOf(bounds.height, 1.0).toFloat()
        val scale = minOf(sx, sy)
        val vcX = (bounds.minX + bounds.maxX) / 2
        val vcY = (bounds.minY + bounds.maxY) / 2

        fun w2c(p: Point2D): Offset {
            val x = ((p.x - vcX) * scale + canvasW / 2).toFloat()
            val y = ((vcY - p.y) * scale + canvasH / 2).toFloat()
            return Offset(x, y)
        }

        // ---- 网格 ----
        val gridStep = if (scale > 8) 10.0 else if (scale > 3) 20.0 else 50.0
        val gridColor = Color(0xFF2A2A3E)
        val startX = ceil(bounds.minX / gridStep) * gridStep
        var wx = startX
        while (wx <= bounds.maxX) {
            val cx = ((wx - vcX) * scale + canvasW / 2).toFloat()
            drawLine(gridColor, Offset(cx, 0f), Offset(cx, canvasH), strokeWidth = 1f)
            wx += gridStep
        }
        val startY = ceil(bounds.minY / gridStep) * gridStep
        var wy = startY
        while (wy <= bounds.maxY) {
            val cy = ((vcY - wy) * scale + canvasH / 2).toFloat()
            drawLine(gridColor, Offset(0f, cy), Offset(canvasW, cy), strokeWidth = 1f)
            wy += gridStep
        }

        // ---- 坐标轴 ----
        val ox = ((0.0 - vcX) * scale + canvasW / 2).toFloat()
        val oy = ((vcY - 0.0) * scale + canvasH / 2).toFloat()
        drawLine(Color(0x6644AA44), Offset(0f, oy), Offset(canvasW, oy), strokeWidth = 1.5f)
        drawLine(Color(0x66AA4444), Offset(ox, 0f), Offset(ox, canvasH), strokeWidth = 1.5f)

        // ---- 轮廓 ----
        geometry.entities.forEach { entity ->
            val pts = entityToPoints(entity)
            if (pts.size >= 2) {
                val path = Path()
                path.moveTo(w2c(pts[0]).x, w2c(pts[0]).y)
                for (i in 1 until pts.size) path.lineTo(w2c(pts[i]).x, w2c(pts[i]).y)
                if (pts.size >= 4 && pts.first() == pts.last()) path.close()
                drawPath(path, Color(0xFF4FC3F7), style = Stroke(width = 2f))
            }
        }

        // ---- 刀路 ----
        toolpath.segments.forEachIndexed { i, seg ->
            val color = when {
                i < state.currentSegmentIndex -> Color(0xFF66BB6A)
                i == state.currentSegmentIndex -> Color(0xFFEF5350)
                else -> Color(0xFF666666)
            }
            val width = if (i == state.currentSegmentIndex) 3f else 1.8f
            drawLine(color, w2c(seg.start), w2c(seg.end), strokeWidth = width)
            if (seg.motionType == MotionType.RAPID) {
                drawCircle(Color(0x88BBBBBB), radius = 2.5f, center = w2c(seg.start))
            }
        }

        // ---- 刀具 marker ----
        val tp = w2c(state.toolPosition)
        drawCircle(Color(0xFFFFEB3B), radius = 8f, center = tp, style = Stroke(width = 2.5f))
        drawCircle(Color(0xFFFFEB3B), radius = 2.5f, center = tp)
        drawLine(Color(0xFFFFEB3B), Offset(tp.x - 16f, tp.y), Offset(tp.x + 16f, tp.y), strokeWidth = 1.5f)
        drawLine(Color(0xFFFFEB3B), Offset(tp.x, tp.y - 16f), Offset(tp.x, tp.y + 16f), strokeWidth = 1.5f)
    }
}

// ============== 信息面板 ==============

@Composable
private fun InfoPanel(state: SimulationUiState) {
    val seg = state.currentSegment
    val tp = state.toolpath

    PanelCard("状态") {
        KV("几何实体", "${state.geometry?.entities?.size ?: 0}")
        KV("刀路段数", "${state.totalSegments}")
        KV("当前段", "${state.currentSegmentIndex + 1}/${state.totalSegments}")
        KV("运动类型", state.motionTypeName)
        val msgs = tp?.validation?.messages?.size ?: 0
        KV("校验", if (msgs == 0) "✓ 通过" else "$msgs 条警告")
    }
    Spacer(Modifier.height(8.dp))

    PanelCard("当前坐标") {
        KV("X (直径)", String.format("%.3f mm", state.toolPosition.x))
        KV("Z (轴向)", String.format("%.3f mm", state.toolPosition.y))
        seg?.let {
            KV("刀号 T", it.toolNumber.toString())
            KV("主轴 RPM", it.spindleRpm.toString())
            if (seg.motionType != MotionType.RAPID) {
                KV("进给", String.format("%.1f mm/min", it.feedMmPerMin))
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    PanelCard("刀具轨迹") {
        LinearProgressIndicator(
            progress = { state.progressPercent },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = Color(0xFFEF5350), trackColor = Color(0xFF333344)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "进度 ${(state.progressPercent * 100).toInt()}% — ${if (state.playing) "播放中 ▶" else "已暂停"}",
            style = CncTypography.labelMedium, color = CncColors.TextSecondary
        )
    }
    Spacer(Modifier.height(8.dp))

    PanelCard("说明") {
        Text(
            "绿色=已执行  红色=当前  灰色=待执行\n⚡ 所有数据为模拟结果",
            style = CncTypography.bodySmall, color = CncColors.TextSecondary,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun PanelCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CncColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(title, style = CncTypography.titleSmall, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun KV(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        Text(value, style = CncTypography.bodySmall, color = CncColors.TextPrimary)
    }
}

// ============== 播放控制 ==============

@Composable
private fun PlaybackBar(state: SimulationUiState, viewModel: SimulationViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CncColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { viewModel.reset() }) {
            Icon(Icons.Default.Refresh, "重置", tint = CncColors.TextPrimary)
        }
        IconButton(onClick = { viewModel.togglePlay() }) {
            Icon(
                if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (state.playing) "暂停" else "播放",
                tint = Color(0xFFEF5350)
            )
        }
        IconButton(onClick = { viewModel.stepForward() }) {
            Icon(Icons.Default.SkipNext, "步进", tint = CncColors.TextPrimary)
        }
        Spacer(Modifier.width(16.dp))

        Text("速度", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
        Slider(
            value = state.speed,
            onValueChange = { viewModel.setSpeed(it) },
            valueRange = 0.1f..5f,
            modifier = Modifier,
            colors = androidx.compose.material3.SliderDefaults.colors(
                activeTrackColor = Color(0xFFEF5350), thumbColor = Color(0xFFEF5350)
            )
        )
        Text("${String.format("%.1f", state.speed)}x", style = CncTypography.labelMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.width(16.dp))

        LinearProgressIndicator(
            progress = { state.progressPercent },
            modifier = Modifier.height(8.dp),
            color = Color(0xFFEF5350), trackColor = Color(0xFF333344)
        )
    }
}

// ============== 状态占位 ==============

@Composable
private fun LoadingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(Modifier.width(200.dp))
            Spacer(Modifier.height(12.dp))
            Text("正在加载刀路数据…", style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
        }
    }
}

@Composable
private fun ErrorContent(msg: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚠️ 加载失败", style = CncTypography.titleMedium, color = Color(0xFFEF5350))
            Spacer(Modifier.height(8.dp))
            Text(msg, style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
            Spacer(Modifier.height(16.dp))
            IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "重试") }
        }
    }
}

@Composable
private fun EmptyContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("暂无刀路数据", style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
    }
}

// ============== 工具 ==============

private data class RectBounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double) {
    val width get() = (maxX - minX).coerceAtLeast(1.0)
    val height get() = (maxY - minY).coerceAtLeast(1.0)
}

private fun computeCombinedBounds(
    geom: GeometryModel,
    tp: com.cnc.model.cam.Toolpath
): RectBounds {
    var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
    geom.entities.forEach { e ->
        val b = e.boundingBox()
        minX = minOf(minX, b.minX); maxX = maxOf(maxX, b.maxX)
        minY = minOf(minY, b.minY); maxY = maxOf(maxY, b.maxY)
    }
    tp.segments.forEach { s ->
        minX = minOf(minX, s.start.x, s.end.x); maxX = maxOf(maxX, s.start.x, s.end.x)
        minY = minOf(minY, s.start.y, s.end.y); maxY = maxOf(maxY, s.start.y, s.end.y)
    }
    if (minX == Double.POSITIVE_INFINITY) return RectBounds(-100.0, 100.0, -100.0, 100.0)
    val padX = (maxX - minX) * 0.1; val padY = (maxY - minY) * 0.1
    return RectBounds(minX - padX, maxX + padX, minY - padY, maxY + padY)
}

private fun entityToPoints(e: com.cnc.model.drawing.GeometryEntity): List<Point2D> = when (e) {
    is com.cnc.model.drawing.LineEntity -> listOf(e.start, e.end)
    is com.cnc.model.drawing.CircleEntity -> {
        (0..48).map { i ->
            val a = 2.0 * PI * i / 48
            Point2D(e.center.x + e.radius * cos(a), e.center.y + e.radius * sin(a))
        }
    }
    is com.cnc.model.drawing.ArcEntity -> {
        val steps = 24
        (0..steps).map { i ->
            val ang = e.startAngleDeg + e.sweepSign * e.sweepAngleDeg * i / steps
            val rad = Math.toRadians(ang)
            Point2D(e.center.x + e.radius * cos(rad), e.center.y + e.radius * sin(rad))
        }
    }
    is com.cnc.model.drawing.LwPolylineEntity -> e.vertices.map { it.point }
    else -> emptyList()
}
