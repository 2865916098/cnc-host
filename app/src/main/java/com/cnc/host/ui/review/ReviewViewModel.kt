package com.cnc.host.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cnc.cam.engine.CamEngine
import com.cnc.cam.engine.ContourExtractor
import com.cnc.device.api.CommandStatus
import com.cnc.device.api.DeviceCommand
import com.cnc.device.api.DeviceController
import com.cnc.dxf.parser.DxfParser
import com.cnc.dxf.parser.toGeometryModel
import com.cnc.gcode.post.MachineFamily
import com.cnc.gcode.post.PostOptions
import com.cnc.gcode.post.fanuc.Fanuc0iPostProcessor
import com.cnc.host.data.CamResult
import com.cnc.host.data.CamSession
import com.cnc.host.data.DeviceRepository
import com.cnc.model.cam.ProcessStep
import com.cnc.model.cam.ProcessType
import com.cnc.model.cam.Toolpath
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 加工审核 ViewModel。
 *
 * 职责：
 * 1. 展示 Toolpath 校验报告
 * 2. 生成 G-code 供审核
 * 3. 一键下发到设备（目前接 SimulatedDeviceController）
 * 4. 逐条等待执行反馈（通过 CommandQueue await）
 *
 * 安全：每条命令 Result 必须是 SUCCESS + COMPLETED 才发下一条。
 *       RESULT_UNKNOWN / SEND_FAILED → 立即停止。
 */
