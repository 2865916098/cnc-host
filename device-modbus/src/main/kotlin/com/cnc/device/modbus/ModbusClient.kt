package com.cnc.device.modbus

/**
 * Modbus TCP 客户端。
 *
 * 实现驱动骨架所需的功能码子集：
 * - 0x01 读线圈 / 0x02 读离散输入 / 0x05 写单线圈
 * - 0x03 读保持寄存器 / 0x04 读输入寄存器
 * - 0x06 写单寄存器 / 0x10 写多寄存器
 *
 * 帧格式（MBAP）：事务ID(2) + 协议ID(2=0) + 长度(2) + 站号(1) + PDU。
 */
class ModbusClient(
    private val transport: ModbusTransport,
    private val unitId: Int = 1
) {
    private var transactionId: Int = 0

    private fun nextTransactionId(): Int = synchronized(this) {
        transactionId = (transactionId + 1) and 0xFFFF
        transactionId
    }

    val isConnected: Boolean get() = transport.isConnected

    suspend fun open() = transport.open()

    fun close() = transport.close()

    // ===== 寄存器读写 =====

    /** 读保持寄存器（0x03），返回无符号寄存器值数组。 */
    suspend fun readHoldingRegisters(address: Int, count: Int): IntArray =
        readRegisters(0x03, address, count)

    /** 读输入寄存器（0x04）。 */
    suspend fun readInputRegisters(address: Int, count: Int): IntArray =
        readRegisters(0x04, address, count)

    private suspend fun readRegisters(fc: Int, address: Int, count: Int): IntArray {
        require(count in 1..125) { "读取数量越界: $count" }
        val pdu = byteArrayOf(
            fc.toByte(),
            hi(address), lo(address),
            hi(count), lo(count)
        )
        val resp = execute(pdu, fc)
        val byteCount = resp[1].toInt() and 0xFF
        if (byteCount != count * 2 || resp.size < 2 + byteCount) {
            throw ModbusException("寄存器响应长度不符: byteCount=$byteCount 期望 ${count * 2}")
        }
        return IntArray(count) { i ->
            ((resp[2 + i * 2].toInt() and 0xFF) shl 8) or (resp[3 + i * 2].toInt() and 0xFF)
        }
    }

    /** 写单寄存器（0x06）。 */
    suspend fun writeSingleRegister(address: Int, value: Int) {
        val pdu = byteArrayOf(0x06, hi(address), lo(address), hi(value), lo(value))
        execute(pdu, 0x06)
    }

    /** 写多寄存器（0x10）。 */
    suspend fun writeMultipleRegisters(address: Int, values: IntArray) {
        require(values.size in 1..123) { "写入数量越界: ${values.size}" }
        val pdu = ByteArray(6 + values.size * 2)
        pdu[0] = 0x10
        pdu[1] = hi(address); pdu[2] = lo(address)
        pdu[3] = hi(values.size); pdu[4] = lo(values.size)
        pdu[5] = (values.size * 2).toByte()
        values.forEachIndexed { i, v ->
            pdu[6 + i * 2] = hi(v)
            pdu[7 + i * 2] = lo(v)
        }
        execute(pdu, 0x10)
    }

    // ===== 线圈 / 离散输入 =====

    /** 读线圈（0x01）。 */
    suspend fun readCoils(address: Int, count: Int): BooleanArray =
        readBits(0x01, address, count)

    /** 读离散输入（0x02）。 */
    suspend fun readDiscreteInputs(address: Int, count: Int): BooleanArray =
        readBits(0x02, address, count)

    private suspend fun readBits(fc: Int, address: Int, count: Int): BooleanArray {
        require(count in 1..2000) { "读取数量越界: $count" }
        val pdu = byteArrayOf(fc.toByte(), hi(address), lo(address), hi(count), lo(count))
        val resp = execute(pdu, fc)
        val byteCount = resp[1].toInt() and 0xFF
        if (byteCount != (count + 7) / 8 || resp.size < 2 + byteCount) {
            throw ModbusException("位响应长度不符: byteCount=$byteCount 期望 ${(count + 7) / 8}")
        }
        return BooleanArray(count) { i ->
            (resp[2 + i / 8].toInt() and (1 shl (i % 8))) != 0
        }
    }

    /** 写单线圈（0x05）。 */
    suspend fun writeSingleCoil(address: Int, on: Boolean) {
        val pdu = byteArrayOf(
            0x05, hi(address), lo(address),
            if (on) 0xFF.toByte() else 0x00, 0x00
        )
        execute(pdu, 0x05)
    }

    // ===== 帧编解码 =====

    private fun buildRequest(pdu: ByteArray): ByteArray {
        val tid = nextTransactionId()
        val length = pdu.size + 1 // 站号 + PDU
        return byteArrayOf(
            hi(tid), lo(tid),
            0, 0,
            hi(length), lo(length),
            unitId.toByte()
        ) + pdu
    }

    /**
     * 发送 PDU 并校验响应。
     *
     * @return 响应 PDU（功能码字节起，不含 MBAP 头）。
     */
    private suspend fun execute(pdu: ByteArray, expectedFc: Int): ByteArray {
        val request = buildRequest(pdu)
        val response = transport.transact(request)
        if (response.size < 8) throw ModbusException("响应帧过短: ${response.size} 字节")
        // 事务 ID 必须匹配，防止旧响应错位串包
        val respTid = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
        val reqTid = ((request[0].toInt() and 0xFF) shl 8) or (request[1].toInt() and 0xFF)
        if (respTid != reqTid) {
            throw ModbusException("事务ID不匹配: 请求=$reqTid 响应=$respTid")
        }
        val fc = response[7].toInt() and 0xFF
        if (fc == (expectedFc or 0x80)) {
            val code = if (response.size > 8) response[8].toInt() and 0xFF else -1
            throw ModbusException("Modbus 异常响应: 功能码=0x${Integer.toHexString(fc)} 异常码=$code")
        }
        if (fc != expectedFc) {
            throw ModbusException("功能码不匹配: 期望=0x${Integer.toHexString(expectedFc)} 实收=0x${Integer.toHexString(fc)}")
        }
        return response.copyOfRange(7, response.size)
    }

    companion object {
        internal fun hi(v: Int): Byte = ((v shr 8) and 0xFF).toByte()
        internal fun lo(v: Int): Byte = (v and 0xFF).toByte()
    }
}
