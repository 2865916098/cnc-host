package com.cnc.host.ui.drawingparse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.dxf.parser.DxfParser
import com.cnc.dxf.parser.toGeometryModel
import com.cnc.host.persistence.cncDb
import com.cnc.host.persistence.DrawingCorrectionEntity
import com.cnc.model.drawing.BoundingBox
import com.cnc.model.drawing.GeometryModel
import com.cnc.model.drawing.GeometryTransform
import com.cnc.model.drawing.Transform2D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图纸尺寸校正 ViewModel。
 *
 * 真实的几何变换：从图纸库读取 DXF → 解析为 GeometryModel → 按校正参数变换，
 * 预览显示变换前后的实际包围盒。参数保存后，CAM 加载该图纸时自动套用。
 */
class DrawingParseViewModel(application: Application) : AndroidViewModel(application) {

    data class Correction(
        val scaleFactor: Double = 1.0,
        val offsetX: Double = 0.0,
        val offsetY: Double = 0.0,
        val rotationDeg: Double = 0.0,
        val mirrorX: Boolean = false,
        val mirrorY: Boolean = false,
        val unit: Unit = Unit.MM
    ) { enum class Unit { MM, INCH } }

    /** 图纸库中可校正的图纸（仅有 DXF 原文的）。 */
    data class DrawingOption(val id: String, val filename: String)

    /** 校正前后的真实几何统计。 */
    data class ParseResult(
        val entityCount: Int = 0,
        val layerCount: Int = 0,
        val sourceBounds: BoundingBox? = null,
        val correctedBounds: BoundingBox? = null,
        val warnings: List<String> = emptyList()
    )

    data class ParseUiState(
        val drawings: List<DrawingOption> = emptyList(),
        val selectedId: String? = null,
        val loading: Boolean = false,
        val error: String? = null,
        val result: ParseResult = ParseResult(),
        val appliedAt: Long? = null,
        val message: String? = null
    )

    private val drawingDao = application.cncDb().drawingDao()
    private val correctionDao = application.cncDb().drawingCorrectionDao()

    private val _correction = MutableStateFlow(Correction())
    val correction: StateFlow<Correction> = _correction.asStateFlow()

    private val _uiState = MutableStateFlow(ParseUiState())
    val uiState: StateFlow<ParseUiState> = _uiState.asStateFlow()

    /** 当前图纸的原始几何（未变换）。 */
    private var baseGeometry: GeometryModel? = null
    private var parseWarnings: List<String> = emptyList()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            drawingDao.observeAll().collect { list ->
                val options = list
                    .filter { it.dxfContent.isNotBlank() }
                    .map { DrawingOption(it.id, it.filename) }
                _uiState.update { state ->
                    val selected = state.selectedId
                    if (selected != null && options.none { it.id == selected }) {
                        state.copy(drawings = options, selectedId = null, result = ParseResult())
                    } else {
                        state.copy(drawings = options)
                    }
                }
                if (_uiState.value.selectedId == null && options.isNotEmpty()) {
                    selectDrawing(options.first().id)
                }
            }
        }
    }

    /** 选择要校正的图纸并解析。 */
    fun selectDrawing(id: String) {
        loadJob?.cancel()
        _uiState.update {
            it.copy(selectedId = id, loading = true, error = null, appliedAt = null, message = null)
        }
        loadJob = viewModelScope.launch {
            try {
                val entity = drawingDao.getById(id)
                val text = entity?.dxfContent
                if (text.isNullOrBlank()) {
                    _uiState.update { it.copy(loading = false, error = "该图纸没有保存 DXF 内容") }
                    return@launch
                }
                val parsed = withContext(Dispatchers.Default) { DxfParser.parseText(text) }
                val geom = parsed.toGeometryModel(id)
                baseGeometry = geom
                parseWarnings = parsed.warnings
                _correction.value = correctionDao.get(id)?.toCorrection() ?: Correction()
                _uiState.update { it.copy(loading = false) }
                recompute()
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "解析失败") }
            }
        }
    }

    /** 修改校正参数（立即重算预览）。 */
    fun update(fn: (Correction) -> Correction) {
        _correction.value = fn(_correction.value)
        _uiState.update { it.copy(appliedAt = null, message = null) }
        recompute()
    }

    /** 恢复默认参数（尚未保存到图纸）。 */
    fun reset() {
        _correction.value = Correction()
        _uiState.update { it.copy(appliedAt = null, message = "已恢复默认参数（未保存）") }
        recompute()
    }

    /** 保存校正参数到图纸；CAM 之后加载该图纸会自动套用。 */
    fun apply() {
        val id = _uiState.value.selectedId
        if (id == null) {
            _uiState.update { it.copy(message = "请先选择图纸") }
            return
        }
        val c = _correction.value
        if (c.scaleFactor <= 0.0) {
            _uiState.update { it.copy(message = "缩放系数必须大于 0") }
            return
        }
        viewModelScope.launch {
            correctionDao.upsert(c.toEntity(id))
            _uiState.update {
                it.copy(
                    appliedAt = System.currentTimeMillis(),
                    message = "校正已保存，CAM 加载该图纸时自动套用"
                )
            }
        }
    }

    /** 基于当前参数重算校正后的几何与包围盒。 */
    private fun recompute() {
        val base = baseGeometry ?: return
        val c = _correction.value
        if (c.scaleFactor <= 0.0) {
            _uiState.update {
                it.copy(
                    error = "缩放系数必须大于 0",
                    result = ParseResult(
                        entityCount = base.entities.size,
                        layerCount = base.byLayer.size,
                        sourceBounds = base.overallBounds,
                        correctedBounds = base.overallBounds,
                        warnings = parseWarnings
                    )
                )
            }
            return
        }
        val corrected = GeometryTransform.apply(base, c.toTransform())
        _uiState.update {
            it.copy(
                error = null,
                result = ParseResult(
                    entityCount = base.entities.size,
                    layerCount = base.byLayer.size,
                    sourceBounds = base.overallBounds,
                    correctedBounds = corrected.overallBounds,
                    warnings = parseWarnings
                )
            )
        }
    }

    private fun Correction.toTransform() = Transform2D(
        scale = scaleFactor,
        offsetX = offsetX,
        offsetY = offsetY,
        rotationDeg = rotationDeg,
        mirrorX = mirrorX,
        mirrorY = mirrorY
    )

    private fun Correction.toEntity(drawingId: String) = DrawingCorrectionEntity(
        drawingId = drawingId,
        scale = scaleFactor,
        offsetX = offsetX,
        offsetY = offsetY,
        rotationDeg = rotationDeg,
        mirrorX = mirrorX,
        mirrorY = mirrorY,
        unit = unit.name,
        updatedAt = System.currentTimeMillis()
    )

    private fun DrawingCorrectionEntity.toCorrection() = Correction(
        scaleFactor = scale,
        offsetX = offsetX,
        offsetY = offsetY,
        rotationDeg = rotationDeg,
        mirrorX = mirrorX,
        mirrorY = mirrorY,
        unit = runCatching { Correction.Unit.valueOf(unit) }.getOrDefault(Correction.Unit.MM)
    )
}