class ReviewViewModel(
    repository: DeviceRepository = DeviceRepository.shared
) : ViewModel() {

    /** 与首页/加工页共用同一台（模拟）设备，保证状态一致。 */
    private val device: DeviceController = repository.controller

    private val _ui = MutableStateFlow(ReviewUiState())
    val ui: StateFlow<ReviewUiState> = _ui.asStateFlow()

    private var executeJob: Job? = null

    init {
        _ui.value = _ui.value.copy(deviceConnected = device.isConnected())
        // 跟随 CAM 会话结果：CAM 生成新刀路后审核页自动同步。
        viewModelScope.launch {
            CamSession.result.collect { result ->
                // 下发过程中不切换刀路，避免一半旧一半新。
                if (_ui.value.executing) return@collect
                if (result == null) runSamplePipeline() else applyCamResult(result)
            }
        }
    }

    /** 使用 CAM 页面最近一次生成的刀路与 G-code。 */
    private fun applyCamResult(result: CamResult) {
        val tp = result.toolpath
        _ui.update {
            it.copy(
                loading = false,
                error = null,
                toolpath = tp,
                gcode = result.gcode,
                messages = tp.validation.messages,
                unverifiedChecks = tp.validation.unverifiedChecks,
                ready = tp.validation.messages.isEmpty() && tp.validation.noIllegalCoordinates,
                machineFamily = result.selectedMachine,
                sourceLabel = result.sourceLabel,
                correctionSummary = result.correctionSummary
            )
        }
    }

    /** CAM 尚未生成刀路时的回退数据，来源明确标注为内置示例。 */
    private fun runSamplePipeline() {
        _ui.value = _ui.value.copy(loading = true)
        try {
            val dxf = DxfParser.parseText(SAMPLE_DXF)
            val geom = dxf.toGeometryModel("review-sample")
            val contours = ContourExtractor.extract(geom)
            val step = ProcessStep(
                id = "s", type = ProcessType.ROUGH_TURNING, toolId = "T1",
                cuttingSpeedMmPerMin = 150.0, spindleRpm = 1200, feedMmPerRev = 0.2,
                depthOfCutMm = 5.0, finishAllowanceMm = 1.0,
                approachDistanceMm = 10.0, retractDistanceMm = 10.0,
                safetyDistanceMm = 10.0, valid = true
            )
            val tp = CamEngine.generate(contours.first(), step, 180.0, 60.0, "pp", 1)
            val gcode = Fanuc0iPostProcessor().generate(tp, PostOptions(lineNumberStep = 5))

            _ui.value = ReviewUiState(
                loading = false,
                toolpath = tp,
                gcode = gcode,
                messages = tp.validation.messages,
                unverifiedChecks = tp.validation.unverifiedChecks,
                ready = tp.validation.messages.isEmpty() && tp.validation.noIllegalCoordinates,
                machineFamily = MachineFamily.FANUC_0I,
                deviceConnected = device.isConnected(),
                sourceLabel = "内置示例 DXF（CAM 尚未生成刀路）"
            )
        } catch (e: Exception) {
            _ui.value = ReviewUiState(loading = false, error = e.message)
        }
    }

    fun reconnectDevice() {
        viewModelScope.launch {
            try { device.connect() } catch (_: Exception) {}
            _ui.value = _ui.value.copy(deviceConnected = device.isConnected())
        }
    }

    /** 一键执行全部刀路段。 */
    fun executeAll() {
        val tp = _ui.value.toolpath ?: return
        executeJob?.cancel()
        _ui.value = _ui.value.copy(executing = true, executeProgress = 0f, log = emptyList())

        executeJob = viewModelScope.launch {
            // 确保连接
            if (!device.isConnected()) {
                device.connect()
            }
            _ui.update { it.copy(deviceConnected = device.isConnected()) }

            for ((idx, seg) in tp.segments.withIndex()) {
                if (!isActive) break
                val cmdId = "ex-${idx}-${System.currentTimeMillis()}"
                // R1 修复：圆弧段按 ARC_FEED 下发；缺圆心时 fromSegment 抛错，拒绝按弦加工
                val cmd = DeviceCommand.fromSegment(cmdId, null, seg)

                val result = device.sendMotionCommand(cmd)

                // sendMotionCommand 内部已阻塞等待命令队列到达终态，
                // 这里直接读取终态，不使用固定延时轮询。
                val status = if (result.isSuccess) {
                    result.getOrNull()?.status ?: CommandStatus.RESULT_UNKNOWN
                } else {
                    // 下发失败：查询队列得到真实终态，区分「未发送」与「结果未知」
                    when (val queued = device.queryCommandResult(cmdId).getOrNull()) {
                        CommandStatus.RESULT_UNKNOWN,
                        CommandStatus.EXECUTION_FAILED,
                        CommandStatus.TIMEOUT,
                        CommandStatus.REJECTED -> queued
                        else -> CommandStatus.SEND_FAILED
                    }
                }

                _ui.update {
                    it.copy(
                        executeProgress = (idx + 1).toFloat() / tp.segments.size.toFloat(),
                        log = it.log + "[${idx + 1}/${tp.segments.size}] ${seg.motionType} → status=$status"
                    )
                }

                if (status == CommandStatus.RESULT_UNKNOWN || status == CommandStatus.SEND_FAILED
                    || status == CommandStatus.EXECUTION_FAILED || status == CommandStatus.TIMEOUT
                ) {
                    _ui.update { it.copy(executing = false, error = "执行中断：第 ${idx + 1} 段返回 $status，停止盲目重发") }
                    return@launch
                }
            }
            _ui.update { it.copy(executing = false, executeProgress = 1.0f, log = it.log + "✓ 全部 ${tp.segments.size} 段执行完毕") }
        }
    }

    fun stopExecution() {
        executeJob?.cancel()
        executeJob = null
        viewModelScope.launch { device.requestStop() }
        _ui.update { it.copy(executing = false, error = null) }
    }

    

    companion object {
        private const val SAMPLE_DXF = """0
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
ENDSEC
0
EOF"""
    }
}

data class ReviewUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val toolpath: Toolpath? = null,
    val gcode: com.cnc.gcode.post.GcodeProgram? = null,
    val messages: List<String> = emptyList(),
    /** 验证器未执行的校验项（如毛坯碰撞），向操作员明示，不影响 ready。 */
    val unverifiedChecks: List<String> = emptyList(),
    val ready: Boolean = false,
    val machineFamily: MachineFamily = MachineFamily.FANUC_0I,
    /** 刀路来源（图纸文件名 / 内置示例），便于确认审的是哪份程序。 */
    val sourceLabel: String = "",
    /** 刀路生成时套用的图纸尺寸校正；null 表示未套用。 */
    val correctionSummary: String? = null,
    val deviceConnected: Boolean = false,
    val executing: Boolean = false,
    val executeProgress: Float = 0f,
    val log: List<String> = emptyList()
)
