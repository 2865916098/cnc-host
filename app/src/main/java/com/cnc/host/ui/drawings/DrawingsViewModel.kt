package com.cnc.host.ui.drawings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.dxf.parser.DxfParser
import com.cnc.host.persistence.cncDb
import com.cnc.host.persistence.DrawingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DrawingStatus { LOADED, PENDING, ERROR }

data class DrawingItem(
    val id: String, val filename: String, val layers: Int,
    val entities: Int, val lastModified: Long, val status: DrawingStatus,
    /** 是否保存了 DXF 原文（决定能否加载到 CAM）。 */
    val hasContent: Boolean = false
)

/** Entity → UI model。 */
fun DrawingEntity.toDrawingItem(): DrawingItem = DrawingItem(
    id = id, filename = filename, layers = layers, entities = entities,
    lastModified = createdAt,
    status = if (entities <= 0) DrawingStatus.ERROR else DrawingStatus.LOADED,
    hasContent = dxfContent.isNotBlank()
)

/** 图纸管理 ViewModel（Room 持久化 + DXF 文件导入）。 */
class DrawingsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = application.cncDb().drawingDao()
    private val correctionDao = application.cncDb().drawingCorrectionDao()
    val items: Flow<List<DrawingEntity>> = dao.observeAll()

    /** 最近一次导入的结果提示，null 表示无提示。 */
    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    /** 当前选中图纸 id（仅 UI 状态，Room 不持久化）。 */
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun clearImportMessage() { _importMessage.value = null }

    /** 导入 DXF 文本：解析后统计图层/实体数并写入图纸库。 */
    fun importDxf(filename: String, text: String) {
        if (text.isBlank()) {
            _importMessage.value = "导入失败：文件为空"
            return
        }
        try {
            val result = DxfParser.parseText(text)
            val layerCount = result.entities.map { it.layer }.distinct().size
            val entityCount = result.entities.size
            if (entityCount == 0) {
                _importMessage.value = "导入失败：未解析到任何实体"
                return
            }
            viewModelScope.launch {
                dao.insert(
                    DrawingEntity(
                        id = "d-${System.currentTimeMillis()}",
                        filename = filename,
                        layers = layerCount,
                        entities = entityCount,
                        source = "FILE_IMPORT",
                        dxfContent = text
                    )
                )
                _importMessage.value = "已导入 $filename（$layerCount 图层 / $entityCount 实体）"
            }
        } catch (e: Exception) {
            _importMessage.value = "导入失败：${e.message ?: "解析异常"}"
        }
    }

    fun add(filename: String, layers: Int, entities: Int) {
        viewModelScope.launch {
            dao.insert(
                DrawingEntity(
                    id = "d-${System.currentTimeMillis()}",
                    filename = filename, layers = layers, entities = entities
                )
            )
        }
    }

    fun remove(id: String) {
        if (_selectedId.value == id) _selectedId.value = null
        viewModelScope.launch {
            dao.delete(id)
            correctionDao.delete(id) // 同时清理该图纸的校正参数
        }
    }

    /** 点击图纸行：再次点击同一行取消选中。 */
    fun setSelected(id: String) {
        _selectedId.value = if (_selectedId.value == id) null else id
    }
}
