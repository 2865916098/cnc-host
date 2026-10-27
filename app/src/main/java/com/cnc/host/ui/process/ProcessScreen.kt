package com.cnc.host.ui.process

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.cnc.host.persistence.ProcessPlanEntity
import com.cnc.host.persistence.ProcessStepEntity
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/** 工艺方案页面（Room 持久化）。 */
@Composable
fun ProcessScreen(modifier: Modifier = Modifier, viewModel: ProcessViewModel = viewModel()) {
    val plans by viewModel.plans.collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var steps by remember { mutableStateOf<List<ProcessStepEntity>>(emptyList()) }

    val selected = plans.firstOrNull { it.id == selectedId } ?: plans.firstOrNull()

    LaunchedEffect(selected?.id) {
        selected?.let { steps = viewModel.getSteps(it.id) }
    }

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Text("工艺方案", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Text("共 ${plans.size} 套 · ${steps.size} 个步骤",
            style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(plans, key = { it.id }) { p ->
                    PlanRow(p, selected?.id == p.id,
                        onClick = { selectedId = p.id },
                        onDelete = { viewModel.removePlanAndSteps(p.id) }
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                if (selected != null) PlanDetail(selected!!, steps)
                else EmptyHint("选择方案查看步骤")
            }
        }
    }
}

@Composable
private fun PlanRow(p: ProcessPlanEntity, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, if (selected) CncColors.Primary else CncColors.Border, RoundedCornerShape(8.dp))
            .background(CncColors.Surface, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(p.name, style = CncTypography.titleSmall, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text("工件 ${p.partId} · 毛坯 φ${p.stockDiameterMm} × ${p.stockLengthMm}mm",
                style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, null, tint = Color(0xFFEF5350))
        }
    }
}

@Composable
private fun PlanDetail(p: ProcessPlanEntity, steps: List<ProcessStepEntity>) {
    Column(
        Modifier.fillMaxSize().background(CncColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
    ) {
        Text(p.name, style = CncTypography.titleLarge, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
        Text("工件 ${p.partId} · 毛坯 φ${p.stockDiameterMm} × ${p.stockLengthMm}mm",
            style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
        if (p.description.isNotBlank()) {
            Text(p.description, style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        }
        Spacer(Modifier.height(12.dp))

        Text("加工步骤", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (steps.isEmpty()) {
            Text("（暂无步骤）", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        } else {
            steps.forEach { s ->
                StepCard(s)
            }
        }
    }
}

private fun stepColor(type: String): Color = when {
    type.contains("ROUGH") -> Color(0xFFFF9800)
    type.contains("FINISH") -> Color(0xFF4CAF50)
    type.contains("GROOV") -> Color(0xFF2196F3)
    type.contains("THREAD") -> Color(0xFF9C27B0)
    type.contains("DRILL") -> Color(0xFF00BCD4)
    type.contains("CUT") -> Color(0xFFEF5350)
    else -> CncColors.Primary
}

@Composable
private fun StepCard(s: ProcessStepEntity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(1.dp, stepColor(s.type).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .background(stepColor(s.type).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("#${s.orderIndex}", Modifier.padding(end = 8.dp),
            style = CncTypography.labelMedium, color = stepColor(s.type), fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth()) {
            Text(s.type, style = CncTypography.bodyMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text("T${s.toolId} · S${s.spindleRpm} · F${String.format("%.2f", s.feedMmPerRev)} · 切深 ${s.depthOfCutMm}mm",
                style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        }
    }
}

@Composable
private fun EmptyHint(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
    }
}
