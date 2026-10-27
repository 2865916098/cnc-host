package com.cnc.device.simulator

import com.cnc.device.api.CommandHandle
import com.cnc.device.api.CommandQueue
import com.cnc.device.api.CommandStatus
import com.cnc.device.api.DeviceCommand
import com.cnc.model.logging.LogCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模拟命令队列。
 *
 * 管理命令的完整生命周期：
 * CREATED → QUEUED → SENT → ACKNOWLEDGED → EXECUTING → COMPLETED
 *
 * 异常状态：SEND_FAILED, TIMEOUT, REJECTED, EXECUTION_FAILED, RESULT_UNKNOWN
 *
 * 关键安全设计：
 * - 队列是命令执行的唯一入口，避免重复执行
 * - 每个命令使用自身的 timeoutMs 字段控制超时
 * - RESULT_UNKNOWN 状态的命令禁止重发，必须先查询设备状态
 * - 线程安全：ConcurrentHashMap + Mutex 保护共享状态
 * - clear()/stop() 正确完成所有等待中的 deferred，不会产生永久挂起
 */
class SimulatedCommandQueue(
    private val config: DeviceSimulatorConfig,
    private val logger: SimulationLogger,
    private val executor: suspend (DeviceCommand) -> CommandExecutionResult
) : CommandQueue {

    /** 命令执行结果。 */
    data class CommandExecutionResult(
        val status: CommandStatus,
        val error: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ConcurrentLinkedQueue<QueuedCommand>()

    /** 线程安全的命令存储（完整命令对象，含 sentAt/ackInfo/error 追踪字段）。 */
    private val commands = ConcurrentHashMap<String, DeviceCommand>()

    /** 线程安全的命令状态索引。 */
    private val commandStates = ConcurrentHashMap<String, CommandStatus>()

    /** 线程安全的句柄索引。 */
    private val handles = ConcurrentHashMap<String, SimulatedCommandHandle>()

    /** 保护 stop()/clear() 与 processCommand() 之间的竞态。 */
    private val stopMutex = Mutex()

    private val running = AtomicBoolean(false)
    private var workerJob: Job? = null

    /** 内部包装：命令 + 完成信号。 */
    private data class QueuedCommand(
        val command: DeviceCommand,
        val deferred: CompletableDeferred<CommandStatus>
    )

    override val pendingCount: Int get() = queue.size
    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.compareAndSet(false, true)) {
            logger.info(LogCategory.COMMUNICATION, "命令队列已启动")
            workerJob = scope.launch { workerLoop() }
        }
    }

    /**
     * 停止队列。幂等操作，多次调用安全。
     * 所有未完成的命令标记为 RESULT_UNKNOWN 并完成 deferred。
     */
    override fun stop() {
        val wasRunning = running.compareAndSet(true, false)
        if (wasRunning) {
            workerJob?.cancel()
            workerJob = null
        }

        // 同步完成所有仍在等待中的命令
        val iterator = handles.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val handle = entry.value
            if (handle.deferred.complete(CommandStatus.RESULT_UNKNOWN)) {
                // deferred 刚被 complete，说明这个命令还没自然结束
                commandStates[entry.key] = CommandStatus.RESULT_UNKNOWN
                updateCommand(entry.key) { it.copy(status = CommandStatus.RESULT_UNKNOWN) }
                logger.info(
                    LogCategory.COMMUNICATION,
                    "命令 ${entry.key} 在队列停止时标记为 RESULT_UNKNOWN"
                )
            }
            // 无论是否刚 complete，该命令的记录在 stop 后不再需要清理
            iterator.remove()
        }
        queue.clear()
        logger.info(LogCategory.COMMUNICATION, "命令队列已停止")
    }

    override fun enqueue(command: DeviceCommand): CommandHandle {
        val deferred = CompletableDeferred<CommandStatus>()
        val queued = command.copy(
            status = CommandStatus.QUEUED,
            sentAt = null // 入队时尚未发送
        )
        commands[command.commandId] = queued
        commandStates[command.commandId] = CommandStatus.QUEUED
        val handle = SimulatedCommandHandle(command.commandId, deferred, this)
        handles[command.commandId] = handle
        queue.add(QueuedCommand(queued, deferred))
        logger.debug(
            LogCategory.COMMUNICATION,
            "命令 ${command.commandId} (${command.type}) 已入队，超时=${command.timeoutMs}ms",
            command.taskId
        )
        return handle
    }

    override fun getStatus(commandId: String): CommandStatus =
        commandStates[commandId] ?: CommandStatus.RESULT_UNKNOWN

    override fun canResend(commandId: String): Boolean {
        return when (getStatus(commandId)) {
            CommandStatus.SEND_FAILED,
            CommandStatus.REJECTED -> true
            // RESULT_UNKNOWN 必须先查询设备状态，禁止重发
            CommandStatus.RESULT_UNKNOWN -> false
            else -> false
        }
    }

    /**
     * 清空待执行命令。
     *
     * 判定标准：状态仍为 QUEUED 且 deferred 还没被完成 = 待执行。
     * 已进入 SENT/ACKNOWLEDGED/EXECUTING 的命令（即使还没 complete）不受影响，
     * 继续走完自然流程。
     */
    override fun clear() {
        var rejectedCount = 0
        val iter = handles.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val id = entry.key
            val handle = entry.value
            if (handle.deferred.isCompleted) {
                iter.remove()
                continue
            }
            // 只有仍在 QUEUED 状态的命令才视为"待执行"
            if (commandStates[id] == CommandStatus.QUEUED) {
                if (handle.deferred.complete(CommandStatus.REJECTED)) {
                    commandStates[id] = CommandStatus.REJECTED
                    updateCommand(id) {
                        it.copy(status = CommandStatus.REJECTED, error = "队列已清空")
                    }
                    rejectedCount++
                    iter.remove()
                    // 尝试从 queue 中移除（如果还在）
                    queue.removeIf { it.command.commandId == id }
                    logger.info(LogCategory.COMMUNICATION, "命令 $id 因队列清空被拒绝")
                }
            }
        }
        queue.clear() // 兜底清理
        logger.info(
            LogCategory.COMMUNICATION,
            "命令队列已清空，拒绝 $rejectedCount 条待执行命令"
        )
    }

    override fun snapshot(): Map<String, CommandStatus> = commandStates.toMap()

    /** 获取命令句柄。 */
    fun getHandle(commandId: String): CommandHandle? = handles[commandId]

    /** 获取完整命令对象（含 sentAt、ackInfo、error 等追踪字段）。 */
    fun getCommand(commandId: String): DeviceCommand? = commands[commandId]

    private suspend fun workerLoop() {
        while (scope.isActive && running.get()) {
            val queued = queue.poll()
            if (queued != null) {
                // 在 stopMutex 保护下执行，避免 stop() 与 processCommand 竞态
                stopMutex.withLock {
                    if (running.get()) {
                        processCommand(queued)
                    } else {
                        // 队列已停止，该命令应被标记为 RESULT_UNKNOWN
                        queued.deferred.complete(CommandStatus.RESULT_UNKNOWN)
                    }
                }
            } else {
                delay(config.commandTickMs)
            }
        }
    }

    private suspend fun processCommand(queued: QueuedCommand) {
        val command = queued.command
        val id = command.commandId
        val deferred = queued.deferred

        // 如果 deferred 已被外部 complete（clear/stop），跳过
        if (deferred.isCompleted) {
            return
        }

        // 设置发送时间
        val sentAt = System.currentTimeMillis()
        updateCommand(id) { it.copy(sentAt = sentAt, status = CommandStatus.SENT) }
        commandStates[id] = CommandStatus.SENT
        logger.debug(LogCategory.COMMUNICATION, "命令 $id 已发送", command.taskId)

        // ACK 延迟
        delay(config.ackDelayMs)
        if (!deferred.isCompleted) {
            updateCommand(id) {
                it.copy(status = CommandStatus.ACKNOWLEDGED, ackInfo = "SIM-ACK-$id")
            }
            commandStates[id] = CommandStatus.ACKNOWLEDGED
            logger.debug(LogCategory.COMMUNICATION, "命令 $id 已确认(ACK)", command.taskId)
        }

        // EXECUTING
        if (!deferred.isCompleted) {
            commandStates[id] = CommandStatus.EXECUTING
            updateCommand(id) { it.copy(status = CommandStatus.EXECUTING) }
            logger.debug(LogCategory.MOTION, "命令 $id 执行中", command.taskId)
        }

        // 如果执行期间 deferred 被外部 complete，不再继续执行
        if (deferred.isCompleted) return

        // 使用命令自身的超时时间执行
        val timeoutMs = command.timeoutMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val result = try {
            withTimeout(timeoutMs) {
                executor(command)
            }
        } catch (e: TimeoutCancellationException) {
            // 超时：结果未知，禁止重发
            logger.warn(LogCategory.COMMUNICATION, "命令 $id 超时（${timeoutMs}ms），结果未知", command.taskId)
            CommandExecutionResult(CommandStatus.RESULT_UNKNOWN, "命令超时")
        } catch (e: Exception) {
            logger.error(LogCategory.MOTION, "命令 $id 执行异常: ${e.message}", command.taskId)
            CommandExecutionResult(CommandStatus.EXECUTION_FAILED, e.message)
        }

        // 如果 deferred 已被外部 complete，不覆盖状态
        if (!deferred.isCompleted) {
            commandStates[id] = result.status
            updateCommand(id) {
                it.copy(
                    status = result.status,
                    error = result.error
                )
            }

            when (result.status) {
                CommandStatus.COMPLETED ->
                    logger.info(LogCategory.MOTION, "命令 $id 执行完成", command.taskId)
                CommandStatus.EXECUTION_FAILED ->
                    logger.error(LogCategory.MOTION, "命令 $id 执行失败: ${result.error}", command.taskId)
                CommandStatus.RESULT_UNKNOWN ->
                    logger.warn(
                        LogCategory.MOTION,
                        "命令 $id 结果未知，禁止盲目重发，需查询设备状态",
                        command.taskId
                    )
                CommandStatus.REJECTED ->
                    logger.warn(LogCategory.COMMUNICATION, "命令 $id 被拒绝: ${result.error}", command.taskId)
                CommandStatus.SEND_FAILED ->
                    logger.error(LogCategory.COMMUNICATION, "命令 $id 发送失败: ${result.error}", command.taskId)
                CommandStatus.TIMEOUT ->
                    logger.warn(LogCategory.COMMUNICATION, "命令 $id 超时", command.taskId)
                else -> {}
            }

            // 通知等待者（如果还没被 complete）
            deferred.complete(result.status)
        }
    }

    /** 更新命令记录（持久化 sentAt、ackInfo、error、status 等到命令对象）。 */
    private fun updateCommand(commandId: String, transform: (DeviceCommand) -> DeviceCommand) {
        commands[commandId]?.let { commands[commandId] = transform(it) }
    }

    /**
     * 模拟命令句柄。
     */
    private class SimulatedCommandHandle(
        override val commandId: String,
        val deferred: CompletableDeferred<CommandStatus>,
        private val queue: SimulatedCommandQueue
    ) : CommandHandle {

        override val status: CommandStatus
            get() = queue.getStatus(commandId)

        override val isCompleted: Boolean
            get() = deferred.isCompleted

        override suspend fun await(): CommandStatus {
            return deferred.await()
        }
    }
}
