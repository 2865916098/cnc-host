package com.cnc.device.modbus

/**
 * 可配置寄存器映射。
 *
 * 「兼容所有控制卡仅需后台配置」的核心载体：不同厂商的网口控制卡只需
 * 在服务端后台调整本映射参数（JSON 键值），无需修改客户端代码。
 *
 * 默认布局是一套通用约定（真实控制卡接入时按厂商协议在后台调整）：
 *
 * | 键 | 默认 | 含义 |
 * |---|---|---|
 * | pos_x_addr / pos_z_addr | 0 / 2 | X/Z 实际位置（保持寄存器，32 位时占 2 个） |
 * | pos_scale | 100 | 寄存器值 ÷ scale = mm（脉冲当量倒数） |
 * | pos_32bit | true | 位置是否 32 位有符号（高字在前） |
 * | spindle_rpm_addr | 4 | 主轴实际转速反馈 |
 * | status_word_addr | 6 | 状态字：bit0=忙 bit1=完成 bit2=报警 bit3=急停 |
 * | control_word_addr | 8 | 控制字：bit0=启动命令 bit1=暂停 bit2=停止 bit3=回零 |
 * | cmd_type_addr | 16 | 命令类型：1=快速 2=直线 3=圆弧CW 4=圆弧CCW |
 * | cmd_x_addr / cmd_z_addr | 10 / 12 | 命令目标 X/Z（位置同 pos 格式） |
 * | cmd_feed_addr | 14 | 命令进给（32 位，单位 0.01 mm/min） |
 * | cmd_cx_addr / cmd_cz_addr | 18 / 20 | 圆弧圆心 X/Z |
 * | spindle_cmd_addr | 22 | 主轴命令转速（rpm） |
 * | spindle_on_addr | 23 | 主轴启停：0=停 1=正转 2=反转 |
 * | input_start / input_count | 0 / 16 | 离散输入区 |
 * | output_start / output_count | 0 / 16 | 线圈输出区 |
 * | alarm_start / alarm_count | 24 / 4 | 报警代码区（非零即报警） |
 * | poll_interval_ms | 200 | 状态轮询间隔 |
 * | command_timeout_ms | 10000 | 运动命令完成位等待超时 |
 */
data class ModbusRegisterMap(
    val posXAddr: Int = 0,
    val posZAddr: Int = 2,
    val posScale: Double = 100.0,
    val pos32Bit: Boolean = true,
    val spindleRpmAddr: Int = 4,
    val statusWordAddr: Int = 6,
    val controlWordAddr: Int = 8,
    val cmdTypeAddr: Int = 16,
    val cmdXAddr: Int = 10,
    val cmdZAddr: Int = 12,
    val cmdFeedAddr: Int = 14,
    val cmdCxAddr: Int = 18,
    val cmdCzAddr: Int = 20,
    val spindleCmdAddr: Int = 22,
    val spindleOnAddr: Int = 23,
    val inputStart: Int = 0,
    val inputCount: Int = 16,
    val outputStart: Int = 0,
    val outputCount: Int = 16,
    val alarmStart: Int = 24,
    val alarmCount: Int = 4,
    val pollIntervalMs: Long = 200,
    val commandTimeoutMs: Long = 10000
) {
    init {
        require(posScale > 0) { "pos_scale 必须为正" }
        require(pollIntervalMs >= 50) { "poll_interval_ms 下限 50" }
        require(commandTimeoutMs >= 500) { "command_timeout_ms 下限 500" }
    }

    /** 状态字位定义。 */
    object StatusBits {
        const val BUSY = 0x0001
        const val DONE = 0x0002
        const val ALARM = 0x0004
        const val ESTOP = 0x0008
    }

    /** 控制字位定义。 */
    object ControlBits {
        const val START = 0x0001
        const val PAUSE = 0x0002
        const val STOP = 0x0004
        const val HOME = 0x0008
    }

    /** 命令类型字定义。 */
    object CmdTypes {
        const val RAPID = 1
        const val LINEAR = 2
        const val ARC_CW = 3
        const val ARC_CCW = 4
    }

    companion object {
        /** 从后台下发的键值参数解析（缺省键用默认值，非法值抛异常由调用方回退）。 */
        fun from(params: Map<String, String>): ModbusRegisterMap {
            fun i(key: String, def: Int) = params[key]?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: def
            fun l(key: String, def: Long) = params[key]?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: def
            fun d(key: String, def: Double) = params[key]?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: def
            fun b(key: String, def: Boolean) =
                params[key]?.trim()?.let { it.equals("true", true) || it == "1" } ?: def
            return ModbusRegisterMap(
                posXAddr = i("pos_x_addr", 0),
                posZAddr = i("pos_z_addr", 2),
                posScale = d("pos_scale", 100.0),
                pos32Bit = b("pos_32bit", true),
                spindleRpmAddr = i("spindle_rpm_addr", 4),
                statusWordAddr = i("status_word_addr", 6),
                controlWordAddr = i("control_word_addr", 8),
                cmdTypeAddr = i("cmd_type_addr", 16),
                cmdXAddr = i("cmd_x_addr", 10),
                cmdZAddr = i("cmd_z_addr", 12),
                cmdFeedAddr = i("cmd_feed_addr", 14),
                cmdCxAddr = i("cmd_cx_addr", 18),
                cmdCzAddr = i("cmd_cz_addr", 20),
                spindleCmdAddr = i("spindle_cmd_addr", 22),
                spindleOnAddr = i("spindle_on_addr", 23),
                inputStart = i("input_start", 0),
                inputCount = i("input_count", 16),
                outputStart = i("output_start", 0),
                outputCount = i("output_count", 16),
                alarmStart = i("alarm_start", 24),
                alarmCount = i("alarm_count", 4),
                pollIntervalMs = l("poll_interval_ms", 200),
                commandTimeoutMs = l("command_timeout_ms", 10000)
            )
        }
    }
}
