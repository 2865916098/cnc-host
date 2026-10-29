package com.cnc.device.modbus

import com.cnc.device.api.CommandStatus
import com.cnc.device.api.CommandType
import com.cnc.device.api.DeviceCommand
import com.cnc.device.api.DeviceController
import com.cnc.model.common.ConnectionMode
import com.cnc.model.common.ConnectionType
import com.cnc.model.device.AlarmRecord
import com.cnc.model.device.AlarmSeverity
import com.cnc.model.device.DeviceSnapshot
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
import kotlin.math.roundToInt

/**
 * 通用 Modbus TCP 控制卡驱动（骨架）。
 *
 * 面向「网口 + Modbus TCP」类控制卡的通用实现：
 * - 状态读取：按 [ModbusRegisterMap] 轮询位置/主轴/状态字寄存器
 * - 运动命令：写参数寄存器组 → 置启动位 → 轮询状态字完成位（超时禁止盲目重发）
 * - IO：离散输入读取 / 线圈写入
 *
 * 所有寄存器地址、缩放、位定义均由后台参数下发（见 [ModbusRegisterMap]），
 * 接入具体厂商控制卡时优先调整映射；厂商命令语义与本骨架不同时再扩展驱动。
 *
 * 本驱动只与真实硬件通讯，[isSimulated] 恒为 false；通讯异常时快照标注
 * [ConnectionMode.REAL_FAULT]，绝不冒充正常数据。
 */
