package com.cnc.device.api

import com.cnc.model.cam.MotionType
import com.cnc.model.cam.ToolpathSegment
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
         * 构造圆弧进给命令（G02/G03 语义：终点 + 圆心 + 半径 + 方向）。
         *
         * 圆心为工件坐标系绝对坐标；执行端如需 I/K 增量自行换算。
         */
        fun arcFeed(
            commandId: String,
            taskId: String?,
            target: Point2D,
            center: Point2D,
            radius: Double,
            clockwise: Boolean,
            feedMmPerMin: Double
        ): DeviceCommand = DeviceCommand(
            commandId = commandId,
            taskId = taskId,
            type = CommandType.ARC_FEED,
            params = mapOf(
                "x" to target.x,
                "z" to target.y,
                "cx" to center.x,
                "cz" to center.y,
                "radius" to radius,
                "clockwise" to clockwise,
                "feed" to feedMmPerMin
            )
        )

        /**
         * 由刀路段构造运动命令（R1 修复：圆弧段下发 ARC_FEED，不再降级为弦）。
         *
         * 圆弧段必须携带圆心数据；缺失时抛出异常拒绝下发——
         * 按弦加工会改变工件几何形状，存在过切风险，宁可停止也不盲发。
         */
        fun fromSegment(commandId: String, taskId: String?, seg: ToolpathSegment): DeviceCommand {
            val target = Point2D(seg.end.x, seg.end.y)
            val feed = seg.feedMmPerMin.takeIf { it > 0.0 } ?: 100.0
            return when (seg.motionType) {
                MotionType.RAPID -> rapidMove(commandId, taskId, target)
                MotionType.LINEAR_FEED -> linearFeed(commandId, taskId, target, feed)
                MotionType.ARC_FEED -> {
                    val center = requireNotNull(seg.center) {
                        "圆弧段[${seg.segmentId}]缺少圆心数据，拒绝下发（禁止按弦加工）"
                    }
                    arcFeed(
                        commandId, taskId, target,
                        center = Point2D(center.x, center.y),
                        radius = seg.radius ?: 0.0,
                        clockwise = seg.clockwise ?: true,
                        feedMmPerMin = feed
                    )
                }
            }
        }

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
