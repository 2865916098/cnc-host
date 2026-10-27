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
import androidx.compose.material3.LinearProgressIndicator
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
 * 运行监控页。
 *
 * 只展示执行器状态并提供停止/急停；离开本页作业仍继续，
 * 因为执行器 SimpleRunController 不依赖页面生命周期。
 */
@Composable
fun SimpleMonitorScreen(
    onOpenPreview: () -> Unit,
    viewModel: SimpleMonitorViewModel = viewModel()
) {
    val run by viewModel.runState.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()
    val elapsed by viewModel.elapsedSec.collectAsState()
    val pending by viewModel.pendingCount.collectAsState()

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
                Text("运行监控", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (run.taskName.isBlank()) "暂无正在执行的作业" else run.taskName,
                    style = CncTypography.titleMedium,
                    color = CncColors.Info
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PhaseBadge(run.phase)
                Button(
                    onClick = { viewModel.emergencyStop() },
                    colors = ButtonDefaults.buttonColors(containerColor = CncColors.Danger),
                    modifier = Modifier.height(48.dp)
                ) { Text("急停", style = CncTypography.titleMedium) }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (run.phase == RunPhase.IDLE && run.startedAt == 0L) {
            EmptyMonitor(onOpenPreview)
            return@Column
        }

        // ===== 进度 =====
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                .background(CncColors.Panel, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    "${(run.progress * 100).toInt()}%",
                    style = CncTypography.displayLarge,
                    color = phaseColor(run.phase),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${run.sentSegments} / ${run.totalSegments} 段 · 已运行 ${formatElapsed(elapsed)}",
                    style = CncTypography.bodyMedium,
                    color = CncColors.TextSecondary
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { run.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(12.dp),
                color = phaseColor(run.phase),
                trackColor = CncColors.SurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text(run.message, style = CncTypography.bodyLarge, color = phaseColor(run.phase))
        }

        Spacer(Modifier.height(10.dp))

        // ===== 实时状态 =====
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                .background(CncColors.Surface, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("设备实时状态", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("X 轴", snapshot.xActualPositionMm?.let { "%.2f mm".format(it) } ?: "--", Modifier.weight(1f))
                Metric("Z 轴", snapshot.zActualPositionMm?.let { "%.2f mm".format(it) } ?: "--", Modifier.weight(1f))
                Metric("主轴", snapshot.spindleRpm?.let { "$it rpm" } ?: "--", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !snapshot.connected -> "设备未连接"
                    snapshot.isSimulated -> "模拟模式：轴位置与加工结果为模拟数据"
                    else -> "真实设备"
                },
                style = CncTypography.labelSmall,
                color = if (snapshot.isSimulated) CncColors.Simulator else CncColors.TextSecondary
            )
            if (pending > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "有 $pending 条任务记录等待补传服务端（离线期间不阻塞加工）",
                    style = CncTypography.labelSmall,
                    color = CncColors.Warning
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 操作 =====
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { viewModel.pause() },
                enabled = run.running,
                modifier = Modifier.height(52.dp)
            ) { Text("暂停") }
            OutlinedButton(
                onClick = { viewModel.resume() },
                enabled = run.phase == RunPhase.PAUSED,
                modifier = Modifier.height(52.dp)
            ) { Text("继续") }
            OutlinedButton(
                onClick = { viewModel.requestStop() },
                enabled = run.running || run.phase == RunPhase.PAUSED,
                modifier = Modifier.height(52.dp)
            ) { Text("受控停止") }
            OutlinedButton(
                onClick = { viewModel.resetEstop() },
                enabled = run.phase == RunPhase.ESTOPPED,
                modifier = Modifier.height(52.dp)
            ) { Text("急停复位") }
            OutlinedButton(
                onClick = { viewModel.clearResult() },
                enabled = !run.running && run.phase != RunPhase.PAUSED,
                modifier = Modifier.height(52.dp)
            ) { Text("清除结果") }
            OutlinedButton(onClick = onOpenPreview, modifier = Modifier.height(52.dp)) {
                Text("返回预览")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 执行日志 =====
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                .background(CncColors.Panel, RoundedCornerShape(8.dp))
                .padding(14.dp)
        ) {
            Text("执行日志", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (run.log.isEmpty()) {
                Text("暂无日志", style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
            } else {
                LazyColumn(modifier = Modifier.height(180.dp), reverseLayout = true) {
                    items(run.log.size) { index ->
                        val line = run.log[run.log.size - 1 - index]
                        Text(
                            line,
                            style = CncTypography.labelSmall,
                            color = when {
                                line.contains("COMPLETED") -> CncColors.Success
                                line.contains("FAILED") || line.contains("UNKNOWN") -> CncColors.Danger
                                else -> CncColors.TextSecondary
                            }
                        )
                    }
                }
            }
        }

        if (run.error != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(CncColors.Danger.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(12.dp)
            ) {
                Text("失败原因：${run.error}", style = CncTypography.bodyMedium, color = CncColors.Danger)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "急停由设备侧直接执行；服务端离线不影响急停、限位与运动下发。",
            style = CncTypography.labelSmall,
            color = CncColors.TextSecondary
        )
    }
}

@Composable
private fun EmptyMonitor(onOpenPreview: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .border(1.dp, CncColors.Border, RoundedCornerShape(10.dp))
            .background(CncColors.Panel, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("当前没有运行中的作业", style = CncTypography.titleLarge, color = CncColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "在「任务」页选择图纸并下发后会在这里显示进度。",
                style = CncTypography.bodyMedium,
                color = CncColors.TextSecondary
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onOpenPreview) { Text("去预览下发") }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(CncColors.Panel, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Text(label, style = CncTypography.labelSmall, color = CncColors.TextSecondary)
        Spacer(Modifier.height(2.dp))
        Text(value, style = CncTypography.titleMedium, color = CncColors.TextPrimary)
    }
}

@Composable
private fun PhaseBadge(phase: RunPhase) {
    val color = phaseColor(phase)
    val label = when (phase) {
        RunPhase.IDLE -> "就绪"
        RunPhase.RUNNING -> "加工中"
        RunPhase.PAUSED -> "已暂停"
        RunPhase.COMPLETED -> "已完成"
        RunPhase.FAILED -> "失败"
        RunPhase.STOPPED -> "已停止"
        RunPhase.ESTOPPED -> "急停"
    }
    Box(
        Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, style = CncTypography.labelLarge, color = color)
    }
}

private fun phaseColor(phase: RunPhase): Color = when (phase) {
    RunPhase.RUNNING -> CncColors.Warning
    RunPhase.PAUSED -> CncColors.Info
    RunPhase.COMPLETED -> CncColors.Success
    RunPhase.FAILED, RunPhase.ESTOPPED -> CncColors.Danger
    RunPhase.STOPPED -> CncColors.Info
    RunPhase.IDLE -> CncColors.TextSecondary
}

private fun formatElapsed(sec: Long): String =
    "%02d:%02d".format(sec / 60, sec % 60)
