package com.cnc.host.ui.materials

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.host.persistence.MaterialEntity
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/** 材料库页面（Room 持久化）。 */
@Composable
fun MaterialScreen(modifier: Modifier = Modifier, viewModel: MaterialViewModel = viewModel()) {
    val materials by viewModel.materials.collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = materials.firstOrNull { it.id == selectedId } ?: materials.firstOrNull()

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Text("材料库", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Text("共 ${materials.size} 种 · ${materials.count { it.enabled }} 已启用",
            style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(materials, key = { it.id }) { m ->
                    MaterialRow(m, selected?.id == m.id,
                        onClick = { selectedId = m.id },
                        onDelete = { viewModel.remove(m.id) },
                        onToggle = { viewModel.toggleEnabled(m.id) }
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                if (selected != null) MaterialDetail(selected)
                else EmptyHint("选择材料查看详情")
            }
        }
    }
}

@Composable
private fun MaterialRow(
    m: MaterialEntity, selected: Boolean,
    onClick: () -> Unit, onDelete: () -> Unit, onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, if (selected) CncColors.Primary else CncColors.Border, RoundedCornerShape(8.dp))
            .background(if (!m.enabled) Color(0xFF2A2A3E) else CncColors.Surface, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(m.name, style = CncTypography.titleSmall,
                color = if (m.enabled) CncColors.TextPrimary else CncColors.TextDisabled)
            Text("切削速度 ${String.format("%.0f", m.recommendedCuttingSpeedMs)} m/s",
                style = CncTypography.bodySmall, color = CncColors.TextSecondary)
            Text("进给 ${String.format("%.2f", m.recommendedFeedPerRev)} mm/r · 切深 ${m.recommendedDepthMm} mm",
                style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        }
        Switch(checked = m.enabled, onCheckedChange = { onToggle() })
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, null, tint = Color(0xFFEF5350))
        }
    }
}

@Composable
private fun MaterialDetail(m: MaterialEntity) {
    Column(
        Modifier.fillMaxSize().background(CncColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
    ) {
        Text(m.name, style = CncTypography.titleLarge, color = CncColors.TextPrimary)
        Text("状态: ${if (m.enabled) "已启用" else "已禁用"}",
            style = CncTypography.bodyMedium,
            color = if (m.enabled) Color(0xFF4CAF50) else CncColors.TextDisabled)
        Spacer(Modifier.height(12.dp))
        DetailRow("材料ID", m.id)
        DetailRow("牌号", if (m.code.isBlank()) "—" else m.code)
        DetailRow("密度", "${m.density} g/cm³")
        DetailRow("硬度", if (m.hardness.isBlank()) "—" else m.hardness)
        DetailRow("推荐切削速度", "${String.format("%.0f", m.recommendedCuttingSpeedMs)} m/s")
        DetailRow("推荐进给", "${String.format("%.2f", m.recommendedFeedPerRev)} mm/r")
        DetailRow("推荐切深", "${m.recommendedDepthMm} mm")
        if (m.notes.isNotBlank()) {
            DetailRow("备注", m.notes)
        }
    }
}

@Composable
private fun DetailRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        Text(value, style = CncTypography.bodySmall, color = CncColors.TextPrimary)
    }
}

@Composable
private fun EmptyHint(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
    }
}
