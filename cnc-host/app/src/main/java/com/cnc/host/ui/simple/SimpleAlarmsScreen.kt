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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.cnc.host.simple.RunPhase
import com.cnc.model.device.AlarmSeverity
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 报警与状态页。
 *
 * 只做展示和本机确认；不提供任何绕过急停/限位的操作。
 */
@Composable
fun SimpleAlarmsScreen(
    viewModel: SimpleAlarmsViewModel = viewModel()
) {
    val alarms by viewModel.alarms.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()
    val sync by viewModel.syncState.collectAsState()
    val run by viewModel.runState.collectAsState()
    val pending by viewModel.pendingCount.collectAsState()

    val activeCount = alarms.count { !it.acknowledged }

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
                Text("报警与状态", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (activeCount == 0) "当前没有未确认的设备报警" else "$activeCount 条报警待确认",
                    style = CncTypography.bodyMedium,
                    color = if (activeCount == 0) CncColors.Success else CncColors.Danger
                )
            }
            OutlinedButton(
                onClick = { viewModel.acknowledgeAll() },
                enabled = activeCount > 0
            ) { Text("全部确认（本机）") }
        }

        Spacer(Modifier.height(14.dp))

        // ===== 设备状态 =====
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                .background(CncColors.Surface, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("设备状态", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            StatusLine(
                if (snapshot.connected) "已连接" else "未连接",
                if (snapshot.connected) CncColors.Success else CncColors.TextSecondary,
                "连接"
            )
            StatusLine(
                if (snapshot.isSimulated) "模拟模式（数据为模拟）" else "真实设备",
                if (snapshot.isSimulated) CncColors.Simulator else CncColors.Info,
                "数据来源"
            )
            StatusLine(
                if (snapshot.estopPressed) "急停已按下" else "急停未触发",
                if (snapshot.estopPressed) CncColors.Danger else CncColors.Success,
                "急停"
            )
            StatusLine(
                "X ${snapshot.xActualPositionMm?.let { "%.2f".format(it) } ?: "--"} mm · " +
                    "Z ${snapshot.zActualPositionMm?.let { "%.2f".format(it) } ?: "--"} mm · " +
                    "主轴 ${snapshot.spindleRpm?.let { "$it rpm" } ?: "--"}",
                CncColors.TextPrimary,
                "实时位置"
            )
        }

        Spacer(Modifier.height(10.dp))

        // ===== 服务端同步 =====
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                .background(CncColors.Surface, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("服务端同步", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            StatusLine(
                when {
                    !sync.configured -> "未配置服务端"
                    sync.syncing -> "正在同步…"
                    sync.online -> "在线"
                    else -> "离线（使用本地缓存）"
                },
                when {
                    !sync.configured -> CncColors.TextSecondary
                    sync.online -> CncColors.Success
                    else -> CncColors.Warning
                },
                "连接"
            )
            StatusLine("配置版本 v${sync.cachedVersion}", CncColors.TextPrimary, "本地缓存")
            StatusLine(
                if (pending == 0) "无待补传任务" else "$pending 条任务等待补传",
                if (pending == 0) CncColors.Success else CncColors.Warning,
                "任务记录"
            )
            if (sync.lastError != null) {
                Spacer(Modifier.height(6.dp))
                Text(sync.lastError!!, style = CncTypography.labelSmall, color = CncColors.Warning)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "服务端只负责配置与报表；急停、限位、运动下发在本地设备侧闭环，离线不影响加工安全。",
                style = CncTypography.labelSmall,
                color = CncColors.TextSecondary
            )
        }

        Spacer(Modifier.height(10.dp))

        // ===== 加工状态 =====
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                .background(CncColors.Surface, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("加工状态", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            StatusLine(runPhaseLabel(run.phase), runPhaseColor(run.phase), "运行阶段")
            StatusLine(run.message, CncColors.TextPrimary, "说明")
            if (run.taskName.isNotBlank()) {
                StatusLine(run.taskName, CncColors.Info, "当前任务")
            }
        }

        Spacer(Modifier.height(14.dp))

        // ===== 报警列表 =====
        Text("设备报警", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        if (alarms.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
                    .background(CncColors.Panel, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("当前无设备报警", style = CncTypography.bodyLarge, color = CncColors.Success)
            }
        } else {
            alarms.forEach { alarm ->
                AlarmCard(alarm, onAck = { viewModel.acknowledge(alarm.code) })
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "「确认」只记录在本机（模拟模式下同时记录到模拟器），不等于故障已消除；" +
                "恢复加工前必须重新检查现场与设备状态。",
            style = CncTypography.labelSmall,
            color = CncColors.TextSecondary
        )
    }
}

@Composable
private fun StatusLine(value: String, valueColor: Color, label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
        Text(value, style = CncTypography.bodyMedium, color = valueColor)
    }
}

@Composable
private fun AlarmCard(alarm: SimpleAlarmItem, onAck: () -> Unit) {
    val accent = severityColor(alarm.severity)
    val alpha = if (alarm.acknowledged) 0.45f else 1.0f
    Row(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f * alpha), RoundedCornerShape(8.dp))
            .border(1.dp, accent.copy(alpha = 0.6f * alpha), RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                Modifier
                    .background(accent.copy(alpha = alpha), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    severityLabel(alarm.severity),
                    color = Color.White,
                    style = CncTypography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.padding(horizontal = 5.dp))
            Column {
                Text(
                    alarm.code,
                    style = CncTypography.titleMedium,
                    color = accent.copy(alpha = alpha),
                    fontWeight = FontWeight.Bold
                )
                Text(alarm.message, style = CncTypography.bodyMedium, color = CncColors.TextPrimary)
                Text(
                    "${formatAgo(alarm.occurredAt)} · 来源 ${alarm.source}",
                    style = CncTypography.labelSmall,
                    color = CncColors.TextSecondary
                )
            }
        }
        if (alarm.acknowledged) {
            Text("✓ 已确认（本机）", style = CncTypography.labelSmall, color = CncColors.Success)
        } else {
            Button(
                onClick = onAck,
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) { Text("确认") }
        }
    }
}

