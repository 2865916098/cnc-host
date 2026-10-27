package com.cnc.host.ui.part

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.cnc.host.persistence.BlankEntity
import com.cnc.host.persistence.PartEntity
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

private enum class Tab { PARTS, BLANKS }

/** 零件与毛坯页面（Room 持久化）。 */
@Composable
fun PartScreen(modifier: Modifier = Modifier, viewModel: PartViewModel = viewModel()) {
    val parts by viewModel.parts.collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var currentTab by remember { mutableStateOf(Tab.PARTS) }
    var blanks by remember { mutableStateOf<List<BlankEntity>>(emptyList()) }

    val selectedPart = parts.firstOrNull { it.id == selectedId } ?: parts.firstOrNull()

    LaunchedEffect(selectedPart?.id) {
        selectedPart?.let { blanks = viewModel.getBlanks(it.id) }
    }

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Text("零件与毛坯", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Text("共 ${parts.size} 件零件 · ${blanks.size} 根毛坯",
            style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        Spacer(Modifier.height(4.dp))

        TabRow(selectedTabIndex = currentTab.ordinal, containerColor = CncColors.Surface) {
            Tab(selected = currentTab == Tab.PARTS, onClick = { currentTab = Tab.PARTS },
                text = { Text("零件列表") })
            Tab(selected = currentTab == Tab.BLANKS, onClick = { currentTab = Tab.BLANKS },
                text = { Text("毛坯列表") })
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (currentTab) {
                    Tab.PARTS -> items(parts, key = { it.id }) { p ->
                        PartRow(p, selectedPart?.id == p.id,
                            onClick = { selectedId = p.id },
                            onDelete = { viewModel.removePart(p.id) })
                    }
                    Tab.BLANKS -> items(blanks, key = { it.id }) { b ->
                        BlankRow(b, onDelete = { viewModel.removeBlank(b.id) })
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    currentTab == Tab.PARTS && selectedPart != null -> PartDetail(selectedPart!!, blanks)
                    currentTab == Tab.PARTS -> EmptyHint("选择零件查看详情")
                    else -> EmptyHint("在零件详情中查看毛坯")
                }
            }
        }
    }
}

@Composable
private fun PartRow(p: PartEntity, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, if (selected) CncColors.Primary else CncColors.Border, RoundedCornerShape(8.dp))
            .background(CncColors.Surface, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(p.name, style = CncTypography.titleSmall, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text("材料 ${p.materialId}", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, null, tint = Color(0xFFEF5350))
        }
    }
}

@Composable
private fun BlankRow(b: BlankEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
            .background(CncColors.Surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text("φ${b.diameterMm} × ${b.lengthMm} mm",
                style = CncTypography.titleSmall, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text("夹持 ${b.clampType} · 材料 ${b.materialId}",
                style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, null, tint = Color(0xFFEF5350))
        }
    }
}

@Composable
private fun PartDetail(p: PartEntity, blanks: List<BlankEntity>) {
    Column(
        Modifier.fillMaxSize().background(CncColors.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
    ) {
        Text(p.name, style = CncTypography.titleLarge, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        DetailRow("零件ID", p.id)
        DetailRow("材料ID", p.materialId)
        DetailRow("备注", if (p.notes.isBlank()) "—" else p.notes)

        Spacer(Modifier.height(16.dp))
        Text("关联毛坯（${blanks.size}）", style = CncTypography.titleMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (blanks.isEmpty()) {
            Text("（暂无毛坯）", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
        } else {
            blanks.forEach { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("φ${b.diameterMm} × ${b.lengthMm} mm",
                        style = CncTypography.bodySmall, color = CncColors.TextPrimary)
                    Text(b.clampType, style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                }
            }
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
