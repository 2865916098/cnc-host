package com.cnc.host.ui.history

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 任务历史页面。
 *
 * ⚠️ 数据为模拟历史，不是生产数据库。
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = viewModel()
) {
    val taskEntities by viewModel.tasks.collectAsState(initial = emptyList())
    val tasks = remember(taskEntities) { taskEntities.map { it.toTaskItem() } }
    val success = tasks.count { it.status == HistoryStatus.SUCCESS }
    val failed = tasks.count { it.status == HistoryStatus.FAILED }
    val total = tasks.size
    val totalMin = tasks.sumOf { it.durationSec } / 60.0

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Text("任务历史", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("总任务", "$total", CncColors.Primary)
            StatCard("成功", "$success", Color(0xFF4CAF50))
            StatCard("失败", "$failed", Color(0xFFEF5350))
            StatCard("累计工时", "${String.format("%.0f", totalMin)} min", Color(0xFFFFA500))
        }
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().background(CncColors.Panel, RoundedCornerShape(6.dp)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("任务ID", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
            Text("零件", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
            Text("工艺", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
            Text("工时", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
            Text("操作员", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
            Text("状态", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
            Text("时间", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(tasks) { t ->
                TaskRow(t)
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color) {
    Column(
        Modifier.background(CncColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(12.dp)
    ) {
        Text(value, style = CncTypography.headlineMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = CncTypography.bodySmall, color = CncColors.TextSecondary)
    }
}

@Composable
private fun TaskRow(t: TaskItem) {
    val (bg, fg, label) = when (t.status) {
        HistoryStatus.PENDING_REVIEW -> Triple(Color(0xFF2A2A3A), Color(0xFF9E9E9E), "待审")
        HistoryStatus.READY -> Triple(Color(0xFF1A2A3A), Color(0xFF42A5F5), "就绪")
        HistoryStatus.RUNNING -> Triple(Color(0xFF1A3A2A), Color(0xFF66BB6A), "执行中")
        HistoryStatus.SUCCESS -> Triple(Color(0xFF1A3A2A), Color(0xFF4CAF50), "完成")
        HistoryStatus.FAILED -> Triple(Color(0xFF3A1A1A), Color(0xFFEF5350), "失败")
        HistoryStatus.ABORTED -> Triple(Color(0xFF3A3015), Color(0xFFFFA500), "中止")
    }
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(6.dp))
            .border(1.dp, fg.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(t.id, style = CncTypography.bodyMedium, color = CncColors.TextPrimary)
        Text(t.partName, style = CncTypography.bodyMedium, color = CncColors.TextPrimary)
        Text(t.planId, style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        Text("${String.format("%.1f", t.durationSec)}s", style = CncTypography.bodyMedium, color = CncColors.TextPrimary)
        Text(t.operator, style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        Text(label, style = CncTypography.bodyMedium, color = fg, fontWeight = FontWeight.Bold)
        Text(formatTime(t.finishedAt), style = CncTypography.bodySmall, color = CncColors.TextSecondary)
    }
}

private fun formatTime(ts: Long): String {
    val s = (System.currentTimeMillis() - ts) / 1000
    return when {
        s < 3600 -> "${s / 60}m 前"
        s < 86400 -> "${s / 3600}h 前"
        else -> "${s / 86400}d 前"
    }
}