private fun severityColor(severity: AlarmSeverity): Color = when (severity) {
    AlarmSeverity.CRITICAL -> CncColors.Danger
    AlarmSeverity.ERROR -> CncColors.Danger
    AlarmSeverity.WARNING -> CncColors.Warning
    AlarmSeverity.INFO -> CncColors.Info
}

private fun severityLabel(severity: AlarmSeverity): String = when (severity) {
    AlarmSeverity.CRITICAL -> "严重"
    AlarmSeverity.ERROR -> "错误"
    AlarmSeverity.WARNING -> "警告"
    AlarmSeverity.INFO -> "提示"
}

private fun runPhaseLabel(phase: RunPhase): String = when (phase) {
    RunPhase.IDLE -> "就绪"
    RunPhase.RUNNING -> "加工中"
    RunPhase.PAUSED -> "已暂停"
    RunPhase.COMPLETED -> "已完成"
    RunPhase.FAILED -> "失败"
    RunPhase.STOPPED -> "已停止"
    RunPhase.ESTOPPED -> "急停"
}

private fun runPhaseColor(phase: RunPhase): Color = when (phase) {
    RunPhase.RUNNING -> CncColors.Warning
    RunPhase.PAUSED -> CncColors.Info
    RunPhase.COMPLETED -> CncColors.Success
    RunPhase.FAILED, RunPhase.ESTOPPED -> CncColors.Danger
    RunPhase.STOPPED -> CncColors.Info
    RunPhase.IDLE -> CncColors.TextSecondary
}

private fun formatAgo(ts: Long): String {
    val seconds = ((System.currentTimeMillis() - ts) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds} 秒前"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86400 -> "${seconds / 3600} 小时前"
        else -> "${seconds / 86400} 天前"
    }
}