class ModbusTcpDeviceController(
    private val client: ModbusClient,
    private val map: ModbusRegisterMap = ModbusRegisterMap(),
    private val maxSpindleRpm: Int = 3000,
    private val pollScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : DeviceController {

    /** 便于测试注入假传输的构造方式。 */
    constructor(
        transport: ModbusTransport,
        unitId: Int = 1,
        map: ModbusRegisterMap = ModbusRegisterMap(),
        maxSpindleRpm: Int = 3000,
        pollScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    ) : this(ModbusClient(transport, unitId), map, maxSpindleRpm, pollScope)

    @Volatile private var connected = false

    /** 通讯故障标记：已连接但最近一次收发失败。 */
    @Volatile private var commFault = false

    private var pollJob: Job? = null

    private val _status = MutableStateFlow(snapshot())

    override val connectionMode: ConnectionMode
        get() = when {
            !connected -> ConnectionMode.OFFLINE
            commFault -> ConnectionMode.REAL_FAULT
            else -> ConnectionMode.REAL_CONNECTED
        }

    override val isSimulated: Boolean = false

    override suspend fun connect(): Result<Unit> {
        if (connected) return Result.success(Unit)
        return try {
            client.open()
            connected = true
            commFault = false
            // 连接后必须读通一次状态字，验证链路可用
            client.readHoldingRegisters(map.statusWordAddr, 1)
            pollOnce()
            startPolling()
            Result.success(Unit)
        } catch (e: Exception) {
            connected = false
            client.close()
            _status.value = snapshot()
            Result.failure(ModbusException("MODBUS: 连接失败: ${e.message}", e))
        }
    }

    override suspend fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        connected = false
        commFault = false
        client.close()
        _status.value = snapshot()
    }

    override fun isConnected(): Boolean = connected && !commFault

    override fun statusFlow(): StateFlow<DeviceSnapshot> = _status.asStateFlow()

    override suspend fun readStatus(): DeviceSnapshot {
        if (connected) pollOnce()
        return _status.value
    }

    override suspend fun readAxisPositions(): Pair<Double?, Double?> {
        if (!connected) return Pair(null, null)
        return try {
            Pair(readPosition(map.posXAddr), readPosition(map.posZAddr))
        } catch (e: ModbusException) {
            Pair(null, null)
        }
    }

    override suspend fun sendMotionCommand(command: DeviceCommand): Result<DeviceCommand> {
        if (!connected) return Result.failure(ModbusException("MODBUS: 设备未连接"))
        return try {
            when (command.type) {
                CommandType.RAPID_MOVE, CommandType.LINEAR_FEED -> {
                    val x = paramDouble(command, "x") ?: return rejected(command, "缺少 X 参数")
                    val z = paramDouble(command, "z") ?: return rejected(command, "缺少 Z 参数")
                    val feed = paramDouble(command, "feed") ?: 0.0
                    writePosition(map.cmdXAddr, x)
                    writePosition(map.cmdZAddr, z)
                    writeFeed(feed)
                    client.writeSingleRegister(
                        map.cmdTypeAddr,
                        if (command.type == CommandType.RAPID_MOVE) ModbusRegisterMap.CmdTypes.RAPID
                        else ModbusRegisterMap.CmdTypes.LINEAR
                    )
                    startAndAwaitDone(command)
                }

                CommandType.ARC_FEED -> {
                    val x = paramDouble(command, "x") ?: return rejected(command, "缺少 X 参数")
                    val z = paramDouble(command, "z") ?: return rejected(command, "缺少 Z 参数")
                    val cx = paramDouble(command, "cx") ?: return rejected(command, "缺少圆心 X 参数")
                    val cz = paramDouble(command, "cz") ?: return rejected(command, "缺少圆心 Z 参数")
                    val clockwise = command.params["clockwise"] as? Boolean ?: true
                    val feed = paramDouble(command, "feed") ?: 0.0
                    writePosition(map.cmdXAddr, x)
                    writePosition(map.cmdZAddr, z)
                    writePosition(map.cmdCxAddr, cx)
                    writePosition(map.cmdCzAddr, cz)
                    writeFeed(feed)
                    client.writeSingleRegister(
                        map.cmdTypeAddr,
                        if (clockwise) ModbusRegisterMap.CmdTypes.ARC_CW else ModbusRegisterMap.CmdTypes.ARC_CCW
                    )
                    startAndAwaitDone(command)
                }

                CommandType.SET_SPINDLE -> {
                    val rpm = (command.params["rpm"] as? Number)?.toInt() ?: 0
                    val on = command.params["on"] as? Boolean ?: false
                    val clockwise = command.params["clockwise"] as? Boolean ?: true
                    client.writeSingleRegister(map.spindleCmdAddr, rpm.coerceIn(0, maxSpindleRpm))
                    client.writeSingleRegister(map.spindleOnAddr, if (!on) 0 else if (clockwise) 1 else 2)
                }

                CommandType.HOME -> {
                    client.writeSingleRegister(map.controlWordAddr, ModbusRegisterMap.ControlBits.HOME)
                    awaitDone(command)
                }

                CommandType.PAUSE -> requestPause()
                CommandType.STOP -> requestStop()
                else -> return rejected(command, "驱动不支持的命令类型: ${command.type}")
            }
            pollOnce()
            Result.success(command.copy(status = CommandStatus.COMPLETED))
        } catch (e: ModbusException) {
            // 链路健康由轮询循环统一判定；命令失败（含超时/报警）不直接置通讯故障
            Result.failure(e)
        }
    }

    override suspend fun requestPause(): Result<Unit> = writeControl(ModbusRegisterMap.ControlBits.PAUSE)

    override suspend fun requestStop(): Result<Unit> = writeControl(ModbusRegisterMap.ControlBits.STOP)

    override suspend fun readInputs(): Map<Int, Boolean> {
        if (!connected) return emptyMap()
        return try {
            val bits = client.readDiscreteInputs(map.inputStart, map.inputCount)
            bits.mapIndexed { i, v -> i to v }.toMap()
        } catch (e: ModbusException) {
            emptyMap()
        }
    }

    override suspend fun setOutputs(outputs: Map<Int, Boolean>): Result<Unit> {
        if (!connected) return Result.failure(ModbusException("MODBUS: 设备未连接"))
        return try {
            for ((idx, on) in outputs) {
                require(idx in 0 until map.outputCount) { "输出点位越界: $idx" }
                client.writeSingleCoil(map.outputStart + idx, on)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(if (e is ModbusException) e else ModbusException("写输出失败: ${e.message}", e))
        }
    }

    override suspend fun readAlarms(): List<AlarmRecord> {
        if (!connected) return emptyList()
        return try {
            val regs = client.readHoldingRegisters(map.alarmStart, map.alarmCount)
            val alarms = mutableListOf<AlarmRecord>()
            regs.forEachIndexed { i, v ->
                if (v != 0) {
                    alarms += AlarmRecord(
                        code = "MB-%04d".format(map.alarmStart + i),
                        source = "MODBUS_TCP",
                        message = "控制卡报警代码 $v（寄存器 ${map.alarmStart + i}）",
                        severity = AlarmSeverity.ERROR,
                        occurredAt = System.currentTimeMillis(),
                        allowsResume = false
                    )
                }
            }
            alarms
        } catch (e: ModbusException) {
            emptyList()
        }
    }

    override suspend fun queryCommandResult(commandId: String): Result<CommandStatus> {
        if (!connected) return Result.failure(ModbusException("MODBUS: 设备未连接"))
        return try {
            val sw = client.readHoldingRegisters(map.statusWordAddr, 1)[0]
            val status = when {
                sw and ModbusRegisterMap.StatusBits.ESTOP != 0 -> CommandStatus.EXECUTION_FAILED
                sw and ModbusRegisterMap.StatusBits.ALARM != 0 -> CommandStatus.EXECUTION_FAILED
                sw and ModbusRegisterMap.StatusBits.BUSY != 0 -> CommandStatus.EXECUTING
                sw and ModbusRegisterMap.StatusBits.DONE != 0 -> CommandStatus.COMPLETED
                else -> CommandStatus.RESULT_UNKNOWN
            }
            Result.success(status)
        } catch (e: ModbusException) {
            Result.failure(e)
        }
    }

    // ===== 内部实现 =====

    private fun rejected(command: DeviceCommand, reason: String): Result<DeviceCommand> =
        Result.failure(ModbusException("MODBUS: 命令拒绝: $reason"))

    private fun paramDouble(command: DeviceCommand, key: String): Double? =
        (command.params[key] as? Number)?.toDouble()

    private suspend fun writeControl(bits: Int): Result<Unit> {
        if (!connected) return Result.failure(ModbusException("MODBUS: 设备未连接"))
        return try {
            client.writeSingleRegister(map.controlWordAddr, bits)
            Result.success(Unit)
        } catch (e: ModbusException) {
            Result.failure(e)
        }
    }

    private suspend fun startAndAwaitDone(command: DeviceCommand) {
        client.writeSingleRegister(map.controlWordAddr, ModbusRegisterMap.ControlBits.START)
        awaitDone(command)
    }

    /**
     * 等待完成位。
     *
     * 超时后命令执行结果未知：调用方禁止盲目重发，必须先查询设备状态恢复同步。
     */
    private suspend fun awaitDone(command: DeviceCommand) {
        val timeoutMs = maxOf(command.timeoutMs, map.commandTimeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val sw = client.readHoldingRegisters(map.statusWordAddr, 1)[0]
            when {
                sw and ModbusRegisterMap.StatusBits.ESTOP != 0 ->
                    throw ModbusException("急停触发，命令中止")
                sw and ModbusRegisterMap.StatusBits.ALARM != 0 ->
                    throw ModbusException("控制卡报警，命令执行失败")
                sw and ModbusRegisterMap.StatusBits.DONE != 0 &&
                    sw and ModbusRegisterMap.StatusBits.BUSY == 0 -> return
            }
            delay(50)
        }
        throw ModbusException("等待完成位超时（${timeoutMs}ms），执行结果未知，禁止盲目重发")
    }

    /** 读位置寄存器并换算为 mm。 */
    private suspend fun readPosition(addr: Int): Double {
        return if (map.pos32Bit) {
            val r = client.readHoldingRegisters(addr, 2)
            val raw = (r[0] shl 16) or r[1] // 高字在前，有符号 32 位
            raw / map.posScale
        } else {
            val r = client.readHoldingRegisters(addr, 1)[0]
            val raw = if (r >= 0x8000) r - 0x10000 else r
            raw / map.posScale
        }
    }

    /** 写位置寄存器（mm → 寄存器值）。 */
    private suspend fun writePosition(addr: Int, mm: Double) {
        val raw = (mm * map.posScale).roundToInt()
        if (map.pos32Bit) {
            client.writeMultipleRegisters(addr, intArrayOf((raw ushr 16) and 0xFFFF, raw and 0xFFFF))
        } else {
            client.writeSingleRegister(addr, raw and 0xFFFF)
        }
    }

    /** 写进给寄存器（32 位，单位 0.01 mm/min）。 */
    private suspend fun writeFeed(feedMmPerMin: Double) {
        val raw = (feedMmPerMin * 100).roundToInt()
        client.writeMultipleRegisters(map.cmdFeedAddr, intArrayOf((raw ushr 16) and 0xFFFF, raw and 0xFFFF))
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = pollScope.launch {
            while (isActive) {
                pollOnce()
                delay(map.pollIntervalMs)
            }
        }
    }

    /** 轮询一次：位置 + 主轴 + 状态字 → 更新快照。通讯失败置 [commFault]，快照标注 REAL_FAULT。 */
    private suspend fun pollOnce() {
        try {
            val x = readPosition(map.posXAddr)
            val z = readPosition(map.posZAddr)
            val rpm = client.readHoldingRegisters(map.spindleRpmAddr, 1)[0]
            val sw = client.readHoldingRegisters(map.statusWordAddr, 1)[0]
            commFault = false
            _status.value = snapshot(
                connected = true,
                x = x, z = z,
                rpm = rpm,
                estop = sw and ModbusRegisterMap.StatusBits.ESTOP != 0,
                alarms = if (sw and ModbusRegisterMap.StatusBits.ALARM != 0)
                    listOf("控制卡报警（状态字 bit2）") else emptyList()
            )
        } catch (e: Exception) {
            commFault = true
            _status.value = snapshot(connected = connected)
        }
    }

    private fun snapshot(
        connected: Boolean = false,
        x: Double? = null,
        z: Double? = null,
        rpm: Int? = null,
        estop: Boolean = false,
        alarms: List<String> = emptyList()
    ): DeviceSnapshot = DeviceSnapshot(
        mode = connectionMode,
        connectionType = ConnectionType.TCP,
        connected = connected,
        xActualPositionMm = x,
        zActualPositionMm = z,
        spindleRpm = rpm,
        spindleRunning = (rpm ?: 0) > 0,
        estopPressed = estop,
        alarms = alarms,
        isSimulated = false
    )
}
