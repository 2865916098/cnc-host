package com.cnc.device.simulator

import com.cnc.device.api.CommandStatus
import com.cnc.device.api.CommandType
import com.cnc.device.api.DeviceCommand
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 命令队列测试。
 *
 * 验证命令状态流转、超时处理和 RESULT_UNKNOWN 安全策略。
 *
 * 分层测试：
 * - 基础功能：入队、状态查询、snapshot
 * - 完整生命周期：await、lifecycle、持久化字段
 * - 安全策略：canResend、timeout→RESULT_UNKNOWN、execute once
 * - 边界：stop 幂等、clear→REJECTED、executor 返回 SEND_FAILED/REJECTED
 * - 竞态：stop 与 processCommand 同时发生
 */
class SimulatedCommandQueueTest {

    private lateinit var logger: SimulationLogger
    private lateinit var queue: SimulatedCommandQueue

    @Before
    fun setUp() {
        logger = SimulationLogger()
        queue = SimulatedCommandQueue(
            config = DeviceSimulatorConfig(ackDelayMs = 10, commandTickMs = 10),
            logger = logger,
            executor = { cmd ->
                SimulatedCommandQueue.CommandExecutionResult(CommandStatus.COMPLETED)
            }
        )
    }

    @After
    fun tearDown() {
        queue.stop()
    }

    // ==================== 基础功能 ====================

    @Test
    fun `enqueue returns handle with QUEUED status`() {
        val cmd = DeviceCommand("c1", null, CommandType.RAPID_MOVE)
        val handle = queue.enqueue(cmd)
        assertEquals("c1", handle.commandId)
        assertEquals(CommandStatus.QUEUED, handle.status)
        assertEquals(CommandStatus.QUEUED, queue.getStatus("c1"))
    }

    @Test
    fun `unknown command returns RESULT_UNKNOWN`() {
        assertEquals(CommandStatus.RESULT_UNKNOWN, queue.getStatus("nonexistent"))
    }

    @Test
    fun `snapshot returns all command states`() = runBlocking {
        queue.start()
        queue.enqueue(DeviceCommand("c3", null, CommandType.STOP))
        queue.enqueue(DeviceCommand("c4", null, CommandType.STOP))
        Thread.sleep(200)
        val snapshot = queue.snapshot()
        assertTrue(snapshot.containsKey("c3"))
        assertTrue(snapshot.containsKey("c4"))
    }

    // ==================== 完整生命周期 ====================

    @Test
    fun `await returns COMPLETED after execution`() = runBlocking {
        queue.start()
        val handle = queue.enqueue(DeviceCommand("c7", null, CommandType.STOP))
        val status = handle.await()
        assertEquals(CommandStatus.COMPLETED, status)
        assertTrue(handle.isCompleted)
    }

    @Test
    fun `handle status reflects lifecycle`() = runBlocking {
        // 先入队再启动，避免 worker 抢先把状态推到 SENT 造成断言竞态
        val handle = queue.enqueue(DeviceCommand("c11", null, CommandType.STOP))
        assertEquals(CommandStatus.QUEUED, handle.status)
        queue.start()
        handle.await()
        assertEquals(CommandStatus.COMPLETED, handle.status)
    }

    @Test
    fun `getCommand returns persisted command with sentAt and ackInfo`() = runBlocking {
        queue.start()
        val cmd = DeviceCommand("c13", null, CommandType.RAPID_MOVE)
        val handle = queue.enqueue(cmd)
        handle.await()
        val persisted = queue.getCommand("c13")
        assertEquals(CommandStatus.COMPLETED, persisted?.status)
        assertNotNull(persisted?.sentAt)
        assertEquals("SIM-ACK-c13", persisted?.ackInfo)
    }

