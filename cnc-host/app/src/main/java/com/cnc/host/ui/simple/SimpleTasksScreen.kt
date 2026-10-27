package com.cnc.host.ui.simple

import android.text.format.DateFormat
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.cnc.host.persistence.DrawingEntity
import com.cnc.host.sync.SyncState
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 任务选择页（简易模式首页）。
 *
 * 只做一件事：从图纸任务里挑一个，进入预览下发。
 * 所有工艺参数来自服务端，平板不在此处编辑。
 */
@Composable
fun SimpleTasksScreen(
    onOpenPreview: () -> Unit,
    viewModel: SimpleTasksViewModel = viewModel()
) {
    val drawings by viewModel.drawings.collectAsState()
    val sync by viewModel.syncState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("选择加工任务", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "选图纸 → 看预览 → 下发开工。工艺参数由服务端统一维护，平板不需要设置。",
                    style = CncTypography.bodyMedium,
                    color = CncColors.TextSecondary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncBadge(sync)
                OutlinedButton(onClick = { viewModel.syncNow() }, enabled = !sync.syncing) {
                    Text(if (sync.syncing) "同步中…" else "同步配置")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (sync.lastError != null && !sync.online) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33FBBF24), RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                Text(
                    "离线：${sync.lastError}。仍可使用本地缓存继续加工。",
                    style = CncTypography.bodyMedium,
                    color = CncColors.Warning
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        if (drawings.isEmpty()) {
            EmptyTasks()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(drawings, key = { it.id }) { drawing ->
                    TaskCard(
                        drawing = drawing,
                        onStart = {
                            viewModel.select(drawing)
                            onOpenPreview()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncBadge(sync: SyncState) {
    val (text, color) = when {
        sync.syncing -> "同步中…" to CncColors.Warning
        !sync.configured -> "未连接服务端" to CncColors.TextSecondary
        sync.online -> "服务端在线 · v${sync.cachedVersion}" to CncColors.Success
        else -> "离线缓存 · v${sync.cachedVersion}" to CncColors.Warning
    }
    val last = sync.lastSyncAt
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .background(color, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(6.dp))
            Text(text, style = CncTypography.labelLarge, color = color)
        }
        if (last != null) {
            Text(
                "上次同步 ${DateFormat.format("MM-dd HH:mm", last)}",
                style = CncTypography.labelSmall,
                color = CncColors.TextSecondary
            )
        }
    }
}

@Composable
private fun TaskCard(drawing: DrawingEntity, onStart: () -> Unit) {
    val ready = drawing.dxfContent.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
            .background(CncColors.Surface, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    drawing.filename,
                    style = CncTypography.titleLarge,
                    color = CncColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "${drawing.layers} 图层 · ${drawing.entities} 实体 · " +
                        if (drawing.source == "SERVER") "服务端下发" else "本地导入",
                    style = CncTypography.bodyMedium,
                    color = CncColors.TextSecondary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Tag(if (ready) "可加工" else "缺少 DXF 内容", ready)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onStart,
                    enabled = ready,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CncColors.Primary,
                        disabledContainerColor = CncColors.SurfaceVariant
                    )
                ) {
                    Text("开始")
                }
            }
        }
        if (!ready) {
            Spacer(Modifier.height(8.dp))
            Text(
                "该图纸没有保存 DXF 原文，无法生成刀路。请在服务端重新上传 DXF 后同步。",
                style = CncTypography.bodyMedium,
                color = CncColors.Warning
            )
        }
    }
}

@Composable
private fun Tag(text: String, positive: Boolean) {
    val color = if (positive) CncColors.Success else CncColors.Warning
    Box(
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = CncTypography.labelSmall, color = color)
    }
}

@Composable
private fun EmptyTasks() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, CncColors.Border, RoundedCornerShape(10.dp))
            .background(CncColors.Panel, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("暂无可加工任务", style = CncTypography.titleLarge, color = CncColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "请在服务端管理后台「图纸任务」中添加 DXF 图纸，\n然后点击右上角「同步配置」。",
                style = CncTypography.bodyMedium,
                color = CncColors.TextSecondary
            )
        }
    }
}
