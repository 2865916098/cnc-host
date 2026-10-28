package com.cnc.host.simple

import android.content.Context
import com.cnc.device.api.CommandStatus
import com.cnc.device.api.DeviceCommand
import com.cnc.device.api.DeviceController
import com.cnc.host.data.DeviceRepository
import com.cnc.host.logging.LogRecorder
import com.cnc.host.persistence.CncDatabase
import com.cnc.host.persistence.TaskHistoryEntity
import com.cnc.host.sync.StatusDto
import com.cnc.host.sync.SyncManager
import com.cnc.model.cam.MotionType
import com.cnc.model.cam.Toolpath
import com.cnc.model.drawing.Point2D
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RunPhase { IDLE, RUNNING, COMPLETED, FAILED, STOPPED, ESTOPPED }

data class SimpleRunState(
    val taskName: String = "",
    val drawingId: String = "",
    val totalSegments: Int = 0,
    val sentSegments: Int = 0,
    val progress: Float = 0f,
    val phase: RunPhase = RunPhase.IDLE,
    val message: String = "就绪",
    val log: List<String> = emptyList(),
    val error: String? = null,
    val startedAt: Long = 0L,
    val finishedAt: Long? = null
) {
    val running: Boolean get() = phase == RunPhase.RUNNING
}

/**
 * 简易模式的加工作业执行器。
 *
 * 独立于页面生命周期：离开预览页进入监控页时作业继续执行。
 *
 * 安全边界：
 * - 急停直接调用设备实现（模拟器为 triggerEstop），不经过服务端
 * - 任一段返回 RESULT_UNKNOWN / SEND_FAILED / 执行失败立即中断，禁止盲目重发
 * - 不使用固定延时判断完成，全部依据命令队列终态
 */
object SimpleRunController {

    private val repo: DeviceRepository = DeviceRepository.shared
    private val device: DeviceController = repo.controller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(SimpleRunState())
    val state: StateFlow<SimpleRunState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var stopPhase: RunPhase? = null

    /** 开始执行刀路；同一时间只允许一个作业。 */
    fun start(context: Context, taskName: String, drawingId: String, toolpath: Toolpath) {
        if (_state.value.running) return
        job?.cancel()
        stopPhase = null
        val app = context.applicationContext
        val startedAt = System.currentTimeMillis()
        val segments = toolpath.segments

        LogRecorder.info(context, "TASK", "加工启动：$taskName，${segments.size} 段刀路", taskId = drawingId)

        _state.value = SimpleRunState(
            taskName = taskName,
            drawingId = drawingId,
            totalSegments = segments.size,
            phase = RunPhase.RUNNING,
            message = "正在连接设备…",
            startedAt = startedAt
        )
        scope.launch { reportStatus(app, _state.value) }

        job = scope.launch {
            var phase = RunPhase.FAILED
            var message = ""
            try {
                if (!device.isConnected()) {
                    device.connect()
                }
                if (!device.isConnected()) {
                    throw IllegalStateException("设备未连接，未下发任何运动指令")
                }

                // 主轴启动（模拟器按输入 RPM 记录）；失败立即中止
                val spindleCmd = DeviceCommand.setSpindle(
                    commandId = "simple-spindle-on-${System.currentTimeMillis()}",
                    taskId = drawingId,
                    rpm = DEFAULT_SPINDLE_RPM,
                    on = true,
                    clockwise = true
                )
                val spindleResult = device.sendMotionCommand(spindleCmd)
                if (spindleResult.isFailure) {
                    throw IllegalStateException("主轴启动失败：${spindleResult.exceptionOrNull()?.message ?: "未知原因"}")
                }

                _state.value = _state.value.copy(message = "加工中：M03 S$DEFAULT_SPINDLE_RPM")
                var lastReported = 0

                for ((idx, seg) in segments.withIndex()) {
                    if (!isActive) break
                    val target = Point2D(seg.end.x, seg.end.y)
                    val cmdId = "simple-${drawingId}-${idx}-${System.currentTimeMillis()}"
                    val cmd = when (seg.motionType) {
                        MotionType.RAPID -> DeviceCommand.rapidMove(cmdId, drawingId, target)
                        MotionType.LINEAR_FEED -> DeviceCommand.linearFeed(
                            cmdId, drawingId, target,
                            seg.feedMmPerMin.takeIf { it > 0.0 } ?: 100.0
                        )
                        MotionType.ARC_FEED -> DeviceCommand.linearFeed(cmdId, drawingId, target, 100.0)
                    }

                    val result = device.sendMotionCommand(cmd)
                    val status = if (result.isSuccess) {
                        result.getOrNull()?.status ?: CommandStatus.RESULT_UNKNOWN
                    } else {
                        when (val queued = device.queryCommandResult(cmdId).getOrNull()) {
                            CommandStatus.RESULT_UNKNOWN,
                            CommandStatus.EXECUTION_FAILED,
                            CommandStatus.TIMEOUT,
                            CommandStatus.REJECTED -> queued
                            else -> CommandStatus.SEND_FAILED
                        }
                    }

                    val progress = (idx + 1).toFloat() / segments.size.toFloat()
                    _state.value = _state.value.copy(
                        sentSegments = idx + 1,
                        progress = progress,
                        log = (_state.value.log + "[${idx + 1}/${segments.size}] ${seg.motionType} → $status").takeLast(80)
                    )

                    if (status != CommandStatus.COMPLETED) {
                        throw IllegalStateException("第 ${idx + 1} 段返回 $status，已停止，禁止盲目重发")
                    }

                    val percent = (progress * 100).toInt()
                    if (percent >= lastReported + 10 || idx == segments.size - 1) {
                        lastReported = percent
                        reportStatus(app, _state.value.copy(message = "加工中 $percent%"))
                    }
                }

                phase = RunPhase.COMPLETED
                message = "✓ 加工完成（${segments.size} 段）"
            } catch (ce: CancellationException) {
                phase = stopPhase ?: RunPhase.STOPPED
                message = if (phase == RunPhase.ESTOPPED) "⚠ 急停触发，作业已中止" else "作业已停止"
                throw ce
            } catch (e: Exception) {
                phase = RunPhase.FAILED
                message = e.message ?: "执行失败"
            } finally {
                withContext(NonCancellable) {
                    finish(app, phase, message)
                }
            }
        }
    }

