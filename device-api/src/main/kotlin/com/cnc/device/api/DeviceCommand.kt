package com.cnc.device.api

import com.cnc.model.cam.MotionType
import com.cnc.model.drawing.Point2D

/** 命令类型。 */
enum class CommandType {
    RAPID_MOVE,
    LINEAR_FEED,
    ARC_FEED,
    SET_SPINDLE,
    SET_OUTPUT,
    WAIT_INPUT,
    PAUSE,
    STOP,
    HOME
}

/** 命令状态。 */
enum class CommandStatus {
    CREATED,
    QUEUED,
    SENT,
    ACKNOWLEDGED,
    EXECUTING,
    COMPLETED,
    SEND_FAILED,
    TIMEOUT,
    REJECTED,
    EXECUTION_FAILED,
    RESULT_UNKNOWN
}

/**
 * 设备命令。
 *
 * 对于执行结果未知的运动命令，禁止盲目重复发送，
 * 必须先查询设备状态或执行规定的恢复程序。
 */
data class DeviceCommand(
    val commandId: String,
    val taskId: String? = null,
    val type: CommandType,
    val params: Map<String, Any> = emptyMap(),
    val sentAt: Long? = null,
    val timeoutMs: Long = 2000,
    val status: CommandStatus = CommandStatus.CREATED,
    val ackInfo: String? = null,
    val error: String? = null
) {
    /** 构造快速定位命令。 */
    companion object {
        fun rapidMove(commandId: String, taskId: String?, target: Point2D): DeviceCommand =
            DeviceCommand(
                commandId = commandId,
                taskId = taskId,
                type = CommandType.RAPID_MOVE,
                params = mapOf("x" to target.x, "z" to target.y)
            )

        fun linearFeed(
            commandId: String,
            taskId: String?,
            target: Point2D,
            feedMmPerMin: Double
        ): DeviceCommand = DeviceCommand(
            commandId = commandId,
            taskId = taskId,
            type = CommandType.LINEAR_FEED,
            params = mapOf("x" to target.x, "z" to target.y, "feed" to feedMmPerMin)
        )

        /**
         * 主轴命令。
         *
         * @param clockwise true = M03 正转，false = M04 反转。控制卡不支持方向时由实现忽略并记录。
         */
        fun setSpindle(
            commandId: String,
            taskId: String?,
            rpm: Int,
            on: Boolean,
            clockwise: Boolean = true
        ): DeviceCommand =
            DeviceCommand(
                commandId = commandId,
                taskId = taskId,
                type = CommandType.SET_SPINDLE,
                params = mapOf("rpm" to rpm, "on" to on, "clockwise" to clockwise)
            )
    }
}
