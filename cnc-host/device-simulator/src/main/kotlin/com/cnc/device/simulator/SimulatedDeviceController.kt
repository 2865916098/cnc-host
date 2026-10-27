package com.cnc.device.simulator

import com.cnc.device.api.CommandStatus
import com.cnc.device.api.CommandType
import com.cnc.device.api.DeviceCommand
import com.cnc.device.api.DeviceController
import com.cnc.model.common.ConnectionMode
import com.cnc.model.common.ConnectionType
import com.cnc.model.device.AlarmRecord
import com.cnc.model.device.AlarmSeverity
import com.cnc.model.device.DeviceSnapshot
import com.cnc.model.drawing.Point2D
import com.cnc.model.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * 故障注入类型，用于测试异常处理流程。
 */
enum class FaultType {
    NONE,
    COMMUNICATION_DROP,
    CLAMP_FAILURE,
    COMMAND_TIMEOUT,
    DEVICE_ALARM,
    AXIS_OVERTRAVEL,
    FEED_FAILURE,
    SPINDLE_FAILURE
}

/** 设备就绪状态。 */
enum class DeviceReadiness {
    NOT_READY,
    READY,
    BUSY,
    FAULT
}

/**
 * 模拟控制卡（增强版）。
 *
 * 实现 DeviceController 接口，模拟 X/Z 轴运动、主轴、IO、报警和故障场景。
 * 集成命令队列、模拟日志、设备就绪检查和更真实的运动模型。
 * 所有输出数据必须标注为模拟数据，不得冒充真实设备数据。
 */
