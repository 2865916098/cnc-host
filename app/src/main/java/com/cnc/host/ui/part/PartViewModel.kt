package com.cnc.host.ui.part

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.persistence.BlankEntity
import com.cnc.host.persistence.PartEntity
import com.cnc.host.persistence.cncDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

enum class BlankClampType(val label: String) {
    CHUCK("卡盘"), COLLET("夹头"), CENTER("心轴")
}

data class PartItem(
    val id: String, val name: String, val materialId: String, val notes: String
)
data class BlankItem(
    val id: String, val partId: String, val diameterMm: Double,
    val lengthMm: Double, val materialId: String, val clampType: String
)

fun PartEntity.toItem() = PartItem(id = id, name = name, materialId = materialId, notes = notes)
fun BlankEntity.toItem() = BlankItem(id = id, partId = partId, diameterMm = diameterMm,
    lengthMm = lengthMm, materialId = materialId, clampType = clampType)

/** 零件/毛坯 ViewModel（Room 持久化）。 */
class PartViewModel(application: Application) : AndroidViewModel(application) {
    private val db = application.cncDb()
    val parts: Flow<List<PartEntity>> = db.partDao().observeAll()

    suspend fun getBlanks(partId: String): List<BlankEntity> = db.blankDao().getByPart(partId)

    fun addPart(name: String, materialId: String, notes: String = "") {
        viewModelScope.launch {
            db.partDao().insert(
                PartEntity(id = "p-${System.currentTimeMillis()}", name = name, materialId = materialId, notes = notes)
            )
        }
    }

    fun addBlank(partId: String, diameter: Double, length: Double, materialId: String, clampType: String) {
        viewModelScope.launch {
            db.blankDao().insert(
                BlankEntity(id = "b-${System.currentTimeMillis()}", partId = partId,
                    diameterMm = diameter, lengthMm = length, materialId = materialId, clampType = clampType)
            )
        }
    }

    fun removePart(id: String) {
        viewModelScope.launch { db.partDao().delete(id) }
    }

    fun removeBlank(id: String) {
        viewModelScope.launch { db.blankDao().delete(id) }
    }
}