    @Test
    fun `command is executed exactly once`() = runBlocking {
        var execCount = 0
        val countingQueue = SimulatedCommandQueue(
            config = DeviceSimulatorConfig(ackDelayMs = 10, commandTickMs = 10),
            logger = SimulationLogger(),
            executor = {
                execCount++
                SimulatedCommandQueue.CommandExecutionResult(CommandStatus.COMPLETED)
            }
        )
        countingQueue.start()
        val handle = countingQueue.enqueue(DeviceCommand("c9", null, CommandType.STOP))
        handle.await()
        assertEquals(1, execCount)
        countingQueue.stop()
    }

    // ==================== 安全策略 ====================

    @Test
    fun `canResend returns false for RESULT_UNKNOWN`() {
        assertFalse(queue.canResend("unknown-cmd"))
    }

    @Test
    fun `canResend returns false for COMPLETED command`() = runBlocking {
        queue.start()
        val handle = queue.enqueue(DeviceCommand("c10", null, CommandType.STOP))
        handle.await()
        assertFalse(queue.canResend("c10"))
    }

    @Test
    fun `canResend returns false for running command after completion`() = runBlocking {
        queue.start()
        val cmd = DeviceCommand("c2", null, CommandType.RAPID_MOVE)
        queue.enqueue(cmd)
        Thread.sleep(200)
        assertFalse(queue.canResend("c2"))
    }

    @Test
    fun `per-command timeout produces RESULT_UNKNOWN`() = runBlocking {
        val slowQueue = SimulatedCommandQueue(
            config = DeviceSimulatorConfig(ackDelayMs = 10, commandTickMs = 10),
            logger = SimulationLogger(),
            executor = {
                kotlinx.coroutines.delay(500)
                SimulatedCommandQueue.CommandExecutionResult(CommandStatus.COMPLETED)
            }
        )
        slowQueue.start()
        val cmd = DeviceCommand("c8", null, CommandType.RAPID_MOVE, timeoutMs = 100)
        val handle = slowQueue.enqueue(cmd)
        val status = handle.await()
        assertEquals(CommandStatus.RESULT_UNKNOWN, status)
        assertFalse(slowQueue.canResend("c8"))
        slowQueue.stop()
    }

    // ==================== 边界：stop ====================

    @Test
    fun `stopped queue marks pending commands as RESULT_UNKNOWN`() = runBlocking {
        val handle = queue.enqueue(DeviceCommand("c12", null, CommandType.STOP))
        queue.stop()
        val status = handle.await()
        assertEquals(CommandStatus.RESULT_UNKNOWN, status)
    }

    @Test
    fun `stop is idempotent - multiple calls safe`() = runBlocking {
        val handle = queue.enqueue(DeviceCommand("c_idem", null, CommandType.STOP))
        queue.stop()
        queue.stop() // 第二次 stop 不应抛异常
        val status = handle.await()
        assertEquals(CommandStatus.RESULT_UNKNOWN, status)
    }

    @Test
    fun `stop during execution produces RESULT_UNKNOWN for in-flight`() = runBlocking {
        // 慢速 executor：stop 时正在执行
        val slowQueue = SimulatedCommandQueue(
            config = DeviceSimulatorConfig(ackDelayMs = 5, commandTickMs = 5),
            logger = SimulationLogger(),
            executor = {
                kotlinx.coroutines.delay(10_000) // 不会跑完，会被 stop 中断
                SimulatedCommandQueue.CommandExecutionResult(CommandStatus.COMPLETED)
            }
        )
        slowQueue.start()
        val handle = slowQueue.enqueue(DeviceCommand("c_inflight", null, CommandType.STOP))
        Thread.sleep(50) // 让 worker 开始处理
        slowQueue.stop()
        val status = handle.await()
        assertEquals(CommandStatus.RESULT_UNKNOWN, status)
        assertFalse(handle.isCompleted.not()) // 应该已经 complete
    }

    // ==================== 边界：clear ====================

