package com.cnc.host.ui.simulation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.cam.engine.CamEngine
import com.cnc.cam.engine.ContourExtractor
import com.cnc.dxf.parser.DxfParser
import com.cnc.dxf.parser.toGeometryModel
import com.cnc.model.cam.MotionType
import com.cnc.model.cam.ProcessStep
import com.cnc.model.cam.ProcessType
import com.cnc.model.cam.Toolpath
import com.cnc.model.drawing.GeometryModel
import com.cnc.model.drawing.Point2D
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 仿真 ViewModel。
 *
 * 负责：
 * 1. 从 sample.dxf 加载 GeometryModel + Toolpath（实际产品中由图纸和工艺步骤指定）
 * 2. 维护播放状态（当前段、播放/暂停、速度）
 * 3. 驱动定时更新
 *
 * 注意：所有仿真数据都标注 isSimulated = true，不得冒充真实设备数据。
 */
class SimulationViewModel : ViewModel() {

    companion object {
        private const val TAG = "SimulationViewModel"
        private val DEFAULT_SAMPLE_DXF = """0
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

    // ============ 状态 ============

    private val _uiState = MutableStateFlow(SimulationUiState())
    val uiState: StateFlow<SimulationUiState> = _uiState.asStateFlow()

    private var playbackJob: Job? = null

    init {
        loadDefaultSample()
    }

    /** 加载内置 sample.dxf 并生成刀路。 */
    fun loadDefaultSample() {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        try {
            val dxf = DxfParser.parseText(DEFAULT_SAMPLE_DXF)
            val geometry = dxf.toGeometryModel("sample-drawing")
            val contours = ContourExtractor.extract(geometry)

            if (contours.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "未提取到任何可加工轮廓"
                )
                return
            }

            val step = makeDefaultStep(ProcessType.ROUGH_TURNING)
            val tp = CamEngine.generate(
                contour = contours.first(),
                step = step,
                stockDiameter = 180.0,
                stockLength = 60.0,
                processPlanId = "sample-pp",
                toolNumber = 1
            )

            _uiState.value = SimulationUiState(
                loading = false,
                error = null,
                geometry = geometry,
                toolpath = tp,
                contours = contours,
                currentSegmentIndex = 0,
                playing = false,
                speed = 1.0f,
                toolPosition = tp.segments.firstOrNull()?.start ?: Point2D(0.0, 0.0),
                message = "加载成功：${contours.size} 轮廓, ${tp.segments.size} 刀路段 （模拟数据）"
            )
        } catch (e: Exception) {
            Log.e(TAG, "加载 sample.dxf 失败", e)
            _uiState.value = _uiState.value.copy(loading = false, error = e.message ?: "未知错误")
        }
    }

    // ============ 播放控制 ============

    fun play() {
        val state = _uiState.value
        if (state.playing) return
        if (state.toolpath == null) return
        // 如果已到末尾，从头开始
        if (state.currentSegmentIndex >= state.toolpath.segments.size - 1) {
            _uiState.value = _uiState.value.copy(currentSegmentIndex = 0)
        }
        _uiState.value = _uiState.value.copy(playing = true)
        startPlaybackLoop()
    }

    fun pause() {
        _uiState.value = _uiState.value.copy(playing = false)
        playbackJob?.cancel()
        playbackJob = null
    }

    fun togglePlay() {
        if (_uiState.value.playing) pause() else play()
    }

    /** 步进一段。 */
    fun stepForward() {
        val state = _uiState.value
        val tp = state.toolpath ?: return
        if (state.currentSegmentIndex >= tp.segments.size - 1) return
        advanceTo(state.currentSegmentIndex + 1, smooth = true)
    }

    /** 回到起始。 */
    fun reset() {
        pause()
        val state = _uiState.value
        val tp = state.toolpath ?: return
        _uiState.value = state.copy(
            currentSegmentIndex = 0,
            toolPosition = tp.segments.firstOrNull()?.start ?: Point2D(0.0, 0.0)
        )
    }

    fun setSpeed(speed: Float) {
        _uiState.value = _uiState.value.copy(speed = speed.coerceIn(0.1f, 10f))
    }

    /** 跳到指定段。 */
    fun seekTo(index: Int) {
        pause()
        advanceTo(index.coerceIn(0, (_uiState.value.toolpath?.segments?.size ?: 1) - 1), smooth = false)
    }

    // ============ 内部 ============

    private fun advanceTo(index: Int, smooth: Boolean) {
        val state = _uiState.value
        val tp = state.toolpath ?: return
        if (index >= tp.segments.size) {
            pause()
            return
        }
        val targetSegment = tp.segments[index]
        _uiState.value = state.copy(
            currentSegmentIndex = index,
            toolPosition = if (smooth) targetSegment.start else targetSegment.end
        )
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            while (isActive && _uiState.value.playing) {
                val state = _uiState.value
                val tp = state.toolpath ?: break
                val seg = tp.segments.getOrNull(state.currentSegmentIndex) ?: break
                val nextIdx = state.currentSegmentIndex + 1

                // 平滑插值：从 seg.start → seg.end 逐步推进
                // 段间用固定 duration（speed 调节）
                val durationMs = (800L / state.speed).toLong().coerceAtLeast(50)
                val steps = 10
                for (s in 1..steps) {
                    if (!isActive || !_uiState.value.playing) break
                    val t = s.toFloat() / steps
                    val pos = Point2D(
                        x = seg.start.x + (seg.end.x - seg.start.x) * t,
                        y = seg.start.y + (seg.end.y - seg.start.y) * t
                    )
                    _uiState.value = _uiState.value.copy(toolPosition = pos)
                    delay(durationMs / steps)
                }

                // 段结束 → 推进到下一段
                if (nextIdx >= tp.segments.size) {
                    _uiState.value = _uiState.value.copy(
                        playing = false,
                        toolPosition = seg.end
                    )
                    break
                } else {
                    _uiState.value = _uiState.value.copy(
                        currentSegmentIndex = nextIdx,
                        toolPosition = tp.segments[nextIdx].start
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
    }

    // ============ 工具 ============

    private fun makeDefaultStep(type: ProcessType): ProcessStep = ProcessStep(
        id = "step-1",
        type = type,
        toolId = "tool-1",
        cuttingSpeedMmPerMin = 150.0,
        spindleRpm = 1200,
        feedMmPerRev = 0.2,
        depthOfCutMm = 5.0,
        finishAllowanceMm = 0.5,
        approachDistanceMm = 10.0,
        retractDistanceMm = 10.0,
        safetyDistanceMm = 10.0,
        valid = true
    )
}

/** 仿真 UI 状态。 */
data class SimulationUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val geometry: GeometryModel? = null,
    val toolpath: Toolpath? = null,
    val contours: List<com.cnc.cam.engine.MachiningContour> = emptyList(),
    val currentSegmentIndex: Int = 0,
    val playing: Boolean = false,
    val speed: Float = 1.0f,
    val toolPosition: Point2D = Point2D(0.0, 0.0),
    val message: String? = null,
    val isSimulated: Boolean = true // 必须明确标注，不得冒充真实设备数据
) {
    val currentSegment: com.cnc.model.cam.ToolpathSegment?
        get() = toolpath?.segments?.getOrNull(currentSegmentIndex)

    val totalSegments: Int get() = toolpath?.segments?.size ?: 0
    val progressPercent: Float
        get() = if (totalSegments <= 1) 0f
                else currentSegmentIndex.toFloat() / (totalSegments - 1).toFloat()

    val motionTypeName: String
        get() = when (currentSegment?.motionType) {
            MotionType.RAPID -> "G00 快速"
            MotionType.LINEAR_FEED -> "G01 直线"
            MotionType.ARC_FEED -> "G02/G03 圆弧"
            null -> "-"
        }
}
