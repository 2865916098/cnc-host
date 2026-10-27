package com.cnc.host.ui.drawings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/** 图纸管理页面（Room 图纸库 + DXF 文件导入）。 */
@Composable
fun DrawingsScreen(
    modifier: Modifier = Modifier,
    onOpenInCam: (String) -> Unit = {},
    vm: DrawingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entities by vm.items.collectAsState(initial = emptyList())
    val importMessage by vm.importMessage.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    val itemModels = remember(entities) { entities.map { it.toDrawingItem() } }
    val selectedItem = itemModels.firstOrNull { it.id == selectedId }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val filename = displayNameOf(context, uri)
        scope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream)).readText()
                    } ?: ""
                }
            } catch (_: Exception) {
                ""
            }
            withContext(Dispatchers.Main) { vm.importDxf(filename, text) }
        }
    }

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("图纸管理", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
                Spacer(Modifier.padding(4.dp))
                Text("共 ${itemModels.size} 份图纸", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Text("导入 DXF")
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().background(CncColors.Panel, RoundedCornerShape(6.dp)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("文件名", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
            Text("图层", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
            Text("实体", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
            Text("状态", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
            Text("修改时间", style = CncTypography.labelMedium, color = CncColors.TextSecondary)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(itemModels) { it ->
                val isSelected = it.id == selectedId
                val (bg, fg, label) = when (it.status) {
                    DrawingStatus.LOADED -> Triple(Color(0xFF1A3A2A), Color(0xFF4CAF50), "已加载")
                    DrawingStatus.PENDING -> Triple(CncColors.Panel, CncColors.TextSecondary, "待处理")
                    DrawingStatus.ERROR -> Triple(Color(0xFF3A1A1A), Color(0xFFEF5350), "错误")
                }
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isSelected) Color(0xFF1E2A3A) else bg, RoundedCornerShape(6.dp))
                        .clickable { vm.setSelected(it.id) }
                        .border(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) CncColors.Primary else fg.copy(alpha = 0.3f),
                            RoundedCornerShape(6.dp)
                        ).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(it.filename, style = CncTypography.bodyMedium, color = CncColors.TextPrimary, fontWeight = FontWeight.Bold)
                    Text("${it.layers}", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                    Text("${it.entities}", style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                    Text(label, style = CncTypography.bodySmall, color = fg, fontWeight = FontWeight.Bold)
                    Text(formatTime(it.lastModified), style = CncTypography.bodySmall, color = CncColors.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (selectedItem != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "已选：${selectedItem.filename}" +
                        if (selectedItem.hasContent) "" else "（无 DXF 内容）",
                    style = CncTypography.bodySmall,
                    color = CncColors.TextPrimary
                )
                OutlinedButton(
                    onClick = { onOpenInCam(selectedItem.id) },
                    enabled = selectedItem.hasContent
                ) { Text("用于 CAM") }
                OutlinedButton(onClick = { vm.remove(selectedItem.id) }) { Text("删除") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            importMessage ?: "支持导入 DXF 文本文件，解析结果会写入本地图纸库。",
            style = CncTypography.bodySmall,
            color = if (importMessage?.startsWith("导入失败") == true) Color(0xFFEF5350) else CncColors.TextSecondary,
            modifier = Modifier.clickable { vm.clearImportMessage() }
        )
    }
}

/** 从 SAF Uri 解析显示文件名；失败时回退为固定名。 */
private fun displayNameOf(context: Context, uri: Uri): String {
    var name = "imported.dxf"
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx) ?: name
            }
        }
    } catch (_: Exception) {
        // 查询失败时保留回退名
    }
    return name
}

private fun formatTime(ts: Long): String {
    val s = (System.currentTimeMillis() - ts) / 1000
    return when { s < 3600 -> "${s/60}m 前"; s < 86400 -> "${s/3600}h 前"; else -> "${s/86400}d 前" }
}
