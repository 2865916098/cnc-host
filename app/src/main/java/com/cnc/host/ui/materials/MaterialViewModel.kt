package com.cnc.host.ui.materials

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.persistence.MaterialEntity
import com.cnc.host.persistence.cncDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

data class MaterialItem(
    val id: String, val name: String, val code: String, val density: Double,
    val hardness: String, val feedSpeed: Double, val feedPerRev: Double,
    val depth: Double, val enabled: Boolean, val notes: String
)

fun MaterialEntity.toItem() = MaterialItem(
    id = id, name = name, code = code, density = density, hardness = hardness,
    feedSpeed = recommendedCuttingSpeedMs, feedPerRev = recommendedFeedPerRev,
    depth = recommendedDepthMm, enabled = enabled, notes = notes
)

/** 材料库 ViewModel（Room 持久化）。 */
class MaterialViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = application.cncDb().materialDao()
    val materials: Flow<List<MaterialEntity>> = dao.observeAll()

    fun add(name: String, code: String = "") {
        viewModelScope.launch {
            dao.insert(MaterialEntity(id = "m-${System.currentTimeMillis()}", name = name, code = code))
        }
    }

    fun toggleEnabled(id: String) {
        viewModelScope.launch {
            val existing = dao.get(id) ?: return@launch
            dao.update(existing.copy(enabled = !existing.enabled))
        }
    }

    fun remove(id: String) { viewModelScope.launch { dao.delete(id) } }
}
