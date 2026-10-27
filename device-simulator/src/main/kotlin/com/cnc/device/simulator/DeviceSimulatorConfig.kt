package com.cnc.device.simulator

/**
 * 模拟控制卡配置。
 *
 * 用于配置模拟器的行为参数，包括运动、IO、时序和故障注入。
 * 所有参数均为模拟值，不代表真实设备能力。
 */
data class DeviceSimulatorConfig(
    /** X 轴行程（mm）。 */
    val xTravelMm: Double = 200.0,
    /** Z 轴行程（mm）。 */
    val zTravelMm: Double = 500.0,
    /** 最大进给速度（mm/min）。 */
    val maxFeedMmPerMin: Double = 5000.0,
    /** 最大加速度（mm/s²）。 */
    val maxAccelMmPerSec2: Double = 500.0,
    /** 主轴最大转速（rpm）。 */
    val maxSpindleRpm: Int = 3000,

    /** 命令 ACK 延迟（ms）。 */
    val ackDelayMs: Long = 50,
    /** 命令执行检查间隔（ms）。 */
    val commandTickMs: Long = 30,
    /** 状态发布间隔（ms）。 */
    val statusTickMs: Long = 500,

    /** 夹紧动作模拟耗时（ms）。 */
    val clampActionMs: Long = 200,
    /** 送料动作模拟耗时（ms）。 */
    val feedActionMs: Long = 300,
    /** 松料动作模拟耗时（ms）。 */
    val unclampActionMs: Long = 150,

    /** IO 输入点数量。 */
    val inputCount: Int = 16,
    /** IO 输出点数量。 */
    val outputCount: Int = 16,

    /** 默认刀具数量。 */
    val toolCount: Int = 8,

    /** 是否启用随机故障（用于压力测试）。 */
    val enableRandomFaults: Boolean = false,
    /** 随机故障概率（0.0 ~ 1.0）。 */
    val randomFaultProbability: Double = 0.0,

    /** 断线后是否允许自动重连。 */
    val autoReconnect: Boolean = false,
    /** 自动重连延迟（ms）。 */
    val reconnectDelayMs: Long = 1000
)
