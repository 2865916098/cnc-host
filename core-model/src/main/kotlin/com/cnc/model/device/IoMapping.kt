package com.cnc.model.device

/** IO 方向。 */
enum class IoDirection {
    INPUT,
    OUTPUT
}

/**
 * IO 点位映射。
 *
 * 手动 IO 操作必须有权限控制、互锁检查和确认机制。
 * 模拟模式可以测试 IO 流程，但模拟结果不能代替真实电气联调。
 */
data class IoMapping(
    val pointNumber: Int,
    val name: String,
    val direction: IoDirection,
    val isCriticalFeedback: Boolean = false,
    val relatedAutomationStep: String? = null,
    val description: String? = null
)
