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
import com.cnc.host.persistence.ProcessPlanEntity
import com.cnc.host.persistence.ProcessStepEntity
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
    val ready: Boolean = false,
    val confirmClamp: Boolean = false,
    val started: Boolean = false,
    /** 工艺方案名称（来自服务端）；null 表示未关联方案，使用默认参数。 */
    val planName: String? = null,
    /** 毛坯尺寸说明，已从工艺方案读取。 */
    val stockNote: String = "毛坯（默认值）",
    /** 主轴转速，来自工艺步骤；下发时传给执行器。 */
    val spindleRpm: Int = 1200
)

/**
 * 预览与下发。
 *
 * 加载当前任务的 DXF 原文 → 套用图纸校正 → 读取关联工艺方案的毛坯与切削参数
 * → 生成刀路与 G-code。下发动作交给 SimpleRunController（页面切换后继续执行）。
 *
 * 若图纸未关联工艺方案（planId 为空），回退到默认参数并在 stockNote 中标注。
 */
class SimplePreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val drawingDao = application.cncDb().drawingDao()
    private val correctionDao = application.cncDb().drawingCorrectionDao()
    private val processPlanDao = application.cncDb().processPlanDao()
    private val processStepDao = application.cncDb().processStepDao()

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

            // 读取关联工艺方案：优先使用服务端下发的毛坯尺寸与切削参数
            val plan = drawing.planId.takeIf { it.isNotBlank() }?.let { processPlanDao.get(it) }
            val steps = plan?.let { processStepDao.getByPlan(it.id) }.orEmpty()
            val primaryStep = steps.firstOrNull { it.enabled } ?: steps.firstOrNull()

            val (step, stockDiameter, stockLength, planName) = resolveProcessParams(plan, primaryStep)
            val stockNote = buildStockNote(plan, stockDiameter, stockLength)

            val parsed = DxfParser.parseText(drawing.dxfContent)
            val base = parsed.toGeometryModel("simple-${drawing.id}")
            val geometry = GeometryTransform.apply(base, transform)
            val contours = ContourExtractor.extract(geometry)
            if (contours.isEmpty()) {
                _ui.value = _ui.value.copy(loading = false, error = "DXF 中未提取到可加工轮廓")
                return
            }

            val toolpath = CamEngine.generate(
                contour = contours.first(),
                step = step,
                stockDiameter = stockDiameter,
                stockLength = stockLength,
                processPlanId = plan?.id ?: drawing.id,
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
                ready = ready,
                confirmClamp = _ui.value.confirmClamp,
                started = SimpleRunController.state.value.running,
                planName = planName,
                stockNote = stockNote,
                spindleRpm = step.spindleRpm
            )
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(
                loading = false,
                error = e.message ?: "刀路生成失败"
            )
        }
    }

    /**
     * 解析工艺参数：优先使用服务端工艺方案，缺省时回退到默认值。
     * 返回 (ProcessStep, stockDiameter, stockLength, planName?)。
     */
    private fun resolveProcessParams(
        plan: ProcessPlanEntity?,
        step: ProcessStepEntity?
    ): ProcessParams {
        val stockDiameter = plan?.stockDiameterMm?.takeIf { it > 0.0 } ?: DEFAULT_STOCK_DIAMETER
        val stockLength = plan?.stockLengthMm?.takeIf { it > 0.0 } ?: DEFAULT_STOCK_LENGTH
        val processStep = if (step != null) step.toModel() else defaultStep()
        return ProcessParams(processStep, stockDiameter, stockLength, plan?.name)
    }

    private fun buildStockNote(plan: ProcessPlanEntity?, diameter: Double, length: Double): String {
        val base = "毛坯 ${"%.0f".format(diameter)}×${"%.0f".format(length)} mm"
        return if (plan != null) "$base（方案：${plan.name}）" else "$base（未关联工艺方案，使用默认值）"
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
            toolpath = toolpath,
            spindleRpm = ui.spindleRpm
        )
        _ui.value = _ui.value.copy(started = true)
        return true
    }

    fun emergencyStop() = SimpleRunController.emergencyStop()

    fun resetRun() = SimpleRunController.reset()

    private fun ProcessStepEntity.toModel(): ProcessStep = ProcessStep(
        id = id,
        type = runCatching { ProcessType.valueOf(type) }.getOrDefault(ProcessType.ROUGH_TURNING),
        toolId = toolId,
        cuttingSpeedMmPerMin = cuttingSpeedMmPerMin,
        spindleRpm = spindleRpm,
        feedMmPerRev = feedMmPerRev,
        depthOfCutMm = depthOfCutMm,
        finishAllowanceMm = finishAllowanceMm,
        approachDistanceMm = approachDistanceMm,
        retractDistanceMm = retractDistanceMm,
        safetyDistanceMm = safetyDistanceMm,
        valid = true
    )

    private fun defaultStep(): ProcessStep = ProcessStep(
        id = "simple-step", type = ProcessType.ROUGH_TURNING, toolId = "T1",
        cuttingSpeedMmPerMin = 150.0, spindleRpm = DEFAULT_SPINDLE_RPM, feedMmPerRev = 0.2,
        depthOfCutMm = 5.0, finishAllowanceMm = 1.0,
        approachDistanceMm = 10.0, retractDistanceMm = 10.0,
        safetyDistanceMm = 10.0, valid = true
    )

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

    private data class ProcessParams(
        val step: ProcessStep,
        val stockDiameter: Double,
        val stockLength: Double,
        val planName: String?
    )

    companion object {
        private const val DEFAULT_STOCK_DIAMETER = 180.0
        private const val DEFAULT_STOCK_LENGTH = 60.0
        private const val DEFAULT_SPINDLE_RPM = 1200
    }
}
