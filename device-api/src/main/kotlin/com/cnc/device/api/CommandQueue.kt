package com.cnc.device.api

/**
 * 命令队列接口。
 *
 * 管理设备命令的完整生命周期：
 * CREATED → QUEUED → SENT → ACKNOWLEDGED → EXECUTING → COMPLETED
 *
 * 异常状态：SEND_FAILED, TIMEOUT, REJECTED, EXECUTION_FAILED, RESULT_UNKNOWN
 *
 * 对于执行结果未知（RESULT_UNKNOWN）的运动命令，禁止盲目重复发送，
 * 必须先查询设备状态或执行规定的恢复程序。
 */
interface CommandQueue {

    /** 待处理队列长度。 */
    val pendingCount: Int

    /** 是否已启动。 */
    val isRunning: Boolean

    /** 启动队列处理。 */
    fun start()

    /** 停止队列处理。 */
    fun stop()

    /**
     * 入队命令并返回一个可等待的结果。
     *
     * 调用方可以通过返回的 [CommandHandle] 等待命令完成、查询状态或取消。
     */
    fun enqueue(command: DeviceCommand): CommandHandle

    /** 查询命令状态。 */
    fun getStatus(commandId: String): CommandStatus

    /**
     * 检查命令是否可以安全重发。
     *
     * 只有状态为 SEND_FAILED 或 REJECTED 的命令才允许重发。
     * RESULT_UNKNOWN 状态的命令必须先查询设备状态。
     */
    fun canResend(commandId: String): Boolean

    /** 清空队列（保留状态记录）。 */
    fun clear()

    /** 获取所有命令状态快照（用于诊断）。 */
    fun snapshot(): Map<String, CommandStatus>
}

/**
 * 命令句柄，用于跟踪入队命令的执行。
 */
interface CommandHandle {
    /** 命令 ID。 */
    val commandId: String

    /** 当前状态。 */
    val status: CommandStatus

    /**
     * 挂起等待命令执行完成（成功或失败）。
     * @return 最终命令状态
     */
    suspend fun await(): CommandStatus

    /** 是否已完成（终态）。 */
    val isCompleted: Boolean
}
