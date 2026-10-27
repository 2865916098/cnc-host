package com.cnc.device.api

import com.cnc.model.common.ConnectionMode
import com.cnc.model.device.AlarmRecord
import com.cnc.model.device.DeviceSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * 统一设备接口。
 *
 * 这是内部接口设计建议，不代表真实控制卡已经支持对应指令。
 * 所有实现必须明确区分模拟设备与真实设备。
 */
interface DeviceController {

    /** 当前连接模式（离线/模拟/真实连接/真实故障）。 */
    val connectionMode: ConnectionMode

    /** 是否为模拟设备。真实设备模式下必须为 false。 */
    val isSimulated: Boolean

    /** 建立连接。 */
    suspend fun connect(): Result<Unit>

    /** 断开连接。 */
    suspend fun disconnect()

    /** 读取当前连接状态。 */
    fun isConnected(): Boolean

    /** 设备状态流。 */
    fun statusFlow(): Flow<DeviceSnapshot>

    /** 读取当前设备状态快照。 */
    suspend fun readStatus(): DeviceSnapshot

    /** 读取 X/Z 轴实际位置（单位 mm）。 */
    suspend fun readAxisPositions(): Pair<Double?, Double?>

    /** 发送运动命令。 */
    suspend fun sendMotionCommand(command: DeviceCommand): Result<DeviceCommand>

    /** 请求暂停。具体暂停方式取决于控制卡能力。 */
    suspend fun requestPause(): Result<Unit>

    /** 请求受控停止。注意：这不是硬件急停。 */
    suspend fun requestStop(): Result<Unit>

    /** 读取全部输入状态。 */
    suspend fun readInputs(): Map<Int, Boolean>

    /** 设置输出点位。 */
    suspend fun setOutputs(outputs: Map<Int, Boolean>): Result<Unit>

    /** 读取当前报警列表。 */
    suspend fun readAlarms(): List<AlarmRecord>

    /** 查询命令执行结果。 */
    suspend fun queryCommandResult(commandId: String): Result<CommandStatus>
}