class SimulatedDeviceController(
    val config: DeviceSimulatorConfig = DeviceSimulatorConfig(),
    val logger: SimulationLogger = SimulationLogger()
) : DeviceController {

    override val connectionMode: ConnectionMode
        get() = when {
            estopPressed -> ConnectionMode.REAL_FAULT
            connected -> ConnectionMode.SIMULATOR
            else -> ConnectionMode.OFFLINE
        }

    override val isSimulated: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private var connected = false
    private var connecting = false

    // 模拟轴位置（mm）
    private var xActual = 0.0
    private var zActual = 0.0
    private var xTarget = 0.0
    private var zTarget = 0.0

    // 模拟主轴
    private var spindleRpm = 0
    private var spindleOn = false

    // 当前刀具
    private var currentTool = 1

    // 急停
    @Volatile var estopPressed = false
        private set

    // 设备就绪
    private var readiness = DeviceReadiness.NOT_READY

    // IO
    private val inputs = mutableMapOf<Int, Boolean>()
    private val outputs = mutableMapOf<Int, Boolean>()

    // 报警
    private val alarms = mutableListOf<AlarmRecord>()

    // 故障注入
    @Volatile private var faultType: FaultType = FaultType.NONE

    // 命令队列
    private val commandQueue = SimulatedCommandQueue(
        config = config,
        logger = logger,
        executor = { cmd -> executeCommand(cmd) }
    )

    // 状态流
    private val _status = MutableStateFlow(buildSnapshot())
    override fun statusFlow(): StateFlow<DeviceSnapshot> = _status.asStateFlow()

    init {
        // 初始化默认 IO
        for (i in 0 until config.inputCount) inputs[i] = false
        for (i in 0 until config.outputCount) outputs[i] = false
        // 默认安全输入
        inputs[1] = false  // 夹紧反馈
        inputs[2] = true   // 送料到位
        inputs[3] = true   // 气压正常
        inputs[4] = true   // 安全门关闭
        inputs[5] = false  // 急停反馈
    }

    override suspend fun connect(): Result<Unit> {
        if (connected) return Result.success(Unit)
        if (connecting) return Result.failure(RuntimeException("SIM: 正在连接中"))

        connecting = true
        logger.info(LogCategory.COMMUNICATION, "开始连接模拟设备")
        delay(300)

        if (faultType == FaultType.COMMUNICATION_DROP) {
            connecting = false
            logger.error(LogCategory.COMMUNICATION, "连接失败：通讯中断（故障注入）")
            return Result.failure(RuntimeException("SIM: 连接被拒绝（故障注入）"))
        }

        connected = true
        connecting = false
        readiness = DeviceReadiness.READY
        inputs[5] = false // 急停未触发
        logger.info(LogCategory.COMMUNICATION, "模拟设备已连接")
        commandQueue.start()
        emitSnapshot()
        startTicking()
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        if (!connected) return
        connected = false
        readiness = DeviceReadiness.NOT_READY
        commandQueue.stop()
        tickJob?.cancel()
        tickJob = null
        logger.info(LogCategory.COMMUNICATION, "模拟设备已断开")
        emitSnapshot()
    }

    override fun isConnected(): Boolean = connected

    /** 设备是否就绪可执行加工。 */
    fun isReady(): Boolean = connected && !estopPressed && readiness == DeviceReadiness.READY

    /** 获取设备就绪状态。 */
    fun getReadiness(): DeviceReadiness = readiness

    override suspend fun readStatus(): DeviceSnapshot = buildSnapshot()

    override suspend fun readAxisPositions(): Pair<Double?, Double?> {
        if (!connected) return Pair(null, null)
        return Pair(xActual, zActual)
    }

    override suspend fun sendMotionCommand(command: DeviceCommand): Result<DeviceCommand> {
        if (!connected) {
            return Result.failure(RuntimeException("SIM: 设备未连接"))
        }
        if (estopPressed) {
            return Result.failure(RuntimeException("SIM: 急停已触发，禁止运动"))
        }
        if (faultType == FaultType.COMMUNICATION_DROP) {
            return Result.failure(RuntimeException("SIM: 通讯中断（故障注入）"))
        }

        // 命令入队并等待执行完成
        // 队列是命令执行的唯一入口，避免重复执行
        val handle = commandQueue.enqueue(command)
        logger.debug(
            LogCategory.COMMUNICATION,
            "命令 ${command.commandId} 已入队，等待执行",
            command.taskId
        )

        val finalStatus = handle.await()
        return when (finalStatus) {
            CommandStatus.COMPLETED -> {
                logger.info(LogCategory.MOTION, "命令 ${command.commandId} 完成", command.taskId)
                Result.success(command.copy(status = finalStatus))
            }
            CommandStatus.RESULT_UNKNOWN -> {
                // 结果未知：禁止重发，需查询设备状态
                Result.failure(RuntimeException("SIM: 命令结果未知，禁止重发，需查询设备状态"))
            }
            else -> {
                Result.failure(RuntimeException("SIM: 命令执行失败: $finalStatus"))
            }
        }
    }

    /** 执行单个命令的核心逻辑。 */
    private suspend fun executeCommand(command: DeviceCommand): SimulatedCommandQueue.CommandExecutionResult {
        val id = command.commandId

        if (faultType == FaultType.COMMAND_TIMEOUT) {
            logger.warn(LogCategory.COMMUNICATION, "命令 $id 超时，结果未知", command.taskId)
            return SimulatedCommandQueue.CommandExecutionResult(
                CommandStatus.RESULT_UNKNOWN,
                "命令超时，执行结果未知"
            )
        }

        return when (command.type) {
            CommandType.RAPID_MOVE, CommandType.LINEAR_FEED, CommandType.ARC_FEED -> {
                val x = command.params["x"] as? Double
                val z = command.params["z"] as? Double
                if (x == null || z == null) {
                    return SimulatedCommandQueue.CommandExecutionResult(
                        CommandStatus.REJECTED,
                        "缺少 X/Z 参数"
                    )
                }
                executeMotion(command, x, z)
            }

            CommandType.SET_SPINDLE -> {
                val rpm = command.params["rpm"] as? Int ?: 0
                val on = command.params["on"] as? Boolean ?: false
                val clockwise = command.params["clockwise"] as? Boolean ?: true
                if (faultType == FaultType.SPINDLE_FAILURE) {
                    addAlarm("SIM-SF", "主轴故障（故障注入）", AlarmSeverity.ERROR, allowsResume = false)
                    return SimulatedCommandQueue.CommandExecutionResult(
                        CommandStatus.EXECUTION_FAILED,
                        "主轴故障"
                    )
                }
                spindleOn = on
                spindleRpm = if (on) rpm.coerceAtMost(config.maxSpindleRpm) else 0
                logger.info(
                    LogCategory.MOTION,
                    "主轴设置: ${if (clockwise) "M03 正转" else "M04 反转"}, on=$on, rpm=$spindleRpm",
                    command.taskId
                )
                SimulatedCommandQueue.CommandExecutionResult(CommandStatus.COMPLETED)
            }

            CommandType.SET_OUTPUT -> {
                SimulatedCommandQueue.CommandExecutionResult(CommandStatus.COMPLETED)
            }

            CommandType.STOP -> {
                spindleOn = false
                spindleRpm = 0
                readiness = DeviceReadiness.READY
                logger.info(LogCategory.MOTION, "受控停止", command.taskId)
                SimulatedCommandQueue.CommandExecutionResult(CommandStatus.COMPLETED)
            }

            CommandType.PAUSE -> {
                readiness = DeviceReadiness.BUSY
                logger.info(LogCategory.MOTION, "暂停请求", command.taskId)
                SimulatedCommandQueue.CommandExecutionResult(CommandStatus.COMPLETED)
            }

            CommandType.HOME -> {
                val result = executeMotion(command, 0.0, 0.0)
                if (result.status == CommandStatus.COMPLETED) {
                    logger.info(LogCategory.MOTION, "回零完成", command.taskId)
                }
                result
            }

            CommandType.WAIT_INPUT -> {
                SimulatedCommandQueue.CommandExecutionResult(CommandStatus.COMPLETED)
            }
        }
    }

    /** 执行运动命令，含行程检查和故障注入。 */
    private suspend fun executeMotion(
        command: DeviceCommand,
        targetX: Double,
        targetZ: Double
    ): SimulatedCommandQueue.CommandExecutionResult {
        // 行程检查
        if (targetX < 0 || targetX > config.xTravelMm || targetZ < 0 || targetZ > config.zTravelMm) {
            addAlarm("SIM-OT", "轴超程: X=$targetX, Z=$targetZ", AlarmSeverity.CRITICAL, allowsResume = false)
            logger.error(LogCategory.MOTION, "轴超程: X=$targetX, Z=$targetZ", command.taskId)
            return SimulatedCommandQueue.CommandExecutionResult(
                CommandStatus.EXECUTION_FAILED,
                "轴超程"
            )
        }

        if (faultType == FaultType.AXIS_OVERTRAVEL) {
            addAlarm("SIM-OT", "轴超程（故障注入）", AlarmSeverity.CRITICAL, allowsResume = false)
            return SimulatedCommandQueue.CommandExecutionResult(
                CommandStatus.EXECUTION_FAILED,
                "轴超程（故障注入）"
            )
        }

        readiness = DeviceReadiness.BUSY
        xTarget = targetX
        zTarget = targetZ
        simulateMotion(targetX, targetZ)
        readiness = DeviceReadiness.READY
        return SimulatedCommandQueue.CommandExecutionResult(CommandStatus.COMPLETED)
    }

    /**
     * 模拟轴运动。
     *
     * 基于距离和进给速度计算运动时间，按步进更新位置。
     * 简化模型：不考虑加减速曲线，但考虑最大速度限制。
     */
    private suspend fun simulateMotion(targetX: Double, targetZ: Double) {
        val startX = xActual
        val startZ = zActual
        val distance = hypot(targetX - startX, targetZ - startZ)
        if (distance < 0.001) {
            xActual = targetX
            zActual = targetZ
            return
        }

        // 步进距离 5mm，每步延迟基于最大速度
        val stepDistance = 5.0
        val steps = (distance / stepDistance).toInt().coerceAtLeast(2)
        // 每步时间 = stepDistance / maxFeed (分钟转毫秒)
        val stepDelayMs = ((stepDistance / config.maxFeedMmPerMin) * 60_000).toLong().coerceAtLeast(10)

        for (i in 1..steps) {
            val t = i.toDouble() / steps
            xActual = startX + (targetX - startX) * t
            zActual = startZ + (targetZ - startZ) * t
            emitSnapshot()
            delay(stepDelayMs)
        }
        xActual = targetX
        zActual = targetZ
        emitSnapshot()
    }

    override suspend fun requestPause(): Result<Unit> {
        if (!connected) return Result.failure(RuntimeException("SIM: 设备未连接"))
        readiness = DeviceReadiness.BUSY
        logger.info(LogCategory.MOTION, "收到暂停请求")
        return Result.success(Unit)
    }

    override suspend fun requestStop(): Result<Unit> {
        if (!connected) return Result.failure(RuntimeException("SIM: 设备未连接"))
        spindleOn = false
        spindleRpm = 0
        readiness = DeviceReadiness.READY
        logger.info(LogCategory.MOTION, "受控停止：主轴已停")
        emitSnapshot()
        return Result.success(Unit)
    }

    /** 触发硬件急停（模拟）。注意：这只是模拟，真实急停由硬件回路承担。 */
    fun triggerEstop() {
        estopPressed = true
        spindleOn = false
        spindleRpm = 0
        readiness = DeviceReadiness.FAULT
        inputs[5] = true
        addAlarm("SIM-ESTOP", "硬件急停已触发", AlarmSeverity.CRITICAL, allowsResume = false)
        logger.error(LogCategory.SYSTEM, "硬件急停触发")
        emitSnapshot()
    }

    /** 复位急停（需人工确认）。 */
    fun resetEstop() {
        estopPressed = false
        inputs[5] = false
        readiness = DeviceReadiness.READY
        logger.info(LogCategory.SYSTEM, "急停已复位")
        emitSnapshot()
    }

    override suspend fun readInputs(): Map<Int, Boolean> {
        if (!connected) return emptyMap()
        return inputs.toMap()
    }

    override suspend fun setOutputs(outputs: Map<Int, Boolean>): Result<Unit> {
        if (!connected) return Result.failure(RuntimeException("SIM: 设备未连接"))
        if (estopPressed) return Result.failure(RuntimeException("SIM: 急停状态下禁止操作输出"))

        this.outputs.putAll(outputs)
        logger.debug(LogCategory.IO, "设置输出: $outputs")

        // 模拟夹紧电磁阀（输出 1）→ 夹紧反馈（输入 1）
        if (outputs.containsKey(1)) {
            val clampOn = outputs[1] == true
            delay(config.clampActionMs)
            if (faultType == FaultType.CLAMP_FAILURE) {
                inputs[1] = false
                addAlarm("SIM-CF", "夹紧失败（故障注入）", AlarmSeverity.ERROR, allowsResume = false)
                logger.error(LogCategory.IO, "夹紧失败", null)
            } else {
                inputs[1] = clampOn
                logger.info(LogCategory.IO, if (clampOn) "夹紧完成" else "松料完成")
            }
        }

        // 模拟送料电磁阀（输出 2）→ 送料到位反馈（输入 2）
        if (outputs.containsKey(2)) {
            val feedOn = outputs[2] == true
            delay(config.feedActionMs)
            if (faultType == FaultType.FEED_FAILURE) {
                inputs[2] = false
                addAlarm("SIM-FF", "送料失败（故障注入）", AlarmSeverity.ERROR, allowsResume = false)
                logger.error(LogCategory.IO, "送料失败")
            } else {
                // 送料电磁阀动作完成后，送料到位反馈为 true
                inputs[2] = true
                logger.info(LogCategory.IO, if (feedOn) "送料中" else "送料到位")
            }
        }

        emitSnapshot()
        return Result.success(Unit)
    }

    override suspend fun readAlarms(): List<AlarmRecord> {
        if (faultType == FaultType.DEVICE_ALARM && alarms.none { it.code == "SIM-ALM" }) {
            addAlarm("SIM-ALM", "设备报警（故障注入）", AlarmSeverity.CRITICAL, allowsResume = false)
        }
        return alarms.toList()
    }

    override suspend fun queryCommandResult(commandId: String): Result<CommandStatus> {
        if (!connected) return Result.failure(RuntimeException("SIM: 设备未连接"))
        val status = commandQueue.getStatus(commandId)
        return Result.success(status)
    }

    // ---- 故障注入（仅模拟模式可用） ----

    fun injectFault(type: FaultType) {
        faultType = type
        logger.warn(LogCategory.SYSTEM, "注入故障: $type")
        if (type == FaultType.COMMUNICATION_DROP) {
            connected = false
            readiness = DeviceReadiness.NOT_READY
        }
        emitSnapshot()
    }

    fun clearFault() {
        faultType = FaultType.NONE
        logger.info(LogCategory.SYSTEM, "清除故障注入")
        emitSnapshot()
    }

    /** 手动设置输入点（用于测试 IO 流程）。 */
    fun setInput(point: Int, value: Boolean) {
        inputs[point] = value
        logger.debug(LogCategory.IO, "手动设置输入 $point=$value")
        emitSnapshot()
    }

    /** 获取命令队列诊断快照。 */
    fun commandQueueSnapshot(): Map<String, CommandStatus> = commandQueue.snapshot()

    /** 导出模拟日志。 */
    fun exportLogs(): String = logger.exportAsText()

    private fun addAlarm(
        code: String,
        message: String,
        severity: AlarmSeverity,
        allowsResume: Boolean
    ) {
        val alarm = AlarmRecord(
            code = code,
            source = "simulator",
            message = message,
            severity = severity,
            occurredAt = System.currentTimeMillis(),
            allowsResume = allowsResume
        )
        alarms.add(alarm)
        logger.log(
            level = when (severity) {
                AlarmSeverity.CRITICAL, AlarmSeverity.ERROR -> com.cnc.model.logging.LogLevel.ERROR
                AlarmSeverity.WARNING -> com.cnc.model.logging.LogLevel.WARN
                AlarmSeverity.INFO -> com.cnc.model.logging.LogLevel.INFO
            },
            category = LogCategory.ALARM,
            message = "[$code] $message"
        )
    }

    /** 清除报警（模拟人工确认）。确认报警不等于故障已消除。 */
    fun acknowledgeAlarm(code: String) {
        val alarm = alarms.find { it.code == code }
        if (alarm != null && !alarm.cleared) {
            val idx = alarms.indexOf(alarm)
            alarms[idx] = alarm.copy(acknowledged = true, acknowledgedAt = System.currentTimeMillis())
            logger.info(LogCategory.ALARM, "报警已确认: $code")
        }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive && connected) {
                emitSnapshot()
                delay(config.statusTickMs)
            }
        }
    }

    private fun buildSnapshot(): DeviceSnapshot = DeviceSnapshot(
        mode = connectionMode,
        connectionType = ConnectionType.NONE,
        connected = connected,
        xActualPositionMm = if (connected) xActual else null,
        zActualPositionMm = if (connected) zActual else null,
        xTargetPositionMm = if (connected) xTarget else null,
        zTargetPositionMm = if (connected) zTarget else null,
        spindleRpm = if (connected) spindleRpm else null,
        spindleRunning = spindleOn,
        currentToolNumber = currentTool,
        inputs = inputs.toMap(),
        outputs = outputs.toMap(),
        estopPressed = estopPressed,
        alarms = alarms.map { it.code },
        timestamp = System.currentTimeMillis(),
        isSimulated = true
    )

    private fun emitSnapshot() {
        _status.value = buildSnapshot()
    }
}