    @Test
    fun `clear empties pending queue`() {
        queue.enqueue(DeviceCommand("c5", null, CommandType.STOP))
        queue.enqueue(DeviceCommand("c6", null, CommandType.STOP))
        queue.clear()
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `clear marks pending commands as REJECTED and completes deferred`() = runBlocking {
        // 不启动队列，直接入队 → 所有命令保持 QUEUED，没有 race
        val h1 = queue.enqueue(DeviceCommand("c_clear1", null, CommandType.STOP))
        val h2 = queue.enqueue(DeviceCommand("c_clear2", null, CommandType.STOP))
        assertEquals(CommandStatus.QUEUED, queue.getStatus("c_clear1"))
        queue.clear()
        // deferred 必须已完成（不挂起）
        assertEquals(CommandStatus.REJECTED, h1.await())
        assertEquals(CommandStatus.REJECTED, h2.await())
        // canResend 对 REJECTED 应返回 true
        assertTrue(queue.canResend("c_clear1"))
        assertTrue(queue.canResend("c_clear2"))
        // pendingCount 归零
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `clear does not affect already executing commands`() = runBlocking {
        // 一个慢速 executor，clear 时它已经在处理了
        val slowQueue = SimulatedCommandQueue(
            config = DeviceSimulatorConfig(ackDelayMs = 5, commandTickMs = 5),
            logger = SimulationLogger(),
            executor = {
                kotlinx.coroutines.delay(50)
                SimulatedCommandQueue.CommandExecutionResult(CommandStatus.COMPLETED)
            }
        )
        slowQueue.start()
        val h1 = slowQueue.enqueue(DeviceCommand("c_exec1", null, CommandType.STOP))
        Thread.sleep(30) // h1 进入 SENT 之后
        slowQueue.clear() // clear 不应影响正在执行的 h1
        val status = h1.await()
        assertEquals(CommandStatus.COMPLETED, status)
        slowQueue.stop()
    }

    // ==================== 边界：executor 返回 SEND_FAILED / REJECTED ====================

    @Test
    fun `executor returning SEND_FAILED allows canResend`() = runBlocking {
        val failQueue = SimulatedCommandQueue(
            config = DeviceSimulatorConfig(ackDelayMs = 10, commandTickMs = 10),
            logger = SimulationLogger(),
            executor = {
                SimulatedCommandQueue.CommandExecutionResult(
                    CommandStatus.SEND_FAILED,
                    "模拟发送失败"
                )
            }
        )
        failQueue.start()
        val handle = failQueue.enqueue(DeviceCommand("c_sendfail", null, CommandType.STOP))
        val status = handle.await()
        assertEquals(CommandStatus.SEND_FAILED, status)
        assertTrue(failQueue.canResend("c_sendfail"))
        failQueue.stop()
    }

    @Test
    fun `executor returning REJECTED allows canResend`() = runBlocking {
        val rejectQueue = SimulatedCommandQueue(
            config = DeviceSimulatorConfig(ackDelayMs = 10, commandTickMs = 10),
            logger = SimulationLogger(),
            executor = {
                SimulatedCommandQueue.CommandExecutionResult(
                    CommandStatus.REJECTED,
                    "参数错误"
                )
            }
        )
        rejectQueue.start()
        val handle = rejectQueue.enqueue(DeviceCommand("c_reject", null, CommandType.STOP))
        val status = handle.await()
        assertEquals(CommandStatus.REJECTED, status)
        assertTrue(rejectQueue.canResend("c_reject"))
        rejectQueue.stop()
    }

    @Test
    fun `executor throwing exception produces EXECUTION_FAILED`() = runBlocking {
        val crashQueue = SimulatedCommandQueue(
            config = DeviceSimulatorConfig(ackDelayMs = 10, commandTickMs = 10),
            logger = SimulationLogger(),
            executor = {
                throw RuntimeException("模拟执行崩溃")
            }
        )
        crashQueue.start()
        val handle = crashQueue.enqueue(DeviceCommand("c_crash", null, CommandType.STOP))
        val status = handle.await()
        assertEquals(CommandStatus.EXECUTION_FAILED, status)
        assertFalse(crashQueue.canResend("c_crash"))
        crashQueue.stop()
    }
}
