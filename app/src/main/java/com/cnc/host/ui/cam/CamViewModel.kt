package com.cnc.host.ui.cam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.cam.engine.CamEngine
import com.cnc.cam.engine.ContourExtractor
import com.cnc.dxf.parser.DxfParser
import com.cnc.dxf.parser.toGeometryModel
import com.cnc.gcode.post.MachineFamily
import com.cnc.gcode.post.PostOptions
import com.cnc.gcode.post.fanuc.Fanuc0iPostProcessor
import com.cnc.gcode.post.siemens.Siemens840DPostProcessor
import com.cnc.host.data.CamResult
import com.cnc.host.data.CamSession
import com.cnc.host.persistence.cncDb
import com.cnc.host.persistence.DrawingCorrectionEntity
import com.cnc.model.cam.ProcessStep
import com.cnc.model.cam.ProcessType
import com.cnc.model.cam.Toolpath
import com.cnc.model.drawing.GeometryModel
import com.cnc.model.drawing.GeometryTransform
import com.cnc.model.drawing.Transform2D
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * CAM 刀路生成 ViewModel。
 *
 * 完整流水线：sample.dxf → GeometryModel → Contour → Toolpath → G-code
 *
 * ⚠️ 所有数据为模拟结果（isSimulated = true）。
 */
class CamViewModel(application: Application) : AndroidViewModel(application) {

    private val drawingDao = application.cncDb().drawingDao()
    private val correctionDao = application.cncDb().drawingCorrectionDao()

    companion object {
        private const val SAMPLE_DXF = """0
SECTION
2
HEADER
9
${"$"}ACADVER
1
AC1024
0
ENDSEC
0
SECTION
2
ENTITIES
0
LWPOLYLINE
8
OUTLINE
90
4
70
1
10
20.0
20
10.0
10
80.0
20
10.0
10
80.0
20
50.0
10
20.0
20
50.0
0
CIRCLE
8
HOLES
10
50.0
20
30.0
40
5.0
0
ENDSEC
0
EOF"""
    }

    private val _uiState = MutableStateFlow(CamUiState())
    val uiState: StateFlow<CamUiState> = _uiState.asStateFlow()

    init { runPipeline(SAMPLE_DXF) }

    /** 使用外部导入的 DXF 文本重新跑完整流水线。 */
    fun loadDxf(text: String) {
        runPipeline(text, sourceLabel = "外部导入 DXF")
    }

    /** 从图纸库加载指定图纸的 DXF 原文，并套用该图纸已保存的尺寸校正。 */
    fun loadDrawing(id: String) {
        viewModelScope.launch {
            val entity = drawingDao.getById(id)
            val text = entity?.dxfContent
            if (text.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "图纸「${entity?.filename ?: id}」没有保存 DXF 内容，请重新导入"
                )
                return@launch
            }
            val correction = correctionDao.get(id)
            val transform = correction?.let {
                Transform2D(
                    scale = if (it.scale > 0.0) it.scale else 1.0,
                    offsetX = it.offsetX,
                    offsetY = it.offsetY,
                    rotationDeg = it.rotationDeg,
                    mirrorX = it.mirrorX,
                    mirrorY = it.mirrorY
                )
            } ?: Transform2D.IDENTITY
            runPipeline(
                dxfText = text,
                transform = transform,
                correctionSummary = correction?.summarize(),
                sourceLabel = entity?.filename ?: "图纸 $id"
            )
        }
    }

    fun runPipeline(
        dxfText: String = SAMPLE_DXF,
        transform: Transform2D = Transform2D.IDENTITY,
        correctionSummary: String? = null,
        sourceLabel: String = "内置示例 DXF"
    ) {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        try {
            val dxf = DxfParser.parseText(dxfText)
            val baseGeom = dxf.toGeometryModel("cnc-host-imported")
            val geom = GeometryTransform.apply(baseGeom, transform)
            val contours = ContourExtractor.extract(geom)

            if (contours.isEmpty()) {
                _uiState.value = _uiState.value.copy(loading = false, error = "DXF 中未提取到可加工轮廓")
                return
            }

            val step = makeStep(ProcessType.ROUGH_TURNING)
            val tp = CamEngine.generate(
                contour = contours.first(), step = step,
                stockDiameter = 180.0, stockLength = 60.0,
                processPlanId = "cnc-host-pp", toolNumber = 1
            )

            val fanuc = Fanuc0iPostProcessor().generate(tp, PostOptions(lineNumberStep = 5))
            val siemens = Siemens840DPostProcessor().generate(tp, PostOptions(lineNumberStep = 10))

            _uiState.value = _uiState.value.copy(
                loading = false,
                geometry = geom,
                contours = contours,
                toolpath = tp,
                fanucGcode = fanuc,
                siemensGcode = siemens,
                selectedMachine = _uiState.value.selectedMachine,
                correctionSummary = correctionSummary
            )

            // 供审核页使用同一份刀路与 G-code，避免审核页另跑示例数据。
            CamSession.publish(
                CamResult(
                    toolpath = tp,
                    fanucGcode = fanuc,
                    siemensGcode = siemens,
                    selectedMachine = _uiState.value.selectedMachine,
                    sourceLabel = sourceLabel,
                    correctionSummary = correctionSummary
                )
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(loading = false, error = e.message ?: "未知错误")
        }
    }

    private fun DrawingCorrectionEntity.summarize(): String {
        val mirror = when {
            mirrorX && mirrorY -> "双轴镜像"
            mirrorX -> "X 镜像"
            mirrorY -> "Y 镜像"
            else -> "无镜像"
        }
        return "缩放 ${"%.3f".format(scale)}x · 偏移 (${"%.2f".format(offsetX)}, ${"%.2f".format(offsetY)}) · 旋转 ${"%.1f".format(rotationDeg)}° · $mirror"
    }

    fun setMachine(family: MachineFamily) {
        _uiState.value = _uiState.value.copy(selectedMachine = family)
        CamSession.reselectMachine(family)
    }

    private fun makeStep(type: ProcessType) = ProcessStep(
        id = "step-1", type = type, toolId = "T1",
        cuttingSpeedMmPerMin = 150.0, spindleRpm = 1200, feedMmPerRev = 0.2,
        depthOfCutMm = 5.0, finishAllowanceMm = 1.0,
        approachDistanceMm = 10.0, retractDistanceMm = 10.0,
        safetyDistanceMm = 10.0, valid = true
    )
}

data class CamUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val geometry: GeometryModel? = null,
    val contours: List<com.cnc.cam.engine.MachiningContour> = emptyList(),
    val toolpath: Toolpath? = null,
    val fanucGcode: com.cnc.gcode.post.GcodeProgram? = null,
    val siemensGcode: com.cnc.gcode.post.GcodeProgram? = null,
    val selectedMachine: MachineFamily = MachineFamily.FANUC_0I,
    /** 从图纸库加载时套用的尺寸校正描述；null 表示未套用。 */
    val correctionSummary: String? = null
) {
    val activeGcode: com.cnc.gcode.post.GcodeProgram?
        get() = when (selectedMachine) {
            MachineFamily.FANUC_0I -> fanucGcode
            MachineFamily.SIEMENS_840D -> siemensGcode
            else -> fanucGcode
        }
}
