package com.cnc.model.device

import com.cnc.model.common.ConnectionType
import com.cnc.model.common.VersionedEntity

/** 校验方式。 */
enum class ChecksumType {
    NONE,
    CRC16,
    CRC32,
    XOR,
    SUM
}

/**
 * 通讯协议配置。
 *
 * 控制卡协议未确定时，先实现统一接口、模拟协议和协议配置框架。
 * 不得假定不同控制卡使用相同指令格式。
 */
data class ProtocolProfile(
    override val id: String,
    override val version: Int = 1,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    val name: String,
    val connectionType: ConnectionType,
    val baudRate: Int = 115200,
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val parity: String = "none",
    val host: String = "192.168.1.100",
    val port: Int = 8080,
    val checksum: ChecksumType = ChecksumType.NONE,
    val timeoutMs: Long = 2000,
    val heartbeatIntervalMs: Long = 1000,
    val requiresAck: Boolean = true,
    val maxRetries: Int = 3,
    val autoReconnect: Boolean = true,
    val logLevel: String = "INFO"
) : VersionedEntity
