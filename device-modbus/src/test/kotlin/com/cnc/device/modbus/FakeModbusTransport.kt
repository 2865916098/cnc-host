package com.cnc.device.modbus

/**
 * 内存版假 Modbus 从站：256 个保持寄存器 + 64 离散输入 + 64 线圈。
 *
 * 仅用于单元测试，验证协议编解码与控制器流程，不接触真实硬件。
 */
class FakeModbusTransport : ModbusTransport {

    val holding = IntArray(256)
    val discrete = BooleanArray(64)
    val coils = BooleanArray(64)
    val sentRequests = mutableListOf<ByteArray>()

    /** 注入：下一次 transact 抛通讯异常。 */
    @Volatile var failNext = false

    /** 注入：打开连接即失败。 */
    @Volatile var failOpen = false

    /** 注入：所有请求均以指定异常码的异常帧应答。 */
    @Volatile var forceExceptionCode: Int? = null

    @Volatile private var open = false
    override val isConnected: Boolean get() = open

    override suspend fun open() {
        if (failOpen) throw ModbusException("注入：连接失败")
        open = true
    }

    override fun close() {
        open = false
    }

    override suspend fun transact(request: ByteArray): ByteArray {
        sentRequests += request.copyOf()
        if (failNext) {
            failNext = false
            throw ModbusException("注入：通讯中断")
        }
        if (!open) throw ModbusException("传输未连接")
        val fc = request[7].toInt() and 0xFF
        val forced = forceExceptionCode
        val pdu: ByteArray = if (forced != null) {
            byteArrayOf((fc or 0x80).toByte(), forced.toByte())
        } else when (fc) {
            0x03, 0x04 -> {
                val addr = u16(request, 8)
                val count = u16(request, 10)
                val body = ByteArray(1 + count * 2)
                body[0] = (count * 2).toByte()
                for (i in 0 until count) {
                    body[1 + i * 2] = ((holding[addr + i] shr 8) and 0xFF).toByte()
                    body[2 + i * 2] = (holding[addr + i] and 0xFF).toByte()
                }
                byteArrayOf(fc.toByte()) + body
            }

            0x06 -> {
                holding[u16(request, 8)] = u16(request, 10)
                request.copyOfRange(7, 12)
            }

            0x10 -> {
                val addr = u16(request, 8)
                val count = u16(request, 10)
                for (i in 0 until count) holding[addr + i] = u16(request, 13 + i * 2)
                byteArrayOf(0x10, request[8], request[9], request[10], request[11])
            }

            0x01, 0x02 -> {
                val addr = u16(request, 8)
                val count = u16(request, 10)
                val src = if (fc == 0x01) coils else discrete
                val bytes = (count + 7) / 8
                val body = ByteArray(1 + bytes)
                body[0] = bytes.toByte()
                for (i in 0 until count) {
                    if (src[addr + i]) {
                        body[1 + i / 8] = (body[1 + i / 8].toInt() or (1 shl (i % 8))).toByte()
                    }
                }
                byteArrayOf(fc.toByte()) + body
            }

            0x05 -> {
                coils[u16(request, 8)] = request[10].toInt() != 0
                request.copyOfRange(7, 12)
            }

            else -> byteArrayOf((fc or 0x80).toByte(), 0x01) // 非法功能码
        }
        val length = pdu.size + 1
        return byteArrayOf(
            request[0], request[1], 0, 0,
            ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte(),
            request[6]
        ) + pdu
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}
