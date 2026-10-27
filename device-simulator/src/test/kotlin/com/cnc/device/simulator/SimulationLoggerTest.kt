package com.cnc.device.simulator

import com.cnc.model.logging.LogCategory
import com.cnc.model.logging.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 模拟日志系统测试。
 */
class SimulationLoggerTest {

    private lateinit var logger: SimulationLogger

    @Before
    fun setUp() {
        logger = SimulationLogger()
    }

    @Test
    fun `log entries are recorded with correct level and category`() {
        logger.info(LogCategory.MOTION, "test motion")
        logger.error(LogCategory.ALARM, "test alarm")

        val logs = logger.logs.value
        assertEquals(2, logs.size)
        assertEquals(LogLevel.INFO, logs[0].level)
        assertEquals(LogCategory.MOTION, logs[0].category)
        assertEquals(LogLevel.ERROR, logs[1].level)
        assertEquals(LogCategory.ALARM, logs[1].category)
    }

    @Test
    fun `exportAsText produces non-empty output`() {
        logger.info(LogCategory.SYSTEM, "hello")
        val text = logger.exportAsText()
        assertTrue(text.contains("hello"))
        assertTrue(text.contains("INFO"))
    }

    @Test
    fun `clear removes all entries`() {
        logger.info(LogCategory.SYSTEM, "a")
        logger.info(LogCategory.SYSTEM, "b")
        assertEquals(2, logger.logs.value.size)
        logger.clear()
        assertEquals(0, logger.logs.value.size)
    }

    @Test
    fun `max entries limit is enforced`() {
        val smallLogger = SimulationLogger(maxEntries = 5)
        for (i in 1..10) {
            smallLogger.info(LogCategory.SYSTEM, "msg $i")
        }
        assertEquals(5, smallLogger.logs.value.size)
        // 保留最新的 5 条
        assertEquals("msg 10", smallLogger.logs.value.last().message)
    }

    @Test
    fun `taskId is associated with log entry`() {
        logger.info(LogCategory.TASK, "task log", taskId = "T-001")
        assertEquals("T-001", logger.logs.value.first().taskId)
    }
}
