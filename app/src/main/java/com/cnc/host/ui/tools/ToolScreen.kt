package com.cnc.host.ui.tools

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.host.persistence.ToolEntity
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/** 刀具库页面（Room 持久化）。 */
@Composable
fun ToolScreen(modifier: Modifier = Modifier, viewModel: ToolViewModel = viewModel()) {
    val tools by viewModel.tools.collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<String?>(null) }

    val filtered = tools.filter { filter == null || it.toolType == filter }
    val selected = tools.firstOrNull { it.id == selectedId } ?: filtered.firstOrNull()

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Text("刀具库", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Text("共 ${tools.size} 把 · ${if (filter != null) "已过滤 $filter" else "全部"}",
            style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null },
                label = { Text("全部 (${tools.size})") })
            val types = tools.map { it.toolType }.distinct()
            types.forEach { t ->
                val count = tools.count { it.toolType == t }
                FilterChip(selected = filter == t, onClick = { filter = if (filter == t) null else t },
                    label = { Text("$t ($count)") })
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { t ->
                    ToolRow(t, selected?.id == t.id,
                        onClick = { selectedId = t.id },
                        onDelete = { viewModel.remove(t.id) },
                        onWear = { viewModel.updateWear(t.id, it) }
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                if (selected != null) ToolDetail(selected!!, viewModel)
                else EmptyHint("选择刀具查看详情")
            }
        }
    }
}

private fun wearColor(s: String): Color = when (s) {
    "NEW" -> Color(0xFF4CAF50)
    "LIGHT" -> Color(0xFFFFB300)
    "MODERATE" -> Color(0xFFFF7043)
    else -> Color(0xFFEF5350)
}

@Composable
private fun ToolRow(
    t: ToolEntity, selected: Boolean, onClick: () -> Unit,
    onDelete: () -> Unit, onWear: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, if (selected) CncColors.Primary else CncColors.Border, RoundedCornerShape(8.dp))
            .background(CncColors.Surface, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.padding(end = 12.dp)) {
            Text("T${t.toolNumber}", style = CncTypography.titleMedium,
                color = CncColors.Primary, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxWidth()) {
            Text(t.name, style = CncTypography.titleSmall, color = CncColors.TextPrimary)
            Text("${t.toolType} · ${t.material}",
                style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        }
        Text(t.wearStatus, style = CncTypography.labelMedium,
            color = wearColor(t.wearStatus), fontWeight = FontWeight.Bold)
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, null, tint = Color(0xFFEF5350))
        }
    }
}

@Composable
private fun ToolDetail(t: ToolEntity, vm: ToolViewModel) {
    Column(
        Modifier.fillMaxSize().background(CncColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
    ) {
        Text("T${t.toolNumber} · ${t.name}", style = CncTypography.titleLarge, color = CncColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        DetailRow("刀具ID", t.id)
        DetailRow("类型", t.toolType)
        DetailRow("刀具材料", t.material)
        DetailRow("最大 RPM", "${t.maxRpm}")
        DetailRow("最大进给", "${t.maxFeedMmPerMin} mm/min")
        DetailRow("使用次数", "${t.usageCount}")

        Spacer(Modifier.height(12.dp))
        Text("磨损状态", style = CncTypography.bodyMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("NEW" to "全新", "LIGHT" to "轻微", "MODERATE" to "中等", "CRITICAL" to "超限").forEach { (key, label) ->
                FilterChip(selected = t.wearStatus == key,
                    onClick = { vm.updateWear(t.id, key) },
                    label = { Text(label) })
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.Button(onClick = { vm.incrementUsage(t.id) }) {
            Text("使用次数 +1")
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
