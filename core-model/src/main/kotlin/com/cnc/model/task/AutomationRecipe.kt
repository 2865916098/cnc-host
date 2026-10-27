package com.cnc.model.task

import com.cnc.model.common.VersionedEntity

/** 自动化步骤类型。 */
enum class AutomationStepType {
    LOAD_MATERIAL,
    CONFIRM_LOAD,
    CLAMP,
    CONFIRM_CLAMP,
    SPINDLE_PREPARE,
    ROUGH_TURN,
    FINISH_TURN,
    RETRACT,
    STOP_SPINDLE,
    UNCLAMP,
    CONFIRM_UNCLAMP,
    RECORD_RESULT
}

/**
 * 自动化流程配方（IO 流程模板）。
 *
 * 具体工序顺序必须由实际机床结构、送料装置和控制卡能力确定。
 */
data class AutomationRecipe(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val name: String,
    val steps: List<AutomationStep>
) : VersionedEntity

/** 单个自动化步骤定义。 */
data class AutomationStep(
    val id: String,
    val type: AutomationStepType,
    val name: String,
    val timeoutMs: Long,
    val requiresFeedback: Boolean,
    val feedbackInputPoint: Int? = null,
    val outputPoints: List<Int> = emptyList(),
    val retryable: Boolean = true,
    val allowsResume: Boolean = false
)
