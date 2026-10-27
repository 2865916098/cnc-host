package com.cnc.host.data

import com.cnc.gcode.post.GcodeProgram
import com.cnc.gcode.post.MachineFamily
import com.cnc.model.cam.Toolpath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 最近一次 CAM 生成结果。
 *
 * CAM 页面生成刀路后写入这里，审核页读取并下发，
 * 避免审核页使用内置示例刀路而与 CAM 实际结果不一致。
 */
data class CamResult(
    val toolpath: Toolpath,
    val fanucGcode: GcodeProgram,
    val siemensGcode: GcodeProgram,
    val selectedMachine: MachineFamily,
    /** 刀路来源描述，例如图纸文件名或「内置示例 DXF」。 */
    val sourceLabel: String,
    /** 从图纸库加载时套用的尺寸校正；null 表示未套用。 */
    val correctionSummary: String? = null,
    val generatedAt: Long = System.currentTimeMillis()
) {
    /** 当前选定机床对应的 G-code。 */
    val gcode: GcodeProgram
        get() = when (selectedMachine) {
            MachineFamily.SIEMENS_840D -> siemensGcode
            else -> fanucGcode
        }
}

/** 进程内唯一的 CAM 结果持有者（CAM 与审核页共用）。 */
object CamSession {

    private val _result = MutableStateFlow<CamResult?>(null)
    val result: StateFlow<CamResult?> = _result.asStateFlow()

    fun publish(result: CamResult) {
        _result.value = result
    }

    /** 切换机床族时同步更新，使审核页与 CAM 页显示同一份 G-code。 */
    fun reselectMachine(family: MachineFamily) {
        _result.update { it?.copy(selectedMachine = family) }
    }

    fun clear() {
        _result.value = null
    }
}