    /** 受控停止（非硬件急停）。 */
    fun requestStop() {
        if (!_state.value.running) return
        stopPhase = RunPhase.STOPPED
        _state.value = _state.value.copy(phase = RunPhase.STOPPED, message = "已请求受控停止")
        scope.launch { runCatching { device.requestStop() } }
        job?.cancel()
    }

    /** 急停：模拟器置 REAL_FAULT 并拒绝后续运动；真实设备由硬件回路执行。 */
    fun emergencyStop() {
        stopPhase = RunPhase.ESTOPPED
        repo.simulatorOrNull()?.triggerEstop()
        _state.value = _state.value.copy(
            phase = RunPhase.ESTOPPED,
            message = "⚠ 急停已触发，所有运动停止（模拟）"
        )
        job?.cancel()
    }

    /** 急停复位，需现场确认安全后人工执行。 */
    fun resetEstop() {
        repo.simulatorOrNull()?.resetEstop()
        if (!_state.value.running && _state.value.phase == RunPhase.ESTOPPED) {
            _state.value = _state.value.copy(phase = RunPhase.IDLE, message = "急停已复位，设备恢复就绪")
        }
    }

    /** 回到空闲状态（已结束的作业）。 */
    fun reset() {
        if (_state.value.running) return
        _state.value = SimpleRunState()
        stopPhase = null
    }

    private suspend fun finish(app: Context, phase: RunPhase, message: String) {
        val finishedAt = System.currentTimeMillis()
        val previous = _state.value
        val finalMessage = message
        _state.value = previous.copy(
            phase = phase,
            message = finalMessage,
            finishedAt = finishedAt,
            error = if (phase == RunPhase.FAILED) finalMessage else previous.error
        )

        // 记录加工结果日志
        when (phase) {
            RunPhase.COMPLETED -> LogRecorder.info(app, "TASK", "加工完成：${previous.taskName}", taskId = previous.drawingId)
            RunPhase.FAILED -> LogRecorder.error(app, "TASK", "加工失败：${finalMessage}", taskId = previous.drawingId)
            RunPhase.ESTOPPED -> LogRecorder.error(app, "ALARM", "急停触发，加工中止：${previous.taskName}", taskId = previous.drawingId)
            RunPhase.STOPPED -> LogRecorder.warn(app, "TASK", "受控停止：${previous.taskName}", taskId = previous.drawingId)
            else -> {}
        }

        // 停止主轴（失败仅记录日志，不覆盖作业结果）
        runCatching { device.sendMotionCommand(DeviceCommand.setSpindle(
            commandId = "simple-spindle-off-${System.currentTimeMillis()}",
            taskId = previous.drawingId,
            rpm = 0, on = false, clockwise = true
        )) }

        if (previous.startedAt > 0L) {
            val status = when (phase) {
                RunPhase.COMPLETED -> "SUCCESS"
                RunPhase.FAILED -> "FAILED"
                else -> "ABORTED"
            }
            val entity = TaskHistoryEntity(
                id = "task-${System.currentTimeMillis()}",
                partName = previous.taskName.ifBlank { "未命名任务" },
                planId = previous.drawingId,
                status = status,
                durationSec = (finishedAt - previous.startedAt) / 1000.0,
                operator = "平板操作员",
                startedAt = previous.startedAt,
                finishedAt = finishedAt
            )
            runCatching {
                CncDatabase.getInstance(app).taskHistoryDao().insert(entity)
                SyncManager.uploadTask(app, entity, entity.operator)
            }
        }
        reportStatus(app, _state.value)
    }

    private suspend fun reportStatus(app: Context, state: SimpleRunState) {
        val snapshot = runCatching { device.readStatus() }.getOrNull()
        val positions = runCatching { device.readAxisPositions() }.getOrNull()
        val dto = StatusDto(
            connected = device.isConnected(),
            mode = device.connectionMode.name,
            isSimulated = device.isSimulated,
            taskName = state.taskName,
            progress = state.progress.toDouble(),
            message = state.message,
            xMm = snapshot?.xActualPositionMm ?: positions?.first,
            zMm = snapshot?.zActualPositionMm ?: positions?.second,
            spindleRpm = snapshot?.spindleRpm
        )
        runCatching { SyncManager.postStatus(app, dto) }
    }

    private const val DEFAULT_SPINDLE_RPM = 1200
}
