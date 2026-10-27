package com.cnc.host.ui.tools

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.persistence.ToolEntity
import com.cnc.host.persistence.cncDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

enum class ToolType(val label: String) {
    TURNING("车刀"), FINISH_TURNING("精车刀"), GROOVING("切槽刀"),
    THREADING("螺纹刀"), DRILLING("钻头"), CUT_OFF("切断刀")
}
enum class WearStatus(val label: String) {
    NEW("全新"), LIGHT("轻微"), MODERATE("中等"), CRITICAL("超限")
}

data class ToolItem(
    val id: String, val toolNumber: Int, val toolType: String, val name: String,
    val material: String, val wearStatus: String, val usageCount: Int, val notes: String
)

fun ToolEntity.toItem() = ToolItem(
    id = id, toolNumber = toolNumber, toolType = toolType, name = name,
    material = material, wearStatus = wearStatus, usageCount = usageCount, notes = notes
)

/** 刀具库 ViewModel（Room 持久化）。 */
class ToolViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = application.cncDb().toolDao()
    val tools: Flow<List<ToolEntity>> = dao.observeAll()

    fun add(toolNumber: Int, type: String, name: String, material: String = "CARBIDE") {
        viewModelScope.launch {
            dao.insert(
                ToolEntity(id = "t-${System.currentTimeMillis()}", toolNumber = toolNumber,
                    toolType = type, name = name, material = material)
            )
        }
    }

    fun updateWear(id: String, status: String) {
        viewModelScope.launch {
            val existing = dao.get(id) ?: return@launch
            dao.update(existing.copy(wearStatus = status))
        }
    }

    fun incrementUsage(id: String) {
        viewModelScope.launch {
            val existing = dao.get(id) ?: return@launch
            dao.update(existing.copy(usageCount = existing.usageCount + 1))
        }
    }

    fun remove(id: String) { viewModelScope.launch { dao.delete(id) } }
}
