package com.cnc.host.ui.simple

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.cam.engine.CamEngine
import com.cnc.cam.engine.ContourExtractor
import com.cnc.dxf.parser.DxfParser
import com.cnc.dxf.parser.toGeometryModel
import com.cnc.gcode.post.GcodeProgram
import com.cnc.gcode.post.PostOptions
import com.cnc.gcode.post.fanuc.Fanuc0iPostProcessor
import com.cnc.host.persistence.DrawingCorrectionEntity
import com.cnc.host.persistence.DrawingEntity
import com.cnc.host.persistence.cncDb
import com.cnc.host.simple.SimpleRunController
import com.cnc.host.simple.SimpleTaskSession
import com.cnc.model.cam.ProcessStep
import com.cnc.model.cam.ProcessType
import com.cnc.model.cam.Toolpath
import com.cnc.model.drawing.GeometryTransform
import com.cnc.model.drawing.Transform2D
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SimplePreviewUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val drawingName: String = "",
    val drawingId: String = "",
    val correctionSummary: String? = null,
    val toolpath: Toolpath? = null,
    val gcode: GcodeProgram? = null,
    val warnings: List<String> = emptyList(),
    /** 验证器未执行的校验项（如毛坯碰撞），向操作员明示，不影响 ready。 */
    val unverifiedChecks: List<String> = emptyList(),
    val ready: Boolean = false,
    val confirmClamp: Boolean = false,
    val started: Boolean = false,
    /** 毛坯参数目前为默认值，需在服务端工艺方案完善后接入。 */
    val stockNote: String = "毛坯 180×60 mm（默认值）"
)

/**
 * 预览与下发。
 *
 * 加载当前任务的 DXF 原文 → 套用图纸校正 → 生成刀路与 G-code。
 * 下发动作交给 SimpleRunController（页面切换后继续执行）。
 */
class SimplePreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val drawingDao = application.cncDb().drawingDao()
    private val correctionDao = application.cncDb().drawingCorrectionDao()

    private val _ui = MutableStateFlow(SimplePreviewUiState())
    val ui: StateFlow<SimplePreviewUiState> = _ui.asStateFlow()

    val runState = SimpleRunController.state

    init {
        viewModelScope.launch {
            SimpleTaskSession.selected.collect { drawing ->
                if (drawing == null) {
                    _ui.value = SimplePreviewUiState(error = "请先在「任务」页选择一个加工任务")
                } else {
                    generate(drawing)
                }
            }
        }
    }

    private suspend fun generate(drawing: DrawingEntity) {
        _ui.value = SimplePreviewUiState(
            loading = true,
            drawingName = drawing.filename,
            drawingId = drawing.id
        )
        if (drawing.dxfContent.isBlank()) {
            _ui.value = _ui.value.copy(
                loading = false,
                error = "该图纸没有 DXF 原文，无法生成刀路"
            )
            return
        }
        try {
            val correction = correctionDao.get(drawing.id)
            val transform = correction?.toTransform() ?: Transform2D.IDENTITY
            val summary = correction?.summarize()

            val parsed = DxfParser.parseText(drawing.dxfContent)
            val base = parsed.toGeometryModel("simple-${drawing.id}")
            val geometry = GeometryTransform.apply(base, transform)
            val contours = ContourExtractor.extract(geometry)
            if (contours.isEmpty()) {
                _ui.value = _ui.value.copy(loading = false, error = "DXF 中未提取到可加工轮廓")
                return
            }

            val step = ProcessStep(
                id = "simple-step", type = ProcessType.ROUGH_TURNING, toolId = "T1",
                cuttingSpeedMmPerMin = 150.0, spindleRpm = 1200, feedMmPerRev = 0.2,
                depthOfCutMm = 5.0, finishAllowanceMm = 1.0,
                approachDistanceMm = 10.0, retractDistanceMm = 10.0,
                safetyDistanceMm = 10.0, valid = true
            )
            // 与 CAM 页一致：毛坯尺寸暂为默认值，后续由服务端工艺方案提供
            val toolpath = CamEngine.generate(
                contour = contours.first(),
                step = step,
                stockDiameter = 180.0,
                stockLength = 60.0,
                processPlanId = drawing.id,
                toolNumber = 1
            )
            val gcode = Fanuc0iPostProcessor().generate(toolpath, PostOptions(lineNumberStep = 5))

            val ready = toolpath.validation.messages.isEmpty() && toolpath.validation.noIllegalCoordinates
            _ui.value = SimplePreviewUiState(
                loading = false,
                error = null,
                drawingName = drawing.filename,
                drawingId = drawing.id,
                correctionSummary = summary,
                toolpath = toolpath,
                gcode = gcode,
                warnings = toolpath.validation.messages,
                unverifiedChecks = toolpath.validation.unverifiedChecks,
                ready = ready,
                confirmClamp = _ui.value.confirmClamp,
                started = SimpleRunController.state.value.running
            )
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(
                loading = false,
                error = e.message ?: "刀路生成失败"
            )
        }
    }

    fun setConfirmClamp(value: Boolean) {
        _ui.value = _ui.value.copy(confirmClamp = value)
    }

    /** 下发并开始执行；返回是否成功启动。 */
    fun start(): Boolean {
        val ui = _ui.value
        val toolpath = ui.toolpath ?: return false
        if (!ui.ready || !ui.confirmClamp) return false
        if (SimpleRunController.state.value.running) return false
        SimpleRunController.start(
            context = getApplication(),
            taskName = ui.drawingName,
            drawingId = ui.drawingId,
            toolpath = toolpath
        )
        _ui.value = _ui.value.copy(started = true)
        return true
    }

    fun emergencyStop() = SimpleRunController.emergencyStop()

    fun resetRun() = SimpleRunController.reset()

    private fun DrawingCorrectionEntity.toTransform(): Transform2D = Transform2D(
        scale = if (scale > 0.0) scale else 1.0,
        offsetX = offsetX,
        offsetY = offsetY,
        rotationDeg = rotationDeg,
        mirrorX = mirrorX,
        mirrorY = mirrorY
    )

    private fun DrawingCorrectionEntity.summarize(): String {
        val mirror = when {
            mirrorX && mirrorY -> "双轴镜像"
            mirrorX -> "X 镜像"
            mirrorY -> "Y 镜像"
            else -> "无镜像"
        }
        return "缩放 ${"%.3f".format(scale)}x · 偏移 (${"%.2f".format(offsetX)}, " +
            "${"%.2f".format(offsetY)}) · 旋转 ${"%.1f".format(rotationDeg)}° · $mirror"
    }
}
