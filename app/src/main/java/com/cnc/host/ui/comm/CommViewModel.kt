package com.cnc.host.ui.comm

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CommProtocol { MODBUS_TCP, MODBUS_RTU, SERIAL_TCP, UNKNOWN }
enum class BaudRate { B9600, B19200, B38400, B57600, B115200 }

data class CommConfig(
    val protocol: CommProtocol = CommProtocol.MODBUS_TCP,
    val ipAddress: String = "192.168.1.100",
    val port: Int = 502,
    val serialPort: String = "/dev/ttyUSB0",
    val baudRate: BaudRate = BaudRate.B115200,
    val deviceId: Int = 1,
    val timeoutMs: Long = 2000
)

/** 通讯配置 ViewModel。 */
class CommViewModel : ViewModel() {
    private val _config = MutableStateFlow(CommConfig())
    val config: StateFlow<CommConfig> = _config.asStateFlow()
    fun update(fn: (CommConfig) -> CommConfig) { _config.value = fn(_config.value) }

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()
    fun testConnection() { _testResult.value = "⚠ 模拟设备无真实网络接口，连接测试为模拟成功。" }
}
