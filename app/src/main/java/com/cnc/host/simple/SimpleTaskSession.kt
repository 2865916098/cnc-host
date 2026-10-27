package com.cnc.host.simple

import com.cnc.host.persistence.DrawingEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 简易模式当前选中的加工任务（图纸）。
 *
 * 任务选择页写入，预览/下发页读取；进程内共享，避免在两个页面之间
 * 通过路由参数传递整份图纸内容（DXF 原文可能很大）。
 */
object SimpleTaskSession {

    private val _selected = MutableStateFlow<DrawingEntity?>(null)
    val selected: StateFlow<DrawingEntity?> = _selected.asStateFlow()

    fun select(drawing: DrawingEntity) {
        _selected.value = drawing
    }

    fun clear() {
        _selected.value = null
    }
}
