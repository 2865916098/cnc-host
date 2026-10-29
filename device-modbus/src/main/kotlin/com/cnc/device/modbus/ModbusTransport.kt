package com.cnc.device.modbus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Modbus 协议异常（通讯失败、异常响应、帧非法等）。 */
class ModbusException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Modbus 传输层抽象：发送一个完整请求帧（含 MBAP 头），返回完整响应帧（含 MBAP 头）。
 *
 * 真实环境使用 [SocketModbusTransport]；单元测试注入假传输验证协议编解码与控制流程，
 * 全程不接触真实硬件。
 */
interface ModbusTransport {
    val isConnected: Boolean

    /** 建立连接。 */
    suspend fun open()

    /** 发送请求帧并等待响应帧。Modbus TCP 为请求-响应模型，调用方需自行串行化。 */
    suspend fun transact(request: ByteArray): ByteArray

    /** 关闭连接（可重复调用）。 */
    fun close()
}

/**
 * 基于 TCP Socket 的 Modbus TCP 传输（MBAP 帧）。
 *
 * 单连接串行收发：Modbus TCP 从站按到达顺序应答，同一 socket 不并发请求。
 */
class SocketModbusTransport(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 2000
) : ModbusTransport {

    private var socket: Socket? = null
    private val lock = Any()

    override val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    override suspend fun open() = withContext(Dispatchers.IO) {
        close()
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            socket = s
        } catch (e: Exception) {
            throw ModbusException("连接 $host:$port 失败: ${e.message}", e)
        }
    }

    override suspend fun transact(request: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val s = socket ?: throw ModbusException("传输未连接")
        synchronized(lock) {
            try {
                s.getOutputStream().apply { write(request); flush() }
                val input = DataInputStream(s.getInputStream())
                // MBAP 头固定 7 字节：事务ID(2) + 协议ID(2) + 长度(2) + 站号(1)
                val header = ByteArray(7)
                input.readFully(header)
                val length = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                if (length < 2) throw ModbusException("响应长度字段非法: $length")
                // length 包含站号字节；站号已在头部读出，剩余 length-1 字节为 PDU
                val pdu = ByteArray(length - 1)
                input.readFully(pdu)
                header + pdu
            } catch (e: ModbusException) {
                throw e
            } catch (e: Exception) {
                throw ModbusException("收发失败: ${e.message}", e)
            }
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
