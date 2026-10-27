package com.cnc.host.ui.process

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.host.persistence.ProcessPlanEntity
import com.cnc.host.persistence.ProcessStepEntity
import com.cnc.host.persistence.cncDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

data class ProcessStepUi(
    val id: String, val planId: String, val orderIndex: Int, val type: String,
    val toolId: String, val depthOfCutMm: Double, val spindleRpm: Int,
    val feedMmPerRev: Double, val enabled: Boolean, val notes: String
)
fun ProcessStepEntity.toUi() = ProcessStepUi(
    id = id, planId = planId, orderIndex = orderIndex, type = type, toolId = toolId,
    depthOfCutMm = depthOfCutMm, spindleRpm = spindleRpm, feedMmPerRev = feedMmPerRev,
    enabled = enabled, notes = notes
)

data class ProcessPlanUi(
    val id: String, val name: String, val partId: String, val stockDiameter: Double,
    val stockLength: Double, val description: String, val steps: List<ProcessStepUi>
)

/** 工艺方案 ViewModel（Room 持久化）。 */
class ProcessViewModel(application: Application) : AndroidViewModel(application) {
    private val db = application.cncDb()

    val plans: Flow<List<ProcessPlanEntity>> = db.processPlanDao().observeAll()

    /** 取一个方案的步骤（挂起函数，Screen 层需 viewModelScope.launch）。 */
    suspend fun getSteps(planId: String): List<ProcessStepEntity> = db.processStepDao().getByPlan(planId)

    fun addPlan(name: String, partId: String, stockDiameter: Double = 100.0, stockLength: Double = 50.0) {
        viewModelScope.launch {
            db.processPlanDao().insert(
                ProcessPlanEntity(id = "pp-${System.currentTimeMillis()}", name = name,
                    partId = partId, stockDiameterMm = stockDiameter, stockLengthMm = stockLength)
            )
        }
    }

    fun addStep(planId: String, orderIndex: Int, type: String, toolId: String) {
        viewModelScope.launch {
            db.processStepDao().insert(
                ProcessStepEntity(id = "ps-${System.currentTimeMillis()}", planId = planId,
                    orderIndex = orderIndex, type = type, toolId = toolId)
            )
        }
    }

    fun removePlanAndSteps(planId: String) {
        viewModelScope.launch {
            db.processStepDao().deleteByPlan(planId)
            db.processPlanDao().delete(planId)
        }
    }
}
