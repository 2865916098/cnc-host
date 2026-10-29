package com.cnc.device.modbus

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Modbus 客户端帧编解码测试（假传输层，不接触真实硬件）。 */
class ModbusClientTest {

    @Test
    fun `读保持寄存器请求帧与响应解析`() = runBlocking {
        val fake = FakeModbusTransport()
        fake.open()
        fake.holding[10] = 0x1234
        fake.holding[11] = 0x00FF
        val client = ModbusClient(fake, unitId = 7)

        val regs = client.readHoldingRegisters(10, 2)

        assertArrayEquals(intArrayOf(0x1234, 0x00FF), regs)
        val req = fake.sentRequests.single()
        // MBAP：事务ID + 协议0 + 长度6 + 站号7
        assertEquals(0, req[2].toInt()); assertEquals(0, req[3].toInt())
        assertEquals(0, req[4].toInt()); assertEquals(6, req[5].toInt())
        assertEquals(7, req[6].toInt())
        // PDU：0x03 addr=10 count=2
        assertEquals(0x03, req[7].toInt())
        assertEquals(0, req[8].toInt()); assertEquals(10, req[9].toInt())
        assertEquals(0, req[10].toInt()); assertEquals(2, req[11].toInt())
    }

    @Test
    fun `写多寄存器请求帧正确`() = runBlocking {
        val fake = FakeModbusTransport()
        fake.open()
        val client = ModbusClient(fake, unitId = 1)

        client.writeMultipleRegisters(20, intArrayOf(0x00E8, 0x0001))

        assertEquals(0x00E8, fake.holding[20])
        assertEquals(0x0001, fake.holding[21])
        val req = fake.sentRequests.single()
        assertEquals(0x10, req[7].toInt())
        assertEquals(20, req[9].toInt())
        assertEquals(2, req[11].toInt())
        assertEquals(4, req[12].toInt())
    }

    @Test
    fun `写单线圈与读回`() = runBlocking {
        val fake = FakeModbusTransport()
        fake.open()
        val client = ModbusClient(fake)

        client.writeSingleCoil(5, true)
        assertTrue(fake.coils[5])
        val bits = client.readCoils(4, 4)
        assertEquals(listOf(false, true, false, false), bits.toList())
    }

    @Test
    fun `异常响应抛出 ModbusException 并带异常码`() = runBlocking {
        val fake = FakeModbusTransport()
        fake.open()
        fake.forceExceptionCode = 0x02 // 非法数据地址
        val client = ModbusClient(fake)
        try {
            client.readHoldingRegisters(0, 1)
            fail("应抛出 ModbusException")
        } catch (e: ModbusException) {
            assertTrue(e.message!!.contains("异常码=2"))
        }
    }

    @Test
    fun `事务ID不匹配拒绝响应`() = runBlocking {
        val tampered = object : ModbusTransport {
            override val isConnected = true
            override suspend fun open() {}
            override fun close() {}
            override suspend fun transact(request: ByteArray): ByteArray {
                // 返回错误事务ID的合法外形帧
                return byteArrayOf(0x7F, 0x7F, 0, 0, 0, 3, 1, 0x03, 0x00)
            }
        }
        val client = ModbusClient(tampered)
        try {
            client.readHoldingRegisters(0, 1)
            fail("应抛出事务ID不匹配异常")
        } catch (e: ModbusException) {
            assertTrue(e.message!!.contains("事务ID"))
        }
    }
}
